/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.io;

import java.awt.image.BufferedImage;
import java.io.IOException;

/** Downsamples planes and rendered images while preserving source maxima. */
public final class Binner {

    public static final int DEFAULT_LONG_EDGE = 150;

    private Binner() {}

    public static int[] scaledDimensions(int width, int height, int targetLongEdge) {
        requirePositive("width", width);
        requirePositive("height", height);
        requirePositive("targetLongEdge", targetLongEdge);
        int longEdge = Math.max(width, height);
        if (longEdge <= targetLongEdge) return new int[] { width, height };
        double scale = targetLongEdge / (double) longEdge;
        int scaledWidth = Math.max(1, (int) Math.round(width * scale));
        int scaledHeight = Math.max(1, (int) Math.round(height * scale));
        return new int[] { scaledWidth, scaledHeight };
    }

    public static short[] maxBin(short[] source, int sourceWidth, int sourceHeight,
            int targetLongEdge) {
        int[] size = scaledDimensions(sourceWidth, sourceHeight, targetLongEdge);
        return maxBin(source, sourceWidth, sourceHeight, size[0], size[1]);
    }

    /** Downsample by taking the maximum of each source block. */
    public static short[] maxBin(short[] source, int sourceWidth, int sourceHeight,
            int destWidth, int destHeight) {
        try {
            return maxBin(source, sourceWidth, sourceHeight, destWidth, destHeight,
                    ImageLoader.NEVER_CANCELLED);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Downsample while polling a cooperative cancellation hook between scan lines. */
    static short[] maxBin(short[] source, int sourceWidth, int sourceHeight,
            int destWidth, int destHeight, ImageLoader.CancelCheck cancelCheck)
            throws IOException {
        if (source == null) throw new IllegalArgumentException("source plane is null");
        requirePositive("sourceWidth", sourceWidth);
        requirePositive("sourceHeight", sourceHeight);
        requirePositive("destWidth", destWidth);
        requirePositive("destHeight", destHeight);
        if (source.length != sourceWidth * sourceHeight) {
            throw new IllegalArgumentException("source length does not match dimensions");
        }
        short[] dest = new short[destWidth * destHeight];
        for (int dy = 0; dy < destHeight; dy++) {
            ImageLoader.checkCancelled(cancelCheck);
            int y0 = dy * sourceHeight / destHeight;
            int y1 = Math.max(y0 + 1, (dy + 1) * sourceHeight / destHeight);
            y1 = Math.min(y1, sourceHeight);
            for (int dx = 0; dx < destWidth; dx++) {
                int x0 = dx * sourceWidth / destWidth;
                int x1 = Math.max(x0 + 1, (dx + 1) * sourceWidth / destWidth);
                x1 = Math.min(x1, sourceWidth);
                int max = 0;
                for (int y = y0; y < y1; y++) {
                    int offset = y * sourceWidth;
                    for (int x = x0; x < x1; x++) {
                        int value = source[offset + x] & 0xFFFF;
                        if (value > max) max = value;
                    }
                }
                dest[dy * destWidth + dx] = (short) max;
            }
        }
        return dest;
    }

    /**
     * Fits a rendered image to exact dimensions using per-component maximum
     * binning. When a target dimension is larger than its source dimension,
     * the same block mapping behaves as nearest-neighbour replication.
     */
    public static BufferedImage maxBin(BufferedImage source, int destWidth,
            int destHeight) {
        if (source == null) throw new IllegalArgumentException("source image is null");
        requirePositive("destWidth", destWidth);
        requirePositive("destHeight", destHeight);
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        requirePositive("sourceWidth", sourceWidth);
        requirePositive("sourceHeight", sourceHeight);

        BufferedImage dest = new BufferedImage(destWidth, destHeight,
                BufferedImage.TYPE_INT_ARGB);
        int[] output = new int[destWidth * destHeight];
        int[] row = new int[sourceWidth];
        for (int dy = 0; dy < destHeight; dy++) {
            int y0 = dy * sourceHeight / destHeight;
            int y1 = Math.max(y0 + 1, (dy + 1) * sourceHeight / destHeight);
            y1 = Math.min(y1, sourceHeight);
            for (int y = y0; y < y1; y++) {
                source.getRGB(0, y, sourceWidth, 1, row, 0, sourceWidth);
                for (int dx = 0; dx < destWidth; dx++) {
                    int x0 = dx * sourceWidth / destWidth;
                    int x1 = Math.max(x0 + 1,
                            (dx + 1) * sourceWidth / destWidth);
                    x1 = Math.min(x1, sourceWidth);
                    int index = dy * destWidth + dx;
                    int maximum = output[index];
                    for (int x = x0; x < x1; x++) {
                        maximum = componentMaximum(maximum, row[x]);
                    }
                    output[index] = maximum;
                }
            }
        }
        dest.setRGB(0, 0, destWidth, destHeight, output, 0, destWidth);
        return dest;
    }

    private static int componentMaximum(int left, int right) {
        int alpha = Math.max((left >>> 24) & 0xFF, (right >>> 24) & 0xFF);
        int red = Math.max((left >>> 16) & 0xFF, (right >>> 16) & 0xFF);
        int green = Math.max((left >>> 8) & 0xFF, (right >>> 8) & 0xFF);
        int blue = Math.max(left & 0xFF, right & 0xFF);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
