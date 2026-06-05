package org.query.optimizer.learned.cardinality;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.CardinalityEstimator;
import org.query.optimizer.HeuristicCardinalityModel;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.learned.nn.SimpleNeuralNetwork;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalFilter;
import org.query.optimizer.parser.LogicalJoin;
import org.query.optimizer.parser.LogicalScan;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the cardinality-model abstraction (Phase 6, task P6-1): the heuristic
 * default, the logical-plan featurizer, and the learned model's fallback behavior.
 */
class CardinalityModelTest {

    private static final Catalog catalog = new Catalog();
    private static final String DATA_DIR = "target/generated-test-resources/p6-ce";

    @BeforeAll
    static void setup() throws IOException {
        Files.createDirectories(Paths.get(DATA_DIR));
        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "customers.csv"))) {
            pw.println("id:INTEGER,city:VARCHAR,age:INTEGER");
            for (int i = 1; i <= 8; i++) {
                pw.println(i + ",City" + (i % 4) + "," + (20 + i * 5));
            }
        }
        catalog.loadTableFromCSV("customers", DATA_DIR + "/customers.csv");

        try (PrintWriter pw = new PrintWriter(new File(DATA_DIR, "orders.csv"))) {
            pw.println("id:INTEGER,customer_id:INTEGER");
            for (int i = 1; i <= 12; i++) {
                pw.println(i + "," + ((i % 8) + 1));
            }
        }
        catalog.loadTableFromCSV("orders", DATA_DIR + "/orders.csv");
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.deleteIfExists(Paths.get(DATA_DIR + "/customers.csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR + "/orders.csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR));
    }

    // -------------------------------------------------------------------------
    // Plan builders
    // -------------------------------------------------------------------------

    private LogicalScan customers() {
        return new LogicalScan("customers");
    }

    private LogicalFilter ageOver40() {
        return new LogicalFilter(
                new Expression.BinaryOp(Expression.BinaryOp.Operator.GT,
                        new Expression.ColumnRef("customers", "age"),
                        new Expression.Literal<>(40)),
                customers());
    }

    private LogicalJoin customersJoinOrders() {
        return new LogicalJoin(customers(), new LogicalScan("orders"),
                LogicalJoin.JoinType.INNER,
                new Expression.BinaryOp(Expression.BinaryOp.Operator.EQ,
                        new Expression.ColumnRef("customers", "id"),
                        new Expression.ColumnRef("orders", "customer_id")));
    }

    // -------------------------------------------------------------------------
    // Heuristic model
    // -------------------------------------------------------------------------

    @Test
    void heuristicModelMatchesUnderlyingEstimator() {
        CardinalityEstimator estimator = new CardinalityEstimator(catalog);
        HeuristicCardinalityModel model = new HeuristicCardinalityModel(catalog);

        for (LogicalNode node : new LogicalNode[]{customers(), ageOver40(), customersJoinOrders()}) {
            assertEquals(estimator.estimate(node), model.estimate(node));
        }
    }

    // -------------------------------------------------------------------------
    // Featurizer
    // -------------------------------------------------------------------------

    @Test
    void featurizerProducesFixedDimensionVector() {
        CardinalityFeaturizer featurizer = new CardinalityFeaturizer(catalog);
        double[] features = featurizer.featurize(customersJoinOrders());
        assertEquals(CardinalityFeaturizer.FEATURE_DIM, features.length);
    }

    @Test
    void featurizerCountsOperatorsAndIncludesHeuristic() {
        CardinalityFeaturizer featurizer = new CardinalityFeaturizer(catalog);
        CardinalityEstimator estimator = new CardinalityEstimator(catalog);

        double[] join = featurizer.featurize(customersJoinOrders());
        assertEquals(2, join[3], "two scans");                 // scans
        assertEquals(1, join[7], "one join");                  // joins
        assertEquals(Math.log1p(estimator.estimate(customersJoinOrders())), join[11], 1e-9);

        double[] filter = featurizer.featurize(ageOver40());
        assertEquals(1, filter[3], "one scan");
        assertEquals(1, filter[4], "one filter");
        assertEquals(1, filter[6], "one range filter (age > 40)");
        assertEquals(0, filter[5], "no equality filters");
    }

    @Test
    void supportsStandardShapesAndRejectsNull() {
        CardinalityFeaturizer featurizer = new CardinalityFeaturizer(catalog);
        assertTrue(featurizer.supports(customers()));
        assertTrue(featurizer.supports(ageOver40()));
        assertTrue(featurizer.supports(customersJoinOrders()));
        assertFalse(featurizer.supports(null));
    }

    // -------------------------------------------------------------------------
    // Learned model fallback / prediction path
    // -------------------------------------------------------------------------

    @Test
    void learnedModelWithoutNetworkFallsBackToHeuristic() {
        CardinalityFeaturizer featurizer = new CardinalityFeaturizer(catalog);
        HeuristicCardinalityModel heuristic = new HeuristicCardinalityModel(catalog);
        LearnedCardinalityModel learned = new LearnedCardinalityModel(null, featurizer, heuristic);

        assertFalse(learned.hasModel());
        for (LogicalNode node : new LogicalNode[]{customers(), ageOver40(), customersJoinOrders()}) {
            assertEquals(heuristic.estimate(node), learned.estimate(node),
                    "with no network, the learned model must equal the heuristic");
        }
    }

    @Test
    void learnedModelWithNetworkProducesPositiveEstimate() {
        CardinalityFeaturizer featurizer = new CardinalityFeaturizer(catalog);
        HeuristicCardinalityModel heuristic = new HeuristicCardinalityModel(catalog);
        SimpleNeuralNetwork net = new SimpleNeuralNetwork(
                new int[]{CardinalityFeaturizer.FEATURE_DIM, 8, 1}, 0.01, new Random(1));
        LearnedCardinalityModel learned = new LearnedCardinalityModel(net, featurizer, heuristic);

        assertTrue(learned.hasModel());
        for (LogicalNode node : new LogicalNode[]{customers(), ageOver40(), customersJoinOrders()}) {
            assertTrue(learned.estimate(node) >= 1, "estimate must be a positive row count");
        }
    }
}
