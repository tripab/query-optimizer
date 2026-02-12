package org.query.optimizer;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.CostModel;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalFilter;
import org.query.optimizer.parser.LogicalJoin;
import org.query.optimizer.parser.LogicalScan;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Demonstration of Cost Calibration.
 * <p>
 * Shows:
 * 1. Running calibration microbenchmarks
 * 2. Comparing default vs calibrated costs
 * 3. Impact on query cost estimates
 * 4. Saving and loading calibration
 * 5. How calibration affects plan selection
 */
public class CostCalibrationDemo {

    static void main() throws IOException {
        System.out.println("=== Cost Calibration Demo ===\n");

        // Setup data
        createSampleData();
        Catalog catalog = new Catalog();
        catalog.loadTableFromCSV("customers", "target/generated-resources/customers.csv");
        catalog.loadTableFromCSV("orders", "target/generated-resources/orders.csv");
        catalog.loadTableFromCSV("products", "target/generated-resources/products.csv");

        // Demo 1: Show system info
        System.out.println("=== Demo 1: System Information ===");
        CostCalibrator.printSystemInfo();

        // Demo 2: Run calibration
        System.out.println("=== Demo 2: Running Calibration ===");
        CostCalibrator calibrator = new CostCalibrator(true);
        CostModel.CostConfig calibratedConfig = calibrator.calibrate();

        // Demo 3: Compare with defaults
        System.out.println("\n=== Demo 3: Default vs Calibrated Costs ===");
        CostModel.CostConfig defaultConfig = new CostModel.CostConfig();
        CostCalibrator.compareConfigs(defaultConfig, "Default",
                calibratedConfig, "Calibrated");

        // Demo 4: Impact on query costs
        System.out.println("\n=== Demo 4: Impact on Query Cost Estimates ===");
        demoQueryImpact(catalog, defaultConfig, calibratedConfig);

        // Demo 5: Save and load
        System.out.println("\n=== Demo 5: Save and Load Calibration ===");
        String filename = "cost_calibration.properties";
        calibrator.saveCalibration(calibratedConfig, filename);

        CostCalibrator loader = new CostCalibrator(true);
        CostModel.CostConfig loadedConfig = loader.loadCalibration(filename);
        System.out.println("\nVerifying loaded config matches:");
        System.out.println("  PAGE_COST matches: " +
                (Math.abs(loadedConfig.PAGE_COST - calibratedConfig.PAGE_COST) < 0.000001));

        // Demo 6: Realistic cost interpretation
        System.out.println("\n=== Demo 6: Realistic Cost Interpretation ===");
        demoRealisticCosts(catalog, calibratedConfig);

        deleteSampleData();
        Files.delete(Paths.get(filename));

        System.out.println("\n=== Cost Calibration Complete ===");
        System.out.println("Key Takeaways:");
        System.out.println("1. Calibrated costs reflect actual hardware performance");
        System.out.println("2. Costs are now in milliseconds (meaningful units)");
        System.out.println("3. Different hardware will have different calibrated costs");
        System.out.println("4. Calibration should be run once per deployment");
    }

    private static void demoQueryImpact(Catalog catalog,
                                        CostModel.CostConfig defaultConfig,
                                        CostModel.CostConfig calibratedConfig) {
        // Simple query: SELECT * FROM customers WHERE age > 30
        LogicalScan scan = new LogicalScan("customers");
        Expression predicate = new Expression.BinaryOp(
                Expression.BinaryOp.Operator.GT,
                new Expression.ColumnRef("customers", "age"),
                new Expression.Literal<>(30)
        );
        LogicalFilter filter = new LogicalFilter(predicate, scan);

        // Estimate with default costs
        CostModel defaultModel = new SimpleCostModel(catalog, defaultConfig);
        annotatePlan(filter, defaultModel);
        double defaultCost = filter.getEstimatedCost();

        // Estimate with calibrated costs
        CostModel calibratedModel = new SimpleCostModel(catalog, calibratedConfig);
        annotatePlan(filter, calibratedModel);
        double calibratedCost = filter.getEstimatedCost();

        System.out.println("Query: SELECT * FROM customers WHERE age > 30");
        System.out.println();
        System.out.println("Cost Estimates:");
        System.out.println("  Default cost:    " + String.format("%.6f", defaultCost) +
                " (arbitrary units)");
        System.out.println("  Calibrated cost: " + String.format("%.6f", calibratedCost) +
                " ms (actual time estimate)");
        System.out.println();
        System.out.println("The calibrated cost gives a realistic time estimate!");
    }

