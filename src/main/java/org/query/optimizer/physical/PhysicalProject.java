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
 * Selects columns from each input tuple by position. The input column index of
 * each projection is resolved once at plan-build time ({@code PhysicalPlanBuilder}
 * disambiguates a qualified reference like {@code products.name} against the
 * origin table of each input column), because resolving by column <em>name</em>
 * per row silently picks the first match when a join output carries the same
 * column name from both sides. Input tuples follow the input schema's column
 * order (scans emit in schema order; joins concatenate; filters pass through).
 */
public class PhysicalProject extends PhysicalNode implements Iterator {
    private final List<Expression> projections;
    private final List<String> columnNames;
    private final PhysicalNode child;
    private final Schema inputSchema;
    /** For each projection, the index of its column in the input tuple. */
    private final int[] projectionIndexes;

    // Execution state
    private Iterator childIterator;
    private boolean isOpen = false;

    /**
     * Legacy constructor: resolves each projection to the first input column
     * with a matching name. Only safe when the input schema has no duplicate
     * column names; the plan builder uses the index-based constructor instead.
     */
    public PhysicalProject(List<Expression> projections, List<String> columnNames,
                           PhysicalNode child, Schema inputSchema) {
        this(projections, columnNames, child, inputSchema,
                resolveByName(projections, inputSchema));
    }

    public PhysicalProject(List<Expression> projections, List<String> columnNames,
                           PhysicalNode child, Schema inputSchema, int[] projectionIndexes) {
        this.projections = projections;
        this.columnNames = columnNames;
        this.child = child;
        this.inputSchema = inputSchema;
        this.projectionIndexes = projectionIndexes;
    }

    private static int[] resolveByName(List<Expression> projections, Schema inputSchema) {
        int[] indexes = new int[projections.size()];
        for (int i = 0; i < indexes.length; i++) {
            String column = ((Expression.ColumnRef) projections.get(i)).columnName();
            indexes[i] = inputSchema.getColumnIndex(column);
        }
        return indexes;
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

        // Copy the pre-resolved input columns into the output tuple
        Tuple outputTuple = new Tuple();
        for (int index : projectionIndexes) {
            Attribute attribute = inputTuple.get(index);
            outputTuple.add(new Attribute(attribute.getKey(), attribute.getValue()));
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