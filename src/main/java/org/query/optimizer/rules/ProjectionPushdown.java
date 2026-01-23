package org.query.optimizer.rules;

import org.query.optimizer.Rule;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalFilter;
import org.query.optimizer.parser.LogicalProject;

import java.util.HashSet;
import java.util.Set;

/**
 * Projection Pushdown Rule
 * <p>
 * Pushes Project nodes down the tree to eliminate unnecessary columns early.
 * This reduces the amount of data flowing through the query plan.
 * <p>
 * Pattern: Project -> Filter
 * Transform: Filter -> Project (swap them)
 * <p>
 * This is safe because:
 * - Filters don't change the schema
 * - We can filter first, then project
 * - Reduces data volume earlier in the plan
 * <p>
 * Note: We only push through Filter nodes in this simple version.
 * A full implementation would also push through Joins (more complex).
 */
public class ProjectionPushdown implements Rule {
    @Override
    public String getName() {
        return "ProjectionPushdown";
    }

    @Override
    public boolean matches(LogicalNode node) {
        // Pattern: Project -> Filter
        if (!(node instanceof LogicalProject)) {
            return false;
        }
        LogicalProject project = (LogicalProject) node;
        return project.getChild() instanceof LogicalFilter;
    }

    @Override
    public LogicalNode apply(LogicalNode node) {
        LogicalProject project = (LogicalProject) node;
        LogicalFilter filter = (LogicalFilter) project.getChild();

        // Check if the filter predicate references any columns
        // that are NOT in the projection list
        Set<String> projectedColumns = getProjectedColumns(project);
        Set<String> filterColumns = getReferencedColumns(filter.getPredicate());

        // If filter needs columns not in projection, we can't safely swap
        // (we'd lose columns needed for filtering)
        if (!projectedColumns.containsAll(filterColumns)) {
            // Need to keep all filter columns in projection
            return null; // no transformation carried out
        }

        // Safe to swap: Filter -> Project
        LogicalNode newProject = new LogicalProject(
                project.getProjections(),
                project.getColumnNames(),
                filter.getChild()
        );
        return new LogicalFilter(filter.getPredicate(), newProject);
    }

    /**
     * Get all column names referenced in an expression.
     */
    private Set<String> getReferencedColumns(Expression expr) {
        Set<String> columns = new HashSet<>();
        collectReferencedColumns(expr, columns);
        return columns;
    }

    private void collectReferencedColumns(Expression expr, Set<String> columns) {
        if (expr instanceof Expression.ColumnRef col) {
            columns.add(col.columnName());
        } else if (expr instanceof Expression.BinaryOp binaryOp) {
            collectReferencedColumns(binaryOp.left(), columns);
            collectReferencedColumns(binaryOp.right(), columns);
        }
    }

    /**
     * Get set of column names in the projection.
     */
    private Set<String> getProjectedColumns(LogicalProject project) {
        Set<String> columns = new HashSet<>();

        for (Expression expr : project.getProjections()) {
            if (expr instanceof Expression.ColumnRef col) {
                columns.add(col.columnName());
            }
            // TODO: handle more complex expressions, need to add code to analyze deeper
        }

        return columns;
    }

    @Override
    public String getDescription() {
        return "Push projections below filters to reduce data volume";
    }
}
