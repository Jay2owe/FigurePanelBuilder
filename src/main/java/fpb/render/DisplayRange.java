/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.render;

/** Immutable locked display min/max for a single channel. */
public final class DisplayRange {

    public static final int MIN_VALUE = 0;
    public static final int MAX_VALUE = 65535;

    private final int min;
    private final int max;

    public DisplayRange(int min, int max) {
        this.min = min;
        this.max = max;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    public boolean isValid() {
        return min >= MIN_VALUE && min <= MAX_VALUE
                && max >= MIN_VALUE && max <= MAX_VALUE
                && max > min;
    }

    static DisplayRange requireValid(DisplayRange range, String channelName, String imageName) {
        if (range == null || !range.isValid()) {
            throw new IllegalStateException("No display range locked for channel '"
                    + safe(channelName) + "' (image: " + safe(imageName) + "). "
                    + "Figure Panel Builder never applies automatic per-image contrast.");
        }
        return range;
    }

    private static String safe(String value) {
        return value == null || value.length() == 0 ? "unnamed" : value;
    }

    @Override
    public String toString() {
        return min + "-" + max;
    }
}
