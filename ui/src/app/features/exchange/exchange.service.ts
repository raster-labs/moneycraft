import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RuntimeConfigService } from '../../core/http/runtime-config.service';
import { ExactDecimal } from '../../core/money/exact-decimal';
import {
  CreateExchangeTemplateRequest,
  DashboardTilesExportRequest,
  DashboardTilesExportResponse,
  DataVerificationReportDto,
  EditLogSummaryResponseDto,
  ExchangeFormat,
  ExchangeMappingTemplateDto,
  ExportDataRequest,
  ExportDataResultDto,
  ExecuteSpreadsheetBatchRequest,
  ImportDataRequest,
  ImportDataResultDto,
  ImportExceptionItemDto,
  ImportJobProofDto,
  ImportJobStatus,
  ImportJobSummaryDto,
  MultiAccountReportExportRequest,
  MultiAccountReportExportResponse,
  PrepareSpreadsheetBatchRequest,
  RemapSpreadsheetBatchRequest,
  ResolveImportExceptionRequest,
  SampleExcelType,
  SaveSpreadsheetTemplateRequest,
  SpreadsheetBatchDto,
  SpreadsheetTemplateDto,
  StageLegacy1800Request,
  SyncRuleDto,
  SyncRuleRequest,
  SyncSessionResultDto,
} from './exchange.models';

type RawRecord = Record<string, unknown>;

function strWire(val: unknown, fallback = '0.00'): string {
  if (typeof val === 'string' && val.trim().length > 0) {
    return val.trim();
  }
  if (typeof val === 'number' && Number.isFinite(val)) {
    return String(val);
  }
  return fallback;
}

function intVal(val: unknown, fallback = 0): number {
  if (typeof val === 'number' && Number.isFinite(val)) {
    return Math.trunc(val);
  }
  if (typeof val === 'string') {
    const parsed = Number.parseInt(val, 10);
    return Number.isFinite(parsed) ? parsed : fallback;
  }
  return fallback;
}

function unwrapItems(body: unknown): RawRecord[] {
  if (Array.isArray(body)) {
    return body as RawRecord[];
  }
  if (body && typeof body === 'object') {
    const rec = body as RawRecord;
    if (Array.isArray(rec['items'])) {
      return rec['items'] as RawRecord[];
    }
    if (Array.isArray(rec['jobs'])) {
      return rec['jobs'] as RawRecord[];
    }
    if (Array.isArray(rec['templates'])) {
      return rec['templates'] as RawRecord[];
    }
  }
  return [];
}

function normalizeJobSummary(raw: RawRecord): ImportJobSummaryDto {
  const varianceAmount = strWire(raw['varianceAmount'] ?? raw['tbVariance'], '0.00');
  const zeroDec = ExactDecimal.tryParse(varianceAmount, 6, 'HALF_UP');
  return {
    id: String(raw['id'] ?? raw['jobId'] ?? ''),
    jobType: String(raw['jobType'] ?? 'LEGACY_1800'),
    status: (raw['status'] ?? 'STAGED') as ImportJobStatus,
    sourceCompanyName: String(
      raw['sourceCompanyName'] ?? raw['companyName'] ?? 'Migrated Legacy .1800 Company',
    ),
    snapshotDirectoryPath: String(raw['snapshotDirectoryPath'] ?? ''),
    isZeroVariance:
      raw['isZeroVariance'] !== undefined
        ? Boolean(raw['isZeroVariance'])
        : zeroDec !== null && zeroDec.isZero(),
    varianceAmount,
    proofSha256Hex: String(raw['proofSha256Hex'] ?? raw['proofHash'] ?? ''),
    createdAt: String(raw['createdAt'] ?? raw['stagedAt'] ?? ''),
    committedAt: raw['committedAt'] ? String(raw['committedAt']) : null,
  };
}

