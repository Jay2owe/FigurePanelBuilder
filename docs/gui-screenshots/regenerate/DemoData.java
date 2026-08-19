import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.process.ShortProcessor;

import java.io.File;
import java.util.Random;

/**
 * Writes a small synthetic immunofluorescence dataset that Figure Panel Builder
 * can open: 3 channels x 3 z-slices, two groups of three animals, two sections
 * each. Filenames follow GROUP_SUBJECT_SECTION.tif so the wizard's filename
 * token strategy assigns metadata without help.
 */
public final class DemoData {

    static final int W = 640;
    static final int H = 640;
    static final int CHANNELS = 3;
    static final int SLICES = 3;
    static final double PIXEL_UM = 0.325;

    static final String[][] SUBJECTS = {
            { "WT", "M01" }, { "WT", "M02" }, { "WT", "M03" },
            { "KO", "M04" }, { "KO", "M05" }, { "KO", "M06" }
    };
    static final String[] SECTIONS = { "S1", "S2" };

    private DemoData() {}

    public static File build(File root) {
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IllegalStateException("Could not create " + root);
        }
        int seed = 0;
        for (String[] subject : SUBJECTS) {
            for (String section : SECTIONS) {
                String name = subject[0] + "_" + subject[1] + "_" + section + ".tif";
                File out = new File(root, name);
                if (!out.isFile()) {
                    write(out, subject[0], seed);
                }
                seed++;
            }
        }
        return root;
    }

    private static void write(File out, String group, int seed) {
        Random random = new Random(4242L + seed * 97L);
        // Knock-outs carry more Iba1 signal so the group comparison plot has a
        // real difference to show.
        double iba1Gain = "KO".equals(group) ? 1.75 : 1.0;
        double gfapGain = "KO".equals(group) ? 1.35 : 1.0;

        ImageStack stack = new ImageStack(W, H);
        for (int z = 0; z < SLICES; z++) {
            double focus = 1.0 - Math.abs(z - (SLICES - 1) / 2.0) * 0.28;
            stack.addSlice("C1-z" + z, nuclei(random, focus));
            stack.addSlice("C2-z" + z, cells(random, focus * iba1Gain));
            stack.addSlice("C3-z" + z, fibres(random, focus * gfapGain));
        }
        ImagePlus imp = new ImagePlus(out.getName(), stack);
        imp.setDimensions(CHANNELS, SLICES, 1);
        imp.setOpenAsHyperStack(true);
        Calibration cal = imp.getCalibration();
        cal.pixelWidth = PIXEL_UM;
        cal.pixelHeight = PIXEL_UM;
        cal.pixelDepth = 1.0;
        cal.setUnit("micron");
        if (!new FileSaver(imp).saveAsTiff(out.getAbsolutePath())) {
            throw new IllegalStateException("Could not write " + out);
        }
    }

    /** Round, evenly scattered nuclei. */
    private static ShortProcessor nuclei(Random random, double gain) {
        float[] buffer = background(random, 180, 40);
        int count = 55 + random.nextInt(15);
        for (int i = 0; i < count; i++) {
            blob(buffer, random.nextInt(W), random.nextInt(H),
                    7.5 + random.nextDouble() * 3.0,
                    (7000 + random.nextDouble() * 6000) * gain);
        }
        return toProcessor(buffer);
    }

    /** Small somata with radiating processes, microglia-like. */
    private static ShortProcessor cells(Random random, double gain) {
        float[] buffer = background(random, 150, 35);
        int count = 18 + random.nextInt(10);
        for (int i = 0; i < count; i++) {
            int cx = random.nextInt(W);
            int cy = random.nextInt(H);
            double peak = (6000 + random.nextDouble() * 7000) * gain;
            blob(buffer, cx, cy, 5.0 + random.nextDouble() * 2.0, peak);
            int arms = 4 + random.nextInt(4);
            for (int a = 0; a < arms; a++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double length = 14.0 + random.nextDouble() * 26.0;
                for (double t = 2.0; t < length; t += 1.2) {
                    double wobble = Math.sin(t * 0.45 + a) * 2.4;
                    int px = (int) Math.round(cx + Math.cos(angle) * t
                            + Math.cos(angle + Math.PI / 2) * wobble);
                    int py = (int) Math.round(cy + Math.sin(angle) * t
                            + Math.sin(angle + Math.PI / 2) * wobble);
                    blob(buffer, px, py, 1.9, peak * 0.55 * (1.0 - t / (length * 1.4)));
                }
            }
        }
        return toProcessor(buffer);
    }

    /** Long crossing filaments, astrocyte-like. */
    private static ShortProcessor fibres(Random random, double gain) {
        float[] buffer = background(random, 200, 45);
        int count = 26 + random.nextInt(12);
        for (int i = 0; i < count; i++) {
            double x = random.nextInt(W);
            double y = random.nextInt(H);
            double angle = random.nextDouble() * Math.PI * 2.0;
            double peak = (4500 + random.nextDouble() * 5000) * gain;
            double length = 60.0 + random.nextDouble() * 150.0;
            for (double t = 0; t < length; t += 1.1) {
                angle += (random.nextDouble() - 0.5) * 0.12;
                x += Math.cos(angle) * 1.1;
                y += Math.sin(angle) * 1.1;
                blob(buffer, (int) Math.round(x), (int) Math.round(y), 1.7,
                        peak * (0.45 + 0.55 * Math.sin(t / length * Math.PI)));
            }
        }
        return toProcessor(buffer);
    }

    private static float[] background(Random random, double level, double noise) {
        float[] buffer = new float[W * H];
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = (float) (level + random.nextGaussian() * noise);
        }
        return buffer;
    }

    private static void blob(float[] buffer, int cx, int cy, double sigma, double peak) {
        if (peak <= 0) return;
        int radius = (int) Math.ceil(sigma * 2.6);
        double twoSigmaSq = 2.0 * sigma * sigma;
        for (int y = cy - radius; y <= cy + radius; y++) {
            if (y < 0 || y >= H) continue;
            for (int x = cx - radius; x <= cx + radius; x++) {
                if (x < 0 || x >= W) continue;
                double dx = x - cx;
                double dy = y - cy;
                double d2 = dx * dx + dy * dy;
                buffer[y * W + x] += (float) (peak * Math.exp(-d2 / twoSigmaSq));
            }
        }
    }

    private static ShortProcessor toProcessor(float[] buffer) {
        short[] pixels = new short[buffer.length];
        for (int i = 0; i < buffer.length; i++) {
            int value = Math.round(buffer[i]);
            if (value < 0) value = 0;
            if (value > 65535) value = 65535;
            pixels[i] = (short) value;
        }
        return new ShortProcessor(W, H, pixels, null);
    }

    public static void main(String[] args) {
        File root = new File(args.length > 0 ? args[0] : "Demo_Experiment");
        build(root);
        System.out.println("Wrote demo data to " + root.getAbsolutePath());
    }
}
