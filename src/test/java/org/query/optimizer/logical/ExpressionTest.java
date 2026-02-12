package org.query.optimizer.logical;

import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.TableMetadata;
import org.query.optimizer.logical.Expression.BinaryOp;
import org.query.optimizer.logical.Expression.BinaryOp.Operator;
import org.query.optimizer.logical.Expression.ColumnRef;
import org.query.optimizer.logical.Expression.Literal;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExpressionTest {
    @Test
    public void testBasicExpression() throws IOException {
        TableMetadata customers = getTableMetadata();

        Schema schema = customers.getSchema();
        Map<Schema.Column, Object> row = customers.getRow(0);

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

    private static TableMetadata getTableMetadata() throws IOException {
        File outputDir = new File("target/generated-resources");
        outputDir.mkdirs();

        // Customers table
        try (PrintWriter pw = new PrintWriter(new File(outputDir, "customers.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,city:VARCHAR,age:INTEGER");
            pw.println("1,Alice,Seattle,30");
            pw.println("2,Bob,Portland,25");
            pw.println("3,Charlie,Seattle,35");
            pw.println("4,Diana,San Francisco,28");
            pw.println("5,Eve,Seattle,32");
            pw.println("6,Frank,Portland,29");
            pw.println("7,Grace,Seattle,31");
            pw.println("8,Henry,San Francisco,27");
        }

        Catalog catalog = new Catalog();
        TableMetadata customers = catalog.loadTableFromCSV("customers", "target/generated-resources/customers.csv");
        return customers;
    }
}
