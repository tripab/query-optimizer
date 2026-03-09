package org.query.optimizer.vectorized;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.DataType;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.TableMetadata;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ColumnarTable}.
 * <p>
 * Coverage:
 * - fromTableMetadata pivot: all three DataTypes preserved correctly
 * - Round-trip: row-major -> columnar -> row-major values match original
 * - Empty table (zero rows) handled without exceptions
 * - Single-row table
 * - Null values pivot correctly (isNull flags set, values accessible)
 * - getColumn(int) and getColumn(String) return consistent results
 * - Metadata: tableName, schema, rowCount
 * - Catalog.getColumnarTable: lazy build, cached on second call (same instance)
 * - Catalog.getColumnarTable: unknown table propagates IllegalArgumentException
 */
class ColumnarTableTest {

    private static final String TABLE_NAME = "products";

    // A 5-row products table: id INTEGER, name VARCHAR, price FLOAT
    private Schema schema;
    private List<Map<Schema.Column, Object>> rows;
    private TableMetadata metadata;

    @BeforeEach
    void buildTableMetadata() {
        schema = new Schema(List.of(
                new Schema.Column("id", DataType.INTEGER),
                new Schema.Column("name", DataType.VARCHAR),
                new Schema.Column("price", DataType.FLOAT)
        ));

        rows = new ArrayList<>();
        rows.add(row(1, "Widget", 9.99f));
        rows.add(row(2, "Gadget", 24.50f));
        rows.add(row(3, "Doohick", 5.00f));
        rows.add(row(4, "Thingam", 14.75f));
        rows.add(row(5, "Gizmo", 49.95f));

        metadata = new TableMetadata(TABLE_NAME, schema, rows);
    }

    // -------------------------------------------------------------------------
    // Pivot correctness
    // -------------------------------------------------------------------------

    @Test
    void fromTableMetadata_rowCount_matchesSource() {
        ColumnarTable ct = ColumnarTable.fromTableMetadata(metadata);
        assertEquals(5, ct.getRowCount());
    }

    @Test
    void fromTableMetadata_integer_columnValuesMatchSource() {
        ColumnarTable ct = ColumnarTable.fromTableMetadata(metadata);
        ColumnVector idCol = ct.getColumn(0);

        assertEquals(DataType.INTEGER, idCol.getType());
        for (int r = 0; r < 5; r++) {
            int expected = (Integer) rows.get(r).get(schema.getColumn(0));
            assertEquals(expected, idCol.getInt(r),
                    "id mismatch at row " + r);
        }
    }

    @Test
    void fromTableMetadata_varchar_columnValuesMatchSource() {
        ColumnarTable ct = ColumnarTable.fromTableMetadata(metadata);
        ColumnVector nameCol = ct.getColumn(1);

        assertEquals(DataType.VARCHAR, nameCol.getType());
        for (int r = 0; r < 5; r++) {
            String expected = (String) rows.get(r).get(schema.getColumn(1));
            assertEquals(expected, nameCol.getString(r),
                    "name mismatch at row " + r);
        }
    }

    @Test
    void fromTableMetadata_float_columnValuesMatchSource() {
        ColumnarTable ct = ColumnarTable.fromTableMetadata(metadata);
        ColumnVector priceCol = ct.getColumn(2);

        assertEquals(DataType.FLOAT, priceCol.getType());
        for (int r = 0; r < 5; r++) {
            float expected = (Float) rows.get(r).get(schema.getColumn(2));
            assertEquals(expected, priceCol.getFloat(r), 0.0001f,
                    "price mismatch at row " + r);
        }
    }

    // -------------------------------------------------------------------------
    // Round-trip: columnar -> row-major should reconstruct original values
    // -------------------------------------------------------------------------

