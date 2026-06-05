package org.query.optimizer.parser;


import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-written SQL parser for our restricted syntax.
 * <p>
 * Supported:
 * - SELECT col1, col2, COUNT(*), SUM(col)
 * - FROM table1 INNER JOIN table2 ON condition [INNER JOIN table3 ON condition]
 * - WHERE condition
 * - GROUP BY col1, col2
 * <p>
 * Not supported: subqueries, DISTINCT, ORDER BY, LIMIT, outer joins, complex expressions
 */
public class SQLParser {
    private List<String> tokens;
    private int position;

    public SQLParser() {
    }

    /**
     * Parse a SQL query string into an AST.
     */
    public AST.SelectStmt parse(String sql) {
        // Tokenize
        tokens = tokenize(sql);
        position = 0;

        return parseSelect();
    }

    /**
     * Simple tokenizer that splits on whitespace and special characters.
     */
    private List<String> tokenize(String sql) {
        List<String> tokens = new ArrayList<>();

        // Pattern: matches quoted strings, numbers, identifiers, operators, commas, parens
        Pattern pattern = Pattern.compile(
                "'[^']*'|" +              // Single-quoted strings
                        "\\d+\\.?\\d*|" +          // Numbers (int or float)
                        "[a-zA-Z_][a-zA-Z0-9_]*|" + // Identifiers
                        "!=|>=|<=|" +              // Two-char operators
                        "[().,=<>*]"               // Single-char operators/delimiters
        );

        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }

