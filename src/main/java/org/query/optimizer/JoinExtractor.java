package org.query.optimizer;

import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalAggregate;
import org.query.optimizer.parser.LogicalFilter;
import org.query.optimizer.parser.LogicalJoin;
import org.query.optimizer.parser.LogicalProject;
import org.query.optimizer.parser.LogicalScan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts join information from a logical plan.
 * <p>
 * Finds the join inputs (<em>leaves</em>) and join conditions in a join subtree.
 * Each leaf is the maximal single-table subtree feeding the join structure — a
 * scan, possibly wrapped in unary operators (such as a pushed-down
 * {@link LogicalFilter}). Leaves are returned <em>whole</em> so that reordering
 * the join structure preserves those operators; previously the extractor recursed
 * through and discarded them, which silently dropped pushed-down filters and
 * produced wrong results after reordering.
 * <p>
 * A non-join operator that spans more than one table (e.g. a filter sitting
 * <em>between</em> two joins) cannot be preserved by table-set-based reordering,
 * so such a subtree is reported as unsupported and left untouched.
 */
public class JoinExtractor {
    record JoinInfo(List<LogicalNode> leaves,
                    List<DPJoinOrderer.JoinCondition> conditions,
                    LogicalNode joinRoot,
                    boolean supported) {
        public boolean hasMultipleJoins() {
            return leaves.size() > 1;
        }

        public boolean isSingleTable() {
            return leaves.size() == 1;
        }

        public boolean hasJoinTree() {
            return joinRoot != null;
        }
    }

    public JoinInfo extract(LogicalNode plan) {
        ArrayList<LogicalNode> leaves = new ArrayList<>();
        ArrayList<DPJoinOrderer.JoinCondition> conditions = new ArrayList<>();

        LogicalNode joinRoot = findJoinRoot(plan);
        if (joinRoot == null) {
            LogicalScan scan = findScan(plan);
            if (scan != null) {
                leaves.add(scan);
            }
            return new JoinInfo(leaves, conditions, null, true);
        }

        boolean supported = extractFromJoinTree(joinRoot, leaves, conditions);
        if (!supported) {
            leaves.clear();
            conditions.clear();
        }

        return new JoinInfo(leaves, conditions, joinRoot, supported);
    }

    private boolean extractFromJoinTree(LogicalNode node,
                                        List<LogicalNode> leaves,
                                        List<DPJoinOrderer.JoinCondition> conditions) {
        if (node instanceof LogicalJoin join) {
            if (join.getJoinType() != LogicalJoin.JoinType.INNER) {
                return false;
            }

            if (!extractFromJoinTree(join.getLeft(), leaves, conditions) ||
                    !extractFromJoinTree(join.getRight(), leaves, conditions)) {
                return false;
            }

            Set<String> leftTables = collectScanNames(join.getLeft());
            Set<String> rightTables = collectScanNames(join.getRight());
            String leftTable = resolveJoinSideTable(join.getCondition(), leftTables, true);
            String rightTable = resolveJoinSideTable(join.getCondition(), rightTables, false);
            if (leftTable == null || rightTable == null) {
                return false;
            }

            conditions.add(new DPJoinOrderer.JoinCondition(leftTable, rightTable, join.getCondition()));
            return true;
        }

        // Non-join node: treat as a join input (leaf). It is only reorderable if it
        // covers exactly one table; otherwise (e.g. a filter over a join) reordering
        // by table set could not preserve it, so report unsupported.
        Set<String> tables = collectScanNames(node);
        if (tables.size() != 1) {
            return false;
        }
        leaves.add(node);
        return true;
    }

    private String resolveJoinSideTable(Expression condition,
                                        Set<String> tables,
                                        boolean useConditionLookup) {
        List<Expression.ColumnRef> refs = new ArrayList<>();
        collectColumnRefs(condition, refs);
        for (Expression.ColumnRef ref : refs) {
            if (ref.tableName() != null && tables.contains(ref.tableName())) {
                return ref.tableName();
            }
        }
        if (useConditionLookup) {
            return tables.size() == 1 ? tables.iterator().next() : null;
        }
        return tables.size() == 1 ? tables.iterator().next() : null;
    }

    private void collectColumnRefs(Expression expression, List<Expression.ColumnRef> refs) {
        if (expression instanceof Expression.ColumnRef columnRef) {
            refs.add(columnRef);
            return;
        }
        if (expression instanceof Expression.BinaryOp binaryOp) {
            collectColumnRefs(binaryOp.left(), refs);
            collectColumnRefs(binaryOp.right(), refs);
        }
    }

    private Set<String> collectScanNames(LogicalNode node) {
        Set<String> tables = new HashSet<>();
        collectScanNames(node, tables);
        return tables;
    }

    private void collectScanNames(LogicalNode node, Set<String> tables) {
        if (node instanceof LogicalScan scan) {
            tables.add(scan.getTableName());
            return;
        }
        for (LogicalNode child : node.getChildren()) {
            collectScanNames(child, tables);
        }
    }

    private LogicalScan findScan(LogicalNode node) {
        if (node instanceof LogicalScan scan) {
            return scan;
        }
        for (LogicalNode child : node.getChildren()) {
            LogicalScan scan = findScan(child);
            if (scan != null) {
                return scan;
            }
        }
        return null;
    }

    private LogicalNode findJoinRoot(LogicalNode node) {
        if (node instanceof LogicalJoin) {
            return node;
        }
        if (node instanceof LogicalProject ||
                node instanceof LogicalFilter ||
                node instanceof LogicalAggregate) {
            return findJoinRoot(node.getChildren().getFirst());
        }
        return null;
    }

    public LogicalNode replaceJoinSubtree(LogicalNode plan,
                                          LogicalNode oldJoinRoot,
                                          LogicalNode newJoinRoot) {
        if (plan == oldJoinRoot) {
            return newJoinRoot;
        }

        ArrayList<LogicalNode> newChildren = new ArrayList<>();
        boolean changed = false;
        for (LogicalNode child : plan.getChildren()) {
            LogicalNode newChild = replaceJoinSubtree(child, oldJoinRoot, newJoinRoot);
            newChildren.add(newChild);
            changed |= newChild != child;
        }
        return changed ? plan.withChildren(newChildren) : plan;
    }
}
