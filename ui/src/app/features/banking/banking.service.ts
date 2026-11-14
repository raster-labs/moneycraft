import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RuntimeConfigService } from '../../core/http/runtime-config.service';
import { ExactDecimal } from '../../core/money/exact-decimal';
import { CursorPage } from '../masters/masters.models';
import {
  ApproveConvertOcrRequest,
  AutoMatchBrsRequest,
  AutoMatchBrsResponse,
  BankStatementImportResponse,
  BrsAllocationRowDto,
  BrsMatchRowDto,
  BrsStatementLineDto,
  BrsSummaryDto,
  ChequeBookDto,
  ChequeLeafDto,
  CreateChequeBookRequest,
  CreateOcrStagedDocRequest,
  ImportBankStatementRequest,
  ManualReconcileRequest,
  OcrStagedDocumentDto,
} from './banking.models';

function unwrapList<T>(raw: CursorPage<T> | readonly T[] | null | undefined): readonly T[] {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw;
  const page = raw as CursorPage<T>;
  return Array.isArray(page.items) ? page.items : [];
}

function safeWire(val: unknown): string {
  if (typeof val === 'string' && val.trim().length > 0) {
    const parsed = ExactDecimal.tryParse(val, 6, 'HALF_UP');
    return parsed ? parsed.rescale(2, 'HALF_UP').toWireString(2) : val.trim();
  }
  if (typeof val === 'number' && Number.isFinite(val)) {
    const parsed = ExactDecimal.tryParse(String(val), 6, 'HALF_UP');
    return parsed ? parsed.rescale(2, 'HALF_UP').toWireString(2) : '0.00';
  }
  return '0.00';
}

function normalizeBrsSummary(raw: Record<string, unknown>, fallbackBankLedgerId: string): BrsSummaryDto {
  const unrec: BrsAllocationRowDto[] = (
    Array.isArray(raw['unreconciledAllocations'])
      ? (raw['unreconciledAllocations'] as Record<string, unknown>[])
      : []
  ).map((u, i) => ({
    bankAllocationId: String(u['bankAllocationId'] ?? u['id'] ?? `alloc-${i}`),
    voucherId: u['voucherId'] ? String(u['voucherId']) : null,
    voucherNumber: String(u['voucherNumber'] ?? '1'),
    voucherTypeName: String(u['voucherTypeName'] ?? 'Payment'),
    voucherDate: String(u['voucherDate'] ?? ''),
    transactionType: String(u['transactionType'] ?? 'CHEQUE'),
    instrumentNo: u['instrumentNo'] ? String(u['instrumentNo']) : null,
    instrumentDate: u['instrumentDate'] ? String(u['instrumentDate']) : null,
    partyOrNarration: String(u['partyOrNarration'] ?? u['partyName'] ?? u['narration'] ?? ''),
    debitAmount: safeWire(u['debitAmount']),
    creditAmount: safeWire(u['creditAmount']),
    bankersDate: u['bankersDate'] ? String(u['bankersDate']) : null,
  }));

  const unmatched: BrsStatementLineDto[] = (
    Array.isArray(raw['unmatchedStatementLines'])
      ? (raw['unmatchedStatementLines'] as Record<string, unknown>[])
      : []
  ).map((s, i) => ({
    id: String(s['id'] ?? s['statementLineId'] ?? `stmt-${i}`),
    valueDate: String(s['valueDate'] ?? s['transactionDate'] ?? ''),
    description: String(s['description'] ?? s['narration'] ?? ''),
    instrumentNo: s['instrumentNo'] ? String(s['instrumentNo']) : null,
    debitAmount: safeWire(s['debitAmount'] ?? s['withdrawalAmount']),
    creditAmount: safeWire(s['creditAmount'] ?? s['depositAmount']),
    runningBalance: s['runningBalance'] !== undefined ? safeWire(s['runningBalance']) : null,
  }));

  const matches: BrsMatchRowDto[] = (
    Array.isArray(raw['matches']) ? (raw['matches'] as Record<string, unknown>[]) : []
  ).map((m) => ({
    bankAllocationId: String(m['bankAllocationId'] ?? ''),
    statementLineId: String(m['statementLineId'] ?? ''),
    voucherNumber: String(m['voucherNumber'] ?? ''),
    instrumentNo: m['instrumentNo'] ? String(m['instrumentNo']) : null,
    amount: safeWire(m['amount']),
    bankersDate: String(m['bankersDate'] ?? ''),
    matchConfidence: String(m['matchConfidence'] ?? 'EXACT'),
  }));

  return {
    bankLedgerId: String(raw['bankLedgerId'] ?? fallbackBankLedgerId),
    bankLedgerName: String(raw['bankLedgerName'] ?? 'Bank Account'),
    balanceAsPerCompanyBooks: safeWire(raw['balanceAsPerCompanyBooks']),
    amountsNotReflectedInBankDr: safeWire(raw['amountsNotReflectedInBankDr']),
    amountsNotReflectedInBankCr: safeWire(raw['amountsNotReflectedInBankCr']),
    balanceAsPerBankComputed: safeWire(raw['balanceAsPerBankComputed']),
    statementClosingBalance: safeWire(raw['statementClosingBalance']),
    unreconciledAllocations: unrec,
    unmatchedStatementLines: unmatched,
    matches,
  };
}

