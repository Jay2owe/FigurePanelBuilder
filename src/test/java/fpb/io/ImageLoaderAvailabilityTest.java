/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ImageLoaderAvailabilityTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void regularLocalFilePassesAvailabilityCheck() throws Exception {
        File image = temp.newFile("local.tif");
        Files.write(image.toPath(), new byte[] { 1, 2, 3 });

        assertFalse(ImageLoader.isOfflinePlaceholder(image));
        ImageLoader.ensureLocallyAvailable(image);
    }

    @Test
    public void missingFileHasAnActionableFailure() throws Exception {
        File missing = new File(temp.getRoot(), "missing.lif");

        try {
            ImageLoader.ensureLocallyAvailable(missing);
            fail("Expected missing source to fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("does not exist"));
        }
    }

    @Test
    public void cloudRecallFailureExplainsRetryAndOfflineFallback() throws Exception {
        File image = temp.newFile("online-only.lif");
        ImageLoader.SourceUnavailableException failure =
                new ImageLoader.SourceUnavailableException(image,
                        new IOException("provider unavailable"));

        assertTrue(failure.getMessage().contains("tried to request a local copy"));
        assertTrue(failure.getMessage().contains("Make available offline"));
        assertTrue(failure.getMessage().contains("Retry"));
        assertTrue(failure.getMessage().contains("choose another image folder"));
    }
}
