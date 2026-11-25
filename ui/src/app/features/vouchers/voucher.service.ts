import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RuntimeConfigService } from '../../core/http/runtime-config.service';
import { CursorPage, VoucherBaseCategory } from '../masters/masters.models';
import {
  ListVouchersQuery,
  OpenBillDto,
  UpsertVoucherRequest,
  VoucherDetailDto,
  VoucherLifecycleStatus,
  VoucherLineResponse,
  VoucherPersistedView,
  VoucherPostingNature,
  VoucherSummaryDto,
} from './vouchers.models';

function ifMatchHeader(versionNo?: number): Readonly<Record<string, string>> {
  return versionNo !== undefined ? { 'If-Match': `"v${versionNo}"` } : {};
}

type RawVoucherSummaryWire = Partial<VoucherSummaryDto> & {
  readonly id: string;
  readonly voucherDate: string;
  readonly voucherTypeId?: string;
  readonly voucherTypeName?: string;
  readonly voucherTypeCategory?: VoucherBaseCategory;
  readonly category?: VoucherBaseCategory;
  readonly totalDebit?: string;
  readonly totalCredit?: string;
  readonly totalAmount?: string;
  readonly primaryParticulars?: string | null;
  readonly partyLedgerName?: string | null;
  readonly primaryLedgerName?: string | null;
};

function normalizeVoucherSummary(raw: RawVoucherSummaryWire): VoucherSummaryDto {
  const debitAmount = raw.debitAmount ?? raw.totalDebit ?? raw.totalAmount ?? '0.00';
  const creditAmount = raw.creditAmount ?? raw.totalCredit ?? raw.totalAmount ?? '0.00';
  const particulars =
    raw.particulars ??
    raw.primaryParticulars ??
    raw.partyLedgerName ??
    raw.primaryLedgerName ??
    raw.narration ??
    raw.voucherTypeName ??
    'Voucher Entry';

  return {
    id: raw.id,
    companyId: raw.companyId,
    voucherDate: raw.voucherDate,
    voucherTypeId: raw.voucherTypeId ?? '',
    voucherTypeName: raw.voucherTypeName ?? 'Voucher',
    voucherCategory: raw.voucherCategory ?? raw.voucherTypeCategory ?? raw.category,
    voucherNumber: raw.voucherNumber ?? '1',
    particulars,
    status: (raw.status ?? 'POSTED') as VoucherLifecycleStatus,
    postingNature: (raw.postingNature ?? 'NORMAL') as VoucherPostingNature,
    persistedView: raw.persistedView as VoucherPersistedView | undefined,
    debitAmount,
    creditAmount,
    narration: raw.narration ?? null,
    createdBy: raw.createdBy ?? null,
    approvedBy: raw.approvedBy ?? null,
    approvalPolicy: raw.approvalPolicy,
    versionNo: raw.versionNo ?? 1,
    lineBreakups: raw.lineBreakups ?? [],
  };
}

type RawVoucherDetailWire = Partial<VoucherDetailDto> & {
  readonly id: string;
  readonly voucherDate: string;
  readonly voucherTypeCategory?: VoucherBaseCategory;
  readonly category?: VoucherBaseCategory;
  readonly lines?: readonly VoucherLineResponse[];
};

