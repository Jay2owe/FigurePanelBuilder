/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.layout;

import fpb.figure.CalibrationCheck;
import fpb.figure.PanelConfig;
import fpb.figure.PanelRecord;
import fpb.figure.PanelWriter;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LayoutTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void fractionalPositionsSurviveResize() {
        Rectangle preview = new Rectangle(10, 20, 200, 100);
        Rectangle export = new Rectangle(0, 0, 800, 400);

        double[] fraction = AnnotationEditor.fractionForPoint(
                new Point(60, 70), preview);
        Point exportPoint = AnnotationEditor.pointForFraction(
                fraction[0], fraction[1], export);

        assertEquals(0.25, fraction[0], 0.0001);
        assertEquals(0.50, fraction[1], 0.0001);
        assertEquals(200, exportPoint.x);
        assertEquals(200, exportPoint.y);
    }

    @Test
    public void rowOrderPanelBuildsRowsFromAssignments() {
        List<String> groups = Arrays.asList("Control", "DrugA", "DrugB");
        RowOrderPanel panel = new RowOrderPanel(groups,
                RowOrderPanel.allInOneRow(groups));

        panel.oneGroupPerRowForTest();

        assertEquals(Arrays.asList(
                Collections.singletonList("Control"),
                Collections.singletonList("DrugA"),
                Collections.singletonList("DrugB")), panel.rows());
    }

    @Test
    public void groupRowsAffectRenderedFigureShape() throws Exception {
        List<PanelRecord> records = Arrays.asList(
                record("Control"),
                record("DrugA"),
                record("DrugB"),
                record("Wash"));
        PanelConfig oneRow = PanelConfig.builder()
                .cellSizePx(60)
                .channelOrder(Collections.singletonList("Merge"))
                .groupLayoutRows(Collections.singletonList(Arrays.asList(
                        "Control", "DrugA", "DrugB", "Wash")))
                .build();
        PanelConfig onePerRow = oneRow.toBuilder()
                .groupLayoutRows(Arrays.asList(
                        Collections.singletonList("Control"),
                        Collections.singletonList("DrugA"),
                        Collections.singletonList("DrugB"),
                        Collections.singletonList("Wash")))
                .build();

        BufferedImage wide = PanelWriter.renderOverviewPanel(records, oneRow);
        BufferedImage tall = PanelWriter.renderOverviewPanel(records, onePerRow);

        assertTrue(wide.getWidth() > tall.getWidth());
        assertTrue(tall.getHeight() > wide.getHeight());
    }

    private PanelRecord record(String group) throws Exception {
        File file = temp.newFile(group + ".png");
        BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, Color.WHITE.getRGB());
            }
        }
        ImageIO.write(image, "png", file);
        return new PanelRecord(file, group, "S1", "", "Merge",
                "Merge", -1, 24, 24, 0.5, 0.5,
                CalibrationCheck.CalibrationSource.USER_ENTERED);
    }
}
