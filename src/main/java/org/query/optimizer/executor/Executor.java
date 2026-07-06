package org.query.optimizer.executor;

import org.query.optimizer.catalog.Tuple;
import org.query.optimizer.physical.PhysicalNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Query Executor - executes physical plans and returns results.
 * <p>
 * The executor:
 * 1. Opens the physical plan (iterator model)
 * 2. Calls next() repeatedly to get result tuples
 * 3. Closes the plan when done
 * <p>
 * Also tracks execution statistics for analysis.
 */
public class Executor {

    /**
     * Execution result containing tuples and statistics.
     *
     * <p>{@code tuplesProcessed} is the total number of rows processed across
     * <em>all</em> operators in the plan (summed from
     * {@link Iterator#rowsProcessed()}), not the result-row count. It is a
     * deterministic, machine-independent proxy for how much work the plan did:
     * a nested-loop join contributes its |L| x |R| evaluated pairs, a hash join
     * its build+probe rows, streaming operators the rows they examined.
     */
    public record ExecutionResult(List<Tuple> tuples, long tuplesProcessed) {

        public int getResultCount() {
            return tuples.size();
        }

        @Override
        public String toString() {
            return String.format("ExecutionResult[%d tuples, %d processed]",
                    tuples.size(), tuplesProcessed);
        }
    }

    /**
     * Execute a physical plan and return all results.
     */
    public ExecutionResult execute(PhysicalNode plan) {
        if (!(plan instanceof Iterator iterator)) {
            throw new IllegalArgumentException("Physical plan must implement Iterator");
        }

        List<Tuple> results = new ArrayList<>();
        long tuplesProcessed;

        try {
            // Open the iterator
            iterator.open();

            // Pull all tuples
            Tuple tuple;
            while ((tuple = iterator.next()) != null) {
                results.add(tuple);
            }

            // Collect per-operator work before close() discards state
            tuplesProcessed = totalRowsProcessed(plan);
        } finally {
            // Always close, even if exception occurs
            iterator.close();
        }

        return new ExecutionResult(results, tuplesProcessed);
    }

    /**
     * Sums {@link Iterator#rowsProcessed()} over the whole operator tree.
     */
    private static long totalRowsProcessed(PhysicalNode node) {
        long total = node instanceof Iterator it ? it.rowsProcessed() : 0;
        for (PhysicalNode child : node.getChildren()) {
            total += totalRowsProcessed(child);
        }
        return total;
    }

    /**
     * Execute plan and print results.
     */
    public ExecutionResult executeAndPrint(PhysicalNode plan, List<String> columnNames) {
        ExecutionResult result = execute(plan);

        // Print header
        printHeader(columnNames);

        // Print rows
        for (var tuple : result.tuples()) {
            printRow(tuple);
        }

        // Print summary
        System.out.println();
        System.out.println(result.getResultCount() + " row(s) returned");

        return result;
    }

    /**
     * Execute plan with limit on result size.
     */
    public ExecutionResult executeWithLimit(PhysicalNode plan, int limit) {
        if (!(plan instanceof Iterator iterator)) {
            throw new IllegalArgumentException("Physical plan must implement Iterator");
        }

        List<Tuple> results = new ArrayList<>();
        long tuplesProcessed;

        try {
            iterator.open();

            Tuple tuple;
            while ((tuple = iterator.next()) != null && results.size() < limit) {
                results.add(tuple);
            }

            tuplesProcessed = totalRowsProcessed(plan);
        } finally {
            iterator.close();
        }

        return new ExecutionResult(results, tuplesProcessed);
    }

    /**
     * Print table header.
     */
    private void printHeader(List<String> columnNames) {
        System.out.println();

        // Print column names
        for (int i = 0; i < columnNames.size(); i++) {
            if (i > 0) System.out.print(" | ");
            System.out.printf("%-15s", columnNames.get(i));
        }
        System.out.println();

        // Print separator
        for (int i = 0; i < columnNames.size(); i++) {
            if (i > 0) System.out.print("-+-");
            System.out.print("---------------");
        }
        System.out.println();
    }

    /**
     * Print a single row.
     */
    private void printRow(Tuple tuples) {
        int i = 0;
        for (var column : tuples) {
            if (i++ > 0) System.out.print(" | ");
            String value = column != null ? column.toString() : "NULL";
            // Truncate long strings
            if (value.length() > 15) {
                value = value.substring(0, 12) + "...";
            }
            System.out.printf("%-15s", value);
        }
        System.out.println();
    }
}