package org.query.optimizer.vectorized;

import org.query.optimizer.catalog.DataType;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.Expression.BinaryOp;
import org.query.optimizer.logical.Expression.BinaryOp.Operator;
import org.query.optimizer.logical.Expression.ColumnRef;
import org.query.optimizer.logical.Expression.Literal;

/**
 * Batch-oriented predicate evaluation for vectorized filter operators.
 *
 * <h2>Core contract</h2>
 * <p>{@link #evaluateFilter} takes an {@link Expression} predicate, a {@link ColumnBatch},
 * and a caller-provided {@code int[]} buffer. It writes the physical row indices of all rows
 * that satisfy the predicate into the buffer and returns the count. The caller installs the
 * result as the batch's selection vector.
 *
 * <h2>Tight loops</h2>
 * <p>For the common case of {@code ColumnRef op Literal} comparisons (e.g. {@code price > 100}),
 * evaluation runs a tight loop directly over the underlying typed primitive array — no boxing,
 * no virtual dispatch, no per-row method calls. The JIT can autovectorise these loops on
 * modern hardware.
 *
 * <h2>Existing selection vector</h2>
 * <p>If the incoming batch already has a selection vector (e.g. from a prior filter), the
 * evaluator only tests the already-selected rows, effectively <em>intersecting</em> the two
 * filter results without any data movement.
 *
 * <h2>AND / OR</h2>
 * <ul>
 *   <li><b>AND</b>: evaluate the left branch on the candidates to produce a reduced candidate
 *       set, then evaluate the right branch only on that reduced set. This is short-circuit
 *       evaluation without any extra allocation.</li>
 *   <li><b>OR</b>: evaluate the left and right branches independently on the same candidate
 *       set, then merge (union) the two result arrays into the output buffer. A bitset over
 *       the batch avoids duplicates in O(batchSize) time.</li>
 * </ul>
 *
 * <h2>Supported expression shapes</h2>
 * <ul>
 *   <li>{@code ColumnRef op Literal} — all six comparison operators</li>
 *   <li>{@code Literal op ColumnRef} — arguments are swapped and the operator is flipped</li>
 *   <li>{@code BinaryOp(AND, …)} — nested to arbitrary depth</li>
 *   <li>{@code BinaryOp(OR, …)} — nested to arbitrary depth</li>
 * </ul>
 * Other expression shapes throw {@link UnsupportedOperationException}.
 */
public final class VectorizedExpressionEvaluator {

    private VectorizedExpressionEvaluator() {}

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    /**
     * Evaluates {@code predicate} against {@code batch}, writing qualifying physical row
     * indices into {@code outSelection} and returning the number of selected rows.
     *
     * <p>If {@code batch} carries an existing selection vector, only the already-selected
     * rows are evaluated (intersection semantics).
     *
     * @param predicate    the filter expression to evaluate
     * @param batch        the batch to evaluate against
     * @param outSelection caller-allocated buffer; must have length ≥ batch size
     * @return number of rows written into {@code outSelection}
     */
    public static int evaluateFilter(Expression predicate,
                                     ColumnBatch batch,
                                     int[] outSelection) {
        if (batch.hasSelectionVector()) {
            return evalPredicate(predicate, batch,
                    batch.getSelectionVector(), batch.getSelectionSize(),
                    outSelection);
        } else {
            return evalPredicate(predicate, batch,
                    null, batch.getSize(),
                    outSelection);
        }
    }

    // -------------------------------------------------------------------------
    // Recursive predicate evaluation
    // -------------------------------------------------------------------------

    /**
     * Core recursive evaluator.
     *
     * @param predicate    expression to evaluate
     * @param batch        the batch whose column vectors are read
     * @param candidates   the row indices to evaluate; {@code null} means all rows [0, count)
     * @param count        number of valid entries in {@code candidates}
     * @param out          output buffer for qualifying row indices
     * @return number of qualifying rows written into {@code out}
     */
    private static int evalPredicate(Expression predicate,
                                     ColumnBatch batch,
                                     int[] candidates, int count,
                                     int[] out) {
        if (predicate instanceof BinaryOp bop) {
            return switch (bop.operator()) {
                case AND -> evalAnd(bop, batch, candidates, count, out);
                case OR  -> evalOr (bop, batch, candidates, count, out);
                default  -> evalComparison(bop, batch, candidates, count, out);
            };
        }
        throw new UnsupportedOperationException(
                "Unsupported predicate type: " + predicate.getClass().getSimpleName());
    }

