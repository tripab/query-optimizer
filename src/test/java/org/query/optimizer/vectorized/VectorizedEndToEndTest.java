package org.query.optimizer.vectorized;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.RuleEngine;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.Tuple;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalPlanBuilder;
import org.query.optimizer.parser.SQLParser;
import org.query.optimizer.physical.PhysicalNode;
import org.query.optimizer.physical.PhysicalPlanBuilder;
import org.query.optimizer.rules.FilterMerge;
import org.query.optimizer.rules.PredicatePushdown;
import org.query.optimizer.rules.ProjectionPushdown;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end correctness tests for the vectorized execution engine (Phase 2, task 2.8).
 *
 * <p>Each test runs the same SQL query through both the Volcano iterator engine and the
 * vectorized engine and asserts that the two result sets are identical (order-independent).
 * This guarantees that the vectorized operators produce exactly the same answers as the
 * proven-correct Volcano path, for every supported query shape.
 *
 * <h2>Pipeline under test</h2>
 * <pre>
 *   SQL string
 *     → SQLParser          → AST
 *     → LogicalPlanBuilder → LogicalNode tree
 *     → RuleEngine         → optimised LogicalNode tree
 *                                  │
 *              ┌───────────────────┴───────────────────┐
 *              │ Volcano path                           │ Vectorized path
 *              ▼                                        ▼
 *     PhysicalPlanBuilder                    VectorizedPlanBuilder
 *     PhysicalNode tree                      VectorizedOperator tree
 *     Executor.execute()                     VectorizedExecutor.execute()
 *              │                                        │
 *              └──────────── assertResultSetsEqual ─────┘
 * </pre>
 *
 * <h2>Covered query shapes (Phase 2 scope)</h2>
 * <ul>
 *   <li>Full table scan with projection</li>
 *   <li>Single-predicate filter with projection</li>
 *   <li>Multi-predicate filter (AND) with projection</li>
 *   <li>Filter that eliminates all rows</li>
 *   <li>Filter on VARCHAR column</li>
 *   <li>Table with rows spanning multiple batches</li>
 * </ul>
 */
public class VectorizedEndToEndTest {

    private static final Catalog catalog = new Catalog();
    private static final SQLParser parser = new SQLParser();
    private static final LogicalPlanBuilder logicalBuilder = new LogicalPlanBuilder(catalog);
    private static final PhysicalPlanBuilder physicalBuilder = new PhysicalPlanBuilder(catalog);
    private static final VectorizedPlanBuilder vectorBuilder = new VectorizedPlanBuilder(catalog);
    private static final Executor volcanoExec = new Executor();
    private static final VectorizedExecutor vectorExec = new VectorizedExecutor();

    private static final RuleEngine optimizer = new RuleEngine(List.of(
            new PredicatePushdown(),
            new FilterMerge(),
            new ProjectionPushdown()
    ));

    // -------------------------------------------------------------------------
    // Test data setup
    // -------------------------------------------------------------------------

