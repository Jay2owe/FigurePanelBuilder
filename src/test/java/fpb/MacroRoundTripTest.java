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
import fpb.figure.ImageOrientation;
import fpb.figure.PanelConfig;
import fpb.figure.ScaleBar;
import fpb.render.ChannelColour;
import fpb.ui.FPBWizard;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MacroRoundTripTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void macroOptionsRoundTripThroughParameters() {
        String macro = "folder=" + bracket(basicFolder())
                + " recursive separator=_ group_token=1 subject_token=2 "
                + "channels=1,2,3 channel_names=[DAPI,GFAP,Iba1] "
                + "channel_luts=Blue,Green,Magenta z_mode=max statistic=brightest_1pct "
                + "range_DAPI_min=0 range_DAPI_max=5000 "
                + "range_GFAP_min=0 range_GFAP_max=5000 "
                + "range_Iba1_min=0 range_Iba1_max=5000 "
                + "pick_Control=S1 pick_DrugA=S1 pick_DrugB=S1 pick_Wash=S1 "
                + "scale_bar_um=50 scale_bar_corner=bottom_right dpi=300 "
                + "formats=png,tif,svg output=" + bracket(temp.getRoot())
                + " hide_display";

        FPBParameters parameters = FPBMacroOptionsParser.parse(macro).toParameters();
        String first = FPBMacroOptions.fromParameters(parameters).toMacroOptions();
        String second = FPBMacroOptions.fromParameters(
                FPBMacroOptionsParser.parse(first).toParameters()).toMacroOptions();

        assertEquals(first, second);
    }

    @Test
    public void allMetadataStrategiesAreExpressible() {
        String common = " folder=" + bracket(basicFolder())
                + " channels=1 channel_names=DAPI channel_luts=Blue "
                + "range_DAPI_min=0 range_DAPI_max=5000 pick_Control=S1";

        assertTrue(FPBMacroOptionsParser.parse("separator=_ group_token=1 subject_token=2"
                + common).toMacroOptions().contains("group_token=1"));
        assertTrue(FPBMacroOptionsParser.parse("group_from=subfolder" + common)
                .toMacroOptions().contains("group_from=subfolder"));
        assertTrue(FPBMacroOptionsParser.parse("metadata_csv=[labels.csv]" + common)
                .toMacroOptions().contains("metadata_csv_b64="));
        assertTrue(FPBMacroOptionsParser.parse("group_regex=[^(.+)_(.+).*] "
                + "group_capture=1 subject_capture=2" + common)
                .toMacroOptions().contains("group_regex_b64="));
    }

    @Test
    public void schemaTwoPreservesCollidingPunctuationAndCommaNames() {
        FPBParameters original = FPBParameters.builder(basicFolder())
                .channel(1, "DNA, primary", ChannelColour.BLUE, 10, 1000)
                .channel(2, "DNA primary", ChannelColour.GREEN, 20, 2000)
                .pick("A-B", "S,1")
                .pick("A B", "S\"2")
                .build();

        String macro = FPBMacroOptions.fromParameters(original).toMacroOptions();
        FPBParameters replay = FPBMacroOptionsParser.parse(macro).toParameters();

        assertTrue(macro.contains("macro_schema=2"));
        assertTrue(macro.contains("channel_names_b64="));
        assertTrue(macro.contains("picks_b64="));
        assertEquals("DNA, primary", replay.channels().get(0).name());
        assertEquals("DNA primary", replay.channels().get(1).name());
        assertEquals(10, replay.channels().get(0).range().min());
        assertEquals(2000, replay.channels().get(1).range().max());
        assertEquals("S,1", replay.picks().get("A-B"));
        assertEquals("S\"2", replay.picks().get("A B"));
    }

    @Test
    public void schemaTwoPreservesBracketPathsRegexesAndFigureNames()
            throws Exception {
        File input = temp.newFolder("input[set]");
        File output = temp.newFolder("output[set]");
        String regex = "([A-Za-z]+)_([0-9]+)\\.tif";
        FPBParameters original = FPBParameters.builder(input)
                .groupRegex(regex)
                .channel(1, "Signal", ChannelColour.BLUE, 0, 1000)
                .pick("Control", "1")
                .outputFolder(output)
                .figureName("Figure [A]")
                .build();

        String macro = FPBMacroOptions.fromParameters(original).toMacroOptions();
        FPBParameters replay = FPBMacroOptionsParser.parse(macro).toParameters();

        assertTrue(macro.contains("folder_b64="));
        assertTrue(macro.contains("group_regex_b64="));
        assertEquals(input.getAbsolutePath(), replay.folder().getAbsolutePath());
        assertEquals(output.getAbsolutePath(), replay.outputFolder().getAbsolutePath());
        assertEquals(regex, replay.groupRegex());
        assertEquals("Figure [A]", replay.figureName());
    }

    @Test
    public void macroRoundTripPreservesPerImageCalibration() {
        double pixelWidthUm = 0.123456789012345;
        double pixelHeightUm = 0.987654321098765;
        FPBParameters original = baseParameters(basicFolder())
                .calibration("nested\\Control_S1.tif", pixelWidthUm, pixelHeightUm)
                .build();

        String macro = FPBMacroOptions.fromParameters(original).toMacroOptions();
        FPBParameters replay = FPBMacroOptionsParser.parse(macro).toParameters();

        assertTrue(macro.contains("calibrations_b64="));
        assertEquals(Double.doubleToLongBits(pixelWidthUm),
                Double.doubleToLongBits(replay.calibrationOverrides()
                        .get("nested/Control_S1.tif").pixelWidthUm()));
        assertEquals(Double.doubleToLongBits(pixelHeightUm),
                Double.doubleToLongBits(replay.calibrationOverrides()
                        .get("nested/Control_S1.tif").pixelHeightUm()));
        assertEquals(macro, FPBMacroOptions.fromParameters(replay).toMacroOptions());
    }

    @Test
    public void allProjectImageExportOptionsSurviveMacroRoundTrip() {
        FPBParameters original = baseParameters(basicFolder())
                .writeAllProjectPng(true)
                .writeAllProjectTiffStacks(true)
                .build();

        String macro = FPBMacroOptions.fromParameters(original).toMacroOptions();
        FPBParameters replay = FPBMacroOptionsParser.parse(macro).toParameters();

        assertTrue(macro.contains("export_all_png"));
        assertTrue(macro.contains("export_all_tiff_stacks"));
        assertTrue(replay.writeAllProjectPng());
        assertTrue(replay.writeAllProjectTiffStacks());
        assertEquals(macro, FPBMacroOptions.fromParameters(replay).toMacroOptions());
    }

    @Test
    public void externalLabelSettingsSurviveMacroRoundTrip() {
        PanelConfig layout = PanelConfig.builder()
                .groupFontSizePx(25)
                .channelFontSizePx(27)
                .rowFontSizePx(21)
                .channelHeaderOrientation(PanelConfig.TextOrientation.ROTATE_LEFT)
                .rowLabelOrientation(PanelConfig.TextOrientation.ROTATE_RIGHT)
                .groupHeaderAlignment(PanelConfig.TextAlignment.RIGHT)
                .channelHeaderGapPx(18)
                .rowLabelGapPx(16)
                .groupHeaderVisible(false)
                .channelHeaderVisible(false)
                .rowLabelVisible(false)
                .annotationSnapEnabled(false)
                .externalLabelOverride(PanelConfig.ExternalLabelKind.GROUP,
                        "Control", "Vehicle")
                .externalLabelOverride(PanelConfig.ExternalLabelKind.COLUMN,
                        "DAPI", "Nuclei")
                .externalLabelOverride(PanelConfig.ExternalLabelKind.ROW,
                        "Control/S1", "Animal one")
                .imageOrientation("nested\\Control_S1.tif",
                        ImageOrientation.IDENTITY
                                .then(ImageOrientation.Action.ROTATE_RIGHT)
                                .then(ImageOrientation.Action.FLIP_HORIZONTAL))
                .build();
        FPBParameters original = FPBParameters.builder(basicFolder())
                .quickGrid(true)
                .panelConfig(layout)
                .build();

        String macro = FPBMacroOptions.fromParameters(original).toMacroOptions();
        PanelConfig replay = FPBMacroOptionsParser.parse(macro).toParameters()
                .panelConfig();

        assertEquals(25, replay.groupFontSizePx());
        assertEquals(27, replay.channelFontSizePx());
        assertEquals(21, replay.rowFontSizePx());
        assertEquals(PanelConfig.TextOrientation.ROTATE_LEFT,
                replay.channelHeaderOrientation());
        assertEquals(PanelConfig.TextOrientation.ROTATE_RIGHT,
                replay.rowLabelOrientation());
        assertEquals(PanelConfig.TextAlignment.RIGHT,
                replay.groupHeaderAlignment());
        assertEquals(18, replay.channelHeaderGapPx());
        assertEquals(16, replay.rowLabelGapPx());
        assertFalse(replay.groupHeaderVisible());
        assertFalse(replay.channelHeaderVisible());
        assertFalse(replay.rowLabelVisible());
        assertFalse(replay.annotationSnapEnabled());
        assertEquals("Vehicle", replay.externalLabelOverride(
                PanelConfig.ExternalLabelKind.GROUP, "Control"));
        assertEquals("Nuclei", replay.externalLabelOverride(
                PanelConfig.ExternalLabelKind.COLUMN, "DAPI"));
        assertEquals("Animal one", replay.externalLabelOverride(
                PanelConfig.ExternalLabelKind.ROW, "Control/S1"));
        assertEquals(layout.imageOrientation("nested/Control_S1.tif"),
                replay.imageOrientation("nested/Control_S1.tif"));
    }

    @Test
    public void exactSectionPickSurvivesMacroReplayAndExportsOnlyThatSection()
            throws Exception {
        File folder = new File("src/test/resources/fixtures/sections")
                .getAbsoluteFile();
        FPBParameters original = FPBParameters.builder(folder)
                .separator('_').groupToken(1).subjectToken(2).sectionToken(3)
                .channel(1, "DAPI", ChannelColour.BLUE, 0, 65535)
                .pickImage("Control", "Control_S1_sec2.tif")
                .build();

        String macro = FPBMacroOptions.fromParameters(original).toMacroOptions();
        FPBParameters replay = FPBMacroOptionsParser.parse(macro).toParameters();
        FPBResult result = FPB.run(replay);

        assertEquals("Control_S1_sec2.tif", replay.pickImages().get("Control"));
        assertEquals(2, result.manifest().size());
        assertEquals("sec2", result.manifest().get(0).section());
        assertEquals("sec2", result.manifest().get(1).section());
    }

    @Test
    public void scaleBarLengthRoundTripsBitExactlyAndKeepsPixelGeometry()
            throws Exception {
        double scaleBarUm = 0.0004;
        double pixelSizeUm = 0.00004;
        FPBParameters original = baseParameters(basicFolder())
                .scaleBarUm(scaleBarUm)
                .build();

        String macro = FPBMacroOptions.fromParameters(original).toMacroOptions();
        FPBParameters replay = FPBMacroOptionsParser.parse(macro).toParameters();

        assertEquals(Double.doubleToLongBits(scaleBarUm),
                Double.doubleToLongBits(replay.scaleBarUm()));
        CalibrationCheck.Result calibration = CalibrationCheck.userEntered(
                pixelSizeUm, pixelSizeUm);
        int before = ScaleBar.lengthPixels(calibration,
                100, 100, 100, 100, original.scaleBarUm());
        int after = ScaleBar.lengthPixels(calibration,
                100, 100, 100, 100, replay.scaleBarUm());
        assertEquals(10, before);
        assertEquals(before, after);
        assertEquals(Double.doubleToLongBits(scaleBarUm),
                Double.doubleToLongBits(FPB.run(replay).panelConfig()
                        .scaleBarLengthUm()));
    }

    @Test
    public void guiContextMacroPreservesScaleBarLengthBitExactly() {
        double scaleBarUm = 0.123456789012345;
        FPBWizard.Context context = new FPBWizard.Context();
        context.folder = basicFolder();
        context.quickGridRequested = true;
        context.panelConfig = PanelConfig.builder()
                .scaleBarLengthUm(scaleBarUm)
                .build();

        FPBParameters replay = FPBMacroOptionsParser.parse(
                FPBMacroOptions.fromContext(context, null).toMacroOptions())
                .toParameters();

        assertEquals(Double.doubleToLongBits(scaleBarUm),
                Double.doubleToLongBits(replay.scaleBarUm()));
        assertEquals(Double.doubleToLongBits(scaleBarUm),
                Double.doubleToLongBits(replay.panelConfig().scaleBarLengthUm()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void schemaTwoRejectsRawChannelCardinalityMismatch() {
        FPBMacroOptionsParser.parse("macro_schema=2 folder=" + bracket(basicFolder())
                + " channels=1 channel_names=DAPI");
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingChannelRangeFailsClosed() {
        FPBMacroOptionsParser.parse("folder=" + bracket(basicFolder())
                + " channels=1 channel_names=DAPI channel_luts=Blue "
                + "pick_Control=S1").toParameters();
    }

    @Test(expected = IllegalArgumentException.class)
    public void macroRejectsUnsupportedStatistic() {
        FPBMacroOptionsParser.parse("folder=" + bracket(basicFolder())
                + " channels=1 channel_names=DAPI channel_luts=Blue "
                + "range_DAPI_min=0 range_DAPI_max=5000 "
                + "statistic=median pick_Control=S1");
    }

    @Test(expected = IllegalArgumentException.class)
    public void publicApiRejectsUnsupportedStatisticBeforeLoading() throws Exception {
        FPB.run(baseParameters(basicFolder()).statistic("median").build());
    }

    @Test(expected = IllegalArgumentException.class)
    public void publicApiRejectsZeroAssembledFigureFormats() throws Exception {
        FPB.run(baseParameters(basicFolder())
                .writePng(false).writeTiff(false).writeSvg(false).build());
    }

    @Test
    public void publicApiReservesSyntheticMergeNameCaseInsensitively()
            throws Exception {
        try {
            FPB.run(FPBParameters.builder(basicFolder())
                    .channel(1, "mErGe", ChannelColour.GREY, 0, 5000)
                    .build());
            assertTrue("A real channel must not collide with synthetic Merge", false);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("reserved"));
        }
    }

    @Test
    public void publicApiRejectsUnknownCalibrationImageId() throws Exception {
        try {
            FPB.run(baseParameters(basicFolder())
                    .calibration("not-present.tif", 0.5, 0.5)
                    .build());
            assertTrue("Unknown calibration keys must fail closed", false);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("SourceImageId was not found"));
        }
    }

    @Test
    public void publicApiRejectsInvalidScaleBarLengthsBeforeLoading()
            throws Exception {
        double[] invalid = new double[] {
                0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY
        };
        for (double value : invalid) {
            try {
                FPB.run(baseParameters(basicFolder()).scaleBarUm(value).build());
                assertTrue("Invalid scale-bar length must fail closed", false);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("finite and positive"));
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void macroRejectsNonPositiveScaleBarLength() {
        FPBMacroOptionsParser.parse("folder=" + bracket(basicFolder())
                + " quick_grid scale_bar_um=0");
    }

    @Test
    public void macroBooleanTokensAreExplicitAndTyposAreRejected() {
        String common = "folder=" + bracket(basicFolder()) + " quick_grid ";
        assertTrue(FPBMacroOptionsParser.parse(common + "recursive=yes")
                .toParameters().recursive());
        assertTrue(FPBMacroOptionsParser.parse(common + "recursive=1")
                .toParameters().recursive());
        assertFalse(FPBMacroOptionsParser.parse(common + "recursive=no")
                .toParameters().recursive());
        assertFalse(FPBMacroOptionsParser.parse(common + "recursive=0")
                .toParameters().recursive());
        try {
            FPBMacroOptionsParser.parse(common + "recursive=treu");
            assertTrue("Boolean typos must fail closed", false);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("recursive"));
            assertTrue(expected.getMessage().contains("true/false"));
        }
    }

    @Test
    public void publicApiRejectsIncompleteCustomStatisticBeforeRanking()
            throws Exception {
        File csv = temp.newFile("partial-statistic.csv");
        PrintWriter out = new PrintWriter(csv);
        try {
            out.println("File,Mean");
            out.println("Control_S1.tif,12.5");
        } finally {
            out.close();
        }
        try {
            FPB.run(baseParameters(basicFolder())
                    .statisticCsv(csv).statisticColumn("Mean").build());
            assertTrue("Incomplete custom statistics must fail before ranking", false);
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("match every input exactly once"));
        }
    }

    @Test
    public void publicApiRunOpensNoOutputFiles() throws Exception {
        FPBParameters parameters = baseParameters(basicFolder())
                .outputFolder(null)
                .build();

        FPBResult result = FPB.run(parameters);

        assertEquals(24, result.metadataTable().fileCount());
        assertEquals("S1", result.selectedSubjects().get("Control"));
        assertFalse(new File(temp.getRoot(), "Figure Panels").exists());
    }

    @Test
    public void publicApiRanksOnlyConfiguredChannelsWithAccurateProvenance()
            throws Exception {
        FPBResult result = FPB.run(FPBParameters.builder(basicFolder())
                .separator('_').groupToken(1).subjectToken(2)
                .channel(3, "Iba1 only", ChannelColour.MAGENTA, 0, 5000)
                .pick("Control", "S1").pick("DrugA", "S1")
                .pick("DrugB", "S1").pick("Wash", "S1")
                .zMode("first")
                .build());

        assertEquals(24, result.selection().size());
        assertEquals(2, result.selection().get(0).channelIndex());
        assertEquals("Iba1 only", result.selection().get(0).channelName());
        assertEquals(fpb.stats.Statistic.BRIGHTEST_ONE_PERCENT_FIRST_SLICE_NAME,
                result.chooserData().subjectStats().statisticName());
    }

    @Test
    public void batchRunnerWritesSeparateOutputTrees() throws Exception {
        File runA = subsetFolder("runA");
        File runB = subsetFolder("runB");
        File out = temp.newFolder("out");
        FPBParameters template = baseParameters(runA)
                .writeTiff(false)
                .writeSvg(false)
                .writeIndividualPanels(false)
                .writeRecords(false)
                .build();

        FPBBatchResult result = FPBBatchRunner.run(FPBBatchParameters
                .builder(Arrays.asList(runA, runB), template)
                .outputRoot(out)
                .build());

        assertEquals(2, result.processedFolders());
        assertEquals(0, result.errorFolders());
        assertTrue(new File(out, "runA/Figure Panels/runA/figure.png").isFile());
        assertTrue(new File(out, "runB/Figure Panels/runB/figure.png").isFile());
    }

    private FPBParameters.Builder baseParameters(File folder) {
        return FPBParameters.builder(folder)
                .separator('_')
                .groupToken(1)
                .subjectToken(2)
                .channel(1, "DAPI", ChannelColour.BLUE, 0, 5000)
                .pick("Control", "S1")
                .pick("DrugA", "S1")
                .pick("DrugB", "S1")
                .pick("Wash", "S1")
                .scaleBarUm(50.0)
                .dpi(300);
    }

    private File subsetFolder(String name) throws Exception {
        File folder = temp.newFolder(name);
        copy("Control_S1.tif", folder);
        copy("DrugA_S1.tif", folder);
        copy("DrugB_S1.tif", folder);
        copy("Wash_S1.tif", folder);
        return folder;
    }

    private void copy(String name, File folder) throws Exception {
        Files.copy(new File(basicFolder(), name).toPath(),
                new File(folder, name).toPath());
    }

    private static String bracket(File file) {
        return "[" + file.getAbsolutePath().replace('\\', '/') + "]";
    }

    private static File basicFolder() {
        return new File("src/test/resources/fixtures/basic").getAbsoluteFile();
    }
}
