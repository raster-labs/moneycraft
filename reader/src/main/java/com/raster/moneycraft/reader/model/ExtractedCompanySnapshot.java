package com.raster.moneycraft.reader.model;

import java.util.List;
import java.util.Objects;

/**
 * Immutable canonical snapshot extracted from a TallyPrime .1800 directory and/or companion XML export.
 */
public record ExtractedCompanySnapshot(
        String snapshotSourceType,
        RawCompanyProfile companyProfile,
        List<StoreFileDescriptor> storeFiles,
        List<RawGroup> groups,
        List<RawLedger> ledgers,
        List<RawVoucherType> voucherTypes,
        List<RawUom> uoms,
        List<RawGodown> godowns,
        List<RawStockGroup> stockGroups,
        List<RawStockItem> stockItems,
        List<RawVoucher> vouchers,
        ReconciliationSummary reconciliation,
        List<ReaderDiagnostic> diagnostics
) {
    public ExtractedCompanySnapshot {
        Objects.requireNonNull(snapshotSourceType, "snapshotSourceType must not be null");
        Objects.requireNonNull(companyProfile, "companyProfile must not be null");
        storeFiles = storeFiles == null ? List.of() : List.copyOf(storeFiles);
        groups = groups == null ? List.of() : List.copyOf(groups);
        ledgers = ledgers == null ? List.of() : List.copyOf(ledgers);
        voucherTypes = voucherTypes == null ? List.of() : List.copyOf(voucherTypes);
        uoms = uoms == null ? List.of() : List.copyOf(uoms);
        godowns = godowns == null ? List.of() : List.copyOf(godowns);
        stockGroups = stockGroups == null ? List.of() : List.copyOf(stockGroups);
        stockItems = stockItems == null ? List.of() : List.copyOf(stockItems);
        vouchers = vouchers == null ? List.of() : List.copyOf(vouchers);
        Objects.requireNonNull(reconciliation, "reconciliation must not be null");
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
