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
import java.util.ArrayList;
import java.util.List;

import ij.process.ShortProcessor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlaneCacheTest {

    @Test
    public void cachedValuesEqualSourcePixelsWithoutRescaling() throws Exception {
        File folder = fixture("basic");
        ImageLoader.LoadResult result = new ImageLoader(150, 2)
                .loadFolder(folder, false, ProgressCallback.NONE);

        assertEquals(24, result.imageCount());
        assertEquals(3, result.channelCount());

        PlaneCache.Plane firstChannel = result.planeCache().plane(0, 0);
        short[] pixels = firstChannel.pixels();
        for (int i = 0; i < pixels.length; i++) {
            assertEquals(1001, pixels[i] & 0xFFFF);
        }
    }

    @Test
    public void loadingBasicFixtureCompletesAcrossFourThreads() throws Exception {
        File folder = fixture("basic");
        final List<Integer> completedValues = new ArrayList<Integer>();

        ImageLoader.LoadResult result = new ImageLoader(150, 4)
                .loadFolder(folder, false, new ProgressCallback() {
                    @Override
                    public void onProgress(int completed, int total, File file) {
                        assertEquals(24, total);
                        completedValues.add(completed);
                    }
                });

        assertEquals(24, result.imageCount());
        assertEquals(3, result.channelCount());
        assertEquals(24, completedValues.size());
        assertTrue(completedValues.contains(Integer.valueOf(24)));
    }

    @Test
    public void basicFixtureHeapGrowthStaysUnderStageBudget() throws Exception {
        File folder = fixture("basic");
        forceGc();
        long before = usedHeap();

        ImageLoader.LoadResult result = new ImageLoader(150, 4)
                .loadFolder(folder, false, ProgressCallback.NONE);

        assertEquals(24, result.imageCount());
        forceGc();
        long after = usedHeap();
        assertTrue("basic fixture heap growth was " + (after - before) + " bytes",
                after - before < 100L * 1024L * 1024L);
    }

    @Test
    public void eightBitImagesArePromotedToRawUnsignedShortValues() throws Exception {
        File image = fixture("eightbit.tif");
        ImageLoader.LoadedImage loaded = new ImageLoader(150, 1).loadImage(image);
        assertArrayEquals(new short[] { 0, 10, (short) 200, (short) 255 },
                loaded.binnedPlane(0).pixels());
        assertEquals(8, loaded.bitDepth());
    }

    @Test
    public void imageCapFailsClosedWithClearMessage() throws Exception {
        List<File> files = new ArrayList<File>();
        File image = fixture("eightbit.tif");
        for (int i = 0; i < ImageLoader.MAX_IMAGES + 1; i++) files.add(image);
        try {
            new ImageLoader(150, 1).loadFiles(files, ProgressCallback.NONE);
        } catch (java.io.IOException expected) {
            assertEquals("Figure Panel Builder v0.1.0 handles up to 100 images per run; "
                    + "this folder has 101.", expected.getMessage());
            return;
        }
        throw new AssertionError("Expected image cap failure");
    }

    @Test
    public void copiedShortPixelsAreDetachedFromLiveProcessorArray() throws Exception {
        short[] live = new short[] { 10, 20, 30, 40 };
        short[] copied = ImageLoader.copyPixelsAsUnsignedShorts(
                new ShortProcessor(2, 2, live, null), 16, 1);
        live[0] = 999;

        assertArrayEquals(new short[] { 10, 20, 30, 40 }, copied);
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void forceGc() throws InterruptedException {
        System.gc();
        Thread.sleep(50L);
    }

    private static File fixture(String path) {
        return new File("src/test/resources/fixtures", path).getAbsoluteFile();
    }
}
