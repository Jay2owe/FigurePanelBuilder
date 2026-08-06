/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.chooser;

import fpb.figure.ImageOrientation;
import fpb.io.HistogramCache;
import fpb.io.ImageLoader;
import fpb.io.ImageSource;
import fpb.io.PlaneCache;
import fpb.io.ProgressCallback;
import fpb.meta.MetadataRow;
import fpb.meta.MetadataTable;
import fpb.render.FPBRenderer;
import fpb.stats.GroupStats;
import fpb.stats.GroupQuantification;
import fpb.stats.SelectionRecord;
import fpb.stats.StatCsvLoader;
import fpb.stats.Statistic;
import fpb.stats.SubjectAggregator;
import fpb.stats.Suggestion;
import fpb.ui.FPBWizard;
import fpb.ui.WizardStep;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Dimension;
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
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTabbedPane;
import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

/** Step 3 chooser screen: channel rail, selectable sections and current picks. */
public final class Step3Chooser implements WizardStep, AutoCloseable {

    private static final Color BACKGROUND = new Color(248, 249, 250);
    private static final Color BORDER = new Color(196, 202, 208);
    private static final Color TEXT = new Color(42, 47, 53);
    private static final Color MUTED = new Color(95, 103, 112);

    private final FPBWizard.Context context;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));
    private final JPanel centre = new JPanel(new BorderLayout());
    private final JLabel summary = new JLabel("Choose images and channels first.");
    private final JLabel hint = new JLabel("Choose one section for every group to continue.");
    private final JPanel recoveryActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
    private final JButton retryButton = new JButton("Retry");
    private final JButton editGroupsButton = new JButton("Edit groups");
    private final JButton oneGroupButton = new JButton("Use all images as one group");
    private final JProgressBar loadingProgress = new JProgressBar();
    private final RenderThread renderThread = new RenderThread();
    private final Runnable editGroupsAction;
    private Runnable advanceStateListener;

    private Data data;
    private ChannelRail rail;
    private PicksStrip picksStrip;
    private GroupComparisonPanel comparisonPanel;
    private GroupQuantification groupQuantification;
    private JTabbedPane tabs;
    private final Map<String, PanelGrid> gridsByGroup =
            new LinkedHashMap<String, PanelGrid>();
    private final Map<String, RowImage.SubjectRow> picksByGroup =
            new LinkedHashMap<String, RowImage.SubjectRow>();
    private boolean threadStarted;
    private SwingWorker<Data, Void> loadingWorker;
    private SwingWorker<List<PicksStrip.RenderedPick>, Void> picksWorker;
    private long picksGeneration;
    private String loadedContextKey = "";
    private String pendingContextKey = "";
    private String emptyMessage = "";
    private boolean recoveryShown;
    private boolean loadingStatus;

    public Step3Chooser(FPBWizard.Context context) {
        this(context, null);
    }

    public Step3Chooser(FPBWizard.Context context, Runnable editGroupsAction) {
        this.context = context;
        this.editGroupsAction = editGroupsAction;
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

        recoveryActions.setOpaque(false);
        loadingProgress.setStringPainted(true);
        loadingProgress.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        loadingProgress.setPreferredSize(new Dimension(520, 22));
        loadingProgress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        retryButton.addActionListener(new java.awt.event.ActionListener() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                retryContextLoad();
            }
        });
        recoveryActions.add(retryButton);
        editGroupsButton.addActionListener(new java.awt.event.ActionListener() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                editGroups();
            }
        });
        recoveryActions.add(editGroupsButton);
        oneGroupButton.addActionListener(new java.awt.event.ActionListener() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                useOneGroupFallback();
            }
        });
        recoveryActions.add(oneGroupButton);
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
        startContextLoadIfNeeded();
        updateAdvanceState();
    }

    @Override
    public boolean canAdvance() {
        if (rail != null) rail.commitPendingFieldEdits();
        boolean ready = data != null && rail != null
                && !data.subjectStats().groups().isEmpty()
                && picksByGroup.size() == data.subjectStats().groups().size()
                && rail.allRangesLocked();
        if (ready) publishLayoutState();
        return ready;
    }

    @Override
    public void close() {
        if (loadingWorker != null) loadingWorker.cancel(true);
        if (picksWorker != null) picksWorker.cancel(true);
        renderThread.close();
        if (context != null) context.imagePreloader.close();
    }

    public void setAdvanceStateListener(Runnable listener) {
        advanceStateListener = listener;
    }

    public void setData(Data data) {
        ensureThreadStarted();
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

    GroupComparisonPanel comparisonPanelForTest() {
        return comparisonPanel;
    }

    public Map<String, RowImage.SubjectRow> picksForTest() {
        return Collections.unmodifiableMap(picksByGroup);
    }

    public int renderedRowCountForTest(String group) {
        PanelGrid grid = gridsByGroup.get(group);
        return grid == null ? 0 : grid.renderedRowCountForTest();
    }

    int gridRowCountForTest(String group) {
        PanelGrid grid = gridsByGroup.get(group);
        return grid == null ? 0 : grid.rowCount();
    }

    int gridSectionCountForTest(String group) {
        PanelGrid grid = gridsByGroup.get(group);
        return grid == null ? 0 : grid.layoutForCurrentScale().sectionCount();
    }

    int gridCellHeightForTest(String group) {
        PanelGrid grid = gridsByGroup.get(group);
        return grid == null ? 0 : grid.subjectList().getFixedCellHeight();
    }

    public String emptyMessageForTest() {
        return emptyMessage;
    }

    public boolean groupRecoveryVisibleForTest() {
        return recoveryShown && oneGroupButton.isVisible();
    }

    public void editGroupsForTest() {
        editGroups();
    }

    public void useOneGroupFallbackForTest() {
        useOneGroupFallback();
    }

    public void selectSubjectForTest(String group, String subject) {
        PanelGrid grid = gridsByGroup.get(group);
        if (grid == null) throw new IllegalArgumentException("Unknown group: " + group);
        for (int i = 0; i < grid.rowCount(); i++) {
            if (grid.rowAt(i).subject().equals(subject)) {
                grid.setSelectedRowIndex(i);
                selectionChanged();
                return;
            }
        }
        throw new IllegalArgumentException("Unknown subject: " + subject);
    }

    public void selectSectionForTest(String group, String subject, String section) {
        PanelGrid grid = gridsByGroup.get(group);
        if (grid == null) throw new IllegalArgumentException("Unknown group: " + group);
        for (int i = 0; i < grid.rowCount(); i++) {
            RowImage.SubjectRow row = grid.rowAt(i);
            if (row.subject().equals(subject) && row.section().equals(section)) {
                grid.setSelectedRowIndex(i);
                selectionChanged();
                return;
            }
        }
        throw new IllegalArgumentException("Unknown section: " + subject + " / " + section);
    }

    private void ensureThreadStarted() {
        if (threadStarted) return;
        renderThread.start();
        threadStarted = true;
    }

    private void startContextLoadIfNeeded() {
        if (context == null || context.metadataTable == null
                || context.metadataTable.rows().isEmpty()) {
            showRecovery("No images are available for representative selection. "
                    + "Return to Images and choose a folder.", false);
            return;
        }
        String groupingProblem = groupingProblem(context.metadataTable);
        if (groupingProblem.length() > 0) {
            data = null;
            rail = null;
            picksStrip = null;
            picksByGroup.clear();
            invalidateDownstreamState();
            showRecovery(groupingProblem, true);
            return;
        }
        final String key = contextKey();
        if (data != null && key.equals(loadedContextKey)) return;
        if (loadingWorker != null && !loadingWorker.isDone()
                && key.equals(pendingContextKey)) return;
        if (loadingWorker != null) loadingWorker.cancel(true);
        pendingContextKey = key;
        data = null;
        rail = null;
        picksStrip = null;
        comparisonPanel = null;
        picksByGroup.clear();
        invalidateDownstreamState();
        showLoading(context.metadataTable.rows().size());
        loadingWorker = new SwingWorker<Data, Void>() {
            @Override
            protected Data doInBackground() throws Exception {
                return buildFromContext(new ProgressCallback() {
                    @Override
                    public void onProgress(final int completed, final int total,
                            final File file) {
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override public void run() {
                                updateLoadingProgress(key, completed, total, file);
                            }
                        });
                    }
                });
            }

            @Override
            protected void done() {
                if (isCancelled() || !key.equals(contextKey())) return;
                try {
                    Data loaded = get();
                    loadedContextKey = key;
                    setData(loaded);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    showRecovery("Image loading was interrupted. Retry, or return "
                            + "to Images to choose a different folder.", false);
                } catch (java.util.concurrent.ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    String message = cause == null
                            ? failure.getMessage() : cause.getMessage();
                    showRecovery("Could not prepare the image chooser. "
                            + (message == null ? "No error detail was provided." : message),
                            false);
                }
            }
        };
        loadingWorker.execute();
    }

    private Data buildFromContext(ProgressCallback progress) throws IOException {
        List<ImageSource> sources = new ArrayList<ImageSource>();
        for (MetadataRow row : context.metadataTable.rows()) sources.add(row.source);
        ImageLoader.ZMode zMode = ImageLoader.ZMode.fromString(context.zHandling);
        ImageLoader.LoadResult loaded = context.imagePreloader.loadOrAwait(
                sources, zMode, progress);
        List<ChannelRail.ChannelSpec> specs = channelSpecs(loaded.channelCount());
        List<Integer> includedIndices = new ArrayList<Integer>();
        List<String> includedNames = new ArrayList<String>();
        for (ChannelRail.ChannelSpec spec : specs) {
            includedIndices.add(Integer.valueOf(spec.channelIndex()));
            includedNames.add(spec.name());
        }
        Statistic.ImageValues values;
        if (context.statisticCsv != null
                || (context.statisticColumn != null
                && !context.statisticColumn.trim().isEmpty())) {
            StatCsvLoader.LoadResult imported = StatCsvLoader.load(
                    context.statisticCsv, context.statisticColumn,
                    context.metadataTable);
            if (!imported.isComplete()) {
                throw new IOException("Statistic CSV must match every input exactly once; "
                        + imported.problemSummary());
            }
            values = imported.imageValues();
        } else {
            values = Statistic.brightestOnePercentMeans(
                    loaded.histogramCache(), includedIndices, includedNames,
                    zMode);
        }
        SubjectAggregator.SubjectStats subjectStats =
                SubjectAggregator.aggregate(context.metadataTable, values);
        if (subjectStats.groups().isEmpty()) {
            throw new IOException("No usable groups and subjects remained after "
                    + "metadata aggregation.");
        }
        GroupStats groupStats = GroupStats.from(subjectStats);
        Map<String, Suggestion.Result> suggestions = Suggestion.suggest(groupStats);
        List<SelectionRecord> records =
                SelectionRecord.from(subjectStats, groupStats, suggestions);
        return new Data(context.metadataTable, loaded.planeCache(),
                loaded.histogramCache(), specs,
                subjectStats, suggestions, records);
    }

    Data buildFromContextForTest() throws IOException {
        return buildFromContext(ProgressCallback.NONE);
    }

    private String contextKey() {
        if (context == null) return "";
        StringBuilder key = new StringBuilder();
        key.append(context.zHandling).append('\n');
        key.append(context.statisticCsv == null ? ""
                : context.statisticCsv.getAbsolutePath()).append('\u001f')
                .append(context.statisticColumn == null ? ""
                        : context.statisticColumn).append('\n');
        if (context.metadataTable != null) {
            for (MetadataRow row : context.metadataTable.rows()) {
                key.append(row.source.key()).append('\u001f')
                        .append(row.group).append('\u001f').append(row.subject)
                        .append('\u001f').append(row.section).append('\n');
            }
        }
        if (context.channelSettings != null) {
            for (FPBWizard.ChannelSetting setting : context.channelSettings) {
                if (setting == null) continue;
                key.append(setting.include).append('\u001f').append(setting.name)
                        .append('\u001f').append(setting.colour).append('\n');
            }
        }
        return key.toString();
    }

    private void invalidateDownstreamState() {
        if (context == null) return;
        context.chooserData = null;
        context.selectedRowsByGroup.clear();
        context.layoutChannelRequests.clear();
        context.panelConfig = null;
        context.groupLayoutRows.clear();
        context.layoutPanelRecords.clear();
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
        loadingStatus = false;
        showStatus(message, false, false);
    }

    private void showRecovery(String message, boolean allowOneGroup) {
        loadingStatus = false;
        showStatus(message, true, allowOneGroup);
    }

    private void showLoading(int total) {
        loadingStatus = true;
        int safeTotal = Math.max(1, total);
        loadingProgress.setMinimum(0);
        loadingProgress.setMaximum(safeTotal);
        loadingProgress.setValue(0);
        loadingProgress.setString("0 of " + safeTotal + " images (0%)");
        showStatus("Loading images in the background...", false, false);
    }

    private void updateLoadingProgress(String key, int completed, int total, File file) {
        if (!loadingStatus || !key.equals(pendingContextKey)) return;
        int safeTotal = Math.max(1, total);
        int safeCompleted = Math.max(0, Math.min(completed, safeTotal));
        if (safeTotal != loadingProgress.getMaximum()) {
            loadingProgress.setMaximum(safeTotal);
        }
        if (safeCompleted < loadingProgress.getValue()) return;
        loadingProgress.setValue(safeCompleted);
        int percent = (int) Math.round(safeCompleted * 100.0 / safeTotal);
        if (safeCompleted >= safeTotal) {
            loadingProgress.setString("Images loaded — calculating suggestions...");
            hint.setText("Images are loaded; preparing statistics and suggestions.");
        } else {
            loadingProgress.setString(safeCompleted + " of " + safeTotal
                    + " images (" + percent + "%)");
            hint.setText(file == null ? "Loading image previews..."
                    : "Loading " + file.getName());
        }
        summary.setText("Choose images — loading " + safeCompleted
                + " of " + safeTotal);
    }

    private void showStatus(String message, boolean recoverable,
            boolean allowOneGroup) {
        centre.removeAll();
        emptyMessage = message == null ? "" : message;
        recoveryShown = recoverable;
        JPanel statusPanel = new JPanel();
        statusPanel.setOpaque(false);
        statusPanel.setLayout(new javax.swing.BoxLayout(statusPanel,
                javax.swing.BoxLayout.Y_AXIS));
        JLabel label = new JLabel("<html><body style='width: 720px'>"
                + (recoverable ? "<b>Choose Images needs attention</b><br><br>" : "")
                + escapeHtml(emptyMessage) + "</body></html>");
        label.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        statusPanel.add(label);
        if (loadingStatus && !recoverable) {
            loadingProgress.setBorder(BorderFactory.createEmptyBorder(4, 12, 8, 12));
            statusPanel.add(loadingProgress);
        }
        if (recoverable) {
            retryButton.setVisible(!allowOneGroup);
            editGroupsButton.setVisible(true);
            oneGroupButton.setVisible(allowOneGroup);
            recoveryActions.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            statusPanel.add(recoveryActions);
        }
        centre.add(statusPanel, BorderLayout.NORTH);
        centre.revalidate();
        centre.repaint();
        summary.setText(recoverable ? "Choose images — action needed" : "Choose images");
        rail = null;
        hint.setText(recoverable
                ? "Fix the metadata or use the explicit one-group fallback."
                : "Please wait while the images are prepared.");
        updateAdvanceState();
    }

    private void buildScreen() {
        loadingStatus = false;
        if (data == null) {
            showEmpty("Choose images and channels first.");
            return;
        }
        if (data.subjectStats().groups().isEmpty()) {
            showRecovery("No usable groups were produced from the metadata. "
                    + "Edit the group and subject columns, or use all images as "
                    + "one explicit group.", true);
            return;
        }
        centre.removeAll();
        recoveryShown = false;
        emptyMessage = "";
        gridsByGroup.clear();
        picksByGroup.clear();
        groupQuantification = GroupQuantification.from(data.subjectStats());

        rail = new ChannelRail(data.channelSpecs(), data.histograms(),
                data.subjectStats().statisticName());
        rail.setPreferredSize(new java.awt.Dimension(240, 520));
        rail.setListener(new ChannelRail.Listener() {
            @Override public void rangeChanged(boolean adjusting) {
                // Keep the last complete frame visible while the coalescing
                // renderer computes the new contrast. Its publication replaces
                // cached rows on the EDT without a blank intermediate frame.
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
            List<RowImage.SubjectRow> rows = rowsForGroup(group);
            PanelGrid grid = new PanelGrid(group, rows,
                    RowImage.Layout.standard(data.channelSpecs().size()));
            grid.setOrientationListener(new PanelGrid.OrientationListener() {
                @Override public void orientationChanged(RowImage.SubjectRow row) {
                    Step3Chooser.this.orientationChanged(row);
                }
            });
            grid.subjectList().addListSelectionListener(new ListSelectionListener() {
                @Override public void valueChanged(ListSelectionEvent event) {
                    if (!event.getValueIsAdjusting()) selectionChanged();
                }
            });
            gridsByGroup.put(group, grid);
            tabs.addTab(group, grid);
            selectInitialPick(group, grid);
        }
        tabs.addChangeListener(new javax.swing.event.ChangeListener() {
            @Override
            public void stateChanged(javax.swing.event.ChangeEvent event) {
                requestRender(false);
            }
        });
        comparisonPanel = new GroupComparisonPanel(groupQuantification);
        comparisonPanel.setChosenRows(picksByGroup);
        JPanel comparisonAndImages = new JPanel(new BorderLayout(0, 6));
        comparisonAndImages.setOpaque(false);
        comparisonAndImages.add(comparisonPanel, BorderLayout.NORTH);
        JPanel imagesAndPicks = new JPanel(new BorderLayout(8, 0));
        imagesAndPicks.setOpaque(false);
        imagesAndPicks.add(tabs, BorderLayout.CENTER);
        comparisonAndImages.add(imagesAndPicks, BorderLayout.CENTER);
        centre.add(comparisonAndImages, BorderLayout.CENTER);

        picksStrip = new PicksStrip(data.subjectStats().groups());
        picksStrip.setPreferredSize(new java.awt.Dimension(360, 520));
        picksStrip.setListener(new PicksStrip.Listener() {
            @Override public void pickClicked(String group) {
                selectGroup(group);
            }
        });
        picksStrip.setRenderListener(new Runnable() {
            @Override
            public void run() {
                updatePicksStrip();
            }
        });
        if (!data.channelSpecs().isEmpty()) {
            ChannelRail.ChannelSpec spec = data.channelSpecs().get(0);
            picksStrip.setFocusedChannel(spec.channelIndex(), spec.name());
        }
        imagesAndPicks.add(picksStrip, BorderLayout.EAST);

        summary.setText(shortlistSummary());
        updateTabLabels();
        if (comparisonPanel != null) comparisonPanel.setChosenRows(picksByGroup);
        updatePicksStrip();
        requestRender(false);
        centre.revalidate();
        centre.repaint();
    }

    private List<RowImage.SubjectRow> rowsForGroup(String group) {
        SpinePainter.GroupData spineData = SpinePainter.groupData(
                groupQuantification, group);
        List<RowImage.SubjectRow> rows = new ArrayList<RowImage.SubjectRow>();
        Suggestion.Result suggestion = data.suggestions().get(group);
        List<MetadataRow> metadata = data.table().rows();
        for (int imageIndex = 0; imageIndex < metadata.size(); imageIndex++) {
            MetadataRow image = metadata.get(imageIndex);
            if (!group.equals(image.group)) continue;
            boolean suggested = suggestion != null
                    && suggestion.isSuggested(image.subject);
            String imageId = data.table().csvFileName(image);
            rows.add(new RowImage.SubjectRow(group, image.subject, image.section,
                    imageIndex, suggested, spineData, imageId,
                    orientationFor(imageId)));
        }
        return rows;
    }

    private ImageOrientation orientationFor(String imageId) {
        if (context == null || context.imageOrientations == null) {
            return ImageOrientation.IDENTITY;
        }
        ImageOrientation orientation = context.imageOrientations.get(
                normalizeImageId(imageId));
        return orientation == null ? ImageOrientation.IDENTITY : orientation;
    }

    private void orientationChanged(RowImage.SubjectRow row) {
        if (row == null || context == null) return;
        String imageId = normalizeImageId(row.imageId());
        if (!imageId.isEmpty()) {
            if (row.orientation().isIdentity()) {
                context.imageOrientations.remove(imageId);
            } else {
                context.imageOrientations.put(imageId, row.orientation());
            }
            if (context.panelConfig != null) {
                context.panelConfig = context.panelConfig.toBuilder()
                        .imageOrientation(imageId, row.orientation()).build();
            }
        }
        context.layoutPanelRecords.clear();
        requestRender(false);
        if (picksByGroup.containsValue(row)) updatePicksStrip();
        publishLayoutState();
    }

    private static String normalizeImageId(String imageId) {
        return imageId == null ? "" : imageId.trim().replace('\\', '/');
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
        if (comparisonPanel != null) comparisonPanel.setChosenRows(picksByGroup);
        updatePicksStrip();
        requestRender(false);
        if (canAdvance()) publishLayoutState();
    }

    private void selectGroup(String group) {
        if (tabs == null) return;
        int index = new ArrayList<String>(gridsByGroup.keySet()).indexOf(group);
        if (index >= 0) tabs.setSelectedIndex(index);
    }

    private void requestRender(boolean adjusting) {
        if (rail == null || data == null) return;
        PanelGrid grid = currentGrid();
        if (grid == null) return;
        renderThread.request(grid.createRenderRequest(data.planes(), data.histograms(),
                rail.previewChannelRequests(), adjusting));
    }

    private PanelGrid currentGrid() {
        if (tabs == null || tabs.getSelectedIndex() < 0) return null;
        String group = new ArrayList<String>(gridsByGroup.keySet())
                .get(tabs.getSelectedIndex());
        return gridsByGroup.get(group);
    }

    private void updatePicksStrip() {
        if (picksStrip == null || rail == null || data == null) return;
        if (picksWorker != null) picksWorker.cancel(true);
        final long generation = ++picksGeneration;
        final PicksStrip.RenderSnapshot snapshot = picksStrip.createRenderSnapshot(
                picksByGroup, data.planes(), data.histograms(),
                rail.previewChannelRequests());
        picksWorker = new SwingWorker<List<PicksStrip.RenderedPick>, Void>() {
            @Override
            protected List<PicksStrip.RenderedPick> doInBackground() {
                return PicksStrip.render(snapshot);
            }

            @Override
            protected void done() {
                if (isCancelled() || generation != picksGeneration
                        || picksStrip == null) return;
                try {
                    picksStrip.applyRenderedPicks(get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.CancellationException cancelled) {
                    // A newer slider or selection snapshot superseded this render.
                } catch (java.util.concurrent.ExecutionException failure) {
                    // Row rendering remains usable even if the compact picks strip fails.
                }
            }
        };
        picksWorker.execute();
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
        if (parts.isEmpty()) return "Suggested animals";
        return "Suggested animals: " + join(parts);
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
        if (!recoveryShown) {
            hint.setText("Choose one section for every group to continue.");
        }
        hint.setVisible(!ready);
        if (advanceStateListener != null) advanceStateListener.run();
    }

    private void retryContextLoad() {
        loadedContextKey = "";
        pendingContextKey = "";
        startContextLoadIfNeeded();
    }

    private void editGroups() {
        if (loadingWorker != null) loadingWorker.cancel(true);
        if (editGroupsAction != null) editGroupsAction.run();
    }

    private void useOneGroupFallback() {
        if (context == null || context.metadataTable == null
                || context.metadataTable.rows().isEmpty()) return;
        if (loadingWorker != null) loadingWorker.cancel(true);
        for (MetadataRow row : context.metadataTable.rows()) {
            String subject = clean(row.subject);
            if (subject.length() == 0) subject = fallbackSubject(row);
            row.setLabels("All images", subject, row.section);
        }
        context.tableHandEdited = true;
        context.invalidateGuidedDownstream(0);
        loadedContextKey = "";
        pendingContextKey = "";
        data = null;
        startContextLoadIfNeeded();
    }

    static String groupingProblem(MetadataTable table) {
        if (table == null || table.rows().isEmpty()) {
            return "No images are available for grouping.";
        }
        int unassigned = table.unassignedCount();
        if (unassigned > 0) {
            return unassigned + (unassigned == 1 ? " image has" : " images have")
                    + " no usable group or subject. Edit the highlighted metadata "
                    + "rows, or use all images as one group.";
        }
        List<String> caseVariants = table.caseVariantGroups();
        if (!caseVariants.isEmpty()) {
            return "Some group names differ only by capitalisation: "
                    + join(caseVariants) + ". Make the names consistent, or use "
                    + "all images as one group.";
        }
        if (table.groupCount() == 0) {
            return "No usable groups are assigned. Edit the metadata, or use all "
                    + "images as one group.";
        }
        return "";
    }

    private static String fallbackSubject(MetadataRow row) {
        if (row.source.isSeries()) return row.source.seriesLabel();
        String name = row.file.getName();
        int dot = name.lastIndexOf('.');
        return clean(dot <= 0 ? name : name.substring(0, dot));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void publishLayoutState() {
        if (context == null || data == null || rail == null) return;
        context.chooserData = data;
        context.selectedRowsByGroup =
                new LinkedHashMap<String, RowImage.SubjectRow>(picksByGroup);
        context.layoutChannelRequests =
                new ArrayList<FPBRenderer.ChannelRequest>(rail.channelRequests());
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
