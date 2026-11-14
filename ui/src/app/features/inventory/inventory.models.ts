import { GstApplicability, GstSupplyType } from '../masters/masters.models';

/**
 * MoneyCraft 1.0.0 — Phase 4 Inventory, Warehouse, Batch, Price List, BOM
 * & Valuation Reporting Domain Models.
 *
 * All quantities, unit rates, conversion factors, MRP values, percentages, and
 * monetary amounts are string-encoded exact decimals (zero IEEE-754 float math).
 */

export type UomType = 'SIMPLE' | 'COMPOUND';

export type StockCostingMethod =
  | 'AVG_COST'
  | 'FIFO'
  | 'FIFO_PERPETUAL'
  | 'LIFO_ANNUAL'
  | 'LIFO_PERPETUAL'
  | 'LAST_PURCHASE_COST'
  | 'STD_COST'
  | 'AT_ZERO_COST';

export type MarketValuationMethod =
  | 'AVG_PRICE'
  | 'LAST_SALE_PRICE'
  | 'STD_PRICE'
  | 'AT_ZERO_PRICE';

export type InventoryMovementDirection = 'INWARD' | 'OUTWARD';

export type OrderVoucherKind = 'SALES_ORDER' | 'PURCHASE_ORDER';

export interface GstUqcOption {
  readonly code: string;
  readonly description: string;
}

export const GST_UQC_OPTIONS: readonly GstUqcOption[] = [
  { code: 'NOS', description: 'NOS — Numbers' },
  { code: 'PCS', description: 'PCS — Pieces' },
  { code: 'KGS', description: 'KGS — Kilograms' },
  { code: 'GMS', description: 'GMS — Grams' },
  { code: 'MTR', description: 'MTR — Meters' },
  { code: 'LTR', description: 'LTR — Litres' },
  { code: 'MLT', description: 'MLT — Millilitres' },
  { code: 'BOX', description: 'BOX — Boxes' },
  { code: 'PAC', description: 'PAC — Packs' },
  { code: 'SET', description: 'SET — Sets' },
  { code: 'BTL', description: 'BTL — Bottles' },
  { code: 'BAG', description: 'BAG — Bags' },
  { code: 'DOZ', description: 'DOZ — Dozens' },
  { code: 'UNT', description: 'UNT — Units' },
  { code: 'OTH', description: 'OTH — Others' },
];

export interface CostingMethodOption {
  readonly value: StockCostingMethod;
  readonly label: string;
}

export const STOCK_COSTING_METHOD_OPTIONS: readonly CostingMethodOption[] = [
  { value: 'AVG_COST', label: 'Avg. Cost' },
  { value: 'FIFO', label: 'FIFO' },
  { value: 'FIFO_PERPETUAL', label: 'FIFO Perpetual' },
  { value: 'LIFO_ANNUAL', label: 'LIFO Annual' },
  { value: 'LIFO_PERPETUAL', label: 'LIFO Perpetual' },
  { value: 'LAST_PURCHASE_COST', label: 'Last Purchase Cost' },
  { value: 'STD_COST', label: 'Std. Cost' },
  { value: 'AT_ZERO_COST', label: 'At Zero Cost' },
];

export interface MarketValuationOption {
  readonly value: MarketValuationMethod;
  readonly label: string;
}

export const MARKET_VALUATION_METHOD_OPTIONS: readonly MarketValuationOption[] = [
  { value: 'AVG_PRICE', label: 'Avg. Price' },
  { value: 'LAST_SALE_PRICE', label: 'Last Sale Price' },
  { value: 'STD_PRICE', label: 'Std. Price' },
  { value: 'AT_ZERO_PRICE', label: 'At Zero Price' },
];

// ---------------------------------------------------------------------------
// 1. Units of Measure (UOM)
// ---------------------------------------------------------------------------

export interface UomMaster {
  readonly id: string;
  readonly companyId?: string;
  readonly uomType: UomType;
  readonly symbol: string;
  readonly formalName: string | null;
  readonly gstUqcCode: string | null;
  readonly decimalPlaces: number;
  readonly firstUomId: string | null;
  readonly firstUomSymbol?: string | null;
  readonly conversionFactor: string | null;
  readonly secondUomId: string | null;
  readonly secondUomSymbol?: string | null;
  readonly active?: boolean;
  readonly versionNo: number;
}

export interface UpsertUomRequest {
  readonly uomType: UomType;
  readonly symbol: string;
  readonly formalName?: string | null;
  readonly gstUqcCode?: string | null;
  readonly decimalPlaces: number;
  readonly firstUomId?: string | null;
  readonly conversionFactor?: string | null;
  readonly secondUomId?: string | null;
  readonly active?: boolean;
  readonly versionNo?: number;
}

