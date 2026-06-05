package org.query.optimizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the optimizer selects the cardinality model per
 * {@link OptimizationOptions#cardinalityModelType()} and falls back to the
 * heuristic when no learned model is available (Phase 6, task P6-3).
 *
 * <p>A sentinel {@link CardinalityModel} that returns a recognizable constant is
 * injected as the "learned" model so the test can observe whether the optimizer
 * actually routed cardinality estimation through it.
 */
class OptimizerCardinalityModeTest {

    private static final Catalog catalog = new Catalog();
    private static final String DATA_DIR = "target/generated-test-resources/p6-mode";
    private static final long SENTINEL = 777L;
    private static final String SQL = "SELECT id FROM customers WHERE age > 40";

    /** A learned-model stand-in that returns a recognizable constant for any subplan. */
    private static final CardinalityModel SENTINEL_MODEL = node -> SENTINEL;

    private static final OptimizationOptions LEARNED = new OptimizationOptions(
            true, true, true, JoinOrderPolicy.DP, JoinAlgorithmPolicy.FORCE_HASH,
            CardinalityModelType.LEARNED);
    private static final OptimizationOptions HEURISTIC = OptimizationOptions.defaults();

    @BeforeAll
    static void setup() throws IOException {
        Files.createDirectories(Paths.get(DATA_DIR));
        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "customers.csv"))) {
            pw.println("id:INTEGER,age:INTEGER");
            for (int i = 1; i <= 10; i++) {
                pw.println(i + "," + (20 + i * 4));
            }
        }
        catalog.loadTableFromCSV("customers", DATA_DIR + "/customers.csv");
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.deleteIfExists(Paths.get(DATA_DIR + "/customers.csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR));
    }

    private long rootRows(QueryOptimizer optimizer, OptimizationOptions options) {
        return optimizer.optimize(SQL, options).optimizedLogicalPlan().getEstimatedRows();
    }

    @Test
    void defaultsAndFiveArgConstructorUseHeuristic() {
        assertEquals(CardinalityModelType.HEURISTIC, OptimizationOptions.defaults().cardinalityModelType());
        assertEquals(CardinalityModelType.HEURISTIC,
                new OptimizationOptions(true, true, true,
                        JoinOrderPolicy.DP, JoinAlgorithmPolicy.FORCE_HASH).cardinalityModelType());
    }

    @Test
    void learnedOptionRoutesEstimationThroughInjectedModel() {
        QueryOptimizer optimizer = new QueryOptimizer(catalog, SENTINEL_MODEL);
        assertEquals(SENTINEL, rootRows(optimizer, LEARNED),
                "LEARNED option must route cardinality estimation through the injected model");
    }

    @Test
    void heuristicOptionIgnoresInjectedLearnedModel() {
        QueryOptimizer optimizer = new QueryOptimizer(catalog, SENTINEL_MODEL);
        long rows = rootRows(optimizer, HEURISTIC);
        assertNotEquals(SENTINEL, rows, "HEURISTIC option must not use the learned model");
        assertTrue(rows > 0);
    }

    @Test
    void learnedOptionFallsBackToHeuristicWhenNoModelInjected() {
        QueryOptimizer withModel = new QueryOptimizer(catalog, SENTINEL_MODEL);
        QueryOptimizer withoutModel = new QueryOptimizer(catalog); // no learned model

        long fallbackRows = rootRows(withoutModel, LEARNED);
        assertNotEquals(SENTINEL, fallbackRows, "missing learned model must fall back to heuristic");
        // Fallback must match what the heuristic option produces.
        assertEquals(rootRows(withModel, HEURISTIC), fallbackRows);
    }
}
