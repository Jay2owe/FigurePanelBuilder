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
import fpb.figure.PanelConfigCodec;
import fpb.io.ImageLoader;
import fpb.io.ImageSource;
import fpb.io.ProgressCallback;
import fpb.render.ChannelColour;
import fpb.ui.FPBWizard;
import fpb.ui.Step5Export;
import fpb.ui.chooser.Step3Chooser;
import fpb.ui.layout.Step4Layout;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.process.ShortProcessor;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/** Explicit opt-in workload. Not collected as a unit test; no user's files are opened. */
public final class PerformanceWorkload {
    private static final List<String> measurements = new ArrayList<String>();
    private static JFrame frame;

    private PerformanceWorkload() {}

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        int count = Integer.parseInt(args[1]);
        int edge = Integer.parseInt(args[2]);
        File input = new File(root, "input-" + count + "-" + edge);
        prepare(input, count, edge);
        try {
            measure("discover", () -> Integer.toString(
                    ImageLoader.discoverImageSources(input, false).size()));
            final FPBParameters parameters = FPBParameters.builder(input)
                    .channel(1, "Nuclei", ChannelColour.BLUE, 100, 60000)
                    .channel(2, "Signal", ChannelColour.GREEN, 100, 60000)
                    .channel(3, "Marker", ChannelColour.MAGENTA, 100, 60000)
                    .pick("Control", "S1").pick("Drug", "S1")
                    .outputFolder(new File(root, "output")).figureName("Benchmark")
                    .panelConfig(PanelConfig.builder().cellSizePx(160)
                            .groupLayoutRows(Arrays.asList(Arrays.asList("Control", "Drug")))
                            .channelOrder(Arrays.asList("Nuclei", "Signal", "Marker", "Merge"))
                            .scaleBarEnabled(false).build()).build();
            final FPBResult[] result = new FPBResult[1];
            measure("guided-analysis", () -> {
                result[0] = FPB.run(parameters);
                return result[0].selectedSubjects().toString() + ":"
                        + result[0].chooserData().table().fileCount();
            });
            measure("quick-grid", () -> {
                FPBResult quick = FPB.run(FPBParameters.builder(input).quickGrid(true).build());
                return Integer.toString(quick.metadataTable().fileCount());
            });
            measure("macro-replay", () -> {
                FPBParameters replay = FPBMacroOptionsParser.parse(
                        FPBMacroOptions.fromParameters(parameters).toMacroOptions()).toParameters();
                return FPB.run(replay).selectedSubjects().toString();
            });
            FPBWizard.Context context = result[0].toContext();
            List<ImageSource> sources = new ArrayList<ImageSource>();
            for (fpb.meta.MetadataRow row : context.metadataTable.rows()) sources.add(row.source);
            context.imagePreloader.loadOrAwait(sources, ImageLoader.ZMode.MAX);
            measure("preload-reuse", () -> Integer.toString(context.imagePreloader
                    .loadOrAwait(sources, ImageLoader.ZMode.MAX).planeCache().imageCount()));
            final Step3Chooser chooser = new Step3Chooser(context);
            final Step4Layout layout = new Step4Layout(context);
            try {
                edt(() -> {
                    frame = new JFrame("Figure Panel Builder - owned performance check");
                    frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                    frame.setSize(1100, 760);
                    frame.setLocation(30, 30);
                    frame.setVisible(true);
                });
                measure("chooser-ready", () -> {
                    edt(() -> { show(chooser.component()); chooser.onShow(); });
                    awaitChooser(chooser);
                    paint();
                    return chooser.picksForTest().keySet().toString();
                });
                measure("select-image", () -> {
                    edt(() -> chooser.selectSubjectForTest("Control", "S2"));
                    awaitPicks(chooser);
                    paint();
                    return chooser.picksForTest().get("Control").imageId();
                });
                measure("layout-first", () -> {
                    edt(() -> { chooser.commitEdits(); show(layout.component()); layout.onShow(); });
                    paint();
                    return context;
                }, PerformanceWorkload::layoutDigest);
                measure("layout-revisit", () -> {
                    edt(() -> { show(chooser.component()); chooser.onShow(); });
                    awaitChooser(chooser);
                    edt(() -> { chooser.commitEdits(); show(layout.component()); layout.onShow(); });
                    paint();
                    return context;
                }, PerformanceWorkload::layoutDigest);
                // Exercise the full UI export controller, not only a pixel kernel.
                measure("export", () -> {
                    Step5Export.ExportResult output = Step5Export.export(context,
                            new Step5Export.Settings(new File(root, "output"),
                                    "Benchmark", 300, 2, true, true, true, true, true),
                            null, Step5Export.NONE);
                    return output;
                }, output -> outputDigest(output.figureDirectory()));
                measure("layout-codec", () -> PanelConfigCodec.encode(
                        PanelConfigCodec.decode(PanelConfigCodec.encode(context.panelConfig))));
                capture(new File(root, "layout-desktop.png"), 1100, 760);
                capture(new File(root, "layout-small.png"), 800, 600);
            } finally {
                edt(() -> { chooser.close(); layout.close(); });
                context.imagePreloader.close();
            }
            System.out.println("FPB_PERFORMANCE={\"operations\":["
                    + String.join(",", measurements) + "]}");
        } finally {
            edt(() -> { if (frame != null) frame.dispose(); });
        }
        System.exit(0);
    }

    private static void prepare(File folder, int count, int edge) throws Exception {
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("Cannot create fixture");
        for (int i = 0; i < count; i++) {
            File file = new File(folder, (i < count / 2 ? "Control" : "Drug")
                    + "_S" + (i % (count / 2) + 1) + ".tif");
            if (file.isFile()) continue;
            ImageStack stack = new ImageStack(edge, edge);
            for (int z = 0; z < 2; z++) {
                for (int c = 0; c < 3; c++) {
                    short[] pixels = new short[edge * edge];
                    for (int p = 0; p < pixels.length; p++) {
                        pixels[p] = (short) ((p * 13 + (p / edge) * 17 + i * 101 + c * 500 + z * 3) % 64000);
                    }
                    stack.addSlice(new ShortProcessor(edge, edge, pixels, null));
                }
            }
            ImagePlus image = new ImagePlus(file.getName(), stack);
            image.setDimensions(3, 2, 1);
            image.setOpenAsHyperStack(true);
            if (!new FileSaver(image).saveAsTiffStack(file.getAbsolutePath())) {
                throw new IllegalStateException("Cannot write fixture");
            }
            image.close();
        }
    }

    private static void measure(String id, Callable<String> operation) throws Exception {
        measure(id, operation, value -> value);
    }

    private interface Fingerprint<T> { String value(T result) throws Exception; }

    private static <T> void measure(String id, Callable<T> operation, Fingerprint<T> oracle) throws Exception {
        long start = System.nanoTime();
        T result = operation.call();
        double seconds = (System.nanoTime() - start) / 1e9;
        String output = oracle.value(result);
        String hash = hex(MessageDigest.getInstance("SHA-256").digest(output.getBytes("UTF-8")));
        measurements.add("{\"id\":\"" + id + "\",\"seconds\":" + seconds
                + ",\"output_sha256\":\"" + hash + "\"}");
    }

    private static void edt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    private static void show(JComponent component) {
        frame.setContentPane(component);
        frame.validate();
    }

    private static void paint() throws Exception {
        edt(() -> {
            JComponent content = (JComponent) frame.getContentPane();
            content.paintImmediately(new Rectangle(0, 0, content.getWidth(), content.getHeight()));
            Toolkit.getDefaultToolkit().sync();
        });
    }

    private static void capture(File file, int width, int height) throws Exception {
        BufferedImage[] image = new BufferedImage[1];
        edt(() -> {
            frame.setSize(width, height);
            frame.validate();
        });
        edt(() -> {
            image[0] = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = image[0].createGraphics();
            try { frame.paintAll(graphics); } finally { graphics.dispose(); }
        });
        ImageIO.write(image[0], "png", file);
    }

    private static void awaitChooser(Step3Chooser chooser) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        boolean[] ready = { false };
        while (!ready[0]) {
            edt(() -> ready[0] = chooser.canAdvance()
                    && chooser.renderedRowCountForTest("Control") > 0);
            if (System.nanoTime() > deadline) throw new IllegalStateException("Chooser completion timed out");
            if (!ready[0]) Thread.sleep(5);
        }
        awaitPicks(chooser);
    }

    private static void awaitPicks(Step3Chooser chooser) throws Exception {
        java.lang.reflect.Field cells = fpb.ui.chooser.PicksStrip.class.getDeclaredField("cellsByGroup");
        cells.setAccessible(true);
        long deadline = System.nanoTime() + 30_000_000_000L;
        boolean[] complete = { false };
        while (!complete[0]) {
            edt(() -> {
                try {
                    java.util.Map<?, ?> displayed = (java.util.Map<?, ?>) cells.get(chooser.picksStripForTest());
                    complete[0] = true;
                    for (java.util.Map.Entry<String, fpb.ui.chooser.RowImage.SubjectRow> entry
                            : chooser.picksForTest().entrySet()) {
                        Object cell = displayed.get(entry.getKey());
                        java.lang.reflect.Field row = cell.getClass().getDeclaredField("row");
                        row.setAccessible(true);
                        if (row.get(cell) != entry.getValue()) complete[0] = false;
                    }
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            });
            if (System.nanoTime() > deadline) throw new IllegalStateException("Pick rendering timed out");
            if (!complete[0]) Thread.sleep(5);
        }
    }

    private static String layoutDigest(FPBWizard.Context context) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (fpb.figure.PanelRecord record : context.layoutPanelRecords) {
            digest.update(Files.readAllBytes(record.imageFile().toPath()));
        }
        digest.update(PanelConfigCodec.encode(context.panelConfig).getBytes("UTF-8"));
        return hex(digest.digest());
    }

    private static String outputDigest(File root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<java.nio.file.Path> paths = new ArrayList<java.nio.file.Path>();
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.walk(root.toPath())) {
            files.filter(Files::isRegularFile).forEach(paths::add);
        }
        paths.sort((a, b) -> root.toPath().relativize(a).toString().compareTo(
                root.toPath().relativize(b).toString()));
        for (java.nio.file.Path path : paths) {
            String relative = root.toPath().relativize(path).toString();
            if (relative.endsWith(".png")) {
                digest.update(relative.getBytes("UTF-8"));
                BufferedImage image = ImageIO.read(path.toFile());
                digest.update((image.getWidth() + "x" + image.getHeight()).getBytes("UTF-8"));
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int value = image.getRGB(x, y);
                        digest.update((byte) (value >>> 16));
                        digest.update((byte) (value >>> 8));
                        digest.update((byte) value);
                    }
                }
            } else if (relative.endsWith(".tif")) {
                digest.update(relative.getBytes("UTF-8"));
                ImagePlus image = new ij.io.Opener().openImage(path.toString());
                if (image == null) throw new IllegalStateException("Cannot reopen exported TIFF");
                try {
                    digest.update((image.getWidth() + "x" + image.getHeight() + ":"
                            + image.getNChannels() + ":" + image.getNSlices() + ":" + image.getNFrames()
                            + ":" + image.getCalibration().pixelWidth + ":" + image.getCalibration().pixelHeight
                            + ":" + image.getCalibration().getUnit()).getBytes("UTF-8"));
                    for (int i = 1; i <= image.getStackSize(); i++) {
                        digest.update(String.valueOf(image.getStack().getSliceLabel(i)).getBytes("UTF-8"));
                        ij.process.ImageProcessor plane = image.getStack().getProcessor(i);
                        for (int p = 0; p < image.getWidth() * image.getHeight(); p++) {
                            int value = plane.get(p);
                            digest.update((byte) (value >>> 24));
                            digest.update((byte) (value >>> 16));
                            digest.update((byte) (value >>> 8));
                            digest.update((byte) value);
                        }
                    }
                } finally { image.close(); }
            } else {
                digest.update(relative.getBytes("UTF-8"));
                String text = new String(Files.readAllBytes(path), "UTF-8");
                if (relative.endsWith("methods.txt")) {
                    text = text.replaceAll("(?m)(^Generated: )[^\\r\\n]*(          Plugin version: )",
                            "$1<timestamp>$2");
                }
                digest.update(text.getBytes("UTF-8"));
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format("%02x", value & 255));
        return out.toString();
    }
}
