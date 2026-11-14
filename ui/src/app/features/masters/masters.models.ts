/**
 * MoneyCraft 1.0.0 — Phase 2 Accounting Masters & Chart of Accounts Domain Models
 *
 * All monetary amounts, percentages, and forex rates are represented strictly as
 * string-encoded exact decimals (never IEEE-754 number math).
 */

export interface CursorPage<T> {
  readonly items: readonly T[];
  readonly nextCursor: string | null;
  readonly hasMore: boolean;
}

export type AccountNature = 'ASSETS' | 'LIABILITIES' | 'INCOME' | 'EXPENSES';

export type PurchaseAllocationMethod =
  | 'NOT_APPLICABLE'
  | 'APPROPRIATE_BY_QTY'
  | 'APPROPRIATE_BY_VALUE';

export type BalanceSide = 'DR' | 'CR';

export interface AccountGroup {
  readonly id: string;
  readonly companyId: string;
  readonly name: string;
  readonly aliasCode: string | null;
  readonly parentGroupId: string | null;
  readonly parentGroupName?: string | null;
  readonly nature: AccountNature;
  readonly affectsGrossProfit: boolean;
  readonly behavesLikeSubledger: boolean;
  readonly netDebitCreditForReporting: boolean;
  readonly usedForCalculation: boolean;
  readonly methodToAllocatePurchase: PurchaseAllocationMethod;
  readonly scheduleIiiLineCode: string | null;
  readonly defaultGstRatePct: string | null;
  readonly defaultHsnSacCode: string | null;
  readonly treePath?: string;
  readonly depthLevel: number;
  readonly isSystemGroup: boolean;
  readonly sortOrder: number;
  readonly versionNo: number;
}

export interface CreateOrUpdateAccountGroupPayload {
  readonly name: string;
  readonly aliasCode: string | null;
  readonly parentGroupId: string | null;
  readonly nature: AccountNature;
  readonly affectsGrossProfit: boolean;
  readonly behavesLikeSubledger: boolean;
  readonly netDebitCreditForReporting: boolean;
  readonly usedForCalculation: boolean;
  readonly methodToAllocatePurchase: PurchaseAllocationMethod;
  readonly scheduleIiiLineCode?: string | null;
  readonly defaultGstRatePct: string | null;
  readonly defaultHsnSacCode: string | null;
  readonly versionNo?: number;
}

export type LedgerTypeaheadFilter =
  | 'ALL'
  | 'CASH_BANK'
  | 'PARTY'
  | 'SALES'
  | 'PURCHASE'
  | 'DUTIES_TAXES'
  | 'NON_CASH_BANK'
  | 'EXPENSE_INCOME';

export type LedgerTaxType = 'NONE' | 'GST' | 'TDS' | 'TCS' | 'OTHERS';

export type LedgerDutyHead =
  | 'CGST'
  | 'SGST_UTGST'
  | 'IGST'
  | 'CESS'
  | 'TDS_SEC'
  | 'TCS_SEC'
  | 'OTHERS';

export type GstApplicability = 'APPLICABLE' | 'NOT_APPLICABLE' | 'UNDEFINED';
export type GstSupplyType = 'GOODS' | 'SERVICES' | 'CAPITAL_GOODS';
export type AppropriateForType = 'NONE' | 'GST' | 'BOTH_QTY' | 'BOTH_VALUE';
export type AppropriationBasis = 'BY_VALUE' | 'BY_QTY';
export type LedgerRoundingMethod = 'NONE' | 'NORMAL_HALF_UP' | 'UPWARD' | 'DOWNWARD';

export type PartyRegistrationType =
  | 'UNKNOWN'
  | 'REGULAR'
  | 'COMPOSITION'
  | 'UNREGISTERED'
  | 'CONSUMER'
  | 'OVERSEAS'
  | 'SEZ'
  | 'GOVERNMENT_ENTITY';

export interface BankDetailsDto {
  readonly accountHolder: string | null;
  readonly accountNumber: string | null;
  readonly ifscCode: string | null;
  readonly bankName: string | null;
  readonly branch: string | null;
  readonly upiId: string | null;
}

