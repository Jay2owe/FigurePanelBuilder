import fpb.figure.PanelConfig;
import fpb.figure.PanelRecord;
import fpb.meta.TokenStrategy;
import fpb.render.DisplayRange;
import fpb.ui.FPBWizard;
import fpb.ui.Step1Images;
import fpb.ui.Step2Channels;
import fpb.ui.TokenPicker;
import fpb.ui.chooser.ChannelRail;
import fpb.ui.chooser.Step3Chooser;
import fpb.ui.layout.AnnotationEditor;
import fpb.ui.layout.ExternalLabelEditor;
import fpb.ui.layout.LayoutEditor;

import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Drives the real Figure Panel Builder wizard through every screen and captures
 * each one with java.awt.Robot. Nothing here re-implements the UI: every window
 * photographed is the class the plugin actually shows.
 */
public final class FpbShots {

    private static final int WIZARD_W = 1500;
    private static final int WIZARD_H = 812;

    /**
     * Windows 11 reports window bounds that include an invisible resize border
     * outside the painted frame. Trimming it keeps the desktop out of the shot.
     */
    private static final int SHADOW_LOGICAL_PX = 7;

    private static File outDir;
    private static Robot robot;
    private static int shotIndex;
    private static final List<String> written = new ArrayList<String>();

    private static FPBWizard wizard;
    private static JDialog wizardDialog;
    private static Object[] steps;

    public static void main(String[] args) throws Exception {
        File dataRoot = new File(args.length > 0 ? args[0] : "Demo_Experiment")
                .getAbsoluteFile();
        outDir = new File(args.length > 1 ? args[1] : "shots").getAbsoluteFile();
        File exportRoot = new File(args.length > 2 ? args[2] : "Demo_Export")
                .getAbsoluteFile();
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IllegalStateException("Could not create " + outDir);
        }
        if (!exportRoot.isDirectory() && !exportRoot.mkdirs()) {
            throw new IllegalStateException("Could not create " + exportRoot);
        }
        DemoData.build(dataRoot);
        robot = new Robot();

        onEdt(new Runnable() {
            public void run() {
                try {
                    UIManager.setLookAndFeel(
                            UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                    // The cross-platform look and feel still renders every screen.
                }
            }
        });

        buildWizard();
        try {
            step1(dataRoot);
            step2();
            step3();
            step4();
            step5(exportRoot);
        } finally {
            onEdt(new Runnable() {
                public void run() {
                    wizardDialog.dispose();
                }
            });
        }

        System.out.println();
        System.out.println("Wrote " + written.size() + " screenshots to " + outDir);
        for (String name : written) System.out.println("  " + name);
        System.exit(0);
    }

    // ------------------------------------------------------------------
    // Wizard setup
    // ------------------------------------------------------------------

