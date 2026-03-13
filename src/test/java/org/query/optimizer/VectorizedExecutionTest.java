package org.query.optimizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Attribute;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.Tuple;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.executor.Executor.ExecutionResult;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalPlanBuilder;
import org.query.optimizer.parser.SQLParser;
import org.query.optimizer.physical.PhysicalNode;
import org.query.optimizer.physical.PhysicalPlanBuilder;
import org.query.optimizer.rules.FilterMerge;
import org.query.optimizer.rules.PredicatePushdown;
import org.query.optimizer.rules.ProjectionPushdown;
import org.query.optimizer.util.DataGenerator;
import org.query.optimizer.vectorized.VectorizedExecutor;
import org.query.optimizer.vectorized.VectorizedOperator;
import org.query.optimizer.vectorized.VectorizedPlanBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctness tests for the five demo queries in {@link VectorizedExecutionDemo}
 * (Phase 5, task 5.5).
 *
 * <p>This test class exists solely to ensure the demo doesn't bitrot: if any
 * operator, plan-builder, or optimisation rule change breaks a demo query the
 * failure will be caught here, in the normal test suite, before anyone notices a
 * silent regression in the demo output.
 *
 * <h2>Strategy</h2>
 * <ul>
 *   <li>Q1–Q4 (scan, filter, filter+project, join): both engines are run and their
 *       result sets compared order-independently.</li>
 *   <li>Q5 (aggregation): the vectorized engine is run and its output is validated
 *       against known invariants (group count ≤ 5, total count = product count)
 *       because the Volcano engine does not yet support {@code LogicalAggregate}.</li>
 * </ul>
 *
 * <h2>Dataset</h2>
 * <p>Uses the same scale as the demo: 500 customers, 1 000 products, 5 000 orders,
 * generated deterministically via {@link DataGenerator}.
 */
public class VectorizedExecutionTest {

    // -------------------------------------------------------------------------
    // Demo dataset scale (must match VectorizedExecutionDemo constants)
    // -------------------------------------------------------------------------

    private static final int CUSTOMERS = 500;
    private static final int PRODUCTS = 1_000;
    private static final int ORDERS = 5_000;

    // -------------------------------------------------------------------------
    // Demo SQL queries (must match VectorizedExecutionDemo constants)
    // -------------------------------------------------------------------------

    private static final String SQL_SCAN =
            "SELECT id, name, category, price FROM products";

    private static final String SQL_FILTER =
            "SELECT id, name, category, price FROM products WHERE price > 500";

    private static final String SQL_PROJECT =
            "SELECT name FROM products WHERE price > 500";

    private static final String SQL_JOIN =
            "SELECT orders.id, orders.total, customers.name " +
                    "FROM orders " +
                    "JOIN customers ON orders.customer_id = customers.id " +
                    "WHERE orders.total > 200";

    private static final String SQL_AGG =
            "SELECT category, COUNT(id) FROM products GROUP BY category";

    // -------------------------------------------------------------------------
    // Shared infrastructure
    // -------------------------------------------------------------------------

    private static final Path DATA_DIR =
            Paths.get("target/generated-test-resources/demo-correctness");

    private static Catalog catalog;
    private static SQLParser parser;
    private static LogicalPlanBuilder logicalBuilder;
    private static PhysicalPlanBuilder physicalBuilder;
    private static VectorizedPlanBuilder vecBuilder;
    private static Executor volcanoExec;
    private static VectorizedExecutor vecExec;
    private static RuleEngine optimizer;

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    @BeforeAll
    static void generateAndLoadData() throws IOException {
        DataGenerator gen = new DataGenerator(CUSTOMERS, PRODUCTS, ORDERS);
        gen.writeAll(DATA_DIR);

        catalog = new Catalog();
        catalog.loadTableFromCSV("customers", DATA_DIR.resolve("customers.csv").toString());
        catalog.loadTableFromCSV("products", DATA_DIR.resolve("products.csv").toString());
        catalog.loadTableFromCSV("orders", DATA_DIR.resolve("orders.csv").toString());

        parser = new SQLParser();
        logicalBuilder = new LogicalPlanBuilder(catalog);
        physicalBuilder = new PhysicalPlanBuilder(catalog);
        vecBuilder = new VectorizedPlanBuilder(catalog);
        volcanoExec = new Executor();
        vecExec = new VectorizedExecutor();
        optimizer = new RuleEngine(List.of(
                new PredicatePushdown(),
                new ProjectionPushdown(),
                new FilterMerge()));
    }

