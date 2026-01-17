package org.query.optimizer.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Abstract Syntax Tree (AST) node hierarchy for SQL queries.
 * <p>
 * Supports our restricted SQL syntax:
 * - SELECT column1, column2, aggregate(column)
 * - FROM table1 JOIN table2 ON condition
 * - WHERE condition
 * - GROUP BY columns
 */
public interface AST {
    /**
     * Root node for a SELECT statement.
     */
    record SelectStmt(List<SelectItem> selectItems, FromClause from,
                      Expr whereClause, List<String> groupByColumns) implements AST {
        public boolean hasWhere() {
            return whereClause != null;
        }

        public boolean hasGroupBy() {
            return !groupByColumns.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("SELECT ");
            for (int i = 0; i < selectItems.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(selectItems.get(i));
            }
            sb.append(" ").append(from);
            if (whereClause != null) {
                sb.append(" WHERE ").append(whereClause);
            }
            if (!groupByColumns.isEmpty()) {
                sb.append(" GROUP BY ").append(String.join(", ", groupByColumns));
            }
            return sb.toString();
        }
    }

    /**
     * Item in the SELECT list (column or aggregate).
     */
    interface SelectItem extends AST {
        String getAlias();
    }

    /**
     * Simple column reference in SELECT.
     */
    record ColumnSelectItem(String tableName, String columnName) implements SelectItem {
        public static ColumnSelectItem from(String columnName) {
            return new ColumnSelectItem(null, columnName);
        }

        @Override
        public String getAlias() {
            return columnName;
        }

        @Override
        public String toString() {
            return tableName != null ? tableName + "." + columnName : columnName;
        }
    }

    /**
     * Aggregate function in SELECT (COUNT, SUM, AVG, etc.).
     */
    record AggregateSelectItem(AggFunc function, String columnName) implements SelectItem {
        public enum AggFunc {COUNT, SUM, AVG, MIN, MAX}

        @Override
        public String getAlias() {
            return function.name().toLowerCase() + "_" + columnName;
        }

        @Override
        public String toString() {
            return function.name() + "(" + columnName + ")";
        }
    }

    /**
     * FROM clause (table or joins).
     */
    interface FromClause extends AST {
        List<String> getTableNames();
    }

    /**
     * Single table in FROM.
     */
    record TableRef(String tableName, String alias) implements FromClause {
        public String getEffectiveName() {
            return alias != null ? alias : tableName;
        }

        @Override
        public List<String> getTableNames() {
            return Collections.singletonList(tableName);
        }

        @Override
        public String toString() {
            return alias != null ? tableName + " " + alias : tableName;
        }
    }

    /**
     * Join in FROM clause.
     */
    record JoinClause(FromClause left, FromClause right,
                      JoinType joinType, Expr condition) implements FromClause {
        public enum JoinType {INNER}

        @Override
        public List<String> getTableNames() {
            List<String> names = new ArrayList<>();
            names.addAll(left.getTableNames());
            names.addAll(right.getTableNames());
            return names;
        }

        @Override
        public String toString() {
            return left + " INNER JOIN " + right + " ON " + condition;
        }
    }

    /**
     * Expression (predicates, column references, etc.).
     */
    interface Expr extends AST {
    }

    /**
     * Column reference in an expression.
     */
    record ColumnExpr(String tableName, String columnName) implements Expr {
        public static Expr from(String columnName) {
            return new ColumnExpr(null, columnName);
        }

        @Override
        public String toString() {
            return tableName != null ? tableName + "." + columnName : columnName;
        }
    }

    /**
     * Literal value in an expression.
     */
    record LiteralExpr(Object value) implements Expr {
        @Override
        public String toString() {
            if (value instanceof String) {
                return "'" + value + "'";
            }
            return String.valueOf(value);
        }
    }

    /**
     * Binary operation (comparison or logical).
     */
    record BinaryExpr(Op operator, Expr left, Expr right) implements Expr {
        public enum Op {
            EQ("="), NEQ("!="), GT(">"), GTE(">="), LT("<"), LTE("<="),
            AND("AND"), OR("OR");

            private final String sql;

            Op(String sql) {
                this.sql = sql;
            }

            public String toSql() {
                return sql;
            }
        }

        @Override
        public String toString() {
            return "(" + left + " " + operator.toSql() + " " + right + ")";
        }
    }
}
