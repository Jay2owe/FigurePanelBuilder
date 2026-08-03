/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.io;

import java.io.File;

/** Receives folder-load progress without tying the cache layer to any UI. */
public interface ProgressCallback {

    ProgressCallback NONE = new ProgressCallback() {
        @Override
        public void onProgress(int completed, int total, File file) {
            // no-op
        }
    };

    void onProgress(int completed, int total, File file);
}
