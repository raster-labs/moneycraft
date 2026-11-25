package com.raster.moneycraft.desktop.client.dto;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record VoucherLineCommand(
    UUID ledgerId,
    String side,
    BigDecimal amount,
    String lineNarration
) {
    public VoucherLineCommand {
        Objects.requireNonNull(ledgerId, "ledgerId must not be null");
        Objects.requireNonNull(side, "side must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Voucher line amount must be strictly positive");
        }
    }
}
