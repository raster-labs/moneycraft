package com.raster.moneycraft.inventory.service;

import com.raster.moneycraft.accounting.service.CompanyMasterSequenceHelper;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.inventory.dto.InventoryMasterDtos;
import com.raster.moneycraft.inventory.dto.SupplyChainReportDtos;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Dynamic 8-method high-precision inventory valuation engine (MathContext.DECIMAL128),
 * O2C/P2P open-order tracker, Multi-BOM manufacturing preview, and supply-chain reporting service.
 */
@Service
public class ValuationService {

    public static final Set<String> VALID_COSTING_METHODS = Set.of(
        "AVG_COST",
        "FIFO",
        "FIFO_PERPETUAL",
        "LIFO_ANNUAL",
        "LIFO_PERPETUAL",
        "LAST_PURCHASE_COST",
        "STD_COST",
        "AT_ZERO_COST"
    );

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final MathContext HIGH_PREC = MathContext.DECIMAL128;

    private final JdbcTemplate businessJdbcTemplate;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final RbacAuthorizationService rbacGuard;
    private final ObjectMapper objectMapper;

    public ValuationService(
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        CompanyMasterSequenceHelper sequenceHelper,
        RbacAuthorizationService rbacGuard,
        ObjectMapper objectMapper
    ) {
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.sequenceHelper = sequenceHelper;
        this.rbacGuard = rbacGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * Converts a quantity expressed in {@code lineUomId} to the stock item's {@code baseUomId}
     * supporting Simple, Alternate (numerator/denominator), and Compound (first/second) UOMs.
     */
    public BigDecimal convertToBaseUom(
        BigDecimal qty,
        UUID lineUomId,
        ItemValuationMeta item,
        Map<UUID, UomConversionMeta> uomsById
    ) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        if (lineUomId == null || lineUomId.equals(item.baseUomId())) {
            return qty.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().scale() < 0
                ? new BigDecimal(qty.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
                : qty.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        }
        if (item.alternateUomId() != null
            && lineUomId.equals(item.alternateUomId())
            && item.conversionNumerator() != null
            && item.conversionDenominator() != null
            && item.conversionDenominator().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal converted = qty.multiply(item.conversionNumerator(), HIGH_PREC)
                .divide(item.conversionDenominator(), 6, RoundingMode.HALF_UP);
            return normalizeQtyScale6(converted);
        }

        UomConversionMeta lineUom = uomsById.get(lineUomId);
        UomConversionMeta baseUom = uomsById.get(item.baseUomId());
        if (lineUom != null && "COMPOUND".equals(lineUom.uomType()) && lineUom.conversionFactor() != null) {
            if (item.baseUomId().equals(lineUom.secondUomId())) {
                return normalizeQtyScale6(qty.multiply(lineUom.conversionFactor(), HIGH_PREC));
            }
            if (item.baseUomId().equals(lineUom.firstUomId())) {
                return normalizeQtyScale6(qty);
            }
        }
        if (baseUom != null && "COMPOUND".equals(baseUom.uomType()) && baseUom.conversionFactor() != null) {
            if (lineUomId.equals(baseUom.secondUomId())) {
                return normalizeQtyScale6(qty.divide(baseUom.conversionFactor(), 6, RoundingMode.HALF_UP));
            }
            if (lineUomId.equals(baseUom.firstUomId())) {
                return normalizeQtyScale6(qty);
            }
        }
        return normalizeQtyScale6(qty);
    }

    /**
     * Rebuilds persisted {@code mc_stock_cost_layer} rows for the given stock items inside the
     * active business transaction so chronological FIFO/LIFO/opening layers stay 100% free of
     * back-dated or voucher-alteration drift.
     */
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public void syncCostLayersForItems(UUID companyId, Set<UUID> stockItemIds) {
        if (stockItemIds == null || stockItemIds.isEmpty()) {
            return;
        }
        CompanyMasterSequenceHelper.CompanyCurrencyMeta meta = sequenceHelper.readCompanyCurrencyMeta(companyId);
        int currencyScale = meta.currencyScale();
        LocalDate booksFrom = sequenceHelper.readBooksBeginningFrom(companyId);
        Map<UUID, UomConversionMeta> uoms = loadCompanyUoms(companyId);

        List<UUID> sortedIds = stockItemIds.stream()
            .sorted(Comparator.comparing(UUID::toString))
            .toList();

        for (UUID itemId : sortedIds) {
            ItemValuationMeta item = loadSingleItemMeta(companyId, itemId);
            if (item == null) {
                continue;
            }
            businessJdbcTemplate.update(
                "DELETE FROM mc_stock_cost_layer WHERE company_id = ? AND stock_item_id = ?",
                companyId,
                itemId
            );

            List<RawMovement> rawMovements = loadActiveMovementsForItem(companyId, itemId, null);
            List<EffectiveMovement> effective = buildEffectiveMovements(rawMovements, item, uoms, null);

            List<MutableLayer> layers = buildSimulatedLayers(
                item,
                booksFrom,
                effective,
                "FIFO_PERPETUAL",
                currencyScale
            );

            UUID openingGodownId = findOpeningGodownId(companyId, itemId);
            UUID openingBatchId = findOpeningBatchId(companyId, itemId);

            long seqNo = 1L;
            for (MutableLayer layer : layers) {
                if (layer.inwardQty.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal inQty = normalizeQtyScale6(layer.inwardQty);
                BigDecimal remQty = normalizeQtyScale6(layer.remainingQty.max(BigDecimal.ZERO).min(inQty));
                BigDecimal inVal = normalizeValScale6(layer.inwardValue.max(BigDecimal.ZERO));
                BigDecimal remVal = normalizeValScale6(layer.remainingValue.max(BigDecimal.ZERO));
                BigDecimal unitHigh = layer.unitCostHighPrec.max(BigDecimal.ZERO)
                    .setScale(18, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
                if (unitHigh.scale() < 0) {
                    unitHigh = new BigDecimal(unitHigh.toPlainString());
                }
                boolean exhausted = remQty.compareTo(BigDecimal.ZERO) == 0;

                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_stock_cost_layer (
                        id, company_id, stock_item_id, godown_id, stock_batch_id,
                        source_journal_id, source_allocation_id, layer_date, layer_seq,
                        inward_qty, remaining_qty, inward_value, remaining_value,
                        unit_cost_high_prec, is_opening_layer, exhausted
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    companyId,
                    itemId,
                    layer.isOpening ? openingGodownId : layer.godownId,
                    layer.isOpening ? openingBatchId : layer.stockBatchId,
                    layer.sourceJournalId,
                    layer.sourceAllocationId,
                    layer.layerDate,
                    seqNo,
                    inQty,
                    remQty,
                    inVal,
                    remVal,
                    unitHigh,
                    layer.isOpening,
                    exhausted
                );
                seqNo++;
            }
        }
    }

    public SupplyChainReportDtos.StockSummaryReportDto getStockSummary(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate rawFromDate,
        LocalDate rawToDate,
        UUID stockGroupId,
        String valuationMethodOverride
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        String methodOverride = normalizeOptionalMethodOverride(valuationMethodOverride);
        CompanyMasterSequenceHelper.CompanyCurrencyMeta companyMeta = sequenceHelper.readCompanyCurrencyMeta(companyId);
        int scale = companyMeta.currencyScale();
        LocalDate booksFrom = sequenceHelper.readBooksBeginningFrom(companyId);
        LocalDate fromDate = rawFromDate != null ? rawFromDate : booksFrom;
        LocalDate toDate = rawToDate != null ? rawToDate : LocalDate.of(2099, 12, 31);
        if (toDate.isBefore(fromDate)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-INVALID-DATE-RANGE",
                "Invalid Report Date Range",
                "toDate must be on or after fromDate."
            );
        }

        Map<UUID, UomConversionMeta> uoms = loadCompanyUoms(companyId);
        List<GroupRow> allGroups = loadStockGroups(companyId);
        Set<UUID> allowedGroupIds = resolveSubtreeGroupIds(allGroups, stockGroupId);
        List<ItemValuationMeta> items = loadActiveItems(companyId, stockGroupId == null ? null : allowedGroupIds);
        Map<UUID, List<RawMovement>> movementsByItem = loadAllActiveMovementsUpTo(companyId, toDate);

        List<SupplyChainReportDtos.StockSummaryItemDto> itemRows = new ArrayList<>();
        Map<UUID, GroupAccumulator> groupTotals = new LinkedHashMap<>();
        for (GroupRow g : allGroups) {
            if (stockGroupId == null || allowedGroupIds.contains(g.id())) {
                groupTotals.put(g.id(), new GroupAccumulator(g));
            }
        }

        BigDecimal totalOpeningVal = BigDecimal.ZERO;
        BigDecimal totalInwardVal = BigDecimal.ZERO;
        BigDecimal totalOutwardVal = BigDecimal.ZERO;
        BigDecimal totalClosingQty = BigDecimal.ZERO;
        BigDecimal totalClosingVal = BigDecimal.ZERO;

        for (ItemValuationMeta item : items) {
            String effectiveMethod = methodOverride != null ? methodOverride : item.costingMethod();
            List<RawMovement> itemRaw = movementsByItem.getOrDefault(item.id(), List.of());
            PeriodValuationResult val = evaluateItemOverPeriod(
                item,
                booksFrom,
                fromDate,
                toDate,
                effectiveMethod,
                scale,
                itemRaw,
                uoms
            );

            itemRows.add(new SupplyChainReportDtos.StockSummaryItemDto(
                item.id(),
                item.name(),
                item.stockGroupId(),
                item.stockGroupName(),
                item.hsnSacCode(),
                item.baseUomSymbol(),
                effectiveMethod,
                ExactMoneyValidator.formatPlainDecimal(val.openingQty()),
                ExactMoneyValidator.formatExactScale(val.openingRate(), scale),
                ExactMoneyValidator.formatExactScale(val.openingValue(), scale),
                ExactMoneyValidator.formatPlainDecimal(val.inwardQty()),
                ExactMoneyValidator.formatExactScale(val.inwardValue(), scale),
                ExactMoneyValidator.formatPlainDecimal(val.outwardQty()),
                ExactMoneyValidator.formatExactScale(val.outwardValue(), scale),
                ExactMoneyValidator.formatPlainDecimal(val.closingQty()),
                ExactMoneyValidator.formatExactScale(val.closingRate(), scale),
                ExactMoneyValidator.formatExactScale(val.closingValue(), scale)
            ));

            totalOpeningVal = totalOpeningVal.add(val.openingValue());
            totalInwardVal = totalInwardVal.add(val.inwardValue());
            totalOutwardVal = totalOutwardVal.add(val.outwardValue());
            totalClosingQty = totalClosingQty.add(val.closingQty());
            totalClosingVal = totalClosingVal.add(val.closingValue());

            // Roll up values along parentStockGroupId hierarchy
            UUID gCursor = item.stockGroupId();
            Set<UUID> visited = new HashSet<>();
            while (gCursor != null && visited.add(gCursor)) {
                GroupAccumulator acc = groupTotals.get(gCursor);
                if (acc == null) {
                    break;
                }
                acc.closingQty = acc.closingQty.add(val.closingQty());
                acc.openingValue = acc.openingValue.add(val.openingValue());
                acc.inwardValue = acc.inwardValue.add(val.inwardValue());
                acc.outwardValue = acc.outwardValue.add(val.outwardValue());
                acc.closingValue = acc.closingValue.add(val.closingValue());
                gCursor = acc.group.parentStockGroupId();
            }
        }

        List<SupplyChainReportDtos.StockSummaryGroupNodeDto> groupNodes = groupTotals.values().stream()
            .map(acc -> new SupplyChainReportDtos.StockSummaryGroupNodeDto(
                acc.group.id(),
                acc.group.name(),
                acc.group.parentStockGroupId(),
                acc.group.shouldAddQty() ? ExactMoneyValidator.formatPlainDecimal(acc.closingQty) : null,
                ExactMoneyValidator.formatExactScale(acc.openingValue, scale),
                ExactMoneyValidator.formatExactScale(acc.inwardValue, scale),
                ExactMoneyValidator.formatExactScale(acc.outwardValue, scale),
                ExactMoneyValidator.formatExactScale(acc.closingValue, scale)
            ))
            .toList();

        return new SupplyChainReportDtos.StockSummaryReportDto(
            fromDate,
            rawToDate,
            methodOverride,
            groupNodes,
            itemRows,
            ExactMoneyValidator.formatExactScale(totalOpeningVal, scale),
            ExactMoneyValidator.formatExactScale(totalInwardVal, scale),
            ExactMoneyValidator.formatExactScale(totalOutwardVal, scale),
            ExactMoneyValidator.formatPlainDecimal(totalClosingQty),
            ExactMoneyValidator.formatExactScale(totalClosingVal, scale)
        );
    }

    public SupplyChainReportDtos.GodownSummaryReportDto getGodownSummary(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID godownId,
        LocalDate rawAsOfDate
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        CompanyMasterSequenceHelper.CompanyCurrencyMeta companyMeta = sequenceHelper.readCompanyCurrencyMeta(companyId);
        int scale = companyMeta.currencyScale();
        LocalDate booksFrom = sequenceHelper.readBooksBeginningFrom(companyId);
        LocalDate asOfDate = rawAsOfDate != null ? rawAsOfDate : LocalDate.of(2099, 12, 31);

        Map<UUID, UomConversionMeta> uoms = loadCompanyUoms(companyId);
        List<ItemValuationMeta> items = loadActiveItems(companyId, null);
        Map<UUID, ItemValuationMeta> itemsById = new LinkedHashMap<>();
        for (ItemValuationMeta item : items) {
            itemsById.put(item.id(), item);
        }

        Map<UUID, String> godownNames = new LinkedHashMap<>();
        Set<UUID> allowedGodowns = resolveSubtreeGodownIds(companyId, godownId, godownNames);

        // Compute company-level closing valuation per item at asOfDate so godown slices value proportionally
        Map<UUID, PointValuation> itemValuations = new HashMap<>();
        Map<UUID, List<RawMovement>> movementsByItem = loadAllActiveMovementsUpTo(companyId, asOfDate);
        for (ItemValuationMeta item : items) {
            List<EffectiveMovement> eff = buildEffectiveMovements(
                movementsByItem.getOrDefault(item.id(), List.of()),
                item,
                uoms,
                asOfDate
            );
            itemValuations.put(
                item.id(),
                evaluatePointValuation(item, booksFrom, asOfDate, eff, item.costingMethod(), scale)
            );
        }

        Map<GodownItemKey, BigDecimal> qtyByGodownItem = computeGodownItemQuantitiesAsOf(
            companyId,
            asOfDate,
            itemsById,
            uoms
        );

        List<SupplyChainReportDtos.GodownSummaryRowDto> rows = new ArrayList<>();
        BigDecimal totalValue = BigDecimal.ZERO;

        for (Map.Entry<GodownItemKey, BigDecimal> entry : qtyByGodownItem.entrySet()) {
            GodownItemKey key = entry.getKey();
            BigDecimal qty = entry.getValue();
            if (qty.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            if (godownId != null && !allowedGodowns.contains(key.godownId())) {
                continue;
            }
            ItemValuationMeta item = itemsById.get(key.stockItemId());
            if (item == null) {
                continue;
            }
            PointValuation pv = itemValuations.get(item.id());
            BigDecimal rowVal = BigDecimal.ZERO;
            BigDecimal rowRate = BigDecimal.ZERO;
            if (pv != null && pv.closingQty().compareTo(BigDecimal.ZERO) > 0 && qty.compareTo(BigDecimal.ZERO) > 0) {
                if (qty.compareTo(pv.closingQty()) == 0) {
                    rowVal = pv.closingValue();
                } else {
                    rowVal = pv.closingValue()
                        .multiply(qty, HIGH_PREC)
                        .divide(pv.closingQty(), scale, RoundingMode.HALF_UP);
                }
                rowRate = rowVal.divide(qty, scale, RoundingMode.HALF_UP);
            }
            rows.add(new SupplyChainReportDtos.GodownSummaryRowDto(
                key.godownId(),
                godownNames.getOrDefault(key.godownId(), "Main Location"),
                item.id(),
                item.name(),
                item.baseUomSymbol(),
                ExactMoneyValidator.formatPlainDecimal(qty),
                ExactMoneyValidator.formatExactScale(rowRate, scale),
                ExactMoneyValidator.formatExactScale(rowVal, scale)
            ));
            totalValue = totalValue.add(rowVal);
        }

        return new SupplyChainReportDtos.GodownSummaryReportDto(
            rawAsOfDate,
            rows,
            ExactMoneyValidator.formatExactScale(totalValue, scale)
        );
    }

    public SupplyChainReportDtos.MovementAnalysisReportDto getMovementAnalysis(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID stockItemId,
        LocalDate rawFromDate,
        LocalDate rawToDate
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        if (stockItemId == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-ITEM-REQUIRED",
                "Stock Item Required",
                "stockItemId is required for movement analysis."
            );
        }
        CompanyMasterSequenceHelper.CompanyCurrencyMeta companyMeta = sequenceHelper.readCompanyCurrencyMeta(companyId);
        int scale = companyMeta.currencyScale();
        LocalDate booksFrom = sequenceHelper.readBooksBeginningFrom(companyId);
        LocalDate fromDate = rawFromDate != null ? rawFromDate : booksFrom;
        LocalDate toDate = rawToDate != null ? rawToDate : LocalDate.of(2099, 12, 31);

        ItemValuationMeta item = loadSingleItemMeta(companyId, stockItemId);
        if (item == null) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-INV-ITEM-NOT-FOUND",
                "Stock Item Not Found",
                "Stock item " + stockItemId + " does not exist."
            );
        }
        Map<UUID, UomConversionMeta> uoms = loadCompanyUoms(companyId);
        List<RawMovement> raw = loadActiveMovementsForItem(companyId, stockItemId, toDate);
        List<EffectiveMovement> eff = buildEffectiveMovements(raw, item, uoms, toDate);

        BigDecimal inwardQty = BigDecimal.ZERO;
        BigDecimal inwardVal = BigDecimal.ZERO;
        BigDecimal outwardQty = BigDecimal.ZERO;
        BigDecimal outwardVal = BigDecimal.ZERO;
        List<SupplyChainReportDtos.MovementAnalysisVoucherRowDto> movementRows = new ArrayList<>();

        for (EffectiveMovement m : eff) {
            if (m.effectiveDate.isBefore(fromDate) || m.effectiveDate.isAfter(toDate)) {
                continue;
            }
            if (m.skipPhysicalStock) {
                continue;
            }
            BigDecimal rate = m.baseQty.compareTo(BigDecimal.ZERO) > 0
                ? m.amount.divide(m.baseQty, scale, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            if ("INWARD".equals(m.direction)) {
                inwardQty = inwardQty.add(m.baseQty);
                inwardVal = inwardVal.add(m.amount);
            } else {
                outwardQty = outwardQty.add(m.baseQty);
                outwardVal = outwardVal.add(m.amount);
            }
            movementRows.add(new SupplyChainReportDtos.MovementAnalysisVoucherRowDto(
                m.journalId,
                m.voucherDate,
                m.voucherTypeName,
                m.voucherNumber == null ? "" : m.voucherNumber,
                m.partyName == null ? m.voucherTypeName : m.partyName,
                m.direction,
                ExactMoneyValidator.formatPlainDecimal(m.baseQty),
                ExactMoneyValidator.formatExactScale(rate, scale),
                ExactMoneyValidator.formatExactScale(m.amount, scale)
            ));
        }

        return new SupplyChainReportDtos.MovementAnalysisReportDto(
            item.id(),
            item.name(),
            item.baseUomSymbol(),
            fromDate,
            rawToDate,
            ExactMoneyValidator.formatPlainDecimal(inwardQty),
            ExactMoneyValidator.formatExactScale(inwardVal, scale),
            ExactMoneyValidator.formatPlainDecimal(outwardQty),
            ExactMoneyValidator.formatExactScale(outwardVal, scale),
            movementRows
        );
    }

    public SupplyChainReportDtos.BatchAgeingReportDto getBatchAgeing(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID stockItemId,
        LocalDate rawAsOfDate
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        CompanyMasterSequenceHelper.CompanyCurrencyMeta companyMeta = sequenceHelper.readCompanyCurrencyMeta(companyId);
        int scale = companyMeta.currencyScale();
        LocalDate booksFrom = sequenceHelper.readBooksBeginningFrom(companyId);
        LocalDate asOfDate = rawAsOfDate != null ? rawAsOfDate : LocalDate.now();

        Map<UUID, UomConversionMeta> uoms = loadCompanyUoms(companyId);
        List<ItemValuationMeta> items = stockItemId == null
            ? loadActiveItems(companyId, null)
            : List.of();
        Map<UUID, ItemValuationMeta> itemsById = new LinkedHashMap<>();
        if (stockItemId != null) {
            ItemValuationMeta single = loadSingleItemMeta(companyId, stockItemId);
            if (single == null) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-INV-ITEM-NOT-FOUND",
                    "Stock Item Not Found",
                    "Stock item " + stockItemId + " does not exist."
                );
            }
            itemsById.put(single.id(), single);
        } else {
            for (ItemValuationMeta im : items) {
                itemsById.put(im.id(), im);
            }
        }

