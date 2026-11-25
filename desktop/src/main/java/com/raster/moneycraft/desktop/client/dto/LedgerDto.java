package com.raster.moneycraft.desktop.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record LedgerDto(
    UUID id,
    UUID companyId,
    String name,
    String aliasCode,
    UUID groupId,
    String groupName,
    String nature,
    BigDecimal openingBalance,
    String openingSide,
    String currencyCode,
    String taxType,
    String gstin,
    boolean isSystemLedger,
    long versionNo
) {
}
