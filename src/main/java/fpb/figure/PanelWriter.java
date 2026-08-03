/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.figure;

import fpb.util.CsvSupport;
import fpb.util.IoUtils;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.measure.Calibration;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/** Composes panel grids and writes annotated raster outputs. */
public final class PanelWriter {

    private static final Color PANEL_BG = Color.WHITE;
    private static final Color PANEL_LINE = new Color(210, 210, 210);
    private static final Color PANEL_TEXT = new Color(35, 35, 35);
    private static final Color PANEL_HELP_TEXT = new Color(90, 90, 90);
    private static final int MAX_ANNOTATION_PREVIEW_DIMENSION = 640;

    private PanelWriter() {}

    public static WriteReport writeRequestedOutputs(File annotatedPanelsDir,
            File panelsDir, File manifestFile, List<PanelRecord> records,
            PanelConfig config) throws IOException {
        WriteReport report = new WriteReport();
        if (records == null || records.isEmpty() || config == null) return report;

        if (config.annotateIndividualPanels() && annotatedPanelsDir != null) {
            writeAnnotatedPanelCopies(annotatedPanelsDir, records, config, report);
        }

        if (manifestFile != null) writeManifest(manifestFile, records);

        if (config.createOverviewPanel() && panelsDir != null) {
            File out = new File(panelsDir, "Panel_Overview_"
                    + (config.groupRowsBy() == PanelConfig.GroupRowsBy.SUBJECT
                    ? "BySubject" : "ByGroup") + ".png");
            report.merge(writeOverviewPanel(out, records, config));
        }
        return report;
    }

    public static void writeManifest(File manifest, List<PanelRecord> records)
            throws IOException {
        File parent = manifest.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);