export interface Ledger {
  readonly id: string;
  readonly companyId: string;
  readonly name: string;
  readonly aliasCode: string | null;
  readonly groupId: string | null;
  readonly groupName?: string | null;
  readonly openingBalance: string;
  readonly openingSide: BalanceSide;
  readonly currencyCode: string;
  readonly maintainBalancesBillByBill: boolean;
  readonly defaultCreditPeriodDays: number | null;
  readonly checkCreditDaysDuringVoucher: boolean;
  readonly creditLimit: string | null;
  readonly inventoryValuesAffected: boolean;
  readonly costCentresApplicable: boolean;
  readonly interestCalculationEnabled?: boolean;
  readonly taxType: LedgerTaxType;
  readonly dutyHead: LedgerDutyHead | null;
  readonly gstApplicability: GstApplicability;
  readonly gstSupplyType: GstSupplyType | null;
  readonly hsnSacCode: string | null;
  readonly gstRatePct: string | null;
  readonly cessRatePct: string | null;
  readonly appropriateFor: AppropriateForType;
  readonly appropriationBasis: AppropriationBasis | null;
  readonly roundingMethod: LedgerRoundingMethod;
  readonly roundingLimit: string | null;
  readonly partyRegistrationType: PartyRegistrationType;
  readonly gstin: string | null;
  readonly countryCode: string;
  readonly stateCode: string | null;
  readonly stateName: string | null;
  readonly pincode: string | null;
  readonly mailingName: string | null;
  readonly mailingAddress: string | null;
  readonly panPlainMasked: string | null;
  readonly panMasked?: string | null;
  readonly msmeRegistrationType: string | null;
  readonly msmeUdyamNo: string | null;
  readonly tdsApplicable: boolean;
  readonly tdsNatureCode: string | null;
  readonly tdsDeducteeType: string | null;
  readonly bankDetails?: BankDetailsDto | null;
  readonly bankAccountHolder: string | null;
  readonly bankAccountMasked?: string | null;
  readonly bankIfscCode: string | null;
  readonly bankName: string | null;
  readonly bankBranch: string | null;
  readonly upiId: string | null;
  readonly isSystemLedger: boolean;
  readonly active: boolean;
  readonly versionNo: number;
}

export interface CreateOrUpdateLedgerPayload {
  readonly name: string;
  readonly aliasCode: string | null;
  readonly groupId: string | null;
  readonly openingBalance: string;
  readonly openingSide: BalanceSide;
  readonly currencyCode: string;
  readonly maintainBalancesBillByBill: boolean;
  readonly defaultCreditPeriodDays: number | null;
  readonly checkCreditDaysDuringVoucher: boolean;
  readonly creditLimit: string | null;
  readonly inventoryValuesAffected: boolean;
  readonly costCentresApplicable: boolean;
  readonly taxType: LedgerTaxType;
  readonly dutyHead: LedgerDutyHead | null;
  readonly gstApplicability: GstApplicability;
  readonly gstSupplyType: GstSupplyType | null;
  readonly hsnSacCode: string | null;
  readonly gstRatePct: string | null;
  readonly cessRatePct: string | null;
  readonly appropriateFor: AppropriateForType;
  readonly appropriationBasis: AppropriationBasis | null;
  readonly roundingMethod: LedgerRoundingMethod;
  readonly roundingLimit: string | null;
  readonly partyRegistrationType: PartyRegistrationType;
  readonly gstin: string | null;
  readonly countryCode: string;
  readonly stateCode: string | null;
  readonly stateName: string | null;
  readonly pincode: string | null;
  readonly mailingName: string | null;
  readonly mailingAddress: string | null;
  readonly pan: string | null;
  readonly msmeRegistrationType: string | null;
  readonly msmeUdyamNo: string | null;
  readonly tdsApplicable: boolean;
  readonly tdsNatureCode: string | null;
  readonly tdsDeducteeType: string | null;
  readonly bankDetails?: BankDetailsDto | null;
  readonly bankAccountHolder: string | null;
  readonly bankAccountNumber: string | null;
  readonly bankIfscCode: string | null;
  readonly bankName: string | null;
  readonly bankBranch: string | null;
  readonly upiId: string | null;
  readonly active?: boolean;
  readonly versionNo?: number;
}

