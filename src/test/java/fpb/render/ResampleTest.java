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

import static org.junit.Assert.assertEquals;

public class ResampleTest {

    @Test
    public void shrinkingAveragesCoveredArea() {
        BufferedImage source = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, 0xFF0000);
        source.setRGB(1, 1, 0x00FF00);
        source.setRGB(3, 0, 0x0000FF);
        BufferedImage out = Resample.toSize(source, 2, 1);
        assertEquals(0x404000, out.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(0x000040, out.getRGB(1, 0) & 0xFFFFFF);
    }

    @Test
    public void nonIntegerShrinkWeightsPartialPixels() {
        BufferedImage source = new BufferedImage(3, 1, BufferedImage.TYPE_INT_RGB);
        source.setRGB(1, 0, 0xFFFFFF);
        BufferedImage out = Resample.toSize(source, 2, 1);
        // Each output pixel covers 1.5 source pixels; half of the bright one.
        assertEquals(85, out.getRGB(0, 0) & 0xFF);
        assertEquals(85, out.getRGB(1, 0) & 0xFF);
    }

    @Test
    public void enlargingRepeatsPixelsWithoutInventingValues() {
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, 0xFFFFFF);
        BufferedImage out = Resample.toSize(source, 6, 3);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 6; x++) {
                assertEquals(x < 3 ? 0xFFFFFF : 0, out.getRGB(x, y) & 0xFFFFFF);
            }
        }
    }
}
