package org.query.optimizer;

import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts join information from a logical plan.
 * <p>
 * Finds all scans and join conditions in a plan tree,
 * preparing them for DP join ordering.
 */
public class JoinExtractor {
    /**
     * Extracted join information.
     */
    record JoinInfo(List<LogicalScan> scans,
                    List<DPJoinOrderer.JoinCondition> conditions,
                    LogicalNode topOperator) {
        public boolean hasMultipleJoins() {
            return scans.size() > 1;
        }

        public boolean isSingleTable() {
            return scans.size() == 1;
        }
    }

    /**
     * Extract join information from a logical plan.
     * <p>
     * This handles plans of the form:
     * Project/Filter/Aggregate
     * └── Join tree
     * ├── Scans
     * └── Scans
     */
    public JoinInfo extract(LogicalNode plan) {
        var scans = new ArrayList<LogicalScan>();
        var conditions = new ArrayList<DPJoinOrderer.JoinCondition>();

        // find the root of join tree
        var joinRoot = findJoinRoot(plan);
        if (joinRoot == null) {
            // no joins, just a single scan
            var scan = findScan(plan);
            if (scan != null) {
                scans.add(scan);
            }
            return new JoinInfo(scans, conditions, joinRoot);
        }
        // extract scans and conditions from join tree
        extractFromJoinTree(joinRoot, scans, conditions);
        // find top operator (Project/Filter/Aggregate above joins)
        var topOperator = findTopOperator(plan, joinRoot);

        return new JoinInfo(scans, conditions, topOperator);
    }

    /**
     * Find the top operator above the join tree.
     */
    private LogicalNode findTopOperator(LogicalNode plan, LogicalNode joinRoot) {
        if (plan == joinRoot) {
            return null;
        }
        // The top operator is the parent of joinRoot
        // We need to reconstruct it with a new child
        return plan; // Simplified - return full plan
    }

    /**
     * Extract scans and join conditions from a join tree.
     */
    private void extractFromJoinTree(LogicalNode node,
                                     ArrayList<LogicalScan> scans,
                                     ArrayList<DPJoinOrderer.JoinCondition> conditions) {
        if (node instanceof LogicalScan) {
            scans.add((LogicalScan) node);
            return;
        }
        if (node instanceof LogicalJoin join) {
            // extract join condition
            Expression condition = join.getCondition();
            // Try to identify which tables are involved
            // This is a simplified version - assumes binary equality join
            String leftTable = extractTableFromSubtree(join.getLeft());
            String rightTable = extractTableFromSubtree(join.getRight());
            if (leftTable != null && rightTable != null) {
                conditions.add(new DPJoinOrderer.JoinCondition(
                        leftTable, rightTable, condition
                ));
            }
            // Recursively extract from children
            extractFromJoinTree(join.getLeft(), scans, conditions);
            extractFromJoinTree(join.getRight(), scans, conditions);
        }
    }

    /**
     * Extract a table name from a subtree.
     * For simple cases, this is just a scan.
     * For complex cases, we might have filters below the join.
     */
    private String extractTableFromSubtree(LogicalNode node) {
        if (node instanceof LogicalScan) {
            return ((LogicalScan) node).getTableName();
        }
        // descend through filters
        if (node instanceof LogicalFilter) {
            return extractTableFromSubtree(((LogicalFilter) node).getChild());
        }
        // for joins, we can't determine a single table
        return null;
    }

    /**
     * Find a single scan in the plan.
     */
    private LogicalScan findScan(LogicalNode node) {
        if (node instanceof LogicalScan) {
            return (LogicalScan) node;
        }
        var scan = node.getChildren().stream()
                .filter(child -> findScan(child) != null)
                .findFirst()
                .orElse(null);
        return scan != null ? (LogicalScan) scan : null;
    }

    /**
     * Find the root of the join subtree.
     * Descends through Project/Filter/Aggregate operators.
     */
    private LogicalNode findJoinRoot(LogicalNode node) {
        if (node instanceof LogicalJoin)
            return node;
        // descend through unary operators
        if (node instanceof LogicalProject ||
                node instanceof LogicalFilter ||
                node instanceof LogicalAggregate) {
            return findJoinRoot(node.getChildren().getFirst());
        }

        return null; // no join found
    }

    /**
     * Replace the join subtree in a plan with a new join tree.
     */
    public LogicalNode replaceJoinSubtree(LogicalNode plan,
                                          LogicalNode oldJoinRoot,
                                          LogicalNode newJoinRoot) {
        if (plan == oldJoinRoot) {
            return newJoinRoot;
        }
        // recursively replace in children
        var newChildren = new ArrayList<LogicalNode>();
        boolean changed = false;
        for (LogicalNode child : plan.getChildren()) {
            LogicalNode newChild = replaceJoinSubtree(child, oldJoinRoot, newJoinRoot);
            newChildren.add(newChild);
            if (newChild != child) {
                changed = true;
            }
        }
        if (changed) {
            return plan.withChildren(newChildren);
        }

        return plan;
    }
}
