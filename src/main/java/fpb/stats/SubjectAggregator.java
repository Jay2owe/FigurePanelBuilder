/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.stats;

import fpb.meta.MetadataRow;
import fpb.meta.MetadataTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregates image/section-level values to the subject before ranking. */
public final class SubjectAggregator {

    private SubjectAggregator() {}

    public static SubjectStats aggregate(MetadataTable table,
            Statistic.ImageValues imageValues) {
        if (table == null) throw new IllegalArgumentException("table must not be null");
        if (imageValues == null) {
            throw new IllegalArgumentException("imageValues must not be null");
        }

        LinkedHashMap<String, List<Accumulator>> byGroupSubject =
                new LinkedHashMap<String, List<Accumulator>>();
        LinkedHashMap<String, List<String>> subjectsByGroup =
                new LinkedHashMap<String, List<String>>();

        for (MetadataRow row : table.rows()) {
            String group = Statistic.clean(row.group);
            String subject = Statistic.clean(row.subject);
            if (group.isEmpty() || subject.isEmpty()) continue;

            String key = key(group, subject);
            List<Accumulator> channels = byGroupSubject.get(key);
            if (channels == null) {
                channels = newAccumulators(imageValues.channelCount());
                byGroupSubject.put(key, channels);
                List<String> subjects = subjectsByGroup.get(group);
                if (subjects == null) {
                    subjects = new ArrayList<String>();
                    subjectsByGroup.put(group, subjects);
                }
                subjects.add(subject);
            }

            for (int channel = 0; channel < imageValues.channelCount(); channel++) {
                Double value = imageValues.value(row.file, channel);
                if (value != null && Double.isFinite(value.doubleValue())) {
                    channels.get(channel).add(value.doubleValue());
                }
            }
        }

        Map<String, SubjectData> dataByKey =
                new LinkedHashMap<String, SubjectData>();
        for (Map.Entry<String, List<Accumulator>> entry : byGroupSubject.entrySet()) {
            String[] split = splitKey(entry.getKey());
            dataByKey.put(entry.getKey(), new SubjectData(
                    split[0], split[1], entry.getValue()));
        }
        return new SubjectStats(imageValues.channelNames(), imageValues.statisticName(),
                subjectsByGroup, dataByKey);
    }

    private static List<Accumulator> newAccumulators(int channelCount) {
        List<Accumulator> accumulators = new ArrayList<Accumulator>(channelCount);
        for (int i = 0; i < channelCount; i++) accumulators.add(new Accumulator());
        return accumulators;
    }

    static String key(String group, String subject) {
        return group + "\u001f" + subject;
    }

    private static String[] splitKey(String key) {
        int split = key.indexOf('\u001f');
        return new String[] { key.substring(0, split), key.substring(split + 1) };
    }

    private static final class Accumulator {
        double sum = 0.0;
        int count = 0;

        void add(double value) {
            sum += value;
            count++;
        }

        double mean() {
            return count == 0 ? Double.NaN : sum / (double) count;
        }
    }

    public static final class SubjectStats {
        private final List<String> channelNames;
        private final String statisticName;
        private final Map<String, List<String>> subjectsByGroup;
        private final Map<String, SubjectData> dataByKey;

        private SubjectStats(List<String> channelNames, String statisticName,
                Map<String, List<String>> subjectsByGroup,
                Map<String, SubjectData> dataByKey) {
            this.channelNames = Collections.unmodifiableList(
                    new ArrayList<String>(channelNames));
            this.statisticName = statisticName;
            Map<String, List<String>> subjectCopy =
                    new LinkedHashMap<String, List<String>>();
            for (Map.Entry<String, List<String>> entry : subjectsByGroup.entrySet()) {
                subjectCopy.put(entry.getKey(), Collections.unmodifiableList(
                        new ArrayList<String>(entry.getValue())));
            }
            this.subjectsByGroup = Collections.unmodifiableMap(subjectCopy);
            this.dataByKey = Collections.unmodifiableMap(
                    new LinkedHashMap<String, SubjectData>(dataByKey));
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

        public List<String> groups() {
            return Collections.unmodifiableList(new ArrayList<String>(
                    subjectsByGroup.keySet()));
        }

        public List<String> subjectsInGroup(String group) {
            List<String> subjects = subjectsByGroup.get(group);
            return subjects == null ? Collections.<String>emptyList() : subjects;
        }

        public Double value(String group, String subject, int channelIndex) {
            SubjectData data = subjectData(group, subject);
            if (data == null) return null;
            double value = data.value(channelIndex);
            return Double.isFinite(value) ? Double.valueOf(value) : null;
        }

        public int sectionCount(String group, String subject, int channelIndex) {
            SubjectData data = subjectData(group, subject);
            return data == null ? 0 : data.sectionCount(channelIndex);
        }

        SubjectData subjectData(String group, String subject) {
            return dataByKey.get(key(group, subject));
        }
    }

    static final class SubjectData {
        final String group;
        final String subject;
        private final double[] values;
        private final int[] sectionCounts;

        SubjectData(String group, String subject, List<Accumulator> accumulators) {
            this.group = group;
            this.subject = subject;
            values = new double[accumulators.size()];
            sectionCounts = new int[accumulators.size()];
            for (int i = 0; i < accumulators.size(); i++) {
                Accumulator accumulator = accumulators.get(i);
                values[i] = accumulator.mean();
                sectionCounts[i] = accumulator.count;
            }
        }

        double value(int channelIndex) {
            return values[channelIndex];
        }

        int sectionCount(int channelIndex) {
            return sectionCounts[channelIndex];
        }
    }
}
