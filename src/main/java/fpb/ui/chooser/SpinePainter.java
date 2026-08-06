/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.chooser;

import fpb.stats.GroupQuantification;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Paints one section's per-channel z-score trace in a chooser row. */
public final class SpinePainter {

    public static final int MAX_CHANNELS = 8;

    private static final Color BACKGROUND = new Color(248, 249, 250);
    private static final Color BORDER = new Color(200, 205, 211);
    private static final Color RULE = new Color(128, 134, 140);
    private static final Color CONTEXT = new Color(72, 76, 82, 55);
    private static final Color SECTION = new Color(38, 42, 47);
    private static final Color SUGGESTED = new Color(86, 180, 233);
    private static final Color CHOSEN = new Color(213, 94, 0);
    private static final Color TEXT = new Color(55, 60, 66);

    private static int paintCountForTest;

    private SpinePainter() {}

    public static BufferedImage paintToImage(GroupData data, int imageIndex,
            boolean suggested, boolean chosen, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("spine dimensions must be positive");
        }
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            paint(graphics, data, imageIndex, suggested, chosen, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    public static void paint(Graphics2D graphics, GroupData data, int imageIndex,
            boolean suggested, boolean chosen, int width, int height) {
        if (graphics == null) {
            throw new IllegalArgumentException("graphics must not be null");
        }
        paintCountForTest++;
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(BACKGROUND);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(BORDER);
        graphics.drawRect(0, 0, Math.max(0, width - 1),
                Math.max(0, height - 1));

        SectionTrace primary = data == null ? null : data.trace(imageIndex);
        String fallback = primary == null ? "section" : primary.section();
        if (data == null || data.channelCount() == 0) {
            drawFallback(graphics, fallback, width, height);
            return;
        }
        if (data.channelCount() > MAX_CHANNELS) {
            drawFallback(graphics, "spine supports " + MAX_CHANNELS + " channels",
                    width, height);
            return;
        }

        int left = 14;
        int right = 7;
        int top = 10;
        int bottom = 10;
        int plotWidth = Math.max(1, width - left - right);
        int plotHeight = Math.max(1, height - top - bottom);
        int centreY = zY(0.0, data.axisLimit(), top, plotHeight);
        graphics.setColor(RULE);
        graphics.drawLine(left, centreY, left + plotWidth, centreY);

        for (SectionTrace trace : data.traces()) {
            if (trace.imageIndex() != imageIndex) {
                drawTrace(graphics, data, trace, left, top, plotWidth,
                        plotHeight, false);
            }
        }
        if (primary != null) {
            drawTrace(graphics, data, primary, left, top, plotWidth,
                    plotHeight, true);
        } else {
            drawFallback(graphics, fallback, width, height);
        }

        int markerY = Math.max(9, Math.min(height - 9, centreY));
        if (primary != null && suggested) {
            graphics.setColor(SUGGESTED);
            int centreX = 8;
            int[] xs = { centreX, centreX + 5, centreX, centreX - 5 };
            int[] ys = { markerY - 5, markerY, markerY + 5, markerY };
            graphics.fillPolygon(xs, ys, 4);
        }
        if (primary != null && chosen) {
            graphics.setColor(CHOSEN);
            graphics.setStroke(new BasicStroke(2f));
            graphics.drawOval(3, markerY - 5, 10, 10);
        }
    }

    private static void drawTrace(Graphics2D graphics, GroupData data,
            SectionTrace trace, int left, int top, int plotWidth,
            int plotHeight, boolean primary) {
        int channels = data.channelCount();
        int[] xs = new int[channels];
        int[] ys = new int[channels];
        for (int channel = 0; channel < channels; channel++) {
            xs[channel] = channelX(channel, channels, left, plotWidth);
            ys[channel] = zY(trace.zScore(channel), data.axisLimit(), top,
                    plotHeight);
        }

        graphics.setStroke(new BasicStroke(primary ? 1.4f : 1f));
        graphics.setColor(primary ? new Color(54, 59, 65, 150)
                : new Color(72, 76, 82, 35));
        for (int channel = 1; channel < channels; channel++) {
            if (!Double.isFinite(trace.zScore(channel - 1))
                    || !Double.isFinite(trace.zScore(channel))) continue;
            graphics.draw(new Line2D.Double(xs[channel - 1], ys[channel - 1],
                    xs[channel], ys[channel]));
        }

        for (int channel = 0; channel < channels; channel++) {
            if (!Double.isFinite(trace.zScore(channel))) continue;
            int radius = primary ? 3 : 2;
            graphics.setColor(primary ? SECTION : CONTEXT);
            graphics.fillOval(xs[channel] - radius, ys[channel] - radius,
                    radius * 2, radius * 2);
        }
    }

    static int channelX(int channel, int channelCount, int left, int plotWidth) {
        if (channelCount <= 1) return left + plotWidth / 2;
        return left + (int) Math.round(channel * plotWidth
                / (double) (channelCount - 1));
    }

    static int zY(double zScore, double axisLimit, int top, int plotHeight) {
        double limit = Double.isFinite(axisLimit) && axisLimit > 0.0
                ? axisLimit : 1.0;
        double value = Double.isFinite(zScore) ? zScore : 0.0;
        value = Math.max(-limit, Math.min(limit, value));
        return top + (int) Math.round((limit - value) / (2.0 * limit)
                * plotHeight);
    }

    private static void drawFallback(Graphics2D graphics, String text,
            int width, int height) {
        graphics.setColor(TEXT);
        FontMetrics metrics = graphics.getFontMetrics();
        String fitted = fit(clean(text).isEmpty() ? "section" : text, metrics,
                Math.max(12, width - 10));
        graphics.drawString(fitted,
                Math.max(4, (width - metrics.stringWidth(fitted)) / 2),
                Math.max(metrics.getAscent(),
                        (height + metrics.getAscent()) / 2 - 2));
    }

    static String fit(String text, FontMetrics metrics, int maxWidth) {
        String value = clean(text);
        if (metrics == null || metrics.stringWidth(value) <= maxWidth) return value;
        String suffix = "...";
        int limit = value.length();
        while (limit > 1) {
            String candidate = value.substring(0, limit) + suffix;
            if (metrics.stringWidth(candidate) <= maxWidth) return candidate;
            limit--;
        }
        return suffix;
    }

    public static GroupData groupData(GroupQuantification quantification,
            String group) {
        if (quantification == null || quantification.channelCount() == 0) {
            return GroupData.empty();
        }
        LinkedHashMap<Integer, TraceBuilder> builders =
                new LinkedHashMap<Integer, TraceBuilder>();
        for (int channel = 0; channel < quantification.channelCount(); channel++) {
            GroupQuantification.GroupData channelGroup = findGroup(
                    quantification.channel(channel), group);
            if (channelGroup == null) continue;
            for (GroupQuantification.SectionValue section
                    : channelGroup.sections()) {
                Integer key = Integer.valueOf(section.imageIndex());
                TraceBuilder builder = builders.get(key);
                if (builder == null) {
                    builder = new TraceBuilder(section.imageIndex(),
                            section.subject(), section.section(),
                            section.sourceLabel());
                    builders.put(key, builder);
                }
                builder.put(channel, section.zScore());
            }
        }
        List<SectionTrace> traces = new ArrayList<SectionTrace>();
        for (TraceBuilder builder : builders.values()) {
            traces.add(builder.trace(quantification.channelCount()));
        }
        return new GroupData(quantification.channelCount(),
                quantification.sharedAxisLimit(), traces);
    }

    private static GroupQuantification.GroupData findGroup(
            GroupQuantification.ChannelData channel, String group) {
        String wanted = clean(group);
        for (GroupQuantification.GroupData candidate : channel.groups()) {
            if (wanted.equals(clean(candidate.group()))) return candidate;
        }
        return null;
    }

    static void resetPaintCountForTest() {
        paintCountForTest = 0;
    }

    static int paintCountForTest() {
        return paintCountForTest;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class TraceBuilder {
        private final int imageIndex;
        private final String subject;
        private final String section;
        private final String sourceLabel;
        private final Map<Integer, Double> zScores =
                new LinkedHashMap<Integer, Double>();

        TraceBuilder(int imageIndex, String subject, String section,
                String sourceLabel) {
            this.imageIndex = imageIndex;
            this.subject = clean(subject);
            this.section = clean(section);
            this.sourceLabel = clean(sourceLabel);
        }

        void put(int channel, double zScore) {
            zScores.put(Integer.valueOf(channel), Double.valueOf(zScore));
        }

        SectionTrace trace(int channelCount) {
            double[] values = new double[channelCount];
            java.util.Arrays.fill(values, Double.NaN);
            for (Map.Entry<Integer, Double> entry : zScores.entrySet()) {
                if (entry.getKey().intValue() >= 0
                        && entry.getKey().intValue() < values.length) {
                    values[entry.getKey().intValue()] = entry.getValue().doubleValue();
                }
            }
            return new SectionTrace(imageIndex, subject, section, sourceLabel,
                    values);
        }
    }

    public static final class GroupData {
        private final int channelCount;
        private final double axisLimit;
        private final List<SectionTrace> traces;
        private final Map<Integer, SectionTrace> byImageIndex;

        private GroupData(int channelCount, double axisLimit,
                List<SectionTrace> traces) {
            if (channelCount < 0) {
                throw new IllegalArgumentException("channelCount is negative");
            }
            this.channelCount = channelCount;
            this.axisLimit = Double.isFinite(axisLimit) && axisLimit > 0.0
                    ? axisLimit : 1.0;
            this.traces = Collections.unmodifiableList(
                    new ArrayList<SectionTrace>(traces));
            LinkedHashMap<Integer, SectionTrace> map =
                    new LinkedHashMap<Integer, SectionTrace>();
            for (SectionTrace trace : this.traces) {
                map.put(Integer.valueOf(trace.imageIndex()), trace);
            }
            byImageIndex = Collections.unmodifiableMap(map);
        }

        public static GroupData empty() {
            return new GroupData(0, 1.0,
                    Collections.<SectionTrace>emptyList());
        }

        public int channelCount() { return channelCount; }
        public double axisLimit() { return axisLimit; }
        public List<SectionTrace> traces() { return traces; }
        public SectionTrace trace(int imageIndex) {
            return byImageIndex.get(Integer.valueOf(imageIndex));
        }
    }

    public static final class SectionTrace {
        private final int imageIndex;
        private final String subject;
        private final String section;
        private final String sourceLabel;
        private final double[] zScores;

        private SectionTrace(int imageIndex, String subject, String section,
                String sourceLabel, double[] zScores) {
            this.imageIndex = imageIndex;
            this.subject = clean(subject);
            this.section = clean(section);
            this.sourceLabel = clean(sourceLabel);
            this.zScores = zScores == null ? new double[0] : zScores.clone();
        }

        public int imageIndex() { return imageIndex; }
        public String subject() { return subject; }
        public String section() { return section; }
        public String sourceLabel() { return sourceLabel; }
        public double zScore(int channel) {
            return channel >= 0 && channel < zScores.length
                    ? zScores[channel] : Double.NaN;
        }
    }
}
