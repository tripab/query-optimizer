package org.query.optimizer.learned.lero;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.executor.ExecutionTimer;
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
 * all physical plan variants for a query and comparing their actual latencies.
 *
 * <h2>How it works</h2>
 * <p>For each call to {@link #exploreQuery}:
 * <ol>
 *   <li>Generate one physical plan per hint set via {@link PlanVariantGenerator}.</li>
 *   <li>Execute every variant and record wall-clock execution time in nanoseconds.</li>
 *   <li>Produce all N×(N−1) ordered pairs (both (i,j) and (j,i)) so the
 *       training distribution is balanced.</li>
 * </ol>
 *
 * <p>All pairs from all explored queries accumulate in an internal list returned
 * by {@link #getTrainingPairs()}.
 *
 * <h2>Exploration cost</h2>
 * <p>Each explored query executes up to 5 plan variants instead of 1, so it
 * costs up to 5× more than a normal query. Lero limits exploration to the
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
     * Executes all plan variants for the given logical plan and appends the
     * resulting pairwise comparisons to the internal training-pair accumulator.
     *
     * @param logicalPlan the unoptimized logical plan to explore
     * @return the new pairs generated from this query (not the full accumulator)
     */
    public List<TrainingPair> exploreQuery(LogicalNode logicalPlan) {
        Map<HintSet, PhysicalNode> variants =
                variantGenerator.generateVariants(logicalPlan, HintSet.allHintSets());

        // Execute each variant and record (features, latency in nanoseconds).
        // Nanosecond resolution matters here: at millisecond resolution every
        // sub-millisecond variant reads 0 ms, so the "i is faster" labels below
        // would collapse to "true" for nearly every pair and teach the
        // comparator nothing.
        List<PlanWithLatency> executed = new ArrayList<>(variants.size());
        for (Map.Entry<HintSet, PhysicalNode> entry : variants.entrySet()) {
            PhysicalNode plan         = entry.getValue();
            long         latencyNanos = ExecutionTimer.run(() -> executor.execute(plan)).nanos();
            double[]     features     = featurizer.featurize(plan);
            executed.add(new PlanWithLatency(features, latencyNanos));
        }

        // Generate all ordered pairs — both (i,j) and (j,i) for balance
        List<TrainingPair> newPairs = new ArrayList<>();
        for (int i = 0; i < executed.size(); i++) {
            for (int j = i + 1; j < executed.size(); j++) {
                boolean iIsFaster =
                        executed.get(i).latencyNanos() <= executed.get(j).latencyNanos();
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

    private record PlanWithLatency(double[] features, long latencyNanos) {}
}
