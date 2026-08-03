/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.svg;

/** XML escaping for SVG text nodes and attributes. */
public final class SvgEscape {

    private SvgEscape() {}

    public static String text(String value) {
        String s = value == null ? "" : value;
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public static String attr(String value) {
        return text(value).replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
