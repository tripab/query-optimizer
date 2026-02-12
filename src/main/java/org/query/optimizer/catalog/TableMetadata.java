package org.query.optimizer.catalog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Metadata for a single table including schema and statistics.
 */
public class TableMetadata {
    private final String tableName;
    private final Schema schema;
    private final long rowCount;
    private final Map<String, ColumnStats> columnStats;
    private final Map<String, Histogram<?>> histograms;

    // Reference to actual data (in-memory for this version)
    private final List<Map<Schema.Column, Object>> data;

    public TableMetadata(String tableName, Schema schema, List<Map<Schema.Column, Object>> data) {
        this.tableName = tableName;
        this.schema = schema;
        this.data = List.copyOf(data);
        this.rowCount = data.size();
        this.columnStats = new HashMap<>();
        this.histograms = new HashMap<>();
    }

    public String getTableName() {
        return tableName;
    }

    public Schema getSchema() {
        return schema;
    }

    public long getRowCount() {
        return rowCount;
    }

    public List<Map<Schema.Column, Object>> getData() {
        return data;
    }

    /**
     * Add statistics for a column. Called during statistics collection.
     */
    public void addColumnStats(ColumnStats stats) {
        columnStats.put(stats.columnName().toLowerCase(), stats);
    }

    public ColumnStats getColumnStats(String columnName) {
        return columnStats.get(columnName.toLowerCase());
    }

    public boolean hasColumnStats(String columnName) {
        return columnStats.containsKey(columnName.toLowerCase());
    }

    /**
     * Add a histogram for a column.
     */
    public void addHistogram(Histogram<?> histogram) {
        histograms.put(histogram.getColumnName().toLowerCase(), histogram);
    }

    /**
     * Get histogram for a column.
     */
    public Histogram<?> getHistogram(String columnName) {
        return histograms.get(columnName.toLowerCase());
    }

    /**
     * Check if histogram is available for a column.
     */
    public boolean hasHistogram(String columnName) {
        return histograms.containsKey(columnName.toLowerCase());
    }

    @Override
    public String toString() {
        return String.format("Table[%s, rows=%d, schema=%s]",
                tableName, rowCount, schema);
    }

    /**
     * Get a single row by index (for testing/debugging).
     */
    public Map<Schema.Column, Object> getRow(int index) {
        return data.get(index);
    }

    /**
     * Get value from a specific row and column.
     */
    public Object getValue(int rowIndex, String columnName) {
        return data.get(rowIndex).get(schema.getColumn(columnName));
    }
}
