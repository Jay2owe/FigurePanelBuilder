/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.svg;

import fpb.figure.PanelConfig;
import fpb.figure.PanelRecord;
import fpb.figure.PanelWriter;
import fpb.figure.ScaleBar;
import fpb.util.IoUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Writes complete, self-contained SVG figures without external dependencies. */
public final class SvgWriter {

    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";
    private static final String FONT_STACK = "Helvetica, Arial, sans-serif";
    private static final Color PANEL_BG = Color.WHITE;
    private static final Color PANEL_LINE = new Color(210, 210, 210);
    private static final Color PANEL_TEXT = new Color(35, 35, 35);
    private static final Color PANEL_HELP_TEXT = new Color(90, 90, 90);

    private SvgWriter() {}

    public static void writeOverviewSvg(File outputFile, List<PanelRecord> records,
            PanelConfig config) throws IOException {
        if (outputFile == null) throw new IllegalArgumentException("outputFile is required");
        File parent = outputFile.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        File temp = tempFileFor(outputFile);
        boolean moved = false;
        try {
            Writer out = new OutputStreamWriter(Files.newOutputStream(temp.toPath()),
                    StandardCharsets.UTF_8);
            try {
                writeOverviewSvg(out, records, config);
            } finally {
                out.close();
            }
            moveAtomically(temp.toPath(), outputFile.toPath());
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temp.toPath());
        }
    }

    public static String renderOverviewSvg(List<PanelRecord> records,
            PanelConfig config) throws IOException {
        java.io.StringWriter out = new java.io.StringWriter();
        writeOverviewSvg(out, records, config);
        return out.toString();
    }

    public static void writeOverviewSvg(Writer out, List<PanelRecord> records,
            PanelConfig config) throws IOException {
        if (out == null) throw new IllegalArgumentException("out is required");
        List<PanelRecord> usable = safeRecords(records);
        if (usable.isEmpty()) {
            throw new IllegalArgumentException("At least one panel record is required.");
        }
        if (config == null) throw new IllegalArgumentException("config is required");

        List<String> columns = orderedOutputNames(usable, config.channelOrder());
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("At least one output column is required.");
        }
        List<Row> rows = orderedRows(usable, config.groupRowsBy());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("At least one row is required.");
        }

        LinkedHashMap<String, PanelRecord> byRowAndColumn =
                new LinkedHashMap<String, PanelRecord>();
        for (PanelRecord record : usable) {
            byRowAndColumn.put(record.imageKey() + "\n" + record.outputName(), record);
        }

        Font headerFont = new Font(Font.SANS_SERIF, Font.BOLD,
                config.channelFontSizePx());
        Font rowFont = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
        Font groupFont = new Font(Font.SANS_SERIF, Font.BOLD,
                config.groupFontSizePx());
        TileLayout layout = createTileLayout(columns, rows, config.cellSizePx(),
                groupCount(rows), headerFont, rowFont, groupFont, config);

        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.write("<svg xmlns=\"" + SVG_NS + "\" xmlns:xlink=\"" + XLINK_NS
                + "\" width=\"" + physicalMm(layout.width, config.outputDpi())
                + "mm\" height=\"" + physicalMm(layout.height, config.outputDpi())
                + "mm\" viewBox=\"0 0 " + layout.width + " " + layout.height + "\">\n");
        rect(out, 0, 0, layout.width, layout.height, PANEL_BG);

        int x0 = layout.margin + layout.rowLabelWidth + layout.rowLabelGap;
        int y = layout.margin;
        if (config.channelHeaderVisible()) {
            writeColumnHeaders(out, columns, x0, y, config.cellSizePx(),
                    layout.colGap, layout.headerHeight, headerFont);
            y += layout.headerHeight;
        }

        String lastGroup = null;
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            if (config.groupHeaderVisible() && !row.groupLabel.equals(lastGroup)) {
                writeGroupLabel(out, row.groupLabel, layout.margin, y,
                        layout.width - layout.margin * 2, layout.groupHeaderHeight,
                        groupFont);
                y += layout.groupHeaderHeight;
                lastGroup = row.groupLabel;
            }
            writeRowLabel(out, row.label, layout.margin, y, layout.rowLabelWidth,
                    config.cellSizePx(), rowFont);
            for (int c = 0; c < columns.size(); c++) {
                int x = x0 + c * (config.cellSizePx() + layout.colGap);
                PanelRecord record = byRowAndColumn.get(
                        row.key + "\n" + columns.get(c));
                writeCell(out, record, config, x, y, config.cellSizePx());
            }
            y += config.cellSizePx();
            if (r < rows.size() - 1) y += layout.rowGap;
        }
        out.write("</svg>\n");
    }

    private static void writeCell(Writer out, PanelRecord record, PanelConfig config,
            int x, int y, int cell) throws IOException {
        rect(out, x, y, cell, cell, Color.BLACK);
        out.write("<rect x=\"" + x + "\" y=\"" + y + "\" width=\"" + cell
                + "\" height=\"" + cell + "\" fill=\"none\" stroke=\""
                + hex(PANEL_LINE) + "\" stroke-width=\"1\"/>\n");

        if (record == null) {
            text(out, "Not saved", x + 12, y + 24, 12, false, PANEL_HELP_TEXT);
            return;
        }
        File imageFile = record.preferredImageFile(config.annotateIndividualPanels());
        BufferedImage image;
        try {
            image = imageFile == null ? null : ImageIO.read(imageFile);
        } catch (IOException ignored) {
            image = null;
        }
        if (image == null) {
            text(out, "Missing image", x + 12, y + 24, 12, false, PANEL_HELP_TEXT);
            return;
        }

        double scale = Math.min((double) cell / image.getWidth(),
                (double) cell / image.getHeight());
        int drawW = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int drawH = Math.max(1, (int) Math.round(image.getHeight() * scale));
        int drawX = x + (cell - drawW) / 2;
        int drawY = y + (cell - drawH) / 2;
        image(out, image, drawX, drawY, drawW, drawH);

        if (config.annotateOverviewPanel()
                && !config.annotateIndividualPanels()) {
            double recordScale = drawW / (double) Math.max(1, record.widthPx());
            writeAnnotations(out, record, config,
                    new Rectangle(drawX, drawY, drawW, drawH), recordScale,
                    Math.max(0.6, recordScale), Math.max(0.6, recordScale));
        }
    }

    private static void writeAnnotations(Writer out, PanelRecord record,
            PanelConfig config, Rectangle imageRect, double scaleFactor,
            double styleScale, double scaleBarThicknessScale) throws IOException {
        double safeStyleScale = styleScale > 0.0 && Double.isFinite(styleScale)
                ? styleScale : 1.0;
        double safeThicknessScale = scaleBarThicknessScale > 0.0
                && Double.isFinite(scaleBarThicknessScale)
                ? scaleBarThicknessScale : safeStyleScale;
        if (config.labelMode() != PanelConfig.LabelMode.NONE) {
            String label = labelText(record, config);
            if (!label.isEmpty()) {
                writeTextLabel(out, label, imageRect, config.labelPosition(),
                        config.labelFontSizePx(), config.annotationColor(),
                        safeStyleScale,
                        config.hasLabelFraction() ? config.labelFracX() : -1.0,
                        config.hasLabelFraction() ? config.labelFracY() : -1.0);
            }
        }
        if (config.scaleBarEnabled()) {
            writeScaleBar(out, record, imageRect, config, safeStyleScale,
                    safeThicknessScale);
        }
    }

    private static void writeScaleBar(Writer out, PanelRecord record,
            Rectangle imageRect, PanelConfig config, double styleScale,
            double thicknessScale) throws IOException {
        int barLengthPx = ScaleBar.lengthPixels(record.calibration(),
                record.widthPx(), record.heightPx(), imageRect.width,
                imageRect.height, config.scaleBarLengthUm());
        if (barLengthPx <= 0) return;

        int inset = scaledDimension(Math.max(8, config.labelFontSizePx() / 2),
                styleScale);
        barLengthPx = Math.min(barLengthPx, imageRect.width - inset * 2);
        if (barLengthPx < 4) return;

        int thickness = scaledDimension(config.scaleBarThicknessPx(), thicknessScale);
        int x;
        int y;
        boolean captionBelow;
        if (config.hasScaleBarFraction()) {
            x = fracOriginX(imageRect, config.scaleBarFracX(), barLengthPx);
            y = fracOriginY(imageRect, config.scaleBarFracY(), thickness);
            captionBelow = (y + thickness / 2) < (imageRect.y + imageRect.height / 2);
        } else {
            x = horizontalPosition(config.scaleBarPosition(), imageRect, inset,
                    barLengthPx);
            y = verticalPosition(config.scaleBarPosition(), imageRect, inset,
                    thickness);
            captionBelow = isTop(config.scaleBarPosition());
        }
        rect(out, x, y, barLengthPx, thickness, config.annotationColor());

        String label = formatLength(config.scaleBarLengthUm()) + " um";
        int baseFontSize = Math.max(8,
                (int) Math.round(config.labelFontSizePx() * 0.78));
        int fontSize = scaledDimension(baseFontSize, styleScale);
        FontMetrics fm = metrics(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        int textX = x + Math.max(0, (barLengthPx - fm.stringWidth(label)) / 2);
        int textY = y - scaledDimension(4, styleScale);
        if (captionBelow) {
            textY = y + thickness + fm.getAscent() + scaledDimension(3, styleScale);
        }
        text(out, label, textX, textY, fontSize, true, config.annotationColor());
    }

    private static void writeTextLabel(Writer out, String value,
            Rectangle imageRect, PanelConfig.Position position, int fontSize,
            Color color, double styleScale, double fracX, double fracY)
            throws IOException {
        int scaledFont = scaledDimension(fontSize, styleScale);
        FontMetrics fm = metrics(new Font(Font.SANS_SERIF, Font.BOLD, scaledFont));
        int inset = scaledDimension(Math.max(8, fontSize / 2), styleScale);
        int textW = fm.stringWidth(value);
        int textX;
        int textY;
        if (fracX >= 0.0 && fracY >= 0.0) {
            int textH = fm.getAscent() + fm.getDescent();
            textX = fracOriginX(imageRect, fracX, textW);
            textY = fracOriginY(imageRect, fracY, textH) + fm.getAscent();
        } else {
            textX = horizontalPosition(position, imageRect, inset, textW);
            if (isTop(position)) textY = imageRect.y + inset + fm.getAscent();
            else textY = imageRect.y + imageRect.height - inset;
        }
        text(out, value, textX, textY, scaledFont, true, color);
    }

    private static TileLayout createTileLayout(List<String> columns, List<Row> rows,
            int cell, int groupCount, Font headerFont, Font rowFont,
            Font groupFont, PanelConfig config) {
        FontMetrics headerFm = metrics(headerFont);
        FontMetrics rowFm = metrics(rowFont);
        FontMetrics groupFm = metrics(groupFont);
        int margin = config.marginPx();
        int colGap = config.innerColGapPx();
        int rowGap = config.rowGapPx();
        int rowLabelGap = 6;
        int rowLabelWidth = tightRowLabelWidth(rows, rowFm, cell);
        if (rowLabelWidth <= 0) rowLabelGap = 0;
        int headerHeight = config.channelHeaderVisible()
                ? headerFm.getHeight() + 4 : 0;
        int groupHeaderHeight = config.groupHeaderVisible()
                ? groupFm.getHeight() + 4 : 0;
        int width = margin * 2 + rowLabelWidth + rowLabelGap
                + columns.size() * cell
                + Math.max(0, columns.size() - 1) * colGap;
        int height = margin * 2 + headerHeight
                + groupCount * groupHeaderHeight
                + rows.size() * cell
                + Math.max(0, rows.size() - 1) * rowGap;
        return new TileLayout(width, height, margin, colGap, rowGap,
                rowLabelWidth, rowLabelGap, headerHeight, groupHeaderHeight);
    }

    private static void writeColumnHeaders(Writer out, List<String> columns,
            int x0, int y, int cell, int colGap, int headerHeight, Font font)
            throws IOException {
        FontMetrics fm = metrics(font);
        for (int i = 0; i < columns.size(); i++) {
            int x = x0 + i * (cell + colGap);
            String label = fitSingleLine(columns.get(i), fm, cell);
            int textX = x + Math.max(0, (cell - fm.stringWidth(label)) / 2);
            int textY = y + Math.max(fm.getAscent(),
                    (headerHeight + fm.getAscent()) / 2 - 1);
            text(out, label, textX, textY, font.getSize(), true, PANEL_TEXT);
        }
    }

    private static void writeGroupLabel(Writer out, String label, int x, int y,
            int width, int height, Font font) throws IOException {
        if (height <= 0 || width <= 0) return;
        FontMetrics fm = metrics(font);
        text(out, fitSingleLine(label, fm, width), x, y + 2 + fm.getAscent(),
                font.getSize(), true, PANEL_TEXT);
    }

    private static void writeRowLabel(Writer out, String label, int x, int y,
            int width, int height, Font font) throws IOException {
        if (width <= 0 || height <= 0) return;
        FontMetrics fm = metrics(font);
        List<String> lines = fitWrappedLines(label, fm, width, height);
        int lineHeight = fm.getHeight();
        int totalHeight = lines.size() * lineHeight;
        int textY = y + Math.max(fm.getAscent(),
                (height - totalHeight) / 2 + fm.getAscent());
        for (String line : lines) {
            text(out, line, x, textY, font.getSize(), false, PANEL_TEXT);
            textY += lineHeight;
        }
    }

    private static void image(Writer out, BufferedImage image, int x, int y,
            int width, int height) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", png)) {
            throw new IOException("No PNG writer available");
        }
        String b64 = Base64.getEncoder().encodeToString(png.toByteArray());
        out.write("<image x=\"" + x + "\" y=\"" + y + "\" width=\"" + width
                + "\" height=\"" + height
                + "\" xlink:href=\"data:image/png;base64," + b64 + "\"/>\n");
    }

    private static void rect(Writer out, int x, int y, int width, int height,
            Color fill) throws IOException {
        out.write("<rect x=\"" + x + "\" y=\"" + y + "\" width=\"" + width
                + "\" height=\"" + height + "\" fill=\"" + hex(fill) + "\"/>\n");
    }

    private static void text(Writer out, String value, int x, int y, int fontSize,
            boolean bold, Color fill) throws IOException {
        out.write("<text x=\"" + x + "\" y=\"" + y + "\" font-family=\""
                + FONT_STACK + "\" font-size=\"" + fontSize + "\"");
        if (bold) out.write(" font-weight=\"bold\"");
        out.write(" fill=\"" + hex(fill) + "\">" + SvgEscape.text(value)
                + "</text>\n");
    }

    private static FontMetrics metrics(Font font) {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        try {
            PanelWriter.applyQualityHints(g);
            return g.getFontMetrics(font);
        } finally {
            g.dispose();
        }
    }

    private static List<String> orderedOutputNames(List<PanelRecord> records,
            List<String> requestedOrder) {
        LinkedHashSet<String> names = new LinkedHashSet<String>();
        for (String requested : requestedOrder == null
                ? Collections.<String>emptyList() : requestedOrder) {
            String trimmed = requested == null ? "" : requested.trim();
            if (!trimmed.isEmpty() && containsOutput(records, trimmed)) {
                names.add(trimmed);
            }
        }
        for (PanelRecord record : records) {
            if (!record.outputName().isEmpty()) names.add(record.outputName());
        }
        return new ArrayList<String>(names);
    }

    private static boolean containsOutput(List<PanelRecord> records,
            String outputName) {
        for (PanelRecord record : records) {
            if (record.outputName().equals(outputName)) return true;
        }
        return false;
    }

    private static List<Row> orderedRows(List<PanelRecord> records,
            PanelConfig.GroupRowsBy groupRowsBy) {
        LinkedHashMap<String, Row> rows = new LinkedHashMap<String, Row>();
        for (PanelRecord record : records) {
            String groupLabel = groupRowsBy == PanelConfig.GroupRowsBy.SUBJECT
                    ? record.subject() : record.group();
            if (groupLabel.isEmpty()) groupLabel = "Unassigned";
            Row existing = rows.get(record.imageKey());
            if (existing == null) {
                rows.put(record.imageKey(), new Row(record.imageKey(),
                        record.imageLabel(), groupLabel, record));
            }
        }
        List<Row> out = new ArrayList<Row>(rows.values());
        Collections.sort(out, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                int group = compareText(a.groupLabel, b.groupLabel);
                if (group != 0) return group;
                int subject = compareText(a.record.subject(), b.record.subject());
                if (subject != 0) return subject;
                int section = compareText(a.record.section(), b.record.section());
                if (section != 0) return section;
                return compareText(a.record.imageId(), b.record.imageId());
            }
        });
        return out;
    }

    private static int groupCount(List<Row> rows) {
        int count = 0;
        String lastGroup = null;
        for (Row row : rows) {
            if (!row.groupLabel.equals(lastGroup)) {
                count++;
                lastGroup = row.groupLabel;
            }
        }
        return count;
    }

    private static List<PanelRecord> safeRecords(List<PanelRecord> records) {
        List<PanelRecord> out = new ArrayList<PanelRecord>();
        if (records == null) return out;
        for (PanelRecord record : records) {
            if (record != null) out.add(record);
        }
        return out;
    }

    private static List<String> wrap(String text, FontMetrics fm, int maxWidth) {
        List<String> lines = new ArrayList<String>();
        String source = text == null ? "" : text.trim();
        if (source.isEmpty()) {
            lines.add("");
            return lines;
        }
        String[] words = source.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(candidate) <= maxWidth || line.length() == 0) {
                line.setLength(0);
                line.append(candidate);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    private static int tightRowLabelWidth(List<Row> rows, FontMetrics fm,
            int cell) {
        int maxTextWidth = 0;
        int longestWordWidth = 0;
        for (Row row : rows) {
            String source = row == null || row.label == null ? "" : row.label.trim();
            maxTextWidth = Math.max(maxTextWidth, fm.stringWidth(source));
            if (!source.isEmpty()) {
                String[] words = source.split("\\s+");
                for (String word : words) {
                    longestWordWidth = Math.max(longestWordWidth,
                            fm.stringWidth(word));
                }
            }
        }
        if (maxTextWidth <= 0) return 0;

        int preferredMin = Math.min(96, Math.max(56, cell / 2));
        int minWidth = Math.min(maxTextWidth,
                Math.max(longestWordWidth, preferredMin));
        int maxWidth = Math.min(maxTextWidth,
                Math.max(minWidth, Math.min(260, Math.max(96, cell))));
        int availableHeight = Math.max(fm.getHeight(), cell - 2);
        for (int width = minWidth; width <= maxWidth; width += 4) {
            if (rowLabelsFit(rows, fm, width, availableHeight)) return width;
        }
        return maxWidth;
    }

    private static boolean rowLabelsFit(List<Row> rows, FontMetrics fm,
            int width, int height) {
        int lineHeight = fm.getHeight();
        int maxLines = Math.max(1, height / Math.max(1, lineHeight));
        for (Row row : rows) {
            List<String> lines = wrap(row == null ? "" : row.label, fm, width);
            if (lines.size() > maxLines) return false;
            for (String line : lines) {
                if (fm.stringWidth(line) > width) return false;
            }
        }
        return true;
    }

    private static List<String> fitWrappedLines(String text, FontMetrics fm,
            int width, int height) {
        List<String> wrapped = wrap(text, fm, width);
        int maxLines = Math.max(1, height / Math.max(1, fm.getHeight()));
        List<String> out = new ArrayList<String>();
        int count = Math.min(wrapped.size(), maxLines);
        for (int i = 0; i < count; i++) {
            String line = wrapped.get(i);
            if (i == count - 1 && wrapped.size() > count) {
                line = ellipsize(line, fm, width);
            } else {
                line = fitSingleLine(line, fm, width);
            }
            out.add(line);
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    private static String fitSingleLine(String text, FontMetrics fm, int width) {
        String clean = text == null ? "" : text.trim();
        if (width <= 0 || clean.isEmpty()) return "";
        if (fm.stringWidth(clean) <= width) return clean;
        return ellipsize(clean, fm, width);
    }

    private static String ellipsize(String text, FontMetrics fm, int width) {
        if (width <= 0) return "";
        String suffix = "...";
        if (fm.stringWidth(suffix) > width) return "";
        String clean = text == null ? "" : text.trim();
        int end = clean.length();
        while (end > 0) {
            String candidate = clean.substring(0, end).trim() + suffix;
            if (fm.stringWidth(candidate) <= width) return candidate;
            end--;
        }
        return suffix;
    }

    private static String labelText(PanelRecord record, PanelConfig config) {
        String template;
        switch (config.labelMode()) {
            case NONE:
                return "";
            case IMAGE_NAME:
                template = "{group} {subject} {section}";
                break;
            case GROUP_SUBJECT:
                template = "{group} {subject}";
                break;
            case CUSTOM:
                template = config.customLabelTemplate().isEmpty()
                        ? "{channel}" : config.customLabelTemplate();
                break;
            case CHANNEL_NAME:
            default:
                template = "{channel}";
                break;
        }
        return replaceTokens(template, record).replaceAll("\\s+", " ").trim();
    }

    private static String replaceTokens(String template, PanelRecord record) {
        String text = template == null ? "" : template;
        text = text.replace("{group}", record.group());
        text = text.replace("{subject}", record.subject());
        text = text.replace("{section}", record.section());
        text = text.replace("{channel}", record.channelName());
        text = text.replace("{output}", record.outputName());
        return text;
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

    private static String physicalMm(int pixels, int dpi) {
        return format(pixels * 25.4d / Math.max(1, dpi));
    }

    private static String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0000001) {
            return String.valueOf((long) Math.rint(value));
        }
        String out = String.format(Locale.US, "%.6f", value);
        while (out.indexOf('.') >= 0 && out.endsWith("0")) {
            out = out.substring(0, out.length() - 1);
        }
        if (out.endsWith(".")) out = out.substring(0, out.length() - 1);
        return out;
    }

    private static String formatLength(double value) {
        if (Math.abs(value - Math.round(value)) < 0.0001) {
            return String.valueOf((long) Math.round(value));
        }
        return String.format(Locale.US, "%.2f", value);
    }

    private static String hex(Color color) {
        Color safe = color == null ? Color.BLACK : color;
        return String.format(Locale.US, "#%02X%02X%02X",
                safe.getRed(), safe.getGreen(), safe.getBlue());
    }

    private static int compareText(String a, String b) {
        String aa = a == null ? "" : a;
        String bb = b == null ? "" : b;
        return aa.compareToIgnoreCase(bb);
    }

    private static File tempFileFor(File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        return File.createTempFile(tempPrefix(target), ".tmp",
                parent == null ? new File(".") : parent);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        IoUtils.moveReplacing(source, target);
    }

    private static String tempPrefix(File target) {
        String name = target == null ? "figure" : target.getName();
        String clean = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.length() < 3 ? "tmp" + clean : clean;
    }

    private static final class TileLayout {
        final int width;
        final int height;
        final int margin;
        final int colGap;
        final int rowGap;
        final int rowLabelWidth;
        final int rowLabelGap;
        final int headerHeight;
        final int groupHeaderHeight;

        TileLayout(int width, int height, int margin, int colGap,
                int rowGap, int rowLabelWidth, int rowLabelGap,
                int headerHeight, int groupHeaderHeight) {
            this.width = width;
            this.height = height;
            this.margin = margin;
            this.colGap = colGap;
            this.rowGap = rowGap;
            this.rowLabelWidth = rowLabelWidth;
            this.rowLabelGap = rowLabelGap;
            this.headerHeight = headerHeight;
            this.groupHeaderHeight = groupHeaderHeight;
        }
    }

    private static final class Row {
        final String key;
        final String label;
        final String groupLabel;
        final PanelRecord record;

        Row(String key, String label, String groupLabel, PanelRecord record) {
            this.key = key;
            this.label = label;
            this.groupLabel = groupLabel;
            this.record = record;
        }
    }
}
