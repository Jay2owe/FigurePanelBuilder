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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Color;
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
    }

    @Test
    public void physicalSizeMatchesRasterViewBoxAtConfiguredDpi()
            throws Exception {
        File source = writeSource("panel.png", Color.GREEN, 40, 40);
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(100)
                .outputDpi(254)
                .scaleBarEnabled(false)
                .channelHeaderVisible(false)
                .groupHeaderVisible(false)
                .channelOrder(Arrays.asList("DAPI"))
                .build();
        PanelRecord record = record(source, "Control", "S1", "", "DAPI");

        BufferedImage raster = PanelWriter.renderOverviewPanel(
                Arrays.asList(record), config);
        Document doc = parse(SvgWriter.renderOverviewSvg(Arrays.asList(record), config));
        Element root = doc.getDocumentElement();

        assertEquals("0 0 " + raster.getWidth() + " " + raster.getHeight(),
                root.getAttribute("viewBox"));
        assertEquals(mm(raster.getWidth(), 254) + "mm", root.getAttribute("width"));
        assertEquals(mm(raster.getHeight(), 254) + "mm", root.getAttribute("height"));
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