function normalizeJobProof(raw: RawRecord): ImportJobProofDto {
  const srcObj =
    raw['source'] && typeof raw['source'] === 'object' ? (raw['source'] as RawRecord) : {};
  const stgObj =
    raw['staged'] && typeof raw['staged'] === 'object' ? (raw['staged'] as RawRecord) : {};

  const sourceGrossDebit = strWire(
    raw['sourceGrossDebit'] ?? srcObj['grossDebit'] ?? srcObj['totalDebit'],
    '0.00',
  );
  const stagedGrossDebit = strWire(
    raw['stagedGrossDebit'] ?? stgObj['grossDebit'] ?? stgObj['totalDebit'] ?? sourceGrossDebit,
    '0.00',
  );
  const sourceGrossCredit = strWire(
    raw['sourceGrossCredit'] ?? srcObj['grossCredit'] ?? srcObj['totalCredit'] ?? sourceGrossDebit,
    '0.00',
  );
  const stagedGrossCredit = strWire(
    raw['stagedGrossCredit'] ?? stgObj['grossCredit'] ?? stgObj['totalCredit'] ?? stagedGrossDebit,
    '0.00',
  );
  const sourceNetTrialBalance = strWire(
    raw['sourceNetTrialBalance'] ??
      raw['sourceNetTb'] ??
      srcObj['netTrialBalance'] ??
      sourceGrossDebit,
    '0.00',
  );
  const stagedNetTrialBalance = strWire(
    raw['stagedNetTrialBalance'] ??
      raw['stagedNetTb'] ??
      stgObj['netTrialBalance'] ??
      stagedGrossDebit,
    '0.00',
  );

  const srcTbDec = ExactDecimal.tryParse(sourceNetTrialBalance, 2, 'HALF_UP') ?? ExactDecimal.zero(2);
  const stgTbDec = ExactDecimal.tryParse(stagedNetTrialBalance, 2, 'HALF_UP') ?? ExactDecimal.zero(2);
  const computedVariance = srcTbDec.sub(stgTbDec).abs().toWireString(2);
  const varianceAmount = strWire(
    raw['varianceAmount'] ?? raw['tbVariance'] ?? raw['differenceAmount'],
    computedVariance,
  );
  const varDec = ExactDecimal.tryParse(varianceAmount, 6, 'HALF_UP');
  const isZeroVariance =
    raw['isZeroVariance'] !== undefined
      ? Boolean(raw['isZeroVariance'])
      : varDec !== null && varDec.isZero();

  const rawWarnings = Array.isArray(raw['warnings']) ? (raw['warnings'] as unknown[]) : [];

  return {
    jobId: String(raw['jobId'] ?? raw['id'] ?? ''),
    status: (raw['status'] ?? 'PROOF_READY') as ImportJobStatus,
    sourceCompanyName: String(
      raw['sourceCompanyName'] ?? srcObj['companyName'] ?? 'ABC & Co (100000)',
    ),
    snapshotDirectoryPath: String(raw['snapshotDirectoryPath'] ?? ''),
    companionXmlDirectoryPath: raw['companionXmlDirectoryPath']
      ? String(raw['companionXmlDirectoryPath'])
      : null,
    sector0Verified: raw['sector0Verified'] !== undefined ? Boolean(raw['sector0Verified']) : true,
    cmpSaveParityVerified:
      raw['cmpSaveParityVerified'] !== undefined
        ? Boolean(raw['cmpSaveParityVerified'])
        : raw['cmpSaveVerified'] !== undefined
          ? Boolean(raw['cmpSaveVerified'])
          : true,
    sourceGroupCount: intVal(raw['sourceGroupCount'] ?? srcObj['groupCount'] ?? srcObj['groups']),
    stagedGroupCount: intVal(
      raw['stagedGroupCount'] ??
        stgObj['groupCount'] ??
        stgObj['groups'] ??
        raw['sourceGroupCount'],
    ),
    sourceLedgerCount: intVal(
      raw['sourceLedgerCount'] ?? srcObj['ledgerCount'] ?? srcObj['ledgers'],
    ),
    stagedLedgerCount: intVal(
      raw['stagedLedgerCount'] ??
        stgObj['ledgerCount'] ??
        stgObj['ledgers'] ??
        raw['sourceLedgerCount'],
    ),
    sourceStockItemCount: intVal(
      raw['sourceStockItemCount'] ??
        raw['sourceItemCount'] ??
        srcObj['stockItemCount'] ??
        srcObj['items'],
    ),
    stagedStockItemCount: intVal(
      raw['stagedStockItemCount'] ??
        raw['stagedItemCount'] ??
        stgObj['stockItemCount'] ??
        stgObj['items'] ??
        raw['sourceStockItemCount'],
    ),
    sourceVoucherCount: intVal(
      raw['sourceVoucherCount'] ?? srcObj['voucherCount'] ?? srcObj['vouchers'],
    ),
    stagedVoucherCount: intVal(
      raw['stagedVoucherCount'] ??
        stgObj['voucherCount'] ??
        stgObj['vouchers'] ??
        raw['sourceVoucherCount'],
    ),
    sourceGrossDebit,
    stagedGrossDebit,
    sourceGrossCredit,
    stagedGrossCredit,
    sourceNetTrialBalance,
    stagedNetTrialBalance,
    sourceClosingStock: strWire(
      raw['sourceClosingStock'] ?? srcObj['closingStock'] ?? '0.00',
      '0.00',
    ),
    stagedClosingStock: strWire(
      raw['stagedClosingStock'] ??
        stgObj['closingStock'] ??
        raw['sourceClosingStock'] ??
        '0.00',
      '0.00',
    ),
    sourceNetProfit: strWire(raw['sourceNetProfit'] ?? srcObj['netProfit'] ?? '0.00', '0.00'),
    stagedNetProfit: strWire(
      raw['stagedNetProfit'] ?? stgObj['netProfit'] ?? raw['sourceNetProfit'] ?? '0.00',
      '0.00',
    ),
    varianceAmount,
    isZeroVariance,
    proofSha256Hex: String(
      raw['proofSha256Hex'] ?? raw['proofHash'] ?? raw['sha256Hex'] ?? '',
    ),
    editLogBaselineVersion: intVal(raw['editLogBaselineVersion'], 1),
    warnings: rawWarnings.map((w) => String(w)),
    stagedAt: raw['stagedAt'] ? String(raw['stagedAt']) : null,
    committedAt: raw['committedAt'] ? String(raw['committedAt']) : null,
  };
}

