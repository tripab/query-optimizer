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
 * Finds all scans and join conditions in a join subtree, including when
 * individual join inputs are wrapped in supported unary operators.
 */
public class JoinExtractor {
    record JoinInfo(List<LogicalScan> scans,
                    List<DPJoinOrderer.JoinCondition> conditions,
                    LogicalNode joinRoot,
                    boolean supported) {
        public boolean hasMultipleJoins() {
            return scans.size() > 1;
        }

        public boolean isSingleTable() {
            return scans.size() == 1;
        }

        public boolean hasJoinTree() {
            return joinRoot != null;
        }
    }

    public JoinInfo extract(LogicalNode plan) {
        ArrayList<LogicalScan> scans = new ArrayList<>();
        ArrayList<DPJoinOrderer.JoinCondition> conditions = new ArrayList<>();

        LogicalNode joinRoot = findJoinRoot(plan);
        if (joinRoot == null) {
            LogicalScan scan = findScan(plan);
            if (scan != null) {
                scans.add(scan);
            }
            return new JoinInfo(scans, conditions, null, true);
        }

        boolean supported = extractFromJoinTree(joinRoot, scans, conditions);
        if (!supported) {
            scans.clear();
            conditions.clear();
        }

        return new JoinInfo(scans, conditions, joinRoot, supported);
    }

    private boolean extractFromJoinTree(LogicalNode node,
                                        List<LogicalScan> scans,
                                        List<DPJoinOrderer.JoinCondition> conditions) {
        if (node instanceof LogicalScan scan) {
            scans.add(scan);
            return true;
        }
        if (node instanceof LogicalFilter filter) {
            return extractFromJoinTree(filter.getChild(), scans, conditions);
        }
        if (node instanceof LogicalProject project) {
            return extractFromJoinTree(project.getChild(), scans, conditions);
        }
        if (node instanceof LogicalAggregate aggregate) {
            return extractFromJoinTree(aggregate.getChild(), scans, conditions);
        }
        if (node instanceof LogicalJoin join) {
            if (join.getJoinType() != LogicalJoin.JoinType.INNER) {
                return false;
            }

            if (!extractFromJoinTree(join.getLeft(), scans, conditions) ||
                    !extractFromJoinTree(join.getRight(), scans, conditions)) {
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

        return false;
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
