/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb;

import fpb.figure.PanelConfig;
import fpb.figure.ImageOrientation;
import fpb.io.HistogramCache;
import fpb.io.ImageLoader;
import fpb.io.ImageSource;
import fpb.io.PlaneCache;
import fpb.io.ProgressCallback;
import fpb.meta.MetadataRow;
import fpb.meta.MetadataTable;
import fpb.render.ChannelColour;
import fpb.render.DisplayRange;
import fpb.render.FPBRenderer;
import fpb.stats.SelectionRecord;
import fpb.stats.Statistic;
import fpb.stats.SubjectAggregator;
import fpb.ui.chooser.ChannelRail;
import fpb.ui.chooser.RowImage;
import fpb.ui.chooser.Step3Chooser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Express route from an image folder to layout-ready, shared-range grid state. */
public final class QuickGrid {

    public static final String RANGE_SOURCE = "cohort_percentile_0.1_99.9";
    public static final String SELECTION_METHOD = "none";
    public static final String GROUPING = "none";

    private static final List<ChannelColour> DEFAULT_COLOURS =
            Collections.unmodifiableList(Arrays.asList(
                    ChannelColour.BLUE,
                    ChannelColour.MAGENTA,
                    ChannelColour.GREEN,
                    ChannelColour.CYAN,
                    ChannelColour.YELLOW,
                    ChannelColour.GREY,
                    ChannelColour.RED));

    private QuickGrid() {}

    public static Result run(File folder, boolean recursive) throws IOException {
        return run(folder, recursive, ProgressCallback.NONE);
    }

    public static Result run(File folder, boolean recursive,
            ProgressCallback progress) throws IOException {
        return run(folder, recursive, ImageLoader.ZMode.MAX, progress);
    }

    public static Result run(File folder, boolean recursive,
            ImageLoader.ZMode zMode, ProgressCallback progress) throws IOException {
        ImageLoader.LoadResult loaded = new ImageLoader(150, 4)
                .loadFolder(folder, recursive, zMode, progress);
        List<ImageSource> sources = sources(loaded.planeCache());
        List<File> files = sourceFiles(sources);
        MetadataTable table = quickGridTable(folder, sources);
        List<ChannelRail.ChannelSpec> specs = channelSpecs(loaded.channelCount());
        LinkedHashMap<Integer, DisplayRange> ranges =
                cohortRanges(loaded.histogramCache());
        List<FPBRenderer.ChannelRequest> requests = channelRequests(specs, ranges);

        Statistic.ImageValues imageValues = neutralImageValues(sources);
        SubjectAggregator.SubjectStats subjectStats =
                SubjectAggregator.aggregate(table, imageValues);

        Step3Chooser.Data data = new Step3Chooser.Data(table, loaded.planeCache(),
                loaded.histogramCache(), specs, subjectStats,
                Collections.<String, fpb.stats.Suggestion.Result>emptyMap(),
                Collections.<SelectionRecord>emptyList());
        LinkedHashMap<String, RowImage.SubjectRow> selected =
                selectedRows(table);
        PanelConfig config = defaultConfig(specs, selected.keySet());
        return new Result(files, ranges, requests, table, data, selected, config);
    }

    private static Statistic.ImageValues neutralImageValues(List<ImageSource> sources) {
        Map<ImageSource, Double> values = new LinkedHashMap<ImageSource, Double>();
        for (ImageSource source : sources) values.put(source, Double.valueOf(0.0));
        return Statistic.ImageValues.singleChannelSources(values, "none", "none");
    }

    public static LinkedHashMap<Integer, DisplayRange> cohortRanges(
            HistogramCache histograms) {
        if (histograms == null) throw new IllegalArgumentException("histograms is required");
        LinkedHashMap<Integer, DisplayRange> ranges =
                new LinkedHashMap<Integer, DisplayRange>();
        for (int channel = 0; channel < histograms.channelCount(); channel++) {
            int min = percentile(histograms.pooledHistogram(channel), 0.001);
            int max = percentile(histograms.pooledHistogram(channel), 0.999);
            ranges.put(Integer.valueOf(channel), validRange(min, max));
        }
        return ranges;
    }

