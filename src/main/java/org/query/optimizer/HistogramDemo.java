package org.query.optimizer;

import org.query.optimizer.catalog.*;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.parser.LogicalFilter;
import org.query.optimizer.parser.LogicalScan;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Random;

/**
 * Demonstration of Histogram-based selectivity estimation.
 * <p>
 * Shows:
 * 1. Histogram construction from data
 * 2. Selectivity estimation for equality predicates
 * 3. Selectivity estimation for range predicates (>, <, BETWEEN)
 * 4. Comparison: histogram vs. NDV-only estimates
 * 5. Impact on query cost estimation
 */
public class HistogramDemo {

    static void main() throws IOException {
        System.out.println("=== Histogram Selectivity Estimation Demo ===\n");

        // Setup data with interesting distribution
        createSkewedData();
        Catalog catalog = new Catalog();
        catalog.loadTableFromCSV("sales",
                "target/generated-resources/sales.csv");

        TableMetadata table = catalog.getTableMetadata("sales");

        // Demo 1: Show histogram structure
        System.out.println("=== Demo 1: Histogram Structure ===");
        demoHistogramStructure(table);

        // Demo 2: Equality predicate estimation
        System.out.println("\n=== Demo 2: Equality Predicates ===");
        demoEqualityEstimation(table);

        // Demo 3: Range predicate estimation
        System.out.println("\n=== Demo 3: Range Predicates ===");
        demoRangeEstimation(table);

        // Demo 4: Comparison with NDV-only
        System.out.println("\n=== Demo 4: Histogram vs NDV Comparison ===");
        demoComparison(table);

        // Demo 5: Impact on query optimization
        System.out.println("\n=== Demo 5: Impact on Query Cost ===");
        demoQueryImpact(catalog);

        deleteSampleData();
        System.out.println("\n=== Histogram Demo Complete ===");
    }

    private static void demoHistogramStructure(TableMetadata table) {
        Histogram<?> amountHist = table.getHistogram("amount");

        if (amountHist != null) {
            System.out.println("Histogram for 'amount' column:");
            amountHist.print();
        } else {
            System.out.println("No histogram available for 'amount'");
        }

        System.out.println("\nObservations:");
        System.out.println("- Equi-depth: Each bucket has approximately equal rows");
        System.out.println("- Bucket bounds show data distribution");
        System.out.println("- Distinct count per bucket for better estimates");
    }

    private static void demoEqualityEstimation(TableMetadata table) {
        Histogram<Integer> amountHist = (Histogram<Integer>) table.getHistogram("amount");
        ColumnStats amountStats = table.getColumnStats("amount");

        // Test values
        int[] testValues = {50, 100, 500, 1000, 5000};

        System.out.println("Estimating selectivity for: amount = value\n");
        System.out.println("Value  | Histogram Est | NDV-Only Est  | Difference");
        System.out.println("-------|---------------|---------------|------------");

        for (int value : testValues) {
            double histEst = amountHist != null ?
                    amountHist.estimateEquality(value) : 0.0;
            double ndvEst = amountStats != null && amountStats.numDistinctValues() > 0 ?
                    1.0 / amountStats.numDistinctValues() : 0.1;

            System.out.printf("%5d  | %12.4f  | %12.4f  | %+.4f\n",
                    value, histEst, ndvEst, histEst - ndvEst);
        }

        System.out.println("\nObservation:");
        System.out.println("Histogram provides value-specific estimates based on actual distribution.");
    }

    private static void demoRangeEstimation(TableMetadata table) {
        Histogram<Integer> amountHist = (Histogram<Integer>) table.getHistogram("amount");

        if (amountHist == null) {
            System.out.println("No histogram available");
            return;
        }

        System.out.println("Range predicate estimates:\n");

        // Test: amount > value
        System.out.println("Predicate: amount > value");
        System.out.println("Value  | Histogram Est | Default (0.33)| Improvement");
        System.out.println("-------|---------------|---------------|------------");

        int[] testValues = {100, 500, 1000, 2000, 5000};
        for (int value : testValues) {
            double histEst = amountHist.estimateGreaterThan(value, false);
            double defaultEst = 0.33;

            System.out.printf("%5d  | %12.4f  | %12.4f  | %.2fx\n",
                    value, histEst, defaultEst,
                    Math.abs(histEst / defaultEst));
        }

        System.out.println("\n\nPredicate: amount < value");
        System.out.println("Value  | Histogram Est | Default (0.33)| Improvement");
        System.out.println("-------|---------------|---------------|------------");

        for (int value : testValues) {
            double histEst = amountHist.estimateLessThan(value, false);
            double defaultEst = 0.33;

            System.out.printf("%5d  | %12.4f  | %12.4f  | %.2fx\n",
                    value, histEst, defaultEst,
                    Math.abs(histEst / defaultEst));
        }

        System.out.println("\n\nPredicate: amount BETWEEN 1000 AND 3000");
        double rangeEst = amountHist.estimateRange(1000, 3000);
        System.out.printf("Histogram estimate: %.4f (%.1f%% of data)\n",
                rangeEst, rangeEst * 100);
    }

