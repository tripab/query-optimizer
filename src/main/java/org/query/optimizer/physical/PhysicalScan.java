package org.query.optimizer.physical;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.TableMetadata;
import org.query.optimizer.catalog.Tuple;
import org.query.optimizer.executor.Iterator;

import java.util.Collections;
import java.util.List;

/**
 * Physical table scan operator.
 * <p>
 * Reads all rows from a table sequentially.
 * This is the leaf node in physical plans.
 */
public class PhysicalScan extends PhysicalNode implements Iterator {
    private final String tableName;
    private final Catalog catalog;

    // Execution state
    private TableMetadata table;
    private java.util.Iterator<Tuple> dataIterator;
    private boolean isOpen = false;
    private long rowsProcessed;

    public PhysicalScan(String tableName, Catalog catalog) {
        this.tableName = tableName;
        this.catalog = catalog;
    }

    public String getTableName() {
        return tableName;
    }

    @Override
    public List<PhysicalNode> getChildren() {
        return Collections.emptyList();
    }

    @Override
    public String describe() {
        return "PhysicalScan[" + tableName + "]";
    }

    @Override
    public double estimateCost() {
        if (getEstimatedCost() >= 0) {
            return getEstimatedCost();
        }
        // Simple estimate: cost is proportional to table size
        return getEstimatedRows() * 0.001;
    }

    // === Iterator implementation ===

    @Override
    public void open() {
        if (isOpen) {
            throw new IllegalStateException("Scan already open");
        }

        table = catalog.getTableMetadata(tableName);
        dataIterator = Tuple.convert(table.getSchema(), table.getData().iterator());
        rowsProcessed = 0;
        isOpen = true;
    }

    @Override
    public Tuple next() {
        if (!isOpen) {
            throw new IllegalStateException("Scan not open");
        }

        if (dataIterator.hasNext()) {
            rowsProcessed++;
            return dataIterator.next();
        }

        return null;
    }

    @Override
    public void close() {
        if (!isOpen) {
            return;
        }

        dataIterator = null;
        table = null;
        isOpen = false;
    }

    @Override
    public long rowsProcessed() {
        return rowsProcessed;
    }
}