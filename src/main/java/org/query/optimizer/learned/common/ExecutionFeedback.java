package org.query.optimizer.learned.common;

/**
 * Captures the outcome of executing one physical plan for one query.
 *
 * <p>This is the training signal for both learned optimizers. After the
 * executor runs a plan selected by a {@link HintSet}, the caller wraps the
 * results in an {@code ExecutionFeedback} and hands it to the model so it can
 * learn from the observed performance.
 *
 * <h2>logicalCost — the preferred training target</h2>
 * <p>Wall-clock time ({@code actualLatencyMs}) is noisy on small in-memory
 * tables where a single query may complete in under a millisecond. The
 * {@link #logicalCost()} derived field blends tuple-processing work with
 * wall-clock time to produce a more stable signal:
 * <pre>
 *   logicalCost = tuplesProcessed × 0.01 + max(actualLatencyMs, 0)
 * </pre>
 * For large datasets the two terms converge; for micro-benchmarks on small
 * tables the tuple-count term dominates, smoothing out timer noise.
 *
 * @param querySQL        the original SQL string
 * @param hintSet         the hint set that produced this plan
 * @param planFeatures    featurized plan vector ({@link PlanFeaturizer#FEATURE_DIM} elements)
 * @param actualLatencyMs wall-clock execution time in milliseconds
 * @param tuplesProcessed rows examined by the executor (from
 *                        {@code ExecutionResult.tuplesProcessed()})
 * @param estimatedCost   cost-model prediction for this plan
 * @param estimatedRows   cardinality-estimator prediction for this plan
 */
public record ExecutionFeedback(
        String   querySQL,
        HintSet  hintSet,
        double[] planFeatures,
        long     actualLatencyMs,
        long     tuplesProcessed,
        double   estimatedCost,
        long     estimatedRows
) {
    /**
     * Returns a deterministic proxy for execution latency that is more stable
     * than wall-clock time on small in-memory tables.
     *
     * <p>Both Bao's value model and Lero's pairwise comparator use this as
     * their training target rather than raw {@code actualLatencyMs}.
     */
    public double logicalCost() {
        return logicalCost(tuplesProcessed, actualLatencyMs);
    }

    /**
     * The {@link #logicalCost()} formula as a free function, so callers that
     * have a tuple count and a latency but no full feedback object (e.g. the
     * benchmark's oracle, which scores plan variants) can use the same stable
     * cost without duplicating the weighting.
     */
    public static double logicalCost(long tuplesProcessed, long actualLatencyMs) {
        return tuplesProcessed * 0.01 + Math.max(actualLatencyMs, 0);
    }
}
