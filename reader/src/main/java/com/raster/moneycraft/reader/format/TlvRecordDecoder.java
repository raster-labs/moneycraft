package com.raster.moneycraft.reader.format;

import com.raster.moneycraft.reader.api.CorruptSnapshotException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Binary TLV record decoder for legacy .1800 company snapshot streams (G1_BINARY_FORMAT_SPEC Section 5).
 */
public final class TlvRecordDecoder {

    private static final long[] NO_KEYS = new long[0];
    private static final byte[] NO_BYTES = new byte[0];

    public List<TlvField> decodeStream(String storeName, byte[] data, int startOffset) {
        return decodeRange(storeName, data, startOffset, data.length);
    }

    public List<TlvField> decodeRange(String storeName, byte[] data, int startOffset, int limit) {
        if (startOffset < 0 || limit > data.length || startOffset > limit) {
            throw new CorruptSnapshotException(
                    "TLV_RANGE_OUT_OF_BOUNDS",
                    "Invalid TLV range [" + startOffset + ".." + limit + "] in " + storeName);
        }
        List<TlvField> fields = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int pos = startOffset;

        while (pos + 6 <= limit) {
            int flags = Short.toUnsignedInt(buf.getShort(pos));
            int fieldId = Short.toUnsignedInt(buf.getShort(pos + 2));
            int wireType = Short.toUnsignedInt(buf.getShort(pos + 4));
            if (flags == 0 && fieldId == 0 && wireType == 0) {
                break;
            }
            int tagStart = pos;
            pos += 6;

            int keyCount = ((flags & 0x0400) != 0 ? 1 : 0) + ((flags & 0x0800) != 0 ? 1 : 0);
            long[] keys = keyCount == 0 ? NO_KEYS : new long[keyCount];
            int kIdx = 0;
            if ((flags & 0x0400) != 0) {
                requireBytes(storeName, pos, 4, limit);
                keys[kIdx++] = Integer.toUnsignedLong(buf.getInt(pos));
                pos += 4;
            }
            if ((flags & 0x0800) != 0) {
                requireBytes(storeName, pos, 4, limit);
                keys[kIdx] = Integer.toUnsignedLong(buf.getInt(pos));
                pos += 4;
            }

            switch (wireType) {
                case 0x0100 -> fields.add(new TlvField(flags, fieldId, wireType, keys, 1L, null, NO_BYTES, List.of()));
                case 0x0200, 0x0400, 0x0500, 0x0D00 -> {
                    requireBytes(storeName, pos, 4, limit);
                    long u32 = Integer.toUnsignedLong(buf.getInt(pos));
                    pos += 4;
                    fields.add(new TlvField(flags, fieldId, wireType, keys, u32, null, NO_BYTES, List.of()));
                }
                case 0x0300, 0x0600 -> {
                    requireBytes(storeName, pos, 4, limit);
                    long i32 = buf.getInt(pos);
                    pos += 4;
                    fields.add(new TlvField(flags, fieldId, wireType, keys, i32, null, NO_BYTES, List.of()));
                }
                case 0x0800, 0x0900 -> {
                    requireBytes(storeName, pos, 8, limit);
                    long i64 = buf.getLong(pos);
                    pos += 8;
                    fields.add(new TlvField(flags, fieldId, wireType, keys, i64, null, NO_BYTES, List.of()));
                }
                case 0x0700, 0x0A00, 0x0B00, 0x0C00, 0x0E00 -> {
                    requireBytes(storeName, pos, 8, limit);
                    byte[] raw = Arrays.copyOfRange(data, pos, pos + 8);
                    pos += 8;
                    fields.add(new TlvField(flags, fieldId, wireType, keys, 0L, null, raw, List.of()));
                }
                case 0x0021, 0x0041 -> {
                    requireBytes(storeName, pos, 16, limit);
                    byte[] raw = Arrays.copyOfRange(data, pos, pos + 16);
                    pos += 16;
                    fields.add(new TlvField(flags, fieldId, wireType, keys, 0L, null, raw, List.of()));
                }
                case 0x0022, 0x0042 -> {
                    requireBytes(storeName, pos, 20, limit);
                    byte[] raw = Arrays.copyOfRange(data, pos, pos + 20);
                    pos += 20;
                    fields.add(new TlvField(flags, fieldId, wireType, keys, 0L, null, raw, List.of()));
                }
                case 0x0044 -> {
                    requireBytes(storeName, pos, 32, limit);
                    byte[] raw = Arrays.copyOfRange(data, pos, pos + 32);
                    pos += 32;
                    fields.add(new TlvField(flags, fieldId, wireType, keys, 0L, null, raw, List.of()));
                }
                case 0x0F00 -> {
                    requireBytes(storeName, pos, 2, limit);
                    int byteLen = Short.toUnsignedInt(buf.getShort(pos));
                    pos += 2;
                    requireBytes(storeName, pos, byteLen, limit);
                    String str = decodeUtf16LeTrimmed(data, pos, byteLen);
                    pos += byteLen;
                    fields.add(new TlvField(flags, fieldId, wireType, keys, byteLen, str, NO_BYTES, List.of()));
                }
                case 0x1000 -> {
                    requireBytes(storeName, pos, 2, limit);
                    int byteLen = Short.toUnsignedInt(buf.getShort(pos));
                    pos += 2;
                    requireBytes(storeName, pos, byteLen, limit);
                    List<TlvField> children = decodeRange(storeName, data, pos, pos + byteLen);
                    pos += byteLen;
                    fields.add(new TlvField(flags, fieldId, wireType, keys, byteLen, null, NO_BYTES, children));
                }
                default -> throw new CorruptSnapshotException(
                        "UNKNOWN_TLV_WIRE_TYPE",
                        String.format(
                                "Unknown TLV wireType=0x%04X flags=0x%04X fieldId=0x%04X at offset %d in %s",
                                wireType, flags, fieldId, tagStart, storeName));
            }
        }
        return fields;
    }

    public static String decodeUtf16LeTrimmed(byte[] bytes, int offset, int length) {
        if (length <= 0) {
            return "";
        }
        String decoded = new String(bytes, offset, length, StandardCharsets.UTF_16LE);
        int end = decoded.length();
        while (end > 0 && decoded.charAt(end - 1) == '\u0000') {
            end--;
        }
        String trimmed = decoded.substring(0, end);
        return sanitizeControlPrefix(trimmed);
    }

    public static String sanitizeControlPrefix(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int start = 0;
        while (start < value.length() && value.charAt(start) <= '\u0004') {
            start++;
        }
        return value.substring(start).trim();
    }

    private static void requireBytes(String storeName, int pos, int needed, int limit) {
        if (pos + needed > limit) {
            throw new CorruptSnapshotException(
                    "TRUNCATED_TLV_FIELD",
                    "Truncated TLV field at offset " + pos + " (needed " + needed + " bytes, limit " + limit
                            + ") in " + storeName);
        }
    }
}
