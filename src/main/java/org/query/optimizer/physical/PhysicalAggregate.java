package org.query.optimizer.physical;

import org.query.optimizer.catalog.Attribute;
import org.query.optimizer.catalog.DataType;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.Tuple;
import org.query.optimizer.executor.Iterator;
import org.query.optimizer.parser.LogicalAggregate.AggFunction;
import org.query.optimizer.parser.LogicalAggregate.AggregateOp;
import org.query.optimizer.vectorized.AggregateAccumulator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Volcano/iterator aggregate operator (GROUP BY with COUNT / SUM / AVG / MIN / MAX).
 *
 * <p>Blocking operator: drains all input tuples before emitting any output.
 * Uses the same {@link AggregateAccumulator} implementations as
 * {@link org.query.optimizer.vectorized.VectorizedAggregate} so that both
 * engines share identical accumulation semantics.
 *
 * <h2>Output schema</h2>
 * Group-by columns appear first (types taken from input schema), followed by
 * aggregate output columns in declaration order.  Type inference rules:
 * <ul>
 *   <li>COUNT → INTEGER</li>
 *   <li>AVG   → FLOAT</li>
 *   <li>SUM / MIN / MAX → same type as the input column</li>
 * </ul>
 */
public class PhysicalAggregate extends PhysicalNode implements Iterator {

    private final PhysicalNode      child;
    private final List<String>      groupByColumns;
    private final List<AggregateOp> aggregateOps;
    private final Schema            inputSchema;
    private final Schema            outputSchema;

    // Resolved in open()
    private int[]       groupByColIndices;
    private int[]       aggInputColIndices;
    private DataType[]  aggInputTypes;

    // Execution state
    private Iterator                                                              childIterator;
    private Map<List<Object>, AggregateAccumulator[]>                            groups;
    private java.util.Iterator<Map.Entry<List<Object>, AggregateAccumulator[]>> emitIter;
    private boolean accumulated;
    private boolean isOpen;

    public PhysicalAggregate(PhysicalNode      child,
                              List<String>      groupByColumns,
                              List<AggregateOp> aggregateOps,
                              Schema            inputSchema) {
        this.child          = child;
        this.groupByColumns = List.copyOf(groupByColumns);
        this.aggregateOps   = List.copyOf(aggregateOps);
        this.inputSchema    = inputSchema;
        this.outputSchema   = buildOutputSchema(inputSchema);
    }

    public Schema getOutputSchema() {
        return outputSchema;
    }

    @Override
    public List<PhysicalNode> getChildren() {
        return List.of(child);
    }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder("PhysicalAggregate[");
        if (!groupByColumns.isEmpty()) {
            sb.append("GROUP BY: ").append(String.join(", ", groupByColumns));
            if (!aggregateOps.isEmpty()) sb.append("; ");
        }
        for (int i = 0; i < aggregateOps.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(aggregateOps.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public double estimateCost() {
        if (getEstimatedCost() >= 0) return getEstimatedCost();
        long childRows = child.getEstimatedRows();
        return child.estimateCost() + (childRows > 0 ? childRows * 0.00005 : 0);
    }

    // =========================================================================
    // Iterator
    // =========================================================================

    @Override
    public void open() {
        if (isOpen) throw new IllegalStateException("PhysicalAggregate already open");
        if (!(child instanceof Iterator)) throw new IllegalStateException("Child must implement Iterator");

        childIterator = (Iterator) child;

        // Resolve group-by column indices
        groupByColIndices = new int[groupByColumns.size()];
        for (int i = 0; i < groupByColumns.size(); i++) {
            groupByColIndices[i] = inputSchema.getColumnIndex(groupByColumns.get(i));
        }

        // Resolve aggregate input column indices and types
        aggInputColIndices = new int[aggregateOps.size()];
        aggInputTypes      = new DataType[aggregateOps.size()];
        for (int i = 0; i < aggregateOps.size(); i++) {
            AggregateOp op = aggregateOps.get(i);
            if (op.function() == AggFunction.COUNT && op.inputColumn().equals("*")) {
                aggInputColIndices[i] = 0;            // dummy — CountAccumulator ignores value
                aggInputTypes[i]      = DataType.INTEGER;
            } else {
                int idx = inputSchema.getColumnIndex(op.inputColumn());
                aggInputColIndices[i] = idx;
                aggInputTypes[i]      = inputSchema.getColumn(idx).type();
            }
        }

        groups      = new LinkedHashMap<>();
        accumulated = false;
        isOpen      = true;

        childIterator.open();
    }

    @Override
    public Tuple next() {
        if (!isOpen) throw new IllegalStateException("PhysicalAggregate not open");

        if (!accumulated) {
            accumulateAll();
            accumulated = true;
            emitIter    = groups.entrySet().iterator();
        }

        if (!emitIter.hasNext()) return null;

        Map.Entry<List<Object>, AggregateAccumulator[]> entry = emitIter.next();
        List<Object>           groupKey = entry.getKey();
        AggregateAccumulator[] accums   = entry.getValue();

        Tuple out = new Tuple();

        for (int g = 0; g < groupByColumns.size(); g++) {
            Schema.Column col = inputSchema.getColumn(groupByColumns.get(g));
            out.add(new Attribute(col, groupKey.get(g)));
        }

        for (int a = 0; a < aggregateOps.size(); a++) {
            AggregateOp   op     = aggregateOps.get(a);
            Schema.Column outCol = outputSchema.getColumn(op.outputColumn());
            out.add(new Attribute(outCol, accums[a].result()));
        }

        return out;
    }

    @Override
    public void close() {
        if (!isOpen) return;
        childIterator.close();
        childIterator = null;
        groups        = null;
        emitIter      = null;
        accumulated   = false;
        isOpen        = false;
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private void accumulateAll() {
        Tuple tuple;
        while ((tuple = childIterator.next()) != null) {
            List<Object> key = new ArrayList<>(groupByColIndices.length);
            for (int i : groupByColIndices) {
                key.add(tuple.find(inputSchema.getColumn(i)));
            }

            AggregateAccumulator[] accums = groups.computeIfAbsent(key, k -> {
                AggregateAccumulator[] arr = new AggregateAccumulator[aggregateOps.size()];
                for (int a = 0; a < aggregateOps.size(); a++) {
                    arr[a] = AggregateAccumulator.create(aggregateOps.get(a).function(),
                                                         aggInputTypes[a]);
                }
                return arr;
            });

            for (int a = 0; a < aggregateOps.size(); a++) {
                AggregateOp op    = aggregateOps.get(a);
                Object      value;
                if (op.function() == AggFunction.COUNT && op.inputColumn().equals("*")) {
                    value = Boolean.TRUE; // non-null sentinel — CountAccumulator always increments
                } else {
                    value = tuple.find(inputSchema.getColumn(aggInputColIndices[a]));
                }
                accums[a].update(value);
            }
        }
    }

    private Schema buildOutputSchema(Schema src) {
        List<Schema.Column> cols = new ArrayList<>();
        for (String colName : groupByColumns) {
            cols.add(src.getColumn(colName));
        }
        for (AggregateOp op : aggregateOps) {
            cols.add(new Schema.Column(op.outputColumn(),
                    AggregateAccumulator.resultType(op, src)));
        }
        return new Schema(cols);
    }
}
