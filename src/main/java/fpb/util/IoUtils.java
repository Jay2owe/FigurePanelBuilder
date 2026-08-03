/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Small filesystem helpers vendored for Figure Panel Builder. */
public final class IoUtils {

    private IoUtils() {}

    public static void mustMkdirs(File directory) throws IOException {
        if (directory == null) throw new IOException("null directory");
        if (directory.exists()) {
            if (!directory.isDirectory()) {
                throw new IOException("path exists but is not a directory: "
                        + directory.getAbsolutePath());
            }
            return;
        }
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("could not create directory: "
                    + directory.getAbsolutePath());
        }
    }

    public static void moveReplacing(Path source, Path target) throws IOException {
        requireDifferentPaths(source, target);
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("target has no parent directory: " + target);
        Files.createDirectories(parent);
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void commitReplacingSmallFile(Path temp, Path target) throws IOException {
        moveReplacing(temp, target);
    }

    private static void requireDifferentPaths(Path source, Path target) throws IOException {
        if (source == null) throw new IOException("source path is null");
        if (target == null) throw new IOException("target path is null");
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new IOException("source and target must be different paths: " + source);
        }
        if (!Files.isRegularFile(source)) {
            throw new IOException("source is not a regular file: " + source);
        }
    }
}
