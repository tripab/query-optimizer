package org.query.optimizer.learned;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.learned.bao.BanditOptimizer;
import org.query.optimizer.learned.common.DataGenerator;
import org.query.optimizer.learned.common.HintSet;
import org.query.optimizer.learned.common.WorkloadGenerator;
import org.query.optimizer.learned.common.WorkloadGenerator.ParsedQuery;
import org.query.optimizer.learned.lero.LeroOptimizer;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises both learned optimizers end-to-end after the timing-seam migration
 * (task T2). Asserts that a full workload runs and that per-query metrics carry
 * the explicit, nanoTime-sourced {@code actualLatencyMs} that replaced reading
 * the time off {@code ExecutionResult}.
 */
class OnlineLearnerTimingTest {

    static Catalog catalog;
    static List<ParsedQuery> workload;

    @BeforeAll
    static void setup() {
        catalog = new Catalog();
        DataGenerator.generate(catalog, 1);                 // small synthetic tables
        workload = new WorkloadGenerator(catalog, 7L).generateWorkload(10);
        assertFalse(workload.isEmpty(), "workload generator should produce queries");
    }

    @Test
    void baoPopulatesMetricsAndLatencyForEveryQuery() {
        List<BanditOptimizer.QueryMetrics> metrics =
                new BanditOptimizer(catalog, new Random(1)).runWorkload(workload);

        assertEquals(workload.size(), metrics.size(), "one metric per query");
        boolean anyTuplesProcessed = false;
        for (BanditOptimizer.QueryMetrics m : metrics) {
            assertNotNull(m.selectedArm(), "an arm must be selected");
            assertTrue(HintSet.allHintSets().contains(m.selectedArm()),
                    "selected arm must come from the registry: " + m.selectedArm());
            assertTrue(m.actualLatencyMs() >= 0, "latency must be non-negative");
            assertEquals(m.result().getResultCount(), m.result().tuples().size(),
                    "result count and tuple list must agree");
            if (m.result().tuplesProcessed() > 0) anyTuplesProcessed = true;
        }
        assertTrue(anyTuplesProcessed, "at least one query should process tuples");
    }

    @Test
    void leroPopulatesExplicitLatencyForEveryQuery() {
        List<LeroOptimizer.QueryMetrics> metrics =
                new LeroOptimizer(catalog, new Random(1)).runWorkload(workload);

        assertEquals(workload.size(), metrics.size(), "one metric per query");
        for (LeroOptimizer.QueryMetrics m : metrics) {
            assertNotNull(m.selectedPlan(), "a plan must be selected");
            assertTrue(m.actualLatencyMs() >= 0,
                    "actualLatencyMs must be populated, not read off ExecutionResult");
        }
    }
}
