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
    private SwingWorker<Integer, Void> detectionWorker;

    public Step2Channels(FPBWizard.Context context) {
        this(context, null);
    }

    public Step2Channels(FPBWizard.Context context,
            Runnable chooseAnotherFolderAction) {
        this.context = context;
        this.chooseAnotherFolderAction = chooseAnotherFolderAction;
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
        if (names.isEmpty()) return false;
        return statisticSource.getSelectedIndex() == 0
                || (context.statisticCsv != null && context.statisticCsv.isFile()
                && context.statisticColumn != null
                && !context.statisticColumn.trim().isEmpty());
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
        int count = 3;
        detectionFailed = false;
        detectionInProgress = false;
        if (source != null && source.file().isFile()) {
            try {
                ImageLoader.LoadResult preloaded = context.imagePreloader.readyResult(
                        currentSources(), ImageLoader.ZMode.fromString(
                                context.zHandling));
                count = preloaded == null
                        ? new ImageLoader().loadImage(source).channelCount()
                        : preloaded.channelCount();
            } catch (IOException failure) {
                showDetectionFailure(source, failure);
                return;
            }
        }
        showDetectionSuccess(count);
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

        final SwingWorker<Integer, Void> worker = new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return Integer.valueOf(new ImageLoader().loadImage(source)
                        .channelCount());
            }

            @Override
            protected void done() {
                if (detectionWorker != this) return;
                detectionInProgress = false;
                if (isCancelled()) return;
                try {
                    showDetectionSuccess(get().intValue());
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

    private void showDetectionSuccess(int count) {
        detectionFailed = false;
        detectionInProgress = false;
        context.detectedChannelCount = Math.max(1, count);
        ensureSettings();
        detectedLabel.setText("Detected " + context.detectedChannelCount
                + " channels in the first image");
        recoveryDetail.setText("");
        recoveryDetail.setVisible(false);
        recoveryActions.setVisible(false);
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

    private void ensureSettings() {
        List<FPBWizard.ChannelSetting> settings =
                new ArrayList<FPBWizard.ChannelSetting>();
        for (int i = 0; i < context.detectedChannelCount; i++) {
            FPBWizard.ChannelSetting existing =
                    i < context.channelSettings.size()
                            ? context.channelSettings.get(i)
                            : null;
            if (existing == null) {
                settings.add(new FPBWizard.ChannelSetting(true, "C" + (i + 1),
                        colourFor(i)));
            } else {
                settings.add(existing);
            }
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
            row.add(new JLabel("C" + (i + 1)));
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
