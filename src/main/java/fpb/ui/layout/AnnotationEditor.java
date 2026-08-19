/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.layout;

import fpb.figure.PanelConfig;
import fpb.figure.PanelRecord;
import fpb.figure.PanelWriter;
import fpb.figure.ScaleBar;
import fpb.ui.FitComboBox;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Locale;

/** Interactive editor for fractional label and scale-bar placement. */
public final class AnnotationEditor extends JDialog {

    public interface Listener {
        void annotationChanged(PanelConfig config);
    }

    public enum Grab {
        NONE,
        LABEL_BODY,
        LABEL_RESIZE,
        BAR_BODY,
        BAR_RESIZE
    }

    private static final int HANDLE = 12;
    private static final double SNAP_THRESHOLD = 0.06;
    private static final double[] SCALE_CHOICES =
            new double[] { 10, 20, 25, 50, 100, 200, 500 };

    private final PanelRecord record;
    private final BufferedImage sourceImage;
    private final Canvas canvas = new Canvas();
    private final JComboBox<String> labelModeBox =
            new FitComboBox<String>(new String[] {
                    "None", "Channel name", "Image name", "Group + subject", "Custom"
            });
    private final JTextField customLabel = field("", 10);
    private final JTextField fontSize = field("18", 4);
    private final JComboBox<String> colourBox =
            new FitComboBox<String>(new String[] { "White", "Black" });
    private final JComboBox<String> lengthBox = new FitComboBox<String>();
    private final JTextField customLength = field("100", 5);
    private final JTextField thickness = field("6", 4);
    private final JComboBox<String> cornerBox =
            new FitComboBox<String>(new String[] {
                    "Top left", "Top right", "Bottom left", "Bottom right"
            });
    private final JCheckBox snapToCorners =
            new JCheckBox("Snap dragged objects to corners");

    private PanelConfig config;
    private PanelConfig result;
    private Listener listener;
    private boolean syncing;

    private AnnotationEditor(Window owner, PanelRecord record, PanelConfig config) {
        super(owner, "Edit annotations", Dialog.ModalityType.APPLICATION_MODAL);
        this.record = record;
        this.config = config;
        this.sourceImage = readSource(record);
        buildChoices();
        syncFieldsFromConfig();
        buildUi();
        pack();
        setLocationRelativeTo(owner);
    }

    public static PanelConfig edit(Window owner, PanelRecord record,
            PanelConfig config, Listener listener) {
        if (config == null || GraphicsEnvironment.isHeadless()) return config;
        AnnotationEditor editor = new AnnotationEditor(owner, record, config);
        editor.listener = listener;
        editor.setVisible(true);
        return editor.result;
    }

    public static double[] fractionForPoint(Point point, Rectangle imageRect) {
        if (point == null || imageRect == null || imageRect.width <= 0
                || imageRect.height <= 0) {
            return new double[] { 0.0, 0.0 };
        }
        return new double[] {
                clampFraction((point.x - imageRect.x) / (double) imageRect.width),
                clampFraction((point.y - imageRect.y) / (double) imageRect.height)
        };
    }

    public static Point pointForFraction(double fracX, double fracY,
            Rectangle imageRect) {
        if (imageRect == null) return new Point();
        return new Point(imageRect.x + (int) Math.round(clampFraction(fracX)
                        * imageRect.width),
                imageRect.y + (int) Math.round(clampFraction(fracY)
                        * imageRect.height));
    }

    public static PanelConfig moveLabelTo(PanelConfig config, Point point,
            Rectangle imageRect) {
        double[] frac = fractionForPoint(point, imageRect);
        return config.toBuilder().labelFracX(frac[0]).labelFracY(frac[1]).build();
    }

    public static PanelConfig moveScaleBarTo(PanelConfig config, Point point,
            Rectangle imageRect) {
        double[] frac = fractionForPoint(point, imageRect);
        return config.toBuilder().scaleBarFracX(frac[0]).scaleBarFracY(frac[1]).build();
    }

    public static Grab pickTarget(Point p, Rectangle labelBox,
            Rectangle labelHandle, Rectangle barBox, Rectangle barHandle) {
        if (p == null) return Grab.NONE;
        if (labelHandle != null && labelHandle.contains(p)) return Grab.LABEL_RESIZE;
        if (barHandle != null && barHandle.contains(p)) return Grab.BAR_RESIZE;
        if (labelBox != null && labelBox.contains(p)) return Grab.LABEL_BODY;
        if (barBox != null && barBox.contains(p)) return Grab.BAR_BODY;
        return Grab.NONE;
    }

