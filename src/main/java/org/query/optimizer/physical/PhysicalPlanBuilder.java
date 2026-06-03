package org.query.optimizer.physical;

import org.query.optimizer.JoinAlgorithmPolicy;
import org.query.optimizer.PhysicalCostEstimator;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.TableMetadata;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.*;
import org.query.optimizer.vectorized.AggregateAccumulator;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts optimized logical plans to executable physical plans.
 * <p>
 * Makes decisions about:
 * - Which join algorithm to use (nested loop vs hash), per the {@link JoinAlgorithmPolicy}
 * - Physical operator implementations
 * - Schema propagation
 * <p>
 * Row estimates are copied down from the (already annotated) logical plan, while
 * each physical node's cost is (re)computed by {@link PhysicalCostEstimator} in a
 * final pass — keeping logical cardinality estimation separate from physical
 * operator costing.
 */
public class PhysicalPlanBuilder {
    private final Catalog catalog;
    private final PhysicalCostEstimator costEstimator = new PhysicalCostEstimator();
    private JoinAlgorithmPolicy joinAlgorithmPolicy;

    public PhysicalPlanBuilder(Catalog catalog) {
        this(catalog, JoinAlgorithmPolicy.FORCE_HASH); // prefer hash join by default
    }

    public PhysicalPlanBuilder(Catalog catalog, boolean preferHashJoin) {
        this(catalog, preferHashJoin ? JoinAlgorithmPolicy.FORCE_HASH : JoinAlgorithmPolicy.FORCE_NLJ);
    }

    public PhysicalPlanBuilder(Catalog catalog, JoinAlgorithmPolicy joinAlgorithmPolicy) {
        this.catalog = catalog;
        this.joinAlgorithmPolicy = joinAlgorithmPolicy;
    }

    /**
     * Sets whether equi-joins should prefer hash join over nested-loop join.
     * Convenience wrapper that maps to {@link JoinAlgorithmPolicy#FORCE_HASH} /
     * {@link JoinAlgorithmPolicy#FORCE_NLJ}.
     *
     * @param preferHashJoin {@code true} to force hash join for equi-joins,
     *                       {@code false} to force nested-loop join
     */
    public void setPreferHashJoin(boolean preferHashJoin) {
        this.joinAlgorithmPolicy = preferHashJoin
                ? JoinAlgorithmPolicy.FORCE_HASH : JoinAlgorithmPolicy.FORCE_NLJ;
    }

    /**
     * Sets the join-algorithm selection policy used for equi-joins.
     */
    public void setJoinAlgorithmPolicy(JoinAlgorithmPolicy joinAlgorithmPolicy) {
        this.joinAlgorithmPolicy = joinAlgorithmPolicy;
    }

    /**
     * Convert logical plan to physical plan, then annotate it with physical costs.
     */
    public PhysicalNode build(LogicalNode logicalPlan) {
        PhysicalNode physical = convertNode(logicalPlan);
        costEstimator.annotateCosts(physical);
        return physical;
    }

    /**
     * Recursively convert logical nodes to physical nodes.
     */
    private PhysicalNode convertNode(LogicalNode node) {
        return switch (node) {
            case LogicalScan logicalScan -> convertScan(logicalScan);
            case LogicalFilter logicalFilter -> convertFilter(logicalFilter);
            case LogicalProject logicalProject -> convertProject(logicalProject);
            case LogicalJoin logicalJoin -> convertJoin(logicalJoin);
            case LogicalAggregate logicalAggregate -> convertAggregate(logicalAggregate);
            default -> throw new IllegalArgumentException("Unknown logical node type: " +
                    node.getClass().getSimpleName());
        };
    }

    /**
     * Convert LogicalScan to PhysicalScan.
     */
    private PhysicalNode convertScan(LogicalScan scan) {
        PhysicalScan physicalScan = new PhysicalScan(scan.getTableName(), catalog);

        // Copy the logical row estimate down; physical cost is annotated later.
        if (scan.getEstimatedRows() > 0) {
            physicalScan.setEstimatedRows(scan.getEstimatedRows());
        }

        return physicalScan;
    }

    /**
     * Convert LogicalFilter to PhysicalFilter.
     */
    private PhysicalNode convertFilter(LogicalFilter filter) {
        PhysicalNode child = convertNode(filter.getChild());
        Schema schema = getOutputSchema(filter.getChild());

        PhysicalFilter physicalFilter = new PhysicalFilter(
                filter.getPredicate(), child, schema
        );

        if (filter.getEstimatedRows() > 0) {
            physicalFilter.setEstimatedRows(filter.getEstimatedRows());
        }

        return physicalFilter;
    }

    /**
     * Convert LogicalProject to PhysicalProject.
     */
    private PhysicalNode convertProject(LogicalProject project) {
        PhysicalNode child = convertNode(project.getChild());
        Schema inputSchema = getOutputSchema(project.getChild());

        PhysicalProject physicalProject = new PhysicalProject(
                project.getProjections(),
                project.getColumnNames(),
                child,
                inputSchema
        );

        if (project.getEstimatedRows() > 0) {
            physicalProject.setEstimatedRows(project.getEstimatedRows());
        }

        return physicalProject;
    }

