package org.query.optimizer.learned.lero;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.learned.common.DataGenerator;
import org.query.optimizer.learned.common.WorkloadGenerator;
import org.query.optimizer.learned.common.WorkloadGenerator.ParsedQuery;
import org.query.optimizer.learned.lero.PairwiseComparator.TrainingPair;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lero's training pairs must carry real signal: labeled by deterministic
 * logical cost with near-ties dropped. Raw nanosecond labels made
 * sub-millisecond plan pairs coin flips, and training on those contradictory
 * labels drove the comparator into order-blind saturation — exactly 50%
 * accuracy — from roughly query 100 onward in both observed demo runs.
 */
public class PlanExplorerPairQualityTest {

    static Catalog catalog = new Catalog();
    static List<ParsedQuery> workload;

    @BeforeAll
    static void setup() {
        DataGenerator.generate(catalog, 1);
        workload = new WorkloadGenerator(catalog, 42L).generateWorkload(40);
    }

    @Test
    void pairsAreBalancedAndDeterministicallyLabeled() {
        PlanExplorer explorer = new PlanExplorer(catalog);

        for (int i = 0; i < 10; i++) {
            List<TrainingPair> pairs = explorer.exploreQuery(workload.get(i).logicalPlan());
            // Both directions of every comparison are emitted, with flipped labels
            assertEquals(0, pairs.size() % 2);
            for (int k = 0; k < pairs.size(); k += 2) {
                assertEquals(pairs.get(k).aIsFaster(), !pairs.get(k + 1).aIsFaster());
            }
        }

        // Re-exploring the same queries must reproduce the same labels: the
        // cost signal is dominated by deterministic operator work, not timing.
        PlanExplorer replay = new PlanExplorer(catalog);
        for (int i = 0; i < 10; i++) {
            replay.exploreQuery(workload.get(i).logicalPlan());
        }
        List<TrainingPair> first = explorer.getTrainingPairs();
        List<TrainingPair> second = replay.getTrainingPairs();
        assertEquals(first.size(), second.size(), "pair count must reproduce");
        for (int k = 0; k < first.size(); k++) {
            assertEquals(first.get(k).aIsFaster(), second.get(k).aIsFaster(),
                    "labels must be deterministic across runs");
        }
    }

    @Test
    void comparatorTrainedOnFilteredPairsStaysHealthy() {
        PlanExplorer explorer = new PlanExplorer(catalog);
        PairwiseComparator comparator = new PairwiseComparator(new Random(7));

        for (ParsedQuery query : workload) {
            List<TrainingPair> newPairs = explorer.exploreQuery(query.logicalPlan());
            for (int epoch = 0; epoch < 10; epoch++) {
                for (TrainingPair pair : newPairs) {
                    comparator.trainStep(pair.featuresA(), pair.featuresB(), pair.aIsFaster());
                }
            }
        }

        List<TrainingPair> pairs = explorer.getTrainingPairs();
        assertTrue(pairs.size() > 100, "workload should produce a meaningful pair set");

        // Business property 1: the comparator actually ranks (well above coin flip)
        double accuracy = comparator.evaluateAccuracy(pairs);
        assertTrue(accuracy >= 0.9,
                "comparator should learn the filtered pairs, accuracy=" + accuracy);

        // Business property 2: no order-blind saturation — swapping the
        // arguments must flip the verdict on the pairs it ranks correctly.
        int orderBlind = 0;
        for (TrainingPair pair : pairs) {
            boolean forward = comparator.compare(pair.featuresA(), pair.featuresB()) > 0.5;
            boolean backward = comparator.compare(pair.featuresB(), pair.featuresA()) > 0.5;
            if (forward == backward) orderBlind++;
        }
        assertTrue(orderBlind < pairs.size() / 10,
                "comparator must be order-sensitive, blind on " + orderBlind + "/" + pairs.size());
    }
}
