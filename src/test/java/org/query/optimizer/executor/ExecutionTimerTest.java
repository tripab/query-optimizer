package org.query.optimizer.executor;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionTimerTest {

    @Test
    void returnsSupplierValueAndRunsWorkExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        Timed<String> timed = ExecutionTimer.run(() -> {
            calls.incrementAndGet();
            return "result";
        });

        assertEquals("result", timed.value());
        assertEquals(1, calls.get(), "work should run exactly once");
        assertTrue(timed.nanos() >= 0, "elapsed nanos must be non-negative");
    }

    @Test
    void measuresElapsedTimeOfTheWork() {
        long sleepMs = 20;
        Timed<Long> timed = ExecutionTimer.run(() -> {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 42L;
        });

        assertEquals(42L, timed.value());
        // Must capture at least most of the sleep (allow scheduler slack).
        assertTrue(timed.nanos() >= (sleepMs - 5) * 1_000_000L,
                "expected >= ~" + (sleepMs - 5) + "ms, got " + timed.millis() + "ms");
        assertEquals(timed.nanos() / 1_000_000L, timed.millis(),
                "millis() should truncate nanos");
    }

    @Test
    void propagatesExceptionsFromWork() {
        assertThrows(IllegalStateException.class, () ->
                ExecutionTimer.run(() -> {
                    throw new IllegalStateException("boom");
                }));
    }
}
