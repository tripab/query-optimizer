package org.query.optimizer;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.CostModel;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalJoin;
import org.query.optimizer.parser.LogicalScan;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Demonstration of Dynamic Programming Join Ordering.
 * <p>
 * Shows:
 * 1. DP algorithm finding optimal join order
 * 2. Comparison with greedy/left-deep heuristic
 * 3. Cost improvements from optimal ordering
 * 4. Scalability analysis (2-5 tables)
 */
public class DPJoinOrderingDemo {
    static void main() throws IOException {
        System.out.println("=== Dynamic Programming Join Ordering Demo ===\n");

        // Setup data
        createSampleData();
        Catalog catalog = new Catalog();
        catalog.loadTableFromCSV("customers", "target/generated-resources/customers.csv");
        catalog.loadTableFromCSV("orders", "target/generated-resources/orders.csv");
        catalog.loadTableFromCSV("products", "target/generated-resources/products.csv");
        catalog.loadTableFromCSV("categories", "target/generated-resources/categories.csv");

        CostModel costModel = new SimpleCostModel(catalog);
        DPJoinOrderer dpOptimizer = new DPJoinOrderer(costModel);

        // Demo 1: 3-table join
        System.out.println("=== Demo 1: Three-Table Join ===");
        demo3TableJoin(catalog, costModel, dpOptimizer);

        // Demo 2: 4-table join
        System.out.println("\n=== Demo 2: Four-Table Join ===");
        demo4TableJoin(catalog, costModel, dpOptimizer);

        // Demo 3: DP statistics
        System.out.println("\n=== Demo 3: DP Algorithm Statistics ===");
        demoStatistics(dpOptimizer);

        // Demo 4: Scalability analysis
        System.out.println("\n=== Demo 4: Scalability Analysis ===");
        demoScalability(catalog, costModel);

        System.out.println("\n=== DP Join Ordering Complete ===");

        deleteSampleData();
    }

    private static void demoScalability(Catalog catalog, CostModel costModel) {
        System.out.println("Testing DP scalability with different table counts:\n");
        for (int n = 2; n <= 6; n++) {
            List<LogicalScan> scans = new ArrayList<>();
            List<DPJoinOrderer.JoinCondition> conditions = new ArrayList<>();
            // create n scans
            String[] tableNames = {"customers", "orders", "products",
                    "categories", "suppliers", "regions"};
            for (int i = 0; i < n && i < tableNames.length; i++) {
                scans.add(new LogicalScan(tableNames[i]));
            }
            // create chain of join conditions
            for (int i = 0; i < n - 1; i++) {
                conditions.add(new DPJoinOrderer.JoinCondition(
                        tableNames[i], tableNames[i + 1],
                        new Expression.BinaryOp(Expression.BinaryOp.Operator.EQ,
                                new Expression.ColumnRef(tableNames[i], "id"),
                                new Expression.ColumnRef(tableNames[i + 1], "fk")
                        )
                ));
            }
            DPJoinOrderer dpOptimizer = new DPJoinOrderer(costModel);
            long start = System.nanoTime();
            try {
                LogicalNode result = dpOptimizer.findBestJoinOrder(scans, conditions);
                long end = System.nanoTime();
                double timeMs = (end - start) / 1_000_000.0;
                System.out.println("  " + n + " tables: " +
                        String.format("%.2f ms", timeMs) +
                        " (" + dpOptimizer.getMemoCacheSize() + " plans explored)");
            } catch (Exception e) {
                System.out.println("  " + n + " tables: FAILED - " + e.getMessage());
            }
        }

        System.out.println("\nTheoretical complexity: O(n * 2^n)");
        System.out.println("  2 tables: 4 subsets");
        System.out.println("  3 tables: 8 subsets");
        System.out.println("  4 tables: 16 subsets");
        System.out.println("  5 tables: 32 subsets");
        System.out.println("  6 tables: 64 subsets");
    }

    private static void demoStatistics(DPJoinOrderer dpOptimizer) {
        dpOptimizer.printMemoStatistics();
    }