// ---------------------------------------------------------------------------
// 2. Godowns / Locations
// ---------------------------------------------------------------------------

export interface GodownMaster {
  readonly id: string;
  readonly companyId?: string;
  readonly name: string;
  readonly aliasCode: string | null;
  readonly parentGodownId: string | null;
  readonly parentGodownName?: string | null;
  readonly address: string | null;
  readonly allowStorageOfMaterials: boolean;
  readonly ourStockWithThirdParty: boolean;
  readonly thirdPartyStockWithUs: boolean;
  readonly isSystemGodown: boolean;
  readonly active?: boolean;
  readonly versionNo: number;
}

export interface UpsertGodownRequest {
  readonly name: string;
  readonly aliasCode?: string | null;
  readonly parentGodownId?: string | null;
  readonly address?: string | null;
  readonly allowStorageOfMaterials: boolean;
  readonly ourStockWithThirdParty: boolean;
  readonly thirdPartyStockWithUs: boolean;
  readonly active?: boolean;
  readonly versionNo?: number;
}

// ---------------------------------------------------------------------------
// 3. Stock Groups & Stock Categories
// ---------------------------------------------------------------------------

export interface StockGroupMaster {
  readonly id: string;
  readonly companyId?: string;
  readonly name: string;
  readonly aliasCode: string | null;
  readonly parentStockGroupId: string | null;
  readonly parentStockGroupName?: string | null;
  readonly shouldQuantitiesOfItemsBeAdded: boolean;
  readonly hsnSacCode: string | null;
  readonly gstRatePct: string | null;
  readonly cessRatePct: string | null;
  readonly isSystemGroup?: boolean;
  readonly active?: boolean;
  readonly versionNo: number;
}

export interface UpsertStockGroupRequest {
  readonly name: string;
  readonly aliasCode?: string | null;
  readonly parentStockGroupId?: string | null;
  readonly shouldQuantitiesOfItemsBeAdded: boolean;
  readonly hsnSacCode?: string | null;
  readonly gstRatePct?: string | null;
  readonly cessRatePct?: string | null;
  readonly active?: boolean;
  readonly versionNo?: number;
}

export interface StockCategoryMaster {
  readonly id: string;
  readonly companyId?: string;
  readonly name: string;
  readonly aliasCode: string | null;
  readonly parentStockCategoryId: string | null;
  readonly parentStockCategoryName?: string | null;
  readonly isSystemCategory?: boolean;
  readonly active?: boolean;
  readonly versionNo: number;
}

export interface UpsertStockCategoryRequest {
  readonly name: string;
  readonly aliasCode?: string | null;
  readonly parentStockCategoryId?: string | null;
  readonly active?: boolean;
  readonly versionNo?: number;
}

// ---------------------------------------------------------------------------
// 4. Stock Items & Batches
// ---------------------------------------------------------------------------

export interface StockItemMaster {
  readonly id: string;
  readonly companyId?: string;
  readonly name: string;
  readonly aliasCode: string | null;
  readonly partNo: string | null;
  readonly description: string | null;
  readonly stockGroupId: string | null;
  readonly stockGroupName?: string | null;
  readonly stockCategoryId: string | null;
  readonly stockCategoryName?: string | null;
  readonly baseUomId: string;
  readonly baseUomSymbol: string;
  readonly baseUomDecimals: number;
  readonly alternateUomId: string | null;
  readonly alternateUomSymbol?: string | null;
  readonly conversionNumerator: string | null;
  readonly conversionDenominator: string | null;
  readonly maintainInBatches: boolean;
  readonly trackDateOfMfg: boolean;
  readonly useExpiryDates: boolean;
  readonly costingMethod: StockCostingMethod;
  readonly marketValuationMethod: MarketValuationMethod;
  readonly standardCost: string | null;
  readonly standardSellingPrice: string | null;
  readonly openingQty: string | null;
  readonly openingRate: string | null;
  readonly openingValue: string | null;
  readonly openingGodownId: string | null;
  readonly openingBatchName: string | null;
  readonly openingMfgDate: string | null;
  readonly openingExpiryDate: string | null;
  readonly gstApplicability: GstApplicability;
  readonly gstSupplyType: GstSupplyType | null;
  readonly hsnSacCode: string | null;
  readonly gstRatePct: string | null;
  readonly cessRatePct: string | null;
  readonly cessPerUnit: string | null;
  readonly isTobaccoMrpItem: boolean;
  readonly mrpPerUnit: string | null;
  readonly defaultSalesLedgerId: string | null;
  readonly defaultPurchaseLedgerId: string | null;
  readonly active?: boolean;
  readonly versionNo: number;
}

