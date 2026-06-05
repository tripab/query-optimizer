package org.query.optimizer;

import org.query.optimizer.learned.common.HintSet;
import org.query.optimizer.rules.FilterMerge;
import org.query.optimizer.rules.PredicatePushdown;
import org.query.optimizer.rules.ProjectionPushdown;

import java.util.ArrayList;
import java.util.List;

public record OptimizationOptions(
        boolean enablePredicatePushdown,
        boolean enableProjectionPushdown,
        boolean enableFilterMerge,
        JoinOrderPolicy joinOrderPolicy,
        JoinAlgorithmPolicy joinAlgorithmPolicy,
        CardinalityModelType cardinalityModelType
) {
    /**
     * Convenience constructor that uses the heuristic cardinality model — the
     * historical default — so existing five-argument callers are unaffected.
     */
    public OptimizationOptions(boolean enablePredicatePushdown,
                               boolean enableProjectionPushdown,
                               boolean enableFilterMerge,
                               JoinOrderPolicy joinOrderPolicy,
                               JoinAlgorithmPolicy joinAlgorithmPolicy) {
        this(enablePredicatePushdown, enableProjectionPushdown, enableFilterMerge,
                joinOrderPolicy, joinAlgorithmPolicy, CardinalityModelType.HEURISTIC);
    }

    public static OptimizationOptions defaults() {
        return new OptimizationOptions(
                true,
                true,
                true,
                JoinOrderPolicy.DP,
                JoinAlgorithmPolicy.FORCE_HASH,
                CardinalityModelType.HEURISTIC
        );
    }

    public List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        if (enablePredicatePushdown) {
            rules.add(new PredicatePushdown());
        }
        if (enableProjectionPushdown) {
            rules.add(new ProjectionPushdown());
        }
        if (enableFilterMerge) {
            rules.add(new FilterMerge());
        }
        return rules;
    }

    public boolean preferHashJoin() {
        return joinAlgorithmPolicy != JoinAlgorithmPolicy.FORCE_NLJ;
    }

    public static OptimizationOptions fromHintSet(HintSet hints) {
        return new OptimizationOptions(
                hints.isPredicatePushdownEnabled(),
                hints.isProjectionPushdownEnabled(),
                hints.isFilterMergeEnabled(),
                JoinOrderPolicy.DP,
                hints.preferHashJoin() ? JoinAlgorithmPolicy.FORCE_HASH : JoinAlgorithmPolicy.FORCE_NLJ
        );
    }
}
