/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.io;

/** Downsamples unsigned 16-bit planes by preserving the maximum source value. */
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

    private static void requirePositive(String name, int value) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