export interface LedgerTypeaheadItem {
  readonly id: string;
  readonly name: string;
  readonly aliasCode: string | null;
  readonly groupId: string | null;
  readonly groupName: string | null;
  readonly nature?: AccountNature;
  readonly maintainBalancesBillByBill: boolean;
  readonly costCentresApplicable: boolean;
  readonly taxType: LedgerTaxType;
  readonly dutyHead: LedgerDutyHead | null;
  readonly gstin: string | null;
  readonly stateCode?: string | null;
  readonly stateName?: string | null;
  readonly partyRegistrationType?: PartyRegistrationType;
  readonly appropriateFor?: AppropriateForType;
  readonly tdsApplicable?: boolean;
  readonly tdsNatureCode?: string | null;
  readonly currentBalance?: string;
  readonly currentBalanceSide?: BalanceSide;
}

export interface BackendCoaTreeNodeDto {
  readonly id: string;
  readonly nodeType: 'GROUP' | 'LEDGER';
  readonly name: string;
  readonly aliasCode: string | null;
  readonly parentId: string | null;
  readonly nature: AccountNature;
  readonly affectsGrossProfit: boolean;
  readonly behavesLikeSubledger: boolean;
  readonly scheduleIiiLineCode?: string | null;
  readonly isSystem: boolean;
  readonly openingBalance: string;
  readonly openingSide: BalanceSide;
  readonly openingDebit: string;
  readonly openingCredit: string;
  readonly versionNo: number;
  readonly children: readonly BackendCoaTreeNodeDto[];
}

export interface CoaTreeNode {
  readonly id: string;
  readonly name: string;
  readonly aliasCode: string | null;
  readonly parentGroupId: string | null;
  readonly nature: AccountNature;
  readonly affectsGrossProfit: boolean;
  readonly behavesLikeSubledger: boolean;
  readonly isSystemGroup: boolean;
  readonly depthLevel: number;
  readonly sortOrder: number;
  readonly versionNo: number;
  readonly openingDebit: string;
  readonly openingCredit: string;
  readonly children: readonly CoaTreeNode[];
  readonly ledgers: readonly Ledger[];
}

export interface CoaTreeResponse {
  readonly companyId?: string;
  readonly groups: readonly CoaTreeNode[];
  readonly systemLedgers?: readonly Ledger[];
  readonly totalOpeningDebit?: string;
  readonly totalOpeningCredit?: string;
  readonly openingDifference?: string;
}

export interface CurrencyMaster {
  readonly id: string;
  readonly companyId: string;
  readonly isoCode: string;
  readonly symbol: string;
  readonly formalName: string;
  readonly decimalPlaces: number;
  readonly decimalSymbol: string | null;
  readonly showAmountInMillions: boolean;
  readonly suffixSymbolToAmount: boolean;
  readonly addSpaceBetweenAmountAndSymbol: boolean;
  readonly isBaseCurrency: boolean;
  readonly versionNo: number;
}

export interface CreateOrUpdateCurrencyPayload {
  readonly isoCode: string;
  readonly symbol: string;
  readonly formalName: string;
  readonly decimalPlaces: number;
  readonly decimalSymbol: string | null;
  readonly showAmountInMillions: boolean;
  readonly suffixSymbolToAmount: boolean;
  readonly addSpaceBetweenAmountAndSymbol: boolean;
  readonly isBaseCurrency?: boolean;
  readonly versionNo?: number;
}

export interface ForexRate {
  readonly id: string;
  readonly companyId?: string;
  readonly currencyId: string;
  readonly effectiveDate: string;
  readonly standardRate: string;
  readonly sellingRate: string | null;
  readonly buyingRate: string | null;
  readonly versionNo: number;
}

export interface CreateForexRatePayload {
  readonly effectiveDate: string;
  readonly standardRate: string;
  readonly sellingRate: string | null;
  readonly buyingRate: string | null;
}

export interface CostCategory {
  readonly id: string;
  readonly companyId: string;
  readonly name: string;
  readonly allocateRevenueItems: boolean;
  readonly allocateNonRevenueItems: boolean;
  readonly isPrimaryCategory: boolean;
  readonly versionNo: number;
}

export interface CreateOrUpdateCostCategoryPayload {
  readonly name: string;
  readonly allocateRevenueItems: boolean;
  readonly allocateNonRevenueItems: boolean;
  readonly versionNo?: number;
}

