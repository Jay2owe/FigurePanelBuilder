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
import java.awt.Component;
import java.awt.Container;

import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Step1ImagesTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void imagesPageDoesNotShowBackgroundLoadingProgressBar() {
        Step1Images step = new Step1Images(new FPBWizard.Context(), null);

        assertFalse(containsComponent(step.component(), JProgressBar.class));
    }

    @Test
    public void choosingBasicFolderPopulatesTableAndSummary() {
        FPBWizard.Context context = new FPBWizard.Context();
        Step1Images step = new Step1Images(context, null);

        step.chooseFolder(fixture("basic"));

        assertEquals("Filename", step.selectedStrategyName());
        assertEquals(24, step.metadataTable().fileCount());
        assertEquals(4, step.metadataTable().groupCount());
        assertEquals(24, step.metadataTable().subjectCount());
        assertEquals("24 images -> 4 groups, 24 subjects, 0 unassigned",
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

    @Test
    public void returningToImagesAfterQuickGridRestoresGuidedMetadata() throws Exception {
        FPBWizard.Context context = new FPBWizard.Context();
        fpb.QuickGrid.Result quick = fpb.QuickGrid.run(fixture("basic"), false);
        context.folder = fixture("basic");
        context.quickGridRequested = true;
        context.metadataTable = quick.table();
        context.chooserData = quick.chooserData();
        context.selectedRowsByGroup = quick.selectedRowsByGroup();
        Step1Images step = new Step1Images(context, null);

        step.onShow();

        assertFalse(context.quickGridRequested);
        assertEquals(4, context.metadataTable.groupCount());
        assertEquals("Control", context.metadataTable.rows().get(0).group);
        assertTrue(context.selectedRowsByGroup.isEmpty());
        assertTrue(context.chooserData == null);
    }

    @Test
    public void guidedRecursiveDiscoverySkipsGeneratedOutputDirectories()
            throws Exception {
        File root = temp.newFolder("guided-recursive");
        touch(mkdir(root, "Originals"), "Control_S1.tif");
        File generated = mkdir(root, "Figure Panels");
        touch(mkdir(generated, "Previous"), "figure.png");
        FPBWizard.Context context = new FPBWizard.Context();
        Step1Images step = new Step1Images(context, null);

        step.chooseFolder(root);

        assertTrue(context.recursive);
        assertEquals(1, step.metadataTable().fileCount());
        assertTrue(step.metadataTable().rows().get(0).file.getAbsolutePath()
                .contains("Originals"));
    }

    @Test
    public void unassignedRowsBlockAdvancing() {
        FPBWizard.Context context = new FPBWizard.Context();
        Step1Images step = new Step1Images(context, null);
        step.chooseFolder(fixture("basic"));

        step.tablePanel().table().setValueAt("", 0, 2);

        assertEquals(1, step.metadataTable().unassignedCount());
        assertFalse(step.canAdvance());
    }

    @Test
    public void nextCommitsTheActiveCellEditorBeforeValidation() throws Exception {
        final FPBWizard.Context context = new FPBWizard.Context();
        final Step1Images step = new Step1Images(context, null);
        step.chooseFolder(fixture("basic"));
        final boolean[] advance = new boolean[1];

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                assertTrue(step.tablePanel().table().editCellAt(0, 1));
                JTextField editor = (JTextField) step.tablePanel().table()
                        .getEditorComponent();
                editor.setText("");
                advance[0] = step.canAdvance();
            }
        });

        assertFalse(advance[0]);
        assertEquals("", context.metadataTable.rows().get(0).group);
        assertFalse(step.tablePanel().table().isEditing());
    }

    @Test
    public void metadataCanBeBulkEditedForSelectedRowsOrAllRows() {
        FPBWizard.Context context = new FPBWizard.Context();
        Step1Images step = new Step1Images(context, null);
        step.chooseFolder(fixture("basic"));
        step.tablePanel().table().setRowSelectionInterval(0, 1);
        String untouchedGroup = step.metadataTable().rows().get(2).group;

        int selected = step.applyBulkMetadata(
                MetadataTablePanel.MetadataField.GROUP, "Combined", false);
        int all = step.applyBulkMetadata(
                MetadataTablePanel.MetadataField.SECTION, "overview", true);

        assertEquals(2, selected);
        assertEquals("Combined", step.metadataTable().rows().get(0).group);
        assertEquals("Combined", step.metadataTable().rows().get(1).group);
        assertEquals(untouchedGroup, step.metadataTable().rows().get(2).group);
        assertEquals(step.metadataTable().fileCount(), all);
        for (MetadataRow row : step.metadataTable().rows()) {
            assertEquals("overview", row.section);
        }
        assertTrue(context.tableHandEdited);
    }

    private static File fixture(String name) {
        return new File("src/test/resources/fixtures", name).getAbsoluteFile();
    }

    private static boolean containsComponent(Component component,
            Class<?> type) {
        if (type.isInstance(component)) return true;
        if (!(component instanceof Container)) return false;
        for (Component child : ((Container) component).getComponents()) {
            if (containsComponent(child, type)) return true;
        }
        return false;
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