    private static List<ImageSource> sources(PlaneCache planes) {
        List<ImageSource> sources = new ArrayList<ImageSource>();
        for (PlaneCache.ImagePlanes image : planes.images()) {
            sources.add(image.source());
        }
        return Collections.unmodifiableList(sources);
    }

    private static List<File> sourceFiles(List<ImageSource> sources) {
        List<File> files = new ArrayList<File>();
        for (ImageSource source : sources) files.add(source.file());
        return Collections.unmodifiableList(files);
    }

    private static MetadataTable quickGridTable(File folder, List<ImageSource> sources) {
        List<MetadataRow> rows = new ArrayList<MetadataRow>();
        for (int i = 0; i < sources.size(); i++) {
            ImageSource source = sources.get(i);
            if (source.isSeries()) {
                String label = String.format(java.util.Locale.ROOT, "%03d %s — %s",
                        Integer.valueOf(i + 1), basename(source.file()),
                        source.seriesLabel());
                rows.add(new MetadataRow(source, label, source.seriesLabel(), ""));
            } else {
                String label = indexedLabel(i, source.file());
                rows.add(new MetadataRow(source, label, label, ""));
            }
        }
        return new MetadataTable(folder, rows);
    }

    private static List<ChannelRail.ChannelSpec> channelSpecs(int channelCount) {
        List<ChannelRail.ChannelSpec> specs =
                new ArrayList<ChannelRail.ChannelSpec>();
        for (int channel = 0; channel < channelCount; channel++) {
            specs.add(new ChannelRail.ChannelSpec(channel, "C" + (channel + 1),
                    DEFAULT_COLOURS.get(channel % DEFAULT_COLOURS.size())));
        }
        return Collections.unmodifiableList(specs);
    }

    private static List<FPBRenderer.ChannelRequest> channelRequests(
            List<ChannelRail.ChannelSpec> specs,
            Map<Integer, DisplayRange> ranges) {
        List<FPBRenderer.ChannelRequest> requests =
                new ArrayList<FPBRenderer.ChannelRequest>();
        for (ChannelRail.ChannelSpec spec : specs) {
            requests.add(new FPBRenderer.ChannelRequest(spec.channelIndex(),
                    spec.name(), spec.colour(),
                    ranges.get(Integer.valueOf(spec.channelIndex()))));
        }
        return Collections.unmodifiableList(requests);
    }

    private static LinkedHashMap<String, RowImage.SubjectRow> selectedRows(
            MetadataTable table) {
        LinkedHashMap<String, RowImage.SubjectRow> rows =
                new LinkedHashMap<String, RowImage.SubjectRow>();
        List<MetadataRow> metadata = table.rows();
        for (int i = 0; i < metadata.size(); i++) {
            MetadataRow row = metadata.get(i);
            rows.put(row.group, new RowImage.SubjectRow(row.group, row.subject,
                    row.section, i, false, null, table.csvFileName(row),
                    ImageOrientation.IDENTITY));
        }
        return rows;
    }

    private static PanelConfig defaultConfig(List<ChannelRail.ChannelSpec> specs,
            Iterable<String> groups) {
        List<String> channels = new ArrayList<String>();
        for (ChannelRail.ChannelSpec spec : specs) channels.add(spec.name());
        channels.add("Merge");
        List<String> orderedGroups = new ArrayList<String>();
        for (String group : groups) orderedGroups.add(group);
        return PanelConfig.builder()
                .createOverviewPanel(true)
                .annotateOverviewPanel(true)
                .channelOrder(channels)
                .cellSizePx(180)
                .scaleBarLengthUm(50.0)
                .groupLayoutRows(balancedRows(orderedGroups))
                .build();
    }

