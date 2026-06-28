package org.query.optimizer.learned.bao;

import org.query.optimizer.SimpleCostModel;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.executor.Executor.ExecutionResult;
import org.query.optimizer.executor.ExecutionTimer;
import org.query.optimizer.executor.Timed;
import org.query.optimizer.learned.common.ExecutionFeedback;
import org.query.optimizer.learned.common.HintSet;
import org.query.optimizer.learned.common.PlanFeaturizer;
import org.query.optimizer.learned.common.PlanVariantGenerator;
import org.query.optimizer.learned.common.WorkloadGenerator.ParsedQuery;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.physical.PhysicalNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Bao: a bandit-based query optimizer that uses Thompson Sampling to learn
 * which hint set produces the fastest physical plan for each query shape.
 *
 * <h2>Algorithm (per query)</h2>
 * <ol>
 *   <li>Generate one physical plan per arm ({@link HintSet}) via
 *       {@link PlanVariantGenerator}.</li>
 *   <li>Featurize each plan into a {@value PlanFeaturizer#FEATURE_DIM}-dim vector.</li>
 *   <li>Ask the {@link ThompsonSampler} to pick the arm with the lowest sampled
 *       predicted latency from the {@link PlanValueModel} ensemble.</li>
 *   <li>Execute the selected plan via the {@link Executor}.</li>
 *   <li>Record an {@link ExecutionFeedback} so the value model can improve.</li>
 * </ol>
 *
 * <h2>Learning</h2>
 * <p>{@link PlanValueModel} maintains a replay buffer and retrains its ensemble
 * every {@link PlanValueModel#RETRAIN_INTERVAL} queries, so the model gradually
 * learns which hint sets work best on the observed workload.
 *
 * <h2>Construction</h2>
 * <p>Use the {@link Builder} for full control, or the convenience constructor
 * {@link #BanditOptimizer(Catalog)} which sets up sensible defaults.
 */
public class BanditOptimizer {

    private final PlanVariantGenerator variantGenerator;
    private final PlanFeaturizer       featurizer;
    private final PlanValueModel       valueModel;
    private final ThompsonSampler      sampler;
    private final Executor             executor;
    private final List<HintSet>        arms;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Convenience constructor using default components and all five hint sets.
     *
     * @param catalog the catalog shared with the rest of the optimizer
     */
    public BanditOptimizer(Catalog catalog) {
        this(catalog, new Random());
    }

    public BanditOptimizer(Catalog catalog, Random random) {
        SimpleCostModel costModel = new SimpleCostModel(catalog);
        this.variantGenerator = new PlanVariantGenerator(catalog, costModel);
        this.featurizer       = new PlanFeaturizer();
        this.valueModel       = new PlanValueModel(random);
        this.sampler          = new ThompsonSampler(random);
        this.executor         = new Executor();
        this.arms             = HintSet.allHintSets();
    }

    /** Full constructor for tests that need to inject specific components. */
    public BanditOptimizer(PlanVariantGenerator variantGenerator,
                           PlanFeaturizer featurizer,
                           PlanValueModel valueModel,
                           ThompsonSampler sampler,
                           Executor executor,
                           List<HintSet> arms) {
        this.variantGenerator = variantGenerator;
        this.featurizer       = featurizer;
        this.valueModel       = valueModel;
        this.sampler          = sampler;
        this.executor         = executor;
        this.arms             = arms;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Optimizes and executes a single query, recording the outcome as training
     * data for the value model.
     *
     * @param logicalPlan the unoptimized logical plan
     * @return the execution result (tuples + timing)
     */
    public ExecutionResult optimizeAndExecute(LogicalNode logicalPlan) {
        return doOptimize("", logicalPlan).result();
    }

    /**
     * Runs the entire workload through Bao, returning per-query metrics that
     * include the selected arm, predicted latency, and actual execution time.
     *
     * @param workload list of parsed queries to execute in order
     * @return one {@link QueryMetrics} entry per query
     */
    public List<QueryMetrics> runWorkload(List<ParsedQuery> workload) {
        List<QueryMetrics> metrics = new ArrayList<>(workload.size());
        for (ParsedQuery q : workload) {
            OptimizeStep step = doOptimize(q.sql(), q.logicalPlan());
            metrics.add(new QueryMetrics(
                    q.sql(),
                    step.result(),
                    step.selectedArm(),
                    step.predictedLatency(),
                    step.actualLatencyMs()));
        }
        return metrics;
    }

    // -------------------------------------------------------------------------
    // Core loop
    // -------------------------------------------------------------------------

    /**
     * Core optimization step — generates variants, samples an arm, executes, and
     * records feedback.
     */
    private OptimizeStep doOptimize(String sql, LogicalNode logicalPlan) {
        // 1. Generate one physical plan per arm
        Map<HintSet, PhysicalNode> variants = variantGenerator.generateVariants(logicalPlan, arms);

        // 2. Featurize each variant
        Map<HintSet, double[]> featureMap = new HashMap<>();
        for (Map.Entry<HintSet, PhysicalNode> entry : variants.entrySet()) {
            featureMap.put(entry.getKey(), featurizer.featurize(entry.getValue()));
        }

        // 3. Thompson Sampling — select the arm with the lowest sampled latency
        HintSet selected          = sampler.selectArm(featureMap, valueModel);
        double  predictedLatency  = valueModel.predict(featureMap.get(selected)).mean();

        // 4. Execute the selected plan, timed via the nanoTime seam
        PhysicalNode           plan = variants.get(selected);
        Timed<ExecutionResult> run  = ExecutionTimer.run(() -> executor.execute(plan));
        ExecutionResult        result          = run.value();
        long                   actualLatencyMs = run.millis();

        // 5. Record feedback for learning
        ExecutionFeedback feedback = new ExecutionFeedback(
                sql,
                selected,
                featureMap.get(selected),
                actualLatencyMs,
                result.tuplesProcessed(),
                plan.getEstimatedCost(),
                plan.getEstimatedRows());
        valueModel.addFeedback(feedback);

        return new OptimizeStep(result, selected, predictedLatency, actualLatencyMs);
    }

    // -------------------------------------------------------------------------
    // Internal step record
    // -------------------------------------------------------------------------

    private record OptimizeStep(ExecutionResult result,
                                HintSet selectedArm,
                                double predictedLatency,
                                long actualLatencyMs) {}

    // -------------------------------------------------------------------------
    // Public result type
    // -------------------------------------------------------------------------

    /**
     * Per-query metrics collected during {@link #runWorkload}.
     *
     * @param sql               the original SQL string
     * @param result            full execution result (tuples + timing)
     * @param selectedArm       the hint set chosen by Thompson Sampling
     * @param predictedLatency  ensemble mean prediction at selection time
     * @param actualLatencyMs   observed wall-clock execution time in milliseconds
     */
    public record QueryMetrics(
            String          sql,
            ExecutionResult result,
            HintSet         selectedArm,
            double          predictedLatency,
            long            actualLatencyMs) {}
}
