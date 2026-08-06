/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.io;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import fpb.util.CancellationCheck;

import java.io.File;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Opens image folders and builds raw preview-plane and histogram caches. */
public final class ImageLoader {

    public static final int MAX_IMAGES = 100;
    private static final int WINDOWS_FILE_ATTRIBUTE_OFFLINE = 0x1000;

    /** Cooperative cancellation hook for full-resolution image decoding. */
    public interface CancelCheck extends CancellationCheck {}

    public static final CancelCheck NEVER_CANCELLED = new CancelCheck() {
        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    /** Distinguishes a requested cancellation from an image decoding failure. */
    public static final class LoadCancelledException extends IOException {
        private static final long serialVersionUID = 1L;

        LoadCancelledException() {
            super("Image loading cancelled.");
        }
    }

    /** A cloud placeholder was found, but Windows could not provide its data. */
    public static final class SourceUnavailableException extends IOException {
        private static final long serialVersionUID = 1L;

        SourceUnavailableException(File file, IOException cause) {
            super("This image is stored online-only. Figure Panel Builder tried "
                    + "to request a local copy from Windows and the cloud-storage "
                    + "provider, but the file is still unavailable. In File Explorer, "
                    + "right-click it and choose Make available offline, then click "
                    + "Retry. You can also choose another image folder. File: "
                    + file.getAbsolutePath(), cause);
        }
    }

    private static final List<String> IMAGE_EXTENSIONS = Collections.unmodifiableList(
            Arrays.asList("tif", "tiff", "png", "jpg", "jpeg", "gif", "bmp",
                    "lif", "czi", "nd2", "oib", "oif", "lsm"));

    private final int previewLongEdge;
    private final int threadCount;

    /** Z-plane policy shared by preview, export, macro and headless routes. */
    public enum ZMode {
        MAX,
        FIRST;

        public static ZMode fromString(String value) {
            String clean = value == null ? "" : value.trim()
                    .toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            if (clean.length() == 0 || "max".equals(clean)
                    || "maximum".equals(clean)
                    || "maximum_projection".equals(clean)) return MAX;
            if ("first".equals(clean) || "first_slice".equals(clean)) return FIRST;
            throw new IllegalArgumentException("Unknown z_mode: " + value
                    + ". Expected max or first.");
        }

        public String optionName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public ImageLoader() {
        this(Binner.DEFAULT_LONG_EDGE, Runtime.getRuntime().availableProcessors());
    }

    public ImageLoader(int previewLongEdge, int threadCount) {
        if (previewLongEdge <= 0) {
            throw new IllegalArgumentException("previewLongEdge must be positive");
        }
        if (threadCount <= 0) throw new IllegalArgumentException("threadCount must be positive");
        this.previewLongEdge = previewLongEdge;
        this.threadCount = threadCount;
    }

    public LoadResult loadFolder(File folder, boolean recursive,
            ProgressCallback callback) throws IOException {
        return loadFolder(folder, recursive, ZMode.MAX, callback);
    }

    public LoadResult loadFolder(File folder, boolean recursive, ZMode zMode,
            ProgressCallback callback) throws IOException {
        return loadSources(discoverImageSources(folder, recursive), zMode, callback);
    }

    /** Discovers supported inputs while excluding FPB's reserved output directories. */
    public static List<File> discoverImageFiles(File folder, boolean recursive)
            throws IOException {
        if (folder == null) throw new IOException("folder is null");
        if (!folder.isDirectory()) throw new IOException("folder is not a directory: " + folder);
        List<File> files = new ArrayList<File>();
        collectImages(folder.getAbsoluteFile(), recursive, files);
        return Collections.unmodifiableList(normalizedSortedFiles(files));
    }

    /**
     * Discovers logical inputs. Each LIF series becomes one source while other
     * formats retain the existing one-file/one-image behaviour.
     */
    public static List<ImageSource> discoverImageSources(File folder,
            boolean recursive) throws IOException {
        List<File> files = discoverImageFiles(folder, recursive);
        List<ImageSource> sources = new ArrayList<ImageSource>();
        for (File file : files) {
            if (isLif(file)) sources.addAll(probeLifSeries(file));
            else sources.add(ImageSource.file(file));
        }
        return Collections.unmodifiableList(normalizedSortedSources(sources));
    }

    public LoadResult loadFiles(List<File> files, ProgressCallback callback) throws IOException {
        return loadFiles(files, ZMode.MAX, callback);
    }

    public LoadResult loadFiles(List<File> files, ZMode zMode,
            ProgressCallback callback) throws IOException {
        if (files == null) throw new IOException("image file list is null");
        List<ImageSource> sources = new ArrayList<ImageSource>();
        for (File file : normalizedSortedFiles(files)) {
            if (isLif(file)) sources.addAll(probeLifSeries(file));
            else sources.add(ImageSource.file(file));
        }
        return loadSources(sources, zMode, callback);
    }

    public LoadResult loadSources(List<ImageSource> sources, ZMode zMode,
            ProgressCallback callback) throws IOException {
        if (sources == null) throw new IOException("image source list is null");
        if (sources.size() > MAX_IMAGES) {
            throw new IOException("Figure Panel Builder v0.1.0 handles up to 100 images per run; "
                    + "this folder has " + sources.size() + ".");
        }
        ProgressCallback progress = callback == null ? ProgressCallback.NONE : callback;
        List<ImageSource> ordered = normalizedSortedSources(sources);
        if (ordered.isEmpty()) {
            throw new IOException("No supported image files were found.");
        }

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(threadCount, ordered.size()));
        CompletionService<IndexedLoad> completion =
                new ExecutorCompletionService<IndexedLoad>(executor);
        try {
            for (int i = 0; i < ordered.size(); i++) {
                completion.submit(new LoadTask(i, ordered.get(i), previewLongEdge,
                        zMode == null ? ZMode.MAX : zMode));
            }
            IndexedLoad[] loaded = new IndexedLoad[ordered.size()];
            int completed = 0;
            for (int i = 0; i < ordered.size(); i++) {
                Future<IndexedLoad> future = completion.take();
                IndexedLoad item = future.get();
                loaded[item.index] = item;
                completed++;
                progress.onProgress(completed, ordered.size(), item.result.sourceFile);
            }
            return assembleResult(loaded);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while loading images", interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("Failed to load images", cause);
        } finally {
            executor.shutdownNow();
        }
    }

    public LoadedImage loadImage(File file) throws IOException {
        return loadImage(file, ZMode.MAX);
    }

    public LoadedImage loadImage(ImageSource source) throws IOException {
        return loadImage(source, ZMode.MAX);
    }

    public LoadedImage loadImage(File file, ZMode zMode) throws IOException {
        return loadImage(ImageSource.file(file), zMode);
    }

    public LoadedImage loadImage(ImageSource source, ZMode zMode) throws IOException {
        return loadOne(source, previewLongEdge, zMode == null ? ZMode.MAX : zMode,
                NEVER_CANCELLED);
    }

    /** Re-opens one source and retains its complete projected planes for publication export. */
    public LoadResult loadFullResolution(File file, ZMode zMode) throws IOException {
        return loadFullResolution(file, zMode, NEVER_CANCELLED);
    }

    public LoadResult loadFullResolution(ImageSource source, ZMode zMode)
            throws IOException {
        return loadFullResolution(source, zMode, NEVER_CANCELLED);
    }

    /** Re-opens one source while polling cancellation between decode/projection stages. */
    public LoadResult loadFullResolution(File file, ZMode zMode,
            CancelCheck cancelCheck) throws IOException {
        return loadFullResolution(ImageSource.file(file), zMode, cancelCheck);
    }

    public LoadResult loadFullResolution(ImageSource source, ZMode zMode,
            CancelCheck cancelCheck) throws IOException {
        LoadedImage loaded = loadOne(source, Integer.MAX_VALUE,
                zMode == null ? ZMode.MAX : zMode, cancelCheck);
        return assembleResult(new IndexedLoad[] { new IndexedLoad(0, loaded) });
    }

    /**
     * Returns true when Windows marks a source as an online-only cloud placeholder.
     * Other platforms and file systems simply return false.
     */
    public static boolean isOfflinePlaceholder(File file) {
        if (file == null) return false;
        try {
            Object value = Files.getAttribute(file.toPath(), "dos:attributes",
                    new LinkOption[] { LinkOption.NOFOLLOW_LINKS });
            return value instanceof Number
                    && ((((Number) value).intValue()
                    & WINDOWS_FILE_ATTRIBUTE_OFFLINE) != 0);
        } catch (IOException ignored) {
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Requests data for an online-only source before giving it to ImageJ or
     * Bio-Formats. Reading one byte uses the normal Windows cloud-file recall
     * path without retaining the image in memory.
     */
    public static void ensureLocallyAvailable(File file) throws IOException {
        if (file == null) throw new IOException("image file is null");
        if (!file.exists() || !file.isFile()) {
            throw new IOException("image file does not exist: " + file);
        }
        if (!isOfflinePlaceholder(file)) return;
        try {
            InputStream input = new BufferedInputStream(new FileInputStream(file));
            try {
                if (file.length() > 0L && input.read() < 0) {
                    throw new IOException("cloud placeholder returned no image data");
                }
            } finally {
                input.close();
            }
        } catch (IOException failure) {
            throw new SourceUnavailableException(file, failure);
        }
    }

    private static LoadResult assembleResult(IndexedLoad[] loaded) throws IOException {
        List<PlaneCache.ImagePlanes> planeImages =
                new ArrayList<PlaneCache.ImagePlanes>(loaded.length);
        List<HistogramCache.ImageHistograms> histogramImages =
                new ArrayList<HistogramCache.ImageHistograms>(loaded.length);
        int channelCount = -1;
        List<int[]> pooledCounts = null;

        for (int i = 0; i < loaded.length; i++) {
            LoadedImage image = loaded[i].result;
            if (channelCount < 0) {
                channelCount = image.channelCount();
                pooledCounts = new ArrayList<int[]>(channelCount);
                for (int c = 0; c < channelCount; c++) {
                    pooledCounts.add(new int[HistogramCache.BIN_COUNT]);
                }
            } else if (image.channelCount() != channelCount) {
                throw new IOException("Image " + image.sourceFile.getAbsolutePath()
                        + " has " + image.channelCount() + " channels; expected "
                        + channelCount + ".");
            }
            planeImages.add(image.toPlaneCacheImage());
            histogramImages.add(image.toHistogramCacheImage());
            for (int c = 0; c < channelCount; c++) {
                addCounts(pooledCounts.get(c), image.histogramCounts.get(c));
            }
        }

        List<HistogramCache.Histogram> pooled =
                new ArrayList<HistogramCache.Histogram>(channelCount);
        for (int c = 0; c < channelCount; c++) {
            pooled.add(HistogramCache.cumulativeFromCounts(pooledCounts.get(c)));
        }
        return new LoadResult(new PlaneCache(planeImages),
                new HistogramCache(histogramImages, pooled));
    }

    private static void addCounts(int[] target, int[] source) throws IOException {
        for (int i = 0; i < target.length; i++) {
            long sum = (long) target[i] + source[i];
            if (sum > Integer.MAX_VALUE) {
                throw new IOException("pooled histogram exceeds integer count range");
            }
            target[i] = (int) sum;
        }
    }

    private static LoadedImage loadOne(ImageSource source, int previewLongEdge, ZMode zMode,
            CancelCheck cancelCheck) throws IOException {
        checkCancelled(cancelCheck);
        File file = source.file();
        OpenedImage opened = openImage(source);
        ImagePlus image = opened.image;
        if (image == null) {
            throw new IOException("Could not open image: " + file.getAbsolutePath());
        }
        try {
            // Opening through ImageJ/Bio-Formats is an indivisible third-party call;
            // cancellation becomes cooperative again immediately after it returns.
            checkCancelled(cancelCheck);
            int bitDepth = image.getBitDepth();
            if (bitDepth != 8 && bitDepth != 16) {
                throw new IOException("Unsupported bit depth " + bitDepth + " for "
                        + file.getAbsolutePath() + ". Stage 02 supports 8-bit and 16-bit grayscale images.");
            }
            int channelCount = Math.max(1, image.getNChannels());
            int sliceCount = Math.max(1, image.getNSlices());
            int frame = Math.max(1, image.getT());
            if (frame > Math.max(1, image.getNFrames())) frame = 1;
            ImageStack stack = image.getImageStack();
            List<PlaneCache.Plane> binnedPlanes =
                    new ArrayList<PlaneCache.Plane>(channelCount);
            List<HistogramCache.Histogram> histograms =
                    new ArrayList<HistogramCache.Histogram>(channelCount);
            List<int[]> histogramCounts = new ArrayList<int[]>(channelCount);

            for (int channel = 1; channel <= channelCount; channel++) {
                checkCancelled(cancelCheck);
                short[] projected = projectChannel(image, stack, channel, sliceCount,
                        frame, bitDepth, zMode, cancelCheck);
                int[] binnedSize = Binner.scaledDimensions(
                        image.getWidth(), image.getHeight(), previewLongEdge);
                short[] binned = Binner.maxBin(projected, image.getWidth(), image.getHeight(),
                        binnedSize[0], binnedSize[1], cancelCheck);
                binnedPlanes.add(new PlaneCache.Plane(binnedSize[0], binnedSize[1], binned));
                int[] counts = HistogramCache.countsFromPlane(projected, cancelCheck);
                histogramCounts.add(counts);
                histograms.add(HistogramCache.cumulativeFromCounts(counts));
            }

            return new LoadedImage(source, bitDepth, image.getWidth(), image.getHeight(),
                    image.getCalibration(), opened.bioFormats, binnedPlanes,
                    histograms, histogramCounts);
        } finally {
            image.changes = false;
            image.close();
            image.flush();
        }
    }

    private static short[] projectChannel(ImagePlus image, ImageStack stack,
            int channel, int sliceCount, int frame, int bitDepth, ZMode zMode,
            CancelCheck cancelCheck) throws IOException {
        checkCancelled(cancelCheck);
        ZMode mode = zMode == null ? ZMode.MAX : zMode;
        if (mode == ZMode.FIRST) {
            int slice = 1;
            int stackIndex = image.getStackIndex(channel, slice, frame);
            return copyPixelsAsUnsignedShorts(stack.getProcessor(stackIndex),
                    bitDepth, stackIndex, cancelCheck);
        }
        short[] projected = null;
        int expectedLength = image.getWidth() * image.getHeight();
        for (int slice = 1; slice <= sliceCount; slice++) {
            checkCancelled(cancelCheck);
            int stackIndex = image.getStackIndex(channel, slice, frame);
            ImageProcessor processor = stack.getProcessor(stackIndex);
            short[] plane = copyPixelsAsUnsignedShorts(processor, bitDepth, stackIndex,
                    cancelCheck);
            if (plane.length != expectedLength) {
                throw new IOException("Plane " + stackIndex + " has " + plane.length
                        + " pixels; expected " + expectedLength + ".");
            }
            if (projected == null) {
                projected = plane;
            } else {
                for (int i = 0; i < projected.length; i++) {
                    if ((i & 0x3fff) == 0) checkCancelled(cancelCheck);
                    int value = plane[i] & 0xFFFF;
                    if (value > (projected[i] & 0xFFFF)) projected[i] = plane[i];
                }
            }
        }
        if (projected == null) {
            throw new IOException("Image has no planes to project: " + image.getTitle());
        }
        return projected;
    }

    static short[] copyPixelsAsUnsignedShorts(ImageProcessor processor,
            int bitDepth, int stackIndex) throws IOException {
        return copyPixelsAsUnsignedShorts(processor, bitDepth, stackIndex,
                NEVER_CANCELLED);
    }

    private static short[] copyPixelsAsUnsignedShorts(ImageProcessor processor,
            int bitDepth, int stackIndex, CancelCheck cancelCheck) throws IOException {
        checkCancelled(cancelCheck);
        if (processor == null) {
            throw new IOException("Missing processor at stack index " + stackIndex);
        }
        Object pixels = processor.getPixels();
        if (bitDepth == 8 && pixels instanceof byte[]) {
            byte[] live = (byte[]) pixels;
            byte[] safe = live.clone();
            short[] out = new short[safe.length];
            for (int i = 0; i < safe.length; i++) {
                if ((i & 0x3fff) == 0) checkCancelled(cancelCheck);
                out[i] = (short) (safe[i] & 0xFF);
            }
            return out;
        }
        if (bitDepth == 16 && pixels instanceof short[]) {
            short[] live = (short[]) pixels;
            short[] copy = live.clone();
            checkCancelled(cancelCheck);
            return copy;
        }
        throw new IOException("Unsupported pixel storage at stack index " + stackIndex
                + ": bit depth " + bitDepth + ", pixels "
                + (pixels == null ? "null" : pixels.getClass().getName()));
    }

    private static OpenedImage openImage(ImageSource source) throws IOException {
        if (source == null) throw new IOException("image source is null");
        File file = source.file();
        if (file == null) throw new IOException("image file is null");
        if (!file.isFile()) throw new IOException("image file does not exist: " + file);
        ensureLocallyAvailable(file);
        ImagePlus bioFormats = openWithBioFormatsIfAvailable(source);
        if (bioFormats != null) return new OpenedImage(bioFormats, true);
        if (source.isSeries()) {
            throw new IOException("Bio-Formats is required to open LIF series "
                    + (source.seriesIndex() + 1) + " from " + file.getAbsolutePath());
        }
        return new OpenedImage(IJ.openImage(file.getAbsolutePath()), false);
    }

    private static ImagePlus openWithBioFormatsIfAvailable(ImageSource source)
            throws IOException {
        File file = source.file();
        try {
            Class<?> optionsClass = Class.forName("loci.plugins.in.ImporterOptions");
            Object options = optionsClass.newInstance();
            invokeIfPresent(optionsClass, options, "setId",
                    new Class<?>[] { String.class },
                    new Object[] { file.getAbsolutePath() });
            invokeIfPresent(optionsClass, options, "setQuiet",
                    new Class<?>[] { boolean.class }, new Object[] { Boolean.TRUE });
            invokeIfPresent(optionsClass, options, "setWindowless",
                    new Class<?>[] { boolean.class }, new Object[] { Boolean.TRUE });
            invokeIfPresent(optionsClass, options, "setVirtual",
                    new Class<?>[] { boolean.class }, new Object[] { Boolean.FALSE });
            if (source.isSeries()) {
                invokeIfPresent(optionsClass, options, "setOpenAllSeries",
                        new Class<?>[] { boolean.class }, new Object[] { Boolean.FALSE });
                Method setSeriesOn = optionsClass.getMethod("setSeriesOn",
                        int.class, boolean.class);
                for (int i = 0; i < source.seriesCount(); i++) {
                    setSeriesOn.invoke(options, Integer.valueOf(i),
                            Boolean.valueOf(i == source.seriesIndex()));
                }
            }
            Class<?> bfClass = Class.forName("loci.plugins.BF");
            Method openImagePlus = bfClass.getMethod("openImagePlus", optionsClass);
            Object value = openImagePlus.invoke(null, options);
            if (value instanceof ImagePlus[]) {
                ImagePlus[] images = (ImagePlus[]) value;
                ImagePlus selected = null;
                for (int i = 0; i < images.length; i++) {
                    ImagePlus candidate = images[i];
                    if (candidate == null) continue;
                    if (selected == null) {
                        selected = candidate;
                    } else {
                        candidate.changes = false;
                        candidate.close();
                        candidate.flush();
                    }
                }
                return selected;
            }
            return null;
        } catch (ClassNotFoundException noBioFormats) {
            return null;
        } catch (ReflectiveOperationException reflectionFailure) {
            throw bioFormatsFailure("Bio-Formats failed to open ", file,
                    reflectionFailure);
        } catch (RuntimeException runtimeFailure) {
            throw bioFormatsFailure("Bio-Formats failed to open ", file,
                    runtimeFailure);
        }
    }

    private static void invokeIfPresent(Class<?> targetClass, Object target, String name,
            Class<?>[] types, Object[] args) throws ReflectiveOperationException {
        try {
            Method method = targetClass.getMethod(name, types);
            method.invoke(target, args);
        } catch (NoSuchMethodException absent) {
            // Optional across Bio-Formats versions.
        }
    }

    private static List<ImageSource> probeLifSeries(File file) throws IOException {
        Object reader = null;
        ensureLocallyAvailable(file);
        try {
            Class<?> metadataTools = Class.forName("loci.formats.MetadataTools");
            Object metadata = metadataTools.getMethod("createOMEXMLMetadata")
                    .invoke(null);
            Class<?> readerClass = Class.forName("loci.formats.ImageReader");
            reader = readerClass.newInstance();
            Class<?> metadataStoreClass = Class.forName(
                    "loci.formats.meta.MetadataStore");
            readerClass.getMethod("setMetadataStore", metadataStoreClass)
                    .invoke(reader, metadata);
            readerClass.getMethod("setId", String.class)
                    .invoke(reader, file.getAbsolutePath());
            int count = ((Number) readerClass.getMethod("getSeriesCount")
                    .invoke(reader)).intValue();
            if (count <= 0) {
                throw new IOException("No Bio-Formats series were found in "
                        + file.getAbsolutePath());
            }
            Method getImageName = metadata.getClass().getMethod("getImageName",
                    int.class);
            List<ImageSource> sources = new ArrayList<ImageSource>(count);
            for (int i = 0; i < count; i++) {
                Object value = getImageName.invoke(metadata, Integer.valueOf(i));
                String name = value == null ? "" : value.toString().trim();
                sources.add(ImageSource.series(file, i, count, name));
            }
            return sources;
        } catch (ClassNotFoundException noBioFormats) {
            throw new IOException("Bio-Formats is required to enumerate LIF series in "
                    + file.getAbsolutePath(), noBioFormats);
        } catch (ReflectiveOperationException failure) {
            throw bioFormatsFailure("Bio-Formats could not read the series in ",
                    file, failure);
        } catch (RuntimeException failure) {
            throw bioFormatsFailure("Bio-Formats could not read the series in ",
                    file, failure);
        } finally {
            if (reader != null) {
                try {
                    reader.getClass().getMethod("close").invoke(reader);
                } catch (Exception ignored) {
                    // Preserve the primary metadata result/failure.
                }
            }
        }
    }

    private static IOException bioFormatsFailure(String prefix, File file,
            Throwable failure) {
        String detail = deepestMessage(failure);
        StringBuilder message = new StringBuilder(prefix)
                .append(file.getAbsolutePath());
        if (detail.length() > 0) message.append(": ").append(detail);
        if (isOfflinePlaceholder(file)) {
            message.append(". This file is still marked online-only. Figure Panel "
                    + "Builder requested its data before opening it; if the cloud "
                    + "provider has not finished downloading, make the file "
                    + "available offline and click Retry");
        }
        return new IOException(message.toString(), failure);
    }

    private static String deepestMessage(Throwable failure) {
        Throwable current = failure;
        String message = "";
        int depth = 0;
        while (current != null && depth < 12) {
            if (current.getMessage() != null
                    && current.getMessage().trim().length() > 0) {
                message = current.getMessage().trim();
            }
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
            depth++;
        }
        return message;
    }

    private static void collectImages(File folder, boolean recursive, List<File> files) {
        File[] children = folder.listFiles();
        if (children == null) return;
        Arrays.sort(children, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                int byIgnoreCase = left.getName().compareToIgnoreCase(right.getName());
                if (byIgnoreCase != 0) return byIgnoreCase;
                return left.getName().compareTo(right.getName());
            }
        });
        for (int i = 0; i < children.length; i++) {
            File child = children[i];
            if (child.isDirectory()) {
                if (recursive && !isGeneratedOutputDirectory(child)) {
                    collectImages(child, true, files);
                }
            } else if (isSupportedImage(child)) {
                files.add(child);
            }
        }
    }

    private static List<File> normalizedSortedFiles(List<File> files) throws IOException {
        List<File> ordered = new ArrayList<File>(files.size());
        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            if (file == null) throw new IOException("image file list contains null");
            if (!file.isFile()) throw new IOException("image file does not exist: " + file);
            if (isSupportedImage(file)) ordered.add(file.getAbsoluteFile());
        }
        Collections.sort(ordered, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                int byIgnoreCase = left.getAbsolutePath().compareToIgnoreCase(
                        right.getAbsolutePath());
                if (byIgnoreCase != 0) return byIgnoreCase;
                return left.getAbsolutePath().compareTo(right.getAbsolutePath());
            }
        });
        return ordered;
    }

    private static List<ImageSource> normalizedSortedSources(List<ImageSource> sources)
            throws IOException {
        List<ImageSource> ordered = new ArrayList<ImageSource>(sources.size());
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<String>();
        for (ImageSource source : sources) {
            if (source == null) throw new IOException("image source list contains null");
            File file = source.file();
            if (!file.isFile()) throw new IOException("image file does not exist: " + file);
            if (!isSupportedImage(file)) continue;
            if (!keys.add(source.key())) {
                throw new IOException("image source list contains a duplicate: " + source);
            }
            ordered.add(source);
        }
        Collections.sort(ordered, new Comparator<ImageSource>() {
            @Override
            public int compare(ImageSource left, ImageSource right) {
                int byIgnoreCase = left.file().getAbsolutePath().compareToIgnoreCase(
                        right.file().getAbsolutePath());
                if (byIgnoreCase != 0) return byIgnoreCase;
                int byPath = left.file().getAbsolutePath().compareTo(
                        right.file().getAbsolutePath());
                if (byPath != 0) return byPath;
                return Integer.compare(left.seriesIndex(), right.seriesIndex());
            }
        });
        return ordered;
    }

    private static boolean isSupportedImage(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.contains(extension);
    }

    private static boolean isLif(File file) {
        return file != null && file.getName().toLowerCase(Locale.ROOT).endsWith(".lif");
    }

    private static boolean isGeneratedOutputDirectory(File folder) {
        String name = folder == null ? "" : folder.getName();
        return "Figure Panels".equalsIgnoreCase(name)
                || name.toLowerCase(Locale.ROOT).startsWith(".fpb-export-");
    }

    static void checkCancelled(CancelCheck cancelCheck) throws LoadCancelledException {
        if (Thread.currentThread().isInterrupted()
                || (cancelCheck != null && cancelCheck.isCancelled())) {
            throw new LoadCancelledException();
        }
    }

    private static final class LoadTask implements Callable<IndexedLoad> {
        private final int index;
        private final ImageSource source;
        private final int previewLongEdge;
        private final ZMode zMode;

        private LoadTask(int index, ImageSource source, int previewLongEdge, ZMode zMode) {
            this.index = index;
            this.source = source;
            this.previewLongEdge = previewLongEdge;
            this.zMode = zMode;
        }

        @Override
        public IndexedLoad call() throws IOException {
            return new IndexedLoad(index, loadOne(source, previewLongEdge, zMode,
                    NEVER_CANCELLED));
        }
    }

    private static final class OpenedImage {
        final ImagePlus image;
        final boolean bioFormats;

        OpenedImage(ImagePlus image, boolean bioFormats) {
            this.image = image;
            this.bioFormats = bioFormats;
        }
    }

    private static final class IndexedLoad {
        private final int index;
        private final LoadedImage result;

        private IndexedLoad(int index, LoadedImage result) {
            this.index = index;
            this.result = result;
        }
    }

    public static final class LoadResult {
        private final PlaneCache planeCache;
        private final HistogramCache histogramCache;

        private LoadResult(PlaneCache planeCache, HistogramCache histogramCache) {
            this.planeCache = planeCache;
            this.histogramCache = histogramCache;
        }

        public PlaneCache planeCache() {
            return planeCache;
        }

        public HistogramCache histogramCache() {
            return histogramCache;
        }

        public int imageCount() {
            return planeCache.imageCount();
        }

        public int channelCount() {
            return planeCache.channelCount();
        }
    }

    public static final class LoadedImage {
        private final ImageSource source;
        private final File sourceFile;
        private final int bitDepth;
        private final int sourceWidthPx;
        private final int sourceHeightPx;
        private final Calibration calibration;
        private final boolean bioFormats;
        private final List<PlaneCache.Plane> binnedPlanes;
        private final List<HistogramCache.Histogram> histograms;
        private final List<int[]> histogramCounts;

        private LoadedImage(ImageSource source, int bitDepth, int sourceWidthPx,
                int sourceHeightPx, Calibration calibration, boolean bioFormats,
                List<PlaneCache.Plane> binnedPlanes,
                List<HistogramCache.Histogram> histograms,
                List<int[]> histogramCounts) {
            this.source = source;
            this.sourceFile = source.file();
            this.bitDepth = bitDepth;
            this.sourceWidthPx = sourceWidthPx;
            this.sourceHeightPx = sourceHeightPx;
            this.calibration = calibration == null ? null : calibration.copy();
            this.bioFormats = bioFormats;
            this.binnedPlanes = Collections.unmodifiableList(
                    new ArrayList<PlaneCache.Plane>(binnedPlanes));
            this.histograms = Collections.unmodifiableList(
                    new ArrayList<HistogramCache.Histogram>(histograms));
            this.histogramCounts = Collections.unmodifiableList(new ArrayList<int[]>(histogramCounts));
        }

        public File sourceFile() {
            return sourceFile;
        }

        public ImageSource source() {
            return source;
        }

        public int bitDepth() {
            return bitDepth;
        }

        public int sourceWidthPx() {
            return sourceWidthPx;
        }

        public int sourceHeightPx() {
            return sourceHeightPx;
        }

        public Calibration calibration() {
            return calibration == null ? null : calibration.copy();
        }

        public boolean openedWithBioFormats() {
            return bioFormats;
        }

        public int channelCount() {
            return binnedPlanes.size();
        }

        public PlaneCache.Plane binnedPlane(int channelIndex) {
            return binnedPlanes.get(channelIndex);
        }

        public HistogramCache.Histogram histogram(int channelIndex) {
            return histograms.get(channelIndex);
        }

        private PlaneCache.ImagePlanes toPlaneCacheImage() {
            return new PlaneCache.ImagePlanes(source, bitDepth, sourceWidthPx,
                    sourceHeightPx, calibration, bioFormats, binnedPlanes);
        }

        private HistogramCache.ImageHistograms toHistogramCacheImage() {
            return new HistogramCache.ImageHistograms(source, histograms);
        }
    }
}
