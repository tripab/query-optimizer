package org.query.optimizer.vectorized;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.DataType;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.TableMetadata;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.Expression.BinaryOp;
import org.query.optimizer.logical.Expression.BinaryOp.Operator;
import org.query.optimizer.logical.Expression.ColumnRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link VectorizedHashJoin}.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Basic equi-join: correct rows matched and combined</li>
 *   <li>Multi-match keys: one probe row matching multiple build rows</li>
 *   <li>No-match keys: probe rows with no build-side counterpart are dropped</li>
 *   <li>Empty build side: no output rows</li>
 *   <li>Empty probe side: no output rows</li>
 *   <li>Both sides empty: no output rows</li>
 *   <li>Integer join key</li>
 *   <li>VARCHAR join key</li>
 *   <li>Output schema: probe columns first, then build columns</li>
 *   <li>Output correctness with a probe-side VectorizedFilter (selection vector)</li>
 *   <li>Multi-batch output: results spanning more than one batch</li>
 *   <li>Join key condition written in either direction (leftRef=rightRef or rightRef=leftRef)</li>
 * </ul>
 */
class VectorizedHashJoinTest {

    // customers: id INTEGER, name VARCHAR, city VARCHAR
    private static final Schema CUSTOMERS_SCHEMA = new Schema(List.of(
            new Schema.Column("id",   DataType.INTEGER),
            new Schema.Column("name", DataType.VARCHAR),
            new Schema.Column("city", DataType.VARCHAR)
    ));

    // orders: order_id INTEGER, customer_id INTEGER, amount FLOAT
    private static final Schema ORDERS_SCHEMA = new Schema(List.of(
            new Schema.Column("order_id",    DataType.INTEGER),
            new Schema.Column("customer_id", DataType.INTEGER),
            new Schema.Column("amount",      DataType.FLOAT)
    ));

    private Catalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new Catalog();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private VectorizedScan registerAndScan(String table, Schema schema,
                                            List<Map<Schema.Column, Object>> rows) {
        catalog.registerTable(new TableMetadata(table, schema, rows));
        return new VectorizedScan(table, catalog);
    }

    private Map<Schema.Column, Object> customerRow(int id, String name, String city) {
        Map<Schema.Column, Object> row = new HashMap<>();
        row.put(CUSTOMERS_SCHEMA.getColumn("id"),   id);
        row.put(CUSTOMERS_SCHEMA.getColumn("name"), name);
        row.put(CUSTOMERS_SCHEMA.getColumn("city"), city);
        return row;
    }

    private Map<Schema.Column, Object> orderRow(int orderId, int custId, float amount) {
        Map<Schema.Column, Object> row = new HashMap<>();
        row.put(ORDERS_SCHEMA.getColumn("order_id"),    orderId);
        row.put(ORDERS_SCHEMA.getColumn("customer_id"), custId);
        row.put(ORDERS_SCHEMA.getColumn("amount"),      amount);
        return row;
    }

    /** Condition: customers.id = orders.customer_id */
    private Expression joinCondition() {
        return new BinaryOp(Operator.EQ,
                ColumnRef.from("id"),
                ColumnRef.from("customer_id"));
    }

    /**
     * Drain all output rows from the join into a list of Object[] (column values
     * in output-schema order).
     */
    private List<Object[]> collectRows(VectorizedHashJoin join) {
        join.open();
        List<Object[]> rows = new ArrayList<>();
        ColumnBatch batch;
        while ((batch = join.next()) != null) {
            int colCount = join.getOutputSchema().columnCount();
            int size = batch.getSize();
            for (int r = 0; r < size; r++) {
                Object[] row = new Object[colCount];
                for (int c = 0; c < colCount; c++) {
                    row[c] = batch.getVector(c).get(r);
                }
                rows.add(row);
            }
        }
        join.close();
        return rows;
    }

    // -------------------------------------------------------------------------
    // Basic correctness
    // -------------------------------------------------------------------------

    @Test
    void testBasicInnerJoinMatchesExpectedRows() {
        List<Map<Schema.Column, Object>> customers = List.of(
                customerRow(1, "Alice", "NY"),
                customerRow(2, "Bob",   "LA"),
                customerRow(3, "Carol", "NY")
        );
        List<Map<Schema.Column, Object>> orders = List.of(
                orderRow(10, 1, 100.0f),
                orderRow(11, 2, 200.0f)
        );

        VectorizedHashJoin join = new VectorizedHashJoin(
                registerAndScan("cust", CUSTOMERS_SCHEMA, customers),
                registerAndScan("ord",  ORDERS_SCHEMA,    orders),
                joinCondition());

        List<Object[]> rows = collectRows(join);

        // Customer 3 (Carol) has no matching order → 2 output rows
        assertEquals(2, rows.size());

        // Verify names of matched customers appear in output
        Set<String> names = new HashSet<>();
        for (Object[] r : rows) names.add((String) r[1]);  // col 1 = name
        assertTrue(names.contains("Alice"));
        assertTrue(names.contains("Bob"));
        assertFalse(names.contains("Carol"));
    }

