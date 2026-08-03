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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MacroRoundTripTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void macroOptionsRoundTripThroughParameters() {
        String macro = "folder=" + bracket(basicFolder())
                + " recursive separator=_ group_token=1 subject_token=2 "
                + "channels=1,2,3 channel_names=[DAPI,GFAP,Iba1] "
                + "channel_luts=Blue,Green,Magenta z_mode=max statistic=brightest_1pct "
                + "range_DAPI_min=0 range_DAPI_max=5000 "
                + "range_GFAP_min=0 range_GFAP_max=5000 "
                + "range_Iba1_min=0 range_Iba1_max=5000 "
                + "pick_Control=S1 pick_DrugA=S1 pick_DrugB=S1 pick_Wash=S1 "
                + "scale_bar_um=50 scale_bar_corner=bottom_right dpi=300 "
                + "formats=png,tif,svg output=" + bracket(temp.getRoot())
                + " hide_display";

        FPBParameters parameters = FPBMacroOptionsParser.parse(macro).toParameters();
        String first = FPBMacroOptions.fromParameters(parameters).toMacroOptions();
        String second = FPBMacroOptions.fromParameters(
                FPBMacroOptionsParser.parse(first).toParameters()).toMacroOptions();

        assertEquals(first, second);
    }

    @Test
    public void allMetadataStrategiesAreExpressible() {
        String common = " folder=" + bracket(basicFolder())
                + " channels=1 channel_names=DAPI channel_luts=Blue "
                + "range_DAPI_min=0 range_DAPI_max=5000 pick_Control=S1";

        assertTrue(FPBMacroOptionsParser.parse("separator=_ group_token=1 subject_token=2"
                + common).toMacroOptions().contains("group_token=1"));
        assertTrue(FPBMacroOptionsParser.parse("group_from=subfolder" + common)
                .toMacroOptions().contains("group_from=subfolder"));
        assertTrue(FPBMacroOptionsParser.parse("metadata_csv=[labels.csv]" + common)
                .toMacroOptions().contains("metadata_csv="));
        assertTrue(FPBMacroOptionsParser.parse("group_regex=[^(.+)_(.+).*] "
                + "group_capture=1 subject_capture=2" + common)
                .toMacroOptions().contains("group_regex="));
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingChannelRangeFailsClosed() {
        FPBMacroOptionsParser.parse("folder=" + bracket(basicFolder())
                + " channels=1 channel_names=DAPI channel_luts=Blue "
                + "pick_Control=S1").toParameters();
    }

    @Test
    public void publicApiRunOpensNoOutputFiles() throws Exception {
        FPBParameters parameters = baseParameters(basicFolder())
                .outputFolder(null)
                .build();

        FPBResult result = FPB.run(parameters);

        assertEquals(24, result.metadataTable().fileCount());
        assertEquals("S1", result.selectedSubjects().get("Control"));
        assertFalse(new File(temp.getRoot(), "Figure Panels").exists());
    }

    @Test
    public void batchRunnerWritesSeparateOutputTrees() throws Exception {
        File runA = subsetFolder("runA");
        File runB = subsetFolder("runB");
        File out = temp.newFolder("out");
        FPBParameters template = baseParameters(runA)
                .writeTiff(false)
                .writeSvg(false)
                .writeIndividualPanels(false)
                .writeRecords(false)
                .build();

        FPBBatchResult result = FPBBatchRunner.run(FPBBatchParameters
                .builder(Arrays.asList(runA, runB), template)
                .outputRoot(out)
                .build());

        assertEquals(2, result.processedFolders());
        assertEquals(0, result.errorFolders());
        assertTrue(new File(out, "runA/Figure Panels/runA/figure.png").isFile());
        assertTrue(new File(out, "runB/Figure Panels/runB/figure.png").isFile());
    }

    private FPBParameters.Builder baseParameters(File folder) {
        return FPBParameters.builder(folder)
                .separator('_')
                .groupToken(1)
                .subjectToken(2)
                .channel(1, "DAPI", ChannelColour.BLUE, 0, 5000)
                .pick("Control", "S1")
                .pick("DrugA", "S1")
                .pick("DrugB", "S1")
                .pick("Wash", "S1")
                .scaleBarUm(50.0)
                .dpi(300);
    }

    private File subsetFolder(String name) throws Exception {
        File folder = temp.newFolder(name);
        copy("Control_S1.tif", folder);
        copy("DrugA_S1.tif", folder);
        copy("DrugB_S1.tif", folder);
        copy("Wash_S1.tif", folder);
        return folder;
    }

    private void copy(String name, File folder) throws Exception {
        Files.copy(new File(basicFolder(), name).toPath(),
                new File(folder, name).toPath());
    }

    private static String bracket(File file) {
        return "[" + file.getAbsolutePath().replace('\\', '/') + "]";
    }

    private static File basicFolder() {
        return new File("src/test/resources/fixtures/basic").getAbsoluteFile();
    }
}
