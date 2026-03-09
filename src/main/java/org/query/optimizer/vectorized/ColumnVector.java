package org.query.optimizer.vectorized;

import org.query.optimizer.catalog.DataType;

/**
 * Typed columnar storage for a single column across multiple rows.
 *
 * <p>A ColumnVector holds a contiguous array of values for one column in a batch.
 * Instead of boxing values into {@code Object[]}, it stores them in a type-specialized
 * primitive or String array ({@code int[]}, {@code float[]}, {@code String[]}), which
 * the JIT can autovectorize and which avoids per-element heap allocation.
 *
 * <p>Exactly one of {@code intData}, {@code floatData}, or {@code stringData} is
 * non-null, determined by the column's {@link DataType} at construction time.
 *
 * <p>Null tracking uses a {@code boolean[]} with one entry per row. Most columns
 * in analytic workloads are non-null, so null tracking is separate from the hot
 * path to avoid polluting cache lines with null flags when iterating values.
 *
 * <p>Use {@link #create(DataType, int)} to construct instances.
 */
public class ColumnVector {

    private final DataType type;
    private final int capacity;

    // Exactly one non-null, selected by type
    private final int[]    intData;
    private final float[]  floatData;
    private final String[] stringData;

    // Null tracking — one flag per row slot
    private final boolean[] nulls;
    private int nullCount;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    private ColumnVector(DataType type, int capacity) {
        this.type     = type;
        this.capacity = capacity;
        this.nulls     = new boolean[capacity];
        this.nullCount = 0;

        switch (type) {
            case INTEGER -> {
                intData    = new int[capacity];
                floatData  = null;
                stringData = null;
            }
            case FLOAT -> {
                intData    = null;
                floatData  = new float[capacity];
                stringData = null;
            }
            case VARCHAR -> {
                intData    = null;
                floatData  = null;
                stringData = new String[capacity];
            }
            default -> throw new IllegalArgumentException("Unsupported DataType: " + type);
        }
    }

    /**
     * Factory method — creates a zero-initialized ColumnVector of the given type
     * with the specified capacity (number of row slots).
     *
     * @param type     the column's data type
     * @param capacity maximum number of rows this vector can hold
     * @return a new, empty ColumnVector
     */
    public static ColumnVector create(DataType type, int capacity) {
        return new ColumnVector(type, capacity);
    }

    // -------------------------------------------------------------------------
    // Typed getters — no boxing on the read path
    // -------------------------------------------------------------------------

    /**
     * Returns the integer value at {@code rowIndex}.
     * Only valid when {@code getType() == DataType.INTEGER}.
     *
     * @throws IllegalStateException if the column type is not INTEGER
     */
    public int getInt(int rowIndex) {
        if (intData == null) {
            throw new IllegalStateException("Column is not of type INTEGER, actual type: " + type);
        }
        return intData[rowIndex];
    }

    /**
     * Returns the float value at {@code rowIndex}.
     * Only valid when {@code getType() == DataType.FLOAT}.
     *
     * @throws IllegalStateException if the column type is not FLOAT
     */
    public float getFloat(int rowIndex) {
        if (floatData == null) {
            throw new IllegalStateException("Column is not of type FLOAT, actual type: " + type);
        }
        return floatData[rowIndex];
    }

    /**
     * Returns the String value at {@code rowIndex}.
     * Only valid when {@code getType() == DataType.VARCHAR}.
     *
     * @throws IllegalStateException if the column type is not VARCHAR
     */
    public String getString(int rowIndex) {
        if (stringData == null) {
            throw new IllegalStateException("Column is not of type VARCHAR, actual type: " + type);
        }
        return stringData[rowIndex];
    }

    /**
     * Generic boxed getter — use typed getters on the hot path; this is
     * provided for convenience in materialization / debugging code.
     *
     * @return boxed value, or {@code null} if the slot is null
     */
    public Object get(int rowIndex) {
        if (nulls[rowIndex]) return null;
        return switch (type) {
            case INTEGER -> intData[rowIndex];
            case FLOAT   -> floatData[rowIndex];
            case VARCHAR -> stringData[rowIndex];
        };
    }

    // -------------------------------------------------------------------------
    // Typed setters
    // -------------------------------------------------------------------------

    /**
     * Writes an integer value at {@code rowIndex} and clears the null flag.
     * Only valid when {@code getType() == DataType.INTEGER}.
     */
    public void putInt(int rowIndex, int value) {
        if (intData == null) {
            throw new IllegalStateException("Column is not of type INTEGER, actual type: " + type);
        }
        if (nulls[rowIndex]) {
            nulls[rowIndex] = false;
            nullCount--;
        }
        intData[rowIndex] = value;
    }

    /**
     * Writes a float value at {@code rowIndex} and clears the null flag.
     * Only valid when {@code getType() == DataType.FLOAT}.
     */
    public void putFloat(int rowIndex, float value) {
        if (floatData == null) {
            throw new IllegalStateException("Column is not of type FLOAT, actual type: " + type);
        }
        if (nulls[rowIndex]) {
            nulls[rowIndex] = false;
            nullCount--;
        }
        floatData[rowIndex] = value;
    }