export interface CostCentre {
  readonly id: string;
  readonly companyId: string;
  readonly costCategoryId: string;
  readonly categoryId?: string;
  readonly costCategoryName?: string;
  readonly categoryName?: string;
  readonly parentCostCentreId: string | null;
  readonly parentCostCentreName?: string | null;
  readonly name: string;
  readonly aliasCode: string | null;
  readonly depthLevel: number;
  readonly active: boolean;
  readonly versionNo: number;
}

export interface CreateOrUpdateCostCentrePayload {
  readonly costCategoryId: string;
  readonly categoryId?: string;
  readonly parentCostCentreId: string | null;
  readonly name: string;
  readonly aliasCode: string | null;
  readonly active?: boolean;
  readonly versionNo?: number;
}

export type VoucherBaseCategory =
  | 'CONTRA'
  | 'PAYMENT'
  | 'RECEIPT'
  | 'JOURNAL'
  | 'SALES'
  | 'PURCHASE'
  | 'CREDIT_NOTE'
  | 'DEBIT_NOTE'
  | 'MEMORANDUM'
  | 'REVERSING_JOURNAL'
  | 'SALES_ORDER'
  | 'PURCHASE_ORDER'
  | 'JOB_WORK_OUT_ORDER'
  | 'JOB_WORK_IN_ORDER'
  | 'DELIVERY_NOTE'
  | 'RECEIPT_NOTE'
  | 'REJECTIONS_IN'
  | 'REJECTIONS_OUT'
  | 'STOCK_JOURNAL'
  | 'PHYSICAL_STOCK'
  | 'MATERIAL_IN'
  | 'MATERIAL_OUT'
  | 'ATTENDANCE'
  | 'PAYROLL';

export type VoucherEntryMode =
  | 'SINGLE_ENTRY'
  | 'DOUBLE_ENTRY'
  | 'ITEM_INVOICE'
  | 'ACCOUNTING_INVOICE'
  | 'AS_VOUCHER';

export type VoucherNumberingMethod =
  | 'AUTOMATIC_RETAIN_NUMBERS'
  | 'AUTOMATIC_RENUMBER'
  | 'MULTI_USER_AUTO'
  | 'MANUAL'
  | 'NONE';

export type VoucherApprovalPolicy = 'SINGLE_STEP' | 'FOUR_EYES';

export interface VoucherTypeMaster {
  readonly id: string;
  readonly companyId: string;
  readonly name: string;
  readonly abbreviation: string;
  readonly baseCategory: VoucherBaseCategory;
  readonly category?: VoucherBaseCategory;
  readonly parentVoucherTypeId: string | null;
  readonly parentVoucherType?: string | null;
  readonly postsAccountingEntries: boolean;
  readonly movesInventory: boolean;
  readonly defaultEntryMode: VoucherEntryMode;
  readonly numberingMethod: VoucherNumberingMethod;
  readonly preventDuplicateNumbers: boolean;
  readonly preventDuplicates?: boolean;
  readonly useEffectiveDates: boolean;
  readonly allowZeroValuedTransactions: boolean;
  readonly allowZeroValued?: boolean;
  readonly makeOptionalByDefault: boolean;
  readonly isOptionalByDefault?: boolean;
  readonly allowNarrationPerLedger: boolean;
  readonly printAfterSave: boolean;
  readonly isManufacturingJournal: boolean;
  readonly approvalPolicy: VoucherApprovalPolicy;
  readonly shortcutKey: string | null;
  readonly isSystemVoucherType: boolean;
  readonly isSystemType?: boolean;
  readonly active: boolean;
  readonly versionNo: number;
}

