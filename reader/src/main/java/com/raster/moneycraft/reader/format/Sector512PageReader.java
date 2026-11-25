package com.raster.moneycraft.reader.format;

import com.raster.moneycraft.reader.api.CorruptSnapshotException;
import com.raster.moneycraft.reader.model.Sector0Header;
import com.raster.moneycraft.reader.model.StoreFileDescriptor;
import com.raster.moneycraft.reader.model.TallyReleaseVersion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Low-level 512-byte sector reader and Sector-0 header validator for legacy .1800 company snapshot stores.
 * Strictly ignores any file containing 'lic' (SEC-015).
 */
public final class Sector512PageReader {

    public static final int SECTOR_BYTES = 512;
    public static final int DATA_SECTOR_HEADER_BYTES = 28;
    public static final int DATA_SECTOR_PAYLOAD_BYTES = SECTOR_BYTES - DATA_SECTOR_HEADER_BYTES;
    public static final long STANDARD_SENTINEL_32 = 0xFFFFFFFFL;

    // NC-090-02 binary format interoperability literal at Sector-0 offset [13..23] of .1800 index stores
    private static final String LEGACY_INDEX_MAGIC = "Tally Index";

    public boolean isLicenseFile(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".lic") || lower.contains("lic");
    }

    public boolean isStore1800File(Path path) {
        if (isLicenseFile(path)) {
            return false;
        }
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".1800") && Files.isRegularFile(path);
    }

    public List<Path> discoverStoreFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new CorruptSnapshotException(
                    "INPUT_NOT_DIRECTORY",
                    "Snapshot input path is not a directory: " + directory);
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .filter(p -> !isLicenseFile(p))
                    .filter(this::isStore1800File)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    public StoreFileDescriptor inspectStoreFile(Path filePath) throws IOException {
        if (isLicenseFile(filePath)) {
            throw new CorruptSnapshotException(
                    "LICENSE_FILE_FORBIDDEN",
                    "SEC-015: Refusing to inspect license file: " + filePath.getFileName());
        }
        byte[] bytes = Files.readAllBytes(filePath);
        return inspectStoreBytes(filePath.getFileName().toString(), bytes);
    }

    public StoreFileDescriptor inspectStoreBytes(String fileName, byte[] bytes) {
        if (bytes.length < SECTOR_BYTES || (bytes.length % SECTOR_BYTES) != 0) {
            throw new CorruptSnapshotException(
                    "TRUNCATED_OR_MISALIGNED_SECTOR_FILE",
                    "Store " + fileName + " length " + bytes.length + " is not a non-empty multiple of 512 bytes");
        }
        long totalSectors = bytes.length / SECTOR_BYTES;
        Sector0Header header = parseSector0Header(fileName, bytes, totalSectors);
        String sha256 = sha256Hex(bytes);
        return new StoreFileDescriptor(fileName, bytes.length, totalSectors, sha256, header);
    }

    public Sector0Header parseSector0Header(String fileName, byte[] fileBytes, long totalSectors) {
        if (fileBytes.length < SECTOR_BYTES) {
            throw new CorruptSnapshotException(
                    "TRUNCATED_SECTOR0",
                    "Store " + fileName + " is smaller than 512 bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(fileBytes, 0, SECTOR_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int releaseMagic = Short.toUnsignedInt(buf.getShort(0));
        int rollingChecksum = Short.toUnsignedInt(buf.getShort(2));
        long headerSignature32 = Integer.toUnsignedLong(buf.getInt(0));
        long reserved4 = Integer.toUnsignedLong(buf.getInt(4));
        long dataSectorCount = Integer.toUnsignedLong(buf.getInt(8));
        long allocatedSectorCount = Integer.toUnsignedLong(buf.getInt(12));
        long rootDirectorySector = Integer.toUnsignedLong(buf.getInt(16));
        long highWaterSector = Integer.toUnsignedLong(buf.getInt(24));
        long activeRootCount = Integer.toUnsignedLong(buf.getInt(28));
        long sentinel32 = Integer.toUnsignedLong(buf.getInt(32));
        long schemaGeneration = Integer.toUnsignedLong(buf.getInt(104));
        long storeSubVersion = Integer.toUnsignedLong(buf.getInt(108));
        byte[] versionBytes = Arrays.copyOfRange(fileBytes, 104, 114);

        String indexMagicCandidate = new String(fileBytes, 13, 11, StandardCharsets.US_ASCII);
        boolean isIndexStore = LEGACY_INDEX_MAGIC.equals(indexMagicCandidate);

        if (isIndexStore) {
            TallyReleaseVersion indexVersion = detectIndexReleaseVersion(headerSignature32);
            return new Sector0Header(
                    fileName,
                    true,
                    releaseMagic,
                    rollingChecksum,
                    headerSignature32,
                    totalSectors - 1L,
                    allocatedSectorCount,
                    rootDirectorySector,
                    highWaterSector,
                    activeRootCount,
                    sentinel32,
                    schemaGeneration,
                    storeSubVersion,
                    versionBytes,
                    indexVersion);
        }

        if (reserved4 != 0L) {
            throw new CorruptSnapshotException(
                    "INVALID_SECTOR0_RESERVED_WORD",
                    "Store " + fileName + " has non-zero Sector-0 [4..7] word: 0x" + Long.toHexString(reserved4));
        }
        if (sentinel32 != STANDARD_SENTINEL_32) {
            throw new CorruptSnapshotException(
                    "INVALID_SECTOR0_SENTINEL",
                    "Store " + fileName + " missing 0xFFFFFFFF sentinel at [32..35]: 0x" + Long.toHexString(sentinel32));
        }
        long expectedDataSectors = totalSectors - 1L;
        if (dataSectorCount != expectedDataSectors) {
            throw new CorruptSnapshotException(
                    "SECTOR_COUNT_MISMATCH",
                    "Store " + fileName + " Sector-0 [8..11] reports " + dataSectorCount
                            + " data sectors, expected " + expectedDataSectors);
        }

        TallyReleaseVersion releaseVersion = detectStandardReleaseVersion(releaseMagic, versionBytes);
        return new Sector0Header(
                fileName,
                false,
                releaseMagic,
                rollingChecksum,
                headerSignature32,
                dataSectorCount,
                allocatedSectorCount,
                rootDirectorySector,
                highWaterSector,
                activeRootCount,
                sentinel32,
                schemaGeneration,
                storeSubVersion,
                versionBytes,
                releaseVersion);
    }

    public DataSectorHeader readDataSectorHeader(byte[] storeBytes, int sectorIndex) {
        int totalSectors = storeBytes.length / SECTOR_BYTES;
        if (sectorIndex < 1 || sectorIndex >= totalSectors) {
            throw new CorruptSnapshotException(
                    "SECTOR_OUT_OF_RANGE",
                    "Sector index " + sectorIndex + " out of bounds (1.." + (totalSectors - 1) + ")");
        }
        int base = sectorIndex * SECTOR_BYTES;
        ByteBuffer buf = ByteBuffer.wrap(storeBytes, base, DATA_SECTOR_HEADER_BYTES)
                .slice()
                .order(ByteOrder.LITTLE_ENDIAN);
        return new DataSectorHeader(
                sectorIndex,
                Integer.toUnsignedLong(buf.getInt(0)),
                buf.getInt(4),
                buf.getInt(8),
                buf.getInt(12),
                buf.getInt(16),
                buf.getInt(20),
                Integer.toUnsignedLong(buf.getInt(24)));
    }

    public byte[] reassembleStream(String storeName, byte[] storeBytes, int headSector) {
        int totalSectors = storeBytes.length / SECTOR_BYTES;
        if (headSector <= 0 || headSector >= totalSectors) {
            throw new CorruptSnapshotException(
                    "INVALID_STREAM_HEAD_SECTOR",
                    "Invalid stream head sector " + headSector + " in " + storeName);
        }
        BitSet visited = new BitSet(totalSectors);
        List<byte[]> chunks = new ArrayList<>();
        int totalBytes = 0;
        int current = headSector;
        while (current != 0) {
            if (current < 1 || current >= totalSectors) {
                throw new CorruptSnapshotException(
                        "BROKEN_SECTOR_CHAIN",
                        "Out-of-bounds nextSector pointer " + current + " in " + storeName);
            }
            if (visited.get(current)) {
                throw new CorruptSnapshotException(
                        "CYCLIC_SECTOR_CHAIN",
                        "Cyclic sector chain detected at sector " + current + " in " + storeName);
            }
            visited.set(current);
            int base = current * SECTOR_BYTES;
            ByteBuffer hdr = ByteBuffer.wrap(storeBytes, base, DATA_SECTOR_HEADER_BYTES)
                    .slice()
                    .order(ByteOrder.LITTLE_ENDIAN);
            int nextSector = hdr.getInt(12);
            byte[] chunk = Arrays.copyOfRange(storeBytes, base + DATA_SECTOR_HEADER_BYTES, base + SECTOR_BYTES);
            chunks.add(chunk);
            totalBytes += chunk.length;
            current = nextSector;
        }
        byte[] reassembled = new byte[totalBytes];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, reassembled, offset, chunk.length);
            offset += chunk.length;
        }
        return reassembled;
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable in java.base", ex);
        }
    }

    private static TallyReleaseVersion detectStandardReleaseVersion(int releaseMagic, byte[] versionBytes) {
        if (releaseMagic == 0xAD59 || releaseMagic == 0x4311) {
            return TallyReleaseVersion.REL_7_0;
        }
        if (releaseMagic == 0x0446 || releaseMagic == 0xEA0E) {
            return TallyReleaseVersion.REL_7_2;
        }
        if (versionBytes.length >= 10 && versionBytes[8] == 0x46 && versionBytes[9] == 0x6D) {
            return TallyReleaseVersion.REL_7_0;
        }
        return TallyReleaseVersion.UNKNOWN;
    }

    private static TallyReleaseVersion detectIndexReleaseVersion(long headerSignature32) {
        if (headerSignature32 == 0x3A3D4DC7L) {
            return TallyReleaseVersion.REL_7_0;
        }
        if (headerSignature32 == 0x5CC90BCBL) {
            return TallyReleaseVersion.REL_7_2;
        }
        return TallyReleaseVersion.UNKNOWN;
    }

    /**
     * Parsed 28-byte header at the start of every Data Sector (Sector >= 1).
     */
    public record DataSectorHeader(
            int sectorIndex,
            long pageChecksum,
            int masterId,
            int chainSectorSpan,
            int nextSector,
            int pageRole,
            int subStreamId,
            long streamDescriptor
    ) {
        public boolean isObjectRootHead() {
            return pageRole == 0x0B && subStreamId == -1 && chainSectorSpan > 0;
        }
    }
}
