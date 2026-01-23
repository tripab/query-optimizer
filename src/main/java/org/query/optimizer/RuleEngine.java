package org.query.optimizer;

import org.query.optimizer.logical.LogicalNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule engine that applies optimization rules to logical plans.
 * <p>
 * Uses fixpoint iteration: repeatedly apply rules until no more
 * transformations are possible. This ensures we reach a stable,
 * optimized plan.
 * <p>
 * Design philosophy:
 * - Rules are applied bottom-up (children before parents)
 * - Each rule is applied exhaustively before moving to next rule
 * - Iteration stops when a full pass produces no changes
 */
public class RuleEngine {
    private final List<Rule> rules;
    private final int maxIterations;
    private boolean verbose = false;

    public RuleEngine(List<Rule> rules) {
        this(rules, 10);
    }

    public RuleEngine(List<Rule> rules, int maxIterations) {
        this.rules = rules;
        this.maxIterations = maxIterations;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Apply all rules to the plan using fixpoint iteration.
     *
     * @param plan The input logical plan
     * @return The optimized logical plan
     */
    public LogicalNode optimize(LogicalNode plan) {
        if (verbose) {
            System.out.println("=== Rule-Based Optimization ===");
            System.out.println("Initial plan:");
            System.out.println(plan.toPrettyString());
        }

        LogicalNode current = plan;
        for (int iter = 1; iter <= maxIterations; iter++) {
            if (verbose) {
                System.out.println("\n--- Iteration " + iter + " ---");
            }
            LogicalNode previous = current;
            for (Rule rule : rules) {
                current = applyRuleExhaustively(current, rule);
            }
            if (plansEqual(previous, current)) {
                if (verbose) {
                    System.out.println("Fixpoint reached after " + iter + " iteration(s)");
                }
                break;
            }
            if (iter >= maxIterations) {
                if (verbose) {
                    System.out.println("WARNING: Max iterations reached without fixpoint");
                }
            }
        }

        if (verbose) {
            System.out.println("\nFinal optimized plan:");
            System.out.println(current.toPrettyString());
        }

        return current;
    }

    /**
     * Check if two plans are structurally equal.
     * This is a simple reference equality check - in production,
     * you'd want structural comparison.
     */
    private boolean plansEqual(LogicalNode plan1, LogicalNode plan2) {
        // TODO: need a more sophisticated version which does structural comparison
        return plan1 == plan2;
    }

    /**
     * Apply a single rule exhaustively to the plan.
     * Uses bottom-up traversal to ensure children are optimized first.
     */
    private LogicalNode applyRuleExhaustively(LogicalNode node, Rule rule) {
        // First, recursively apply to children (bottom-up)
        List<LogicalNode> newChildren = new ArrayList<>();
        boolean childrenChanged = false;

        for (LogicalNode child : node.getChildren()) {
            LogicalNode newChild = applyRuleExhaustively(child, rule);
            newChildren.add(newChild);
            if (newChild != child) {
                childrenChanged = true;
            }
        }

        // If children changed, create new node with updated children
        LogicalNode current = childrenChanged ? node.withChildren(newChildren) : node;

        // Now try to apply rule to current node
        if (rule.matches(current)) {
            LogicalNode transformed = rule.apply(current);
            if (transformed != null && transformed != current) {
                if (verbose) {
                    System.out.println("Applied rule: " + rule.getName());
                    System.out.println("  Before: " + current.describe());
                    System.out.println("  After:  " + transformed.describe());
                }

                // Recursively apply rule to the transformed node
                // (in case the transformation enables further applications)
                return applyRuleExhaustively(transformed, rule);
            }
        }

        return current;
    }

    /**
     * Get statistics about the optimization.
     */
    record OptimizationStats(int rulesApplied, int iterations, long timeMs) {
        @Override
        public String toString() {
            return String.format("OptimizationStats[rules=%d, iterations=%d, time=%dms]",
                    rulesApplied, iterations, timeMs);
        }
    }
}
