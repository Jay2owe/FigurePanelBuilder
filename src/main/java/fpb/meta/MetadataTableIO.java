/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.meta;

import fpb.util.CsvSupport;
import fpb.util.IoUtils;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** CSV import/export for the metadata table. */
public final class MetadataTableIO {

    public static final String VERSION_COLUMN = "FPBMetadataVersion";
    private static final String VERSION = "3";
    private static final String LEGACY_REVERSIBLE_VERSION = "2";

    private MetadataTableIO() {}

    public static void exportCsv(MetadataTable table, File csvFile) throws IOException {
        if (table == null) throw new IllegalArgumentException("table must not be null");
        if (csvFile == null) throw new IllegalArgumentException("csvFile must not be null");
        File parent = csvFile.getAbsoluteFile().getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        Path temp = Files.createTempFile(parent == null ? null : parent.toPath(),
                "metadata-table-", ".csv.tmp");
        boolean moved = false;
        try {
            PrintWriter out = CsvSupport.newWriter(temp.toFile());
            try {
                out.println(CsvSupport.joinRow(Arrays.asList("File", "Group", "Subject",
                        "Section", VERSION_COLUMN)));
                for (MetadataRow row : table.rows()) {
                    out.println(CsvSupport.joinRowProtectingText(Arrays.asList(
                            table.csvFileName(row), row.group, row.subject, row.section,
                            VERSION), 0, 1, 2, 3));
                }
                CsvSupport.requireNoError(out, temp.toFile());
            } finally {
                out.close();
            }
            IoUtils.commitReplacingSmallFile(temp, csvFile.toPath());
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temp);
        }
    }

    public static ImportResult importCsv(MetadataTable table, File csvFile) throws IOException {
        if (table == null) throw new IllegalArgumentException("table must not be null");
        if (csvFile == null) throw new IllegalArgumentException("csvFile must not be null");
        Map<String, MetadataRow> byKey = rowLookup(table);
        List<String> unmatched = new ArrayList<String>();
        List<String> duplicates = new ArrayList<String>();
        Map<MetadataRow, String[]> pending = new LinkedHashMap<MetadataRow, String[]>();

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(csvFile);
        try {
            CsvSupport.Record headerRecord = nextNonBlank(reader);
            if (headerRecord == null) throw new IOException("Metadata CSV is empty");
            String[] header = CsvSupport.parseRecord(
                    CsvSupport.stripUtf8Bom(headerRecord.text));
            int fileCol = column(header, "File");
            int groupCol = column(header, "Group");
            int subjectCol = column(header, "Subject");
            int sectionCol = column(header, "Section");
            int versionCol = optionalColumn(header, VERSION_COLUMN);

            CsvSupport.Record record;
            while ((record = reader.readRecord()) != null) {
                if (CsvSupport.isBlankRecord(record.text)) continue;
                String[] fields = CsvSupport.parseRecord(record.text);
                String version = value(fields, versionCol);
                String key = replayValue(fields, fileCol, version);
                MetadataRow row = byKey.get(normalizeKey(key));
                if (row == null) {
                    unmatched.add(key);
                } else if (pending.containsKey(row)) {
                    duplicates.add(key);
                } else {
                    pending.put(row, new String[] {
                            replayValue(fields, groupCol, version),
                            replayValue(fields, subjectCol, version),
                            replayValue(fields, sectionCol, version) });
                }
            }
        } finally {
            reader.close();
        }
        List<String> uncovered = new ArrayList<String>();
        for (MetadataRow row : table.rows()) {
            if (!pending.containsKey(row)) uncovered.add(table.csvFileName(row));
        }
        ImportResult result = new ImportResult(unmatched, duplicates, uncovered);
        if (result.isComplete()) {
            for (Map.Entry<MetadataRow, String[]> entry : pending.entrySet()) {
                String[] labels = entry.getValue();
                entry.getKey().setLabels(labels[0], labels[1], labels[2]);
            }
        }
        return result;
    }

    private static CsvSupport.Record nextNonBlank(CsvSupport.RecordReader reader)
            throws IOException {
        CsvSupport.Record record;
        while ((record = reader.readRecord()) != null) {
            if (!CsvSupport.isBlankRecord(record.text)) return record;
        }
        return null;
    }

    private static int column(String[] header, String name) throws IOException {
        for (int i = 0; i < header.length; i++) {
            if (name.equals(MetadataRow.clean(header[i]))) return i;
        }
        throw new IOException("Metadata CSV is missing required column " + name);
    }

    private static int optionalColumn(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (name.equals(MetadataRow.clean(header[i]))) return i;
        }
        return -1;
    }

    private static String value(String[] fields, int index) {
        return index >= 0 && index < fields.length
                ? MetadataRow.clean(fields[index]) : "";
    }

    private static String replayValue(String[] fields, int index,
            String version) {
        return restoreVersionedText(value(fields, index), version);
    }

    /** Restores one field written by a versioned FPB metadata export. */
    public static String restoreVersionedText(String value, String version) {
        String clean = MetadataRow.clean(value);
        if (VERSION.equals(version)) {
            return MetadataRow.clean(CsvSupport.restoreSpreadsheetText(clean));
        }
        if (LEGACY_REVERSIBLE_VERSION.equals(version)) {
            return MetadataRow.clean(CsvSupport.restoreSpreadsheetSafe(clean));
        }
        return clean;
    }

    private static Map<String, MetadataRow> rowLookup(MetadataTable table) {
        Map<String, MetadataRow> lookup = new LinkedHashMap<String, MetadataRow>();
        Map<String, MetadataRow> basename = new LinkedHashMap<String, MetadataRow>();
        Set<String> duplicateBasenames = new LinkedHashSet<String>();

        for (MetadataRow row : table.rows()) {
            lookup.put(normalizeKey(table.csvFileName(row)), row);
            String base = normalizeKey(row.file.getName());
            if (basename.containsKey(base)) duplicateBasenames.add(base);
            else basename.put(base, row);
        }
        for (Map.Entry<String, MetadataRow> entry : basename.entrySet()) {
            if (!duplicateBasenames.contains(entry.getKey())) {
                lookup.put(entry.getKey(), entry.getValue());
            }
        }
        return lookup;
    }

    private static String normalizeKey(String key) {
        return MetadataRow.clean(key).replace('\\', '/');
    }

    public static final class ImportResult {
        private final List<String> unmatchedFiles;
        private final List<String> duplicateFiles;
        private final List<String> uncoveredFiles;

        private ImportResult(List<String> unmatchedFiles, List<String> duplicateFiles,
                List<String> uncoveredFiles) {
            this.unmatchedFiles = java.util.Collections.unmodifiableList(
                    new ArrayList<String>(unmatchedFiles));
            this.duplicateFiles = java.util.Collections.unmodifiableList(
                    new ArrayList<String>(duplicateFiles));
            this.uncoveredFiles = java.util.Collections.unmodifiableList(
                    new ArrayList<String>(uncoveredFiles));
        }

        public List<String> unmatchedFiles() {
            return unmatchedFiles;
        }

        public boolean hasUnmatchedFiles() {
            return !unmatchedFiles.isEmpty();
        }

        public List<String> duplicateFiles() {
            return duplicateFiles;
        }

        public List<String> uncoveredFiles() {
            return uncoveredFiles;
        }

        public boolean isComplete() {
            return unmatchedFiles.isEmpty() && duplicateFiles.isEmpty()
                    && uncoveredFiles.isEmpty();
        }

        public String problemSummary() {
            List<String> parts = new ArrayList<String>();
            if (!unmatchedFiles.isEmpty()) {
                parts.add("unknown CSV files: " + join(unmatchedFiles));
            }
            if (!duplicateFiles.isEmpty()) {
                parts.add("duplicate CSV files: " + join(duplicateFiles));
            }
            if (!uncoveredFiles.isEmpty()) {
                parts.add("input files missing from CSV: " + join(uncoveredFiles));
            }
            return parts.isEmpty() ? "Metadata CSV is complete."
                    : "Metadata CSV does not match the input folder ("
                    + join(parts) + ").";
        }

        private static String join(List<String> values) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) text.append(", ");
                text.append(values.get(i));
            }
            return text.toString();
        }
    }
}
