package fpb.ui;

import fpb.figure.CalibrationOverride;
import fpb.figure.ImageOrientation;
import fpb.figure.PanelConfigCodec;
import fpb.io.ImageSource;
import fpb.meta.MetadataRow;
import fpb.meta.MetadataTable;
import fpb.render.ChannelColour;
import fpb.render.DisplayRange;
import fpb.render.FPBRenderer;
import fpb.ui.chooser.RowImage;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Versioned whole-project storage. Raw images stay external and must match on reopen. */
public final class FigureProjectIO {
    private FigureProjectIO() {}

    /** Capture on the UI thread after committing the active editor; no file reads. */
    public static Properties capture(FPBWizard.Context c) {
        if (c.folder == null) throw new IllegalStateException("Choose an image folder first.");
        Properties p = new Properties();
        put(p, "schema", 1);
        put(p, "root", c.folder.getAbsolutePath());
        put(p, "recursive", c.recursive);
        put(p, "quickGrid", c.quickGridRequested);
        put(p, "z", c.zHandling);
        put(p, "statistic", c.statistic);
        put(p, "statisticColumn", c.statisticColumn);
        put(p, "statisticCsv", c.statisticCsv == null ? "" : relative(c.folder, c.statisticCsv));
        put(p, "channelsDetected", c.detectedChannelCount);
        put(p, "panel", PanelConfigCodec.encode(c.panelConfig));
        put(p, "savedZ", c.savedZHandling);
        MetadataTable table = c.allImagesTable == null ? c.metadataTable : c.allImagesTable;
        int n = 0;
        if (table != null) for (MetadataRow row : table.rows()) {
            String k = "image." + n++ + ".";
            put(p, k + "path", relative(c.folder, row.file));
            put(p, k + "series", row.source.seriesIndex());
            put(p, k + "seriesCount", row.source.seriesCount());
            put(p, k + "seriesName", row.source.seriesName());
            put(p, k + "group", row.group); put(p, k + "subject", row.subject);
            put(p, k + "section", row.section); put(p, k + "reason", row.unassignedReason);
            put(p, k + "included", row.included);
        }
        put(p, "images", n);
        n = 0;
        for (FPBWizard.ChannelSetting s : c.channelSettings) {
            String k = "channel." + n++ + ".";
            put(p, k + "index", s.sourceIndex); put(p, k + "include", s.include);
            put(p, k + "name", s.name); put(p, k + "colour", s.colour.name());
        }
        put(p, "channels", n);
        n = 0;
        List<FPBRenderer.ChannelRequest> requests = c.layoutChannelRequests.isEmpty()
                ? c.savedChannelRequests : c.layoutChannelRequests;
        for (FPBRenderer.ChannelRequest r : requests) {
            String k = "range." + n++ + ".";
            put(p, k + "index", r.channelIndex()); put(p, k + "name", r.name());
            put(p, k + "colour", r.colour().name());
            put(p, k + "min", r.range().min()); put(p, k + "max", r.range().max());
        }
        put(p, "ranges", n);
        n = 0;
        Map<String, RowImage.SubjectRow> picks = c.selectedRowsByGroup.isEmpty()
                ? c.savedPicks : c.selectedRowsByGroup;
        for (Map.Entry<String, RowImage.SubjectRow> e : picks.entrySet()) {
            String k = "pick." + n++ + "."; RowImage.SubjectRow r = e.getValue();
            put(p, k + "group", e.getKey()); put(p, k + "subject", r.subject());
            put(p, k + "section", r.section()); put(p, k + "image", r.imageId());
            put(p, k + "index", r.imageIndex());
        }
        put(p, "picks", n);
        n = 0;
        for (Map.Entry<String, CalibrationOverride> e : c.calibrationOverrides.entrySet()) {
            String k = "calibration." + n++ + ".";
            put(p, k + "image", e.getKey());
            put(p, k + "width", e.getValue().pixelWidthUm());
            put(p, k + "height", e.getValue().pixelHeightUm());
        }
        put(p, "calibrations", n);
        n = 0;
        for (Map.Entry<String, ImageOrientation> e : c.imageOrientations.entrySet()) {
            String k = "orientation." + n++ + ".";
            put(p, k + "image", e.getKey()); put(p, k + "value", e.getValue().token());
        }
        put(p, "orientations", n);
        for (String key : c.exportSettings.stringPropertyNames())
            put(p, "export." + key, c.exportSettings.getProperty(key));
        String output = p.getProperty("export.folder", "");
        if (!output.isEmpty()) put(p, "export.folder", relative(c.folder, new File(output)));
        return p;
    }

