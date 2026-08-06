/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.stats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Section-level cross-group data, z-normalized independently per channel. */
public final class GroupQuantification {

    private final String statisticName;
    private final List<ChannelData> channels;

    private GroupQuantification(String statisticName, List<ChannelData> channels) {
        this.statisticName = clean(statisticName, "Statistic");
        this.channels = Collections.unmodifiableList(
                new ArrayList<ChannelData>(channels));
    }

    public static GroupQuantification from(
            SubjectAggregator.SubjectStats subjectStats) {
        if (subjectStats == null) {
            throw new IllegalArgumentException("subjectStats must not be null");
        }
        List<ChannelData> channels = new ArrayList<ChannelData>();
        for (int channel = 0; channel < subjectStats.channelCount(); channel++) {
            List<Double> allRawValues = new ArrayList<Double>();
            for (String group : subjectStats.groups()) {
                for (SubjectAggregator.SectionObservation section
                        : subjectStats.sectionsInGroup(group)) {
                    Double value = section.value(channel);
                    if (value != null && Double.isFinite(value.doubleValue())) {
                        allRawValues.add(value);
                    }
                }
            }
            Summary overall = Summary.of(allRawValues);
            List<GroupData> groups = new ArrayList<GroupData>();
            double largestAbsoluteZ = 1.0;
            for (String group : subjectStats.groups()) {
                List<SectionValue> values = new ArrayList<SectionValue>();
                for (SubjectAggregator.SectionObservation section
                        : subjectStats.sectionsInGroup(group)) {
                    Double raw = section.value(channel);
                    if (raw == null || !Double.isFinite(raw.doubleValue())) continue;
                    double z = zScore(raw.doubleValue(), overall.mean,
                            overall.standardDeviation);
                    values.add(new SectionValue(section.imageIndex(),
                            section.subject(), section.section(),
                            section.sourceLabel(), raw.doubleValue(), z));
                    largestAbsoluteZ = Math.max(largestAbsoluteZ, Math.abs(z));
                }
                GroupData groupData = new GroupData(group, values);
                groups.add(groupData);
                if (Double.isFinite(groupData.zMean())) {
                    largestAbsoluteZ = Math.max(largestAbsoluteZ,
                            Math.abs(groupData.zMean()));
                }
            }
            double axisLimit = largestAbsoluteZ * 1.12;
            channels.add(new ChannelData(subjectStats.sourceChannelIndex(channel),
                    subjectStats.channelName(channel), groups,
                    -axisLimit, axisLimit, overall.count, overall.mean,
                    overall.standardDeviation));
        }
        return new GroupQuantification(subjectStats.statisticName(), channels);
    }

    public String statisticName() { return statisticName; }
    public int channelCount() { return channels.size(); }
    public List<ChannelData> channels() { return channels; }
    public ChannelData channel(int logicalChannelIndex) {
        return channels.get(logicalChannelIndex);
    }

    public List<String> groups() {
        if (channels.isEmpty()) return Collections.emptyList();
        List<String> groups = new ArrayList<String>();
        for (GroupData group : channels.get(0).groups()) groups.add(group.group());
        return Collections.unmodifiableList(groups);
    }

    public double sharedAxisLimit() {
        double limit = 1.0;
        for (ChannelData channel : channels) {
            limit = Math.max(limit, Math.abs(channel.axisMinimum()));
            limit = Math.max(limit, Math.abs(channel.axisMaximum()));
        }
        return limit;
    }

    private static double zScore(double value, double mean, double sd) {
        if (!Double.isFinite(mean) || !Double.isFinite(sd) || sd <= 0.0) {
            return 0.0;
        }
        return (value - mean) / sd;
    }

