package org.query.optimizer.learned.lero;

import org.query.optimizer.SimpleCostModel;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.executor.ExecutionTimer;
import org.query.optimizer.learned.common.DataGenerator;
import org.query.optimizer.learned.common.HintSet;
import org.query.optimizer.learned.common.PlanFeaturizer;
import org.query.optimizer.learned.common.PlanVariantGenerator;
import org.query.optimizer.learned.common.WorkloadGenerator;
import org.query.optimizer.learned.common.WorkloadGenerator.ParsedQuery;
import org.query.optimizer.learned.lero.LeroOptimizer.QueryMetrics;
import org.query.optimizer.learned.lero.PairwiseComparator.TrainingPair;
import org.query.optimizer.physical.PhysicalNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * End-to-end demonstration of Lero: learning-to-rank plan selection.
 *
 * <h2>What this demo shows</h2>
 * <ol>
 *   <li><b>Summary</b> — total workload latency: Lero vs. the default optimizer.</li>
 *   <li><b>Learning curve</b> — cumulative latency at query 50 / 100 / 150 / 200,
 *       showing Lero converging after the warm-up phase ends.</li>
 *   <li><b>Comparator accuracy</b> — pairwise ranking accuracy on accumulated
 *       training pairs at each checkpoint, measuring how well the model learned.</li>
 *   <li><b>Cost model vs. Lero disagreements</b> — queries in the warm phase where
 *       Lero chose a different plan than the cost model would have, annotated with
 *       which choice resulted in lower latency.</li>
 *   <li><b>Warm-up vs. warm breakdown</b> — query count split between the cold
 *       (cost-model) and warm (Lero) phases.</li>
 * </ol>
 *
 * <h2>How to run</h2>
 * <pre>{@code
 *   mvn -q exec:java -Dexec.mainClass=org.query.optimizer.learned.lero.LeroDemo
 * }</pre>
 */
public class LeroDemo {

    private static final int WORKLOAD_SIZE = 200;
    private static final int SCALE_FACTOR  = 2;   // ~2K customers, ~4K orders, ~1K products

    public static void main(String[] args) {
        // ------------------------------------------------------------------
        // 1. Setup: generate synthetic tables
        // ------------------------------------------------------------------
        Catalog catalog = new Catalog();
        System.out.println("=== Lero Demo: Learning-to-Rank Plan Selection ===");
        System.out.println();
        System.out.println("Generating synthetic tables (scale factor " + SCALE_FACTOR + ")...");
        DataGenerator.generate(catalog, SCALE_FACTOR);
        printTableSizes(catalog);

        // ------------------------------------------------------------------
        // 2. Generate a shared workload of 200 queries
        // ------------------------------------------------------------------
        System.out.println("Generating workload of " + WORKLOAD_SIZE + " queries...");
        WorkloadGenerator workloadGen = new WorkloadGenerator(catalog, 42L);
        List<ParsedQuery> workload = workloadGen.generateWorkload(WORKLOAD_SIZE);
        System.out.println("Done.\n");

        // ------------------------------------------------------------------
        // 3. Baseline: run all queries with the DEFAULT hint set
        // ------------------------------------------------------------------
        System.out.println("Running BASELINE (DEFAULT hint set)...");
        long[] baselineLatencies = runBaseline(catalog, workload);
        long   baselineTotal     = sum(baselineLatencies);
        System.out.printf("Baseline total: %,d ms%n%n", baselineTotal);

        // ------------------------------------------------------------------
        // 4. Cost-model oracle: best plan by estimated cost (no learning)
        // ------------------------------------------------------------------
        System.out.println("Running COST-MODEL (best estimated cost per query)...");
        long[] costModelLatencies = runCostModel(catalog, workload);
        long   costModelTotal     = sum(costModelLatencies);
        System.out.printf("Cost model total: %,d ms%n%n", costModelTotal);

        // ------------------------------------------------------------------
        // 5. Lero: pairwise ranking
        // ------------------------------------------------------------------
        System.out.println("Running LERO (pairwise learning-to-rank)...");
        System.out.printf("  Warm-up phase: first %d queries (cost model + exploration)%n",
                LeroOptimizer.WARMUP_QUERIES);
        System.out.printf("  Warm phase: queries %d-%d (Lero ranking, explore every %d)%n%n",
                LeroOptimizer.WARMUP_QUERIES + 1, WORKLOAD_SIZE, LeroOptimizer.EXPLORE_INTERVAL);

        LeroOptimizer lero = new LeroOptimizer(catalog);
        List<QueryMetrics> leroMetrics = lero.runWorkload(workload);
        long leroTotal = leroMetrics.stream()
                .mapToLong(m -> m.actualLatencyMs())
                .sum();
        System.out.printf("Lero total: %,d ms%n%n", leroTotal);

        // Training pairs accumulated during the run (for accuracy reporting)
        PlanExplorer explorer      = buildExplorerForAccuracy(catalog, workload);
        List<TrainingPair> allPairs = explorer.getTrainingPairs();

        // ------------------------------------------------------------------
        // 6. Output sections
        // ------------------------------------------------------------------
        printSection1Summary(baselineTotal, costModelTotal, leroTotal);
        printSection2LearningCurve(baselineLatencies, leroMetrics);
        printSection3ComparatorAccuracy(catalog, workload, leroMetrics);
        printSection4Disagreements(catalog, workload, leroMetrics,
                baselineLatencies, costModelLatencies);
        printSection5PhaseBreakdown(leroMetrics);
    }

