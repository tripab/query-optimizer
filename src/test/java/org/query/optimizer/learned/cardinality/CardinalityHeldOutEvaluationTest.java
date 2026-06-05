package org.query.optimizer.learned.cardinality;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.CardinalityModel;
import org.query.optimizer.HeuristicCardinalityModel;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.learned.cardinality.CardinalityTrainingData.Example;
import org.query.optimizer.learned.nn.SimpleNeuralNetwork;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalPlanBuilder;
import org.query.optimizer.parser.SQLParser;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Held-out evaluation of the learned cardinality model versus the heuristic
 * (Phase 6, task P6-5).
 *
 * <p>The model is trained on one workload and evaluated, by Q-error, on a disjoint
 * held-out workload of the same query shapes with different constants. This checks
 * that the learned model <em>generalizes</em> to unseen queries — it must beat an
 * untrained network and stay competitive with the heuristic — rather than merely
 * memorizing its training set.
 */
class CardinalityHeldOutEvaluationTest {

    private static final Catalog catalog = new Catalog();
    private static final SQLParser parser = new SQLParser();
    private static LogicalPlanBuilder planBuilder;
    private static final String DATA_DIR = "target/generated-test-resources/p6-heldout";

    @BeforeAll
    static void setup() throws IOException {
        Files.createDirectories(Paths.get(DATA_DIR));
        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "customers.csv"))) {
            pw.println("id:INTEGER,city:VARCHAR,age:INTEGER");
            for (int i = 1; i <= 30; i++) {
                pw.println(i + ",City" + (i % 5) + "," + (20 + (i % 45)));
            }
        }
        catalog.loadTableFromCSV("customers", DATA_DIR + "/customers.csv");

        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "orders.csv"))) {
            pw.println("id:INTEGER,customer_id:INTEGER,amount:FLOAT");
            for (int i = 1; i <= 60; i++) {
                pw.println(i + "," + ((i % 30) + 1) + "," + String.format("%.2f", 10.0 + (i % 100)));
            }
        }
        catalog.loadTableFromCSV("orders", DATA_DIR + "/orders.csv");

        planBuilder = new LogicalPlanBuilder(catalog);
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.deleteIfExists(Paths.get(DATA_DIR + "/customers.csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR + "/orders.csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR));
    }

    private List<LogicalNode> plans(List<String> sql) {
        List<LogicalNode> plans = new ArrayList<>();
        for (String s : sql) {
            plans.add(planBuilder.build(parser.parse(s)));
        }
        return plans;
    }

    private List<LogicalNode> trainingQueries() {
        return plans(List.of(
                "SELECT id FROM customers WHERE age > 30",
                "SELECT id FROM customers WHERE age > 50",
                "SELECT id FROM customers WHERE city = 'City1'",
                "SELECT id, amount FROM orders WHERE amount > 30.00",
                "SELECT id, amount FROM orders WHERE amount > 70.00",
                "SELECT customers.id, orders.amount FROM customers " +
                        "INNER JOIN orders ON customers.id = orders.customer_id WHERE orders.amount > 40.00",
                "SELECT city, COUNT(*) FROM customers GROUP BY city"));
    }

    private List<LogicalNode> heldOutQueries() {
        // Same shapes, different constants/columns — unseen during training.
        return plans(List.of(
                "SELECT id FROM customers WHERE age > 40",
                "SELECT id FROM customers WHERE city = 'City2'",
                "SELECT id, amount FROM orders WHERE amount > 55.00",
                "SELECT customers.id, orders.amount FROM customers " +
                        "INNER JOIN orders ON customers.id = orders.customer_id WHERE orders.amount > 60.00"));
    }

    @Test
    void learnedModelGeneralizesToHeldOutQueries() {
        CardinalityTrainingData dataGen = new CardinalityTrainingData(catalog);
        CardinalityFeaturizer featurizer = dataGen.featurizer();
        HeuristicCardinalityModel heuristic = new HeuristicCardinalityModel(catalog);

        List<Example> trainingExamples = dataGen.generate(trainingQueries());
        List<Example> heldOutExamples = dataGen.generate(heldOutQueries());
        assertFalse(heldOutExamples.isEmpty());

        SimpleNeuralNetwork trainedNet =
                CardinalityModelTrainer.train(trainingExamples, 800, 0.01, 21);
        SimpleNeuralNetwork untrainedNet =
                new SimpleNeuralNetwork(CardinalityModelTrainer.layerSizes(), 0.01, new Random(21));

        CardinalityModel learned = new LearnedCardinalityModel(trainedNet, featurizer, heuristic);
        CardinalityModel untrained = new LearnedCardinalityModel(untrainedNet, featurizer, heuristic);

        QErrorStats.Summary learnedQ = QErrorStats.evaluate(learned, heldOutExamples);
        QErrorStats.Summary untrainedQ = QErrorStats.evaluate(untrained, heldOutExamples);
        QErrorStats.Summary heuristicQ = QErrorStats.evaluate(heuristic, heldOutExamples);

        // 1. Training generalizes: the trained model beats an untrained one on unseen queries.
        assertTrue(learnedQ.mean() < untrainedQ.mean(),
                "trained model must generalize better than untrained on held-out data: "
                        + "learned=" + learnedQ + " untrained=" + untrainedQ);

        // 2. The learned model stays competitive with the strong heuristic baseline.
        assertTrue(learnedQ.median() <= heuristicQ.median() * 2.0 + 1e-9,
                "learned held-out median q-error must be competitive with heuristic: "
                        + "learned=" + learnedQ + " heuristic=" + heuristicQ);

        // 3. Predictions stay in a sane range (no blow-ups) on unseen queries.
        assertTrue(Double.isFinite(learnedQ.max()) && learnedQ.median() <= 5.0,
                "learned held-out q-error should be bounded: " + learnedQ);
    }
}
