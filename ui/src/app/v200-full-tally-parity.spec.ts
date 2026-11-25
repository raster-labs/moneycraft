import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { APP_ROUTES } from './app.routes';
import { ShortcutCommandRegistry } from './core/keyboard/shortcut-command.registry';
import { CompanyHubComponent } from './features/companies/company-hub.component';
import { MigrationAndExchangeComponent } from './features/exchange/migration-and-exchange.component';
import { MastersHubComponent } from './features/masters/masters-hub.component';
import { AccountBooksAndExceptionsComponent } from './features/reports/account-books-and-exceptions.component';
import { BalanceSheetComponent } from './features/reports/balance-sheet.component';
import { TrialBalanceComponent } from './features/reports/trial-balance.component';
import { DayBookComponent } from './features/vouchers/day-book.component';
import { VoucherGridComponent } from './features/vouchers/voucher-grid.component';
import { ErpShellComponent } from './shell/erp-shell.component';
import { GatewayComponent } from './shell/gateway.component';
import {
  CompanyContextStore,
  CompanySummary,
  DEFAULT_COMPANY_F12_CONFIG,
} from './state/company-context.store';
import { NavigationStackStore } from './state/navigation-stack.store';

const BASE_COMPANY: CompanySummary = {
  id: '22222222-2222-4222-8222-222222222222',
  code: 'CMP-200',
  legalName: 'MoneyCraft Full Parity Enterprises Pvt Ltd',
  mailingName: 'MoneyCraft Full Parity',
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
    mcaEditLogEnforced: false,
    batchesEnabled: false,
    expiryDateForBatchesEnabled: false,
    multiplePriceLevelsEnabled: false,
    useDiscountColumnInInvoices: true,
    useSeparateActualAndBilledQty: true,
    payrollEnabled: false,
    payrollStatutoryEnabled: false,
    tcsEnabled: false,
    jobOrderProcessingEnabled: false,
    multipleAddressesEnabled: false,
  },
};

