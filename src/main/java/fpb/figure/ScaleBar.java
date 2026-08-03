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
        int barLengthPx = lengthPixels(record.calibration(), record.widthPx(),
                record.heightPx(), imageRect.width, imageRect.height,
                config.scaleBarLengthUm());
        if (barLengthPx <= 0) {
            if (report != null) report.addUncalibrated(record);
            return false;
        }

        int inset = scaledDimension(Math.max(8, config.labelFontSizePx() / 2),
                styleScale);
        barLengthPx = Math.min(barLengthPx, imageRect.width - inset * 2);
        if (barLengthPx < 4) return false;

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

        Color color = config.annotationColor();
        g.setColor(color);
        g.fillRect(x, y, barLengthPx, thickness);

        String label = formatLength(config.scaleBarLengthUm()) + " um";
        int baseFontSize = Math.max(8,
                (int) Math.round(config.labelFontSizePx() * 0.78));
        Font font = new Font(Font.SANS_SERIF, Font.BOLD,
                scaledDimension(baseFontSize, styleScale));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int textX = x + Math.max(0, (barLengthPx - fm.stringWidth(label)) / 2);
        int textY = y - scaledDimension(4, styleScale);
        if (captionBelow) {
            textY = y + thickness + fm.getAscent() + scaledDimension(3, styleScale);
        }
        g.drawString(label, textX, textY);
        return true;
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

    private static String formatLength(double value) {
        if (Math.abs(value - Math.round(value)) < 0.0001) {
            return String.valueOf((long) Math.round(value));
        }
        return String.format(java.util.Locale.US, "%.2f", value);
    }
}
