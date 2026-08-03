/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb;

import fpb.figure.CalibrationCheck;
import fpb.figure.ScaleBar;
import fpb.io.Binner;
import fpb.io.ImageLoader;
import fpb.io.PlaneCache;
import fpb.io.ProgressCallback;
import fpb.meta.MetadataRow;
import fpb.meta.MetadataTable;
import fpb.render.ChannelColour;
import fpb.render.ClipReport;
import fpb.render.DisplayRange;
import fpb.render.FPBRenderer;
import fpb.render.FastRaster;
import fpb.stats.GroupStats;
import fpb.stats.Statistic;
import fpb.stats.SubjectAggregator;
import fpb.stats.Suggestion;
import org.junit.Test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DefectLedgerTest {

    private static volatile int renderSink;

    @Test
    public void defect1_missingRangeThrowsRatherThanAutoEnhancing() throws Exception {
        try {
            renderEightBit(null);
            fail("Expected missing display range to fail closed.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(
                    "never applies automatic per-image contrast"));
        }
    }

    @Test
    public void defect2_invalidRangeThrowsRatherThanUsingPerImageMinMax() throws Exception {
        try {
            renderEightBit(new DisplayRange(200, 200));
            fail("Expected invalid display range to fail closed.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("No display range locked"));
        }
    }

    @Test
    public void defect3_clippingCountedPerChannelNotFromMerge() throws Exception {
        ImageLoader.LoadResult loaded = load("eightbit.tif", 1);

        FPBRenderer.PanelRender panel = new FPBRenderer().renderPanel(
                loaded.planeCache(), loaded.histogramCache(), 0,
                Arrays.asList(request(0, "DAPI", ChannelColour.GREY,
                        new DisplayRange(10, 200))), 2, 2);
        ClipReport report = panel.clipReport();

        assertEquals(1, report.channels().size());
        assertEquals(0, report.channel(0).channelIndex());
        assertEquals(25.0, report.channel(0).lowPercent(), 0.0001);
        assertEquals(25.0, report.channel(0).highPercent(), 0.0001);
    }

    @Test
    public void defect4_directRasterPathIsAtLeastTenTimesFasterThanSetRGB() {
        final int width = 768;
        final int height = 768;
        final short[] raw = gradient(width * height);
        final DisplayRange range = new DisplayRange(100, 60000);
        final byte[] lut = FastRaster.buildLut(range);
        final BufferedImage directImage = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);

        for (int i = 0; i < 3; i++) {
            FastRaster.renderInto(raw, width, height, lut, ChannelColour.GREY,
                    directImage);
            renderSink ^= directImage.getRGB(17, 19);
            renderSink ^= slowSetRgbRender(raw, width, height, lut).getRGB(17, 19);
        }

        long direct = bestNanos(new RenderJob() {
            @Override
            public void run() {
                FastRaster.renderInto(raw, width, height, lut, ChannelColour.GREY,
                        directImage);
                renderSink ^= directImage.getRGB(23, 29);
            }
        }, 5);
        long setRgb = bestNanos(new RenderJob() {
            @Override
            public void run() {
                renderSink ^= slowSetRgbRender(raw, width, height, lut).getRGB(23, 29);
            }
        }, 3);

        assertTrue("direct raster " + direct + " ns, setRGB " + setRgb + " ns",
                setRgb >= direct * 10L);
    }

    @Test
    public void defect5_maxBinningPreservesPunctaPeaksWhereAveragingDoesNot() {
        short[] source = new short[16];
        source[6] = (short) 60000;

        short[] maxBinned = Binner.maxBin(source, 4, 4, 1, 1);

        assertEquals(60000, maxBinned[0] & 0xFFFF);
        assertTrue("area averaging would dilute the punctum",
                averageBin(source) < 60000);
    }

    @Test
    public void defect6_noFlashVocabularyRemainsInSource() throws Exception {
        List<String> hits = grepSourceTree(new File("src/main/java/fpb"),
                Pattern.compile("\\b(animal|hemisphere|region)\\b",
                        Pattern.CASE_INSENSITIVE));

        assertTrue("FLASH vocabulary found: " + hits, hits.isEmpty());
    }

    @Test
    public void defect7_extraSectionsDoNotImproveASubjectsRanking() throws Exception {
        MetadataTable table = sectionTable();
        Statistic.ImageValues values = values(table,
                39.0, 102.0, 31.0, 33.0, 195.0, 122.0);

        SubjectAggregator.SubjectStats subjects =
                SubjectAggregator.aggregate(table, values);
        Suggestion.Result suggestion = Suggestion.suggestGroup(
                GroupStats.from(subjects), "Control");

        assertEquals("S4", suggestion.suggestedSubject());
        assertEquals(3, subjects.sectionCount("Control", "S1", 0));
        assertEquals(57.333333333333336,
                subjects.value("Control", "S1", 0).doubleValue(), 0.0000001);
    }

    @Test
    public void defect8_scaleBarLengthRecomputedForDrawnPanelSize() {
        CalibrationCheck.Result calibration =
                CalibrationCheck.userEntered(0.5, 0.5);

        int fullSize = ScaleBar.lengthPixels(calibration,
                1000, 800, 1000, 800, 100.0);
        int halfSize = ScaleBar.lengthPixels(calibration,
                1000, 800, 500, 400, 100.0);

        assertEquals(200, fullSize);
        assertEquals(100, halfSize);
    }

    @Test
    public void defect9_virtualStackPlanesAreClonedNotAliased() throws Exception {
        ImageLoader.LoadResult loaded = load("basic/Control_S1.tif", 3);
        PlaneCache.Plane plane = loaded.planeCache().plane(0, 0);

        short[] first = plane.pixels();
        short original = first[0];
        first[0] = (short) 65535;

        assertEquals(original, plane.pixels()[0]);
    }

    @Test
    public void chooserContainsNoWarningLanguageOrTrafficLightColours() throws Exception {
        List<String> hits = grepSourceTree(new File("src/main/java/fpb/ui/chooser"),
                Pattern.compile("\\b(outlier|atypical|warning|should|cherry-pick)\\b"
                        + "|Color\\.RED|Color\\.GREEN",
                        Pattern.CASE_INSENSITIVE));

        assertTrue("Selection UI tone rule hit: " + hits, hits.isEmpty());
    }

    private static FPBRenderer.PanelRender renderEightBit(DisplayRange range)
            throws Exception {
        ImageLoader.LoadResult loaded = load("eightbit.tif", 1);
        return new FPBRenderer().renderPanel(loaded.planeCache(),
                loaded.histogramCache(), 0,
                Arrays.asList(request(0, "DAPI", ChannelColour.GREY, range)),
                2, 2);
    }

    private static FPBRenderer.ChannelRequest request(int channelIndex, String name,
            ChannelColour colour, DisplayRange range) {
        return new FPBRenderer.ChannelRequest(channelIndex, name, colour, range);
    }

    private static ImageLoader.LoadResult load(String fixture, int channels)
            throws Exception {
        return new ImageLoader(150, channels).loadFiles(
                Arrays.asList(new File("src/test/resources/fixtures", fixture)
                        .getAbsoluteFile()), ProgressCallback.NONE);
    }

    private static short[] gradient(int length) {
        short[] raw = new short[length];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (short) (i & 0xFFFF);
        }
        return raw;
    }

    private static BufferedImage slowSetRgbRender(short[] raw, int width, int height,
            byte[] lut) {
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        int i = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int grey = lut[raw[i++] & 0xFFFF] & 0xFF;
                int rgb = (grey << 16) | (grey << 8) | grey;
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    private static long bestNanos(RenderJob job, int runs) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            job.run();
            long elapsed = System.nanoTime() - start;
            if (elapsed < best) best = elapsed;
        }
        return best;
    }

    private static int averageBin(short[] source) {
        long sum = 0L;
        for (short value : source) sum += value & 0xFFFF;
        return (int) Math.round(sum / (double) source.length);
    }

    private static MetadataTable sectionTable() throws Exception {
        File root = Files.createTempDirectory("fpb-ledger").toFile();
        List<MetadataRow> rows = new ArrayList<MetadataRow>();
        rows.add(new MetadataRow(touch(root, "Control_S1_sec1.tif"), "Control", "S1", "sec1"));
        rows.add(new MetadataRow(touch(root, "Control_S1_sec2.tif"), "Control", "S1", "sec2"));
        rows.add(new MetadataRow(touch(root, "Control_S1_sec3.tif"), "Control", "S1", "sec3"));
        rows.add(new MetadataRow(touch(root, "Control_S2.tif"), "Control", "S2", ""));
        rows.add(new MetadataRow(touch(root, "Control_S3.tif"), "Control", "S3", ""));
        rows.add(new MetadataRow(touch(root, "Control_S4.tif"), "Control", "S4", ""));
        return new MetadataTable(root, rows);
    }

    private static File touch(File root, String name) throws Exception {
        File file = new File(root, name);
        assertTrue(file.createNewFile());
        return file;
    }

    private static Statistic.ImageValues values(MetadataTable table,
            double... values) {
        Map<File, Double> byFile = new LinkedHashMap<File, Double>();
        for (int i = 0; i < table.rows().size(); i++) {
            byFile.put(table.rows().get(i).file, Double.valueOf(values[i]));
        }
        return Statistic.ImageValues.singleChannel(byFile, "Mean", "Mean");
    }

    private static List<String> grepSourceTree(File root, Pattern forbidden)
            throws Exception {
        List<String> hits = new ArrayList<String>();
        collectHits(root, forbidden, hits);
        return hits;
    }

    private static void collectHits(File file, Pattern forbidden, List<String> hits)
            throws Exception {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return;
            for (File child : children) collectHits(child, forbidden, hits);
            return;
        }
        if (!file.getName().endsWith(".java")) return;
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (forbidden.matcher(line).find()) {
                hits.add(file.getPath() + ":" + (i + 1) + ":"
                        + line.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    private interface RenderJob {
        void run();
    }
}
