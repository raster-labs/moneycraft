package com.raster.moneycraft.reader.api;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable configuration options for {@link TallySnapshotReader}.
 */
public record ReaderOptions(
        Path inputDirectory,
        Path companionXmlDirectory,
        boolean failOnShadowMismatch,
        boolean failOnReconciliationMismatch
) {
    public ReaderOptions {
        Objects.requireNonNull(inputDirectory, "inputDirectory must not be null");
    }

    public static ReaderOptions strict(Path inputDirectory) {
        return new ReaderOptions(inputDirectory, null, true, true);
    }

    public static ReaderOptions strictWithCompanion(Path inputDirectory, Path companionXmlDirectory) {
        return new ReaderOptions(inputDirectory, companionXmlDirectory, true, true);
    }

    public static ReaderOptions diagnosticOnly(Path inputDirectory) {
        return new ReaderOptions(inputDirectory, null, false, false);
    }
}
