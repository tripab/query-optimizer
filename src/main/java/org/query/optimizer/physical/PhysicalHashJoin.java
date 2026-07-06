package org.query.optimizer.physical;


import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.Tuple;
import org.query.optimizer.executor.Iterator;
import org.query.optimizer.logical.Expression;

import java.util.*;

/**
 * Physical hash join operator.
 * <p>
 * More efficient than nested loop join for equi-joins.
 * <p>
 * Algorithm:
 * 1. Build phase: Hash all tuples from build side (right) into hash table
 * 2. Probe phase: For each tuple from probe side (left), probe hash table
 * <p>
 * This is much faster when the build side fits in memory.
 */
public class PhysicalHashJoin extends PhysicalNode implements Iterator {
    private final PhysicalNode left;  // Probe side
    private final PhysicalNode right; // Build side
    private final Expression condition;
    private final Schema leftSchema;
    private final Schema rightSchema;

    // Execution state
    private Iterator leftIterator;
    private Iterator rightIterator;
    private Map<Object, List<Tuple>> hashTable;
    private Tuple currentLeftTuple;
    private java.util.Iterator<Tuple> currentMatches;
    private final Schema combinedSchema;
    private boolean isOpen = false;
    private Schema.Column buildColumn;
    private Schema.Column probeColumn;
    private long rowsProcessed;

    public PhysicalHashJoin(PhysicalNode left, PhysicalNode right,
                            Expression condition,
                            Schema leftSchema, Schema rightSchema) {
        this.left = left;
        this.right = right;
        this.condition = condition;
        this.leftSchema = leftSchema;
        this.rightSchema = rightSchema;
        this.combinedSchema = createCombinedSchema(leftSchema, rightSchema);

        // Extract join columns for hashing
        extractJoinColumns();
    }

    private static List<Tuple> apply(Object k) {
        return new ArrayList<>();
    }

    public PhysicalNode getLeft() {
        return left;
    }

    public PhysicalNode getRight() {
        return right;
    }

    public Expression getCondition() {
        return condition;
    }

    @Override
    public List<PhysicalNode> getChildren() {
        return Arrays.asList(left, right);
    }

    @Override
    public String describe() {
        return "PhysicalHashJoin[" + condition.toSQLString() + "]";
    }

    @Override
    public double estimateCost() {
        if (getEstimatedCost() >= 0) {
            return getEstimatedCost();
        }
        // Hash join cost: left_cost + right_cost + (left_rows + right_rows) * hash_cost
        double leftCost = left.estimateCost();
        double rightCost = right.estimateCost();
        long leftRows = left.getEstimatedRows();
        long rightRows = right.getEstimatedRows();

        return leftCost + rightCost + ((leftRows + rightRows) * 0.00003);
    }

    // === Iterator implementation ===

    @Override
    public void open() {
        if (isOpen) {
            throw new IllegalStateException("Join already open");
        }

        if (!(left instanceof Iterator) || !(right instanceof Iterator)) {
            throw new IllegalStateException("Children must be Iterators");
        }

        leftIterator = (Iterator) left;
        rightIterator = (Iterator) right;

        // Build phase: build hash table from right side
        rowsProcessed = 0;
        buildHashTable();

        // Probe phase: open left side
        leftIterator.open();
        currentLeftTuple = null;
        currentMatches = null;

        isOpen = true;
    }

    @Override
    public Tuple next() {
        if (!isOpen) {
            throw new IllegalStateException("Join not open");
        }

        while (true) {
            // If we have pending matches from current left tuple, return them
            if (currentMatches != null && currentMatches.hasNext()) {
                Tuple rightTuple = currentMatches.next();
                return combineTuples(currentLeftTuple, rightTuple);
            }

            // Get next left tuple
            currentLeftTuple = leftIterator.next();

            if (currentLeftTuple == null) {
                return null; // No more tuples
            }

            rowsProcessed++; // probe-side row examined

            // Probe hash table
            Object probeKey = currentLeftTuple.find(probeColumn);
            List<Tuple> matches = hashTable.get(probeKey);

            if (matches != null && !matches.isEmpty()) {
                currentMatches = matches.iterator();
                // Will return first match in next iteration
            }

            // If no matches, continue with next left tuple
        }
    }

    @Override
    public void close() {
        if (!isOpen) {
            return;
        }

        if (leftIterator != null) {
            leftIterator.close();
            leftIterator = null;
        }

        if (rightIterator != null) {
            rightIterator.close();
            rightIterator = null;
        }

        if (hashTable != null) {
            hashTable.clear();
            hashTable = null;
        }

        currentLeftTuple = null;
        currentMatches = null;
        isOpen = false;
    }

    @Override
    public long rowsProcessed() {
        return rowsProcessed;
    }

    /**
     * Build hash table from right side.
     */
    private void buildHashTable() {
        hashTable = new HashMap<>();

        rightIterator.open();

        Tuple tuple;
        while ((tuple = rightIterator.next()) != null) {
            rowsProcessed++; // build-side row inserted
            Object key = tuple.find(buildColumn);
            hashTable.computeIfAbsent(key, PhysicalHashJoin::apply).add(tuple);
        }

        rightIterator.close();
    }

    /**
     * Extract join column indices from condition.
     * Assumes condition is: left_column = right_column
     */
    private void extractJoinColumns() {
        // Simplified: assume condition is BinaryOp with ColumnRef on each side
        if (condition instanceof Expression.BinaryOp binOp) {
            if (binOp.left() instanceof Expression.ColumnRef leftCol &&
                    binOp.right() instanceof Expression.ColumnRef rightCol) {
                // Determine which column belongs to which side
                try {
                    probeColumn = leftSchema.getColumn(leftCol.columnName());
                    buildColumn = rightSchema.getColumn(rightCol.columnName());
                } catch (Exception e) {
                    // Try reversed
                    try {
                        probeColumn = leftSchema.getColumn(rightCol.columnName());
                        buildColumn = rightSchema.getColumn(leftCol.columnName());
                    } catch (Exception e2) {
                        // Default to first column
                        probeColumn = null;
                        buildColumn = null;
                    }
                }
            }
        }
    }

    /**
     * Combine left and right tuples.
     */
    private Tuple combineTuples(Tuple left, Tuple right) {
        Tuple combined = new Tuple();
        combined.addAll(left);
        combined.addAll(right);
        return combined;
    }

    /**
     * Create combined schema.
     */
    private Schema createCombinedSchema(Schema left, Schema right) {
        List<Schema.Column> columns = new ArrayList<>();
        columns.addAll(left.getColumns());
        columns.addAll(right.getColumns());
        return new Schema(columns);
    }
}