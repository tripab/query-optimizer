package org.query.optimizer;

import org.query.optimizer.catalog.ColumnStats;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.TableMetadata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Estimated statistics for the output of a logical subtree.
 *
 * <p>Where {@link ColumnStats} captures <em>collected</em> statistics for a base
 * table column, this structure captures <em>derived</em> statistics for whatever
 * a subtree produces: the estimated row count plus a per-column estimate of the
 * number of distinct values (NDV) and, optionally, the surviving min/max bounds.
 *
 * <p>The point of carrying these alongside the plan is so that estimates made
 * higher in the tree (joins, aggregates) can use NDVs that reflect the filtering
 * and projection done below them, instead of always reaching back to the original
 * base-table statistics. This class is the foundation for that propagation;
 * deriving statistics for non-scan operators is layered on top of it separately.
 *
 * <p>Instances are immutable. Column estimates are keyed case-insensitively to
 * match the rest of the catalog, which lower-cases column names.
 *
 * <p><b>Known limitation — unqualified column keys.</b> Columns are keyed by their
 * <em>unqualified</em> name only, with no table qualifier. When a subtree exposes
 * two columns that share a name (for example both inputs of a join having an
 * {@code id} column), they collapse to a single entry and one estimate is lost.
 * Join-key NDV resolution works around this by using the column's table qualifier
 * to pick the correct side before looking the name up (see
 * {@code CardinalityEstimator.sideKeyNdv}), but the general within-subtree collision
 * remains. This should be addressed exhaustively later, most likely by keying
 * estimates on a qualified (table, column) identity rather than a bare name.
 */
public final class SubtreeStatistics {

    /**
     * Per-column estimate.
     *
     * @param ndv estimated number of distinct values; must be {@code >= 0}
     * @param min estimated minimum value, or {@code null} if unknown
     * @param max estimated maximum value, or {@code null} if unknown
     */
    public record ColumnEstimate(long ndv, Object min, Object max) {
        public ColumnEstimate {
            if (ndv < 0) {
                throw new IllegalArgumentException("ndv must be >= 0, got " + ndv);
            }
        }
    }

    private final long rowCount;
    private final Map<String, ColumnEstimate> columns;

    /**
     * @param rowCount estimated output row count; must be {@code >= 0}
     * @param columns  per-column estimates; keys are normalised to lower case and
     *                 the map is defensively copied (insertion order preserved)
     */
    public SubtreeStatistics(long rowCount, Map<String, ColumnEstimate> columns) {
        if (rowCount < 0) {
            throw new IllegalArgumentException("rowCount must be >= 0, got " + rowCount);
        }
        this.rowCount = rowCount;
        Map<String, ColumnEstimate> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ColumnEstimate> entry : columns.entrySet()) {
            copy.put(entry.getKey().toLowerCase(), entry.getValue());
        }
        this.columns = copy;
    }

    public long rowCount() {
        return rowCount;
    }

    /**
     * Returns the estimate for {@code columnName}, or {@code null} if no estimate
     * is tracked for that column.
     */
    public ColumnEstimate columnEstimate(String columnName) {
        return columns.get(columnName.toLowerCase());
    }

    public boolean hasColumn(String columnName) {
        return columns.containsKey(columnName.toLowerCase());
    }

    /**
     * Returns the tracked NDV for {@code columnName}, or {@code fallback} if no
     * estimate is tracked for that column.
     */
    public long ndvOf(String columnName, long fallback) {
        ColumnEstimate estimate = columns.get(columnName.toLowerCase());
        return estimate != null ? estimate.ndv() : fallback;
    }

    /** Lower-cased names of all columns with a tracked estimate. */
    public Set<String> columnNames() {
        return Collections.unmodifiableSet(columns.keySet());
    }

    /** Unmodifiable view of all column estimates, keyed by lower-cased name. */
    public Map<String, ColumnEstimate> columnEstimates() {
        return Collections.unmodifiableMap(columns);
    }

    /**
     * Derives subtree statistics for a base-table scan from the table's collected
     * statistics.
     *
     * <p>Preserves the base-table NDV/min/max for every column that has collected
     * {@link ColumnStats}. The NDV is capped at the table's row count, since a
     * column cannot hold more distinct values than there are rows (this also
     * guards against externally-supplied stats that violate that invariant).
     * Columns without collected statistics are omitted; callers should use
     * {@link #ndvOf(String, long)} with a sensible fallback for those.
     *
     * @param table the base table being scanned
     * @return statistics describing the scan's output
     */
    public static SubtreeStatistics forScan(TableMetadata table) {
        long rows = table.getRowCount();
        Map<String, ColumnEstimate> cols = new LinkedHashMap<>();
        for (Schema.Column column : table.getSchema().getColumns()) {
            ColumnStats stats = table.getColumnStats(column.name());
            if (stats == null) {
                continue; // no collected statistics for this column
            }
            long cappedNdv = Math.min(stats.numDistinctValues(), rows);
            cols.put(column.name().toLowerCase(),
                    new ColumnEstimate(cappedNdv, stats.minValue(), stats.maxValue()));
        }
        return new SubtreeStatistics(rows, cols);
    }

    @Override
    public String toString() {
        return "SubtreeStatistics[rows=" + rowCount + ", columns=" + columns + "]";
    }
}