    // -------------------------------------------------------------------------
    // AND / OR
    // -------------------------------------------------------------------------

    /**
     * AND: evaluate left → pass its result as the new candidate set to right.
     * The two temporary buffers needed are the {@code out} buffer (first pass)
     * and a small scratch array (second pass swaps back into {@code out}).
     */
    private static int evalAnd(BinaryOp bop, ColumnBatch batch,
                                int[] candidates, int count, int[] out) {
        // First, evaluate left into `out`
        int leftCount = evalPredicate(bop.left(), batch, candidates, count, out);
        if (leftCount == 0) return 0;

        // Then evaluate right using `out[0..leftCount)` as candidates.
        // We need a second buffer so we don't clobber `out` while reading it.
        // Reuse a thread-local scratch buffer sized to the batch capacity.
        int[] scratch = new int[out.length];
        int rightCount = evalPredicate(bop.right(), batch, out, leftCount, scratch);

        // Copy result back into `out`
        System.arraycopy(scratch, 0, out, 0, rightCount);
        return rightCount;
    }

    /**
     * OR: evaluate left and right independently on the same candidates,
     * then union the results. Uses a small boolean bitset over physical row
     * indices to deduplicate in a single O(n) pass.
     */
    private static int evalOr(BinaryOp bop, ColumnBatch batch,
                               int[] candidates, int count, int[] out) {
        int batchSize = batch.getSize();
        boolean[] seen = new boolean[batchSize];

        int[] leftOut  = new int[batchSize];
        int[] rightOut = new int[batchSize];

        int leftCount  = evalPredicate(bop.left(),  batch, candidates, count, leftOut);
        int rightCount = evalPredicate(bop.right(), batch, candidates, count, rightOut);

        int total = 0;
        for (int i = 0; i < leftCount; i++) {
            int row = leftOut[i];
            if (!seen[row]) { seen[row] = true; out[total++] = row; }
        }
        for (int i = 0; i < rightCount; i++) {
            int row = rightOut[i];
            if (!seen[row]) { seen[row] = true; out[total++] = row; }
        }
        // Re-sort so selection vector stays in ascending order (required by
        // downstream operators that assume indices are monotonically increasing)
        sortAscending(out, total);
        return total;
    }

    // -------------------------------------------------------------------------
    // Comparison dispatching
    // -------------------------------------------------------------------------

    private static int evalComparison(BinaryOp bop, ColumnBatch batch,
                                      int[] candidates, int count, int[] out) {
        Expression left  = bop.left();
        Expression right = bop.right();
        Operator   op    = bop.operator();

        // Normalise: ColumnRef op Literal  OR  Literal op ColumnRef (flipped)
        if (left instanceof ColumnRef colRef && right instanceof Literal<?> lit) {
            return evalColumnLiteral(colRef, op, lit, batch, candidates, count, out);
        }
        if (left instanceof Literal<?> lit && right instanceof ColumnRef colRef) {
            return evalColumnLiteral(colRef, flip(op), lit, batch, candidates, count, out);
        }
        throw new UnsupportedOperationException(
                "evaluateFilter only supports ColumnRef op Literal comparisons, got: "
                + bop.toSQLString());
    }

    /**
     * Evaluates {@code columnRef op literal} over the candidate rows of {@code batch},
     * dispatching to a type-specialized tight loop.
     */
    private static int evalColumnLiteral(ColumnRef colRef, Operator op, Literal<?> lit,
                                         ColumnBatch batch,
                                         int[] candidates, int count, int[] out) {
        Schema schema  = batch.getSchema();
        int    colIdx  = schema.getColumnIndex(colRef.columnName());
        ColumnVector vec = batch.getVector(colIdx);
        DataType colType = vec.getType();

        return switch (colType) {
            case INTEGER -> evalInt   (vec.getIntData(),    op, toDouble(lit.value()), candidates, count, out);
            case FLOAT   -> evalFloat (vec.getFloatData(),  op, toDouble(lit.value()), candidates, count, out);
            case VARCHAR -> evalString(vec.getStringData(), op, lit.value(),            candidates, count, out);
        };
    }

