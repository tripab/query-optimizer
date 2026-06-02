package org.query.optimizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.DataType;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.TableMetadata;
import org.query.optimizer.catalog.Tuple;
import org.query.optimizer.parser.LogicalAggregate;
import org.query.optimizer.parser.LogicalAggregate.AggFunction;
import org.query.optimizer.parser.LogicalAggregate.AggregateOp;
import org.query.optimizer.parser.LogicalScan;
import org.query.optimizer.physical.PhysicalAggregate;
import org.query.optimizer.physical.PhysicalNode;
import org.query.optimizer.physical.PhysicalPlanBuilder;
import org.query.optimizer.physical.PhysicalScan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PhysicalAggregate and PhysicalPlanBuilder lowering of LogicalAggregate.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Global COUNT(*) with no GROUP BY</li>
 *   <li>Empty input → no output rows</li>
 *   <li>COUNT(*) / SUM / AVG / MIN / MAX grouped by a single column</li>
 *   <li>Multi-column GROUP BY</li>
 *   <li>Multiple aggregate functions on the same GROUP BY</li>
 *   <li>Output schema: group-by columns first, aggregate columns after</li>
 *   <li>COUNT output type = INTEGER, AVG output type = FLOAT</li>
 *   <li>PhysicalPlanBuilder lowers LogicalAggregate to PhysicalAggregate</li>
 *   <li>End-to-end lowering and execution through PhysicalPlanBuilder</li>
 * </ul>
 */
class PhysicalAggregateTest {

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

    private Map<Schema.Column, Object> row(String region, String category,
                                            float amount, int units) {
        Map<Schema.Column, Object> r = new HashMap<>();
        r.put(SALES_SCHEMA.getColumn("region"),   region);
        r.put(SALES_SCHEMA.getColumn("category"), category);
        r.put(SALES_SCHEMA.getColumn("amount"),   amount);
        r.put(SALES_SCHEMA.getColumn("units"),    units);
        return r;
    }

    private PhysicalScan scanOf(String table, List<Map<Schema.Column, Object>> rows) {
        catalog.registerTable(new TableMetadata(table, SALES_SCHEMA, rows));
        return new PhysicalScan(table, catalog);
    }

    private AggregateOp countStar(String out) {
        return new AggregateOp(AggFunction.COUNT, "*", out);
    }

    private AggregateOp sum(String in, String out) {
        return new AggregateOp(AggFunction.SUM, in, out);
    }

    private AggregateOp avg(String in, String out) {
        return new AggregateOp(AggFunction.AVG, in, out);
    }

    private AggregateOp min(String in, String out) {
        return new AggregateOp(AggFunction.MIN, in, out);
    }

    private AggregateOp max(String in, String out) {
        return new AggregateOp(AggFunction.MAX, in, out);
    }

    /**
     * Drain a PhysicalAggregate into a list of Object[] in output-schema column order.
     */
    private List<Object[]> collect(PhysicalAggregate agg) {
        agg.open();
        List<Object[]> rows = new ArrayList<>();
        Schema schema = agg.getOutputSchema();
        Tuple t;
        while ((t = agg.next()) != null) {
            Object[] r = new Object[schema.columnCount()];
            for (int c = 0; c < schema.columnCount(); c++) {
                r[c] = t.find(schema.getColumn(c));
            }
            rows.add(r);
        }
        agg.close();
        return rows;
    }

    // =========================================================================
    // Global aggregation (no GROUP BY)
    // =========================================================================

    @Test
    void testGlobalCountStarReturnsOneRow() {
        PhysicalScan scan = scanOf("t1", List.of(
                row("East", "A", 10f, 1),
                row("West", "B", 20f, 2),
                row("East", "A", 30f, 3)
        ));

        PhysicalAggregate agg = new PhysicalAggregate(
                scan, List.of(), List.of(countStar("total")), SALES_SCHEMA);

        List<Object[]> result = collect(agg);
        assertEquals(1, result.size());
        assertEquals(3, result.get(0)[0]);
    }

    @Test
    void testGlobalCountStarOnEmptyInputReturnsNoRows() {
        PhysicalScan scan = scanOf("t_empty", List.of());

        PhysicalAggregate agg = new PhysicalAggregate(
                scan, List.of(), List.of(countStar("total")), SALES_SCHEMA);

        List<Object[]> result = collect(agg);
        assertTrue(result.isEmpty());
    }

    // =========================================================================
    // Single GROUP BY — correctness of each function
    // =========================================================================

