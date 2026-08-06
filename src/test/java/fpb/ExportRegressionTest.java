/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb;

import fpb.figure.FigureWriter;
import fpb.figure.ImageOrientation;
import fpb.figure.PanelConfig;
import fpb.io.ImageLoader;
import fpb.io.ProgressCallback;
import fpb.render.ChannelColour;
import fpb.ui.Step5Export;
import fpb.util.CsvSupport;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.measure.Calibration;
import ij.process.ShortProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Regression coverage for the first sequential release-verification findings. */
public class ExportRegressionTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void exportReloadsFullResolutionAndRecordsRealSourceAndPanelPaths()
            throws Exception {
        File input = temp.newFolder("input");
        File source = new File(input, "Control_S1.tif");
        saveStack(source, 320, 180, new int[] { 1000 });
        File output = temp.newFolder("output");
        int tempPanelsBefore = temporaryPanelCount();

        PanelConfig layout = PanelConfig.builder()
                .createOverviewPanel(true)
                .annotateOverviewPanel(true)
                .annotateIndividualPanels(false)
                .channelOrder(Arrays.asList("C1", "Merge"))
                .cellSizePx(220)
                .labelMode(PanelConfig.LabelMode.CHANNEL_NAME)
                .build();
        FPBParameters parameters = FPBParameters.builder(input)
                .channel(1, "Signal", ChannelColour.BLUE, 0, 2000)
                .pick("Control", "S1")
                .panelConfig(layout)
                .outputFolder(output)
                .figureName("FullResolution")
                .writeTiff(false)
                .writeSvg(false)
                .build();

        Step5Export.ExportResult result = FPB.runAndWrite(parameters);
        File[] panels = supporting(result.figureDirectory(), "panels").listFiles();
        assertTrue(panels != null && panels.length == 2);
        BufferedImage channel = ImageIO.read(findNamed(panels, "Signal"));
        assertEquals(320, channel.getWidth());
        assertEquals(180, channel.getHeight());
        assertUniform(channel);

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(
                supporting(result.figureDirectory(), "manifest.csv"));
        try {
            String[] header = CsvSupport.parseRecord(reader.readRecord().text);
            String[] row = CsvSupport.parseRecord(reader.readRecord().text);
            String sourcePath = row[column(header, "SourceFile")];
            String panelPath = row[column(header, "PanelFile")];
            assertEquals(source.getAbsolutePath(), sourcePath);
            assertEquals("max", row[column(header, "ZMode")]);
            assertTrue(panelPath.startsWith(result.figureDirectory().getAbsolutePath()));
            assertTrue(panelPath.contains(File.separator
                    + FigureWriter.SUPPORTING_DIR + File.separator));
            assertTrue(new File(panelPath).isFile());
            assertNotEquals(sourcePath, panelPath);
            assertEquals("1", row[column(header, "GroupRank")]);
            String[] merge = CsvSupport.parseRecord(reader.readRecord().text);
            assertEquals("Merge", merge[column(header, "ChannelName")]);
            assertEquals("1", merge[column(header, "GroupRank")]);
            assertEquals("S1", merge[column(header, "SuggestedSubject")]);
            assertEquals("S1", merge[column(header, "ChosenSubject")]);
        } finally {
            reader.close();
        }
        assertTrue(result.metadataCsv().isFile());
        String methods = new String(java.nio.file.Files.readAllBytes(
                supporting(result.figureDirectory(), "methods.txt").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(methods.contains("Z handling:            max"));
        assertFalse("staging directories must be removed",
                containsStagingDirectory(output));
        assertEquals(tempPanelsBefore, temporaryPanelCount());
    }

    @Test
    public void svgBakesTheCommittedDisplayRangeIntoEmbeddedPanels()
            throws Exception {
        File input = temp.newFolder("svg-range-input");
        saveStack(new File(input, "Control_S1.tif"), 24, 24,
                new int[] { 10000 });
        File output = temp.newFolder("svg-range-output");
        PanelConfig layout = PanelConfig.builder()
                .createOverviewPanel(true)
                .annotateOverviewPanel(false)
                .annotateIndividualPanels(false)
                .channelOrder(Arrays.asList("Signal"))
                .cellSizePx(24)
                .groupHeaderVisible(false)
                .channelHeaderVisible(false)
                .rowLabelVisible(false)
                .scaleBarEnabled(false)
                .labelMode(PanelConfig.LabelMode.NONE)
                .build();
        FPBParameters parameters = FPBParameters.builder(input)
                .channel(1, "Signal", ChannelColour.BLUE, 9000, 11000)
                .pick("Control", "S1")
                .panelConfig(layout)
                .outputFolder(output)
                .figureName("SvgRange")
                .writePng(false)
                .writeTiff(false)
                .writeSvg(true)
                .writeIndividualPanels(false)
                .build();

        Step5Export.ExportResult result = FPB.runAndWrite(parameters);
        String svg = new String(java.nio.file.Files.readAllBytes(
                new File(result.figureDirectory(), "figure.svg").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        String prefix = "data:image/png;base64,";
        int start = svg.indexOf(prefix);
        assertTrue(start >= 0);
        start += prefix.length();
        int end = svg.indexOf('"', start);
        BufferedImage embedded = ImageIO.read(new java.io.ByteArrayInputStream(
                Base64.getDecoder().decode(svg.substring(start, end))));

        Color middle = new Color(embedded.getRGB(0, 0));
        assertEquals(0, middle.getRed());
        assertEquals(0, middle.getGreen());
        assertEquals(128, middle.getBlue());

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(
                supporting(result.figureDirectory(), "manifest.csv"));
        try {
            String[] header = CsvSupport.parseRecord(reader.readRecord().text);
            String[] row = CsvSupport.parseRecord(reader.readRecord().text);
            assertEquals("9000", row[column(header, "DisplayMin")]);
            assertEquals("11000", row[column(header, "DisplayMax")]);
        } finally {
            reader.close();
        }
    }

    @Test
    public void customRangesProduceIdenticalIndividualPngAndSvgAssets()
            throws Exception {
        File input = temp.newFolder("all-range-assets-input");
        saveChannels(new File(input, "Control_S1.tif"), 18, 12,
                new int[] { 10000, 2000 });
        File output = temp.newFolder("all-range-assets-output");
        PanelConfig layout = PanelConfig.builder()
                .createOverviewPanel(true)
                .annotateOverviewPanel(false)
                .annotateIndividualPanels(false)
                .channelOrder(Arrays.asList("DAPI", "Signal", "Merge"))
                .cellSizePx(40)
                .groupHeaderVisible(false)
                .channelHeaderVisible(false)
                .rowLabelVisible(false)
                .scaleBarEnabled(false)
                .labelMode(PanelConfig.LabelMode.NONE)
                .build();
        FPBParameters parameters = FPBParameters.builder(input)
                .channel(1, "DAPI", ChannelColour.BLUE, 9000, 11000)
                .channel(2, "Signal", ChannelColour.MAGENTA, 1000, 3000)
                .pick("Control", "S1")
                .panelConfig(layout)
                .outputFolder(output)
                .figureName("AllRangeAssets")
                .writePng(true)
                .writeTiff(false)
                .writeSvg(true)
                .writeIndividualPanels(true)
                .build();

        Step5Export.ExportResult result = FPB.runAndWrite(parameters);
        File[] panels = supporting(result.figureDirectory(), "panels").listFiles();
        assertTrue(panels != null);
        File dapiFile = findNamedEnding(panels, "DAPI", ".png");
        File signalFile = findNamedEnding(panels, "Signal", ".png");
        File mergeFile = findNamedEnding(panels, "Merge", ".png");
        BufferedImage dapi = ImageIO.read(dapiFile);
        BufferedImage signal = ImageIO.read(signalFile);
        BufferedImage merge = ImageIO.read(mergeFile);

        assertEquals(new Color(0, 0, 128), new Color(dapi.getRGB(0, 0)));
        assertEquals(new Color(128, 0, 128), new Color(signal.getRGB(0, 0)));
        assertEquals(new Color(128, 0, 255), new Color(merge.getRGB(0, 0)));

        String svg = new String(java.nio.file.Files.readAllBytes(
                new File(result.figureDirectory(), "figure.svg").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        List<byte[]> embedded = embeddedPngBytes(svg);
        assertEquals(3, embedded.size());
        assertArrayEquals(java.nio.file.Files.readAllBytes(dapiFile.toPath()),
                embedded.get(0));
        assertArrayEquals(java.nio.file.Files.readAllBytes(signalFile.toPath()),
                embedded.get(1));
        assertArrayEquals(java.nio.file.Files.readAllBytes(mergeFile.toPath()),
                embedded.get(2));
    }

    @Test
    public void optionalProjectExportWritesEveryImageAtFullResolutionWithLockedRanges()
            throws Exception {
        File input = temp.newFolder("all-project-input");
        saveChannels(new File(input, "Control_S1.tif"), 18, 12,
                new int[] { 1000, 2000 });
        saveChannels(new File(input, "Control_S2.tif"), 18, 12,
                new int[] { 1000, 2000 });
        saveChannels(new File(input, "Control_S3.tif"), 18, 12,
                new int[] { 1000, 2000 });
        File output = temp.newFolder("all-project-output");
        PanelConfig layout = PanelConfig.builder()
                .createOverviewPanel(true)
                .annotateOverviewPanel(false)
                .annotateIndividualPanels(false)
                .channelOrder(Arrays.asList("DAPI", "Signal", "Merge"))
                .cellSizePx(40)
                .scaleBarEnabled(false)
                .labelMode(PanelConfig.LabelMode.NONE)
                .build();
        FPBParameters parameters = FPBParameters.builder(input)
                .channel(1, "DAPI", ChannelColour.BLUE, 0, 2000)
                .channel(2, "Signal", ChannelColour.MAGENTA, 1000, 3000)
                .pick("Control", "S1")
                .panelConfig(layout)
                .outputFolder(output)
                .figureName("AllProject")
                .writeTiff(false)
                .writeSvg(false)
                .writeIndividualPanels(false)
                .writeAllProjectPng(true)
                .writeAllProjectTiffStacks(true)
                .build();

        Step5Export.ExportResult result = FPB.runAndWrite(parameters);
        File allImages = supporting(result.figureDirectory(),
                fpb.record.OutputTree.ALL_PROJECT_IMAGES_DIR);
        File[] files = allImages.listFiles();
        assertTrue(files != null);
        assertEquals(12, files.length);

        File nonSelectedPng = new File(allImages, "Control_S3_DAPI.png");
        assertTrue(nonSelectedPng.isFile());
        BufferedImage png = ImageIO.read(nonSelectedPng);
        assertEquals(18, png.getWidth());
        assertEquals(12, png.getHeight());
        Color blue = new Color(png.getRGB(0, 0));
        assertEquals(0, blue.getRed());
        assertEquals(0, blue.getGreen());
        assertEquals(128, blue.getBlue());

        File stackFile = new File(allImages,
                "Control_S3_channels.tif");
        assertTrue(stackFile.isFile());
        ImagePlus stack = new Opener().openImage(stackFile.getAbsolutePath());
        try {
            assertEquals(2, stack.getStackSize());
            assertEquals("DAPI", stack.getStack().getSliceLabel(1));
            assertEquals("Signal", stack.getStack().getSliceLabel(2));
            Color stackBlue = new Color(stack.getStack().getProcessor(1)
                    .getPixel(0, 0));
            Color stackMagenta = new Color(stack.getStack().getProcessor(2)
                    .getPixel(0, 0));
            assertEquals(128, stackBlue.getBlue());
            assertEquals(0, stackBlue.getRed());
            assertEquals(128, stackMagenta.getRed());
            assertEquals(128, stackMagenta.getBlue());
            assertEquals(0.5, stack.getCalibration().pixelWidth, 0.000001);
            assertEquals(0.5, stack.getCalibration().pixelHeight, 0.000001);
        } finally {
            stack.changes = false;
            stack.close();
        }
    }

    @Test
    public void individualTiffExportWritesOneChannelOnlyHyperstackPerImage()
            throws Exception {
        File input = temp.newFolder("individual-stack-input");
        saveChannels(new File(input, "Control_S1.tif"), 18, 12,
                new int[] { 1000, 2000 });
        File output = temp.newFolder("individual-stack-output");
        PanelConfig layout = PanelConfig.builder()
                .createOverviewPanel(true)
                .annotateOverviewPanel(false)
                .annotateIndividualPanels(false)
                .channelOrder(Arrays.asList("DAPI", "Signal", "Merge"))
                .cellSizePx(40)
                .scaleBarEnabled(false)
                .labelMode(PanelConfig.LabelMode.NONE)
                .build();
        FPBParameters parameters = FPBParameters.builder(input)
                .channel(1, "DAPI", ChannelColour.BLUE, 0, 2000)
                .channel(2, "Signal", ChannelColour.MAGENTA, 1000, 3000)
                .pick("Control", "S1")
                .panelConfig(layout)
                .outputFolder(output)
                .figureName("IndividualStack")
                .writePng(false)
                .writeTiff(true)
                .writeSvg(false)
                .writeIndividualPanels(true)
                .build();

        Step5Export.ExportResult result = FPB.runAndWrite(parameters);
        File panelsDir = supporting(result.figureDirectory(), "panels");
        File[] panelFiles = panelsDir.listFiles();
        assertTrue(panelFiles != null);
        assertEquals(1, panelFiles.length);
        assertEquals("Control_S1_channels.tif", panelFiles[0].getName());

        ImagePlus stack = new Opener().openImage(panelFiles[0].getAbsolutePath());
        try {
            assertEquals(2, stack.getStackSize());
            assertEquals(2, stack.getNChannels());
            assertEquals(1, stack.getNSlices());
            assertEquals(1, stack.getNFrames());
            assertTrue(stack.getOpenAsHyperStack());
            assertEquals("DAPI", stack.getStack().getSliceLabel(1));
            assertEquals("Signal", stack.getStack().getSliceLabel(2));
            assertFalse("Merge".equals(stack.getStack().getSliceLabel(1)));
            assertFalse("Merge".equals(stack.getStack().getSliceLabel(2)));
            Color blue = new Color(stack.getStack().getProcessor(1)
                    .getPixel(0, 0));
            Color magenta = new Color(stack.getStack().getProcessor(2)
                    .getPixel(0, 0));
            assertEquals(128, blue.getBlue());
            assertEquals(0, blue.getRed());
            assertEquals(128, magenta.getRed());
            assertEquals(128, magenta.getBlue());
            assertEquals(0.5, stack.getCalibration().pixelWidth, 0.000001);
            assertEquals(0.5, stack.getCalibration().pixelHeight, 0.000001);
        } finally {
            if (stack != null) {
                stack.changes = false;
                stack.close();
            }
        }
    }

    @Test
    public void imageOrientationReachesEveryFormatAndSwapsCalibrationAxes()
            throws Exception {
        File input = temp.newFolder("orientation-input");
        savePattern(new File(input, "Control_S1.tif"), 3, 2,
                new int[] {1000, 2000, 3000, 4000, 5000, 6000},
                0.25, 0.75);
        File output = temp.newFolder("orientation-output");
        ImageOrientation right = ImageOrientation.IDENTITY
                .then(ImageOrientation.Action.ROTATE_RIGHT);
        PanelConfig layout = PanelConfig.builder()
                .createOverviewPanel(true)
                .annotateOverviewPanel(false)
                .annotateIndividualPanels(false)
                .channelOrder(Arrays.asList("Signal", "Merge"))
                .cellSizePx(24)
                .groupHeaderVisible(false)
                .channelHeaderVisible(false)
                .rowLabelVisible(false)
                .scaleBarEnabled(false)
                .labelMode(PanelConfig.LabelMode.NONE)
                .imageOrientation("Control_S1.tif", right)
                .build();
        FPBParameters parameters = FPBParameters.builder(input)
                .channel(1, "Signal", ChannelColour.BLUE, 0, 6000)
                .pickImage("Control", "Control_S1.tif")
                .panelConfig(layout)
                .outputFolder(output)
                .figureName("Oriented")
                .writePng(true)
                .writeTiff(true)
                .writeSvg(true)
                .writeIndividualPanels(true)
                .writeAllProjectPng(true)
                .writeAllProjectTiffStacks(true)
                .build();

        Step5Export.ExportResult result = FPB.runAndWrite(parameters);
        File[] panels = supporting(result.figureDirectory(), "panels").listFiles();
        assertTrue(panels != null);
        BufferedImage individual = ImageIO.read(findNamedEnding(
                panels, "Signal", ".png"));
        assertEquals(2, individual.getWidth());
        assertEquals(3, individual.getHeight());
        assertTrue(new Color(individual.getRGB(0, 0)).getBlue()
                > new Color(individual.getRGB(1, 0)).getBlue());

        File allImages = supporting(result.figureDirectory(),
                fpb.record.OutputTree.ALL_PROJECT_IMAGES_DIR);
        BufferedImage projectPng = ImageIO.read(new File(allImages,
                "Control_S1_Signal.png"));
        assertEquals(2, projectPng.getWidth());
        assertEquals(3, projectPng.getHeight());

        ImagePlus stack = new Opener().openImage(new File(allImages,
                "Control_S1_channels.tif").getAbsolutePath());
        try {
            assertEquals(2, stack.getWidth());
            assertEquals(3, stack.getHeight());
            assertEquals(0.75, stack.getCalibration().pixelWidth, 0.000001);
            assertEquals(0.25, stack.getCalibration().pixelHeight, 0.000001);
        } finally {
            stack.changes = false;
            stack.close();
        }

        String svg = new String(java.nio.file.Files.readAllBytes(
                new File(result.figureDirectory(), "figure.svg").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        String prefix = "data:image/png;base64,";
        int start = svg.indexOf(prefix) + prefix.length();
        int end = svg.indexOf('"', start);
        BufferedImage embedded = ImageIO.read(new java.io.ByteArrayInputStream(
                Base64.getDecoder().decode(svg.substring(start, end))));
        assertEquals(2, embedded.getWidth());
        assertEquals(3, embedded.getHeight());

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(
                supporting(result.figureDirectory(), "manifest.csv"));
        try {
            String[] header = CsvSupport.parseRecord(reader.readRecord().text);
            String[] row = CsvSupport.parseRecord(reader.readRecord().text);
            assertEquals("2", row[column(header, "WidthPx")]);
            assertEquals("3", row[column(header, "HeightPx")]);
            assertEquals(0.75, Double.parseDouble(
                    row[column(header, "PixelWidthUm")]), 0.00001);
            assertEquals(0.25, Double.parseDouble(
                    row[column(header, "PixelHeightUm")]), 0.00001);
        } finally {
            reader.close();
        }
    }

    @Test
    public void selectedAnimalExportsEverySectionCountedForThatAnimal()
            throws Exception {
        File input = temp.newFolder("all-sections-input");
        saveStack(new File(input, "Control_S1_sec1.tif"), 32, 32,
                new int[] { 10000 });
        saveStack(new File(input, "Control_S1_sec2.tif"), 32, 32,
                new int[] { 30000 });
        saveStack(new File(input, "Control_S1_sec3.tif"), 32, 32,
                new int[] { 50000 });
        File output = temp.newFolder("all-sections-output");
        PanelConfig layout = PanelConfig.builder()
                .createOverviewPanel(true)
                .annotateOverviewPanel(false)
                .annotateIndividualPanels(false)
                .channelOrder(Arrays.asList("Signal", "Merge"))
                .cellSizePx(80)
                .scaleBarEnabled(false)
                .labelMode(PanelConfig.LabelMode.NONE)
                .marginPx(0)
                .innerColGapPx(0)
                .rowGapPx(0)
                .groupHeaderVisible(false)
                .channelHeaderVisible(false)
                .groupLayoutRows(Arrays.asList(Arrays.asList("Control")))
                .build();
        FPBParameters parameters = FPBParameters.builder(input)
                .groupToken(1)
                .subjectToken(2)
                .sectionToken(3)
                .channel(1, "Signal", ChannelColour.GREY, 0, 65535)
                .pick("Control", "S1")
                .panelConfig(layout)
                .outputFolder(output)
                .figureName("AllSections")
                .writeTiff(false)
                .writeSvg(false)
                .build();

        FPBResult planned = FPB.run(parameters);
        assertEquals(6, planned.manifest().size());
        assertEquals("sec1", planned.manifest().get(0).section());
        assertEquals("sec2", planned.manifest().get(2).section());
        assertEquals("sec3", planned.manifest().get(4).section());

        Step5Export.ExportResult result = FPB.write(planned);
        File[] panels = supporting(result.figureDirectory(), "panels").listFiles();
        assertTrue(panels != null && panels.length == 6);
        BufferedImage assembled = ImageIO.read(
                new File(result.figureDirectory(), "figure.png"));
        assertEquals(3 * planned.panelConfig().cellSizePx(), assembled.getHeight());
        int mergeCentreX = assembled.getWidth()
                - planned.panelConfig().cellSizePx() / 2;
        int first = assembled.getRGB(mergeCentreX, 40);
        int second = assembled.getRGB(mergeCentreX, 120);
        int third = assembled.getRGB(mergeCentreX, 200);
        assertNotEquals(first, second);
        assertNotEquals(second, third);
        assertNotEquals(first, third);
        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(
                supporting(result.figureDirectory(), "manifest.csv"));
        int rows = 0;
        try {
            reader.readRecord();
            while (reader.readRecord() != null) rows++;
        } finally {
            reader.close();
        }
        assertEquals(6, rows);
    }

    @Test
    public void guidedExportWritesAuditableCrossGroupQuantification()
            throws Exception {
        File input = temp.newFolder("cross-group-input");
        saveStack(new File(input, "Control_S1.tif"), 24, 24,
                new int[] { 10 });
        saveStack(new File(input, "Control_S2.tif"), 24, 24,
                new int[] { 20 });
        saveStack(new File(input, "Drug_S1.tif"), 24, 24,
                new int[] { 30 });
        saveStack(new File(input, "Drug_S2.tif"), 24, 24,
                new int[] { 50 });
        File output = temp.newFolder("cross-group-output");
        FPBParameters parameters = FPBParameters.builder(input)
                .channel(1, "Signal", ChannelColour.GREY, 0, 100)
                .pick("Control", "S1")
                .pick("Drug", "S2")
                .outputFolder(output)
                .figureName("CrossGroup")
                .writeTiff(false)
                .writeSvg(false)
                .build();

        Step5Export.ExportResult result = FPB.runAndWrite(parameters);
        File csv = supporting(result.figureDirectory(),
                "group_quantification.csv");
        File plot = supporting(result.figureDirectory(),
                "group_quantification.png");
        assertTrue(csv.isFile());
        assertTrue(plot.isFile());
        assertFalse(new File(result.figureDirectory(),
                "group_quantification.csv").exists());
        assertFalse(new File(result.figureDirectory(),
                "group_quantification.png").exists());
        BufferedImage image = ImageIO.read(plot);
        assertEquals(600, image.getWidth());
        assertEquals(320, image.getHeight());

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(csv);
        try {
            String[] header = CsvSupport.parseRecord(reader.readRecord().text);
            assertTrue(column(header, "Section") >= 0);
            assertTrue(column(header, "SectionValue") >= 0);
            assertTrue(column(header, "ZScore") >= 0);
            assertTrue(column(header, "GroupZMean") >= 0);
            java.util.Map<String, String> means =
                    new java.util.LinkedHashMap<String, String>();
            java.util.Map<String, String> chosen =
                    new java.util.LinkedHashMap<String, String>();
            CsvSupport.Record record;
            while ((record = reader.readRecord()) != null) {
                String[] row = CsvSupport.parseRecord(record.text);
                means.put(row[column(header, "Group")],
                        row[column(header, "GroupMean")]);
                if ("yes".equals(row[column(header, "Chosen")])) {
                    chosen.put(row[column(header, "Group")],
                            row[column(header, "Subject")]);
                }
            }
            assertEquals("15.0", means.get("Control"));
            assertEquals("40.0", means.get("Drug"));
            assertEquals("S1", chosen.get("Control"));
            assertEquals("S2", chosen.get("Drug"));
        } finally {
            reader.close();
        }
    }

    @Test
    public void cancelledExportLeavesNoFinalFigureDirectory() throws Exception {
        File input = temp.newFolder("cancel-input");
        saveStack(new File(input, "Control_S1.tif"), 32, 18,
                new int[] { 1000 });
        FPBParameters parameters = FPBParameters.builder(input)
                .channel(1, "Signal", ChannelColour.GREY, 0, 2000)
                .pick("Control", "S1")
                .build();
        FPBResult planned = FPB.run(parameters);
        File output = temp.newFolder("cancel-output");
        Step5Export.Settings settings = new Step5Export.Settings(output,
                "Cancelled", 300, 1, true, false, false, true, true);
        try {
            Step5Export.export(planned.toContext(), settings,
                    new FigureWriter.CancelCheck() {
                        @Override
                        public boolean isCancelled() {
                            return true;
                        }
                    }, Step5Export.NONE);
            fail("Expected export cancellation");
        } catch (IOException expected) {
            assertEquals("Export cancelled.", expected.getMessage());
        }
        File root = new File(output, FigureWriter.ROOT_DIR);
        File[] children = root.listFiles();
        assertTrue(children == null || children.length == 0);
        assertFalse(containsStagingDirectory(output));
    }

    @Test
    public void cancellationDuringPanelPreparationDeletesPartialTemporaryFiles()
            throws Exception {
        File input = temp.newFolder("partial-cancel-input");
        saveStack(new File(input, "Control_S1.tif"), 48, 24,
                new int[] { 1000 });
        FPBResult planned = FPB.run(FPBParameters.builder(input)
                .channel(1, "Signal", ChannelColour.GREY, 0, 2000)
                .pick("Control", "S1")
                .build());
        File output = temp.newFolder("partial-cancel-output");
        Step5Export.Settings settings = new Step5Export.Settings(output,
                "Partial", 300, 1, true, false, false, true, true);
        final java.util.concurrent.atomic.AtomicInteger checks =
                new java.util.concurrent.atomic.AtomicInteger();
        int before = temporaryPanelCount();

        try {
            Step5Export.export(planned.toContext(), settings,
                    new FigureWriter.CancelCheck() {
                        @Override
                        public boolean isCancelled() {
                            return checks.incrementAndGet() >= 4;
                        }
                    }, Step5Export.NONE);
            fail("Expected export cancellation");
        } catch (IOException expected) {
            assertEquals("Export cancelled.", expected.getMessage());
        }

        assertEquals(before, temporaryPanelCount());
        assertFalse(containsStagingDirectory(output));
    }

    @Test
    public void fullResolutionLoadingPollsCancellationDuringProjection()
            throws Exception {
        File input = temp.newFolder("load-cancel-input");
        File source = new File(input, "Control_S1.tif");
        int[] slices = new int[12];
        Arrays.fill(slices, 1000);
        saveStack(source, 128, 96, slices);
        final java.util.concurrent.atomic.AtomicInteger polls =
                new java.util.concurrent.atomic.AtomicInteger();

        try {
            new ImageLoader().loadFullResolution(source, ImageLoader.ZMode.MAX,
                    new ImageLoader.CancelCheck() {
                        @Override
                        public boolean isCancelled() {
                            return polls.incrementAndGet() >= 10;
                        }
                    });
            fail("Expected image loading cancellation");
        } catch (ImageLoader.LoadCancelledException expected) {
            assertEquals("Image loading cancelled.", expected.getMessage());
        }
        assertTrue(polls.get() >= 10);
    }

    @Test
    public void firstAndMaximumZModesProduceDifferentPlanes() throws Exception {
        File image = new File(temp.newFolder("z"), "Control_S1.tif");
        saveStack(image, 4, 3, new int[] { 10, 20, 30 });
        ImageLoader loader = new ImageLoader(150, 1);
        ImageLoader.LoadResult first = loader.loadFiles(Arrays.asList(image),
                ImageLoader.ZMode.FIRST, ProgressCallback.NONE);
        ImageLoader.LoadResult max = loader.loadFiles(Arrays.asList(image),
                ImageLoader.ZMode.MAX, ProgressCallback.NONE);
        assertEquals(10, first.planeCache().plane(0, 0).pixels()[0] & 0xffff);
        assertEquals(30, max.planeCache().plane(0, 0).pixels()[0] & 0xffff);
        try {
            ImageLoader.ZMode.fromString("current");
            fail("Folder input must not advertise a non-reproducible current slice");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("max or first"));
        }
    }

    @Test
    public void externalStatisticEvidenceAppliesToThirdChannelAndMerge()
            throws Exception {
        File input = temp.newFolder("external-stat-input");
        File source = new File(input, "Control_S1.tif");
        saveChannels(source, 8, 6, new int[] { 100, 200, 300 });
        File csv = temp.newFile("external-stat.csv");
        java.nio.file.Files.write(csv.toPath(),
                "File,MeanIntensity\nControl_S1.tif,42.5\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File output = temp.newFolder("external-stat-output");
        PanelConfig layout = PanelConfig.builder()
                .channelOrder(Arrays.asList("Third", "Merge"))
                .build();
        FPBParameters parameters = FPBParameters.builder(input)
                .channel(3, "Third", ChannelColour.GREEN, 0, 1000)
                .statisticCsv(csv)
                .statisticColumn("MeanIntensity")
                .pick("Control", "S1")
                .panelConfig(layout)
                .outputFolder(output)
                .writeTiff(false)
                .writeSvg(false)
                .build();

        Step5Export.ExportResult result = FPB.runAndWrite(parameters);
        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(
                supporting(result.figureDirectory(), "manifest.csv"));
        try {
            String[] header = CsvSupport.parseRecord(reader.readRecord().text);
            String[] channel = CsvSupport.parseRecord(reader.readRecord().text);
            String[] merge = CsvSupport.parseRecord(reader.readRecord().text);
            assertEquals("Third", channel[column(header, "ChannelName")]);
            assertEquals("42.5", channel[column(header, "StatisticValue")]);
            assertEquals("42.5", merge[column(header, "StatisticValue")]);
        } finally {
            reader.close();
        }
    }

    @Test
    public void recursiveDuplicateBasenamesKeepTheirOwnClipEvidence()
            throws Exception {
        File input = temp.newFolder("duplicates");
        File control = new File(input, "Control");
        File drug = new File(input, "DrugA");
        assertTrue(control.mkdir());
        assertTrue(drug.mkdir());
        saveStack(new File(control, "Sample.tif"), 16, 10, new int[] { 10 });
        saveStack(new File(drug, "Sample.tif"), 16, 10, new int[] { 20 });
        File output = temp.newFolder("duplicates-output");
        FPBParameters parameters = FPBParameters.builder(input)
                .recursive(true)
                .metadataMode(FPBParameters.MetadataMode.SUBFOLDER)
                .channel(1, "Signal", ChannelColour.GREY, 0, 15)
                .pick("Control", "Sample")
                .pick("DrugA", "Sample")
                .writeTiff(false)
                .writeSvg(false)
                .outputFolder(output)
                .build();

        Step5Export.ExportResult result = FPB.runAndWrite(parameters);
        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(
                supporting(result.figureDirectory(), "manifest.csv"));
        java.util.Map<String, String> clippedById =
                new java.util.LinkedHashMap<String, String>();
        try {
            String[] header = CsvSupport.parseRecord(reader.readRecord().text);
            CsvSupport.Record record;
            while ((record = reader.readRecord()) != null) {
                String[] row = CsvSupport.parseRecord(record.text);
                if (!"Signal".equals(row[column(header, "ChannelName")])) continue;
                clippedById.put(row[column(header, "SourceImageId")],
                        row[column(header, "ClippedHighPct")]);
            }
        } finally {
            reader.close();
        }
        assertEquals("0.0", clippedById.get("Control/Sample.tif"));
        assertEquals("100.0", clippedById.get("DrugA/Sample.tif"));
    }

    @Test
    public void recursiveReplaySkipsItsGeneratedOutputTree() throws Exception {
        File input = temp.newFolder("recursive-replay-input");
        saveStack(new File(input, "Control_S1.tif"), 24, 12,
                new int[] { 1000 });
        FPBParameters original = FPBParameters.builder(input)
                .recursive(true)
                .channel(1, "Signal", ChannelColour.GREY, 0, 2000)
                .pick("Control", "S1")
                .outputFolder(input)
                .figureName("Replay")
                .writeTiff(false)
                .writeSvg(false)
                .build();

        Step5Export.ExportResult first = FPB.runAndWrite(original);
        FPBParameters replay = original.toBuilder()
                .metadataCsv(first.metadataCsv())
                .build();
        FPBResult plannedReplay = FPB.run(replay);
        Step5Export.ExportResult second = FPB.write(plannedReplay);

        assertEquals(1, plannedReplay.metadataTable().fileCount());
        assertTrue(second.figureDirectory().isDirectory());
        assertNotEquals(first.figureDirectory().getAbsolutePath(),
                second.figureDirectory().getAbsolutePath());
    }

    @Test
    public void macroRoundTripPreservesFullLayoutQuickGridAndResultRecords()
            throws Exception {
        File input = temp.newFolder("macro-input");
        saveStack(new File(input, "Control_S1.tif"), 24, 12,
                new int[] { 1000 });
        PanelConfig layout = PanelConfig.builder()
                .createOverviewPanel(true)
                .annotateOverviewPanel(true)
                .annotateIndividualPanels(false)
                .groupRowsBy(PanelConfig.GroupRowsBy.SUBJECT)
                .channelOrder(Arrays.asList("Signal", "Merge"))
                .cellSizePx(333)
                .scaleBarEnabled(false)
                .annotationColor(new Color(12, 34, 56, 78))
                .labelMode(PanelConfig.LabelMode.CUSTOM)
                .customLabelTemplate("{group}-{subject}-{channel}")
                .marginPx(17)
                .groupLayoutRows(Arrays.asList(Arrays.asList("Control")))
                .build();
        FPBParameters original = FPBParameters.builder(input)
                .zMode("first")
                .quickGrid(true)
                .panelConfig(layout)
                .build();

        String macro = FPBMacroOptions.fromParameters(original).toMacroOptions();
        FPBParameters replay = FPBMacroOptionsParser.parse(macro).toParameters();
        assertEquals(macro, FPBMacroOptions.fromParameters(replay).toMacroOptions());
        assertTrue(replay.quickGrid());
        assertEquals("first", replay.zMode());
        assertEquals(333, replay.panelConfig().cellSizePx());
        assertEquals(17, replay.panelConfig().marginPx());
        assertEquals(layout.customLabelTemplate(),
                replay.panelConfig().customLabelTemplate());
        assertEquals(layout.annotationColor(), replay.panelConfig().annotationColor());

        FPBResult result = FPB.run(replay);
        assertEquals(2, result.manifest().size());
        assertEquals(0, result.selection().size());
        assertEquals(new File(input, "Control_S1.tif").getAbsolutePath(),
                result.manifest().get(0).sourceFile().getAbsolutePath());
    }

    @Test
    public void svgOnlyExportReportsUncalibratedScaleBarOmissions()
            throws Exception {
        File input = temp.newFolder("svg-only-uncalibrated-input");
        File source = new File(input, "Control_S1.png");
        BufferedImage image = new BufferedImage(40, 24, BufferedImage.TYPE_BYTE_GRAY);
        assertTrue(ImageIO.write(image, "png", source));
        FPBResult planned = FPB.run(FPBParameters.builder(input)
                .channel(1, "Signal", ChannelColour.GREY, 0, 255)
                .pick("Control", "S1")
                .scaleBarUm(10.0)
                .build());
        File output = temp.newFolder("svg-only-uncalibrated-output");
        Step5Export.Settings settings = new Step5Export.Settings(output,
                "SvgOnly", 300, 1, false, false, true, false, true);

        Step5Export.ExportResult result = Step5Export.export(planned.toContext(),
                settings, FigureWriter.NEVER_CANCELLED, Step5Export.NONE);

        assertEquals(Arrays.asList(source.getAbsolutePath()),
                result.uncalibratedImages());
        assertTrue(result.summaryText().contains("Scale bars were not drawn"));
        String methods = new String(java.nio.file.Files.readAllBytes(
                supporting(result.figureDirectory(), "methods.txt").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(methods.contains("calibration was unavailable"));
    }

    @Test
    public void userCalibrationOverridesUncalibratedInputAndIsManifested()
            throws Exception {
        File input = temp.newFolder("user-calibrated-input");
        File source = new File(input, "Control_S1.png");
        BufferedImage image = new BufferedImage(40, 24, BufferedImage.TYPE_BYTE_GRAY);
        assertTrue(ImageIO.write(image, "png", source));
        FPBResult planned = FPB.run(FPBParameters.builder(input)
                .channel(1, "Signal", ChannelColour.GREY, 0, 255)
                .pick("Control", "S1")
                .calibration("Control_S1.png", 0.5, 0.75)
                .scaleBarUm(10.0)
                .build());
        File output = temp.newFolder("user-calibrated-output");
        Step5Export.ExportResult result = Step5Export.export(planned.toContext(),
                new Step5Export.Settings(output, "UserCalibration", 300, 1,
                        true, false, true, false, true),
                FigureWriter.NEVER_CANCELLED, Step5Export.NONE);

        assertTrue(result.uncalibratedImages().isEmpty());
        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(
                supporting(result.figureDirectory(), "manifest.csv"));
        try {
            String[] header = CsvSupport.parseRecord(reader.readRecord().text);
            String[] row = CsvSupport.parseRecord(reader.readRecord().text);
            assertEquals("0.5", row[column(header, "PixelWidthUm")]);
            assertEquals("0.75", row[column(header, "PixelHeightUm")]);
            assertEquals("user-entered", row[column(header, "CalibrationSource")]);
            assertEquals("Control_S1.png", row[column(header, "SourceImageId")]);
        } finally {
            reader.close();
        }
    }

    @Test
    public void exportReportsRequestedScaleBarsThatDoNotFit()
            throws Exception {
        File input = temp.newFolder("overlong-scale-bar-input");
        File source = new File(input, "Control_S1.tif");
        saveStack(source, 40, 24, new int[] { 1000 });
        FPBResult planned = FPB.run(FPBParameters.builder(input)
                .channel(1, "Signal", ChannelColour.GREY, 0, 2000)
                .pick("Control", "S1")
                .scaleBarUm(10000.0)
                .build());
        File output = temp.newFolder("overlong-scale-bar-output");
        Step5Export.Settings settings = new Step5Export.Settings(output,
                "Overlong", 300, 1, false, false, true, false, true);

        Step5Export.ExportResult result = Step5Export.export(planned.toContext(),
                settings, FigureWriter.NEVER_CANCELLED, Step5Export.NONE);

        assertEquals(Arrays.asList(source.getAbsolutePath()),
                result.scaleBarsThatDidNotFit());
        assertTrue(result.summaryText().contains("did not fit"));
        String methods = new String(java.nio.file.Files.readAllBytes(
                supporting(result.figureDirectory(), "methods.txt").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(methods.contains("did not fit the rendered output"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void publicExportRejectsZeroAssembledFigureFormats() throws Exception {
        Step5Export.export(new fpb.ui.FPBWizard.Context(),
                new Step5Export.Settings(temp.getRoot(), "None", 300, 1,
                        false, false, false, true, true),
                FigureWriter.NEVER_CANCELLED, Step5Export.NONE);
    }

    private static File findNamed(File[] files, String part) {
        for (File file : files) if (file.getName().contains(part)) return file;
        throw new AssertionError("No panel file contained " + part);
    }

    private static File findNamedEnding(File[] files, String part,
            String extension) {
        for (File file : files) {
            if (file.getName().contains(part)
                    && file.getName().endsWith(extension)) return file;
        }
        throw new AssertionError("No panel file contained " + part
                + " and ended with " + extension);
    }

    private static File supporting(File figureDirectory, String name) {
        return new File(new File(figureDirectory, FigureWriter.SUPPORTING_DIR),
                name);
    }

    private static List<byte[]> embeddedPngBytes(String svg) {
        List<byte[]> images = new ArrayList<byte[]>();
        String prefix = "data:image/png;base64,";
        int from = 0;
        while (svg != null) {
            int start = svg.indexOf(prefix, from);
            if (start < 0) break;
            start += prefix.length();
            int end = svg.indexOf('"', start);
            if (end < 0) throw new AssertionError("Unterminated SVG image data");
            images.add(Base64.getDecoder().decode(svg.substring(start, end)));
            from = end + 1;
        }
        return images;
    }

    private static int column(String[] header, String name) {
        for (int i = 0; i < header.length; i++) if (name.equals(header[i])) return i;
        throw new AssertionError("Missing column " + name);
    }

    private static boolean containsStagingDirectory(File output) {
        File[] children = output.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (child.getName().startsWith(".fpb-export-")) return true;
        }
        return false;
    }

    private static int temporaryPanelCount() {
        File folder = new File(System.getProperty("java.io.tmpdir"),
                "FigurePanelBuilder-export-preview");
        File[] files = folder.listFiles();
        return files == null ? 0 : files.length;
    }

    private static void assertUniform(BufferedImage image) {
        int expected = image.getRGB(0, 0);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                assertEquals("individual panels must not inherit overview annotations",
                        expected, image.getRGB(x, y));
            }
        }
    }

    private static void saveStack(File file, int width, int height, int[] values)
            throws IOException {
        ImageStack stack = new ImageStack(width, height);
        for (int value : values) {
            short[] pixels = new short[width * height];
            Arrays.fill(pixels, (short) value);
            stack.addSlice(new ShortProcessor(width, height, pixels, null));
        }
        ImagePlus image = new ImagePlus(file.getName(), stack);
        image.setDimensions(1, values.length, 1);
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.5;
        calibration.pixelHeight = 0.5;
        calibration.setUnit("micron");
        image.setCalibration(calibration);
        boolean saved = values.length > 1
                ? new FileSaver(image).saveAsTiffStack(file.getAbsolutePath())
                : new FileSaver(image).saveAsTiff(file.getAbsolutePath());
        image.changes = false;
        image.close();
        if (!saved) throw new IOException("Could not save fixture " + file);
    }

    private static void saveChannels(File file, int width, int height,
            int[] values) throws IOException {
        ImageStack stack = new ImageStack(width, height);
        for (int value : values) {
            short[] pixels = new short[width * height];
            Arrays.fill(pixels, (short) value);
            stack.addSlice(new ShortProcessor(width, height, pixels, null));
        }
        ImagePlus image = new ImagePlus(file.getName(), stack);
        image.setDimensions(values.length, 1, 1);
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.5;
        calibration.pixelHeight = 0.5;
        calibration.setUnit("micron");
        image.setCalibration(calibration);
        if (!new FileSaver(image).saveAsTiffStack(file.getAbsolutePath())) {
            throw new IOException("Could not save test channels");
        }
    }

    private static void savePattern(File file, int width, int height, int[] values,
            double pixelWidth, double pixelHeight) throws IOException {
        if (values.length != width * height) {
            throw new IllegalArgumentException("Pattern dimensions differ");
        }
        short[] pixels = new short[values.length];
        for (int i = 0; i < values.length; i++) pixels[i] = (short) values[i];
        ImagePlus image = new ImagePlus(file.getName(),
                new ShortProcessor(width, height, pixels, null));
        Calibration calibration = new Calibration();
        calibration.pixelWidth = pixelWidth;
        calibration.pixelHeight = pixelHeight;
        calibration.setUnit("micron");
        image.setCalibration(calibration);
        if (!new FileSaver(image).saveAsTiff(file.getAbsolutePath())) {
            throw new IOException("Could not save patterned fixture");
        }
        image.changes = false;
        image.close();
    }
}
