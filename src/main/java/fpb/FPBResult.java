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
import fpb.meta.MetadataTable;
import fpb.render.FPBRenderer;
import fpb.ui.FPBWizard;
import fpb.ui.chooser.RowImage;
import fpb.ui.chooser.Step3Chooser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result bundle returned by the headless Figure Panel Builder API. */
public final class FPBResult {

    private final FPBParameters parameters;
    private final MetadataTable metadataTable;
    private final Step3Chooser.Data chooserData;
    private final LinkedHashMap<String, RowImage.SubjectRow> selectedRowsByGroup;
    private final List<FPBRenderer.ChannelRequest> channelRequests;
    private final PanelConfig panelConfig;

    FPBResult(FPBParameters parameters, MetadataTable metadataTable,
            Step3Chooser.Data chooserData,
            LinkedHashMap<String, RowImage.SubjectRow> selectedRowsByGroup,
            List<FPBRenderer.ChannelRequest> channelRequests, PanelConfig panelConfig) {
        this.parameters = parameters;
        this.metadataTable = metadataTable;
        this.chooserData = chooserData;
        this.selectedRowsByGroup =
                new LinkedHashMap<String, RowImage.SubjectRow>(selectedRowsByGroup);
        this.channelRequests = Collections.unmodifiableList(
                new ArrayList<FPBRenderer.ChannelRequest>(channelRequests));
        this.panelConfig = panelConfig;
    }

    public FPBParameters parameters() { return parameters; }
    public MetadataTable metadataTable() { return metadataTable; }
    public Step3Chooser.Data chooserData() { return chooserData; }
    public List<FPBRenderer.ChannelRequest> channelRequests() { return channelRequests; }
    public PanelConfig panelConfig() { return panelConfig; }

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
        context.metadataTable = metadataTable;
        context.chooserData = chooserData;
        context.selectedRowsByGroup =
                new LinkedHashMap<String, RowImage.SubjectRow>(selectedRowsByGroup);
        context.layoutChannelRequests =
                new ArrayList<FPBRenderer.ChannelRequest>(channelRequests);
        context.panelConfig = panelConfig;
        context.groupLayoutRows = panelConfig.groupLayoutRows();
        return context;
    }
}