    private static void demoComparison(TableMetadata table) {
        System.out.println("Comparing estimation accuracy for skewed data:\n");

        Histogram<Integer> hist = (Histogram<Integer>) table.getHistogram("amount");
        // ColumnStats stats = table.getColumnStats("amount");

        // The data is skewed: many small values, few large values
        // Histogram should recognize this

        System.out.println("Query: SELECT * FROM sales WHERE amount > 2000");
        System.out.println();

        double histSelectivity = hist != null ?
                hist.estimateGreaterThan(2000, false) : 0.33;
        double ndvSelectivity = 0.33; // Default without histogram

        long totalRows = table.getRowCount();
        long histEstRows = (long) (totalRows * histSelectivity);
        long ndvEstRows = (long) (totalRows * ndvSelectivity);

        // Count actual rows > 2000
        long actualRows = countRowsWhere(table, 2000, true);

        System.out.println("Total rows:        " + totalRows);
        System.out.println("Actual rows:       " + actualRows +
                " (" + String.format("%.1f%%", actualRows * 100.0 / totalRows) + ")");
        System.out.println();
        System.out.println("Histogram estimate: " + histEstRows + " rows" +
                " (error: " + Math.abs(histEstRows - actualRows) + ")");
        System.out.println("NDV-only estimate:  " + ndvEstRows + " rows" +
                " (error: " + Math.abs(ndvEstRows - actualRows) + ")");
        System.out.println();

        double histError = Math.abs(histEstRows - actualRows) / (double) actualRows;
        double ndvError = Math.abs(ndvEstRows - actualRows) / (double) actualRows;

        System.out.printf("Histogram error: %.1f%%\n", histError * 100);
        System.out.printf("NDV-only error:  %.1f%%\n", ndvError * 100);
        System.out.printf("Improvement:     %.1fx better\n", ndvError / histError);
    }

    private static void demoQueryImpact(Catalog catalog) throws IOException {
        CostModel costModelWithHist = new SimpleCostModel(catalog);

        // Create a temporary catalog without histograms for comparison
        Catalog catalogNoHist = new Catalog();
        catalogNoHist.loadTableFromCSV("sales",
                "target/generated-resources/sales.csv");
        // Remove histograms
        TableMetadata table = catalogNoHist.getTableMetadata("sales");
        // Histograms are already built, but we can simulate by using NDV-only

        // Build a simple query plan
        LogicalScan scan = new LogicalScan("sales");
        Expression predicate = new Expression.BinaryOp(
                Expression.BinaryOp.Operator.GT,
                new Expression.ColumnRef("sales", "amount"),
                new Expression.Literal<>(2000)
        );
        LogicalFilter filter = new LogicalFilter(predicate, scan);

        // Estimate with histogram
        long cardWithHist = costModelWithHist.estimateCardinality(filter);
        double costWithHist = costModelWithHist.estimate(filter);

        System.out.println("Query: SELECT * FROM sales WHERE amount > 2000");
        System.out.println();
        System.out.println("With Histogram:");
        System.out.println("  Estimated rows: " + cardWithHist);
        System.out.println("  Estimated cost: " + String.format("%.2f", costWithHist));
        System.out.println();
        System.out.println("Impact: More accurate cardinality estimates lead to:");
        System.out.println("  - Better join order decisions");
        System.out.println("  - Correct memory allocation");
        System.out.println("  - Appropriate algorithm selection");
    }

    private static long countRowsWhere(TableMetadata table, int threshold, boolean greater) {
        long count = 0;
        var amountColumn = table.getSchema().getColumn("amount");

        for (Map<Schema.Column, Object> row : table.getData()) {
            Integer amount = (Integer) row.get(amountColumn);
            if (amount != null) {
                if (greater && amount > threshold) {
                    count++;
                } else if (!greater && amount <= threshold) {
                    count++;
                }
            }
        }

        return count;
    }

    private static void createSkewedData() throws IOException {
        File outputDir = new File("target/generated-resources");
        outputDir.mkdirs();

        // Create data with skewed distribution
        // Many small amounts, few large amounts (realistic for sales data)
        Random rand = new Random(42);

        try (PrintWriter pw = new PrintWriter(new File(outputDir, "sales.csv"))) {
            pw.println("id:INTEGER,amount:INTEGER,category:VARCHAR");

            int id = 1;

            // 70% small amounts (0-1000)
            for (int i = 0; i < 70; i++) {
                int amount = rand.nextInt(1000);
                pw.println(id++ + "," + amount + ",Small");
            }

            // 20% medium amounts (1000-3000)
            for (int i = 0; i < 20; i++) {
                int amount = 1000 + rand.nextInt(2000);
                pw.println(id++ + "," + amount + ",Medium");
            }

            // 10% large amounts (3000-10000)
            for (int i = 0; i < 10; i++) {
                int amount = 3000 + rand.nextInt(7000);
                pw.println(id++ + "," + amount + ",Large");
            }
        }
    }

    private static void deleteSampleData() throws IOException {
        Files.delete(Paths.get("target/generated-resources/sales.csv"));
    }
}