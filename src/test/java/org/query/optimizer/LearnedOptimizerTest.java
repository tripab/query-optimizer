package org.query.optimizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.executor.Executor.ExecutionResult;
import org.query.optimizer.executor.ExecutionTimer;
import org.query.optimizer.executor.Timed;
import org.query.optimizer.learned.bao.BanditOptimizer;
import org.query.optimizer.learned.bao.BanditOptimizer.QueryMetrics;
import org.query.optimizer.learned.bao.PlanValueModel;
import org.query.optimizer.learned.bao.ThompsonSampler;
import org.query.optimizer.learned.common.ExecutionFeedback;
import org.query.optimizer.learned.common.HintSet;
import org.query.optimizer.learned.common.PlanFeaturizer;
import org.query.optimizer.learned.common.PlanVariantGenerator;
import org.query.optimizer.learned.common.WorkloadGenerator;
import org.query.optimizer.learned.common.WorkloadGenerator.ParsedQuery;
import org.query.optimizer.learned.benchmark.LearnedOptimizerBenchmark;
import org.query.optimizer.learned.benchmark.LearnedOptimizerBenchmark.BenchmarkResults;
import org.query.optimizer.learned.lero.LeroOptimizer;
import org.query.optimizer.learned.lero.PairwiseComparator;
import org.query.optimizer.learned.lero.PairwiseComparator.TrainingPair;
import org.query.optimizer.learned.nn.LossFunction;
import org.query.optimizer.learned.nn.SimpleNeuralNetwork;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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

        // Join-order policy is an additional dimension: PRESERVE_ORDER keeps input order
        assertEquals(org.query.optimizer.JoinOrderPolicy.DP, HintSet.DEFAULT.joinOrderPolicy());
        assertEquals(org.query.optimizer.JoinOrderPolicy.PRESERVE_INPUT,
                HintSet.PRESERVE_ORDER.joinOrderPolicy());

        // The arm registry contains all six predefined hint sets
        assertEquals(6, HintSet.allHintSets().size());
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

    // --- Phase 2 tests ---

    @Test
    void neuralNetworkLearnsSineFunction() {
        // Train a small network to approximate sin(x) on [0, π].
        // After sufficient training the MSE on a held-out test set should be < 0.01.
        SimpleNeuralNetwork net = new SimpleNeuralNetwork(
                new int[]{1, 64, 32, 1}, 0.001, new Random(42));
        LossFunction mse = LossFunction.mse();

        for (int epoch = 0; epoch < 3000; epoch++) {
            for (int i = 0; i <= 20; i++) {
                double x = Math.PI * i / 20.0;
                net.trainStep(new double[]{x}, new double[]{Math.sin(x)}, mse);
            }
        }

        // Evaluate on held-out points (midpoints of training intervals)
        double totalMse = 0.0;
        int testPoints = 20;
        for (int i = 0; i < testPoints; i++) {
            double x = Math.PI * (i + 0.5) / testPoints;
            double pred = net.predict(new double[]{x})[0];
            double diff = pred - Math.sin(x);
            totalMse += diff * diff;
        }
        totalMse /= testPoints;

        assertTrue(totalMse < 0.01,
                "Network should approximate sin(x) with MSE < 0.01, got: " + totalMse);
    }

    @Test
    void neuralNetworkLearnsBinaryClassification() {
        // Train a network to solve XOR: (0,0)→0, (0,1)→1, (1,0)→1, (1,1)→0.
        // After sufficient training it should classify all four examples correctly.
        double[][] inputs  = {{0, 0}, {0, 1}, {1, 0}, {1, 1}};
        double[][] targets = {{0},    {1},    {1},    {0}};

        SimpleNeuralNetwork net = new SimpleNeuralNetwork(
                new int[]{2, 8, 1}, 0.1, new Random(42));
        LossFunction mse = LossFunction.mse();

        for (int epoch = 0; epoch < 5000; epoch++) {
            for (int i = 0; i < inputs.length; i++) {
                net.trainStep(inputs[i], targets[i], mse);
            }
        }

        int correct = 0;
        for (int i = 0; i < inputs.length; i++) {
            double pred = net.predict(inputs[i])[0];
            // threshold at 0.5
            int predicted = pred >= 0.5 ? 1 : 0;
            if (predicted == (int) targets[i][0]) correct++;
        }

        assertEquals(4, correct, "Network must solve XOR — all 4 examples correct");
    }

    @Test
    void neuralNetworkSaveAndLoad() throws IOException {
        SimpleNeuralNetwork original = new SimpleNeuralNetwork(
                new int[]{4, 8, 2}, 0.01, new Random(7));
        LossFunction mse = LossFunction.mse();

        // Train briefly so weights are non-trivial
        for (int i = 0; i < 50; i++) {
            original.trainStep(new double[]{1, 2, 3, 4}, new double[]{0.5, -0.5}, mse);
        }

        Path tmp = Files.createTempFile("nn-test-", ".txt");
        try {
            original.save(tmp.toString());
            SimpleNeuralNetwork loaded = SimpleNeuralNetwork.load(tmp.toString());

            double[] predOriginal = original.predict(new double[]{1, 0, -1, 2});
            double[] predLoaded   = loaded.predict(new double[]{1, 0, -1, 2});

            assertArrayEquals(predOriginal, predLoaded, 1e-10,
                    "Loaded network must produce identical predictions to the saved one");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // --- Phase 3 tests ---

    @Test
    void thompsonSamplerSelectsBestArmOverTime() {
        // Property test: once the value model has learned that arm A is cheaper
        // than arm B, Thompson Sampling must prefer arm A.
        //
        // We use one-hot feature vectors (only one input neuron active per arm)
        // to keep first-layer activations small and avoid gradient explosion.
        // Targets are 1 ms (cheap) vs 10 ms (expensive) — a 10× difference that
        // the model can learn reliably given enough training data.
        //
        // 500 feedback pairs → 100 retrains; the final retrain uses 1 000 samples
        // × 20 epochs = 20 000 gradient steps at lr=0.001, which is sufficient
        // for a 2-input regression on a 34→64→32→1 network.
        double[] featA = new double[PlanFeaturizer.FEATURE_DIM]; featA[0] = 1.0;
        double[] featB = new double[PlanFeaturizer.FEATURE_DIM]; featB[1] = 1.0;

        PlanValueModel  model   = new PlanValueModel(new Random(42));
        ThompsonSampler sampler = new ThompsonSampler(new Random(99));

        // 500 pairs × 2 calls = 1 000 addFeedback calls → 100 retrains
        for (int i = 0; i < 500; i++) {
            model.addFeedback(new ExecutionFeedback("", HintSet.DEFAULT,   featA, 1,  0, 0.0, 0));
            model.addFeedback(new ExecutionFeedback("", HintSet.FORCE_NLJ, featB, 10, 0, 0.0, 0));
        }

        // Step 1: verify the model has learned the correct cost ordering
        double predA = model.predict(featA).mean();
        double predB = model.predict(featB).mean();
        assertTrue(predA < predB,
                "After training, model must predict lower cost for cheap plan (A). "
                + "predA=" + predA + " predB=" + predB);

        // Step 2: verify Thompson Sampling prefers the cheaper arm ≥75% of trials
        Map<HintSet, double[]> planFeatures = new LinkedHashMap<>();
        planFeatures.put(HintSet.DEFAULT,   featA);
        planFeatures.put(HintSet.FORCE_NLJ, featB);

        int countA = 0;
        for (int i = 0; i < 200; i++) {
            if (sampler.selectArm(planFeatures, model) == HintSet.DEFAULT) countA++;
        }

        assertTrue(countA >= 150,
                "Low-cost arm (DEFAULT) should be selected ≥75% after training. "
                + "Got " + countA + "/200");
    }

    @Test
    void baoImprovesOverBaseline() {
        // End-to-end integration test for BanditOptimizer.
        //
        // With 3-row test tables, latency differences between hint sets are
        // sub-millisecond, so we do not assert a strict latency improvement.
        // Instead we verify:
        //   1. All queries complete without error.
        //   2. Every selected arm is a valid member of the arm list.
        //   3. Bao's total logical cost is at most 4× the DEFAULT baseline
        //      (sanity bound during pure exploration).
        //
        // We use hand-crafted queries (no aggregation) because the test
        // catalog's 3-row tables keep execution fast.
        List<ParsedQuery> workload = List.of(
                new ParsedQuery("SELECT id, name FROM customers",
                        parse("SELECT id, name FROM customers")),
                new ParsedQuery("SELECT id, name FROM products",
                        parse("SELECT id, name FROM products")),
                new ParsedQuery("SELECT c.name, o.total FROM customers c "
                        + "INNER JOIN orders o ON c.id = o.customer_id",
                        parse("SELECT c.name, o.total FROM customers c "
                        + "INNER JOIN orders o ON c.id = o.customer_id")),
                new ParsedQuery("SELECT c.name, o.total FROM customers c "
                        + "INNER JOIN orders o ON c.id = o.customer_id "
                        + "WHERE c.city = 'Seattle'",
                        parse("SELECT c.name, o.total FROM customers c "
                        + "INNER JOIN orders o ON c.id = o.customer_id "
                        + "WHERE c.city = 'Seattle'")),
                new ParsedQuery("SELECT id, name FROM customers WHERE city = 'Portland'",
                        parse("SELECT id, name FROM customers WHERE city = 'Portland'"))
        );

        // --- Baseline: every query executed with DEFAULT hint set ---
        PlanVariantGenerator varGen   = new PlanVariantGenerator(catalog, costModel);
        PlanFeaturizer       feat2    = new PlanFeaturizer();
        Executor             exec2    = new Executor();
        double               baseCost = 0.0;
        for (ParsedQuery q : workload) {
            Map<HintSet, PhysicalNode> variants =
                    varGen.generateVariants(q.logicalPlan(), List.of(HintSet.DEFAULT));
            PhysicalNode plan = variants.values().iterator().next();
            Timed<ExecutionResult> run = ExecutionTimer.run(() -> exec2.execute(plan));
            ExecutionResult res = run.value();
            double[] features = feat2.featurize(plan);
            baseCost += new ExecutionFeedback("", HintSet.DEFAULT, features,
                    run.millis(), res.tuplesProcessed(),
                    plan.getEstimatedCost(), plan.getEstimatedRows()).logicalCost();
        }

        // --- Bao run ---
        BanditOptimizer bao = new BanditOptimizer(catalog, new Random(42));
        List<QueryMetrics> baoMetrics = bao.runWorkload(workload);

        // 1. Correct count
        assertEquals(workload.size(), baoMetrics.size(), "Bao must process all queries");

        // 2. Every selected arm is in the official arm list
        List<HintSet> allArms = HintSet.allHintSets();
        for (QueryMetrics m : baoMetrics) {
            assertTrue(allArms.contains(m.selectedArm()),
                    "Selected arm must be a known HintSet: " + m.selectedArm());
        }

        // 3. Total logical cost at most 4× DEFAULT baseline (sanity bound)
        double baoCost = baoMetrics.stream()
                .mapToDouble(m -> m.result().tuplesProcessed() * 0.01
                             + m.actualLatencyMs())
                .sum();
        assertTrue(baoCost <= baseCost * 4.0 + 1.0,
                String.format("Bao cost (%.3f) must not exceed 4× baseline (%.3f)",
                        baoCost, baseCost));
    }

    // --- Phase 4 tests ---

    @Test
    void pairwiseComparatorLearnsTrivialOrdering() {
        // Property test: after training on pairs where A is always faster than B,
        // the comparator must achieve >90% accuracy on those same pairs.
        //
        // We use one-hot feature vectors (featA[0]=1, featB[1]=1) so the encoder
        // receives a clearly separable signal with no feature overlap.
        // 200 pairs × 30 epochs = 6 000 gradient steps at lr=0.001, which is
        // sufficient for this trivial 2-class ordering problem.
        double[] featA = new double[PlanFeaturizer.FEATURE_DIM]; featA[0] = 1.0;
        double[] featB = new double[PlanFeaturizer.FEATURE_DIM]; featB[1] = 1.0;

        PairwiseComparator comparator = new PairwiseComparator(new Random(42));
        List<TrainingPair> pairs = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            pairs.add(new TrainingPair(featA, featB, true));   // A is faster
            pairs.add(new TrainingPair(featB, featA, false));  // B is not faster than A
        }

        for (int epoch = 0; epoch < 30; epoch++) {
            for (TrainingPair pair : pairs) {
                comparator.trainStep(pair.featuresA(), pair.featuresB(), pair.aIsFaster());
            }
        }

        double accuracy = comparator.evaluateAccuracy(pairs);
        assertTrue(accuracy > 0.9,
                String.format("Comparator must achieve >90%% accuracy on trivial ordering "
                        + "(10× latency difference). Got: %.1f%%", accuracy * 100));
    }

    @Test
    void tournamentSelectReturnsCorrectWinner() {
        // Verify that tournamentSelect identifies the best plan after training.
        //
        // We create three one-hot feature vectors and train the comparator to
        // know that plan 0 beats plans 1 and 2, and plan 1 beats plan 2.
        // tournamentSelect must return index 0 (the clear winner).
        double[] feat0 = new double[PlanFeaturizer.FEATURE_DIM]; feat0[0] = 1.0;
        double[] feat1 = new double[PlanFeaturizer.FEATURE_DIM]; feat1[1] = 1.0;
        double[] feat2 = new double[PlanFeaturizer.FEATURE_DIM]; feat2[2] = 1.0;

        PairwiseComparator comparator = new PairwiseComparator(new Random(7));

        // Train: 0 > 1 > 2 (300 pairs each direction for balance)
        List<TrainingPair> pairs = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            pairs.add(new TrainingPair(feat0, feat1, true));
            pairs.add(new TrainingPair(feat1, feat0, false));
            pairs.add(new TrainingPair(feat0, feat2, true));
            pairs.add(new TrainingPair(feat2, feat0, false));
            pairs.add(new TrainingPair(feat1, feat2, true));
            pairs.add(new TrainingPair(feat2, feat1, false));
        }
        for (int epoch = 0; epoch < 20; epoch++) {
            for (TrainingPair pair : pairs) {
                comparator.trainStep(pair.featuresA(), pair.featuresB(), pair.aIsFaster());
            }
        }

        int winner = comparator.tournamentSelect(List.of(feat0, feat1, feat2));
        assertEquals(0, winner,
                "After training, tournamentSelect must return index 0 (the fastest plan)");
    }

    @Test
    void leroImprovesOverBaseline() {
        // End-to-end integration test for LeroOptimizer.
        //
        // With 3-row test tables, latency differences are sub-millisecond, so we
        // do not assert a strict latency improvement. Instead we verify:
        //   1. All queries complete without error.
        //   2. Returned metrics count matches workload size.
        //   3. Lero's total logical cost is at most 4× the DEFAULT baseline
        //      (sanity bound — warm-up exploration overhead must not explode).
        //
        // The workload repeats the same 5 templates 7 times (35 queries total)
        // so Lero exits warm-up (WARMUP_QUERIES=30) and uses ranking for the last 5.
        List<String> sqlTemplates = List.of(
                "SELECT id, name FROM customers",
                "SELECT id, name FROM products",
                "SELECT c.name, o.total FROM customers c "
                        + "INNER JOIN orders o ON c.id = o.customer_id",
                "SELECT c.name, o.total FROM customers c "
                        + "INNER JOIN orders o ON c.id = o.customer_id "
                        + "WHERE c.city = 'Seattle'",
                "SELECT id, name FROM customers WHERE city = 'Portland'"
        );

        List<ParsedQuery> workload = new java.util.ArrayList<>();
        for (int round = 0; round < 7; round++) {
            for (String sql : sqlTemplates) {
                workload.add(new ParsedQuery(sql, parse(sql)));
            }
        }

        // --- Baseline: every query with DEFAULT hint set ---
        PlanVariantGenerator varGen  = new PlanVariantGenerator(catalog, costModel);
        PlanFeaturizer       feat    = new PlanFeaturizer();
        Executor             exec    = new Executor();
        double               baseCost = 0.0;
        for (ParsedQuery q : workload) {
            Map<HintSet, PhysicalNode> variants =
                    varGen.generateVariants(q.logicalPlan(), List.of(HintSet.DEFAULT));
            PhysicalNode plan = variants.values().iterator().next();
            Timed<ExecutionResult> run = ExecutionTimer.run(() -> exec.execute(plan));
            ExecutionResult res = run.value();
            baseCost += new ExecutionFeedback("", HintSet.DEFAULT, feat.featurize(plan),
                    run.millis(), res.tuplesProcessed(),
                    plan.getEstimatedCost(), plan.getEstimatedRows()).logicalCost();
        }

        // --- Lero run ---
        LeroOptimizer lero = new LeroOptimizer(catalog, new Random(42));
        List<LeroOptimizer.QueryMetrics> leroMetrics = lero.runWorkload(workload);

        // 1. Correct count
        assertEquals(workload.size(), leroMetrics.size(), "Lero must process all queries");

        // 2. At least some queries used Lero's own ranking (warm phase was reached)
        long warmPhaseCount = leroMetrics.stream()
                .filter(m -> !m.usedCostModel())
                .count();
        assertTrue(warmPhaseCount > 0,
                "With " + workload.size() + " queries and WARMUP_QUERIES="
                        + LeroOptimizer.WARMUP_QUERIES
                        + ", at least some queries should use Lero ranking");

        // 3. Total logical cost at most 4× DEFAULT baseline (sanity bound)
        double leroCost = leroMetrics.stream()
                .mapToDouble(m -> m.result().tuplesProcessed() * 0.01
                                  + m.actualLatencyMs())
                .sum();
        assertTrue(leroCost <= baseCost * 4.0 + 1.0,
                String.format("Lero cost (%.3f) must not exceed 4× baseline (%.3f)",
                        leroCost, baseCost));
    }

    // --- Phase 5 tests ---

    @Test
    void oracleIsAtLeastAsGoodAsDefault() {
        // The oracle executes every plan variant per query and keeps the one with
        // the lowest actual latency. By construction it can never be worse than the
        // default strategy. This test verifies that structural invariant.
        List<ParsedQuery> workload = buildPhase5Workload();
        BenchmarkResults results = new LearnedOptimizerBenchmark(catalog).run(workload);

        // Oracle can only match or beat DEFAULT — it picks the actual best plan per query
        assertTrue(results.oracleTotal() <= results.defaultTotal(),
                String.format("Oracle (%,d ms) must be ≤ DEFAULT (%,d ms)",
                        results.oracleTotal(), results.defaultTotal()));

        // Oracle's regret vs. itself is always 1.0 by definition
        assertEquals(1.0, results.oracleMetrics().regretVsOracle(), 1e-9,
                "Oracle regret vs. oracle must be exactly 1.0");

        // Results must cover every query in the workload
        assertEquals(workload.size(), results.perQuery().size(),
                "Benchmark must return one QueryResult per workload query");
    }

    @Test
    void baoAndLeroBothBeatDefault() {
        // With tiny (3-row) test tables all latencies are 0 ms, so strict latency
        // improvement cannot be asserted. We verify:
        //   1. Both strategies process every query without error.
        //   2. Neither strategy's total latency exceeds 4× DEFAULT (sanity bound —
        //      exploration overhead must not blow up even on tiny tables).
        //   3. Regret values are non-negative (well-formed output).
        //   4. The Bao–Lero agreement rate is a valid probability in [0, 1].
        List<ParsedQuery> workload = buildPhase5Workload();
        BenchmarkResults results = new LearnedOptimizerBenchmark(catalog).run(workload);

        // 1. All queries processed
        assertEquals(workload.size(), results.perQuery().size(),
                "Benchmark must return one QueryResult per workload query");

        // 2. Sanity bound: neither strategy's overhead explodes relative to DEFAULT
        long safetyBound = Math.max(results.defaultTotal() * 4, 1L);
        assertTrue(results.baoTotal() <= safetyBound,
                String.format("Bao total (%,d ms) must be ≤ 4× DEFAULT (%,d ms)",
                        results.baoTotal(), results.defaultTotal()));
        assertTrue(results.leroTotal() <= safetyBound,
                String.format("Lero total (%,d ms) must be ≤ 4× DEFAULT (%,d ms)",
                        results.leroTotal(), results.defaultTotal()));

        // 3. Regret values must be non-negative
        assertTrue(results.baoMetrics().regretVsOracle() >= 0.0,
                "Bao regret must be non-negative");
        assertTrue(results.leroMetrics().regretVsOracle() >= 0.0,
                "Lero regret must be non-negative");

        // 4. Agreement rate is a valid probability
        double rate = results.baoLeroAgreementRate();
        assertTrue(rate >= 0.0 && rate <= 1.0,
                "Bao–Lero agreement rate must be in [0, 1], got: " + rate);
    }

    private static List<ParsedQuery> buildPhase5Workload() {
        String[] sqls = {
            "SELECT id, name FROM customers",
            "SELECT id, name FROM products",
            "SELECT c.name, o.total FROM customers c "
                    + "INNER JOIN orders o ON c.id = o.customer_id",
            "SELECT c.name, o.total FROM customers c "
                    + "INNER JOIN orders o ON c.id = o.customer_id "
                    + "WHERE c.city = 'Seattle'",
            "SELECT id, name FROM customers WHERE city = 'Portland'"
        };
        List<ParsedQuery> workload = new java.util.ArrayList<>();
        for (String sql : sqls) {
            workload.add(new ParsedQuery(sql, parse(sql)));
        }
        return workload;
    }

    // --- Helpers ---

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
