package org.query.optimizer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.physical.PhysicalHashJoin;
import org.query.optimizer.physical.PhysicalNestedLoopJoin;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QueryOptimizerTest {
    private static Catalog catalog;

    @BeforeAll
    static void setup() throws IOException {
        PhysicalExecutionTest.setup();
        catalog = PhysicalExecutionTest.catalog;
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
}
