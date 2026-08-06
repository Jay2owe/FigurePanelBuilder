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
import fpb.util.CancellationCheck;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Writes an assembled figure and its per-panel raster output tree. */
public final class FigureWriter {

    public static final String ROOT_DIR = OutputTree.ROOT_DIR;
    public static final String SUPPORTING_DIR = OutputTree.SUPPORTING_DIR;
    public static final String PANELS_DIR = OutputTree.PANELS_DIR;

    public interface CancelCheck extends CancellationCheck {}

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
        LinkedHashMap<PanelRecord, File> panelFiles =
                new LinkedHashMap<PanelRecord, File>();
        PanelWriter.WriteReport report = new PanelWriter.WriteReport();

        if (writeIndividualPanels) {
            checkCancelled(cancel);
            panelFiles.putAll(writePanelCopies(panelsDir, safeRecords, config,
                    files, report, writePng, writeTiff, cancel));
        }

        File png = writePng ? new File(figureDir, "figure.png") : null;
        File tif = writeTiff ? new File(figureDir, "figure.tif") : null;
        if (writePng || writeTiff) {
            checkCancelled(cancel);
            BufferedImage figure = PanelWriter.renderOverviewPanel(
                    safeRecords, config, report, config.exportScale(), cancel);
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
        return new FigureOutput(figureDir, panelsDir, png, tif, report, files,
                panelFiles);
    }

    private static Map<PanelRecord, File> writePanelCopies(File panelsDir,
            List<PanelRecord> records, PanelConfig config, List<File> written,
            PanelWriter.WriteReport report, boolean writePng, boolean writeTiff,
            CancelCheck cancel) throws IOException {
        LinkedHashMap<PanelRecord, File> outputs =
                new LinkedHashMap<PanelRecord, File>();
        LinkedHashSet<String> usedBases = new LinkedHashSet<String>();
        if (writePng) {
            for (PanelRecord record : records) {
                checkCancelled(cancel);
                File source = record == null ? null : record.imageFile();
                if (source == null || !source.isFile()) continue;
                String base = imageBase(record) + "_"
                        + PanelWriter.safeFileBase(record.outputName(), "Panel");
                String uniqueBase = uniqueBaseName(usedBases, base);
                File png = new File(panelsDir, uniqueBase + ".png");
                checkCancelled(cancel);
                if (config.annotateIndividualPanels()) {
                    BufferedImage image = panelImage(record, config, report, cancel);
                    if (image == null) continue;
                    PanelWriter.writePngAtomically(image, png, 0);
                } else {
                    PanelWriter.copyPngAtomically(source, png);
                }
                checkCancelled(cancel);
                written.add(png);
                outputs.put(record, png);
                if (config.annotateIndividualPanels()) {
                    record.setAnnotatedImageFile(png);
                }
            }
        }
        if (writeTiff) {
            LinkedHashMap<String, List<PanelRecord>> recordsByImage =
                    channelRecordsByImage(records);
            for (List<PanelRecord> channelRecords : recordsByImage.values()) {
                checkCancelled(cancel);
                List<BufferedImage> channelImages = new ArrayList<BufferedImage>();
                List<String> channelLabels = new ArrayList<String>();
                List<PanelRecord> writtenRecords = new ArrayList<PanelRecord>();
                for (PanelRecord record : channelRecords) {
                    BufferedImage image = panelImage(record, config, report, cancel);
                    if (image == null) continue;
                    channelImages.add(image);
                    channelLabels.add(record.outputName());
                    writtenRecords.add(record);
                }
                if (channelImages.isEmpty()) continue;
                PanelRecord representative = writtenRecords.get(0);
                String uniqueBase = uniqueBaseName(usedBases,
                        imageBase(representative) + "_channels");
                File tif = new File(panelsDir, uniqueBase + ".tif");
                checkCancelled(cancel);
                PanelWriter.writeTiffStackAtomically(channelImages, channelLabels,
                        tif, representative.pixelWidthUm(),
                        representative.pixelHeightUm());
                checkCancelled(cancel);
                written.add(tif);
                if (!writePng) {
                    for (PanelRecord record : writtenRecords) {
                        outputs.put(record, tif);
                    }
                }
            }
        }
        return outputs;
    }