    @AfterAll
    static void cleanup() throws IOException {
        if (Files.exists(DATA_DIR)) {
            try (Stream<Path> paths = Files.walk(DATA_DIR)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        }
    }

    // -------------------------------------------------------------------------
    // Q1 — Scan-only
    // -------------------------------------------------------------------------

    @Test
    void testScanOnlyVectorizedMatchesVolcano() {
        assertVectorizedMatchesVolcano(SQL_SCAN);
    }

    @Test
    void testScanOnlyReturnsAllProductRows() {
        ExecutionResult result = runVectorized(SQL_SCAN);
        assertEquals(PRODUCTS, result.getResultCount(),
                "Full scan must return all " + PRODUCTS + " product rows");
    }

    @Test
    void testScanOnlyOutputHasFourColumns() {
        ExecutionResult result = runVectorized(SQL_SCAN);
        assertFalse(result.tuples().isEmpty());
        assertEquals(4, result.tuples().getFirst().size(),
                "SELECT id, name, category, price should produce 4 columns");
    }

    // -------------------------------------------------------------------------
    // Q2 — Scan + Filter
    // -------------------------------------------------------------------------

    @Test
    void testScanFilterVectorizedMatchesVolcano() {
        assertVectorizedMatchesVolcano(SQL_FILTER);
    }

    @Test
    void testScanFilterRowCountWithinExpectedRange() {
        // price uniform in [5, 2000]; price > 500 → roughly 75 % of rows pass
        ExecutionResult result = runVectorized(SQL_FILTER);
        assertTrue(result.getResultCount() > 0,
                "At least one product should have price > 500");
        assertTrue(result.getResultCount() <= PRODUCTS,
                "Filtered result cannot exceed total product count");
    }

    @Test
    void testScanFilterAllPassingRowsSatisfyPredicate() {
        ExecutionResult result = runVectorized(SQL_FILTER);
        // Every returned row must have price > 500; price is the 4th column (index 3)
        for (Tuple tuple : result.tuples()) {
            Object priceObj = tuple.stream().skip(3).findFirst()
                    .map(AbstractMap.SimpleEntry::getValue).orElse(null);
            assertNotNull(priceObj, "price column must not be null");
            float price = ((Number) priceObj).floatValue();
            assertTrue(price > 500f,
                    "Row with price=" + price + " should not pass price > 500");
        }
    }

    // -------------------------------------------------------------------------
    // Q3 — Scan + Filter + Project
    // -------------------------------------------------------------------------

    @Test
    void testScanFilterProjectVectorizedMatchesVolcano() {
        assertVectorizedMatchesVolcano(SQL_PROJECT);
    }

    @Test
    void testScanFilterProjectOutputHasOneColumn() {
        ExecutionResult result = runVectorized(SQL_PROJECT);
        assertFalse(result.tuples().isEmpty());
        assertEquals(1, result.tuples().getFirst().size(),
                "SELECT name should produce exactly 1 column");
    }

    @Test
    void testScanFilterProjectRowCountMatchesScanFilter() {
        // Q3 is Q2 projected down — row counts must be equal
        ExecutionResult filterResult = runVectorized(SQL_FILTER);
        ExecutionResult projectResult = runVectorized(SQL_PROJECT);
        assertEquals(filterResult.getResultCount(), projectResult.getResultCount(),
                "Filter+Project must return the same number of rows as Filter alone");
    }

    // -------------------------------------------------------------------------
    // Q4 — Hash Join with filter
    // -------------------------------------------------------------------------

    @Test
    void testHashJoinVectorizedMatchesVolcano() {
        assertVectorizedMatchesVolcano(SQL_JOIN);
    }

    @Test
    void testHashJoinRowCountIsPositive() {
        ExecutionResult result = runVectorized(SQL_JOIN);
        assertTrue(result.getResultCount() > 0,
                "At least one order should have total > 200");
    }

    @Test
    void testHashJoinRowCountDoesNotExceedOrderCount() {
        ExecutionResult result = runVectorized(SQL_JOIN);
        assertTrue(result.getResultCount() <= ORDERS,
                "Join output cannot exceed the number of input orders");
    }

    @Test
    void testHashJoinOutputHasThreeColumns() {
        ExecutionResult result = runVectorized(SQL_JOIN);
        assertFalse(result.tuples().isEmpty());
        assertEquals(3, result.tuples().getFirst().size(),
                "SELECT orders.id, orders.total, customers.name must produce 3 columns");
    }

    @Test
    void testHashJoinAllReturnedOrdersSatisfyFilter() {
        ExecutionResult result = runVectorized(SQL_JOIN);
        // total is the 2nd column (index 1) in (orders.id, orders.total, customers.name)
        for (Tuple tuple : result.tuples()) {
            Object totalObj = tuple.stream().skip(1).findFirst()
                    .map(AbstractMap.SimpleEntry::getValue).orElse(null);
            assertNotNull(totalObj, "total column must not be null");
            float total = ((Number) totalObj).floatValue();
            assertTrue(total > 200f,
                    "Row with total=" + total + " should not pass total > 200");
        }
    }

    // -------------------------------------------------------------------------
    // Q5 — Aggregation (vectorized-only)
    // -------------------------------------------------------------------------

    @Test
    void testAggregationProducesAtMostFiveCategoryGroups() {
        // DataGenerator uses exactly 5 categories
        ExecutionResult result = runVectorized(SQL_AGG);
        assertTrue(result.getResultCount() > 0,
                "Aggregation must produce at least one group");
        assertTrue(result.getResultCount() <= 5,
                "There are only 5 distinct product categories");
    }

    @Test
    void testAggregationGroupCountsSumToTotalProductCount() {
        ExecutionResult result = runVectorized(SQL_AGG);
        // COUNT column is the second one
        int totalCount = result.tuples().stream()
                .mapToInt(t -> {
                    Object v = t.stream().skip(1).findFirst()
                            .map(AbstractMap.SimpleEntry::getValue).orElse(0);
                    return v instanceof Integer i ? i : ((Number) v).intValue();
                })
                .sum();
        assertEquals(PRODUCTS, totalCount,
                "Sum of all category group counts must equal total number of products");
    }

    @Test
    void testAggregationEachGroupHasPositiveCount() {
        ExecutionResult result = runVectorized(SQL_AGG);
        for (Tuple tuple : result.tuples()) {
            Object v = tuple.stream().skip(1).findFirst()
                    .map(AbstractMap.SimpleEntry::getValue).orElse(0);
            int count = v instanceof Integer i ? i : ((Number) v).intValue();
            assertTrue(count > 0, "Every GROUP BY group must have count > 0");
        }
    }

    @Test
    void testAggregationOutputHasTwoColumns() {
        ExecutionResult result = runVectorized(SQL_AGG);
        assertFalse(result.tuples().isEmpty());
        assertEquals(2, result.tuples().getFirst().size(),
                "SELECT category, COUNT(id) must produce 2 columns");
    }

    // -------------------------------------------------------------------------
    // Demo does not throw (smoke test)
    // -------------------------------------------------------------------------

    @Test
    void testVectorizedExecutionDemoRunsWithoutException() {
        assertDoesNotThrow(() -> VectorizedExecutionDemo.main(new String[0]),
                "VectorizedExecutionDemo.main() must complete without throwing");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertVectorizedMatchesVolcano(String sql) {
        ExecutionResult volcanoResult = runVolcano(sql);
        ExecutionResult vecResult = runVectorized(sql);

        assertEquals(volcanoResult.getResultCount(), vecResult.getResultCount(),
                "Row count mismatch for: " + sql);
        assertTrue(sameResultSet(volcanoResult.tuples(), vecResult.tuples()),
                "Result set mismatch for: " + sql);
    }

    private ExecutionResult runVolcano(String sql) {
        PhysicalNode plan = physicalBuilder.build(optimise(sql));
        return volcanoExec.execute(plan);
    }

    private ExecutionResult runVectorized(String sql) {
        VectorizedOperator plan = vecBuilder.build(optimise(sql));
        return vecExec.execute(plan);
    }

    private LogicalNode optimise(String sql) {
        return optimizer.optimize(logicalBuilder.build(parser.parse(sql)));
    }

    public static boolean sameResultSet(List<Tuple> r1, List<Tuple> r2) {
        if (r1.size() != r2.size()) return false;

        List<Tuple> l1 = r1.stream().map(VectorizedExecutionTest::normalizeTuple).toList();
        List<Tuple> l2 = r2.stream().map(VectorizedExecutionTest::normalizeTuple).toList();

        List<Tuple> s1 = new ArrayList<>(l1);
        List<Tuple> s2 = new ArrayList<>(l2);

        s1.sort(VectorizedExecutionTest::compareTuple);
        s2.sort(VectorizedExecutionTest::compareTuple);

        for (int i = 0; i < 10; i++)
            System.out.println(s1.get(i));
        for (int i = 0; i < 10; i++)
            System.out.println(s2.get(i));

        return s1.equals(s2);
    }

    private static Tuple normalizeTuple(Tuple t) {
        Tuple copy = new Tuple();
        copy.addAll(t);
        copy.sort(Comparator.comparing((Attribute a) -> a.getKey().name()));

        return copy;
    }

    private static int compareTuple(Tuple t1, Tuple t2) {
        int sizeCompare = Integer.compare(t1.size(), t2.size());
        if (sizeCompare != 0) return sizeCompare;

        for (int i = 0; i < t1.size(); i++) {
            Attribute a1 = t1.get(i);
            Attribute a2 = t2.get(i);
            int c;
            c = a1.getKey().name().compareTo(a2.getKey().name());
            if (c != 0) return c;
            c = a1.getKey().type().name().compareTo(a2.getKey().type().name());
            if (c != 0) return c;
            c = String.valueOf(a1.getValue()).compareTo(String.valueOf(a2.getValue()));
            if (c != 0) return c;
        }

        return 0;
    }
}
