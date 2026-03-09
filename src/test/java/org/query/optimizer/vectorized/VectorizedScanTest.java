package org.query.optimizer.vectorized;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.DataType;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.TableMetadata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link VectorizedScan}.
 * <p>
 * Coverage:
 * - Empty table: next() returns null immediately after open()
 * - Table with exactly DEFAULTBATCHSIZE rows: one full batch then null
 * - Table with DEFAULTBATCHSIZE + 1 rows: two batches, last has 1 row
 * - Multi-batch scan: correct values in every batch across all columns
 * - Batch instance reuse: same ColumnBatch object returned on every call
 * - No selection vector on scan output (scan always returns dense batches)
 * - close() then open() resets cursor (re-entrant scan)
 * - getOutputSchema() safe to call before open()
 * - getOutputSchema() schema matches registered table schema
 */
class VectorizedScanTest {

    private static final int BATCH = ColumnBatch.DEFAULT_BATCH_SIZE;

    private Schema schema;   // id:INTEGER, name:VARCHAR, price:FLOAT
    private Catalog catalog;

    @BeforeEach
    void setUp() {
        schema = new Schema(List.of(
                new Schema.Column("id", DataType.INTEGER),
                new Schema.Column("name", DataType.VARCHAR),
                new Schema.Column("price", DataType.FLOAT)
        ));
        catalog = new Catalog();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a TableMetadata with {@code rowCount} rows: id=i, name="n"+i, price=i*0.5f
     */
    private TableMetadata buildTable(String name, int rowCount) {
        List<Map<Schema.Column, Object>> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            Map<Schema.Column, Object> row = new HashMap<>();
            row.put(schema.getColumn(0), i);
            row.put(schema.getColumn(1), "n" + i);
            row.put(schema.getColumn(2), i * 0.5f);
            rows.add(row);
        }
        return new TableMetadata(name, schema, rows);
    }

    private VectorizedScan scanOf(String tableName, int rowCount) {
        catalog.registerTable(buildTable(tableName, rowCount));
        return new VectorizedScan(tableName, catalog);
    }

    // -------------------------------------------------------------------------
    // Empty table
    // -------------------------------------------------------------------------

    @Test
    void testEmptyTableFirstNextReturnsNull() {
        VectorizedScan scan = scanOf("t", 0);
        scan.open();
        assertNull(scan.next());
        scan.close();
    }

    // -------------------------------------------------------------------------
    // Exactly one full batch
    // -------------------------------------------------------------------------

    @Test
    void testExactlyBatchSizeRowsOneFullBatchThenNull() {
        VectorizedScan scan = scanOf("t", BATCH);
        scan.open();

        ColumnBatch batch = scan.next();
        assertNotNull(batch);
        assertEquals(BATCH, batch.getSize());
        assertNull(scan.next());

        scan.close();
    }

    @Test
    void testExactlyBatchSizeRowsValuesCorrect() {
        VectorizedScan scan = scanOf("t", BATCH);
        scan.open();
        ColumnBatch batch = scan.next();

        for (int i = 0; i < BATCH; i++) {
            assertEquals(i, batch.getVector(0).getInt(i), "id mismatch at " + i);
            assertEquals("n" + i, batch.getVector(1).getString(i), "name mismatch at " + i);
            assertEquals(i * 0.5f, batch.getVector(2).getFloat(i), 1e-6f, "price mismatch at " + i);
        }
        scan.close();
    }

    // -------------------------------------------------------------------------
    // Partial last batch
    // -------------------------------------------------------------------------

    @Test
    void testBatchSizePlusOneTwoBatchesSecondHasOneRow() {
        VectorizedScan scan = scanOf("t", BATCH + 1);
        scan.open();

        ColumnBatch first = scan.next();
        assertNotNull(first);
        assertEquals(BATCH, first.getSize());

        ColumnBatch second = scan.next();
        assertNotNull(second);
        assertEquals(1, second.getSize());

        assertNull(scan.next());
        scan.close();
    }

