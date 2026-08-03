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
import javax.swing.JPanel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FPBWizardTest {

    @Test
    public void backButtonRuleMatchesWizardContract() {
        assertFalse(FPBWizard.backVisibleForStep(0));
        assertTrue(FPBWizard.backVisibleForStep(1));
        assertTrue(FPBWizard.backVisibleForStep(4));
    }

    @Test
    public void primaryButtonNamesNextDestination() {
        WizardStep step = new StubStep("Channels");

        assertEquals("Next: Channels", FPBWizard.primaryButtonLabel(step, false));
        assertEquals("Channels", FPBWizard.primaryButtonLabel(step, true));
    }

    private static final class StubStep implements WizardStep {
        private final String nextTitle;

        StubStep(String nextTitle) {
            this.nextTitle = nextTitle;
        }

        @Override public String title() {
            return "Stub";
        }

        @Override public String nextTitle() {
            return nextTitle;
        }

        @Override public JComponent component() {
            return new JPanel();
        }

        @Override public void onShow() {}

        @Override public boolean canAdvance() {
            return true;
        }
    }
}
