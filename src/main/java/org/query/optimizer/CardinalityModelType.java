package org.query.optimizer;

/**
 * Selects which {@link CardinalityModel} the optimizer uses for cardinality
 * estimation.
 */
public enum CardinalityModelType {
    /** Built-in heuristic estimator (the default; always available). */
    HEURISTIC,
    /**
     * Learned estimator. Used only when the optimizer has been given a trained
     * learned model; otherwise the optimizer falls back to the heuristic. The
     * learned model itself also falls back per-subplan for unsupported shapes.
     */
    LEARNED
}
