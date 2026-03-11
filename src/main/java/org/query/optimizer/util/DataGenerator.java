package org.query.optimizer.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Generates synthetic CSV files for the three standard benchmark tables:
 * {@code customers}, {@code products}, and {@code orders}.
 *
 * <h2>Table schemas</h2>
 * <pre>
 * customers : id:INTEGER, name:VARCHAR, city:VARCHAR, age:INTEGER
 * products  : id:INTEGER, name:VARCHAR, category:VARCHAR, price:FLOAT
 * orders    : id:INTEGER, customer_id:INTEGER, product_id:INTEGER,
 *             quantity:INTEGER, total:FLOAT
 * </pre>
 *
 * <h2>Controlled distributions</h2>
 * <p>All distributions are seeded, so the same {@code rowCount} + {@code seed}
 * always produces an identical dataset — benchmarks and correctness tests are
 * therefore fully reproducible.
 * <ul>
 *   <li><b>city</b> — 8 distinct values; default NDV kept small so predicate
 *       pushdown and cardinality estimation show meaningful improvements.</li>
 *   <li><b>category</b> — 5 distinct values.</li>
 *   <li><b>age</b> — uniform in [20, 65].</li>
 *   <li><b>price</b> — uniform in [5.00, 2 000.00]; useful for range queries.</li>
 *   <li><b>total</b> — uniform in [10.00, 1 000.00].</li>
 *   <li><b>customer_id / product_id</b> in orders — uniform foreign-key
 *       references into the corresponding table; every key is valid.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Write all three tables with 10 000 customers, 500 products, 50 000 orders
 * DataGenerator gen = new DataGenerator(10_000, 500, 50_000);
 * gen.writeAll(Path.of("target/bench-data"));
 *
 * // Load into the catalog
 * catalog.loadTableFromCSV("customers", "target/bench-data/customers.csv");
 * catalog.loadTableFromCSV("products",  "target/bench-data/products.csv");
 * catalog.loadTableFromCSV("orders",    "target/bench-data/orders.csv");
 * }</pre>
 *
 * <h2>CSV format</h2>
 * <p>Header row uses the {@code name:TYPE} convention expected by
 * {@link org.query.optimizer.catalog.Catalog#loadTableFromCSV}.  All values
 * are unquoted; VARCHAR values are guaranteed to contain no commas.
 */
public final class DataGenerator {

    // ---- Value pools for low-NDV columns --------------------------------

    private static final String[] CITIES = {
        "Seattle", "Portland", "San Francisco", "New York",
        "Chicago", "Austin", "Boston", "Denver"
    };

    private static final String[] CATEGORIES = {
        "Electronics", "Furniture", "Clothing", "Books", "Sports"
    };

    private static final String[] ORDER_STATUSES = {
        "Pending", "Shipped", "Delivered", "Cancelled"
    };

    // ---- Configuration --------------------------------------------------

    /** Default random seed — guarantees reproducibility when none is supplied. */
    public static final long DEFAULT_SEED = 42L;

    private final int  customerCount;
    private final int  productCount;
    private final int  orderCount;
    private final long seed;

    // ---- Construction ---------------------------------------------------

    /**
     * Creates a generator with explicit row counts per table and the
     * {@link #DEFAULT_SEED default seed}.
     *
     * @param customerCount rows in {@code customers}
     * @param productCount  rows in {@code products}
     * @param orderCount    rows in {@code orders}
     */
    public DataGenerator(int customerCount, int productCount, int orderCount) {
        this(customerCount, productCount, orderCount, DEFAULT_SEED);
    }

    /**
     * Creates a generator with explicit row counts and a custom seed.
     *
     * @param customerCount rows in {@code customers}
     * @param productCount  rows in {@code products}
     * @param orderCount    rows in {@code orders}
     * @param seed          PRNG seed for reproducibility
     */
    public DataGenerator(int customerCount, int productCount, int orderCount, long seed) {
        if (customerCount < 1 || productCount < 1 || orderCount < 1) {
            throw new IllegalArgumentException(
                "Row counts must be positive: customers=" + customerCount
                + ", products=" + productCount + ", orders=" + orderCount);
        }
        this.customerCount = customerCount;
        this.productCount  = productCount;
        this.orderCount    = orderCount;
        this.seed          = seed;
    }

    // ---- Convenience factories ------------------------------------------

    /**
     * Returns a generator sized for quick unit / integration tests
     * (20 customers, 10 products, 50 orders).
     */
    public static DataGenerator small() {
        return new DataGenerator(20, 10, 50);
    }

    /**
     * Returns a generator sized for demo runs
     * (200 customers, 50 products, 1 000 orders).
     */
    public static DataGenerator medium() {
        return new DataGenerator(200, 50, 1_000);
    }

    /**
     * Returns a generator sized for benchmark runs
     * (10 000 customers, 500 products, 100 000 orders).
     */
    public static DataGenerator large() {
        return new DataGenerator(10_000, 500, 100_000);
    }

    // ---- Public write API -----------------------------------------------

    /**
     * Writes all three CSV files to {@code outputDir}, creating the directory
     * if it does not already exist.
     *
     * <p>Files written:
     * <ul>
     *   <li>{@code customers.csv}</li>
     *   <li>{@code products.csv}</li>
     *   <li>{@code orders.csv}</li>
     * </ul>
     *
     * @param outputDir directory that will receive the generated files
     * @throws IOException if any file cannot be written
     */
    public void writeAll(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        writeCustomers(outputDir);
        writeProducts(outputDir);
        writeOrders(outputDir);
    }

    /**
     * Writes {@code customers.csv} to {@code outputDir}.
     *
     * <p>Schema: {@code id:INTEGER, name:VARCHAR, city:VARCHAR, age:INTEGER}
     *
     * @param outputDir target directory
     * @throws IOException if the file cannot be written
     */
    public void writeCustomers(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve("customers.csv");
        Random rng = new Random(seed);

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file))) {
            pw.println("id:INTEGER,name:VARCHAR,city:VARCHAR,age:INTEGER");
            for (int i = 1; i <= customerCount; i++) {
                String city = CITIES[rng.nextInt(CITIES.length)];
                int    age  = 20 + rng.nextInt(46);   // [20, 65]
                pw.printf("%d,Customer_%d,%s,%d%n", i, i, city, age);
            }
        }
    }

    /**
     * Writes {@code products.csv} to {@code outputDir}.
     *
     * <p>Schema: {@code id:INTEGER, name:VARCHAR, category:VARCHAR, price:FLOAT}
     *
     * @param outputDir target directory
     * @throws IOException if the file cannot be written
     */
    public void writeProducts(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve("products.csv");
        Random rng = new Random(seed + 1);   // distinct stream from customers

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file))) {
            pw.println("id:INTEGER,name:VARCHAR,category:VARCHAR,price:FLOAT");
            for (int i = 1; i <= productCount; i++) {
                String category = CATEGORIES[rng.nextInt(CATEGORIES.length)];
                // price in [5.00, 2000.00] with two decimal places
                float price = 5.0f + rng.nextFloat() * 1995.0f;
                pw.printf("%d,Product_%d,%s,%.2f%n", i, i, category, price);
            }
        }
    }

    /**
     * Writes {@code orders.csv} to {@code outputDir}.
     *
     * <p>Schema:
     * {@code id:INTEGER, customer_id:INTEGER, product_id:INTEGER, quantity:INTEGER, total:FLOAT}
     *
     * <p>{@code customer_id} is a uniform foreign-key reference into
     * {@code [1, customerCount]}; {@code product_id} similarly references
     * {@code [1, productCount]}.
     *
     * @param outputDir target directory
     * @throws IOException if the file cannot be written
     */
    public void writeOrders(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve("orders.csv");
        Random rng = new Random(seed + 2);   // distinct stream

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file))) {
            pw.println("id:INTEGER,customer_id:INTEGER,product_id:INTEGER,quantity:INTEGER,total:FLOAT");
            for (int i = 1; i <= orderCount; i++) {
                int   customerId = 1 + rng.nextInt(customerCount);
                int   productId  = 1 + rng.nextInt(productCount);
                int   quantity   = 1 + rng.nextInt(10);          // [1, 10]
                float total      = 10.0f + rng.nextFloat() * 990.0f;  // [10, 1000]
                pw.printf("%d,%d,%d,%d,%.2f%n", i, customerId, productId, quantity, total);
            }
        }
    }

    // ---- Accessors ------------------------------------------------------

    /** Returns the configured number of customer rows. */
    public int getCustomerCount() { return customerCount; }

    /** Returns the configured number of product rows. */
    public int getProductCount() { return productCount; }

    /** Returns the configured number of order rows. */
    public int getOrderCount() { return orderCount; }

    /** Returns the PRNG seed used for generation. */
    public long getSeed() { return seed; }

    // ---- Object overrides -----------------------------------------------

    @Override
    public String toString() {
        return String.format(
            "DataGenerator[customers=%d, products=%d, orders=%d, seed=%d]",
            customerCount, productCount, orderCount, seed);
    }
}
