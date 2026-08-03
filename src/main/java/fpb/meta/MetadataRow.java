/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.meta;

import java.io.File;

/** One source image and its editable metadata labels. */
public final class MetadataRow {

    public final File file;
    public String group;
    public String subject;
    public String section;
    public String unassignedReason;

    public MetadataRow(File file) {
        this(file, "", "", "");
    }

    public MetadataRow(File file, String group, String subject, String section) {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        this.file = file.getAbsoluteFile();
        this.group = clean(group);
        this.subject = clean(subject);
        this.section = clean(section);
        this.unassignedReason = "";
    }

    public boolean isAssigned() {
        return !isBlank(group) && !isBlank(subject);
    }

    public void clearLabels(String reason) {
        group = "";
        subject = "";
        section = "";
        unassignedReason = clean(reason);
    }

    public void setLabels(String group, String subject, String section) {
        this.group = clean(group);
        this.subject = clean(subject);
        this.section = clean(section);
        this.unassignedReason = isAssigned() ? "" : "Missing group or subject";
    }

    static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    static boolean isBlank(String value) {
        return clean(value).isEmpty();
    }
}
