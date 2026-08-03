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

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

/** One group tab's virtualized subject grid backed by a single-selection JList. */
public final class PanelGrid extends JPanel {

    private final String group;
    private final List<RowImage.SubjectRow> rows;
    private final DefaultListModel<RowImage.SubjectRow> model;
    private final JList<RowImage.SubjectRow> list;
    private final Map<Integer, BufferedImage> rowImageCache;
    private final RowRenderer renderer;
    private final RowImage.Layout baseLayout;

    public PanelGrid(String group, List<RowImage.SubjectRow> rows,
            RowImage.Layout layout) {
        super(new BorderLayout());
        this.group = clean(group, "group");
        if (rows == null) throw new IllegalArgumentException("rows must not be null");
        this.rows = Collections.unmodifiableList(
                new ArrayList<RowImage.SubjectRow>(rows));
        this.baseLayout = layout == null
                ? RowImage.Layout.standard(Math.max(1, inferChannelCount()))
                : layout;
        rowImageCache = new ConcurrentHashMap<Integer, BufferedImage>();
        model = new DefaultListModel<RowImage.SubjectRow>();
        for (int i = 0; i < this.rows.size(); i++) model.addElement(this.rows.get(i));

        list = new JList<RowImage.SubjectRow>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setLayoutOrientation(JList.VERTICAL);
        list.setVisibleRowCount(0);
        list.setFixedCellWidth(baseLayout.rowWidth());
        list.setFixedCellHeight(baseLayout.rowHeight());
        list.setOpaque(true);
        renderer = new RowRenderer(rowImageCache);
        list.setCellRenderer(renderer);

        JScrollPane scroll = new JScrollPane(list,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        add(scroll, BorderLayout.CENTER);
    }

    public String group() {
        return group;
    }

    public int rowCount() {
        return rows.size();
    }

    public RowImage.SubjectRow rowAt(int rowIndex) {
        return rows.get(rowIndex);
    }

    public List<RowImage.SubjectRow> rows() {
        return rows;
    }

    public JList<RowImage.SubjectRow> subjectList() {
        return list;
    }

    public RowRenderer renderer() {
        return renderer;
    }

    public RowImage.Layout layoutForCurrentScale() {
        return baseLayout.withScale(list.getGraphicsConfiguration());
    }

    public RowImage.SubjectRow selectedSubject() {
        return list.getSelectedValue();
    }

    public int selectedRowIndex() {
        return list.getSelectedIndex();
    }

    public void setSelectedRowIndex(int rowIndex) {
        list.setSelectedIndex(rowIndex);
    }

    public RenderRequest createRenderRequest(PlaneCache planes,
            HistogramCache histograms, List<FPBRenderer.ChannelRequest> channels,
            boolean adjusting) {
        return RenderRequest.forGrid(this, planes, histograms, channels, adjusting);
    }

    public List<Integer> rowIndices(boolean visibleOnly) {
        if (!visibleOnly) return allRowIndices();
        return visibleRowIndices();
    }

    public List<Integer> allRowIndices() {
        List<Integer> indices = new ArrayList<Integer>(rows.size());
        for (int i = 0; i < rows.size(); i++) indices.add(Integer.valueOf(i));
        return Collections.unmodifiableList(indices);
    }

    public List<Integer> visibleRowIndices() {
        int first = list.getFirstVisibleIndex();
        int last = list.getLastVisibleIndex();
        if (first >= 0 && last >= first) return range(first, last);

        Rectangle visible = list.getVisibleRect();
        if (visible == null || visible.height <= 0) return Collections.emptyList();
        int cellHeight = Math.max(1, list.getFixedCellHeight());
        first = Math.max(0, visible.y / cellHeight);
        last = Math.min(rows.size() - 1, (visible.y + visible.height - 1) / cellHeight);
        if (last < first) return Collections.emptyList();
        return range(first, last);
    }

    public void putRowImage(int rowIndex, BufferedImage image) {
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            throw new IllegalArgumentException("rowIndex out of range");
        }
        if (image == null) throw new IllegalArgumentException("image must not be null");
        rowImageCache.put(Integer.valueOf(rowIndex), image);
        repaintRow(rowIndex);
    }

    public void applyRenderedRows(List<RenderThread.RenderedRow> renderedRows) {
        if (renderedRows == null || renderedRows.isEmpty()) return;
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("rendered rows must be applied on the event thread");
        }
        for (int i = 0; i < renderedRows.size(); i++) {
            RenderThread.RenderedRow row = renderedRows.get(i);
            putRowImage(row.rowIndex(), row.image());
        }
    }

    private void repaintRow(int rowIndex) {
        Rectangle bounds = list.getCellBounds(rowIndex, rowIndex);
        if (bounds == null) {
            list.repaint();
        } else {
            list.repaint(bounds);
        }
    }

    private int inferChannelCount() {
        return 1;
    }

    private List<Integer> range(int first, int last) {
        List<Integer> indices = new ArrayList<Integer>(Math.max(0, last - first + 1));
        for (int i = first; i <= last && i < rows.size(); i++) {
            indices.add(Integer.valueOf(i));
        }
        return Collections.unmodifiableList(indices);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        int width = Math.max(preferred.width, baseLayout.rowWidth() + 24);
        int height = Math.max(preferred.height,
                Math.min(Math.max(1, rows.size()), 6) * baseLayout.rowHeight());
        return new Dimension(width, height);
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.length() == 0 ? fallback : trimmed;
    }
}
