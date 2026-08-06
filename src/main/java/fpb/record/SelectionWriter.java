/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.record;

import fpb.stats.SelectionRecord;
import fpb.util.CsvSupport;
import fpb.util.IoUtils;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Writes selection.csv, one row per subject per channel. */
public final class SelectionWriter {

    public static final List<String> COLUMNS = Collections.unmodifiableList(Arrays.asList(
            "Group", "Subject", "SectionCount", "ChannelName", "StatisticName",
            "Value", "GroupMean", "Deviation", "AggregationUnit", "Suggested",
            "Chosen"));

    public File write(File selection, List<SelectionRecord> records,
            String statisticName, Map<String, String> chosenSubjects)
            throws IOException {
        if (selection == null) throw new IllegalArgumentException("selection is required");
        File parent = selection.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        File temp = File.createTempFile(tempPrefix(selection), ".tmp",
                parent == null ? new File(".") : parent);
        boolean moved = false;
        try {
            PrintWriter out = CsvSupport.newWriter(temp);
            try {
                out.println(CsvSupport.joinRow(COLUMNS));
                if (records != null) {
                    for (SelectionRecord record : records) {
                        if (record == null) continue;
                        out.println(CsvSupport.joinRow(Arrays.asList(
                                ManifestWriter.text(record.group()),
                                ManifestWriter.text(record.subject()),
                                String.valueOf(record.sectionCount()),
                                ManifestWriter.text(record.channelName()),
                                ManifestWriter.text(statisticName),
                                ManifestWriter.number(record.value()),
                                ManifestWriter.number(record.groupMean()),
                                ManifestWriter.number(record.deviation()),
                                "subject",
                                yesNo(record.suggested()),
                                yesNo(isChosen(record, chosenSubjects)))));
                    }
                }
                CsvSupport.requireNoError(out, temp);
            } finally {
                out.close();
            }
            IoUtils.commitReplacingSmallFile(temp.toPath(), selection.toPath());
            moved = true;
            return selection;
        } finally {
            if (!moved) Files.deleteIfExists(temp.toPath());
        }
    }

    private static boolean isChosen(SelectionRecord record,
            Map<String, String> chosenSubjects) {
        if (chosenSubjects == null) return false;
        String chosen = chosenSubjects.get(record.group());
        return chosen != null && chosen.equals(record.subject());
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private static String tempPrefix(File target) {
        String name = target == null ? "selection" : target.getName();
        String clean = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.length() < 3 ? "tmp" + clean : clean;
    }
}
