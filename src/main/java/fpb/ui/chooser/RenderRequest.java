/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.chooser;

import fpb.io.HistogramCache;
import fpb.io.PlaneCache;
import fpb.render.FPBRenderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable render parameters consumed by the chooser render thread. */
public final class RenderRequest {

    private final String key;
    private final PanelGrid grid;
    private final PlaneCache planes;
    private final HistogramCache histograms;
    private final List<FPBRenderer.ChannelRequest> channels;
    private final boolean adjusting;
    private final List<Integer> rowIndices;
    private final RowImage.Layout layout;
    private final List<RowImage.SubjectRow> rows;
    private final int selectedRowIndex;

    private RenderRequest(String key, PanelGrid grid, PlaneCache planes,
            HistogramCache histograms, List<FPBRenderer.ChannelRequest> channels,
            boolean adjusting, List<Integer> rowIndices, RowImage.Layout layout,
            List<RowImage.SubjectRow> rows, int selectedRowIndex) {
        this.key = key == null ? "" : key;
        this.grid = grid;
        this.planes = planes;
        this.histograms = histograms;
        this.channels = channels == null ? Collections.<FPBRenderer.ChannelRequest>emptyList()
                : Collections.unmodifiableList(
                        new ArrayList<FPBRenderer.ChannelRequest>(channels));
        this.adjusting = adjusting;
        this.rowIndices = rowIndices == null ? Collections.<Integer>emptyList()
                : Collections.unmodifiableList(new ArrayList<Integer>(rowIndices));
        this.layout = layout;
        this.rows = rows == null ? Collections.<RowImage.SubjectRow>emptyList()
                : Collections.unmodifiableList(new ArrayList<RowImage.SubjectRow>(rows));
        this.selectedRowIndex = selectedRowIndex;
    }

    public static RenderRequest forGrid(PanelGrid grid, PlaneCache planes,
            HistogramCache histograms, List<FPBRenderer.ChannelRequest> channels,
            boolean adjusting) {
        if (grid == null) throw new IllegalArgumentException("grid must not be null");
        if (planes == null) throw new IllegalArgumentException("planes must not be null");
        if (histograms == null) throw new IllegalArgumentException("histograms must not be null");
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("channels must not be empty");
        }
        List<Integer> indices = grid.rowIndices(adjusting);
        return new RenderRequest(grid.group(), grid, planes, histograms, channels,
                adjusting, indices, grid.layoutForCurrentScale(), grid.rows(),
                grid.selectedRowIndex());
    }

    static RenderRequest marker(String key) {
        return new RenderRequest(key, null, null, null, null, false,
                Collections.<Integer>emptyList(), null,
                Collections.<RowImage.SubjectRow>emptyList(), -1);
    }

    public String key() {
        return key;
    }

    public boolean hasGrid() {
        return grid != null;
    }

    public PanelGrid grid() {
        return grid;
    }

    public PlaneCache planes() {
        return planes;
    }

    public HistogramCache histograms() {
        return histograms;
    }

    public List<FPBRenderer.ChannelRequest> channels() {
        return channels;
    }

    public boolean adjusting() {
        return adjusting;
    }

    public List<Integer> rowIndices() {
        return rowIndices;
    }

    public RowImage.Layout layout() {
        return layout;
    }

    public RowImage.SubjectRow rowAt(int rowIndex) {
        return rows.get(rowIndex);
    }

    public int selectedRowIndex() {
        return selectedRowIndex;
    }
}
