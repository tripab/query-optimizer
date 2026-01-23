package org.query.optimizer.rules;

import org.query.optimizer.Rule;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalFilter;

/**
 * Filter Merge Rule
 * <p>
 * Merges consecutive Filter nodes into a single Filter with AND predicate.
 * This can improve execution efficiency by reducing operator overhead.
 * <p>
 * Pattern: Filter -> Filter
 * Transform: Single Filter with combined predicate
 * <p>
 * Note: This is the opposite of canonical form! We split ANDs during
 * parsing to enable individual filter pushdown, then merge them back
 * after optimization for efficient execution.
 */
public class FilterMerge implements Rule {
    @Override
    public String getName() {
        return "FilterMerge";
    }

    @Override
    public boolean matches(LogicalNode node) {
        // Pattern: Filter -> Filter
        if (!(node instanceof LogicalFilter)) {
            return false;
        }
        LogicalFilter filter = (LogicalFilter) node;
        return filter.getChild() instanceof LogicalFilter;
    }

    @Override
    public LogicalNode apply(LogicalNode node) {
        LogicalFilter outer = (LogicalFilter) node;
        LogicalFilter inner = (LogicalFilter) outer.getChild();

        // Combine predicates with AND
        Expression combined = new Expression.BinaryOp(
                Expression.BinaryOp.Operator.AND,
                outer.getPredicate(),
                inner.getPredicate()
        );

        // Create single filter with combined predicate
        return new LogicalFilter(combined, inner.getChild());
    }

    @Override
    public String getDescription() {
        return "Merge consecutive filters into single filter with AND";
    }
}
