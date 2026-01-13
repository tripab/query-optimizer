package org.query.optimizer.catalog;

/**
 * Simple column statistics for cardinality estimation.
 * Only tracks: row count, NDV (number of distinct values), min, and max.
 * No histograms in this version (deferred to phase 2).
 */
public record ColumnStats (String columnName, long numDistinctValues, Object minValue,
                           Object maxValue, long numNulls) {
    public static class Builder {
        private String columnName;
        private long numDistinctValues;
        private Object minValue;
        private Object maxValue;
        private long numNulls = 0;

        public Builder(String columnName) {
            this.columnName = columnName;
        }

        public Builder setNumDistinctValues(long ndv) {
            this.numDistinctValues = ndv;
            return this;
        }

        public Builder setMinValue(Object min) {
            this.minValue = min;
            return this;
        }

        public Builder setMaxValue(Object max) {
            this.maxValue = max;
            return this;
        }

        public Builder setNumNulls(long nulls) {
            this.numNulls = nulls;
            return this;
        }

        public ColumnStats build() {
            return new ColumnStats(columnName, numDistinctValues,
                    minValue, maxValue, numNulls);
        }
    }
}
