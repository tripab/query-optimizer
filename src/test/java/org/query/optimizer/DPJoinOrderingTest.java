package org.query.optimizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Dynamic Programming Join Ordering.
 */
public class DPJoinOrderingTest {
    static Catalog catalog = new Catalog();

    @BeforeAll
    static void setup() throws IOException {
        Path outputDir = Paths.get("target/generated-test-resources");
        if (!Files.exists(outputDir))
            Files.createDirectory(outputDir);

        try (PrintWriter pw = new PrintWriter(
                new File(outputDir.toFile(), "test_products.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR");
            pw.println("1,Laptop");
            pw.println("2,Mouse");
            pw.println("3,Desk");
            pw.println("4,Chair");
            pw.println("5,Monitor");
            pw.println("6,Keyboard");
            pw.println("7,Lamp");
        }
        catalog.loadTableFromCSV("products",
                "target/generated-test-resources/test_products.csv");

        try (PrintWriter pw = new PrintWriter(
                new File(outputDir.toFile(), "test_customers.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR");
            pw.println("1,Alice");
            pw.println("2,Bob");
            pw.println("3,Charlie");
        }
        catalog.loadTableFromCSV("customers",
                "target/generated-test-resources/test_customers.csv");

        try (PrintWriter pw = new PrintWriter(
                new File(outputDir.toFile(), "test_orders.csv"))) {
            pw.println("id:INTEGER,customer_id:INTEGER,product_id:INTEGER");
            pw.println("1,1,1");
            pw.println("2,2,2");
            pw.println("3,3,3");
            pw.println("4,1,2");
            pw.println("5,2,1");
        }
        catalog.loadTableFromCSV("orders",
                "target/generated-test-resources/test_orders.csv");
    }

    @Test
    public void testTwoTableJoin() {
        CostModel costModel = new SimpleCostModel(catalog);
        LogicalNode result = getLogicalNode(costModel);

        // Should produce a join
        assertInstanceOf(LogicalJoin.class, result, "Result should be a join");

        // Should have both tables
        LogicalJoin join = (LogicalJoin) result;
        assertInstanceOf(LogicalScan.class, join.getLeft(), "Left should be a scan");
        assertInstanceOf(LogicalScan.class, join.getRight(), "Right should be a scan");
    }

    private static LogicalNode getLogicalNode(CostModel costModel) {
        DPJoinOrderer optimizer = new DPJoinOrderer(costModel);

        List<LogicalScan> scans = Arrays.asList(
                new LogicalScan("customers"),
                new LogicalScan("orders")
        );

        List<DPJoinOrderer.JoinCondition> conditions = List.of(
                new DPJoinOrderer.JoinCondition("customers", "orders",
                        new Expression.BinaryOp(Expression.BinaryOp.Operator.EQ,
                                new Expression.ColumnRef("customers", "id"),
                                new Expression.ColumnRef("orders", "customer_id")))
        );

        return optimizer.findBestJoinOrder(scans, conditions);
    }

    @Test
    public void testThreeTableJoin() {
        CostModel costModel = new SimpleCostModel(catalog);
        DPJoinOrderer optimizer = new DPJoinOrderer(costModel);

        // Create a chain join: A -> B -> C
        List<LogicalScan> scans = Arrays.asList(
                new LogicalScan("customers"),   // Smallest
                new LogicalScan("orders"),      // Medium
                new LogicalScan("products")     // Largest
        );

        List<DPJoinOrderer.JoinCondition> conditions = Arrays.asList(
                new DPJoinOrderer.JoinCondition("customers", "orders",
                        new Expression.BinaryOp(Expression.BinaryOp.Operator.EQ,
                                new Expression.ColumnRef("customers", "id"),
                                new Expression.ColumnRef("orders", "customer_id"))),
                new DPJoinOrderer.JoinCondition("orders", "products",
                        new Expression.BinaryOp(Expression.BinaryOp.Operator.EQ,
                                new Expression.ColumnRef("orders", "product_id"),
                                new Expression.ColumnRef("products", "id")))
        );

        LogicalNode result = optimizer.findBestJoinOrder(scans, conditions);

        // Should produce nested joins
        assertInstanceOf(LogicalJoin.class, result, "Result should be a join");

        LogicalJoin topJoin = (LogicalJoin) result;
        assertTrue(topJoin.getLeft() instanceof LogicalJoin ||
                        topJoin.getRight() instanceof LogicalJoin,
                "Should have nested join");

        // Count scans
        int scanCount = countScans(result);
        assertEquals(3, scanCount, "Should have 3 scans, got " + scanCount);
    }

    @Test
    public void testCostComparison() {
        CostModel costModel = new SimpleCostModel(catalog);
        DPJoinOrderer optimizer = new DPJoinOrderer(costModel);

        List<LogicalScan> scans = Arrays.asList(
                new LogicalScan("customers"),
                new LogicalScan("orders"),
                new LogicalScan("products")
        );

        List<DPJoinOrderer.JoinCondition> conditions = Arrays.asList(
                new DPJoinOrderer.JoinCondition("customers", "orders",
                        new Expression.BinaryOp(Expression.BinaryOp.Operator.EQ,
                                new Expression.ColumnRef("customers", "id"),
                                new Expression.ColumnRef("orders", "customer_id"))),
                new DPJoinOrderer.JoinCondition("orders", "products",
                        new Expression.BinaryOp(Expression.BinaryOp.Operator.EQ,
                                new Expression.ColumnRef("orders", "product_id"),
                                new Expression.ColumnRef("products", "id")))
        );

        // Get DP result
        LogicalNode dpResult = optimizer.findBestJoinOrder(scans, conditions);
        annotatePlan(dpResult, costModel);
        double dpCost = dpResult.getEstimatedCost();

        // Build left-deep heuristic
        LogicalNode heuristic = buildSimpleLeftDeep(scans, conditions);
        annotatePlan(heuristic, costModel);
        double heuristicCost = heuristic.getEstimatedCost();

        // DP should be at least as good
        assertTrue(dpCost <= heuristicCost * 1.01, // Allow 1% tolerance
                "DP cost (" + dpCost + ") should be <= heuristic cost (" + heuristicCost + ")");
    }

    @Test
    public void testMemoization() {
        CostModel costModel = new SimpleCostModel(catalog);
        DPJoinOrderer optimizer = new DPJoinOrderer(costModel);

        List<LogicalScan> scans = Arrays.asList(
                new LogicalScan("customers"),
                new LogicalScan("orders"),
                new LogicalScan("products")
        );

        List<DPJoinOrderer.JoinCondition> conditions = Arrays.asList(
                new DPJoinOrderer.JoinCondition("customers", "orders",
                        new Expression.BinaryOp(Expression.BinaryOp.Operator.EQ,
                                new Expression.ColumnRef("customers", "id"),
                                new Expression.ColumnRef("orders", "customer_id"))),
                new DPJoinOrderer.JoinCondition("orders", "products",
                        new Expression.BinaryOp(Expression.BinaryOp.Operator.EQ,
                                new Expression.ColumnRef("orders", "product_id"),
                                new Expression.ColumnRef("products", "id")))
        );

        optimizer.findBestJoinOrder(scans, conditions);

        // For 3 tables with chain join (A-B-C):
        // Memo contains: {A}, {B}, {C}, {A,B}, {B,C}, {A,B,C}
        // However, here {customers,products} is NOT in memo because there's no direct join condition
        // Total: 6 subsets
        int cacheSize = optimizer.getMemoCacheSize();
        assertEquals(6, cacheSize, "Expected 7 memoized plans, got %d".formatted(cacheSize));
    }

    @Test
    public void testSingleTable() {
        CostModel costModel = new SimpleCostModel(catalog);
        DPJoinOrderer optimizer = new DPJoinOrderer(costModel);

        List<LogicalScan> scans = List.of(
                new LogicalScan("customers")
        );

        List<DPJoinOrderer.JoinCondition> conditions = new ArrayList<>();

        LogicalNode result = optimizer.findBestJoinOrder(scans, conditions);

        // Should just return the scan
        assertInstanceOf(LogicalScan.class, result, "Single table should return scan");
        assertEquals("customers", ((LogicalScan) result).getTableName());
    }

    // Helper methods

    private static int countScans(LogicalNode node) {
        if (node instanceof LogicalScan) return 1;

        int count = 0;
        for (LogicalNode child : node.getChildren()) {
            count += countScans(child);
        }
        return count;
    }

    private static void annotatePlan(LogicalNode node, CostModel costModel) {
        for (LogicalNode child : node.getChildren()) {
            annotatePlan(child, costModel);
        }

        long card = costModel.estimateCardinality(node);
        double cost = costModel.estimate(node);
        node.setEstimatedRows(card);
        node.setEstimatedCost(cost);
    }

    private static LogicalNode buildSimpleLeftDeep(List<LogicalScan> scans,
                                                   List<DPJoinOrderer.JoinCondition> conditions) {
        LogicalNode result = scans.getFirst();

        for (int i = 1; i < scans.size(); i++) {
            DPJoinOrderer.JoinCondition cond = conditions.get(i - 1);
            result = new LogicalJoin(result, scans.get(i),
                    LogicalJoin.JoinType.INNER, cond.condition());
        }

        return result;
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.delete(Paths.get("target/generated-test-resources/test_products.csv"));
        Files.delete(Paths.get("target/generated-test-resources/test_customers.csv"));
        Files.delete(Paths.get("target/generated-test-resources/test_orders.csv"));
    }
}