/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.record;

import fpb.figure.CalibrationCheck;
import fpb.figure.PanelRecord;
import fpb.render.ClipReport;
import fpb.render.DisplayRange;
import fpb.util.CsvSupport;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ManifestWriterTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void writesExactColumnsAndEscapesCsvValues() throws Exception {
        File source = temp.newFile("source.tif");
        File panelFile = temp.newFile("panel.png");
        PanelRecord panel = new PanelRecord(source, "Control, A", "S\"1", "",
                "image, \"1\"", "DAPI, blue", "DAPI, blue", 0,
                120, 80, 0.325, 0.325,
                CalibrationCheck.CalibrationSource.BIO_FORMATS);
        ClipReport.ChannelClip clip =
                new ClipReport.ChannelClip(0, "DAPI", 1.25, 0.5);

        File manifest = temp.newFile("manifest.csv");
        new ManifestWriter().write(manifest, Arrays.asList(new ManifestWriter.Row(
                panel, "Blue, \"warm\"", panelFile, new DisplayRange(120, 4200),
                clip, "brightest 1%", 101.5, 99.25, 2, "S2", "S\"1")));

        String[] header;
        String[] row;
        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(manifest);
        try {
            header = CsvSupport.parseRecord(reader.readRecord().text);
            row = CsvSupport.parseRecord(reader.readRecord().text);
        } finally {
            reader.close();
        }

        assertArrayEquals(ManifestWriter.COLUMNS.toArray(new String[0]), header);
        assertEquals("Control, A", row[0]);
        assertEquals("S\"1", row[1]);
        assertEquals("", row[2]);
        assertEquals("image, \"1\"", row[4]);
        assertEquals("not available", row[5]);
        assertEquals("DAPI, blue", row[7]);
        assertEquals("Blue, \"warm\"", row[8]);
        assertEquals(panelFile.getAbsolutePath(), row[9]);
        assertEquals("bio-formats", row[14]);
        assertEquals("120", row[15]);
        assertEquals("4200", row[16]);
        assertEquals("locked", row[17]);
        assertEquals("1.25", row[18]);
        assertEquals("0.5", row[19]);
        assertEquals("S2", row[24]);
        assertEquals("S\"1", row[25]);
        assertEquals(row.length, header.length);
        for (int i = 0; i < row.length; i++) {
            if ("Section".equals(header[i])) continue;
            assertFalse("Blank field: " + header[i], row[i].isEmpty());
        }
    }

    @Test
    public void missingOptionalValuesAreRecordedAsNotAvailable()
            throws Exception {
        PanelRecord panel = new PanelRecord(null, "Control", "S1", "sec1",
                "DAPI", "DAPI", 0, 10, 10, Double.NaN, Double.NaN,
                CalibrationCheck.CalibrationSource.NONE);

        File manifest = temp.newFile("manifest.csv");
        new ManifestWriter().write(manifest, Arrays.asList(new ManifestWriter.Row(
                panel, "blue", null, new DisplayRange(1, 2), null,
                null, Double.NaN, Double.NaN, 0, null, null)));

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(manifest);
        String[] row;
        try {
            reader.readRecord();
            row = CsvSupport.parseRecord(reader.readRecord().text);
        } finally {
            reader.close();
        }

        assertEquals("not available", row[3]);
        assertEquals("not available", row[9]);
        assertEquals("not available", row[12]);
        assertEquals("not available", row[13]);
        assertEquals("not available", row[14]);
        assertEquals("not available", row[18]);
        assertEquals("not available", row[21]);
        assertEquals("not available", row[23]);
        assertEquals("not available", row[24]);
        assertEquals("not available", row[25]);
    }

    @Test
    public void missingLockedRangeFailsClosed() {
        PanelRecord panel = new PanelRecord(null, "Control", "S1", "",
                "DAPI", "DAPI", 0, 10, 10, 0.5, 0.5,
                CalibrationCheck.CalibrationSource.USER_ENTERED);

        try {
            new ManifestWriter.Row(panel, "blue", null, null, null,
                    "Mean", 1.0, 1.0, 1, "S1", "S1");
            assertTrue("Expected locked range to be required", false);
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("locked display range"));
        }
    }

    @Test
    public void mergeRowsRecordComponentRangesInsteadOfInventingOne()
            throws Exception {
        PanelRecord merge = new PanelRecord(null, "Control", "S1", "",
                "image", "Merge", "Merge", -1, 10, 10, 0.5, 0.5,
                CalibrationCheck.CalibrationSource.USER_ENTERED);
        File manifest = temp.newFile("merge-manifest.csv");
        new ManifestWriter().write(manifest, Arrays.asList(new ManifestWriter.Row(
                merge, "merge", null, null, null, "component channel ranges",
                "Mean", Double.NaN, Double.NaN, 1, "S1", "S1",
                "representative", "metadata", "first")));

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(manifest);
        String[] row;
        try {
            reader.readRecord();
            row = CsvSupport.parseRecord(reader.readRecord().text);
        } finally {
            reader.close();
        }
        assertEquals("first", row[5]);
        assertEquals("not available", row[15]);
        assertEquals("not available", row[16]);
        assertEquals("component channel ranges", row[17]);
    }
}