function normalizeOcrDoc(raw: Record<string, unknown>, idx: number): OcrStagedDocumentDto {
  const statusRaw = String(raw['reviewStatus'] ?? raw['status'] ?? 'PENDING_REVIEW');
  const reviewStatus: OcrStagedDocumentDto['reviewStatus'] =
    statusRaw === 'APPROVED_CONVERTED' || statusRaw === 'CONVERTED'
      ? 'APPROVED_CONVERTED'
      : statusRaw === 'REJECTED'
        ? 'REJECTED'
        : 'PENDING_REVIEW';

  const docTypeRaw = String(raw['documentType'] ?? 'PURCHASE_INVOICE');
  const documentType: OcrStagedDocumentDto['documentType'] =
    docTypeRaw === 'EXPENSE_RECEIPT' || docTypeRaw === 'SALES_INVOICE'
      ? docTypeRaw
      : 'PURCHASE_INVOICE';

  return {
    id: String(raw['id'] ?? `ocr-${idx}`),
    fileName: String(raw['fileName'] ?? 'invoice.pdf'),
    documentType,
    supplierName: String(raw['supplierName'] ?? raw['partyName'] ?? ''),
    supplierGstin: raw['supplierGstin'] ? String(raw['supplierGstin']) : null,
    invoiceNumber: String(raw['invoiceNumber'] ?? ''),
    invoiceDate: String(raw['invoiceDate'] ?? ''),
    taxableAmount: safeWire(raw['taxableAmount']),
    taxAmount: safeWire(raw['taxAmount']),
    totalAmount: safeWire(raw['totalAmount']),
    confidencePct: safeWire(raw['confidencePct'] ?? '98.50'),
    reviewStatus,
    convertedVoucherId: raw['convertedVoucherId'] ? String(raw['convertedVoucherId']) : null,
  };
}

@Injectable({ providedIn: 'root' })
export class BankingService {
  private readonly http = inject(HttpClient);
  private readonly runtimeConfig = inject(RuntimeConfigService);

  private companyUrl(companyId: string, suffix: string): string {
    return this.runtimeConfig.url(`/companies/${encodeURIComponent(companyId)}${suffix}`);
  }

  async listChequeBooks(companyId: string): Promise<readonly ChequeBookDto[]> {
    const body = await firstValueFrom(
      this.http.get<CursorPage<ChequeBookDto> | readonly ChequeBookDto[]>(
        this.companyUrl(companyId, '/banking/cheque-books'),
        { withCredentials: true },
      ),
    );
    return unwrapList(body);
  }

  async createChequeBook(
    companyId: string,
    payload: CreateChequeBookRequest,
  ): Promise<ChequeBookDto> {
    return firstValueFrom(
      this.http.post<ChequeBookDto>(this.companyUrl(companyId, '/banking/cheque-books'), payload, {
        withCredentials: true,
      }),
    );
  }

  async listChequeLeaves(companyId: string, bookId: string): Promise<readonly ChequeLeafDto[]> {
    const body = await firstValueFrom(
      this.http.get<CursorPage<ChequeLeafDto> | readonly ChequeLeafDto[]>(
        this.companyUrl(companyId, `/banking/cheque-books/${encodeURIComponent(bookId)}/leaves`),
        { withCredentials: true },
      ),
    );
    return unwrapList(body);
  }

