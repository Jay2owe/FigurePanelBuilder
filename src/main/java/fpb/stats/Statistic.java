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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Built-in image statistics computed from full-resolution histograms. */
public final class Statistic {

    public static final String BRIGHTEST_ONE_PERCENT_NAME =
            "sampled_max_projection_brightest_1_percent_mean_v1";
    public static final double BRIGHTEST_FRACTION = 0.01;

    private Statistic() {}

    public static ImageValues brightestOnePercentMeans(HistogramCache cache) {
        if (cache == null) throw new IllegalArgumentException("cache must not be null");
        Map<File, Map<Integer, Double>> values =
                new LinkedHashMap<File, Map<Integer, Double>>();
        for (int imageIndex = 0; imageIndex < cache.imageCount(); imageIndex++) {
            HistogramCache.ImageHistograms image = cache.image(imageIndex);
            Map<Integer, Double> byChannel = new LinkedHashMap<Integer, Double>();
            for (int channelIndex = 0; channelIndex < image.channelCount(); channelIndex++) {
                byChannel.put(Integer.valueOf(channelIndex), Double.valueOf(
                        brightestOnePercentMean(image.histogram(channelIndex))));
            }
            values.put(image.sourceFile(), byChannel);
        }
        return ImageValues.of(values, defaultChannelNames(cache.channelCount()),
                BRIGHTEST_ONE_PERCENT_NAME);
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
        private final Map<String, File> filesByPath;
        private final Map<String, Map<Integer, Double>> valuesByPath;
        private final List<String> channelNames;
        private final String statisticName;

        private ImageValues(Map<File, Map<Integer, Double>> valuesByFile,
                List<String> channelNames, String statisticName) {
            if (valuesByFile == null) {
                throw new IllegalArgumentException("valuesByFile must not be null");
            }
            if (channelNames == null || channelNames.isEmpty()) {
                throw new IllegalArgumentException("channelNames must not be empty");
            }
            this.channelNames = Collections.unmodifiableList(
                    new ArrayList<String>(channelNames));
            this.statisticName = clean(statisticName).isEmpty()
                    ? "statistic" : clean(statisticName);

            Map<String, File> files = new LinkedHashMap<String, File>();
            Map<String, Map<Integer, Double>> values =
                    new LinkedHashMap<String, Map<Integer, Double>>();
            for (Map.Entry<File, Map<Integer, Double>> entry : valuesByFile.entrySet()) {
                File file = entry.getKey();
                if (file == null) {
                    throw new IllegalArgumentException("valuesByFile contains a null file");
                }
                String path = normalizedPath(file);
                files.put(path, file.getAbsoluteFile());
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
                values.put(path, Collections.unmodifiableMap(byChannel));
            }
            filesByPath = Collections.unmodifiableMap(files);
            valuesByPath = Collections.unmodifiableMap(values);
        }

        public static ImageValues of(Map<File, Map<Integer, Double>> valuesByFile,
                List<String> channelNames, String statisticName) {
            return new ImageValues(valuesByFile, channelNames, statisticName);
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
            return new ImageValues(nested, names, statisticName);
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

        public String statisticName() {
            return statisticName;
        }

        public Double value(File sourceFile, int channelIndex) {
            if (sourceFile == null) return null;
            Map<Integer, Double> byChannel = valuesByPath.get(normalizedPath(sourceFile));
            return byChannel == null ? null : byChannel.get(Integer.valueOf(channelIndex));
        }

        public Map<File, Map<Integer, Double>> valuesByFile() {
            Map<File, Map<Integer, Double>> copy =
                    new LinkedHashMap<File, Map<Integer, Double>>();
            for (Map.Entry<String, Map<Integer, Double>> entry : valuesByPath.entrySet()) {
                copy.put(filesByPath.get(entry.getKey()), entry.getValue());
            }
            return Collections.unmodifiableMap(copy);
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