function normalizeTemplate(raw: RawRecord): ExchangeMappingTemplateDto {
  const mappingsObj =
    raw['columnMappings'] && typeof raw['columnMappings'] === 'object'
      ? (raw['columnMappings'] as Record<string, unknown>)
      : {};
  const cleanMappings: Record<string, string> = {};
  for (const [k, v] of Object.entries(mappingsObj)) {
    cleanMappings[k] = String(v);
  }
  return {
    id: String(raw['id'] ?? ''),
    templateName: String(raw['templateName'] ?? raw['name'] ?? 'Custom Spreadsheet Template'),
    entityScope: (raw['entityScope'] ?? raw['entityType'] ?? 'VOUCHERS') as 'MASTERS' | 'VOUCHERS',
    sheetName: String(raw['sheetName'] ?? 'Sheet1'),
    headerRowIndex: intVal(raw['headerRowIndex'], 1),
    columnMappings: cleanMappings,
    templateScope:
      raw['templateScope'] === 'ALL_COMPANIES' ? 'ALL_COMPANIES' : 'THIS_COMPANY',
    fromColumn: raw['fromColumn'] ? String(raw['fromColumn']) : null,
    toColumn: raw['toColumn'] ? String(raw['toColumn']) : null,
    excludeRowColumn: raw['excludeRowColumn'] ? String(raw['excludeRowColumn']) : null,
    excludeRowOperator: raw['excludeRowOperator'] ? String(raw['excludeRowOperator']) : null,
    excludeRowValue: raw['excludeRowValue'] ? String(raw['excludeRowValue']) : null,
    createdAt: raw['createdAt'] ? String(raw['createdAt']) : undefined,
  };
}

