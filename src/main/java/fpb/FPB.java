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
import fpb.io.HistogramCache;
import fpb.io.ImageLoader;
import fpb.io.PlaneCache;
import fpb.io.ProgressCallback;
import fpb.meta.MetadataRow;
import fpb.meta.MetadataTable;
import fpb.meta.MetadataTableIO;
import fpb.meta.RegexStrategy;
import fpb.meta.SubfolderStrategy;
import fpb.meta.TokenStrategy;
import fpb.render.FPBRenderer;
import fpb.stats.GroupStats;
import fpb.stats.SelectionRecord;
import fpb.stats.StatCsvLoader;
import fpb.stats.Statistic;
import fpb.stats.SubjectAggregator;
import fpb.stats.Suggestion;
import fpb.ui.Step5Export;
import fpb.ui.chooser.ChannelRail;
import fpb.ui.chooser.RowImage;
import fpb.ui.chooser.Step3Chooser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Public Java facade for running Figure Panel Builder without dialogs. */
public final class FPB {

    private FPB() {}

    public static FPBResult run(FPBParameters parameters) throws IOException {
        return run(parameters, ProgressCallback.NONE);
    }

    public static FPBResult run(FPBParameters parameters,
            ProgressCallback progress) throws IOException {
        FPBParameters.validate(parameters);
        ImageLoader.LoadResult loaded = new ImageLoader(150, 4)
                .loadFolder(parameters.folder(), parameters.recursive(), progress);
        List<File> files = sourceFiles(loaded.planeCache());
        MetadataTable table = metadataTable(parameters, files);
        if (table.unassignedCount() > 0) {
            throw new IOException("Metadata table has " + table.unassignedCount()
                    + " unassigned rows.");
        }

        Statistic.ImageValues imageValues = imageValues(parameters, table,
                loaded.histogramCache());
        SubjectAggregator.SubjectStats subjectStats =
                SubjectAggregator.aggregate(table, imageValues);
        GroupStats groupStats = GroupStats.from(subjectStats);
        Map<String, Suggestion.Result> suggestions = Suggestion.suggest(groupStats);
        List<SelectionRecord> selectionRecords =
                SelectionRecord.from(subjectStats, groupStats, suggestions);
        List<ChannelRail.ChannelSpec> specs = channelSpecs(parameters);
        List<FPBRenderer.ChannelRequest> requests = channelRequests(parameters);
        Step3Chooser.Data data = new Step3Chooser.Data(table, loaded.planeCache(),
                loaded.histogramCache(), specs, subjectStats, suggestions,
                selectionRecords);
        LinkedHashMap<String, RowImage.SubjectRow> selected =
                selectedRows(parameters, data);
        PanelConfig config = panelConfig(parameters, requests, selected.keySet());
        return new FPBResult(parameters, table, data, selected, requests, config);
    }

    public static Step5Export.ExportResult write(FPBResult result) throws IOException {
        if (result == null) throw new IllegalArgumentException("result is required");
        File output = result.parameters().outputFolder();
        if (output == null) {
            throw new IllegalArgumentException("Output folder is required to write files.");
        }
        return write(result, output);
    }

    public static Step5Export.ExportResult write(FPBResult result, File outputFolder)
            throws IOException {
        if (result == null) throw new IllegalArgumentException("result is required");
        if (outputFolder == null) {
            throw new IllegalArgumentException("outputFolder is required");
        }
        FPBParameters p = result.parameters();
        Step5Export.Settings settings = new Step5Export.Settings(outputFolder,
                p.figureName(), p.dpi(), p.exportScale(), p.writePng(),
                p.writeTiff(), p.writeSvg(), p.writeIndividualPanels(),
                p.writeRecords());
        return Step5Export.export(result.toContext(), settings, null,
                Step5Export.NONE);
    }

    public static Step5Export.ExportResult runAndWrite(FPBParameters parameters)
            throws IOException {
        return write(run(parameters));
    }

    private static MetadataTable metadataTable(FPBParameters parameters,
            List<File> files) throws IOException {
        MetadataTable table = MetadataTable.empty(parameters.folder(), files);
        if (parameters.metadataMode() == FPBParameters.MetadataMode.SUBFOLDER) {
            new SubfolderStrategy().apply(table);
            return table;
        }
        if (parameters.metadataMode() == FPBParameters.MetadataMode.CSV) {
            MetadataTableIO.importCsv(table, parameters.metadataCsv());
            return table;
        }
        if (parameters.metadataMode() == FPBParameters.MetadataMode.REGEX) {
            new RegexStrategy(parameters.groupRegex(), parameters.groupCapture(),
                    parameters.subjectCapture(), parameters.sectionCapture())
                    .apply(table);
            return table;
        }

        Map<Integer, TokenStrategy.Field> assignment =
                new LinkedHashMap<Integer, TokenStrategy.Field>();
        assignment.put(Integer.valueOf(parameters.groupToken() - 1),
                TokenStrategy.Field.GROUP);
        if (parameters.subjectToken() > 0) {
            assignment.put(Integer.valueOf(parameters.subjectToken() - 1),
                    TokenStrategy.Field.SUBJECT);
        }
        if (parameters.sectionToken() > 0) {
            assignment.put(Integer.valueOf(parameters.sectionToken() - 1),
                    TokenStrategy.Field.SECTION);
        }
        new TokenStrategy(parameters.separator(), assignment).apply(table);
        return table;
    }

