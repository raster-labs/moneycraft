package com.raster.moneycraft.desktop.client.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BalanceSheetReportDto(
    LocalDate asOfDate,
    String format,
    BigDecimal totalLiabilities,
    BigDecimal totalAssets,
    BigDecimal differenceAmount,
    boolean isBalanced
) {
}
