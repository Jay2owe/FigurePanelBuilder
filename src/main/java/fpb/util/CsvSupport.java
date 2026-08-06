/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.util;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Focused UTF-8 CSV parsing and writing support. */
public final class CsvSupport {

    public static final Charset CHARSET = StandardCharsets.UTF_8;
    private static final int MAX_RECORD_CHARACTERS = 16 * 1024 * 1024;
    private static final int MAX_FIELDS_PER_RECORD = 100_000;

    private CsvSupport() {}

    public static PrintWriter newWriter(File file) throws IOException {
        return new PrintWriter(Files.newBufferedWriter(file.toPath(), CHARSET));
    }

    /** Converts PrintWriter's otherwise swallowed flush/write failure into IOException. */
    public static void requireNoError(PrintWriter writer, File file)
            throws IOException {
        if (writer == null) throw new IOException("Writer is unavailable.");
        writer.flush();
        if (writer.checkError()) {
            throw new IOException("Could not write "
                    + (file == null ? "output" : file.getAbsolutePath()) + ".");
        }
    }

    public static String joinRow(List<String> values) {
        if (values == null) throw new IllegalArgumentException("CSV row is null");
        StringBuilder row = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) row.append(',');
            row.append(escapeField(values.get(index)));
        }
        return row.toString();
    }

    /**
     * Writes selected columns as spreadsheet text by adding one versioned,
     * reversible apostrophe prefix. This preserves identifiers such as 001.
     */
    public static String joinRowProtectingText(List<String> values,
            int... protectedColumns) {
        if (values == null) throw new IllegalArgumentException("CSV row is null");
        StringBuilder row = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) row.append(',');
            row.append(escapeField(values.get(index), contains(protectedColumns, index)));
        }
        return row.toString();
    }

    public static RecordReader openRecordReader(File file) throws IOException {
        return new RecordReader(file);
    }

    public static boolean isBlankRecord(String record) {
        return record == null || record.trim().isEmpty();
    }

    /** Removes one UTF-8 byte-order mark from the start of a logical CSV record. */
    public static String stripUtf8Bom(String record) {
        return record != null && !record.isEmpty() && record.charAt(0) == '\uFEFF'
                ? record.substring(1) : record;
    }

    public static String[] parseRecord(String record) throws IOException {
        if (record == null) throw new IOException("CSV record is null");
        if (record.length() > MAX_RECORD_CHARACTERS) {
            throw new IOException("CSV record exceeds the " + MAX_RECORD_CHARACTERS
                    + " character limit");
        }

        List<String> fields = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean afterClosingQuote = false;

        for (int index = 0; index < record.length(); index++) {
            char current = record.charAt(index);
            if (inQuotes) {
                if (current == '"') {
                    if (index + 1 < record.length() && record.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        inQuotes = false;
                        afterClosingQuote = true;
                    }
                } else {
                    field.append(current);
                }
                continue;
            }

            if (afterClosingQuote) {
                if (current == ',') {
                    addField(fields, field);
                    field.setLength(0);
                    afterClosingQuote = false;
                } else if (!Character.isWhitespace(current)) {
                    throw malformed("Unexpected character after closing quote", record, index);
                }
                continue;
            }

            if (current == ',') {
                addField(fields, field);
                field.setLength(0);
            } else if (current == '"') {
                if (field.length() != 0) {
                    throw malformed("Unexpected quote inside unquoted field", record, index);
                }
                inQuotes = true;
            } else {
                field.append(current);
            }
        }

        if (inQuotes) throw new IOException("Unterminated quoted CSV field");
        addField(fields, field);
        return fields.toArray(new String[fields.size()]);
    }

    private static void addField(List<String> fields, StringBuilder field) throws IOException {
        if (fields.size() >= MAX_FIELDS_PER_RECORD) {
            throw new IOException("CSV record exceeds the " + MAX_FIELDS_PER_RECORD
                    + " field limit");
        }
        fields.add(field.toString());
    }

    private static IOException malformed(String message, String record, int index) {
        String preview = record.length() <= 120 ? record : record.substring(0, 120) + "...";
        return new IOException(message + " at column " + (index + 1) + ": " + preview);
    }

    private static String escapeField(String value) {
        return escapeField(value, false);
    }

    private static String escapeField(String value, boolean protectAsText) {
        String raw = value == null ? "" : value;
        String text = protectAsText && !raw.isEmpty() ? "'" + raw
                : spreadsheetSafe(raw);
        boolean quote = text.indexOf(',') >= 0
                || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0
                || startsOrEndsWithWhitespace(text);
        return quote ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    private static String spreadsheetSafe(String text) {
        if (isPlainNumber(text)) return text;
        int apostrophes = 0;
        while (apostrophes < text.length() && text.charAt(apostrophes) == '\'') {
            apostrophes++;
        }
        int offset = apostrophes;
        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            if (codePoint == '=' || codePoint == '+' || codePoint == '-'
                    || codePoint == '@' || codePoint == '\t'
                    || codePoint == '\r' || codePoint == '\n') {
                return "'" + text;
            }
            if (!Character.isWhitespace(codePoint)
                    && !Character.isSpaceChar(codePoint)
                    && !Character.isISOControl(codePoint)
                    && Character.getType(codePoint) != Character.FORMAT) {
                break;
            }
            offset += Character.charCount(codePoint);
        }
        return text;
    }

    /** Reverses one spreadsheet-safety prefix in a versioned FPB data field. */
    public static String restoreSpreadsheetSafe(String text) {
        if (text == null || text.isEmpty() || text.charAt(0) != '\'') return text;
        int apostrophes = 0;
        while (apostrophes < text.length() && text.charAt(apostrophes) == '\'') {
            apostrophes++;
        }
        String afterPrefixes = text.substring(apostrophes);
        return beginsWithFormulaTrigger(afterPrefixes) ? text.substring(1) : text;
    }

    /** Removes the single prefix added by joinRowProtectingText. */
    public static String restoreSpreadsheetText(String text) {
        if (text == null || text.isEmpty() || text.charAt(0) != '\'') return text;
        return text.substring(1);
    }

    private static boolean contains(int[] values, int wanted) {
        if (values == null) return false;
        for (int value : values) if (value == wanted) return true;
        return false;
    }

    private static boolean isPlainNumber(String text) {
        if (text == null) return false;
        String clean = text.trim();
        return clean.matches("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?");
    }

    private static boolean beginsWithFormulaTrigger(String text) {
        int offset = 0;
        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            if (codePoint == '=' || codePoint == '+' || codePoint == '-'
                    || codePoint == '@' || codePoint == '\t'
                    || codePoint == '\r' || codePoint == '\n') return true;
            if (!Character.isWhitespace(codePoint)
                    && !Character.isSpaceChar(codePoint)
                    && !Character.isISOControl(codePoint)
                    && Character.getType(codePoint) != Character.FORMAT) return false;
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean startsOrEndsWithWhitespace(String text) {
        return !text.isEmpty() && (Character.isWhitespace(text.charAt(0))
                || Character.isWhitespace(text.charAt(text.length() - 1)));
    }

    /** One logical CSV record and its inclusive physical line span. */
    public static final class Record {
        public final String text;
        public final int startLineNumber;
        public final int endLineNumber;

        private Record(String text, int startLineNumber, int endLineNumber) {
            this.text = text;
            this.startLineNumber = startLineNumber;
            this.endLineNumber = endLineNumber;
        }
    }

    /** Streaming reader that preserves newlines inside quoted fields. */
    public static final class RecordReader implements Closeable {
        private final BufferedReader reader;
        private final String sourceName;
        private int nextLineNumber = 1;
        private int pendingCharacter = -1;

        private RecordReader(File file) throws IOException {
            if (file == null) throw new IOException("CSV input file is null");
            reader = new BufferedReader(new InputStreamReader(
                    Files.newInputStream(file.toPath()),
                    CHARSET.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)));
            sourceName = file.getAbsolutePath();
        }

        public Record readRecord() throws IOException {
            int current = readCharacter();
            if (current < 0) return null;

            int startLine = nextLineNumber;
            StringBuilder record = new StringBuilder(256);
            boolean inQuotes = false;

            while (current >= 0) {
                char character = (char) current;
                if (character == '"') {
                    appendBounded(record, character, startLine);
                    if (inQuotes) {
                        int next = readCharacter();
                        if (next == '"') {
                            appendBounded(record, '"', startLine);
                            current = readCharacter();
                            continue;
                        }
                        inQuotes = false;
                        current = next;
                        continue;
                    }
                    inQuotes = true;
                    current = readCharacter();
                    continue;
                }

                if (character == '\r' || character == '\n') {
                    boolean crlf = false;
                    if (character == '\r') {
                        int next = readCharacter();
                        if (next == '\n') crlf = true;
                        else pendingCharacter = next;
                    }
                    if (inQuotes) {
                        appendBounded(record, character, startLine);
                        if (crlf) appendBounded(record, '\n', startLine);
                        nextLineNumber++;
                        current = readCharacter();
                        continue;
                    }
                    nextLineNumber++;
                    return new Record(record.toString(), startLine, nextLineNumber - 1);
                }

                appendBounded(record, character, startLine);
                current = readCharacter();
            }

            if (inQuotes) {
                throw new IOException("Malformed CSV in " + sourceName + " starting at line "
                        + startLine + ": unterminated quoted field");
            }
            return new Record(record.toString(), startLine, nextLineNumber);
        }

        private int readCharacter() throws IOException {
            if (pendingCharacter >= 0) {
                int current = pendingCharacter;
                pendingCharacter = -1;
                return current;
            }
            return reader.read();
        }

        private void appendBounded(StringBuilder record, char value, int startLine)
                throws IOException {
            if (record.length() >= MAX_RECORD_CHARACTERS) {
                throw new IOException("CSV record in " + sourceName + " starting at line "
                        + startLine + " exceeds the character limit");
            }
            record.append(value);
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }
}
