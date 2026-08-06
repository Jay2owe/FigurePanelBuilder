/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.meta;

import fpb.io.ImageSource;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MetadataTableTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void tokenStrategyLabelsBasicFixtureDeterministically() throws Exception {
        File folder = fixture("basic");
        MetadataTable table = MetadataTable.fromFiles(folder, imageFiles(folder),
                basicTokenStrategy());

        assertEquals(24, table.fileCount());
        assertEquals(4, table.groupCount());
        assertEquals(24, table.subjectCount());
        assertEquals(0, table.unassignedCount());
        assertEquals("24 images -> 4 groups, 24 subjects, 0 unassigned",
                table.summary());
        assertEquals("Control", table.rows().get(0).group);
        assertEquals("S1", table.rows().get(0).subject);
    }

    @Test
    public void sectionsShareSubjectAndKeepDifferentSectionValues() throws Exception {
        File folder = fixture("sections");
        Map<Integer, TokenStrategy.Field> assignment =
                new LinkedHashMap<Integer, TokenStrategy.Field>();
        assignment.put(Integer.valueOf(0), TokenStrategy.Field.GROUP);
        assignment.put(Integer.valueOf(1), TokenStrategy.Field.SUBJECT);
        assignment.put(Integer.valueOf(2), TokenStrategy.Field.SECTION);

        MetadataTable table = MetadataTable.fromFiles(folder, imageFiles(folder),
                new TokenStrategy('_', assignment));

        int s1Rows = 0;
        boolean sec1 = false;
        boolean sec2 = false;
        boolean sec3 = false;
        for (MetadataRow row : table.rows()) {
            if ("S1".equals(row.subject)) {
                s1Rows++;
                sec1 |= "sec1".equals(row.section);
                sec2 |= "sec2".equals(row.section);
                sec3 |= "sec3".equals(row.section);
            }
        }
        assertEquals(4, table.fileCount());
        assertEquals(2, table.subjectCount());
        assertEquals(3, s1Rows);
        assertTrue(sec1);
        assertTrue(sec2);
        assertTrue(sec3);
    }

    @Test
    public void subfolderStrategyLabelsFolderPerGroupLayout() throws Exception {
        File root = temp.newFolder("subfolders");
        File control = mkdir(root, "Control");
        File drug = mkdir(root, "DrugA");
        File a = touch(control, "S1.tif");
        File b = touch(drug, "S2.tif");

        MetadataTable table = MetadataTable.fromFiles(root, Arrays.asList(b, a),
                new SubfolderStrategy());

        assertEquals("Control", table.rows().get(0).group);
        assertEquals("S1", table.rows().get(0).subject);
        assertEquals("DrugA", table.rows().get(1).group);
        assertEquals("S2", table.rows().get(1).subject);
        assertEquals(0, table.unassignedCount());
    }

    @Test
    public void csvExportThenImportReproducesTableIncludingEmptySections()
            throws Exception {
        File folder = fixture("basic");
        MetadataTable original = MetadataTable.fromFiles(folder, imageFiles(folder),
                basicTokenStrategy());
        File csv = temp.newFile("metadata.csv");

        MetadataTableIO.exportCsv(original, csv);
        MetadataTable imported = MetadataTable.empty(folder, imageFiles(folder));
        MetadataTableIO.ImportResult result = MetadataTableIO.importCsv(imported, csv);

        assertFalse(result.hasUnmatchedFiles());
        assertTrue(result.isComplete());
        for (int i = 0; i < original.rows().size(); i++) {
            MetadataRow expected = original.rows().get(i);
            MetadataRow actual = imported.rows().get(i);
            assertEquals(expected.file, actual.file);
            assertEquals(expected.group, actual.group);
            assertEquals(expected.subject, actual.subject);
            assertEquals(expected.section, actual.section);
        }
    }

    @Test
    public void versionedCsvReplayRestoresSpreadsheetHardenedNamesExactly()
            throws Exception {
        File root = temp.newFolder("formula-labels");
        File file = touch(root, "-Control_S1.tif");
        MetadataRow row = new MetadataRow(file, "-Control", "+S1", "=sec");
        MetadataTable original = new MetadataTable(root, Arrays.asList(row));
        File csv = temp.newFile("formula-metadata.csv");

        MetadataTableIO.exportCsv(original, csv);
        MetadataTable imported = MetadataTable.empty(root, Arrays.asList(file));
        MetadataTableIO.ImportResult result = MetadataTableIO.importCsv(imported, csv);

        assertTrue(result.isComplete());
        assertEquals("-Control", imported.rows().get(0).group);
        assertEquals("+S1", imported.rows().get(0).subject);
        assertEquals("=sec", imported.rows().get(0).section);
    }

    @Test
    public void versionThreeCsvPreservesNumericLookingIdentifiersAsText()
            throws Exception {
        File root = temp.newFolder("numeric-identifiers");
        File file = touch(root, "001.tif");
        MetadataRow row = new MetadataRow(file, "001", "+2.5e3", "'literal");
        MetadataTable original = new MetadataTable(root, Arrays.asList(row));
        File csv = temp.newFile("numeric-identifiers.csv");

        MetadataTableIO.exportCsv(original, csv);
        String csvText = new String(java.nio.file.Files.readAllBytes(csv.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        MetadataTable imported = MetadataTable.empty(root, Arrays.asList(file));
        MetadataTableIO.ImportResult result = MetadataTableIO.importCsv(imported, csv);

        assertTrue(csvText.contains("FPBMetadataVersion"));
        assertTrue(csvText.contains("'001"));
        assertTrue(result.isComplete());
        assertEquals("001", imported.rows().get(0).group);
        assertEquals("+2.5e3", imported.rows().get(0).subject);
        assertEquals("'literal", imported.rows().get(0).section);
    }

    @Test
    public void csvImportReportsUnknownFilenames() throws Exception {
        File folder = fixture("basic");
        MetadataTable table = MetadataTable.empty(folder, imageFiles(folder));
        File csv = temp.newFile("unknown.csv");
        PrintWriter out = new PrintWriter(csv);
        try {
            out.println("File,Group,Subject,Section");
            out.println("missing.tif,Control,S1,");
        } finally {
            out.close();
        }

        MetadataTableIO.ImportResult result = MetadataTableIO.importCsv(table, csv);

        assertTrue(result.hasUnmatchedFiles());
        assertEquals(Arrays.asList("missing.tif"), result.unmatchedFiles());
        assertFalse(result.isComplete());
        assertEquals("", table.rows().get(0).group);
    }

    @Test
    public void csvImportAcceptsUtf8BomBeforeQuotedHeader() throws Exception {
        File root = temp.newFolder("bom-metadata");
        File image = touch(root, "Control_S1.tif");
        MetadataTable table = MetadataTable.empty(root, Arrays.asList(image));
        File csv = temp.newFile("bom-metadata.csv");
        Files.write(csv.toPath(), ("\uFEFF\"File\",Group,Subject,Section\n"
                + "Control_S1.tif,Control,S1,sec1\n")
                .getBytes(StandardCharsets.UTF_8));

        MetadataTableIO.ImportResult result = MetadataTableIO.importCsv(table, csv);

        assertTrue(result.isComplete());
        assertEquals("Control", table.rows().get(0).group);
        assertEquals("S1", table.rows().get(0).subject);
    }

    @Test
    public void csvImportRejectsDuplicateAndUncoveredInputsWithoutPartialMutation()
            throws Exception {
        File root = temp.newFolder("csv-integrity");
        File one = touch(root, "Control_S1.tif");
        File two = touch(root, "Drug_S2.tif");
        MetadataTable table = MetadataTable.empty(root, Arrays.asList(one, two));
        File csv = temp.newFile("partial-duplicate.csv");
        PrintWriter out = new PrintWriter(csv);
        try {
            out.println("File,Group,Subject,Section");
            out.println("Control_S1.tif,Control,S1,");
            out.println("Control_S1.tif,Wrong,Wrong,");
        } finally {
            out.close();
        }

        MetadataTableIO.ImportResult result = MetadataTableIO.importCsv(table, csv);

        assertFalse(result.isComplete());
        assertEquals(Arrays.asList("Control_S1.tif"), result.duplicateFiles());
        assertEquals(Arrays.asList("Drug_S2.tif"), result.uncoveredFiles());
        assertEquals("", table.rows().get(0).group);
        assertTrue(result.problemSummary().contains("duplicate CSV files"));
        assertTrue(result.problemSummary().contains("input files missing from CSV"));
    }

    @Test
    public void autoDetectionChoosesSubfolderStrategyWhenImagesAreBelowRoot()
            throws Exception {
        File root = temp.newFolder("auto-subfolder");
        File control = mkdir(root, "Control");
        File drug = mkdir(root, "DrugA");
        File a = touch(control, "S1.tif");
        File b = touch(drug, "S2.tif");

        LabelStrategy strategy = MetadataTable.suggest(root, Arrays.asList(a, b));

        assertTrue(strategy instanceof SubfolderStrategy);
    }

    @Test
    public void autoDetectionChoosesTokenStrategyForFlatFolder() throws Exception {
        File folder = fixture("basic");

        LabelStrategy strategy = MetadataTable.suggest(folder, imageFiles(folder));

        assertTrue(strategy instanceof TokenStrategy);
        TokenStrategy token = (TokenStrategy) strategy;
        assertEquals('_', token.separator());
        assertEquals(TokenStrategy.Field.GROUP,
                token.assignment().get(Integer.valueOf(0)));
        assertEquals(TokenStrategy.Field.SUBJECT,
                token.assignment().get(Integer.valueOf(1)));
    }

    @Test
    public void filesWithTooFewTokensRemainUnassigned() throws Exception {
        File root = temp.newFolder("short-token");
        File file = touch(root, "Control.tif");
        Map<Integer, TokenStrategy.Field> assignment =
                new LinkedHashMap<Integer, TokenStrategy.Field>();
        assignment.put(Integer.valueOf(0), TokenStrategy.Field.GROUP);
        assignment.put(Integer.valueOf(1), TokenStrategy.Field.SUBJECT);

        MetadataTable table = MetadataTable.fromFiles(root, Arrays.asList(file),
                new TokenStrategy('_', assignment));

        assertEquals(1, table.unassignedCount());
        assertEquals("", table.rows().get(0).group);
        assertEquals("", table.rows().get(0).subject);
        assertTrue(table.rows().get(0).unassignedReason.contains("too few tokens"));
    }

    @Test
    public void regexStrategyUsesCaptureGroupsAndLeavesNonMatchesEmpty()
            throws Exception {
        File root = temp.newFolder("regex");
        File matched = touch(root, "Control-S1-secA.tif");
        File unmatched = touch(root, "notes.tif");

        MetadataTable table = MetadataTable.fromFiles(root,
                Arrays.asList(unmatched, matched),
                new RegexStrategy("(.+?)-(.+?)-(.+?)\\.tif", 1, 2, 3));

        assertEquals("Control", table.rows().get(0).group);
        assertEquals("S1", table.rows().get(0).subject);
        assertEquals("secA", table.rows().get(0).section);
        assertEquals(1, table.unassignedCount());
        assertEquals("", table.rows().get(1).group);
    }

    @Test
    public void csvUsesRelativePathWhenBasenamesCollide() throws Exception {
        File root = temp.newFolder("relative-csv");
        File a = touch(mkdir(root, "Control"), "S1.tif");
        File b = touch(mkdir(root, "DrugA"), "S1.tif");
        MetadataTable table = MetadataTable.fromFiles(root, Arrays.asList(b, a),
                new SubfolderStrategy());
        File csv = temp.newFile("relative.csv");

        MetadataTableIO.exportCsv(table, csv);
        String text = new String(java.nio.file.Files.readAllBytes(csv.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(text.contains("Control/S1.tif"));
        assertTrue(text.contains("DrugA/S1.tif"));
    }

    @Test
    public void lifSeriesNamesSetExplicitConditionsInsteadOfUsingContainerName()
            throws Exception {
        File root = temp.newFolder("lif-condition");
        File lif = touch(root, "Vehicle_24h.lif");
        List<ImageSource> sources = Arrays.asList(
                ImageSource.series(lif, 0, 2, "MouseA_LH_SCN_WT"),
                ImageSource.series(lif, 1, 2, "MouseB_RH_SCN_KO"));
        Map<Integer, TokenStrategy.Field> assignment =
                new LinkedHashMap<Integer, TokenStrategy.Field>();
        assignment.put(Integer.valueOf(0), TokenStrategy.Field.GROUP);
        assignment.put(Integer.valueOf(1), TokenStrategy.Field.SUBJECT);

        MetadataTable table = MetadataTable.fromSources(root, sources,
                new TokenStrategy('_', assignment));

        assertEquals(2, table.fileCount());
        assertEquals(2, table.groupCount());
        assertEquals("WT", table.rows().get(0).group);
        assertEquals("KO", table.rows().get(1).group);
        assertEquals("MouseA", table.rows().get(0).subject);
        assertEquals("MouseB", table.rows().get(1).subject);
        assertEquals("LH_SCN", table.rows().get(0).section);
        assertEquals("RH_SCN", table.rows().get(1).section);
        assertEquals("Vehicle_24h.lif#series=1",
                table.csvFileName(table.rows().get(0)));
        assertEquals("Vehicle_24h.lif#series=2",
                table.csvFileName(table.rows().get(1)));

        File csv = temp.newFile("lif-series-metadata.csv");
        MetadataTableIO.exportCsv(table, csv);
        MetadataTable replay = MetadataTable.emptySources(root, sources);
        MetadataTableIO.ImportResult imported = MetadataTableIO.importCsv(replay, csv);
        assertTrue(imported.isComplete());
        assertEquals("WT", replay.rows().get(0).group);
        assertEquals("MouseB", replay.rows().get(1).subject);
    }

    @Test
    public void explicitSeriesTokenMappingInfersGroupsFromSubjectPrefixes()
            throws Exception {
        File root = temp.newFolder("lif-series-tokens");
        File lif = touch(root, "NLGFKI.Cas.Iba.lif");
        List<ImageSource> sources = Arrays.asList(
                ImageSource.series(lif, 0, 2, "NLGFMa348_LH_SCN"),
                ImageSource.series(lif, 1, 2, "WTMa343_RH_SCN"));
        Map<Integer, TokenStrategy.Field> assignment =
                new LinkedHashMap<Integer, TokenStrategy.Field>();
        assignment.put(Integer.valueOf(0), TokenStrategy.Field.SUBJECT);
        assignment.put(Integer.valueOf(1), TokenStrategy.Field.SECTION);
        assignment.put(Integer.valueOf(2), TokenStrategy.Field.SECTION);

        MetadataTable table = MetadataTable.fromSources(root, sources,
                TokenStrategy.forSeriesLabels('_', assignment));

        assertEquals("NLGFMa", table.rows().get(0).group);
        assertEquals("WTMa", table.rows().get(1).group);
        assertEquals("NLGFMa348", table.rows().get(0).subject);
        assertEquals("WTMa343", table.rows().get(1).subject);
        assertEquals("LH_SCN", table.rows().get(0).section);
        assertEquals("RH_SCN", table.rows().get(1).section);
    }

    @Test
    public void sourceSuggestionUsesSeriesIdentityRatherThanLifStem()
            throws Exception {
        File root = temp.newFolder("lif-series-suggestion");
        File lif = touch(root, "NLGFKI.Cas.Iba.lif");
        List<ImageSource> sources = Arrays.asList(
                ImageSource.series(lif, 0, 4, "NLGFMa348_LH_SCN"),
                ImageSource.series(lif, 1, 4, "NLGFFe347_RH_SCN"),
                ImageSource.series(lif, 2, 4, "WTMa343_LH_SCN"),
                ImageSource.series(lif, 3, 4, "WTFe351_RH_SCN"));

        LabelStrategy strategy = MetadataTable.suggestSources(root, sources);
        MetadataTable table = MetadataTable.fromSources(root, sources, strategy);

        assertTrue(strategy instanceof TokenStrategy);
        assertTrue(((TokenStrategy) strategy).splitsSeriesLabels());
        assertEquals(4, table.groupCount());
        assertEquals("NLGFMa", table.rows().get(0).group);
        assertEquals("NLGFFe", table.rows().get(1).group);
        assertEquals("WTMa", table.rows().get(2).group);
        assertEquals("WTFe", table.rows().get(3).group);
        assertEquals("NLGFMa348", table.rows().get(0).subject);
        assertEquals("LH_SCN", table.rows().get(0).section);
    }

    private static TokenStrategy basicTokenStrategy() {
        Map<Integer, TokenStrategy.Field> assignment =
                new LinkedHashMap<Integer, TokenStrategy.Field>();
        assignment.put(Integer.valueOf(0), TokenStrategy.Field.GROUP);
        assignment.put(Integer.valueOf(1), TokenStrategy.Field.SUBJECT);
        return new TokenStrategy('_', assignment);
    }

    private static List<File> imageFiles(File folder) {
        File[] files = folder.listFiles();
        assertTrue(files != null);
        return Arrays.asList(files);
    }

    private static File fixture(String path) {
        return new File("src/test/resources/fixtures", path).getAbsoluteFile();
    }

    private static File mkdir(File parent, String name) {
        File folder = new File(parent, name);
        assertTrue(folder.mkdir());
        return folder;
    }

    private static File touch(File parent, String name) throws Exception {
        File file = new File(parent, name);
        PrintWriter out = new PrintWriter(file);
        try {
            out.println("placeholder");
        } finally {
            out.close();
        }
        return file;
    }
}
