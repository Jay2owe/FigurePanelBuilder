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
import fpb.figure.ImageOrientation;
import fpb.figure.PanelConfig;
import fpb.figure.PanelRecord;
import fpb.figure.PanelWriter;
import fpb.figure.QuantificationPlot;
import fpb.meta.MetadataRow;
import fpb.meta.MetadataTableIO;
import fpb.io.ImageLoader;
import fpb.io.ImageSource;
import fpb.record.ManifestWriter;
import fpb.record.MethodsWriter;
import fpb.record.OutputTree;
import fpb.record.QuantificationWriter;
import fpb.record.SelectionWriter;
import fpb.render.ClipReport;
import fpb.render.DisplayRange;
import fpb.render.FPBRenderer;
import fpb.svg.SvgWriter;
import fpb.stats.GroupQuantification;
import fpb.ui.chooser.RowImage;
import fpb.util.CancellationCheck;
import fpb.util.IoUtils;
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
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.imageio.ImageIO;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Final wizard step: export settings, progress and completion summary. */
public final class Step5Export implements WizardStep, AutoCloseable {

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
            new FitComboBox<String>(new String[] { "1x", "2x", "3x", "4x" });
    private final JCheckBox png = new JCheckBox("PNG", true);
    private final JCheckBox tiff = new JCheckBox("TIFF", true);
    private final JCheckBox svg = new JCheckBox("SVG", true);
    private final JCheckBox panels = new JCheckBox("individual panels", true);
    private final JCheckBox records = new JCheckBox(
            "quantification, manifest and methods", true);
    private final JCheckBox allProjectPng = new JCheckBox(
            "all project images as full-resolution PNGs", false);
    private final JCheckBox allProjectTiffStacks = new JCheckBox(
            "all project images as channel TIFF stacks", false);
    private final JButton build = new JButton("Build figure");
    private final JButton cancel = new JButton("Cancel export");
    private final JButton openFolder = new JButton("Open folder");
    private final JProgressBar progress = new JProgressBar();
    private final JTextArea summary = new JTextArea(8, 64);
    private final Timer progressTimer;
    private volatile ExportRun activeRun;
    private volatile File completedFolder;
    private Runnable busyStateListener = new Runnable() {
        @Override
        public void run() {
            // no-op until hosted by the wizard
        }
    };

    public Step5Export(FPBWizard.Context context) {
        this.context = context;
        progressTimer = new Timer(1000, e -> refreshProgressText());
        progressTimer.setRepeats(true);
        panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        panel.add(form(), BorderLayout.NORTH);
        summary.setEditable(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        panel.add(new JScrollPane(summary), BorderLayout.CENTER);
        panel.add(progressRow(), BorderLayout.SOUTH);
        build.addActionListener(e -> startExport());
        panels.setToolTipText("Save full-resolution per-panel PNGs and one calibrated channel-only TIFF hyperstack per chosen image in the selected formats.");
        allProjectPng.setToolTipText("Export every logical project image, including every container series, as lossless full-resolution channel and merge PNGs.");
        allProjectTiffStacks.setToolTipText("Export one calibrated, display-adjusted RGB TIFF stack per logical project image, with one slice per selected channel.");
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

    @Override
    public void onPrimaryAction() {
        startExport();
    }

    @Override
    public boolean primaryActionClosesWizard() {
        return false;
    }

    @Override
    public void close() {
        cancelExport();
    }

    void setBusyStateListener(Runnable listener) {
        busyStateListener = listener == null ? new Runnable() {
            @Override
            public void run() {
                // no-op
            }
        } : listener;
    }

    boolean isExportRunning() {
        return activeRun != null;
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

        JPanel entireProject = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        entireProject.add(new JLabel("Entire project"));
        entireProject.add(allProjectPng);
        entireProject.add(allProjectTiffStacks);
        form.add(entireProject);
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

    void startExport() {
        if (activeRun != null) return;
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
        progress.setValue(0);
        progress.setString("Starting export");
        summary.setText("Export is running. Keep this window open; the final "
                + "folder is published only after every selected format has "
                + "finished successfully.");
        build.setEnabled(false);
        cancel.setEnabled(true);
        openFolder.setEnabled(false);
        final ExportRun run = new ExportRun();
        run.startedAtNanos = System.nanoTime();
        run.progressMessage = "Starting export";
        SwingWorker<ExportResult, Void> newWorker = new SwingWorker<ExportResult, Void>() {
            @Override
            protected ExportResult doInBackground() throws Exception {
                run.thread = Thread.currentThread();
                try {
                    return export(context, settings, new FigureWriter.CancelCheck() {
                        @Override
                        public boolean isCancelled() {
                            return run.cancelRequested;
                        }
                    }, new ProgressListener() {
                        @Override
                        public void update(final String message, int completed, int total) {
                            run.progressMessage = message;
                            run.completed = Math.max(0, completed);
                            run.total = Math.max(0, total);
                            setProgress(total <= 0 ? 0
                                    : Math.min(100, (int) Math.round(
                                            completed * 100.0 / total)));
                            SwingUtilities.invokeLater(new Runnable() {
                                @Override
                                public void run() {
                                    if (activeRun == run) refreshProgressText();
                                }
                            });
                        }
                    });
                } finally {
                    run.thread = null;
                    run.backgroundFinished = true;
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            finishRun(run);
                        }
                    });
                }
            }

            @Override
            protected void done() {
                try {
                    ExportResult result = get();
                    completedFolder = result.figureDirectory();
                    progress.setValue(100);
                    progress.setString("Export complete");
                    summary.setText(result.summaryText());
                    openFolder.setEnabled(true);
                    context.recordedMetadataCsv = result.metadataCsv();
                    recordMacroCall(settings);
                } catch (CancellationException cancelled) {
                    progress.setString("Export stopped");
                    summary.setText("Export cancelled.");
                } catch (Exception failure) {
                    progress.setString("Export stopped");
                    summary.setText(rootMessage(failure));
                } finally {
                    run.doneProcessed = true;
                    finishRun(run);
                }
            }
        };
        activeRun = run;
        progressTimer.start();
        newWorker.addPropertyChangeListener(event -> {
            if ("progress".equals(event.getPropertyName())) {
                if (activeRun == run) {
                    progress.setValue(((Integer) event.getNewValue()).intValue());
                }
            }
        });
        busyStateListener.run();
        newWorker.execute();
    }

