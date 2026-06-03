package org.query.optimizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.parser.LogicalAggregate;
import org.query.optimizer.parser.LogicalAggregate.AggFunction;
import org.query.optimizer.parser.LogicalAggregate.AggregateOp;
import org.query.optimizer.parser.LogicalFilter;
import org.query.optimizer.parser.LogicalJoin;
import org.query.optimizer.parser.LogicalProject;
import org.query.optimizer.parser.LogicalScan;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for per-operator statistics propagation in {@link CardinalityEstimator}
 * (Phase 3, task P3-2).
 *
 * <p>These exercise the propagation contract directly via {@code propagate(...)}:
 * filters collapse equality-constrained column NDVs to 1 and cap other NDVs at the
 * surviving row count; projections keep only surviving columns; joins use the
 * propagated child NDVs (so a filter beneath a join shrinks the estimate); and
 * aggregates derive the group count from propagated group-by NDVs.
 *
 * <h2>Fixtures</h2>
 * <pre>
 *   left  : id (NDV 6, 1..6), grp (NDV 2: A,B)   -- 6 rows
 *   right : rid (NDV 5),      lid (NDV 4: 1,2,3,5) -- 5 rows
 * </pre>
 */
class CardinalityPropagationTest {

    private static final Catalog catalog = new Catalog();
    private static CardinalityEstimator estimator;
    private static final String DATA_DIR = "target/generated-test-resources/p3-prop";

