package org.query.optimizer.logical;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for all logical plan nodes.
 * Logical plans represent the "what" of a query, not the "how".
 * <p>
 * Key design: Nodes are annotated with estimated statistics
 * (cardinality, cost) that get filled in during optimization.
 */
public abstract class LogicalNode {
    private final Map<String, Object> annotations = new HashMap<>();

    public abstract List<LogicalNode> getChildren();

    public abstract LogicalNode withChildren(List<LogicalNode> children);

    public abstract String describe();

    /* --- Annotation methods for optimizer metadata --- */
    public void setEstimatedRows(long rows) {
        annotations.put("est_rows", rows);
    }

    public long getEstimatedRows() {
        Object val = annotations.get("est_rows");
        return val != null ? (Long) val : -1;
    }

    public void setEstimatedCost(double cost) {
        annotations.put("est_cost", cost);
    }

    public double getEstimatedCost() {
        Object val = annotations.get("est_cost");
        return val != null ? (Double) val : -1d;
    }

    public void setAnnotation(String key, Object value) {
        annotations.put(key, value);
    }

    public Object getAnnotation(String key) {
        return annotations.get(key);
    }

    public boolean hasAnnotation(String key) {
        return annotations.containsKey(key);
    }

    /* --- Pretty printing --- */

    /**
     * Generate a tree representation of the plan.
     */
    public String toPrettyString() {
        StringBuilder sb = new StringBuilder();
        toPrettyString(sb, "", true);
        return sb.toString();
    }

    private void toPrettyString(StringBuilder sb, String prefix, boolean isTail) {
        sb.append(prefix).append(isTail ? "└── " : "├── ");
        sb.append(describe());

        // Add annotations if present
        if (hasAnnotation("est_rows")) {
            sb.append(" [rows=").append(getEstimatedRows());
            if (hasAnnotation("est_cost")) {
                sb.append(", cost=").append(String.format("%.2f", getEstimatedCost()));
            }
            sb.append("]");
        }

        sb.append("\n");

        List<LogicalNode> children = getChildren();
        for (int i = 0; i < children.size(); i++) {
            boolean isLastChild = (i == children.size() - 1);
            children.get(i).toPrettyString(sb,
                    prefix + (isTail ? "    " : "│   "),
                    isLastChild);
        }
    }

    @Override
    public String toString() {
        return describe();
    }
}