  async importBankStatement(
    companyId: string,
    payload: ImportBankStatementRequest,
  ): Promise<BankStatementImportResponse> {
    const raw = await firstValueFrom(
      this.http.post<Record<string, unknown>>(
        this.companyUrl(companyId, '/banking/statements/import'),
        payload,
        { withCredentials: true },
      ),
    );
    return {
      statementImportId: String(raw['statementImportId'] ?? raw['id'] ?? ''),
      bankLedgerId: String(raw['bankLedgerId'] ?? payload.bankLedgerId),
      fileName: String(raw['fileName'] ?? payload.fileName),
      sourceFormat: payload.sourceFormat,
      importedLinesCount: Number(raw['importedLinesCount'] ?? raw['lineCount'] ?? 0),
      closingBalance:
        raw['closingBalance'] !== undefined && raw['closingBalance'] !== null
          ? safeWire(raw['closingBalance'])
          : null,
    };
  }

  async getBrsSummary(
    companyId: string,
    bankLedgerId: string,
    asOfDate: string,
  ): Promise<BrsSummaryDto> {
    const params = new HttpParams().set('bankLedgerId', bankLedgerId).set('asOfDate', asOfDate);
    const raw = await firstValueFrom(
      this.http.get<Record<string, unknown>>(this.companyUrl(companyId, '/banking/brs'), {
        params,
        withCredentials: true,
      }),
    );
    return normalizeBrsSummary(raw, bankLedgerId);
  }

  async autoMatchBrs(
    companyId: string,
    payload: AutoMatchBrsRequest,
  ): Promise<AutoMatchBrsResponse> {
    const raw = await firstValueFrom(
      this.http.post<Record<string, unknown>>(
        this.companyUrl(companyId, '/banking/brs/auto-match'),
        payload,
        { withCredentials: true },
      ),
    );
    return {
      matchedCount: Number(raw['matchedCount'] ?? 0),
      remainingUnreconciledCount: Number(raw['remainingUnreconciledCount'] ?? 0),
      summary: raw['summary']
        ? normalizeBrsSummary(raw['summary'] as Record<string, unknown>, payload.bankLedgerId)
        : null,
    };
  }

  async reconcileAllocation(
    companyId: string,
    payload: ManualReconcileRequest,
  ): Promise<Record<string, unknown>> {
    return firstValueFrom(
      this.http.put<Record<string, unknown>>(
        this.companyUrl(companyId, '/banking/brs/reconcile'),
        payload,
        { withCredentials: true },
      ),
    );
  }

  async listOcrDocuments(companyId: string): Promise<readonly OcrStagedDocumentDto[]> {
    const body = await firstValueFrom(
      this.http.get<CursorPage<Record<string, unknown>> | readonly Record<string, unknown>[]>(
        this.companyUrl(companyId, '/banking/ocr-staging/documents'),
        { withCredentials: true },
      ),
    );
    return unwrapList(body).map(normalizeOcrDoc);
  }

  async createOcrDocument(
    companyId: string,
    payload: CreateOcrStagedDocRequest,
  ): Promise<OcrStagedDocumentDto> {
    const raw = await firstValueFrom(
      this.http.post<Record<string, unknown>>(
        this.companyUrl(companyId, '/banking/ocr-staging/documents'),
        payload,
        { withCredentials: true },
      ),
    );
    return normalizeOcrDoc(raw, 0);
  }

  async approveConvertOcrDocument(
    companyId: string,
    docId: string,
    payload: ApproveConvertOcrRequest,
  ): Promise<{ readonly convertedVoucherId: string; readonly status: string }> {
    const raw = await firstValueFrom(
      this.http.post<Record<string, unknown>>(
        this.companyUrl(
          companyId,
          `/banking/ocr-staging/documents/${encodeURIComponent(docId)}/approve-convert`,
        ),
        payload,
        { withCredentials: true },
      ),
    );
    return {
      convertedVoucherId: String(raw['convertedVoucherId'] ?? raw['voucherId'] ?? ''),
      status: String(raw['status'] ?? 'APPROVED_CONVERTED'),
    };
  }
}
