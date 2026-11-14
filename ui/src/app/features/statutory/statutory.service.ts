import { DOCUMENT } from '@angular/common';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RuntimeConfigService } from '../../core/http/runtime-config.service';
import { ExactDecimal } from '../../core/money/exact-decimal';
import { CursorPage } from '../masters/masters.models';
import { UpsertVoucherRequest } from '../vouchers/vouchers.models';
import {
  AcceptGstConflictRequest,
  CreateTdsChallanRequest,
  EditLogChangedFieldDto,
  EditLogEntityType,
  EditLogVersionDto,
  EinvoiceCancelRequest,
  EinvoiceGenerateResponse,
  EwayBillGenerateRequest,
  EwayBillGenerateResponse,
  GstComputePreviewResponse,
  GstRegistrationMaster,
  Gstr1ReportDto,
  Gstr3bReportDto,
  Gstr3bTaxBucketDto,
  Gstr9ExportFormat,
  Gstr9ReportDto,
  ImsActionRequest,
  ImsActionType,
  ImsImportPayload,
  ImsInvoiceDto,
  ImsMatchStatus,
  StatutoryRulePackDto,
  TdsChallanDto,
  TdsForm26qReportDto,
  TdsNatureDto,
  TdsQuarter,
  UncertainTransactionDto,
  UpsertGstRegistrationRequest,
} from './statutory.models';

function ifMatchHeader(versionNo?: number): Readonly<Record<string, string>> {
  return versionNo !== undefined ? { 'If-Match': `"v${versionNo}"` } : {};
}

function unwrapList<T>(body: CursorPage<T> | readonly T[] | null | undefined): readonly T[] {
  if (!body) {
    return [];
  }
  if (Array.isArray(body)) {
    return body as readonly T[];
  }
  const page = body as CursorPage<T>;
  return Array.isArray(page.items) ? page.items : [];
}

function safeDecimalWire(val: unknown, fallback = '0.00'): string {
  if (typeof val === 'string' && val.trim().length > 0) {
    return val.trim();
  }
  if (typeof val === 'number' && Number.isFinite(val)) {
    return ExactDecimal.parse(String(val), 2, 'HALF_UP').toWireString(2);
  }
  return fallback;
}

function normalizeTaxBucket(raw: unknown): Gstr3bTaxBucketDto {
  const rec = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
  const igstAmount = safeDecimalWire(rec['igstAmount'] ?? rec['igst']);
  const cgstAmount = safeDecimalWire(rec['cgstAmount'] ?? rec['cgst']);
  const sgstAmount = safeDecimalWire(rec['sgstAmount'] ?? rec['sgst']);
  const cessAmount = safeDecimalWire(rec['cessAmount'] ?? rec['cess']);
  const explicitTotal = rec['totalTax'] ?? rec['total'];
  const totalTax =
    explicitTotal !== undefined && explicitTotal !== null
      ? safeDecimalWire(explicitTotal)
      : ExactDecimal.sum([igstAmount, cgstAmount, sgstAmount, cessAmount], 2).toWireString(2);
  const taxableRaw = rec['taxableValue'] ?? rec['assessableValue'];
  return {
    taxableValue: taxableRaw !== undefined && taxableRaw !== null ? safeDecimalWire(taxableRaw) : undefined,
    igstAmount,
    cgstAmount,
    sgstAmount,
    cessAmount,
    totalTax,
  };
}

