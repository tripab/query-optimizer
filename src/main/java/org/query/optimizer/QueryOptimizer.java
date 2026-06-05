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

    private final Catalog catalog;
    private final SQLParser parser;
    private final LogicalPlanBuilder logicalPlanBuilder;
    private final PhysicalPlanBuilder physicalPlanBuilder;
    private final JoinExtractor joinExtractor;
    private final CardinalityModel heuristicModel;
    private final CardinalityModel learnedModel; // nullable; used only when options select LEARNED

    public QueryOptimizer(Catalog catalog) {
        this(catalog, null);
    }

    /**
     * @param catalog      the catalog
     * @param learnedModel a trained learned cardinality model to use when
     *                     {@link OptimizationOptions#cardinalityModelType()} is
     *                     {@code LEARNED}; may be {@code null}, in which case the
     *                     optimizer always falls back to the heuristic model
     */
    public QueryOptimizer(Catalog catalog, CardinalityModel learnedModel) {
        this.catalog = catalog;
        this.parser = new SQLParser();
        this.logicalPlanBuilder = new LogicalPlanBuilder(catalog);
        this.physicalPlanBuilder = new PhysicalPlanBuilder(catalog);
        this.joinExtractor = new JoinExtractor();
        this.heuristicModel = new HeuristicCardinalityModel(catalog);
        this.learnedModel = learnedModel;
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
        SimpleCostModel costModel = costModelFor(options);
        RuleEngine engine = new RuleEngine(options.rules());
        LogicalNode optimized = engine.optimize(logicalPlan);
        optimized = applyJoinReordering(optimized, options, costModel);
        annotatePlan(optimized, costModel);
        return optimized;
    }

    public PhysicalNode buildPhysicalPlan(LogicalNode logicalPlan, OptimizationOptions options) {
        physicalPlanBuilder.setJoinAlgorithmPolicy(options.joinAlgorithmPolicy());
        return physicalPlanBuilder.build(logicalPlan);
    }

    /**
     * Selects the cardinality model for this optimization. Uses the learned model
     * only when the options request it <em>and</em> one was supplied; otherwise
     * falls back to the heuristic model.
     */
    private SimpleCostModel costModelFor(OptimizationOptions options) {
        CardinalityModel model =
                (options.cardinalityModelType() == CardinalityModelType.LEARNED && learnedModel != null)
                        ? learnedModel
                        : heuristicModel;
        return new SimpleCostModel(catalog, model);
    }

    private void annotatePlan(LogicalNode node, SimpleCostModel costModel) {
        for (LogicalNode child : node.getChildren()) {
            annotatePlan(child, costModel);
        }
        node.setEstimatedRows(costModel.estimateCardinality(node));
        node.setEstimatedCost(costModel.estimate(node));
    }

    private LogicalNode applyJoinReordering(LogicalNode plan, OptimizationOptions options,
                                            SimpleCostModel costModel) {
        if (options.joinOrderPolicy() != JoinOrderPolicy.DP) {
            return plan;
        }

        JoinExtractor.JoinInfo joinInfo = joinExtractor.extract(plan);
        if (!joinInfo.supported() || !joinInfo.hasJoinTree() || joinInfo.leaves().size() < 3) {
            return plan;
        }

        try {
            DPJoinOrderer orderer = new DPJoinOrderer(costModel);
            LogicalNode reorderedJoinTree = orderer.findBestJoinOrder(joinInfo.leaves(), joinInfo.conditions());
            return joinExtractor.replaceJoinSubtree(plan, joinInfo.joinRoot(), reorderedJoinTree);
        } catch (IllegalStateException e) {
            return plan;
        }
    }
}
