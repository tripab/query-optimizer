package org.query.optimizer;

import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.DataType;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.physical.JoinAlgorithmCounts;
import org.query.optimizer.physical.PhysicalHashJoin;
import org.query.optimizer.physical.PhysicalNestedLoopJoin;
import org.query.optimizer.physical.PhysicalScan;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link JoinAlgorithmCounts} (Phase 4, task P4-3).
 */
class JoinAlgorithmCountsTest {

    private static final Catalog CATALOG = new Catalog();
    private static final Schema SA = new Schema(List.of(new Schema.Column("a_id", DataType.INTEGER)));
    private static final Schema SB = new Schema(List.of(new Schema.Column("b_id", DataType.INTEGER)));
    private static final Expression COND = new Expression.BinaryOp(
            Expression.BinaryOp.Operator.EQ,
            new Expression.ColumnRef("A", "a_id"),
            new Expression.ColumnRef("B", "b_id"));

    private PhysicalScan scan(String name) {
        return new PhysicalScan(name, CATALOG);
    }

    @Test
    void countsZeroJoinsForLeafScan() {
        JoinAlgorithmCounts counts = JoinAlgorithmCounts.of(scan("A"));
        assertEquals(0, counts.hashJoins());
        assertEquals(0, counts.nestedLoopJoins());
        assertEquals(0, counts.totalJoins());
    }

    @Test
    void countsSingleHashJoin() {
        PhysicalHashJoin hj = new PhysicalHashJoin(scan("A"), scan("B"), COND, SA, SB);
        JoinAlgorithmCounts counts = JoinAlgorithmCounts.of(hj);
        assertEquals(1, counts.hashJoins());
        assertEquals(0, counts.nestedLoopJoins());
    }

    @Test
    void countsSingleNestedLoopJoin() {
        PhysicalNestedLoopJoin nlj = new PhysicalNestedLoopJoin(scan("A"), scan("B"), COND, SA, SB);
        JoinAlgorithmCounts counts = JoinAlgorithmCounts.of(nlj);
        assertEquals(0, counts.hashJoins());
        assertEquals(1, counts.nestedLoopJoins());
    }

    @Test
    void countsMixedNestedJoins() {
        // hashJoin( scan, nestedLoopJoin(scan, scan) )
        PhysicalNestedLoopJoin inner =
                new PhysicalNestedLoopJoin(scan("A"), scan("B"), COND, SA, SB);
        PhysicalHashJoin outer =
                new PhysicalHashJoin(scan("C"), inner, COND, SA, SB);

        JoinAlgorithmCounts counts = JoinAlgorithmCounts.of(outer);
        assertEquals(1, counts.hashJoins());
        assertEquals(1, counts.nestedLoopJoins());
        assertEquals(2, counts.totalJoins());
    }
}
