/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.meta;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Advanced filename labelling using a full-name regex and capture groups. */
public final class RegexStrategy implements LabelStrategy {

    private final Pattern pattern;
    private final int groupCapture;
    private final int subjectCapture;
    private final int sectionCapture;

    public RegexStrategy(String regex, int groupCapture, int subjectCapture,
            int sectionCapture) {
        if (MetadataRow.isBlank(regex)) throw new IllegalArgumentException("regex is blank");
        if (groupCapture <= 0) {
            throw new IllegalArgumentException("groupCapture must be a one-based capture index");
        }
        this.pattern = Pattern.compile(regex);
        this.groupCapture = groupCapture;
        this.subjectCapture = subjectCapture;
        this.sectionCapture = sectionCapture;
    }

    @Override
    public void apply(MetadataTable table) {
        for (MetadataRow row : table.rows()) {
            apply(row);
        }
    }

    public void apply(MetadataRow row) {
        Matcher matcher = pattern.matcher(row.file.getName());
        if (!matcher.matches()) {
            row.clearLabels("Filename did not match the regex");
            return;
        }
        if (matcher.groupCount() < groupCapture
                || (subjectCapture > 0 && matcher.groupCount() < subjectCapture)
                || (sectionCapture > 0 && matcher.groupCount() < sectionCapture)) {
            row.clearLabels("Regex capture group was not present");
            return;
        }
        String group = matcher.group(groupCapture);
        String subject = subjectCapture > 0
                ? matcher.group(subjectCapture)
                : MetadataTable.basenameWithoutExtension(row.file);
        String section = sectionCapture > 0 ? matcher.group(sectionCapture) : "";
        row.setLabels(group, subject, section);
    }
}
