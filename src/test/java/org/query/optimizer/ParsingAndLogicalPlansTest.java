package org.query.optimizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.*;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Milestone 2 deliverables.
 * <p>
 * Test criteria:
 * 1. Parse "SELECT * FROM orders WHERE price > 100" successfully
 * 2. AST to logical plan conversion works
 * 3. Canonical form: AND predicates split into separate Filter nodes
 * 4. All operator types can be created
 * </p>
 */
public class ParsingAndLogicalPlansTest {
    static Catalog catalog = new Catalog();

    @BeforeAll
    static void setup() throws IOException {
        Path outputDir = Paths.get("target/generated-test-resources");
        if (!Files.exists(outputDir))
            Files.createDirectory(outputDir);

        // Create minimal test data
        try (PrintWriter pw = new PrintWriter(
                new File(outputDir.toFile(), "test_products.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,category:VARCHAR,price:FLOAT");
            pw.println("1,Laptop,Electronics,999.99");
            pw.println("2,Mouse,Electronics,29.99");
            pw.println("3,Desk,Furniture,299.99");
        }
        catalog.loadTableFromCSV("products",
                "target/generated-test-resources/test_products.csv");

        try (PrintWriter pw = new PrintWriter(
                new File(outputDir.toFile(), "test_customers.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,city:VARCHAR,age:INTEGER");
            pw.println("1,Alice,Seattle,30");
            pw.println("2,Bob,Portland,25");
            pw.println("3,Charlie,Seattle,35");
        }
        catalog.loadTableFromCSV("customers",
                "target/generated-test-resources/test_customers.csv");

        try (PrintWriter pw = new PrintWriter(
                new File(outputDir.toFile(), "test_orders.csv"))) {
            pw.println("id:INTEGER,customer_id:INTEGER,product_id:INTEGER,total:FLOAT");
            pw.println("1,1,1,999.99");
            pw.println("2,2,2,29.99");
            pw.println("3,3,3,299.99");
        }
        catalog.loadTableFromCSV("orders",
                "target/generated-test-resources/test_orders.csv");
    }

    @Test
    public void testBasicParsing() {
        SQLParser parser = new SQLParser();
        String sql = "SELECT name, price FROM products WHERE price > 100";
        AST.SelectStmt ast = parser.parse(sql);

        assertEquals(2, ast.selectItems().size());
        assertInstanceOf(AST.ColumnSelectItem.class, ast.selectItems().getFirst());

        assertInstanceOf(AST.TableRef.class, ast.from());
        AST.TableRef table = (AST.TableRef) ast.from();
        assertEquals("products", table.tableName());

        assertTrue(ast.hasWhere());
        assertInstanceOf(AST.BinaryExpr.class, ast.whereClause());
    }

    @Test
    public void testLogicalPlanGeneration() {
        SQLParser parser = new SQLParser();
        LogicalPlanBuilder builder = new LogicalPlanBuilder(catalog);

        String sql = "SELECT name FROM products WHERE price > 100";
        AST.SelectStmt ast = parser.parse(sql);
        LogicalNode plan = builder.build(ast);

        assertInstanceOf(LogicalProject.class, plan);
        LogicalProject project = (LogicalProject) plan;
        assertInstanceOf(LogicalFilter.class, project.getChild());

        LogicalFilter filter = (LogicalFilter) project.getChild();
        assertInstanceOf(LogicalScan.class, filter.getChild());

        LogicalScan scan = (LogicalScan) filter.getChild();
        assertEquals("products", scan.getTableName());
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.delete(Paths.get("target/generated-test-resources/test_products.csv"));
        Files.delete(Paths.get("target/generated-test-resources/test_customers.csv"));
        Files.delete(Paths.get("target/generated-test-resources/test_orders.csv"));
    }
}
