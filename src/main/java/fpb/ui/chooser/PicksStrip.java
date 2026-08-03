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
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
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

/** Right chooser strip: one live picked subject preview per group. */
public final class PicksStrip extends JPanel {

    public interface Listener {
        void pickClicked(String group);
    }

    private static final Color BACKGROUND = new Color(248, 249, 250);
    private static final Color BORDER = new Color(196, 202, 208);
    private static final Color TILE = new Color(30, 32, 34);
    private static final Color TEXT = new Color(42, 47, 53);
    private static final Color MUTED = new Color(98, 106, 114);

    private final JComboBox<String> viewMode = new JComboBox<String>();
    private final JPanel grid = new JPanel(new GridLayout(0, 2, 8, 8));
    private final Map<String, PickCell> cellsByGroup =
            new LinkedHashMap<String, PickCell>();
    private Listener listener;
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
                repaint();
            }
        });
        add(viewMode, BorderLayout.SOUTH);
        setGroups(groups);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
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
    }

    public void updatePicks(Map<String, RowImage.SubjectRow> picks, PlaneCache planes,
            HistogramCache histograms, List<FPBRenderer.ChannelRequest> channels) {
        boolean canRender = picks != null && planes != null && histograms != null
                && channels != null && !channels.isEmpty();
        for (Map.Entry<String, PickCell> entry : cellsByGroup.entrySet()) {
            RowImage.SubjectRow row = picks == null ? null : picks.get(entry.getKey());
            BufferedImage image = null;
            if (canRender && row != null) {
                try {
                    FPBRenderer.PanelRender render = new FPBRenderer().renderPanel(planes,
                            histograms, row.imageIndex(), channels, 340, 340);
                    image = selectedImage(render, channels);
                } catch (RuntimeException notReady) {
                    image = null;
                }
            }
            entry.getValue().setPick(row, image);
        }
    }

    public List<String> groupsForTest() {
        return Collections.unmodifiableList(new ArrayList<String>(cellsByGroup.keySet()));
    }

    private BufferedImage selectedImage(FPBRenderer.PanelRender render,
            List<FPBRenderer.ChannelRequest> channels) {
        if (render == null) return null;
        Object selected = viewMode.getSelectedItem();
        if (selected == null || "Merge".equals(selected.toString())) return render.mergeImage();
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).channelIndex() == focusedChannelIndex
                    && i < render.channelImages().size()) {
                return render.channelImages().get(i);
            }
        }
        return render.mergeImage();
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
                g.drawImage(image, imageX, imageY, imageSize, imageSize, null);
            }
            g.setColor(BORDER);
            g.drawRect(imageX, imageY, imageSize, imageSize);
            g.setColor(row == null ? MUTED : TEXT);
            String subject = row == null ? "No pick" : row.subject();
            String fitted = fit(subject, metrics, Math.max(20, width - 12));
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
