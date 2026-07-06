package org.query.optimizer.physical;

import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.Tuple;
import org.query.optimizer.executor.Iterator;
import org.query.optimizer.logical.Expression;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * Physical nested loop join operator.
 * <p>
 * For each tuple from the left child, scan the entire right child
 * looking for matching tuples. Simple but can be expensive.
 * <p>
 * Algorithm:
 * for each left_tuple in left:
 * for each right_tuple in right:
 * if join_condition(left_tuple, right_tuple):
 * output (left_tuple, right_tuple)
 * <p>
 * The right (inner) input is materialized once during {@code open()} and then
 * re-scanned in memory for every left tuple. The comparison count stays
 * |L| x |R|, but the right subtree executes exactly once — re-executing it per
 * left tuple would quadratically re-do that subtree's work whenever the inner
 * input is itself a join or filter subtree.
 * <p>
 * The join condition is evaluated <em>side-aware</em>: each {@code ColumnRef} is
 * resolved against the side ({@code left} or {@code right}) that owns it and read
 * from that side's tuple, rather than from a flat concatenated tuple. This is
 * what keeps a qualified reference such as {@code customers.id} bound to the right
 * input even when the left input also exposes an {@code id} column — a collision
 * that a name-only lookup over the combined tuple silently resolves to the wrong
 * side. (Hash join is immune because it extracts each side's key separately.)
 */
public class PhysicalNestedLoopJoin extends PhysicalNode implements Iterator {
    private final PhysicalNode left;
    private final PhysicalNode right;
    private final Expression condition;
    private final Schema leftSchema;
    private final Schema rightSchema;
    /** Lower-cased table names scanned under the left/right inputs, used to
     *  disambiguate a qualified column that exists on both sides. */
    private final Set<String> leftTables;
    private final Set<String> rightTables;

    // Execution state
    private Iterator leftIterator;
    private List<Tuple> rightRows;
    private int rightIndex;
    private Tuple currentLeftTuple;
    private boolean isOpen = false;
    private long rowsProcessed;

    public PhysicalNestedLoopJoin(PhysicalNode left, PhysicalNode right,
                                  Expression condition,
                                  Schema leftSchema, Schema rightSchema) {
        this(left, right, condition, leftSchema, rightSchema, Set.of(), Set.of());
    }

    /**
     * @param leftTables  lower-cased names of tables scanned under {@code left}
     * @param rightTables lower-cased names of tables scanned under {@code right}
     *                    — together they let a qualified column reference that
     *                    collides across both inputs bind to the correct side.
     */
    public PhysicalNestedLoopJoin(PhysicalNode left, PhysicalNode right,
                                  Expression condition,
                                  Schema leftSchema, Schema rightSchema,
                                  Set<String> leftTables, Set<String> rightTables) {
        this.left = left;
        this.right = right;
        this.condition = condition;
        this.leftSchema = leftSchema;
        this.rightSchema = rightSchema;
        this.leftTables = lowerCased(leftTables);
        this.rightTables = lowerCased(rightTables);
    }

    private static Set<String> lowerCased(Set<String> names) {
        Set<String> result = new HashSet<>();
        for (String name : names) {
            result.add(name.toLowerCase());
        }
        return result;
    }

    public PhysicalNode getLeft() {
        return left;
    }

    public PhysicalNode getRight() {
        return right;
    }

    public Expression getCondition() {
        return condition;
    }

    @Override
    public List<PhysicalNode> getChildren() {
        return Arrays.asList(left, right);
    }

    @Override
    public String describe() {
        return "PhysicalNestedLoopJoin[" + condition.toSQLString() + "]";
    }

    @Override
    public double estimateCost() {
        if (getEstimatedCost() >= 0) {
            return getEstimatedCost();
        }
        // Nested loop cost: left_cost + (left_rows * right_cost)
        double leftCost = left.estimateCost();
        double rightCost = right.estimateCost();
        long leftRows = left.getEstimatedRows();
        long rightRows = right.getEstimatedRows();

        return leftCost + (leftRows * rightCost) + (leftRows * rightRows * 0.00001);
    }

    // === Iterator implementation ===

    @Override
    public void open() {
        if (isOpen) {
            throw new IllegalStateException("Join already open");
        }

        if (!(left instanceof Iterator) || !(right instanceof Iterator)) {
            throw new IllegalStateException("Children must be Iterators");
        }

        leftIterator = (Iterator) left;
        leftIterator.open();

        // Materialize the inner side once; every left tuple re-scans this list
        rowsProcessed = 0;
        Iterator rightIterator = (Iterator) right;
        rightIterator.open();
        rightRows = new ArrayList<>();
        Tuple rightTuple;
        while ((rightTuple = rightIterator.next()) != null) {
            rightRows.add(rightTuple);
        }
        rightIterator.close();
        rowsProcessed += rightRows.size(); // materializing the inner side

        // Get first left tuple
        currentLeftTuple = leftIterator.next();
        rightIndex = 0;

        isOpen = true;
    }

    @Override
    public Tuple next() {
        if (!isOpen) {
            throw new IllegalStateException("Join not open");
        }

        while (currentLeftTuple != null) {
            if (rightIndex < rightRows.size()) {
                Tuple rightTuple = rightRows.get(rightIndex++);
                rowsProcessed++; // one (left, right) pair evaluated

                // Check join condition with each side resolved against its own
                // schema/tuple, then emit the combined tuple only on a match.
                Object result = evaluateCondition(condition, currentLeftTuple, rightTuple);

                if (result instanceof Boolean match && match) {
                    return combineTuples(currentLeftTuple, rightTuple);
                }

                // Continue with next right tuple
            } else {
                // Exhausted right side, move to next left tuple
                currentLeftTuple = leftIterator.next();
                rightIndex = 0;
            }
        }

        return null; // No more matches
    }

    @Override
    public void close() {
        if (!isOpen) {
            return;
        }

        if (leftIterator != null) {
            leftIterator.close();
            leftIterator = null;
        }

        rightRows = null;
        currentLeftTuple = null;
        isOpen = false;
    }

    @Override
    public long rowsProcessed() {
        return rowsProcessed;
    }

    /**
     * Combine left and right tuples into a single tuple.
     */
    private Tuple combineTuples(Tuple left, Tuple right) {
        Tuple combined = new Tuple();
        combined.addAll(left);
        combined.addAll(right);
        return combined;
    }

    /**
     * Evaluates the join predicate for a single {@code (left, right)} tuple pair,
     * resolving every column reference against the side that owns it. Only
     * {@code ColumnRef} resolution is side-sensitive; binary operators and
     * literals reuse the standard {@link Expression} semantics.
     */
    private Object evaluateCondition(Expression expr, Tuple leftTuple, Tuple rightTuple) {
        if (expr instanceof Expression.ColumnRef ref) {
            return resolveColumn(ref, leftTuple, rightTuple);
        }
        if (expr instanceof Expression.BinaryOp binOp) {
            Object leftVal = evaluateCondition(binOp.left(), leftTuple, rightTuple);
            Object rightVal = evaluateCondition(binOp.right(), leftTuple, rightTuple);
            return binOp.applyOperator(leftVal, rightVal);
        }
        // Literals (and any other side-independent expression) ignore the row.
        return expr.evaluate(leftTuple, leftSchema);
    }

    /**
     * Resolves a column reference to its value by binding it to the correct input
     * and reading from that input's tuple with that input's column key.
     * <p>
     * A column present on only one side binds there. A column present on both
     * sides (a name collision such as {@code id}) is disambiguated by the
     * reference's table qualifier; absent a usable qualifier it falls back to the
     * left side, preserving the historical name-only behaviour.
     */
    private Object resolveColumn(Expression.ColumnRef ref, Tuple leftTuple, Tuple rightTuple) {
        String column = ref.columnName();
        boolean leftHas = leftSchema.hasColumn(column);
        boolean rightHas = rightSchema.hasColumn(column);

        if (leftHas && rightHas) {
            return boundToRight(ref)
                    ? readFrom(rightTuple, rightSchema, column)
                    : readFrom(leftTuple, leftSchema, column);
        }
        if (rightHas) {
            return readFrom(rightTuple, rightSchema, column);
        }
        if (leftHas) {
            return readFrom(leftTuple, leftSchema, column);
        }
        throw new IllegalArgumentException("Column not found on either join input: " + column);
    }

    /**
     * Decides whether a column that exists on both inputs should bind to the
     * right side, using the reference's table qualifier; defaults to the left
     * side when the qualifier is missing or matches neither input's tables.
     */
    private boolean boundToRight(Expression.ColumnRef ref) {
        if (ref.tableName() == null) {
            return false;
        }
        String table = ref.tableName().toLowerCase();
        // Prefer an explicit right-side match; only the right branch can override
        // the left-side default, so a left match (or no match) keeps the default.
        return rightTables.contains(table) && !leftTables.contains(table);
    }

    private static Object readFrom(Tuple tuple, Schema schema, String column) {
        return tuple.find(schema.getColumn(column));
    }
}