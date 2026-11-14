import { BalanceSide, VoucherBaseCategory } from '../masters/masters.models';

/**
 * MoneyCraft 1.0.0 — Phase 3 Double-Entry Voucher, Sub-Ledger Allocations,
 * Day Book & Four-Eyes Approval Domain Models.
 *
 * Every monetary amount, tax rate percentage, and exchange rate is represented
 * strictly as a string-encoded exact decimal (never IEEE-754 float math).
 */

export type VoucherPersistedView =
  | 'SINGLE_ENTRY'
  | 'AS_VOUCHER'
  | 'ACCOUNTING_INVOICE'
  | 'ITEM_INVOICE';

export type VoucherPostingNature =
  | 'NORMAL'
  | 'OPTIONAL'
  | 'MEMORANDUM'
  | 'REVERSING_JOURNAL';

export type VoucherSubmitAction = 'SAVE_DRAFT' | 'SUBMIT_FOR_APPROVAL' | 'POST';

export type VoucherLifecycleStatus =
  | 'DRAFT'
  | 'PENDING_APPROVAL'
  | 'POSTED'
  | 'CANCELLED'
  | 'REVERSED';

export type VoucherLineRole =
  | 'PARTY'
  | 'ACCOUNTING'
  | 'LEDGER'
  | 'ITEM_ACCOUNTING'
  | 'TAX'
  | 'DISCOUNT'
  | 'ADDITIONAL_CHARGE'
  | 'ROUND_OFF';

export type BillAllocationType = 'NEW_REF' | 'AGST_REF' | 'ADVANCE' | 'ON_ACCOUNT';

export type BankTransactionType =
  | 'CHEQUE'
  | 'NEFT'
  | 'RTGS'
  | 'IMPS'
  | 'UPI'
  | 'CASH_DEPOSIT'
  | 'CARD'
  | 'ECS_NACH'
  | 'BANK_CHARGES'
  | 'OTHERS';

export interface BillAllocationDto {
  readonly allocationType: BillAllocationType;
  readonly billName: string;
  readonly creditPeriodDays?: number | null;
  readonly dueDate?: string | null;
  readonly side: BalanceSide;
  readonly amount: string;
  readonly forexCurrencyCode?: string | null;
  readonly forexAmount?: string | null;
  readonly forexRate?: string | null;
  readonly allowOverSettlement?: boolean;
}

export interface CostAllocationDto {
  readonly costCategoryId?: string | null;
  readonly costCentreId: string;
  readonly costCentreName?: string | null;
  readonly side: BalanceSide;
  readonly amount: string;
}

export interface BankAllocationDto {
  readonly bankLedgerId?: string;
  readonly transactionType: BankTransactionType;
  readonly instrumentNo?: string | null;
  readonly instrumentDate?: string | null;
  readonly bankersDate?: string | null;
  readonly favouringName?: string | null;
  readonly transferModeRef?: string | null;
  readonly side: BalanceSide;
  readonly amount: string;
}

export interface VoucherBatchAllocationDto {
  readonly godownId?: string | null;
  readonly godownName?: string | null;
  readonly batchName: string;
  readonly actualQty: string;
  readonly billedQty: string;
  readonly rate: string;
  readonly amount: string;
  readonly mfgDate?: string | null;
  readonly expiryDate?: string | null;
}

export interface VoucherInventoryLineDto {
  readonly lineNo: number;
  readonly accountingLedgerId?: string | null;
  readonly stockItemId: string;
  readonly stockItemName?: string;
  readonly movementDirection?: 'INWARD' | 'OUTWARD';
  readonly uomId?: string | null;
  readonly uomSymbol?: string;
  readonly actualQty: string;
  readonly billedQty: string;
  readonly rate: string;
  readonly discountPct: string;
  readonly amount: string;
  readonly declaredMrp?: string | null;
  readonly orderRefNo?: string | null;
  readonly trackingNo?: string | null;
  readonly linkedInwardAllocationId?: string | null;
  readonly batchAllocations?: readonly VoucherBatchAllocationDto[];
}

