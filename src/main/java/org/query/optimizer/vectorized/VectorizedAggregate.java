package org.query.optimizer.vectorized;

import org.query.optimizer.catalog.DataType;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.parser.LogicalAggregate.AggFunction;
import org.query.optimizer.parser.LogicalAggregate.AggregateOp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vectorized aggregation operator (GROUP BY with COUNT / SUM / AVG / MIN / MAX).
 *
 * <h2>Algorithm</h2>
 * <p>Aggregation is a <em>blocking</em> operator: the entire input must be consumed
 * before the first output row can be emitted.  Execution has two clearly separated
 * phases:
 *
 * <ol>
 *   <li><b>Accumulation phase</b> — the first call to {@link #next()} that finds
 *       {@code !accumulated} drains all input batches via a private helper.  For
 *       every live row in each batch the group key is extracted, an
 *       {@link AggregateAccumulator} array is looked up (or created) in the
 *       group-state map, and each accumulator is updated with the row's aggregate
 *       column value.</li>
 *   <li><b>Emission phase</b> — subsequent calls to {@link #next()} materialise
 *       groups from the map into output {@link ColumnBatch}es of up to
 *       {@link ColumnBatch#DEFAULT_BATCH_SIZE} rows per call.  A
 *       {@link java.util.Iterator} over the map entries drives emission and
 *       persists across calls so that a large number of groups can be returned
 *       across multiple batches.</li>
 * </ol>
 *
 * <h2>Group key encoding</h2>
 * <p>The group key is a {@link List}{@code <Object>} of the group-by column values
 * in schema order.  {@code List} is used rather than {@code Object[]} because it
 * provides correct {@link Object#equals}/{@link Object#hashCode} semantics for use
 * as a {@link java.util.HashMap} key.  For the common single-column GROUP BY the
 * list has exactly one element, which is still O(1) to create and look up.
 * An empty list (size 0) represents the implicit single group used when there are
 * no GROUP BY columns ({@code SELECT COUNT(*) FROM t}).
 *
 * <h2>Output schema</h2>
 * <p>Group-by columns appear first (in the order they were declared in the SQL),
 * followed by aggregate output columns in declaration order.  Column types for
 * group-by columns are taken verbatim from the input schema.  Aggregate output
 * types are inferred as follows:
 * <ul>
 *   <li>{@code COUNT} → {@link DataType#INTEGER}</li>
 *   <li>{@code SUM}   → same as the input column type</li>
 *   <li>{@code AVG}   → {@link DataType#FLOAT} (averages are generally fractional)</li>
 *   <li>{@code MIN} / {@code MAX} → same as the input column type</li>
 * </ul>
 *
 * <h2>Batch output</h2>
 * <p>A single {@link ColumnBatch} is allocated once in {@link #open()} and reused
 * across emission calls.  Only its {@link ColumnBatch#setSize size} and the values
 * written into its column vectors change between calls.  No selection vector is
 * ever set on the output — all rows in the batch are always live.
 *
 * <h2>Selection vector handling</h2>
 * <p>During accumulation the operator fully respects any selection vector present
 * on input batches, accumulating only the rows selected by that vector.
 */
public class VectorizedAggregate implements VectorizedOperator {

    private final VectorizedOperator     input;
    private final List<String>           groupByColumns;
    private final List<AggregateOp>      aggregateOps;

    // ---- Resolved in open() ----
    private Schema  inputSchema;
    private Schema  outputSchema;

    /** Indices of group-by columns in the input schema. */
    private int[]   groupByColIndices;
    /** Indices of the aggregate input columns in the input schema, parallel to aggregateOps. */
    private int[]   aggInputColIndices;
    /** DataType of each aggregate input column, parallel to aggregateOps. */
    private DataType[] aggInputTypes;

    // ---- Accumulation state ----
    /**
     * Maps group key (List<Object>) → one AggregateAccumulator per aggregateOp.
     * {@link LinkedHashMap} preserves insertion order so output is deterministic,
     * making end-to-end correctness tests easier to reason about.
     */
    private Map<List<Object>, AggregateAccumulator[]> groups;
    private boolean accumulated;

    // ---- Emission cursor ----
    private java.util.Iterator<Map.Entry<List<Object>, AggregateAccumulator[]>> emitIter;

    // ---- Output batch ----
    private ColumnBatch outputBatch;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * @param input          upstream operator to aggregate
     * @param groupByColumns GROUP BY column names, in declaration order; may be empty
     * @param aggregateOps   aggregate functions to compute, in declaration order
     */
    public VectorizedAggregate(VectorizedOperator      input,
                                List<String>            groupByColumns,
                                List<AggregateOp>       aggregateOps) {
        this.input          = input;
        this.groupByColumns = List.copyOf(groupByColumns);
        this.aggregateOps   = List.copyOf(aggregateOps);
    }

    // -------------------------------------------------------------------------
    // VectorizedOperator
    // -------------------------------------------------------------------------

    @Override
    public void open() {
        input.open();
        inputSchema = input.getOutputSchema();

        // Resolve group-by column indices
        groupByColIndices = new int[groupByColumns.size()];
        for (int i = 0; i < groupByColumns.size(); i++) {
            groupByColIndices[i] = inputSchema.getColumnIndex(groupByColumns.get(i));
        }

        // Resolve aggregate input column indices and their types
        aggInputColIndices = new int[aggregateOps.size()];
        aggInputTypes      = new DataType[aggregateOps.size()];
        for (int i = 0; i < aggregateOps.size(); i++) {
            AggregateOp op = aggregateOps.get(i);
            if (op.function() == AggFunction.COUNT && op.inputColumn().equals("*")) {
                // COUNT(*) — no specific column; we use column 0 as a dummy just
                // to obtain a valid index. The CountAccumulator ignores the value.
                aggInputColIndices[i] = 0;
                aggInputTypes[i]      = DataType.INTEGER; // unused for COUNT
            } else {
                int idx = inputSchema.getColumnIndex(op.inputColumn());
                aggInputColIndices[i] = idx;
                aggInputTypes[i]      = inputSchema.getColumn(idx).type();
            }
        }

        outputSchema = buildOutputSchema();
        groups       = new LinkedHashMap<>();
        accumulated  = false;
        outputBatch  = new ColumnBatch(outputSchema);
    }

    /**
     * Returns the next batch of aggregate results, or {@code null} when all groups
     * have been emitted.
     *
     * <p>The first call triggers the accumulation phase; subsequent calls continue
     * emitting from where the previous call left off.
     */
    @Override
    public ColumnBatch next() {
        // Phase 1: accumulate all input on the first call
        if (!accumulated) {
            accumulateAll();
            accumulated = true;
            emitIter = groups.entrySet().iterator();
        }

        // Phase 2: emit groups into output batches
        int outRow = 0;
        outputBatch.resetSelectionVector();

        while (outRow < ColumnBatch.DEFAULT_BATCH_SIZE && emitIter.hasNext()) {
            Map.Entry<List<Object>, AggregateAccumulator[]> entry = emitIter.next();
            List<Object>            groupKey  = entry.getKey();
            AggregateAccumulator[]  accums    = entry.getValue();

            // Write group-by column values
            for (int g = 0; g < groupByColumns.size(); g++) {
                outputBatch.getVector(g).put(outRow, groupKey.get(g));
            }

            // Write aggregate result values
            int baseIdx = groupByColumns.size();
            for (int a = 0; a < aggregateOps.size(); a++) {
                outputBatch.getVector(baseIdx + a).put(outRow, accums[a].result());
            }

            outRow++;
        }

        if (outRow == 0) return null;
        outputBatch.setSize(outRow);
        return outputBatch;
    }

    @Override
    public void close() {
        input.close();
        groups      = null;
        emitIter    = null;
        outputBatch = null;
        accumulated = false;
    }

    /**
     * Returns the output schema.  Safe to call before {@link #open()}.
     */
    @Override
    public Schema getOutputSchema() {
        if (outputSchema != null) return outputSchema;
        return buildOutputSchema();
    }

    // -------------------------------------------------------------------------
    // Accumulation
    // -------------------------------------------------------------------------

    /**
     * Drains all input batches and populates {@link #groups}.
     */
    private void accumulateAll() {
        ColumnBatch batch;
        while ((batch = input.next()) != null) {
            int   size = batch.getSelectionSize();
            int[] sv   = batch.hasSelectionVector() ? batch.getSelectionVector() : null;

            for (int i = 0; i < size; i++) {
                int row = (sv != null) ? sv[i] : i;
                accumulateRow(batch, row);
            }
        }
    }

    /**
     * Extracts the group key for physical row {@code row} in {@code batch},
     * looks up (or creates) its accumulator array, and updates each accumulator.
     */
    private void accumulateRow(ColumnBatch batch, int row) {
        // Build group key as a List<Object>
        List<Object> key = new ArrayList<>(groupByColIndices.length);
        for (int idx : groupByColIndices) {
            key.add(batch.getVector(idx).get(row));
        }

        // Look up or create accumulators for this group
        AggregateAccumulator[] accums = groups.computeIfAbsent(key, k -> {
            AggregateAccumulator[] arr = new AggregateAccumulator[aggregateOps.size()];
            for (int a = 0; a < aggregateOps.size(); a++) {
                arr[a] = AggregateAccumulator.create(aggregateOps.get(a).function(),
                                                     aggInputTypes[a]);
            }
            return arr;
        });

        // Update each accumulator
        for (int a = 0; a < aggregateOps.size(); a++) {
            AggregateOp op = aggregateOps.get(a);
            Object value;
            if (op.function() == AggFunction.COUNT && op.inputColumn().equals("*")) {
                // COUNT(*) passes a non-null sentinel so the accumulator always increments
                value = Boolean.TRUE;
            } else {
                value = batch.getVector(aggInputColIndices[a]).get(row);
            }
            accums[a].update(value);
        }
    }

    // -------------------------------------------------------------------------
    // Output schema construction
    // -------------------------------------------------------------------------

    /**
     * Builds the output schema: group-by columns (types from input schema) followed
     * by aggregate output columns (types inferred from function + input column type).
     */
    private Schema buildOutputSchema() {
        Schema src = (inputSchema != null) ? inputSchema : input.getOutputSchema();
        List<Schema.Column> cols = new ArrayList<>();

        // Group-by columns retain their input types
        for (String colName : groupByColumns) {
            cols.add(src.getColumn(colName));
        }

        // Aggregate output columns
        for (AggregateOp op : aggregateOps) {
            DataType outType = inferOutputType(op, src);
            cols.add(new Schema.Column(op.outputColumn(), outType));
        }

        return new Schema(cols);
    }

    /**
     * Infers the output {@link DataType} for an aggregate operation.
     *
     * <ul>
     *   <li>COUNT  → INTEGER</li>
     *   <li>AVG    → FLOAT (averages are fractional even for integer input)</li>
     *   <li>SUM / MIN / MAX → same type as the input column</li>
     * </ul>
     */
    private static DataType inferOutputType(AggregateOp op, Schema inputSchema) {
        return switch (op.function()) {
            case COUNT -> DataType.INTEGER;
            case AVG   -> DataType.FLOAT;
            case SUM, MIN, MAX -> {
                if (op.inputColumn().equals("*")) yield DataType.INTEGER;
                yield inputSchema.getColumn(op.inputColumn()).type();
            }
        };
    }

    // -------------------------------------------------------------------------
    // toString
    // -------------------------------------------------------------------------

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder("VectorizedAggregate[");
        if (!groupByColumns.isEmpty()) {
            sb.append("GROUP BY ").append(String.join(", ", groupByColumns));
            if (!aggregateOps.isEmpty()) sb.append("; ");
        }
        for (int i = 0; i < aggregateOps.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(aggregateOps.get(i));
        }
        sb.append("]\n  +-  ").append(input.describe().replace("\n", "\n      "));
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("VectorizedAggregate[");
        if (!groupByColumns.isEmpty()) {
            sb.append("GROUP BY ").append(String.join(", ", groupByColumns));
            if (!aggregateOps.isEmpty()) sb.append("; ");
        }
        for (int i = 0; i < aggregateOps.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(aggregateOps.get(i));
        }
        sb.append("]");
        return sb.toString();
    }
}
