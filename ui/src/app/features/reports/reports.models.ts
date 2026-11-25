/**
 * MoneyCraft 1.0.0 — Phase 6 Financial, Statutory Schedule III, Cash Flow, Ratio & Ageing Report Models
 *
 * Every monetary balance, ratio, and percentage is serialized as an exact decimal string
 * (`"610949.71"`, `"49385.71"`) — zero IEEE-754 floating-point math.
 */

export type PrintableReportType =
  | 'trial-balance'
  | 'profit-and-loss'
  | 'balance-sheet'
  | 'schedule-iii'
  | 'day-book'
  | 'stock-summary'
  | 'ageing';

export type BalanceSheetFormat = 'HORIZONTAL' | 'VERTICAL';
export type ScheduleIiiEntityFormat = 'DIVISION_I' | 'NCE';
export type ScheduleIiiRoundingScale = 'ACTUAL' | 'HUNDREDS' | 'THOUSANDS' | 'LAKHS' | 'CRORES';
export type BillsAgeingType = 'RECEIVABLES' | 'PAYABLES';

// ---------------------------------------------------------------------------
// 1. Trial Balance (`GET /reports/trial-balance`)
// ---------------------------------------------------------------------------

export interface TrialBalanceRowDto {
  readonly id: string;
  readonly parentGroupId?: string | null;
  readonly groupName?: string | null;
  readonly code?: string | null;
  readonly name: string;
  readonly rowType: 'GROUP' | 'LEDGER';
  readonly depthLevel: number;
  readonly openingDebit: string;
  readonly openingCredit: string;
  readonly periodDebit: string;
  readonly periodCredit: string;
  readonly closingDebit: string;
  readonly closingCredit: string;
  readonly children: readonly TrialBalanceRowDto[];
}

export interface TrialBalanceReportDto {
  readonly fromDate: string;
  readonly toDate: string;
  readonly includeOptional: boolean;
  readonly groupRows: readonly TrialBalanceRowDto[];
  readonly ledgerRows: readonly TrialBalanceRowDto[];
  readonly unadjustedForexDebit: string;
  readonly unadjustedForexCredit: string;
  readonly totalOpeningDebit: string;
  readonly totalOpeningCredit: string;
  readonly totalPeriodDebit: string;
  readonly totalPeriodCredit: string;
  readonly totalClosingDebit: string;
  readonly totalClosingCredit: string;
  readonly differenceAmount: string;
  readonly isBalanced: boolean;
}

// ---------------------------------------------------------------------------
// 2. Profit & Loss A/c & Balance Sheet Shared Statement Lines
// ---------------------------------------------------------------------------

export type StatementLineKind =
  | 'GROUP'
  | 'LEDGER'
  | 'STOCK'
  | 'PROFIT_LOSS'
  | 'FOREX'
  | 'DIFF'
  | 'SUBTOTAL';

export interface FinancialStatementLineDto {
  readonly id: string;
  readonly lineType: StatementLineKind;
  readonly code?: string | null;
  readonly name: string;
  readonly amount: string;
  readonly comparativeAmount?: string | null;
  readonly ledgerId?: string | null;
  readonly groupId?: string | null;
  readonly children: readonly FinancialStatementLineDto[];
}

export interface ProfitAndLossReportDto {
  readonly fromDate: string;
  readonly toDate: string;
  readonly openingStockValue: string;
  readonly closingStockValue: string;
  readonly salesTotal: string;
  readonly purchaseTotal: string;
  readonly directExpensesTotal: string;
  readonly directIncomesTotal: string;
  readonly indirectExpensesTotal: string;
  readonly indirectIncomesTotal: string;
  readonly tradingDebitLines: readonly FinancialStatementLineDto[];
  readonly tradingCreditLines: readonly FinancialStatementLineDto[];
  readonly tradingTotal: string;
  readonly grossProfit: string;
  readonly grossLoss: string;
  readonly pnlDebitLines: readonly FinancialStatementLineDto[];
  readonly pnlCreditLines: readonly FinancialStatementLineDto[];
  readonly pnlTotal: string;
  readonly netProfit: string;
  readonly netLoss: string;
  readonly signedNetProfit: string;
}

export interface BalanceSheetReportDto {
  readonly asOfDate: string;
  readonly format: BalanceSheetFormat;
  readonly liabilityLines: readonly FinancialStatementLineDto[];
  readonly assetLines: readonly FinancialStatementLineDto[];
  readonly closingStockValue: string;
  readonly profitAndLossBalance: string;
  readonly openingBalanceDifference: string;
  readonly totalLiabilities: string;
  readonly totalAssets: string;
  readonly differenceAmount: string;
  readonly isBalanced: boolean;
}

