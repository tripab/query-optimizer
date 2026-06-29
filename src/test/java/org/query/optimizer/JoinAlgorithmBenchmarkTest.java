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

/**
 * Smoke test for {@link JoinAlgorithmBenchmark}: builds the exact hash-join and
 * nested-loop-join plans the benchmark times and runs each once through the pure
 * executor, proving the benchmark state is valid and executable.
 *
 * <p>It pins the hash-join cardinality (every order joins its one customer, a
 * foreign-key join, so the result has exactly one row per order) and asserts
 * hash/nested-loop parity. The parity check guards AGENT_LOG T7: both tables
 * expose an {@code id} column, and before the fix {@code customers.id} in the
 * join condition bound to {@code orders.id}, so the nested-loop arm under-counted
 * rows. Side-aware column resolution now makes the two algorithms agree.
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
            assertEquals(hashRows, nestedLoopRows,
                    "nested-loop join must agree with hash join (AGENT_LOG T7)");
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }
}
