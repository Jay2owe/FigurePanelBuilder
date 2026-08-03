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
import java.util.List;
import java.util.Map;

/** Per-subject evidence for the suggestion and later selection records. */
public final class SelectionRecord {

    private final String group;
    private final String subject;
    private final int channelIndex;
    private final String channelName;
    private final double value;
    private final double groupMean;
    private final double rawDeviation;
    private final double deviation;
    private final int sectionCount;
    private final boolean suggested;

    public SelectionRecord(String group, String subject, int channelIndex,
            String channelName, double value, double groupMean,
            double rawDeviation, double deviation, int sectionCount,
            boolean suggested) {
        this.group = group;
        this.subject = subject;
        this.channelIndex = channelIndex;
        this.channelName = channelName;
        this.value = value;
        this.groupMean = groupMean;
        this.rawDeviation = rawDeviation;
        this.deviation = deviation;
        this.sectionCount = sectionCount;
        this.suggested = suggested;
    }

    public static List<SelectionRecord> from(
            SubjectAggregator.SubjectStats subjectStats,
            GroupStats groupStats,
            Map<String, Suggestion.Result> suggestions) {
        if (subjectStats == null) {
            throw new IllegalArgumentException("subjectStats must not be null");
        }
        if (groupStats == null) throw new IllegalArgumentException("groupStats must not be null");
        Map<String, Suggestion.Result> suggestionMap = suggestions == null
                ? Collections.<String, Suggestion.Result>emptyMap()
                : suggestions;
        List<SelectionRecord> records = new ArrayList<SelectionRecord>();
        for (String group : subjectStats.groups()) {
            Suggestion.Result suggestion = suggestionMap.get(group);
            for (String subject : subjectStats.subjectsInGroup(group)) {
                boolean suggested = suggestion != null && suggestion.isSuggested(subject);
                for (int channel = 0; channel < subjectStats.channelCount(); channel++) {
                    Double value = subjectStats.value(group, subject, channel);
                    double v = value == null ? Double.NaN : value.doubleValue();
                    double mean = groupStats.mean(group, channel);
                    double rawDeviation = Double.isFinite(v) && Double.isFinite(mean)
                            ? v - mean : Double.NaN;
                    double deviation = groupStats.normalizedDeviation(group, subject, channel);
                    records.add(new SelectionRecord(group, subject, channel,
                            subjectStats.channelName(channel), v, mean, rawDeviation,
                            deviation, subjectStats.sectionCount(group, subject, channel),
                            suggested));
                }
            }
        }
        return Collections.unmodifiableList(records);
    }

    public String group() {
        return group;
    }

    public String subject() {
        return subject;
    }

    public int channelIndex() {
        return channelIndex;
    }

    public String channelName() {
        return channelName;
    }

    public double value() {
        return value;
    }

    public double groupMean() {
        return groupMean;
    }

    public double rawDeviation() {
        return rawDeviation;
    }

    /** Signed range-normalised deviation used by the Stage 11 spine. */
    public double deviation() {
        return deviation;
    }

    public int sectionCount() {
        return sectionCount;
    }

    public boolean suggested() {
        return suggested;
    }
}
