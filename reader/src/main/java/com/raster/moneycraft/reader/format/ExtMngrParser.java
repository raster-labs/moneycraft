package com.raster.moneycraft.reader.format;

import com.raster.moneycraft.reader.api.CorruptSnapshotException;
import com.raster.moneycraft.reader.model.DiagnosticSeverity;
import com.raster.moneycraft.reader.model.ReaderDiagnostic;

/**
 * Clean-room sector validator for ExtMngr.1800, VchStatus.1800, and StatStatus.1800 MCA Edit Log & statutory stores.
 */
public final class ExtMngrParser {

    private final Sector512PageReader pageReader;

    public ExtMngrParser(Sector512PageReader pageReader) {
        this.pageReader = pageReader;
    }

    public ReaderDiagnostic verifyAuxiliaryStore(String storeName, byte[] storeBytes) {
        int totalSectors = storeBytes.length / Sector512PageReader.SECTOR_BYTES;
        int activeChains = 0;
        for (int s = 1; s < totalSectors; s++) {
            Sector512PageReader.DataSectorHeader hdr = pageReader.readDataSectorHeader(storeBytes, s);
            if (hdr.nextSector() < 0 || hdr.nextSector() >= totalSectors) {
                throw new CorruptSnapshotException(
                        "CORRUPT_AUX_STORE_CHAIN",
                        storeName + " sector " + s + " has out-of-bounds nextSector=" + hdr.nextSector());
            }
            if (hdr.chainSectorSpan() > 0) {
                pageReader.reassembleStream(storeName, storeBytes, s);
                activeChains++;
            }
        }
        return new ReaderDiagnostic(
                DiagnosticSeverity.INFO,
                "AUX_STORE_VERIFIED",
                storeName,
                "Verified " + (totalSectors - 1) + " data sectors (" + activeChains + " chains) in " + storeName);
    }
}
