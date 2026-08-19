/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;

import javax.swing.ComboBoxModel;
import javax.swing.JComboBox;

/**
 * A combo box that always reserves room for its widest entry.
 *
 * <p>Under Windows display scaling the platform look and feel paints its arrow
 * button, border and padding wider than the space it reserves, so entries such
 * as "Magenta" or "1x" are replaced by an ellipsis. Measuring the model on
 * every layout pass fixes that, and keeps working for the combo boxes whose
 * items are only added once a folder, group or scale list is known.</p>
 */
public final class FitComboBox<E> extends JComboBox<E> {

    private static final long serialVersionUID = 1L;

    /** Arrow button, both borders and the padding the platform theme adds. */
    private static final int CHROME_PX = 36;

    public FitComboBox() {
        super();
    }

    public FitComboBox(E[] items) {
        super(items);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        if (isPreferredSizeSet()) return preferred;
        return new Dimension(Math.max(preferred.width, widestEntryPx() + CHROME_PX),
                preferred.height);
    }

    private int widestEntryPx() {
        Font font = getFont();
        if (font == null) return 0;
        FontMetrics metrics = getFontMetrics(font);
        int widest = textWidth(metrics, getSelectedItem());
        ComboBoxModel<E> model = getModel();
        for (int i = 0; i < model.getSize(); i++) {
            widest = Math.max(widest, textWidth(metrics, model.getElementAt(i)));
        }
        return widest;
    }

    private static int textWidth(FontMetrics metrics, Object value) {
        return value == null ? 0 : metrics.stringWidth(String.valueOf(value));
    }
}
