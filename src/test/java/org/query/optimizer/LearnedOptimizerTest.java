package org.query.optimizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.learned.common.HintSet;
import org.query.optimizer.learned.common.PlanFeaturizer;
import org.query.optimizer.learned.common.PlanVariantGenerator;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.AST;
import org.query.optimizer.parser.LogicalPlanBuilder;
import org.query.optimizer.parser.SQLParser;
import org.query.optimizer.physical.PhysicalNode;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class LearnedOptimizerTest {

    static Catalog catalog = new Catalog();
    static SimpleCostModel costModel;
    static final SQLParser parser = new SQLParser();
    static LogicalPlanBuilder planBuilder;

    @BeforeAll
    static void setup() throws IOException {
        Path outputDir = Paths.get("target/generated-test-resources");
        if (!Files.exists(outputDir))
            Files.createDirectory(outputDir);

        try (PrintWriter pw = new PrintWriter(
                new File(outputDir.toFile(), "learned_customers.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,city:VARCHAR,age:INTEGER");
            pw.println("1,Alice,Seattle,30");
            pw.println("2,Bob,Portland,25");
            pw.println("3,Charlie,Seattle,35");
        }
        catalog.loadTableFromCSV("customers",
                "target/generated-test-resources/learned_customers.csv");

        try (PrintWriter pw = new PrintWriter(
                new File(outputDir.toFile(), "learned_products.csv"))) {
            pw.println("id:INTEGER,name:VARCHAR,category:VARCHAR,price:FLOAT");
            pw.println("1,Laptop,Electronics,999.99");
            pw.println("2,Mouse,Electronics,29.99");
            pw.println("3,Desk,Furniture,299.99");
        }
        catalog.loadTableFromCSV("products",
                "target/generated-test-resources/learned_products.csv");

        try (PrintWriter pw = new PrintWriter(
                new File(outputDir.toFile(), "learned_orders.csv"))) {
            pw.println("id:INTEGER,customer_id:INTEGER,product_id:INTEGER,total:FLOAT");
            pw.println("1,1,1,999.99");
            pw.println("2,2,2,29.99");
            pw.println("3,3,3,299.99");
        }
        catalog.loadTableFromCSV("orders",
                "target/generated-test-resources/learned_orders.csv");

        costModel = new SimpleCostModel(catalog);
        planBuilder = new LogicalPlanBuilder(catalog);
    }

    // --- Phase 1 tests ---

    @Test
    void hintSetProducesDifferentRuleSets() {
        // DEFAULT has all three rules: predicate pushdown, projection pushdown, filter merge
        assertEquals(3, HintSet.DEFAULT.getRules().size());

        // FORCE_NLJ applies the same rules as DEFAULT — only join algorithm differs
        assertEquals(3, HintSet.FORCE_NLJ.getRules().size());

        // NO_PUSHDOWN skips predicate and projection pushdown, keeps filter merge
        assertEquals(1, HintSet.NO_PUSHDOWN.getRules().size());

        // MINIMAL_OPT applies no rules at all
        assertEquals(0, HintSet.MINIMAL_OPT.getRules().size());

        // Join algorithm preference distinguishes DEFAULT from FORCE_NLJ
        assertTrue(HintSet.DEFAULT.preferHashJoin());
        assertFalse(HintSet.FORCE_NLJ.preferHashJoin());

        // The arm registry contains all five predefined hint sets
        assertEquals(5, HintSet.allHintSets().size());
    }

    @Test
    void planVariantGeneratorProducesMultiplePlans() {
        PlanVariantGenerator gen = new PlanVariantGenerator(catalog, costModel);
        LogicalNode plan = parse(
                "SELECT c.name, o.total FROM customers c "
                + "INNER JOIN orders o ON c.id = o.customer_id "
                + "WHERE c.city = 'Seattle'");

        Map<HintSet, PhysicalNode> variants = gen.generateVariants(plan, HintSet.allHintSets());

        // A join query must yield at least two distinct plans: one with hash join (DEFAULT)
        // and one with nested-loop join (FORCE_NLJ). In practice we get more.
        assertTrue(variants.size() > 1,
                "Join query should produce multiple distinct plan variants, got: " + variants.size());
    }

    @Test
    void planVariantGeneratorDeduplicates() {
        PlanVariantGenerator gen = new PlanVariantGenerator(catalog, costModel);
        // A scan-only query has no join, so hint sets that differ only in join-algorithm
        // preference (DEFAULT vs FORCE_NLJ, NO_PUSHDOWN vs NO_PUSHDOWN_NLJ) produce
        // structurally identical physical plans and should be deduplicated.
        LogicalNode plan = parse("SELECT id, name FROM customers");

        Map<HintSet, PhysicalNode> variants = gen.generateVariants(plan, HintSet.allHintSets());

        assertTrue(variants.size() < HintSet.allHintSets().size(),
                "Scan-only query should deduplicate hint sets that only differ in join preference. "
                + "Got " + variants.size() + " variants from "
                + HintSet.allHintSets().size() + " hint sets.");
    }

    @Test
    void planFeaturizerOutputsCorrectDimension() {
        PlanVariantGenerator gen = new PlanVariantGenerator(catalog, costModel);
        LogicalNode plan = parse("SELECT id, name FROM customers");
        Map<HintSet, PhysicalNode> variants =
                gen.generateVariants(plan, List.of(HintSet.DEFAULT));

        PhysicalNode physicalPlan = variants.values().iterator().next();
        double[] features = new PlanFeaturizer().featurize(physicalPlan);

        assertEquals(PlanFeaturizer.FEATURE_DIM, features.length,
                "Feature vector must be exactly " + PlanFeaturizer.FEATURE_DIM + " elements");
    }

    @Test
    void planFeaturizerDifferentPlansProduceDifferentVectors() {
        PlanVariantGenerator gen = new PlanVariantGenerator(catalog, costModel);
        // DEFAULT produces a hash join, FORCE_NLJ produces a nested-loop join —
        // the operator-type encoding in slot 0 must differ between the two.
        LogicalNode plan = parse(
                "SELECT c.name, o.total FROM customers c "
                + "INNER JOIN orders o ON c.id = o.customer_id");

        Map<HintSet, PhysicalNode> variants =
                gen.generateVariants(plan, List.of(HintSet.DEFAULT, HintSet.FORCE_NLJ));

        assertEquals(2, variants.size(),
                "DEFAULT (hash join) and FORCE_NLJ (nested-loop) must yield distinct plans");

        PlanFeaturizer featurizer = new PlanFeaturizer();
        var it = variants.values().iterator();
        double[] featuresA = featurizer.featurize(it.next());
        double[] featuresB = featurizer.featurize(it.next());

        assertFalse(Arrays.equals(featuresA, featuresB),
                "Hash join and nested-loop plans must produce different feature vectors");
    }

    // --- Helper ---

    private static LogicalNode parse(String sql) {
        AST.SelectStmt ast = parser.parse(sql);
        return planBuilder.build(ast);
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.deleteIfExists(Paths.get("target/generated-test-resources/learned_customers.csv"));
        Files.deleteIfExists(Paths.get("target/generated-test-resources/learned_products.csv"));
        Files.deleteIfExists(Paths.get("target/generated-test-resources/learned_orders.csv"));
    }
}
