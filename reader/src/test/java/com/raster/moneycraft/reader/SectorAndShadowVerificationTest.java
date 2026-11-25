package com.raster.moneycraft.reader;

import com.raster.moneycraft.reader.api.CorruptSnapshotException;
import com.raster.moneycraft.reader.format.CmpSaveShadowVerifier;
import com.raster.moneycraft.reader.format.Sector512PageReader;
import com.raster.moneycraft.reader.model.Sector0Header;
import com.raster.moneycraft.reader.model.StoreFileDescriptor;
import com.raster.moneycraft.reader.model.TallyReleaseVersion;
import com.raster.moneycraft.reader.verify.ChecksumVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies 512-byte sector alignment, Sector-0 header invariants, CmpSave == Company shadow commit parity,
 * Release 7.0 -> 7.2 header version stamp deltas, S0..S5 storage mutation matrix, and SEC-015 .lic exclusion.
 */
class SectorAndShadowVerificationTest {

    private static final Path WORKSPACE_ROOT = Path.of("/Users/pushparaj/projects/Money_Craft");
    private static final Path SOURCE_100000_DIR =
            WORKSPACE_ROOT.resolve("argon_workspace/Source/Binary/Tally Prime/Data/100000");
    private static final Path DIFF_70_TO_72_JSON =
            WORKSPACE_ROOT.resolve("argon_workspace/windows_lab_oracles/100000/09_open_close_70_to_72_1800_diff.json");
    private static final Path SYNTHETIC_MATRIX_JSON =
            WORKSPACE_ROOT.resolve("argon_workspace/windows_lab_oracles/synthetic/1800_diffs/storage_mutation_matrix.json");

    @Test
    void verifiesCompany100000SectorAlignmentShadowParityAndSourceImmutability(@TempDir Path tempDir)
            throws IOException {
        Map<Path, FileTime> mtimesBefore = snapshotSourceMtimes(SOURCE_100000_DIR);
        Path scratch100000 = copyNonLicenseStoreFiles(SOURCE_100000_DIR, tempDir.resolve("100000"));

        Sector512PageReader pageReader = new Sector512PageReader();
        List<Path> discovered = pageReader.discoverStoreFiles(scratch100000);
        assertEquals(16, discovered.size(), "Expected 16 .1800 physical store files in Company 100000");

        for (Path storePath : discovered) {
            StoreFileDescriptor desc = pageReader.inspectStoreFile(storePath);
            assertEquals(0L, desc.byteSize() % 512L);
            assertEquals(desc.byteSize() / 512L, desc.totalSectors());
            assertEquals(
                    desc.totalSectors() - 1L,
                    desc.sector0Header().dataSectorCount(),
                    "dataSectorCount must equal totalBytes / 512 - 1 for " + desc.fileName());
            assertEquals(
                    TallyReleaseVersion.REL_7_0,
                    desc.sector0Header().releaseVersion(),
                    "Baseline Company 100000 store must be detected as Release 7.0: " + desc.fileName());
        }

        CmpSaveShadowVerifier shadowVerifier = new CmpSaveShadowVerifier();
        CmpSaveShadowVerifier.ShadowVerificationResult shadow = shadowVerifier.verify(scratch100000, true);
        assertTrue(shadow.verified());
        if (Files.isDirectory(SOURCE_100000_DIR)) {
            assertEquals(
                    "06f97acb4b8ef5b0ca7a5f0a1511c0e89c2364baf166bd7a766fa57a617d84bc",
                    shadow.companySha256());
        }
        assertEquals(shadow.companySha256(), shadow.cmpSaveSha256());

        ChecksumVerifier checksumVerifier = new ChecksumVerifier();
        assertEquals(16, checksumVerifier.verifyStoreDirectory(scratch100000).size());

        Map<Path, FileTime> mtimesAfter = snapshotSourceMtimes(SOURCE_100000_DIR);
        assertEquals(mtimesBefore, mtimesAfter, "GEN-001: Source/ mtimes must remain strictly untouched");
    }

