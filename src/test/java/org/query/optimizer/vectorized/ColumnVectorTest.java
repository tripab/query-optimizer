package org.query.optimizer.vectorized;

import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.DataType;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ColumnVector}.
 *
 * Coverage:
 *  - Factory construction for all three DataTypes
 *  - Typed read/write round-trips (putInt/getInt, putFloat/getFloat, putString/getString)
 *  - Generic boxed get/put path
 *  - Null tracking: setNull, isNull, getNullCount, hasNulls
 *  - Null cleared when a typed value is written over a null slot
 *  - Wrong-type accessor throws IllegalStateException
 *  - Metadata accessors: getType(), getCapacity()
 *  - Package-private raw array accessors return the correct backing array
 */
class ColumnVectorTest {

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    @Test
    void create_integer_allocatesIntArray() {
        ColumnVector v = ColumnVector.create(DataType.INTEGER, 8);
        assertEquals(DataType.INTEGER, v.getType());
        assertEquals(8, v.getCapacity());
        assertNotNull(v.getIntData());
        assertNull(v.getFloatData());
        assertNull(v.getStringData());
    }

    @Test
    void create_float_allocatesFloatArray() {
        ColumnVector v = ColumnVector.create(DataType.FLOAT, 4);
        assertEquals(DataType.FLOAT, v.getType());
        assertNotNull(v.getFloatData());
        assertNull(v.getIntData());
        assertNull(v.getStringData());
    }

    @Test
    void create_varchar_allocatesStringArray() {
        ColumnVector v = ColumnVector.create(DataType.VARCHAR, 16);
        assertEquals(DataType.VARCHAR, v.getType());
        assertNotNull(v.getStringData());
        assertNull(v.getIntData());
        assertNull(v.getFloatData());
    }

    // -------------------------------------------------------------------------
    // INTEGER typed read/write
    // -------------------------------------------------------------------------

    @Test
    void putInt_getInt_roundTrip() {
        ColumnVector v = ColumnVector.create(DataType.INTEGER, 4);
        v.putInt(0, 42);
        v.putInt(1, -7);
        v.putInt(3, Integer.MAX_VALUE);

        assertEquals(42, v.getInt(0));
        assertEquals(-7, v.getInt(1));
        assertEquals(0,  v.getInt(2));          // default zero
        assertEquals(Integer.MAX_VALUE, v.getInt(3));
    }

    @Test
    void putInt_clearsNullFlag() {
        ColumnVector v = ColumnVector.create(DataType.INTEGER, 2);
        v.setNull(0);
        assertTrue(v.isNull(0));

        v.putInt(0, 99);
        assertFalse(v.isNull(0));
        assertEquals(0, v.getNullCount());
    }

    // -------------------------------------------------------------------------
    // FLOAT typed read/write
    // -------------------------------------------------------------------------

    @Test
    void putFloat_getFloat_roundTrip() {
        ColumnVector v = ColumnVector.create(DataType.FLOAT, 3);
        v.putFloat(0, 1.5f);
        v.putFloat(1, -0.25f);
        v.putFloat(2, Float.MAX_VALUE);

        assertEquals(1.5f,          v.getFloat(0), 0.0001f);
        assertEquals(-0.25f,        v.getFloat(1), 0.0001f);
        assertEquals(Float.MAX_VALUE, v.getFloat(2), 0.0001f);
    }

    @Test
    void putFloat_clearsNullFlag() {
        ColumnVector v = ColumnVector.create(DataType.FLOAT, 1);
        v.setNull(0);
        v.putFloat(0, 3.14f);
        assertFalse(v.isNull(0));
    }

    // -------------------------------------------------------------------------
    // VARCHAR typed read/write
    // -------------------------------------------------------------------------

    @Test
    void putString_getString_roundTrip() {
        ColumnVector v = ColumnVector.create(DataType.VARCHAR, 3);
        v.putString(0, "hello");
        v.putString(1, "");
        v.putString(2, "world");

        assertEquals("hello", v.getString(0));
        assertEquals("",      v.getString(1));
        assertEquals("world", v.getString(2));
    }

    @Test
    void putString_clearsNullFlag() {
        ColumnVector v = ColumnVector.create(DataType.VARCHAR, 1);
        v.setNull(0);
        v.putString(0, "data");
        assertFalse(v.isNull(0));
    }

    // -------------------------------------------------------------------------
    // Generic boxed get / put
    // -------------------------------------------------------------------------

    @Test
    void put_integer_dispatchesCorrectly() {
        ColumnVector v = ColumnVector.create(DataType.INTEGER, 2);
        v.put(0, 100);
        v.put(1, null);

        assertEquals(100, v.get(0));
        assertNull(v.get(1));
        assertTrue(v.isNull(1));
    }

    @Test
    void put_float_dispatchesCorrectly() {
        ColumnVector v = ColumnVector.create(DataType.FLOAT, 1);
        v.put(0, 2.5f);
        assertEquals(2.5f, (Float) v.get(0), 0.0001f);
    }

    @Test
    void put_varchar_dispatchesCorrectly() {
        ColumnVector v = ColumnVector.create(DataType.VARCHAR, 1);
        v.put(0, "test");
        assertEquals("test", v.get(0));
    }

