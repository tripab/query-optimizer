package org.query.optimizer.learned.common;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.JoinAlgorithmPolicy;
import org.query.optimizer.JoinOrderPolicy;
import org.query.optimizer.OptimizationOptions;
import org.query.optimizer.QueryOptimizer;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.physical.PhysicalNode;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the expanded Bao/Lero hint space (join-order dimension) and the richer
 * plan featurization (aggregate operator type, deeper operator paths) — Phase 6,
 * task P6-4.
 */
class HintSpaceAndFeaturizationTest {

    private static final Catalog catalog = new Catalog();
    private static QueryOptimizer optimizer;
    private static final String DATA_DIR = "target/generated-test-resources/p6-hints";

    private static final double TYPE_AGGREGATE = 6.0;

    @BeforeAll
    static void setup() throws IOException {
        Files.createDirectories(Paths.get(DATA_DIR));
        write("products", "id:INTEGER,category:VARCHAR", new String[]{"1,A", "2,A", "3,B"});
        write("orders", "id:INTEGER,product_id:INTEGER,customer_id:INTEGER",
                new String[]{"1,1,1", "2,1,2", "3,2,3", "4,3,1", "5,2,2"});
        write("customers", "id:INTEGER,name:VARCHAR", new String[]{"1,Ann", "2,Bo", "3,Cy"});
        optimizer = new QueryOptimizer(catalog);
    }

    private static void write(String table, String header, String[] rows) throws IOException {
        File f = new File(DATA_DIR, table + ".csv");
        try (PrintWriter pw = new PrintWriter(f)) {
            pw.println(header);
            for (String r : rows) pw.println(r);
        }
        catalog.loadTableFromCSV(table, f.getPath());
    }

    @AfterAll
    static void cleanup() throws IOException {
        for (String t : new String[]{"products", "orders", "customers"}) {
            Files.deleteIfExists(Paths.get(DATA_DIR + "/" + t + ".csv"));
        }
        Files.deleteIfExists(Paths.get(DATA_DIR));
    }

    private PhysicalNode plan(String sql, JoinOrderPolicy order) {
        return optimizer.optimize(sql, new OptimizationOptions(
                true, true, true, order, JoinAlgorithmPolicy.FORCE_HASH)).physicalPlan();
    }

    // -------------------------------------------------------------------------
    // Hint space: join-order dimension
    // -------------------------------------------------------------------------

    @Test
    void hintSpaceIncludesJoinOrderDimension() {
        assertEquals(6, HintSet.allHintSets().size());
        assertTrue(HintSet.allHintSets().contains(HintSet.PRESERVE_ORDER));

        assertEquals(JoinOrderPolicy.DP, HintSet.DEFAULT.joinOrderPolicy());
        assertEquals(JoinOrderPolicy.PRESERVE_INPUT, HintSet.PRESERVE_ORDER.joinOrderPolicy());
    }

    @Test
    void fromHintSetPropagatesJoinOrderPolicy() {
        assertEquals(JoinOrderPolicy.DP,
                OptimizationOptions.fromHintSet(HintSet.DEFAULT).joinOrderPolicy());
        assertEquals(JoinOrderPolicy.PRESERVE_INPUT,
                OptimizationOptions.fromHintSet(HintSet.PRESERVE_ORDER).joinOrderPolicy());
    }

    @Test
    void defaultAndPreserveOrderHintsAreDistinctArms() {
        assertNotEquals(HintSet.DEFAULT, HintSet.PRESERVE_ORDER,
                "DEFAULT (DP) and PRESERVE_ORDER must be distinct bandit arms");
    }

    // -------------------------------------------------------------------------
    // Featurization: aggregates and deeper paths
    // -------------------------------------------------------------------------

    @Test
    void featureDimensionCoversEightOperatorSlots() {
        assertEquals(PlanFeaturizer.MAX_OPERATOR_SLOTS * PlanFeaturizer.FEATURES_PER_SLOT
                        + PlanFeaturizer.GLOBAL_FEATURES,
                PlanFeaturizer.FEATURE_DIM);
        assertEquals(8, PlanFeaturizer.MAX_OPERATOR_SLOTS, "deeper paths than the old 5 slots");
    }

    @Test
    void aggregateOperatorIsEncodedInFeatures() {
        PhysicalNode aggPlan = plan(
                "SELECT products.category, COUNT(*) FROM products " +
                        "INNER JOIN orders ON products.id = orders.product_id GROUP BY products.category",
                JoinOrderPolicy.PRESERVE_INPUT);

        double[] features = new PlanFeaturizer().featurize(aggPlan);

        boolean aggregateSlotFound = false;
        for (int slot = 0; slot < PlanFeaturizer.MAX_OPERATOR_SLOTS; slot++) {
            if (features[slot * PlanFeaturizer.FEATURES_PER_SLOT] == TYPE_AGGREGATE) {
                aggregateSlotFound = true;
                break;
            }
        }
        assertTrue(aggregateSlotFound, "an aggregate plan must encode the aggregate operator type");
    }

    @Test
    void deeperPlanPopulatesSlotsBeyondTheOldLimit() {
        // A three-way join has more than five operators; the sixth slot (index 5,
        // unreachable under the old 5-slot encoding) must now carry an operator type.
        PhysicalNode threeWay = plan(
                "SELECT products.category, orders.id, customers.name FROM products " +
                        "INNER JOIN orders ON products.id = orders.product_id " +
                        "INNER JOIN customers ON orders.customer_id = customers.id",
                JoinOrderPolicy.PRESERVE_INPUT);

        double[] features = new PlanFeaturizer().featurize(threeWay);
        int slot5TypeIndex = 5 * PlanFeaturizer.FEATURES_PER_SLOT;
        assertNotEquals(0.0, features[slot5TypeIndex],
                "the sixth operator slot must be populated for a deep plan");
    }
}
