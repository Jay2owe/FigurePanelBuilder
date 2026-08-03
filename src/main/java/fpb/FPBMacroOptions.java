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
import fpb.render.FPBRenderer;
import fpb.ui.FPBWizard;
import fpb.ui.Step5Export;
import fpb.ui.chooser.RowImage;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Macro-facing Figure Panel Builder options, independent of dialogs. */
public final class FPBMacroOptions {

    public static final String PLUGIN_NAME = "Figure Panel Builder";

    private File folder;
    private boolean recursive;
    private FPBParameters.MetadataMode metadataMode =
            FPBParameters.MetadataMode.FILENAME_TOKENS;
    private File metadataCsv;
    private char separator = '_';
    private int groupToken = 1;
    private int subjectToken = 2;
    private int sectionToken;
    private String groupRegex = "";
    private int groupCapture = 1;
    private int subjectCapture = 2;
    private int sectionCapture;
    private final List<Integer> channels = new ArrayList<Integer>();
    private final List<String> channelNames = new ArrayList<String>();
    private final List<ChannelColour> channelLuts = new ArrayList<ChannelColour>();
    private final Map<String, DisplayRange> rangesByName =
            new LinkedHashMap<String, DisplayRange>();
    private String zMode = "max";
    private String statistic = "brightest_1pct";
    private File statisticCsv;
    private String statisticColumn = "";
    private final Map<String, String> picks = new LinkedHashMap<String, String>();
    private double scaleBarUm = 50.0;
    private PanelConfig.Position scaleBarCorner = PanelConfig.Position.BOTTOM_RIGHT;
    private int dpi = 300;
    private int exportScale = 1;
    private boolean writePng = true;
    private boolean writeTiff = true;
    private boolean writeSvg = true;
    private boolean writePanels = true;
    private boolean writeRecords = true;
    private File output;
    private String figureName = "Figure";
    private boolean hideDisplay;

    public File folder() { return folder; }
    public void setFolder(File folder) { this.folder = absolute(folder); }
    public boolean recursive() { return recursive; }
    public void setRecursive(boolean recursive) { this.recursive = recursive; }
    public FPBParameters.MetadataMode metadataMode() { return metadataMode; }
    public void setMetadataMode(FPBParameters.MetadataMode mode) {
        this.metadataMode = mode == null
                ? FPBParameters.MetadataMode.FILENAME_TOKENS : mode;
    }
    public File metadataCsv() { return metadataCsv; }
    public void setMetadataCsv(File csv) {
        this.metadataCsv = absolute(csv);
        if (csv != null) this.metadataMode = FPBParameters.MetadataMode.CSV;
    }
    public char separator() { return separator; }
    public void setSeparator(char separator) { this.separator = separator; }
    public int groupToken() { return groupToken; }
    public void setGroupToken(int groupToken) { this.groupToken = groupToken; }
    public int subjectToken() { return subjectToken; }
    public void setSubjectToken(int subjectToken) { this.subjectToken = subjectToken; }
    public int sectionToken() { return sectionToken; }
    public void setSectionToken(int sectionToken) { this.sectionToken = sectionToken; }
    public String groupRegex() { return groupRegex; }
    public void setGroupRegex(String groupRegex) {
        this.groupRegex = clean(groupRegex);
        if (hasText(groupRegex)) this.metadataMode = FPBParameters.MetadataMode.REGEX;
    }
    public int groupCapture() { return groupCapture; }
    public void setGroupCapture(int groupCapture) { this.groupCapture = groupCapture; }
    public int subjectCapture() { return subjectCapture; }
    public void setSubjectCapture(int subjectCapture) { this.subjectCapture = subjectCapture; }
    public int sectionCapture() { return sectionCapture; }
    public void setSectionCapture(int sectionCapture) { this.sectionCapture = sectionCapture; }
    public String zMode() { return zMode; }
    public void setZMode(String zMode) { this.zMode = cleanOrDefault(zMode, "max"); }
    public String statistic() { return statistic; }
    public void setStatistic(String statistic) {
        this.statistic = cleanOrDefault(statistic, "brightest_1pct");
    }
    public void setStatisticCsv(File csv) { this.statisticCsv = absolute(csv); }
    public void setStatisticColumn(String column) { this.statisticColumn = clean(column); }
    public void putRange(String channelName, DisplayRange range) {
        if (hasText(channelName)) rangesByName.put(optionSuffix(channelName), range);
    }
    public void putPick(String group, String subject) {
        if (hasText(group) && hasText(subject)) picks.put(group.trim(), subject.trim());
    }
    public void setScaleBarUm(double value) { this.scaleBarUm = value; }
    public void setScaleBarCorner(PanelConfig.Position corner) {
        this.scaleBarCorner = corner == null ? PanelConfig.Position.BOTTOM_RIGHT : corner;
    }
    public void setDpi(int dpi) { this.dpi = dpi; }
    public void setExportScale(int exportScale) { this.exportScale = exportScale; }
    public void setOutput(File output) { this.output = absolute(output); }
    public void setFigureName(String figureName) {
        this.figureName = cleanOrDefault(figureName, "Figure");
    }
    public void setHideDisplay(boolean hideDisplay) { this.hideDisplay = hideDisplay; }
    public void setWritePanels(boolean writePanels) { this.writePanels = writePanels; }
    public void setWriteRecords(boolean writeRecords) { this.writeRecords = writeRecords; }

