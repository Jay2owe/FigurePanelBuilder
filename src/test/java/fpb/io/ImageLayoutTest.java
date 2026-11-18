/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.io;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.process.ShortProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression for a four-channel Echo Revolution data set reported as three
 * channels: the "_Merge.tif" the microscope writes is an 8-bit RGB picture.
 */
public class ImageLayoutTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void fourChannelCompositeIsChannelData() throws Exception {
        File file = save("sample01.tif", composite(4, 1));
        ImageLayout layout = ImageLayout.probe(ImageSource.file(file));
        assertEquals(4, layout.channels());
        assertFalse(layout.rgbOverlay());
        assertEquals(4, new ImageLoader(150, 1).loadImage(file).channelCount());
    }

    @Test
    public void rgbMergeIsRecognisedAsColourPicture() throws Exception {
        ImagePlus rgb = composite(4, 1).flatten();
        File file = save("SCN_TAM1_Merge.tif", rgb);
        ImageLayout layout = ImageLayout.probe(ImageSource.file(file));
        assertTrue(layout.rgbOverlay());
        assertEquals(3, layout.channels());
    }

    @Test
    public void rgbPictureWithoutBioFormatsExplainsWhatItIs() throws Exception {
        File file = save("sample01_RGB.tif", composite(4, 1).flatten());
        try {
            new ImageLoader(150, 1).loadImage(file);
            fail("plain ImageJ cannot split an RGB picture into channels");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("RGB colour picture"));
        }
    }

    @Test
    public void channelsSavedAsSlicesAreFlagged() throws Exception {
        ImagePlus image = composite(4, 1);
        ImagePlus slices = new ImagePlus("slices", image.getStack());
        slices.setDimensions(1, 4, 1);
        File file = save("slices.tif", slices);
        ImageLayout layout = ImageLayout.probe(ImageSource.file(file));
        assertEquals(1, layout.channels());
        assertTrue(layout.planesWithoutChannelAxis());
        assertTrue(new ImageLoader(150, 1).loadImage(file).layout()
                .planesWithoutChannelAxis());
    }

    @Test
    public void tiledRgbPictureLikeTheEchoMergeIsRecognised() throws Exception {
        // The Echo Revolution writes tiled TIFFs, which ImageJ's header
        // decoder refuses; the first directory still identifies RGB.
        java.nio.ByteBuffer tiff = java.nio.ByteBuffer.allocate(128)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        int[][] entries = {
                { 256, 3, 1, 32 }, { 257, 3, 1, 32 }, { 258, 3, 3, 110 },
                { 262, 3, 1, 2 }, { 277, 3, 1, 3 }, { 322, 3, 1, 16 },
                { 323, 3, 1, 16 } };
        tiff.putShort((short) entries.length);
        for (int[] entry : entries) {
            tiff.putShort((short) entry[0]).putShort((short) entry[1])
                    .putInt(entry[2]);
            if (entry[2] == 1) tiff.putShort((short) entry[3]).putShort((short) 0);
            else tiff.putInt(entry[3]);
        }
        tiff.putInt(0);
        tiff.position(110);
        tiff.putShort((short) 8).putShort((short) 8).putShort((short) 8);
        File file = new File(temp.getRoot(), "SCN_TAM1_Merge.tif");
        java.nio.file.Files.write(file.toPath(), tiff.array());

        ImageLayout layout = ImageLayout.probe(ImageSource.file(file));
        assertTrue(layout.toString(), layout.rgbOverlay());
    }

    @Test
    public void onlyRgbCopyNamesAreFetchedWhenOnlineOnly() {
        assertTrue(ImageLayout.namedLikeRgbCopy(new File("SCN_TAM1_Merge.tif")));
        assertTrue(ImageLayout.namedLikeRgbCopy(new File("sample01_RGB.tif")));
        assertFalse(ImageLayout.namedLikeRgbCopy(
                new File("Tile000001_to_Tile000004_merged.tif")));
        assertFalse(ImageLayout.namedLikeRgbCopy(new File("SCN_TAM1_DAPI.tif")));
    }

    @Test
    public void nonTiffAndSeriesAreUnknownWithoutOpening() {
        assertEquals(ImageLayout.UNKNOWN, ImageLayout.probe(ImageSource.file(
                new File(temp.getRoot(), "missing.czi"))).channels());
    }

    private static ImagePlus composite(int channels, int slices) {
        ImageStack stack = new ImageStack(32, 24);
        for (int z = 0; z < slices; z++) {
            for (int c = 0; c < channels; c++) {
                ShortProcessor processor = new ShortProcessor(32, 24);
                processor.setValue(1000 * (c + 1) + z);
                processor.fill();
                stack.addSlice(processor);
            }
        }
        ImagePlus image = new ImagePlus("composite", stack);
        image.setDimensions(channels, slices, 1);
        return new CompositeImage(image, IJ.COMPOSITE);
    }

    private File save(String name, ImagePlus image) {
        File file = new File(temp.getRoot(), name);
        assertTrue(new FileSaver(image).saveAsTiff(file.getAbsolutePath()));
        return file;
    }
}