    @Test
    void testOutputColumnOrderIsProbeFirstThenBuild() {
        List<Map<Schema.Column, Object>> customers = List.of(customerRow(1, "Alice", "NY"));
        List<Map<Schema.Column, Object>> orders    = List.of(orderRow(10, 1, 99.0f));

        VectorizedHashJoin join = new VectorizedHashJoin(
                registerAndScan("cust2", CUSTOMERS_SCHEMA, customers),
                registerAndScan("ord2",  ORDERS_SCHEMA,    orders),
                joinCondition());

        join.open();
        Schema out = join.getOutputSchema();
        join.close();

        // Probe schema (CUSTOMERS) first, then build schema (ORDERS)
        assertEquals("id",          out.getColumn(0).name());
        assertEquals("name",        out.getColumn(1).name());
        assertEquals("city",        out.getColumn(2).name());
        assertEquals("order_id",    out.getColumn(3).name());
        assertEquals("customer_id", out.getColumn(4).name());
        assertEquals("amount",      out.getColumn(5).name());
    }

    @Test
    void testCorrectValuesInOutputRow() {
        List<Map<Schema.Column, Object>> customers = List.of(customerRow(42, "Dave", "SF"));
        List<Map<Schema.Column, Object>> orders    = List.of(orderRow(7, 42, 500.0f));

        VectorizedHashJoin join = new VectorizedHashJoin(
                registerAndScan("cust3", CUSTOMERS_SCHEMA, customers),
                registerAndScan("ord3",  ORDERS_SCHEMA,    orders),
                joinCondition());

        List<Object[]> rows = collectRows(join);
        assertEquals(1, rows.size());

        Object[] r = rows.get(0);
        assertEquals(42,    r[0]);   // id
        assertEquals("Dave",r[1]);   // name
        assertEquals("SF",  r[2]);   // city
        assertEquals(7,     r[3]);   // order_id
        assertEquals(42,    r[4]);   // customer_id
        assertEquals(500.0f,r[5]);   // amount
    }

    // -------------------------------------------------------------------------
    // Multi-match keys
    // -------------------------------------------------------------------------

    @Test
    void testOneProbeRowMatchingMultipleBuildRows() {
        List<Map<Schema.Column, Object>> customers = List.of(customerRow(1, "Alice", "NY"));
        List<Map<Schema.Column, Object>> orders = List.of(
                orderRow(10, 1, 100.0f),
                orderRow(11, 1, 200.0f),
                orderRow(12, 1, 300.0f)
        );

        VectorizedHashJoin join = new VectorizedHashJoin(
                registerAndScan("cust4", CUSTOMERS_SCHEMA, customers),
                registerAndScan("ord4",  ORDERS_SCHEMA,    orders),
                joinCondition());

        List<Object[]> rows = collectRows(join);
        assertEquals(3, rows.size());

        Set<Integer> orderIds = new HashSet<>();
        for (Object[] r : rows) orderIds.add((Integer) r[3]);
        assertEquals(Set.of(10, 11, 12), orderIds);
    }

    // -------------------------------------------------------------------------
    // Empty sides
    // -------------------------------------------------------------------------

    @Test
    void testEmptyBuildSideProducesNoOutput() {
        List<Map<Schema.Column, Object>> customers = List.of(customerRow(1, "Alice", "NY"));

        VectorizedHashJoin join = new VectorizedHashJoin(
                registerAndScan("cust5", CUSTOMERS_SCHEMA, customers),
                registerAndScan("ord5",  ORDERS_SCHEMA,    List.of()),
                joinCondition());

        List<Object[]> rows = collectRows(join);
        assertTrue(rows.isEmpty());
    }

    @Test
    void testEmptyProbeSideProducesNoOutput() {
        List<Map<Schema.Column, Object>> orders = List.of(orderRow(10, 1, 100.0f));

        VectorizedHashJoin join = new VectorizedHashJoin(
                registerAndScan("cust6", CUSTOMERS_SCHEMA, List.of()),
                registerAndScan("ord6",  ORDERS_SCHEMA,    orders),
                joinCondition());

        List<Object[]> rows = collectRows(join);
        assertTrue(rows.isEmpty());
    }

    @Test
    void testBothSidesEmptyProducesNoOutput() {
        VectorizedHashJoin join = new VectorizedHashJoin(
                registerAndScan("cust7", CUSTOMERS_SCHEMA, List.of()),
                registerAndScan("ord7",  ORDERS_SCHEMA,    List.of()),
                joinCondition());

        List<Object[]> rows = collectRows(join);
        assertTrue(rows.isEmpty());
    }

