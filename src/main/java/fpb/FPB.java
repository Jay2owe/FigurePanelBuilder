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
        if (parameters.quickGrid()) {
            return quickGrid(parameters, progress);
        }
        ImageLoader.LoadResult loaded = new ImageLoader(150, 4)
                .loadFolder(parameters.folder(), parameters.recursive(),
                        ImageLoader.ZMode.fromString(parameters.zMode()), progress);
        List<ImageSource> sources = sources(loaded.planeCache());
        MetadataTable table = metadataTable(parameters, sources);
        if (table.unassignedCount() > 0) {
            throw new IOException("Metadata table has " + table.unassignedCount()
                    + " unassigned rows.");
        }
        validateCalibrationOverrides(parameters, table);

        List<ChannelRail.ChannelSpec> specs = channelSpecs(parameters);
        Statistic.ImageValues imageValues = imageValues(parameters, table,
                loaded.histogramCache(), specs);
        SubjectAggregator.SubjectStats subjectStats =
                SubjectAggregator.aggregate(table, imageValues);
        GroupStats groupStats = GroupStats.from(subjectStats);
        Map<String, Suggestion.Result> suggestions = Suggestion.suggest(groupStats);
        List<SelectionRecord> selectionRecords =
                SelectionRecord.from(subjectStats, groupStats, suggestions);
        List<FPBRenderer.ChannelRequest> requests = channelRequests(parameters);
        Step3Chooser.Data data = new Step3Chooser.Data(table, loaded.planeCache(),
                loaded.histogramCache(), specs, subjectStats, suggestions,
                selectionRecords);
        LinkedHashMap<String, RowImage.SubjectRow> selected =
                selectedRows(parameters, data);
        PanelConfig config = panelConfig(parameters, requests, selected.keySet());
        return new FPBResult(parameters, table, data, selected, requests, config,
                FPBResult.Route.GUIDED);
    }

    private static FPBResult quickGrid(FPBParameters parameters,
            ProgressCallback progress) throws IOException {
        QuickGrid.Result quick = QuickGrid.run(parameters.folder(),
                parameters.recursive(),
                ImageLoader.ZMode.fromString(parameters.zMode()), progress);
        validateCalibrationOverrides(parameters, quick.table());
        PanelConfig config = parameters.panelConfig() == null
                ? quick.panelConfig().toBuilder()
                        .scaleBarLengthUm(parameters.scaleBarUm())
                        .scaleBarPosition(parameters.scaleBarCorner())
                        .outputDpi(parameters.dpi())
                        .exportScale(parameters.exportScale())
                        .build()
                : parameters.panelConfig().toBuilder()
                        .outputDpi(parameters.dpi())
                        .exportScale(parameters.exportScale())
                        .build();
        return new FPBResult(parameters, quick.table(), quick.chooserData(),
                quick.selectedRowsByGroup(), quick.channelRequests(), config,
                FPBResult.Route.QUICK_GRID);
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
                p.writeRecords(), p.writeAllProjectPng(),
                p.writeAllProjectTiffStacks());
        return Step5Export.export(result.toContext(), settings, null,
                Step5Export.NONE);
    }

    public static Step5Export.ExportResult runAndWrite(FPBParameters parameters)
            throws IOException {
        return write(run(parameters));
    }

    private static MetadataTable metadataTable(FPBParameters parameters,
            List<ImageSource> sources) throws IOException {
        MetadataTable table = MetadataTable.emptySources(parameters.folder(), sources);
        if (parameters.metadataMode() == FPBParameters.MetadataMode.SUBFOLDER) {
            new SubfolderStrategy().apply(table);
            return table;
        }
        if (parameters.metadataMode() == FPBParameters.MetadataMode.CSV) {
            MetadataTableIO.ImportResult imported =
                    MetadataTableIO.importCsv(table, parameters.metadataCsv());
            if (!imported.isComplete()) throw new IOException(imported.problemSummary());
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
            MetadataTable table, HistogramCache histograms,
            List<ChannelRail.ChannelSpec> specs) throws IOException {
        if (parameters.statisticCsv() != null
                || parameters.statisticColumn().length() > 0) {
            StatCsvLoader.LoadResult result = StatCsvLoader.load(
                    parameters.statisticCsv(), parameters.statisticColumn(), table);
            if (!result.isComplete()) {
                throw new IOException("Statistic CSV must match every input exactly once; "
                        + result.problemSummary());
            }
            return result.imageValues();
        }
        List<Integer> indices = new ArrayList<Integer>();
        List<String> names = new ArrayList<String>();
        for (ChannelRail.ChannelSpec spec : specs) {
            indices.add(Integer.valueOf(spec.channelIndex()));
            names.add(spec.name());
        }
        return Statistic.brightestOnePercentMeans(histograms, indices, names,
                ImageLoader.ZMode.fromString(parameters.zMode()));
    }

    private static List<ImageSource> sources(PlaneCache planes) {
        List<ImageSource> sources = new ArrayList<ImageSource>();
        for (PlaneCache.ImagePlanes image : planes.images()) {
            sources.add(image.source());
        }
        return Collections.unmodifiableList(sources);
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
        Map<String, List<Integer>> images = imageIndicesByGroupSubject(data.table());
        for (String group : data.subjectStats().groups()) {
            String pickedImage = pickValue(parameters.pickImages(), group);
            String subject = parameters.picks().get(group);
            if (subject == null || subject.trim().length() == 0) {
                subject = parameters.picks().get(FPBMacroOptions.optionSuffix(group));
            }
            if ((subject == null || subject.trim().length() == 0)
                    && (pickedImage == null || pickedImage.trim().length() == 0)) {
                throw new IOException("pick_" + FPBMacroOptions.optionSuffix(group)
                        + " is required for group " + group + ".");
            }
            if (pickedImage != null && pickedImage.trim().length() > 0) {
                int imageIndex = imageIndex(data.table(), group, pickedImage);
                if (imageIndex < 0) {
                    throw new IOException("Picked image " + pickedImage
                            + " was not found in group " + group + ".");
                }
                fpb.meta.MetadataRow metadata = data.table().rows().get(imageIndex);
                Suggestion.Result suggestion = data.suggestions().get(group);
                boolean suggested = suggestion != null
                        && suggestion.isSuggested(metadata.subject);
                String imageId = data.table().csvFileName(metadata);
                selected.put(group, new RowImage.SubjectRow(group, metadata.subject,
                        metadata.section, imageIndex, suggested, null, imageId,
                        parameters.panelConfig() == null
                                ? ImageOrientation.IDENTITY
                                : parameters.panelConfig().imageOrientation(imageId)));
                continue;
            }
            List<Integer> indices = images.get(key(group, subject));
            if (indices == null || indices.isEmpty()) {
                throw new IOException("Pick " + subject + " was not found in group "
                        + group + ".");
            }
            Suggestion.Result suggestion = data.suggestions().get(group);
            boolean suggested = suggestion != null && suggestion.isSuggested(subject);
            selected.put(group, new RowImage.SubjectRow(group, subject,
                    indices, suggested, null));
        }
        return selected;
    }

    private static String pickValue(Map<String, String> values, String group) {
        if (values == null) return null;
        String value = values.get(group);
        if (value == null || value.trim().isEmpty()) {
            value = values.get(FPBMacroOptions.optionSuffix(group));
        }
        return value;
    }

    private static int imageIndex(MetadataTable table, String group,
            String sourceImageId) {
        String wanted = sourceImageId == null ? ""
                : sourceImageId.trim().replace('\\', '/');
        for (int i = 0; i < table.rows().size(); i++) {
            fpb.meta.MetadataRow row = table.rows().get(i);
            if (group.equals(row.group)
                    && wanted.equals(table.csvFileName(row).replace('\\', '/'))) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, List<Integer>> imageIndicesByGroupSubject(
            MetadataTable table) {
        LinkedHashMap<String, List<Integer>> map =
                new LinkedHashMap<String, List<Integer>>();
        List<MetadataRow> rows = table.rows();
        for (int i = 0; i < rows.size(); i++) {
            MetadataRow row = rows.get(i);
            String key = key(row.group, row.subject);
            List<Integer> indices = map.get(key);
            if (indices == null) {
                indices = new ArrayList<Integer>();
                map.put(key, indices);
            }
            indices.add(Integer.valueOf(i));
        }
        return map;
    }

    private static PanelConfig panelConfig(FPBParameters parameters,
            List<FPBRenderer.ChannelRequest> requests, Iterable<String> groups) {
        if (parameters.panelConfig() != null) {
            return parameters.panelConfig().toBuilder()
                    .outputDpi(parameters.dpi())
                    .exportScale(parameters.exportScale())
                    .build();
        }
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

    private static void validateCalibrationOverrides(FPBParameters parameters,
            MetadataTable table) {
        if (parameters.calibrationOverrides().isEmpty()) return;
        java.util.LinkedHashSet<String> imageIds =
                new java.util.LinkedHashSet<String>();
        for (MetadataRow row : table.rows()) {
            imageIds.add(FPBParameters.normalizeImageId(table.csvFileName(row)));
        }
        for (String imageId : parameters.calibrationOverrides().keySet()) {
            if (!imageIds.contains(imageId)) {
                throw new IllegalArgumentException("Calibration SourceImageId was not found: "
                        + imageId);
            }
        }
    }

    private static String key(String group, String subject) {
        return String.valueOf(group) + "\u001f" + String.valueOf(subject);
    }
}
