package org.query.optimizer;

import org.junit.jupiter.api.Test;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalAggregate;
import org.query.optimizer.parser.LogicalFilter;
import org.query.optimizer.parser.LogicalJoin;
import org.query.optimizer.parser.LogicalProject;
import org.query.optimizer.parser.LogicalScan;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JoinExtractorTest {
    private final JoinExtractor extractor = new JoinExtractor();

    /** Returns the table name of the single scan under a join-input leaf. */
    private static String tableOf(LogicalNode leaf) {
        if (leaf instanceof LogicalScan scan) {
            return scan.getTableName();
        }
        return tableOf(leaf.getChildren().getFirst());
    }

    @Test
    void extractPreservesUnaryWrappersAsLeaves() {
        LogicalNode plan = new LogicalProject(
                List.of(new Expression.ColumnRef("customers", "name")),
                List.of("name"),
                new LogicalJoin(
                        new LogicalFilter(
                                new Expression.BinaryOp(
                                        Expression.BinaryOp.Operator.GT,
                                        new Expression.ColumnRef("customers", "id"),
                                        new Expression.Literal<>(10)
                                ),
                                new LogicalScan("customers")
                        ),
                        new LogicalProject(
                                List.of(new Expression.ColumnRef("orders", "customer_id")),
                                List.of("customer_id"),
                                new LogicalScan("orders")
                        ),
                        LogicalJoin.JoinType.INNER,
                        new Expression.BinaryOp(
                                Expression.BinaryOp.Operator.EQ,
                                new Expression.ColumnRef("customers", "id"),
                                new Expression.ColumnRef("orders", "customer_id")
                        )
                )
        );

        JoinExtractor.JoinInfo info = extractor.extract(plan);

        assertTrue(info.supported());
        assertTrue(info.hasJoinTree());
        assertEquals(2, info.leaves().size());
        // Leaves are returned whole, preserving the unary operators wrapping each scan
        // (so reordering cannot drop a pushed-down filter).
        assertInstanceOf(LogicalFilter.class, info.leaves().get(0));
        assertInstanceOf(LogicalProject.class, info.leaves().get(1));
        assertEquals(List.of("customers", "orders"),
                info.leaves().stream().map(JoinExtractorTest::tableOf).toList());
        assertEquals(1, info.conditions().size());
        assertEquals("customers", info.conditions().getFirst().leftTable());
        assertEquals("orders", info.conditions().getFirst().rightTable());
    }

    @Test
    void replaceJoinSubtreePreservesUnaryChainAboveJoinRoot() {
        LogicalJoin originalJoin = new LogicalJoin(
                new LogicalScan("customers"),
                new LogicalScan("orders"),
                LogicalJoin.JoinType.INNER,
                new Expression.BinaryOp(
                        Expression.BinaryOp.Operator.EQ,
                        new Expression.ColumnRef("customers", "id"),
                        new Expression.ColumnRef("orders", "customer_id")
                )
        );
        LogicalNode plan = new LogicalProject(
                List.of(Expression.ColumnRef.from("customer_count")),
                List.of("customer_count"),
                new LogicalAggregate(
                        List.of("customers.id"),
                        List.of(new LogicalAggregate.AggregateOp(
                                LogicalAggregate.AggFunction.COUNT,
                                "*",
                                "customer_count"
                        )),
                        new LogicalFilter(
                                new Expression.BinaryOp(
                                        Expression.BinaryOp.Operator.GT,
                                        new Expression.ColumnRef("orders", "customer_id"),
                                        new Expression.Literal<>(0)
                                ),
                                originalJoin
                        )
                )
        );

        LogicalJoin replacementJoin = new LogicalJoin(
                new LogicalScan("orders"),
                new LogicalScan("customers"),
                LogicalJoin.JoinType.INNER,
                originalJoin.getCondition()
        );

        LogicalNode replaced = extractor.replaceJoinSubtree(plan, originalJoin, replacementJoin);

        LogicalProject project = assertInstanceOf(LogicalProject.class, replaced);
        LogicalAggregate aggregate = assertInstanceOf(LogicalAggregate.class, project.getChild());
        LogicalFilter filter = assertInstanceOf(LogicalFilter.class, aggregate.getChild());
        assertSame(replacementJoin, filter.getChild());
    }

    @Test
    void extractSingleScanPlanWithoutJoin() {
        LogicalScan scan = new LogicalScan("customers");

        JoinExtractor.JoinInfo info = extractor.extract(scan);

        assertTrue(info.supported());
        assertEquals(1, info.leaves().size());
        assertEquals("customers", tableOf(info.leaves().getFirst()));
        assertEquals(0, info.conditions().size());
    }
}
