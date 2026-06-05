package org.query.optimizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.parser.AST;
import org.query.optimizer.parser.SQLParser;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for qualified column names in GROUP BY.
 *
 * <p>Previously {@code GROUP BY p.category} parsed to the qualifier {@code "p"}
 * (the rest of the qualified name was left unconsumed), so planning a query like
 * the learned-benchmark aggregation template crashed with
 * {@code Column not found: p}. These tests pin both the parser fix and the
 * end-to-end execution that exercises it.
 *
 * <h2>Fixtures</h2>
 * <pre>
 *   products : id, category (Electronics x2, Furniture x1)  -- 3 rows
 *   orders   : id, product_id                               -- 5 rows
 * </pre>
 */
class QualifiedGroupByTest {

    private static final Catalog catalog = new Catalog();
    private static final String DATA_DIR = "target/generated-test-resources/qual-groupby";

    @BeforeAll
    static void setup() throws IOException {
        Files.createDirectories(Paths.get(DATA_DIR));

        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "products.csv"))) {
            pw.println("id:INTEGER,category:VARCHAR");
            pw.println("1,Electronics");
            pw.println("2,Electronics");
            pw.println("3,Furniture");
        }
        catalog.loadTableFromCSV("products", DATA_DIR + "/products.csv");

        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "orders.csv"))) {
            pw.println("id:INTEGER,product_id:INTEGER");
            pw.println("1,1");
            pw.println("2,1");
            pw.println("3,2");
            pw.println("4,3");
            pw.println("5,3");
        }
        catalog.loadTableFromCSV("orders", DATA_DIR + "/orders.csv");
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.deleteIfExists(Paths.get(DATA_DIR + "/products.csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR + "/orders.csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR));
    }

    // -------------------------------------------------------------------------
    // Parser-level
    // -------------------------------------------------------------------------

    @Test
    void groupByQualifiedColumnResolvesToBareColumnName() {
        AST.SelectStmt ast = new SQLParser().parse(
                "SELECT p.category, COUNT(*) FROM products p " +
                        "INNER JOIN orders o ON p.id = o.product_id GROUP BY p.category");

        assertEquals(List.of("category"), ast.groupByColumns(),
                "GROUP BY p.category must resolve to the bare column 'category', not the qualifier 'p'");
    }

    @Test
    void groupByUnqualifiedColumnStillWorks() {
        AST.SelectStmt ast = new SQLParser().parse(
                "SELECT category, COUNT(*) FROM products GROUP BY category");
        assertEquals(List.of("category"), ast.groupByColumns());
    }

    @Test
    void groupByMultipleQualifiedColumns() {
        AST.SelectStmt ast = new SQLParser().parse(
                "SELECT p.category, COUNT(*) FROM products p GROUP BY p.category, p.id");
        assertEquals(List.of("category", "id"), ast.groupByColumns());
    }

    // -------------------------------------------------------------------------
    // End-to-end (the shape that used to crash)
    // -------------------------------------------------------------------------

    @Test
    void aggregationWithQualifiedGroupByPlansAndExecutes() {
        QueryOptimizer optimizer = new QueryOptimizer(catalog);
        String sql = "SELECT p.category, COUNT(*) FROM products p " +
                "INNER JOIN orders o ON p.id = o.product_id GROUP BY p.category";

        // Used to throw IllegalArgumentException("Column not found: p") during planning.
        QueryOptimizer.OptimizationResult result =
                assertDoesNotThrow(() -> optimizer.optimize(sql, OptimizationOptions.defaults()));

        Executor.ExecutionResult exec = new Executor().execute(result.physicalPlan());
        // Two categories among the joined rows: Electronics (orders 1,2,3) and Furniture (orders 4,5).
        assertEquals(2, exec.getResultCount());
    }

    @Test
    void aggregationWithQualifiedGroupByAndFilterExecutes() {
        QueryOptimizer optimizer = new QueryOptimizer(catalog);
        String sql = "SELECT p.category, COUNT(*) FROM products p " +
                "INNER JOIN orders o ON p.id = o.product_id " +
                "WHERE p.category = 'Electronics' GROUP BY p.category";

        QueryOptimizer.OptimizationResult result =
                assertDoesNotThrow(() -> optimizer.optimize(sql, OptimizationOptions.defaults()));

        Executor.ExecutionResult exec = new Executor().execute(result.physicalPlan());
        assertEquals(1, exec.getResultCount(), "only the Electronics group should remain");
    }
}
