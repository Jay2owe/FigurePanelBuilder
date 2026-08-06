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

    private final Context context = new Context();
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
        final boolean recursive = context.recursive;
        final ImageLoader.ZMode zMode = ImageLoader.ZMode.fromString(
                context.zHandling);
        quickGridWorker = new SwingWorker<QuickGrid.Result, String>() {
            @Override
            protected QuickGrid.Result doInBackground() throws Exception {
                return QuickGrid.run(folder, recursive, zMode,
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
        if (!step.canAdvance()) {
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
        showStep(navigationTarget(context.quickGridRequested, requestedIndex));
    }

    private void updateButtons() {
        boolean exportRunning = isExportRunning();
        boolean navigationEnabled = navigationEnabledWhile(
                exportRunning || isQuickGridRunning());
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

    static int navigationTarget(boolean quickGridRequested, int requestedIndex) {
        return quickGridRequested && requestedIndex < 3 ? 0 : requestedIndex;
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

    private void cancel() {
        if (!wizardMayClose(isExportRunning())) {
            JOptionPane.showMessageDialog(dialog,
                    "The figure export is still running. Use Cancel export if "
                    + "you need to stop it, then wait for the export to finish "
                    + "cleaning up before closing this window.",
                    "Figure Panel Builder", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (context.tableHandEdited) {
            int result = JOptionPane.showConfirmDialog(dialog,
                    "Discard the edited metadata table?",
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
        interface InvalidationListener {
            void invalidatedAfter(int sourceStepIndex);
        }

        public MetadataTable metadataTable;
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

        private InvalidationListener invalidationListener;

        void setInvalidationListener(InvalidationListener listener) {
            invalidationListener = listener;
        }

        /** Clears every result derived from images/metadata/channels and revokes step jumps. */
        public void invalidateGuidedDownstream(int sourceStepIndex) {
            if (sourceStepIndex <= 0) {
                calibrationOverrides.clear();
                imageOrientations.clear();
            }
            chooserData = null;
            selectedRowsByGroup.clear();
            layoutChannelRequests.clear();
            panelConfig = null;
            groupLayoutRows.clear();
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

    public static final class ChannelSetting {
        public boolean include;
        public String name;
        public ChannelColour colour;

        ChannelSetting(boolean include, String name, ChannelColour colour) {
            this.include = include;
            this.name = name;
            this.colour = colour;
        }
    }
}
