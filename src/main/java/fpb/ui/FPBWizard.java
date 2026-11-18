/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui;

import fpb.QuickGrid;
import fpb.io.ImageLoader;
import fpb.io.ImagePreloader;
import fpb.io.ProgressCallback;
import fpb.meta.MetadataTable;
import fpb.figure.PanelConfig;
import fpb.figure.ImageOrientation;
import fpb.figure.PanelRecord;
import fpb.render.FPBRenderer;
import fpb.render.ChannelColour;
import fpb.ui.chooser.Step3Chooser;
import fpb.ui.chooser.RowImage;
import fpb.ui.layout.Step4Layout;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

/** Five-step wizard shell for the interactive Figure Panel Builder workflow. */
public final class FPBWizard {

    private final Context context;
    private final JLabel projectStatus = new JLabel("Project not saved");
    private final JButton saveProject = new JButton("Save project...");
    private final JButton openProject = new JButton("Open project...");
    private boolean projectBusy;
    private final JDialog dialog;
    private final JPanel stepButtons;
    private final JPanel content;
    private final JButton backButton;
    private final JButton cancelButton;
    private final JButton fullScreenButton;
    private final JButton nextButton;
    private final WizardStep[] steps;
    private final JButton[] jumpButtons;
    private int stepIndex;
    private int maxCompletedIndex;
    private boolean resourcesClosed;
    private boolean fullScreen;
    private GraphicsDevice fullScreenDevice;
    private Rectangle windowedBounds;
    private SwingWorker<QuickGrid.Result, String> quickGridWorker;

    public FPBWizard() {
        this(new Context());
    }

    public FPBWizard(Context initial) {
        context = initial;
        dialog = new JDialog((Frame) null, "Figure Panel Builder", true);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                cancel();
            }