    /** Hash and publish off the UI thread; a failed write preserves the previous project. */
    public static void save(Properties snapshot, File destination) throws IOException {
        Properties p = new Properties(); p.putAll(snapshot);
        File root = new File(required(p, "root"));
        Map<String, String> hashes = new HashMap<String, String>();
        for (int i = 0; i < count(p, "images"); i++) {
            String key = "image." + i + ".";
            put(p, key + "sha256", fingerprint(resolve(root, required(p, key + "path")), hashes));
        }
        if (!p.getProperty("statisticCsv", "").isEmpty())
            put(p, "statisticSha256", fingerprint(resolve(root, p.getProperty("statisticCsv")), hashes));
        File absolute = destination.getAbsoluteFile();
        put(p, "root", relative(absolute.getParentFile(), root));
        put(p, "revision", UUID.randomUUID().toString());
        Files.createDirectories(absolute.toPath().getParent());
        Path tmp = Files.createTempFile(absolute.toPath().getParent(), ".figure-project-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.storeToXML(out, "Figure Panel Builder project; images are external", "UTF-8");
            }
            Files.move(tmp, absolute.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }

    public static FPBWizard.Context load(File project, File relinkRoot) throws IOException {
        if (Files.size(project.toPath()) > 16 * 1024 * 1024)
            throw new IOException("Project exceeds the 16 MiB metadata limit.");
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(project.toPath())) { p.loadFromXML(in); }
        if (!"1".equals(required(p, "schema"))) throw new IOException("Unsupported project schema.");
        File root = relinkRoot == null
                ? resolve(project.getAbsoluteFile().getParentFile(), required(p, "root"))
                : relinkRoot.getAbsoluteFile();
        Map<String, String> hashes = new HashMap<String, String>();
        List<MetadataRow> rows = new ArrayList<MetadataRow>();
        try {
            for (int i = 0; i < count(p, "images"); i++) {
                String k = "image." + i + ".";
                File file = resolve(root, required(p, k + "path"));
                verify(file, required(p, k + "sha256"), hashes);
                int series = integer(p, k + "series");
                ImageSource source = series < 0 ? ImageSource.file(file)
                        : ImageSource.series(file, series, integer(p, k + "seriesCount"),
                                required(p, k + "seriesName"));
                MetadataRow row = new MetadataRow(source, required(p, k + "group"),
                        required(p, k + "subject"), required(p, k + "section"));
                row.included = bool(p, k + "included"); row.unassignedReason = required(p, k + "reason");
                rows.add(row);
            }
            File csv = p.getProperty("statisticCsv", "").isEmpty() ? null
                    : resolve(root, p.getProperty("statisticCsv"));
            if (csv != null) verify(csv, required(p, "statisticSha256"), hashes);
            FPBWizard.Context c = new FPBWizard.Context();
            c.folder = root; c.recursive = bool(p, "recursive");
            c.quickGridRequested = bool(p, "quickGrid"); c.zHandling = required(p, "z");
            c.statistic = required(p, "statistic"); c.statisticColumn = required(p, "statisticColumn");
            c.statisticCsv = csv; c.detectedChannelCount = integer(p, "channelsDetected");
            c.allImagesTable = new MetadataTable(root, rows); c.metadataTable = c.allImagesTable.includedTable();
            c.panelConfig = PanelConfigCodec.decode(required(p, "panel"));
            if (c.panelConfig != null) c.groupLayoutRows = new ArrayList<List<String>>(c.panelConfig.groupLayoutRows());
            c.savedZHandling = p.getProperty("savedZ", "");
            for (int i = 0; i < count(p, "channels"); i++) {
                String k = "channel." + i + ".";
                c.channelSettings.add(new FPBWizard.ChannelSetting(integer(p, k + "index"),
                        bool(p, k + "include"), required(p, k + "name"),
                        ChannelColour.fromName(required(p, k + "colour"))));
            }
            for (int i = 0; i < count(p, "ranges"); i++) {
                String k = "range." + i + ".";
                DisplayRange range = new DisplayRange(integer(p, k + "min"), integer(p, k + "max"));
                if (!range.isValid()) throw new IOException("Invalid saved channel range.");
                c.savedChannelRequests.add(new FPBRenderer.ChannelRequest(integer(p, k + "index"),
                        required(p, k + "name"), ChannelColour.fromName(required(p, k + "colour")), range));
            }
            for (int i = 0; i < count(p, "picks"); i++) {
                String k = "pick." + i + ".";
                c.savedPicks.put(required(p, k + "group"), new RowImage.SubjectRow(
                        required(p, k + "group"), required(p, k + "subject"), required(p, k + "section"),
                        integer(p, k + "index"), false, null, required(p, k + "image"), ImageOrientation.IDENTITY));
            }
            for (int i = 0; i < count(p, "calibrations"); i++) {
                String k = "calibration." + i + ".";
                c.calibrationOverrides.put(required(p, k + "image"), new CalibrationOverride(
                        Double.parseDouble(required(p, k + "width")), Double.parseDouble(required(p, k + "height"))));
            }
            for (int i = 0; i < count(p, "orientations"); i++) {
                String k = "orientation." + i + ".";
                c.imageOrientations.put(required(p, k + "image"), ImageOrientation.fromToken(required(p, k + "value")));
            }
            for (String key : p.stringPropertyNames()) if (key.startsWith("export."))
                c.exportSettings.setProperty(key.substring(7), p.getProperty(key));
            String output = c.exportSettings.getProperty("folder", "");
            if (!output.isEmpty()) c.exportSettings.setProperty("folder", resolve(root, output).getAbsolutePath());
            c.projectFile = project.getAbsoluteFile(); c.projectRevision = required(p, "revision");
            return c;
        } catch (IllegalArgumentException e) { throw new IOException("Invalid project: " + e.getMessage(), e); }
    }

