/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.chooser;

import org.junit.Test;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.JList;

import static org.junit.Assert.assertEquals;

public class RowRendererTest {

    @Test
    public void fullscreenWidthDoesNotStretchCachedRowHorizontally() {
        BufferedImage square = new BufferedImage(100, 100,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D sourceGraphics = square.createGraphics();
        try {
            sourceGraphics.setColor(Color.RED);
            sourceGraphics.fillRect(0, 0, 100, 100);
        } finally {
            sourceGraphics.dispose();
        }
        Map<Integer, BufferedImage> cache =
                new LinkedHashMap<Integer, BufferedImage>();
        cache.put(Integer.valueOf(0), square);
        RowRenderer renderer = new RowRenderer(cache);
        RowImage.SubjectRow row = new RowImage.SubjectRow(
                "Control", "S1", 0, false);
        Component component = renderer.getListCellRendererComponent(
                new JList<RowImage.SubjectRow>(), row, 0, false, false);
        component.setSize(200, 100);
        BufferedImage painted = new BufferedImage(200, 100,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = painted.createGraphics();
        try {
            component.paint(graphics);
        } finally {
            graphics.dispose();
        }

        assertEquals(Color.RED.getRGB(), painted.getRGB(50, 50));
        assertEquals(new Color(244, 246, 248).getRGB(),
                painted.getRGB(150, 50));
    }
}
