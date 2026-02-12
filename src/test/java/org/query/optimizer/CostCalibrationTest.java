package org.query.optimizer;

import org.junit.jupiter.api.Test;
import org.query.optimizer.catalog.CostModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Cost Calibration.
 */
public class CostCalibrationTest {
    @Test
    public void testCalibration() {
        CostCalibrator calibrator = new CostCalibrator(false);
        CostModel.CostConfig config = calibrator.calibrate();

        assertNotNull(config, "Config should not be null");
        assertTrue(config.PAGE_COST > 0, "PAGE_COST should be positive");
        assertTrue(config.TUPLE_COST > 0, "TUPLE_COST should be positive");
        assertTrue(config.COMPARISON_COST > 0, "COMPARISON_COST should be positive");
        assertTrue(config.HASH_COST > 0, "HASH_COST should be positive");
    }

    @Test
    public void testReasonableCosts() {
        CostCalibrator calibrator = new CostCalibrator(false);
        CostModel.CostConfig config = calibrator.calibrate();

        // Sanity checks: costs should be in reasonable range
        // For in-memory operations, costs should be in microseconds/milliseconds

        // PAGE_COST should be small (accessing memory is fast)
        assertTrue(config.PAGE_COST < 1.0,
                "PAGE_COST should be < 1ms for in-memory, got " + config.PAGE_COST);

        // TUPLE_COST should be very small
        assertTrue(config.TUPLE_COST < 0.1,
                "TUPLE_COST should be < 0.1ms, got " + config.TUPLE_COST);

        // COMPARISON_COST should be tiny
        assertTrue(config.COMPARISON_COST < 0.01,
                "COMPARISON_COST should be < 0.01ms, got " + config.COMPARISON_COST);

        // HASH_COST should be small
        assertTrue(config.HASH_COST < 0.1,
                "HASH_COST should be < 0.1ms, got " + config.HASH_COST);

        // Relative costs should make sense
        assertTrue(config.PAGE_COST > config.TUPLE_COST,
                "PAGE_COST should be > TUPLE_COST");
        assertTrue(config.TUPLE_COST > config.COMPARISON_COST,
                "TUPLE_COST should be > COMPARISON_COST");

    }

    @Test
    public void testSaveLoad() throws IOException {
        CostCalibrator calibrator = new CostCalibrator(false);
        CostModel.CostConfig original = calibrator.calibrate();

        // Save
        String filename = "test_calibration.properties";
        calibrator.saveCalibration(original, filename);

        // Load
        CostModel.CostConfig loaded = calibrator.loadCalibration(filename);

        // Verify
        assertTrue(Math.abs(loaded.PAGE_COST - original.PAGE_COST) < 0.000001,
                "PAGE_COST should match after load");
        assertTrue(Math.abs(loaded.TUPLE_COST - original.TUPLE_COST) < 0.000001,
                "TUPLE_COST should match after load");
        assertTrue(Math.abs(loaded.COMPARISON_COST - original.COMPARISON_COST) < 0.000001,
                "COMPARISON_COST should match after load");
        assertTrue(Math.abs(loaded.HASH_COST - original.HASH_COST) < 0.000001,
                "HASH_COST should match after load");

        // Clean up
        Files.delete(Paths.get(filename));
    }

    @Test
    public void testDifferentFromDefaults() {
        CostCalibrator calibrator = new CostCalibrator(false);
        CostModel.CostConfig calibrated = calibrator.calibrate();
        CostModel.CostConfig defaults = new CostModel.CostConfig();

        // Calibrated costs should differ from arbitrary defaults
        // (They might coincidentally be close, but unlikely to be exact)

        boolean different =
                Math.abs(calibrated.PAGE_COST - defaults.PAGE_COST) > 0.0001 ||
                        Math.abs(calibrated.TUPLE_COST - defaults.TUPLE_COST) > 0.0001 ||
                        Math.abs(calibrated.COMPARISON_COST - defaults.COMPARISON_COST) > 0.0001 ||
                        Math.abs(calibrated.HASH_COST - defaults.HASH_COST) > 0.0001;

        assertTrue(different,
                "At least some calibrated costs should differ from defaults");
    }

    @Test
    public void testPositiveCosts() {
        CostCalibrator calibrator = new CostCalibrator(false);
        CostModel.CostConfig config = calibrator.calibrate();

        assertTrue(config.PAGE_COST > 0, "PAGE_COST must be positive");
        assertTrue(config.TUPLE_COST > 0, "TUPLE_COST must be positive");
        assertTrue(config.COMPARISON_COST > 0, "COMPARISON_COST must be positive");
        assertTrue(config.HASH_COST > 0, "HASH_COST must be positive");

        // Also check they're finite
        assertTrue(Double.isFinite(config.PAGE_COST), "PAGE_COST must be finite");
        assertTrue(Double.isFinite(config.TUPLE_COST), "TUPLE_COST must be finite");
        assertTrue(Double.isFinite(config.COMPARISON_COST), "COMPARISON_COST must be finite");
        assertTrue(Double.isFinite(config.HASH_COST), "HASH_COST must be finite");
    }
}