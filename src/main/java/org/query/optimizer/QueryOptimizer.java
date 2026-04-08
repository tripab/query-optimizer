package org.query.optimizer;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.AST;
import org.query.optimizer.parser.LogicalPlanBuilder;
import org.query.optimizer.parser.SQLParser;
import org.query.optimizer.physical.PhysicalNode;
import org.query.optimizer.physical.PhysicalPlanBuilder;

public class QueryOptimizer {
    public record OptimizationResult(AST.SelectStmt ast, LogicalNode initialLogicalPlan,
                                     LogicalNode optimizedLogicalPlan, PhysicalNode physicalPlan) {
    }

    private final SQLParser parser;
    private final LogicalPlanBuilder logicalPlanBuilder;
    private final SimpleCostModel costModel;
    private final PhysicalPlanBuilder physicalPlanBuilder;
    private final JoinExtractor joinExtractor;

    public QueryOptimizer(Catalog catalog) {
        this.parser = new SQLParser();
        this.logicalPlanBuilder = new LogicalPlanBuilder(catalog);
        this.costModel = new SimpleCostModel(catalog);
        this.physicalPlanBuilder = new PhysicalPlanBuilder(catalog);
        this.joinExtractor = new JoinExtractor();
    }

    public OptimizationResult optimize(String sql) {
        return optimize(sql, OptimizationOptions.defaults());
    }

    public OptimizationResult optimize(String sql, OptimizationOptions options) {
        AST.SelectStmt ast = parser.parse(sql);
        LogicalNode initial = logicalPlanBuilder.build(ast);
        return optimize(ast, initial, options);
    }

    public OptimizationResult optimize(AST.SelectStmt ast, LogicalNode logicalPlan, OptimizationOptions options) {
        LogicalNode optimized = optimizeLogical(logicalPlan, options);
        PhysicalNode physical = buildPhysicalPlan(optimized, options);
        return new OptimizationResult(ast, logicalPlan, optimized, physical);
    }

    public LogicalNode optimizeLogical(LogicalNode logicalPlan, OptimizationOptions options) {
        RuleEngine engine = new RuleEngine(options.rules());
        LogicalNode optimized = engine.optimize(logicalPlan);
        optimized = applyJoinReordering(optimized, options);
        annotatePlan(optimized);
        return optimized;
    }

    public PhysicalNode buildPhysicalPlan(LogicalNode logicalPlan, OptimizationOptions options) {
        physicalPlanBuilder.setPreferHashJoin(options.preferHashJoin());
        return physicalPlanBuilder.build(logicalPlan);
    }

    private void annotatePlan(LogicalNode node) {
        for (LogicalNode child : node.getChildren()) {
            annotatePlan(child);
        }
        node.setEstimatedRows(costModel.estimateCardinality(node));
        node.setEstimatedCost(costModel.estimate(node));
    }

    private LogicalNode applyJoinReordering(LogicalNode plan, OptimizationOptions options) {
        if (options.joinOrderPolicy() != JoinOrderPolicy.DP) {
            return plan;
        }

        JoinExtractor.JoinInfo joinInfo = joinExtractor.extract(plan);
        if (!joinInfo.supported() || !joinInfo.hasJoinTree() || joinInfo.scans().size() < 3) {
            return plan;
        }

        try {
            DPJoinOrderer orderer = new DPJoinOrderer(costModel);
            LogicalNode reorderedJoinTree = orderer.findBestJoinOrder(joinInfo.scans(), joinInfo.conditions());
            return joinExtractor.replaceJoinSubtree(plan, joinInfo.joinRoot(), reorderedJoinTree);
        } catch (IllegalStateException e) {
            return plan;
        }
    }
}
