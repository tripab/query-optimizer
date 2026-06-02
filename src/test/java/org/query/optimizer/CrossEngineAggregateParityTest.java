package org.query.optimizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import org.query.optimizer.vectorized.VectorizedExecutor;
import org.query.optimizer.vectorized.VectorizedOperator;
import org.query.optimizer.vectorized.VectorizedPlanBuilder;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-engine aggregate parity tests (Phase 2, task P2-2).
 *
 * <h2>Strategy</h2>
 * <p>Each aggregate SQL query is parsed once, rule-optimised, and the resulting
 * logical plan is realised by <em>both</em> physical engines:
 * <ul>
 *   <li>{@link PhysicalPlanBuilder} → Volcano iterator plan (now including
 *       {@code PhysicalAggregate}, added in P2-1)</li>
 *   <li>{@link VectorizedPlanBuilder} → vectorized batch plan</li>
 * </ul>
 * Both result sets are compared row-for-row, order-independently. This is the
 * strongest available correctness check: rather than asserting against
 * hand-computed values, it asserts the two independent implementations agree.
 *
 * <p>The parser does not support {@code AS} aliases for aggregate items, so
 * aggregate output columns use the parser's auto-generated names
 * ({@code count_*}, {@code sum_amount}, …). That naming is irrelevant to the
 * comparison because both engines share the same logical plan.
 *
 * <h2>Test data</h2>
 * <pre>
 *   customers : id, name, city, age
 *   orders    : id, customer_id, amount, status
 *   products  : id, name, category, price
 * </pre>
 */
class CrossEngineAggregateParityTest {

    private static final Catalog catalog = new Catalog();
    private static final SQLParser parser = new SQLParser();
    private static final LogicalPlanBuilder logicalBuilder = new LogicalPlanBuilder(catalog);
    private static final PhysicalPlanBuilder volcanoBuilder = new PhysicalPlanBuilder(catalog);
    private static final VectorizedPlanBuilder vecBuilder = new VectorizedPlanBuilder(catalog);
    private static final Executor volcanoExec = new Executor();
    private static final VectorizedExecutor vecExec = new VectorizedExecutor();

    private static final RuleEngine optimizer = new RuleEngine(List.of(
            new PredicatePushdown(),
            new FilterMerge(),
            new ProjectionPushdown()
    ));

    private static final String DATA_DIR = "target/generated-test-resources/agg-parity";

    // -------------------------------------------------------------------------
    // Test data
    // -------------------------------------------------------------------------

