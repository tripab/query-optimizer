package org.query.optimizer.vectorized;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.DataType;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.TableMetadata;
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
 * Unit tests for {@link VectorizedProject}.
 * <p>
 * Coverage:
 * - Single column projection: only the requested column is in the output batch
 * - Multi-column projection: correct subset, correct order
 * - All-columns projection: output matches input exactly
 * - Column reordering: output columns appear in the requested order
 * - Values are correct after projection (vector reference sharing)
 * - Selection vector propagated from input batch unchanged
 * - Output batch size equals input batch size
 * - Batch reuse: same ColumnBatch instance returned on every call
 * - Unknown column name throws at open()
 * - getOutputSchema() safe before open(), matches requested columns
 * - Projection on top of filter: selection vector propagated correctly
 */
class VectorizedProjectTest {

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

    private VectorizedScan scanOf(String tableName, int rowCount) {
        List<Map<Schema.Column, Object>> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            Map<Schema.Column, Object> row = new HashMap<>();
            row.put(schema.getColumn(0), i);
            row.put(schema.getColumn(1), "name" + i);
            row.put(schema.getColumn(2), i * 2.0f);
            rows.add(row);
        }
        catalog.registerTable(new TableMetadata(tableName, schema, rows));
        return new VectorizedScan(tableName, catalog);
    }

    // -------------------------------------------------------------------------
    // Column subsetting
    // -------------------------------------------------------------------------

    @Test
    void testTestSingleColumnOnlyRequestedColumnInOutput() {
        VectorizedProject project = new VectorizedProject(scanOf("t", 5), List.of("name"));
        project.open();

        ColumnBatch batch = project.next();
        assertNotNull(batch);
        assertEquals(1, batch.getSchema().columnCount());
        assertEquals("name", batch.getSchema().getColumn(0).name());

        project.close();
    }

    @Test
    void testTwoColumnsCorrectSubsetInOrder() {
        VectorizedProject project = new VectorizedProject(
                scanOf("t", 5), List.of("id", "price"));
        project.open();

        ColumnBatch batch = project.next();
        Schema s = batch.getSchema();

        assertEquals(2, s.columnCount());
        assertEquals("id", s.getColumn(0).name());
        assertEquals("price", s.getColumn(1).name());
        assertEquals(DataType.INTEGER, s.getColumn(0).type());
        assertEquals(DataType.FLOAT, s.getColumn(1).type());

        project.close();
    }

    @Test
    void testAllColumnsOutputMatchesInput() {
        VectorizedProject project = new VectorizedProject(
                scanOf("t", 5), List.of("id", "name", "price"));
        project.open();

        ColumnBatch batch = project.next();
        assertEquals(3, batch.getSchema().columnCount());

        project.close();
    }

    @Test
    void testColumnReorderingOutputInRequestedOrder() {
        // Request price then id — reversed from schema order
        VectorizedProject project = new VectorizedProject(
                scanOf("t", 5), List.of("price", "id"));
        project.open();

        ColumnBatch batch = project.next();
        assertEquals("price", batch.getSchema().getColumn(0).name());
        assertEquals("id", batch.getSchema().getColumn(1).name());

        project.close();
    }

    // -------------------------------------------------------------------------
    // Values
    // -------------------------------------------------------------------------

    @Test
    void testValuesCorrectAfterProjection() {
        VectorizedProject project = new VectorizedProject(
                scanOf("t", 5), List.of("name", "price"));
        project.open();

        ColumnBatch batch = project.next();
        for (int i = 0; i < 5; i++) {
            assertEquals("name" + i, batch.getVector(0).getString(i), "name mismatch at row " + i);
            assertEquals(i * 2.0f, batch.getVector(1).getFloat(i), 1e-6f, "price mismatch at row " + i);
        }

        project.close();
    }

    @Test
    void testValuesSingleColumnProjectionMatchesSource() {
        VectorizedProject project = new VectorizedProject(scanOf("t", 10), List.of("id"));
        project.open();

        ColumnBatch batch = project.next();
        for (int i = 0; i < 10; i++) {
            assertEquals(i, batch.getVector(0).getInt(i));
        }

        project.close();
    }

    // -------------------------------------------------------------------------
    // Batch size propagation
    // -------------------------------------------------------------------------

    @Test
    void testBatchSizePropagatedFromInput() {
        VectorizedProject project = new VectorizedProject(scanOf("t", 7), List.of("id"));
        project.open();

        ColumnBatch batch = project.next();
        assertEquals(7, batch.getSize());

        project.close();
    }

    // -------------------------------------------------------------------------
    // Batch reuse
    // -------------------------------------------------------------------------

    @Test
    void testNextReturnsSameBatchInstanceEveryCall() {
        VectorizedProject project = new VectorizedProject(
                scanOf("t", ColumnBatch.DEFAULT_BATCH_SIZE * 2), List.of("id"));
        project.open();

        ColumnBatch first = project.next();
        ColumnBatch second = project.next();
        assertSame(first, second, "VectorizedProject must reuse the same ColumnBatch instance");

        project.close();
    }

    // -------------------------------------------------------------------------
    // Selection vector propagation
    // -------------------------------------------------------------------------

    @Test
    void testSelectionVectorPropagatedFromFilteredInput() {
        VectorizedScan scan = scanOf("t", 10);
        VectorizedFilter filter = new VectorizedFilter(scan,
                new BinaryOp(Operator.GT, ColumnRef.from("id"), new Literal<>(5)));
        VectorizedProject project = new VectorizedProject(filter, List.of("name"));

        project.open();
        ColumnBatch batch = project.next();

        assertNotNull(batch);
        assertTrue(batch.hasSelectionVector(),
                "VectorizedProject must propagate the selection vector from its input");
        // id > 5 → rows 6,7,8,9 selected (4 rows)
        assertEquals(4, batch.getSelectionSize());

        project.close();
    }

    @Test
    void testNoSelectionVectorPropagatedWhenInputIsUnfiltered() {
        VectorizedProject project = new VectorizedProject(scanOf("t", 5), List.of("id"));
        project.open();

        ColumnBatch batch = project.next();
        assertFalse(batch.hasSelectionVector(),
                "VectorizedProject must not add a selection vector when input has none");

        project.close();
    }

    @Test
    void testProjectedValuesCorrectUnderSelectionVector() {
        // Filter id > 7 (rows 8, 9), then project name
        VectorizedScan scan = scanOf("t", 10);
        VectorizedFilter filter = new VectorizedFilter(scan,
                new BinaryOp(Operator.GT, ColumnRef.from("id"), new Literal<>(7)));
        VectorizedProject project = new VectorizedProject(filter, List.of("name"));

        project.open();
        ColumnBatch batch = project.next();

        int[] sv = batch.getSelectionVector();
        int size = batch.getSelectionSize();
        assertEquals(2, size);
        assertEquals("name8", batch.getVector(0).getString(sv[0]));
        assertEquals("name9", batch.getVector(0).getString(sv[1]));

        project.close();
    }

    // -------------------------------------------------------------------------
    // Null returns when input exhausted
    // -------------------------------------------------------------------------

    @Test
    void testNextReturnsNullWhenInputExhausted() {
        VectorizedProject project = new VectorizedProject(scanOf("t", 3), List.of("id"));
        project.open();
        assertNotNull(project.next());
        assertNull(project.next());
        project.close();
    }

    // -------------------------------------------------------------------------
    // Unknown column
    // -------------------------------------------------------------------------

    @Test
    void testUnknownColumnThrowsAtOpen() {
        VectorizedProject project = new VectorizedProject(
                scanOf("t", 5), List.of("nonexistent"));
        assertThrows(IllegalArgumentException.class, project::open);
    }

    // -------------------------------------------------------------------------
    // getOutputSchema()
    // -------------------------------------------------------------------------

    @Test
    void testGetOutputSchemaSafeBeforeOpen() {
        VectorizedProject project = new VectorizedProject(
                scanOf("t", 5), List.of("name", "price"));
        assertDoesNotThrow(project::getOutputSchema);
    }

    @Test
    void testGetOutputSchemaReflectsRequestedColumns() {
        VectorizedProject project = new VectorizedProject(
                scanOf("t", 5), List.of("price", "id"));
        Schema s = project.getOutputSchema();

        assertEquals(2, s.columnCount());
        assertEquals("price", s.getColumn(0).name());
        assertEquals("id", s.getColumn(1).name());
    }

    @Test
    void testGetOutputSchemaAfterOpenMatchesBeforeOpen() {
        VectorizedProject project = new VectorizedProject(
                scanOf("t", 5), List.of("name"));

        Schema before = project.getOutputSchema();
        project.open();
        Schema after = project.getOutputSchema();
        project.close();

        assertEquals(before.columnCount(), after.columnCount());
        assertEquals(before.getColumn(0).name(), after.getColumn(0).name());
    }
}
