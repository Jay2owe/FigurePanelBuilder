/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.figure;

import fpb.stats.GroupQuantification;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One z-normalized section-level chart spanning every channel and group. */
public final class QuantificationPlot {

    private static final Color BACKGROUND = Color.WHITE;
    private static final Color AXIS = new Color(78, 84, 91);
    private static final Color GRID = new Color(224, 228, 232);
    private static final Color TEXT = new Color(42, 47, 53);
    private static final Color CHOSEN = new Color(20, 20, 20);
    private static final Color[] GROUP_COLORS = new Color[] {
            new Color(0, 114, 178), new Color(213, 94, 0),
            new Color(0, 158, 115), new Color(204, 121, 167),
            new Color(230, 159, 0), new Color(86, 180, 233),
            new Color(100, 74, 155), new Color(80, 80, 80)
    };

    private QuantificationPlot() {}

    public static BufferedImage renderAll(GroupQuantification quantification,
            Map<String, Integer> chosenSections, int width, int height) {
        requireQuantification(quantification);
        BufferedImage image = new BufferedImage(Math.max(600, width),
                Math.max(260, height), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            paintAll(graphics, quantification, chosenSections,
                    image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /** Retained for callers that need a one-channel crop of the same plot style. */
    public static BufferedImage renderChannel(GroupQuantification quantification,
            int logicalChannelIndex, Map<String, String> chosenSubjects,
            int width, int height) {
        requireQuantification(quantification);
        if (logicalChannelIndex < 0
                || logicalChannelIndex >= quantification.channelCount()) {
            throw new IllegalArgumentException("logicalChannelIndex is out of range");
        }
        BufferedImage image = new BufferedImage(Math.max(320, width),
                Math.max(220, height), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            paintChannels(graphics, quantification,
                    Collections.singletonList(quantification.channel(
                            logicalChannelIndex)), chosenImageIndices(
                                    quantification, chosenSubjects),
                    image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        return image;
    }

    public static void paintAll(Graphics2D graphics,
            GroupQuantification quantification,
            Map<String, Integer> chosenSections, int width, int height) {
        requireQuantification(quantification);
        paintChannels(graphics, quantification, quantification.channels(),
                chosenSections, width, height);
    }

    private static void paintChannels(Graphics2D graphics,
            GroupQuantification quantification,
            List<GroupQuantification.ChannelData> channels,
            Map<String, Integer> chosenSections, int width, int height) {
        if (graphics == null) throw new IllegalArgumentException("graphics is required");
        Map<String, Integer> chosen = chosenSections == null
                ? Collections.<String, Integer>emptyMap() : chosenSections;
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(BACKGROUND);
        graphics.fillRect(0, 0, width, height);

        boolean compact = height < 260 || width < 700;
        int left = compact ? 48 : 62;
        int right = 14;
        int top = compact ? 53 : 64;
        int bottom = compact ? 42 : 50;
        int plotWidth = Math.max(1, width - left - right);
        int plotHeight = Math.max(1, height - top - bottom);
        double axisLimit = Math.max(1.0, quantification.sharedAxisLimit());

        graphics.setColor(TEXT);
        graphics.setFont(graphics.getFont().deriveFont(Font.BOLD,
                compact ? 11f : 13f));
        graphics.drawString("Section-level group comparison (z score)", 9, 16);
        graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN,
                compact ? 9f : 10f));
        graphics.drawString("dots: sections | coloured bars: group means | dashed: overall mean",
                9, 31);
        drawLegend(graphics, quantification.groups(), width, compact ? 44 : 48);

        FontMetrics tickMetrics = graphics.getFontMetrics();
        for (int tick = 0; tick <= 4; tick++) {
            double z = axisLimit - tick * axisLimit / 2.0;
            int y = zY(z, axisLimit, top, plotHeight);
            graphics.setColor(GRID);
            graphics.drawLine(left, y, left + plotWidth, y);
            graphics.setColor(AXIS);
            String label = format(z);
            graphics.drawString(label, left - 7 - tickMetrics.stringWidth(label),
                    y + tickMetrics.getAscent() / 2 - 1);
        }

        int overallY = zY(0.0, axisLimit, top, plotHeight);
        Stroke originalStroke = graphics.getStroke();
        graphics.setColor(new Color(38, 42, 47, 185));
        graphics.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 10f, new float[] { 6f, 4f }, 0f));
        graphics.drawLine(left, overallY, left + plotWidth, overallY);
        graphics.setStroke(originalStroke);

        graphics.setColor(AXIS);
        graphics.drawLine(left, top, left, top + plotHeight);
        graphics.drawLine(left, top + plotHeight, left + plotWidth,
                top + plotHeight);

        int channelCount = channels.size();
        for (int channelIndex = 0; channelIndex < channelCount; channelIndex++) {
            GroupQuantification.ChannelData channel = channels.get(channelIndex);
            int centreX = channelX(channelIndex, channelCount, left, plotWidth);
            int categoryWidth = Math.max(22, plotWidth / Math.max(1, channelCount));
            int groupCount = Math.max(1, channel.groups().size());
            double groupBand = Math.min(categoryWidth * 0.68, 92.0);
            for (int groupIndex = 0; groupIndex < channel.groups().size();
                    groupIndex++) {
                GroupQuantification.GroupData group = channel.groups().get(groupIndex);
                int groupX = groupX(centreX, groupIndex, groupCount, groupBand);
                Color color = groupColor(groupIndex);
                Integer chosenImageIndex = chosen.get(group.group());
                for (int sectionIndex = 0; sectionIndex < group.sections().size();
                        sectionIndex++) {
                    GroupQuantification.SectionValue section =
                            group.sections().get(sectionIndex);
                    int x = sectionX(groupX, sectionIndex);
                    int y = zY(section.zScore(), axisLimit, top, plotHeight);
                    graphics.setColor(withAlpha(color, 190));
                    graphics.fillOval(x - 3, y - 3, 7, 7);
                    if (chosenImageIndex != null
                            && section.imageIndex() == chosenImageIndex.intValue()) {
                        graphics.setColor(CHOSEN);
                        graphics.setStroke(new BasicStroke(1.4f));
                        graphics.drawOval(x - 5, y - 5, 10, 10);
                    }
                }
                if (Double.isFinite(group.zMean())) {
                    int meanY = zY(group.zMean(), axisLimit, top, plotHeight);
                    graphics.setColor(color);
                    graphics.setStroke(new BasicStroke(3f));
                    int half = Math.max(5, Math.min(11,
                            (int) Math.round(groupBand / Math.max(4, groupCount * 3))));
                    graphics.drawLine(groupX - half, meanY, groupX + half, meanY);
                }
            }

            graphics.setColor(TEXT);
            graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN, 10f));
            String label = fit(channel.channelName(), graphics.getFontMetrics(),
                    Math.max(28, categoryWidth - 5));
            graphics.drawString(label,
                    centreX - graphics.getFontMetrics().stringWidth(label) / 2,
                    top + plotHeight + graphics.getFontMetrics().getAscent() + 7);
        }
    }

    private static void drawLegend(Graphics2D graphics, List<String> groups,
            int width, int baseline) {
        int x = 10;
        FontMetrics metrics = graphics.getFontMetrics();
        for (int i = 0; i < groups.size(); i++) {
            String label = groups.get(i);
            int itemWidth = 16 + metrics.stringWidth(label) + 12;
            if (x + itemWidth > width - 8) break;
            graphics.setColor(groupColor(i));
            graphics.fillOval(x, baseline - 7, 7, 7);
            graphics.drawLine(x + 10, baseline - 4, x + 20, baseline - 4);
            graphics.setColor(TEXT);
            graphics.drawString(label, x + 24, baseline);
            x += itemWidth + 14;
        }
    }

    static int channelX(int channel, int channelCount, int left, int plotWidth) {
        return left + (int) Math.round((channel + 0.5) * plotWidth
                / Math.max(1.0, channelCount));
    }

    static int groupX(int channelX, int group, int groupCount, double band) {
        if (groupCount <= 1) return channelX;
        return channelX + (int) Math.round((group - (groupCount - 1) / 2.0)
                * band / (groupCount - 1));
    }

    static int zY(double z, double axisLimit, int top, int plotHeight) {
        double safeLimit = !Double.isFinite(axisLimit) || axisLimit <= 0.0
                ? 1.0 : axisLimit;
        double value = Double.isFinite(z) ? z : 0.0;
        value = Math.max(-safeLimit, Math.min(safeLimit, value));
        return top + (int) Math.round((safeLimit - value)
                / (safeLimit * 2.0) * plotHeight);
    }

    static Color groupColor(int index) {
        return GROUP_COLORS[Math.floorMod(index, GROUP_COLORS.length)];
    }

    static int sectionX(int groupX, int sectionIndex) {
        return groupX + jitter(sectionIndex);
    }

    private static int jitter(int index) {
        int[] offsets = new int[] { 0, -3, 3, -6, 6, -9, 9 };
        return offsets[Math.floorMod(index, offsets.length)];
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private static Map<String, Integer> chosenImageIndices(
            GroupQuantification quantification,
            Map<String, String> chosenSubjects) {
        if (chosenSubjects == null || chosenSubjects.isEmpty()) {
            return Collections.emptyMap();
        }
        java.util.LinkedHashMap<String, Integer> indices =
                new java.util.LinkedHashMap<String, Integer>();
        for (String group : quantification.groups()) {
            String subject = chosenSubjects.get(group);
            if (subject == null) continue;
            for (GroupQuantification.ChannelData channel
                    : quantification.channels()) {
                for (GroupQuantification.GroupData candidate : channel.groups()) {
                    if (!group.equals(candidate.group())) continue;
                    for (GroupQuantification.SectionValue section
                            : candidate.sections()) {
                        if (subject.equals(section.subject())) {
                            indices.put(group, Integer.valueOf(section.imageIndex()));
                            break;
                        }
                    }
                    if (indices.containsKey(group)) break;
                }
                if (indices.containsKey(group)) break;
            }
        }
        return indices;
    }

    private static String format(double value) {
        if (Math.abs(value) < 0.0005) return "0";
        return trim(String.format(Locale.ROOT, "%.2f", value));
    }

    private static String trim(String value) {
        String text = value;
        while (text.contains(".") && text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    private static String fit(String text, FontMetrics metrics, int maximum) {
        String value = text == null ? "" : text;
        if (metrics.stringWidth(value) <= maximum) return value;
        String suffix = "...";
        for (int end = value.length() - 1; end > 0; end--) {
            String candidate = value.substring(0, end) + suffix;
            if (metrics.stringWidth(candidate) <= maximum) return candidate;
        }
        return suffix;
    }

    private static void requireQuantification(GroupQuantification quantification) {
        if (quantification == null || quantification.channelCount() == 0) {
            throw new IllegalArgumentException("quantification must contain channels");
        }
    }
}