    private static void verify(File file, String expected, Map<String, String> hashes) throws IOException {
        if (!expected.equals(fingerprint(file, hashes)))
            throw new IOException("Source changed: " + file + ". Restore the recorded source before reopening.");
    }
    private static String fingerprint(File file, Map<String, String> hashes) throws IOException {
        String key = file.getCanonicalPath();
        if (hashes.containsKey(key)) return hashes.get(key);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            hashes.put(key, hex.toString()); return hex.toString();
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    private static String relative(File root, File file) {
        Path base = root.getAbsoluteFile().toPath().normalize();
        Path path = file.getAbsoluteFile().toPath().normalize();
        try { return base.relativize(path).toString(); } catch (IllegalArgumentException e) { return path.toString(); }
    }
    private static File resolve(File root, String path) { return root.toPath().resolve(path).normalize().toFile(); }
    private static void put(Properties p, String key, Object value) { p.setProperty(key, value == null ? "" : value.toString()); }
    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null) throw new IOException("Project is missing " + key); return value;
    }
    private static int integer(Properties p, String key) throws IOException {
        try { return Integer.parseInt(required(p, key)); }
        catch (NumberFormatException e) { throw new IOException("Invalid project count: " + key, e); }
    }
    private static int count(Properties p, String key) throws IOException {
        int n = integer(p, key);
        if (n < 0 || n > 100000) throw new IOException("Invalid project count: " + key); return n;
    }
    private static boolean bool(Properties p, String key) throws IOException {
        String value = required(p, key);
        if (!"true".equals(value) && !"false".equals(value)) throw new IOException("Invalid boolean: " + key);
        return Boolean.parseBoolean(value);
    }
}

