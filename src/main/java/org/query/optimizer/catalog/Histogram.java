package org.query.optimizer.catalog;

import java.util.*;

/**
 * Equi-Depth Histogram for selectivity estimation.
 * <p>
 * An equi-depth histogram divides data into buckets where each bucket
 * contains approximately the same number of rows. This provides much
 * better selectivity estimates than just using NDV, especially for
 * range predicates.
 * <p>
 * Example: For values [1,2,2,3,3,3,4,5,5,6] with 3 buckets:
 * Bucket 1: [1,2] - 3 rows
 * Bucket 2: [2,3] - 4 rows
 * Bucket 3: [4,6] - 3 rows
 * <p>
 * Used by CardinalityEstimator to improve estimates for:
 * - Equality predicates (col = value)
 * - Range predicates (col > value, col < value, col BETWEEN x AND y)
 */
public class Histogram<T extends Comparable<? super T>> {
    private final String columnName;
    private final DataType dataType;
    private final int numBuckets;
    private final List<Bucket<T>> buckets;
    private final long totalRows;

    /**
     * A single bucket in the histogram.
     *
     * @param lowerBound    Inclusive
     * @param upperBound    Inclusive
     * @param rowCount      Rows in this bucket
     * @param distinctCount Distinct values in bucket
     */
    public record Bucket<T extends Comparable<? super T>>(T lowerBound, T upperBound, long rowCount,
                                                          long distinctCount) {
        /**
         * Check if a value falls within this bucket's range.
         */
        public boolean contains(T value) {
            return lowerBound.compareTo(value) <= 0 &&
                    upperBound.compareTo(value) >= 0;
        }

        /**
         * Average frequency (rows per distinct value).
         */
        public double density() {
            return distinctCount > 0 ? (double) rowCount / distinctCount : 0.0;
        }

        @Override
        public String toString() {
            return String.format("Bucket[%s-%s: %d rows, %d distinct]",
                    lowerBound, upperBound, rowCount, distinctCount);
        }
    }

    private Histogram(String columnName, DataType dataType, int numBuckets,
                      List<Bucket<T>> buckets, long totalRows) {
        this.columnName = columnName;
        this.dataType = dataType;
        this.numBuckets = numBuckets;
        this.buckets = buckets;
        this.totalRows = totalRows;
    }

    public String getColumnName() {
        return columnName;
    }

    public DataType getDataType() {
        return dataType;
    }

    public int getNumBuckets() {
        return numBuckets;
    }

    public List<Bucket<T>> getBuckets() {
        return Collections.unmodifiableList(buckets);
    }

    public long getTotalRows() {
        return totalRows;
    }

    /**
     * Build an equi-depth histogram from column values.
     *
     * @param columnName Name of the column
     * @param dataType   Type of the column
     * @param values     All values from the column (including duplicates)
     * @param numBuckets Desired number of buckets
     * @return Histogram instance
     */
    public static <T extends Comparable<? super T>> Histogram<T> build(String columnName, DataType dataType,
                                                                       List<Object> values, int numBuckets) {
        if (values.isEmpty()) {
            return new Histogram<>(columnName, dataType, 0,
                    Collections.emptyList(), 0);
        }

        // Filter out nulls and convert to Comparable
        List<T> comparableValues = new ArrayList<>();
        for (Object val : values) {
            if (val instanceof Comparable<?>) {
                comparableValues.add((T) val);
            }
        }

        if (comparableValues.isEmpty()) {
            return new Histogram<>(columnName, dataType, 0,
                    Collections.emptyList(), values.size());
        }

        // Sort values
        Collections.sort(comparableValues);

        long totalRows = comparableValues.size();

        // Calculate bucket size (rows per bucket)
        int targetBucketSize = (int) Math.ceil((double) totalRows / numBuckets);

        // Build buckets
        List<Bucket<T>> buckets = new ArrayList<>();
        int startIdx = 0;

        while (startIdx < comparableValues.size()) {
            int endIdx = getEndIdx(startIdx, targetBucketSize, comparableValues);

            T lowerBound = comparableValues.get(startIdx);
            T upperBound = comparableValues.get(endIdx - 1);

            // Count distinct values in this bucket
            Set<T> distinctValues = new HashSet<>(
                    comparableValues.subList(startIdx, endIdx)
            );

            long rowCount = endIdx - startIdx;
            long distinctCount = distinctValues.size();

            buckets.add(new Bucket<>(lowerBound, upperBound,
                    rowCount, distinctCount));

            startIdx = endIdx;
        }

        return new Histogram<>(columnName, dataType, buckets.size(),
                buckets, totalRows);
    }

    private static <T extends Comparable<? super T>> int getEndIdx(int startIdx, int targetBucketSize, List<T> comparableValues) {
        int endIdx = Math.min(startIdx + targetBucketSize,
                comparableValues.size());

        // Adjust endIdx to avoid splitting equal values across buckets
        // Move to the last occurrence of the value at endIdx-1
        if (endIdx < comparableValues.size()) {
            T endValue = comparableValues.get(endIdx - 1);
            while (endIdx < comparableValues.size() &&
                    comparableValues.get(endIdx).equals(endValue)) {
                endIdx++;
            }
        }
        return endIdx;
    }

    /**
     * Estimate selectivity for an equality predicate (col = value).
     * <p>
     * Uses uniform distribution assumption within buckets.
     */
    public double estimateEquality(Object value) {
        if (value == null || !(value instanceof Comparable<?>)) {
            return 0.0;
        }

        // Find bucket containing this value
        Bucket<T> bucket = findBucket((T) value); // safe since value is Comparable
        if (bucket == null) {
            // Value outside histogram range
            return 0.0;
        }

        // Assume uniform distribution within bucket
        // Selectivity = (1 / distinct_count) * (bucket_rows / total_rows)
        if (bucket.distinctCount == 0) {
            return 0.0;
        }

        return bucket.density() / totalRows;
    }

