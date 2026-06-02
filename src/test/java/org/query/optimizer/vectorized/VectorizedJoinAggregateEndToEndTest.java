package org.query.optimizer.vectorized;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.RuleEngine;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.Tuple;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.executor.Executor.ExecutionResult;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalAggregate.AggFunction;
import org.query.optimizer.parser.LogicalAggregate.AggregateOp;
import org.query.optimizer.parser.LogicalPlanBuilder;
import org.query.optimizer.parser.SQLParser;
import org.query.optimizer.physical.PhysicalNode;
import org.query.optimizer.physical.PhysicalPlanBuilder;
import org.query.optimizer.rules.FilterMerge;
import org.query.optimizer.rules.PredicatePushdown;
import org.query.optimizer.rules.ProjectionPushdown;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end correctness tests for join and aggregate queries (Phase 3, task 3.5).
 *
 * <h2>Strategy</h2>
 * <p>Join queries are verified by running the same SQL through both the Volcano iterator
 * engine and the vectorized engine and asserting identical result sets (order-independent).
 * This reuses the pattern established in {@link VectorizedEndToEndTest}.
 *
 * <p>The aggregate tests below assert against hand-computed expected values derived
 * from the test data, providing an independent oracle for the vectorized engine.
 * Direct Volcano-vs-vectorized aggregate parity (now that {@link PhysicalPlanBuilder}
 * implements {@code LogicalAggregate}) is covered separately in
 * {@code CrossEngineAggregateParityTest}.
 *
 * <h2>Test data</h2>
 * <pre>
 *   customers : id, name, city, age
 *   orders    : id, customer_id, amount, status
 *   products  : id, name, category, price
 * </pre>
 * All three tables are kept small enough to reason about manually, while large enough to
 * exercise multi-row joins and non-trivial group counts.
 */
class VectorizedJoinAggregateEndToEndTest {

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

    private static final String DATA_DIR = "target/generated-test-resources/join-agg";

    // -------------------------------------------------------------------------
    // Test data
    // -------------------------------------------------------------------------

