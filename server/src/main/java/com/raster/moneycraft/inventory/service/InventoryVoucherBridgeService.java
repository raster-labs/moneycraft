package com.raster.moneycraft.inventory.service;

import com.raster.moneycraft.accounting.dto.VoucherDtos;
import com.raster.moneycraft.accounting.spi.VoucherInventoryBridge;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link VoucherInventoryBridge} to integrate Inventory Allocations, Godown/Batch
 * movements, O2C/P2P Order/Tracking progressions, ITEM_INVOICE accounting synthesis, and
 * ValuationService cost-layer synchronization into ACID voucher lifecycles.
 */
@Service
public class InventoryVoucherBridgeService implements VoucherInventoryBridge {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final MathContext HIGH_PREC = MathContext.DECIMAL128;

    private static final Set<String> INWARD_DEFAULT_CATEGORIES = Set.of(
        "PURCHASE",
        "PURCHASE_ORDER",
        "RECEIPT_NOTE",
        "CREDIT_NOTE",
        "REJECTIONS_IN",
        "MATERIAL_IN",
        "PHYSICAL_STOCK"
    );

    private static final Set<String> OUTWARD_DEFAULT_CATEGORIES = Set.of(
        "SALES",
        "SALES_ORDER",
        "DELIVERY_NOTE",
        "DEBIT_NOTE",
        "REJECTIONS_OUT",
        "MATERIAL_OUT"
    );

    private final JdbcTemplate businessJdbcTemplate;
    private final ValuationService valuationService;

    public InventoryVoucherBridgeService(
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        ValuationService valuationService
    ) {
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.valuationService = valuationService;
    }

    @Override
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public InventoryPreLockPlan resolvePreLockPlan(
        UUID companyId,
        UUID existingJournalId,
        List<VoucherDtos.VoucherInventoryLineDto> requestInventoryLines
    ) {
        Set<UUID> itemIds = new TreeSet<>(Comparator.comparing(UUID::toString));
        Set<UUID> ledgerIds = new TreeSet<>(Comparator.comparing(UUID::toString));

        if (requestInventoryLines != null) {
            for (VoucherDtos.VoucherInventoryLineDto line : requestInventoryLines) {
                if (line != null && line.stockItemId() != null) {
                    itemIds.add(line.stockItemId());
                }
                if (line != null && line.accountingLedgerId() != null) {
                    ledgerIds.add(line.accountingLedgerId());
                }
            }
        }

        if (existingJournalId != null) {
            businessJdbcTemplate.query(
                "SELECT DISTINCT stock_item_id FROM mc_inventory_allocation WHERE company_id = ? AND journal_id = ?",
                rs -> {
                    itemIds.add(rs.getObject("stock_item_id", UUID.class));
                },
                companyId,
                existingJournalId
            );
        }

        for (UUID itemId : itemIds) {
            businessJdbcTemplate.query(
                """
                SELECT default_sales_ledger_id, default_purchase_ledger_id
                  FROM mc_stock_item
                 WHERE company_id = ? AND id = ?
                """,
                rs -> {
                    UUID salesId = rs.getObject("default_sales_ledger_id", UUID.class);
                    UUID purchId = rs.getObject("default_purchase_ledger_id", UUID.class);
                    if (salesId != null) {
                        ledgerIds.add(salesId);
                    }
                    if (purchId != null) {
                        ledgerIds.add(purchId);
                    }
                },
                companyId,
                itemId
            );
        }

        return new InventoryPreLockPlan(ledgerIds, itemIds);
    }

