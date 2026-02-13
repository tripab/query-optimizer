package org.query.optimizer.physical;

import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.Tuple;
import org.query.optimizer.executor.Iterator;
import org.query.optimizer.logical.Expression;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


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
 */
public class PhysicalNestedLoopJoin extends PhysicalNode implements Iterator {
    private final PhysicalNode left;
    private final PhysicalNode right;
    private final Expression condition;
    private final Schema leftSchema;
    private final Schema rightSchema;

    // Execution state
    private Iterator leftIterator;
    private Iterator rightIterator;
    private Tuple currentLeftTuple;
    private final Schema combinedSchema;
    private boolean isOpen = false;

    public PhysicalNestedLoopJoin(PhysicalNode left, PhysicalNode right,
                                  Expression condition,
                                  Schema leftSchema, Schema rightSchema) {
        this.left = left;
        this.right = right;
        this.condition = condition;
        this.leftSchema = leftSchema;
        this.rightSchema = rightSchema;
        this.combinedSchema = createCombinedSchema(leftSchema, rightSchema);
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
        rightIterator = (Iterator) right;

        leftIterator.open();
        rightIterator.open();

        // Get first left tuple
        currentLeftTuple = leftIterator.next();

        isOpen = true;
    }

    @Override
    public Tuple next() {
        if (!isOpen) {
            throw new IllegalStateException("Join not open");
        }

        while (currentLeftTuple != null) {
            // Try to find matching right tuple
            Tuple rightTuple = rightIterator.next();

            if (rightTuple != null) {
                // Combine tuples
                Tuple combined = combineTuples(currentLeftTuple, rightTuple);

                // Check join condition
                Object result = condition.evaluate(combined, combinedSchema);

                if (result instanceof Boolean && (Boolean) result) {
                    return combined;
                }

                // Continue with next right tuple
            } else {
                // Exhausted right side, move to next left tuple
                currentLeftTuple = leftIterator.next();

                if (currentLeftTuple != null) {
                    // Reopen right iterator for new left tuple
                    rightIterator.close();
                    rightIterator.open();
                }
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

        if (rightIterator != null) {
            rightIterator.close();
            rightIterator = null;
        }

        currentLeftTuple = null;
        isOpen = false;
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
     * Create combined schema for joined tuples.
     */
    private Schema createCombinedSchema(Schema left, Schema right) {
        List<Schema.Column> columns = new ArrayList<>();
        columns.addAll(left.getColumns());
        columns.addAll(right.getColumns());
        return new Schema(columns);
    }
}