function normalizeVoucherDetail(raw: RawVoucherDetailWire, fallbackCompanyId: string): VoucherDetailDto {
  return {
    id: raw.id,
    companyId: raw.companyId ?? fallbackCompanyId,
    voucherTypeId: raw.voucherTypeId ?? '',
    voucherTypeName: raw.voucherTypeName ?? 'Journal',
    voucherCategory: raw.voucherCategory ?? raw.voucherTypeCategory ?? raw.category,
    voucherClassId: raw.voucherClassId ?? null,
    voucherNumber: raw.voucherNumber ?? '1',
    voucherDate: raw.voucherDate,
    effectiveDate: raw.effectiveDate ?? raw.voucherDate,
    reversingApplicableUpto: raw.reversingApplicableUpto ?? null,
    persistedView: (raw.persistedView ?? 'AS_VOUCHER') as VoucherPersistedView,
    postingNature: (raw.postingNature ?? 'NORMAL') as VoucherPostingNature,
    status: (raw.status ?? 'POSTED') as VoucherLifecycleStatus,
    approvalPolicy: raw.approvalPolicy,
    supplierInvoiceNo: raw.supplierInvoiceNo ?? null,
    supplierInvoiceDate: raw.supplierInvoiceDate ?? null,
    partyLedgerId: raw.partyLedgerId ?? null,
    partyLedgerName: raw.partyLedgerName ?? null,
    accountingLedgerId: raw.accountingLedgerId ?? null,
    accountingLedgerName: raw.accountingLedgerName ?? null,
    placeOfSupplyState: raw.placeOfSupplyState ?? null,
    companyGstinId: raw.companyGstinId ?? null,
    partyGstin: raw.partyGstin ?? null,
    partyMailingName: raw.partyMailingName ?? null,
    partyStateCode: raw.partyStateCode ?? null,
    isReverseCharge: Boolean(raw.isReverseCharge),
    exportType: raw.exportType ?? null,
    currencyCode: raw.currencyCode ?? 'INR',
    exchangeRate: raw.exchangeRate ?? '1',
    narration: raw.narration ?? '',
    totalDebit: raw.totalDebit ?? '0.00',
    totalCredit: raw.totalCredit ?? '0.00',
    lines: raw.lines ?? [],
    inventoryLines: raw.inventoryLines ?? [],
    createdBy: raw.createdBy ?? null,
    approvedBy: raw.approvedBy ?? null,
    cancelledReason: raw.cancelledReason ?? null,
    alterId: raw.alterId,
    versionNo: raw.versionNo ?? 1,
  };
}

export interface StagedVoucherSeed {
  readonly voucherDate?: string;
  readonly voucherTypeId?: string;
  readonly voucherCategory?: VoucherBaseCategory;
}

@Injectable({ providedIn: 'root' })
export class VoucherService {
  private readonly http = inject(HttpClient);
  private readonly runtimeConfig = inject(RuntimeConfigService);

  private readonly duplicateDraftSignal = signal<VoucherDetailDto | null>(null);
  private readonly seedParamsSignal = signal<StagedVoucherSeed | null>(null);

  readonly pendingDuplicateDraft = this.duplicateDraftSignal.asReadonly();
  readonly pendingSeedParams = this.seedParamsSignal.asReadonly();

  private companyBase(companyId: string, suffix: string): string {
    return this.runtimeConfig.url(`/companies/${encodeURIComponent(companyId)}${suffix}`);
  }

  stageDuplicateDraft(source: VoucherDetailDto): void {
    this.duplicateDraftSignal.set(source);
  }

  consumeDuplicateDraft(): VoucherDetailDto | null {
    const draft = this.duplicateDraftSignal();
    this.duplicateDraftSignal.set(null);
    return draft;
  }

  stageSeedParams(seed: StagedVoucherSeed): void {
    this.seedParamsSignal.set(seed);
  }

  consumeSeedParams(): StagedVoucherSeed | null {
    const seed = this.seedParamsSignal();
    this.seedParamsSignal.set(null);
    return seed;
  }

  async getNextVoucherNumber(
    companyId: string,
    voucherTypeId: string,
    voucherDate: string,
  ): Promise<string> {
    const params = new HttpParams()
      .set('voucherTypeId', voucherTypeId)
      .set('voucherDate', voucherDate);

    const res = await firstValueFrom(
      this.http.get<{
        readonly voucherNumber?: string;
        readonly nextVoucherNumber?: string;
        readonly expectedVoucherNumber?: string;
      }>(
        this.companyBase(companyId, '/vouchers/next-number'),
        { params, withCredentials: true },
      ),
    );
    return res.voucherNumber ?? res.nextVoucherNumber ?? res.expectedVoucherNumber ?? '1';
  }

  async createVoucher(
    companyId: string,
    payload: UpsertVoucherRequest,
  ): Promise<VoucherDetailDto> {
    const raw = await firstValueFrom(
      this.http.post<RawVoucherDetailWire>(
        this.companyBase(companyId, '/vouchers'),
        payload,
        { withCredentials: true },
      ),
    );
    return normalizeVoucherDetail(raw, companyId);
  }

  async getVoucher(companyId: string, voucherId: string): Promise<VoucherDetailDto> {
    const raw = await firstValueFrom(
      this.http.get<RawVoucherDetailWire>(
        this.companyBase(companyId, `/vouchers/${encodeURIComponent(voucherId)}`),
        { withCredentials: true },
      ),
    );
    return normalizeVoucherDetail(raw, companyId);
  }

