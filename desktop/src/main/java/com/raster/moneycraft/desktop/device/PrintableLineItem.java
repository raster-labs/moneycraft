package com.raster.moneycraft.desktop.device;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable line item for bounded local raw-printer compilation (DEV-001).
 * Uses exact {@link BigDecimal} values exclusively.
 */
public record PrintableLineItem(
    String description,
    BigDecimal quantity,
    String uomSymbol,
    BigDecimal rate,
    BigDecimal amount
) {
    public PrintableLineItem {
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        Objects.requireNonNull(uomSymbol, "uomSymbol must not be null");
        Objects.requireNonNull(rate, "rate must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
    }
}