export interface VoucherInventoryItemHookDto {
  readonly rowId: string;
  readonly stockItemId: string | null;
  readonly stockItemName: string;
  readonly godownId?: string | null;
  readonly batchNo?: string | null;
  readonly quantity: string;
  readonly actualQty?: string;
  readonly billedQty?: string;
  readonly uomId?: string | null;
  readonly uomCode: string;
  readonly baseUomId?: string | null;
  readonly baseUomSymbol?: string;
  readonly alternateUomId: string | null;
  readonly alternateUomSymbol?: string | null;
  readonly maintainInBatches?: boolean;
  readonly trackDateOfMfg?: boolean;
  readonly useExpiryDates?: boolean;
  readonly isTobaccoMrpItem?: boolean;
  readonly declaredMrp?: string | null;
  readonly orderRefNo?: string | null;
  readonly trackingNo?: string | null;
  readonly movementDirection?: 'INWARD' | 'OUTWARD';
  readonly rate: string;
  readonly discountPct?: string | null;
  readonly amount: string;
  readonly gstRatePct?: string | null;
  readonly batchAllocations?: readonly VoucherBatchAllocationDto[];
}

export interface VoucherLineRequest {
  readonly lineNo?: number;
  readonly lineRole: VoucherLineRole;
  readonly ledgerId: string;
  readonly side: BalanceSide;
  readonly amount: string;
  readonly forexCurrencyCode?: string | null;
  readonly forexAmount?: string | null;
  readonly forexRate?: string | null;
  readonly ratePct?: string | null;
  readonly computedTaxAmount?: string | null;
  readonly isGstOverridden?: boolean;
  readonly gstOverrideReason?: string | null;
  readonly lineNarration?: string;
  readonly billAllocations?: readonly BillAllocationDto[];
  readonly costAllocations?: readonly CostAllocationDto[];
  readonly bankAllocations?: readonly BankAllocationDto[];
}

export interface VoucherLineResponse extends VoucherLineRequest {
  readonly id?: string;
  readonly ledgerName?: string;
}

export interface UpsertVoucherRequest {
  readonly voucherTypeId?: string;
  readonly voucherTypeName?: string;
  readonly voucherClassId?: string | null;
  readonly voucherNumber?: string | null;
  readonly voucherDate: string;
  readonly effectiveDate?: string;
  readonly reversingApplicableUpto?: string | null;
  readonly persistedView: VoucherPersistedView;
  readonly postingNature: VoucherPostingNature;
  readonly action: VoucherSubmitAction;
  readonly supplierInvoiceNo?: string | null;
  readonly supplierInvoiceDate?: string | null;
  readonly partyLedgerId?: string | null;
  readonly accountingLedgerId?: string | null;
  readonly placeOfSupplyState?: string | null;
  readonly companyGstinId?: string | null;
  readonly partyGstin?: string | null;
  readonly partyMailingName?: string | null;
  readonly partyStateCode?: string | null;
  readonly isReverseCharge?: boolean;
  readonly exportType?: string | null;
  readonly currencyCode?: string;
  readonly exchangeRate?: string;
  readonly narration: string;
  readonly lines: readonly VoucherLineRequest[];
  readonly inventoryLines?: readonly VoucherInventoryLineDto[];
}

export interface VoucherDetailDto {
  readonly id: string;
  readonly companyId: string;
  readonly voucherTypeId: string;
  readonly voucherTypeName: string;
  readonly voucherCategory?: VoucherBaseCategory;
  readonly voucherClassId?: string | null;
  readonly voucherNumber: string;
  readonly voucherDate: string;
  readonly effectiveDate?: string | null;
  readonly reversingApplicableUpto?: string | null;
  readonly persistedView: VoucherPersistedView;
  readonly postingNature: VoucherPostingNature;
  readonly status: VoucherLifecycleStatus;
  readonly approvalPolicy?: 'SINGLE_STEP' | 'FOUR_EYES';
  readonly supplierInvoiceNo?: string | null;
  readonly supplierInvoiceDate?: string | null;
  readonly partyLedgerId?: string | null;
  readonly partyLedgerName?: string | null;
  readonly accountingLedgerId?: string | null;
  readonly accountingLedgerName?: string | null;
  readonly placeOfSupplyState?: string | null;
  readonly companyGstinId?: string | null;
  readonly partyGstin?: string | null;
  readonly partyMailingName?: string | null;
  readonly partyStateCode?: string | null;
  readonly isReverseCharge?: boolean;
  readonly exportType?: string | null;
  readonly currencyCode: string;
  readonly exchangeRate?: string;
  readonly narration: string;
  readonly totalDebit: string;
  readonly totalCredit: string;
  readonly lines: readonly VoucherLineResponse[];
  readonly inventoryLines?: readonly VoucherInventoryLineDto[];
  readonly createdBy?: string | null;
  readonly approvedBy?: string | null;
  readonly cancelledReason?: string | null;
  readonly alterId?: number;
  readonly versionNo: number;
}

