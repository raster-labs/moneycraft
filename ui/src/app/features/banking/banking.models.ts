/**
 * MoneyCraft 1.0.0 — Phase 6 Banking Auto-BRS, Cheque Books & Docs-by-Ira OCR Staging Models
 *
 * Every monetary balance is transmitted as an exact decimal string (`"125000.00"`).
 */

export type BankStatementFormat = 'CSV' | 'MT940' | 'CAMT_053';
export type BankingWorkbenchTab = 'BRS' | 'CHEQUE_BOOKS' | 'OCR_QUEUE' | 'PAYROLL';

export interface PayrollHeadDto {
  readonly id: string;
  readonly name: string;
  readonly headType: 'EARNINGS' | 'DEDUCTIONS' | 'EMPLOYER_CONTRIBUTION';
  readonly ledgerId?: string | null;
}

export interface EmployeeDto {
  readonly id: string;
  readonly employeeCode: string;
  readonly displayName: string;
  readonly designation?: string | null;
  readonly dateOfJoining?: string | null;
}

// ---------------------------------------------------------------------------
// 1. Cheque Books & Leaves (`GET|POST /banking/cheque-books`)
// ---------------------------------------------------------------------------

export interface ChequeBookDto {
  readonly id: string;
  readonly bankLedgerId: string;
  readonly bankLedgerName?: string | null;
  readonly bookName: string;
  readonly fromLeafNo: string;
  readonly toLeafNo: string;
  readonly totalLeaves: number;
  readonly availableLeaves: number;
}

export interface CreateChequeBookRequest {
  readonly bankLedgerId: string;
  readonly bookName: string;
  readonly fromLeafNo: string;
  readonly toLeafNo: string;
}

export interface ChequeLeafDto {
  readonly id: string;
  readonly chequeBookId: string;
  readonly leafNumber: string;
  readonly status: 'AVAILABLE' | 'ISSUED' | 'CANCELLED' | 'BLANK';
  readonly voucherId?: string | null;
  readonly voucherNumber?: string | null;
  readonly payeeName?: string | null;
  readonly amount?: string | null;
  readonly instrumentDate?: string | null;
}

// ---------------------------------------------------------------------------
// 2. Bank Statement Import & Auto-BRS (`GET /banking/brs`, `POST /banking/brs/auto-match`)
// ---------------------------------------------------------------------------

export interface ImportBankStatementRequest {
  readonly bankLedgerId: string;
  readonly fileName: string;
  readonly sourceFormat: BankStatementFormat;
  readonly rawContent: string;
}

export interface BankStatementImportResponse {
  readonly statementImportId: string;
  readonly bankLedgerId: string;
  readonly fileName: string;
  readonly sourceFormat: BankStatementFormat;
  readonly importedLinesCount: number;
  readonly closingBalance?: string | null;
}

export interface BrsAllocationRowDto {
  readonly bankAllocationId: string;
  readonly voucherId?: string | null;
  readonly voucherNumber: string;
  readonly voucherTypeName: string;
  readonly voucherDate: string;
  readonly transactionType: string;
  readonly instrumentNo: string | null;
  readonly instrumentDate: string | null;
  readonly partyOrNarration: string;
  readonly debitAmount: string;
  readonly creditAmount: string;
  readonly bankersDate: string | null;
}

export interface BrsStatementLineDto {
  readonly id: string;
  readonly valueDate: string;
  readonly description: string;
  readonly instrumentNo: string | null;
  readonly debitAmount: string;
  readonly creditAmount: string;
  readonly runningBalance?: string | null;
}

export interface BrsMatchRowDto {
  readonly bankAllocationId: string;
  readonly statementLineId: string;
  readonly voucherNumber: string;
  readonly instrumentNo: string | null;
  readonly amount: string;
  readonly bankersDate: string;
  readonly matchConfidence: string;
}

export interface BrsSummaryDto {
  readonly bankLedgerId: string;
  readonly bankLedgerName: string;
  readonly balanceAsPerCompanyBooks: string;
  readonly amountsNotReflectedInBankDr: string;
  readonly amountsNotReflectedInBankCr: string;
  readonly balanceAsPerBankComputed: string;
  readonly statementClosingBalance: string;
  readonly unreconciledAllocations: readonly BrsAllocationRowDto[];
  readonly unmatchedStatementLines: readonly BrsStatementLineDto[];
  readonly matches: readonly BrsMatchRowDto[];
}

export interface AutoMatchBrsRequest {
  readonly bankLedgerId: string;
  readonly statementImportId?: string | null;
  readonly dateWindowDays: number;
}

export interface AutoMatchBrsResponse {
  readonly matchedCount: number;
  readonly remainingUnreconciledCount: number;
  readonly summary?: BrsSummaryDto | null;
}

export interface ManualReconcileRequest {
  readonly bankAllocationId: string;
  readonly bankersDate: string;
  readonly statementLineId?: string | null;
}

// ---------------------------------------------------------------------------
// 3. Docs-by-Ira OCR Document Staging (`GET|POST /banking/ocr-staging/documents`)
// ---------------------------------------------------------------------------

export interface OcrStagedDocumentDto {
  readonly id: string;
  readonly fileName: string;
  readonly documentType: 'PURCHASE_INVOICE' | 'EXPENSE_RECEIPT' | 'SALES_INVOICE';
  readonly supplierName: string;
  readonly supplierGstin: string | null;
  readonly invoiceNumber: string;
  readonly invoiceDate: string;
  readonly taxableAmount: string;
  readonly taxAmount: string;
  readonly totalAmount: string;
  readonly confidencePct: string;
  readonly reviewStatus: 'PENDING_REVIEW' | 'APPROVED_CONVERTED' | 'REJECTED';
  readonly convertedVoucherId?: string | null;
}

export interface CreateOcrStagedDocRequest {
  readonly fileName: string;
  readonly documentType: 'PURCHASE_INVOICE' | 'EXPENSE_RECEIPT' | 'SALES_INVOICE';
  readonly supplierName: string;
  readonly supplierGstin?: string | null;
  readonly invoiceNumber: string;
  readonly invoiceDate: string;
  readonly taxableAmount: string;
  readonly taxAmount: string;
  readonly totalAmount: string;
  readonly rawExtractedJson?: string;
}

export interface ApproveConvertOcrRequest {
  readonly voucherTypeName?: string;
  readonly partyLedgerId?: string | null;
  readonly expenseOrPurchaseLedgerId?: string | null;
  readonly verifiedTotalAmount: string;
  readonly narration?: string;
}
