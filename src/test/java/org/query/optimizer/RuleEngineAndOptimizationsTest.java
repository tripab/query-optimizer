package org.query.optimizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.CostModel;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.*;
import org.query.optimizer.rules.FilterMerge;
import org.query.optimizer.rules.PredicatePushdown;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RuleEngineAndOptimizationsTest {
    static Catalog catalog = new Catalog();

    @BeforeAll
    static void setup() throws IOException {
        Path outputDir = Paths.get("target/generated-test-resources");
        if (!Files.exists(outputDir))
            Files.createDirectory(outputDir);

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
    public void testPredicatePushdown() {
        // create a plan: Filter -> Join -> Scan
        LogicalNode leftScan = new LogicalScan("customers");
        LogicalNode rightScan = new LogicalScan("orders");
        Expression joinCondition = new Expression.BinaryOp(
                Expression.BinaryOp.Operator.EQ,
                new Expression.ColumnRef("customers", "id"),
                new Expression.ColumnRef("orders", "customer_id")
        );
        LogicalJoin join = new LogicalJoin(leftScan, rightScan, LogicalJoin.JoinType.INNER,
                joinCondition);
        Expression filterPredicate = new Expression.BinaryOp(
                Expression.BinaryOp.Operator.EQ,
                new Expression.ColumnRef("customers", "city"),
                new Expression.Literal("Seattle")
        );
        LogicalFilter filter = new LogicalFilter(filterPredicate, join);
        // apply predicate pushdown
        PredicatePushdown rule = new PredicatePushdown();
        assertTrue(rule.matches(filter), "Rule should match Filter->Join pattern");
        LogicalNode transformed = rule.apply(filter);
        assertNotNull(transformed, "Rule should produce transformation");
        assertInstanceOf(LogicalJoin.class, transformed, "Result should still be a join");
        // check that filter was pushed to left child
        LogicalJoin newJoin = (LogicalJoin) transformed;
        assertInstanceOf(LogicalFilter.class, newJoin.getLeft(),
                "Filter should be pushed to left");
    }

    @Test
    public void testCostEstimation() {
        CostModel costModel = new SimpleCostModel(catalog);
        // test scan cost
        LogicalScan scan = new LogicalScan("products");
        double scanCost = costModel.estimate(scan);
        assertTrue(scanCost > 0, "Cost should be positive");
        // test filter cost
        LogicalFilter filter = new LogicalFilter(
                new Expression.BinaryOp(
                        Expression.BinaryOp.Operator.GT,
                        Expression.ColumnRef.from("price"),
                        new Expression.Literal(100f)),
                scan);
        double filterCost = costModel.estimate(filter);
        assertTrue(filterCost > scanCost,
                "Filter cost should be greater than scan cost");
    }

    @Test
    public void testCardinalityEstimation() {
        CostModel costModel = new SimpleCostModel(catalog);
        // test scan cardinality
        LogicalScan scan = new LogicalScan("products");
        long scanCardinality = costModel.estimateCardinality(scan);
        assertEquals(3, scanCardinality, "Scan should return 3 rows (test data)");

        // test filter cardinality
        LogicalFilter filter = new LogicalFilter(
                new Expression.BinaryOp(
                        Expression.BinaryOp.Operator.EQ,
                        Expression.ColumnRef.from("category"),
                        new Expression.Literal("Electronics")
                ),
                scan
        );
        long filterCardinality = costModel.estimateCardinality(filter);
        assertTrue(filterCardinality < scanCardinality,
                "Filter should reduce cardinality");
        assertTrue(filterCardinality > 0,
                "Filter cardinality should produce at least one row");
    }

    @Test
    public void testOptimizationImprovement() {
        var parser = new SQLParser();
        var builder = new LogicalPlanBuilder(catalog);
        var costModel = new SimpleCostModel(catalog);

        var sql = "SELECT c.name FROM customers c " +
                "INNER JOIN orders o ON c.id = o.customer_id " +
                "WHERE c.city = 'Seattle'";
        var ast = parser.parse(sql);
        var initialPlan = builder.build(ast);

        // cost before optimization
        annotatePlanWithCosts(initialPlan, costModel);
        double initialCost = initialPlan.getEstimatedCost();
        // optimize
        List<Rule> rules = List.of(new PredicatePushdown());
        var engine = new RuleEngine(rules);
        var optimizedPlan = engine.optimize(initialPlan);
        // cost after optimization
        annotatePlanWithCosts(optimizedPlan, costModel);
        double optimizedCost = optimizedPlan.getEstimatedCost();

        assertTrue(optimizedCost <= initialCost,
                "Optimized cost (" + optimizedCost +
                        ") should be <= initial cost (" + initialCost + ")");
    }

    @Test
    public void testFixpointIteration() {
        var parser = new SQLParser();
        var builder = new LogicalPlanBuilder(catalog);

        var sql = "SELECT name FROM products WHERE price > 100 AND category = 'Electronics'";
        var ast = parser.parse(sql);
        var initialPlan = builder.build(ast);

        // Create rule engine with filter merge rule
        List<Rule> rules = List.of(new FilterMerge());
        var engine = new RuleEngine(rules, 5);
        var optimizedPlan = engine.optimize(initialPlan);
        int filterCount = countFilters(optimizedPlan);
        assertEquals(1, filterCount, "Should have merged filters into one");
    }

    private int countFilters(LogicalNode node) {
        int count = (node instanceof LogicalFilter) ? 1 : 0;
        for (LogicalNode child : node.getChildren()) {
            count += countFilters(child);
        }

        return count;
    }

    private void annotatePlanWithCosts(LogicalNode node, CostModel costModel) {
        for (LogicalNode child : node.getChildren()) {
            annotatePlanWithCosts(child, costModel);
        }
        long cardinality = costModel.estimateCardinality(node);
        double cost = costModel.estimate(node);
        node.setEstimatedRows(cardinality);
        node.setEstimatedCost(cost);
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.delete(Paths.get("target/generated-test-resources/test_products.csv"));
        Files.delete(Paths.get("target/generated-test-resources/test_customers.csv"));
        Files.delete(Paths.get("target/generated-test-resources/test_orders.csv"));
    }
}
