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

    /**
     * Configuration object for cost model parameters.
     * Makes it easy to experiment with different cost weightings.
     */
    class CostConfig {
        // Cost to read/write one page from/to disk
        public double PAGE_COST = 1.0;

        // Cost to process one tuple in memory
        public double TUPLE_COST = 0.01;

        // Number of tuples that fit in one page
        public int PAGE_SIZE = 100;

        // Cost to perform one comparison
        public double COMPARISON_COST = 0.001;

        // Cost to hash one tuple
        public double HASH_COST = 0.005;

        public CostConfig() {
            // Use defaults
        }

        public CostConfig(double pageCost, double tupleCost, int pageSize) {
            this.PAGE_COST = pageCost;
            this.TUPLE_COST = tupleCost;
            this.PAGE_SIZE = pageSize;
        }

        @Override
        public String toString() {
            return String.format("CostConfig[PAGE=%.2f, TUPLE=%.4f, PAGE_SIZE=%d]",
                    PAGE_COST, TUPLE_COST, PAGE_SIZE);
        }
    }
}
