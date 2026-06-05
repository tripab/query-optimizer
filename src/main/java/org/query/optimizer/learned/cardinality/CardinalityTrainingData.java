package org.query.optimizer.learned.cardinality;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.physical.PhysicalNode;
import org.query.optimizer.physical.PhysicalPlanBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds supervised training data for the learned cardinality model from a
 * workload of logical plans.
 *
 * <p>For every supported subplan in each query, the subplan is executed to obtain
 * its <em>exact</em> output cardinality (the ground-truth label) and featurized
 * with {@link CardinalityFeaturizer}. Enumerating every subplan — not just whole
 * queries — yields many labelled examples per query (scans, filters, joins, and
 * the intermediate results between them), which is what makes a tiny synthetic
 * workload enough to train on.
 */
public final class CardinalityTrainingData {

    /**
     * One labelled training example.
     *
     * @param subplan           the logical subplan (kept so a model can be re-evaluated on it)
     * @param features          its {@link CardinalityFeaturizer} feature vector
     * @param actualCardinality the exact number of rows the subplan produces
     */
    public record Example(LogicalNode subplan, double[] features, long actualCardinality) {
        /** Training target: natural log of the (floored-at-1) actual cardinality. */
        public double logCardinality() {
            return Math.log(Math.max(1, actualCardinality));
        }
    }

    private final Catalog catalog;
    private final CardinalityFeaturizer featurizer;
    private final PhysicalPlanBuilder physicalBuilder;

    public CardinalityTrainingData(Catalog catalog) {
        this.catalog = catalog;
        this.featurizer = new CardinalityFeaturizer(catalog);
        this.physicalBuilder = new PhysicalPlanBuilder(catalog);
    }

    public CardinalityFeaturizer featurizer() {
        return featurizer;
    }

    /**
     * Generates labelled examples for every supported subplan across {@code queries}.
     */
    public List<Example> generate(List<LogicalNode> queries) {
        List<Example> examples = new ArrayList<>();
        for (LogicalNode query : queries) {
            collectSubplans(query, examples);
        }
        return examples;
    }

    private void collectSubplans(LogicalNode node, List<Example> out) {
        if (featurizer.supports(node)) {
            long actual = executeCount(node);
            out.add(new Example(node, featurizer.featurize(node), actual));
        }
        for (LogicalNode child : node.getChildren()) {
            collectSubplans(child, out);
        }
    }

    private long executeCount(LogicalNode node) {
        PhysicalNode physical = physicalBuilder.build(node);
        return new Executor().execute(physical).getResultCount();
    }
}
