package org.query.optimizer;

import org.query.optimizer.catalog.CostModel;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;

/**
 * Cost Calibrator - Measures actual hardware performance to calibrate cost model.
 * <p>
 * The cost model uses constants like PAGE_COST and TUPLE_COST, but what should
 * their values be? This class runs microbenchmarks to measure real performance
 * on the current hardware and derives appropriate cost constants.
 * <p>
 * Why calibration matters:
 * - Default costs are arbitrary (PAGE_COST=1.0, TUPLE_COST=0.01)
 * - Real hardware varies: SSD vs HDD, fast vs slow CPU
 * - Calibrated costs make plan costs meaningful (milliseconds, not arbitrary units)
 * - Better relative costs improve plan selection
 */
public class CostCalibrator {
    private static final int WARMUP_ITERATIONS = 100;
    private static final int BENCHMARK_ITERATIONS = 1000;

    private boolean verbose = false;

    public CostCalibrator() {
    }

    public CostCalibrator(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Run all calibration benchmarks and return calibrated config.
     */
    public CostModel.CostConfig calibrate() {
        if (verbose) {
            System.out.println("=== Cost Model Calibration ===");
            System.out.println("Running microbenchmarks to measure hardware performance...\n");
        }

        // Warmup JVM
        if (verbose) {
            System.out.println("Warming up JVM...");
        }
        warmup();

        // Run benchmarks
        double pageCost = calibratePageAccess();
        double tupleCost = calibrateTupleProcessing();
        double comparisonCost = calibrateComparison();
        double hashCost = calibrateHashing();

        // Create calibrated config
        CostModel.CostConfig config = new CostModel.CostConfig();
        config.PAGE_COST = pageCost;
        config.TUPLE_COST = tupleCost;
        config.COMPARISON_COST = comparisonCost;
        config.HASH_COST = hashCost;

        if (verbose) {
            System.out.println("\n=== Calibration Results ===");
            System.out.println("PAGE_COST:       " + String.format("%.6f", pageCost) + " ms");
            System.out.println("TUPLE_COST:      " + String.format("%.6f", tupleCost) + " ms");
            System.out.println("COMPARISON_COST: " + String.format("%.6f", comparisonCost) + " ms");
            System.out.println("HASH_COST:       " + String.format("%.6f", hashCost) + " ms");
            System.out.println("\nThese costs are in milliseconds and reflect your hardware performance.");
        }

        return config;
    }

    /**
     * Warmup to get JVM into steady state (JIT compilation, etc.)
     */
    private void warmup() {
        Random rand = new Random(42);
        long sum = 0;

        // Warmup: page access
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            byte[] page = new byte[8192];
            for (int j = 0; j < page.length; j += 64) {
                page[j] = (byte) rand.nextInt();
            }
            sum += page[0];
        }

        // Warmup: tuple processing
        Object[][] tuples = generateTestTuples(100);
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (Object[] tuple : tuples) {
                sum += (Integer) tuple[0];
            }
        }

