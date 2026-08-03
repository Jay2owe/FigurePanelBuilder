/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.record;

import fpb.figure.CalibrationCheck;
import fpb.figure.PanelRecord;
import fpb.render.DisplayRange;
import fpb.stats.SelectionRecord;
import fpb.util.CsvSupport;
import fpb.util.IoUtils;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes methods.txt with checklist fields followed by suggested methods text. */
public final class MethodsWriter {

    private static final double SAME_PIXEL_TOLERANCE = 0.0000001;

    public File write(File methods, Record record) throws IOException {
        if (methods == null) throw new IllegalArgumentException("methods is required");
        File parent = methods.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        File temp = File.createTempFile(tempPrefix(methods), ".tmp",
                parent == null ? new File(".") : parent);
        boolean moved = false;
        try {
            PrintWriter out = CsvSupport.newWriter(temp);
            try {
                writeRecord(out, record == null ? Record.builder().build() : record);
            } finally {
                out.close();
            }
            IoUtils.commitReplacingSmallFile(temp.toPath(), methods.toPath());
            moved = true;
            return methods;
        } finally {
            if (!moved) Files.deleteIfExists(temp.toPath());
        }
    }

    private static void writeRecord(PrintWriter out, Record record) {
        PixelSummary pixel = pixelSummary(record.panels);
        String version = ManifestWriter.text(record.pluginVersion);
        String scaleBar = scaleBarText(record.scaleBarUm);
        out.println("FIGURE PANEL BUILDER - RECORD");
        out.println("Generated: " + timestamp(record.clock)
                + "          Plugin version: " + version);
        out.println();
        out.println("Pixel size:            " + pixel.fieldText());
        out.println("Scale bar:             " + scaleBar);
        out.println("Channels:              " + channelsText(record.channelRanges));
        for (ChannelRange range : record.channelRanges) {
            out.println("Display range " + ManifestWriter.text(range.channelName)
                    + ":    " + range.range.min() + " - " + range.range.max()
                    + "   (applied identically to all "
                    + appliedCount(range.appliedImageCount) + " images)");
        }
        out.println("Contrast method:       fixed values, set once per channel; no per-image adjustment");
        out.println("Selection statistic:   " + ManifestWriter.text(record.statisticName));
        out.println("Aggregation unit:      subject (sections averaged before ranking)");
        out.println("Groups:                " + groupsText(record));
        out.println("Panels shown:          " + panelsShownText(record));
        out.println();
        out.println("SUGGESTED METHODS TEXT");
        out.println("----------------------");
        out.println(methodsParagraph(record, pixel, version, scaleBar));
    }

    private static String methodsParagraph(Record record, PixelSummary pixel,
            String version, String scaleBar) {
        StringBuilder sb = new StringBuilder();
        sb.append("Representative images were selected using Figure Panel Builder (v")
                .append(version).append("). ");
        sb.append("Display ranges were set once per channel and applied identically ");
        sb.append("to all images in the experiment");
        String rangeList = rangeList(record.channelRanges);
        if (!rangeList.isEmpty()) sb.append(" (").append(rangeList).append(")");
        sb.append(". ");
        sb.append("The selection statistic was ")
                .append(ManifestWriter.text(record.statisticName))
                .append(", aggregated by subject before ranking. ");
        sb.append("Pixel size was ").append(pixel.paragraphText()).append("; ");
        if ("not available".equals(scaleBar)) {
            sb.append("scale bar length was not available.");
        } else {
            sb.append("scale bars represent ").append(scaleBar).append(".");
        }
        return sb.toString();
    }

