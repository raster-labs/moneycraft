export type ExchangeTab = 'MIGRATE_1800' | 'SPREADSHEET_WIZARD' | 'IMPORT_EXPORT';

export type ExchangeFormat = 'XML' | 'JSONEX' | 'EXCEL' | 'ASCII' | 'HTML';
export type ExchangeEntityScope =
  | 'ALL'
  | 'MASTERS'
  | 'VOUCHERS'
  | 'TRIAL_BALANCE'
  | 'REMINDER_LETTERS'
  | 'CONFIRMATION_OF_ACCOUNTS';
export type MasterDuplicateRule =
  | 'COMBINE_OPENING_BALANCES'
  | 'IGNORE_DUPLICATES'
  | 'MODIFY_WITH_NEW_DATA';
export type ImportExceptionBehavior =
  | 'STOP_AT_FIRST_EXCEPTION'
  | 'RECORD_EXCEPTIONS_AND_IMPORT'
  | 'IGNORE_EXCEPTIONS_AND_IMPORT';
export type SampleExcelType =
  | 'ALL'
  | 'ACCOUNTING_MASTERS'
  | 'INVENTORY_MASTERS'
  | 'ACCOUNTING_VOUCHERS'
  | 'ITEM_VOUCHERS';

export type ImportJobStatus =
  | 'STAGED'
  | 'STAGED_VERIFIED'
  | 'PROOF_READY'
  | 'COMMITTED'
  | 'FAILED'
  | 'ROLLED_BACK';

export interface StageLegacy1800Request {
  readonly snapshotDirectoryPath: string;
  readonly companionXmlDirectoryPath?: string | null;
}

/** Alias retained for backward TypeScript compatibility. */
export type StageTally1800Request = StageLegacy1800Request;

export interface ImportJobSummaryDto {
  readonly id: string;
  readonly jobType: string;
  readonly status: ImportJobStatus;
  readonly sourceCompanyName: string;
  readonly snapshotDirectoryPath: string;
  readonly isZeroVariance: boolean;
  readonly varianceAmount: string;
  readonly proofSha256Hex: string;
  readonly createdAt: string;
  readonly committedAt?: string | null;
}

export interface ImportJobProofDto {
  readonly jobId: string;
  readonly status: ImportJobStatus;
  readonly sourceCompanyName: string;
  readonly snapshotDirectoryPath: string;
  readonly companionXmlDirectoryPath?: string | null;
  readonly sector0Verified: boolean;
  readonly cmpSaveParityVerified: boolean;
  readonly sourceGroupCount: number;
  readonly stagedGroupCount: number;
  readonly sourceLedgerCount: number;
  readonly stagedLedgerCount: number;
  readonly sourceStockItemCount: number;
  readonly stagedStockItemCount: number;
  readonly sourceVoucherCount: number;
  readonly stagedVoucherCount: number;
  readonly sourceGrossDebit: string;
  readonly stagedGrossDebit: string;
  readonly sourceGrossCredit: string;
  readonly stagedGrossCredit: string;
  readonly sourceNetTrialBalance: string;
  readonly stagedNetTrialBalance: string;
  readonly sourceClosingStock: string;
  readonly stagedClosingStock: string;
  readonly sourceNetProfit: string;
  readonly stagedNetProfit: string;
  readonly varianceAmount: string;
  readonly isZeroVariance: boolean;
  readonly proofSha256Hex: string;
  readonly editLogBaselineVersion: number;
  readonly warnings: readonly string[];
  readonly stagedAt?: string | null;
  readonly committedAt?: string | null;
}

export interface ExchangeMappingTemplateDto {
  readonly id: string;
  readonly templateName: string;
  readonly entityScope: 'MASTERS' | 'VOUCHERS';
  readonly sheetName: string;
  readonly headerRowIndex: number;
  readonly columnMappings: Readonly<Record<string, string>>;
  readonly templateScope?: 'THIS_COMPANY' | 'ALL_COMPANIES';
  readonly fromColumn?: string | null;
  readonly toColumn?: string | null;
  readonly excludeRowColumn?: string | null;
  readonly excludeRowOperator?: string | null;
  readonly excludeRowValue?: string | null;
  readonly createdAt?: string;
}

export interface CreateExchangeTemplateRequest {
  readonly templateName: string;
  readonly entityScope: 'MASTERS' | 'VOUCHERS';
  readonly sheetName: string;
  readonly headerRowIndex: number;
  readonly columnMappings: Readonly<Record<string, string>>;
  readonly defaultValues?: Readonly<Record<string, string>>;
  readonly templateScope?: 'THIS_COMPANY' | 'ALL_COMPANIES';
  readonly fromColumn?: string | null;
  readonly toColumn?: string | null;
  readonly excludeRowColumn?: string | null;
  readonly excludeRowOperator?: string | null;
  readonly excludeRowValue?: string | null;
}