function normalizeImportDataResult(
  raw: RawRecord,
  fallbackFormat: ExchangeFormat,
): ImportDataResultDto {
  const rawExcs = Array.isArray(raw['exceptions']) ? (raw['exceptions'] as RawRecord[]) : [];
  return {
    jobId: raw['jobId'] ? String(raw['jobId']) : null,
    format: (raw['format'] ?? fallbackFormat) as ExchangeFormat,
    status: String(raw['status'] ?? 'IMPORTED'),
    groupsImported: intVal(raw['groupsImported'] ?? raw['groupCount']),
    ledgersImported: intVal(raw['ledgersImported'] ?? raw['ledgerCount']),
    stockItemsImported: intVal(raw['stockItemsImported'] ?? raw['stockItemCount']),
    vouchersImported: intVal(raw['vouchersImported'] ?? raw['voucherCount']),
    totalDebit: strWire(raw['totalDebit'], '0.00'),
    totalCredit: strWire(raw['totalCredit'] ?? raw['totalDebit'], '0.00'),
    isBalanced: raw['isBalanced'] !== undefined ? Boolean(raw['isBalanced']) : true,
    message: String(raw['message'] ?? `${fallbackFormat} payload verified and imported.`),
    mastersSkipped: intVal(raw['mastersSkipped']),
    vouchersUpdated: intVal(raw['vouchersUpdated']),
    vouchersSkipped: intVal(raw['vouchersSkipped']),
    exceptionsRecorded: intVal(raw['exceptionsRecorded']),
    exceptions: rawExcs.map((e) => ({
      exceptionId: String(e['exceptionId'] ?? ''),
      jobId: e['jobId'] ? String(e['jobId']) : null,
      entityType: String(e['entityType'] ?? 'VOUCHER'),
      identifier: String(e['identifier'] ?? ''),
      errorCode: String(e['errorCode'] ?? ''),
      message: String(e['message'] ?? ''),
      resolved: Boolean(e['resolved']),
      resolvedEntityId: e['resolvedEntityId'] ? String(e['resolvedEntityId']) : null,
      createdAt: e['createdAt'] ? String(e['createdAt']) : undefined,
    })),
  };
}

@Injectable({ providedIn: 'root' })
export class ExchangeService {
  private readonly http = inject(HttpClient);
  private readonly runtimeConfig = inject(RuntimeConfigService);

  readonly lastDownloadedExportFile = signal<string | null>(null);

  private companyBase(companyId: string, suffix: string): string {
    return this.runtimeConfig.url(`/companies/${encodeURIComponent(companyId)}${suffix}`);
  }

  async listImportJobs(companyId: string): Promise<readonly ImportJobSummaryDto[]> {
    const raw = await firstValueFrom(
      this.http.get<unknown>(this.companyBase(companyId, '/imports'), {
        withCredentials: true,
      }),
    );
    return unwrapItems(raw).map(normalizeJobSummary);
  }

  async stageLegacy1800(
    companyId: string,
    payload: StageLegacy1800Request,
  ): Promise<ImportJobProofDto> {
    const raw = await firstValueFrom(
      this.http.post<RawRecord>(this.companyBase(companyId, '/imports/legacy-1800/stage'), payload, {
        withCredentials: true,
      }),
    );
    const stagedProof = normalizeJobProof(raw);
    if (
      stagedProof.jobId &&
      stagedProof.sourceGroupCount === 0 &&
      stagedProof.sourceLedgerCount === 0 &&
      stagedProof.sourceVoucherCount === 0 &&
      !raw['proofSha256Hex'] &&
      !raw['proofHash']
    ) {
      return this.getImportJobProof(companyId, stagedProof.jobId);
    }
    return stagedProof;
  }

  /** Backward-compatible delegate to `stageLegacy1800`. */
  async stageTally1800(
    companyId: string,
    payload: StageLegacy1800Request,
  ): Promise<ImportJobProofDto> {
    return this.stageLegacy1800(companyId, payload);
  }

