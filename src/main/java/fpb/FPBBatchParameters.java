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

/** Immutable input bundle for running Figure Panel Builder over folders. */
public final class FPBBatchParameters {

    private final List<File> folders;
    private final FPBParameters template;
    private final File outputRoot;
    private final boolean continueOnError;

    private FPBBatchParameters(Builder builder) {
        this.folders = Collections.unmodifiableList(new ArrayList<File>(builder.folders));
        this.template = builder.template;
        this.outputRoot = builder.outputRoot == null ? null
                : builder.outputRoot.getAbsoluteFile();
        this.continueOnError = builder.continueOnError;
    }

    public static Builder builder(List<File> folders, FPBParameters template) {
        return new Builder(folders, template);
    }

    public List<File> folders() { return folders; }
    public FPBParameters template() { return template; }
    public File outputRoot() { return outputRoot; }
    public boolean continueOnError() { return continueOnError; }

    public static final class Builder {
        private final List<File> folders = new ArrayList<File>();
        private final FPBParameters template;
        private File outputRoot;
        private boolean continueOnError = true;

        private Builder(List<File> folders, FPBParameters template) {
            if (folders != null) {
                for (File folder : folders) {
                    if (folder != null) this.folders.add(folder.getAbsoluteFile());
                }
            }
            this.template = template;
        }

        public Builder outputRoot(File outputRoot) {
            this.outputRoot = outputRoot;
            return this;
        }

        public Builder continueOnError(boolean continueOnError) {
            this.continueOnError = continueOnError;
            return this;
        }

        public FPBBatchParameters build() {
            return new FPBBatchParameters(this);
        }
    }
}