        return tokens;
    }

    private String peek() {
        if (position >= tokens.size()) {
            return null;
        }
        return tokens.get(position);
    }

    private String consume() {
        if (position >= tokens.size()) {
            throw new IllegalStateException("Unexpected end of query");
        }
        return tokens.get(position++);
    }

    private boolean match(String expected) {
        String token = peek();
        return token != null && token.equalsIgnoreCase(expected);
    }

    private void expect(String expected) {
        String token = consume();
        if (!token.equalsIgnoreCase(expected)) {
            throw new IllegalArgumentException(
                    "Expected '" + expected + "' but got '" + token + "'");
        }
    }

    /* --- Parser methods --- */
    private AST.SelectStmt parseSelect() {
        expect("SELECT");

        // Parse SELECT items
        List<AST.SelectItem> selectItems = parseSelectList();

        // Parse FROM
        expect("FROM");
        AST.FromClause from = parseFrom();

        // Parse optional WHERE
        AST.Expr whereClause = null;
        if (match("WHERE")) {
            consume(); // WHERE
            whereClause = parseExpression();
        }

        List<String> groupBy = new ArrayList<>();
        if (match("GROUP")) {
            consume(); // GROUP
            expect("BY");
            groupBy = parseColumnList();
        }

        return new AST.SelectStmt(selectItems, from, whereClause, groupBy);
    }

    private List<AST.SelectItem> parseSelectList() {
        List<AST.SelectItem> items = new ArrayList<>();
        do {
            items.add(parseSelectItem());
            if (match(",")) {
                consume();
            } else {
                break;
            }
        } while (true);

        return items;
    }

    private AST.SelectItem parseSelectItem() {
        // checks for aggregate functions
        String token = peek();

        if (isAggregateFunction(token)) {
            String funcName = consume();
            expect("(");
            String column = consume();  // Column name or *
            expect(")");

            AST.AggregateSelectItem.AggFunc func =
                    AST.AggregateSelectItem.AggFunc.valueOf(funcName.toUpperCase());

            return new AST.AggregateSelectItem(func, column);
        }

        // Regular column reference
        String first = consume();

        // Check for qualified column (table.column)
        if (match(".")) {
            consume();
            String column = consume();
            return new AST.ColumnSelectItem(first, column);
        }

        return AST.ColumnSelectItem.from(first);
    }

    private boolean isAggregateFunction(String token) {
        if (token == null) return false;
        String upper = token.toUpperCase();
        return upper.equals("COUNT") || upper.equals("SUM") ||
                upper.equals("AVG") || upper.equals("MIN") || upper.equals("MAX");
    }

    private AST.FromClause parseFrom() {
        AST.FromClause left = parseTableRef();

        // Parse JOINs (left-associative)
        while (match("INNER") || match("JOIN")) {
            if (match("INNER")) {
                consume(); // INNER
            }
            expect("JOIN");

            AST.FromClause right = parseTableRef();

            expect("ON");
            AST.Expr condition = parseExpression();

            left = new AST.JoinClause(left, right, AST.JoinClause.JoinType.INNER, condition);
        }

        return left;
    }

    private AST.TableRef parseTableRef() {
        String tableName = consume();

        // Check for alias (optional)
        String alias = null;
        if (!match("INNER") && !match("JOIN") && !match("ON") &&
                !match("WHERE") && !match("GROUP") && !match(",") && peek() != null) {
            // Next token might be an alias if it's not a keyword
            String next = peek();
            if (!isKeyword(next)) {
                alias = consume();
            }
        }

        return new AST.TableRef(tableName, alias);
    }

    private boolean isKeyword(String token) {
        if (token == null) return false;
        String upper = token.toUpperCase();
        return upper.equals("SELECT") || upper.equals("FROM") || upper.equals("WHERE") ||
                upper.equals("INNER") || upper.equals("JOIN") || upper.equals("ON") ||
                upper.equals("GROUP") || upper.equals("BY") || upper.equals("AND") ||
                upper.equals("OR");
    }

    private AST.Expr parseExpression() {
        return parseOrExpression();
    }

    private AST.Expr parseOrExpression() {
        AST.Expr left = parseAndExpression();

        while (match("OR")) {
            consume();
            AST.Expr right = parseAndExpression();
            left = new AST.BinaryExpr(AST.BinaryExpr.Op.OR, left, right);
        }

        return left;
    }

    private AST.Expr parseAndExpression() {
        AST.Expr left = parseComparisonExpression();

        while (match("AND")) {
            consume();
            AST.Expr right = parseComparisonExpression();
            left = new AST.BinaryExpr(AST.BinaryExpr.Op.AND, left, right);
        }

        return left;
    }

    private AST.Expr parseComparisonExpression() {
        AST.Expr left = parsePrimaryExpression();

        // Check for comparison operator
        String op = peek();
        if (op != null) {
            AST.BinaryExpr.Op operator = null;

            if (op.equals("=")) {
                operator = AST.BinaryExpr.Op.EQ;
            } else if (op.equals("!=")) {
                operator = AST.BinaryExpr.Op.NEQ;
            } else if (op.equals(">")) {
                operator = AST.BinaryExpr.Op.GT;
            } else if (op.equals(">=")) {
                operator = AST.BinaryExpr.Op.GTE;
            } else if (op.equals("<")) {
                operator = AST.BinaryExpr.Op.LT;
            } else if (op.equals("<=")) {
                operator = AST.BinaryExpr.Op.LTE;
            }

            if (operator != null) {
                consume();
                AST.Expr right = parsePrimaryExpression();
                return new AST.BinaryExpr(operator, left, right);
            }
        }

        return left;
    }

    private AST.Expr parsePrimaryExpression() {
        String token = peek();

        // Check for parenthesized expression
        if (match("(")) {
            consume();
            AST.Expr expr = parseExpression();
            expect(")");
            return expr;
        }

        // Check for literal
        if (token.startsWith("'")) {
            // String literal
            consume();
            String value = token.substring(1, token.length() - 1); // Remove quotes
            return new AST.LiteralExpr(value);
        }

        if (token.matches("\\d+\\.\\d+")) {
            // Float literal
            consume();
            return new AST.LiteralExpr(Float.parseFloat(token));
        }

        if (token.matches("\\d+")) {
            // Integer literal
            consume();
            return new AST.LiteralExpr(Integer.parseInt(token));
        }

        // Must be a column reference
        String first = consume();

        // Check for qualified column (table.column)
        if (match(".")) {
            consume();
            String column = consume();
            return new AST.ColumnExpr(first, column);
        }

        return AST.ColumnExpr.from(first);
    }

    private List<String> parseColumnList() {
        List<String> columns = new ArrayList<>();

        do {
            // Accept an optional table qualifier (e.g. "p.category") and keep only
            // the bare column name, matching how the SELECT list resolves
            // qualified columns. Without this, "GROUP BY p.category" would yield
            // the qualifier "p" as the group-by column and fail to resolve.
            String name = consume();
            if (match(".")) {
                consume();          // '.'
                name = consume();   // column name after the qualifier
            }
            columns.add(name);

            if (match(",")) {
                consume();
            } else {
                break;
            }
        } while (true);

        return columns;
    }
}