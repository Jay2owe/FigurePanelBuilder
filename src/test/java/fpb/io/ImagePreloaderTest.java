/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.io;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ImagePreloaderTest {

    @Test
    public void completedBackgroundLoadIsReusedByTheChooser() throws Exception {
        File image = new File("src/test/resources/fixtures/eightbit.tif")
                .getAbsoluteFile();
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<IOException> failure =
                new AtomicReference<IOException>();
        final AtomicInteger progressCompleted = new AtomicInteger();
        final AtomicInteger progressTotal = new AtomicInteger();
        ImagePreloader preloader = new ImagePreloader();
        try {
            preloader.preload(Arrays.asList(ImageSource.file(image)),
                    ImageLoader.ZMode.MAX,
                    new ProgressCallback() {
                        @Override
                        public void onProgress(int complete, int total, File file) {
                            progressCompleted.set(Math.max(progressCompleted.get(), complete));
                            progressTotal.set(total);
                        }
                    },
                    new ImagePreloader.CompletionListener() {
                        @Override public void completed(IOException problem) {
                            failure.set(problem);
                            completed.countDown();
                        }
                    });

            assertTrue(completed.await(15, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue(progressCompleted.get() == 1);
            assertTrue(progressTotal.get() == 1);
            ImageLoader.LoadResult ready = preloader.readyResult(
                    Arrays.asList(ImageSource.file(image)), ImageLoader.ZMode.MAX);
            ImageLoader.LoadResult reused = preloader.loadOrAwait(
                    Arrays.asList(ImageSource.file(image)), ImageLoader.ZMode.MAX);

            assertTrue(preloader.isReady(Arrays.asList(ImageSource.file(image)),
                    ImageLoader.ZMode.MAX));
            assertSame(ready, reused);
            assertFalse(preloader.isReady(Arrays.asList(ImageSource.file(image)),
                    ImageLoader.ZMode.FIRST));

            final AtomicInteger reusedProgress = new AtomicInteger();
            preloader.loadOrAwait(Arrays.asList(ImageSource.file(image)),
                    ImageLoader.ZMode.MAX, new ProgressCallback() {
                        @Override
                        public void onProgress(int complete, int total, File file) {
                            reusedProgress.set(complete);
                        }
                    });
            assertTrue(reusedProgress.get() == 1);
        } finally {
            preloader.close();
        }
    }
}
