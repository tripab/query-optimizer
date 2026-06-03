package org.query.optimizer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalJoin;
import org.query.optimizer.physical.PhysicalHashJoin;
import org.query.optimizer.physical.PhysicalNestedLoopJoin;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QueryOptimizerTest {
    private static Catalog catalog;

    @BeforeAll
    static void setup() throws IOException {
        PhysicalExecutionTest.setup();
        catalog = PhysicalExecutionTest.catalog;

        Path outputDir = Paths.get("target/generated-test-resources");
        if (!Files.exists(outputDir)) {
            Files.createDirectory(outputDir);
        }
        try (PrintWriter pw = new PrintWriter(new File(outputDir.toFile(), "test_products.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR");
            pw.println("1,Laptop");
            pw.println("2,Mouse");
            pw.println("3,Desk");
            pw.println("4,Chair");
            pw.println("5,Monitor");
            pw.println("6,Keyboard");
            pw.println("7,Lamp");
        }
        catalog.loadTableFromCSV("products", "target/generated-test-resources/test_products.csv");
    }

    @Test
    void optimizeAnnotatesLogicalPlanAndBuildsPhysicalPlan() {
        QueryOptimizer optimizer = new QueryOptimizer(catalog);

        QueryOptimizer.OptimizationResult result = optimizer.optimize(
                "SELECT c.name, o.customer_id FROM customers c " +
                "INNER JOIN orders o ON c.id = o.customer_id " +
                "WHERE c.age > 25",
                OptimizationOptions.defaults()
        );

        assertNotNull(result.initialLogicalPlan());
        assertNotNull(result.optimizedLogicalPlan());
        assertNotNull(result.physicalPlan());
        assertTrue(result.optimizedLogicalPlan().getEstimatedRows() > 0);
        assertTrue(result.optimizedLogicalPlan().getEstimatedCost() >= 0);
    }

    @Test
    void joinAlgorithmPolicyControlsPhysicalJoinSelection() {
        QueryOptimizer optimizer = new QueryOptimizer(catalog);
        String sql = "SELECT c.name, o.customer_id FROM customers c " +
                "INNER JOIN orders o ON c.id = o.customer_id";

        var hashResult = optimizer.optimize(
                sql,
                new OptimizationOptions(true, true, true,
                        JoinOrderPolicy.PRESERVE_INPUT,
                        JoinAlgorithmPolicy.FORCE_HASH)
        );
        var nljResult = optimizer.optimize(
                sql,
                new OptimizationOptions(true, true, true,
                        JoinOrderPolicy.PRESERVE_INPUT,
                        JoinAlgorithmPolicy.FORCE_NLJ)
        );

        assertInstanceOf(PhysicalHashJoin.class, nljAwareJoin(hashResult.physicalPlan()));
        assertInstanceOf(PhysicalNestedLoopJoin.class, nljAwareJoin(nljResult.physicalPlan()));
    }

    @Test
    void dpJoinOrderingReordersSupportedMultiJoinPlans() {
        QueryOptimizer optimizer = new QueryOptimizer(catalog);

        QueryOptimizer.OptimizationResult preserveResult = optimizer.optimize(
                "SELECT customers.name, orders.customer_id, products.name " +
                "FROM products " +
                "INNER JOIN orders ON products.id = orders.product_id " +
                "INNER JOIN customers ON orders.customer_id = customers.id",
                new OptimizationOptions(true, true, true,
                        JoinOrderPolicy.PRESERVE_INPUT,
                        JoinAlgorithmPolicy.FORCE_HASH)
        );
        QueryOptimizer.OptimizationResult dpResult = optimizer.optimize(
                "SELECT customers.name, orders.customer_id, products.name " +
                "FROM products " +
                "INNER JOIN orders ON products.id = orders.product_id " +
                "INNER JOIN customers ON orders.customer_id = customers.id",
                OptimizationOptions.defaults()
        );

        LogicalJoin preservedTopJoin = topLogicalJoin(preserveResult.optimizedLogicalPlan());
        LogicalJoin dpTopJoin = topLogicalJoin(dpResult.optimizedLogicalPlan());

        // Preserve-input order is left-deep over the FROM order: (products ⋈ orders) ⋈ customers.
        assertEquals("customers", leftmostScanName(preservedTopJoin.getRight()));
        assertEquals("products", leftmostScanName(((LogicalJoin) preservedTopJoin.getLeft()).getLeft()));
        // DP reorders to the cost-model minimum: customers ⋈ (orders ⋈ products), distinct
        // from the preserve order, confirming join reordering took effect.
        assertEquals("customers", leftmostScanName(dpTopJoin.getLeft()));
        assertEquals("orders", leftmostScanName(((LogicalJoin) dpTopJoin.getRight()).getLeft()));
        assertEquals("products", leftmostScanName(((LogicalJoin) dpTopJoin.getRight()).getRight()));
        assertTrue(dpResult.optimizedLogicalPlan().getEstimatedRows() > 0);
        assertTrue(dpResult.optimizedLogicalPlan().getEstimatedCost() >= 0);
    }

    @Test
    void dpJoinOrderingFallsBackWhenJoinGraphIsDisconnected() {
        QueryOptimizer optimizer = new QueryOptimizer(catalog);
        String sql = "SELECT customers.name, orders.customer_id, products.name " +
                "FROM customers " +
                "INNER JOIN orders ON customers.id = orders.customer_id " +
                "INNER JOIN products ON products.id = products.id";

        QueryOptimizer.OptimizationResult preserveResult = optimizer.optimize(
                sql,
                new OptimizationOptions(true, true, true,
                        JoinOrderPolicy.PRESERVE_INPUT,
                        JoinAlgorithmPolicy.FORCE_HASH)
        );
        QueryOptimizer.OptimizationResult dpResult = optimizer.optimize(sql, OptimizationOptions.defaults());

        assertEquals(
                preserveResult.optimizedLogicalPlan().toPrettyString(),
                dpResult.optimizedLogicalPlan().toPrettyString()
        );
    }

    private Object nljAwareJoin(org.query.optimizer.physical.PhysicalNode node) {
        if (node instanceof PhysicalHashJoin || node instanceof PhysicalNestedLoopJoin) {
            return node;
        }
        for (var child : node.getChildren()) {
            Object found = nljAwareJoin(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private LogicalJoin topLogicalJoin(LogicalNode node) {
        if (node instanceof LogicalJoin join) {
            return join;
        }
        for (LogicalNode child : node.getChildren()) {
            LogicalJoin join = topLogicalJoin(child);
            if (join != null) {
                return join;
            }
        }
        return null;
    }

    private String leftmostScanName(LogicalNode node) {
        if (node instanceof org.query.optimizer.parser.LogicalScan scan) {
            return scan.getTableName();
        }
        return leftmostScanName(node.getChildren().getFirst());
    }
}
