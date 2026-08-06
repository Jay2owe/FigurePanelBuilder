/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui;

import fpb.figure.ImageOrientation;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;

/** Paints and hit-tests the shared icon-only orientation control panel. */
public final class ImageOrientationControls {

    public static final int BUTTON_SIZE = 20;
    public static final int GAP = 2;
    public static final int PADDING = 3;
    public static final int PANEL_SIZE = PADDING * 2 + BUTTON_SIZE * 2 + GAP;

    private static final Color PANEL = new Color(248, 249, 250, 236);
    private static final Color BUTTON = new Color(255, 255, 255, 244);
    private static final Color BUTTON_BORDER = new Color(142, 151, 160);
    private static final Color ICON = new Color(42, 47, 53);

    private static final ImageOrientation.Action[] ACTIONS = {
            ImageOrientation.Action.ROTATE_LEFT,
            ImageOrientation.Action.ROTATE_RIGHT,
            ImageOrientation.Action.FLIP_HORIZONTAL,
            ImageOrientation.Action.FLIP_VERTICAL
    };

    private ImageOrientationControls() {}

    public static void paint(Graphics2D graphics, Rectangle bounds,
            ImageOrientation orientation) {
        if (graphics == null || bounds == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(PANEL);
            g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 7, 7);
            g.setColor(BUTTON_BORDER);
            g.drawRoundRect(bounds.x, bounds.y, bounds.width - 1,
                    bounds.height - 1, 7, 7);
            for (int i = 0; i < ACTIONS.length; i++) {
                Rectangle button = buttonBounds(bounds, i);
                g.setColor(BUTTON);
                g.fillRoundRect(button.x, button.y, button.width, button.height, 5, 5);
                g.setColor(BUTTON_BORDER);
                g.drawRoundRect(button.x, button.y, button.width - 1,
                        button.height - 1, 5, 5);
                paintIcon(g, button, ACTIONS[i]);
            }
        } finally {
            g.dispose();
        }
    }

    public static ImageOrientation.Action actionAt(Point point,
            Rectangle bounds) {
        if (point == null || bounds == null || !bounds.contains(point)) return null;
        for (int i = 0; i < ACTIONS.length; i++) {
            if (buttonBounds(bounds, i).contains(point)) return ACTIONS[i];
        }
        return null;
    }

    private static Rectangle buttonBounds(Rectangle panel, int index) {
        int column = index % 2;
        int row = index / 2;
        return new Rectangle(panel.x + PADDING + column * (BUTTON_SIZE + GAP),
                panel.y + PADDING + row * (BUTTON_SIZE + GAP),
                BUTTON_SIZE, BUTTON_SIZE);
    }

    private static void paintIcon(Graphics2D g, Rectangle button,
            ImageOrientation.Action action) {
        g.setColor(ICON);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        int cx = button.x + button.width / 2;
        int cy = button.y + button.height / 2;
        if (action == ImageOrientation.Action.ROTATE_LEFT
                || action == ImageOrientation.Action.ROTATE_RIGHT) {
            boolean left = action == ImageOrientation.Action.ROTATE_LEFT;
            double start = left ? 25 : 155;
            double extent = left ? 255 : -255;
            g.draw(new Arc2D.Double(cx - 6, cy - 6, 12, 12,
                    start, extent, Arc2D.OPEN));
            Path2D arrow = new Path2D.Double();
            if (left) {
                arrow.moveTo(cx - 6, cy - 5);
                arrow.lineTo(cx - 7, cy + 1);
                arrow.lineTo(cx - 1, cy - 1);
            } else {
                arrow.moveTo(cx + 6, cy - 5);
                arrow.lineTo(cx + 7, cy + 1);
                arrow.lineTo(cx + 1, cy - 1);
            }
            arrow.closePath();
            g.fill(arrow);
            return;
        }
        boolean horizontal = action == ImageOrientation.Action.FLIP_HORIZONTAL;
        if (horizontal) {
            g.drawLine(cx, cy - 7, cx, cy + 7);
            triangle(g, cx - 2, cy, -1, 0);
            triangle(g, cx + 2, cy, 1, 0);
        } else {
            g.drawLine(cx - 7, cy, cx + 7, cy);
            triangle(g, cx, cy - 2, 0, -1);
            triangle(g, cx, cy + 2, 0, 1);
        }
    }

    private static void triangle(Graphics2D g, int x, int y, int dx, int dy) {
        Path2D triangle = new Path2D.Double();
        if (dx != 0) {
            triangle.moveTo(x + dx * 6, y);
            triangle.lineTo(x, y - 5);
            triangle.lineTo(x, y + 5);
        } else {
            triangle.moveTo(x, y + dy * 6);
            triangle.lineTo(x - 5, y);
            triangle.lineTo(x + 5, y);
        }
        triangle.closePath();
        g.draw(triangle);
    }
}
