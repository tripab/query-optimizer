package org.query.optimizer;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.query.optimizer.catalog.Catalog;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmarks comparing the Volcano iterator model against the
 * vectorized execution engine across five workload shapes:
 *
 * <ol>
 *   <li><b>Scan-only</b>           — full table scan with no predicate or projection</li>
 *   <li><b>Scan + Filter</b>       — single-column range predicate on a numeric column</li>
 *   <li><b>Scan + Filter + Project</b> — as above, then project one column</li>
 *   <li><b>Hash Join</b>           — inner equi-join between two tables</li>
 *   <li><b>Aggregation</b>         — COUNT + GROUP BY on a low-cardinality column</li>
 * </ol>
 *
 * <h2>Row-count parameterisation</h2>
 * <p>Single-table benchmarks (scan, filter, project) are parameterised over
 * {@code 1_000 / 10_000 / 100_000 / 1_000_000} product rows.  Join and
 * aggregate benchmarks use the same {@code rowCount} for orders, with a
 * customer table of {@code rowCount / 10} rows (minimum 100), so the join
 * probes a non-trivial build side without blowing the heap at large scales.
 *
 * <h2>Running</h2>
 * <pre>
 *   # build the fat JAR (benchmark profile)
 *   mvn package -P benchmark -DskipTests
 *
 *   # run all benchmarks
 *   java -jar target/benchmarks.jar
 *
 *   # run only scan benchmarks
 *   java -jar target/benchmarks.jar ".*[Ss]can.*"
 * </pre>
 *
 * <h2>Aggregate benchmark — Volcano note</h2>
 * <p>The Volcano {@link PhysicalPlanBuilder} does not yet implement aggregation
 * ({@code LogicalAggregate} throws {@link UnsupportedOperationException}), so
 * only the vectorized variant is provided for that workload.  The benchmark is
 * still parameterised so the vectorized numbers can be read on their own.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsPrepend = {"-Xms512m", "-Xmx2g"})
public class VectorizedBenchmark {

    // -------------------------------------------------------------------------
    // Parameters
    // -------------------------------------------------------------------------

    /**
     * Product / order row count. Drives the scale of every benchmark.
     */
    @Param({"1000", "10000", "100000", "1000000"})
    public int rowCount;

    // -------------------------------------------------------------------------
    // Benchmark state
    // -------------------------------------------------------------------------

    private Catalog catalog;
    private Path dataDir;

    private final SQLParser parser = new SQLParser();
    private LogicalPlanBuilder planBuilder;

    // -------------------------------------------------------------------------
    // Benchmark SQL queries
    // -------------------------------------------------------------------------

    // (a) scan-only
    private static final String SQL_SCAN =
            "SELECT id, name, category, price FROM products";

    // (b) scan + filter
    private static final String SQL_FILTER =
            "SELECT id, name, category, price FROM products WHERE price > 500";

    // (c) scan + filter + project
    private static final String SQL_PROJECT =
            "SELECT name FROM products WHERE price > 500";

    // (d) hash join  (orders ⋈ customers on customer_id = id)
    private static final String SQL_JOIN =
            "SELECT orders.id, orders.total, customers.name " +
                    "FROM orders " +
                    "JOIN customers ON orders.customer_id = customers.id";

    // (e) aggregation  (COUNT per category — 5 distinct groups)
    private static final String SQL_AGG =
            "SELECT category, COUNT(id) FROM products GROUP BY category";

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    /**
     * Generates the synthetic CSV files and loads them into a fresh {@link Catalog}.
     * Called once per {@code rowCount} value before any warmup iterations begin.
     */
    @Setup(Level.Trial)
    public void setup() throws IOException {
        dataDir = Files.createTempDirectory("qo-bench-");

        // customer count = rowCount / 10, minimum 100
        int customerCount = Math.max(100, rowCount / 10);

        DataGenerator gen = new DataGenerator(customerCount, rowCount, rowCount);
        gen.writeAll(dataDir);

        catalog = new Catalog();
        catalog.loadTableFromCSV("customers", dataDir.resolve("customers.csv").toString());
        catalog.loadTableFromCSV("products", dataDir.resolve("products.csv").toString());
        catalog.loadTableFromCSV("orders", dataDir.resolve("orders.csv").toString());

        planBuilder = new LogicalPlanBuilder(catalog);
    }

