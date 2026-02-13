package org.query.optimizer.physical;

import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.Tuple;
import org.query.optimizer.executor.Iterator;
import org.query.optimizer.logical.Expression;

import java.util.Collections;
import java.util.List;

/**
 * Physical filter operator.
 * <p>
 * Applies a predicate to each input tuple, passing through only
 * those that satisfy the condition.
 */
public class PhysicalFilter extends PhysicalNode implements Iterator {
    private final Expression predicate;
    private final PhysicalNode child;
    private final Schema schema;

    // Execution state
    private Iterator childIterator;
    private boolean isOpen = false;

    public PhysicalFilter(Expression predicate, PhysicalNode child, Schema schema) {
        this.predicate = predicate;
        this.child = child;
        this.schema = schema;
    }

    public Expression getPredicate() {
        return predicate;
    }

    public PhysicalNode getChild() {
        return child;
    }

    @Override
    public List<PhysicalNode> getChildren() {
        return Collections.singletonList(child);
    }

    @Override
    public String describe() {
        return "PhysicalFilter[" + predicate.toSQLString() + "]";
    }

    @Override
    public double estimateCost() {
        if (getEstimatedCost() >= 0) {
            return getEstimatedCost();
        }
        // Cost = child cost + evaluation cost
        double childCost = child.estimateCost();
        long inputRows = child.getEstimatedRows();
        return childCost + (inputRows * 0.0001);
    }

    // === Iterator implementation ===

    @Override
    public void open() {
        if (isOpen) {
            throw new IllegalStateException("Filter already open");
        }

        if (child instanceof Iterator) {
            childIterator = (Iterator) child;
            childIterator.open();
        } else {
            throw new IllegalStateException("Child is not an Iterator");
        }

        isOpen = true;
    }

    @Override
    public Tuple next() {
        if (!isOpen) {
            throw new IllegalStateException("Filter not open");
        }

        // Loop until we find a tuple that satisfies the predicate
        while (true) {
            Tuple tuple = childIterator.next();

            if (tuple == null) {
                return null; // No more tuples
            }

            // Evaluate predicate
            Object result = predicate.evaluate(tuple, schema);

            if (result instanceof Boolean && (Boolean) result) {
                return tuple; // Predicate satisfied
            }

            // Predicate not satisfied, try next tuple
        }
    }

    @Override
    public void close() {
        if (!isOpen) {
            return;
        }

        if (childIterator != null) {
            childIterator.close();
            childIterator = null;
        }

        isOpen = false;
    }
}