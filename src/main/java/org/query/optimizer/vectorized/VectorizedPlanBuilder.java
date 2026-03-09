package org.query.optimizer.vectorized;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalFilter;
import org.query.optimizer.parser.LogicalProject;
import org.query.optimizer.parser.LogicalScan;

import java.util.List;

/**
 * Converts a (possibly optimised) logical plan into a tree of {@link VectorizedOperator}s.
 *
 * <p>This is the vectorized counterpart of {@link org.query.optimizer.physical.PhysicalPlanBuilder}.
 * Both builders accept the same {@link LogicalNode} tree produced by
 * {@link org.query.optimizer.parser.LogicalPlanBuilder} (optionally after rule-based optimisation),
 * which is what makes side-by-side Volcano vs. vectorized comparison possible: one logical plan,
 * two physical realisations.
 *
 * <h2>Supported operators (Phase 2)</h2>
 * <ul>
 *   <li>{@link LogicalScan}    → {@link VectorizedScan}</li>
 *   <li>{@link LogicalFilter}  → {@link VectorizedFilter}</li>
 *   <li>{@link LogicalProject} → {@link VectorizedProject}</li>
 * </ul>
 * Join and aggregate support will be added in Phase 3 (tasks 3.1–3.2).
 */
public class VectorizedPlanBuilder {

    private final Catalog catalog;

    public VectorizedPlanBuilder(Catalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Recursively converts {@code logicalPlan} into a {@link VectorizedOperator} tree.
     *
     * @param logicalPlan the root of a logical plan (post-optimisation)
     * @return the root of an equivalent vectorized operator tree
     * @throws UnsupportedOperationException if the plan contains unsupported node types
     */
    public VectorizedOperator build(LogicalNode logicalPlan) {
        return switch (logicalPlan) {
            case LogicalScan    scan    -> buildScan(scan);
            case LogicalFilter  filter  -> buildFilter(filter);
            case LogicalProject project -> buildProject(project);
            default -> throw new UnsupportedOperationException(
                    "VectorizedPlanBuilder (Phase 2) does not yet support: " +
                    logicalPlan.getClass().getSimpleName() +
                    ". Join and aggregate support arrives in Phase 3.");
        };
    }

    // -------------------------------------------------------------------------
    // Per-node builders
    // -------------------------------------------------------------------------

    private VectorizedScan buildScan(LogicalScan scan) {
        return new VectorizedScan(scan.getTableName(), catalog);
    }

    private VectorizedFilter buildFilter(LogicalFilter filter) {
        VectorizedOperator child     = build(filter.getChild());
        Expression         predicate = filter.getPredicate();
        return new VectorizedFilter(child, predicate);
    }

    private VectorizedProject buildProject(LogicalProject project) {
        VectorizedOperator child       = build(project.getChild());
        List<String>       columnNames = project.getColumnNames();
        return new VectorizedProject(child, columnNames);
    }
}
