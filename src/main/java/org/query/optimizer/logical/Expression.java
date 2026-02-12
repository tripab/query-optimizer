package org.query.optimizer.logical;

import org.query.optimizer.catalog.Schema;

import java.util.Map;

/**
 * Represents expressions used in queries (predicates, projections, etc.).
 * <p>
 * For this simplified optimizer, we support:
 * - Column references (e.g., "customers.id")
 * - Literals (integers, floats, strings)
 * - Binary operations (=, >, <, >=, <=, !=, AND, OR)
 * <p>
 * Complex expressions (arithmetic, functions, CASE) are deferred to phase 2.
 */
public interface Expression {
    /**
     * Evaluate this expression against a row.
     *
     * @param row    The row data
     * @param schema The schema defining column positions
     * @return The result of evaluation
     */
    Object evaluate(Map<Schema.Column, Object> row, Schema schema);

    String toSQLString();

    default String asString() {
        return toSQLString();
    }

    /* --- Column Reference --- */
    record ColumnRef(String tableName, String columnName) implements Expression {
        public static ColumnRef from(String columnName) {
            return new ColumnRef(null, columnName);
        }

        @Override
        public Object evaluate(Map<Schema.Column, Object> row, Schema schema) {
            return row.get(schema.getColumn(columnName));
        }

        public String getQualifiedName() {
            return tableName != null ? tableName + "." + columnName : columnName;
        }

        @Override
        public String toSQLString() {
            return getQualifiedName();
        }
    }

    /* --- Literal --- */
    record Literal<T extends Comparable<? super T>>(T value) implements Expression {

        @Override
        public Object evaluate(Map<Schema.Column, Object> row, Schema schema) {
            return value;
        }

        @Override
        public String toSQLString() {
            if (value == null) {
                return "NULL";
            } else if (value instanceof String) {
                return "'" + value + "'";
            } else {
                return value.toString();
            }
        }
    }

    /* --- Binary operation --- */
    record BinaryOp(Operator operator, Expression left, Expression right)
            implements Expression {
        public enum Operator {
            // Comparison
            EQ("="), NEQ("!="), GT(">"), GTE(">="), LT("<"), LTE("<="),
            // Logical
            AND("AND"), OR("OR");

            private final String sql;

            Operator(String sql) {
                this.sql = sql;
            }

            public String toSql() {
                return sql;
            }
        }

        @Override
        public Object evaluate(Map<Schema.Column, Object> row, Schema schema) {
            Object leftVal = left.evaluate(row, schema);
            Object rightVal = right.evaluate(row, schema);

            if (leftVal == null || rightVal == null) {
                return false;
            }

            switch (operator()) {
                case EQ -> {
                    return leftVal.equals(rightVal);
                }
                case NEQ -> {
                    return !leftVal.equals(rightVal);
                }
                case GT -> {
                    return compare(leftVal, rightVal) > 0;
                }
                case GTE -> {
                    return compare(leftVal, rightVal) >= 0;
                }
                case LT -> {
                    return compare(leftVal, rightVal) < 0;
                }
                case LTE -> {
                    return compare(leftVal, rightVal) <= 0;
                }
                case AND -> {
                    return (Boolean) leftVal && (Boolean) rightVal;
                }
                case OR -> {
                    return (Boolean) leftVal || (Boolean) rightVal;
                }
                default -> throw new UnsupportedOperationException("Unknown operator: " + operator);
            }
        }

        public static int compare(Object left, Object right) {
            if (left instanceof Comparable<?> compA &&
                    right != null &&
                    left.getClass() == right.getClass()) {

                return compareSameType(compA, right);
            }
            throw new IllegalArgumentException("Cannot compare: " + left + " and " + right);
        }

        private static <T> int compareSameType(Comparable<T> a, Object b) {
            return a.compareTo((T) b);
        }

        @Override
        public String toSQLString() {
            return "(" + left.toSQLString() + " " + operator.toSql() +
                    " " + right.toSQLString() + ")";
        }
    }
}
