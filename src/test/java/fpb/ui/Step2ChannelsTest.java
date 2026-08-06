/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import fpb.meta.MetadataRow;
import fpb.meta.MetadataTable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Step2ChannelsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void defaultsToThreeNamedChannelsWithDistinguishableColours() {
        FPBWizard.Context context = new FPBWizard.Context();
        Step2Channels step = new Step2Channels(context);

        step.onShow();

        assertEquals(3, step.detectedChannelCount());
        assertEquals("C1", step.channelSettings().get(0).name);
        assertEquals("C2", step.channelSettings().get(1).name);
        assertEquals("C3", step.channelSettings().get(2).name);
        assertEquals("blue", step.channelSettings().get(0).colour.name());
        assertEquals("magenta", step.channelSettings().get(1).colour.name());
        assertEquals("green", step.channelSettings().get(2).colour.name());
        assertTrue(step.canAdvance());
    }

    @Test
    public void atLeastOneIncludedChannelIsRequiredToAdvance() {
        FPBWizard.Context context = new FPBWizard.Context();
        Step2Channels step = new Step2Channels(context);
        step.onShow();

        for (FPBWizard.ChannelSetting setting : step.channelSettings()) {
            setting.include = false;
        }

        assertFalse(step.canAdvance());
    }

    @Test
    public void includedChannelNamesMustBeNonEmptyAndCaseInsensitivelyUnique() {
        FPBWizard.Context context = new FPBWizard.Context();
        Step2Channels step = new Step2Channels(context);
        step.onShow();

        step.channelSettings().get(0).name = "Signal";
        step.channelSettings().get(1).name = " signal ";
        assertFalse(step.canAdvance());

        step.channelSettings().get(1).name = "";
        assertFalse(step.canAdvance());

        step.channelSettings().get(1).include = false;
        assertTrue(step.canAdvance());
    }

    @Test
    public void syntheticMergeNameIsReservedCaseInsensitively() {
        FPBWizard.Context context = new FPBWizard.Context();
        Step2Channels step = new Step2Channels(context);
        step.onShow();

        step.channelSettings().get(0).name = " mErGe ";

        assertFalse(step.canAdvance());
    }

    @Test
    public void numericCsvStatisticRequiresARealFileAndColumn() throws Exception {
        FPBWizard.Context context = new FPBWizard.Context();
        Step2Channels step = new Step2Channels(context);
        step.onShow();
        File csv = temp.newFile("statistics.csv");

        step.setStatisticCsv(csv, "MeanIntensity");

        assertTrue(step.canAdvance());
        assertEquals(csv.getAbsoluteFile(), context.statisticCsv);
        assertEquals("MeanIntensity", context.statisticColumn);

        step.setStatisticCsv(csv, "");
        assertFalse(step.canAdvance());
    }

    @Test
    public void failedDetectionBlocksInsteadOfInventingThreeChannels()
            throws Exception {
        File root = temp.newFolder("invalid-image");
        File image = new File(root, "broken.tif");
        Files.write(image.toPath(), "not an image".getBytes(StandardCharsets.UTF_8));
        FPBWizard.Context context = new FPBWizard.Context();
        context.metadataTable = new MetadataTable(root, Arrays.asList(
                new MetadataRow(image, "Control", "S1", "")));
        Step2Channels step = new Step2Channels(context);

        step.onShow();

        assertEquals(0, step.detectedChannelCount());
        assertTrue(step.channelSettings().isEmpty());
        assertFalse(step.canAdvance());
        assertTrue(step.detectionMessage().contains("Could not open the first image"));
        assertTrue(step.detectionMessage().contains("Click <b>Retry</b>"));
        assertTrue(step.retryVisible());
        assertTrue(step.chooseAnotherFolderVisible());
    }

    @Test
    public void failedDetectionCanReturnDirectlyToFolderSelection()
            throws Exception {
        File root = temp.newFolder("alternate-folder");
        File image = new File(root, "broken.tif");
        Files.write(image.toPath(), "not an image".getBytes(StandardCharsets.UTF_8));
        FPBWizard.Context context = new FPBWizard.Context();
        context.metadataTable = new MetadataTable(root, Arrays.asList(
                new MetadataRow(image, "Control", "S1", "")));
        final boolean[] returned = new boolean[1];
        Step2Channels step = new Step2Channels(context, new Runnable() {
            @Override
            public void run() {
                returned[0] = true;
            }
        });
        step.onShow();

        step.chooseAnotherFolder();

        assertTrue(returned[0]);
    }
}