    private void cancelExport() {
        ExportRun run = activeRun;
        if (run == null) return;
        run.cancelRequested = true;
        run.progressMessage = "Stopping export safely";
        refreshProgressText();
        Thread thread = run.thread;
        if (thread != null) thread.interrupt();
    }

    private void finishRun(ExportRun run) {
        if (activeRun != run || !exportRunMayFinish(
                run.backgroundFinished, run.doneProcessed)) return;
        activeRun = null;
        progressTimer.stop();
        build.setEnabled(true);
        cancel.setEnabled(false);
        busyStateListener.run();
    }

    static boolean exportRunMayFinish(boolean backgroundFinished,
            boolean doneProcessed) {
        return backgroundFinished && doneProcessed;
    }

    private void refreshProgressText() {
        ExportRun run = activeRun;
        if (run == null) return;
        long elapsedSeconds = Math.max(0L,
                (System.nanoTime() - run.startedAtNanos) / 1_000_000_000L);
        progress.setString(progressText(run.progressMessage, run.completed,
                run.total, elapsedSeconds));
    }

    static String progressText(String message, int completed, int total,
            long elapsedSeconds) {
        String phase = message == null || message.trim().isEmpty()
                ? "Exporting" : message.trim();
        if (total <= 0) return phase;
        int safeCompleted = Math.max(0, Math.min(completed, total));
        int percent = (int) Math.round(safeCompleted * 100.0 / total);
        if (safeCompleted >= total) return phase + " - 100%";
        if (safeCompleted == 0 || elapsedSeconds < 2L) {
            return phase + " - " + percent + "% - estimating time";
        }
        long remaining = Math.max(1L, Math.round(elapsedSeconds
                * (total - safeCompleted) / (double) safeCompleted));
        return phase + " - " + percent + "% - about "
                + formatDuration(remaining) + " remaining";
    }

