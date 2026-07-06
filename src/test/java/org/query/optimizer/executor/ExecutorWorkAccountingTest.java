package org.query.optimizer.executor;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.JoinAlgorithmPolicy;
import org.query.optimizer.JoinOrderPolicy;
import org.query.optimizer.OptimizationOptions;
import org.query.optimizer.QueryOptimizer;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor.ExecutionResult;
import org.query.optimizer.learned.common.DataGenerator;
import org.query.optimizer.physical.PhysicalNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ExecutionResult.tuplesProcessed} must measure the plan's total
 * operator work, not its output size. Output size is identical for every
 * correct plan of a query, so it cannot distinguish a good plan from a bad
 * one — which silently broke the benchmark oracle ("pick the variant with the
 * least work") and the learned optimizers' {@code logicalCost} training
 * signal, both of which rely on this field.
 */
public class ExecutorWorkAccountingTest {

    static Catalog catalog = new Catalog();
    static final int CUSTOMERS = 1_000;
    static final int ORDERS = 2_000;

    static final String JOIN_SQL =
            "SELECT c.name, o.total FROM customers c "
                    + "INNER JOIN orders o ON c.id = o.customer_id "
                    + "WHERE o.total > 100.0";

    @BeforeAll
    static void setup() {
        DataGenerator.generate(catalog, 1); // 1,000 customers / 2,000 orders
    }

    private static ExecutionResult run(String sql, JoinAlgorithmPolicy joinAlgorithm) {
        QueryOptimizer optimizer = new QueryOptimizer(catalog);
        PhysicalNode plan = optimizer.optimize(sql, new OptimizationOptions(
                true, true, true, JoinOrderPolicy.DP, joinAlgorithm)).physicalPlan();
        return new Executor().execute(plan);
    }

    @Test
    void workCountsEveryOperatorNotJustOutputRows() {
        ExecutionResult result = run(
                "SELECT id, name FROM customers WHERE city = 'Seattle'",
                JoinAlgorithmPolicy.FORCE_HASH);

        // The scan emits all rows and the filter examines all of them, so the
        // plan's work must be at least twice the table size — far more than
        // the filtered output. (Upper bound guards against double counting.)
        assertTrue(result.tuples().size() < CUSTOMERS / 2, "filter should be selective");
        assertTrue(result.tuplesProcessed() >= 2L * CUSTOMERS,
                "work must include scan + filter examinations, got " + result.tuplesProcessed());
        assertTrue(result.tuplesProcessed() <= 4L * CUSTOMERS,
                "work should not double count, got " + result.tuplesProcessed());
    }

    @Test
    void nestedLoopJoinReportsFarMoreWorkThanHashJoin() {
        ExecutionResult hash = run(JOIN_SQL, JoinAlgorithmPolicy.FORCE_HASH);
        ExecutionResult nlj = run(JOIN_SQL, JoinAlgorithmPolicy.FORCE_NLJ);

        // Identical answers...
        assertEquals(hash.tuples().size(), nlj.tuples().size());

        // ...but the NLJ evaluates |customers| x |filtered orders| pairs while
        // the hash join only builds + probes. This gap is what lets the oracle
        // and the learned optimizers tell the plans apart deterministically.
        // (Pushdown filters one input first, hence the / 2 slack.)
        assertTrue(nlj.tuplesProcessed() >= (long) CUSTOMERS * ORDERS / 2,
                "NLJ must account for its evaluated pairs, got " + nlj.tuplesProcessed());
        assertTrue(nlj.tuplesProcessed() > 50 * hash.tuplesProcessed(),
                String.format("expected NLJ work >> hash work, got nlj=%d hash=%d",
                        nlj.tuplesProcessed(), hash.tuplesProcessed()));
    }

    @Test
    void repeatedExecutionOfTheSamePlanReportsTheSameWork() {
        QueryOptimizer optimizer = new QueryOptimizer(catalog);
        PhysicalNode plan = optimizer.optimize(JOIN_SQL, OptimizationOptions.defaults())
                .physicalPlan();
        Executor executor = new Executor();

        long first = executor.execute(plan).tuplesProcessed();
        long second = executor.execute(plan).tuplesProcessed();

        assertTrue(first > 0);
        assertEquals(first, second, "operator counters must reset on open()");
    }
}
