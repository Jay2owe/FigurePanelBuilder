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
    public void headerNavigationCommitsMetadataAndRejectsNewlyInvalidatedDestination()
            throws Exception {
        org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                FPBWizard wizard = new FPBWizard();
                javax.swing.JDialog dialog = null;
                try {
                    java.lang.reflect.Field dialogField = FPBWizard.class.getDeclaredField("dialog");
                    dialogField.setAccessible(true);
                    dialog = (javax.swing.JDialog) dialogField.get(wizard);
                    java.lang.reflect.Field stepsField = FPBWizard.class.getDeclaredField("steps");
                    stepsField.setAccessible(true);
                    Step1Images images = (Step1Images) ((WizardStep[]) stepsField.get(wizard))[0];
                    images.chooseFolder(new java.io.File("src/test/resources/fixtures/basic"));
                    java.lang.reflect.Field maximum = FPBWizard.class.getDeclaredField("maxCompletedIndex");
                    maximum.setAccessible(true);
                    maximum.setInt(wizard, 3);
                    assertTrue(images.tablePanel().table().editCellAt(0, 2));
                    ((javax.swing.JTextField) images.tablePanel().table().getEditorComponent())
                            .setText("Renamed group");
                    java.lang.reflect.Method navigate = FPBWizard.class.getDeclaredMethod(
                            "navigateTo", int.class);
                    navigate.setAccessible(true);
                    navigate.invoke(wizard, 0);
                    assertTrue(images.tablePanel().table().isEditing());
                    navigate.invoke(wizard, 3);
                    assertFalse(images.tablePanel().table().isEditing());
                    assertEquals("Renamed group", images.metadataTable().rows().get(0).group);
                    assertEquals(0, wizard.currentStepIndex());
                    assertEquals(0, maximum.getInt(wizard));
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                } finally {
                    wizard.context().imagePreloader.close();
                    if (dialog != null) dialog.dispose();
                }
            }
        });
    }

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
    public void leavingQuickGridLayoutGoesBackToChannelsSoTheyCanBeChanged() {
        assertEquals(1, FPBWizard.navigationTarget(true, 2));
        assertEquals(1, FPBWizard.navigationTarget(true, 1));
        assertEquals(0, FPBWizard.navigationTarget(true, 0));
        assertEquals(3, FPBWizard.navigationTarget(true, 3));
        assertEquals(2, FPBWizard.navigationTarget(false, 2));
        assertFalse(FPBWizard.quickGridRequestedForStep(true,
                FPBWizard.navigationTarget(true, 2)));
    }

    @Test
    public void quickGridUsesIncludedChannelsInTheirChosenOrder() {
        java.util.List<FPBWizard.ChannelSetting> settings =
                new java.util.ArrayList<FPBWizard.ChannelSetting>();
        FPBWizard.Context context = new FPBWizard.Context();
        Step2Channels step = new Step2Channels(context);
        step.onShow();
        step.moveChannel(2, -2);
        context.channelSettings.get(1).include = false;
        settings.addAll(context.channelSettings);

        java.util.List<fpb.ui.chooser.ChannelRail.ChannelSpec> specs =
                FPBWizard.quickGridChannels(settings);

        assertEquals(2, specs.size());
        assertEquals(2, specs.get(0).channelIndex());
        assertEquals(0, specs.get(1).channelIndex());
        assertEquals(null, FPBWizard.quickGridChannels(
                new java.util.ArrayList<FPBWizard.ChannelSetting>()));
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
