/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui;

import fpb.io.ImageLoader;
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
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/** Second wizard step: included channels, names, LUT colours and z handling. */
public final class Step2Channels implements WizardStep {

    private static final String[] COLOUR_NAMES = new String[] {
            "Blue", "Magenta", "Green", "Cyan", "Yellow", "Grey", "Red"
    };
    private static final String[] Z_HANDLING = new String[] {
            "Maximum projection", "First slice", "Current slice"
    };

    private final FPBWizard.Context context;
    private final JPanel panel;
    private final JLabel detectedLabel;
    private final JPanel channelsPanel;
    private final JComboBox<String> zHandling;
    private File lastSource;

    public Step2Channels(FPBWizard.Context context) {
        this.context = context;
        panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        detectedLabel = new JLabel();
        panel.add(detectedLabel, BorderLayout.NORTH);

        channelsPanel = new JPanel(new GridLayout(0, 1, 6, 6));
        panel.add(channelsPanel, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bottom.add(new JLabel("Z handling"));
        zHandling = new JComboBox<String>(Z_HANDLING);
        zHandling.setSelectedItem(context.zHandling);
        zHandling.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                context.zHandling = (String) zHandling.getSelectedItem();
            }
        });
        bottom.add(zHandling);
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
        File source = firstSourceFile();
        if (source == null || !source.equals(lastSource)) {
            detectChannels(source);
            rebuildRows();
            lastSource = source;
        }
    }

    @Override
    public boolean canAdvance() {
        for (FPBWizard.ChannelSetting setting : context.channelSettings) {
            if (setting.include) return true;
        }
        return false;
    }

    public int detectedChannelCount() {
        return context.detectedChannelCount;
    }

    public List<FPBWizard.ChannelSetting> channelSettings() {
        return context.channelSettings;
    }

    private void detectChannels(File source) {
        int count = 3;
        if (source != null && source.isFile()) {
            try {
                count = new ImageLoader().loadImage(source).channelCount();
            } catch (IOException ignored) {
                count = 3;
            }
        }
        context.detectedChannelCount = Math.max(1, count);
        ensureSettings();
        detectedLabel.setText("Detected " + context.detectedChannelCount
                + " channels in the first image");
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
                }
            });
            row.add(name);
            final JComboBox<String> colour = new JComboBox<String>(COLOUR_NAMES);
            colour.setSelectedItem(titleCase(setting.colour.name()));
            colour.addActionListener(new java.awt.event.ActionListener() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    setting.colour = ChannelColour.fromName((String) colour.getSelectedItem());
                }
            });
            row.add(colour);
            channelsPanel.add(row);
        }
        channelsPanel.revalidate();
        channelsPanel.repaint();
    }

    private File firstSourceFile() {
        if (context.metadataTable == null || context.metadataTable.rows().isEmpty()) return null;
        MetadataRow row = context.metadataTable.rows().get(0);
        return row.file;
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
