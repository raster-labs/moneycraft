package com.raster.moneycraft.reader.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Bill-wise allocation line attached to a party ledger entry (LinkMgr.1800 / BILLALLOCATIONS.LIST).
 */
public record RawBillAllocation(
        String billName,
        String billType,
        BigDecimal amountSigned
) {
    public RawBillAllocation {
        Objects.requireNonNull(billName, "billName must not be null");
        billType = billType == null || billType.isBlank() ? "New Ref" : billType;
        Objects.requireNonNull(amountSigned, "amountSigned must not be null");
    }
}
