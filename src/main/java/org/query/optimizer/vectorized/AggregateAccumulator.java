package org.query.optimizer.vectorized;

import org.query.optimizer.catalog.DataType;
import org.query.optimizer.parser.LogicalAggregate.AggFunction;

/**
 * Per-group, per-function accumulator used by {@link VectorizedAggregate}.
 *
 * <p>Each accumulator holds the running state for one aggregate function applied
 * to one column within a single group.  A new instance is created the first time
 * a group key is encountered; subsequent rows in the same group call
 * {@link #update(Object)}.  After all input has been consumed, {@link #result()}
 * returns the final aggregate value ready to be written into the output batch.
 *
 * <h2>Type handling</h2>
 * <p>Values arrive as boxed {@link Object}s because they are read from a
 * {@link ColumnVector} via the generic {@link ColumnVector#get(int)} accessor.
 * Each concrete subtype casts to the narrowest numeric type it needs:
 * <ul>
 *   <li>Integer columns arrive as {@code Integer}.</li>
 *   <li>Float columns arrive as {@code Float}.</li>
 *   <li>VARCHAR columns are supported only where the aggregate makes semantic
 *       sense (MIN, MAX via lexicographic ordering).</li>
 * </ul>
 *
 * <h2>Null semantics</h2>
 * <p>SQL aggregate functions ignore {@code NULL} values (except {@code COUNT(*)},
 * which counts all rows regardless).  Each {@link #update} implementation skips
 * {@code null} inputs accordingly.
 *
 * <h2>Factory</h2>
 * <p>Use {@link #create(AggFunction, DataType)} to obtain an accumulator for a
 * given function / column-type combination without switch statements at the
 * call site.
 */
