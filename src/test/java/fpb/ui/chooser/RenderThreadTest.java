/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.chooser;

import org.junit.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RenderThreadTest {

    @Test
    public void rapidRequestsCoalesceAndLastRequestIsRendered() throws Exception {
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final CountDownLatch lastPublished = new CountDownLatch(1);
        final AtomicInteger renderCount = new AtomicInteger();
        final AtomicReference<String> publishedKey = new AtomicReference<String>();

        RenderThread thread = new RenderThread(new RenderThread.RequestRenderer() {
            @Override
            public RenderThread.RenderedFrame render(RenderRequest request) throws Exception {
                renderCount.incrementAndGet();
                if ("0".equals(request.key())) {
                    firstStarted.countDown();
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                }
                return new RenderThread.RenderedFrame(request,
                        Collections.<RenderThread.RenderedRow>emptyList());
            }
        }, new RenderThread.FramePublisher() {
            @Override
            public void publish(RenderThread.RenderedFrame frame) {
                publishedKey.set(frame.request().key());
                if ("49".equals(frame.request().key())) lastPublished.countDown();
            }
        });

        try {
            thread.start();
            thread.request(RenderRequest.marker("0"));
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            for (int i = 1; i < 50; i++) {
                thread.request(RenderRequest.marker(String.valueOf(i)));
            }
            releaseFirst.countDown();

            assertTrue(lastPublished.await(5, TimeUnit.SECONDS));
            flushEdt();
            assertEquals("49", publishedKey.get());
            assertTrue("render count was " + renderCount.get(),
                    renderCount.get() < 50);
            assertNull(thread.lastFailure());
        } finally {
            thread.close();
        }
    }

    @Test
    public void supersededFrameIsNotPublished() throws Exception {
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final CountDownLatch secondPublished = new CountDownLatch(1);
        final List<String> published = Collections.synchronizedList(new ArrayList<String>());

        RenderThread thread = new RenderThread(new RenderThread.RequestRenderer() {
            @Override
            public RenderThread.RenderedFrame render(RenderRequest request) throws Exception {
                if ("first".equals(request.key())) {
                    firstStarted.countDown();
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                }
                return new RenderThread.RenderedFrame(request,
                        Collections.<RenderThread.RenderedRow>emptyList());
            }
        }, new RenderThread.FramePublisher() {
            @Override
            public void publish(RenderThread.RenderedFrame frame) {
                published.add(frame.request().key());
                if ("second".equals(frame.request().key())) secondPublished.countDown();
            }
        });

        try {
            thread.start();
            thread.request(RenderRequest.marker("first"));
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            thread.request(RenderRequest.marker("second"));
            releaseFirst.countDown();

            assertTrue(secondPublished.await(5, TimeUnit.SECONDS));
            flushEdt();
            assertEquals(Collections.singletonList("second"), published);
            assertNull(thread.lastFailure());
        } finally {
            thread.close();
        }
    }

    @Test
    public void eachPanelGridKeepsItsOwnSingleSelection() throws Exception {
        final PanelGrid first = grid("Control");
        final PanelGrid second = grid("DrugA");

        runOnEdt(new Runnable() {
            @Override
            public void run() {
                first.setSelectedRowIndex(0);
                second.setSelectedRowIndex(1);
                first.setSelectedRowIndex(2);
            }
        });

        assertEquals("S3", first.selectedSubject().subject());
        assertEquals("S2", second.selectedSubject().subject());
    }

    @Test
    public void rowImageCacheUpdatesWithoutChangingListShape() throws Exception {
        final PanelGrid grid = grid("Control");
        final BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);

        runOnEdt(new Runnable() {
            @Override
            public void run() {
                grid.putRowImage(0, image);
            }
        });

        assertEquals(3, grid.rowCount());
        assertEquals(0, grid.selectedRowIndex());
    }

    private static PanelGrid grid(String group) {
        List<RowImage.SubjectRow> rows = new ArrayList<RowImage.SubjectRow>();
        rows.add(new RowImage.SubjectRow(group, "S1", 0, true));
        rows.add(new RowImage.SubjectRow(group, "S2", 1, false));
        rows.add(new RowImage.SubjectRow(group, "S3", 2, false));
        PanelGrid grid = new PanelGrid(group, rows, RowImage.Layout.standard(3));
        grid.setSelectedRowIndex(0);
        return grid;
    }

    private static void flushEdt() throws Exception {
        runOnEdt(new Runnable() {
            @Override
            public void run() {}
        });
    }

    private static void runOnEdt(Runnable runnable) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeAndWait(runnable);
        }
    }
}
