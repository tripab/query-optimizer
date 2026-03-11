package org.query.optimizer.vectorized;

import org.query.optimizer.catalog.Schema;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.Expression.BinaryOp;
import org.query.optimizer.logical.Expression.ColumnRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Vectorized hash join operator (INNER equi-join only).
 *
 * <p>Implements the classic two-phase hash join algorithm adapted for
 * batch-at-a-time execution:
 *
 * <h2>Build phase ({@link #open()})</h2>
 * <p>The entire build-side (right input) is consumed during {@code open()}.
 * For every live row in every build batch the join key is extracted and the
 * row is materialised into an {@code Object[]} and stored in a
 * {@code HashMap<Object, List<Object[]>>}.  Materialising to row-major form
 * here is the same pragmatic choice made by Dremio and described in the InfoQ
 * vectorized-execution article: columnar storage is excellent for scan/filter
 * loops but hash-table insertion is naturally key-per-row, so a brief pivot
 * back to row form is worth the simplicity.
 *
 * <h2>Probe phase ({@link #next()})</h2>
 * <p>On each {@code next()} call the operator fills an output
 * {@link ColumnBatch} of up to {@link ColumnBatch#DEFAULT_BATCH_SIZE} rows by
 * iterating probe-side batches, looking up each probe key in the hash table,
 * and emitting one output row per (probe row, build row) match.  Selection
 * vectors on the probe input are respected — only live probe rows are looked
 * up.
 *
 * <h2>State machine</h2>
 * <p>Between {@code next()} calls the operator preserves:
 * <ul>
 *   <li>{@code currentProbeBatch} / {@code probeIdx} / {@code probeCount} —
 *       position within the current probe batch.</li>
 *   <li>{@code currentProbeRow} — physical row index of the probe row whose
 *       build-side matches are currently being drained.</li>
 *   <li>{@code currentMatches} / {@code matchIdx} — the list of build rows
 *       matching {@code currentProbeRow} and how far through them we are.</li>
 * </ul>
 * This allows a single (probe row, build row) match stream to span multiple
 * output batches without losing position.
 *
 * <h2>Output schema</h2>
 * <p>Probe schema columns come first, followed by build schema columns —
 * identical ordering to {@link org.query.optimizer.physical.PhysicalHashJoin},
 * so end-to-end correctness tests can compare the two engines without any
 * column-remapping.
 *
 * <h2>Limitations (educational scope)</h2>
 * <ul>
 *   <li>INNER join only.</li>
 *   <li>Single equi-join column only (condition must be
 *       {@code ColumnRef = ColumnRef}).</li>
 *   <li>Build side must fit entirely in the JVM heap.</li>
 * </ul>
 */
public class VectorizedHashJoin implements VectorizedOperator {

    private final VectorizedOperator probeInput;   // left side
    private final VectorizedOperator buildInput;   // right side (smaller)
    private final Expression         condition;

    // ---- Resolved during open() ----
    private Schema probeSchema;
    private Schema buildSchema;
    private Schema outputSchema;

    /** Column index within the probe batch for the join key. */
    private int probeKeyColIdx;
    /** Column index within the build batch (and materialised row) for the join key. */
    private int buildKeyColIdx;

    // ---- Hash table (populated during build phase) ----
    /** Maps join key → list of materialised build-side rows (Object[colCount]). */
    private Map<Object, List<Object[]>> hashTable;

    // ---- Probe-phase cursor state ----
    private ColumnBatch currentProbeBatch;
    /** Index into the current probe batch's live rows (selection-aware). */
    private int         probeIdx;
    /** Total live rows in {@code currentProbeBatch}. */
    private int         probeCount;
    /**
     * Selection vector of {@code currentProbeBatch}, or {@code null} when the
     * batch is dense (no selection vector).
     */
    private int[]       probeSv;

    /** Physical row index in {@code currentProbeBatch} whose matches we are draining. */
    private int         currentProbeRow;
    /** Build rows matching {@code currentProbeRow}; {@code null} when no active match list. */
    private List<Object[]> currentMatches;
    /** Index into {@code currentMatches} for the next unemitted match. */
    private int            matchIdx;

    // ---- Output ----
    /** Reused across {@code next()} calls; allocated once in {@link #open()}. */
    private ColumnBatch outputBatch;
    /** Number of rows written into {@code outputBatch} in the current call. */
    private int         outRow;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a hash join.
     *
     * @param probeInput left (probe) operator — typically the larger side
     * @param buildInput right (build) operator — typically the smaller side
     * @param condition  equi-join condition; must be a {@link BinaryOp} with
     *                   {@link ColumnRef} on both sides
     */
    public VectorizedHashJoin(VectorizedOperator probeInput,
                               VectorizedOperator buildInput,
                               Expression condition) {
        this.probeInput = probeInput;
        this.buildInput = buildInput;
        this.condition  = condition;
    }

    // -------------------------------------------------------------------------
    // VectorizedOperator
    // -------------------------------------------------------------------------

    /**
     * Opens both child operators, resolves join-key columns, and runs the build
     * phase to completion before returning.
     */
    @Override
    public void open() {
        probeSchema  = probeInput.getOutputSchema();
        buildSchema  = buildInput.getOutputSchema();
        outputSchema = combineSchemas(probeSchema, buildSchema);

        resolveJoinKeyColumns();

        // --- Build phase ---
        buildInput.open();
        hashTable = new HashMap<>();
        ColumnBatch batch;
        while ((batch = buildInput.next()) != null) {
            int    count = batch.getSelectionSize();
            int[]  sv    = batch.hasSelectionVector() ? batch.getSelectionVector() : null;
            for (int i = 0; i < count; i++) {
                int      row = (sv != null) ? sv[i] : i;
                Object   key = batch.getVector(buildKeyColIdx).get(row);
                Object[] mat = materializeRow(batch, buildSchema.columnCount(), row);
                hashTable.computeIfAbsent(key, k -> new ArrayList<>()).add(mat);
            }
        }
        buildInput.close();

        // --- Probe phase setup ---
        probeInput.open();
        currentProbeBatch = null;
        probeIdx          = 0;
        probeCount        = 0;
        probeSv           = null;
        currentProbeRow   = -1;
        currentMatches    = null;
        matchIdx          = 0;

        outputBatch = new ColumnBatch(outputSchema);
        outRow      = 0;
    }

    /**
     * Returns the next output batch (up to {@link ColumnBatch#DEFAULT_BATCH_SIZE}
     * rows), or {@code null} when the join is fully exhausted.
     *
     * <p>The same {@link ColumnBatch} instance is returned on every call; callers
     * must not hold a reference across calls.
     */
    @Override
    public ColumnBatch next() {
        outRow = 0;
        outputBatch.resetSelectionVector();

        outer:
        while (outRow < ColumnBatch.DEFAULT_BATCH_SIZE) {

            // ---- Drain pending matches for the current probe row ----
            while (currentMatches != null && matchIdx < currentMatches.size()) {
                if (outRow >= ColumnBatch.DEFAULT_BATCH_SIZE) break outer;
                emitRow(currentProbeBatch, currentProbeRow, currentMatches.get(matchIdx));
                matchIdx++;
                outRow++;
            }
            currentMatches = null;   // match list exhausted (or was null)

            // ---- Advance to the next live probe row ----
            if (currentProbeBatch == null || probeIdx >= probeCount) {
                // Fetch the next probe batch
                currentProbeBatch = probeInput.next();
                if (currentProbeBatch == null) {
                    // Probe side exhausted — we're done
                    break;
                }
                probeSv    = currentProbeBatch.hasSelectionVector()
                             ? currentProbeBatch.getSelectionVector()
                             : null;
                probeCount = currentProbeBatch.getSelectionSize();
                probeIdx   = 0;
            }

            // Translate selection-vector index → physical row index
            currentProbeRow = (probeSv != null) ? probeSv[probeIdx] : probeIdx;
            probeIdx++;

            // ---- Probe the hash table ----
            Object key = currentProbeBatch.getVector(probeKeyColIdx).get(currentProbeRow);
            List<Object[]> matches = hashTable.get(key);
            if (matches != null && !matches.isEmpty()) {
                currentMatches = matches;
                matchIdx       = 0;
                // Inner loop above will drain these on the next iteration
            }
            // No match → advance to next probe row (no output emitted for this row)
        }

        if (outRow == 0) return null;
        outputBatch.setSize(outRow);
        return outputBatch;
    }

    @Override
    public void close() {
        probeInput.close();
        if (hashTable != null) {
            hashTable.clear();
            hashTable = null;
        }
        currentProbeBatch = null;
        currentMatches    = null;
        outputBatch       = null;
    }

    /**
     * Returns the combined output schema (probe columns then build columns).
     * Safe to call before {@link #open()}.
     */
    @Override
    public Schema getOutputSchema() {
        if (outputSchema != null) return outputSchema;
        return combineSchemas(probeInput.getOutputSchema(), buildInput.getOutputSchema());
    }

    // -------------------------------------------------------------------------
    // Row materialization and emission
    // -------------------------------------------------------------------------

    /**
     * Reads {@code colCount} values from {@code batch} at physical row index
     * {@code row} into a new {@code Object[]}.
     */
    private static Object[] materializeRow(ColumnBatch batch, int colCount, int row) {
        Object[] vals = new Object[colCount];
        for (int c = 0; c < colCount; c++) {
            vals[c] = batch.getVector(c).get(row);
        }
        return vals;
    }

    /**
     * Writes one output row at slot {@code outRow} in {@code outputBatch} by
     * combining a probe row (read directly from its live batch) and a
     * build row (already materialised as {@code Object[]}).
     *
     * <p>Probe columns occupy {@code [0, probeColCount)} and build columns
     * occupy {@code [probeColCount, probeColCount + buildColCount)}.
     *
     * @param probeBatch the current probe-side batch
     * @param probeRow   physical row index within {@code probeBatch}
     * @param buildRow   materialised build-side row
     */
    private void emitRow(ColumnBatch probeBatch, int probeRow, Object[] buildRow) {
        int probeColCount = probeSchema.columnCount();
        int buildColCount = buildSchema.columnCount();

        for (int c = 0; c < probeColCount; c++) {
            outputBatch.getVector(c).put(outRow, probeBatch.getVector(c).get(probeRow));
        }
        for (int c = 0; c < buildColCount; c++) {
            outputBatch.getVector(probeColCount + c).put(outRow, buildRow[c]);
        }
    }

    // -------------------------------------------------------------------------
    // Schema helpers
    // -------------------------------------------------------------------------

    /**
     * Concatenates {@code left} and {@code right} schemas into a single schema
     * (left columns first, right columns second).  Mirrors the schema produced
     * by {@link org.query.optimizer.physical.PhysicalHashJoin} so that both
     * engines return identical column ordering.
     */
    private static Schema combineSchemas(Schema left, Schema right) {
        List<Schema.Column> cols = new ArrayList<>(left.columnCount() + right.columnCount());
        cols.addAll(left.getColumns());
        cols.addAll(right.getColumns());
        return new Schema(cols);
    }

    /**
     * Inspects the join condition and resolves which {@link ColumnRef} belongs
     * to the probe schema and which to the build schema, storing the resulting
     * column indices in {@link #probeKeyColIdx} and {@link #buildKeyColIdx}.
     *
     * <p>Two orderings are tried:
     * <ol>
     *   <li>left ref → probe, right ref → build</li>
     *   <li>right ref → probe, left ref → build</li>
     * </ol>
     *
     * @throws UnsupportedOperationException if the condition is not a
     *         {@link BinaryOp} with two {@link ColumnRef} operands
     * @throws IllegalArgumentException      if neither ordering resolves both
     *         columns to valid schemas
     */
    private void resolveJoinKeyColumns() {
        if (!(condition instanceof BinaryOp bop)) {
            throw new UnsupportedOperationException(
                    "VectorizedHashJoin requires a BinaryOp condition, got: " +
                    condition.getClass().getSimpleName());
        }
        if (!(bop.left()  instanceof ColumnRef leftRef) ||
            !(bop.right() instanceof ColumnRef rightRef)) {
            throw new UnsupportedOperationException(
                    "VectorizedHashJoin condition must be ColumnRef = ColumnRef, got: " +
                    condition.toSQLString());
        }

        if (probeSchema.hasColumn(leftRef.columnName())
                && buildSchema.hasColumn(rightRef.columnName())) {
            probeKeyColIdx = probeSchema.getColumnIndex(leftRef.columnName());
            buildKeyColIdx = buildSchema.getColumnIndex(rightRef.columnName());
        } else if (probeSchema.hasColumn(rightRef.columnName())
                && buildSchema.hasColumn(leftRef.columnName())) {
            probeKeyColIdx = probeSchema.getColumnIndex(rightRef.columnName());
            buildKeyColIdx = buildSchema.getColumnIndex(leftRef.columnName());
        } else {
            throw new IllegalArgumentException(
                    "Cannot resolve join condition columns against probe/build schemas. " +
                    "Condition: " + condition.toSQLString() +
                    ", probeSchema: " + probeSchema +
                    ", buildSchema: " + buildSchema);
        }
    }

    // -------------------------------------------------------------------------
    // toString
    // -------------------------------------------------------------------------

    @Override
    public String describe() {
        return "VectorizedHashJoin[" + condition.toSQLString() + "]\n" +
               "  +-- (probe) " + probeInput.describe().replace("\n", "\n  |           ") + "\n" +
               "  +-- (build) " + buildInput.describe().replace("\n", "\n              ");
    }

    @Override
    public String toString() {
        return "VectorizedHashJoin[" + condition.toSQLString() + "]";
    }
}