    private static void demo4TableJoin(Catalog catalog, CostModel costModel,
                                       DPJoinOrderer dpOptimizer) {
        var scans = Arrays.asList(
                new LogicalScan("customers"),
                new LogicalScan("orders"),
                new LogicalScan("products"),
                new LogicalScan("categories")
        );

        var conditions = Arrays.asList(
                new DPJoinOrderer.JoinCondition("customers", "orders",
                        new Expression.BinaryOp(Expression.BinaryOp.Operator.EQ,
                                new Expression.ColumnRef("customers", "id"),
                                new Expression.ColumnRef("orders", "customer_id"))),
                new DPJoinOrderer.JoinCondition("orders", "products",
                        new Expression.BinaryOp(Expression.BinaryOp.Operator.EQ,
                                new Expression.ColumnRef("orders", "product_id"),
                                new Expression.ColumnRef("products", "id"))),
                new DPJoinOrderer.JoinCondition("products", "categories",
                        new Expression.BinaryOp(Expression.BinaryOp.Operator.EQ,
                                new Expression.ColumnRef("products", "category_id"),
                                new Expression.ColumnRef("categories", "id")))
        );

        System.out.println("Query: 4-way join (customers-orders-products-categories)\n");
        long startTime = System.nanoTime();
        LogicalNode optimized = dpOptimizer.findBestJoinOrder(scans, conditions);
        long endTime = System.nanoTime();
        annotatePlan(optimized, costModel);
        System.out.println("Optimal Join Order:");
        System.out.println(optimized.toPrettyString());

        System.out.println("DP Optimization Time: " +
                String.format("%.2f", (endTime - startTime) / 1_000_000.0) + " ms");
        System.out.println("Total Cost: " + String.format("%.2f", optimized.getEstimatedCost()));
    }

    private static void demo3TableJoin(Catalog catalog, CostModel costModel,
                                       DPJoinOrderer dpOptimizer) {
        // Create scans
        var scans = Arrays.asList(
                new LogicalScan("customers"),   // ~8 rows
                new LogicalScan("orders"),      // ~10 rows
                new LogicalScan("products")     // ~7 rows
        );

        // create join conditions
        var conditions = Arrays.asList(
                new DPJoinOrderer.JoinCondition("customers", "orders",
                        new Expression.BinaryOp(Expression.BinaryOp.Operator.EQ,
                                new Expression.ColumnRef("customers", "id"),
                                new Expression.ColumnRef("orders", "customer_id"))),
                new DPJoinOrderer.JoinCondition("orders", "products",
                        new Expression.BinaryOp(Expression.BinaryOp.Operator.EQ,
                                new Expression.ColumnRef("orders", "product_id"),
                                new Expression.ColumnRef("products", "id"))));

        System.out.println("Query: SELECT * FROM customers, orders, products");
        System.out.println("       WHERE customers.id = orders.customer_id");
        System.out.println("         AND orders.product_id = products.id\n");

        // show table sizes
        scans.forEach(scan ->
                System.out.println("Table " + scan.getTableName() + ": "
                        + costModel.estimateCardinality(scan) + " rows")
        );
        System.out.println();
        // find optimal join order with DP
        long start = System.nanoTime();
        LogicalNode optimizedPlan = dpOptimizer.findBestJoinOrder(scans, conditions);
        long end = System.nanoTime();
        // annotate with costs
        annotatePlan(optimizedPlan, costModel);
        System.out.println("Optimal Join Order (DP):");
        System.out.println(optimizedPlan.toPrettyString());
        System.out.println("DP Optimization Time: " +
                String.format("%.2f", (end - start) / 1_000_000.0) + " ms");
        // compare with left-deep heuristic
        LogicalNode leftDeep = buildLeftDeep(scans, conditions);
        annotatePlan(leftDeep, costModel);
        System.out.println("\nLeft-Deep Heuristic Order:");
        System.out.println(leftDeep.toPrettyString());
        // display improvement
        double dpCost = optimizedPlan.getEstimatedCost();
        double heuristicCost = leftDeep.getEstimatedCost();
        double improvement = (heuristicCost - dpCost) / heuristicCost * 100;
        System.out.println("\nCost Comparison:");
        System.out.println("  DP Optimal:       " + String.format("%.2f", dpCost));
        System.out.println("  Left-Deep:        " + String.format("%.2f", heuristicCost));
        System.out.println("  Improvement:      " + String.format("%.1f%%", improvement));
    }