    private static String formatDuration(long seconds) {
        long safe = Math.max(1L, seconds);
        if (safe < 60L) return safe + "s";
        if (safe < 3600L) {
            long minutes = safe / 60L;
            long remainder = safe % 60L;
            return remainder == 0L ? minutes + "m"
                    : minutes + "m " + remainder + "s";
        }
        long hours = safe / 3600L;
        long minutes = (safe % 3600L) / 60L;
        return minutes == 0L ? hours + "h" : hours + "h " + minutes + "m";
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
                panels.isSelected(), records.isSelected(),
                allProjectPng.isSelected(), allProjectTiffStacks.isSelected());
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
        if (!settings.writePng && !settings.writeTiff && !settings.writeSvg) {
            throw new IllegalArgumentException("At least one assembled figure format is required.");
        }
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
                .build();
        context.panelConfig = config;
        progress.update("Checking export folder", 0, 1);
        OutputTree.verifyPublishAccess(settings.outputRoot);
        boolean writeQuantification = settings.writeRecords
                && !context.quickGridRequested;
        boolean writeAllProjectImages = settings.writeAllProjectPng
                || settings.writeAllProjectTiffStacks;
        int allProjectSteps = writeAllProjectImages
                ? projectImageCount(context) : 0;
        int writingSteps = 2 + (settings.writeSvg ? 1 : 0)
                + (settings.writeRecords ? 3 : 0)
                + (writeQuantification ? 1 : 0);
        int preparationSteps = selectedImageCount(context);
        int total = preparationSteps + writingSteps + allProjectSteps;
        progress.update(preparationMessage(0, preparationSteps), 0, total);
        List<PanelRecord> panelRecords = ensurePanelRecords(context, config, cancel,
                progress, total);
        if (panelRecords.isEmpty()) throw new IOException("No panels are ready to export.");
        try {
            int step = preparationSteps;
            File finalFigure = OutputTree.nextFigureDirectory(settings.outputRoot,
                    settings.figureName);
            File stagingRoot = Files.createTempDirectory(settings.outputRoot.toPath(),
                    ".fpb-export-").toFile();
            boolean committed = false;
            boolean preserveCompletedStaging = false;
            Throwable primaryFailure = null;
            try {
            progress.update("Writing figure files", ++step, total);
            FigureWriter.FigureOutput figure = new FigureWriter().writeFigure(
                    stagingRoot, settings.figureName, panelRecords, config,
                    settings.writePng, settings.writeTiff,
                    settings.writeIndividualPanels, cancel);
            List<File> written = new ArrayList<File>(figure.writtenFiles());
            PanelWriter.WriteReport svgReport = new PanelWriter.WriteReport();
            File supporting = new File(figure.figureDirectory(),
                    OutputTree.SUPPORTING_DIR);
            Map<PanelRecord, File> finalPanelFiles = relocatePanelFiles(
                    figure.panelFiles(), figure.figureDirectory(), finalFigure);

            if (settings.writeSvg) {
                checkCancelled(cancel);
                progress.update("Writing SVG", ++step, total);
                File svg = new File(figure.figureDirectory(), "figure.svg");
                SvgWriter.writeOverviewSvg(svg, panelRecords, config, cancel, svgReport);
                written.add(svg);
            }

            if (settings.writeRecords) {
                checkCancelled(cancel);
                progress.update("Writing manifest", ++step, total);
                File manifest = new File(supporting, "manifest.csv");
                new ManifestWriter().write(manifest,
                        manifestRows(context, panelRecords, finalPanelFiles));
                written.add(manifest);

                checkCancelled(cancel);
                progress.update("Writing selection records", ++step, total);
                File selection = new File(supporting, "selection.csv");
                new SelectionWriter().write(selection, selectionRecords(context),
                        statisticName(context), chosenSubjects(context));
                written.add(selection);

                if (writeQuantification) {
                    checkCancelled(cancel);
                    progress.update("Writing group quantification", ++step, total);
                    GroupQuantification quantification = GroupQuantification.from(
                            context.chooserData.subjectStats());
                    File quantificationCsv = new File(supporting,
                            "group_quantification.csv");
                    new QuantificationWriter().write(quantificationCsv,
                            quantification, chosenSectionIndices(context));
                    written.add(quantificationCsv);
                    File quantificationPng = new File(supporting,
                            "group_quantification.png");
                    BufferedImage quantificationImage = QuantificationPlot.renderAll(
                            quantification, chosenSectionIndices(context), 600, 320);
                    PanelWriter.writePngAtomically(quantificationImage,
                            quantificationPng, settings.dpi);
                    written.add(quantificationPng);
                }

                checkCancelled(cancel);
                progress.update("Writing methods text", ++step, total);
                File methods = new File(supporting, "methods.txt");
                new MethodsWriter().write(methods,
                        methodsRecord(context, panelRecords,
                                settings,
                                figure.hasDrawnScaleBar()
                                || svgReport.hasDrawnScaleBar(),
                                !figure.scaleBarsThatDidNotFit().isEmpty()
                                || svgReport.hasScaleBarsThatDidNotFit()));
                written.add(methods);
            }

            checkCancelled(cancel);
            progress.update("Writing replay metadata", ++step, total);
            File metadata = new File(supporting, "metadata.csv");
            MetadataTableIO.exportCsv(context.metadataTable, metadata);
            written.add(metadata);

            if (allProjectSteps > 0) {
                checkCancelled(cancel);
                File allProjectDirectory = new File(supporting,
                        OutputTree.ALL_PROJECT_IMAGES_DIR);
                List<File> projectFiles = writeAllProjectImages(context,
                        panelRecords, allProjectDirectory, settings, cancel,
                        progress, step, total);
                written.addAll(projectFiles);
                step += allProjectSteps;
            }

            checkCancelled(cancel);
            try {
                OutputTree.commitStagedFigure(figure.figureDirectory(), finalFigure);
            } catch (OutputTree.PublishException publishFailure) {
                preserveCompletedStaging = true;
                throw publishFailure;
            }
            committed = true;
            List<File> finalWritten = relocateFiles(written,
                    figure.figureDirectory(), finalFigure);
            File finalMetadata = new File(new File(finalFigure,
                    OutputTree.SUPPORTING_DIR), "metadata.csv");
            context.recordedMetadataCsv = finalMetadata;
            progress.update("Export complete", total, total);
            LinkedHashSet<String> uncalibrated = new LinkedHashSet<String>(
                    figure.uncalibratedImages());
            uncalibrated.addAll(svgReport.uncalibratedImages());
            LinkedHashSet<String> barsThatDidNotFit = new LinkedHashSet<String>(
                    figure.scaleBarsThatDidNotFit());
            barsThatDidNotFit.addAll(svgReport.scaleBarsThatDidNotFit());
            return new ExportResult(finalFigure, finalWritten,
                    new ArrayList<String>(uncalibrated),
                    new ArrayList<String>(barsThatDidNotFit), finalMetadata);
            } catch (IOException | RuntimeException | Error failure) {
                primaryFailure = failure;
                throw failure;
            } finally {
                try {
                    if (!preserveCompletedStaging) OutputTree.deleteTree(stagingRoot);
                } catch (IOException cleanupFailure) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure);
                    } else if (!committed) {
                        throw cleanupFailure;
                    }
                }
            }
        } finally {
            deleteTemporaryPanels(panelRecords);
            context.layoutPanelRecords.clear();
        }
    }

    private static List<PanelRecord> ensurePanelRecords(FPBWizard.Context context,
            PanelConfig config, FigureWriter.CancelCheck cancel,
            final ProgressListener progress, final int totalSteps) throws IOException {
        List<PanelRecord> records = new ArrayList<PanelRecord>();
        final List<PanelRecord> createdRecords = Collections.synchronizedList(
                new ArrayList<PanelRecord>());
        boolean complete = false;
        final List<FPBRenderer.ChannelRequest> channels =
                new ArrayList<FPBRenderer.ChannelRequest>(context.layoutChannelRequests);
        final ImageLoader.ZMode zMode = ImageLoader.ZMode.fromString(context.zHandling);
        final File dir = previewDirectory();
        List<PanelPreparation> preparations = panelPreparations(context);
        List<ParallelJob<List<PanelRecord>>> jobs =
                new ArrayList<ParallelJob<List<PanelRecord>>>(preparations.size());
        for (final PanelPreparation preparation : preparations) {
            jobs.add(new ParallelJob<List<PanelRecord>>() {
                @Override
                public List<PanelRecord> run(CancellationCheck taskCancel)
                        throws Exception {
                    List<PanelRecord> prepared = preparePanelRecords(preparation,
                            channels, zMode, dir, config, taskCancel);
                    createdRecords.addAll(prepared);
                    return prepared;
                }
            });
        }
        try {
            List<List<PanelRecord>> prepared = runOrderedJobs(jobs,
                    fullResolutionWorkerCount(jobs.size()), cancel,
                    new ParallelCompletion<List<PanelRecord>>() {
                        @Override
                        public void completed(List<PanelRecord> result,
                                int completed, int total) {
                            progress.update(preparationMessage(completed, total),
                                    completed, totalSteps);
                        }
                    });
            for (List<PanelRecord> imageRecords : prepared) {
                records.addAll(imageRecords);
            }
            context.layoutPanelRecords = records;
            complete = true;
            return records;
        } finally {
            if (!complete) {
                synchronized (createdRecords) {
                    deleteTemporaryPanels(new ArrayList<PanelRecord>(createdRecords));
                }
            }
        }
    }

    private static List<PanelPreparation> panelPreparations(FPBWizard.Context context) {
        List<PanelPreparation> preparations = new ArrayList<PanelPreparation>();
        if (context.selectedRowsByGroup == null) return preparations;
        for (Map.Entry<String, RowImage.SubjectRow> entry
                : context.selectedRowsByGroup.entrySet()) {
            RowImage.SubjectRow row = entry.getValue();
            if (row == null) continue;
            for (Integer imageIndex : row.imageIndices()) {
                if (imageIndex == null) continue;
                int index = imageIndex.intValue();
                fpb.io.PlaneCache.ImagePlanes sourcePlanes =
                        context.chooserData.planes().image(index);
                MetadataRow metadata = context.chooserData.table().rows().get(index);
                String imageId = context.chooserData.table().csvFileName(metadata);
                CalibrationCheck.Result calibration = CalibrationCheck.resolve(
                        sourcePlanes.calibration(), sourcePlanes.openedWithBioFormats(),
                        context.calibrationOverrides.get(imageId));
                preparations.add(new PanelPreparation(sourcePlanes.source(), metadata,
                        imageId, calibration, orientationFor(context, imageId)));
            }
        }
        return preparations;
    }

    private static List<PanelRecord> preparePanelRecords(
            PanelPreparation preparation, List<FPBRenderer.ChannelRequest> channels,
            ImageLoader.ZMode zMode, File dir, PanelConfig config,
            final CancellationCheck cancel) throws IOException {
        List<PanelRecord> records = new ArrayList<PanelRecord>();
        boolean complete = false;
        try {
            RenderedImageSet rendered = renderFullResolution(preparation,
                    channels, zMode, cancel);
            for (int i = 0; i < channels.size(); i++) {
                checkCancelled(cancel);
                FPBRenderer.ChannelRequest channel = channels.get(i);
                BufferedImage image = rendered.channelImages.get(i);
                File file = writeTempPanel(dir, preparation.metadata.group,
                        preparation.metadata.subject, channel.name(), image,
                        config.outputDpi());
                records.add(record(file, preparation.source.file(), preparation.metadata,
                        preparation.imageId, channel.name(), channel.name(),
                        channel.channelIndex(), image, preparation.calibration));
            }
            checkCancelled(cancel);
            BufferedImage merge = rendered.mergeImage;
            File mergeFile = writeTempPanel(dir, preparation.metadata.group,
                    preparation.metadata.subject, "Merge", merge, config.outputDpi());
            records.add(record(mergeFile, preparation.source.file(), preparation.metadata,
                    preparation.imageId, "Merge", "Merge", -1, merge,
                    preparation.calibration));
            checkCancelled(cancel);
            complete = true;
            return records;
        } finally {
            if (!complete) deleteTemporaryPanels(records);
        }
    }

    private static RenderedImageSet renderFullResolution(
            PanelPreparation preparation,
            List<FPBRenderer.ChannelRequest> channels, ImageLoader.ZMode zMode,
            final CancellationCheck cancel) throws IOException {
        checkCancelled(cancel);
        ImageLoader.LoadResult full;
        try {
            full = new ImageLoader().loadFullResolution(preparation.source, zMode,
                    new ImageLoader.CancelCheck() {
                        @Override
                        public boolean isCancelled() {
                            return cancel != null && cancel.isCancelled();
                        }
                    });
        } catch (ImageLoader.LoadCancelledException cancelled) {
            throw new IOException("Export cancelled.", cancelled);
        }
        checkCancelled(cancel);
        int width = full.planeCache().plane(0, 0).width();
        int height = full.planeCache().plane(0, 0).height();
        FPBRenderer.PanelRender render = new FPBRenderer().renderPanel(
                full.planeCache(), full.histogramCache(), 0,
                channels, width, height, cancel);
        List<BufferedImage> orientedChannels =
                new ArrayList<BufferedImage>(render.channelImages().size());
        for (BufferedImage image : render.channelImages()) {
            checkCancelled(cancel);
            orientedChannels.add(preparation.orientation.apply(image));
        }
        checkCancelled(cancel);
        return new RenderedImageSet(orientedChannels,
                preparation.orientation.apply(render.mergeImage()));
    }

    private static int selectedImageCount(FPBWizard.Context context) {
        if (context == null || context.selectedRowsByGroup == null) return 0;
        int count = 0;
        for (RowImage.SubjectRow row : context.selectedRowsByGroup.values()) {
            if (row != null && row.imageIndices() != null) {
                count += row.imageIndices().size();
            }
        }
        return count;
    }

    private static int projectImageCount(FPBWizard.Context context) {
        return context == null || context.chooserData == null
                || context.chooserData.table() == null ? 0
                : context.chooserData.table().rows().size();
    }

    private static List<File> writeAllProjectImages(
            FPBWizard.Context context, List<PanelRecord> selectedPanels,
            File outputDirectory, final Settings settings,
            final FigureWriter.CancelCheck cancel,
            final ProgressListener progress, final int startingStep,
            final int totalSteps) throws IOException {
        IoUtils.mustMkdirs(outputDirectory);
        final List<FPBRenderer.ChannelRequest> channels =
                new ArrayList<FPBRenderer.ChannelRequest>(
                        context.layoutChannelRequests);
        final ImageLoader.ZMode zMode = ImageLoader.ZMode.fromString(
                context.zHandling);
        final Map<String, List<PanelRecord>> selectedByImageId =
                recordsByImageId(selectedPanels);
        List<ProjectExportPreparation> preparations =
                projectExportPreparations(context);
        List<ParallelJob<List<File>>> jobs =
                new ArrayList<ParallelJob<List<File>>>(preparations.size());
        for (final ProjectExportPreparation project : preparations) {
            jobs.add(new ParallelJob<List<File>>() {
                @Override
                public List<File> run(CancellationCheck taskCancel)
                        throws Exception {
                    RenderedImageSet rendered = renderedFromSelectedPanels(
                            selectedByImageId.get(project.preparation.imageId),
                            channels);
                    if (rendered == null) {
                        rendered = renderFullResolution(project.preparation,
                                channels, zMode, taskCancel);
                    }
                    return writeProjectImageFiles(outputDirectory, project,
                            rendered, channels, settings, taskCancel);
                }
            });
        }
        List<List<File>> jobFiles = runOrderedJobs(jobs,
                fullResolutionWorkerCount(jobs.size()), cancel,
                new ParallelCompletion<List<File>>() {
                    @Override
                    public void completed(List<File> result, int completed,
                            int total) {
                        progress.update("Exporting all project images ("
                                + completed + "/" + total + ")",
                                startingStep + completed, totalSteps);
                    }
                });
        List<File> written = new ArrayList<File>();
        for (List<File> files : jobFiles) written.addAll(files);
        return written;
    }

    private static List<ProjectExportPreparation> projectExportPreparations(
            FPBWizard.Context context) {
        List<ProjectExportPreparation> out =
                new ArrayList<ProjectExportPreparation>();
        LinkedHashSet<String> usedBases = new LinkedHashSet<String>();
        for (int i = 0; i < context.chooserData.table().rows().size(); i++) {
            MetadataRow metadata = context.chooserData.table().rows().get(i);
            fpb.io.PlaneCache.ImagePlanes sourcePlanes =
                    context.chooserData.planes().image(i);
            String imageId = context.chooserData.table().csvFileName(metadata);
            CalibrationCheck.Result calibration = CalibrationCheck.resolve(
                    sourcePlanes.calibration(), sourcePlanes.openedWithBioFormats(),
                    context.calibrationOverrides.get(imageId));
            PanelPreparation preparation = new PanelPreparation(
                    sourcePlanes.source(), metadata, imageId, calibration,
                    orientationFor(context, imageId));
            String base = safe(metadata.group) + "_" + safe(metadata.subject);
            if (metadata.section != null && !metadata.section.trim().isEmpty()) {
                base += "_" + safe(metadata.section);
            }
            String unique = base;
            int suffix = 2;
            while (!usedBases.add(unique)) unique = base + "_" + suffix++;
            out.add(new ProjectExportPreparation(preparation, unique));
        }
        return out;
    }

    private static Map<String, List<PanelRecord>> recordsByImageId(
            List<PanelRecord> records) {
        LinkedHashMap<String, List<PanelRecord>> byId =
                new LinkedHashMap<String, List<PanelRecord>>();
        if (records == null) return byId;
        for (PanelRecord record : records) {
            if (record == null) continue;
            List<PanelRecord> sameImage = byId.get(record.imageId());
            if (sameImage == null) {
                sameImage = new ArrayList<PanelRecord>();
                byId.put(record.imageId(), sameImage);
            }
            sameImage.add(record);
        }
        return byId;
    }

    private static RenderedImageSet renderedFromSelectedPanels(
            List<PanelRecord> records,
            List<FPBRenderer.ChannelRequest> channels) throws IOException {
        if (records == null || records.isEmpty()) return null;
        LinkedHashMap<Integer, PanelRecord> byChannel =
                new LinkedHashMap<Integer, PanelRecord>();
        PanelRecord merge = null;
        for (PanelRecord record : records) {
            if (record.channelIndex() < 0) merge = record;
            else byChannel.put(Integer.valueOf(record.channelIndex()), record);
        }
        List<BufferedImage> channelImages = new ArrayList<BufferedImage>();
        for (FPBRenderer.ChannelRequest channel : channels) {
            PanelRecord record = byChannel.get(Integer.valueOf(
                    channel.channelIndex()));
            BufferedImage image = record == null || record.imageFile() == null
                    ? null : ImageIO.read(record.imageFile());
            if (image == null) return null;
            channelImages.add(image);
        }
        BufferedImage mergeImage = merge == null || merge.imageFile() == null
                ? null : ImageIO.read(merge.imageFile());
        return mergeImage == null ? null
                : new RenderedImageSet(channelImages, mergeImage);
    }

    private static List<File> writeProjectImageFiles(File outputDirectory,
            ProjectExportPreparation project, RenderedImageSet rendered,
            List<FPBRenderer.ChannelRequest> channels, Settings settings,
            CancellationCheck cancel) throws IOException {
        List<File> written = new ArrayList<File>();
        if (settings.writeAllProjectPng) {
            String mergeBase = project.outputBase + "_Merge";
            LinkedHashSet<String> usedPngBases = new LinkedHashSet<String>();
            usedPngBases.add(mergeBase.toLowerCase(java.util.Locale.ROOT));
            for (int i = 0; i < channels.size(); i++) {
                checkCancelled(cancel);
                FPBRenderer.ChannelRequest channel = channels.get(i);
                String preferredBase = project.outputBase + "_"
                        + safe(channel.name());
                String pngBase = uniqueFileBase(usedPngBases, preferredBase,
                        "_C" + (channel.channelIndex() + 1));
                File png = new File(outputDirectory, pngBase + ".png");
                PanelWriter.writePngAtomically(rendered.channelImages.get(i),
                        png, 0);
                written.add(png);
            }
            checkCancelled(cancel);
            File mergePng = new File(outputDirectory, mergeBase + ".png");
            PanelWriter.writePngAtomically(rendered.mergeImage, mergePng, 0);
            written.add(mergePng);
        }
        if (settings.writeAllProjectTiffStacks) {
            checkCancelled(cancel);
            List<String> labels = new ArrayList<String>();
            for (FPBRenderer.ChannelRequest channel : channels) {
                labels.add(channel.name());
            }
            File stack = new File(outputDirectory,
                    project.outputBase + "_channels.tif");
            PanelWriter.writeTiffStackAtomically(rendered.channelImages,
                    labels, stack,
                    project.preparation.calibration.pixelWidthUm(),
                    project.preparation.calibration.pixelHeightUm());
            written.add(stack);
        }
        checkCancelled(cancel);
        return written;
    }

    private static String uniqueFileBase(LinkedHashSet<String> used,
            String preferred, String collisionSuffix) {
        String candidate = preferred;
        if (used.add(candidate.toLowerCase(java.util.Locale.ROOT))) {
            return candidate;
        }
        candidate = preferred + collisionSuffix;
        int duplicate = 2;
        while (!used.add(candidate.toLowerCase(java.util.Locale.ROOT))) {
            candidate = preferred + collisionSuffix + "_" + duplicate++;
        }
        return candidate;
    }

    private static String preparationMessage(int completed, int total) {
        if (total <= 0) return "Preparing full-resolution panels";
        return "Preparing full-resolution images (" + completed + "/" + total + ")";
    }

    private static ImageOrientation orientationFor(FPBWizard.Context context,
            String imageId) {
        String key = imageId == null ? ""
                : imageId.trim().replace('\\', '/');
        if (context != null && context.imageOrientations != null) {
            ImageOrientation orientation = context.imageOrientations.get(key);
            if (orientation != null) return orientation;
        }
        return context == null || context.panelConfig == null
                ? ImageOrientation.IDENTITY
                : context.panelConfig.imageOrientation(key);
    }

    static int fullResolutionWorkerCount(int jobCount) {
        return fullResolutionWorkerCount(jobCount,
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory());
    }

    static int fullResolutionWorkerCount(int jobCount, int availableProcessors,
            long maximumHeapBytes) {
        if (jobCount <= 0) return 1;
        int cpuLimit = Math.max(1, availableProcessors - 1);
        long memoryUnit = 512L * 1024L * 1024L;
        int memoryLimit = (int) Math.max(1L,
                Math.min(4L, maximumHeapBytes / memoryUnit));
        return Math.max(1, Math.min(jobCount,
                Math.min(4, Math.min(cpuLimit, memoryLimit))));
    }

    interface ParallelJob<T> {
        T run(CancellationCheck cancelCheck) throws Exception;
    }

    interface ParallelCompletion<T> {
        void completed(T result, int completed, int total);
    }

    static <T> List<T> runOrderedJobs(List<ParallelJob<T>> jobs, int workerCount,
            final CancellationCheck externalCancel,
            ParallelCompletion<T> completionListener) throws IOException {
        if (jobs == null || jobs.isEmpty()) return new ArrayList<T>();
        final AtomicBoolean abort = new AtomicBoolean(false);
        final CancellationCheck combinedCancel = new CancellationCheck() {
            @Override
            public boolean isCancelled() {
                return abort.get()
                        || (externalCancel != null && externalCancel.isCancelled())
                        || Thread.currentThread().isInterrupted();
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.max(1, Math.min(workerCount, jobs.size())),
                new ExportThreadFactory());
        CompletionService<IndexedJobResult<T>> completedJobs =
                new ExecutorCompletionService<IndexedJobResult<T>>(executor);
        List<Future<IndexedJobResult<T>>> futures =
                new ArrayList<Future<IndexedJobResult<T>>>(jobs.size());
        @SuppressWarnings("unchecked")
        T[] ordered = (T[]) new Object[jobs.size()];
        boolean succeeded = false;
        try {
            for (int i = 0; i < jobs.size(); i++) {
                final int index = i;
                final ParallelJob<T> job = jobs.get(i);
                futures.add(completedJobs.submit(
                        new java.util.concurrent.Callable<IndexedJobResult<T>>() {
                            @Override
                            public IndexedJobResult<T> call() throws Exception {
                                checkCancelled(combinedCancel);
                                T result = job.run(combinedCancel);
                                checkCancelled(combinedCancel);
                                return new IndexedJobResult<T>(index, result);
                            }
                        }));
            }
            int finished = 0;
            while (finished < jobs.size()) {
                checkCancelled(combinedCancel);
                IndexedJobResult<T> result = completedJobs.take().get();
                ordered[result.index] = result.value;
                finished++;
                if (completionListener != null) {
                    completionListener.completed(result.value, finished, jobs.size());
                }
            }
            succeeded = true;
            List<T> values = new ArrayList<T>(ordered.length);
            Collections.addAll(values, ordered);
            return values;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Export cancelled.", interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IOException("Could not prepare full-resolution panels.", cause);
        } finally {
            if (!succeeded) {
                abort.set(true);
                for (Future<IndexedJobResult<T>> future : futures) {
                    future.cancel(true);
                }
                executor.shutdownNow();
            } else {
                executor.shutdown();
            }
            awaitTermination(executor);
        }
    }

    private static void awaitTermination(ExecutorService executor) {
        boolean restoreInterrupt = Thread.interrupted();
        try {
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(200L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    restoreInterrupt = true;
                    executor.shutdownNow();
                }
            }
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    private static PanelRecord record(File file, File sourceFile, MetadataRow metadata,
            String imageId,
            String outputName, String channelName, int channelIndex,
            BufferedImage image, CalibrationCheck.Result calibration) {
        CalibrationCheck.Result safe = calibration == null
                ? CalibrationCheck.none() : calibration;
        return new PanelRecord(file, sourceFile, metadata.group, metadata.subject,
                metadata.section, imageId, outputName, channelName, channelIndex,
                image.getWidth(), image.getHeight(), safe.pixelWidthUm(),
                safe.pixelHeightUm(), safe.source());
    }

    private static File writeTempPanel(File dir, String group, String subject,
            String output, BufferedImage image, int dpi) throws IOException {
        File file = File.createTempFile(safe(group) + "_" + safe(subject)
                + "_" + safe(output) + "_", ".png", dir);
        file.deleteOnExit();
        boolean written = false;
        try {
            PanelWriter.writePngAtomically(image, file, dpi);
            written = true;
            return file;
        } finally {
            if (!written) Files.deleteIfExists(file.toPath());
        }
    }

    private static List<ManifestWriter.Row> manifestRows(FPBWizard.Context context,
            List<PanelRecord> panels, Map<PanelRecord, File> panelFiles) {
        List<ManifestWriter.Row> rows = new ArrayList<ManifestWriter.Row>();
        Map<Integer, DisplayRange> ranges = rangesByChannel(context);
        Map<String, Integer> imageIndexBySource = imageIndexBySourceId(context);
        for (PanelRecord panel : panels) {
            DisplayRange range = panel.channelIndex() < 0 ? null
                    : ranges.get(Integer.valueOf(panel.channelIndex()));
            if (panel.channelIndex() >= 0 && range == null) {
                throw new IllegalStateException("A locked display range is required for manifest.csv.");
            }
            ClipReport.ChannelClip clip = clipFor(context, panel,
                    imageIndexBySource, range);
            SelectionLookup selection = selectionFor(context, panel);
            rows.add(new ManifestWriter.Row(panel, lutFor(context, panel),
                    panelFiles == null ? null : panelFiles.get(panel),
                    range, clip, panel.channelIndex() < 0
                            ? "component channel ranges" : rangeSource(context),
                    statisticName(context),
                    selection.value, selection.groupMean, selection.rank,
                    selection.suggestedSubject, selection.chosenSubject,
                    selectionMethod(context), grouping(context), zMode(context)));
        }
        return rows;
    }

    private static ClipReport.ChannelClip clipFor(FPBWizard.Context context,
            PanelRecord panel, Map<String, Integer> imageIndexBySource,
            DisplayRange range) {
        if (panel.channelIndex() < 0 || context.chooserData == null) return null;
        Integer imageIndex = imageIndexBySource.get(panel.imageId());
        if (imageIndex == null) return null;
        return new ClipReport.ChannelClip(panel.channelIndex(), panel.channelName(),
                context.chooserData.histograms().histogram(imageIndex.intValue(),
                        panel.channelIndex()).clippedLowPercent(range.min()),
                context.chooserData.histograms().histogram(imageIndex.intValue(),
                        panel.channelIndex()).clippedHighPercent(range.max()));
    }

    private static SelectionLookup selectionFor(FPBWizard.Context context,
            PanelRecord panel) {
        if (context.quickGridRequested) {
            return new SelectionLookup(Double.NaN, Double.NaN, 0, "none", "none");
        }
        if (context.chooserData != null) {
            fpb.stats.SelectionRecord channelIndependent = null;
            for (fpb.stats.SelectionRecord record
                    : context.chooserData.selectionRecords()) {
                if (record.group().equals(panel.group())
                        && record.subject().equals(panel.subject())) {
                    if (record.channelIndex() == fpb.stats.Statistic.CHANNEL_INDEPENDENT) {
                        channelIndependent = record;
                    }
                    if (record.channelIndex() != panel.channelIndex()) continue;
                    return new SelectionLookup(record.value(), record.groupMean(),
                            suggestionRank(context, panel.group(), panel.subject()),
                            suggestedSubject(context, panel.group()),
                            chosenSubjects(context).get(panel.group()));
                }
            }
            if (channelIndependent != null) {
                return new SelectionLookup(channelIndependent.value(),
                        channelIndependent.groupMean(),
                        suggestionRank(context, panel.group(), panel.subject()),
                        suggestedSubject(context, panel.group()),
                        chosenSubjects(context).get(panel.group()));
            }
        }
        return new SelectionLookup(Double.NaN, Double.NaN,
                suggestionRank(context, panel.group(), panel.subject()),
                suggestedSubject(context, panel.group()),
                chosenSubjects(context).get(panel.group()));
    }

    private static int suggestionRank(FPBWizard.Context context, String group,
            String subject) {
        if (context == null || context.chooserData == null) return 0;
        fpb.stats.Suggestion.Result suggestion =
                context.chooserData.suggestions().get(group);
        return suggestion == null ? 0 : suggestion.rankOf(subject);
    }

    private static List<fpb.stats.SelectionRecord> selectionRecords(
            FPBWizard.Context context) {
        if (context.quickGridRequested || context.chooserData == null) {
            return Collections.emptyList();
        }
        return context.chooserData.selectionRecords();
    }

    private static MethodsWriter.Record methodsRecord(FPBWizard.Context context,
            List<PanelRecord> panels, Settings settings, boolean scaleBarDrawn,
            boolean scaleBarDidNotFit) {
        PanelConfig config = context.panelConfig;
        boolean overviewWritten = settings.writePng || settings.writeTiff
                || settings.writeSvg;
        boolean scaleBarRequested = config != null && config.scaleBarEnabled()
                && ((config.annotateOverviewPanel() && overviewWritten)
                || (config.annotateIndividualPanels()
                && settings.writeIndividualPanels));
        return MethodsWriter.Record.builder()
                .panels(panels)
                .selectionRecords(selectionRecords(context))
                .channelRanges(methodsRanges(context))
                .chosenSubjects(chosenSubjects(context))
                .statisticName(statisticName(context))
                .selectionMethod(selectionMethod(context))
                .grouping(context.quickGridRequested ? "none" : "subject")
                .zMode(zMode(context))
                .scaleBarEnabled(config != null && config.scaleBarEnabled())
                .scaleBarRequested(scaleBarRequested)
                .scaleBarRendered(scaleBarDrawn)
                .scaleBarDidNotFit(scaleBarDidNotFit)
                .scaleBarUm(context.panelConfig == null ? null
                        : Double.valueOf(context.panelConfig.scaleBarLengthUm()))
                .build();
    }

    private static String zMode(FPBWizard.Context context) {
        return ImageLoader.ZMode.fromString(context.zHandling).optionName();
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

    private static Map<String, Integer> chosenSectionIndices(
            FPBWizard.Context context) {
        LinkedHashMap<String, Integer> chosen =
                new LinkedHashMap<String, Integer>();
        if (context == null || context.selectedRowsByGroup == null) return chosen;
        for (Map.Entry<String, RowImage.SubjectRow> entry
                : context.selectedRowsByGroup.entrySet()) {
            chosen.put(entry.getKey(), Integer.valueOf(entry.getValue().imageIndex()));
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

    private static Map<String, Integer> imageIndexBySourceId(FPBWizard.Context context) {
        LinkedHashMap<String, Integer> map = new LinkedHashMap<String, Integer>();
        if (context.chooserData == null) return map;
        for (int i = 0; i < context.chooserData.planes().imageCount(); i++) {
            MetadataRow row = context.chooserData.table().rows().get(i);
            map.put(context.chooserData.table().csvFileName(row), Integer.valueOf(i));
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

    private static void deleteTemporaryPanels(List<PanelRecord> panels) {
        if (panels == null) return;
        for (PanelRecord panel : panels) {
            if (panel == null || panel.imageFile() == null) continue;
            try {
                Files.deleteIfExists(panel.imageFile().toPath());
            } catch (IOException ignored) {
                panel.imageFile().deleteOnExit();
            }
        }
    }

    private static Map<PanelRecord, File> relocatePanelFiles(
            Map<PanelRecord, File> files, File sourceRoot, File targetRoot) {
        LinkedHashMap<PanelRecord, File> relocated =
                new LinkedHashMap<PanelRecord, File>();
        if (files == null) return relocated;
        for (Map.Entry<PanelRecord, File> entry : files.entrySet()) {
            relocated.put(entry.getKey(), relocate(entry.getValue(), sourceRoot,
                    targetRoot));
        }
        return relocated;
    }

    private static List<File> relocateFiles(List<File> files, File sourceRoot,
            File targetRoot) {
        List<File> relocated = new ArrayList<File>();
        for (File file : files) relocated.add(relocate(file, sourceRoot, targetRoot));
        return relocated;
    }

    private static File relocate(File file, File sourceRoot, File targetRoot) {
        if (file == null) return null;
        java.nio.file.Path relative = sourceRoot.toPath().toAbsolutePath()
                .relativize(file.toPath().toAbsolutePath());
        return targetRoot.toPath().resolve(relative).toFile();
    }

    private static void checkCancelled(CancellationCheck cancelCheck)
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

    private static final class ExportRun {
        volatile boolean cancelRequested;
        volatile boolean backgroundFinished;
        volatile Thread thread;
        volatile long startedAtNanos;
        volatile String progressMessage = "Starting export";
        volatile int completed;
        volatile int total;
        boolean doneProcessed;
    }

    private static final class PanelPreparation {
        final ImageSource source;
        final MetadataRow metadata;
        final String imageId;
        final CalibrationCheck.Result calibration;
        final ImageOrientation orientation;

        PanelPreparation(ImageSource source, MetadataRow metadata, String imageId,
                CalibrationCheck.Result calibration,
                ImageOrientation orientation) {
            this.source = source;
            this.metadata = metadata;
            this.imageId = imageId;
            this.orientation = orientation == null
                    ? ImageOrientation.IDENTITY : orientation;
            this.calibration = this.orientation.orientCalibration(calibration);
        }
    }

    private static final class ProjectExportPreparation {
        final PanelPreparation preparation;
        final String outputBase;

        ProjectExportPreparation(PanelPreparation preparation,
                String outputBase) {
            this.preparation = preparation;
            this.outputBase = outputBase;
        }
    }

    private static final class RenderedImageSet {
        final List<BufferedImage> channelImages;
        final BufferedImage mergeImage;

        RenderedImageSet(List<BufferedImage> channelImages,
                BufferedImage mergeImage) {
            this.channelImages = new ArrayList<BufferedImage>(channelImages);
            this.mergeImage = mergeImage;
        }
    }

    private static final class IndexedJobResult<T> {
        final int index;
        final T value;

        IndexedJobResult(int index, T value) {
            this.index = index;
            this.value = value;
        }
    }

    private static final class ExportThreadFactory implements ThreadFactory {
        private static final AtomicInteger THREAD_NUMBER = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "FPB full-resolution export-" + THREAD_NUMBER.getAndIncrement());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY,
                    Thread.NORM_PRIORITY - 1));
            return thread;
        }
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
        private final boolean writeAllProjectPng;
        private final boolean writeAllProjectTiffStacks;

        public Settings(File outputRoot, String figureName, int dpi, int exportScale,
                boolean writePng, boolean writeTiff, boolean writeSvg,
                boolean writeIndividualPanels, boolean writeRecords) {
            this(outputRoot, figureName, dpi, exportScale, writePng, writeTiff,
                    writeSvg, writeIndividualPanels, writeRecords, false, false);
        }

        public Settings(File outputRoot, String figureName, int dpi, int exportScale,
                boolean writePng, boolean writeTiff, boolean writeSvg,
                boolean writeIndividualPanels, boolean writeRecords,
                boolean writeAllProjectPng,
                boolean writeAllProjectTiffStacks) {
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
            this.writeAllProjectPng = writeAllProjectPng;
            this.writeAllProjectTiffStacks = writeAllProjectTiffStacks;
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

        public boolean writeAllProjectPng() {
            return writeAllProjectPng;
        }

        public boolean writeAllProjectTiffStacks() {
            return writeAllProjectTiffStacks;
        }
    }

    public static final class ExportResult {
        private final File figureDirectory;
        private final List<File> writtenFiles;
        private final List<String> uncalibratedImages;
        private final List<String> scaleBarsThatDidNotFit;
        private final File metadataCsv;

        private ExportResult(File figureDirectory, List<File> writtenFiles,
                List<String> uncalibratedImages, List<String> scaleBarsThatDidNotFit,
                File metadataCsv) {
            this.figureDirectory = figureDirectory;
            this.writtenFiles = Collections.unmodifiableList(
                    new ArrayList<File>(writtenFiles));
            this.uncalibratedImages = Collections.unmodifiableList(
                    new ArrayList<String>(uncalibratedImages));
            this.scaleBarsThatDidNotFit = Collections.unmodifiableList(
                    new ArrayList<String>(scaleBarsThatDidNotFit));
            this.metadataCsv = metadataCsv;
        }

        public File figureDirectory() {
            return figureDirectory;
        }

        public List<File> writtenFiles() {
            return writtenFiles;
        }

        public File metadataCsv() {
            return metadataCsv;
        }

        public List<String> uncalibratedImages() {
            return uncalibratedImages;
        }

        public List<String> scaleBarsThatDidNotFit() {
            return scaleBarsThatDidNotFit;
        }

        public String summaryText() {
            StringBuilder sb = new StringBuilder();
            sb.append("Wrote ").append(writtenFiles.size())
                    .append(" files to ")
                    .append(figureDirectory.getAbsolutePath()).append('\n');
            int allProjectFileCount = 0;
            for (File file : writtenFiles) {
                if (isAllProjectImageFile(file)) {
                    allProjectFileCount++;
                    continue;
                }
                sb.append(file.getName()).append('\n');
            }
            if (allProjectFileCount > 0) {
                sb.append("All project images: ").append(allProjectFileCount)
                        .append(" files in ")
                        .append(OutputTree.SUPPORTING_DIR).append(File.separator)
                        .append(OutputTree.ALL_PROJECT_IMAGES_DIR).append('\n');
            }
            if (!uncalibratedImages.isEmpty()) {
                sb.append("Scale bars were not drawn for: ");
                for (int i = 0; i < uncalibratedImages.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(uncalibratedImages.get(i));
                }
                sb.append('\n');
            }
            if (!scaleBarsThatDidNotFit.isEmpty()) {
                sb.append("Requested scale bars did not fit for: ");
                for (int i = 0; i < scaleBarsThatDidNotFit.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(scaleBarsThatDidNotFit.get(i));
                }
                sb.append('\n');
            }
            return sb.toString();
        }

        private static boolean isAllProjectImageFile(File file) {
            File parent = file == null ? null : file.getParentFile();
            return parent != null && OutputTree.ALL_PROJECT_IMAGES_DIR
                    .equals(parent.getName());
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