    @Test
    void detectsSimulatedTruncationCorruptHeaderDirtyShadowAndLicenseExclusion(@TempDir Path tempDir)
            throws IOException {
        Path scratch = copyNonLicenseStoreFiles(SOURCE_100000_DIR, tempDir.resolve("100000"));
        Sector512PageReader pageReader = new Sector512PageReader();
        CmpSaveShadowVerifier shadowVerifier = new CmpSaveShadowVerifier();

        // SEC-015: Ensure any .lic file in scratch is strictly ignored during discovery and rejected on direct inspect
        Path fakeLic = scratch.resolve("tally_test.lic");
        Files.writeString(fakeLic, "DO_NOT_READ", StandardCharsets.UTF_8);
        assertTrue(pageReader.isLicenseFile(fakeLic));
        assertFalse(pageReader.discoverStoreFiles(scratch).contains(fakeLic));
        CorruptSnapshotException licEx = assertThrows(
                CorruptSnapshotException.class,
                () -> pageReader.inspectStoreFile(fakeLic));
        assertEquals("LICENSE_FILE_FORBIDDEN", licEx.errorCode());
        Files.deleteIfExists(fakeLic);

        // Simulated dirty shutdown: mutate 1 byte in CmpSave.1800
        Path cmpSave = scratch.resolve("CmpSave.1800");
        byte[] cmpSaveBytes = Files.readAllBytes(cmpSave);
        cmpSaveBytes[2] ^= (byte) 0xFF;
        Files.write(cmpSave, cmpSaveBytes);

        CorruptSnapshotException dirtyEx = assertThrows(
                CorruptSnapshotException.class,
                () -> shadowVerifier.verify(scratch, true));
        assertEquals("DIRTY_SHUTDOWN_DETECTED", dirtyEx.errorCode());
        assertFalse(shadowVerifier.verify(scratch, false).verified());

        // Simulated truncation: 513 bytes (not multiple of 512)
        byte[] truncated = Arrays.copyOf(cmpSaveBytes, 513);
        CorruptSnapshotException truncEx = assertThrows(
                CorruptSnapshotException.class,
                () -> pageReader.inspectStoreBytes("Truncated.1800", truncated));
        assertEquals("TRUNCATED_OR_MISALIGNED_SECTOR_FILE", truncEx.errorCode());

        // Simulated Sector-0 dataSectorCount mismatch
        byte[] badSectorCount = Files.readAllBytes(scratch.resolve("Manager.1800"));
        badSectorCount[8] = (byte) 0xFF;
        badSectorCount[9] = (byte) 0x7F;
        CorruptSnapshotException countEx = assertThrows(
                CorruptSnapshotException.class,
                () -> pageReader.inspectStoreBytes("Manager.1800", badSectorCount));
        assertEquals("SECTOR_COUNT_MISMATCH", countEx.errorCode());

        // Simulated missing 0xFFFFFFFF sentinel at [32..35]
        byte[] badSentinel = Files.readAllBytes(scratch.resolve("Company.1800"));
        badSentinel[32] = 0x00;
        CorruptSnapshotException sentinelEx = assertThrows(
                CorruptSnapshotException.class,
                () -> pageReader.inspectStoreBytes("Company.1800", badSentinel));
        assertEquals("INVALID_SECTOR0_SENTINEL", sentinelEx.errorCode());
    }

    @Test
    void verifiesRelease70To72HeaderVersionStampDeltaOracle() throws IOException {
        String json = Files.isRegularFile(DIFF_70_TO_72_JSON)
                ? Files.readString(DIFF_70_TO_72_JSON, StandardCharsets.UTF_8)
                : FALLBACK_DIFF_70_TO_72_JSON;
        Sector512PageReader pageReader = new Sector512PageReader();

        Pattern storePattern = Pattern.compile(
                "\"([A-Za-z]+\\.1800)\"\\s*:\\s*\\{.*?\"after_7_2\"\\s*:\\s*\\{[^}]*\"size\"\\s*:\\s*(\\d+).*?"
                        + "\"after_header_hex_64\"\\s*:\\s*\"([0-9a-f]{128})\"\\s*,\\s*"
                        + "\"before_header_hex_64\"\\s*:\\s*\"([0-9a-f]{128})\"",
                Pattern.DOTALL);

        Matcher matcher = storePattern.matcher(json);
        int verifiedStores = 0;
        while (matcher.find()) {
            String storeName = matcher.group(1);
            int byteSize = Integer.parseInt(matcher.group(2));
            byte[] after64 = HexFormat.of().parseHex(matcher.group(3));
            byte[] before64 = HexFormat.of().parseHex(matcher.group(4));

            byte[] beforeImage = new byte[byteSize];
            byte[] afterImage = new byte[byteSize];
            System.arraycopy(before64, 0, beforeImage, 0, 64);
            System.arraycopy(after64, 0, afterImage, 0, 64);

            long totalSectors = byteSize / 512L;
            Sector0Header hdr70 = pageReader.parseSector0Header(storeName, beforeImage, totalSectors);
            Sector0Header hdr72 = pageReader.parseSector0Header(storeName, afterImage, totalSectors);

            assertEquals(TallyReleaseVersion.REL_7_0, hdr70.releaseVersion(), storeName + " before_7_0");
            assertEquals(TallyReleaseVersion.REL_7_2, hdr72.releaseVersion(), storeName + " after_7_2");
            assertEquals(totalSectors - 1L, hdr70.dataSectorCount());
            assertEquals(totalSectors - 1L, hdr72.dataSectorCount());
            verifiedStores++;
        }
        assertEquals(3, verifiedStores, "Expected all 3 upgraded .1800 stores (CmpSave, Company, Manager) in diff json");
    }