function normalizeImsInvoice(raw: Record<string, unknown>): ImsInvoiceDto {
  const igstAmount = safeDecimalWire(raw['igstAmount']);
  const cgstAmount = safeDecimalWire(raw['cgstAmount']);
  const sgstAmount = safeDecimalWire(raw['sgstAmount']);
  const cessAmount = safeDecimalWire(raw['cessAmount']);
  const portalItcRaw = raw['portalItcAmount'] ?? raw['totalItcAmount'] ?? raw['totalTaxAmount'];
  const portalItcAmount =
    portalItcRaw !== undefined && portalItcRaw !== null
      ? safeDecimalWire(portalItcRaw)
      : ExactDecimal.sum([igstAmount, cgstAmount, sgstAmount, cessAmount], 2).toWireString(2);

  const rawStatus = String(
    raw['imsStatus'] ?? raw['action'] ?? raw['status'] ?? 'NO_ACTION',
  ).toUpperCase() as ImsActionType;

  const rawMatch = String(raw['matchStatus'] ?? 'EXACT_MATCH').toUpperCase() as ImsMatchStatus;

  return {
    id: String(raw['id'] ?? raw['imsInvoiceId'] ?? ''),
    supplierGstin: String(raw['supplierGstin'] ?? ''),
    supplierName: String(raw['supplierName'] ?? ''),
    invoiceNumber: String(raw['invoiceNumber'] ?? ''),
    invoiceDate: String(raw['invoiceDate'] ?? ''),
    returnPeriod: String(raw['returnPeriod'] ?? ''),
    invoiceValue: safeDecimalWire(raw['invoiceValue']),
    taxableValue: safeDecimalWire(raw['taxableValue']),
    igstAmount,
    cgstAmount,
    sgstAmount,
    cessAmount,
    portalItcAmount,
    booksVoucherId: (raw['booksVoucherId'] as string | null | undefined) ?? null,
    booksInvoiceValue:
      raw['booksInvoiceValue'] !== undefined && raw['booksInvoiceValue'] !== null
        ? safeDecimalWire(raw['booksInvoiceValue'])
        : null,
    booksTaxAmount:
      raw['booksTaxAmount'] !== undefined && raw['booksTaxAmount'] !== null
        ? safeDecimalWire(raw['booksTaxAmount'])
        : null,
    matchStatus: rawMatch,
    imsStatus: rawStatus,
    itcReductionAmount: safeDecimalWire(raw['itcReductionAmount']),
    remarks: (raw['remarks'] as string | null | undefined) ?? null,
    effectiveFilingPeriod: (raw['effectiveFilingPeriod'] as string | null | undefined) ?? null,
  };
}

function normalizeEditLogVersion(raw: Record<string, unknown>): EditLogVersionDto {
  const rawDiffs = Array.isArray(raw['changedFields']) ? raw['changedFields'] : [];
  const changedFields: EditLogChangedFieldDto[] = rawDiffs.map((item) => {
    const entry = (item && typeof item === 'object' ? item : {}) as Record<string, unknown>;
    return {
      fieldPath: String(entry['fieldPath'] ?? entry['field'] ?? ''),
      beforeValue: entry['beforeValue'] !== undefined ? entry['beforeValue'] : entry['oldValue'],
      afterValue: entry['afterValue'] !== undefined ? entry['afterValue'] : entry['newValue'],
    };
  });

  let canonicalSnapshot: Record<string, unknown> | string = {};
  const snapRaw = raw['canonicalSnapshot'];
  if (typeof snapRaw === 'string') {
    try {
      const parsed: unknown = JSON.parse(snapRaw);
      canonicalSnapshot =
        parsed && typeof parsed === 'object' && !Array.isArray(parsed)
          ? (parsed as Record<string, unknown>)
          : snapRaw;
    } catch {
      canonicalSnapshot = snapRaw;
    }
  } else if (snapRaw && typeof snapRaw === 'object') {
    canonicalSnapshot = snapRaw as Record<string, unknown>;
  }

  return {
    id: raw['id'] ? String(raw['id']) : undefined,
    versionNo: Number(raw['versionNo'] ?? 1),
    masterId: Number(raw['masterId'] ?? 0),
    alterId: Number(raw['alterId'] ?? 0),
    operation: String(raw['operation'] ?? 'CREATE'),
    actorPrincipalId: (raw['actorPrincipalId'] as string | null | undefined) ?? null,
    actorUsername: String(raw['actorUsername'] ?? 'system'),
    occurredAt: String(raw['occurredAt'] ?? ''),
    voucherNumberSnapshot: (raw['voucherNumberSnapshot'] as string | null | undefined) ?? null,
    voucherDateSnapshot: (raw['voucherDateSnapshot'] as string | null | undefined) ?? null,
    canonicalSnapshot,
    changedFields,
    snapshotSha256Hex: String(raw['snapshotSha256Hex'] ?? raw['snapshotSha256'] ?? ''),
  };
}