    @Test
    void roundTrip_columnarToRowMajor_matchesOriginal() {
        ColumnarTable ct = ColumnarTable.fromTableMetadata(metadata);
        int colCount = schema.columnCount();

        for (int r = 0; r < ct.getRowCount(); r++) {
            Map<Schema.Column, Object> originalRow = rows.get(r);
            for (int c = 0; c < colCount; c++) {
                Schema.Column col = schema.getColumn(c);
                Object expected = originalRow.get(col);
                Object actual = ct.getColumn(c).get(r);
                assertEquals(expected, actual,
                        "Mismatch at row=" + r + " col=" + col.name());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    void emptyTable_zeroRows_noException() {
        TableMetadata empty = new TableMetadata(TABLE_NAME, schema, List.of());
        ColumnarTable ct = assertDoesNotThrow(
                () -> ColumnarTable.fromTableMetadata(empty));
        assertEquals(0, ct.getRowCount());
    }

    @Test
    void singleRowTable_pivotCorrectly() {
        TableMetadata single = new TableMetadata(TABLE_NAME, schema,
                List.of(row(99, "Lone", 1.0f)));
        ColumnarTable ct = ColumnarTable.fromTableMetadata(single);

        assertEquals(1, ct.getRowCount());
        assertEquals(99, ct.getColumn(0).getInt(0));
        assertEquals("Lone", ct.getColumn(1).getString(0));
        assertEquals(1.0f, ct.getColumn(2).getFloat(0), 0.0001f);
    }

    @Test
    void nullValues_pivotToNullFlags() {
        // Build a row with a null name
        Map<Schema.Column, Object> nullRow = new HashMap<>();
        nullRow.put(schema.getColumn(0), 10);
        nullRow.put(schema.getColumn(1), null);   // null name
        nullRow.put(schema.getColumn(2), 3.14f);

        TableMetadata withNull = new TableMetadata(TABLE_NAME, schema, List.of(nullRow));
        ColumnarTable ct = ColumnarTable.fromTableMetadata(withNull);

        assertFalse(ct.getColumn(0).isNull(0));
        assertTrue(ct.getColumn(1).isNull(0));
        assertFalse(ct.getColumn(2).isNull(0));
        assertEquals(1, ct.getColumn(1).getNullCount());
    }

    // -------------------------------------------------------------------------
    // Column access by name vs index
    // -------------------------------------------------------------------------

    @Test
    void getColumn_byName_matchesGetColumn_byIndex() {
        ColumnarTable ct = ColumnarTable.fromTableMetadata(metadata);
        assertSame(ct.getColumn(0), ct.getColumn("id"));
        assertSame(ct.getColumn(1), ct.getColumn("name"));
        assertSame(ct.getColumn(2), ct.getColumn("price"));
    }

    @Test
    void getColumn_byName_caseInsensitive() {
        ColumnarTable ct = ColumnarTable.fromTableMetadata(metadata);
        assertSame(ct.getColumn("price"), ct.getColumn("PRICE"));
        assertSame(ct.getColumn("price"), ct.getColumn("Price"));
    }

    @Test
    void getColumn_unknownName_throws() {
        ColumnarTable ct = ColumnarTable.fromTableMetadata(metadata);
        assertThrows(IllegalArgumentException.class, () -> ct.getColumn("nonexistent"));
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    @Test
    void tableName_matchesSource() {
        ColumnarTable ct = ColumnarTable.fromTableMetadata(metadata);
        assertEquals(TABLE_NAME, ct.getTableName());
    }

    @Test
    void schema_matchesSource() {
        ColumnarTable ct = ColumnarTable.fromTableMetadata(metadata);
        assertSame(schema, ct.getSchema());
    }

    // -------------------------------------------------------------------------
    // Catalog integration — lazy caching
    // -------------------------------------------------------------------------

    @Test
    void testGetColumnarTableIdempotency() throws IOException {
        Catalog catalog = buildCatalogWithProductsTable();

        ColumnarTable first = catalog.getColumnarTable("products");
        ColumnarTable second = catalog.getColumnarTable("products");

        assertSame(first, second, "Catalog should cache and return the same ColumnarTable instance");
        deleteCatalogWithProductsTable();
    }

    @Test
    void testGetColumnarTableCaseInsensitivity() throws IOException {
        Catalog catalog = buildCatalogWithProductsTable();

        ColumnarTable lower = catalog.getColumnarTable("products");
        ColumnarTable upper = catalog.getColumnarTable("PRODUCTS");

        assertSame(lower, upper);
        deleteCatalogWithProductsTable();
    }

    @Test
    void testGetColumnarTableValuesMatch() throws IOException {
        Catalog catalog = buildCatalogWithProductsTable();
        ColumnarTable ct = catalog.getColumnarTable("products");

        // CSV contains: 1,Widget,9.99
        assertEquals(1, ct.getColumn("id").getInt(0));
        assertEquals("Widget", ct.getColumn("name").getString(0));
        assertEquals(9.99f, ct.getColumn("price").getFloat(0), 0.01f);
        deleteCatalogWithProductsTable();
    }

    @Test
    void testGetColumnarTableOnUnknownTable() {
        Catalog catalog = new Catalog();
        assertThrows(IllegalArgumentException.class,
                () -> catalog.getColumnarTable("ghost_table"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<Schema.Column, Object> row(int id, String name, float price) {
        Map<Schema.Column, Object> m = new HashMap<>();
        m.put(schema.getColumn(0), id);
        m.put(schema.getColumn(1), name);
        m.put(schema.getColumn(2), price);
        return m;
    }

    private Catalog buildCatalogWithProductsTable() throws IOException {
        Path dir = Paths.get("target/generated-test-resources");
        Files.createDirectories(dir);
        File csv = dir.resolve("products.csv").toFile();

        try (PrintWriter pw = new PrintWriter(csv)) {
            pw.println("id:INTEGER,name:VARCHAR,price:FLOAT");
            pw.println("1,Widget,9.99");
            pw.println("2,Gadget,24.50");
            pw.println("3,Doohick,5.00");
        }

        Catalog catalog = new Catalog();
        catalog.loadTableFromCSV("products", csv.getPath());
        return catalog;
    }

    private void deleteCatalogWithProductsTable() throws IOException {
        Files.delete(Paths.get("target/generated-test-resources/products.csv"));
    }
}
