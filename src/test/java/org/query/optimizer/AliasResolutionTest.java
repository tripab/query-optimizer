package org.query.optimizer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.Tuple;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.executor.Executor.ExecutionResult;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalFilter;
import org.query.optimizer.parser.LogicalPlanBuilder;
import org.query.optimizer.parser.LogicalProject;
import org.query.optimizer.parser.SQLParser;
import org.query.optimizer.physical.PhysicalFilter;
import org.query.optimizer.physical.PhysicalNode;
import org.query.optimizer.physical.PhysicalScan;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FROM-clause aliases must be canonicalized to real table names during
 * logical-plan building, because downstream consumers match a
 * {@code ColumnRef}'s qualifier against scan table names:
 * {@code PredicatePushdown} (whether a filter can move below a join) and
 * {@code PhysicalNestedLoopJoin} (which side owns an ambiguous column).
 * Before canonicalization, aliased queries silently disabled pushdown and made
 * forced-NLJ plans return wrong results on 3-way joins.
 */
public class AliasResolutionTest {

    static Catalog catalog = new Catalog();

    static final String ALIASED_THREE_WAY_JOIN =
            "SELECT c.name, o.total, p.name FROM customers c "
                    + "INNER JOIN orders o ON c.id = o.customer_id "
                    + "INNER JOIN products p ON o.product_id = p.id "
                    + "WHERE c.city = 'Seattle'";

