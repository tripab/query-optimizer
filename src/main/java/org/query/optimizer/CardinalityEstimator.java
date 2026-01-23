package org.query.optimizer;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.ColumnStats;
import org.query.optimizer.catalog.TableMetadata;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.*;

/**
 * Cardinality Estimator for logical operators.
 * <p>
 * Uses simple heuristics and statistics to estimate output row counts.
 * These estimates guide the optimizer's cost model.
 * <p>
 * Estimation techniques:
 * - Scan: table row count
 * - Filter: input_rows * selectivity
 * - Project: same as input (projection doesn't change row count)
 * - Join: standard join cardinality formula
 * - Aggregate: estimated number of groups
 */
public class CardinalityEstimator {
    private final Catalog catalog;

    public CardinalityEstimator(Catalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Estimate the output cardinality of a logical operator.
     */
    public long estimate(LogicalNode node) {
        return switch (node) {
            case LogicalScan logicalScan -> estimateScan(logicalScan);
            case LogicalFilter filter -> estimateFilter(filter);
            case LogicalProject project -> estimateProject(project);
            case LogicalJoin join -> estimateJoin(join);
            case LogicalAggregate logicalAggregate -> estimateAggregate(logicalAggregate);
            case null, default -> 1000; // Unknown operator, return default
        };
    }

    /**
     * Aggregate cardinality = estimated number of groups.
     * <p>
     * For GROUP BY on columns c1, c2, ...:
     * cardinality = min(input_rows, product of NDVs)
     */
    private long estimateAggregate(LogicalAggregate aggregate) {
        long inputRows = estimate(aggregate.getChild());
        if (aggregate.getGroupByColumns().isEmpty()) {
            return 1; // no GROUP BY, single output row
        }

        // Estimate number of groups
        // this is simplified here, we assume number of groups = min(input_rows, 10% of input)
        // TODO: use a better estimate such as NDV of GROUP BY columns
        return Math.max(1, Math.min(inputRows, inputRows / 10));
    }

    /**
     * Join cardinality estimation.
     * <p>
     * For equality join on columns c1, c2:
     * cardinality = (|R| * |S|) / max(NDV(c1), NDV(c2))
     * <p>
     * This assumes uniform distribution and independence.
     * For non-equality joins, use Cartesian product estimate.
     */
    private long estimateJoin(LogicalJoin join) {
        long leftRows = estimate(join.getLeft());
        long rightRows = estimate(join.getRight());
        Expression condition = join.getCondition();
        if (isEqualityJoin(condition)) {
            // extract column NDVs
            long ndv = estimateJoinNDV(condition, join.getLeft(), join.getRight());
            if (ndv > 0) {
                // Standard join cardinality formula
                return Math.max(1, (leftRows * rightRows) / ndv);
            }
        }

        // Fallback: assume 10% selectivity for join
        return (long) Math.max(1, leftRows * rightRows * 0.1);
    }

    /**
     * Estimate NDV for join condition.
     * Returns max(NDV(left_column), NDV(right_column))
     */
    private long estimateJoinNDV(Expression condition, LogicalNode left, LogicalNode right) {
        if (!(condition instanceof Expression.BinaryOp binaryOp)) {
            return 0;
        }
        Expression.ColumnRef leftCol = null, rightCol = null;
        if (binaryOp.left() instanceof Expression.ColumnRef) {
            leftCol = (Expression.ColumnRef) binaryOp.left();
        }
        if (binaryOp.right() instanceof Expression.ColumnRef) {
            rightCol = (Expression.ColumnRef) binaryOp.right();
        }
        if (leftCol == null || rightCol == null) {
            return 0;
        }

        long leftNDV = getColumnNDV(leftCol, left);
        long rightNDV = getColumnNDV(rightCol, right);
        return Math.max(leftNDV, rightNDV);
    }

    /**
     * Get NDV for a column in a subtree.
     */
    private long getColumnNDV(Expression.ColumnRef column, LogicalNode node) {
        String tableName = findTableForColumn(column, node);
        if (tableName != null) {
            TableMetadata table = catalog.getTableMetadata(tableName);
            ColumnStats stats = table.getColumnStats(column.columnName());
            if (stats != null)
                return stats.numDistinctValues();
        }

        return 1; // unknown, so we take a conservative estimate
    }

    /**
     * Check if join condition is an equality join.
     */
    private boolean isEqualityJoin(Expression condition) {
        return condition instanceof Expression.BinaryOp &&
                ((Expression.BinaryOp) condition).operator() == Expression.BinaryOp.Operator.EQ;
    }

    /**
     * Project doesn't change row count
     */
    private long estimateProject(LogicalProject project) {
        return estimate(project.getChild());
    }

    /**
     * Filter cardinality = input_rows * selectivity
     */
    private long estimateFilter(LogicalFilter filter) {
        long inputRows = estimate(filter.getChild());
        double selectivity = estimateSelectivity(filter.getPredicate(), filter.getChild());

        return (long) Math.max(1, inputRows * selectivity);
    }

    /**
     * Estimate selectivity of a predicate.
     * <p>
     * Selectivity heuristics:
     * - column = value: 1 / NDV(column)
     * - column > value: 0.33 (assumes uniform distribution)
     * - column < value: 0.33
     * - pred1 AND pred2: selectivity(pred1) * selectivity(pred2)
     * - pred1 OR pred2: selectivity(pred1) + selectivity(pred2) - (sel1 * sel2)
     */
    private double estimateSelectivity(Expression predicate, LogicalNode input) {
        if (predicate instanceof Expression.BinaryOp binaryOp) {
            switch (binaryOp.operator()) {
                case EQ:
                    return estimateEqualitySelectivity(binaryOp, input);
                case NEQ:
                    return 1.0 - estimateEqualitySelectivity(binaryOp, input);
                case GT:
                case GTE:
                case LT:
                case LTE:
                    return 0.33; // Default for range predicates
                case AND:
                    double leftSel = estimateSelectivity(binaryOp.left(), input);
                    double rightSel = estimateSelectivity(binaryOp.right(), input);
                    return leftSel + rightSel;
                case OR:
                    double leftSelOr = estimateSelectivity(binaryOp.left(), input);
                    double rightSelOr = estimateSelectivity(binaryOp.right(), input);
                    return leftSelOr + rightSelOr - (leftSelOr * rightSelOr);
                default:
                    return 0.1; // unknown operator
            }
        }

        return 0.1; // default selectivity
    }

    /**
     * Estimate selectivity for equality predicate.
     * Uses 1/NDV if we have statistics, otherwise 0.1
     */
    private double estimateEqualitySelectivity(Expression.BinaryOp predicate, LogicalNode input) {
        // Try to find column reference
        Expression.ColumnRef column = null;

        if (predicate.left() instanceof Expression.ColumnRef) {
            column = (Expression.ColumnRef) predicate.left();
        } else if (predicate.right() instanceof Expression.ColumnRef) {
            column = (Expression.ColumnRef) predicate.right();
        }
        if (column != null) {
            // Find the table this column comes from
            String tableName = findTableForColumn(column, input);
            if (tableName != null) {
                TableMetadata table = catalog.getTableMetadata(tableName);
                ColumnStats stats = table.getColumnStats(column.columnName());
                if (stats != null && stats.numDistinctValues() > 0) {
                    // Use 1/NDV formula
                    return 1.0 / stats.numDistinctValues();
                }
            }
        }

        return 0;
    }

    /**
     * Find which table a column belongs to by traversing the input tree.
     */
    private String findTableForColumn(Expression.ColumnRef column, LogicalNode node) {
        if (node instanceof LogicalScan scan) {
            // Check if this table has the column
            TableMetadata table = catalog.getTableMetadata(scan.getTableName());
            if (table.getSchema().hasColumn(column.columnName())) {
                return scan.getTableName();
            }
        }
        // Recursively search children
        for (LogicalNode child : node.getChildren()) {
            String tableName = findTableForColumn(column, child);
            if (tableName != null)
                return tableName;
        }

        return null;
    }

    /**
     * Scan cardinality = table row count
     */
    private long estimateScan(LogicalScan scan) {
        TableMetadata table = catalog.getTableMetadata(scan.getTableName());
        return table.getRowCount();
    }
}
