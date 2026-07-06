package org.query.optimizer.learned.lero;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.executor.Executor.ExecutionResult;
import org.query.optimizer.executor.ExecutionTimer;
import org.query.optimizer.executor.Timed;
import org.query.optimizer.learned.common.ExecutionFeedback;
import org.query.optimizer.learned.common.HintSet;
import org.query.optimizer.learned.common.PlanFeaturizer;
import org.query.optimizer.learned.common.PlanVariantGenerator;
import org.query.optimizer.learned.lero.PairwiseComparator.TrainingPair;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.physical.PhysicalNode;
import org.query.optimizer.SimpleCostModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates pairwise training data for {@link PairwiseComparator} by executing
 * all physical plan variants for a query and comparing their
 * {@link ExecutionFeedback#logicalCost(long, long) logical costs}.
 *
 * <h2>How it works</h2>
 * <p>For each call to {@link #exploreQuery}:
 * <ol>
 *   <li>Generate one physical plan per hint set via {@link PlanVariantGenerator}.</li>
 *   <li>Execute every variant and score it by logical cost (deterministic
 *       per-operator work + measured wall-clock milliseconds).</li>
 *   <li>Produce ordered pairs (both (i,j) and (j,i)) so the training
 *       distribution is balanced — skipping near-ties, whose ordering is
 *       noise (see {@link #MIN_COST_RATIO}).</li>
 * </ol>
 *
 * <p>All pairs from all explored queries accumulate in an internal list returned
 * by {@link #getTrainingPairs()}.
 *
 * <h2>Exploration cost</h2>
 * <p>Each explored query executes up to six plan variants instead of one, so it
 * costs several times more than a normal query. Lero limits exploration to the
 * warm-up phase and periodic refreshes (see {@link LeroOptimizer}).
 */
public class PlanExplorer {

    private final PlanVariantGenerator variantGenerator;
    private final PlanFeaturizer       featurizer;
    private final Executor             executor;
    private final List<TrainingPair>   trainingPairs;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public PlanExplorer(Catalog catalog) {
        SimpleCostModel costModel = new SimpleCostModel(catalog);
        this.variantGenerator = new PlanVariantGenerator(catalog, costModel);
        this.featurizer       = new PlanFeaturizer();
        this.executor         = new Executor();
        this.trainingPairs    = new ArrayList<>();
    }

    /** Full constructor for tests that inject specific components. */
    PlanExplorer(PlanVariantGenerator variantGenerator,
                 PlanFeaturizer featurizer,
                 Executor executor) {
        this.variantGenerator = variantGenerator;
        this.featurizer       = featurizer;
        this.executor         = executor;
        this.trainingPairs    = new ArrayList<>();
    }

    // -------------------------------------------------------------------------
    // Exploration
    // -------------------------------------------------------------------------

    /**
     * Two plans are only labeled as a training pair when their costs differ by
     * at least this ratio. Near-ties carry no reliable signal — their ordering
     * flips with timer noise from run to run, and training on such
     * contradictory labels is what drove the comparator into order-blind
     * saturation (predicting the same class regardless of argument order).
     * The Lero paper applies the same filter to its training pairs.
     */
    static final double MIN_COST_RATIO = 1.2;

    /**
     * Executes all plan variants for the given logical plan and appends the
     * resulting pairwise comparisons to the internal training-pair accumulator.
     *
     * @param logicalPlan the unoptimized logical plan to explore
     * @return the new pairs generated from this query (not the full accumulator)
     */
    public List<TrainingPair> exploreQuery(LogicalNode logicalPlan) {
        Map<HintSet, PhysicalNode> variants =
                variantGenerator.generateVariants(logicalPlan, HintSet.allHintSets());

        // Execute each variant and score it by logicalCost — dominated by the
        // deterministic per-operator work term, so labels are reproducible and
        // machine-independent; wall-clock only matters when it is large enough
        // to matter. (Raw nanosecond labels made sub-millisecond plan pairs
        // coin flips: JIT/GC jitter decided which variant was "faster".)
        List<PlanWithCost> executed = new ArrayList<>(variants.size());
        for (Map.Entry<HintSet, PhysicalNode> entry : variants.entrySet()) {
            PhysicalNode plan = entry.getValue();
            Timed<ExecutionResult> run = ExecutionTimer.run(() -> executor.execute(plan));
            double cost = ExecutionFeedback.logicalCost(
                    run.value().tuplesProcessed(), run.millis());
            executed.add(new PlanWithCost(featurizer.featurize(plan), cost));
        }

        // Generate ordered pairs — both (i,j) and (j,i) for balance — skipping
        // near-ties (see MIN_COST_RATIO)
        List<TrainingPair> newPairs = new ArrayList<>();
        for (int i = 0; i < executed.size(); i++) {
            for (int j = i + 1; j < executed.size(); j++) {
                double costI = executed.get(i).cost();
                double costJ = executed.get(j).cost();
                if (Math.max(costI, costJ) < MIN_COST_RATIO * Math.min(costI, costJ)
                        || costI == costJ) {
                    continue;
                }
                boolean iIsFaster = costI < costJ;
                newPairs.add(new TrainingPair(
                        executed.get(i).features(),
                        executed.get(j).features(),
                        iIsFaster));
                newPairs.add(new TrainingPair(
                        executed.get(j).features(),
                        executed.get(i).features(),
                        !iIsFaster));
            }
        }

        trainingPairs.addAll(newPairs);
        return newPairs;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Returns all training pairs accumulated across every {@link #exploreQuery} call.
     * The list is a live view — callers must not modify it.
     */
    public List<TrainingPair> getTrainingPairs() {
        return trainingPairs;
    }

    // -------------------------------------------------------------------------
    // Internal record
    // -------------------------------------------------------------------------

    private record PlanWithCost(double[] features, double cost) {}
}
