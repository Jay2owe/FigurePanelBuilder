/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.stats;

import fpb.io.HistogramCache;
import fpb.io.ImageLoader;
import fpb.io.ImageSource;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Built-in image statistics computed from full-resolution histograms. */
public final class Statistic {

    /** Source-channel sentinel used by statistics that describe the whole image. */
    public static final int CHANNEL_INDEPENDENT = -1;

    public static final String BRIGHTEST_ONE_PERCENT_NAME =
            "full_resolution_max_projection_brightest_1_percent_mean_v1";
    public static final String BRIGHTEST_ONE_PERCENT_FIRST_SLICE_NAME =
            "full_resolution_first_slice_brightest_1_percent_mean_v1";
    public static final double BRIGHTEST_FRACTION = 0.01;

    private Statistic() {}

    public static ImageValues brightestOnePercentMeans(HistogramCache cache) {
        if (cache == null) throw new IllegalArgumentException("cache must not be null");
        List<Integer> indices = new ArrayList<Integer>();
        for (int i = 0; i < cache.channelCount(); i++) indices.add(Integer.valueOf(i));
        return brightestOnePercentMeans(cache, indices,
                defaultChannelNames(cache.channelCount()), ImageLoader.ZMode.MAX);
    }

    public static ImageValues brightestOnePercentMeans(HistogramCache cache,
            List<Integer> sourceChannelIndices, List<String> channelNames,
            ImageLoader.ZMode zMode) {
        if (cache == null) throw new IllegalArgumentException("cache must not be null");
        if (sourceChannelIndices == null || sourceChannelIndices.isEmpty()) {
            throw new IllegalArgumentException("sourceChannelIndices must not be empty");
        }
        if (channelNames == null || channelNames.size() != sourceChannelIndices.size()) {
            throw new IllegalArgumentException(
                    "channelNames must match sourceChannelIndices");
        }
        Map<ImageSource, Map<Integer, Double>> values =
                new LinkedHashMap<ImageSource, Map<Integer, Double>>();
        for (int imageIndex = 0; imageIndex < cache.imageCount(); imageIndex++) {
            HistogramCache.ImageHistograms image = cache.image(imageIndex);
            Map<Integer, Double> byChannel = new LinkedHashMap<Integer, Double>();
            for (int logicalIndex = 0; logicalIndex < sourceChannelIndices.size();
                    logicalIndex++) {
                int sourceIndex = sourceChannelIndices.get(logicalIndex).intValue();
                if (sourceIndex < 0 || sourceIndex >= image.channelCount()) {
                    throw new IllegalArgumentException("source channel index is outside cache");
                }
                byChannel.put(Integer.valueOf(logicalIndex), Double.valueOf(
                        brightestOnePercentMean(image.histogram(sourceIndex))));
            }
            values.put(image.source(), byChannel);
        }
        ImageLoader.ZMode mode = zMode == null ? ImageLoader.ZMode.MAX : zMode;
        String name = mode == ImageLoader.ZMode.FIRST
                ? BRIGHTEST_ONE_PERCENT_FIRST_SLICE_NAME
                : BRIGHTEST_ONE_PERCENT_NAME;
        return ImageValues.ofSources(values, channelNames, sourceChannelIndices, name);
    }

    public static double brightestOnePercentMean(HistogramCache.Histogram histogram) {
        if (histogram == null) throw new IllegalArgumentException("histogram must not be null");
        return brightestOnePercentMean(histogram.cumulative(), histogram.total());
    }

    /** Mean of the brightest 1% of pixels from a 65,536-bin cumulative histogram. */
    public static double brightestOnePercentMean(int[] cumulativeHist, long total) {
        if (cumulativeHist == null || cumulativeHist.length != HistogramCache.BIN_COUNT) {
            throw new IllegalArgumentException("cumulative histogram must have 65536 bins");
        }
        if (total < 0L) throw new IllegalArgumentException("total must not be negative");
        if (total == 0L) return 0.0;

        long cutoffCount = (long) Math.ceil(total * BRIGHTEST_FRACTION);
        long seen = 0L;
        double sum = 0.0;
        for (int value = HistogramCache.BIN_COUNT - 1;
                value >= 0 && seen < cutoffCount; value--) {
            long atValue = countAt(cumulativeHist, value);
            long take = Math.min(atValue, cutoffCount - seen);
            sum += (double) value * (double) take;
            seen += take;
        }
        return seen == 0L ? 0.0 : sum / (double) seen;
    }

