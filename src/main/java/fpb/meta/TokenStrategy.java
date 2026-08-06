/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.meta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Labels rows by splitting the filename stem on one separator. */
public final class TokenStrategy implements LabelStrategy {

    public enum Field {
        GROUP,
        SUBJECT,
        SECTION,
        IGNORE
    }

    private final char separator;
    private final Map<Integer, Field> assignment;
    private final boolean splitSeriesLabels;

    public TokenStrategy(char separator, Map<Integer, Field> assignment) {
        this(separator, assignment, false);
    }

    private TokenStrategy(char separator, Map<Integer, Field> assignment,
            boolean splitSeriesLabels) {
        this.separator = separator;
        if (assignment == null) {
            throw new IllegalArgumentException("assignment must not be null");
        }
        this.assignment = new LinkedHashMap<Integer, Field>(assignment);
        this.splitSeriesLabels = splitSeriesLabels;
    }

    /** Creates a strategy whose token positions refer to each container series label. */
    public static TokenStrategy forSeriesLabels(char separator,
            Map<Integer, Field> assignment) {
        return new TokenStrategy(separator, assignment, true);
    }

    public char separator() {
        return separator;
    }

    public Map<Integer, Field> assignment() {
        return new LinkedHashMap<Integer, Field>(assignment);
    }

    public boolean splitsSeriesLabels() {
        return splitSeriesLabels;
    }

    /** Guesses series roles using Animal_Hemisphere_Region[_Condition]. */
    public static Map<Integer, Field> guessSeriesAssignment(List<String> labels,
            char separator) {
        Map<Integer, Set<String>> distinct = new LinkedHashMap<Integer, Set<String>>();
        Map<Integer, Integer> hemisphereCounts = new LinkedHashMap<Integer, Integer>();
        int maximumTokens = 0;
        if (labels != null) {
            for (String label : labels) {
                String[] tokens = splitTokens(label, separator);
                maximumTokens = Math.max(maximumTokens, tokens.length);
                for (int index = 0; index < tokens.length; index++) {
                    Integer key = Integer.valueOf(index);
                    Set<String> values = distinct.get(key);
                    if (values == null) {
                        values = new LinkedHashSet<String>();
                        distinct.put(key, values);
                    }
                    values.add(tokens[index]);
                    if (isHemisphere(tokens[index])) {
                        Integer count = hemisphereCounts.get(key);
                        hemisphereCounts.put(key, Integer.valueOf(
                                count == null ? 1 : count.intValue() + 1));
                    }
                }
            }
        }

        Map<Integer, Field> guessed = new LinkedHashMap<Integer, Field>();
        Integer hemisphereIndex = mostFrequentIndex(hemisphereCounts);
        if (hemisphereIndex != null) {
            int hemi = hemisphereIndex.intValue();
            for (int index = 0; index < hemi; index++) {
                guessed.put(Integer.valueOf(index), Field.SUBJECT);
            }
            guessed.put(hemisphereIndex, Field.SECTION);
            if (hemi + 1 < maximumTokens) {
                guessed.put(Integer.valueOf(hemi + 1), Field.SECTION);
            }
            for (int index = hemi + 2; index < maximumTokens; index++) {
                guessed.put(Integer.valueOf(index), Field.GROUP);
            }
            return guessed;
        }

        Integer groupIndex = leastDistinctIndex(distinct);
        Integer subjectIndex = mostDistinctIndexExcept(distinct, groupIndex);
        if (groupIndex != null) guessed.put(groupIndex, Field.GROUP);
        if (subjectIndex != null) guessed.put(subjectIndex, Field.SUBJECT);
        return guessed;
    }

    @Override
    public void apply(MetadataTable table) {
        for (MetadataRow row : table.rows()) {
            apply(row);
        }
    }

    public void apply(MetadataRow row) {
        if (row.source.isSeries()) {
            applySeries(row);
            return;
        }
        String[] tokens = splitTokens(MetadataTable.basenameWithoutExtension(row.file), separator);
        for (Integer index : assignment.keySet()) {
            if (assignment.get(index) == Field.IGNORE) continue;
            if (index == null || index.intValue() < 0
                    || index.intValue() >= tokens.length) {
                row.clearLabels("Filename has too few tokens for the selected positions");
                return;
            }
        }

        String group = joinedTokens(tokens, Field.GROUP);
        String subject = joinedTokens(tokens, Field.SUBJECT);
        String section = joinedTokens(tokens, Field.SECTION);

        if (MetadataRow.isBlank(group)) {
            row.clearLabels("No filename token was assigned to group");
            return;
        }
        if (MetadataRow.isBlank(subject)) subject = MetadataTable.basenameWithoutExtension(row.file);
        row.setLabels(group, subject, section);
    }

