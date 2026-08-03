/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.meta;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
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
        assertEquals("24 files -> 4 groups, 24 subjects, 0 unassigned",
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
