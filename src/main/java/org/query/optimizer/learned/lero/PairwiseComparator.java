package org.query.optimizer.learned.lero;

import org.query.optimizer.learned.nn.ActivationFunction;
import org.query.optimizer.learned.nn.SimpleNeuralNetwork;
import org.query.optimizer.learned.nn.SimpleNeuralNetwork.ForwardResult;

import java.util.List;
import java.util.Random;

/**
 * Lero's pairwise plan comparator: a Siamese neural network that learns to rank
 * physical plans without predicting absolute cost.
 *
 * <h2>Architecture</h2>
 * <pre>
 *   Plan A features (34) ──→ SharedEncoder (34→64→32) ──→ embA (32)
 *   Plan B features (34) ──→ SharedEncoder (34→64→32) ──→ embB (32)
 *   [embA ; embB ; embA−embB]  (96)  ──→  Classifier (96→32→1)
 *   Output: sigmoid  →  P(plan A is faster than plan B)
 * </pre>
 *
 * <p>The encoder is <em>shared</em>: both plans are processed by the same weights.
 * The 96-dim combined vector gives the classifier both absolute position information
 * ({@code embA}, {@code embB}) and relative difference ({@code embA − embB}), which
 * empirically improves ranking accuracy.
 *
 * <h2>Training</h2>
 * <p>Each call to {@link #trainStep} performs online SGD on a single labeled pair
 * (planA, planB, aIsFaster) using binary cross-entropy loss. Gradient flow:
 * <ol>
 *   <li>BCE gradient at sigmoid output: {@code pred − target}</li>
 *   <li>Backprop through classifier → gradient at the 96-dim combined input</li>
 *   <li>Split into per-branch gradients dEmbA and dEmbB</li>
 *   <li>Backprop through the shared encoder twice, once per branch</li>
 * </ol>
 *
 * <p>Applying two separate encoder updates (rather than accumulating the sum first)
 * is an approximation that works well in practice at these learning rates.
 */
public class PairwiseComparator {

    // Encoder: FEATURE_DIM plan features → 64 hidden → 32 embedding
    static final int[] ENCODER_SIZES    =
            {org.query.optimizer.learned.common.PlanFeaturizer.FEATURE_DIM, 64, 32};
    // Classifier: [embA; embB; embA−embB] = 96 → 32 hidden → 1 logit
    static final int[] CLASSIFIER_SIZES = {96, 32, 1};
    static final double LEARNING_RATE   = 0.001;

    private final SimpleNeuralNetwork encoder;
    private final SimpleNeuralNetwork classifier;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public PairwiseComparator() {
        this(new Random());
    }

    public PairwiseComparator(Random random) {
        this.encoder    = new SimpleNeuralNetwork(ENCODER_SIZES,    LEARNING_RATE, random);
        this.classifier = new SimpleNeuralNetwork(CLASSIFIER_SIZES, LEARNING_RATE, random);
    }

    // -------------------------------------------------------------------------
    // Inference
    // -------------------------------------------------------------------------

    /**
     * Predicts P(plan A is faster than plan B).
     *
     * @param featuresA plan feature vector for plan A
     *                  (length {@link org.query.optimizer.learned.common.PlanFeaturizer#FEATURE_DIM})
     * @param featuresB plan feature vector for plan B
     * @return probability in (0, 1); values above 0.5 mean A is predicted faster
     */
    public double compare(double[] featuresA, double[] featuresB) {
        double[] embA     = encoder.predict(featuresA);
        double[] embB     = encoder.predict(featuresB);
        double[] combined = concatenateWithDiff(embA, embB);
        double   logit    = classifier.predict(combined)[0];
        return ActivationFunction.sigmoid(logit);
    }

    // -------------------------------------------------------------------------
    // Training
    // -------------------------------------------------------------------------

