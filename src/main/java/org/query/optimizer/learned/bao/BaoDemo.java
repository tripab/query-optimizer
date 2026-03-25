package org.query.optimizer.learned.bao;

import org.query.optimizer.SimpleCostModel;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.executor.Executor.ExecutionResult;
import org.query.optimizer.learned.common.DataGenerator;
import org.query.optimizer.learned.common.HintSet;
import org.query.optimizer.learned.common.PlanVariantGenerator;
import org.query.optimizer.learned.common.WorkloadGenerator;
import org.query.optimizer.learned.common.WorkloadGenerator.ParsedQuery;
import org.query.optimizer.physical.PhysicalNode;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end demonstration of Bao: Thompson Sampling bandit plan steering.
 *
 * <h2>What this demo shows</h2>
 * <ol>
 *   <li><b>Summary</b> — total workload latency: Bao vs. the default optimizer.</li>
 *   <li><b>Learning curve</b> — cumulative latency at query 50 / 100 / 150 / 200,
 *       showing Bao converging toward or below the baseline.</li>
 *   <li><b>Arm selection histogram</b> — how often each hint set was chosen by
 *       Thompson Sampling, revealing which plan variants Bao preferred.</li>
 *   <li><b>Top beneficiaries</b> — the 5 queries where Bao's plan was
 *       fastest relative to the default plan.</li>
 *   <li><b>Convergence</b> — the first query index after which Bao's rolling
 *       average latency stays below the baseline rolling average.</li>
 * </ol>
 *
 * <h2>How to run</h2>
 * <pre>{@code
 *   mvn -q exec:java -Dexec.mainClass=org.query.optimizer.learned.bao.BaoDemo
 * }</pre>
 */
public class BaoDemo {

    private static final int WORKLOAD_SIZE = 200;
    private static final int SCALE_FACTOR  = 2;   // ~2K customers, ~4K orders, ~1K products

    public static void main(String[] args) {
        // ------------------------------------------------------------------
        // 1. Setup: generate synthetic tables
        // ------------------------------------------------------------------
        Catalog catalog = new Catalog();
        System.out.println("=== Bao Demo: Bandit Plan Steering ===");
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
        // 4. Bao: run all queries with Thompson Sampling
        // ------------------------------------------------------------------
        System.out.println("Running BAO (Thompson Sampling)...");
        BanditOptimizer bao = new BanditOptimizer(catalog);
        List<BanditOptimizer.QueryMetrics> baoMetrics = bao.runWorkload(workload);
        long baoTotal = baoMetrics.stream().mapToLong(BanditOptimizer.QueryMetrics::actualLatencyMs).sum();
        System.out.printf("Bao total:      %,d ms%n%n", baoTotal);

        // ------------------------------------------------------------------
        // 5. Output sections
        // ------------------------------------------------------------------
        printSection1Summary(baselineTotal, baoTotal);
        printSection2LearningCurve(baselineLatencies, baoMetrics);
        printSection3ArmHistogram(baoMetrics);
        printSection4TopBeneficiaries(workload, baselineLatencies, baoMetrics);
        printSection5Convergence(baselineLatencies, baoMetrics);
    }

    // -------------------------------------------------------------------------
    // Baseline execution
    // -------------------------------------------------------------------------

    private static long[] runBaseline(Catalog catalog, List<ParsedQuery> workload) {
        SimpleCostModel    costModel  = new SimpleCostModel(catalog);
        PlanVariantGenerator varGen   = new PlanVariantGenerator(catalog, costModel);
        Executor           executor   = new Executor();
        long[]             latencies  = new long[workload.size()];

        for (int i = 0; i < workload.size(); i++) {
            Map<HintSet, PhysicalNode> variants =
                    varGen.generateVariants(workload.get(i).logicalPlan(), List.of(HintSet.DEFAULT));
            PhysicalNode plan = variants.values().iterator().next();
            ExecutionResult result = executor.execute(plan);
            latencies[i] = result.executionTimeMs();
        }
        return latencies;
    }

    // -------------------------------------------------------------------------
    // Section 1: Overview summary
    // -------------------------------------------------------------------------

    private static void printSection1Summary(long baselineTotal, long baoTotal) {
        System.out.println("=== Section 1: Summary ===");
        System.out.printf("  Default: %,6d ms total%n", baselineTotal);
        System.out.printf("  Bao:     %,6d ms total%n", baoTotal);
        long diff = baselineTotal - baoTotal;
        if (diff > 0) {
            System.out.printf("  Bao saved %,d ms (%.1f%% improvement)%n%n",
                    diff, 100.0 * diff / Math.max(baselineTotal, 1));
        } else {
            System.out.printf("  Bao used %,d ms more (%.1f%% overhead — still learning)%n%n",
                    -diff, 100.0 * (-diff) / Math.max(baselineTotal, 1));
        }
    }

