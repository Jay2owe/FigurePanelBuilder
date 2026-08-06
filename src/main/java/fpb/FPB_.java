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
import ij.IJ;
import ij.Macro;
import ij.plugin.frame.Recorder;
import ij.plugin.PlugIn;

import java.awt.GraphicsEnvironment;

/** ImageJ entry point for Figure Panel Builder. */
public class FPB_ implements PlugIn {

    @Override
    public void run(String arg) {
        String macroOptions = Macro.getOptions();
        if (!hasText(macroOptions) && hasText(arg)) {
            macroOptions = arg;
        }
        if (hasText(macroOptions) || GraphicsEnvironment.isHeadless()) {
            runMacro(macroOptions);
            return;
        }
        FPBWizard.showWizard();
    }

    private void runMacro(String optionsText) {
        if (!hasText(optionsText)) {
            reportMacroError("Figure Panel Builder macro/headless execution requires explicit macro options.");
            return;
        }
        try {
            FPBMacroOptions options = FPBMacroOptionsParser.parse(optionsText);
            FPBParameters parameters = options.toParameters();
            FPBResult result = FPB.run(parameters);
            if (parameters.outputFolder() != null) {
                FPB.write(result);
            }
            IJ.showStatus("Figure Panel Builder: done.");
        } catch (Exception ex) {
            reportMacroError(ex.getMessage());
        }
    }

    static void recordMacroCall(FPBMacroOptions options) {
        if (!Recorder.record || options == null) return;
        try {
            Recorder.recordString("run(\"" + FPBMacroOptions.PLUGIN_NAME
                    + "\", \"" + options.toMacroOptions() + "\");\n");
        } catch (IllegalArgumentException ex) {
            IJ.log("Figure Panel Builder: Could not record macro options: "
                    + ex.getMessage());
        }
    }

    private void reportMacroError(String message) {
        String text = hasText(message) ? message : "Unknown Figure Panel Builder macro error.";
        if (GraphicsEnvironment.isHeadless()) {
            IJ.log("Figure Panel Builder ERROR: " + text);
        } else {
            IJ.error("Figure Panel Builder", text);
        }
    }

    private static boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
