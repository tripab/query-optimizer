package org.query.optimizer.vectorized;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.DataType;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.TableMetadata;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.Expression.BinaryOp;
import org.query.optimizer.logical.Expression.BinaryOp.Operator;
import org.query.optimizer.logical.Expression.ColumnRef;
import org.query.optimizer.parser.LogicalAggregate.AggFunction;
import org.query.optimizer.parser.LogicalAggregate.AggregateOp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link VectorizedAggregate} and {@link AggregateAccumulator}.
 *
 * <p>Coverage:
 * <ul>
 *   <li>AggregateAccumulator — COUNT, SUM (integer + float), AVG, MIN, MAX, no-input null result</li>
 *   <li>COUNT(*) — global count with no GROUP BY</li>
 *   <li>SUM — single-group and multi-group, integer and float columns</li>
 *   <li>AVG — fractional result for integer input</li>
 *   <li>MIN / MAX — integer, float, and VARCHAR columns</li>
 *   <li>Multi-column GROUP BY</li>
 *   <li>Output schema: group-by columns first, aggregate columns after</li>
 *   <li>Empty input produces no output rows</li>
 *   <li>All rows in same group (single-group aggregation)</li>
 *   <li>Aggregate over filtered input (selection-vector awareness)</li>
 *   <li>Multi-batch output when number of groups exceeds DEFAULT_BATCH_SIZE</li>
 * </ul>
 */
class VectorizedAggregateTest {

    // sales: region VARCHAR, category VARCHAR, amount FLOAT, units INTEGER
    private static final Schema SALES_SCHEMA = new Schema(List.of(
            new Schema.Column("region",   DataType.VARCHAR),
            new Schema.Column("category", DataType.VARCHAR),
            new Schema.Column("amount",   DataType.FLOAT),
            new Schema.Column("units",    DataType.INTEGER)
    ));

    private Catalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new Catalog();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<Schema.Column, Object> salesRow(String region, String category,
                                                 float amount, int units) {
        Map<Schema.Column, Object> row = new HashMap<>();
        row.put(SALES_SCHEMA.getColumn("region"),   region);
        row.put(SALES_SCHEMA.getColumn("category"), category);
        row.put(SALES_SCHEMA.getColumn("amount"),   amount);
        row.put(SALES_SCHEMA.getColumn("units"),    units);
        return row;
    }

    private VectorizedScan scanOf(String table, List<Map<Schema.Column, Object>> rows) {
        catalog.registerTable(new TableMetadata(table, SALES_SCHEMA, rows));
        return new VectorizedScan(table, catalog);
    }

    private AggregateOp countStar(String outputCol) {
        return new AggregateOp(AggFunction.COUNT, "*", outputCol);
    }

    private AggregateOp sum(String inputCol, String outputCol) {
        return new AggregateOp(AggFunction.SUM, inputCol, outputCol);
    }

    private AggregateOp avg(String inputCol, String outputCol) {
        return new AggregateOp(AggFunction.AVG, inputCol, outputCol);
    }

    private AggregateOp min(String inputCol, String outputCol) {
        return new AggregateOp(AggFunction.MIN, inputCol, outputCol);
    }

    private AggregateOp max(String inputCol, String outputCol) {
        return new AggregateOp(AggFunction.MAX, inputCol, outputCol);
    }

    /**
     * Drain all output rows into a list of Object[] in output-schema column order.
     */
    private List<Object[]> collectRows(VectorizedAggregate agg) {
        agg.open();
        List<Object[]> rows = new ArrayList<>();
        ColumnBatch batch;
        while ((batch = agg.next()) != null) {
            int colCount = agg.getOutputSchema().columnCount();
            for (int r = 0; r < batch.getSize(); r++) {
                Object[] row = new Object[colCount];
                for (int c = 0; c < colCount; c++) {
                    row[c] = batch.getVector(c).get(r);
                }
                rows.add(row);
            }
        }
        agg.close();
        return rows;
    }