    /**
     * Writes a String value at {@code rowIndex} and clears the null flag.
     * Only valid when {@code getType() == DataType.VARCHAR}.
     */
    public void putString(int rowIndex, String value) {
        if (stringData == null) {
            throw new IllegalStateException("Column is not of type VARCHAR, actual type: " + type);
        }
        if (nulls[rowIndex]) {
            nulls[rowIndex] = false;
            nullCount--;
        }
        stringData[rowIndex] = value;
    }

    /**
     * Generic boxed setter — dispatches to the correct typed setter based on
     * the column's type. Accepts {@code null} to mark the slot as null.
     */
    public void put(int rowIndex, Object value) {
        if (value == null) {
            setNull(rowIndex);
            return;
        }
        switch (type) {
            case INTEGER -> putInt(rowIndex, (Integer) value);
            case FLOAT   -> putFloat(rowIndex, (Float) value);
            case VARCHAR -> putString(rowIndex, (String) value);
        }
    }

    // -------------------------------------------------------------------------
    // Null handling
    // -------------------------------------------------------------------------

    /**
     * Marks the slot at {@code rowIndex} as null.
     */
    public void setNull(int rowIndex) {
        if (!nulls[rowIndex]) {
            nulls[rowIndex] = true;
            nullCount++;
        }
    }

    /** Returns {@code true} if the slot at {@code rowIndex} is null. */
    public boolean isNull(int rowIndex) {
        return nulls[rowIndex];
    }

    /** Returns the number of null slots in this vector. */
    public int getNullCount() {
        return nullCount;
    }

    /** Returns {@code true} if this vector has any null values. */
    public boolean hasNulls() {
        return nullCount > 0;
    }

    // -------------------------------------------------------------------------
    // Accessors for raw arrays (package-private for use by vectorized operators)
    // -------------------------------------------------------------------------

    /** Direct access to the underlying int array; null if type != INTEGER. */
    int[] getIntData() {
        return intData;
    }

    /** Direct access to the underlying float array; null if type != FLOAT. */
    float[] getFloatData() {
        return floatData;
    }

    /** Direct access to the underlying String array; null if type != VARCHAR. */
    String[] getStringData() {
        return stringData;
    }

    /** Direct access to the null flags array. */
    boolean[] getNulls() {
        return nulls;
    }

    // -------------------------------------------------------------------------
    // Bulk slice copy (used by VectorizedScan)
    // -------------------------------------------------------------------------

    /**
     * Copies {@code length} values starting at {@code srcOffset} from {@code src}
     * into this vector starting at index 0, including null flags and null count.
     *
     * <p>This is the only path that performs a bulk copy of raw array data while
     * keeping {@link #nullCount} consistent, because it owns the null-flags array
     * and can recount after the copy without a second pass through the caller.
     *
     * <p>Package-private: intended for use by {@code VectorizedScan} only.
     *
     * @param src       source vector; must have the same {@link DataType} as this vector
     * @param srcOffset first row index to copy from {@code src}
     * @param length    number of rows to copy
     * @throws IllegalArgumentException if types differ or bounds are violated
     */
    void loadSlice(ColumnVector src, int srcOffset, int length) {
        if (src.type != this.type) {
            throw new IllegalArgumentException(
                    "Type mismatch: src=" + src.type + ", dst=" + this.type);
        }
        if (srcOffset < 0 || length < 0 || srcOffset + length > src.capacity) {
            throw new IllegalArgumentException(
                    "Invalid slice: srcOffset=" + srcOffset + ", length=" + length +
                    ", srcCapacity=" + src.capacity);
        }
        if (length > this.capacity) {
            throw new IllegalArgumentException(
                    "Slice length " + length + " exceeds dst capacity " + this.capacity);
        }

        // Copy typed data
        switch (type) {
            case INTEGER -> System.arraycopy(src.intData,    srcOffset, this.intData,    0, length);
            case FLOAT   -> System.arraycopy(src.floatData,  srcOffset, this.floatData,  0, length);
            case VARCHAR -> System.arraycopy(src.stringData, srcOffset, this.stringData, 0, length);
        }

        // Copy null flags and recount
        System.arraycopy(src.nulls, srcOffset, this.nulls, 0, length);
        int count = 0;
        for (int i = 0; i < length; i++) {
            if (this.nulls[i]) count++;
        }
        this.nullCount = count;
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    /** Returns the DataType this vector stores. */
    public DataType getType() {
        return type;
    }

    /** Returns the maximum number of rows this vector can hold. */
    public int getCapacity() {
        return capacity;
    }

    @Override
    public String toString() {
        return String.format("ColumnVector[type=%s, capacity=%d, nullCount=%d]",
                type, capacity, nullCount);
    }
}
