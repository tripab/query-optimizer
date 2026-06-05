package org.query.optimizer.learned.cardinality;

import org.query.optimizer.CardinalityEstimator;
import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.logical.Expression;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.LogicalAggregate;
import org.query.optimizer.parser.LogicalFilter;
import org.query.optimizer.parser.LogicalJoin;
import org.query.optimizer.parser.LogicalProject;
import org.query.optimizer.parser.LogicalScan;

/**
 * Turns a logical subplan into a fixed-length feature vector for the learned
 * cardinality model.
 *
 * <p>Cardinality is dominated by base-table sizes, the number and kind of
 * predicates, and the number of joins, so those are summarised here. The current
 * <em>heuristic</em> estimate is included as a feature as well: the network then
 * learns a correction on top of a reasonable baseline rather than the full mapping
 * from scratch, which makes it data-efficient on small synthetic workloads.
 *
 * <p>Feature layout (length {@value #FEATURE_DIM}):
 * <pre>
 *   0  log1p(sum of base-table rows under the node)
 *   1  log1p(max base-table rows)
 *   2  log1p(min base-table rows)
 *   3  number of scans (base tables)
 *   4  number of filters
 *   5  number of equality filters (col = literal)
 *   6  number of range filters (&gt;, &lt;, &gt;=, &lt;=)
 *   7  number of inner joins
 *   8  number of aggregates
 *   9  number of GROUP BY columns
 *   10 number of projects
 *   11 log1p(heuristic cardinality estimate)
 * </pre>
 */
public class CardinalityFeaturizer {

    public static final int FEATURE_DIM = 12;

    private final Catalog catalog;
    private final CardinalityEstimator heuristic;

    public CardinalityFeaturizer(Catalog catalog) {
        this.catalog = catalog;
        this.heuristic = new CardinalityEstimator(catalog);
    }

    /**
     * Returns whether this subplan is built from supported operators (scan, filter,
     * project, inner join, aggregate). Unsupported shapes should fall back to the
     * heuristic estimator.
     */
    public boolean supports(LogicalNode node) {
        return switch (node) {
            case LogicalScan ignored -> true;
            case LogicalFilter filter -> supports(filter.getChild());
            case LogicalProject project -> supports(project.getChild());
            case LogicalAggregate aggregate -> supports(aggregate.getChild());
            case LogicalJoin join -> join.getJoinType() == LogicalJoin.JoinType.INNER
                    && supports(join.getLeft()) && supports(join.getRight());
            case null, default -> false;
        };
    }

    public double[] featurize(LogicalNode node) {
        Counts counts = new Counts();
        collect(node, counts);

        double[] f = new double[FEATURE_DIM];
        f[0] = Math.log1p(counts.sumBaseRows);
        f[1] = Math.log1p(counts.maxBaseRows);
        f[2] = Math.log1p(counts.minBaseRows == Long.MAX_VALUE ? 0 : counts.minBaseRows);
        f[3] = counts.scans;
        f[4] = counts.filters;
        f[5] = counts.eqFilters;
        f[6] = counts.rangeFilters;
        f[7] = counts.joins;
        f[8] = counts.aggregates;
        f[9] = counts.groupByColumns;
        f[10] = counts.projects;
        f[11] = Math.log1p(heuristic.estimate(node));
        return f;
    }

    // -------------------------------------------------------------------------
    // Internal traversal
    // -------------------------------------------------------------------------

    private static final class Counts {
        long sumBaseRows = 0;
        long maxBaseRows = 0;
        long minBaseRows = Long.MAX_VALUE;
        int scans, filters, eqFilters, rangeFilters, joins, aggregates, projects, groupByColumns;
    }

    private void collect(LogicalNode node, Counts counts) {
        switch (node) {
            case LogicalScan scan -> {
                long rows = catalog.getTableMetadata(scan.getTableName()).getRowCount();
                counts.scans++;
                counts.sumBaseRows += rows;
                counts.maxBaseRows = Math.max(counts.maxBaseRows, rows);
                counts.minBaseRows = Math.min(counts.minBaseRows, rows);
            }
            case LogicalFilter filter -> {
                counts.filters++;
                classifyPredicate(filter.getPredicate(), counts);
                collect(filter.getChild(), counts);
            }
            case LogicalProject project -> {
                counts.projects++;
                collect(project.getChild(), counts);
            }
            case LogicalAggregate aggregate -> {
                counts.aggregates++;
                counts.groupByColumns += aggregate.getGroupByColumns().size();
                collect(aggregate.getChild(), counts);
            }
            case LogicalJoin join -> {
                counts.joins++;
                collect(join.getLeft(), counts);
                collect(join.getRight(), counts);
            }
            default -> {
                // unsupported leaf; nothing to add
            }
        }
    }

    private void classifyPredicate(Expression predicate, Counts counts) {
        if (!(predicate instanceof Expression.BinaryOp binary)) {
            return;
        }
        switch (binary.operator()) {
            case EQ -> counts.eqFilters++;
            case GT, GTE, LT, LTE -> counts.rangeFilters++;
            case AND, OR -> {
                classifyPredicate(binary.left(), counts);
                classifyPredicate(binary.right(), counts);
            }
            default -> {
                // NEQ and others: not separately counted
            }
        }
    }
}
