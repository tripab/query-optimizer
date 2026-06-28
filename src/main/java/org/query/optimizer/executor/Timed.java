package org.query.optimizer.executor;

/**
 * Pairs a computed value with the wall-clock time it took to produce, in
 * nanoseconds.
 *
 * <p>This keeps timing out of the value itself (e.g.
 * {@link Executor.ExecutionResult}) so that execution and measurement remain
 * separate concerns: the executor returns rows, the caller decides whether it
 * cares how long they took.
 *
 * @param value the result of the timed work
 * @param nanos elapsed wall-clock time, from {@link System#nanoTime()}
 * @param <T>   the type of the produced value
 */
public record Timed<T>(T value, long nanos) {

    /** Elapsed time in whole milliseconds (truncated toward zero). */
    public long millis() {
        return nanos / 1_000_000L;
    }
}
