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
import fpb.render.ClipReport;
import fpb.render.DisplayRange;
import fpb.util.CsvSupport;
import fpb.util.IoUtils;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Writes manifest.csv, one row per exported panel. */
public final class ManifestWriter {

    public static final List<String> COLUMNS = Collections.unmodifiableList(Arrays.asList(
            "Group", "Subject", "Section", "SourceFile", "SourceImageId",
            "ZMode", "ChannelIndex", "ChannelName", "LUT", "PanelFile", "WidthPx",
            "HeightPx", "PixelWidthUm", "PixelHeightUm", "CalibrationSource",
            "DisplayMin", "DisplayMax", "RangeSource", "ClippedLowPct",
            "ClippedHighPct", "StatisticName", "StatisticValue", "GroupMean",
            "GroupRank", "SuggestedSubject", "ChosenSubject",
            "SelectionMethod", "Grouping"));

    public File write(File manifest, List<Row> rows) throws IOException {
        if (manifest == null) throw new IllegalArgumentException("manifest is required");
        File parent = manifest.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        File temp = File.createTempFile(tempPrefix(manifest), ".tmp",
                parent == null ? new File(".") : parent);
        boolean moved = false;
        try {
            PrintWriter out = CsvSupport.newWriter(temp);
            try {
                out.println(CsvSupport.joinRow(COLUMNS));
                for (Row row : safeRows(rows)) {
                    out.println(CsvSupport.joinRow(row.fields()));
                }
                CsvSupport.requireNoError(out, temp);
            } finally {
                out.close();
            }
            IoUtils.commitReplacingSmallFile(temp.toPath(), manifest.toPath());
            moved = true;
            return manifest;
        } finally {
            if (!moved) Files.deleteIfExists(temp.toPath());
        }
    }

    private static List<Row> safeRows(List<Row> rows) {
        if (rows == null) return Collections.emptyList();
        List<Row> out = new ArrayList<Row>();
        for (Row row : rows) {
            if (row != null) out.add(row);
        }
        return out;
    }

    private static String tempPrefix(File target) {
        String name = target == null ? "manifest" : target.getName();
        String clean = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.length() < 3 ? "tmp" + clean : clean;
    }

    public static final class Row {
        private final PanelRecord panel;
        private final String zMode;
        private final String lut;
        private final File panelFile;
        private final DisplayRange displayRange;
        private final double clippedLowPct;
        private final double clippedHighPct;
        private final String rangeSource;
        private final String statisticName;
        private final double statisticValue;
        private final double groupMean;
        private final int groupRank;
        private final String suggestedSubject;
        private final String chosenSubject;
        private final String selectionMethod;
        private final String grouping;

        public Row(PanelRecord panel, String lut, File panelFile,
                DisplayRange displayRange, ClipReport.ChannelClip clip,
                String statisticName, double statisticValue, double groupMean,
                int groupRank, String suggestedSubject, String chosenSubject) {
            this(panel, lut, panelFile, displayRange, clip, "locked",
                    statisticName, statisticValue, groupMean, groupRank,
                    suggestedSubject, chosenSubject, "representative", "metadata",
                    "not available");
        }

        public Row(PanelRecord panel, String lut, File panelFile,
                DisplayRange displayRange, ClipReport.ChannelClip clip,
                String rangeSource, String statisticName, double statisticValue,
                double groupMean, int groupRank, String suggestedSubject,
                String chosenSubject, String selectionMethod, String grouping) {
            this(panel, lut, panelFile, displayRange, clip, rangeSource,
                    statisticName, statisticValue, groupMean, groupRank,
                    suggestedSubject, chosenSubject, selectionMethod, grouping,
                    "not available");
        }

        public Row(PanelRecord panel, String lut, File panelFile,
                DisplayRange displayRange, ClipReport.ChannelClip clip,
                String rangeSource, String statisticName, double statisticValue,
                double groupMean, int groupRank, String suggestedSubject,
                String chosenSubject, String selectionMethod, String grouping,
                String zMode) {
            if (panel == null) throw new IllegalArgumentException("panel is required");
            if (panel.channelIndex() >= 0
                    && (displayRange == null || !displayRange.isValid())) {
                throw new IllegalStateException("A locked display range is required for manifest.csv.");
            }
            this.panel = panel;
            this.zMode = valueOrNotAvailable(zMode);
            this.lut = valueOrNotAvailable(lut);
            this.panelFile = panelFile == null ? null : panelFile.getAbsoluteFile();
            this.displayRange = displayRange;
            this.clippedLowPct = clip == null ? Double.NaN : clip.lowPercent();
            this.clippedHighPct = clip == null ? Double.NaN : clip.highPercent();
            this.rangeSource = valueOrNotAvailable(rangeSource);
            this.statisticName = valueOrNotAvailable(statisticName);
            this.statisticValue = statisticValue;
            this.groupMean = groupMean;
            this.groupRank = groupRank;
            this.suggestedSubject = valueOrNotAvailable(suggestedSubject);
            this.chosenSubject = valueOrNotAvailable(chosenSubject);
            this.selectionMethod = valueOrNotAvailable(selectionMethod);
            this.grouping = valueOrNotAvailable(grouping);
        }

        private List<String> fields() {
            return Arrays.asList(
                    panel.group(),
                    panel.subject(),
                    panel.section(),
                    fileValue(panel.sourceFile()),
                    text(panel.imageId()),
                    zMode,
                    String.valueOf(panel.channelIndex()),
                    text(panel.channelName()),
                    lut,
                    fileValue(panelFile),
                    String.valueOf(panel.widthPx()),
                    String.valueOf(panel.heightPx()),
                    number(panel.pixelWidthUm()),
                    number(panel.pixelHeightUm()),
                    calibrationSource(panel.calibrationSource()),
                    displayRange == null ? "not available"
                            : String.valueOf(displayRange.min()),
                    displayRange == null ? "not available"
                            : String.valueOf(displayRange.max()),
                    rangeSource,
                    number(clippedLowPct),
                    number(clippedHighPct),
                    statisticName,
                    number(statisticValue),
                    number(groupMean),
                    groupRank > 0 ? String.valueOf(groupRank) : "not available",
                    suggestedSubject,
                    chosenSubject,
                    selectionMethod,
                    grouping);
        }
    }

    static String number(double value) {
        return Double.isFinite(value) ? String.valueOf(value) : "not available";
    }

    static String text(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.isEmpty() ? "not available" : clean;
    }

    private static String fileValue(File file) {
        return file == null ? "not available" : file.getAbsolutePath();
    }

    private static String valueOrNotAvailable(String value) {
        return text(value);
    }

    private static String calibrationSource(CalibrationCheck.CalibrationSource source) {
        if (source == null || source == CalibrationCheck.CalibrationSource.NONE) {
            return "not available";
        }
        return source.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