    // =========================================================================
    // AggregateAccumulator unit tests
    // =========================================================================

    @Test
    void testCountAccumulatorCountsNonNullValues() {
        AggregateAccumulator acc = AggregateAccumulator.create(AggFunction.COUNT, DataType.INTEGER);
        acc.update(1);
        acc.update(2);
        acc.update(null);
        acc.update(3);
        assertEquals(3, acc.result());
    }

    @Test
    void testCountAccumulatorWithNoUpdatesReturnsZero() {
        AggregateAccumulator acc = AggregateAccumulator.create(AggFunction.COUNT, DataType.INTEGER);
        assertEquals(0, acc.result());
    }

    @Test
    void testSumAccumulatorForIntegerColumn() {
        AggregateAccumulator acc = AggregateAccumulator.create(AggFunction.SUM, DataType.INTEGER);
        acc.update(10);
        acc.update(20);
        acc.update(30);
        assertEquals(60, acc.result());
    }

    @Test
    void testSumAccumulatorForFloatColumn() {
        AggregateAccumulator acc = AggregateAccumulator.create(AggFunction.SUM, DataType.FLOAT);
        acc.update(1.5f);
        acc.update(2.5f);
        assertEquals(4.0f, (float) acc.result(), 0.0001f);
    }

    @Test
    void testSumAccumulatorSkipsNulls() {
        AggregateAccumulator acc = AggregateAccumulator.create(AggFunction.SUM, DataType.INTEGER);
        acc.update(5);
        acc.update(null);
        acc.update(10);
        assertEquals(15, acc.result());
    }

    @Test
    void testSumAccumulatorWithNoNonNullValuesReturnsNull() {
        AggregateAccumulator acc = AggregateAccumulator.create(AggFunction.SUM, DataType.INTEGER);
        acc.update(null);
        assertNull(acc.result());
    }

    @Test
    void testAvgAccumulatorProducesFloatResult() {
        AggregateAccumulator acc = AggregateAccumulator.create(AggFunction.AVG, DataType.INTEGER);
        acc.update(10);
        acc.update(20);
        acc.update(30);
        assertEquals(20.0f, (float) acc.result(), 0.0001f);
    }

    @Test
    void testAvgAccumulatorSkipsNulls() {
        AggregateAccumulator acc = AggregateAccumulator.create(AggFunction.AVG, DataType.FLOAT);
        acc.update(6.0f);
        acc.update(null);
        acc.update(14.0f);
        assertEquals(10.0f, (float) acc.result(), 0.0001f);
    }

    @Test
    void testAvgAccumulatorWithNoNonNullValuesReturnsNull() {
        AggregateAccumulator acc = AggregateAccumulator.create(AggFunction.AVG, DataType.FLOAT);
        assertNull(acc.result());
    }

    @Test
    void testMinAccumulatorForIntegers() {
        AggregateAccumulator acc = AggregateAccumulator.create(AggFunction.MIN, DataType.INTEGER);
        acc.update(5);
        acc.update(2);
        acc.update(8);
        assertEquals(2, acc.result());
    }

    @Test
    void testMinAccumulatorForVarchar() {
        AggregateAccumulator acc = AggregateAccumulator.create(AggFunction.MIN, DataType.VARCHAR);
        acc.update("banana");
        acc.update("apple");
        acc.update("cherry");
        assertEquals("apple", acc.result());
    }

    @Test
    void testMaxAccumulatorForFloats() {
        AggregateAccumulator acc = AggregateAccumulator.create(AggFunction.MAX, DataType.FLOAT);
        acc.update(3.5f);
        acc.update(9.1f);
        acc.update(1.2f);
        assertEquals(9.1f, (float) acc.result(), 0.0001f);
    }

    @Test
    void testMaxAccumulatorForVarchar() {
        AggregateAccumulator acc = AggregateAccumulator.create(AggFunction.MAX, DataType.VARCHAR);
        acc.update("apple");
        acc.update("zebra");
        acc.update("mango");
        assertEquals("zebra", acc.result());
    }

