package org.query.optimizer;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.*;

import java.io.FileNotFoundException;
import java.io.PrintWriter;

/**
 * Demonstration of parsing and logical plans functionality.
 * <p>
 * Shows:
 * <li>1. Parsing SQL queries into AST</li>
 * <li>2. Converting AST to logical plans</li>
 * <li>3. Canonical form enforcement (split AND predicates)</li>
 * <li>4. Pretty-printing logical plans</li>
 * </p>
 */
public class ParserAndLogicalPlansDemo {
    static void main() {
        try {
            System.out.println("--- Query Optimizer - Parse and Logical Plans Demo ---\n");

            // setup: load sample data
            System.out.println("Setting up sample data ...");
            createSampleData();
            Catalog catalog = new Catalog();
            catalog.loadTableFromCSV("customers", "customers.csv");
            catalog.loadTableFromCSV("products", "products.csv");
            catalog.loadTableFromCSV("orders", "orders.csv");
            System.out.println("Loaded 3 tables\n");

            // create parser and plan builder
            SQLParser parser = new SQLParser();
            LogicalPlanBuilder planBuilder = new LogicalPlanBuilder(catalog);

            // Demo query 1: simple filter and projection
            System.out.println("=== Query 1: Simple Filter and Projection ===");
            String sql1 = "SELECT name, price FROM products WHERE category = 'Electronics'";
            demonstrateQuery(sql1, parser, planBuilder);

            // Demo query 2: multiple filters, tests canonical form
            System.out.println("\n=== Query 2: Multiple Filters (Canonical Form) ===");
            String sql2 = "SELECT name, age FROM customers WHERE city = 'Seattle' AND age > 30";
            demonstrateQuery(sql2, parser, planBuilder);

            // Demo query 3: simple join
            System.out.println("n=== Query 4: Multi-Way Join ===");
            String sql3 = "SELECT c.name, o.total FROM customers c " +
                    "INNER JOIN orders o ON c.id = o.customer_id " +
                    "WHERE c.city = 'Seattle' AND o.total > 100";
            demonstrateQuery(sql3, parser, planBuilder);

            // Demo query 4: multi-way join
            System.out.println("\n=== Query 4: Multi-Way Join ===");
            String sql4 = "SELECT p.name, o.total, c.name " +
                    "FROM products p " +
                    "INNER JOIN orders o ON p.id = o.product_id " +
                    "INNER JOIN customers c ON o.customer_id = c.id " +
                    "WHERE p.category = 'Electronics'";
            demonstrateQuery(sql4, parser, planBuilder);

            // Demo query 5: aggregation
            System.out.println("\n=== Query 5: Aggregation with GROUP BY ===");
            String sql5 = "SELECT category, COUNT(*), AVG(price) FROM products GROUP BY category";
            demonstrateQuery(sql5, parser, planBuilder);

            System.out.println("\n=== Milestone 2 Complete ===");
            System.out.println("SQL parsing");
            System.out.println("AST generation");
            System.out.println("Logical plan conversion");
            System.out.println("Canonical form (split ANDs)");
            System.out.println("Support for: Scan, Filter, Project, Join, Aggregate");
        } catch (Exception e) {
            System.err.println("Encountered error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void demonstrateQuery(String sql, SQLParser parser, LogicalPlanBuilder planBuilder) {
        System.out.println("SQL: " + sql + "\n");
        System.out.println("Step 1: parse to AST");
        AST.SelectStmt ast = parser.parse(sql);
        System.out.println(" " + ast);
        System.out.println("\nStep 2: Convert to Logical Plan");
        LogicalNode plan = planBuilder.build(ast);
        System.out.println(plan.toPrettyString());
        System.out.println("\nStep3: Analysis");
        analyzePlan(plan);
    }

    private static void analyzePlan(LogicalNode plan) {
        int scanCount = countNodes(plan, LogicalScan.class);
        int filterCount = countNodes(plan, LogicalFilter.class);
        int projectCount = countNodes(plan, LogicalProject.class);
        int joinCount = countNodes(plan, LogicalJoin.class);
        int aggCount = countNodes(plan, LogicalAggregate.class);

        System.out.println("  - Operators: " +
                scanCount + " Scan, " +
                filterCount + " Filter, " +
                projectCount + " Project, " +
                joinCount + " Join, " +
                aggCount + " Aggregate");
        if (filterCount > 1)
            System.out.println("  - NOTE: Multiple Filter nodes demonstrate canonical form (split ANDs)");
    }

    private static int countNodes(LogicalNode node, Class<?> type) {
        int count = type.isInstance(node) ? 1 : 0;
        for (LogicalNode child : node.getChildren()) {
            count += countNodes(child, type);
        }

        return count;
    }

    private static void createSampleData() throws FileNotFoundException {
        // Customers
        try (PrintWriter pw = new PrintWriter("customers.csv")) {
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

        // Products
        try (PrintWriter pw = new PrintWriter("products.csv")) {
            pw.println("id:INTEGER,name:VARCHAR,category:VARCHAR,price:FLOAT");
            pw.println("1,Laptop,Electronics,999.99");
            pw.println("2,Mouse,Electronics,29.99");
            pw.println("3,Desk,Furniture,299.99");
            pw.println("4,Chair,Furniture,199.99");
            pw.println("5,Monitor,Electronics,399.99");
            pw.println("6,Keyboard,Electronics,79.99");
            pw.println("7,Lamp,Furniture,49.99");
        }

        // Orders
        try (PrintWriter pw = new PrintWriter("orders.csv")) {
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
}
