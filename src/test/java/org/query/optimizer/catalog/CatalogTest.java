package org.query.optimizer.catalog;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class CatalogTest {
    @Test
    public void testBasicCatalogCreation() throws IOException {
        // Customers table
        TableMetadata customers = getTableMetadata();

        long rowCount = customers.getRowCount();
        Schema schema = customers.getSchema();
        ColumnStats stats = customers.getColumnStats("city");
        Map<Schema.Column, Object> row = customers.getRow(0);
        String name = (String) customers.getValue(0, "name");
        assertTrue(name.equals("Alice"));
        int age = (Integer) customers.getValue(0, "age");
        assertTrue(age == 30);
    }

    private static TableMetadata getTableMetadata() throws IOException {
        File outputDir = new File("target/generated-resources");
        outputDir.mkdirs();

        try (PrintWriter pw = new PrintWriter(new File(outputDir, "customers.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,city:VARCHAR,age:INTEGER");
            pw.println("1,Alice,Seattle,30");
            pw.println("2,Bob,Portland,25");
            pw.println("3,Charlie,Seattle,35");
            pw.println("4,Diana,San Francisco,28");
            pw.println("5,Eve,Seattle,32");
            pw.println("6,Frank,Portland,29");
            pw.println("7,Grace,Seattle,31");
            pw.println("8,Henry,San Francisco,27");
        }

        Catalog catalog = new Catalog();
        TableMetadata customers = catalog.loadTableFromCSV("customers", "target/generated-resources/customers.csv");
        return customers;
    }
}
