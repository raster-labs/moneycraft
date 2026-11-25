package com.raster.moneycraft.desktop.client.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record VoucherSummaryDto(
    UUID id,
    LocalDate voucherDate,
    LocalDate effectiveDate,
    UUID voucherTypeId,
    String voucherTypeName,
    String voucherTypeCategory,
    String voucherNumber,
    String status,
    String postingNature,
    String persistedView,
    String primaryParticulars,
    BigDecimal totalDebit,
    BigDecimal totalCredit,
    String narration,
    long versionNo
) {
}
