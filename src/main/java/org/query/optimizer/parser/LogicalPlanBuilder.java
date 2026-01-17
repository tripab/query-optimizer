package org.query.optimizer.parser;


import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts AST to logical plan using visitor pattern.
 * <p>
 * Key responsibilities:
 * 1. Convert AST expressions to Expression objects
 * 2. Build logical operator tree
 * 3. Enforce canonical form:
 * - Separate Filter nodes for each predicate (split ANDs)
 * - Normalized join conditions
 * <p>
 * This class needs access to the Catalog to resolve table/column references.
 */
public class LogicalPlanBuilder {
    private final Catalog catalog;

    public LogicalPlanBuilder(Catalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Build a logical plan from an AST.
     */
    public LogicalNode build(AST.SelectStmt stmt) {
        // Step 1: Build FROM clause (scans and joins)
        LogicalNode plan = buildFrom(stmt.from());

        // Step 2: Add WHERE predicates (as separate Filter nodes - canonical form)
        if (stmt.hasWhere()) {
            plan = buildFilters(stmt.whereClause(), plan);
        }

        // Step 3: Add aggregation if present
        if (stmt.hasGroupBy() || hasAggregates(stmt.selectItems())) {
            plan = buildAggregate(stmt, plan);
        }

        // Step 4: Add projection for SELECT list
        plan = buildProject(stmt.selectItems(), plan);

        return plan;
    }

    /**
     * Build the FROM clause (scans and joins).
     */
    private LogicalNode buildFrom(AST.FromClause from) {
        if (from instanceof AST.TableRef tableRef) {
            return new LogicalScan(tableRef.tableName());
        } else if (from instanceof AST.JoinClause join) {
            LogicalNode left = buildFrom(join.left());
            LogicalNode right = buildFrom(join.right());
            Expression condition = convertExpression(join.condition());
            return new LogicalJoin(left, right, LogicalJoin.JoinType.INNER, condition);
        } else {
            throw new IllegalArgumentException("Unknown FROM clause type: " + from.getClass());
        }
    }

    /**
     * Build Filter nodes from WHERE predicate.
     * IMPORTANT: Split AND predicates into separate Filter nodes (canonical form).
     */
    private LogicalNode buildFilters(AST.Expr whereExpr, LogicalNode child) {
        List<AST.Expr> predicates = splitAndPredicates(whereExpr);

        // Create a Filter node for each predicate (bottom-up)
        LogicalNode result = child;
        for (AST.Expr predicate : predicates) {
            Expression expr = convertExpression(predicate);
            result = new LogicalFilter(expr, result);
        }

        return result;
    }

    /**
     * Split AND predicates into a list.
     * Example: (a = 1 AND b = 2 AND c = 3) becomes [a=1, b=2, c=3]
     * This is part of enforcing canonical form.
     */
    private List<AST.Expr> splitAndPredicates(AST.Expr expr) {
        List<AST.Expr> result = new ArrayList<>();

        if (expr instanceof AST.BinaryExpr(AST.BinaryExpr.Op operator, AST.Expr left, AST.Expr right)) {
            if (operator == AST.BinaryExpr.Op.AND) {
                // Recursively split both sides
                result.addAll(splitAndPredicates(left));
                result.addAll(splitAndPredicates(right));
                return result;
            }
        }

        // Not an AND, so this is a leaf predicate
        result.add(expr);
        return result;
    }

    /**
     * Build aggregation node.
     */
    private LogicalNode buildAggregate(AST.SelectStmt stmt, LogicalNode child) {
        List<String> groupByColumns = stmt.groupByColumns();
        List<LogicalAggregate.AggregateOp> aggOps = new ArrayList<>();

        // Extract aggregate operations from SELECT list
        for (AST.SelectItem item : stmt.selectItems()) {
            if (item instanceof AST.AggregateSelectItem aggItem) {

                LogicalAggregate.AggFunction function = convertAggFunction(aggItem.function());
                String inputColumn = aggItem.columnName();
                String outputColumn = aggItem.getAlias();

                aggOps.add(new LogicalAggregate.AggregateOp(function, inputColumn, outputColumn));
            }
        }

        return new LogicalAggregate(groupByColumns, aggOps, child);
    }

    /**
     * Build projection for SELECT list.
     */
    private LogicalNode buildProject(List<AST.SelectItem> selectItems, LogicalNode child) {
        List<Expression> projections = new ArrayList<>();
        List<String> columnNames = new ArrayList<>();

        for (AST.SelectItem item : selectItems) {
            if (item instanceof AST.ColumnSelectItem colItem) {

                Expression.ColumnRef colRef;
                if (colItem.tableName() != null) {
                    colRef = new Expression.ColumnRef(colItem.tableName(), colItem.columnName());
                } else {
                    colRef = Expression.ColumnRef.from(colItem.columnName());
                }

                projections.add(colRef);
                columnNames.add(colItem.getAlias());
            } else if (item instanceof AST.AggregateSelectItem aggItem) {
                // Aggregates are handled by LogicalAggregate node
                // Here we just pass through the result column
                Expression.ColumnRef colRef = Expression.ColumnRef.from(aggItem.getAlias());
                projections.add(colRef);
                columnNames.add(aggItem.getAlias());
            }
        }

        return new LogicalProject(projections, columnNames, child);
    }

    /**
     * Convert AST expression to Expression object.
     */
    private Expression convertExpression(AST.Expr expr) {
        switch (expr) {
            case AST.ColumnExpr col -> {
                if (col.tableName() != null) {
                    return new Expression.ColumnRef(col.tableName(), col.columnName());
                } else {
                    return Expression.ColumnRef.from(col.columnName());
                }
            }
            case AST.LiteralExpr lit -> {
                return new Expression.Literal(lit.value());
            }
            case AST.BinaryExpr binary -> {

                Expression left = convertExpression(binary.left());
                Expression right = convertExpression(binary.right());
                Expression.BinaryOp.Operator op = convertOperator(binary.operator());

                return new Expression.BinaryOp(op, left, right);
            }
            default -> throw new IllegalArgumentException("Unknown expression type: " + expr.getClass());
        }
    }

    /**
     * Convert AST operator to Expression operator.
     */
    private Expression.BinaryOp.Operator convertOperator(AST.BinaryExpr.Op op) {
        return switch (op) {
            case EQ -> Expression.BinaryOp.Operator.EQ;
            case NEQ -> Expression.BinaryOp.Operator.NEQ;
            case GT -> Expression.BinaryOp.Operator.GT;
            case GTE -> Expression.BinaryOp.Operator.GTE;
            case LT -> Expression.BinaryOp.Operator.LT;
            case LTE -> Expression.BinaryOp.Operator.LTE;
            case AND -> Expression.BinaryOp.Operator.AND;
            case OR -> Expression.BinaryOp.Operator.OR;
        };
    }

    /**
     * Convert AST aggregate function to LogicalAggregate function.
     */
    private LogicalAggregate.AggFunction convertAggFunction(AST.AggregateSelectItem.AggFunc func) {
        return switch (func) {
            case COUNT -> LogicalAggregate.AggFunction.COUNT;
            case SUM -> LogicalAggregate.AggFunction.SUM;
            case AVG -> LogicalAggregate.AggFunction.AVG;
            case MIN -> LogicalAggregate.AggFunction.MIN;
            case MAX -> LogicalAggregate.AggFunction.MAX;
        };
    }

    /**
     * Check if SELECT list contains any aggregates.
     */
    private boolean hasAggregates(List<AST.SelectItem> selectItems) {
        for (AST.SelectItem item : selectItems) {
            if (item instanceof AST.AggregateSelectItem) {
                return true;
            }
        }
        return false;
    }
}