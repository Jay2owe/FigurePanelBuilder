/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.record;

import fpb.util.IoUtils;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Creates the figure export folder layout and its README file. */
public final class OutputTree {

    public static final String ROOT_DIR = "Figure Panels";
    public static final String SUPPORTING_DIR = "Supporting files";
    public static final String PANELS_DIR = "panels";
    public static final String ALL_PROJECT_IMAGES_DIR = "All project images";
    public static final String README_FILE = "README.txt";
    private static final int PUBLISH_ATTEMPTS = 8;
    private static final int DELETE_ATTEMPTS = 5;
    private static final RetryPause SYSTEM_PAUSE = new RetryPause() {
        @Override
        public void pause(long milliseconds) throws InterruptedException {
            Thread.sleep(milliseconds);
        }
    };

    private OutputTree() {}

    public static Tree prepare(File outputRoot, String figureName) throws IOException {
        if (outputRoot == null) throw new IllegalArgumentException("outputRoot is required");
        File root = new File(outputRoot, ROOT_DIR);
        IoUtils.mustMkdirs(root);
        File figureDir = nextFigureDirectory(outputRoot, figureName);
        IoUtils.mustMkdirs(figureDir);
        File supportingDir = new File(figureDir, SUPPORTING_DIR);
        IoUtils.mustMkdirs(supportingDir);
        File panelsDir = new File(supportingDir, PANELS_DIR);
        IoUtils.mustMkdirs(panelsDir);
        File readme = new File(supportingDir, README_FILE);
        writeReadme(readme);
        return new Tree(root, figureDir, supportingDir, panelsDir, readme);
    }

    /** Returns the next non-existing final figure directory without creating it. */
    public static File nextFigureDirectory(File outputRoot, String figureName) {
        if (outputRoot == null) throw new IllegalArgumentException("outputRoot is required");
        File root = new File(outputRoot, ROOT_DIR);
        return uniqueDirectory(root, safeFileBase(figureName, "Figure"));
    }

