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
import fpb.render.ChannelColour;
import fpb.render.DisplayRange;
import fpb.render.FPBRenderer;
import fpb.stats.GroupStats;
import fpb.stats.SelectionRecord;
import fpb.stats.Statistic;
import fpb.stats.SubjectAggregator;
import fpb.stats.Suggestion;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SpinePainterTest {

    @Test
    public void markedSubjectMatchesStageFiveSuggestionForBasicFixture()
            throws Exception {
        Computed computed = computedBasic();

        for (String group : computed.subjects.groups()) {
            Suggestion.Result suggestion = computed.suggestions.get(group);
            SpinePainter.GroupData spine = SpinePainter.groupData(computed.records,
                    group, computed.subjects.subjectsInGroup(group));

            assertNotNull(suggestion);
            assertTrue(spine.trace(suggestion.suggestedSubject()).suggested());
        }
    }

    @Test
    public void sectionCountsDriveTickModel() {
        List<SelectionRecord> records = new ArrayList<SelectionRecord>();
        records.add(new SelectionRecord("Control", "S1", 0, "C1",
                10.0, 20.0, -10.0, -0.5, 3, true));
        records.add(new SelectionRecord("Control", "S2", 0, "C1",
                30.0, 20.0, 10.0, 0.5, 1, false));

        SpinePainter.GroupData spine = SpinePainter.groupData(records, "Control",
                Arrays.asList("S1", "S2"));

        assertEquals(3, spine.trace("S1").sectionCount(0));
        assertEquals(1, spine.trace("S2").sectionCount(0));
    }

    @Test
    public void brightnessRenderReusesCachedSpine() throws Exception {
        Computed computed = computedBasic();
        String group = "Control";
        SpinePainter.GroupData spine = SpinePainter.groupData(computed.records,
                group, computed.subjects.subjectsInGroup(group));
        RowImage.SubjectRow row = new RowImage.SubjectRow(group, "S3", 2, true, spine);
        List<FPBRenderer.ChannelRequest> channels =
                new ArrayList<FPBRenderer.ChannelRequest>();
        channels.add(new FPBRenderer.ChannelRequest(0, "C1", ChannelColour.BLUE,
                new DisplayRange(0, 2000)));
        channels.add(new FPBRenderer.ChannelRequest(1, "C2", ChannelColour.MAGENTA,
                new DisplayRange(0, 3000)));
        channels.add(new FPBRenderer.ChannelRequest(2, "C3", ChannelColour.CYAN,
                new DisplayRange(0, 4000)));

        SpinePainter.resetPaintCountForTest();
        RowImage.renderSubject(row, computed.loaded.planeCache(),
                computed.loaded.histogramCache(), channels, RowImage.Layout.standard(3),
                true);
        RowImage.renderSubject(row, computed.loaded.planeCache(),
                computed.loaded.histogramCache(), channels, RowImage.Layout.standard(3),
                true);

        assertEquals(1, SpinePainter.paintCountForTest());
    }

    private static Computed computedBasic() throws Exception {
        MetadataTable table = basicTable();
        ImageLoader.LoadResult loaded = new ImageLoader(150, 4)
                .loadFolder(fixture("basic"), false, ProgressCallback.NONE);
        Statistic.ImageValues values =
                Statistic.brightestOnePercentMeans(loaded.histogramCache());
        SubjectAggregator.SubjectStats subjects =
                SubjectAggregator.aggregate(table, values);
        GroupStats groupStats = GroupStats.from(subjects);
        Map<String, Suggestion.Result> suggestions = Suggestion.suggest(groupStats);
        List<SelectionRecord> records =
                SelectionRecord.from(subjects, groupStats, suggestions);
        return new Computed(loaded, subjects, suggestions, records);
    }

    private static MetadataTable basicTable() throws Exception {
        File folder = fixture("basic");
        return MetadataTable.fromFiles(folder, imageFiles(folder), basicTokenStrategy());
    }

    private static TokenStrategy basicTokenStrategy() {
        Map<Integer, TokenStrategy.Field> assignment =
                new LinkedHashMap<Integer, TokenStrategy.Field>();
        assignment.put(Integer.valueOf(0), TokenStrategy.Field.GROUP);
        assignment.put(Integer.valueOf(1), TokenStrategy.Field.SUBJECT);
        return new TokenStrategy('_', assignment);
    }

    private static List<File> imageFiles(File folder) {
        File[] files = folder.listFiles();
        assertNotNull(files);
        List<File> list = Arrays.asList(files);
        Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        return list;
    }

    private static File fixture(String path) {
        return new File("src/test/resources/fixtures", path).getAbsoluteFile();
    }

    private static final class Computed {
        final ImageLoader.LoadResult loaded;
        final SubjectAggregator.SubjectStats subjects;
        final Map<String, Suggestion.Result> suggestions;
        final List<SelectionRecord> records;

        Computed(ImageLoader.LoadResult loaded,
                SubjectAggregator.SubjectStats subjects,
                Map<String, Suggestion.Result> suggestions,
                List<SelectionRecord> records) {
            this.loaded = loaded;
            this.subjects = subjects;
            this.suggestions = suggestions;
            this.records = records;
        }
    }
}