    @Test
    void testPartialLastBatchValuesAreCorrect() {
        int totalRows = BATCH + 3;
        VectorizedScan scan = scanOf("t", totalRows);
        scan.open();

        scan.next(); // skip first full batch

        ColumnBatch last = scan.next();
        assertNotNull(last);
        assertEquals(3, last.getSize());

        // Rows BATCH, BATCH+1, BATCH+2
        for (int i = 0; i < 3; i++) {
            int expectedId = BATCH + i;
            assertEquals(expectedId, last.getVector(0).getInt(i));
            assertEquals("n" + expectedId, last.getVector(1).getString(i));
            assertEquals(expectedId * 0.5f, last.getVector(2).getFloat(i), 1e-6f);
        }
        scan.close();
    }

    // -------------------------------------------------------------------------
    // Multi-batch scan: all values across all batches
    // -------------------------------------------------------------------------

    @Test
    void testMultiBatchScanAllRowsProducedInOrder() {
        int totalRows = BATCH * 3 + 7;
        VectorizedScan scan = scanOf("t", totalRows);
        scan.open();

        List<Integer> ids = new ArrayList<>(totalRows);
        ColumnBatch batch;
        while ((batch = scan.next()) != null) {
            int size = batch.getSize();
            for (int i = 0; i < size; i++) {
                ids.add(batch.getVector(0).getInt(i));
            }
        }
        scan.close();

        assertEquals(totalRows, ids.size());
        for (int i = 0; i < totalRows; i++) {
            assertEquals(i, ids.get(i), "Wrong id at position " + i);
        }
    }

    @Test
    void testMultiBatchScanTotalRowCount() {
        int totalRows = BATCH * 2 + 100;
        VectorizedScan scan = scanOf("t", totalRows);
        scan.open();

        int count = 0;
        ColumnBatch batch;
        while ((batch = scan.next()) != null) {
            count += batch.getSize();
        }
        scan.close();

        assertEquals(totalRows, count);
    }

    // -------------------------------------------------------------------------
    // Batch instance reuse
    // -------------------------------------------------------------------------

    @Test
    void testNextReturnsSameBatchInstanceEveryCall() {
        VectorizedScan scan = scanOf("t", BATCH * 2);
        scan.open();

        ColumnBatch first = scan.next();
        ColumnBatch second = scan.next();
        assertSame(first, second, "VectorizedScan must reuse the same ColumnBatch instance");

        scan.close();
    }

    // -------------------------------------------------------------------------
    // No selection vector on scan output
    // -------------------------------------------------------------------------

    @Test
    void testNextBatchHasNoSelectionVector() {
        VectorizedScan scan = scanOf("t", 10);
        scan.open();

        ColumnBatch batch = scan.next();
        assertFalse(batch.hasSelectionVector(),
                "VectorizedScan must not install a selection vector");

        scan.close();
    }

    // -------------------------------------------------------------------------
    // Re-entrant scan (close then open resets cursor)
    // -------------------------------------------------------------------------

    @Test
    void testCloseAndReopenResetsToStart() {
        VectorizedScan scan = scanOf("t", 5);

        scan.open();
        ColumnBatch firstRun = scan.next();
        assertNotNull(firstRun);
        int firstId = firstRun.getVector(0).getInt(0);
        scan.close();

        scan.open();
        ColumnBatch secondRun = scan.next();
        assertNotNull(secondRun);
        int secondId = secondRun.getVector(0).getInt(0);
        scan.close();

        assertEquals(firstId, secondId, "Re-opened scan must restart from row 0");
    }

    // -------------------------------------------------------------------------
    // getOutputSchema()
    // -------------------------------------------------------------------------

    @Test
    void testGetOutputSchemaSafeBeforeOpen() {
        VectorizedScan scan = scanOf("t", 1);
        Schema s = assertDoesNotThrow(scan::getOutputSchema);
        assertNotNull(s);
    }

    @Test
    void testGetOutputSchemaMatchesRegisteredTableSchema() {
        VectorizedScan scan = scanOf("t", 1);
        Schema s = scan.getOutputSchema();

        assertEquals(3, s.columnCount());
        assertEquals(DataType.INTEGER, s.getColumn("id").type());
        assertEquals(DataType.VARCHAR, s.getColumn("name").type());
        assertEquals(DataType.FLOAT, s.getColumn("price").type());
    }
}