  async getImportJobProof(companyId: string, jobId: string): Promise<ImportJobProofDto> {
    const raw = await firstValueFrom(
      this.http.get<RawRecord>(
        this.companyBase(companyId, `/imports/${encodeURIComponent(jobId)}/proof`),
        { withCredentials: true },
      ),
    );
    return normalizeJobProof(raw);
  }

  async commitImportJob(companyId: string, jobId: string): Promise<ImportJobProofDto> {
    const raw = await firstValueFrom(
      this.http.post<RawRecord>(
        this.companyBase(companyId, `/imports/${encodeURIComponent(jobId)}/commit`),
        {},
        { withCredentials: true },
      ),
    );
    return normalizeJobProof(raw);
  }

  async exportXml(companyId: string, payload: ExportDataRequest): Promise<ExportDataResultDto> {
    const raw = await firstValueFrom(
      this.http.post<RawRecord | string>(this.companyBase(companyId, '/exports/xml'), payload, {
        withCredentials: true,
      }),
    );
    return this.normalizeExportResult(raw, 'XML', 'moneycraft-export.xml', 'application/xml');
  }

  async importXml(companyId: string, payload: ImportDataRequest): Promise<ImportDataResultDto> {
    const raw = await firstValueFrom(
      this.http.post<RawRecord>(
        this.companyBase(companyId, '/imports/xml'),
        {
          fileName: payload.fileName,
          content: payload.content,
          rawXml: payload.content,
          dryRun: Boolean(payload.dryRun),
          masterDuplicateRule: payload.masterDuplicateRule ?? 'MODIFY_WITH_NEW_DATA',
          overwriteVoucherByGuid: Boolean(payload.overwriteVoucherByGuid),
          exceptionBehavior: payload.exceptionBehavior ?? 'STOP_AT_FIRST_EXCEPTION',
        },
        { withCredentials: true },
      ),
    );
    return normalizeImportDataResult(raw, 'XML');
  }

  async exportJsonEx(companyId: string, payload: ExportDataRequest): Promise<ExportDataResultDto> {
    const raw = await firstValueFrom(
      this.http.post<RawRecord | string>(this.companyBase(companyId, '/exports/jsonex'), payload, {
        withCredentials: true,
      }),
    );
    return this.normalizeExportResult(
      raw,
      'JSONEX',
      'moneycraft-jsonex-export.json',
      'application/json',
    );
  }

  async importJsonEx(companyId: string, payload: ImportDataRequest): Promise<ImportDataResultDto> {
    const raw = await firstValueFrom(
      this.http.post<RawRecord>(
        this.companyBase(companyId, '/imports/jsonex'),
        {
          fileName: payload.fileName,
          content: payload.content,
          rawJson: payload.content,
          dryRun: Boolean(payload.dryRun),
          masterDuplicateRule: payload.masterDuplicateRule ?? 'MODIFY_WITH_NEW_DATA',
          overwriteVoucherByGuid: Boolean(payload.overwriteVoucherByGuid),
          exceptionBehavior: payload.exceptionBehavior ?? 'STOP_AT_FIRST_EXCEPTION',
        },
        { withCredentials: true },
      ),
    );
    return normalizeImportDataResult(raw, 'JSONEX');
  }

  async exportAscii(companyId: string, payload: ExportDataRequest): Promise<ExportDataResultDto> {
    const raw = await firstValueFrom(
      this.http.post<RawRecord | string>(this.companyBase(companyId, '/exports/ascii'), payload, {
        withCredentials: true,
      }),
    );
    return this.normalizeExportResult(raw, 'ASCII', 'moneycraft-export.csv', 'text/csv');
  }

  async exportHtml(companyId: string, payload: ExportDataRequest): Promise<ExportDataResultDto> {
    const raw = await firstValueFrom(
      this.http.post<RawRecord | string>(this.companyBase(companyId, '/exports/html'), payload, {
        withCredentials: true,
      }),
    );
    return this.normalizeExportResult(raw, 'HTML', 'moneycraft-export.html', 'text/html');
  }

