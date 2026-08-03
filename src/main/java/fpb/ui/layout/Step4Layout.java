/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.layout;

import fpb.figure.CalibrationCheck;
import fpb.figure.PanelConfig;
import fpb.figure.PanelRecord;
import fpb.figure.PanelWriter;
import fpb.meta.MetadataRow;
import fpb.render.FPBRenderer;
import fpb.ui.FPBWizard;
import fpb.ui.WizardStep;
import fpb.ui.chooser.RowImage;
import fpb.ui.chooser.Step3Chooser;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Step 4: live layout preview, group row ordering and annotation controls. */
public final class Step4Layout implements WizardStep {

    private static final Color BACKGROUND = new Color(248, 249, 250);
    private static final Color TEXT = new Color(42, 47, 53);
    private static final Color MUTED = new Color(95, 103, 112);
    private static final double[] SCALE_CHOICES =
            new double[] { 10, 20, 25, 50, 100, 200, 500 };

    private final FPBWizard.Context context;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));
    private final JLabel summary = new JLabel("Arrange layout");
    private final JLabel preview = new JLabel("Choose images first.");
    private final JLabel size = new JLabel(" ");
    private final JPanel side = new JPanel();
    private final JComboBox<String> scaleLength = new JComboBox<String>();
    private final JComboBox<String> scaleCorner =
            new JComboBox<String>(new String[] {
                    "Top left", "Top right", "Bottom left", "Bottom right"
            });

    private RowOrderPanel rowOrderPanel;
    private PanelConfig config;
    private List<PanelRecord> previewRecords =
            new ArrayList<PanelRecord>();
    private boolean recordsDirty = true;
    private boolean syncing;

    public Step4Layout(FPBWizard.Context context) {
        this.context = context;
        panel.setBackground(BACKGROUND);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        summary.setForeground(TEXT);
        panel.add(summary, BorderLayout.NORTH);
        preview.setHorizontalAlignment(JLabel.CENTER);
        preview.setVerticalAlignment(JLabel.CENTER);
        preview.setOpaque(true);
        preview.setBackground(Color.WHITE);
        preview.setBorder(BorderFactory.createLineBorder(new Color(196, 202, 208)));
        JScrollPane scroll = new JScrollPane(preview);
        panel.add(scroll, BorderLayout.CENTER);
        side.setOpaque(false);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        panel.add(side, BorderLayout.EAST);
        size.setForeground(MUTED);
        panel.add(size, BorderLayout.SOUTH);
        for (double choice : SCALE_CHOICES) {
            scaleLength.addItem(formatNumber(choice));
        }
    }

    @Override
    public String title() {
        return "Layout";
    }

    @Override
    public String nextTitle() {
        return "Export";
    }

    @Override
    public JComponent component() {
        return panel;
    }

    @Override
    public void onShow() {
        rebuildFromContext();
    }

    @Override
    public boolean canAdvance() {
        return config != null && !previewRecords.isEmpty();
    }

    public RowOrderPanel rowOrderPanelForTest() {
        return rowOrderPanel;
    }

    public PanelConfig panelConfigForTest() {
        return config;
    }

    private void rebuildFromContext() {
        if (!hasChooserState()) {
            showEmpty("Choose images and lock display ranges first.");
            return;
        }
        List<String> groups = groups();
        if (groups.isEmpty()) {
            showEmpty("Choose one subject for each group first.");
            return;
        }
        if (config == null) {
            config = context.panelConfig == null
                    ? defaultConfig(groups)
                    : context.panelConfig;
        }
        if (!config.hasGroupLayoutRows()) {
            config = config.toBuilder()
                    .groupLayoutRows(initialRows(groups))
                    .build();
        }
        buildSide(groups);
        recordsDirty = true;
        refreshPreview();
    }

    private void buildSide(List<String> groups) {
        side.removeAll();
        JLabel header = new JLabel("GROUP ROWS");
        header.setForeground(TEXT);
        side.add(header);
        rowOrderPanel = new RowOrderPanel(groups, config.groupLayoutRows());
        rowOrderPanel.setOnChange(new Runnable() {
            @Override public void run() {
                config = config.toBuilder()
                        .groupLayoutRows(rowOrderPanel.rows())
                        .build();
                saveConfig();
                refreshPreview();
            }
        });
        side.add(rowOrderPanel);
        side.add(Box.createVerticalStrut(8));
        side.add(scaleControls());
        side.add(Box.createVerticalStrut(8));
        side.add(buttons());
        side.add(Box.createVerticalGlue());
        side.revalidate();
        side.repaint();
    }

    private JPanel scaleControls() {
        JPanel controls = new JPanel();
        controls.setOpaque(false);
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.add(section("Scale bar"));
        controls.add(row("Length um", scaleLength));
        controls.add(row("Corner", scaleCorner));
        syncing = true;
        try {
            scaleLength.setSelectedItem(formatNumber(config.scaleBarLengthUm()));
            scaleCorner.setSelectedItem(positionLabel(config.scaleBarPosition()));
        } finally {
            syncing = false;
        }
        scaleLength.addActionListener(e -> {
            if (syncing) return;
            config = config.toBuilder()
                    .scaleBarLengthUm(parseDouble(selected(scaleLength),
                            config.scaleBarLengthUm()))
                    .build();
            saveConfig();
            refreshPreview();
        });
        scaleCorner.addActionListener(e -> {
            if (syncing) return;
            PanelConfig.Position position = parsePosition(selected(scaleCorner));
            double[] frac = AnnotationEditor.cornerFraction(position);
            config = config.toBuilder()
                    .scaleBarPosition(position)
                    .scaleBarFracX(frac[0])
                    .scaleBarFracY(frac[1])
                    .build();
            saveConfig();
            refreshPreview();
        });
        return controls;
    }

    private JPanel buttons() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        JButton spacing = new JButton("Edit spacing...");
        JButton annotations = new JButton("Edit annotations...");
        spacing.addActionListener(e -> {
            PanelConfig before = config;
            PanelConfig edited = LayoutEditor.edit(owner(), config,
                    new LayoutEditor.Listener() {
                        @Override public void spacingChanged(PanelConfig candidate) {
                            config = candidate;
                            saveConfig();
                            refreshPreview();
                        }
                    });
            if (edited == null) {
                config = before;
            } else {
                config = edited;
            }
            saveConfig();
            refreshPreview();
        });
        annotations.addActionListener(e -> {
            PanelConfig before = config;
            PanelConfig edited = AnnotationEditor.edit(owner(),
                    representativeRecord(), config,
                    new AnnotationEditor.Listener() {
                        @Override public void annotationChanged(PanelConfig candidate) {
                            config = candidate;
                            saveConfig();
                            refreshPreview();
                        }
                    });
            if (edited == null) {
                config = before;
            } else {
                config = edited;
            }
            saveConfig();
            refreshPreview();
        });
        buttons.add(spacing);
        buttons.add(annotations);
        return buttons;
    }

    private void refreshPreview() {
        if (config == null) return;
        try {
            if (recordsDirty || previewRecords.isEmpty()) {
                previewRecords = buildPreviewRecords();
                recordsDirty = false;
            }
            if (previewRecords.isEmpty()) {
                showEmpty("Choose one subject for each group first.");
                return;
            }
            PanelWriter.WriteReport report = new PanelWriter.WriteReport();
            BufferedImage figure = PanelWriter.renderOverviewPanel(
                    previewRecords, config, report);
            preview.setIcon(new ImageIcon(scaleForPreview(figure, 720, 520)));
            preview.setText(null);
            size.setText("Preview through PanelWriter: " + figure.getWidth()
                    + " x " + figure.getHeight() + " px");
            saveConfig();
        } catch (RuntimeException | IOException failure) {
            preview.setIcon(null);
            preview.setText(failure.getMessage());
            size.setText(" ");
        }
    }

    private List<PanelRecord> buildPreviewRecords() throws IOException {
        Step3Chooser.Data data = context.chooserData;
        List<FPBRenderer.ChannelRequest> channels = context.layoutChannelRequests;
        LinkedHashMap<String, RowImage.SubjectRow> picks =
                new LinkedHashMap<String, RowImage.SubjectRow>(
                        context.selectedRowsByGroup);
        List<PanelRecord> records = new ArrayList<PanelRecord>();
        FPBRenderer renderer = new FPBRenderer();
        File dir = previewDirectory();
        for (String group : orderedGroups()) {
            RowImage.SubjectRow row = picks.get(group);
            if (row == null) continue;
            FPBRenderer.PanelRender render = renderer.renderPanel(data.planes(),
                    data.histograms(), row.imageIndex(), channels,
                    config.cellSizePx(), config.cellSizePx());
            MetadataRow metadata = data.table().rows().get(row.imageIndex());
            CalibrationCheck.Result calibration =
                    CalibrationCheck.fromImageMetadata(
                            data.planes().image(row.imageIndex()).calibration());
            for (int i = 0; i < channels.size(); i++) {
                FPBRenderer.ChannelRequest channel = channels.get(i);
                BufferedImage image = render.channelImages().get(i);
                File file = writePreviewImage(dir, group, row.subject(),
                        channel.name(), image);
                records.add(record(file, metadata, render.sourceFile().getName(),
                        channel.name(), channel.name(), channel.channelIndex(),
                        image, calibration));
            }
            BufferedImage merge = render.mergeImage();
            File mergeFile = writePreviewImage(dir, group, row.subject(),
                    "Merge", merge);
            records.add(record(mergeFile, metadata, render.sourceFile().getName(),
                    "Merge", "Merge", -1, merge, calibration));
        }
        context.layoutPanelRecords = new ArrayList<PanelRecord>(records);
        return records;
    }

    private PanelRecord record(File file, MetadataRow metadata, String imageId,
            String outputName, String channelName, int channelIndex,
            BufferedImage image, CalibrationCheck.Result calibration) {
        CalibrationCheck.Result safe = calibration == null
                ? CalibrationCheck.none() : calibration;
        return new PanelRecord(file, metadata.group, metadata.subject,
                metadata.section, imageId, outputName, channelName, channelIndex,
                image.getWidth(), image.getHeight(), safe.pixelWidthUm(),
                safe.pixelHeightUm(), safe.source());
    }

    private File writePreviewImage(File dir, String group, String subject,
            String output, BufferedImage image) throws IOException {
        File file = File.createTempFile(safe(group) + "_" + safe(subject)
                + "_" + safe(output) + "_", ".png", dir);
        file.deleteOnExit();
        PanelWriter.writePngAtomically(image, file, config.outputDpi());
        return file;
    }

    private File previewDirectory() throws IOException {
        File root = new File(System.getProperty("java.io.tmpdir"),
                "FigurePanelBuilder-layout-preview");
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("Could not create preview directory: "
                    + root.getAbsolutePath());
        }
        return root;
    }

    private List<String> orderedGroups() {
        List<String> ordered = new ArrayList<String>();
        for (List<String> row : config.groupLayoutRows()) {
            for (String group : row) {
                if (!ordered.contains(group)) ordered.add(group);
            }
        }
        for (String group : groups()) {
            if (!ordered.contains(group)) ordered.add(group);
        }
        return ordered;
    }

    private PanelRecord representativeRecord() {
        if (previewRecords.isEmpty()) {
            try {
                previewRecords = buildPreviewRecords();
            } catch (IOException ignored) {
                return null;
            }
        }
        return previewRecords.isEmpty() ? null : previewRecords.get(0);
    }

    private PanelConfig defaultConfig(List<String> groups) {
        List<String> channels = new ArrayList<String>();
        for (FPBRenderer.ChannelRequest request : context.layoutChannelRequests) {
            channels.add(request.name());
        }
        channels.add("Merge");
        return PanelConfig.builder()
                .createOverviewPanel(true)
                .annotateOverviewPanel(true)
                .channelOrder(channels)
                .cellSizePx(220)
                .scaleBarLengthUm(50.0)
                .scaleBarPosition(PanelConfig.Position.BOTTOM_RIGHT)
                .groupLayoutRows(initialRows(groups))
                .build();
    }

    private List<List<String>> initialRows(List<String> groups) {
        if (context.groupLayoutRows != null && !context.groupLayoutRows.isEmpty()) {
            return context.groupLayoutRows;
        }
        return RowOrderPanel.allInOneRow(groups);
    }

    private void saveConfig() {
        context.panelConfig = config;
        context.groupLayoutRows = config == null
                ? Collections.<List<String>>emptyList()
                : config.groupLayoutRows();
    }

    private boolean hasChooserState() {
        return context != null && context.chooserData != null
                && context.chooserData.planes() != null
                && context.chooserData.histograms() != null
                && context.selectedRowsByGroup != null
                && !context.selectedRowsByGroup.isEmpty()
                && context.layoutChannelRequests != null
                && !context.layoutChannelRequests.isEmpty();
    }

    private List<String> groups() {
        if (context == null || context.selectedRowsByGroup == null) {
            return Collections.emptyList();
        }
        return new ArrayList<String>(context.selectedRowsByGroup.keySet());
    }

    private void showEmpty(String message) {
        preview.setIcon(null);
        preview.setText(message == null ? "" : message);
        size.setText(" ");
        summary.setText("Arrange layout");
        side.removeAll();
        side.revalidate();
        side.repaint();
    }

    private Window owner() {
        return SwingUtilities.getWindowAncestor(panel);
    }

    private static BufferedImage scaleForPreview(BufferedImage image,
            int maxWidth, int maxHeight) {
        if (image == null) return null;
        double scale = Math.min(1.0, Math.min(maxWidth / (double) image.getWidth(),
                maxHeight / (double) image.getHeight()));
        if (scale >= 1.0) return image;
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage out = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            PanelWriter.applyQualityHints(g);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static JPanel row(String label, JComponent component) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setOpaque(false);
        JLabel text = new JLabel(label);
        text.setPreferredSize(new Dimension(76, 18));
        row.add(text);
        row.add(component);
        return row;
    }

    private static JLabel section(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        return label;
    }

    private static PanelConfig.Position parsePosition(String text) {
        String value = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if ("top right".equals(value)) return PanelConfig.Position.TOP_RIGHT;
        if ("bottom left".equals(value)) return PanelConfig.Position.BOTTOM_LEFT;
        if ("bottom right".equals(value)) return PanelConfig.Position.BOTTOM_RIGHT;
        return PanelConfig.Position.TOP_LEFT;
    }

    private static String positionLabel(PanelConfig.Position position) {
        if (position == PanelConfig.Position.TOP_RIGHT) return "Top right";
        if (position == PanelConfig.Position.BOTTOM_LEFT) return "Bottom left";
        if (position == PanelConfig.Position.BOTTOM_RIGHT) return "Bottom right";
        return "Top left";
    }

    private static String selected(JComboBox<String> combo) {
        Object value = combo.getSelectedItem();
        return value == null ? "" : value.toString();
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String formatNumber(double value) {
        if (Math.rint(value) == value && Double.isFinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private static String safe(String value) {
        String clean = value == null ? "" : value.trim()
                .replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.isEmpty() ? "preview" : clean;
    }
}
