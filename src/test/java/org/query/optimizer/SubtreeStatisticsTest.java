package org.query.optimizer;

import org.junit.jupiter.api.Test;
import org.query.optimizer.SubtreeStatistics.ColumnEstimate;
import org.query.optimizer.catalog.ColumnStats;
import org.query.optimizer.catalog.DataType;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.TableMetadata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SubtreeStatistics} and its base-table scan derivation.
 *
 * <p>These exercise the actual derivation logic: NDV/min/max preservation from
 * collected stats, capping NDV at the row count, omission of columns without
 * stats, case-insensitive lookup, fallbacks, and constructor invariants.
 */
class SubtreeStatisticsTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds an in-memory table with {@code rowCount} (content-free) rows and the
     * supplied column statistics. Only row count and stats matter for scan
     * derivation, so the row contents are irrelevant here.
     */
    private TableMetadata tableWith(String name, List<Schema.Column> cols,
                                    int rowCount, ColumnStats... stats) {
        List<Map<Schema.Column, Object>> data = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            data.add(new HashMap<>());
        }
        TableMetadata table = new TableMetadata(name, new Schema(cols), data);
        for (ColumnStats s : stats) {
            table.addColumnStats(s);
        }
        return table;
    }

    private ColumnStats stats(String col, long ndv, Object min, Object max) {
        return new ColumnStats.Builder(col)
                .setNumDistinctValues(ndv)
                .setMinValue(min)
                .setMaxValue(max)
                .build();
    }

    // -------------------------------------------------------------------------
    // forScan: base-stat preservation
    // -------------------------------------------------------------------------

    @Test
    void forScanPreservesRowCountNdvMinMax() {
        TableMetadata t = tableWith("customers",
                List.of(new Schema.Column("id", DataType.INTEGER),
                        new Schema.Column("name", DataType.VARCHAR)),
                100,
                stats("id", 100, 1, 100),
                stats("name", 50, "alice", "zoe"));

        SubtreeStatistics s = SubtreeStatistics.forScan(t);

        assertEquals(100, s.rowCount());

        ColumnEstimate id = s.columnEstimate("id");
        assertNotNull(id);
        assertEquals(100, id.ndv());
        assertEquals(1, id.min());
        assertEquals(100, id.max());

        ColumnEstimate name = s.columnEstimate("name");
        assertNotNull(name);
        assertEquals(50, name.ndv());
        assertEquals("alice", name.min());
        assertEquals("zoe", name.max());
    }

    @Test
    void forScanCapsNdvAtRowCount() {
        // Externally inconsistent stats: NDV (999) exceeds the 10 rows present.
        TableMetadata t = tableWith("t",
                List.of(new Schema.Column("id", DataType.INTEGER)),
                10,
                stats("id", 999, 1, 9));

        SubtreeStatistics s = SubtreeStatistics.forScan(t);

        assertEquals(10, s.rowCount());
        assertEquals(10, s.columnEstimate("id").ndv(), "NDV must be capped at row count");
    }

    @Test
    void forScanCapsNdvAtZeroForEmptyTable() {
        TableMetadata t = tableWith("empty",
                List.of(new Schema.Column("id", DataType.INTEGER)),
                0,
                stats("id", 5, 1, 5));

        SubtreeStatistics s = SubtreeStatistics.forScan(t);

        assertEquals(0, s.rowCount());
        assertEquals(0, s.columnEstimate("id").ndv());
    }

    @Test
    void forScanOmitsColumnsWithoutStats() {
        // 'price' has no collected stats.
        TableMetadata t = tableWith("products",
                List.of(new Schema.Column("id", DataType.INTEGER),
                        new Schema.Column("price", DataType.FLOAT)),
                20,
                stats("id", 20, 1, 20));

        SubtreeStatistics s = SubtreeStatistics.forScan(t);

        assertTrue(s.hasColumn("id"));
        assertFalse(s.hasColumn("price"));
        assertNull(s.columnEstimate("price"));
        assertEquals(1, s.columnNames().size());
    }

    @Test
    void forScanLookupIsCaseInsensitive() {
        TableMetadata t = tableWith("t",
                List.of(new Schema.Column("CustomerId", DataType.INTEGER)),
                5,
                stats("CustomerId", 5, 1, 5));

        SubtreeStatistics s = SubtreeStatistics.forScan(t);

        assertTrue(s.hasColumn("customerid"));
        assertTrue(s.hasColumn("CUSTOMERID"));
        assertNotNull(s.columnEstimate("CustomerId"));
    }

    // -------------------------------------------------------------------------
    // ndvOf fallback behaviour
    // -------------------------------------------------------------------------

    @Test
    void ndvOfReturnsTrackedValueWhenPresent() {
        SubtreeStatistics s = new SubtreeStatistics(
                100, Map.of("id", new ColumnEstimate(42, null, null)));

        assertEquals(42, s.ndvOf("id", 7));
    }

    @Test
    void ndvOfReturnsFallbackWhenAbsent() {
        SubtreeStatistics s = new SubtreeStatistics(100, Map.of());

        assertEquals(7, s.ndvOf("missing", 7));
    }

    // -------------------------------------------------------------------------
    // Construction and normalisation
    // -------------------------------------------------------------------------

    @Test
    void constructorNormalisesColumnKeysToLowerCase() {
        SubtreeStatistics s = new SubtreeStatistics(
                10, Map.of("ID", new ColumnEstimate(10, null, null)));

        assertTrue(s.hasColumn("id"));
        assertNotNull(s.columnEstimate("id"));
    }

    @Test
    void constructorRejectsNegativeRowCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new SubtreeStatistics(-1, Map.of()));
    }

    @Test
    void columnEstimateRejectsNegativeNdv() {
        assertThrows(IllegalArgumentException.class,
                () -> new ColumnEstimate(-1, null, null));
    }

    @Test
    void columnEstimatesViewIsUnmodifiable() {
        SubtreeStatistics s = new SubtreeStatistics(
                10, Map.of("id", new ColumnEstimate(10, null, null)));

        assertThrows(UnsupportedOperationException.class,
                () -> s.columnEstimates().put("x", new ColumnEstimate(1, null, null)));
    }
}