    public void setFormats(List<String> formats) {
        writePng = writeTiff = writeSvg = false;
        if (formats == null || formats.isEmpty()) {
            writePng = writeTiff = writeSvg = true;
            return;
        }
        for (String format : formats) {
            String value = clean(format).toLowerCase(Locale.ROOT);
            if ("png".equals(value)) writePng = true;
            else if ("tif".equals(value) || "tiff".equals(value)) writeTiff = true;
            else if ("svg".equals(value)) writeSvg = true;
            else throw new IllegalArgumentException("Unknown export format: " + format);
        }
    }

    public void setChannels(List<Integer> indices, List<String> names,
            List<ChannelColour> colours) {
        channels.clear();
        channelNames.clear();
        channelLuts.clear();
        if (indices == null) return;
        for (int i = 0; i < indices.size(); i++) {
            int number = indices.get(i).intValue();
            if (number <= 0) throw new IllegalArgumentException("channels must be one-based.");
            channels.add(Integer.valueOf(number));
            channelNames.add(i < size(names) && hasText(names.get(i))
                    ? names.get(i).trim() : "C" + number);
            channelLuts.add(i < size(colours) && colours.get(i) != null
                    ? colours.get(i) : ChannelColour.GREY);
        }
    }

    public FPBParameters toParameters() {
        validate();
        FPBParameters.Builder builder = FPBParameters.builder(folder)
                .recursive(recursive)
                .metadataMode(metadataMode)
                .metadataCsv(metadataCsv)
                .separator(separator)
                .groupToken(groupToken)
                .subjectToken(subjectToken)
                .sectionToken(sectionToken)
                .groupRegex(groupRegex)
                .groupCapture(groupCapture)
                .subjectCapture(subjectCapture)
                .sectionCapture(sectionCapture)
                .zMode(zMode)
                .statistic(statistic)
                .statisticCsv(statisticCsv)
                .statisticColumn(statisticColumn)
                .picks(picks)
                .scaleBarUm(scaleBarUm)
                .scaleBarCorner(scaleBarCorner)
                .dpi(dpi)
                .exportScale(exportScale)
                .writePng(writePng)
                .writeTiff(writeTiff)
                .writeSvg(writeSvg)
                .writeIndividualPanels(writePanels)
                .writeRecords(writeRecords)
                .outputFolder(output)
                .figureName(figureName)
                .hideDisplay(hideDisplay);
        for (int i = 0; i < channels.size(); i++) {
            String name = channelNames.get(i);
            DisplayRange range = rangesByName.get(optionSuffix(name));
            if (range == null) {
                throw new IllegalArgumentException("range_" + optionSuffix(name)
                        + "_min and range_" + optionSuffix(name)
                        + "_max are required.");
            }
            builder.channel(channels.get(i).intValue(), name, channelLuts.get(i),
                    range.min(), range.max());
        }
        return builder.build();
    }

