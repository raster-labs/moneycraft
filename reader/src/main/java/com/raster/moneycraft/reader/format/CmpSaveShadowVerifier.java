package com.raster.moneycraft.reader.format;

import com.raster.moneycraft.reader.api.CorruptSnapshotException;
import com.raster.moneycraft.reader.model.DiagnosticSeverity;
import com.raster.moneycraft.reader.model.ReaderDiagnostic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Verifies the legacy .1800 clean-shutdown shadow-commit invariant:
 * CmpSave.1800 must exist and match Company.1800 byte-for-byte (MIG-002, AT-MIG-002).
 */
public final class CmpSaveShadowVerifier {

    public static final String COMPANY_STORE = "Company.1800";
    public static final String CMPSAVE_STORE = "CmpSave.1800";

    public ShadowVerificationResult verify(Path snapshotDirectory, boolean failClosed) throws IOException {
        Path companyPath = snapshotDirectory.resolve(COMPANY_STORE);
        Path cmpSavePath = snapshotDirectory.resolve(CMPSAVE_STORE);

        if (!Files.isRegularFile(companyPath)) {
            ReaderDiagnostic diagnostic = new ReaderDiagnostic(
                    DiagnosticSeverity.ERROR,
                    "MISSING_COMPANY_STORE",
                    COMPANY_STORE,
                    "Required primary company store Company.1800 is missing");
            if (failClosed) {
                throw new CorruptSnapshotException(diagnostic.code(), diagnostic.message());
            }
            return new ShadowVerificationResult(false, "", "", List.of(diagnostic));
        }

        if (!Files.isRegularFile(cmpSavePath)) {
            ReaderDiagnostic diagnostic = new ReaderDiagnostic(
                    DiagnosticSeverity.ERROR,
                    "MISSING_CMPSAVE_SHADOW",
                    CMPSAVE_STORE,
                    "Required shadow commit store CmpSave.1800 is missing (dirty or incomplete copy)");
            if (failClosed) {
                throw new CorruptSnapshotException(diagnostic.code(), diagnostic.message());
            }
            String companySha = Sector512PageReader.sha256Hex(Files.readAllBytes(companyPath));
            return new ShadowVerificationResult(false, companySha, "", List.of(diagnostic));
        }

        byte[] companyBytes = Files.readAllBytes(companyPath);
        byte[] cmpSaveBytes = Files.readAllBytes(cmpSavePath);
        String companySha = Sector512PageReader.sha256Hex(companyBytes);
        String cmpSaveSha = Sector512PageReader.sha256Hex(cmpSaveBytes);

        if (!Arrays.equals(companyBytes, cmpSaveBytes)) {
            int firstMismatchOffset = Arrays.mismatch(companyBytes, cmpSaveBytes);
            ReaderDiagnostic diagnostic = new ReaderDiagnostic(
                    DiagnosticSeverity.ERROR,
                    "DIRTY_SHUTDOWN_DETECTED",
                    CMPSAVE_STORE,
                    "CmpSave.1800 diverges from Company.1800 at byte offset " + firstMismatchOffset
                            + " (Company SHA-256=" + companySha + ", CmpSave SHA-256=" + cmpSaveSha + ")");
            if (failClosed) {
                throw new CorruptSnapshotException(diagnostic.code(), diagnostic.message());
            }
            return new ShadowVerificationResult(false, companySha, cmpSaveSha, List.of(diagnostic));
        }

        ReaderDiagnostic okDiagnostic = new ReaderDiagnostic(
                DiagnosticSeverity.INFO,
                "SHADOW_COMMIT_VERIFIED",
                CMPSAVE_STORE,
                "CmpSave.1800 matches Company.1800 byte-for-byte (SHA-256=" + companySha + ")");
        return new ShadowVerificationResult(true, companySha, cmpSaveSha, List.of(okDiagnostic));
    }

    /**
     * Outcome of CmpSave.1800 == Company.1800 shadow verification.
     */
    public record ShadowVerificationResult(
            boolean verified,
            String companySha256,
            String cmpSaveSha256,
            List<ReaderDiagnostic> diagnostics
    ) {
    }
}