export interface CreateOrUpdateVoucherTypePayload {
  readonly name: string;
  readonly abbreviation: string;
  readonly baseCategory: VoucherBaseCategory;
  readonly category?: VoucherBaseCategory;
  readonly parentVoucherTypeId: string | null;
  readonly postsAccountingEntries?: boolean;
  readonly movesInventory?: boolean;
  readonly defaultEntryMode: VoucherEntryMode;
  readonly numberingMethod: VoucherNumberingMethod;
  readonly preventDuplicateNumbers: boolean;
  readonly preventDuplicates?: boolean;
  readonly useEffectiveDates: boolean;
  readonly allowZeroValuedTransactions: boolean;
  readonly allowZeroValued?: boolean;
  readonly makeOptionalByDefault: boolean;
  readonly isOptionalByDefault?: boolean;
  readonly allowNarrationPerLedger: boolean;
  readonly printAfterSave: boolean;
  readonly isManufacturingJournal: boolean;
  readonly approvalPolicy: VoucherApprovalPolicy;
  readonly shortcutKey?: string | null;
  readonly active?: boolean;
  readonly versionNo?: number;
}

export interface PredefinedGroupDescriptor {
  readonly name: string;
  readonly parentName: string | null;
  readonly nature: AccountNature;
  readonly affectsGrossProfit: boolean;
  readonly behavesLikeSubledger: boolean;
}

/**
 * Canonical TallyPrime 7.2 28 Predefined Account Groups (15 Primary + 13 Sub-Groups).
 */