    public String toMacroOptions() {
        List<String> tokens = new ArrayList<String>();
        append(tokens, "folder", path(folder));
        if (recursive) tokens.add("recursive");
        if (metadataMode == FPBParameters.MetadataMode.SUBFOLDER) {
            tokens.add("group_from=subfolder");
        } else if (metadataMode == FPBParameters.MetadataMode.CSV) {
            append(tokens, "metadata_csv", path(metadataCsv));
        } else if (metadataMode == FPBParameters.MetadataMode.REGEX) {
            append(tokens, "group_regex", groupRegex);
            tokens.add("group_capture=" + groupCapture);
            if (subjectCapture > 0) tokens.add("subject_capture=" + subjectCapture);
            if (sectionCapture > 0) tokens.add("section_capture=" + sectionCapture);
        } else {
            tokens.add("separator=" + encodeValue(String.valueOf(separator)));
            tokens.add("group_token=" + groupToken);
            if (subjectToken > 0) tokens.add("subject_token=" + subjectToken);
            if (sectionToken > 0) tokens.add("section_token=" + sectionToken);
        }
        tokens.add("channels=" + joinIntegers(channels));
        tokens.add("channel_names=" + encodeValue(joinStrings(channelNames)));
        tokens.add("channel_luts=" + joinColours(channelLuts));
        tokens.add("z_mode=" + zMode);
        tokens.add("statistic=" + statistic);
        append(tokens, "statistic_csv", path(statisticCsv));
        append(tokens, "statistic_column", statisticColumn);
        for (int i = 0; i < channelNames.size(); i++) {
            String suffix = optionSuffix(channelNames.get(i));
            DisplayRange range = rangesByName.get(suffix);
            if (range != null) {
                tokens.add("range_" + suffix + "_min=" + range.min());
                tokens.add("range_" + suffix + "_max=" + range.max());
            }
        }
        for (Map.Entry<String, String> entry : picks.entrySet()) {
            tokens.add("pick_" + optionSuffix(entry.getKey())
                    + "=" + encodeValue(entry.getValue()));
        }
        tokens.add("scale_bar_um=" + formatDouble(scaleBarUm));
        tokens.add("scale_bar_corner=" + cornerName(scaleBarCorner));
        tokens.add("dpi=" + dpi);
        tokens.add("export_scale=" + exportScale);
        tokens.add("formats=" + formats());
        append(tokens, "output", path(output));
        append(tokens, "figure_name", figureName);
        if (!writePanels) tokens.add("hide_panels");
        if (!writeRecords) tokens.add("hide_records");
        if (hideDisplay) tokens.add("hide_display");
        return join(tokens);
    }

    public static FPBMacroOptions fromParameters(FPBParameters parameters) {
        FPBParameters.validate(parameters);
        FPBMacroOptions options = new FPBMacroOptions();
        options.setFolder(parameters.folder());
        options.setRecursive(parameters.recursive());
        options.setMetadataMode(parameters.metadataMode());
        options.setMetadataCsv(parameters.metadataCsv());
        options.setSeparator(parameters.separator());
        options.setGroupToken(parameters.groupToken());
        options.setSubjectToken(parameters.subjectToken());
        options.setSectionToken(parameters.sectionToken());
        options.setGroupRegex(parameters.groupRegex());
        options.setGroupCapture(parameters.groupCapture());
        options.setSubjectCapture(parameters.subjectCapture());
        options.setSectionCapture(parameters.sectionCapture());
        List<Integer> indices = new ArrayList<Integer>();
        List<String> names = new ArrayList<String>();
        List<ChannelColour> colours = new ArrayList<ChannelColour>();
        for (FPBParameters.Channel channel : parameters.channels()) {
            indices.add(Integer.valueOf(channel.channelNumber()));
            names.add(channel.name());
            colours.add(channel.colour());
            options.putRange(channel.name(), channel.range());
        }
        options.setChannels(indices, names, colours);
        options.setZMode(parameters.zMode());
        options.setStatistic(parameters.statistic());
        options.setStatisticCsv(parameters.statisticCsv());
        options.setStatisticColumn(parameters.statisticColumn());
        for (Map.Entry<String, String> entry : parameters.picks().entrySet()) {
            options.putPick(entry.getKey(), entry.getValue());
        }
        options.setScaleBarUm(parameters.scaleBarUm());
        options.setScaleBarCorner(parameters.scaleBarCorner());
        options.setDpi(parameters.dpi());
        options.setExportScale(parameters.exportScale());
        options.writePng = parameters.writePng();
        options.writeTiff = parameters.writeTiff();
        options.writeSvg = parameters.writeSvg();
        options.writePanels = parameters.writeIndividualPanels();
        options.writeRecords = parameters.writeRecords();
        options.setOutput(parameters.outputFolder());
        options.setFigureName(parameters.figureName());
        options.setHideDisplay(parameters.hideDisplay());
        return options;
    }

