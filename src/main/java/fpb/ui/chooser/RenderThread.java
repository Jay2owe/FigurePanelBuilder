/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.chooser;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

/** Long-lived coalescing daemon renderer for chooser-grid brightness updates. */
public final class RenderThread implements Runnable, AutoCloseable {

    public interface RequestRenderer {
        RenderedFrame render(RenderRequest request) throws Exception;
    }

    public interface FramePublisher {
        void publish(RenderedFrame frame);
    }

    private final AtomicReference<QueuedRequest> pending =
            new AtomicReference<QueuedRequest>();
    private final AtomicBoolean frameInFlight = new AtomicBoolean(false);
    private final AtomicLong nextSequence = new AtomicLong();
    private final AtomicLong newestRequestedSequence = new AtomicLong(-1L);
    private final Object lock = new Object();
    private final RequestRenderer renderer;
    private final FramePublisher publisher;
    private final Thread thread;
    private volatile boolean done;
    private volatile Throwable lastFailure;

    public RenderThread() {
        this(new GridRequestRenderer(), new GridFramePublisher());
    }

    public RenderThread(RequestRenderer renderer, FramePublisher publisher) {
        if (renderer == null) throw new IllegalArgumentException("renderer must not be null");
        if (publisher == null) throw new IllegalArgumentException("publisher must not be null");
        this.renderer = renderer;
        this.publisher = publisher;
        thread = new Thread(this, "FPB chooser render thread");
        thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    public void request(RenderRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        long sequence = nextSequence.incrementAndGet();
        newestRequestedSequence.set(sequence);
        pending.set(new QueuedRequest(sequence, request));
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    @Override
    public void run() {
        while (!done) {
            QueuedRequest queued = takeLatest();
            if (queued == null) continue;
            if (!frameInFlight.compareAndSet(false, true)) {
                offerIfNewer(queued);
                waitForFrameGate();
                continue;
            }
            renderOne(queued);
        }
    }

    public Throwable lastFailure() {
        return lastFailure;
    }

    public boolean isAlive() {
        return thread.isAlive();
    }

    @Override
    public void close() {
        done = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        thread.interrupt();
    }

    private QueuedRequest takeLatest() {
        QueuedRequest queued;
        synchronized (lock) {
            while ((queued = pending.getAndSet(null)) == null && !done) {
                try {
                    lock.wait();
                } catch (InterruptedException interrupted) {
                    if (done) return null;
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return queued;
    }

    private void renderOne(final QueuedRequest queued) {
        try {
            final RenderedFrame frame = renderer.render(queued.request);
            if (queued.sequence != newestRequestedSequence.get()) {
                clearFrameGate();
                return;
            }
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (queued.sequence == newestRequestedSequence.get()) {
                            publisher.publish(frame);
                        }
                    } finally {
                        clearFrameGate();
                    }
                }
            });
        } catch (Throwable failure) {
            lastFailure = failure;
            clearFrameGate();
        }
    }

    private void clearFrameGate() {
        frameInFlight.set(false);
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void waitForFrameGate() {
        synchronized (lock) {
            while (frameInFlight.get() && !done) {
                try {
                    lock.wait();
                } catch (InterruptedException interrupted) {
                    if (done) return;
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void offerIfNewer(QueuedRequest queued) {
        while (true) {
            QueuedRequest current = pending.get();
            if (current != null && current.sequence > queued.sequence) return;
            if (pending.compareAndSet(current, queued)) return;
        }
    }

    private static final class GridRequestRenderer implements RequestRenderer {
        @Override
        public RenderedFrame render(RenderRequest request) {
            if (!request.hasGrid()) return new RenderedFrame(request,
                    Collections.<RenderedRow>emptyList());
            PanelGrid grid = request.grid();
            RowImage.Layout layout = grid.layoutForCurrentScale();
            List<Integer> indices = request.rowIndices();
            List<RenderedRow> rows = new ArrayList<RenderedRow>(indices.size());
            for (int i = 0; i < indices.size(); i++) {
                int rowIndex = indices.get(i).intValue();
                RowImage.SubjectRow row = grid.rowAt(rowIndex);
                BufferedImage image = RowImage.renderSubject(row, request.planes(),
                        request.histograms(), request.channels(), layout,
                        grid.selectedRowIndex() == rowIndex);
                rows.add(new RenderedRow(rowIndex, image));
            }
            return new RenderedFrame(request, rows);
        }
    }

    private static final class GridFramePublisher implements FramePublisher {
        @Override
        public void publish(RenderedFrame frame) {
            RenderRequest request = frame.request();
            if (request.hasGrid()) request.grid().applyRenderedRows(frame.rows());
        }
    }

    private static final class QueuedRequest {
        private final long sequence;
        private final RenderRequest request;

        private QueuedRequest(long sequence, RenderRequest request) {
            this.sequence = sequence;
            this.request = request;
        }
    }

    public static final class RenderedFrame {
        private final RenderRequest request;
        private final List<RenderedRow> rows;

        public RenderedFrame(RenderRequest request, List<RenderedRow> rows) {
            if (request == null) throw new IllegalArgumentException("request must not be null");
            this.request = request;
            this.rows = rows == null ? Collections.<RenderedRow>emptyList()
                    : Collections.unmodifiableList(new ArrayList<RenderedRow>(rows));
        }

        public RenderRequest request() {
            return request;
        }

        public List<RenderedRow> rows() {
            return rows;
        }
    }

    public static final class RenderedRow {
        private final int rowIndex;
        private final BufferedImage image;

        public RenderedRow(int rowIndex, BufferedImage image) {
            if (rowIndex < 0) throw new IllegalArgumentException("rowIndex is negative");
            if (image == null) throw new IllegalArgumentException("image must not be null");
            this.rowIndex = rowIndex;
            this.image = image;
        }

        public int rowIndex() {
            return rowIndex;
        }

        public BufferedImage image() {
            return image;
        }
    }
}
