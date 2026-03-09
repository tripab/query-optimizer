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
import org.query.optimizer.logical.Expression.Literal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link VectorizedFilter}.
 * <p>
 * Coverage:
 * - GT predicate: correct rows selected
 * - LT predicate: correct rows selected
 * - EQ predicate on INTEGER column
 * - NEQ predicate
 * - GTE / LTE boundary values included
 * - EQ predicate on VARCHAR column
 * - All-pass predicate: selection vector size equals batch size
 * - All-reject predicate: no batch returned (operator skips fully-filtered batches)
 * - AND predicate: intersection of two conditions
 * - OR predicate: union of two conditions
 * - Chained VectorizedFilter nodes (stacked filters intersect correctly)
 * - Selection vector is installed on the returned batch
 * - Multi-batch input: filter applied to every batch
 * - Output schema equals input schema
 */
class VectorizedFilterTest {

    private static final int BATCH = ColumnBatch.DEFAULT_BATCH_SIZE;

    private Schema schema;   // id:INTEGER, category:VARCHAR, price:FLOAT
    private Catalog catalog;

    @BeforeEach
    void setUp() {
        schema = new Schema(List.of(
                new Schema.Column("id", DataType.INTEGER),
                new Schema.Column("category", DataType.VARCHAR),
                new Schema.Column("price", DataType.FLOAT)
        ));
        catalog = new Catalog();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Rows: id=i, category="cat"+(i%3), price=i*1.0f for i in [0, rowCount).
     */
    private VectorizedScan scanOf(String tableName, int rowCount) {
        List<Map<Schema.Column, Object>> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            Map<Schema.Column, Object> row = new HashMap<>();
            row.put(schema.getColumn(0), i);
            row.put(schema.getColumn(1), "cat" + (i % 3));
            row.put(schema.getColumn(2), (float) i);
            rows.add(row);
        }
        catalog.registerTable(new TableMetadata(tableName, schema, rows));
        return new VectorizedScan(tableName, catalog);
    }

    private Expression gt(String col, int val) {
        return new BinaryOp(Operator.GT, ColumnRef.from(col), new Literal<>(val));
    }

    private Expression lt(String col, int val) {
        return new BinaryOp(Operator.LT, ColumnRef.from(col), new Literal<>(val));
    }

    private Expression eq(String col, int val) {
        return new BinaryOp(Operator.EQ, ColumnRef.from(col), new Literal<>(val));
    }

    private Expression eq(String col, String val) {
        return new BinaryOp(Operator.EQ, ColumnRef.from(col), new Literal<>(val));
    }

    private Expression neq(String col, int val) {
        return new BinaryOp(Operator.NEQ, ColumnRef.from(col), new Literal<>(val));
    }

    private Expression gte(String col, int val) {
        return new BinaryOp(Operator.GTE, ColumnRef.from(col), new Literal<>(val));
    }

    private Expression lte(String col, int val) {
        return new BinaryOp(Operator.LTE, ColumnRef.from(col), new Literal<>(val));
    }

    private Expression and(Expression l, Expression r) {
        return new BinaryOp(Operator.AND, l, r);
    }

    private Expression or(Expression l, Expression r) {
        return new BinaryOp(Operator.OR, l, r);
    }

    /**
     * Drain all selected row ids from a filter into a list.
     */
    private List<Integer> collectIds(VectorizedFilter filter) {
        filter.open();
        List<Integer> ids = new ArrayList<>();
        ColumnBatch batch;
        while ((batch = filter.next()) != null) {
            int[] sv = batch.getSelectionVector();
            int size = batch.getSelectionSize();
            for (int i = 0; i < size; i++) {
                ids.add(batch.getVector(0).getInt(sv[i]));
            }
        }
        filter.close();
        return ids;
    }

    // -------------------------------------------------------------------------
    // Comparison operators
    // -------------------------------------------------------------------------