    private static String clean(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    public static final class ChannelData {
        private final int channelIndex;
        private final String channelName;
        private final List<GroupData> groups;
        private final double axisMinimum;
        private final double axisMaximum;
        private final int overallCount;
        private final double overallMean;
        private final double overallStandardDeviation;

        private ChannelData(int channelIndex, String channelName,
                List<GroupData> groups, double axisMinimum, double axisMaximum,
                int overallCount, double overallMean,
                double overallStandardDeviation) {
            this.channelIndex = channelIndex;
            this.channelName = clean(channelName, "Channel");
            this.groups = Collections.unmodifiableList(
                    new ArrayList<GroupData>(groups));
            this.axisMinimum = axisMinimum;
            this.axisMaximum = axisMaximum;
            this.overallCount = overallCount;
            this.overallMean = overallMean;
            this.overallStandardDeviation = overallStandardDeviation;
        }

        public int channelIndex() { return channelIndex; }
        public String channelName() { return channelName; }
        public List<GroupData> groups() { return groups; }
        public double axisMinimum() { return axisMinimum; }
        public double axisMaximum() { return axisMaximum; }
        public int overallCount() { return overallCount; }
        public double overallMean() { return overallMean; }
        public double overallStandardDeviation() {
            return overallStandardDeviation;
        }
    }

    public static final class GroupData {
        private final String group;
        private final List<SectionValue> sections;
        private final int subjectCount;
        private final Summary rawSummary;
        private final double zMean;

        private GroupData(String group, List<SectionValue> sections) {
            this.group = clean(group, "Group");
            this.sections = Collections.unmodifiableList(
                    new ArrayList<SectionValue>(sections));
            LinkedHashSet<String> subjects = new LinkedHashSet<String>();
            List<Double> rawValues = new ArrayList<Double>();
            double zSum = 0.0;
            int zCount = 0;
            for (SectionValue section : sections) {
                subjects.add(section.subject());
                rawValues.add(Double.valueOf(section.rawValue()));
                if (Double.isFinite(section.zScore())) {
                    zSum += section.zScore();
                    zCount++;
                }
            }
            subjectCount = subjects.size();
            rawSummary = Summary.of(rawValues);
            zMean = zCount == 0 ? Double.NaN : zSum / zCount;
        }

        public String group() { return group; }
        public List<SectionValue> sections() { return sections; }
        public int sectionCount() { return sections.size(); }
        public int subjectCount() { return subjectCount; }
        public double mean() { return rawSummary.mean; }
        public double standardDeviation() { return rawSummary.standardDeviation; }
        public double standardError() { return rawSummary.standardError; }
        public double minimum() { return rawSummary.minimum; }
        public double maximum() { return rawSummary.maximum; }
        public double zMean() { return zMean; }

        public boolean containsSubject(String subject) {
            if (subject == null) return false;
            for (SectionValue section : sections) {
                if (subject.equals(section.subject())) return true;
            }
            return false;
        }
    }

    public static final class SectionValue {
        private final int imageIndex;
        private final String subject;
        private final String section;
        private final String sourceLabel;
        private final double rawValue;
        private final double zScore;

        private SectionValue(int imageIndex, String subject, String section,
                String sourceLabel, double rawValue, double zScore) {
            this.imageIndex = imageIndex;
            this.subject = clean(subject, "Subject");
            this.section = section == null ? "" : section.trim();
            this.sourceLabel = clean(sourceLabel, "Image");
            this.rawValue = rawValue;
            this.zScore = zScore;
        }

        public int imageIndex() { return imageIndex; }
        public String subject() { return subject; }
        public String section() { return section; }
        public String sourceLabel() { return sourceLabel; }
        public double rawValue() { return rawValue; }
        public double zScore() { return zScore; }
    }

    private static final class Summary {
        final int count;
        final double mean;
        final double standardDeviation;
        final double standardError;
        final double minimum;
        final double maximum;

        private Summary(int count, double mean, double standardDeviation,
                double standardError, double minimum, double maximum) {
            this.count = count;
            this.mean = mean;
            this.standardDeviation = standardDeviation;
            this.standardError = standardError;
            this.minimum = minimum;
            this.maximum = maximum;
        }

        static Summary of(List<Double> values) {
            if (values == null || values.isEmpty()) {
                return new Summary(0, Double.NaN, Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN);
            }
            double sum = 0.0;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            int count = 0;
            for (Double boxed : values) {
                if (boxed == null || !Double.isFinite(boxed.doubleValue())) continue;
                double value = boxed.doubleValue();
                sum += value;
                min = Math.min(min, value);
                max = Math.max(max, value);
                count++;
            }
            if (count == 0) return of(Collections.<Double>emptyList());
            double mean = sum / count;
            if (count < 2) {
                return new Summary(count, mean, Double.NaN, Double.NaN,
                        min, max);
            }
            double squares = 0.0;
            for (Double boxed : values) {
                if (boxed == null || !Double.isFinite(boxed.doubleValue())) continue;
                double difference = boxed.doubleValue() - mean;
                squares += difference * difference;
            }
            double sd = Math.sqrt(squares / (count - 1));
            return new Summary(count, mean, sd, sd / Math.sqrt(count), min, max);
        }
    }
}
