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
import fpb.figure.PanelConfig;
import fpb.figure.ScaleBar;
import fpb.render.ChannelColour;
import fpb.render.DisplayRange;
import fpb.render.FPBRenderer;
import fpb.record.OutputTree;
import fpb.ui.FPBWizard;
import fpb.ui.Step5Export;
import fpb.util.CsvSupport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class QuickGridTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void quickGridUsesOneSharedRangePerChannel() throws Exception {
        QuickGrid.Result result = QuickGrid.run(basicFolder(), false);

        assertEquals(24, result.files().size());
        assertEquals(3, result.ranges().size());
        for (FPBRenderer.ChannelRequest request : result.channelRequests()) {
            DisplayRange range = result.ranges().get(
                    Integer.valueOf(request.channelIndex()));
            assertEquals(range.min(), request.range().min());
            assertEquals(range.max(), request.range().max());
        }

        FPBRenderer renderer = new FPBRenderer();
        BufferedImage first = renderer.renderPanel(result.chooserData().planes(),
                result.chooserData().histograms(), 0, result.channelRequests(),
                48, 48).channelImages().get(0);
        BufferedImage last = renderer.renderPanel(result.chooserData().planes(),
                result.chooserData().histograms(), 23, result.channelRequests(),
                48, 48).channelImages().get(0);

        assertNotEquals(first.getRGB(0, 0), last.getRGB(0, 0));
    }

    @Test
    public void quickGridExportManifestRecordsExpressRoute() throws Exception {
        QuickGrid.Result result = QuickGrid.run(basicFolder(), false);
        FPBWizard.Context context = contextFor(result);

        Step5Export.ExportResult export = Step5Export.export(context,
                new Step5Export.Settings(temp.getRoot(), "QuickGrid",
                        300, 1, true, true, true, true, true),
                null, Step5Export.NONE);

        File dir = export.figureDirectory();
        File supporting = new File(dir, OutputTree.SUPPORTING_DIR);
        assertTrue(new File(dir, "figure.png").isFile());
        assertTrue(new File(dir, "figure.tif").isFile());
        assertTrue(new File(dir, "figure.svg").isFile());
        assertTrue(new File(supporting, "manifest.csv").isFile());
        assertTrue(new File(supporting, "selection.csv").isFile());
        assertTrue(new File(supporting, "methods.txt").isFile());
        assertTrue(new File(supporting, "README.txt").isFile());
        assertFalse(new File(dir, "group_quantification.csv").exists());
        assertFalse(new File(dir, "group_quantification.png").exists());
        assertFalse(new File(supporting, "group_quantification.csv").exists());
        assertFalse(new File(supporting, "group_quantification.png").exists());
        String readme = new String(java.nio.file.Files.readAllBytes(
                new File(supporting, "README.txt").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(readme.contains(
                "Quick Grid does not create group-quantification files."));

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(
                new File(supporting, "manifest.csv"));
        try {
            String[] header = CsvSupport.parseRecord(reader.readRecord().text);
            String[] row = CsvSupport.parseRecord(reader.readRecord().text);
            Map<String, Integer> columns = columns(header);
            assertEquals(QuickGrid.RANGE_SOURCE,
                    row[columns.get("RangeSource").intValue()]);
            assertEquals(QuickGrid.SELECTION_METHOD,
                    row[columns.get("SelectionMethod").intValue()]);
            assertEquals(QuickGrid.GROUPING,
                    row[columns.get("Grouping").intValue()]);
        } finally {
            reader.close();
        }
    }

    @Test
    public void publicQuickGridRouteExportsEveryFileWithoutGuidedEvidence()
            throws Exception {
        FPBParameters parameters = FPBParameters.builder(basicFolder())
                .quickGrid(true)
                .build();

        FPBResult result = FPB.run(parameters);

        assertEquals(24, result.selectedSubjects().size());
        assertEquals(96, result.manifest().size());
        assertTrue(result.selection().isEmpty());
        assertTrue(result.selectedSubjects().containsKey("001 Control_S1"));
        assertTrue(result.selectedSubjects().containsKey("024 Wash_S6"));
        assertTrue(result.toContext().quickGridRequested);
    }

    @Test
    public void macroQuickGridRouteDoesNotRequireGuidedChannelsOrPicks()
            throws Exception {
        FPBParameters original = FPBParameters.builder(basicFolder())
                .quickGrid(true)
                .build();
        String macro = FPBMacroOptions.fromParameters(original).toMacroOptions();

        FPBParameters replay = FPBMacroOptionsParser.parse(macro).toParameters();
        FPBResult result = FPB.run(replay);

        assertFalse(macro.contains("channels="));
        assertFalse(macro.contains("channel_names_b64="));
        assertFalse(macro.contains("channel_luts="));
        assertFalse(macro.contains("range_1_"));
        assertTrue(replay.quickGrid());
        assertEquals(24, result.selectedSubjects().size());
        assertTrue(result.selection().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void quickGridRejectsExplicitGuidedChannelSettings() throws Exception {
        FPB.run(FPBParameters.builder(basicFolder())
                .quickGrid(true)
                .channel(1, "Signal", ChannelColour.GREEN, 123, 456)
                .build());
    }

    @Test(expected = IllegalArgumentException.class)
    public void macroQuickGridRejectsExplicitGuidedChannelSettings() {
        FPBMacroOptionsParser.parse("folder_b64=" + MacroDataCodec.encodeString(
                basicFolder().getAbsolutePath())
                + " quick_grid channels=1 channel_names=Signal "
                + "channel_luts=Green range_Signal_min=123 "
                + "range_Signal_max=456");
    }

    @Test
    public void legacyMacroQuickGridRejectsEveryOrphanedChannelOption() {
        String common = "folder_b64=" + MacroDataCodec.encodeString(
                basicFolder().getAbsolutePath()) + " quick_grid ";
        String[] orphaned = new String[] {
                "channels=1",
                "channel_names=Signal",
                "channel_names_b64=" + MacroDataCodec.encodeStrings(
                        java.util.Arrays.asList("Signal")),
                "channel_luts=Green"
        };
        for (String option : orphaned) {
            try {
                FPBMacroOptionsParser.parse(common + option);
                fail("Expected Quick Grid to reject " + option);
            } catch (IllegalArgumentException expected) {
                // Rejection may come from raw codec/cardinality validation first.
            }
        }
    }

    @Test
    public void legacyMacroQuickGridAllowsCompleteEmptyChannelPlaceholders() {
        FPBParameters parameters = FPBMacroOptionsParser.parse("folder_b64="
                + MacroDataCodec.encodeString(basicFolder().getAbsolutePath())
                + " quick_grid channels= channel_names= channel_luts=")
                .toParameters();

        assertTrue(parameters.quickGrid());
        assertTrue(parameters.channels().isEmpty());
    }

    @Test
    public void recordedQuickGridMacroOmitsDerivedChannelSettings()
            throws Exception {
        QuickGrid.Result quick = QuickGrid.run(basicFolder(), false);
        String macro = FPBMacroOptions.fromContext(contextFor(quick), null)
                .toMacroOptions();

        assertFalse(macro.contains("channels="));
        assertFalse(macro.contains("channel_names_b64="));
        assertFalse(macro.contains("channel_luts="));
        assertFalse(macro.contains("range_1_"));
        assertTrue(FPBMacroOptionsParser.parse(macro).toParameters().quickGrid());
    }

    @Test
    public void apiAndMacroQuickGridApplyTopLevelScaleBarSettings()
            throws Exception {
        double scaleBarUm = 0.0004;
        double pixelSizeUm = 0.00004;
        FPBParameters original = FPBParameters.builder(basicFolder())
                .quickGrid(true)
                .scaleBarUm(scaleBarUm)
                .scaleBarCorner(PanelConfig.Position.TOP_LEFT)
                .build();

        FPBResult direct = FPB.run(original);
        FPBParameters replay = FPBMacroOptionsParser.parse(
                FPBMacroOptions.fromParameters(original).toMacroOptions())
                .toParameters();
        FPBResult replayed = FPB.run(replay);

        assertEquals(Double.doubleToLongBits(scaleBarUm),
                Double.doubleToLongBits(direct.panelConfig().scaleBarLengthUm()));
        assertEquals(Double.doubleToLongBits(scaleBarUm),
                Double.doubleToLongBits(replayed.panelConfig().scaleBarLengthUm()));
        assertEquals(PanelConfig.Position.TOP_LEFT,
                direct.panelConfig().scaleBarPosition());
        assertEquals(PanelConfig.Position.TOP_LEFT,
                replayed.panelConfig().scaleBarPosition());
        CalibrationCheck.Result calibration = CalibrationCheck.userEntered(
                pixelSizeUm, pixelSizeUm);
        int directPixels = ScaleBar.lengthPixels(calibration,
                100, 100, 100, 100, direct.panelConfig().scaleBarLengthUm());
        int replayedPixels = ScaleBar.lengthPixels(calibration,
                100, 100, 100, 100, replayed.panelConfig().scaleBarLengthUm());
        assertEquals(10, directPixels);
        assertEquals(directPixels, replayedPixels);
    }

    private static FPBWizard.Context contextFor(QuickGrid.Result result) {
        FPBWizard.Context context = new FPBWizard.Context();
        context.folder = basicFolder();
        context.quickGridRequested = true;
        context.metadataTable = result.table();
        context.chooserData = result.chooserData();
        context.selectedRowsByGroup = result.selectedRowsByGroup();
        context.layoutChannelRequests =
                new java.util.ArrayList<FPBRenderer.ChannelRequest>(
                        result.channelRequests());
        context.panelConfig = result.panelConfig();
        context.groupLayoutRows = result.panelConfig().groupLayoutRows();
        return context;
    }

    private static Map<String, Integer> columns(String[] header) {
        java.util.LinkedHashMap<String, Integer> map =
                new java.util.LinkedHashMap<String, Integer>();
        for (int i = 0; i < header.length; i++) {
            map.put(header[i], Integer.valueOf(i));
        }
        return map;
    }

    private static File basicFolder() {
        return new File("src/test/resources/fixtures/basic").getAbsoluteFile();
    }
}
