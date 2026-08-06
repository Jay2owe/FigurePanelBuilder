/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.figure;

import fpb.util.CancellationCheck;
import ij.ImagePlus;
import ij.io.Opener;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PanelWriterTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void pngWriterAddsPhysChunkForRequestedDpi() throws Exception {
        File output = temp.newFile("panel.png");
        PanelWriter.writePngAtomically(solid(10, 8, Color.BLACK), output, 300);

        PhysChunk phys = readPhysChunk(output);

        assertEquals(11811, phys.xPixelsPerMeter);
        assertEquals(11811, phys.yPixelsPerMeter);
        assertEquals(1, phys.unitSpecifier);
    }

    @Test
    public void overviewCompositionWritesExpectedGridSize() throws Exception {
        File dapi = writeSource("dapi.png", Color.RED, 24, 16);
        File merge = writeSource("merge.png", Color.GREEN, 24, 16);
        List<PanelRecord> records = Arrays.asList(
                record(dapi, "Control", "S1", "sec1", "DAPI", 24, 16,
                        0.5, CalibrationCheck.CalibrationSource.USER_ENTERED),
                record(merge, "Control", "S1", "sec1", "Merge", 24, 16,
                        0.5, CalibrationCheck.CalibrationSource.USER_ENTERED));
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(80)
                .scaleBarLengthUm(5.0)
                .scaleBarThicknessPx(2)
                .channelOrder(Arrays.asList("DAPI", "Merge"))
                .build();
        File output = temp.newFile("overview.png");

        PanelWriter.WriteReport report =
                PanelWriter.writeOverviewPanel(output, records, config);
        BufferedImage image = ImageIO.read(output);

        assertTrue(image.getWidth() >= 80 * 2);
        assertTrue(image.getHeight() >= 80);
        assertFalse(report.hasUnavailableScaleBars());
    }

    @Test
    public void unavailableCalibrationOmitsBarAndNamesAffectedImage()
            throws Exception {
        File rendered = writeSource("uncalibrated.png", Color.BLACK, 80, 60);
        File source = temp.newFile("original-source.tif");
        PanelRecord record = new PanelRecord(rendered, source, "Control", "S1",
                "sec1", "original-source.tif", "DAPI", "DAPI", 0, 80, 60,
                Double.NaN, Double.NaN, CalibrationCheck.CalibrationSource.NONE);
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(80)
                .scaleBarLengthUm(10.0)
                .build();

        PanelWriter.WriteReport report = new PanelWriter.WriteReport();
        PanelWriter.renderOverviewPanel(Arrays.asList(record), config, report);

        assertTrue(report.hasUnavailableScaleBars());
        assertEquals(1, report.uncalibratedImages().size());
        assertEquals(source.getAbsolutePath(), report.uncalibratedImages().get(0));
    }

    @Test
    public void overlongScaleBarIsOmittedInsteadOfShortenedAndMislabeled()
            throws Exception {
        File source = writeSource("overlong-bar.png", Color.BLACK, 100, 100);
        PanelRecord record = record(source, "Control", "S1", "", "DAPI",
                100, 100, 1.0, CalibrationCheck.CalibrationSource.USER_ENTERED);
        PanelConfig withBar = PanelConfig.builder()
                .cellSizePx(100)
                .channelOrder(Arrays.asList("DAPI"))
                .scaleBarEnabled(true)
                .scaleBarLengthUm(500.0)
                .build();
        PanelWriter.WriteReport report = new PanelWriter.WriteReport();

        BufferedImage actual = PanelWriter.renderOverviewPanel(
                Arrays.asList(record), withBar, report);
        BufferedImage withoutBar = PanelWriter.renderOverviewPanel(
                Arrays.asList(record), withBar.toBuilder()
                        .scaleBarEnabled(false).build());

        assertImagesEqual(withoutBar, actual);
        assertFalse(report.hasDrawnScaleBar());
        assertEquals(Arrays.asList(source.getAbsolutePath()),
                report.scaleBarsThatDidNotFit());
    }

    @Test
    public void subPixelCalibratedScaleBarIsReportedAsNotFitting()
            throws Exception {
        File source = writeSource("sub-pixel-bar.png", Color.BLACK, 100, 100);
        PanelRecord record = record(source, "Control", "S1", "", "DAPI",
                100, 100, 1.0, CalibrationCheck.CalibrationSource.USER_ENTERED);
        PanelConfig withBar = PanelConfig.builder()
                .cellSizePx(100)
                .channelOrder(Arrays.asList("DAPI"))
                .scaleBarEnabled(true)
                .scaleBarLengthUm(0.4)
                .build();
        PanelWriter.WriteReport report = new PanelWriter.WriteReport();

        BufferedImage actual = PanelWriter.renderOverviewPanel(
                Arrays.asList(record), withBar, report);
        BufferedImage withoutBar = PanelWriter.renderOverviewPanel(
                Arrays.asList(record), withBar.toBuilder()
                        .scaleBarEnabled(false).build());

        assertImagesEqual(withoutBar, actual);
        assertTrue(report.uncalibratedImages().isEmpty());
        assertFalse(report.hasDrawnScaleBar());
        assertEquals(Arrays.asList(source.getAbsolutePath()),
                report.scaleBarsThatDidNotFit());
    }

    @Test
    public void calibratedScaleBarWithCaptionWiderThanImageDoesNotDraw()
            throws Exception {
        double lengthUm = 1.23456789012345E-10;
        File source = writeSource("wide-caption-bar.png", Color.BLACK, 80, 80);
        PanelRecord record = record(source, "Control", "S1", "", "DAPI",
                80, 80, lengthUm / 10.0,
                CalibrationCheck.CalibrationSource.USER_ENTERED);
        PanelConfig withBar = PanelConfig.builder()
                .cellSizePx(80)
                .channelOrder(Arrays.asList("DAPI"))
                .scaleBarEnabled(true)
                .scaleBarLengthUm(lengthUm)
                .build();
        PanelWriter.WriteReport report = new PanelWriter.WriteReport();

        BufferedImage actual = PanelWriter.renderOverviewPanel(
                Arrays.asList(record), withBar, report);
        BufferedImage withoutBar = PanelWriter.renderOverviewPanel(
                Arrays.asList(record), withBar.toBuilder()
                        .scaleBarEnabled(false).build());

        assertImagesEqual(withoutBar, actual);
        assertTrue(report.uncalibratedImages().isEmpty());
        assertFalse(report.hasDrawnScaleBar());
        assertEquals(Arrays.asList(source.getAbsolutePath()),
                report.scaleBarsThatDidNotFit());
    }

    @Test
    public void overviewCompositionPollsCancellationBetweenRecords()
            throws Exception {
        File source = writeSource("cancel-overview.png", Color.BLACK, 80, 60);
        PanelRecord record = record(source, "Control", "S1", "", "DAPI",
                80, 60, 0.5, CalibrationCheck.CalibrationSource.USER_ENTERED);
        try {
            PanelWriter.renderOverviewPanel(Arrays.asList(record),
                    PanelConfig.builder().cellSizePx(80).build(),
                    new PanelWriter.WriteReport(), 1, new CancellationCheck() {
                        @Override
                        public boolean isCancelled() {
                            return true;
                        }
                    });
            throw new AssertionError("Expected overview cancellation");
        } catch (IOException expected) {
            assertEquals("Export cancelled.", expected.getMessage());
        }
    }

    @Test
    public void mixedCalibrationsProduceDifferentBarLengths() {
        PanelRecord fine = record(null, "Control", "S1", "sec1", "DAPI",
                200, 100, 0.5, CalibrationCheck.CalibrationSource.USER_ENTERED);
        PanelRecord coarse = record(null, "DrugA", "S2", "sec1", "DAPI",
                200, 100, 1.0, CalibrationCheck.CalibrationSource.USER_ENTERED);

        assertEquals(40, ScaleBar.lengthPixels(fine, 200, 20.0));
        assertEquals(20, ScaleBar.lengthPixels(coarse, 200, 20.0));
    }

    @Test
    public void figureWriterCreatesOutputTree() throws Exception {
        File source = writeSource("source.png", Color.BLUE, 32, 24);
        PanelRecord record = record(source, "Control", "S1", "sec1",
                "DAPI", 32, 24, 0.5,
                CalibrationCheck.CalibrationSource.USER_ENTERED);
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(80)
                .scaleBarLengthUm(5.0)
                .outputDpi(2400)
                .exportScale(4)
                .build();

        FigureWriter.FigureOutput output = new FigureWriter()
                .writeFigure(temp.getRoot(), "My Figure", Arrays.asList(record), config);

        assertTrue(output.figureDirectory().isDirectory());
        assertTrue(output.panelsDirectory().isDirectory());
        assertTrue(new File(new File(output.figureDirectory(),
                FigureWriter.SUPPORTING_DIR), "README.txt").isFile());
        assertTrue(output.figurePng().isFile());
        assertTrue(output.figureTif().isFile());
        File[] individual = output.panelsDirectory().listFiles();
        assertTrue(individual != null);
        assertEquals(2, individual.length);
        boolean sawPng = false;
        boolean sawTif = false;
        for (File file : individual) {
            if (file.getName().endsWith(".png")) {
                sawPng = true;
                BufferedImage panel = ImageIO.read(file);
                assertNotNull(panel);
                assertEquals(32, panel.getWidth());
                assertEquals(24, panel.getHeight());
            } else if (file.getName().endsWith(".tif")) {
                sawTif = true;
                ImagePlus panel = new Opener().openImage(file.getAbsolutePath());
                assertNotNull(panel);
                try {
                    assertEquals(32, panel.getWidth());
                    assertEquals(24, panel.getHeight());
                } finally {
                    panel.close();
                }
            }
        }
        assertTrue(sawPng);
        assertTrue(sawTif);
        assertTrue(output.uncalibratedImages().isEmpty());
    }

    @Test
    public void exportScaleRendersDirectHighResolutionLayout() throws Exception {
        File source = writeSource("scaled-source.png", Color.BLUE, 32, 24);
        PanelRecord record = record(source, "Control", "S1", "sec1",
                "DAPI", 32, 24, 0.5,
                CalibrationCheck.CalibrationSource.USER_ENTERED);
        PanelConfig oneX = PanelConfig.builder()
                .cellSizePx(80)
                .scaleBarLengthUm(5.0)
                .channelOrder(Arrays.asList("DAPI"))
                .exportScale(1)
                .build();
        PanelConfig twoX = oneX.toBuilder().exportScale(2).build();

        FigureWriter.FigureOutput normal = new FigureWriter().writeFigure(
                temp.getRoot(), "Scale One", Arrays.asList(record), oneX);
        FigureWriter.FigureOutput scaled = new FigureWriter().writeFigure(
                temp.getRoot(), "Scale Two", Arrays.asList(record), twoX);

        BufferedImage normalImage = ImageIO.read(normal.figurePng());
        BufferedImage scaledImage = ImageIO.read(scaled.figurePng());
        assertEquals(normalImage.getWidth() * 2, scaledImage.getWidth());
        assertEquals(normalImage.getHeight() * 2, scaledImage.getHeight());

        BufferedImage directTwoX = PanelWriter.renderOverviewPanel(
                Arrays.asList(record), twoX, new PanelWriter.WriteReport(), 2);
        assertImagesEqual(directTwoX, scaledImage);
    }

    @Test
    public void overviewAnnotatesWhenIndividualAnnotationIsEnabledButNotWritten()
            throws Exception {
        File source = writeSource("annotation-fallback.png", Color.BLACK, 100, 60);
        PanelRecord record = record(source, "Control", "S1", "sec1",
                "DAPI", 100, 60, 0.5,
                CalibrationCheck.CalibrationSource.USER_ENTERED);
        PanelConfig annotated = PanelConfig.builder()
                .cellSizePx(100)
                .channelOrder(Arrays.asList("DAPI"))
                .annotateOverviewPanel(true)
                .annotateIndividualPanels(true)
                .scaleBarEnabled(false)
                .build();
        PanelConfig plain = annotated.toBuilder()
                .annotateIndividualPanels(false)
                .annotateOverviewPanel(false)
                .build();

        BufferedImage withAnnotation = PanelWriter.renderOverviewPanel(
                Arrays.asList(record), annotated);
        BufferedImage withoutAnnotation = PanelWriter.renderOverviewPanel(
                Arrays.asList(record), plain);

        assertTrue("overview annotation should be drawn from the unannotated source",
                differingPixels(withAnnotation, withoutAnnotation) > 0);
    }

    private File writeSource(String name, Color color, int width, int height)
            throws IOException {
        File file = temp.newFile(name);
        ImageIO.write(solid(width, height, color), "png", file);
        return file;
    }

    private static BufferedImage solid(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void assertImagesEqual(BufferedImage expected,
            BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        assertEquals(0, differingPixels(expected, actual));
    }

    private static int differingPixels(BufferedImage left, BufferedImage right) {
        assertEquals(left.getWidth(), right.getWidth());
        assertEquals(left.getHeight(), right.getHeight());
        int differences = 0;
        for (int y = 0; y < left.getHeight(); y++) {
            for (int x = 0; x < left.getWidth(); x++) {
                if (left.getRGB(x, y) != right.getRGB(x, y)) differences++;
            }
        }
        return differences;
    }

    private static PanelRecord record(File file, String group, String subject,
            String section, String output, int width, int height,
            double pixelSize, CalibrationCheck.CalibrationSource source) {
        return new PanelRecord(file, group, subject, section, output, output,
                0, width, height, pixelSize, pixelSize, source);
    }

    private static PhysChunk readPhysChunk(File png) throws IOException {
        DataInputStream in = new DataInputStream(new FileInputStream(png));
        try {
            long signature = in.readLong();
            if (signature != 0x89504E470D0A1A0AL) {
                throw new IOException("Not a PNG file: " + png);
            }
            while (true) {
                int length = in.readInt();
                byte[] typeBytes = new byte[4];
                in.readFully(typeBytes);
                String type = new String(typeBytes, "US-ASCII");
                byte[] data = new byte[length];
                in.readFully(data);
                in.readInt();
                if ("pHYs".equals(type)) {
                    if (data.length != 9) throw new IOException("Invalid pHYs length");
                    return new PhysChunk(readInt(data, 0), readInt(data, 4),
                            data[8] & 0xFF);
                }
            }
        } catch (EOFException eof) {
            throw new IOException("PNG has no pHYs chunk", eof);
        } finally {
            in.close();
        }
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static final class PhysChunk {
        final int xPixelsPerMeter;
        final int yPixelsPerMeter;
        final int unitSpecifier;

        PhysChunk(int xPixelsPerMeter, int yPixelsPerMeter,
                int unitSpecifier) {
            this.xPixelsPerMeter = xPixelsPerMeter;
            this.yPixelsPerMeter = yPixelsPerMeter;
            this.unitSpecifier = unitSpecifier;
        }
    }
}
