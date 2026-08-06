/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.figure;

import org.junit.Test;

import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class ImageOrientationTest {

    @Test
    public void rotationsAndFlipsPreserveExactPixels() {
        BufferedImage source = numberedImage();

        assertPixels(ImageOrientation.IDENTITY
                        .then(ImageOrientation.Action.ROTATE_RIGHT).apply(source),
                new int[][] {{4, 1}, {5, 2}, {6, 3}});
        assertPixels(ImageOrientation.IDENTITY
                        .then(ImageOrientation.Action.ROTATE_LEFT).apply(source),
                new int[][] {{3, 6}, {2, 5}, {1, 4}});
        assertPixels(ImageOrientation.IDENTITY
                        .then(ImageOrientation.Action.FLIP_HORIZONTAL).apply(source),
                new int[][] {{3, 2, 1}, {6, 5, 4}});
        assertPixels(ImageOrientation.IDENTITY
                        .then(ImageOrientation.Action.FLIP_VERTICAL).apply(source),
                new int[][] {{4, 5, 6}, {1, 2, 3}});
    }

    @Test
    public void actionsComposeInDisplayedCoordinatesAndNormalize() {
        ImageOrientation orientation = ImageOrientation.IDENTITY
                .then(ImageOrientation.Action.ROTATE_RIGHT)
                .then(ImageOrientation.Action.FLIP_HORIZONTAL);
        assertPixels(orientation.apply(numberedImage()),
                new int[][] {{1, 4}, {2, 5}, {3, 6}});

        ImageOrientation fullTurn = ImageOrientation.IDENTITY;
        for (int i = 0; i < 4; i++) {
            fullTurn = fullTurn.then(ImageOrientation.Action.ROTATE_RIGHT);
        }
        assertSame(ImageOrientation.IDENTITY, fullTurn);
        assertEquals(orientation,
                ImageOrientation.fromToken(orientation.token()));
    }

    @Test
    public void quarterTurnsSwapDimensionsAndCalibrationAxes() {
        ImageOrientation right = ImageOrientation.IDENTITY
                .then(ImageOrientation.Action.ROTATE_RIGHT);
        CalibrationCheck.Result calibration = CalibrationCheck.userEntered(
                0.25, 0.75);
        CalibrationCheck.Result oriented = right.orientCalibration(calibration);

        assertEquals(2, right.orientedWidth(3, 2));
        assertEquals(3, right.orientedHeight(3, 2));
        assertEquals(0.75, oriented.pixelWidthUm(), 0.0);
        assertEquals(0.25, oriented.pixelHeightUm(), 0.0);
        assertEquals(calibration.source(), oriented.source());
    }

    private static BufferedImage numberedImage() {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        int value = 1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, value++);
            }
        }
        return image;
    }

    private static void assertPixels(BufferedImage image, int[][] expected) {
        assertEquals(expected[0].length, image.getWidth());
        assertEquals(expected.length, image.getHeight());
        for (int y = 0; y < expected.length; y++) {
            for (int x = 0; x < expected[y].length; x++) {
                assertEquals("pixel " + x + "," + y, expected[y][x],
                        image.getRGB(x, y) & 0x00ffffff);
            }
        }
    }
}
