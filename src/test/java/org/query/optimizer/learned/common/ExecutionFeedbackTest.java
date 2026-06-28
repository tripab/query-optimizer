package org.query.optimizer.learned.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionFeedbackTest {

    @Test
    void logicalCostBlendsTuplesAndLatency() {
        // 100 tuples * 0.01 + 5 ms = 6.0
        assertEquals(6.0, ExecutionFeedback.logicalCost(100, 5), 1e-9);
        assertEquals(0.0, ExecutionFeedback.logicalCost(0, 0), 1e-9);
    }

    @Test
    void logicalCostClampsNegativeLatency() {
        assertEquals(1.0, ExecutionFeedback.logicalCost(100, -3), 1e-9,
                "negative latency must not subtract from the cost");
    }

    @Test
    void instanceMethodMatchesStatic() {
        ExecutionFeedback fb = new ExecutionFeedback(
                "SELECT 1", HintSet.DEFAULT, new double[]{0.0}, 7, 250, 1.0, 10);
        assertEquals(ExecutionFeedback.logicalCost(250, 7), fb.logicalCost(), 1e-9);
    }
}
