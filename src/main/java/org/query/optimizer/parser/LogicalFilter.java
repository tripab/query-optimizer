package org.query.optimizer.parser;

import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;

import java.util.Collections;
import java.util.List;

/**
 * Filter rows based on a predicate (WHERE clause).
 * In canonical form, each Filter node has exactly one predicate.
 */
public class LogicalFilter extends LogicalNode {
    private final Expression predicate;
    private final LogicalNode child;

    public LogicalFilter(Expression predicate, LogicalNode child) {
        this.predicate = predicate;
        this.child = child;
    }

    public Expression getPredicate() {
        return predicate;
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
            throw new IllegalArgumentException("Filter must have exactly one child");
        }
        return new LogicalFilter(predicate, children.get(0));
    }

    @Override
    public String describe() {
        return "Filter[" + predicate.toSQLString() + "]";
    }
}