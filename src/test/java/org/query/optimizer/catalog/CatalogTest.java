package org.query.optimizer.catalog;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class CatalogTest {
    @Test
    public void testBasicCatalogCreation() throws IOException {
        Catalog catalog = new Catalog();
        TableMetadata customers = catalog.loadTableFromCSV("customers", "customers.csv");

        long rowCount = customers.getRowCount();
        Schema schema = customers.getSchema();
        ColumnStats stats = customers.getColumnStats("city");
        Object[] row = customers.getRow(0);
        String name = (String) customers.getValue(0, "name");
        assertTrue(name.equals("Alice"));
        int age = (Integer) customers.getValue(0, "age");
        assertTrue(age == 30);
    }
}