    private static String timestamp(Clock clock) {
        Clock safeClock = clock == null ? Clock.systemDefaultZone() : clock;
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                ZonedDateTime.now(safeClock));
    }

    private static String scaleBarText(Double scaleBarUm) {
        if (scaleBarUm == null || !Double.isFinite(scaleBarUm.doubleValue())
                || scaleBarUm.doubleValue() <= 0.0) {
            return "not available";
        }
        return trim(scaleBarUm.doubleValue()) + " um";
    }

    private static String channelsText(List<ChannelRange> ranges) {
        if (ranges == null || ranges.isEmpty()) return "not available";
        List<String> parts = new ArrayList<String>();
        for (ChannelRange range : ranges) {
            parts.add(ManifestWriter.text(range.channelName) + " ("
                    + ManifestWriter.text(range.lut) + ")");
        }
        return join(parts, ", ");
    }

    private static String rangeList(List<ChannelRange> ranges) {
        if (ranges == null || ranges.isEmpty()) return "";
        List<String> parts = new ArrayList<String>();
        for (ChannelRange range : ranges) {
            parts.add(ManifestWriter.text(range.channelName) + " "
                    + range.range.min() + "-" + range.range.max());
        }
        return join(parts, "; ");
    }

    private static String groupsText(Record record) {
        LinkedHashMap<String, LinkedHashSet<String>> groups =
                groupsFromSelection(record.selectionRecords);
        if (groups.isEmpty()) groups = groupsFromPanels(record.panels);
        if (groups.isEmpty()) return "not available";
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : groups.entrySet()) {
            parts.add(entry.getKey() + " (" + entry.getValue().size() + ")");
        }
        return join(parts, ", ");
    }

    private static LinkedHashMap<String, LinkedHashSet<String>> groupsFromSelection(
            List<SelectionRecord> records) {
        LinkedHashMap<String, LinkedHashSet<String>> out =
                new LinkedHashMap<String, LinkedHashSet<String>>();
        if (records == null) return out;
        for (SelectionRecord record : records) {
            if (record == null) continue;
            addSubject(out, record.group(), record.subject());
        }
        return out;
    }

    private static LinkedHashMap<String, LinkedHashSet<String>> groupsFromPanels(
            List<PanelRecord> panels) {
        LinkedHashMap<String, LinkedHashSet<String>> out =
                new LinkedHashMap<String, LinkedHashSet<String>>();
        if (panels == null) return out;
        for (PanelRecord panel : panels) {
            if (panel == null) continue;
            addSubject(out, panel.group(), panel.subject());
        }
        return out;
    }

    private static void addSubject(LinkedHashMap<String, LinkedHashSet<String>> groups,
            String group, String subject) {
        String groupText = ManifestWriter.text(group);
        String subjectText = ManifestWriter.text(subject);
        LinkedHashSet<String> subjects = groups.get(groupText);
        if (subjects == null) {
            subjects = new LinkedHashSet<String>();
            groups.put(groupText, subjects);
        }
        subjects.add(subjectText);
    }

    private static String panelsShownText(Record record) {
        LinkedHashMap<String, String> chosen =
                new LinkedHashMap<String, String>(record.chosenSubjects);
        if (chosen.isEmpty() && record.panels != null) {
            for (PanelRecord panel : record.panels) {
                if (panel != null && !chosen.containsKey(panel.group())) {
                    chosen.put(panel.group(), panel.subject());
                }
            }
        }
        if (chosen.isEmpty()) return "not available";
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, String> entry : chosen.entrySet()) {
            parts.add(ManifestWriter.text(entry.getKey()) + "="
                    + ManifestWriter.text(entry.getValue()));
        }
        return join(parts, ", ");
    }

    private static int appliedCount(int count) {
        return count > 0 ? count : 0;
    }

    private static PixelSummary pixelSummary(List<PanelRecord> panels) {
        if (panels == null || panels.isEmpty()) return PixelSummary.notAvailable();
        Double width = null;
        Double height = null;
        LinkedHashSet<String> sources = new LinkedHashSet<String>();
        boolean sawAvailable = false;
        boolean sawUnavailable = false;
        boolean varies = false;
        for (PanelRecord panel : panels) {
            if (panel == null) continue;
            CalibrationCheck.Result calibration = panel.calibration();
            if (!calibration.isAvailable()) {
                sawUnavailable = true;
                continue;
            }
            sawAvailable = true;
            sources.add(sourceText(calibration.source()));
            if (width == null) {
                width = Double.valueOf(calibration.pixelWidthUm());
                height = Double.valueOf(calibration.pixelHeightUm());
            } else if (different(width.doubleValue(), calibration.pixelWidthUm())
                    || different(height.doubleValue(), calibration.pixelHeightUm())) {
                varies = true;
            }
        }
        if (!sawAvailable) return PixelSummary.notAvailable();
        if (varies || sawUnavailable) return PixelSummary.varies();
        return PixelSummary.single(width.doubleValue(), height.doubleValue(),
                join(new ArrayList<String>(sources), ", "));
    }

    private static boolean different(double left, double right) {
        return Math.abs(left - right) > SAME_PIXEL_TOLERANCE;
    }

    private static String sourceText(CalibrationCheck.CalibrationSource source) {
        if (source == null || source == CalibrationCheck.CalibrationSource.NONE) {
            return "not available";
        }
        return source.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String join(List<String> values, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private static String trim(double value) {
        String text = String.format(Locale.ROOT, "%.6f", value);
        while (text.indexOf('.') >= 0 && text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) text = text.substring(0, text.length() - 1);
        return text;
    }

    private static String tempPrefix(File target) {
        String name = target == null ? "methods" : target.getName();
        String clean = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.length() < 3 ? "tmp" + clean : clean;
    }

    public static String pluginVersion() {
        Package pkg = MethodsWriter.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null || version.trim().isEmpty()
                ? "not available" : version.trim();
    }

    public static final class ChannelRange {
        private final int channelIndex;
        private final String channelName;
        private final String lut;
        private final DisplayRange range;
        private final int appliedImageCount;

        public ChannelRange(int channelIndex, String channelName, String lut,
                DisplayRange range, int appliedImageCount) {
            if (channelIndex < 0) throw new IllegalArgumentException("channelIndex is negative");
            if (range == null || !range.isValid()) {
                throw new IllegalStateException("A locked display range is required for methods.txt.");
            }
            this.channelIndex = channelIndex;
            this.channelName = ManifestWriter.text(channelName);
            this.lut = ManifestWriter.text(lut);
            this.range = range;
            this.appliedImageCount = Math.max(0, appliedImageCount);
        }

        public int channelIndex() {
            return channelIndex;
        }
    }

    public static final class Record {
        private final List<PanelRecord> panels;
        private final List<SelectionRecord> selectionRecords;
        private final List<ChannelRange> channelRanges;
        private final Map<String, String> chosenSubjects;
        private final String statisticName;
        private final Double scaleBarUm;
        private final String pluginVersion;
        private final Clock clock;

        private Record(Builder builder) {
            this.panels = immutable(builder.panels);
            this.selectionRecords = immutable(builder.selectionRecords);
            this.channelRanges = immutable(builder.channelRanges);
            this.chosenSubjects = Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(builder.chosenSubjects));
            this.statisticName = builder.statisticName;
            this.scaleBarUm = builder.scaleBarUm;
            this.pluginVersion = builder.pluginVersion == null
                    ? MethodsWriter.pluginVersion() : builder.pluginVersion;
            this.clock = builder.clock == null ? Clock.systemDefaultZone() : builder.clock;
        }

        public static Builder builder() {
            return new Builder();
        }

        private static <T> List<T> immutable(List<T> values) {
            return Collections.unmodifiableList(new ArrayList<T>(
                    values == null ? Collections.<T>emptyList() : values));
        }
    }

    public static final class Builder {
        private List<PanelRecord> panels = Collections.emptyList();
        private List<SelectionRecord> selectionRecords = Collections.emptyList();
        private List<ChannelRange> channelRanges = Collections.emptyList();
        private Map<String, String> chosenSubjects =
                Collections.emptyMap();
        private String statisticName;
        private Double scaleBarUm;
        private String pluginVersion;
        private Clock clock;

        public Builder panels(List<PanelRecord> panels) {
            this.panels = panels;
            return this;
        }

        public Builder selectionRecords(List<SelectionRecord> selectionRecords) {
            this.selectionRecords = selectionRecords;
            return this;
        }

        public Builder channelRanges(ChannelRange... ranges) {
            this.channelRanges = ranges == null ? Collections.<ChannelRange>emptyList()
                    : Arrays.asList(ranges);
            return this;
        }

        public Builder channelRanges(List<ChannelRange> channelRanges) {
            this.channelRanges = channelRanges;
            return this;
        }

        public Builder chosenSubjects(Map<String, String> chosenSubjects) {
            this.chosenSubjects = chosenSubjects == null
                    ? Collections.<String, String>emptyMap() : chosenSubjects;
            return this;
        }

        public Builder statisticName(String statisticName) {
            this.statisticName = statisticName;
            return this;
        }

        public Builder scaleBarUm(Double scaleBarUm) {
            this.scaleBarUm = scaleBarUm;
            return this;
        }

        public Builder pluginVersion(String pluginVersion) {
            this.pluginVersion = pluginVersion;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Record build() {
            return new Record(this);
        }
    }

    private static final class PixelSummary {
        private final String fieldText;
        private final String paragraphText;

        private PixelSummary(String fieldText, String paragraphText) {
            this.fieldText = fieldText;
            this.paragraphText = paragraphText;
        }

        static PixelSummary notAvailable() {
            return new PixelSummary("not available", "not available");
        }

        static PixelSummary varies() {
            return new PixelSummary("varies by panel (see manifest)",
                    "listed by panel in the manifest");
        }

        static PixelSummary single(double width, double height, String source) {
            String value = trim(width) + " x " + trim(height)
                    + " um   (source: " + ManifestWriter.text(source) + ")";
            return new PixelSummary(value, trim(width) + " x " + trim(height) + " um");
        }

        String fieldText() {
            return fieldText;
        }

        String paragraphText() {
            return paragraphText;
        }
    }
}
