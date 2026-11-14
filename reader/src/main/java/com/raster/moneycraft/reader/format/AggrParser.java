package com.raster.moneycraft.reader.format;

import com.raster.moneycraft.reader.api.CorruptSnapshotException;
import com.raster.moneycraft.reader.model.DiagnosticSeverity;
import com.raster.moneycraft.reader.model.ReaderDiagnostic;

/**
 * Clean-room sector-chain validator for Aggr.1800 periodic ledger/group balance rollup pages.
 */
public final class AggrParser {

    private final Sector512PageReader pageReader;

    public AggrParser(Sector512PageReader pageReader) {
        this.pageReader = pageReader;
    }

    public ReaderDiagnostic verifyRollupSectors(byte[] aggrBytes) {
        int totalSectors = aggrBytes.length / Sector512PageReader.SECTOR_BYTES;
        int rootRollupChains = 0;
        for (int s = 1; s < totalSectors; s++) {
            Sector512PageReader.DataSectorHeader hdr = pageReader.readDataSectorHeader(aggrBytes, s);
            if (hdr.nextSector() < 0 || hdr.nextSector() >= totalSectors) {
                throw new CorruptSnapshotException(
                        "CORRUPT_AGGR_CHAIN",
                        "Aggr.1800 sector " + s + " has out-of-bounds nextSector=" + hdr.nextSector());
            }
            if (hdr.chainSectorSpan() > 0) {
                pageReader.reassembleStream("Aggr.1800", aggrBytes, s);
                rootRollupChains++;
            }
        }
        return new ReaderDiagnostic(
                DiagnosticSeverity.INFO,
                "AGGR_STORE_VERIFIED",
                "Aggr.1800",
                "Verified " + (totalSectors - 1) + " data sectors and " + rootRollupChains + " rollup chains in Aggr.1800");
    }
}
