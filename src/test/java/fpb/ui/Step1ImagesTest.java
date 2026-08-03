/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui;

import fpb.meta.MetadataRow;
import fpb.meta.TokenStrategy;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Step1ImagesTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void choosingBasicFolderPopulatesTableAndSummary() {
        FPBWizard.Context context = new FPBWizard.Context();
        Step1Images step = new Step1Images(context, null);

        step.chooseFolder(fixture("basic"));

        assertEquals("Filename", step.selectedStrategyName());
        assertEquals(24, step.metadataTable().fileCount());
        assertEquals(4, step.metadataTable().groupCount());
        assertEquals(24, step.metadataTable().subjectCount());
        assertEquals("24 files -> 4 groups, 24 subjects, 0 unassigned",
                step.summaryText());
        assertTrue(step.canAdvance());
    }

    @Test
    public void folderPerGroupLayoutSelectsSubfolderStrategy() throws Exception {
        File root = temp.newFolder("folder-per-group");
        touch(mkdir(root, "Control"), "S1.tif");
        touch(mkdir(root, "DrugA"), "S2.tif");
        FPBWizard.Context context = new FPBWizard.Context();
        Step1Images step = new Step1Images(context, null);

        step.chooseFolder(root);

        assertEquals("Subfolder", step.selectedStrategyName());
        assertTrue(context.recursive);
        assertEquals("Control", step.metadataTable().rows().get(0).group);
        assertEquals("DrugA", step.metadataTable().rows().get(1).group);
    }

    @Test
    public void changingTokenDropdownRelabelsImmediately() {
        FPBWizard.Context context = new FPBWizard.Context();
        Step1Images step = new Step1Images(context, null);
        step.chooseFolder(fixture("basic"));

        step.tokenPicker().setTokenField(1, TokenStrategy.Field.SECTION);

        MetadataRow first = step.metadataTable().rows().get(0);
        assertEquals("Control", first.group);
        assertEquals("Control_S1", first.subject);
        assertEquals("S1", first.section);
    }

    @Test
    public void handEditedCellSurvivesNavigatingAwayAndBack() {
        FPBWizard.Context context = new FPBWizard.Context();
        Step1Images step = new Step1Images(context, null);
        step.chooseFolder(fixture("basic"));

        step.tablePanel().table().setValueAt("EditedGroup", 0, 1);
        step.onShow();

        assertTrue(context.tableHandEdited);
        assertEquals("EditedGroup", step.metadataTable().rows().get(0).group);
    }

    @Test
    public void csvExportThenImportThroughStepReproducesTable() throws Exception {
        FPBWizard.Context context = new FPBWizard.Context();
        Step1Images step = new Step1Images(context, null);
        step.chooseFolder(fixture("basic"));
        File csv = temp.newFile("metadata.csv");

        step.exportCsv(csv);
        step.tablePanel().table().setValueAt("Changed", 0, 1);
        step.importCsv(csv);

        assertFalse(context.tableHandEdited);
        assertEquals("Control", step.metadataTable().rows().get(0).group);
        assertEquals("S1", step.metadataTable().rows().get(0).subject);
    }

    private static File fixture(String name) {
        return new File("src/test/resources/fixtures", name).getAbsoluteFile();
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
