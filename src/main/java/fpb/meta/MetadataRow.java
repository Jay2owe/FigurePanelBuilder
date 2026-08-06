/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.meta;

import fpb.io.ImageSource;

import java.io.File;

/** One source image and its editable metadata labels. */
public final class MetadataRow {

    public final ImageSource source;
    public final File file;
    public String group;
    public String subject;
    public String section;
    public String unassignedReason;

    public MetadataRow(File file) {
        this(ImageSource.file(file), "", "", "");
    }

    public MetadataRow(File file, String group, String subject, String section) {
        this(ImageSource.file(file), group, subject, section);
    }

    public MetadataRow(ImageSource source) {
        this(source, "", "", "");
    }

    public MetadataRow(ImageSource source, String group, String subject,
            String section) {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        this.source = source;
        this.file = source.file();
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
