import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import {
   SmartFindModalComponent,
  splitHighlightSegments,
} from '../../shared/components/smart-find-modal/smart-find-modal.component';
import { CompanyContextStore, CompanySummary } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { SmartFindStore } from '../../state/smart-find.store';
import { BankingBrsWorkbenchComponent } from '../banking/banking-brs-workbench.component';
import { MigrationAndExchangeComponent } from '../exchange/migration-and-exchange.component';
import { BalanceSheetComponent } from './balance-sheet.component';
import { BillsAgeingComponent } from './bills-ageing.component';
import { CashFlowAndRatiosComponent } from './cash-flow-and-ratios.component';
import { ProfitAndLossComponent } from './profit-and-loss.component';
import { ReportsService } from './reports.service';
import { ScheduleIiiComponent } from './schedule-iii.component';
import {
  TB_GROUP_DRILL_ROUTE_TOKEN,
  TrialBalanceComponent,
} from './trial-balance.component';

const PHASE6_COMPANY: CompanySummary = {
  id: 'c1',
  code: 'CMP001',
  legalName: 'Dakshin Precision Engineering Pvt Ltd',
  mailingName: 'Dakshin Precision',
  countryCode: 'IN',
  stateCode: '33',
  pinCode: '600032',
  baseCurrencyCode: 'INR',
  currencySymbol: '₹',
  currencyScale: 2,
  financialYearFrom: '2025-04-01',
  booksBeginningFrom: '2025-04-01',
  lastEntryDate: '2025-04-30',
  versionNo: 1,
  features: {
    maintainAccounts: true,
    billWiseEnabled: true,
    costCentresEnabled: true,
    maintainInventory: true,
    inventoryIntegrated: true,
    gstEnabled: true,
    multiGstinEnabled: true,
    gstRegistrationType: 'REGULAR',
    gstin: '33AABCD1234E1Z5',
    gstr1Periodicity: 'MONTHLY',
    gstEffectiveFrom: '2025-04-01',
    tdsEnabled: true,
    mcaEditLogEnforced: true,
  },
};

async function flushMicrotasks(): Promise<void> {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve();
  }
}

