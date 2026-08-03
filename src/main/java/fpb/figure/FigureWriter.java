/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.figure;

import fpb.util.IoUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Writes an assembled figure and its per-panel raster output tree. */
public final class FigureWriter {

    public static final String ROOT_DIR = "Figure Panels";
    public static final String PANELS_DIR = "panels";

    public FigureOutput writeFigure(File outputRoot, String figureName,
            List<PanelRecord> records, PanelConfig config) throws IOException {
        if (outputRoot == null) throw new IllegalArgumentException("outputRoot is required");
        if (config == null) throw new IllegalArgumentException("config is required");
        List<PanelRecord> safeRecords = safeRecords(records);
        if (safeRecords.isEmpty()) {
            throw new IllegalArgumentException("At least one panel record is required.");
        }

        File figureRoot = new File(outputRoot, ROOT_DIR);
        IoUtils.mustMkdirs(figureRoot);
        File figureDir = uniqueDirectory(figureRoot,
                PanelWriter.safeFileBase(figureName, "Figure"));
        IoUtils.mustMkdirs(figureDir);
        File panelsDir = new File(figureDir, PANELS_DIR);
        IoUtils.mustMkdirs(panelsDir);

        writePanelCopies(panelsDir, safeRecords, config);

        PanelWriter.WriteReport report = new PanelWriter.WriteReport();
        BufferedImage figure = PanelWriter.renderOverviewPanel(
                safeRecords, config, report);
        File png = new File(figureDir, "figure.png");
        File tif = new File(figureDir, "figure.tif");
        PanelWriter.writePngAtomically(figure, png, config.outputDpi());
        PanelWriter.writeTiffAtomically(figure, tif, config.outputDpi());
        return new FigureOutput(figureDir, panelsDir, png, tif, report);
    }

    private static void writePanelCopies(File panelsDir, List<PanelRecord> records,
            PanelConfig config) throws IOException {
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
            PanelWriter.writePngAtomically(image, new File(panelsDir, name),
                    config.outputDpi());
        }
    }

    private static File uniqueDirectory(File parent, String base) {
        File first = new File(parent, base);
        if (!first.exists()) return first;
        for (int i = 2; i < 10000; i++) {
            File candidate = new File(parent, base + "_" + i);
            if (!candidate.exists()) return candidate;
        }
        throw new IllegalStateException("Could not choose a unique figure folder.");
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

    public static final class FigureOutput {
        private final File figureDirectory;
        private final File panelsDirectory;
        private final File figurePng;
        private final File figureTif;
        private final PanelWriter.WriteReport report;

        private FigureOutput(File figureDirectory, File panelsDirectory,
                File figurePng, File figureTif, PanelWriter.WriteReport report) {
            this.figureDirectory = figureDirectory;
            this.panelsDirectory = panelsDirectory;
            this.figurePng = figurePng;
            this.figureTif = figureTif;
            this.report = report;
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
    }
}
