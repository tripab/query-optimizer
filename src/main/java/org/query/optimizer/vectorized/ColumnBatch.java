package org.query.optimizer.vectorized;

import org.query.optimizer.catalog.DataType;
import org.query.optimizer.catalog.Schema;

import java.util.Arrays;

/**
 * A horizontal slice of a table: a fixed-size batch of rows represented in
 * columnar form.
 *
 * <p>A ColumnBatch groups one {@link ColumnVector} per column in the schema,
 * all sharing the same logical row count ({@link #getSize()}). It is the unit
 * of data exchanged between vectorized operators — analogous to the single
 * {@code Object[]} returned by {@code Iterator.next()} in the Volcano model,
 * but carrying up to {@link #DEFAULT_BATCH_SIZE} rows at a time.
 *
 * <h2>Selection vector</h2>
 * <p>Rather than physically removing non-qualifying rows after a filter, the
 * batch carries an optional <em>selection vector</em>: an {@code int[]} of
 * row indices that are "live". When a selection vector is present, downstream
 * operators iterate {@code for (int i = 0; i < selectionSize; i++) { int row =
 * selectionVector[i]; ... }} instead of {@code for (int row = 0; row < size;
 * row++)}. This avoids any data movement and lets the branch predictor handle
 * the tight loop cleanly.
 *
 * <h2>Batch reuse</h2>
 * <p>Operators are expected to reuse their output ColumnBatch across successive
 * {@code next()} calls (allocate once in {@code open()}, refill in
 * {@code next()}). Callers that need to retain the data beyond the next
 * {@code next()} call must copy it.
 *
 * <h2>Batch size</h2>
 * <p>{@link #DEFAULT_BATCH_SIZE} = 1024 matches the batch size used by
 * CockroachDB, DuckDB and Velox. It is large enough to amortize per-batch
 * overhead while small enough for a few columns to fit comfortably in L1/L2
 * cache.
 */
public class ColumnBatch {

    /** Standard batch size — 1024 rows per batch. */
    public static final int DEFAULT_BATCH_SIZE = 1024;

    private final Schema        schema;
    private final ColumnVector[] vectors;   // one per schema column, index-aligned

    /** Number of valid rows currently held in this batch (≤ capacity of each vector). */
    private int size;

    // ---- Selection vector state ----
    /**
     * Indices of "live" rows within this batch. Non-null only when a filter has
     * been applied. Length is always {@link #DEFAULT_BATCH_SIZE} so the array
     * can be reused without reallocation; only the first {@link #selectionSize}
     * entries are valid.
     */
    private int[] selectionVector;
    private int   selectionSize;
    private boolean hasSelection;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a ColumnBatch backed by freshly allocated ColumnVectors, one per
     * column in {@code schema}, each with capacity {@link #DEFAULT_BATCH_SIZE}.
     */
    public ColumnBatch(Schema schema) {
        this(schema, DEFAULT_BATCH_SIZE);
    }

    /**
     * Creates a ColumnBatch with a custom per-vector capacity.
     * Prefer {@link #ColumnBatch(Schema)} for normal operator use.
     */
    public ColumnBatch(Schema schema, int capacity) {
        this.schema  = schema;
        this.vectors = new ColumnVector[schema.columnCount()];
        for (int i = 0; i < schema.columnCount(); i++) {
            Schema.Column col = schema.getColumn(i);
            this.vectors[i] = ColumnVector.create(col.type(), capacity);
        }
        this.size         = 0;
        this.hasSelection = false;
        this.selectionSize = 0;
    }

    /**
     * Creates a ColumnBatch that wraps pre-built {@link ColumnVector}s.
     * The caller is responsible for ensuring the vectors are aligned with
     * {@code schema} (same count, same order, compatible types).
     */
    public static ColumnBatch wrap(Schema schema, ColumnVector[] vectors) {
        if (vectors.length != schema.columnCount()) {
            throw new IllegalArgumentException(
                    "Vector count " + vectors.length +
                    " does not match schema column count " + schema.columnCount());
        }
        ColumnBatch batch = new ColumnBatch(schema);
        // Replace the freshly allocated vectors with the provided ones
        System.arraycopy(vectors, 0, batch.vectors, 0, vectors.length);
        return batch;
    }

    // -------------------------------------------------------------------------
    // Column vector access
    // -------------------------------------------------------------------------

    /**
     * Returns the ColumnVector for column at position {@code colIndex}.
     *
     * @throws ArrayIndexOutOfBoundsException if {@code colIndex} is out of range
     */
    public ColumnVector getVector(int colIndex) {
        return vectors[colIndex];
    }

    /**
     * Replaces the {@link ColumnVector} at {@code colIndex} with {@code vec}.
     *
     * <p>Package-private: intended for use by {@code VectorizedProject} only, which
     * needs to swap vector references on each {@code next()} call without allocating
     * a new {@code ColumnBatch}. The caller is responsible for ensuring {@code vec}
     * is type-compatible with the column at {@code colIndex} in the schema.
     *
     * @param colIndex column position (0-based)
     * @param vec      the replacement vector; must not be null
     */
    void setVector(int colIndex, ColumnVector vec) {
        vectors[colIndex] = vec;
    }

    /**
     * Returns the ColumnVector for the column with the given name (case-insensitive).
     *
     * @throws IllegalArgumentException if no column with that name exists in the schema
     */
    public ColumnVector getVector(String columnName) {
        return vectors[schema.getColumnIndex(columnName)];
    }

    // -------------------------------------------------------------------------
    // Size
    // -------------------------------------------------------------------------

    /** Returns the number of valid rows currently in this batch. */
    public int getSize() {
        return size;
    }

    /**
     * Sets the number of valid rows in this batch.
     * Must be ≥ 0 and ≤ the capacity of the underlying vectors.
     */
    public void setSize(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Batch size cannot be negative: " + size);
        }
        this.size = size;
    }