@Injectable({ providedIn: 'root' })
export class StatutoryService {
  private readonly http = inject(HttpClient);
  private readonly runtimeConfig = inject(RuntimeConfigService);
  private readonly document = inject(DOCUMENT);

  /** Tracks the last triggered client download for deterministic unit-test inspection. */
  lastDownloadedFilename: string | null = null;

  private companyUrl(companyId: string, suffix: string): string {
    return this.runtimeConfig.url(`/companies/${encodeURIComponent(companyId)}${suffix}`);
  }

  triggerBlobDownload(blob: Blob, filename: string): void {
    this.lastDownloadedFilename = filename;
    if (
      typeof URL === 'undefined' ||
      typeof URL.createObjectURL !== 'function' ||
      !this.document?.body ||
      (typeof navigator !== 'undefined' && /jsdom/i.test(navigator.userAgent))
    ) {
      return;
    }
    const objectUrl = URL.createObjectURL(blob);
    try {
      const anchor = this.document.createElement('a');
      anchor.href = objectUrl;
      anchor.download = filename;
      anchor.rel = 'noopener';
      this.document.body.appendChild(anchor);
      anchor.click();
      this.document.body.removeChild(anchor);
    } finally {
      if (typeof URL.revokeObjectURL === 'function') {
        URL.revokeObjectURL(objectUrl);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // 1. Multi-GSTIN & Statutory Rule Packs
  // ---------------------------------------------------------------------------

  async listGstRegistrations(companyId: string): Promise<readonly GstRegistrationMaster[]> {
    const body = await firstValueFrom(
      this.http.get<CursorPage<GstRegistrationMaster> | readonly GstRegistrationMaster[]>(
        this.companyUrl(companyId, '/gst/registrations'),
        { withCredentials: true },
      ),
    );
    return unwrapList(body);
  }

  async getGstRegistration(
    companyId: string,
    registrationId: string,
  ): Promise<GstRegistrationMaster> {
    return firstValueFrom(
      this.http.get<GstRegistrationMaster>(
        this.companyUrl(companyId, `/gst/registrations/${encodeURIComponent(registrationId)}`),
        { withCredentials: true },
      ),
    );
  }

  async createGstRegistration(
    companyId: string,
    payload: UpsertGstRegistrationRequest,
  ): Promise<GstRegistrationMaster> {
    return firstValueFrom(
      this.http.post<GstRegistrationMaster>(
        this.companyUrl(companyId, '/gst/registrations'),
        payload,
        { withCredentials: true },
      ),
    );
  }

  async updateGstRegistration(
    companyId: string,
    registrationId: string,
    payload: UpsertGstRegistrationRequest,
    versionNo?: number,
  ): Promise<GstRegistrationMaster> {
    return firstValueFrom(
      this.http.put<GstRegistrationMaster>(
        this.companyUrl(companyId, `/gst/registrations/${encodeURIComponent(registrationId)}`),
        payload,
        { headers: ifMatchHeader(versionNo ?? payload.versionNo), withCredentials: true },
      ),
    );
  }

  async listRulePacks(
    companyId: string,
    asOfDate?: string,
  ): Promise<readonly StatutoryRulePackDto[]> {
    let params = new HttpParams();
    if (asOfDate) {
      params = params.set('asOfDate', asOfDate);
    }
    const body = await firstValueFrom(
      this.http.get<CursorPage<StatutoryRulePackDto> | readonly StatutoryRulePackDto[]>(
        this.companyUrl(companyId, '/statutory/rule-packs'),
        { params, withCredentials: true },
      ),
    );
    return unwrapList(body);
  }

  // ---------------------------------------------------------------------------
  // 2. Live GST Compute Preview & Uncertain Transactions (`Ctrl+J`)
  // ---------------------------------------------------------------------------

  async computeGstPreview(
    companyId: string,
    payload: UpsertVoucherRequest,
  ): Promise<GstComputePreviewResponse> {
    return firstValueFrom(
      this.http.post<GstComputePreviewResponse>(
        this.companyUrl(companyId, '/gst/compute-preview'),
        payload,
        { withCredentials: true },
      ),
    );
  }

  async listUncertainTransactions(
    companyId: string,
    fromDate?: string,
    toDate?: string,
  ): Promise<readonly UncertainTransactionDto[]> {
    let params = new HttpParams();
    if (fromDate) {
      params = params.set('fromDate', fromDate);
    }
    if (toDate) {
      params = params.set('toDate', toDate);
    }
    const body = await firstValueFrom(
      this.http.get<CursorPage<UncertainTransactionDto> | readonly UncertainTransactionDto[]>(
        this.companyUrl(companyId, '/gst/uncertain-transactions'),
        { params, withCredentials: true },
      ),
    );
    return unwrapList(body);
  }

  async acceptUncertainConflict(
    companyId: string,
    voucherId: string,
    reason: string,
  ): Promise<UncertainTransactionDto | Record<string, unknown>> {
    const body: AcceptGstConflictRequest = { reason };
    return firstValueFrom(
      this.http.post<UncertainTransactionDto | Record<string, unknown>>(
        this.companyUrl(
          companyId,
          `/gst/uncertain-transactions/${encodeURIComponent(voucherId)}/accept-conflict`,
        ),
        body,
        { withCredentials: true },
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // 3. GST Returns (`GSTR-1`, `GSTR-3B`, One-Click `GSTR-9`)
  // ---------------------------------------------------------------------------

  async getGstr1(
    companyId: string,
    query: {
      readonly fromDate?: string;
      readonly toDate?: string;
      readonly gstRegistrationId?: string | null;
    } = {},
  ): Promise<Gstr1ReportDto> {
    let params = new HttpParams();
    if (query.fromDate) {
      params = params.set('fromDate', query.fromDate);
    }
    if (query.toDate) {
      params = params.set('toDate', query.toDate);
    }
    if (query.gstRegistrationId) {
      params = params.set('gstRegistrationId', query.gstRegistrationId);
    }
    const raw = await firstValueFrom(
      this.http.get<Gstr1ReportDto>(this.companyUrl(companyId, '/gst/gstr1'), {
        params,
        withCredentials: true,
      }),
    );
    return {
      ...raw,
      b2b: raw.b2b ?? [],
      b2cl: raw.b2cl ?? [],
      b2cs: raw.b2cs ?? [],
      cdnr: raw.cdnr ?? [],
      exp: raw.exp ?? [],
      hsnB2b: raw.hsnB2b ?? [],
      hsnB2c: raw.hsnB2c ?? [],
      documentSummary: raw.documentSummary ?? [],
      uncertainCount: raw.uncertainCount ?? 0,
      totalTaxableValue: safeDecimalWire(raw.totalTaxableValue),
      totalIgst: safeDecimalWire(raw.totalIgst),
      totalCgst: safeDecimalWire(raw.totalCgst),
      totalSgst: safeDecimalWire(raw.totalSgst),
      totalCess: safeDecimalWire(raw.totalCess),
      totalTax: safeDecimalWire(raw.totalTax),
    };
  }

  async exportGstr1(
    companyId: string,
    query: {
      readonly fromDate?: string;
      readonly toDate?: string;
      readonly gstRegistrationId?: string | null;
    } = {},
    autoDownload = true,
  ): Promise<Blob> {
    let params = new HttpParams();
    if (query.fromDate) {
      params = params.set('fromDate', query.fromDate);
    }
    if (query.toDate) {
      params = params.set('toDate', query.toDate);
    }
    if (query.gstRegistrationId) {
      params = params.set('gstRegistrationId', query.gstRegistrationId);
    }
    const blob = await firstValueFrom(
      this.http.post(this.companyUrl(companyId, '/gst/gstr1/export'), query, {
        params,
        responseType: 'blob',
        withCredentials: true,
      }),
    );
    if (autoDownload) {
      const periodSlug = `${query.fromDate ?? 'period'}_${query.toDate ?? 'end'}`;
      this.triggerBlobDownload(blob, `GSTR1_${periodSlug}.json`);
    }
    return blob;
  }

  async getGstr3b(
    companyId: string,
    query: {
      readonly fromDate?: string;
      readonly toDate?: string;
      readonly returnPeriod?: string;
      readonly gstRegistrationId?: string | null;
    } = {},
  ): Promise<Gstr3bReportDto> {
    let params = new HttpParams();
    if (query.fromDate) {
      params = params.set('fromDate', query.fromDate);
    }
    if (query.toDate) {
      params = params.set('toDate', query.toDate);
    }
    if (query.returnPeriod) {
      params = params.set('returnPeriod', query.returnPeriod);
    }
    if (query.gstRegistrationId) {
      params = params.set('gstRegistrationId', query.gstRegistrationId);
    }
    const raw = await firstValueFrom(
      this.http.get<Record<string, unknown>>(this.companyUrl(companyId, '/gst/gstr3b'), {
        params,
        withCredentials: true,
      }),
    );
    return {
      fromDate: (raw['fromDate'] as string | undefined) ?? query.fromDate,
      toDate: (raw['toDate'] as string | undefined) ?? query.toDate,
      returnPeriod: (raw['returnPeriod'] as string | undefined) ?? query.returnPeriod,
      gstRegistrationId:
        (raw['gstRegistrationId'] as string | null | undefined) ?? query.gstRegistrationId ?? null,
      gstin: (raw['gstin'] as string | null | undefined) ?? null,
      outwardTaxable: normalizeTaxBucket(raw['outwardTaxable']),
      inwardRcm: normalizeTaxBucket(raw['inwardRcm']),
      eligibleItc: normalizeTaxBucket(raw['eligibleItc']),
      itcReversed: normalizeTaxBucket(raw['itcReversed']),
      netTaxPayable: normalizeTaxBucket(raw['netTaxPayable']),
      imsAcceptedCount:
        raw['imsAcceptedCount'] !== undefined ? Number(raw['imsAcceptedCount']) : undefined,
      imsPendingItcDeferred:
        raw['imsPendingItcDeferred'] !== undefined
          ? safeDecimalWire(raw['imsPendingItcDeferred'])
          : undefined,
      imsItcReductionTotal:
        raw['imsItcReductionTotal'] !== undefined
          ? safeDecimalWire(raw['imsItcReductionTotal'])
          : undefined,
      uncertainCount:
        raw['uncertainCount'] !== undefined ? Number(raw['uncertainCount']) : undefined,
    };
  }

  async getGstr9(companyId: string, fiscalYearId?: string): Promise<Gstr9ReportDto> {
    let params = new HttpParams();
    if (fiscalYearId) {
      params = params.set('fiscalYearId', fiscalYearId);
    }
    const raw = await firstValueFrom(
      this.http.get<Gstr9ReportDto>(this.companyUrl(companyId, '/gst/gstr9'), {
        params,
        withCredentials: true,
      }),
    );
    return {
      ...raw,
      table4Outward: raw.table4Outward ?? [],
      table5Exempt: raw.table5Exempt ?? [],
      table6ItcAvailed: raw.table6ItcAvailed ?? [],
      table7ItcReversed: raw.table7ItcReversed ?? [],
      table8Reconciliation: raw.table8Reconciliation ?? [],
      table9TaxPaid: raw.table9TaxPaid ?? [],
      table17HsnOutward: raw.table17HsnOutward ?? [],
      table18HsnInward: raw.table18HsnInward ?? [],
      totalOutwardTaxable: safeDecimalWire(raw.totalOutwardTaxable),
      totalOutwardTax: safeDecimalWire(raw.totalOutwardTax),
      totalItcAvailed: safeDecimalWire(raw.totalItcAvailed),
      totalItcReversed: safeDecimalWire(raw.totalItcReversed),
      netTaxPayable: safeDecimalWire(raw.netTaxPayable),
      reconciliationDifference: safeDecimalWire(raw.reconciliationDifference),
    };
  }

  async exportGstr9(
    companyId: string,
    fiscalYearId: string,
    format: Gstr9ExportFormat,
    autoDownload = true,
  ): Promise<Blob> {
    let params = new HttpParams().set('format', format);
    if (fiscalYearId) {
      params = params.set('fiscalYearId', fiscalYearId);
    }
    const blob = await firstValueFrom(
      this.http.post(
        this.companyUrl(companyId, '/gst/gstr9/export'),
        { fiscalYearId, format },
        {
          params,
          responseType: 'blob',
          withCredentials: true,
        },
      ),
    );
    if (autoDownload) {
      const ext = format === 'XLSX' ? 'xlsx' : 'json';
      this.triggerBlobDownload(blob, `GSTR9_${fiscalYearId || 'annual'}.${ext}`);
    }
    return blob;
  }

  // ---------------------------------------------------------------------------
  // 4. Rel 7.2 IMS (Invoice Management System) Reconciliation
  // ---------------------------------------------------------------------------

  async importImsJson(
    companyId: string,
    payload: ImsImportPayload | Record<string, unknown>,
  ): Promise<readonly ImsInvoiceDto[]> {
    const body = await firstValueFrom(
      this.http.post<CursorPage<Record<string, unknown>> | readonly Record<string, unknown>[]>(
        this.companyUrl(companyId, '/gst/ims/import-json'),
        payload,
        { withCredentials: true },
      ),
    );
    return unwrapList(body).map((item) => normalizeImsInvoice(item));
  }

  async listImsInvoices(
    companyId: string,
    query: {
      readonly returnPeriod?: string;
      readonly gstRegistrationId?: string | null;
    } = {},
  ): Promise<readonly ImsInvoiceDto[]> {
    let params = new HttpParams();
    if (query.returnPeriod) {
      params = params.set('returnPeriod', query.returnPeriod);
    }
    if (query.gstRegistrationId) {
      params = params.set('gstRegistrationId', query.gstRegistrationId);
    }
    const body = await firstValueFrom(
      this.http.get<CursorPage<Record<string, unknown>> | readonly Record<string, unknown>[]>(
        this.companyUrl(companyId, '/gst/ims/invoices'),
        { params, withCredentials: true },
      ),
    );
    return unwrapList(body).map((item) => normalizeImsInvoice(item));
  }

  async applyImsAction(companyId: string, payload: ImsActionRequest): Promise<ImsInvoiceDto> {
    const raw = await firstValueFrom(
      this.http.post<Record<string, unknown>>(
        this.companyUrl(companyId, '/gst/ims/actions'),
        payload,
        { withCredentials: true },
      ),
    );
    return normalizeImsInvoice(raw);
  }

  // ---------------------------------------------------------------------------
  // 5. e-Invoice, e-Way Bill & TDS Form 26Q (Protean FVU 1.2)
  // ---------------------------------------------------------------------------

  async generateEinvoice(
    companyId: string,
    voucherId: string,
  ): Promise<EinvoiceGenerateResponse> {
    return firstValueFrom(
      this.http.post<EinvoiceGenerateResponse>(
        this.companyUrl(companyId, `/gst/einvoice/${encodeURIComponent(voucherId)}/generate`),
        {},
        { withCredentials: true },
      ),
    );
  }

  async cancelEinvoice(
    companyId: string,
    voucherId: string,
    payload: EinvoiceCancelRequest,
  ): Promise<EinvoiceGenerateResponse> {
    return firstValueFrom(
      this.http.post<EinvoiceGenerateResponse>(
        this.companyUrl(companyId, `/gst/einvoice/${encodeURIComponent(voucherId)}/cancel`),
        payload,
        { withCredentials: true },
      ),
    );
  }

  async exportEinvoiceOfflineJson(
    companyId: string,
    voucherIds: readonly string[] = [],
    autoDownload = true,
  ): Promise<Blob> {
    const blob = await firstValueFrom(
      this.http.post(
        this.companyUrl(companyId, '/gst/einvoice/export-offline-json'),
        { voucherIds },
        { responseType: 'blob', withCredentials: true },
      ),
    );
    if (autoDownload) {
      this.triggerBlobDownload(blob, 'einvoice_bulk_offline.json');
    }
    return blob;
  }

  async generateEwayBill(
    companyId: string,
    voucherId: string,
    payload: EwayBillGenerateRequest,
  ): Promise<EwayBillGenerateResponse> {
    return firstValueFrom(
      this.http.post<EwayBillGenerateResponse>(
        this.companyUrl(companyId, `/gst/ewaybill/${encodeURIComponent(voucherId)}/generate`),
        payload,
        { withCredentials: true },
      ),
    );
  }

  async exportEwayBillOfflineJson(
    companyId: string,
    voucherIds: readonly string[] = [],
    autoDownload = true,
  ): Promise<Blob> {
    const blob = await firstValueFrom(
      this.http.post(
        this.companyUrl(companyId, '/gst/ewaybill/export-offline-json'),
        { voucherIds },
        { responseType: 'blob', withCredentials: true },
      ),
    );
    if (autoDownload) {
      this.triggerBlobDownload(blob, 'ewaybill_bulk_offline.json');
    }
    return blob;
  }

  async listTdsNatures(companyId: string): Promise<readonly TdsNatureDto[]> {
    const body = await firstValueFrom(
      this.http.get<CursorPage<TdsNatureDto> | readonly TdsNatureDto[]>(
        this.companyUrl(companyId, '/tds/natures'),
        { withCredentials: true },
      ),
    );
    return unwrapList(body);
  }

  async createTdsChallan(
    companyId: string,
    payload: CreateTdsChallanRequest,
  ): Promise<TdsChallanDto> {
    return firstValueFrom(
      this.http.post<TdsChallanDto>(this.companyUrl(companyId, '/tds/challans'), payload, {
        withCredentials: true,
      }),
    );
  }

  async getTdsForm26q(
    companyId: string,
    quarter: TdsQuarter,
    fiscalYearId?: string,
  ): Promise<TdsForm26qReportDto> {
    let params = new HttpParams().set('quarter', quarter);
    if (fiscalYearId) {
      params = params.set('fiscalYearId', fiscalYearId);
    }
    const raw = await firstValueFrom(
      this.http.get<TdsForm26qReportDto>(this.companyUrl(companyId, '/tds/form26q'), {
        params,
        withCredentials: true,
      }),
    );
    return {
      ...raw,
      quarter: raw.quarter ?? quarter,
      fiscalYearId: raw.fiscalYearId ?? fiscalYearId ?? '',
      deductions: raw.deductions ?? [],
      challans: raw.challans ?? [],
      totalGrossAmount: safeDecimalWire(raw.totalGrossAmount),
      totalTdsDeducted: safeDecimalWire(raw.totalTdsDeducted),
      totalTdsDeposited: safeDecimalWire(raw.totalTdsDeposited),
      unpaidTdsAmount: safeDecimalWire(raw.unpaidTdsAmount),
    };
  }

  async exportTdsFvu(
    companyId: string,
    quarter: TdsQuarter,
    fiscalYearId?: string,
    autoDownload = true,
  ): Promise<Blob> {
    let params = new HttpParams().set('quarter', quarter);
    if (fiscalYearId) {
      params = params.set('fiscalYearId', fiscalYearId);
    }
    const blob = await firstValueFrom(
      this.http.post(
        this.companyUrl(companyId, '/tds/export-fvu'),
        { quarter, fiscalYearId },
        { params, responseType: 'blob', withCredentials: true },
      ),
    );
    if (autoDownload) {
      this.triggerBlobDownload(blob, `Form26Q_${quarter}_${fiscalYearId || 'FY'}.fvu`);
    }
    return blob;
  }

  // ---------------------------------------------------------------------------
  // 6. MCA Edit Log (`Alt+Q`)
  // ---------------------------------------------------------------------------

  async getEditLogVersions(
    companyId: string,
    entityType: EditLogEntityType | string,
    entityId: string,
  ): Promise<readonly EditLogVersionDto[]> {
    const body = await firstValueFrom(
      this.http.get<CursorPage<Record<string, unknown>> | readonly Record<string, unknown>[]>(
        this.companyUrl(
          companyId,
          `/edit-log/${encodeURIComponent(entityType)}/${encodeURIComponent(entityId)}/versions`,
        ),
        { withCredentials: true },
      ),
    );
    return unwrapList(body)
      .map((item) => normalizeEditLogVersion(item))
      .sort((a, b) => a.versionNo - b.versionNo);
  }
}
