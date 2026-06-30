package org.query.optimizer.learned.benchmark;

import org.junit.jupiter.api.Test;
import org.query.optimizer.learned.benchmark.LearnedOptimizerBenchmark.VariantCost;
import org.query.optimizer.learned.common.HintSet;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the oracle's plan-selection rule (tasks T3/T6): the best variant is
 * the one that did the least tuple-processing work, with exact ties broken by arm
 * name. Selection ignores the run's measured latency, so a noisy single-shot timer
 * cannot flip which variant is declared the ceiling — the choice is reproducible.
 */
class LearnedOptimizerBenchmarkOracleTest {

    @Test
    void picksLeastTupleWorkVariantIgnoringLatency() {
        // FORCE_NLJ is the fastest this run but does the most tuple work; the
        // oracle ignores latency and picks the least-tuples arm.
        List<VariantCost> executed = List.of(
                new VariantCost(HintSet.DEFAULT, 100, 9),       // least tuples, slow this run
                new VariantCost(HintSet.FORCE_NLJ, 5000, 0));   // fastest this run, most tuples
        assertEquals(HintSet.DEFAULT, LearnedOptimizerBenchmark.pickOracleArm(executed));
    }

    @Test
    void breaksTiesByArmNameNotLatencyWhenTuplesEqual() {
        // Equal tuple work: the tie is broken by arm name, not by the (noisy)
        // latency, and not by list order — FORCE_NLJ is listed first and faster,
        // yet "default" < "force_nlj" wins, the same way on every run.
        List<VariantCost> executed = List.of(
                new VariantCost(HintSet.FORCE_NLJ, 100, 2),
                new VariantCost(HintSet.DEFAULT, 100, 9));
        assertEquals(HintSet.DEFAULT, LearnedOptimizerBenchmark.pickOracleArm(executed));
    }

    @Test
    void returnsNullForEmptyInput() {
        assertNull(LearnedOptimizerBenchmark.pickOracleArm(List.of()));
    }
}