    @Test
    void testCountStarGroupByRegion() {
        PhysicalScan scan = scanOf("t2", List.of(
                row("East", "A", 10f, 1),
                row("West", "B", 20f, 2),
                row("East", "C", 30f, 3)
        ));

        PhysicalAggregate agg = new PhysicalAggregate(
                scan, List.of("region"), List.of(countStar("cnt")), SALES_SCHEMA);

        List<Object[]> result = collect(agg);
        assertEquals(2, result.size());

        Map<String, Integer> counts = new HashMap<>();
        for (Object[] r : result) counts.put((String) r[0], (Integer) r[1]);
        assertEquals(2, counts.get("East"));
        assertEquals(1, counts.get("West"));
    }

    @Test
    void testSumIntegerGroupByRegion() {
        PhysicalScan scan = scanOf("t3", List.of(
                row("East", "A", 0f, 5),
                row("West", "B", 0f, 3),
                row("East", "C", 0f, 7)
        ));

        PhysicalAggregate agg = new PhysicalAggregate(
                scan, List.of("region"), List.of(sum("units", "total")), SALES_SCHEMA);

        List<Object[]> result = collect(agg);
        assertEquals(2, result.size());

        Map<String, Integer> sums = new HashMap<>();
        for (Object[] r : result) sums.put((String) r[0], (Integer) r[1]);
        assertEquals(12, sums.get("East"));
        assertEquals(3,  sums.get("West"));
    }

    @Test
    void testSumFloatGroupByRegion() {
        PhysicalScan scan = scanOf("t4", List.of(
                row("North", "X", 100f, 0),
                row("North", "Y", 200f, 0),
                row("South", "X", 50f,  0)
        ));

        PhysicalAggregate agg = new PhysicalAggregate(
                scan, List.of("region"), List.of(sum("amount", "total_amt")), SALES_SCHEMA);

        List<Object[]> result = collect(agg);
        Map<String, Float> sums = new HashMap<>();
        for (Object[] r : result) sums.put((String) r[0], (Float) r[1]);

        assertEquals(300f, sums.get("North"), 0.001f);
        assertEquals(50f,  sums.get("South"), 0.001f);
    }

    @Test
    void testAvgGroupByRegion() {
        PhysicalScan scan = scanOf("t5", List.of(
                row("East", "A", 0f, 10),
                row("East", "B", 0f, 20),
                row("West", "C", 0f, 30)
        ));

        PhysicalAggregate agg = new PhysicalAggregate(
                scan, List.of("region"), List.of(avg("units", "avg_u")), SALES_SCHEMA);

        List<Object[]> result = collect(agg);
        Map<String, Float> avgs = new HashMap<>();
        for (Object[] r : result) avgs.put((String) r[0], (Float) r[1]);

        assertEquals(15f, avgs.get("East"), 0.001f);
        assertEquals(30f, avgs.get("West"), 0.001f);
    }

    @Test
    void testMinMaxGroupByRegion() {
        PhysicalScan scan = scanOf("t6", List.of(
                row("East", "A", 0f, 3),
                row("East", "B", 0f, 9),
                row("East", "C", 0f, 1)
        ));

        PhysicalAggregate agg = new PhysicalAggregate(
                scan, List.of("region"),
                List.of(min("units", "min_u"), max("units", "max_u")),
                SALES_SCHEMA);

        List<Object[]> result = collect(agg);
        assertEquals(1, result.size());
        assertEquals(1, result.get(0)[1]);  // min
        assertEquals(9, result.get(0)[2]);  // max
    }

    // =========================================================================
    // Multi-column GROUP BY
    // =========================================================================

    @Test
    void testMultiColumnGroupBy() {
        PhysicalScan scan = scanOf("t7", List.of(
                row("East", "A", 0f, 1),
                row("East", "B", 0f, 2),
                row("East", "A", 0f, 3),
                row("West", "A", 0f, 4)
        ));

        PhysicalAggregate agg = new PhysicalAggregate(
                scan, List.of("region", "category"),
                List.of(countStar("cnt")),
                SALES_SCHEMA);

        List<Object[]> result = collect(agg);
        assertEquals(3, result.size());

        Set<String> keys = new HashSet<>();
        for (Object[] r : result) keys.add(r[0] + ":" + r[1]);
        assertTrue(keys.contains("East:A"));
        assertTrue(keys.contains("East:B"));
        assertTrue(keys.contains("West:A"));
    }

    // =========================================================================
    // Multiple aggregates on same GROUP BY
    // =========================================================================

    @Test
    void testMultipleAggregatesOnSameGroupBy() {
        PhysicalScan scan = scanOf("t8", List.of(
                row("East", "A", 0f, 2),
                row("East", "B", 0f, 8)
        ));

        PhysicalAggregate agg = new PhysicalAggregate(
                scan, List.of("region"),
                List.of(countStar("cnt"), sum("units", "total"), avg("units", "avg_u")),
                SALES_SCHEMA);

        List<Object[]> result = collect(agg);
        assertEquals(1, result.size());

        Object[] r = result.get(0);
        assertEquals("East", r[0]);
        assertEquals(2,      r[1]);                     // cnt
        assertEquals(10,     r[2]);                     // sum(2+8)
        assertEquals(5f, (float) r[3], 0.001f);         // avg(10/2)
    }

