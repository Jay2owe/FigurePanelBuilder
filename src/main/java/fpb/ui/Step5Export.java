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
import fpb.FPBMacroOptions;
import fpb.figure.CalibrationCheck;
import fpb.figure.FigureWriter;
import fpb.figure.PanelConfig;
import fpb.figure.PanelRecord;
import fpb.figure.PanelWriter;
import fpb.meta.MetadataRow;
import fpb.record.ManifestWriter;
import fpb.record.MethodsWriter;
import fpb.record.SelectionWriter;
import fpb.render.ClipReport;
import fpb.render.DisplayRange;
import fpb.render.FPBRenderer;
import fpb.svg.SvgWriter;
import fpb.ui.chooser.RowImage;
import ij.IJ;
import ij.plugin.frame.Recorder;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Final wizard step: export settings, progress and completion summary. */
public final class Step5Export implements WizardStep {

    public interface ProgressListener {
        void update(String message, int completed, int total);
    }

    public static final ProgressListener NONE = new ProgressListener() {
        @Override
        public void update(String message, int completed, int total) {
            // no-op
        }
    };

    private final FPBWizard.Context context;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));
    private final JTextField outputFolder = new JTextField(32);
    private final JTextField dpi = new JTextField("300", 5);
    private final JComboBox<String> exportScale =
            new JComboBox<String>(new String[] { "1x", "2x", "3x", "4x" });
    private final JCheckBox png = new JCheckBox("PNG", true);
    private final JCheckBox tiff = new JCheckBox("TIFF", true);
    private final JCheckBox svg = new JCheckBox("SVG", true);
    private final JCheckBox panels = new JCheckBox("individual panels", true);
    private final JCheckBox records = new JCheckBox("manifest and methods", true);
    private final JButton build = new JButton("Build figure");
    private final JButton cancel = new JButton("Cancel export");
    private final JButton openFolder = new JButton("Open folder");
    private final JProgressBar progress = new JProgressBar();
    private final JTextArea summary = new JTextArea(8, 64);
    private volatile SwingWorker<ExportResult, Void> worker;
    private volatile File completedFolder;

    public Step5Export(FPBWizard.Context context) {
        this.context = context;
        panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        panel.add(form(), BorderLayout.NORTH);
        summary.setEditable(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        panel.add(new JScrollPane(summary), BorderLayout.CENTER);
        panel.add(progressRow(), BorderLayout.SOUTH);
        build.addActionListener(e -> startExport());
        cancel.addActionListener(e -> cancelExport());
        openFolder.addActionListener(e -> openCompletedFolder());
        cancel.setEnabled(false);
        openFolder.setEnabled(false);
    }

    @Override
    public String title() {
        return "Export";
    }

    @Override
    public String nextTitle() {
        return "Build figure";
    }

    @Override
    public JComponent component() {
        return panel;
    }

    @Override
    public void onShow() {
        if (outputFolder.getText().trim().isEmpty()) {
            File root = context == null ? null : context.folder;
            outputFolder.setText((root == null ? new File(".") : root)
                    .getAbsolutePath());
        }
        if (context != null && context.panelConfig != null) {
            dpi.setText(String.valueOf(context.panelConfig.outputDpi()));
            exportScale.setSelectedItem(context.panelConfig.exportScale() + "x");
        }
    }

    @Override
    public boolean canAdvance() {
        return true;
    }

    private JPanel form() {
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 8));
        JPanel output = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        output.add(new JLabel("Output folder"));
        output.add(outputFolder);
        JButton browse = new JButton("Browse");
        browse.addActionListener(e -> browseOutputFolder());
        output.add(browse);
        form.add(output);

        JPanel size = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        size.add(new JLabel("Resolution"));
        size.add(dpi);
        size.add(new JLabel("dpi"));
        size.add(new JLabel("Export scale"));
        size.add(exportScale);
        form.add(size);

        JPanel formats = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        formats.add(new JLabel("Formats"));
        formats.add(png);
        formats.add(tiff);
        formats.add(svg);
        form.add(formats);

        JPanel also = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        also.add(new JLabel("Also write"));
        also.add(panels);
        also.add(records);
        also.add(build);
        form.add(also);
        return form;
    }

    private JPanel progressRow() {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        progress.setStringPainted(true);
        progress.setString("Ready");
        row.add(progress, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(cancel);
        buttons.add(openFolder);
        row.add(buttons, BorderLayout.EAST);
        return row;
    }

    private void browseOutputFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        File current = new File(outputFolder.getText().trim());
        if (current.isDirectory()) chooser.setCurrentDirectory(current);
        if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
            outputFolder.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void startExport() {
        if (worker != null && !worker.isDone()) return;
        final Settings settings = settingsFromControls();
        if (!settings.writePng && !settings.writeTiff && !settings.writeSvg) {
            showMessage("Choose at least one figure format.");
            return;
        }
        if (settings.outputRoot == null || settings.outputRoot.getPath().trim().isEmpty()) {
            showMessage("Choose an output folder.");
            return;
        }
        if (new File(settings.outputRoot, FigureWriter.ROOT_DIR).isDirectory()
                && hasChildren(new File(settings.outputRoot, FigureWriter.ROOT_DIR))) {
            int result = JOptionPane.showConfirmDialog(panel,
                    "Write a new figure folder inside the existing output tree?",
                    "Figure Panel Builder", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return;
        }
        recordMacroCall(settings);
        progress.setValue(0);
        progress.setString("Starting export");
        summary.setText("");
        build.setEnabled(false);
        cancel.setEnabled(true);
        openFolder.setEnabled(false);
        worker = new SwingWorker<ExportResult, Void>() {
            @Override
            protected ExportResult doInBackground() throws Exception {
                return export(context, settings, new FigureWriter.CancelCheck() {
                    @Override
                    public boolean isCancelled() {
                        return Step5Export.this.worker != null
                                && Step5Export.this.worker.isCancelled();
                    }
                }, new ProgressListener() {
                    @Override
                    public void update(String message, int completed, int total) {
                        setProgress(total <= 0 ? 0
                                : Math.min(100, (int) Math.round(
                                        completed * 100.0 / total)));
                        progress.setString(message);
                    }
                });
            }

            @Override
            protected void done() {
                build.setEnabled(true);
                cancel.setEnabled(false);
                try {
                    ExportResult result = get();
                    completedFolder = result.figureDirectory();
                    progress.setValue(100);
                    progress.setString("Export complete");
                    summary.setText(result.summaryText());
                    openFolder.setEnabled(true);
                } catch (Exception failure) {
                    progress.setString("Export stopped");
                    summary.setText(rootMessage(failure));
                }
            }
        };
        worker.addPropertyChangeListener(event -> {
            if ("progress".equals(event.getPropertyName())) {
                progress.setValue(((Integer) event.getNewValue()).intValue());
            }
        });
        worker.execute();
    }

    private void cancelExport() {
        SwingWorker<ExportResult, Void> current = worker;
        if (current != null) current.cancel(true);
    }

    private void recordMacroCall(Settings settings) {
        if (!Recorder.record) return;
        try {
            Recorder.recordString("run(\"" + FPBMacroOptions.PLUGIN_NAME
                    + "\", \"" + FPBMacroOptions.fromContext(context, settings)
                    .toMacroOptions() + "\");\n");
        } catch (IllegalArgumentException ex) {
            IJ.log("Figure Panel Builder: Could not record macro options: "
                    + ex.getMessage());
        }
    }

    private void openCompletedFolder() {
        if (completedFolder == null || !completedFolder.isDirectory()) return;
        try {
            Desktop.getDesktop().open(completedFolder);
        } catch (IOException failure) {
            showMessage(failure.getMessage());
        }
    }

    private Settings settingsFromControls() {
        return new Settings(new File(outputFolder.getText().trim()),
                figureName(), parseInt(dpi.getText(), 300),
                parseScale((String) exportScale.getSelectedItem()),
                png.isSelected(), tiff.isSelected(), svg.isSelected(),
                panels.isSelected(), records.isSelected());
    }

    private String figureName() {
        File folder = context == null ? null : context.folder;
        if (folder == null) return context != null && context.quickGridRequested
                ? "Quick_grid" : "Figure";
        String name = folder.getName();
        return context != null && context.quickGridRequested
                ? name + "_Quick_grid" : name;
    }

    public static ExportResult export(FPBWizard.Context context, Settings settings,
            FigureWriter.CancelCheck cancelCheck, ProgressListener listener)
            throws IOException {
        if (context == null) throw new IllegalArgumentException("context is required");
        if (settings == null) throw new IllegalArgumentException("settings is required");
        ProgressListener progress = listener == null ? NONE : listener;
        FigureWriter.CancelCheck cancel = cancelCheck == null
                ? FigureWriter.NEVER_CANCELLED : cancelCheck;
        if (context.chooserData == null) {
            throw new IOException("Choose images before export.");
        }
        PanelConfig base = context.panelConfig == null
                ? PanelConfig.builder().createOverviewPanel(true).build()
                : context.panelConfig;
        PanelConfig config = base.toBuilder()
                .outputDpi(settings.dpi)
                .exportScale(settings.exportScale)
                .annotateIndividualPanels(settings.writeIndividualPanels)
                .build();
        context.panelConfig = config;
        List<PanelRecord> panelRecords = ensurePanelRecords(context, config);
        if (panelRecords.isEmpty()) throw new IOException("No panels are ready to export.");

        int total = 3 + (settings.writeSvg ? 1 : 0)
                + (settings.writeRecords ? 3 : 0);
        int step = 0;
        progress.update("Writing figure files", ++step, total);
        FigureWriter.FigureOutput figure = new FigureWriter().writeFigure(
                settings.outputRoot, settings.figureName, panelRecords, config,
                settings.writePng, settings.writeTiff,
                settings.writeIndividualPanels, cancel);
        List<File> written = new ArrayList<File>(figure.writtenFiles());

        PanelConfig exportConfig = FigureWriter.scaledForExport(config);
        if (settings.writeSvg) {
            checkCancelled(cancel);
            progress.update("Writing SVG", ++step, total);
            File svg = new File(figure.figureDirectory(), "figure.svg");
            SvgWriter.writeOverviewSvg(svg, panelRecords, exportConfig);
            written.add(svg);
        }

        if (settings.writeRecords) {
            checkCancelled(cancel);
            progress.update("Writing manifest", ++step, total);
            File manifest = new File(figure.figureDirectory(), "manifest.csv");
            new ManifestWriter().write(manifest, manifestRows(context, panelRecords));
            written.add(manifest);

            checkCancelled(cancel);
            progress.update("Writing selection records", ++step, total);
            File selection = new File(figure.figureDirectory(), "selection.csv");
            new SelectionWriter().write(selection, selectionRecords(context),
                    statisticName(context), chosenSubjects(context));
            written.add(selection);

            checkCancelled(cancel);
            progress.update("Writing methods text", ++step, total);
            File methods = new File(figure.figureDirectory(), "methods.txt");
            new MethodsWriter().write(methods, methodsRecord(context, panelRecords));
            written.add(methods);
        }
        progress.update("Export complete", total, total);
        return new ExportResult(figure.figureDirectory(), written,
                figure.uncalibratedImages());
    }

    private static List<PanelRecord> ensurePanelRecords(FPBWizard.Context context,
            PanelConfig config) throws IOException {
        if (context.layoutPanelRecords != null && !context.layoutPanelRecords.isEmpty()) {
            return context.layoutPanelRecords;
        }
        List<PanelRecord> records = new ArrayList<PanelRecord>();
        FPBRenderer renderer = new FPBRenderer();
        File dir = previewDirectory();
        for (Map.Entry<String, RowImage.SubjectRow> entry
                : context.selectedRowsByGroup.entrySet()) {
            RowImage.SubjectRow row = entry.getValue();
            FPBRenderer.PanelRender render = renderer.renderPanel(
                    context.chooserData.planes(), context.chooserData.histograms(),
                    row.imageIndex(), context.layoutChannelRequests,
                    config.cellSizePx(), config.cellSizePx());
            MetadataRow metadata = context.chooserData.table().rows()
                    .get(row.imageIndex());
            CalibrationCheck.Result calibration =
                    CalibrationCheck.fromImageMetadata(context.chooserData.planes()
                            .image(row.imageIndex()).calibration());
            for (int i = 0; i < context.layoutChannelRequests.size(); i++) {
                FPBRenderer.ChannelRequest channel =
                        context.layoutChannelRequests.get(i);
                BufferedImage image = render.channelImages().get(i);
                File file = writeTempPanel(dir, metadata.group, metadata.subject,
                        channel.name(), image, config.outputDpi());
                records.add(record(file, metadata, render.sourceFile().getName(),
                        channel.name(), channel.name(), channel.channelIndex(),
                        image, calibration));
            }
            BufferedImage merge = render.mergeImage();
            File mergeFile = writeTempPanel(dir, metadata.group, metadata.subject,
                    "Merge", merge, config.outputDpi());
            records.add(record(mergeFile, metadata, render.sourceFile().getName(),
                    "Merge", "Merge", -1, merge, calibration));
        }
        context.layoutPanelRecords = records;
        return records;
    }

    private static PanelRecord record(File file, MetadataRow metadata, String imageId,
            String outputName, String channelName, int channelIndex,
            BufferedImage image, CalibrationCheck.Result calibration) {
        CalibrationCheck.Result safe = calibration == null
                ? CalibrationCheck.none() : calibration;
        return new PanelRecord(file, metadata.group, metadata.subject,
                metadata.section, imageId, outputName, channelName, channelIndex,
                image.getWidth(), image.getHeight(), safe.pixelWidthUm(),
                safe.pixelHeightUm(), safe.source());
    }

    private static File writeTempPanel(File dir, String group, String subject,
            String output, BufferedImage image, int dpi) throws IOException {
        File file = File.createTempFile(safe(group) + "_" + safe(subject)
                + "_" + safe(output) + "_", ".png", dir);
        file.deleteOnExit();
        PanelWriter.writePngAtomically(image, file, dpi);
        return file;
    }

    private static List<ManifestWriter.Row> manifestRows(FPBWizard.Context context,
            List<PanelRecord> panels) {
        List<ManifestWriter.Row> rows = new ArrayList<ManifestWriter.Row>();
        Map<Integer, DisplayRange> ranges = rangesByChannel(context);
        Map<String, Integer> imageIndexByName = imageIndexByName(context);
        for (PanelRecord panel : panels) {
            DisplayRange range = panel.channelIndex() < 0
                    ? new DisplayRange(0, DisplayRange.MAX_VALUE)
                    : ranges.get(Integer.valueOf(panel.channelIndex()));
            if (range == null) {
                throw new IllegalStateException("A locked display range is required for manifest.csv.");
            }
            ClipReport.ChannelClip clip = clipFor(context, panel, imageIndexByName, range);
            SelectionLookup selection = selectionFor(context, panel);
            rows.add(new ManifestWriter.Row(panel, lutFor(context, panel),
                    panel.preferredImageFile(context.panelConfig.annotateIndividualPanels()),
                    range, clip, rangeSource(context), statisticName(context),
                    selection.value, selection.groupMean, selection.rank,
                    selection.suggestedSubject, selection.chosenSubject,
                    selectionMethod(context), grouping(context)));
        }
        return rows;
    }

    private static ClipReport.ChannelClip clipFor(FPBWizard.Context context,
            PanelRecord panel, Map<String, Integer> imageIndexByName,
            DisplayRange range) {
        if (panel.channelIndex() < 0 || context.chooserData == null) return null;
        Integer imageIndex = imageIndexByName.get(panel.imageId());
        if (imageIndex == null) return null;
        return new ClipReport.ChannelClip(panel.channelIndex(), panel.channelName(),
                context.chooserData.histograms().histogram(imageIndex.intValue(),
                        panel.channelIndex()).clippedLowPercent(range.min()),
                context.chooserData.histograms().histogram(imageIndex.intValue(),
                        panel.channelIndex()).clippedHighPercent(range.max()));
    }

    private static SelectionLookup selectionFor(FPBWizard.Context context,
            PanelRecord panel) {
        if (context.quickGridRequested || panel.channelIndex() < 0) {
            return new SelectionLookup(Double.NaN, Double.NaN, 0, "none", "none");
        }
        if (context.chooserData != null) {
            for (fpb.stats.SelectionRecord record
                    : context.chooserData.selectionRecords()) {
                if (record.group().equals(panel.group())
                        && record.subject().equals(panel.subject())
                        && record.channelIndex() == panel.channelIndex()) {
                    return new SelectionLookup(record.value(), record.groupMean(),
                            0, suggestedSubject(context, panel.group()),
                            chosenSubjects(context).get(panel.group()));
                }
            }
        }
        return new SelectionLookup(Double.NaN, Double.NaN, 0,
                suggestedSubject(context, panel.group()),
                chosenSubjects(context).get(panel.group()));
    }

    private static List<fpb.stats.SelectionRecord> selectionRecords(
            FPBWizard.Context context) {
        if (context.quickGridRequested || context.chooserData == null) {
            return Collections.emptyList();
        }
        return context.chooserData.selectionRecords();
    }

    private static MethodsWriter.Record methodsRecord(FPBWizard.Context context,
            List<PanelRecord> panels) {
        return MethodsWriter.Record.builder()
                .panels(panels)
                .selectionRecords(selectionRecords(context))
                .channelRanges(methodsRanges(context))
                .chosenSubjects(chosenSubjects(context))
                .statisticName(statisticName(context))
                .scaleBarUm(context.panelConfig == null ? null
                        : Double.valueOf(context.panelConfig.scaleBarLengthUm()))
                .build();
    }

    private static List<MethodsWriter.ChannelRange> methodsRanges(
            FPBWizard.Context context) {
        List<MethodsWriter.ChannelRange> ranges =
                new ArrayList<MethodsWriter.ChannelRange>();
        int imageCount = context.chooserData == null ? 0
                : context.chooserData.planes().imageCount();
        for (FPBRenderer.ChannelRequest request : context.layoutChannelRequests) {
            ranges.add(new MethodsWriter.ChannelRange(request.channelIndex(),
                    request.name(), request.colour().name(), request.range(),
                    imageCount));
        }
        return ranges;
    }

    private static Map<String, String> chosenSubjects(FPBWizard.Context context) {
        LinkedHashMap<String, String> chosen = new LinkedHashMap<String, String>();
        if (context == null || context.selectedRowsByGroup == null) return chosen;
        for (Map.Entry<String, RowImage.SubjectRow> entry
                : context.selectedRowsByGroup.entrySet()) {
            chosen.put(entry.getKey(), entry.getValue().subject());
        }
        return chosen;
    }

    private static Map<Integer, DisplayRange> rangesByChannel(FPBWizard.Context context) {
        LinkedHashMap<Integer, DisplayRange> ranges =
                new LinkedHashMap<Integer, DisplayRange>();
        for (FPBRenderer.ChannelRequest request : context.layoutChannelRequests) {
            ranges.put(Integer.valueOf(request.channelIndex()), request.range());
        }
        return ranges;
    }

    private static Map<String, Integer> imageIndexByName(FPBWizard.Context context) {
        LinkedHashMap<String, Integer> map = new LinkedHashMap<String, Integer>();
        if (context.chooserData == null) return map;
        for (int i = 0; i < context.chooserData.planes().imageCount(); i++) {
            map.put(context.chooserData.planes().image(i).sourceFile().getName(),
                    Integer.valueOf(i));
        }
        return map;
    }

    private static String lutFor(FPBWizard.Context context, PanelRecord panel) {
        if (panel.channelIndex() < 0) return "merge";
        for (FPBRenderer.ChannelRequest request : context.layoutChannelRequests) {
            if (request.channelIndex() == panel.channelIndex()) {
                return request.colour().name();
            }
        }
        return "not available";
    }

    private static String suggestedSubject(FPBWizard.Context context, String group) {
        if (context.chooserData == null) return "not available";
        fpb.stats.Suggestion.Result suggestion =
                context.chooserData.suggestions().get(group);
        return suggestion == null ? "not available" : suggestion.suggestedSubject();
    }

    private static String rangeSource(FPBWizard.Context context) {
        return context.quickGridRequested ? QuickGrid.RANGE_SOURCE : "locked";
    }

    private static String selectionMethod(FPBWizard.Context context) {
        return context.quickGridRequested ? QuickGrid.SELECTION_METHOD : "representative";
    }

    private static String grouping(FPBWizard.Context context) {
        return context.quickGridRequested ? QuickGrid.GROUPING : "metadata";
    }

    private static String statisticName(FPBWizard.Context context) {
        return context.quickGridRequested ? "none"
                : context.chooserData == null ? "not available"
                : context.chooserData.subjectStats().statisticName();
    }

    private static File previewDirectory() throws IOException {
        File root = new File(System.getProperty("java.io.tmpdir"),
                "FigurePanelBuilder-export-preview");
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("Could not create preview directory: "
                    + root.getAbsolutePath());
        }
        return root;
    }

    private static void checkCancelled(FigureWriter.CancelCheck cancelCheck)
            throws IOException {
        if (cancelCheck != null && cancelCheck.isCancelled()) {
            throw new IOException("Export cancelled.");
        }
    }

    private static boolean hasChildren(File folder) {
        File[] children = folder.listFiles();
        return children != null && children.length > 0;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int parseScale(String value) {
        if (value == null || value.length() == 0) return 1;
        return parseInt(value.substring(0, 1), 1);
    }

    private static String safe(String value) {
        String clean = value == null ? "" : value.trim()
                .replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.isEmpty() ? "panel" : clean;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private void showMessage(String message) {
        JOptionPane.showMessageDialog(panel, message, "Figure Panel Builder",
                JOptionPane.INFORMATION_MESSAGE);
    }

    public static final class Settings {
        private final File outputRoot;
        private final String figureName;
        private final int dpi;
        private final int exportScale;
        private final boolean writePng;
        private final boolean writeTiff;
        private final boolean writeSvg;
        private final boolean writeIndividualPanels;
        private final boolean writeRecords;

        public Settings(File outputRoot, String figureName, int dpi, int exportScale,
                boolean writePng, boolean writeTiff, boolean writeSvg,
                boolean writeIndividualPanels, boolean writeRecords) {
            this.outputRoot = outputRoot == null ? null : outputRoot.getAbsoluteFile();
            this.figureName = figureName == null || figureName.trim().isEmpty()
                    ? "Figure" : figureName.trim();
            this.dpi = Math.max(72, Math.min(2400, dpi));
            this.exportScale = Math.max(1, Math.min(4, exportScale));
            this.writePng = writePng;
            this.writeTiff = writeTiff;
            this.writeSvg = writeSvg;
            this.writeIndividualPanels = writeIndividualPanels;
            this.writeRecords = writeRecords;
        }

        public File outputRoot() {
            return outputRoot;
        }

        public String figureName() {
            return figureName;
        }

        public int dpi() {
            return dpi;
        }

        public int exportScale() {
            return exportScale;
        }

        public boolean writePng() {
            return writePng;
        }

        public boolean writeTiff() {
            return writeTiff;
        }

        public boolean writeSvg() {
            return writeSvg;
        }

        public boolean writeIndividualPanels() {
            return writeIndividualPanels;
        }

        public boolean writeRecords() {
            return writeRecords;
        }
    }

    public static final class ExportResult {
        private final File figureDirectory;
        private final List<File> writtenFiles;
        private final List<String> uncalibratedImages;

        private ExportResult(File figureDirectory, List<File> writtenFiles,
                List<String> uncalibratedImages) {
            this.figureDirectory = figureDirectory;
            this.writtenFiles = Collections.unmodifiableList(
                    new ArrayList<File>(writtenFiles));
            this.uncalibratedImages = Collections.unmodifiableList(
                    new ArrayList<String>(uncalibratedImages));
        }

        public File figureDirectory() {
            return figureDirectory;
        }

        public List<File> writtenFiles() {
            return writtenFiles;
        }

        public String summaryText() {
            StringBuilder sb = new StringBuilder();
            sb.append("Wrote ").append(writtenFiles.size())
                    .append(" files to ")
                    .append(figureDirectory.getAbsolutePath()).append('\n');
            for (File file : writtenFiles) {
                sb.append(file.getName()).append('\n');
            }
            if (!uncalibratedImages.isEmpty()) {
                sb.append("Scale bars were not drawn for: ");
                for (int i = 0; i < uncalibratedImages.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(uncalibratedImages.get(i));
                }
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    private static final class SelectionLookup {
        final double value;
        final double groupMean;
        final int rank;
        final String suggestedSubject;
        final String chosenSubject;

        SelectionLookup(double value, double groupMean, int rank,
                String suggestedSubject, String chosenSubject) {
            this.value = value;
            this.groupMean = groupMean;
            this.rank = rank;
            this.suggestedSubject = suggestedSubject;
            this.chosenSubject = chosenSubject == null ? "not available" : chosenSubject;
        }
    }
}
