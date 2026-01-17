package org.query.optimizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.*;
import org.query.optimizer.logical.Expression;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Milestone 1 deliverables.
 * <p>
 * Test criteria:
 * 1. Load 2-3 sample tables from CSV
 * 2. Query metadata successfully
 * 3. Statistics are collected correctly
 * 4. Expressions evaluate correctly
 */
public class FoundationTest {
    private static final Catalog catalog = new Catalog();

    @BeforeAll
    static void setup() throws IOException {
        Path outputDir = Paths.get("target/generated-test-resources");
        if (!Files.exists(outputDir))
            Files.createDirectory(outputDir);

        // Create a simple test CSV
        try (PrintWriter pw = new PrintWriter(new File(outputDir.toFile(),
                "test_table.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,value:FLOAT");
            pw.println("1,Alice,100.5");
            pw.println("2,Bob,200.75");
            pw.println("3,Charlie,150.25");
        }
        // Create CSV with known statistics
        try (PrintWriter pw = new PrintWriter(new File(outputDir.toFile(),
                "stats_test.csv"))) {
            pw.println("id:INTEGER,category:VARCHAR,score:INTEGER");
            pw.println("1,A,100");
            pw.println("2,B,200");
            pw.println("3,A,150");
            pw.println("4,C,175");
            pw.println("5,B,125");
        }
    }

    @Test
    public void testCSVLoading() throws IOException {
        TableMetadata table = catalog.loadTableFromCSV("test_table",
                "target/generated-test-resources/test_table.csv");

        // Verify basic properties
        assertEquals("test_table", table.getTableName());
        assertEquals(3, table.getRowCount());
        assertEquals(3, table.getSchema().columnCount());

        // Verify we can access data
        Object[] row0 = table.getRow(0);
        assertEquals(1, row0[0]);
        assertEquals("Alice", row0[1]);
        assertTrue(Math.abs((Float) row0[2] - 100.5f) < 0.01);
    }

    @Test
    public void testMetadataQueries() {
        // Create test data
        List<Schema.Column> columns = Arrays.asList(
                new Schema.Column("id", DataType.INTEGER),
                new Schema.Column("name", DataType.VARCHAR),
                new Schema.Column("age", DataType.INTEGER)
        );
        Schema schema = new Schema(columns);

        List<Object[]> data = Arrays.asList(
                new Object[]{1, "Alice", 30},
                new Object[]{2, "Bob", 25},
                new Object[]{3, "Charlie", 35}
        );

        TableMetadata table = new TableMetadata("test", schema, data);

        // Test metadata queries
        assertEquals(3, table.getRowCount());
        assertTrue(table.getSchema().hasColumn("name"));
        assertFalse(table.getSchema().hasColumn("nonexistent"));

        Schema.Column nameCol = schema.getColumn("name");
        assertEquals("name", nameCol.name());
        assertSame(DataType.VARCHAR, nameCol.type());

        // Test value access
        assertEquals("Alice", table.getValue(0, "name"));
        assertEquals(25, table.getValue(1, "age"));
    }

    @Test
    public void testStatistics() throws IOException {
        TableMetadata table = catalog.loadTableFromCSV("stats_test",
                "target/generated-test-resources/stats_test.csv");

        // Check ID statistics
        ColumnStats idStats = table.getColumnStats("id");
        assertNotNull(idStats);
        assertEquals(5, idStats.numDistinctValues());
        assertEquals(1, idStats.minValue());
        assertEquals(5, idStats.maxValue());

        // Check category statistics (3 distinct values: A, B, C)
        ColumnStats catStats = table.getColumnStats("category");
        assertEquals(3, catStats.numDistinctValues());

        // Check score statistics
        ColumnStats scoreStats = table.getColumnStats("score");
        assertEquals(100, scoreStats.minValue());
        assertEquals(200, scoreStats.maxValue());
    }

    @Test
    public void testExpressions() {
        // Create a simple schema and row
        List<Schema.Column> columns = Arrays.asList(
                new Schema.Column("id", DataType.INTEGER),
                new Schema.Column("name", DataType.VARCHAR),
                new Schema.Column("age", DataType.INTEGER)
        );
        Schema schema = new Schema(columns);

        Object[] row = new Object[]{1, "Alice", 30};

        // Test column reference
        Expression.ColumnRef idRef = new Expression.ColumnRef("customers", "id");
        assertEquals(1, idRef.evaluate(row, schema));

        // Test literal
        Expression.Literal lit = new Expression.Literal(25);
        assertEquals(25, lit.evaluate(row, schema));

        // Test equality: age = 30
        Expression eq = new Expression.BinaryOp(
                Expression.BinaryOp.Operator.EQ,
                new Expression.ColumnRef("customers", "age"),
                new Expression.Literal(30)
        );
        assertTrue((Boolean) eq.evaluate(row, schema));

        // Test inequality: age > 25
        Expression gt = new Expression.BinaryOp(
                Expression.BinaryOp.Operator.GT,
                new Expression.ColumnRef("customers", "age"),
                new Expression.Literal(25)
        );
        assertTrue((Boolean) gt.evaluate(row, schema));

        // Test string comparison: name = 'Alice'
        Expression nameEq = new Expression.BinaryOp(
                Expression.BinaryOp.Operator.EQ,
                new Expression.ColumnRef("customers", "name"),
                new Expression.Literal("Alice")
        );
        assertTrue((Boolean) nameEq.evaluate(row, schema));

        // Test compound: age > 25 AND name = 'Alice'
        Expression compound = new Expression.BinaryOp(
                Expression.BinaryOp.Operator.AND,
                gt,
                nameEq
        );
        assertTrue((Boolean) compound.evaluate(row, schema));

        // Test SQL string generation
        assertEquals("(customers.age = 30)", eq.toSQLString());
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.delete(Paths.get("target/generated-test-resources/test_table.csv"));
        Files.delete(Paths.get("target/generated-test-resources/stats_test.csv"));
    }
}