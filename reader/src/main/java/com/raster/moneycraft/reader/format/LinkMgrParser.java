package com.raster.moneycraft.reader.format;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Clean-room parser for LinkMgr.1800 bill-wise reference names and relational links.
 */
public final class LinkMgrParser {

    private static final byte[] BILL_NAME_TAG = {0x02, 0x10, 0x02, 0x00, 0x00, 0x0F};

    private final Sector512PageReader pageReader;

    public LinkMgrParser(Sector512PageReader pageReader) {
        this.pageReader = pageReader;
    }

    public Map<Integer, String> parseBillReferenceNames(byte[] linkMgrBytes) {
        int totalSectors = linkMgrBytes.length / Sector512PageReader.SECTOR_BYTES;
        Map<Integer, String> billNamesById = new HashMap<>();

        for (int s = 1; s < totalSectors; s++) {
            Sector512PageReader.DataSectorHeader hdr = pageReader.readDataSectorHeader(linkMgrBytes, s);
            if (hdr.chainSectorSpan() <= 0 || hdr.masterId() <= 0) {
                continue;
            }
            byte[] payload = pageReader.reassembleStream("LinkMgr.1800", linkMgrBytes, s);
            int tagOffset = indexOfSubArray(payload, BILL_NAME_TAG);
            if (tagOffset >= 0 && tagOffset + 8 <= payload.length) {
                int byteLen = Short.toUnsignedInt(
                        ByteBuffer.wrap(payload, tagOffset + 6, 2).order(ByteOrder.LITTLE_ENDIAN).getShort());
                if (tagOffset + 8 + byteLen <= payload.length) {
                    String billName = TlvRecordDecoder.decodeUtf16LeTrimmed(payload, tagOffset + 8, byteLen);
                    if (!billName.isEmpty()) {
                        billNamesById.put(hdr.masterId(), billName);
                    }
                }
            }
        }
        return Map.copyOf(billNamesById);
    }

    private static int indexOfSubArray(byte[] haystack, byte[] needle) {
        int maxStart = haystack.length - needle.length;
        for (int i = 0; i <= maxStart; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }
}
