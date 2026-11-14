package com.raster.moneycraft.accounting.spi;

import com.raster.moneycraft.accounting.dto.VoucherDtos;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Hexagonal SPI allowing the Inventory bounded context to participate in ACID voucher
 * posting, MCJ1-TXN-01 lock ordering, ITEM_INVOICE accounting synthesis, O2C/P2P order
 * tracking, and 8-method valuation layer synchronization without coupling accounting to inventory.
 */
public interface VoucherInventoryBridge {

    InventoryPreLockPlan resolvePreLockPlan(
        UUID companyId,
        UUID existingJournalId,
        List<VoucherDtos.VoucherInventoryLineDto> requestInventoryLines
    );

    PreparedInventoryContext lockAndPrepareInventory(InventoryPrepareCommand command);

    Set<UUID> lockJournalStockItemsAndBatches(UUID companyId, UUID journalId);

    Set<UUID> clearAndReverseJournalInventory(
        UUID companyId,
        UUID journalId,
        String priorStatus,
        String priorPostingNature,
        String baseCategory,
        boolean movesInventory
    );

    void persistAndApplyInventory(InventoryPersistCommand command);

    void activateStoredJournalInventoryOnPost(
        UUID companyId,
        UUID journalId,
        LocalDate effectiveDate,
        String baseCategory,
        boolean movesInventory,
        UUID partyLedgerId,
        String voucherNumber
    );

    void cloneInvertedInventoryOnReverse(
        UUID companyId,
        UUID originalJournalId,
        UUID reversalJournalId,
        LocalDate reversalDate,
        String baseCategory,
        boolean movesInventory,
        UUID partyLedgerId
    );

    void syncCostLayers(UUID companyId, Set<UUID> stockItemIds);

    List<VoucherDtos.VoucherInventoryLineDto> loadInventoryLines(
        UUID companyId,
        UUID journalId,
        int currencyScale
    );

    record InventoryPreLockPlan(
        Set<UUID> additionalLedgerIdsToLock,
        Set<UUID> stockItemIdsToLock
    ) {
    }

    record InventoryPrepareCommand(
        UUID companyId,
        UUID excludeJournalId,
        LocalDate voucherDate,
        LocalDate effectiveDate,
        int currencyScale,
        String baseCategory,
        boolean postsAccountingEntries,
        boolean movesInventory,
        boolean allowZeroValuedTransactions,
        String persistedView,
        String postingNature,
        String status,
        UUID partyLedgerId,
        boolean partyMaintainBillByBill,
        UUID headerAccountingLedgerId,
        String supplierInvoiceNo,
        String voucherNumberHint,
        Set<UUID> stockItemIdsToLock,
        List<VoucherDtos.VoucherLineRequest> rawAccountingLines,
        List<VoucherDtos.VoucherInventoryLineDto> rawInventoryLines
    ) {
    }

    record PreparedInventoryContext(
        List<VoucherDtos.VoucherLineRequest> effectiveAccountingLines,
        Object preparedPayload,
        Set<UUID> touchedStockItemIds
    ) {
    }

    record InventoryPersistCommand(
        UUID companyId,
        UUID journalId,
        String voucherNumber,
        LocalDate effectiveDate,
        String status,
        String postingNature,
        String baseCategory,
        boolean movesInventory,
        UUID partyLedgerId,
        String supplierInvoiceNo,
        PreparedInventoryContext context
    ) {
    }
}
