package org.query.optimizer.learned.nn;

/**
 * Static activation functions used by the neural network layers.
 *
 * <p>ReLU is applied element-wise to every hidden layer. Sigmoid is used
 * by {@link org.query.optimizer.learned.lero.PairwiseComparator} to convert
 * the classifier's scalar logit into a probability.
 */
public final class ActivationFunction {

    private ActivationFunction() {}

    // -------------------------------------------------------------------------
    // ReLU
    // -------------------------------------------------------------------------

    /** Applies ReLU element-wise: {@code max(0, x[i])} for each element. */
    public static double[] relu(double[] x) {
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            out[i] = Math.max(0.0, x[i]);
        }
        return out;
    }

    /**
     * ReLU sub-gradient: 1 if {@code x[i] > 0}, else 0.
     *
     * <p>Applied during backpropagation to gate the error signal through
     * hidden layers that used ReLU on the forward pass.
     */
    public static double[] reluDerivative(double[] x) {
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            out[i] = x[i] > 0.0 ? 1.0 : 0.0;
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Sigmoid
    // -------------------------------------------------------------------------

    /** Sigmoid: {@code 1 / (1 + exp(-x))}. Clamps input to [-500, 500] to avoid overflow. */
    public static double sigmoid(double x) {
        x = Math.max(-500.0, Math.min(500.0, x));
        return 1.0 / (1.0 + Math.exp(-x));
    }

    /** Sigmoid derivative: {@code σ(x) * (1 - σ(x))}. */
    public static double sigmoidDerivative(double x) {
        double s = sigmoid(x);
        return s * (1.0 - s);
    }
}
