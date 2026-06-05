package org.query.optimizer;

import org.query.optimizer.catalog.CostModel;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalJoin;
import org.query.optimizer.parser.LogicalScan;

import java.util.*;

/**
 * Dynamic Programming Join Ordering (System R Style)
 * <p>
 * Finds the optimal join order for a set of tables using dynamic programming.
 * This is the algorithm used in IBM System R and most production databases.
 * <p>
 * Algorithm:
 * 1. Start with single-table plans (scans)
 * 2. Build up plans for larger sets by combining smaller sets
 * 3. For each subset, try all ways to split it into left/right
 * 4. Keep only the lowest-cost plan for each subset
 * <p>
 * Time Complexity: O(n * 2^n) for n tables
 * Space Complexity: O(2^n) for memoization
 * <p>
 * Practical limit: Works well for n ≤ 10 tables
 */
public class DPJoinOrderer {
    private final CostModel costModel;
    private final Map<Set<String>, PlanInfo> memo;
    private final int maxTables;

    record PlanInfo(LogicalNode plan, double cost, Set<String> tables) {
        @Override
        public String toString() {
            return String.format("PlanInfo[tables=%s, cost=%.2f]", tables, cost);
        }
    }

    /**
     * Represents a join condition between tables.
     */
    public record JoinCondition(String leftTable, String rightTable, Expression condition) {
        /**
         * Check if this condition connects the given table sets.
         */
        boolean connects(Set<String> left, Set<String> right) {
            return (left.contains(leftTable) && right.contains(rightTable)) ||
                    (left.contains(rightTable) && right.contains(leftTable));
        }
    }

    public DPJoinOrderer(CostModel costModel) {
        this(costModel, 10);
    }

    public DPJoinOrderer(CostModel costModel, int maxTables) {
        this.costModel = costModel;
        this.maxTables = maxTables;
        this.memo = new HashMap<>();
    }

    /**
     * Find the optimal join order for a set of join inputs.
     *
     * @param leaves     join inputs, each covering exactly one table; a leaf may be
     *                   a bare {@link LogicalScan} or a single-table subtree (e.g. a
     *                   pushed-down filter over a scan), which is preserved in the
     *                   reordered tree
     * @param conditions Join conditions between tables
     * @return Optimally ordered join tree
     */
    public LogicalNode findBestJoinOrder(List<? extends LogicalNode> leaves, List<JoinCondition> conditions) {
        if (leaves.isEmpty()) {
            throw new IllegalArgumentException("No tables to join!");
        }
        if (leaves.size() == 1) {
            return leaves.getFirst();
        }

        // check if the problem is too large
        if (leaves.size() > maxTables) {
            System.err.println("WARNING: Too many tables (" + leaves.size() +
                    ") for DP. Falling back to left-deep heuristic.");
            return buildLeftDeepTree(leaves, conditions);
        }

        memo.clear();

        // Phase 1: Initialize with single tables
        for (LogicalNode leaf : leaves) {
            Set<String> tableSet = Collections.singleton(tableNameOf(leaf));
            double cost = costModel.estimate(leaf);
            memo.put(tableSet, new PlanInfo(leaf, cost, tableSet));
        }
        // Phase 2: Build up larger subsets
        for (int size = 2; size <= leaves.size(); size++) {
            optimizeSubsetsOfSize(size, leaves, conditions);
        }
        // Phase 3: Return best plan for all tables
        Set<String> allTables = new HashSet<>();
        for (LogicalNode leaf : leaves) {
            allTables.add(tableNameOf(leaf));
        }
        PlanInfo best = memo.get(allTables);
        if (best == null) {
            throw new IllegalStateException("No valid join order found");
        }

        return best.plan();
    }

    /**
     * Returns the single table covered by a join-input leaf.
     */
    private String tableNameOf(LogicalNode leaf) {
        Set<String> tables = getTableNames(leaf);
        if (tables.size() != 1) {
            throw new IllegalArgumentException(
                    "Join leaf must cover exactly one table, got: " + tables);
        }
        return tables.iterator().next();
    }

    /**
     * Optimize all subsets of a given size.
     */
    private void optimizeSubsetsOfSize(int size, List<? extends LogicalNode> leaves, List<JoinCondition> conditions) {
        // generate all subsets of a given size
        List<Set<String>> subsets = generateSubsets(leaves, size);
        for (Set<String> subset : subsets) {
            PlanInfo best = findBestPlanForSubset(subset, conditions);
            if (best != null) {
                memo.put(subset, best);
            }
        }
    }

