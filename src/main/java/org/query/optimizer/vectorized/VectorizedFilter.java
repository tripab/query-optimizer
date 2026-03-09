package org.query.optimizer.vectorized;

import org.query.optimizer.catalog.Schema;
import org.query.optimizer.logical.Expression;

/**
 * Vectorized filter operator.
 *
 * <p>Wraps an upstream {@link VectorizedOperator} and a predicate {@link Expression}.
 * For each batch received from the input, it delegates to
 * {@link VectorizedExpressionEvaluator#evaluateFilter} to compute a selection vector,
 * then installs that vector on the batch before returning it to the caller.
 *
 * <h2>No data movement</h2>
 * <p>The batch itself is never copied. Non-qualifying rows are simply excluded from the
 * selection vector; their values remain in the underlying column arrays untouched.
 * Downstream operators iterate only over the selected indices:
 * <pre>{@code
 *   int[] sv   = batch.getSelectionVector();
 *   int   size = batch.getSelectionSize();
 *   for (int i = 0; i < size; i++) {
 *       int row = sv[i];
 *       // process row
 *   }
 * }</pre>
 *
 * <h2>Chained filters / existing selection vector</h2>
 * <p>If the incoming batch already carries a selection vector (from a prior filter lower
 * in the tree), {@link VectorizedExpressionEvaluator#evaluateFilter} automatically
 * intersects — it only tests the already-selected rows. This means stacking two
 * {@code VectorizedFilter} nodes is equivalent to a single AND predicate, with no
 * extra work.
 *
 * <h2>All-filtered batches</h2>
 * <p>When every row in a batch is eliminated, the operator loops internally and fetches
 * the next batch from the input rather than returning a zero-row batch to the caller.
 * This avoids forcing every downstream operator to handle the empty-batch edge case.
 *
 * <h2>Selection buffer reuse</h2>
 * <p>A single {@code int[]} selection buffer of size {@link ColumnBatch#DEFAULT_BATCH_SIZE}
 * is allocated in {@link #open()} and reused across all {@link #next()} calls.
 */
public class VectorizedFilter implements VectorizedOperator {

    private final VectorizedOperator input;
    private final Expression         predicate;

    // Allocated once in open(); reused across next() calls
    private int[] selectionBuffer;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a filter over {@code input} that keeps only rows satisfying {@code predicate}.
     *
     * @param input     the upstream operator to pull batches from
     * @param predicate the filter predicate; must be a supported expression shape
     *                  (see {@link VectorizedExpressionEvaluator})
     */
    public VectorizedFilter(VectorizedOperator input, Expression predicate) {
        this.input     = input;
        this.predicate = predicate;
    }

    // -------------------------------------------------------------------------
    // VectorizedOperator
    // -------------------------------------------------------------------------

    @Override
    public void open() {
        input.open();
        selectionBuffer = new int[ColumnBatch.DEFAULT_BATCH_SIZE];
    }

    /**
     * Returns the next batch that has at least one row passing the predicate, or
     * {@code null} when the input is exhausted.
     *
     * <p>Batches where every row is filtered out are consumed silently — the loop
     * continues pulling from the input until a non-empty result or end-of-input.
     */
    @Override
    public ColumnBatch next() {
        while (true) {
            ColumnBatch batch = input.next();
            if (batch == null) return null;

            int selected = VectorizedExpressionEvaluator.evaluateFilter(
                    predicate, batch, selectionBuffer);

            if (selected > 0) {
                batch.setSelectionVector(selectionBuffer, selected);
                return batch;
            }
            // Entire batch filtered out — fetch the next one
        }
    }

    @Override
    public void close() {
        input.close();
        selectionBuffer = null;
    }

    /** The output schema is identical to the input schema — filtering never removes columns. */
    @Override
    public Schema getOutputSchema() {
        return input.getOutputSchema();
    }

    // -------------------------------------------------------------------------
    // toString
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return "VectorizedFilter[" + predicate.toSQLString() + "]";
    }
}
