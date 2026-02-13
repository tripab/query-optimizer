package org.query.optimizer.executor;

import org.query.optimizer.catalog.Tuple;

/**
 * Iterator interface for the Volcano/Iterator execution model.
 * <p>
 * Each physical operator implements this interface and produces
 * tuples one at a time. Operators are composed into a pipeline
 * by having parent operators call next() on their children.
 * <p>
 * Lifecycle:
 * 1. open() - Initialize the operator, allocate resources
 * 2. next() - Get the next tuple (called repeatedly until null)
 * 3. close() - Clean up resources
 * <p>
 * This model is simple, easy to implement, and works well for
 * our teaching purposes. Production systems often use more
 * sophisticated models (vectorization, code generation).
 */
public interface Iterator {
    /**
     * Initialize the operator. Called once before any next() calls.
     * This is where operators can:
     * - Open child iterators
     * - Allocate buffers
     * - Build hash tables (for hash joins)
     * - Perform any one-time setup
     */
    void open();

    /**
     * Get the next output tuple.
     *
     * @return The next tuple as an Object array, or null if no more tuples
     */
    Tuple next();

    /**
     * Clean up resources. Called once after all tuples have been consumed.
     * This is where operators can:
     * - Close child iterators
     * - Free buffers
     * - Release resources
     */
    void close();

    /**
     * Get a description of this operator (for debugging).
     */
    String describe();
}
