package org.query.optimizer.learned.cardinality;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.HeuristicCardinalityModel;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.learned.cardinality.CardinalityTrainingData.Example;
import org.query.optimizer.learned.nn.SimpleNeuralNetwork;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalPlanBuilder;
import org.query.optimizer.parser.LogicalScan;
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
 * Tests for cardinality-model training data generation, training, and Q-error
 * evaluation (Phase 6, task P6-2).
 */
class CardinalityTrainingTest {

    private static final Catalog catalog = new Catalog();
    private static final SQLParser parser = new SQLParser();
    private static LogicalPlanBuilder planBuilder;
    private static final String DATA_DIR = "target/generated-test-resources/p6-train";

    @BeforeAll
    static void setup() throws IOException {
        Files.createDirectories(Paths.get(DATA_DIR));
        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "customers.csv"))) {
            pw.println("id:INTEGER,city:VARCHAR,age:INTEGER");
            for (int i = 1; i <= 20; i++) {
                pw.println(i + ",City" + (i % 4) + "," + (20 + (i % 30)));
            }
        }
        catalog.loadTableFromCSV("customers", DATA_DIR + "/customers.csv");

        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "orders.csv"))) {
            pw.println("id:INTEGER,customer_id:INTEGER,amount:FLOAT");
            for (int i = 1; i <= 40; i++) {
                pw.println(i + "," + ((i % 20) + 1) + "," + String.format("%.2f", 10.0 + (i % 100)));
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

    private List<LogicalNode> workload() {
        List<String> sql = List.of(
                "SELECT id, city FROM customers WHERE age > 40",
                "SELECT id FROM customers WHERE city = 'City2'",
                "SELECT id, amount FROM orders WHERE amount > 50.00",
                "SELECT customers.id, orders.amount FROM customers " +
                        "INNER JOIN orders ON customers.id = orders.customer_id WHERE orders.amount > 50.00",
                "SELECT city, COUNT(*) FROM customers GROUP BY city");
        List<LogicalNode> plans = new ArrayList<>();
        for (String s : sql) {
            plans.add(planBuilder.build(parser.parse(s)));
        }
        return plans;
    }

    // -------------------------------------------------------------------------
    // Q-error metric
    // -------------------------------------------------------------------------

    @Test
    void qErrorIsSymmetricAndScaleFree() {
        assertEquals(1.0, QErrorStats.qError(100, 100), 1e-9);
        assertEquals(2.0, QErrorStats.qError(200, 100), 1e-9);
        assertEquals(2.0, QErrorStats.qError(50, 100), 1e-9);
        assertEquals(1.0, QErrorStats.qError(0, 0), 1e-9, "floored at 1 on both sides");
    }

    // -------------------------------------------------------------------------
    // Training data labels are the exact executed cardinalities
    // -------------------------------------------------------------------------

    @Test
    void trainingExamplesAreLabelledWithExactCardinalities() {
        CardinalityTrainingData data = new CardinalityTrainingData(catalog);
        List<Example> examples = data.generate(workload());

        assertFalse(examples.isEmpty());

        // Every base-table scan example must be labelled with the table's row count.
        long customerScans = 0;
        for (Example ex : examples) {
            if (ex.subplan() instanceof LogicalScan scan && scan.getTableName().equals("customers")) {
                assertEquals(20, ex.actualCardinality(), "customers scan cardinality");
                customerScans++;
            }
        }
        assertTrue(customerScans > 0, "workload should contain customer scans");
    }

    // -------------------------------------------------------------------------
    // Training reduces Q-error
    // -------------------------------------------------------------------------

    @Test
    void trainingReducesQErrorVersusUntrainedNetwork() {
        CardinalityTrainingData data = new CardinalityTrainingData(catalog);
        List<Example> examples = data.generate(workload());
        CardinalityFeaturizer featurizer = data.featurizer();
        HeuristicCardinalityModel heuristic = new HeuristicCardinalityModel(catalog);

        SimpleNeuralNetwork untrained =
                new SimpleNeuralNetwork(CardinalityModelTrainer.layerSizes(), 0.01, new Random(13));
        SimpleNeuralNetwork trained =
                CardinalityModelTrainer.train(examples, 500, 0.01, 13);

        QErrorStats.Summary untrainedQ = QErrorStats.evaluate(
                new LearnedCardinalityModel(untrained, featurizer, heuristic), examples);
        QErrorStats.Summary trainedQ = QErrorStats.evaluate(
                new LearnedCardinalityModel(trained, featurizer, heuristic), examples);

        assertTrue(trainedQ.mean() < untrainedQ.mean(),
                "training must reduce mean q-error: trained=" + trainedQ + " untrained=" + untrainedQ);
        // After training the model should be in a sane range on its own training data.
        assertTrue(trainedQ.median() <= 3.0,
                "trained median q-error should be small: " + trainedQ);
    }

    @Test
    void evaluateRejectsEmptyExampleSet() {
        HeuristicCardinalityModel heuristic = new HeuristicCardinalityModel(catalog);
        assertThrows(IllegalArgumentException.class,
                () -> QErrorStats.evaluate(heuristic, List.of()));
    }
}
