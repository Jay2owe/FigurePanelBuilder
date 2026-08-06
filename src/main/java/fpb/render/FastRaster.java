/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.render;

import fpb.io.HistogramCache;
import fpb.util.CancellationCheck;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.util.List;

/** LUT-based unsigned 16-bit renderer using direct TYPE_INT_RGB raster access. */
public final class FastRaster {

    private FastRaster() {}

    public static byte[] buildLut(DisplayRange range) {
        DisplayRange locked = DisplayRange.requireValid(range, "unnamed", "unnamed");
        return buildLut(locked.min(), locked.max());
    }

    public static byte[] buildLut(int min, int max) {
        DisplayRange locked = new DisplayRange(min, max);
        DisplayRange.requireValid(locked, "unnamed", "unnamed");
        byte[] lut = new byte[HistogramCache.BIN_COUNT];
        double scale = 255.0 / (max - min);
        for (int v = 0; v < lut.length; v++) {
            int grey = (v <= min) ? 0 : (v >= max) ? 255
                    : (int) ((v - min) * scale + 0.5);
            lut[v] = (byte) grey;
        }
        return lut;
    }

    public static BufferedImage render(short[] raw, int sourceWidth, int sourceHeight,
            DisplayRange range, ChannelColour colour, int targetWidth, int targetHeight) {
        BufferedImage image = new BufferedImage(targetWidth, targetHeight,
                BufferedImage.TYPE_INT_RGB);
        renderInto(raw, sourceWidth, sourceHeight, buildLut(range), colour, image);
        return image;
    }

    public static void renderInto(short[] raw, int sourceWidth, int sourceHeight,
            byte[] lut, ChannelColour colour, BufferedImage target) {
        requirePlane(raw, sourceWidth, sourceHeight);
        requireLut(lut);
        if (target == null) throw new IllegalArgumentException("target image must not be null");
        if (target.getType() != BufferedImage.TYPE_INT_RGB) {
            throw new IllegalArgumentException("target image must be TYPE_INT_RGB");
        }
        ChannelColour mask = colour == null ? ChannelColour.GREY : colour;
        boolean red = mask.red();
        boolean green = mask.green();
        boolean blue = mask.blue();
        int colourMask = (red ? 0x010000 : 0)
                | (green ? 0x000100 : 0)
                | (blue ? 0x000001 : 0);
        int targetWidth = target.getWidth();
        int targetHeight = target.getHeight();
        int[] dst = pixels(target);
        if (sourceWidth == targetWidth && sourceHeight == targetHeight) {
            int i = 0;
            int unrolledEnd = dst.length - (dst.length % 8);
            for (; i < unrolledEnd; i += 8) {
                dst[i] = (lut[raw[i] & 0xFFFF] & 0xFF) * colourMask;
                dst[i + 1] = (lut[raw[i + 1] & 0xFFFF] & 0xFF) * colourMask;
                dst[i + 2] = (lut[raw[i + 2] & 0xFFFF] & 0xFF) * colourMask;
                dst[i + 3] = (lut[raw[i + 3] & 0xFFFF] & 0xFF) * colourMask;
                dst[i + 4] = (lut[raw[i + 4] & 0xFFFF] & 0xFF) * colourMask;
                dst[i + 5] = (lut[raw[i + 5] & 0xFFFF] & 0xFF) * colourMask;
                dst[i + 6] = (lut[raw[i + 6] & 0xFFFF] & 0xFF) * colourMask;
                dst[i + 7] = (lut[raw[i + 7] & 0xFFFF] & 0xFF) * colourMask;
            }
            for (; i < dst.length; i++) {
                dst[i] = (lut[raw[i] & 0xFFFF] & 0xFF) * colourMask;
            }
            return;
        }
        int out = 0;
        for (int y = 0; y < targetHeight; y++) {
            int sourceY = y * sourceHeight / targetHeight;
            int sourceOffset = sourceY * sourceWidth;
            for (int x = 0; x < targetWidth; x++) {
                int sourceX = x * sourceWidth / targetWidth;
                int grey = lut[raw[sourceOffset + sourceX] & 0xFFFF] & 0xFF;
                dst[out++] = grey * colourMask;
            }
        }
    }