    /**
     * Trains on a single labeled pair with online SGD.
     *
     * @param featuresA plan feature vector for plan A
     * @param featuresB plan feature vector for plan B
     * @param aIsFaster true if plan A had a lower observed execution latency
     * @return BCE loss value computed before this update
     */
    public double trainStep(double[] featuresA, double[] featuresB, boolean aIsFaster) {
        // --- Forward pass through both encoder branches ---
        ForwardResult fwdA = encoder.forward(featuresA);
        ForwardResult fwdB = encoder.forward(featuresB);
        double[] embA = fwdA.output();
        double[] embB = fwdB.output();

        // --- Forward pass through classifier ---
        double[] combined      = concatenateWithDiff(embA, embB);
        ForwardResult fwdClass = classifier.forward(combined);
        double logit           = fwdClass.output()[0];
        double pred            = ActivationFunction.sigmoid(logit);

        // --- BCE loss ---
        double target = aIsFaster ? 1.0 : 0.0;
        double loss   = -target       * Math.log(pred       + 1e-8)
                      - (1.0 - target) * Math.log(1.0 - pred + 1e-8);

        // --- Backprop through classifier → gradient at combined input ---
        // The combined gradient of BCE + sigmoid w.r.t. the pre-sigmoid logit
        // simplifies to (pred − target).
        double[] dClassifierOut = {pred - target};
        double[] dCombined = classifier.backpropReturnInputGrad(fwdClass, dClassifierOut);

        // --- Split dCombined into per-branch embedding gradients ---
        // combined = [embA (0..dim) ; embB (dim..2*dim) ; embA−embB (2*dim..3*dim)]
        //
        // Chain rule through the concatenation + difference:
        //   d/d_embA = dCombined[0..dim)  +  dCombined[2*dim..3*dim)
        //   d/d_embB = dCombined[dim..2*dim)  −  dCombined[2*dim..3*dim)
        int embDim = embA.length; // 32
        double[] dEmbA = new double[embDim];
        double[] dEmbB = new double[embDim];
        for (int i = 0; i < embDim; i++) {
            dEmbA[i] = dCombined[i]           + dCombined[2 * embDim + i];
            dEmbB[i] = dCombined[embDim + i]  - dCombined[2 * embDim + i];
        }

        // --- Backprop through shared encoder (two branches) ---
        // Each call updates the same encoder weights; the return value (gradient
        // at the plan-feature input) is not needed here.
        encoder.backpropReturnInputGrad(fwdA, dEmbA);
        encoder.backpropReturnInputGrad(fwdB, dEmbB);

        return loss;
    }

    // -------------------------------------------------------------------------
    // Tournament ranking
    // -------------------------------------------------------------------------

    /**
     * Selects the best plan from a list via round-robin pairwise comparison.
     *
     * <p>Every pair (i, j) is compared once. The plan with the most wins is
     * returned. O(n²) comparisons; with 5 hint sets that is 10 comparisons.
     *
     * @param planFeatures list of plan feature vectors to rank
     * @return index into {@code planFeatures} of the predicted fastest plan
     */
    public int tournamentSelect(List<double[]> planFeatures) {
        int n = planFeatures.size();
        if (n == 1) return 0;

        int[] wins = new int[n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double prob = compare(planFeatures.get(i), planFeatures.get(j));
                if (prob > 0.5) wins[i]++;
                else            wins[j]++;
            }
        }

        int best = 0;
        for (int i = 1; i < n; i++) {
            if (wins[i] > wins[best]) best = i;
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // Evaluation
    // -------------------------------------------------------------------------

    /**
     * Computes pairwise classification accuracy on a held-out test set.
     *
     * @param testSet labeled pairs (must be non-empty)
     * @return fraction of pairs correctly classified, in [0, 1]
     */
    public double evaluateAccuracy(List<TrainingPair> testSet) {
        if (testSet.isEmpty()) return 0.0;
        int correct = 0;
        for (TrainingPair pair : testSet) {
            boolean predicted = compare(pair.featuresA(), pair.featuresB()) > 0.5;
            if (predicted == pair.aIsFaster()) correct++;
        }
        return (double) correct / testSet.size();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Builds [embA ; embB ; embA − embB], total length 3 × embDim. */
    private static double[] concatenateWithDiff(double[] embA, double[] embB) {
        int      dim    = embA.length;
        double[] result = new double[3 * dim];
        System.arraycopy(embA, 0, result,       0, dim);
        System.arraycopy(embB, 0, result,     dim, dim);
        for (int i = 0; i < dim; i++) {
            result[2 * dim + i] = embA[i] - embB[i];
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    /**
     * A labeled pair of plans used for training and accuracy evaluation.
     *
     * @param featuresA featurized plan A
     * @param featuresB featurized plan B
     * @param aIsFaster true when plan A executed faster than plan B
     */
    public record TrainingPair(double[] featuresA, double[] featuresB, boolean aIsFaster) {}
}
