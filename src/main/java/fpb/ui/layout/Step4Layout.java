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
import fpb.figure.CalibrationOverride;
import fpb.figure.ImageOrientation;
import fpb.figure.PanelConfig;
import fpb.figure.PanelRecord;
import fpb.figure.PanelWriter;
import fpb.meta.MetadataRow;
import fpb.render.FPBRenderer;
import fpb.ui.FPBWizard;
import fpb.ui.ImageOrientationControls;
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
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Step 4: live layout preview, group row ordering and annotation controls. */
public final class Step4Layout implements WizardStep, AutoCloseable {

    private static final Color BACKGROUND = new Color(248, 249, 250);
    private static final Color TEXT = new Color(42, 47, 53);
    private static final Color MUTED = new Color(95, 103, 112);
    private static final double[] SCALE_CHOICES =
            new double[] { 10, 20, 25, 50, 100, 200, 500 };
    private static final int PREVIEW_SUPERSAMPLE = 2;

    private final FPBWizard.Context context;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));
    private final JLabel summary = new JLabel("Arrange layout");
    private final OrientationPreview preview = new OrientationPreview();
    private final JLabel size = new JLabel(" ");
    private final JScrollPane previewScroll;
    private final JComboBox<String> previewZoom = new JComboBox<String>(
            new String[] { "Fit", "100%", "150%", "200%" });
    private final JPanel side = new JPanel();
    private final JComboBox<String> scaleLength = new JComboBox<String>();
    private final JComboBox<String> scaleCorner =
            new JComboBox<String>(new String[] {
                    "Top left", "Top right", "Bottom left", "Bottom right"
            });
    private final JComboBox<String> groupLabelGroup =
            new JComboBox<String>();
    private final JTextField groupLabelText = new JTextField(13);
    private final JComboBox<String> groupLabelAlignment =
            new JComboBox<String>(new String[] { "Left", "Middle", "Right" });
    private final JButton originalGroupLabel = new JButton("Use original");
    private final Timer groupLabelUpdateTimer;

    private RowOrderPanel rowOrderPanel;
    private PanelConfig config;
    private List<PanelRecord> previewRecords =
            new ArrayList<PanelRecord>();
    private boolean recordsDirty = true;
    private boolean syncing;
    private boolean syncingGroupLabelControls;
    private BufferedImage previewFigure;
    private List<PanelWriter.ImageBox> previewImageBoxes =
            Collections.emptyList();
    private PanelWriter.ImageBox hoveredImageBox;
    private double previewDisplayScale = 1.0;
    private boolean zoomSyncing;
    private java.awt.Point dragStartScreen;
    private java.awt.Point dragStartView;

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
        preview.setToolTipText("Ctrl+mouse wheel to zoom; drag to pan");
        previewScroll = new JScrollPane(preview);
        previewScroll.setWheelScrollingEnabled(false);
        previewScroll.addMouseWheelListener(e -> {
            if (e.isControlDown()) zoomAt(e);
            else scrollCanvas(e);
        });
        installCanvasPanning();
        installOrientationControls();
        panel.add(previewScroll, BorderLayout.CENTER);
        side.setOpaque(false);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        panel.add(side, BorderLayout.EAST);
        size.setForeground(MUTED);
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        footer.add(size, BorderLayout.WEST);
        JPanel zoom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        zoom.setOpaque(false);
        zoom.add(new JLabel("Preview zoom"));
        previewZoom.setEditable(true);
        previewZoom.setSelectedItem("100%");
        previewZoom.addActionListener(e -> {
            if (!zoomSyncing) updatePreviewIcon();
        });
        zoom.add(previewZoom);
        footer.add(zoom, BorderLayout.EAST);
        panel.add(footer, BorderLayout.SOUTH);
        previewScroll.getViewport().addComponentListener(
                new java.awt.event.ComponentAdapter() {
                    @Override public void componentResized(
                            java.awt.event.ComponentEvent event) {
                        if ("Fit".equals(selected(previewZoom))) {
                            updatePreviewIcon();
                        }
                    }
                });
        for (double choice : SCALE_CHOICES) {
            scaleLength.addItem(formatNumber(choice));
        }
        groupLabelUpdateTimer = new Timer(180, e -> applyGroupLabelText());
        groupLabelUpdateTimer.setRepeats(false);
        installGroupLabelControls();
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

    @Override
    public void close() {
        groupLabelUpdateTimer.stop();
        deletePreviewRecords(previewRecords);
        previewRecords = new ArrayList<PanelRecord>();
        context.layoutPanelRecords.clear();
    }

    public RowOrderPanel rowOrderPanelForTest() {
        return rowOrderPanel;
    }

    public PanelConfig panelConfigForTest() {
        return config;
    }

    List<PanelRecord> previewRecordsForTest() {
        return Collections.unmodifiableList(
                new ArrayList<PanelRecord>(previewRecords));
    }

    void setCalibrationOverrideForTest(String imageId, double pixelWidthUm,
            double pixelHeightUm) {
        setCalibrationOverride(imageId,
                new CalibrationOverride(pixelWidthUm, pixelHeightUm));
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
        config = context.panelConfig == null
                ? defaultConfig(groups)
                : context.panelConfig;
        synchronizeImageOrientations();
        if (!config.hasGroupLayoutRows()) {
            config = config.toBuilder()
                    .groupLayoutRows(initialRows(groups))
                    .build();
        }
        buildSide(groups);
        deletePreviewRecords(previewRecords);
        previewRecords = new ArrayList<PanelRecord>();
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
        side.add(groupLabelControls(groups));
        side.add(Box.createVerticalStrut(8));
        side.add(scaleControls());
        side.add(Box.createVerticalStrut(8));
        side.add(buttons());
        side.add(Box.createVerticalGlue());
        side.revalidate();
        side.repaint();
    }

    private JPanel groupLabelControls(List<String> groups) {
        JPanel controls = new JPanel();
        controls.setOpaque(false);
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.add(section("Group labels"));
        controls.add(row("Group", groupLabelGroup));
        controls.add(row("Text", groupLabelText));
        controls.add(row("Alignment", groupLabelAlignment));
        JPanel reset = new JPanel(new FlowLayout(FlowLayout.LEFT, 82, 0));
        reset.setOpaque(false);
        reset.add(originalGroupLabel);
        controls.add(reset);

        String selectedGroup = selected(groupLabelGroup);
        syncingGroupLabelControls = true;
        try {
            groupLabelGroup.removeAllItems();
            if (groups != null) {
                for (String group : groups) groupLabelGroup.addItem(group);
            }
            if (!selectedGroup.isEmpty() && groups != null
                    && groups.contains(selectedGroup)) {
                groupLabelGroup.setSelectedItem(selectedGroup);
            } else if (groupLabelGroup.getItemCount() > 0) {
                groupLabelGroup.setSelectedIndex(0);
            }
            groupLabelAlignment.setSelectedItem(alignmentLabel(
                    config.groupHeaderAlignment()));
        } finally {
            syncingGroupLabelControls = false;
        }
        syncGroupLabelText();
        return controls;
    }

    private void installGroupLabelControls() {
        groupLabelGroup.setToolTipText("Choose the group title to edit");
        groupLabelText.setToolTipText(
                "Edit this group title directly in the Layout preview");
        groupLabelAlignment.setToolTipText(
                "Align all group titles within their group block");
        groupLabelGroup.addActionListener(e -> {
            if (!syncingGroupLabelControls) syncGroupLabelText();
        });
        groupLabelAlignment.addActionListener(e -> {
            if (syncingGroupLabelControls || config == null) return;
            config = config.toBuilder().groupHeaderAlignment(
                    parseAlignment(selected(groupLabelAlignment))).build();
            saveConfig();
            refreshPreview();
        });
        groupLabelText.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) {
                queueGroupLabelUpdate();
            }
            @Override public void removeUpdate(DocumentEvent event) {
                queueGroupLabelUpdate();
            }
            @Override public void changedUpdate(DocumentEvent event) {
                queueGroupLabelUpdate();
            }
        });
        groupLabelText.addActionListener(e -> {
            groupLabelUpdateTimer.stop();
            applyGroupLabelText();
        });
        originalGroupLabel.addActionListener(e -> resetGroupLabelText());
    }

    private void queueGroupLabelUpdate() {
        if (!syncingGroupLabelControls) groupLabelUpdateTimer.restart();
    }

    private void applyGroupLabelText() {
        if (syncingGroupLabelControls || config == null) return;
        String group = selected(groupLabelGroup);
        if (group.isEmpty()) return;
        config = withGroupLabelText(config, group, groupLabelText.getText());
        saveConfig();
        refreshPreview();
    }

    private void resetGroupLabelText() {
        if (config == null) return;
        String group = selected(groupLabelGroup);
        if (group.isEmpty()) return;
        groupLabelUpdateTimer.stop();
        config = withGroupLabelText(config, group, null);
        syncGroupLabelText();
        saveConfig();
        refreshPreview();
    }

    private void syncGroupLabelText() {
        groupLabelUpdateTimer.stop();
        String group = selected(groupLabelGroup);
        syncingGroupLabelControls = true;
        try {
            boolean available = config != null && !group.isEmpty();
            groupLabelText.setEnabled(available);
            originalGroupLabel.setEnabled(available);
            groupLabelText.setText(available ? config.externalLabelText(
                    PanelConfig.ExternalLabelKind.GROUP, group, group) : "");
        } finally {
            syncingGroupLabelControls = false;
        }
    }

    static PanelConfig withGroupLabelText(PanelConfig config, String group,
            String text) {
        if (config == null) throw new IllegalArgumentException("config is required");
        return config.toBuilder().externalLabelOverride(
                PanelConfig.ExternalLabelKind.GROUP, group, text).build();
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
        for (java.awt.event.ActionListener listener
                : scaleLength.getActionListeners()) {
            scaleLength.removeActionListener(listener);
        }
        for (java.awt.event.ActionListener listener
                : scaleCorner.getActionListeners()) {
            scaleCorner.removeActionListener(listener);
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
        JButton externalLabels = new JButton("Edit external labels...");
        JButton annotations = new JButton("Edit annotations...");
        JButton calibration = new JButton("Edit calibration...");
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
        externalLabels.addActionListener(e -> {
            PanelConfig before = config;
            PanelConfig edited = ExternalLabelEditor.edit(owner(), previewRecords,
                    config,
                    new ExternalLabelEditor.Listener() {
                        @Override public void externalLabelsChanged(
                                PanelConfig candidate) {
                            config = candidate;
                            saveConfig();
                        }
                    });
            config = edited == null ? before : edited;
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
        calibration.addActionListener(e -> editCalibrationOverrides());
        buttons.add(spacing);
        buttons.add(externalLabels);
        buttons.add(annotations);
        buttons.add(calibration);
        return buttons;
    }

    private void editCalibrationOverrides() {
        List<CalibrationEntry> entries = calibrationEntries();
        if (entries.isEmpty()) return;
        JPanel editor = new JPanel(new GridLayout(0, 3, 6, 4));
        editor.add(new JLabel("Selected source image"));
        editor.add(new JLabel("Width um/px"));
        editor.add(new JLabel("Height um/px"));
        for (CalibrationEntry entry : entries) {
            JLabel source = new JLabel(entry.label);
            source.setToolTipText(entry.imageId);
            editor.add(source);
            editor.add(entry.width);
            editor.add(entry.height);
        }
        JScrollPane scroll = new JScrollPane(editor);
        scroll.setPreferredSize(new Dimension(620,
                Math.min(360, 42 + entries.size() * 30)));
        while (JOptionPane.showConfirmDialog(owner(), scroll,
                "Per-image calibration (blank uses embedded metadata)",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                == JOptionPane.OK_OPTION) {
            try {
                LinkedHashMap<String, CalibrationOverride> next =
                        new LinkedHashMap<String, CalibrationOverride>(
                                context.calibrationOverrides);
                for (CalibrationEntry entry : entries) {
                    String x = entry.width.getText().trim();
                    String y = entry.height.getText().trim();
                    if (x.isEmpty() && y.isEmpty()) {
                        next.remove(entry.imageId);
                    } else if (x.isEmpty() || y.isEmpty()) {
                        throw new IllegalArgumentException("Enter both pixel sizes for "
                                + entry.imageId + ".");
                    } else {
                        next.put(entry.imageId, new CalibrationOverride(
                                parseRequiredDouble(x, entry.imageId),
                                parseRequiredDouble(y, entry.imageId)));
                    }
                }
                context.calibrationOverrides = next;
                rebuildPreviewRecords();
                return;
            } catch (IllegalArgumentException invalid) {
                JOptionPane.showMessageDialog(owner(), invalid.getMessage(),
                        "Invalid calibration", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private List<CalibrationEntry> calibrationEntries() {
        List<CalibrationEntry> entries = new ArrayList<CalibrationEntry>();
        if (context.chooserData == null) return entries;
        for (String group : orderedGroups()) {
            RowImage.SubjectRow row = context.selectedRowsByGroup.get(group);
            if (row == null) continue;
            for (Integer imageIndex : row.imageIndices()) {
                MetadataRow metadata = context.chooserData.table().rows()
                        .get(imageIndex.intValue());
                String imageId = context.chooserData.table().csvFileName(metadata);
                CalibrationOverride existing = context.calibrationOverrides.get(imageId);
                entries.add(new CalibrationEntry(imageId,
                        metadata.group + " / " + metadata.subject + " - " + imageId,
                        existing));
            }
        }
        return entries;
    }

    private void setCalibrationOverride(String imageId,
            CalibrationOverride override) {
        if (imageId == null || imageId.trim().isEmpty()) {
            throw new IllegalArgumentException("imageId is required");
        }
        context.calibrationOverrides.put(imageId.trim().replace('\\', '/'), override);
        rebuildPreviewRecords();
    }

    private void rebuildPreviewRecords() {
        deletePreviewRecords(previewRecords);
        previewRecords = new ArrayList<PanelRecord>();
        context.layoutPanelRecords.clear();
        recordsDirty = true;
        refreshPreview();
    }

    private static double parseRequiredDouble(String value, String imageId) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Pixel sizes for " + imageId
                    + " must be numeric.");
        }
    }

    private void refreshPreview() {
        if (config == null) return;
        try {
            if (recordsDirty || previewRecords.isEmpty()) {
                previewRecords = buildPreviewRecords();
                recordsDirty = false;
            }
            if (previewRecords.isEmpty()) {
                showEmpty("Choose one section for each group first.");
                return;
            }
            PanelWriter.WriteReport report = new PanelWriter.WriteReport();
            previewFigure = PanelWriter.renderOverviewPanel(
                    previewRecords, config, report, PREVIEW_SUPERSAMPLE);
            previewImageBoxes = report.imageBoxes();
            hoveredImageBox = null;
            updatePreviewIcon();
            preview.setText(null);
            size.setText("High-quality preview: "
                    + logicalDimension(previewFigure.getWidth()) + " x "
                    + logicalDimension(previewFigure.getHeight())
                    + " px (2x rendered)");
            saveConfig();
        } catch (RuntimeException | IOException failure) {
            previewImageBoxes = Collections.emptyList();
            hoveredImageBox = null;
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
        boolean complete = false;
        FPBRenderer renderer = new FPBRenderer();
        File dir = previewDirectory();
        try {
            for (String group : orderedGroups()) {
                RowImage.SubjectRow row = picks.get(group);
                if (row == null) continue;
                for (Integer imageIndex : row.imageIndices()) {
                    int index = imageIndex.intValue();
                    fpb.io.PlaneCache.ImagePlanes sourcePlanes =
                            data.planes().image(index);
                    MetadataRow metadata = data.table().rows().get(index);
                    String imageId = data.table().csvFileName(metadata);
                    ImageOrientation orientation = orientationFor(imageId);
                    fpb.io.PlaneCache.Plane previewPlane = sourcePlanes.plane(
                            channels.get(0).channelIndex());
                    int[] previewSize = FPBRenderer.aspectFitDimensions(
                            previewPlane.width(), previewPlane.height(),
                            config.cellSizePx() * PREVIEW_SUPERSAMPLE,
                            config.cellSizePx() * PREVIEW_SUPERSAMPLE);
                    FPBRenderer.PanelRender render = renderer.renderPanel(data.planes(),
                            data.histograms(), index, channels,
                            previewSize[0], previewSize[1]);
                    CalibrationCheck.Result calibration = orientation.orientCalibration(
                            CalibrationCheck.resolve(
                            sourcePlanes.calibration(), sourcePlanes.openedWithBioFormats(),
                            context.calibrationOverrides.get(imageId)));
                    int orientedSourceWidth = orientation.orientedWidth(
                            sourcePlanes.sourceWidthPx(), sourcePlanes.sourceHeightPx());
                    int orientedSourceHeight = orientation.orientedHeight(
                            sourcePlanes.sourceWidthPx(), sourcePlanes.sourceHeightPx());
                    for (int i = 0; i < channels.size(); i++) {
                        FPBRenderer.ChannelRequest channel = channels.get(i);
                        BufferedImage image = orientation.apply(
                                render.channelImages().get(i));
                        File file = writePreviewImage(dir, group, row.subject(),
                                channel.name(), image);
                        records.add(record(file, render.sourceFile(), metadata,
                                imageId,
                                channel.name(), channel.name(), channel.channelIndex(),
                                image, orientedSourceWidth,
                                orientedSourceHeight, calibration));
                    }
                    BufferedImage merge = orientation.apply(render.mergeImage());
                    File mergeFile = writePreviewImage(dir, group, row.subject(),
                            "Merge", merge);
                    records.add(record(mergeFile, render.sourceFile(), metadata,
                            imageId,
                            "Merge", "Merge", -1, merge, orientedSourceWidth,
                            orientedSourceHeight, calibration));
                }
            }
            context.layoutPanelRecords = new ArrayList<PanelRecord>(records);
            complete = true;
            return records;
        } finally {
            if (!complete) deletePreviewRecords(records);
        }
    }

    private PanelRecord record(File file, File sourceFile, MetadataRow metadata,
            String imageId,
            String outputName, String channelName, int channelIndex,
            BufferedImage image, int sourceWidthPx, int sourceHeightPx,
            CalibrationCheck.Result calibration) {
        CalibrationCheck.Result safe = calibration == null
                ? CalibrationCheck.none() : calibration;
        return new PanelRecord(file, sourceFile, metadata.group, metadata.subject,
                metadata.section, imageId, outputName, channelName, channelIndex,
                sourceWidthPx, sourceHeightPx, safe.pixelWidthUm(),
                safe.pixelHeightUm(), safe.source());
    }

    private File writePreviewImage(File dir, String group, String subject,
            String output, BufferedImage image) throws IOException {
        File file = File.createTempFile(safe(group) + "_" + safe(subject)
                + "_" + safe(output) + "_", ".png", dir);
        file.deleteOnExit();
        boolean written = false;
        try {
            PanelWriter.writePngAtomically(image, file, config.outputDpi());
            written = true;
            return file;
        } finally {
            if (!written) Files.deleteIfExists(file.toPath());
        }
    }

    private static void deletePreviewRecords(List<PanelRecord> records) {
        if (records == null) return;
        for (PanelRecord record : records) {
            if (record == null || record.imageFile() == null) continue;
            try {
                Files.deleteIfExists(record.imageFile().toPath());
            } catch (IOException ignored) {
                record.imageFile().deleteOnExit();
            }
        }
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
                .imageOrientations(orientationTokens())
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

    private void synchronizeImageOrientations() {
        if (context.imageOrientations.isEmpty()
                && !config.imageOrientations().isEmpty()) {
            for (java.util.Map.Entry<String, String> entry
                    : config.imageOrientations().entrySet()) {
                context.imageOrientations.put(entry.getKey(),
                        ImageOrientation.fromToken(entry.getValue()));
            }
        } else {
            config = config.toBuilder()
                    .imageOrientations(orientationTokens()).build();
        }
    }

    private ImageOrientation orientationFor(String imageId) {
        String key = normalizeImageId(imageId);
        ImageOrientation orientation = context.imageOrientations.get(key);
        if (orientation == null) orientation = config.imageOrientation(key);
        return orientation == null ? ImageOrientation.IDENTITY : orientation;
    }

    private java.util.Map<String, String> orientationTokens() {
        LinkedHashMap<String, String> tokens =
                new LinkedHashMap<String, String>();
        for (java.util.Map.Entry<String, ImageOrientation> entry
                : context.imageOrientations.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isIdentity()) {
                tokens.put(normalizeImageId(entry.getKey()),
                        entry.getValue().token());
            }
        }
        return tokens;
    }

    private static String normalizeImageId(String imageId) {
        return imageId == null ? "" : imageId.trim().replace('\\', '/');
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
        previewFigure = null;
        previewImageBoxes = Collections.emptyList();
        hoveredImageBox = null;
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

    private void updatePreviewIcon() {
        if (previewFigure == null) return;
        int logicalWidth = logicalDimension(previewFigure.getWidth());
        int logicalHeight = logicalDimension(previewFigure.getHeight());
        String zoom = selected(previewZoom);
        int targetWidth;
        int targetHeight;
        if ("Fit".equals(zoom)) {
            java.awt.Dimension extent = previewScroll.getViewport().getExtentSize();
            int maxWidth = extent.width > 32 ? extent.width - 12 : 720;
            int maxHeight = extent.height > 32 ? extent.height - 12 : 520;
            double scale = Math.min(1.0, Math.min(maxWidth / (double) logicalWidth,
                    maxHeight / (double) logicalHeight));
            targetWidth = Math.max(1, (int) Math.round(logicalWidth * scale));
            targetHeight = Math.max(1, (int) Math.round(logicalHeight * scale));
            previewDisplayScale = scale;
        } else {
            double scale = parseZoomScale(zoom, previewDisplayScale);
            previewDisplayScale = scale;
            targetWidth = Math.max(1, (int) Math.round(logicalWidth * scale));
            targetHeight = Math.max(1, (int) Math.round(logicalHeight * scale));
        }
        preview.setIcon(new ImageIcon(resizeHighQuality(previewFigure,
                targetWidth, targetHeight)));
        preview.revalidate();
        preview.repaint();
    }

    private void zoomAt(java.awt.event.MouseWheelEvent event) {
        if (previewFigure == null) return;
        event.consume();
        javax.swing.JViewport viewport = previewScroll.getViewport();
        java.awt.Point cursor = SwingUtilities.convertPoint(previewScroll,
                event.getPoint(), viewport);
        java.awt.Point viewPosition = viewport.getViewPosition();
        javax.swing.Icon oldIcon = preview.getIcon();
        int oldWidth = oldIcon == null ? 1 : oldIcon.getIconWidth();
        int oldHeight = oldIcon == null ? 1 : oldIcon.getIconHeight();
        int oldIconX = Math.max(0, (preview.getWidth() - oldWidth) / 2);
        int oldIconY = Math.max(0, (preview.getHeight() - oldHeight) / 2);
        double imageX = (viewPosition.x + cursor.x - oldIconX)
                / (double) Math.max(1, oldWidth);
        double imageY = (viewPosition.y + cursor.y - oldIconY)
                / (double) Math.max(1, oldHeight);

        final double next = wheelZoom(previewDisplayScale,
                event.getPreciseWheelRotation());
        setPreviewZoom(next);
        final java.awt.Point anchor = cursor;
        final double anchorX = imageX;
        final double anchorY = imageY;
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                javax.swing.Icon icon = preview.getIcon();
                if (icon == null) return;
                java.awt.Dimension extent = viewport.getExtentSize();
                int iconX = Math.max(0,
                        (preview.getWidth() - icon.getIconWidth()) / 2);
                int iconY = Math.max(0,
                        (preview.getHeight() - icon.getIconHeight()) / 2);
                int x = (int) Math.round(iconX + anchorX * icon.getIconWidth()
                        - anchor.x);
                int y = (int) Math.round(iconY + anchorY * icon.getIconHeight()
                        - anchor.y);
                x = Math.max(0, Math.min(x,
                        Math.max(0, preview.getWidth() - extent.width)));
                y = Math.max(0, Math.min(y,
                        Math.max(0, preview.getHeight() - extent.height)));
                viewport.setViewPosition(new java.awt.Point(x, y));
            }
        });
    }

    private void setPreviewZoom(double scale) {
        int percent = (int) Math.round(clampZoom(scale) * 100.0);
        zoomSyncing = true;
        try {
            previewZoom.setSelectedItem(percent + "%");
        } finally {
            zoomSyncing = false;
        }
        updatePreviewIcon();
    }

    static double wheelZoom(double currentScale, double wheelRotation) {
        double safe = Double.isFinite(currentScale) ? currentScale : 1.0;
        double next = safe * Math.pow(1.12, -wheelRotation);
        return Math.round(clampZoom(next) * 100.0) / 100.0;
    }

    private static double parseZoomScale(String value, double fallback) {
        String text = value == null ? "" : value.trim().replace("%", "");
        try {
            return clampZoom(Double.parseDouble(text) / 100.0);
        } catch (NumberFormatException invalid) {
            return clampZoom(Double.isFinite(fallback) ? fallback : 1.0);
        }
    }

    private static double clampZoom(double value) {
        return Math.max(0.25, Math.min(4.0, value));
    }

    private void scrollCanvas(java.awt.event.MouseWheelEvent event) {
        event.consume();
        javax.swing.JScrollBar bar = event.isShiftDown()
                ? previewScroll.getHorizontalScrollBar()
                : previewScroll.getVerticalScrollBar();
        int direction = event.getPreciseWheelRotation() < 0 ? -1 : 1;
        int increment = Math.max(12, bar.getUnitIncrement(direction));
        int movement = (int) Math.round(event.getPreciseWheelRotation()
                * increment * 3.0);
        bar.setValue(bar.getValue() + movement);
    }

    private void installCanvasPanning() {
        java.awt.event.MouseAdapter pan = new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                dragStartScreen = event.getLocationOnScreen();
                dragStartView = previewScroll.getViewport().getViewPosition();
                preview.setCursor(java.awt.Cursor.getPredefinedCursor(
                        java.awt.Cursor.MOVE_CURSOR));
            }

            @Override public void mouseDragged(java.awt.event.MouseEvent event) {
                if (dragStartScreen == null || dragStartView == null) return;
                java.awt.Point now = event.getLocationOnScreen();
                int x = dragStartView.x - (now.x - dragStartScreen.x);
                int y = dragStartView.y - (now.y - dragStartScreen.y);
                java.awt.Dimension extent = previewScroll.getViewport().getExtentSize();
                x = Math.max(0, Math.min(x,
                        Math.max(0, preview.getWidth() - extent.width)));
                y = Math.max(0, Math.min(y,
                        Math.max(0, preview.getHeight() - extent.height)));
                previewScroll.getViewport().setViewPosition(
                        new java.awt.Point(x, y));
            }

            @Override public void mouseReleased(java.awt.event.MouseEvent event) {
                dragStartScreen = null;
                dragStartView = null;
                preview.setCursor(java.awt.Cursor.getDefaultCursor());
            }
        };
        preview.addMouseListener(pan);
        preview.addMouseMotionListener(pan);
    }

    private void installOrientationControls() {
        java.awt.event.MouseAdapter controls = new java.awt.event.MouseAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent event) {
                PanelWriter.ImageBox next = imageBoxAt(event.getPoint());
                if (next != hoveredImageBox) {
                    hoveredImageBox = next;
                    preview.repaint();
                }
                Rectangle bounds = controlBounds(hoveredImageBox);
                boolean overButton = ImageOrientationControls.actionAt(
                        event.getPoint(), bounds) != null;
                if (overButton) {
                    preview.setCursor(java.awt.Cursor.getPredefinedCursor(
                            java.awt.Cursor.HAND_CURSOR));
                } else if (dragStartScreen == null) {
                    preview.setCursor(java.awt.Cursor.getDefaultCursor());
                }
            }

            @Override public void mouseExited(java.awt.event.MouseEvent event) {
                hoveredImageBox = null;
                preview.repaint();
            }

            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)
                        || hoveredImageBox == null) return;
                ImageOrientation.Action action = ImageOrientationControls.actionAt(
                        event.getPoint(), controlBounds(hoveredImageBox));
                if (action != null) applyOrientation(hoveredImageBox, action);
            }
        };
        preview.addMouseListener(controls);
        preview.addMouseMotionListener(controls);
    }

    private PanelWriter.ImageBox imageBoxAt(Point point) {
        if (point == null) return null;
        if (hoveredImageBox != null
                && controlBounds(hoveredImageBox).contains(point)) {
            return hoveredImageBox;
        }
        for (PanelWriter.ImageBox box : previewImageBoxes) {
            if (displayedBounds(box.bounds()).contains(point)) return box;
        }
        return null;
    }

    private Rectangle displayedBounds(Rectangle logical) {
        javax.swing.Icon icon = preview.getIcon();
        if (logical == null || icon == null) return new Rectangle();
        int iconX = Math.max(0, (preview.getWidth() - icon.getIconWidth()) / 2);
        int iconY = Math.max(0, (preview.getHeight() - icon.getIconHeight()) / 2);
        return new Rectangle(iconX + scaled(logical.x, previewDisplayScale),
                iconY + scaled(logical.y, previewDisplayScale),
                Math.max(1, scaled(logical.width, previewDisplayScale)),
                Math.max(1, scaled(logical.height, previewDisplayScale)));
    }

    private Rectangle controlBounds(PanelWriter.ImageBox box) {
        if (box == null) return new Rectangle();
        Rectangle image = displayedBounds(box.bounds());
        Rectangle canvas = new Rectangle(0, 0,
                Math.max(0, preview.getWidth()),
                Math.max(0, preview.getHeight()));
        Rectangle visible = preview.getVisibleRect();
        return visibleOrientationControlBounds(image, canvas, visible,
                ImageOrientationControls.PANEL_SIZE, 3);
    }

    static Rectangle visibleOrientationControlBounds(Rectangle image,
            Rectangle canvas, Rectangle visible, int panelSize, int inset) {
        int size = Math.max(1, panelSize);
        int gap = Math.max(0, inset);
        Rectangle safeCanvas = canvas == null ? new Rectangle()
                : new Rectangle(canvas);
        Rectangle safeVisible = visible == null ? new Rectangle()
                : new Rectangle(visible);
        Rectangle allowed = safeCanvas.intersection(safeVisible);
        if (allowed.width < size || allowed.height < size) {
            allowed = safeCanvas;
        }
        Rectangle anchor = image == null ? new Rectangle() : image;
        int preferredX = anchor.x + anchor.width - size - gap;
        int preferredY = anchor.y + gap;
        int minX = allowed.x;
        int minY = allowed.y;
        int maxX = allowed.x + Math.max(0, allowed.width - size);
        int maxY = allowed.y + Math.max(0, allowed.height - size);
        int x = Math.max(minX, Math.min(preferredX, maxX));
        int y = Math.max(minY, Math.min(preferredY, maxY));
        return new Rectangle(x, y, size, size);
    }

    private void applyOrientation(PanelWriter.ImageBox box,
            ImageOrientation.Action action) {
        String imageId = normalizeImageId(box == null ? "" : box.imageId());
        if (imageId.isEmpty() || action == null) return;
        ImageOrientation next = orientationFor(imageId).then(action);
        if (next.isIdentity()) context.imageOrientations.remove(imageId);
        else context.imageOrientations.put(imageId, next);
        config = config.toBuilder().imageOrientation(imageId, next).build();
        for (RowImage.SubjectRow row : context.selectedRowsByGroup.values()) {
            if (row != null && imageId.equals(normalizeImageId(row.imageId()))) {
                row.setOrientation(next);
            }
        }
        hoveredImageBox = null;
        rebuildPreviewRecords();
    }

    private void paintOrientationControls(Graphics2D graphics) {
        if (hoveredImageBox == null || preview.getIcon() == null) return;
        ImageOrientationControls.paint(graphics,
                controlBounds(hoveredImageBox),
                orientationFor(hoveredImageBox.imageId()));
    }

    private static int scaled(int value, double scale) {
        return (int) Math.round(value * scale);
    }

    private final class OrientationPreview extends JLabel {
        OrientationPreview() {
            super("Choose images first.");
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (graphics instanceof Graphics2D) {
                paintOrientationControls((Graphics2D) graphics);
            }
        }
    }

    private static int logicalDimension(int supersampled) {
        return Math.max(1, (int) Math.round(
                supersampled / (double) PREVIEW_SUPERSAMPLE));
    }

    static BufferedImage scaleForPreview(BufferedImage image,
            int maxWidth, int maxHeight) {
        if (image == null) return null;
        double scale = Math.min(1.0, Math.min(maxWidth / (double) image.getWidth(),
                maxHeight / (double) image.getHeight()));
        if (scale >= 1.0) return image;
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        return resizeHighQuality(image, width, height);
    }

    static BufferedImage resizeHighQuality(BufferedImage image,
            int targetWidth, int targetHeight) {
        if (image == null) return null;
        int checkedWidth = Math.max(1, targetWidth);
        int checkedHeight = Math.max(1, targetHeight);
        if (image.getWidth() == checkedWidth && image.getHeight() == checkedHeight) {
            return image;
        }
        BufferedImage current = image;
        while (current.getWidth() > checkedWidth * 2
                || current.getHeight() > checkedHeight * 2) {
            int nextWidth = Math.max(checkedWidth, current.getWidth() / 2);
            int nextHeight = Math.max(checkedHeight, current.getHeight() / 2);
            current = resizeStep(current, nextWidth, nextHeight);
        }
        return resizeStep(current, checkedWidth, checkedHeight);
    }

    private static BufferedImage resizeStep(BufferedImage image,
            int width, int height) {
        BufferedImage out = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            PanelWriter.applyQualityHints(g);
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(java.awt.RenderingHints.KEY_ALPHA_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
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

    private static PanelConfig.TextAlignment parseAlignment(String text) {
        if ("Middle".equals(text)) return PanelConfig.TextAlignment.CENTER;
        if ("Right".equals(text)) return PanelConfig.TextAlignment.RIGHT;
        return PanelConfig.TextAlignment.LEFT;
    }

    private static String alignmentLabel(PanelConfig.TextAlignment alignment) {
        if (alignment == PanelConfig.TextAlignment.CENTER) return "Middle";
        if (alignment == PanelConfig.TextAlignment.RIGHT) return "Right";
        return "Left";
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

    private static final class CalibrationEntry {
        private final String imageId;
        private final String label;
        private final JTextField width = new JTextField(8);
        private final JTextField height = new JTextField(8);

        private CalibrationEntry(String imageId, String label,
                CalibrationOverride existing) {
            this.imageId = imageId;
            this.label = label;
            if (existing != null) {
                width.setText(Double.toString(existing.pixelWidthUm()));
                height.setText(Double.toString(existing.pixelHeightUm()));
            }
        }
    }

    private static String safe(String value) {
        String clean = value == null ? "" : value.trim()
                .replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.isEmpty() ? "preview" : clean;
    }
}
