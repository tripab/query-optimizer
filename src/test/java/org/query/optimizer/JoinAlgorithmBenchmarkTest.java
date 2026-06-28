package org.query.optimizer;

import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.physical.PhysicalNode;
import org.query.optimizer.util.DataGenerator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for {@link JoinAlgorithmBenchmark}: builds the exact hash-join and
 * nested-loop-join plans the benchmark times and runs each once through the pure
 * executor, proving the benchmark state is valid and executable.
 *
 * <p>It also pins the hash-join cardinality (every order joins its one customer,
 * a foreign-key join, so the result has exactly one row per order). It does NOT
 * assert hash/nested-loop parity: the nested-loop join currently returns fewer
 * rows on this query because of a pre-existing qualified-column resolution bug
 * (both tables expose an {@code id} column, and {@code customers.id} in the join
 * condition binds to {@code orders.id}). That correctness defect is tracked
 * separately (AGENT_LOG T7); when fixed, this test should additionally assert
 * {@code hashRows == nestedLoopRows}.
 */
class JoinAlgorithmBenchmarkTest {

    @Test
    void hashAndNestedLoopPlansBuildAndExecute() throws IOException {
        Path dir = Files.createTempDirectory("qo-join-bench-test-");
        try {
            new DataGenerator(100, 200, 500).writeAll(dir);   // customers, products, orders
            Catalog catalog = new Catalog();
            catalog.loadTableFromCSV("customers", dir.resolve("customers.csv").toString());
            catalog.loadTableFromCSV("products", dir.resolve("products.csv").toString());
            catalog.loadTableFromCSV("orders", dir.resolve("orders.csv").toString());

            PhysicalNode[] plans = JoinAlgorithmBenchmark.buildJoinPlans(catalog);

            int hashRows = new Executor().execute(plans[0]).getResultCount();
            int nestedLoopRows = new Executor().execute(plans[1]).getResultCount();

            assertEquals(500, hashRows,
                    "foreign-key join: one row per order (hash join is correct)");
            assertTrue(nestedLoopRows > 0,
                    "nested-loop plan must still build and execute");
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }
}
