package com.raster.moneycraft.reader.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Extracted accounting/inventory voucher from TranMgr.1800 or companion XML collection.
 */
public record RawVoucher(
        int masterId,
        int alterId,
        String guid,
        String voucherTypeName,
        String voucherNumber,
        LocalDate date,
        LocalDate effectiveDate,
        String partyLedgerName,
        String reference,
        String narration,
        String placeOfSupply,
        boolean deleted,
        boolean cancelled,
        boolean optional,
        int editLogVersionCount,
        List<RawVoucherLine> ledgerLines,
        List<RawInventoryLine> inventoryLines
) {
    public RawVoucher {
        Objects.requireNonNull(guid, "guid must not be null");
        Objects.requireNonNull(voucherTypeName, "voucherTypeName must not be null");
        voucherNumber = voucherNumber == null ? "" : voucherNumber;
        partyLedgerName = partyLedgerName == null ? "" : partyLedgerName;
        reference = reference == null ? "" : reference;
        narration = narration == null ? "" : narration;
        placeOfSupply = placeOfSupply == null ? "" : placeOfSupply;
        ledgerLines = ledgerLines == null ? List.of() : List.copyOf(ledgerLines);
        inventoryLines = inventoryLines == null ? List.of() : List.copyOf(inventoryLines);
    }

    public boolean isPostedAccounting() {
        return !deleted && !cancelled && !optional && !ledgerLines.isEmpty();
    }
}
