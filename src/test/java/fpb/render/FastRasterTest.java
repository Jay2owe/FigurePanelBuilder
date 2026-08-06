/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.render;

import org.junit.Test;

import java.awt.image.BufferedImage;
import java.util.Arrays;

import ij.process.ShortProcessor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FastRasterTest {

    @Test
    public void renderedOutputMatchesKnownRangeByteForByte() {
        short[] raw = {
                0, 10,
                (short) 200, (short) 255
        };

        BufferedImage image = FastRaster.render(raw, 2, 2,
                new DisplayRange(0, 255), ChannelColour.RED, 2, 2);

        assertArrayEquals(new int[] {
                0x000000, 0x0A0000,
                0xC80000, 0xFF0000
        }, FastRaster.pixels(image));
    }

    @Test
    public void nearestNeighbourDecimationIsFoldedIntoLookupLoop() {
        short[] raw = {
                0, 100, 200, 300,
                400, 500, 600, 700,
                800, 900, 1000, 1100,
                1200, 1300, 1400, 1500
        };

        BufferedImage image = FastRaster.render(raw, 4, 4,
                new DisplayRange(0, 1500), ChannelColour.GREY, 2, 2);

        assertArrayEquals(new int[] {
                0x000000, 0x222222,
                0x888888, 0xAAAAAA
        }, FastRaster.pixels(image));
    }

    @Test
    public void magentaGreenCyanMergeUsesGeneralSaturatingAddition() {
        FastRaster.GreyPlane magenta = plane(200, 100);
        FastRaster.GreyPlane green = plane(80, 180);
        FastRaster.GreyPlane cyan = plane(90, 100);

        BufferedImage image = FastRaster.merge(Arrays.asList(magenta, green, cyan),
                Arrays.asList(ChannelColour.MAGENTA, ChannelColour.GREEN, ChannelColour.CYAN));

        assertArrayEquals(new int[] {
                0xC8AAFF,
                0x64FFC8
        }, FastRaster.pixels(image));
    }

    @Test
    public void directRasterPathIsAtLeastTenTimesFasterThanImageProcessorSetRgbLoop() {
        int width = 2048;
        int height = 1024;
        short[] raw = new short[width * height];
        for (int i = 0; i < raw.length; i++) raw[i] = (short) (i * 31);
        DisplayRange range = new DisplayRange(100, 60000);
        ChannelColour colour = ChannelColour.MAGENTA;
        ShortProcessor processor = new ShortProcessor(width, height, raw, null);
        byte[] lut = FastRaster.buildLut(range);
        BufferedImage directImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        BufferedImage setRgbImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        for (int i = 0; i < 3; i++) {
            FastRaster.renderInto(raw, width, height, lut, colour, directImage);
            imageProcessorSetRgbReference(processor, range, colour, setRgbImage);
        }

        long directBest = Long.MAX_VALUE;
        long setRgbBest = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            FastRaster.renderInto(raw, width, height, lut, colour, directImage);
            directBest = Math.min(directBest, System.nanoTime() - t0);

            t0 = System.nanoTime();
            imageProcessorSetRgbReference(processor, range, colour, setRgbImage);
            setRgbBest = Math.min(setRgbBest, System.nanoTime() - t0);
        }

        assertTrue("direct=" + directBest + " ns, setRGB=" + setRgbBest + " ns",
                setRgbBest >= directBest * 10L);
    }

    private static FastRaster.GreyPlane plane(int left, int right) {
        return new FastRaster.GreyPlane(2, 1, new byte[] { (byte) left, (byte) right });
    }

    private static BufferedImage imageProcessorSetRgbReference(ShortProcessor processor,
            DisplayRange range, ChannelColour colour, BufferedImage image) {
        int width = processor.getWidth();
        int height = processor.getHeight();
        double scale = 255.0 / (range.max() - range.min());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double value = processor.getPixelValue(x, y);
                int grey = value <= range.min() ? 0 : value >= range.max() ? 255
                        : (int) ((value - range.min()) * scale + 0.5);
                image.setRGB(x, y, 1, 1, new int[] { colour.rgb(grey) }, 0, 1);
            }
        }
        assertEquals(width, image.getWidth());
        return image;
    }
}
