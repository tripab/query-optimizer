package org.query.optimizer.catalog;

import org.query.optimizer.logical.LogicalNode;

/**
 * Interface for cost estimation.
 * <p>
 * The cost model takes a logical plan (with estimated cardinalities)
 * and computes an estimated execution cost. This guides the optimizer
 * in choosing between alternative plans.
 * <p>
 * Cost models can vary in sophistication from simple I/O counting
 * to complex multi-dimensional models.
 */
public interface CostModel {
    /**
     * Estimate the cost of executing this logical node.
     * <p>
     * Pre-condition: The node should have estimated row counts
     * available (either from statistics or from child nodes).
     *
     * @param node The logical plan node to cost
     * @return Estimated cost (arbitrary units, but higher = worse)
     */
    double estimate(LogicalNode node);

    /**
     * Estimate the output cardinality (number of rows) from this node.
     * This is used to propagate estimates up the tree.
     *
     * @param node The logical plan node
     * @return Estimated number of output rows
     */
    long estimateCardinality(LogicalNode node);

    /**
     * Get the configuration for this cost model.
     * Allows tuning of cost constants.
     */
    CostConfig getConfig();

    record CostConfig(double pageCost, double tupleCost, int pageSize,
                      double comparisonCost, double hashCost) {
        public static CostConfig defaultConfig() {
            return new CostConfig(1D, 0.01D, 100,
                    0.001D, 0.005D);
        }

        @Override
        public String toString() {
            return String.format("CostConfig[PAGE=%.2f, TUPLE=%.4f, PAGE_SIZE=%d, " +
                            "COMPARISON_COST=%.2f, HASH_COST=%.2f]",
                    pageCost, tupleCost, pageSize, comparisonCost, hashCost);
        }
    }
}
