package com.raster.moneycraft.reader.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Inventory movement line inside a voucher (ALLINVENTORYENTRIES.LIST).
 */
public record RawInventoryLine(
        String stockItemName,
        boolean deemedPositive,
        BigDecimal actualQty,
        BigDecimal billedQty,
        String uomSymbol,
        BigDecimal ratePerUnit,
        BigDecimal amountSigned,
        String godownName,
        String batchName
) {
    public RawInventoryLine {
        Objects.requireNonNull(stockItemName, "stockItemName must not be null");
        actualQty = actualQty == null ? BigDecimal.ZERO : actualQty;
        billedQty = billedQty == null ? BigDecimal.ZERO : billedQty;
        uomSymbol = uomSymbol == null ? "" : uomSymbol;
        ratePerUnit = ratePerUnit == null ? BigDecimal.ZERO : ratePerUnit;
        amountSigned = amountSigned == null ? BigDecimal.ZERO : amountSigned;
        godownName = godownName == null ? "" : godownName;
        batchName = batchName == null ? "" : batchName;
    }
}
