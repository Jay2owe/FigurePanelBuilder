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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable input bundle for the headless Figure Panel Builder API. */
public final class FPBParameters {

    public enum MetadataMode {
        FILENAME_TOKENS,
        SUBFOLDER,
        CSV,
        REGEX
    }

    private final File folder;
    private final boolean recursive;
    private final MetadataMode metadataMode;
    private final File metadataCsv;
    private final char separator;
    private final int groupToken;
    private final int subjectToken;
    private final int sectionToken;
    private final String groupRegex;
    private final int groupCapture;
    private final int subjectCapture;
    private final int sectionCapture;
    private final List<Channel> channels;
    private final String zMode;
    private final String statistic;
    private final File statisticCsv;
    private final String statisticColumn;
    private final Map<String, String> picks;
    private final double scaleBarUm;
    private final PanelConfig.Position scaleBarCorner;
    private final int dpi;
    private final int exportScale;
    private final boolean writePng;
    private final boolean writeTiff;
    private final boolean writeSvg;
    private final boolean writeIndividualPanels;
    private final boolean writeRecords;
    private final File outputFolder;
    private final String figureName;
    private final boolean hideDisplay;

    private FPBParameters(Builder builder) {
        this.folder = absolute(builder.folder);
        this.recursive = builder.recursive;
        this.metadataMode = builder.metadataMode == null
                ? MetadataMode.FILENAME_TOKENS : builder.metadataMode;
        this.metadataCsv = absolute(builder.metadataCsv);
        this.separator = builder.separator;
        this.groupToken = builder.groupToken;
        this.subjectToken = builder.subjectToken;
        this.sectionToken = builder.sectionToken;
        this.groupRegex = clean(builder.groupRegex);
        this.groupCapture = builder.groupCapture;
        this.subjectCapture = builder.subjectCapture;
        this.sectionCapture = builder.sectionCapture;
        this.channels = Collections.unmodifiableList(
                new ArrayList<Channel>(builder.channels));
        this.zMode = cleanOrDefault(builder.zMode, "max");
        this.statistic = cleanOrDefault(builder.statistic, "brightest_1pct");
        this.statisticCsv = absolute(builder.statisticCsv);
        this.statisticColumn = clean(builder.statisticColumn);
        this.picks = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(builder.picks));
        this.scaleBarUm = builder.scaleBarUm;
        this.scaleBarCorner = builder.scaleBarCorner == null
                ? PanelConfig.Position.BOTTOM_RIGHT : builder.scaleBarCorner;
        this.dpi = builder.dpi;
        this.exportScale = builder.exportScale;
        this.writePng = builder.writePng;
        this.writeTiff = builder.writeTiff;
        this.writeSvg = builder.writeSvg;
        this.writeIndividualPanels = builder.writeIndividualPanels;
        this.writeRecords = builder.writeRecords;
        this.outputFolder = absolute(builder.outputFolder);
        this.figureName = cleanOrDefault(builder.figureName, "Figure");
        this.hideDisplay = builder.hideDisplay;
    }

    public static Builder builder(File folder) {
        return new Builder().folder(folder);
    }

    public Builder toBuilder() {
        return new Builder()
                .folder(folder)
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
                .channels(channels)
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
                .writeIndividualPanels(writeIndividualPanels)
                .writeRecords(writeRecords)
                .outputFolder(outputFolder)
                .figureName(figureName)
                .hideDisplay(hideDisplay);
    }

    public File folder() { return folder; }
    public boolean recursive() { return recursive; }
    public MetadataMode metadataMode() { return metadataMode; }
    public File metadataCsv() { return metadataCsv; }
    public char separator() { return separator; }
    public int groupToken() { return groupToken; }
    public int subjectToken() { return subjectToken; }
    public int sectionToken() { return sectionToken; }
    public String groupRegex() { return groupRegex; }
    public int groupCapture() { return groupCapture; }
    public int subjectCapture() { return subjectCapture; }
    public int sectionCapture() { return sectionCapture; }
    public List<Channel> channels() { return channels; }
    public String zMode() { return zMode; }
    public String statistic() { return statistic; }
    public File statisticCsv() { return statisticCsv; }
    public String statisticColumn() { return statisticColumn; }
    public Map<String, String> picks() { return picks; }
    public double scaleBarUm() { return scaleBarUm; }
    public PanelConfig.Position scaleBarCorner() { return scaleBarCorner; }
    public int dpi() { return dpi; }
    public int exportScale() { return exportScale; }
    public boolean writePng() { return writePng; }
    public boolean writeTiff() { return writeTiff; }
    public boolean writeSvg() { return writeSvg; }
    public boolean writeIndividualPanels() { return writeIndividualPanels; }
    public boolean writeRecords() { return writeRecords; }
    public File outputFolder() { return outputFolder; }
    public String figureName() { return figureName; }
    public boolean hideDisplay() { return hideDisplay; }

    static void validate(FPBParameters parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("FPB parameters must not be null.");
        }
        if (parameters.folder == null || !parameters.folder.isDirectory()) {
            throw new IllegalArgumentException("Input folder does not exist: "
                    + parameters.folder);
        }
        if (parameters.metadataMode == MetadataMode.CSV
                && (parameters.metadataCsv == null || !parameters.metadataCsv.isFile())) {
            throw new IllegalArgumentException("metadata_csv does not exist: "
                    + parameters.metadataCsv);
        }
        if (parameters.metadataMode == MetadataMode.REGEX
                && !hasText(parameters.groupRegex)) {
            throw new IllegalArgumentException("group_regex is required for regex metadata.");
        }
        if (parameters.channels.isEmpty()) {
            throw new IllegalArgumentException("At least one channel with an explicit range is required.");
        }
        Map<String, Boolean> names = new LinkedHashMap<String, Boolean>();
        for (Channel channel : parameters.channels) {
            if (channel == null) {
                throw new IllegalArgumentException("Channel list contains null.");
            }
            if (channel.range() == null || !channel.range().isValid()) {
                throw new IllegalArgumentException("No display range locked for channel '"
                        + channel.name() + "'. Figure Panel Builder never applies automatic per-image contrast.");
            }
            String key = channel.name().toLowerCase(java.util.Locale.ROOT);
            if (names.put(key, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("Duplicate channel name: " + channel.name());
            }
        }
        if (parameters.outputFolder != null && parameters.outputFolder.exists()
                && !parameters.outputFolder.isDirectory()) {
            throw new IllegalArgumentException("Output path is not a folder: "
                    + parameters.outputFolder);
        }
    }

    private static File absolute(File file) {
        return file == null ? null : file.getAbsoluteFile();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String cleanOrDefault(String value, String fallback) {
        String clean = clean(value);
        return clean.length() == 0 ? fallback : clean;
    }

    private static boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }

    public static final class Channel {
        private final int channelIndex;
        private final String name;
        private final ChannelColour colour;
        private final DisplayRange range;

        public Channel(int channelIndex, String name, ChannelColour colour,
                DisplayRange range) {
            if (channelIndex < 0) {
                throw new IllegalArgumentException("channelIndex is negative");
            }
            this.channelIndex = channelIndex;
            this.name = cleanOrDefault(name, "C" + (channelIndex + 1));
            this.colour = colour == null ? ChannelColour.GREY : colour;
            this.range = range;
        }

        public int channelIndex() { return channelIndex; }
        public int channelNumber() { return channelIndex + 1; }
        public String name() { return name; }
        public ChannelColour colour() { return colour; }
        public DisplayRange range() { return range; }
    }

    public static final class Builder {
        private File folder;
        private boolean recursive;
        private MetadataMode metadataMode = MetadataMode.FILENAME_TOKENS;
        private File metadataCsv;
        private char separator = '_';
        private int groupToken = 1;
        private int subjectToken = 2;
        private int sectionToken = 0;
        private String groupRegex = "";
        private int groupCapture = 1;
        private int subjectCapture = 2;
        private int sectionCapture = 0;
        private List<Channel> channels = new ArrayList<Channel>();
        private String zMode = "max";
        private String statistic = "brightest_1pct";
        private File statisticCsv;
        private String statisticColumn = "";
        private Map<String, String> picks = new LinkedHashMap<String, String>();
        private double scaleBarUm = 50.0;
        private PanelConfig.Position scaleBarCorner = PanelConfig.Position.BOTTOM_RIGHT;
        private int dpi = 300;
        private int exportScale = 1;
        private boolean writePng = true;
        private boolean writeTiff = true;
        private boolean writeSvg = true;
        private boolean writeIndividualPanels = true;
        private boolean writeRecords = true;
        private File outputFolder;
        private String figureName = "Figure";
        private boolean hideDisplay;

        public Builder folder(File folder) {
            this.folder = folder;
            return this;
        }

        public Builder recursive(boolean recursive) {
            this.recursive = recursive;
            return this;
        }

        public Builder metadataMode(MetadataMode metadataMode) {
            this.metadataMode = metadataMode;
            return this;
        }

        public Builder metadataCsv(File metadataCsv) {
            this.metadataCsv = metadataCsv;
            if (metadataCsv != null) this.metadataMode = MetadataMode.CSV;
            return this;
        }

        public Builder separator(char separator) {
            this.separator = separator;
            return this;
        }

        public Builder groupToken(int token) {
            this.groupToken = token;
            return this;
        }

        public Builder subjectToken(int token) {
            this.subjectToken = token;
            return this;
        }

        public Builder sectionToken(int token) {
            this.sectionToken = token;
            return this;
        }

        public Builder groupRegex(String regex) {
            this.groupRegex = regex;
            if (hasText(regex)) this.metadataMode = MetadataMode.REGEX;
            return this;
        }

        public Builder groupCapture(int capture) {
            this.groupCapture = capture;
            return this;
        }

        public Builder subjectCapture(int capture) {
            this.subjectCapture = capture;
            return this;
        }

        public Builder sectionCapture(int capture) {
            this.sectionCapture = capture;
            return this;
        }

        public Builder channel(int channelNumber, String name, ChannelColour colour,
                int min, int max) {
            channels.add(new Channel(channelNumber - 1, name, colour,
                    new DisplayRange(min, max)));
            return this;
        }

        public Builder channel(Channel channel) {
            if (channel != null) channels.add(channel);
            return this;
        }

        public Builder channels(List<Channel> channels) {
            this.channels = channels == null
                    ? new ArrayList<Channel>()
                    : new ArrayList<Channel>(channels);
            return this;
        }

        public Builder zMode(String zMode) {
            this.zMode = zMode;
            return this;
        }

        public Builder statistic(String statistic) {
            this.statistic = statistic;
            return this;
        }

        public Builder statisticCsv(File statisticCsv) {
            this.statisticCsv = statisticCsv;
            return this;
        }

        public Builder statisticColumn(String statisticColumn) {
            this.statisticColumn = statisticColumn;
            return this;
        }

        public Builder pick(String group, String subject) {
            if (hasText(group) && hasText(subject)) {
                picks.put(group.trim(), subject.trim());
            }
            return this;
        }

        public Builder picks(Map<String, String> picks) {
            this.picks = new LinkedHashMap<String, String>();
            if (picks != null) {
                for (Map.Entry<String, String> entry : picks.entrySet()) {
                    pick(entry.getKey(), entry.getValue());
                }
            }
            return this;
        }

        public Builder scaleBarUm(double scaleBarUm) {
            this.scaleBarUm = scaleBarUm;
            return this;
        }

        public Builder scaleBarCorner(PanelConfig.Position scaleBarCorner) {
            this.scaleBarCorner = scaleBarCorner;
            return this;
        }

        public Builder dpi(int dpi) {
            this.dpi = dpi;
            return this;
        }

        public Builder exportScale(int exportScale) {
            this.exportScale = exportScale;
            return this;
        }

        public Builder writePng(boolean writePng) {
            this.writePng = writePng;
            return this;
        }

        public Builder writeTiff(boolean writeTiff) {
            this.writeTiff = writeTiff;
            return this;
        }

        public Builder writeSvg(boolean writeSvg) {
            this.writeSvg = writeSvg;
            return this;
        }

        public Builder writeIndividualPanels(boolean writeIndividualPanels) {
            this.writeIndividualPanels = writeIndividualPanels;
            return this;
        }

        public Builder writeRecords(boolean writeRecords) {
            this.writeRecords = writeRecords;
            return this;
        }

        public Builder outputFolder(File outputFolder) {
            this.outputFolder = outputFolder;
            return this;
        }

        public Builder figureName(String figureName) {
            this.figureName = figureName;
            return this;
        }

        public Builder hideDisplay(boolean hideDisplay) {
            this.hideDisplay = hideDisplay;
            return this;
        }

        public FPBParameters build() {
            return new FPBParameters(this);
        }
    }
}