    private static long countAt(int[] cumulativeHist, int value) {
        int previous = value == 0 ? 0 : cumulativeHist[value - 1];
        return (long) cumulativeHist[value] - (long) previous;
    }

    private static List<String> defaultChannelNames(int channelCount) {
        List<String> names = new ArrayList<String>(channelCount);
        for (int i = 0; i < channelCount; i++) {
            names.add("Channel " + (i + 1));
        }
        return names;
    }

    /** Immutable image-level values indexed by source file and zero-based channel. */
    public static final class ImageValues {
        private final Map<String, ImageSource> sourcesByKey;
        private final Map<String, Map<Integer, Double>> valuesByKey;
        private final List<String> channelNames;
        private final List<Integer> sourceChannelIndices;
        private final String statisticName;

        private ImageValues(Map<File, Map<Integer, Double>> valuesByFile,
                List<String> channelNames, List<Integer> sourceChannelIndices,
                String statisticName) {
            this(toSourceValues(valuesByFile), channelNames, sourceChannelIndices,
                    statisticName, true);
        }

        private ImageValues(Map<ImageSource, Map<Integer, Double>> valuesBySource,
                List<String> channelNames, List<Integer> sourceChannelIndices,
                String statisticName, boolean sourceMap) {
            if (valuesBySource == null) {
                throw new IllegalArgumentException("valuesBySource must not be null");
            }
            if (channelNames == null || channelNames.isEmpty()) {
                throw new IllegalArgumentException("channelNames must not be empty");
            }
            this.channelNames = Collections.unmodifiableList(
                    new ArrayList<String>(channelNames));
            if (sourceChannelIndices == null
                    || sourceChannelIndices.size() != channelNames.size()) {
                throw new IllegalArgumentException(
                        "sourceChannelIndices must match channelNames");
            }
            this.sourceChannelIndices = Collections.unmodifiableList(
                    new ArrayList<Integer>(sourceChannelIndices));
            this.statisticName = clean(statisticName).isEmpty()
                    ? "statistic" : clean(statisticName);

            Map<String, ImageSource> sources = new LinkedHashMap<String, ImageSource>();
            Map<String, Map<Integer, Double>> values =
                    new LinkedHashMap<String, Map<Integer, Double>>();
            for (Map.Entry<ImageSource, Map<Integer, Double>> entry
                    : valuesBySource.entrySet()) {
                ImageSource source = entry.getKey();
                if (source == null) {
                    throw new IllegalArgumentException("valuesBySource contains a null source");
                }
                String key = source.key();
                sources.put(key, source);
                Map<Integer, Double> byChannel = new LinkedHashMap<Integer, Double>();
                if (entry.getValue() != null) {
                    for (Map.Entry<Integer, Double> channel : entry.getValue().entrySet()) {
                        Integer index = channel.getKey();
                        Double value = channel.getValue();
                        if (index == null) continue;
                        if (index.intValue() < 0 || index.intValue() >= channelNames.size()) {
                            throw new IllegalArgumentException(
                                    "channel index is outside channelNames");
                        }
                        if (value != null && Double.isFinite(value.doubleValue())) {
                            byChannel.put(index, value);
                        }
                    }
                }
                values.put(key, Collections.unmodifiableMap(byChannel));
            }
            sourcesByKey = Collections.unmodifiableMap(sources);
            valuesByKey = Collections.unmodifiableMap(values);
        }

        public static ImageValues of(Map<File, Map<Integer, Double>> valuesByFile,
                List<String> channelNames, String statisticName) {
            List<Integer> indices = new ArrayList<Integer>();
            for (int i = 0; i < channelNames.size(); i++) indices.add(Integer.valueOf(i));
            return new ImageValues(valuesByFile, channelNames, indices, statisticName);
        }

        public static ImageValues ofSources(
                Map<ImageSource, Map<Integer, Double>> valuesBySource,
                List<String> channelNames, List<Integer> sourceChannelIndices,
                String statisticName) {
            return new ImageValues(valuesBySource, channelNames,
                    sourceChannelIndices, statisticName, true);
        }

