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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Per-channel clipped-pixel percentages computed from cumulative histograms. */
public final class ClipReport {

    private final List<ChannelClip> channels;

    public ClipReport(List<ChannelClip> channels) {
        if (channels == null) throw new IllegalArgumentException("channels must not be null");
        this.channels = Collections.unmodifiableList(new ArrayList<ChannelClip>(channels));
    }

    static ChannelClip fromHistogram(int channelIndex, String channelName,
            HistogramCache.Histogram histogram, DisplayRange range) {
        if (histogram == null) throw new IllegalArgumentException("histogram must not be null");
        return new ChannelClip(channelIndex, channelName,
                histogram.clippedLowPercent(range.min()),
                histogram.clippedHighPercent(range.max()));
    }

    public List<ChannelClip> channels() {
        return channels;
    }

    public ChannelClip channel(int index) {
        return channels.get(index);
    }

    public static final class ChannelClip {
        private final int channelIndex;
        private final String channelName;
        private final double lowPercent;
        private final double highPercent;

        public ChannelClip(int channelIndex, String channelName,
                double lowPercent, double highPercent) {
            if (channelIndex < 0) throw new IllegalArgumentException("channelIndex is negative");
            this.channelIndex = channelIndex;
            this.channelName = channelName == null ? "" : channelName;
            this.lowPercent = lowPercent;
            this.highPercent = highPercent;
        }

        public int channelIndex() {
            return channelIndex;
        }

        public String channelName() {
            return channelName;
        }

        public double lowPercent() {
            return lowPercent;
        }

        public double highPercent() {
            return highPercent;
        }
    }
}
