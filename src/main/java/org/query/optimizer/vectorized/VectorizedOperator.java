package org.query.optimizer.vectorized;

import org.query.optimizer.catalog.Schema;

/**
 * Interface for batch-at-a-time (vectorized) query operators.
 *
 * <p>This is the vectorized counterpart of the Volcano {@link org.query.optimizer.executor.Iterator}
 * interface. Where {@code Iterator.next()} returns a single {@code Tuple}, {@code VectorizedOperator.next()}
 * returns a {@link ColumnBatch} carrying up to {@link ColumnBatch#DEFAULT_BATCH_SIZE} rows at once.
 * This amortizes the per-call overhead of the pull-based pipeline across an entire batch of rows.
 *
 * <h2>Lifecycle</h2>
 * <p>The lifecycle mirrors the Volcano model exactly:
 * <ol>
 *   <li>{@link #open()} — Called once before any {@code next()} calls. Operators use this to open
 *       child operators, allocate reusable output batches, and perform one-time setup (e.g. building
 *       a hash table in {@code VectorizedHashJoin}).</li>
 *   <li>{@link #next()} — Called repeatedly until it returns {@code null}. Each call returns a
 *       {@link ColumnBatch} containing the next batch of rows. Operators reuse the same
 *       {@code ColumnBatch} instance across calls (allocated in {@code open()}) — callers that need
 *       to retain a batch beyond the next {@code next()} call must copy it.</li>
 *   <li>{@link #close()} — Called once after all batches have been consumed. Releases resources and
 *       closes child operators.</li>
 * </ol>
 *
 * <h2>Selection vectors</h2>
 * <p>Filter operators do not physically remove non-qualifying rows. Instead they install a
 * <em>selection vector</em> on the returned batch (see {@link ColumnBatch#setSelectionVector}).
 * Downstream operators iterate over {@code selectionVector[0..selectionSize)} rather than
 * {@code 0..size}. This avoids data movement and allows tight loops over typed arrays.
 *
 * <h2>Output schema</h2>
 * <p>{@link #getOutputSchema()} is available at any point after construction (before {@code open()}).
 * It is used by {@code VectorizedPlanBuilder} to wire operators together and validate column references.
 *
 * <h2>Comparison with the Volcano iterator</h2>
 * <pre>{@code
 *   // Volcano: one tuple per call, Object[] boxing
 *   Tuple row = scan.next();            // ~1 row
 *
 *   // Vectorized: one batch per call, typed arrays
 *   ColumnBatch batch = scan.next();    // up to 1024 rows
 * }</pre>
 */
public interface VectorizedOperator {

    /**
     * Initializes this operator and opens its children.
     *
     * <p>Implementations should allocate output {@link ColumnBatch}es and any reusable buffers
     * here. For blocking operators (e.g. hash join), the build phase runs entirely during
     * {@code open()}.
     */
    void open();

    /**
     * Returns the next batch of rows, or {@code null} if the operator is exhausted.
     *
     * <p>The returned batch is owned by this operator and its contents are only guaranteed
     * to be stable until the next call to {@code next()}. Callers that need to retain the
     * data (e.g. for building a hash table) must copy the relevant values out of the batch
     * before calling {@code next()} again.
     *
     * <p>The batch may carry a selection vector (see {@link ColumnBatch#hasSelectionVector()}).
     * When it does, only the rows indexed by the selection vector are considered live; all
     * other rows in the batch should be ignored.
     *
     * @return a {@link ColumnBatch} with at least one live row, or {@code null} when exhausted
     */
    ColumnBatch next();

    /**
     * Releases all resources held by this operator and closes its children.
     *
     * <p>After {@code close()} returns, no further calls to {@code next()} are permitted.
     */
    void close();

    /**
     * Returns the schema of the batches produced by this operator.
     *
     * <p>The output schema is fixed at construction time and does not change between
     * {@code open()} and {@code close()}. It is safe to call before {@code open()}.
     *
     * @return the schema describing the columns in each output {@link ColumnBatch}
     */
    Schema getOutputSchema();

    /**
     * Returns a human-readable description of this operator and its children,
     * indented to reflect the operator tree depth.
     *
     * <p>The default implementation returns the simple class name followed by the
     * output schema column names.  Implementations should override this to include
     * operator-specific details (table name, predicate, join condition, etc.) and
     * to recurse into child operators.
     *
     * @return a multi-line string suitable for printing to stdout
     */
    default String describe() {
        return getClass().getSimpleName() +
               "[" + getOutputSchema().getColumns().stream()
                       .map(Schema.Column::name)
                       .reduce((a, b) -> a + ", " + b)
                       .orElse("") + "]";
    }
}
