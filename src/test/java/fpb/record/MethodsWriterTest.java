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
import fpb.render.DisplayRange;
import fpb.stats.SelectionRecord;
import fpb.util.CsvSupport;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MethodsWriterTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void methodsFieldsArePopulatedWithoutInventingValues()
            throws Exception {
        File methods = temp.newFile("methods.txt");
        Map<String, String> chosen = new LinkedHashMap<String, String>();
        chosen.put("Control", "A4");
        List<PanelRecord> panels = Arrays.asList(new PanelRecord(
                temp.newFile("control_a4_dapi.png"), "Control", "A4", "sec1",
                "DAPI", "DAPI", 0, 100, 80, 0.325, 0.325,
                CalibrationCheck.CalibrationSource.USER_ENTERED));

        new MethodsWriter().write(methods, MethodsWriter.Record.builder()
                .panels(panels)
                .channelRanges(new MethodsWriter.ChannelRange(0, "DAPI", "blue",
                        new DisplayRange(120, 4200), 24))
                .chosenSubjects(chosen)
                .statisticName("mean of brightest 1% of pixels per channel")
                .scaleBarUm(Double.valueOf(50.0))
                .pluginVersion("0.1.0-test")
                .clock(Clock.fixed(Instant.parse("2026-08-03T12:34:56Z"),
                        ZoneOffset.UTC))
                .build());

        String text = new String(Files.readAllBytes(methods.toPath()),
                StandardCharsets.UTF_8);

        assertTrue(text.contains("Generated: 2026-08-03T12:34:56Z"));
        assertTrue(text.contains("Plugin version: 0.1.0-test"));
        assertTrue(text.contains("Pixel size:            0.325 x 0.325 um   (source: user-entered)"));
        assertTrue(text.contains("Scale bar:             50 um"));
        assertTrue(text.contains("Channels:              DAPI (blue)"));
        assertTrue(text.contains("Display range DAPI:    120 - 4200   (applied identically to all 24 images)"));
        assertTrue(text.contains("Contrast method:       fixed values, set once per channel; no per-image adjustment"));
        assertTrue(text.contains("Aggregation unit:      subject (sections averaged before ranking)"));
        assertTrue(text.contains("Panels shown:          Control=A4"));
        assertTrue(text.contains("SUGGESTED METHODS TEXT"));
        assertTrue(text.contains("Display ranges were set once per channel and applied identically to all images"));
        assertNoDiscouragedWords(text);
    }

    @Test
    public void unavailableFieldsUseNotAvailable() throws Exception {
        File methods = temp.newFile("methods.txt");

        new MethodsWriter().write(methods, MethodsWriter.Record.builder()
                .pluginVersion(null)
                .clock(Clock.fixed(Instant.parse("2026-08-03T12:34:56Z"),
                        ZoneOffset.UTC))
                .build());

        String text = new String(Files.readAllBytes(methods.toPath()),
                StandardCharsets.UTF_8);

        assertTrue(text.contains("Plugin version: not available"));
        assertTrue(text.contains("Pixel size:            not available"));
        assertTrue(text.contains("Scale bar:             not available"));
        assertTrue(text.contains("Channels:              not available"));
        assertTrue(text.contains("Groups:                not available"));
        assertTrue(text.contains("Panels shown:          not available"));
        assertTrue(text.contains("Pixel size was not available"));
        assertNoDiscouragedWords(text);
    }

    @Test
    public void selectionCsvRecordsSubjectAggregationAndChosenSubject()
            throws Exception {
        List<SelectionRecord> records = Arrays.asList(
                new SelectionRecord("Control", "S1", 0, "DAPI", 10.0,
                        12.0, -2.0, -0.5, 3, true),
                new SelectionRecord("Control", "S2", 0, "DAPI", 14.0,
                        12.0, 2.0, 0.5, 1, false));
        Map<String, String> chosen = new LinkedHashMap<String, String>();
        chosen.put("Control", "S2");

        File selection = temp.newFile("selection.csv");
        new SelectionWriter().write(selection, records, "Mean", chosen);

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(selection);
        String[] header;
        String[] first;
        String[] second;
        try {
            header = CsvSupport.parseRecord(reader.readRecord().text);
            first = CsvSupport.parseRecord(reader.readRecord().text);
            second = CsvSupport.parseRecord(reader.readRecord().text);
        } finally {
            reader.close();
        }

        assertArrayEquals(SelectionWriter.COLUMNS.toArray(new String[0]), header);
        assertEquals("3", first[2]);
        assertEquals("subject", first[8]);
        assertEquals("yes", first[9]);
        assertEquals("no", first[10]);
        assertEquals("1", second[2]);
        assertEquals("subject", second[8]);
        assertEquals("no", second[9]);
        assertEquals("yes", second[10]);
    }

    private static void assertNoDiscouragedWords(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String word : Arrays.asList("outlier", "atypical", "unusual",
                "warning", "should", "correct", "bias", "cherry-pick")) {
            assertFalse(word, lower.contains(word));
        }
    }
}
