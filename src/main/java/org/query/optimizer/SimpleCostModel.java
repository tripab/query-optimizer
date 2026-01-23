package org.query.optimizer;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.CostModel;
import org.query.optimizer.catalog.TableMetadata;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.*;

/**
 * Simple cost model for query optimization.
 * <p>
 * Cost formula: Cost = I/O_cost + CPU_cost
 * - I/O_cost = pages_read * PAGE_COST
 * - CPU_cost = tuples_processed * TUPLE_COST
 * <p>
 * This is a simplified version of the System R cost model.
 * Production systems use more sophisticated models that consider:
 * - Memory availability
 * - Network costs (for distributed systems)
 * - Multi-dimensional costs (time, memory, I/O)
 */
public class SimpleCostModel implements CostModel {
    private final Catalog catalog;
    private final CostConfig costConfig;
    private final CardinalityEstimator cardinalityEstimator;

    public SimpleCostModel(Catalog catalog) {
        this(catalog, new CostConfig());
    }

    public SimpleCostModel(Catalog catalog, CostConfig costConfig) {
        this.catalog = catalog;
        this.costConfig = costConfig;
        this.cardinalityEstimator = new CardinalityEstimator(catalog);
    }

    @Override
    public double estimate(LogicalNode node) {
        // First ensure we have cardinality estimates
        long cardinality = estimateCardinality(node);
        node.setEstimatedRows(cardinality);

        // Calculate cost based on operator type
        return switch (node) {
            case LogicalScan logicalScan -> estimateScanCost(logicalScan);
            case LogicalFilter filter -> estimateFilterCost(filter);
            case LogicalProject project -> estimateProjectCost(project);
            case LogicalJoin join -> estimateJoinCost(join);
            case LogicalAggregate logicalAggregate -> estimateAggregateCost(logicalAggregate);
            default -> 1000000.0; // unknown operator, so we return high cost
        };
    }

    private double estimateAggregateCost(LogicalAggregate aggregate) {
        double childCost = estimate(aggregate.getChild());
        long inputRows = aggregate.getChild().getEstimatedRows();
        long outputRows = estimateCardinality(aggregate);
        // Cost to hash and aggregate
        double aggregationCost = inputRows * costConfig.HASH_COST + outputRows * costConfig.TUPLE_COST;

        return childCost + aggregationCost;
    }

    /**
     * Cost to join two relations.
     * We use nested loop join cost for simplicity.
     * <p>
     * Cost = left_cost + right_cost + (left_rows * right_rows * COMPARISON_COST)
     * <p>
     * Note: This is worst-case. Hash join would be:
     * Cost = left_cost + right_cost + (left_rows + right_rows) * HASH_COST
     */
    private double estimateJoinCost(LogicalJoin join) {
        double leftCost = estimate(join.getLeft());
        double rightCost = estimate(join.getRight());
        long leftRows = join.getLeft().getEstimatedRows();
        long rightRows = join.getRight().getEstimatedRows();
        // Nested loop join cost: for each left row, scan right relation
        double joinCost = leftRows * (rightRows * costConfig.COMPARISON_COST);

        return leftCost + rightCost + joinCost;
    }

    /**
     * Cost to project columns.
     * Cost = child_cost + (input_rows * TUPLE_COST)
     * (Projection is essentially copying selected columns)
     */
    private double estimateProjectCost(LogicalProject project) {
        double childCost = estimate(project.getChild());
        long inputRows = project.getChild().getEstimatedRows();
        double projectCost = inputRows * costConfig.TUPLE_COST;

        return childCost + projectCost;
    }

    /**
     * Cost to filter rows.
     * Cost = child_cost + (input_rows * TUPLE_COST * COMPARISON_COST)
     */
    private double estimateFilterCost(LogicalFilter filter) {
        double childCost = estimate(filter.getChild());
        long inputRows = filter.getChild().getEstimatedRows();
        // Cost to evaluate predicate for each row
        double filterCost = inputRows * costConfig.COMPARISON_COST;

        return childCost + filterCost;
    }

    /**
     * Cost to scan a table.
     * Cost = (table_pages * PAGE_COST) + (table_rows * TUPLE_COST)
     */
    private double estimateScanCost(LogicalScan scan) {
        TableMetadata table = catalog.getTableMetadata(scan.getTableName());
        long rows = table.getRowCount();
        long pages = (rows + costConfig.PAGE_SIZE - 1) / costConfig.PAGE_SIZE;
        double ioCost = pages * costConfig.PAGE_COST;
        double cpuCost = rows * costConfig.TUPLE_COST;

        return ioCost + cpuCost;
    }

    @Override
    public long estimateCardinality(LogicalNode node) {
        return cardinalityEstimator.estimate(node);
    }

    @Override
    public CostConfig getConfig() {
        return costConfig;
    }
}
