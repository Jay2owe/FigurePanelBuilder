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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
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

    private static final List<String> IMAGE_EXTENSIONS = Collections.unmodifiableList(
            Arrays.asList("tif", "tiff", "png", "jpg", "jpeg", "gif", "bmp",
                    "lif", "czi", "nd2", "oib", "oif", "lsm"));

    private final int previewLongEdge;
    private final int threadCount;

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
        List<File> files = discoverImages(folder, recursive);
        return loadFiles(files, callback);
    }

    public LoadResult loadFiles(List<File> files, ProgressCallback callback) throws IOException {
        if (files == null) throw new IOException("image file list is null");
        if (files.size() > MAX_IMAGES) {
            throw new IOException("Figure Panel Builder v0.1.0 handles up to 100 images per run; "
                    + "this folder has " + files.size() + ".");
        }
        ProgressCallback progress = callback == null ? ProgressCallback.NONE : callback;
        List<File> ordered = normalizedSortedFiles(files);
        if (ordered.isEmpty()) {
            throw new IOException("No supported image files were found.");
        }

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(threadCount, ordered.size()));
        CompletionService<IndexedLoad> completion =
                new ExecutorCompletionService<IndexedLoad>(executor);
        try {
            for (int i = 0; i < ordered.size(); i++) {
                completion.submit(new LoadTask(i, ordered.get(i), previewLongEdge));
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
        return loadOne(file, previewLongEdge);
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

    private static LoadedImage loadOne(File file, int previewLongEdge) throws IOException {
        ImagePlus image = openImage(file);
        if (image == null) {
            throw new IOException("Could not open image: " + file.getAbsolutePath());
        }
        try {
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
                short[] projected = maxProjectChannel(
                        image, stack, channel, sliceCount, frame, bitDepth);
                int[] binnedSize = Binner.scaledDimensions(
                        image.getWidth(), image.getHeight(), previewLongEdge);
                short[] binned = Binner.maxBin(projected, image.getWidth(), image.getHeight(),
                        binnedSize[0], binnedSize[1]);
                binnedPlanes.add(new PlaneCache.Plane(binnedSize[0], binnedSize[1], binned));
                int[] counts = HistogramCache.countsFromPlane(projected);
                histogramCounts.add(counts);
                histograms.add(HistogramCache.cumulativeFromCounts(counts));
            }

            return new LoadedImage(file, bitDepth, image.getCalibration(),
                    binnedPlanes, histograms, histogramCounts);
        } finally {
            image.changes = false;
            image.close();
            image.flush();
        }
    }

    private static short[] maxProjectChannel(ImagePlus image, ImageStack stack,
            int channel, int sliceCount, int frame, int bitDepth) throws IOException {
        short[] projected = null;
        int expectedLength = image.getWidth() * image.getHeight();
        for (int slice = 1; slice <= sliceCount; slice++) {
            int stackIndex = image.getStackIndex(channel, slice, frame);
            ImageProcessor processor = stack.getProcessor(stackIndex);
            short[] plane = copyPixelsAsUnsignedShorts(processor, bitDepth, stackIndex);
            if (plane.length != expectedLength) {
                throw new IOException("Plane " + stackIndex + " has " + plane.length
                        + " pixels; expected " + expectedLength + ".");
            }
            if (projected == null) {
                projected = plane;
            } else {
                for (int i = 0; i < projected.length; i++) {
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
        if (processor == null) {
            throw new IOException("Missing processor at stack index " + stackIndex);
        }
        Object pixels = processor.getPixels();
        if (bitDepth == 8 && pixels instanceof byte[]) {
            byte[] live = (byte[]) pixels;
            byte[] safe = live.clone();
            short[] out = new short[safe.length];
            for (int i = 0; i < safe.length; i++) out[i] = (short) (safe[i] & 0xFF);
            return out;
        }
        if (bitDepth == 16 && pixels instanceof short[]) {
            short[] live = (short[]) pixels;
            return live.clone();
        }
        throw new IOException("Unsupported pixel storage at stack index " + stackIndex
                + ": bit depth " + bitDepth + ", pixels "
                + (pixels == null ? "null" : pixels.getClass().getName()));
    }

    private static ImagePlus openImage(File file) throws IOException {
        if (file == null) throw new IOException("image file is null");
        if (!file.isFile()) throw new IOException("image file does not exist: " + file);
        ImagePlus bioFormats = openWithBioFormatsIfAvailable(file);
        if (bioFormats != null) return bioFormats;
        return IJ.openImage(file.getAbsolutePath());
    }

    private static ImagePlus openWithBioFormatsIfAvailable(File file) throws IOException {
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
            Class<?> bfClass = Class.forName("loci.plugins.BF");
            Method openImagePlus = bfClass.getMethod("openImagePlus", optionsClass);
            Object value = openImagePlus.invoke(null, options);
            if (value instanceof ImagePlus[]) {
                ImagePlus[] images = (ImagePlus[]) value;
                for (int i = 0; i < images.length; i++) {
                    if (images[i] != null) return images[i];
                }
            }
            return null;
        } catch (ClassNotFoundException noBioFormats) {
            return null;
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new IOException("Bio-Formats failed to open " + file.getAbsolutePath(),
                    reflectionFailure);
        } catch (RuntimeException runtimeFailure) {
            throw new IOException("Bio-Formats failed to open " + file.getAbsolutePath(),
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

    private static List<File> discoverImages(File folder, boolean recursive) throws IOException {
        if (folder == null) throw new IOException("folder is null");
        if (!folder.isDirectory()) throw new IOException("folder is not a directory: " + folder);
        List<File> files = new ArrayList<File>();
        collectImages(folder.getAbsoluteFile(), recursive, files);
        return normalizedSortedFiles(files);
    }

    private static void collectImages(File folder, boolean recursive, List<File> files) {
        File[] children = folder.listFiles();
        if (children == null) return;
        Arrays.sort(children, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        for (int i = 0; i < children.length; i++) {
            File child = children[i];
            if (child.isDirectory()) {
                if (recursive) collectImages(child, true, files);
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
                return left.getAbsolutePath().compareToIgnoreCase(right.getAbsolutePath());
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

    private static final class LoadTask implements Callable<IndexedLoad> {
        private final int index;
        private final File file;
        private final int previewLongEdge;

        private LoadTask(int index, File file, int previewLongEdge) {
            this.index = index;
            this.file = file;
            this.previewLongEdge = previewLongEdge;
        }

        @Override
        public IndexedLoad call() throws IOException {
            return new IndexedLoad(index, loadOne(file, previewLongEdge));
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
        private final File sourceFile;
        private final int bitDepth;
        private final Calibration calibration;
        private final List<PlaneCache.Plane> binnedPlanes;
        private final List<HistogramCache.Histogram> histograms;
        private final List<int[]> histogramCounts;

        private LoadedImage(File sourceFile, int bitDepth, Calibration calibration,
                List<PlaneCache.Plane> binnedPlanes,
                List<HistogramCache.Histogram> histograms,
                List<int[]> histogramCounts) {
            this.sourceFile = sourceFile.getAbsoluteFile();
            this.bitDepth = bitDepth;
            this.calibration = calibration == null ? null : calibration.copy();
            this.binnedPlanes = Collections.unmodifiableList(
                    new ArrayList<PlaneCache.Plane>(binnedPlanes));
            this.histograms = Collections.unmodifiableList(
                    new ArrayList<HistogramCache.Histogram>(histograms));
            this.histogramCounts = Collections.unmodifiableList(new ArrayList<int[]>(histogramCounts));
        }

        public File sourceFile() {
            return sourceFile;
        }

        public int bitDepth() {
            return bitDepth;
        }

        public Calibration calibration() {
            return calibration == null ? null : calibration.copy();
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
            return new PlaneCache.ImagePlanes(sourceFile, bitDepth, calibration, binnedPlanes);
        }

        private HistogramCache.ImageHistograms toHistogramCacheImage() {
            return new HistogramCache.ImageHistograms(sourceFile, histograms);
        }
    }
}
