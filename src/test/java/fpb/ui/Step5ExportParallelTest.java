/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui;

import fpb.util.CancellationCheck;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/** Regression coverage for bounded parallel full-resolution export preparation. */
public class Step5ExportParallelTest {

    @Test
    public void jobsOverlapButResultsKeepSelectionOrder() throws Exception {
        final CountDownLatch bothStarted = new CountDownLatch(2);
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maximumActive = new AtomicInteger();
        List<Step5Export.ParallelJob<Integer>> jobs =
                new ArrayList<Step5Export.ParallelJob<Integer>>();
        for (int i = 0; i < 2; i++) {
            final int value = i;
            jobs.add(new Step5Export.ParallelJob<Integer>() {
                @Override
                public Integer run(CancellationCheck cancelCheck) throws Exception {
                    int nowActive = active.incrementAndGet();
                    updateMaximum(maximumActive, nowActive);
                    bothStarted.countDown();
                    try {
                        if (!bothStarted.await(5L, TimeUnit.SECONDS)) {
                            throw new IOException("Export preparation ran sequentially");
                        }
                        if (value == 0) Thread.sleep(75L);
                        return Integer.valueOf(value);
                    } finally {
                        active.decrementAndGet();
                    }
                }
            });
        }
        final List<Integer> completionCounts = new ArrayList<Integer>();

        List<Integer> results = Step5Export.runOrderedJobs(jobs, 2,
                CancellationCheck.NEVER_CANCELLED,
                new Step5Export.ParallelCompletion<Integer>() {
                    @Override
                    public void completed(Integer result, int completed, int total) {
                        completionCounts.add(Integer.valueOf(completed));
                    }
                });

        assertEquals(Arrays.asList(Integer.valueOf(0), Integer.valueOf(1)), results);
        assertEquals(2, maximumActive.get());
        assertEquals(Arrays.asList(Integer.valueOf(1), Integer.valueOf(2)),
                completionCounts);
    }

    @Test
    public void workerCountHonoursJobCpuMemoryAndSafetyCaps() {
        long eightGiB = 8L * 1024L * 1024L * 1024L;
        assertEquals(4, Step5Export.fullResolutionWorkerCount(12, 16, eightGiB));
        assertEquals(2, Step5Export.fullResolutionWorkerCount(2, 16, eightGiB));
        assertEquals(1, Step5Export.fullResolutionWorkerCount(12, 1, eightGiB));
        assertEquals(1, Step5Export.fullResolutionWorkerCount(12, 16,
                256L * 1024L * 1024L));
    }

    @Test
    public void progressTextIncludesPhasePercentageAndRoughRemainingTime() {
        assertEquals("Preparing images - 0% - estimating time",
                Step5Export.progressText("Preparing images", 0, 4, 10));
        assertEquals("Writing files - 25% - about 30s remaining",
                Step5Export.progressText("Writing files", 1, 4, 10));
        assertEquals("Writing files - 50% - about 1m 5s remaining",
                Step5Export.progressText("Writing files", 2, 4, 65));
        assertEquals("Export complete - 100%",
                Step5Export.progressText("Export complete", 4, 4, 90));
    }

    private static void updateMaximum(AtomicInteger maximum, int candidate) {
        int current;
        do {
            current = maximum.get();
            if (candidate <= current) return;
        } while (!maximum.compareAndSet(current, candidate));
    }
}