    public static GreyPlane greyPlane(short[] raw, int sourceWidth, int sourceHeight,
            DisplayRange range, int targetWidth, int targetHeight) {
        try {
            return greyPlane(raw, sourceWidth, sourceHeight, range, targetWidth,
                    targetHeight, CancellationCheck.NEVER_CANCELLED);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static GreyPlane greyPlane(short[] raw, int sourceWidth, int sourceHeight,
            DisplayRange range, int targetWidth, int targetHeight,
            CancellationCheck cancelCheck) throws IOException {
        requirePlane(raw, sourceWidth, sourceHeight);
        requirePositive("targetWidth", targetWidth);
        requirePositive("targetHeight", targetHeight);
        byte[] lut = buildLut(range);
        byte[] grey = new byte[targetWidth * targetHeight];
        if (sourceWidth == targetWidth && sourceHeight == targetHeight) {
            for (int i = 0; i < grey.length; i++) {
                if ((i & 0x3fff) == 0) checkCancelled(cancelCheck);
                grey[i] = lut[raw[i] & 0xFFFF];
            }
            return new GreyPlane(targetWidth, targetHeight, grey, false);
        }
        int dst = 0;
        for (int y = 0; y < targetHeight; y++) {
            checkCancelled(cancelCheck);
            int sourceY = y * sourceHeight / targetHeight;
            int sourceOffset = sourceY * sourceWidth;
            for (int x = 0; x < targetWidth; x++) {
                int sourceX = x * sourceWidth / targetWidth;
                grey[dst++] = lut[raw[sourceOffset + sourceX] & 0xFFFF];
            }
        }
        return new GreyPlane(targetWidth, targetHeight, grey, false);
    }

    public static BufferedImage colourize(GreyPlane grey, ChannelColour colour) {
        try {
            return colourize(grey, colour, CancellationCheck.NEVER_CANCELLED);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static BufferedImage colourize(GreyPlane grey, ChannelColour colour,
            CancellationCheck cancelCheck) throws IOException {
        if (grey == null) throw new IllegalArgumentException("grey plane must not be null");
        ChannelColour mask = colour == null ? ChannelColour.GREY : colour;
        BufferedImage image = new BufferedImage(grey.width(), grey.height(),
                BufferedImage.TYPE_INT_RGB);
        int[] dst = pixels(image);
        byte[] values = grey.valuesUnsafe();
        boolean red = mask.red();
        boolean green = mask.green();
        boolean blue = mask.blue();
        for (int i = 0; i < dst.length; i++) {
            if ((i & 0x3fff) == 0) checkCancelled(cancelCheck);
            dst[i] = rgb(values[i] & 0xFF, red, green, blue);
        }
        return image;
    }

    public static BufferedImage merge(List<GreyPlane> greyPlanes,
            List<ChannelColour> colours) {
        try {
            return merge(greyPlanes, colours, CancellationCheck.NEVER_CANCELLED);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static BufferedImage merge(List<GreyPlane> greyPlanes,
            List<ChannelColour> colours, CancellationCheck cancelCheck)
            throws IOException {
        if (greyPlanes == null || greyPlanes.isEmpty()) {
            throw new IllegalArgumentException("greyPlanes must not be empty");
        }
        if (colours == null || colours.size() != greyPlanes.size()) {
            throw new IllegalArgumentException("colours must match greyPlanes");
        }
        GreyPlane first = greyPlanes.get(0);
        if (first == null) throw new IllegalArgumentException("grey plane 0 is null");
        int width = first.width();
        int height = first.height();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] dst = pixels(image);
        for (int p = 0; p < greyPlanes.size(); p++) {
            GreyPlane grey = greyPlanes.get(p);
            if (grey == null) throw new IllegalArgumentException("grey plane " + p + " is null");
            if (grey.width() != width || grey.height() != height) {
                throw new IllegalArgumentException("grey planes have mismatched sizes");
            }
            ChannelColour colour = colours.get(p) == null ? ChannelColour.GREY : colours.get(p);
            byte[] values = grey.valuesUnsafe();
            boolean red = colour.red();
            boolean green = colour.green();
            boolean blue = colour.blue();
            for (int i = 0; i < dst.length; i++) {
                if ((i & 0x3fff) == 0) checkCancelled(cancelCheck);
                int next = rgb(values[i] & 0xFF, red, green, blue);
                int r = Math.min(255, ((dst[i] >> 16) & 0xFF)
                        + ((next >> 16) & 0xFF));
                int g = Math.min(255, ((dst[i] >> 8) & 0xFF)
                        + ((next >> 8) & 0xFF));
                int b = Math.min(255, (dst[i] & 0xFF) + (next & 0xFF));
                dst[i] = (r << 16) | (g << 8) | b;
            }
        }
        return image;
    }

    private static void checkCancelled(CancellationCheck cancelCheck)
            throws IOException {
        if (cancelCheck != null && cancelCheck.isCancelled()) {
            throw new IOException("Export cancelled.");
        }
    }

    static int[] pixels(BufferedImage image) {
        return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }

    private static int rgb(int grey, boolean red, boolean green, boolean blue) {
        return (red ? grey << 16 : 0) | (green ? grey << 8 : 0) | (blue ? grey : 0);
    }

    private static void requirePlane(short[] raw, int width, int height) {
        if (raw == null) throw new IllegalArgumentException("raw plane must not be null");
        requirePositive("sourceWidth", width);
        requirePositive("sourceHeight", height);
        if (raw.length != width * height) {
            throw new IllegalArgumentException("raw plane length does not match dimensions");
        }
    }

    private static void requireLut(byte[] lut) {
        if (lut == null || lut.length != HistogramCache.BIN_COUNT) {
            throw new IllegalArgumentException("lut must have 65536 entries");
        }
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    public static final class GreyPlane {
        private final int width;
        private final int height;
        private final byte[] values;

        public GreyPlane(int width, int height, byte[] values) {
            this(width, height, values, true);
        }

        private GreyPlane(int width, int height, byte[] values, boolean cloneValues) {
            requirePositive("width", width);
            requirePositive("height", height);
            if (values == null || values.length != width * height) {
                throw new IllegalArgumentException("grey values do not match dimensions");
            }
            this.width = width;
            this.height = height;
            this.values = cloneValues ? values.clone() : values;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public byte[] values() {
            return values.clone();
        }

        byte[] valuesUnsafe() {
            return values;
        }
    }
}