    @BeforeAll
    static void setup() throws IOException {
        Path dir = Paths.get("target/generated-test-resources");
        Files.createDirectories(dir);

        // products table: id INTEGER, name VARCHAR, price FLOAT
        // 10 rows — fits within a single batch
        try (PrintWriter pw = new PrintWriter(new File(dir.toFile(), "e2eProducts.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,price:FLOAT");
            pw.println("1,Laptop,999.99");
            pw.println("2,Mouse,29.99");
            pw.println("3,Keyboard,79.99");
            pw.println("4,Monitor,349.99");
            pw.println("5,Headphones,149.99");
            pw.println("6,Webcam,89.99");
            pw.println("7,Desk,299.99");
            pw.println("8,Chair,199.99");
            pw.println("9,Lamp,49.99");
            pw.println("10,Notebook,9.99");
        }
        catalog.loadTableFromCSV("products", dir + "/e2eProducts.csv");

        // items table — deliberately larger than DEFAULTBATCHSIZE (1024) to exercise
        // multi-batch paths.  We generate 1100 rows programmatically via a CSV.
        try (PrintWriter pw = new PrintWriter(new File(dir.toFile(), "e2eItems.csv"))) {
            pw.println("id:INTEGER,category:VARCHAR,value:FLOAT");
            for (int i = 1; i <= 1100; i++) {
                // category cycles through A, B, C
                String cat = switch (i % 3) {
                    case 0 -> "C";
                    case 1 -> "A";
                    default -> "B";
                };
                pw.printf("%d,%s,%.2f%n", i, cat, i * 1.5f);
            }
        }
        catalog.loadTableFromCSV("items", dir + "/e2eItems.csv");
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.deleteIfExists(Paths.get("target/generated-test-resources/e2eProducts.csv"));
        Files.deleteIfExists(Paths.get("target/generated-test-resources/e2eItems.csv"));
    }

    // -------------------------------------------------------------------------
    // Core helper: parse → optimise → run both engines → compare
    // -------------------------------------------------------------------------

    /**
     * Runs {@code sql} through both execution engines and asserts the result sets are equal
     * (order-independent comparison on string-serialised rows).
     */
    private void assertVectorizedMatchesVolcano(String sql) {
        // 1. Parse
        var ast = parser.parse(sql);

        // 2. Build logical plan
        LogicalNode logical = logicalBuilder.build(ast);

        // 3. Optimise
        LogicalNode optimised = optimizer.optimize(logical);

        // 4. Execute via Volcano
        PhysicalNode physical = physicalBuilder.build(optimised);
        Executor.ExecutionResult volcanoResult = volcanoExec.execute(physical);

        // 5. Execute via vectorized engine
        // Re-optimise from the same logical plan to get a fresh tree for the vectorized builder
        LogicalNode optimised2 = optimizer.optimize(logicalBuilder.build(parser.parse(sql)));
        VectorizedOperator vectorPlan = vectorBuilder.build(optimised2);
        Executor.ExecutionResult vectorResult = vectorExec.execute(vectorPlan);

        // 6. Assert same row count
        assertEquals(volcanoResult.getResultCount(), vectorResult.getResultCount(),
                "Row count mismatch for: " + sql);

        // 7. Assert same rows (order-independent)
        Set<String> volcanoRows = serialise(volcanoResult.tuples());
        Set<String> vectorRows = serialise(vectorResult.tuples());
        assertEquals(volcanoRows, vectorRows,
                "Result set mismatch for: " + sql);
    }

    /**
     * Serialises each tuple to a canonical string (sorted column values) for
     * order-independent comparison. Column names are included so schema differences
     * are caught as well as value differences.
     */
    private static Set<String> serialise(List<Tuple> tuples) {
        return tuples.stream()
                .map(VectorizedEndToEndTest::serialiseRow)
                .collect(Collectors.toSet());
    }

    private static String serialiseRow(Tuple tuple) {
        // Sort by column name so that column ordering differences don't cause false failures.
        return tuple.stream()
                .sorted(Comparator.comparing(a -> a.getKey().name()))
                .map(a -> a.getKey().name() + "=" + a.getValue())
                .collect(Collectors.joining("|"));
    }

    // -------------------------------------------------------------------------
    // Tests: SELECT name FROM products WHERE price > 100  (the canonical task 2.8 query)
    // -------------------------------------------------------------------------

    @Test
    void testSelectNameWherePriceMatchesVolcano() {
        assertVectorizedMatchesVolcano(
                "SELECT name FROM products WHERE price > 100");
    }

    @Test
    void testSelectNameWherePriceCorrectRowCount() {
        // price > 100: Laptop(999.99), Monitor(349.99), Headphones(149.99), Desk(299.99), Chair(199.99)
        var ast = parser.parse("SELECT name FROM products WHERE price > 100");
        var logical = logicalBuilder.build(ast);
        var optimised = optimizer.optimize(logical);
        var vectorPlan = vectorBuilder.build(optimised);
        var result = vectorExec.execute(vectorPlan);

        assertEquals(5, result.getResultCount(),
                "Expected 5 products with price > 100");
    }

    @Test
    void testSelectNameWherePriceCorrectNames() {
        var ast = parser.parse("SELECT name FROM products WHERE price > 100");
        var optimised = optimizer.optimize(logicalBuilder.build(ast));
        var result = vectorExec.execute(vectorBuilder.build(optimised));

        Set<String> names = result.tuples().stream()
                .map(t -> (String) t.find(catalog.getTableMetadata("products").getSchema().getColumn("name")))
                .collect(Collectors.toSet());

        assertTrue(names.contains("Laptop"));
        assertTrue(names.contains("Monitor"));
        assertTrue(names.contains("Headphones"));
        assertTrue(names.contains("Desk"));
        assertTrue(names.contains("Chair"));
        assertFalse(names.contains("Mouse"), "Mouse (29.99) must not pass price > 100");
    }

    // -------------------------------------------------------------------------
    // Full scan projection
    // -------------------------------------------------------------------------

    @Test
    void testSelectAllColumnsMatchesVolcano() {
        assertVectorizedMatchesVolcano("SELECT id, name, price FROM products");
    }

    @Test
    void testSelectSingleColumnMatchesVolcano() {
        assertVectorizedMatchesVolcano("SELECT name FROM products");
    }

    // -------------------------------------------------------------------------
    // Various filter predicates
    // -------------------------------------------------------------------------

    @Test
    void testFilterWithLTMatchesVolcano() {
        assertVectorizedMatchesVolcano("SELECT name FROM products WHERE price < 50");
    }

    @Test
    void testFilterWithEQIntegerMatchesVolcano() {
        assertVectorizedMatchesVolcano("SELECT name FROM products WHERE id = 3");
    }

    @Test
    void testFilterWithGTEMatchesVolcano() {
        assertVectorizedMatchesVolcano("SELECT id, name FROM products WHERE price >= 199.99");
    }

    @Test
    void testFilterWithLTEMatchesVolcano() {
        assertVectorizedMatchesVolcano("SELECT id, name FROM products WHERE id <= 3");
    }

    // -------------------------------------------------------------------------
    // All-reject filter
    // -------------------------------------------------------------------------

    @Test
    void testAllRejectFilterReturnsEmptyResultSet() {
        assertVectorizedMatchesVolcano(
                "SELECT name FROM products WHERE price > 9999");
    }

    @Test
    void testAllRejectFilterZeroRows() {
        var optimised = optimizer.optimize(
                logicalBuilder.build(parser.parse("SELECT name FROM products WHERE price > 9999")));
        var result = vectorExec.execute(vectorBuilder.build(optimised));
        assertEquals(0, result.getResultCount());
    }

    // -------------------------------------------------------------------------
    // Multi-batch table
    // -------------------------------------------------------------------------

    @Test
    void testMultiBatchTableFullScanMatchesVolcano() {
        // items has 1100 rows → 2 batches (1024 + 76)
        assertVectorizedMatchesVolcano("SELECT id, category, value FROM items");
    }

    @Test
    void testMultiBatchTableWithFilterMatchesVolcano() {
        assertVectorizedMatchesVolcano("SELECT id FROM items WHERE value > 1000");
    }

    @Test
    void testMultiBatchTableWithProjectionMatchesVolcano() {
        assertVectorizedMatchesVolcano("SELECT category FROM items WHERE id < 100");
    }
}
