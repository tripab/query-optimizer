package org.query.optimizer;

import org.query.optimizer.logical.LogicalNode;

/**
 * A swappable estimator of the output cardinality (row count) of a logical
 * subplan.
 *
 * <p>This is the seam that lets the optimizer choose between the built-in
 * heuristic estimator ({@link HeuristicCardinalityModel}, the default) and a
 * learned estimator without the cost model caring which is in use. Implementations
 * estimate the number of rows produced by {@code node} and its subtree.
 */
public interface CardinalityModel {

    /**
     * Estimates the output cardinality of {@code node} (and its subtree).
     *
     * @param node the root of the logical subplan to estimate
     * @return the estimated number of output rows (always {@code >= 1})
     */
    long estimate(LogicalNode node);
}
