package org.query.optimizer.learned.common;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.query.optimizer.OptimizationOptions;
import org.query.optimizer.QueryOptimizer;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.executor.Executor;
import org.query.optimizer.learned.common.WorkloadGenerator.ParsedQuery;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated workload must span the selectivity spectrum. Which rewrite
 * rules pay off depends on how selective a filter is, so if every generated
 * filter passes roughly the same fraction of rows, all queries have the same
 * best plan and an adaptive (learned) strategy has nothing to adapt to.
 */
public class WorkloadSelectivitySpreadTest {

    static Catalog catalog = new Catalog();
    static List<ParsedQuery> workload;

    @BeforeAll
    static void setup() {
        DataGenerator.generate(catalog, 1);
        workload = new WorkloadGenerator(catalog, 42L).generateWorkload(200);
    }

    @Test
    void rangeFilterSelectivitiesSpanTheSpectrum() {
        QueryOptimizer optimizer = new QueryOptimizer(catalog);
        Executor executor = new Executor();

        List<Double> selectivities = new ArrayList<>();
        for (ParsedQuery q : workload) {
            // Range-filtered single-table scans expose the filter's selectivity
            // directly as resultRows / tableRows.
            if (!q.sql().contains("WHERE") || q.sql().contains("JOIN") || !q.sql().contains(">")) {
                continue;
            }
            String table = q.sql().contains("customers") ? "customers" : "products";
            long tableRows = catalog.getTableMetadata(table).getRowCount();
            long resultRows = executor.execute(
                    optimizer.optimize(null, q.logicalPlan(), OptimizationOptions.defaults())
                            .physicalPlan()).tuples().size();
            selectivities.add((double) resultRows / tableRows);
        }

        assertTrue(selectivities.size() >= 20,
                "workload should contain many range-filtered scans, got " + selectivities.size());
        double min = selectivities.stream().mapToDouble(Double::doubleValue).min().orElse(1.0);
        double max = selectivities.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        assertTrue(min < 0.2, "some filters must be selective (<20% pass), min=" + min);
        assertTrue(max > 0.8, "some filters must be unselective (>80% pass), max=" + max);
    }
}
