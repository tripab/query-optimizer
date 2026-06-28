package org.query.optimizer.executor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.executor.Executor.ExecutionResult;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalPlanBuilder;
import org.query.optimizer.parser.SQLParser;
import org.query.optimizer.physical.PhysicalFilter;
import org.query.optimizer.physical.PhysicalNode;
import org.query.optimizer.physical.PhysicalPlanBuilder;
import org.query.optimizer.physical.PhysicalScan;
import org.query.optimizer.vectorized.VectorizedExecutor;
import org.query.optimizer.vectorized.VectorizedOperator;
import org.query.optimizer.vectorized.VectorizedPlanBuilder;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@code Executor} (and {@code VectorizedExecutor}) are pure after
 * task T4: they return rows and a processed-row count, with no timing baked into
 * {@link ExecutionResult}.
 */
class ExecutorPurityTest {

    static final Catalog catalog = new Catalog();

    @BeforeAll
    static void setup() throws IOException {
        Path dir = Paths.get("target/generated-test-resources");
        if (!Files.exists(dir)) Files.createDirectory(dir);
        try (PrintWriter pw = new PrintWriter(new File(dir.toFile(), "purity_customers.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,age:INTEGER");
            pw.println("1,Alice,30");
            pw.println("2,Bob,25");
            pw.println("3,Charlie,35");
            pw.println("4,Dana,20");
        }
        catalog.loadTableFromCSV("customers",
                "target/generated-test-resources/purity_customers.csv");
    }

    @Test
    void executionResultCarriesNoTimeComponent() {
        List<String> components = Arrays.stream(ExecutionResult.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertEquals(List.of("tuples", "tuplesProcessed"), components,
                "ExecutionResult must expose only tuples and tuplesProcessed (no timing)");
    }

    @Test
    void executeReturnsTuplesAndProcessedCount() {
        ExecutionResult scan = new Executor().execute(new PhysicalScan("customers", catalog));
        assertEquals(4, scan.getResultCount());
        assertEquals(4, scan.tuplesProcessed());
        assertEquals(scan.getResultCount(), scan.tuples().size());
    }

    @Test
    void filterPassesOnlyMatchingRows() {
        Schema schema = catalog.getTableMetadata("customers").getSchema();
        Expression pred = new Expression.BinaryOp(
                Expression.BinaryOp.Operator.GT,
                new Expression.ColumnRef("customers", "age"),
                new Expression.Literal<>(25));
        PhysicalFilter filter = new PhysicalFilter(pred,
                new PhysicalScan("customers", catalog), schema);

        ExecutionResult res = new Executor().execute(filter);
        // age > 25 keeps Alice(30) and Charlie(35)
        assertEquals(2, res.getResultCount());
        assertEquals(2, res.tuplesProcessed());
    }

    @Test
    void volcanoAndVectorizedAgreeOnRowCount() {
        LogicalNode logical = new LogicalPlanBuilder(catalog)
                .build(new SQLParser().parse(
                        "SELECT id, name FROM customers WHERE age > 25"));

        PhysicalNode volcano = new PhysicalPlanBuilder(catalog).build(logical);
        VectorizedOperator vectorized = new VectorizedPlanBuilder(catalog).build(logical);

        int volcanoRows = new Executor().execute(volcano).getResultCount();
        int vectorizedRows = new VectorizedExecutor().execute(vectorized).getResultCount();

        assertEquals(volcanoRows, vectorizedRows,
                "both pure executors must return the same row count");
        assertEquals(2, volcanoRows);
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.deleteIfExists(Paths.get("target/generated-test-resources/purity_customers.csv"));
    }
}
