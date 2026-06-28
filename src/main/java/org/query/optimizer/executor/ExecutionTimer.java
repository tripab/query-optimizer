package org.query.optimizer.executor;

import java.util.function.Supplier;

/**
 * The single timing seam for the project. Wrap any work whose latency matters —
 * query execution, plan exploration — in {@link #run(Supplier)} to get the
 * result together with its measured {@link System#nanoTime()} duration.
 *
 * <p>Routing every measurement through one helper keeps all latencies on the
 * same monotonic, nanosecond-resolution clock. That matters for the
 * sub-millisecond in-memory queries in this project, where
 * {@code System.currentTimeMillis()} quantises to 0/1&nbsp;ms.
 */
public final class ExecutionTimer {

    private ExecutionTimer() {
    }

    /**
     * Runs {@code work} exactly once and returns its result paired with the
     * elapsed nanoseconds. Any exception thrown by {@code work} propagates
     * unchanged (the duration is not reported in that case).
     */
    public static <T> Timed<T> run(Supplier<T> work) {
        long start = System.nanoTime();
        T value = work.get();
        long elapsed = System.nanoTime() - start;
        return new Timed<>(value, elapsed);
    }
}
