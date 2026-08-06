/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.figure;

import fpb.meta.MetadataRow;
import fpb.meta.MetadataTable;
import fpb.stats.GroupQuantification;
import fpb.stats.Statistic;
import fpb.stats.SubjectAggregator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class QuantificationPlotTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void onePlotContainsEveryChannelAndSectionWithGroupColours()
            throws Exception {
        List<MetadataRow> rows = new ArrayList<MetadataRow>();
        Map<File, Map<Integer, Double>> values =
                new LinkedHashMap<File, Map<Integer, Double>>();
        add(rows, values, "Control_S1_a.tif", "Control", "S1", "a", 10, 100);
        add(rows, values, "Control_S1_b.tif", "Control", "S1", "b", 14, 80);
        add(rows, values, "Control_S2.tif", "Control", "S2", "", 20, 60);
        add(rows, values, "Drug_S1.tif", "Drug", "S1", "", 30, 40);
        add(rows, values, "Drug_S2.tif", "Drug", "S2", "", 50, 20);
        SubjectAggregator.SubjectStats subjects = SubjectAggregator.aggregate(
                new MetadataTable(temp.getRoot(), rows), Statistic.ImageValues.of(
                        values, Arrays.asList("DAPI", "Signal"), "Mean"));
        GroupQuantification quantification = GroupQuantification.from(subjects);
        Map<String, Integer> chosen = new LinkedHashMap<String, Integer>();
        chosen.put("Control", Integer.valueOf(2));
        chosen.put("Drug", Integer.valueOf(3));

        BufferedImage plot = QuantificationPlot.renderAll(quantification,
                chosen, 900, 340);

        assertEquals(900, plot.getWidth());
        assertEquals(340, plot.getHeight());
        assertEquals(2, quantification.channelCount());
        int left = 62;
        int top = 64;
        int plotWidth = 900 - left - 14;
        int plotHeight = 340 - top - 50;
        double axisLimit = quantification.sharedAxisLimit();
        for (int channelIndex = 0; channelIndex < 2; channelIndex++) {
            GroupQuantification.ChannelData channel =
                    quantification.channel(channelIndex);
            int centreX = QuantificationPlot.channelX(channelIndex, 2,
                    left, plotWidth);
            for (int groupIndex = 0; groupIndex < channel.groups().size();
                    groupIndex++) {
                GroupQuantification.GroupData group =
                        channel.groups().get(groupIndex);
                int groupX = QuantificationPlot.groupX(centreX, groupIndex,
                        channel.groups().size(), 92.0);
                for (int sectionIndex = 0;
                        sectionIndex < group.sections().size(); sectionIndex++) {
                    GroupQuantification.SectionValue section =
                            group.sections().get(sectionIndex);
                    int x = QuantificationPlot.sectionX(groupX, sectionIndex);
                    int y = QuantificationPlot.zY(section.zScore(), axisLimit,
                            top, plotHeight);
                    assertTrue("missing section point for " + group.group()
                                    + " on " + channel.channelName(),
                            hasGroupColour(plot, x, y,
                                    QuantificationPlot.groupColor(groupIndex), 3));
                }
                int meanY = QuantificationPlot.zY(group.zMean(), axisLimit,
                        top, plotHeight);
                assertTrue("missing group mean bar",
                        hasGroupColour(plot, groupX, meanY,
                                QuantificationPlot.groupColor(groupIndex), 3));
            }
        }
        int overallY = QuantificationPlot.zY(0.0, axisLimit, top, plotHeight);
        assertTrue("overall z=0 mean must be a dashed reference",
                countDarkPixelsOnRow(plot, overallY, left, left + plotWidth) > 80);
        assertTrue("chosen subjects' sections must have dark rings",
                countDarkPixels(plot) > 150);
    }

    private void add(List<MetadataRow> rows,
            Map<File, Map<Integer, Double>> values, String name, String group,
            String subject, String section, double channel0, double channel1)
            throws Exception {
        File file = temp.newFile(name);
        rows.add(new MetadataRow(file, group, subject, section));
        Map<Integer, Double> channels = new LinkedHashMap<Integer, Double>();
        channels.put(Integer.valueOf(0), Double.valueOf(channel0));
        channels.put(Integer.valueOf(1), Double.valueOf(channel1));
        values.put(file, channels);
    }

    private static boolean hasGroupColour(BufferedImage image, int x, int y,
            Color expected, int radius) {
        Color blended = new Color(
                (expected.getRed() * 190 + 255 * 65) / 255,
                (expected.getGreen() * 190 + 255 * 65) / 255,
                (expected.getBlue() * 190 + 255 * 65) / 255);
        for (int yy = Math.max(0, y - radius);
                yy <= Math.min(image.getHeight() - 1, y + radius); yy++) {
            for (int xx = Math.max(0, x - radius);
                    xx <= Math.min(image.getWidth() - 1, x + radius); xx++) {
                Color actual = new Color(image.getRGB(xx, yy));
                if (distance(actual, expected) < 35
                        || distance(actual, blended) < 35) return true;
            }
        }
        return false;
    }

    private static int distance(Color left, Color right) {
        return Math.abs(left.getRed() - right.getRed())
                + Math.abs(left.getGreen() - right.getGreen())
                + Math.abs(left.getBlue() - right.getBlue());
    }

    private static int countDarkPixelsOnRow(BufferedImage image, int y,
            int startX, int endX) {
        int count = 0;
        for (int x = Math.max(0, startX);
                x <= Math.min(image.getWidth() - 1, endX); x++) {
            Color color = new Color(image.getRGB(x, y));
            if (color.getRed() < 150 && color.getGreen() < 150
                    && color.getBlue() < 150) count++;
        }
        return count;
    }

    private static int countDarkPixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                Color color = new Color(image.getRGB(x, y));
                if (color.getRed() < 80 && color.getGreen() < 80
                        && color.getBlue() < 80) count++;
            }
        }
        return count;
    }
}