    private static void demoRealisticCosts(Catalog catalog,
                                           CostModel.CostConfig calibratedConfig) {
        CostModel model = new SimpleCostModel(catalog, calibratedConfig);

        // Query 1: Simple scan
        System.out.println("Query 1: SELECT * FROM customers");
        LogicalScan scan1 = new LogicalScan("customers");
        annotatePlan(scan1, model);
        System.out.println("  Estimated time: " +
                String.format("%.3f ms", scan1.getEstimatedCost()));

        // Query 2: Scan with filter
        System.out.println("\nQuery 2: SELECT * FROM customers WHERE age > 30");
        LogicalScan scan2 = new LogicalScan("customers");
        Expression pred2 = new Expression.BinaryOp(
                Expression.BinaryOp.Operator.GT,
                new Expression.ColumnRef("customers", "age"),
                new Expression.Literal<>(30)
        );
        LogicalFilter filter2 = new LogicalFilter(pred2, scan2);
        annotatePlan(filter2, model);
        System.out.println("  Estimated time: " +
                String.format("%.3f ms", filter2.getEstimatedCost()));

        // Query 3: Join
        System.out.println("\nQuery 3: SELECT * FROM customers JOIN orders");
        LogicalScan scanC = new LogicalScan("customers");
        LogicalScan scanO = new LogicalScan("orders");
        Expression joinCond = new Expression.BinaryOp(
                Expression.BinaryOp.Operator.EQ,
                new Expression.ColumnRef("customers", "id"),
                new Expression.ColumnRef("orders", "customer_id")
        );
        LogicalJoin join = new LogicalJoin(scanC, scanO,
                LogicalJoin.JoinType.INNER, joinCond);
        annotatePlan(join, model);
        System.out.println("  Estimated time: " +
                String.format("%.3f ms", join.getEstimatedCost()));

        System.out.println("\nThese time estimates help:");
        System.out.println("  - Set realistic query timeouts");
        System.out.println("  - Identify expensive queries");
        System.out.println("  - Choose between alternative plans");
    }

    private static void annotatePlan(LogicalNode node, CostModel model) {
        for (LogicalNode child : node.getChildren()) {
            annotatePlan(child, model);
        }

        long card = model.estimateCardinality(node);
        double cost = model.estimate(node);
        node.setEstimatedRows(card);
        node.setEstimatedCost(cost);
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
            pw.println("1,1,100.0");
            pw.println("2,1,200.0");
            pw.println("3,2,150.0");
            pw.println("4,3,300.0");
            pw.println("5,4,250.0");
            pw.println("6,5,180.0");
            pw.println("7,1,120.0");
            pw.println("8,2,90.0");
            pw.println("9,3,200.0");
            pw.println("10,4,160.0");
        }

        // Products
        try (PrintWriter pw = new PrintWriter(new File(outputDir, "products.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,price:FLOAT");
            pw.println("1,Laptop,999.99");
            pw.println("2,Mouse,29.99");
            pw.println("3,Keyboard,79.99");
            pw.println("4,Monitor,399.99");
            pw.println("5,Desk,299.99");
        }
    }

    private static void deleteSampleData() throws IOException {
        Files.delete(Paths.get("target/generated-resources/customers.csv"));
        Files.delete(Paths.get("target/generated-resources/products.csv"));
        Files.delete(Paths.get("target/generated-resources/orders.csv"));
    }
}