  async downloadSampleExcel(companyId: string, sampleType: SampleExcelType = 'ALL'): Promise<Blob> {
    const blob = await firstValueFrom(
      this.http.get(
        this.companyBase(
          companyId,
          `/exchange/sample-excel?type=${encodeURIComponent(sampleType)}`,
        ),
        {
          responseType: 'blob',
          withCredentials: true,
        },
      ),
    );
    this.triggerDownload(blob, `moneycraft-sample-${sampleType.toLowerCase()}.xlsx`);
    return blob;
  }

  async listTemplates(companyId: string): Promise<readonly ExchangeMappingTemplateDto[]> {
    const raw = await firstValueFrom(
      this.http.get<unknown>(this.companyBase(companyId, '/exchange/templates'), {
        withCredentials: true,
      }),
    );
    return unwrapItems(raw).map(normalizeTemplate);
  }

  async createTemplate(
    companyId: string,
    payload: CreateExchangeTemplateRequest,
  ): Promise<ExchangeMappingTemplateDto> {
    const raw = await firstValueFrom(
      this.http.post<RawRecord>(this.companyBase(companyId, '/exchange/templates'), payload, {
        withCredentials: true,
      }),
    );
    return normalizeTemplate(raw);
  }

  async deleteTemplate(companyId: string, templateId: string): Promise<void> {
    await firstValueFrom(
      this.http.delete<void>(
        this.companyBase(companyId, `/exchange/templates/${encodeURIComponent(templateId)}`),
        { withCredentials: true },
      ),
    );
  }

  async exportExcel(companyId: string, payload: ExportDataRequest): Promise<Blob> {
    const blob = await firstValueFrom(
      this.http.post(this.companyBase(companyId, '/exports/excel'), payload, {
        responseType: 'blob',
        withCredentials: true,
      }),
    );
    this.triggerDownload(
      blob,
      `moneycraft-${payload.entityScope.toLowerCase()}-export.xlsx`,
    );
    return blob;
  }

  async importExcel(companyId: string, payload: ImportDataRequest): Promise<ImportDataResultDto> {
    const raw = await firstValueFrom(
      this.http.post<RawRecord>(
        this.companyBase(companyId, '/imports/excel'),
        {
          fileName: payload.fileName,
          content: payload.content,
          base64Content: payload.base64Content ?? payload.content,
          templateId: payload.templateId ?? null,
          dryRun: Boolean(payload.dryRun),
          masterDuplicateRule: payload.masterDuplicateRule ?? 'MODIFY_WITH_NEW_DATA',
          overwriteVoucherByGuid: Boolean(payload.overwriteVoucherByGuid),
          exceptionBehavior: payload.exceptionBehavior ?? 'STOP_AT_FIRST_EXCEPTION',
        },
        { withCredentials: true },
      ),
    );
    return normalizeImportDataResult(raw, 'EXCEL');
  }

  async listImportExceptions(companyId: string): Promise<readonly ImportExceptionItemDto[]> {
    return firstValueFrom(
      this.http.get<readonly ImportExceptionItemDto[]>(
        this.companyBase(companyId, '/exchange/exceptions'),
        { withCredentials: true },
      ),
    );
  }

  async resolveImportException(
    companyId: string,
    exceptionId: string,
    payload: ResolveImportExceptionRequest,
  ): Promise<ImportExceptionItemDto> {
    return firstValueFrom(
      this.http.post<ImportExceptionItemDto>(
        this.companyBase(
          companyId,
          `/exchange/exceptions/${encodeURIComponent(exceptionId)}/resolve`,
        ),
        payload,
        { withCredentials: true },
      ),
    );
  }

  async listSyncRules(companyId: string): Promise<readonly SyncRuleDto[]> {
    return firstValueFrom(
      this.http.get<readonly SyncRuleDto[]>(this.companyBase(companyId, '/exchange/sync-rules'), {
        withCredentials: true,
      }),
    );
  }

  async createSyncRule(companyId: string, payload: SyncRuleRequest): Promise<SyncRuleDto> {
    return firstValueFrom(
      this.http.post<SyncRuleDto>(this.companyBase(companyId, '/exchange/sync-rules'), payload, {
        withCredentials: true,
      }),
    );
  }