    public static FPBMacroOptions fromContext(FPBWizard.Context context,
            Step5Export.Settings settings) {
        if (context == null) throw new IllegalArgumentException("context is required");
        FPBMacroOptions options = new FPBMacroOptions();
        options.setFolder(context.folder);
        options.setRecursive(context.recursive);
        for (FPBRenderer.ChannelRequest request : context.layoutChannelRequests) {
            options.channels.add(Integer.valueOf(request.channelIndex() + 1));
            options.channelNames.add(request.name());
            options.channelLuts.add(request.colour());
            options.putRange(request.name(), request.range());
        }
        for (Map.Entry<String, RowImage.SubjectRow> entry
                : context.selectedRowsByGroup.entrySet()) {
            options.putPick(entry.getKey(), entry.getValue().subject());
        }
        PanelConfig config = context.panelConfig;
        if (config != null) {
            options.setScaleBarUm(config.scaleBarLengthUm());
            options.setScaleBarCorner(config.scaleBarPosition());
            options.setDpi(config.outputDpi());
            options.setExportScale(config.exportScale());
        }
        if (settings != null) {
            options.setOutput(settings.outputRoot());
            options.setFigureName(settings.figureName());
            options.setDpi(settings.dpi());
            options.setExportScale(settings.exportScale());
            options.writePng = settings.writePng();
            options.writeTiff = settings.writeTiff();
            options.writeSvg = settings.writeSvg();
            options.writePanels = settings.writeIndividualPanels();
            options.writeRecords = settings.writeRecords();
        }
        return options;
    }

    void validate() {
        if (folder == null) throw new IllegalArgumentException("folder is required.");
        if (channels.isEmpty()) throw new IllegalArgumentException("channels is required.");
        if (channelNames.size() != channels.size()
                || channelLuts.size() != channels.size()) {
            throw new IllegalArgumentException("channels, channel_names and channel_luts must match.");
        }
        if (!writePng && !writeTiff && !writeSvg) {
            throw new IllegalArgumentException("At least one export format is required.");
        }
    }

    static String optionSuffix(String value) {
        String clean = clean(value).toLowerCase(Locale.ROOT);
        if (clean.length() == 0) return "value";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
            else sb.append('_');
        }
        return sb.toString();
    }

    static String encodeValue(String value) {
        String normalized = clean(value).replace('\\', '/');
        if (normalized.indexOf('[') >= 0 || normalized.indexOf(']') >= 0
                || normalized.indexOf('"') >= 0 || normalized.indexOf('\n') >= 0
                || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Macro option values must not contain brackets, quotes, or line breaks.");
        }
        return "[" + normalized + "]";
    }

    private static void append(List<String> tokens, String key, String value) {
        if (hasText(value)) tokens.add(key + "=" + encodeValue(value));
    }

    private static String path(File file) {
        return file == null ? "" : file.getAbsolutePath();
    }

    private String formats() {
        List<String> values = new ArrayList<String>();
        if (writePng) values.add("png");
        if (writeTiff) values.add("tif");
        if (writeSvg) values.add("svg");
        return joinStrings(values);
    }

    private static String joinIntegers(List<Integer> values) {
        List<String> strings = new ArrayList<String>();
        for (Integer value : values) strings.add(String.valueOf(value));
        return joinStrings(strings);
    }

    private static String joinColours(List<ChannelColour> values) {
        List<String> strings = new ArrayList<String>();
        for (ChannelColour value : values) strings.add(value.name());
        return joinStrings(strings);
    }

    private static String joinStrings(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private static String join(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(tokens.get(i));
        }
        return sb.toString();
    }

    private static String cornerName(PanelConfig.Position position) {
        if (position == PanelConfig.Position.TOP_LEFT) return "top_left";
        if (position == PanelConfig.Position.TOP_RIGHT) return "top_right";
        if (position == PanelConfig.Position.BOTTOM_LEFT) return "bottom_left";
        return "bottom_right";
    }

    private static String formatDouble(double value) {
        if (Math.rint(value) == value && Double.isFinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.US, "%.3f", Double.valueOf(value));
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static File absolute(File file) {
        return file == null ? null : file.getAbsoluteFile();
    }

    private static String cleanOrDefault(String value, String fallback) {
        String clean = clean(value);
        return clean.length() == 0 ? fallback : clean;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
