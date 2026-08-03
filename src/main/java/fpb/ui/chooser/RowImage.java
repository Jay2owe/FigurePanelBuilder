/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.chooser;

import fpb.io.HistogramCache;
import fpb.io.PlaneCache;
import fpb.render.FPBRenderer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.List;

/** Composes one subject row into a single cached image for list-cell blitting. */
public final class RowImage {

    public static final int DEFAULT_SPINE_WIDTH = 88;
    public static final int DEFAULT_TILE_WIDTH = 150;
    public static final int DEFAULT_TILE_HEIGHT = 150;
    public static final int DEFAULT_GAP = 8;
    public static final int DEFAULT_PADDING = 6;

    private static final Color BACKGROUND = new Color(248, 249, 250);
    private static final Color SELECTED_BACKGROUND = new Color(232, 239, 246);
    private static final Color BORDER = new Color(188, 196, 204);
    private static final Color TILE_BACKGROUND = new Color(30, 32, 34);
    private static final Color TEXT = new Color(45, 48, 51);
    private static final Color MUTED = new Color(105, 112, 120);
    private static final Color SUGGESTED = new Color(86, 180, 233);
    private static final Color CHOSEN = new Color(213, 94, 0);

    private RowImage() {}

    public static BufferedImage renderSubject(SubjectRow row, PlaneCache planes,
            HistogramCache histograms, List<FPBRenderer.ChannelRequest> channels,
            Layout layout, boolean selected) {
        requireRow(row);
        if (planes == null) throw new IllegalArgumentException("planes must not be null");
        if (histograms == null) throw new IllegalArgumentException("histograms must not be null");
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("channels must not be empty");
        }
        Layout checked = requireLayout(layout);
        FPBRenderer.PanelRender panel = new FPBRenderer().renderPanel(planes, histograms,
                row.imageIndex(), channels, checked.deviceTileWidth(),
                checked.deviceTileHeight());
        return compose(row, panel, checked, selected);
    }

    public static BufferedImage compose(SubjectRow row, FPBRenderer.PanelRender panel,
            Layout layout, boolean selected) {
        requireRow(row);
        if (panel == null) throw new IllegalArgumentException("panel must not be null");
        Layout checked = requireLayout(layout);
        BufferedImage image = new BufferedImage(checked.deviceRowWidth(),
                checked.deviceRowHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.scale(checked.scaleX(), checked.scaleY());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            paintBackground(g, checked, selected);
            paintSpine(g, row, checked, selected);
            paintTiles(g, panel, checked);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void paintBackground(Graphics2D g, Layout layout, boolean selected) {
        g.setColor(selected ? SELECTED_BACKGROUND : BACKGROUND);
        g.fillRect(0, 0, layout.rowWidth(), layout.rowHeight());
        g.setColor(BORDER);
        g.drawLine(0, layout.rowHeight() - 1, layout.rowWidth(), layout.rowHeight() - 1);
    }

    private static void paintSpine(Graphics2D g, SubjectRow row,
            Layout layout, boolean selected) {
        int x = layout.padding();
        int y = layout.padding();
        int width = layout.spineWidth() - layout.padding() * 2;
        int height = layout.tileHeight();
        BufferedImage spine = row.spineImage(width, height, selected);
        if (spine != null) {
            g.drawImage(spine, x, y, width, height, null);
            return;
        }

        g.setColor(new Color(239, 242, 245));
        g.fillRect(x, y, width, height);
        g.setColor(BORDER);
        g.drawRect(x, y, width, height);

        int markerY = y + 10;
        if (row.suggested()) {
            g.setColor(SUGGESTED);
            int cx = x + 9;
            int cy = markerY;
            int[] xs = { cx, cx + 5, cx, cx - 5 };
            int[] ys = { cy - 5, cy, cy + 5, cy };
            g.fillPolygon(xs, ys, 4);
        }
        if (selected) {
            g.setColor(CHOSEN);
            g.setStroke(new BasicStroke(2f));
            g.drawOval(x + 21, markerY - 5, 10, 10);
        }

        g.setColor(TEXT);
        FontMetrics metrics = g.getFontMetrics();
        String subject = abbreviate(row.subject(), metrics, Math.max(20, width - 8));
        g.drawString(subject, x + 4, y + height / 2);
        g.setColor(MUTED);
        String group = abbreviate(row.group(), metrics, Math.max(20, width - 8));
        g.drawString(group, x + 4, y + height / 2 + metrics.getHeight());
    }

    private static void paintTiles(Graphics2D g, FPBRenderer.PanelRender panel,
            Layout layout) {
        int x = layout.spineWidth() + layout.gap();
        int y = layout.padding();
        List<BufferedImage> channelImages = panel.channelImages();
        for (int i = 0; i < channelImages.size(); i++) {
            paintTile(g, channelImages.get(i), x, y, layout);
            x += layout.tileWidth() + layout.gap();
        }
        paintTile(g, panel.mergeImage(), x, y, layout);
    }

    private static void paintTile(Graphics2D g, BufferedImage image, int x, int y,
            Layout layout) {
        g.setColor(TILE_BACKGROUND);
        g.fillRect(x, y, layout.tileWidth(), layout.tileHeight());
        if (image != null) {
            g.drawImage(image, x, y, layout.tileWidth(), layout.tileHeight(), null);
        }
        g.setColor(BORDER);
        g.drawRect(x, y, layout.tileWidth(), layout.tileHeight());
    }

    private static String abbreviate(String value, FontMetrics metrics, int maxWidth) {
        String text = value == null || value.length() == 0 ? "subject" : value;
        if (metrics.stringWidth(text) <= maxWidth) return text;
        String ellipsis = "...";
        int allowed = Math.max(1, maxWidth - metrics.stringWidth(ellipsis));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (metrics.stringWidth(out.toString() + text.charAt(i)) > allowed) break;
            out.append(text.charAt(i));
        }
        return out.append(ellipsis).toString();
    }

    private static void requireRow(SubjectRow row) {
        if (row == null) throw new IllegalArgumentException("row must not be null");
    }

    private static Layout requireLayout(Layout layout) {
        if (layout == null) throw new IllegalArgumentException("layout must not be null");
        return layout;
    }

    public static final class SubjectRow {
        private final String group;
        private final String subject;
        private final int imageIndex;
        private final boolean suggested;
        private final SpinePainter.GroupData spineData;
        private transient BufferedImage unchosenSpine;
        private transient BufferedImage chosenSpine;
        private transient int spineWidth;
        private transient int spineHeight;

        public SubjectRow(String group, String subject, int imageIndex, boolean suggested) {
            this(group, subject, imageIndex, suggested, null);
        }

        public SubjectRow(String group, String subject, int imageIndex, boolean suggested,
                SpinePainter.GroupData spineData) {
            if (imageIndex < 0) throw new IllegalArgumentException("imageIndex is negative");
            this.group = clean(group, "group");
            this.subject = clean(subject, "subject");
            this.imageIndex = imageIndex;
            this.suggested = suggested;
            this.spineData = spineData;
        }

        public String group() {
            return group;
        }

        public String subject() {
            return subject;
        }

        public int imageIndex() {
            return imageIndex;
        }

        public boolean suggested() {
            return suggested;
        }

        public SpinePainter.GroupData spineData() {
            return spineData;
        }

        private synchronized BufferedImage spineImage(int width, int height,
                boolean chosen) {
            if (spineData == null) return null;
            if (width != spineWidth || height != spineHeight) {
                unchosenSpine = null;
                chosenSpine = null;
                spineWidth = width;
                spineHeight = height;
            }
            if (chosen) {
                if (chosenSpine == null) {
                    chosenSpine = SpinePainter.paintToImage(spineData, subject, true,
                            width, height);
                }
                return chosenSpine;
            }
            if (unchosenSpine == null) {
                unchosenSpine = SpinePainter.paintToImage(spineData, subject, false,
                        width, height);
            }
            return unchosenSpine;
        }

        @Override
        public String toString() {
            return subject;
        }

        private static String clean(String value, String fallback) {
            if (value == null) return fallback;
            String trimmed = value.trim();
            return trimmed.length() == 0 ? fallback : trimmed;
        }
    }

    public static final class Layout {
        private final int channelCount;
        private final int spineWidth;
        private final int tileWidth;
        private final int tileHeight;
        private final int gap;
        private final int padding;
        private final double scaleX;
        private final double scaleY;

        public Layout(int channelCount, int spineWidth, int tileWidth, int tileHeight,
                int gap, int padding, double scaleX, double scaleY) {
            if (channelCount <= 0) throw new IllegalArgumentException("channelCount must be positive");
            requirePositive("spineWidth", spineWidth);
            requirePositive("tileWidth", tileWidth);
            requirePositive("tileHeight", tileHeight);
            if (gap < 0) throw new IllegalArgumentException("gap must not be negative");
            if (padding < 0) throw new IllegalArgumentException("padding must not be negative");
            this.channelCount = channelCount;
            this.spineWidth = spineWidth;
            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;
            this.gap = gap;
            this.padding = padding;
            this.scaleX = saneScale(scaleX);
            this.scaleY = saneScale(scaleY);
        }

        public static Layout standard(int channelCount) {
            return new Layout(channelCount, DEFAULT_SPINE_WIDTH, DEFAULT_TILE_WIDTH,
                    DEFAULT_TILE_HEIGHT, DEFAULT_GAP, DEFAULT_PADDING, 1.0, 1.0);
        }

        public static Layout forComponent(Component component, int channelCount) {
            GraphicsConfiguration configuration = component == null
                    ? null : component.getGraphicsConfiguration();
            return standard(channelCount).withScale(configuration);
        }

        public Layout withScale(GraphicsConfiguration configuration) {
            double sx = 1.0;
            double sy = 1.0;
            if (configuration != null) {
                AffineTransform transform = configuration.getDefaultTransform();
                sx = transform.getScaleX();
                sy = transform.getScaleY();
            } else if (!GraphicsEnvironment.isHeadless()) {
                GraphicsConfiguration screen = GraphicsEnvironment
                        .getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice()
                        .getDefaultConfiguration();
                AffineTransform transform = screen.getDefaultTransform();
                sx = transform.getScaleX();
                sy = transform.getScaleY();
            }
            return new Layout(channelCount, spineWidth, tileWidth, tileHeight, gap,
                    padding, sx, sy);
        }

        public int channelCount() {
            return channelCount;
        }

        public int spineWidth() {
            return spineWidth;
        }

        public int tileWidth() {
            return tileWidth;
        }

        public int tileHeight() {
            return tileHeight;
        }

        public int gap() {
            return gap;
        }

        public int padding() {
            return padding;
        }

        public double scaleX() {
            return scaleX;
        }

        public double scaleY() {
            return scaleY;
        }

        public int rowWidth() {
            return spineWidth + gap + (channelCount + 1) * tileWidth
                    + channelCount * gap + padding;
        }

        public int rowHeight() {
            return tileHeight + padding * 2;
        }

        public int deviceRowWidth() {
            return scaled(rowWidth(), scaleX);
        }

        public int deviceRowHeight() {
            return scaled(rowHeight(), scaleY);
        }

        public int deviceTileWidth() {
            return scaled(tileWidth, scaleX);
        }

        public int deviceTileHeight() {
            return scaled(tileHeight, scaleY);
        }

        private static void requirePositive(String name, int value) {
            if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        }

        private static double saneScale(double value) {
            return Double.isFinite(value) && value > 0.0 ? value : 1.0;
        }

        private static int scaled(int value, double scale) {
            return Math.max(1, (int) Math.round(value * scale));
        }
    }
}
