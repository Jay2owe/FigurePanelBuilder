/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.chooser;

import fpb.io.ImageLoader;
import fpb.io.ProgressCallback;
import fpb.meta.MetadataRow;
import fpb.meta.MetadataTable;
import fpb.meta.TokenStrategy;
import fpb.stats.GroupStats;
import fpb.stats.SelectionRecord;
import fpb.stats.Statistic;
import fpb.stats.SubjectAggregator;
import fpb.stats.Suggestion;
import fpb.ui.FPBWizard;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class Step3ChooserTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void changingLockedRepresentativeImmediatelyPublishesToContext()
            throws Exception {
        File folder = new File("src/test/resources/fixtures/basic").getAbsoluteFile();
        Map<Integer, TokenStrategy.Field> assignment =
                new LinkedHashMap<Integer, TokenStrategy.Field>();
        assignment.put(Integer.valueOf(0), TokenStrategy.Field.GROUP);
        assignment.put(Integer.valueOf(1), TokenStrategy.Field.SUBJECT);
        MetadataTable table = MetadataTable.fromFiles(folder,
                Arrays.asList(folder.listFiles()), new TokenStrategy('_', assignment));
        ImageLoader.LoadResult loaded = new ImageLoader(150, 2)
                .loadFolder(folder, false, ProgressCallback.NONE);
        List<ChannelRail.ChannelSpec> specs = Arrays.asList(
                new ChannelRail.ChannelSpec(0, "DAPI", null),
                new ChannelRail.ChannelSpec(1, "Signal 1", null),
                new ChannelRail.ChannelSpec(2, "Signal 2", null));
        Statistic.ImageValues values = Statistic.brightestOnePercentMeans(
                loaded.histogramCache(), Arrays.asList(Integer.valueOf(0),
                        Integer.valueOf(1), Integer.valueOf(2)),
                Arrays.asList("DAPI", "Signal 1", "Signal 2"),
                ImageLoader.ZMode.MAX);
        SubjectAggregator.SubjectStats subjects =
                SubjectAggregator.aggregate(table, values);
        GroupStats groups = GroupStats.from(subjects);
        Map<String, Suggestion.Result> suggestions = Suggestion.suggest(groups);
        Step3Chooser.Data data = new Step3Chooser.Data(table, loaded.planeCache(),
                loaded.histogramCache(), specs, subjects, suggestions,
                SelectionRecord.from(subjects, groups, suggestions));
        final FPBWizard.Context context = new FPBWizard.Context();
        final Step3Chooser chooser = new Step3Chooser(context);

        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    chooser.setData(data);
                    chooser.channelRailForTest().lockChannelForTest(0, 0, 5000);
                    chooser.selectSubjectForTest("Control", "S2");
                }
            });

            assertEquals("S2", context.selectedRowsByGroup.get("Control").subject());
            assertNotNull(chooser.comparisonPanelForTest());
            assertEquals(3, chooser.comparisonPanelForTest()
                    .visibleChannelCountForTest());
            assertEquals(1, chooser.comparisonPanelForTest()
                    .plotCanvasCountForTest());
            assertEquals("S2", chooser.comparisonPanelForTest()
                    .chosenSubjectsForTest().get("Control"));
        } finally {
            chooser.close();
        }
    }

    @Test
    public void eachSectionIsASeparatePickWhileAnimalStatisticsStayAggregated()
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
        List<ChannelRail.ChannelSpec> specs = Arrays.asList(
                new ChannelRail.ChannelSpec(0, "DAPI", null));
        Statistic.ImageValues values = Statistic.brightestOnePercentMeans(
                loaded.histogramCache(), Arrays.asList(Integer.valueOf(0)),
                Arrays.asList("DAPI"), ImageLoader.ZMode.MAX);
        SubjectAggregator.SubjectStats subjects =
                SubjectAggregator.aggregate(table, values);
        GroupStats groups = GroupStats.from(subjects);
        Map<String, Suggestion.Result> suggestions = Suggestion.suggest(groups);
        final Step3Chooser.Data data = new Step3Chooser.Data(table,
                loaded.planeCache(), loaded.histogramCache(), specs, subjects,
                suggestions, SelectionRecord.from(subjects, groups, suggestions));
        final FPBWizard.Context context = new FPBWizard.Context();
        final Step3Chooser chooser = new Step3Chooser(context);

        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    chooser.setData(data);
                    chooser.selectSectionForTest("Control", "S1", "sec2");
                }
            });

            RowImage.SubjectRow pick = context.selectedRowsByGroup.get("Control");
            assertEquals("S1", pick.subject());
            assertEquals("sec2", pick.section());
            assertEquals(1, pick.sectionCount());
            assertEquals(4, chooser.gridRowCountForTest("Control"));
            assertEquals(1, chooser.gridSectionCountForTest("Control"));
            assertEquals(RowImage.Layout.standard(1).rowHeight(),
                    chooser.gridCellHeightForTest("Control"));
            assertEquals("sec2",
                    table.rows().get(pick.imageIndices().get(0)).section);
            assertEquals(3, subjects.sectionCount("Control", "S1", 0));
        } finally {
            chooser.close();
        }
    }

    @Test
    public void actualImagesRenderWithImmediatelyUsableDisplayRanges() throws Exception {
        File image = new File("src/test/resources/fixtures/eightbit.tif")
                .getAbsoluteFile();
        MetadataTable table = new MetadataTable(image.getParentFile(),
                Arrays.asList(new MetadataRow(image, "Control", "S1", "")));
        ImageLoader.LoadResult loaded = new ImageLoader(150, 1).loadFiles(
                Arrays.asList(image), ProgressCallback.NONE);
        List<ChannelRail.ChannelSpec> specs = Arrays.asList(
                new ChannelRail.ChannelSpec(0, "DAPI", null));
        Statistic.ImageValues values = Statistic.brightestOnePercentMeans(
                loaded.histogramCache(), Arrays.asList(Integer.valueOf(0)),
                Arrays.asList("DAPI"), ImageLoader.ZMode.MAX);
        SubjectAggregator.SubjectStats subjects =
                SubjectAggregator.aggregate(table, values);
        GroupStats groups = GroupStats.from(subjects);
        Map<String, Suggestion.Result> suggestions = Suggestion.suggest(groups);
        final Step3Chooser.Data data = new Step3Chooser.Data(table,
                loaded.planeCache(), loaded.histogramCache(), specs, subjects,
                suggestions, SelectionRecord.from(subjects, groups, suggestions));
        final FPBWizard.Context context = new FPBWizard.Context();
        final Step3Chooser chooser = new Step3Chooser(context);

        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    chooser.setData(data);
                }
            });
            long deadline = System.currentTimeMillis() + 5000L;
            while (chooser.renderedRowCountForTest("Control") == 0
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
                SwingUtilities.invokeAndWait(new Runnable() {
                    @Override public void run() {
                        // Flush queued render publication on the event thread.
                    }
                });
            }

            assertTrue(chooser.channelRailForTest().previewChannelRequests()
                    .get(0).range().isValid());
            assertTrue(chooser.channelRailForTest().allRangesLocked());
            assertTrue(chooser.canAdvance());
            assertTrue(chooser.renderedRowCountForTest("Control") > 0);

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    chooser.channelRailForTest().setRangeFieldTextForTest(
                            0, "10", "200");
                    assertTrue(chooser.canAdvance());
                }
            });
            assertEquals(10, context.layoutChannelRequests.get(0).range().min());
            assertEquals(200, context.layoutChannelRequests.get(0).range().max());

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    chooser.channelRailForTest().lockChannelForTest(0, 10, 200);
                    assertTrue("the previous frame must remain visible while contrast rerenders",
                            chooser.renderedRowCountForTest("Control") > 0);
                }
            });
        } finally {
            chooser.close();
        }
    }

    @Test
    public void guiContextUsesSelectedNumericCsvStatistic() throws Exception {
        File folder = new File("src/test/resources/fixtures/basic").getAbsoluteFile();
        Map<Integer, TokenStrategy.Field> assignment =
                new LinkedHashMap<Integer, TokenStrategy.Field>();
        assignment.put(Integer.valueOf(0), TokenStrategy.Field.GROUP);
        assignment.put(Integer.valueOf(1), TokenStrategy.Field.SUBJECT);
        MetadataTable table = MetadataTable.fromFiles(folder,
                Arrays.asList(folder.listFiles()), new TokenStrategy('_', assignment));
        File csv = temp.newFile("gui-statistic.csv");
        PrintWriter out = new PrintWriter(csv);
        try {
            out.println("File,MeanIntensity");
            for (int i = 0; i < table.rows().size(); i++) {
                out.println(table.csvFileName(table.rows().get(i)) + "," + (i + 1));
            }
        } finally {
            out.close();
        }
        FPBWizard.Context context = new FPBWizard.Context();
        context.metadataTable = table;
        context.statisticCsv = csv;
        context.statisticColumn = "MeanIntensity";
        Step3Chooser chooser = new Step3Chooser(context);

        try {
            Step3Chooser.Data data = chooser.buildFromContextForTest();
            assertEquals("MeanIntensity", data.subjectStats().statisticName());
            assertEquals(24, data.selectionRecords().size());
            assertEquals(-1, data.selectionRecords().get(0).channelIndex());
            assertTrue(SpinePainter.groupData(
                    fpb.stats.GroupQuantification.from(data.subjectStats()),
                    "Control").channelCount() > 0);
        } finally {
            chooser.close();
        }
    }

    @Test
    public void missingGroupsShowRecoveryInsteadOfABlankChooser() throws Exception {
        File image = new File("src/test/resources/fixtures/eightbit.tif")
                .getAbsoluteFile();
        FPBWizard.Context context = new FPBWizard.Context();
        context.metadataTable = new MetadataTable(image.getParentFile(),
                Arrays.asList(new MetadataRow(image, "", "S1", "")));
        Step3Chooser chooser = new Step3Chooser(context);

        try {
            chooser.onShow();

            assertTrue(chooser.emptyMessageForTest().contains(
                    "1 image has no usable group or subject"));
            assertTrue(chooser.groupRecoveryVisibleForTest());
            assertFalse(chooser.canAdvance());
        } finally {
            chooser.close();
        }
    }

    @Test
    public void groupRecoveryCanReturnDirectlyToMetadata() throws Exception {
        File image = new File("src/test/resources/fixtures/eightbit.tif")
                .getAbsoluteFile();
        FPBWizard.Context context = new FPBWizard.Context();
        context.metadataTable = new MetadataTable(image.getParentFile(),
                Arrays.asList(new MetadataRow(image, "", "S1", "")));
        final boolean[] returned = new boolean[1];
        Step3Chooser chooser = new Step3Chooser(context, new Runnable() {
            @Override public void run() {
                returned[0] = true;
            }
        });

        try {
            chooser.onShow();
            chooser.editGroupsForTest();

            assertTrue(returned[0]);
        } finally {
            chooser.close();
        }
    }

    @Test
    public void explicitOneGroupFallbackRepairsMissingAssignments() throws Exception {
        File image = new File("src/test/resources/fixtures/eightbit.tif")
                .getAbsoluteFile();
        MetadataRow row = new MetadataRow(image, "", "", "section-1");
        FPBWizard.Context context = new FPBWizard.Context();
        context.metadataTable = new MetadataTable(image.getParentFile(),
                Arrays.asList(row));
        Step3Chooser chooser = new Step3Chooser(context);

        try {
            chooser.onShow();
            chooser.useOneGroupFallbackForTest();

            assertEquals("All images", row.group);
            assertEquals("eightbit", row.subject);
            assertEquals("section-1", row.section);
            assertTrue(context.tableHandEdited);
            assertEquals("", Step3Chooser.groupingProblem(context.metadataTable));
        } finally {
            chooser.close();
        }
    }

    @Test
    public void caseOnlyGroupVariantsAreActionableInsteadOfSeparateSilentGroups()
            throws Exception {
        File image = new File("src/test/resources/fixtures/eightbit.tif")
                .getAbsoluteFile();
        MetadataTable table = new MetadataTable(image.getParentFile(), Arrays.asList(
                new MetadataRow(image, "Control", "S1", ""),
                new MetadataRow(image, "control", "S2", "")));

        String problem = Step3Chooser.groupingProblem(table);

        assertTrue(problem.contains("differ only by capitalisation"));
        assertTrue(problem.contains("Control"));
        assertTrue(problem.contains("control"));
    }
}
