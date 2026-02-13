package org.query.optimizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.Tuple;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.physical.*;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Milestone 4: Physical Execution
 */
public class PhysicalExecutionTest {
    static Catalog catalog = new Catalog();

    @BeforeAll
    static void setup() throws IOException {
        Path outputDir = Paths.get("target/generated-test-resources");
        if (!Files.exists(outputDir))
            Files.createDirectory(outputDir);

        try (PrintWriter pw = new PrintWriter(new File(outputDir.toFile(), "test_customers.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,age:INTEGER");
            pw.println("1,Alice,30");
            pw.println("2,Bob,25");
            pw.println("3,Charlie,35");
        }
        catalog.loadTableFromCSV("customers",
                "target/generated-test-resources/test_customers.csv");

        try (PrintWriter pw = new PrintWriter(new File(outputDir.toFile(), "test_orders.csv"))) {
            pw.println("id:INTEGER,customer_id:INTEGER");
            pw.println("1,1");
            pw.println("2,1");
            pw.println("3,3");
        }
        catalog.loadTableFromCSV("orders",
                "target/generated-test-resources/test_orders.csv");
    }

    @Test
    public void testScanExecution() {
        PhysicalScan scan = new PhysicalScan("customers", catalog);
        Executor executor = new Executor();

        Executor.ExecutionResult result = executor.execute(scan);

        assertEquals(3, result.getResultCount(), "Expected 3 rows, got " + result.getResultCount());
        assertEquals(3, result.tuplesProcessed(), "Expected 3 tuples processed");

        var schema = catalog.getTableMetadata("customers").getSchema();
        // Check first row
        Tuple firstRow = result.tuples().getFirst();
        assertEquals(1, firstRow.find(schema.getColumn("id")),
                "First row id should be 1");
        assertEquals("Alice", firstRow.find(schema.getColumn("name")),
                "First row name should be Alice");
    }

    @Test
    public void testFilterExecution() {
        PhysicalScan scan = new PhysicalScan("customers", catalog);

        Expression predicate = new Expression.BinaryOp(
                Expression.BinaryOp.Operator.GT,
                new Expression.ColumnRef("customers", "age"),
                new Expression.Literal<>(25)
        );

        Schema schema = catalog.getTableMetadata("customers").getSchema();
        PhysicalFilter filter = new PhysicalFilter(predicate, scan, schema);

        Executor executor = new Executor();
        Executor.ExecutionResult result = executor.execute(filter);

        // Alice (30) and Charlie (35) should pass
        assertEquals(2, result.getResultCount(), "Expected 2 rows after filter, got " + result.getResultCount());
    }

    @Test
    public void testProjectExecution() {
        PhysicalScan scan = new PhysicalScan("customers", catalog);
        Schema schema = catalog.getTableMetadata("customers").getSchema();

        // Project only name column
        List<Expression> projections = List.of(
                new Expression.ColumnRef("customers", "name")
        );
        List<String> columnNames = List.of("name");

        PhysicalProject project = new PhysicalProject(
                projections, columnNames, scan, schema
        );

        Executor executor = new Executor();
        Executor.ExecutionResult result = executor.execute(project);

        assertEquals(3, result.getResultCount(), "Should have 3 rows");

        // Check that output has only 1 column
        Tuple firstRow = result.tuples().getFirst();
        assertEquals(1, firstRow.size(), "Should have 1 column, got " + firstRow.size());
        assertEquals("Alice", firstRow.find(schema.getColumn("name")),
                "First row should be Alice");
    }

    @Test
    public void testNestedLoopJoin() {
        PhysicalScan leftScan = new PhysicalScan("customers", catalog);
        PhysicalScan rightScan = new PhysicalScan("orders", catalog);

        Expression joinCondition = new Expression.BinaryOp(
                Expression.BinaryOp.Operator.EQ,
                new Expression.ColumnRef("customers", "id"),
                new Expression.ColumnRef("orders", "customer_id")
        );

        Schema leftSchema = catalog.getTableMetadata("customers").getSchema();
        Schema rightSchema = catalog.getTableMetadata("orders").getSchema();

        PhysicalNestedLoopJoin join = new PhysicalNestedLoopJoin(
                leftScan, rightScan, joinCondition, leftSchema, rightSchema
        );

        Executor executor = new Executor();
        Executor.ExecutionResult result = executor.execute(join);

        // Should have some join results
        assertTrue(result.getResultCount() > 0, "Join should produce results");

        // Each result should have columns from both sides
        Tuple firstRow = result.tuples().getFirst();
        assertEquals(5, firstRow.size(), "Join should have 5 columns (3 from customers + 2 from orders)");
    }

    @Test
    public void testHashJoin() {
        PhysicalScan leftScan = new PhysicalScan("customers", catalog);
        PhysicalScan rightScan = new PhysicalScan("orders", catalog);

        Expression joinCondition = new Expression.BinaryOp(
                Expression.BinaryOp.Operator.EQ,
                new Expression.ColumnRef("customers", "id"),
                new Expression.ColumnRef("orders", "customer_id")
        );

        Schema leftSchema = catalog.getTableMetadata("customers").getSchema();
        Schema rightSchema = catalog.getTableMetadata("orders").getSchema();

        PhysicalHashJoin hashJoin = new PhysicalHashJoin(
                leftScan, rightScan, joinCondition, leftSchema, rightSchema
        );

        Executor executor = new Executor();
        Executor.ExecutionResult result = executor.execute(hashJoin);

        // Should produce same results as nested loop join
        assertTrue(result.getResultCount() > 0, "Hash join should produce results");

        // Verify column count
        Tuple firstRow = result.tuples().getFirst();
        assertEquals(5, firstRow.size(), "Hash join should have 5 columns");
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.delete(Paths.get("target/generated-test-resources/test_customers.csv"));
        Files.delete(Paths.get("target/generated-test-resources/test_orders.csv"));
    }
}