export interface UpsertStockItemRequest {
  readonly name: string;
  readonly aliasCode?: string | null;
  readonly partNo?: string | null;
  readonly description?: string | null;
  readonly stockGroupId?: string | null;
  readonly stockCategoryId?: string | null;
  readonly baseUomId: string;
  readonly alternateUomId?: string | null;
  readonly conversionNumerator?: string | null;
  readonly conversionDenominator?: string | null;
  readonly maintainInBatches: boolean;
  readonly trackDateOfMfg: boolean;
  readonly useExpiryDates: boolean;
  readonly costingMethod: StockCostingMethod;
  readonly marketValuationMethod: MarketValuationMethod;
  readonly standardCost?: string | null;
  readonly standardSellingPrice?: string | null;
  readonly openingQty?: string | null;
  readonly openingRate?: string | null;
  readonly openingValue?: string | null;
  readonly openingGodownId?: string | null;
  readonly openingBatchName?: string | null;
  readonly openingMfgDate?: string | null;
  readonly openingExpiryDate?: string | null;
  readonly gstApplicability: GstApplicability;
  readonly gstSupplyType?: GstSupplyType | null;
  readonly hsnSacCode?: string | null;
  readonly gstRatePct?: string | null;
  readonly cessRatePct?: string | null;
  readonly cessPerUnit?: string | null;
  readonly isTobaccoMrpItem: boolean;
  readonly mrpPerUnit?: string | null;
  readonly defaultSalesLedgerId?: string | null;
  readonly defaultPurchaseLedgerId?: string | null;
  readonly active?: boolean;
  readonly versionNo?: number;
}

export interface StockBatchDto {
  readonly batchId?: string;
  readonly stockItemId: string;
  readonly godownId?: string | null;
  readonly godownName?: string | null;
  readonly batchName: string;
  readonly mfgDate?: string | null;
  readonly expiryDate?: string | null;
  readonly closingQty: string;
  readonly rate?: string | null;
  readonly mrpPerUnit?: string | null;
  readonly isExpired?: boolean;
}

// ---------------------------------------------------------------------------
// 5. Price Lists & Quantity Tiers
// ---------------------------------------------------------------------------

export interface PriceTierDto {
  readonly stockItemId: string;
  readonly stockItemName?: string;
  readonly fromQty: string;
  readonly lessThanQty: string | null;
  readonly rate: string;
  readonly discountPct: string;
}

export interface PriceListMaster {
  readonly id: string;
  readonly companyId?: string;
  readonly priceLevelName: string;
  readonly applicableFrom: string;
  readonly currencyCode?: string;
  readonly tiers: readonly PriceTierDto[];
  readonly active?: boolean;
  readonly versionNo: number;
}

export interface UpsertPriceListRequest {
  readonly priceLevelName: string;
  readonly applicableFrom: string;
  readonly currencyCode?: string;
  readonly tiers: readonly PriceTierDto[];
  readonly active?: boolean;
  readonly versionNo?: number;
}

export interface PriceLookupResponseDto {
  readonly stockItemId: string;
  readonly priceLevelName: string;
  readonly rate: string;
  readonly discountPct: string;
}

// ---------------------------------------------------------------------------
// 6. Bills of Materials (BOM) & Manufacturing Preview
// ---------------------------------------------------------------------------

export type BomComponentRole = 'COMPONENT' | 'CO_PRODUCT' | 'BY_PRODUCT' | 'SCRAP';
export type BomCostRuleBasis = 'PERCENTAGE' | 'FIXED_AMOUNT';

export interface BomComponentDto {
  readonly stockItemId: string;
  readonly stockItemName?: string;
  readonly componentType?: BomComponentRole;
  readonly godownId?: string | null;
  readonly quantity: string;
  readonly uomId?: string | null;
  readonly costAllocationPct?: string | null;
}

export interface BomAdditionalCostRuleDto {
  readonly ledgerId: string;
  readonly ledgerName?: string;
  readonly calculationBasis: BomCostRuleBasis;
  readonly rateOrAmount: string;
}

export interface BomMaster {
  readonly id: string;
  readonly companyId?: string;
  readonly stockItemId: string;
  readonly stockItemName?: string;
  readonly bomName: string;
  readonly unitOfManufacture: string;
  readonly components: readonly BomComponentDto[];
  readonly additionalCostRules: readonly BomAdditionalCostRuleDto[];
  readonly active?: boolean;
  readonly versionNo: number;
}

export interface UpsertBomRequest {
  readonly stockItemId: string;
  readonly bomName: string;
  readonly unitOfManufacture: string;
  readonly components: readonly BomComponentDto[];
  readonly additionalCostRules: readonly BomAdditionalCostRuleDto[];
  readonly active?: boolean;
  readonly versionNo?: number;
}

export interface ManufacturingPreviewRequest {
  readonly bomId: string;
  readonly finishedStockItemId?: string;
  readonly manufacturedQty: string;
  readonly targetGodownId?: string | null;
  readonly asOfDate?: string;
}