    // =========================================================================
    // Output schema
    // =========================================================================

    @Test
    void testOutputSchemaGroupByColumnsFirst() {
        PhysicalScan scan = scanOf("tSchema", List.of(row("E", "A", 1f, 1)));

        PhysicalAggregate agg = new PhysicalAggregate(
                scan, List.of("region", "category"),
                List.of(countStar("cnt"), sum("units", "total")),
                SALES_SCHEMA);

        Schema schema = agg.getOutputSchema();
        assertEquals(4, schema.columnCount());
        assertEquals("region",   schema.getColumn(0).name());
        assertEquals("category", schema.getColumn(1).name());
        assertEquals("cnt",      schema.getColumn(2).name());
        assertEquals("total",    schema.getColumn(3).name());
        assertEquals(DataType.INTEGER, schema.getColumn(2).type());
        assertEquals(DataType.INTEGER, schema.getColumn(3).type());
    }

    @Test
    void testAvgOutputTypeIsFloat() {
        PhysicalScan scan = scanOf("tAvg", List.of(row("E", "A", 1f, 1)));

        PhysicalAggregate agg = new PhysicalAggregate(
                scan, List.of("region"), List.of(avg("units", "avg_u")), SALES_SCHEMA);

        assertEquals(DataType.FLOAT, agg.getOutputSchema().getColumn(1).type());
    }

    @Test
    void testOutputSchemaAccessibleBeforeOpen() {
        PhysicalScan scan = scanOf("tPre", List.of());

        PhysicalAggregate agg = new PhysicalAggregate(
                scan, List.of("region"), List.of(countStar("cnt")), SALES_SCHEMA);

        assertDoesNotThrow(agg::getOutputSchema);
        assertEquals(2, agg.getOutputSchema().columnCount());
    }

    // =========================================================================
    // Iterator lifecycle
    // =========================================================================

    @Test
    void testDoubleOpenThrows() {
        PhysicalScan scan = scanOf("tOL", List.of());

        PhysicalAggregate agg = new PhysicalAggregate(
                scan, List.of("region"), List.of(countStar("cnt")), SALES_SCHEMA);

        agg.open();
        assertThrows(IllegalStateException.class, agg::open);
        agg.close();
    }

    @Test
    void testCloseWithoutOpenIsIdempotent() {
        PhysicalScan scan = scanOf("tCL", List.of());

        PhysicalAggregate agg = new PhysicalAggregate(
                scan, List.of("region"), List.of(countStar("cnt")), SALES_SCHEMA);

        assertDoesNotThrow(agg::close);
    }

    // =========================================================================
    // PhysicalPlanBuilder lowering
    // =========================================================================

    @Test
    void testPhysicalPlanBuilderLowersLogicalAggregate() {
        catalog.registerTable(new TableMetadata("sales", SALES_SCHEMA, List.of(
                row("East", "A", 10f, 5),
                row("West", "B", 20f, 3),
                row("East", "C", 30f, 7)
        )));

        LogicalScan scan  = new LogicalScan("sales");
        LogicalAggregate logical = new LogicalAggregate(
                List.of("region"),
                List.of(sum("units", "total_units")),
                scan);

        PhysicalPlanBuilder builder = new PhysicalPlanBuilder(catalog);
        PhysicalNode physical = builder.build(logical);

        assertInstanceOf(PhysicalAggregate.class, physical);
    }

    @Test
    void testEndToEndLoweringAndExecution() {
        catalog.registerTable(new TableMetadata("sales2", SALES_SCHEMA, List.of(
                row("East", "A", 10f, 5),
                row("West", "B", 20f, 3),
                row("East", "C", 30f, 7)
        )));

        LogicalScan scan = new LogicalScan("sales2");
        LogicalAggregate logical = new LogicalAggregate(
                List.of("region"),
                List.of(sum("units", "total_units")),
                scan);

        PhysicalPlanBuilder builder = new PhysicalPlanBuilder(catalog);
        PhysicalAggregate physical = (PhysicalAggregate) builder.build(logical);

        List<Object[]> results = collect(physical);
        assertEquals(2, results.size());

        Map<String, Integer> sums = new HashMap<>();
        for (Object[] r : results) {
            sums.put((String) r[0], (Integer) r[1]);
        }
        assertEquals(12, sums.get("East")); // 5 + 7
        assertEquals(3,  sums.get("West"));
    }
}