export interface VoucherSummaryDto {
  readonly id: string;
  readonly companyId?: string;
  readonly voucherDate: string;
  readonly voucherTypeId: string;
  readonly voucherTypeName: string;
  readonly voucherCategory?: VoucherBaseCategory;
  readonly voucherNumber: string;
  readonly particulars: string;
  readonly status: VoucherLifecycleStatus;
  readonly postingNature: VoucherPostingNature;
  readonly persistedView?: VoucherPersistedView;
  readonly debitAmount: string;
  readonly creditAmount: string;
  readonly narration?: string | null;
  readonly createdBy?: string | null;
  readonly approvedBy?: string | null;
  readonly approvalPolicy?: 'SINGLE_STEP' | 'FOUR_EYES';
  readonly versionNo: number;
}

export interface OpenBillDto {
  readonly billName: string;
  readonly billDate: string;
  readonly dueDate?: string | null;
  readonly totalDr: string;
  readonly totalCr: string;
  readonly pendingAmount: string;
  readonly pendingSide: BalanceSide;
  readonly unadjustedForexDelta?: string | null;
}

export interface ListVouchersQuery {
  readonly fromDate?: string;
  readonly toDate?: string;
  readonly voucherTypeId?: string;
  readonly voucherCategory?: VoucherBaseCategory;
  readonly ledgerId?: string;
  readonly status?: VoucherLifecycleStatus;
  readonly includeOptional?: boolean;
  readonly includeCancelled?: boolean;
  readonly limit?: number;
  readonly cursor?: string;
}

export interface PredefinedVoucherSpec {
  readonly name: string;
  readonly abbreviation: string;
  readonly category: VoucherBaseCategory;
  readonly shortcutKey: string | null;
  readonly defaultPersistedView: VoucherPersistedView;
  readonly allowedViews: readonly VoucherPersistedView[];
  readonly defaultPostingNature: VoucherPostingNature;
  readonly defaultFirstSide: BalanceSide;
}

