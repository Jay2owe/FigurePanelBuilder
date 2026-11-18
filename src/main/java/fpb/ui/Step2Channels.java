/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui;

import fpb.FPBParameters;
import fpb.io.ImageLayout;
import fpb.io.ImageLoader;
import fpb.io.ImageSource;
import fpb.meta.MetadataRow;
import fpb.render.ChannelColour;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.JTextField;

/** Second wizard step: included channels, names, LUT colours and z handling. */
public final class Step2Channels implements WizardStep {

    private static final String[] COLOUR_NAMES = new String[] {
            "Blue", "Magenta", "Green", "Cyan", "Yellow", "Grey", "Red"
    };
    private static final String[] Z_HANDLING = new String[] {
            "Maximum projection", "First slice"
    };

    private final FPBWizard.Context context;
    private final Runnable chooseAnotherFolderAction;
    private final Runnable quickGridAction;
    private final JPanel panel;
    private final JLabel detectedLabel;
    private final JLabel recoveryDetail;
    private final JPanel recoveryActions;
    private final JButton retryButton;
    private final JButton chooseAnotherFolderButton;
    private final JPanel channelsPanel;
    private final JComboBox<String> zHandling;
    private final JComboBox<String> statisticSource;
    private final JTextField statisticCsvPath;
    private final JTextField statisticColumn;
    private ImageSource lastSource;
    private boolean detectionFailed;
    private boolean detectionInProgress;
    private List<ChannelColour> detectedColours = new ArrayList<ChannelColour>();
    private ImageLayout detectedLayout = ImageLayout.unknown();
    private String screeningNotice = "";
    private List<ImageSource> lastScreenedSources = new ArrayList<ImageSource>();
    /** Overlays already left out once; a user who re-ticks one keeps it. */
    private final java.util.Set<ImageSource> autoLeftOut =
            new java.util.HashSet<ImageSource>();
    private SwingWorker<ImageLoader.LoadedImage, Void> detectionWorker;

    public Step2Channels(FPBWizard.Context context) {
        this(context, null, null);
    }

    public Step2Channels(FPBWizard.Context context,
            Runnable chooseAnotherFolderAction) {
        this(context, chooseAnotherFolderAction, null);
    }