    @BeforeAll
    static void setup() throws IOException {
        Files.createDirectories(Paths.get(DATA_DIR));

        // customers: id INTEGER, name VARCHAR, city VARCHAR, age INTEGER
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

        // orders: id INTEGER, customer_id INTEGER, amount FLOAT, status VARCHAR
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

        // products: id INTEGER, name VARCHAR, category VARCHAR, price FLOAT
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
     * Runs SQL through both engines and asserts identical order-independent result sets.
     */
    private void assertVectorizedMatchesVolcano(String sql) {
        LogicalNode logical1 = optimizer.optimize(logicalBuilder.build(parser.parse(sql)));
        LogicalNode logical2 = optimizer.optimize(logicalBuilder.build(parser.parse(sql)));

        PhysicalNode physical = volcanoBuilder.build(logical1);
        ExecutionResult volcanoResult = volcanoExec.execute(physical);

        VectorizedOperator vecPlan = vecBuilder.build(logical2);
        ExecutionResult vecResult = vecExec.execute(vecPlan);

        assertEquals(volcanoResult.getResultCount(), vecResult.getResultCount(),
                "Row count mismatch for: " + sql);
        assertEquals(serialise(volcanoResult.tuples()), serialise(vecResult.tuples()),
                "Result set mismatch for: " + sql);
    }

    private static Set<String> serialise(List<Tuple> tuples) {
        return tuples.stream().map(t -> t.stream()
                        .sorted(Comparator.comparing(a -> a.getKey().name()))
                        .map(a -> a.getKey().name() + "=" + a.getValue())
                        .collect(Collectors.joining("|")))
                .collect(Collectors.toSet());
    }

    /**
     * Executes SQL via the vectorized engine only, returning all result rows.
     */
    private List<Object[]> execVec(String sql) {
        LogicalNode logical = optimizer.optimize(logicalBuilder.build(parser.parse(sql)));
        VectorizedOperator plan = vecBuilder.build(logical);
        plan.open();
        List<Object[]> rows = new java.util.ArrayList<>();
        ColumnBatch batch;
        Schema schema = plan.getOutputSchema();
        while ((batch = plan.next()) != null) {
            int cols = schema.columnCount();
            int size = batch.getSize();
            for (int r = 0; r < size; r++) {
                Object[] row = new Object[cols];
                for (int c = 0; c < cols; c++) row[c] = batch.getVector(c).get(r);
                rows.add(row);
            }
        }
        plan.close();
        return rows;
    }

    /**
     * Executes a manually-constructed VectorizedAggregate plan.
     */
    private List<Object[]> execAgg(VectorizedOperator plan) {
        plan.open();
        List<Object[]> rows = new java.util.ArrayList<>();
        ColumnBatch batch;
        Schema schema = plan.getOutputSchema();
        while ((batch = plan.next()) != null) {
            for (int r = 0; r < batch.getSize(); r++) {
                Object[] row = new Object[schema.columnCount()];
                for (int c = 0; c < schema.columnCount(); c++) row[c] = batch.getVector(c).get(r);
                rows.add(row);
            }
        }
        plan.close();
        return rows;
    }

    // =========================================================================
    // JOIN end-to-end tests (vectorized == Volcano)
    // =========================================================================

    @Test
    void testInnerJoinCustomersOrdersMatchesVolcano() {
        assertVectorizedMatchesVolcano(
                "SELECT name, amount FROM customers c " +
                        "INNER JOIN orders o ON c.id = o.customer_id");
    }

    @Test
    void testInnerJoinWithFilterOnProbeMatchesVolcano() {
        // Only Seattle customers (ids 1, 3, 6)
        assertVectorizedMatchesVolcano(
                "SELECT name, amount FROM customers c " +
                        "INNER JOIN orders o ON c.id = o.customer_id " +
                        "WHERE c.city = 'Seattle'");
    }

    @Test
    void testInnerJoinWithFilterOnBuildMatchesVolcano() {
        // Only open orders
        assertVectorizedMatchesVolcano(
                "SELECT name, amount FROM customers c " +
                        "INNER JOIN orders o ON c.id = o.customer_id " +
                        "WHERE o.status = 'open'");
    }

    @Test
    void testInnerJoinWithFilterOnBothSidesMatchesVolcano() {
        assertVectorizedMatchesVolcano(
                "SELECT name, amount FROM customers c " +
                        "INNER JOIN orders o ON c.id = o.customer_id " +
                        "WHERE c.age > 30 AND o.amount > 100");
    }

    @Test
    void testInnerJoinRowCountIsCorrect() {
        // customers 1,2,3,4,5,6 → orders: 1→3 orders, 2→2, 3→2, 4→1, 5→1, 6→1 = 10 rows
        assertVectorizedMatchesVolcano(
                "SELECT name, amount FROM customers c " +
                        "INNER JOIN orders o ON c.id = o.customer_id");

        LogicalNode logical = optimizer.optimize(
                logicalBuilder.build(parser.parse(
                        "SELECT name, amount FROM customers c " +
                                "INNER JOIN orders o ON c.id = o.customer_id")));
        ExecutionResult result = vecExec.execute(vecBuilder.build(logical));
        assertEquals(10, result.getResultCount());
    }

    @Test
    void testInnerJoinWithProjectionMatchesVolcano() {
        assertVectorizedMatchesVolcano(
                "SELECT name FROM customers c " +
                        "INNER JOIN orders o ON c.id = o.customer_id " +
                        "WHERE o.amount > 150");
    }

    @Test
    void testJoinProducesCorrectPairings() {
        // Alice (id=1) has orders 1(150), 2(80), 7(110)
        LogicalNode logical = optimizer.optimize(
                logicalBuilder.build(parser.parse(
                        "SELECT name, amount FROM customers c " +
                                "INNER JOIN orders o ON c.id = o.customer_id " +
                                "WHERE c.name = 'Alice'")));
        ExecutionResult result = vecExec.execute(vecBuilder.build(logical));
        assertEquals(3, result.getResultCount());

        Set<String> amounts = result.tuples().stream()
                .map(t -> t.find(catalog.getTableMetadata("orders")
                        .getSchema().getColumn("amount")).toString())
                .collect(Collectors.toSet());
        assertTrue(amounts.contains("150.0"));
        assertTrue(amounts.contains("80.0"));
        assertTrue(amounts.contains("110.0"));
    }

    // =========================================================================
    // AGGREGATE end-to-end tests (known expected values)
    //
    // These verify the vectorized engine against hand-computed expected values
    // from the test data above, serving as an independent oracle. Volcano now
    // implements LogicalAggregate; direct cross-engine parity lives in
    // CrossEngineAggregateParityTest.
    // =========================================================================

    @Test
    void testCountStarGroupByCity() {
        // Expected: Denver=1, Portland=2, Seattle=3
        VectorizedScan scan = new VectorizedScan("customers", catalog);
        VectorizedAggregate agg = new VectorizedAggregate(
                scan,
                List.of("city"),
                List.of(new AggregateOp(AggFunction.COUNT, "*", "cnt")));

        List<Object[]> rows = execAgg(agg);
        assertEquals(3, rows.size());

        Map<String, Integer> counts = new HashMap<>();
        for (Object[] r : rows) counts.put((String) r[0], (Integer) r[1]);
        assertEquals(1, counts.get("Denver"));
        assertEquals(2, counts.get("Portland"));
        assertEquals(3, counts.get("Seattle"));
    }

    @Test
    void testSumAmountGroupByCustomerId() {
        // customer 1: 150+80+110=340, customer 2: 200+55=255, customer 3: 120+95=215,
        // customer 4: 90, customer 5: 180, customer 6: 300
        VectorizedScan scan = new VectorizedScan("orders", catalog);
        VectorizedAggregate agg = new VectorizedAggregate(
                scan,
                List.of("customer_id"),
                List.of(new AggregateOp(AggFunction.SUM, "amount", "total")));

        List<Object[]> rows = execAgg(agg);
        assertEquals(6, rows.size());

        Map<Integer, Float> sums = new HashMap<>();
        for (Object[] r : rows) sums.put((Integer) r[0], (Float) r[1]);
        assertEquals(340.0f, sums.get(1), 0.01f);
        assertEquals(255.0f, sums.get(2), 0.01f);
        assertEquals(215.0f, sums.get(3), 0.01f);
        assertEquals(90.0f, sums.get(4), 0.01f);
        assertEquals(180.0f, sums.get(5), 0.01f);
        assertEquals(300.0f, sums.get(6), 0.01f);
    }

    @Test
    void testMinMaxPriceGroupByCategory() {
        // Electronics: min=29.99, max=999.99
        // Furniture:   min=199.99, max=299.99
        // Stationery:  min=2.99,  max=9.99
        VectorizedScan scan = new VectorizedScan("products", catalog);
        VectorizedAggregate agg = new VectorizedAggregate(
                scan,
                List.of("category"),
                List.of(new AggregateOp(AggFunction.MIN, "price", "min_p"),
                        new AggregateOp(AggFunction.MAX, "price", "max_p")));

        List<Object[]> rows = execAgg(agg);
        assertEquals(3, rows.size());

        Map<String, float[]> bounds = new HashMap<>();
        for (Object[] r : rows) bounds.put((String) r[0], new float[]{(Float) r[1], (Float) r[2]});

        assertEquals(29.99f, bounds.get("Electronics")[0], 0.01f);
        assertEquals(999.99f, bounds.get("Electronics")[1], 0.01f);
        assertEquals(199.99f, bounds.get("Furniture")[0], 0.01f);
        assertEquals(299.99f, bounds.get("Furniture")[1], 0.01f);
        assertEquals(2.99f, bounds.get("Stationery")[0], 0.01f);
        assertEquals(9.99f, bounds.get("Stationery")[1], 0.01f);
    }

    @Test
    void testAvgPriceGroupByCategory() {
        // Electronics avg: (999.99+29.99+79.99)/3 = 369.99
        // Furniture avg:   (299.99+199.99)/2 = 249.99
        // Stationery avg:  (9.99+2.99)/2 = 6.49
        VectorizedScan scan = new VectorizedScan("products", catalog);
        VectorizedAggregate agg = new VectorizedAggregate(
                scan,
                List.of("category"),
                List.of(new AggregateOp(AggFunction.AVG, "price", "avg_p")));

        List<Object[]> rows = execAgg(agg);
        Map<String, Float> avgs = new HashMap<>();
        for (Object[] r : rows) avgs.put((String) r[0], (Float) r[1]);

        assertEquals(369.99f, avgs.get("Electronics"), 0.1f);
        assertEquals(249.99f, avgs.get("Furniture"), 0.1f);
        assertEquals(6.49f, avgs.get("Stationery"), 0.1f);
    }

    @Test
    void testCountOpenOrdersGroupByCustomerAfterFilter() {
        // Filter: status = 'open' → orders 1,3,4,6,7,9
        // customer 1: 2 open, customer 2: 1, customer 3: 1, customer 5: 1, customer 6: 1
        VectorizedFilter filter = new VectorizedFilter(
                new VectorizedScan("orders", catalog),
                new org.query.optimizer.logical.Expression.BinaryOp(
                        org.query.optimizer.logical.Expression.BinaryOp.Operator.EQ,
                        org.query.optimizer.logical.Expression.ColumnRef.from("status"),
                        new org.query.optimizer.logical.Expression.Literal<>("open")));

        VectorizedAggregate agg = new VectorizedAggregate(
                filter,
                List.of("customer_id"),
                List.of(new AggregateOp(AggFunction.COUNT, "*", "open_cnt")));

        List<Object[]> rows = execAgg(agg);
        assertEquals(5, rows.size());

        Map<Integer, Integer> openCounts = new HashMap<>();
        for (Object[] r : rows) openCounts.put((Integer) r[0], (Integer) r[1]);
        assertEquals(2, openCounts.get(1));
        assertEquals(1, openCounts.get(2));
        assertEquals(1, openCounts.get(3));
        assertEquals(1, openCounts.get(5));
        assertEquals(1, openCounts.get(6));
    }

    @Test
    void testGlobalCountAllOrders() {
        VectorizedScan scan = new VectorizedScan("orders", catalog);
        VectorizedAggregate agg = new VectorizedAggregate(
                scan,
                List.of(),
                List.of(new AggregateOp(AggFunction.COUNT, "*", "total")));

        List<Object[]> rows = execAgg(agg);
        assertEquals(1, rows.size());
        assertEquals(10, rows.get(0)[0]);
    }

    @Test
    void testGlobalSumAllOrderAmounts() {
        // 150+80+200+120+90+180+110+95+300+55 = 1380
        VectorizedScan scan = new VectorizedScan("orders", catalog);
        VectorizedAggregate agg = new VectorizedAggregate(
                scan,
                List.of(),
                List.of(new AggregateOp(AggFunction.SUM, "amount", "grand_total")));

        List<Object[]> rows = execAgg(agg);
        assertEquals(1, rows.size());
        assertEquals(1380.0f, (Float) rows.get(0)[0], 0.01f);
    }

    @Test
    void testMultiColumnGroupByStatusAndCityViaJoin() {
        // Join customers → orders, then group by city, status; count rows
        VectorizedHashJoin join = new VectorizedHashJoin(
                new VectorizedScan("customers", catalog),
                new VectorizedScan("orders", catalog),
                new org.query.optimizer.logical.Expression.BinaryOp(
                        org.query.optimizer.logical.Expression.BinaryOp.Operator.EQ,
                        org.query.optimizer.logical.Expression.ColumnRef.from("id"),
                        org.query.optimizer.logical.Expression.ColumnRef.from("customer_id")));

        VectorizedAggregate agg = new VectorizedAggregate(
                join,
                List.of("city", "status"),
                List.of(new AggregateOp(AggFunction.COUNT, "*", "cnt")));

        List<Object[]> rows = execAgg(agg);
        // Possible city+status combos from our data
        assertTrue(rows.size() >= 2, "Expected multiple city+status groups");

        // All counts must be positive
        for (Object[] r : rows) assertTrue((Integer) r[2] > 0);
    }
}
