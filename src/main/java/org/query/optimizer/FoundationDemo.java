package org.query.optimizer;


import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.ColumnStats;
import org.query.optimizer.catalog.TableMetadata;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;

/**
 * Demonstration of Milestone 1 functionality.
 * <p>
 * This shows:
 * 1. Creating sample CSV files
 * 2. Loading tables into the catalog
 * 3. Querying table metadata and statistics
 * 4. Creating a simple hardcoded logical plan (before we have a parser)
 * 5. Pretty-printing the plan with annotations
 */
public class FoundationDemo {

    public static void main(String[] args) {
        try {
            System.out.println("=== Query Optimizer - Milestone 1 Demo ===\n");

            // Step 1: Create sample CSV files
            System.out.println("Step 1: Creating sample data files...");
            createSampleData();
            System.out.println("Created: customers.csv, products.csv, orders.csv\n");

            // Step 2: Initialize catalog and load tables
            System.out.println("Step 2: Loading tables into catalog...");
            Catalog catalog = new Catalog();

            TableMetadata customers = catalog.loadTableFromCSV("customers", "target/generated-resources/customers.csv");
            TableMetadata products = catalog.loadTableFromCSV("products", "target/generated-resources/products.csv");
            TableMetadata orders = catalog.loadTableFromCSV("orders", "target/generated-resources/orders.csv");

            System.out.println("Loaded 3 tables\n");

            // Step 3: Display catalog contents
            System.out.println("Step 3: Catalog contents with statistics:");
            catalog.printCatalog();

            // Step 4: Query some metadata
            System.out.println("Step 4: Querying metadata...");
            System.out.println("Customers table has " + customers.getRowCount() + " rows");
            System.out.println("Customers schema: " + customers.getSchema());

            ColumnStats cityStats = customers.getColumnStats("city");
            System.out.println("City column stats: " + cityStats);
            System.out.println();

            // Step 5: Create a hardcoded logical plan
            System.out.println("Step 5: Creating a hardcoded logical plan...");
            System.out.println("Query (conceptual): SELECT name, price FROM products WHERE category = 'Electronics'\n");

            LogicalNode plan = createSamplePlan();

            // Step 6: Add some sample annotations
            System.out.println("Step 6: Adding optimizer annotations...");
            annotatePlan(plan, products);

            // Step 7: Pretty print the plan
            System.out.println("Step 7: Logical plan with annotations:");
            System.out.println(plan.toPrettyString());

            // Step 8: Show interface usage
            System.out.println("Step 8: Demonstrating core interfaces...");
            demonstrateInterfaces();

            System.out.println("\n=== Milestone 1 Complete ===");
            System.out.println("Catalog with statistics");
            System.out.println("CSV loading");
            System.out.println("Core interfaces (LogicalNode, PhysicalNode, Rule, CostModel, Iterator)");
            System.out.println("Expression trees");
            System.out.println("Plan annotations and pretty printing");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Create sample CSV files for testing.
     */
    private static void createSampleData() throws IOException {
        File outputDir = new File("target/generated-resources");
        outputDir.mkdirs();

        // Customers table
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

        // Products table
        try (PrintWriter pw = new PrintWriter(new File(outputDir, "products.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,category:VARCHAR,price:FLOAT");
            pw.println("1,Laptop,Electronics,999.99");
            pw.println("2,Mouse,Electronics,29.99");
            pw.println("3,Desk,Furniture,299.99");
            pw.println("4,Chair,Furniture,199.99");
            pw.println("5,Monitor,Electronics,399.99");
            pw.println("6,Keyboard,Electronics,79.99");
            pw.println("7,Lamp,Furniture,49.99");
        }

        // Orders table
        try (PrintWriter pw = new PrintWriter(new File(outputDir, "orders.csv"))) {
            pw.println("id:INTEGER,customer_id:INTEGER,product_id:INTEGER,quantity:INTEGER,total:FLOAT");
            pw.println("1,1,1,1,999.99");
            pw.println("2,1,2,2,59.98");
            pw.println("3,2,3,1,299.99");
            pw.println("4,3,5,2,799.98");
            pw.println("5,4,4,1,199.99");
            pw.println("6,5,6,1,79.99");
            pw.println("7,1,7,3,149.97");
            pw.println("8,2,1,1,999.99");
            pw.println("9,3,2,4,119.96");
            pw.println("10,4,5,1,399.99");
        }
    }

    /**
     * Create a simple hardcoded logical plan.
     * Represents: SELECT name, price FROM products WHERE category = 'Electronics'
     */
    private static LogicalNode createSamplePlan() {
        // For now, we'll create a simple stub that just demonstrates the structure
        // We'll implement actual logical operators in Milestone 2
        return new SimpleLogicalScan("products");
    }

    /**
     * Add sample annotations to demonstrate the annotation system.
     */
    private static void annotatePlan(LogicalNode plan, TableMetadata table) {
        // Simulate what the optimizer would do
        long estimatedRows = table.getRowCount() / 3; // Assume 1/3 selectivity
        double estimatedCost = estimatedRows * 0.01; // Simple cost formula

        plan.setEstimatedRows(estimatedRows);
        plan.setEstimatedCost(estimatedCost);
        plan.setAnnotation("selectivity", 0.33);
    }

    /**
     * Demonstrate the core interfaces.
     */
    private static void demonstrateInterfaces() {
        System.out.println("\nCore interfaces defined:");
        System.out.println("  - LogicalNode: Base for logical operators");
        System.out.println("  - PhysicalNode: Base for physical operators");
        System.out.println("  - Rule: Pattern matching and transformation");
        System.out.println("  - CostModel: Cost and cardinality estimation");
        System.out.println("  - Iterator: Volcano execution model");
        System.out.println("  - Expression: Predicate and projection expressions");

        // Show Expression example
        System.out.println("\nExpression example:");
        Expression expr = new Expression.BinaryOp(
                Expression.BinaryOp.Operator.EQ,
                new Expression.ColumnRef("products", "category"),
                new Expression.Literal("Electronics")
        );
        System.out.println("  Expression: " + expr.toSQLString());
    }

    /**
     * Simple stub for logical scan - will be properly implemented in Milestone 2.
     */
    private static class SimpleLogicalScan extends LogicalNode {
        private final String tableName;

        public SimpleLogicalScan(String tableName) {
            this.tableName = tableName;
        }

        @Override
        public List<LogicalNode> getChildren() {
            return Collections.emptyList();
        }

        @Override
        public LogicalNode withChildren(List<LogicalNode> children) {
            if (!children.isEmpty()) {
                throw new IllegalArgumentException("Scan has no children");
            }
            return this;
        }

        @Override
        public String describe() {
            return "Scan[" + tableName + "]";
        }
    }
}