        File temp = tempFileFor(manifest);
        boolean moved = false;
        try {
            PrintWriter pw = CsvSupport.newWriter(temp);
            try {
                pw.println(CsvSupport.joinRow(Arrays.asList(
                        "Group", "Subject", "Section",
                        "OutputName", "ChannelName", "ChannelIndex",
                        "ImagePath", "AnnotatedImagePath",
                        "WidthPx", "HeightPx", "PixelWidthUm", "PixelHeightUm",
                        "CalibrationSource", "SourceImageId")));
                for (PanelRecord record : safeRecords(records)) {
                    pw.println(CsvSupport.joinRow(Arrays.asList(
                            record.group(),
                            record.subject(),
                            record.section(),
                            record.outputName(),
                            record.channelName(),
                            String.valueOf(record.channelIndex()),
                            absolutePath(record.imageFile()),
                            absolutePath(record.annotatedImageFile()),
                            String.valueOf(record.widthPx()),
                            String.valueOf(record.heightPx()),
                            formatNumber(record.pixelWidthUm()),
                            formatNumber(record.pixelHeightUm()),
                            record.calibrationSource().name(),
                            record.imageId())));
                }
            } finally {
                pw.close();
            }
            moveAtomically(temp.toPath(), manifest.toPath());
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temp.toPath());
        }
    }

    public static void writeAnnotatedPanelCopies(File annotatedRoot,
            List<PanelRecord> records, PanelConfig config, WriteReport report)
            throws IOException {
        IoUtils.mustMkdirs(annotatedRoot);
        WriteReport safeReport = report == null ? new WriteReport() : report;
        for (PanelRecord record : safeRecords(records)) {
            File source = record.imageFile();
            if (source == null || !source.isFile()) continue;
            BufferedImage image = ImageIO.read(source);
            if (image == null) continue;

            BufferedImage annotated = toArgb(image);
            Graphics2D g = annotated.createGraphics();
            try {
                applyQualityHints(g);
                Rectangle imageRect = new Rectangle(0, 0,
                        annotated.getWidth(), annotated.getHeight());
                drawAnnotations(g, record, config, imageRect, 1.0,
                        1.0, 1.0, safeReport);
            } finally {
                g.dispose();
            }

            File groupDir = new File(annotatedRoot, safeFileBase(record.group(), "Group"));
            IoUtils.mustMkdirs(groupDir);
            File out = new File(groupDir, source.getName());
            writePngAtomically(annotated, out, config.outputDpi());
            record.setAnnotatedImageFile(out);
        }
    }

    public static WriteReport writeOverviewPanel(File outputFile,
            List<PanelRecord> records, PanelConfig config) throws IOException {
        WriteReport report = new WriteReport();
        BufferedImage image = renderOverviewPanel(records, config, report);
        File parent = outputFile.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        writePngAtomically(image, outputFile, config.outputDpi());
        return report;
    }

    public static BufferedImage renderOverviewPanel(List<PanelRecord> records,
            PanelConfig config) throws IOException {
        return renderOverviewPanel(records, config, new WriteReport());
    }

    public static BufferedImage renderOverviewPanel(List<PanelRecord> records,
            PanelConfig config, WriteReport report) throws IOException {
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

        int cell = config.cellSizePx();
        int groupCount = groupCount(rows);
        TileLayout layout = createTileLayout(columns, rows, cell, groupCount,
                headerFont, rowFont, groupFont, config);

        BufferedImage panel = new BufferedImage(layout.width, layout.height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = panel.createGraphics();
        try {
            applyQualityHints(g);
            g.setColor(PANEL_BG);
            g.fillRect(0, 0, layout.width, layout.height);

            int x0 = layout.margin + layout.rowLabelWidth + layout.rowLabelGap;
            int y = layout.margin;
            if (config.channelHeaderVisible()) {
                drawColumnHeaders(g, columns, x0, y, cell, layout.colGap,
                        layout.headerHeight, headerFont);
                y += layout.headerHeight;
            }

            String lastGroup = null;
            for (int r = 0; r < rows.size(); r++) {
                Row row = rows.get(r);
                if (config.groupHeaderVisible()
                        && !row.groupLabel.equals(lastGroup)) {
                    drawGroupLabel(g, row.groupLabel, layout.margin, y,
                            layout.width - layout.margin * 2,
                            layout.groupHeaderHeight, groupFont);
                    y += layout.groupHeaderHeight;
                    lastGroup = row.groupLabel;
                }

                drawRowLabel(g, row.label, layout.margin, y,
                        layout.rowLabelWidth, cell, rowFont);
                for (int c = 0; c < columns.size(); c++) {
                    int x = x0 + c * (cell + layout.colGap);
                    PanelRecord record = byRowAndColumn.get(
                            row.key + "\n" + columns.get(c));
                    drawCell(g, record, config, x, y, cell, report);
                }
                y += cell;
                if (r < rows.size() - 1) y += layout.rowGap;
            }
        } finally {
            g.dispose();
        }
        return panel;
    }

    public static BufferedImage renderAnnotationPreview(PanelConfig config) {
        return renderAnnotationPreview(config, null);
    }

    public static BufferedImage renderAnnotationPreview(PanelConfig config,
            PanelRecord representative) {
        int sourceW = representative == null ? 420 : representative.widthPx();
        int sourceH = representative == null ? 260 : representative.heightPx();
        double previewScale = previewScale(sourceW, sourceH);
        int w = Math.max(1, (int) Math.round(sourceW * previewScale));
        int h = Math.max(1, (int) Math.round(sourceH * previewScale));

        BufferedImage preview = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = preview.createGraphics();
        try {
            applyQualityHints(g);
            drawSyntheticPreviewBackground(g, w, h);
            PanelRecord record = previewRecord(representative, sourceW, sourceH);
            drawAnnotations(g, record, config, new Rectangle(0, 0, w, h),
                    previewScale, previewScale, previewScale, new WriteReport());
        } finally {
            g.dispose();
        }
        return preview;
    }

    public static void drawAnnotations(Graphics2D g, PanelRecord record,
            PanelConfig config, Rectangle imageRect, double scaleFactor) {
        drawAnnotations(g, record, config, imageRect, scaleFactor,
                1.0, Math.max(0.6, scaleFactor), new WriteReport());
    }

    public static void drawAnnotations(Graphics2D g, PanelRecord record,
            PanelConfig config, Rectangle imageRect, double scaleFactor,
            double styleScale, double scaleBarThicknessScale, WriteReport report) {
        if (record == null || config == null || imageRect == null) return;
        double safeStyleScale = styleScale > 0.0 && Double.isFinite(styleScale)
                ? styleScale : 1.0;
        double safeThicknessScale = scaleBarThicknessScale > 0.0
                && Double.isFinite(scaleBarThicknessScale)
                ? scaleBarThicknessScale : safeStyleScale;
        if (config.labelMode() != PanelConfig.LabelMode.NONE) {
            String label = labelText(record, config);
            if (!label.isEmpty()) {
                drawTextLabel(g, label, imageRect, config.labelPosition(),
                        config.labelFontSizePx(), config.annotationColor(),
                        safeStyleScale,
                        config.hasLabelFraction() ? config.labelFracX() : -1.0,
                        config.hasLabelFraction() ? config.labelFracY() : -1.0);
            }
        }
        if (config.scaleBarEnabled()) {
            ScaleBar.draw(g, record, imageRect, config, safeStyleScale,
                    safeThicknessScale, report);
        }
    }

    public static void writePngAtomically(BufferedImage image, File outputFile)
            throws IOException {
        writePngAtomically(image, outputFile, 0);
    }

    public static void writePngAtomically(BufferedImage image, File outputFile,
            int dpi) throws IOException {
        File parent = outputFile.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        File temp = tempFileFor(outputFile);
        boolean moved = false;
        try {
            writePng(image, temp, dpi);
            moveAtomically(temp.toPath(), outputFile.toPath());
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temp.toPath());
        }
    }

    public static void writeTiffAtomically(BufferedImage image, File outputFile,
            int dpi) throws IOException {
        File parent = outputFile.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        File temp = tempFileFor(outputFile);
        boolean moved = false;
        try {
            writeTiff(image, temp, dpi);
            moveAtomically(temp.toPath(), outputFile.toPath());
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temp.toPath());
        }
    }

    public static void applyQualityHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.setStroke(new BasicStroke(1f));
    }

    private static void drawCell(Graphics2D g, PanelRecord record,
            PanelConfig config, int x, int y, int cell, WriteReport report) {
        g.setColor(Color.BLACK);
        g.fillRect(x, y, cell, cell);
        g.setColor(PANEL_LINE);
        g.drawRect(x, y, cell, cell);

        if (record == null) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(PANEL_HELP_TEXT);
            g.drawString("Not saved", x + 12, y + 24);
            return;
        }

        File imageFile = record.preferredImageFile(config.annotateIndividualPanels());
        BufferedImage image = null;
        try {
            image = imageFile == null ? null : ImageIO.read(imageFile);
        } catch (IOException ignored) {
            image = null;
        }
        if (image == null) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(PANEL_HELP_TEXT);
            g.drawString("Missing image", x + 12, y + 24);
            return;
        }

        double scale = Math.min((double) cell / image.getWidth(),
                (double) cell / image.getHeight());
        int drawW = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int drawH = Math.max(1, (int) Math.round(image.getHeight() * scale));
        int drawX = x + (cell - drawW) / 2;
        int drawY = y + (cell - drawH) / 2;
        g.drawImage(image, drawX, drawY, drawW, drawH, null);

        if (config.annotateOverviewPanel()
                && !config.annotateIndividualPanels()) {
            double recordScale = drawW / (double) Math.max(1, record.widthPx());
            drawAnnotations(g, record, config,
                    new Rectangle(drawX, drawY, drawW, drawH), recordScale,
                    Math.max(0.6, recordScale), Math.max(0.6, recordScale),
                    report);
        }
    }

    private static TileLayout createTileLayout(List<String> columns, List<Row> rows,
            int cell, int groupCount, Font headerFont, Font rowFont,
            Font groupFont, PanelConfig config) {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        try {
            applyQualityHints(g);
            FontMetrics headerFm = g.getFontMetrics(headerFont);
            FontMetrics rowFm = g.getFontMetrics(rowFont);
            FontMetrics groupFm = g.getFontMetrics(groupFont);
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
        } finally {
            g.dispose();
        }
    }

    private static void writePng(BufferedImage image, File outputFile, int dpi)
            throws IOException {
        if (dpi <= 0) {
            if (!ImageIO.write(image, "png", outputFile)) {
                throw new IOException("No PNG writer available");
            }
            return;
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) throw new IOException("No PNG writer available");
        ImageWriter writer = writers.next();
        ImageOutputStream output = null;
        try {
            output = ImageIO.createImageOutputStream(outputFile);
            if (output == null) {
                throw new IOException("Could not open " + outputFile.getAbsolutePath());
            }
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            IIOMetadata metadata = writer.getDefaultImageMetadata(
                    ImageTypeSpecifier.createFromRenderedImage(image), param);
            setPngDpi(metadata, dpi);
            writer.write(null, new IIOImage(image, null, metadata), param);
        } finally {
            writer.dispose();
            if (output != null) output.close();
        }
    }

    private static void writeTiff(BufferedImage image, File outputFile, int dpi)
            throws IOException {
        if (image == null) throw new IOException("image is null");
        ImagePlus imagePlus = new ImagePlus(outputFile.getName(), image);
        if (dpi > 0) {
            Calibration calibration = imagePlus.getCalibration();
            double pixelsPerCm = dpi / 2.54d;
            calibration.setUnit("cm");
            calibration.pixelWidth = 1.0 / pixelsPerCm;
            calibration.pixelHeight = 1.0 / pixelsPerCm;
            imagePlus.setCalibration(calibration);
        }
        boolean saved = new FileSaver(imagePlus).saveAsTiff(outputFile.getAbsolutePath());
        imagePlus.changes = false;
        imagePlus.close();
        imagePlus.flush();
        if (!saved || !outputFile.isFile()) {
            throw new IOException("Could not write TIFF: " + outputFile.getAbsolutePath());
        }
    }

    private static void setPngDpi(IIOMetadata metadata, int dpi) throws IOException {
        if (metadata == null || metadata.isReadOnly()) return;
        int pixelsPerMeter = Math.max(1, (int) Math.round(dpi / 0.0254d));
        IIOMetadataNode root = new IIOMetadataNode("javax_imageio_png_1.0");
        IIOMetadataNode phys = new IIOMetadataNode("pHYs");
        phys.setAttribute("pixelsPerUnitXAxis", String.valueOf(pixelsPerMeter));
        phys.setAttribute("pixelsPerUnitYAxis", String.valueOf(pixelsPerMeter));
        phys.setAttribute("unitSpecifier", "meter");
        root.appendChild(phys);
        try {
            metadata.mergeTree("javax_imageio_png_1.0", root);
        } catch (RuntimeException e) {
            throw new IOException("Could not write PNG DPI metadata.", e);
        }
    }

    private static PanelRecord previewRecord(PanelRecord representative,
            int width, int height) {
        if (representative == null) {
            return new PanelRecord(null, "Group1", "Subject1", "Section1",
                    "DAPI", "DAPI", 0, width, height, 0.5, 0.5,
                    CalibrationCheck.CalibrationSource.USER_ENTERED);
        }
        return new PanelRecord(null, representative.group(), representative.subject(),
                representative.section(), representative.imageId(),
                representative.outputName(), representative.channelName(),
                representative.channelIndex(), width, height,
                representative.pixelWidthUm(), representative.pixelHeightUm(),
                representative.calibrationSource());
    }

    private static double previewScale(int width, int height) {
        int maxDimension = Math.max(width, height);
        if (maxDimension <= MAX_ANNOTATION_PREVIEW_DIMENSION) return 1.0;
        return (double) MAX_ANNOTATION_PREVIEW_DIMENSION / maxDimension;
    }

    private static void drawSyntheticPreviewBackground(Graphics2D g,
            int width, int height) {
        g.setColor(new Color(18, 18, 20));
        g.fillRect(0, 0, width, height);
        g.setPaint(new java.awt.GradientPaint(0, 0, new Color(12, 26, 55),
                width, height, new Color(15, 90, 82)));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(70, 140, 180, 90));
        int spot = Math.max(12, Math.min(width, height) / 18);
        int step = Math.max(spot * 2, Math.min(width, height) / 8);
        for (int yy = step / 2; yy < height; yy += step) {
            for (int xx = step / 2; xx < width; xx += step) {
                g.fillOval(xx - spot / 2, yy - spot / 2, spot, spot);
            }
        }
    }

    private static void drawColumnHeaders(Graphics2D g, List<String> columns,
            int x0, int y, int cell, int colGap, int headerHeight, Font font) {
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < columns.size(); i++) {
            int x = x0 + i * (cell + colGap);
            g.setColor(PANEL_TEXT);
            String label = fitSingleLine(columns.get(i), fm, cell);
            int textX = x + Math.max(0, (cell - fm.stringWidth(label)) / 2);
            int textY = y + Math.max(fm.getAscent(),
                    (headerHeight + fm.getAscent()) / 2 - 1);
            g.drawString(label, textX, textY);
        }
    }

    private static void drawGroupLabel(Graphics2D g, String label, int x, int y,
            int width, int height, Font font) {
        if (height <= 0 || width <= 0) return;
        g.setFont(font);
        g.setColor(PANEL_TEXT);
        FontMetrics fm = g.getFontMetrics();
        String fitted = fitSingleLine(label, fm, width);
        g.drawString(fitted, x, y + 2 + fm.getAscent());
    }

    private static void drawRowLabel(Graphics2D g, String label, int x, int y,
            int width, int height, Font font) {
        if (width <= 0 || height <= 0) return;
        g.setFont(font);
        g.setColor(PANEL_TEXT);
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = fitWrappedLines(label, fm, width, height);
        int lineHeight = fm.getHeight();
        int totalHeight = lines.size() * lineHeight;
        int textY = y + Math.max(fm.getAscent(),
                (height - totalHeight) / 2 + fm.getAscent());
        for (String line : lines) {
            g.drawString(line, x, textY);
            textY += lineHeight;
        }
    }

    private static void drawTextLabel(Graphics2D g, String text,
            Rectangle imageRect, PanelConfig.Position position, int fontSize,
            Color color, double styleScale, double fracX, double fracY) {
        Font font = new Font(Font.SANS_SERIF, Font.BOLD,
                scaledDimension(fontSize, styleScale));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int inset = scaledDimension(Math.max(8, fontSize / 2), styleScale);
        int textW = fm.stringWidth(text);
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
        g.setColor(color);
        g.drawString(text, textX, textY);
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

    private static BufferedImage toArgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) return image;
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
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

    private static boolean isTop(PanelConfig.Position position) {
        return position == PanelConfig.Position.TOP_LEFT
                || position == PanelConfig.Position.TOP_RIGHT;
    }

    private static int scaledDimension(int value, double scale) {
        double safeScale = scale > 0.0 && Double.isFinite(scale) ? scale : 1.0;
        return Math.max(1, (int) Math.round(value * safeScale));
    }

    private static int compareText(String a, String b) {
        String aa = a == null ? "" : a;
        String bb = b == null ? "" : b;
        return aa.compareToIgnoreCase(bb);
    }

    private static String absolutePath(File file) {
        return file == null ? "" : file.getAbsolutePath();
    }

    private static String formatNumber(double value) {
        return Double.isFinite(value) ? String.valueOf(value) : "";
    }

    static String safeFileBase(String value, String fallback) {
        String source = value == null ? "" : value.trim();
        if (source.isEmpty()) source = fallback == null ? "" : fallback.trim();
        if (source.isEmpty()) source = "File";
        String safe = source.replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("_+", "_");
        while (safe.startsWith(".")) safe = safe.substring(1);
        if (safe.isEmpty()) safe = "File";
        return safe.length() > 140 ? safe.substring(0, 140) : safe;
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
        String name = target == null ? "panel" : target.getName();
        String clean = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.length() < 3 ? "tmp" + clean : clean;
    }

    public static final class WriteReport {
        private final LinkedHashSet<String> uncalibratedImages =
                new LinkedHashSet<String>();

        void addUncalibrated(PanelRecord record) {
            if (record == null) return;
            File image = record.imageFile();
            String name = image == null ? record.imageKey() : image.getAbsolutePath();
            if (name != null && !name.trim().isEmpty()) {
                uncalibratedImages.add(name.trim());
            }
        }

        void merge(WriteReport other) {
            if (other != null) uncalibratedImages.addAll(other.uncalibratedImages);
        }

        public List<String> uncalibratedImages() {
            return Collections.unmodifiableList(
                    new ArrayList<String>(uncalibratedImages));
        }

        public boolean hasUnavailableScaleBars() {
            return !uncalibratedImages.isEmpty();
        }
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