        public static ImageValues of(Map<File, Map<Integer, Double>> valuesByFile,
                List<String> channelNames, List<Integer> sourceChannelIndices,
                String statisticName) {
            return new ImageValues(valuesByFile, channelNames, sourceChannelIndices,
                    statisticName);
        }

        public static ImageValues singleChannel(Map<File, Double> valuesByFile,
                String channelName, String statisticName) {
            if (valuesByFile == null) {
                throw new IllegalArgumentException("valuesByFile must not be null");
            }
            Map<File, Map<Integer, Double>> nested =
                    new LinkedHashMap<File, Map<Integer, Double>>();
            for (Map.Entry<File, Double> entry : valuesByFile.entrySet()) {
                Map<Integer, Double> channel = new LinkedHashMap<Integer, Double>();
                channel.put(Integer.valueOf(0), entry.getValue());
                nested.put(entry.getKey(), channel);
            }
            List<String> names = new ArrayList<String>();
            names.add(clean(channelName).isEmpty() ? "Value" : clean(channelName));
            return new ImageValues(nested, names,
                    Collections.singletonList(Integer.valueOf(CHANNEL_INDEPENDENT)),
                    statisticName);
        }

        public static ImageValues singleChannelSources(
                Map<ImageSource, Double> valuesBySource,
                String channelName, String statisticName) {
            if (valuesBySource == null) {
                throw new IllegalArgumentException("valuesBySource must not be null");
            }
            Map<ImageSource, Map<Integer, Double>> nested =
                    new LinkedHashMap<ImageSource, Map<Integer, Double>>();
            for (Map.Entry<ImageSource, Double> entry : valuesBySource.entrySet()) {
                Map<Integer, Double> channel = new LinkedHashMap<Integer, Double>();
                channel.put(Integer.valueOf(0), entry.getValue());
                nested.put(entry.getKey(), channel);
            }
            List<String> names = new ArrayList<String>();
            names.add(clean(channelName).isEmpty() ? "Value" : clean(channelName));
            return new ImageValues(nested, names,
                    Collections.singletonList(Integer.valueOf(CHANNEL_INDEPENDENT)),
                    statisticName, true);
        }

        public int channelCount() {
            return channelNames.size();
        }

        public String channelName(int channelIndex) {
            return channelNames.get(channelIndex);
        }

        public List<String> channelNames() {
            return channelNames;
        }

        public int sourceChannelIndex(int logicalChannelIndex) {
            return sourceChannelIndices.get(logicalChannelIndex).intValue();
        }

        public String statisticName() {
            return statisticName;
        }

        public Double value(File sourceFile, int channelIndex) {
            if (sourceFile == null) return null;
            return value(ImageSource.file(sourceFile), channelIndex);
        }

        public Double value(ImageSource source, int channelIndex) {
            if (source == null) return null;
            Map<Integer, Double> byChannel = valuesByKey.get(source.key());
            return byChannel == null ? null : byChannel.get(Integer.valueOf(channelIndex));
        }

        public Map<File, Map<Integer, Double>> valuesByFile() {
            Map<File, Map<Integer, Double>> copy =
                    new LinkedHashMap<File, Map<Integer, Double>>();
            for (Map.Entry<String, Map<Integer, Double>> entry : valuesByKey.entrySet()) {
                copy.put(sourcesByKey.get(entry.getKey()).file(), entry.getValue());
            }
            return Collections.unmodifiableMap(copy);
        }

        private static Map<ImageSource, Map<Integer, Double>> toSourceValues(
                Map<File, Map<Integer, Double>> valuesByFile) {
            if (valuesByFile == null) {
                throw new IllegalArgumentException("valuesByFile must not be null");
            }
            Map<ImageSource, Map<Integer, Double>> sources =
                    new LinkedHashMap<ImageSource, Map<Integer, Double>>();
            for (Map.Entry<File, Map<Integer, Double>> entry : valuesByFile.entrySet()) {
                if (entry.getKey() == null) {
                    throw new IllegalArgumentException("valuesByFile contains a null file");
                }
                sources.put(ImageSource.file(entry.getKey()), entry.getValue());
            }
            return sources;
        }
    }

    static String normalizedPath(File file) {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        return file.getAbsoluteFile().toURI().normalize().getPath();
    }

    static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
