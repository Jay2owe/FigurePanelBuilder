/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.stats;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-group, per-channel centre and range across subjects. */
public final class GroupStats {

    private final SubjectAggregator.SubjectStats subjectStats;
    private final Map<String, GroupChannelStats[]> statsByGroup;

    private GroupStats(SubjectAggregator.SubjectStats subjectStats,
            Map<String, GroupChannelStats[]> statsByGroup) {
        this.subjectStats = subjectStats;
        this.statsByGroup = Collections.unmodifiableMap(
                new LinkedHashMap<String, GroupChannelStats[]>(statsByGroup));
    }

    public static GroupStats from(SubjectAggregator.SubjectStats subjectStats) {
        if (subjectStats == null) {
            throw new IllegalArgumentException("subjectStats must not be null");
        }
        Map<String, GroupChannelStats[]> byGroup =
                new LinkedHashMap<String, GroupChannelStats[]>();
        for (String group : subjectStats.groups()) {
            GroupChannelStats[] channels =
                    new GroupChannelStats[subjectStats.channelCount()];
            for (int channel = 0; channel < subjectStats.channelCount(); channel++) {
                channels[channel] = compute(subjectStats, group, channel);
            }
            byGroup.put(group, channels);
        }
        return new GroupStats(subjectStats, byGroup);
    }

    public SubjectAggregator.SubjectStats subjectStats() {
        return subjectStats;
    }

    public double mean(String group, int channelIndex) {
        return channelStats(group, channelIndex).mean;
    }

    public double range(String group, int channelIndex) {
        return channelStats(group, channelIndex).range;
    }

    public int subjectCount(String group, int channelIndex) {
        return channelStats(group, channelIndex).subjectCount;
    }

    public double normalizedDeviation(String group, String subject, int channelIndex) {
        Double value = subjectStats.value(group, subject, channelIndex);
        if (value == null) return Double.NaN;
        double mean = mean(group, channelIndex);
        if (!Double.isFinite(mean)) return Double.NaN;
        double range = range(group, channelIndex);
        if (range == 0.0) return 0.0;
        return (value.doubleValue() - mean) / range;
    }

    private GroupChannelStats channelStats(String group, int channelIndex) {
        GroupChannelStats[] channels = statsByGroup.get(group);
        if (channels == null) {
            throw new IllegalArgumentException("Unknown group: " + group);
        }
        return channels[channelIndex];
    }

    private static GroupChannelStats compute(SubjectAggregator.SubjectStats stats,
            String group, int channelIndex) {
        double sum = 0.0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int count = 0;
        for (String subject : stats.subjectsInGroup(group)) {
            Double value = stats.value(group, subject, channelIndex);
            if (value == null) continue;
            double v = value.doubleValue();
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
            count++;
        }
        if (count == 0) {
            return new GroupChannelStats(Double.NaN, Double.NaN, 0);
        }
        return new GroupChannelStats(sum / (double) count, max - min, count);
    }

    private static final class GroupChannelStats {
        final double mean;
        final double range;
        final int subjectCount;

        GroupChannelStats(double mean, double range, int subjectCount) {
            this.mean = mean;
            this.range = range;
            this.subjectCount = subjectCount;
        }
    }
}
