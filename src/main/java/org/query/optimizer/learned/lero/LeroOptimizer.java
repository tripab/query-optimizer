package org.query.optimizer.learned.lero;

import org.query.optimizer.SimpleCostModel;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.executor.Executor.ExecutionResult;
import org.query.optimizer.learned.common.HintSet;
import org.query.optimizer.learned.common.PlanFeaturizer;
import org.query.optimizer.learned.common.PlanVariantGenerator;
import org.query.optimizer.learned.common.WorkloadGenerator.ParsedQuery;
import org.query.optimizer.learned.lero.PairwiseComparator.TrainingPair;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.physical.PhysicalNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Lero: a learning-to-rank query optimizer that selects physical plans via
 * pairwise comparison rather than absolute cost prediction.
 *
 * <h2>Algorithm</h2>
 * <p>Lero operates in two phases:
 *
 * <h3>Warm-up phase (first {@value #WARMUP_QUERIES} queries)</h3>
 * <p>For each query, all plan variants are executed (exploration), all pairwise
 * comparisons are added to the training set, and the {@link PairwiseComparator}
 * is retrained. The query itself is answered using the cost model so the user
 * sees sensible results before Lero has learned anything.
 *
 * <h3>Warm phase (queries beyond warm-up)</h3>
 * <p>The comparator ranks candidate plans via
 * {@link PairwiseComparator#tournamentSelect} and executes the winner.
 * Every {@value #EXPLORE_INTERVAL}th query still explores all variants to
 * keep the model adapting to workload shifts.
 *
 * <h2>Construction</h2>
 * <p>Use {@link #LeroOptimizer(Catalog)} for defaults, or the full constructor
 * to inject specific components for testing.
 */
public class LeroOptimizer {

    /** Queries to explore before trusting the comparator's rankings. */
    static final int WARMUP_QUERIES   = 30;
    /** How often (in queries) to re-explore after the warm-up phase. */
    static final int EXPLORE_INTERVAL = 10;

    private final PlanVariantGenerator variantGenerator;
    private final PlanFeaturizer       featurizer;
    private final PairwiseComparator   comparator;
    private final PlanExplorer         explorer;
    private final Executor             executor;

    private int queriesSeen = 0;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Convenience constructor using default components and all five hint sets.
     *
     * @param catalog the catalog shared with the rest of the optimizer
     */
    public LeroOptimizer(Catalog catalog) {
        this(catalog, new Random());
    }

    public LeroOptimizer(Catalog catalog, Random random) {
        SimpleCostModel costModel = new SimpleCostModel(catalog);
        this.variantGenerator = new PlanVariantGenerator(catalog, costModel);
        this.featurizer       = new PlanFeaturizer();
        this.comparator       = new PairwiseComparator(random);
        this.explorer         = new PlanExplorer(catalog);
        this.executor         = new Executor();
    }

    /** Full constructor for tests that inject specific components. */
    public LeroOptimizer(PlanVariantGenerator variantGenerator,
                         PlanFeaturizer featurizer,
                         PairwiseComparator comparator,
                         PlanExplorer explorer,
                         Executor executor) {
        this.variantGenerator = variantGenerator;
        this.featurizer       = featurizer;
        this.comparator       = comparator;
        this.explorer         = explorer;
        this.executor         = executor;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Optimizes and executes a single query, updating the model if warranted.
     *
     * @param logicalPlan the unoptimized logical plan
     * @return the execution result
     */
    public ExecutionResult optimizeAndExecute(LogicalNode logicalPlan) {
        return doOptimize(logicalPlan).result();
    }

    /**
     * Runs the entire workload through Lero, returning per-query metrics.
     *
     * @param workload list of parsed queries to execute in order
     * @return one {@link QueryMetrics} entry per query
     */
    public List<QueryMetrics> runWorkload(List<ParsedQuery> workload) {
        List<QueryMetrics> metrics = new ArrayList<>(workload.size());
        for (ParsedQuery q : workload) {
            OptimizeStep step = doOptimize(q.logicalPlan());
            metrics.add(new QueryMetrics(
                    q.sql(),
                    step.result(),
                    step.selectedPlan(),
                    step.usedCostModel()));
        }
        return metrics;
    }

    // -------------------------------------------------------------------------
    // Core loop
    // -------------------------------------------------------------------------

    private OptimizeStep doOptimize(LogicalNode logicalPlan) {
        Map<HintSet, PhysicalNode> variants =
                variantGenerator.generateVariants(logicalPlan, HintSet.allHintSets());
        List<HintSet>     keys    = new ArrayList<>(variants.keySet());
        queriesSeen++;

        PhysicalNode selectedPlan;
        boolean      usedCostModel;

        if (queriesSeen <= WARMUP_QUERIES) {
            // Cold start: answer with cost model, but explore all plans for training
            explorer.exploreQuery(logicalPlan);
            retrainComparator();
            selectedPlan  = selectByCostModel(variants);
            usedCostModel = true;
        } else {
            // Warm: use Lero's tournament ranking
            List<double[]> featuresList = new ArrayList<>(keys.size());
            for (HintSet key : keys) {
                featuresList.add(featurizer.featurize(variants.get(key)));
            }
            int bestIdx  = comparator.tournamentSelect(featuresList);
            selectedPlan = variants.get(keys.get(bestIdx));
            usedCostModel = false;

            // Periodically explore to keep the model adapting
            if (queriesSeen % EXPLORE_INTERVAL == 0) {
                explorer.exploreQuery(logicalPlan);
                retrainComparator();
            }
        }

        ExecutionResult result = executor.execute(selectedPlan);
        return new OptimizeStep(result, selectedPlan, usedCostModel);
    }

    // -------------------------------------------------------------------------
    // Training
    // -------------------------------------------------------------------------

    private void retrainComparator() {
        List<TrainingPair> data = new ArrayList<>(explorer.getTrainingPairs());
        Collections.shuffle(data);
        for (int epoch = 0; epoch < 10; epoch++) {
            for (TrainingPair pair : data) {
                comparator.trainStep(pair.featuresA(), pair.featuresB(), pair.aIsFaster());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cost-model fallback
    // -------------------------------------------------------------------------

    /** Returns the variant with the lowest {@link PhysicalNode#getEstimatedCost()}. */
    private static PhysicalNode selectByCostModel(Map<HintSet, PhysicalNode> variants) {
        PhysicalNode best     = null;
        double       bestCost = Double.MAX_VALUE;
        for (PhysicalNode plan : variants.values()) {
            double cost = plan.getEstimatedCost();
            if (best == null || cost < bestCost) {
                best     = plan;
                bestCost = cost;
            }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // Internal step record
    // -------------------------------------------------------------------------

    private record OptimizeStep(ExecutionResult result,
                                PhysicalNode selectedPlan,
                                boolean usedCostModel) {}

    // -------------------------------------------------------------------------
    // Public result type
    // -------------------------------------------------------------------------

    /**
     * Per-query metrics collected during {@link #runWorkload}.
     *
     * @param sql           the original SQL string
     * @param result        full execution result (tuples + timing)
     * @param selectedPlan  the physical plan that was executed
     * @param usedCostModel true during warm-up when cost model made the choice
     */
    public record QueryMetrics(
            String          sql,
            ExecutionResult result,
            PhysicalNode    selectedPlan,
            boolean         usedCostModel) {}
}
