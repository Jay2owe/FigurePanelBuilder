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
import fpb.ui.FitComboBox;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Right chooser strip: one live picked section preview per group. */
public final class PicksStrip extends JPanel {

    public interface Listener {
        void pickClicked(String group);
    }

    private static final Color BACKGROUND = new Color(248, 249, 250);
    private static final Color BORDER = new Color(196, 202, 208);
    private static final Color TILE = new Color(30, 32, 34);
    private static final Color TEXT = new Color(42, 47, 53);
    private static final Color MUTED = new Color(98, 106, 114);

    private final JComboBox<String> viewMode = new FitComboBox<String>();
    private final JPanel grid = new JPanel(new GridLayout(0, 2, 8, 8));
    private final Map<String, PickCell> cellsByGroup =
            new LinkedHashMap<String, PickCell>();
    private final Map<String, Integer> channelIndexByLabel =
            new LinkedHashMap<String, Integer>();
    private Listener listener;
    private Runnable renderListener;
    private int focusedChannelIndex;

    public PicksStrip(List<String> groups) {
        super(new BorderLayout(0, 8));
        setBackground(BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JLabel header = new JLabel("CURRENT PICKS");
        header.setForeground(TEXT);
        add(header, BorderLayout.NORTH);
        grid.setOpaque(false);
        add(grid, BorderLayout.CENTER);
        viewMode.addItem("Merge");
        viewMode.addActionListener(new java.awt.event.ActionListener() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                if (renderListener != null) renderListener.run();
            }
        });
        add(viewMode, BorderLayout.SOUTH);
        setGroups(groups);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setRenderListener(Runnable renderListener) {
        this.renderListener = renderListener;
    }

    public void setGroups(List<String> groups) {
        grid.removeAll();
        cellsByGroup.clear();
        List<String> safe = groups == null ? Collections.<String>emptyList() : groups;
        for (String group : safe) {
            PickCell cell = new PickCell(group);
            cellsByGroup.put(group, cell);
            grid.add(cell);
        }
        revalidate();
        repaint();
    }

    public void setFocusedChannel(int channelIndex, String name) {
        focusedChannelIndex = channelIndex;
        String label = clean(name).isEmpty() ? "Focused channel" : clean(name);
        boolean found = false;
        for (int i = 0; i < viewMode.getItemCount(); i++) {
            if (label.equals(viewMode.getItemAt(i))) found = true;
        }
        if (!found) viewMode.addItem(label);
        channelIndexByLabel.put(label, Integer.valueOf(channelIndex));
    }

    public void updatePicks(Map<String, RowImage.SubjectRow> picks, PlaneCache planes,
            HistogramCache histograms, List<FPBRenderer.ChannelRequest> channels) {
        applyRenderedPicks(render(createRenderSnapshot(picks, planes, histograms,
                channels)));
    }

    public RenderSnapshot createRenderSnapshot(
            Map<String, RowImage.SubjectRow> picks, PlaneCache planes,
            HistogramCache histograms, List<FPBRenderer.ChannelRequest> channels) {
        Object selected = viewMode.getSelectedItem();
        boolean merge = selected == null || "Merge".equals(selected.toString());
        Integer selectedChannel = selected == null ? null
                : channelIndexByLabel.get(selected.toString());
        int channelIndex = selectedChannel == null ? focusedChannelIndex
                : selectedChannel.intValue();
        return new RenderSnapshot(new ArrayList<String>(cellsByGroup.keySet()), picks,
                planes, histograms, channels, channelIndex, merge);
    }

    public static List<RenderedPick> render(RenderSnapshot snapshot) {
        List<RenderedPick> rendered = new ArrayList<RenderedPick>();
        boolean canRender = snapshot != null && snapshot.planes != null
                && snapshot.histograms != null && !snapshot.channels.isEmpty();
        if (snapshot == null) return rendered;
        for (String group : snapshot.groups) {
            RowImage.SubjectRow row = snapshot.picks.get(group);
            BufferedImage image = null;
            if (canRender && row != null) {
                try {
                    List<BufferedImage> sections = new ArrayList<BufferedImage>();
                    FPBRenderer renderer = new FPBRenderer();
                    for (Integer imageIndex : row.imageIndices()) {
                        PlaneCache.Plane source = snapshot.planes.plane(
                                imageIndex.intValue(),
                                snapshot.channels.get(0).channelIndex());
                        int[] fitted = FPBRenderer.aspectFitDimensions(source.width(),
                                source.height(), 340, 340);
                        FPBRenderer.PanelRender panel = renderer.renderPanel(
                                snapshot.planes, snapshot.histograms,
                                imageIndex.intValue(), snapshot.channels,
                                fitted[0], fitted[1]);
                        sections.add(row.orientation().apply(selectedImage(panel,
                                snapshot.channels, snapshot.focusedChannelIndex,
                                snapshot.merge)));
                    }
                    image = combineSections(sections);
                } catch (RuntimeException notReady) {
                    image = null;
                }
            }
            rendered.add(new RenderedPick(group, row, image));
        }
        return rendered;
    }

