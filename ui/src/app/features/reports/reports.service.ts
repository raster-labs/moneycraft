import { DOCUMENT } from '@angular/common';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RuntimeConfigService } from '../../core/http/runtime-config.service';
import { ExactDecimal } from '../../core/money/exact-decimal';
import {
  AccountBookMonthlySummaryDto,
  AccountBookReportDto,
  AccountBookRowDto,
  AccountBookType,
  AgeingBillRowDto,
  AgeingBucketTotalsDto,
  BalanceSheetFormat,
  BalanceSheetReportDto,
  BillsAgeingReportDto,
  BillsAgeingType,
  CashFlowActivityRowDto,
  CashFlowMonthRowDto,
  CashFlowReportDto,
  ExceptionReportDto,
  ExceptionReportRowDto,
  ExceptionReportType,
  FinancialStatementLineDto,
  PrintableReportType,
  ProfitAndLossReportDto,
  RatioAnalysisReportDto,
  ScheduleIiiEntityFormat,
  ScheduleIiiLineDto,
  ScheduleIiiNoteDto,
  ScheduleIiiReportDto,
  ScheduleIiiRoundingScale,
  StatementLineKind,
  StatisticsMasterRowDto,
  StatisticsReportDto,
  StatisticsVoucherTypeRowDto,
  StockQueryGodownRowDto,
  StockQueryReportDto,
  TrialBalanceReportDto,
  TrialBalanceRowDto,
} from './reports.models';

function safeWire(value: unknown, scale = 2): string {
  if (typeof value === 'string' && value.trim().length > 0) {
    const parsed = ExactDecimal.tryParse(value, 6, 'HALF_UP');
    return parsed ? parsed.rescale(scale, 'HALF_UP').toWireString(scale) : value.trim();
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    const parsed = ExactDecimal.tryParse(String(value), 6, 'HALF_UP');
    return parsed ? parsed.rescale(scale, 'HALF_UP').toWireString(scale) : '0.00';
  }
  return ExactDecimal.zero(scale).toWireString(scale);
}

function normalizeTbRow(raw: Record<string, unknown>, defaultType: 'GROUP' | 'LEDGER'): TrialBalanceRowDto {
  const rowType =
    raw['rowType'] === 'GROUP' || raw['rowType'] === 'LEDGER'
      ? raw['rowType']
      : raw['ledgerId'] && !raw['groupId']
        ? 'LEDGER'
        : defaultType;
  const rawChildren = Array.isArray(raw['children'])
    ? raw['children']
    : Array.isArray(raw['childLedgers'])
      ? raw['childLedgers']
      : Array.isArray(raw['ledgers'])
        ? raw['ledgers']
        : [];

  return {
    id: String(raw['id'] ?? raw['groupId'] ?? raw['ledgerId'] ?? raw['name'] ?? ''),
    parentGroupId: raw['parentGroupId'] ? String(raw['parentGroupId']) : null,
    groupName: raw['groupName'] ? String(raw['groupName']) : null,
    code: raw['code'] ?? raw['aliasCode'] ? String(raw['code'] ?? raw['aliasCode']) : null,
    name: String(raw['name'] ?? raw['groupName'] ?? raw['ledgerName'] ?? raw['particulars'] ?? 'Row'),
    rowType,
    depthLevel: typeof raw['depthLevel'] === 'number' ? raw['depthLevel'] : 0,
    openingDebit: safeWire(raw['openingDebit'] ?? raw['openingDr']),
    openingCredit: safeWire(raw['openingCredit'] ?? raw['openingCr']),
    periodDebit: safeWire(raw['periodDebit'] ?? raw['periodDr'] ?? raw['debitTurnover']),
    periodCredit: safeWire(raw['periodCredit'] ?? raw['periodCr'] ?? raw['creditTurnover']),
    closingDebit: safeWire(raw['closingDebit'] ?? raw['closingDr'] ?? raw['debitAmount']),
    closingCredit: safeWire(raw['closingCredit'] ?? raw['closingCr'] ?? raw['creditAmount']),
    children: (rawChildren as readonly Record<string, unknown>[]).map((c) =>
      normalizeTbRow(c, 'LEDGER'),
    ),
  };
}

function normalizeStatementLine(raw: Record<string, unknown>): FinancialStatementLineDto {
  const rawType = String(raw['lineType'] ?? raw['rowType'] ?? raw['type'] ?? 'GROUP').toUpperCase();
  const validKinds: readonly StatementLineKind[] = [
    'GROUP',
    'LEDGER',
    'STOCK',
    'PROFIT_LOSS',
    'FOREX',
    'DIFF',
    'SUBTOTAL',
  ];
  const lineType: StatementLineKind = (validKinds as readonly string[]).includes(rawType)
    ? (rawType as StatementLineKind)
    : 'GROUP';

  const rawChildren = Array.isArray(raw['children'])
    ? raw['children']
    : Array.isArray(raw['subLines'])
      ? raw['subLines']
      : Array.isArray(raw['ledgerLines'])
        ? raw['ledgerLines']
        : [];

  return {
    id: String(
      raw['id'] ?? raw['groupId'] ?? raw['ledgerId'] ?? raw['code'] ?? raw['name'] ?? raw['particulars'] ?? '',
    ),
    lineType,
    code: raw['code'] ? String(raw['code']) : null,
    name: String(raw['name'] ?? raw['particulars'] ?? raw['groupName'] ?? raw['ledgerName'] ?? ''),
    amount: safeWire(raw['amount'] ?? raw['balance'] ?? raw['closingBalance']),
    comparativeAmount:
      raw['comparativeAmount'] !== undefined && raw['comparativeAmount'] !== null
        ? safeWire(raw['comparativeAmount'])
        : null,
    ledgerId: raw['ledgerId'] ? String(raw['ledgerId']) : lineType === 'LEDGER' && raw['id'] ? String(raw['id']) : null,
    groupId: raw['groupId'] ? String(raw['groupId']) : lineType === 'GROUP' && raw['id'] ? String(raw['id']) : null,
    children: (rawChildren as readonly Record<string, unknown>[]).map(normalizeStatementLine),
  };
}

