package org.query.optimizer.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

/**
 * Central catalog managing all table metadata and statistics.
 * This is the single source of truth for schema information.
 */
public class Catalog {
    private final Map<String, TableMetadata> tables;


    public Catalog() {
        this.tables = new HashMap<>();
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

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(csvPath);
             BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = br.readLine()) != null) {
                rawData.add(line.split(","));
            }
        }

        if (rawData.isEmpty()) {
            throw new IllegalArgumentException("CSV file is empty: " + csvPath);
        }

        // Parse header to build schema
        String[] header = rawData.get(0);
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
        List<Object[]> data = new ArrayList<>();
        for (int i = 1; i < rawData.size(); i++) {
            String[] row = rawData.get(i);
            if (row.length != columns.size()) {
                throw new IllegalArgumentException(
                        "Row " + i + " has wrong number of columns: expected " +
                                columns.size() + ", got " + row.length);
            }
            Object[] parsedRow = new Object[columns.size()];
            for (int j = 0; j < columns.size(); j++) {
                parsedRow[j] = columns.get(j).type().parse(row[j]);
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
     */
    private void collectStatistics(TableMetadata table) {
        Schema schema = table.getSchema();
        List<Object[]> data = table.getData();

        for (int colIdx = 0; colIdx < schema.columnCount(); colIdx++) {
            Schema.Column column = schema.getColumn(colIdx);

            Set<Object> distinctValues = new HashSet<>();
            Object minValue = null, maxValue = null;
            long nullCount = 0;

            for (Object[] row : data) {
                Object value = row[colIdx];
                if (value == null) {
                    nullCount++;
                    continue;
                }
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
            }
            System.out.println();
        }
    }
}
