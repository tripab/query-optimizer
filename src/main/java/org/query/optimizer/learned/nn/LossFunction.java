package org.query.optimizer.learned.nn;

/**
 * Loss function interface for neural network training.
 *
 * <p>Each implementation provides both the scalar loss value (used to track
 * training progress) and the gradient of the loss with respect to the
 * network's output vector (used to seed backpropagation).
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link #mse()} — Mean Squared Error, used by Bao's latency predictor</li>
 *   <li>{@link #bce()} — Binary Cross-Entropy, used by Lero's pairwise comparator</li>
 * </ul>
 */
public interface LossFunction {

    /**
     * Computes the scalar loss between predicted and target vectors.
     *
     * @param predicted network output
     * @param target    ground-truth values, same length as {@code predicted}
     * @return non-negative loss value
     */
    double compute(double[] predicted, double[] target);

    /**
     * Computes the gradient of the loss with respect to each element of
     * {@code predicted}. This is passed directly into
     * {@link SimpleNeuralNetwork#trainStep} to seed backpropagation.
     *
     * @param predicted network output
     * @param target    ground-truth values
     * @return gradient vector, same length as {@code predicted}
     */
    double[] gradient(double[] predicted, double[] target);

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Mean Squared Error: {@code loss = mean((p - t)^2)},
     * {@code grad[i] = 2*(p[i] - t[i]) / n}.
     *
     * <p>Used by Bao's {@code PlanValueModel} to regress on {@code logicalCost}.
     */
    static LossFunction mse() {
        return new LossFunction() {
            @Override
            public double compute(double[] predicted, double[] target) {
                double sum = 0.0;
                for (int i = 0; i < predicted.length; i++) {
                    double diff = predicted[i] - target[i];
                    sum += diff * diff;
                }
                return sum / predicted.length;
            }

            @Override
            public double[] gradient(double[] predicted, double[] target) {
                double[] grad = new double[predicted.length];
                double scale = 2.0 / predicted.length;
                for (int i = 0; i < predicted.length; i++) {
                    grad[i] = scale * (predicted[i] - target[i]);
                }
                return grad;
            }
        };
    }

    /**
     * Binary Cross-Entropy: {@code loss = -t*log(p) - (1-t)*log(1-p)}.
     *
     * <p>Expects {@code predicted[0]} to be a raw logit (not yet passed through
     * sigmoid). The sigmoid is applied internally so that the combined
     * sigmoid+BCE gradient simplifies to {@code p - t}, which is numerically
     * stable and avoids vanishing gradients.
     *
     * <p>Used by Lero's {@code PairwiseComparator} for the binary "which plan
     * is faster" classification.
     */
    static LossFunction bce() {
        return new LossFunction() {
            @Override
            public double compute(double[] predicted, double[] target) {
                double p = ActivationFunction.sigmoid(predicted[0]);
                double t = target[0];
                return -(t * Math.log(p + 1e-8) + (1.0 - t) * Math.log(1.0 - p + 1e-8));
            }

            @Override
            public double[] gradient(double[] predicted, double[] target) {
                // Combined sigmoid + BCE gradient: d(loss)/d(logit) = sigmoid(logit) - target
                // This is the standard numerically-stable form.
                double p = ActivationFunction.sigmoid(predicted[0]);
                return new double[]{p - target[0]};
            }
        };
    }
}
