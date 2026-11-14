package com.raster.moneycraft.reader.model;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Parsed 512-byte Sector-0 header for a TallyPrime .1800 store or weighted B-tree index file.
 */
public record Sector0Header(
        String fileName,
        boolean indexStore,
        int releaseStoreMagic,
        int rollingChecksum16,
        long headerSignature32,
        long dataSectorCount,
        long allocatedSectorCount,
        long rootDirectorySector,
        long highWaterSector,
        long activeRootCount,
        long sentinel32,
        long schemaGeneration,
        long storeSubVersion,
        byte[] formatVersionBytes,
        TallyReleaseVersion releaseVersion
) {
    public Sector0Header {
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(formatVersionBytes, "formatVersionBytes must not be null");
        Objects.requireNonNull(releaseVersion, "releaseVersion must not be null");
        formatVersionBytes = Arrays.copyOf(formatVersionBytes, formatVersionBytes.length);
    }

    @Override
    public byte[] formatVersionBytes() {
        return Arrays.copyOf(formatVersionBytes, formatVersionBytes.length);
    }

    public String formatVersionHex() {
        return HexFormat.of().formatHex(formatVersionBytes);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Sector0Header that)) {
            return false;
        }
        return indexStore == that.indexStore
                && releaseStoreMagic == that.releaseStoreMagic
                && rollingChecksum16 == that.rollingChecksum16
                && headerSignature32 == that.headerSignature32
                && dataSectorCount == that.dataSectorCount
                && allocatedSectorCount == that.allocatedSectorCount
                && rootDirectorySector == that.rootDirectorySector
                && highWaterSector == that.highWaterSector
                && activeRootCount == that.activeRootCount
                && sentinel32 == that.sentinel32
                && schemaGeneration == that.schemaGeneration
                && storeSubVersion == that.storeSubVersion
                && fileName.equals(that.fileName)
                && Arrays.equals(formatVersionBytes, that.formatVersionBytes)
                && releaseVersion == that.releaseVersion;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                fileName,
                indexStore,
                releaseStoreMagic,
                rollingChecksum16,
                headerSignature32,
                dataSectorCount,
                allocatedSectorCount,
                rootDirectorySector,
                highWaterSector,
                activeRootCount,
                sentinel32,
                schemaGeneration,
                storeSubVersion,
                releaseVersion);
        result = 31 * result + Arrays.hashCode(formatVersionBytes);
        return result;
    }
}
