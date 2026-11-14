export type ExchangeTab = 'MIGRATE_1800' | 'IMPORT_EXPORT';

export type ExchangeFormat = 'XML' | 'JSONEX' | 'EXCEL';
export type ExchangeEntityScope = 'ALL' | 'MASTERS' | 'VOUCHERS' | 'TRIAL_BALANCE';

export type ImportJobStatus =
  | 'STAGED'
  | 'PROOF_READY'
  | 'COMMITTED'
  | 'FAILED'
  | 'ROLLED_BACK';

export interface StageTally1800Request {
  readonly snapshotDirectoryPath: string;
  readonly companionXmlDirectoryPath?: string | null;
}

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
  readonly createdAt?: string;
}

export interface CreateExchangeTemplateRequest {
  readonly templateName: string;
  readonly entityScope: 'MASTERS' | 'VOUCHERS';
  readonly sheetName: string;
  readonly headerRowIndex: number;
  readonly columnMappings: Readonly<Record<string, string>>;
}

export interface ExportDataRequest {
  readonly entityScope: ExchangeEntityScope;
  readonly fromDate?: string;
  readonly toDate?: string;
  readonly includeDependentMasters?: boolean;
  readonly templateId?: string | null;
}

export interface ExportDataResultDto {
  readonly format: ExchangeFormat;
  readonly fileName: string;
  readonly mimeType: string;
  readonly content: string;
  readonly recordCount: number;
  readonly sha256Hex?: string | null;
}

export interface ImportDataRequest {
  readonly fileName: string;
  readonly content: string;
  readonly base64Content?: string | null;
  readonly templateId?: string | null;
  readonly dryRun?: boolean;
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
}
