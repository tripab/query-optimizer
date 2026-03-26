package org.query.optimizer.learned.nn;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * A minimal feedforward neural network with backpropagation.
 *
 * <h2>Architecture</h2>
 * <p>Layer sizes are specified as an {@code int[]} at construction time.
 * For example, {@code new int[]{34, 64, 32, 1}} creates:
 * <ul>
 *   <li>Input layer: 34 units</li>
 *   <li>Hidden layer 1: 64 units (ReLU)</li>
 *   <li>Hidden layer 2: 32 units (ReLU)</li>
 *   <li>Output layer: 1 unit (linear)</li>
 * </ul>
 * Hidden layers use ReLU activation; the output layer is linear. Callers that
 * need a sigmoid output (e.g. {@link org.query.optimizer.learned.lero.PairwiseComparator})
 * apply it themselves after calling {@link #predict}.
 *
 * <h2>Weight storage</h2>
 * <p>Weights for layer {@code i} (connecting layer {@code i} to layer {@code i+1})
 * are stored as a flattened row-major matrix of size
 * {@code layerSizes[i+1] × layerSizes[i]}: element {@code [j * layerSizes[i] + k]}
 * is the weight from input unit {@code k} to output unit {@code j}.
 *
 * <h2>Initialization</h2>
 * <p>Xavier uniform: {@code U(-sqrt(6/(fan_in+fan_out)), +sqrt(6/(fan_in+fan_out)))}.
 *
 * <h2>Training</h2>
 * <p>Online SGD — one weight update per call to {@link #trainStep}. No mini-batching.
 * This is sufficient given the small network sizes used here (largest weight matrix
 * is 34×64 = 2 176 parameters).
 */
public class SimpleNeuralNetwork {

    private final int[] layerSizes;
    private double[][] weights;  // weights[i]: flattened (layerSizes[i+1] x layerSizes[i])
    private double[][] biases;   // biases[i]:  layerSizes[i+1]
    private final double learningRate;
    private final Random random;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public SimpleNeuralNetwork(int[] layerSizes, double learningRate) {
        this(layerSizes, learningRate, new Random());
    }

    public SimpleNeuralNetwork(int[] layerSizes, double learningRate, Random random) {
        if (layerSizes.length < 2) {
            throw new IllegalArgumentException("Need at least 2 layer sizes (input + output)");
        }
        this.layerSizes   = layerSizes.clone();
        this.learningRate = learningRate;
        this.random       = random;
        initializeWeights();
    }

    // -------------------------------------------------------------------------
    // Forward pass
    // -------------------------------------------------------------------------

    /**
     * Runs a forward pass and returns the output together with all intermediate
     * activations (needed for backpropagation).
     *
     * @param input feature vector of length {@code layerSizes[0]}
     * @return {@link ForwardResult} containing output and all layer activations
     */
    public ForwardResult forward(double[] input) {
        int numLayers = layerSizes.length;
        double[][] activations = new double[numLayers][];
        activations[0] = input;

        for (int i = 0; i < weights.length; i++) {
            double[] z = matVecMultiply(weights[i], activations[i],
                                        layerSizes[i + 1], layerSizes[i]);
            addInPlace(z, biases[i]);

            // Hidden layers: ReLU. Output layer: linear (no activation).
            activations[i + 1] = (i < weights.length - 1) ? ActivationFunction.relu(z) : z;
        }

        return new ForwardResult(activations[numLayers - 1], activations);
    }

    /**
     * Convenience predict — runs the forward pass and returns only the output.
     *
     * @param input feature vector
     * @return output vector (length {@code layerSizes[layerSizes.length - 1]})
     */
    public double[] predict(double[] input) {
        return forward(input).output();
    }

    // -------------------------------------------------------------------------
    // Training
    // -------------------------------------------------------------------------

    /**
     * Performs one online SGD update on a single (input, target) example.
     *
     * @param input  feature vector
     * @param target ground-truth output vector
     * @param loss   loss function used to compute the gradient
     * @return scalar loss value before the update (for monitoring)
     */
    public double trainStep(double[] input, double[] target, LossFunction loss) {
        ForwardResult fwd   = forward(input);
        double        lossVal = loss.compute(fwd.output(), target);
        double[]      dLoss  = loss.gradient(fwd.output(), target);
        backprop(fwd.activations(), dLoss);
        return lossVal;
    }

    // -------------------------------------------------------------------------
    // Save / load
    // -------------------------------------------------------------------------

    /**
     * Serializes weight and bias matrices to a plain-text file, one value per
     * line. The file format is:
     * <pre>
     *   layerSizes[0] layerSizes[1] ... layerSizes[n-1]
     *   learningRate
     *   w[0][0]  w[0][1]  ...   (all weights for layer 0, row-major)
     *   b[0][0]  b[0][1]  ...   (all biases for layer 0)
     *   w[1][0]  ...
     *   b[1][0]  ...
     *   ...
     * </pre>
     */
    public void save(String path) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(Path.of(path))) {
            // Header: layer sizes
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < layerSizes.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(layerSizes[i]);
            }
            w.write(sb.toString());
            w.newLine();
            w.write(Double.toString(learningRate));
            w.newLine();

            // Weights and biases
            for (int i = 0; i < weights.length; i++) {
                writeDoubleArray(w, weights[i]);
                writeDoubleArray(w, biases[i]);
            }
        }
    }

    /**
     * Loads a model saved by {@link #save}. The learning rate is read from the
     * file and used for any subsequent training.
     */
    public static SimpleNeuralNetwork load(String path) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(Path.of(path))) {
            // Layer sizes
            String[] parts = r.readLine().trim().split("\\s+");
            int[] sizes = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                sizes[i] = Integer.parseInt(parts[i]);
            }
            double lr = Double.parseDouble(r.readLine().trim());

            SimpleNeuralNetwork net = new SimpleNeuralNetwork(sizes, lr);
            for (int i = 0; i < net.weights.length; i++) {
                net.weights[i] = readDoubleArray(r, sizes[i + 1] * sizes[i]);
                net.biases[i]  = readDoubleArray(r, sizes[i + 1]);
            }
            return net;
        }
    }

    // -------------------------------------------------------------------------
    // Backpropagation
    // -------------------------------------------------------------------------

    /**
     * Performs backpropagation from an external gradient and returns the gradient
     * at the network input.
     *
     * <p>This is required by composite architectures such as
     * {@link org.query.optimizer.learned.lero.PairwiseComparator} where the
     * gradient must flow through multiple networks (classifier → shared encoder).
     *
     * @param fwd        the cached forward pass result from {@link #forward}
     * @param outputGrad gradient of the loss w.r.t. this network's output
     * @return gradient of the loss w.r.t. this network's input
     */
    public double[] backpropReturnInputGrad(ForwardResult fwd, double[] outputGrad) {
        double[] delta = outputGrad;
        for (int i = weights.length - 1; i >= 0; i--) {
            if (i < weights.length - 1) {
                double[] rd = ActivationFunction.reluDerivative(fwd.activations()[i + 1]);
                delta = elementwiseMultiply(delta, rd);
            }
            updateWeights(i, delta, fwd.activations()[i]);
            delta = matTVecMultiply(weights[i], delta, layerSizes[i], layerSizes[i + 1]);
        }
        return delta;
    }

    private void backprop(double[][] activations, double[] outputGrad) {
        double[] delta = outputGrad;

        for (int i = weights.length - 1; i >= 0; i--) {
            // Apply ReLU derivative for hidden layers (not the output layer)
            if (i < weights.length - 1) {
                double[] rd = ActivationFunction.reluDerivative(activations[i + 1]);
                delta = elementwiseMultiply(delta, rd);
            }

            updateWeights(i, delta, activations[i]);

            // Propagate delta backwards (not needed for the input layer)
            if (i > 0) {
                delta = matTVecMultiply(weights[i], delta, layerSizes[i], layerSizes[i + 1]);
            }
        }
    }

    private void updateWeights(int layer, double[] delta, double[] input) {
        int outSize = layerSizes[layer + 1];
        int inSize  = layerSizes[layer];
        for (int j = 0; j < outSize; j++) {
            for (int k = 0; k < inSize; k++) {
                weights[layer][j * inSize + k] -= learningRate * delta[j] * input[k];
            }
            biases[layer][j] -= learningRate * delta[j];
        }
    }

    // -------------------------------------------------------------------------
    // Weight initialization
    // -------------------------------------------------------------------------

    private void initializeWeights() {
        int numWeightLayers = layerSizes.length - 1;
        weights = new double[numWeightLayers][];
        biases  = new double[numWeightLayers][];

        for (int i = 0; i < numWeightLayers; i++) {
            int fanIn  = layerSizes[i];
            int fanOut = layerSizes[i + 1];
            double limit = Math.sqrt(6.0 / (fanIn + fanOut));

            weights[i] = new double[fanOut * fanIn];
            for (int j = 0; j < weights[i].length; j++) {
                weights[i][j] = (random.nextDouble() * 2.0 - 1.0) * limit;
            }

            biases[i] = new double[fanOut]; // initialized to 0
        }
    }

    // -------------------------------------------------------------------------
    // Linear algebra helpers
    // -------------------------------------------------------------------------

    /** y = W * x, where W is (rows × cols) stored row-major. */
    private static double[] matVecMultiply(double[] w, double[] x, int rows, int cols) {
        double[] y = new double[rows];
        for (int j = 0; j < rows; j++) {
            double sum = 0.0;
            for (int k = 0; k < cols; k++) {
                sum += w[j * cols + k] * x[k];
            }
            y[j] = sum;
        }
        return y;
    }

    /** y = W^T * x, where W is (outSize × inSize) stored row-major. */
    private static double[] matTVecMultiply(double[] w, double[] x, int inSize, int outSize) {
        double[] y = new double[inSize];
        for (int k = 0; k < inSize; k++) {
            double sum = 0.0;
            for (int j = 0; j < outSize; j++) {
                sum += w[j * inSize + k] * x[j];
            }
            y[k] = sum;
        }
        return y;
    }

    private static void addInPlace(double[] a, double[] b) {
        for (int i = 0; i < a.length; i++) a[i] += b[i];
    }

    private static double[] elementwiseMultiply(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] * b[i];
        return out;
    }

    // -------------------------------------------------------------------------
    // Save / load helpers
    // -------------------------------------------------------------------------

    private static void writeDoubleArray(BufferedWriter w, double[] arr) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(arr[i]);
        }
        w.write(sb.toString());
        w.newLine();
    }

    private static double[] readDoubleArray(BufferedReader r, int length) throws IOException {
        String[] parts = r.readLine().trim().split("\\s+");
        double[] arr = new double[length];
        for (int i = 0; i < length; i++) {
            arr[i] = Double.parseDouble(parts[i]);
        }
        return arr;
    }

    // -------------------------------------------------------------------------
    // Result record
    // -------------------------------------------------------------------------

    /**
     * Return value of {@link #forward}: the output vector plus all intermediate
     * activations needed by backpropagation.
     *
     * @param output      the network's output vector
     * @param activations all layer activations, {@code activations[0]} is the input,
     *                    {@code activations[layerSizes.length - 1]} equals {@code output}
     */
    public record ForwardResult(double[] output, double[][] activations) {}
}
