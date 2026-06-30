package org.query.optimizer.learned.benchmark;

import org.query.optimizer.SimpleCostModel;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.executor.Executor.ExecutionResult;
import org.query.optimizer.executor.ExecutionTimer;
import org.query.optimizer.executor.Timed;
import org.query.optimizer.learned.bao.BanditOptimizer;
import org.query.optimizer.learned.common.ExecutionFeedback;
import org.query.optimizer.learned.common.HintSet;
import org.query.optimizer.learned.common.PlanVariantGenerator;
import org.query.optimizer.learned.common.WorkloadGenerator.ParsedQuery;
import org.query.optimizer.learned.lero.LeroOptimizer;
import org.query.optimizer.physical.PhysicalNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Runs four plan-selection strategies on the same workload and collects
 * per-query and aggregate metrics for comparison.
 *
 * <h2>Strategies</h2>
 * <ol>
 *   <li><b>DEFAULT</b> — always executes the plan produced by the DEFAULT hint
 *       set (baseline).</li>
 *   <li><b>ORACLE</b> — executes every plan variant and keeps the one with the
 *       lowest actual latency (theoretical ceiling — cannot be beaten).</li>
 *   <li><b>BAO</b> — Thompson Sampling bandit optimizer
 *       ({@link BanditOptimizer}).</li>
 *   <li><b>LERO</b> — pairwise learning-to-rank optimizer
 *       ({@link LeroOptimizer}).</li>
 * </ol>
 *
 * <h2>Metrics (per strategy)</h2>
 * <ul>
 *   <li>Total workload latency.</li>
 *   <li>Regret vs. oracle — {@code totalMs / oracleTotal}; 1.0 means perfect.</li>
 *   <li>Learning speed — first query index (1-based) at which the strategy's
 *       10-query rolling average falls below 1.2× the oracle's rolling average;
 *       −1 if no such point exists.</li>
 *   <li>Tail latency — P95 and P99 per-query latency.</li>
 * </ul>
 * <ul>
 *   <li>Bao–Lero agreement rate — fraction of queries where both strategies
 *       chose a plan with the same estimated cost (structural proxy for
 *       "same plan").</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   LearnedOptimizerBenchmark bench = new LearnedOptimizerBenchmark(catalog);
 *   BenchmarkResults results = bench.run(workload);
 * }</pre>
 */
public class LearnedOptimizerBenchmark {

    /** Seed for the learned strategies, so their decisions are reproducible. */
    public static final long DEFAULT_SEED = 42L;

    private final Catalog catalog;
    private final long seed;

    public LearnedOptimizerBenchmark(Catalog catalog) {
        this(catalog, DEFAULT_SEED);
    }

    public LearnedOptimizerBenchmark(Catalog catalog, long seed) {
        this.catalog = catalog;
        this.seed = seed;
    }

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    /**
     * Runs all four strategies on {@code workload} and returns the combined
     * results.  Progress lines are printed to stdout so long workloads give
     * visible feedback.
     *
     * @param workload the queries to execute; all strategies see the same sequence
     * @return complete benchmark results
     */
    public BenchmarkResults run(List<ParsedQuery> workload) {
        int n = workload.size();

        System.out.print("  [1/4] DEFAULT ...");
        long[] defaultMs = runDefault(workload);
        System.out.printf(" %,d ms%n", sum(defaultMs));

        System.out.print("  [2/4] ORACLE  ...");
        OracleRun oracle = runOracle(workload);
        System.out.printf(" %,d ms%n", sum(oracle.latencies()));

        System.out.print("  [3/4] BAO     ...");
        List<BanditOptimizer.QueryMetrics> baoMetrics =
                new BanditOptimizer(catalog, new Random(seed)).runWorkload(workload);
        long[] baoMs = extractLatencies(baoMetrics);
        System.out.printf(" %,d ms%n", sum(baoMs));

        System.out.print("  [4/4] LERO    ...");
        List<LeroOptimizer.QueryMetrics> leroMetrics =
                new LeroOptimizer(catalog, new Random(seed)).runWorkload(workload);
        long[] leroMs = extractLeroLatencies(leroMetrics);
        System.out.printf(" %,d ms%n", sum(leroMs));

        // Per-query result objects
        List<QueryResult> queryResults = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            queryResults.add(new QueryResult(
                    i,
                    workload.get(i).sql(),
                    defaultMs[i],
                    oracle.latencies()[i],
                    baoMs[i],
                    leroMs[i],
                    oracle.bestArms()[i],
                    baoMetrics.get(i).selectedArm()));
        }

