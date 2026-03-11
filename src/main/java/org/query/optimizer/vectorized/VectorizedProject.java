package org.query.optimizer.vectorized;

import org.query.optimizer.catalog.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * Vectorized projection operator.
 *
 * <p>Subsetting a batch to a chosen set of columns is nearly zero-cost in the columnar
 * model: instead of copying data, the output {@link ColumnBatch} simply holds references
 * to the relevant {@link ColumnVector}s from the input batch. No values are copied.
 *
 * <h2>Column subsetting only</h2>
 * <p>This implementation handles the common case of projecting a subset of existing
 * columns (e.g. {@code SELECT name, price FROM products}). Computed projections such as
 * {@code price * 0.8} are deferred to a future
 * {@code VectorizedExpressionEvaluator.evaluateProjection} extension.
 *
 * <h2>Zero allocation on the hot path</h2>
 * <p>One output {@link ColumnBatch} is allocated in {@link #open()} with the projected
 * schema and placeholder vectors. On each {@link #next()} call the operator swaps in
 * the correct {@link ColumnVector} references from the input batch via the package-private
 * {@link ColumnBatch#setVector}. No new objects are created per batch.
 *
 * <h2>Selection vector propagation</h2>
 * <p>The selection vector from the input batch (if any) is propagated to the output batch
 * unchanged. Filtering happens upstream; this operator does not need to understand it.
 */
public class VectorizedProject implements VectorizedOperator {

    private final VectorizedOperator input;
    private final List<String>        outputColumnNames;

    // Built during open()
    private Schema   outputSchema;
    private int[]    inputColIndices;   // outputColIndices[i] → index in input schema
    private ColumnBatch outputBatch;   // reused across next() calls

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a projection that retains only the named columns from the input.
     *
     * @param input             the upstream operator
     * @param outputColumnNames ordered list of column names to keep; names are
     *                          resolved case-insensitively against the input schema
     */
    public VectorizedProject(VectorizedOperator input, List<String> outputColumnNames) {
        this.input             = input;
        this.outputColumnNames = List.copyOf(outputColumnNames);
    }

    // -------------------------------------------------------------------------
    // VectorizedOperator
    // -------------------------------------------------------------------------

    @Override
    public void open() {
        input.open();

        Schema inputSchema = input.getOutputSchema();

        // Build output schema and the input→output column index mapping
        List<Schema.Column> outCols = new ArrayList<>(outputColumnNames.size());
        inputColIndices = new int[outputColumnNames.size()];

        for (int i = 0; i < outputColumnNames.size(); i++) {
            String colName = outputColumnNames.get(i);
            int    srcIdx  = inputSchema.getColumnIndex(colName);   // throws if missing
            inputColIndices[i] = srcIdx;
            outCols.add(inputSchema.getColumn(srcIdx));
        }

        outputSchema = new Schema(outCols);

        // Allocate output batch once — vectors will be swapped in on each next() call
        outputBatch = new ColumnBatch(outputSchema);
    }

    /**
     * Returns the next projected batch, or {@code null} when the input is exhausted.
     *
     * <p>The same {@link ColumnBatch} instance is returned on every call; only its
     * vector references, size, and selection vector change. Callers must not hold a
     * reference across calls.
     */
    @Override
    public ColumnBatch next() {
        ColumnBatch inputBatch = input.next();
        if (inputBatch == null) return null;

        // Swap in vector references from the input batch — no data copied
        for (int i = 0; i < inputColIndices.length; i++) {
            outputBatch.setVector(i, inputBatch.getVector(inputColIndices[i]));
        }

        // Propagate size and selection vector unchanged
        outputBatch.setSize(inputBatch.getSize());
        if (inputBatch.hasSelectionVector()) {
            outputBatch.setSelectionVector(
                    inputBatch.getSelectionVector(),
                    inputBatch.getSelectionSize());
        } else {
            outputBatch.resetSelectionVector();
        }

        return outputBatch;
    }

    @Override
    public void close() {
        input.close();
        outputBatch     = null;
        inputColIndices = null;
        outputSchema    = null;
    }

    @Override
    public Schema getOutputSchema() {
        if (outputSchema != null) return outputSchema;

        // Safe to call before open(): resolve from input schema without opening it
        Schema inputSchema = input.getOutputSchema();
        List<Schema.Column> outCols = new ArrayList<>(outputColumnNames.size());
        for (String colName : outputColumnNames) {
            outCols.add(inputSchema.getColumn(colName));
        }
        return new Schema(outCols);
    }

    // -------------------------------------------------------------------------
    // toString
    // -------------------------------------------------------------------------

    @Override
    public String describe() {
        return "VectorizedProject[" + String.join(", ", outputColumnNames) + "]\n" +
               "  +-  " + input.describe().replace("\n", "\n      ");
    }

    @Override
    public String toString() {
        return "VectorizedProject[" + String.join(", ", outputColumnNames) + "]";
    }
}
