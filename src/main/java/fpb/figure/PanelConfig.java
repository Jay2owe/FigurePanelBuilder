/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.figure;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** User-facing composition and annotation settings for panel images. */
public final class PanelConfig {

    public enum GroupRowsBy {
        GROUP,
        SUBJECT
    }

    public enum Position {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    public enum LabelMode {
        NONE,
        CHANNEL_NAME,
        IMAGE_NAME,
        GROUP_SUBJECT,
        CUSTOM
    }

    public enum TextOrientation {
        HORIZONTAL,
        ROTATE_LEFT,
        ROTATE_RIGHT
    }

    public enum TextAlignment {
        LEFT,
        CENTER,
        RIGHT
    }

    public enum ExternalLabelKind {
        GROUP,
        COLUMN,
        ROW
    }

    private static final String EXTERNAL_LABEL_SEPARATOR = "\u001f";

    private final boolean createOverviewPanel;
    private final boolean annotateOverviewPanel;
    private final boolean annotateIndividualPanels;
    private final GroupRowsBy groupRowsBy;
    private final List<String> channelOrder;
    private final int cellSizePx;
    private final boolean scaleBarEnabled;
    private final double scaleBarLengthUm;
    private final int scaleBarThicknessPx;
    private final Position scaleBarPosition;
    private final Color annotationColor;
    private final LabelMode labelMode;
    private final String customLabelTemplate;
    private final int labelFontSizePx;
    private final Position labelPosition;
    private final int marginPx;
    private final int innerColGapPx;
    private final int groupGapPx;
    private final int rowGapPx;
    private final int groupFontSizePx;
    private final int channelFontSizePx;
    private final int rowFontSizePx;
    private final TextAlignment groupHeaderAlignment;
    private final TextOrientation channelHeaderOrientation;
    private final TextOrientation rowLabelOrientation;
    private final int channelHeaderGapPx;
    private final int rowLabelGapPx;
    private final boolean groupHeaderVisible;
    private final boolean channelHeaderVisible;
    private final boolean rowLabelVisible;
    private final int outputDpi;
    private final int exportScale;
    private final double labelFracX;
    private final double labelFracY;
    private final double scaleBarFracX;
    private final double scaleBarFracY;
    private final boolean annotationSnapEnabled;
    private final List<List<String>> groupLayoutRows;
    private final Map<String, String> externalLabelOverrides;
    private final Map<String, String> imageOrientations;