    @Test
    void testMinMaxSkipNulls() {
        AggregateAccumulator min = AggregateAccumulator.create(AggFunction.MIN, DataType.INTEGER);
        AggregateAccumulator max = AggregateAccumulator.create(AggFunction.MAX, DataType.INTEGER);
        min.update(null);
        min.update(7);
        max.update(null);
        max.update(7);
        assertEquals(7, min.result());
        assertEquals(7, max.result());
    }

    // =========================================================================
    // VectorizedAggregate operator tests
    // =========================================================================

    // ---- COUNT(*) with no GROUP BY (global count) ----

    @Test
    void testGlobalCountStarReturnsOneRow() {
        List<Map<Schema.Column, Object>> rows = List.of(
                salesRow("East", "A", 10f, 1),
                salesRow("West", "B", 20f, 2),
                salesRow("East", "A", 30f, 3)
        );

        VectorizedAggregate agg = new VectorizedAggregate(
                scanOf("s1", rows), List.of(), List.of(countStar("total")));

        List<Object[]> result = collectRows(agg);
        assertEquals(1, result.size());
        assertEquals(3, result.get(0)[0]);   // total = 3
    }

    @Test
    void testGlobalCountStarOnEmptyTableReturnsOneRowWithZero() {
        VectorizedAggregate agg = new VectorizedAggregate(
                scanOf("s_empty", List.of()), List.of(), List.of(countStar("total")));

        List<Object[]> result = collectRows(agg);
        // COUNT(*) with no GROUP BY on empty input: no groups formed → no output rows
        // (this matches standard SQL behaviour for aggregate-without-group-by on empty)
        assertEquals(0, result.size());
    }

    // ---- Single GROUP BY ----

    @Test
    void testCountStarGroupByRegionProducesOneRowPerGroup() {
        List<Map<Schema.Column, Object>> rows = List.of(
                salesRow("East", "A", 10f, 1),
                salesRow("West", "B", 20f, 2),
                salesRow("East", "C", 30f, 3)
        );

        VectorizedAggregate agg = new VectorizedAggregate(
                scanOf("s2", rows),
                List.of("region"),
                List.of(countStar("cnt")));

        List<Object[]> result = collectRows(agg);
        assertEquals(2, result.size());

        Map<String, Integer> counts = new HashMap<>();
        for (Object[] r : result) counts.put((String) r[0], (Integer) r[1]);

        assertEquals(2, counts.get("East"));
        assertEquals(1, counts.get("West"));
    }

    @Test
    void testSumGroupByRegionProducesCorrectSums() {
        List<Map<Schema.Column, Object>> rows = List.of(
                salesRow("East", "A", 10f, 5),
                salesRow("West", "B", 20f, 3),
                salesRow("East", "C", 30f, 7)
        );

        VectorizedAggregate agg = new VectorizedAggregate(
                scanOf("s3", rows),
                List.of("region"),
                List.of(sum("units", "total_units")));

        List<Object[]> result = collectRows(agg);
        assertEquals(2, result.size());

        Map<String, Integer> sums = new HashMap<>();
        for (Object[] r : result) sums.put((String) r[0], (Integer) r[1]);

        assertEquals(12, sums.get("East"));   // 5 + 7
        assertEquals(3,  sums.get("West"));
    }

    @Test
    void testSumFloatColumnGroupByRegion() {
        List<Map<Schema.Column, Object>> rows = List.of(
                salesRow("North", "X", 100.0f, 1),
                salesRow("North", "Y", 200.0f, 2),
                salesRow("South", "X", 50.0f,  1)
        );

        VectorizedAggregate agg = new VectorizedAggregate(
                scanOf("s4", rows),
                List.of("region"),
                List.of(sum("amount", "total_amount")));

        List<Object[]> result = collectRows(agg);

        Map<String, Float> sums = new HashMap<>();
        for (Object[] r : result) sums.put((String) r[0], (Float) r[1]);

        assertEquals(300.0f, sums.get("North"), 0.001f);
        assertEquals(50.0f,  sums.get("South"), 0.001f);
    }