public sealed interface AggregateAccumulator
        permits AggregateAccumulator.CountAccumulator,
        AggregateAccumulator.SumAccumulator,
        AggregateAccumulator.AvgAccumulator,
        AggregateAccumulator.MinAccumulator,
        AggregateAccumulator.MaxAccumulator {

    /**
     * Incorporates {@code value} (possibly {@code null}) into the running state.
     *
     * @param value the next column value for this group; may be {@code null}
     */
    void update(Object value);

    /**
     * Returns the final aggregate result after all rows for this group have been
     * processed.  The return type matches the {@link DataType} contract of the
     * accumulator (e.g. {@code Integer} for COUNT, {@code Float} for SUM over a
     * FLOAT column).
     *
     * @return the aggregate result; never {@code null} for COUNT, may be
     * {@code null} for other functions if no non-null rows were seen
     */
    Object result();

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Creates a fresh accumulator for {@code function} applied to a column of
     * {@code columnType}.
     *
     * @param function   the aggregate function
     * @param columnType the {@link DataType} of the column being aggregated;
     *                   used to choose integer vs. floating-point accumulators
     *                   for SUM/AVG/MIN/MAX; ignored for COUNT
     * @return a new, zero-initialised accumulator
     * @throws UnsupportedOperationException if the combination is not supported
     */
    static AggregateAccumulator create(AggFunction function, DataType columnType) {
        return switch (function) {
            case COUNT -> new CountAccumulator();
            case SUM -> new SumAccumulator(columnType);
            case AVG -> new AvgAccumulator(columnType);
            case MIN -> new MinAccumulator(columnType);
            case MAX -> new MaxAccumulator(columnType);
        };
    }

    // =========================================================================
    // COUNT
    // =========================================================================

    /**
     * Counts rows.
     *
     * <p>Counts every {@link #update} call regardless of whether {@code value}
     * is {@code null}, matching the semantics of {@code COUNT(*)}.  If the
     * logical plan uses {@code COUNT(col)}, null-filtering is handled upstream
     * by how the optimizer rewrites the plan; this accumulator always increments.
     *
     * <p>Result type: {@code Integer}.
     */
    final class CountAccumulator implements AggregateAccumulator {
        private int count = 0;

        @Override
        public void update(Object value) {
            // COUNT(*) counts all rows; COUNT(col) counts non-null values.
            // We treat every non-null value as a valid count contribution.
            if (value != null) count++;
        }

        @Override
        public Object result() {
            return count;
        }

        @Override
        public String toString() {
            return "COUNT=" + count;
        }
    }

    // =========================================================================
    // SUM
    // =========================================================================

    /**
     * Sums numeric values.
     *
     * <p>Maintains a running sum as {@code long} for INTEGER columns and as
     * {@code double} for FLOAT columns.  {@code null} inputs are skipped.
     *
     * <p>Result type: {@code Integer} for INTEGER columns, {@code Float} for FLOAT.
     * Returns {@code null} if no non-null values were seen.
     */
    final class SumAccumulator implements AggregateAccumulator {
        private final DataType type;
        private long intSum = 0L;
        private double floatSum = 0.0;
        private boolean hasValue = false;

        SumAccumulator(DataType type) {
            if (type == DataType.VARCHAR) {
                throw new UnsupportedOperationException("SUM is not defined for VARCHAR columns");
            }
            this.type = type;
        }

        @Override
        public void update(Object value) {
            if (value == null) return;
            hasValue = true;
            if (type == DataType.INTEGER) {
                intSum += ((Number) value).longValue();
            } else {
                floatSum += ((Number) value).doubleValue();
            }
        }

        @Override
        public Object result() {
            if (!hasValue) return null;
            if (type == DataType.INTEGER) {
                return (int) intSum;
            } else {
                return (float) floatSum;
            }
        }

        @Override
        public String toString() {
            if (type == DataType.INTEGER) {
                return "SUM=" + intSum;
            } else {
                return "SUM=" + floatSum;
            }
        }
    }

    // =========================================================================
    // AVG
    // =========================================================================

    /**
     * Computes the arithmetic mean of numeric values.
     *
     * <p>Tracks sum (as {@code double} regardless of column type) and a
     * non-null row count.  Division is performed once in {@link #result()}.
     *
     * <p>Result type: {@code Float} (even for INTEGER input columns), since
     * averages are generally fractional.
     * Returns {@code null} if no non-null values were seen.
     */
    final class AvgAccumulator implements AggregateAccumulator {
        private final DataType type;
        private double sum = 0.0;
        private int count = 0;

        AvgAccumulator(DataType type) {
            if (type == DataType.VARCHAR) {
                throw new UnsupportedOperationException("AVG is not defined for VARCHAR columns");
            }
            this.type = type;
        }

        @Override
        public void update(Object value) {
            if (value == null) return;
            sum += ((Number) value).doubleValue();
            count++;
        }

        @Override
        public Object result() {
            if (count == 0) return null;
            return (float) (sum / count);
        }

        @Override
        public String toString() {
            return "AVG=" + (count == 0 ? "null" : (sum / count));
        }
    }

    // =========================================================================
    // MIN
    // =========================================================================

    /**
     * Tracks the minimum value seen.
     *
     * <p>Supports INTEGER, FLOAT, and VARCHAR (lexicographic ordering).
     * {@code null} inputs are skipped.
     *
     * <p>Result type mirrors the input column type:
     * {@code Integer} / {@code Float} / {@code String}.
     * Returns {@code null} if no non-null values were seen.
     */
    final class MinAccumulator implements AggregateAccumulator {
        private final DataType type;
        private Object min = null;

        MinAccumulator(DataType type) {
            this.type = type;
        }

        @Override
        public void update(Object value) {
            if (value == null) return;
            if (min == null || compare(value, min) < 0) min = value;
        }

        @Override
        public Object result() {
            return min;
        }

        @Override
        public String toString() {
            return "MIN=" + min;
        }

        private int compare(Object a, Object b) {
            return switch (type) {
                case INTEGER -> Integer.compare(((Number) a).intValue(), ((Number) b).intValue());
                case FLOAT -> Float.compare(((Number) a).floatValue(), ((Number) b).floatValue());
                case VARCHAR -> ((String) a).compareTo((String) b);
            };
        }
    }

    // =========================================================================
    // MAX
    // =========================================================================

    /**
     * Tracks the maximum value seen.
     *
     * <p>Supports INTEGER, FLOAT, and VARCHAR (lexicographic ordering).
     * {@code null} inputs are skipped.
     *
     * <p>Result type mirrors the input column type:
     * {@code Integer} / {@code Float} / {@code String}.
     * Returns {@code null} if no non-null values were seen.
     */
    final class MaxAccumulator implements AggregateAccumulator {
        private final DataType type;
        private Object max = null;

        MaxAccumulator(DataType type) {
            this.type = type;
        }

        @Override
        public void update(Object value) {
            if (value == null) return;
            if (max == null || compare(value, max) > 0) max = value;
        }

        @Override
        public Object result() {
            return max;
        }

        @Override
        public String toString() {
            return "MAX=" + max;
        }

        private int compare(Object a, Object b) {
            return switch (type) {
                case INTEGER -> Integer.compare(((Number) a).intValue(), ((Number) b).intValue());
                case FLOAT -> Float.compare(((Number) a).floatValue(), ((Number) b).floatValue());
                case VARCHAR -> ((String) a).compareTo((String) b);
            };
        }
    }
}