    @BeforeAll
    static void setup() throws IOException {
        Path dir = Paths.get("target/generated-test-resources");
        Files.createDirectories(dir);

        try (PrintWriter pw = new PrintWriter(new File(dir.toFile(), "alias_customers.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,city:VARCHAR,age:INTEGER");
            pw.println("1,Alice,Seattle,30");
            pw.println("2,Bob,Portland,25");
            pw.println("3,Charlie,Seattle,35");
            pw.println("4,Dana,Denver,41");
        }
        catalog.loadTableFromCSV("customers", dir + "/alias_customers.csv");

        try (PrintWriter pw = new PrintWriter(new File(dir.toFile(), "alias_products.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,category:VARCHAR,price:FLOAT");
            pw.println("1,Laptop,Electronics,999.99");
            pw.println("2,Mouse,Electronics,29.99");
            pw.println("3,Desk,Furniture,299.99");
        }
        catalog.loadTableFromCSV("products", dir + "/alias_products.csv");

        // Note: customer_id and product_id ranges overlap the id columns of the
        // joined tables, so a mis-bound ambiguous "id" column changes the result —
        // exactly the failure mode this test guards against.
        try (PrintWriter pw = new PrintWriter(new File(dir.toFile(), "alias_orders.csv"))) {
            pw.println("id:INTEGER,customer_id:INTEGER,product_id:INTEGER,total:FLOAT");
            pw.println("1,1,2,29.99");
            pw.println("2,1,1,999.99");
            pw.println("3,2,3,299.99");
            pw.println("4,3,2,29.99");
            pw.println("5,3,3,299.99");
            pw.println("6,4,1,999.99");
        }
        catalog.loadTableFromCSV("orders", dir + "/alias_orders.csv");
    }

    private LogicalNode buildLogical(String sql) {
        return new LogicalPlanBuilder(catalog).build(new SQLParser().parse(sql));
    }

    // -------------------------------------------------------------------------
    // Canonicalization at the logical layer
    // -------------------------------------------------------------------------

    @Test
    void filterAndJoinQualifiersResolveToTableNames() {
        LogicalNode plan = buildLogical(
                "SELECT c.name, o.total FROM customers c "
                        + "INNER JOIN orders o ON c.id = o.customer_id "
                        + "WHERE o.total > 100.0");

        List<Expression.ColumnRef> refs = collectColumnRefs(plan);
        assertFalse(refs.isEmpty());
        for (Expression.ColumnRef ref : refs) {
            if (ref.tableName() != null) {
                assertTrue(ref.tableName().equals("customers") || ref.tableName().equals("orders"),
                        "qualifier should be a table name, was: " + ref.tableName());
            }
        }
    }

    @Test
    void tableNameQualifiersPassThroughUnchanged() {
        LogicalNode plan = buildLogical(
                "SELECT customers.name FROM customers WHERE customers.city = 'Seattle'");

        for (Expression.ColumnRef ref : collectColumnRefs(plan)) {
            if (ref.tableName() != null) {
                assertEquals("customers", ref.tableName());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Rule behavior: pushdown must fire on aliased queries
    // -------------------------------------------------------------------------

    @Test
    void predicatePushdownFiresOnAliasedThreeWayJoin() {
        QueryOptimizer optimizer = new QueryOptimizer(catalog);
        PhysicalNode plan = optimizer
                .optimize(null, buildLogical(ALIASED_THREE_WAY_JOIN), OptimizationOptions.defaults())
                .physicalPlan();

        PhysicalFilter filter = findFilter(plan);
        assertNotNull(filter, "plan should retain the city filter");
        assertInstanceOf(PhysicalScan.class, filter.getChildren().get(0),
                "filter should be pushed onto the customers scan, not sit above the joins");
    }

    // -------------------------------------------------------------------------
    // Execution correctness: NLJ must agree with hash join on aliased joins
    // -------------------------------------------------------------------------

    @Test
    void nestedLoopJoinMatchesHashJoinOnAliasedThreeWayJoin() {
        QueryOptimizer optimizer = new QueryOptimizer(catalog);
        Executor executor = new Executor();

        ExecutionResult hash = executor.execute(optimizer
                .optimize(null, buildLogical(ALIASED_THREE_WAY_JOIN), options(JoinAlgorithmPolicy.FORCE_HASH))
                .physicalPlan());
        ExecutionResult nlj = executor.execute(optimizer
                .optimize(null, buildLogical(ALIASED_THREE_WAY_JOIN), options(JoinAlgorithmPolicy.FORCE_NLJ))
                .physicalPlan());

        // Ground truth: Seattle customers are Alice (orders 1, 2) and Charlie
        // (orders 4, 5), each order matching exactly one product -> 4 rows.
        assertEquals(4, hash.tuples().size());
        assertEquals(sortedRows(hash), sortedRows(nlj),
                "forced-NLJ plan must return the same rows as the hash-join plan");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static OptimizationOptions options(JoinAlgorithmPolicy joinAlgorithm) {
        return new OptimizationOptions(true, true, true, JoinOrderPolicy.DP, joinAlgorithm);
    }

    private static List<String> sortedRows(ExecutionResult result) {
        List<String> rows = new ArrayList<>();
        for (Tuple t : result.tuples()) {
            rows.add(t.toString());
        }
        rows.sort(String::compareTo);
        return rows;
    }

    private static List<Expression.ColumnRef> collectColumnRefs(LogicalNode node) {
        List<Expression.ColumnRef> refs = new ArrayList<>();
        collectColumnRefs(node, refs);
        return refs;
    }

    private static void collectColumnRefs(LogicalNode node, List<Expression.ColumnRef> out) {
        if (node instanceof LogicalFilter filter) {
            collectExprRefs(filter.getPredicate(), out);
        } else if (node instanceof LogicalProject project) {
            for (Expression e : project.getProjections()) {
                collectExprRefs(e, out);
            }
        } else if (node instanceof org.query.optimizer.parser.LogicalJoin join) {
            collectExprRefs(join.getCondition(), out);
        }
        for (LogicalNode child : node.getChildren()) {
            collectColumnRefs(child, out);
        }
    }

    private static void collectExprRefs(Expression expr, List<Expression.ColumnRef> out) {
        if (expr instanceof Expression.ColumnRef ref) {
            out.add(ref);
        } else if (expr instanceof Expression.BinaryOp op) {
            collectExprRefs(op.left(), out);
            collectExprRefs(op.right(), out);
        }
    }

    private static PhysicalFilter findFilter(PhysicalNode node) {
        if (node instanceof PhysicalFilter filter) {
            return filter;
        }
        for (PhysicalNode child : node.getChildren()) {
            PhysicalFilter found = findFilter(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
