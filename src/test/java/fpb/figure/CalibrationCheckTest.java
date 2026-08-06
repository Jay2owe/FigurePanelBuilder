/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.figure;

import ij.measure.Calibration;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CalibrationCheckTest {

    @Test
    public void drawnHalfWidthHasHalfThePixelLengthForSamePhysicalBar() {
        CalibrationCheck.Result source = CalibrationCheck.userEntered(0.5, 0.5);

        int full = ScaleBar.lengthPixels(source, 1000, 800, 1000, 800, 100.0);
        int half = ScaleBar.lengthPixels(source, 1000, 800, 500, 400, 100.0);

        assertEquals(200, full);
        assertEquals(100, half);
    }

    @Test
    public void pyramidLevelRecomputesFromLevelDimensions() {
        CalibrationCheck.Result level = CalibrationCheck.recomputedPyramid(
                0.25, 0.25, 4096, 2048, 1024, 512);

        assertTrue(level.isAvailable());
        assertEquals(CalibrationCheck.CalibrationSource.RECOMPUTED_PYRAMID,
                level.source());
        assertEquals(1.0, level.pixelWidthUm(), 0.000001);
        assertEquals(1.0, level.pixelHeightUm(), 0.000001);
    }

    @Test
    public void unqualifiedImageJDefaultCalibrationIsNotTreatedAsMicrons() {
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 1.0;
        calibration.pixelHeight = 1.0;

        CalibrationCheck.Result result =
                CalibrationCheck.fromImageMetadata(calibration);

        assertFalse(result.isAvailable());
        assertEquals(CalibrationCheck.CalibrationSource.NONE, result.source());
    }

    @Test
    public void bioFormatsMetadataRetainsItsProvenance() {
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.25;
        calibration.pixelHeight = 0.5;
        calibration.setUnit("micron");

        CalibrationCheck.Result result =
                CalibrationCheck.fromBioFormatsMetadata(calibration);

        assertTrue(result.isAvailable());
        assertEquals(CalibrationCheck.CalibrationSource.BIO_FORMATS,
                result.source());
    }

    @Test
    public void userOverrideTakesPrecedenceOverEmbeddedMetadata() {
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 2.0;
        calibration.pixelHeight = 3.0;
        calibration.setUnit("micron");

        CalibrationCheck.Result result = CalibrationCheck.resolve(calibration,
                true, new CalibrationOverride(0.25, 0.5));

        assertEquals(CalibrationCheck.CalibrationSource.USER_ENTERED,
                result.source());
        assertEquals(0.25, result.pixelWidthUm(), 0.0);
        assertEquals(0.5, result.pixelHeightUm(), 0.0);
    }
}
