package com.raster.moneycraft.reader.model;

import java.util.Objects;

/**
 * Physical descriptor and cryptographic SHA-256 digest of a validated .1800 store file.
 */
public record StoreFileDescriptor(
        String fileName,
        long byteSize,
        long totalSectors,
        String sha256Hex,
        Sector0Header sector0Header
) {
    public StoreFileDescriptor {
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(sha256Hex, "sha256Hex must not be null");
        Objects.requireNonNull(sector0Header, "sector0Header must not be null");
    }
}