    /**
     * Convert LogicalJoin to a physical join, choosing the algorithm per the
     * configured {@link JoinAlgorithmPolicy}.
     * <p>
     * Non-equi joins are always nested-loop (hash join needs an equi-key). For
     * equi-joins: {@code FORCE_HASH} and {@code FORCE_NLJ} pick that algorithm
     * directly, while {@code COST_BASED} builds both candidates and keeps the one
     * the {@link PhysicalCostEstimator} prices lower (ties go to hash).
     */
    private PhysicalNode convertJoin(LogicalJoin join) {
        PhysicalNode left = convertNode(join.getLeft());
        PhysicalNode right = convertNode(join.getRight());

        Schema leftSchema = getOutputSchema(join.getLeft());
        Schema rightSchema = getOutputSchema(join.getRight());

        PhysicalNode physicalJoin =
                chooseJoinAlgorithm(join, left, right, leftSchema, rightSchema);

        // Carry the logical row estimate; physical cost is annotated in build().
        if (join.getEstimatedRows() > 0) {
            physicalJoin.setEstimatedRows(join.getEstimatedRows());
        }

        return physicalJoin;
    }

    /**
     * Picks the physical join operator for {@code join} according to the policy.
     */
    private PhysicalNode chooseJoinAlgorithm(LogicalJoin join,
                                             PhysicalNode left, PhysicalNode right,
                                             Schema leftSchema, Schema rightSchema) {
        Expression condition = join.getCondition();

        // Hash join requires an equi-join key; otherwise we must use nested-loop.
        if (!isEquiJoin(condition)) {
            return new PhysicalNestedLoopJoin(left, right, condition, leftSchema, rightSchema);
        }

        return switch (joinAlgorithmPolicy) {
            case FORCE_NLJ ->
                    new PhysicalNestedLoopJoin(left, right, condition, leftSchema, rightSchema);
            case FORCE_HASH ->
                    new PhysicalHashJoin(left, right, condition, leftSchema, rightSchema);
            case COST_BASED -> {
                double hashCost = costEstimator.hashJoinCost(left, right);
                double nljCost = costEstimator.nestedLoopJoinCost(left, right);
                yield (hashCost <= nljCost)
                        ? new PhysicalHashJoin(left, right, condition, leftSchema, rightSchema)
                        : new PhysicalNestedLoopJoin(left, right, condition, leftSchema, rightSchema);
            }
        };
    }

    /**
     * Convert LogicalAggregate to PhysicalAggregate.
     */
    private PhysicalNode convertAggregate(LogicalAggregate agg) {
        PhysicalNode child = convertNode(agg.getChild());
        Schema inputSchema = getOutputSchema(agg.getChild());

        PhysicalAggregate physicalAgg = new PhysicalAggregate(
                child, agg.getGroupByColumns(), agg.getAggregateOps(), inputSchema
        );

        if (agg.getEstimatedRows() > 0) {
            physicalAgg.setEstimatedRows(agg.getEstimatedRows());
        }

        return physicalAgg;
    }

    /**
     * Check if join condition is an equi-join (column = column).
     */
    private boolean isEquiJoin(Expression condition) {
        if (condition instanceof Expression.BinaryOp(
                Expression.BinaryOp.Operator operator, Expression left, Expression right
        )) {
            return operator == Expression.BinaryOp.Operator.EQ &&
                    left instanceof Expression.ColumnRef &&
                    right instanceof Expression.ColumnRef;
        }
        return false;
    }

    /**
     * Get the output schema for a logical node.
     */
    private Schema getOutputSchema(LogicalNode node) {
        switch (node) {
            case LogicalScan scan -> {
                TableMetadata table = catalog.getTableMetadata(scan.getTableName());
                return table.getSchema();
            }
            case LogicalFilter logicalFilter -> {
                // Filter doesn't change schema
                return getOutputSchema(logicalFilter.getChild());
                // Filter doesn't change schema
            }
            case LogicalProject project -> {
                // Resolve each projected column's type from the child schema so that
                // downstream operators receive correctly-typed Schema.Column keys.
                // Previously this hard-coded DataType.VARCHAR, which caused Tuple.find()
                // to return null for INTEGER/FLOAT columns (Schema.Column is a record and
                // equality checks both name AND type), making every filter predicate on
                // non-VARCHAR columns evaluate to false.
                Schema childSchema = getOutputSchema(project.getChild());
                List<Schema.Column> columns = new ArrayList<>();
                for (String colName : project.getColumnNames()) {
                    columns.add(childSchema.getColumn(colName));
                }
                return new Schema(columns);
            }
            case LogicalJoin join -> {
                Schema leftSchema = getOutputSchema(join.getLeft());
                Schema rightSchema = getOutputSchema(join.getRight());
                // Combine schemas
                List<Schema.Column> columns = new ArrayList<>();
                columns.addAll(leftSchema.getColumns());
                columns.addAll(rightSchema.getColumns());
                return new Schema(columns);
            }
            case LogicalAggregate agg -> {
                Schema childSchema = getOutputSchema(agg.getChild());
                List<Schema.Column> columns = new ArrayList<>();
                for (String colName : agg.getGroupByColumns()) {
                    columns.add(childSchema.getColumn(colName));
                }
                for (LogicalAggregate.AggregateOp op : agg.getAggregateOps()) {
                    columns.add(new Schema.Column(op.outputColumn(),
                            AggregateAccumulator.resultType(op, childSchema)));
                }
                return new Schema(columns);
            }
            default -> throw new IllegalArgumentException("Cannot determine schema for: " +
                    node.getClass().getSimpleName());
        }
    }
}