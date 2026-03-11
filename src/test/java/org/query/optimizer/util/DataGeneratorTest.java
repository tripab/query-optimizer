package org.query.optimizer.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataGeneratorTest {

    @TempDir
    Path tempDir;

    // ---- construction ---------------------------------------------------

    @Test
    void testConstructorRejectsZeroRowCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new DataGenerator(0, 10, 50));
        assertThrows(IllegalArgumentException.class,
                () -> new DataGenerator(10, 0, 50));
        assertThrows(IllegalArgumentException.class,
                () -> new DataGenerator(10, 10, 0));
    }

    @Test
    void testAccessorsReturnConfiguredValues() {
        DataGenerator gen = new DataGenerator(7, 3, 15, 99L);
        assertEquals(7,   gen.getCustomerCount());
        assertEquals(3,   gen.getProductCount());
        assertEquals(15,  gen.getOrderCount());
        assertEquals(99L, gen.getSeed());
    }

    // ---- factory methods ------------------------------------------------

    @Test
    void testSmallFactoryProducesExpectedRowCounts() {
        DataGenerator gen = DataGenerator.small();
        assertEquals(20, gen.getCustomerCount());
        assertEquals(10, gen.getProductCount());
        assertEquals(50, gen.getOrderCount());
    }

    @Test
    void testMediumFactoryProducesExpectedRowCounts() {
        DataGenerator gen = DataGenerator.medium();
        assertEquals(200,   gen.getCustomerCount());
        assertEquals(50,    gen.getProductCount());
        assertEquals(1_000, gen.getOrderCount());
    }

    @Test
    void testLargeFactoryProducesExpectedRowCounts() {
        DataGenerator gen = DataGenerator.large();
        assertEquals(10_000,  gen.getCustomerCount());
        assertEquals(500,     gen.getProductCount());
        assertEquals(100_000, gen.getOrderCount());
    }

    // ---- customers.csv --------------------------------------------------

    @Test
    void testWriteCustomersCreatesFile() throws IOException {
        DataGenerator.small().writeCustomers(tempDir);
        assertTrue(Files.exists(tempDir.resolve("customers.csv")));
    }

    @Test
    void testWriteCustomersHasCorrectHeader() throws IOException {
        DataGenerator.small().writeCustomers(tempDir);
        List<String> lines = Files.readAllLines(tempDir.resolve("customers.csv"));
        assertEquals("id:INTEGER,name:VARCHAR,city:VARCHAR,age:INTEGER", lines.get(0));
    }

    @Test
    void testWriteCustomersCorrectRowCount() throws IOException {
        new DataGenerator(30, 10, 50).writeCustomers(tempDir);
        List<String> lines = Files.readAllLines(tempDir.resolve("customers.csv"));
        // header + 30 data rows
        assertEquals(31, lines.size());
    }

    @Test
    void testWriteCustomersRowStructure() throws IOException {
        new DataGenerator(5, 5, 5).writeCustomers(tempDir);
        List<String> lines = Files.readAllLines(tempDir.resolve("customers.csv"));
        // Check every data row has 4 comma-separated fields
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",");
            assertEquals(4, parts.length, "Row " + i + " should have 4 fields");
            // id is a positive integer
            assertTrue(Integer.parseInt(parts[0]) > 0);
            // age is in [20, 65]
            int age = Integer.parseInt(parts[3]);
            assertTrue(age >= 20 && age <= 65, "Age out of range: " + age);
        }
    }

    @Test
    void testWriteCustomersCityValuesAreFromKnownPool() throws IOException {
        new DataGenerator(50, 10, 50).writeCustomers(tempDir);
        List<String> lines = Files.readAllLines(tempDir.resolve("customers.csv"));
        List<String> validCities = List.of(
                "Seattle", "Portland", "San Francisco", "New York",
                "Chicago", "Austin", "Boston", "Denver");
        for (int i = 1; i < lines.size(); i++) {
            String city = lines.get(i).split(",")[2];
            assertTrue(validCities.contains(city), "Unknown city: " + city);
        }
    }

    // ---- products.csv ---------------------------------------------------

    @Test
    void testWriteProductsCreatesFile() throws IOException {
        DataGenerator.small().writeProducts(tempDir);
        assertTrue(Files.exists(tempDir.resolve("products.csv")));
    }

    @Test
    void testWriteProductsHasCorrectHeader() throws IOException {
        DataGenerator.small().writeProducts(tempDir);
        List<String> lines = Files.readAllLines(tempDir.resolve("products.csv"));
        assertEquals("id:INTEGER,name:VARCHAR,category:VARCHAR,price:FLOAT", lines.get(0));
    }

    @Test
    void testWriteProductsCorrectRowCount() throws IOException {
        new DataGenerator(10, 25, 50).writeProducts(tempDir);
        List<String> lines = Files.readAllLines(tempDir.resolve("products.csv"));
        assertEquals(26, lines.size());  // header + 25
    }

    @Test
    void testWriteProductsPriceIsPositive() throws IOException {
        new DataGenerator(10, 40, 50).writeProducts(tempDir);
        List<String> lines = Files.readAllLines(tempDir.resolve("products.csv"));
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",");
            assertEquals(4, parts.length, "Row " + i + " should have 4 fields");
            float price = Float.parseFloat(parts[3]);
            assertTrue(price >= 5.0f && price <= 2000.0f, "Price out of range: " + price);
        }
    }

    @Test
    void testWriteProductsCategoryValuesAreFromKnownPool() throws IOException {
        new DataGenerator(10, 60, 50).writeProducts(tempDir);
        List<String> lines = Files.readAllLines(tempDir.resolve("products.csv"));
        List<String> validCategories = List.of(
                "Electronics", "Furniture", "Clothing", "Books", "Sports");
        for (int i = 1; i < lines.size(); i++) {
            String category = lines.get(i).split(",")[2];
            assertTrue(validCategories.contains(category), "Unknown category: " + category);
        }
    }

    // ---- orders.csv -----------------------------------------------------

    @Test
    void testWriteOrdersCreatesFile() throws IOException {
        DataGenerator.small().writeOrders(tempDir);
        assertTrue(Files.exists(tempDir.resolve("orders.csv")));
    }

    @Test
    void testWriteOrdersHasCorrectHeader() throws IOException {
        DataGenerator.small().writeOrders(tempDir);
        List<String> lines = Files.readAllLines(tempDir.resolve("orders.csv"));
        assertEquals(
            "id:INTEGER,customer_id:INTEGER,product_id:INTEGER,quantity:INTEGER,total:FLOAT",
            lines.get(0));
    }

    @Test
    void testWriteOrdersCorrectRowCount() throws IOException {
        new DataGenerator(10, 10, 75).writeOrders(tempDir);
        List<String> lines = Files.readAllLines(tempDir.resolve("orders.csv"));
        assertEquals(76, lines.size());  // header + 75
    }

    @Test
    void testWriteOrdersForeignKeysAreInRange() throws IOException {
        int customers = 8;
        int products  = 5;
        new DataGenerator(customers, products, 100).writeOrders(tempDir);
        List<String> lines = Files.readAllLines(tempDir.resolve("orders.csv"));
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",");
            assertEquals(5, parts.length, "Row " + i + " should have 5 fields");
            int cid = Integer.parseInt(parts[1]);
            int pid = Integer.parseInt(parts[2]);
            assertTrue(cid >= 1 && cid <= customers, "customer_id out of range: " + cid);
            assertTrue(pid >= 1 && pid <= products,  "product_id out of range: " + pid);
        }
    }

    @Test
    void testWriteOrdersQuantityAndTotalAreInRange() throws IOException {
        new DataGenerator(10, 10, 80).writeOrders(tempDir);
        List<String> lines = Files.readAllLines(tempDir.resolve("orders.csv"));
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",");
            int   qty   = Integer.parseInt(parts[3]);
            float total = Float.parseFloat(parts[4]);
            assertTrue(qty >= 1 && qty <= 10,           "quantity out of range: " + qty);
            assertTrue(total >= 10.0f && total <= 1000.0f, "total out of range: " + total);
        }
    }

    // ---- writeAll -------------------------------------------------------

    @Test
    void testWriteAllCreatesAllThreeFiles() throws IOException {
        DataGenerator.small().writeAll(tempDir);
        assertTrue(Files.exists(tempDir.resolve("customers.csv")));
        assertTrue(Files.exists(tempDir.resolve("products.csv")));
        assertTrue(Files.exists(tempDir.resolve("orders.csv")));
    }

    @Test
    void testWriteAllCreatesOutputDirectoryIfAbsent() throws IOException {
        Path subDir = tempDir.resolve("generated");
        assertFalse(Files.exists(subDir));
        DataGenerator.small().writeAll(subDir);
        assertTrue(Files.exists(subDir));
    }

    // ---- reproducibility ------------------------------------------------

    @Test
    void testSameSeedProducesIdenticalOutput() throws IOException {
        Path dir1 = tempDir.resolve("run1");
        Path dir2 = tempDir.resolve("run2");
        DataGenerator gen = new DataGenerator(15, 8, 30, 7L);
        gen.writeAll(dir1);
        gen.writeAll(dir2);

        for (String filename : List.of("customers.csv", "products.csv", "orders.csv")) {
            assertEquals(
                Files.readAllLines(dir1.resolve(filename)),
                Files.readAllLines(dir2.resolve(filename)),
                filename + " should be identical across runs with the same seed");
        }
    }

    @Test
    void testDifferentSeedsProduceDifferentOutput() throws IOException {
        Path dir1 = tempDir.resolve("seed1");
        Path dir2 = tempDir.resolve("seed2");
        new DataGenerator(15, 8, 30, 1L).writeAll(dir1);
        new DataGenerator(15, 8, 30, 2L).writeAll(dir2);

        // The city column in customers should differ for at least one row
        List<String> customers1 = Files.readAllLines(dir1.resolve("customers.csv"));
        List<String> customers2 = Files.readAllLines(dir2.resolve("customers.csv"));
        assertNotEquals(customers1, customers2,
                "Different seeds should produce different customers data");
    }

    // ---- catalog integration --------------------------------------------

    @Test
    void testGeneratedFilesLoadIntoCanonicalCatalog() throws IOException {
        DataGenerator.small().writeAll(tempDir);

        org.query.optimizer.catalog.Catalog catalog =
                new org.query.optimizer.catalog.Catalog();
        assertDoesNotThrow(() -> {
            catalog.loadTableFromCSV("customers",
                    tempDir.resolve("customers.csv").toString());
            catalog.loadTableFromCSV("products",
                    tempDir.resolve("products.csv").toString());
            catalog.loadTableFromCSV("orders",
                    tempDir.resolve("orders.csv").toString());
        });

        assertEquals(20, catalog.getTableMetadata("customers").getRowCount());
        assertEquals(10, catalog.getTableMetadata("products").getRowCount());
        assertEquals(50, catalog.getTableMetadata("orders").getRowCount());
    }
}
