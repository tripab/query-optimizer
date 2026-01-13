package org.query.optimizer.catalog;

import java.util.*;

/**
 * Represents the schema (column definitions) for a table.
 * Schema instances are immutable.
 */
public class Schema {
    public record Column(String name, DataType type){} // Represents a single column definition.
    private final List<Column> columns;
    private final Map<String, Integer> columnIndex;

    public Schema(List<Column> columns) {
        this.columns = List.copyOf(columns);
        this.columnIndex = new HashMap<>();
        for(int i=0; i < columns.size(); i++) {
            columnIndex.put(columns.get(i).name().toLowerCase(), i);
        }
    }

    public List<Column> getColumns() {
        return columns;
    }

    public int columnCount() {
        return columns.size();
    }

    public Column getColumn(int index) {
        return columns.get(index);
    }

    public Column getColumn(String name) {
        Integer idx = columnIndex.get(name.toLowerCase());
        if (idx == null) {
            throw new IllegalArgumentException("Column not found: " + name);
        }
        return columns.get(idx);
    }

    public int getColumnIndex(String name) {
        Integer idx = columnIndex.get(name.toLowerCase());
        if (idx == null) {
            throw new IllegalArgumentException("Column not found: " + name);
        }
        return idx;
    }

    public boolean hasColumn(String name) {
        return columnIndex.containsKey(name.toLowerCase());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Schema[");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            Column col = columns.get(i);
            sb.append(col.name()).append(":").append(col.type());
        }
        sb.append("]");
        return sb.toString();
    }

}
