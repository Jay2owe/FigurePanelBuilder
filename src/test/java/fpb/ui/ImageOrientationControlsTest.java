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

import org.junit.Test;

import java.awt.Point;
import java.awt.Rectangle;

import static org.junit.Assert.assertEquals;

public class ImageOrientationControlsTest {

    @Test
    public void iconPanelExposesFourIndependentHitTargets() {
        Rectangle panel = new Rectangle(10, 10,
                ImageOrientationControls.PANEL_SIZE,
                ImageOrientationControls.PANEL_SIZE);
        int first = 10 + ImageOrientationControls.PADDING
                + ImageOrientationControls.BUTTON_SIZE / 2;
        int second = first + ImageOrientationControls.BUTTON_SIZE
                + ImageOrientationControls.GAP;

        assertEquals(ImageOrientation.Action.ROTATE_LEFT,
                ImageOrientationControls.actionAt(new Point(first, first), panel));
        assertEquals(ImageOrientation.Action.ROTATE_RIGHT,
                ImageOrientationControls.actionAt(new Point(second, first), panel));
        assertEquals(ImageOrientation.Action.FLIP_HORIZONTAL,
                ImageOrientationControls.actionAt(new Point(first, second), panel));
        assertEquals(ImageOrientation.Action.FLIP_VERTICAL,
                ImageOrientationControls.actionAt(new Point(second, second), panel));
    }
}