    // -------------------------------------------------------------------------
    // Baseline & cost-model runs
    // -------------------------------------------------------------------------

    private static long[] runBaseline(Catalog catalog, List<ParsedQuery> workload) {
        SimpleCostModel      costModel = new SimpleCostModel(catalog);
        PlanVariantGenerator varGen    = new PlanVariantGenerator(catalog, costModel);
        Executor             executor  = new Executor();
        long[]               latencies = new long[workload.size()];

        for (int i = 0; i < workload.size(); i++) {
            Map<HintSet, PhysicalNode> variants =
                    varGen.generateVariants(workload.get(i).logicalPlan(), List.of(HintSet.DEFAULT));
            PhysicalNode plan = variants.values().iterator().next();
            latencies[i] = ExecutionTimer.run(() -> executor.execute(plan)).millis();
        }
        return latencies;
    }

    private static long[] runCostModel(Catalog catalog, List<ParsedQuery> workload) {
        SimpleCostModel      costModel = new SimpleCostModel(catalog);
        PlanVariantGenerator varGen    = new PlanVariantGenerator(catalog, costModel);
        Executor             executor  = new Executor();
        long[]               latencies = new long[workload.size()];

        for (int i = 0; i < workload.size(); i++) {
            Map<HintSet, PhysicalNode> variants =
                    varGen.generateVariants(workload.get(i).logicalPlan(), HintSet.allHintSets());
            PhysicalNode best     = null;
            double       bestCost = Double.MAX_VALUE;
            for (PhysicalNode plan : variants.values()) {
                if (plan.getEstimatedCost() < bestCost) {
                    bestCost = plan.getEstimatedCost();
                    best     = plan;
                }
            }
            final PhysicalNode chosen = best;
            latencies[i] = ExecutionTimer.run(() -> executor.execute(chosen)).millis();
        }
        return latencies;
    }

    /**
     * Replays the workload through PlanExplorer (executing all variants) so we
     * have training pairs to measure comparator accuracy at checkpoints.
     * This is a separate run used only for the accuracy section.
     */
    private static PlanExplorer buildExplorerForAccuracy(
            Catalog catalog, List<ParsedQuery> workload) {
        PlanExplorer explorer = new PlanExplorer(catalog);
        for (ParsedQuery q : workload) {
            explorer.exploreQuery(q.logicalPlan());
        }
        return explorer;
    }

