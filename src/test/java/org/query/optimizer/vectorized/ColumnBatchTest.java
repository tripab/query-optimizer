package org.query.optimizer.vectorized;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.DataType;
import org.query.optimizer.catalog.Schema;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ColumnBatch}.
 *
 * Coverage:
 *  - Construction from Schema (DEFAULT_BATCH_SIZE and custom capacity)
 *  - wrap() factory validates vector count
 *  - getVector(int) and getVector(String) return correct vectors
 *  - setSize / getSize validation
 *  - Selection vector lifecycle:
 *      - initially no selection vector (hasSelectionVector == false)
 *      - setSelectionVector installs a selection, getSelectionSize returns count
 *      - getSelectionSize falls back to batch size when no selection is active
 *      - resetSelectionVector clears selection flag
 *      - setSelectionVector rejects null array and invalid counts
 *  - materializeRow returns correct boxed values
 */
class ColumnBatchTest {

    private Schema threeColSchema;  // INTEGER id, VARCHAR name, FLOAT price

    @BeforeEach
    void buildSchema() {
        threeColSchema = new Schema(List.of(
                new Schema.Column("id",    DataType.INTEGER),
                new Schema.Column("name",  DataType.VARCHAR),
                new Schema.Column("price", DataType.FLOAT)
        ));
    }

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    @Test
    void defaultConstructor_allocatesOneVectorPerColumn() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);

        assertEquals(3, threeColSchema.columnCount());
        assertNotNull(batch.getVector(0));
        assertNotNull(batch.getVector(1));
        assertNotNull(batch.getVector(2));
    }

    @Test
    void defaultConstructor_vectorsHaveDefaultBatchSizeCapacity() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);

        assertEquals(ColumnBatch.DEFAULT_BATCH_SIZE, batch.getVector(0).getCapacity());
        assertEquals(ColumnBatch.DEFAULT_BATCH_SIZE, batch.getVector(1).getCapacity());
        assertEquals(ColumnBatch.DEFAULT_BATCH_SIZE, batch.getVector(2).getCapacity());
    }

    @Test
    void customCapacityConstructor_vectorsHaveRequestedCapacity() {
        ColumnBatch batch = new ColumnBatch(threeColSchema, 16);

        assertEquals(16, batch.getVector(0).getCapacity());
        assertEquals(16, batch.getVector(1).getCapacity());
        assertEquals(16, batch.getVector(2).getCapacity());
    }

    @Test
    void defaultConstructor_vectorTypesMatchSchema() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);

        assertEquals(DataType.INTEGER, batch.getVector(0).getType());
        assertEquals(DataType.VARCHAR, batch.getVector(1).getType());
        assertEquals(DataType.FLOAT,   batch.getVector(2).getType());
    }

    @Test
    void defaultConstructor_initialSizeIsZero() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        assertEquals(0, batch.getSize());
    }

    // -------------------------------------------------------------------------
    // wrap() factory
    // -------------------------------------------------------------------------

    @Test
    void wrap_substitutesProvidedVectors() {
        ColumnVector idVec    = ColumnVector.create(DataType.INTEGER, 4);
        ColumnVector nameVec  = ColumnVector.create(DataType.VARCHAR, 4);
        ColumnVector priceVec = ColumnVector.create(DataType.FLOAT, 4);
        idVec.putInt(0, 7);

        ColumnBatch batch = ColumnBatch.wrap(threeColSchema, new ColumnVector[]{idVec, nameVec, priceVec});

        assertSame(idVec, batch.getVector(0));
        assertEquals(7, batch.getVector(0).getInt(0));
    }

    @Test
    void wrap_wrongVectorCount_throws() {
        ColumnVector only = ColumnVector.create(DataType.INTEGER, 4);
        assertThrows(IllegalArgumentException.class,
                () -> ColumnBatch.wrap(threeColSchema, new ColumnVector[]{only}));
    }

    // -------------------------------------------------------------------------
    // Vector access by name
    // -------------------------------------------------------------------------

    @Test
    void getVector_byName_caseInsensitive() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        assertSame(batch.getVector(1), batch.getVector("name"));
        assertSame(batch.getVector(1), batch.getVector("NAME"));
        assertSame(batch.getVector(1), batch.getVector("Name"));
    }

    @Test
    void getVector_unknownName_throws() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        assertThrows(IllegalArgumentException.class, () -> batch.getVector("nonexistent"));
    }

    // -------------------------------------------------------------------------
    // Size management
    // -------------------------------------------------------------------------

    @Test
    void setSize_getSize_roundTrip() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        batch.setSize(512);
        assertEquals(512, batch.getSize());
    }

    @Test
    void setSize_zero_isAllowed() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        batch.setSize(0);
        assertEquals(0, batch.getSize());
    }

    @Test
    void setSize_negative_throws() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        assertThrows(IllegalArgumentException.class, () -> batch.setSize(-1));
    }

    // -------------------------------------------------------------------------
    // Selection vector — initial state
    // -------------------------------------------------------------------------

    @Test
    void initialState_noSelectionVector() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        assertFalse(batch.hasSelectionVector());
        assertNull(batch.getSelectionVector());
    }

    @Test
    void getSelectionSize_withoutSelection_returnsBatchSize() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        batch.setSize(100);
        assertEquals(100, batch.getSelectionSize());
    }

    // -------------------------------------------------------------------------
    // Selection vector — install and inspect
    // -------------------------------------------------------------------------

    @Test
    void setSelectionVector_enablesSelection() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        batch.setSize(5);

        int[] sv = {0, 2, 4};
        batch.setSelectionVector(sv, 3);

        assertTrue(batch.hasSelectionVector());
        assertSame(sv, batch.getSelectionVector());
        assertEquals(3, batch.getSelectionSize());
    }

    @Test
    void setSelectionVector_countZero_isAllowed() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        int[] sv = new int[8];
        batch.setSelectionVector(sv, 0);

        assertTrue(batch.hasSelectionVector());
        assertEquals(0, batch.getSelectionSize());
    }

    @Test
    void setSelectionVector_nullArray_throws() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        assertThrows(IllegalArgumentException.class,
                () -> batch.setSelectionVector(null, 0));
    }

    @Test
    void setSelectionVector_countExceedsArrayLength_throws() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        int[] sv = new int[3];
        assertThrows(IllegalArgumentException.class,
                () -> batch.setSelectionVector(sv, 5));
    }

    @Test
    void setSelectionVector_negativeCount_throws() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        int[] sv = new int[4];
        assertThrows(IllegalArgumentException.class,
                () -> batch.setSelectionVector(sv, -1));
    }

    // -------------------------------------------------------------------------
    // Selection vector — reset
    // -------------------------------------------------------------------------

    @Test
    void resetSelectionVector_clearsSelectionFlag() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        batch.setSize(5);
        batch.setSelectionVector(new int[]{1, 3}, 2);

        batch.resetSelectionVector();

        assertFalse(batch.hasSelectionVector());
    }

    @Test
    void resetSelectionVector_getSelectionSize_returnsBatchSize() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        batch.setSize(7);
        batch.setSelectionVector(new int[]{0, 1}, 2);
        batch.resetSelectionVector();

        assertEquals(7, batch.getSelectionSize());
    }

    @Test
    void resetSelectionVector_thenReinstall_works() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        batch.setSize(4);
        batch.setSelectionVector(new int[]{0, 1, 2, 3}, 4);
        batch.resetSelectionVector();

        int[] newSv = {1, 3};
        batch.setSelectionVector(newSv, 2);

        assertTrue(batch.hasSelectionVector());
        assertEquals(2, batch.getSelectionSize());
        assertSame(newSv, batch.getSelectionVector());
    }

    @Test
    void resetSelectionVector_noOp_whenNotActive() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        // Should not throw
        assertDoesNotThrow(batch::resetSelectionVector);
        assertFalse(batch.hasSelectionVector());
    }

    // -------------------------------------------------------------------------
    // materializeRow
    // -------------------------------------------------------------------------

    @Test
    void materializeRow_returnsCorrectBoxedValues() {
        ColumnBatch batch = new ColumnBatch(threeColSchema, 4);
        batch.getVector(0).putInt(2, 42);
        batch.getVector(1).putString(2, "Alice");
        batch.getVector(2).putFloat(2, 9.99f);
        batch.setSize(4);

        Object[] row = batch.materializeRow(2);

        assertEquals(3, row.length);
        assertEquals(42,      row[0]);
        assertEquals("Alice", row[1]);
        assertEquals(9.99f,  (Float) row[2], 0.001f);
    }

    @Test
    void materializeRow_nullSlot_returnsNull() {
        ColumnBatch batch = new ColumnBatch(threeColSchema, 2);
        batch.getVector(0).putInt(0, 1);
        batch.getVector(1).setNull(0);
        batch.getVector(2).putFloat(0, 1.0f);
        batch.setSize(2);

        Object[] row = batch.materializeRow(0);
        assertNull(row[1]);
    }

    // -------------------------------------------------------------------------
    // Schema access
    // -------------------------------------------------------------------------

    @Test
    void getSchema_returnsSchemaPassedToConstructor() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        assertSame(threeColSchema, batch.getSchema());
    }

    // -------------------------------------------------------------------------
    // toString
    // -------------------------------------------------------------------------

    @Test
    void toString_containsUsefulInfo() {
        ColumnBatch batch = new ColumnBatch(threeColSchema);
        batch.setSize(64);
        String s = batch.toString();
        assertTrue(s.contains("64"));
    }
}