    @BeforeAll
    static void setup() throws IOException {
        Files.createDirectories(Paths.get(DATA_DIR));

        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "left.csv"))) {
            pw.println("id:INTEGER,grp:VARCHAR");
            pw.println("1,A");
            pw.println("2,A");
            pw.println("3,A");
            pw.println("4,B");
            pw.println("5,B");
            pw.println("6,B");
        }
        catalog.loadTableFromCSV("left", DATA_DIR + "/left.csv");

        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "right.csv"))) {
            pw.println("rid:INTEGER,lid:INTEGER");
            pw.println("1,1");
            pw.println("2,1");
            pw.println("3,2");
            pw.println("4,3");
            pw.println("5,5");
        }
        catalog.loadTableFromCSV("right", DATA_DIR + "/right.csv");

        estimator = new CardinalityEstimator(catalog);
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.deleteIfExists(Paths.get(DATA_DIR + "/left.csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR + "/right.csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private LogicalScan leftScan() {
        return new LogicalScan("left");
    }

    private LogicalFilter grpEquals(String value, LogicalScan child) {
        return new LogicalFilter(
                new Expression.BinaryOp(Expression.BinaryOp.Operator.EQ,
                        new Expression.ColumnRef("left", "grp"),
                        new Expression.Literal<>(value)),
                child);
    }

    // -------------------------------------------------------------------------
    // Scan
    // -------------------------------------------------------------------------

    @Test
    void scanPropagatesBaseStats() {
        SubtreeStatistics s = estimator.propagate(leftScan());

        assertEquals(6, s.rowCount());
        assertEquals(6, s.columnEstimate("id").ndv());
        assertEquals(1, s.columnEstimate("id").min());
        assertEquals(6, s.columnEstimate("id").max());
        assertEquals(2, s.columnEstimate("grp").ndv());
    }

    // -------------------------------------------------------------------------
    // Filter
    // -------------------------------------------------------------------------

    @Test
    void filterEqualityCollapsesFilteredColumnNdvToOne() {
        // grp = 'A': selectivity 1/NDV = 1/2 over 6 rows -> 3 rows survive.
        SubtreeStatistics s = estimator.propagate(grpEquals("A", leftScan()));

        assertEquals(3, s.rowCount());
        // Filtered column pinned to a single value.
        assertEquals(1, s.columnEstimate("grp").ndv());
        assertEquals("A", s.columnEstimate("grp").min());
        assertEquals("A", s.columnEstimate("grp").max());
    }

    @Test
    void filterCapsOtherColumnNdvsAtSurvivingRowCount() {
        // After grp='A' leaves 3 rows, id cannot have more than 3 distinct values.
        SubtreeStatistics s = estimator.propagate(grpEquals("A", leftScan()));

        assertEquals(3, s.rowCount());
        assertEquals(3, s.columnEstimate("id").ndv(), "id NDV must be capped at surviving rows");
        // min/max are preserved (conservative).
        assertEquals(1, s.columnEstimate("id").min());
        assertEquals(6, s.columnEstimate("id").max());
    }

    // -------------------------------------------------------------------------
    // Project
    // -------------------------------------------------------------------------

    @Test
    void projectKeepsOnlySurvivingColumnsAndRowCount() {
        LogicalProject project = new LogicalProject(
                List.of(new Expression.ColumnRef("left", "grp")),
                List.of("grp"),
                leftScan());

        SubtreeStatistics s = estimator.propagate(project);

        assertEquals(6, s.rowCount(), "projection does not change row count");
        assertTrue(s.hasColumn("grp"));
        assertFalse(s.hasColumn("id"), "projected-away column should be dropped");
        assertEquals(2, s.columnEstimate("grp").ndv());
    }

    // -------------------------------------------------------------------------
    // Join
    // -------------------------------------------------------------------------

    private LogicalJoin joinOn(org.query.optimizer.logical.LogicalNode left) {
        return new LogicalJoin(left, new LogicalScan("right"),
                LogicalJoin.JoinType.INNER,
                new Expression.BinaryOp(Expression.BinaryOp.Operator.EQ,
                        new Expression.ColumnRef("left", "id"),
                        new Expression.ColumnRef("right", "lid")));
    }

    @Test
    void joinUsesChildNdvsForCardinality() {
        // |left|*|right| / max(NDV(id)=6, NDV(lid)=4) = 6*5/6 = 5.
        SubtreeStatistics s = estimator.propagate(joinOn(leftScan()));

        assertEquals(5, s.rowCount());
    }

    @Test
    void joinKeyColumnsTakeIntersectionNdv() {
        // Surviving join-key distinct values = min(NDV(id)=6, NDV(lid)=4) capped at rows(5) = 4.
        SubtreeStatistics s = estimator.propagate(joinOn(leftScan()));

        assertEquals(4, s.columnEstimate("id").ndv());
        assertEquals(4, s.columnEstimate("lid").ndv());
    }

    @Test
    void filterBeneathJoinShrinksJoinEstimate() {
        long unfiltered = estimator.propagate(joinOn(leftScan())).rowCount();
        // grp='A' -> 3 left rows, id NDV capped at 3; join = 3*5/max(3,4) = 3.
        long filtered = estimator.propagate(joinOn(grpEquals("A", leftScan()))).rowCount();

        assertEquals(5, unfiltered);
        assertEquals(3, filtered);
        assertTrue(filtered < unfiltered,
                "filtering an input must not increase the join estimate");
    }

    // -------------------------------------------------------------------------
    // Aggregate
    // -------------------------------------------------------------------------

    @Test
    void aggregateGroupCountComesFromGroupByNdv() {
        LogicalAggregate agg = new LogicalAggregate(
                List.of("grp"),
                List.of(new AggregateOp(AggFunction.COUNT, "*", "cnt")),
                leftScan());

        SubtreeStatistics s = estimator.propagate(agg);

        // grp has NDV 2 -> two groups.
        assertEquals(2, s.rowCount());
        assertTrue(s.columnEstimate("grp").ndv() <= 2);
        assertEquals(2, s.columnEstimate("cnt").ndv(), "aggregate output has one value per group");
    }

    @Test
    void aggregateWithoutGroupByProducesSingleRow() {
        LogicalAggregate agg = new LogicalAggregate(
                List.of(),
                List.of(new AggregateOp(AggFunction.COUNT, "*", "cnt")),
                leftScan());

        assertEquals(1, estimator.propagate(agg).rowCount());
    }

    @Test
    void aggregateGroupCountNeverExceedsInputRows() {
        // Group by id (NDV 6) over 6 rows -> at most 6 groups.
        LogicalAggregate agg = new LogicalAggregate(
                List.of("id"),
                List.of(new AggregateOp(AggFunction.COUNT, "*", "cnt")),
                leftScan());

        SubtreeStatistics s = estimator.propagate(agg);
        assertEquals(6, s.rowCount());
        assertTrue(s.rowCount() <= estimator.propagate(leftScan()).rowCount());
    }
}
