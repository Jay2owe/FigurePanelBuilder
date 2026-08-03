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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Applies the range-normalised representative-subject suggestion rule. */
public final class Suggestion {

    private Suggestion() {}

    public static Map<String, Result> suggest(GroupStats groupStats) {
        if (groupStats == null) throw new IllegalArgumentException("groupStats must not be null");
        SubjectAggregator.SubjectStats subjects = groupStats.subjectStats();
        Map<String, Result> results = new LinkedHashMap<String, Result>();
        for (String group : subjects.groups()) {
            Result result = suggestGroup(groupStats, group);
            if (!result.suggestedSubject().isEmpty()) results.put(group, result);
        }
        return Collections.unmodifiableMap(results);
    }

    public static Result suggestGroup(GroupStats groupStats, String group) {
        if (groupStats == null) throw new IllegalArgumentException("groupStats must not be null");
        SubjectAggregator.SubjectStats subjects = groupStats.subjectStats();
        List<CandidateScore> candidates = new ArrayList<CandidateScore>();
        for (String subject : subjects.subjectsInGroup(group)) {
            CandidateScore candidate = new CandidateScore(subject);
            for (int channel = 0; channel < subjects.channelCount(); channel++) {
                Double value = subjects.value(group, subject, channel);
                if (value == null) continue;
                double mean = groupStats.mean(group, channel);
                if (!Double.isFinite(mean)) continue;
                double range = groupStats.range(group, channel);
                double distance = range == 0.0
                        ? 0.0
                        : Math.abs(value.doubleValue() - mean) / range;
                candidate.addDistance(distance);
            }
            if (candidate.dimensionCount > 0) candidates.add(candidate);
        }
        Collections.sort(candidates, CandidateScore.BY_RULE);
        List<String> shortlist = new ArrayList<String>();
        for (int i = 0; i < candidates.size() && i < 3; i++) {
            shortlist.add(candidates.get(i).subject);
        }
        String suggested = shortlist.isEmpty() ? "" : shortlist.get(0);
        return new Result(group, suggested, shortlist);
    }

    private static final class CandidateScore {
        static final Comparator<CandidateScore> BY_RULE =
                new Comparator<CandidateScore>() {
                    @Override
                    public int compare(CandidateScore left, CandidateScore right) {
                        int byDistance = Double.compare(left.distanceSum, right.distanceSum);
                        if (byDistance != 0) return byDistance;
                        if (left.dimensionCount != right.dimensionCount) {
                            return right.dimensionCount - left.dimensionCount;
                        }
                        return left.subject.compareTo(right.subject);
                    }
                };

        final String subject;
        double distanceSum = 0.0;
        int dimensionCount = 0;

        CandidateScore(String subject) {
            this.subject = subject;
        }

        void addDistance(double distance) {
            if (!Double.isFinite(distance)) return;
            distanceSum += distance;
            dimensionCount++;
        }
    }

    public static final class Result {
        private final String group;
        private final String suggestedSubject;
        private final List<String> shortlist;

        private Result(String group, String suggestedSubject, List<String> shortlist) {
            this.group = group;
            this.suggestedSubject = suggestedSubject;
            this.shortlist = Collections.unmodifiableList(new ArrayList<String>(shortlist));
        }

        public String group() {
            return group;
        }

        public String suggestedSubject() {
            return suggestedSubject;
        }

        /** The suggested subject followed by up to two alternatives. */
        public List<String> shortlist() {
            return shortlist;
        }

        public boolean isSuggested(String subject) {
            return suggestedSubject.equals(subject);
        }
    }
}
