package org.query.optimizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Estimation-quality regression tests (Phase 3, task P3-3).
 *
 * <h2>Strategy</h2>
 * <p>Two complementary checks lock in the sharper, stabler estimates produced by
 * the {@link SubtreeStatistics} propagation:
 * <ol>
 *   <li><b>Estimate vs. actual (q-error).</b> Each representative query is
 *       optimised, its root row estimate is read, the plan is executed for the
 *       <em>actual</em> row count, and the q-error
 *       ({@code max(est/act, act/est)}) is asserted within a bound. This guards
 *       against estimates drifting away from reality.</li>
 *   <li><b>Monotonicity.</b> Adding a selective filter must never increase an
 *       estimate, a filtered join must not be estimated larger than the same join
 *       unfiltered, and an aggregate's group count stays within
 *       {@code [1, input rows]}.</li>
 * </ol>
 *
 * <h2>Fixtures</h2>
 * <pre>
 *   customers : id(8), city(4: NYC/LA/SF/BOS x2), age(8 distinct)       -- 8 rows
 *   orders    : id(16), customer_id(8, 2 each), product_id(4)           -- 16 rows
 *   products  : id(4)                                                   -- 4 rows
 * </pre>
 * The data is chosen so estimates land very close to actuals; the bound has
 * headroom for the histogram-based range case.
 */
class CardinalityEstimateQualityTest {

    private static final Catalog catalog = new Catalog();
    private static QueryOptimizer optimizer;
    private static final String DATA_DIR = "target/generated-test-resources/p3-quality";

    /** Default q-error bound: estimates must be within 3x of actual (either way). */
    private static final double MAX_Q_ERROR = 3.0;

    @BeforeAll
    static void setup() throws IOException {
        Files.createDirectories(Paths.get(DATA_DIR));

        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "customers.csv"))) {
            pw.println("id:INTEGER,city:VARCHAR,age:INTEGER");
            pw.println("1,NYC,20");
            pw.println("2,NYC,30");
            pw.println("3,LA,40");
            pw.println("4,LA,50");
            pw.println("5,SF,25");
            pw.println("6,SF,35");
            pw.println("7,BOS,45");
            pw.println("8,BOS,55");
        }
        catalog.loadTableFromCSV("customers", DATA_DIR + "/customers.csv");

        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "orders.csv"))) {
            pw.println("id:INTEGER,customer_id:INTEGER,product_id:INTEGER");
            int oid = 1;
            for (int cust = 1; cust <= 8; cust++) {
                pw.println(oid++ + "," + cust + "," + (((cust * 2 - 1) % 4) + 1));
                pw.println(oid++ + "," + cust + "," + (((cust * 2) % 4) + 1));
            }
        }
        catalog.loadTableFromCSV("orders", DATA_DIR + "/orders.csv");

        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "products.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR");
            pw.println("1,A");
            pw.println("2,B");
            pw.println("3,C");
            pw.println("4,D");
        }
        catalog.loadTableFromCSV("products", DATA_DIR + "/products.csv");

        optimizer = new QueryOptimizer(catalog);
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

    private long estimatedRows(String sql) {
        return optimizer.optimize(sql, OptimizationOptions.defaults())
                .optimizedLogicalPlan().getEstimatedRows();
    }

    private long actualRows(String sql) {
        var result = optimizer.optimize(sql, OptimizationOptions.defaults());
        return new Executor().execute(result.physicalPlan()).getResultCount();
    }

    private static double qError(long estimate, long actual) {
        double e = Math.max(1, estimate);
        double a = Math.max(1, actual);
        return Math.max(e / a, a / e);
    }

    private void assertEstimateCloseToActual(String sql, double maxQError) {
        long est = estimatedRows(sql);
        long act = actualRows(sql);
        double q = qError(est, act);
        assertTrue(q <= maxQError,
                () -> String.format("%s%n  estimate=%d actual=%d q-error=%.2f (max %.2f)",
                        sql, est, act, q, maxQError));
    }

    // -------------------------------------------------------------------------
    // Estimate vs. actual (q-error)
    // -------------------------------------------------------------------------

    @Test
    void equalityFilterEstimateIsAccurate() {
        // city = 'NYC' -> 2 of 8 rows; selectivity 1/NDV(city)=1/4 -> est 2.
        assertEstimateCloseToActual(
                "SELECT id, city FROM customers WHERE city = 'NYC'", MAX_Q_ERROR);
    }

    @Test
    void rangeFilterEstimateIsAccurate() {
        // age > 35 -> 4 of 8 rows (histogram-driven).
        assertEstimateCloseToActual(
                "SELECT id FROM customers WHERE age > 35", MAX_Q_ERROR);
    }

    @Test
    void twoTableJoinEstimateIsAccurate() {
        // 8 customers x 16 orders / NDV 8 = 16; actual 16.
        assertEstimateCloseToActual(
                "SELECT customers.id, orders.id FROM customers " +
                        "JOIN orders ON customers.id = orders.customer_id", MAX_Q_ERROR);
    }

    @Test
    void filteredJoinEstimateIsAccurate() {
        // NYC customers (2) joined to their orders (4); actual 4.
        assertEstimateCloseToActual(
                "SELECT customers.id, orders.id FROM customers " +
                        "JOIN orders ON customers.id = orders.customer_id " +
                        "WHERE customers.city = 'NYC'", MAX_Q_ERROR);
    }

    @Test
    void aggregateEstimateIsAccurate() {
        // GROUP BY city -> 4 groups; actual 4.
        assertEstimateCloseToActual(
                "SELECT city, COUNT(id) FROM customers GROUP BY city", MAX_Q_ERROR);
    }

    @Test
    void multiJoinEstimateIsAccurate() {
        // customers x orders x products along the foreign keys; actual 16.
        assertEstimateCloseToActual(
                "SELECT customers.id, orders.id, products.name FROM customers " +
                        "JOIN orders ON customers.id = orders.customer_id " +
                        "JOIN products ON orders.product_id = products.id", MAX_Q_ERROR);
    }

    // -------------------------------------------------------------------------
    // Monotonicity / stability
    // -------------------------------------------------------------------------

    @Test
    void filterDoesNotIncreaseEstimate() {
        long unfiltered = estimatedRows("SELECT id FROM customers");
        long filtered = estimatedRows("SELECT id FROM customers WHERE age > 35");
        assertTrue(filtered <= unfiltered,
                "filter estimate (" + filtered + ") must not exceed unfiltered (" + unfiltered + ")");
    }

    @Test
    void filteredJoinNotEstimatedLargerThanUnfilteredJoin() {
        long unfiltered = estimatedRows(
                "SELECT customers.id, orders.id FROM customers " +
                        "JOIN orders ON customers.id = orders.customer_id");
        long filtered = estimatedRows(
                "SELECT customers.id, orders.id FROM customers " +
                        "JOIN orders ON customers.id = orders.customer_id " +
                        "WHERE customers.city = 'NYC'");
        assertTrue(filtered <= unfiltered,
                "filtered join (" + filtered + ") must not exceed unfiltered join (" + unfiltered + ")");
    }

    @Test
    void aggregateGroupCountStaysWithinInputBounds() {
        long inputRows = estimatedRows("SELECT id FROM customers");           // 8
        long groups = estimatedRows("SELECT city, COUNT(id) FROM customers GROUP BY city");
        assertTrue(groups >= 1, "group count must be at least 1");
        assertTrue(groups <= inputRows,
                "group count (" + groups + ") must not exceed input rows (" + inputRows + ")");
    }
}
