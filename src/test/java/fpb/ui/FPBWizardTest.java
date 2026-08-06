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

    @Test
    public void fullScreenButtonAlwaysNamesTheAvailableAction() {
        assertEquals("Full screen", FPBWizard.fullScreenButtonText(false));
        assertEquals("Exit full screen", FPBWizard.fullScreenButtonText(true));
    }

    @Test
    public void exportPrimaryActionKeepsWizardOpenForProgressAndSummary() {
        Step5Export export = new Step5Export(new FPBWizard.Context());

        assertFalse(export.primaryActionClosesWizard());
        assertTrue(export instanceof AutoCloseable);
    }

    @Test
    public void upstreamMutationRevokesHistoricallyCompletedStepJumps() {
        assertEquals(0, FPBWizard.invalidatedMaxCompletedIndex(4, 0));
        assertEquals(1, FPBWizard.invalidatedMaxCompletedIndex(4, 1));
        assertEquals(1, FPBWizard.invalidatedMaxCompletedIndex(1, 3));
    }

    @Test
    public void exportDisablesWizardNavigationAndPreventsPrematureClosing() {
        assertTrue(FPBWizard.navigationEnabledWhile(false));
        assertFalse(FPBWizard.navigationEnabledWhile(true));
        assertTrue(FPBWizard.wizardMayClose(false));
        assertFalse(FPBWizard.wizardMayClose(true));
    }

    @Test
    public void enteringGuidedStepsClearsQuickGridRouteState() {
        assertTrue(FPBWizard.quickGridRequestedForStep(true, 3));
        assertFalse(FPBWizard.quickGridRequestedForStep(true, 2));
        assertFalse(FPBWizard.quickGridRequestedForStep(true, 1));
        assertFalse(FPBWizard.quickGridRequestedForStep(false, 3));
    }

    @Test
    public void leavingQuickGridLayoutRoutesThroughImagesBeforeGuidedSteps() {
        assertEquals(0, FPBWizard.navigationTarget(true, 2));
        assertEquals(0, FPBWizard.navigationTarget(true, 1));
        assertEquals(0, FPBWizard.navigationTarget(true, 0));
        assertEquals(3, FPBWizard.navigationTarget(true, 3));
        assertEquals(2, FPBWizard.navigationTarget(false, 2));
        assertTrue(FPBWizard.quickGridRequestedForStep(true,
                FPBWizard.navigationTarget(true, 2)));
    }

    @Test
    public void cancelledWorkerDoesNotUnlockUntilItsBackgroundActuallyExits() {
        assertFalse(Step5Export.exportRunMayFinish(false, true));
        assertFalse(Step5Export.exportRunMayFinish(true, false));
        assertTrue(Step5Export.exportRunMayFinish(true, true));
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
