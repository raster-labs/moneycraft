package com.raster.moneycraft.inventory.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Exact string-decimal DTOs for Phase 4 Supply Chain, Manufacturing Preview,
 * and 8-Method Valuation Reports.
 */
public final class SupplyChainReportDtos {

    private SupplyChainReportDtos() {
    }

    public record OpenOrderLineDto(
        UUID orderVoucherId,
        UUID orderAllocationId,
        String orderNo,
        LocalDate orderDate,
        LocalDate dueDate,
        String orderType,
        UUID partyLedgerId,
        String partyLedgerName,
        UUID stockItemId,
        String stockItemName,
        UUID uomId,
        String uomSymbol,
        String orderedQty,
        String deliveredReceivedQty,
        String billedQty,
        String rejectedQty,
        String pendingQty,
        String trackingNo,
        String status,
        String rate,
        String discountPct
    ) {
    }

    public record ManufacturingAdditionalCostInput(
        UUID ledgerId,
        String description,
        String calculationBasis,
        String rateOrAmount,
        String pct,
        String amount
    ) {
    }

    public record ManufacturingPreviewRequest(
        UUID bomId,
        UUID finishedStockItemId,
        @JsonAlias({"manufacturedQty"}) String finishedQty,
        @JsonAlias({"targetGodownId"}) UUID godownId,
        LocalDate asOfDate,
        List<ManufacturingAdditionalCostInput> additionalCosts
    ) {
    }

    public record ManufacturingPreviewLineDto(
        int lineNo,
        UUID stockItemId,
        UUID componentItemId,
        String stockItemName,
        String componentType,
        String movementDirection,
        UUID godownId,
        UUID uomId,
        String uomSymbol,
        String requiredQty,
        String quantity,
        String unitRate,
        String rate,
        String amount,
        String costAllocationPct
    ) {
    }

    public record ManufacturingPreviewResponse(
        UUID bomId,
        String bomName,
        UUID finishedStockItemId,
        String finishedStockItemName,
        String finishedUomSymbol,
        String manufacturedQty,
        String finishedQty,
        List<ManufacturingPreviewLineDto> components,
        List<ManufacturingPreviewLineDto> coProductsAndScrap,
        String componentTotalCost,
        String scrapDeductionTotal,
        String additionalCostTotal,
        String totalManufacturingCost,
        String finishedTotalCost,
        String effectiveUnitRate,
        String finishedUnitCost
    ) {
    }

    public record StockSummaryItemDto(
        UUID stockItemId,
        String stockItemName,
        UUID stockGroupId,
        String stockGroupName,
        String hsnSacCode,
        String uomSymbol,
        String costingMethod,
        String openingQty,
        String openingRate,
        String openingValue,
        String inwardQty,
        String inwardValue,
        String outwardQty,
        String outwardValue,
        String closingQty,
        String closingRate,
        String closingValue
    ) {
    }

    public record StockSummaryGroupNodeDto(
        UUID stockGroupId,
        String stockGroupName,
        UUID parentStockGroupId,
        String closingQty,
        String openingValue,
        String inwardValue,
        String outwardValue,
        String closingValue
    ) {
    }

    public record StockSummaryReportDto(
        LocalDate fromDate,
        LocalDate toDate,
        String valuationMethodOverride,
        List<StockSummaryGroupNodeDto> groups,
        List<StockSummaryItemDto> items,
        String totalOpeningValue,
        String totalInwardValue,
        String totalOutwardValue,
        String totalClosingQty,
        String totalClosingValue
    ) {
    }

    public record GodownSummaryRowDto(
        UUID godownId,
        String godownName,
        UUID stockItemId,
        String stockItemName,
        String uomSymbol,
        String closingQty,
        String closingRate,
        String closingValue
    ) {
    }

    public record GodownSummaryReportDto(
        LocalDate asOfDate,
        List<GodownSummaryRowDto> rows,
        String totalClosingValue
    ) {
    }

    public record MovementAnalysisVoucherRowDto(
        UUID voucherId,
        LocalDate voucherDate,
        String voucherTypeName,
        String voucherNumber,
        String particulars,
        String movementDirection,
        String quantity,
        String rate,
        String value
    ) {
    }

    public record MovementAnalysisReportDto(
        UUID stockItemId,
        String stockItemName,
        String uomSymbol,
        LocalDate fromDate,
        LocalDate toDate,
        String inwardQty,
        String inwardValue,
        String outwardQty,
        String outwardValue,
        List<MovementAnalysisVoucherRowDto> movements
    ) {
    }

    public record BatchAgeingRowDto(
        UUID batchId,
        UUID stockItemId,
        String stockItemName,
        UUID godownId,
        String godownName,
        String batchName,
        LocalDate mfgDate,
        LocalDate expiryDate,
        Long daysToExpiry,
        @JsonProperty("isExpired") boolean isExpired,
        String closingQty,
        String closingValue
    ) {
    }

    public record BatchAgeingReportDto(
        LocalDate asOfDate,
        List<BatchAgeingRowDto> batches
    ) {
    }
}
