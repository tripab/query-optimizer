package org.query.optimizer.learned.cardinality;

import org.query.optimizer.learned.cardinality.CardinalityTrainingData.Example;
import org.query.optimizer.learned.nn.LossFunction;
import org.query.optimizer.learned.nn.SimpleNeuralNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Trains the {@link LearnedCardinalityModel}'s network on labelled
 * {@link Example}s by regressing log-cardinality with mean-squared-error loss.
 *
 * <p>The network is a small MLP ({@link CardinalityFeaturizer#FEATURE_DIM} →
 * 16 → 8 → 1) trained with online SGD over shuffled epochs. Targets are in log
 * space, matching {@link LearnedCardinalityModel#estimate}.
 */
public final class CardinalityModelTrainer {

    private static final int[] LAYER_SIZES =
            {CardinalityFeaturizer.FEATURE_DIM, 16, 8, 1};

    private CardinalityModelTrainer() {
    }

    /** The MLP layer sizes used by {@link #train} (defensive copy). */
    public static int[] layerSizes() {
        return LAYER_SIZES.clone();
    }

    /**
     * Trains a fresh network on {@code examples}.
     *
     * @param examples     labelled training examples
     * @param epochs       number of passes over the data
     * @param learningRate SGD learning rate
     * @param seed         RNG seed for weight init and shuffling (reproducible)
     * @return the trained network
     */
    public static SimpleNeuralNetwork train(List<Example> examples, int epochs,
                                            double learningRate, long seed) {
        if (examples.isEmpty()) {
            throw new IllegalArgumentException("Cannot train on an empty example set");
        }
        Random random = new Random(seed);
        SimpleNeuralNetwork network = new SimpleNeuralNetwork(LAYER_SIZES, learningRate, random);
        LossFunction mse = LossFunction.mse();

        List<Example> shuffled = new ArrayList<>(examples);
        for (int epoch = 0; epoch < epochs; epoch++) {
            Collections.shuffle(shuffled, random);
            for (Example example : shuffled) {
                network.trainStep(example.features(),
                        new double[]{example.logCardinality()}, mse);
            }
        }
        return network;
    }
}
