/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb;

import fpb.ui.Step5Export;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Public Java facade for folder batch processing without Swing dialogs. */
public final class FPBBatchRunner {

    private FPBBatchRunner() {}

    public static FPBBatchResult run(FPBBatchParameters parameters) throws IOException {
        validate(parameters);
        int processed = 0;
        int errors = 0;
        List<FPBResult> results = new ArrayList<FPBResult>();
        List<String> messages = new ArrayList<String>();
        for (File folder : parameters.folders()) {
            try {
                FPBParameters runParameters = parameters.template().toBuilder()
                        .folder(folder)
                        .outputFolder(outputFolder(parameters, folder))
                        .figureName(folder.getName())
                        .build();
                FPBResult result = FPB.run(runParameters);
                results.add(result);
                if (runParameters.outputFolder() != null) {
                    Step5Export.ExportResult ignored = FPB.write(result);
                }
                processed++;
            } catch (Exception failure) {
                errors++;
                messages.add(folder.getAbsolutePath() + ": "
                        + (failure.getMessage() == null
                        ? failure.toString() : failure.getMessage()));
                if (!parameters.continueOnError()) {
                    if (failure instanceof IOException) throw (IOException) failure;
                    throw new IOException(failure.getMessage(), failure);
                }
            }
        }
        return new FPBBatchResult(parameters.folders().size(), processed, errors,
                parameters.outputRoot(), results, messages);
    }

    private static File outputFolder(FPBBatchParameters parameters, File folder) {
        if (parameters.outputRoot() == null) return null;
        return new File(parameters.outputRoot(), safe(folder.getName()));
    }

    private static void validate(FPBBatchParameters parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("FPB batch parameters must not be null.");
        }
        if (parameters.template() == null) {
            throw new IllegalArgumentException("FPB batch template parameters are required.");
        }
        if (parameters.folders().isEmpty()) {
            throw new IllegalArgumentException("At least one input folder is required.");
        }
        for (File folder : parameters.folders()) {
            if (folder == null || !folder.isDirectory()) {
                throw new IllegalArgumentException("Input folder does not exist: "
                        + folder);
            }
        }
        if (parameters.outputRoot() != null && parameters.outputRoot().exists()
                && !parameters.outputRoot().isDirectory()) {
            throw new IllegalArgumentException("Output root is not a folder: "
                    + parameters.outputRoot());
        }
    }

    private static String safe(String value) {
        String clean = value == null ? "" : value.trim()
                .replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.length() == 0 ? "folder" : clean;
    }
}
