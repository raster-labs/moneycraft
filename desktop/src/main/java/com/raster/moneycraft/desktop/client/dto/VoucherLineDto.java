package com.raster.moneycraft.desktop.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record VoucherLineDto(
    UUID id,
    int lineNo,
    String lineRole,
    UUID ledgerId,
    String ledgerName,
    String side,
    BigDecimal amount,
    String lineNarration
) {
}
