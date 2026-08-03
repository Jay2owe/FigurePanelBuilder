/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Summary returned by the Figure Panel Builder batch runner. */
public final class FPBBatchResult {

    private final int totalFolders;
    private final int processedFolders;
    private final int errorFolders;
    private final File outputRoot;
    private final List<FPBResult> results;
    private final List<String> errors;

    FPBBatchResult(int totalFolders, int processedFolders, int errorFolders,
            File outputRoot, List<FPBResult> results, List<String> errors) {
        this.totalFolders = totalFolders;
        this.processedFolders = processedFolders;
        this.errorFolders = errorFolders;
        this.outputRoot = outputRoot;
        this.results = Collections.unmodifiableList(new ArrayList<FPBResult>(results));
        this.errors = Collections.unmodifiableList(new ArrayList<String>(errors));
    }

    public int totalFolders() { return totalFolders; }
    public int processedFolders() { return processedFolders; }
    public int errorFolders() { return errorFolders; }
    public File outputRoot() { return outputRoot; }
    public List<FPBResult> results() { return results; }
    public List<String> errors() { return errors; }
    public boolean hasErrors() { return errorFolders > 0; }
}