    private PanelConfig(Builder b) {
        this.annotateIndividualPanels = b.annotateIndividualPanels;
        this.createOverviewPanel = b.createOverviewPanel || b.annotateIndividualPanels;
        this.annotateOverviewPanel = b.annotateOverviewPanel || b.annotateIndividualPanels;
        this.groupRowsBy = b.groupRowsBy == null ? GroupRowsBy.GROUP : b.groupRowsBy;
        this.channelOrder = Collections.unmodifiableList(new ArrayList<String>(b.channelOrder));
        this.cellSizePx = clamp(b.cellSizePx, 80, 1200);
        this.scaleBarEnabled = b.scaleBarEnabled;
        if (!Double.isFinite(b.scaleBarLengthUm) || b.scaleBarLengthUm <= 0.0) {
            throw new IllegalArgumentException(
                    "scaleBarLengthUm must be finite and positive");
        }
        this.scaleBarLengthUm = b.scaleBarLengthUm;
        this.scaleBarThicknessPx = clamp(b.scaleBarThicknessPx, 1, 30);
        this.scaleBarPosition = b.scaleBarPosition == null
                ? Position.BOTTOM_RIGHT : b.scaleBarPosition;
        this.annotationColor = b.annotationColor == null ? Color.WHITE : b.annotationColor;
        this.labelMode = b.labelMode == null ? LabelMode.CHANNEL_NAME : b.labelMode;
        this.customLabelTemplate = b.customLabelTemplate == null
                ? "" : b.customLabelTemplate.trim();
        this.labelFontSizePx = clamp(b.labelFontSizePx, 8, 96);
        this.labelPosition = b.labelPosition == null ? Position.TOP_LEFT : b.labelPosition;
        this.marginPx = clamp(b.marginPx, 0, 200);
        this.innerColGapPx = clamp(b.innerColGapPx, 0, 200);
        this.groupGapPx = clamp(b.groupGapPx, 0, 400);
        this.rowGapPx = clamp(b.rowGapPx, 0, 400);
        this.groupFontSizePx = clamp(b.groupFontSizePx, 6, 96);
        this.channelFontSizePx = clamp(b.channelFontSizePx, 6, 96);
        this.rowFontSizePx = clamp(b.rowFontSizePx, 6, 96);
        this.groupHeaderAlignment = b.groupHeaderAlignment == null
                ? TextAlignment.LEFT : b.groupHeaderAlignment;
        this.channelHeaderOrientation = b.channelHeaderOrientation == null
                ? TextOrientation.HORIZONTAL : b.channelHeaderOrientation;
        this.rowLabelOrientation = b.rowLabelOrientation == null
                ? TextOrientation.HORIZONTAL : b.rowLabelOrientation;
        this.channelHeaderGapPx = clamp(b.channelHeaderGapPx, 0, 200);
        this.rowLabelGapPx = clamp(b.rowLabelGapPx, 0, 200);
        this.groupHeaderVisible = b.groupHeaderVisible;
        this.channelHeaderVisible = b.channelHeaderVisible;
        this.rowLabelVisible = b.rowLabelVisible;
        this.outputDpi = clamp(b.outputDpi, 72, 2400);
        this.exportScale = clamp(b.exportScale, 1, 4);
        this.labelFracX = clampFrac(b.labelFracX);
        this.labelFracY = clampFrac(b.labelFracY);
        this.scaleBarFracX = clampFrac(b.scaleBarFracX);
        this.scaleBarFracY = clampFrac(b.scaleBarFracY);
        this.annotationSnapEnabled = b.annotationSnapEnabled;
        this.groupLayoutRows = copyRows(b.groupLayoutRows);
        this.externalLabelOverrides = copyOverrides(b.externalLabelOverrides);
        this.imageOrientations = copyImageOrientations(b.imageOrientations);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static PanelConfig disabled(List<String> channelOrder) {
        return builder().createOverviewPanel(false).channelOrder(channelOrder).build();
    }

    public Builder toBuilder() {
        return new Builder()
                .createOverviewPanel(createOverviewPanel)
                .annotateOverviewPanel(annotateOverviewPanel)
                .annotateIndividualPanels(annotateIndividualPanels)
                .groupRowsBy(groupRowsBy)
                .channelOrder(channelOrder)
                .cellSizePx(cellSizePx)
                .scaleBarEnabled(scaleBarEnabled)
                .scaleBarLengthUm(scaleBarLengthUm)
                .scaleBarThicknessPx(scaleBarThicknessPx)
                .scaleBarPosition(scaleBarPosition)
                .annotationColor(annotationColor)
                .labelMode(labelMode)
                .customLabelTemplate(customLabelTemplate)
                .labelFontSizePx(labelFontSizePx)
                .labelPosition(labelPosition)
                .marginPx(marginPx)
                .innerColGapPx(innerColGapPx)
                .groupGapPx(groupGapPx)
                .rowGapPx(rowGapPx)
                .groupFontSizePx(groupFontSizePx)
                .channelFontSizePx(channelFontSizePx)
                .rowFontSizePx(rowFontSizePx)
                .groupHeaderAlignment(groupHeaderAlignment)
                .channelHeaderOrientation(channelHeaderOrientation)
                .rowLabelOrientation(rowLabelOrientation)
                .channelHeaderGapPx(channelHeaderGapPx)
                .rowLabelGapPx(rowLabelGapPx)
                .groupHeaderVisible(groupHeaderVisible)
                .channelHeaderVisible(channelHeaderVisible)
                .rowLabelVisible(rowLabelVisible)
                .outputDpi(outputDpi)
                .exportScale(exportScale)
                .labelFracX(labelFracX)
                .labelFracY(labelFracY)
                .scaleBarFracX(scaleBarFracX)
                .scaleBarFracY(scaleBarFracY)
                .annotationSnapEnabled(annotationSnapEnabled)
                .groupLayoutRows(groupLayoutRows)
                .externalLabelOverrides(externalLabelOverrides)
                .imageOrientations(imageOrientations);
    }

    public boolean createOverviewPanel() {
        return createOverviewPanel;
    }

    public boolean annotateOverviewPanel() {
        return annotateOverviewPanel;
    }

    public boolean annotateIndividualPanels() {
        return annotateIndividualPanels;
    }

    public GroupRowsBy groupRowsBy() {
        return groupRowsBy;
    }

    public List<String> channelOrder() {
        return channelOrder;
    }

    public int cellSizePx() {
        return cellSizePx;
    }

    public boolean scaleBarEnabled() {
        return scaleBarEnabled;
    }

    public double scaleBarLengthUm() {
        return scaleBarLengthUm;
    }

    public int scaleBarThicknessPx() {
        return scaleBarThicknessPx;
    }

    public Position scaleBarPosition() {
        return scaleBarPosition;
    }

    public Color annotationColor() {
        return annotationColor;
    }

    public LabelMode labelMode() {
        return labelMode;
    }

    public String customLabelTemplate() {
        return customLabelTemplate;
    }

    public int labelFontSizePx() {
        return labelFontSizePx;
    }

    public Position labelPosition() {
        return labelPosition;
    }

    public int marginPx() {
        return marginPx;
    }

    public int innerColGapPx() {
        return innerColGapPx;
    }

    public int groupGapPx() {
        return groupGapPx;
    }

    public int rowGapPx() {
        return rowGapPx;
    }

    public int groupFontSizePx() {
        return groupFontSizePx;
    }

    public int channelFontSizePx() {
        return channelFontSizePx;
    }

    public int rowFontSizePx() {
        return rowFontSizePx;
    }

    public TextAlignment groupHeaderAlignment() {
        return groupHeaderAlignment;
    }

    public TextOrientation channelHeaderOrientation() {
        return channelHeaderOrientation;
    }

    public TextOrientation rowLabelOrientation() {
        return rowLabelOrientation;
    }

    public int channelHeaderGapPx() {
        return channelHeaderGapPx;
    }

    public int rowLabelGapPx() {
        return rowLabelGapPx;
    }

    public boolean groupHeaderVisible() {
        return groupHeaderVisible;
    }

    public boolean channelHeaderVisible() {
        return channelHeaderVisible;
    }

    public boolean rowLabelVisible() {
        return rowLabelVisible;
    }

    public int outputDpi() {
        return outputDpi;
    }

    public int exportScale() {
        return exportScale;
    }

    public double labelFracX() {
        return labelFracX;
    }

    public double labelFracY() {
        return labelFracY;
    }

    public boolean hasLabelFraction() {
        return labelFracX >= 0.0 && labelFracY >= 0.0;
    }

    public double scaleBarFracX() {
        return scaleBarFracX;
    }

    public double scaleBarFracY() {
        return scaleBarFracY;
    }

    public boolean hasScaleBarFraction() {
        return scaleBarFracX >= 0.0 && scaleBarFracY >= 0.0;
    }

    public boolean annotationSnapEnabled() {
        return annotationSnapEnabled;
    }

    public List<List<String>> groupLayoutRows() {
        return groupLayoutRows;
    }

    public boolean hasGroupLayoutRows() {
        return !groupLayoutRows.isEmpty();
    }

    public String externalLabelOverride(ExternalLabelKind kind, String key) {
        if (kind == null) return null;
        return externalLabelOverrides.get(externalLabelKey(kind, key));
    }

    public String externalLabelText(ExternalLabelKind kind, String key,
            String fallback) {
        String override = externalLabelOverride(kind, key);
        return override == null ? (fallback == null ? "" : fallback) : override;
    }

    public Map<String, String> externalLabelOverrides() {
        return externalLabelOverrides;
    }

    public ImageOrientation imageOrientation(String imageId) {
        String token = imageOrientations.get(normalizeImageId(imageId));
        return ImageOrientation.fromToken(token);
    }

    /** Stable image-id to orientation-token mapping used by macro replay. */
    public Map<String, String> imageOrientations() {
        return imageOrientations;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampFrac(double value) {
        if (Double.isNaN(value) || value < 0.0) return -1.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static List<List<String>> copyRows(List<List<String>> rows) {
        List<List<String>> out = new ArrayList<List<String>>();
        if (rows != null) {
            for (List<String> input : rows) {
                List<String> row = new ArrayList<String>();
                if (input != null) {
                    for (String value : input) {
                        if (value != null && !value.trim().isEmpty()) {
                            row.add(value.trim());
                        }
                    }
                }
                if (!row.isEmpty()) {
                    out.add(Collections.unmodifiableList(row));
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static Map<String, String> copyOverrides(Map<String, String> input) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (input != null) {
            for (Map.Entry<String, String> entry : input.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                out.put(entry.getKey(), entry.getValue().trim());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, String> copyImageOrientations(
            Map<String, String> input) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (input != null) {
            for (Map.Entry<String, String> entry : input.entrySet()) {
                String imageId = normalizeImageId(entry.getKey());
                if (imageId.isEmpty() || entry.getValue() == null) continue;
                ImageOrientation orientation = ImageOrientation.fromToken(
                        entry.getValue());
                if (!orientation.isIdentity()) {
                    out.put(imageId, orientation.token());
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static String normalizeImageId(String imageId) {
        return imageId == null ? "" : imageId.trim().replace('\\', '/');
    }

    private static String externalLabelKey(ExternalLabelKind kind, String key) {
        return kind.name() + EXTERNAL_LABEL_SEPARATOR
                + (key == null ? "" : key.trim());
    }

    public static final class Builder {
        private boolean createOverviewPanel;
        private boolean annotateOverviewPanel = true;
        private boolean annotateIndividualPanels;
        private GroupRowsBy groupRowsBy = GroupRowsBy.GROUP;
        private List<String> channelOrder = new ArrayList<String>();
        private int cellSizePx = 260;
        private boolean scaleBarEnabled = true;
        private double scaleBarLengthUm = 100.0;
        private int scaleBarThicknessPx = 6;
        private Position scaleBarPosition = Position.BOTTOM_RIGHT;
        private Color annotationColor = Color.WHITE;
        private LabelMode labelMode = LabelMode.CHANNEL_NAME;
        private String customLabelTemplate = "{channel}";
        private int labelFontSizePx = 18;
        private Position labelPosition = Position.TOP_LEFT;
        private int marginPx = 6;
        private int innerColGapPx = 4;
        private int groupGapPx = 12;
        private int rowGapPx = 8;
        private int groupFontSizePx = 15;
        private int channelFontSizePx = 16;
        private int rowFontSizePx = 14;
        private TextAlignment groupHeaderAlignment = TextAlignment.LEFT;
        private TextOrientation channelHeaderOrientation = TextOrientation.HORIZONTAL;
        private TextOrientation rowLabelOrientation = TextOrientation.HORIZONTAL;
        private int channelHeaderGapPx = 4;
        private int rowLabelGapPx = 6;
        private boolean groupHeaderVisible = true;
        private boolean channelHeaderVisible = true;
        private boolean rowLabelVisible = true;
        private int outputDpi = 300;
        private int exportScale = 1;
        private double labelFracX = -1.0;
        private double labelFracY = -1.0;
        private double scaleBarFracX = -1.0;
        private double scaleBarFracY = -1.0;
        private boolean annotationSnapEnabled = true;
        private List<List<String>> groupLayoutRows =
                new ArrayList<List<String>>();
        private Map<String, String> externalLabelOverrides =
                new LinkedHashMap<String, String>();
        private Map<String, String> imageOrientations =
                new LinkedHashMap<String, String>();

        public Builder createOverviewPanel(boolean value) {
            this.createOverviewPanel = value;
            return this;
        }

        public Builder annotateOverviewPanel(boolean value) {
            this.annotateOverviewPanel = value;
            return this;
        }

        public Builder annotateIndividualPanels(boolean value) {
            this.annotateIndividualPanels = value;
            return this;
        }

        public Builder groupRowsBy(GroupRowsBy value) {
            this.groupRowsBy = value;
            return this;
        }

        public Builder channelOrder(List<String> values) {
            this.channelOrder = new ArrayList<String>();
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.trim().isEmpty()) {
                        this.channelOrder.add(value.trim());
                    }
                }
            }
            return this;
        }

        public Builder cellSizePx(int value) {
            this.cellSizePx = value;
            return this;
        }

        public Builder scaleBarEnabled(boolean value) {
            this.scaleBarEnabled = value;
            return this;
        }

        public Builder scaleBarLengthUm(double value) {
            this.scaleBarLengthUm = value;
            return this;
        }

        public Builder scaleBarThicknessPx(int value) {
            this.scaleBarThicknessPx = value;
            return this;
        }

        public Builder scaleBarPosition(Position value) {
            this.scaleBarPosition = value;
            return this;
        }

        public Builder annotationColor(Color value) {
            this.annotationColor = value;
            return this;
        }

        public Builder labelMode(LabelMode value) {
            this.labelMode = value;
            return this;
        }

        public Builder customLabelTemplate(String value) {
            this.customLabelTemplate = value;
            return this;
        }

        public Builder labelFontSizePx(int value) {
            this.labelFontSizePx = value;
            return this;
        }

        public Builder labelPosition(Position value) {
            this.labelPosition = value;
            return this;
        }

        public Builder marginPx(int value) {
            this.marginPx = value;
            return this;
        }

        public Builder innerColGapPx(int value) {
            this.innerColGapPx = value;
            return this;
        }

        public Builder groupGapPx(int value) {
            this.groupGapPx = value;
            return this;
        }

        public Builder rowGapPx(int value) {
            this.rowGapPx = value;
            return this;
        }

        public Builder groupFontSizePx(int value) {
            this.groupFontSizePx = value;
            return this;
        }

        public Builder channelFontSizePx(int value) {
            this.channelFontSizePx = value;
            return this;
        }

        public Builder rowFontSizePx(int value) {
            this.rowFontSizePx = value;
            return this;
        }

        public Builder groupHeaderAlignment(TextAlignment value) {
            this.groupHeaderAlignment = value;
            return this;
        }

        public Builder channelHeaderOrientation(TextOrientation value) {
            this.channelHeaderOrientation = value;
            return this;
        }

        public Builder rowLabelOrientation(TextOrientation value) {
            this.rowLabelOrientation = value;
            return this;
        }

        public Builder channelHeaderGapPx(int value) {
            this.channelHeaderGapPx = value;
            return this;
        }

        public Builder rowLabelGapPx(int value) {
            this.rowLabelGapPx = value;
            return this;
        }

        public Builder groupHeaderVisible(boolean value) {
            this.groupHeaderVisible = value;
            return this;
        }

        public Builder channelHeaderVisible(boolean value) {
            this.channelHeaderVisible = value;
            return this;
        }

        public Builder rowLabelVisible(boolean value) {
            this.rowLabelVisible = value;
            return this;
        }

        public Builder outputDpi(int value) {
            this.outputDpi = value;
            return this;
        }

        public Builder exportScale(int value) {
            this.exportScale = value;
            return this;
        }

        public Builder labelFracX(double value) {
            this.labelFracX = value;
            return this;
        }

        public Builder labelFracY(double value) {
            this.labelFracY = value;
            return this;
        }

        public Builder scaleBarFracX(double value) {
            this.scaleBarFracX = value;
            return this;
        }

        public Builder scaleBarFracY(double value) {
            this.scaleBarFracY = value;
            return this;
        }

        public Builder annotationSnapEnabled(boolean value) {
            this.annotationSnapEnabled = value;
            return this;
        }

        public Builder groupLayoutRows(List<List<String>> rows) {
            this.groupLayoutRows = copyRows(rows);
            return this;
        }

        public Builder externalLabelOverrides(Map<String, String> values) {
            this.externalLabelOverrides = new LinkedHashMap<String, String>(
                    copyOverrides(values));
            return this;
        }

        public Builder externalLabelOverride(ExternalLabelKind kind, String key,
                String text) {
            if (kind == null) throw new IllegalArgumentException("kind is required");
            String mapKey = externalLabelKey(kind, key);
            if (text == null) externalLabelOverrides.remove(mapKey);
            else externalLabelOverrides.put(mapKey, text.trim());
            return this;
        }

        public Builder imageOrientations(Map<String, String> values) {
            this.imageOrientations = new LinkedHashMap<String, String>(
                    copyImageOrientations(values));
            return this;
        }

        public Builder imageOrientation(String imageId,
                ImageOrientation orientation) {
            String key = normalizeImageId(imageId);
            if (key.isEmpty()) {
                throw new IllegalArgumentException("imageId is required");
            }
            ImageOrientation value = orientation == null
                    ? ImageOrientation.IDENTITY : orientation;
            if (value.isIdentity()) imageOrientations.remove(key);
            else imageOrientations.put(key, value.token());
            return this;
        }

        public PanelConfig build() {
            return new PanelConfig(this);
        }
    }
}
