/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui;

import javax.swing.JComponent;

/** One screen hosted by the Figure Panel Builder wizard. */
public interface WizardStep {

    String title();

    String nextTitle();

    JComponent component();

    void onShow();

    boolean canAdvance();
}
