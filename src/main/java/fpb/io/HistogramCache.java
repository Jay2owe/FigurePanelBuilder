/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.io;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable cumulative 65,536-bin histograms for source-resolution planes. */
public final class HistogramCache {

    public static final int BIN_COUNT = 65536;

    private final List<ImageHistograms> images;
    private final List<Histogram> pooledByChannel;

    HistogramCache(List<ImageHistograms> images, List<Histogram> pooledByChannel) {
        if (images == null) throw new IllegalArgumentException("images must not be null");
        if (pooledByChannel == null) {
            throw new IllegalArgumentException("pooledByChannel must not be null");
        }
        this.images = Collections.unmodifiableList(new ArrayList<ImageHistograms>(images));
        this.pooledByChannel = Collections.unmodifiableList(
                new ArrayList<Histogram>(pooledByChannel));
    }

    public int imageCount() {
        return images.size();
    }

    public int channelCount() {
        return pooledByChannel.size();
    }

    public ImageHistograms image(int imageIndex) {
        return images.get(imageIndex);
    }

    public Histogram histogram(int imageIndex, int channelIndex) {
        return image(imageIndex).histogram(channelIndex);
    }

    public Histogram pooledHistogram(int channelIndex) {
        return pooledByChannel.get(channelIndex);
    }

    static Histogram cumulativeFromPlane(short[] fullResolutionPlane) {
        if (fullResolutionPlane == null) {
            throw new IllegalArgumentException("fullResolutionPlane must not be null");
        }
        int[] counts = new int[BIN_COUNT];
        for (int i = 0; i < fullResolutionPlane.length; i++) {
            counts[fullResolutionPlane[i] & 0xFFFF]++;
        }
        return cumulativeFromCounts(counts);
    }

    static Histogram cumulativeFromCounts(int[] counts) {
        if (counts == null || counts.length != BIN_COUNT) {
            throw new IllegalArgumentException("histogram counts must have 65536 bins");
        }
        int[] cumulative = counts.clone();
        long total = 0L;
        for (int i = 0; i < cumulative.length; i++) {
            total += cumulative[i];
            if (total > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("histogram exceeds integer count range");
            }
            cumulative[i] = (int) total;
        }
        return new Histogram(cumulative, total);
    }

    static int[] countsFromPlane(short[] fullResolutionPlane) {
        if (fullResolutionPlane == null) {
            throw new IllegalArgumentException("fullResolutionPlane must not be null");
        }
        int[] counts = new int[BIN_COUNT];
        for (int i = 0; i < fullResolutionPlane.length; i++) {
            counts[fullResolutionPlane[i] & 0xFFFF]++;
        }
        return counts;
    }

    public static final class ImageHistograms {
        private final File sourceFile;
        private final List<Histogram> histograms;

        ImageHistograms(File sourceFile, List<Histogram> histograms) {
            if (sourceFile == null) throw new IllegalArgumentException("sourceFile is null");
            if (histograms == null || histograms.isEmpty()) {
                throw new IllegalArgumentException("histograms must not be empty");
            }
            this.sourceFile = sourceFile.getAbsoluteFile();
            this.histograms = Collections.unmodifiableList(new ArrayList<Histogram>(histograms));
        }

        public File sourceFile() {
            return sourceFile;
        }

        public int channelCount() {
            return histograms.size();
        }

        public Histogram histogram(int channelIndex) {
            return histograms.get(channelIndex);
        }
    }

    public static final class Histogram {
        private final int[] cumulative;
        private final long total;

        Histogram(int[] cumulative, long total) {
            if (cumulative == null || cumulative.length != BIN_COUNT) {
                throw new IllegalArgumentException("cumulative histogram must have 65536 bins");
            }
            if (total < 0L) throw new IllegalArgumentException("total must not be negative");
            this.cumulative = cumulative.clone();
            this.total = total;
        }

        public int[] cumulative() {
            return cumulative.clone();
        }

        public long total() {
            return total;
        }

        public double clippedLowPercent(int displayMin) {
            requireRangeValue(displayMin);
            if (total == 0L || displayMin == 0) return 0.0;
            return 100.0 * cumulative[displayMin - 1] / (double) total;
        }

        public double clippedHighPercent(int displayMax) {
            requireRangeValue(displayMax);
            if (total == 0L || displayMax == BIN_COUNT - 1) return 0.0;
            return 100.0 * (total - cumulative[displayMax]) / (double) total;
        }

        public int cumulativeCountAt(int value) {
            requireRangeValue(value);
            return cumulative[value];
        }

        private static void requireRangeValue(int value) {
            if (value < 0 || value >= BIN_COUNT) {
                throw new IllegalArgumentException("display value must be in 0-65535");
            }
        }
    }
}