describe('MoneyCraft v2.0.0 — Full TallyPrime 7.2 Functional & Keyboard Parity (CHG-UI-001..004, CHG-VCH-001..002, CHG-RPT-001)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('CHG-UI-001: CompanyContextStore normalizes all 33 F11 flags, manages F12 config, and enables features on demand', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });

    const store = TestBed.inject(CompanyContextStore);
    store.setCompanies([BASE_COMPANY], BASE_COMPANY.id);

    expect(store.f12Config()).toEqual(DEFAULT_COMPANY_F12_CONFIG);
    expect(store.isPayrollEnabled()).toBe(false);
    expect(store.isPriceLevelsEnabled()).toBe(false);
    expect(store.isDiscountColEnabled()).toBe(true);
    expect(store.isActualBilledQtySplit()).toBe(true);

    await store.updateF12Config({
      useCrDrInsteadOfToBy: false,
      provideLineNarration: true,
      showOpeningBalances: true,
      showTransactions: true,
      showPercentages: true,
    });
    expect(store.f12Config().useCrDrInsteadOfToBy).toBe(false);
    expect(store.f12Config().provideLineNarration).toBe(true);
    expect(store.f12Config().showPercentages).toBe(true);

    await store.enableCompanyFeature('payrollEnabled');
    expect(store.isPayrollEnabled()).toBe(true);
    expect(store.activeCompany()?.features.payrollEnabled).toBe(true);
  });

  it('CHG-UI-002 & CHG-UI-004: ErpShellComponent opens in-place F11 modal with Show More/All & dependent guards, tabbed F12 modal, and Ctrl+Left/Right menu cycling', async () => {
    await TestBed.configureTestingModule({
      imports: [ErpShellComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const store = TestBed.inject(CompanyContextStore);
    store.setCompanies([BASE_COMPANY], BASE_COMPANY.id);

    const fixture = TestBed.createComponent(ErpShellComponent);
    fixture.detectChanges();
    const shell = fixture.componentInstance;

    // 1. Open in-place F11 modal
    shell.openF11Modal();
    fixture.detectChanges();

    const f11Modal = fixture.nativeElement.querySelector('[data-testid="global-f11-modal"]');
    expect(f11Modal).toBeTruthy();
    expect(shell.f11ShowMore()).toBe(true);
    expect(shell.f11ShowAll()).toBe(false);

    // Enable Show All features
    shell.onF11ShowAllChange(true);
    fixture.detectChanges();
    expect(shell.f11ShowAll()).toBe(true);
    expect(shell.f11ShowMore()).toBe(true);

    // Dependent guard: when batchesEnabled is false, acceptF11Modal forces expiryDateForBatchesEnabled = false
    shell.f11DraftBatches = false;
    shell.f11DraftExpiryBatches = true;
    shell.f11DraftPriceLevels = true;
    shell.f11DraftTcsEnabled = true;

    // Accept F11 modal (Ctrl+A) updates company features in place
    await shell.acceptF11Modal();
    fixture.detectChanges();
    expect(shell.f11ModalOpen()).toBe(false);
    expect(store.activeCompany()?.features.expiryDateForBatchesEnabled).toBe(false);
    expect(store.activeCompany()?.features.multiplePriceLevelsEnabled).toBe(true);
    expect(store.activeCompany()?.features.tcsEnabled).toBe(true);

    // 2. Open context-sensitive F12 modal
    shell.openShellConfigureModal();
    fixture.detectChanges();
    const f12Modal = fixture.nativeElement.querySelector('[data-testid="shell-configure-modal"]');
    expect(f12Modal).toBeTruthy();

    shell.f12ActiveTab.set('VOUCHER');
    shell.f12Draft.useCrDrInsteadOfToBy = false;
    shell.f12Draft.provideLineNarration = true;
    await shell.acceptShellConfigureModal();
    fixture.detectChanges();
    expect(shell.shellConfigureOpen()).toBe(false);
    expect(store.f12Config().useCrDrInsteadOfToBy).toBe(false);
    expect(store.f12Config().provideLineNarration).toBe(true);

    // 3. Top Omni-Bar Ctrl+Right / Ctrl+Left menu cycling
    shell.toggleTopBarMenu('COMPANY');
    expect(shell.openTopBarMenu()).toBe('COMPANY');
    shell.onTopBarMenuKeyDown(
      new KeyboardEvent('keydown', { key: 'ArrowRight', ctrlKey: true, cancelable: true }),
    );
    expect(shell.openTopBarMenu()).toBe('DATA');
    shell.onTopBarMenuKeyDown(
      new KeyboardEvent('keydown', { key: 'ArrowLeft', ctrlKey: true, cancelable: true }),
    );
    expect(shell.openTopBarMenu()).toBe('COMPANY');

    // 4. Right-rail contextual disabled flag when maintainAccounts is false
    await store.patchFeatureToggle({ maintainAccounts: false });
    fixture.detectChanges();
    const paymentRail = shell.rightRailItems().find((i) => i.commandId === 'voucher.payment');
    expect(paymentRail?.disabled).toBe(true);
  });

  it('CHG-UI-002: CompanyHubComponent exposes Show More and Show All F11 feature toggles', async () => {
    await TestBed.configureTestingModule({
      imports: [CompanyHubComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const store = TestBed.inject(CompanyContextStore);
    store.setCompanies([BASE_COMPANY], BASE_COMPANY.id);

    const fixture = TestBed.createComponent(CompanyHubComponent);
    fixture.componentInstance.startAlterCompany(BASE_COMPANY);
    fixture.detectChanges();

    const showMoreToggle = fixture.nativeElement.querySelector(
      '[data-testid="hub-f11-show-more-toggle"]',
    );
    const showAllToggle = fixture.nativeElement.querySelector(
      '[data-testid="hub-f11-show-all-toggle"]',
    );
    expect(showMoreToggle).toBeTruthy();
    expect(showAllToggle).toBeTruthy();

    fixture.componentInstance.onF11ShowAllChange(true);
    fixture.detectChanges();
    expect(fixture.componentInstance.f11ShowAll()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Enable Job Order Processing');
  });

  it('CHG-UI-003: GatewayComponent and MastersHubComponent support Show More / Show Inactive and one-click F11 feature activation', async () => {
    await TestBed.configureTestingModule({
      imports: [GatewayComponent, MastersHubComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const store = TestBed.inject(CompanyContextStore);
    store.setCompanies([BASE_COMPANY], BASE_COMPANY.id);

    // 1. GatewayComponent
    const gwFixture = TestBed.createComponent(GatewayComponent);
    gwFixture.detectChanges();
    const gw = gwFixture.componentInstance;

    // Enter 'Create' sub-menu
    const createBtn: HTMLButtonElement = gwFixture.nativeElement.querySelector(
      '[data-testid="gateway-l1-C"]',
    );
    createBtn.click();
    gwFixture.detectChanges();
    expect(gw.activeMenuTitle()).toContain('Create');

    // Toggle Show Inactive
    const showInactiveBtn: HTMLButtonElement = gwFixture.nativeElement.querySelector(
      '[data-testid="gateway-show-inactive-btn"]',
    );
    expect(showInactiveBtn).toBeTruthy();
    showInactiveBtn.click();
    gwFixture.detectChanges();
    expect(gw.showInactive()).toBe(true);

    // Click inactive 'Payroll Masters' (requires payrollEnabled)
    const payrollNodeBtn: HTMLButtonElement = gwFixture.nativeElement.querySelector(
      '[data-testid="gateway-subnode-P"]',
    );
    expect(payrollNodeBtn).toBeTruthy();
    payrollNodeBtn.click();
    gwFixture.detectChanges();

    const promptModal = gwFixture.nativeElement.querySelector(
      '[data-testid="inactive-feature-enable-modal"]',
    );
    expect(promptModal).toBeTruthy();
    expect(promptModal.textContent).toContain('Maintain Payroll');

    // Confirm Yes -> enables payrollEnabled immediately
    expect(store.isPayrollEnabled()).toBe(false);
    await gw.confirmFeatureActivation();
    gwFixture.detectChanges();
    expect(store.isPayrollEnabled()).toBe(true);

    // 2. MastersHubComponent
    const mhFixture = TestBed.createComponent(MastersHubComponent);
    mhFixture.detectChanges();
    const mh = mhFixture.componentInstance;

    expect(mh.showInactive()).toBe(true);
    mh.toggleShowInactive();
    expect(mh.showInactive()).toBe(false);
    mh.toggleShowInactive();
    mhFixture.detectChanges();
    expect(mh.showInactive()).toBe(true);

    const tcsEntry = mh.filteredEntries().find((i) => i.id === 'tcs-nature');
    expect(tcsEntry).toBeTruthy();
    expect(mh.isEntryInactive(tcsEntry!)).toBe(true);

    mh.openMasterWorkbench(tcsEntry!);
    mhFixture.detectChanges();
    expect(
      mhFixture.nativeElement.querySelector('[data-testid="inactive-feature-enable-modal"]'),
    ).toBeTruthy();
    await mh.confirmFeatureActivation();
    expect(store.isTcsEnabled()).toBe(true);
  });

  it('CHG-VCH-001 & CHG-VCH-002: VoucherGridComponent reacts to F11/F12 flags, F10 Show Inactive activation, Ctrl+Alt+U, Alt+S, and Alt+J', async () => {
    await TestBed.configureTestingModule({
      imports: [VoucherGridComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const store = TestBed.inject(CompanyContextStore);
    const navStack = TestBed.inject(NavigationStackStore);
    store.setCompanies([BASE_COMPANY], BASE_COMPANY.id);

    const fixture = TestBed.createComponent(VoucherGridComponent);
    fixture.detectChanges();
    const vch = fixture.componentInstance;

    // Switch to Sales -> ITEM_INVOICE
    vch.switchToCategory('SALES');
    vch.applyPersistedView('ITEM_INVOICE');
    fixture.detectChanges();

    // Default: both Actual and Billed Qty columns and Disc % column visible
    expect(fixture.nativeElement.querySelector('[data-testid="item-actual-qty-0"]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('[data-testid="item-billed-qty-0"]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('[data-testid="item-disc-0"]')).toBeTruthy();

    // Disable useSeparateActualAndBilledQty and useDiscountColumnInInvoices, enable multiplePriceLevelsEnabled
    await store.patchFeatureToggle({
      useSeparateActualAndBilledQty: false,
      useDiscountColumnInInvoices: false,
      multiplePriceLevelsEnabled: true,
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="item-actual-qty-0"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="item-billed-qty-0"]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('[data-testid="item-disc-0"]')).toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="vch-price-level-select"]'),
    ).toBeTruthy();

    // Switch to Journal -> AS_VOUCHER and test F12 By/To + Line Narration
    vch.switchToCategory('JOURNAL');
    await store.updateF12Config({ useCrDrInsteadOfToBy: false, provideLineNarration: true });
    fixture.detectChanges();

    const sideSelect: HTMLSelectElement = fixture.nativeElement.querySelector(
      '[data-testid="vch-side-0"]',
    );
    expect(sideSelect.options[0].textContent?.trim()).toBe('By');
    expect(sideSelect.options[1].textContent?.trim()).toBe('To');
    expect(
      fixture.nativeElement.querySelector('[data-testid="vch-line-narration-0"]'),
    ).toBeTruthy();

    // F10: Other Vouchers Show Inactive + auto-enable feature on selection
    vch.openOtherVouchersModal();
    fixture.detectChanges();
    const payrollChoice = vch
      .filteredOtherVoucherChoices()
      .find((c) => c.category === 'PAYROLL');
    expect(payrollChoice?.isInactive).toBe(true);
    vch.selectOtherVoucherChoice(payrollChoice!);
    expect(store.isPayrollEnabled()).toBe(true);
    expect(vch.activeCategory()).toBe('PAYROLL');

    // Ctrl+Alt+U: Unused Voucher Numbers Modal
    const unusedPromise = vch.openUnusedVoucherNumbersModal();
    const reqs = httpMock.match((r) => r.url.includes('/vouchers/unused-numbers'));
    for (const req of reqs) {
      req.flush({ unusedNumbers: ['104', '108'] });
    }
    await unusedPromise;
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector('[data-testid="unused-voucher-numbers-modal"]'),
    ).toBeTruthy();
    vch.selectUnusedVoucherNumber('104');
    expect(vch.voucherNumber()).toBe('104');

    // Alt+S: Stock Query overlay push
    vch.openStockQueryOverlay();
    expect(navStack.topFrame()?.route).toBe('/app/reports/stock-query');
    navStack.pop();

    // Alt+J: Stat Adjustment modal
    vch.statAdjustmentModalOpen.set(true);
    vch.statAdjustmentNature.set('Increase of Tax Liability');
    vch.statAdjustmentAdditionalDetails.set('Section 16(4)');
    vch.applyStatAdjustment();
    expect(vch.narration()).toContain('[Stat Adj: Increase of Tax Liability - Section 16(4)]');
  });

  it('CHG-RPT-001: ShortcutCommandRegistry aliases, DayBook invertSelection/stepPeriod, and TrialBalance F12 dynamic columns', async () => {
    await TestBed.configureTestingModule({
      imports: [DayBookComponent, TrialBalanceComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const registry = TestBed.inject(ShortcutCommandRegistry);
    const store = TestBed.inject(CompanyContextStore);
    store.setCompanies([BASE_COMPANY], BASE_COMPANY.id);

    // Verify new shortcut bindings in registry
    expect(registry.getCommand('help.contextual')?.alternateBindings).toContain('Ctrl+F1');
    expect(registry.getCommand('report.toggleDetailed')?.alternateBindings).toContain('Alt+F5');
    expect(registry.getCommand('report.explodeRow')?.alternateBindings).toContain('Alt+Enter');
    expect(registry.getCommand('register.invertSelection')?.primaryBinding).toBe('Ctrl+Alt+I');
    expect(registry.getCommand('voucher.unusedNumbers')?.primaryBinding).toBe('Ctrl+Alt+U');
    expect(registry.getCommand('voucher.stockQuery')?.primaryBinding).toBe('Alt+S');
    expect(registry.getCommand('voucher.statAdjustment')?.primaryBinding).toBe('Alt+J');

    // DayBookComponent invertSelection & stepPeriod
    const dbFixture = TestBed.createComponent(DayBookComponent);
    dbFixture.detectChanges();
    const db = dbFixture.componentInstance;
    db.rawVouchers.set([
      {
        id: 'v-1',
        voucherDate: '2025-04-01',
        voucherTypeId: 'vt-1',
        voucherTypeName: 'Sales',
        voucherNumber: '1',
        particulars: 'Acme',
        status: 'POSTED',
        postingNature: 'NORMAL',
        debitAmount: '100.00',
        creditAmount: '100.00',
        versionNo: 1,
      },
      {
        id: 'v-2',
        voucherDate: '2025-04-02',
        voucherTypeId: 'vt-1',
        voucherTypeName: 'Sales',
        voucherNumber: '2',
        particulars: 'Beta',
        status: 'POSTED',
        postingNature: 'NORMAL',
        debitAmount: '200.00',
        creditAmount: '200.00',
        versionNo: 1,
      },
    ]);
    db.selectedRowIds.set(new Set(['v-1']));
    db.invertSelection();
    expect(db.selectedRowIds().has('v-1')).toBe(false);
    expect(db.selectedRowIds().has('v-2')).toBe(true);

    const prevFrom = db.fromDate();
    db.stepPeriod(1);
    expect(db.fromDate()).not.toBe(prevFrom);

    // TrialBalanceComponent F12 Opening / Transactions / Percentage columns
    const tbFixture = TestBed.createComponent(TrialBalanceComponent);
    tbFixture.detectChanges();
    const tb = tbFixture.componentInstance;
    tb.report.set({
      fromDate: '2025-04-01',
      toDate: '2025-04-30',
      includeOptional: false,
      groupRows: [
        {
          id: 'g-1',
          name: 'Current Assets',
          rowType: 'GROUP',
          depthLevel: 0,
          openingDebit: '500.00',
          openingCredit: '0.00',
          periodDebit: '250.00',
          periodCredit: '0.00',
          closingDebit: '750.00',
          closingCredit: '0.00',
          children: [],
        },
      ],
      ledgerRows: [],
      totalOpeningDebit: '500.00',
      totalOpeningCredit: '500.00',
      totalPeriodDebit: '250.00',
      totalPeriodCredit: '250.00',
      totalClosingDebit: '1000.00',
      totalClosingCredit: '1000.00',
      unadjustedForexDebit: '0.00',
      unadjustedForexCredit: '0.00',
      differenceAmount: '0.00',
      isBalanced: true,
    });

    await store.updateF12Config({
      showOpeningBalances: true,
      showTransactions: true,
      showPercentages: true,
    });
    tbFixture.detectChanges();

    expect(tb.showOpeningColumns()).toBe(true);
    expect(tb.showTransactionColumns()).toBe(true);
    expect(tb.showPercentageColumn()).toBe(true);
    expect(tb.totalColumnCount()).toBe(8);
    expect(tb.rowPercentage(tb.displayRows()[0])).toBe('75.00%');
  });

  it('CHG-UI-005 & CHG-RPT-002: Detailed DayBook ledger breakup, Detailed BalanceSheet child navigation, Level-3 keyboard drill, and hierarchical Esc step-back', async () => {
    await TestBed.configureTestingModule({
      imports: [
        ErpShellComponent,
        DayBookComponent,
        BalanceSheetComponent,
        AccountBooksAndExceptionsComponent,
      ],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const store = TestBed.inject(CompanyContextStore);
    const registry = TestBed.inject(ShortcutCommandRegistry);
    store.setCompanies([BASE_COMPANY], BASE_COMPANY.id);

    // 1. DayBookComponent Alt+F1 Detailed renders double-entry lineBreakups
    const dbFixture = TestBed.createComponent(DayBookComponent);
    dbFixture.detectChanges();
    const db = dbFixture.componentInstance;
    db.rawVouchers.set([
      {
        id: 'v-det-1',
        voucherDate: '2025-04-10',
        voucherTypeId: 'vt-sales',
        voucherTypeName: 'Sales',
        voucherNumber: '1',
        particulars: 'Customer A',
        status: 'POSTED',
        postingNature: 'NORMAL',
        debitAmount: '1180.00',
        creditAmount: '0.00',
        narration: 'GST Invoice #1',
        versionNo: 1,
        lineBreakups: [
          { lineNo: 1, ledgerName: 'Sales Local 18%', side: 'CR', amount: '1000.00' },
          { lineNo: 2, ledgerName: 'CGST', side: 'CR', amount: '90.00' },
          { lineNo: 3, ledgerName: 'SGST', side: 'CR', amount: '90.00' },
        ],
      },
    ]);
    db.loading.set(false);
    db.toggleDetailedMode();
    dbFixture.detectChanges();
    const breakupEl: HTMLElement | null = dbFixture.nativeElement.querySelector(
      '[data-testid="daybook-detail-breakup"]',
    );
    expect(breakupEl).toBeTruthy();
    expect(breakupEl?.textContent).toContain('Sales Local 18%');
    expect(breakupEl?.textContent).toContain('1,000.00');
    expect(breakupEl?.textContent).toContain('CGST');

    // 2. BalanceSheetComponent Detailed (Alt+F1) includes child sub-rows in navigableLines
    const bsFixture = TestBed.createComponent(BalanceSheetComponent);
    bsFixture.detectChanges();
    const bs = bsFixture.componentInstance;
    bs.report.set({
      asOfDate: '2025-04-30',
      format: 'HORIZONTAL',
      liabilityLines: [
        {
          id: 'grp-cl',
          lineType: 'GROUP',
          name: 'Current Liabilities',
          amount: '1000.00',
          children: [
            { id: 'led-duties', lineType: 'LEDGER', name: 'Duties & Taxes', amount: '180.00', children: [] },
          ],
        },
      ],
      assetLines: [
        {
          id: 'grp-ca',
          lineType: 'GROUP',
          name: 'Current Assets',
          amount: '1000.00',
          children: [
            { id: 'led-cash', lineType: 'LEDGER', name: 'Cash-in-Hand', amount: '1000.00', children: [] },
          ],
        },
      ],
      totalLiabilities: '1000.00',
      totalAssets: '1000.00',
      closingStockValue: '0.00',
      profitAndLossBalance: '0.00',
      openingBalanceDifference: '0.00',
      differenceAmount: '0.00',
      isBalanced: true,
    });
    expect(bs.navigableLines().length).toBe(2);
    bs.detailedMode.set(true);
    expect(bs.navigableLines().length).toBe(4);
    expect(bs.navigableLines()[1].key).toBe('L:grp-cl:led-duties');

    // 3. AccountBooksAndExceptionsComponent keyboard ArrowDown / ArrowUp navigation
    const l3Fixture = TestBed.createComponent(AccountBooksAndExceptionsComponent);
    l3Fixture.detectChanges();
    const l3 = l3Fixture.componentInstance;
    l3.accountBookReport.set({
      bookType: 'SALES_REGISTER',
      fromDate: '2025-04-01',
      toDate: '2025-04-30',
      totalDebit: '2000.00',
      totalCredit: '0.00',
      closingBalance: '2000.00',
      monthlySummary: [],
      rows: [
        {
          id: 'r-1',
          rowDate: '2025-04-01',
          particulars: 'Customer 1',
          voucherTypeName: 'Sales',
          voucherNumber: '1',
          voucherId: 'v-1',
          debitAmount: '1000.00',
          creditAmount: '0.00',
          closingBalance: '1000.00',
        },
        {
          id: 'r-2',
          rowDate: '2025-04-02',
          particulars: 'Customer 2',
          voucherTypeName: 'Sales',
          voucherNumber: '2',
          voucherId: 'v-2',
          debitAmount: '1000.00',
          creditAmount: '0.00',
          closingBalance: '2000.00',
        },
      ],
    });
    expect(l3.selectedIndex()).toBe(0);
    l3.onDocumentKeyDown(new KeyboardEvent('keydown', { key: 'ArrowDown', cancelable: true }));
    expect(l3.selectedIndex()).toBe(1);
    l3.onDocumentKeyDown(new KeyboardEvent('keydown', { key: 'ArrowUp', cancelable: true }));
    expect(l3.selectedIndex()).toBe(0);

    // 4. ErpShellComponent context-sensitive rightRailItems & hierarchical Esc step-back
    const shellFixture = TestBed.createComponent(ErpShellComponent);
    shellFixture.detectChanges();
    const shell = shellFixture.componentInstance;

    const railIds = shell.rightRailItems().map((i) => i.commandId);
    expect(railIds).toContain('report.basisOfValues');
    expect(railIds).toContain('voucher.changeMode');
    expect(railIds).toContain('voucher.taxAnalysis');
    expect(railIds).toContain('report.exceptions');

    shell.currentRouteUrl.set('/app/daybook');
    expect(shell.rightRailItems().find((i) => i.commandId === 'voucher.contra')?.label).toBe(
      'Chg Vch',
    );
    shell.currentRouteUrl.set('/app/reports/trial-balance');
    expect(shell.rightRailItems().find((i) => i.commandId === 'voucher.payment')?.label).toBe(
      'Ledger-wise',
    );

    // When on /app/vouchers/new with dirty draft, Esc prompts dirtyEditorModalOpen
    shell.currentRouteUrl.set('/app/vouchers/new');
    store.setHasUnsavedDraft(true);
    registry.triggerCommand('nav.escape');
    expect(shell.dirtyEditorModalOpen()).toBe(true);
    shell.stayInDirtyDialog();
    store.setHasUnsavedDraft(false);
  });

  it('VM-EXG-001..008: MigrationAndExchangeComponent exposes TallyPrime Gold Import duplicate rules, Exception behaviors, Overwrite GUID, Sample Excel (Alt+L), and ASCII/HTML Export', async () => {
    await TestBed.configureTestingModule({
      imports: [MigrationAndExchangeComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const store = TestBed.inject(CompanyContextStore);
    store.setCompanies([BASE_COMPANY], BASE_COMPANY.id);

    const fixture = TestBed.createComponent(MigrationAndExchangeComponent);
    fixture.componentRef.setInput('initialTab', 'IMPORT_EXPORT');
    fixture.detectChanges();
    const exg = fixture.componentInstance;

    const initTplReqs = httpMock.match((r) => r.url.includes('/exchange/templates'));
    for (const r of initTplReqs) {
      r.flush([]);
    }

    expect(
      fixture.nativeElement.querySelector('[data-testid="exchange-import-dup-rule"]'),
    ).toBeTruthy();
    expect(
      fixture.nativeElement.querySelector('[data-testid="exchange-import-exc-behavior"]'),
    ).toBeTruthy();
    expect(
      fixture.nativeElement.querySelector('[data-testid="exchange-import-overwrite-guid"]'),
    ).toBeTruthy();
    expect(
      fixture.nativeElement.querySelector('[data-testid="exchange-sample-excel-btn"]'),
    ).toBeTruthy();

    // Test ASCII Export with includeNarration = false
    exg.selectedFormat.set('ASCII');
    exg.exportIncludeNarration = false;
    const expPromise = exg.runExport();
    const asciiReq = httpMock.expectOne((r) => r.url.includes('/exports/ascii'));
    expect(asciiReq.request.body.includeNarration).toBe(false);
    asciiReq.flush({
      format: 'ASCII',
      fileName: 'moneycraft-export.csv',
      mimeType: 'text/csv',
      content: 'RecordType,GUID,Name\nLEDGER,g-1,Alpha Traders\n',
      recordCount: 1,
    });
    await expPromise;
    expect(exg.lastExportResult()?.format).toBe('ASCII');

    // Test Import with COMBINE_OPENING_BALANCES, RECORD_EXCEPTIONS_AND_IMPORT, and overwriteVoucherByGuid = true
    exg.selectedFormat.set('XML');
    exg.importContent = '<ENVELOPE></ENVELOPE>';
    exg.importMasterDuplicateRule = 'COMBINE_OPENING_BALANCES';
    exg.importExceptionBehavior = 'RECORD_EXCEPTIONS_AND_IMPORT';
    exg.importOverwriteVoucherByGuid = true;
    const impPromise = exg.runImport();
    const impReq = httpMock.expectOne((r) => r.url.includes('/imports/xml'));
    expect(impReq.request.body.masterDuplicateRule).toBe('COMBINE_OPENING_BALANCES');
    expect(impReq.request.body.exceptionBehavior).toBe('RECORD_EXCEPTIONS_AND_IMPORT');
    expect(impReq.request.body.overwriteVoucherByGuid).toBe(true);
    impReq.flush({
      jobId: 'job-1',
      format: 'XML',
      status: 'IMPORTED',
      groupsImported: 0,
      ledgersImported: 1,
      stockItemsImported: 0,
      vouchersImported: 1,
      vouchersUpdated: 1,
      exceptionsRecorded: 1,
      totalDebit: '1000.00',
      totalCredit: '1000.00',
      isBalanced: true,
      message: 'XML import committed with zero double-entry variance.',
      exceptions: [
        {
          exceptionId: 'exc-1',
          entityType: 'VOUCHER',
          identifier: 'GLD-BAD-003',
          errorCode: 'MC-EXG-UNBALANCED-VOUCHER',
          message: 'Unbalanced voucher lines',
          resolved: false,
        },
      ],
    });
    await impPromise;
    expect(exg.lastImportResult()?.vouchersUpdated).toBe(1);
    expect(exg.importExceptions().length).toBe(1);
  });
});
