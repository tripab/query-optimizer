package org.query.optimizer;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.AST;
import org.query.optimizer.parser.LogicalPlanBuilder;
import org.query.optimizer.parser.SQLParser;
import org.query.optimizer.physical.PhysicalNode;
import org.query.optimizer.physical.PhysicalPlanBuilder;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Demonstration of Milestone 4: Physical Execution
 * <p>
 * Shows complete end-to-end query processing:
 * 1. Parse SQL
 * 2. Build logical plan
 * 3. Optimize logical plan
 * 4. Convert to physical plan
 * 5. Execute and return results
 */
public class PhysicalExecutionDemo {

    static void main() throws IOException {
        System.out.println("=== Milestone 4: Physical Execution Demo ===\n");

        // Setup
        createSampleData();
        Catalog catalog = new Catalog();
        catalog.loadTableFromCSV("customers", "target/generated-resources/customers.csv");
        catalog.loadTableFromCSV("orders", "target/generated-resources/orders.csv");
        catalog.loadTableFromCSV("products", "target/generated-resources/products.csv");

        // Query 1: Simple scan
        System.out.println("=== Query 1: Simple Table Scan ===");
        executeQuery(
                "SELECT name, age FROM customers",
                catalog
        );

        // Query 2: Scan with filter
        System.out.println("\n=== Query 2: Scan with Filter ===");
        executeQuery(
                "SELECT name, age FROM customers WHERE age > 30",
                catalog
        );

        // Query 3: Join query
        System.out.println("\n=== Query 3: Join Query ===");
        executeQuery(
                "SELECT c.name, o.amount FROM customers c " +
                        "INNER JOIN orders o ON c.id = o.customer_id " +
                        "WHERE c.age > 25",
                catalog
        );

        // Query 4: Compare join algorithms
        System.out.println("\n=== Query 4: Join Algorithm Comparison ===");
        compareJoinAlgorithms(catalog);

        // Query 5: Complete pipeline demo
        System.out.println("\n=== Query 5: Complete Optimization Pipeline ===");
        demonstrateCompletePipeline(catalog);

        deleteSampleData();

        System.out.println("\n=== Milestone 4 Complete ===");
        System.out.println("Physical operators (Scan, Filter, Project, Join)");
        System.out.println("Iterator execution model");
        System.out.println("Hash join and nested loop join");
        System.out.println("End-to-end query execution");
        System.out.println("Performance measurement");
    }

    private static void executeQuery(String sql, Catalog catalog) {
        System.out.println("SQL: " + sql);
        System.out.println();

        // Parse
        SQLParser parser = new SQLParser();
        AST.SelectStmt ast = parser.parse(sql);

        // Build logical plan
        PhysicalNode physicalPlan = getPhysicalNode(catalog, ast);

        System.out.println("Physical Plan:");
        System.out.println(physicalPlan.toPrettyString());

        // Execute
        Executor executor = new Executor();

        // Extract column names for display
        List<String> columnNames = extractColumnNames(ast);

        Executor.ExecutionResult result = executor.executeAndPrint(
                physicalPlan, columnNames
        );
    }

    private static PhysicalNode getPhysicalNode(Catalog catalog, AST.SelectStmt ast) {
        QueryOptimizer optimizer = new QueryOptimizer(catalog);
        LogicalNode logicalPlan = new org.query.optimizer.parser.LogicalPlanBuilder(catalog).build(ast);
        return optimizer.optimize(
                ast,
                logicalPlan,
                new OptimizationOptions(
                        true,
                        false,
                        true,
                        JoinOrderPolicy.PRESERVE_INPUT,
                        JoinAlgorithmPolicy.FORCE_HASH
                )
        ).physicalPlan();
    }

    private static void compareJoinAlgorithms(Catalog catalog) {
        String sql = "SELECT c.name, o.amount FROM customers c " +
                "INNER JOIN orders o ON c.id = o.customer_id";

        System.out.println("SQL: " + sql);
        System.out.println();

        // Parse and build logical plan
        SQLParser parser = new SQLParser();
        AST.SelectStmt ast = parser.parse(sql);
        LogicalPlanBuilder logicalBuilder = new LogicalPlanBuilder(catalog);
        LogicalNode logicalPlan = logicalBuilder.build(ast);

        // Test 1: Hash Join
        System.out.println("--- Hash Join ---");
        QueryOptimizer optimizer = new QueryOptimizer(catalog);
        PhysicalNode hashPlan = optimizer.buildPhysicalPlan(
                logicalPlan,
                new OptimizationOptions(
                        false,
                        false,
                        false,
                        JoinOrderPolicy.PRESERVE_INPUT,
                        JoinAlgorithmPolicy.FORCE_HASH
                )
        );

        Executor executor = new Executor();
        Executor.ExecutionResult hashResult = executor.execute(hashPlan);

        System.out.println("Result: " + hashResult.getResultCount() + " rows");
        System.out.println("Time:   " + hashResult.executionTimeMs() + " ms");
        System.out.println();

        // Test 2: Nested Loop Join
        System.out.println("--- Nested Loop Join ---");
        PhysicalNode nlPlan = optimizer.buildPhysicalPlan(
                logicalPlan,
                new OptimizationOptions(
                        false,
                        false,
                        false,
                        JoinOrderPolicy.PRESERVE_INPUT,
                        JoinAlgorithmPolicy.FORCE_NLJ
                )
        );

        Executor.ExecutionResult nlResult = executor.execute(nlPlan);

        System.out.println("Result: " + nlResult.getResultCount() + " rows");
        System.out.println("Time:   " + nlResult.executionTimeMs() + " ms");
        System.out.println();

        // Test 3: Cost-based selection — let the optimizer choose
        System.out.println("--- Cost-Based Selection ---");
        PhysicalNode costPlan = optimizer.buildPhysicalPlan(
                logicalPlan,
                new OptimizationOptions(
                        false,
                        false,
                        false,
                        JoinOrderPolicy.PRESERVE_INPUT,
                        JoinAlgorithmPolicy.COST_BASED
                )
        );
        org.query.optimizer.physical.JoinAlgorithmCounts chosen =
                org.query.optimizer.physical.JoinAlgorithmCounts.of(costPlan);
        System.out.println("Optimizer chose: " + chosen);
        System.out.printf("Estimated physical cost — hash: %.4f, nested-loop: %.4f%n",
                hashPlan.getEstimatedCost(), nlPlan.getEstimatedCost());
        System.out.println();

        // Compare
        System.out.println("--- Comparison ---");
        System.out.println("Hash join:        " + hashResult.executionTimeMs() + " ms");
        System.out.println("Nested loop join: " + nlResult.executionTimeMs() + " ms");

        if (hashResult.executionTimeMs() < nlResult.executionTimeMs()) {
            double speedup = (double) nlResult.executionTimeMs() /
                    hashResult.executionTimeMs();
            System.out.println("Hash join is " + String.format("%.1fx", speedup) + " faster!");
        }
    }