            @Override
            public void windowClosed(WindowEvent event) {
                closeResources();
            }
        });
        dialog.getContentPane().setLayout(new BorderLayout());

        Step3Chooser chooserStep = new Step3Chooser(context, new Runnable() {
            @Override
            public void run() {
                navigateTo(0);
            }
        });
        chooserStep.setAdvanceStateListener(new Runnable() {
            @Override
            public void run() {
                updateButtons();
            }
        });

        Step5Export exportStep = new Step5Export(context);
        exportStep.setBusyStateListener(new Runnable() {
            @Override
            public void run() {
                updateButtons();
            }
        });

        steps = new WizardStep[] {
                new Step1Images(context, new Runnable() {
                    @Override
                    public void run() {
                        goToQuickGrid();
                    }
                }),
                new Step2Channels(context, new Runnable() {
                    @Override
                    public void run() {
                        navigateTo(0);
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        goToQuickGrid();
                    }
                }),
                chooserStep,
                new Step4Layout(context),
                exportStep
        };

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        stepButtons = new JPanel(new GridLayout(1, steps.length, 0, 0));
        jumpButtons = new JButton[steps.length];
        for (int i = 0; i < steps.length; i++) {
            final int target = i;
            JButton button = new JButton((i + 1) + ". " + steps[i].title());
            button.addActionListener(new java.awt.event.ActionListener() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    if (target <= maxCompletedIndex) navigateTo(target);
                }
            });
            jumpButtons[i] = button;
            stepButtons.add(button);
        }
        header.add(stepButtons, BorderLayout.CENTER);
        fullScreenButton = new JButton(fullScreenButtonText(false));
        fullScreenButton.setToolTipText("Use the whole screen");
        fullScreenButton.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                setFullScreen(!fullScreen);
            }
        });
        header.add(fullScreenButton, BorderLayout.EAST);
        dialog.getContentPane().add(header, BorderLayout.NORTH);

        content = new JPanel(new BorderLayout());
        dialog.getContentPane().add(content, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        backButton = new JButton("Back");
        backButton.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (stepIndex > 0) navigateTo(stepIndex - 1);
            }
        });
        left.add(backButton);
        saveProject.setToolTipText("Save metadata, channels, selected sections, layout and export settings");
        openProject.setToolTipText("Open a saved project; verify or relink its source images");
        saveProject.addActionListener(e -> saveProject());
        openProject.addActionListener(e -> openProject());
        left.add(openProject);
        left.add(saveProject);
        if (context.projectFile != null) projectStatus.setText("Opened: " + context.projectFile.getName());
        projectStatus.setToolTipText("The last confirmed saved snapshot; save again after making edits");
        footer.add(projectStatus, BorderLayout.NORTH);
        footer.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                cancel();
            }
        });
        nextButton = new JButton();
        nextButton.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                advance();
            }
        });
        right.add(cancelButton);
        right.add(nextButton);
        footer.add(right, BorderLayout.EAST);
        dialog.getContentPane().add(footer, BorderLayout.SOUTH);

        context.setInvalidationListener(new Context.InvalidationListener() {
            @Override
            public void invalidatedAfter(int sourceStepIndex) {
                maxCompletedIndex = invalidatedMaxCompletedIndex(
                        maxCompletedIndex, sourceStepIndex);
                updateButtons();
            }
        });
        showStep(0);
        dialog.setSize(920, 640);
        dialog.setLocationRelativeTo(null);
    }

    public static void showWizard() {
        if (SwingUtilities.isEventDispatchThread()) {
            new FPBWizard().show();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    new FPBWizard().show();
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Figure Panel Builder was interrupted while opening.",
                    interrupted);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(
                    "Figure Panel Builder could not open.", cause);
        }
    }

    public void show() {
        dialog.setVisible(true);
    }

    public Context context() {
        return context;
    }

    public int currentStepIndex() {
        return stepIndex;
    }

    public boolean backVisible() {
        return backButton.isVisible();
    }

    public String nextButtonText() {
        return nextButton.getText();
    }

    void goToQuickGrid() {
        if (context.folder == null) {
            JOptionPane.showMessageDialog(dialog,
                    "Choose an image folder before using Quick grid.",
                    "Figure Panel Builder", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (isQuickGridRunning()) return;
        final java.io.File folder = context.folder;
        final List<fpb.io.ImageSource> sources = includedSources();
        if (sources != null && sources.isEmpty()) {
            JOptionPane.showMessageDialog(dialog,
                    "Tick at least one image before using Quick grid.",
                    "Figure Panel Builder", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        final List<fpb.ui.chooser.ChannelRail.ChannelSpec> channels =
                quickGridChannels(context.channelSettings);
        final boolean recursive = context.recursive;
        final ImageLoader.ZMode zMode = ImageLoader.ZMode.fromString(
                context.zHandling);
        quickGridWorker = new SwingWorker<QuickGrid.Result, String>() {
            @Override
            protected QuickGrid.Result doInBackground() throws Exception {
                return QuickGrid.run(folder, sources != null ? sources
                        : ImageLoader.discoverImageSources(folder, recursive),
                        zMode, channels,
                        new ProgressCallback() {
                            @Override
                            public void onProgress(int completed, int total,
                                    java.io.File file) {
                                publish("Loading " + completed + "/" + total
                                        + ": " + (file == null ? "image"
                                        : file.getName()));
                            }
                        });
            }

            @Override
            protected void process(List<String> updates) {
                if (!updates.isEmpty()) {
                    dialog.setTitle("Figure Panel Builder - "
                            + updates.get(updates.size() - 1));
                }
            }

            @Override
            protected void done() {
                try {
                    if (!isCancelled() && !resourcesClosed) {
                        applyQuickGridResult(get());
                    }
                } catch (java.util.concurrent.CancellationException cancelled) {
                    // Closing the wizard or cancelling the worker is user-directed.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    JOptionPane.showMessageDialog(dialog,
                            cause == null ? failure.getMessage() : cause.getMessage(),
                            "Figure Panel Builder", JOptionPane.INFORMATION_MESSAGE);
                } finally {
                    quickGridWorker = null;
                    dialog.setTitle("Figure Panel Builder");
                    updateButtons();
                }
            }
        };
        updateButtons();
        quickGridWorker.execute();
    }

    /** Ticked images from step 1, or null when no table has been built yet. */
    private List<fpb.io.ImageSource> includedSources() {
        MetadataTable table = context.allImagesTable != null
                ? context.allImagesTable.includedTable() : context.metadataTable;
        if (table == null || context.quickGridRequested) return null;
        List<fpb.io.ImageSource> sources = new ArrayList<fpb.io.ImageSource>();
        for (fpb.meta.MetadataRow row : table.rows()) sources.add(row.source);
        return sources;
    }

    /** Included channels in their chosen order; null lets Quick grid use the file's own. */
    static List<fpb.ui.chooser.ChannelRail.ChannelSpec> quickGridChannels(
            List<ChannelSetting> settings) {
        if (settings == null || settings.isEmpty()) return null;
        List<fpb.ui.chooser.ChannelRail.ChannelSpec> specs =
                new ArrayList<fpb.ui.chooser.ChannelRail.ChannelSpec>();
        for (ChannelSetting setting : settings) {
            if (setting == null || !setting.include) continue;
            specs.add(new fpb.ui.chooser.ChannelRail.ChannelSpec(
                    setting.sourceIndex, setting.name, setting.colour));
        }
        return specs.isEmpty() ? null : specs;
    }

    private void applyQuickGridResult(QuickGrid.Result result) {
        context.quickGridRequested = true;
        context.metadataTable = result.table();
        context.chooserData = result.chooserData();
        context.selectedRowsByGroup = result.selectedRowsByGroup();
        context.layoutChannelRequests =
                new ArrayList<FPBRenderer.ChannelRequest>(result.channelRequests());
        context.panelConfig = result.panelConfig();
        context.imageOrientations = orientationsFrom(result.panelConfig());
        context.groupLayoutRows = result.panelConfig().groupLayoutRows();
        context.layoutPanelRecords = new ArrayList<PanelRecord>();
        maxCompletedIndex = Math.max(maxCompletedIndex, 3);
        showStep(3);
    }

    private void advance() {
        WizardStep step = steps[stepIndex];
        if (!step.commitEdits() || !step.canAdvance()) {
            JOptionPane.showMessageDialog(dialog,
                    "Complete this step before continuing.",
                    "Figure Panel Builder", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        maxCompletedIndex = Math.max(maxCompletedIndex, stepIndex + 1);
        if (stepIndex < steps.length - 1) {
            showStep(stepIndex + 1);
        } else {
            step.onPrimaryAction();
            if (step.primaryActionClosesWizard()) dialog.dispose();
        }
    }

    private void showStep(int index) {
        stepIndex = Math.max(0, Math.min(index, steps.length - 1));
        if (context.quickGridRequested && stepIndex < 3
                && context.allImagesTable != null) {
            // The Quick grid table labels each image as its own group; the
            // guided steps need the user's own labels back.
            context.metadataTable = context.allImagesTable.includedTable();
            context.invalidateGuidedDownstream(1);
        }
        context.quickGridRequested = quickGridRequestedForStep(
                context.quickGridRequested, stepIndex);
        WizardStep step = steps[stepIndex];
        content.removeAll();
        JComponent component = step.component();
        content.add(component, BorderLayout.CENTER);
        step.onShow();
        updateButtons();
        content.revalidate();
        content.repaint();
    }

    private void navigateTo(int requestedIndex) {
        int target = navigationTarget(context.quickGridRequested, requestedIndex);
        if (target == stepIndex) return;
        if (!steps[stepIndex].commitEdits()) return;
        // Committing a table cell can invalidate a previously enabled step jump.
        if (target > stepIndex
                && (!steps[stepIndex].canAdvance() || target > maxCompletedIndex)) return;
        showStep(target);
    }

    private void updateButtons() {
        boolean exportRunning = isExportRunning();
        boolean navigationEnabled = navigationEnabledWhile(
                exportRunning || isQuickGridRunning());
        saveProject.setEnabled(navigationEnabled && !projectBusy);
        openProject.setEnabled(navigationEnabled && !projectBusy);
        backButton.setVisible(backVisibleForStep(stepIndex));
        backButton.setEnabled(navigationEnabled);
        cancelButton.setEnabled(!exportRunning);
        nextButton.setText(primaryButtonLabel(steps[stepIndex], stepIndex == steps.length - 1));
        nextButton.setEnabled(navigationEnabled
                && (!(steps[stepIndex] instanceof Step3Chooser)
                || steps[stepIndex].canAdvance()));
        for (int i = 0; i < jumpButtons.length; i++) {
            jumpButtons[i].setEnabled(navigationEnabled && i <= maxCompletedIndex);
            jumpButtons[i].setBackground(i == stepIndex ? new Color(229, 236, 242) : null);
        }
    }

    private boolean isExportRunning() {
        return steps.length > 0 && steps[steps.length - 1] instanceof Step5Export
                && ((Step5Export) steps[steps.length - 1]).isExportRunning();
    }

    private boolean isQuickGridRunning() {
        return quickGridWorker != null && !quickGridWorker.isDone();
    }

    static boolean backVisibleForStep(int index) {
        return index > 0;
    }

    static boolean navigationEnabledWhile(boolean exportRunning) {
        return !exportRunning;
    }

    static boolean quickGridRequestedForStep(boolean quickGridRequested,
            int stepIndex) {
        return quickGridRequested && stepIndex != 1 && stepIndex != 2;
    }

    /** Quick grid has no Choose-images state, so stepping back lands on Channels. */
    static int navigationTarget(boolean quickGridRequested, int requestedIndex) {
        return quickGridRequested && requestedIndex == 2 ? 1 : requestedIndex;
    }

    static int invalidatedMaxCompletedIndex(int currentMaximum,
            int sourceStepIndex) {
        return Math.min(currentMaximum, Math.max(0, sourceStepIndex));
    }

    static String primaryButtonLabel(WizardStep step, boolean finalStep) {
        return finalStep ? step.nextTitle() : "Next: " + step.nextTitle();
    }

    static String fullScreenButtonText(boolean fullScreen) {
        return fullScreen ? "Exit full screen" : "Full screen";
    }

    private void setFullScreen(boolean requested) {
        if (requested == fullScreen) return;
        if (requested) {
            GraphicsConfiguration configuration = dialog.getGraphicsConfiguration();
            if (configuration == null) return;
            windowedBounds = dialog.getBounds();
            fullScreenDevice = configuration.getDevice();
            fullScreenDevice.setFullScreenWindow(dialog);
            fullScreen = true;
        } else {
            leaveFullScreen();
        }
        updateFullScreenButton();
    }

    private void leaveFullScreen() {
        if (!fullScreen) return;
        if (fullScreenDevice != null
                && fullScreenDevice.getFullScreenWindow() == dialog) {
            fullScreenDevice.setFullScreenWindow(null);
        }
        fullScreen = false;
        fullScreenDevice = null;
        if (windowedBounds != null) dialog.setBounds(windowedBounds);
    }

    private void updateFullScreenButton() {
        fullScreenButton.setText(fullScreenButtonText(fullScreen));
        fullScreenButton.setToolTipText(fullScreen
                ? "Return to the previous window size" : "Use the whole screen");
    }

    private void saveProject() {
        if (!steps[stepIndex].commitEdits()) return;
        if (!steps[steps.length - 1].commitEdits()) return;
        javax.swing.JFileChooser picker = new javax.swing.JFileChooser(context.folder);
        picker.setSelectedFile(context.projectFile == null
                ? new java.io.File(context.folder, "figure-project.fpb.xml") : context.projectFile);
        if (picker.showSaveDialog(dialog) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        final java.io.File target = picker.getSelectedFile();
        if (target.exists() && !target.equals(context.projectFile)
                && JOptionPane.showConfirmDialog(dialog, "Replace " + target.getName() + "?",
                        "Save project", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        final java.util.Properties snapshot;
        try { snapshot = FigureProjectIO.capture(context); }
        catch (RuntimeException failure) { projectFailure(failure); return; }
        projectBusy = true; projectStatus.setText("Saving project and checking sources..."); updateButtons();
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception {
                FigureProjectIO.save(snapshot, target); return null;
            }
            protected void done() {
                projectBusy = false;
                try {
                    get(); context.projectFile = target;
                    projectStatus.setText("Last saved: " + target.getName() + " at "
                            + java.time.LocalTime.now().withNano(0));
                } catch (Exception failure) { projectFailure(failure); }
                updateButtons();
            }
        }.execute();
    }

    private void openProject() {
        javax.swing.JFileChooser picker = new javax.swing.JFileChooser(context.folder);
        if (picker.showOpenDialog(dialog) == javax.swing.JFileChooser.APPROVE_OPTION)
            loadProject(picker.getSelectedFile(), null);
    }

    private void loadProject(final java.io.File file, final java.io.File root) {
        projectBusy = true; projectStatus.setText("Checking saved project sources..."); updateButtons();
        new SwingWorker<Context, Void>() {
            protected Context doInBackground() throws Exception { return FigureProjectIO.load(file, root); }
            protected void done() {
                projectBusy = false; updateButtons();
                try {
                    Context loaded = get();
                    projectStatus.setText("Opened project in another window");
                    new FPBWizard(loaded).show();
                } catch (Exception failure) {
                    projectFailure(failure);
                    if (JOptionPane.showConfirmDialog(dialog,
                            "Locate the original image folder? Its files must match the saved fingerprints.",
                            "Relink project sources", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                        javax.swing.JFileChooser picker = new javax.swing.JFileChooser();
                        picker.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
                        if (picker.showOpenDialog(dialog) == javax.swing.JFileChooser.APPROVE_OPTION)
                            loadProject(file, picker.getSelectedFile());
                    }
                }
            }
        }.execute();
    }

    private void projectFailure(Exception failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        projectStatus.setText("Project operation failed; previous saved file retained");
        JOptionPane.showMessageDialog(dialog, cause.getMessage(), "Figure project", JOptionPane.ERROR_MESSAGE);
    }

    private void cancel() {
        if (!wizardMayClose(isExportRunning())) {
            JOptionPane.showMessageDialog(dialog,
                    "The figure export is still running. Use Cancel export if "
                    + "you need to stop it, then wait for the export to finish "
                    + "cleaning up before closing this window.",
                    "Figure Panel Builder", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (context.folder != null) {
            int result = JOptionPane.showConfirmDialog(dialog,
                    "Close this window? Changes since the last Save project will be lost.",
                    "Figure Panel Builder", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return;
        }
        if (quickGridWorker != null) quickGridWorker.cancel(true);
        leaveFullScreen();
        closeResources();
        dialog.dispose();
    }

    static boolean wizardMayClose(boolean exportRunning) {
        return !exportRunning;
    }

    private void closeResources() {
        if (resourcesClosed) return;
        resourcesClosed = true;
        for (WizardStep step : steps) {
            if (step instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) step).close();
                } catch (Exception ignored) {
                    // Closing a daemon renderer must not prevent the dialog from closing.
                }
            }
        }
        context.imagePreloader.close();
    }

    public static final class Context {
        public java.io.File projectFile;
        public String projectRevision = "";
        public final java.util.Properties exportSettings = new java.util.Properties();
        interface InvalidationListener {
            void invalidatedAfter(int sourceStepIndex);
        }

        /** Included images only; every later step reads this table. */
        public MetadataTable metadataTable;
        /** Every discovered image, including those unticked in step 1. */
        public MetadataTable allImagesTable;
        public final ImagePreloader imagePreloader = new ImagePreloader();
        public java.io.File folder;
        public boolean recursive;
        public boolean tableHandEdited;
        public boolean quickGridRequested;
        public int detectedChannelCount = 3;
        public String zHandling = "Maximum projection";
        public String statistic = fpb.FPBParameters.BRIGHTEST_ONE_PERCENT_STATISTIC;
        public java.io.File statisticCsv;
        public String statisticColumn = "";
        public java.io.File recordedMetadataCsv;
        public Map<String, fpb.figure.CalibrationOverride> calibrationOverrides =
                new LinkedHashMap<String, fpb.figure.CalibrationOverride>();
        public Map<String, ImageOrientation> imageOrientations =
                new LinkedHashMap<String, ImageOrientation>();
        public List<ChannelSetting> channelSettings =
                new ArrayList<ChannelSetting>();
        public Step3Chooser.Data chooserData;
        public Map<String, RowImage.SubjectRow> selectedRowsByGroup =
                new LinkedHashMap<String, RowImage.SubjectRow>();
        public List<FPBRenderer.ChannelRequest> layoutChannelRequests =
                new ArrayList<FPBRenderer.ChannelRequest>();
        public PanelConfig panelConfig;
        public List<List<String>> groupLayoutRows =
                new ArrayList<List<String>>();
        public List<PanelRecord> layoutPanelRecords =
                new ArrayList<PanelRecord>();
        public Map<String, RowImage.SubjectRow> savedPicks =
                new LinkedHashMap<String, RowImage.SubjectRow>();
        public List<FPBRenderer.ChannelRequest> savedChannelRequests =
                new ArrayList<FPBRenderer.ChannelRequest>();
        public String savedZHandling;

        private InvalidationListener invalidationListener;

        void setInvalidationListener(InvalidationListener listener) {
            invalidationListener = listener;
        }

        /** Clears every result derived from images/metadata/channels and revokes step jumps. */
        public void invalidateGuidedDownstream(int sourceStepIndex) {
            if (sourceStepIndex <= 0) {
                calibrationOverrides.clear();
                imageOrientations.clear();
                savedPicks.clear();
                savedChannelRequests.clear();
                savedZHandling = null;
                panelConfig = null;
                groupLayoutRows.clear();
            }
            chooserData = null;
            selectedRowsByGroup.clear();
            layoutChannelRequests.clear();
            layoutPanelRecords.clear();
            recordedMetadataCsv = null;
            if (invalidationListener != null) {
                invalidationListener.invalidatedAfter(sourceStepIndex);
            }
        }
    }

    private static Map<String, ImageOrientation> orientationsFrom(
            PanelConfig config) {
        LinkedHashMap<String, ImageOrientation> out =
                new LinkedHashMap<String, ImageOrientation>();
        if (config == null) return out;
        for (Map.Entry<String, String> entry
                : config.imageOrientations().entrySet()) {
            out.put(entry.getKey(), ImageOrientation.fromToken(entry.getValue()));
        }
        return out;
    }

    /** One file channel; list position sets its panel column order. */
    public static final class ChannelSetting {
        public final int sourceIndex;
        public boolean include;
        public String name;
        public ChannelColour colour;

        ChannelSetting(int sourceIndex, boolean include, String name,
                ChannelColour colour) {
            this.sourceIndex = sourceIndex;
            this.include = include;
            this.name = name;
            this.colour = colour;
        }
    }
}
