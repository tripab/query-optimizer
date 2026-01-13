package org.query.optimizer.logical;

import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.TableMetadata;
import org.query.optimizer.logical.Expression.BinaryOp;
import org.query.optimizer.logical.Expression.BinaryOp.Operator;
import org.query.optimizer.logical.Expression.ColumnRef;
import org.query.optimizer.logical.Expression.Literal;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExpressionTest {
    @Test
    public void testBasicExpression() throws IOException {
        Catalog catalog = new Catalog();
        TableMetadata customers = catalog.loadTableFromCSV("customers", "customers.csv");
        Schema schema = customers.getSchema();
        Object[] row = customers.getRow(0);

        Expression expr = new BinaryOp(BinaryOp.Operator.AND,
                new BinaryOp(Operator.EQ,
                        new ColumnRef("customers", "name"),
                        new Literal("Alice")),
                new BinaryOp(Operator.GT,
                        new ColumnRef("customers", "age"),
                        new Literal(25)));
        assertTrue((Boolean) expr.evaluate(row, schema));
        assertEquals("((customers.name = 'Alice') AND (customers.age > 25))",
                expr.toSQLString());
    }
}