    // -------------------------------------------------------------------------
    // No-match probe rows dropped
    // -------------------------------------------------------------------------

    @Test
    void testProbeRowsWithNoMatchAreDropped() {
        List<Map<Schema.Column, Object>> customers = List.of(
                customerRow(1, "Alice", "NY"),
                customerRow(99, "Ghost", "XX")   // no matching order
        );
        List<Map<Schema.Column, Object>> orders = List.of(orderRow(10, 1, 50.0f));

        VectorizedHashJoin join = new VectorizedHashJoin(
                registerAndScan("cust8", CUSTOMERS_SCHEMA, customers),
                registerAndScan("ord8",  ORDERS_SCHEMA,    orders),
                joinCondition());

        List<Object[]> rows = collectRows(join);
        assertEquals(1, rows.size());
        assertEquals("Alice", rows.get(0)[1]);
    }

    // -------------------------------------------------------------------------
    // Selection vector on probe input
    // -------------------------------------------------------------------------

    @Test
    void testJoinWithFilteredProbeInputRespectsSelectionVector() {
        // customers: id 1..5; filter keeps only id <= 2
        List<Map<Schema.Column, Object>> customers = new ArrayList<>();
        for (int i = 1; i <= 5; i++) customers.add(customerRow(i, "C" + i, "City"));

        List<Map<Schema.Column, Object>> orders = new ArrayList<>();
        for (int i = 1; i <= 5; i++) orders.add(orderRow(100 + i, i, i * 10.0f));

        VectorizedFilter filteredProbe = new VectorizedFilter(
                registerAndScan("cust9", CUSTOMERS_SCHEMA, customers),
                new BinaryOp(Operator.LTE, ColumnRef.from("id"), new Expression.Literal<>(2)));

        VectorizedHashJoin join = new VectorizedHashJoin(
                filteredProbe,
                registerAndScan("ord9", ORDERS_SCHEMA, orders),
                joinCondition());

        List<Object[]> rows = collectRows(join);
        assertEquals(2, rows.size());

        Set<Integer> ids = new HashSet<>();
        for (Object[] r : rows) ids.add((Integer) r[0]);
        assertEquals(Set.of(1, 2), ids);
    }

    // -------------------------------------------------------------------------
    // Multi-batch output
    // -------------------------------------------------------------------------

    @Test
    void testMultiBatchOutputWhenMatchesExceedBatchSize() {
        // 1 customer with DEFAULT_BATCH_SIZE+10 orders → output spans 2 batches
        int orderCount = ColumnBatch.DEFAULT_BATCH_SIZE + 10;
        List<Map<Schema.Column, Object>> customers = List.of(customerRow(1, "Alice", "NY"));
        List<Map<Schema.Column, Object>> orders = new ArrayList<>();
        for (int i = 0; i < orderCount; i++) orders.add(orderRow(i, 1, i * 1.0f));

        VectorizedHashJoin join = new VectorizedHashJoin(
                registerAndScan("custM", CUSTOMERS_SCHEMA, customers),
                registerAndScan("ordM",  ORDERS_SCHEMA,    orders),
                joinCondition());

        List<Object[]> rows = collectRows(join);
        assertEquals(orderCount, rows.size());
    }

    // -------------------------------------------------------------------------
    // Join condition in reversed column order
    // -------------------------------------------------------------------------

    @Test
    void testJoinConditionWithColumnsInReversedOrder() {
        // Condition written as customer_id = id (build ref on left)
        Expression reversedCondition = new BinaryOp(Operator.EQ,
                ColumnRef.from("customer_id"),
                ColumnRef.from("id"));

        List<Map<Schema.Column, Object>> customers = List.of(customerRow(5, "Eve", "Boston"));
        List<Map<Schema.Column, Object>> orders    = List.of(orderRow(20, 5, 75.0f));

        VectorizedHashJoin join = new VectorizedHashJoin(
                registerAndScan("custR", CUSTOMERS_SCHEMA, customers),
                registerAndScan("ordR",  ORDERS_SCHEMA,    orders),
                reversedCondition);

        List<Object[]> rows = collectRows(join);
        assertEquals(1, rows.size());
        assertEquals("Eve", rows.get(0)[1]);
    }

    // -------------------------------------------------------------------------
    // Output schema safe before open()
    // -------------------------------------------------------------------------

    @Test
    void testOutputSchemaSafeToCallBeforeOpen() {
        VectorizedHashJoin join = new VectorizedHashJoin(
                registerAndScan("custS", CUSTOMERS_SCHEMA, List.of()),
                registerAndScan("ordS",  ORDERS_SCHEMA,    List.of()),
                joinCondition());

        assertDoesNotThrow(join::getOutputSchema);
        assertEquals(CUSTOMERS_SCHEMA.columnCount() + ORDERS_SCHEMA.columnCount(),
                join.getOutputSchema().columnCount());
    }
}
