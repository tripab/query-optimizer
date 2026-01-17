package org.query.optimizer.parser;

import org.query.optimizer.logical.LogicalNode;

import java.util.Collections;
import java.util.List;

/**
 * Aggregate with grouping (GROUP BY).
 */
public class LogicalAggregate extends LogicalNode {
    public enum AggFunction {COUNT, SUM, AVG, MIN, MAX}

    /**
     * Represents a single aggregation operation.
     *
     * @param inputColumn  Column to aggregate (or "*" for COUNT(*))
     * @param outputColumn Result column name
     */
    public record AggregateOp(AggFunction function, String inputColumn, String outputColumn) {
        @Override
        public String toString() {
            return function + "(" + inputColumn + ") AS " + outputColumn;
        }
    }

    private final List<String> groupByColumns;
    private final List<AggregateOp> aggregateOps;
    private final LogicalNode child;

    public LogicalAggregate(List<String> groupByColumns, List<AggregateOp> aggregateOps,
                            LogicalNode child) {
        this.groupByColumns = groupByColumns;
        this.aggregateOps = aggregateOps;
        this.child = child;
    }

    public List<String> getGroupByColumns() {
        return groupByColumns;
    }

    public List<AggregateOp> getAggregateOps() {
        return aggregateOps;
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
            throw new IllegalArgumentException("Aggregate must have exactly one child");
        }
        return new LogicalAggregate(groupByColumns, aggregateOps, children.get(0));
    }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder("Aggregate[");
        if (!groupByColumns.isEmpty()) {
            sb.append("GROUP BY: ").append(String.join(", ", groupByColumns));
        }
        if (!aggregateOps.isEmpty()) {
            if (!groupByColumns.isEmpty()) sb.append("; ");
            sb.append("AGGS: ");
            for (int i = 0; i < aggregateOps.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(aggregateOps.get(i));
            }
        }
        sb.append("]");
        return sb.toString();
    }
}