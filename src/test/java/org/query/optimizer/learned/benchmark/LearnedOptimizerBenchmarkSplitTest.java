package org.query.optimizer.learned.benchmark;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.learned.benchmark.LearnedOptimizerBenchmark.BenchmarkResults;
import org.query.optimizer.learned.benchmark.LearnedOptimizerBenchmark.Distribution;
import org.query.optimizer.learned.common.DataGenerator;
import org.query.optimizer.learned.common.WorkloadGenerator;
import org.query.optimizer.learned.common.WorkloadGenerator.ParsedQuery;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers task T6: separating reproducible decision-quality from machine-dependent
 * wall-clock in {@link LearnedOptimizerBenchmark}.
 */
class LearnedOptimizerBenchmarkSplitTest {

    static Catalog catalog;
    static List<ParsedQuery> workload;

    @BeforeAll
    static void setup() {
        catalog = new Catalog();
        DataGenerator.generate(catalog, 1);
        workload = new WorkloadGenerator(catalog, 11L).generateWorkload(8);
    }

    @Test
    void distributionComputesMedianIqrMinMax() {
        Distribution d = LearnedOptimizerBenchmark.distribution(new long[]{40, 10, 30, 20});
        // sorted 10,20,30,40 -> nearest-rank p25=10, p50=20, p75=30
        assertEquals(20, d.median());
        assertEquals(20, d.iqr());
        assertEquals(10, d.min());
        assertEquals(40, d.max());
    }

    @Test
    void distributionHandlesSingleSample() {
        Distribution d = LearnedOptimizerBenchmark.distribution(new long[]{7});
        assertEquals(7, d.median());
        assertEquals(0, d.iqr());
        assertEquals(7, d.min());
        assertEquals(7, d.max());
    }

    @Test
    void wallClockTotalsReturnOneSamplePerRepeat() {
        Map<String, long[]> samples =
                new LearnedOptimizerBenchmark(catalog).measureWallClockTotals(workload, 2);

        assertEquals(Set.of("DEFAULT", "ORACLE", "BAO", "LERO"), samples.keySet());
        for (long[] arr : samples.values()) {
            assertEquals(2, arr.length, "one total per repeat");
            for (long v : arr) assertTrue(v >= 0, "wall-clock total is non-negative");
        }
    }

    @Test
    void decisionsReproducibleAcrossRunsWithTheSameSeed() {
        BenchmarkResults a = new LearnedOptimizerBenchmark(catalog, 123L).run(workload);
        BenchmarkResults b = new LearnedOptimizerBenchmark(catalog, 123L).run(workload);

        assertEquals(a.perQuery().size(), b.perQuery().size());
        for (int i = 0; i < a.perQuery().size(); i++) {
            assertEquals(a.perQuery().get(i).oracleArm(), b.perQuery().get(i).oracleArm(),
                    "oracle's best-arm decision is deterministic");
            assertEquals(a.perQuery().get(i).baoArm(), b.perQuery().get(i).baoArm(),
                    "seeded Bao arm selection must reproduce");
        }
    }
}