        // Prevent dead code elimination
        if (sum == Long.MAX_VALUE) {
            System.out.println("Warmup complete");
        }
    }

    /**
     * Calibrate PAGE_COST: cost to access a page of data.
     * <p>
     * Simulates reading pages from memory (in-memory database scenario).
     * For disk-based systems, this would measure actual I/O.
     */
    private double calibratePageAccess() {
        if (verbose) {
            System.out.println("\nCalibrating PAGE_COST...");
        }

        final int PAGE_SIZE = 8192; // 8 KB pages
        final int NUM_PAGES = 100;
        Random rand = new Random(42);

        // Pre-allocate pages
        List<byte[]> pages = new ArrayList<>();
        for (int i = 0; i < NUM_PAGES; i++) {
            pages.add(new byte[PAGE_SIZE]);
        }

        long totalTime = 0;
        long operations = 0;

        // Benchmark: sequential page access
        for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
            long startTime = System.nanoTime();

            for (byte[] page : pages) {
                // Simulate page access by touching cache lines
                for (int j = 0; j < PAGE_SIZE; j += 64) {
                    page[j] = (byte) rand.nextInt();
                }
            }

            long endTime = System.nanoTime();
            totalTime += (endTime - startTime);
            operations += NUM_PAGES;
        }

        double avgTimeMs = (totalTime / 1_000_000.0) / operations;

        if (verbose) {
            System.out.println("  Average page access time: " +
                    String.format("%.6f", avgTimeMs) + " ms");
        }

        return avgTimeMs;
    }

    /**
     * Calibrate TUPLE_COST: cost to process (copy/materialize) a tuple.
     * <p>
     * Simulates the work done when passing tuples between operators.
     */
    private double calibrateTupleProcessing() {
        if (verbose) {
            System.out.println("\nCalibrating TUPLE_COST...");
        }

        final int NUM_TUPLES = 1000;
        Object[][] sourceTuples = generateTestTuples(NUM_TUPLES);
        Object[][] destTuples = new Object[NUM_TUPLES][4];

        long totalTime = 0;
        long operations = 0;

        // Benchmark: tuple copying
        for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
            long startTime = System.nanoTime();

            for (int i = 0; i < NUM_TUPLES; i++) {
                // Simulate tuple processing: copy and basic arithmetic
                Object[] src = sourceTuples[i];
                Object[] dest = destTuples[i];

                dest[0] = src[0];
                dest[1] = src[1];
                dest[2] = src[2];
                dest[3] = (Integer) src[0] + (Integer) src[1];
            }

            long endTime = System.nanoTime();
            totalTime += (endTime - startTime);
            operations += NUM_TUPLES;
        }

        double avgTimeMs = (totalTime / 1_000_000.0) / operations;

        if (verbose) {
            System.out.println("  Average tuple processing time: " +
                    String.format("%.6f", avgTimeMs) + " ms");
        }

        return avgTimeMs;
    }

    /**
     * Calibrate COMPARISON_COST: cost to compare two values.
     * <p>
     * Used for filter predicates and join conditions.
     */
    private double calibrateComparison() {
        if (verbose) {
            System.out.println("\nCalibrating COMPARISON_COST...");
        }

        final int NUM_VALUES = 1000;
        Integer[] values = new Integer[NUM_VALUES];
        Random rand = new Random(42);

        for (int i = 0; i < NUM_VALUES; i++) {
            values[i] = rand.nextInt(10000);
        }

        long totalTime = 0;
        long operations = 0;
        int matches = 0;

        // Benchmark: integer comparisons
        for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
            long startTime = System.nanoTime();

            for (int i = 0; i < NUM_VALUES; i++) {
                if (values[i] > 5000) {
                    matches++;
                }
            }

            long endTime = System.nanoTime();
            totalTime += (endTime - startTime);
            operations += NUM_VALUES;
        }

        double avgTimeMs = (totalTime / 1_000_000.0) / operations;

        if (verbose) {
            System.out.println("  Average comparison time: " +
                    String.format("%.6f", avgTimeMs) + " ms");
            System.out.println("  (Matches: " + matches + " to prevent optimization)");
        }

        return avgTimeMs;
    }

    /**
     * Calibrate HASH_COST: cost to hash a value.
     * <p>
     * Used for hash joins and hash aggregation.
     */
    private double calibrateHashing() {
        if (verbose) {
            System.out.println("\nCalibrating HASH_COST...");
        }

        final int NUM_VALUES = 1000;
        Object[][] tuples = generateTestTuples(NUM_VALUES);

        long totalTime = 0;
        long operations = 0;
        long hashSum = 0;

        // Benchmark: hashing tuples
        for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
            long startTime = System.nanoTime();

            for (Object[] tuple : tuples) {
                // Hash the first column (typical for joins)
                hashSum += tuple[0].hashCode();
            }

            long endTime = System.nanoTime();
            totalTime += (endTime - startTime);
            operations += NUM_VALUES;
        }

        double avgTimeMs = (totalTime / 1_000_000.0) / operations;

        if (verbose) {
            System.out.println("  Average hash time: " +
                    String.format("%.6f", avgTimeMs) + " ms");
            System.out.println("  (Hash sum: " + hashSum + " to prevent optimization)");
        }

        return avgTimeMs;
    }

    /**
     * Generate test tuples for benchmarks.
     */
    private Object[][] generateTestTuples(int count) {
        Random rand = new Random(42);
        Object[][] tuples = new Object[count][4];

        for (int i = 0; i < count; i++) {
            tuples[i][0] = rand.nextInt(1000);      // id
            tuples[i][1] = rand.nextInt(100);       // value1
            tuples[i][2] = rand.nextFloat() * 100;  // value2
            tuples[i][3] = "String_" + i;           // name
        }

        return tuples;
    }

    /**
     * Save calibration results to a properties file.
     */
    public void saveCalibration(CostModel.CostConfig config, String filename)
            throws IOException {
        Properties props = new Properties();
        props.setProperty("PAGE_COST", String.valueOf(config.PAGE_COST));
        props.setProperty("TUPLE_COST", String.valueOf(config.TUPLE_COST));
        props.setProperty("PAGE_SIZE", String.valueOf(config.PAGE_SIZE));
        props.setProperty("COMPARISON_COST", String.valueOf(config.COMPARISON_COST));
        props.setProperty("HASH_COST", String.valueOf(config.HASH_COST));

        try (FileOutputStream fos = new FileOutputStream(filename)) {
            props.store(fos, "Cost Model Calibration - Generated by CostCalibrator");
        }

        if (verbose) {
            System.out.println("\nCalibration saved to: " + filename);
        }
    }

    /**
     * Load calibration results from a properties file.
     */
    public CostModel.CostConfig loadCalibration(String filename) throws IOException {
        Properties props = new Properties();

        try (FileInputStream fis = new FileInputStream(filename)) {
            props.load(fis);
        }

        CostModel.CostConfig config = new CostModel.CostConfig();
        config.PAGE_COST = Double.parseDouble(props.getProperty("PAGE_COST"));
        config.TUPLE_COST = Double.parseDouble(props.getProperty("TUPLE_COST"));
        config.PAGE_SIZE = Integer.parseInt(props.getProperty("PAGE_SIZE"));
        config.COMPARISON_COST = Double.parseDouble(props.getProperty("COMPARISON_COST"));
        config.HASH_COST = Double.parseDouble(props.getProperty("HASH_COST"));

        if (verbose) {
            System.out.println("Calibration loaded from: " + filename);
        }

        return config;
    }

    /**
     * Compare two cost configurations.
     */
    public static void compareConfigs(CostModel.CostConfig config1, String name1,
                                      CostModel.CostConfig config2, String name2) {
        System.out.println("\n=== Cost Configuration Comparison ===");
        System.out.println();
        System.out.printf("%-20s | %-15s | %-15s | Ratio\n", "Parameter", name1, name2);
        System.out.println("---------------------|-----------------|-----------------|-------");

        compareParam("PAGE_COST", config1.PAGE_COST, config2.PAGE_COST);
        compareParam("TUPLE_COST", config1.TUPLE_COST, config2.TUPLE_COST);
        compareParam("COMPARISON_COST", config1.COMPARISON_COST, config2.COMPARISON_COST);
        compareParam("HASH_COST", config1.HASH_COST, config2.HASH_COST);
    }

    private static void compareParam(String name, double val1, double val2) {
        double ratio = val1 / val2;
        System.out.printf("%-20s | %15.6f | %15.6f | %.2fx\n",
                name, val1, val2, ratio);
    }

    /**
     * Get system information for calibration context.
     */
    public static void printSystemInfo() {
        System.out.println("=== System Information ===");
        System.out.println("OS:            " + System.getProperty("os.name") +
                " " + System.getProperty("os.version"));
        System.out.println("Java Version:  " + System.getProperty("java.version"));
        System.out.println("JVM:           " + System.getProperty("java.vm.name") +
                " " + System.getProperty("java.vm.version"));
        System.out.println("Processors:    " + Runtime.getRuntime().availableProcessors());
        System.out.println("Max Memory:    " +
                (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");
        System.out.println();
    }
}