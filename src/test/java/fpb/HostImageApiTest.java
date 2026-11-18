/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb;

import fpb.io.ImageLoader;
import fpb.io.ImageSource;
import fpb.render.ChannelColour;
import fpb.ui.Step5Export;
import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A host plugin (FLASH) hands Figure Panel Builder images it has already
 * prepared, labelled explicitly, instead of a folder to scan.
 */
public class HostImageApiTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void preparedImagesWithExplicitLabelsExportWithoutOpeningFiles()
            throws Exception {
        File project = temp.newFolder("project");
        // Neither file exists: a LIF series and a deconvolved derivative.
        final ImageSource control = ImageSource.series(new File(project, "Exp.lif"), 0, 2,
                "Mouse1_LH_SCN");
        final ImageSource treated = ImageSource.series(new File(project, "Exp.lif"), 1, 2,
                "Mouse2_LH_SCN");
        final AtomicInteger opened = new AtomicInteger();
        ImageLoader.ImageOpener opener = new ImageLoader.ImageOpener() {
            @Override
            public ImagePlus open(ImageSource source) throws IOException {
                opened.incrementAndGet();
                return twoChannel(source.equals(control) ? 1000 : 3000);
            }
        };
        File output = temp.newFolder("out");

        FPBParameters parameters = FPBParameters.builder(project)
                .image(control, "Control", "Mouse1", "LH")
                .image(treated, "Treated", "Mouse2", "LH")
                .imageOpener(opener)
                .channel(1, "DAPI", ChannelColour.BLUE, 0, 4000)
                .channel(2, "GFAP", ChannelColour.GREEN, 0, 4000)
                .pick("Control", "Mouse1")
                .pick("Treated", "Mouse2")
                .outputFolder(output)
                .figureName("Host")
                .writeTiff(false)
                .build();

        Step5Export.ExportResult result = FPB.runAndWrite(parameters);

        BufferedImage figure = ImageIO.read(new File(result.figureDirectory(),
                "figure.png"));
        assertTrue(figure.getWidth() > 0);
        assertTrue(new File(result.figureDirectory(), "figure.svg").isFile());
        // Previews for both images, then full resolution for each pick.
        assertEquals(4, opened.get());
    }

    private static ImagePlus twoChannel(int value) {
        ImageStack stack = new ImageStack(40, 30);
        for (int c = 0; c < 2; c++) {
            ShortProcessor processor = new ShortProcessor(40, 30);
            processor.setValue(value * (c + 1) / 2);
            processor.fill();
            stack.addSlice(processor);
        }
        ImagePlus image = new ImagePlus("prepared", stack);
        image.setDimensions(2, 1, 1);
        return new CompositeImage(image, CompositeImage.COMPOSITE);
    }
}
