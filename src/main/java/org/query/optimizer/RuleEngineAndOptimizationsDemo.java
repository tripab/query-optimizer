package org.query.optimizer;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.CostModel;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.AST;
import org.query.optimizer.parser.LogicalPlanBuilder;
import org.query.optimizer.parser.SQLParser;
import org.query.optimizer.rules.FilterMerge;
import org.query.optimizer.rules.PredicatePushdown;
import org.query.optimizer.rules.ProjectionPushdown;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Demonstration of Milestone 3 functionality.
 * <p>
 * Shows:
 * 1. Rule-based optimization with fixpoint iteration
 * 2. Predicate pushdown in action
 * 3. Projection pushdown
 * 4. Cost model and cardinality estimation
 * 5. Before/after optimization comparison
 */
public class RuleEngineAndOptimizationsDemo {
    static void main() {
        try {
            System.out.println("=== Query Optimizer - Milestone 3 Demo ===\n");

            // Setup: Load sample data
            System.out.println("Setting up sample data...");
            createSampleData();
            Catalog catalog = new Catalog();
            catalog.loadTableFromCSV("customers", "target/generated-resources/customers.csv");
            catalog.loadTableFromCSV("products", "target/generated-resources/products.csv");
            catalog.loadTableFromCSV("orders", "target/generated-resources/orders.csv");
            System.out.println("Loaded 3 tables\n");

            // Create parser and plan builder
            SQLParser parser = new SQLParser();
            LogicalPlanBuilder planBuilder = new LogicalPlanBuilder(catalog);

            // Create cost model
            CostModel costModel = new SimpleCostModel(catalog);

            // Create optimization rules
            List<Rule> rules = Arrays.asList(
                    new PredicatePushdown(),
                    new ProjectionPushdown(),
                    new FilterMerge()
            );

            // Create rule engine
            RuleEngine ruleEngine = new RuleEngine(rules, 5);
            ruleEngine.setVerbose(false); // We'll show plans manually

            // Demo Query 1: Predicate pushdown
            System.out.println("=== Query 1: Predicate Pushdown ===");
            String sql1 = "SELECT c.name, o.total FROM customers c " +
                    "INNER JOIN orders o ON c.id = o.customer_id " +
                    "WHERE c.city = 'Seattle' AND o.total > 100";
            demonstrateOptimization(sql1, parser, planBuilder, ruleEngine, costModel);

            // Demo Query 2: Multiple filters with pushdown
            System.out.println("\n=== Query 2: Multiple Predicate Pushdown ===");
            String sql2 = "SELECT p.name, o.total, c.city " +
                    "FROM products p " +
                    "INNER JOIN orders o ON p.id = o.product_id " +
                    "INNER JOIN customers c ON o.customer_id = c.id " +
                    "WHERE p.category = 'Electronics' AND c.city = 'Seattle'";
            demonstrateOptimization(sql2, parser, planBuilder, ruleEngine, costModel);

            // Demo Query 3: Cost model demonstration
            System.out.println("\n=== Query 3: Cost Model Demonstration ===");
            String sql3 = "SELECT name FROM products WHERE price > 100";
            demonstrateCostModel(sql3, parser, planBuilder, costModel, catalog);

            System.out.println("\n=== Milestone 3 Complete ===");
            System.out.println("Rule engine with fixpoint iteration");
            System.out.println("Predicate pushdown");
            System.out.println("Projection pushdown");
            System.out.println("Filter merge");
            System.out.println("Cost model with configurable parameters");
            System.out.println("Cardinality estimation");

            deleteSampleData();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void demonstrateOptimization(String sql, SQLParser parser,
                                                LogicalPlanBuilder builder,
                                                RuleEngine optimizer,
                                                CostModel costModel) {
        System.out.println("SQL: " + sql);
        System.out.println();

        // Parse and build logical plan
        AST.SelectStmt ast = parser.parse(sql);
        LogicalNode initialPlan = builder.build(ast);

        // Add costs to initial plan
        annotatePlanWithCosts(initialPlan, costModel);

        System.out.println("BEFORE Optimization:");
        System.out.println(initialPlan.toPrettyString());

        double initialCost = initialPlan.getEstimatedCost();
        long initialRows = initialPlan.getEstimatedRows();

        // Optimize
        LogicalNode optimizedPlan = optimizer.optimize(initialPlan);

        // Add costs to optimized plan
        annotatePlanWithCosts(optimizedPlan, costModel);

        System.out.println("AFTER Optimization:");
        System.out.println(optimizedPlan.toPrettyString());

        double finalCost = optimizedPlan.getEstimatedCost();
        long finalRows = optimizedPlan.getEstimatedRows();

        // Show improvement
        System.out.println("Optimization Impact:");
        System.out.println("  Initial cost:    " + String.format("%.2f", initialCost));
        System.out.println("  Optimized cost:  " + String.format("%.2f", finalCost));
        System.out.println("  Improvement:     " + String.format("%.1f%%",
                (initialCost - finalCost) / initialCost * 100));
        System.out.println("  Output rows:     " + finalRows);
    }

    private static void demonstrateCostModel(String sql, SQLParser parser,
                                             LogicalPlanBuilder builder,
                                             CostModel costModel,
                                             Catalog catalog) {
        System.out.println("SQL: " + sql);
        System.out.println();

        AST.SelectStmt ast = parser.parse(sql);
        LogicalNode plan = builder.build(ast);

        System.out.println("Logical Plan:");
        System.out.println(plan.toPrettyString());

        // Show cost breakdown
        System.out.println("Cost Model Configuration:");
        System.out.println("  " + costModel.getConfig());
        System.out.println();

        // Annotate with costs
        annotatePlanWithCosts(plan, costModel);

        System.out.println("Cost Analysis:");
        analyzeCosts(plan, costModel);

        // Show what happens with different cost configurations
        System.out.println("\nCost Sensitivity Analysis:");

        CostModel.CostConfig config1 = new CostModel.CostConfig();
        config1.PAGE_COST = 2.0;
        CostModel model1 = new SimpleCostModel(catalog, config1);
        annotatePlanWithCosts(plan, model1);
        System.out.println("  With PAGE_COST=2.0:  Total cost = " +
                String.format("%.2f", plan.getEstimatedCost()));

        CostModel.CostConfig config2 = new CostModel.CostConfig();
        config2.TUPLE_COST = 0.02;
        CostModel model2 = new SimpleCostModel(catalog, config2);
        annotatePlanWithCosts(plan, model2);
        System.out.println("  With TUPLE_COST=0.02: Total cost = " +
                String.format("%.2f", plan.getEstimatedCost()));
    }

    private static void annotatePlanWithCosts(LogicalNode node, CostModel costModel) {
        // Post-order traversal: children first
        for (LogicalNode child : node.getChildren()) {
            annotatePlanWithCosts(child, costModel);
        }

        // Now annotate this node
        long cardinality = costModel.estimateCardinality(node);
        double cost = costModel.estimate(node);

        node.setEstimatedRows(cardinality);
        node.setEstimatedCost(cost);
    }

    private static void analyzeCosts(LogicalNode node, CostModel costModel) {
        System.out.println("  " + node.describe());
        System.out.println("    Estimated rows: " + node.getEstimatedRows());
        System.out.println("    Estimated cost: " + String.format("%.2f", node.getEstimatedCost()));

        for (LogicalNode child : node.getChildren()) {
            analyzeCosts(child, costModel);
        }
    }

    private static void createSampleData() throws IOException {
        File outputDir = new File("target/generated-resources");
        outputDir.mkdirs();

        // Customers - 8 rows, some from Seattle
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

        // Products - 7 rows, mix of categories
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

        // Orders - 10 rows
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

    private static void deleteSampleData() throws IOException {
        Files.delete(Paths.get("target/generated-resources/customers.csv"));
        Files.delete(Paths.get("target/generated-resources/products.csv"));
        Files.delete(Paths.get("target/generated-resources/orders.csv"));
    }
}
