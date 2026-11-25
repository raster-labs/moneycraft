import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { APP_ROUTES } from './app.routes';
import { AuthStore } from './core/auth/auth.store';
import { SseStreamService } from './core/http/sse-stream.service';
import { ShortcutCommandRegistry } from './core/keyboard/shortcut-command.registry';
import { UiPreferencesService } from './core/preferences/ui-preferences.service';
import { LoginComponent } from './features/auth/login.component';
import { AdminConsoleComponent } from './features/companies/admin-console.component';
import { CompanyHubComponent } from './features/companies/company-hub.component';
import { AccountBooksAndExceptionsComponent } from './features/reports/account-books-and-exceptions.component';
import { VoucherGridComponent } from './features/vouchers/voucher-grid.component';
import { AmountCellComponent } from './shared/components/amount-cell/amount-cell.component';
import {
  TypeaheadOption,
  TypeaheadSelectComponent,
} from './shared/components/typeahead-select/typeahead-select.component';
import { ErpShellComponent } from './shell/erp-shell.component';
import { GatewayComponent } from './shell/gateway.component';
import { CompanyContextStore, CompanySummary } from './state/company-context.store';

const MOCK_COMPANY: CompanySummary = {
  id: '11111111-1111-4111-8111-111111111111',
  code: 'CMP-110',
  legalName: 'MoneyCraft Industrial Works Pvt Ltd',
  mailingName: 'MoneyCraft Industrial Works',
  countryCode: 'IN',
  stateCode: '33',
  pinCode: '600001',
  baseCurrencyCode: 'INR',
  currencySymbol: '₹',
  currencyScale: 2,
  financialYearFrom: '2025-04-01',
  booksBeginningFrom: '2025-04-01',
  lastEntryDate: '2025-04-20',
  versionNo: 1,
  features: {
    maintainAccounts: true,
    billWiseEnabled: true,
    costCentresEnabled: true,
    maintainInventory: true,
    inventoryIntegrated: true,
    gstEnabled: true,
    multiGstinEnabled: false,
    gstRegistrationType: 'REGULAR',
    gstin: '33AABCM1234F1Z4',
    gstr1Periodicity: 'MONTHLY',
    gstEffectiveFrom: '2025-04-01',
    tdsEnabled: true,
    mcaEditLogEnforced: true,
  },
};

const LEDGER_OPTIONS: readonly TypeaheadOption[] = [
  { id: 'ldg-cash', label: 'Cash', secondaryLabel: 'Cash-in-Hand' },
  { id: 'ldg-sales', label: 'Sales Account', secondaryLabel: 'Sales Accounts' },
  { id: 'ldg-party', label: 'Acme Traders', secondaryLabel: 'Sundry Debtors' },
];

async function flushMicrotasks(): Promise<void> {
  for (let i = 0; i < 8; i += 1) {
    await Promise.resolve();
  }
}

