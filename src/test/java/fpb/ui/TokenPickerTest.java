/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui;

import fpb.io.ImageSource;
import fpb.meta.MetadataRow;
import fpb.meta.TokenStrategy;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TokenPickerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void containerSourceShowsAndSplitsTheIndividualSeriesName()
            throws Exception {
        File lif = temp.newFile("NLGFKI.Cas.Iba.lif");
        ImageSource source = ImageSource.series(lif, 0, 1,
                "NLGFMa348_LH_SCN");
        Map<Integer, TokenStrategy.Field> oldFileAssignment =
                new LinkedHashMap<Integer, TokenStrategy.Field>();
        oldFileAssignment.put(Integer.valueOf(0), TokenStrategy.Field.GROUP);
        oldFileAssignment.put(Integer.valueOf(1), TokenStrategy.Field.SUBJECT);
        TokenPicker picker = new TokenPicker();

        picker.setSampleSource(source,
                new TokenStrategy('_', oldFileAssignment));

        assertEquals("NLGFMa348_LH_SCN", picker.sampleTextForTest());
        assertEquals("Split individual series name on",
                picker.splitLabelForTest());
        assertTrue(picker.groupChoiceAvailableForTest(0));
        assertEquals(TokenStrategy.Field.SUBJECT,
                picker.assignment().get(Integer.valueOf(0)));
        assertEquals(TokenStrategy.Field.SECTION,
                picker.assignment().get(Integer.valueOf(1)));
    }

    @Test
    public void seriesPickerBuildsExplicitSeriesLabelStrategy()
            throws Exception {
        File lif = temp.newFile("NLGFKI.Cas.Iba.lif");
        ImageSource source = ImageSource.series(lif, 0, 1,
                "NLGFMa348_LH_SCN");
        TokenPicker picker = new TokenPicker();
        picker.setSampleSource(source, null);
        picker.setTokenField(1, TokenStrategy.Field.SECTION);
        picker.setTokenField(2, TokenStrategy.Field.SECTION);

        TokenStrategy strategy = picker.strategy();
        MetadataRow row = new MetadataRow(source);
        strategy.apply(row);

        assertTrue(strategy.splitsSeriesLabels());
        assertEquals("NLGFMa", row.group);
        assertEquals("NLGFMa348", row.subject);
        assertEquals("LH_SCN", row.section);
    }

    @Test
    public void seriesPickerAllowsExplicitGroupTokens() throws Exception {
        File lif = temp.newFile("outer-name.lif");
        ImageSource source = ImageSource.series(lif, 0, 1,
                "Control_Mouse7_LH_SCN");
        TokenPicker picker = new TokenPicker();
        picker.setSampleSource(source, null);
        picker.setTokenField(0, TokenStrategy.Field.GROUP);
        picker.setTokenField(1, TokenStrategy.Field.SUBJECT);
        picker.setTokenField(2, TokenStrategy.Field.SECTION);
        picker.setTokenField(3, TokenStrategy.Field.SECTION);

        MetadataRow row = new MetadataRow(source);
        picker.strategy().apply(row);

        assertEquals("Control", row.group);
        assertEquals("Mouse7", row.subject);
        assertEquals("LH_SCN", row.section);
    }
}
