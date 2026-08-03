/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb;

import fpb.ui.FPBWizard;
import ij.plugin.PlugIn;

/** ImageJ entry point for Figure Panel Builder. */
public class FPB_ implements PlugIn {

    @Override
    public void run(String arg) {
        FPBWizard.showWizard();
    }
}