    /**
     * Estimate selectivity for: col > value
     */
    public double estimateGreaterThan(Object val, boolean inclusive) {
        if (val == null || !(val instanceof Comparable value)) {
            return 0.0;
        }

        double selectivity = 0.0;

        for (Bucket<T> bucket : buckets) {
            double upperBound = 0, lowerBound = 0, v = 0;
            if (value instanceof Number && bucket.upperBound() instanceof Number) {
                v = ((Number) value).doubleValue();
                upperBound = ((Number) bucket.upperBound()).doubleValue();
                lowerBound = ((Number) bucket.lowerBound()).doubleValue();
            }
            if (Double.compare(v, upperBound) < 0 ||
                    (inclusive && Double.compare(v, upperBound) == 0)) {
                if (Double.compare(v, lowerBound) <= 0) {
                    // Entire bucket qualifies
                    selectivity += (double) bucket.rowCount / totalRows;
                } else {
                    // Partial bucket - estimate fraction
                    double fraction = estimateFractionGreaterThan(
                            Double.valueOf(v), bucket, inclusive
                    );
                    selectivity += fraction * bucket.rowCount / totalRows;
                }
            }
        }

        return Math.min(1.0, selectivity);
    }

    /**
     * Estimate selectivity for: col < value
     */
    public double estimateLessThan(Object val, boolean inclusive) {
        if (val == null || !(val instanceof Comparable value)) {
            return 0.0;
        }

        double selectivity = 0.0;

        for (Bucket<T> bucket : buckets) {
            double upperBound = 0, lowerBound = 0, v = 0;
            if (value instanceof Number && bucket.upperBound() instanceof Number) {
                v = ((Number) value).doubleValue();
                upperBound = ((Number) bucket.upperBound()).doubleValue();
                lowerBound = ((Number) bucket.lowerBound()).doubleValue();
            }
            if (Double.compare(v, lowerBound) > 0 ||
                    (inclusive && Double.compare(v, lowerBound) == 0)) {

                if (Double.compare(v, upperBound) >= 0) {
                    // Entire bucket qualifies
                    selectivity += (double) bucket.rowCount / totalRows;
                } else {
                    // Partial bucket - estimate fraction
                    double fraction = estimateFractionLessThan(
                            Double.valueOf(v), bucket, inclusive
                    );
                    selectivity += fraction * bucket.rowCount / totalRows;
                }
            }
        }

        return Math.min(1.0, selectivity);
    }

    /**
     * Estimate selectivity for: col BETWEEN low AND high
     */
    public double estimateRange(T lowValue, T highValue) {
        // P(low <= col <= high) = P(col <= high) - P(col < low)
        double pLessHigh = estimateLessThan(highValue, true);
        double pLessLow = estimateLessThan(lowValue, false);
        return Math.max(0.0, pLessHigh - pLessLow);
    }

    /**
     * Find the bucket containing a value.
     */
    private Bucket<T> findBucket(T value) {
        for (Bucket<T> bucket : buckets) {
            if (bucket.contains(value)) {
                return bucket;
            }
        }
        return null;
    }

    /**
     * Estimate fraction of bucket rows > value.
     * Assumes uniform distribution within bucket.
     */
    private double estimateFractionGreaterThan(Object value, Bucket<T> bucket,
                                               boolean inclusive) {
        // For numeric types, use linear interpolation
        if (dataType == DataType.INTEGER || dataType == DataType.FLOAT) {
            double bucketLow = ((Number) bucket.lowerBound).doubleValue();
            double bucketHigh = ((Number) bucket.upperBound).doubleValue();
            double val = ((Number) value).doubleValue();

            if (bucketHigh == bucketLow) {
                return inclusive ? 1.0 : 0.0;
            }

            // Fraction of range above value
            double fraction = (bucketHigh - val) / (bucketHigh - bucketLow);
            return Math.max(0.0, Math.min(1.0, fraction));
        }

        // For strings, use simple heuristic (half the bucket)
        return 0.5;
    }

    /**
     * Estimate fraction of bucket rows < value.
     */
    private double estimateFractionLessThan(Object value, Bucket<T> bucket,
                                            boolean inclusive) {
        if (dataType == DataType.INTEGER || dataType == DataType.FLOAT) {
            double bucketLow = ((Number) bucket.lowerBound).doubleValue();
            double bucketHigh = ((Number) bucket.upperBound).doubleValue();
            double val = ((Number) value).doubleValue();

            if (bucketHigh == bucketLow) {
                return inclusive ? 1.0 : 0.0;
            }

            double fraction = (val - bucketLow) / (bucketHigh - bucketLow);
            return Math.max(0.0, Math.min(1.0, fraction));
        }

        return 0.5;
    }

    /**
     * Print histogram for debugging.
     */
    public void print() {
        System.out.println("Histogram for " + columnName +
                " (" + dataType + ", " + numBuckets + " buckets):");
        System.out.println("Total rows: " + totalRows);
        for (int i = 0; i < buckets.size(); i++) {
            Bucket<T> b = buckets.get(i);
            System.out.println("  Bucket " + (i + 1) + ": " + b);
        }
    }

    /**
     * Get summary statistics.
     */
    public String getSummary() {
        if (buckets.isEmpty()) {
            return "Empty histogram";
        }

        Bucket<T> first = buckets.getFirst();
        Bucket<T> last = buckets.getLast();

        return String.format("Histogram[%s: %d buckets, range=%s to %s, total=%d rows]",
                columnName, numBuckets, first.lowerBound, last.upperBound, totalRows);
    }
}