package org.query.optimizer.rules;

import org.query.optimizer.Rule;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalFilter;
import org.query.optimizer.parser.LogicalJoin;
import org.query.optimizer.parser.LogicalScan;

import java.util.HashSet;
import java.util.Set;

/**
 * Predicate Pushdown Rule
 * <p>
 * Pushes Filter nodes down the tree, closer to the data source.
 * This reduces the number of rows flowing through the query plan.
 * <p>
 * Pattern: Filter -> Join
 * Transform: Join with Filter pushed to appropriate child
 * <p>
 * Algorithm:
 * 1. Find Filter above Join
 * 2. Analyze predicate to determine which table(s) it references
 * 3. Push filter to left child, right child, or keep above join
 * <p>
 * Correctness: Filters can be pushed through inner joins because
 * filtering before or after joining produces the same result.
 */
public class PredicatePushdown implements Rule {
    @Override
    public String getName() {
        return "PredicatePushdown";
    }

    @Override
    public boolean matches(LogicalNode node) {
        // Pattern: Filter -> Join
        if (!(node instanceof LogicalFilter filter)) {
            return false;
        }
        return filter.getChild() instanceof LogicalJoin;
    }

    @Override
    public LogicalNode apply(LogicalNode node) {
        LogicalFilter filter = (LogicalFilter) node;
        LogicalJoin join = (LogicalJoin) filter.getChild();

        Expression predicate = filter.getPredicate();
        // Determine which side(s) of the join this predicate references
        Set<String> referencedTables = getReferencedTables(predicate);
        Set<String> leftTables = getTablesInSubtree(join.getLeft());
        Set<String> rightTables = getTablesInSubtree(join.getRight());

        // Check if predicate can be pushed to left child
        boolean canPushLeft = leftTables.containsAll(referencedTables);
        // Check if predicate can be pushed to right child
        boolean canPushRight = rightTables.containsAll(referencedTables);

        if (canPushLeft && !canPushRight) {
            // Push to left child
            LogicalNode newLeft = new LogicalFilter(predicate, join.getLeft());
            return new LogicalJoin(newLeft, join.getRight(), join.getJoinType(), join.getCondition());
        } else if (canPushRight && !canPushLeft) {
            // Push to right child
            LogicalNode newRight = new LogicalFilter(predicate, join.getRight());
            return new LogicalJoin(join.getLeft(), newRight, join.getJoinType(), join.getCondition());
        } else if (canPushLeft && canPushRight) {
            // Predicate references single table - pick left arbitrarily
            LogicalNode newLeft = new LogicalFilter(predicate, join.getLeft());
            return new LogicalJoin(newLeft, join.getRight(), join.getJoinType(), join.getCondition());
        } else {
            // Predicate references columns from both sides - cannot push down
            // Keep filter above join
            return null; // no transformation carried out
        }
    }

    /**
     * Get all table names in a subtree (from Scan nodes).
     */
    private Set<String> getTablesInSubtree(LogicalNode expr) {
        Set<String> tables = new HashSet<>();
        collectTablesInSubtree(expr, tables);
        return tables;
    }

    private void collectTablesInSubtree(LogicalNode node, Set<String> tables) {
        if (node instanceof LogicalScan) {
            tables.add(((LogicalScan) node).getTableName());
        }
        for (LogicalNode child : node.getChildren()) {
            collectTablesInSubtree(child, tables);
        }
    }

    /**
     * Get all table names referenced in an expression.
     */
    private Set<String> getReferencedTables(Expression expr) {
        Set<String> tables = new HashSet<>();
        collectReferencedTables(expr, tables);
        return tables;
    }

    private void collectReferencedTables(Expression expr, Set<String> tables) {
        if (expr instanceof Expression.ColumnRef col) {
            if (col.tableName() != null) {
                tables.add(col.tableName());
            }
            // Note: For unqualified columns, we'd need schema information
            // For simplicity, we assume all columns are qualified in predicates
        } else if (expr instanceof Expression.BinaryOp binaryOp) {
            collectReferencedTables(binaryOp.left(), tables);
            collectReferencedTables(binaryOp.right(), tables);
        } else {
            // Literals don't reference tables
        }
    }

    @Override
    public String getDescription() {
        return "Push filters below joins to reduce intermediate result sizes";
    }
}
