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
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Editable metadata table plus deterministic counts used by later stages. */
public final class MetadataTable {

    private static final List<String> IMAGE_EXTENSIONS = Collections.unmodifiableList(
            Arrays.asList("tif", "tiff", "png", "jpg", "jpeg", "gif", "bmp",
                    "lif", "czi", "nd2", "oib", "oif", "lsm"));

    private final File root;
    private final List<MetadataRow> rows;

    public MetadataTable(List<MetadataRow> rows) {
        this(null, rows);
    }

    public MetadataTable(File root, List<MetadataRow> rows) {
        if (rows == null) throw new IllegalArgumentException("rows must not be null");
        this.root = root == null ? null : root.getAbsoluteFile();
        List<MetadataRow> ordered = new ArrayList<MetadataRow>(rows);
        Collections.sort(ordered, rowComparator());
        this.rows = Collections.unmodifiableList(ordered);
    }

    public static MetadataTable empty(File root, List<File> files) throws IOException {
        return new MetadataTable(root, rowsFor(files));
    }

    public static MetadataTable fromFiles(File root, List<File> files,
            LabelStrategy strategy) throws IOException {
        if (strategy == null) throw new IllegalArgumentException("strategy must not be null");
        MetadataTable table = empty(root, files);
        strategy.apply(table);
        return table;
    }

    public static LabelStrategy suggest(List<File> files) {
        return suggest(null, files);
    }

    public static LabelStrategy suggest(File root, List<File> files) {
        List<File> ordered = normalizedSortedFiles(files);
        if (hasImageBearingSubfolders(root, ordered)) return new SubfolderStrategy();
        char separator = mostCommonSeparator(ordered);
        return new TokenStrategy(separator, guessAssignment(ordered, separator));
    }

    public File root() {
        return root;
    }

    public List<MetadataRow> rows() {
        return rows;
    }

    public int fileCount() {
        return rows.size();
    }

    public int groupCount() {
        Set<String> groups = new LinkedHashSet<String>();
        for (MetadataRow row : rows) {
            if (!MetadataRow.isBlank(row.group)) groups.add(row.group);
        }
        return groups.size();
    }

    public int subjectCount() {
        Set<String> subjects = new LinkedHashSet<String>();
        for (MetadataRow row : rows) {
            if (!MetadataRow.isBlank(row.subject)) {
                subjects.add(MetadataRow.clean(row.group) + "\u001f" + row.subject);
            }
        }
        return subjects.size();
    }

    public int unassignedCount() {
        int count = 0;
        for (MetadataRow row : rows) {
            if (!row.isAssigned()) count++;
        }
        return count;
    }

    public String summary() {
        return fileCount() + " files -> " + groupCount() + " groups, "
                + subjectCount() + " subjects, " + unassignedCount() + " unassigned";
    }

    public List<String> caseVariantGroups() {
        Map<String, Set<String>> byLower = new LinkedHashMap<String, Set<String>>();
        for (MetadataRow row : rows) {
            if (MetadataRow.isBlank(row.group)) continue;
            String key = row.group.toLowerCase(Locale.ROOT);
            Set<String> names = byLower.get(key);
            if (names == null) {
                names = new LinkedHashSet<String>();
                byLower.put(key, names);
            }
            names.add(row.group);
        }
        List<String> variants = new ArrayList<String>();
        for (Set<String> names : byLower.values()) {
            if (names.size() > 1) variants.addAll(names);
        }
        return Collections.unmodifiableList(variants);
    }

    public String csvFileName(MetadataRow row) {
        if (row == null) throw new IllegalArgumentException("row must not be null");
        if (root == null) return row.file.getName();
        try {
            Path rootPath = root.toPath().toAbsolutePath().normalize();
            Path filePath = row.file.toPath().toAbsolutePath().normalize();
            Path relative = rootPath.relativize(filePath);
            String value = relative.toString().replace(File.separatorChar, '/');
            return value.isEmpty() ? row.file.getName() : value;
        } catch (IllegalArgumentException outsideRoot) {
            return row.file.getName();
        }
    }

    static List<File> normalizedSortedFiles(List<File> files) {
        if (files == null) throw new IllegalArgumentException("files must not be null");
        List<File> ordered = new ArrayList<File>(files.size());
        for (File file : files) {
            if (file == null) throw new IllegalArgumentException("files contains null");
            if (isSupportedImage(file)) ordered.add(file.getAbsoluteFile());
        }
        Collections.sort(ordered, fileComparator());
        return ordered;
    }

