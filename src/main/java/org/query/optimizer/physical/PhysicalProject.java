package org.query.optimizer.physical;

import org.query.optimizer.catalog.Attribute;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.Tuple;
import org.query.optimizer.executor.Iterator;
import org.query.optimizer.logical.Expression;

import java.util.Collections;
import java.util.List;


/**
 * Physical projection operator.
 * <p>
 * Evaluates projection expressions and produces output tuples
 * with only the selected columns.
 */
public class PhysicalProject extends PhysicalNode implements Iterator {
    private final List<Expression> projections;
    private final List<String> columnNames;
    private final PhysicalNode child;
    private final Schema inputSchema;

    // Execution state
    private Iterator childIterator;
    private boolean isOpen = false;

    public PhysicalProject(List<Expression> projections, List<String> columnNames,
                           PhysicalNode child, Schema inputSchema) {
        this.projections = projections;
        this.columnNames = columnNames;
        this.child = child;
        this.inputSchema = inputSchema;
    }

    public List<Expression> getProjections() {
        return projections;
    }

    public List<String> getColumnNames() {
        return columnNames;
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
        return "PhysicalProject[" + String.join(", ", columnNames) + "]";
    }

    @Override
    public double estimateCost() {
        if (getEstimatedCost() >= 0) {
            return getEstimatedCost();
        }
        // Cost = child cost + projection cost
        double childCost = child.estimateCost();
        long inputRows = child.getEstimatedRows();
        return childCost + (inputRows * 0.00005);
    }

    // === Iterator implementation ===

    @Override
    public void open() {
        if (isOpen) {
            throw new IllegalStateException("Project already open");
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
            throw new IllegalStateException("Project not open");
        }

        Tuple inputTuple = childIterator.next();

        if (inputTuple == null) {
            return null;
        }

        // Evaluate each projection expression
        Tuple outputTuple = new Tuple();
        for (Expression projection : projections) {
            Attribute columnInfo =
                    new Attribute(inputSchema.getColumn(((Expression.ColumnRef) projection).columnName()),
                            projection.evaluate(inputTuple, inputSchema));
            outputTuple.add(columnInfo);
        }

        return outputTuple;
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