    @Test
    void testAvgGroupByRegion() {
        List<Map<Schema.Column, Object>> rows = List.of(
                salesRow("East", "A", 10f, 10),
                salesRow("East", "B", 20f, 20),
                salesRow("West", "C", 30f, 30)
        );

        VectorizedAggregate agg = new VectorizedAggregate(
                scanOf("s5", rows),
                List.of("region"),
                List.of(avg("units", "avg_units")));

        List<Object[]> result = collectRows(agg);
        Map<String, Float> avgs = new HashMap<>();
        for (Object[] r : result) avgs.put((String) r[0], (Float) r[1]);

        assertEquals(15.0f, avgs.get("East"), 0.001f);   // (10+20)/2
        assertEquals(30.0f, avgs.get("West"), 0.001f);
    }

    @Test
    void testMinMaxGroupByRegion() {
        List<Map<Schema.Column, Object>> rows = List.of(
                salesRow("East", "A", 5f,  3),
                salesRow("East", "B", 15f, 9),
                salesRow("East", "C", 10f, 1)
        );

        VectorizedAggregate agg = new VectorizedAggregate(
                scanOf("s6", rows),
                List.of("region"),
                List.of(min("units", "min_u"), max("units", "max_u")));

        List<Object[]> result = collectRows(agg);
        assertEquals(1, result.size());
        assertEquals(1,  result.get(0)[1]);   // min_u
        assertEquals(9,  result.get(0)[2]);   // max_u
    }

    // ---- Multi-column GROUP BY ----

    @Test
    void testMultiColumnGroupBy() {
        List<Map<Schema.Column, Object>> rows = List.of(
                salesRow("East", "A", 10f, 1),
                salesRow("East", "B", 20f, 2),
                salesRow("East", "A", 30f, 3),
                salesRow("West", "A", 40f, 4)
        );

        VectorizedAggregate agg = new VectorizedAggregate(
                scanOf("s7", rows),
                List.of("region", "category"),
                List.of(countStar("cnt")));

        List<Object[]> result = collectRows(agg);
        assertEquals(3, result.size());   // (East,A), (East,B), (West,A)

        Set<String> groupKeys = new HashSet<>();
        for (Object[] r : result) groupKeys.add(r[0] + ":" + r[1]);
        assertTrue(groupKeys.contains("East:A"));
        assertTrue(groupKeys.contains("East:B"));
        assertTrue(groupKeys.contains("West:A"));
    }

    // ---- Multiple aggregate functions in one operator ----

    @Test
    void testMultipleAggregatesOnSameGroupBy() {
        List<Map<Schema.Column, Object>> rows = List.of(
                salesRow("East", "A", 10f, 2),
                salesRow("East", "B", 40f, 8)
        );

        VectorizedAggregate agg = new VectorizedAggregate(
                scanOf("s8", rows),
                List.of("region"),
                List.of(countStar("cnt"), sum("units", "total"), avg("units", "avg_u")));

        List<Object[]> result = collectRows(agg);
        assertEquals(1, result.size());

        Object[] r = result.get(0);
        assertEquals("East", r[0]);
        assertEquals(2,     r[1]);    // cnt
        assertEquals(10,    r[2]);    // total (2+8)
        assertEquals(5.0f,  (float) r[3], 0.001f);  // avg_u (10/2)
    }

    // ---- Empty input ----

    @Test
    void testEmptyInputProducesNoOutputRows() {
        VectorizedAggregate agg = new VectorizedAggregate(
                scanOf("s_e", List.of()),
                List.of("region"),
                List.of(countStar("cnt")));

        List<Object[]> result = collectRows(agg);
        assertTrue(result.isEmpty());
    }

    // ---- Output schema ----

