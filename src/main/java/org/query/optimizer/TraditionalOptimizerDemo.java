package org.query.optimizer;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.executor.Executor.ExecutionResult;
import org.query.optimizer.physical.JoinAlgorithmCounts;
import org.query.optimizer.physical.PhysicalNode;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end showcase of the <em>traditional</em> (non-learned) optimizer:
 * parse → rule rewrites → DP join ordering → cardinality/cost annotation →
 * cost-based physical planning → execution.
 *
 * <p>For one representative three-way join the demo prints each stage and then
 * contrasts the strong configuration (all rules, DP join ordering, cost-based
 * join-algorithm selection) against a naive baseline (no rules, input join order,
 * forced nested-loop), showing that optimization lowers the estimated cost while
 * producing identical results.
 *
 * <h2>Dataset</h2>
 * <p>Tables use distinct column names across the schema so multi-way joins (and
 * DP reordering, which changes which table is leftmost) resolve unambiguously:
 * <pre>
 *   region  : region_id, region_name                       (3 rows)
 *   account : acct_id, acct_name, acct_region, acct_age     (60 rows)
 *   txn     : txn_id, txn_acct, txn_amount                  (300 rows)
 * </pre>
 *
 * <h2>Running</h2>
 * <pre>
 *   mvn -q compile exec:java -Dexec.mainClass=org.query.optimizer.TraditionalOptimizerDemo
 * </pre>
 */
public class TraditionalOptimizerDemo {

    private static final OptimizationOptions STRONG = new OptimizationOptions(
            true, true, true, JoinOrderPolicy.DP, JoinAlgorithmPolicy.COST_BASED);
    private static final OptimizationOptions NAIVE = new OptimizationOptions(
            false, false, false, JoinOrderPolicy.PRESERVE_INPUT, JoinAlgorithmPolicy.FORCE_NLJ);

    private static final String QUERY =
            "SELECT account.acct_name, txn.txn_amount, region.region_name FROM txn " +
                    "INNER JOIN account ON txn.txn_acct = account.acct_id " +
                    "INNER JOIN region ON account.acct_region = region.region_id " +
                    "WHERE region.region_name = 'West'";

    public static void main(String[] args) throws IOException {
        banner("Traditional Optimizer — Full Pipeline Demo");

        Path dataDir = Files.createTempDirectory("qo-trad-");
        try {
            Catalog catalog = generateData(dataDir);
            QueryOptimizer optimizer = new QueryOptimizer(catalog);

            System.out.println("SQL:\n  " + QUERY + "\n");

            // ---- Stage-by-stage under the strong configuration ----------------
            QueryOptimizer.OptimizationResult strong = optimizer.optimize(QUERY, STRONG);

            section("1. Initial logical plan (as parsed)");
            System.out.println(indent(strong.initialLogicalPlan().toPrettyString(), 2));

            section("2. Optimized logical plan (rules + DP join ordering + estimates)");
            System.out.println(indent(strong.optimizedLogicalPlan().toPrettyString(), 2));

            section("3. Physical plan (cost-based join selection + physical cost)");
            System.out.println(indent(strong.physicalPlan().toPrettyString(), 2));
            System.out.println("  Join algorithms chosen: " +
                    JoinAlgorithmCounts.of(strong.physicalPlan()));

            section("4. Execution");
            ExecutionResult strongResult = new Executor().execute(strong.physicalPlan());
            System.out.printf("  %d row(s) returned%n", strongResult.getResultCount());

            // ---- Strong vs naive comparison -----------------------------------
            section("5. Strong vs. naive baseline");
            QueryOptimizer.OptimizationResult naive = optimizer.optimize(QUERY, NAIVE);
            ExecutionResult naiveResult = new Executor().execute(naive.physicalPlan());

            PhysicalNode strongPlan = strong.physicalPlan();
            PhysicalNode naivePlan = naive.physicalPlan();
            System.out.printf("  %-8s  %-22s  %12s  %8s%n", "Config", "Join algorithms", "Est. cost", "Rows");
            System.out.printf("  %-8s  %-22s  %12.2f  %8d%n",
                    "naive", summarize(naivePlan), naivePlan.getEstimatedCost(), naiveResult.getResultCount());
            System.out.printf("  %-8s  %-22s  %12.2f  %8d%n",
                    "strong", summarize(strongPlan), strongPlan.getEstimatedCost(), strongResult.getResultCount());
            System.out.println();
            System.out.printf("  Same results: %s | Strong cost is %.1fx the naive cost%n",
                    strongResult.getResultCount() == naiveResult.getResultCount() ? "yes" : "NO",
                    naivePlan.getEstimatedCost() == 0 ? 0.0
                            : strongPlan.getEstimatedCost() / naivePlan.getEstimatedCost());

            banner("Done");
        } finally {
            deleteRecursively(dataDir);
        }
    }

    // -------------------------------------------------------------------------
    // Data
    // -------------------------------------------------------------------------

    private static Catalog generateData(Path dir) throws IOException {
        try (PrintWriter pw = new PrintWriter(new File(dir.toFile(), "region.csv"))) {
            pw.println("region_id:INTEGER,region_name:VARCHAR");
            pw.println("0,East");
            pw.println("1,West");
            pw.println("2,North");
        }
        try (PrintWriter pw = new PrintWriter(new File(dir.toFile(), "account.csv"))) {
            pw.println("acct_id:INTEGER,acct_name:VARCHAR,acct_region:INTEGER,acct_age:INTEGER");
            for (int i = 0; i < 60; i++) {
                pw.println(i + ",acct" + i + "," + (i % 3) + "," + (20 + (i % 40)));
            }
        }
        try (PrintWriter pw = new PrintWriter(new File(dir.toFile(), "txn.csv"))) {
            pw.println("txn_id:INTEGER,txn_acct:INTEGER,txn_amount:FLOAT");
            for (int i = 0; i < 300; i++) {
                pw.println(i + "," + (i % 60) + "," + String.format("%.2f", 10.0 + (i % 250)));
            }
        }

        Catalog catalog = new Catalog();
        catalog.loadTableFromCSV("region", dir.resolve("region.csv").toString());
        catalog.loadTableFromCSV("account", dir.resolve("account.csv").toString());
        catalog.loadTableFromCSV("txn", dir.resolve("txn.csv").toString());
        return catalog;
    }

    // -------------------------------------------------------------------------
    // Printing helpers
    // -------------------------------------------------------------------------

    private static String summarize(PhysicalNode plan) {
        JoinAlgorithmCounts c = JoinAlgorithmCounts.of(plan);
        return c.hashJoins() + "h/" + c.nestedLoopJoins() + "nlj";
    }

    private static void banner(String title) {
        String bar = "=".repeat(60);
        System.out.println(bar);
        System.out.println("  " + title);
        System.out.println(bar);
        System.out.println();
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("-".repeat(60));
        System.out.println("  " + title);
        System.out.println("-".repeat(60));
    }

    private static String indent(String text, int spaces) {
        String prefix = " ".repeat(spaces);
        return prefix + text.replace("\n", "\n" + prefix);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }
}
