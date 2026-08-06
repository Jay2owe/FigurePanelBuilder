/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.layout;

import fpb.figure.CalibrationCheck;
import fpb.io.ImageLoader;
import fpb.io.ProgressCallback;
import fpb.meta.MetadataRow;
import fpb.meta.MetadataTable;
import fpb.meta.TokenStrategy;
import fpb.render.ChannelColour;
import fpb.render.DisplayRange;
import fpb.render.FPBRenderer;
import fpb.stats.GroupStats;
import fpb.stats.SelectionRecord;
import fpb.stats.Statistic;
import fpb.stats.SubjectAggregator;
import fpb.stats.Suggestion;
import fpb.ui.FPBWizard;
import fpb.ui.ImageOrientationControls;
import fpb.ui.chooser.ChannelRail;
import fpb.ui.chooser.RowImage;
import fpb.ui.chooser.Step3Chooser;
import fpb.figure.PanelConfig;
import fpb.figure.PanelRecord;
import fpb.figure.PanelWriter;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LayoutTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void fractionalPositionsSurviveResize() {
        Rectangle preview = new Rectangle(10, 20, 200, 100);
        Rectangle export = new Rectangle(0, 0, 800, 400);

        double[] fraction = AnnotationEditor.fractionForPoint(
                new Point(60, 70), preview);
        Point exportPoint = AnnotationEditor.pointForFraction(
                fraction[0], fraction[1], export);

        assertEquals(0.25, fraction[0], 0.0001);
        assertEquals(0.50, fraction[1], 0.0001);
        assertEquals(200, exportPoint.x);
        assertEquals(200, exportPoint.y);
    }

    @Test
    public void annotationCornerSnappingCanBeDisabled() {
        PanelConfig free = PanelConfig.builder()
                .annotationSnapEnabled(false)
                .labelFracX(0.03)
                .labelFracY(0.04)
                .scaleBarFracX(0.97)
                .scaleBarFracY(0.97)
                .build();

        PanelConfig freeLabel = AnnotationEditor.maybeSnapLabel(free);
        PanelConfig freeBar = AnnotationEditor.maybeSnapBar(free);
        assertEquals(0.03, freeLabel.labelFracX(), 0.0001);
        assertEquals(0.04, freeLabel.labelFracY(), 0.0001);
        assertEquals(0.97, freeBar.scaleBarFracX(), 0.0001);
        assertEquals(0.97, freeBar.scaleBarFracY(), 0.0001);

        PanelConfig snapping = free.toBuilder()
                .annotationSnapEnabled(true)
                .build();
        PanelConfig snappedLabel = AnnotationEditor.maybeSnapLabel(snapping);
        PanelConfig snappedBar = AnnotationEditor.maybeSnapBar(snapping);
        assertEquals(0.0, snappedLabel.labelFracX(), 0.0001);
        assertEquals(0.0, snappedLabel.labelFracY(), 0.0001);
        assertEquals(1.0, snappedBar.scaleBarFracX(), 0.0001);
        assertEquals(1.0, snappedBar.scaleBarFracY(), 0.0001);
    }

    @Test
    public void layoutPreviewUsesProgressiveBicubicDownsampling() {
        BufferedImage checker = new BufferedImage(16, 16,
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < checker.getHeight(); y++) {
            for (int x = 0; x < checker.getWidth(); x++) {
                checker.setRGB(x, y, ((x + y) & 1) == 0
                        ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
            }
        }

        BufferedImage reduced = Step4Layout.resizeHighQuality(checker, 2, 2);

        assertEquals(2, reduced.getWidth());
        assertEquals(2, reduced.getHeight());
        int grey = new Color(reduced.getRGB(0, 0), true).getRed();
        assertTrue("high-quality reduction should blend sub-pixel detail",
                grey > 40 && grey < 215);
    }

    @Test
    public void controlWheelZoomIsContinuousAndClamped() {
        assertEquals(1.12, Step4Layout.wheelZoom(1.0, -1.0), 0.0001);
        assertEquals(1.0, Step4Layout.wheelZoom(1.12, 1.0), 0.01);
        assertEquals(4.0, Step4Layout.wheelZoom(4.0, -10.0), 0.0);
        assertEquals(0.25, Step4Layout.wheelZoom(0.25, 10.0), 0.0);
    }

    @Test
    public void orientationControlsStayFullyVisibleAtCanvasAndViewportEdges() {
        Rectangle canvas = new Rectangle(0, 0, 640, 480);
        Rectangle viewport = new Rectangle(220, 130, 180, 150);
        int size = ImageOrientationControls.PANEL_SIZE;
        List<Rectangle> edgeImages = Arrays.asList(
                new Rectangle(220, 125, 20, 20),
                new Rectangle(390, 135, 18, 18),
                new Rectangle(225, 270, 22, 22),
                new Rectangle(390, 270, 16, 16));

        for (Rectangle image : edgeImages) {
            Rectangle controls = Step4Layout.visibleOrientationControlBounds(
                    image, canvas, viewport, size, 3);
            assertEquals(size, controls.width);
            assertEquals(size, controls.height);
            assertTrue("orientation panel must remain inside the canvas",
                    canvas.contains(controls));
            assertTrue("all four buttons must remain inside the viewport",
                    viewport.contains(controls));
        }
    }

    @Test
    public void rowOrderPanelBuildsRowsFromAssignments() {
        List<String> groups = Arrays.asList("Control", "DrugA", "DrugB");
        RowOrderPanel panel = new RowOrderPanel(groups,
                RowOrderPanel.allInOneRow(groups));

        panel.oneGroupPerRowForTest();

        assertEquals(Arrays.asList(
                Collections.singletonList("Control"),
                Collections.singletonList("DrugA"),
                Collections.singletonList("DrugB")), panel.rows());
    }

    @Test
    public void groupRowsAffectRenderedFigureShape() throws Exception {
        List<PanelRecord> records = Arrays.asList(
                record("Control"),
                record("DrugA"),
                record("DrugB"),
                record("Wash"));
        PanelConfig oneRow = PanelConfig.builder()
                .cellSizePx(60)
                .channelOrder(Collections.singletonList("Merge"))
                .groupLayoutRows(Collections.singletonList(Arrays.asList(
                        "Control", "DrugA", "DrugB", "Wash")))
                .build();
        PanelConfig onePerRow = oneRow.toBuilder()
                .groupLayoutRows(Arrays.asList(
                        Collections.singletonList("Control"),
                        Collections.singletonList("DrugA"),
                        Collections.singletonList("DrugB"),
                        Collections.singletonList("Wash")))
                .build();

        BufferedImage wide = PanelWriter.renderOverviewPanel(records, oneRow);
        BufferedImage tall = PanelWriter.renderOverviewPanel(records, onePerRow);

        assertTrue(wide.getWidth() > tall.getWidth());
        assertTrue(tall.getHeight() > wide.getHeight());
    }

    @Test
    public void externalLabelEditorControlsSizeOrientationAndImageDistance()
            throws Exception {
        PanelConfig base = PanelConfig.builder()
                .cellSizePx(80)
                .channelOrder(Collections.singletonList("Merge"))
                .groupHeaderVisible(false)
                .build();
        PanelConfig edited = ExternalLabelEditor.applySettings(base, 24, 28,
                PanelConfig.TextOrientation.ROTATE_LEFT, 19, 22,
                PanelConfig.TextOrientation.ROTATE_RIGHT, 17);
        edited = ExternalLabelEditor.applySettings(edited, 24, true,
                PanelConfig.TextAlignment.CENTER, 28,
                PanelConfig.TextOrientation.ROTATE_LEFT, 19, true, 22,
                PanelConfig.TextOrientation.ROTATE_RIGHT, 17, true);

        assertEquals(24, edited.groupFontSizePx());
        assertEquals(PanelConfig.TextAlignment.CENTER,
                edited.groupHeaderAlignment());
        assertEquals(28, edited.channelFontSizePx());
        assertEquals(22, edited.rowFontSizePx());
        assertEquals(19, edited.channelHeaderGapPx());
        assertEquals(17, edited.rowLabelGapPx());
        assertEquals(PanelConfig.TextOrientation.ROTATE_LEFT,
                edited.channelHeaderOrientation());
        assertEquals(PanelConfig.TextOrientation.ROTATE_RIGHT,
                edited.rowLabelOrientation());

        PanelConfig close = base.toBuilder()
                .channelHeaderGapPx(0).rowLabelGapPx(0).build();
        PanelConfig distant = close.toBuilder()
                .channelHeaderGapPx(30).rowLabelGapPx(26).build();
        List<PanelRecord> records = Collections.singletonList(record("Control"));
        BufferedImage closeImage = PanelWriter.renderOverviewPanel(
                records, close);
        BufferedImage distantImage = PanelWriter.renderOverviewPanel(
                records, distant);
        assertEquals(closeImage.getWidth() + 26, distantImage.getWidth());
        assertEquals(closeImage.getHeight() + 30, distantImage.getHeight());
    }

    @Test
    public void externalLabelEditorCanRemoveEveryExternalLabel() throws Exception {
        PanelConfig visible = PanelConfig.builder()
                .cellSizePx(80)
                .channelOrder(Collections.singletonList("Merge"))
                .build();
        PanelConfig hidden = ExternalLabelEditor.applySettings(visible, 24,
                false, 28, PanelConfig.TextOrientation.HORIZONTAL, 4, false,
                22, PanelConfig.TextOrientation.HORIZONTAL, 6, false);

        assertFalse(hidden.groupHeaderVisible());
        assertFalse(hidden.channelHeaderVisible());
        assertFalse(hidden.rowLabelVisible());

        List<PanelRecord> records = Collections.singletonList(record("Control"));
        BufferedImage withLabels = PanelWriter.renderOverviewPanel(records, visible);
        BufferedImage withoutLabels = PanelWriter.renderOverviewPanel(records, hidden);
        assertTrue(withoutLabels.getWidth() < withLabels.getWidth());
        assertTrue(withoutLabels.getHeight() < withLabels.getHeight());
    }

    @Test
    public void fullFigureExternalLabelsCanBeSelectedAndRenamed() throws Exception {
        PanelRecord record = record("Control");
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(80)
                .channelOrder(Collections.singletonList("Merge"))
                .externalLabelOverride(PanelConfig.ExternalLabelKind.GROUP,
                        "Control", "Vehicle")
                .externalLabelOverride(PanelConfig.ExternalLabelKind.COLUMN,
                        "Merge", "Composite")
                .externalLabelOverride(PanelConfig.ExternalLabelKind.ROW,
                        record.imageKey(), "Animal 01")
                .build();
        PanelWriter.WriteReport report = new PanelWriter.WriteReport();

        PanelWriter.renderOverviewPanel(Collections.singletonList(record),
                config, report);

        assertEquals("Vehicle", config.externalLabelText(
                PanelConfig.ExternalLabelKind.GROUP, "Control", "Control"));
        assertEquals("Composite", config.externalLabelText(
                PanelConfig.ExternalLabelKind.COLUMN, "Merge", "Merge"));
        assertEquals("Animal 01", config.externalLabelText(
                PanelConfig.ExternalLabelKind.ROW, record.imageKey(),
                record.imageLabel()));
        assertEquals(3, report.externalLabels().size());
        for (PanelWriter.ExternalLabelBox label : report.externalLabels()) {
            assertTrue(label.bounds().width > 0);
            assertTrue(label.bounds().height > 0);
        }
        assertEquals(1, report.imageBoxes().size());
        assertEquals(record.imageKey(), report.imageBoxes().get(0).imageKey());
        assertTrue(report.imageBoxes().get(0).bounds().width > 0);
        assertTrue(report.imageBoxes().get(0).bounds().height > 0);

        Rectangle fitted = ExternalLabelEditor.fitRect(400, 200, 800, 600, 20);
        assertEquals(new Rectangle(20, 110, 760, 380), fitted);
    }

    @Test
    public void mainLayoutCanRenameAndAlignGroupLabels() throws Exception {
        PanelRecord record = record("Control");
        PanelConfig left = Step4Layout.withGroupLabelText(
                PanelConfig.builder()
                        .cellSizePx(100)
                        .channelOrder(Collections.singletonList("Merge"))
                        .groupHeaderAlignment(PanelConfig.TextAlignment.LEFT)
                        .build(),
                "Control", "Vehicle");
        PanelConfig middle = left.toBuilder()
                .groupHeaderAlignment(PanelConfig.TextAlignment.CENTER).build();
        PanelConfig right = left.toBuilder()
                .groupHeaderAlignment(PanelConfig.TextAlignment.RIGHT).build();

        assertEquals("Vehicle", left.externalLabelText(
                PanelConfig.ExternalLabelKind.GROUP, "Control", "Control"));
        assertTrue(groupLabelX(record, left) < groupLabelX(record, middle));
        assertTrue(groupLabelX(record, middle) < groupLabelX(record, right));

        PanelConfig reset = Step4Layout.withGroupLabelText(left, "Control", null);
        assertEquals("Control", reset.externalLabelText(
                PanelConfig.ExternalLabelKind.GROUP, "Control", "Control"));
    }

    @Test
    public void layoutAcceptsFinitePerImageCalibrationForTheGuiContext() {
        FPBWizard.Context context = new FPBWizard.Context();
        Step4Layout layout = new Step4Layout(context);

        layout.setCalibrationOverrideForTest("nested\\Control_S1.tif", 0.2, 0.3);

        assertEquals(0.2, context.calibrationOverrides
                .get("nested/Control_S1.tif").pixelWidthUm(), 0.0);
        assertEquals(0.3, context.calibrationOverrides
                .get("nested/Control_S1.tif").pixelHeightUm(), 0.0);
    }

    @Test
    public void layoutPreviewIncludesEverySectionOfTheSelectedAnimal()
            throws Exception {
        File folder = new File("src/test/resources/fixtures/sections")
                .getAbsoluteFile();
        List<File> files = Arrays.asList(folder.listFiles());
        Map<Integer, TokenStrategy.Field> assignment =
                new LinkedHashMap<Integer, TokenStrategy.Field>();
        assignment.put(Integer.valueOf(0), TokenStrategy.Field.GROUP);
        assignment.put(Integer.valueOf(1), TokenStrategy.Field.SUBJECT);
        assignment.put(Integer.valueOf(2), TokenStrategy.Field.SECTION);
        MetadataTable table = MetadataTable.fromFiles(folder, files,
                new TokenStrategy('_', assignment));
        ImageLoader.LoadResult loaded = new ImageLoader(150, 2)
                .loadFiles(files, ProgressCallback.NONE);
        List<ChannelRail.ChannelSpec> specs = Collections.singletonList(
                new ChannelRail.ChannelSpec(0, "DAPI", null));
        Statistic.ImageValues values = Statistic.brightestOnePercentMeans(
                loaded.histogramCache(), Collections.singletonList(
                        Integer.valueOf(0)), Collections.singletonList("DAPI"),
                ImageLoader.ZMode.MAX);
        SubjectAggregator.SubjectStats subjects =
                SubjectAggregator.aggregate(table, values);
        GroupStats groups = GroupStats.from(subjects);
        Map<String, Suggestion.Result> suggestions = Suggestion.suggest(groups);
        FPBWizard.Context context = new FPBWizard.Context();
        context.chooserData = new Step3Chooser.Data(table, loaded.planeCache(),
                loaded.histogramCache(), specs, subjects, suggestions,
                SelectionRecord.from(subjects, groups, suggestions));
        List<Integer> selectedIndices = new java.util.ArrayList<Integer>();
        for (int i = 0; i < table.rows().size(); i++) {
            MetadataRow row = table.rows().get(i);
            if ("Control".equals(row.group) && "S1".equals(row.subject)) {
                selectedIndices.add(Integer.valueOf(i));
            }
        }
        context.selectedRowsByGroup.put("Control", new RowImage.SubjectRow(
                "Control", "S1", selectedIndices, true, null));
        context.layoutChannelRequests.add(new FPBRenderer.ChannelRequest(0,
                "DAPI", ChannelColour.BLUE, new DisplayRange(0, 65535)));
        Step4Layout layout = new Step4Layout(context);

        try {
            layout.onShow();
            List<PanelRecord> records = layout.previewRecordsForTest();
            assertEquals(6, records.size());
            BufferedImage supersampled = ImageIO.read(records.get(0).imageFile());
            assertEquals(440, Math.max(supersampled.getWidth(),
                    supersampled.getHeight()));
            assertEquals("sec1", records.get(0).section());
            assertEquals("sec2", records.get(2).section());
            assertEquals("sec3", records.get(4).section());
        } finally {
            layout.close();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void panelConfigRejectsInvalidScaleBarInsteadOfSubstitutingDefault() {
        PanelConfig.builder().scaleBarLengthUm(Double.NaN).build();
    }

    private PanelRecord record(String group) throws Exception {
        File file = temp.newFile(group + ".png");
        BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, Color.WHITE.getRGB());
            }
        }
        ImageIO.write(image, "png", file);
        return new PanelRecord(file, group, "S1", "", "Merge",
                "Merge", -1, 24, 24, 0.5, 0.5,
                CalibrationCheck.CalibrationSource.USER_ENTERED);
    }

    private static int groupLabelX(PanelRecord record, PanelConfig config)
            throws Exception {
        PanelWriter.WriteReport report = new PanelWriter.WriteReport();
        PanelWriter.renderOverviewPanel(Collections.singletonList(record),
                config, report);
        for (PanelWriter.ExternalLabelBox label : report.externalLabels()) {
            if (label.kind() == PanelConfig.ExternalLabelKind.GROUP) {
                return label.bounds().x;
            }
        }
        throw new AssertionError("Missing group label bounds");
    }
}
