package org.query.optimizer.vectorized;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalAggregate;
import org.query.optimizer.parser.LogicalFilter;
import org.query.optimizer.parser.LogicalJoin;
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
 * <h2>Supported operators</h2>
 * <ul>
 *   <li>{@link LogicalScan}      → {@link VectorizedScan}</li>
 *   <li>{@link LogicalFilter}    → {@link VectorizedFilter}</li>
 *   <li>{@link LogicalProject}   → {@link VectorizedProject}</li>
 *   <li>{@link LogicalJoin}      → {@link VectorizedHashJoin} (inner equi-join only)</li>
 *   <li>{@link LogicalAggregate} → {@link VectorizedAggregate}</li>
 * </ul>
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
            case LogicalScan      scan  -> buildScan(scan);
            case LogicalFilter    f     -> buildFilter(f);
            case LogicalProject   p     -> buildProject(p);
            case LogicalJoin      j     -> buildJoin(j);
            case LogicalAggregate a     -> buildAggregate(a);
            default -> throw new UnsupportedOperationException(
                    "VectorizedPlanBuilder does not support: " +
                    logicalPlan.getClass().getSimpleName());
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

    /**
     * Converts a {@link LogicalJoin} to a {@link VectorizedHashJoin}.
     *
     * <p>The left child becomes the probe side and the right child the build side,
     * mirroring the convention used by {@link org.query.optimizer.physical.PhysicalHashJoin}.
     * Only inner equi-joins are supported at this scope.
     */
    private VectorizedHashJoin buildJoin(LogicalJoin join) {
        VectorizedOperator probe = build(join.getLeft());
        VectorizedOperator build = build(join.getRight());
        return new VectorizedHashJoin(probe, build, join.getCondition());
    }

    /**
     * Converts a {@link LogicalAggregate} to a {@link VectorizedAggregate}.
     */
    private VectorizedAggregate buildAggregate(LogicalAggregate agg) {
        VectorizedOperator child = build(agg.getChild());
        return new VectorizedAggregate(child, agg.getGroupByColumns(), agg.getAggregateOps());
    }
}
