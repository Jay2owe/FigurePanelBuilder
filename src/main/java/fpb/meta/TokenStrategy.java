/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.meta;

import java.util.LinkedHashMap;
import java.util.Map;

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

    public TokenStrategy(char separator, Map<Integer, Field> assignment) {
        this.separator = separator;
        if (assignment == null) {
            throw new IllegalArgumentException("assignment must not be null");
        }
        this.assignment = new LinkedHashMap<Integer, Field>(assignment);
    }

    public char separator() {
        return separator;
    }

    public Map<Integer, Field> assignment() {
        return new LinkedHashMap<Integer, Field>(assignment);
    }

    @Override
    public void apply(MetadataTable table) {
        for (MetadataRow row : table.rows()) {
            apply(row);
        }
    }

    public void apply(MetadataRow row) {
        String[] tokens = splitTokens(MetadataTable.basenameWithoutExtension(row.file), separator);
        String group = null;
        String subject = null;
        String section = "";

        for (Map.Entry<Integer, Field> entry : assignment.entrySet()) {
            int index = entry.getKey().intValue();
            if (index < 0 || index >= tokens.length) {
                row.clearLabels("Filename has too few tokens for the selected positions");
                return;
            }
            Field field = entry.getValue();
            if (field == Field.GROUP) group = tokens[index];
            else if (field == Field.SUBJECT) subject = tokens[index];
            else if (field == Field.SECTION) section = tokens[index];
        }

        if (MetadataRow.isBlank(group)) {
            row.clearLabels("No filename token was assigned to group");
            return;
        }
        if (MetadataRow.isBlank(subject)) subject = MetadataTable.basenameWithoutExtension(row.file);
        row.setLabels(group, subject, section);
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
