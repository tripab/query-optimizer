package org.query.optimizer.parser;

import org.query.optimizer.logical.LogicalNode;

import java.util.Collections;
import java.util.List;

/**
 * Concrete logical operator implementations.
 * <p>
 * These represent the relational algebra operations:
 * - Scan: Read from a table
 * - Filter: Apply a predicate (WHERE)
 * - Project: Select specific columns
 * - Join: Combine two relations
 * - Aggregate: GROUP BY with aggregation functions
 */

/**
 * Scan a table (base relation).
 */
public class LogicalScan extends LogicalNode {
    private final String tableName;

    public LogicalScan(String tableName) {
        this.tableName = tableName;
    }

    public String getTableName() {
        return tableName;
    }

    @Override
    public List<LogicalNode> getChildren() {
        return Collections.emptyList();
    }

    @Override
    public LogicalNode withChildren(List<LogicalNode> children) {
        if (!children.isEmpty()) {
            throw new IllegalArgumentException("Scan cannot have children");
        }
        return this;
    }

    @Override
    public String describe() {
        return "Scan[" + tableName + "]";
    }
}




