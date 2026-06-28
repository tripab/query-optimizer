package org.query.optimizer;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.executor.Executor.ExecutionResult;
import org.query.optimizer.executor.ExecutionTimer;
import org.query.optimizer.executor.Timed;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.physical.PhysicalNode;
import org.query.optimizer.util.DataGenerator;
import org.query.optimizer.vectorized.VectorizedExecutor;
import org.query.optimizer.vectorized.VectorizedOperator;
import org.query.optimizer.vectorized.VectorizedPlanBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Interactive demo for the vectorized execution engine (Phase 5, task 5.4).
 *
 * <h2>What this demo shows</h2>
 * <p>Five query workloads (scan, filter, project, join, and aggregation) are
 * executed through <em>both</em> the classic Volcano iterator model and the new
 * vectorized engine.  After each query the demo prints:
 * <ul>
 *   <li>The optimised logical plan (shared by both engines).</li>
 *   <li>The Volcano physical plan tree.</li>
 *   <li>The vectorized operator tree.</li>
 *   <li>A side-by-side timing comparison table.</li>
 * </ul>
 *
 * <h2>Dataset</h2>
 * <p>Data is generated with {@link DataGenerator} at a "demo" scale
 * (500 customers, 1,000 products, 5,000 orders) -- large enough to show
 * meaningful timing differences, small enough to run in a few seconds.
 *
 * <h2>Running</h2>
 * <pre>
 *   # from the project root
 *   mvn compile exec:java -Dexec.mainClass=org.query.optimizer.VectorizedExecutionDemo
 * </pre>
 */
public class VectorizedExecutionDemo {

    // -----------------------------------------------------------------------
    // Demo scale -- larger than unit-test fixtures, smaller than benchmarks
    // -----------------------------------------------------------------------
    private static final int CUSTOMERS = 500;
    private static final int PRODUCTS  = 1_000;
    private static final int ORDERS    = 5_000;

    // -----------------------------------------------------------------------
    // Demo queries
    // -----------------------------------------------------------------------

    /** (a) Full scan -- no filter, no projection. */
    private static final String SQL_SCAN =
            "SELECT id, name, category, price FROM products";

    /** (b) Scan + single filter on a numeric column (~50% selectivity). */
    private static final String SQL_FILTER =
            "SELECT id, name, category, price FROM products WHERE price > 500";

    /** (c) Scan + filter + narrow projection (1 column out of 4). */
    private static final String SQL_PROJECT =
            "SELECT name FROM products WHERE price > 500";

    /** (d) Inner equi-join: orders join customers on customer_id = id. */
    private static final String SQL_JOIN =
            "SELECT orders.id, orders.total, customers.name " +
            "FROM orders " +
            "JOIN customers ON orders.customer_id = customers.id";

    /** (e) Aggregation: COUNT per category (5 distinct groups). */
    private static final String SQL_AGG =
            "SELECT category, COUNT(id) FROM products GROUP BY category";

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        printBanner("Vectorized Execution Engine Demo");

