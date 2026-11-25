package com.raster.moneycraft.desktop.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record TrialBalanceRowDto(
    UUID id,
    String name,
    String nature,
    BigDecimal openingDebit,
    BigDecimal openingCredit,
    BigDecimal periodDebit,
    BigDecimal periodCredit,
    BigDecimal closingDebit,
    BigDecimal closingCredit
) {
}
