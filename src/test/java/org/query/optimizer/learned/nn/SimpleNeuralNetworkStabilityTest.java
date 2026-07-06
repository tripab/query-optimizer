package org.query.optimizer.learned.nn;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Online SGD must stay numerically stable: before gradient clipping, training
 * on large regression targets (raw millisecond latencies reach thousands)
 * drove the weights to NaN within a few epochs, after which the network
 * predicted NaN forever — Bao's value model hit this on its very first
 * retrain, degrading arm selection to a constant policy.
 */
public class SimpleNeuralNetworkStabilityTest {

    private static final int DIM = 8;

    private static double[] features(Random random) {
        double[] f = new double[DIM];
        for (int i = 0; i < DIM; i++) {
            f[i] = random.nextDouble() * 20.0; // log-scale plan features are ~0..20
        }
        return f;
    }

    @Test
    void trainingOnLargeTargetsStaysFinite() {
        SimpleNeuralNetwork net =
                new SimpleNeuralNetwork(new int[]{DIM, 16, 8, 1}, 0.001, new Random(1),
                        SimpleNeuralNetwork.DEFAULT_GRADIENT_CLIP);
        Random random = new Random(2);

        for (int step = 0; step < 2_000; step++) {
            // EC2-scale latency targets: thousands of milliseconds
            double target = 2_000.0 + random.nextDouble() * 3_000.0;
            net.trainStep(features(random), new double[]{target}, LossFunction.mse());
        }

        double prediction = net.predict(features(random))[0];
        assertTrue(Double.isFinite(prediction),
                "prediction must stay finite after training on large targets, got " + prediction);
    }

    @Test
    void poisonedExampleDoesNotCorruptTheNetwork() {
        SimpleNeuralNetwork net =
                new SimpleNeuralNetwork(new int[]{DIM, 16, 8, 1}, 0.001, new Random(3),
                        SimpleNeuralNetwork.DEFAULT_GRADIENT_CLIP);
        Random random = new Random(4);

        double[] poisoned = features(random);
        poisoned[3] = Double.NaN;
        net.trainStep(poisoned, new double[]{1.0}, LossFunction.mse());

        // The network must survive the poisoned step and keep learning
        for (int step = 0; step < 200; step++) {
            net.trainStep(features(random), new double[]{5.0}, LossFunction.mse());
        }
        double prediction = net.predict(features(random))[0];
        assertTrue(Double.isFinite(prediction),
                "a NaN feature must not poison the weights, got " + prediction);
    }

    @Test
    void clippingStillAllowsLearning() {
        SimpleNeuralNetwork net =
                new SimpleNeuralNetwork(new int[]{DIM, 16, 8, 1}, 0.005, new Random(5),
                        SimpleNeuralNetwork.DEFAULT_GRADIENT_CLIP);
        Random random = new Random(6);

        // Learn a simple separable signal: target = 10 if f[0] > 10 else 2
        for (int step = 0; step < 4_000; step++) {
            double[] f = features(random);
            double target = f[0] > 10.0 ? 10.0 : 2.0;
            net.trainStep(f, new double[]{target}, LossFunction.mse());
        }

        double[] high = features(random);
        high[0] = 18.0;
        double[] low = features(random);
        low[0] = 2.0;

        double predHigh = net.predict(high)[0];
        double predLow = net.predict(low)[0];
        assertTrue(Double.isFinite(predHigh) && Double.isFinite(predLow));
        assertTrue(predHigh > predLow + 2.0,
                String.format("network should still learn under clipping: high=%.2f low=%.2f",
                        predHigh, predLow));
    }
}