        // ---- 1. Generate data -------------------------------------------
        Path dataDir = Files.createTempDirectory("qo-demo-");
        try {
            DataGenerator gen = new DataGenerator(CUSTOMERS, PRODUCTS, ORDERS);
            gen.writeAll(dataDir);

            Catalog catalog = new Catalog();
            catalog.loadTableFromCSV("customers", dataDir.resolve("customers.csv").toString());
            catalog.loadTableFromCSV("products",  dataDir.resolve("products.csv").toString());
            catalog.loadTableFromCSV("orders",    dataDir.resolve("orders.csv").toString());

            System.out.printf(
                "Dataset: %,d customers | %,d products | %,d orders%n%n",
                CUSTOMERS, PRODUCTS, ORDERS);

            // ---- 2. Timing accumulators ----------------------------------
            long[] volcanoTotalMs    = {0};
            long[] vectorizedTotalMs = {0};

            // ---- 3. Run each query ---------------------------------------
            runComparison("Query 1 - Scan-only",        SQL_SCAN,    catalog,
                          volcanoTotalMs, vectorizedTotalMs);

            runComparison("Query 2 - Scan + Filter",    SQL_FILTER,  catalog,
                          volcanoTotalMs, vectorizedTotalMs);

            runComparison("Query 3 - Filter + Project", SQL_PROJECT, catalog,
                          volcanoTotalMs, vectorizedTotalMs);

            runComparison("Query 4 - Hash Join",        SQL_JOIN,    catalog,
                          volcanoTotalMs, vectorizedTotalMs);

            // Aggregation now runs on both engines (PhysicalAggregate added in
            // Phase 2), so it is compared and included in the summary like the
            // others.
            runComparison("Query 5 - Aggregation",      SQL_AGG,     catalog,
                          volcanoTotalMs, vectorizedTotalMs);

            // ---- 4. Overall summary (queries 1-5) ------------------------
            printSummary(volcanoTotalMs[0], vectorizedTotalMs[0]);

        } finally {
            // Clean up temp CSVs
            try (var walk = Files.walk(dataDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Core comparison runner (Volcano + Vectorized)
    // -----------------------------------------------------------------------

    /**
     * Runs {@code sql} through both engines, prints plans and a timing table,
     * and adds the elapsed times to the running totals.
     *
     * @param title          section heading printed to stdout
     * @param sql            the query to execute
     * @param catalog        loaded catalog
     * @param volcanoTotalMs accumulator for total Volcano time
     * @param vecTotalMs     accumulator for total vectorized time
     */
    private static void runComparison(
            String title, String sql, Catalog catalog,
            long[] volcanoTotalMs, long[] vecTotalMs) {

        printSection(title);
        System.out.println("SQL: " + sql);
        System.out.println();

        // ---- Parse & optimise (shared) -----------------------------------
        LogicalNode optimised = new QueryOptimizer(catalog)
                .optimize(sql, OptimizationOptions.defaults())
                .optimizedLogicalPlan();
        System.out.println("Optimised logical plan:");
        System.out.println(indent(optimised.toPrettyString(), 2));

        // ---- Volcano -----------------------------------------------------
        PhysicalNode volcanoplan = new QueryOptimizer(catalog)
                .buildPhysicalPlan(optimised, OptimizationOptions.defaults());
        System.out.println("Volcano physical plan:");
        System.out.println(indent(volcanoplan.toPrettyString(), 2));

        Timed<ExecutionResult> volcanoRun = ExecutionTimer.run(() -> new Executor().execute(volcanoplan));
        ExecutionResult volcanoResult = volcanoRun.value();

        // ---- Vectorized --------------------------------------------------
        VectorizedOperator vecPlan = new VectorizedPlanBuilder(catalog).build(optimised);
        System.out.println("Vectorized operator tree:");
        System.out.println(indent(vecPlan.describe(), 2));

        Timed<ExecutionResult> vecRun = ExecutionTimer.run(() -> new VectorizedExecutor().execute(vecPlan));
        ExecutionResult vecResult = vecRun.value();

        // ---- Sanity check ------------------------------------------------
        if (volcanoResult.getResultCount() != vecResult.getResultCount()) {
            System.out.printf(
                "  [WARN] Row count mismatch: Volcano=%d  Vectorized=%d%n",
                volcanoResult.getResultCount(), vecResult.getResultCount());
        }

        // ---- Timing table ------------------------------------------------
        printTimingTable(
            volcanoResult.getResultCount(),
            volcanoRun.millis(),
            vecRun.millis());

        volcanoTotalMs[0] += volcanoRun.millis();
        vecTotalMs[0]     += vecRun.millis();
    }

    // -----------------------------------------------------------------------
    // Printing helpers
    // -----------------------------------------------------------------------

    private static void printBanner(String title) {
        String bar = "=".repeat(60);
        System.out.println(bar);
        System.out.println("  " + title);
        System.out.println(bar);
        System.out.println();
    }

    private static void printSection(String title) {
        System.out.println();
        System.out.println("-".repeat(60));
        System.out.println("  " + title);
        System.out.println("-".repeat(60));
    }

    private static void printTimingTable(int rowCount, long volcanoMs, long vecMs) {
        System.out.println();
        System.out.printf("  %-22s  %8s  %10s  %8s%n",
                "Engine", "Rows", "Time (ms)", "Speedup");
        System.out.printf("  %-22s  %8s  %10s  %8s%n",
                "----------------------", "--------", "----------", "--------");
        System.out.printf("  %-22s  %,8d  %10d  %8s%n",
                "Volcano (iterator)", rowCount, volcanoMs, "1.00x");
        double speedup = volcanoMs > 0 && vecMs > 0 ? (double) volcanoMs / vecMs : 0.0;
        System.out.printf("  %-22s  %,8d  %10d  %7.2fx%n",
                "Vectorized (batch)", rowCount, vecMs, speedup);
        System.out.println();
    }

    private static void printSummary(long volcanoTotalMs, long vecTotalMs) {
        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("  Overall timing summary (queries 1-5, Volcano vs Vectorized)");
        System.out.println("=".repeat(60));
        System.out.printf("  Volcano total    : %,d ms%n", volcanoTotalMs);
        System.out.printf("  Vectorized total : %,d ms%n", vecTotalMs);
        if (vecTotalMs > 0) {
            System.out.printf("  Overall speedup  : %.2fx%n",
                    (double) volcanoTotalMs / vecTotalMs);
        }
        System.out.println();
        System.out.println("  Key takeaways:");
        System.out.println("  * Typed columnar arrays eliminate per-row boxing overhead.");
        System.out.println("  * Selection vectors avoid data copies on filter operations.");
        System.out.println("  * Batch amortisation cuts virtual-dispatch cost by up to 1024x.");
        System.out.println("  * Further gains possible with SIMD (Extension A1).");
        System.out.println("=".repeat(60));
    }

    private static String indent(String text, int spaces) {
        String prefix = " ".repeat(spaces);
        return prefix + text.replace("\n", "\n" + prefix);
    }
}
