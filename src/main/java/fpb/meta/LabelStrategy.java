/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.meta;

/** Populates editable metadata rows from one labelling source. */
public interface LabelStrategy {

    void apply(MetadataTable table);
}