    static boolean isSupportedImage(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.contains(extension);
    }

    static String basenameWithoutExtension(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    static Comparator<MetadataRow> rowComparator() {
        return new Comparator<MetadataRow>() {
            @Override
            public int compare(MetadataRow left, MetadataRow right) {
                return fileComparator().compare(left.file, right.file);
            }
        };
    }

    private static Comparator<File> fileComparator() {
        return new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                int byIgnoreCase = left.getAbsolutePath().compareToIgnoreCase(
                        right.getAbsolutePath());
                if (byIgnoreCase != 0) return byIgnoreCase;
                return left.getAbsolutePath().compareTo(right.getAbsolutePath());
            }
        };
    }

    private static List<MetadataRow> rowsFor(List<File> files) throws IOException {
        List<File> ordered = normalizedSortedFiles(files);
        if (ordered.size() != files.size()) {
            throw new IOException("Metadata table can only be built from supported image files.");
        }
        List<MetadataRow> rows = new ArrayList<MetadataRow>(ordered.size());
        for (File file : ordered) rows.add(new MetadataRow(file));
        return rows;
    }

    private static boolean hasImageBearingSubfolders(File root, List<File> files) {
        if (files.isEmpty()) return false;
        File base = root == null ? commonParent(files) : root.getAbsoluteFile();
        if (base == null) return false;
        String basePath = normalizedDirectoryPath(base);
        for (File file : files) {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !normalizedDirectoryPath(parent).equals(basePath)) {
                return true;
            }
        }
        return false;
    }

    private static File commonParent(List<File> files) {
        if (files.isEmpty()) return null;
        File common = files.get(0).getAbsoluteFile().getParentFile();
        for (int i = 1; i < files.size() && common != null; i++) {
            File parent = files.get(i).getAbsoluteFile().getParentFile();
            while (parent != null && !isSameOrChild(parent, common)) {
                common = common.getParentFile();
            }
        }
        return common;
    }

    private static boolean isSameOrChild(File child, File possibleParent) {
        Path childPath = child.toPath().toAbsolutePath().normalize();
        Path parentPath = possibleParent.toPath().toAbsolutePath().normalize();
        return childPath.equals(parentPath) || childPath.startsWith(parentPath);
    }

    private static String normalizedDirectoryPath(File file) {
        return file.toPath().toAbsolutePath().normalize().toString();
    }

    private static char mostCommonSeparator(List<File> files) {
        char[] candidates = new char[] { '_', '-', '.', ' ' };
        int[] counts = new int[candidates.length];
        for (File file : files) {
            String name = basenameWithoutExtension(file);
            for (int c = 0; c < candidates.length; c++) {
                for (int i = 0; i < name.length(); i++) {
                    if (name.charAt(i) == candidates[c]) counts[c]++;
                }
            }
        }
        int best = 0;
        for (int i = 1; i < candidates.length; i++) {
            if (counts[i] > counts[best]) best = i;
        }
        return candidates[best];
    }

    private static Map<Integer, TokenStrategy.Field> guessAssignment(List<File> files,
            char separator) {
        Map<Integer, Set<String>> distinct = new LinkedHashMap<Integer, Set<String>>();
        for (File file : files) {
            String[] tokens = TokenStrategy.splitTokens(basenameWithoutExtension(file), separator);
            for (int i = 0; i < tokens.length; i++) {
                Set<String> values = distinct.get(Integer.valueOf(i));
                if (values == null) {
                    values = new LinkedHashSet<String>();
                    distinct.put(Integer.valueOf(i), values);
                }
                values.add(tokens[i]);
            }
        }
        Map<Integer, TokenStrategy.Field> assignment =
                new LinkedHashMap<Integer, TokenStrategy.Field>();
        if (distinct.isEmpty()) return assignment;

        Integer groupIndex = null;
        Integer subjectIndex = null;
        for (Integer index : distinct.keySet()) {
            if (groupIndex == null
                    || distinct.get(index).size() < distinct.get(groupIndex).size()) {
                groupIndex = index;
            }
        }
        for (Integer index : distinct.keySet()) {
            if (index.equals(groupIndex)) continue;
            if (subjectIndex == null
                    || distinct.get(index).size() > distinct.get(subjectIndex).size()) {
                subjectIndex = index;
            }
        }
        if (groupIndex != null) assignment.put(groupIndex, TokenStrategy.Field.GROUP);
        if (subjectIndex != null) assignment.put(subjectIndex, TokenStrategy.Field.SUBJECT);
        return assignment;
    }
}