    private void applySeries(MetadataRow row) {
        String seriesName = MetadataTable.seriesSubject(row);
        String[] tokens = splitTokens(seriesName, separator);
        Map<Integer, Field> effectiveAssignment = splitSeriesLabels
                ? assignment
                : guessSeriesAssignment(Collections.singletonList(seriesName), separator);
        for (Integer index : effectiveAssignment.keySet()) {
            if (effectiveAssignment.get(index) == Field.IGNORE) continue;
            if (index == null || index.intValue() < 0
                    || index.intValue() >= tokens.length) {
                row.clearLabels("Series name has too few tokens for the selected positions");
                return;
            }
        }
        String group = joinedTokens(tokens, Field.GROUP, effectiveAssignment);
        String subject = joinedTokens(tokens, Field.SUBJECT, effectiveAssignment);
        String section = joinedTokens(tokens, Field.SECTION, effectiveAssignment);
        if (MetadataRow.isBlank(subject)) subject = seriesName;
        if (MetadataRow.isBlank(group)) group = guessGroupFromSubject(subject);
        row.setLabels(group, subject, section);
    }

    private String joinedTokens(String[] tokens, Field field) {
        return joinedTokens(tokens, field, assignment);
    }

    private String joinedTokens(String[] tokens, Field field,
            Map<Integer, Field> positions) {
        StringBuilder joined = new StringBuilder();
        for (int index = 0; index < tokens.length; index++) {
            if (positions.get(Integer.valueOf(index)) != field) continue;
            if (joined.length() > 0) joined.append(separator);
            joined.append(tokens[index]);
        }
        return joined.toString();
    }

    static String guessGroupFromSubject(String subject) {
        String value = MetadataRow.clean(subject);
        if (value.isEmpty()) return "";
        int trailingStart = trailingDigitStart(value);
        if (trailingStart > 0 && containsLetter(value.substring(0, trailingStart))) {
            return trimSeparators(value.substring(0, trailingStart));
        }
        for (int start = 0; start < value.length(); start++) {
            if (!Character.isDigit(value.charAt(start))) continue;
            int end = start + 1;
            while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
            if (!containsLetter(value.substring(0, start))) continue;
            int suffix = end;
            while (suffix < value.length() && isJoinSeparator(value.charAt(suffix))) suffix++;
            if (suffix < value.length() && Character.isLetter(value.charAt(suffix))) {
                String before = trimSeparators(value.substring(0, start));
                String after = trimSeparators(value.substring(end));
                return before + preferredJoiner(value.substring(0, start),
                        value.substring(end)) + after;
            }
        }
        return value;
    }

    private static Integer mostFrequentIndex(Map<Integer, Integer> counts) {
        Integer best = null;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            if (best == null || entry.getValue().intValue()
                    > counts.get(best).intValue()) best = entry.getKey();
        }
        return best;
    }

    private static Integer leastDistinctIndex(Map<Integer, Set<String>> distinct) {
        Integer best = null;
        for (Map.Entry<Integer, Set<String>> entry : distinct.entrySet()) {
            if (best == null || entry.getValue().size()
                    < distinct.get(best).size()) best = entry.getKey();
        }
        return best;
    }

    private static Integer mostDistinctIndexExcept(Map<Integer, Set<String>> distinct,
            Integer excluded) {
        Integer best = null;
        for (Map.Entry<Integer, Set<String>> entry : distinct.entrySet()) {
            if (entry.getKey().equals(excluded)) continue;
            if (best == null || entry.getValue().size()
                    > distinct.get(best).size()) best = entry.getKey();
        }
        return best;
    }

    private static boolean isHemisphere(String token) {
        return "LH".equalsIgnoreCase(MetadataRow.clean(token))
                || "RH".equalsIgnoreCase(MetadataRow.clean(token));
    }

    private static int trailingDigitStart(String value) {
        int index = value.length();
        while (index > 0 && Character.isDigit(value.charAt(index - 1))) index--;
        return index == value.length() ? -1 : index;
    }

    private static boolean containsLetter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isLetter(value.charAt(index))) return true;
        }
        return false;
    }

    private static boolean isJoinSeparator(char value) {
        return value == '_' || value == '-';
    }

    private static String trimSeparators(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isJoinSeparator(value.charAt(start))) start++;
        while (end > start && isJoinSeparator(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static String preferredJoiner(String before, String after) {
        if (!before.isEmpty() && isJoinSeparator(before.charAt(before.length() - 1))) {
            return String.valueOf(before.charAt(before.length() - 1));
        }
        if (!after.isEmpty() && isJoinSeparator(after.charAt(0))) {
            return String.valueOf(after.charAt(0));
        }
        return "";
    }

    static String[] splitTokens(String value, char separator) {
        if (value == null) return new String[] { "" };
        if (separator == '\0') return new String[] { MetadataRow.clean(value) };
        java.util.List<String> tokens = new java.util.ArrayList<String>();
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == separator) {
                tokens.add(MetadataRow.clean(value.substring(start, i)));
                start = i + 1;
            }
        }
        tokens.add(MetadataRow.clean(value.substring(start)));
        return tokens.toArray(new String[tokens.size()]);
    }
}
