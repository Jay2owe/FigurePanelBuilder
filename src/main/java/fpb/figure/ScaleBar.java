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
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.math.BigDecimal;

/** Draws calibrated scale annotations directly with Java2D. */
public final class ScaleBar {

    private ScaleBar() {}

    public static int lengthPixels(PanelRecord record, int drawnWidthPx,
            double lengthUm) {
        if (record == null) return 0;
        return lengthPixels(record.calibration(), record.widthPx(), record.heightPx(),
                drawnWidthPx, Math.max(1, (int) Math.round(
                        drawnWidthPx * (record.heightPx() / (double) record.widthPx()))),
                lengthUm);
    }

    public static int lengthPixels(CalibrationCheck.Result sourceCalibration,
            int sourceWidthPx, int sourceHeightPx, int drawnWidthPx,
            int drawnHeightPx, double lengthUm) {
        if (lengthUm <= 0.0 || !Double.isFinite(lengthUm)) return 0;
        CalibrationCheck.Result drawn = CalibrationCheck.forDrawnSize(
                sourceCalibration, sourceWidthPx, sourceHeightPx,
                drawnWidthPx, drawnHeightPx);
        if (!drawn.isAvailable()) return 0;
        return Math.max(0, (int) Math.round(lengthUm / drawn.pixelWidthUm()));
    }

    static boolean draw(Graphics2D g, PanelRecord record, Rectangle imageRect,
            PanelConfig config, double styleScale, double thicknessScale,
            PanelWriter.WriteReport report) {
        if (g == null || record == null || imageRect == null || config == null) return false;
        if (!record.calibration().isAvailable()) {
            if (report != null) report.addUncalibrated(record);
            return false;
        }
        int barLengthPx = lengthPixels(record.calibration(), record.widthPx(),
                record.heightPx(), imageRect.width, imageRect.height,
                config.scaleBarLengthUm());

        int inset = scaledDimension(Math.max(8, config.labelFontSizePx() / 2),
                styleScale);
        int availableWidth = imageRect.width - inset * 2;
        if (barLengthPx > availableWidth || barLengthPx < 4) {
            if (report != null) report.addScaleBarDidNotFit(record);
            return false;
        }

        int thickness = scaledDimension(config.scaleBarThicknessPx(), thicknessScale);
        int x;
        int y;
        boolean captionBelow;
        if (config.hasScaleBarFraction()) {
            x = fracOriginX(imageRect, config.scaleBarFracX(), barLengthPx);
            y = fracOriginY(imageRect, config.scaleBarFracY(), thickness);
            captionBelow = (y + thickness / 2) < (imageRect.y + imageRect.height / 2);
        } else {
            x = horizontalPosition(config.scaleBarPosition(), imageRect, inset, barLengthPx);
            y = verticalPosition(config.scaleBarPosition(), imageRect, inset, thickness);
            captionBelow = isTop(config.scaleBarPosition());
        }

        String label = formatLengthUm(config.scaleBarLengthUm()) + " um";
        int baseFontSize = Math.max(8,
                (int) Math.round(config.labelFontSizePx() * 0.78));
        Font font = new Font(Font.SANS_SERIF, Font.BOLD,
                scaledDimension(baseFontSize, styleScale));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        CaptionPlacement caption = captionPlacement(imageRect, x, y, barLengthPx,
                thickness, captionBelow, label, fm, scaledDimension(4, styleScale),
                scaledDimension(3, styleScale));
        if (caption == null) {
            if (report != null) report.addScaleBarDidNotFit(record);
            return false;
        }

        Color color = config.annotationColor();
        g.setColor(color);
        g.fillRect(x, y, barLengthPx, thickness);
        g.drawString(label, caption.x(), caption.baseline());
        if (report != null) report.addScaleBarDrawn();
        return true;
    }

