/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb;

import fpb.figure.PanelConfig;
import fpb.render.ChannelColour;
import fpb.render.DisplayRange;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parser for ImageJ macro options passed to Figure Panel Builder. */
public final class FPBMacroOptionsParser {

    private FPBMacroOptionsParser() {}

    public static FPBMacroOptions parse(String optionsText) {
        FPBMacroOptions options = new FPBMacroOptions();
        Set<String> seenKeys = new HashSet<String>();
        List<String> tokens = tokenize(optionsText == null ? "" : optionsText);
        PendingChannels pending = new PendingChannels();
        RangeAccumulator ranges = new RangeAccumulator();
        for (String token : tokens) {
            int eq = token.indexOf('=');
            if (eq >= 0) {
                String key = token.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = decodeValue(token.substring(eq + 1).trim());
                if (!seenKeys.add(key)) {
                    throw new IllegalArgumentException("Duplicate macro option: " + key);
                }
                applyKeyValue(options, pending, ranges, key, value);
            } else {
                applyFlag(options, token.toLowerCase(Locale.ROOT));
            }
        }
        options.setChannels(pending.channels, pending.names, pending.colours);
        ranges.apply(options);
        options.validate();
        return options;
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder token = new StringBuilder();
        boolean inBracket = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inBracket) {
                if (c == '[') throw new IllegalArgumentException(
                        "Nested brackets are not allowed in macro options.");
                if (c == '\n' || c == '\r') throw new IllegalArgumentException(
                        "Line breaks are not allowed in macro option values.");
                token.append(c);
                if (c == ']') inBracket = false;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (token.length() > 0) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
                continue;
            }
            if (c == '[') inBracket = true;
            else if (c == ']') throw new IllegalArgumentException(
                    "Unexpected closing bracket in macro options.");
            token.append(c);
        }
        if (inBracket) throw new IllegalArgumentException(
                "Unclosed bracketed macro option value.");
        if (token.length() > 0) tokens.add(token.toString());
        return tokens;
    }

    private static void applyKeyValue(FPBMacroOptions options,
            PendingChannels pending, RangeAccumulator ranges,
            String key, String value) {
        if ("folder".equals(key)) options.setFolder(new File(value));
        else if ("recursive".equals(key)) options.setRecursive(parseBoolean(value));
        else if ("metadata_csv".equals(key)) options.setMetadataCsv(new File(value));
        else if ("group_from".equals(key)) applyGroupFrom(options, value);
        else if ("separator".equals(key)) options.setSeparator(parseSeparator(value));
        else if ("group_token".equals(key)) options.setGroupToken(parseInt(key, value));
        else if ("subject_token".equals(key)) options.setSubjectToken(parseInt(key, value));
        else if ("section_token".equals(key)) options.setSectionToken(parseInt(key, value));
        else if ("group_regex".equals(key)) options.setGroupRegex(value);
        else if ("group_capture".equals(key)) options.setGroupCapture(parseInt(key, value));
        else if ("subject_capture".equals(key)) options.setSubjectCapture(parseInt(key, value));
        else if ("section_capture".equals(key)) options.setSectionCapture(parseInt(key, value));
        else if ("channels".equals(key)) pending.channels = parseIntegers(value);
        else if ("channel_names".equals(key)) pending.names = parseStrings(value);
        else if ("channel_luts".equals(key)) pending.colours = parseColours(value);
        else if ("z_mode".equals(key)) options.setZMode(value);
        else if ("statistic".equals(key)) options.setStatistic(value);
        else if ("statistic_csv".equals(key)) options.setStatisticCsv(new File(value));
        else if ("statistic_column".equals(key)) options.setStatisticColumn(value);
        else if ("scale_bar_um".equals(key)) options.setScaleBarUm(parseDouble(key, value));
        else if ("scale_bar_corner".equals(key)) options.setScaleBarCorner(parseCorner(value));
        else if ("dpi".equals(key)) options.setDpi(parseInt(key, value));
        else if ("export_scale".equals(key)) options.setExportScale(parseInt(key, value));
        else if ("formats".equals(key)) options.setFormats(parseStrings(value));
        else if ("output".equals(key)) options.setOutput(new File(value));
        else if ("figure_name".equals(key)) options.setFigureName(value);
        else if (key.startsWith("range_") && key.endsWith("_min")) {
            ranges.putMin(key.substring(6, key.length() - 4), parseInt(key, value));
        } else if (key.startsWith("range_") && key.endsWith("_max")) {
            ranges.putMax(key.substring(6, key.length() - 4), parseInt(key, value));
        } else if (key.startsWith("pick_")) {
            options.putPick(key.substring(5), value);
        } else {
            throw new IllegalArgumentException("Unknown FPB macro option: " + key);
        }
    }

    private static void applyFlag(FPBMacroOptions options, String flag) {
        if ("recursive".equals(flag)) options.setRecursive(true);
        else if ("no_recursive".equals(flag) || "flat".equals(flag)) options.setRecursive(false);
        else if ("hide_display".equals(flag) || "no_display".equals(flag)) options.setHideDisplay(true);
        else if ("hide_panels".equals(flag)) {
            options.setWritePanels(false);
        } else if ("hide_records".equals(flag)) {
            options.setWriteRecords(false);
        } else {
            throw new IllegalArgumentException("Unknown FPB macro flag: " + flag);
        }
    }

    private static void applyGroupFrom(FPBMacroOptions options, String value) {
        String mode = value.toLowerCase(Locale.ROOT);
        if ("filename".equals(mode) || "tokens".equals(mode)) {
            options.setMetadataMode(FPBParameters.MetadataMode.FILENAME_TOKENS);
        } else if ("subfolder".equals(mode) || "folder".equals(mode)) {
            options.setMetadataMode(FPBParameters.MetadataMode.SUBFOLDER);
        } else {
            throw new IllegalArgumentException("group_from must be filename or subfolder.");
        }
    }

    private static String decodeValue(String raw) {
        if (raw.length() >= 2 && raw.charAt(0) == '['
                && raw.charAt(raw.length() - 1) == ']') {
            String inner = raw.substring(1, raw.length() - 1);
            if (inner.indexOf('[') >= 0 || inner.indexOf(']') >= 0
                    || inner.indexOf('"') >= 0 || inner.indexOf('\\') >= 0
                    || inner.indexOf('\n') >= 0 || inner.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Bracketed macro values must not contain brackets, quotes, backslashes, or line breaks.");
            }
            return inner;
        }
        if (raw.indexOf('"') >= 0 || raw.indexOf('\\') >= 0
                || raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Macro values must not contain quotes, backslashes, or line breaks.");
        }
        return raw;
    }

    private static List<Integer> parseIntegers(String value) {
        List<Integer> out = new ArrayList<Integer>();
        for (String part : parseStrings(value)) out.add(Integer.valueOf(parseInt("channels", part)));
        return out;
    }

    private static List<String> parseStrings(String value) {
        List<String> out = new ArrayList<String>();
        if (value == null || value.trim().length() == 0) return out;
        String[] parts = value.split(",");
        for (int i = 0; i < parts.length; i++) {
            String clean = parts[i].trim();
            if (clean.length() > 0) out.add(clean);
        }
        return out;
    }

    private static List<ChannelColour> parseColours(String value) {
        List<ChannelColour> colours = new ArrayList<ChannelColour>();
        for (String name : parseStrings(value)) colours.add(ChannelColour.fromName(name));
        return colours;
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be an integer.");
        }
    }

    private static double parseDouble(String key, String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be numeric.");
        }
    }

    private static boolean parseBoolean(String value) {
        String clean = value.toLowerCase(Locale.ROOT);
        return "true".equals(clean) || "1".equals(clean) || "yes".equals(clean);
    }

    private static char parseSeparator(String value) {
        if (value == null || value.length() != 1) {
            throw new IllegalArgumentException("separator must be one character.");
        }
        return value.charAt(0);
    }

    private static PanelConfig.Position parseCorner(String value) {
        String clean = value.toLowerCase(Locale.ROOT);
        if ("top_left".equals(clean) || "topleft".equals(clean)) return PanelConfig.Position.TOP_LEFT;
        if ("top_right".equals(clean) || "topright".equals(clean)) return PanelConfig.Position.TOP_RIGHT;
        if ("bottom_left".equals(clean) || "bottomleft".equals(clean)) return PanelConfig.Position.BOTTOM_LEFT;
        if ("bottom_right".equals(clean) || "bottomright".equals(clean)) return PanelConfig.Position.BOTTOM_RIGHT;
        throw new IllegalArgumentException("scale_bar_corner is not recognised.");
    }

    private static final class PendingChannels {
        List<Integer> channels = new ArrayList<Integer>();
        List<String> names = new ArrayList<String>();
        List<ChannelColour> colours = new ArrayList<ChannelColour>();
    }

    private static final class RangeAccumulator {
        private final java.util.Map<String, Integer> min =
                new java.util.LinkedHashMap<String, Integer>();
        private final java.util.Map<String, Integer> max =
                new java.util.LinkedHashMap<String, Integer>();

        void putMin(String name, int value) { min.put(name, Integer.valueOf(value)); }
        void putMax(String name, int value) { max.put(name, Integer.valueOf(value)); }

        void apply(FPBMacroOptions options) {
            for (String name : min.keySet()) {
                Integer hi = max.get(name);
                if (hi != null) {
                    options.putRange(name, new DisplayRange(min.get(name).intValue(),
                            hi.intValue()));
                }
            }
            for (String name : max.keySet()) {
                if (!min.containsKey(name)) {
                    throw new IllegalArgumentException("range_" + name + "_min is required.");
                }
            }
            for (String name : min.keySet()) {
                if (!max.containsKey(name)) {
                    throw new IllegalArgumentException("range_" + name + "_max is required.");
                }
            }
        }
    }
}
