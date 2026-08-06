/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.chooser;

import fpb.render.FPBRenderer;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;

/** Rubber-stamp renderer that paints one pre-composed row image per cell. */
public final class RowRenderer extends JPanel
        implements ListCellRenderer<RowImage.SubjectRow> {

    private static final Color EMPTY = new Color(244, 246, 248);

    private final Map<Integer, BufferedImage> rowImageCache;
    private final AtomicInteger paintedCellCount = new AtomicInteger();
    private BufferedImage image;
    private boolean selected;

    public RowRenderer(Map<Integer, BufferedImage> rowImageCache) {
        if (rowImageCache == null) {
            throw new IllegalArgumentException("rowImageCache must not be null");
        }
        this.rowImageCache = rowImageCache;
        setOpaque(true);
    }

    @Override
    public Component getListCellRendererComponent(
            JList<? extends RowImage.SubjectRow> list, RowImage.SubjectRow value,
            int index, boolean isSelected, boolean cellHasFocus) {
        image = rowImageCache.get(Integer.valueOf(index));
        selected = isSelected;
        return this;
    }

    @Override
    protected void paintComponent(Graphics g) {
        paintedCellCount.incrementAndGet();
        g.setColor(EMPTY);
        g.fillRect(0, 0, getWidth(), getHeight());
        if (image == null) {
            return;
        }
        if (getWidth() <= 0 || getHeight() <= 0) return;
        int[] fitted = FPBRenderer.aspectFitDimensions(image.getWidth(),
                image.getHeight(), getWidth(), getHeight());
        int drawY = (getHeight() - fitted[1]) / 2;
        Graphics2D graphics = (Graphics2D) g.create();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, drawY, fitted[0], fitted[1], null);
        } finally {
            graphics.dispose();
        }
        if (selected) {
            g.setColor(new Color(213, 94, 0));
            g.drawRect(1, 1, Math.max(0, getWidth() - 3),
                    Math.max(0, getHeight() - 3));
        }
    }

    public int paintedCellCount() {
        return paintedCellCount.get();
    }
}