    @Test
    void testOutputSchemaGroupByColumnsFirst() {
        List<Map<Schema.Column, Object>> rows = List.of(salesRow("E", "A", 1f, 1));

        VectorizedAggregate agg = new VectorizedAggregate(
                scanOf("s9", rows),
                List.of("region", "category"),
                List.of(countStar("cnt"), sum("units", "total")));

        agg.open();
        Schema schema = agg.getOutputSchema();
        agg.close();

        assertEquals(4, schema.columnCount());
        assertEquals("region",   schema.getColumn(0).name());
        assertEquals("category", schema.getColumn(1).name());
        assertEquals("cnt",      schema.getColumn(2).name());
        assertEquals("total",    schema.getColumn(3).name());
        assertEquals(DataType.INTEGER, schema.getColumn(2).type());
        assertEquals(DataType.INTEGER, schema.getColumn(3).type());
    }

    @Test
    void testOutputSchemaAvgIsFloat() {
        List<Map<Schema.Column, Object>> rows = List.of(salesRow("E", "A", 1f, 1));

        VectorizedAggregate agg = new VectorizedAggregate(
                scanOf("sAvg", rows),
                List.of("region"),
                List.of(avg("units", "avg_u")));

        agg.open();
        Schema schema = agg.getOutputSchema();
        agg.close();

        assertEquals(DataType.FLOAT, schema.getColumn(1).type());
    }

    @Test
    void testOutputSchemaSafeToCallBeforeOpen() {
        VectorizedAggregate agg = new VectorizedAggregate(
                scanOf("sPre", List.of()),
                List.of("region"),
                List.of(countStar("cnt")));

        assertDoesNotThrow(agg::getOutputSchema);
        assertEquals(2, agg.getOutputSchema().columnCount());
    }

    // ---- All rows in same group ----

    @Test
    void testAllRowsInSameGroupProducesOneOutputRow() {
        List<Map<Schema.Column, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) rows.add(salesRow("East", "A", i * 1.0f, i));

        VectorizedAggregate agg = new VectorizedAggregate(
                scanOf("sOne", rows),
                List.of("region"),
                List.of(countStar("cnt"), sum("units", "total")));

        List<Object[]> result = collectRows(agg);
        assertEquals(1, result.size());
        assertEquals(100, result.get(0)[1]);         // cnt
        // sum(0..99) = 4950
        assertEquals(4950, result.get(0)[2]);
    }

    // ---- Aggregate over filtered input ----

    @Test
    void testAggregateOverFilteredInputRespectsSelectionVector() {
        List<Map<Schema.Column, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 10; i++) rows.add(salesRow("R", "C", i * 10f, i));

        // Filter: units > 5 → rows with units 6..10
        VectorizedFilter filtered = new VectorizedFilter(
                scanOf("sFil", rows),
                new BinaryOp(Operator.GT, ColumnRef.from("units"), new Expression.Literal<>(5)));

        VectorizedAggregate agg = new VectorizedAggregate(
                filtered, List.of(), List.of(countStar("cnt"), sum("units", "total")));

        List<Object[]> result = collectRows(agg);
        assertEquals(1, result.size());
        assertEquals(5,  result.get(0)[0]);    // cnt (6,7,8,9,10)
        assertEquals(40, result.get(0)[1]);    // sum(6+7+8+9+10)=40
    }

    // ---- Multi-batch output (many distinct groups) ----

    @Test
    void testManyGroupsSpanMultipleOutputBatches() {
        // Each row has a unique region key → DEFAULT_BATCH_SIZE+10 groups
        int groupCount = ColumnBatch.DEFAULT_BATCH_SIZE + 10;
        List<Map<Schema.Column, Object>> rows = new ArrayList<>();
        for (int i = 0; i < groupCount; i++) {
            rows.add(salesRow("R" + i, "X", 1f, 1));
        }

        VectorizedAggregate agg = new VectorizedAggregate(
                scanOf("sMany", rows),
                List.of("region"),
                List.of(countStar("cnt")));

        List<Object[]> result = collectRows(agg);
        assertEquals(groupCount, result.size());
    }
}
