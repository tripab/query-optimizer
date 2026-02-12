package org.query.optimizer.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds histograms for table columns.
 * <p>
 * This is called during statistics collection to create
 * equi-depth histograms for numeric columns.
 */
public class HistogramBuilder {
    private static final int DEFAULT_NUM_BUCKETS = 10;

    /**
     * Build histograms for all appropriate columns in a table.
     * <p>
     * Only builds histograms for:
     * - INTEGER columns
     * - FLOAT columns
     * <p>
     * VARCHAR columns could have histograms but are skipped for simplicity.
     *
     * @param table      Table metadata
     * @param numBuckets Number of buckets per histogram (default 10)
     */
    public static <T extends Comparable<? super T>> void buildHistograms(TableMetadata table, int numBuckets) {
        Schema schema = table.getSchema();
        List<Map<Schema.Column, Object>> data = table.getData();

        for (int colIdx = 0; colIdx < schema.columnCount(); colIdx++) {
            Schema.Column column = schema.getColumn(colIdx);

            // Only build histograms for numeric columns
            if (shouldBuildHistogram(column.type())) {
                Histogram<T> histogram = buildHistogramForColumn(
                        column.name(),
                        column.type(),
                        data,
                        colIdx,
                        numBuckets
                );

                table.addHistogram(histogram);
            }
        }
    }

    /**
     * Build histograms with default number of buckets.
     */
    public static <T extends Comparable<? super T>> void buildHistograms(TableMetadata table) {
        buildHistograms(table, DEFAULT_NUM_BUCKETS);
    }

    /**
     * Check if we should build a histogram for this data type.
     */
    private static boolean shouldBuildHistogram(DataType type) {
        return type == DataType.INTEGER || type == DataType.FLOAT;
    }

    /**
     * Build a histogram for a specific column.
     */
    private static <T extends Comparable<? super T>> Histogram<T> buildHistogramForColumn(
            String columnName,
            DataType dataType,
            List<Map<Schema.Column, Object>> data,
            int columnIndex,
            int numBuckets) {
        // Extract all values for this column
        List<Object> values = new ArrayList<>();
        for (Map<Schema.Column, Object> row : data) {
            values.add(row.get(new Schema.Column(columnName, dataType)));
        }

        // Build histogram
        return Histogram.build(columnName, dataType, values, numBuckets);
    }

    /**
     * Configuration for histogram building.
     */
    public static class HistogramConfig {
        public int numBuckets = DEFAULT_NUM_BUCKETS;
        public boolean buildForIntegers = true;
        public boolean buildForFloats = true;
        public boolean buildForStrings = false; // Not implemented yet

        public HistogramConfig() {
        }

        public HistogramConfig(int numBuckets) {
            this.numBuckets = numBuckets;
        }
    }
}