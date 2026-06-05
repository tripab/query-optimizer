package org.query.optimizer;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.logical.LogicalNode;

/**
 * The default {@link CardinalityModel}: the built-in heuristic estimator.
 *
 * <p>Delegates to {@link CardinalityEstimator}, which propagates
 * {@link SubtreeStatistics} (row counts plus per-column NDV/min/max) up the plan.
 * This preserves the optimizer's existing behavior when no learned model is in use.
 */
public final class HeuristicCardinalityModel implements CardinalityModel {

    private final CardinalityEstimator estimator;

    public HeuristicCardinalityModel(Catalog catalog) {
        this(new CardinalityEstimator(catalog));
    }

    public HeuristicCardinalityModel(CardinalityEstimator estimator) {
        this.estimator = estimator;
    }

    @Override
    public long estimate(LogicalNode node) {
        return estimator.estimate(node);
    }
}