    public Step2Channels(FPBWizard.Context context,
            Runnable chooseAnotherFolderAction, Runnable quickGridAction) {
        this.context = context;
        this.chooseAnotherFolderAction = chooseAnotherFolderAction;
        this.quickGridAction = quickGridAction;
        panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));

        JPanel status = new JPanel();
        status.setLayout(new javax.swing.BoxLayout(status,
                javax.swing.BoxLayout.Y_AXIS));
        detectedLabel = new JLabel();
        detectedLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        status.add(detectedLabel);
        recoveryDetail = new JLabel();
        recoveryDetail.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        recoveryDetail.setVisible(false);
        status.add(recoveryDetail);
        recoveryActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        recoveryActions.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        retryButton = new JButton("Retry");
        retryButton.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                retryDetection();
            }
        });
        recoveryActions.add(retryButton);
        chooseAnotherFolderButton = new JButton("Choose another folder");
        chooseAnotherFolderButton.addActionListener(
                new java.awt.event.ActionListener() {
                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent event) {
                        chooseAnotherFolder();
                    }
                });
        recoveryActions.add(chooseAnotherFolderButton);
        recoveryActions.setVisible(false);
        status.add(recoveryActions);
        panel.add(status, BorderLayout.NORTH);

        channelsPanel = new JPanel(new GridLayout(0, 1, 6, 6));
        panel.add(channelsPanel, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new GridLayout(0, 1, 4, 4));
        JPanel zRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        zRow.add(new JLabel("Z handling"));
        zHandling = new FitComboBox<String>(Z_HANDLING);
        zHandling.setSelectedItem(context.zHandling);
        zHandling.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                context.zHandling = (String) zHandling.getSelectedItem();
                context.invalidateGuidedDownstream(1);
                preloadCurrentImages();
            }
        });
        zRow.add(zHandling);
        bottom.add(zRow);

        JPanel statisticRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        statisticRow.add(new JLabel("Guiding statistic"));
        statisticSource = new FitComboBox<String>(new String[] {
                "Built-in brightest 1%", "Numeric CSV column"
        });
        statisticSource.setSelectedIndex(context.statisticCsv == null ? 0 : 1);
        statisticSource.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (statisticSource.getSelectedIndex() == 0) {
                    clearStatisticSelection();
                }
                updateStatisticControls();
                context.invalidateGuidedDownstream(1);
            }
        });
        statisticRow.add(statisticSource);
        statisticCsvPath = new JTextField(context.statisticCsv == null ? ""
                : context.statisticCsv.getAbsolutePath(), 18);
        statisticCsvPath.setEditable(false);
        statisticRow.add(statisticCsvPath);
        JButton browseStatistic = new JButton("Browse...");
        browseStatistic.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Choose statistic CSV");
                if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
                    setStatisticCsv(chooser.getSelectedFile(),
                            statisticColumn.getText());
                }
            }
        });
        statisticRow.add(browseStatistic);
        statisticRow.add(new JLabel("Column"));
        statisticColumn = new JTextField(context.statisticColumn, 12);
        statisticColumn.getDocument().addDocumentListener(
                new javax.swing.event.DocumentListener() {
                    @Override public void insertUpdate(javax.swing.event.DocumentEvent e) {
                        update();
                    }
                    @Override public void removeUpdate(javax.swing.event.DocumentEvent e) {
                        update();
                    }
                    @Override public void changedUpdate(javax.swing.event.DocumentEvent e) {
                        update();
                    }
                    private void update() {
                        context.statisticColumn = statisticColumn.getText().trim();
                        context.invalidateGuidedDownstream(1);
                    }
                });
        statisticRow.add(statisticColumn);
        bottom.add(statisticRow);

        if (quickGridAction != null) {
            JPanel quickGridRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            JButton quickGrid = new JButton("Quick grid");
            quickGrid.addActionListener(new java.awt.event.ActionListener() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    if (!channelsValid()) {
                        javax.swing.JOptionPane.showMessageDialog(panel,
                                "Include at least one channel and give each "
                                + "included channel a unique name.",
                                "Figure Panel Builder",
                                javax.swing.JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                    Step2Channels.this.quickGridAction.run();
                }
            });
            quickGridRow.add(quickGrid);
            quickGridRow.add(new JLabel("Every included image in one grid, "
                    + "using the channels above"));
            bottom.add(quickGridRow);
        }
        updateStatisticControls();
        panel.add(bottom, BorderLayout.SOUTH);
    }

    @Override
    public String title() {
        return "Channels";
    }

    @Override
    public String nextTitle() {
        return "Choose images";
    }

    @Override
    public JPanel component() {
        return panel;
    }

    @Override
    public void onShow() {
        screenIncludedImages();
        ImageSource source = firstSource();
        if (source == null || !source.equals(lastSource) || detectionFailed) {
            if (source != null
                    && ImageLoader.isOfflinePlaceholder(source.file())) {
                startDetection(source);
            } else {
                detectChannels(source);
                rebuildRows();
            }
            lastSource = source;
        }
    }

    @Override
    public boolean canAdvance() {
        if (!channelsValid()) return false;
        return statisticSource.getSelectedIndex() == 0
                || (context.statisticCsv != null && context.statisticCsv.isFile()
                && context.statisticColumn != null
                && !context.statisticColumn.trim().isEmpty());
    }

    /** True when detection finished and the included channels have usable names. */
    public boolean channelsValid() {
        if (detectionFailed || detectionInProgress) return false;
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<String>();
        for (FPBWizard.ChannelSetting setting : context.channelSettings) {
            if (!setting.include) continue;
            String name = setting.name == null ? "" : setting.name.trim();
            if (name.isEmpty() || FPBParameters.isReservedChannelName(name)
                    || !names.add(name.toLowerCase(java.util.Locale.ROOT))) {
                return false;
            }
        }
        return !names.isEmpty();
    }

    /** Moves the channel at {@code position} earlier (negative) or later in the panel order. */
    public boolean moveChannel(int position, int delta) {
        int target = position + delta;
        List<FPBWizard.ChannelSetting> settings = context.channelSettings;
        if (position < 0 || position >= settings.size()
                || target < 0 || target >= settings.size()) {
            return false;
        }
        java.util.Collections.swap(settings, position, target);
        context.invalidateGuidedDownstream(1);
        rebuildRows();
        return true;
    }

    public int detectedChannelCount() {
        return context.detectedChannelCount;
    }

    public List<FPBWizard.ChannelSetting> channelSettings() {
        return context.channelSettings;
    }

    public String detectionMessage() {
        return detectedLabel.getText() + " " + recoveryDetail.getText();
    }

    public boolean retryVisible() {
        return recoveryActions.isVisible() && retryButton.isVisible();
    }

    public boolean chooseAnotherFolderVisible() {
        return recoveryActions.isVisible()
                && chooseAnotherFolderButton.isVisible();
    }

    public void retryDetection() {
        ImageSource source = firstSource();
        if (source == null) {
            detectChannels(null);
            rebuildRows();
            return;
        }
        startDetection(source);
    }

    public void chooseAnotherFolder() {
        if (detectionWorker != null) detectionWorker.cancel(true);
        detectionInProgress = false;
        if (chooseAnotherFolderAction != null) chooseAnotherFolderAction.run();
    }

    public void setStatisticCsv(File csv, String column) {
        context.statisticCsv = csv == null ? null : csv.getAbsoluteFile();
        context.statisticColumn = column == null ? "" : column.trim();
        statisticCsvPath.setText(context.statisticCsv == null ? ""
                : context.statisticCsv.getAbsolutePath());
        statisticColumn.setText(context.statisticColumn);
        statisticSource.setSelectedIndex(context.statisticCsv == null ? 0 : 1);
        updateStatisticControls();
        context.invalidateGuidedDownstream(1);
    }

    private void updateStatisticControls() {
        boolean external = statisticSource.getSelectedIndex() == 1;
        statisticCsvPath.setEnabled(external);
        statisticColumn.setEnabled(external);
    }

    private void clearStatisticSelection() {
        context.statisticCsv = null;
        context.statisticColumn = "";
        statisticCsvPath.setText("");
        statisticColumn.setText("");
    }

    private void detectChannels(ImageSource source) {
        List<ChannelColour> colours = new ArrayList<ChannelColour>();
        for (int i = 0; i < 3; i++) colours.add(null);
        ImageLayout layout = ImageLayout.unknown();
        detectionFailed = false;
        detectionInProgress = false;
        if (source != null && source.file().isFile()) {
            try {
                ImageLoader.LoadResult preloaded = context.imagePreloader.readyResult(
                        currentSources(), ImageLoader.ZMode.fromString(
                                context.zHandling));
                if (preloaded == null) {
                    ImageLoader.LoadedImage loaded = new ImageLoader().loadImage(source);
                    colours = loaded.channelColours();
                    layout = loaded.layout();
                } else {
                    colours = preloaded.channelColours();
                    layout = preloaded.firstLayout();
                }
            } catch (IOException failure) {
                showDetectionFailure(source, failure);
                return;
            }
        }
        showDetectionSuccess(colours, layout);
    }

    private void startDetection(final ImageSource source) {
        if (detectionWorker != null && !detectionWorker.isDone()) {
            detectionWorker.cancel(true);
        }
        detectionFailed = false;
        detectionInProgress = true;
        context.detectedChannelCount = 0;
        context.channelSettings = new ArrayList<FPBWizard.ChannelSetting>();
        rebuildRows();
        detectedLabel.setText("<html><b>Requesting a local copy and detecting "
                + "channels...</b></html>");
        recoveryDetail.setText("<html><body style='width: 760px'>"
                + "This image is stored online-only. Figure Panel Builder is "
                + "asking Windows and the cloud provider for its data before opening it "
                + "with Bio-Formats.<br><span style='color:#555555'>"
                + escapeHtml(source.file().getAbsolutePath())
                + "</span></body></html>");
        recoveryDetail.setVisible(true);
        recoveryActions.setVisible(false);

        final SwingWorker<ImageLoader.LoadedImage, Void> worker =
                new SwingWorker<ImageLoader.LoadedImage, Void>() {
            @Override
            protected ImageLoader.LoadedImage doInBackground() throws Exception {
                return new ImageLoader().loadImage(source);
            }

            @Override
            protected void done() {
                if (detectionWorker != this) return;
                detectionInProgress = false;
                if (isCancelled()) return;
                try {
                    ImageLoader.LoadedImage loaded = get();
                    showDetectionSuccess(loaded.channelColours(), loaded.layout());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    showDetectionFailure(source, new IOException(
                            "Channel detection was interrupted.", interrupted));
                } catch (java.util.concurrent.ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    showDetectionFailure(source, cause instanceof IOException
                            ? (IOException) cause
                            : new IOException("Could not open the image.", cause));
                }
                rebuildRows();
            }
        };
        detectionWorker = worker;
        worker.execute();
    }

    private void showDetectionSuccess(List<ChannelColour> colours,
            ImageLayout layout) {
        detectionFailed = false;
        detectionInProgress = false;
        detectedColours = colours == null
                ? new ArrayList<ChannelColour>()
                : new ArrayList<ChannelColour>(colours);
        detectedLayout = layout == null ? ImageLayout.unknown() : layout;
        context.detectedChannelCount = Math.max(1, detectedColours.size());
        ensureSettings();
        boolean fromFile = false;
        for (ChannelColour colour : detectedColours) fromFile |= colour != null;
        detectedLabel.setText("Detected " + context.detectedChannelCount
                + (context.detectedChannelCount == 1 ? " channel" : " channels")
                + " in the first image"
                + (fromFile ? "; starting colours were read from the file" : "")
                + ". The arrows change the panel order.");
        String notice = layoutNotice(detectedLayout, firstSource());
        if (!screeningNotice.isEmpty()) {
            notice = notice.isEmpty() ? screeningNotice
                    : screeningNotice + "<br><br>" + notice;
        }
        if (!autoLeftOut.isEmpty() && detectedColours.size() <= 1) {
            notice += "<br><br>" + SEPARATE_CHANNEL_FILES_HINT;
        }
        recoveryDetail.setText(notice.isEmpty() ? ""
                : "<html><body style='width: 760px'>" + notice + "</body></html>");
        recoveryDetail.setVisible(!notice.isEmpty());
        recoveryActions.setVisible(false);
    }

    static final String SEPARATE_CHANNEL_FILES_HINT = "<b>The remaining images have "
            + "1 channel each.</b> If they are separate channels of each sample (for "
            + "example <i>_DAPI</i>, <i>_FITC</i>, <i>_TX RED</i>, <i>_CY 3</i>), combine "
            + "them into one composite TIFF per sample first: in Fiji, Image &gt; Color "
            + "&gt; Merge Channels with <i>Create composite</i> ticked, then save as TIFF.";

    /** Plain-language warning when the first image's channels are not real channel data. */
    static String layoutNotice(ImageLayout layout, ImageSource source) {
        if (layout == null) return "";
        String name = source == null ? "The first image"
                : escapeHtml(source.file().getName());
        if (layout.rgbOverlay()) {
            return "<b>" + name + " is an RGB colour picture, not channel data.</b> "
                    + "Files such as the Echo Revolution's <i>_Merge.tif</i> or "
                    + "ImageJ's <i>_RGB.tif</i> blend every channel into red, green "
                    + "and blue when saved, so only those 3 colours can be shown "
                    + "and the original channels cannot be separated or given their "
                    + "own display ranges. Use the per-channel files (for example "
                    + "<i>_DAPI</i>, <i>_FITC</i>, <i>_TX RED</i>, <i>_CY 3</i>) "
                    + "combined into one composite TIFF: in Fiji, "
                    + "Image &gt; Color &gt; Merge Channels with <i>Create composite</i> "
                    + "ticked, then save as TIFF.";
        }
        if (layout.planesWithoutChannelAxis()) {
            boolean z = layout.zPlanes() > 1;
            int planes = z ? layout.zPlanes() : layout.timePoints();
            return "<b>" + name + " has 1 channel and " + planes
                    + (z ? " Z planes" : " time points")
                    + ", with no channel axis.</b> If those planes are really "
                    + "channels, the file lost its dimensions when it was saved: "
                    + "in Fiji use Image &gt; Hyperstacks &gt; Re-order Hyperstack "
                    + "to make them channels, then save as TIFF. Otherwise "
                    + (z ? "the planes are combined by the Z handling below."
                    : "only the first time point is used.");
        }
        return "";
    }

    /**
     * Reads the header of every included TIFF. RGB colour copies saved beside
     * real multichannel images are left out (they would otherwise set the
     * channel count to 3 or stop loading); mixed channel counts are reported.
     */
    private void screenIncludedImages() {
        List<ImageSource> sources = currentSources();
        if (sources.equals(lastScreenedSources)) return;
        screeningNotice = "";
        List<ImageSource> overlays = new ArrayList<ImageSource>();
        int judgedByName = 0;
        java.util.Map<Integer, List<ImageSource>> byCount =
                new java.util.TreeMap<Integer, List<ImageSource>>();
        for (ImageSource source : sources) {
            ImageLayout layout = ImageLayout.probe(source);
            boolean offlineCopy = !source.isSeries()
                    && ImageLoader.isOfflinePlaceholder(source.file())
                    && ImageLayout.namedLikeRgbCopy(source.file());
            if (layout.rgbOverlay() || offlineCopy) {
                overlays.add(source);
                if (offlineCopy) judgedByName++;
            } else if (layout.channels() != ImageLayout.UNKNOWN) {
                Integer key = Integer.valueOf(layout.channels());
                if (!byCount.containsKey(key)) {
                    byCount.put(key, new ArrayList<ImageSource>());
                }
                byCount.get(key).add(source);
            }
        }
        boolean hasChannelData = overlays.size() < sources.size();
        overlays.removeAll(autoLeftOut);
        if (!overlays.isEmpty() && hasChannelData && context.allImagesTable != null) {
            java.util.Set<ImageSource> leaveOut =
                    new java.util.HashSet<ImageSource>(overlays);
            autoLeftOut.addAll(overlays);
            for (MetadataRow row : context.allImagesTable.rows()) {
                if (leaveOut.contains(row.source)) row.included = false;
            }
            context.metadataTable = context.allImagesTable.includedTable();
            context.invalidateGuidedDownstream(1);
            screeningNotice = "<b>Left out " + overlays.size() + " RGB colour "
                    + (overlays.size() == 1 ? "copy" : "copies") + "</b> (for example "
                    + escapeHtml(overlays.get(0).file().getName()) + "): they are "
                    + "flattened red/green/blue pictures of the channels, not channel "
                    + "data. Tick them again under Images to use them."
                    + (judgedByName == 0 ? "" : " " + judgedByName
                    + (judgedByName == 1 ? " is" : " are") + " online-only and "
                    + (judgedByName == 1 ? "was" : "were") + " judged by the "
                    + "<i>_RGB</i>/<i>_Merge</i> name without downloading.");
            sources = currentSources();
        }
        if (byCount.size() > 1) {
            StringBuilder counts = new StringBuilder();
            for (java.util.Map.Entry<Integer, List<ImageSource>> entry
                    : byCount.entrySet()) {
                if (counts.length() > 0) counts.append("; ");
                counts.append(entry.getKey()).append(entry.getKey() == 1
                        ? " channel in " : " channels in ")
                        .append(entry.getValue().size()).append(" (for example ")
                        .append(escapeHtml(entry.getValue().get(0).file().getName()))
                        .append(")");
            }
            String mixed = "<b>The included images have different channel counts:</b> "
                    + counts + ". Every image in a figure needs the same channels; "
                    + "untick the ones that do not match under Images.";
            screeningNotice = screeningNotice.isEmpty() ? mixed
                    : screeningNotice + "<br><br>" + mixed;
        }
        lastScreenedSources = sources;
    }

    private void showDetectionFailure(ImageSource source, IOException failure) {
        detectionFailed = true;
        detectionInProgress = false;
        context.detectedChannelCount = 0;
        context.channelSettings = new ArrayList<FPBWizard.ChannelSetting>();
        detectedLabel.setText("<html><b>Could not open the first image</b></html>");
        String explanation = failure.getMessage() == null
                ? "Bio-Formats did not provide an error message."
                : failure.getMessage();
        String path = source == null ? "" : source.file().getAbsolutePath();
        recoveryDetail.setText("<html><body style='width: 760px'>"
                + escapeHtml(explanation)
                + "<br><br>Click <b>Retry</b> after the download completes, or "
                + "choose another image folder.<br><span style='color:#555555'>"
                + escapeHtml(path) + "</span></body></html>");
        recoveryDetail.setVisible(true);
        recoveryActions.setVisible(true);
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Keeps earlier choices in their chosen order and adds any new channels. */
    private void ensureSettings() {
        int count = context.detectedChannelCount;
        List<FPBWizard.ChannelSetting> settings =
                new ArrayList<FPBWizard.ChannelSetting>();
        boolean[] present = new boolean[count];
        for (FPBWizard.ChannelSetting existing : context.channelSettings) {
            if (existing == null || existing.sourceIndex >= count
                    || present[existing.sourceIndex]) continue;
            present[existing.sourceIndex] = true;
            settings.add(existing);
        }
        for (int i = 0; i < count; i++) {
            if (present[i]) continue;
            ChannelColour detected = i < detectedColours.size()
                    ? detectedColours.get(i) : null;
            settings.add(new FPBWizard.ChannelSetting(i, true, "C" + (i + 1),
                    detected == null ? colourFor(i) : detected));
        }
        context.channelSettings = settings;
    }

    private void rebuildRows() {
        channelsPanel.removeAll();
        for (int i = 0; i < context.channelSettings.size(); i++) {
            final FPBWizard.ChannelSetting setting = context.channelSettings.get(i);
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            final ToggleSwitch include = new ToggleSwitch(setting.include);
            include.addChangeListener(new Runnable() {
                @Override
                public void run() {
                    setting.include = include.isSelected();
                    context.invalidateGuidedDownstream(1);
                }
            });
            row.add(include);
            row.add(new JLabel("C" + (setting.sourceIndex + 1)));
            final JTextField name = new JTextField(setting.name, 12);
            name.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override public void insertUpdate(javax.swing.event.DocumentEvent event) {
                    update();
                }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent event) {
                    update();
                }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent event) {
                    update();
                }
                private void update() {
                    setting.name = name.getText().trim();
                    context.invalidateGuidedDownstream(1);
                }
            });
            row.add(name);
            final JComboBox<String> colour = new FitComboBox<String>(COLOUR_NAMES);
            colour.setSelectedItem(titleCase(setting.colour.name()));
            colour.addActionListener(new java.awt.event.ActionListener() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    setting.colour = ChannelColour.fromName((String) colour.getSelectedItem());
                    context.invalidateGuidedDownstream(1);
                }
            });
            row.add(colour);
            final int position = i;
            JButton up = new JButton("▲");
            up.setToolTipText("Move this channel one panel to the left");
            up.setEnabled(i > 0);
            up.addActionListener(new java.awt.event.ActionListener() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    moveChannel(position, -1);
                }
            });
            row.add(up);
            JButton down = new JButton("▼");
            down.setToolTipText("Move this channel one panel to the right");
            down.setEnabled(i < context.channelSettings.size() - 1);
            down.addActionListener(new java.awt.event.ActionListener() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    moveChannel(position, 1);
                }
            });
            row.add(down);
            channelsPanel.add(row);
        }
        channelsPanel.revalidate();
        channelsPanel.repaint();
    }

    private ImageSource firstSource() {
        if (context.metadataTable == null || context.metadataTable.rows().isEmpty()) return null;
        MetadataRow row = context.metadataTable.rows().get(0);
        return row.source;
    }

    private List<ImageSource> currentSources() {
        List<ImageSource> sources = new ArrayList<ImageSource>();
        if (context.metadataTable == null) return sources;
        for (MetadataRow row : context.metadataTable.rows()) sources.add(row.source);
        return sources;
    }

    private void preloadCurrentImages() {
        List<ImageSource> sources = currentSources();
        if (sources.isEmpty()) return;
        context.imagePreloader.preload(sources, ImageLoader.ZMode.fromString(
                context.zHandling), null);
    }

    private static ChannelColour colourFor(int index) {
        return ChannelColour.fromName(COLOUR_NAMES[index % COLOUR_NAMES.length]);
    }

    private static String titleCase(String value) {
        if (value == null || value.length() == 0) return "Grey";
        return value.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                + value.substring(1).toLowerCase(java.util.Locale.ROOT);
    }
}