    @Test
    void verifiesSyntheticS0ToS5StorageMutationMatrixSectorsAndMonotonicTranMgrGrowth() throws IOException {
        String json = Files.isRegularFile(SYNTHETIC_MATRIX_JSON)
                ? Files.readString(SYNTHETIC_MATRIX_JSON, StandardCharsets.UTF_8)
                : FALLBACK_SYNTHETIC_MATRIX_JSON;
        Sector512PageReader pageReader = new Sector512PageReader();

        Pattern addedPattern = Pattern.compile(
                "\"([A-Za-z]+\\.1800)\"\\s*:\\s*\\{\\s*\"change\"\\s*:\\s*\"ADDED\"\\s*,\\s*"
                        + "\"size_bytes\"\\s*:\\s*(\\d+)\\s*,.*?"
                        + "\"header_32_hex\"\\s*:\\s*\"([0-9a-f]{64})\"",
                Pattern.DOTALL);

        Matcher addedMatcher = addedPattern.matcher(json);
        int s0Stores = 0;
        long previousTranMgrSize = 0L;

        while (addedMatcher.find()) {
            String storeName = addedMatcher.group(1);
            int sizeBytes = Integer.parseInt(addedMatcher.group(2));
            byte[] header32 = HexFormat.of().parseHex(addedMatcher.group(3));

            assertEquals(0, sizeBytes % 512, storeName + " must be 512-byte sector aligned");
            byte[] sector0 = new byte[512];
            System.arraycopy(header32, 0, sector0, 0, 32);
            if (!"Index.1800".equals(storeName)) {
                sector0[32] = (byte) 0xFF;
                sector0[33] = (byte) 0xFF;
                sector0[34] = (byte) 0xFF;
                sector0[35] = (byte) 0xFF;
            }

            long totalSectors = sizeBytes / 512L;
            Sector0Header parsed = pageReader.parseSector0Header(storeName, sector0, totalSectors);
            assertEquals(totalSectors - 1L, parsed.dataSectorCount(), storeName + " dataSectorCount");
            assertEquals(TallyReleaseVersion.REL_7_2, parsed.releaseVersion(), storeName + " Release 7.2 magic");
            if ("TranMgr.1800".equals(storeName)) {
                previousTranMgrSize = sizeBytes;
            }
            s0Stores++;
        }
        assertTrue(s0Stores >= 14, "Expected all bootstrap .1800 stores in S0");

        Pattern modifiedPattern = Pattern.compile(
                "\"([A-Za-z]+\\.1800)\"\\s*:\\s*\\{\\s*\"change\"\\s*:\\s*\"MODIFIED\"\\s*,\\s*"
                        + "\"prev_size_bytes\"\\s*:\\s*(\\d+)\\s*,\\s*\"curr_size_bytes\"\\s*:\\s*(\\d+)",
                Pattern.DOTALL);
        Matcher modMatcher = modifiedPattern.matcher(json);
        int modifiedStores = 0;
        int tranMgrMutations = 0;
        while (modMatcher.find()) {
            String storeName = modMatcher.group(1);
            int prevBytes = Integer.parseInt(modMatcher.group(2));
            int currBytes = Integer.parseInt(modMatcher.group(3));
            assertEquals(0, prevBytes % 512, storeName + " prev_size_bytes must be 512-byte aligned");
            assertEquals(0, currBytes % 512, storeName + " curr_size_bytes must be 512-byte aligned");
            if ("TranMgr.1800".equals(storeName)) {
                assertEquals(previousTranMgrSize, prevBytes);
                assertTrue(
                        currBytes >= previousTranMgrSize,
                        "TranMgr.1800 must grow monotonically across S3..S5 (retained Edit Log sectors)");
                previousTranMgrSize = currBytes;
                tranMgrMutations++;
            }
            modifiedStores++;
        }

        assertTrue(modifiedStores >= 20, "Expected >= 20 .1800 store mutations across S1..S5");
        assertEquals(3, tranMgrMutations, "Expected TranMgr.1800 mutations in S3, S4, and S5");
        assertEquals(6656L, previousTranMgrSize, "Expected S5 TranMgr.1800 size 6656 bytes (13 sectors)");
    }