        // Aggregate metrics
        long            oracleTotal  = sum(oracle.latencies());
        StrategyMetrics defaultStats = computeMetrics("DEFAULT", defaultMs,          oracle.latencies(), oracleTotal);
        StrategyMetrics oracleStats  = computeMetrics("ORACLE",  oracle.latencies(), oracle.latencies(), oracleTotal);
        StrategyMetrics baoStats     = computeMetrics("BAO",     baoMs,              oracle.latencies(), oracleTotal);
        StrategyMetrics leroStats    = computeMetrics("LERO",    leroMs,             oracle.latencies(), oracleTotal);

        double agreementRate = computeAgreementRate(workload, baoMetrics, leroMetrics);

        return new BenchmarkResults(
                queryResults, defaultStats, oracleStats, baoStats, leroStats, agreementRate);
    }

    // -------------------------------------------------------------------------
    // Per-strategy runners
    // -------------------------------------------------------------------------

    private long[] runDefault(List<ParsedQuery> workload) {
        SimpleCostModel      cm     = new SimpleCostModel(catalog);
        PlanVariantGenerator varGen = new PlanVariantGenerator(catalog, cm);
        Executor             exec   = new Executor();
        long[]               result = new long[workload.size()];

        for (int i = 0; i < workload.size(); i++) {
            Map<HintSet, PhysicalNode> variants =
                    varGen.generateVariants(workload.get(i).logicalPlan(),
                            List.of(HintSet.DEFAULT));
            PhysicalNode plan = variants.values().iterator().next();
            result[i] = ExecutionTimer.run(() -> exec.execute(plan)).millis();
        }
        return result;
    }

    /**
     * Executes every distinct plan variant for each query and picks the one that
     * did the least tuple-processing work (see {@link #pickOracleArm}). This is the
     * oracle — the ceiling no strategy can beat.
     *
     * <p>Selecting by {@code tuplesProcessed} rather than the run's measured
     * latency keeps the oracle's plan choice stable: timer noise on sub-millisecond
     * in-memory queries can never flip which variant is declared best. The reported
     * latency is the chosen variant's measured time, in milliseconds.
     */
    private OracleRun runOracle(List<ParsedQuery> workload) {
        SimpleCostModel      cm        = new SimpleCostModel(catalog);
        PlanVariantGenerator varGen    = new PlanVariantGenerator(catalog, cm);
        Executor             exec      = new Executor();
        int                  n         = workload.size();
        long[]               latencies = new long[n];
        HintSet[]            bestArms  = new HintSet[n];

        for (int i = 0; i < n; i++) {
            Map<HintSet, PhysicalNode> variants =
                    varGen.generateVariants(workload.get(i).logicalPlan(),
                            HintSet.allHintSets());

            List<VariantCost> executed = new ArrayList<>(variants.size());
            for (Map.Entry<HintSet, PhysicalNode> e : variants.entrySet()) {
                PhysicalNode           plan = e.getValue();
                Timed<ExecutionResult> run  = ExecutionTimer.run(() -> exec.execute(plan));
                executed.add(new VariantCost(e.getKey(),
                        run.value().tuplesProcessed(), run.millis()));
            }

            HintSet bestArm = pickOracleArm(executed);
            long    bestMs  = 0L;
            for (VariantCost v : executed) {
                if (v.arm().equals(bestArm)) {
                    bestMs = v.latencyMs();
                    break;
                }
            }
            latencies[i] = bestMs;
            bestArms[i]  = bestArm;
        }
        return new OracleRun(latencies, bestArms);
    }

    /** One executed variant's measured work, used to score the oracle's choice. */
    record VariantCost(HintSet arm, long tuplesProcessed, long latencyMs) {}

    /**
     * Returns the arm whose variant did the least tuple-processing work, or
     * {@code null} if the input is empty. Package-visible so the selection rule
     * can be tested directly against a constructed set.
     *
     * <p>Selection uses {@code tuplesProcessed} — the deterministic component of
     * {@link ExecutionFeedback#logicalCost(long, long)} — and breaks exact ties by
     * arm name, so the oracle's choice never depends on the run's measured
     * latency. That keeps it reproducible: on small in-memory tables a wall-clock
     * blip (a 1&nbsp;ms timer tick equals 100 tuple-units) could otherwise flip the
     * winner between runs. The chosen arm's measured latency is still what the
     * benchmark reports; only the selection is made deterministic.
     */
    static HintSet pickOracleArm(List<VariantCost> executed) {
        HintSet best       = null;
        long    bestTuples = Long.MAX_VALUE;
        for (VariantCost v : executed) {
            long tuples = v.tuplesProcessed();
            if (best == null
                    || tuples < bestTuples
                    || (tuples == bestTuples && v.arm().getName().compareTo(best.getName()) < 0)) {
                best       = v.arm();
                bestTuples = tuples;
            }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // Wall-clock distribution (machine-dependent)
    // -------------------------------------------------------------------------

    /**
     * Re-runs the whole workload {@code repeats} times (after one untimed
     * warm-up pass) and returns each repeat's total wall-clock latency per
     * strategy. Strategy decisions are seeded, so only the timing varies between
     * repeats — report these with {@link #distribution(long[])} as a median and
     * IQR rather than as a single noisy total.
     *
     * @return strategy name -&gt; {@code repeats} total-latency samples (ms)
     */
    public Map<String, long[]> measureWallClockTotals(List<ParsedQuery> workload, int repeats) {
        runWorkloadTotals(workload);                       // warm-up, discarded
        Map<String, long[]> samples = new LinkedHashMap<>();
        for (int k = 0; k < repeats; k++) {
            Map<String, Long> totals = runWorkloadTotals(workload);
            for (Map.Entry<String, Long> e : totals.entrySet()) {
                samples.computeIfAbsent(e.getKey(), key -> new long[repeats])[k] = e.getValue();
            }
        }
        return samples;
    }

    /** Total wall-clock latency (ms) of one workload pass, per strategy. */
    private Map<String, Long> runWorkloadTotals(List<ParsedQuery> workload) {
        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("DEFAULT", sum(runDefault(workload)));
        totals.put("ORACLE", sum(runOracle(workload).latencies()));
        totals.put("BAO", sum(extractLatencies(
                new BanditOptimizer(catalog, new Random(seed)).runWorkload(workload))));
        totals.put("LERO", sum(extractLeroLatencies(
                new LeroOptimizer(catalog, new Random(seed)).runWorkload(workload))));
        return totals;
    }

    /** Median, inter-quartile range, min, and max of a set of samples. */
    public record Distribution(long median, long iqr, long min, long max) {}

    /** Computes a {@link Distribution} from raw samples (e.g. per-repeat totals). */
    public static Distribution distribution(long[] samples) {
        if (samples.length == 0) return new Distribution(0, 0, 0, 0);
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        long iqr = percentile(sorted, 75) - percentile(sorted, 25);
        return new Distribution(percentile(sorted, 50), iqr, sorted[0], sorted[sorted.length - 1]);
    }

    /** Nearest-rank percentile of an already-sorted array. */
    private static long percentile(long[] sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        int idx = Math.max(0, Math.min(sorted.length - 1, rank - 1));
        return sorted[idx];
    }

    // -------------------------------------------------------------------------
    // Metrics computation
    // -------------------------------------------------------------------------

    private StrategyMetrics computeMetrics(String name, long[] latencies,
                                           long[] oracleLatencies, long oracleTotal) {
        long   total  = sum(latencies);
        long[] sorted = Arrays.copyOf(latencies, latencies.length);
        Arrays.sort(sorted);

        long p95 = sorted.length > 0
                ? sorted[Math.max(0, (int) Math.ceil(sorted.length * 0.95) - 1)] : 0L;
        long p99 = sorted.length > 0
                ? sorted[Math.max(0, (int) Math.ceil(sorted.length * 0.99) - 1)] : 0L;

        double regret        = oracleTotal > 0 ? (double) total / oracleTotal : 1.0;
        int    learningSpeed = computeLearningSpeed(latencies, oracleLatencies);

        return new StrategyMetrics(name, total, regret, learningSpeed, p95, p99);
    }

    /**
     * Returns the first query index (1-based) at which the strategy's 10-query
     * rolling average first falls at or below 1.2× the oracle's rolling average.
     * Returns −1 if no such point exists within the workload.
     *
     * <p>When oracle latencies are all 0 (sub-millisecond tables) the condition
     * is satisfied from query 1 because the strategy also produces 0ms latencies.
     */
    private static int computeLearningSpeed(long[] latencies, long[] oracleLatencies) {
        int window = 10;
        if (latencies.length < window) return -1;

        for (int i = window; i <= latencies.length; i++) {
            double stratAvg  = 0;
            double oracleAvg = 0;
            for (int j = i - window; j < i; j++) {
                stratAvg  += latencies[j];
                oracleAvg += oracleLatencies[j];
            }
            if (oracleAvg == 0 || stratAvg <= oracleAvg * 1.2) {
                return i - window + 1;
            }
        }
        return -1;
    }

    /**
     * Fraction of queries where Bao and Lero chose structurally equivalent plans,
     * determined by comparing each selected plan's estimated cost.
     *
     * <p>We use estimated cost as a proxy for plan identity rather than a full
     * structural comparison. This is intentional: the agreement rate is a
     * diagnostic metric for human consumption in the benchmark report — no
     * query routing or training signal depends on it. A false positive (two
     * structurally different plans with the same estimated cost counted as
     * "agreement") would only slightly inflate a printed percentage, with no
     * downstream consequence. In practice the false-positive risk is negligible
     * because a plan's estimated cost is now the physical cost annotated by the
     * planner (a function of the operator tree and the join algorithms chosen), so
     * structurally different plans almost always receive different costs.
     */
    private double computeAgreementRate(List<ParsedQuery> workload,
                                        List<BanditOptimizer.QueryMetrics> baoMetrics,
                                        List<LeroOptimizer.QueryMetrics> leroMetrics) {
        if (workload.isEmpty()) return 0.0;

        SimpleCostModel      cm     = new SimpleCostModel(catalog);
        PlanVariantGenerator varGen = new PlanVariantGenerator(catalog, cm);

        int agreements = 0;
        for (int i = 0; i < workload.size(); i++) {
            HintSet baoArm = baoMetrics.get(i).selectedArm();

            // Re-generate only the Bao arm's variant to obtain its estimated cost
            Map<HintSet, PhysicalNode> variants =
                    varGen.generateVariants(workload.get(i).logicalPlan(), List.of(baoArm));
            if (variants.isEmpty()) continue;

            PhysicalNode baoPlan  = variants.values().iterator().next();
            PhysicalNode leroPlan = leroMetrics.get(i).selectedPlan();

            if (Math.abs(baoPlan.getEstimatedCost() - leroPlan.getEstimatedCost()) < 1e-6) {
                agreements++;
            }
        }
        return (double) agreements / workload.size();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static long[] extractLatencies(List<BanditOptimizer.QueryMetrics> metrics) {
        long[] ms = new long[metrics.size()];
        for (int i = 0; i < ms.length; i++) ms[i] = metrics.get(i).actualLatencyMs();
        return ms;
    }

    private static long[] extractLeroLatencies(List<LeroOptimizer.QueryMetrics> metrics) {
        long[] ms = new long[metrics.size()];
        for (int i = 0; i < ms.length; i++) ms[i] = metrics.get(i).actualLatencyMs();
        return ms;
    }

    static long sum(long[] arr) {
        long s = 0;
        for (long v : arr) s += v;
        return s;
    }

    // -------------------------------------------------------------------------
    // Internal helper record
    // -------------------------------------------------------------------------

    private record OracleRun(long[] latencies, HintSet[] bestArms) {}

    // =========================================================================
    // Public result types
    // =========================================================================

    /**
     * Per-query results for all four strategies.
     *
     * @param index      0-based position in the workload
     * @param sql        the original SQL string
     * @param defaultMs  latency of the DEFAULT strategy
     * @param oracleMs   latency of the best plan found by the oracle
     * @param baoMs      latency of the plan chosen by Bao
     * @param leroMs     latency of the plan chosen by Lero
     * @param oracleArm  hint set the oracle identified as best for this query
     * @param baoArm     hint set Bao selected for this query
     */
    public record QueryResult(
            int     index,
            String  sql,
            long    defaultMs,
            long    oracleMs,
            long    baoMs,
            long    leroMs,
            HintSet oracleArm,
            HintSet baoArm) {}

    /**
     * Aggregate metrics for one strategy across the full workload.
     *
     * @param name               display name
     * @param totalMs            sum of all per-query latencies
     * @param regretVsOracle     {@code totalMs / oracleTotal}; 1.0 = perfect
     * @param learningSpeedQuery first query (1-based) at which rolling avg ≤ 1.2×
     *                           oracle rolling avg; −1 if never
     * @param p95Ms              95th-percentile per-query latency
     * @param p99Ms              99th-percentile per-query latency
     */
    public record StrategyMetrics(
            String name,
            long   totalMs,
            double regretVsOracle,
            int    learningSpeedQuery,
            long   p95Ms,
            long   p99Ms) {}

    /**
     * Complete results from a single benchmark run.
     *
     * @param perQuery             one {@link QueryResult} per query in the workload
     * @param defaultMetrics       aggregate metrics for the DEFAULT strategy
     * @param oracleMetrics        aggregate metrics for the ORACLE strategy
     * @param baoMetrics           aggregate metrics for BAO
     * @param leroMetrics          aggregate metrics for LERO
     * @param baoLeroAgreementRate fraction of queries where Bao and Lero chose
     *                             plans with the same estimated cost
     */
    public record BenchmarkResults(
            List<QueryResult> perQuery,
            StrategyMetrics   defaultMetrics,
            StrategyMetrics   oracleMetrics,
            StrategyMetrics   baoMetrics,
            StrategyMetrics   leroMetrics,
            double            baoLeroAgreementRate) {

        /** Total latency for the DEFAULT strategy. */
        public long defaultTotal() { return defaultMetrics.totalMs(); }

        /** Total latency for the ORACLE strategy. */
        public long oracleTotal()  { return oracleMetrics.totalMs(); }

        /** Total latency for BAO. */
        public long baoTotal()     { return baoMetrics.totalMs(); }

        /** Total latency for LERO. */
        public long leroTotal()    { return leroMetrics.totalMs(); }
    }
}
