/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Step2ChannelsTest {

    @Test
    public void defaultsToThreeNamedChannelsWithDistinguishableColours() {
        FPBWizard.Context context = new FPBWizard.Context();
        Step2Channels step = new Step2Channels(context);

        step.onShow();

        assertEquals(3, step.detectedChannelCount());
        assertEquals("C1", step.channelSettings().get(0).name);
        assertEquals("C2", step.channelSettings().get(1).name);
        assertEquals("C3", step.channelSettings().get(2).name);
        assertEquals("blue", step.channelSettings().get(0).colour.name());
        assertEquals("magenta", step.channelSettings().get(1).colour.name());
        assertEquals("green", step.channelSettings().get(2).colour.name());
        assertTrue(step.canAdvance());
    }

    @Test
    public void atLeastOneIncludedChannelIsRequiredToAdvance() {
        FPBWizard.Context context = new FPBWizard.Context();
        Step2Channels step = new Step2Channels(context);
        step.onShow();

        for (FPBWizard.ChannelSetting setting : step.channelSettings()) {
            setting.include = false;
        }

        assertFalse(step.canAdvance());
    }
}
