package com.raster.moneycraft.reader.format;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Decoded binary Tag-Length-Value (TLV) attribute from a .1800 stream.
 */
public record TlvField(
        int flags,
        int fieldId,
        int wireType,
        long[] keyPrefixes,
        long intValue,
        String stringValue,
        byte[] rawBytes,
        List<TlvField> children
) {
    /**
     * Tally serial date epoch: 1899-12-31 (Day 1 = 1900-01-01; unlike Excel, Tally has no 1900 leap-year bug).
     */
    private static final LocalDate TALLY_EPOCH = LocalDate.of(1899, 12, 31);

    public boolean isBooleanTrue() {
        return wireType == 0x0100;
    }

    public boolean isDeemedPositiveFlagged() {
        return (flags & 0x0080) != 0;
    }

    /**
     * Converts a 0x0900 int64_le scaled (10^-5) monetary value into an exact BigDecimal.
     */
    public BigDecimal toScaledAmount() {
        return BigDecimal.valueOf(intValue, 5).stripTrailingZeros().scale() < 2
                ? BigDecimal.valueOf(intValue, 5).setScale(2)
                : BigDecimal.valueOf(intValue, 5).stripTrailingZeros();
    }

    /**
     * Converts a 0x0D00 packed serial date (days since 1899-12-30) into a LocalDate, or null if 0.
     */
    public LocalDate toLocalDate() {
        if (intValue <= 0L || intValue > 200_000L) {
            return null;
        }
        return TALLY_EPOCH.plusDays(intValue);
    }
}
