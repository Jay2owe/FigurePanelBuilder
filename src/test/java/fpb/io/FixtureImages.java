/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.io;

import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;

import java.io.File;
import java.io.IOException;

final class FixtureImages {

    private FixtureImages() {}

    static File basic(File root) throws IOException {
        File folder = new File(root, "basic");
        mkdirs(folder);
        String[] groups = { "Control", "DrugA", "DrugB", "Wash" };
        for (int g = 0; g < groups.length; g++) {
            for (int subject = 1; subject <= 6; subject++) {
                int[] values = {
                        1000 + g * 100 + subject,
                        2000 + g * 100 + subject,
                        3000 + g * 100 + subject
                };
                saveSixteenBitHyperstack(new File(folder,
                        groups[g] + "_S" + subject + ".tif"),
                        8, 6, 3, 1, values, true);
            }
        }
        return folder;
    }

    static File sections(File root) throws IOException {
        File folder = new File(root, "sections");
        mkdirs(folder);
        saveSixteenBitHyperstack(new File(folder, "Control_S1_sec1.tif"),
                8, 6, 3, 1, new int[] { 101, 201, 301 }, true);
        saveSixteenBitHyperstack(new File(folder, "Control_S1_sec2.tif"),
                8, 6, 3, 1, new int[] { 102, 202, 302 }, true);
        saveSixteenBitHyperstack(new File(folder, "Control_S1_sec3.tif"),
                8, 6, 3, 1, new int[] { 103, 203, 303 }, true);
        saveSixteenBitHyperstack(new File(folder, "Control_S2_sec1.tif"),
                8, 6, 3, 1, new int[] { 111, 211, 311 }, true);
        return folder;
    }

    static File uncalibrated(File root) throws IOException {
        File file = new File(root, "uncalibrated.tif");
        saveSixteenBitHyperstack(file, 5, 4, 1, 1, new int[] { 700 }, false);
        return file;
    }

    static File eightBit(File root) throws IOException {
        File file = new File(root, "eightbit.tif");
        byte[] pixels = new byte[] { 0, 10, (byte) 200, (byte) 255 };
        ImagePlus image = new ImagePlus("eightbit", new ByteProcessor(2, 2, pixels, null));
        save(file, image);
        return file;
    }

    static File puncta(File root) throws IOException {
        File file = new File(root, "puncta.tif");
        int width = 300;
        int height = 300;
        short[] pixels = new short[width * height];
        pixels[77 * width + 123] = (short) 60000;
        ImagePlus image = new ImagePlus("puncta",
                new ShortProcessor(width, height, pixels, null));
        save(file, image);
        return file;
    }

    private static void saveSixteenBitHyperstack(File file, int width, int height,
            int channels, int slices, int[] values, boolean calibrated) throws IOException {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < slices; z++) {
            for (int c = 0; c < channels; c++) {
                short[] pixels = filled(width * height, values[c] + z);
                stack.addSlice("C" + (c + 1) + " Z" + (z + 1),
                        new ShortProcessor(width, height, pixels, null));
            }
        }
        ImagePlus image = new ImagePlus(file.getName(), stack);
        image.setDimensions(channels, slices, 1);
        if (channels > 1 || slices > 1) image.setOpenAsHyperStack(true);
        if (calibrated) {
            Calibration calibration = new Calibration();
            calibration.pixelWidth = 0.5;
            calibration.pixelHeight = 0.5;
            calibration.setUnit("micron");
            image.setCalibration(calibration);
        }
        save(file, image);
    }

    private static short[] filled(int length, int value) {
        short[] pixels = new short[length];
        for (int i = 0; i < pixels.length; i++) pixels[i] = (short) value;
        return pixels;
    }

    private static void save(File file, ImagePlus image) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) mkdirs(parent);
        boolean ok = image.getStackSize() > 1
                ? new FileSaver(image).saveAsTiffStack(file.getAbsolutePath())
                : new FileSaver(image).saveAsTiff(file.getAbsolutePath());
        image.changes = false;
        image.close();
        image.flush();
        if (!ok || !file.isFile()) {
            throw new IOException("Could not write fixture " + file.getAbsolutePath());
        }
    }

    private static void mkdirs(File folder) throws IOException {
        if (folder.isDirectory()) return;
        if (!folder.mkdirs() && !folder.isDirectory()) {
            throw new IOException("Could not create " + folder.getAbsolutePath());
        }
    }
}
