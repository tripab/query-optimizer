package org.query.optimizer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.learned.bao.BanditOptimizer;
import org.query.optimizer.learned.common.DataGenerator;
import org.query.optimizer.learned.common.HintSet;
import org.query.optimizer.learned.common.WorkloadGenerator.ParsedQuery;
import org.query.optimizer.learned.lero.LeroOptimizer;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalPlanBuilder;
import org.query.optimizer.parser.SQLParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the learned optimizers running on top of the current
 * (stronger) traditional optimizer (Phase 5, task P5-3).
 *
 * <p>The workload deliberately includes the GROUP BY aggregation shape that used
 * to crash planning ("Column not found: p" from qualified GROUP BY parsing) and
 * is repeated enough to trigger value-model retraining, exercising the Thompson
 * sampler after training. This guards both the planning path and the learned
 * selection loop against regressions across the full pipeline.
 */
class LearnedPipelineRegressionTest {

    private static final Catalog catalog = new Catalog();
    private static final SQLParser parser = new SQLParser();
    private static LogicalPlanBuilder planBuilder;

    @BeforeAll
    static void setup() {
        DataGenerator.generate(catalog, 1); // small synthetic customers/products/orders
        planBuilder = new LogicalPlanBuilder(catalog);
    }

    private ParsedQuery query(String sql) {
        LogicalNode plan = planBuilder.build(parser.parse(sql));
        return new ParsedQuery(sql, plan);
    }

    /** A mixed workload covering scan, filter, join, and aggregation shapes. */
    private List<ParsedQuery> workload() {
        List<String> shapes = List.of(
                "SELECT id, name FROM customers",
                "SELECT id, name, price FROM products WHERE price > 100.00",
                "SELECT c.name, o.total FROM customers c " +
                        "INNER JOIN orders o ON c.id = o.customer_id WHERE o.total > 100.00",
                // The shape that previously crashed planning (qualified GROUP BY):
                "SELECT p.category, COUNT(*) FROM products p " +
                        "INNER JOIN orders o ON p.id = o.product_id GROUP BY p.category");

        List<ParsedQuery> workload = new ArrayList<>();
        for (int round = 0; round < 5; round++) {     // repeat to trigger retraining
            for (String sql : shapes) {
                workload.add(query(sql));
            }
        }
        return workload;
    }

    @Test
    void baoRunsMixedWorkloadIncludingAggregation() {
        List<ParsedQuery> workload = workload();

        List<BanditOptimizer.QueryMetrics> metrics =
                assertDoesNotThrow(() -> new BanditOptimizer(catalog, new Random(7)).runWorkload(workload));

        assertEquals(workload.size(), metrics.size(), "Bao must process every query");
        List<HintSet> arms = HintSet.allHintSets();
        for (BanditOptimizer.QueryMetrics m : metrics) {
            assertTrue(arms.contains(m.selectedArm()),
                    "selected arm must be a known hint set: " + m.selectedArm());
            assertNotNull(m.result());
        }
    }

    @Test
    void leroRunsMixedWorkloadIncludingAggregation() {
        List<ParsedQuery> workload = workload();

        List<LeroOptimizer.QueryMetrics> metrics =
                assertDoesNotThrow(() -> new LeroOptimizer(catalog, new Random(7)).runWorkload(workload));

        assertEquals(workload.size(), metrics.size(), "Lero must process every query");
        for (LeroOptimizer.QueryMetrics m : metrics) {
            assertNotNull(m.selectedPlan(), "Lero must select a plan for every query");
        }
    }

    @Test
    void aggregationQueryPlansAndExecutesThroughLearnedPipeline() {
        // Single-query sanity: the previously-crashing aggregation shape now runs.
        List<ParsedQuery> single = List.of(query(
                "SELECT p.category, COUNT(*) FROM products p " +
                        "INNER JOIN orders o ON p.id = o.product_id GROUP BY p.category"));

        List<BanditOptimizer.QueryMetrics> metrics =
                assertDoesNotThrow(() -> new BanditOptimizer(catalog, new Random(1)).runWorkload(single));
        assertEquals(1, metrics.size());
        assertTrue(metrics.get(0).result().getResultCount() >= 1,
                "aggregation should produce at least one group");
    }
}
