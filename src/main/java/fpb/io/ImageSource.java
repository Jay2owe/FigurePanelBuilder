/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.io;

import java.io.File;

/**
 * Durable identity for one image input. A normal image is identified by its
 * file; a Bio-Formats container image is identified by the container plus its
 * zero-based series index.
 */
public final class ImageSource {

    public static final int NO_SERIES = -1;

    private final File file;
    private final int seriesIndex;
    private final int seriesCount;
    private final String seriesName;

    private ImageSource(File file, int seriesIndex, int seriesCount,
            String seriesName) {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        if (seriesIndex < NO_SERIES) {
            throw new IllegalArgumentException("seriesIndex is invalid");
        }
        if (seriesIndex >= 0 && seriesCount <= seriesIndex) {
            throw new IllegalArgumentException("seriesCount must include seriesIndex");
        }
        this.file = file.getAbsoluteFile();
        this.seriesIndex = seriesIndex;
        this.seriesCount = seriesIndex < 0 ? 0 : seriesCount;
        this.seriesName = clean(seriesName);
    }

    public static ImageSource file(File file) {
        return new ImageSource(file, NO_SERIES, 0, "");
    }

    public static ImageSource series(File container, int seriesIndex,
            int seriesCount, String seriesName) {
        return new ImageSource(container, seriesIndex, seriesCount, seriesName);
    }

    public File file() {
        return file;
    }

    public boolean isSeries() {
        return seriesIndex >= 0;
    }

    public int seriesIndex() {
        return seriesIndex;
    }

    public int seriesCount() {
        return seriesCount;
    }

    public String seriesName() {
        return seriesName;
    }

    public String seriesLabel() {
        return seriesName.isEmpty() ? "Series " + (seriesIndex + 1) : seriesName;
    }

    /** Stable process-local key used wherever multiple series share one file. */
    public String key() {
        String path = file.toURI().normalize().getPath();
        return path + "\u001f" + seriesIndex;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ImageSource
                && key().equals(((ImageSource) other).key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }

    @Override
    public String toString() {
        return isSeries() ? file + " [series " + (seriesIndex + 1) + ": "
                + seriesLabel() + "]" : file.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
