package org.query.optimizer.physical;

/**
 * Tally of the join algorithms used in a physical plan tree.
 *
 * <p>A small reporting helper for surfacing which physical join algorithm the
 * planner chose — useful in demos and benchmark reports now that join-algorithm
 * selection is cost-based.
 *
 * @param hashJoins       number of {@link PhysicalHashJoin} operators in the plan
 * @param nestedLoopJoins number of {@link PhysicalNestedLoopJoin} operators
 */
public record JoinAlgorithmCounts(int hashJoins, int nestedLoopJoins) {

    /** Total number of join operators (hash + nested-loop). */
    public int totalJoins() {
        return hashJoins + nestedLoopJoins;
    }

    /**
     * Counts the join operators in {@code root} and its subtree.
     */
    public static JoinAlgorithmCounts of(PhysicalNode root) {
        int[] counts = new int[2]; // [0] = hash, [1] = nested-loop
        walk(root, counts);
        return new JoinAlgorithmCounts(counts[0], counts[1]);
    }

    private static void walk(PhysicalNode node, int[] counts) {
        if (node instanceof PhysicalHashJoin) {
            counts[0]++;
        } else if (node instanceof PhysicalNestedLoopJoin) {
            counts[1]++;
        }
        for (PhysicalNode child : node.getChildren()) {
            walk(child, counts);
        }
    }

    @Override
    public String toString() {
        return hashJoins + " hash join(s), " + nestedLoopJoins + " nested-loop join(s)";
    }
}
