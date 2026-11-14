package com.raster.moneycraft.inventory.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Exact string-decimal DTOs for Phase 4 Inventory Masters:
 * UOMs, Godowns, Stock Groups, Stock Categories, Stock Items, Batches, Price Lists, and BOMs.
 */
public final class InventoryMasterDtos {

    private InventoryMasterDtos() {
    }

    // -------------------------------------------------------------------------
    // 1. Units of Measure (Simple & Compound)
    // -------------------------------------------------------------------------

    public record UpsertUomRequest(
        String uomType,
        String symbol,
        String formalName,
        String gstUqcCode,
        Integer decimalPlaces,
        UUID firstUomId,
        String conversionFactor,
        UUID secondUomId,
        Boolean active,
        Long versionNo
    ) {
    }

    public record UomDto(
        UUID id,
        UUID companyId,
        String uomType,
        String symbol,
        String formalName,
        String gstUqcCode,
        int decimalPlaces,
        UUID firstUomId,
        String firstUomSymbol,
        String conversionFactor,
        UUID secondUomId,
        String secondUomSymbol,
        boolean active,
        long masterSeqId,
        long alterId,
        long versionNo,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    // -------------------------------------------------------------------------
    // 2. Hierarchical Godowns / Locations
    // -------------------------------------------------------------------------

    public record UpsertGodownRequest(
        String name,
        String aliasCode,
        UUID parentGodownId,
        String address,
        Boolean allowStorageOfMaterials,
        Boolean ourStockWithThirdParty,
        Boolean thirdPartyStockWithUs,
        Boolean active,
        Long versionNo
    ) {
    }

    public record GodownDto(
        UUID id,
        UUID companyId,
        String name,
        String aliasCode,
        UUID parentGodownId,
        String parentGodownName,
        String address,
        boolean allowStorageOfMaterials,
        boolean ourStockWithThirdParty,
        boolean thirdPartyStockWithUs,
        String treePath,
        int depthLevel,
        boolean isSystemGodown,
        boolean active,
        long masterSeqId,
        long alterId,
        long versionNo,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    // -------------------------------------------------------------------------
    // 3. Stock Groups & Stock Categories
    // -------------------------------------------------------------------------

    public record UpsertStockGroupRequest(
        String name,
        String aliasCode,
        UUID parentStockGroupId,
        Boolean shouldQuantitiesOfItemsBeAdded,
        String hsnSacCode,
        String gstRatePct,
        String cessRatePct,
        Boolean active,
        Long versionNo
    ) {
    }

    public record StockGroupDto(
        UUID id,
        UUID companyId,
        String name,
        String aliasCode,
        UUID parentStockGroupId,
        String parentStockGroupName,
        boolean shouldQuantitiesOfItemsBeAdded,
        String hsnSacCode,
        String gstRatePct,
        String cessRatePct,
        String treePath,
        int depthLevel,
        boolean active,
        long masterSeqId,
        long alterId,
        long versionNo,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record UpsertStockCategoryRequest(
        String name,
        String aliasCode,
        UUID parentStockCategoryId,
        Boolean active,
        Long versionNo
    ) {
    }

    public record StockCategoryDto(
        UUID id,
        UUID companyId,
        String name,
        String aliasCode,
        UUID parentStockCategoryId,
        String parentStockCategoryName,
        String treePath,
        int depthLevel,
        boolean active,
        long masterSeqId,
        long alterId,
        long versionNo,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    // -------------------------------------------------------------------------
    // 4. Stock Items & Batches
    // -------------------------------------------------------------------------

    public record UpsertStockItemRequest(
        String name,
        String aliasCode,
        String partNo,
        String description,
        UUID stockGroupId,
        UUID stockCategoryId,
        UUID baseUomId,
        UUID alternateUomId,
        String conversionNumerator,
        String conversionDenominator,
        Boolean maintainInBatches,
        Boolean trackDateOfMfg,
        Boolean useExpiryDates,
        String costingMethod,
        String marketValuationMethod,
        String standardCost,
        String standardSellingPrice,
        String openingQty,
        String openingRate,
        String openingValue,
        UUID openingGodownId,
        String openingBatchName,
        LocalDate openingMfgDate,
        LocalDate openingExpiryDate,
        String gstApplicability,
        String gstSupplyType,
        String hsnSacCode,
        String gstRatePct,
        String cessRatePct,
        String cessPerUnit,
        Boolean isTobaccoMrpItem,
        String mrpPerUnit,
        UUID defaultSalesLedgerId,
        UUID defaultPurchaseLedgerId,
        Boolean active,
        Long versionNo
    ) {
    }

    public record StockItemDto(
        UUID id,
        UUID companyId,
        String name,
        String aliasCode,
        String partNo,
        String description,
        UUID stockGroupId,
        String stockGroupName,
        UUID stockCategoryId,
        String stockCategoryName,
        UUID baseUomId,
        String baseUomSymbol,
        int baseUomDecimals,
        UUID alternateUomId,
        String alternateUomSymbol,
        String conversionNumerator,
        String conversionDenominator,
        boolean maintainInBatches,
        boolean trackDateOfMfg,
        boolean useExpiryDates,
        String costingMethod,
        String marketValuationMethod,
        String standardCost,
        String standardSellingPrice,
        String openingQty,
        String openingRate,
        String openingValue,
        UUID openingGodownId,
        String openingBatchName,
        LocalDate openingMfgDate,
        LocalDate openingExpiryDate,
        String gstApplicability,
        String gstSupplyType,
        String hsnSacCode,
        String gstRatePct,
        String cessRatePct,
        String cessPerUnit,
        boolean isTobaccoMrpItem,
        String mrpPerUnit,
        UUID defaultSalesLedgerId,
        UUID defaultPurchaseLedgerId,
        boolean active,
        long masterSeqId,
        long alterId,
        long versionNo,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record StockBatchDto(
        UUID id,
        UUID batchId,
        UUID stockItemId,
        UUID godownId,
        String godownName,
        String batchName,
        LocalDate mfgDate,
        LocalDate expiryDate,
        String mrpPerUnit,
        String openingQty,
        String closingQty,
        String rate,
        boolean expired,
        @JsonProperty("isExpired") boolean isExpired
    ) {
    }

    // -------------------------------------------------------------------------
    // 5. Price Lists & Quantity Slabs
    // -------------------------------------------------------------------------

    public record PriceTierDto(
        UUID id,
        UUID stockItemId,
        String stockItemName,
        String fromQty,
        String lessThanQty,
        String rate,
        String discountPct
    ) {
    }

    public record UpsertPriceListRequest(
        String priceLevelName,
        @JsonAlias({"applicableFrom"}) LocalDate effectiveFrom,
        String currencyCode,
        List<PriceTierDto> tiers,
        Boolean active,
        Long versionNo
    ) {
    }

    public record PriceListDto(
        UUID id,
        UUID companyId,
        String priceLevelName,
        LocalDate effectiveFrom,
        LocalDate applicableFrom,
        String currencyCode,
        List<PriceTierDto> tiers,
        boolean active,
        long versionNo,
        Instant createdAt
    ) {
    }

    public record PriceLookupResponse(
        UUID priceListId,
        String priceLevelName,
        LocalDate effectiveFrom,
        UUID stockItemId,
        String currencyCode,
        String rate,
        String discountPct
    ) {
    }

    // -------------------------------------------------------------------------
    // 6. Bills of Materials (Multi-BOM)
    // -------------------------------------------------------------------------

    public record BomComponentDto(
        Integer lineNo,
        @JsonAlias({"stockItemId"}) UUID componentItemId,
        UUID stockItemId,
        String componentItemName,
        String stockItemName,
        String componentType,
        UUID godownId,
        UUID uomId,
        String quantity,
        String costAllocationPct
    ) {
    }

    public record BomAdditionalCostRuleDto(
        UUID ledgerId,
        String ledgerName,
        String description,
        String calculationBasis,
        String rateOrAmount,
        String pct,
        String amount
    ) {
    }

    public record UpsertBomRequest(
        UUID stockItemId,
        String bomName,
        String unitOfManufacture,
        List<BomAdditionalCostRuleDto> additionalCostRules,
        List<BomComponentDto> components,
        Boolean active,
        Long versionNo
    ) {
    }

    public record BomDto(
        UUID id,
        UUID companyId,
        UUID stockItemId,
        String stockItemName,
        String bomName,
        String unitOfManufacture,
        List<BomAdditionalCostRuleDto> additionalCostRules,
        List<BomComponentDto> components,
        boolean active,
        long versionNo,
        Instant createdAt,
        Instant updatedAt
    ) {
    }
}
