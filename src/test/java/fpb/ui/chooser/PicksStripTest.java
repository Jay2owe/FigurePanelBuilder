/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.chooser;

import fpb.io.ImageLoader;
import fpb.io.ProgressCallback;
import fpb.render.ChannelColour;
import fpb.render.DisplayRange;
import fpb.render.FPBRenderer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class PicksStripTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void namedViewKeepsTheChannelIndexThatItsLabelDescribes() {
        PicksStrip strip = new PicksStrip(Arrays.asList("Control"));
        strip.setFocusedChannel(0, "DAPI");
        strip.setFocusedChannel(2, "Iba1");

        strip.selectViewForTest("DAPI");
        assertEquals(0, strip.selectedChannelIndexForTest());
        strip.selectViewForTest("Iba1");
        assertEquals(2, strip.selectedChannelIndexForTest());
    }

    @Test
    public void renderedPicksPreserveLandscapeAndPortraitAspectRatios()
            throws Exception {
        File input = temp.newFolder("non-square-picks");
        File landscape = writeImage(input, "01-landscape.png", 100, 50);
        File portrait = writeImage(input, "02-portrait.png", 50, 100);
        ImageLoader.LoadResult loaded = new ImageLoader(150, 1).loadFiles(
                Arrays.asList(landscape, portrait), ProgressCallback.NONE);
        PicksStrip strip = new PicksStrip(Arrays.asList("Landscape", "Portrait"));
        Map<String, RowImage.SubjectRow> picks =
                new LinkedHashMap<String, RowImage.SubjectRow>();
        picks.put("Landscape", new RowImage.SubjectRow("Landscape", "S1", 0, false));
        picks.put("Portrait", new RowImage.SubjectRow("Portrait", "S1", 1, false));
        List<FPBRenderer.ChannelRequest> channels = Arrays.asList(
                new FPBRenderer.ChannelRequest(0, "Signal", ChannelColour.GREY,
                        new DisplayRange(0, 255)));

        List<PicksStrip.RenderedPick> rendered = PicksStrip.render(
                strip.createRenderSnapshot(picks, loaded.planeCache(),
                        loaded.histogramCache(), channels));

        assertEquals(340, rendered.get(0).imageForTest().getWidth());
        assertEquals(170, rendered.get(0).imageForTest().getHeight());
        assertEquals(170, rendered.get(1).imageForTest().getWidth());
        assertEquals(340, rendered.get(1).imageForTest().getHeight());
    }

    @Test
    public void selectedAnimalPreviewIncludesEverySection() throws Exception {
        File input = temp.newFolder("picked-sections");
        File first = writeImage(input, "01.png", 100, 100, Color.BLACK);
        File second = writeImage(input, "02.png", 100, 100, Color.WHITE);
        ImageLoader.LoadResult loaded = new ImageLoader(150, 1).loadFiles(
                Arrays.asList(first, second), ProgressCallback.NONE);
        PicksStrip strip = new PicksStrip(Arrays.asList("Control"));
        Map<String, RowImage.SubjectRow> picks =
                new LinkedHashMap<String, RowImage.SubjectRow>();
        picks.put("Control", new RowImage.SubjectRow("Control", "S1",
                Arrays.asList(Integer.valueOf(0), Integer.valueOf(1)),
                false, null));
        List<FPBRenderer.ChannelRequest> channels = Arrays.asList(
                new FPBRenderer.ChannelRequest(0, "Signal", ChannelColour.GREY,
                        new DisplayRange(0, 255)));

        List<PicksStrip.RenderedPick> rendered = PicksStrip.render(
                strip.createRenderSnapshot(picks, loaded.planeCache(),
                        loaded.histogramCache(), channels));

        assertEquals(684, rendered.get(0).imageForTest().getWidth());
        assertEquals(340, rendered.get(0).imageForTest().getHeight());
        assertNotEquals(rendered.get(0).imageForTest().getRGB(170, 170),
                rendered.get(0).imageForTest().getRGB(514, 170));
    }

    private File writeImage(File folder, String name, int width, int height)
            throws Exception {
        return writeImage(folder, name, width, height, Color.BLACK);
    }

    private File writeImage(File folder, String name, int width, int height,
            Color colour) throws Exception {
        File file = new File(folder, name);
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_BYTE_GRAY);
        java.awt.Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(colour);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", file);
        return file;
    }
}
