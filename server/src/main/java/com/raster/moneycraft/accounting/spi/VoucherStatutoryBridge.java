package com.raster.moneycraft.accounting.spi;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Hexagonal SPI allowing {@code accounting} to delegate Multi-GSTIN default resolution,
 * 6-tier GST tax allocations, Tobacco Deemed MRP checks, and TDS/TCS auto-deduction
 * persistence to {@code statutory} without introducing a reverse package dependency.
 */
public interface VoucherStatutoryBridge {

    UUID resolveDefaultCompanyGstinId(UUID companyId, LocalDate effectiveDate);

    void clearJournalStatutoryAllocations(UUID companyId, UUID journalId);

    void computeAndPersistJournalStatutory(UUID companyId, UUID journalId);

    default void recomputeOpenPeriodStatutoryForLedger(UUID companyId, UUID ledgerId) {
    }
}
