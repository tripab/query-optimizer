package org.query.optimizer.catalog;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

import org.query.optimizer.vectorized.ColumnarTable;

/**
 * Central catalog managing all table metadata and statistics.
 * This is the single source of truth for schema information.
 */
public class Catalog {
    private final Map<String, TableMetadata> tables;

    /** Lazily populated columnar views, keyed by lower-cased table name. */
    private final Map<String, ColumnarTable> columnarTables;

    public Catalog() {
        this.tables         = new HashMap<>();
        this.columnarTables = new HashMap<>();
    }

    public void registerTable(TableMetadata table) {
        tables.put(table.getTableName().toLowerCase(), table);
    }

    public TableMetadata getTableMetadata(String tableName) {
        TableMetadata table = tables.get(tableName.toLowerCase());
        if (table == null) {
            throw new IllegalArgumentException("Table not found: " + tableName);
        }
        return table;
    }

    public boolean hasTable(String tableName) {
        return tables.containsKey(tableName.toLowerCase());
    }

    public Set<String> getTableNames() {
        return tables.keySet();
    }

    /**
     * Load a table from a CSV file.
     * First row is assumed to be header with format: columnName:TYPE
     * Example: id:INTEGER,name:VARCHAR,price:FLOAT
     */
    public TableMetadata loadTableFromCSV(String tableName, String csvPath) throws IOException {
        List<String[]> rawData = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String line;
            while ((line = br.readLine()) != null) {
                // Simple CSV parsing (no quoted commas support for simplicity)
                String[] values = line.split(",");
                rawData.add(values);
            }
        }

        if (rawData.isEmpty()) {
            throw new IllegalArgumentException("CSV file is empty: " + csvPath);
        }

        // Parse header to build schema
        String[] header = rawData.getFirst();
        List<Schema.Column> columns = new ArrayList<>();
        for (String colDef : header) {
            String[] parts = colDef.trim().split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid column definition: " + colDef);
            }
            String colName = parts[0].trim();
            DataType colType = DataType.valueOf(parts[1].trim().toUpperCase());
            columns.add(new Schema.Column(colName, colType));
        }

        // Parse data rows
        List<Map<Schema.Column, Object>> data = new ArrayList<>();
        for (int i = 1; i < rawData.size(); i++) {
            String[] row = rawData.get(i);
            if (row.length != columns.size()) {
                throw new IllegalArgumentException(
                        "Row " + i + " has wrong number of columns: expected " +
                                columns.size() + ", got " + row.length);
            }
            Map<Schema.Column, Object> parsedRow = new HashMap<>();
            for (int j = 0; j < columns.size(); j++) {
                parsedRow.put(columns.get(j), columns.get(j).type().parse(row[j]));
            }
            data.add(parsedRow);
        }

        TableMetadata table = new TableMetadata(tableName, new Schema(columns), data);

        // Collect statistics
        collectStatistics(table);

        // Register in catalog
        registerTable(table);

        return table;
    }

    /**
     * Collect simple statistics for a table.
     * Computes NDV, min, max, and null count for each column.
     * Also builds histograms for numeric columns.
     */
    private void collectStatistics(TableMetadata table) {
        Schema schema = table.getSchema();
        List<Map<Schema.Column, Object>> data = table.getData();

        for (int colIdx = 0; colIdx < schema.columnCount(); colIdx++) {
            Schema.Column column = schema.getColumn(colIdx);

            Set<Object> distinctValues = new HashSet<>();
            Object minValue = null, maxValue = null;
            long nullCount = 0;

            for (Map<Schema.Column, Object> row : data) {
                Object value = row.get(schema.getColumn(colIdx));
                if (value == null) {
                    nullCount++;
                    continue;
                }
                distinctValues.add(value);
                switch (column.type()) {
                    case INTEGER -> {
                        int intVal = (Integer) value;
                        if (minValue == null || intVal < (Integer) minValue) {
                            minValue = intVal;
                        }
                        if (maxValue == null || intVal > (Integer) maxValue) {
                            maxValue = intVal;
                        }
                    }
                    case FLOAT -> {
                        float floatVal = (Float) value;
                        if (minValue == null || floatVal < (Float) minValue) {
                            minValue = floatVal;
                        }
                        if (maxValue == null || floatVal > (Float) maxValue) {
                            maxValue = floatVal;
                        }
                    }
                    case VARCHAR -> {
                        String strVal = (String) value;
                        if (minValue == null || strVal.compareTo((String) minValue) < 0) {
                            minValue = strVal;
                        }
                        if (maxValue == null || strVal.compareTo((String) maxValue) > 0) {
                            maxValue = strVal;
                        }
                    }
                }
            }

            ColumnStats stats = new ColumnStats.Builder(column.name())
                    .setNumDistinctValues(distinctValues.size())
                    .setMinValue(minValue)
                    .setMaxValue(maxValue)
                    .setNumNulls(nullCount)
                    .build();

            table.addColumnStats(stats);
        }

        // Build histograms for numeric columns
        HistogramBuilder.buildHistograms(table);
    }

    /**
     * Returns the columnar (column-major) representation of the named table,
     * building and caching it on first access.
     *
     * <p>The columnar view is derived lazily from the existing row-oriented
     * {@link TableMetadata} via {@link ColumnarTable#fromTableMetadata}. The
     * result is cached so subsequent calls are O(1). The underlying
     * {@code TableMetadata} is not modified.
     *
     * <p>This is the entry point for the vectorized execution path:
     * {@code VectorizedScan} calls this method during {@code open()} to obtain
     * the columnar table it will scan.
     *
     * @param tableName the table name (case-insensitive)
     * @return the cached or newly created {@link ColumnarTable}
     * @throws IllegalArgumentException if the table does not exist in the catalog
     */
    public ColumnarTable getColumnarTable(String tableName) {
        String key = tableName.toLowerCase();
        return columnarTables.computeIfAbsent(key, k -> {
            TableMetadata meta = getTableMetadata(k);   // throws if not found
            return ColumnarTable.fromTableMetadata(meta);
        });
    }

    /**
     * Print catalog contents for debugging.
     */
    public void printCatalog() {
        System.out.println("=== Catalog Contents ===");
        for (String tableName : tables.keySet()) {
            TableMetadata table = tables.get(tableName);
            System.out.println(table);
            System.out.println("  Statistics:");
            for (Schema.Column col : table.getSchema().getColumns()) {
                ColumnStats stats = table.getColumnStats(col.name());
                if (stats != null) {
                    System.out.println("    " + stats);
                }
                Histogram<?> histogram = table.getHistogram(col.name());
                if (histogram != null) {
                    System.out.println("    " + histogram.getSummary());
                }
            }
            System.out.println();
        }
    }
}
