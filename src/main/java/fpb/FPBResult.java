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
import fpb.meta.MetadataTable;
import fpb.meta.MetadataRow;
import fpb.render.FPBRenderer;
import fpb.stats.SelectionRecord;
import fpb.ui.FPBWizard;
import fpb.ui.chooser.RowImage;
import fpb.ui.chooser.Step3Chooser;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result bundle returned by the headless Figure Panel Builder API. */
public final class FPBResult {

    enum Route {
        GUIDED,
        QUICK_GRID
    }

    private final FPBParameters parameters;
    private final MetadataTable metadataTable;
    private final Step3Chooser.Data chooserData;
    private final LinkedHashMap<String, RowImage.SubjectRow> selectedRowsByGroup;
    private final List<FPBRenderer.ChannelRequest> channelRequests;
    private final PanelConfig panelConfig;
    private final Route route;

    FPBResult(FPBParameters parameters, MetadataTable metadataTable,
            Step3Chooser.Data chooserData,
            LinkedHashMap<String, RowImage.SubjectRow> selectedRowsByGroup,
            List<FPBRenderer.ChannelRequest> channelRequests, PanelConfig panelConfig,
            Route route) {
        this.parameters = parameters;
        this.metadataTable = metadataTable;
        this.chooserData = chooserData;
        this.selectedRowsByGroup =
                new LinkedHashMap<String, RowImage.SubjectRow>(selectedRowsByGroup);
        this.channelRequests = Collections.unmodifiableList(
                new ArrayList<FPBRenderer.ChannelRequest>(channelRequests));
        this.panelConfig = panelConfig;
        this.route = route == null ? Route.GUIDED : route;
    }

    public FPBParameters parameters() { return parameters; }
    public MetadataTable metadataTable() { return metadataTable; }
    public Step3Chooser.Data chooserData() { return chooserData; }
    public List<FPBRenderer.ChannelRequest> channelRequests() { return channelRequests; }
    public PanelConfig panelConfig() { return panelConfig; }

    /** Immutable selection evidence used for representative-subject ranking. */
    public List<SelectionRecord> selection() {
        return route == Route.QUICK_GRID || chooserData == null
                ? Collections.<SelectionRecord>emptyList()
                : chooserData.selectionRecords();
    }

    /** The exact selected source/channel rows that a subsequent write will export. */
    public List<ManifestEntry> manifest() {
        List<ManifestEntry> entries = new ArrayList<ManifestEntry>();
        if (chooserData == null) return Collections.unmodifiableList(entries);
        for (Map.Entry<String, RowImage.SubjectRow> selected
                : selectedRowsByGroup.entrySet()) {
            for (Integer imageIndex : selected.getValue().imageIndices()) {
                int index = imageIndex.intValue();
                MetadataRow row = metadataTable.rows().get(index);
                File source = chooserData.planes().image(index).sourceFile();
                for (FPBRenderer.ChannelRequest channel : channelRequests) {
                    entries.add(new ManifestEntry(source, row.group, row.subject,
                            row.section, channel.channelIndex(), channel.name()));
                }
                entries.add(new ManifestEntry(source, row.group, row.subject,
                        row.section, -1, "Merge"));
            }
        }
        return Collections.unmodifiableList(entries);
    }

    public Map<String, String> selectedSubjects() {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, RowImage.SubjectRow> entry : selectedRowsByGroup.entrySet()) {
            out.put(entry.getKey(), entry.getValue().subject());
        }
        return Collections.unmodifiableMap(out);
    }

    FPBWizard.Context toContext() {
        FPBWizard.Context context = new FPBWizard.Context();
        context.folder = parameters.folder();
        context.recursive = parameters.recursive();
        context.zHandling = parameters.zMode();
        context.statistic = parameters.statistic();
        context.statisticCsv = parameters.statisticCsv();
        context.statisticColumn = parameters.statisticColumn();
        context.calibrationOverrides = new LinkedHashMap<String,
                fpb.figure.CalibrationOverride>(
                        parameters.calibrationOverrides());
        context.quickGridRequested = route == Route.QUICK_GRID;
        context.metadataTable = metadataTable;
        context.chooserData = chooserData;
        context.selectedRowsByGroup =
                new LinkedHashMap<String, RowImage.SubjectRow>(selectedRowsByGroup);
        context.layoutChannelRequests =
                new ArrayList<FPBRenderer.ChannelRequest>(channelRequests);
        context.panelConfig = panelConfig;
        for (Map.Entry<String, String> entry
                : panelConfig.imageOrientations().entrySet()) {
            context.imageOrientations.put(entry.getKey(),
                    ImageOrientation.fromToken(entry.getValue()));
        }
        context.groupLayoutRows = panelConfig.groupLayoutRows();
        return context;
    }

    /** One planned output row exposed before filesystem export. */
    public static final class ManifestEntry {
        private final File sourceFile;
        private final String group;
        private final String subject;
        private final String section;
        private final int channelIndex;
        private final String channelName;

        private ManifestEntry(File sourceFile, String group, String subject,
                String section, int channelIndex, String channelName) {
            this.sourceFile = sourceFile == null ? null : sourceFile.getAbsoluteFile();
            this.group = group;
            this.subject = subject;
            this.section = section;
            this.channelIndex = channelIndex;
            this.channelName = channelName;
        }

        public File sourceFile() { return sourceFile; }
        public String group() { return group; }
        public String subject() { return subject; }
        public String section() { return section; }
        public int channelIndex() { return channelIndex; }
        public String channelName() { return channelName; }
    }
}