    private static Statistic.ImageValues imageValues(FPBParameters parameters,
            MetadataTable table, HistogramCache histograms) throws IOException {
        if (parameters.statisticCsv() != null
                || parameters.statisticColumn().length() > 0) {
            return StatCsvLoader.load(parameters.statisticCsv(),
                    parameters.statisticColumn(), table).imageValues();
        }
        return Statistic.brightestOnePercentMeans(histograms);
    }

    private static List<File> sourceFiles(PlaneCache planes) {
        List<File> files = new ArrayList<File>();
        for (PlaneCache.ImagePlanes image : planes.images()) {
            files.add(image.sourceFile());
        }
        return Collections.unmodifiableList(files);
    }

    private static List<ChannelRail.ChannelSpec> channelSpecs(FPBParameters parameters) {
        List<ChannelRail.ChannelSpec> specs =
                new ArrayList<ChannelRail.ChannelSpec>();
        for (FPBParameters.Channel channel : parameters.channels()) {
            specs.add(new ChannelRail.ChannelSpec(channel.channelIndex(),
                    channel.name(), channel.colour()));
        }
        return Collections.unmodifiableList(specs);
    }

    private static List<FPBRenderer.ChannelRequest> channelRequests(
            FPBParameters parameters) {
        List<FPBRenderer.ChannelRequest> requests =
                new ArrayList<FPBRenderer.ChannelRequest>();
        for (FPBParameters.Channel channel : parameters.channels()) {
            requests.add(new FPBRenderer.ChannelRequest(channel.channelIndex(),
                    channel.name(), channel.colour(), channel.range()));
        }
        return Collections.unmodifiableList(requests);
    }

    private static LinkedHashMap<String, RowImage.SubjectRow> selectedRows(
            FPBParameters parameters, Step3Chooser.Data data) throws IOException {
        LinkedHashMap<String, RowImage.SubjectRow> selected =
                new LinkedHashMap<String, RowImage.SubjectRow>();
        Map<String, Integer> firstImage = firstImageIndexByGroupSubject(data.table());
        for (String group : data.subjectStats().groups()) {
            String subject = parameters.picks().get(group);
            if (subject == null || subject.trim().length() == 0) {
                subject = parameters.picks().get(FPBMacroOptions.optionSuffix(group));
            }
            if (subject == null || subject.trim().length() == 0) {
                throw new IOException("pick_" + FPBMacroOptions.optionSuffix(group)
                        + " is required for group " + group + ".");
            }
            Integer index = firstImage.get(key(group, subject));
            if (index == null) {
                throw new IOException("Pick " + subject + " was not found in group "
                        + group + ".");
            }
            Suggestion.Result suggestion = data.suggestions().get(group);
            boolean suggested = suggestion != null && suggestion.isSuggested(subject);
            selected.put(group, new RowImage.SubjectRow(group, subject,
                    index.intValue(), suggested));
        }
        return selected;
    }

    private static Map<String, Integer> firstImageIndexByGroupSubject(
            MetadataTable table) {
        LinkedHashMap<String, Integer> map = new LinkedHashMap<String, Integer>();
        List<MetadataRow> rows = table.rows();
        for (int i = 0; i < rows.size(); i++) {
            MetadataRow row = rows.get(i);
            String key = key(row.group, row.subject);
            if (!map.containsKey(key)) map.put(key, Integer.valueOf(i));
        }
        return map;
    }

    private static PanelConfig panelConfig(FPBParameters parameters,
            List<FPBRenderer.ChannelRequest> requests, Iterable<String> groups) {
        List<String> channels = new ArrayList<String>();
        for (FPBRenderer.ChannelRequest request : requests) channels.add(request.name());
        channels.add("Merge");
        List<String> orderedGroups = new ArrayList<String>();
        for (String group : groups) orderedGroups.add(group);
        return PanelConfig.builder()
                .createOverviewPanel(true)
                .annotateOverviewPanel(true)
                .channelOrder(channels)
                .cellSizePx(220)
                .scaleBarLengthUm(parameters.scaleBarUm())
                .scaleBarPosition(parameters.scaleBarCorner())
                .outputDpi(parameters.dpi())
                .exportScale(parameters.exportScale())
                .groupLayoutRows(Collections.singletonList(orderedGroups))
                .build();
    }

    private static String key(String group, String subject) {
        return String.valueOf(group) + "\u001f" + String.valueOf(subject);
    }
}
