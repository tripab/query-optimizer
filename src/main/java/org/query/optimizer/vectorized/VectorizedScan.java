package org.query.optimizer.vectorized;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.Schema;

/**
 * Vectorized table scan operator.
 *
 * <p>Reads from a {@link ColumnarTable} and produces {@link ColumnBatch}es of up to
 * {@link ColumnBatch#DEFAULT_BATCH_SIZE} rows per {@link #next()} call. This is the leaf
 * node of every vectorized plan tree — the only operator that touches the underlying storage.
 *
 * <h2>Batch filling</h2>
 * <p>On each {@code next()} call, the scan copies a contiguous slice of each column's typed
 * array from the {@link ColumnarTable} into the reusable output batch. For a table with 2500
 * rows and a batch size of 1024, three calls return batches of 1024, 1024, and 452 rows; the
 * fourth call returns {@code null}.
 *
 * <p>The copy is intentionally straightforward for clarity. A zero-copy approach (pointing the
 * batch's vectors directly at slices of the table's arrays) is an Extension A1 optimisation.
 *
 * <h2>Batch reuse</h2>
 * <p>A single {@link ColumnBatch} is allocated once in {@link #open()} and refilled on every
 * {@link #next()} call, avoiding per-batch heap allocation on the hot path. Callers that need
 * to retain a batch beyond the next {@code next()} call must copy its contents.
 *
 * <h2>Selection vector</h2>
 * <p>The scan itself never installs a selection vector — it always returns full, dense batches.
 * Any stale selection vector is cleared at the top of each {@code next()} call. Selection
 * vectors are added by downstream {@link VectorizedFilter} operators.
 */
public class VectorizedScan implements VectorizedOperator {

    private final String  tableName;
    private final Catalog catalog;

    // Resolved during open()
    private ColumnarTable columnarTable;

    // Index of the first unread row in the ColumnarTable
    private int currentRow;

    // Reused across next() calls — allocated once in open()
    private ColumnBatch outputBatch;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a scan over the named table.
     *
     * @param tableName the table to scan (case-insensitive, resolved via {@code catalog})
     * @param catalog   catalog used to obtain the {@link ColumnarTable} on {@link #open()}
     */
    public VectorizedScan(String tableName, Catalog catalog) {
        this.tableName = tableName;
        this.catalog   = catalog;
    }

    // -------------------------------------------------------------------------
    // VectorizedOperator
    // -------------------------------------------------------------------------

    @Override
    public void open() {
        columnarTable = catalog.getColumnarTable(tableName);
        currentRow    = 0;
        outputBatch   = new ColumnBatch(columnarTable.getSchema());
    }

    /**
     * Returns the next batch of rows, or {@code null} when the table is exhausted.
     *
     * <p>The same {@link ColumnBatch} instance is returned on every call — only its
     * contents and {@link ColumnBatch#getSize() size} change. Callers must not hold a
     * reference to the returned batch across calls.
     */
    @Override
    public ColumnBatch next() {
        int totalRows = columnarTable.getRowCount();
        if (currentRow >= totalRows) {
            return null;
        }

        int batchSize = Math.min(ColumnBatch.DEFAULT_BATCH_SIZE, totalRows - currentRow);

        outputBatch.resetSelectionVector();
        outputBatch.setSize(batchSize);

        int colCount = columnarTable.getSchema().columnCount();
        for (int c = 0; c < colCount; c++) {
            outputBatch.getVector(c).loadSlice(
                    columnarTable.getColumn(c), currentRow, batchSize);
        }

        currentRow += batchSize;
        return outputBatch;
    }

    @Override
    public void close() {
        columnarTable = null;
        outputBatch   = null;
        currentRow    = 0;
    }

    /**
     * Returns the output schema. Safe to call before {@link #open()} — schema is resolved
     * directly from the catalog's row-oriented {@code TableMetadata}, which does not require
     * the columnar view to be built yet.
     */
    @Override
    public Schema getOutputSchema() {
        return catalog.getTableMetadata(tableName).getSchema();
    }

    // -------------------------------------------------------------------------
    // toString
    // -------------------------------------------------------------------------

    @Override
    public String describe() {
        return "VectorizedScan[" + tableName + "]";
    }

    @Override
    public String toString() {
        return describe();
    }
}