        Map<UUID, List<RawMovement>> movementsByItem = loadAllActiveMovementsUpTo(companyId, asOfDate);
        Map<UUID, PointValuation> itemValuations = new HashMap<>();
        for (ItemValuationMeta item : itemsById.values()) {
            List<EffectiveMovement> eff = buildEffectiveMovements(
                movementsByItem.getOrDefault(item.id(), List.of()),
                item,
                uoms,
                asOfDate
            );
            itemValuations.put(
                item.id(),
                evaluatePointValuation(item, booksFrom, asOfDate, eff, item.costingMethod(), scale)
            );
        }

        Map<UUID, BigDecimal> batchQtyAsOf = computeBatchClosingQuantitiesAsOf(
            companyId,
            stockItemId,
            asOfDate,
            itemsById,
            uoms
        );

        StringBuilder sql = new StringBuilder(
            """
            SELECT b.id, b.stock_item_id, si.name AS item_name,
                   b.godown_id, g.name AS godown_name,
                   b.batch_name, b.mfg_date, b.expiry_date, b.closing_qty
              FROM mc_stock_batch b
              JOIN mc_stock_item si
                ON si.company_id = b.company_id AND si.id = b.stock_item_id
              JOIN mc_godown g
                ON g.company_id = b.company_id AND g.id = b.godown_id
             WHERE b.company_id = ?
               AND si.active = true
            """
        );
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        if (stockItemId != null) {
            sql.append(" AND b.stock_item_id = ?");
            params.add(stockItemId);
        }
        sql.append(" ORDER BY b.expiry_date ASC NULLS LAST, si.name ASC, b.batch_name ASC");

