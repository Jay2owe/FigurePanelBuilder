/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.chooser;

import fpb.figure.ImageOrientation;
import fpb.io.ImageLoader;
import fpb.io.ProgressCallback;
import fpb.render.ChannelColour;
import fpb.render.DisplayRange;
import fpb.render.FPBRenderer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class RowImageTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void wideChooserThumbnailIsLetterboxedNotStretched() throws Exception {
        File file = temp.newFile("wide.png");
        BufferedImage source = new BufferedImage(100, 50,
                BufferedImage.TYPE_BYTE_GRAY);
        java.awt.Graphics2D g = source.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, source.getWidth(), source.getHeight());
        } finally {
            g.dispose();
        }
        ImageIO.write(source, "png", file);
        ImageLoader.LoadResult loaded = new ImageLoader(150, 1)
                .loadFiles(Arrays.asList(file), ProgressCallback.NONE);
        RowImage.Layout layout = new RowImage.Layout(1, 20, 100, 100,
                8, 6, 1.0, 1.0);
        BufferedImage row = RowImage.renderSubject(
                new RowImage.SubjectRow("Control", "S1", 0, false),
                loaded.planeCache(), loaded.histogramCache(),
                Arrays.asList(new FPBRenderer.ChannelRequest(0, "Signal",
                        ChannelColour.GREY, new DisplayRange(0, 255))),
                layout, false);
        int tileX = layout.spineWidth() + layout.gap();
        int tileY = layout.padding();

        assertEquals(new Color(30, 32, 34).getRGB(),
                row.getRGB(tileX + 50, tileY + 10));
        assertEquals(Color.WHITE.getRGB(),
                row.getRGB(tileX + 50, tileY + 50));
    }

    @Test
    public void everySectionIsPaintedInItsOwnChooserRowBand() throws Exception {
        File first = temp.newFile("section-1.png");
        File second = temp.newFile("section-2.png");
        writeSolid(first, 100, 50, Color.WHITE);
        writeSolid(second, 100, 50, Color.GRAY);
        ImageLoader.LoadResult loaded = new ImageLoader(150, 1)
                .loadFiles(Arrays.asList(first, second), ProgressCallback.NONE);
        RowImage.Layout layout = new RowImage.Layout(1, 20, 100, 100,
                8, 6, 1.0, 1.0).withSectionCount(2);
        BufferedImage row = RowImage.renderSubject(new RowImage.SubjectRow(
                        "Control", "S1", Arrays.asList(Integer.valueOf(0),
                                Integer.valueOf(1)), false, null),
                loaded.planeCache(), loaded.histogramCache(),
                Arrays.asList(new FPBRenderer.ChannelRequest(0, "Signal",
                        ChannelColour.GREY, new DisplayRange(0, 255))),
                layout, false);
        int tileX = layout.spineWidth() + layout.gap();
        int firstY = layout.padding();
        int secondY = firstY + layout.tileHeight() + layout.gap();
        int background = new Color(30, 32, 34).getRGB();

        assertEquals(layout.rowHeight(), row.getHeight());
        assertEquals(background, row.getRGB(tileX + 50, firstY + 10));
        assertEquals(background, row.getRGB(tileX + 50, secondY + 10));
        assertNotEquals(background, row.getRGB(tileX + 50, firstY + 50));
        assertNotEquals(background, row.getRGB(tileX + 50, secondY + 50));
        assertNotEquals(row.getRGB(tileX + 50, firstY + 50),
                row.getRGB(tileX + 50, secondY + 50));
    }

    @Test
    public void everyChooserRowOwnsIndependentOrientationControls() {
        RowImage.SubjectRow first = new RowImage.SubjectRow("Control", "S1", "A",
                0, false, null, "Control_S1_A.tif", ImageOrientation.IDENTITY);
        RowImage.SubjectRow second = new RowImage.SubjectRow("Control", "S1", "B",
                1, false, null, "Control_S1_B.tif", ImageOrientation.IDENTITY);
        PanelGrid grid = new PanelGrid("Control", Arrays.asList(first, second),
                RowImage.Layout.standard(1));
        AtomicInteger changes = new AtomicInteger();
        grid.setOrientationListener(new PanelGrid.OrientationListener() {
            @Override public void orientationChanged(RowImage.SubjectRow row) {
                changes.incrementAndGet();
            }
        });

        grid.applyOrientationForTest(1, ImageOrientation.Action.ROTATE_RIGHT);
        grid.applyOrientationForTest(1, ImageOrientation.Action.FLIP_VERTICAL);

        assertEquals(ImageOrientation.IDENTITY, first.orientation());
        assertEquals(ImageOrientation.IDENTITY
                        .then(ImageOrientation.Action.ROTATE_RIGHT)
                        .then(ImageOrientation.Action.FLIP_VERTICAL),
                second.orientation());
        assertEquals(2, changes.get());
    }

    private static void writeSolid(File file, int width, int height, Color colour)
            throws Exception {
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_BYTE_GRAY);
        java.awt.Graphics2D g = image.createGraphics();
        try {
            g.setColor(colour);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", file);
    }
}
