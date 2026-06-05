package org.query.optimizer.learned.bao;

import org.junit.jupiter.api.Test;
import org.query.optimizer.learned.common.HintSet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ThompsonSampler} arm selection, with emphasis on its
 * documented "never returns null for a non-empty map" contract.
 */
class ThompsonSamplerTest {

    private static final List<HintSet> ARMS = HintSet.allHintSets();

    /** A value model whose mean prediction is simply {@code features[0]} (variance 0). */
    private static PlanValueModel deterministicModel() {
        return new PlanValueModel() {
            @Override
            public PredictionWithUncertainty predict(double[] planFeatures) {
                return new PredictionWithUncertainty(planFeatures[0], 0.0);
            }
        };
    }

    /** A value model that always predicts NaN (degenerate / numerically unstable). */
    private static PlanValueModel nanModel() {
        return new PlanValueModel() {
            @Override
            public PredictionWithUncertainty predict(double[] planFeatures) {
                return new PredictionWithUncertainty(Double.NaN, Double.NaN);
            }
        };
    }

    @Test
    void picksArmWithLowestPredictedLatency() {
        // variance 0 => sample == mean == features[0], so selection is deterministic.
        Map<HintSet, double[]> features = new LinkedHashMap<>();
        features.put(ARMS.get(0), new double[]{5.0});
        features.put(ARMS.get(1), new double[]{2.0});   // lowest
        features.put(ARMS.get(2), new double[]{9.0});

        ThompsonSampler sampler = new ThompsonSampler(new Random(1));
        HintSet chosen = sampler.selectArm(features, deterministicModel());

        assertEquals(ARMS.get(1), chosen);
    }

    @Test
    void returnsNonNullArmEvenWhenAllPredictionsAreNaN() {
        // Regression: NaN samples made `NaN < bestSample` always false, so the
        // sampler returned null and the caller NPE'd. It must still pick an arm.
        Map<HintSet, double[]> features = new LinkedHashMap<>();
        features.put(ARMS.get(0), new double[]{1.0});
        features.put(ARMS.get(1), new double[]{1.0});

        ThompsonSampler sampler = new ThompsonSampler(new Random(1));
        HintSet chosen = sampler.selectArm(features, nanModel());

        assertNotNull(chosen, "selectArm must never return null for a non-empty map");
        assertTrue(features.containsKey(chosen));
    }

    @Test
    void aFiniteArmBeatsNaNArms() {
        // Model returns NaN for everything except a feature flagged with a finite mean.
        PlanValueModel mixed = new PlanValueModel() {
            @Override
            public PredictionWithUncertainty predict(double[] planFeatures) {
                if (planFeatures[0] >= 0) {
                    return new PredictionWithUncertainty(planFeatures[0], 0.0);
                }
                return new PredictionWithUncertainty(Double.NaN, Double.NaN);
            }
        };

        Map<HintSet, double[]> features = new LinkedHashMap<>();
        features.put(ARMS.get(0), new double[]{-1.0});  // NaN prediction
        features.put(ARMS.get(1), new double[]{7.0});   // finite -> should win
        features.put(ARMS.get(2), new double[]{-1.0});  // NaN prediction

        ThompsonSampler sampler = new ThompsonSampler(new Random(1));
        HintSet chosen = sampler.selectArm(features, mixed);

        assertEquals(ARMS.get(1), chosen, "a finite-cost arm must be chosen over NaN arms");
    }

    @Test
    void singleEntryMapReturnsThatArm() {
        Map<HintSet, double[]> features = new LinkedHashMap<>();
        features.put(ARMS.get(0), new double[]{42.0});

        ThompsonSampler sampler = new ThompsonSampler(new Random(1));
        assertEquals(ARMS.get(0), sampler.selectArm(features, deterministicModel()));
    }
}
