import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RuntimeConfigService } from '../../core/http/runtime-config.service';
import { ExactDecimal } from '../../core/money/exact-decimal';
import {
  CreateExchangeTemplateRequest,
  ExchangeFormat,
  ExchangeMappingTemplateDto,
  ExportDataRequest,
  ExportDataResultDto,
  ImportDataRequest,
  ImportDataResultDto,
  ImportJobProofDto,
  ImportJobStatus,
  ImportJobSummaryDto,
  StageTally1800Request,
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
    jobType: String(raw['jobType'] ?? 'TALLY_1800'),
    status: (raw['status'] ?? 'STAGED') as ImportJobStatus,
    sourceCompanyName: String(
      raw['sourceCompanyName'] ?? raw['companyName'] ?? 'Migrated .1800 Company',
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
    templateName: String(raw['templateName'] ?? raw['name'] ?? 'Custom Excel Template'),
    entityScope: (raw['entityScope'] ?? raw['entityType'] ?? 'VOUCHERS') as 'MASTERS' | 'VOUCHERS',
    sheetName: String(raw['sheetName'] ?? 'Sheet1'),
    headerRowIndex: intVal(raw['headerRowIndex'], 1),
    columnMappings: cleanMappings,
    createdAt: raw['createdAt'] ? String(raw['createdAt']) : undefined,
  };
}

function normalizeImportDataResult(
  raw: RawRecord,
  fallbackFormat: ExchangeFormat,
): ImportDataResultDto {
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

  async stageTally1800(
    companyId: string,
    payload: StageTally1800Request,
  ): Promise<ImportJobProofDto> {
    const raw = await firstValueFrom(
      this.http.post<RawRecord>(this.companyBase(companyId, '/imports/tally-1800/stage'), payload, {
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
      'moneycraft-rel72-jsonex.json',
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
        },
        { withCredentials: true },
      ),
    );
    return normalizeImportDataResult(raw, 'JSONEX');
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
        },
        { withCredentials: true },
      ),
    );
    return normalizeImportDataResult(raw, 'EXCEL');
  }

  triggerTextDownload(result: ExportDataResultDto): void {
    const blob = new Blob([result.content], { type: result.mimeType });
    this.triggerDownload(blob, result.fileName);
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
