/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.chooser;

import fpb.figure.QuantificationPlot;
import fpb.stats.GroupQuantification;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Always-visible comparison of the subject statistic across every group. */
public final class GroupComparisonPanel extends JPanel {

    private static final Color BACKGROUND = Color.WHITE;
    private static final Color BORDER = new Color(196, 202, 208);
    private static final Color TEXT = new Color(42, 47, 53);
    private static final Color MUTED = new Color(95, 103, 112);

    private final GroupQuantification quantification;
    private final PlotCanvas canvas = new PlotCanvas();
    private Map<String, String> chosenSubjects =
            Collections.emptyMap();
    private Map<String, Integer> chosenSections =
            Collections.emptyMap();

    public GroupComparisonPanel(GroupQuantification quantification) {
        super(new BorderLayout(4, 2));
        if (quantification == null) {
            throw new IllegalArgumentException("quantification is required");
        }
        this.quantification = quantification;
        setBackground(BACKGROUND);
        setBorder(BorderFactory.createLineBorder(BORDER));
        setPreferredSize(new Dimension(760, 250));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JPanel text = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, 7, 3));
        text.setOpaque(false);
        JLabel title = new JLabel("GROUP QUANTIFICATION");
        title.setForeground(TEXT);
        text.add(title);
        JLabel description = new JLabel(
                "all channels on one z-normalized axis; dots are sections; "
                + "colours and mean bars identify groups");
        description.setForeground(MUTED);
        text.add(description);
        header.add(text, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);

        canvas.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        add(canvas, BorderLayout.CENTER);
    }

    public void setChosenRows(Map<String, RowImage.SubjectRow> rows) {
        LinkedHashMap<String, String> chosen =
                new LinkedHashMap<String, String>();
        LinkedHashMap<String, Integer> sectionIndices =
                new LinkedHashMap<String, Integer>();
        if (rows != null) {
            for (Map.Entry<String, RowImage.SubjectRow> entry : rows.entrySet()) {
                if (entry.getValue() != null) {
                    chosen.put(entry.getKey(), entry.getValue().subject());
                    sectionIndices.put(entry.getKey(),
                            Integer.valueOf(entry.getValue().imageIndex()));
                }
            }
        }
        chosenSubjects = Collections.unmodifiableMap(chosen);
        chosenSections = Collections.unmodifiableMap(sectionIndices);
        canvas.repaint();
    }

    GroupQuantification quantificationForTest() {
        return quantification;
    }

    Map<String, String> chosenSubjectsForTest() {
        return chosenSubjects;
    }

    int visibleChannelCountForTest() {
        return quantification.channelCount();
    }

    int plotCanvasCountForTest() { return 1; }

    private final class PlotCanvas extends JPanel {
        PlotCanvas() {
            setOpaque(true);
            setBackground(BACKGROUND);
            setMinimumSize(new Dimension(560, 180));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                QuantificationPlot.paintAll(copy, quantification, chosenSections,
                        getWidth(), getHeight());
            } finally {
                copy.dispose();
            }
        }
    }
}
