package com.raster.moneycraft.desktop.client.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record UpsertVoucherCommand(
    UUID voucherTypeId,
    String voucherTypeName,
    String voucherNumber,
    LocalDate voucherDate,
    String persistedView,
    String postingNature,
    String action,
    String currencyCode,
    BigDecimal exchangeRate,
    String narration,
    Long versionNo,
    List<VoucherLineCommand> lines
) {
    public UpsertVoucherCommand {
        Objects.requireNonNull(voucherDate, "voucherDate must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Voucher must include accounting lines");
        }
    }
}