// ---------------------------------------------------------------------------
// 3. MCA Schedule III (`GET /reports/schedule-iii`)
// ---------------------------------------------------------------------------

export interface ScheduleIiiLineDto {
  readonly lineCode: string;
  readonly particulars: string;
  readonly noteNo: string | null;
  readonly section?: string | null;
  readonly indentLevel: number;
  readonly isSubtotal: boolean;
  readonly currentAmount: string;
  readonly comparativeAmount: string;
}

export interface ScheduleIiiNoteItemDto {
  readonly id: string;
  readonly name: string;
  readonly currentAmount: string;
  readonly comparativeAmount: string;
}

export interface ScheduleIiiNoteDto {
  readonly noteNo: string;
  readonly lineCode: string;
  readonly title: string;
  readonly currentTotal: string;
  readonly comparativeTotal: string;
  readonly items: readonly ScheduleIiiNoteItemDto[];
}

export interface ScheduleIiiReportDto {
  readonly asOfDate: string;
  readonly comparativeAsOfDate: string | null;
  readonly entityFormat: ScheduleIiiEntityFormat;
  readonly roundingScale: ScheduleIiiRoundingScale;
  readonly balanceSheetLines: readonly ScheduleIiiLineDto[];
  readonly statementOfPnlLines: readonly ScheduleIiiLineDto[];
  readonly notes: readonly ScheduleIiiNoteDto[];
  readonly totalEquityAndLiabilities: string;
  readonly totalAssets: string;
  readonly netProfitForPeriod: string;
  readonly isBalanced: boolean;
}

// ---------------------------------------------------------------------------
// 4. Cash Flow & Ratio Analysis (`GET /reports/cash-flow`, `GET /reports/ratios`)
// ---------------------------------------------------------------------------

export interface CashFlowMonthRowDto {
  readonly monthLabel: string;
  readonly fromDate: string;
  readonly toDate: string;
  readonly inflow: string;
  readonly outflow: string;
  readonly netFlow: string;
  readonly closingBalance: string;
}

export interface CashFlowActivityRowDto {
  readonly category: 'OPERATING' | 'INVESTING' | 'FINANCING';
  readonly particulars: string;
  readonly inflow: string;
  readonly outflow: string;
  readonly netFlow: string;
}

export interface CashFlowReportDto {
  readonly openingCashAndBank: string;
  readonly operatingInflow: string;
  readonly operatingOutflow: string;
  readonly netOperatingCashFlow: string;
  readonly investingCashFlow: string;
  readonly financingCashFlow: string;
  readonly netCashMovement: string;
  readonly closingCashAndBank: string;
  readonly monthlySummary: readonly CashFlowMonthRowDto[];
  readonly activities: readonly CashFlowActivityRowDto[];
}

export interface RatioAnalysisReportDto {
  readonly workingCapital: string;
  readonly cashInHand: string;
  readonly bankAccounts: string;
  readonly sundryDebtors: string;
  readonly sundryCreditors: string;
  readonly salesAccounts: string;
  readonly purchaseAccounts: string;
  readonly stockInHand: string;
  readonly netProfit: string;
  readonly currentRatio: string;
  readonly quickRatio: string;
  readonly debtEquityRatio: string;
  readonly grossProfitPct: string;
  readonly netProfitPct: string;
  readonly returnOnWorkingCapitalPct: string;
  readonly inventoryTurnover: string;
  readonly receivableTurnoverDays: string;
}

// ---------------------------------------------------------------------------
// 5. Bills Receivable / Payable Ageing (`GET /reports/ageing`)
// ---------------------------------------------------------------------------

export interface AgeingBillRowDto {
  readonly id: string;
  readonly voucherId?: string | null;
  readonly ledgerId: string;
  readonly partyName: string;
  readonly partyGstin?: string | null;
  readonly billName: string;
  readonly billDate: string;
  readonly dueDate: string;
  readonly ageDays: number;
  readonly overdueDays: number;
  readonly bucket: '0_30' | '31_60' | '61_90' | 'GT_90';
  readonly originalAmount: string;
  readonly pendingAmount: string;
  readonly bucket0To30: string;
  readonly bucket31To60: string;
  readonly bucket61To90: string;
  readonly bucketOver90: string;
}

export interface AgeingBucketTotalsDto {
  readonly bucket0To30: string;
  readonly bucket31To60: string;
  readonly bucket61To90: string;
  readonly bucketOver90: string;
  readonly totalPending: string;
}

export interface BillsAgeingReportDto {
  readonly type: BillsAgeingType;
  readonly asOfDate: string;
  readonly bills: readonly AgeingBillRowDto[];
  readonly bucketTotals: AgeingBucketTotalsDto;
  readonly totalPendingAmount: string;
}

