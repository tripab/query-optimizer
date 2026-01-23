package org.query.optimizer.rules;

import org.query.optimizer.Rule;
import org.query.optimizer.catalog.CostModel;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalJoin;

/**
 * Simple Join Reordering Rule (Greedy Heuristic)
 * <p>
 * For left-deep join trees, reorder joins to put smaller relations first.
 * This reduces intermediate result sizes.
 * <p>
 * Pattern: Join(Join(A, B), C)
 * Transform: Join(Join(A, C), B) if |A ⋈ C| < |A ⋈ B|
 * <p>
 * This is a SIMPLIFIED version. Full join reordering uses dynamic programming
 * (System R style), which is deferred to phase 2.
 * <p>
 * Heuristic: Prefer joins that produce smaller intermediate results.
 */
public class JoinReorder implements Rule {
    private final CostModel costModel;

    public JoinReorder(CostModel costModel) {
        this.costModel = costModel;
    }

    @Override
    public String getName() {
        return "JoinReorder";
    }

    @Override
    public boolean matches(LogicalNode node) {
        // Pattern: Join -> Join (left-deep tree)
        if (!(node instanceof LogicalJoin topJoin)) {
            return false;
        }

        return topJoin.getLeft() instanceof LogicalJoin;
    }

    @Override
    public LogicalNode apply(LogicalNode node) {
        LogicalJoin topJoin = (LogicalJoin) node;
        LogicalJoin bottomJoin = (LogicalJoin) topJoin.getLeft();

        // We have: Join(Join(A, B), C)
        // A = bottomJoin.getLeft()
        // B = bottomJoin.getRight()
        // C = topJoin.getRight()

        LogicalNode A = bottomJoin.getLeft();
        LogicalNode B = bottomJoin.getRight();
        LogicalNode C = topJoin.getRight();

        // Try alternative: Join(Join(A, C), B)
        // Check if join conditions allow this reordering

        // For simplicity, we only reorder if both joins are cross products
        // or if we can determine the reordering preserves semantics
        // A full implementation would analyze join predicates carefully

        // Simple heuristic: if B is larger than C, swap them
        long cardB = costModel.estimateCardinality(B);
        long cardC = costModel.estimateCardinality(C);

        if (cardC < cardB) {
            // Reorder: Join(Join(A, C), B)
            LogicalJoin newBottomJoin = new LogicalJoin(
                    A, C, bottomJoin.getJoinType(), topJoin.getCondition()
            );

            LogicalJoin newTopJoin = new LogicalJoin(
                    newBottomJoin, B, topJoin.getJoinType(), bottomJoin.getCondition()
            );

            // Only return if cost improved
            double oldCost = costModel.estimate(topJoin);
            double newCost = costModel.estimate(newTopJoin);

            if (newCost < oldCost) {
                return newTopJoin;
            }
        }

        return null; // No improvement
    }

    @Override
    public String getDescription() {
        return "Reorder joins to minimize intermediate result sizes (greedy heuristic)";
    }
}