        List<SupplyChainReportDtos.BatchAgeingRowDto> rows = new ArrayList<>();
        businessJdbcTemplate.query(
            sql.toString(),
            rs -> {
                UUID batchId = rs.getObject("id", UUID.class);
                UUID itemId = rs.getObject("stock_item_id", UUID.class);
                BigDecimal qty = batchQtyAsOf.getOrDefault(batchId, rs.getBigDecimal("closing_qty"));
                if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                    return;
                }
                LocalDate expiry = rs.getObject("expiry_date", LocalDate.class);
                Long daysToExpiry = expiry == null ? null : ChronoUnit.DAYS.between(asOfDate, expiry);
                boolean expired = expiry != null && expiry.isBefore(asOfDate);

                PointValuation pv = itemValuations.get(itemId);
                BigDecimal batchVal = BigDecimal.ZERO;
                if (pv != null && pv.closingQty().compareTo(BigDecimal.ZERO) > 0) {
                    if (qty.compareTo(pv.closingQty()) == 0) {
                        batchVal = pv.closingValue();
                    } else {
                        batchVal = pv.closingValue()
                            .multiply(qty, HIGH_PREC)
                            .divide(pv.closingQty(), scale, RoundingMode.HALF_UP);
                    }
                }
                rows.add(new SupplyChainReportDtos.BatchAgeingRowDto(
                    batchId,
                    itemId,
                    rs.getString("item_name"),
                    rs.getObject("godown_id", UUID.class),
                    rs.getString("godown_name"),
                    rs.getString("batch_name"),
                    rs.getObject("mfg_date", LocalDate.class),
                    expiry,
                    daysToExpiry,
                    expired,
                    ExactMoneyValidator.formatPlainDecimal(qty),
                    ExactMoneyValidator.formatExactScale(batchVal, scale)
                ));
            },
            params.toArray()
        );

        return new SupplyChainReportDtos.BatchAgeingReportDto(asOfDate, rows);
    }

    public List<SupplyChainReportDtos.OpenOrderLineDto> listOpenOrders(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String rawOrderType,
        UUID partyLedgerId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        CompanyMasterSequenceHelper.CompanyCurrencyMeta companyMeta = sequenceHelper.readCompanyCurrencyMeta(companyId);
        int scale = companyMeta.currencyScale();
        String orderType = rawOrderType == null ? "" : rawOrderType.trim().toUpperCase(Locale.ROOT);
        if (!orderType.isEmpty()
            && !"SALES_ORDER".equals(orderType)
            && !"PURCHASE_ORDER".equals(orderType)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-INVALID-ORDER-TYPE",
                "Invalid Order Type",
                "orderType must be SALES_ORDER or PURCHASE_ORDER."
            );
        }

        StringBuilder sql = new StringBuilder(
            """
            SELECT olf.order_journal_id, olf.order_allocation_id, olf.order_ref_no,
                   j.voucher_date AS order_date, olf.due_on_date, vt.base_category,
                   olf.party_ledger_id, pl.name AS party_name,
                   olf.stock_item_id, si.name AS item_name,
                   ia.uom_id, u.symbol AS uom_symbol,
                   olf.ordered_qty, olf.delivered_received_qty, olf.billed_qty,
                   olf.rejected_qty, olf.preclosed_qty, olf.tracking_no, olf.status,
                   ia.rate, ia.discount_pct
              FROM mc_order_line_fulfillment olf
              JOIN mc_journal j
                ON j.company_id = olf.company_id AND j.id = olf.order_journal_id
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
              JOIN mc_inventory_allocation ia
                ON ia.company_id = olf.company_id AND ia.id = olf.order_allocation_id
              JOIN mc_stock_item si
                ON si.company_id = olf.company_id AND si.id = olf.stock_item_id
              JOIN mc_uom u
                ON u.company_id = ia.company_id AND u.id = ia.uom_id
              LEFT JOIN mc_ledger pl
                ON pl.company_id = olf.company_id AND pl.id = olf.party_ledger_id
             WHERE olf.company_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND olf.status IN ('OPEN', 'PARTIAL')
            """
        );
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        if (!orderType.isEmpty()) {
            sql.append(" AND vt.base_category = ?");
            params.add(orderType);
        }
        if (partyLedgerId != null) {
            sql.append(" AND olf.party_ledger_id = ?");
            params.add(partyLedgerId);
        }
        sql.append(" ORDER BY j.voucher_date ASC, olf.order_ref_no ASC, ia.line_no ASC");

        List<SupplyChainReportDtos.OpenOrderLineDto> openLines = new ArrayList<>(businessJdbcTemplate.query(
            sql.toString(),
            (rs, rowNum) -> {
                BigDecimal ordered = rs.getBigDecimal("ordered_qty");
                BigDecimal deliv = rs.getBigDecimal("delivered_received_qty");
                BigDecimal billed = rs.getBigDecimal("billed_qty");
                BigDecimal rejected = rs.getBigDecimal("rejected_qty");
                BigDecimal preclosed = rs.getBigDecimal("preclosed_qty");
                BigDecimal netDeliv = deliv.subtract(rejected).max(BigDecimal.ZERO);
                BigDecimal remDeliver = ordered.subtract(netDeliv).subtract(preclosed).max(BigDecimal.ZERO);
                BigDecimal remBill = ordered.subtract(billed).subtract(preclosed).max(BigDecimal.ZERO);
                BigDecimal pending = remDeliver.compareTo(BigDecimal.ZERO) > 0 ? remDeliver : remBill;

                return new SupplyChainReportDtos.OpenOrderLineDto(
                    rs.getObject("order_journal_id", UUID.class),
                    rs.getObject("order_allocation_id", UUID.class),
                    rs.getString("order_ref_no"),
                    rs.getObject("order_date", LocalDate.class),
                    rs.getObject("due_on_date", LocalDate.class),
                    rs.getString("base_category"),
                    rs.getObject("party_ledger_id", UUID.class),
                    rs.getString("party_name"),
                    rs.getObject("stock_item_id", UUID.class),
                    rs.getString("item_name"),
                    rs.getObject("uom_id", UUID.class),
                    rs.getString("uom_symbol"),
                    ExactMoneyValidator.formatPlainDecimal(ordered),
                    ExactMoneyValidator.formatPlainDecimal(deliv),
                    ExactMoneyValidator.formatPlainDecimal(billed),
                    ExactMoneyValidator.formatPlainDecimal(rejected),
                    ExactMoneyValidator.formatPlainDecimal(pending),
                    rs.getString("tracking_no"),
                    rs.getString("status"),
                    ExactMoneyValidator.formatExactScale(rs.getBigDecimal("rate").setScale(scale, RoundingMode.HALF_UP), scale),
                    ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("discount_pct"))
                );
            },
            params.toArray()
        ));

        // Also include standalone unbilled Delivery Notes / Receipt Notes with tracking_no not linked to an order
        appendStandaloneUnbilledTrackingNotes(companyId, orderType, partyLedgerId, scale, openLines);
        return openLines;
    }

    private void appendStandaloneUnbilledTrackingNotes(
        UUID companyId,
        String orderType,
        UUID partyLedgerId,
        int scale,
        List<SupplyChainReportDtos.OpenOrderLineDto> out
    ) {
        StringBuilder sql = new StringBuilder(
            """
            SELECT j.id AS note_journal_id, ia.id AS note_alloc_id,
                   COALESCE(j.voucher_number, ia.tracking_no) AS note_no,
                   j.voucher_date, vt.base_category,
                   j.party_ledger_id, pl.name AS party_name,
                   ia.stock_item_id, si.name AS item_name,
                   ia.uom_id, u.symbol AS uom_symbol,
                   ia.actual_qty, ia.tracking_no, ia.rate, ia.discount_pct
              FROM mc_inventory_allocation ia
              JOIN mc_journal j ON j.company_id = ia.company_id AND j.id = ia.journal_id
              JOIN mc_voucher_type vt ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
              JOIN mc_stock_item si ON si.company_id = ia.company_id AND si.id = ia.stock_item_id
              JOIN mc_uom u ON u.company_id = ia.company_id AND u.id = ia.uom_id
              LEFT JOIN mc_ledger pl ON pl.company_id = j.company_id AND pl.id = j.party_ledger_id
             WHERE ia.company_id = ?
               AND ia.tracking_no IS NOT NULL
               AND ia.order_ref_no IS NULL
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND vt.base_category IN ('DELIVERY_NOTE', 'RECEIPT_NOTE')
               AND NOT EXISTS (
                   SELECT 1
                     FROM mc_inventory_allocation inv
                     JOIN mc_journal ij ON ij.company_id = inv.company_id AND ij.id = inv.journal_id
                     JOIN mc_voucher_type ivt ON ivt.company_id = ij.company_id AND ivt.id = ij.voucher_type_id
                    WHERE inv.company_id = ia.company_id
                      AND inv.stock_item_id = ia.stock_item_id
                      AND lower(COALESCE(inv.tracking_no, '')) = lower(ia.tracking_no)
                      AND ij.status = 'POSTED'
                      AND ij.posting_nature = 'NORMAL'
                      AND ivt.base_category IN ('SALES', 'PURCHASE')
               )
            """
        );
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        if ("SALES_ORDER".equals(orderType)) {
            sql.append(" AND vt.base_category = 'DELIVERY_NOTE'");
        } else if ("PURCHASE_ORDER".equals(orderType)) {
            sql.append(" AND vt.base_category = 'RECEIPT_NOTE'");
        }
        if (partyLedgerId != null) {
            sql.append(" AND j.party_ledger_id = ?");
            params.add(partyLedgerId);
        }
        sql.append(" ORDER BY j.voucher_date ASC, ia.id ASC");

        businessJdbcTemplate.query(
            sql.toString(),
            rs -> {
                String cat = rs.getString("base_category");
                String mappedOrderType = "DELIVERY_NOTE".equals(cat) ? "SALES_ORDER" : "PURCHASE_ORDER";
                String qtyStr = ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("actual_qty"));
                out.add(new SupplyChainReportDtos.OpenOrderLineDto(
                    rs.getObject("note_journal_id", UUID.class),
                    rs.getObject("note_alloc_id", UUID.class),
                    rs.getString("note_no"),
                    rs.getObject("voucher_date", LocalDate.class),
                    rs.getObject("voucher_date", LocalDate.class),
                    mappedOrderType,
                    rs.getObject("party_ledger_id", UUID.class),
                    rs.getString("party_name"),
                    rs.getObject("stock_item_id", UUID.class),
                    rs.getString("item_name"),
                    rs.getObject("uom_id", UUID.class),
                    rs.getString("uom_symbol"),
                    qtyStr,
                    qtyStr,
                    "0",
                    "0",
                    qtyStr,
                    rs.getString("tracking_no"),
                    "PARTIAL",
                    ExactMoneyValidator.formatExactScale(
                        rs.getBigDecimal("rate").setScale(scale, RoundingMode.HALF_UP),
                        scale
                    ),
                    ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("discount_pct"))
                ));
            },
            params.toArray()
        );
    }

    public SupplyChainReportDtos.ManufacturingPreviewResponse previewManufacturing(
        MoneyCraftPrincipal principal,
        UUID companyId,
        SupplyChainReportDtos.ManufacturingPreviewRequest request
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        if (request == null || request.bomId() == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-BOM-REQUIRED",
                "BOM Required",
                "bomId is required for manufacturing preview."
            );
        }
        BigDecimal finishedQty = ExactMoneyValidator.parseAndValidateBoundedDecimal(
            request.finishedQty(),
            6,
            BigDecimal.ZERO,
            null,
            true,
            "finishedQty"
        );
        CompanyMasterSequenceHelper.CompanyCurrencyMeta companyMeta = sequenceHelper.readCompanyCurrencyMeta(companyId);
        int scale = companyMeta.currencyScale();
        LocalDate booksFrom = sequenceHelper.readBooksBeginningFrom(companyId);
        LocalDate asOfDate = request.asOfDate() != null ? request.asOfDate() : LocalDate.now();

        List<BomHeaderRow> boms = businessJdbcTemplate.query(
            """
            SELECT b.id, b.stock_item_id, si.name AS item_name, u.symbol AS uom_symbol,
                   b.bom_name, b.unit_of_manufacture, b.additional_cost_rules::text AS rules_json
              FROM mc_bom b
              JOIN mc_stock_item si
                ON si.company_id = b.company_id AND si.id = b.stock_item_id
              JOIN mc_uom u
                ON u.company_id = si.company_id AND u.id = si.base_uom_id
             WHERE b.company_id = ? AND b.id = ? AND b.active = true
            """,
            (rs, rowNum) -> new BomHeaderRow(
                rs.getObject("id", UUID.class),
                rs.getObject("stock_item_id", UUID.class),
                rs.getString("item_name"),
                rs.getString("uom_symbol"),
                rs.getString("bom_name"),
                rs.getBigDecimal("unit_of_manufacture"),
                rs.getString("rules_json")
            ),
            companyId,
            request.bomId()
        );
        if (boms.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-INV-BOM-NOT-FOUND",
                "BOM Not Found",
                "Active BOM " + request.bomId() + " does not exist."
            );
        }
        BomHeaderRow bom = boms.getFirst();
        UUID defaultGodownId = request.godownId() != null ? request.godownId() : findMainLocationGodownId(companyId);

        List<BomCompRow> compRows = businessJdbcTemplate.query(
            """
            SELECT bc.line_no, bc.component_item_id, bc.component_type,
                   bc.godown_id, bc.quantity, bc.cost_allocation_pct
              FROM mc_bom_component bc
             WHERE bc.company_id = ? AND bc.bom_id = ?
             ORDER BY bc.line_no ASC
            """,
            (rs, rowNum) -> new BomCompRow(
                rs.getInt("line_no"),
                rs.getObject("component_item_id", UUID.class),
                rs.getString("component_type"),
                rs.getObject("godown_id", UUID.class),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("cost_allocation_pct")
            ),
            companyId,
            bom.id()
        );

        Map<UUID, UomConversionMeta> uoms = loadCompanyUoms(companyId);
        List<SupplyChainReportDtos.ManufacturingPreviewLineDto> rawMatLines = new ArrayList<>();
        List<SupplyChainReportDtos.ManufacturingPreviewLineDto> byProductLines = new ArrayList<>();
        BigDecimal componentTotalCost = BigDecimal.ZERO;

        for (BomCompRow comp : compRows) {
            if (!"RAW_MATERIAL".equals(comp.componentType())) {
                continue;
            }
            ItemValuationMeta item = loadSingleItemMeta(companyId, comp.componentItemId());
            if (item == null) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-ITEM-NOT-FOUND",
                    "BOM Component Item Not Found",
                    "Component item " + comp.componentItemId() + " does not exist."
                );
            }
            BigDecimal reqQty = normalizeQtyScale6(
                comp.quantity().multiply(finishedQty, HIGH_PREC)
                    .divide(bom.unitOfManufacture(), 6, RoundingMode.HALF_UP)
            );
            BigDecimal issueCost = estimateOutwardConsumptionValue(
                companyId,
                item,
                booksFrom,
                asOfDate,
                reqQty,
                scale,
                uoms
            );
            BigDecimal unitRate = reqQty.compareTo(BigDecimal.ZERO) > 0
                ? issueCost.divide(reqQty, scale, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
            componentTotalCost = componentTotalCost.add(issueCost);

            UUID lineGodown = comp.godownId() != null ? comp.godownId() : defaultGodownId;
            String qtyStr = ExactMoneyValidator.formatPlainDecimal(reqQty);
            String rateStr = ExactMoneyValidator.formatExactScale(unitRate, scale);
            rawMatLines.add(new SupplyChainReportDtos.ManufacturingPreviewLineDto(
                comp.lineNo(),
                item.id(),
                item.id(),
                item.name(),
                "RAW_MATERIAL",
                "OUTWARD",
                lineGodown,
                item.baseUomId(),
                item.baseUomSymbol(),
                qtyStr,
                qtyStr,
                rateStr,
                rateStr,
                ExactMoneyValidator.formatExactScale(issueCost, scale),
                "0"
            ));
        }

        BigDecimal additionalCostTotal = computeManufacturingAdditionalCost(
            bom,
            finishedQty,
            componentTotalCost,
            request.additionalCosts(),
            scale
        );
        BigDecimal grossPool = componentTotalCost.add(additionalCostTotal);
        BigDecimal scrapDeductionTotal = BigDecimal.ZERO;

        for (BomCompRow comp : compRows) {
            if ("RAW_MATERIAL".equals(comp.componentType())) {
                continue;
            }
            ItemValuationMeta item = loadSingleItemMeta(companyId, comp.componentItemId());
            if (item == null) {
                continue;
            }
            BigDecimal outQty = normalizeQtyScale6(
                comp.quantity().multiply(finishedQty, HIGH_PREC)
                    .divide(bom.unitOfManufacture(), 6, RoundingMode.HALF_UP)
            );
            BigDecimal allocVal = grossPool.multiply(comp.costAllocationPct(), HIGH_PREC)
                .divide(HUNDRED, scale, RoundingMode.HALF_UP);
            BigDecimal unitRate = outQty.compareTo(BigDecimal.ZERO) > 0
                ? allocVal.divide(outQty, scale, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
            scrapDeductionTotal = scrapDeductionTotal.add(allocVal);

            UUID lineGodown = comp.godownId() != null ? comp.godownId() : defaultGodownId;
            String qtyStr = ExactMoneyValidator.formatPlainDecimal(outQty);
            String rateStr = ExactMoneyValidator.formatExactScale(unitRate, scale);
            byProductLines.add(new SupplyChainReportDtos.ManufacturingPreviewLineDto(
                comp.lineNo(),
                item.id(),
                item.id(),
                item.name(),
                comp.componentType(),
                "INWARD",
                lineGodown,
                item.baseUomId(),
                item.baseUomSymbol(),
                qtyStr,
                qtyStr,
                rateStr,
                rateStr,
                ExactMoneyValidator.formatExactScale(allocVal, scale),
                ExactMoneyValidator.formatPlainDecimal(comp.costAllocationPct())
            ));
        }

        BigDecimal finishedTotalCost = grossPool.subtract(scrapDeductionTotal)
            .max(BigDecimal.ZERO)
            .setScale(scale, RoundingMode.HALF_UP);
        BigDecimal finishedUnitCost = finishedTotalCost.divide(finishedQty, scale, RoundingMode.HALF_UP);

        String finQtyStr = ExactMoneyValidator.formatPlainDecimal(finishedQty);
        String finTotalStr = ExactMoneyValidator.formatExactScale(finishedTotalCost, scale);
        String finUnitStr = ExactMoneyValidator.formatExactScale(finishedUnitCost, scale);

        return new SupplyChainReportDtos.ManufacturingPreviewResponse(
            bom.id(),
            bom.bomName(),
            bom.stockItemId(),
            bom.stockItemName(),
            bom.uomSymbol(),
            finQtyStr,
            finQtyStr,
            rawMatLines,
            byProductLines,
            ExactMoneyValidator.formatExactScale(componentTotalCost, scale),
            ExactMoneyValidator.formatExactScale(scrapDeductionTotal, scale),
            ExactMoneyValidator.formatExactScale(additionalCostTotal, scale),
            finTotalStr,
            finTotalStr,
            finUnitStr,
            finUnitStr
        );
    }

    // =========================================================================
    // Core 8-Method Valuation Engine
    // =========================================================================

    public PointValuation evaluateItemAtDate(UUID companyId, UUID stockItemId, LocalDate asOfDate) {
        CompanyMasterSequenceHelper.CompanyCurrencyMeta meta = sequenceHelper.readCompanyCurrencyMeta(companyId);
        LocalDate booksFrom = sequenceHelper.readBooksBeginningFrom(companyId);
        ItemValuationMeta item = loadSingleItemMeta(companyId, stockItemId);
        if (item == null) {
            return new PointValuation(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Map<UUID, UomConversionMeta> uoms = loadCompanyUoms(companyId);
        List<RawMovement> raw = loadActiveMovementsForItem(companyId, stockItemId, asOfDate);
        List<EffectiveMovement> eff = buildEffectiveMovements(raw, item, uoms, asOfDate);
        return evaluatePointValuation(item, booksFrom, asOfDate, eff, item.costingMethod(), meta.currencyScale());
    }

    private PeriodValuationResult evaluateItemOverPeriod(
        ItemValuationMeta item,
        LocalDate booksFrom,
        LocalDate fromDate,
        LocalDate toDate,
        String costingMethod,
        int scale,
        List<RawMovement> rawMovementsUpToEnd,
        Map<UUID, UomConversionMeta> uoms
    ) {
        PointValuation opening;
        if (!fromDate.isAfter(booksFrom)) {
            BigDecimal openQty = item.openingQty();
            BigDecimal openVal = item.openingValue().setScale(scale, RoundingMode.HALF_UP);
            BigDecimal openRate = openQty.compareTo(BigDecimal.ZERO) > 0
                ? openVal.divide(openQty, scale, RoundingMode.HALF_UP)
                : item.openingRate().setScale(scale, RoundingMode.HALF_UP);
            opening = new PointValuation(openQty, openRate, openVal);
        } else {
            LocalDate openAsOf = fromDate.minusDays(1);
            List<EffectiveMovement> openEff = buildEffectiveMovements(rawMovementsUpToEnd, item, uoms, openAsOf);
            opening = evaluatePointValuation(item, booksFrom, openAsOf, openEff, costingMethod, scale);
        }

        List<EffectiveMovement> closeEff = buildEffectiveMovements(rawMovementsUpToEnd, item, uoms, toDate);
        PointValuation closing = evaluatePointValuation(item, booksFrom, toDate, closeEff, costingMethod, scale);

        BigDecimal inwardQty = BigDecimal.ZERO;
        BigDecimal inwardVal = BigDecimal.ZERO;
        BigDecimal outwardQty = BigDecimal.ZERO;
        BigDecimal outwardVal = BigDecimal.ZERO;

        for (EffectiveMovement m : closeEff) {
            if (m.effectiveDate.isBefore(fromDate) || m.effectiveDate.isAfter(toDate) || m.skipPhysicalStock) {
                continue;
            }
            if ("INWARD".equals(m.direction)) {
                inwardQty = inwardQty.add(m.baseQty);
                inwardVal = inwardVal.add(m.amount);
            } else {
                outwardQty = outwardQty.add(m.baseQty);
                outwardVal = outwardVal.add(m.amount);
            }
        }

        return new PeriodValuationResult(
            opening.closingQty(),
            opening.closingRate(),
            opening.closingValue(),
            inwardQty,
            inwardVal.setScale(scale, RoundingMode.HALF_UP),
            outwardQty,
            outwardVal.setScale(scale, RoundingMode.HALF_UP),
            closing.closingQty(),
            closing.closingRate(),
            closing.closingValue()
        );
    }

    private PointValuation evaluatePointValuation(
        ItemValuationMeta item,
        LocalDate booksFrom,
        LocalDate asOfDate,
        List<EffectiveMovement> effectiveMovements,
        String costingMethod,
        int scale
    ) {
        BigDecimal closingQty = item.openingQty();
        for (EffectiveMovement m : effectiveMovements) {
            if (m.skipPhysicalStock) {
                continue;
            }
            if ("INWARD".equals(m.direction)) {
                closingQty = closingQty.add(m.baseQty);
            } else {
                closingQty = closingQty.subtract(m.baseQty);
            }
        }

        if (closingQty.compareTo(BigDecimal.ZERO) <= 0) {
            return new PointValuation(
                closingQty,
                BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP)
            );
        }

        return switch (costingMethod) {
            case "AT_ZERO_COST" -> new PointValuation(
                closingQty,
                BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP)
            );
            case "STD_COST" -> {
                BigDecimal stdUnit = item.standardCost() != null ? item.standardCost() : item.openingRate();
                BigDecimal val = closingQty.multiply(stdUnit, HIGH_PREC).setScale(scale, RoundingMode.HALF_UP);
                BigDecimal rate = val.divide(closingQty, scale, RoundingMode.HALF_UP);
                yield new PointValuation(closingQty, rate, val);
            }
            case "LAST_PURCHASE_COST" -> {
                BigDecimal lastUnitCost = item.openingRate();
                for (EffectiveMovement m : effectiveMovements) {
                    if ("INWARD".equals(m.direction)
                        && !m.isSalesReturn
                        && !m.isSameVoucherTransfer
                        && m.baseQty.compareTo(BigDecimal.ZERO) > 0) {
                        lastUnitCost = m.amount.divide(m.baseQty, HIGH_PREC);
                    }
                }
                BigDecimal val = closingQty.multiply(lastUnitCost, HIGH_PREC).setScale(scale, RoundingMode.HALF_UP);
                BigDecimal rate = val.divide(closingQty, scale, RoundingMode.HALF_UP);
                yield new PointValuation(closingQty, rate, val);
            }
            case "AVG_COST" -> evaluatePeriodicAverageCost(item, booksFrom, asOfDate, effectiveMovements, closingQty, scale);
            case "FIFO", "FIFO_PERPETUAL", "LIFO_ANNUAL", "LIFO_PERPETUAL" -> {
                List<MutableLayer> layers = buildSimulatedLayers(
                    item,
                    booksFrom,
                    effectiveMovements,
                    costingMethod,
                    scale
                );
                BigDecimal highVal = BigDecimal.ZERO;
                BigDecimal remLayerQty = BigDecimal.ZERO;
                for (MutableLayer layer : layers) {
                    if (layer.remainingQty.compareTo(BigDecimal.ZERO) > 0) {
                        remLayerQty = remLayerQty.add(layer.remainingQty);
                        highVal = highVal.add(layer.remainingValue);
                    }
                }
                BigDecimal finalVal = highVal.setScale(scale, RoundingMode.HALF_UP);
                BigDecimal finalRate = finalVal.divide(closingQty, scale, RoundingMode.HALF_UP);
                yield new PointValuation(closingQty, finalRate, finalVal);
            }
            default -> evaluatePeriodicAverageCost(item, booksFrom, asOfDate, effectiveMovements, closingQty, scale);
        };
    }

    /**
     * TallyPrime Periodic/Annual Weighted Average ("Avg. Cost") with MathContext.DECIMAL128:
     * Net Inward Pool = Opening + Purchases/Receipts/Mfg - Purchase Returns (Debit Notes).
     * Final rounding HALF_UP to company.currencyScale occurs strictly once on closingValue.
     */
    private PointValuation evaluatePeriodicAverageCost(
        ItemValuationMeta item,
        LocalDate booksFrom,
        LocalDate asOfDate,
        List<EffectiveMovement> movements,
        BigDecimal closingQty,
        int scale
    ) {
        LocalDate fyStart = indianFiscalYearStart(booksFrom, asOfDate);
        BigDecimal poolQty;
        BigDecimal poolValue;

        if (!fyStart.isAfter(booksFrom)) {
            poolQty = item.openingQty();
            poolValue = item.openingValue();
        } else {
            LocalDate priorFyEnd = fyStart.minusDays(1);
            List<EffectiveMovement> priorMovements = movements.stream()
                .filter(m -> !m.effectiveDate.isAfter(priorFyEnd))
                .toList();
            PointValuation priorClosing = evaluatePointValuation(
                item,
                booksFrom,
                priorFyEnd,
                priorMovements,
                "AVG_COST",
                scale
            );
            poolQty = priorClosing.closingQty().max(BigDecimal.ZERO);
            poolValue = priorClosing.closingValue().max(BigDecimal.ZERO);
        }

        for (EffectiveMovement m : movements) {
            if (m.effectiveDate.isBefore(fyStart) || m.skipPhysicalStock || m.isSameVoucherTransfer) {
                continue;
            }
            if ("INWARD".equals(m.direction) && !m.isSalesReturn) {
                poolQty = poolQty.add(m.baseQty);
                poolValue = poolValue.add(m.amount);
            } else if ("OUTWARD".equals(m.direction) && m.isPurchaseReturn) {
                poolQty = poolQty.subtract(m.baseQty);
                poolValue = poolValue.subtract(m.amount);
            }
        }

        BigDecimal highPrecAvgUnitCost;
        if (poolQty.compareTo(BigDecimal.ZERO) > 0 && poolValue.compareTo(BigDecimal.ZERO) > 0) {
            highPrecAvgUnitCost = poolValue.divide(poolQty, HIGH_PREC);
        } else {
            highPrecAvgUnitCost = item.openingRate();
        }

        BigDecimal closingValue = closingQty.multiply(highPrecAvgUnitCost, HIGH_PREC)
            .setScale(scale, RoundingMode.HALF_UP);
        BigDecimal closingRate = closingValue.divide(closingQty, scale, RoundingMode.HALF_UP);
        return new PointValuation(closingQty, closingRate, closingValue);
    }

    private List<MutableLayer> buildSimulatedLayers(
        ItemValuationMeta item,
        LocalDate booksFrom,
        List<EffectiveMovement> movements,
        String costingMethod,
        int scale
    ) {
        if ("LIFO_ANNUAL".equals(costingMethod) && !movements.isEmpty()) {
            return simulateAnnualLifoLayers(item, booksFrom, movements, scale);
        }

        List<MutableLayer> layers = new ArrayList<>();
        if (item.openingQty().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal openUnit = item.openingValue().divide(item.openingQty(), HIGH_PREC);
            layers.add(new MutableLayer(
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                booksFrom,
                item.openingQty(),
                item.openingQty(),
                item.openingValue(),
                item.openingValue(),
                openUnit
            ));
        }

        for (EffectiveMovement m : movements) {
            if (m.skipPhysicalStock || m.isSameVoucherTransfer) {
                continue;
            }
            if ("INWARD".equals(m.direction)) {
                BigDecimal layerVal = m.amount;
                if (m.isSalesReturn) {
                    BigDecimal currentAvg = currentWeightedCost(layers, item.openingRate());
                    layerVal = m.baseQty.multiply(currentAvg, HIGH_PREC);
                }
                BigDecimal unitCost = m.baseQty.compareTo(BigDecimal.ZERO) > 0
                    ? layerVal.divide(m.baseQty, HIGH_PREC)
                    : BigDecimal.ZERO;
                layers.add(new MutableLayer(
                    false,
                    m.journalId,
                    m.allocationId,
                    m.godownId,
                    m.stockBatchId,
                    m.partyLedgerId,
                    m.voucherNumber,
                    m.supplierInvoiceNo,
                    m.effectiveDate,
                    m.baseQty,
                    m.baseQty,
                    layerVal,
                    layerVal,
                    unitCost
                ));
            } else if (m.isPurchaseReturn) {
                applyPurchaseReturnToInwardLayer(layers, m);
            } else {
                consumeOutwardFromLayers(layers, m.baseQty, "LIFO_PERPETUAL".equals(costingMethod));
            }
        }
        return layers;
    }

    private List<MutableLayer> simulateAnnualLifoLayers(
        ItemValuationMeta item,
        LocalDate booksFrom,
        List<EffectiveMovement> movements,
        int scale
    ) {
        LocalDate maxDate = movements.getLast().effectiveDate;
        LocalDate fyStart = indianFiscalYearStart(booksFrom, maxDate);
        List<MutableLayer> layers = new ArrayList<>();

        if (!fyStart.isAfter(booksFrom)) {
            if (item.openingQty().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal openUnit = item.openingValue().divide(item.openingQty(), HIGH_PREC);
                layers.add(new MutableLayer(
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    booksFrom,
                    item.openingQty(),
                    item.openingQty(),
                    item.openingValue(),
                    item.openingValue(),
                    openUnit
                ));
            }
        } else {
            LocalDate priorEnd = fyStart.minusDays(1);
            List<EffectiveMovement> prior = movements.stream()
                .filter(m -> !m.effectiveDate.isAfter(priorEnd))
                .toList();
            PointValuation pv = evaluatePointValuation(item, booksFrom, priorEnd, prior, "LIFO_ANNUAL", scale);
            if (pv.closingQty().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal unit = pv.closingValue().divide(pv.closingQty(), HIGH_PREC);
                layers.add(new MutableLayer(
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    fyStart,
                    pv.closingQty(),
                    pv.closingQty(),
                    pv.closingValue(),
                    pv.closingValue(),
                    unit
                ));
            }
        }

        BigDecimal netOutwardInFy = BigDecimal.ZERO;
        for (EffectiveMovement m : movements) {
            if (m.effectiveDate.isBefore(fyStart) || m.skipPhysicalStock || m.isSameVoucherTransfer) {
                continue;
            }
            if ("INWARD".equals(m.direction)) {
                if (m.isSalesReturn) {
                    netOutwardInFy = netOutwardInFy.subtract(m.baseQty);
                } else {
                    BigDecimal unit = m.baseQty.compareTo(BigDecimal.ZERO) > 0
                        ? m.amount.divide(m.baseQty, HIGH_PREC)
                        : BigDecimal.ZERO;
                    layers.add(new MutableLayer(
                        false,
                        m.journalId,
                        m.allocationId,
                        m.godownId,
                        m.stockBatchId,
                        m.partyLedgerId,
                        m.voucherNumber,
                        m.supplierInvoiceNo,
                        m.effectiveDate,
                        m.baseQty,
                        m.baseQty,
                        m.amount,
                        m.amount,
                        unit
                    ));
                }
            } else if (m.isPurchaseReturn) {
                applyPurchaseReturnToInwardLayer(layers, m);
            } else {
                netOutwardInFy = netOutwardInFy.add(m.baseQty);
            }
        }

        if (netOutwardInFy.compareTo(BigDecimal.ZERO) > 0) {
            consumeOutwardFromLayers(layers, netOutwardInFy, true);
        }
        return layers;
    }

    private void applyPurchaseReturnToInwardLayer(List<MutableLayer> layers, EffectiveMovement debitNote) {
        BigDecimal remReturnQty = debitNote.baseQty;
        BigDecimal returnUnitRate = debitNote.baseQty.compareTo(BigDecimal.ZERO) > 0
            ? debitNote.amount.divide(debitNote.baseQty, 6, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        // 1. Exact linkedInwardAllocationId match
        if (debitNote.linkedInwardAllocationId != null) {
            for (MutableLayer layer : layers) {
                if (debitNote.linkedInwardAllocationId.equals(layer.sourceAllocationId)
                    && layer.remainingQty.compareTo(BigDecimal.ZERO) > 0) {
                    remReturnQty = deductFromSpecificLayer(layer, remReturnQty, debitNote.amount, debitNote.baseQty);
                    if (remReturnQty.compareTo(BigDecimal.ZERO) <= 0) {
                        return;
                    }
                }
            }
        }

        // 2. Match supplier invoice / voucher number + party
        if (debitNote.supplierInvoiceNo != null && !debitNote.supplierInvoiceNo.isBlank()) {
            for (int i = layers.size() - 1; i >= 0; i--) {
                MutableLayer layer = layers.get(i);
                if (layer.remainingQty.compareTo(BigDecimal.ZERO) > 0
                    && (debitNote.supplierInvoiceNo.equalsIgnoreCase(layer.supplierInvoiceNo)
                        || debitNote.supplierInvoiceNo.equalsIgnoreCase(layer.voucherNumber))) {
                    remReturnQty = deductFromSpecificLayer(layer, remReturnQty, debitNote.amount, debitNote.baseQty);
                    if (remReturnQty.compareTo(BigDecimal.ZERO) <= 0) {
                        return;
                    }
                }
            }
        }

        // 3. Match same party + matching unit cost (or matching unit cost across inward layers)
        for (int pass = 0; pass < 2; pass++) {
            boolean requireSameParty = pass == 0 && debitNote.partyLedgerId != null;
            for (int i = layers.size() - 1; i >= 0; i--) {
                MutableLayer layer = layers.get(i);
                if (layer.remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                if (requireSameParty && !debitNote.partyLedgerId.equals(layer.partyLedgerId)) {
                    continue;
                }
                BigDecimal layerUnit6 = layer.unitCostHighPrec.setScale(6, RoundingMode.HALF_UP);
                if (layerUnit6.compareTo(returnUnitRate) == 0) {
                    remReturnQty = deductFromSpecificLayer(layer, remReturnQty, debitNote.amount, debitNote.baseQty);
                    if (remReturnQty.compareTo(BigDecimal.ZERO) <= 0) {
                        return;
                    }
                }
            }
        }

        // 4. Fallback: reduce latest available inward layer
        consumeOutwardFromLayers(layers, remReturnQty, true);
    }

    private BigDecimal deductFromSpecificLayer(
        MutableLayer layer,
        BigDecimal remReturnQty,
        BigDecimal totalReturnAmount,
        BigDecimal totalReturnQty
    ) {
        BigDecimal take = remReturnQty.min(layer.remainingQty);
        BigDecimal takeVal = totalReturnQty.compareTo(BigDecimal.ZERO) > 0
            ? totalReturnAmount.multiply(take, HIGH_PREC).divide(totalReturnQty, HIGH_PREC)
            : take.multiply(layer.unitCostHighPrec, HIGH_PREC);
        layer.inwardQty = layer.inwardQty.subtract(take).max(BigDecimal.ZERO);
        layer.remainingQty = layer.remainingQty.subtract(take).max(BigDecimal.ZERO);
        layer.inwardValue = layer.inwardValue.subtract(takeVal).max(BigDecimal.ZERO);
        layer.remainingValue = layer.remainingValue.subtract(takeVal).max(BigDecimal.ZERO);
        if (layer.remainingQty.compareTo(BigDecimal.ZERO) > 0) {
            layer.unitCostHighPrec = layer.remainingValue.divide(layer.remainingQty, HIGH_PREC);
        }
        return remReturnQty.subtract(take);
    }

    private void consumeOutwardFromLayers(List<MutableLayer> layers, BigDecimal outwardQty, boolean lifoOrder) {
        BigDecimal rem = outwardQty;
        if (lifoOrder) {
            for (int i = layers.size() - 1; i >= 0 && rem.compareTo(BigDecimal.ZERO) > 0; i--) {
                rem = takeFromLayer(layers.get(i), rem);
            }
        } else {
            for (int i = 0; i < layers.size() && rem.compareTo(BigDecimal.ZERO) > 0; i++) {
                rem = takeFromLayer(layers.get(i), rem);
            }
        }
    }

    private BigDecimal takeFromLayer(MutableLayer layer, BigDecimal rem) {
        if (layer.remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
            return rem;
        }
        if (layer.remainingQty.compareTo(rem) <= 0) {
            BigDecimal taken = layer.remainingQty;
            layer.remainingQty = BigDecimal.ZERO;
            layer.remainingValue = BigDecimal.ZERO;
            return rem.subtract(taken);
        }
        layer.remainingQty = layer.remainingQty.subtract(rem);
        layer.remainingValue = layer.remainingQty.multiply(layer.unitCostHighPrec, HIGH_PREC);
        return BigDecimal.ZERO;
    }

    private BigDecimal currentWeightedCost(List<MutableLayer> layers, BigDecimal fallbackRate) {
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalVal = BigDecimal.ZERO;
        for (MutableLayer layer : layers) {
            if (layer.remainingQty.compareTo(BigDecimal.ZERO) > 0) {
                totalQty = totalQty.add(layer.remainingQty);
                totalVal = totalVal.add(layer.remainingValue);
            }
        }
        if (totalQty.compareTo(BigDecimal.ZERO) > 0) {
            return totalVal.divide(totalQty, HIGH_PREC);
        }
        return fallbackRate;
    }

    private BigDecimal estimateOutwardConsumptionValue(
        UUID companyId,
        ItemValuationMeta item,
        LocalDate booksFrom,
        LocalDate asOfDate,
        BigDecimal requiredQty,
        int scale,
        Map<UUID, UomConversionMeta> uoms
    ) {
        List<RawMovement> raw = loadActiveMovementsForItem(companyId, item.id(), asOfDate);
        List<EffectiveMovement> eff = buildEffectiveMovements(raw, item, uoms, asOfDate);
        PointValuation before = evaluatePointValuation(item, booksFrom, asOfDate, eff, item.costingMethod(), scale);
        if (before.closingQty().compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal fallback = item.standardCost() != null ? item.standardCost() : item.openingRate();
            return requiredQty.multiply(fallback, HIGH_PREC).setScale(scale, RoundingMode.HALF_UP);
        }
        if ("FIFO".equals(item.costingMethod())
            || "FIFO_PERPETUAL".equals(item.costingMethod())
            || "LIFO_ANNUAL".equals(item.costingMethod())
            || "LIFO_PERPETUAL".equals(item.costingMethod())) {
            List<MutableLayer> layers = buildSimulatedLayers(item, booksFrom, eff, item.costingMethod(), scale);
            BigDecimal rem = requiredQty;
            BigDecimal consumedVal = BigDecimal.ZERO;
            boolean lifo = item.costingMethod().startsWith("LIFO");
            int start = lifo ? layers.size() - 1 : 0;
            int step = lifo ? -1 : 1;
            for (int i = start; i >= 0 && i < layers.size() && rem.compareTo(BigDecimal.ZERO) > 0; i += step) {
                MutableLayer l = layers.get(i);
                if (l.remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal take = rem.min(l.remainingQty);
                consumedVal = consumedVal.add(take.multiply(l.unitCostHighPrec, HIGH_PREC));
                rem = rem.subtract(take);
            }
            if (rem.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal unit = before.closingValue().divide(before.closingQty(), HIGH_PREC);
                consumedVal = consumedVal.add(rem.multiply(unit, HIGH_PREC));
            }
            return consumedVal.setScale(scale, RoundingMode.HALF_UP);
        }
        BigDecimal unitHigh = before.closingValue().divide(before.closingQty(), HIGH_PREC);
        return requiredQty.multiply(unitHigh, HIGH_PREC).setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal computeManufacturingAdditionalCost(
        BomHeaderRow bom,
        BigDecimal finishedQty,
        BigDecimal componentTotalCost,
        List<SupplyChainReportDtos.ManufacturingAdditionalCostInput> overrides,
        int scale
    ) {
        BigDecimal total = BigDecimal.ZERO;
        if (overrides != null && !overrides.isEmpty()) {
            for (SupplyChainReportDtos.ManufacturingAdditionalCostInput in : overrides) {
                if (in == null) {
                    continue;
                }
                if (in.amount() != null && !in.amount().isBlank()) {
                    BigDecimal amt = ExactMoneyValidator.parseAndValidateBoundedDecimal(
                        in.amount(),
                        6,
                        BigDecimal.ZERO,
                        null,
                        false,
                        "additionalCost.amount"
                    );
                    total = total.add(amt.setScale(scale, RoundingMode.HALF_UP));
                } else if (in.pct() != null && !in.pct().isBlank()) {
                    BigDecimal pct = ExactMoneyValidator.parseAndValidateBoundedDecimal(
                        in.pct(),
                        6,
                        BigDecimal.ZERO,
                        HUNDRED,
                        false,
                        "additionalCost.pct"
                    );
                    total = total.add(componentTotalCost.multiply(pct, HIGH_PREC)
                        .divide(HUNDRED, scale, RoundingMode.HALF_UP));
                } else if (in.rateOrAmount() != null && !in.rateOrAmount().isBlank()) {
                    BigDecimal val = ExactMoneyValidator.parseAndValidateBoundedDecimal(
                        in.rateOrAmount(),
                        6,
                        BigDecimal.ZERO,
                        null,
                        false,
                        "additionalCost.rateOrAmount"
                    );
                    if ("PERCENTAGE".equalsIgnoreCase(in.calculationBasis())) {
                        total = total.add(componentTotalCost.multiply(val, HIGH_PREC)
                            .divide(HUNDRED, scale, RoundingMode.HALF_UP));
                    } else {
                        total = total.add(val.setScale(scale, RoundingMode.HALF_UP));
                    }
                }
            }
            return total;
        }

        List<InventoryMasterDtos.BomAdditionalCostRuleDto> rules = parseBomCostRules(bom.rulesJson());
        for (InventoryMasterDtos.BomAdditionalCostRuleDto r : rules) {
            String rawVal = r.rateOrAmount() != null ? r.rateOrAmount() : (r.pct() != null ? r.pct() : r.amount());
            if (rawVal == null || rawVal.isBlank()) {
                continue;
            }
            BigDecimal val = new BigDecimal(rawVal.trim());
            boolean isPct = "PERCENTAGE".equalsIgnoreCase(r.calculationBasis())
                || (r.calculationBasis() == null && r.pct() != null);
            if (isPct) {
                total = total.add(componentTotalCost.multiply(val, HIGH_PREC)
                    .divide(HUNDRED, scale, RoundingMode.HALF_UP));
            } else {
                total = total.add(val.multiply(finishedQty, HIGH_PREC)
                    .divide(bom.unitOfManufacture(), scale, RoundingMode.HALF_UP));
            }
        }
        return total;
    }

    private List<InventoryMasterDtos.BomAdditionalCostRuleDto> parseBomCostRules(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                json,
                new TypeReference<List<InventoryMasterDtos.BomAdditionalCostRuleDto>>() { }
            );
        } catch (JacksonException ignored) {
            return List.of();
        }
    }

    /**
     * Resolves trackingNo deduplication (Receipt Note -> Purchase, Delivery Note -> Sales) and
     * inter-godown transfer pairing on Stock Journals.
     */
    private List<EffectiveMovement> buildEffectiveMovements(
        List<RawMovement> rawMovements,
        ItemValuationMeta item,
        Map<UUID, UomConversionMeta> uoms,
        LocalDate maxEffectiveDate
    ) {
        List<RawMovement> filtered = maxEffectiveDate == null
            ? rawMovements
            : rawMovements.stream().filter(m -> !m.effectiveDate.isAfter(maxEffectiveDate)).toList();

        // Map trackingNo -> billed PURCHASE amount so RECEIPT_NOTE takes final invoice value without double-counting qty
        Map<String, BigDecimal> purchaseBilledAmountByTracking = new HashMap<>();
        Set<String> activeDeliveryTrackings = new HashSet<>();
        Set<String> activeReceiptTrackings = new HashSet<>();
        Map<UUID, BigDecimal> stockJournalInQty = new HashMap<>();
        Map<UUID, BigDecimal> stockJournalOutQty = new HashMap<>();

        for (RawMovement m : filtered) {
            BigDecimal baseQty = convertToBaseUom(m.actualQty, m.uomId, item, uoms);
            if (m.trackingNo != null && !m.trackingNo.isBlank()) {
                String tKey = m.trackingNo.trim();
                if ("DELIVERY_NOTE".equals(m.baseCategory)) {
                    activeDeliveryTrackings.add(tKey);
                } else if ("RECEIPT_NOTE".equals(m.baseCategory)) {
                    activeReceiptTrackings.add(tKey);
                } else if ("PURCHASE".equals(m.baseCategory)) {
                    purchaseBilledAmountByTracking.merge(tKey, m.amount, BigDecimal::add);
                }
            }
            if ("STOCK_JOURNAL".equals(m.baseCategory)) {
                if ("INWARD".equals(m.direction)) {
                    stockJournalInQty.merge(m.journalId, baseQty, BigDecimal::add);
                } else {
                    stockJournalOutQty.merge(m.journalId, baseQty, BigDecimal::add);
                }
            }
        }

        Set<String> consumedPurchaseTrackingOverride = new HashSet<>();
        List<EffectiveMovement> result = new ArrayList<>(filtered.size());

        for (RawMovement m : filtered) {
            BigDecimal baseQty = convertToBaseUom(m.actualQty, m.uomId, item, uoms);
            String tKey = m.trackingNo == null || m.trackingNo.isBlank() ? null : m.trackingNo.trim();

            boolean skipPhysical = false;
            BigDecimal effectiveAmount = m.amount;

            if ("SALES".equals(m.baseCategory) && tKey != null && activeDeliveryTrackings.contains(tKey)) {
                skipPhysical = true;
            } else if ("PURCHASE".equals(m.baseCategory) && tKey != null && activeReceiptTrackings.contains(tKey)) {
                skipPhysical = true;
            } else if ("RECEIPT_NOTE".equals(m.baseCategory)
                && tKey != null
                && purchaseBilledAmountByTracking.containsKey(tKey)
                && consumedPurchaseTrackingOverride.add(tKey)) {
                effectiveAmount = purchaseBilledAmountByTracking.get(tKey);
            }

            boolean sameVoucherTransfer = false;
            if ("STOCK_JOURNAL".equals(m.baseCategory)) {
                BigDecimal inQ = stockJournalInQty.getOrDefault(m.journalId, BigDecimal.ZERO);
                BigDecimal outQ = stockJournalOutQty.getOrDefault(m.journalId, BigDecimal.ZERO);
                if (inQ.compareTo(BigDecimal.ZERO) > 0 && inQ.compareTo(outQ) == 0) {
                    sameVoucherTransfer = true;
                }
            }

            boolean isPurchaseReturn = "OUTWARD".equals(m.direction)
                && ("DEBIT_NOTE".equals(m.baseCategory) || "REJECTIONS_OUT".equals(m.baseCategory));
            boolean isSalesReturn = "INWARD".equals(m.direction)
                && ("CREDIT_NOTE".equals(m.baseCategory) || "REJECTIONS_IN".equals(m.baseCategory));

            result.add(new EffectiveMovement(
                m.allocationId,
                m.journalId,
                m.voucherDate,
                m.effectiveDate,
                m.voucherTypeName,
                m.baseCategory,
                m.voucherNumber,
                m.supplierInvoiceNo,
                m.partyLedgerId,
                m.partyName,
                m.direction,
                baseQty,
                effectiveAmount,
                m.trackingNo,
                m.linkedInwardAllocationId,
                m.firstGodownId,
                m.firstStockBatchId,
                skipPhysical,
                sameVoucherTransfer,
                isPurchaseReturn,
                isSalesReturn
            ));
        }
        return result;
    }

    // =========================================================================
    // Database Loaders (Strictly Materialized, Non-Nested)
    // =========================================================================

    public Map<UUID, UomConversionMeta> loadCompanyUoms(UUID companyId) {
        Map<UUID, UomConversionMeta> map = new LinkedHashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT id, uom_type, symbol, decimal_places, first_uom_id, conversion_factor, second_uom_id
              FROM mc_uom
             WHERE company_id = ?
            """,
            rs -> {
                UUID id = rs.getObject("id", UUID.class);
                map.put(id, new UomConversionMeta(
                    id,
                    rs.getString("uom_type"),
                    rs.getString("symbol"),
                    rs.getShort("decimal_places"),
                    rs.getObject("first_uom_id", UUID.class),
                    rs.getBigDecimal("conversion_factor"),
                    rs.getObject("second_uom_id", UUID.class)
                ));
            },
            companyId
        );
        return map;
    }

    public ItemValuationMeta loadSingleItemMeta(UUID companyId, UUID stockItemId) {
        List<ItemValuationMeta> rows = businessJdbcTemplate.query(
            """
            SELECT si.id, si.name, si.stock_group_id, sg.name AS group_name, si.hsn_sac_code,
                   si.base_uom_id, u.symbol AS base_uom_symbol,
                   si.alternate_uom_id, si.conversion_numerator, si.conversion_denominator,
                   si.costing_method, si.standard_cost,
                   si.opening_qty, si.opening_rate, si.opening_value
              FROM mc_stock_item si
              JOIN mc_uom u
                ON u.company_id = si.company_id AND u.id = si.base_uom_id
              LEFT JOIN mc_stock_group sg
                ON sg.company_id = si.company_id AND sg.id = si.stock_group_id
             WHERE si.company_id = ? AND si.id = ?
            """,
            (rs, rowNum) -> new ItemValuationMeta(
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
            companyId,
            stockItemId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<ItemValuationMeta> loadActiveItems(UUID companyId, Set<UUID> filterGroupIds) {
        List<ItemValuationMeta> all = businessJdbcTemplate.query(
            """
            SELECT si.id, si.name, si.stock_group_id, sg.name AS group_name, si.hsn_sac_code,
                   si.base_uom_id, u.symbol AS base_uom_symbol,
                   si.alternate_uom_id, si.conversion_numerator, si.conversion_denominator,
                   si.costing_method, si.standard_cost,
                   si.opening_qty, si.opening_rate, si.opening_value
              FROM mc_stock_item si
              JOIN mc_uom u
                ON u.company_id = si.company_id AND u.id = si.base_uom_id
              LEFT JOIN mc_stock_group sg
                ON sg.company_id = si.company_id AND sg.id = si.stock_group_id
             WHERE si.company_id = ? AND si.active = true
             ORDER BY lower(si.name) ASC, si.id ASC
            """,
            (rs, rowNum) -> new ItemValuationMeta(
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
            companyId
        );
        if (filterGroupIds == null) {
            return all;
        }
        return all.stream()
            .filter(i -> i.stockGroupId() != null && filterGroupIds.contains(i.stockGroupId()))
            .toList();
    }

    private List<RawMovement> loadActiveMovementsForItem(UUID companyId, UUID stockItemId, LocalDate upToDate) {
        StringBuilder sql = new StringBuilder(
            """
            SELECT ia.id AS alloc_id, ia.journal_id, ia.stock_item_id, ia.movement_direction,
                   ia.uom_id, ia.actual_qty, ia.amount, ia.tracking_no, ia.linked_inward_allocation_id,
                   j.voucher_date, j.effective_date, j.voucher_number, j.supplier_invoice_no,
                   j.party_ledger_id, pl.name AS party_name,
                   vt.name AS vt_name, vt.base_category,
                   fb.godown_id AS first_godown_id, fb.stock_batch_id AS first_batch_id
              FROM mc_inventory_allocation ia
              JOIN mc_journal j
                ON j.company_id = ia.company_id AND j.id = ia.journal_id
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
              LEFT JOIN mc_ledger pl
                ON pl.company_id = j.company_id AND pl.id = j.party_ledger_id
              LEFT JOIN LATERAL (
                  SELECT ba.godown_id, ba.stock_batch_id
                    FROM mc_batch_allocation ba
                   WHERE ba.company_id = ia.company_id
                     AND ba.inventory_allocation_id = ia.id
                   ORDER BY ba.id ASC
                   LIMIT 1
              ) fb ON true
             WHERE ia.company_id = ?
               AND ia.stock_item_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND vt.moves_inventory = true
            """
        );
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        params.add(stockItemId);
        if (upToDate != null) {
            sql.append(" AND j.effective_date <= ?");
            params.add(upToDate);
        }
        sql.append(
            """
             ORDER BY j.effective_date ASC,
                      COALESCE(j.post_seq, j.alter_id) ASC,
                      COALESCE(j.voucher_seq_no, 9223372036854775807) ASC,
                      ia.line_no ASC,
                      ia.id ASC
            """
        );
        return businessJdbcTemplate.query(
            sql.toString(),
            (rs, rowNum) -> mapRawMovement(rs),
            params.toArray()
        );
    }

    private Map<UUID, List<RawMovement>> loadAllActiveMovementsUpTo(UUID companyId, LocalDate upToDate) {
        Map<UUID, List<RawMovement>> byItem = new HashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT ia.id AS alloc_id, ia.journal_id, ia.stock_item_id, ia.movement_direction,
                   ia.uom_id, ia.actual_qty, ia.amount, ia.tracking_no, ia.linked_inward_allocation_id,
                   j.voucher_date, j.effective_date, j.voucher_number, j.supplier_invoice_no,
                   j.party_ledger_id, pl.name AS party_name,
                   vt.name AS vt_name, vt.base_category,
                   fb.godown_id AS first_godown_id, fb.stock_batch_id AS first_batch_id
              FROM mc_inventory_allocation ia
              JOIN mc_journal j
                ON j.company_id = ia.company_id AND j.id = ia.journal_id
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
              LEFT JOIN mc_ledger pl
                ON pl.company_id = j.company_id AND pl.id = j.party_ledger_id
              LEFT JOIN LATERAL (
                  SELECT ba.godown_id, ba.stock_batch_id
                    FROM mc_batch_allocation ba
                   WHERE ba.company_id = ia.company_id
                     AND ba.inventory_allocation_id = ia.id
                   ORDER BY ba.id ASC
                   LIMIT 1
              ) fb ON true
             WHERE ia.company_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND vt.moves_inventory = true
               AND j.effective_date <= ?
             ORDER BY j.effective_date ASC,
                      COALESCE(j.post_seq, j.alter_id) ASC,
                      COALESCE(j.voucher_seq_no, 9223372036854775807) ASC,
                      ia.line_no ASC,
                      ia.id ASC
            """,
            rs -> {
                RawMovement rm = mapRawMovement(rs);
                byItem.computeIfAbsent(rm.stockItemId, ignored -> new ArrayList<>()).add(rm);
            },
            companyId,
            upToDate
        );
        return byItem;
    }

    private RawMovement mapRawMovement(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RawMovement(
            rs.getObject("alloc_id", UUID.class),
            rs.getObject("journal_id", UUID.class),
            rs.getObject("stock_item_id", UUID.class),
            rs.getObject("voucher_date", LocalDate.class),
            rs.getObject("effective_date", LocalDate.class),
            rs.getString("vt_name"),
            rs.getString("base_category"),
            rs.getString("voucher_number"),
            rs.getString("supplier_invoice_no"),
            rs.getObject("party_ledger_id", UUID.class),
            rs.getString("party_name"),
            rs.getString("movement_direction"),
            rs.getObject("uom_id", UUID.class),
            rs.getBigDecimal("actual_qty"),
            rs.getBigDecimal("amount"),
            rs.getString("tracking_no"),
            rs.getObject("linked_inward_allocation_id", UUID.class),
            rs.getObject("first_godown_id", UUID.class),
            rs.getObject("first_batch_id", UUID.class)
        );
    }

    private Map<GodownItemKey, BigDecimal> computeGodownItemQuantitiesAsOf(
        UUID companyId,
        LocalDate asOfDate,
        Map<UUID, ItemValuationMeta> itemsById,
        Map<UUID, UomConversionMeta> uoms
    ) {
        Map<GodownItemKey, BigDecimal> map = new LinkedHashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT godown_id, stock_item_id, SUM(opening_qty) AS open_qty
              FROM mc_stock_batch
             WHERE company_id = ?
             GROUP BY godown_id, stock_item_id
            """,
            rs -> {
                BigDecimal oq = rs.getBigDecimal("open_qty");
                if (oq != null && oq.compareTo(BigDecimal.ZERO) != 0) {
                    map.put(
                        new GodownItemKey(rs.getObject("godown_id", UUID.class), rs.getObject("stock_item_id", UUID.class)),
                        oq
                    );
                }
            },
            companyId
        );

        Set<String> activeDeliveryTrackings = loadActiveTrackingKeys(companyId, "DELIVERY_NOTE", asOfDate);
        Set<String> activeReceiptTrackings = loadActiveTrackingKeys(companyId, "RECEIPT_NOTE", asOfDate);

        businessJdbcTemplate.query(
            """
            SELECT ba.godown_id, ia.stock_item_id, ia.movement_direction, ia.uom_id,
                   ia.tracking_no, vt.base_category, ba.actual_qty
              FROM mc_batch_allocation ba
              JOIN mc_inventory_allocation ia
                ON ia.company_id = ba.company_id AND ia.id = ba.inventory_allocation_id
              JOIN mc_journal j
                ON j.company_id = ia.company_id AND j.id = ia.journal_id
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
             WHERE ba.company_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND vt.moves_inventory = true
               AND j.effective_date <= ?
            """,
            rs -> {
                UUID itemId = rs.getObject("stock_item_id", UUID.class);
                ItemValuationMeta item = itemsById.get(itemId);
                if (item == null) {
                    return;
                }
                String cat = rs.getString("base_category");
                String trk = rs.getString("tracking_no");
                if (trk != null && !trk.isBlank()) {
                    String key = itemId + "|" + trk.trim();
                    if ("SALES".equals(cat) && activeDeliveryTrackings.contains(key)) {
                        return;
                    }
                    if ("PURCHASE".equals(cat) && activeReceiptTrackings.contains(key)) {
                        return;
                    }
                }
                BigDecimal baseQty = rs.getBigDecimal("actual_qty");
                BigDecimal signed = "INWARD".equals(rs.getString("movement_direction")) ? baseQty : baseQty.negate();
                map.merge(new GodownItemKey(rs.getObject("godown_id", UUID.class), itemId), signed, BigDecimal::add);
            },
            companyId,
            asOfDate
        );
        return map;
    }

    public Map<UUID, BigDecimal> computeBatchClosingQuantitiesAsOf(
        UUID companyId,
        UUID filterStockItemId,
        LocalDate asOfDate,
        Map<UUID, ItemValuationMeta> itemsById,
        Map<UUID, UomConversionMeta> uoms
    ) {
        Map<UUID, BigDecimal> map = new HashMap<>();
        StringBuilder openSql = new StringBuilder(
            "SELECT id, opening_qty FROM mc_stock_batch WHERE company_id = ?"
        );
        List<Object> openParams = new ArrayList<>();
        openParams.add(companyId);
        if (filterStockItemId != null) {
            openSql.append(" AND stock_item_id = ?");
            openParams.add(filterStockItemId);
        }
        businessJdbcTemplate.query(
            openSql.toString(),
            rs -> {
                map.put(rs.getObject("id", UUID.class), rs.getBigDecimal("opening_qty"));
            },
            openParams.toArray()
        );

        Set<String> activeDeliveryTrackings = loadActiveTrackingKeys(companyId, "DELIVERY_NOTE", asOfDate);
        Set<String> activeReceiptTrackings = loadActiveTrackingKeys(companyId, "RECEIPT_NOTE", asOfDate);

        StringBuilder movSql = new StringBuilder(
            """
            SELECT ba.stock_batch_id, ia.stock_item_id, ia.movement_direction,
                   ia.tracking_no, vt.base_category, ba.actual_qty
              FROM mc_batch_allocation ba
              JOIN mc_inventory_allocation ia
                ON ia.company_id = ba.company_id AND ia.id = ba.inventory_allocation_id
              JOIN mc_journal j
                ON j.company_id = ia.company_id AND j.id = ia.journal_id
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
             WHERE ba.company_id = ?
               AND ba.stock_batch_id IS NOT NULL
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND vt.moves_inventory = true
               AND j.effective_date <= ?
            """
        );
        List<Object> movParams = new ArrayList<>();
        movParams.add(companyId);
        movParams.add(asOfDate);
        if (filterStockItemId != null) {
            movSql.append(" AND ia.stock_item_id = ?");
            movParams.add(filterStockItemId);
        }
        businessJdbcTemplate.query(
            movSql.toString(),
            rs -> {
                UUID batchId = rs.getObject("stock_batch_id", UUID.class);
                UUID itemId = rs.getObject("stock_item_id", UUID.class);
                String cat = rs.getString("base_category");
                String trk = rs.getString("tracking_no");
                if (trk != null && !trk.isBlank()) {
                    String key = itemId + "|" + trk.trim();
                    if ("SALES".equals(cat) && activeDeliveryTrackings.contains(key)) {
                        return;
                    }
                    if ("PURCHASE".equals(cat) && activeReceiptTrackings.contains(key)) {
                        return;
                    }
                }
                BigDecimal qty = rs.getBigDecimal("actual_qty");
                BigDecimal signed = "INWARD".equals(rs.getString("movement_direction")) ? qty : qty.negate();
                map.merge(batchId, signed, BigDecimal::add);
            },
            movParams.toArray()
        );
        return map;
    }

    private Set<String> loadActiveTrackingKeys(UUID companyId, String baseCategory, LocalDate asOfDate) {
        Set<String> keys = new HashSet<>();
        businessJdbcTemplate.query(
            """
            SELECT ia.stock_item_id, ia.tracking_no
              FROM mc_inventory_allocation ia
              JOIN mc_journal j
                ON j.company_id = ia.company_id AND j.id = ia.journal_id
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
             WHERE ia.company_id = ?
               AND ia.tracking_no IS NOT NULL
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND vt.base_category = ?
               AND j.effective_date <= ?
            """,
            rs -> {
                String trk = rs.getString("tracking_no");
                if (trk != null && !trk.isBlank()) {
                    keys.add(rs.getObject("stock_item_id", UUID.class) + "|" + trk.trim());
                }
            },
            companyId,
            baseCategory,
            asOfDate
        );
        return keys;
    }

    private List<GroupRow> loadStockGroups(UUID companyId) {
        return businessJdbcTemplate.query(
            """
            SELECT id, name, parent_stock_group_id, should_quantities_of_items_be_added
              FROM mc_stock_group
             WHERE company_id = ? AND active = true
             ORDER BY depth_level ASC, lower(name) ASC
            """,
            (rs, rowNum) -> new GroupRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getObject("parent_stock_group_id", UUID.class),
                rs.getBoolean("should_quantities_of_items_be_added")
            ),
            companyId
        );
    }

    private Set<UUID> resolveSubtreeGroupIds(List<GroupRow> groups, UUID rootGroupId) {
        if (rootGroupId == null) {
            return Set.of();
        }
        Set<UUID> allowed = new HashSet<>();
        allowed.add(rootGroupId);
        boolean added = true;
        while (added) {
            added = false;
            for (GroupRow g : groups) {
                if (g.parentStockGroupId() != null
                    && allowed.contains(g.parentStockGroupId())
                    && allowed.add(g.id())) {
                    added = true;
                }
            }
        }
        return allowed;
    }

    private Set<UUID> resolveSubtreeGodownIds(UUID companyId, UUID rootGodownId, Map<UUID, String> godownNames) {
        record GodownParent(UUID id, String name, UUID parentId) { }
        List<GodownParent> all = businessJdbcTemplate.query(
            """
            SELECT id, name, parent_godown_id
              FROM mc_godown
             WHERE company_id = ? AND active = true
             ORDER BY depth_level ASC, lower(name) ASC
            """,
            (rs, rowNum) -> new GodownParent(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getObject("parent_godown_id", UUID.class)
            ),
            companyId
        );
        for (GodownParent g : all) {
            godownNames.put(g.id(), g.name());
        }
        if (rootGodownId == null) {
            return godownNames.keySet();
        }
        Set<UUID> allowed = new HashSet<>();
        allowed.add(rootGodownId);
        boolean added = true;
        while (added) {
            added = false;
            for (GodownParent g : all) {
                if (g.parentId() != null && allowed.contains(g.parentId()) && allowed.add(g.id())) {
                    added = true;
                }
            }
        }
        return allowed;
    }

    public UUID findMainLocationGodownId(UUID companyId) {
        List<UUID> ids = businessJdbcTemplate.query(
            """
            SELECT id FROM mc_godown
             WHERE company_id = ? AND is_system_godown = true
             ORDER BY created_at ASC
             LIMIT 1
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId
        );
        if (!ids.isEmpty()) {
            return ids.getFirst();
        }
        List<UUID> fallback = businessJdbcTemplate.query(
            "SELECT id FROM mc_godown WHERE company_id = ? ORDER BY created_at ASC LIMIT 1",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId
        );
        if (fallback.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-GODOWN-NOT-FOUND",
                "Default Godown Missing",
                "No godown exists in company " + companyId + "."
            );
        }
        return fallback.getFirst();
    }

    private UUID findOpeningGodownId(UUID companyId, UUID stockItemId) {
        List<UUID> ids = businessJdbcTemplate.query(
            """
            SELECT godown_id FROM mc_stock_batch
             WHERE company_id = ? AND stock_item_id = ? AND opening_qty > 0
             ORDER BY created_at ASC LIMIT 1
            """,
            (rs, rowNum) -> rs.getObject("godown_id", UUID.class),
            companyId,
            stockItemId
        );
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private UUID findOpeningBatchId(UUID companyId, UUID stockItemId) {
        List<UUID> ids = businessJdbcTemplate.query(
            """
            SELECT id FROM mc_stock_batch
             WHERE company_id = ? AND stock_item_id = ? AND opening_qty > 0
             ORDER BY created_at ASC LIMIT 1
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            stockItemId
        );
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private String normalizeOptionalMethodOverride(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String norm = raw.trim().toUpperCase(Locale.ROOT);
        if (!VALID_COSTING_METHODS.contains(norm)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-INVALID-COSTING-METHOD",
                "Invalid Valuation Method",
                "valuationMethodOverride must be one of " + VALID_COSTING_METHODS + "."
            );
        }
        return norm;
    }

    private static LocalDate indianFiscalYearStart(LocalDate booksFrom, LocalDate date) {
        int startYear = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        LocalDate candidate = LocalDate.of(startYear, 4, 1);
        return candidate.isBefore(booksFrom) ? booksFrom : candidate;
    }

    public static BigDecimal normalizeQtyScale6(BigDecimal val) {
        BigDecimal scaled = val.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        return scaled.scale() < 0 ? new BigDecimal(scaled.toPlainString()) : scaled;
    }

    private static BigDecimal normalizeValScale6(BigDecimal val) {
        BigDecimal scaled = val.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        return scaled.scale() < 0 ? new BigDecimal(scaled.toPlainString()) : scaled;
    }

    // =========================================================================
    // Internal Records & Accumulators
    // =========================================================================

    public record UomConversionMeta(
        UUID id,
        String uomType,
        String symbol,
        int decimalPlaces,
        UUID firstUomId,
        BigDecimal conversionFactor,
        UUID secondUomId
    ) {
    }

    public record ItemValuationMeta(
        UUID id,
        String name,
        UUID stockGroupId,
        String stockGroupName,
        String hsnSacCode,
        UUID baseUomId,
        String baseUomSymbol,
        UUID alternateUomId,
        BigDecimal conversionNumerator,
        BigDecimal conversionDenominator,
        String costingMethod,
        BigDecimal standardCost,
        BigDecimal openingQty,
        BigDecimal openingRate,
        BigDecimal openingValue
    ) {
    }

    public record PointValuation(
        BigDecimal closingQty,
        BigDecimal closingRate,
        BigDecimal closingValue
    ) {
    }

    private record PeriodValuationResult(
        BigDecimal openingQty,
        BigDecimal openingRate,
        BigDecimal openingValue,
        BigDecimal inwardQty,
        BigDecimal inwardValue,
        BigDecimal outwardQty,
        BigDecimal outwardValue,
        BigDecimal closingQty,
        BigDecimal closingRate,
        BigDecimal closingValue
    ) {
    }

    private record RawMovement(
        UUID allocationId,
        UUID journalId,
        UUID stockItemId,
        LocalDate voucherDate,
        LocalDate effectiveDate,
        String voucherTypeName,
        String baseCategory,
        String voucherNumber,
        String supplierInvoiceNo,
        UUID partyLedgerId,
        String partyName,
        String direction,
        UUID uomId,
        BigDecimal actualQty,
        BigDecimal amount,
        String trackingNo,
        UUID linkedInwardAllocationId,
        UUID firstGodownId,
        UUID firstStockBatchId
    ) {
    }

    private record EffectiveMovement(
        UUID allocationId,
        UUID journalId,
        LocalDate voucherDate,
        LocalDate effectiveDate,
        String voucherTypeName,
        String baseCategory,
        String voucherNumber,
        String supplierInvoiceNo,
        UUID partyLedgerId,
        String partyName,
        String direction,
        BigDecimal baseQty,
        BigDecimal amount,
        String trackingNo,
        UUID linkedInwardAllocationId,
        UUID godownId,
        UUID stockBatchId,
        boolean skipPhysicalStock,
        boolean isSameVoucherTransfer,
        boolean isPurchaseReturn,
        boolean isSalesReturn
    ) {
    }

    private static final class MutableLayer {
        final boolean isOpening;
        final UUID sourceJournalId;
        final UUID sourceAllocationId;
        final UUID godownId;
        final UUID stockBatchId;
        final UUID partyLedgerId;
        final String voucherNumber;
        final String supplierInvoiceNo;
        final LocalDate layerDate;
        BigDecimal inwardQty;
        BigDecimal remainingQty;
        BigDecimal inwardValue;
        BigDecimal remainingValue;
        BigDecimal unitCostHighPrec;

        MutableLayer(
            boolean isOpening,
            UUID sourceJournalId,
            UUID sourceAllocationId,
            UUID godownId,
            UUID stockBatchId,
            UUID partyLedgerId,
            String voucherNumber,
            String supplierInvoiceNo,
            LocalDate layerDate,
            BigDecimal inwardQty,
            BigDecimal remainingQty,
            BigDecimal inwardValue,
            BigDecimal remainingValue,
            BigDecimal unitCostHighPrec
        ) {
            this.isOpening = isOpening;
            this.sourceJournalId = sourceJournalId;
            this.sourceAllocationId = sourceAllocationId;
            this.godownId = godownId;
            this.stockBatchId = stockBatchId;
            this.partyLedgerId = partyLedgerId;
            this.voucherNumber = voucherNumber;
            this.supplierInvoiceNo = supplierInvoiceNo;
            this.layerDate = layerDate;
            this.inwardQty = inwardQty;
            this.remainingQty = remainingQty;
            this.inwardValue = inwardValue;
            this.remainingValue = remainingValue;
            this.unitCostHighPrec = unitCostHighPrec;
        }
    }

    private record GroupRow(
        UUID id,
        String name,
        UUID parentStockGroupId,
        boolean shouldAddQty
    ) {
    }

    private static final class GroupAccumulator {
        final GroupRow group;
        BigDecimal closingQty = BigDecimal.ZERO;
        BigDecimal openingValue = BigDecimal.ZERO;
        BigDecimal inwardValue = BigDecimal.ZERO;
        BigDecimal outwardValue = BigDecimal.ZERO;
        BigDecimal closingValue = BigDecimal.ZERO;

        GroupAccumulator(GroupRow group) {
            this.group = group;
        }
    }

    private record GodownItemKey(UUID godownId, UUID stockItemId) {
    }

    private record BomHeaderRow(
        UUID id,
        UUID stockItemId,
        String stockItemName,
        String uomSymbol,
        String bomName,
        BigDecimal unitOfManufacture,
        String rulesJson
    ) {
    }

    private record BomCompRow(
        int lineNo,
        UUID componentItemId,
        String componentType,
        UUID godownId,
        BigDecimal quantity,
        BigDecimal costAllocationPct
    ) {
    }
}
