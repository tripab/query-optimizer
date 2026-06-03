package org.query.optimizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.parser.LogicalJoin;
import org.query.optimizer.parser.LogicalScan;
import org.query.optimizer.physical.PhysicalHashJoin;
import org.query.optimizer.physical.PhysicalNestedLoopJoin;
import org.query.optimizer.physical.PhysicalNode;
import org.query.optimizer.physical.PhysicalPlanBuilder;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Physical-planning join-algorithm selection tests (Phase 4).
 *
 * <p>Verifies that under {@link JoinAlgorithmPolicy#COST_BASED} the planner picks
 * the cheaper algorithm given the join's input sizes (hash for large inputs,
 * nested-loop for tiny ones), that {@code FORCE_HASH}/{@code FORCE_NLJ} override
 * that choice, that non-equi joins always fall back to nested-loop, and that
 * physical cost is annotated onto the chosen plan.
 *
 * <h2>Fixtures</h2>
 * <pre>
 *   big_left/big_right   : 40 rows each, join on id -> hash wins under COST_BASED
 *   small_left/small_right : 3 rows each, join on id -> nested-loop wins under COST_BASED
 * </pre>
 */
class JoinAlgorithmSelectionTest {

    private static final Catalog catalog = new Catalog();
    private static QueryOptimizer optimizer;
    private static final String DATA_DIR = "target/generated-test-resources/p4-select";

    @BeforeAll
    static void setup() throws IOException {
        Files.createDirectories(Paths.get(DATA_DIR));
        writeKeyValue("big_left", "lval", 40);
        writeKeyValue("big_right", "rval", 40);
        writeKeyValue("small_left", "lval", 3);
        writeKeyValue("small_right", "rval", 3);
        optimizer = new QueryOptimizer(catalog);
    }

    private static void writeKeyValue(String table, String valueCol, int rows) throws IOException {
        File f = new File(DATA_DIR, table + ".csv");
        try (PrintWriter pw = new PrintWriter(f)) {
            pw.println("id:INTEGER," + valueCol + ":INTEGER");
            for (int i = 1; i <= rows; i++) {
                pw.println(i + "," + (i * 10));
            }
        }
        catalog.loadTableFromCSV(table, f.getPath());
    }

    @AfterAll
    static void cleanup() throws IOException {
        for (String t : new String[]{"big_left", "big_right", "small_left", "small_right"}) {
            Files.deleteIfExists(Paths.get(DATA_DIR + "/" + t + ".csv"));
        }
        Files.deleteIfExists(Paths.get(DATA_DIR));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private OptimizationOptions opts(JoinAlgorithmPolicy policy) {
        return new OptimizationOptions(true, true, true,
                JoinOrderPolicy.PRESERVE_INPUT, policy);
    }

    private PhysicalNode planJoin(String leftTable, String rightTable, String leftVal,
                                  String rightVal, JoinAlgorithmPolicy policy) {
        String sql = String.format(
                "SELECT %s.%s, %s.%s FROM %s JOIN %s ON %s.id = %s.id",
                leftTable, leftVal, rightTable, rightVal,
                leftTable, rightTable, leftTable, rightTable);
        return findJoin(optimizer.optimize(sql, opts(policy)).physicalPlan());
    }

    private PhysicalNode findJoin(PhysicalNode node) {
        if (node instanceof PhysicalHashJoin || node instanceof PhysicalNestedLoopJoin) {
            return node;
        }
        for (PhysicalNode child : node.getChildren()) {
            PhysicalNode found = findJoin(child);
            if (found != null) return found;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Cost-based selection
    // -------------------------------------------------------------------------

    @Test
    void costBasedChoosesHashForLargeInputs() {
        PhysicalNode join = planJoin("big_left", "big_right", "lval", "rval",
                JoinAlgorithmPolicy.COST_BASED);
        assertInstanceOf(PhysicalHashJoin.class, join);
    }

    @Test
    void costBasedChoosesNestedLoopForTinyInputs() {
        PhysicalNode join = planJoin("small_left", "small_right", "lval", "rval",
                JoinAlgorithmPolicy.COST_BASED);
        assertInstanceOf(PhysicalNestedLoopJoin.class, join);
    }

    // -------------------------------------------------------------------------
    // Forced overrides
    // -------------------------------------------------------------------------

    @Test
    void forceHashOverridesCostOnTinyInputs() {
        // Cost-based would pick nested-loop here; FORCE_HASH must override.
        PhysicalNode join = planJoin("small_left", "small_right", "lval", "rval",
                JoinAlgorithmPolicy.FORCE_HASH);
        assertInstanceOf(PhysicalHashJoin.class, join);
    }

    @Test
    void forceNljOverridesCostOnLargeInputs() {
        // Cost-based would pick hash here; FORCE_NLJ must override.
        PhysicalNode join = planJoin("big_left", "big_right", "lval", "rval",
                JoinAlgorithmPolicy.FORCE_NLJ);
        assertInstanceOf(PhysicalNestedLoopJoin.class, join);
    }

    // -------------------------------------------------------------------------
    // Non-equi joins
    // -------------------------------------------------------------------------

    @Test
    void nonEquiJoinAlwaysUsesNestedLoopRegardlessOfPolicy() {
        LogicalJoin join = new LogicalJoin(
                new LogicalScan("big_left"), new LogicalScan("big_right"),
                LogicalJoin.JoinType.INNER,
                new Expression.BinaryOp(Expression.BinaryOp.Operator.GT,
                        new Expression.ColumnRef("big_left", "id"),
                        new Expression.ColumnRef("big_right", "id")));

        PhysicalPlanBuilder builder = new PhysicalPlanBuilder(catalog, JoinAlgorithmPolicy.COST_BASED);
        assertInstanceOf(PhysicalNestedLoopJoin.class, findJoin(builder.build(join)));

        builder.setJoinAlgorithmPolicy(JoinAlgorithmPolicy.FORCE_HASH);
        assertInstanceOf(PhysicalNestedLoopJoin.class, findJoin(builder.build(join)),
                "non-equi join cannot use a hash join even under FORCE_HASH");
    }

    // -------------------------------------------------------------------------
    // Physical cost annotation
    // -------------------------------------------------------------------------

    @Test
    void physicalCostIsAnnotatedAndDistinguishesAlgorithms() {
        PhysicalNode hashJoin = planJoin("big_left", "big_right", "lval", "rval",
                JoinAlgorithmPolicy.FORCE_HASH);
        PhysicalNode nljJoin = planJoin("big_left", "big_right", "lval", "rval",
                JoinAlgorithmPolicy.FORCE_NLJ);

        assertTrue(hashJoin.getEstimatedCost() >= 0, "join must carry a physical cost");
        assertTrue(nljJoin.getEstimatedCost() >= 0, "join must carry a physical cost");
        assertTrue(hashJoin.getEstimatedCost() < nljJoin.getEstimatedCost(),
                "on large inputs the hash join must be priced below the nested-loop join");
    }
}
