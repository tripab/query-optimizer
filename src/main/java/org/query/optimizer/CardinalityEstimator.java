package org.query.optimizer;

import org.query.optimizer.SubtreeStatistics.ColumnEstimate;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.ColumnStats;
import org.query.optimizer.catalog.Histogram;
import org.query.optimizer.catalog.TableMetadata;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cardinality Estimator for logical operators.
 * <p>
 * Estimates are produced by propagating {@link SubtreeStatistics} up the logical
 * plan. Each operator derives the estimated row count <em>and</em> per-column
 * distinct-value (NDV) estimates of its output from those of its children, so
 * estimates made higher in the tree reflect the filtering and projection done
 * below them rather than always reaching back to the original base-table
 * statistics.
 * <p>
 * Estimation techniques:
 * - Scan: base-table statistics ({@link SubtreeStatistics#forScan})
 * - Filter: input_rows * selectivity; equality-filtered columns collapse to NDV 1,
 *   other column NDVs are capped at the surviving row count
 * - Project: same rows as input, keeping only surviving columns' estimates
 * - Join: standard join cardinality formula using propagated child NDVs
 * - Aggregate: estimated number of groups from propagated group-by NDVs
 * <p>
 * <b>Known limitation — unqualified column keys.</b> {@link SubtreeStatistics}
 * keys per-column estimates by unqualified name. Join-key NDV resolution
 * disambiguates same-named columns across the two join inputs via the table
 * qualifier (see {@link #sideKeyNdv}), but columns that share a name <em>within</em>
 * a single subtree still collapse to one estimate. This should be addressed
 * exhaustively later (e.g. by keying on a qualified (table, column) identity).
 */
public class CardinalityEstimator {
    private final Catalog catalog;

    public CardinalityEstimator(Catalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Estimate the output cardinality (row count) of a logical operator.
     */
    public long estimate(LogicalNode node) {
        return propagate(node).rowCount();
    }

    /**
     * Estimate the full output statistics (row count + per-column estimates) of a
     * logical subtree by recursively propagating statistics from its children.
     */
    public SubtreeStatistics propagate(LogicalNode node) {
        return switch (node) {
            case LogicalScan logicalScan -> propagateScan(logicalScan);
            case LogicalFilter logicalFilter -> propagateFilter(logicalFilter);
            case LogicalProject logicalProject -> propagateProject(logicalProject);
            case LogicalJoin logicalJoin -> propagateJoin(logicalJoin);
            case LogicalAggregate logicalAggregate -> propagateAggregate(logicalAggregate);
            case null, default -> new SubtreeStatistics(1000, Map.of()); // unknown operator
        };
    }

    // =========================================================================
    // Per-operator propagation
    // =========================================================================

    /**
     * Scan statistics come straight from the base table.
     */
    private SubtreeStatistics propagateScan(LogicalScan scan) {
        TableMetadata table = catalog.getTableMetadata(scan.getTableName());
        return SubtreeStatistics.forScan(table);
    }

    /**
     * Filter keeps {@code input_rows * selectivity} rows. Column NDVs are adjusted
     * conservatively: a column constrained by an equality predicate collapses to a
     * single distinct value, while every other column's NDV is capped at the
     * surviving row count (it cannot have more distinct values than rows).
     */
    private SubtreeStatistics propagateFilter(LogicalFilter filter) {
        SubtreeStatistics childStats = propagate(filter.getChild());
        double selectivity = estimateSelectivity(filter.getPredicate(), filter.getChild());

        // Preserve the historical row formula exactly (truncating cast, floored at 1).
        long rows = Math.max(1, (long) (childStats.rowCount() * selectivity));

        Map<String, Object> equalityConstants = collectEqualityConstants(filter.getPredicate());

        Map<String, ColumnEstimate> columns = new LinkedHashMap<>();
        for (Map.Entry<String, ColumnEstimate> entry : childStats.columnEstimates().entrySet()) {
            String name = entry.getKey();
            ColumnEstimate est = entry.getValue();
            if (equalityConstants.containsKey(name)) {
                Object constant = equalityConstants.get(name);
                columns.put(name, new ColumnEstimate(1, constant, constant));
            } else {
                columns.put(name, capNdv(est, rows));
            }
        }
        return new SubtreeStatistics(rows, columns);
    }

    /**
     * Projection does not change the row count; it keeps the estimates for the
     * columns that survive in its output.
     */
    private SubtreeStatistics propagateProject(LogicalProject project) {
        SubtreeStatistics childStats = propagate(project.getChild());

        Map<String, ColumnEstimate> columns = new LinkedHashMap<>();
        for (String columnName : project.getColumnNames()) {
            ColumnEstimate est = childStats.columnEstimate(columnName);
            if (est != null) {
                columns.put(columnName.toLowerCase(), est);
            }
        }
        return new SubtreeStatistics(childStats.rowCount(), columns);
    }

    /**
     * Join cardinality estimation.
     * <p>
     * For an equality join on columns c1, c2:
     * cardinality = (|R| * |S|) / max(NDV(c1), NDV(c2))
     * <p>
     * The NDVs are taken from the propagated child statistics, so a filter or
     * projection beneath the join reduces the estimate. For non-equality joins,
     * a 10% selectivity fallback is used.
     */
    private SubtreeStatistics propagateJoin(LogicalJoin join) {
        SubtreeStatistics leftStats = propagate(join.getLeft());
        SubtreeStatistics rightStats = propagate(join.getRight());
        long leftRows = leftStats.rowCount();
        long rightRows = rightStats.rowCount();

        Expression condition = join.getCondition();
        long rows;
        String leftKeyName = null;
        String rightKeyName = null;
        long joinKeyNdv = -1;

        if (isEqualityJoin(condition)
                && condition instanceof Expression.BinaryOp binary
                && binary.left() instanceof Expression.ColumnRef colA
                && binary.right() instanceof Expression.ColumnRef colB) {
            long ndvA = sideKeyNdv(colA, join, leftStats, leftRows, rightStats, rightRows);
            long ndvB = sideKeyNdv(colB, join, leftStats, leftRows, rightStats, rightRows);
            long denom = Math.max(1, Math.max(ndvA, ndvB));
            rows = Math.max(1, (leftRows * rightRows) / denom);

            leftKeyName = colA.columnName().toLowerCase();
            rightKeyName = colB.columnName().toLowerCase();
            joinKeyNdv = Math.min(Math.min(ndvA, ndvB), rows);
        } else {
            // Non-equality join: fall back to 10% selectivity (Cartesian * 0.1)
            rows = Math.max(1, (long) (leftRows * rightRows * 0.1));
        }

        // Output columns: union of both sides, NDV capped at the join's row count.
        Map<String, ColumnEstimate> columns = new LinkedHashMap<>();
        for (Map.Entry<String, ColumnEstimate> e : leftStats.columnEstimates().entrySet()) {
            columns.put(e.getKey(), capNdv(e.getValue(), rows));
        }
        for (Map.Entry<String, ColumnEstimate> e : rightStats.columnEstimates().entrySet()) {
            columns.put(e.getKey(), capNdv(e.getValue(), rows));
        }
        // Join key columns share the surviving (intersection) distinct values.
        if (joinKeyNdv >= 0) {
            if (columns.containsKey(leftKeyName)) {
                columns.put(leftKeyName, new ColumnEstimate(joinKeyNdv, null, null));
            }
            if (columns.containsKey(rightKeyName)) {
                columns.put(rightKeyName, new ColumnEstimate(joinKeyNdv, null, null));
            }
        }
        return new SubtreeStatistics(rows, columns);
    }

    /**
     * Aggregate cardinality = estimated number of groups.
     * <p>
     * For GROUP BY on columns c1, c2, ... the group count is estimated as the
     * product of the propagated NDVs of those columns, capped at the input row
     * count. With no GROUP BY, the aggregate produces a single row.
     */
    private SubtreeStatistics propagateAggregate(LogicalAggregate aggregate) {
        SubtreeStatistics childStats = propagate(aggregate.getChild());
        long childRows = childStats.rowCount();
        List<String> groupBy = aggregate.getGroupByColumns();

        long groups;
        if (groupBy.isEmpty()) {
            groups = 1;
        } else {
            long product = 1;
            for (String col : groupBy) {
                long ndv = Math.max(1, Math.min(childStats.ndvOf(col, childRows), childRows));
                product = cappedProduct(product, ndv, childRows);
            }
            groups = Math.max(1, product);
        }

        Map<String, ColumnEstimate> columns = new LinkedHashMap<>();
        // Group-by columns keep their (capped) distinct-value estimate.
        for (String col : groupBy) {
            ColumnEstimate childEst = childStats.columnEstimate(col);
            long ndv = (childEst != null) ? Math.min(childEst.ndv(), groups) : groups;
            Object min = (childEst != null) ? childEst.min() : null;
            Object max = (childEst != null) ? childEst.max() : null;
            columns.put(col.toLowerCase(), new ColumnEstimate(ndv, min, max));
        }
        // Aggregate output columns: one value per group, range unknown.
        for (LogicalAggregate.AggregateOp op : aggregate.getAggregateOps()) {
            columns.put(op.outputColumn().toLowerCase(), new ColumnEstimate(groups, null, null));
        }
        return new SubtreeStatistics(groups, columns);
    }

    // =========================================================================
    // Helpers: statistics
    // =========================================================================

    /**
     * Caps a column estimate's NDV at {@code rows} (a column cannot have more
     * distinct values than there are rows), preserving min/max.
     */
    private static ColumnEstimate capNdv(ColumnEstimate est, long rows) {
        long capped = Math.min(est.ndv(), Math.max(0, rows));
        if (capped == est.ndv()) {
            return est;
        }
        return new ColumnEstimate(capped, est.min(), est.max());
    }

    /**
     * Overflow-safe {@code min(cap, product * factor)} for accumulating a group
     * count from per-column NDVs.
     */
    private static long cappedProduct(long product, long factor, long cap) {
        if (factor <= 0) {
            return product;
        }
        if (product > cap / factor) {
            return cap;
        }
        return Math.min(cap, product * factor);
    }

    /**
     * Resolves the NDV of a join-key column reference by first determining which
     * side of the join the column belongs to, then looking up its (unqualified)
     * name in that side's statistics. Using the table qualifier to pick the side
     * is what disambiguates columns that share a name across the two inputs (e.g.
     * both inputs having an {@code id} column). Falls back to the side's row count
     * (assume distinct) when the column has no tracked estimate.
     */
    private long sideKeyNdv(Expression.ColumnRef ref, LogicalJoin join,
                            SubtreeStatistics leftStats, long leftRows,
                            SubtreeStatistics rightStats, long rightRows) {
        boolean onLeft;
        if (ref.tableName() != null) {
            onLeft = subtreeContainsTable(join.getLeft(), ref.tableName());
        } else {
            // Unqualified reference: prefer the side that actually tracks the column.
            onLeft = leftStats.hasColumn(ref.columnName());
        }
        SubtreeStatistics side = onLeft ? leftStats : rightStats;
        long sideRows = onLeft ? leftRows : rightRows;
        return side.ndvOf(ref.columnName(), sideRows);
    }

    /**
     * Returns whether {@code subtree} scans a table named {@code tableName}.
     */
    private boolean subtreeContainsTable(LogicalNode subtree, String tableName) {
        if (subtree instanceof LogicalScan scan) {
            return scan.getTableName().equalsIgnoreCase(tableName);
        }
        for (LogicalNode child : subtree.getChildren()) {
            if (subtreeContainsTable(child, tableName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collects equality constraints ({@code column = literal}) from a predicate,
     * recursing through AND. Column = column comparisons and other operators are
     * ignored. Keys are lower-cased column names.
     */
    private Map<String, Object> collectEqualityConstants(Expression predicate) {
        Map<String, Object> result = new HashMap<>();
        collectEqualityConstants(predicate, result);
        return result;
    }

    private void collectEqualityConstants(Expression predicate, Map<String, Object> out) {
        if (!(predicate instanceof Expression.BinaryOp binary)) {
            return;
        }
        switch (binary.operator()) {
            case EQ -> {
                Expression.ColumnRef column = null;
                Object value = null;
                if (binary.left() instanceof Expression.ColumnRef c
                        && binary.right() instanceof Expression.Literal<?> lit) {
                    column = c;
                    value = lit.value();
                } else if (binary.right() instanceof Expression.ColumnRef c
                        && binary.left() instanceof Expression.Literal<?> lit) {
                    column = c;
                    value = lit.value();
                }
                if (column != null) {
                    out.put(column.columnName().toLowerCase(), value);
                }
            }
            case AND -> {
                collectEqualityConstants(binary.left(), out);
                collectEqualityConstants(binary.right(), out);
            }
            default -> {
                // Other operators do not pin a column to a single value.
            }
        }
    }

    // =========================================================================
    // Helpers: selectivity (histogram / NDV based, unchanged)
    // =========================================================================

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
}
