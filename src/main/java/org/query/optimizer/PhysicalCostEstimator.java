package org.query.optimizer;

import org.query.optimizer.catalog.CostModel.CostConfig;
import org.query.optimizer.physical.PhysicalAggregate;
import org.query.optimizer.physical.PhysicalFilter;
import org.query.optimizer.physical.PhysicalHashJoin;
import org.query.optimizer.physical.PhysicalNestedLoopJoin;
import org.query.optimizer.physical.PhysicalNode;
import org.query.optimizer.physical.PhysicalProject;
import org.query.optimizer.physical.PhysicalScan;

import java.util.List;

/**
 * Estimates the execution cost of a <em>physical</em> plan, accounting for the
 * specific algorithm each operator uses.
 *
 * <p>This deliberately separates two concerns the optimizer used to conflate:
 * <ul>
 *   <li><b>Logical cardinality estimation</b> ({@link CardinalityEstimator}) decides
 *       <em>how many rows</em> each operator produces. Those row counts are read
 *       here from each node's {@code getEstimatedRows()} annotation.</li>
 *   <li><b>Physical costing</b> (this class) decides <em>how expensive</em> it is to
 *       produce them with a particular algorithm — most importantly distinguishing a
 *       hash join from a nested-loop join, which the logical cost model cannot.</li>
 * </ul>
 *
 * <p>Costs are in the same arbitrary units as {@link org.query.optimizer.SimpleCostModel}
 * and are driven by the shared {@link CostConfig} constants, so the two models stay
 * comparable. Cardinalities are taken from annotations; when a node has no row
 * estimate (e.g. the plan was built without prior annotation) a floor of 1 is used.
 */
public class PhysicalCostEstimator {

    private final CostConfig config;

    public PhysicalCostEstimator() {
        this(new CostConfig());
    }

    public PhysicalCostEstimator(CostConfig config) {
        this.config = config;
    }

    /**
     * Computes the total physical cost of {@code node} and its subtree. Pure: does
     * not mutate the plan.
     */
    public double estimateCost(PhysicalNode node) {
        if (node instanceof PhysicalScan scan) {
            return scanCost(scan);
        }
        if (node instanceof PhysicalHashJoin join) {
            return hashJoinCost(join.getLeft(), join.getRight());
        }
        if (node instanceof PhysicalNestedLoopJoin join) {
            return nestedLoopJoinCost(join.getLeft(), join.getRight());
        }

        List<PhysicalNode> children = node.getChildren();
        if (node instanceof PhysicalFilter) {
            PhysicalNode child = children.get(0);
            return estimateCost(child) + rows(child) * config.COMPARISON_COST;
        }
        if (node instanceof PhysicalProject) {
            PhysicalNode child = children.get(0);
            return estimateCost(child) + rows(child) * config.TUPLE_COST;
        }
        if (node instanceof PhysicalAggregate) {
            PhysicalNode child = children.get(0);
            return estimateCost(child)
                    + rows(child) * config.HASH_COST     // hash each input row into groups
                    + rows(node) * config.TUPLE_COST;    // emit one row per group
        }

        // Unknown operator: sum of children (zero for a leaf).
        double total = 0.0;
        for (PhysicalNode child : children) {
            total += estimateCost(child);
        }
        return total;
    }

    /**
     * Physical cost of a hash join over the given children: build a hash table on
     * one side and probe with the other, so cost scales with the <em>sum</em> of the
     * input sizes.
     */
    public double hashJoinCost(PhysicalNode left, PhysicalNode right) {
        return estimateCost(left) + estimateCost(right)
                + (rows(left) + rows(right)) * config.HASH_COST;
    }

    /**
     * Physical cost of a nested-loop join over the given children: the right side is
     * rescanned for every left row, so cost scales with the <em>product</em> of the
     * input sizes.
     */
    public double nestedLoopJoinCost(PhysicalNode left, PhysicalNode right) {
        return estimateCost(left) + estimateCost(right)
                + (rows(left) * rows(right)) * config.COMPARISON_COST;
    }

    /**
     * Recursively annotates {@code node} and its subtree with their physical
     * estimated cost (via {@code setEstimatedCost}), leaving row estimates untouched.
     */
    public void annotateCosts(PhysicalNode node) {
        for (PhysicalNode child : node.getChildren()) {
            annotateCosts(child);
        }
        node.setEstimatedCost(estimateCost(node));
    }

    private double scanCost(PhysicalScan scan) {
        long rows = rows(scan);
        long pages = (rows + config.PAGE_SIZE - 1) / config.PAGE_SIZE;
        return pages * config.PAGE_COST + rows * config.TUPLE_COST;
    }

    /** Row count for costing; floors unknown ({@code <= 0}) estimates at 1. */
    private long rows(PhysicalNode node) {
        long estimated = node.getEstimatedRows();
        return estimated > 0 ? estimated : 1;
    }
}