  async updateVoucher(
    companyId: string,
    voucherId: string,
    payload: UpsertVoucherRequest,
    versionNo?: number,
  ): Promise<VoucherDetailDto> {
    const raw = await firstValueFrom(
      this.http.put<RawVoucherDetailWire>(
        this.companyBase(companyId, `/vouchers/${encodeURIComponent(voucherId)}`),
        payload,
        { headers: ifMatchHeader(versionNo), withCredentials: true },
      ),
    );
    return normalizeVoucherDetail(raw, companyId);
  }

  async approveVoucher(
    companyId: string,
    voucherId: string,
    remarks?: string,
    versionNo?: number,
  ): Promise<VoucherDetailDto> {
    const raw = await firstValueFrom(
      this.http.post<RawVoucherDetailWire>(
        this.companyBase(companyId, `/vouchers/${encodeURIComponent(voucherId)}/approve`),
        remarks ? { remarks } : {},
        { headers: ifMatchHeader(versionNo), withCredentials: true },
      ),
    );
    return normalizeVoucherDetail(raw, companyId);
  }

  async postVoucher(
    companyId: string,
    voucherId: string,
    versionNo?: number,
  ): Promise<VoucherDetailDto> {
    const raw = await firstValueFrom(
      this.http.post<RawVoucherDetailWire>(
        this.companyBase(companyId, `/vouchers/${encodeURIComponent(voucherId)}/post`),
        {},
        { headers: ifMatchHeader(versionNo), withCredentials: true },
      ),
    );
    return normalizeVoucherDetail(raw, companyId);
  }

  async reverseVoucher(
    companyId: string,
    voucherId: string,
    options?: { readonly reversalDate?: string; readonly narration?: string },
    versionNo?: number,
  ): Promise<VoucherDetailDto> {
    const raw = await firstValueFrom(
      this.http.post<RawVoucherDetailWire>(
        this.companyBase(companyId, `/vouchers/${encodeURIComponent(voucherId)}/reverse`),
        options ?? {},
        { headers: ifMatchHeader(versionNo), withCredentials: true },
      ),
    );
    return normalizeVoucherDetail(raw, companyId);
  }

  async cancelVoucher(
    companyId: string,
    voucherId: string,
    reason?: string,
    versionNo?: number,
  ): Promise<VoucherDetailDto> {
    const raw = await firstValueFrom(
      this.http.post<RawVoucherDetailWire>(
        this.companyBase(companyId, `/vouchers/${encodeURIComponent(voucherId)}/cancel`),
        reason ? { reason } : {},
        { headers: ifMatchHeader(versionNo), withCredentials: true },
      ),
    );
    return normalizeVoucherDetail(raw, companyId);
  }

  async listVouchers(
    companyId: string,
    query?: ListVouchersQuery,
  ): Promise<CursorPage<VoucherSummaryDto>> {
    let params = new HttpParams();
    if (query?.fromDate) params = params.set('fromDate', query.fromDate);
    if (query?.toDate) params = params.set('toDate', query.toDate);
    if (query?.voucherTypeId) params = params.set('voucherTypeId', query.voucherTypeId);
    if (query?.voucherCategory) params = params.set('voucherCategory', query.voucherCategory);
    if (query?.ledgerId) params = params.set('ledgerId', query.ledgerId);
    if (query?.status) params = params.set('status', query.status);
    if (query?.includeOptional !== undefined) {
      params = params.set('includeOptional', String(query.includeOptional));
    }
    if (query?.includeCancelled !== undefined) {
      params = params.set('includeCancelled', String(query.includeCancelled));
    }
    if (query?.limit !== undefined) {
      params = params.set('limit', String(query.limit));
    }
    if (query?.cursor) {
      params = params.set('cursor', query.cursor);
    }

    const body = await firstValueFrom(
      this.http.get<CursorPage<RawVoucherSummaryWire> | readonly RawVoucherSummaryWire[]>(
        this.companyBase(companyId, '/vouchers'),
        { params, withCredentials: true },
      ),
    );

    if (Array.isArray(body)) {
      return {
        items: (body as readonly RawVoucherSummaryWire[]).map(normalizeVoucherSummary),
        nextCursor: null,
        hasMore: false,
      };
    }

    const page = body as CursorPage<RawVoucherSummaryWire>;
    return {
      items: (page.items ?? []).map(normalizeVoucherSummary),
      nextCursor: page.nextCursor ?? null,
      hasMore: Boolean(page.hasMore),
    };
  }

