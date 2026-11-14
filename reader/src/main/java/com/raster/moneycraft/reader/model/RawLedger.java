package com.raster.moneycraft.reader.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Extracted accounting Ledger master (classId = 3 in Manager.1800 or LEDGER in XML).
 * Sign convention: negative = Debit opening balance, positive = Credit opening balance.
 */
public record RawLedger(
        int masterId,
        String guid,
        String name,
        String parentGroupName,
        BigDecimal openingBalanceSigned,
        boolean billWiseOn,
        String partyGstin,
        String stateName,
        int alterId
) {
    public RawLedger {
        Objects.requireNonNull(guid, "guid must not be null");
        Objects.requireNonNull(name, "name must not be null");
        parentGroupName = parentGroupName == null ? "Primary" : parentGroupName;
        openingBalanceSigned = openingBalanceSigned == null ? BigDecimal.ZERO : openingBalanceSigned;
        partyGstin = partyGstin == null ? "" : partyGstin;
        stateName = stateName == null ? "" : stateName;
    }
}