export const PREDEFINED_24_VOUCHER_SPECS: readonly PredefinedVoucherSpec[] = [
  {
    name: 'Contra',
    abbreviation: 'Cntr',
    category: 'CONTRA',
    shortcutKey: 'F4',
    defaultPersistedView: 'SINGLE_ENTRY',
    allowedViews: ['SINGLE_ENTRY', 'AS_VOUCHER'],
    defaultPostingNature: 'NORMAL',
    defaultFirstSide: 'CR',
  },
  {
    name: 'Payment',
    abbreviation: 'Pymt',
    category: 'PAYMENT',
    shortcutKey: 'F5',
    defaultPersistedView: 'SINGLE_ENTRY',
    allowedViews: ['SINGLE_ENTRY', 'AS_VOUCHER'],
    defaultPostingNature: 'NORMAL',
    defaultFirstSide: 'DR',
  },
  {
    name: 'Receipt',
    abbreviation: 'Rcpt',
    category: 'RECEIPT',
    shortcutKey: 'F6',
    defaultPersistedView: 'SINGLE_ENTRY',
    allowedViews: ['SINGLE_ENTRY', 'AS_VOUCHER'],
    defaultPostingNature: 'NORMAL',
    defaultFirstSide: 'CR',
  },
  {
    name: 'Journal',
    abbreviation: 'Jrnl',
    category: 'JOURNAL',
    shortcutKey: 'F7',
    defaultPersistedView: 'AS_VOUCHER',
    allowedViews: ['AS_VOUCHER'],
    defaultPostingNature: 'NORMAL',
    defaultFirstSide: 'DR',
  },
  {
    name: 'Sales',
    abbreviation: 'Sale',
    category: 'SALES',
    shortcutKey: 'F8',
    defaultPersistedView: 'ACCOUNTING_INVOICE',
    allowedViews: ['ITEM_INVOICE', 'ACCOUNTING_INVOICE', 'AS_VOUCHER'],
    defaultPostingNature: 'NORMAL',
    defaultFirstSide: 'DR',
  },
  {
    name: 'Purchase',
    abbreviation: 'Purc',
    category: 'PURCHASE',
    shortcutKey: 'F9',
    defaultPersistedView: 'ACCOUNTING_INVOICE',
    allowedViews: ['ITEM_INVOICE', 'ACCOUNTING_INVOICE', 'AS_VOUCHER'],
    defaultPostingNature: 'NORMAL',
    defaultFirstSide: 'CR',
  },
  {
    name: 'Credit Note',
    abbreviation: 'C/Note',
    category: 'CREDIT_NOTE',
    shortcutKey: 'Alt+F6',
    defaultPersistedView: 'ACCOUNTING_INVOICE',
    allowedViews: ['ITEM_INVOICE', 'ACCOUNTING_INVOICE', 'AS_VOUCHER'],
    defaultPostingNature: 'NORMAL',
    defaultFirstSide: 'CR',
  },
  {
    name: 'Debit Note',
    abbreviation: 'D/Note',
    category: 'DEBIT_NOTE',
    shortcutKey: 'Alt+F5',
    defaultPersistedView: 'ACCOUNTING_INVOICE',
    allowedViews: ['ITEM_INVOICE', 'ACCOUNTING_INVOICE', 'AS_VOUCHER'],
    defaultPostingNature: 'NORMAL',
    defaultFirstSide: 'DR',
  },
  {
    name: 'Memorandum',
    abbreviation: 'Memo',
    category: 'MEMORANDUM',
    shortcutKey: null,
    defaultPersistedView: 'AS_VOUCHER',
    allowedViews: ['AS_VOUCHER'],
    defaultPostingNature: 'MEMORANDUM',
    defaultFirstSide: 'DR',
  },
  {
    name: 'Reversing Journal',
    abbreviation: 'RevJrnl',
    category: 'REVERSING_JOURNAL',
    shortcutKey: null,
    defaultPersistedView: 'AS_VOUCHER',
    allowedViews: ['AS_VOUCHER'],
    defaultPostingNature: 'REVERSING_JOURNAL',
    defaultFirstSide: 'DR',
  },
  {
    name: 'Sales Order',
    abbreviation: 'S/Order',
    category: 'SALES_ORDER',
    shortcutKey: 'Ctrl+F8',
    defaultPersistedView: 'ITEM_INVOICE',
    allowedViews: ['ITEM_INVOICE', 'ACCOUNTING_INVOICE'],
    defaultPostingNature: 'OPTIONAL',
    defaultFirstSide: 'DR',
  },
  {
    name: 'Purchase Order',
    abbreviation: 'P/Order',
    category: 'PURCHASE_ORDER',
    shortcutKey: 'Ctrl+F9',
    defaultPersistedView: 'ITEM_INVOICE',
    allowedViews: ['ITEM_INVOICE', 'ACCOUNTING_INVOICE'],
    defaultPostingNature: 'OPTIONAL',
    defaultFirstSide: 'CR',
  },
  {
    name: 'Job Work Out Order',
    abbreviation: 'JWOut',
    category: 'JOB_WORK_OUT_ORDER',
    shortcutKey: null,
    defaultPersistedView: 'ITEM_INVOICE',
    allowedViews: ['ITEM_INVOICE'],
    defaultPostingNature: 'OPTIONAL',
    defaultFirstSide: 'DR',
  },
  {
    name: 'Job Work In Order',
    abbreviation: 'JWIn',
    category: 'JOB_WORK_IN_ORDER',
    shortcutKey: null,
    defaultPersistedView: 'ITEM_INVOICE',
    allowedViews: ['ITEM_INVOICE'],
    defaultPostingNature: 'OPTIONAL',
    defaultFirstSide: 'CR',
  },
  {
    name: 'Delivery Note',
    abbreviation: 'Dely',
    category: 'DELIVERY_NOTE',
    shortcutKey: 'Alt+F8',
    defaultPersistedView: 'ITEM_INVOICE',
    allowedViews: ['ITEM_INVOICE'],
    defaultPostingNature: 'NORMAL',
    defaultFirstSide: 'DR',
  },
  {
    name: 'Receipt Note',
    abbreviation: 'RcptNote',
    category: 'RECEIPT_NOTE',
    shortcutKey: 'Alt+F9',
    defaultPersistedView: 'ITEM_INVOICE',
    allowedViews: ['ITEM_INVOICE'],
    defaultPostingNature: 'NORMAL',
    defaultFirstSide: 'CR',
  },
  {
    name: 'Rejections In',
    abbreviation: 'RejIn',
    category: 'REJECTIONS_IN',
    shortcutKey: 'Ctrl+F6',
    defaultPersistedView: 'ITEM_INVOICE',
    allowedViews: ['ITEM_INVOICE'],
    defaultPostingNature: 'NORMAL',
    defaultFirstSide: 'CR',
  },
  {
    name: 'Rejections Out',
    abbreviation: 'RejOut',
    category: 'REJECTIONS_OUT',
    shortcutKey: 'Alt+F6',
    defaultPersistedView: 'ITEM_INVOICE',
    allowedViews: ['ITEM_INVOICE'],
    defaultPostingNature: 'NORMAL',
    defaultFirstSide: 'DR',
  },
  {
    name: 'Stock Journal',
    abbreviation: 'StkJrnl',
    category: 'STOCK_JOURNAL',
    shortcutKey: 'Alt+F7',
    defaultPersistedView: 'ITEM_INVOICE',
    allowedViews: ['ITEM_INVOICE'],
    defaultPostingNature: 'NORMAL',
    defaultFirstSide: 'DR',
  },
  {
    name: 'Physical Stock',
    abbreviation: 'PhyStk',
    category: 'PHYSICAL_STOCK',
    shortcutKey: 'Ctrl+F7',
    defaultPersistedView: 'ITEM_INVOICE',
    allowedViews: ['ITEM_INVOICE'],
    defaultPostingNature: 'NORMAL',
    defaultFirstSide: 'DR',
  },
  {
    name: 'Material In',
    abbreviation: 'MatIn',
    category: 'MATERIAL_IN',
    shortcutKey: null,
    defaultPersistedView: 'ITEM_INVOICE',
    allowedViews: ['ITEM_INVOICE'],
    defaultPostingNature: 'NORMAL',
    defaultFirstSide: 'DR',
  },
  {
    name: 'Material Out',
    abbreviation: 'MatOut',
    category: 'MATERIAL_OUT',
    shortcutKey: null,
    defaultPersistedView: 'ITEM_INVOICE',
    allowedViews: ['ITEM_INVOICE'],
    defaultPostingNature: 'NORMAL',
    defaultFirstSide: 'CR',
  },
  {
    name: 'Attendance',
    abbreviation: 'Attd',
    category: 'ATTENDANCE',
    shortcutKey: null,
    defaultPersistedView: 'AS_VOUCHER',
    allowedViews: ['AS_VOUCHER'],
    defaultPostingNature: 'OPTIONAL',
    defaultFirstSide: 'DR',
  },
  {
    name: 'Payroll',
    abbreviation: 'Pyrl',
    category: 'PAYROLL',
    shortcutKey: 'Ctrl+F4',
    defaultPersistedView: 'AS_VOUCHER',
    allowedViews: ['SINGLE_ENTRY', 'AS_VOUCHER'],
    defaultPostingNature: 'NORMAL',
    defaultFirstSide: 'DR',
  },
];

export const BANK_TRANSACTION_TYPES: readonly {
  readonly code: BankTransactionType;
  readonly label: string;
}[] = [
  { code: 'CHEQUE', label: 'Cheque / DD' },
  { code: 'NEFT', label: 'e-Fund Transfer (NEFT)' },
  { code: 'RTGS', label: 'e-Fund Transfer (RTGS)' },
  { code: 'IMPS', label: 'e-Fund Transfer (IMPS)' },
  { code: 'UPI', label: 'UPI Instant Transfer' },
  { code: 'CASH_DEPOSIT', label: 'Cash Deposit / Withdrawal' },
  { code: 'CARD', label: 'Card Settlement' },
  { code: 'ECS_NACH', label: 'ECS / NACH Mandate' },
  { code: 'BANK_CHARGES', label: 'Bank Charges' },
  { code: 'OTHERS', label: 'Others' },
];

export function resolveVoucherSpecByCategory(
  category: VoucherBaseCategory | undefined | null,
): PredefinedVoucherSpec {
  return (
    PREDEFINED_24_VOUCHER_SPECS.find((s) => s.category === category) ??
    PREDEFINED_24_VOUCHER_SPECS[3] // Journal fallback
  );
}