    private static BufferedImage panelImage(PanelRecord record,
            PanelConfig config, PanelWriter.WriteReport report,
            CancelCheck cancel) throws IOException {
        checkCancelled(cancel);
        File source = record == null ? null : record.imageFile();
        if (source == null || !source.isFile()) return null;
        BufferedImage image = ImageIO.read(source);
        if (image == null) return null;
        checkCancelled(cancel);
        if (config.annotateIndividualPanels()) {
            image = PanelWriter.renderAnnotatedPanel(image, record, config, report);
        }
        return image;
    }

    private static LinkedHashMap<String, List<PanelRecord>> channelRecordsByImage(
            List<PanelRecord> records) {
        LinkedHashMap<String, List<PanelRecord>> grouped =
                new LinkedHashMap<String, List<PanelRecord>>();
        for (PanelRecord record : records) {
            if (record == null || record.channelIndex() < 0) continue;
            List<PanelRecord> imageRecords = grouped.get(record.imageKey());
            if (imageRecords == null) {
                imageRecords = new ArrayList<PanelRecord>();
                grouped.put(record.imageKey(), imageRecords);
            }
            imageRecords.add(record);
        }
        return grouped;
    }

    private static String imageBase(PanelRecord record) {
        String base = PanelWriter.safeFileBase(record.group(), "Group")
                + "_" + PanelWriter.safeFileBase(record.subject(), "Subject");
        if (!record.section().isEmpty()) {
            base += "_" + PanelWriter.safeFileBase(record.section(), "Section");
        }
        return base;
    }

    private static String uniqueBaseName(LinkedHashSet<String> usedBases,
            String base) {
        if (usedBases.add(base)) return base;
        for (int i = 2; i < 10000; i++) {
            String candidate = base + "_" + i;
            if (usedBases.add(candidate)) return candidate;
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

    public static final class FigureOutput {
        private final File figureDirectory;
        private final File panelsDirectory;
        private final File figurePng;
        private final File figureTif;
        private final PanelWriter.WriteReport report;
        private final List<File> writtenFiles;
        private final Map<PanelRecord, File> panelFiles;

        private FigureOutput(File figureDirectory, File panelsDirectory,
                File figurePng, File figureTif, PanelWriter.WriteReport report,
                List<File> writtenFiles, Map<PanelRecord, File> panelFiles) {
            this.figureDirectory = figureDirectory;
            this.panelsDirectory = panelsDirectory;
            this.figurePng = figurePng;
            this.figureTif = figureTif;
            this.report = report;
            this.writtenFiles = Collections.unmodifiableList(
                    new ArrayList<File>(writtenFiles));
            this.panelFiles = Collections.unmodifiableMap(
                    new LinkedHashMap<PanelRecord, File>(panelFiles));
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

        public boolean hasDrawnScaleBar() {
            return report != null && report.hasDrawnScaleBar();
        }

        public List<String> scaleBarsThatDidNotFit() {
            if (report == null) return Collections.emptyList();
            return report.scaleBarsThatDidNotFit();
        }

        public List<File> writtenFiles() {
            return writtenFiles;
        }

        /** Actual per-panel files written for each source record. */
        public Map<PanelRecord, File> panelFiles() {
            return panelFiles;
        }

        /** Re-bases all paths after an atomic staging-directory commit. */
        public FigureOutput relocated(File finalFigureDirectory) {
            if (finalFigureDirectory == null) {
                throw new IllegalArgumentException("finalFigureDirectory is required");
            }
            List<File> relocatedFiles = new ArrayList<File>();
            for (File file : writtenFiles) {
                relocatedFiles.add(relocate(file, finalFigureDirectory));
            }
            LinkedHashMap<PanelRecord, File> relocatedPanels =
                    new LinkedHashMap<PanelRecord, File>();
            for (Map.Entry<PanelRecord, File> entry : panelFiles.entrySet()) {
                relocatedPanels.put(entry.getKey(),
                        relocate(entry.getValue(), finalFigureDirectory));
            }
            return new FigureOutput(finalFigureDirectory,
                    new File(new File(finalFigureDirectory, SUPPORTING_DIR),
                            PANELS_DIR),
                    relocate(figurePng, finalFigureDirectory),
                    relocate(figureTif, finalFigureDirectory), report,
                    relocatedFiles, relocatedPanels);
        }

        private File relocate(File file, File finalFigureDirectory) {
            if (file == null) return null;
            java.nio.file.Path relative = figureDirectory.toPath().toAbsolutePath()
                    .relativize(file.toPath().toAbsolutePath());
            return finalFigureDirectory.toPath().resolve(relative).toFile();
        }
    }
}
