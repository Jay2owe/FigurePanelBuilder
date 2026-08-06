/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.figure;

import java.awt.image.BufferedImage;

/** Immutable orthogonal orientation for one logical microscopy image. */
public final class ImageOrientation {

    public enum Action {
        ROTATE_LEFT,
        ROTATE_RIGHT,
        FLIP_HORIZONTAL,
        FLIP_VERTICAL
    }

    public static final ImageOrientation IDENTITY =
            new ImageOrientation(1, 0, 0, 1, "normal");

    private static final ImageOrientation[] VALUES = new ImageOrientation[] {
            IDENTITY,
            new ImageOrientation(0, -1, 1, 0, "rotate-right"),
            new ImageOrientation(-1, 0, 0, -1, "rotate-180"),
            new ImageOrientation(0, 1, -1, 0, "rotate-left"),
            new ImageOrientation(-1, 0, 0, 1, "flip-horizontal"),
            new ImageOrientation(1, 0, 0, -1, "flip-vertical"),
            new ImageOrientation(0, 1, 1, 0, "transpose"),
            new ImageOrientation(0, -1, -1, 0, "anti-transpose")
    };

    private final int m00;
    private final int m01;
    private final int m10;
    private final int m11;
    private final String token;

    private ImageOrientation(int m00, int m01, int m10, int m11,
            String token) {
        this.m00 = m00;
        this.m01 = m01;
        this.m10 = m10;
        this.m11 = m11;
        this.token = token;
    }

    public static ImageOrientation fromToken(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) return IDENTITY;
        for (ImageOrientation orientation : VALUES) {
            if (orientation.token.equalsIgnoreCase(clean)) return orientation;
        }
        throw new IllegalArgumentException("Unknown image orientation: " + value);
    }

    /** Applies the requested action in the currently displayed coordinate system. */
    public ImageOrientation then(Action action) {
        if (action == null) return this;
        int a00;
        int a01;
        int a10;
        int a11;
        switch (action) {
            case ROTATE_LEFT:
                a00 = 0; a01 = 1; a10 = -1; a11 = 0;
                break;
            case ROTATE_RIGHT:
                a00 = 0; a01 = -1; a10 = 1; a11 = 0;
                break;
            case FLIP_HORIZONTAL:
                a00 = -1; a01 = 0; a10 = 0; a11 = 1;
                break;
            case FLIP_VERTICAL:
                a00 = 1; a01 = 0; a10 = 0; a11 = -1;
                break;
            default:
                return this;
        }
        return forMatrix(a00 * m00 + a01 * m10,
                a00 * m01 + a01 * m11,
                a10 * m00 + a11 * m10,
                a10 * m01 + a11 * m11);
    }

    public BufferedImage apply(BufferedImage source) {
        if (source == null) throw new IllegalArgumentException("source is required");
        if (isIdentity()) return source;
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int outputWidth = orientedWidth(sourceWidth, sourceHeight);
        int outputHeight = orientedHeight(sourceWidth, sourceHeight);
        int minX = minimum(m00, sourceWidth, m01, sourceHeight);
        int minY = minimum(m10, sourceWidth, m11, sourceHeight);
        int[] input = source.getRGB(0, 0, sourceWidth, sourceHeight,
                null, 0, sourceWidth);
        int[] output = new int[outputWidth * outputHeight];
        for (int y = 0; y < sourceHeight; y++) {
            int inputOffset = y * sourceWidth;
            for (int x = 0; x < sourceWidth; x++) {
                int outputX = m00 * x + m01 * y - minX;
                int outputY = m10 * x + m11 * y - minY;
                output[outputY * outputWidth + outputX] = input[inputOffset + x];
            }
        }
        int type = source.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage transformed = new BufferedImage(outputWidth, outputHeight, type);
        transformed.setRGB(0, 0, outputWidth, outputHeight,
                output, 0, outputWidth);
        return transformed;
    }

    public int orientedWidth(int width, int height) {
        return m00 == 0 ? height : width;
    }

    public int orientedHeight(int width, int height) {
        return m10 == 0 ? height : width;
    }

    public double orientedPixelWidth(double pixelWidth, double pixelHeight) {
        return m00 == 0 ? pixelHeight : pixelWidth;
    }

    public double orientedPixelHeight(double pixelWidth, double pixelHeight) {
        return m10 == 0 ? pixelHeight : pixelWidth;
    }

    public CalibrationCheck.Result orientCalibration(
            CalibrationCheck.Result calibration) {
        if (calibration == null || !calibration.isAvailable() || !swapsAxes()) {
            return calibration == null ? CalibrationCheck.none() : calibration;
        }
        return new CalibrationCheck.Result(calibration.pixelHeightUm(),
                calibration.pixelWidthUm(), calibration.source());
    }

    public boolean swapsAxes() {
        return m00 == 0;
    }

    public boolean isIdentity() {
        return this == IDENTITY || (m00 == 1 && m01 == 0 && m10 == 0 && m11 == 1);
    }

    public String token() {
        return token;
    }

    @Override
    public String toString() {
        return token;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ImageOrientation)) return false;
        ImageOrientation value = (ImageOrientation) other;
        return m00 == value.m00 && m01 == value.m01
                && m10 == value.m10 && m11 == value.m11;
    }

    @Override
    public int hashCode() {
        int result = m00;
        result = 31 * result + m01;
        result = 31 * result + m10;
        return 31 * result + m11;
    }

    private static ImageOrientation forMatrix(int m00, int m01,
            int m10, int m11) {
        for (ImageOrientation orientation : VALUES) {
            if (orientation.m00 == m00 && orientation.m01 == m01
                    && orientation.m10 == m10 && orientation.m11 == m11) {
                return orientation;
            }
        }
        throw new IllegalStateException("Unsupported image orientation matrix.");
    }

    private static int minimum(int firstCoefficient, int firstSize,
            int secondCoefficient, int secondSize) {
        int first = firstCoefficient < 0 ? firstCoefficient * (firstSize - 1) : 0;
        int second = secondCoefficient < 0
                ? secondCoefficient * (secondSize - 1) : 0;
        return first + second;
    }
}