    private static List<List<String>> balancedRows(List<String> groups) {
        if (groups.isEmpty()) return Collections.emptyList();
        int perRow = Math.max(1, (int) Math.ceil(Math.sqrt(groups.size())));
        List<List<String>> rows = new ArrayList<List<String>>();
        for (int i = 0; i < groups.size(); i += perRow) {
            int end = Math.min(groups.size(), i + perRow);
            rows.add(Collections.unmodifiableList(
                    new ArrayList<String>(groups.subList(i, end))));
        }
        return Collections.unmodifiableList(rows);
    }

    private static int percentile(HistogramCache.Histogram histogram,
            double fraction) {
        if (histogram == null || histogram.total() <= 0L) return 0;
        long target = (long) Math.ceil(histogram.total() * fraction);
        if (target < 1L) target = 1L;
        for (int value = 0; value < HistogramCache.BIN_COUNT; value++) {
            if (histogram.cumulativeCountAt(value) >= target) return value;
        }
        return HistogramCache.BIN_COUNT - 1;
    }

    private static DisplayRange validRange(int min, int max) {
        int lo = Math.max(DisplayRange.MIN_VALUE, Math.min(DisplayRange.MAX_VALUE, min));
        int hi = Math.max(DisplayRange.MIN_VALUE, Math.min(DisplayRange.MAX_VALUE, max));
        if (hi <= lo) {
            if (lo > DisplayRange.MIN_VALUE) lo--;
            if (hi <= lo && hi < DisplayRange.MAX_VALUE) hi++;
            if (hi <= lo) lo = Math.max(DisplayRange.MIN_VALUE, hi - 1);
        }
        return new DisplayRange(lo, hi);
    }

    private static String indexedLabel(int index, File file) {
        return String.format(java.util.Locale.ROOT, "%03d %s",
                Integer.valueOf(index + 1), basename(file));
    }

    private static String basename(File file) {
        String name = file == null ? "image" : file.getName();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    public static final class Result {
        private final List<File> files;
        private final Map<Integer, DisplayRange> ranges;
        private final List<FPBRenderer.ChannelRequest> channelRequests;
        private final MetadataTable table;
        private final Step3Chooser.Data chooserData;
        private final LinkedHashMap<String, RowImage.SubjectRow> selectedRowsByGroup;
        private final PanelConfig panelConfig;

        private Result(List<File> files, Map<Integer, DisplayRange> ranges,
                List<FPBRenderer.ChannelRequest> channelRequests,
                MetadataTable table, Step3Chooser.Data chooserData,
                LinkedHashMap<String, RowImage.SubjectRow> selectedRowsByGroup,
                PanelConfig panelConfig) {
            this.files = Collections.unmodifiableList(new ArrayList<File>(files));
            this.ranges = Collections.unmodifiableMap(
                    new LinkedHashMap<Integer, DisplayRange>(ranges));
            this.channelRequests = Collections.unmodifiableList(
                    new ArrayList<FPBRenderer.ChannelRequest>(channelRequests));
            this.table = table;
            this.chooserData = chooserData;
            this.selectedRowsByGroup = new LinkedHashMap<String, RowImage.SubjectRow>(
                    selectedRowsByGroup);
            this.panelConfig = panelConfig;
        }

        public List<File> files() {
            return files;
        }

        public Map<Integer, DisplayRange> ranges() {
            return ranges;
        }

        public List<FPBRenderer.ChannelRequest> channelRequests() {
            return channelRequests;
        }

        public MetadataTable table() {
            return table;
        }

        public Step3Chooser.Data chooserData() {
            return chooserData;
        }

        public LinkedHashMap<String, RowImage.SubjectRow> selectedRowsByGroup() {
            return new LinkedHashMap<String, RowImage.SubjectRow>(selectedRowsByGroup);
        }

        public PanelConfig panelConfig() {
            return panelConfig;
        }
    }
}