export interface ExportDataRequest {
  readonly entityScope: ExchangeEntityScope;
  readonly fromDate?: string;
  readonly toDate?: string;
  readonly includeDependentMasters?: boolean;
  readonly templateId?: string | null;
  readonly includeNarration?: boolean;
  readonly includeBillWise?: boolean;
  readonly includeInventory?: boolean;
  readonly digitalSignatureSignerName?: string | null;
}

export interface ExportDataResultDto {
  readonly format: ExchangeFormat;
  readonly fileName: string;
  readonly mimeType: string;
  readonly content: string;
  readonly recordCount: number;
  readonly sha256Hex?: string | null | undefined;
}

export interface ImportDataRequest {
  readonly fileName: string;
  readonly content: string;
  readonly base64Content?: string | null;
  readonly templateId?: string | null;
  readonly dryRun?: boolean;
  readonly masterDuplicateRule?: MasterDuplicateRule;
  readonly overwriteVoucherByGuid?: boolean;
  readonly exceptionBehavior?: ImportExceptionBehavior;
}

export interface ImportExceptionItemDto {
  readonly exceptionId: string;
  readonly jobId?: string | null;
  readonly entityType: string;
  readonly identifier: string;
  readonly errorCode: string;
  readonly message: string;
  readonly resolved: boolean;
  readonly resolvedEntityId?: string | null;
  readonly createdAt?: string;
}

export interface ResolveImportExceptionRequest {
  readonly voucherTypeName: string;
  readonly voucherDate: string;
  readonly debitLedgerName: string;
  readonly creditLedgerName: string;
  readonly amount: string;
  readonly narration?: string | null;
}

export interface ImportDataResultDto {
  readonly jobId?: string | null;
  readonly format: ExchangeFormat;
  readonly status: string;
  readonly groupsImported: number;
  readonly ledgersImported: number;
  readonly stockItemsImported: number;
  readonly vouchersImported: number;
  readonly totalDebit: string;
  readonly totalCredit: string;
  readonly isBalanced: boolean;
  readonly message: string;
  readonly mastersSkipped?: number;
  readonly vouchersUpdated?: number;
  readonly vouchersSkipped?: number;
  readonly exceptionsRecorded?: number;
  readonly exceptions?: readonly ImportExceptionItemDto[];
}

export interface SyncRuleRequest {
  readonly ruleName: string;
  readonly ruleType: 'MASTER_SYNC' | 'TRANSACTION_SYNC' | 'FULL_SYNC';
  readonly direction: 'BOTH_WAYS' | 'SEND_ONLY' | 'RECEIVE_ONLY';
  readonly targetCompanyId?: string | null;
  readonly targetCompanyGuid: string;
  readonly targetCompanyName: string;
  readonly serverUrl?: string | null;
  readonly masterDuplicateRule?: MasterDuplicateRule;
  readonly overwriteVoucherByGuid?: boolean;
}

export interface SyncRuleDto {
  readonly id: string;
  readonly companyId: string;
  readonly ruleName: string;
  readonly ruleType: string;
  readonly direction: string;
  readonly targetCompanyId?: string | null;
  readonly targetCompanyGuid: string;
  readonly targetCompanyName: string;
  readonly serverUrl: string;
  readonly masterDuplicateRule: string;
  readonly overwriteVoucherByGuid: boolean;
  readonly lastSyncAlterId: number;
  readonly active: boolean;
  readonly createdAt?: string;
}

export interface SyncSessionResultDto {
  readonly sessionId: string;
  readonly ruleId: string;
  readonly ruleName: string;
  readonly sourceCompanyId: string;
  readonly targetCompanyId?: string | null;
  readonly status: string;
  readonly previousAlterIdWatermark: number;
  readonly newAlterIdWatermark: number;
  readonly mastersSynced: number;
  readonly vouchersSynced: number;
  readonly exceptionsRecorded: number;
  readonly message: string;
  readonly completedAt?: string;
}

// ---------------------------------------------------------------------------
// Increment X — Guided Multi-Format Columnar Spreadsheet Importer (`SHEET-001..008`)
// ---------------------------------------------------------------------------

export type SpreadsheetTargetType = 'VOUCHERS' | 'LEDGERS';
export type SpreadsheetVoucherAction = 'POST' | 'SAVE_DRAFT' | 'SUBMIT_FOR_APPROVAL';

export interface LedgerColumnMappingDto {
  sourceColumn: string;
  ledgerName: string;
  ledgerId?: string | null;
  side: 'DR' | 'CR';
  lineRole: 'ACCOUNTING' | 'TAX' | 'DISCOUNT' | 'ROUND_OFF' | 'LEDGER';
}

