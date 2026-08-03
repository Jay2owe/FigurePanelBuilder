/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.render;

import fpb.io.HistogramCache;
import fpb.io.PlaneCache;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Synchronous render entry point for cached planes and locked display ranges. */
public final class FPBRenderer {

    public PanelRender renderPanel(PlaneCache planes, HistogramCache histograms,
            int imageIndex, List<ChannelRequest> channels, int width, int height) {
        if (planes == null) throw new IllegalArgumentException("planes must not be null");
        if (histograms == null) throw new IllegalArgumentException("histograms must not be null");
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("channels must not be empty");
        }
        if (planes.imageCount() != histograms.imageCount()) {
            throw new IllegalArgumentException("plane and histogram image counts differ");
        }
        requirePositive("width", width);
        requirePositive("height", height);

        PlaneCache.ImagePlanes image = planes.image(imageIndex);
        String imageName = image.sourceFile().getName();
        List<BufferedImage> channelImages =
                new ArrayList<BufferedImage>(channels.size());
        List<FastRaster.GreyPlane> greyPlanes =
                new ArrayList<FastRaster.GreyPlane>(channels.size());
        List<ChannelColour> colours = new ArrayList<ChannelColour>(channels.size());
        List<ClipReport.ChannelClip> clips =
                new ArrayList<ClipReport.ChannelClip>(channels.size());

        for (int i = 0; i < channels.size(); i++) {
            ChannelRequest request = channels.get(i);
            if (request == null) throw new IllegalArgumentException("channel request is null");
            DisplayRange range = DisplayRange.requireValid(request.range(),
                    request.name(), imageName);
            PlaneCache.Plane plane = image.plane(request.channelIndex());
            FastRaster.GreyPlane grey = FastRaster.greyPlane(plane.pixelsUnsafe(),
                    plane.width(), plane.height(), range, width, height);
            greyPlanes.add(grey);
            ChannelColour colour = request.colour();
            colours.add(colour);
            channelImages.add(FastRaster.colourize(grey, colour));
            clips.add(ClipReport.fromHistogram(request.channelIndex(), request.name(),
                    histograms.histogram(imageIndex, request.channelIndex()), range));
        }

        return new PanelRender(image.sourceFile(), imageIndex, channelImages,
                FastRaster.merge(greyPlanes, colours), new ClipReport(clips));
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    public static final class ChannelRequest {
        private final int channelIndex;
        private final String name;
        private final ChannelColour colour;
        private final DisplayRange range;

        public ChannelRequest(int channelIndex, String name, ChannelColour colour,
                DisplayRange range) {
            if (channelIndex < 0) throw new IllegalArgumentException("channelIndex is negative");
            this.channelIndex = channelIndex;
            this.name = name == null || name.length() == 0
                    ? "channel " + (channelIndex + 1) : name;
            this.colour = colour == null ? ChannelColour.GREY : colour;
            this.range = range;
        }

        public int channelIndex() {
            return channelIndex;
        }

        public String name() {
            return name;
        }

        public ChannelColour colour() {
            return colour;
        }

        public DisplayRange range() {
            return range;
        }
    }

    public static final class PanelRender {
        private final File sourceFile;
        private final int imageIndex;
        private final List<BufferedImage> channelImages;
        private final BufferedImage mergeImage;
        private final ClipReport clipReport;

        private PanelRender(File sourceFile, int imageIndex,
                List<BufferedImage> channelImages, BufferedImage mergeImage,
                ClipReport clipReport) {
            this.sourceFile = sourceFile;
            this.imageIndex = imageIndex;
            this.channelImages = Collections.unmodifiableList(
                    new ArrayList<BufferedImage>(channelImages));
            this.mergeImage = mergeImage;
            this.clipReport = clipReport;
        }

        public File sourceFile() {
            return sourceFile;
        }

        public int imageIndex() {
            return imageIndex;
        }

        public List<BufferedImage> channelImages() {
            return channelImages;
        }

        public BufferedImage mergeImage() {
            return mergeImage;
        }

        public ClipReport clipReport() {
            return clipReport;
        }
    }
}
