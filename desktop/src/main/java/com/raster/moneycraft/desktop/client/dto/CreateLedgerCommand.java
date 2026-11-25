package com.raster.moneycraft.desktop.client.dto;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record CreateLedgerCommand(
    String name,
    String aliasCode,
    UUID groupId,
    BigDecimal openingBalance,
    String openingSide,
    String currencyCode,
    String gstin
) {
    public CreateLedgerCommand {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Ledger name must not be blank");
        }
        openingBalance = openingBalance == null ? BigDecimal.ZERO : openingBalance;
        openingSide = openingSide == null ? "DR" : openingSide;
    }
}
