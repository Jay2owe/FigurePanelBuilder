/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui;

import fpb.meta.MetadataTable;
import fpb.figure.PanelConfig;
import fpb.figure.PanelRecord;
import fpb.render.FPBRenderer;
import fpb.render.ChannelColour;
import fpb.ui.chooser.Step3Chooser;
import fpb.ui.chooser.RowImage;
import fpb.ui.layout.Step4Layout;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Frame;
import java.awt.GridLayout;
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
import javax.swing.WindowConstants;

/** Five-step wizard shell for the interactive Figure Panel Builder workflow. */
public final class FPBWizard {

    private final Context context = new Context();
    private final JDialog dialog;
    private final JPanel stepButtons;
    private final JPanel content;
    private final JButton backButton;
    private final JButton cancelButton;
    private final JButton nextButton;
    private final WizardStep[] steps;
    private final JButton[] jumpButtons;
    private int stepIndex;
    private int maxCompletedIndex;

    public FPBWizard() {
        dialog = new JDialog((Frame) null, "Figure Panel Builder", false);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                cancel();
            }
        });
        dialog.getContentPane().setLayout(new BorderLayout());

        Step3Chooser chooserStep = new Step3Chooser(context);
        chooserStep.setAdvanceStateListener(new Runnable() {
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
                new Step2Channels(context),
                chooserStep,
                new Step4Layout(context),
                new PlaceholderStep("Export", "Build figure",
                        "Export arrives in a later stage.")
        };

        stepButtons = new JPanel(new GridLayout(1, steps.length, 0, 0));
        stepButtons.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        jumpButtons = new JButton[steps.length];
        for (int i = 0; i < steps.length; i++) {
            final int target = i;
            JButton button = new JButton((i + 1) + ". " + steps[i].title());
            button.addActionListener(new java.awt.event.ActionListener() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    if (target <= maxCompletedIndex) showStep(target);
                }
            });
            jumpButtons[i] = button;
            stepButtons.add(button);
        }
        dialog.getContentPane().add(stepButtons, BorderLayout.NORTH);

        content = new JPanel(new BorderLayout());
        dialog.getContentPane().add(content, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        backButton = new JButton("Back");
        backButton.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (stepIndex > 0) showStep(stepIndex - 1);
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

        showStep(0);
        dialog.setSize(920, 640);
        dialog.setLocationRelativeTo(null);
    }

    public static void showWizard() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new FPBWizard().show();
            }
        });
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
        context.quickGridRequested = true;
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
        if (stepIndex < steps.length - 1) showStep(stepIndex + 1);
        else dialog.dispose();
    }

    private void showStep(int index) {
        stepIndex = Math.max(0, Math.min(index, steps.length - 1));
        WizardStep step = steps[stepIndex];
        content.removeAll();
        JComponent component = step.component();
        content.add(component, BorderLayout.CENTER);
        step.onShow();
        updateButtons();
        content.revalidate();
        content.repaint();
    }

    private void updateButtons() {
        backButton.setVisible(backVisibleForStep(stepIndex));
        nextButton.setText(primaryButtonLabel(steps[stepIndex], stepIndex == steps.length - 1));
        nextButton.setEnabled(!(steps[stepIndex] instanceof Step3Chooser)
                || steps[stepIndex].canAdvance());
        for (int i = 0; i < jumpButtons.length; i++) {
            jumpButtons[i].setEnabled(i <= maxCompletedIndex);
            jumpButtons[i].setBackground(i == stepIndex ? new Color(229, 236, 242) : null);
        }
    }

    static boolean backVisibleForStep(int index) {
        return index > 0;
    }

    static String primaryButtonLabel(WizardStep step, boolean finalStep) {
        return finalStep ? step.nextTitle() : "Next: " + step.nextTitle();
    }

    private void cancel() {
        if (context.tableHandEdited) {
            int result = JOptionPane.showConfirmDialog(dialog,
                    "Discard the edited metadata table?",
                    "Figure Panel Builder", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return;
        }
        dialog.dispose();
    }

    public static final class Context {
        public MetadataTable metadataTable;
        public java.io.File folder;
        public boolean recursive;
        public boolean tableHandEdited;
        public boolean quickGridRequested;
        public int detectedChannelCount = 3;
        public String zHandling = "Maximum projection";
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

    private static final class PlaceholderStep implements WizardStep {
        private final String title;
        private final String nextTitle;
        private final JPanel panel;

        PlaceholderStep(String title, String nextTitle, String text) {
            this.title = title;
            this.nextTitle = nextTitle;
            panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
            panel.add(new JLabel(text), BorderLayout.NORTH);
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public String nextTitle() {
            return nextTitle;
        }

        @Override
        public JComponent component() {
            return panel;
        }

        @Override
        public void onShow() {}

        @Override
        public boolean canAdvance() {
            return true;
        }
    }
}
