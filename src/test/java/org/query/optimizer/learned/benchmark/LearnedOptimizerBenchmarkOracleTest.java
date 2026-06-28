package org.query.optimizer.learned.benchmark;

import org.junit.jupiter.api.Test;
import org.query.optimizer.learned.benchmark.LearnedOptimizerBenchmark.VariantCost;
import org.query.optimizer.learned.common.HintSet;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the oracle's plan-selection rule (task T3): the best variant is the
 * one with the lowest {@code logicalCost}, so a noisy single-shot timer cannot
 * flip which variant is declared the ceiling.
 */
class LearnedOptimizerBenchmarkOracleTest {

    @Test
    void picksLowestLogicalCostVariant() {
        // Latencies ~equal (sub-millisecond), so the deterministic tuple term decides.
        List<VariantCost> executed = List.of(
                new VariantCost(HintSet.DEFAULT, 100, 0),        // cost 1.0
                new VariantCost(HintSet.FORCE_NLJ, 5000, 0),     // cost 50.0
                new VariantCost(HintSet.NO_PUSHDOWN, 100, 3));   // cost 4.0
        assertEquals(HintSet.DEFAULT, LearnedOptimizerBenchmark.pickOracleArm(executed));
    }

    @Test
    void breaksTiesByLatencyWhenTuplesEqual() {
        List<VariantCost> executed = List.of(
                new VariantCost(HintSet.DEFAULT, 100, 9),     // cost 10.0
                new VariantCost(HintSet.FORCE_NLJ, 100, 2));   // cost 3.0
        assertEquals(HintSet.FORCE_NLJ, LearnedOptimizerBenchmark.pickOracleArm(executed));
    }

    @Test
    void returnsNullForEmptyInput() {
        assertNull(LearnedOptimizerBenchmark.pickOracleArm(List.of()));
    }
}
