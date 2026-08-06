/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.stats;

import fpb.meta.MetadataRow;
import fpb.meta.MetadataTable;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GroupQuantificationTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void eachSectionIsPreservedAndZNormalizedWithinItsChannel()
            throws Exception {
        List<MetadataRow> rows = new ArrayList<MetadataRow>();
        Map<File, Double> values = new LinkedHashMap<File, Double>();
        add(rows, values, "Control_S1_a.tif", "Control", "S1", "a", 10.0);
        add(rows, values, "Control_S1_b.tif", "Control", "S1", "b", 14.0);
        add(rows, values, "Control_S2.tif", "Control", "S2", "", 20.0);
        add(rows, values, "Drug_S1.tif", "Drug", "S1", "", 30.0);
        add(rows, values, "Drug_S2.tif", "Drug", "S2", "", 50.0);
        MetadataTable table = new MetadataTable(temp.getRoot(), rows);
        SubjectAggregator.SubjectStats subjects = SubjectAggregator.aggregate(table,
                Statistic.ImageValues.singleChannel(values, "Signal", "Mean"));

        GroupQuantification quantification = GroupQuantification.from(subjects);
        GroupQuantification.ChannelData channel = quantification.channel(0);
        GroupQuantification.GroupData control = channel.groups().get(0);
        GroupQuantification.GroupData drug = channel.groups().get(1);

        assertEquals(3, control.sectionCount());
        assertEquals(2, control.subjectCount());
        assertEquals(10.0, control.sections().get(0).rawValue(), 0.0);
        assertEquals("a", control.sections().get(0).section());
        assertEquals(14.0, control.sections().get(1).rawValue(), 0.0);
        assertEquals(20.0, control.sections().get(2).rawValue(), 0.0);
        assertEquals(44.0 / 3.0, control.mean(), 0.0000001);
        assertEquals(Math.sqrt(76.0 / 3.0), control.standardDeviation(),
                0.0000001);
        assertEquals(Math.sqrt(76.0 / 3.0) / Math.sqrt(3.0),
                control.standardError(), 0.0000001);
        assertEquals(40.0, drug.mean(), 0.0);
        assertEquals(10.0, drug.standardError(), 0.0000001);
        assertEquals(5, channel.overallCount());
        assertEquals(24.8, channel.overallMean(), 0.0000001);
        assertEquals(Math.sqrt(255.2), channel.overallStandardDeviation(),
                0.0000001);
        double zSum = 0.0;
        for (GroupQuantification.GroupData group : channel.groups()) {
            for (GroupQuantification.SectionValue section : group.sections()) {
                zSum += section.zScore();
            }
        }
        assertEquals(0.0, zSum / channel.overallCount(), 0.0000001);
        assertEquals(-channel.axisMinimum(), channel.axisMaximum(), 0.0);
        assertTrue(control.sections().get(0).zScore()
                != control.sections().get(1).zScore());
    }

    private void add(List<MetadataRow> rows, Map<File, Double> values,
            String name, String group, String subject, String section,
            double value) throws Exception {
        File file = temp.newFile(name);
        rows.add(new MetadataRow(file, group, subject, section));
        values.put(file, Double.valueOf(value));
    }
}
