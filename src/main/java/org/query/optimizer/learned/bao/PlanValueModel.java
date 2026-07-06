package org.query.optimizer.learned.bao;

import org.query.optimizer.learned.common.ExecutionFeedback;
import org.query.optimizer.learned.common.PlanFeaturizer;
import org.query.optimizer.learned.nn.LossFunction;
import org.query.optimizer.learned.nn.SimpleNeuralNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Ensemble latency predictor for Bao's Thompson Sampling bandit.
 *
 * <p>Wraps {@value #ENSEMBLE_SIZE} independently-trained {@link SimpleNeuralNetwork}
 * instances that each predict the {@link ExecutionFeedback#logicalCost()} of a
 * physical plan from its feature vector.  Because each member is trained on a
 * different bootstrap sample of the replay buffer, they naturally disagree on
 * plans the model hasn't seen, producing well-calibrated uncertainty estimates.
 *
 * <h2>Uncertainty estimation</h2>
 * <p>{@link #predict} returns both the ensemble mean prediction and its variance.
 * {@link ThompsonSampler} draws a Gaussian sample from this distribution for each
 * candidate plan and picks the one with the lowest sampled cost — implementing
 * Thompson Sampling without requiring an explicit Bayesian model.
 *
 * <h2>Retraining schedule</h2>
 * <p>After every {@value #RETRAIN_INTERVAL} calls to {@link #addFeedback}, all
 * ensemble members are retrained from scratch on a fresh bootstrap sample.
 * This keeps the model current without requiring incremental / online updates
 * (which would need careful learning-rate scheduling to avoid catastrophic
 * forgetting).
 */
public class PlanValueModel {

    static final int ENSEMBLE_SIZE    = 3;
    static final int RETRAIN_INTERVAL = 10;

    private static final int[]   LAYER_SIZES    = {PlanFeaturizer.FEATURE_DIM, 64, 32, 1};
    private static final double  LEARNING_RATE  = 0.001;
    private static final int     RETRAIN_EPOCHS = 20;

    private SimpleNeuralNetwork[]        ensemble;
    private final List<ExecutionFeedback> replayBuffer = new ArrayList<>();
    private final Random                  random;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public PlanValueModel() {
        this(new Random());
    }

    public PlanValueModel(Random random) {
        this.random = random;
        this.ensemble = createFreshEnsemble();
    }

    // -------------------------------------------------------------------------
    // Prediction
    // -------------------------------------------------------------------------

    /**
     * Returns the ensemble mean and variance of predicted latency for
     * {@code planFeatures}.
     *
     * <p>Before any training data has been collected the ensemble members
     * produce whatever their random initialisation gives, so the variance will
     * be non-zero and Thompson Sampling will explore freely.
     *
     * @param planFeatures featurized plan vector (length {@code LAYER_SIZES[0]})
     * @return mean and variance across all ensemble members
     */
    public PredictionWithUncertainty predict(double[] planFeatures) {
        double sum   = 0.0;
        double sumSq = 0.0;
        for (SimpleNeuralNetwork member : ensemble) {
            double p = member.predict(planFeatures)[0];
            sum   += p;
            sumSq += p * p;
        }
        double mean     = sum / ENSEMBLE_SIZE;
        double variance = Math.max(sumSq / ENSEMBLE_SIZE - mean * mean, 0.0);
        return new PredictionWithUncertainty(mean, variance);
    }

    // -------------------------------------------------------------------------
    // Learning
    // -------------------------------------------------------------------------

    /**
     * Records a new execution outcome and retrains all ensemble members if the
     * buffer has grown by another {@value #RETRAIN_INTERVAL} entries.
     *
     * @param feedback observed execution outcome
     */
    public void addFeedback(ExecutionFeedback feedback) {
        replayBuffer.add(feedback);
        if (replayBuffer.size() % RETRAIN_INTERVAL == 0) {
            retrain();
        }
    }

    /**
     * Returns the number of feedback examples collected so far (read-only,
     * for testing and demo output).
     */
    public int replayBufferSize() {
        return replayBuffer.size();
    }

    // -------------------------------------------------------------------------
    // Internal retraining
    // -------------------------------------------------------------------------

    /**
     * Retrains each ensemble member from scratch on a bootstrap sample of the
     * replay buffer.  Using a fresh network (rather than continuing to train the
     * existing one) avoids learning-rate decay artefacts and ensures each member
     * sees a genuinely different view of the data.
     */
    private void retrain() {
        LossFunction mse = LossFunction.mse();
        for (int i = 0; i < ENSEMBLE_SIZE; i++) {
            ensemble[i] = new SimpleNeuralNetwork(LAYER_SIZES, LEARNING_RATE, new Random(random.nextLong()),
                    SimpleNeuralNetwork.DEFAULT_GRADIENT_CLIP);
            List<ExecutionFeedback> sample = bootstrapSample(replayBuffer);
            for (int epoch = 0; epoch < RETRAIN_EPOCHS; epoch++) {
                for (ExecutionFeedback fb : sample) {
                    ensemble[i].trainStep(
                            fb.planFeatures(),
                            new double[]{fb.logicalCost()},
                            mse);
                }
            }
        }
    }

    /**
     * Draws a bootstrap sample (sampling with replacement) of the same size as
     * {@code source}.
     */
    private List<ExecutionFeedback> bootstrapSample(List<ExecutionFeedback> source) {
        List<ExecutionFeedback> sample = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            sample.add(source.get(random.nextInt(source.size())));
        }
        return sample;
    }

    private SimpleNeuralNetwork[] createFreshEnsemble() {
        SimpleNeuralNetwork[] members = new SimpleNeuralNetwork[ENSEMBLE_SIZE];
        for (int i = 0; i < ENSEMBLE_SIZE; i++) {
            members[i] = new SimpleNeuralNetwork(LAYER_SIZES, LEARNING_RATE, new Random(random.nextLong()),
                    SimpleNeuralNetwork.DEFAULT_GRADIENT_CLIP);
        }
        return members;
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /**
     * Ensemble prediction: the mean predicted latency and the variance across
     * ensemble members.  A high variance signals that the model is uncertain
     * about this plan region, which Thompson Sampling uses to encourage
     * exploration.
     */
    public record PredictionWithUncertainty(double mean, double variance) {}
}
