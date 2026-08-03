/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.render;

import fpb.io.ImageLoader;
import fpb.io.ProgressCallback;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FPBRendererTest {

    @Test
    public void nullDisplayRangeFailsClosed() throws Exception {
        ImageLoader.LoadResult loaded = load("eightbit.tif");
        try {
            renderSingle(loaded, null);
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(
                    "Figure Panel Builder never applies automatic per-image contrast."));
            return;
        }
        throw new AssertionError("Expected missing display range failure");
    }

    @Test
    public void invalidDisplayRangeFailsClosed() throws Exception {
        ImageLoader.LoadResult loaded = load("eightbit.tif");
        try {
            renderSingle(loaded, new DisplayRange(200, 200));
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(
                    "No display range locked for channel 'DAPI'"));
            return;
        }
        throw new AssertionError("Expected invalid display range failure");
    }

    @Test
    public void renderUsesLockedRangeWithoutPerImageStatistics() throws Exception {
        ImageLoader.LoadResult loaded = load("eightbit.tif");

        FPBRenderer.PanelRender panel = renderSingle(loaded, new DisplayRange(10, 200));

        assertArrayEquals(new int[] {
                0x000000, 0x000000,
                0xFFFFFF, 0xFFFFFF
        }, FastRaster.pixels(panel.mergeImage()));
    }

    @Test
    public void clipReportMatchesBruteForceCountFromSourcePlane() throws Exception {
        ImageLoader.LoadResult loaded = load("eightbit.tif");

        FPBRenderer.PanelRender panel = renderSingle(loaded, new DisplayRange(10, 200));
        ClipReport.ChannelClip clip = panel.clipReport().channel(0);

        assertEquals(25.0, clip.lowPercent(), 0.0001);
        assertEquals(25.0, clip.highPercent(), 0.0001);
    }

    @Test
    public void threeChannelMergeMatchesHandComputedGeneralReference() throws Exception {
        ImageLoader.LoadResult loaded = new ImageLoader(150, 2)
                .loadFolder(fixture("basic"), false, ProgressCallback.NONE);
        FPBRenderer renderer = new FPBRenderer();

        FPBRenderer.PanelRender panel = renderer.renderPanel(loaded.planeCache(),
                loaded.histogramCache(), 0, Arrays.asList(
                        request(0, "DAPI", ChannelColour.MAGENTA,
                                new DisplayRange(1000, 1255)),
                        request(1, "GFAP", ChannelColour.GREEN,
                                new DisplayRange(2000, 2255)),
                        request(2, "Iba1", ChannelColour.CYAN,
                                new DisplayRange(3000, 3255))),
                1, 1);

        assertArrayEquals(new int[] { 0x010202 }, FastRaster.pixels(panel.mergeImage()));
    }

    private static FPBRenderer.PanelRender renderSingle(ImageLoader.LoadResult loaded,
            DisplayRange range) {
        return new FPBRenderer().renderPanel(loaded.planeCache(), loaded.histogramCache(),
                0, Arrays.asList(request(0, "DAPI", ChannelColour.GREY, range)), 2, 2);
    }

    private static FPBRenderer.ChannelRequest request(int channelIndex, String name,
            ChannelColour colour, DisplayRange range) {
        return new FPBRenderer.ChannelRequest(channelIndex, name, colour, range);
    }

    private static ImageLoader.LoadResult load(String path) throws Exception {
        return new ImageLoader(150, 1).loadFiles(
                Arrays.asList(fixture(path)), ProgressCallback.NONE);
    }

    private static File fixture(String path) {
        return new File("src/test/resources/fixtures", path).getAbsoluteFile();
    }
}
