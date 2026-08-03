/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb;

import fpb.render.ChannelColour;
import fpb.ui.Step5Export;
import fpb.util.CsvSupport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class EndToEndTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void endToEnd_basicFixtureProducesCompleteOutputTree() throws Exception {
        File output = temp.newFolder("output");
        FPBParameters params = FPBParameters.builder(basicFixture())
                .channel(1, "DAPI", ChannelColour.BLUE, 900, 1800)
                .channel(2, "GFAP", ChannelColour.GREEN, 1900, 2800)
                .channel(3, "Iba1", ChannelColour.MAGENTA, 2900, 3800)
                .pick("Control", "S3")
                .pick("DrugA", "S3")
                .pick("DrugB", "S3")
                .pick("Wash", "S3")
                .outputFolder(output)
                .figureName("EndToEnd")
                .dpi(300)
                .exportScale(1)
                .hideDisplay(true)
                .build();

        Step5Export.ExportResult export = FPB.runAndWrite(params);
        File figureDir = export.figureDirectory();

        assertFileExists(new File(figureDir, "figure.png"));
        assertFileExists(new File(figureDir, "figure.tif"));
        assertFileExists(new File(figureDir, "figure.svg"));
        assertFileExists(new File(figureDir, "manifest.csv"));
        assertFileExists(new File(figureDir, "selection.csv"));
        assertFileExists(new File(figureDir, "methods.txt"));
        assertFileExists(new File(figureDir, "README.txt"));
        File[] panelFiles = new File(figureDir, "panels").listFiles();
        assertTrue(panelFiles != null && panelFiles.length >= 16);

        assertEquals(300, pngDpi(new File(figureDir, "figure.png")));
        assertSvgHasEditableText(new File(figureDir, "figure.svg"));
        assertCsvRowsAndColumns(new File(figureDir, "manifest.csv"), 16, "Section");
        assertCsvRowsAndColumns(new File(figureDir, "selection.csv"), 72);

        String methods = new String(Files.readAllBytes(
                new File(figureDir, "methods.txt").toPath()), StandardCharsets.UTF_8);
        assertTrue(methods.contains("Display range DAPI"));
        assertTrue(methods.contains("Display range GFAP"));
        assertTrue(methods.contains("Display range Iba1"));
        assertTrue(methods.contains("Aggregation unit:      subject"));
    }

    private static File basicFixture() {
        return new File("src/test/resources/fixtures/basic").getAbsoluteFile();
    }

    private static void assertFileExists(File file) {
        assertTrue(file.getAbsolutePath(), file.isFile());
    }

    private static void assertSvgHasEditableText(File svg) throws Exception {
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(svg);
        assertTrue(document.getElementsByTagName("text").getLength() > 0);
    }

    private static void assertCsvRowsAndColumns(File csv, int expectedRows)
            throws Exception {
        assertCsvRowsAndColumns(csv, expectedRows, new String[0]);
    }

    private static void assertCsvRowsAndColumns(File csv, int expectedRows,
            String... optionalColumns)
            throws Exception {
        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(csv);
        int rows = 0;
        int columns = -1;
        String[] header = null;
        try {
            CsvSupport.Record record;
            while ((record = reader.readRecord()) != null) {
                String[] fields = CsvSupport.parseRecord(record.text);
                if (columns < 0) {
                    columns = fields.length;
                    header = fields;
                } else {
                    rows++;
                    assertEquals("column count in " + csv.getName(),
                            columns, fields.length);
                    for (int i = 0; i < fields.length; i++) {
                        if (isOptional(header[i], optionalColumns)) continue;
                        String field = fields[i];
                        assertTrue("blank field in " + csv.getName(),
                                field != null && field.trim().length() > 0);
                    }
                }
            }
        } finally {
            reader.close();
        }
        assertEquals(expectedRows, rows);
    }

    private static boolean isOptional(String column, String[] optionalColumns) {
        for (String optional : optionalColumns) {
            if (optional.equals(column)) return true;
        }
        return false;
    }

    private static int pngDpi(File file) throws Exception {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
        assertTrue("PNG reader available", readers.hasNext());
        ImageReader reader = readers.next();
        ImageInputStream input = null;
        try {
            input = ImageIO.createImageInputStream(file);
            reader.setInput(input);
            IIOMetadata metadata = reader.getImageMetadata(0);
            Node root = metadata.getAsTree("javax_imageio_png_1.0");
            Node phys = childNamed(root, "pHYs");
            assertNotNull("PNG pHYs metadata", phys);
            int pixelsPerMeter = Integer.parseInt(
                    phys.getAttributes().getNamedItem("pixelsPerUnitXAxis")
                            .getNodeValue());
            assertEquals("meter", phys.getAttributes().getNamedItem("unitSpecifier")
                    .getNodeValue());
            return (int) Math.round(pixelsPerMeter * 0.0254d);
        } finally {
            reader.dispose();
            if (input != null) input.close();
        }
    }

    private static Node childNamed(Node root, String name) {
        if (root == null) return null;
        for (Node child = root.getFirstChild(); child != null;
             child = child.getNextSibling()) {
            if (name.equals(child.getNodeName())) return child;
        }
        return null;
    }
}
