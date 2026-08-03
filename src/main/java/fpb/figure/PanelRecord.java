/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.figure;

import java.io.File;

/** One rendered panel with metadata needed for layout and calibrated annotations. */
public final class PanelRecord {
    private final File imageFile;
    private File annotatedImageFile;
    private final String group;
    private final String subject;
    private final String section;
    private final String imageId;
    private final String outputName;
    private final String channelName;
    private final int channelIndex;
    private final int widthPx;
    private final int heightPx;
    private final double pixelWidthUm;
    private final double pixelHeightUm;
    private final CalibrationCheck.CalibrationSource calibrationSource;

    public PanelRecord(File imageFile, String group, String subject, String section,
            String outputName, String channelName, int channelIndex, int widthPx,
            int heightPx, double pixelWidthUm, double pixelHeightUm,
            CalibrationCheck.CalibrationSource calibrationSource) {
        this(imageFile, group, subject, section, "", outputName, channelName,
                channelIndex, widthPx, heightPx, pixelWidthUm, pixelHeightUm,
                calibrationSource);
    }

    public PanelRecord(File imageFile, String group, String subject, String section,
            String imageId, String outputName, String channelName, int channelIndex,
            int widthPx, int heightPx, double pixelWidthUm, double pixelHeightUm,
            CalibrationCheck.CalibrationSource calibrationSource) {
        this.imageFile = imageFile == null ? null : imageFile.getAbsoluteFile();
        this.group = clean(group, "Unassigned");
        this.subject = clean(subject, "Unknown");
        this.section = clean(section, "");
        this.imageId = clean(imageId, "");
        this.outputName = clean(outputName, "");
        this.channelName = clean(channelName, this.outputName);
        this.channelIndex = channelIndex;
        this.widthPx = Math.max(1, widthPx);
        this.heightPx = Math.max(1, heightPx);
        this.pixelWidthUm = pixelWidthUm;
        this.pixelHeightUm = pixelHeightUm;
        this.calibrationSource = calibrationSource == null
                ? CalibrationCheck.CalibrationSource.NONE : calibrationSource;
    }

    public File imageFile() {
        return imageFile;
    }

    public File annotatedImageFile() {
        return annotatedImageFile;
    }

    public void setAnnotatedImageFile(File annotatedImageFile) {
        this.annotatedImageFile = annotatedImageFile == null
                ? null : annotatedImageFile.getAbsoluteFile();
    }

    public File preferredImageFile(boolean preferAnnotated) {
        if (preferAnnotated && annotatedImageFile != null && annotatedImageFile.isFile()) {
            return annotatedImageFile;
        }
        return imageFile;
    }

    public String group() {
        return group;
    }

    public String subject() {
        return subject;
    }

    public String section() {
        return section;
    }

    public String imageId() {
        return imageId;
    }

    public String outputName() {
        return outputName;
    }

    public String channelName() {
        return channelName;
    }

    public int channelIndex() {
        return channelIndex;
    }

    public int widthPx() {
        return widthPx;
    }

    public int heightPx() {
        return heightPx;
    }

    public double pixelWidthUm() {
        return pixelWidthUm;
    }

    public double pixelHeightUm() {
        return pixelHeightUm;
    }

    public CalibrationCheck.CalibrationSource calibrationSource() {
        return calibrationSource;
    }

    public CalibrationCheck.Result calibration() {
        if (calibrationSource == CalibrationCheck.CalibrationSource.NONE) {
            return CalibrationCheck.none();
        }
        if (!Double.isFinite(pixelWidthUm) || !Double.isFinite(pixelHeightUm)
                || pixelWidthUm <= 0.0 || pixelHeightUm <= 0.0) {
            return CalibrationCheck.none();
        }
        return new CalibrationCheck.Result(pixelWidthUm, pixelHeightUm,
                calibrationSource);
    }

    public String imageKey() {
        StringBuilder sb = new StringBuilder(group);
        sb.append('|').append(subject);
        if (!section.isEmpty()) sb.append('|').append(section);
        if (!imageId.isEmpty()) sb.append('|').append(imageId);
        return sb.toString();
    }

    public String imageLabel() {
        StringBuilder sb = new StringBuilder(subject);
        if (!section.isEmpty()) sb.append(' ').append(section);
        return sb.toString();
    }

    private static String clean(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.isEmpty()) return trimmed;
        return fallback == null ? "" : fallback;
    }

}
