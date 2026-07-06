package org.query.optimizer.learned.bao;

import org.junit.jupiter.api.Test;
import org.query.optimizer.learned.common.ExecutionFeedback;
import org.query.optimizer.learned.common.HintSet;
import org.query.optimizer.learned.common.PlanFeaturizer;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bao's value model must survive its retrains and rank a cheap plan below an
 * expensive one — the property Thompson sampling depends on. Before log-space
 * regression the ensemble diverged (to NaN, later to ~1e14 with clipping) on
 * its first retrain, making arm selection a constant policy.
 */
public class PlanValueModelTest {

    /** Distinct, plausible feature vectors for a cheap and an expensive plan. */
    private static double[] planFeatures(boolean expensive) {
        double[] f = new double[PlanFeaturizer.FEATURE_DIM];
        // operator-type + log-cost slots; the expensive plan has NLJ-typed
        // operators with much larger log(cost)/log(rows) values
        for (int slot = 0; slot < 4; slot++) {
            int off = slot * PlanFeaturizer.FEATURES_PER_SLOT;
            f[off] = expensive ? 4.0 : 5.0;             // nlj vs hash join type code
            f[off + 1] = expensive ? 15.0 : 7.0;        // log(rows)
            f[off + 2] = expensive ? 17.0 : 8.0;        // log(cost)
            f[off + 3] = 1.0;
            f[off + 5] = 2.0;
        }
        int g = PlanFeaturizer.MAX_OPERATOR_SLOTS * PlanFeaturizer.FEATURES_PER_SLOT;
        f[g] = 5;
        f[g + 1] = 3;
        f[g + 2] = expensive ? 15.0 : 7.0;
        f[g + 3] = expensive ? 17.0 : 8.0;
        return f;
    }

    private static ExecutionFeedback feedback(double[] features, long tuples, long latencyMs) {
        return new ExecutionFeedback("q", HintSet.DEFAULT, features,
                latencyMs, tuples, 0.0, tuples);
    }

    @Test
    void predictionsStayFiniteAndRankPlansAcrossCostScales() {
        double[] cheap = planFeatures(false);
        double[] expensive = planFeatures(true);

        // Local-scale latencies (sub-ms reads 0) and EC2-scale latencies both
        // must leave the model finite and correctly ordered: the work term
        // (tuples) carries the signal when latency quantizes to zero.
        long[][] scenarios = {
                // {cheapTuples, cheapMs, expensiveTuples, expensiveMs}
                {6_000, 0, 4_000_000, 300},
                {6_000, 2, 4_000_000, 2_800},
        };

        for (long[] sc : scenarios) {
            PlanValueModel model = new PlanValueModel(new Random(42));
            for (int i = 0; i < 30; i++) {
                boolean exp = (i % 2 == 1);
                model.addFeedback(exp
                        ? feedback(expensive, sc[2], sc[3])
                        : feedback(cheap, sc[0], sc[1]));
            }

            PlanValueModel.PredictionWithUncertainty pCheap = model.predict(cheap);
            PlanValueModel.PredictionWithUncertainty pExpensive = model.predict(expensive);

            assertTrue(Double.isFinite(pCheap.mean()) && Double.isFinite(pExpensive.mean()),
                    String.format("means must be finite: cheap=%s expensive=%s",
                            pCheap.mean(), pExpensive.mean()));
            assertTrue(Double.isFinite(pCheap.variance()) && Double.isFinite(pExpensive.variance()),
                    "variances must be finite");
            assertTrue(pCheap.mean() < pExpensive.mean(), String.format(
                    "cheap plan must predict lower cost: cheap=%.1f expensive=%.1f",
                    pCheap.mean(), pExpensive.mean()));
        }
    }
}
