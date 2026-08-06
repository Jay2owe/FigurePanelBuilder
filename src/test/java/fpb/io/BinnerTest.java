/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.io;

import org.junit.Test;

import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BinnerTest {

    @Test
    public void maxBinPreservesPunctaPeak() throws Exception {
        File puncta = fixture("puncta.tif");

        ImageLoader.LoadedImage loaded =
                new ImageLoader(150, 1).loadImage(puncta);
        short[] binned = loaded.binnedPlane(0).pixels();

        int max = unsignedMax(binned);
        assertEquals(60000, max, 600);

        short[] source = new short[300 * 300];
        source[77 * 300 + 123] = (short) 60000;
        short[] averaged = areaAverage(source, 300, 300, 150, 150);
        assertTrue(unsignedMax(averaged) < 60000 * 0.99);
    }

    @Test
    public void maxBinUsesUnsignedShortOrdering() {
        short[] source = new short[] {
                1, (short) 65000,
                2, 3
        };
        short[] binned = Binner.maxBin(source, 2, 2, 1, 1);
        assertEquals(65000, binned[0] & 0xFFFF);
    }

    @Test
    public void renderedImageMaxBinPreservesSparseColourPeaksWithoutAveraging() {
        BufferedImage source = new BufferedImage(4, 4,
                BufferedImage.TYPE_INT_ARGB);
        source.setRGB(1, 1, 0xFFFF0000);
        source.setRGB(3, 3, 0xFF00FF00);

        BufferedImage binned = Binner.maxBin(source, 2, 2);

        assertEquals(0xFFFF0000, binned.getRGB(0, 0));
        assertEquals(0xFF00FF00, binned.getRGB(1, 1));
        assertEquals(0x00000000, binned.getRGB(1, 0));
        assertEquals(0x00000000, binned.getRGB(0, 1));
    }

    private static int unsignedMax(short[] pixels) {
        int max = 0;
        for (int i = 0; i < pixels.length; i++) {
            int value = pixels[i] & 0xFFFF;
            if (value > max) max = value;
        }
        return max;
    }

    private static short[] areaAverage(short[] source, int sourceWidth, int sourceHeight,
            int destWidth, int destHeight) {
        short[] dest = new short[destWidth * destHeight];
        for (int dy = 0; dy < destHeight; dy++) {
            int y0 = dy * sourceHeight / destHeight;
            int y1 = Math.max(y0 + 1, (dy + 1) * sourceHeight / destHeight);
            for (int dx = 0; dx < destWidth; dx++) {
                int x0 = dx * sourceWidth / destWidth;
                int x1 = Math.max(x0 + 1, (dx + 1) * sourceWidth / destWidth);
                long sum = 0L;
                int count = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        sum += source[y * sourceWidth + x] & 0xFFFF;
                        count++;
                    }
                }
                dest[dy * destWidth + dx] = (short) Math.round(sum / (double) count);
            }
        }
        return dest;
    }

    private static File fixture(String path) {
        return new File("src/test/resources/fixtures", path).getAbsoluteFile();
    }
}
