package fpb.ui;

import fpb.figure.*;
import fpb.io.ImageSource;
import fpb.meta.*;
import fpb.render.*;
import fpb.ui.chooser.RowImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class FigureProjectIOTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private FPBWizard.Context example(File root) throws Exception {
        FPBWizard.Context c = new FPBWizard.Context();
        c.folder = root;
        File image = new File(root, "original.tif");
        Files.write(image.toPath(), new byte[]{1,2,3,4});
        MetadataRow a = new MetadataRow(ImageSource.series(image, 1, 3, "Section B"),
                "Treatment", "Mouse 1", "B");
        MetadataRow b = new MetadataRow(ImageSource.series(image, 2, 3, "Section C"),
                "Control", "Mouse 2", "C");
        b.included = false;
        c.allImagesTable = new MetadataTable(root, Arrays.asList(a, b));
        c.metadataTable = c.allImagesTable.includedTable();
        c.channelSettings.add(new FPBWizard.ChannelSetting(1, true, "Marker", ChannelColour.MAGENTA));
        c.channelSettings.add(new FPBWizard.ChannelSetting(0, false, "Nuclei", ChannelColour.BLUE));
        c.savedChannelRequests.add(new FPBRenderer.ChannelRequest(1, "Marker",
                ChannelColour.MAGENTA, new DisplayRange(9, 900)));
        c.savedZHandling = c.zHandling;
        String id = c.metadataTable.csvFileName(a);
        c.savedPicks.put("Treatment", new RowImage.SubjectRow("Treatment", "Mouse 1", "B", 0,
                false, null, id, ImageOrientation.IDENTITY));
        c.calibrationOverrides.put(id, new CalibrationOverride(0.25, 0.5));
        c.imageOrientations.put(id, ImageOrientation.IDENTITY);
        c.panelConfig = PanelConfig.builder().cellSizePx(317).outputDpi(600)
                .channelOrder(Arrays.asList("Marker")).customLabelTemplate("{subject}").build();
        c.exportSettings.setProperty("png", "false");
        c.exportSettings.setProperty("dpi", "450");
        c.exportSettings.setProperty("folder", new File(root, "exports").getAbsolutePath());
        return c;
    }

    @Test public void completeChoicesRoundTripAndRelinkByContent() throws Exception {
        File root = temp.newFolder("images");
        FPBWizard.Context c = example(root);
        File project = new File(temp.getRoot(), "figure.fpb.xml");
        Properties captured = FigureProjectIO.capture(c);
        FigureProjectIO.save(captured, project);
        File moved = new File(temp.getRoot(), "moved-images");
        Files.move(root.toPath(), moved.toPath());
        FPBWizard.Context restored = FigureProjectIO.load(project, moved);
        assertEquals(2, restored.allImagesTable.fileCount());
        assertEquals(1, restored.metadataTable.fileCount());
        assertEquals("Treatment", restored.metadataTable.rows().get(0).group);
        assertEquals(1, restored.metadataTable.rows().get(0).source.seriesIndex());
        assertEquals("Section B", restored.metadataTable.rows().get(0).source.seriesName());
        assertEquals(1, restored.channelSettings.get(0).sourceIndex);
        assertEquals(900, restored.savedChannelRequests.get(0).range().max());
        assertEquals(c.savedPicks.get("Treatment").imageId(), restored.savedPicks.get("Treatment").imageId());
        assertEquals(PanelConfigCodec.encode(c.panelConfig), PanelConfigCodec.encode(restored.panelConfig));
        assertEquals(0.25, restored.calibrationOverrides.values().iterator().next().pixelWidthUm(), 0);
        assertEquals("false", restored.exportSettings.getProperty("png"));
        assertEquals("450", restored.exportSettings.getProperty("dpi"));
        assertEquals(new File(moved, "exports").getCanonicalPath(),
                new File(restored.exportSettings.getProperty("folder")).getCanonicalPath());
        assertFalse(restored.projectRevision.isEmpty());
        c.imagePreloader.close(); restored.imagePreloader.close();
    }

    @Test public void sourceReplacementAndMissingSourcesAreRejected() throws Exception {
        File root = temp.newFolder("changed");
        FPBWizard.Context c = example(root);
        File project = new File(temp.getRoot(), "changed.xml");
        FigureProjectIO.save(FigureProjectIO.capture(c), project);
        Files.write(new File(root, "original.tif").toPath(), new byte[]{4,3,2,1});
        try { FigureProjectIO.load(project, null); fail("Changed pixels accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Source changed")); }
        Files.delete(new File(root, "original.tif").toPath());
        try { FigureProjectIO.load(project, null); fail("Missing source accepted"); }
        catch (IOException expected) { /* no substitution */ }
        c.imagePreloader.close();
    }

    @Test public void failedSavePreservesLastProjectAndCaptureFreezesEdits() throws Exception {
        File root = temp.newFolder("save");
        FPBWizard.Context c = example(root);
        File project = new File(temp.getRoot(), "saved.xml");
        Properties snapshot = FigureProjectIO.capture(c);
        c.channelSettings.get(0).name = "Later edit";
        FigureProjectIO.save(snapshot, project);
        FPBWizard.Context restored = FigureProjectIO.load(project, null);
        assertEquals("Marker", restored.channelSettings.get(0).name);
        byte[] previous = Files.readAllBytes(project.toPath());
        Files.delete(new File(root, "original.tif").toPath());
        try { FigureProjectIO.save(snapshot, project); fail("Missing source saved"); }
        catch (IOException expected) { assertArrayEquals(previous, Files.readAllBytes(project.toPath())); }
        c.imagePreloader.close(); restored.imagePreloader.close();
    }

    @Test public void futureSchemaIsRejected() throws Exception {
        File project = new File(temp.getRoot(), "future.xml");
        Properties p = new Properties(); p.setProperty("schema", "999");
        try (OutputStream out = Files.newOutputStream(project.toPath())) { p.storeToXML(out, ""); }
        try { FigureProjectIO.load(project, null); fail("Unknown schema accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("schema")); }
    }
}