function normalizeScheduleIiiLine(raw: Record<string, unknown>): ScheduleIiiLineDto {
  return {
    lineCode: String(raw['lineCode'] ?? raw['code'] ?? ''),
    particulars: String(raw['particulars'] ?? raw['name'] ?? raw['label'] ?? ''),
    noteNo:
      raw['noteNo'] !== undefined && raw['noteNo'] !== null && String(raw['noteNo']).length > 0
        ? String(raw['noteNo'])
        : null,
    section: raw['section'] ? String(raw['section']) : null,
    indentLevel: typeof raw['indentLevel'] === 'number' ? raw['indentLevel'] : 0,
    isSubtotal: Boolean(raw['isSubtotal'] ?? raw['subtotal'] ?? raw['isTotal']),
    currentAmount: safeWire(raw['currentAmount'] ?? raw['amount']),
    comparativeAmount: safeWire(raw['comparativeAmount'] ?? raw['priorAmount']),
  };
}

function normalizeScheduleIiiNote(raw: Record<string, unknown>, idx: number): ScheduleIiiNoteDto {
  const rawItems = Array.isArray(raw['items'])
    ? raw['items']
    : Array.isArray(raw['lines'])
      ? raw['lines']
      : [];
  return {
    noteNo: String(raw['noteNo'] ?? idx + 1),
    lineCode: String(raw['lineCode'] ?? ''),
    title: String(raw['title'] ?? raw['particulars'] ?? `Note ${idx + 1}`),
    currentTotal: safeWire(raw['currentTotal'] ?? raw['currentAmount'] ?? raw['amount']),
    comparativeTotal: safeWire(raw['comparativeTotal'] ?? raw['comparativeAmount']),
    items: (rawItems as readonly Record<string, unknown>[]).map((it, itemIdx) => ({
      id: String(it['id'] ?? it['ledgerId'] ?? `note-item-${idx}-${itemIdx}`),
      name: String(it['name'] ?? it['particulars'] ?? it['ledgerName'] ?? ''),
      currentAmount: safeWire(it['currentAmount'] ?? it['amount']),
      comparativeAmount: safeWire(it['comparativeAmount'] ?? it['priorAmount']),
    })),
  };
}

function normalizeAgeingBill(raw: Record<string, unknown>, idx: number): AgeingBillRowDto {
  const pending = safeWire(raw['pendingAmount'] ?? raw['closingBalance'] ?? raw['amount']);
  const ageDays = typeof raw['ageDays'] === 'number' ? raw['ageDays'] : Number(raw['ageDays'] ?? 0);
  const overdueDays =
    typeof raw['overdueDays'] === 'number' ? raw['overdueDays'] : Number(raw['overdueDays'] ?? ageDays);

  let bucket: '0_30' | '31_60' | '61_90' | 'GT_90' = '0_30';
  const rawBucket = String(raw['bucket'] ?? '');
  if (rawBucket === '31_60' || rawBucket === '31-60') bucket = '31_60';
  else if (rawBucket === '61_90' || rawBucket === '61-90') bucket = '61_90';
  else if (rawBucket === 'GT_90' || rawBucket === '>90' || rawBucket === '90+') bucket = 'GT_90';
  else if (rawBucket === '0_30' || rawBucket === '0-30') bucket = '0_30';
  else if (ageDays > 90) bucket = 'GT_90';
  else if (ageDays > 60) bucket = '61_90';
  else if (ageDays > 30) bucket = '31_60';

  return {
    id: String(raw['id'] ?? raw['billAllocationId'] ?? `bill-${idx}`),
    voucherId: raw['voucherId'] ? String(raw['voucherId']) : null,
    ledgerId: String(raw['ledgerId'] ?? raw['partyLedgerId'] ?? ''),
    partyName: String(raw['partyName'] ?? raw['ledgerName'] ?? ''),
    partyGstin: raw['partyGstin'] ? String(raw['partyGstin']) : null,
    billName: String(raw['billName'] ?? raw['refName'] ?? raw['billRef'] ?? ''),
    billDate: String(raw['billDate'] ?? raw['voucherDate'] ?? ''),
    dueDate: String(raw['dueDate'] ?? raw['billDate'] ?? ''),
    ageDays: Number.isFinite(ageDays) ? ageDays : 0,
    overdueDays: Number.isFinite(overdueDays) ? overdueDays : 0,
    bucket,
    originalAmount: safeWire(raw['originalAmount'] ?? pending),
    pendingAmount: pending,
    bucket0To30:
      raw['bucket0To30'] !== undefined
        ? safeWire(raw['bucket0To30'])
        : bucket === '0_30'
          ? pending
          : '0.00',
    bucket31To60:
      raw['bucket31To60'] !== undefined
        ? safeWire(raw['bucket31To60'])
        : bucket === '31_60'
          ? pending
          : '0.00',
    bucket61To90:
      raw['bucket61To90'] !== undefined
        ? safeWire(raw['bucket61To90'])
        : bucket === '61_90'
          ? pending
          : '0.00',
    bucketOver90:
      raw['bucketOver90'] !== undefined
        ? safeWire(raw['bucketOver90'])
        : bucket === 'GT_90'
          ? pending
          : '0.00',
  };
}

