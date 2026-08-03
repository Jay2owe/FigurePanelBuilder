/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.figure;

import fpb.record.OutputTree;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Writes an assembled figure and its per-panel raster output tree. */
public final class FigureWriter {

    public static final String ROOT_DIR = OutputTree.ROOT_DIR;
    public static final String PANELS_DIR = OutputTree.PANELS_DIR;

    public interface CancelCheck {
        boolean isCancelled();
    }

    public static final CancelCheck NEVER_CANCELLED = new CancelCheck() {
        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    public FigureOutput writeFigure(File outputRoot, String figureName,
            List<PanelRecord> records, PanelConfig config) throws IOException {
        return writeFigure(outputRoot, figureName, records, config,
                true, true, true, NEVER_CANCELLED);
    }

    public FigureOutput writeFigure(File outputRoot, String figureName,
            List<PanelRecord> records, PanelConfig config, boolean writePng,
            boolean writeTiff, boolean writeIndividualPanels,
            CancelCheck cancelCheck) throws IOException {
        if (outputRoot == null) throw new IllegalArgumentException("outputRoot is required");
        if (config == null) throw new IllegalArgumentException("config is required");
        List<PanelRecord> safeRecords = safeRecords(records);
        if (safeRecords.isEmpty()) {
            throw new IllegalArgumentException("At least one panel record is required.");
        }
        CancelCheck cancel = cancelCheck == null ? NEVER_CANCELLED : cancelCheck;

        OutputTree.Tree tree = OutputTree.prepare(outputRoot, figureName);
        File figureDir = tree.figureDirectory();
        File panelsDir = tree.panelsDirectory();
        List<File> files = new ArrayList<File>();
        files.add(tree.readme());

        if (writeIndividualPanels) {
            checkCancelled(cancel);
            files.addAll(writePanelCopies(panelsDir, safeRecords, config));
        }

        PanelWriter.WriteReport report = new PanelWriter.WriteReport();
        File png = writePng ? new File(figureDir, "figure.png") : null;
        File tif = writeTiff ? new File(figureDir, "figure.tif") : null;
        if (writePng || writeTiff) {
            checkCancelled(cancel);
            BufferedImage figure = PanelWriter.renderOverviewPanel(
                    safeRecords, config, report);
            figure = scaleImage(figure, config.exportScale());
            if (writePng) {
                checkCancelled(cancel);
                PanelWriter.writePngAtomically(figure, png, config.outputDpi());
                files.add(png);
            }
            if (writeTiff) {
                checkCancelled(cancel);
                PanelWriter.writeTiffAtomically(figure, tif, config.outputDpi());
                files.add(tif);
            }
        }
        return new FigureOutput(figureDir, panelsDir, png, tif, report, files);
    }

    public static PanelConfig scaledForExport(PanelConfig config) {
        if (config == null) throw new IllegalArgumentException("config is required");
        int scale = Math.max(1, config.exportScale());
        if (scale == 1) return config;
        return config.toBuilder()
                .cellSizePx(config.cellSizePx() * scale)
                .marginPx(config.marginPx() * scale)
                .innerColGapPx(config.innerColGapPx() * scale)
                .groupGapPx(config.groupGapPx() * scale)
                .rowGapPx(config.rowGapPx() * scale)
                .groupFontSizePx(config.groupFontSizePx() * scale)
                .channelFontSizePx(config.channelFontSizePx() * scale)
                .labelFontSizePx(config.labelFontSizePx() * scale)
                .scaleBarThicknessPx(config.scaleBarThicknessPx() * scale)
                .exportScale(1)
                .build();
    }

    private static List<File> writePanelCopies(File panelsDir, List<PanelRecord> records,
            PanelConfig config) throws IOException {
        List<File> written = new ArrayList<File>();
        LinkedHashSet<String> usedNames = new LinkedHashSet<String>();
        for (PanelRecord record : records) {
            File source = record.preferredImageFile(config.annotateIndividualPanels());
            if (source == null || !source.isFile()) continue;
            BufferedImage image = ImageIO.read(source);
            if (image == null) continue;
            String base = PanelWriter.safeFileBase(record.group(), "Group")
                    + "_" + PanelWriter.safeFileBase(record.subject(), "Subject");
            if (!record.section().isEmpty()) {
                base += "_" + PanelWriter.safeFileBase(record.section(), "Section");
            }
            base += "_" + PanelWriter.safeFileBase(record.outputName(), "Panel");
            String name = uniqueFileName(usedNames, base, ".png");
            File out = new File(panelsDir, name);
            PanelWriter.writePngAtomically(image, out, config.outputDpi());
            written.add(out);
        }
        return written;
    }

    private static String uniqueFileName(LinkedHashSet<String> usedNames,
            String base, String extension) {
        String first = base + extension;
        if (usedNames.add(first)) return first;
        for (int i = 2; i < 10000; i++) {
            String candidate = base + "_" + i + extension;
            if (usedNames.add(candidate)) return candidate;
        }
        throw new IllegalStateException("Could not choose a unique panel filename.");
    }

    private static List<PanelRecord> safeRecords(List<PanelRecord> records) {
        List<PanelRecord> out = new ArrayList<PanelRecord>();
        if (records == null) return out;
        for (PanelRecord record : records) {
            if (record != null) out.add(record);
        }
        return out;
    }

    private static void checkCancelled(CancelCheck cancelCheck) throws IOException {
        if (cancelCheck != null && cancelCheck.isCancelled()) {
            throw new IOException("Export cancelled.");
        }
    }

    private static BufferedImage scaleImage(BufferedImage image, int scale) {
        int safeScale = Math.max(1, scale);
        if (safeScale == 1 || image == null) return image;
        BufferedImage out = new BufferedImage(image.getWidth() * safeScale,
                image.getHeight() * safeScale, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            PanelWriter.applyQualityHints(g);
            g.drawImage(image, 0, 0, out.getWidth(), out.getHeight(), null);
        } finally {
            g.dispose();
        }
        return out;
    }

    public static final class FigureOutput {
        private final File figureDirectory;
        private final File panelsDirectory;
        private final File figurePng;
        private final File figureTif;
        private final PanelWriter.WriteReport report;
        private final List<File> writtenFiles;

        private FigureOutput(File figureDirectory, File panelsDirectory,
                File figurePng, File figureTif, PanelWriter.WriteReport report,
                List<File> writtenFiles) {
            this.figureDirectory = figureDirectory;
            this.panelsDirectory = panelsDirectory;
            this.figurePng = figurePng;
            this.figureTif = figureTif;
            this.report = report;
            this.writtenFiles = Collections.unmodifiableList(
                    new ArrayList<File>(writtenFiles));
        }

        public File figureDirectory() {
            return figureDirectory;
        }

        public File panelsDirectory() {
            return panelsDirectory;
        }

        public File figurePng() {
            return figurePng;
        }

        public File figureTif() {
            return figureTif;
        }

        public List<String> uncalibratedImages() {
            if (report == null) return Collections.emptyList();
            return report.uncalibratedImages();
        }

        public List<File> writtenFiles() {
            return writtenFiles;
        }
    }
}
