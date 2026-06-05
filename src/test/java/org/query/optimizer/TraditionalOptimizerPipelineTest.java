package org.query.optimizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.Tuple;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.executor.Executor.ExecutionResult;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end regression tests for the full "traditional" (non-learned) optimizer
 * pipeline (Phase 5, task P5-1): parse → rule rewrites → DP join ordering →
 * cardinality/cost annotation → cost-based physical planning → execution.
 *
 * <p>The strong configuration (all rules, DP join ordering, cost-based join
 * algorithm selection) is checked against a naive baseline (no rules, input join
 * order, forced nested-loop) on the same queries: optimization must preserve
 * results while not increasing the estimated plan cost.
 *
 * <h2>Fixtures</h2>
 * <p>Tables use <em>distinct</em> column names across the schema (region_id,
 * acct_id, txn_id, …) so that multi-way joins — and DP join reordering, which
 * changes which table is leftmost — resolve columns unambiguously. (Shared column
 * names like a common {@code id} are a known estimation/execution limitation.)
 * <pre>
 *   region  : region_id, region_name                       -- 3 rows
 *   account : acct_id, acct_name, acct_region, acct_age     -- 60 rows
 *   txn     : txn_id, txn_acct, txn_amount                  -- 300 rows
 * </pre>
 */
class TraditionalOptimizerPipelineTest {

    private static final Catalog catalog = new Catalog();
    private static QueryOptimizer optimizer;
    private static final String DATA_DIR = "target/generated-test-resources/p5-pipeline";

    private static final OptimizationOptions STRONG = new OptimizationOptions(
            true, true, true, JoinOrderPolicy.DP, JoinAlgorithmPolicy.COST_BASED);
    private static final OptimizationOptions NAIVE = new OptimizationOptions(
            false, false, false, JoinOrderPolicy.PRESERVE_INPUT, JoinAlgorithmPolicy.FORCE_NLJ);

    @BeforeAll
    static void setup() throws IOException {
        Files.createDirectories(Paths.get(DATA_DIR));

        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "region.csv"))) {
            pw.println("region_id:INTEGER,region_name:VARCHAR");
            pw.println("0,East");
            pw.println("1,West");
            pw.println("2,North");
        }
        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "account.csv"))) {
            pw.println("acct_id:INTEGER,acct_name:VARCHAR,acct_region:INTEGER,acct_age:INTEGER");
            for (int i = 0; i < 60; i++) {
                pw.println(i + ",acct" + i + "," + (i % 3) + "," + (20 + (i % 40)));
            }
        }
        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "txn.csv"))) {
            pw.println("txn_id:INTEGER,txn_acct:INTEGER,txn_amount:FLOAT");
            for (int i = 0; i < 300; i++) {
                pw.println(i + "," + (i % 60) + "," + String.format("%.2f", 10.0 + (i % 250)));
            }
        }

        catalog.loadTableFromCSV("region", DATA_DIR + "/region.csv");
        catalog.loadTableFromCSV("account", DATA_DIR + "/account.csv");
        catalog.loadTableFromCSV("txn", DATA_DIR + "/txn.csv");
        optimizer = new QueryOptimizer(catalog);
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.deleteIfExists(Paths.get(DATA_DIR + "/region.csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR + "/account.csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR + "/txn.csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ExecutionResult run(String sql, OptimizationOptions options) {
        return new Executor().execute(optimizer.optimize(sql, options).physicalPlan());
    }

    private static Set<String> serialise(List<Tuple> tuples) {
        return tuples.stream().map(t -> t.stream()
                        .sorted(Comparator.comparing(a -> a.getKey().name()))
                        .map(a -> a.getKey().name() + "=" + a.getValue())
                        .collect(Collectors.joining("|")))
                .collect(Collectors.toSet());
    }

    private static final String THREE_WAY_JOIN =
            "SELECT account.acct_name, txn.txn_amount, region.region_name FROM txn " +
                    "INNER JOIN account ON txn.txn_acct = account.acct_id " +
                    "INNER JOIN region ON account.acct_region = region.region_id " +
                    "WHERE region.region_name = 'West'";

    // -------------------------------------------------------------------------
    // Correctness: optimization must not change results
    // -------------------------------------------------------------------------

    @Test
    void strongPipelineProducesSameResultsAsNaiveBaseline() {
        ExecutionResult strong = run(THREE_WAY_JOIN, STRONG);
        ExecutionResult naive = run(THREE_WAY_JOIN, NAIVE);

        assertTrue(strong.getResultCount() > 0, "sanity: the query should match some rows");
        assertEquals(naive.getResultCount(), strong.getResultCount(),
                "row count must be identical regardless of optimization");
        assertEquals(serialise(naive.tuples()), serialise(strong.tuples()),
                "result set must be identical regardless of optimization");
    }

    // -------------------------------------------------------------------------
    // Cost: optimization must not make the plan more expensive
    // -------------------------------------------------------------------------

    @Test
    void strongPipelineIsNotMoreExpensiveThanNaiveBaseline() {
        double strongCost = optimizer.optimize(THREE_WAY_JOIN, STRONG).physicalPlan().getEstimatedCost();
        double naiveCost = optimizer.optimize(THREE_WAY_JOIN, NAIVE).physicalPlan().getEstimatedCost();

        assertTrue(strongCost <= naiveCost,
                "strong plan cost (" + strongCost + ") must not exceed naive cost (" + naiveCost + ")");
    }

    // -------------------------------------------------------------------------
    // Hardening: the full pipeline handles all supported query shapes
    // -------------------------------------------------------------------------

    @Test
    void fullPipelineHandlesRepresentativeQueryShapes() {
        List<String> queries = List.of(
                "SELECT acct_id, acct_name FROM account WHERE acct_age > 40",
                "SELECT account.acct_name, txn.txn_amount FROM account " +
                        "INNER JOIN txn ON account.acct_id = txn.txn_acct " +
                        "WHERE txn.txn_amount > 100.00",
                THREE_WAY_JOIN,
                "SELECT region.region_name, COUNT(*) FROM account " +
                        "INNER JOIN region ON account.acct_region = region.region_id " +
                        "GROUP BY region.region_name");

        for (String sql : queries) {
            ExecutionResult result = assertDoesNotThrow(() -> run(sql, STRONG),
                    "strong pipeline must plan and execute: " + sql);
            assertTrue(result.getResultCount() >= 0);
        }
    }

    @Test
    void aggregateThroughStrongPipelineMatchesNaive() {
        String sql = "SELECT region.region_name, COUNT(*) FROM account " +
                "INNER JOIN region ON account.acct_region = region.region_id " +
                "GROUP BY region.region_name";
        assertEquals(serialise(run(sql, NAIVE).tuples()), serialise(run(sql, STRONG).tuples()));
    }
}