    /** Places a complete scale caption inside its source image, or returns null. */
    public static CaptionPlacement captionPlacement(Rectangle imageRect,
            int barX, int barY, int barLengthPx, int thicknessPx,
            boolean captionBelow, String label, FontMetrics metrics, int gapAbovePx,
            int gapBelowPx) {
        if (imageRect == null || label == null || metrics == null || barLengthPx <= 0
                || thicknessPx <= 0) return null;
        long right = imageRect.x + (long) imageRect.width;
        long bottom = imageRect.y + (long) imageRect.height;
        if (barX < imageRect.x || barY < imageRect.y
                || barX + (long) barLengthPx > right
                || barY + (long) thicknessPx > bottom) return null;

        int textWidth = metrics.stringWidth(label);
        return captionPlacementForWidth(imageRect, barX, barY, barLengthPx,
                thicknessPx, captionBelow, metrics, textWidth,
                gapAbovePx, gapBelowPx);
    }

    private static CaptionPlacement captionPlacementForWidth(Rectangle imageRect,
            int barX, int barY, int barLengthPx, int thicknessPx,
            boolean captionBelow, FontMetrics metrics, int textWidth,
            int gapAbovePx, int gapBelowPx) {
        int maximumX = imageRect.x + imageRect.width - textWidth;
        if (textWidth > imageRect.width || maximumX < imageRect.x) return null;
        long desiredX = barX + (barLengthPx - (long) textWidth) / 2L;
        int textX = (int) Math.max(imageRect.x,
                Math.min(maximumX, desiredX));
        int baseline = captionBelow
                ? barY + thicknessPx + metrics.getAscent()
                        + Math.max(0, gapBelowPx)
                : barY - Math.max(0, gapAbovePx) - metrics.getDescent();
        int textTop = baseline - metrics.getAscent();
        int textBottom = baseline + metrics.getDescent();
        if (textTop < imageRect.y
                || textBottom > imageRect.y + imageRect.height) return null;
        return new CaptionPlacement(textX, baseline);
    }

    /** Pixel position of a caption baseline whose complete glyph box fits. */
    public static final class CaptionPlacement {
        private final int x;
        private final int baseline;

        CaptionPlacement(int x, int baseline) {
            this.x = x;
            this.baseline = baseline;
        }

        public int x() { return x; }
        public int baseline() { return baseline; }
    }

    private static int fracOriginX(Rectangle rect, double frac, int contentWidth) {
        int min = rect.x;
        int max = rect.x + rect.width - contentWidth;
        if (max < min) max = min;
        int pos = rect.x + (int) Math.round(frac * rect.width);
        return Math.max(min, Math.min(max, pos));
    }

    private static int fracOriginY(Rectangle rect, double frac, int contentHeight) {
        int min = rect.y;
        int max = rect.y + rect.height - contentHeight;
        if (max < min) max = min;
        int pos = rect.y + (int) Math.round(frac * rect.height);
        return Math.max(min, Math.min(max, pos));
    }

    private static int horizontalPosition(PanelConfig.Position position,
            Rectangle rect, int inset, int contentWidth) {
        if (position == PanelConfig.Position.TOP_RIGHT
                || position == PanelConfig.Position.BOTTOM_RIGHT) {
            return rect.x + rect.width - inset - contentWidth;
        }
        return rect.x + inset;
    }

    private static int verticalPosition(PanelConfig.Position position,
            Rectangle rect, int inset, int contentHeight) {
        if (position == PanelConfig.Position.TOP_LEFT
                || position == PanelConfig.Position.TOP_RIGHT) {
            return rect.y + inset;
        }
        return rect.y + rect.height - inset - contentHeight;
    }

    private static boolean isTop(PanelConfig.Position position) {
        return position == PanelConfig.Position.TOP_LEFT
                || position == PanelConfig.Position.TOP_RIGHT;
    }

    private static int scaledDimension(int value, double scale) {
        double safeScale = scale > 0.0 && Double.isFinite(scale) ? scale : 1.0;
        return Math.max(1, (int) Math.round(value * safeScale));
    }

    /** Exact non-scientific decimal used for visible physical-length annotations. */
    public static String formatLengthUm(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Physical length must be finite");
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