export interface SpreadsheetMappingSpecDto {
  readonly targetType: SpreadsheetTargetType | string;
  readonly voucherAction?: SpreadsheetVoucherAction | string;
  readonly defaultVoucherType?: string | null;
  readonly voucherDateColumn?: string | null;
  readonly voucherTypeColumn?: string | null;
  readonly voucherNumberColumn?: string | null;
  readonly referenceNumberColumn?: string | null;
  readonly narrationColumn?: string | null;
  readonly groupingKeyColumn?: string | null;
  readonly partyLedgerNameColumn?: string | null;
  readonly partyAmountColumn?: string | null;
  readonly partySide?: 'DR' | 'CR' | string | null;
  readonly debitLedgerColumn?: string | null;
  readonly creditLedgerColumn?: string | null;
  readonly amountColumn?: string | null;
  readonly ledgerColumns: readonly LedgerColumnMappingDto[];
  readonly ledgerNameColumn?: string | null;
  readonly aliasCodeColumn?: string | null;
  readonly groupNameColumn?: string | null;
  readonly openingBalanceColumn?: string | null;
  readonly openingSideColumn?: string | null;
  readonly gstinColumn?: string | null;
  readonly stateCodeColumn?: string | null;
  readonly stateNameColumn?: string | null;
  readonly billWiseColumn?: string | null;
  readonly datePattern?: string | null;
  readonly duplicateHandling?: string | null;
}

export interface GoogleSheetCaptureSpecDto {
  readonly spreadsheetId: string;
  readonly sheetName: string;
  readonly rangeA1: string;
  readonly scopeUsed: string;
  readonly endpointUri?: string | null;
  readonly frozenRows?: readonly (readonly string[])[];
}

export interface PrepareSpreadsheetBatchRequest {
  readonly fileName: string;
  readonly formatHint?: string | null;
  readonly base64Content?: string | null;
  readonly csvText?: string | null;
  readonly sheetName?: string | null;
  readonly headerRowIndex?: number | null;
  readonly delimiter?: string | null;
  readonly cachedFormulaConsent?: boolean;
  readonly acknowledgeDuplicateRun?: boolean;
  readonly templateId?: string | null;
  readonly mapping?: SpreadsheetMappingSpecDto | null;
  readonly googleSheetCapture?: GoogleSheetCaptureSpecDto | null;
}

export interface RemapSpreadsheetBatchRequest {
  readonly sheetName?: string | null;
  readonly headerRowIndex?: number | null;
  readonly delimiter?: string | null;
  readonly cachedFormulaConsent?: boolean;
  readonly templateId?: string | null;
  readonly mapping: SpreadsheetMappingSpecDto;
}

export interface ExecuteSpreadsheetBatchRequest {
  readonly action?: SpreadsheetVoucherAction | null;
  readonly allowValidUnitsOnly?: boolean;
  readonly maxUnitsThisRun?: number | null;
}

export interface SaveSpreadsheetTemplateRequest {
  readonly templateId?: string | null;
  readonly name: string;
  readonly targetType: string;
  readonly sheetName: string;
  readonly headerRowIndex: number;
  readonly mapping: SpreadsheetMappingSpecDto;
  readonly duplicateHandling?: string;
}

export interface SpreadsheetTemplateDto {
  readonly id: string;
  readonly companyId: string;
  readonly name: string;
  readonly targetType: string;
  readonly sheetName: string;
  readonly headerRowIndex: number;
  readonly mapping: SpreadsheetMappingSpecDto;
  readonly duplicateHandling: string;
  readonly versionNo: number;
  readonly createdAt?: string;
}

export interface CellValidationErrorDto {
  readonly code: string;
  readonly field: string;
  readonly sheetName: string;
  readonly rowNumber: number;
  readonly columnIndex: number;
  readonly cellRef: string;
  readonly columnHeader: string;
  readonly rawCellSnippet: string;
  readonly message: string;
}

export interface SourceCoordinatesDto {
  readonly sheetName: string;
  readonly rowNumbers: readonly number[];
  readonly cellRefs: Readonly<Record<string, string>>;
}

export interface SpreadsheetUnitDto {
  readonly id: string;
  readonly unitSeq: number;
  readonly unitKey: string;
  readonly targetType: string;
  readonly validationStatus: string;
  readonly executionStatus: string;
  readonly unitDebit: string;
  readonly unitCredit: string;
  readonly unitIdempotencyKey: string;
  readonly sourceCoordinates?: SourceCoordinatesDto | null;
  readonly intendedSummary?: Readonly<Record<string, unknown>> | null;
  readonly validationErrors: readonly CellValidationErrorDto[];
  readonly executionErrorCode?: string | null;
  readonly executionErrorMessage?: string | null;
  readonly resultingEntityId?: string | null;
  readonly resultingReference?: string | null;
  readonly committedAt?: string | null;
}

