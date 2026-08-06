/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.record;

import fpb.stats.GroupQuantification;
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

/** Writes auditable section values, per-channel z scores, and group summaries. */
public final class QuantificationWriter {

    public static final List<String> COLUMNS = Collections.unmodifiableList(
            Arrays.asList("ChannelIndex", "ChannelName", "StatisticName",
                    "Group", "Subject", "Section", "SourceImage",
                    "SectionValue", "ZScore", "GroupN", "GroupMean",
                    "GroupSD", "GroupSEM", "GroupMin", "GroupMax",
                    "GroupZMean", "OverallN", "OverallMean", "OverallSD",
                    "Chosen"));

    public File write(File output, GroupQuantification quantification,
            Map<String, Integer> chosenSections) throws IOException {
        if (output == null) throw new IllegalArgumentException("output is required");
        if (quantification == null) {
            throw new IllegalArgumentException("quantification is required");
        }
        File parent = output.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        File temp = File.createTempFile(prefix(output), ".tmp",
                parent == null ? new File(".") : parent);
        boolean moved = false;
        try {
            PrintWriter writer = CsvSupport.newWriter(temp);
            try {
                writer.println(CsvSupport.joinRow(COLUMNS));
                for (GroupQuantification.ChannelData channel
                        : quantification.channels()) {
                    for (GroupQuantification.GroupData group : channel.groups()) {
                        Integer chosen = chosenSections == null
                                ? null : chosenSections.get(group.group());
                        for (GroupQuantification.SectionValue section
                                : group.sections()) {
                            writer.println(CsvSupport.joinRow(Arrays.asList(
                                    String.valueOf(channel.channelIndex()),
                                    ManifestWriter.text(channel.channelName()),
                                    ManifestWriter.text(quantification.statisticName()),
                                    ManifestWriter.text(group.group()),
                                    ManifestWriter.text(section.subject()),
                                    ManifestWriter.text(section.section()),
                                    ManifestWriter.text(section.sourceLabel()),
                                    ManifestWriter.number(section.rawValue()),
                                    ManifestWriter.number(section.zScore()),
                                    String.valueOf(group.sectionCount()),
                                    ManifestWriter.number(group.mean()),
                                    ManifestWriter.number(group.standardDeviation()),
                                    ManifestWriter.number(group.standardError()),
                                    ManifestWriter.number(group.minimum()),
                                    ManifestWriter.number(group.maximum()),
                                    ManifestWriter.number(group.zMean()),
                                    String.valueOf(channel.overallCount()),
                                    ManifestWriter.number(channel.overallMean()),
                                    ManifestWriter.number(
                                            channel.overallStandardDeviation()),
                                    chosen != null && chosen.intValue()
                                            == section.imageIndex()
                                            ? "yes" : "no")));
                        }
                    }
                }
                CsvSupport.requireNoError(writer, temp);
            } finally {
                writer.close();
            }
            IoUtils.commitReplacingSmallFile(temp.toPath(), output.toPath());
            moved = true;
            return output;
        } finally {
            if (!moved) Files.deleteIfExists(temp.toPath());
        }
    }

    private static String prefix(File output) {
        String clean = output.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.length() < 3 ? "tmp" + clean : clean;
    }
}
