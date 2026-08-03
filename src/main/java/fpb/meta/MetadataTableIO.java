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

    private MetadataTableIO() {}

    public static void exportCsv(MetadataTable table, File csvFile) throws IOException {
        if (table == null) throw new IllegalArgumentException("table must not be null");
        if (csvFile == null) throw new IllegalArgumentException("csvFile must not be null");
        File parent = csvFile.getAbsoluteFile().getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        Path temp = Files.createTempFile(parent == null ? null : parent.toPath(),
                "metadata-table-", ".csv.tmp");
        PrintWriter out = CsvSupport.newWriter(temp.toFile());
        try {
            out.println(CsvSupport.joinRow(Arrays.asList("File", "Group", "Subject", "Section")));
            for (MetadataRow row : table.rows()) {
                out.println(CsvSupport.joinRow(Arrays.asList(
                        table.csvFileName(row), row.group, row.subject, row.section)));
            }
        } finally {
            out.close();
        }
        IoUtils.commitReplacingSmallFile(temp, csvFile.toPath());
    }

    public static ImportResult importCsv(MetadataTable table, File csvFile) throws IOException {
        if (table == null) throw new IllegalArgumentException("table must not be null");
        if (csvFile == null) throw new IllegalArgumentException("csvFile must not be null");
        Map<String, MetadataRow> byKey = rowLookup(table);
        List<String> unmatched = new ArrayList<String>();

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(csvFile);
        try {
            CsvSupport.Record headerRecord = nextNonBlank(reader);
            if (headerRecord == null) throw new IOException("Metadata CSV is empty");
            String[] header = CsvSupport.parseRecord(headerRecord.text);
            int fileCol = column(header, "File");
            int groupCol = column(header, "Group");
            int subjectCol = column(header, "Subject");
            int sectionCol = column(header, "Section");

            CsvSupport.Record record;
            while ((record = reader.readRecord()) != null) {
                if (CsvSupport.isBlankRecord(record.text)) continue;
                String[] fields = CsvSupport.parseRecord(record.text);
                String key = value(fields, fileCol);
                MetadataRow row = byKey.get(normalizeKey(key));
                if (row == null) {
                    unmatched.add(key);
                } else {
                    row.setLabels(value(fields, groupCol),
                            value(fields, subjectCol), value(fields, sectionCol));
                }
            }
        } finally {
            reader.close();
        }
        return new ImportResult(unmatched);
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

    private static String value(String[] fields, int index) {
        return index < fields.length ? MetadataRow.clean(fields[index]) : "";
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

        private ImportResult(List<String> unmatchedFiles) {
            this.unmatchedFiles = java.util.Collections.unmodifiableList(
                    new ArrayList<String>(unmatchedFiles));
        }

        public List<String> unmatchedFiles() {
            return unmatchedFiles;
        }

        public boolean hasUnmatchedFiles() {
            return !unmatchedFiles.isEmpty();
        }
    }
}