    // -------------------------------------------------------------------------
    // Selection vector
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if this batch currently has an active selection vector,
     * meaning only the rows indexed by {@link #getSelectionVector()} are live.
     */
    public boolean hasSelectionVector() {
        return hasSelection;
    }

    /**
     * Returns the selection vector array. Only the first {@link #getSelectionSize()}
     * entries are valid. Returns {@code null} if no selection vector is active.
     *
     * <p>The returned array is owned by this batch and may be overwritten on the
     * next call to {@link #setSelectionVector}. Callers that need to retain it
     * must copy it.
     */
    public int[] getSelectionVector() {
        return selectionVector;
    }

    /**
     * Returns the number of valid entries in the selection vector.
     * Returns {@link #size} when no selection vector is active (all rows are live).
     */
    public int getSelectionSize() {
        return hasSelection ? selectionSize : size;
    }

    /**
     * Installs a selection vector on this batch.
     *
     * <p>The batch takes ownership of {@code sv} — the caller must not modify
     * it after this call. {@code count} is the number of valid entries in
     * {@code sv}.
     *
     * @param sv    array of live row indices (length ≥ {@code count})
     * @param count number of valid entries in {@code sv}
     */
    public void setSelectionVector(int[] sv, int count) {
        if (sv == null) {
            throw new IllegalArgumentException("Selection vector array must not be null");
        }
        if (count < 0 || count > sv.length) {
            throw new IllegalArgumentException(
                    "Invalid selection count " + count + " for array of length " + sv.length);
        }
        this.selectionVector = sv;
        this.selectionSize   = count;
        this.hasSelection    = true;
    }

    /**
     * Removes the active selection vector, making all {@link #size} rows live again.
     * A no-op if no selection vector is currently active.
     */
    public void resetSelectionVector() {
        this.hasSelection  = false;
        this.selectionSize = 0;
        // Keep the selectionVector array allocated so it can be reused next time
    }

    // -------------------------------------------------------------------------
    // Schema
    // -------------------------------------------------------------------------

    /** Returns the schema that describes the columns in this batch. */
    public Schema getSchema() {
        return schema;
    }

    // -------------------------------------------------------------------------
    // Convenience: materialise a single row as an Object[]
    // -------------------------------------------------------------------------

    /**
     * Materialises row {@code rowIndex} into a boxed {@code Object[]} in schema
     * column order. Intended for result materialisation and debugging — not for
     * use on the hot evaluation path.
     *
     * @param rowIndex physical row index within this batch (not a selection index)
     * @return boxed row values; nulls where the column value is null
     */
    public Object[] materializeRow(int rowIndex) {
        Object[] row = new Object[vectors.length];
        for (int c = 0; c < vectors.length; c++) {
            row[c] = vectors[c].get(rowIndex);
        }
        return row;
    }

    // -------------------------------------------------------------------------
    // Object overrides
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return String.format(
                "ColumnBatch[schema=%s, size=%d, hasSelection=%b, selectionSize=%d]",
                schema, size, hasSelection, getSelectionSize());
    }
}
