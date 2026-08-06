/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.stats;

import fpb.io.ImageSource;
import fpb.meta.MetadataRow;
import fpb.meta.MetadataTable;
import fpb.meta.MetadataTableIO;
import fpb.util.CsvSupport;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads one numeric CSV column and joins it to metadata rows by filename. */
public final class StatCsvLoader {

    private StatCsvLoader() {}

    public static LoadResult load(File csvFile, String numericColumn,
            MetadataTable table) throws IOException {
        if (csvFile == null) throw new IllegalArgumentException("csvFile must not be null");
        if (table == null) throw new IllegalArgumentException("table must not be null");
        String requestedColumn = Statistic.clean(numericColumn);
        if (requestedColumn.isEmpty()) {
            throw new IOException("Pick a numeric statistic column before loading CSV values.");
        }
        if (!csvFile.isFile()) {
            throw new IOException("CSV file not found: " + csvFile.getAbsolutePath());
        }

        Map<String, MetadataRow> rowsByKey = rowLookup(table);
        List<String> unmatched = new ArrayList<String>();
        List<String> duplicates = new ArrayList<String>();
        Map<ImageSource, Double> values = new LinkedHashMap<ImageSource, Double>();
        Set<MetadataRow> matchedRows = new LinkedHashSet<MetadataRow>();
        boolean sawFiniteValue = false;

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(csvFile);
        try {
            CsvSupport.Record headerRecord = nextNonBlank(reader);
            if (headerRecord == null) throw new IOException("Statistic CSV is empty");
            String[] header = CsvSupport.parseRecord(
                    CsvSupport.stripUtf8Bom(headerRecord.text));
            int fileColumn = fileColumn(header);
            int valueColumn = exactColumn(header, requestedColumn, csvFile);
            int versionColumn = exactColumn(header,
                    MetadataTableIO.VERSION_COLUMN, null);

            CsvSupport.Record record;
            while ((record = reader.readRecord()) != null) {
                if (CsvSupport.isBlankRecord(record.text)) continue;
                String[] fields = CsvSupport.parseRecord(record.text);
                String version = value(fields, versionColumn);
                String key = MetadataTableIO.restoreVersionedText(
                        value(fields, fileColumn), version);
                MetadataRow row = rowsByKey.get(normalizeKey(key));
                if (row == null) {
                    unmatched.add(key);
                    continue;
                }
                if (!matchedRows.add(row)) {
                    duplicates.add(key);
                    continue;
                }
                String text = value(fields, valueColumn);
                if (text.isEmpty()) continue;
                Double parsed = parseFiniteDouble(text);
                if (parsed == null) {
                    throw new IOException("Column '" + requestedColumn
                            + "' contains a non-numeric value at line "
                            + record.startLineNumber + ": " + text);
                }
                sawFiniteValue = true;
                values.put(row.source, parsed);
            }
        } finally {
            reader.close();
        }

        if (!sawFiniteValue) {
            throw new IOException("Column '" + requestedColumn
                    + "' does not contain a finite numeric value matched to the metadata table.");
        }
        List<String> uncovered = new ArrayList<String>();
        for (MetadataRow row : table.rows()) {
            if (!values.containsKey(row.source)) uncovered.add(table.csvFileName(row));
        }
        return new LoadResult(requestedColumn, values, unmatched, duplicates, uncovered);
    }

    private static CsvSupport.Record nextNonBlank(CsvSupport.RecordReader reader)
            throws IOException {
        CsvSupport.Record record;
        while ((record = reader.readRecord()) != null) {
            if (!CsvSupport.isBlankRecord(record.text)) return record;
        }
        return null;
    }

    private static int fileColumn(String[] header) throws IOException {
        int file = exactColumn(header, "File", null);
        if (file >= 0) return file;
        int filename = exactColumn(header, "Filename", null);
        if (filename >= 0) return filename;
        throw new IOException("Statistic CSV is missing required column File");
    }

    private static int exactColumn(String[] header, String name, File csvFile)
            throws IOException {
        for (int i = 0; i < header.length; i++) {
            if (name.equals(Statistic.clean(header[i]))) return i;
        }
        if (csvFile == null) return -1;
        throw new IOException("Exact column '" + name + "' was not found in "
                + csvFile.getAbsolutePath());
    }

    private static String value(String[] fields, int index) {
        return index >= 0 && index < fields.length ? Statistic.clean(fields[index]) : "";
    }

    private static Double parseFiniteDouble(String text) {
        try {
            double value = Double.parseDouble(text);
            return Double.isFinite(value) ? Double.valueOf(value) : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
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
        return Statistic.clean(key).replace('\\', '/');
    }

    public static final class LoadResult {
        private final String columnName;
        private final Map<ImageSource, Double> valuesBySource;
        private final Map<File, Double> valuesByFile;
        private final List<String> unmatchedFiles;
        private final List<String> duplicateFiles;
        private final List<String> uncoveredFiles;

        private LoadResult(String columnName, Map<ImageSource, Double> valuesBySource,
                List<String> unmatchedFiles, List<String> duplicateFiles,
                List<String> uncoveredFiles) {
            this.columnName = columnName;
            this.valuesBySource = Collections.unmodifiableMap(
                    new LinkedHashMap<ImageSource, Double>(valuesBySource));
            Map<File, Double> byFile = new LinkedHashMap<File, Double>();
            for (Map.Entry<ImageSource, Double> entry : valuesBySource.entrySet()) {
                byFile.put(entry.getKey().file(), entry.getValue());
            }
            this.valuesByFile = Collections.unmodifiableMap(
                    byFile);
            this.unmatchedFiles = Collections.unmodifiableList(
                    new ArrayList<String>(unmatchedFiles));
            this.duplicateFiles = Collections.unmodifiableList(
                    new ArrayList<String>(duplicateFiles));
            this.uncoveredFiles = Collections.unmodifiableList(
                    new ArrayList<String>(uncoveredFiles));
        }

        public String columnName() {
            return columnName;
        }

        public Map<File, Double> valuesByFile() {
            return valuesByFile;
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

        /** Exact one-to-one coverage is required before scientific ranking. */
        public boolean isComplete() {
            return unmatchedFiles.isEmpty() && duplicateFiles.isEmpty()
                    && uncoveredFiles.isEmpty();
        }

        public String problemSummary() {
            List<String> problems = new ArrayList<String>();
            if (!unmatchedFiles.isEmpty()) problems.add("unknown rows: " + unmatchedFiles);
            if (!duplicateFiles.isEmpty()) problems.add("duplicate rows: " + duplicateFiles);
            if (!uncoveredFiles.isEmpty()) problems.add("uncovered images: " + uncoveredFiles);
            return problems.toString();
        }

        public Statistic.ImageValues imageValues() {
            if (!isComplete()) {
                throw new IllegalStateException("Statistic CSV join is incomplete: "
                        + problemSummary());
            }
            return Statistic.ImageValues.singleChannelSources(valuesBySource,
                    "Channel-independent (" + columnName + ")", columnName);
        }
    }
}