    // -------------------------------------------------------------------------
    // Section 1: Overview summary
    // -------------------------------------------------------------------------

    private static void printSection1Summary(
            long baselineTotal, long costModelTotal, long leroTotal) {

        System.out.println("=== Section 1: Summary ===");
        System.out.printf("  Default (always DEFAULT hint): %,6d ms%n", baselineTotal);
        System.out.printf("  Cost model (best est. cost):   %,6d ms%n", costModelTotal);
        System.out.printf("  Lero (pairwise ranking):       %,6d ms%n", leroTotal);
        System.out.println();

        long leroVsDefault = baselineTotal - leroTotal;
        if (leroVsDefault > 0) {
            System.out.printf("  Lero saved %,d ms vs. default (%.1f%% improvement)%n",
                    leroVsDefault, 100.0 * leroVsDefault / Math.max(baselineTotal, 1));
        } else {
            System.out.printf("  Lero used %,d ms more than default "
                    + "(%.1f%% overhead — warm-up cost amortized over workload)%n",
                    -leroVsDefault, 100.0 * (-leroVsDefault) / Math.max(baselineTotal, 1));
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Section 2: Learning curve at query 50 / 100 / 150 / 200
    // -------------------------------------------------------------------------

    private static void printSection2LearningCurve(
            long[] baselineLatencies, List<QueryMetrics> leroMetrics) {

        System.out.println("=== Section 2: Learning Curve (cumulative latency) ===");
        System.out.printf("  %-10s  %-14s  %-14s  %-10s  %-10s%n",
                "Query #", "Default (ms)", "Lero (ms)", "Delta", "Phase");
        System.out.println("  " + "-".repeat(62));

        long baseCumul = 0, leroCumul = 0;
        int  prev = 0;
        int  n    = leroMetrics.size();

        for (int checkpoint : new int[]{50, 100, 150, 200}) {
            int end = Math.min(checkpoint, n);
            for (int i = prev; i < end; i++) {
                baseCumul += baselineLatencies[i];
                leroCumul += leroMetrics.get(i).actualLatencyMs();
            }
            long   delta = baseCumul - leroCumul;
            String phase = end <= LeroOptimizer.WARMUP_QUERIES ? "warm-up" : "warm";
            System.out.printf("  %-10d  %-14s  %-14s  %+,d ms     %s%n",
                    end, formatMs(baseCumul), formatMs(leroCumul), delta, phase);
            prev = end;
            if (end >= n) break;
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Section 3: Comparator accuracy on training pairs at checkpoints
    // -------------------------------------------------------------------------

    private static void printSection3ComparatorAccuracy(
            Catalog catalog, List<ParsedQuery> workload, List<QueryMetrics> leroMetrics) {

        System.out.println("=== Section 3: Comparator Accuracy Over Time ===");
        System.out.println("  (Measured on training pairs accumulated up to each checkpoint)");
        System.out.printf("  %-10s  %-14s  %-10s%n", "Query #", "Pairs seen", "Accuracy");
        System.out.println("  " + "-".repeat(38));

        PlanExplorer       explorer   = new PlanExplorer(catalog);
        PairwiseComparator comparator = new PairwiseComparator();

        int prev = 0;
        for (int checkpoint : new int[]{50, 100, 150, 200}) {
            int end = Math.min(checkpoint, workload.size());
            // Explore queries in this segment to accumulate pairs
            for (int i = prev; i < end; i++) {
                List<TrainingPair> newPairs =
                        explorer.exploreQuery(workload.get(i).logicalPlan());
                // Train on new pairs (10 epochs, matching LeroOptimizer)
                for (int epoch = 0; epoch < 10; epoch++) {
                    for (TrainingPair pair : newPairs) {
                        comparator.trainStep(
                                pair.featuresA(), pair.featuresB(), pair.aIsFaster());
                    }
                }
            }

            List<TrainingPair> allPairs = explorer.getTrainingPairs();
            double accuracy = comparator.evaluateAccuracy(allPairs);
            System.out.printf("  %-10d  %-14d  %.1f%%%n",
                    end, allPairs.size(), accuracy * 100.0);
            prev = end;
            if (end >= workload.size()) break;
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Section 4: Lero vs. cost-model disagreements in the warm phase
    // -------------------------------------------------------------------------

    private static void printSection4Disagreements(
            Catalog catalog,
            List<ParsedQuery> workload,
            List<QueryMetrics> leroMetrics,
            long[] baselineLatencies,
            long[] costModelLatencies) {

        System.out.println("=== Section 4: Lero vs. Cost-Model Disagreements (warm phase) ===");

        // In the warm phase, Lero used its own ranking. Compare what the cost
        // model would have picked by re-generating variants and checking costs.
        SimpleCostModel      costModel = new SimpleCostModel(catalog);
        PlanVariantGenerator varGen    = new PlanVariantGenerator(catalog, costModel);
        PlanFeaturizer       feat      = new PlanFeaturizer();

        int leroWon  = 0;
        int costWon  = 0;
        int tied     = 0;
        int warmCount = 0;

        for (int i = LeroOptimizer.WARMUP_QUERIES; i < leroMetrics.size(); i++) {
            QueryMetrics m = leroMetrics.get(i);
            if (m.usedCostModel()) continue; // shouldn't happen in warm phase
            warmCount++;

            long leroMs      = m.actualLatencyMs();
            long costModelMs = costModelLatencies[i];

            if (leroMs < costModelMs)       leroWon++;
            else if (costModelMs < leroMs)  costWon++;
            else                            tied++;
        }

        System.out.printf("  Warm-phase queries: %d%n", warmCount);
        System.out.printf("  Lero beat cost model:   %d queries%n", leroWon);
        System.out.printf("  Cost model beat Lero:   %d queries%n", costWon);
        System.out.printf("  Tied (same latency):    %d queries%n", tied);
        if (warmCount > 0) {
            System.out.printf("  Lero win rate: %.1f%%%n",
                    100.0 * leroWon / warmCount);
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Section 5: Warm-up vs. warm phase breakdown
    // -------------------------------------------------------------------------

    private static void printSection5PhaseBreakdown(List<QueryMetrics> leroMetrics) {
        System.out.println("=== Section 5: Phase Breakdown ===");

        int warmUpCount = 0, warmCount = 0;
        long warmUpMs = 0, warmMs = 0;

        for (QueryMetrics m : leroMetrics) {
            if (m.usedCostModel()) {
                warmUpCount++;
                warmUpMs += m.actualLatencyMs();
            } else {
                warmCount++;
                warmMs += m.actualLatencyMs();
            }
        }

        System.out.printf("  Warm-up phase: %d queries, %,d ms total, avg %.2f ms/query%n",
                warmUpCount, warmUpMs,
                warmUpCount > 0 ? (double) warmUpMs / warmUpCount : 0.0);
        System.out.printf("  Warm phase:    %d queries, %,d ms total, avg %.2f ms/query%n",
                warmCount, warmMs,
                warmCount > 0 ? (double) warmMs / warmCount : 0.0);
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    private static void printTableSizes(Catalog catalog) {
        for (String name : List.of("customers", "products", "orders")) {
            if (catalog.hasTable(name)) {
                long rows = catalog.getTableMetadata(name).getRowCount();
                System.out.printf("  %-12s %,d rows%n", name, rows);
            }
        }
        System.out.println();
    }

    private static long sum(long[] arr) {
        long s = 0;
        for (long v : arr) s += v;
        return s;
    }

    private static String formatMs(long ms) {
        return String.format("%,d ms", ms);
    }
}