    @Test
    void get_returnsNull_whenSlotIsNull() {
        ColumnVector v = ColumnVector.create(DataType.INTEGER, 1);
        v.setNull(0);
        assertNull(v.get(0));
    }

    // -------------------------------------------------------------------------
    // Null tracking
    // -------------------------------------------------------------------------

    @Test
    void nullCount_initiallyZero() {
        ColumnVector v = ColumnVector.create(DataType.INTEGER, 5);
        assertEquals(0, v.getNullCount());
        assertFalse(v.hasNulls());
    }

    @Test
    void setNull_incrementsNullCount() {
        ColumnVector v = ColumnVector.create(DataType.FLOAT, 4);
        v.setNull(0);
        v.setNull(2);

        assertEquals(2, v.getNullCount());
        assertTrue(v.hasNulls());
        assertTrue(v.isNull(0));
        assertFalse(v.isNull(1));
        assertTrue(v.isNull(2));
        assertFalse(v.isNull(3));
    }

    @Test
    void setNull_idempotent_doesNotDoubleCount() {
        ColumnVector v = ColumnVector.create(DataType.INTEGER, 2);
        v.setNull(0);
        v.setNull(0); // second call on the same index
        assertEquals(1, v.getNullCount());
    }

    @Test
    void writingValueOverNull_decrementsNullCount() {
        ColumnVector v = ColumnVector.create(DataType.INTEGER, 3);
        v.setNull(0);
        v.setNull(1);
        assertEquals(2, v.getNullCount());

        v.putInt(0, 5);
        assertEquals(1, v.getNullCount());

        v.putInt(1, 10);
        assertEquals(0, v.getNullCount());
        assertFalse(v.hasNulls());
    }

    @Test
    void allSlotsNull() {
        int cap = 10;
        ColumnVector v = ColumnVector.create(DataType.VARCHAR, cap);
        for (int i = 0; i < cap; i++) v.setNull(i);

        assertEquals(cap, v.getNullCount());
        assertTrue(v.hasNulls());
        for (int i = 0; i < cap; i++) {
            assertTrue(v.isNull(i));
            assertNull(v.get(i));
        }
    }

    // -------------------------------------------------------------------------
    // Wrong-type accessor guards
    // -------------------------------------------------------------------------

    @Test
    void getInt_onFloatVector_throws() {
        ColumnVector v = ColumnVector.create(DataType.FLOAT, 1);
        assertThrows(IllegalStateException.class, () -> v.getInt(0));
    }

    @Test
    void getFloat_onVarcharVector_throws() {
        ColumnVector v = ColumnVector.create(DataType.VARCHAR, 1);
        assertThrows(IllegalStateException.class, () -> v.getFloat(0));
    }

    @Test
    void getString_onIntegerVector_throws() {
        ColumnVector v = ColumnVector.create(DataType.INTEGER, 1);
        assertThrows(IllegalStateException.class, () -> v.getString(0));
    }

    @Test
    void putInt_onFloatVector_throws() {
        ColumnVector v = ColumnVector.create(DataType.FLOAT, 1);
        assertThrows(IllegalStateException.class, () -> v.putInt(0, 1));
    }

    // -------------------------------------------------------------------------
    // Raw array accessors (package-private)
    // -------------------------------------------------------------------------

    @Test
    void rawIntArray_reflectsWrittenValues() {
        ColumnVector v = ColumnVector.create(DataType.INTEGER, 3);
        v.putInt(0, 10);
        v.putInt(1, 20);
        v.putInt(2, 30);

        int[] raw = v.getIntData();
        assertNotNull(raw);
        assertEquals(10, raw[0]);
        assertEquals(20, raw[1]);
        assertEquals(30, raw[2]);
    }

    @Test
    void rawFloatArray_reflectsWrittenValues() {
        ColumnVector v = ColumnVector.create(DataType.FLOAT, 2);
        v.putFloat(0, 1.1f);
        v.putFloat(1, 2.2f);

        float[] raw = v.getFloatData();
        assertNotNull(raw);
        assertEquals(1.1f, raw[0], 0.0001f);
        assertEquals(2.2f, raw[1], 0.0001f);
    }

    @Test
    void rawStringArray_reflectsWrittenValues() {
        ColumnVector v = ColumnVector.create(DataType.VARCHAR, 2);
        v.putString(0, "foo");
        v.putString(1, "bar");

        String[] raw = v.getStringData();
        assertNotNull(raw);
        assertEquals("foo", raw[0]);
        assertEquals("bar", raw[1]);
    }

    @Test
    void nullsArray_reflectsNullFlags() {
        ColumnVector v = ColumnVector.create(DataType.INTEGER, 3);
        v.setNull(1);

        boolean[] nulls = v.getNulls();
        assertFalse(nulls[0]);
        assertTrue(nulls[1]);
        assertFalse(nulls[2]);
    }

    // -------------------------------------------------------------------------
    // toString
    // -------------------------------------------------------------------------

    @Test
    void toString_containsTypeAndCapacity() {
        ColumnVector v = ColumnVector.create(DataType.INTEGER, 1024);
        String s = v.toString();
        assertTrue(s.contains("INTEGER"));
        assertTrue(s.contains("1024"));
    }
}
