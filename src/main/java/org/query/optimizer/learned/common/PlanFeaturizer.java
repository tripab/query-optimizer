package org.query.optimizer.learned.common;

import org.query.optimizer.physical.PhysicalAggregate;
import org.query.optimizer.physical.PhysicalFilter;
import org.query.optimizer.physical.PhysicalHashJoin;
import org.query.optimizer.physical.PhysicalNestedLoopJoin;
import org.query.optimizer.physical.PhysicalNode;
import org.query.optimizer.physical.PhysicalProject;
import org.query.optimizer.physical.PhysicalScan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Converts a {@link PhysicalNode} plan tree into a fixed-length {@code double[]}
 * vector suitable for consumption by a neural network.
 *
 * <h2>Feature vector layout — total length {@value #FEATURE_DIM}</h2>
 *
 * <p><b>Per-operator slots (indices 0–29):</b> The plan is flattened in
 * breadth-first order so that the root (the final output operator) is always
 * in slot 0.  Up to {@value #MAX_OPERATOR_SLOTS} operator slots are encoded;
 * unused slots are left as zero.  Each slot occupies 6 consecutive elements:
 * <pre>
 *   offset+0  operator type (scan=1, filter=2, project=3, nlj=4, hashjoin=5)
 *   offset+1  log(estimatedRows + 1)
 *   offset+2  log(estimatedCost + 1)
 *   offset+3  is_join  (1.0 if NLJ or HashJoin, else 0.0)
 *   offset+4  is_leaf  (1.0 if the node has no children, else 0.0)
 *   offset+5  fan_out  (number of children: 0, 1, or 2)
 * </pre>
 *
 * <p><b>Global features (indices 30–33):</b>
 * <pre>
 *   30  total operator count in the plan
 *   31  tree depth (longest root-to-leaf path)
 *   32  log(root.estimatedRows + 1)
 *   33  log(root.estimatedCost + 1)
 * </pre>
 *
 * <h2>Design rationale</h2>
 * <ul>
 *   <li>BFS ordering places the root at slot 0, giving the model a stable
 *       reference point independent of plan shape.</li>
 *   <li>Log-normalization compresses the orders-of-magnitude span of row
 *       counts and costs into a range the network can learn from without
 *       gradient issues.</li>
 *   <li>The fixed vector length avoids variable-size encoding complexity and
 *       keeps the network architecture constant across all plan shapes.</li>
 *   <li>Five operator slots covers the depth of the plans the current physical
 *       layer produces (scan + filter + project + join + join for a three-table
 *       query is at most 5 nodes in BFS order).</li>
 * </ul>
 */
public class PlanFeaturizer {

    /**
     * Number of operator slots encoded in the per-operator section. Eight slots
     * cover deeper plans than the three-table join the earlier five-slot encoding
     * assumed — e.g. a three-way join with pushed-down filters and an aggregate.
     */
    public static final int MAX_OPERATOR_SLOTS = 8;

    /** Features per operator slot. */
    public static final int FEATURES_PER_SLOT  = 6;

    /** Number of global (plan-level) features. */
    public static final int GLOBAL_FEATURES     = 4;

    /** Total feature vector length. */
    public static final int FEATURE_DIM =
            MAX_OPERATOR_SLOTS * FEATURES_PER_SLOT + GLOBAL_FEATURES;

    // Operator-type encoding constants (slot offset 0)
    private static final double TYPE_SCAN      = 1.0;
    private static final double TYPE_FILTER    = 2.0;
    private static final double TYPE_PROJECT   = 3.0;
    private static final double TYPE_NLJ       = 4.0;
    private static final double TYPE_HASH_JOIN = 5.0;
    private static final double TYPE_AGGREGATE = 6.0;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Converts {@code root} and its subtree into a {@value #FEATURE_DIM}-element
     * {@code double[]} ready to be fed to a neural network.
     *
     * <p>If the plan has not been annotated with cost/cardinality estimates
     * (i.e. {@code getEstimatedRows()} returns −1), the corresponding log
     * features are emitted as {@code 0.0} rather than raising an error, so
     * featurization is safe even on unannotated plans.
     *
     * @param root the root physical operator of the plan
     * @return a new {@code double[FEATURE_DIM]} feature vector
     */
    public double[] featurize(PhysicalNode root) {
        double[] features = new double[FEATURE_DIM];

        List<PhysicalNode> bfsOrder = flattenBFS(root);

        // --- Per-operator slots ---
        int slotCount = Math.min(bfsOrder.size(), MAX_OPERATOR_SLOTS);
        for (int i = 0; i < slotCount; i++) {
            int         offset = i * FEATURES_PER_SLOT;
            PhysicalNode node  = bfsOrder.get(i);

            features[offset + 0] = encodeOperatorType(node);
            features[offset + 1] = log1pSafe(node.getEstimatedRows());
            features[offset + 2] = log1pSafe(node.getEstimatedCost());
            features[offset + 3] = isJoin(node)              ? 1.0 : 0.0;
            features[offset + 4] = node.getChildren().isEmpty() ? 1.0 : 0.0;
            features[offset + 5] = node.getChildren().size();
        }

        // --- Global features (immediately after the per-operator section) ---
        int g = MAX_OPERATOR_SLOTS * FEATURES_PER_SLOT;
        features[g]     = bfsOrder.size();
        features[g + 1] = computeDepth(root);
        features[g + 2] = log1pSafe(root.getEstimatedRows());
        features[g + 3] = log1pSafe(root.getEstimatedCost());

        return features;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Flattens the plan tree into breadth-first order, root first.
     */
    private List<PhysicalNode> flattenBFS(PhysicalNode root) {
        List<PhysicalNode> result = new ArrayList<>();
        Deque<PhysicalNode> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            PhysicalNode node = queue.poll();
            result.add(node);
            queue.addAll(node.getChildren());
        }
        return result;
    }

    /**
     * Returns the length of the longest root-to-leaf path (root has depth 1).
     */
    private int computeDepth(PhysicalNode node) {
        if (node.getChildren().isEmpty()) return 1;
        int max = 0;
        for (PhysicalNode child : node.getChildren()) {
            max = Math.max(max, computeDepth(child));
        }
        return max + 1;
    }

    /**
     * Maps a physical operator class to its integer type code.
     * Unknown operator types are encoded as {@code 0.0} (the "other" bucket).
     */
    private double encodeOperatorType(PhysicalNode node) {
        if (node instanceof PhysicalScan)            return TYPE_SCAN;
        if (node instanceof PhysicalFilter)          return TYPE_FILTER;
        if (node instanceof PhysicalProject)         return TYPE_PROJECT;
        if (node instanceof PhysicalNestedLoopJoin)  return TYPE_NLJ;
        if (node instanceof PhysicalHashJoin)        return TYPE_HASH_JOIN;
        if (node instanceof PhysicalAggregate)       return TYPE_AGGREGATE;
        return 0.0;
    }

    /** Returns {@code true} for join operators (NLJ and HashJoin). */
    private boolean isJoin(PhysicalNode node) {
        return node instanceof PhysicalHashJoin
            || node instanceof PhysicalNestedLoopJoin;
    }

    /**
     * {@code Math.log1p} guarded against negative annotations (−1 sentinel)
     * and {@code NaN}/{@code Infinity}.
     */
    private double log1pSafe(double value) {
        if (value < 0) return 0.0;
        double result = Math.log1p(value);
        return Double.isFinite(result) ? result : 0.0;
    }
}
