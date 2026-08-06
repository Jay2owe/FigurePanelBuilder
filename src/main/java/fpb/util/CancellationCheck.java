/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.util;

/** Shared cooperative cancellation signal for long-running export stages. */
public interface CancellationCheck {

    CancellationCheck NEVER_CANCELLED = new CancellationCheck() {
        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    boolean isCancelled();
}
