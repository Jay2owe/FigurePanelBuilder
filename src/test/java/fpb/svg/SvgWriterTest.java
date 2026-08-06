/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.svg;

import fpb.figure.CalibrationCheck;
import fpb.figure.PanelConfig;
import fpb.figure.PanelRecord;
import fpb.figure.PanelWriter;
import fpb.util.CancellationCheck;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SvgWriterTest {

    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void svgParsesAndKeepsLabelsAsText() throws Exception {
        File source = writeSource("panel.png", Color.BLUE, 64, 48);
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(100)
                .labelMode(PanelConfig.LabelMode.CUSTOM)
                .customLabelTemplate("A & <B> \"beta\" β")
                .scaleBarLengthUm(10.0)
                .scaleBarThicknessPx(2)
                .channelOrder(Arrays.asList("DAPI"))
                .build();

        Document doc = parse(SvgWriter.renderOverviewSvg(
                Arrays.asList(record(source, "Control", "S1", "sec1", "DAPI")),
                config));

        NodeList text = doc.getElementsByTagNameNS(SVG_NS, "text");
        assertTrue(text.getLength() >= 1);
        assertEquals(0, doc.getElementsByTagNameNS(SVG_NS, "path").getLength());
        assertTrue(containsText(text, "A & <B> \"beta\" β"));
    }

    @Test
    public void tinyValidScaleBarKeepsANonZeroPhysicalLabel() throws Exception {
        File source = writeSource("tiny-scale.png", Color.BLACK, 100, 100);
        PanelRecord record = new PanelRecord(source, "Control", "S1", "",
                "DAPI", "DAPI", 0, 100, 100, 0.00004, 0.00004,
                CalibrationCheck.CalibrationSource.USER_ENTERED);
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(100)
                .scaleBarLengthUm(0.0004)
                .channelOrder(Arrays.asList("DAPI"))
                .build();

        String svg = SvgWriter.renderOverviewSvg(Arrays.asList(record), config);

        assertTrue(svg.contains(">0.0004 um</text>"));
        assertFalse(svg.contains(">0.00 um</text>"));
        assertEquals("0.0004", fpb.figure.ScaleBar.formatLengthUm(0.0004));
    }

    @Test
    public void embedsPanelsAsBase64PngDataUris() throws Exception {
        File source = writeSource("panel.png", Color.RED, 32, 24);
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(80)
                .scaleBarEnabled(false)
                .channelOrder(Arrays.asList("Merge"))
                .build();

        Document doc = parse(SvgWriter.renderOverviewSvg(
                Arrays.asList(record(source, "Control", "S1", "sec1", "Merge")),
                config));

        NodeList images = doc.getElementsByTagNameNS(SVG_NS, "image");
        assertEquals(1, images.getLength());
        Element image = (Element) images.item(0);
        String href = image.getAttributeNS(XLINK_NS, "href");
        assertTrue(href.startsWith("data:image/png;base64,"));
        byte[] bytes = Base64.getDecoder().decode(
                href.substring("data:image/png;base64,".length()));
        assertEquals((byte) 0x89, bytes[0]);
        assertEquals((byte) 'P', bytes[1]);
        assertEquals((byte) 'N', bytes[2]);
        assertEquals((byte) 'G', bytes[3]);
        BufferedImage embedded = ImageIO.read(new ByteArrayInputStream(bytes));
        assertEquals(32, embedded.getWidth());
        assertEquals(24, embedded.getHeight());
        assertEquals("optimizeQuality", image.getAttribute("image-rendering"));
    }

    @Test
    public void svgEmbedsFullResolutionPanelAtLayoutDisplaySize() throws Exception {
        File source = temp.newFile("puncta-panel.png");
        BufferedImage puncta = new BufferedImage(160, 80,
                BufferedImage.TYPE_INT_RGB);
        puncta.setRGB(1, 1, Color.WHITE.getRGB());
        assertTrue(ImageIO.write(puncta, "png", source));
        PanelRecord record = new PanelRecord(source, "Control", "S1", "",
                "DAPI", "DAPI", 0, 160, 80, 0.5, 0.5,
                CalibrationCheck.CalibrationSource.USER_ENTERED);
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(80)
                .marginPx(0)
                .groupHeaderVisible(false)
                .channelHeaderVisible(false)
                .labelMode(PanelConfig.LabelMode.NONE)
                .scaleBarEnabled(false)
                .channelOrder(Arrays.asList("DAPI"))
                .build();

        Document svg = parse(SvgWriter.renderOverviewSvg(
                Arrays.asList(record), config));
        Element image = (Element) svg.getElementsByTagNameNS(SVG_NS, "image")
                .item(0);
        assertEquals("80", image.getAttribute("width"));
        assertEquals("40", image.getAttribute("height"));
        String href = image.getAttributeNS(XLINK_NS, "href");
        BufferedImage embedded = ImageIO.read(new ByteArrayInputStream(
                Base64.getDecoder().decode(href.substring(
                        "data:image/png;base64,".length()))));

        assertEquals(160, embedded.getWidth());
        assertEquals(80, embedded.getHeight());
        assertEquals(Color.WHITE.getRGB(), embedded.getRGB(1, 1));
        for (int y = 0; y < embedded.getHeight(); y++) {
            for (int x = 0; x < embedded.getWidth(); x++) {
                assertEquals(puncta.getRGB(x, y), embedded.getRGB(x, y));
            }
        }
    }

    @Test
    public void externalLabelsCanAllBeRemovedFromSvg() throws Exception {
        File source = writeSource("no-external-labels.png", Color.BLUE, 40, 40);
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(80)
                .groupHeaderVisible(false)
                .channelHeaderVisible(false)
                .rowLabelVisible(false)
                .labelMode(PanelConfig.LabelMode.NONE)
                .scaleBarEnabled(false)
                .channelOrder(Arrays.asList("DAPI"))
                .build();

        Document svg = parse(SvgWriter.renderOverviewSvg(Arrays.asList(
                record(source, "Control", "S1", "Section 1", "DAPI")), config));

        assertEquals(0, svg.getElementsByTagNameNS(SVG_NS, "text").getLength());
    }

    @Test
    public void annotationAlphaIsPreservedAsSvgFillOpacity() throws Exception {
        File source = writeSource("alpha-panel.png", Color.BLUE, 64, 48);
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(100)
                .labelMode(PanelConfig.LabelMode.CUSTOM)
                .customLabelTemplate("alpha label")
                .annotationColor(new Color(12, 34, 56, 64))
                .scaleBarEnabled(false)
                .channelOrder(Arrays.asList("DAPI"))
                .build();

        Document doc = parse(SvgWriter.renderOverviewSvg(
                Arrays.asList(record(source, "Control", "S1", "", "DAPI")),
                config));
        NodeList text = doc.getElementsByTagNameNS(SVG_NS, "text");
        Element annotation = null;
        for (int i = 0; i < text.getLength(); i++) {
            if ("alpha label".equals(text.item(i).getTextContent())) {
                annotation = (Element) text.item(i);
            }
        }

        assertTrue(annotation != null);
        assertEquals("#0C2238", annotation.getAttribute("fill"));
        assertEquals(64.0 / 255.0,
                Double.parseDouble(annotation.getAttribute("fill-opacity")),
                0.000001);
    }

    @Test
    public void physicalSizeMatchesRasterViewBoxAtConfiguredDpi()
            throws Exception {
        File source = writeSource("panel.png", Color.GREEN, 40, 40);
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(100)
                .outputDpi(254)
                .exportScale(2)
                .scaleBarEnabled(false)
                .channelHeaderVisible(false)
                .groupHeaderVisible(false)
                .channelOrder(Arrays.asList("DAPI"))
                .build();
        PanelRecord record = record(source, "Control", "S1", "", "DAPI");

        BufferedImage raster = PanelWriter.renderOverviewPanel(
                Arrays.asList(record), config, new PanelWriter.WriteReport(), 2);
        Document doc = parse(SvgWriter.renderOverviewSvg(Arrays.asList(record), config));
        Element root = doc.getDocumentElement();

        assertEquals("0 0 " + raster.getWidth() + " " + raster.getHeight(),
                root.getAttribute("viewBox"));
        assertEquals(mm(raster.getWidth(), 254) + "mm", root.getAttribute("width"));
        assertEquals(mm(raster.getHeight(), 254) + "mm", root.getAttribute("height"));
        NodeList groups = doc.getElementsByTagNameNS(SVG_NS, "g");
        assertEquals("scale(2)", ((Element) groups.item(0)).getAttribute("transform"));
    }

    @Test
    public void writesCompleteSvgFile() throws Exception {
        File source = writeSource("panel.png", Color.BLACK, 20, 20);
        File output = temp.newFile("figure.svg");
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(80)
                .scaleBarEnabled(false)
                .build();

        SvgWriter.writeOverviewSvg(output,
                Arrays.asList(record(source, "Control", "S1", "", "DAPI")),
                config);

        assertTrue(output.isFile());
        assertFalse(parse(new String(java.nio.file.Files.readAllBytes(output.toPath()),
                java.nio.charset.StandardCharsets.UTF_8))
                .getElementsByTagNameNS(SVG_NS, "svg").getLength() == 0);
    }

    @Test
    public void svgCompositionPollsCancellationAndDoesNotCommitPartialFile()
            throws Exception {
        File source = writeSource("cancel-svg-panel.png", Color.BLACK, 20, 20);
        File output = new File(temp.getRoot(), "cancelled.svg");
        try {
            SvgWriter.writeOverviewSvg(output,
                    Arrays.asList(record(source, "Control", "S1", "", "DAPI")),
                    PanelConfig.builder().cellSizePx(80).build(),
                    new CancellationCheck() {
                        @Override
                        public boolean isCancelled() {
                            return true;
                        }
                    });
            throw new AssertionError("Expected SVG cancellation");
        } catch (java.io.IOException expected) {
            assertEquals("Export cancelled.", expected.getMessage());
        }
        assertFalse(output.exists());
    }

    @Test
    public void svgGroupLayoutAndScaleMatchRasterCanvas() throws Exception {
        File control = writeSource("control.png", Color.BLUE, 40, 30);
        File drug = writeSource("drug.png", Color.RED, 40, 30);
        PanelRecord first = record(control, "Control", "S1", "", "DAPI");
        PanelRecord second = record(drug, "DrugA", "S2", "", "DAPI");
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(90)
                .scaleBarEnabled(false)
                .exportScale(2)
                .channelOrder(Arrays.asList("DAPI"))
                .channelFontSizePx(23)
                .rowFontSizePx(19)
                .channelHeaderOrientation(PanelConfig.TextOrientation.ROTATE_LEFT)
                .rowLabelOrientation(PanelConfig.TextOrientation.ROTATE_RIGHT)
                .channelHeaderGapPx(13)
                .rowLabelGapPx(11)
                .externalLabelOverride(PanelConfig.ExternalLabelKind.GROUP,
                        "Control", "Vehicle")
                .externalLabelOverride(PanelConfig.ExternalLabelKind.COLUMN,
                        "DAPI", "Nuclei")
                .externalLabelOverride(PanelConfig.ExternalLabelKind.ROW,
                        first.imageKey(), "A1")
                .groupLayoutRows(Arrays.asList(Arrays.asList("Control"),
                        Arrays.asList("DrugA")))
                .build();

        BufferedImage raster = PanelWriter.renderOverviewPanel(
                Arrays.asList(first, second), config,
                new PanelWriter.WriteReport(), 2);
        Document doc = parse(SvgWriter.renderOverviewSvg(
                Arrays.asList(first, second), config));

        assertEquals("0 0 " + raster.getWidth() + " " + raster.getHeight(),
                doc.getDocumentElement().getAttribute("viewBox"));
        String xml = SvgWriter.renderOverviewSvg(Arrays.asList(first, second), config);
        assertTrue(xml.contains("rotate(-90"));
        assertTrue(xml.contains("rotate(90"));
        assertTrue(xml.contains(">Vehicle</text>"));
        assertTrue(xml.contains(">Nuclei</text>"));
        assertTrue(xml.contains(">A1</text>"));
    }

    @Test
    public void svgGroupAlignmentMovesEditableGroupText() throws Exception {
        File source = writeSource("aligned-group.png", Color.BLUE, 40, 30);
        PanelRecord record = record(source, "Control", "S1", "", "DAPI");
        PanelConfig left = PanelConfig.builder()
                .cellSizePx(100)
                .channelOrder(Arrays.asList("DAPI"))
                .externalLabelOverride(PanelConfig.ExternalLabelKind.GROUP,
                        "Control", "Vehicle")
                .groupHeaderAlignment(PanelConfig.TextAlignment.LEFT)
                .build();
        PanelConfig middle = left.toBuilder()
                .groupHeaderAlignment(PanelConfig.TextAlignment.CENTER).build();
        PanelConfig right = left.toBuilder()
                .groupHeaderAlignment(PanelConfig.TextAlignment.RIGHT).build();

        int leftX = Integer.parseInt(textElement(parse(
                SvgWriter.renderOverviewSvg(Arrays.asList(record), left)),
                "Vehicle").getAttribute("x"));
        int middleX = Integer.parseInt(textElement(parse(
                SvgWriter.renderOverviewSvg(Arrays.asList(record), middle)),
                "Vehicle").getAttribute("x"));
        int rightX = Integer.parseInt(textElement(parse(
                SvgWriter.renderOverviewSvg(Arrays.asList(record), right)),
                "Vehicle").getAttribute("x"));

        assertTrue(leftX < middleX);
        assertTrue(middleX < rightX);
    }

    @Test
    public void svgReportsUncalibratedScaleBarOmissions() throws Exception {
        File source = writeSource("uncalibrated-svg.png", Color.BLACK, 64, 48);
        PanelRecord record = new PanelRecord(source, source, "Control", "S1", "",
                "uncalibrated-svg.png", "DAPI", "DAPI", 0, 64, 48,
                Double.NaN, Double.NaN, CalibrationCheck.CalibrationSource.NONE);
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(100)
                .annotateOverviewPanel(true)
                .scaleBarEnabled(true)
                .scaleBarLengthUm(10.0)
                .channelOrder(Arrays.asList("DAPI"))
                .build();
        PanelWriter.WriteReport report = new PanelWriter.WriteReport();
        File output = new File(temp.getRoot(), "uncalibrated.svg");

        SvgWriter.writeOverviewSvg(output, Arrays.asList(record), config,
                fpb.util.CancellationCheck.NEVER_CANCELLED, report);

        assertEquals(Arrays.asList(source.getAbsolutePath()),
                report.uncalibratedImages());
        assertFalse(report.hasDrawnScaleBar());
    }

    @Test
    public void svgOmitsOverlongScaleBarWithoutFalsePhysicalLabel()
            throws Exception {
        File source = writeSource("overlong-svg.png", Color.BLACK, 100, 100);
        PanelRecord record = new PanelRecord(source, source, "Control", "S1", "",
                "overlong-svg.png", "DAPI", "DAPI", 0, 100, 100,
                1.0, 1.0, CalibrationCheck.CalibrationSource.USER_ENTERED);
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(100)
                .annotateOverviewPanel(true)
                .scaleBarEnabled(true)
                .scaleBarLengthUm(500.0)
                .channelOrder(Arrays.asList("DAPI"))
                .build();
        PanelWriter.WriteReport report = new PanelWriter.WriteReport();
        File output = new File(temp.getRoot(), "overlong.svg");

        SvgWriter.writeOverviewSvg(output, Arrays.asList(record), config,
                fpb.util.CancellationCheck.NEVER_CANCELLED, report);
        String svg = new String(java.nio.file.Files.readAllBytes(output.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

        assertFalse(svg.contains("500 um"));
        assertFalse(report.hasDrawnScaleBar());
        assertEquals(Arrays.asList(source.getAbsolutePath()),
                report.scaleBarsThatDidNotFit());
    }

    @Test
    public void svgReportsSubPixelCalibratedScaleBarAsNotFitting()
            throws Exception {
        File source = writeSource("sub-pixel-svg.png", Color.BLACK, 100, 100);
        PanelRecord record = new PanelRecord(source, source, "Control", "S1", "",
                "sub-pixel-svg.png", "DAPI", "DAPI", 0, 100, 100,
                1.0, 1.0, CalibrationCheck.CalibrationSource.USER_ENTERED);
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(100)
                .annotateOverviewPanel(true)
                .scaleBarEnabled(true)
                .scaleBarLengthUm(0.4)
                .channelOrder(Arrays.asList("DAPI"))
                .build();
        PanelWriter.WriteReport report = new PanelWriter.WriteReport();
        File output = new File(temp.getRoot(), "sub-pixel.svg");

        SvgWriter.writeOverviewSvg(output, Arrays.asList(record), config,
                fpb.util.CancellationCheck.NEVER_CANCELLED, report);
        String svg = new String(java.nio.file.Files.readAllBytes(output.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

        assertFalse(svg.contains("0.4 um"));
        assertTrue(report.uncalibratedImages().isEmpty());
        assertFalse(report.hasDrawnScaleBar());
        assertEquals(Arrays.asList(source.getAbsolutePath()),
                report.scaleBarsThatDidNotFit());
    }

    @Test
    public void svgOmitsScaleBarWhenItsCaptionCannotFit() throws Exception {
        double lengthUm = 1.23456789012345E-10;
        File source = writeSource("wide-caption-svg.png", Color.BLACK, 80, 80);
        PanelRecord record = new PanelRecord(source, source, "Control", "S1", "",
                "wide-caption-svg.png", "DAPI", "DAPI", 0, 80, 80,
                lengthUm / 10.0, lengthUm / 10.0,
                CalibrationCheck.CalibrationSource.USER_ENTERED);
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(80)
                .labelMode(PanelConfig.LabelMode.NONE)
                .scaleBarEnabled(true)
                .scaleBarLengthUm(lengthUm)
                .channelOrder(Arrays.asList("DAPI"))
                .build();
        PanelWriter.WriteReport report = new PanelWriter.WriteReport();
        File output = new File(temp.getRoot(), "wide-caption.svg");

        SvgWriter.writeOverviewSvg(output, Arrays.asList(record), config,
                fpb.util.CancellationCheck.NEVER_CANCELLED, report);
        String svg = new String(java.nio.file.Files.readAllBytes(output.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

        assertFalse(svg.contains(fpb.figure.ScaleBar.formatLengthUm(lengthUm)
                + " um"));
        assertTrue(report.uncalibratedImages().isEmpty());
        assertFalse(report.hasDrawnScaleBar());
        assertEquals(Arrays.asList(source.getAbsolutePath()),
                report.scaleBarsThatDidNotFit());
    }

    @Test
    public void shortScaleBarCaptionsFitInsideEveryConfiguredCorner()
            throws Exception {
        File source = writeSource("corner-caption-svg.png", Color.BLACK, 100, 100);
        PanelRecord record = new PanelRecord(source, source, "Control", "S1", "",
                "corner-caption-svg.png", "DAPI", "DAPI", 0, 100, 100,
                0.1, 0.1, CalibrationCheck.CalibrationSource.USER_ENTERED);
        for (PanelConfig.Position position : PanelConfig.Position.values()) {
            PanelConfig config = PanelConfig.builder()
                    .cellSizePx(100)
                    .labelMode(PanelConfig.LabelMode.NONE)
                    .scaleBarEnabled(true)
                    .scaleBarLengthUm(1.0)
                    .scaleBarPosition(position)
                    .channelOrder(Arrays.asList("DAPI"))
                    .build();
            PanelWriter.WriteReport report = new PanelWriter.WriteReport();
            File output = new File(temp.getRoot(), "corner-" + position + ".svg");

            SvgWriter.writeOverviewSvg(output, Arrays.asList(record), config,
                    fpb.util.CancellationCheck.NEVER_CANCELLED, report);
            Document document = parse(new String(java.nio.file.Files.readAllBytes(
                    output.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            Element image = (Element) document.getElementsByTagNameNS(SVG_NS,
                    "image").item(0);
            Element caption = textElement(document, "1 um");
            FontMetrics metrics = fontMetrics(new Font(Font.SANS_SERIF,
                    Font.BOLD, 14));
            int imageX = Integer.parseInt(image.getAttribute("x"));
            int imageY = Integer.parseInt(image.getAttribute("y"));
            int imageWidth = Integer.parseInt(image.getAttribute("width"));
            int imageHeight = Integer.parseInt(image.getAttribute("height"));
            int textX = Integer.parseInt(caption.getAttribute("x"));
            int baseline = Integer.parseInt(caption.getAttribute("y"));

            assertTrue(textX >= imageX);
            assertTrue(textX + metrics.stringWidth("1 um") <= imageX + imageWidth);
            assertTrue(baseline - metrics.getAscent() >= imageY);
            assertTrue(baseline + metrics.getDescent() <= imageY + imageHeight);
            assertTrue(report.hasDrawnScaleBar());
            assertTrue(report.scaleBarsThatDidNotFit().isEmpty());
        }
    }

    @Test
    public void largeFontBottomCaptionsKeepTheirDescentAboveTheBar()
            throws Exception {
        File source = writeSource("large-caption-svg.png", Color.BLACK, 400, 400);
        PanelRecord record = new PanelRecord(source, source, "Control", "S1", "",
                "large-caption-svg.png", "DAPI", "DAPI", 0, 400, 400,
                0.1, 0.1, CalibrationCheck.CalibrationSource.USER_ENTERED);
        PanelConfig.Position[] positions = new PanelConfig.Position[] {
                PanelConfig.Position.BOTTOM_LEFT,
                PanelConfig.Position.BOTTOM_RIGHT
        };
        for (PanelConfig.Position position : positions) {
            PanelConfig config = PanelConfig.builder()
                    .cellSizePx(400)
                    .labelMode(PanelConfig.LabelMode.NONE)
                    .labelFontSizePx(96)
                    .scaleBarEnabled(true)
                    .scaleBarLengthUm(10.0)
                    .scaleBarPosition(position)
                    .channelOrder(Arrays.asList("DAPI"))
                    .build();
            PanelWriter.WriteReport report = new PanelWriter.WriteReport();
            File output = new File(temp.getRoot(), "large-" + position + ".svg");

            SvgWriter.writeOverviewSvg(output, Arrays.asList(record), config,
                    fpb.util.CancellationCheck.NEVER_CANCELLED, report);
            Document document = parse(new String(java.nio.file.Files.readAllBytes(
                    output.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            Element caption = textElement(document, "10 um");
            Element bar = rectElement(document, "100", "6");
            FontMetrics metrics = fontMetrics(new Font(Font.SANS_SERIF,
                    Font.BOLD, 75));
            int baseline = Integer.parseInt(caption.getAttribute("y"));
            int barTop = Integer.parseInt(bar.getAttribute("y"));

            assertTrue(baseline + metrics.getDescent() + 4 <= barTop);
            assertTrue(report.hasDrawnScaleBar());
            assertTrue(report.scaleBarsThatDidNotFit().isEmpty());
        }
    }

    private File writeSource(String name, Color color, int width, int height)
            throws Exception {
        File file = temp.newFile(name);
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", file);
        return file;
    }

    private static PanelRecord record(File file, String group, String subject,
            String section, String output) {
        return new PanelRecord(file, group, subject, section, output, output,
                0, 64, 48, 0.5, 0.5,
                CalibrationCheck.CalibrationSource.USER_ENTERED);
    }

    private static Document parse(String svg) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                svg.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static Element textElement(Document document, String value) {
        NodeList text = document.getElementsByTagNameNS(SVG_NS, "text");
        for (int i = 0; i < text.getLength(); i++) {
            if (value.equals(text.item(i).getTextContent())) {
                return (Element) text.item(i);
            }
        }
        throw new AssertionError("Missing SVG text: " + value);
    }

    private static Element rectElement(Document document, String width,
            String height) {
        NodeList rects = document.getElementsByTagNameNS(SVG_NS, "rect");
        for (int i = 0; i < rects.getLength(); i++) {
            Element rect = (Element) rects.item(i);
            if (width.equals(rect.getAttribute("width"))
                    && height.equals(rect.getAttribute("height"))) return rect;
        }
        throw new AssertionError("Missing SVG rectangle " + width + "x" + height);
    }

    private static FontMetrics fontMetrics(Font font) {
        BufferedImage scratch = new BufferedImage(1, 1,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scratch.createGraphics();
        try {
            return graphics.getFontMetrics(font);
        } finally {
            graphics.dispose();
        }
    }

    private static boolean containsText(NodeList nodes, String expected) {
        for (int i = 0; i < nodes.getLength(); i++) {
            if (expected.equals(nodes.item(i).getTextContent())) return true;
        }
        return false;
    }

    private static String mm(int pixels, int dpi) {
        double value = pixels * 25.4d / dpi;
        if (Math.abs(value - Math.rint(value)) < 0.0000001) {
            return String.valueOf((long) Math.rint(value));
        }
        String out = String.format(java.util.Locale.US, "%.6f", value);
        while (out.indexOf('.') >= 0 && out.endsWith("0")) {
            out = out.substring(0, out.length() - 1);
        }
        if (out.endsWith(".")) out = out.substring(0, out.length() - 1);
        return out;
    }
}
