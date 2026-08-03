/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.meta;

import java.io.File;

/** Uses the containing folder as group and the filename stem as subject. */
public final class SubfolderStrategy implements LabelStrategy {

    @Override
    public void apply(MetadataTable table) {
        for (MetadataRow row : table.rows()) {
            File parent = row.file.getParentFile();
            if (parent == null || MetadataRow.isBlank(parent.getName())) {
                row.clearLabels("Image has no containing folder name");
            } else {
                row.setLabels(parent.getName(),
                        MetadataTable.basenameWithoutExtension(row.file), "");
            }
        }
    }
}
