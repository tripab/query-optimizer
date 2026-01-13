package org.query.optimizer.catalog;

public enum DataType {
    INTEGER,
    FLOAT,
    VARCHAR;

    public Object parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        switch (this) {
            case INTEGER -> {
                return Integer.parseInt(value.trim());
            }
            case FLOAT -> {
                return Float.parseFloat(value.trim());
            }
            case VARCHAR -> {
                return value.trim();
            }
            default -> throw new IllegalStateException("Unknown type: " + this);
        }
    }

    public Object getDefaultValue() {
        switch (this) {
            case INTEGER -> {
                return 0;
            }
            case FLOAT -> {
                return 0f;
            }
            case VARCHAR -> {
                return "";
            }
            default -> throw new IllegalStateException("Unknown type: " + this);
        }
    }
}
