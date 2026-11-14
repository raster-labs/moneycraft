package com.raster.moneycraft.reader.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Per-ledger closing Trial Balance verification row.
 */
public record TrialBalanceLine(
        String ledgerName,
        String parentGroupName,
        BigDecimal openingBalanceSigned,
        BigDecimal debitTurnover,
        BigDecimal creditTurnover,
        BigDecimal closingDebit,
        BigDecimal closingCredit,
        BigDecimal closingBalanceSigned
) {
    public TrialBalanceLine {
        Objects.requireNonNull(ledgerName, "ledgerName must not be null");
        Objects.requireNonNull(parentGroupName, "parentGroupName must not be null");
        Objects.requireNonNull(openingBalanceSigned, "openingBalanceSigned must not be null");
        Objects.requireNonNull(debitTurnover, "debitTurnover must not be null");
        Objects.requireNonNull(creditTurnover, "creditTurnover must not be null");
        Objects.requireNonNull(closingDebit, "closingDebit must not be null");
        Objects.requireNonNull(closingCredit, "closingCredit must not be null");
        Objects.requireNonNull(closingBalanceSigned, "closingBalanceSigned must not be null");
    }
}