    private static LogicalNode buildLeftDeep(List<LogicalScan> scans,
                                             List<DPJoinOrderer.JoinCondition> conditions) {
        if (scans.isEmpty()) {
            return null;
        }
        LogicalNode result = scans.getFirst();
        for (int i = 1; i < scans.size(); i++) {
            LogicalScan right = scans.get(i);
            // find applicable condition
            DPJoinOrderer.JoinCondition condition = null;
            for (DPJoinOrderer.JoinCondition joinCondition : conditions) {
                var leftTables = getTableNames(result);
                if ((leftTables.contains(joinCondition.leftTable()) &&
                        joinCondition.rightTable().equals(right.getTableName())) ||
                        (leftTables.contains(joinCondition.rightTable()) &&
                                joinCondition.leftTable().equals(right.getTableName()))
                ) {
                    condition = joinCondition;
                    break;
                }
            }

            if (condition != null) {
                result = new LogicalJoin(result, right,
                        LogicalJoin.JoinType.INNER, condition.condition());
            }
        }

        return result;
    }

    private static Set<String> getTableNames(LogicalNode node) {
        Set<String> tables = new HashSet<>();
        if (node instanceof LogicalScan scan) {
            tables.add(scan.getTableName());
        }
        node.getChildren().stream()
                .flatMap(child -> getTableNames(child).stream())
                .forEach(tables::add);

        return tables;
    }

    private static void annotatePlan(LogicalNode node, CostModel costModel) {
        node.getChildren().forEach(child -> annotatePlan(child, costModel));
        long cardinality = costModel.estimateCardinality(node);
        double cost = costModel.estimate(node);
        node.setEstimatedRows(cardinality);
        node.setEstimatedCost(cost);
    }

    private static void createSampleData() throws IOException {
        File outputDir = new File("target/generated-resources");
        outputDir.mkdirs();

        // Customers - 8 rows
        try (PrintWriter pw = new PrintWriter(new File(outputDir, "customers.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,city:VARCHAR");
            pw.println("1,Alice,Seattle");
            pw.println("2,Bob,Portland");
            pw.println("3,Charlie,Seattle");
            pw.println("4,Diana,SF");
            pw.println("5,Eve,Seattle");
            pw.println("6,Frank,Portland");
            pw.println("7,Grace,Seattle");
            pw.println("8,Henry,SF");
        }

        // Orders - 10 rows
        try (PrintWriter pw = new PrintWriter(new File(outputDir, "orders.csv"))) {
            pw.println("id:INTEGER,customer_id:INTEGER,product_id:INTEGER");
            pw.println("1,1,1");
            pw.println("2,1,2");
            pw.println("3,2,3");
            pw.println("4,3,1");
            pw.println("5,4,2");
            pw.println("6,5,3");
            pw.println("7,1,1");
            pw.println("8,2,2");
            pw.println("9,3,3");
            pw.println("10,4,1");
        }

        // Products - 7 rows
        try (PrintWriter pw = new PrintWriter(new File(outputDir, "products.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,category_id:INTEGER");
            pw.println("1,Laptop,1");
            pw.println("2,Mouse,1");
            pw.println("3,Desk,2");
            pw.println("4,Chair,2");
            pw.println("5,Monitor,1");
            pw.println("6,Keyboard,1");
            pw.println("7,Lamp,2");
        }

        // Categories - 3 rows
        try (PrintWriter pw = new PrintWriter(new File(outputDir, "categories.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR");
            pw.println("1,Electronics");
            pw.println("2,Furniture");
            pw.println("3,Office");
        }
    }

    private static void deleteSampleData() throws IOException {
        Files.delete(Paths.get("target/generated-resources/customers.csv"));
        Files.delete(Paths.get("target/generated-resources/products.csv"));
        Files.delete(Paths.get("target/generated-resources/orders.csv"));
        Files.delete(Paths.get("target/generated-resources/categories.csv"));
    }
}