    @Test
    void testGtSelectsOnlyRowsAboveThreshold() {
        int rowCount = 10;
        VectorizedFilter filter = new VectorizedFilter(scanOf("t", rowCount), gt("id", 5));
        List<Integer> ids = collectIds(filter);

        assertEquals(List.of(6, 7, 8, 9), ids);
    }

    @Test
    void testLtSelectsOnlyRowsBelowThreshold() {
        VectorizedFilter filter = new VectorizedFilter(scanOf("t", 10), lt("id", 3));
        List<Integer> ids = collectIds(filter);

        assertEquals(List.of(0, 1, 2), ids);
    }

    @Test
    void testEqIntegerSelectsExactlyOneRow() {
        VectorizedFilter filter = new VectorizedFilter(scanOf("t", 10), eq("id", 4));
        List<Integer> ids = collectIds(filter);

        assertEquals(List.of(4), ids);
    }

    @Test
    void testNeqIntegerExcludesOneRow() {
        VectorizedFilter filter = new VectorizedFilter(scanOf("t", 5), neq("id", 2));
        List<Integer> ids = collectIds(filter);

        assertEquals(List.of(0, 1, 3, 4), ids);
    }

    @Test
    void testGteInclusiveLowerBound() {
        VectorizedFilter filter = new VectorizedFilter(scanOf("t", 5), gte("id", 3));
        List<Integer> ids = collectIds(filter);

        assertEquals(List.of(3, 4), ids);
    }

    @Test
    void testLteInclusiveUpperBound() {
        VectorizedFilter filter = new VectorizedFilter(scanOf("t", 5), lte("id", 2));
        List<Integer> ids = collectIds(filter);

        assertEquals(List.of(0, 1, 2), ids);
    }

    @Test
    void testEqVarcharSelectsMatchingRows() {
        // category = "cat0" for ids 0, 3, 6, 9
        VectorizedFilter filter = new VectorizedFilter(scanOf("t", 10), eq("category", "cat0"));
        List<Integer> ids = collectIds(filter);

        assertEquals(List.of(0, 3, 6, 9), ids);
    }

    // -------------------------------------------------------------------------
    // All-pass and all-reject
    // -------------------------------------------------------------------------

    @Test
    void testAllPassSelectionVectorCoversAllRows() {
        int rowCount = 5;
        VectorizedFilter filter = new VectorizedFilter(scanOf("t", rowCount), gte("id", 0));
        filter.open();

        ColumnBatch batch = filter.next();
        assertNotNull(batch);
        assertTrue(batch.hasSelectionVector());
        assertEquals(rowCount, batch.getSelectionSize());

        filter.close();
    }

    @Test
    void testAllRejectNextReturnsNull() {
        VectorizedFilter filter = new VectorizedFilter(scanOf("t", 10), gt("id", 999));
        filter.open();
        assertNull(filter.next(), "All-reject filter must return null, not a zero-row batch");
        filter.close();
    }

    @Test
    void testAllRejectSpanningMultipleBatchesNextReturnsNull() {
        // Table larger than one batch, all rows filtered
        VectorizedFilter filter = new VectorizedFilter(
                scanOf("t", BATCH + 100), gt("id", BATCH + 200));
        filter.open();
        assertNull(filter.next());
        filter.close();
    }

    // -------------------------------------------------------------------------
    // AND / OR
    // -------------------------------------------------------------------------

    @Test
    void testAndIntersectsConditions() {
        // id > 2 AND id < 6  →  {3, 4, 5}
        VectorizedFilter filter = new VectorizedFilter(
                scanOf("t", 10), and(gt("id", 2), lt("id", 6)));
        List<Integer> ids = collectIds(filter);

        assertEquals(List.of(3, 4, 5), ids);
    }

    @Test
    void testOrUnionsConditions() {
        // id < 2 OR id > 7  →  {0, 1, 8, 9}
        VectorizedFilter filter = new VectorizedFilter(
                scanOf("t", 10), or(lt("id", 2), gt("id", 7)));
        List<Integer> ids = collectIds(filter);

        assertEquals(List.of(0, 1, 8, 9), ids);
    }