    private void buildChoices() {
        for (double choice : SCALE_CHOICES) lengthBox.addItem(formatNumber(choice));
        lengthBox.addItem("Custom");
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        canvas.setPreferredSize(new Dimension(540, 420));
        canvas.setBackground(new Color(42, 44, 46));
        canvas.setBorder(BorderFactory.createLineBorder(new Color(90, 96, 102)));
        root.add(canvas, BorderLayout.CENTER);
        root.add(sidePanel(), BorderLayout.EAST);

        JLabel hint = new JLabel("Drag to move, drag a corner handle to resize.");
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            result = null;
            dispose();
        });
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> {
            result = config;
            dispose();
        });
        buttons.add(cancel);
        buttons.add(ok);

        JPanel south = new JPanel(new BorderLayout());
        south.add(hint, BorderLayout.WEST);
        south.add(buttons, BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private JPanel sidePanel() {
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.add(section("Label"));
        side.add(row("Mode", labelModeBox));
        side.add(row("Custom", customLabel));
        side.add(row("Font px", fontSize));
        side.add(row("Colour", colourBox));
        side.add(Box.createVerticalStrut(10));
        side.add(section("Scale bar"));
        side.add(row("Length um", lengthBox));
        side.add(row("Custom um", customLength));
        side.add(row("Thickness", thickness));
        side.add(row("Corner", cornerBox));
        side.add(Box.createVerticalStrut(10));
        side.add(section("Canvas"));
        snapToCorners.setAlignmentX(Component.LEFT_ALIGNMENT);
        snapToCorners.setOpaque(false);
        side.add(snapToCorners);
        side.add(Box.createVerticalGlue());

        labelModeBox.addActionListener(e -> updateConfigFromFields());
        customLabel.addActionListener(e -> updateConfigFromFields());
        fontSize.addActionListener(e -> updateConfigFromFields());
        colourBox.addActionListener(e -> updateConfigFromFields());
        lengthBox.addActionListener(e -> updateConfigFromFields());
        customLength.addActionListener(e -> updateConfigFromFields());
        thickness.addActionListener(e -> updateConfigFromFields());
        snapToCorners.addActionListener(e -> updateConfigFromFields());
        cornerBox.addActionListener(e -> {
            if (syncing) return;
            PanelConfig.Position position = parsePosition(selected(cornerBox));
            double[] frac = cornerFraction(position);
            config = config.toBuilder()
                    .scaleBarPosition(position)
                    .scaleBarFracX(frac[0])
                    .scaleBarFracY(frac[1])
                    .build();
            fireChanged();
        });
        return side;
    }

    private void updateConfigFromFields() {
        if (syncing) return;
        double length = parseLength();
        PanelConfig.Builder builder = config.toBuilder()
                .labelMode(parseLabelMode(selected(labelModeBox)))
                .customLabelTemplate(customLabel.getText())
                .labelFontSizePx(parseInt(fontSize, config.labelFontSizePx()))
                .annotationColor("Black".equals(selected(colourBox))
                        ? Color.BLACK : Color.WHITE)
                .scaleBarLengthUm(length)
                .scaleBarThicknessPx(parseInt(thickness,
                        config.scaleBarThicknessPx()))
                .annotationSnapEnabled(snapToCorners.isSelected());
        config = builder.build();
        fireChanged();
    }

    private void fireChanged() {
        canvas.repaint();
        if (listener != null) listener.annotationChanged(config);
    }

    private void syncFieldsFromConfig() {
        syncing = true;
        try {
            labelModeBox.setSelectedItem(labelModeLabel(config.labelMode()));
            customLabel.setText(config.customLabelTemplate());
            fontSize.setText(String.valueOf(config.labelFontSizePx()));
            colourBox.setSelectedItem(Color.BLACK.equals(config.annotationColor())
                    ? "Black" : "White");
            selectLength(config.scaleBarLengthUm());
            customLength.setText(formatNumber(config.scaleBarLengthUm()));
            thickness.setText(String.valueOf(config.scaleBarThicknessPx()));
            cornerBox.setSelectedItem(positionLabel(config.scaleBarPosition()));
            snapToCorners.setSelected(config.annotationSnapEnabled());
        } finally {
            syncing = false;
        }
    }

    private final class Canvas extends JPanel {
        private Rectangle imageRect;
        private Rectangle labelBox;
        private Rectangle labelHandle;
        private Rectangle barBox;
        private Rectangle barHandle;
        private Grab grab = Grab.NONE;
        private int offsetX;
        private int offsetY;
        private int startY;
        private int startValue;

        Canvas() {
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    press(e.getPoint());
                }

                @Override public void mouseDragged(MouseEvent e) {
                    drag(e.getPoint());
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private void press(Point point) {
            grab = pickTarget(point, labelBox, labelHandle, barBox, barHandle);
            if (grab == Grab.LABEL_BODY && labelBox != null) {
                offsetX = point.x - labelBox.x;
                offsetY = point.y - labelBox.y;
            } else if (grab == Grab.BAR_BODY && barBox != null) {
                offsetX = point.x - barBox.x;
                offsetY = point.y - barBox.y;
            } else if (grab == Grab.LABEL_RESIZE) {
                startY = point.y;
                startValue = config.labelFontSizePx();
            } else if (grab == Grab.BAR_RESIZE) {
                startY = point.y;
                startValue = config.scaleBarThicknessPx();
            }
        }

        private void drag(Point point) {
            if (grab == Grab.NONE || imageRect == null) return;
            if (grab == Grab.LABEL_BODY) {
                config = moveLabelTo(config,
                        new Point(point.x - offsetX, point.y - offsetY), imageRect);
                config = maybeSnapLabel(config);
            } else if (grab == Grab.BAR_BODY) {
                config = moveScaleBarTo(config,
                        new Point(point.x - offsetX, point.y - offsetY), imageRect);
                config = maybeSnapBar(config);
            } else if (grab == Grab.LABEL_RESIZE) {
                config = config.toBuilder().labelFontSizePx(
                        clamp(startValue + (point.y - startY) / 2, 8, 96)).build();
                syncFieldsFromConfig();
            } else if (grab == Grab.BAR_RESIZE) {
                config = config.toBuilder().scaleBarThicknessPx(
                        clamp(startValue + (point.y - startY) / 2, 1, 30)).build();
                syncFieldsFromConfig();
            }
            fireChanged();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            labelBox = null;
            labelHandle = null;
            barBox = null;
            barHandle = null;
            imageRect = imageRect();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Color.BLACK);
                g.fillRect(imageRect.x, imageRect.y, imageRect.width, imageRect.height);
                if (sourceImage != null) {
                    g.drawImage(sourceImage, imageRect.x, imageRect.y,
                            imageRect.width, imageRect.height, null);
                }
                paintEditableAnnotations(g);
            } finally {
                g.dispose();
            }
        }

        private Rectangle imageRect() {
            int sourceW = sourceImage == null ? 420 : sourceImage.getWidth();
            int sourceH = sourceImage == null ? 260 : sourceImage.getHeight();
            int availW = Math.max(1, getWidth() - 24);
            int availH = Math.max(1, getHeight() - 24);
            double scale = Math.min(availW / (double) sourceW,
                    availH / (double) sourceH);
            int w = Math.max(1, (int) Math.round(sourceW * scale));
            int h = Math.max(1, (int) Math.round(sourceH * scale));
            return new Rectangle((getWidth() - w) / 2, (getHeight() - h) / 2, w, h);
        }

        private void paintEditableAnnotations(Graphics2D g) {
            double styleScale = imageRect.width / (double) Math.max(1,
                    record == null ? imageRect.width : record.widthPx());
            PanelWriter.drawAnnotations(g, previewRecord(), config, imageRect,
                    styleScale);
            labelBox = labelBox(g, styleScale);
            if (labelBox != null) labelHandle = handle(g, labelBox);
            barBox = barBox();
            if (barBox != null) barHandle = handle(g, barBox);
            outline(g, labelBox);
            outline(g, barBox);
        }

        private PanelRecord previewRecord() {
            if (record != null) return record;
            return new PanelRecord(null, "Group", "Subject", "Section",
                    "Merge", "Merge", -1, imageRect.width, imageRect.height,
                    1.0, 1.0,
                    fpb.figure.CalibrationCheck.CalibrationSource.USER_ENTERED);
        }

        private Rectangle labelBox(Graphics2D g, double styleScale) {
            String text = labelText(previewRecord(), config);
            if (text.isEmpty()) return null;
            int fontPx = Math.max(8, (int) Math.round(config.labelFontSizePx()
                    * Math.max(0.6, styleScale)));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontPx));
            FontMetrics fm = g.getFontMetrics();
            int textW = fm.stringWidth(text);
            int textH = fm.getAscent() + fm.getDescent();
            Point origin = config.hasLabelFraction()
                    ? pointForFraction(config.labelFracX(), config.labelFracY(),
                    imageRect)
                    : pointForFraction(cornerFraction(config.labelPosition())[0],
                    cornerFraction(config.labelPosition())[1], imageRect);
            return clampBox(origin.x, origin.y, textW, textH);
        }

        private Rectangle barBox() {
            PanelRecord preview = previewRecord();
            int barLength = ScaleBar.lengthPixels(preview, imageRect.width,
                    config.scaleBarLengthUm());
            if (barLength <= 0) barLength = Math.max(12, imageRect.width / 4);
            int thicknessPx = Math.max(2, config.scaleBarThicknessPx());
            Point origin = config.hasScaleBarFraction()
                    ? pointForFraction(config.scaleBarFracX(), config.scaleBarFracY(),
                    imageRect)
                    : pointForFraction(cornerFraction(config.scaleBarPosition())[0],
                    cornerFraction(config.scaleBarPosition())[1], imageRect);
            return clampBox(origin.x, origin.y,
                    Math.min(barLength, imageRect.width), thicknessPx);
        }

        private Rectangle clampBox(int x, int y, int w, int h) {
            int maxX = imageRect.x + imageRect.width - w;
            int maxY = imageRect.y + imageRect.height - h;
            if (maxX < imageRect.x) maxX = imageRect.x;
            if (maxY < imageRect.y) maxY = imageRect.y;
            return new Rectangle(Math.max(imageRect.x, Math.min(maxX, x)),
                    Math.max(imageRect.y, Math.min(maxY, y)), w, h);
        }

        private Rectangle handle(Graphics2D g, Rectangle box) {
            Rectangle handle = new Rectangle(box.x + box.width - HANDLE / 2,
                    box.y + box.height - HANDLE / 2, HANDLE, HANDLE);
            g.setColor(new Color(255, 214, 64));
            g.fillRect(handle.x, handle.y, handle.width, handle.height);
            g.setColor(new Color(40, 40, 40));
            g.drawRect(handle.x, handle.y, handle.width, handle.height);
            return handle;
        }

        private void outline(Graphics2D g, Rectangle box) {
            if (box == null) return;
            g.setColor(new Color(255, 214, 64, 160));
            g.setStroke(new BasicStroke(1.0f));
            g.drawRect(box.x - 2, box.y - 2, box.width + 4, box.height + 4);
        }
    }

    static PanelConfig maybeSnapLabel(PanelConfig config) {
        if (!config.annotationSnapEnabled()) return config;
        PanelConfig.Position position = snap(config.labelFracX(), config.labelFracY());
        if (position == null) return config;
        double[] frac = cornerFraction(position);
        return config.toBuilder().labelPosition(position)
                .labelFracX(frac[0]).labelFracY(frac[1]).build();
    }

    static PanelConfig maybeSnapBar(PanelConfig config) {
        if (!config.annotationSnapEnabled()) return config;
        PanelConfig.Position position = snap(config.scaleBarFracX(),
                config.scaleBarFracY());
        if (position == null) return config;
        double[] frac = cornerFraction(position);
        return config.toBuilder().scaleBarPosition(position)
                .scaleBarFracX(frac[0]).scaleBarFracY(frac[1]).build();
    }

    private static PanelConfig.Position snap(double x, double y) {
        PanelConfig.Position best = null;
        double bestDistance = Double.MAX_VALUE;
        for (PanelConfig.Position position : PanelConfig.Position.values()) {
            double[] corner = cornerFraction(position);
            double distance = Math.hypot(x - corner[0], y - corner[1]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = position;
            }
        }
        return bestDistance <= SNAP_THRESHOLD ? best : null;
    }

    static double[] cornerFraction(PanelConfig.Position position) {
        if (position == PanelConfig.Position.TOP_RIGHT) return new double[] { 1.0, 0.0 };
        if (position == PanelConfig.Position.BOTTOM_LEFT) return new double[] { 0.0, 1.0 };
        if (position == PanelConfig.Position.BOTTOM_RIGHT) return new double[] { 1.0, 1.0 };
        return new double[] { 0.0, 0.0 };
    }

    private static BufferedImage readSource(PanelRecord record) {
        try {
            if (record != null && record.imageFile() != null) {
                return ImageIO.read(record.imageFile());
            }
        } catch (IOException ignored) {
            // Use a synthetic dark preview below.
        }
        BufferedImage image = new BufferedImage(420, 260, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(18, 22, 28));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(new Color(70, 140, 180));
            for (int y = 30; y < image.getHeight(); y += 52) {
                for (int x = 36; x < image.getWidth(); x += 58) {
                    g.fillOval(x - 7, y - 7, 14, 14);
                }
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private double parseLength() {
        String selected = selected(lengthBox);
        if ("Custom".equals(selected)) {
            return parseDouble(customLength, config.scaleBarLengthUm());
        }
        try {
            return Double.parseDouble(selected);
        } catch (NumberFormatException e) {
            return config.scaleBarLengthUm();
        }
    }

    private void selectLength(double length) {
        String formatted = formatNumber(length);
        for (int i = 0; i < lengthBox.getItemCount(); i++) {
            if (formatted.equals(lengthBox.getItemAt(i))) {
                lengthBox.setSelectedIndex(i);
                return;
            }
        }
        lengthBox.setSelectedItem("Custom");
    }

    private static String labelText(PanelRecord record, PanelConfig config) {
        if (config.labelMode() == PanelConfig.LabelMode.NONE) return "";
        String template;
        if (config.labelMode() == PanelConfig.LabelMode.IMAGE_NAME) {
            template = "{group} {subject} {section}";
        } else if (config.labelMode() == PanelConfig.LabelMode.GROUP_SUBJECT) {
            template = "{group} {subject}";
        } else if (config.labelMode() == PanelConfig.LabelMode.CUSTOM) {
            template = config.customLabelTemplate().isEmpty()
                    ? "{channel}" : config.customLabelTemplate();
        } else {
            template = "{channel}";
        }
        return template
                .replace("{group}", record.group())
                .replace("{subject}", record.subject())
                .replace("{section}", record.section())
                .replace("{channel}", record.channelName())
                .replace("{output}", record.outputName())
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static PanelConfig.LabelMode parseLabelMode(String text) {
        String value = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(value)) return PanelConfig.LabelMode.NONE;
        if ("image name".equals(value)) return PanelConfig.LabelMode.IMAGE_NAME;
        if ("group + subject".equals(value)) return PanelConfig.LabelMode.GROUP_SUBJECT;
        if ("custom".equals(value)) return PanelConfig.LabelMode.CUSTOM;
        return PanelConfig.LabelMode.CHANNEL_NAME;
    }

    private static String labelModeLabel(PanelConfig.LabelMode mode) {
        if (mode == PanelConfig.LabelMode.NONE) return "None";
        if (mode == PanelConfig.LabelMode.IMAGE_NAME) return "Image name";
        if (mode == PanelConfig.LabelMode.GROUP_SUBJECT) return "Group + subject";
        if (mode == PanelConfig.LabelMode.CUSTOM) return "Custom";
        return "Channel name";
    }

    private static PanelConfig.Position parsePosition(String text) {
        String value = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if ("top right".equals(value)) return PanelConfig.Position.TOP_RIGHT;
        if ("bottom left".equals(value)) return PanelConfig.Position.BOTTOM_LEFT;
        if ("bottom right".equals(value)) return PanelConfig.Position.BOTTOM_RIGHT;
        return PanelConfig.Position.TOP_LEFT;
    }

    private static String positionLabel(PanelConfig.Position position) {
        if (position == PanelConfig.Position.TOP_RIGHT) return "Top right";
        if (position == PanelConfig.Position.BOTTOM_LEFT) return "Bottom left";
        if (position == PanelConfig.Position.BOTTOM_RIGHT) return "Bottom right";
        return "Top left";
    }

    private static JPanel row(String label, Component component) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel text = new JLabel(label);
        text.setPreferredSize(new Dimension(86, 18));
        row.add(text);
        row.add(component);
        return row;
    }

    private static JLabel section(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JTextField field(String value, int columns) {
        JTextField field = new JTextField(value, columns);
        field.setMaximumSize(new Dimension(Math.max(50, columns * 12), 24));
        return field;
    }

    private static int parseInt(JTextField field, int fallback) {
        try {
            return Integer.parseInt(field.getText().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(JTextField field, double fallback) {
        try {
            return Double.parseDouble(field.getText().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampFraction(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String selected(JComboBox<String> combo) {
        Object value = combo.getSelectedItem();
        return value == null ? "" : value.toString();
    }

    private static String formatNumber(double value) {
        if (Math.rint(value) == value && Double.isFinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.US, "%.1f", value);
    }
}
