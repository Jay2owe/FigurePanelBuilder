/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.figure;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PanelWriterTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void pngWriterAddsPhysChunkForRequestedDpi() throws Exception {
        File output = temp.newFile("panel.png");
        PanelWriter.writePngAtomically(solid(10, 8, Color.BLACK), output, 300);

        PhysChunk phys = readPhysChunk(output);

        assertEquals(11811, phys.xPixelsPerMeter);
        assertEquals(11811, phys.yPixelsPerMeter);
        assertEquals(1, phys.unitSpecifier);
    }

    @Test
    public void overviewCompositionWritesExpectedGridSize() throws Exception {
        File dapi = writeSource("dapi.png", Color.RED, 24, 16);
        File merge = writeSource("merge.png", Color.GREEN, 24, 16);
        List<PanelRecord> records = Arrays.asList(
                record(dapi, "Control", "S1", "sec1", "DAPI", 24, 16,
                        0.5, CalibrationCheck.CalibrationSource.USER_ENTERED),
                record(merge, "Control", "S1", "sec1", "Merge", 24, 16,
                        0.5, CalibrationCheck.CalibrationSource.USER_ENTERED));
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(80)
                .scaleBarLengthUm(5.0)
                .scaleBarThicknessPx(2)
                .channelOrder(Arrays.asList("DAPI", "Merge"))
                .build();
        File output = temp.newFile("overview.png");

        PanelWriter.WriteReport report =
                PanelWriter.writeOverviewPanel(output, records, config);
        BufferedImage image = ImageIO.read(output);

        assertTrue(image.getWidth() >= 80 * 2);
        assertTrue(image.getHeight() >= 80);
        assertFalse(report.hasUnavailableScaleBars());
    }

    @Test
    public void unavailableCalibrationOmitsBarAndNamesAffectedImage()
            throws Exception {
        File source = writeSource("uncalibrated.png", Color.BLACK, 80, 60);
        PanelRecord record = record(source, "Control", "S1", "sec1",
                "DAPI", 80, 60, Double.NaN,
                CalibrationCheck.CalibrationSource.NONE);
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(80)
                .scaleBarLengthUm(10.0)
                .build();

        PanelWriter.WriteReport report = new PanelWriter.WriteReport();
        PanelWriter.renderOverviewPanel(Arrays.asList(record), config, report);

        assertTrue(report.hasUnavailableScaleBars());
        assertEquals(1, report.uncalibratedImages().size());
        assertEquals(source.getAbsolutePath(), report.uncalibratedImages().get(0));
    }

    @Test
    public void mixedCalibrationsProduceDifferentBarLengths() {
        PanelRecord fine = record(null, "Control", "S1", "sec1", "DAPI",
                200, 100, 0.5, CalibrationCheck.CalibrationSource.USER_ENTERED);
        PanelRecord coarse = record(null, "DrugA", "S2", "sec1", "DAPI",
                200, 100, 1.0, CalibrationCheck.CalibrationSource.USER_ENTERED);

        assertEquals(40, ScaleBar.lengthPixels(fine, 200, 20.0));
        assertEquals(20, ScaleBar.lengthPixels(coarse, 200, 20.0));
    }

    @Test
    public void figureWriterCreatesOutputTree() throws Exception {
        File source = writeSource("source.png", Color.BLUE, 32, 24);
        PanelRecord record = record(source, "Control", "S1", "sec1",
                "DAPI", 32, 24, 0.5,
                CalibrationCheck.CalibrationSource.USER_ENTERED);
        PanelConfig config = PanelConfig.builder()
                .cellSizePx(80)
                .scaleBarLengthUm(5.0)
                .build();

        FigureWriter.FigureOutput output = new FigureWriter()
                .writeFigure(temp.getRoot(), "My Figure", Arrays.asList(record), config);

        assertTrue(output.figureDirectory().isDirectory());
        assertTrue(output.panelsDirectory().isDirectory());
        assertTrue(new File(output.figureDirectory(), "README.txt").isFile());
        assertTrue(output.figurePng().isFile());
        assertTrue(output.figureTif().isFile());
        assertTrue(output.uncalibratedImages().isEmpty());
    }

    private File writeSource(String name, Color color, int width, int height)
            throws IOException {
        File file = temp.newFile(name);
        ImageIO.write(solid(width, height, color), "png", file);
        return file;
    }

    private static BufferedImage solid(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static PanelRecord record(File file, String group, String subject,
            String section, String output, int width, int height,
            double pixelSize, CalibrationCheck.CalibrationSource source) {
        return new PanelRecord(file, group, subject, section, output, output,
                0, width, height, pixelSize, pixelSize, source);
    }

    private static PhysChunk readPhysChunk(File png) throws IOException {
        DataInputStream in = new DataInputStream(new FileInputStream(png));
        try {
            long signature = in.readLong();
            if (signature != 0x89504E470D0A1A0AL) {
                throw new IOException("Not a PNG file: " + png);
            }
            while (true) {
                int length = in.readInt();
                byte[] typeBytes = new byte[4];
                in.readFully(typeBytes);
                String type = new String(typeBytes, "US-ASCII");
                byte[] data = new byte[length];
                in.readFully(data);
                in.readInt();
                if ("pHYs".equals(type)) {
                    if (data.length != 9) throw new IOException("Invalid pHYs length");
                    return new PhysChunk(readInt(data, 0), readInt(data, 4),
                            data[8] & 0xFF);
                }
            }
        } catch (EOFException eof) {
            throw new IOException("PNG has no pHYs chunk", eof);
        } finally {
            in.close();
        }
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static final class PhysChunk {
        final int xPixelsPerMeter;
        final int yPixelsPerMeter;
        final int unitSpecifier;

        PhysChunk(int xPixelsPerMeter, int yPixelsPerMeter,
                int unitSpecifier) {
            this.xPixelsPerMeter = xPixelsPerMeter;
            this.yPixelsPerMeter = yPixelsPerMeter;
            this.unitSpecifier = unitSpecifier;
        }
    }
}
