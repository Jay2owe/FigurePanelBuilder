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
import fpb.util.CancellationCheck;

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
        writeOverviewSvg(outputFile, records, config,
                CancellationCheck.NEVER_CANCELLED);
    }

    public static void writeOverviewSvg(File outputFile, List<PanelRecord> records,
            PanelConfig config, CancellationCheck cancelCheck) throws IOException {
        writeOverviewSvg(outputFile, records, config, cancelCheck,
                new PanelWriter.WriteReport());
    }

    public static void writeOverviewSvg(File outputFile, List<PanelRecord> records,
            PanelConfig config, CancellationCheck cancelCheck,
            PanelWriter.WriteReport report) throws IOException {
        if (outputFile == null) throw new IllegalArgumentException("outputFile is required");
        checkCancelled(cancelCheck);
        File parent = outputFile.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        File temp = tempFileFor(outputFile);
        boolean moved = false;
        try {
            Writer out = new CancellationWriter(new OutputStreamWriter(
                    Files.newOutputStream(temp.toPath()), StandardCharsets.UTF_8),
                    cancelCheck);
            try {
                writeOverviewSvg(out, records, config, report);
                checkCancelled(cancelCheck);
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
        writeOverviewSvg(out, records, config, new PanelWriter.WriteReport());
    }

    private static void writeOverviewSvg(Writer out, List<PanelRecord> records,
            PanelConfig config, PanelWriter.WriteReport report) throws IOException {
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
        if (config.hasGroupLayoutRows()) {
            writeGroupLayoutSvg(out, usable, columns, config, report);
            return;
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
        Font rowFont = new Font(Font.SANS_SERIF, Font.PLAIN,
                config.rowFontSizePx());
        Font groupFont = new Font(Font.SANS_SERIF, Font.BOLD,
                config.groupFontSizePx());
        TileLayout layout = createTileLayout(columns, rows, config.cellSizePx(),
                groupCount(rows), headerFont, rowFont, groupFont, config);
        int exportScale = Math.max(1, Math.min(4, config.exportScale()));
        int outputWidth = scaledCanvas(layout.width, exportScale);
        int outputHeight = scaledCanvas(layout.height, exportScale);

        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.write("<svg xmlns=\"" + SVG_NS + "\" xmlns:xlink=\"" + XLINK_NS
                + "\" width=\"" + physicalMm(outputWidth, config.outputDpi())
                + "mm\" height=\"" + physicalMm(outputHeight, config.outputDpi())
                + "mm\" viewBox=\"0 0 " + outputWidth + " " + outputHeight + "\">\n");
        out.write("<g transform=\"scale(" + exportScale + ")\">\n");
        rect(out, 0, 0, layout.width, layout.height, PANEL_BG);

        int x0 = layout.margin + layout.rowLabelWidth + layout.rowLabelGap;
        int y = layout.margin;
        if (config.channelHeaderVisible()) {
            writeColumnHeaders(out, columns, x0, y, config.cellSizePx(),
                    layout.colGap, layout.headerHeight, headerFont,
                    config.channelHeaderOrientation(),
                    config.channelHeaderGapPx(), config);
            y += layout.headerHeight;
        }

        String lastGroup = null;
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            if (config.groupHeaderVisible() && !row.groupLabel.equals(lastGroup)) {
                writeGroupLabel(out, row.groupLabel, layout.margin, y,
                        layout.width - layout.margin * 2, layout.groupHeaderHeight,
                        groupFont, config);
                y += layout.groupHeaderHeight;
                lastGroup = row.groupLabel;
            }
            if (config.rowLabelVisible()) {
                writeRowLabel(out, row.key, row.label, layout.margin, y,
                        layout.rowLabelWidth, config.cellSizePx(), rowFont,
                        config.rowLabelOrientation(), config);
            }
            for (int c = 0; c < columns.size(); c++) {
                int x = x0 + c * (config.cellSizePx() + layout.colGap);
                PanelRecord record = byRowAndColumn.get(
                        row.key + "\n" + columns.get(c));
                writeCell(out, record, config, x, y, config.cellSizePx(), report);
            }
            y += config.cellSizePx();
            if (r < rows.size() - 1) y += layout.rowGap;
        }
        out.write("</g>\n</svg>\n");
    }

    private static void writeGroupLayoutSvg(Writer out, List<PanelRecord> records,
            List<String> columns, PanelConfig config,
            PanelWriter.WriteReport report) throws IOException {
        List<List<String>> requested = normalizedGroupLayout(records,
                config.groupLayoutRows());
        if (requested.isEmpty()) {
            writeOverviewSvg(out, records, config.toBuilder()
                    .groupLayoutRows(Collections.<List<String>>emptyList()).build(), report);
            return;
        }
        Font headerFont = new Font(Font.SANS_SERIF, Font.BOLD,
                config.channelFontSizePx());
        Font rowFont = new Font(Font.SANS_SERIF, Font.PLAIN,
                config.rowFontSizePx());
        Font groupFont = new Font(Font.SANS_SERIF, Font.BOLD,
                config.groupFontSizePx());
        FontMetrics headerMetrics = metrics(headerFont);
        FontMetrics rowMetrics = metrics(rowFont);
        FontMetrics groupMetrics = metrics(groupFont);
        List<List<GroupBlock>> blockRows = new ArrayList<List<GroupBlock>>();
        int width = config.marginPx() * 2;
        int height = config.marginPx() * 2;
        for (List<String> requestedRow : requested) {
            List<GroupBlock> blocks = new ArrayList<GroupBlock>();
            int rowWidth = 0;
            int rowHeight = 0;
            for (String group : requestedRow) {
                GroupBlock block = createGroupBlock(group, records, columns, config,
                        headerMetrics, rowMetrics, groupMetrics);
                if (block.rows.isEmpty()) continue;
                if (!blocks.isEmpty()) rowWidth += config.groupGapPx();
                rowWidth += block.width;
                rowHeight = Math.max(rowHeight, block.height);
                blocks.add(block);
            }
            if (!blocks.isEmpty()) {
                if (!blockRows.isEmpty()) height += config.rowGapPx();
                width = Math.max(width, rowWidth + config.marginPx() * 2);
                height += rowHeight;
                blockRows.add(blocks);
            }
        }
        if (blockRows.isEmpty()) {
            writeOverviewSvg(out, records, config.toBuilder()
                    .groupLayoutRows(Collections.<List<String>>emptyList()).build(), report);
            return;
        }
        int exportScale = Math.max(1, Math.min(4, config.exportScale()));
        int outputWidth = scaledCanvas(width, exportScale);
        int outputHeight = scaledCanvas(height, exportScale);
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.write("<svg xmlns=\"" + SVG_NS + "\" xmlns:xlink=\"" + XLINK_NS
                + "\" width=\"" + physicalMm(outputWidth, config.outputDpi())
                + "mm\" height=\"" + physicalMm(outputHeight, config.outputDpi())
                + "mm\" viewBox=\"0 0 " + outputWidth + " " + outputHeight + "\">\n");
        out.write("<g transform=\"scale(" + exportScale + ")\">\n");
        rect(out, 0, 0, width, height, PANEL_BG);

        int y = config.marginPx();
        for (List<GroupBlock> blocks : blockRows) {
            int x = config.marginPx();
            int rowHeight = 0;
            for (GroupBlock block : blocks) rowHeight = Math.max(rowHeight, block.height);
            for (GroupBlock block : blocks) {
                writeGroupBlock(out, block, x, y, columns, config, headerFont,
                        rowFont, groupFont, report);
                x += block.width + config.groupGapPx();
            }
            y += rowHeight + config.rowGapPx();
        }
        out.write("</g>\n</svg>\n");
    }

    private static GroupBlock createGroupBlock(String group,
            List<PanelRecord> records, List<String> columns, PanelConfig config,
            FontMetrics headerMetrics, FontMetrics rowMetrics,
            FontMetrics groupMetrics) {
        List<PanelRecord> groupRecords = recordsForGroup(records, group);
        List<Row> rows = rowsForGroup(groupRecords, group);
        int cell = config.cellSizePx();
        int rowLabelGap = rows.isEmpty() || !config.rowLabelVisible()
                ? 0 : config.rowLabelGapPx();
        int rowLabelWidth = config.rowLabelVisible()
                ? rowLabelWidth(rows, rowMetrics, cell,
                        config.rowLabelOrientation(), config) : 0;
        if (rowLabelWidth <= 0) rowLabelGap = 0;
        int headerHeight = config.channelHeaderVisible()
                ? headerLabelHeight(displayColumnLabels(columns, config),
                        headerMetrics, cell, config.channelHeaderOrientation())
                        + config.channelHeaderGapPx() : 0;
        int groupHeaderHeight = config.groupHeaderVisible()
                ? groupMetrics.getHeight() + 4 : 0;
        int rowGap = Math.min(config.innerColGapPx(), config.rowGapPx());
        int width = rowLabelWidth + rowLabelGap + columns.size() * cell
                + Math.max(0, columns.size() - 1) * config.innerColGapPx();
        int height = groupHeaderHeight + headerHeight + rows.size() * cell
                + Math.max(0, rows.size() - 1) * rowGap;
        return new GroupBlock(group, rows, groupRecords, Math.max(1, width),
                Math.max(1, height), rowLabelWidth, rowLabelGap,
                headerHeight, groupHeaderHeight, rowGap);
    }

    private static void writeGroupBlock(Writer out, GroupBlock block, int x, int y,
            List<String> columns, PanelConfig config, Font headerFont,
            Font rowFont, Font groupFont, PanelWriter.WriteReport report)
            throws IOException {
        int cursorY = y;
        if (config.groupHeaderVisible()) {
            writeGroupLabel(out, block.group, x, cursorY, block.width,
                    block.groupHeaderHeight, groupFont, config);
            cursorY += block.groupHeaderHeight;
        }
        int x0 = x + block.rowLabelWidth + block.rowLabelGap;
        if (config.channelHeaderVisible()) {
            writeColumnHeaders(out, columns, x0, cursorY, config.cellSizePx(),
                    config.innerColGapPx(), block.headerHeight, headerFont,
                    config.channelHeaderOrientation(),
                    config.channelHeaderGapPx(), config);
            cursorY += block.headerHeight;
        }
        LinkedHashMap<String, PanelRecord> byKey =
                new LinkedHashMap<String, PanelRecord>();
        for (PanelRecord record : block.records) {
            byKey.put(record.imageKey() + "\n" + record.outputName(), record);
        }
        for (int r = 0; r < block.rows.size(); r++) {
            Row row = block.rows.get(r);
            if (config.rowLabelVisible()) {
                writeRowLabel(out, row.key, row.label, x, cursorY,
                        block.rowLabelWidth,
                        config.cellSizePx(), rowFont,
                        config.rowLabelOrientation(), config);
            }
            for (int c = 0; c < columns.size(); c++) {
                int cellX = x0 + c * (config.cellSizePx() + config.innerColGapPx());
                writeCell(out, byKey.get(row.key + "\n" + columns.get(c)), config,
                        cellX, cursorY, config.cellSizePx(), report);
            }
            cursorY += config.cellSizePx() + block.rowGap;
        }
    }

    private static List<Row> rowsForGroup(List<PanelRecord> records, String group) {
        LinkedHashMap<String, Row> rows = new LinkedHashMap<String, Row>();
        for (PanelRecord record : records) {
            if (!group.equals(record.group())) continue;
            if (!rows.containsKey(record.imageKey())) {
                rows.put(record.imageKey(), new Row(record.imageKey(),
                        record.imageLabel(), record.group(), record));
            }
        }
        List<Row> out = new ArrayList<Row>(rows.values());
        Collections.sort(out, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                int subject = compareText(a.record.subject(), b.record.subject());
                if (subject != 0) return subject;
                int section = compareText(a.record.section(), b.record.section());
                if (section != 0) return section;
                return compareText(a.record.imageId(), b.record.imageId());
            }
        });
        return out;
    }

    private static List<PanelRecord> recordsForGroup(List<PanelRecord> records,
            String group) {
        List<PanelRecord> out = new ArrayList<PanelRecord>();
        for (PanelRecord record : records) {
            if (group.equals(record.group())) out.add(record);
        }
        return out;
    }

    private static List<List<String>> normalizedGroupLayout(
            List<PanelRecord> records, List<List<String>> requested) {
        LinkedHashSet<String> present = new LinkedHashSet<String>();
        for (PanelRecord record : records) present.add(record.group());
        List<List<String>> rows = new ArrayList<List<String>>();
        LinkedHashSet<String> used = new LinkedHashSet<String>();
        if (requested != null) {
            for (List<String> requestedRow : requested) {
                List<String> row = new ArrayList<String>();
                if (requestedRow != null) {
                    for (String group : requestedRow) {
                        String clean = group == null ? "" : group.trim();
                        if (present.contains(clean) && used.add(clean)) row.add(clean);
                    }
                }
                if (!row.isEmpty()) rows.add(row);
            }
        }
        for (String group : present) {
            if (used.add(group)) rows.add(Collections.singletonList(group));
        }
        return rows;
    }

    private static int scaledCanvas(int dimension, int scale) {
        long result = (long) Math.max(1, dimension) * Math.max(1, scale);
        if (result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Scaled SVG dimensions are too large.");
        }
        return (int) result;
    }

    private static void checkCancelled(CancellationCheck cancelCheck)
            throws IOException {
        if (cancelCheck != null && cancelCheck.isCancelled()) {
            throw new IOException("Export cancelled.");
        }
    }

    private static final class CancellationWriter extends Writer {
        private final Writer delegate;
        private final CancellationCheck cancelCheck;

        CancellationWriter(Writer delegate, CancellationCheck cancelCheck) {
            this.delegate = delegate;
            this.cancelCheck = cancelCheck;
        }

        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            checkCancelled(cancelCheck);
            delegate.write(buffer, offset, length);
        }

        @Override
        public void write(String value, int offset, int length) throws IOException {
            checkCancelled(cancelCheck);
            delegate.write(value, offset, length);
        }

        @Override
        public void flush() throws IOException {
            checkCancelled(cancelCheck);
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static void writeCell(Writer out, PanelRecord record, PanelConfig config,
            int x, int y, int cell, PanelWriter.WriteReport report) throws IOException {
        rect(out, x, y, cell, cell, Color.BLACK);
        out.write("<rect x=\"" + x + "\" y=\"" + y + "\" width=\"" + cell
                + "\" height=\"" + cell + "\" fill=\"none\" stroke=\""
                + hex(PANEL_LINE) + "\" stroke-width=\"1\"/>\n");

        if (record == null) {
            text(out, "Not saved", x + 12, y + 24, 12, false, PANEL_HELP_TEXT);
            return;
        }
        File imageFile = record.imageFile();
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
        image(out, imageFile, image, drawX, drawY, drawW, drawH);

        if (config.annotateOverviewPanel()) {
            double recordScale = drawW / (double) Math.max(1, record.widthPx());
            writeAnnotations(out, record, config,
                    new Rectangle(drawX, drawY, drawW, drawH), recordScale,
                    Math.max(0.6, recordScale), Math.max(0.6, recordScale), report);
        }
    }

    private static void writeAnnotations(Writer out, PanelRecord record,
            PanelConfig config, Rectangle imageRect, double scaleFactor,
            double styleScale, double scaleBarThicknessScale,
            PanelWriter.WriteReport report) throws IOException {
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
                    safeThicknessScale, report);
        }
    }

    private static void writeScaleBar(Writer out, PanelRecord record,
            Rectangle imageRect, PanelConfig config, double styleScale,
            double thicknessScale, PanelWriter.WriteReport report) throws IOException {
        if (!record.calibration().isAvailable()) {
            if (report != null) report.addUncalibrated(record);
            return;
        }
        int barLengthPx = ScaleBar.lengthPixels(record.calibration(),
                record.widthPx(), record.heightPx(), imageRect.width,
                imageRect.height, config.scaleBarLengthUm());

        int inset = scaledDimension(Math.max(8, config.labelFontSizePx() / 2),
                styleScale);
        int availableWidth = imageRect.width - inset * 2;
        if (barLengthPx > availableWidth || barLengthPx < 4) {
            if (report != null) report.addScaleBarDidNotFit(record);
            return;
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
            x = horizontalPosition(config.scaleBarPosition(), imageRect, inset,
                    barLengthPx);
            y = verticalPosition(config.scaleBarPosition(), imageRect, inset,
                    thickness);
            captionBelow = isTop(config.scaleBarPosition());
        }

        String label = ScaleBar.formatLengthUm(config.scaleBarLengthUm()) + " um";
        int baseFontSize = Math.max(8,
                (int) Math.round(config.labelFontSizePx() * 0.78));
        int fontSize = scaledDimension(baseFontSize, styleScale);
        FontMetrics fm = metrics(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        ScaleBar.CaptionPlacement caption = ScaleBar.captionPlacement(imageRect,
                x, y, barLengthPx, thickness, captionBelow, label, fm,
                scaledDimension(4, styleScale), scaledDimension(3, styleScale));
        if (caption == null) {
            if (report != null) report.addScaleBarDidNotFit(record);
            return;
        }

        rect(out, x, y, barLengthPx, thickness, config.annotationColor());
        text(out, label, caption.x(), caption.baseline(), fontSize, true,
                config.annotationColor());
        if (report != null) report.addScaleBarDrawn();
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
        int rowLabelGap = config.rowLabelVisible()
                ? config.rowLabelGapPx() : 0;
        int rowLabelWidth = config.rowLabelVisible()
                ? rowLabelWidth(rows, rowFm, cell,
                        config.rowLabelOrientation(), config) : 0;
        if (rowLabelWidth <= 0) rowLabelGap = 0;
        int headerHeight = config.channelHeaderVisible()
                ? headerLabelHeight(displayColumnLabels(columns, config),
                        headerFm, cell, config.channelHeaderOrientation())
                        + config.channelHeaderGapPx() : 0;
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
            int x0, int y, int cell, int colGap, int headerHeight, Font font,
            PanelConfig.TextOrientation orientation, int gap, PanelConfig config)
            throws IOException {
        FontMetrics fm = metrics(font);
        int labelHeight = Math.max(0, headerHeight - Math.max(0, gap));
        for (int i = 0; i < columns.size(); i++) {
            int x = x0 + i * (cell + colGap);
            String key = columns.get(i);
            String label = fitSingleLine(config.externalLabelText(
                    PanelConfig.ExternalLabelKind.COLUMN, key, key), fm, cell);
            if (label.isEmpty()) continue;
            if (orientation == null
                    || orientation == PanelConfig.TextOrientation.HORIZONTAL) {
                int textX = x + Math.max(0, (cell - fm.stringWidth(label)) / 2);
                int textY = y + Math.max(fm.getAscent(),
                        labelHeight - fm.getDescent());
                text(out, label, textX, textY, font.getSize(), true, PANEL_TEXT);
            } else {
                int textWidth = fm.stringWidth(label);
                int centerY = y + Math.max(textWidth / 2,
                        labelHeight - textWidth / 2);
                rotatedText(out, label, x + cell / 2, centerY, font.getSize(),
                        true, PANEL_TEXT, orientation);
            }
        }
    }

    private static void writeGroupLabel(Writer out, String key, int x, int y,
            int width, int height, Font font, PanelConfig config)
            throws IOException {
        if (height <= 0 || width <= 0) return;
        FontMetrics fm = metrics(font);
        String label = config.externalLabelText(
                PanelConfig.ExternalLabelKind.GROUP, key, key);
        if (label.isEmpty()) return;
        String fitted = fitSingleLine(label, fm, width);
        int textX = alignedTextX(x, width, fm.stringWidth(fitted),
                config.groupHeaderAlignment());
        text(out, fitted, textX, y + 2 + fm.getAscent(),
                font.getSize(), true, PANEL_TEXT);
    }

    private static int alignedTextX(int x, int width, int textWidth,
            PanelConfig.TextAlignment alignment) {
        int remaining = Math.max(0, width - Math.max(0, textWidth));
        if (alignment == PanelConfig.TextAlignment.RIGHT) return x + remaining;
        if (alignment == PanelConfig.TextAlignment.CENTER) {
            return x + remaining / 2;
        }
        return x;
    }

    private static void writeRowLabel(Writer out, String key, String fallback,
            int x, int y,
            int width, int height, Font font,
            PanelConfig.TextOrientation orientation, PanelConfig config)
            throws IOException {
        if (width <= 0 || height <= 0) return;
        String label = config.externalLabelText(
                PanelConfig.ExternalLabelKind.ROW, key, fallback);
        if (label.isEmpty()) return;
        FontMetrics fm = metrics(font);
        if (orientation != null
                && orientation != PanelConfig.TextOrientation.HORIZONTAL) {
            rotatedText(out, fitSingleLine(label, fm, height), x + width / 2,
                    y + height / 2, font.getSize(), false, PANEL_TEXT,
                    orientation);
            return;
        }
        List<String> lines = fitWrappedLines(label, fm, width, height);
        int lineHeight = fm.getHeight();
        int totalHeight = lines.size() * lineHeight;
        int textY = y + Math.max(fm.getAscent(),
                (height - totalHeight) / 2 + fm.getAscent());
        for (String line : lines) {
            text(out, line, x + Math.max(0, width - fm.stringWidth(line)), textY,
                    font.getSize(), false, PANEL_TEXT);
            textY += lineHeight;
        }
    }

    private static void image(Writer out, File imageFile, BufferedImage image,
            int x, int y, int width, int height) throws IOException {
        byte[] pngBytes;
        if (imageFile != null && imageFile.isFile()
                && imageFile.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
            // Export preparation has already baked the locked channel ranges
            // into this full-resolution PNG. Embed those exact bytes so every
            // SVG image is pixel-identical to the individual PNG export.
            pngBytes = Files.readAllBytes(imageFile.toPath());
        } else {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", png)) {
                throw new IOException("No PNG writer available");
            }
            pngBytes = png.toByteArray();
        }
        String b64 = Base64.getEncoder().encodeToString(pngBytes);
        out.write("<image x=\"" + x + "\" y=\"" + y + "\" width=\"" + width
                + "\" height=\"" + height
                + "\" image-rendering=\"optimizeQuality"
                + "\" xlink:href=\"data:image/png;base64," + b64 + "\"/>\n");
    }

    private static void rect(Writer out, int x, int y, int width, int height,
            Color fill) throws IOException {
        out.write("<rect x=\"" + x + "\" y=\"" + y + "\" width=\"" + width
                + "\" height=\"" + height + "\" fill=\"" + hex(fill) + "\""
                + fillOpacity(fill) + "/>\n");
    }

    private static void text(Writer out, String value, int x, int y, int fontSize,
            boolean bold, Color fill) throws IOException {
        out.write("<text x=\"" + x + "\" y=\"" + y + "\" font-family=\""
                + FONT_STACK + "\" font-size=\"" + fontSize + "\"");
        if (bold) out.write(" font-weight=\"bold\"");
        out.write(" fill=\"" + hex(fill) + "\"" + fillOpacity(fill) + ">"
                + SvgEscape.text(value)
                + "</text>\n");
    }

    private static void rotatedText(Writer out, String value, int centerX,
            int centerY, int fontSize, boolean bold, Color fill,
            PanelConfig.TextOrientation orientation) throws IOException {
        int angle = orientation == PanelConfig.TextOrientation.ROTATE_RIGHT ? 90 : -90;
        out.write("<text x=\"" + centerX + "\" y=\"" + centerY
                + "\" font-family=\"" + FONT_STACK + "\" font-size=\""
                + fontSize + "\" text-anchor=\"middle\" dominant-baseline=\"middle\""
                + " transform=\"rotate(" + angle + " " + centerX + " "
                + centerY + ")\"");
        if (bold) out.write(" font-weight=\"bold\"");
        out.write(" fill=\"" + hex(fill) + "\"" + fillOpacity(fill) + ">"
                + SvgEscape.text(value) + "</text>\n");
    }

    private static int headerLabelHeight(List<String> labels, FontMetrics fm,
            int cell, PanelConfig.TextOrientation orientation) {
        if (orientation == null
                || orientation == PanelConfig.TextOrientation.HORIZONTAL) {
            return fm.getHeight();
        }
        int height = fm.getHeight();
        for (String label : labels) {
            height = Math.max(height,
                    fm.stringWidth(fitSingleLine(label, fm, cell)));
        }
        return height;
    }

    private static int rowLabelWidth(List<Row> rows, FontMetrics fm, int cell,
            PanelConfig.TextOrientation orientation, PanelConfig config) {
        if (rows == null || rows.isEmpty()) return 0;
        if (orientation != null
                && orientation != PanelConfig.TextOrientation.HORIZONTAL) {
            return fm.getHeight();
        }
        return tightRowLabelWidth(rows, fm, cell, config);
    }

    private static List<String> displayColumnLabels(List<String> columns,
            PanelConfig config) {
        List<String> labels = new ArrayList<String>();
        for (String column : columns) {
            labels.add(config.externalLabelText(
                    PanelConfig.ExternalLabelKind.COLUMN, column, column));
        }
        return labels;
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
            int cell, PanelConfig config) {
        int maxTextWidth = 0;
        int longestWordWidth = 0;
        for (Row row : rows) {
            String source = row == null ? "" : config.externalLabelText(
                    PanelConfig.ExternalLabelKind.ROW, row.key, row.label).trim();
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
            if (rowLabelsFit(rows, fm, width, availableHeight, config)) return width;
        }
        return maxWidth;
    }

    private static boolean rowLabelsFit(List<Row> rows, FontMetrics fm,
            int width, int height, PanelConfig config) {
        int lineHeight = fm.getHeight();
        int maxLines = Math.max(1, height / Math.max(1, lineHeight));
        for (Row row : rows) {
            String label = row == null ? "" : config.externalLabelText(
                    PanelConfig.ExternalLabelKind.ROW, row.key, row.label);
            List<String> lines = wrap(label, fm, width);
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

    private static String hex(Color color) {
        Color safe = color == null ? Color.BLACK : color;
        return String.format(Locale.US, "#%02X%02X%02X",
                safe.getRed(), safe.getGreen(), safe.getBlue());
    }

    private static String fillOpacity(Color color) {
        Color safe = color == null ? Color.BLACK : color;
        if (safe.getAlpha() == 255) return "";
        return " fill-opacity=\"" + String.format(Locale.US, "%.6f",
                safe.getAlpha() / 255.0) + "\"";
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

    private static final class GroupBlock {
        final String group;
        final List<Row> rows;
        final List<PanelRecord> records;
        final int width;
        final int height;
        final int rowLabelWidth;
        final int rowLabelGap;
        final int headerHeight;
        final int groupHeaderHeight;
        final int rowGap;

        GroupBlock(String group, List<Row> rows, List<PanelRecord> records,
                int width, int height, int rowLabelWidth, int rowLabelGap,
                int headerHeight, int groupHeaderHeight, int rowGap) {
            this.group = group;
            this.rows = rows;
            this.records = records;
            this.width = width;
            this.height = height;
            this.rowLabelWidth = rowLabelWidth;
            this.rowLabelGap = rowLabelGap;
            this.headerHeight = headerHeight;
            this.groupHeaderHeight = groupHeaderHeight;
            this.rowGap = rowGap;
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
