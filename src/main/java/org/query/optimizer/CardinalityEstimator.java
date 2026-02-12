package org.query.optimizer;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.ColumnStats;
import org.query.optimizer.catalog.Histogram;
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
            case LogicalFilter logicalFilter -> estimateFilter(logicalFilter);
            case LogicalProject logicalProject -> estimateProject(logicalProject);
            case LogicalJoin logicalJoin -> estimateJoin(logicalJoin);
            case LogicalAggregate logicalAggregate -> estimateAggregate(logicalAggregate);
            case null, default -> 1000; // Unknown operator - return default

        };
    }

    /**
     * Scan cardinality = table row count
     */
    private long estimateScan(LogicalScan scan) {
        TableMetadata table = catalog.getTableMetadata(scan.getTableName());
        return table.getRowCount();
    }

    /**
     * Filter cardinality = input_rows * selectivity
     */
    private long estimateFilter(LogicalFilter filter) {
        long inputRows = estimate(filter.getChild());
        double selectivity = estimateSelectivity(filter.getPredicate(), filter.getChild());

        return Math.max(1, (long) (inputRows * selectivity));
    }

    /**
     * Project doesn't change row count
     */
    private long estimateProject(LogicalProject project) {
        return estimate(project.getChild());
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

        // Analyze join condition
        Expression condition = join.getCondition();
        if (isEqualityJoin(condition)) {
            // Extract column NDVs
            long ndv = estimateJoinNDV(condition, join.getLeft(), join.getRight());
            if (ndv > 0) {
                // Standard join cardinality formula
                return Math.max(1, (leftRows * rightRows) / ndv);
            }
        }

        // Fallback: assume 10% selectivity for join
        return Math.max(1, (long) (leftRows * rightRows * 0.1));
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
            // No GROUP BY - single output row
            return 1;
        }

        // Estimate number of groups
        // Simplified: assume number of groups = min(input_rows, 10% of input)
        // A better estimate would use NDV of GROUP BY columns
        return Math.max(1, Math.min(inputRows, inputRows / 10));
    }

    /**
     * Estimate selectivity of a predicate.
     * <p>
     * Selectivity heuristics:
     * - column = value: Use histogram if available, else 1 / NDV(column)
     * - column > value: Use histogram if available, else 0.33
     * - column < value: Use histogram if available, else 0.33
     * - pred1 AND pred2: selectivity(pred1) * selectivity(pred2)
     * - pred1 OR pred2: selectivity(pred1) + selectivity(pred2) - (sel1 * sel2)
     */
    private double estimateSelectivity(Expression predicate, LogicalNode input) {
        if (predicate instanceof Expression.BinaryOp binary) {
            switch (binary.operator()) {
                case EQ:
                    return estimateEqualitySelectivity(binary, input);
                case NEQ:
                    return 1.0 - estimateEqualitySelectivity(binary, input);
                case GT:
                    return estimateRangeSelectivity(binary, input, false, true);
                case GTE:
                    return estimateRangeSelectivity(binary, input, true, true);
                case LT:
                    return estimateRangeSelectivity(binary, input, false, false);
                case LTE:
                    return estimateRangeSelectivity(binary, input, true, false);
                case AND:
                    double leftSel = estimateSelectivity(binary.left(), input);
                    double rightSel = estimateSelectivity(binary.right(), input);
                    return leftSel * rightSel;
                case OR:
                    double leftSelOr = estimateSelectivity(binary.left(), input);
                    double rightSelOr = estimateSelectivity(binary.right(), input);
                    return leftSelOr + rightSelOr - (leftSelOr * rightSelOr);
                default:
                    return 0.1; // Unknown operator
            }
        }

        return 0.1; // Default selectivity
    }

    /**
     * Estimate selectivity for range predicates (>, >=, <, <=).
     * Uses histogram if available, otherwise defaults to 0.33
     */
    private <T extends Comparable<? super T>> double estimateRangeSelectivity(
            Expression.BinaryOp predicate,
            LogicalNode input,
            boolean inclusive,
            boolean greaterThan) {
        // Extract column and value
        Expression.ColumnRef column = null;
        T value = null;

        if (predicate.left() instanceof Expression.ColumnRef) {
            column = (Expression.ColumnRef) predicate.left();
            if (predicate.right() instanceof Expression.Literal) {
                value = ((Expression.Literal<T>) predicate.right()).value();
            }
        } else if (predicate.right() instanceof Expression.ColumnRef) {
            column = (Expression.ColumnRef) predicate.right();
            if (predicate.left() instanceof Expression.Literal) {
                value = ((Expression.Literal<T>) predicate.left()).value();
            }
            // Flip direction if column is on right
            greaterThan = !greaterThan;
        }

        if (column != null && value != null) {
            String tableName = findTableForColumn(column, input);

            if (tableName != null) {
                TableMetadata table = catalog.getTableMetadata(tableName);
                Histogram<?> histogram = table.getHistogram(column.columnName());

                if (histogram != null) {
                    // Use histogram for accurate estimate
                    if (greaterThan) {
                        return histogram.estimateGreaterThan(value, inclusive);
                    } else {
                        return histogram.estimateLessThan(value, inclusive);
                    }
                }
            }
        }

        // Fall back to default heuristic
        return 0.33;
    }

    /**
     * Estimate selectivity for equality predicate.
     * Uses histogram if available, otherwise falls back to 1/NDV
     */
    private <T extends Comparable<? super T>> double estimateEqualitySelectivity(
            Expression.BinaryOp predicate, LogicalNode input) {
        // Try to find column reference
        Expression.ColumnRef column = null;
        T value = null;

        if (predicate.left() instanceof Expression.ColumnRef) {
            column = (Expression.ColumnRef) predicate.left();
            if (predicate.right() instanceof Expression.Literal) {
                value = ((Expression.Literal<T>) predicate.right()).value();
            }
        } else if (predicate.right() instanceof Expression.ColumnRef) {
            column = (Expression.ColumnRef) predicate.right();
            if (predicate.left() instanceof Expression.Literal) {
                value = ((Expression.Literal<T>) predicate.left()).value();
            }
        }

        if (column != null && value != null) {
            // Find the table this column comes from
            String tableName = findTableForColumn(column, input);

            if (tableName != null) {
                TableMetadata table = catalog.getTableMetadata(tableName);

                // Try histogram first
                Histogram<?> histogram = table.getHistogram(column.columnName());
                if (histogram != null) {
                    return histogram.estimateEquality(value);
                }

                // Fall back to NDV-based estimate
                ColumnStats stats = table.getColumnStats(column.columnName());
                if (stats != null && stats.numDistinctValues() > 0) {
                    return 1.0 / stats.numDistinctValues();
                }
            }
        }

        return 0.1; // Default selectivity
    }

    /**
     * Find which table a column belongs to by traversing the input tree.
     */
    private String findTableForColumn(Expression.ColumnRef column, LogicalNode node) {
        if (node instanceof LogicalScan scan) {
            // Check if this table has the column
            var table = catalog.getTableMetadata(scan.getTableName());
            if (table.getSchema().hasColumn(column.columnName())) {
                return scan.getTableName();
            }
        }

        // Recursively search children
        for (LogicalNode child : node.getChildren()) {
            String tableName = findTableForColumn(column, child);
            if (tableName != null) {
                return tableName;
            }
        }

        return null;
    }

    /**
     * Check if join condition is an equality join.
     */
    private boolean isEqualityJoin(Expression condition) {
        return condition instanceof Expression.BinaryOp &&
                ((Expression.BinaryOp) condition).operator() == Expression.BinaryOp.Operator.EQ;
    }

    /**
     * Estimate NDV for join condition.
     * Returns max(NDV(left_column), NDV(right_column))
     */
    private long estimateJoinNDV(Expression condition, LogicalNode left, LogicalNode right) {
        if (!(condition instanceof Expression.BinaryOp binary)) {
            return 0;
        }

        // Extract columns from both sides
        Expression.ColumnRef leftCol = null;
        Expression.ColumnRef rightCol = null;

        if (binary.left() instanceof Expression.ColumnRef) {
            leftCol = (Expression.ColumnRef) binary.left();
        }
        if (binary.right() instanceof Expression.ColumnRef) {
            rightCol = (Expression.ColumnRef) binary.right();
        }

        if (leftCol == null || rightCol == null) {
            return 0;
        }

        // Get NDV for both columns
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
            if (stats != null) {
                return stats.numDistinctValues();
            }
        }
        return 1; // Unknown - conservative estimate
    }
}