describe('Phase 6 — Financial Statements, SmartFind, Auto-BRS & Clean-Room .1800 Migration', () => {
  let httpMock: HttpTestingController;
  let companyContext: CompanyContextStore;
  let navStack: NavigationStackStore;
  let shortcutRegistry: ShortcutCommandRegistry;
  let reportsService: ReportsService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });

    httpMock = TestBed.inject(HttpTestingController);
    companyContext = TestBed.inject(CompanyContextStore);
    navStack = TestBed.inject(NavigationStackStore);
    shortcutRegistry = TestBed.inject(ShortcutCommandRegistry);
    reportsService = TestBed.inject(ReportsService);

    companyContext.setCompanies([PHASE6_COMPANY], 'c1');
    navStack.clear();
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('BalanceSheetComponent renders Golden Vector ₹ 6,10,949.71, toggles Horizontal/Vertical (Ctrl+H) & Auto-Column (Alt+N), and prints PDF (Ctrl+P)', async () => {
    const fixture = TestBed.createComponent(BalanceSheetComponent);
    fixture.detectChanges();

    const getHorizontal = httpMock.expectOne(
      (req) =>
        req.method === 'GET' &&
        req.url.endsWith('/api/v1/companies/c1/reports/balance-sheet') &&
        req.params.get('format') === 'HORIZONTAL',
    );
    getHorizontal.flush({
      asOfDate: '2026-03-31',
      format: 'HORIZONTAL',
      liabilityLines: [
        { id: 'grp-cap', lineType: 'GROUP', name: 'Capital Account', amount: '500000.00' },
        { id: 'grp-cl', lineType: 'GROUP', name: 'Current Liabilities', amount: '106564.00' },
        { id: 'pnl-trf', lineType: 'PROFIT_LOSS', name: 'Profit & Loss A/c', amount: '4385.71' },
      ],
      assetLines: [
        { id: 'grp-fa', lineType: 'GROUP', name: 'Fixed Assets', amount: '245000.00' },
        {
          id: 'grp-ca',
          lineType: 'GROUP',
          name: 'Current Assets',
          amount: '365949.71',
          comparativeAmount: '310000.00',
        },
      ],
      totalLiabilities: '610949.71',
      totalAssets: '610949.71',
      openingBalanceDifference: '0.00',
      differenceAmount: '0.00',
      isBalanced: true,
    });
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="bs-horizontal-view"]')).toBeTruthy();
    expect(host.querySelector('[data-testid="bs-total-liabilities"]')?.textContent).toContain(
      '6,10,949.71',
    );
    expect(host.querySelector('[data-testid="bs-total-assets"]')?.textContent).toContain(
      '6,10,949.71',
    );
    expect(host.querySelector('[data-testid="bs-balanced-badge"]')?.textContent).toContain(
      'Assets = Liabilities',
    );

    // Alt+N Auto-Columnar toggle
    const altN = new KeyboardEvent('keydown', { key: 'n', code: 'KeyN', altKey: true });
    shortcutRegistry.dispatchKeyboardEvent(altN);
    fixture.detectChanges();
    expect(fixture.componentInstance.autoColumnar()).toBe(true);

    // Ctrl+H switches Horizontal -> Vertical and reloads with format=VERTICAL
    const ctrlH = new KeyboardEvent('keydown', { key: 'h', code: 'KeyH', ctrlKey: true });
    shortcutRegistry.dispatchKeyboardEvent(ctrlH);

    const getVertical = httpMock.expectOne(
      (req) =>
        req.method === 'GET' &&
        req.url.endsWith('/api/v1/companies/c1/reports/balance-sheet') &&
        req.params.get('format') === 'VERTICAL',
    );
    getVertical.flush({
      asOfDate: '2026-03-31',
      format: 'VERTICAL',
      liabilityLines: [
        { id: 'grp-cap', lineType: 'GROUP', name: 'Sources of Funds', amount: '610949.71' },
      ],
      assetLines: [
        { id: 'grp-ca', lineType: 'GROUP', name: 'Application of Funds', amount: '610949.71' },
      ],
      totalLiabilities: '610949.71',
      totalAssets: '610949.71',
      differenceAmount: '0.00',
      isBalanced: true,
    });
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="bs-vertical-view"]')).toBeTruthy();

    // Loop 5: Scale Factor (`LAKHS` -> 6,10,949.71 / 1,00,000 = 6.11) and Vertical View `Alt+R` / `Alt+U` line removal/restoration
    fixture.componentInstance.setScaleFactor('LAKHS');
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="bs-total-assets"]')?.textContent).toContain('6.11');
    fixture.componentInstance.setScaleFactor('DEFAULT');
    fixture.detectChanges();

    fixture.componentInstance.selectedKey.set('L:grp-cap');
    fixture.componentInstance.removeSelectedLine();
    fixture.detectChanges();
    expect(fixture.componentInstance.visibleLiabilityLines().length).toBe(0);
    fixture.componentInstance.restoreLastRemovedLine();
    fixture.detectChanges();
    expect(fixture.componentInstance.visibleLiabilityLines().length).toBe(1);

    // Ctrl+P triggers PDF export
    const ctrlP = new KeyboardEvent('keydown', { key: 'p', code: 'KeyP', ctrlKey: true });
    shortcutRegistry.dispatchKeyboardEvent(ctrlP);

    const pdfReq = httpMock.expectOne(
      (req) =>
        req.method === 'POST' &&
        req.url.endsWith('/api/v1/companies/c1/reports/balance-sheet/pdf'),
    );
    pdfReq.flush(new Blob(['%PDF-1.7 balance-sheet'], { type: 'application/pdf' }));
    await flushMicrotasks();
    fixture.detectChanges();

    expect(reportsService.lastDownloadedPdfFilename).toBe('BalanceSheet_2026-03-31.pdf');
    expect(reportsService.lastPdfBlobSize).toBeGreaterThan(8);
  });

  it('ProfitAndLossComponent renders two-tier Trading + P&L Account with Golden Vector Gross Profit ₹ 49,385.71 & Net Profit ₹ 4,385.71', async () => {
    const fixture = TestBed.createComponent(ProfitAndLossComponent);
    fixture.detectChanges();

    const req = httpMock.expectOne(
      (r) => r.method === 'GET' && r.url.endsWith('/api/v1/companies/c1/reports/profit-and-loss'),
    );
    req.flush({
      fromDate: '2025-04-01',
      toDate: '2026-03-31',
      openingStockValue: '15000.00',
      closingStockValue: '24385.71',
      salesTotal: '125000.00',
      purchaseTotal: '85000.00',
      directExpensesTotal: '0.00',
      directIncomesTotal: '0.00',
      indirectExpensesTotal: '45000.00',
      indirectIncomesTotal: '0.00',
      tradingDebitLines: [
        { id: 'op-stk', lineType: 'STOCK', name: 'Opening Stock', amount: '15000.00' },
        { id: 'grp-pur', lineType: 'GROUP', name: 'Purchase Accounts', amount: '85000.00' },
        { id: 'gp-co', lineType: 'PROFIT_LOSS', name: 'Gross Profit c/o', amount: '49385.71' },
      ],
      tradingCreditLines: [
        { id: 'grp-sls', lineType: 'GROUP', name: 'Sales Accounts', amount: '125000.00' },
        { id: 'cl-stk', lineType: 'STOCK', name: 'Closing Stock', amount: '24385.71' },
      ],
      tradingTotal: '149385.71',
      grossProfit: '49385.71',
      grossLoss: '0.00',
      pnlDebitLines: [
        { id: 'grp-iex', lineType: 'GROUP', name: 'Indirect Expenses', amount: '45000.00' },
        { id: 'np', lineType: 'PROFIT_LOSS', name: 'Net Profit', amount: '4385.71' },
      ],
      pnlCreditLines: [
        { id: 'gp-bf', lineType: 'PROFIT_LOSS', name: 'Gross Profit b/f', amount: '49385.71' },
      ],
      pnlTotal: '49385.71',
      netProfit: '4385.71',
      netLoss: '0.00',
      signedNetProfit: '4385.71',
    });
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="pnl-trading-account"]')).toBeTruthy();
    expect(host.querySelector('[data-testid="pnl-account-section"]')).toBeTruthy();
    expect(host.querySelector('[data-testid="pnl-gross-profit"]')?.textContent).toContain(
      '49,385.71',
    );
    expect(host.querySelector('[data-testid="pnl-net-profit"]')?.textContent).toContain(
      '4,385.71',
    );
    expect(host.querySelector('[data-testid="pnl-sales-total"]')?.textContent).toContain(
      '1,25,000.00',
    );
    expect(host.querySelector('[data-testid="pnl-closing-stock"]')?.textContent).toContain(
      '24,385.71',
    );

    // Loop 5: Profit & Loss Scale Factor (`THOUSANDS` -> 1,25,000.00 / 1,000 = 125.00)
    fixture.componentInstance.setScaleFactor('THOUSANDS');
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="pnl-sales-total"]')?.textContent).toContain('125.00');
    fixture.componentInstance.setScaleFactor('DEFAULT');
    fixture.detectChanges();
  });

  it('TrialBalanceComponent renders balanced ₹ 7,60,448.00, supports F5 Group/Ledger toggle, and drills down & pops via NavigationStackStore restoring exact row focus', async () => {
    const fixture = TestBed.createComponent(TrialBalanceComponent);
    fixture.detectChanges();

    const tbReq = httpMock.expectOne(
      (r) => r.method === 'GET' && r.url.endsWith('/api/v1/companies/c1/reports/trial-balance'),
    );
    tbReq.flush({
      fromDate: '2025-04-01',
      toDate: '2026-03-31',
      includeOptional: false,
      groupRows: [
        {
          id: 'grp-cap',
          name: 'Capital Account',
          rowType: 'GROUP',
          closingDebit: '0.00',
          closingCredit: '500000.00',
          children: [],
        },
        {
          id: 'grp-debtors',
          name: 'Sundry Debtors',
          rowType: 'GROUP',
          closingDebit: '760448.00',
          closingCredit: '0.00',
          children: [
            {
              id: 'led-arjun',
              name: 'Arjun Components Ltd',
              rowType: 'LEDGER',
              closingDebit: '460448.00',
              closingCredit: '0.00',
            },
            {
              id: 'led-meera',
              name: 'Meera Precision Works',
              rowType: 'LEDGER',
              closingDebit: '300000.00',
              closingCredit: '0.00',
            },
          ],
        },
      ],
      ledgerRows: [
        {
          id: 'led-cap',
          name: 'Share Capital',
          rowType: 'LEDGER',
          closingDebit: '0.00',
          closingCredit: '500000.00',
        },
        {
          id: 'led-arjun',
          parentGroupId: 'grp-debtors',
          name: 'Arjun Components Ltd',
          rowType: 'LEDGER',
          closingDebit: '460448.00',
          closingCredit: '0.00',
        },
      ],
      totalOpeningDebit: '0.00',
      totalOpeningCredit: '0.00',
      totalPeriodDebit: '760448.00',
      totalPeriodCredit: '760448.00',
      totalClosingDebit: '760448.00',
      totalClosingCredit: '760448.00',
      differenceAmount: '0.00',
      isBalanced: true,
    });
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const comp = fixture.componentInstance;

    expect(host.querySelector('[data-testid="tb-total-closing-debit"]')?.textContent).toContain(
      '7,60,448.00',
    );
    expect(host.querySelector('[data-testid="tb-total-closing-credit"]')?.textContent).toContain(
      '7,60,448.00',
    );
    expect(host.querySelector('[data-testid="tb-balanced-badge"]')?.textContent).toContain(
      'Balanced',
    );

    // Move selection to index 1 (Sundry Debtors) via ArrowDown
    comp.onKeyDown(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    fixture.detectChanges();
    expect(comp.selectedIndex()).toBe(1);

    // Press Enter to drill into Sundry Debtors -> pushes onto NavigationStackStore
    comp.onKeyDown(new KeyboardEvent('keydown', { key: 'Enter' }));
    fixture.detectChanges();

    expect(navStack.depth()).toBe(1);
    expect(navStack.topFrame()?.route).toBe(TB_GROUP_DRILL_ROUTE_TOKEN);
    expect(host.querySelector('[data-testid="tb-active-group-drill"]')?.textContent).toContain(
      'Sundry Debtors',
    );
    expect(comp.displayRows().map((r) => r.name)).toEqual([
      'Arjun Components Ltd',
      'Meera Precision Works',
    ]);
    expect(comp.selectedIndex()).toBe(0);

    // Press Escape -> pops NavigationStackStore and restores exact prior row focus (index 1)!
    comp.onKeyDown(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();

    expect(navStack.depth()).toBe(0);
    expect(comp.activeGroupDrill()).toBeNull();
    expect(comp.selectedIndex()).toBe(1);

    // F5 toggles GROUP <-> LEDGER mode
    shortcutRegistry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'F5', code: 'F5' }),
    );
    fixture.detectChanges();
    expect(comp.viewMode()).toBe('LEDGER');
  });

  it('ScheduleIiiComponent switches Division I / NCE & Lakhs rounding with expandable Notes; BillsAgeingComponent toggles Receivable/Payable (F4)', async () => {
    const s3Fixture = TestBed.createComponent(ScheduleIiiComponent);
    s3Fixture.detectChanges();

    const s3Init = httpMock.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url.endsWith('/api/v1/companies/c1/reports/schedule-iii') &&
        r.params.get('entityFormat') === 'DIVISION_I' &&
        r.params.get('roundingScale') === 'ACTUAL',
    );
    s3Init.flush({
      asOfDate: '2026-03-31',
      comparativeAsOfDate: '2025-03-31',
      entityFormat: 'DIVISION_I',
      roundingScale: 'ACTUAL',
      balanceSheetLines: [
        {
          lineCode: 'BS.I.1.a',
          particulars: 'Share Capital',
          noteNo: '1',
          indentLevel: 1,
          isSubtotal: false,
          currentAmount: '500000.00',
          comparativeAmount: '500000.00',
        },
      ],
      statementOfPnlLines: [
        {
          lineCode: 'PL.I',
          particulars: 'Revenue from Operations',
          noteNo: '2',
          indentLevel: 0,
          isSubtotal: false,
          currentAmount: '125000.00',
          comparativeAmount: '100000.00',
        },
      ],
      notes: [
        {
          noteNo: '1',
          lineCode: 'BS.I.1.a',
          title: 'Share Capital',
          currentTotal: '500000.00',
          comparativeTotal: '500000.00',
          items: [
            {
              id: 'led-eq',
              name: 'Equity Share Capital',
              currentAmount: '500000.00',
              comparativeAmount: '500000.00',
            },
          ],
        },
      ],
      totalEquityAndLiabilities: '610949.71',
      totalAssets: '610949.71',
      netProfitForPeriod: '4385.71',
      isBalanced: true,
    });
    await flushMicrotasks();
    s3Fixture.detectChanges();

    const s3Host = s3Fixture.nativeElement as HTMLElement;
    expect(s3Host.querySelector('[data-testid="s3-part-i-bs"]')).toBeTruthy();
    expect(s3Host.querySelector('[data-testid="s3-part-ii-pnl"]')).toBeTruthy();

    // Note 1 is expanded by default; clicking toggle collapses then re-expands
    expect(s3Host.querySelector('[data-testid="s3-note-body-1"]')?.textContent).toContain(
      'Equity Share Capital',
    );
    (s3Host.querySelector('[data-testid="s3-note-toggle-1"]') as HTMLButtonElement).click();
    s3Fixture.detectChanges();
    expect(s3Host.querySelector('[data-testid="s3-note-body-1"]')).toBeNull();

    // Switch rounding to LAKHS
    s3Fixture.componentInstance.onRoundingChange('LAKHS');
    const s3Lakhs = httpMock.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url.endsWith('/api/v1/companies/c1/reports/schedule-iii') &&
        r.params.get('roundingScale') === 'LAKHS',
    );
    s3Lakhs.flush({
      asOfDate: '2026-03-31',
      entityFormat: 'DIVISION_I',
      roundingScale: 'LAKHS',
      balanceSheetLines: [],
      statementOfPnlLines: [],
      notes: [],
      totalEquityAndLiabilities: '6.11',
      totalAssets: '6.11',
      netProfitForPeriod: '0.04',
      isBalanced: true,
    });
    await flushMicrotasks();
    s3Fixture.detectChanges();
    expect(s3Host.querySelector('[data-testid="s3-total-equity-liab"]')?.textContent).toContain(
      '6.11',
    );
    s3Fixture.destroy();

    // BillsAgeingComponent test
    const ageFixture = TestBed.createComponent(BillsAgeingComponent);
    ageFixture.detectChanges();

    const ageRec = httpMock.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url.endsWith('/api/v1/companies/c1/reports/ageing') &&
        r.params.get('type') === 'RECEIVABLES',
    );
    ageRec.flush({
      type: 'RECEIVABLES',
      asOfDate: '2026-03-31',
      bills: [
        {
          id: 'bill-1',
          voucherId: 'vch-101',
          ledgerId: 'led-arjun',
          partyName: 'Arjun Components Ltd',
          partyGstin: '33AABCA9999K1Z2',
          billName: 'INV/25-26/001',
          billDate: '2025-12-01',
          dueDate: '2025-12-31',
          ageDays: 95,
          overdueDays: 90,
          bucket: 'GT_90',
          pendingAmount: '118000.00',
        },
        {
          id: 'bill-2',
          voucherId: 'vch-102',
          ledgerId: 'led-meera',
          partyName: 'Meera Precision Works',
          billName: 'INV/25-26/045',
          billDate: '2026-03-15',
          dueDate: '2026-04-14',
          ageDays: 16,
          overdueDays: 0,
          bucket: '0_30',
          pendingAmount: '59000.00',
        },
      ],
    });
    await flushMicrotasks();
    ageFixture.detectChanges();

    const ageHost = ageFixture.nativeElement as HTMLElement;
    expect(ageHost.querySelector('[data-testid="ageing-bkt-0-30"]')?.textContent).toContain(
      '59,000.00',
    );
    expect(ageHost.querySelector('[data-testid="ageing-bkt-gt-90"]')?.textContent).toContain(
      '1,18,000.00',
    );
    expect(ageHost.querySelector('[data-testid="ageing-total-pending"]')?.textContent).toContain(
      '1,77,000.00',
    );

    // Party filter narrows visible bills
    ageFixture.componentInstance.partyFilter.set('Meera');
    ageFixture.detectChanges();
    expect(ageFixture.componentInstance.filteredBills().length).toBe(1);

    // F4 switches RECEIVABLES -> PAYABLES
    shortcutRegistry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'F4', code: 'F4' }),
    );
    const agePay = httpMock.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url.endsWith('/api/v1/companies/c1/reports/ageing') &&
        r.params.get('type') === 'PAYABLES',
    );
    agePay.flush({
      type: 'PAYABLES',
      asOfDate: '2026-03-31',
      bills: [],
    });
    await flushMicrotasks();
    ageFixture.detectChanges();
    expect(ageFixture.componentInstance.ageingType()).toBe('PAYABLES');
  });

  it('SmartFindModalComponent queries live /search/smartfind, categorizes results, highlights snippets, toggles Alt+S scope, jumps on Enter, and ReportsService prints voucher PDF', async () => {
    const router = TestBed.inject(Router);
    const navSpy = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    const smartFind = TestBed.inject(SmartFindStore);

    expect(splitHighlightSegments('Settled Invoice KIS/25-26/088 via NEFT', 'KIS/25-26')).toEqual([
      { text: 'Settled Invoice ', matched: false },
      { text: 'KIS/25-26', matched: true },
      { text: '/088 via NEFT', matched: false },
    ]);

    const fixture = TestBed.createComponent(SmartFindModalComponent);
    smartFind.open();
    fixture.detectChanges();

    const searchPromise = smartFind.search('Kaveri');
    const getActive = httpMock.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url.endsWith('/api/v1/companies/c1/search/smartfind') &&
        r.params.get('q') === 'Kaveri' &&
        r.params.get('scope') === 'ACTIVE' &&
        r.params.get('limit') === '30',
    );
    getActive.flush({
      query: 'Kaveri',
      scope: 'ACTIVE',
      tookMs: 4,
      totalMatches: 3,
      results: [
        {
          id: 'led-kav',
          companyId: 'c1',
          companyName: 'Dakshin Precision Engineering Pvt Ltd',
          category: 'MASTER',
          entityType: 'LEDGER',
          title: 'Kaveri Industrial Supplies',
          subtitle: 'Sundry Creditors',
          matchedField: 'Ledger Name',
          matchedSnippet: 'Supplier Kaveri Industrial Supplies (33AABCK1111F1Z9)',
          referenceCode: '33AABCK1111F1Z9',
          amount: null,
          route: '/app/masters/ledgers',
        },
        {
          id: 'vch-55',
          companyId: 'c1',
          companyName: 'Dakshin Precision Engineering Pvt Ltd',
          category: 'VOUCHER',
          entityType: 'PURCHASE',
          title: 'Purchase #PUR-0055',
          subtitle: '2025-04-20',
          matchedField: 'Narration',
          matchedSnippet: 'Raw material inward from Kaveri warehouse',
          referenceCode: 'PUR-0055',
          amount: '11800.00',
          route: '/app/vouchers/vch-55',
        },
        {
          id: 'gst-ims-9',
          companyId: 'c1',
          companyName: 'Dakshin Precision Engineering Pvt Ltd',
          category: 'STATUTORY',
          entityType: 'IMS_INVOICE',
          title: 'IMS 2B #KIS/25-26/088',
          subtitle: 'GSTR-2B / IMS',
          matchedField: 'Supplier GSTIN',
          matchedSnippet: 'Kaveri inward supply pending IMS acceptance',
          referenceCode: 'KIS/25-26/088',
          amount: '11800.00',
          route: '/app/statutory/ims',
        },
      ],
    });
    await searchPromise;
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="smartfind-cat-MASTER"]')).toBeTruthy();
    expect(host.querySelector('[data-testid="smartfind-cat-VOUCHER"]')).toBeTruthy();
    expect(host.querySelector('[data-testid="smartfind-cat-STATUTORY"]')).toBeTruthy();

    const highlightedMarks = host.querySelectorAll('mark.mc-smartfind-mark');
    expect(highlightedMarks.length).toBeGreaterThanOrEqual(3);
    expect(highlightedMarks[0].textContent).toBe('Kaveri');

    // Alt+S toggles scope to ALL_OPEN_COMPANIES and re-queries
    const dialog = host.querySelector('[data-testid="smartfind-dialog"]') as HTMLElement;
    dialog.dispatchEvent(
      new KeyboardEvent('keydown', { key: 's', code: 'KeyS', altKey: true, bubbles: true }),
    );
    const getAllScope = httpMock.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url.endsWith('/api/v1/companies/c1/search/smartfind') &&
        r.params.get('scope') === 'ALL_OPEN_COMPANIES',
    );
    getAllScope.flush({
      query: 'Kaveri',
      scope: 'ALL_OPEN_COMPANIES',
      tookMs: 6,
      totalMatches: 2,
      results: [
        {
          id: 'led-kav',
          category: 'MASTER',
          entityType: 'LEDGER',
          title: 'Kaveri Industrial Supplies',
          subtitle: 'Sundry Creditors',
          matchedField: 'Name',
          matchedSnippet: 'Kaveri Industrial Supplies',
          route: '/app/masters/ledgers',
        },
        {
          id: 'vch-55',
          category: 'VOUCHER',
          entityType: 'PURCHASE',
          title: 'Purchase #PUR-0055',
          subtitle: '2025-04-20',
          matchedField: 'Narration',
          matchedSnippet: 'Kaveri Purchase',
          amount: '11800.00',
          route: '/app/vouchers/vch-55',
        },
      ],
    });
    await flushMicrotasks();
    fixture.detectChanges();

    // ArrowDown selects index 1 (Purchase #PUR-0055), Enter pushes onto NavigationStackStore & navigates
    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    fixture.detectChanges();

    expect(smartFind.isOpen()).toBe(false);
    expect(navStack.topFrame()?.route).toBe('/app/vouchers/vch-55');
    expect(navSpy).toHaveBeenCalledWith('/app/vouchers/vch-55');

    // Double-Ctrl+F passthrough closes SmartFind without intercepting native browser Find
    shortcutRegistry.configureSmartFindPassthrough(
      () => smartFind.isOpen(),
      () => smartFind.close(),
    );
    const unbindFind = shortcutRegistry.bindHandler('search.smartFind', 'GLOBAL', () =>
      smartFind.open(),
    );
    expect(
      shortcutRegistry.dispatchKeyboardEvent(
        new KeyboardEvent('keydown', { key: 'f', code: 'KeyF', ctrlKey: true, cancelable: true }),
      ),
    ).toBe(true);
    expect(smartFind.isOpen()).toBe(true);

    const secondCtrlF = new KeyboardEvent('keydown', {
      key: 'f',
      code: 'KeyF',
      ctrlKey: true,
      cancelable: true,
    });
    expect(shortcutRegistry.dispatchKeyboardEvent(secondCtrlF)).toBe(false);
    expect(secondCtrlF.defaultPrevented).toBe(false);
    expect(smartFind.isOpen()).toBe(false);
    unbindFind();

    // Verify voucher Ctrl+P PDF endpoint contract
    const pdfPromise = reportsService.printVoucherPdf('c1', 'vch-55', 'Voucher_PUR-0055.pdf');
    const vchPdfReq = httpMock.expectOne(
      (r) => r.method === 'POST' && r.url.endsWith('/api/v1/companies/c1/vouchers/vch-55/print-pdf'),
    );
    vchPdfReq.flush(new Blob(['%PDF-1.7 voucher'], { type: 'application/pdf' }));
    await pdfPromise;
    expect(reportsService.lastDownloadedPdfFilename).toBe('Voucher_PUR-0055.pdf');
  });

  it('BankingBrsWorkbenchComponent runs CSV import, Alt+M Auto-BRS match, inline Bankers Date reconcile, Cheque Book creation & OCR approve-convert', async () => {
    const fixture = TestBed.createComponent(BankingBrsWorkbenchComponent);
    fixture.componentRef.setInput('initialBankLedgerId', 'led-hdfc');
    fixture.detectChanges();

    const initBrs = httpMock.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url.endsWith('/api/v1/companies/c1/banking/brs') &&
        r.params.get('bankLedgerId') === 'led-hdfc',
    );
    initBrs.flush({
      bankLedgerId: 'led-hdfc',
      bankLedgerName: 'HDFC Bank Current A/c',
      asOfDate: '2026-03-31',
      balanceAsPerCompanyBooks: '250000.00',
      amountsNotReflectedInBankDr: '40000.00',
      amountsNotReflectedInBankCr: '25000.00',
      balanceAsPerBankComputed: '235000.00',
      statementClosingBalance: '250000.00',
      unreconciledAllocations: [
        {
          bankAllocationId: 'alloc-1',
          voucherId: 'vch-201',
          voucherNumber: 'PMT-0021',
          voucherTypeName: 'Payment',
          voucherDate: '2025-04-12',
          partyOrNarration: 'Kaveri Industrial Supplies',
          transactionType: 'Cheque',
          instrumentNo: '400101',
          instrumentDate: '2025-04-12',
          debitAmount: '0.00',
          creditAmount: '25000.00',
          bankersDate: null,
        },
        {
          bankAllocationId: 'alloc-2',
          voucherId: 'vch-202',
          voucherNumber: 'RCT-0019',
          voucherTypeName: 'Receipt',
          voucherDate: '2025-04-14',
          partyOrNarration: 'Arjun Components NEFT',
          transactionType: 'NEFT',
          instrumentNo: 'UTR998877',
          instrumentDate: '2025-04-14',
          debitAmount: '40000.00',
          creditAmount: '0.00',
          bankersDate: null,
        },
      ],
      unmatchedStatementLines: [],
      matches: [],
    });
    await flushMicrotasks();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="brs-company-books-bal"]')?.textContent).toContain(
      '2,50,000.00',
    );

    // 1. Import CSV statement
    comp.stmtFormat = 'CSV';
    comp.stmtRawContent = '2025-04-13,400101,CHQ PAID,25000.00,0.00';
    const importPromise = comp.submitStatementImport();
    const stmtReq = httpMock.expectOne(
      (r) => r.method === 'POST' && r.url.endsWith('/api/v1/companies/c1/banking/statements/import'),
    );
    expect(stmtReq.request.body.sourceFormat).toBe('CSV');
    stmtReq.flush({
      statementImportId: 'imp-stmt-1',
      bankLedgerId: 'led-hdfc',
      fileName: 'hdfc_april_2025.csv',
      sourceFormat: 'CSV',
      importedLinesCount: 1,
    });
    await flushMicrotasks();
    const reloadAfterImport = httpMock.expectOne(
      (r) => r.method === 'GET' && r.url.endsWith('/api/v1/companies/c1/banking/brs'),
    );
    reloadAfterImport.flush(comp.brsSummary());
    await importPromise;
    fixture.detectChanges();

    // 2. Alt+M triggers Auto-BRS Match
    shortcutRegistry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'm', code: 'KeyM', altKey: true }),
    );
    const autoMatchReq = httpMock.expectOne(
      (r) => r.method === 'POST' && r.url.endsWith('/api/v1/companies/c1/banking/brs/auto-match'),
    );
    expect(autoMatchReq.request.body).toEqual({
      bankLedgerId: 'led-hdfc',
      statementImportId: 'imp-stmt-1',
      dateWindowDays: 3,
    });
    autoMatchReq.flush({
      matchedCount: 1,
      remainingUnreconciledCount: 1,
      summary: {
        bankLedgerId: 'led-hdfc',
        bankLedgerName: 'HDFC Bank Current A/c',
        asOfDate: '2026-03-31',
        balanceAsPerCompanyBooks: '250000.00',
        amountsNotReflectedInBankDr: '40000.00',
        amountsNotReflectedInBankCr: '0.00',
        balanceAsPerBankComputed: '210000.00',
        statementClosingBalance: '210000.00',
        unreconciledAllocations: [comp.brsSummary()!.unreconciledAllocations[1]],
        unmatchedStatementLines: [],
        matches: [],
      },
    });
    await flushMicrotasks();
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="brs-reconciled-badge"]')?.textContent).toContain(
      'Reconciled (0.00 Diff)',
    );

    // 3. Inline manual reconcile on remaining allocation
    comp.setBankersDate('alloc-2', '2025-04-15');
    const manualPromise = comp.manualReconcile(comp.brsSummary()!.unreconciledAllocations[0]);
    const recReq = httpMock.expectOne(
      (r) => r.method === 'PUT' && r.url.endsWith('/api/v1/companies/c1/banking/brs/reconcile'),
    );
    expect(recReq.request.body).toEqual({
      bankAllocationId: 'alloc-2',
      bankersDate: '2025-04-15',
      statementLineId: null,
    });
    recReq.flush({ reconciled: true });
    await manualPromise;
    fixture.detectChanges();
    expect(comp.brsSummary()!.unreconciledAllocations.length).toBe(0);

    // 4. Switch to Cheque Books tab & create 6-digit range
    comp.switchTab('CHEQUE_BOOKS');
    httpMock
      .expectOne((r) => r.method === 'GET' && r.url.endsWith('/api/v1/companies/c1/banking/cheque-books'))
      .flush([]);
    await flushMicrotasks();

    comp.cbBankLedgerId = 'led-hdfc';
    comp.cbBookName = 'HDFC Main Series 2025';
    comp.cbFromLeaf = '400101';
    comp.cbToLeaf = '400150';
    const cbPromise = comp.createChequeBook();
    const cbPost = httpMock.expectOne(
      (r) => r.method === 'POST' && r.url.endsWith('/api/v1/companies/c1/banking/cheque-books'),
    );
    cbPost.flush({
      id: 'cb-1',
      bankLedgerId: 'led-hdfc',
      bookName: 'HDFC Main Series 2025',
      fromLeafNo: '400101',
      toLeafNo: '400150',
      totalLeaves: 50,
      availableLeaves: 50,
    });
    await cbPromise;
    fixture.detectChanges();
    expect(comp.chequeBooks().length).toBe(1);

    // 5. Switch to Docs by Ira OCR Queue & approve-convert staged document
    comp.switchTab('OCR_QUEUE');
    httpMock
      .expectOne(
        (r) => r.method === 'GET' && r.url.endsWith('/api/v1/companies/c1/banking/ocr-staging/documents'),
      )
      .flush([
        {
          id: 'ocr-99',
          fileName: 'kaveri_inv_088.pdf',
          documentType: 'PURCHASE_INVOICE',
          supplierName: 'Kaveri Industrial Supplies',
          supplierGstin: '33AABCK1111F1Z9',
          invoiceNumber: 'KIS/25-26/088',
          invoiceDate: '2025-04-20',
          totalAmount: '11800.00',
          confidencePct: '98.50',
          reviewStatus: 'PENDING_REVIEW',
        },
      ]);
    await flushMicrotasks();
    fixture.detectChanges();

    const approvePromise = comp.approveAndConvertOcr(comp.ocrDocs()[0]);
    const ocrApproveReq = httpMock.expectOne(
      (r) =>
        r.method === 'POST' &&
        r.url.endsWith('/api/v1/companies/c1/banking/ocr-staging/documents/ocr-99/approve-convert'),
    );
    ocrApproveReq.flush({
      documentId: 'ocr-99',
      convertedVoucherId: 'vch-draft-701',
      status: 'APPROVED_CONVERTED',
    });
    await approvePromise;
    fixture.detectChanges();

    expect(comp.ocrDocs()[0].reviewStatus).toBe('APPROVED_CONVERTED');
    expect(comp.ocrDocs()[0].convertedVoucherId).toBe('vch-draft-701');
  });

  it('MigrationAndExchangeComponent stages .1800 snapshot, verifies 0.00 variance Trial Balance proof, commits atomically, and runs XML / JSONEx exchange', async () => {
    const fixture = TestBed.createComponent(MigrationAndExchangeComponent);
    fixture.detectChanges();

    httpMock
      .expectOne((r) => r.method === 'GET' && r.url.endsWith('/api/v1/companies/c1/imports'))
      .flush([]);
    await flushMicrotasks();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    const host = fixture.nativeElement as HTMLElement;

    comp.snapshotPathInput = '/var/moneycraft/staging/100000';
    const stagePromise = comp.stageSnapshot();
    const stageReq = httpMock.expectOne(
      (r) =>
        r.method === 'POST' &&
        (r.url.endsWith('/api/v1/companies/c1/imports/legacy-1800/stage') ||
          r.url.endsWith('/api/v1/companies/c1/imports/tally-1800/stage')),
    );
    expect(stageReq.request.body.snapshotDirectoryPath).toBe('/var/moneycraft/staging/100000');
    stageReq.flush({
      jobId: 'mig-job-100',
      status: 'STAGED_VERIFIED',
      sourceCompanyName: 'Dakshin Legacy Tally 7.2',
      sector0Verified: true,
      cmpSaveParityVerified: true,
      sourceGroupCount: 28,
      stagedGroupCount: 28,
      sourceLedgerCount: 42,
      stagedLedgerCount: 42,
      sourceStockItemCount: 15,
      stagedStockItemCount: 15,
      sourceVoucherCount: 128,
      stagedVoucherCount: 128,
      sourceGrossDebit: '760448.00',
      stagedGrossDebit: '760448.00',
      sourceGrossCredit: '760448.00',
      stagedGrossCredit: '760448.00',
      sourceNetTrialBalance: '338580.00',
      stagedNetTrialBalance: '338580.00',
      varianceAmount: '0.00',
      isZeroVariance: true,
      proofSha256Hex: '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08',
      editLogBaselineVersion: 1,
    });
    await flushMicrotasks();
    httpMock
      .expectOne((r) => r.method === 'GET' && r.url.endsWith('/api/v1/companies/c1/imports'))
      .flush([
        {
          id: 'mig-job-100',
          sourceCompanyName: 'Dakshin Legacy Tally 7.2',
          snapshotDirectoryPath: '/var/moneycraft/staging/100000',
          status: 'STAGED_VERIFIED',
          varianceAmount: '0.00',
          isZeroVariance: true,
        },
      ]);
    await stagePromise;
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="migrate-proof-card"]')).toBeTruthy();
    expect(host.querySelector('[data-testid="migrate-variance-badge"]')?.textContent).toContain(
      'ZERO VARIANCE',
    );
    expect(host.querySelector('[data-testid="migrate-source-net-tb"]')?.textContent).toContain(
      '3,38,580.00',
    );
    expect(host.querySelector('[data-testid="migrate-staged-net-tb"]')?.textContent).toContain(
      '3,38,580.00',
    );
    const hashEl = host.querySelector('[data-testid="migrate-proof-hash"]');
    expect(hashEl?.textContent).toContain('9f86d081884c7d65');
    expect(hashEl?.querySelector('.mc-code')?.textContent).not.toContain('₹');

    // Commit atomic promotion
    const commitPromise = comp.commitActiveProof();
    const commitReq = httpMock.expectOne(
      (r) =>
        r.method === 'POST' &&
        r.url.endsWith('/api/v1/companies/c1/imports/mig-job-100/commit'),
    );
    commitReq.flush({ ...comp.activeProof(), status: 'COMMITTED' });
    await flushMicrotasks();
    httpMock
      .expectOne((r) => r.method === 'GET' && r.url.endsWith('/api/v1/companies/c1/imports'))
      .flush([]);
    await commitPromise;
    fixture.detectChanges();
    expect(comp.activeProof()?.status).toBe('COMMITTED');

    // Switch to IMPORT_EXPORT tab & run JSONEx export + XML import
    comp.switchTab('IMPORT_EXPORT');
    httpMock
      .expectOne(
        (r) => r.method === 'GET' && r.url.endsWith('/api/v1/companies/c1/exchange/templates'),
      )
      .flush([]);
    await flushMicrotasks();
    fixture.detectChanges();

    comp.selectedFormat.set('JSONEX');
    const exportPromise = comp.runExport();
    const expReq = httpMock.expectOne(
      (r) => r.method === 'POST' && r.url.endsWith('/api/v1/companies/c1/exports/jsonex'),
    );
    expect(expReq.request.body.entityScope).toBe('ALL');
    expReq.flush({
      format: 'JSONEX',
      fileName: 'Dakshin_Rel72.json',
      contentType: 'application/json',
      content: '{"schema":"TallyPrime.JSONEx.7.2","vouchers":[]}',
      recordCount: 12,
    });
    await exportPromise;
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="exchange-export-preview"]')?.textContent).toContain(
      'TallyPrime.JSONEx.7.2',
    );

    comp.selectedFormat.set('XML');
    comp.importMasterDuplicateRule = 'COMBINE_OPENING_BALANCES';
    comp.importOverwriteVoucherByGuid = true;
    comp.importExceptionBehavior = 'RECORD_EXCEPTIONS_AND_IMPORT';
    comp.importContent = '<ENVELOPE><HEADER><TALLYREQUEST>Import Data</TALLYREQUEST></HEADER></ENVELOPE>';
    const impPromise = comp.runImport();
    const impReq = httpMock.expectOne(
      (r) => r.method === 'POST' && r.url.endsWith('/api/v1/companies/c1/imports/xml'),
    );
    expect(impReq.request.body.rawXml).toContain('<ENVELOPE>');
    expect(impReq.request.body.masterDuplicateRule).toBe('COMBINE_OPENING_BALANCES');
    expect(impReq.request.body.overwriteVoucherByGuid).toBe(true);
    expect(impReq.request.body.exceptionBehavior).toBe('RECORD_EXCEPTIONS_AND_IMPORT');
    impReq.flush({
      status: 'COMMITTED',
      format: 'XML',
      groupsImported: 0,
      ledgersImported: 2,
      stockItemsImported: 1,
      vouchersImported: 4,
      exceptionsRecorded: 1,
      exceptions: [
        {
          exceptionId: 'exc-loop3-01',
          entityType: 'VOUCHER',
          identifier: 'GLD-BAD-003',
          errorCode: 'MC-EXG-UNBALANCED-VOUCHER',
          message: 'Unbalanced voucher lines: DR 450.00 != CR 200.00',
          resolved: false,
        },
      ],
      message: 'Imported Tally XML envelope with -Dr/+Cr sign parity.',
    });
    await impPromise;
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="exchange-import-result"]')?.textContent).toContain(
      'Vouchers: 4',
    );
    expect(comp.importExceptions().length).toBe(1);
    expect(host.querySelector('[data-testid="exchange-exceptions-table"]')?.textContent).toContain(
      'GLD-BAD-003',
    );

    // Loop 3: Resolve Import Exception via UI (`Created due to Data Exception Resolution`)
    comp.resolveDebitLedgerName = 'Alpha Traders';
    comp.resolveCreditLedgerName = 'Gold Sales A/c';
    comp.resolveAmount = '450.00';
    const resolvePromise = comp.resolveException('exc-loop3-01');
    const resolveReq = httpMock.expectOne(
      (r) =>
        r.method === 'POST' &&
        r.url.endsWith('/api/v1/companies/c1/exchange/exceptions/exc-loop3-01/resolve'),
    );
    expect(resolveReq.request.body.debitLedgerName).toBe('Alpha Traders');
    expect(resolveReq.request.body.creditLedgerName).toBe('Gold Sales A/c');
    expect(resolveReq.request.body.amount).toBe('450.00');
    resolveReq.flush({
      exceptionId: 'exc-loop3-01',
      entityType: 'VOUCHER',
      identifier: 'GLD-BAD-003',
      errorCode: 'MC-EXG-UNBALANCED-VOUCHER',
      message: 'Unbalanced voucher lines: DR 450.00 != CR 200.00',
      resolved: true,
      resolvedEntityId: 'vch-resolved-01',
    });
    await resolvePromise;
    fixture.detectChanges();
    expect(comp.importExceptions()[0].resolved).toBe(true);

    // Loop 3: Sample Excel Files selector & download (`Alt+L`)
    comp.selectedSampleExcelType = 'ACCOUNTING_MASTERS';
    const samplePromise = comp.downloadSampleExcelFile(comp.selectedSampleExcelType);
    const sampleReq = httpMock.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url.endsWith('/api/v1/companies/c1/exchange/sample-excel?type=ACCOUNTING_MASTERS'),
    );
    sampleReq.flush(new Blob(['PK-sample-xlsx'], { type: 'application/octet-stream' }));
    await samplePromise;
    fixture.detectChanges();

    // Loop 3: Mapping Template client-side column validation (`A..ZZ`, `from <= to`), creation (`ALL_COMPANIES`), and deletion
    comp.newTemplateName = 'Sales Register Template';
    comp.newTemplateFromColumn = '99';
    comp.newTemplateToColumn = 'D';
    await comp.saveNewTemplate();
    expect(comp.errorBanner()).toContain('Enter a value from A to ZZ');

    comp.newTemplateFromColumn = 'Z';
    comp.newTemplateToColumn = 'A';
    await comp.saveNewTemplate();
    expect(comp.errorBanner()).toContain(
      "the value in the 'to' field should be greater than or equal to the value in the 'from' field",
    );

    comp.newTemplateFromColumn = 'A';
    comp.newTemplateToColumn = 'F';
    comp.newTemplateCompanyScope = 'ALL_COMPANIES';
    comp.newTemplateExcludeRowColumn = 'Day';
    comp.newTemplateExcludeRowValue = 'Sunday';
    const saveTplPromise = comp.saveNewTemplate();
    const saveTplReq = httpMock.expectOne(
      (r) => r.method === 'POST' && r.url.endsWith('/api/v1/companies/c1/exchange/templates'),
    );
    expect(saveTplReq.request.body.templateScope).toBe('ALL_COMPANIES');
    expect(saveTplReq.request.body.fromColumn).toBe('A');
    expect(saveTplReq.request.body.toColumn).toBe('F');
    expect(saveTplReq.request.body.excludeRowColumn).toBe('Day');
    saveTplReq.flush({
      id: 'tpl-shared-01',
      templateName: 'Sales Register Template',
      entityScope: 'VOUCHERS',
      sheetName: 'Sheet1',
      headerRowIndex: 1,
      templateScope: 'ALL_COMPANIES',
      fromColumn: 'A',
      toColumn: 'F',
      excludeRowColumn: 'Day',
      excludeRowOperator: 'CONTAINS',
      excludeRowValue: 'Sunday',
      columnMappings: { amount: 'Amount' },
    });
    await saveTplPromise;
    fixture.detectChanges();
    expect(comp.templates().length).toBe(1);
    expect(comp.templates()[0].templateScope).toBe('ALL_COMPANIES');
    expect(comp.templates()[0].fromColumn).toBe('A');
    expect(comp.templates()[0].toColumn).toBe('F');

    const delTplPromise = comp.deleteMappingTemplate('tpl-shared-01');
    const delTplReq = httpMock.expectOne(
      (r) =>
        r.method === 'DELETE' &&
        r.url.endsWith('/api/v1/companies/c1/exchange/templates/tpl-shared-01'),
    );
    delTplReq.flush(null);
    await delTplPromise;
    fixture.detectChanges();
    expect(comp.templates().length).toBe(0);

    // Loop 4: Client/Server Data Synchronization (`Alt+Z`) self-sync GUID guard, rule creation & execution
    comp.newSyncRuleName = 'Self Sync Attempt';
    comp.newSyncTargetCompanyGuid = 'c1';
    await comp.createNewSyncRule();
    expect(comp.errorBanner()).toContain(
      'Client and Server companies have same GUID. Cannot proceed with synchronization.',
    );

    comp.newSyncRuleName = 'HO to Branch Sync';
    comp.newSyncRuleType = 'FULL_SYNC';
    comp.newSyncDirection = 'SEND_ONLY';
    comp.newSyncTargetCompanyGuid = 'c2-branch-guid';
    comp.newSyncTargetCompanyName = 'Branch Sync Target Co';
    const createSyncPromise = comp.createNewSyncRule();
    const createSyncReq = httpMock.expectOne(
      (r) => r.method === 'POST' && r.url.endsWith('/api/v1/companies/c1/exchange/sync-rules'),
    );
    expect(createSyncReq.request.body.ruleName).toBe('HO to Branch Sync');
    expect(createSyncReq.request.body.targetCompanyGuid).toBe('c2-branch-guid');
    createSyncReq.flush({
      id: 'sync-rule-1',
      companyId: 'c1',
      ruleName: 'HO to Branch Sync',
      ruleType: 'FULL_SYNC',
      direction: 'SEND_ONLY',
      targetCompanyId: 'c2-branch-guid',
      targetCompanyGuid: 'c2-branch-guid',
      targetCompanyName: 'Branch Sync Target Co',
      serverUrl: 'http://localhost:18080',
      masterDuplicateRule: 'MODIFY_WITH_NEW_DATA',
      overwriteVoucherByGuid: true,
      lastSyncAlterId: 0,
      active: true,
    });
    await createSyncPromise;
    fixture.detectChanges();
    expect(comp.syncRules().length).toBe(1);

    const execSyncPromise = comp.runSyncRule('sync-rule-1');
    const execSyncReq = httpMock.expectOne(
      (r) =>
        r.method === 'POST' &&
        r.url.endsWith('/api/v1/companies/c1/exchange/sync-rules/sync-rule-1/execute'),
    );
    execSyncReq.flush({
      sessionId: 'sync-sess-1',
      ruleId: 'sync-rule-1',
      ruleName: 'HO to Branch Sync',
      sourceCompanyId: 'c1',
      targetCompanyId: 'c2-branch-guid',
      status: 'COMPLETED',
      previousAlterIdWatermark: 0,
      newAlterIdWatermark: 24,
      mastersSynced: 5,
      vouchersSynced: 4,
      exceptionsRecorded: 0,
      message: 'Synchronising Company "Dakshin Precision" for Rule "HO to Branch Sync" completed.',
    });
    await execSyncPromise;
    fixture.detectChanges();
    expect(comp.lastSyncSessionResult()?.newAlterIdWatermark).toBe(24);
    expect(comp.syncRules()[0].lastSyncAlterId).toBe(24);

    // Loop 4: TallyAudit & MCA Edit Log Activity Summary (`entityType`, `onlyAltered`, `username`)
    comp.editLogEntityTypeFilter = 'STOCK_ITEM';
    comp.editLogOnlyAltered = true;
    comp.editLogUsernameFilter = 'owner';
    const loadElPromise = comp.loadEditLogSummary();
    const loadElReq = httpMock.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url.endsWith(
          '/api/v1/companies/c1/exchange/edit-log-summary?entityType=STOCK_ITEM&onlyAltered=true&username=owner',
        ),
    );
    loadElReq.flush({
      companyId: 'c1',
      entityTypeFilter: 'STOCK_ITEM',
      onlyAltered: true,
      totalItems: 1,
      alteredItems: 1,
      items: [
        {
          entityType: 'STOCK_ITEM',
          entityId: 'item-gold-1',
          displayName: 'Gold Widget',
          voucherNumber: null,
          activityType: 'ALTERED',
          alteredDueTo: 'MASTER_ALTERATION',
          versionCount: 2,
          latestAlterId: 18,
          lastModifiedBy: 'owner',
          lastModifiedAt: '2026-04-12T10:00:00Z',
        },
      ],
    });
    await loadElPromise;
    fixture.detectChanges();
    expect(comp.editLogSummary()?.items.length).toBe(1);
    expect(
      host.querySelector('[data-testid="exchange-edit-log-summary-table"]')?.textContent,
    ).toContain('Gold Widget');

    // Loop 5: Multi-Account Reports (`CONFIRMATION_OF_ACCOUNTS` & `REMINDER_LETTER`) with 4-slab ageing
    comp.multiAccountReportType = 'REMINDER_LETTER';
    comp.multiAccountExportFormat = 'HTML';
    const maPromise = comp.runMultiAccountReportExport();
    const maReq = httpMock.expectOne(
      (r) => r.method === 'POST' && r.url.endsWith('/api/v1/companies/c1/exports/multi-account-report'),
    );
    expect(maReq.request.body.reportType).toBe('REMINDER_LETTER');
    maReq.flush({
      companyId: 'c1',
      companyName: 'Dakshin Precision',
      reportType: 'REMINDER_LETTER',
      fromDate: '2025-04-01',
      toDate: '2026-03-31',
      format: 'HTML',
      partyCount: 1,
      statements: [
        {
          ledgerId: 'led-alpha',
          partyName: 'Alpha Traders',
          groupName: 'Sundry Debtors',
          gstin: '33AABCU9603R1ZM',
          openingBalance: '1400.00',
          openingSide: 'DR',
          periodDebit: '2150.00',
          periodCredit: '0.00',
          closingBalance: '3550.00',
          closingSide: 'DR',
          slab0To30: '1500.00',
          slab31To60: '500.00',
          slab61To90: '550.00',
          slabOver90: '1000.00',
        },
      ],
      renderedContent: '<html><body>Alpha Traders 1000.00</body></html>',
    });
    await maPromise;
    fixture.detectChanges();
    expect(comp.multiAccountReportResponse()?.partyCount).toBe(1);
    expect(host.querySelector('[data-testid="exchange-multi-account-table"]')?.textContent).toContain(
      'Alpha Traders',
    );

    // Loop 5: Interactive Dashboard Tiles Workbench (`Add Tile`, `Remove Tile`, `Expand Tile`, `Vertical View`)
    const tilesPromise = comp.loadDashboardTiles();
    const tilesReq = httpMock.expectOne(
      (r) => r.method === 'POST' && r.url.endsWith('/api/v1/companies/c1/exports/dashboard-tiles'),
    );
    tilesReq.flush({
      companyId: 'c1',
      companyName: 'Dakshin Precision',
      fromDate: '2025-04-01',
      toDate: '2026-03-31',
      format: 'HTML',
      totalTiles: 2,
      tiles: [
        {
          tileCode: 'TRIAL_BALANCE_SUMMARY',
          tileTitle: 'Trial Balance Summary',
          primaryMetricLabel: 'Total Debit',
          primaryMetricValue: '3,38,580.00',
          breakdown: { totalDebit: '338580.00', totalCredit: '338580.00', netVariance: '0.00' },
        },
        {
          tileCode: 'RECEIVABLES_AND_PAYABLES',
          tileTitle: 'Receivables & Payables',
          primaryMetricLabel: 'Sundry Debtors Closing',
          primaryMetricValue: '1,31,564.00',
          breakdown: { sundryDebtors: '131564.00', side: 'DR' },
        },
      ],
      renderedContent: '<html><body>Dashboard Tiles</body></html>',
    });
    await tilesPromise;
    fixture.detectChanges();
    expect(comp.visibleDashboardTiles().length).toBe(2);

    comp.toggleExpandDashboardTile('TRIAL_BALANCE_SUMMARY');
    fixture.detectChanges();
    expect(
      host.querySelector('[data-testid="expanded-tile-details-TRIAL_BALANCE_SUMMARY"]')?.textContent,
    ).toContain('338580.00');

    comp.toggleDashboardVerticalView();
    expect(comp.dashboardVerticalView()).toBe(true);

    comp.removeDashboardTile('TRIAL_BALANCE_SUMMARY');
    fixture.detectChanges();
    expect(comp.visibleDashboardTiles().length).toBe(1);
    expect(comp.removedDashboardTiles().length).toBe(1);

    comp.restoreAllDashboardTiles();
    fixture.detectChanges();
    expect(comp.visibleDashboardTiles().length).toBe(2);

    // Loop 5: Company Data Verification (`Alt+Y` `runCompanyDataVerification`)
    const verifyPromise = comp.runCompanyDataVerification();
    const verifyReq = httpMock.expectOne(
      (r) => r.method === 'GET' && r.url.endsWith('/api/v1/companies/c1/exchange/data-verification'),
    );
    verifyReq.flush({
      companyId: 'c1',
      companyName: 'Dakshin Precision',
      trialBalanceDifference: '0.00',
      unbalancedVoucherCount: 0,
      unitMismatchCount: 0,
      missingHsnOrGstinCount: 0,
      pendingImportExceptionCount: 0,
      readyForSplitOrSync: true,
      checks: [
        {
          checkCode: 'DOUBLE_ENTRY_GL_PARITY',
          checkTitle: 'General Ledger Double-Entry Balance (DR == CR)',
          status: 'PASS',
          issueCount: 0,
          detail: 'Posted GL Dr/Cr variance: 0.00',
        },
      ],
    });
    await verifyPromise;
    fixture.detectChanges();
    expect(comp.dataVerificationReport()?.readyForSplitOrSync).toBe(true);
    expect(
      host.querySelector('[data-testid="exchange-data-verify-ready-badge"]')?.textContent,
    ).toContain('READY FOR SPLIT / SYNC');
  });

  it('CashFlowAndRatiosComponent renders Operating/Investing/Financing Cash Flow and switches to Tally two-pane Ratio Analysis', async () => {
    const fixture = TestBed.createComponent(CashFlowAndRatiosComponent);
    fixture.componentRef.setInput('initialTab', 'CASH_FLOW');
    fixture.detectChanges();

    const cfReq = httpMock.expectOne(
      (r) => r.method === 'GET' && r.url.endsWith('/api/v1/companies/c1/reports/cash-flow'),
    );
    cfReq.flush({
      fromDate: '2025-04-01',
      toDate: '2026-03-31',
      openingCashAndBank: '100000.00',
      netOperatingCashFlow: '85000.00',
      investingCashFlow: '-25000.00',
      financingCashFlow: '50000.00',
      closingCashAndBank: '210000.00',
      activities: [
        {
          activityType: 'OPERATING',
          particulars: 'Receipts from Sundry Debtors',
          inflow: '125000.00',
          outflow: '40000.00',
          netFlow: '85000.00',
        },
      ],
      monthlySummary: [
        {
          monthLabel: 'April 2025',
          inflow: '175000.00',
          outflow: '65000.00',
          netFlow: '110000.00',
          closingBalance: '210000.00',
        },
      ],
    });
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="cf-net-operating"]')?.textContent).toContain(
      '85,000.00',
    );
    expect(host.querySelector('[data-testid="cf-closing-cash"]')?.textContent).toContain(
      '2,10,000.00',
    );

    // Switch to Ratio Analysis tab
    fixture.componentInstance.switchTab('RATIOS');
    const raReq = httpMock.expectOne(
      (r) => r.method === 'GET' && r.url.endsWith('/api/v1/companies/c1/reports/ratios'),
    );
    raReq.flush({
      fromDate: '2025-04-01',
      toDate: '2026-03-31',
      workingCapital: '259385.71',
      cashInHand: '40000.00',
      bankAccounts: '170000.00',
      sundryDebtors: '131564.00',
      sundryCreditors: '106564.00',
      salesAccounts: '125000.00',
      purchaseAccounts: '85000.00',
      stockInHand: '24385.71',
      netProfit: '4385.71',
      currentRatio: '3.43',
      quickRatio: '3.21',
      debtEquityRatio: '0.00',
      grossProfitPct: '39.51',
      netProfitPct: '3.51',
      operatingCostPct: '96.49',
      receivableTurnoverDays: '48.00',
      returnOnInvestmentPct: '0.87',
      returnOnWorkingCapitalPct: '1.69',
    });
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="ratios-two-pane"]')).toBeTruthy();
    expect(host.querySelector('[data-testid="ratio-working-capital"]')?.textContent).toContain(
      '2,59,385.71',
    );
    expect(host.querySelector('[data-testid="ratio-current"]')?.textContent).toContain('3.43 : 1');
  });
});