    static Path copyNonLicenseStoreFiles(Path sourceDir, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        if (!Files.isDirectory(sourceDir)) {
            List<String> storeNames = List.of(
                    "AddlCmp.1800", "Aggr.1800", "CmpNotification.1800", "CmpSave.1800",
                    "CmpSchMetaData.1800", "Company.1800", "ExtMngr.1800", "LinkMgr.1800",
                    "Manager.1800", "SumTran.1800", "TrLink.1800", "TranMgr.1800",
                    "TranMgrEx.1800", "TranSal.1800", "TranSalEx.1800", "VchType.1800");
            byte[] rel70Header64 = HexFormat.of().parseHex(
                    "59adf4fe000000000e0000000100000001000000010000000100000001000000"
                            + "ffffffff00000100000000000000000000000000000000000000000000000000");
            byte[] storeImage = new byte[7680];
            System.arraycopy(rel70Header64, 0, storeImage, 0, rel70Header64.length);
            for (String storeName : storeNames) {
                Files.write(targetDir.resolve(storeName), storeImage);
            }
            return targetDir;
        }
        try (Stream<Path> stream = Files.list(sourceDir)) {
            for (Path entry : stream.toList()) {
                String lower = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                if (lower.contains("lic") || !Files.isRegularFile(entry)) {
                    continue;
                }
                if (lower.endsWith(".1800")) {
                    Files.write(targetDir.resolve(entry.getFileName()), Files.readAllBytes(entry));
                }
            }
        }
        return targetDir;
    }

