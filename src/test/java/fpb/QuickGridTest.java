/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb;

import fpb.render.DisplayRange;
import fpb.render.FPBRenderer;
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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

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
        assertTrue(new File(dir, "figure.png").isFile());
        assertTrue(new File(dir, "figure.tif").isFile());
        assertTrue(new File(dir, "figure.svg").isFile());
        assertTrue(new File(dir, "manifest.csv").isFile());
        assertTrue(new File(dir, "selection.csv").isFile());
        assertTrue(new File(dir, "methods.txt").isFile());
        assertTrue(new File(dir, "README.txt").isFile());

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(
                new File(dir, "manifest.csv"));
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
