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
import fpb.io.ImageLoader;
import fpb.io.PlaneCache;
import fpb.io.ProgressCallback;
import fpb.meta.MetadataRow;
import fpb.meta.MetadataTable;
import fpb.render.FPBRenderer;
import fpb.stats.GroupStats;
import fpb.stats.SelectionRecord;
import fpb.stats.Statistic;
import fpb.stats.SubjectAggregator;
import fpb.stats.Suggestion;
import fpb.ui.FPBWizard;
import fpb.ui.WizardStep;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

/** Step 3 chooser screen: channel rail, subject rows and current picks. */
public final class Step3Chooser implements WizardStep, AutoCloseable {

    private static final Color BACKGROUND = new Color(248, 249, 250);
    private static final Color BORDER = new Color(196, 202, 208);
    private static final Color TEXT = new Color(42, 47, 53);
    private static final Color MUTED = new Color(95, 103, 112);

    private final FPBWizard.Context context;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));
    private final JPanel centre = new JPanel(new BorderLayout());
    private final JLabel summary = new JLabel("Choose images and channels first.");
    private final JLabel hint = new JLabel("Set a display range for every channel to continue.");
    private final RenderThread renderThread = new RenderThread();
    private Runnable advanceStateListener;

    private Data data;
    private ChannelRail rail;
    private PicksStrip picksStrip;
    private JTabbedPane tabs;
    private final Map<String, PanelGrid> gridsByGroup =
            new LinkedHashMap<String, PanelGrid>();
    private final Map<String, RowImage.SubjectRow> picksByGroup =
            new LinkedHashMap<String, RowImage.SubjectRow>();
    private boolean threadStarted;
    private boolean loadingAttempted;

    public Step3Chooser(FPBWizard.Context context) {
        this.context = context;
        panel.setBackground(BACKGROUND);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        summary.setForeground(TEXT);
        panel.add(summary, BorderLayout.NORTH);
        panel.add(centre, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        JLabel caption = new JLabel("Display range affects appearance only.");
        caption.setForeground(MUTED);
        footer.add(caption, BorderLayout.WEST);
        hint.setForeground(MUTED);
        footer.add(hint, BorderLayout.EAST);
        panel.add(footer, BorderLayout.SOUTH);
    }

    @Override
    public String title() {
        return "Choose images";
    }

    @Override
    public String nextTitle() {
        return "Layout";
    }

    @Override
    public JComponent component() {
        return panel;
    }

    @Override
    public void onShow() {
        ensureThreadStarted();
        if (data == null && !loadingAttempted) {
            loadingAttempted = true;
            tryBuildFromContext();
        }
        updateAdvanceState();
    }

    @Override
    public boolean canAdvance() {
        return rail != null && rail.allRangesLocked();
    }

    @Override
    public void close() {
        renderThread.close();
    }

    public void setAdvanceStateListener(Runnable listener) {
        advanceStateListener = listener;
    }

    public void setData(Data data) {
        this.data = data;
        buildScreen();
        updateAdvanceState();
    }

    public ChannelRail channelRailForTest() {
        return rail;
    }

    public PicksStrip picksStripForTest() {
        return picksStrip;
    }

    public Map<String, RowImage.SubjectRow> picksForTest() {
        return Collections.unmodifiableMap(picksByGroup);
    }

    private void ensureThreadStarted() {
        if (threadStarted) return;
        renderThread.start();
        threadStarted = true;
    }

    private void tryBuildFromContext() {
        if (context == null || context.metadataTable == null
                || context.metadataTable.rows().isEmpty()) {
            showEmpty("Choose images and channels first.");
            return;
        }
        try {
            List<File> files = new ArrayList<File>();
            for (MetadataRow row : context.metadataTable.rows()) files.add(row.file);
            ImageLoader.LoadResult loaded = new ImageLoader(150, 4)
                    .loadFiles(files, ProgressCallback.NONE);
            Statistic.ImageValues values =
                    Statistic.brightestOnePercentMeans(loaded.histogramCache());
            SubjectAggregator.SubjectStats subjectStats =
                    SubjectAggregator.aggregate(context.metadataTable, values);
            GroupStats groupStats = GroupStats.from(subjectStats);
            Map<String, Suggestion.Result> suggestions = Suggestion.suggest(groupStats);
            List<SelectionRecord> records =
                    SelectionRecord.from(subjectStats, groupStats, suggestions);
            setData(new Data(context.metadataTable, loaded.planeCache(),
                    loaded.histogramCache(), channelSpecs(loaded.channelCount()),
                    subjectStats, suggestions, records));
        } catch (IOException failure) {
            showEmpty(failure.getMessage());
        }
    }

    private List<ChannelRail.ChannelSpec> channelSpecs(int loadedChannelCount) {
        List<ChannelRail.ChannelSpec> specs =
                new ArrayList<ChannelRail.ChannelSpec>();
        if (context != null && context.channelSettings != null
                && !context.channelSettings.isEmpty()) {
            for (int i = 0; i < context.channelSettings.size()
                    && i < loadedChannelCount; i++) {
                FPBWizard.ChannelSetting setting = context.channelSettings.get(i);
                if (setting != null && setting.include) {
                    specs.add(new ChannelRail.ChannelSpec(i, setting.name, setting.colour));
                }
            }
        }
        if (specs.isEmpty()) {
            for (int i = 0; i < loadedChannelCount; i++) {
                specs.add(new ChannelRail.ChannelSpec(i, "C" + (i + 1), null));
            }
        }
        return specs;
    }

    private void showEmpty(String message) {
        centre.removeAll();
        JLabel label = new JLabel(message == null ? "" : message);
        label.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        centre.add(label, BorderLayout.NORTH);
        centre.revalidate();
        centre.repaint();
        summary.setText("Choose images");
        rail = null;
        updateAdvanceState();
    }

    private void buildScreen() {
        if (data == null) {
            showEmpty("Choose images and channels first.");
            return;
        }
        centre.removeAll();
        gridsByGroup.clear();
        picksByGroup.clear();

        rail = new ChannelRail(data.channelSpecs(), data.histograms(),
                data.subjectStats().statisticName());
        rail.setPreferredSize(new java.awt.Dimension(240, 520));
        rail.setListener(new ChannelRail.Listener() {
            @Override public void rangeChanged(boolean adjusting) {
                requestRender(adjusting);
                updatePicksStrip();
                updateAdvanceState();
            }
            @Override public void focusChanged(int channelIndex) {
                if (picksStrip != null) {
                    picksStrip.setFocusedChannel(channelIndex,
                            channelName(channelIndex));
                    updatePicksStrip();
                }
            }
        });
        centre.add(rail, BorderLayout.WEST);

        tabs = new JTabbedPane();
        for (String group : data.subjectStats().groups()) {
            PanelGrid grid = new PanelGrid(group, rowsForGroup(group),
                    RowImage.Layout.standard(data.channelSpecs().size()));
            grid.subjectList().addListSelectionListener(new ListSelectionListener() {
                @Override public void valueChanged(ListSelectionEvent event) {
                    if (!event.getValueIsAdjusting()) selectionChanged();
                }
            });
            gridsByGroup.put(group, grid);
            tabs.addTab(group, grid);
            selectInitialPick(group, grid);
        }
        centre.add(tabs, BorderLayout.CENTER);

        picksStrip = new PicksStrip(data.subjectStats().groups());
        picksStrip.setPreferredSize(new java.awt.Dimension(360, 520));
        picksStrip.setListener(new PicksStrip.Listener() {
            @Override public void pickClicked(String group) {
                selectGroup(group);
            }
        });
        if (!data.channelSpecs().isEmpty()) {
            ChannelRail.ChannelSpec spec = data.channelSpecs().get(0);
            picksStrip.setFocusedChannel(spec.channelIndex(), spec.name());
        }
        centre.add(picksStrip, BorderLayout.EAST);

        summary.setText(shortlistSummary());
        updateTabLabels();
        updatePicksStrip();
        requestRender(false);
        centre.revalidate();
        centre.repaint();
    }

    private List<RowImage.SubjectRow> rowsForGroup(String group) {
        SpinePainter.GroupData spineData = SpinePainter.groupData(data.selectionRecords(),
                group, data.subjectStats().subjectsInGroup(group));
        List<RowImage.SubjectRow> rows = new ArrayList<RowImage.SubjectRow>();
        Map<String, Integer> firstImageBySubject = firstImageIndexBySubject(group);
        Suggestion.Result suggestion = data.suggestions().get(group);
        for (String subject : data.subjectStats().subjectsInGroup(group)) {
            Integer imageIndex = firstImageBySubject.get(subject);
            if (imageIndex == null) continue;
            boolean suggested = suggestion != null && suggestion.isSuggested(subject);
            rows.add(new RowImage.SubjectRow(group, subject, imageIndex.intValue(),
                    suggested, spineData));
        }
        return rows;
    }

    private Map<String, Integer> firstImageIndexBySubject(String group) {
        LinkedHashMap<String, Integer> map = new LinkedHashMap<String, Integer>();
        List<MetadataRow> rows = data.table().rows();
        for (int imageIndex = 0; imageIndex < rows.size(); imageIndex++) {
            MetadataRow row = rows.get(imageIndex);
            if (!group.equals(row.group) || map.containsKey(row.subject)) continue;
            map.put(row.subject, Integer.valueOf(imageIndex));
        }
        return map;
    }

    private void selectInitialPick(String group, PanelGrid grid) {
        Suggestion.Result suggestion = data.suggestions().get(group);
        int selected = 0;
        if (suggestion != null) {
            for (int i = 0; i < grid.rowCount(); i++) {
                if (suggestion.isSuggested(grid.rowAt(i).subject())) {
                    selected = i;
                    break;
                }
            }
        }
        if (grid.rowCount() > 0) {
            grid.setSelectedRowIndex(selected);
            picksByGroup.put(group, grid.rowAt(selected));
        }
    }

    private void selectionChanged() {
        for (Map.Entry<String, PanelGrid> entry : gridsByGroup.entrySet()) {
            RowImage.SubjectRow selected = entry.getValue().selectedSubject();
            if (selected != null) picksByGroup.put(entry.getKey(), selected);
        }
        updateTabLabels();
        updatePicksStrip();
        requestRender(false);
    }

    private void selectGroup(String group) {
        if (tabs == null) return;
        int index = new ArrayList<String>(gridsByGroup.keySet()).indexOf(group);
        if (index >= 0) tabs.setSelectedIndex(index);
    }

    private void requestRender(boolean adjusting) {
        if (rail == null || data == null || !rail.allRangesLocked()) return;
        PanelGrid grid = currentGrid();
        if (grid == null) return;
        renderThread.request(grid.createRenderRequest(data.planes(), data.histograms(),
                rail.channelRequests(), adjusting));
    }

    private PanelGrid currentGrid() {
        if (tabs == null || tabs.getSelectedIndex() < 0) return null;
        String group = new ArrayList<String>(gridsByGroup.keySet())
                .get(tabs.getSelectedIndex());
        return gridsByGroup.get(group);
    }

    private void updatePicksStrip() {
        if (picksStrip == null || rail == null || data == null) return;
        List<FPBRenderer.ChannelRequest> channels = rail.channelRequests();
        picksStrip.updatePicks(picksByGroup, data.planes(), data.histograms(), channels);
    }

    private void updateTabLabels() {
        if (tabs == null) return;
        int i = 0;
        for (String group : gridsByGroup.keySet()) {
            String label = group + (picksByGroup.containsKey(group) ? " \u25cf" : "");
            tabs.setTitleAt(i, label);
            i++;
        }
    }

    private String shortlistSummary() {
        List<String> parts = new ArrayList<String>();
        for (Suggestion.Result result : data.suggestions().values()) {
            List<String> shortlist = result.shortlist();
            if (shortlist.isEmpty()) continue;
            String text = result.group() + ": " + shortlist.get(0);
            if (shortlist.size() > 1) {
                text += " (next: " + join(shortlist.subList(1, shortlist.size())) + ")";
            }
            parts.add(text);
        }
        if (parts.isEmpty()) return "Suggested subjects";
        return "Suggested: " + join(parts);
    }

    private String channelName(int channelIndex) {
        if (data == null) return "";
        for (ChannelRail.ChannelSpec spec : data.channelSpecs()) {
            if (spec.channelIndex() == channelIndex) return spec.name();
        }
        return "";
    }

    private void updateAdvanceState() {
        boolean ready = canAdvance();
        hint.setVisible(!ready);
        if (advanceStateListener != null) advanceStateListener.run();
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(", ");
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    public static final class Data {
        private final MetadataTable table;
        private final PlaneCache planes;
        private final HistogramCache histograms;
        private final List<ChannelRail.ChannelSpec> channelSpecs;
        private final SubjectAggregator.SubjectStats subjectStats;
        private final Map<String, Suggestion.Result> suggestions;
        private final List<SelectionRecord> selectionRecords;

        public Data(MetadataTable table, PlaneCache planes, HistogramCache histograms,
                List<ChannelRail.ChannelSpec> channelSpecs,
                SubjectAggregator.SubjectStats subjectStats,
                Map<String, Suggestion.Result> suggestions,
                List<SelectionRecord> selectionRecords) {
            if (table == null) throw new IllegalArgumentException("table must not be null");
            if (planes == null) throw new IllegalArgumentException("planes must not be null");
            if (histograms == null) throw new IllegalArgumentException("histograms must not be null");
            if (channelSpecs == null || channelSpecs.isEmpty()) {
                throw new IllegalArgumentException("channelSpecs must not be empty");
            }
            if (subjectStats == null) {
                throw new IllegalArgumentException("subjectStats must not be null");
            }
            this.table = table;
            this.planes = planes;
            this.histograms = histograms;
            this.channelSpecs = Collections.unmodifiableList(
                    new ArrayList<ChannelRail.ChannelSpec>(channelSpecs));
            this.subjectStats = subjectStats;
            this.suggestions = suggestions == null
                    ? Collections.<String, Suggestion.Result>emptyMap()
                    : Collections.unmodifiableMap(
                            new LinkedHashMap<String, Suggestion.Result>(suggestions));
            this.selectionRecords = selectionRecords == null
                    ? Collections.<SelectionRecord>emptyList()
                    : Collections.unmodifiableList(
                            new ArrayList<SelectionRecord>(selectionRecords));
        }

        public MetadataTable table() {
            return table;
        }

        public PlaneCache planes() {
            return planes;
        }

        public HistogramCache histograms() {
            return histograms;
        }

        public List<ChannelRail.ChannelSpec> channelSpecs() {
            return channelSpecs;
        }

        public SubjectAggregator.SubjectStats subjectStats() {
            return subjectStats;
        }

        public Map<String, Suggestion.Result> suggestions() {
            return suggestions;
        }

        public List<SelectionRecord> selectionRecords() {
            return selectionRecords;
        }
    }
}
