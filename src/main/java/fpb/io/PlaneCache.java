/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.io;

import ij.measure.Calibration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable raw preview-plane cache indexed by image and channel. */
public final class PlaneCache {

    private final List<ImagePlanes> images;

    PlaneCache(List<ImagePlanes> images) {
        if (images == null) throw new IllegalArgumentException("images must not be null");
        this.images = Collections.unmodifiableList(new ArrayList<ImagePlanes>(images));
    }

    public int imageCount() {
        return images.size();
    }

    public int channelCount() {
        return images.isEmpty() ? 0 : images.get(0).channelCount();
    }

    public List<ImagePlanes> images() {
        return images;
    }

    public ImagePlanes image(int imageIndex) {
        return images.get(imageIndex);
    }

    public Plane plane(int imageIndex, int channelIndex) {
        return image(imageIndex).plane(channelIndex);
    }

    public Plane plane(File sourceFile, int channelIndex) {
        String wanted = normalizedPath(sourceFile);
        for (ImagePlanes image : images) {
            if (image.normalizedSourcePath.equals(wanted)) {
                return image.plane(channelIndex);
            }
        }
        throw new IllegalArgumentException("No cached image for " + sourceFile);
    }

    private static String normalizedPath(File file) {
        if (file == null) throw new IllegalArgumentException("sourceFile must not be null");
        return file.getAbsoluteFile().toURI().normalize().getPath();
    }

    public static final class ImagePlanes {
        private final File sourceFile;
        private final String normalizedSourcePath;
        private final int bitDepth;
        private final Calibration calibration;
        private final List<Plane> planes;

        ImagePlanes(File sourceFile, int bitDepth, Calibration calibration,
                List<Plane> planes) {
            if (sourceFile == null) throw new IllegalArgumentException("sourceFile is null");
            if (planes == null || planes.isEmpty()) {
                throw new IllegalArgumentException("planes must not be empty");
            }
            this.sourceFile = sourceFile.getAbsoluteFile();
            normalizedSourcePath = normalizedPath(sourceFile);
            this.bitDepth = bitDepth;
            this.calibration = calibration == null ? null : calibration.copy();
            this.planes = Collections.unmodifiableList(new ArrayList<Plane>(planes));
        }

        public File sourceFile() {
            return sourceFile;
        }

        public int bitDepth() {
            return bitDepth;
        }

        public Calibration calibration() {
            return calibration == null ? null : calibration.copy();
        }

        public int channelCount() {
            return planes.size();
        }

        public Plane plane(int channelIndex) {
            return planes.get(channelIndex);
        }
    }

    public static final class Plane {
        private final int width;
        private final int height;
        private final short[] pixels;

        Plane(int width, int height, short[] pixels) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("plane dimensions must be positive");
            }
            if (pixels == null || pixels.length != width * height) {
                throw new IllegalArgumentException("plane pixels do not match dimensions");
            }
            this.width = width;
            this.height = height;
            this.pixels = pixels.clone();
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public short[] pixels() {
            return pixels.clone();
        }

        /** Internal fast path for render code; callers must not mutate the returned array. */
        public short[] pixelsUnsafe() {
            return pixels;
        }
    }
}
