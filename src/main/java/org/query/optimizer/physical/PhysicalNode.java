package org.query.optimizer.physical;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for all physical plan nodes.
 * Physical plans represent the "how" of query execution,
 * with specific algorithms chosen (e.g., hash join vs nested loop).
 */
public abstract class PhysicalNode {
    // Annotations carry over from logical plan
    private final Map<String, Object> annotations = new HashMap<>();

    public abstract List<PhysicalNode> getChildren();

    /**
     * Get a human-readable description of this operator.
     */
    public abstract String describe();

    /**
     * Estimate the cost of executing this operator.
     * This should be implemented by concrete operators.
     */
    public abstract double estimateCost();

    /* --- Annotation methods --- */
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
        return val != null ? (Double) val : -1.0;
    }

    public void setAnnotation(String key, Object value) {
        annotations.put(key, value);
    }

    public Object getAnnotation(String key) {
        return annotations.get(key);
    }

    /* --- Pretty printing --- */

    public String toPrettyString() {
        StringBuilder sb = new StringBuilder();
        toPrettyString(sb, "", true);
        return sb.toString();
    }

    private void toPrettyString(StringBuilder sb, String prefix, boolean isTail) {
        sb.append(prefix).append(isTail ? "└── " : "├── ");
        sb.append(describe());

        if (getEstimatedRows() >= 0) {
            sb.append(" [rows=").append(getEstimatedRows());
            if (getEstimatedCost() >= 0) {
                sb.append(", cost=").append(String.format("%.2f", getEstimatedCost()));
            }
            sb.append("]");
        }

        sb.append("\n");

        List<PhysicalNode> children = getChildren();
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