    @Override
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public PreparedInventoryContext lockAndPrepareInventory(InventoryPrepareCommand cmd) {
        boolean hasInvLines = cmd.rawInventoryLines() != null && !cmd.rawInventoryLines().isEmpty();
        if (!hasInvLines) {
            boolean hasAccLines = cmd.rawAccountingLines() != null && !cmd.rawAccountingLines().isEmpty();
            if (!cmd.postsAccountingEntries()
                && !hasAccLines
                && !"MEMORANDUM".equals(cmd.baseCategory())
                && !"ATTENDANCE".equals(cmd.baseCategory())) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-LINES-REQUIRED",
                    "Inventory Lines Required",
                    "Voucher category " + cmd.baseCategory() + " requires at least one inventory line."
                );
            }
            return new PreparedInventoryContext(cmd.rawAccountingLines(), null, Set.of());
        }

        Map<UUID, LockedStockItem> lockedItems = lockStockItemsInOrder(cmd.companyId(), cmd.stockItemIdsToLock());
        lockItemBatchesInOrder(cmd.companyId(), lockedItems.keySet());

        Map<UUID, ValuationService.UomConversionMeta> uoms = valuationService.loadCompanyUoms(cmd.companyId());
        UUID defaultGodownId = valuationService.findMainLocationGodownId(cmd.companyId());

        List<PreparedInvLine> preparedInvLines = new ArrayList<>(cmd.rawInventoryLines().size());
        int autoLineNo = 1;

        for (int idx = 0; idx < cmd.rawInventoryLines().size(); idx++) {
            VoucherDtos.VoucherInventoryLineDto raw = cmd.rawInventoryLines().get(idx);
            if (raw == null || raw.stockItemId() == null) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-ITEM-REQUIRED",
                    "Stock Item Required",
                    "inventoryLines[" + idx + "].stockItemId is required."
                );
            }
            LockedStockItem item = lockedItems.get(raw.stockItemId());
            if (item == null || !item.active()) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-ITEM-NOT-FOUND",
                    "Stock Item Not Found or Inactive",
                    "Stock item " + raw.stockItemId() + " does not exist or is inactive."
                );
            }

            String direction = resolveDirection(raw.movementDirection(), cmd.baseCategory(), idx);
            UUID uomId = raw.uomId() != null ? raw.uomId() : item.meta().baseUomId();
            if (!uoms.containsKey(uomId)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-UOM-NOT-FOUND",
                    "UOM Not Found",
                    "UOM " + uomId + " does not exist in company."
                );
            }

            BigDecimal actualQty = ExactMoneyValidator.parseAndValidateBoundedDecimal(
                raw.actualQty(), 6, BigDecimal.ZERO, null, false, "inventoryLines[" + idx + "].actualQty"
            );
            BigDecimal billedQty = raw.billedQty() == null || raw.billedQty().isBlank()
                ? actualQty
                : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                    raw.billedQty(), 6, BigDecimal.ZERO, null, false, "inventoryLines[" + idx + "].billedQty"
                );
            if (actualQty.compareTo(BigDecimal.ZERO) <= 0 && billedQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-QTY-REQUIRED",
                    "Positive Quantity Required",
                    "inventoryLines[" + idx + "] must have actualQty > 0 or billedQty > 0."
                );
            }

            BigDecimal rate = raw.rate() == null || raw.rate().isBlank()
                ? BigDecimal.ZERO
                : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                    raw.rate(), 6, BigDecimal.ZERO, null, false, "inventoryLines[" + idx + "].rate"
                );
            BigDecimal discountPct = raw.discountPct() == null || raw.discountPct().isBlank()
                ? BigDecimal.ZERO
                : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                    raw.discountPct(), 6, BigDecimal.ZERO, HUNDRED, false, "inventoryLines[" + idx + "].discountPct"
                );

            BigDecimal amount;
            if (raw.amount() == null || raw.amount().isBlank()) {
                amount = billedQty.multiply(rate, HIGH_PREC)
                    .multiply(HUNDRED.subtract(discountPct), HIGH_PREC)
                    .divide(HUNDRED, cmd.currencyScale(), RoundingMode.HALF_UP);
            } else {
                amount = ExactMoneyValidator.parseAndValidateExactScale(
                    raw.amount(), cmd.currencyScale(), "inventoryLines[" + idx + "].amount"
                );
            }

            BigDecimal declaredMrp = raw.declaredMrp() == null || raw.declaredMrp().isBlank()
                ? null
                : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                    raw.declaredMrp(), 6, BigDecimal.ZERO, null, false, "inventoryLines[" + idx + "].declaredMrp"
                );

            BigDecimal baseActualQty = valuationService.convertToBaseUom(actualQty, uomId, item.meta(), uoms);
            BigDecimal baseBilledQty = valuationService.convertToBaseUom(billedQty, uomId, item.meta(), uoms);

            List<PreparedBatchLine> batchLines = prepareBatchLines(
                cmd.companyId(),
                cmd.voucherDate(),
                item,
                direction,
                uomId,
                actualQty,
                baseActualQty,
                baseBilledQty,
                rate,
                amount,
                defaultGodownId,
                uoms,
                Boolean.TRUE.equals(raw.allowExpiredBatch()),
                raw.batchAllocations()
            );

            int lineNo = raw.lineNo() != null && raw.lineNo() > 0 ? raw.lineNo() : autoLineNo;
            autoLineNo = Math.max(autoLineNo + 1, lineNo + 1);

            preparedInvLines.add(new PreparedInvLine(
                lineNo,
                raw.accountingLedgerId(),
                item,
                direction,
                uomId,
                actualQty,
                billedQty,
                baseActualQty,
                baseBilledQty,
                rate,
                discountPct,
                amount,
                declaredMrp,
                trimOrNull(raw.orderRefNo(), 64),
                trimOrNull(raw.trackingNo(), 64),
                raw.linkedInwardAllocationId(),
                batchLines
            ));
        }

        List<VoucherDtos.VoucherLineRequest> effectiveLines = synthesizeOrPreserveAccountingLines(
            cmd,
            preparedInvLines
        );

        Set<UUID> touchedIds = new LinkedHashSet<>(lockedItems.keySet());
        return new PreparedInventoryContext(effectiveLines, new PreparedPayload(preparedInvLines), touchedIds);
    }

    @Override
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public Set<UUID> lockJournalStockItemsAndBatches(UUID companyId, UUID journalId) {
        Set<UUID> itemIds = new TreeSet<>(Comparator.comparing(UUID::toString));
        businessJdbcTemplate.query(
            "SELECT DISTINCT stock_item_id FROM mc_inventory_allocation WHERE company_id = ? AND journal_id = ?",
            rs -> {
                itemIds.add(rs.getObject("stock_item_id", UUID.class));
            },
            companyId,
            journalId
        );
        if (!itemIds.isEmpty()) {
            lockStockItemsInOrder(companyId, itemIds);
            lockItemBatchesInOrder(companyId, itemIds);
        }
        return itemIds;
    }

    @Override
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public Set<UUID> clearAndReverseJournalInventory(
        UUID companyId,
        UUID journalId,
        String priorStatus,
        String priorPostingNature,
        String baseCategory,
        boolean movesInventory
    ) {
        Set<UUID> itemIds = lockJournalStockItemsAndBatches(companyId, journalId);
        if (itemIds.isEmpty()) {
            return Set.of();
        }

        boolean wasActive = "POSTED".equals(priorStatus) && "NORMAL".equals(priorPostingNature);
        if (wasActive) {
            reverseStoredJournalEffects(companyId, journalId, baseCategory, movesInventory);
        }

        // Unlink any downstream debit-note allocations pointing at this journal's allocations before replacement
        businessJdbcTemplate.update(
            """
            UPDATE mc_inventory_allocation
               SET linked_inward_allocation_id = NULL
             WHERE company_id = ?
               AND linked_inward_allocation_id IN (
                   SELECT id FROM mc_inventory_allocation WHERE company_id = ? AND journal_id = ?
               )
            """,
            companyId,
            companyId,
            journalId
        );
        businessJdbcTemplate.update(
            "DELETE FROM mc_order_line_fulfillment WHERE company_id = ? AND order_journal_id = ?",
            companyId,
            journalId
        );
        businessJdbcTemplate.update(
            """
            DELETE FROM mc_batch_allocation
             WHERE company_id = ?
               AND inventory_allocation_id IN (
                   SELECT id FROM mc_inventory_allocation WHERE company_id = ? AND journal_id = ?
               )
            """,
            companyId,
            companyId,
            journalId
        );
        businessJdbcTemplate.update(
            "DELETE FROM mc_inventory_allocation WHERE company_id = ? AND journal_id = ?",
            companyId,
            journalId
        );
        return itemIds;
    }

    @Override
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public void persistAndApplyInventory(InventoryPersistCommand cmd) {
        if (cmd.context() == null || !(cmd.context().preparedPayload() instanceof PreparedPayload payload)) {
            if (cmd.context() != null && !cmd.context().touchedStockItemIds().isEmpty()) {
                valuationService.syncCostLayersForItems(cmd.companyId(), cmd.context().touchedStockItemIds());
            }
            return;
        }

        boolean isActive = "POSTED".equals(cmd.status()) && "NORMAL".equals(cmd.postingNature());
        boolean isOrder = "SALES_ORDER".equals(cmd.baseCategory()) || "PURCHASE_ORDER".equals(cmd.baseCategory());

        for (PreparedInvLine line : payload.lines()) {
            UUID allocId = UUID.randomUUID();
            UUID linkedInwardId = line.linkedInwardAllocationId();
            if (linkedInwardId == null && "DEBIT_NOTE".equals(cmd.baseCategory()) && "OUTWARD".equals(line.direction())) {
                linkedInwardId = autoFindInwardAllocation(
                    cmd.companyId(),
                    cmd.journalId(),
                    line.item().meta().id(),
                    cmd.effectiveDate(),
                    cmd.partyLedgerId(),
                    cmd.supplierInvoiceNo(),
                    line.rate()
                );
            }

            String effectiveOrderRef = line.orderRefNo();
            if (isOrder && (effectiveOrderRef == null || effectiveOrderRef.isBlank())) {
                effectiveOrderRef = cmd.voucherNumber() != null ? cmd.voucherNumber() : "ORD-" + allocId.toString().substring(0, 8);
            }

            businessJdbcTemplate.update(
                """
                INSERT INTO mc_inventory_allocation (
                    id, company_id, journal_id, journal_line_id, line_no,
                    stock_item_id, movement_direction, uom_id,
                    actual_qty, billed_qty, rate, discount_pct, amount,
                    declared_mrp, order_ref_no, tracking_no, linked_inward_allocation_id
                ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                allocId,
                cmd.companyId(),
                cmd.journalId(),
                line.lineNo(),
                line.item().meta().id(),
                line.direction(),
                line.uomId(),
                line.actualQty(),
                line.billedQty(),
                line.rate(),
                line.discountPct(),
                line.amount(),
                line.declaredMrp(),
                effectiveOrderRef,
                line.trackingNo(),
                linkedInwardId
            );

            boolean skipPhysicalBatchMove = isActive && cmd.movesInventory() && isAlreadyMovedByTrackingNote(
                cmd.companyId(),
                cmd.journalId(),
                line.item().meta().id(),
                cmd.baseCategory(),
                line.trackingNo()
            );

            for (PreparedBatchLine bl : line.batchLines()) {
                UUID stockBatchId = ensureStockBatchRow(
                    cmd.companyId(),
                    line.item().meta().id(),
                    bl,
                    line.declaredMrp()
                );
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_batch_allocation (
                        id, company_id, inventory_allocation_id, godown_id, stock_batch_id,
                        batch_name, actual_qty, billed_qty, rate, amount, mfg_date, expiry_date
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    cmd.companyId(),
                    allocId,
                    bl.godownId(),
                    stockBatchId,
                    bl.batchName(),
                    bl.baseActualQty(),
                    bl.baseBilledQty(),
                    bl.rate(),
                    bl.amount(),
                    bl.mfgDate(),
                    bl.expiryDate()
                );

                if (isActive && cmd.movesInventory() && !skipPhysicalBatchMove) {
                    BigDecimal delta = "INWARD".equals(line.direction())
                        ? bl.baseActualQty()
                        : bl.baseActualQty().negate();
                    adjustStockBatchClosingQty(cmd.companyId(), stockBatchId, delta);
                }
            }

            if (isActive && isOrder) {
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_order_line_fulfillment (
                        id, company_id, order_journal_id, order_allocation_id,
                        order_ref_no, party_ledger_id, stock_item_id, due_on_date,
                        ordered_qty, delivered_received_qty, billed_qty, rejected_qty,
                        preclosed_qty, tracking_no, status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, ?, 'OPEN')
                    """,
                    UUID.randomUUID(),
                    cmd.companyId(),
                    cmd.journalId(),
                    allocId,
                    effectiveOrderRef,
                    cmd.partyLedgerId(),
                    line.item().meta().id(),
                    cmd.effectiveDate(),
                    line.baseActualQty(),
                    line.trackingNo()
                );
            } else if (isActive) {
                applyOrderFulfillmentProgression(
                    cmd.companyId(),
                    cmd.baseCategory(),
                    line.item().meta().id(),
                    line.orderRefNo(),
                    line.trackingNo(),
                    line.baseActualQty(),
                    line.baseBilledQty(),
                    BigDecimal.ONE
                );
            }
        }

        if (isActive && cmd.movesInventory()) {
            valuationService.syncCostLayersForItems(cmd.companyId(), cmd.context().touchedStockItemIds());
        }
    }

    @Override
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public void activateStoredJournalInventoryOnPost(
        UUID companyId,
        UUID journalId,
        LocalDate effectiveDate,
        String baseCategory,
        boolean movesInventory,
        UUID partyLedgerId,
        String voucherNumber
    ) {
        List<StoredAllocRow> allocs = loadStoredAllocRows(companyId, journalId);
        if (allocs.isEmpty()) {
            return;
        }
        Set<UUID> touched = new LinkedHashSet<>();
        boolean isOrder = "SALES_ORDER".equals(baseCategory) || "PURCHASE_ORDER".equals(baseCategory);
        Map<UUID, ValuationService.UomConversionMeta> uoms = valuationService.loadCompanyUoms(companyId);

        for (StoredAllocRow a : allocs) {
            touched.add(a.stockItemId());
            ValuationService.ItemValuationMeta item = valuationService.loadSingleItemMeta(companyId, a.stockItemId());
            BigDecimal baseActual = item != null
                ? valuationService.convertToBaseUom(a.actualQty(), a.uomId(), item, uoms)
                : a.actualQty();
            BigDecimal baseBilled = item != null
                ? valuationService.convertToBaseUom(a.billedQty(), a.uomId(), item, uoms)
                : a.billedQty();

            if (isOrder) {
                String ref = a.orderRefNo() != null && !a.orderRefNo().isBlank()
                    ? a.orderRefNo()
                    : (voucherNumber != null ? voucherNumber : "ORD-" + a.id().toString().substring(0, 8));
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_order_line_fulfillment (
                        id, company_id, order_journal_id, order_allocation_id,
                        order_ref_no, party_ledger_id, stock_item_id, due_on_date,
                        ordered_qty, delivered_received_qty, billed_qty, rejected_qty,
                        preclosed_qty, tracking_no, status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, ?, 'OPEN')
                    """,
                    UUID.randomUUID(),
                    companyId,
                    journalId,
                    a.id(),
                    ref,
                    partyLedgerId,
                    a.stockItemId(),
                    effectiveDate,
                    baseActual,
                    a.trackingNo()
                );
                continue;
            }

            boolean skipMove = movesInventory && isAlreadyMovedByTrackingNote(
                companyId,
                journalId,
                a.stockItemId(),
                baseCategory,
                a.trackingNo()
            );
            if (movesInventory && !skipMove) {
                List<StoredBatchRow> batches = loadStoredBatchRows(companyId, a.id());
                for (StoredBatchRow b : batches) {
                    if (b.stockBatchId() != null) {
                        BigDecimal delta = "INWARD".equals(a.direction())
                            ? b.actualQty()
                            : b.actualQty().negate();
                        adjustStockBatchClosingQty(companyId, b.stockBatchId(), delta);
                    }
                }
            }

            applyOrderFulfillmentProgression(
                companyId,
                baseCategory,
                a.stockItemId(),
                a.orderRefNo(),
                a.trackingNo(),
                baseActual,
                baseBilled,
                BigDecimal.ONE
            );
        }

        if (movesInventory && !touched.isEmpty()) {
            valuationService.syncCostLayersForItems(companyId, touched);
        }
    }

    @Override
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public void cloneInvertedInventoryOnReverse(
        UUID companyId,
        UUID originalJournalId,
        UUID reversalJournalId,
        LocalDate reversalDate,
        String baseCategory,
        boolean movesInventory,
        UUID partyLedgerId
    ) {
        List<StoredAllocRow> allocs = loadStoredAllocRows(companyId, originalJournalId);
        if (allocs.isEmpty()) {
            return;
        }
        Set<UUID> touched = new LinkedHashSet<>();

        for (StoredAllocRow a : allocs) {
            touched.add(a.stockItemId());
            String invertedDir = "INWARD".equals(a.direction()) ? "OUTWARD" : "INWARD";
            UUID newAllocId = UUID.randomUUID();

            businessJdbcTemplate.update(
                """
                INSERT INTO mc_inventory_allocation (
                    id, company_id, journal_id, journal_line_id, line_no,
                    stock_item_id, movement_direction, uom_id,
                    actual_qty, billed_qty, rate, discount_pct, amount,
                    declared_mrp, order_ref_no, tracking_no, linked_inward_allocation_id
                ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                """,
                newAllocId,
                companyId,
                reversalJournalId,
                a.lineNo(),
                a.stockItemId(),
                invertedDir,
                a.uomId(),
                a.actualQty(),
                a.billedQty(),
                a.rate(),
                a.discountPct(),
                a.amount(),
                a.declaredMrp(),
                a.orderRefNo(),
                a.trackingNo()
            );

            List<StoredBatchRow> batches = loadStoredBatchRows(companyId, a.id());
            boolean skipMove = movesInventory && isAlreadyMovedByTrackingNote(
                companyId,
                originalJournalId,
                a.stockItemId(),
                baseCategory,
                a.trackingNo()
            );

            for (StoredBatchRow b : batches) {
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_batch_allocation (
                        id, company_id, inventory_allocation_id, godown_id, stock_batch_id,
                        batch_name, actual_qty, billed_qty, rate, amount, mfg_date, expiry_date
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    companyId,
                    newAllocId,
                    b.godownId(),
                    b.stockBatchId(),
                    b.batchName(),
                    b.actualQty(),
                    b.billedQty(),
                    b.rate(),
                    b.amount(),
                    b.mfgDate(),
                    b.expiryDate()
                );

                if (movesInventory && !skipMove && b.stockBatchId() != null) {
                    BigDecimal delta = "INWARD".equals(invertedDir)
                        ? b.actualQty()
                        : b.actualQty().negate();
                    adjustStockBatchClosingQty(companyId, b.stockBatchId(), delta);
                }
            }
        }

        if (movesInventory && !touched.isEmpty()) {
            valuationService.syncCostLayersForItems(companyId, touched);
        }
    }

    @Override
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public void syncCostLayers(UUID companyId, Set<UUID> stockItemIds) {
        valuationService.syncCostLayersForItems(companyId, stockItemIds);
    }

    @Override
    public List<VoucherDtos.VoucherInventoryLineDto> loadInventoryLines(
        UUID companyId,
        UUID journalId,
        int currencyScale
    ) {
        Map<UUID, List<VoucherDtos.BatchAllocationDto>> batchesByAlloc = new HashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT ba.id, ba.inventory_allocation_id, ba.godown_id, g.name AS godown_name,
                   ba.stock_batch_id, ba.batch_name, ba.actual_qty, ba.billed_qty,
                   ba.rate, ba.amount, ba.mfg_date, ba.expiry_date
              FROM mc_batch_allocation ba
              JOIN mc_godown g ON g.company_id = ba.company_id AND g.id = ba.godown_id
             WHERE ba.company_id = ?
               AND ba.inventory_allocation_id IN (
                   SELECT id FROM mc_inventory_allocation WHERE company_id = ? AND journal_id = ?
               )
             ORDER BY ba.id ASC
            """,
            rs -> {
                UUID allocId = rs.getObject("inventory_allocation_id", UUID.class);
                batchesByAlloc.computeIfAbsent(allocId, ignored -> new ArrayList<>())
                    .add(new VoucherDtos.BatchAllocationDto(
                        rs.getObject("id", UUID.class),
                        rs.getObject("godown_id", UUID.class),
                        rs.getString("godown_name"),
                        rs.getObject("stock_batch_id", UUID.class),
                        rs.getString("batch_name"),
                        ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("actual_qty")),
                        ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("billed_qty")),
                        ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("rate")),
                        ExactMoneyValidator.formatExactScale(
                            rs.getBigDecimal("amount").setScale(currencyScale, RoundingMode.HALF_UP),
                            currencyScale
                        ),
                        rs.getObject("mfg_date", LocalDate.class),
                        rs.getObject("expiry_date", LocalDate.class),
                        null
                    ));
            },
            companyId,
            companyId,
            journalId
        );

        return businessJdbcTemplate.query(
            """
            SELECT ia.id, ia.line_no, ia.stock_item_id, si.name AS item_name,
                   ia.movement_direction, ia.uom_id, u.symbol AS uom_symbol,
                   ia.actual_qty, ia.billed_qty, ia.rate, ia.discount_pct, ia.amount,
                   ia.declared_mrp, ia.order_ref_no, ia.tracking_no, ia.linked_inward_allocation_id
              FROM mc_inventory_allocation ia
              JOIN mc_stock_item si ON si.company_id = ia.company_id AND si.id = ia.stock_item_id
              JOIN mc_uom u ON u.company_id = ia.company_id AND u.id = ia.uom_id
             WHERE ia.company_id = ? AND ia.journal_id = ?
             ORDER BY ia.line_no ASC, ia.id ASC
            """,
            (rs, rowNum) -> {
                UUID allocId = rs.getObject("id", UUID.class);
                BigDecimal mrp = rs.getBigDecimal("declared_mrp");
                return new VoucherDtos.VoucherInventoryLineDto(
                    allocId,
                    rs.getInt("line_no"),
                    null,
                    rs.getObject("stock_item_id", UUID.class),
                    rs.getString("item_name"),
                    rs.getString("movement_direction"),
                    rs.getObject("uom_id", UUID.class),
                    rs.getString("uom_symbol"),
                    ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("actual_qty")),
                    ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("billed_qty")),
                    ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("rate")),
                    ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("discount_pct")),
                    ExactMoneyValidator.formatExactScale(
                        rs.getBigDecimal("amount").setScale(currencyScale, RoundingMode.HALF_UP),
                        currencyScale
                    ),
                    mrp == null ? null : ExactMoneyValidator.formatPlainDecimal(mrp),
                    rs.getString("order_ref_no"),
                    rs.getString("tracking_no"),
                    rs.getObject("linked_inward_allocation_id", UUID.class),
                    null,
                    batchesByAlloc.getOrDefault(allocId, List.of())
                );
            },
            companyId,
            journalId
        );
    }

    // =========================================================================
    // ITEM_INVOICE Accounting Synthesis & Helper Methods
    // =========================================================================

    private List<VoucherDtos.VoucherLineRequest> synthesizeOrPreserveAccountingLines(
        InventoryPrepareCommand cmd,
        List<PreparedInvLine> invLines
    ) {
        if (!cmd.postsAccountingEntries()) {
            return cmd.rawAccountingLines() == null ? List.of() : cmd.rawAccountingLines();
        }
        List<VoucherDtos.VoucherLineRequest> supplied = cmd.rawAccountingLines() == null
            ? List.of()
            : cmd.rawAccountingLines();

        if ("SINGLE_ENTRY".equals(cmd.persistedView()) && !supplied.isEmpty()) {
            return supplied;
        }
        if (isAlreadyBalancedDoubleEntry(supplied, cmd.currencyScale())) {
            return supplied;
        }

        // Synthesize ITEM_ACCOUNTING + balancing PARTY lines for ITEM_INVOICE
        boolean isSalesSide = "SALES".equals(cmd.baseCategory()) || "DEBIT_NOTE".equals(cmd.baseCategory());
        boolean isRevenueFamily = "SALES".equals(cmd.baseCategory()) || "CREDIT_NOTE".equals(cmd.baseCategory());
        String itemSide = isSalesSide ? "CR" : "DR";
        String partySide = isSalesSide ? "DR" : "CR";

        Map<UUID, BigDecimal> itemLedgerTotals = new LinkedHashMap<>();
        for (PreparedInvLine line : invLines) {
            UUID itemLedgerId = line.accountingLedgerId();
            if (itemLedgerId == null) {
                itemLedgerId = cmd.headerAccountingLedgerId();
            }
            if (itemLedgerId == null) {
                itemLedgerId = isRevenueFamily
                    ? line.item().defaultSalesLedgerId()
                    : line.item().defaultPurchaseLedgerId();
            }
            if (itemLedgerId == null) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-LEDGER-REQUIRED",
                    "Item Accounting Ledger Required",
                    "Specify accountingLedgerId or default sales/purchase ledger on stock item '"
                        + line.item().meta().name() + "'."
                );
            }
            itemLedgerTotals.merge(itemLedgerId, line.amount(), BigDecimal::add);
        }

        List<VoucherDtos.VoucherLineRequest> synthesized = new ArrayList<>();
        BigDecimal totalDr = BigDecimal.ZERO;
        BigDecimal totalCr = BigDecimal.ZERO;
        int lineNo = 1;

        for (Map.Entry<UUID, BigDecimal> e : itemLedgerTotals.entrySet()) {
            if (e.getValue().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String amtStr = ExactMoneyValidator.formatExactScale(e.getValue(), cmd.currencyScale());
            synthesized.add(new VoucherDtos.VoucherLineRequest(
                lineNo++,
                "ITEM_ACCOUNTING",
                e.getKey(),
                itemSide,
                amtStr,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of()
            ));
            if ("DR".equals(itemSide)) {
                totalDr = totalDr.add(e.getValue());
            } else {
                totalCr = totalCr.add(e.getValue());
            }
        }

        boolean hasExplicitPartyLine = false;
        for (VoucherDtos.VoucherLineRequest extra : supplied) {
            if (extra == null || extra.ledgerId() == null) {
                continue;
            }
            if ("PARTY".equalsIgnoreCase(extra.lineRole())) {
                hasExplicitPartyLine = true;
            }
            String extraSide = extra.side() == null || extra.side().isBlank()
                ? itemSide
                : extra.side().trim().toUpperCase(Locale.ROOT);
            BigDecimal extraAmt = ExactMoneyValidator.parseAndValidateExactScale(
                extra.amount(), cmd.currencyScale(), "lines.amount"
            );
            synthesized.add(new VoucherDtos.VoucherLineRequest(
                lineNo++,
                extra.lineRole() == null ? "TAX" : extra.lineRole(),
                extra.ledgerId(),
                extraSide,
                ExactMoneyValidator.formatExactScale(extraAmt, cmd.currencyScale()),
                extra.forexCurrencyCode(),
                extra.forexAmount(),
                extra.forexRate(),
                extra.ratePct(),
                extra.computedTaxAmount(),
                extra.isGstOverridden(),
                extra.gstOverrideReason(),
                extra.lineNarration(),
                extra.billAllocations(),
                extra.costAllocations(),
                extra.bankAllocations()
            ));
            if ("DR".equals(extraSide)) {
                totalDr = totalDr.add(extraAmt);
            } else {
                totalCr = totalCr.add(extraAmt);
            }
        }

        if (!hasExplicitPartyLine) {
            if (cmd.partyLedgerId() == null) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-PARTY-REQUIRED",
                    "Party Ledger Required",
                    "partyLedgerId is required to synthesize ITEM_INVOICE accounting entries."
                );
            }
            BigDecimal netDiff = "DR".equals(partySide)
                ? totalCr.subtract(totalDr)
                : totalDr.subtract(totalCr);
            if (netDiff.compareTo(BigDecimal.ZERO) > 0) {
                String partyAmtStr = ExactMoneyValidator.formatExactScale(netDiff, cmd.currencyScale());
                List<VoucherDtos.BillAllocationDto> autoBills = List.of();
                if (cmd.partyMaintainBillByBill()) {
                    boolean isNote = "CREDIT_NOTE".equals(cmd.baseCategory())
                        || "DEBIT_NOTE".equals(cmd.baseCategory());
                    String allocType = isNote ? "ON_ACCOUNT" : "NEW_REF";
                    String billRef = cmd.supplierInvoiceNo() != null && !cmd.supplierInvoiceNo().isBlank()
                        ? cmd.supplierInvoiceNo()
                        : (cmd.voucherNumberHint() != null && !cmd.voucherNumberHint().isBlank()
                            ? cmd.voucherNumberHint()
                            : "INV-" + cmd.voucherDate() + "-" + UUID.randomUUID().toString().substring(0, 8));
                    autoBills = List.of(new VoucherDtos.BillAllocationDto(
                        null,
                        allocType,
                        isNote ? "On Account" : billRef,
                        null,
                        null,
                        partySide,
                        partyAmtStr,
                        null,
                        null,
                        null,
                        null,
                        false
                    ));
                }

                List<VoucherDtos.VoucherLineRequest> withPartyFirst = new ArrayList<>(synthesized.size() + 1);
                withPartyFirst.add(new VoucherDtos.VoucherLineRequest(
                    1,
                    "PARTY",
                    cmd.partyLedgerId(),
                    partySide,
                    partyAmtStr,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    autoBills,
                    List.of(),
                    List.of()
                ));
                int renum = 2;
                for (VoucherDtos.VoucherLineRequest s : synthesized) {
                    withPartyFirst.add(new VoucherDtos.VoucherLineRequest(
                        renum++,
                        s.lineRole(),
                        s.ledgerId(),
                        s.side(),
                        s.amount(),
                        s.forexCurrencyCode(),
                        s.forexAmount(),
                        s.forexRate(),
                        s.ratePct(),
                        s.computedTaxAmount(),
                        s.isGstOverridden(),
                        s.gstOverrideReason(),
                        s.lineNarration(),
                        s.billAllocations(),
                        s.costAllocations(),
                        s.bankAllocations()
                    ));
                }
                return withPartyFirst;
            }
        }

        return synthesized;
    }

    private boolean isAlreadyBalancedDoubleEntry(List<VoucherDtos.VoucherLineRequest> lines, int scale) {
        if (lines == null || lines.size() < 2) {
            return false;
        }
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (VoucherDtos.VoucherLineRequest l : lines) {
            if (l == null || l.side() == null || l.amount() == null || l.amount().isBlank()) {
                return false;
            }
            try {
                BigDecimal amt = new BigDecimal(l.amount().trim()).setScale(scale, RoundingMode.UNNECESSARY);
                if ("DR".equalsIgnoreCase(l.side().trim())) {
                    dr = dr.add(amt);
                } else if ("CR".equalsIgnoreCase(l.side().trim())) {
                    cr = cr.add(amt);
                } else {
                    return false;
                }
            } catch (RuntimeException ex) {
                return false;
            }
        }
        return dr.compareTo(BigDecimal.ZERO) > 0 && dr.compareTo(cr) == 0;
    }

    private List<PreparedBatchLine> prepareBatchLines(
        UUID companyId,
        LocalDate voucherDate,
        LockedStockItem item,
        String direction,
        UUID lineUomId,
        BigDecimal lineActualQty,
        BigDecimal baseActualQty,
        BigDecimal baseBilledQty,
        BigDecimal lineRate,
        BigDecimal lineAmount,
        UUID defaultGodownId,
        Map<UUID, ValuationService.UomConversionMeta> uoms,
        boolean lineAllowExpiredBatch,
        List<VoucherDtos.BatchAllocationDto> rawBatches
    ) {
        if (rawBatches == null || rawBatches.isEmpty()) {
            checkBatchExpiryIfOutward(
                companyId,
                voucherDate,
                item,
                direction,
                defaultGodownId,
                "Primary Batch",
                null,
                lineAllowExpiredBatch
            );
            return List.of(new PreparedBatchLine(
                defaultGodownId,
                "Primary Batch",
                baseActualQty,
                baseBilledQty,
                lineRate,
                lineAmount,
                null,
                null
            ));
        }

        BigDecimal rawSumActual = BigDecimal.ZERO;
        for (VoucherDtos.BatchAllocationDto b : rawBatches) {
            if (b != null && b.actualQty() != null && !b.actualQty().isBlank()) {
                rawSumActual = rawSumActual.add(new BigDecimal(b.actualQty().trim()));
            }
        }
        boolean batchesInLineUom = rawSumActual.compareTo(lineActualQty) == 0;

        List<PreparedBatchLine> list = new ArrayList<>(rawBatches.size());
        for (int i = 0; i < rawBatches.size(); i++) {
            VoucherDtos.BatchAllocationDto b = rawBatches.get(i);
            UUID godownId = b.godownId() != null ? b.godownId() : defaultGodownId;
            assertGodownExists(companyId, godownId);
            String batchName = b.batchName() == null || b.batchName().isBlank()
                ? "Primary Batch"
                : b.batchName().trim();

            BigDecimal bActualRaw = ExactMoneyValidator.parseAndValidateBoundedDecimal(
                b.actualQty(), 6, BigDecimal.ZERO, null, false, "batchAllocations[" + i + "].actualQty"
            );
            BigDecimal bBilledRaw = b.billedQty() == null || b.billedQty().isBlank()
                ? bActualRaw
                : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                    b.billedQty(), 6, BigDecimal.ZERO, null, false, "batchAllocations[" + i + "].billedQty"
                );
            BigDecimal bBaseActual = batchesInLineUom
                ? valuationService.convertToBaseUom(bActualRaw, lineUomId, item.meta(), uoms)
                : ValuationService.normalizeQtyScale6(bActualRaw);
            BigDecimal bBaseBilled = batchesInLineUom
                ? valuationService.convertToBaseUom(bBilledRaw, lineUomId, item.meta(), uoms)
                : ValuationService.normalizeQtyScale6(bBilledRaw);

            BigDecimal bRate = b.rate() == null || b.rate().isBlank()
                ? lineRate
                : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                    b.rate(), 6, BigDecimal.ZERO, null, false, "batchAllocations[" + i + "].rate"
                );
            BigDecimal bAmt = b.amount() == null || b.amount().isBlank()
                ? (rawBatches.size() == 1
                    ? lineAmount
                    : bBaseBilled.multiply(bRate, HIGH_PREC).setScale(lineAmount.scale(), RoundingMode.HALF_UP))
                : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                    b.amount(), 6, BigDecimal.ZERO, null, false, "batchAllocations[" + i + "].amount"
                );

            if (b.mfgDate() != null && b.expiryDate() != null && b.expiryDate().isBefore(b.mfgDate())) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-BATCH-DATE-ORDER",
                    "Invalid Batch Dates",
                    "Batch expiryDate must be on or after mfgDate."
                );
            }

            checkBatchExpiryIfOutward(
                companyId,
                voucherDate,
                item,
                direction,
                godownId,
                batchName,
                b.expiryDate(),
                lineAllowExpiredBatch || Boolean.TRUE.equals(b.allowExpiredBatch())
            );

            list.add(new PreparedBatchLine(
                godownId,
                batchName,
                bBaseActual,
                bBaseBilled,
                bRate,
                bAmt,
                b.mfgDate(),
                b.expiryDate()
            ));
        }
        return list;
    }

    private void checkBatchExpiryIfOutward(
        UUID companyId,
        LocalDate voucherDate,
        LockedStockItem item,
        String direction,
        UUID godownId,
        String batchName,
        LocalDate requestExpiryDate,
        boolean allowExpired
    ) {
        if (!item.useExpiryDates() || !"OUTWARD".equals(direction) || allowExpired) {
            return;
        }
        LocalDate effectiveExpiry = requestExpiryDate;
        if (effectiveExpiry == null) {
            List<LocalDate> existingExp = businessJdbcTemplate.query(
                """
                SELECT expiry_date FROM mc_stock_batch
                 WHERE company_id = ? AND stock_item_id = ? AND godown_id = ? AND batch_name = ?
                """,
                (rs, rowNum) -> rs.getObject("expiry_date", LocalDate.class),
                companyId,
                item.meta().id(),
                godownId,
                batchName
            );
            if (!existingExp.isEmpty()) {
                effectiveExpiry = existingExp.getFirst();
            }
        }
        if (effectiveExpiry != null && effectiveExpiry.isBefore(voucherDate)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-BATCH-EXPIRED",
                "Batch Expired",
                "Batch '" + batchName + "' of item '" + item.meta().name()
                    + "' expired on " + effectiveExpiry + " before voucher date " + voucherDate + "."
            );
        }
    }

    private void reverseStoredJournalEffects(
        UUID companyId,
        UUID journalId,
        String baseCategory,
        boolean movesInventory
    ) {
        boolean isOrder = "SALES_ORDER".equals(baseCategory) || "PURCHASE_ORDER".equals(baseCategory);
        if (isOrder) {
            return;
        }
        List<StoredAllocRow> allocs = loadStoredAllocRows(companyId, journalId);
        Map<UUID, ValuationService.UomConversionMeta> uoms = valuationService.loadCompanyUoms(companyId);

        for (StoredAllocRow a : allocs) {
            boolean skipMove = movesInventory && isAlreadyMovedByTrackingNote(
                companyId,
                journalId,
                a.stockItemId(),
                baseCategory,
                a.trackingNo()
            );
            if (movesInventory && !skipMove) {
                List<StoredBatchRow> batches = loadStoredBatchRows(companyId, a.id());
                for (StoredBatchRow b : batches) {
                    if (b.stockBatchId() != null) {
                        BigDecimal revDelta = "INWARD".equals(a.direction())
                            ? b.actualQty().negate()
                            : b.actualQty();
                        adjustStockBatchClosingQty(companyId, b.stockBatchId(), revDelta);
                    }
                }
            }

            ValuationService.ItemValuationMeta item = valuationService.loadSingleItemMeta(companyId, a.stockItemId());
            BigDecimal baseActual = item != null
                ? valuationService.convertToBaseUom(a.actualQty(), a.uomId(), item, uoms)
                : a.actualQty();
            BigDecimal baseBilled = item != null
                ? valuationService.convertToBaseUom(a.billedQty(), a.uomId(), item, uoms)
                : a.billedQty();

            applyOrderFulfillmentProgression(
                companyId,
                baseCategory,
                a.stockItemId(),
                a.orderRefNo(),
                a.trackingNo(),
                baseActual,
                baseBilled,
                BigDecimal.ONE.negate()
            );
        }
    }

    private void applyOrderFulfillmentProgression(
        UUID companyId,
        String baseCategory,
        UUID stockItemId,
        String orderRefNo,
        String trackingNo,
        BigDecimal baseActualQty,
        BigDecimal baseBilledQty,
        BigDecimal sign
    ) {
        if ((orderRefNo == null || orderRefNo.isBlank()) && (trackingNo == null || trackingNo.isBlank())) {
            return;
        }

        List<OrderFulfillRow> targets = findMatchingOrderLines(companyId, stockItemId, orderRefNo, trackingNo);
        if (targets.isEmpty()) {
            return;
        }
        OrderFulfillRow row = targets.getFirst();

        BigDecimal deliv = row.deliveredReceivedQty();
        BigDecimal billed = row.billedQty();
        BigDecimal rejected = row.rejectedQty();
        String newTracking = row.trackingNo();

        switch (baseCategory) {
            case "DELIVERY_NOTE", "RECEIPT_NOTE" -> {
                deliv = deliv.add(baseActualQty.multiply(sign)).max(BigDecimal.ZERO);
                if (sign.signum() > 0 && trackingNo != null && !trackingNo.isBlank()) {
                    newTracking = trackingNo;
                }
            }
            case "SALES", "PURCHASE" -> {
                billed = billed.add(baseBilledQty.multiply(sign)).max(BigDecimal.ZERO);
                if (trackingNo == null || trackingNo.isBlank()) {
                    deliv = deliv.add(baseActualQty.multiply(sign)).max(BigDecimal.ZERO);
                }
            }
            case "REJECTIONS_IN", "REJECTIONS_OUT" -> {
                rejected = rejected.add(baseActualQty.multiply(sign)).max(BigDecimal.ZERO);
            }
            case "CREDIT_NOTE", "DEBIT_NOTE" -> {
                billed = billed.subtract(baseBilledQty.multiply(sign)).max(BigDecimal.ZERO);
                deliv = deliv.subtract(baseActualQty.multiply(sign)).max(BigDecimal.ZERO);
            }
            default -> {
                return;
            }
        }

        BigDecimal netDeliv = deliv.subtract(rejected).max(BigDecimal.ZERO);
        boolean fullyDelivered = netDeliv.add(row.preclosedQty()).compareTo(row.orderedQty()) >= 0;
        boolean fullyBilled = billed.add(row.preclosedQty()).compareTo(row.orderedQty()) >= 0;
        String nextStatus;
        if ("PRECLOSED".equals(row.status())) {
            nextStatus = "PRECLOSED";
        } else if (fullyDelivered && fullyBilled) {
            nextStatus = "FULFILLED";
        } else if (netDeliv.compareTo(BigDecimal.ZERO) > 0 || billed.compareTo(BigDecimal.ZERO) > 0) {
            nextStatus = "PARTIAL";
        } else {
            nextStatus = "OPEN";
        }

        businessJdbcTemplate.update(
            """
            UPDATE mc_order_line_fulfillment
               SET delivered_received_qty = ?,
                   billed_qty = ?,
                   rejected_qty = ?,
                   tracking_no = ?,
                   status = ?,
                   updated_at = clock_timestamp()
             WHERE company_id = ? AND id = ?
            """,
            deliv,
            billed,
            rejected,
            newTracking,
            nextStatus,
            companyId,
            row.id()
        );
    }

    private List<OrderFulfillRow> findMatchingOrderLines(
        UUID companyId,
        UUID stockItemId,
        String orderRefNo,
        String trackingNo
    ) {
        if (orderRefNo != null && !orderRefNo.isBlank()) {
            List<OrderFulfillRow> byOrder = businessJdbcTemplate.query(
                """
                SELECT id, ordered_qty, delivered_received_qty, billed_qty,
                       rejected_qty, preclosed_qty, tracking_no, status
                  FROM mc_order_line_fulfillment
                 WHERE company_id = ? AND stock_item_id = ? AND lower(order_ref_no) = lower(?)
                 ORDER BY updated_at ASC
                   FOR UPDATE
                """,
                (rs, rowNum) -> mapOrderRow(rs),
                companyId,
                stockItemId,
                orderRefNo.trim()
            );
            if (!byOrder.isEmpty()) {
                return byOrder;
            }
        }
        if (trackingNo != null && !trackingNo.isBlank()) {
            return businessJdbcTemplate.query(
                """
                SELECT id, ordered_qty, delivered_received_qty, billed_qty,
                       rejected_qty, preclosed_qty, tracking_no, status
                  FROM mc_order_line_fulfillment
                 WHERE company_id = ? AND stock_item_id = ? AND lower(tracking_no) = lower(?)
                 ORDER BY updated_at ASC
                   FOR UPDATE
                """,
                (rs, rowNum) -> mapOrderRow(rs),
                companyId,
                stockItemId,
                trackingNo.trim()
            );
        }
        return List.of();
    }

    private static OrderFulfillRow mapOrderRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderFulfillRow(
            rs.getObject("id", UUID.class),
            rs.getBigDecimal("ordered_qty"),
            rs.getBigDecimal("delivered_received_qty"),
            rs.getBigDecimal("billed_qty"),
            rs.getBigDecimal("rejected_qty"),
            rs.getBigDecimal("preclosed_qty"),
            rs.getString("tracking_no"),
            rs.getString("status")
        );
    }

    private boolean isAlreadyMovedByTrackingNote(
        UUID companyId,
        UUID currentJournalId,
        UUID stockItemId,
        String currentCategory,
        String trackingNo
    ) {
        if (trackingNo == null || trackingNo.isBlank()) {
            return false;
        }
        String noteCategory;
        if ("SALES".equals(currentCategory)) {
            noteCategory = "DELIVERY_NOTE";
        } else if ("PURCHASE".equals(currentCategory)) {
            noteCategory = "RECEIPT_NOTE";
        } else {
            return false;
        }

        Long cnt = businessJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM mc_inventory_allocation ia
              JOIN mc_journal j ON j.company_id = ia.company_id AND j.id = ia.journal_id
              JOIN mc_voucher_type vt ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
             WHERE ia.company_id = ?
               AND ia.journal_id <> ?
               AND ia.stock_item_id = ?
               AND lower(ia.tracking_no) = lower(?)
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND vt.base_category = ?
            """,
            Long.class,
            companyId,
            currentJournalId,
            stockItemId,
            trackingNo.trim(),
            noteCategory
        );
        return cnt != null && cnt > 0;
    }

    private UUID autoFindInwardAllocation(
        UUID companyId,
        UUID excludeJournalId,
        UUID stockItemId,
        LocalDate effectiveDate,
        UUID partyLedgerId,
        String supplierInvoiceNo,
        BigDecimal returnRate
    ) {
        if (supplierInvoiceNo != null && !supplierInvoiceNo.isBlank()) {
            List<UUID> byInv = businessJdbcTemplate.query(
                """
                SELECT ia.id
                  FROM mc_inventory_allocation ia
                  JOIN mc_journal j ON j.company_id = ia.company_id AND j.id = ia.journal_id
                  JOIN mc_voucher_type vt ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
                 WHERE ia.company_id = ?
                   AND ia.journal_id <> ?
                   AND ia.stock_item_id = ?
                   AND ia.movement_direction = 'INWARD'
                   AND j.status = 'POSTED'
                   AND j.posting_nature = 'NORMAL'
                   AND vt.base_category IN ('PURCHASE', 'RECEIPT_NOTE')
                   AND j.effective_date <= ?
                   AND (lower(COALESCE(j.supplier_invoice_no, '')) = lower(?)
                        OR lower(COALESCE(j.voucher_number, '')) = lower(?))
                 ORDER BY j.effective_date DESC, ia.id DESC
                 LIMIT 1
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                companyId,
                excludeJournalId,
                stockItemId,
                effectiveDate,
                supplierInvoiceNo.trim(),
                supplierInvoiceNo.trim()
            );
            if (!byInv.isEmpty()) {
                return byInv.getFirst();
            }
        }

        List<UUID> byRate = businessJdbcTemplate.query(
            """
            SELECT ia.id
              FROM mc_inventory_allocation ia
              JOIN mc_journal j ON j.company_id = ia.company_id AND j.id = ia.journal_id
              JOIN mc_voucher_type vt ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
             WHERE ia.company_id = ?
               AND ia.journal_id <> ?
               AND ia.stock_item_id = ?
               AND ia.movement_direction = 'INWARD'
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND vt.base_category IN ('PURCHASE', 'RECEIPT_NOTE')
               AND j.effective_date <= ?
               AND (?::uuid IS NULL OR j.party_ledger_id = ?::uuid)
               AND ia.rate = ?
             ORDER BY j.effective_date DESC, ia.id DESC
             LIMIT 1
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            excludeJournalId,
            stockItemId,
            effectiveDate,
            partyLedgerId,
            partyLedgerId,
            returnRate
        );
        return byRate.isEmpty() ? null : byRate.getFirst();
    }

    private UUID ensureStockBatchRow(
        UUID companyId,
        UUID stockItemId,
        PreparedBatchLine bl,
        BigDecimal declaredMrp
    ) {
        List<UUID> existing = businessJdbcTemplate.query(
            """
            SELECT id FROM mc_stock_batch
             WHERE company_id = ? AND stock_item_id = ? AND godown_id = ? AND batch_name = ?
               FOR UPDATE
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            stockItemId,
            bl.godownId(),
            bl.batchName()
        );
        if (!existing.isEmpty()) {
            UUID batchId = existing.getFirst();
            if (bl.mfgDate() != null || bl.expiryDate() != null || declaredMrp != null) {
                businessJdbcTemplate.update(
                    """
                    UPDATE mc_stock_batch
                       SET mfg_date = COALESCE(?, mfg_date),
                           expiry_date = COALESCE(?, expiry_date),
                           mrp_per_unit = COALESCE(?, mrp_per_unit),
                           updated_at = clock_timestamp()
                     WHERE company_id = ? AND id = ?
                    """,
                    bl.mfgDate(),
                    bl.expiryDate(),
                    declaredMrp,
                    companyId,
                    batchId
                );
            }
            return batchId;
        }

        UUID batchId = UUID.randomUUID();
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_stock_batch (
                id, company_id, stock_item_id, godown_id, batch_name,
                mfg_date, expiry_date, mrp_per_unit, opening_qty, closing_qty, version_no
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 1)
            """,
            batchId,
            companyId,
            stockItemId,
            bl.godownId(),
            bl.batchName(),
            bl.mfgDate(),
            bl.expiryDate(),
            declaredMrp
        );
        return batchId;
    }

    private void adjustStockBatchClosingQty(UUID companyId, UUID stockBatchId, BigDecimal delta) {
        if (delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        businessJdbcTemplate.update(
            """
            UPDATE mc_stock_batch
               SET closing_qty = closing_qty + ?,
                   version_no = version_no + 1,
                   updated_at = clock_timestamp()
             WHERE company_id = ? AND id = ?
            """,
            delta,
            companyId,
            stockBatchId
        );
    }

    private Map<UUID, LockedStockItem> lockStockItemsInOrder(UUID companyId, Set<UUID> itemIds) {
        Map<UUID, LockedStockItem> map = new LinkedHashMap<>();
        List<UUID> sorted = itemIds.stream()
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
        for (UUID itemId : sorted) {
            List<LockedStockItem> rows = businessJdbcTemplate.query(
                """
                SELECT si.id, si.name, si.stock_group_id, sg.name AS group_name, si.hsn_sac_code,
                       si.base_uom_id, u.symbol AS base_uom_symbol,
                       si.alternate_uom_id, si.conversion_numerator, si.conversion_denominator,
                       si.costing_method, si.standard_cost,
                       si.opening_qty, si.opening_rate, si.opening_value,
                       si.use_expiry_dates, si.default_sales_ledger_id, si.default_purchase_ledger_id, si.active
                  FROM mc_stock_item si
                  JOIN mc_uom u ON u.company_id = si.company_id AND u.id = si.base_uom_id
                  LEFT JOIN mc_stock_group sg ON sg.company_id = si.company_id AND sg.id = si.stock_group_id
                 WHERE si.company_id = ? AND si.id = ?
                   FOR UPDATE OF si
                """,
                (rs, rowNum) -> new LockedStockItem(
                    new ValuationService.ItemValuationMeta(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getObject("stock_group_id", UUID.class),
                        rs.getString("group_name"),
                        rs.getString("hsn_sac_code"),
                        rs.getObject("base_uom_id", UUID.class),
                        rs.getString("base_uom_symbol"),
                        rs.getObject("alternate_uom_id", UUID.class),
                        rs.getBigDecimal("conversion_numerator"),
                        rs.getBigDecimal("conversion_denominator"),
                        rs.getString("costing_method"),
                        rs.getBigDecimal("standard_cost"),
                        rs.getBigDecimal("opening_qty"),
                        rs.getBigDecimal("opening_rate"),
                        rs.getBigDecimal("opening_value")
                    ),
                    rs.getBoolean("use_expiry_dates"),
                    rs.getObject("default_sales_ledger_id", UUID.class),
                    rs.getObject("default_purchase_ledger_id", UUID.class),
                    rs.getBoolean("active")
                ),
                companyId,
                itemId
            );
            if (!rows.isEmpty()) {
                map.put(itemId, rows.getFirst());
            }
        }
        return map;
    }

    private void lockItemBatchesInOrder(UUID companyId, Set<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return;
        }
        List<UUID> batchIds = new ArrayList<>();
        for (UUID itemId : itemIds) {
            batchIds.addAll(businessJdbcTemplate.query(
                "SELECT id FROM mc_stock_batch WHERE company_id = ? AND stock_item_id = ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                companyId,
                itemId
            ));
        }
        batchIds.sort(Comparator.comparing(UUID::toString));
        for (UUID batchId : batchIds) {
            businessJdbcTemplate.query(
                "SELECT id FROM mc_stock_batch WHERE company_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                companyId,
                batchId
            );
        }
    }

    private void assertGodownExists(UUID companyId, UUID godownId) {
        Long cnt = businessJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM mc_godown WHERE company_id = ? AND id = ? AND active = true",
            Long.class,
            companyId,
            godownId
        );
        if (cnt == null || cnt == 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-GODOWN-NOT-FOUND",
                "Godown Not Found",
                "Godown " + godownId + " does not exist in company."
            );
        }
    }

    private static String resolveDirection(String rawDir, String baseCategory, int idx) {
        if (rawDir != null && !rawDir.isBlank()) {
            String norm = rawDir.trim().toUpperCase(Locale.ROOT);
            if (!"INWARD".equals(norm) && !"OUTWARD".equals(norm)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-DIRECTION-INVALID",
                    "Invalid Movement Direction",
                    "inventoryLines[" + idx + "].movementDirection must be INWARD or OUTWARD."
                );
            }
            return norm;
        }
        if (INWARD_DEFAULT_CATEGORIES.contains(baseCategory)) {
            return "INWARD";
        }
        if (OUTWARD_DEFAULT_CATEGORIES.contains(baseCategory)) {
            return "OUTWARD";
        }
        throw new MoneyCraftException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "MC-INV-DIRECTION-REQUIRED",
            "Movement Direction Required",
            "movementDirection (INWARD or OUTWARD) is required on " + baseCategory + " vouchers."
        );
    }

    private List<StoredAllocRow> loadStoredAllocRows(UUID companyId, UUID journalId) {
        return businessJdbcTemplate.query(
            """
            SELECT id, line_no, stock_item_id, movement_direction, uom_id,
                   actual_qty, billed_qty, rate, discount_pct, amount,
                   declared_mrp, order_ref_no, tracking_no
              FROM mc_inventory_allocation
             WHERE company_id = ? AND journal_id = ?
             ORDER BY line_no ASC, id ASC
            """,
            (rs, rowNum) -> new StoredAllocRow(
                rs.getObject("id", UUID.class),
                rs.getInt("line_no"),
                rs.getObject("stock_item_id", UUID.class),
                rs.getString("movement_direction"),
                rs.getObject("uom_id", UUID.class),
                rs.getBigDecimal("actual_qty"),
                rs.getBigDecimal("billed_qty"),
                rs.getBigDecimal("rate"),
                rs.getBigDecimal("discount_pct"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("declared_mrp"),
                rs.getString("order_ref_no"),
                rs.getString("tracking_no")
            ),
            companyId,
            journalId
        );
    }

    private List<StoredBatchRow> loadStoredBatchRows(UUID companyId, UUID inventoryAllocationId) {
        return businessJdbcTemplate.query(
            """
            SELECT godown_id, stock_batch_id, batch_name, actual_qty, billed_qty,
                   rate, amount, mfg_date, expiry_date
              FROM mc_batch_allocation
             WHERE company_id = ? AND inventory_allocation_id = ?
             ORDER BY id ASC
            """,
            (rs, rowNum) -> new StoredBatchRow(
                rs.getObject("godown_id", UUID.class),
                rs.getObject("stock_batch_id", UUID.class),
                rs.getString("batch_name"),
                rs.getBigDecimal("actual_qty"),
                rs.getBigDecimal("billed_qty"),
                rs.getBigDecimal("rate"),
                rs.getBigDecimal("amount"),
                rs.getObject("mfg_date", LocalDate.class),
                rs.getObject("expiry_date", LocalDate.class)
            ),
            companyId,
            inventoryAllocationId
        );
    }

    private static String trimOrNull(String raw, int maxLen) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        return t.length() > maxLen ? t.substring(0, maxLen) : t;
    }

    private record LockedStockItem(
        ValuationService.ItemValuationMeta meta,
        boolean useExpiryDates,
        UUID defaultSalesLedgerId,
        UUID defaultPurchaseLedgerId,
        boolean active
    ) {
    }

    private record PreparedBatchLine(
        UUID godownId,
        String batchName,
        BigDecimal baseActualQty,
        BigDecimal baseBilledQty,
        BigDecimal rate,
        BigDecimal amount,
        LocalDate mfgDate,
        LocalDate expiryDate
    ) {
    }

    private record PreparedInvLine(
        int lineNo,
        UUID accountingLedgerId,
        LockedStockItem item,
        String direction,
        UUID uomId,
        BigDecimal actualQty,
        BigDecimal billedQty,
        BigDecimal baseActualQty,
        BigDecimal baseBilledQty,
        BigDecimal rate,
        BigDecimal discountPct,
        BigDecimal amount,
        BigDecimal declaredMrp,
        String orderRefNo,
        String trackingNo,
        UUID linkedInwardAllocationId,
        List<PreparedBatchLine> batchLines
    ) {
    }

    private record PreparedPayload(List<PreparedInvLine> lines) {
    }

    private record StoredAllocRow(
        UUID id,
        int lineNo,
        UUID stockItemId,
        String direction,
        UUID uomId,
        BigDecimal actualQty,
        BigDecimal billedQty,
        BigDecimal rate,
        BigDecimal discountPct,
        BigDecimal amount,
        BigDecimal declaredMrp,
        String orderRefNo,
        String trackingNo
    ) {
    }

    private record StoredBatchRow(
        UUID godownId,
        UUID stockBatchId,
        String batchName,
        BigDecimal actualQty,
        BigDecimal billedQty,
        BigDecimal rate,
        BigDecimal amount,
        LocalDate mfgDate,
        LocalDate expiryDate
    ) {
    }

    private record OrderFulfillRow(
        UUID id,
        BigDecimal orderedQty,
        BigDecimal deliveredReceivedQty,
        BigDecimal billedQty,
        BigDecimal rejectedQty,
        BigDecimal preclosedQty,
        String trackingNo,
        String status
    ) {
    }
}
