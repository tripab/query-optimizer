package org.query.optimizer.vectorized;

import org.query.optimizer.catalog.Attribute;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.Tuple;
import org.query.optimizer.executor.Executor.ExecutionResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives a vectorized operator tree and materialises the results into the same
 * {@link ExecutionResult} type produced by the Volcano {@link org.query.optimizer.executor.Executor}.
 *
 * <h2>Execution loop</h2>
 * <pre>{@code
 *   plan.open();
 *   ColumnBatch batch;
 *   while ((batch = plan.next()) != null) {
 *       materializeBatch(batch, results);
 *   }
 *   plan.close();
 * }</pre>
 *
 * <h2>Materialization</h2>
 * <p>Each {@link ColumnBatch} is converted to a sequence of {@link Tuple} objects
 * (column-major → row-major). When the batch carries a selection vector only the
 * selected row indices are emitted; physical rows not in the selection vector are
 * skipped without touching their column data.
 *
 * <h2>Shared result type</h2>
 * <p>Returning {@link ExecutionResult} (the same class used by the Volcano executor)
 * means correctness tests can compare the two engines with a single
 * {@code assertEquals(volcanoResult.tuples(), vectorizedResult.tuples())} call —
 * no adapter code needed.
 *
 * <h2>Statistics</h2>
 * <p>The result records wall-clock execution time (milliseconds) and the total number
 * of tuples materialised, mirroring the Volcano executor's statistics fields.
 */
public class VectorizedExecutor {

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Executes {@code plan} to completion and returns all result tuples.
     *
     * @param plan the root of a vectorized operator tree
     * @return execution result containing tuples and the tuple count
     */
    public ExecutionResult execute(VectorizedOperator plan) {
        List<Tuple> results = new ArrayList<>();
        long tuplesProduced = 0;

        try {
            plan.open();
            ColumnBatch batch;
            while ((batch = plan.next()) != null) {
                tuplesProduced += materializeBatch(batch, plan.getOutputSchema(), results);
            }
        } finally {
            plan.close();
        }

        return new ExecutionResult(results, tuplesProduced);
    }

    /**
     * Executes {@code plan} and prints results to stdout in a formatted table,
     * mirroring the presentation of {@link org.query.optimizer.executor.Executor#executeAndPrint}.
     *
     * @param plan the root of a vectorized operator tree
     * @return the execution result (same as {@link #execute})
     */
    public ExecutionResult executeAndPrint(VectorizedOperator plan) {
        ExecutionResult result = execute(plan);

        Schema schema = plan.getOutputSchema();
        List<String> colNames = schema.getColumns().stream()
                .map(Schema.Column::name)
                .toList();

        printHeader(colNames);
        for (Tuple tuple : result.tuples()) {
            printRow(tuple);
        }

        System.out.println();
        System.out.printf("%d row(s) returned%n", result.getResultCount());

        return result;
    }

    // -------------------------------------------------------------------------
    // Materialization
    // -------------------------------------------------------------------------

    /**
     * Converts one {@link ColumnBatch} into {@link Tuple} objects and appends them
     * to {@code out}.
     *
     * <p>If the batch has an active selection vector, only the rows indexed by the
     * selection vector are emitted. Otherwise all rows in {@code [0, batch.getSize())}
     * are emitted.
     *
     * @param batch  the batch to materialise
     * @param schema the output schema describing column names and types
     * @param out    accumulator list to append tuples to
     * @return the number of tuples appended
     */
    private static int materializeBatch(ColumnBatch batch, Schema schema, List<Tuple> out) {
        int colCount = schema.columnCount();

        if (batch.hasSelectionVector()) {
            int[]  sv   = batch.getSelectionVector();
            int    size = batch.getSelectionSize();
            for (int i = 0; i < size; i++) {
                out.add(materializeRow(batch, schema, sv[i], colCount));
            }
            return size;
        } else {
            int size = batch.getSize();
            for (int row = 0; row < size; row++) {
                out.add(materializeRow(batch, schema, row, colCount));
            }
            return size;
        }
    }

    /**
     * Reads one physical row from {@code batch} and assembles it as a {@link Tuple}.
     *
     * @param batch    source batch
     * @param schema   schema for column name lookup
     * @param rowIndex physical row index within the batch
     * @param colCount number of columns
     * @return a new Tuple with one {@link Attribute} per column
     */
    private static Tuple materializeRow(ColumnBatch batch, Schema schema,
                                         int rowIndex, int colCount) {
        Tuple tuple = new Tuple();
        for (int c = 0; c < colCount; c++) {
            Schema.Column col   = schema.getColumn(c);
            Object        value = batch.getVector(c).get(rowIndex);
            tuple.add(new Attribute(col, value));
        }
        return tuple;
    }

    // -------------------------------------------------------------------------
    // Pretty-printing (mirrors Executor formatting)
    // -------------------------------------------------------------------------

    private static void printHeader(List<String> columnNames) {
        System.out.println();
        for (int i = 0; i < columnNames.size(); i++) {
            if (i > 0) System.out.print(" | ");
            System.out.printf("%-15s", columnNames.get(i));
        }
        System.out.println();
        for (int i = 0; i < columnNames.size(); i++) {
            if (i > 0) System.out.print("-+-");
            System.out.print("---------------");
        }
        System.out.println();
    }

    private static void printRow(Tuple tuple) {
        int i = 0;
        for (Attribute attr : tuple) {
            if (i++ > 0) System.out.print(" | ");
            String value = attr.getValue() != null ? attr.getValue().toString() : "NULL";
            if (value.length() > 15) value = value.substring(0, 12) + "...";
            System.out.printf("%-15s", value);
        }
        System.out.println();
    }
}
