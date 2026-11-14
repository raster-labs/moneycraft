package com.raster.moneycraft.reader.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Double-entry accounting line inside a voucher (TranMgr sub2 / ALLLEDGERENTRIES.LIST).
 * Universal Tally sign law: negative = Debit, positive = Credit.
 */
public record RawVoucherLine(
        String ledgerName,
        boolean deemedPositive,
        boolean partyLedger,
        BigDecimal amountSigned,
        List<RawBillAllocation> billAllocations
) {
    public RawVoucherLine {
        Objects.requireNonNull(ledgerName, "ledgerName must not be null");
        Objects.requireNonNull(amountSigned, "amountSigned must not be null");
        billAllocations = billAllocations == null ? List.of() : List.copyOf(billAllocations);
    }
}
