package com.raster.moneycraft.reader.verify;

import com.raster.moneycraft.reader.api.CorruptSnapshotException;
import com.raster.moneycraft.reader.format.Sector512PageReader;
import com.raster.moneycraft.reader.model.DiagnosticSeverity;
import com.raster.moneycraft.reader.model.ReaderDiagnostic;
import com.raster.moneycraft.reader.model.Sector0Header;
import com.raster.moneycraft.reader.model.StoreFileDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Verifies SHA-256 store digests, 512-byte sector alignment, and Sector-0 checksum/count invariants.
 */
public final class ChecksumVerifier {

    private final Sector512PageReader pageReader;

    public ChecksumVerifier() {
        this.pageReader = new Sector512PageReader();
    }

    public List<ReaderDiagnostic> verifyStoreDirectory(Path directory) throws IOException {
        List<Path> storeFiles = pageReader.discoverStoreFiles(directory);
        List<ReaderDiagnostic> diagnostics = new ArrayList<>();
        for (Path file : storeFiles) {
            StoreFileDescriptor descriptor = pageReader.inspectStoreFile(file);
            Sector0Header hdr = descriptor.sector0Header();
            long expectedDataSectors = descriptor.totalSectors() - 1L;
            if (!hdr.indexStore() && hdr.dataSectorCount() != expectedDataSectors) {
                throw new CorruptSnapshotException(
                        "SECTOR_COUNT_INVARIANT_FAILED",
                        "Store " + descriptor.fileName() + " dataSectorCount=" + hdr.dataSectorCount()
                                + " != expected " + expectedDataSectors);
            }
            diagnostics.add(new ReaderDiagnostic(
                    DiagnosticSeverity.INFO,
                    "SECTOR0_HEADER_VERIFIED",
                    descriptor.fileName(),
                    "Verified " + descriptor.totalSectors() + " sectors (release="
                            + hdr.releaseVersion().versionLabel() + ", sha256=" + descriptor.sha256Hex() + ")"));
        }
        return List.copyOf(diagnostics);
    }

    public boolean verifyExpectedSha256Map(Path directory, Map<String, String> expectedSha256ByFileName)
            throws IOException {
        for (Map.Entry<String, String> entry : expectedSha256ByFileName.entrySet()) {
            Path target = directory.resolve(entry.getKey());
            if (pageReader.isLicenseFile(target)) {
                continue;
            }
            if (!Files.isRegularFile(target)) {
                return false;
            }
            String actual = Sector512PageReader.sha256Hex(Files.readAllBytes(target));
            if (!actual.equalsIgnoreCase(entry.getValue())) {
                return false;
            }
        }
        return true;
    }
}