    /**
     * Removes the temporary CSV files after all measurement iterations finish.
     */
    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (dataDir != null) {
            try (var walk = Files.walk(dataDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }

    // -------------------------------------------------------------------------
    // (a) Scan-only
    // -------------------------------------------------------------------------

    @Benchmark
    public void scanOnlyVolcano(Blackhole bh) {
        bh.consume(runVolcano(SQL_SCAN));
    }

    @Benchmark
    public void scanOnlyVectorized(Blackhole bh) {
        bh.consume(runVectorized(SQL_SCAN));
    }

    // -------------------------------------------------------------------------
    // (b) Scan + Filter
    // -------------------------------------------------------------------------

    @Benchmark
    public void scanFilterVolcano(Blackhole bh) {
        bh.consume(runVolcano(SQL_FILTER));
    }

    @Benchmark
    public void scanFilterVectorized(Blackhole bh) {
        bh.consume(runVectorized(SQL_FILTER));
    }

    // -------------------------------------------------------------------------
    // (c) Scan + Filter + Project
    // -------------------------------------------------------------------------

    @Benchmark
    public void scanFilterProjectVolcano(Blackhole bh) {
        bh.consume(runVolcano(SQL_PROJECT));
    }

    @Benchmark
    public void scanFilterProjectVectorized(Blackhole bh) {
        bh.consume(runVectorized(SQL_PROJECT));
    }

    // -------------------------------------------------------------------------
    // (d) Hash Join
    // -------------------------------------------------------------------------

    @Benchmark
    public void hashJoinVolcano(Blackhole bh) {
        bh.consume(runVolcano(SQL_JOIN));
    }

    @Benchmark
    public void hashJoinVectorized(Blackhole bh) {
        bh.consume(runVectorized(SQL_JOIN));
    }

    // -------------------------------------------------------------------------
    // (e) Aggregation  (vectorized only — Volcano aggregate not yet implemented)
    // -------------------------------------------------------------------------

    @Benchmark
    public void aggregationVectorized(Blackhole bh) {
        bh.consume(runVectorized(SQL_AGG));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Parses {@code sql}, applies standard optimisation rules, builds a
     * Volcano physical plan, and executes it to completion.
     *
     * @return the {@link ExecutionResult} (consumed by the {@link Blackhole})
     */
    private ExecutionResult runVolcano(String sql) {
        LogicalNode logical = optimise(sql);
        PhysicalNode physical = new PhysicalPlanBuilder(catalog).build(logical);
        return new Executor().execute(physical);
    }

    /**
     * Parses {@code sql}, applies standard optimisation rules, builds a
     * vectorized operator tree, and executes it to completion.
     *
     * @return the {@link ExecutionResult} (consumed by the {@link Blackhole})
     */
    private ExecutionResult runVectorized(String sql) {
        LogicalNode logical = optimise(sql);
        VectorizedOperator vectorized = new VectorizedPlanBuilder(catalog).build(logical);
        return new VectorizedExecutor().execute(vectorized);
    }

    /**
     * Parses {@code sql} and runs the standard rule-based optimiser:
     * predicate pushdown → projection pushdown → filter merge.
     */
    private LogicalNode optimise(String sql) {
        LogicalNode logical = planBuilder.build(parser.parse(sql));
        RuleEngine engine = new RuleEngine(List.of(
                new PredicatePushdown(),
                new ProjectionPushdown(),
                new FilterMerge()));
        return engine.optimize(logical);
    }

    /**
     * Main method for running benchmarks directly (without Maven exec plugin).
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(VectorizedBenchmark.class.getSimpleName())
                .warmupIterations(3)
                .measurementIterations(5)
                .forks(1)
                .jvmArgsPrepend("-Xms512m", "-Xmx2g")
                .build();

        new Runner(opt).run();
    }
}
