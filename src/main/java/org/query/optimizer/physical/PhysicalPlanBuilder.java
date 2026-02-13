package org.query.optimizer.physical;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.DataType;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.TableMetadata;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts optimized logical plans to executable physical plans.
 * <p>
 * Makes decisions about:
 * - Which join algorithm to use (nested loop vs hash)
 * - Physical operator implementations
 * - Schema propagation
 */
public class PhysicalPlanBuilder {
    private final Catalog catalog;
    private final boolean preferHashJoin;

    public PhysicalPlanBuilder(Catalog catalog) {
        this(catalog, true); // Prefer hash join by default
    }

    public PhysicalPlanBuilder(Catalog catalog, boolean preferHashJoin) {
        this.catalog = catalog;
        this.preferHashJoin = preferHashJoin;
    }

    /**
     * Convert logical plan to physical plan.
     */
    public PhysicalNode build(LogicalNode logicalPlan) {
        return convertNode(logicalPlan);
    }

    /**
     * Recursively convert logical nodes to physical nodes.
     */
    private PhysicalNode convertNode(LogicalNode node) {
        // Aggregation not implemented yet - would be next step
        return switch (node) {
            case LogicalScan logicalScan -> convertScan(logicalScan);
            case LogicalFilter logicalFilter -> convertFilter(logicalFilter);
            case LogicalProject logicalProject -> convertProject(logicalProject);
            case LogicalJoin logicalJoin -> convertJoin(logicalJoin);
            case LogicalAggregate logicalAggregate ->
                    throw new UnsupportedOperationException("Aggregation not yet implemented");
            default -> throw new IllegalArgumentException("Unknown logical node type: " +
                    node.getClass().getSimpleName());
        };
    }

    /**
     * Convert LogicalScan to PhysicalScan.
     */
    private PhysicalNode convertScan(LogicalScan scan) {
        PhysicalScan physicalScan = new PhysicalScan(scan.getTableName(), catalog);

        // Copy annotations from logical plan
        if (scan.getEstimatedRows() > 0) {
            physicalScan.setEstimatedRows(scan.getEstimatedRows());
        }
        if (scan.getEstimatedCost() >= 0) {
            physicalScan.setEstimatedCost(scan.getEstimatedCost());
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

        // Copy annotations
        if (filter.getEstimatedRows() > 0) {
            physicalFilter.setEstimatedRows(filter.getEstimatedRows());
        }
        if (filter.getEstimatedCost() >= 0) {
            physicalFilter.setEstimatedCost(filter.getEstimatedCost());
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

        // Copy annotations
        if (project.getEstimatedRows() > 0) {
            physicalProject.setEstimatedRows(project.getEstimatedRows());
        }
        if (project.getEstimatedCost() >= 0) {
            physicalProject.setEstimatedCost(project.getEstimatedCost());
        }

        return physicalProject;
    }

    /**
     * Convert LogicalJoin to PhysicalJoin.
     * Decides between nested loop and hash join based on cost.
     */
    private PhysicalNode convertJoin(LogicalJoin join) {
        PhysicalNode left = convertNode(join.getLeft());
        PhysicalNode right = convertNode(join.getRight());

        Schema leftSchema = getOutputSchema(join.getLeft());
        Schema rightSchema = getOutputSchema(join.getRight());

        PhysicalNode physicalJoin;

        // Decision: Hash join or nested loop?
        if (preferHashJoin && isEquiJoin(join.getCondition())) {
            // Use hash join for equi-joins
            physicalJoin = new PhysicalHashJoin(
                    left, right, join.getCondition(), leftSchema, rightSchema
            );
        } else {
            // Use nested loop join for non-equi joins or when specified
            physicalJoin = new PhysicalNestedLoopJoin(
                    left, right, join.getCondition(), leftSchema, rightSchema
            );
        }

        // Copy annotations
        if (join.getEstimatedRows() > 0) {
            physicalJoin.setEstimatedRows(join.getEstimatedRows());
        }
        if (join.getEstimatedCost() >= 0) {
            physicalJoin.setEstimatedCost(join.getEstimatedCost());
        }

        return physicalJoin;
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
                // Build schema from projection list
                List<Schema.Column> columns = new ArrayList<>();
                for (int i = 0; i < project.getColumnNames().size(); i++) {
                    // Simplified: assume all projected columns are VARCHAR
                    // A full implementation would track types through expressions
                    columns.add(new Schema.Column(
                            project.getColumnNames().get(i),
                            DataType.VARCHAR
                    ));
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
            default -> throw new IllegalArgumentException("Cannot determine schema for: " +
                    node.getClass().getSimpleName());
        }
    }
}