export interface SpreadsheetBatchDto {
  readonly id: string;
  readonly companyId: string;
  readonly sourceFileName: string;
  readonly sourceSha256Hex: string;
  readonly formatProfile: string;
  readonly targetType: string;
  readonly sheetName: string;
  readonly availableSheets: readonly string[];
  readonly detectedHeaders: readonly string[];
  readonly headerRowIndex: number;
  readonly dateSystem: string;
  readonly macroDetected: boolean;
  readonly formulaCellsDetected: boolean;
  readonly cachedFormulaPolicyAccepted: boolean;
  readonly templateId?: string | null;
  readonly templateVersion?: number | null;
  readonly mappingRevision: number;
  readonly mapping?: SpreadsheetMappingSpecDto | null;
  readonly status: string;
  readonly previousMatchingBatchId?: string | null;
  readonly totalUnits: number;
  readonly validUnits: number;
  readonly invalidUnits: number;
  readonly committedUnits: number;
  readonly failedUnits: number;
  readonly skippedUnits: number;
  readonly uncertainUnits: number;
  readonly totalDebit: string;
  readonly totalCredit: string;
  readonly diagnostics: readonly string[];
  readonly versionNo: number;
  readonly createdAt?: string;
  readonly completedAt?: string | null;
  readonly units: readonly SpreadsheetUnitDto[];
}

// ---------------------------------------------------------------------------
// Five-Loop Deep-Dive Models (`GAP-L2-001`..`GAP-L5-001`)
// ---------------------------------------------------------------------------

export interface MultiAccountReportExportRequest {
  readonly reportType?: 'CONFIRMATION_OF_ACCOUNTS' | 'REMINDER_LETTER';
  readonly fromDate?: string | null;
  readonly toDate?: string | null;
  readonly format?: 'JSON' | 'HTML' | 'ASCII';
  readonly includeZeroBalanceParties?: boolean;
}

export interface MultiAccountPartyStatementDto {
  readonly ledgerId: string;
  readonly partyName: string;
  readonly groupName: string;
  readonly gstin?: string | null;
  readonly openingBalance: string;
  readonly openingSide: string;
  readonly periodDebit: string;
  readonly periodCredit: string;
  readonly closingBalance: string;
  readonly closingSide: string;
  readonly slab0To30: string;
  readonly slab31To60: string;
  readonly slab61To90: string;
  readonly slabOver90: string;
}

export interface MultiAccountReportExportResponse {
  readonly companyId: string;
  readonly companyName: string;
  readonly reportType: string;
  readonly fromDate: string;
  readonly toDate: string;
  readonly format: string;
  readonly partyCount: number;
  readonly statements: readonly MultiAccountPartyStatementDto[];
  readonly renderedContent: string;
}

export interface EditLogSummaryItemDto {
  readonly entityType: string;
  readonly entityId: string;
  readonly displayName: string;
  readonly voucherNumber?: string | null;
  readonly activityType: string;
  readonly alteredDueTo: string;
  readonly versionCount: number;
  readonly latestAlterId: number;
  readonly lastModifiedBy: string;
  readonly lastModifiedAt: string;
}

export interface EditLogSummaryResponseDto {
  readonly companyId: string;
  readonly entityTypeFilter: string;
  readonly onlyAltered: boolean;
  readonly totalItems: number;
  readonly alteredItems: number;
  readonly items: readonly EditLogSummaryItemDto[];
}

export interface DashboardTilesExportRequest {
  readonly fromDate?: string | null;
  readonly toDate?: string | null;
  readonly format?: 'JSON' | 'HTML' | 'ASCII';
}

export interface DashboardTileDto {
  readonly tileCode: string;
  readonly tileTitle: string;
  readonly primaryMetricLabel: string;
  readonly primaryMetricValue: string;
  readonly breakdown: Record<string, string>;
}

export interface DashboardTilesExportResponse {
  readonly companyId: string;
  readonly companyName: string;
  readonly fromDate: string;
  readonly toDate: string;
  readonly format: string;
  readonly totalTiles: number;
  readonly tiles: readonly DashboardTileDto[];
  readonly renderedContent: string;
}

export interface DataVerificationCheckDto {
  readonly checkCode: string;
  readonly checkTitle: string;
  readonly status: string;
  readonly issueCount: number;
  readonly detail: string;
}

export interface DataVerificationReportDto {
  readonly companyId: string;
  readonly companyName: string;
  readonly trialBalanceDifference: string;
  readonly unbalancedVoucherCount: number;
  readonly unitMismatchCount: number;
  readonly missingHsnOrGstinCount: number;
  readonly pendingImportExceptionCount: number;
  readonly readyForSplitOrSync: boolean;
  readonly checks: readonly DataVerificationCheckDto[];
}
