package org.query.optimizer.learned.benchmark;

import org.query.optimizer.JoinAlgorithmPolicy;
import org.query.optimizer.JoinOrderPolicy;
import org.query.optimizer.OptimizationOptions;
import org.query.optimizer.QueryOptimizer;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.learned.benchmark.LearnedOptimizerBenchmark.BenchmarkResults;
import org.query.optimizer.learned.benchmark.LearnedOptimizerBenchmark.Distribution;
import org.query.optimizer.learned.benchmark.LearnedOptimizerBenchmark.QueryResult;
import org.query.optimizer.learned.benchmark.LearnedOptimizerBenchmark.StrategyMetrics;
import org.query.optimizer.learned.common.DataGenerator;
import org.query.optimizer.learned.common.HintSet;
import org.query.optimizer.learned.common.WorkloadGenerator;
import org.query.optimizer.learned.common.WorkloadGenerator.ParsedQuery;
import org.query.optimizer.learned.lero.LeroOptimizer;
import org.query.optimizer.learned.lero.PairwiseComparator;
import org.query.optimizer.learned.lero.PairwiseComparator.TrainingPair;
import org.query.optimizer.learned.lero.PlanExplorer;
import org.query.optimizer.physical.JoinAlgorithmCounts;
import org.query.optimizer.physical.PhysicalNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Showpiece demo that runs Bao and Lero side-by-side against a DEFAULT baseline
 * and an ORACLE ceiling, then prints a five-section comparative report.
 *
 * <h2>Sections</h2>
 * <ol>
 *   <li><b>Overview</b> — one-line summary per strategy (total latency, regret,
 *       tail latency, learning speed).</li>
 *   <li><b>Learning curves</b> — cumulative latency at query 50/100/150/200/250/300
 *       for all four strategies.</li>
 *   <li><b>Bao deep-dive</b> — arm selection frequency split into early/mid/late
 *       thirds of the workload, showing how Thompson Sampling's arm preferences
 *       shift as the value model converges.</li>
 *   <li><b>Lero deep-dive</b> — pairwise comparator accuracy at checkpoints, plus
 *       a warm-up vs. warm-phase performance breakdown.</li>
 *   <li><b>Head-to-head</b> — queries where each strategy won or lost, agreement
 *       rate, and shared failure modes.</li>
 *   <li><b>Cost-based join-algorithm mix</b> — how the cost model distributes
 *       hash vs. nested-loop joins across the workload.</li>
 *   <li><b>Wall-clock distribution</b> — each strategy's total latency as a
 *       median and IQR over {@link #WALL_CLOCK_REPEATS} timed repeats. This is
 *       the only machine-dependent section; every section above is seeded and
 *       reproduces identically across machines.</li>
 * </ol>
 *
 * <h2>How to run</h2>
 * <pre>{@code
 *   mvn -q exec:java -Dexec.mainClass=org.query.optimizer.learned.benchmark.BaoVsLeroDemo
 * }</pre>
 */
public class BaoVsLeroDemo {

    private static final int WORKLOAD_SIZE = 300;
    /**
     * Scale factor 2 keeps the demo fast (~2 K customers, ~4 K orders, ~1 K products)
     * while still producing measurable latency differences between plan variants.
     * Raise to 10 for a more pronounced benchmark at the cost of longer runtime.
     */
    private static final int SCALE_FACTOR  = 2;
    /**
     * Timed workload repeats used for the wall-clock distribution (Section 7),
     * after one discarded warm-up pass. Five is the minimum that gives a
     * meaningful median + IQR; raise it on a quiet benchmark host for tighter
     * intervals.
     */
    private static final int WALL_CLOCK_REPEATS = 5;

    public static void main(String[] args) {
        // ------------------------------------------------------------------
        // 1. Setup: generate synthetic tables
        // ------------------------------------------------------------------
        Catalog catalog = new Catalog();
        System.out.println("=== Bao vs. Lero: Head-to-Head Benchmark ===");
        System.out.println();
        System.out.println("Generating synthetic tables (scale factor " + SCALE_FACTOR + ")...");
        DataGenerator.generate(catalog, SCALE_FACTOR);
        printTableSizes(catalog);

        System.out.println("Generating workload of " + WORKLOAD_SIZE + " queries...");
        WorkloadGenerator gen = new WorkloadGenerator(catalog, 42L);
        List<ParsedQuery> workload = gen.generateWorkload(WORKLOAD_SIZE);
        System.out.println("Done.\n");

        // ------------------------------------------------------------------
        // 2. Run all four strategies through the benchmark harness
        // ------------------------------------------------------------------
        System.out.println("Running benchmark (4 strategies on the same workload)...");
        LearnedOptimizerBenchmark benchmark = new LearnedOptimizerBenchmark(catalog);
        BenchmarkResults results = benchmark.run(workload);
        System.out.println();

        // ------------------------------------------------------------------
        // 3. Print the five report sections
        // ------------------------------------------------------------------
        printSection1Overview(results);
        printSection2LearningCurves(results, workload.size());
        printSection3BaoDeepDive(results, workload.size());
        printSection4LeroDeepDive(catalog, workload, results);
        printSection5HeadToHead(results);
        printSection6JoinAlgorithmMix(catalog, workload);
        printSection7WallClock(benchmark, workload);
    }

    // =========================================================================
    // Section 7: Wall-clock distribution (machine-dependent)
    // =========================================================================

    /**
     * Reports each strategy's total workload latency as a median and IQR over
     * {@link #WALL_CLOCK_REPEATS} timed repeats (after one discarded warm-up pass).
     *
     * <p>Everything above is seeded and therefore reproducible — those are the
     * decision-quality numbers, identical on every machine. Wall-clock totals are
     * the machine-dependent part, so they are summarised as a distribution rather
     * than a single noisy total: this is the figure an EC2 (or any other) run
     * should quote. Strategy decisions don't change between repeats, so the spread
     * reflects timing noise on the host, not different plan choices.
     */
    private static void printSection7WallClock(
            LearnedOptimizerBenchmark benchmark, List<ParsedQuery> workload) {
        System.out.println("=== Section 7: Wall-Clock Distribution (machine-dependent) ===");
        System.out.printf("  %d timed repeat(s) after one warm-up pass; "
                + "decisions are seeded, so only timing varies across repeats.%n",
                WALL_CLOCK_REPEATS);

        Map<String, long[]> samples =
                benchmark.measureWallClockTotals(workload, WALL_CLOCK_REPEATS);

        System.out.printf("  %-10s  %12s  %10s  %10s  %10s%n",
                "Strategy", "Median (ms)", "IQR (ms)", "Min (ms)", "Max (ms)");
        System.out.println("  " + "-".repeat(60));
        for (Map.Entry<String, long[]> e : samples.entrySet()) {
            Distribution d = LearnedOptimizerBenchmark.distribution(e.getValue());
            System.out.printf("  %-10s  %,12d  %,10d  %,10d  %,10d%n",
                    e.getKey(), d.median(), d.iqr(), d.min(), d.max());
        }
        System.out.println();
    }

    // =========================================================================
    // Section 6: Cost-based join-algorithm mix
    // =========================================================================

    /**
     * Reports how the cost-based planner distributes hash vs. nested-loop joins
     * across the workload. Unlike the hint-forced variants the learned optimizers
     * choose among, this re-plans each query under {@link JoinAlgorithmPolicy#COST_BASED}
     * so the physical cost model decides the algorithm per join.
     */
    private static void printSection6JoinAlgorithmMix(Catalog catalog, List<ParsedQuery> workload) {
        System.out.println("=== Section 6: Cost-Based Join-Algorithm Mix ===");

        QueryOptimizer optimizer = new QueryOptimizer(catalog);
        OptimizationOptions costBased = new OptimizationOptions(
                true, true, true, JoinOrderPolicy.DP, JoinAlgorithmPolicy.COST_BASED);

        int hashJoins = 0;
        int nestedLoopJoins = 0;
        int queriesWithJoins = 0;
        int skipped = 0;
        for (ParsedQuery q : workload) {
            PhysicalNode plan;
            try {
                plan = optimizer.optimize(null, q.logicalPlan(), costBased).physicalPlan();
            } catch (RuntimeException e) {
                // A query whose columns don't fully resolve can't be planned; skip
                // it for this report rather than aborting the whole section.
                skipped++;
                continue;
            }
            JoinAlgorithmCounts counts = JoinAlgorithmCounts.of(plan);
            hashJoins += counts.hashJoins();
            nestedLoopJoins += counts.nestedLoopJoins();
            if (counts.totalJoins() > 0) queriesWithJoins++;
        }

        System.out.printf("  Across %d queries (%d with joins): %d hash join(s), %d nested-loop join(s)%n",
                workload.size(), queriesWithJoins, hashJoins, nestedLoopJoins);
        if (skipped > 0) {
            System.out.printf("  (%d quer%s skipped: columns did not resolve under planning.)%n",
                    skipped, skipped == 1 ? "y" : "ies");
        }
        System.out.println("  (Cost-based selection favours nested-loop for tiny inputs and hash for large ones.)");
        System.out.println();
    }

    // =========================================================================
    // Section 1: Overview table
    // =========================================================================

    private static void printSection1Overview(BenchmarkResults results) {
        System.out.println("=== Section 1: Overview ===");
        System.out.printf("  %-10s  %12s  %8s  %9s  %9s  %s%n",
                "Strategy", "Total (ms)", "Regret", "P95 (ms)", "P99 (ms)", "Learn@");
        System.out.println("  " + "-".repeat(68));
        printMetricsRow(results.defaultMetrics());
        printMetricsRow(results.oracleMetrics());
        printMetricsRow(results.baoMetrics());
        printMetricsRow(results.leroMetrics());
        System.out.println();
        System.out.printf("  Bao total:    %,d ms (%+.1f%% vs. default)%n",
                results.baoTotal(),
                pctChange(results.defaultTotal(), results.baoTotal()));
        System.out.printf("  Lero total:   %,d ms (%+.1f%% vs. default)%n",
                results.leroTotal(),
                pctChange(results.defaultTotal(), results.leroTotal()));
        System.out.printf("  Oracle total: %,d ms (%+.1f%% vs. default — theoretical ceiling)%n",
                results.oracleTotal(),
                pctChange(results.defaultTotal(), results.oracleTotal()));
        System.out.println();
    }

    private static void printMetricsRow(StrategyMetrics m) {
        String learn = m.learningSpeedQuery() >= 0
                ? "@q" + m.learningSpeedQuery()
                : "never";
        System.out.printf("  %-10s  %,12d  %7.2fx  %,9d  %,9d  %s%n",
                m.name(), m.totalMs(), m.regretVsOracle(),
                m.p95Ms(), m.p99Ms(), learn);
    }

    // =========================================================================
    // Section 2: Learning curves
    // =========================================================================

    private static void printSection2LearningCurves(BenchmarkResults results, int workloadSize) {
        System.out.println("=== Section 2: Learning Curves (cumulative latency) ===");
        System.out.printf("  %-8s  %14s  %14s  %14s  %14s%n",
                "Query #", "DEFAULT (ms)", "ORACLE (ms)", "BAO (ms)", "LERO (ms)");
        System.out.println("  " + "-".repeat(70));

        long defCumul = 0, oraCumul = 0, baoCumul = 0, leroCumul = 0;
        int  prev     = 0;

        for (int checkpoint : new int[]{50, 100, 150, 200, 250, 300}) {
            int end = Math.min(checkpoint, workloadSize);
            for (int i = prev; i < end; i++) {
                QueryResult q = results.perQuery().get(i);
                defCumul  += q.defaultMs();
                oraCumul  += q.oracleMs();
                baoCumul  += q.baoMs();
                leroCumul += q.leroMs();
            }
            System.out.printf("  %-8d  %,14d  %,14d  %,14d  %,14d%n",
                    end, defCumul, oraCumul, baoCumul, leroCumul);
            prev = end;
            if (end >= workloadSize) break;
        }
        System.out.println();
    }

    // =========================================================================
    // Section 3: Bao deep-dive — arm selection across workload thirds
    // =========================================================================

    private static void printSection3BaoDeepDive(BenchmarkResults results, int workloadSize) {
        System.out.println("=== Section 3: Bao Deep-Dive ===");

        int third = workloadSize / 3;
        int[] boundaries = {0, third, third * 2, workloadSize};
        String[] labels  = {
            "1–" + third,
            (third + 1) + "–" + (third * 2),
            (third * 2 + 1) + "–" + workloadSize
        };

        // Count arm selections per workload period
        @SuppressWarnings("unchecked")
        Map<String, Integer>[] counts = new Map[3];
        for (int p = 0; p < 3; p++) counts[p] = new HashMap<>();

        for (int p = 0; p < 3; p++) {
            for (int i = boundaries[p]; i < boundaries[p + 1]; i++) {
                HintSet arm = results.perQuery().get(i).baoArm();
                String  key = arm != null ? arm.getName() : "(none)";
                counts[p].merge(key, 1, Integer::sum);
            }
        }

        // Arm selection frequency table
        System.out.println("  Arm selection frequency (queries selected per arm per workload third):");
        System.out.printf("  %-22s  %-12s  %-12s  %-12s%n",
                "Arm", labels[0], labels[1], labels[2]);
        System.out.println("  " + "-".repeat(62));

        for (HintSet arm : HintSet.allHintSets()) {
            String name = arm.getName();
            System.out.printf("  %-22s  %-12d  %-12d  %-12d%n",
                    name,
                    counts[0].getOrDefault(name, 0),
                    counts[1].getOrDefault(name, 0),
                    counts[2].getOrDefault(name, 0));
        }
        System.out.println();

        // Arm diversity per period — proxy for exploration vs. exploitation
        System.out.println("  Arm diversity per period (unique arms selected):");
        for (int p = 0; p < 3; p++) {
            long distinct = counts[p].values().stream().filter(v -> v > 0).count();
            String annotation = p == 0 ? "  <- exploration phase (high uncertainty)"
                              : p == 2 ? "  <- exploitation phase (model converging)"
                              : "";
            System.out.printf("  %s:  %d distinct arm(s)%s%n", labels[p], distinct, annotation);
        }
        System.out.println("  (Decreasing diversity indicates the value model is converging.)");
        System.out.println();
    }

    // =========================================================================
    // Section 4: Lero deep-dive — comparator accuracy + phase breakdown
    // =========================================================================

    private static void printSection4LeroDeepDive(
            Catalog catalog, List<ParsedQuery> workload, BenchmarkResults results) {

        System.out.println("=== Section 4: Lero Deep-Dive ===");

        // 4a. Comparator accuracy at checkpoints.
        // A fresh PlanExplorer + PairwiseComparator is trained incrementally so
        // we can report how ranking accuracy evolves as more data accumulates.
        System.out.println("  Comparator accuracy over time");
        System.out.println("  (trained on pairwise comparisons from each query's explored variants):");
        System.out.printf("  %-8s  %-14s  %-10s%n", "Query #", "Pairs seen", "Accuracy");
        System.out.println("  " + "-".repeat(36));

        PlanExplorer       explorer   = new PlanExplorer(catalog);
        PairwiseComparator comparator = new PairwiseComparator();
        int prev = 0;

        for (int checkpoint : new int[]{50, 100, 200, 300}) {
            int end = Math.min(checkpoint, workload.size());
            for (int i = prev; i < end; i++) {
                List<TrainingPair> newPairs =
                        explorer.exploreQuery(workload.get(i).logicalPlan());
                for (int epoch = 0; epoch < 10; epoch++) {
                    for (TrainingPair pair : newPairs) {
                        comparator.trainStep(pair.featuresA(), pair.featuresB(), pair.aIsFaster());
                    }
                }
            }
            List<TrainingPair> allPairs = explorer.getTrainingPairs();
            double accuracy = comparator.evaluateAccuracy(allPairs);
            System.out.printf("  %-8d  %-14d  %.1f%%%n", end, allPairs.size(), accuracy * 100.0);
            prev = end;
            if (end >= workload.size()) break;
        }
        System.out.println();

        // 4b. Warm-up vs. warm phase performance breakdown
        System.out.printf("  Phase breakdown (warm-up = first %d queries, cost-model fallback):%n",
                LeroOptimizer.WARMUP_QUERIES);

        long warmUpMs = 0, warmMs = 0;
        long defaultWarmUpMs = 0, defaultWarmMs = 0;
        int  warmUpN  = 0, warmN = 0;

        for (int i = 0; i < results.perQuery().size(); i++) {
            QueryResult q = results.perQuery().get(i);
            if (i < LeroOptimizer.WARMUP_QUERIES) {
                warmUpMs        += q.leroMs();
                defaultWarmUpMs += q.defaultMs();
                warmUpN++;
            } else {
                warmMs        += q.leroMs();
                defaultWarmMs += q.defaultMs();
                warmN++;
            }
        }

        System.out.printf("  Warm-up (%d queries): Lero %,d ms | Default %,d ms | avg %.2f ms/q%n",
                warmUpN, warmUpMs, defaultWarmUpMs,
                warmUpN > 0 ? (double) warmUpMs / warmUpN : 0.0);
        System.out.printf("  Warm    (%d queries): Lero %,d ms | Default %,d ms | avg %.2f ms/q%n",
                warmN, warmMs, defaultWarmMs,
                warmN > 0 ? (double) warmMs / warmN : 0.0);
        System.out.println();
    }

    // =========================================================================
    // Section 5: Head-to-head comparison
    // =========================================================================

    private static void printSection5HeadToHead(BenchmarkResults results) {
        System.out.println("=== Section 5: Head-to-Head ===");

        System.out.printf("  Plan agreement rate (Bao & Lero chose same plan): %.1f%%%n",
                results.baoLeroAgreementRate() * 100.0);
        System.out.println();

        int  baoWon = 0, leroWon = 0, tied = 0;
        long baoWonSavingTotal = 0, leroWonSavingTotal = 0;
        int  bothBeatDefault = 0, bothWorseThanDefault = 0;

        for (QueryResult q : results.perQuery()) {
            long defaultMs = q.defaultMs();
            long baoMs     = q.baoMs();
            long leroMs    = q.leroMs();

            if (baoMs < leroMs)      { baoWon++;  baoWonSavingTotal  += (leroMs - baoMs); }
            else if (leroMs < baoMs) { leroWon++; leroWonSavingTotal += (baoMs - leroMs); }
            else                     { tied++; }

            if (baoMs < defaultMs && leroMs < defaultMs) bothBeatDefault++;
            if (baoMs > defaultMs && leroMs > defaultMs) bothWorseThanDefault++;
        }

        int total = results.perQuery().size();
        System.out.println("  Per-query win/loss (lower latency = win):");
        System.out.printf("  Bao  beat Lero:  %4d / %d queries  (avg %s ms saved/query)%n",
                baoWon, total,
                baoWon > 0 ? String.format("%,.0f", (double) baoWonSavingTotal / baoWon) : "0");
        System.out.printf("  Lero beat Bao:   %4d / %d queries  (avg %s ms saved/query)%n",
                leroWon, total,
                leroWon > 0 ? String.format("%,.0f", (double) leroWonSavingTotal / leroWon) : "0");
        System.out.printf("  Tied:            %4d / %d queries%n", tied, total);
        System.out.println();

        System.out.println("  Collaboration & failure modes:");
        System.out.printf("  Both beat default:       %4d / %d queries  (%.1f%%)%n",
                bothBeatDefault, total, 100.0 * bothBeatDefault / total);
        System.out.printf("  Both worse than default: %4d / %d queries  (%.1f%%)%n",
                bothWorseThanDefault, total, 100.0 * bothWorseThanDefault / total);
        System.out.println();

        if (bothWorseThanDefault > 0) {
            System.out.println("  Note: queries where both strategies underperformed the default");
            System.out.println("  represent shared failure modes — likely queries with unusual");
            System.out.println("  plan shapes that fall outside the training distribution.");
        }
        System.out.println();
    }

    // =========================================================================
    // Utility helpers
    // =========================================================================

    private static void printTableSizes(Catalog catalog) {
        for (String name : List.of("customers", "products", "orders")) {
            if (catalog.hasTable(name)) {
                long rows = catalog.getTableMetadata(name).getRowCount();
                System.out.printf("  %-12s %,d rows%n", name, rows);
            }
        }
        System.out.println();
    }

    /** Returns the percentage change from {@code baseline} to {@code value}. */
    private static double pctChange(long baseline, long value) {
        if (baseline == 0) return 0.0;
        return 100.0 * (value - baseline) / baseline;
    }
}