    /**
     * Find the best plan for a specific subset of tables.
     */
    private PlanInfo findBestPlanForSubset(Set<String> tables, List<JoinCondition> conditions) {
        PlanInfo best = null;
        // try all the ways to partition this subset into left and right
        for (Set<String> left : properSubsets(tables)) {
            Set<String> right = new HashSet<>(tables);
            right.removeAll(left);
            // skip if right is empty or if we already tried this split
            if (right.isEmpty() || left.size() > right.size()) {
                continue;
            }
            // get memoized plans for left and right
            PlanInfo leftPlan = memo.get(left);
            PlanInfo rightPlan = memo.get(right);
            if (leftPlan == null || rightPlan == null) {
                continue;
            }
            // find a join condition that connects left and right
            JoinCondition joinCondition = findJoinCondition(left, right, conditions);
            if (joinCondition != null) {
                // create join and estimate cost
                LogicalNode join = new LogicalJoin(
                        leftPlan.plan(),
                        rightPlan.plan(),
                        LogicalJoin.JoinType.INNER,
                        joinCondition.condition()
                );
                double cost = costModel.estimate(join);
                // keep if this is the best plan so far
                if (best == null || cost < best.cost()) {
                    best = new PlanInfo(join, cost, tables);
                }
            }
        }

        return best;
    }

    /**
     * Find a join condition that connects two table sets.
     */
    private JoinCondition findJoinCondition(Set<String> left, Set<String> right, List<JoinCondition> conditions) {
        for (JoinCondition condition : conditions) {
            if (condition.connects(left, right)) {
                return condition;
            }
        }
        return null;
    }

    /**
     * Generate all proper subsets of a set (excluding empty set and full set).
     */
    private Iterable<? extends Set<String>> properSubsets(Set<String> set) {
        var list = new ArrayList<>(set);
        List<Set<String>> result = new ArrayList<>();
        // generate all subsets using bit manipulation
        int totalSubsets = (1 << list.size());
        for (int i = 1; i < totalSubsets - 1; i++) { // skip empty set(0) and the whole set(2^n-1)
            Set<String> subset = new HashSet<>();
            for (int j = 0; j < list.size(); j++) {
                if ((i & (1 << j)) != 0) {
                    subset.add(list.get(j));
                }
            }
            result.add(subset);
        }

        return result;
    }

    /**
     * Generate all subsets of tables with a specific size.
     */
    private List<Set<String>> generateSubsets(List<? extends LogicalNode> leaves, int size) {
        var tables = leaves.stream()
                .map(this::tableNameOf)
                .toList();
        var result = new ArrayList<Set<String>>();
        generateSubsetsHelper(tables, size, 0, new HashSet<>(), result);
        return result;
    }

    private void generateSubsetsHelper(List<String> tables, int size,
                                       int start, Set<String> current, List<Set<String>> result) {
        if (current.size() == size) {
            result.add(new HashSet<>(current));
            return;
        }
        for (int i = start; i < tables.size(); i++) {
            current.add(tables.get(i));
            generateSubsetsHelper(tables, size, i + 1, current, result);
            current.remove(tables.get(i));
        }
    }

    /**
     * Fallback: Build a left-deep tree with heuristic ordering.
     * Used when DP is too expensive.
     */
    private LogicalNode buildLeftDeepTree(List<? extends LogicalNode> leaves, List<JoinCondition> conditions) {
        // simple heuristic: order by table size (smallest first)
        var sorted = new ArrayList<LogicalNode>(leaves);
        sorted.sort((a, b) -> {
            long cardinalityA = costModel.estimateCardinality(a);
            long cardinalityB = costModel.estimateCardinality(b);
            return Long.compare(cardinalityA, cardinalityB);
        });
        // build left-deep tree
        LogicalNode result = sorted.getFirst();
        for (int i = 1; i < sorted.size(); i++) {
            var right = sorted.get(i);
            // find applicable join condition
            var leftTables = getTableNames(result);
            var rightTables = Collections.singleton(tableNameOf(right));
            JoinCondition condition = findJoinCondition(leftTables, rightTables, conditions);
            if (condition != null) {
                result = new LogicalJoin(result, right, LogicalJoin.JoinType.INNER, condition.condition());
            }
        }
        return result;
    }

    /**
     * Get all table names in a plan subtree.
     */
    private Set<String> getTableNames(LogicalNode node) {
        var tables = new HashSet<String>();
        collectTableName(node, tables);
        return tables;
    }

    private void collectTableName(LogicalNode node, HashSet<String> tables) {
        if (node instanceof LogicalScan scan) {
            tables.add(scan.getTableName());
        }
        node.getChildren().forEach(child -> collectTableName(child, tables));
    }

    /**
     * Get statistics about the DP search.
     */
    public int getMemoCacheSize() {
        return memo.size();
    }

    public void printMemoStatistics() {
        System.out.println("DP Join Ordering Statistics:");
        System.out.println("  Memoized plans: " + memo.size());
        System.out.println("  Table subsets explored:");
        var sizeCount = new HashMap<Integer, Integer>();
        memo.keySet().forEach(tables ->
                sizeCount.put(tables.size(),
                        sizeCount.getOrDefault(tables.size(), 0) + 1));
        sizeCount.forEach((key, value) -> System.out.println("    Size " + key + ": " +
                value + " subsets"));
    }
}
