/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.io;

import java.io.IOException;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/** Shared background preview load reused by channel setup and the chooser. */
public final class ImagePreloader implements AutoCloseable {

    public interface CompletionListener {
        void completed(IOException failure);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "FPB-image-preloader");
                    thread.setDaemon(true);
                    return thread;
                }
            });
    private Future<ImageLoader.LoadResult> pending;
    private String pendingKey = "";
    private ImageLoader.LoadResult ready;
    private String readyKey = "";
    private long generation;
    private boolean closed;
    private final List<CompletionListener> listeners =
            new ArrayList<CompletionListener>();
    private final List<ProgressCallback> progressListeners =
            new ArrayList<ProgressCallback>();
    private int progressCompleted;
    private int progressTotal;
    private File progressFile;

    /** Starts or joins a preload without blocking the caller. */
    public void preload(List<ImageSource> sources, ImageLoader.ZMode zMode,
            CompletionListener listener) {
        preload(sources, zMode, ProgressCallback.NONE, listener);
    }

    /** Starts or joins a preload and reports completed logical images. */
    public void preload(List<ImageSource> sources, ImageLoader.ZMode zMode,
            ProgressCallback progressListener, CompletionListener listener) {
        final List<ImageSource> snapshot = copySources(sources);
        final ImageLoader.ZMode mode = zMode == null
                ? ImageLoader.ZMode.MAX : zMode;
        final String key = key(snapshot, mode);
        boolean alreadyReady = false;
        ProgressSnapshot immediateProgress = null;
        synchronized (this) {
            if (closed) return;
            if (key.equals(readyKey) && ready != null) {
                alreadyReady = true;
                immediateProgress = new ProgressSnapshot(snapshot.size(),
                        snapshot.size(), snapshot.isEmpty() ? null
                        : snapshot.get(snapshot.size() - 1).file());
            } else if (key.equals(pendingKey) && pending != null) {
                if (listener != null) listeners.add(listener);
                addProgressListener(progressListener);
                immediateProgress = progressSnapshot();
            } else {
                startLocked(snapshot, mode, key, progressListener, listener);
                immediateProgress = progressSnapshot();
            }
            notifyProgress(progressListener, immediateProgress);
        }
        if (alreadyReady && listener != null) listener.completed(null);
    }

    /** Returns a matching completed preload, or null without waiting. */
    public synchronized ImageLoader.LoadResult readyResult(
            List<ImageSource> sources, ImageLoader.ZMode zMode) {
        String key = key(copySources(sources), zMode == null
                ? ImageLoader.ZMode.MAX : zMode);
        return key.equals(readyKey) ? ready : null;
    }

    /** Reuses, waits for, or starts the load required by Choose Images. */
    public ImageLoader.LoadResult loadOrAwait(List<ImageSource> sources,
            ImageLoader.ZMode zMode) throws IOException {
        return loadOrAwait(sources, zMode, ProgressCallback.NONE);
    }

    public ImageLoader.LoadResult loadOrAwait(List<ImageSource> sources,
            ImageLoader.ZMode zMode, ProgressCallback progressListener)
            throws IOException {
        List<ImageSource> snapshot = copySources(sources);
        ImageLoader.ZMode mode = zMode == null ? ImageLoader.ZMode.MAX : zMode;
        String key = key(snapshot, mode);
        Future<ImageLoader.LoadResult> future;
        ImageLoader.LoadResult completedResult = null;
        ProgressSnapshot immediateProgress;
        synchronized (this) {
            if (closed) throw new IOException("image preloader is closed");
            if (key.equals(readyKey) && ready != null) {
                completedResult = ready;
                immediateProgress = new ProgressSnapshot(snapshot.size(),
                        snapshot.size(), snapshot.isEmpty() ? null
                        : snapshot.get(snapshot.size() - 1).file());
                future = null;
            } else {
                if (!key.equals(pendingKey) || pending == null) {
                    startLocked(snapshot, mode, key, progressListener, null);
                } else {
                    addProgressListener(progressListener);
                }
                future = pending;
                immediateProgress = progressSnapshot();
            }
            notifyProgress(progressListener, immediateProgress);
        }
        if (completedResult != null) return completedResult;
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while preparing image previews.",
                    interrupted);
        } catch (CancellationException cancelled) {
            throw new IOException("Image preview preparation was cancelled.",
                    cancelled);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("Could not prepare image previews.", cause);
        }
    }

    public synchronized boolean isReady(List<ImageSource> sources,
            ImageLoader.ZMode zMode) {
        return readyResult(sources, zMode) != null;
    }

    public synchronized void cancel() {
        generation++;
        if (pending != null) pending.cancel(true);
        pending = null;
        pendingKey = "";
        ready = null;
        readyKey = "";
        listeners.clear();
        progressListeners.clear();
        progressCompleted = 0;
        progressTotal = 0;
        progressFile = null;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        cancel();
        executor.shutdownNow();
    }

    private void startLocked(final List<ImageSource> sources,
            final ImageLoader.ZMode zMode, final String key,
            ProgressCallback progressListener, CompletionListener listener) {
        generation++;
        final long taskGeneration = generation;
        if (pending != null) pending.cancel(true);
        pending = null;
        pendingKey = key;
        ready = null;
        readyKey = "";
        listeners.clear();
        progressListeners.clear();
        progressCompleted = 0;
        progressTotal = sources.size();
        progressFile = null;
        addProgressListener(progressListener);
        if (listener != null) listeners.add(listener);
        pending = executor.submit(new java.util.concurrent.Callable<ImageLoader.LoadResult>() {
            @Override
            public ImageLoader.LoadResult call() throws Exception {
                try {
                    ImageLoader.LoadResult result = new ImageLoader(150, 4)
                            .loadSources(sources, zMode, new ProgressCallback() {
                                @Override
                                public void onProgress(int completed, int total,
                                        File file) {
                                    reportProgress(taskGeneration, key, completed,
                                            total, file);
                                }
                            });
                    finish(taskGeneration, key, result, null);
                    return result;
                } catch (IOException failure) {
                    finish(taskGeneration, key, null, failure);
                    throw failure;
                } catch (RuntimeException failure) {
                    IOException wrapped = new IOException(
                            "Could not prepare image previews.", failure);
                    finish(taskGeneration, key, null, wrapped);
                    throw wrapped;
                }
            }
        });
    }

    private void finish(long taskGeneration, String key,
            ImageLoader.LoadResult result, IOException failure) {
        List<CompletionListener> notify;
        synchronized (this) {
            if (closed || taskGeneration != generation
                    || !key.equals(pendingKey)) return;
            pending = null;
            pendingKey = "";
            if (failure == null) {
                ready = result;
                readyKey = key;
            } else {
                ready = null;
                readyKey = "";
            }
            notify = new ArrayList<CompletionListener>(listeners);
            listeners.clear();
            progressListeners.clear();
        }
        for (CompletionListener listener : notify) {
            try {
                listener.completed(failure);
            } catch (RuntimeException ignored) {
                // UI status listeners must not invalidate a completed preload.
            }
        }
    }

    private void reportProgress(long taskGeneration, String key, int completed,
            int total, File file) {
        List<ProgressCallback> notify;
        ProgressSnapshot snapshot;
        synchronized (this) {
            if (closed || taskGeneration != generation
                    || !key.equals(pendingKey)) return;
            progressCompleted = Math.max(0, completed);
            progressTotal = Math.max(0, total);
            progressFile = file;
            snapshot = progressSnapshot();
            notify = new ArrayList<ProgressCallback>(progressListeners);
        }
        for (ProgressCallback listener : notify) notifyProgress(listener, snapshot);
    }

    private void addProgressListener(ProgressCallback listener) {
        if (listener != null && listener != ProgressCallback.NONE
                && !progressListeners.contains(listener)) {
            progressListeners.add(listener);
        }
    }

    private ProgressSnapshot progressSnapshot() {
        return new ProgressSnapshot(progressCompleted, progressTotal, progressFile);
    }

    private static void notifyProgress(ProgressCallback listener,
            ProgressSnapshot snapshot) {
        if (listener == null || listener == ProgressCallback.NONE || snapshot == null) return;
        try {
            listener.onProgress(snapshot.completed, snapshot.total, snapshot.file);
        } catch (RuntimeException ignored) {
            // UI progress listeners must not invalidate a preload.
        }
    }

    private static List<ImageSource> copySources(List<ImageSource> sources) {
        if (sources == null) throw new IllegalArgumentException("sources must not be null");
        List<ImageSource> copy = new ArrayList<ImageSource>(sources.size());
        for (ImageSource source : sources) {
            if (source == null) throw new IllegalArgumentException("sources contains null");
            copy.add(source);
        }
        return copy;
    }

    private static String key(List<ImageSource> sources, ImageLoader.ZMode zMode) {
        StringBuilder key = new StringBuilder(zMode.name()).append('\n');
        for (ImageSource source : sources) key.append(source.key()).append('\n');
        return key.toString();
    }

    private static final class ProgressSnapshot {
        final int completed;
        final int total;
        final File file;

        ProgressSnapshot(int completed, int total, File file) {
            this.completed = completed;
            this.total = total;
            this.file = file;
        }
    }
}
