/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.record;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Windows/cloud-sync publication and preflight regression coverage. */
public class OutputTreeTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void supportingArtifactsAreGroupedAwayFromPrimaryFigures()
            throws Exception {
        OutputTree.Tree tree = OutputTree.prepare(temp.newFolder("layout"),
                "Example");

        assertEquals(OutputTree.SUPPORTING_DIR,
                tree.supportingDirectory().getName());
        assertEquals(tree.supportingDirectory(),
                tree.panelsDirectory().getParentFile());
        assertEquals(tree.supportingDirectory(), tree.readme().getParentFile());
        assertFalse(new File(tree.figureDirectory(), OutputTree.PANELS_DIR).exists());
        assertFalse(new File(tree.figureDirectory(), OutputTree.README_FILE).exists());
    }

    @Test
    public void transientFileUseIsRetriedWithShortBackoff() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        final List<Long> delays = new ArrayList<Long>();

        OutputTree.retryIo(new OutputTree.IoOperation() {
            @Override
            public void run() throws IOException {
                if (attempts.incrementAndGet() < 3) {
                    throw new FileSystemException("staged", null,
                            "The process cannot access the file because it is being used");
                }
            }
        }, 5, new OutputTree.RetryPause() {
            @Override
            public void pause(long milliseconds) {
                delays.add(Long.valueOf(milliseconds));
            }
        });

        assertEquals(3, attempts.get());
        assertEquals(Arrays.asList(Long.valueOf(50L), Long.valueOf(100L)), delays);
    }

    @Test
    public void destinationCollisionFailsWithoutRetrying() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        try {
            OutputTree.retryIo(new OutputTree.IoOperation() {
                @Override
                public void run() throws IOException {
                    attempts.incrementAndGet();
                    throw new FileAlreadyExistsException("final");
                }
            }, 8, new OutputTree.RetryPause() {
                @Override
                public void pause(long milliseconds) {
                    fail("A permanent collision must not be retried");
                }
            });
            fail("Expected destination collision");
        } catch (FileAlreadyExistsException expected) {
            assertEquals(1, attempts.get());
        }
    }

    @Test
    public void publishProbeAndCommitLeaveOnlyTheCompletedFigure() throws Exception {
        File output = temp.newFolder("output");
        OutputTree.verifyPublishAccess(output);
        File root = new File(output, OutputTree.ROOT_DIR);
        File[] afterProbe = root.listFiles();
        assertTrue(afterProbe == null || afterProbe.length == 0);

        File staged = temp.newFolder("complete-stage");
        File payload = new File(staged, "figure.png");
        Files.write(payload.toPath(), "complete".getBytes(StandardCharsets.UTF_8));
        File finalFigure = new File(root, "Published");

        OutputTree.commitStagedFigure(staged, finalFigure);

        assertFalse(staged.exists());
        assertTrue(new File(finalFigure, "figure.png").isFile());
        assertEquals("complete", new String(Files.readAllBytes(
                new File(finalFigure, "figure.png").toPath()), StandardCharsets.UTF_8));
    }
}