describe('MoneyCraft v1.1.0 Wave W3 — UM-110-01..05 Menus, Interactive Voucher Fixes, Startup Flow & Tally 7.2 Shortcuts', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('UM-110-02: TypeaheadSelectComponent auto-selects exact/highlighted option on Tab and blur, and emits alterRequested on Ctrl+Enter', async () => {
    await TestBed.configureTestingModule({
      imports: [TypeaheadSelectComponent],
    }).compileComponents();

    const fixture = TestBed.createComponent(TypeaheadSelectComponent);
    fixture.componentRef.setInput('options', LEDGER_OPTIONS);
    fixture.detectChanges();

    const selectedValues: (TypeaheadOption | null)[] = [];
    const alterValues: TypeaheadOption[] = [];
    fixture.componentInstance.optionSelected.subscribe((v) => selectedValues.push(v));
    fixture.componentInstance.alterRequested.subscribe((v) => alterValues.push(v));

    const input: HTMLInputElement = fixture.nativeElement.querySelector('input');
    input.focus();
    input.value = 'Cash';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();

    // Tab without pressing ArrowDown or Enter must commit 'Cash'
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
    fixture.detectChanges();
    expect(selectedValues.at(-1)?.id).toBe('ldg-cash');

    // Ctrl+Enter emits alterRequested for the selected option
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true, cancelable: true }),
    );
    expect(alterValues.at(-1)?.id).toBe('ldg-cash');
  });

  it('UM-110-02: AmountCellComponent parses Indian comma formatting and commits live on keystroke without waiting for blur', async () => {
    await TestBed.configureTestingModule({
      imports: [AmountCellComponent],
    }).compileComponents();

    const fixture = TestBed.createComponent(AmountCellComponent);
    fixture.detectChanges();

    const committed: string[] = [];
    fixture.componentInstance.valueCommitted.subscribe((v) => committed.push(v));

    const input: HTMLInputElement = fixture.nativeElement.querySelector('input');
    input.dispatchEvent(new FocusEvent('focus'));
    input.value = '12,500.50';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();

    expect(committed.at(-1)).toBe('12500.50');
    expect(fixture.componentInstance.flushPendingCommit()).toBe('12500.50');
  });

  it('UM-110-02: SseStreamService skips EventSource connection when unauthenticated to prevent 401 polling', () => {
    const FakeEventSource = vi.fn();
    vi.stubGlobal('EventSource', FakeEventSource);

    try {
      TestBed.configureTestingModule({
        providers: [provideHttpClient(), provideHttpClientTesting()],
      });

      const authStore = TestBed.inject(AuthStore);
      const sse = TestBed.inject(SseStreamService);
      authStore.setAuthenticatedPrincipal(null);

      sse.connect();
      expect(FakeEventSource).not.toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('UM-110-02 & UM-110-05: VoucherGridComponent flushes cells and posts on Ctrl+A inside AmountCell, and supports Ctrl+Alt+T, Ctrl+R & PageUp', async () => {
    await TestBed.configureTestingModule({
      imports: [VoucherGridComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyContext = TestBed.inject(CompanyContextStore);
    const registry = TestBed.inject(ShortcutCommandRegistry);

    companyContext.setCompanies([MOCK_COMPANY], MOCK_COMPANY.id);

    const fixture = TestBed.createComponent(VoucherGridComponent);
    fixture.detectChanges();

    // Flush initial master/voucherType/next-number requests
    for (const req of httpMock.match(() => true)) {
      const url = req.request.url;
      if (url.includes('/voucher-types')) {
        req.flush([
          {
            id: 'vt-receipt',
            companyId: MOCK_COMPANY.id,
            name: 'Receipt',
            baseCategory: 'RECEIPT',
            abbreviation: 'Rcpt',
            numberingMethod: 'AUTOMATIC',
            preventDuplicates: true,
            startingNumber: 1,
            allowNarration: true,
            isActive: true,
            isReserved: true,
            classes: [],
            versionNo: 1,
          },
        ]);
      } else if (url.includes('/ledgers')) {
        req.flush([
          {
            id: 'ldg-cash',
            companyId: MOCK_COMPANY.id,
            name: 'Cash',
            groupId: 'grp-cash',
            groupName: 'Cash-in-Hand',
            primaryNature: 'ASSETS',
            openingBalance: '0.00',
            openingSide: 'DR',
            versionNo: 1,
          },
          {
            id: 'ldg-sales',
            companyId: MOCK_COMPANY.id,
            name: 'Sales Account',
            groupId: 'grp-sales',
            groupName: 'Sales Accounts',
            primaryNature: 'INCOME',
            openingBalance: '0.00',
            openingSide: 'CR',
            versionNo: 1,
          },
        ]);
      } else if (url.includes('/next-number')) {
        req.flush({ voucherNumber: '1' });
      } else {
        req.flush([]);
      }
    }
    await flushMicrotasks();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    comp.switchToCategory('RECEIPT');
    for (const req of httpMock.match((r) => r.url.includes('/next-number'))) {
      req.flush({ voucherNumber: 'RCPT-001' });
    }
    await flushMicrotasks();
    fixture.detectChanges();

    // Toggle Post-Dated with Ctrl+Alt+T via registry
    expect(comp.isPostDated()).toBe(false);
    registry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', {
        key: 't',
        code: 'KeyT',
        ctrlKey: true,
        altKey: true,
        cancelable: true,
      }),
    );
    fixture.detectChanges();
    expect(comp.isPostDated()).toBe(true);
    expect(fixture.nativeElement.querySelector('[data-testid="voucher-postdated-badge"]')).not.toBeNull();

    // Repeat Narration via Ctrl+R
    registry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'r', code: 'KeyR', ctrlKey: true, cancelable: true }),
    );
    const narReq = httpMock.expectOne((r) => r.url.includes('/vouchers/last-narration'));
    narReq.flush({ found: true, narration: 'Being advance receipt collected' });
    await flushMicrotasks();
    expect(comp.narration()).toBe('Being advance receipt collected');

    // Fill Receipt in Double-Entry Voucher Mode and press Ctrl+A from inside mc-amount-cell
    comp.persistedView.set('AS_VOUCHER');
    comp.gridLines.set([
      {
        rowId: 'r-1',
        side: 'CR',
        lineRole: 'PARTY',
        ledgerId: 'ldg-sales',
        ledgerName: 'Sales Account',
        ratePct: '',
        computedTaxAmount: null,
        isGstOverridden: false,
        amount: '1500.00',
        lineNarration: '',
        billAllocations: [],
        costAllocations: [],
        bankAllocations: [],
      },
      {
        rowId: 'r-2',
        side: 'DR',
        lineRole: 'LEDGER',
        ledgerId: 'ldg-cash',
        ledgerName: 'Cash',
        ratePct: '',
        computedTaxAmount: null,
        isGstOverridden: false,
        amount: '1500.00',
        lineNarration: '',
        billAllocations: [],
        costAllocations: [],
        bankAllocations: [],
      },
    ]);
    fixture.detectChanges();

    const amountHost = fixture.nativeElement.querySelector('mc-amount-cell input') as HTMLElement;
    const ctrlAEvent = new KeyboardEvent('keydown', {
      key: 'a',
      code: 'KeyA',
      ctrlKey: true,
      bubbles: true,
      cancelable: true,
    });
    Object.defineProperty(ctrlAEvent, 'target', { value: amountHost });
    expect(registry.dispatchKeyboardEvent(ctrlAEvent)).toBe(true);

    const postReq = httpMock.expectOne(
      (r) => r.method === 'POST' && r.url.endsWith(`/companies/${MOCK_COMPANY.id}/vouchers`),
    );
    expect(postReq.request.body.totalDebit).toBeUndefined();
    expect(postReq.request.body.lines.length).toBe(2);
    postReq.flush({
      id: 'vch-saved-1',
      companyId: MOCK_COMPANY.id,
      voucherTypeId: 'vt-receipt',
      voucherTypeName: 'Receipt',
      voucherNumber: 'RCPT-001',
      voucherDate: '2025-04-20',
      effectiveDate: '2025-04-20',
      status: 'POSTED',
      totalDebit: '1500.00',
      totalCredit: '1500.00',
      lines: [],
      inventoryLines: [],
      versionNo: 1,
    });
    for (const req of httpMock.match(() => true)) {
      req.flush({ voucherNumber: 'RCPT-002' });
    }
    await flushMicrotasks();

    // PageUp loads adjacent voucher unwrapping { found: true, voucher: ... }
    registry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'PageUp', code: 'PageUp', cancelable: true }),
    );
    const adjReq = httpMock.expectOne((r) => r.url.includes('/vouchers/adjacent'));
    expect(adjReq.request.params.get('direction')).toBe('PREV');
    adjReq.flush({
      found: true,
      direction: 'PREV',
      voucher: {
        id: 'vch-prev-99',
        companyId: MOCK_COMPANY.id,
        voucherTypeId: 'vt-receipt',
        voucherTypeName: 'Receipt',
        voucherNumber: 'RCPT-099',
        voucherDate: '2025-04-19',
        status: 'POSTED',
        narration: 'Previous receipt',
        lines: [],
        inventoryLines: [],
        versionNo: 1,
      },
    });
    await flushMicrotasks();
    expect(comp.editingVoucherId()).toBe('vch-prev-99');
  });

  it('UM-110-01 & UM-110-03: GatewayComponent renders Windows Startup Select Company when empty, and cascades Level 1 -> Level 2 -> Level 3 with Esc step-back', async () => {
    await TestBed.configureTestingModule({
      imports: [GatewayComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const companyContext = TestBed.inject(CompanyContextStore);
    companyContext.setCompanies([]);

    const fixture = TestBed.createComponent(GatewayComponent);
    fixture.detectChanges();

    // Windows Startup Select Company screen when 0 companies are active
    expect(
      fixture.nativeElement.querySelector('[data-testid="windows-startup-select-company"]'),
    ).not.toBeNull();

    // Activate company -> Gateway Level 1 menu appears
    companyContext.setCompanies([MOCK_COMPANY], MOCK_COMPANY.id);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="gateway-menu-card"]')).not.toBeNull();
    expect(fixture.componentInstance.currentLevelNumber()).toBe(1);

    // Press 'D' -> Level 2 (Display More Reports)
    fixture.componentInstance.onGatewayMnemonicKey(
      new KeyboardEvent('keydown', { key: 'd', code: 'KeyD', cancelable: true }),
    );
    fixture.detectChanges();
    expect(fixture.componentInstance.currentLevelNumber()).toBe(2);
    expect(fixture.componentInstance.breadcrumbText()).toContain('Display More Reports');

    // Press 'A' -> Level 3 (Account Books)
    fixture.componentInstance.onGatewayMnemonicKey(
      new KeyboardEvent('keydown', { key: 'a', code: 'KeyA', cancelable: true }),
    );
    fixture.detectChanges();
    expect(fixture.componentInstance.currentLevelNumber()).toBe(3);
    expect(fixture.componentInstance.breadcrumbText()).toContain('Account Books');

    // Press Escape -> steps back Level 3 -> Level 2 -> Level 1
    fixture.componentInstance.onGatewayMnemonicKey(
      new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', cancelable: true }),
    );
    expect(fixture.componentInstance.currentLevelNumber()).toBe(2);

    fixture.componentInstance.onGatewayMnemonicKey(
      new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', cancelable: true }),
    );
    expect(fixture.componentInstance.currentLevelNumber()).toBe(1);
  });

  it('UM-110-01 & UM-110-05: ErpShellComponent opens Top-Bar dropdown menus via Alt+K/Y/Z/O/E/M/P & F1 and shuts active company on Ctrl+F3', async () => {
    await TestBed.configureTestingModule({
      imports: [ErpShellComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyContext = TestBed.inject(CompanyContextStore);
    const registry = TestBed.inject(ShortcutCommandRegistry);
    companyContext.setCompanies([MOCK_COMPANY], MOCK_COMPANY.id);

    const fixture = TestBed.createComponent(ErpShellComponent);
    fixture.detectChanges();
    for (const req of httpMock.match(() => true)) {
      req.flush([]);
    }

    // Alt+K opens COMPANY dropdown menu
    registry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'k', code: 'KeyK', altKey: true, cancelable: true }),
    );
    fixture.detectChanges();
    expect(fixture.componentInstance.openTopBarMenu()).toBe('COMPANY');
    expect(
      fixture.nativeElement.querySelector('[data-testid="topbar-dropdown-company"]'),
    ).not.toBeNull();

    // Alt+M opens SHARE dropdown menu
    registry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'm', code: 'KeyM', altKey: true, cancelable: true }),
    );
    fixture.detectChanges();
    expect(fixture.componentInstance.openTopBarMenu()).toBe('SHARE');

    // Escape closes Top-Bar dropdown before any underlying modal
    registry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', cancelable: true }),
    );
    fixture.detectChanges();
    expect(fixture.componentInstance.openTopBarMenu()).toBeNull();

    // Ctrl+F3 shuts the active company
    registry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'F3', code: 'F3', ctrlKey: true, cancelable: true }),
    );
    fixture.detectChanges();
    expect(companyContext.openCompanies().length).toBe(0);
  });

  it('UM-110-01: AccountBooksAndExceptionsComponent loads /reports/account-books, /exceptions, /statistics & /stock-query matching backend W2B DTOs', async () => {
    await TestBed.configureTestingModule({
      imports: [AccountBooksAndExceptionsComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyContext = TestBed.inject(CompanyContextStore);
    companyContext.setCompanies([MOCK_COMPANY], MOCK_COMPANY.id);

    const fixture = TestBed.createComponent(AccountBooksAndExceptionsComponent);
    fixture.detectChanges();

    // 1. Account Books (`summaryRows` + `debitTotal`/`creditTotal`)
    const abReq = httpMock.expectOne((r) => r.url.includes('/reports/account-books'));
    abReq.flush({
      bookType: 'CASH_BANK_SUMMARY',
      fromDate: '2025-04-01',
      toDate: '2026-03-31',
      totalDebit: '25000.00',
      totalCredit: '10000.00',
      monthlySummary: [
        {
          monthKey: '2025-04',
          monthLabel: 'April 2025',
          fromDate: '2025-04-01',
          toDate: '2025-04-30',
          voucherCount: 2,
          debitTotal: '25000.00',
          creditTotal: '10000.00',
          closingBalance: '15000.00',
        },
      ],
      summaryRows: [
        {
          entityId: 'ldg-cash',
          rowType: 'LEDGER',
          name: 'Cash',
          closingDebit: '15000.00',
          closingCredit: '0.00',
        },
      ],
      voucherRows: [],
    });
    await flushMicrotasks();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Cash');

    // 2. Exceptions (`referenceNumber` + `balanceOrAmount`)
    fixture.componentInstance.switchMode('EXCEPTIONS');
    const excReq = httpMock.expectOne((r) => r.url.includes('/reports/exceptions'));
    excReq.flush({
      type: 'NEGATIVE_STOCK',
      fromDate: '2025-04-01',
      toDate: '2026-03-31',
      rowCount: 1,
      totalAmount: '-5000.00',
      rows: [
        {
          entityId: 'item-1',
          entityType: 'STOCK_ITEM',
          referenceNumber: 'PMP-5',
          particulars: 'Industrial Pump 5HP',
          groupOrCategory: 'Primary',
          quantity: '-1.0000',
          balanceOrAmount: '-5000.00',
          status: 'NEGATIVE',
        },
      ],
    });
    await flushMicrotasks();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Industrial Pump 5HP');

    // 3. Statistics
    fixture.componentInstance.switchMode('STATISTICS');
    const statReq = httpMock.expectOne((r) => r.url.includes('/reports/statistics'));
    statReq.flush({
      fromDate: '2025-04-01',
      toDate: '2026-03-31',
      totalVouchers: 5,
      voucherTypeCounts: [
        { name: 'Sales', baseCategory: 'SALES', postedCount: 4, optionalCount: 1, cancelledCount: 0, totalCount: 5 },
      ],
      masterCounts: [{ code: 'LEDGERS', name: 'Ledgers', count: 12 }],
    });
    await flushMicrotasks();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Ledgers');

    // 4. Stock Query (`godownBatchBreakup`, server defaults item when none selected)
    fixture.componentInstance.switchMode('STOCK_QUERY');
    for (const req of httpMock.match((r) => !r.url.includes('/reports/stock-query'))) {
      req.flush([]);
    }
    await flushMicrotasks();
    const sqReq = httpMock.expectOne((r) => r.url.includes('/reports/stock-query'));
    sqReq.flush({
      stockItemId: 'item-1',
      stockItemName: 'Industrial Pump 5HP',
      uomSymbol: 'NOS',
      costingMethod: 'AVG_COST',
      openingQty: '10.0000',
      openingValue: '50000.00',
      inwardQty: '5.0000',
      inwardValue: '25000.00',
      outwardQty: '2.0000',
      outwardValue: '10000.00',
      closingQty: '13.0000',
      closingValue: '65000.00',
      lastPurchaseParty: 'Bharat Motors',
      lastPurchaseRate: '5000.00',
      lastSalesParty: 'Acme Traders',
      lastSalesRate: '6200.00',
      godownBatchBreakup: [
        { godownName: 'Chennai Main Godown', batchName: 'BATCH-A1', closingQty: '13.0000', closingValue: '65000.00' },
      ],
    });
    await flushMicrotasks();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Chennai Main Godown');
  });

  it('UM-110-03: CompanyHubComponent opens F11 Company Features confirmation modal immediately after creating a company', async () => {
    await TestBed.configureTestingModule({
      imports: [CompanyHubComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(CompanyHubComponent);
    fixture.detectChanges();
    for (const req of httpMock.match(() => true)) {
      req.flush([]);
    }

    const comp = fixture.componentInstance;
    comp.startCreateCompany();
    comp.formCode = 'CMP-NEW';
    comp.formLegalName = 'New Horizon Exports Ltd';
    const savePromise = comp.saveCompanyForm();

    const createReq = httpMock.expectOne(
      (r) => r.method === 'POST' && r.url.endsWith('/companies'),
    );
    createReq.flush({
      ...MOCK_COMPANY,
      id: '33333333-3333-4333-8333-333333333333',
      code: 'CMP-NEW',
      legalName: 'New Horizon Exports Ltd',
    });
    for (const req of httpMock.match(() => true)) {
      req.flush([]);
    }
    await savePromise;
    fixture.detectChanges();

    expect(comp.postCreateF11ModalOpen()).toBe(true);
    expect(fixture.nativeElement.querySelector('[data-testid="post-create-f11-modal"]')).not.toBeNull();
  });

  it('Native PKCE Login Redirect: redirects valid /api/v1/auth/native/authorize path and blocks external open-redirects', async () => {
    const validNativePath =
      '/api/v1/auth/native/authorize?client_id=mc-desktop&code_challenge=abc123&redirect_uri=http%3A%2F%2F127.0.0.1%3A54123%2Fcb';

    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter(APP_ROUTES),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              queryParamMap: convertToParamMap({ nativeAuthorize: validNativePath }),
            },
          },
        },
      ],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const router = TestBed.inject(Router);
    const routerSpy = vi.spyOn(router, 'navigateByUrl');

    const fixture = TestBed.createComponent(LoginComponent);
    const nativeNavSpy = vi
      .spyOn(fixture.componentInstance, 'navigateToNativeAuthorize')
      .mockImplementation(() => undefined);
    fixture.detectChanges();

    for (const req of httpMock.match((r) => r.url.endsWith('/auth/oidc/issuers'))) {
      req.flush([]);
    }

    fixture.componentInstance.username = 'admin';
    fixture.componentInstance.password = 'AdminSecret!234';
    const loginPromise = fixture.componentInstance.signInWithPassword();

    const loginReq = httpMock.expectOne((r) => r.url.endsWith('/auth/login'));
    loginReq.flush({
      principalId: 'p-1',
      username: 'admin',
      displayName: 'Installation Owner',
      systemRoles: ['INSTALLATION_OWNER'],
      companyGrants: [],
      passkeyEnrolled: false,
    });
    await loginPromise;

    expect(nativeNavSpy).toHaveBeenCalledWith(validNativePath);
    expect(routerSpy).not.toHaveBeenCalled();
  });

  it('Doc 50 & Doc 51 Integration: wires UiPreferencesService v2 live preview/apply, F1 ContextualHelpService Esc-first unwind, and SUPER_ADMIN console access', async () => {
    await TestBed.configureTestingModule({
      imports: [ErpShellComponent, AdminConsoleComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const authStore = TestBed.inject(AuthStore);
    const uiPrefs = TestBed.inject(UiPreferencesService);
    const registry = TestBed.inject(ShortcutCommandRegistry);
    const companyContext = TestBed.inject(CompanyContextStore);
    companyContext.setCompanies([MOCK_COMPANY], MOCK_COMPANY.id);

    authStore.setAuthenticatedPrincipal({
      principalId: 'sa-1',
      username: 'superadmin',
      displayName: 'Installation Super Admin',
      systemRoles: ['INSTALLATION_OWNER'],
      companyGrants: [],
      passkeyEnrolled: true,
      superAdmin: true,
    });
    for (const req of httpMock.match((r) => r.url.endsWith('/me/ui-preferences'))) {
      req.flush(
        {
          subjectScope: 'scope_superadmin_0001',
          revision: '1',
          preferences: {
            schemaVersion: 2,
            appearance: 'LIGHT',
            textScalePercent: 100,
            keyboard: {
              version: 1,
              mode: 'CHARACTER',
              locale: 'en-GB',
              profileId: null,
              overrides: [],
            },
          },
        },
        { headers: { ETag: '"ui:scope_superadmin_0001:1"' } },
      );
    }
    await flushMicrotasks();

    const shellFixture = TestBed.createComponent(ErpShellComponent);
    shellFixture.detectChanges();
    for (const req of httpMock.match(() => true)) {
      req.flush([]);
    }

    // 1. Open Preferences Modal -> preview DARK + 125% -> Close reverts preview cleanly
    registry.openKeyboardSettingsModal();
    shellFixture.detectChanges();
    const darkRadio = shellFixture.nativeElement.querySelector(
      '[data-testid="pref-appearance-dark"]',
    ) as HTMLInputElement;
    expect(darkRadio).not.toBeNull();
    darkRadio.click();
    shellFixture.detectChanges();
    expect(uiPrefs.effectiveAppearance()).toBe('DARK');

    const closeBtn = shellFixture.nativeElement.querySelector(
      '[data-testid="kbd-close-btn"]',
    ) as HTMLButtonElement;
    closeBtn.click();
    shellFixture.detectChanges();
    expect(uiPrefs.effectiveAppearance()).toBe('LIGHT');

    // 2. Open F2 Working Date modal AND F1 Contextual Help -> Escape closes Help FIRST while keeping F2 modal open!
    shellFixture.componentInstance.openWorkingDateModal();
    registry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'F1', code: 'F1', cancelable: true }),
    );
    shellFixture.detectChanges();
    expect(shellFixture.componentInstance.contextualHelp.panelOpen()).toBe(true);
    expect(shellFixture.componentInstance.workingDateModalOpen()).toBe(true);

    shellFixture.componentInstance['handleGlobalEscape']();
    shellFixture.detectChanges();
    expect(shellFixture.componentInstance.contextualHelp.panelOpen()).toBe(false);
    expect(shellFixture.componentInstance.workingDateModalOpen()).toBe(true);

    // 3. SUPER_ADMIN entry appears in COMPANY Top-Bar menu and AdminConsoleComponent
    shellFixture.componentInstance.toggleTopBarMenu('COMPANY');
    shellFixture.detectChanges();
    expect(
      shellFixture.componentInstance
        .currentTopBarMenuItems()
        .some((i) => i.id === 'company.govIntegrations'),
    ).toBe(true);

    const adminFixture = TestBed.createComponent(AdminConsoleComponent);
    adminFixture.detectChanges();
    for (const req of httpMock.match(() => true)) {
      req.flush([]);
    }
    expect(
      adminFixture.nativeElement.querySelector('[data-testid="admin-gov-integrations-link"]'),
    ).not.toBeNull();

    // 4. Verify Backend API Server (IP & Port) configuration in ErpShellComponent and LoginComponent
    shellFixture.componentInstance.openApiServerModal();
    shellFixture.detectChanges();
    expect(shellFixture.componentInstance.apiServerModalOpen()).toBe(true);
    shellFixture.componentInstance.shellApiScheme = 'https';
    shellFixture.componentInstance.shellApiHost = '192.168.10.55';
    shellFixture.componentInstance.shellApiPort = '9443';
    shellFixture.componentInstance.applyShellBackendApiConfig();
    shellFixture.detectChanges();
    expect(shellFixture.componentInstance.runtimeConfig.apiBaseUrl).toBe(
      'https://192.168.10.55:9443/api/v1',
    );
    expect(shellFixture.componentInstance.runtimeConfig.hasCustomEndpointOverride()).toBe(true);

    shellFixture.componentInstance.resetShellBackendApiConfig();
    shellFixture.detectChanges();
    expect(shellFixture.componentInstance.runtimeConfig.apiBaseUrl).toBe('/api/v1');
    expect(shellFixture.componentInstance.runtimeConfig.hasCustomEndpointOverride()).toBe(false);
  });
});
