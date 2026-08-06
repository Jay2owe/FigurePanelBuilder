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

/** Resolves and recomputes physical pixel sizes used for panel annotations. */
public final class CalibrationCheck {

    private CalibrationCheck() {}

    public enum CalibrationSource {
        BIO_FORMATS,
        IMAGE_METADATA,
        USER_ENTERED,
        RECOMPUTED_PYRAMID,
        NONE
    }

    public static Result fromImageMetadata(Calibration calibration) {
        return fromCalibrationMetadata(calibration, CalibrationSource.IMAGE_METADATA);
    }

    public static Result fromBioFormatsMetadata(Calibration calibration) {
        return fromCalibrationMetadata(calibration, CalibrationSource.BIO_FORMATS);
    }

    private static Result fromCalibrationMetadata(Calibration calibration,
            CalibrationSource source) {
        if (calibration == null) return none();
        if (!isMicronUnit(calibration.getUnit())) return none();
        return fromPixelSize(calibration.pixelWidth, calibration.pixelHeight,
                source);
    }

    public static Result bioFormats(double pixelWidthUm, double pixelHeightUm) {
        return fromPixelSize(pixelWidthUm, pixelHeightUm, CalibrationSource.BIO_FORMATS);
    }

    public static Result userEntered(double pixelWidthUm, double pixelHeightUm) {
        return fromPixelSize(pixelWidthUm, pixelHeightUm, CalibrationSource.USER_ENTERED);
    }

    /** Resolves an explicit user override before considering embedded metadata. */
    public static Result resolve(Calibration calibration, boolean openedWithBioFormats,
            CalibrationOverride override) {
        if (override != null) {
            return userEntered(override.pixelWidthUm(), override.pixelHeightUm());
        }
        return openedWithBioFormats
                ? fromBioFormatsMetadata(calibration)
                : fromImageMetadata(calibration);
    }

    public static Result recomputedPyramid(double level0PixelWidthUm,
            double level0PixelHeightUm, int level0WidthPx, int level0HeightPx,
            int levelWidthPx, int levelHeightPx) {
        requirePositive("level0WidthPx", level0WidthPx);
        requirePositive("level0HeightPx", level0HeightPx);
        requirePositive("levelWidthPx", levelWidthPx);
        requirePositive("levelHeightPx", levelHeightPx);
        if (!validPixelSize(level0PixelWidthUm, level0PixelHeightUm)) return none();
        double x = level0PixelWidthUm * (level0WidthPx / (double) levelWidthPx);
        double y = level0PixelHeightUm * (level0HeightPx / (double) levelHeightPx);
        return fromPixelSize(x, y, CalibrationSource.RECOMPUTED_PYRAMID);
    }

    public static Result forDrawnSize(Result source, int sourceWidthPx,
            int sourceHeightPx, int drawnWidthPx, int drawnHeightPx) {
        Result safe = source == null ? none() : source;
        if (!safe.isAvailable()) return safe;
        requirePositive("sourceWidthPx", sourceWidthPx);
        requirePositive("sourceHeightPx", sourceHeightPx);
        requirePositive("drawnWidthPx", drawnWidthPx);
        requirePositive("drawnHeightPx", drawnHeightPx);
        double x = safe.pixelWidthUm()
                * (sourceWidthPx / (double) drawnWidthPx);
        double y = safe.pixelHeightUm()
                * (sourceHeightPx / (double) drawnHeightPx);
        return fromPixelSize(x, y, safe.source());
    }

    public static Result none() {
        return new Result(Double.NaN, Double.NaN, CalibrationSource.NONE);
    }

    private static Result fromPixelSize(double pixelWidthUm, double pixelHeightUm,
            CalibrationSource source) {
        if (!validPixelSize(pixelWidthUm, pixelHeightUm)) return none();
        CalibrationSource safeSource = source == null ? CalibrationSource.NONE : source;
        if (safeSource == CalibrationSource.NONE) return none();
        return new Result(pixelWidthUm, pixelHeightUm, safeSource);
    }

    private static boolean validPixelSize(double pixelWidthUm, double pixelHeightUm) {
        return Double.isFinite(pixelWidthUm) && Double.isFinite(pixelHeightUm)
                && pixelWidthUm > 0.0 && pixelHeightUm > 0.0;
    }

    private static boolean isMicronUnit(String unit) {
        if (unit == null) return false;
        String normalized = unit.trim().toLowerCase(java.util.Locale.ROOT)
                .replace('\u00b5', 'u')
                .replace('\u03bc', 'u');
        return "um".equals(normalized)
                || "micron".equals(normalized)
                || "microns".equals(normalized)
                || "micrometer".equals(normalized)
                || "micrometers".equals(normalized)
                || "micrometre".equals(normalized)
                || "micrometres".equals(normalized);
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    public static final class Result {
        private final double pixelWidthUm;
        private final double pixelHeightUm;
        private final CalibrationSource source;

        Result(double pixelWidthUm, double pixelHeightUm,
                CalibrationSource source) {
            this.pixelWidthUm = pixelWidthUm;
            this.pixelHeightUm = pixelHeightUm;
            this.source = source == null ? CalibrationSource.NONE : source;
        }

        public boolean isAvailable() {
            return source != CalibrationSource.NONE
                    && validPixelSize(pixelWidthUm, pixelHeightUm);
        }

        public double pixelWidthUm() {
            return pixelWidthUm;
        }

        public double pixelHeightUm() {
            return pixelHeightUm;
        }

        public CalibrationSource source() {
            return source;
        }
    }
}
