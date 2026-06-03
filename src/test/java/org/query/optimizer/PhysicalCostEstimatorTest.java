package org.query.optimizer;

import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.DataType;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.parser.LogicalAggregate.AggFunction;
import org.query.optimizer.parser.LogicalAggregate.AggregateOp;
import org.query.optimizer.physical.PhysicalAggregate;
import org.query.optimizer.physical.PhysicalFilter;
import org.query.optimizer.physical.PhysicalHashJoin;
import org.query.optimizer.physical.PhysicalNestedLoopJoin;
import org.query.optimizer.physical.PhysicalProject;
import org.query.optimizer.physical.PhysicalScan;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PhysicalCostEstimator} (Phase 4, task P4-1).
 *
 * <p>These exercise the algorithm-specific cost formulas directly. The estimator
 * reads only row annotations and child structure, so no catalog data is needed.
 * With the default {@code CostConfig} (PAGE_COST=1, TUPLE_COST=0.01, PAGE_SIZE=100,
 * COMPARISON_COST=0.001, HASH_COST=0.005):
 * <pre>
 *   scan(1000 rows) = ceil(1000/100)*1 + 1000*0.01 = 10 + 10 = 20
 *   hashJoin(1000,1000) = 20 + 20 + (1000+1000)*0.005   = 40 + 10   = 50
 *   nlj(1000,1000)      = 20 + 20 + (1000*1000)*0.001    = 40 + 1000 = 1040
 * </pre>
 */
class PhysicalCostEstimatorTest {

    private static final Catalog CATALOG = new Catalog();
    private static final Schema SA = new Schema(List.of(new Schema.Column("a_id", DataType.INTEGER)));
    private static final Schema SB = new Schema(List.of(new Schema.Column("b_id", DataType.INTEGER)));
    private static final Expression COND = new Expression.BinaryOp(
            Expression.BinaryOp.Operator.EQ,
            new Expression.ColumnRef("A", "a_id"),
            new Expression.ColumnRef("B", "b_id"));

    private final PhysicalCostEstimator estimator = new PhysicalCostEstimator();

    private PhysicalScan scan(String name, long rows) {
        PhysicalScan s = new PhysicalScan(name, CATALOG);
        s.setEstimatedRows(rows);
        return s;
    }

    // -------------------------------------------------------------------------
    // Scan
    // -------------------------------------------------------------------------

    @Test
    void scanCostCombinesPagesAndTuples() {
        assertEquals(20.0, estimator.estimateCost(scan("A", 1000)), 1e-9);
    }

    @Test
    void unknownRowCountFloorsAtOne() {
        // No row estimate set -> treated as 1 row: ceil(1/100)*1 + 1*0.01 = 1.01
        PhysicalScan s = new PhysicalScan("A", CATALOG);
        assertEquals(1.01, estimator.estimateCost(s), 1e-9);
    }

    // -------------------------------------------------------------------------
    // Join algorithm costs
    // -------------------------------------------------------------------------

    @Test
    void hashJoinCostScalesWithSumOfInputs() {
        assertEquals(50.0, estimator.hashJoinCost(scan("A", 1000), scan("B", 1000)), 1e-9);
    }

    @Test
    void nestedLoopCostScalesWithProductOfInputs() {
        assertEquals(1040.0, estimator.nestedLoopJoinCost(scan("A", 1000), scan("B", 1000)), 1e-9);
    }

    @Test
    void estimateCostMatchesAlgorithmHelpersForJoinNodes() {
        PhysicalScan a = scan("A", 1000);
        PhysicalScan b = scan("B", 1000);
        PhysicalHashJoin hj = new PhysicalHashJoin(a, b, COND, SA, SB);
        PhysicalNestedLoopJoin nlj = new PhysicalNestedLoopJoin(a, b, COND, SA, SB);

        assertEquals(estimator.hashJoinCost(a, b), estimator.estimateCost(hj), 1e-9);
        assertEquals(estimator.nestedLoopJoinCost(a, b), estimator.estimateCost(nlj), 1e-9);
    }

    @Test
    void hashJoinIsCheaperForLargeBalancedInputs() {
        PhysicalScan a = scan("A", 1000);
        PhysicalScan b = scan("B", 1000);
        assertTrue(estimator.hashJoinCost(a, b) < estimator.nestedLoopJoinCost(a, b),
                "hash join should win on large inputs (50 vs 1040)");
    }

    @Test
    void nestedLoopIsCheaperForTinyInputs() {
        // 2x2: sum-based hash term (4*0.005=0.02) exceeds product-based nlj term (4*0.001=0.004)
        PhysicalScan a = scan("A", 2);
        PhysicalScan b = scan("B", 2);
        assertTrue(estimator.nestedLoopJoinCost(a, b) < estimator.hashJoinCost(a, b),
                "nested-loop should win on tiny inputs");
    }

    // -------------------------------------------------------------------------
    // Unary operators
    // -------------------------------------------------------------------------

    @Test
    void filterAddsComparisonCostPerInputRow() {
        PhysicalScan a = scan("A", 1000);
        PhysicalFilter filter = new PhysicalFilter(
                new Expression.BinaryOp(Expression.BinaryOp.Operator.GT,
                        new Expression.ColumnRef("A", "a_id"), new Expression.Literal<>(5)),
                a, SA);
        // 20 (scan) + 1000*0.001 = 21
        assertEquals(21.0, estimator.estimateCost(filter), 1e-9);
    }

    @Test
    void projectAddsTupleCostPerInputRow() {
        PhysicalScan a = scan("A", 1000);
        PhysicalProject project = new PhysicalProject(
                List.of(new Expression.ColumnRef("A", "a_id")), List.of("a_id"), a, SA);
        // 20 (scan) + 1000*0.01 = 30
        assertEquals(30.0, estimator.estimateCost(project), 1e-9);
    }

    @Test
    void aggregateAddsHashPerInputAndTuplePerGroup() {
        PhysicalScan a = scan("A", 1000);
        PhysicalAggregate agg = new PhysicalAggregate(
                a, List.of("a_id"), List.of(new AggregateOp(AggFunction.COUNT, "*", "cnt")), SA);
        agg.setEstimatedRows(5);
        // 20 (scan) + 1000*0.005 (hash) + 5*0.01 (emit) = 25.05
        assertEquals(25.05, estimator.estimateCost(agg), 1e-9);
    }

    // -------------------------------------------------------------------------
    // Annotation
    // -------------------------------------------------------------------------

    @Test
    void annotateCostsSetsCostOnEveryNode() {
        PhysicalScan a = scan("A", 1000);
        PhysicalScan b = scan("B", 1000);
        PhysicalHashJoin hj = new PhysicalHashJoin(a, b, COND, SA, SB);

        estimator.annotateCosts(hj);

        assertEquals(20.0, a.getEstimatedCost(), 1e-9);
        assertEquals(20.0, b.getEstimatedCost(), 1e-9);
        assertEquals(50.0, hj.getEstimatedCost(), 1e-9);
    }
}
