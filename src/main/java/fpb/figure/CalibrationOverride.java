/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.figure;

/** Immutable user-entered physical pixel size for one source image. */
public final class CalibrationOverride {

    private final double pixelWidthUm;
    private final double pixelHeightUm;

    public CalibrationOverride(double pixelWidthUm, double pixelHeightUm) {
        if (!valid(pixelWidthUm) || !valid(pixelHeightUm)) {
            throw new IllegalArgumentException(
                    "User-entered pixel sizes must be finite and positive.");
        }
        this.pixelWidthUm = pixelWidthUm;
        this.pixelHeightUm = pixelHeightUm;
    }

    public double pixelWidthUm() {
        return pixelWidthUm;
    }

    public double pixelHeightUm() {
        return pixelHeightUm;
    }

    private static boolean valid(double value) {
        return Double.isFinite(value) && value > 0.0;
    }
}