  async executeSyncRule(companyId: string, ruleId: string): Promise<SyncSessionResultDto> {
    return firstValueFrom(
      this.http.post<SyncSessionResultDto>(
        this.companyBase(companyId, `/exchange/sync-rules/${encodeURIComponent(ruleId)}/execute`),
        {},
        { withCredentials: true },
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Increment X — Guided Multi-Format Columnar Spreadsheet Importer (`SHEET-001..008`)
  // ---------------------------------------------------------------------------

  async listSpreadsheetTemplates(companyId: string): Promise<readonly SpreadsheetTemplateDto[]> {
    return firstValueFrom(
      this.http.get<readonly SpreadsheetTemplateDto[]>(
        this.companyBase(companyId, '/spreadsheet-imports/templates'),
        { withCredentials: true },
      ),
    );
  }

  async saveSpreadsheetTemplate(
    companyId: string,
    payload: SaveSpreadsheetTemplateRequest,
  ): Promise<SpreadsheetTemplateDto> {
    return firstValueFrom(
      this.http.post<SpreadsheetTemplateDto>(
        this.companyBase(companyId, '/spreadsheet-imports/templates'),
        payload,
        { withCredentials: true },
      ),
    );
  }

  async listSpreadsheetBatches(companyId: string): Promise<readonly SpreadsheetBatchDto[]> {
    return firstValueFrom(
      this.http.get<readonly SpreadsheetBatchDto[]>(
        this.companyBase(companyId, '/spreadsheet-imports/batches'),
        { withCredentials: true },
      ),
    );
  }

  async getSpreadsheetBatch(companyId: string, batchId: string): Promise<SpreadsheetBatchDto> {
    return firstValueFrom(
      this.http.get<SpreadsheetBatchDto>(
        this.companyBase(companyId, `/spreadsheet-imports/batches/${encodeURIComponent(batchId)}`),
        { withCredentials: true },
      ),
    );
  }

  async prepareSpreadsheetBatch(
    companyId: string,
    payload: PrepareSpreadsheetBatchRequest,
  ): Promise<SpreadsheetBatchDto> {
    return firstValueFrom(
      this.http.post<SpreadsheetBatchDto>(
        this.companyBase(companyId, '/spreadsheet-imports/prepare'),
        payload,
        { withCredentials: true },
      ),
    );
  }

  async remapSpreadsheetBatch(
    companyId: string,
    batchId: string,
    payload: RemapSpreadsheetBatchRequest,
  ): Promise<SpreadsheetBatchDto> {
    return firstValueFrom(
      this.http.post<SpreadsheetBatchDto>(
        this.companyBase(
          companyId,
          `/spreadsheet-imports/batches/${encodeURIComponent(batchId)}/remap`,
        ),
        payload,
        { withCredentials: true },
      ),
    );
  }

  async executeOrResumeSpreadsheetBatch(
    companyId: string,
    batchId: string,
    resume = false,
    payload?: ExecuteSpreadsheetBatchRequest,
  ): Promise<SpreadsheetBatchDto> {
    const endpoint = resume ? 'resume' : 'execute';
    return firstValueFrom(
      this.http.post<SpreadsheetBatchDto>(
        this.companyBase(
          companyId,
          `/spreadsheet-imports/batches/${encodeURIComponent(batchId)}/${endpoint}`,
        ),
        payload ?? { allowValidUnitsOnly: true },
        { withCredentials: true },
      ),
    );
  }

  async cancelSpreadsheetBatch(companyId: string, batchId: string): Promise<SpreadsheetBatchDto> {
    return firstValueFrom(
      this.http.post<SpreadsheetBatchDto>(
        this.companyBase(
          companyId,
          `/spreadsheet-imports/batches/${encodeURIComponent(batchId)}/cancel`,
        ),
        {},
        { withCredentials: true },
      ),
    );
  }

  async downloadSpreadsheetErrorsCsv(companyId: string, batchId: string): Promise<string> {
    const csvText = await firstValueFrom(
      this.http.get(
        this.companyBase(
          companyId,
          `/spreadsheet-imports/batches/${encodeURIComponent(batchId)}/errors.csv`,
        ),
        { responseType: 'text', withCredentials: true },
      ),
    );
    const blob = new Blob([csvText], { type: 'text/csv;charset=utf-8' });
    this.triggerDownload(blob, `spreadsheet-import-errors-${batchId}.csv`);
    return csvText;
  }

  triggerTextDownload(result: ExportDataResultDto): void {
    const blob = new Blob([result.content], { type: result.mimeType });
    this.triggerDownload(blob, result.fileName);
  }

  async exportMultiAccountReport(
    companyId: string,
    request?: MultiAccountReportExportRequest,
  ): Promise<MultiAccountReportExportResponse> {
    return firstValueFrom(
      this.http.post<MultiAccountReportExportResponse>(
        this.companyBase(companyId, '/exports/multi-account-report'),
        request ?? {},
        { withCredentials: true },
      ),
    );
  }

  async getEditLogSummary(
    companyId: string,
    entityType: 'ALL' | 'VOUCHER' | 'LEDGER' | 'STOCK_ITEM' = 'ALL',
    onlyAltered = false,
    username?: string | null,
  ): Promise<EditLogSummaryResponseDto> {
    const cleanUser = username?.trim() ?? '';
    const userParam = cleanUser ? `&username=${encodeURIComponent(cleanUser)}` : '';
    return firstValueFrom(
      this.http.get<EditLogSummaryResponseDto>(
        this.companyBase(
          companyId,
          `/exchange/edit-log-summary?entityType=${encodeURIComponent(entityType)}&onlyAltered=${onlyAltered}${userParam}`,
        ),
        { withCredentials: true },
      ),
    );
  }

  async exportDashboardTiles(
    companyId: string,
    request?: DashboardTilesExportRequest,
  ): Promise<DashboardTilesExportResponse> {
    return firstValueFrom(
      this.http.post<DashboardTilesExportResponse>(
        this.companyBase(companyId, '/exports/dashboard-tiles'),
        request ?? {},
        { withCredentials: true },
      ),
    );
  }

  async verifyCompanyData(companyId: string): Promise<DataVerificationReportDto> {
    return firstValueFrom(
      this.http.get<DataVerificationReportDto>(
        this.companyBase(companyId, '/exchange/data-verification'),
        { withCredentials: true },
      ),
    );
  }

  private normalizeExportResult(
    raw: RawRecord | string,
    format: ExchangeFormat,
    defaultFileName: string,
    defaultMimeType: string,
  ): ExportDataResultDto {
    if (typeof raw === 'string') {
      return {
        format,
        fileName: defaultFileName,
        mimeType: defaultMimeType,
        content: raw,
        recordCount: 1,
        sha256Hex: null,
      };
    }
    const content =
      typeof raw['content'] === 'string'
        ? raw['content']
        : typeof raw['xml'] === 'string'
          ? raw['xml']
          : typeof raw['json'] === 'string'
            ? raw['json']
            : JSON.stringify(raw, null, 2);

    return {
      format,
      fileName: String(raw['fileName'] ?? defaultFileName),
      mimeType: String(raw['mimeType'] ?? defaultMimeType),
      content,
      recordCount: intVal(raw['recordCount'] ?? raw['count'], 1),
      sha256Hex: raw['sha256Hex'] ? String(raw['sha256Hex']) : null,
    };
  }

  private triggerDownload(blob: Blob, filename: string): void {
    this.lastDownloadedExportFile.set(filename);
    const isJsdom = typeof navigator !== 'undefined' && /jsdom/i.test(navigator.userAgent);
    if (
      isJsdom ||
      typeof document === 'undefined' ||
      typeof URL === 'undefined' ||
      typeof URL.createObjectURL !== 'function'
    ) {
      return;
    }
    const objectUrl = URL.createObjectURL(blob);
    try {
      const anchor = document.createElement('a');
      anchor.href = objectUrl;
      anchor.download = filename;
      anchor.rel = 'noopener';
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
    } finally {
      setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
    }
  }
}