    private static void demonstrateCompletePipeline(Catalog catalog) {
        String sql = "SELECT c.name, o.amount FROM customers c " +
                "INNER JOIN orders o ON c.id = o.customer_id " +
                "WHERE c.age > 30 AND o.amount > 100";

        System.out.println("SQL: " + sql);
        System.out.println();

        // Step 1: Parse
        System.out.println("Step 1: Parsing...");
        SQLParser parser = new SQLParser();
        AST.SelectStmt ast = parser.parse(sql);
        System.out.println("  Parsed");

        QueryOptimizer optimizer = new QueryOptimizer(catalog);
        System.out.println("\nStep 2: Building logical plan...");
        LogicalNode initialPlan = new org.query.optimizer.parser.LogicalPlanBuilder(catalog).build(ast);
        System.out.println("  Initial plan:");
        System.out.println(indent(initialPlan.toPrettyString(), 2));

        // Step 3: Optimize
        System.out.println("Step 3: Optimizing...");
        OptimizationOptions options = new OptimizationOptions(
                true,
                false,
                true,
                JoinOrderPolicy.PRESERVE_INPUT,
                JoinAlgorithmPolicy.FORCE_HASH
        );
        LogicalNode optimizedPlan = optimizer.optimize(ast, initialPlan, options).optimizedLogicalPlan();
        System.out.println("  Optimized plan:");
        System.out.println(indent(optimizedPlan.toPrettyString(), 2));

        // Step 4: Physical plan
        System.out.println("Step 4: Generating physical plan...");
        PhysicalNode physicalPlan = optimizer.buildPhysicalPlan(optimizedPlan, options);
        System.out.println("  Physical plan:");
        System.out.println(indent(physicalPlan.toPrettyString(), 2));

        // Step 5: Execute
        System.out.println("Step 5: Executing...");
        Executor executor = new Executor();
        List<String> columnNames = Arrays.asList("name", "amount");
        Executor.ExecutionResult result = executor.executeAndPrint(
                physicalPlan, columnNames
        );

        System.out.println("\n=== Pipeline Complete ===");
        System.out.println("Parse → Logical Plan → Optimize → Physical Plan → Execute");
        System.out.println("Total execution time: " + result.executionTimeMs() + " ms");
    }

    private static List<String> extractColumnNames(AST.SelectStmt ast) {
        List<String> names = new ArrayList<>();
        for (AST.SelectItem item : ast.selectItems()) {
            names.add(item.getAlias());
        }
        return names;
    }

    private static String indent(String text, int spaces) {
        String prefix = " ".repeat(spaces);
        return prefix + text.replace("\n", "\n" + prefix);
    }

    private static void createSampleData() throws IOException {
        File outputDir = new File("target/generated-resources");
        outputDir.mkdirs();

        // Customers
        try (PrintWriter pw = new PrintWriter(new File(outputDir, "customers.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,age:INTEGER");
            pw.println("1,Alice,30");
            pw.println("2,Bob,25");
            pw.println("3,Charlie,35");
            pw.println("4,Diana,28");
            pw.println("5,Eve,32");
            pw.println("6,Frank,29");
            pw.println("7,Grace,31");
            pw.println("8,Henry,27");
        }

        // Orders
        try (PrintWriter pw = new PrintWriter(new File(outputDir, "orders.csv"))) {
            pw.println("id:INTEGER,customer_id:INTEGER,amount:FLOAT");
            pw.println("1,1,150.0");
            pw.println("2,1,80.0");
            pw.println("3,2,200.0");
            pw.println("4,3,120.0");
            pw.println("5,4,90.0");
            pw.println("6,5,180.0");
            pw.println("7,1,110.0");
            pw.println("8,3,95.0");
            pw.println("9,5,160.0");
            pw.println("10,7,140.0");
        }

        // Products
        try (PrintWriter pw = new PrintWriter(new File(outputDir, "products.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,price:FLOAT");
            pw.println("1,Laptop,999.99");
            pw.println("2,Mouse,29.99");
            pw.println("3,Keyboard,79.99");
        }
    }

    private static void deleteSampleData() throws IOException {
        Files.delete(Paths.get("target/generated-resources/customers.csv"));
        Files.delete(Paths.get("target/generated-resources/products.csv"));
        Files.delete(Paths.get("target/generated-resources/orders.csv"));
    }
}