@Injectable({ providedIn: 'root' })
export class ReportsService {
  private readonly http = inject(HttpClient);
  private readonly runtimeConfig = inject(RuntimeConfigService);
  private readonly document = inject(DOCUMENT);

  /** Tracks the last generated PDF filename & byte length for deterministic Vitest assertions. */
  lastDownloadedPdfFilename: string | null = null;
  lastPdfBlobSize = 0;

  private companyUrl(companyId: string, suffix: string): string {
    return this.runtimeConfig.url(`/companies/${encodeURIComponent(companyId)}${suffix}`);
  }

  triggerPdfDownload(blob: Blob, filename: string): void {
    this.lastDownloadedPdfFilename = filename;
    this.lastPdfBlobSize = blob.size;
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

  async getTrialBalance(
    companyId: string,
    query: {
      readonly fromDate: string;
      readonly toDate: string;
      readonly includeOptional?: boolean;
    },
  ): Promise<TrialBalanceReportDto> {
    const params = new HttpParams()
      .set('fromDate', query.fromDate)
      .set('toDate', query.toDate)
      .set('includeOptional', String(query.includeOptional ?? false));

    const raw = await firstValueFrom(
      this.http.get<Record<string, unknown>>(this.companyUrl(companyId, '/reports/trial-balance'), {
        params,
        withCredentials: true,
      }),
    );

    const groupRows = (
      Array.isArray(raw['groupRows']) ? (raw['groupRows'] as Record<string, unknown>[]) : []
    ).map((r) => normalizeTbRow(r, 'GROUP'));
    const ledgerRows = (
      Array.isArray(raw['ledgerRows']) ? (raw['ledgerRows'] as Record<string, unknown>[]) : []
    ).map((r) => normalizeTbRow(r, 'LEDGER'));

    const closingDr = safeWire(raw['totalClosingDebit']);
    const closingCr = safeWire(raw['totalClosingCredit']);
    const diff = safeWire(raw['differenceAmount']);

    return {
      fromDate: String(raw['fromDate'] ?? query.fromDate),
      toDate: String(raw['toDate'] ?? query.toDate),
      includeOptional: Boolean(raw['includeOptional'] ?? query.includeOptional),
      groupRows,
      ledgerRows,
      unadjustedForexDebit: safeWire(raw['unadjustedForexDebit']),
      unadjustedForexCredit: safeWire(raw['unadjustedForexCredit']),
      totalOpeningDebit: safeWire(raw['totalOpeningDebit']),
      totalOpeningCredit: safeWire(raw['totalOpeningCredit']),
      totalPeriodDebit: safeWire(raw['totalPeriodDebit']),
      totalPeriodCredit: safeWire(raw['totalPeriodCredit']),
      totalClosingDebit: closingDr,
      totalClosingCredit: closingCr,
      differenceAmount: diff,
      isBalanced:
        raw['isBalanced'] !== undefined
          ? Boolean(raw['isBalanced'])
          : closingDr === closingCr && (ExactDecimal.tryParse(diff, 2)?.isZero() ?? true),
    };
  }

  async getProfitAndLoss(
    companyId: string,
    query: {
      readonly fromDate: string;
      readonly toDate: string;
      readonly includeOptional?: boolean;
    },
  ): Promise<ProfitAndLossReportDto> {
    const params = new HttpParams()
      .set('fromDate', query.fromDate)
      .set('toDate', query.toDate)
      .set('includeOptional', String(query.includeOptional ?? false));

    const raw = await firstValueFrom(
      this.http.get<Record<string, unknown>>(
        this.companyUrl(companyId, '/reports/profit-and-loss'),
        { params, withCredentials: true },
      ),
    );

    return {
      fromDate: String(raw['fromDate'] ?? query.fromDate),
      toDate: String(raw['toDate'] ?? query.toDate),
      openingStockValue: safeWire(raw['openingStockValue']),
      closingStockValue: safeWire(raw['closingStockValue']),
      salesTotal: safeWire(raw['salesTotal']),
      purchaseTotal: safeWire(raw['purchaseTotal']),
      directExpensesTotal: safeWire(raw['directExpensesTotal']),
      directIncomesTotal: safeWire(raw['directIncomesTotal']),
      indirectExpensesTotal: safeWire(raw['indirectExpensesTotal']),
      indirectIncomesTotal: safeWire(raw['indirectIncomesTotal']),
      tradingDebitLines: (
        Array.isArray(raw['tradingDebitLines'])
          ? (raw['tradingDebitLines'] as Record<string, unknown>[])
          : []
      ).map(normalizeStatementLine),
      tradingCreditLines: (
        Array.isArray(raw['tradingCreditLines'])
          ? (raw['tradingCreditLines'] as Record<string, unknown>[])
          : []
      ).map(normalizeStatementLine),
      tradingTotal: safeWire(raw['tradingTotal']),
      grossProfit: safeWire(raw['grossProfit']),
      grossLoss: safeWire(raw['grossLoss']),
      pnlDebitLines: (
        Array.isArray(raw['pnlDebitLines'])
          ? (raw['pnlDebitLines'] as Record<string, unknown>[])
          : []
      ).map(normalizeStatementLine),
      pnlCreditLines: (
        Array.isArray(raw['pnlCreditLines'])
          ? (raw['pnlCreditLines'] as Record<string, unknown>[])
          : []
      ).map(normalizeStatementLine),
      pnlTotal: safeWire(raw['pnlTotal']),
      netProfit: safeWire(raw['netProfit']),
      netLoss: safeWire(raw['netLoss']),
      signedNetProfit: safeWire(raw['signedNetProfit'] ?? raw['netProfit']),
    };
  }

  async getBalanceSheet(
    companyId: string,
    query: {
      readonly asOfDate: string;
      readonly format?: BalanceSheetFormat;
      readonly includeOptional?: boolean;
    },
  ): Promise<BalanceSheetReportDto> {
    const format = query.format ?? 'HORIZONTAL';
    const params = new HttpParams()
      .set('asOfDate', query.asOfDate)
      .set('format', format)
      .set('includeOptional', String(query.includeOptional ?? false));

    const raw = await firstValueFrom(
      this.http.get<Record<string, unknown>>(this.companyUrl(companyId, '/reports/balance-sheet'), {
        params,
        withCredentials: true,
      }),
    );

    const totalLiab = safeWire(raw['totalLiabilities']);
    const totalAst = safeWire(raw['totalAssets']);
    const diff = safeWire(raw['differenceAmount']);

    return {
      asOfDate: String(raw['asOfDate'] ?? query.asOfDate),
      format: raw['format'] === 'VERTICAL' ? 'VERTICAL' : format,
      liabilityLines: (
        Array.isArray(raw['liabilityLines'])
          ? (raw['liabilityLines'] as Record<string, unknown>[])
          : []
      ).map(normalizeStatementLine),
      assetLines: (
        Array.isArray(raw['assetLines'])
          ? (raw['assetLines'] as Record<string, unknown>[])
          : []
      ).map(normalizeStatementLine),
      closingStockValue: safeWire(raw['closingStockValue']),
      profitAndLossBalance: safeWire(raw['profitAndLossBalance']),
      openingBalanceDifference: safeWire(raw['openingBalanceDifference']),
      totalLiabilities: totalLiab,
      totalAssets: totalAst,
      differenceAmount: diff,
      isBalanced:
        raw['isBalanced'] !== undefined
          ? Boolean(raw['isBalanced'])
          : totalLiab === totalAst && (ExactDecimal.tryParse(diff, 2)?.isZero() ?? true),
    };
  }

  async getScheduleIii(
    companyId: string,
    query: {
      readonly asOfDate: string;
      readonly comparativeAsOfDate?: string | null;
      readonly entityFormat?: ScheduleIiiEntityFormat;
      readonly roundingScale?: ScheduleIiiRoundingScale;
    },
  ): Promise<ScheduleIiiReportDto> {
    const entityFormat = query.entityFormat ?? 'DIVISION_I';
    const roundingScale = query.roundingScale ?? 'ACTUAL';
    let params = new HttpParams()
      .set('asOfDate', query.asOfDate)
      .set('entityFormat', entityFormat)
      .set('roundingScale', roundingScale);
    if (query.comparativeAsOfDate) {
      params = params.set('comparativeAsOfDate', query.comparativeAsOfDate);
    }

    const raw = await firstValueFrom(
      this.http.get<Record<string, unknown>>(this.companyUrl(companyId, '/reports/schedule-iii'), {
        params,
        withCredentials: true,
      }),
    );

    const totalEqLiab = safeWire(raw['totalEquityAndLiabilities']);
    const totalAst = safeWire(raw['totalAssets']);

    return {
      asOfDate: String(raw['asOfDate'] ?? query.asOfDate),
      comparativeAsOfDate: raw['comparativeAsOfDate']
        ? String(raw['comparativeAsOfDate'])
        : (query.comparativeAsOfDate ?? null),
      entityFormat: raw['entityFormat'] === 'NCE' ? 'NCE' : entityFormat,
      roundingScale: (raw['roundingScale'] as ScheduleIiiRoundingScale) ?? roundingScale,
      balanceSheetLines: (
        Array.isArray(raw['balanceSheetLines'])
          ? (raw['balanceSheetLines'] as Record<string, unknown>[])
          : []
      ).map(normalizeScheduleIiiLine),
      statementOfPnlLines: (
        Array.isArray(raw['statementOfPnlLines'])
          ? (raw['statementOfPnlLines'] as Record<string, unknown>[])
          : []
      ).map(normalizeScheduleIiiLine),
      notes: (Array.isArray(raw['notes']) ? (raw['notes'] as Record<string, unknown>[]) : []).map(
        normalizeScheduleIiiNote,
      ),
      totalEquityAndLiabilities: totalEqLiab,
      totalAssets: totalAst,
      netProfitForPeriod: safeWire(raw['netProfitForPeriod']),
      isBalanced:
        raw['isBalanced'] !== undefined ? Boolean(raw['isBalanced']) : totalEqLiab === totalAst,
    };
  }

  async getCashFlow(
    companyId: string,
    query: { readonly fromDate: string; readonly toDate: string },
  ): Promise<CashFlowReportDto> {
    const params = new HttpParams().set('fromDate', query.fromDate).set('toDate', query.toDate);
    const raw = await firstValueFrom(
      this.http.get<Record<string, unknown>>(this.companyUrl(companyId, '/reports/cash-flow'), {
        params,
        withCredentials: true,
      }),
    );

    const monthlySummary: CashFlowMonthRowDto[] = (
      Array.isArray(raw['monthlySummary'])
        ? (raw['monthlySummary'] as Record<string, unknown>[])
        : []
    ).map((m) => ({
      monthLabel: String(m['monthLabel'] ?? m['month'] ?? ''),
      fromDate: String(m['fromDate'] ?? ''),
      toDate: String(m['toDate'] ?? ''),
      inflow: safeWire(m['inflow']),
      outflow: safeWire(m['outflow']),
      netFlow: safeWire(m['netFlow']),
      closingBalance: safeWire(m['closingBalance']),
    }));

    const activities: CashFlowActivityRowDto[] = (
      Array.isArray(raw['activities']) ? (raw['activities'] as Record<string, unknown>[]) : []
    ).map((a) => ({
      category:
        a['category'] === 'INVESTING' || a['category'] === 'FINANCING'
          ? a['category']
          : 'OPERATING',
      particulars: String(a['particulars'] ?? a['name'] ?? ''),
      inflow: safeWire(a['inflow']),
      outflow: safeWire(a['outflow']),
      netFlow: safeWire(a['netFlow']),
    }));

    return {
      openingCashAndBank: safeWire(raw['openingCashAndBank']),
      operatingInflow: safeWire(raw['operatingInflow']),
      operatingOutflow: safeWire(raw['operatingOutflow']),
      netOperatingCashFlow: safeWire(raw['netOperatingCashFlow']),
      investingCashFlow: safeWire(raw['investingCashFlow']),
      financingCashFlow: safeWire(raw['financingCashFlow']),
      netCashMovement: safeWire(raw['netCashMovement']),
      closingCashAndBank: safeWire(raw['closingCashAndBank']),
      monthlySummary,
      activities,
    };
  }

  async getRatios(
    companyId: string,
    query: { readonly fromDate: string; readonly toDate: string },
  ): Promise<RatioAnalysisReportDto> {
    const params = new HttpParams().set('fromDate', query.fromDate).set('toDate', query.toDate);
    const raw = await firstValueFrom(
      this.http.get<Record<string, unknown>>(this.companyUrl(companyId, '/reports/ratios'), {
        params,
        withCredentials: true,
      }),
    );

    return {
      workingCapital: safeWire(raw['workingCapital']),
      cashInHand: safeWire(raw['cashInHand']),
      bankAccounts: safeWire(raw['bankAccounts']),
      sundryDebtors: safeWire(raw['sundryDebtors']),
      sundryCreditors: safeWire(raw['sundryCreditors']),
      salesAccounts: safeWire(raw['salesAccounts']),
      purchaseAccounts: safeWire(raw['purchaseAccounts']),
      stockInHand: safeWire(raw['stockInHand']),
      netProfit: safeWire(raw['netProfit']),
      currentRatio: safeWire(raw['currentRatio']),
      quickRatio: safeWire(raw['quickRatio']),
      debtEquityRatio: safeWire(raw['debtEquityRatio']),
      grossProfitPct: safeWire(raw['grossProfitPct']),
      netProfitPct: safeWire(raw['netProfitPct']),
      returnOnWorkingCapitalPct: safeWire(raw['returnOnWorkingCapitalPct']),
      inventoryTurnover: safeWire(raw['inventoryTurnover']),
      receivableTurnoverDays: safeWire(raw['receivableTurnoverDays']),
    };
  }

  async getBillsAgeing(
    companyId: string,
    query: { readonly type: BillsAgeingType; readonly asOfDate: string },
  ): Promise<BillsAgeingReportDto> {
    const params = new HttpParams().set('type', query.type).set('asOfDate', query.asOfDate);
    const raw = await firstValueFrom(
      this.http.get<Record<string, unknown>>(this.companyUrl(companyId, '/reports/ageing'), {
        params,
        withCredentials: true,
      }),
    );

    const bills = (
      Array.isArray(raw['bills']) ? (raw['bills'] as Record<string, unknown>[]) : []
    ).map(normalizeAgeingBill);

    const rawTotals = (raw['bucketTotals'] ?? {}) as Record<string, unknown>;
    let sum0 = ExactDecimal.zero(2);
    let sum31 = ExactDecimal.zero(2);
    let sum61 = ExactDecimal.zero(2);
    let sum90 = ExactDecimal.zero(2);
    for (const b of bills) {
      sum0 = sum0.add(ExactDecimal.tryParse(b.bucket0To30, 2) ?? ExactDecimal.zero(2), 2);
      sum31 = sum31.add(ExactDecimal.tryParse(b.bucket31To60, 2) ?? ExactDecimal.zero(2), 2);
      sum61 = sum61.add(ExactDecimal.tryParse(b.bucket61To90, 2) ?? ExactDecimal.zero(2), 2);
      sum90 = sum90.add(ExactDecimal.tryParse(b.bucketOver90, 2) ?? ExactDecimal.zero(2), 2);
    }
    const computedTotal = sum0.add(sum31, 2).add(sum61, 2).add(sum90, 2).toWireString(2);
    const totalPending =
      raw['totalPendingAmount'] !== undefined
        ? safeWire(raw['totalPendingAmount'])
        : computedTotal;

    const bucketTotals: AgeingBucketTotalsDto = {
      bucket0To30:
        rawTotals['bucket0To30'] !== undefined
          ? safeWire(rawTotals['bucket0To30'])
          : rawTotals['0_30'] !== undefined
            ? safeWire(rawTotals['0_30'])
            : sum0.toWireString(2),
      bucket31To60:
        rawTotals['bucket31To60'] !== undefined
          ? safeWire(rawTotals['bucket31To60'])
          : rawTotals['31_60'] !== undefined
            ? safeWire(rawTotals['31_60'])
            : sum31.toWireString(2),
      bucket61To90:
        rawTotals['bucket61To90'] !== undefined
          ? safeWire(rawTotals['bucket61To90'])
          : rawTotals['61_90'] !== undefined
            ? safeWire(rawTotals['61_90'])
            : sum61.toWireString(2),
      bucketOver90:
        rawTotals['bucketOver90'] !== undefined
          ? safeWire(rawTotals['bucketOver90'])
          : rawTotals['GT_90'] !== undefined
            ? safeWire(rawTotals['GT_90'])
            : sum90.toWireString(2),
      totalPending:
        rawTotals['totalPending'] !== undefined ? safeWire(rawTotals['totalPending']) : totalPending,
    };

    return {
      type: raw['type'] === 'PAYABLES' ? 'PAYABLES' : query.type,
      asOfDate: String(raw['asOfDate'] ?? query.asOfDate),
      bills,
      bucketTotals,
      totalPendingAmount: totalPending,
    };
  }

  async exportReportPdf(
    companyId: string,
    reportType: PrintableReportType,
    payload: Record<string, unknown> = {},
    filename?: string,
  ): Promise<Blob> {
    const blob = await firstValueFrom(
      this.http.post(
        this.companyUrl(companyId, `/reports/${encodeURIComponent(reportType)}/pdf`),
        payload,
        { responseType: 'blob', withCredentials: true },
      ),
    );
    this.triggerPdfDownload(blob, filename ?? `${reportType}_${companyId}.pdf`);
    return blob;
  }

  async printVoucherPdf(
    companyId: string,
    voucherId: string,
    filename?: string,
  ): Promise<Blob> {
    const blob = await firstValueFrom(
      this.http.post(
        this.companyUrl(companyId, `/vouchers/${encodeURIComponent(voucherId)}/print-pdf`),
        {},
        { responseType: 'blob', withCredentials: true },
      ),
    );
    this.triggerPdfDownload(blob, filename ?? `Voucher_${voucherId}.pdf`);
    return blob;
  }

  async getAccountBook(
    companyId: string,
    query: {
      readonly bookType: AccountBookType;
      readonly fromDate: string;
      readonly toDate: string;
      readonly groupId?: string | null;
      readonly ledgerId?: string | null;
    },
  ): Promise<AccountBookReportDto> {
    let params = new HttpParams()
      .set('bookType', query.bookType)
      .set('fromDate', query.fromDate)
      .set('toDate', query.toDate);
    if (query.groupId) {
      params = params.set('groupId', query.groupId);
    }
    if (query.ledgerId) {
      params = params.set('ledgerId', query.ledgerId);
    }

    const raw = await firstValueFrom(
      this.http.get<Record<string, unknown>>(this.companyUrl(companyId, '/reports/account-books'), {
        params,
        withCredentials: true,
      }),
    );

    const rawMonthly = Array.isArray(raw['monthlySummary'])
      ? (raw['monthlySummary'] as Record<string, unknown>[])
      : Array.isArray(raw['months'])
        ? (raw['months'] as Record<string, unknown>[])
        : [];
    const monthlySummary: AccountBookMonthlySummaryDto[] = rawMonthly.map((m) => ({
      monthLabel: String(m['monthLabel'] ?? m['monthKey'] ?? m['month'] ?? m['periodLabel'] ?? ''),
      fromDate: String(m['fromDate'] ?? ''),
      toDate: String(m['toDate'] ?? ''),
      voucherCount: Number(m['voucherCount'] ?? m['count'] ?? 0),
      debitAmount: safeWire(
        m['debitTotal'] ?? m['debitAmount'] ?? m['totalDebit'] ?? m['periodDebit'],
      ),
      creditAmount: safeWire(
        m['creditTotal'] ?? m['creditAmount'] ?? m['totalCredit'] ?? m['periodCredit'],
      ),
      closingBalance: safeWire(m['closingBalance'] ?? m['netAmount'] ?? m['netBalance']),
    }));

    const rawVoucherRows = Array.isArray(raw['voucherRows'])
      ? (raw['voucherRows'] as Record<string, unknown>[])
      : [];
    const rawSummaryRows = Array.isArray(raw['summaryRows'])
      ? (raw['summaryRows'] as Record<string, unknown>[])
      : [];
    const rawFallbackRows = Array.isArray(raw['rows'])
      ? (raw['rows'] as Record<string, unknown>[])
      : Array.isArray(raw['vouchers'])
        ? (raw['vouchers'] as Record<string, unknown>[])
        : Array.isArray(raw['items'])
          ? (raw['items'] as Record<string, unknown>[])
          : [];

    const sourceRows =
      rawVoucherRows.length > 0
        ? rawVoucherRows
        : rawSummaryRows.length > 0
          ? rawSummaryRows
          : rawFallbackRows;

    const rows: AccountBookRowDto[] = sourceRows.map((r, idx) => ({
      id: String(
        r['id'] ?? r['voucherId'] ?? r['entityId'] ?? r['groupId'] ?? r['ledgerId'] ?? `ab-row-${idx}`,
      ),
      voucherId: r['voucherId'] ? String(r['voucherId']) : null,
      rowDate: String(r['rowDate'] ?? r['voucherDate'] ?? r['date'] ?? ''),
      particulars: String(
        r['particulars'] ?? r['name'] ?? r['partyLedgerName'] ?? r['ledgerName'] ?? r['groupName'] ?? 'Entry',
      ),
      voucherTypeName: String(
        r['voucherTypeName'] ?? r['rowType'] ?? r['parentGroupName'] ?? query.bookType,
      ),
      voucherNumber: String(r['voucherNumber'] ?? r['code'] ?? ''),
      debitAmount: safeWire(
        r['debitAmount'] ?? r['closingDebit'] ?? r['periodDebit'] ?? r['totalDebit'],
      ),
      creditAmount: safeWire(
        r['creditAmount'] ?? r['closingCredit'] ?? r['periodCredit'] ?? r['totalCredit'],
      ),
      closingBalance: safeWire(
        r['runningBalance'] ?? r['closingBalance'] ?? r['closingDebit'] ?? r['balance'],
      ),
    }));

    return {
      bookType: (raw['bookType'] as AccountBookType | undefined) ?? query.bookType,
      fromDate: String(raw['fromDate'] ?? query.fromDate),
      toDate: String(raw['toDate'] ?? query.toDate),
      monthlySummary,
      rows,
      totalDebit: safeWire(raw['totalDebit'] ?? raw['closingDebit'] ?? raw['totalClosingDebit']),
      totalCredit: safeWire(raw['totalCredit'] ?? raw['closingCredit'] ?? raw['totalClosingCredit']),
      closingBalance: safeWire(
        raw['closingBalance'] ?? raw['closingDebit'] ?? raw['netBalance'],
      ),
    };
  }

  async getExceptionReport(
    companyId: string,
    query: {
      readonly type: ExceptionReportType;
      readonly fromDate: string;
      readonly toDate: string;
      readonly asOfDate?: string;
    },
  ): Promise<ExceptionReportDto> {
    let params = new HttpParams()
      .set('type', query.type)
      .set('fromDate', query.fromDate)
      .set('toDate', query.toDate);
    if (query.asOfDate) {
      params = params.set('asOfDate', query.asOfDate);
    }

    const raw = await firstValueFrom(
      this.http.get<Record<string, unknown>>(this.companyUrl(companyId, '/reports/exceptions'), {
        params,
        withCredentials: true,
      }),
    );

    const rawRows = Array.isArray(raw['rows'])
      ? (raw['rows'] as Record<string, unknown>[])
      : Array.isArray(raw['items'])
        ? (raw['items'] as Record<string, unknown>[])
        : [];
    const rows: ExceptionReportRowDto[] = rawRows.map((r, idx) => {
      const entityType = String(r['entityType'] ?? '').toUpperCase();
      const entityId = String(r['entityId'] ?? r['id'] ?? '');
      const resolvedVoucherId = r['voucherId']
        ? String(r['voucherId'])
        : entityType === 'VOUCHER' && entityId
          ? entityId
          : null;
      return {
        id: String(r['id'] ?? r['voucherId'] ?? r['entityId'] ?? `exc-${idx}`),
        entityId,
        voucherId: resolvedVoucherId,
        code: String(
          r['referenceNumber'] ?? r['code'] ?? r['voucherNumber'] ?? r['billName'] ?? '',
        ),
        name: String(
          r['particulars'] ?? r['name'] ?? r['stockItemName'] ?? r['ledgerName'] ?? r['partyName'] ?? '',
        ),
        secondaryLabel: String(
          r['groupOrCategory'] ?? r['secondaryLabel'] ?? r['voucherTypeName'] ?? r['groupName'] ?? r['uomSymbol'] ?? '',
        ),
        rowDate: String(r['date'] ?? r['rowDate'] ?? r['voucherDate'] ?? r['dueDate'] ?? ''),
        quantity: safeWire(r['quantity'] ?? r['closingQty'], 4),
        amount: safeWire(
          r['balanceOrAmount'] ?? r['amount'] ?? r['closingBalance'] ?? r['pendingAmount'] ?? r['debitAmount'],
        ),
        status: String(r['status'] ?? r['postingNature'] ?? query.type),
      };
    });

    return {
      exceptionType: (raw['type'] as ExceptionReportType | undefined) ?? query.type,
      fromDate: String(raw['fromDate'] ?? query.fromDate),
      toDate: String(raw['toDate'] ?? query.toDate),
      rows,
      totalCount: Number(raw['rowCount'] ?? raw['totalCount'] ?? raw['count'] ?? rows.length),
      totalAmount: safeWire(raw['totalAmount'] ?? raw['totalDebit'] ?? raw['totalPendingAmount']),
    };
  }

  async getStatistics(
    companyId: string,
    query: { readonly fromDate: string; readonly toDate: string },
  ): Promise<StatisticsReportDto> {
    const params = new HttpParams()
      .set('fromDate', query.fromDate)
      .set('toDate', query.toDate);

    const raw = await firstValueFrom(
      this.http.get<Record<string, unknown>>(this.companyUrl(companyId, '/reports/statistics'), {
        params,
        withCredentials: true,
      }),
    );

    const rawVch = Array.isArray(raw['voucherTypeCounts'])
      ? (raw['voucherTypeCounts'] as Record<string, unknown>[])
      : [];
    const voucherTypeCounts: StatisticsVoucherTypeRowDto[] = rawVch.map((v) => {
      const posted = Number(v['postedCount'] ?? v['activeCount'] ?? v['count'] ?? 0);
      const optional = Number(v['optionalCount'] ?? 0);
      const cancelled = Number(v['cancelledCount'] ?? 0);
      const total = Number(v['totalCount'] ?? posted + optional + cancelled);
      return {
        voucherTypeName: String(v['voucherTypeName'] ?? v['name'] ?? 'Voucher'),
        category: String(v['category'] ?? v['baseCategory'] ?? ''),
        postedCount: posted,
        optionalCount: optional,
        cancelledCount: cancelled,
        totalCount: total,
      };
    });

    const masterCounts: StatisticsMasterRowDto[] = [];
    const rawMasters = raw['masterCounts'];
    if (Array.isArray(rawMasters)) {
      for (const m of rawMasters as Record<string, unknown>[]) {
        masterCounts.push({
          masterType: String(m['masterType'] ?? m['label'] ?? m['name'] ?? ''),
          count: Number(m['count'] ?? 0),
        });
      }
    } else if (rawMasters && typeof rawMasters === 'object') {
      for (const [k, v] of Object.entries(rawMasters as Record<string, unknown>)) {
        masterCounts.push({
          masterType: k,
          count: Number(v ?? 0),
        });
      }
    }

    const totalVouchers = voucherTypeCounts.reduce((acc, r) => acc + r.totalCount, 0);
    const totalMasters = masterCounts.reduce((acc, r) => acc + r.count, 0);

    return {
      fromDate: String(raw['fromDate'] ?? query.fromDate),
      toDate: String(raw['toDate'] ?? query.toDate),
      voucherTypeCounts,
      masterCounts,
      totalVouchers: Number(raw['totalVouchers'] ?? totalVouchers),
      totalMasters: Number(raw['totalMasters'] ?? totalMasters),
    };
  }

  async getStockQuery(
    companyId: string,
    query: {
      readonly stockItemId?: string | null;
      readonly fromDate: string;
      readonly toDate: string;
    },
  ): Promise<StockQueryReportDto> {
    let params = new HttpParams()
      .set('fromDate', query.fromDate)
      .set('toDate', query.toDate);
    if (query.stockItemId) {
      params = params.set('stockItemId', query.stockItemId);
    }

    const raw = await firstValueFrom(
      this.http.get<Record<string, unknown>>(this.companyUrl(companyId, '/reports/stock-query'), {
        params,
        withCredentials: true,
      }),
    );

    const rawGodowns = Array.isArray(raw['godownBatchBreakup'])
      ? (raw['godownBatchBreakup'] as Record<string, unknown>[])
      : Array.isArray(raw['godownBreakup'])
        ? (raw['godownBreakup'] as Record<string, unknown>[])
        : Array.isArray(raw['godowns'])
          ? (raw['godowns'] as Record<string, unknown>[])
          : [];
    const godownBreakup: StockQueryGodownRowDto[] = rawGodowns.map((g) => ({
      godownName: String(g['godownName'] ?? g['name'] ?? 'Main Location'),
      batchName: String(g['batchName'] ?? 'Primary Batch'),
      closingQty: safeWire(g['closingQty'] ?? g['quantity'], 4),
      closingValue: safeWire(g['closingValue'] ?? g['value'], 2),
    }));

    return {
      stockItemId: String(raw['stockItemId'] ?? query.stockItemId ?? ''),
      stockItemName: String(raw['stockItemName'] ?? raw['name'] ?? ''),
      uomSymbol: String(raw['uomSymbol'] ?? raw['baseUomSymbol'] ?? 'NOS'),
      costingMethod: String(raw['costingMethod'] ?? 'AVG_COST'),
      openingQty: safeWire(raw['openingQty'], 4),
      openingValue: safeWire(raw['openingValue'], 2),
      inwardQty: safeWire(raw['inwardQty'], 4),
      inwardValue: safeWire(raw['inwardValue'], 2),
      outwardQty: safeWire(raw['outwardQty'], 4),
      outwardValue: safeWire(raw['outwardValue'], 2),
      closingQty: safeWire(raw['closingQty'], 4),
      closingValue: safeWire(raw['closingValue'], 2),
      lastPurchaseParty: String(raw['lastPurchaseParty'] ?? '—'),
      lastPurchaseRate: safeWire(raw['lastPurchaseRate'], 2),
      lastSalesParty: String(raw['lastSalesParty'] ?? '—'),
      lastSalesRate: safeWire(raw['lastSalesRate'], 2),
      godownBreakup,
    };
  }
}
