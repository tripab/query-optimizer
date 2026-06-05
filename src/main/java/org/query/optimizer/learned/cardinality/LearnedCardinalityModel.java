package org.query.optimizer.learned.cardinality;

import org.query.optimizer.CardinalityModel;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.learned.nn.SimpleNeuralNetwork;

/**
 * A {@link CardinalityModel} backed by a neural network that predicts
 * <em>log</em>-cardinality from a {@link CardinalityFeaturizer} feature vector.
 *
 * <p>Cardinalities span many orders of magnitude, so the network is trained and
 * queried in log space; {@link #estimate} exponentiates the prediction back to a
 * row count.
 *
 * <h2>Fallback</h2>
 * <p>The model defers to a {@code fallback} estimator (the heuristic) whenever it
 * cannot produce a trustworthy prediction:
 * <ul>
 *   <li>no network has been provided (untrained / not loaded),</li>
 *   <li>the subplan uses an operator shape the featurizer does not support, or</li>
 *   <li>the network returns a non-finite value.</li>
 * </ul>
 * This keeps the optimizer robust: a missing or misbehaving model degrades to the
 * existing heuristic rather than failing.
 */
public class LearnedCardinalityModel implements CardinalityModel {

    private final SimpleNeuralNetwork network; // may be null
    private final CardinalityFeaturizer featurizer;
    private final CardinalityModel fallback;

    public LearnedCardinalityModel(SimpleNeuralNetwork network,
                                   CardinalityFeaturizer featurizer,
                                   CardinalityModel fallback) {
        this.network = network;
        this.featurizer = featurizer;
        this.fallback = fallback;
    }

    @Override
    public long estimate(LogicalNode node) {
        if (network == null || !featurizer.supports(node)) {
            return fallback.estimate(node);
        }
        double logPrediction = network.predict(featurizer.featurize(node))[0];
        if (!Double.isFinite(logPrediction)) {
            return fallback.estimate(node);
        }
        long prediction = Math.round(Math.exp(logPrediction));
        return Math.max(1, prediction);
    }

    /** Whether a trained network is available (vs. pure fallback). */
    public boolean hasModel() {
        return network != null;
    }
}