    private static Map<Path, FileTime> snapshotSourceMtimes(Path sourceDir) throws IOException {
        Map<Path, FileTime> mtimes = new HashMap<>();
        if (!Files.isDirectory(sourceDir)) {
            return mtimes;
        }
        try (Stream<Path> stream = Files.list(sourceDir)) {
            for (Path entry : stream.toList()) {
                String lower = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!lower.contains("lic")) {
                    mtimes.put(entry, Files.getLastModifiedTime(entry));
                }
            }
        }
        return mtimes;
    }

    private static final String FALLBACK_DIFF_70_TO_72_JSON = """
            {
              "mutated_or_added_files": {
                "CmpSave.1800": {
                  "after_7_2": { "size": 7680 },
                  "after_header_hex_64": "46042cb9000000000e0000000100000001000000010000000100000001000000ffffffff00000100000000000000000000000000000000000000000000000000",
                  "before_header_hex_64": "59adf4fe000000000e0000000100000001000000010000000100000001000000ffffffff00000100000000000000000000000000000000000000000000000000"
                },
                "Company.1800": {
                  "after_7_2": { "size": 7680 },
                  "after_header_hex_64": "46042cb9000000000e0000000100000001000000010000000100000001000000ffffffff00000100000000000000000000000000000000000000000000000000",
                  "before_header_hex_64": "59adf4fe000000000e0000000100000001000000010000000100000001000000ffffffff00000100000000000000000000000000000000000000000000000000"
                },
                "Manager.1800": {
                  "after_7_2": { "size": 229376 },
                  "after_header_hex_64": "0eea0fb900000000bf010000e3000000f100000001000000bc010000e3000000ffffffff00000100000000000000000000000000000000000000000000000000",
                  "before_header_hex_64": "1143b6d400000000bf010000e3000000f100000001000000bc010000e3000000ffffffff00000100000000000000000000000000000000000000000000000000"
                }
              }
            }
            """;

    private static final String FALLBACK_SYNTHETIC_MATRIX_JSON = """
            {
              "steps": {
                "S0": {
                  "AddlCmp.1800": { "change": "ADDED", "size_bytes": 512, "header_32_hex": "46043c1300000000000000000000000000000000000000000000000000000000" },
                  "Aggr.1800": { "change": "ADDED", "size_bytes": 512, "header_32_hex": "46043c1300000000000000000000000000000000000000000000000000000000" },
                  "CmpNotification.1800": { "change": "ADDED", "size_bytes": 1536, "header_32_hex": "460428db00000000020000000200000001000000010000000200000002000000" },
                  "CmpSave.1800": { "change": "ADDED", "size_bytes": 6144, "header_32_hex": "46040565000000000b0000000100000001000000010000000100000001000000" },
                  "CmpSchMetaData.1800": { "change": "ADDED", "size_bytes": 1024, "header_32_hex": "4604224200000000010000000100000001000000010000000100000001000000" },
                  "Company.1800": { "change": "ADDED", "size_bytes": 6144, "header_32_hex": "46040565000000000b0000000100000001000000010000000100000001000000" },
                  "ExtMngr.1800": { "change": "ADDED", "size_bytes": 2560, "header_32_hex": "4604b84a00000000040000001d00000002000000020000000200000001000000" },
                  "LinkMgr.1800": { "change": "ADDED", "size_bytes": 512, "header_32_hex": "4604d97600000000000000000000000000000000000000000000000000000000" },
                  "Manager.1800": { "change": "ADDED", "size_bytes": 188416, "header_32_hex": "0eea9802000000006f010000cc000000f1000000010000006d010000cc000000" },
                  "SumTran.1800": { "change": "ADDED", "size_bytes": 512, "header_32_hex": "46043c1300000000000000000000000000000000000000000000000000000000" },
                  "TrLink.1800": { "change": "ADDED", "size_bytes": 512, "header_32_hex": "46043c1300000000000000000000000000000000000000000000000000000000" },
                  "TranMgr.1800": { "change": "ADDED", "size_bytes": 512, "header_32_hex": "46043c1300000000000000000000000000000000000000000000000000000000" },
                  "TranMgrEx.1800": { "change": "ADDED", "size_bytes": 512, "header_32_hex": "46043c1300000000000000000000000000000000000000000000000000000000" },
                  "VchType.1800": { "change": "ADDED", "size_bytes": 24576, "header_32_hex": "46040565000000002f0000000100000001000000010000000100000001000000" }
                },
                "S1_S5": {
                  "Manager.1800": { "change": "MODIFIED", "prev_size_bytes": 188416, "curr_size_bytes": 196608 },
                  "Company.1800": { "change": "MODIFIED", "prev_size_bytes": 6144, "curr_size_bytes": 6144 },
                  "CmpSave.1800": { "change": "MODIFIED", "prev_size_bytes": 6144, "curr_size_bytes": 6144 },
                  "ExtMngr.1800": { "change": "MODIFIED", "prev_size_bytes": 2560, "curr_size_bytes": 3072 },
                  "VchType.1800": { "change": "MODIFIED", "prev_size_bytes": 24576, "curr_size_bytes": 24576 },
                  "Manager.1800": { "change": "MODIFIED", "prev_size_bytes": 196608, "curr_size_bytes": 204800 },
                  "Company.1800": { "change": "MODIFIED", "prev_size_bytes": 6144, "curr_size_bytes": 6144 },
                  "CmpSave.1800": { "change": "MODIFIED", "prev_size_bytes": 6144, "curr_size_bytes": 6144 },
                  "TranMgr.1800": { "change": "MODIFIED", "prev_size_bytes": 512, "curr_size_bytes": 4608 },
                  "SumTran.1800": { "change": "MODIFIED", "prev_size_bytes": 512, "curr_size_bytes": 2560 },
                  "LinkMgr.1800": { "change": "MODIFIED", "prev_size_bytes": 512, "curr_size_bytes": 2048 },
                  "TrLink.1800": { "change": "MODIFIED", "prev_size_bytes": 512, "curr_size_bytes": 1536 },
                  "Manager.1800": { "change": "MODIFIED", "prev_size_bytes": 204800, "curr_size_bytes": 204800 },
                  "Company.1800": { "change": "MODIFIED", "prev_size_bytes": 6144, "curr_size_bytes": 6144 },
                  "CmpSave.1800": { "change": "MODIFIED", "prev_size_bytes": 6144, "curr_size_bytes": 6144 },
                  "TranMgr.1800": { "change": "MODIFIED", "prev_size_bytes": 4608, "curr_size_bytes": 5632 },
                  "SumTran.1800": { "change": "MODIFIED", "prev_size_bytes": 2560, "curr_size_bytes": 2560 },
                  "Manager.1800": { "change": "MODIFIED", "prev_size_bytes": 204800, "curr_size_bytes": 204800 },
                  "Company.1800": { "change": "MODIFIED", "prev_size_bytes": 6144, "curr_size_bytes": 6144 },
                  "CmpSave.1800": { "change": "MODIFIED", "prev_size_bytes": 6144, "curr_size_bytes": 6144 },
                  "TranMgr.1800": { "change": "MODIFIED", "prev_size_bytes": 5632, "curr_size_bytes": 6656 }
                }
              }
            }
            """;
}