    /**
     * Verifies the actual final-output directory before expensive image rendering begins.
     * The probe exercises directory creation and rename, which catches read-only folders,
     * invalid destinations and persistent Windows locks early.
     */
    public static void verifyPublishAccess(File outputRoot) throws IOException {
        if (outputRoot == null) throw new IllegalArgumentException("outputRoot is required");
        IoUtils.mustMkdirs(outputRoot);
        File root = new File(outputRoot, ROOT_DIR);
        IoUtils.mustMkdirs(root);
        Path probeRoot = Files.createTempDirectory(outputRoot.toPath(),
                ".fpb-write-check-");
        Path source = probeRoot.resolve(ROOT_DIR).resolve("probe");
        Files.createDirectories(source);
        Path target = root.toPath().resolve(".fpb-publish-check-"
                + probeRoot.getFileName().toString());
        Throwable primaryFailure = null;
        try {
            moveDirectoryWithRetry(source, target, 3, SYSTEM_PAUSE);
        } catch (IOException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                deleteTree(target.toFile());
                deleteTree(probeRoot.toFile());
            } catch (IOException cleanupFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    /** Publishes a completely-written staging directory as one final figure directory. */
    public static void commitStagedFigure(File stagedFigure, File finalFigure)
            throws IOException {
        if (stagedFigure == null || !stagedFigure.isDirectory()) {
            throw new IOException("Staged figure directory does not exist.");
        }
        if (finalFigure == null) throw new IllegalArgumentException("finalFigure is required");
        if (finalFigure.exists()) {
            throw new IOException("Final figure directory already exists: "
                    + finalFigure.getAbsolutePath());
        }
        File parent = finalFigure.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        try {
            moveDirectoryWithRetry(stagedFigure.toPath(), finalFigure.toPath(),
                    PUBLISH_ATTEMPTS, SYSTEM_PAUSE);
        } catch (IOException failure) {
            if (finalFigure.isDirectory() && !stagedFigure.exists()) return;
            PublishException publishFailure = new PublishException(
                    "The figure was completed, but Windows or a cloud-sync service kept its folder "
                    + "in use after " + PUBLISH_ATTEMPTS + " publication attempts. "
                    + "The completed staging export has been kept at "
                    + stagedFigure.getAbsolutePath()
                    + ". Close any Explorer window or program using that folder, then "
                    + "move it to " + finalFigure.getAbsolutePath() + ".",
                    stagedFigure, finalFigure);
            publishFailure.addSuppressed(failure);
            throw publishFailure;
        }
    }

    /** Deletes an export staging tree. Intended only for abandoned temporary output. */
    public static void deleteTree(File target) throws IOException {
        if (target == null || !target.exists()) return;
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        final Path path = target.toPath();
        retryIo(new IoOperation() {
            @Override
            public void run() throws IOException {
                Files.deleteIfExists(path);
            }
        }, DELETE_ATTEMPTS, SYSTEM_PAUSE);
    }

    private static void moveDirectoryWithRetry(final Path source, final Path target,
            int attempts, RetryPause pause) throws IOException {
        retryIo(new IoOperation() {
            @Override
            public void run() throws IOException {
                if (Files.isDirectory(target) && !Files.exists(source)) return;
                if (Files.exists(target)) throw new FileAlreadyExistsException(
                        target.toAbsolutePath().toString());
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(source, target);
                }
            }
        }, attempts, pause);
    }

    static void retryIo(IoOperation operation, int attempts, RetryPause pause)
            throws IOException {
        if (operation == null) throw new IllegalArgumentException("operation is required");
        int safeAttempts = Math.max(1, attempts);
        RetryPause safePause = pause == null ? SYSTEM_PAUSE : pause;
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= safeAttempts; attempt++) {
            try {
                operation.run();
                return;
            } catch (FileAlreadyExistsException | NoSuchFileException permanent) {
                throw permanent;
            } catch (IOException transientFailure) {
                lastFailure = transientFailure;
                if (attempt == safeAttempts) break;
                try {
                    safePause.pause(retryDelayMillis(attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Export cancelled.", interrupted);
                }
            }
        }
        throw lastFailure == null ? new IOException("File operation failed.") : lastFailure;
    }

    private static long retryDelayMillis(int completedAttempt) {
        long delay = 50L << Math.min(5, Math.max(0, completedAttempt - 1));
        return Math.min(1000L, delay);
    }

    public static void writeReadme(File readme) throws IOException {
        if (readme == null) throw new IllegalArgumentException("readme is required");
        File parent = readme.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        File temp = File.createTempFile(tempPrefix(readme), ".tmp",
                parent == null ? new File(".") : parent);
        boolean moved = false;
        try {
            PrintWriter out = fpb.util.CsvSupport.newWriter(temp);
            try {
                out.println("Figure Panel Builder output");
                out.println();
                out.println("panels/ contains full-resolution per-panel PNGs and one calibrated channel-only TIFF hyperstack per selected image in the selected formats.");
                out.println("All project images/ contains optional full-resolution, display-adjusted PNGs and per-image channel TIFF stacks for every logical source image.");
                out.println("../figure.png, ../figure.tif and ../figure.svg are assembled figure files when written.");
                out.println("manifest.csv lists one row for each exported panel and the rendering values used.");
                out.println("selection.csv lists one row for each subject and channel used for selection records.");
                out.println("Guided representative-selection exports include group_quantification.csv and group_quantification.png: one section-level, per-channel z-score comparison across all groups.");
                out.println("Quick Grid does not create group-quantification files.");
                out.println("metadata.csv preserves the exact labels used for macro replay.");
                out.println("methods.txt contains checklist fields and suggested methods text.");
                fpb.util.CsvSupport.requireNoError(out, temp);
            } finally {
                out.close();
            }
            IoUtils.commitReplacingSmallFile(temp.toPath(), readme.toPath());
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temp.toPath());
        }
    }

    private static File uniqueDirectory(File parent, String base) {
        File first = new File(parent, base);
        if (!first.exists()) return first;
        for (int i = 2; i < 10000; i++) {
            File candidate = new File(parent, base + "_" + i);
            if (!candidate.exists()) return candidate;
        }
        throw new IllegalStateException("Could not choose a figure folder.");
    }

    private static String safeFileBase(String value, String fallback) {
        String source = value == null ? "" : value.trim();
        if (source.isEmpty()) source = fallback == null ? "" : fallback.trim();
        if (source.isEmpty()) source = "File";
        String safe = source.replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("_+", "_");
        while (safe.startsWith(".")) safe = safe.substring(1);
        if (safe.isEmpty()) safe = "File";
        return safe.length() > 140 ? safe.substring(0, 140) : safe;
    }

    private static String tempPrefix(File target) {
        String name = target == null ? "readme" : target.getName();
        String clean = name.replaceAll("[^A-Za-z0-9._-]", "_")
                .toLowerCase(Locale.ROOT);
        return clean.length() < 3 ? "tmp" + clean : clean;
    }

    interface IoOperation {
        void run() throws IOException;
    }

    interface RetryPause {
        void pause(long milliseconds) throws InterruptedException;
    }

    /** A complete export that could not be renamed into its final destination. */
    public static final class PublishException extends IOException {
        private static final long serialVersionUID = 1L;
        private final File stagedFigure;
        private final File finalFigure;

        PublishException(String message, File stagedFigure, File finalFigure) {
            super(message);
            this.stagedFigure = stagedFigure;
            this.finalFigure = finalFigure;
        }

        public File stagedFigure() {
            return stagedFigure;
        }

        public File finalFigure() {
            return finalFigure;
        }
    }

    public static final class Tree {
        private final File rootDirectory;
        private final File figureDirectory;
        private final File supportingDirectory;
        private final File panelsDirectory;
        private final File readme;

        private Tree(File rootDirectory, File figureDirectory,
                File supportingDirectory, File panelsDirectory, File readme) {
            this.rootDirectory = rootDirectory;
            this.figureDirectory = figureDirectory;
            this.supportingDirectory = supportingDirectory;
            this.panelsDirectory = panelsDirectory;
            this.readme = readme;
        }

        public File rootDirectory() {
            return rootDirectory;
        }

        public File figureDirectory() {
            return figureDirectory;
        }

        public File supportingDirectory() {
            return supportingDirectory;
        }

        public File panelsDirectory() {
            return panelsDirectory;
        }

        public File readme() {
            return readme;
        }
    }
}
