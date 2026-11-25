package com.raster.moneycraft.desktop.client.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record VoucherDto(
    UUID id,
    UUID companyId,
    UUID periodId,
    UUID fiscalYearId,
    UUID voucherTypeId,
    String voucherTypeName,
    String voucherTypeCategory,
    String status,
    String postingNature,
    String persistedView,
    String approvalPolicy,
    String voucherNumber,
    LocalDate voucherDate,
    LocalDate effectiveDate,
    String currencyCode,
    BigDecimal exchangeRate,
    BigDecimal totalDebit,
    BigDecimal totalCredit,
    String narration,
    long versionNo,
    List<VoucherLineDto> lines
) {
    public VoucherDto {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
