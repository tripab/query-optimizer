package org.query.optimizer.learned.common;

import org.query.optimizer.catalog.Catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Populates a {@link Catalog} with large synthetic tables suitable for
 * measuring plan-quality differences between alternative hint sets.
 *
 * <p>The existing test CSV fixtures have ~10 rows per table — far too small
 * for hash-join vs. nested-loop differences to show up in wall-clock time.
 * This class generates 1 000–100 000-row tables directly into a {@link Catalog}
 * so the learned optimizer models have a workload where plan choice actually
 * matters.
 *
 * <h2>Scale factor</h2>
 * <pre>
 *   scaleFactor=1   →  1 000 customers,  500 products,  2 000 orders
 *   scaleFactor=10  → 10 000 customers, 5 000 products, 20 000 orders
 *   scaleFactor=100 → 100 000 customers ...
 * </pre>
 *
 * <h2>Implementation</h2>
 * <p>Generation delegates to {@link org.query.optimizer.util.DataGenerator},
 * which owns all the distribution logic (city NDV=8, category NDV=5, seeded
 * PRNG for reproducibility). The CSV files are written to a temporary
 * directory, loaded into the catalog via
 * {@link Catalog#loadTableFromCSV} (which runs statistics collection and
 * histogram construction), then deleted. The catalog is left with fully
 * annotated {@code TableMetadata} objects ready for optimisation.
 *
 * <h2>Idempotency</h2>
 * <p>If a table with the same name already exists in the catalog the method
 * skips generation for that table. Call {@link #replaceAll} to force
 * regeneration.
 */
public final class DataGenerator {

    private DataGenerator() {} // static utility class

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates and registers all three benchmark tables in {@code catalog},
     * skipping any table that is already registered.
     *
     * @param catalog     target catalog
     * @param scaleFactor controls table size (1 → ~1 K rows, 10 → ~10 K rows)
     * @throws IllegalArgumentException if {@code scaleFactor} is less than 1
     * @throws RuntimeException         wrapping any {@link IOException} during
     *                                  temp-file I/O
     */
    public static void generate(Catalog catalog, int scaleFactor) {
        if (scaleFactor < 1) {
            throw new IllegalArgumentException(
                    "scaleFactor must be ≥ 1, got: " + scaleFactor);
        }

        int customerCount = scaleFactor * 1_000;
        int productCount  = scaleFactor * 500;
        int orderCount    = scaleFactor * 2_000;

        generateInto(catalog, customerCount, productCount, orderCount, false);
    }

    /**
     * Like {@link #generate}, but replaces any existing tables with the same
     * names rather than skipping them.
     *
     * @param catalog     target catalog
     * @param scaleFactor controls table size
     */
    public static void replaceAll(Catalog catalog, int scaleFactor) {
        if (scaleFactor < 1) {
            throw new IllegalArgumentException(
                    "scaleFactor must be ≥ 1, got: " + scaleFactor);
        }

        int customerCount = scaleFactor * 1_000;
        int productCount  = scaleFactor * 500;
        int orderCount    = scaleFactor * 2_000;

        generateInto(catalog, customerCount, productCount, orderCount, true);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static void generateInto(
            Catalog catalog,
            int customerCount,
            int productCount,
            int orderCount,
            boolean replace) {

        org.query.optimizer.util.DataGenerator gen =
                new org.query.optimizer.util.DataGenerator(
                        customerCount, productCount, orderCount);

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("query-optimizer-datagen-");

            // Write CSVs using the existing util generator (owns all distributions)
            gen.writeAll(tempDir);

            // Load each table into the catalog, optionally skipping existing ones
            loadIfNeeded(catalog, "customers",
                    tempDir.resolve("customers.csv"), replace);
            loadIfNeeded(catalog, "products",
                    tempDir.resolve("products.csv"), replace);
            loadIfNeeded(catalog, "orders",
                    tempDir.resolve("orders.csv"), replace);

        } catch (IOException e) {
            throw new RuntimeException(
                    "DataGenerator failed during CSV I/O: " + e.getMessage(), e);
        } finally {
            deleteTempDir(tempDir);
        }
    }

    private static void loadIfNeeded(
            Catalog catalog, String tableName, Path csvFile, boolean replace)
            throws IOException {

        if (!replace && catalog.hasTable(tableName)) {
            return; // already registered — skip
        }
        catalog.loadTableFromCSV(tableName, csvFile.toString());
    }

    /** Best-effort cleanup — temp files are small; failures are logged but swallowed. */
    private static void deleteTempDir(Path dir) {
        if (dir == null) return;
        try {
            try (var stream = Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                      .forEach(p -> {
                          try { Files.deleteIfExists(p); }
                          catch (IOException ignored) {}
                      });
            }
        } catch (IOException ignored) {}
    }
}
