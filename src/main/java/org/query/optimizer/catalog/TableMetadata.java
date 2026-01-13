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

    // Reference to actual data (in-memory for this version)
    private final List<Object[]> data;

    public TableMetadata(String tableName, Schema schema, List<Object[]> data) {
        this.tableName = tableName;
        this.schema = schema;
        this.rowCount = data.size();
        this.columnStats = new HashMap<>();
        this.data = List.copyOf(data);
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

    public List<Object[]> getData() {
        return data;
    }

    /**
     * Add statistics for a column. Called during statistics collection.
     */
    public void addColumnStats(ColumnStats stats) {
        this.columnStats.put(stats.columnName().toLowerCase(), stats);
    }

    public ColumnStats getColumnStats(String columnName) {
        return columnStats.get(columnName.toLowerCase());
    }

    public boolean hasColumnStats(String columnName) {
        return columnStats.containsKey(columnName.toLowerCase());
    }

    @Override
    public String toString() {
        return String.format("Table[%s, rows=%d, schema=%s]",
                tableName, rowCount, schema);
    }

    public Object[] getRow(int index) {
        return data.get(index);
    }

    public Object getValue(int rowIndex, String columnName) {
        int colIndex = schema.getColumnIndex(columnName);
        return data.get(rowIndex)[colIndex];
    }
}
