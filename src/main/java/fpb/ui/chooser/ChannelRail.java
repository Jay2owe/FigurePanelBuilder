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
import fpb.render.ChannelColour;
import fpb.render.DisplayRange;
import fpb.render.FPBRenderer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

/** Left chooser rail: all channel display ranges and pooled histograms. */
public final class ChannelRail extends JPanel {

    public interface Listener {
        void rangeChanged(boolean adjusting);
        void focusChanged(int channelIndex);
    }

    private static final Color BACKGROUND = new Color(248, 249, 250);
    private static final Color BORDER = new Color(196, 202, 208);
    private static final Color TEXT = new Color(42, 47, 53);
    private static final Color MUTED = new Color(95, 103, 112);
    private static final Color PROPOSED = new Color(86, 180, 233);
    private static final Color GRAPHITE = new Color(72, 76, 82);

    private final List<ChannelState> states;
    private final List<ChannelBlock> blocks = new ArrayList<ChannelBlock>();
    private final String statisticName;
    private Listener listener;
    private int focusedChannel;

    public ChannelRail(List<ChannelSpec> channels, HistogramCache histograms,
            String statisticName) {
        super(new BorderLayout(0, 6));
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("channels must not be empty");
        }
        if (histograms == null) throw new IllegalArgumentException("histograms must not be null");
        this.statisticName = clean(statisticName).isEmpty()
                ? "statistic" : clean(statisticName);
        states = new ArrayList<ChannelState>(channels.size());
        setBackground(BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JLabel header = new JLabel("CHANNELS");
        header.setForeground(TEXT);
        add(header, BorderLayout.NORTH);

        JPanel stack = new JPanel(new GridLayout(0, 1, 0, 8));
        stack.setOpaque(false);
        for (int i = 0; i < channels.size(); i++) {
            ChannelSpec spec = channels.get(i);
            ChannelState state = new ChannelState(spec,
                    histograms.pooledHistogram(spec.channelIndex()));
            states.add(state);
            ChannelBlock block = new ChannelBlock(state);
            blocks.add(block);
            stack.add(block);
        }
        add(stack, BorderLayout.CENTER);
        updateFocus(0);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean allRangesLocked() {
        for (ChannelState state : states) {
            if (state.lockedRange == null || !state.lockedRange.isValid()) return false;
        }
        return true;
    }

    public int focusedChannelIndex() {
        return states.get(focusedChannel).spec.channelIndex();
    }

    public List<FPBRenderer.ChannelRequest> channelRequests() {
        commitPendingFieldEdits();
        List<FPBRenderer.ChannelRequest> requests =
                new ArrayList<FPBRenderer.ChannelRequest>(states.size());
        for (ChannelState state : states) {
            requests.add(new FPBRenderer.ChannelRequest(state.spec.channelIndex(),
                    state.spec.name(), state.spec.colour(), state.lockedRange));
        }
        return Collections.unmodifiableList(requests);
    }

    /** Valid ranges for immediate previews, using proposals until explicitly locked. */
    public List<FPBRenderer.ChannelRequest> previewChannelRequests() {
        List<FPBRenderer.ChannelRequest> requests =
                new ArrayList<FPBRenderer.ChannelRequest>(states.size());
        for (ChannelState state : states) {
            DisplayRange range = state.lockedRange == null
                    ? new DisplayRange(state.proposedMin, state.proposedMax)
                    : state.lockedRange;
            requests.add(new FPBRenderer.ChannelRequest(state.spec.channelIndex(),
                    state.spec.name(), state.spec.colour(), range));
        }
        return Collections.unmodifiableList(requests);
    }

    public Map<Integer, DisplayRange> lockedRanges() {
        LinkedHashMap<Integer, DisplayRange> ranges =
                new LinkedHashMap<Integer, DisplayRange>();
        for (ChannelState state : states) {
            if (state.lockedRange != null) {
                ranges.put(Integer.valueOf(state.spec.channelIndex()), state.lockedRange);
            }
        }
        return Collections.unmodifiableMap(ranges);
    }

    public void lockChannelForTest(int channelIndex, int min, int max) {
        for (ChannelBlock block : blocks) {
            if (block.state.spec.channelIndex() == channelIndex) {
                block.setProposed(min, max, false);
                block.lockCurrentProposal();
                return;
            }
        }
        throw new IllegalArgumentException("Unknown channel: " + channelIndex);
    }

    private void updateFocus(int blockIndex) {
        focusedChannel = Math.max(0, Math.min(blockIndex, blocks.size() - 1));
        for (int i = 0; i < blocks.size(); i++) blocks.get(i).setFocused(i == focusedChannel);
        if (listener != null) listener.focusChanged(focusedChannelIndex());
    }

    private void fireRangeChanged(boolean adjusting) {
        if (listener != null) listener.rangeChanged(adjusting);
    }

    private static int lowerPercentile(HistogramCache.Histogram histogram) {
        return percentile(histogram, 0.001);
    }

    private static int upperPercentile(HistogramCache.Histogram histogram) {
        return percentile(histogram, 0.999);
    }

    static int percentile(HistogramCache.Histogram histogram, double fraction) {
        if (histogram == null || histogram.total() <= 0L) return 0;
        double safe = fraction;
        if (!Double.isFinite(safe)) safe = 0.0;
        if (safe < 0.0) safe = 0.0;
        if (safe > 1.0) safe = 1.0;
        long target = (long) Math.ceil(histogram.total() * safe);
        if (target < 1L) target = 1L;
        for (int value = 0; value < HistogramCache.BIN_COUNT; value++) {
            if (histogram.cumulativeCountAt(value) >= target) return value;
        }
        return HistogramCache.BIN_COUNT - 1;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class ChannelSpec {
        private final int channelIndex;
        private final String name;
        private final ChannelColour colour;

        public ChannelSpec(int channelIndex, String name, ChannelColour colour) {
            if (channelIndex < 0) throw new IllegalArgumentException("channelIndex is negative");
            this.channelIndex = channelIndex;
            this.name = clean(name).isEmpty() ? "C" + (channelIndex + 1) : clean(name);
            this.colour = colour == null ? ChannelColour.GREY : colour;
        }

        public int channelIndex() {
            return channelIndex;
        }

        public String name() {
            return name;
        }

        public ChannelColour colour() {
            return colour;
        }
    }

    void setRangeFieldTextForTest(int channelIndex, String min, String max) {
        for (ChannelBlock block : blocks) {
            if (block.state.spec.channelIndex() == channelIndex) {
                block.minField.setText(min);
                block.maxField.setText(max);
                return;
            }
        }
        throw new IllegalArgumentException("Unknown channel index " + channelIndex);
    }

    void commitPendingFieldEdits() {
        for (ChannelBlock block : blocks) block.commitFields(false);
    }

    private static final class ChannelState {
        final ChannelSpec spec;
        final HistogramCache.Histogram histogram;
        final int domainMin;
        final int domainMax;
        int proposedMin;
        int proposedMax;
        DisplayRange lockedRange;

        ChannelState(ChannelSpec spec, HistogramCache.Histogram histogram) {
            this.spec = spec;
            this.histogram = histogram;
            int first = firstNonEmpty(histogram);
            int last = lastNonEmpty(histogram);
            if (last <= first && first >= HistogramCache.BIN_COUNT - 1) first--;
            this.domainMin = first;
            this.domainMax = last > first ? last : first + 1;
            this.proposedMin = domainMin;
            this.proposedMax = domainMax;
            this.lockedRange = new DisplayRange(proposedMin, proposedMax);
        }

        private static int firstNonEmpty(HistogramCache.Histogram histogram) {
            if (histogram == null || histogram.total() <= 0L) return 0;
            for (int value = 0; value < HistogramCache.BIN_COUNT; value++) {
                if (histogram.cumulativeCountAt(value) > 0) return value;
            }
            return 0;
        }

        private static int lastNonEmpty(HistogramCache.Histogram histogram) {
            if (histogram == null || histogram.total() <= 0L) {
                return HistogramCache.BIN_COUNT - 1;
            }
            long total = histogram.total();
            for (int value = 0; value < HistogramCache.BIN_COUNT; value++) {
                if (histogram.cumulativeCountAt(value) >= total) return value;
            }
            return HistogramCache.BIN_COUNT - 1;
        }
    }

    private final class ChannelBlock extends JPanel {
        private final ChannelState state;
        private final JLabel name;
        private final HistogramView histogramView;
        private final JSlider minSlider;
        private final JSlider maxSlider;
        private final JTextField minField = new JTextField(5);
        private final JTextField maxField = new JTextField(5);
        private boolean updating;

        ChannelBlock(ChannelState state) {
            super(new BorderLayout(0, 4));
            this.state = state;
            setOpaque(true);
            setBackground(BACKGROUND);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BORDER),
                    BorderFactory.createEmptyBorder(6, 6, 6, 6)));

            JPanel top = new JPanel(new BorderLayout(5, 0));
            top.setOpaque(false);
            JLabel swatch = new JLabel(" ");
            swatch.setOpaque(true);
            swatch.setBackground(swatchColour(state.spec.colour()));
            swatch.setPreferredSize(new Dimension(14, 14));
            top.add(swatch, BorderLayout.WEST);
            name = new JLabel(state.spec.name());
            name.setForeground(TEXT);
            top.add(name, BorderLayout.CENTER);
            add(top, BorderLayout.NORTH);

            histogramView = new HistogramView(state.histogram);
            add(histogramView, BorderLayout.CENTER);

            JPanel lower = new JPanel(new BorderLayout(0, 3));
            lower.setOpaque(false);
            JPanel controls = new JPanel(new GridLayout(0, 1, 0, 2));
            controls.setOpaque(false);
            minSlider = slider(state.domainMin, state.domainMax, state.proposedMin);
            maxSlider = slider(state.domainMin, state.domainMax, state.proposedMax);
            controls.add(labelled("Min", minSlider));
            controls.add(labelled("Max", maxSlider));
            lower.add(controls, BorderLayout.NORTH);

            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
            bottom.setOpaque(false);
            minField.setHorizontalAlignment(SwingConstants.RIGHT);
            maxField.setHorizontalAlignment(SwingConstants.RIGHT);
            bottom.add(minField);
            bottom.add(new JLabel("-"));
            bottom.add(maxField);
            JButton suggest = new JButton("Auto");
            suggest.setToolTipText("Set this channel range from the cohort histogram");
            suggest.addActionListener(new java.awt.event.ActionListener() {
                @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                    setProposed(lowerPercentile(ChannelBlock.this.state.histogram),
                            upperPercentile(ChannelBlock.this.state.histogram), false);
                }
            });
            bottom.add(suggest);
            lower.add(bottom, BorderLayout.SOUTH);
            add(lower, BorderLayout.SOUTH);

