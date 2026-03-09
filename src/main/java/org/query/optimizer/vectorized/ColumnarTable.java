package org.query.optimizer.vectorized;

import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.TableMetadata;

import java.util.List;
import java.util.Map;

/**
 * An entire table stored in columnar layout: one {@link ColumnVector} per
 * column, each containing all rows for that column.
 *
 * <p>The existing {@link TableMetadata} stores data in row-major form:
 * {@code List<Map<Schema.Column, Object>>}. {@code ColumnarTable} performs a
 * one-time <em>pivot</em> of that representation into column-major form.
 * This is directly analogous to what CockroachDB's vectorized engine does:
 * data lives on disk in row-oriented SSTs but is transposed to columnar
 * in-memory for vectorized execution.
 *
 * <h2>Why columnar layout matters</h2>
 * <p>A filter that touches only the {@code price} column needs to load only
 * {@code price}'s {@code float[]} into cache. In row-oriented storage every
 * cache line also pulls in {@code id}, {@code name}, and every other column
 * that the filter does not need. Columnar layout keeps relevant data contiguous,
 * maximising cache utilisation and enabling JIT autovectorisation of tight
 * comparison loops.
 *
 * <h2>Relationship to the catalog</h2>
 * <p>{@code ColumnarTable} is a <em>derived, read-only</em> view built from an
 * existing {@link TableMetadata}. It does not replace or modify
 * {@code TableMetadata}; the Volcano execution path continues to use the
 * row-oriented data unchanged. {@link org.query.optimizer.catalog.Catalog}
 * lazily creates and caches a {@code ColumnarTable} per table the first time
 * {@code getColumnarTable(tableName)} is called.
 *
 * <h2>Usage by VectorizedScan</h2>
 * <p>{@code VectorizedScan} holds a reference to a {@code ColumnarTable} and
 * calls {@link #getColumn(int)} to copy column slices into its output
 * {@link ColumnBatch} on each {@code next()} call.
 */
public class ColumnarTable {

    private final String        tableName;
    private final Schema        schema;
    private final int           rowCount;
    private final ColumnVector[] columns;   // one per schema column, length = rowCount

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    private ColumnarTable(String tableName, Schema schema, int rowCount, ColumnVector[] columns) {
        this.tableName = tableName;
        this.schema    = schema;
        this.rowCount  = rowCount;
        this.columns   = columns;
    }

    /**
     * Pivots a row-oriented {@link TableMetadata} into a columnar
     * {@code ColumnarTable}.
     *
     * <p>The algorithm is a straightforward transpose: for each column index
     * {@code c}, iterate all rows and write {@code row[c]} into
     * {@code columns[c][rowIndex]}. This is O(rows × columns) time and
     * O(rows × columns) space — the same asymptotic cost as the source data.
     *
     * <p>The pivot is performed once at construction time. After that, all
     * column accesses are O(1) array lookups.
     *
     * @param table the source row-oriented table; must not be null
     * @return a new {@code ColumnarTable} containing a full copy of the data
     */
    public static ColumnarTable fromTableMetadata(TableMetadata table) {
        Schema schema   = table.getSchema();
        int    rowCount = (int) table.getRowCount();
        int    colCount = schema.columnCount();

        // Allocate one full-length ColumnVector per column
        ColumnVector[] columns = new ColumnVector[colCount];
        for (int c = 0; c < colCount; c++) {
            columns[c] = ColumnVector.create(schema.getColumn(c).type(), rowCount);
        }

        // Pivot: row-major → column-major
        List<Map<Schema.Column, Object>> rows = table.getData();
        for (int r = 0; r < rowCount; r++) {
            Map<Schema.Column, Object> row = rows.get(r);
            for (int c = 0; c < colCount; c++) {
                Schema.Column col   = schema.getColumn(c);
                Object        value = row.get(col);
                columns[c].put(r, value);   // handles null via ColumnVector.setNull()
            }
        }

        return new ColumnarTable(table.getTableName(), schema, rowCount, columns);
    }

    // -------------------------------------------------------------------------
    // Column access
    // -------------------------------------------------------------------------

    /**
     * Returns the {@link ColumnVector} for the column at {@code index}.
     * The vector contains all {@link #getRowCount()} rows for that column.
     *
     * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
     */
    public ColumnVector getColumn(int index) {
        return columns[index];
    }

    /**
     * Returns the {@link ColumnVector} for the column with the given name
     * (case-insensitive).
     *
     * @throws IllegalArgumentException if no column with that name exists
     */
    public ColumnVector getColumn(String name) {
        return columns[schema.getColumnIndex(name)];
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    /** Returns the table name. */
    public String getTableName() {
        return tableName;
    }

    /** Returns the schema describing this table's columns. */
    public Schema getSchema() {
        return schema;
    }

    /** Returns the total number of rows in this table. */
    public int getRowCount() {
        return rowCount;
    }

    @Override
    public String toString() {
        return String.format("ColumnarTable[%s, rows=%d, schema=%s]",
                tableName, rowCount, schema);
    }
}
