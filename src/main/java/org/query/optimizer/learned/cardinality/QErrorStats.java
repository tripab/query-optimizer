package org.query.optimizer.learned.cardinality;

import org.query.optimizer.CardinalityModel;
import org.query.optimizer.learned.cardinality.CardinalityTrainingData.Example;

import java.util.Arrays;
import java.util.List;

/**
 * Q-error evaluation harness for cardinality models.
 *
 * <p><b>Q-error</b> is the standard metric for cardinality estimation:
 * {@code max(predicted/actual, actual/predicted)}, with a floor of 1 on both
 * sides to avoid division by zero. It is symmetric and multiplicative — a q-error
 * of 1.0 is a perfect estimate, 2.0 means off by 2x in either direction. Because
 * it is scale-free it is comparable across queries of wildly different sizes.
 */
public final class QErrorStats {

    private QErrorStats() {
    }

    /** Q-error of a single estimate. */
    public static double qError(long predicted, long actual) {
        double p = Math.max(1, predicted);
        double a = Math.max(1, actual);
        return Math.max(p / a, a / p);
    }

    /**
     * Aggregate q-error statistics over a set of examples.
     *
     * @param count  number of examples evaluated
     * @param mean   arithmetic mean q-error
     * @param median 50th-percentile q-error
     * @param p90    90th-percentile q-error
     * @param max    worst-case q-error
     */
    public record Summary(int count, double mean, double median, double p90, double max) {
        @Override
        public String toString() {
            return String.format("q-error[n=%d, mean=%.2f, median=%.2f, p90=%.2f, max=%.2f]",
                    count, mean, median, p90, max);
        }
    }

    /**
     * Evaluates {@code model} against the ground-truth cardinalities in
     * {@code examples} and returns aggregate q-error statistics.
     */
    public static Summary evaluate(CardinalityModel model, List<Example> examples) {
        if (examples.isEmpty()) {
            throw new IllegalArgumentException("Cannot evaluate on an empty example set");
        }
        double[] errors = new double[examples.size()];
        double sum = 0.0;
        for (int i = 0; i < examples.size(); i++) {
            Example example = examples.get(i);
            errors[i] = qError(model.estimate(example.subplan()), example.actualCardinality());
            sum += errors[i];
        }
        Arrays.sort(errors);
        return new Summary(
                errors.length,
                sum / errors.length,
                percentile(errors, 50),
                percentile(errors, 90),
                errors[errors.length - 1]);
    }

    /** Nearest-rank percentile over a pre-sorted ascending array. */
    private static double percentile(double[] sorted, int percentile) {
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.length);
        int index = Math.min(Math.max(rank - 1, 0), sorted.length - 1);
        return sorted[index];
    }
}
