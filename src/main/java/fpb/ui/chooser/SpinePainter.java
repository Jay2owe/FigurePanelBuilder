/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.chooser;

import fpb.stats.SelectionRecord;

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

/** Paints the per-subject, per-channel deviation spine for the chooser rows. */
public final class SpinePainter {

    public static final int MAX_CHANNELS = 8;

    private static final Color BACKGROUND = new Color(248, 249, 250);
    private static final Color BORDER = new Color(200, 205, 211);
    private static final Color RULE = new Color(128, 134, 140);
    private static final Color CONTEXT = new Color(72, 76, 82, 55);
    private static final Color SUBJECT = new Color(38, 42, 47);
    private static final Color SUGGESTED = new Color(86, 180, 233);
    private static final Color CHOSEN = new Color(213, 94, 0);
    private static final Color TEXT = new Color(55, 60, 66);

    private static int paintCountForTest;

    private SpinePainter() {}

    public static BufferedImage paintToImage(GroupData data, String subject,
            boolean chosen, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("spine dimensions must be positive");
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            paint(g, data, subject, chosen, width, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    public static void paint(Graphics2D g, GroupData data, String subject,
            boolean chosen, int width, int height) {
        if (g == null) throw new IllegalArgumentException("graphics must not be null");
        paintCountForTest++;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, width, height);
        g.setColor(BORDER);
        g.drawRect(0, 0, Math.max(0, width - 1), Math.max(0, height - 1));

        if (data == null || data.channelCount() == 0) {
            drawFallback(g, clean(subject), width, height);
            return;
        }

        if (data.channelCount() > MAX_CHANNELS) {
            drawFallback(g, "spine supports " + MAX_CHANNELS + " channels", width, height);
            return;
        }

        int left = 14;
        int right = 7;
        int top = 10;
        int bottom = 10;
        int plotWidth = Math.max(1, width - left - right);
        int plotHeight = Math.max(1, height - top - bottom);
        int centreY = top + plotHeight / 2;
        g.setColor(RULE);
        g.drawLine(left, centreY, left + plotWidth, centreY);

        for (SubjectTrace trace : data.traces()) {
            if (!trace.subject().equals(subject)) drawTrace(g, data, trace, left, top,
                    plotWidth, plotHeight, false);
        }
        SubjectTrace trace = data.trace(subject);
        if (trace != null) {
            drawTrace(g, data, trace, left, top, plotWidth, plotHeight, true);
            drawSectionTicks(g, data, trace, left, top, plotWidth, plotHeight);
        } else {
            drawFallback(g, clean(subject), width, height);
        }

        int markerY = Math.max(9, Math.min(height - 9, centreY));
        if (trace != null && trace.suggested()) {
            g.setColor(SUGGESTED);
            int cx = 8;
            int[] xs = { cx, cx + 5, cx, cx - 5 };
            int[] ys = { markerY - 5, markerY, markerY + 5, markerY };
            g.fillPolygon(xs, ys, 4);
        }
        if (chosen) {
            g.setColor(CHOSEN);
            g.setStroke(new BasicStroke(2f));
            g.drawOval(3, markerY - 5, 10, 10);
        }
    }

    private static void drawTrace(Graphics2D g, GroupData data, SubjectTrace trace,
            int left, int top, int plotWidth, int plotHeight, boolean primary) {
        int channels = data.channelCount();
        int[] xs = new int[channels];
        int[] ys = new int[channels];
        for (int channel = 0; channel < channels; channel++) {
            xs[channel] = channelX(channel, channels, left, plotWidth);
            ys[channel] = deviationY(trace.deviation(channel), top, plotHeight);
        }

        g.setStroke(new BasicStroke(primary ? 1.4f : 1f));
        g.setColor(primary ? new Color(54, 59, 65, 150) : new Color(72, 76, 82, 35));
        for (int channel = 1; channel < channels; channel++) {
            if (!Double.isFinite(trace.deviation(channel - 1))
                    || !Double.isFinite(trace.deviation(channel))) {
                continue;
            }
            g.draw(new Line2D.Double(xs[channel - 1], ys[channel - 1],
                    xs[channel], ys[channel]));
        }

        for (int channel = 0; channel < channels; channel++) {
            if (!Double.isFinite(trace.deviation(channel))) continue;
            int radius = primary ? 3 : 2;
            g.setColor(primary ? SUBJECT : CONTEXT);
            g.fillOval(xs[channel] - radius, ys[channel] - radius,
                    radius * 2, radius * 2);
        }
    }

    private static void drawSectionTicks(Graphics2D g, GroupData data,
            SubjectTrace trace, int left, int top, int plotWidth, int plotHeight) {
        g.setColor(new Color(38, 42, 47, 130));
        for (int channel = 0; channel < data.channelCount(); channel++) {
            int count = trace.sectionCount(channel);
            if (count <= 1) continue;
            int x = channelX(channel, data.channelCount(), left, plotWidth);
            int y = deviationY(trace.deviation(channel), top, plotHeight);
            int start = y - (count - 1) * 3;
            for (int i = 0; i < count; i++) {
                int yy = start + i * 6;
                g.drawLine(x - 5, yy, x + 5, yy);
            }
        }
    }

    private static int channelX(int channel, int channelCount, int left, int plotWidth) {
        if (channelCount <= 1) return left + plotWidth / 2;
        return left + (int) Math.round(channel * plotWidth / (double) (channelCount - 1));
    }

    private static int deviationY(double deviation, int top, int plotHeight) {
        double value = Double.isFinite(deviation) ? deviation : 0.0;
        if (value < -0.5) value = -0.5;
        if (value > 0.5) value = 0.5;
        double normalized = 0.5 - value;
        return top + (int) Math.round(normalized * plotHeight);
    }

    private static void drawFallback(Graphics2D g, String text, int width, int height) {
        g.setColor(TEXT);
        FontMetrics metrics = g.getFontMetrics();
        String fitted = fit(text.isEmpty() ? "subject" : text, metrics,
                Math.max(12, width - 10));
        g.drawString(fitted, Math.max(4, (width - metrics.stringWidth(fitted)) / 2),
                Math.max(metrics.getAscent(),
                        (height + metrics.getAscent()) / 2 - 2));
    }

    static String fit(String text, FontMetrics metrics, int maxWidth) {
        String clean = clean(text);
        if (metrics == null || metrics.stringWidth(clean) <= maxWidth) return clean;
        String suffix = "...";
        int limit = clean.length();
        while (limit > 1) {
            String candidate = clean.substring(0, limit) + suffix;
            if (metrics.stringWidth(candidate) <= maxWidth) return candidate;
            limit--;
        }
        return suffix;
    }

    public static GroupData groupData(List<SelectionRecord> records, String group,
            List<String> subjectOrder) {
        if (records == null) return GroupData.empty();
        LinkedHashMap<String, TraceBuilder> builders =
                new LinkedHashMap<String, TraceBuilder>();
        int channelCount = 0;
        for (SelectionRecord record : records) {
            if (record == null || !clean(group).equals(clean(record.group()))) continue;
            channelCount = Math.max(channelCount, record.channelIndex() + 1);
            TraceBuilder builder = builders.get(record.subject());
            if (builder == null) {
                builder = new TraceBuilder(record.subject());
                builders.put(record.subject(), builder);
            }
            builder.put(record);
        }
        if (channelCount == 0) return GroupData.empty();

        List<SubjectTrace> traces = new ArrayList<SubjectTrace>();
        List<String> order = subjectOrder == null
                ? new ArrayList<String>(builders.keySet())
                : subjectOrder;
        for (String subject : order) {
            TraceBuilder builder = builders.get(subject);
            if (builder != null) traces.add(builder.trace(channelCount));
        }
        for (Map.Entry<String, TraceBuilder> entry : builders.entrySet()) {
            if (subjectOrder == null || subjectOrder.contains(entry.getKey())) continue;
            traces.add(entry.getValue().trace(channelCount));
        }
        return new GroupData(channelCount, traces);
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
        private final String subject;
        private final Map<Integer, SelectionRecord> byChannel =
                new LinkedHashMap<Integer, SelectionRecord>();

        TraceBuilder(String subject) {
            this.subject = clean(subject);
        }

        void put(SelectionRecord record) {
            byChannel.put(Integer.valueOf(record.channelIndex()), record);
        }

        SubjectTrace trace(int channelCount) {
            double[] deviations = new double[channelCount];
            double[] values = new double[channelCount];
            int[] sectionCounts = new int[channelCount];
            boolean suggested = false;
            for (int i = 0; i < channelCount; i++) {
                SelectionRecord record = byChannel.get(Integer.valueOf(i));
                deviations[i] = record == null ? Double.NaN : record.deviation();
                values[i] = record == null ? Double.NaN : record.value();
                sectionCounts[i] = record == null ? 0 : record.sectionCount();
                suggested = suggested || (record != null && record.suggested());
            }
            return new SubjectTrace(subject, deviations, values, sectionCounts,
                    suggested);
        }
    }

    public static final class GroupData {
        private final int channelCount;
        private final List<SubjectTrace> traces;
        private final Map<String, SubjectTrace> bySubject;

        private GroupData(int channelCount, List<SubjectTrace> traces) {
            if (channelCount < 0) throw new IllegalArgumentException("channelCount is negative");
            this.channelCount = channelCount;
            this.traces = Collections.unmodifiableList(new ArrayList<SubjectTrace>(traces));
            LinkedHashMap<String, SubjectTrace> map =
                    new LinkedHashMap<String, SubjectTrace>();
            for (SubjectTrace trace : this.traces) map.put(trace.subject(), trace);
            bySubject = Collections.unmodifiableMap(map);
        }

        public static GroupData empty() {
            return new GroupData(0, Collections.<SubjectTrace>emptyList());
        }

        public int channelCount() {
            return channelCount;
        }

        public List<SubjectTrace> traces() {
            return traces;
        }

        public SubjectTrace trace(String subject) {
            return bySubject.get(clean(subject));
        }
    }

    public static final class SubjectTrace {
        private final String subject;
        private final double[] deviations;
        private final double[] values;
        private final int[] sectionCounts;
        private final boolean suggested;

        public SubjectTrace(String subject, double[] deviations, double[] values,
                int[] sectionCounts, boolean suggested) {
            this.subject = clean(subject);
            this.deviations = deviations == null ? new double[0] : deviations.clone();
            this.values = values == null ? new double[this.deviations.length] : values.clone();
            this.sectionCounts = sectionCounts == null
                    ? new int[this.deviations.length] : sectionCounts.clone();
            this.suggested = suggested;
        }

        public String subject() {
            return subject;
        }

        public double deviation(int channel) {
            return channel >= 0 && channel < deviations.length
                    ? deviations[channel] : Double.NaN;
        }

        public double value(int channel) {
            return channel >= 0 && channel < values.length ? values[channel] : Double.NaN;
        }

        public int sectionCount(int channel) {
            return channel >= 0 && channel < sectionCounts.length
                    ? sectionCounts[channel] : 0;
        }

        public boolean suggested() {
            return suggested;
        }
    }
}
