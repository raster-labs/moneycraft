package com.raster.moneycraft.reader.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic pre-commit reconciliation proof computed across extracted masters and vouchers.
 */
public record ReconciliationSummary(
        int groupCount,
        int ledgerCount,
        int voucherTypeCount,
        int postedVoucherCount,
        int deletedVoucherCount,
        int cancelledVoucherCount,
        int optionalVoucherCount,
        BigDecimal grossDebitTurnover,
        BigDecimal grossCreditTurnover,
        BigDecimal netTrialBalanceDebit,
        BigDecimal netTrialBalanceCredit,
        BigDecimal topGroupTrialBalanceDebit,
        BigDecimal topGroupTrialBalanceCredit,
        BigDecimal closingStockValue,
        BigDecimal grossProfit,
        BigDecimal netProfit,
        boolean shadowCommitVerified,
        boolean voucherDoubleEntryBalanced,
        boolean trialBalanceReconciled,
        List<TrialBalanceLine> trialBalanceLines
) {
    public ReconciliationSummary {
        Objects.requireNonNull(grossDebitTurnover, "grossDebitTurnover must not be null");
        Objects.requireNonNull(grossCreditTurnover, "grossCreditTurnover must not be null");
        Objects.requireNonNull(netTrialBalanceDebit, "netTrialBalanceDebit must not be null");
        Objects.requireNonNull(netTrialBalanceCredit, "netTrialBalanceCredit must not be null");
        Objects.requireNonNull(topGroupTrialBalanceDebit, "topGroupTrialBalanceDebit must not be null");
        Objects.requireNonNull(topGroupTrialBalanceCredit, "topGroupTrialBalanceCredit must not be null");
        Objects.requireNonNull(closingStockValue, "closingStockValue must not be null");
        Objects.requireNonNull(grossProfit, "grossProfit must not be null");
        Objects.requireNonNull(netProfit, "netProfit must not be null");
        trialBalanceLines = trialBalanceLines == null ? List.of() : List.copyOf(trialBalanceLines);
    }
}