    @Test
    void testAndLeftFalseShortCircuits() {
        // id > 100 AND id < 5  →  {} (left eliminates everything)
        VectorizedFilter filter = new VectorizedFilter(
                scanOf("t", 10), and(gt("id", 100), lt("id", 5)));
        filter.open();
        assertNull(filter.next());
        filter.close();
    }

    // -------------------------------------------------------------------------
    // Chained filters (stacked VectorizedFilter nodes)
    // -------------------------------------------------------------------------

    @Test
    void testChainedFiltersIntersectCorrectly() {
        // First filter: id > 1  →  {2..9}
        // Second filter: id < 5 →  {2, 3, 4}
        VectorizedScan scan = scanOf("t", 10);
        VectorizedFilter inner = new VectorizedFilter(scan, gt("id", 1));
        VectorizedFilter outer = new VectorizedFilter(inner, lt("id", 5));

        List<Integer> ids = collectIds(outer);
        assertEquals(List.of(2, 3, 4), ids);
    }

    // -------------------------------------------------------------------------
    // Selection vector on returned batch
    // -------------------------------------------------------------------------

    @Test
    void testReturnedBatchHasSelectionVectorInstalled() {
        VectorizedFilter filter = new VectorizedFilter(scanOf("t", 10), gt("id", 3));
        filter.open();

        ColumnBatch batch = filter.next();
        assertNotNull(batch);
        assertTrue(batch.hasSelectionVector());

        filter.close();
    }

    @Test
    void testSelectionVectorContainsOnlyQualifyingPhysicalIndices() {
        // id > 7 in a 10-row batch → physical rows 8, 9
        VectorizedFilter filter = new VectorizedFilter(scanOf("t", 10), gt("id", 7));
        filter.open();

        ColumnBatch batch = filter.next();
        int[] sv = batch.getSelectionVector();
        int size = batch.getSelectionSize();

        assertEquals(2, size);
        assertEquals(8, sv[0]);
        assertEquals(9, sv[1]);

        filter.close();
    }

    // -------------------------------------------------------------------------
    // Multi-batch input
    // -------------------------------------------------------------------------

    @Test
    void testMultiBatchInputFilterAppliedToEveryBatch() {
        // BATCH + 10 rows; keep only even ids
        int totalRows = BATCH + 10;
        VectorizedFilter filter = new VectorizedFilter(
                scanOf("t", totalRows),
                new BinaryOp(Operator.EQ,
                        ColumnRef.from("id"),
                        new Literal<>(0)));  // id == 0 → exactly 1 row across all batches

        List<Integer> ids = collectIds(filter);
        assertEquals(List.of(0), ids);
    }

    @Test
    void testMultiBatchInputTotalSelectedCount() {
        // 2*BATCH rows, keep id < BATCH/2
        int threshold = BATCH / 2;
        VectorizedFilter filter = new VectorizedFilter(
                scanOf("t", BATCH * 2), lt("id", threshold));

        List<Integer> ids = collectIds(filter);
        assertEquals(threshold, ids.size());
    }

    // -------------------------------------------------------------------------
    // Output schema
    // -------------------------------------------------------------------------

    @Test
    void testOutputSchemaIdenticalToInputSchema() {
        VectorizedFilter filter = new VectorizedFilter(scanOf("t", 5), gt("id", 0));
        assertSame(filter.getOutputSchema(), new VectorizedScan("t", catalog).getOutputSchema());
    }

    @Test
    void testOutputSchemaSafeBeforeOpen() {
        VectorizedFilter filter = new VectorizedFilter(scanOf("t", 5), gt("id", 0));
        assertDoesNotThrow(filter::getOutputSchema);
        assertEquals(3, filter.getOutputSchema().columnCount());
    }
}