            minSlider.addChangeListener(new ChangeListener() {
                @Override public void stateChanged(ChangeEvent event) {
                    if (!updating) updateFromSliders(minSlider.getValueIsAdjusting()
                            || maxSlider.getValueIsAdjusting());
                }
            });
            maxSlider.addChangeListener(new ChangeListener() {
                @Override public void stateChanged(ChangeEvent event) {
                    if (!updating) updateFromSliders(minSlider.getValueIsAdjusting()
                            || maxSlider.getValueIsAdjusting());
                }
            });
            minField.addActionListener(new java.awt.event.ActionListener() {
                @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                    commitFields();
                }
            });
            maxField.addActionListener(new java.awt.event.ActionListener() {
                @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                    commitFields();
                }
            });
            java.awt.event.FocusAdapter commitOnFocusLost =
                    new java.awt.event.FocusAdapter() {
                        @Override public void focusLost(java.awt.event.FocusEvent event) {
                            commitFields();
                        }
                    };
            minField.addFocusListener(commitOnFocusLost);
            maxField.addFocusListener(commitOnFocusLost);
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent event) {
                    updateFocus(blocks.indexOf(ChannelBlock.this));
                }
            });
            setProposed(state.proposedMin, state.proposedMax, false);
        }

        void setFocused(boolean focused) {
            name.setText((focused ? "> " : "") + state.spec.name());
        }

        void setProposed(int min, int max, boolean adjusting) {
            setProposed(min, max, adjusting, true);
        }

        private void setProposed(int min, int max, boolean adjusting,
                boolean notifyListener) {
            int safeMin = clamp(min, state.domainMin, state.domainMax);
            int safeMax = clamp(max, state.domainMin, state.domainMax);
            if (safeMax <= safeMin) {
                if (safeMin < state.domainMax) safeMax = safeMin + 1;
                else safeMin = Math.max(state.domainMin, safeMax - 1);
            }
            state.proposedMin = safeMin;
            state.proposedMax = safeMax;
            updating = true;
            try {
                minSlider.setValue(safeMin);
                maxSlider.setValue(safeMax);
                minField.setText(String.valueOf(safeMin));
                maxField.setText(String.valueOf(safeMax));
                histogramView.setRange(safeMin, safeMax);
            } finally {
                updating = false;
            }
            // Every valid value visible in the controls is authoritative. There
            // is no separate hidden confirmation state for the user to satisfy.
            state.lockedRange = new DisplayRange(safeMin, safeMax);
            if (notifyListener) fireRangeChanged(adjusting);
        }

        void lockCurrentProposal() {
            state.lockedRange = new DisplayRange(state.proposedMin, state.proposedMax);
            fireRangeChanged(false);
        }

        private void updateFromSliders(boolean adjusting) {
            setProposed(minSlider.getValue(), maxSlider.getValue(), adjusting);
        }

        private void commitFields() {
            commitFields(true);
        }

        private void commitFields(boolean notifyListener) {
            try {
                setProposed(Integer.parseInt(minField.getText().trim()),
                        Integer.parseInt(maxField.getText().trim()), false,
                        notifyListener);
            } catch (NumberFormatException invalid) {
                setProposed(state.proposedMin, state.proposedMax, false,
                        notifyListener);
            }
        }

        private JPanel labelled(String label, JSlider slider) {
            JPanel panel = new JPanel(new BorderLayout(4, 0));
            panel.setOpaque(false);
            JLabel text = new JLabel(label);
            text.setForeground(MUTED);
            text.setPreferredSize(new Dimension(28, 18));
            panel.add(text, BorderLayout.WEST);
            panel.add(slider, BorderLayout.CENTER);
            return panel;
        }

        private JSlider slider(int min, int max, int value) {
            JSlider slider = new JSlider(min, max, value);
            slider.setOpaque(false);
            return slider;
        }
    }

    private static Color swatchColour(ChannelColour colour) {
        int rgb = colour == null ? ChannelColour.GREY.rgb(210) : colour.rgb(210);
        return new Color(rgb);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static final class HistogramView extends JPanel {
        private final HistogramCache.Histogram histogram;
        private int min;
        private int max;

        HistogramView(HistogramCache.Histogram histogram) {
            this.histogram = histogram;
            setOpaque(true);
            setBackground(new Color(252, 253, 254));
            setPreferredSize(new Dimension(180, 48));
            setMinimumSize(new Dimension(120, 40));
        }

        void setRange(int min, int max) {
            this.min = min;
            this.max = max;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getWidth();
                int height = getHeight();
                g.setColor(BORDER);
                g.drawRect(0, 0, Math.max(0, width - 1), Math.max(0, height - 1));
                if (histogram == null || histogram.total() <= 0L) return;
                int bins = 96;
                long maxCount = Math.max(1L, maxBucket(histogram, bins));
                g.setColor(GRAPHITE);
                for (int i = 0; i < bins; i++) {
                    long count = bucket(histogram, i, bins);
                    int x0 = (int) Math.floor(i * width / (double) bins);
                    int x1 = (int) Math.floor((i + 1) * width / (double) bins);
                    int barHeight = (int) Math.round((height - 8)
                            * (count / (double) maxCount));
                    g.fillRect(x0, height - 4 - barHeight, Math.max(1, x1 - x0),
                            Math.max(1, barHeight));
                }
                int minX = valueX(min, width);
                int maxX = valueX(max, width);
                g.setColor(new Color(PROPOSED.getRed(), PROPOSED.getGreen(),
                        PROPOSED.getBlue(), 55));
                g.fillRect(minX, 1, Math.max(1, maxX - minX), Math.max(1, height - 2));
                g.setColor(PROPOSED.darker());
                g.drawLine(minX, 1, minX, Math.max(1, height - 2));
                g.drawLine(maxX, 1, maxX, Math.max(1, height - 2));
            } finally {
                g.dispose();
            }
        }

        private int valueX(int value, int width) {
            return (int) Math.round((value / (double) (HistogramCache.BIN_COUNT - 1))
                    * Math.max(1, width - 1));
        }

        private static long maxBucket(HistogramCache.Histogram histogram, int bins) {
            long max = 0L;
            for (int i = 0; i < bins; i++) {
                long count = bucket(histogram, i, bins);
                if (count > max) max = count;
            }
            return max;
        }

        private static long bucket(HistogramCache.Histogram histogram, int index, int bins) {
            int start = (int) Math.floor(index * HistogramCache.BIN_COUNT / (double) bins);
            int end = (int) Math.floor((index + 1) * HistogramCache.BIN_COUNT
                    / (double) bins) - 1;
            if (end < start) end = start;
            int before = start == 0 ? 0 : histogram.cumulativeCountAt(start - 1);
            return (long) histogram.cumulativeCountAt(end) - (long) before;
        }
    }
}
