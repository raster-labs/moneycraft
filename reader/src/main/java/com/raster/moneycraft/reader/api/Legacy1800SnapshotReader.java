package com.raster.moneycraft.reader.api;

import com.raster.moneycraft.reader.model.ExtractedCompanySnapshot;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Neutral public entry point for reading and reconciling legacy .1800 company snapshot directories
 * and companion canonical XML exports with zero runtime framework or network dependencies.
 */
public final class Legacy1800SnapshotReader {

    private final TallySnapshotReader delegate;

    public Legacy1800SnapshotReader() {
        this.delegate = new TallySnapshotReader();
    }

    public ExtractedCompanySnapshot read(Path inputDirectory) throws IOException {
        return delegate.read(inputDirectory);
    }

    public ExtractedCompanySnapshot read(ReaderOptions options) throws IOException {
        return delegate.read(options);
    }
}
