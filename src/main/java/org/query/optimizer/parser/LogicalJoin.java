package org.query.optimizer.parser;

import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;

import java.util.Arrays;
import java.util.List;

/**
 * Join two relations.
 */
public class LogicalJoin extends LogicalNode {
    public enum JoinType {INNER}

    private final LogicalNode left;
    private final LogicalNode right;
    private final JoinType joinType;
    private final Expression condition;

    public LogicalJoin(LogicalNode left, LogicalNode right, JoinType joinType, Expression condition) {
        this.left = left;
        this.right = right;
        this.joinType = joinType;
        this.condition = condition;
    }

    public LogicalNode getLeft() {
        return left;
    }

    public LogicalNode getRight() {
        return right;
    }

    public JoinType getJoinType() {
        return joinType;
    }

    public Expression getCondition() {
        return condition;
    }

    @Override
    public List<LogicalNode> getChildren() {
        return Arrays.asList(left, right);
    }

    @Override
    public LogicalNode withChildren(List<LogicalNode> children) {
        if (children.size() != 2) {
            throw new IllegalArgumentException("Join must have exactly two children");
        }
        return new LogicalJoin(children.get(0), children.get(1), joinType, condition);
    }

    @Override
    public String describe() {
        return "Join[" + joinType + ", " + condition.toSQLString() + "]";
    }
}