export interface ManufacturingPreviewComponentLine {
  readonly stockItemId: string;
  readonly stockItemName: string;
  readonly componentType?: BomComponentRole;
  readonly godownId?: string | null;
  readonly uomId?: string | null;
  readonly uomSymbol?: string;
  readonly requiredQty: string;
  readonly unitRate: string;
  readonly amount: string;
}

export interface ManufacturingPreviewResponse {
  readonly bomId: string;
  readonly bomName: string;
  readonly finishedStockItemId: string;
  readonly finishedStockItemName?: string;
  readonly manufacturedQty: string;
  readonly components: readonly ManufacturingPreviewComponentLine[];
  readonly componentTotalCost: string;
  readonly additionalCostTotal: string;
  readonly totalManufacturingCost: string;
  readonly effectiveUnitRate: string;
}

// ---------------------------------------------------------------------------
// 7. Open Sales / Purchase Orders & Supply-Chain Reports
// ---------------------------------------------------------------------------

export interface OpenOrderLineDto {
  readonly orderVoucherId?: string;
  readonly orderNo: string;
  readonly orderDate: string;
  readonly dueDate?: string | null;
  readonly orderType: OrderVoucherKind;
  readonly partyLedgerId: string;
  readonly partyLedgerName?: string;
  readonly stockItemId: string;
  readonly stockItemName: string;
  readonly uomId?: string | null;
  readonly uomSymbol?: string;
  readonly orderedQty: string;
  readonly pendingQty: string;
  readonly rate: string;
  readonly discountPct?: string | null;
}

export interface StockSummaryItemDto {
  readonly stockItemId: string;
  readonly stockItemName: string;
  readonly stockGroupId: string | null;
  readonly stockGroupName: string | null;
  readonly hsnSacCode?: string | null;
  readonly uomSymbol: string;
  readonly costingMethod: StockCostingMethod;
  readonly openingQty: string;
  readonly openingRate: string;
  readonly openingValue: string;
  readonly inwardQty: string;
  readonly inwardValue: string;
  readonly outwardQty: string;
  readonly outwardValue: string;
  readonly closingQty: string;
  readonly closingRate: string;
  readonly closingValue: string;
}

export interface StockSummaryGroupNodeDto {
  readonly stockGroupId: string;
  readonly stockGroupName: string;
  readonly parentStockGroupId?: string | null;
  readonly closingQty?: string | null;
  readonly openingValue: string;
  readonly inwardValue: string;
  readonly outwardValue: string;
  readonly closingValue: string;
}

export interface StockSummaryReportDto {
  readonly fromDate?: string;
  readonly toDate?: string;
  readonly valuationMethodOverride?: StockCostingMethod | null;
  readonly groups: readonly StockSummaryGroupNodeDto[];
  readonly items: readonly StockSummaryItemDto[];
  readonly totalOpeningValue: string;
  readonly totalInwardValue: string;
  readonly totalOutwardValue: string;
  readonly totalClosingQty: string;
  readonly totalClosingValue: string;
}

export interface GodownSummaryRowDto {
  readonly godownId: string;
  readonly godownName: string;
  readonly stockItemId?: string | null;
  readonly stockItemName?: string | null;
  readonly uomSymbol?: string | null;
  readonly closingQty: string;
  readonly closingRate?: string | null;
  readonly closingValue: string;
}

export interface GodownSummaryReportDto {
  readonly rows: readonly GodownSummaryRowDto[];
  readonly totalClosingValue: string;
}

export interface MovementAnalysisVoucherRowDto {
  readonly voucherId: string;
  readonly voucherDate: string;
  readonly voucherTypeName: string;
  readonly voucherNumber: string;
  readonly particulars: string;
  readonly movementDirection: InventoryMovementDirection;
  readonly quantity: string;
  readonly rate: string;
  readonly value: string;
}

export interface MovementAnalysisReportDto {
  readonly stockItemId: string;
  readonly stockItemName: string;
  readonly uomSymbol: string;
  readonly inwardQty: string;
  readonly inwardValue: string;
  readonly outwardQty: string;
  readonly outwardValue: string;
  readonly movements: readonly MovementAnalysisVoucherRowDto[];
}

export interface BatchAgeingRowDto {
  readonly stockItemId: string;
  readonly stockItemName: string;
  readonly godownName: string;
  readonly batchName: string;
  readonly mfgDate: string | null;
  readonly expiryDate: string | null;
  readonly daysToExpiry: number | null;
  readonly isExpired: boolean;
  readonly closingQty: string;
  readonly closingValue: string;
}

export interface BatchAgeingReportDto {
  readonly asOfDate: string;
  readonly batches: readonly BatchAgeingRowDto[];
}