    @BeforeAll
    static void setup() throws IOException {
        Files.createDirectories(Paths.get(DATA_DIR));

        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "customers.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,city:VARCHAR,age:INTEGER");
            pw.println("1,Alice,Seattle,32");
            pw.println("2,Bob,Portland,28");
            pw.println("3,Carol,Seattle,35");
            pw.println("4,Dave,Denver,41");
            pw.println("5,Eve,Portland,29");
            pw.println("6,Frank,Seattle,38");
        }
        catalog.loadTableFromCSV("customers", DATA_DIR + "/customers.csv");

        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "orders.csv"))) {
            pw.println("id:INTEGER,customer_id:INTEGER,amount:FLOAT,status:VARCHAR");
            pw.println("1,1,150.0,open");
            pw.println("2,1,80.0,closed");
            pw.println("3,2,200.0,open");
            pw.println("4,3,120.0,open");
            pw.println("5,4,90.0,closed");
            pw.println("6,5,180.0,open");
            pw.println("7,1,110.0,open");
            pw.println("8,3,95.0,closed");
            pw.println("9,6,300.0,open");
            pw.println("10,2,55.0,closed");
        }
        catalog.loadTableFromCSV("orders", DATA_DIR + "/orders.csv");

        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "products.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,category:VARCHAR,price:FLOAT");
            pw.println("1,Laptop,Electronics,999.99");
            pw.println("2,Mouse,Electronics,29.99");
            pw.println("3,Keyboard,Electronics,79.99");
            pw.println("4,Desk,Furniture,299.99");
            pw.println("5,Chair,Furniture,199.99");
            pw.println("6,Notebook,Stationery,9.99");
            pw.println("7,Pen,Stationery,2.99");
        }
        catalog.loadTableFromCSV("products", DATA_DIR + "/products.csv");
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.deleteIfExists(Paths.get(DATA_DIR + "/customers.csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR + "/orders.csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR + "/products.csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Runs an aggregate SQL query through both engines and asserts the result
     * sets are identical (order-independent), returning the shared row count so
     * callers can additionally assert the expected number of groups.
     */
    private int assertEnginesAgree(String sql) {
        LogicalNode logicalForVolcano = optimizer.optimize(logicalBuilder.build(parser.parse(sql)));
        LogicalNode logicalForVec     = optimizer.optimize(logicalBuilder.build(parser.parse(sql)));

        PhysicalNode physical = volcanoBuilder.build(logicalForVolcano);
        ExecutionResult volcanoResult = volcanoExec.execute(physical);

        VectorizedOperator vecPlan = vecBuilder.build(logicalForVec);
        ExecutionResult vecResult = vecExec.execute(vecPlan);

        assertEquals(volcanoResult.getResultCount(), vecResult.getResultCount(),
                "Row count mismatch for: " + sql);
        assertEquals(serialise(volcanoResult.tuples()), serialise(vecResult.tuples()),
                "Result set mismatch for: " + sql);

        return volcanoResult.getResultCount();
    }

    private static Set<String> serialise(List<Tuple> tuples) {
        return tuples.stream().map(t -> t.stream()
                        .sorted(Comparator.comparing(a -> a.getKey().name()))
                        .map(a -> a.getKey().name() + "=" + a.getValue())
                        .collect(Collectors.joining("|")))
                .collect(Collectors.toSet());
    }

    // =========================================================================
    // Global aggregation (no GROUP BY)
    // =========================================================================

    @Test
    void testGlobalCountStarAgrees() {
        // One row, count of all 10 orders
        assertEquals(1, assertEnginesAgree("SELECT COUNT(*) FROM orders"));
    }

    @Test
    void testGlobalSumAgrees() {
        assertEquals(1, assertEnginesAgree("SELECT SUM(amount) FROM orders"));
    }

    @Test
    void testGlobalAvgAgrees() {
        assertEquals(1, assertEnginesAgree("SELECT AVG(price) FROM products"));
    }

    @Test
    void testGlobalMinMaxAgrees() {
        assertEquals(1, assertEnginesAgree("SELECT MIN(price), MAX(price) FROM products"));
    }

    // =========================================================================
    // Single-column GROUP BY — each function
    // =========================================================================

    @Test
    void testCountStarGroupByCityAgrees() {
        // Seattle, Portland, Denver
        assertEquals(3, assertEnginesAgree("SELECT city, COUNT(*) FROM customers GROUP BY city"));
    }

    @Test
    void testSumGroupByCustomerAgrees() {
        // 6 distinct customers in orders
        assertEquals(6, assertEnginesAgree(
                "SELECT customer_id, SUM(amount) FROM orders GROUP BY customer_id"));
    }

    @Test
    void testAvgGroupByCategoryAgrees() {
        // Electronics, Furniture, Stationery
        assertEquals(3, assertEnginesAgree(
                "SELECT category, AVG(price) FROM products GROUP BY category"));
    }

    @Test
    void testMinMaxGroupByCategoryAgrees() {
        assertEquals(3, assertEnginesAgree(
                "SELECT category, MIN(price), MAX(price) FROM products GROUP BY category"));
    }

    // =========================================================================
    // Multiple aggregates on the same GROUP BY
    // =========================================================================

    @Test
    void testMultipleAggregatesGroupByCityAgrees() {
        assertEquals(3, assertEnginesAgree(
                "SELECT city, COUNT(*), MIN(age), MAX(age) FROM customers GROUP BY city"));
    }

    // =========================================================================
    // Aggregate over filtered input (predicate pushdown still feeds aggregate)
    // =========================================================================

    @Test
    void testCountGroupByCustomerOnOpenOrdersAgrees() {
        // Only open orders: customers 1,2,3,5,6 have open orders → 5 groups
        assertEquals(5, assertEnginesAgree(
                "SELECT customer_id, COUNT(*) FROM orders WHERE status = 'open' GROUP BY customer_id"));
    }

    @Test
    void testSumGroupByCategoryOnFilteredPriceAgrees() {
        // price > 50 keeps Laptop, Keyboard, Desk, Chair → Electronics + Furniture
        assertEquals(2, assertEnginesAgree(
                "SELECT category, SUM(price) FROM products WHERE price > 50 GROUP BY category"));
    }

    // =========================================================================
    // Multi-column GROUP BY
    // =========================================================================

    @Test
    void testMultiColumnGroupByAgrees() {
        // Each (city, age) pair is distinct in our data → 6 groups
        int groups = assertEnginesAgree(
                "SELECT city, age, COUNT(*) FROM customers GROUP BY city, age");
        assertTrue(groups >= 1, "Expected at least one group");
    }
}
