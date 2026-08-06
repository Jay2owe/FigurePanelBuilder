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

import static org.junit.Assert.assertEquals;

public class HistogramCacheTest {

    @Test
    public void clippingPercentagesMatchHandComputedFixture() throws Exception {
        File image = fixture("eightbit.tif");
        ImageLoader.LoadedImage loaded = new ImageLoader(150, 1).loadImage(image);
        HistogramCache.Histogram histogram = loaded.histogram(0);

        assertEquals(4, histogram.total());
        assertEquals(2, histogram.cumulativeCountAt(10));
        assertEquals(50.0, histogram.clippedLowPercent(11), 0.0001);
        assertEquals(25.0, histogram.clippedHighPercent(200), 0.0001);
    }

    @Test
    public void pooledHistogramUsesFullResolutionValuesAcrossFolder() throws Exception {
        File folder = fixture("basic");
        ImageLoader.LoadResult result = new ImageLoader(150, 4)
                .loadFolder(folder, false, ProgressCallback.NONE);

        HistogramCache.Histogram pooled = result.histogramCache().pooledHistogram(0);
        assertEquals(24L * 8L * 6L, pooled.total());
        assertEquals(48, pooled.cumulativeCountAt(1001));
        assertEquals(24L * 8L * 6L, pooled.cumulativeCountAt(1406));
    }

    private static File fixture(String path) {
        return new File("src/test/resources/fixtures", path).getAbsoluteFile();
    }
}