    private static void buildWizard() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                try {
                    wizard = new FPBWizard();
                    wizardDialog = (JDialog) get(wizard, "dialog");
                    steps = (Object[]) get(wizard, "steps");
                    wizardDialog.setModal(false);
                    wizardDialog.setAlwaysOnTop(true);
                    wizardDialog.setSize(fitToScreen(WIZARD_W, WIZARD_H));
                    wizardDialog.setLocation(0, 0);
                    wizardDialog.setVisible(true);
                    wizardDialog.toFront();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
        });
        settle(600);
    }

    private static Dimension fitToScreen(int width, int height) {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        return new Dimension(Math.min(width, screen.width - 8),
                Math.min(height, screen.height - 8));
    }

    private static void showStep(final int index, final int maxCompleted)
            throws Exception {
        onEdt(new Runnable() {
            public void run() {
                try {
                    set(wizard, "maxCompletedIndex", Integer.valueOf(maxCompleted));
                    Method show = FPBWizard.class.getDeclaredMethod("showStep", int.class);
                    show.setAccessible(true);
                    show.invoke(wizard, Integer.valueOf(index));
                    // Put the focus ring on the step the shot is about, so the
                    // header reads "you are here" instead of pointing at step 1.
                    Object[] jump = (Object[]) get(wizard, "jumpButtons");
                    ((javax.swing.JButton) jump[index]).requestFocusInWindow();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
        });
        settle(400);
    }

    // ------------------------------------------------------------------
    // Step 1 - Images
    // ------------------------------------------------------------------

    private static void step1(final File dataRoot) throws Exception {
        final Step1Images step = (Step1Images) steps[0];
        showStep(0, 0);
        onEdt(new Runnable() {
            public void run() {
                step.chooseFolder(dataRoot);
            }
        });
        settle(700);

        // The auto-guess leaves the third filename token unused; assigning it to
        // Section shows the table fully populated, as a user would leave it.
        onEdt(new Runnable() {
            public void run() {
                TokenPicker picker = step.tokenPicker();
                picker.setTokenField(2, TokenStrategy.Field.SECTION);
            }
        });
        settle(500);
        refreshButtons();
        shootWizard("01_step1_images_and_metadata");

        shootModal("02_step1_advanced_metadata_regex", new Runnable() {
            public void run() {
                invoke(step, "showAdvancedRegex");
            }
        });
    }

    // ------------------------------------------------------------------
    // Step 2 - Channels
    // ------------------------------------------------------------------

    private static void step2() throws Exception {
        final Step2Channels step = (Step2Channels) steps[1];
        showStep(1, 1);
        settle(900);
        onEdt(new Runnable() {
            public void run() {
                List<FPBWizard.ChannelSetting> settings = step.channelSettings();
                String[] names = { "DAPI", "Iba1", "GFAP" };
                for (int i = 0; i < settings.size() && i < names.length; i++) {
                    settings.get(i).name = names[i];
                }
                invoke(step, "rebuildRows");
            }
        });
        settle(400);
        refreshButtons();
        shootWizard("03_step2_channels");
    }

    // ------------------------------------------------------------------
    // Step 3 - Choose images
    // ------------------------------------------------------------------

    private static void step3() throws Exception {
        final Step3Chooser step = (Step3Chooser) steps[2];
        showStep(2, 2);

        // Catch the background loader mid-flight. Skipped rather than faked if
        // the cohort finishes loading before the shutter opens.
        settle(140);
        boolean stillLoading = onEdtGet(new Callable<Boolean>() {
            public Boolean call() {
                return Boolean.valueOf(get(step, "data") == null);
            }
        }).booleanValue();
        if (stillLoading) {
            shootWizard("04_step3_loading_images");
        } else {
            System.out.println("  - skipped 04_step3_loading_images "
                    + "(cohort loaded before the shutter opened)");
        }

        waitUntil("chooser data", 180000, new Callable<Boolean>() {
            public Boolean call() throws Exception {
                return Boolean.valueOf(onEdtGet(new Callable<Boolean>() {
                    public Boolean call() {
                        return Boolean.valueOf(get(step, "data") != null
                                && step.channelRailForTest() != null);
                    }
                }).booleanValue());
            }
        });
        settle(2500);
        refreshButtons();
        shootWizard("05_step3_choose_images");

        // Lock every display range on the cohort percentiles, exactly what the
        // rail's own "lock" action stores, so the step can advance.
        onEdt(new Runnable() {
            public void run() {
                Step3Chooser.Data data = (Step3Chooser.Data) get(step, "data");
                ChannelRail rail = step.channelRailForTest();
                Map<Integer, DisplayRange> ranges =
                        fpb.QuickGrid.cohortRanges(data.histograms());
                for (ChannelRail.ChannelSpec spec : data.channelSpecs()) {
                    DisplayRange range = ranges.get(
                            Integer.valueOf(spec.channelIndex()));
                    if (range == null) continue;
                    rail.lockChannelForTest(spec.channelIndex(), range.min(),
                            range.max());
                }
            }
        });
        settle(2500);
        refreshButtons();
        shootWizard("06_step3_ranges_locked");

        // Second group tab, showing the per-group comparison plot update.
        onEdt(new Runnable() {
            public void run() {
                javax.swing.JTabbedPane tabs =
                        (javax.swing.JTabbedPane) get(step, "tabs");
                if (tabs != null && tabs.getTabCount() > 1) tabs.setSelectedIndex(1);
            }
        });
        settle(2500);
        shootWizard("07_step3_second_group_tab");

        onEdt(new Runnable() {
            public void run() {
                step.canAdvance();
            }
        });
        settle(300);
    }

    // ------------------------------------------------------------------
    // Step 4 - Layout, plus its four editors
    // ------------------------------------------------------------------

    private static void step4() throws Exception {
        final Object step = steps[3];
        showStep(3, 3);
        waitUntil("layout preview", 180000, new Callable<Boolean>() {
            public Boolean call() throws Exception {
                return onEdtGet(new Callable<Boolean>() {
                    public Boolean call() {
                        Object records = get(step, "previewRecords");
                        return Boolean.valueOf(records instanceof List
                                && !((List<?>) records).isEmpty()
                                && get(step, "previewFigure") != null);
                    }
                });
            }
        });
        settle(1500);

        // One group per row makes a squarer figure, and "Fit" shows all of it
        // rather than the left third of a very wide strip.
        onEdt(new Runnable() {
            public void run() {
                fpb.ui.layout.RowOrderPanel rows =
                        ((fpb.ui.layout.Step4Layout) step).rowOrderPanelForTest();
                if (rows != null) rows.oneGroupPerRowForTest();
            }
        });
        settle(2000);
        onEdt(new Runnable() {
            public void run() {
                @SuppressWarnings("unchecked")
                javax.swing.JComboBox<String> zoom =
                        (javax.swing.JComboBox<String>) get(step, "previewZoom");
                zoom.setSelectedItem("Fit");
            }
        });
        settle(2000);
        refreshButtons();
        shootWizard("08_step4_layout_preview");

        final PanelConfig config = onEdtGet(new Callable<PanelConfig>() {
            public PanelConfig call() {
                return (PanelConfig) get(step, "config");
            }
        });
        @SuppressWarnings("unchecked")
        final List<PanelRecord> records = onEdtGet(new Callable<List<PanelRecord>>() {
            @SuppressWarnings("unchecked")
            public List<PanelRecord> call() {
                return new ArrayList<PanelRecord>(
                        (List<PanelRecord>) get(step, "previewRecords"));
            }
        });
        final PanelRecord representative = records.isEmpty() ? null : records.get(0);

        shootModal("09_step4_edit_spacing", new Runnable() {
            public void run() {
                LayoutEditor.edit(wizardDialog, config, null);
            }
        });
        shootModal("10_step4_edit_external_labels", new Runnable() {
            public void run() {
                ExternalLabelEditor.edit(wizardDialog, records, config, null);
            }
        }, 4000);
        if (representative != null) {
            shootModal("11_step4_edit_annotations", new Runnable() {
                public void run() {
                    AnnotationEditor.edit(wizardDialog, representative, config, null);
                }
            }, 2500);
        }
        shootModal("12_step4_edit_calibration", new Runnable() {
            public void run() {
                invoke(step, "editCalibrationOverrides");
            }
        });
    }

    // ------------------------------------------------------------------
    // Step 5 - Export, including a real run
    // ------------------------------------------------------------------

    private static void step5(final File exportRoot) throws Exception {
        final Object step = steps[4];
        showStep(4, 4);
        onEdt(new Runnable() {
            public void run() {
                javax.swing.JTextField folder =
                        (javax.swing.JTextField) get(step, "outputFolder");
                folder.setText(exportRoot.getAbsolutePath());
                javax.swing.JComboBox<?> scale =
                        (javax.swing.JComboBox<?>) get(step, "exportScale");
                System.out.println("[probe] exportScale selected="
                        + scale.getSelectedItem() + " size=" + scale.getSize()
                        + " preferred=" + scale.getPreferredSize());
            }
        });
        settle(400);
        refreshButtons();
        shootWizard("13_step5_export_options");

        onEdt(new Runnable() {
            public void run() {
                invoke(step, "startExport");
            }
        });
        settle(3000);
        shootWizard("14_step5_export_running");

        waitUntil("export to finish", 600000, new Callable<Boolean>() {
            public Boolean call() throws Exception {
                return onEdtGet(new Callable<Boolean>() {
                    public Boolean call() {
                        return Boolean.valueOf(get(step, "activeRun") == null);
                    }
                });
            }
        });
        settle(1500);
        refreshButtons();
        shootWizard("15_step5_export_complete");
    }

    // ------------------------------------------------------------------
    // Capture plumbing
    // ------------------------------------------------------------------

    private static void refreshButtons() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                invoke(wizard, "updateButtons");
            }
        });
        settle(200);
    }

    private static void shootWizard(String name) throws Exception {
        capture(wizardDialog, name);
    }

    private static void shootModal(String name, Runnable trigger) throws Exception {
        shootModal(name, trigger, 1500);
    }

    /**
     * Clicks through to a modal dialog, photographs it and closes it. The modal
     * nested event loop keeps pumping the queue, so EDT calls still work while
     * the dialog is up.
     */
    private static void shootModal(String name, final Runnable trigger,
            int extraSettleMs) throws Exception {
        final Set<Window> before = new HashSet<Window>(
                Arrays.asList(Window.getWindows()));
        // Two always-on-top windows fight; drop the wizard so the child wins.
        onEdt(new Runnable() {
            public void run() {
                wizardDialog.setAlwaysOnTop(false);
            }
        });
        SwingUtilities.invokeLater(trigger);
        final Window dialog = waitForNewWindow(before, 30000);
        if (dialog == null) {
            System.out.println("  ! no dialog appeared for " + name);
            return;
        }
        settle(extraSettleMs);
        onEdt(new Runnable() {
            public void run() {
                dialog.setAlwaysOnTop(true);
                dialog.setLocation(0, 0);
                dialog.toFront();
            }
        });
        settle(700);
        capture(dialog, name);
        onEdt(new Runnable() {
            public void run() {
                dialog.setVisible(false);
                dialog.dispose();
            }
        });
        // Returns only once the nested modal loop has unwound.
        settle(600);
        onEdt(new Runnable() {
            public void run() {
                wizardDialog.setAlwaysOnTop(true);
                wizardDialog.toFront();
            }
        });
        settle(400);
    }

    private static Window waitForNewWindow(Set<Window> before, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Window found = onEdtGet(new Callable<Window>() {
                public Window call() {
                    for (Window window : Window.getWindows()) {
                        if (!before.contains(window) && window.isShowing()
                                && window.getWidth() > 60) {
                            return window;
                        }
                    }
                    return null;
                }
            });
            if (found != null) return found;
            Thread.sleep(120);
        }
        return null;
    }

    private static void capture(final Window window, String name) throws Exception {
        Rectangle bounds = onEdtGet(new Callable<Rectangle>() {
            public Rectangle call() {
                Point location = window.getLocationOnScreen();
                Dimension size = window.getSize();
                return new Rectangle(location.x, location.y, size.width, size.height);
            }
        });
        BufferedImage image = trimShadow(grab(bounds), bounds);
        shotIndex++;
        String fileName = name + ".png";
        File out = new File(outDir, fileName);
        ImageIO.write(image, "png", out);
        written.add(fileName + "  (" + image.getWidth() + "x" + image.getHeight()
                + (looksBlank(image) ? ", BLANK?" : "") + ")");
        System.out.println("captured " + fileName + " " + image.getWidth() + "x"
                + image.getHeight());
    }

    /**
     * Takes the highest-resolution variant so a 125%-scaled display yields
     * native pixels rather than a downscaled logical-size image.
     */
    private static BufferedImage grab(Rectangle bounds) {
        MultiResolutionImage multi = robot.createMultiResolutionScreenCapture(bounds);
        List<Image> variants = multi.getResolutionVariants();
        Image best = variants.get(variants.size() - 1);
        BufferedImage out = new BufferedImage(best.getWidth(null),
                best.getHeight(null), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = out.createGraphics();
        graphics.drawImage(best, 0, 0, null);
        graphics.dispose();
        return out;
    }

    private static BufferedImage trimShadow(BufferedImage image, Rectangle logical) {
        double scale = image.getWidth() / (double) logical.width;
        int inset = (int) Math.round(SHADOW_LOGICAL_PX * scale);
        if (inset <= 0 || image.getWidth() <= inset * 2 + 40
                || image.getHeight() <= inset + 40) {
            return image;
        }
        return image.getSubimage(inset, 0, image.getWidth() - inset * 2,
                image.getHeight() - inset);
    }

    private static boolean looksBlank(BufferedImage image) {
        Set<Integer> colours = new LinkedHashSet<Integer>();
        for (int y = 0; y < image.getHeight(); y += 17) {
            for (int x = 0; x < image.getWidth(); x += 17) {
                colours.add(Integer.valueOf(image.getRGB(x, y)));
                if (colours.size() > 6) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static void settle(int millis) throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(80);
            onEdt(new Runnable() {
                public void run() {
                    // Flushing the queue keeps painting ahead of the capture.
                }
            });
        }
    }

    private static void waitUntil(String what, long timeoutMs, Callable<Boolean> test)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(test.call())) return;
            Thread.sleep(200);
        }
        throw new IllegalStateException("Timed out waiting for " + what);
    }

    private static void onEdt(Runnable task) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }
        SwingUtilities.invokeAndWait(task);
    }

    private static <T> T onEdtGet(final Callable<T> task) throws Exception {
        final AtomicReference<T> result = new AtomicReference<T>();
        final AtomicReference<Exception> failure = new AtomicReference<Exception>();
        onEdt(new Runnable() {
            public void run() {
                try {
                    result.set(task.call());
                } catch (Exception thrown) {
                    failure.set(thrown);
                }
            }
        });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private static Object get(Object target, String name) {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception failure) {
            throw new IllegalStateException("field " + name, failure);
        }
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception failure) {
            throw new IllegalStateException("field " + name, failure);
        }
    }

    private static Object invoke(Object target, String name) {
        try {
            Method method = findMethod(target.getClass(), name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Exception failure) {
            throw new IllegalStateException("method " + name, failure);
        }
    }

    private static Field findField(Class<?> type, String name)
            throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Keep walking up.
            }
        }
        throw new NoSuchFieldException(name + " on " + type);
    }

    private static Method findMethod(Class<?> type, String name)
            throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                // Keep walking up.
            }
        }
        throw new NoSuchMethodException(name + " on " + type);
    }

    private FpbShots() {}
}