    // -------------------------------------------------------------------------
    // Section 2: Learning curve at query 50 / 100 / 150 / 200
    // -------------------------------------------------------------------------

    private static void printSection2LearningCurve(
            long[] baselineLatencies,
            List<BanditOptimizer.QueryMetrics> baoMetrics) {

        System.out.println("=== Section 2: Learning Curve (cumulative latency) ===");
        System.out.printf("  %-10s  %-14s  %-14s  %-10s%n",
                "Query #", "Default (ms)", "Bao (ms)", "Delta");
        System.out.println("  " + "-".repeat(52));

        long baseCumul = 0;
        long baoCumul  = 0;
        int  n         = baoMetrics.size();

        for (int checkpoint : new int[]{50, 100, 150, 200}) {
            int end = Math.min(checkpoint, n);
            for (int i = (checkpoint == 50 ? 0 : checkpoint - 50); i < end; i++) {
                baseCumul += baselineLatencies[i];
                baoCumul  += baoMetrics.get(i).actualLatencyMs();
            }
            long delta = baseCumul - baoCumul;
            System.out.printf("  %-10d  %-14s  %-14s  %+,d ms%n",
                    end,
                    formatMs(baseCumul),
                    formatMs(baoCumul),
                    delta);
            if (end >= n) break;
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Section 3: Arm selection histogram
    // -------------------------------------------------------------------------

    private static void printSection3ArmHistogram(
            List<BanditOptimizer.QueryMetrics> baoMetrics) {

        System.out.println("=== Section 3: Arm Selection Histogram ===");
        Map<String, Integer> counts = new HashMap<>();
        for (BanditOptimizer.QueryMetrics m : baoMetrics) {
            counts.merge(m.selectedArm().getName(), 1, Integer::sum);
        }

        int total = baoMetrics.size();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> {
                    int    cnt  = e.getValue();
                    double pct  = 100.0 * cnt / total;
                    String bar  = "#".repeat((int) (pct / 2));
                    System.out.printf("  %-20s %4d (%5.1f%%)  %s%n",
                            e.getKey(), cnt, pct, bar);
                });
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Section 4: Top 5 queries where Bao beat baseline the most
    // -------------------------------------------------------------------------

    private static void printSection4TopBeneficiaries(
            List<ParsedQuery> workload,
            long[] baselineLatencies,
            List<BanditOptimizer.QueryMetrics> baoMetrics) {

        System.out.println("=== Section 4: Top Beneficiaries (Bao vs. Default) ===");

        record Row(int idx, long saving, String arm, String sql) {}

        List<Row> rows = new java.util.ArrayList<>();
        for (int i = 0; i < baoMetrics.size(); i++) {
            long saving = baselineLatencies[i] - baoMetrics.get(i).actualLatencyMs();
            if (saving > 0) {
                rows.add(new Row(i + 1, saving, baoMetrics.get(i).selectedArm().getName(),
                        truncate(workload.get(i).sql(), 60)));
            }
        }
        rows.sort(Comparator.comparingLong(Row::saving).reversed());

        if (rows.isEmpty()) {
            System.out.println("  No individual query improvements detected "
                    + "(model still exploring — run with a larger workload).");
        } else {
            System.out.printf("  %-6s  %-10s  %-20s  %s%n",
                    "Query", "Saved(ms)", "Arm", "SQL");
            System.out.println("  " + "-".repeat(70));
            rows.stream().limit(5).forEach(r ->
                    System.out.printf("  %-6d  %-10d  %-20s  %s%n",
                            r.idx(), r.saving(), r.arm(), r.sql()));
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Section 5: Convergence — first query after which Bao's rolling avg < baseline
    // -------------------------------------------------------------------------

    private static void printSection5Convergence(
            long[] baselineLatencies,
            List<BanditOptimizer.QueryMetrics> baoMetrics) {

        System.out.println("=== Section 5: Convergence ===");

        int window = 20;
        int firstConverged = -1;

        for (int i = window; i <= baoMetrics.size(); i++) {
            double baseAvg = 0, baoAvg = 0;
            for (int j = i - window; j < i; j++) {
                baseAvg += baselineLatencies[j];
                baoAvg  += baoMetrics.get(j).actualLatencyMs();
            }
            if (baoAvg < baseAvg) {
                firstConverged = i - window + 1;
                break;
            }
        }

        if (firstConverged > 0) {
            System.out.printf("  Bao first outperformed baseline (rolling avg over %d queries) "
                    + "starting at query #%d.%n", window, firstConverged);
        } else {
            System.out.printf("  Bao did not consistently outperform baseline within %d queries.%n",
                    baoMetrics.size());
            System.out.println("  This is expected when tables are small and latency differences "
                    + "are measured in single-digit milliseconds.");
        }
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

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