    public void applyRenderedPicks(List<RenderedPick> rendered) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("rendered picks must be applied on the event thread");
        }
        if (rendered == null) return;
        for (RenderedPick pick : rendered) {
            PickCell cell = cellsByGroup.get(pick.group);
            if (cell != null) cell.setPick(pick.row, pick.image);
        }
    }

    public List<String> groupsForTest() {
        return Collections.unmodifiableList(new ArrayList<String>(cellsByGroup.keySet()));
    }

    void selectViewForTest(String label) {
        viewMode.setSelectedItem(label);
    }

    int selectedChannelIndexForTest() {
        Object selected = viewMode.getSelectedItem();
        Integer channel = selected == null ? null
                : channelIndexByLabel.get(selected.toString());
        return channel == null ? focusedChannelIndex : channel.intValue();
    }

    private static BufferedImage selectedImage(FPBRenderer.PanelRender render,
            List<FPBRenderer.ChannelRequest> channels, int focusedChannelIndex,
            boolean merge) {
        if (render == null) return null;
        if (merge) return render.mergeImage();
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).channelIndex() == focusedChannelIndex
                    && i < render.channelImages().size()) {
                return render.channelImages().get(i);
            }
        }
        return render.mergeImage();
    }

    private static BufferedImage combineSections(List<BufferedImage> sections) {
        if (sections == null || sections.isEmpty()) return null;
        if (sections.size() == 1) return sections.get(0);
        int gap = 4;
        int width = gap * (sections.size() - 1);
        int height = 1;
        for (BufferedImage section : sections) {
            if (section == null) continue;
            width += section.getWidth();
            height = Math.max(height, section.getHeight());
        }
        BufferedImage combined = new BufferedImage(Math.max(1, width), height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = combined.createGraphics();
        try {
            graphics.setColor(TILE);
            graphics.fillRect(0, 0, combined.getWidth(), combined.getHeight());
            int x = 0;
            for (BufferedImage section : sections) {
                if (section != null) {
                    int y = (height - section.getHeight()) / 2;
                    graphics.drawImage(section, x, y, null);
                    x += section.getWidth();
                }
                x += gap;
            }
        } finally {
            graphics.dispose();
        }
        return combined;
    }

    public static final class RenderSnapshot {
        private final List<String> groups;
        private final Map<String, RowImage.SubjectRow> picks;
        private final PlaneCache planes;
        private final HistogramCache histograms;
        private final List<FPBRenderer.ChannelRequest> channels;
        private final int focusedChannelIndex;
        private final boolean merge;

        private RenderSnapshot(List<String> groups,
                Map<String, RowImage.SubjectRow> picks, PlaneCache planes,
                HistogramCache histograms,
                List<FPBRenderer.ChannelRequest> channels,
                int focusedChannelIndex, boolean merge) {
            this.groups = Collections.unmodifiableList(new ArrayList<String>(groups));
            this.picks = Collections.unmodifiableMap(
                    new LinkedHashMap<String, RowImage.SubjectRow>(picks == null
                            ? Collections.<String, RowImage.SubjectRow>emptyMap()
                            : picks));
            this.planes = planes;
            this.histograms = histograms;
            this.channels = Collections.unmodifiableList(
                    new ArrayList<FPBRenderer.ChannelRequest>(channels == null
                            ? Collections.<FPBRenderer.ChannelRequest>emptyList()
                            : channels));
            this.focusedChannelIndex = focusedChannelIndex;
            this.merge = merge;
        }
    }

    public static final class RenderedPick {
        private final String group;
        private final RowImage.SubjectRow row;
        private final BufferedImage image;

        private RenderedPick(String group, RowImage.SubjectRow row,
                BufferedImage image) {
            this.group = group;
            this.row = row;
            this.image = image;
        }

        BufferedImage imageForTest() {
            return image;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private final class PickCell extends JPanel {
        private final String group;
        private RowImage.SubjectRow row;
        private BufferedImage image;

        PickCell(String group) {
            this.group = clean(group).isEmpty() ? "group" : clean(group);
            setOpaque(true);
            setBackground(BACKGROUND);
            setPreferredSize(new Dimension(168, 184));
            setMinimumSize(new Dimension(132, 148));
            setBorder(BorderFactory.createLineBorder(BORDER));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                    if (listener != null) listener.pickClicked(PickCell.this.group);
                }
            });
        }

        void setPick(RowImage.SubjectRow row, BufferedImage image) {
            this.row = row;
            this.image = image;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int width = getWidth();
            int height = getHeight();
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, width, height);
            FontMetrics metrics = g.getFontMetrics();
            g.setColor(TEXT);
            String title = fit(group, metrics, Math.max(20, width - 12));
            g.drawString(title, 6, metrics.getAscent() + 4);
            int imageY = metrics.getHeight() + 8;
            int imageSize = Math.max(1, Math.min(width - 12, height - imageY - 22));
            int imageX = Math.max(6, (width - imageSize) / 2);
            g.setColor(TILE);
            g.fillRect(imageX, imageY, imageSize, imageSize);
            if (image != null) {
                int[] fitted = FPBRenderer.aspectFitDimensions(image.getWidth(),
                        image.getHeight(), imageSize, imageSize);
                int fittedX = imageX + (imageSize - fitted[0]) / 2;
                int fittedY = imageY + (imageSize - fitted[1]) / 2;
                g.drawImage(image, fittedX, fittedY, fitted[0], fitted[1], null);
            }
            g.setColor(BORDER);
            g.drawRect(imageX, imageY, imageSize, imageSize);
            g.setColor(row == null ? MUTED : TEXT);
            String selection = "No pick";
            if (row != null) {
                selection = row.subject();
                if (!row.section().isEmpty()) selection += " - " + row.section();
            }
            String fitted = fit(selection, metrics, Math.max(20, width - 12));
            g.drawString(fitted, Math.max(6, (width - metrics.stringWidth(fitted)) / 2),
                    height - 8);
        }

        private String fit(String value, FontMetrics metrics, int maxWidth) {
            String text = clean(value);
            if (metrics.stringWidth(text) <= maxWidth) return text;
            String suffix = "...";
            int limit = text.length();
            while (limit > 1) {
                String candidate = text.substring(0, limit) + suffix;
                if (metrics.stringWidth(candidate) <= maxWidth) return candidate;
                limit--;
            }
            return suffix;
        }
    }
}
