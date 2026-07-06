package org.query.optimizer.learned.common;

import org.query.optimizer.JoinOrderPolicy;
import org.query.optimizer.Rule;
import org.query.optimizer.rules.FilterMerge;
import org.query.optimizer.rules.PredicatePushdown;
import org.query.optimizer.rules.ProjectionPushdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An immutable configuration object that controls which optimizer rules fire
 * and which physical join algorithm is preferred.
 *
 * <p>In Bao's bandit terminology each {@code HintSet} is one <em>arm</em>: a
 * distinct way to steer the optimizer. The bandit learns, over repeated query
 * executions, which arm tends to produce the fastest plan for a given query
 * shape.
 *
 * <h2>Predefined arms</h2>
 * <p>Six arms are defined as public constants. A small arm space is deliberate:
 * it keeps the exploration cost tractable while still producing meaningfully
 * different physical plans (full-optimisation with hash join, full-optimisation
 * with NLJ, no-pushdown variants, a minimal-optimisation baseline, and a
 * preserve-input-join-order variant).
 *
 * <h2>Extending the arm space</h2>
 * <p>Additional knobs — join-order hints, index preferences, batch-size
 * choices — can be added as new fields without breaking existing arms or the
 * bandit interface.
 */
public final class HintSet {

    // -------------------------------------------------------------------------
    // Predefined arms
    // -------------------------------------------------------------------------

    /** All optimisations on, prefer hash join (the standard optimizer path). */
    public static final HintSet DEFAULT =
            new HintSet("default", true, true, true, true);

    /** All optimisations on, force nested-loop join for every join. */
    public static final HintSet FORCE_NLJ =
            new HintSet("force_nlj", true, true, true, false);

    /**
     * Predicate and projection pushdown disabled; hash join allowed.
     * Filters stay above joins — exercises the cost model's ability to handle
     * unoptimised plans.
     */
    public static final HintSet NO_PUSHDOWN =
            new HintSet("no_pushdown", false, false, true, true);

    /**
     * Predicate and projection pushdown disabled; nested-loop join forced.
     * The "worst of both worlds" arm — useful as a lower-bound sanity check.
     */
    public static final HintSet NO_PUSHDOWN_NLJ =
            new HintSet("no_pushdown_nlj", false, false, true, false);

    /**
     * No rule-based optimisation at all (not even filter merge); hash join
     * allowed. Produces the raw logical plan converted directly to physical.
     */
    public static final HintSet MINIMAL_OPT =
            new HintSet("minimal", false, false, false, true);

    /**
     * All optimisations on, hash join, but join ordering left in the input (FROM)
     * order rather than searched with dynamic programming. This adds join order as
     * a bandit dimension: the model can learn when keeping the written order beats
     * the cost-based DP order (and vice versa).
     */
    public static final HintSet PRESERVE_ORDER =
            new HintSet("preserve_order", true, true, true, true, JoinOrderPolicy.PRESERVE_INPUT);

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final String  name;
    private final boolean enablePredicatePushdown;
    private final boolean enableProjectionPushdown;
    private final boolean enableFilterMerge;
    private final boolean preferHashJoin;
    private final JoinOrderPolicy joinOrderPolicy;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /** Creates a hint set using dynamic-programming join ordering. */
    public HintSet(String  name,
                   boolean enablePredicatePushdown,
                   boolean enableProjectionPushdown,
                   boolean enableFilterMerge,
                   boolean preferHashJoin) {
        this(name, enablePredicatePushdown, enableProjectionPushdown, enableFilterMerge,
                preferHashJoin, JoinOrderPolicy.DP);
    }

    public HintSet(String  name,
                   boolean enablePredicatePushdown,
                   boolean enableProjectionPushdown,
                   boolean enableFilterMerge,
                   boolean preferHashJoin,
                   JoinOrderPolicy joinOrderPolicy) {
        this.name                    = Objects.requireNonNull(name, "name");
        this.enablePredicatePushdown = enablePredicatePushdown;
        this.enableProjectionPushdown = enableProjectionPushdown;
        this.enableFilterMerge       = enableFilterMerge;
        this.preferHashJoin          = preferHashJoin;
        this.joinOrderPolicy         = Objects.requireNonNull(joinOrderPolicy, "joinOrderPolicy");
    }

    // -------------------------------------------------------------------------
    // Arm registry
    // -------------------------------------------------------------------------

    /**
     * Returns an unmodifiable list of all six predefined hint sets, in the
     * order they are defined above.  This list is the default arm space for
     * the Bao bandit.
     */
    public static List<HintSet> allHintSets() {
        return List.of(DEFAULT, FORCE_NLJ, NO_PUSHDOWN, NO_PUSHDOWN_NLJ, MINIMAL_OPT, PRESERVE_ORDER);
    }

    // -------------------------------------------------------------------------
    // Rule construction
    // -------------------------------------------------------------------------

    /**
     * Returns the list of {@link Rule}s implied by this hint set.  Passing
     * this list directly to {@link org.query.optimizer.RuleEngine} is the
     * intended usage.
     *
     * <p>Rules are returned in the canonical application order:
     * predicate pushdown → projection pushdown → filter merge.
     */
    public List<Rule> getRules() {
        List<Rule> rules = new ArrayList<>();
        if (enablePredicatePushdown)  rules.add(new PredicatePushdown());
        if (enableProjectionPushdown) rules.add(new ProjectionPushdown());
        if (enableFilterMerge)        rules.add(new FilterMerge());
        return rules;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String  getName()                    { return name; }
    public boolean isPredicatePushdownEnabled() { return enablePredicatePushdown; }
    public boolean isProjectionPushdownEnabled(){ return enableProjectionPushdown; }
    public boolean isFilterMergeEnabled()       { return enableFilterMerge; }
    public boolean preferHashJoin()             { return preferHashJoin; }
    public JoinOrderPolicy joinOrderPolicy()    { return joinOrderPolicy; }

    // -------------------------------------------------------------------------
    // Object overrides
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HintSet h)) return false;
        return enablePredicatePushdown  == h.enablePredicatePushdown
            && enableProjectionPushdown == h.enableProjectionPushdown
            && enableFilterMerge        == h.enableFilterMerge
            && preferHashJoin           == h.preferHashJoin
            && joinOrderPolicy          == h.joinOrderPolicy
            && name.equals(h.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name,
                enablePredicatePushdown,
                enableProjectionPushdown,
                enableFilterMerge,
                preferHashJoin,
                joinOrderPolicy);
    }

    @Override
    public String toString() {
        return String.format(
                "HintSet[%s pred=%b proj=%b merge=%b hash=%b order=%s]",
                name,
                enablePredicatePushdown,
                enableProjectionPushdown,
                enableFilterMerge,
                preferHashJoin,
                joinOrderPolicy);
    }
}
