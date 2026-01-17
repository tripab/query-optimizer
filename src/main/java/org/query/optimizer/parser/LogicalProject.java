package org.query.optimizer.parser;

import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;

import java.util.Collections;
import java.util.List;

/**
 * Project specific columns (SELECT list).
 */
public class LogicalProject extends LogicalNode {
    private final List<Expression> projections;
    private final List<String> columnNames;  // Output column names
    private final LogicalNode child;

    public LogicalProject(List<Expression> projections, List<String> columnNames, LogicalNode child) {
        this.projections = projections;
        this.columnNames = columnNames;
        this.child = child;
    }

    public List<Expression> getProjections() {
        return projections;
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public LogicalNode getChild() {
        return child;
    }

    @Override
    public List<LogicalNode> getChildren() {
        return Collections.singletonList(child);
    }

    @Override
    public LogicalNode withChildren(List<LogicalNode> children) {
        if (children.size() != 1) {
            throw new IllegalArgumentException("Project must have exactly one child");
        }
        return new LogicalProject(projections, columnNames, children.get(0));
    }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder("Project[");
        for (int i = 0; i < columnNames.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(columnNames.get(i));
        }
        sb.append("]");
        return sb.toString();
    }
}