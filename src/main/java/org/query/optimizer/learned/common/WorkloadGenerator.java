package org.query.optimizer.learned.common;

import org.query.optimizer.catalog.Catalog;
import org.query.optimizer.catalog.ColumnStats;
import org.query.optimizer.catalog.DataType;
import org.query.optimizer.catalog.Schema;
import org.query.optimizer.catalog.TableMetadata;
import org.query.optimizer.logical.LogicalNode;
import org.query.optimizer.parser.AST;
import org.query.optimizer.parser.LogicalPlanBuilder;
import org.query.optimizer.parser.SQLParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates a random workload of parsed SQL queries for use by the learned
 * optimizer benchmarks and demos.
 *
 * <p>Each query is generated from one of five structural templates:
 * <ol>
 *   <li><b>Simple scan</b> — {@code SELECT … FROM t}</li>
 *   <li><b>Filtered scan</b> — {@code SELECT … FROM t WHERE predicate}</li>
 *   <li><b>Two-way join</b> — {@code SELECT … FROM t1 JOIN t2 ON … WHERE …}</li>
 *   <li><b>Three-way join</b> — {@code SELECT … FROM t1 JOIN t2 … JOIN t3 …}</li>
 *   <li><b>Join with aggregation</b> — {@code SELECT …, COUNT(*) FROM … GROUP BY …}</li>
 * </ol>
 *
 * <p>Predicates are drawn from the actual column statistics stored in the
 * catalog so that generated range values fall within the real data range and
 * produce non-trivial selectivities.
 *
 * <h2>Reproducibility</h2>
 * <p>Pass an explicit seed to the constructor to get a deterministic workload;
 * the no-seed constructor uses a random seed so each run is different by default.
 *
 * <h2>Prerequisites</h2>
 * <p>The catalog must contain tables named {@code customers}, {@code products},
 * and {@code orders} (e.g. populated via {@link DataGenerator#generate}).
 * Queries involving tables that are absent from the catalog are skipped and
 * regenerated rather than throwing.
 */
public class WorkloadGenerator {

    /** A parsed SQL query ready to be handed to the optimizer. */
    public record ParsedQuery(String sql, LogicalNode logicalPlan) {}

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Catalog           catalog;
    private final Random            random;
    private final SQLParser         parser;
    private final LogicalPlanBuilder planBuilder;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a generator backed by the given catalog with a random seed.
     *
     * @param catalog source of schema and statistics used to build predicates
     */
    public WorkloadGenerator(Catalog catalog) {
        this(catalog, new Random().nextLong());
    }

    /**
     * Creates a generator backed by the given catalog with an explicit seed
     * for reproducible workloads.
     *
     * @param catalog source of schema and statistics
     * @param seed    PRNG seed
     */
    public WorkloadGenerator(Catalog catalog, long seed) {
        this.catalog      = catalog;
        this.random       = new Random(seed);
        this.parser       = new SQLParser();
        this.planBuilder  = new LogicalPlanBuilder(catalog);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates {@code size} random queries and returns them as a list of
     * {@link ParsedQuery} records, each carrying both the original SQL string
     * and the unoptimized logical plan.
     *
     * <p>If a generated SQL string fails to parse or build a logical plan
     * (e.g. because a referenced table is absent), that attempt is silently
     * retried with a different template until {@code size} queries are
     * accumulated.
     *
     * @param size number of queries to generate
     * @return list of parsed queries, length exactly {@code size}
     * @throws IllegalArgumentException if {@code size} is less than 1
     */
    public List<ParsedQuery> generateWorkload(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("size must be ≥ 1, got: " + size);
        }

        List<ParsedQuery> queries = new ArrayList<>(size);
        int attempts = 0;
        int maxAttempts = size * 10; // guard against degenerate catalogs

        while (queries.size() < size && attempts < maxAttempts) {
            attempts++;
            try {
                String sql = generateSQL();
                AST.SelectStmt ast = parser.parse(sql);
                LogicalNode plan   = planBuilder.build(ast);
                queries.add(new ParsedQuery(sql, plan));
            } catch (Exception ignored) {
                // template referenced a missing column or table — retry
            }
        }

        if (queries.size() < size) {
            throw new IllegalStateException(
                "Could not generate " + size + " queries after " + maxAttempts
                + " attempts. Ensure the catalog contains customers, products, and orders.");
        }

        return queries;
    }

    // -------------------------------------------------------------------------
    // Query shape selection
    // -------------------------------------------------------------------------

    private String generateSQL() {
        int shape = random.nextInt(5);
        return switch (shape) {
            case 0 -> generateSimpleScan();
            case 1 -> generateFilteredScan();
            case 2 -> generateTwoWayJoin();
            case 3 -> generateThreeWayJoin();
            default -> generateJoinWithAggregation();
        };
    }

    // -------------------------------------------------------------------------
    // Template generators
    // -------------------------------------------------------------------------

    /** SELECT id, name FROM customers  (or products) */
    private String generateSimpleScan() {
        if (random.nextBoolean()) {
            return "SELECT id, name FROM customers";
        } else {
            return "SELECT id, name FROM products";
        }
    }

    /** SELECT … FROM t WHERE col OP literal */
    private String generateFilteredScan() {
        if (random.nextBoolean()) {
            // customers filtered by city (equality) or age (range)
            if (random.nextBoolean()) {
                String city = randomCity();
                return "SELECT id, name FROM customers WHERE city = '" + city + "'";
            } else {
                int age = randomAge();
                return "SELECT id, name, age FROM customers WHERE age > " + age;
            }
        } else {
            // products filtered by category (equality) or price (range)
            if (random.nextBoolean()) {
                String cat = randomCategory();
                return "SELECT id, name FROM products WHERE category = '" + cat + "'";
            } else {
                float price = randomPrice();
                return String.format("SELECT id, name, price FROM products WHERE price > %.2f", price);
            }
        }
    }

    /** SELECT … FROM customers c INNER JOIN orders o ON c.id = o.customer_id WHERE … */
    private String generateTwoWayJoin() {
        String predicate = randomOrderPredicate("o");
        return "SELECT c.name, o.total FROM customers c "
             + "INNER JOIN orders o ON c.id = o.customer_id "
             + "WHERE " + predicate;
    }

    /** Three-way join: customers × orders × products */
    private String generateThreeWayJoin() {
        String customerFilter = "c.city = '" + randomCity() + "'";
        return "SELECT c.name, o.total, p.name FROM customers c "
             + "INNER JOIN orders o ON c.id = o.customer_id "
             + "INNER JOIN products p ON o.product_id = p.id "
             + "WHERE " + customerFilter;
    }

    /** SELECT category, COUNT(*) … GROUP BY category */
    private String generateJoinWithAggregation() {
        String cat = randomCategory();
        return "SELECT p.category, COUNT(*) FROM products p "
             + "INNER JOIN orders o ON p.id = o.product_id "
             + "WHERE p.category = '" + cat + "' "
             + "GROUP BY p.category";
    }

    // -------------------------------------------------------------------------
    // Value helpers — drawn from catalog stats where available
    // -------------------------------------------------------------------------

    private static final String[] DEFAULT_CITIES = {
        "Seattle", "Portland", "San Francisco", "New York",
        "Chicago", "Austin", "Boston", "Denver"
    };

    private static final String[] DEFAULT_CATEGORIES = {
        "Electronics", "Furniture", "Clothing", "Books", "Sports"
    };

    private String randomCity() {
        return DEFAULT_CITIES[random.nextInt(DEFAULT_CITIES.length)];
    }

    private String randomCategory() {
        return DEFAULT_CATEGORIES[random.nextInt(DEFAULT_CATEGORIES.length)];
    }

    /**
     * Returns a random age threshold spanning the full observed [min, max]
     * range, so {@code age > threshold} selectivities run from ~100% down to
     * ~0% across the workload. The spread matters for the learned optimizers:
     * which rewrite rules pay off depends on filter selectivity (e.g. pushing
     * a projection below an almost-pass-nothing filter processes every row for
     * nothing), so a workload whose filters all pass ~50% of rows gives an
     * adaptive strategy nothing to adapt to.
     */
    private int randomAge() {
        int min = 20;
        int max = 60;
        if (catalog.hasTable("customers")) {
            TableMetadata t = catalog.getTableMetadata("customers");
            ColumnStats   s = t.getColumnStats("age");
            if (s != null && s.minValue() instanceof Integer lo
                          && s.maxValue() instanceof Integer hi) {
                min = lo;
                max = hi;
            }
        }
        return min + random.nextInt(Math.max(1, max - min));
    }

    /**
     * Returns a random price threshold spanning the full observed range —
     * see {@link #randomAge()} for why the workload needs the full
     * selectivity spread.
     */
    private float randomPrice() {
        float min = 5.0f;
        float max = 1000.0f;
        if (catalog.hasTable("products")) {
            TableMetadata t = catalog.getTableMetadata("products");
            ColumnStats   s = t.getColumnStats("price");
            if (s != null && s.minValue() instanceof Float lo
                          && s.maxValue() instanceof Float hi) {
                min = lo;
                max = hi;
            }
        }
        return min + random.nextFloat() * (max - min);
    }

    /** Random predicate on the orders table, qualified by {@code alias}. */
    private String randomOrderPredicate(String alias) {
        float threshold = randomOrderTotal();
        return String.format("%s.total > %.2f", alias, threshold);
    }

    /**
     * Returns a random order-total threshold spanning the full observed range —
     * see {@link #randomAge()} for why the workload needs the full
     * selectivity spread.
     */
    private float randomOrderTotal() {
        float min = 10.0f;
        float max = 500.0f;
        if (catalog.hasTable("orders")) {
            TableMetadata t = catalog.getTableMetadata("orders");
            ColumnStats   s = t.getColumnStats("total");
            if (s != null && s.minValue() instanceof Float lo
                          && s.maxValue() instanceof Float hi) {
                min = lo;
                max = hi;
            }
        }
        return min + random.nextFloat() * (max - min);
    }
}
