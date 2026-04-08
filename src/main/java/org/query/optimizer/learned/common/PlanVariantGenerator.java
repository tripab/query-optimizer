package org.query.optimizer.learned.common;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.OptimizationOptions;
import org.query.optimizer.QueryOptimizer;
import org.query.optimizer.SimpleCostModel;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.physical.PhysicalNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates one physical plan per {@link HintSet} for a given logical plan,
 * deduplicating structurally identical plans before returning.
 *
 * <p>This is the core shared component for both the Bao and Lero optimizers.
 * Each call to {@link #generateVariants} produces the full set of alternative
 * physical plans that the learned optimizer can choose from.
 *
 * <h2>Pipeline per hint set</h2>
 * <ol>
 *   <li>Create a fresh {@link RuleEngine} from the hint set's rule list.</li>
 *   <li>Run the rule engine to obtain an optimized logical plan.</li>
 *   <li>Annotate the optimized logical plan with cost and cardinality estimates
 *       so that {@link org.query.optimizer.learned.common.PlanFeaturizer} can
 *       read them during featurization.</li>
 *   <li>Build a physical plan via {@link PhysicalPlanBuilder}, configured
 *       with the hint set's join-algorithm preference.</li>
 *   <li>Deduplicate by structural signature — if two hint sets happen to
 *       produce the same operator tree, only the first is kept.</li>
 * </ol>
 *
 * <h2>Deduplication</h2>
 * <p>The signature is a depth-first concatenation of each node's
 * {@code describe()} string. This captures operator type, join conditions,
 * filter predicates, and table names — enough to distinguish any two plans
 * that differ in a way the learned model would care about.
 *
 * <h2>Thread safety</h2>
 * <p>Instances are not thread-safe. Create one per thread or protect with
 * external synchronization.
 */
public class PlanVariantGenerator {

    private final QueryOptimizer optimizer;

    public PlanVariantGenerator(Catalog catalog, SimpleCostModel costModel) {
        this.optimizer = new QueryOptimizer(catalog);
    }

    /**
     * Generates a physical plan for each hint set in {@code hintSets} and
     * returns a map from hint set to physical plan, preserving insertion order.
     *
     * <p>Hint sets whose optimized plan is structurally identical to one already
     * in the result are silently dropped. The caller can detect deduplication by
     * comparing {@code variants.size()} with {@code hintSets.size()}.
     *
     * @param logicalPlan the parsed, unoptimized logical plan (not mutated)
     * @param hintSets    ordered list of hint sets to try; must not be empty
     * @return a {@link LinkedHashMap} from surviving hint set → physical plan
     */
    public Map<HintSet, PhysicalNode> generateVariants(
            LogicalNode logicalPlan,
            List<HintSet> hintSets) {

        Map<HintSet, PhysicalNode> variants     = new LinkedHashMap<>();
        java.util.Set<String>      seenSigs      = new java.util.HashSet<>();

        for (HintSet hints : hintSets) {
            QueryOptimizer.OptimizationResult result = optimizer.optimize(
                    null,
                    logicalPlan,
                    OptimizationOptions.fromHintSet(hints)
            );
            LogicalNode optimized = result.optimizedLogicalPlan();
            PhysicalNode physical = result.physicalPlan();

            // 4. Deduplicate
            String sig = computeSignature(physical);
            if (seenSigs.add(sig)) {
                variants.put(hints, physical);
            }
        }

        return variants;
    }
    // -------------------------------------------------------------------------
    // Signature computation
    // -------------------------------------------------------------------------

    /**
     * Computes a structural signature for a physical plan tree.
     *
     * <p>The signature is built by a depth-first pre-order walk, appending
     * each node's {@code describe()} string separated by {@code "|"}. This
     * captures operator types, join conditions, filter predicates, and table
     * names in a canonical order that distinguishes any two plans that differ
     * structurally.
     */
    private String computeSignature(PhysicalNode node) {
        StringBuilder sb = new StringBuilder();
        appendSignature(node, sb);
        return sb.toString();
    }

    private void appendSignature(PhysicalNode node, StringBuilder sb) {
        if (sb.length() > 0) sb.append('|');
        sb.append(node.describe());
        for (PhysicalNode child : node.getChildren()) {
            appendSignature(child, sb);
        }
    }
}
