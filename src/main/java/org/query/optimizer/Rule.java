package org.query.optimizer;

import org.query.optimizer.logical.LogicalNode;

/**
 * Interface for optimization rules.
 * <p>
 * Rules transform logical plans into equivalent but potentially
 * more efficient logical plans. Rules are applied repeatedly
 * until a fixpoint is reached (no more transformations possible).
 * <p>
 * Design philosophy: Rules should be small, composable, and
 * easy to understand. Each rule does one thing well.
 */
public interface Rule {
    /**
     * Get the name of this rule (for debugging/logging).
     */
    String getName();

    /**
     * Check if this rule can be applied to the given node.
     * This is the "pattern matching" phase.
     */
    boolean matches(LogicalNode node);

    /**
     * Apply the transformation to the node.
     * Returns a new transformed node, or null if no transformation
     * was actually performed.
     * <p>
     * Pre-condition: matches(node) == true
     */
    LogicalNode apply(LogicalNode node);

    /**
     * Optional: Get a description of what this rule does.
     */
    default String getDescription() {
        return "No description provided";
    }
}