export const PREDEFINED_28_ACCOUNT_GROUPS: readonly PredefinedGroupDescriptor[] = [
  // 15 Primary Groups
  { name: 'Branch / Divisions', parentName: null, nature: 'LIABILITIES', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Capital Account', parentName: null, nature: 'LIABILITIES', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Current Assets', parentName: null, nature: 'ASSETS', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Current Liabilities', parentName: null, nature: 'LIABILITIES', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Direct Expenses', parentName: null, nature: 'EXPENSES', affectsGrossProfit: true, behavesLikeSubledger: false },
  { name: 'Direct Incomes', parentName: null, nature: 'INCOME', affectsGrossProfit: true, behavesLikeSubledger: false },
  { name: 'Fixed Assets', parentName: null, nature: 'ASSETS', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Indirect Expenses', parentName: null, nature: 'EXPENSES', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Indirect Incomes', parentName: null, nature: 'INCOME', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Investments', parentName: null, nature: 'ASSETS', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Loans (Liability)', parentName: null, nature: 'LIABILITIES', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Misc. Expenses (ASSET)', parentName: null, nature: 'ASSETS', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Purchase Accounts', parentName: null, nature: 'EXPENSES', affectsGrossProfit: true, behavesLikeSubledger: false },
  { name: 'Sales Accounts', parentName: null, nature: 'INCOME', affectsGrossProfit: true, behavesLikeSubledger: false },
  { name: 'Suspense A/c', parentName: null, nature: 'LIABILITIES', affectsGrossProfit: false, behavesLikeSubledger: false },
  // 13 Sub-Groups
  { name: 'Reserves & Surplus', parentName: 'Capital Account', nature: 'LIABILITIES', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Bank OD A/c', parentName: 'Loans (Liability)', nature: 'LIABILITIES', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Secured Loans', parentName: 'Loans (Liability)', nature: 'LIABILITIES', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Unsecured Loans', parentName: 'Loans (Liability)', nature: 'LIABILITIES', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Duties & Taxes', parentName: 'Current Liabilities', nature: 'LIABILITIES', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Provisions', parentName: 'Current Liabilities', nature: 'LIABILITIES', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Sundry Creditors', parentName: 'Current Liabilities', nature: 'LIABILITIES', affectsGrossProfit: false, behavesLikeSubledger: true },
  { name: 'Stock-in-Hand', parentName: 'Current Assets', nature: 'ASSETS', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Deposits (Asset)', parentName: 'Current Assets', nature: 'ASSETS', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Loans & Advances (Asset)', parentName: 'Current Assets', nature: 'ASSETS', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Sundry Debtors', parentName: 'Current Assets', nature: 'ASSETS', affectsGrossProfit: false, behavesLikeSubledger: true },
  { name: 'Cash-in-Hand', parentName: 'Current Assets', nature: 'ASSETS', affectsGrossProfit: false, behavesLikeSubledger: false },
  { name: 'Bank Accounts', parentName: 'Current Assets', nature: 'ASSETS', affectsGrossProfit: false, behavesLikeSubledger: false },
];

export interface TdsNatureOption {
  readonly section: string;
  readonly paymentCode: string;
  readonly name: string;
  readonly rateWithPanPct: string;
}

export const TDS_NATURE_SECTIONS: readonly TdsNatureOption[] = [
  { section: '194C', paymentCode: '94C', name: 'Payment to Contractors (194C)', rateWithPanPct: '2.00' },
  { section: '194J', paymentCode: '94J', name: 'Professional / Technical Fees (194J)', rateWithPanPct: '10.00' },
  { section: '194H', paymentCode: '94H', name: 'Commission or Brokerage (194H)', rateWithPanPct: '2.00' },
  { section: '194I', paymentCode: '94I', name: 'Rent — Land / Building / Plant (194I)', rateWithPanPct: '10.00' },
  { section: '194Q', paymentCode: '94Q', name: 'Purchase of Goods > 50L (194Q)', rateWithPanPct: '0.10' },
  { section: '206C', paymentCode: '6CR', name: 'TCS on Sale of Scrap / Goods (206C)', rateWithPanPct: '1.00' },
];

export const TDS_DEDUCTEE_TYPES: readonly { readonly code: string; readonly label: string }[] = [
  { code: 'COMPANY_RESIDENT', label: 'Company — Resident' },
  { code: 'PARTNERSHIP_FIRM', label: 'Partnership Firm / LLP' },
  { code: 'INDIVIDUAL_HUF', label: 'Individual / HUF — Resident' },
  { code: 'ASSOCIATION_OF_PERSONS', label: 'Association of Persons (AOP / BOI)' },
  { code: 'LOCAL_AUTHORITY', label: 'Local Authority' },
  { code: 'GOVERNMENT', label: 'Government / Artificial Juridical Person' },
  { code: 'NON_RESIDENT', label: 'Company / Non-Resident' },
];

export const VOUCHER_BASE_CATEGORIES: readonly {
  readonly code: VoucherBaseCategory;
  readonly label: string;
  readonly defaultMode: VoucherEntryMode;
  readonly postsAccounting: boolean;
  readonly movesInventory: boolean;
}[] = [
  { code: 'CONTRA', label: 'Contra (F4)', defaultMode: 'SINGLE_ENTRY', postsAccounting: true, movesInventory: false },
  { code: 'PAYMENT', label: 'Payment (F5)', defaultMode: 'SINGLE_ENTRY', postsAccounting: true, movesInventory: false },
  { code: 'RECEIPT', label: 'Receipt (F6)', defaultMode: 'SINGLE_ENTRY', postsAccounting: true, movesInventory: false },
  { code: 'JOURNAL', label: 'Journal (F7)', defaultMode: 'AS_VOUCHER', postsAccounting: true, movesInventory: false },
  { code: 'SALES', label: 'Sales (F8)', defaultMode: 'ITEM_INVOICE', postsAccounting: true, movesInventory: true },
  { code: 'PURCHASE', label: 'Purchase (F9)', defaultMode: 'ITEM_INVOICE', postsAccounting: true, movesInventory: true },
  { code: 'CREDIT_NOTE', label: 'Credit Note (Alt+F6)', defaultMode: 'ITEM_INVOICE', postsAccounting: true, movesInventory: true },
  { code: 'DEBIT_NOTE', label: 'Debit Note (Alt+F5)', defaultMode: 'ITEM_INVOICE', postsAccounting: true, movesInventory: true },
  { code: 'MEMORANDUM', label: 'Memorandum', defaultMode: 'AS_VOUCHER', postsAccounting: false, movesInventory: false },
  { code: 'REVERSING_JOURNAL', label: 'Reversing Journal', defaultMode: 'AS_VOUCHER', postsAccounting: false, movesInventory: false },
  { code: 'SALES_ORDER', label: 'Sales Order', defaultMode: 'ITEM_INVOICE', postsAccounting: false, movesInventory: false },
  { code: 'PURCHASE_ORDER', label: 'Purchase Order', defaultMode: 'ITEM_INVOICE', postsAccounting: false, movesInventory: false },
  { code: 'JOB_WORK_OUT_ORDER', label: 'Job Work Out Order', defaultMode: 'ITEM_INVOICE', postsAccounting: false, movesInventory: false },
  { code: 'JOB_WORK_IN_ORDER', label: 'Job Work In Order', defaultMode: 'ITEM_INVOICE', postsAccounting: false, movesInventory: false },
  { code: 'DELIVERY_NOTE', label: 'Delivery Note', defaultMode: 'ITEM_INVOICE', postsAccounting: false, movesInventory: true },
  { code: 'RECEIPT_NOTE', label: 'Receipt Note', defaultMode: 'ITEM_INVOICE', postsAccounting: false, movesInventory: true },
  { code: 'REJECTIONS_IN', label: 'Rejections In', defaultMode: 'ITEM_INVOICE', postsAccounting: false, movesInventory: true },
  { code: 'REJECTIONS_OUT', label: 'Rejections Out', defaultMode: 'ITEM_INVOICE', postsAccounting: false, movesInventory: true },
  { code: 'STOCK_JOURNAL', label: 'Stock Journal (Alt+F7)', defaultMode: 'AS_VOUCHER', postsAccounting: false, movesInventory: true },
  { code: 'PHYSICAL_STOCK', label: 'Physical Stock (Ctrl+F7)', defaultMode: 'AS_VOUCHER', postsAccounting: false, movesInventory: true },
  { code: 'MATERIAL_IN', label: 'Material In', defaultMode: 'ITEM_INVOICE', postsAccounting: false, movesInventory: true },
  { code: 'MATERIAL_OUT', label: 'Material Out', defaultMode: 'ITEM_INVOICE', postsAccounting: false, movesInventory: true },
  { code: 'ATTENDANCE', label: 'Attendance', defaultMode: 'AS_VOUCHER', postsAccounting: false, movesInventory: false },
  { code: 'PAYROLL', label: 'Payroll', defaultMode: 'AS_VOUCHER', postsAccounting: true, movesInventory: false },
];

export interface GroupLineageFlags {
  readonly isPartyGroup: boolean;
  readonly isSundryDebtor: boolean;
  readonly isSundryCreditor: boolean;
  readonly isDutiesAndTaxes: boolean;
  readonly isBankAccount: boolean;
  readonly isCashInHand: boolean;
  readonly isSalesOrPurchase: boolean;
  readonly isExpenseOrIncome: boolean;
  readonly defaultOpeningSide: BalanceSide;
}

/**
 * Resolves TallyPrime 7.2 group lineage flags for any predefined or custom sub-group
 * by walking `parentGroupId` up to the root Primary group.
 */
export function resolveGroupLineageFlags(
  groupId: string | null,
  groups: readonly AccountGroup[],
): GroupLineageFlags {
  const byId = new Map(groups.map((g) => [g.id, g]));
  const lineageNames: string[] = [];
  let current = groupId ? byId.get(groupId) : undefined;
  const leafNature: AccountNature = current?.nature ?? 'ASSETS';
  let guard = 0;

  while (current && guard < 16) {
    lineageNames.push(current.name.trim().toLowerCase());
    current = current.parentGroupId ? byId.get(current.parentGroupId) : undefined;
    guard += 1;
  }

  const hasName = (target: string): boolean => lineageNames.includes(target.toLowerCase());

  const isSundryDebtor = hasName('Sundry Debtors');
  const isSundryCreditor = hasName('Sundry Creditors');
  const isPartyGroup = isSundryDebtor || isSundryCreditor;
  const isDutiesAndTaxes = hasName('Duties & Taxes');
  const isBankAccount = hasName('Bank Accounts') || hasName('Bank OD A/c');
  const isCashInHand = hasName('Cash-in-Hand');
  const isSalesOrPurchase = hasName('Sales Accounts') || hasName('Purchase Accounts');
  const isExpenseOrIncome =
    leafNature === 'EXPENSES' ||
    leafNature === 'INCOME' ||
    hasName('Direct Expenses') ||
    hasName('Indirect Expenses') ||
    hasName('Direct Incomes') ||
    hasName('Indirect Incomes');

  const defaultOpeningSide: BalanceSide =
    leafNature === 'ASSETS' || leafNature === 'EXPENSES' ? 'DR' : 'CR';

  return {
    isPartyGroup,
    isSundryDebtor,
    isSundryCreditor,
    isDutiesAndTaxes,
    isBankAccount,
    isCashInHand,
    isSalesOrPurchase,
    isExpenseOrIncome,
    defaultOpeningSide,
  };
}