// ---------------------------------------------------------------------------
// 6. UM-110-01 Level 2 / Level 3 Account Books, Exceptions, Statistics & Stock Query
// ---------------------------------------------------------------------------

export type AccountBookType =
  | 'CASH_BANK_SUMMARY'
  | 'GROUP_SUMMARY'
  | 'GROUP_VOUCHERS'
  | 'LEDGER_VOUCHERS'
  | 'SALES_REGISTER'
  | 'PURCHASE_REGISTER'
  | 'JOURNAL_REGISTER'
  | 'CONTRA_REGISTER'
  | 'PAYMENT_REGISTER'
  | 'RECEIPT_REGISTER'
  | 'DEBIT_NOTE_REGISTER'
  | 'CREDIT_NOTE_REGISTER';

export type ExceptionReportType =
  | 'NEGATIVE_STOCK'
  | 'NEGATIVE_LEDGERS'
  | 'OVERDUE_RECEIVABLES'
  | 'OVERDUE_PAYABLES'
  | 'OPTIONAL_VOUCHERS'
  | 'CANCELLED_VOUCHERS'
  | 'MEMORANDUM_VOUCHERS'
  | 'REVERSING_JOURNALS'
  | 'APPROVAL_QUEUE';

export type TallyLevel3WorkbenchMode =
  | 'ACCOUNT_BOOKS'
  | 'EXCEPTIONS'
  | 'STATISTICS'
  | 'STOCK_QUERY';

export interface AccountBookMonthlySummaryDto {
  readonly monthLabel: string;
  readonly fromDate: string;
  readonly toDate: string;
  readonly voucherCount: number;
  readonly debitAmount: string;
  readonly creditAmount: string;
  readonly closingBalance: string;
}

export interface AccountBookRowDto {
  readonly id: string;
  readonly voucherId?: string | null;
  readonly rowDate: string;
  readonly particulars: string;
  readonly voucherTypeName: string;
  readonly voucherNumber: string;
  readonly debitAmount: string;
  readonly creditAmount: string;
  readonly closingBalance: string;
}

export interface AccountBookReportDto {
  readonly bookType: AccountBookType;
  readonly fromDate: string;
  readonly toDate: string;
  readonly monthlySummary: readonly AccountBookMonthlySummaryDto[];
  readonly rows: readonly AccountBookRowDto[];
  readonly totalDebit: string;
  readonly totalCredit: string;
  readonly closingBalance: string;
}

export interface ExceptionReportRowDto {
  readonly id: string;
  readonly entityId: string;
  readonly voucherId?: string | null;
  readonly code: string;
  readonly name: string;
  readonly secondaryLabel: string;
  readonly rowDate: string;
  readonly quantity: string;
  readonly amount: string;
  readonly status: string;
}

export interface ExceptionReportDto {
  readonly exceptionType: ExceptionReportType;
  readonly fromDate: string;
  readonly toDate: string;
  readonly rows: readonly ExceptionReportRowDto[];
  readonly totalCount: number;
  readonly totalAmount: string;
}

export interface StatisticsVoucherTypeRowDto {
  readonly voucherTypeName: string;
  readonly category: string;
  readonly postedCount: number;
  readonly optionalCount: number;
  readonly cancelledCount: number;
  readonly totalCount: number;
}

export interface StatisticsMasterRowDto {
  readonly masterType: string;
  readonly count: number;
}

export interface StatisticsReportDto {
  readonly fromDate: string;
  readonly toDate: string;
  readonly voucherTypeCounts: readonly StatisticsVoucherTypeRowDto[];
  readonly masterCounts: readonly StatisticsMasterRowDto[];
  readonly totalVouchers: number;
  readonly totalMasters: number;
}

export interface StockQueryGodownRowDto {
  readonly godownName: string;
  readonly batchName: string;
  readonly closingQty: string;
  readonly closingValue: string;
}

export interface StockQueryReportDto {
  readonly stockItemId: string;
  readonly stockItemName: string;
  readonly uomSymbol: string;
  readonly costingMethod: string;
  readonly openingQty: string;
  readonly openingValue: string;
  readonly inwardQty: string;
  readonly inwardValue: string;
  readonly outwardQty: string;
  readonly outwardValue: string;
  readonly closingQty: string;
  readonly closingValue: string;
  readonly lastPurchaseParty: string;
  readonly lastPurchaseRate: string;
  readonly lastSalesParty: string;
  readonly lastSalesRate: string;
  readonly godownBreakup: readonly StockQueryGodownRowDto[];
}
