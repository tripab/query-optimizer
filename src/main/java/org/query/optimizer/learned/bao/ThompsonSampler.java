package org.query.optimizer.learned.bao;

import org.query.optimizer.learned.common.HintSet;

import java.util.Map;
import java.util.Random;

/**
 * Thompson Sampling arm-selector for Bao's bandit optimizer.
 *
 * <p>For each candidate plan (arm), a single scalar is drawn from a Gaussian
 * whose mean and variance come from the {@link PlanValueModel} ensemble.  The
 * arm with the <em>lowest</em> sampled value is selected — "lowest" because
 * the model predicts latency (cost), not reward.
 *
 * <h2>Why Thompson Sampling?</h2>
 * <p>Greedy exploitation (always pick the arm with the lowest predicted mean)
 * converges too early on suboptimal plans when the model is inaccurate.  Pure
 * random exploration wastes execution budget on bad plans.  Thompson Sampling
 * naturally balances the two: arms with high uncertainty get a wide distribution,
 * so they are sampled more often — the model is "curious" about uncertain regions.
 *
 * <h2>Sample polarity</h2>
 * <p>Because we are minimising latency the sample is
 * {@code mean - |σ| * N(0,1)} — a low sample beats a high one.
 * Equivalently, {@code mean + σ * N(0,1)} with the conventional sign: when
 * {@code N(0,1) < 0} (half the time) the sampled value is below the mean,
 * which favors uncertain arms appropriately.
 */
public class ThompsonSampler {

    private final Random random;

    public ThompsonSampler() {
        this(new Random());
    }

    public ThompsonSampler(Random random) {
        this.random = random;
    }

    /**
     * Selects the arm with the lowest Thompson-sampled predicted latency.
     *
     * @param planFeatures map from {@link HintSet} to its featurized plan vector
     * @param model        ensemble latency predictor
     * @return the {@link HintSet} whose sampled value was lowest; never {@code null}
     *         (the map must have at least one entry)
     */
    public HintSet selectArm(Map<HintSet, double[]> planFeatures,
                             PlanValueModel model) {
        HintSet bestArm    = null;
        double  bestSample = Double.MAX_VALUE;

        for (Map.Entry<HintSet, double[]> entry : planFeatures.entrySet()) {
            PlanValueModel.PredictionWithUncertainty pred = model.predict(entry.getValue());
            double sigma  = Math.sqrt(pred.variance());
            double sample = pred.mean() + random.nextGaussian() * sigma;
            if (sample < bestSample) {
                bestSample = sample;
                bestArm    = entry.getKey();
            }
        }

        return bestArm;
    }
}
