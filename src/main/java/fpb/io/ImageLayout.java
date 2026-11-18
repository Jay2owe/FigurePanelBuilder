/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.io;

import ij.io.FileInfo;
import ij.io.TiffDecoder;

import java.io.File;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How one image file stores its channels, read from the file header where
 * possible. It separates real channel data from an 8-bit RGB colour picture
 * (for example an Echo Revolution "_Merge.tif" or an ImageJ "_RGB.tif"),
 * whose original channels were blended into red, green and blue when it was
 * saved and cannot be recovered.
 */
public final class ImageLayout {

    /** Channel count is unknown without opening the image. */
    public static final int UNKNOWN = -1;

    private static final Pattern IMAGEJ_CHANNELS =
            Pattern.compile("(?m)^channels=(\\d+)\\s*$");

    private final int channels;
    private final int zPlanes;
    private final int timePoints;
    private final boolean rgbOverlay;

    public ImageLayout(int channels, int zPlanes, int timePoints,
            boolean rgbOverlay) {
        this.channels = channels;
        this.zPlanes = Math.max(1, zPlanes);
        this.timePoints = Math.max(1, timePoints);
        this.rgbOverlay = rgbOverlay;
    }

    public static ImageLayout unknown() {
        return new ImageLayout(UNKNOWN, 1, 1, false);
    }

    public int channels() {
        return channels;
    }

    public int zPlanes() {
        return zPlanes;
    }

    public int timePoints() {
        return timePoints;
    }

    /** True for an 8-bit red/green/blue colour picture rather than channel data. */
    public boolean rgbOverlay() {
        return rgbOverlay;
    }

    /**
     * True when a single-channel image holds several planes with no channel
     * axis, which is how channels look when the dimensions were lost on save.
     */
    public boolean planesWithoutChannelAxis() {
        return channels == 1 && (zPlanes > 1 || timePoints > 1);
    }

    /**
     * True for names the Echo Revolution ({@code _Merge.tif}) and ImageJ
     * ({@code _RGB.tif}) give their flattened colour copies; {@code _merged}
     * composites are channel data and do not match.
     */
    public static boolean namedLikeRgbCopy(File file) {
        return file != null && file.getName().toLowerCase(java.util.Locale.ROOT)
                .matches(".*[_ -](rgb|merge)\\.tiff?");
    }