  async listOpenBills(
    companyId: string,
    ledgerId: string,
    excludeVoucherId?: string | null,
  ): Promise<readonly OpenBillDto[]> {
    let params = new HttpParams();
    if (excludeVoucherId) {
      params = params.set('excludeVoucherId', excludeVoucherId);
    }

    const body = await firstValueFrom(
      this.http.get<CursorPage<OpenBillDto> | readonly OpenBillDto[]>(
        this.companyBase(companyId, `/ledgers/${encodeURIComponent(ledgerId)}/open-bills`),
        { params, withCredentials: true },
      ),
    );

    if (Array.isArray(body)) {
      return body as readonly OpenBillDto[];
    }
    const page = body as CursorPage<OpenBillDto>;
    return Array.isArray(page.items) ? page.items : [];
  }

  async getAdjacentVoucher(
    companyId: string,
    query: {
      readonly currentVoucherId?: string | null;
      readonly voucherTypeId?: string | null;
      readonly voucherCategory?: VoucherBaseCategory | null;
      readonly voucherDate?: string | null;
      readonly direction: 'PREV' | 'NEXT';
    },
  ): Promise<VoucherDetailDto | null> {
    let params = new HttpParams().set('direction', query.direction);
    if (query.currentVoucherId) params = params.set('currentVoucherId', query.currentVoucherId);
    if (query.voucherTypeId) params = params.set('voucherTypeId', query.voucherTypeId);
    if (query.voucherCategory) params = params.set('voucherCategory', query.voucherCategory);
    if (query.voucherDate) params = params.set('voucherDate', query.voucherDate);

    try {
      const res = await firstValueFrom(
        this.http.get<
          | {
              readonly found?: boolean;
              readonly voucher?: RawVoucherDetailWire | null;
            }
          | RawVoucherDetailWire
          | null
        >(this.companyBase(companyId, '/vouchers/adjacent'), { params, withCredentials: true }),
      );
      if (!res) {
        return null;
      }
      if ('voucher' in res) {
        if (res.found === false || !res.voucher || !res.voucher.id) {
          return null;
        }
        return normalizeVoucherDetail(res.voucher, companyId);
      }
      const direct = res as RawVoucherDetailWire;
      if (!direct.id) {
        return null;
      }
      return normalizeVoucherDetail(direct, companyId);
    } catch {
      return null;
    }
  }

  async getLastNarration(
    companyId: string,
    query: {
      readonly voucherTypeId?: string | null;
      readonly voucherCategory?: VoucherBaseCategory | null;
      readonly ledgerId?: string | null;
    },
  ): Promise<string> {
    let params = new HttpParams();
    if (query.voucherTypeId) params = params.set('voucherTypeId', query.voucherTypeId);
    if (query.voucherCategory) params = params.set('voucherCategory', query.voucherCategory);
    if (query.ledgerId) params = params.set('ledgerId', query.ledgerId);

    try {
      const res = await firstValueFrom(
        this.http.get<{ readonly narration?: string | null } | string>(
          this.companyBase(companyId, '/vouchers/last-narration'),
          { params, withCredentials: true },
        ),
      );
      if (typeof res === 'string') {
        return res;
      }
      return res?.narration ?? '';
    } catch {
      return '';
    }
  }

  async listUnusedVoucherNumbers(
    companyId: string,
    voucherTypeId?: string | null,
  ): Promise<readonly string[]> {
    let params = new HttpParams();
    if (voucherTypeId) {
      params = params.set('voucherTypeId', voucherTypeId);
    }

    try {
      const res = await firstValueFrom(
        this.http.get<
          | readonly string[]
          | { readonly unusedNumbers?: readonly string[]; readonly items?: readonly string[] }
        >(this.companyBase(companyId, '/vouchers/unused-numbers'), {
          params,
          withCredentials: true,
        }),
      );
      if (Array.isArray(res)) {
        return (res as readonly string[]).map(String);
      }
      if (res && typeof res === 'object') {
        const obj = res as {
          readonly unusedNumbers?: readonly string[];
          readonly items?: readonly string[];
        };
        const list = obj.unusedNumbers ?? obj.items;
        if (Array.isArray(list)) {
          return list.map(String);
        }
      }
      return [];
    } catch {
      return [];
    }
  }
}