    // -------------------------------------------------------------------------
    // Typed tight loops
    // -------------------------------------------------------------------------

    /**
     * Tight loop over an {@code int[]} column. Compares against a double threshold
     * so that an {@code INTEGER} column can be compared to a {@code FLOAT} literal
     * without a cast branch inside the hot loop.
     */
    private static int evalInt(int[] data, Operator op, double threshold,
                                int[] candidates, int count, int[] out) {
        int selected = 0;
        if (candidates == null) {
            for (int i = 0; i < count; i++) {
                if (compareDouble(data[i], threshold, op)) out[selected++] = i;
            }
        } else {
            for (int i = 0; i < count; i++) {
                int row = candidates[i];
                if (compareDouble(data[row], threshold, op)) out[selected++] = row;
            }
        }
        return selected;
    }

    /** Tight loop over a {@code float[]} column. */
    private static int evalFloat(float[] data, Operator op, double threshold,
                                  int[] candidates, int count, int[] out) {
        int selected = 0;
        if (candidates == null) {
            for (int i = 0; i < count; i++) {
                if (compareDouble(data[i], threshold, op)) out[selected++] = i;
            }
        } else {
            for (int i = 0; i < count; i++) {
                int row = candidates[i];
                if (compareDouble(data[row], threshold, op)) out[selected++] = row;
            }
        }
        return selected;
    }

    /** Tight loop over a {@code String[]} column. */
    @SuppressWarnings("unchecked")
    private static int evalString(String[] data, Operator op, Object literalValue,
                                   int[] candidates, int count, int[] out) {
        String threshold = literalValue.toString();
        int selected = 0;
        if (candidates == null) {
            for (int i = 0; i < count; i++) {
                if (data[i] != null && compareString(data[i], threshold, op)) out[selected++] = i;
            }
        } else {
            for (int i = 0; i < count; i++) {
                int row = candidates[i];
                if (data[row] != null && compareString(data[row], threshold, op)) out[selected++] = row;
            }
        }
        return selected;
    }

    // -------------------------------------------------------------------------
    // Comparison helpers
    // -------------------------------------------------------------------------

    private static boolean compareDouble(double colVal, double threshold, Operator op) {
        return switch (op) {
            case EQ  -> colVal == threshold;
            case NEQ -> colVal != threshold;
            case GT  -> colVal >  threshold;
            case GTE -> colVal >= threshold;
            case LT  -> colVal <  threshold;
            case LTE -> colVal <= threshold;
            default  -> throw new UnsupportedOperationException("Not a comparison: " + op);
        };
    }

    private static boolean compareString(String colVal, String threshold, Operator op) {
        int cmp = colVal.compareTo(threshold);
        return switch (op) {
            case EQ  -> cmp == 0;
            case NEQ -> cmp != 0;
            case GT  -> cmp >  0;
            case GTE -> cmp >= 0;
            case LT  -> cmp <  0;
            case LTE -> cmp <= 0;
            default  -> throw new UnsupportedOperationException("Not a comparison: " + op);
        };
    }

    /** Converts a literal value (Integer, Float, Double) to double for numeric comparisons. */
    private static double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        throw new UnsupportedOperationException(
                "Cannot convert literal to numeric: " + value + " (" + value.getClass().getSimpleName() + ")");
    }

    /**
     * Flips a comparison operator for when literal and column reference positions are swapped.
     * e.g. {@code 100 < price} becomes {@code price > 100}.
     */
    private static Operator flip(Operator op) {
        return switch (op) {
            case GT  -> Operator.LT;
            case GTE -> Operator.LTE;
            case LT  -> Operator.GT;
            case LTE -> Operator.GTE;
            case EQ  -> Operator.EQ;
            case NEQ -> Operator.NEQ;
            default  -> throw new UnsupportedOperationException("Cannot flip logical op: " + op);
        };
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Insertion sort on the first {@code len} elements of {@code arr}.
     * Selection vectors are typically already nearly-sorted (they come from forward
     * iteration), so insertion sort is optimal here — O(n) for nearly-sorted input.
     */
    private static void sortAscending(int[] arr, int len) {
        for (int i = 1; i < len; i++) {
            int key = arr[i];
            int j   = i - 1;
            while (j >= 0 && arr[j] > key) {
                arr[j + 1] = arr[j];
                j--;
            }
            arr[j + 1] = key;
        }
    }
}