    /** Reads the layout from a TIFF header without decoding pixels. */
    public static ImageLayout probe(ImageSource source) {
        if (source == null || source.isSeries()) return unknown();
        File file = source.file();
        if (!isTiff(file)) return unknown();
        // Reading an online-only file waits for a download that can take
        // minutes; callers fall back to namedLikeRgbCopy for those.
        if (ImageLoader.isOfflinePlaceholder(file)) return unknown();
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            FileInfo[] info = new TiffDecoder(parent.getAbsolutePath()
                    + File.separator, file.getName()).getTiffInfo();
            if (info == null || info.length == 0) return fromFirstIfd(file);
            return fromTiffInfo(info);
        } catch (Exception unreadable) {
            // ImageJ refuses tiled TIFFs (the Echo Revolution writes them);
            // the first directory's tags still tell an RGB picture apart.
            return fromFirstIfd(file);
        }
    }

    /** Reads SamplesPerPixel, Photometric and BitsPerSample from the first IFD. */
    static ImageLayout fromFirstIfd(File file) {
        try {
            java.io.RandomAccessFile in = new java.io.RandomAccessFile(file, "r");
            try {
                byte[] header = new byte[16];
                in.readFully(header, 0, 8);
                boolean little = header[0] == 'I' && header[1] == 'I';
                if (!little && !(header[0] == 'M' && header[1] == 'M')) return unknown();
                int magic = (int) value(header, 2, 2, little);
                boolean big = magic == 43;
                if (magic != 42 && !big) return unknown();
                long ifd;
                if (big) {
                    in.readFully(header, 8, 8);
                    ifd = value(header, 8, 8, little);
                } else {
                    ifd = value(header, 4, 4, little);
                }
                in.seek(ifd);
                byte[] countBytes = new byte[big ? 8 : 2];
                in.readFully(countBytes);
                long count = value(countBytes, 0, countBytes.length, little);
                if (count <= 0 || count > 4096) return unknown();
                int entrySize = big ? 20 : 12;
                byte[] entries = new byte[(int) count * entrySize];
                in.readFully(entries);
                int samples = 1;
                int photometric = -1;
                int bits = -1;
                for (int i = 0; i < count; i++) {
                    int at = i * entrySize;
                    int tag = (int) value(entries, at, 2, little);
                    int type = (int) value(entries, at + 2, 2, little);
                    long values = value(entries, at + 4, big ? 8 : 4, little);
                    int valueAt = at + (big ? 12 : 8);
                    int width = type == 3 ? 2 : type == 4 ? 4 : 1;
                    long first;
                    if ((long) width * values <= (big ? 8 : 4)) {
                        first = value(entries, valueAt, width, little);
                    } else if (tag == 258) {
                        long offset = value(entries, valueAt, big ? 8 : 4, little);
                        byte[] bitsBytes = new byte[width];
                        long here = in.getFilePointer();
                        in.seek(offset);
                        in.readFully(bitsBytes);
                        in.seek(here);
                        first = value(bitsBytes, 0, width, little);
                    } else {
                        continue;
                    }
                    if (tag == 277) samples = (int) first;
                    else if (tag == 262) photometric = (int) first;
                    else if (tag == 258) bits = (int) first;
                }
                if (photometric == 2 && bits == 8 && samples >= 3) {
                    return new ImageLayout(3, 1, 1, true);
                }
                if (photometric == 2 && samples >= 3) {
                    return new ImageLayout(3, 1, 1, false);
                }
                return unknown();
            } finally {
                in.close();
            }
        } catch (Exception unreadable) {
            return unknown();
        }
    }

    private static long value(byte[] bytes, int offset, int length, boolean little) {
        long result = 0L;
        for (int i = 0; i < length; i++) {
            int b = bytes[offset + (little ? length - 1 - i : i)] & 0xFF;
            result = (result << 8) | b;
        }
        return result;
    }

    static ImageLayout fromTiffInfo(FileInfo[] info) {
        FileInfo first = info[0];
        if (isEightBitColour(first.fileType)) {
            return new ImageLayout(3, 1, 1, true);
        }
        String description = first.description == null ? "" : first.description;
        if (description.startsWith("ImageJ")) {
            int channels = intValue(description, "channels", 1);
            int slices = intValue(description, "slices", 1);
            int frames = intValue(description, "frames", 1);
            int images = intValue(description, "images", first.nImages);
            if (channels == 1 && slices == 1 && frames == 1 && images > 1) {
                slices = images;
            }
            return new ImageLayout(channels, slices, frames, false);
        }
        if (first.fileType == FileInfo.RGB48
                || first.fileType == FileInfo.RGB48_PLANAR) {
            return new ImageLayout(3, 1, 1, false);
        }
        return unknown();
    }

    private static boolean isEightBitColour(int fileType) {
        return fileType == FileInfo.RGB || fileType == FileInfo.BGR
                || fileType == FileInfo.ARGB || fileType == FileInfo.RGB_PLANAR
                || fileType == FileInfo.BARG || fileType == FileInfo.ABGR
                || fileType == FileInfo.CMYK;
    }

    private static int intValue(String description, String key, int fallback) {
        Matcher matcher = (key.equals("channels") ? IMAGEJ_CHANNELS
                : Pattern.compile("(?m)^" + key + "=(\\d+)\\s*$"))
                .matcher(description);
        if (!matcher.find()) return fallback;
        try {
            return Math.max(1, Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    static boolean isTiff(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".tif") || name.endsWith(".tiff");
    }

    /** Picture formats whose three 8-bit channels are always red, green and blue. */
    static boolean isPictureFormat(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".gif")
                || name.endsWith(".bmp");
    }

    @Override
    public String toString() {
        return "ImageLayout[channels=" + channels + ", z=" + zPlanes + ", t="
                + timePoints + ", rgbOverlay=" + rgbOverlay + "]";
    }
}
