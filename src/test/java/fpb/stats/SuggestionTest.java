/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.stats;

import fpb.io.ImageLoader;
import fpb.io.HistogramCache;
import fpb.io.ProgressCallback;
import fpb.meta.MetadataRow;
import fpb.meta.MetadataTable;
import fpb.meta.MetadataTableIO;
import fpb.meta.TokenStrategy;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SuggestionTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void aggregatesSectionsBeforeRanking() throws Exception {
        MetadataTable table = tableWithSections();
        Statistic.ImageValues values = singleChannelValues(table,
                39.0, 102.0, 31.0, 33.0, 195.0, 122.0);

        SubjectAggregator.SubjectStats subjects =
                SubjectAggregator.aggregate(table, values);
        GroupStats groupStats = GroupStats.from(subjects);
        Suggestion.Result suggestion =
                Suggestion.suggestGroup(groupStats, "Control");

        assertEquals("S4", suggestion.suggestedSubject());
        assertEquals("S1", imageLevelSuggestion(table, values));
        assertEquals(3, subjects.sectionCount("Control", "S1", 0));
        assertEquals(57.333333333333336,
                subjects.value("Control", "S1", 0).doubleValue(), 0.0000001);
    }

    @Test
    public void basicFixtureSuggestionMatchesHandComputedSubject()
            throws Exception {
        MetadataTable table = basicTable();
        Statistic.ImageValues values = Statistic.brightestOnePercentMeans(
                new ImageLoader(150, 4)
                        .loadFolder(fixture("basic"), false, ProgressCallback.NONE)
                        .histogramCache());

        Map<String, Suggestion.Result> suggestions = Suggestion.suggest(
                GroupStats.from(SubjectAggregator.aggregate(table, values)));

        assertEquals("S3", suggestions.get("Control").suggestedSubject());
        assertEquals(Arrays.asList("S3", "S4", "S2"),
                suggestions.get("Control").shortlist());
        assertEquals("S3", suggestions.get("DrugA").suggestedSubject());
        assertEquals("S3", suggestions.get("DrugB").suggestedSubject());
        assertEquals("S3", suggestions.get("Wash").suggestedSubject());
    }

    @Test
    public void channelScaleDoesNotChangeSuggestion() throws Exception {
        MetadataTable table = fourSubjectTable();
        Statistic.ImageValues original = twoChannelValues(table, 1.0);
        Statistic.ImageValues scaled = twoChannelValues(table, 1000.0);

        String before = Suggestion.suggestGroup(GroupStats.from(
                SubjectAggregator.aggregate(table, original)), "Control")
                .suggestedSubject();
        String after = Suggestion.suggestGroup(GroupStats.from(
                SubjectAggregator.aggregate(table, scaled)), "Control")
                .suggestedSubject();

        assertEquals("S3", before);
        assertEquals(before, after);
    }

    @Test
    public void tiesResolveDeterministicallyBySubjectName() throws Exception {
        MetadataTable table = basicTable();
        Statistic.ImageValues values = Statistic.brightestOnePercentMeans(
                new ImageLoader(150, 4)
                        .loadFolder(fixture("basic"), false, ProgressCallback.NONE)
                        .histogramCache());

        for (int run = 0; run < 10; run++) {
            Suggestion.Result suggestion = Suggestion.suggestGroup(GroupStats.from(
                    SubjectAggregator.aggregate(table, values)), "Control");
            assertEquals("S3", suggestion.suggestedSubject());
        }
    }

    @Test
    public void brightestOnePercentMatchesBruteForceReference() {
        int[] pixels = new int[250];
        for (int i = 0; i < pixels.length; i++) pixels[i] = i % 200;
        pixels[10] = 1000;
        pixels[11] = 2000;
        pixels[12] = 3000;

        int[] cumulative = cumulative(pixels);

        assertEquals(bruteForceBrightestOnePercent(pixels),
                Statistic.brightestOnePercentMean(cumulative, pixels.length),
                0.0000001);
    }

    @Test
    public void csvReportsUnmatchedAndRejectsNonNumericColumn() throws Exception {
        MetadataTable table = basicTable();
        File csv = temp.newFile("stats.csv");
        PrintWriter out = new PrintWriter(csv);
        try {
            out.println("File,MeanIntensity,Label");
            out.println("Control_S1.tif,12.5,ok");
            out.println("missing.tif,99.0,missing");
        } finally {
            out.close();
        }

        StatCsvLoader.LoadResult result =
                StatCsvLoader.load(csv, "MeanIntensity", table);

        assertTrue(result.hasUnmatchedFiles());
        assertEquals(Arrays.asList("missing.tif"), result.unmatchedFiles());
        assertEquals(12.5, result.valuesByFile().values().iterator().next(),
                0.0000001);

        try {
            StatCsvLoader.load(csv, "Label", table);
            assertTrue("Expected non-numeric column to be rejected", false);
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("non-numeric"));
        }
    }

    @Test
    public void statisticCsvRequiresAnExactOneToOneJoinBeforeRanking()
            throws Exception {
        MetadataTable table = basicTable();
        File csv = temp.newFile("duplicate-partial-stats.csv");
        PrintWriter out = new PrintWriter(csv);
        try {
            out.println("File,MeanIntensity");
            out.println("Control_S1.tif,12.5");
            out.println("Control_S1.tif,99.0");
            out.println("unknown.tif,44.0");
        } finally {
            out.close();
        }

        StatCsvLoader.LoadResult result =
                StatCsvLoader.load(csv, "MeanIntensity", table);

        assertFalse(result.isComplete());
        assertEquals(Arrays.asList("Control_S1.tif"), result.duplicateFiles());
        assertEquals(Arrays.asList("unknown.tif"), result.unmatchedFiles());
        assertEquals(23, result.uncoveredFiles().size());
        try {
            result.imageValues();
            assertTrue("Incomplete statistic joins must not produce rankable values", false);
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("incomplete"));
        }
    }

    @Test
    public void importedStatisticIsBomTolerantAndChannelIndependent()
            throws Exception {
        MetadataTable table = fourSubjectTable();
        File csv = temp.newFile("bom-complete-stats.csv");
        StringBuilder text = new StringBuilder("\uFEFF\"File\",MeanIntensity\n");
        for (int i = 0; i < table.rows().size(); i++) {
            text.append(table.rows().get(i).file.getName()).append(',')
                    .append(10 + i).append('\n');
        }
        Files.write(csv.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));

        Statistic.ImageValues values = StatCsvLoader.load(
                csv, "MeanIntensity", table).imageValues();

        assertEquals(Statistic.CHANNEL_INDEPENDENT,
                values.sourceChannelIndex(0));
        assertEquals("Channel-independent (MeanIntensity)",
                values.channelName(0));
    }

    @Test
    public void exportedMetadataCsvCanBeExtendedWithStatisticColumn()
            throws Exception {
        MetadataTable table = fourSubjectTable();
        File csv = temp.newFile("metadata-with-statistic.csv");
        MetadataTableIO.exportCsv(table, csv);
        List<String> lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
        List<String> extended = new ArrayList<String>();
        for (int i = 0; i < lines.size(); i++) {
            extended.add(lines.get(i) + (i == 0
                    ? ",MeanIntensity" : "," + (10 + i)));
        }
        Files.write(csv.toPath(), extended, StandardCharsets.UTF_8);

        StatCsvLoader.LoadResult result = StatCsvLoader.load(
                csv, "MeanIntensity", table);

        assertTrue(result.isComplete());
        assertEquals(4, result.valuesByFile().size());
    }

    @Test
    public void selectionRecordCarriesEvidenceForEverySubjectChannel()
            throws Exception {
        File folder = fixture("sections");
        MetadataTable table = MetadataTable.fromFiles(folder, imageFiles(folder),
                sectionsTokenStrategy());
        Statistic.ImageValues values = Statistic.brightestOnePercentMeans(
                new ImageLoader(150, 2)
                        .loadFolder(folder, false, ProgressCallback.NONE)
                        .histogramCache());
        SubjectAggregator.SubjectStats subjects =
                SubjectAggregator.aggregate(table, values);
        GroupStats groupStats = GroupStats.from(subjects);
        Map<String, Suggestion.Result> suggestions = Suggestion.suggest(groupStats);

        List<SelectionRecord> records =
                SelectionRecord.from(subjects, groupStats, suggestions);

        assertEquals(6, records.size());
        SelectionRecord s1Channel1 = find(records, "S1", 0);
        assertEquals(102.0, s1Channel1.value(), 0.0000001);
        assertEquals(106.5, s1Channel1.groupMean(), 0.0000001);
        assertEquals(-4.5, s1Channel1.rawDeviation(), 0.0000001);
        assertEquals(-0.5, s1Channel1.deviation(), 0.0000001);
        assertEquals(3, s1Channel1.sectionCount());
        assertTrue(s1Channel1.suggested());
    }

    @Test
    public void includedChannelSubsetKeepsConfiguredNameAndSourceIndex()
            throws Exception {
        File folder = fixture("basic");
        ImageLoader.LoadResult loaded = new ImageLoader(150, 2)
                .loadFolder(folder, false, ImageLoader.ZMode.FIRST,
                        ProgressCallback.NONE);
        Statistic.ImageValues values = Statistic.brightestOnePercentMeans(
                loaded.histogramCache(), Arrays.asList(Integer.valueOf(2)),
                Arrays.asList("Iba1 only"), ImageLoader.ZMode.FIRST);
        SubjectAggregator.SubjectStats subjects = SubjectAggregator.aggregate(
                basicTable(), values);
        GroupStats groups = GroupStats.from(subjects);
        List<SelectionRecord> records = SelectionRecord.from(subjects, groups,
                Suggestion.suggest(groups));

        assertEquals(1, values.channelCount());
        assertEquals(2, values.sourceChannelIndex(0));
        assertEquals("Iba1 only", values.channelName(0));
        assertEquals(24, records.size());
        assertEquals(2, records.get(0).channelIndex());
        assertEquals("Iba1 only", records.get(0).channelName());
        assertEquals(Statistic.BRIGHTEST_ONE_PERCENT_FIRST_SLICE_NAME,
                values.statisticName());
    }

    @Test
    public void statisticProvenanceNamesActualProjectionMode() throws Exception {
        HistogramCache cache = new ImageLoader(150, 1)
                .loadFolder(fixture("sections"), false, ImageLoader.ZMode.MAX,
                        ProgressCallback.NONE).histogramCache();
        List<Integer> channel = Arrays.asList(Integer.valueOf(0));
        List<String> name = Arrays.asList("Signal");

        Statistic.ImageValues first = Statistic.brightestOnePercentMeans(
                cache, channel, name, ImageLoader.ZMode.FIRST);
        Statistic.ImageValues max = Statistic.brightestOnePercentMeans(
                cache, channel, name, ImageLoader.ZMode.MAX);

        assertEquals(Statistic.BRIGHTEST_ONE_PERCENT_FIRST_SLICE_NAME,
                first.statisticName());
        assertEquals(Statistic.BRIGHTEST_ONE_PERCENT_NAME, max.statisticName());
        assertFalse(first.statisticName().equals(max.statisticName()));
    }

    private static SelectionRecord find(List<SelectionRecord> records,
            String subject, int channel) {
        for (SelectionRecord record : records) {
            if (subject.equals(record.subject()) && record.channelIndex() == channel) {
                return record;
            }
        }
        throw new AssertionError("Missing record for " + subject + " channel " + channel);
    }

    private MetadataTable tableWithSections() throws Exception {
        List<MetadataRow> rows = new ArrayList<MetadataRow>();
        rows.add(new MetadataRow(touch("Control_S1_sec1.tif"), "Control", "S1", "sec1"));
        rows.add(new MetadataRow(touch("Control_S1_sec2.tif"), "Control", "S1", "sec2"));
        rows.add(new MetadataRow(touch("Control_S1_sec3.tif"), "Control", "S1", "sec3"));
        rows.add(new MetadataRow(touch("Control_S2.tif"), "Control", "S2", ""));
        rows.add(new MetadataRow(touch("Control_S3.tif"), "Control", "S3", ""));
        rows.add(new MetadataRow(touch("Control_S4.tif"), "Control", "S4", ""));
        return new MetadataTable(temp.getRoot(), rows);
    }

    private MetadataTable fourSubjectTable() throws Exception {
        List<MetadataRow> rows = new ArrayList<MetadataRow>();
        rows.add(new MetadataRow(touch("Control_S1.tif"), "Control", "S1", ""));
        rows.add(new MetadataRow(touch("Control_S2.tif"), "Control", "S2", ""));
        rows.add(new MetadataRow(touch("Control_S3.tif"), "Control", "S3", ""));
        rows.add(new MetadataRow(touch("Control_S4.tif"), "Control", "S4", ""));
        return new MetadataTable(temp.getRoot(), rows);
    }

    private File touch(String name) throws Exception {
        File file = new File(temp.getRoot(), name);
        PrintWriter out = new PrintWriter(file);
        try {
            out.println("placeholder");
        } finally {
            out.close();
        }
        return file;
    }

    private static Statistic.ImageValues singleChannelValues(MetadataTable table,
            double... values) {
        Map<File, Double> byFile = new LinkedHashMap<File, Double>();
        for (int i = 0; i < table.rows().size(); i++) {
            byFile.put(table.rows().get(i).file, Double.valueOf(values[i]));
        }
        return Statistic.ImageValues.singleChannel(byFile, "Mean", "Mean");
    }

    private static Statistic.ImageValues twoChannelValues(MetadataTable table,
            double secondChannelScale) {
        double[][] values = {
                { 10.0, 10.0 * secondChannelScale },
                { 20.0, 20.0 * secondChannelScale },
                { 24.0, 24.0 * secondChannelScale },
                { 100.0, 100.0 * secondChannelScale }
        };
        Map<File, Map<Integer, Double>> byFile =
                new LinkedHashMap<File, Map<Integer, Double>>();
        for (int i = 0; i < table.rows().size(); i++) {
            Map<Integer, Double> channels = new LinkedHashMap<Integer, Double>();
            channels.put(Integer.valueOf(0), Double.valueOf(values[i][0]));
            channels.put(Integer.valueOf(1), Double.valueOf(values[i][1]));
            byFile.put(table.rows().get(i).file, channels);
        }
        return Statistic.ImageValues.of(byFile, Arrays.asList("A", "B"), "Synthetic");
    }

    private static String imageLevelSuggestion(MetadataTable table,
            Statistic.ImageValues values) {
        List<ImageCandidate> candidates = new ArrayList<ImageCandidate>();
        double sum = 0.0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (MetadataRow row : table.rows()) {
            Double value = values.value(row.file, 0);
            if (value == null) continue;
            double v = value.doubleValue();
            candidates.add(new ImageCandidate(row.subject, v));
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / (double) candidates.size();
        double range = max > min ? max - min : 1.0;
        for (ImageCandidate candidate : candidates) {
            candidate.score = Math.abs(candidate.value - mean) / range;
        }
        Collections.sort(candidates, ImageCandidate.BY_SCORE);
        return candidates.get(0).subject;
    }

    private static int[] cumulative(int[] pixels) {
        int[] cumulative = new int[65536];
        for (int i = 0; i < pixels.length; i++) cumulative[pixels[i]]++;
        for (int i = 1; i < cumulative.length; i++) cumulative[i] += cumulative[i - 1];
        return cumulative;
    }

    private static double bruteForceBrightestOnePercent(int[] pixels) {
        int[] sorted = pixels.clone();
        Arrays.sort(sorted);
        int count = (int) Math.ceil(sorted.length * 0.01);
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            sum += sorted[sorted.length - 1 - i];
        }
        return sum / (double) count;
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

    private static TokenStrategy sectionsTokenStrategy() {
        Map<Integer, TokenStrategy.Field> assignment =
                new LinkedHashMap<Integer, TokenStrategy.Field>();
        assignment.put(Integer.valueOf(0), TokenStrategy.Field.GROUP);
        assignment.put(Integer.valueOf(1), TokenStrategy.Field.SUBJECT);
        assignment.put(Integer.valueOf(2), TokenStrategy.Field.SECTION);
        return new TokenStrategy('_', assignment);
    }

    private static List<File> imageFiles(File folder) {
        File[] files = folder.listFiles();
        assertTrue(files != null);
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

    private static final class ImageCandidate {
        static final Comparator<ImageCandidate> BY_SCORE =
                new Comparator<ImageCandidate>() {
                    @Override
                    public int compare(ImageCandidate left, ImageCandidate right) {
                        int byScore = Double.compare(left.score, right.score);
                        if (byScore != 0) return byScore;
                        return left.subject.compareTo(right.subject);
                    }
                };

        final String subject;
        final double value;
        double score;

        ImageCandidate(String subject, double value) {
            this.subject = subject;
            this.value = value;
        }
    }
}
