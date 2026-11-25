package com.raster.moneycraft.desktop.client.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TrialBalanceReportDto(
    LocalDate fromDate,
    LocalDate toDate,
    boolean includeOptional,
    List<TrialBalanceRowDto> groupRows,
    List<TrialBalanceRowDto> ledgerRows,
    BigDecimal totalOpeningDebit,
    BigDecimal totalOpeningCredit,
    BigDecimal totalPeriodDebit,
    BigDecimal totalPeriodCredit,
    BigDecimal totalClosingDebit,
    BigDecimal totalClosingCredit,
    BigDecimal differenceAmount,
    boolean isBalanced
) {
    public TrialBalanceReportDto {
        groupRows = groupRows == null ? List.of() : List.copyOf(groupRows);
        ledgerRows = ledgerRows == null ? List.of() : List.copyOf(ledgerRows);
    }
}
