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
import java.nio.file.Files;
import java.util.Locale;

/** Creates the figure export folder layout and its README file. */
public final class OutputTree {

    public static final String ROOT_DIR = "Figure Panels";
    public static final String PANELS_DIR = "panels";
    public static final String README_FILE = "README.txt";

    private OutputTree() {}

    public static Tree prepare(File outputRoot, String figureName) throws IOException {
        if (outputRoot == null) throw new IllegalArgumentException("outputRoot is required");
        File root = new File(outputRoot, ROOT_DIR);
        IoUtils.mustMkdirs(root);
        File figureDir = uniqueDirectory(root, safeFileBase(figureName, "Figure"));
        IoUtils.mustMkdirs(figureDir);
        File panelsDir = new File(figureDir, PANELS_DIR);
        IoUtils.mustMkdirs(panelsDir);
        File readme = new File(figureDir, README_FILE);
        writeReadme(readme);
        return new Tree(root, figureDir, panelsDir, readme);
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
                out.println("panels/ contains the exported per-panel image files.");
                out.println("figure.png, figure.tif and figure.svg are assembled figure files when written.");
                out.println("manifest.csv lists one row for each exported panel and the rendering values used.");
                out.println("selection.csv lists one row for each subject and channel used for selection records.");
                out.println("methods.txt contains checklist fields and suggested methods text.");
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

    public static final class Tree {
        private final File rootDirectory;
        private final File figureDirectory;
        private final File panelsDirectory;
        private final File readme;

        private Tree(File rootDirectory, File figureDirectory,
                File panelsDirectory, File readme) {
            this.rootDirectory = rootDirectory;
            this.figureDirectory = figureDirectory;
            this.panelsDirectory = panelsDirectory;
            this.readme = readme;
        }

        public File rootDirectory() {
            return rootDirectory;
        }

        public File figureDirectory() {
            return figureDirectory;
        }

        public File panelsDirectory() {
            return panelsDirectory;
        }

        public File readme() {
            return readme;
        }
    }
}
