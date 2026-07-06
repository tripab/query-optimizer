package org.query.optimizer.learned.common;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.SimpleCostModel;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.Tuple;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.learned.common.WorkloadGenerator.ParsedQuery;
import org.query.optimizer.physical.PhysicalNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant: every hint-set plan variant of the same query must return the
 * same rows. Hint sets change *how* a query is executed (rules, join
 * algorithm, join order) — never *what* it returns.
 *
 * <p>This is the benchmark's correctness precondition: the learned optimizers
 * and the oracle compare variant latencies, which is only meaningful when the
 * variants agree on the answer. The alias-resolution bug this guards against
 * made forced-NLJ variants of aliased 3-way joins return wrong rows on 70/300
 * workload queries, silently corrupting every downstream metric.
 */
public class PlanVariantResultParityTest {

    static Catalog catalog = new Catalog();
    static List<ParsedQuery> workload;

    @BeforeAll
    static void setup() {
        DataGenerator.generate(catalog, 1);
        // Seeded workload; 25 queries cover all five shapes several times each
        workload = new WorkloadGenerator(catalog, 42L).generateWorkload(25);
    }

    @Test
    void allHintSetVariantsReturnIdenticalResults() {
        PlanVariantGenerator generator =
                new PlanVariantGenerator(catalog, new SimpleCostModel(catalog));
        Executor executor = new Executor();

        int queriesWithAlternatives = 0;

        for (ParsedQuery query : workload) {
            Map<HintSet, PhysicalNode> variants =
                    generator.generateVariants(query.logicalPlan(), HintSet.allHintSets());
            if (variants.size() > 1) {
                queriesWithAlternatives++;
            }

            List<String> reference = null;
            HintSet referenceArm = null;
            for (Map.Entry<HintSet, PhysicalNode> e : variants.entrySet()) {
                List<String> rows = sortedRows(executor.execute(e.getValue()).tuples());
                if (reference == null) {
                    reference = rows;
                    referenceArm = e.getKey();
                } else {
                    assertEquals(reference, rows, String.format(
                            "variant '%s' disagrees with '%s' on: %s",
                            e.getKey().getName(), referenceArm.getName(), query.sql()));
                }
            }
        }

        // Guard against a vacuous pass: the workload must actually produce
        // alternative plans to compare (joins yield several variants each).
        assertTrue(queriesWithAlternatives >= 5,
                "expected several queries with multiple plan variants, got "
                        + queriesWithAlternatives);
    }

    private static List<String> sortedRows(List<Tuple> tuples) {
        List<String> rows = new ArrayList<>(tuples.size());
        for (Tuple t : tuples) {
            rows.add(t.toString());
        }
        rows.sort(String::compareTo);
        return rows;
    }
}
