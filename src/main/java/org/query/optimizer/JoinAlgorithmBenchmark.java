package org.query.optimizer;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.physical.PhysicalNode;
import org.query.optimizer.util.DataGenerator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmark comparing the two equi-join algorithms — hash join versus
 * nested-loop join — on the same optimized logical plan.
 *
 * <p>The executor stays pure: each {@code @Benchmark} lowers the shared logical
 * plan under a forced join policy and runs it, while JMH supplies the timing and
 * statistics. This is the right home for an engine/operator A-vs-B comparison,
 * unlike {@link org.query.optimizer.learned.benchmark.LearnedOptimizerBenchmark},
 * which is a stateful sequential experiment rather than a microbenchmark.
 *
 * <h2>Running</h2>
 * <pre>
 *   mvn package -P benchmark -DskipTests
 *   java -jar target/benchmarks.jar JoinAlgorithmBenchmark
 * </pre>
 *
 * <p><b>Caveat:</b> the nested-loop arm currently under-counts rows on joins
 * whose two inputs share a column name (e.g. both have {@code id}), because the
 * join condition is evaluated on a flat combined tuple where a qualified column
 * binds to the wrong side. Until that correctness bug is fixed (AGENT_LOG T7),
 * the nested-loop timings are not a like-for-like comparison against hash join.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsPrepend = {"-Xms512m", "-Xmx2g"})
public class JoinAlgorithmBenchmark {

    /** Orders row count; the customer build side is {@code rowCount / 10} (min 100). */
    @Param({"1000", "10000", "100000"})
    public int rowCount;

    static final String SQL_JOIN =
            "SELECT orders.id, orders.total, customers.name " +
                    "FROM orders JOIN customers ON orders.customer_id = customers.id";

    private Path dataDir;
    private QueryOptimizer optimizer;
    private LogicalNode logical;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        dataDir = Files.createTempDirectory("qo-join-bench-");
        int customerCount = Math.max(100, rowCount / 10);
        new DataGenerator(customerCount, rowCount, rowCount).writeAll(dataDir);

        Catalog catalog = new Catalog();
        catalog.loadTableFromCSV("customers", dataDir.resolve("customers.csv").toString());
        catalog.loadTableFromCSV("products", dataDir.resolve("products.csv").toString());
        catalog.loadTableFromCSV("orders", dataDir.resolve("orders.csv").toString());

        optimizer = new QueryOptimizer(catalog);
        // The optimized logical plan is reused; only the physical plan (a
        // single-use iterator tree) is rebuilt per invocation.
        logical = optimizer.optimize(SQL_JOIN, OptimizationOptions.defaults())
                .optimizedLogicalPlan();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (dataDir == null) return;
        try (var walk = Files.walk(dataDir)) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    @Benchmark
    public void hashJoin(Blackhole bh) {
        PhysicalNode plan = optimizer.buildPhysicalPlan(logical, joinPolicy(JoinAlgorithmPolicy.FORCE_HASH));
        bh.consume(new Executor().execute(plan));
    }

    @Benchmark
    public void nestedLoopJoin(Blackhole bh) {
        PhysicalNode plan = optimizer.buildPhysicalPlan(logical, joinPolicy(JoinAlgorithmPolicy.FORCE_NLJ));
        bh.consume(new Executor().execute(plan));
    }

    private static OptimizationOptions joinPolicy(JoinAlgorithmPolicy policy) {
        return new OptimizationOptions(true, true, true, JoinOrderPolicy.DP, policy);
    }

    /**
     * Builds the hash-join and nested-loop-join physical plans this benchmark
     * times. Exposed so a unit test can build and run them without a JMH harness.
     *
     * @return a two-element array: index 0 = hash join, index 1 = nested-loop join
     */
    public static PhysicalNode[] buildJoinPlans(Catalog catalog) {
        QueryOptimizer optimizer = new QueryOptimizer(catalog);
        return new PhysicalNode[]{
                buildJoinPlan(optimizer, JoinAlgorithmPolicy.FORCE_HASH),
                buildJoinPlan(optimizer, JoinAlgorithmPolicy.FORCE_NLJ),
        };
    }

    /** Optimizes the join query fresh and lowers it under the given join policy. */
    static PhysicalNode buildJoinPlan(QueryOptimizer optimizer, JoinAlgorithmPolicy policy) {
        LogicalNode logical = optimizer.optimize(SQL_JOIN, OptimizationOptions.defaults())
                .optimizedLogicalPlan();
        return optimizer.buildPhysicalPlan(logical, joinPolicy(policy));
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(JoinAlgorithmBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
