package org.query.optimizer.executor;

/**
 * Selects which execution engine drives a query.
 *
 * <p>{@link #VOLCANO} uses the classic tuple-at-a-time iterator model
 * ({@link org.query.optimizer.physical.PhysicalPlanBuilder} +
 * {@link Executor}).  One {@link org.query.optimizer.catalog.Tuple} is
 * pulled per {@code next()} call; values are boxed {@code Object}s.
 *
 * <p>{@link #VECTORIZED} uses the batch-at-a-time columnar engine
 * ({@link org.query.optimizer.vectorized.VectorizedPlanBuilder} +
 * {@link org.query.optimizer.vectorized.VectorizedExecutor}).
 * Up to {@link org.query.optimizer.vectorized.ColumnBatch#DEFAULT_BATCH_SIZE}
 * rows are processed per {@code next()} call using typed primitive arrays and
 * selection vectors, avoiding per-row boxing and virtual dispatch.
 *
 * <p>Both modes accept the same optimised {@link org.query.optimizer.logical.LogicalNode}
 * tree, so switching between them requires only changing this enum value —
 * the parse → optimise pipeline is shared.
 */
public enum ExecutionMode {

    /**
     * Classic Volcano / iterator model.
     * One tuple per {@code next()} call; row-oriented, boxed values.
     */
    VOLCANO,

    /**
     * Vectorized / batch-at-a-time model.
     * Up to 1024 rows per {@code next()} call; columnar typed arrays,
     * selection vectors, minimal boxing.
     */
    VECTORIZED
}
