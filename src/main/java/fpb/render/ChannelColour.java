/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.render;

import java.util.Locale;

/** RGB mask used to colour an 8-bit grey plane. */
public final class ChannelColour {

    public static final ChannelColour RED = new ChannelColour("red", true, false, false);
    public static final ChannelColour GREEN = new ChannelColour("green", false, true, false);
    public static final ChannelColour BLUE = new ChannelColour("blue", false, false, true);
    public static final ChannelColour MAGENTA = new ChannelColour("magenta", true, false, true);
    public static final ChannelColour CYAN = new ChannelColour("cyan", false, true, true);
    public static final ChannelColour YELLOW = new ChannelColour("yellow", true, true, false);
    public static final ChannelColour GREY = new ChannelColour("grey", true, true, true);
    public static final ChannelColour WHITE = new ChannelColour("white", true, true, true);

    private final String name;
    private final boolean red;
    private final boolean green;
    private final boolean blue;

    public ChannelColour(String name, boolean red, boolean green, boolean blue) {
        if (!red && !green && !blue) {
            throw new IllegalArgumentException("at least one RGB component must be enabled");
        }
        this.name = name == null || name.trim().length() == 0 ? "custom" : name.trim();
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    public static ChannelColour fromName(String name) {
        if (name == null) return GREY;
        String key = name.trim().toLowerCase(Locale.ROOT);
        if ("red".equals(key)) return RED;
        if ("green".equals(key)) return GREEN;
        if ("blue".equals(key)) return BLUE;
        if ("magenta".equals(key)) return MAGENTA;
        if ("cyan".equals(key)) return CYAN;
        if ("yellow".equals(key)) return YELLOW;
        if ("gray".equals(key) || "grey".equals(key)) return GREY;
        if ("white".equals(key)) return WHITE;
        throw new IllegalArgumentException("Unknown channel colour: " + name);
    }

    public String name() {
        return name;
    }

    public int rgb(int grey) {
        int g = grey & 0xFF;
        return (red ? g << 16 : 0) | (green ? g << 8 : 0) | (blue ? g : 0);
    }

    boolean red() {
        return red;
    }

    boolean green() {
        return green;
    }

    boolean blue() {
        return blue;
    }

    @Override
    public String toString() {
        return name;
    }
}
