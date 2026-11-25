import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { APP_ROUTES } from './app.routes';
import { ShortcutCommandRegistry } from './core/keyboard/shortcut-command.registry';
import { LoginComponent } from './features/auth/login.component';
import { AdminConsoleComponent } from './features/companies/admin-console.component';
import { MigrationAndExchangeComponent } from './features/exchange/migration-and-exchange.component';
import { ExtensionWorkbenchComponent } from './features/extensions/extension-workbench.component';
import { StockSummaryComponent } from './features/inventory/stock-summary.component';
import { TrialBalanceComponent } from './features/reports/trial-balance.component';
import { VoucherGridComponent } from './features/vouchers/voucher-grid.component';
import {
  ALL_24_SEMANTIC_ICON_NAMES,
  SEMANTIC_ICON_MANIFEST,
  SemanticIconComponent,
} from './shared/components/semantic-icon/semantic-icon.component';
import { ErpShellComponent } from './shell/erp-shell.component';
import { CompanyContextStore, CompanySummary } from './state/company-context.store';

const MOCK_COMPANY_A: CompanySummary = {
  id: '11111111-1111-4111-8111-111111111111',
  code: 'CMP-100',
  legalName: 'Alpha Traders Pvt Ltd',
  mailingName: 'Alpha Traders',
  countryCode: 'IN',
  stateCode: '33',
  pinCode: '600001',
  baseCurrencyCode: 'INR',
  currencySymbol: '₹',
  currencyScale: 2,
  financialYearFrom: '2025-04-01',
  booksBeginningFrom: '2025-04-01',
  lastEntryDate: '2025-04-15',
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

const MOCK_COMPANY_B: CompanySummary = {
  ...MOCK_COMPANY_A,
  id: '22222222-2222-4222-8222-222222222222',
  code: 'CMP-200',
  legalName: 'Beta Logistics LLP',
};

async function flushMicrotasks(): Promise<void> {
  for (let i = 0; i < 8; i += 1) {
    await Promise.resolve();
  }
}

describe('MoneyCraft v1.0.0 — VIS-001, KEY-001, MC-FED-01, Tally Parity & Increment X Workbenches', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('VIS-001: renders all 24 original semantic SVG icons with 24x24 viewBox, 1.75 stroke, currentColor, and aria-hidden', async () => {
    await TestBed.configureTestingModule({
      imports: [SemanticIconComponent],
    }).compileComponents();

    expect(ALL_24_SEMANTIC_ICON_NAMES.length).toBe(24);
    expect(Object.keys(SEMANTIC_ICON_MANIFEST).length).toBe(24);

    for (const iconName of ALL_24_SEMANTIC_ICON_NAMES) {
      const fixture = TestBed.createComponent(SemanticIconComponent);
      fixture.componentRef.setInput('name', iconName);
      fixture.componentRef.setInput('size', 20);
      fixture.detectChanges();

      const svg: SVGElement | null = fixture.nativeElement.querySelector('svg');
      expect(svg, `Missing SVG for ${iconName}`).not.toBeNull();
      expect(svg?.getAttribute('viewBox')).toBe('0 0 24 24');
      expect(svg?.getAttribute('stroke')).toBe('currentColor');
      expect(svg?.getAttribute('stroke-width')).toBe('1.75');
      expect(svg?.getAttribute('aria-hidden')).toBe('true');
      expect(svg?.getAttribute('width')).toBe('20');
      expect((svg?.children.length ?? 0) > 0, `Empty SVG paths for ${iconName}`).toBe(true);
    }
  });

  it('MC-FED-01 & KEY-001: LoginComponent loads OIDC issuers dynamically and supports KeyboardSettingsModal Reset-without-Apply', async () => {
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const registry = TestBed.inject(ShortcutCommandRegistry);
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();

    const oidcReq = httpMock.expectOne((r) => r.url.endsWith('/auth/oidc/issuers'));
    oidcReq.flush([
      {
        issuerId: 'iss-1',
        displayName: 'Keycloak Enterprise SSO',
        enabled: true,
      },
    ]);
    await flushMicrotasks();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Keycloak Enterprise SSO');

    // Open Keyboard settings from Login screen, apply OFF mode, then reopen & Reset defaults WITHOUT Apply -> stays OFF!
    registry.openKeyboardSettingsModal();
    fixture.detectChanges();

    registry.applyKeyboardPreferences({
      version: 1,
      mode: 'OFF',
      locale: 'en-GB',
      profileId: null,
      overrides: [],
    });
    expect(registry.preferences().mode).toBe('OFF');

    registry.openKeyboardSettingsModal();
    fixture.detectChanges();
    const defaultsBtn: HTMLButtonElement | null =
      fixture.nativeElement.querySelector('[data-testid="kbd-defaults-btn"]');
    const closeBtn: HTMLButtonElement | null =
      fixture.nativeElement.querySelector('[data-testid="kbd-close-btn"]');
    expect(defaultsBtn).not.toBeNull();
    defaultsBtn?.click();
    fixture.detectChanges();
    closeBtn?.click();
    fixture.detectChanges();

    // Active mode must STILL be OFF because Close discarded pending Reset!
    expect(registry.preferences().mode).toBe('OFF');
  });

  it('MC-FED-01: AdminConsoleComponent renders Enterprise OIDC Federation Status card', async () => {
    await TestBed.configureTestingModule({
      imports: [AdminConsoleComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(AdminConsoleComponent);
    fixture.detectChanges();

    for (const req of httpMock.match(() => true)) {
      if (req.request.url.includes('oidc')) {
        req.flush([
          {
            issuerId: 'iss-entra',
            displayName: 'Microsoft Entra ID',
            issuerUri: 'https://login.microsoftonline.com/tenant/v2.0',
            clientId: 'mc-erp-client',
            enabled: true,
          },
        ]);
      } else {
        req.flush([]);
      }
    }
    await flushMicrotasks();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('OIDC');
    expect(fixture.nativeElement.textContent).toContain('Microsoft Entra ID');
  });

  it('KEY-001 ErpShellComponent: opens editor.dirty 3-way modal when switching companies with unsaved draft and opens 13-row commands.menu', async () => {
    await TestBed.configureTestingModule({
      imports: [ErpShellComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const companyContext = TestBed.inject(CompanyContextStore);
    const registry = TestBed.inject(ShortcutCommandRegistry);
    companyContext.setCompanies([MOCK_COMPANY_A, MOCK_COMPANY_B], MOCK_COMPANY_A.id);
    companyContext.setHasUnsavedDraft(true);

    const fixture = TestBed.createComponent(ErpShellComponent);
    fixture.detectChanges();

    // Attempt company switch while dirty -> opens editor.dirty modal instead of switching
    fixture.componentInstance.switchCompany(MOCK_COMPANY_B.id);
    fixture.detectChanges();

    expect(fixture.componentInstance.dirtyEditorModalOpen()).toBe(true);
    expect(companyContext.activeCompany()?.id).toBe(MOCK_COMPANY_A.id);

    // Press 't' (Stay) -> closes dirty modal and stays on Company A with draft preserved
    registry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 't', code: 'KeyT', cancelable: true }),
    );
    registry.recordKeyUp(new KeyboardEvent('keyup', { key: 't', code: 'KeyT' }));
    fixture.detectChanges();
    expect(fixture.componentInstance.dirtyEditorModalOpen()).toBe(false);
    expect(companyContext.activeCompany()?.id).toBe(MOCK_COMPANY_A.id);
    expect(companyContext.hasUnsavedDraft()).toBe(true);

    // Attempt switch again and press 'd' (Discard) -> switches cleanly to Company B
    fixture.componentInstance.switchCompany(MOCK_COMPANY_B.id);
    fixture.detectChanges();
    registry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'd', code: 'KeyD', cancelable: true }),
    );
    registry.recordKeyUp(new KeyboardEvent('keyup', { key: 'd', code: 'KeyD' }));
    fixture.detectChanges();
    expect(companyContext.activeCompany()?.id).toBe(MOCK_COMPANY_B.id);
    expect(companyContext.hasUnsavedDraft()).toBe(false);

    // Open Commands Menu -> renders all 13 rows
    fixture.componentInstance.openCommandsMenuModal();
    fixture.detectChanges();
    const rows = fixture.nativeElement.querySelectorAll('[data-testid^="cmd-row-"]');
    expect(rows.length).toBe(13);
    fixture.componentInstance.closeCommandsMenuModal();
  });

  it('Tally Parity Reports & Inventory: TrialBalance Alt+R / Alt+U session row hide/restore and StockSummary OPEN_ORDERS ExactDecimal totals', async () => {
    await TestBed.configureTestingModule({
      imports: [TrialBalanceComponent, StockSummaryComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyContext = TestBed.inject(CompanyContextStore);
    const registry = TestBed.inject(ShortcutCommandRegistry);
    companyContext.setCompanies([MOCK_COMPANY_A], MOCK_COMPANY_A.id);

    const tbFixture = TestBed.createComponent(TrialBalanceComponent);
    tbFixture.detectChanges();

    const tbReq = httpMock.expectOne((r) => r.url.includes('/reports/trial-balance'));
    tbReq.flush({
      fromDate: '2025-04-01',
      toDate: '2026-03-31',
      groupRows: [
        {
          id: 'grp-assets',
          name: 'Current Assets',
          rowType: 'GROUP',
          openingDebit: '0.00',
          openingCredit: '0.00',
          periodDebit: '1000.00',
          periodCredit: '0.00',
          closingDebit: '1000.00',
          closingCredit: '0.00',
        },
        {
          id: 'grp-liab',
          name: 'Current Liabilities',
          rowType: 'GROUP',
          openingDebit: '0.00',
          openingCredit: '0.00',
          periodDebit: '0.00',
          periodCredit: '1000.00',
          closingDebit: '0.00',
          closingCredit: '1000.00',
        },
      ],
      ledgerRows: [],
      totalOpeningDebit: '0.00',
      totalOpeningCredit: '0.00',
      totalPeriodDebit: '1000.00',
      totalPeriodCredit: '1000.00',
      totalClosingDebit: '1000.00',
      totalClosingCredit: '1000.00',
      differenceAmount: '0.00',
      isBalanced: true,
    });
    await flushMicrotasks();
    tbFixture.detectChanges();

    expect(tbFixture.componentInstance.displayRows().length).toBe(2);

    // Alt+R hides focused row temporarily and recalculates effectiveTotals
    registry.triggerCommand('register.removeLine');
    tbFixture.detectChanges();
    expect(tbFixture.componentInstance.displayRows().length).toBe(1);
    expect(tbFixture.componentInstance.effectiveTotals().totalClosingDebit).toBe('0.00');
    expect(tbFixture.componentInstance.effectiveTotals().totalClosingCredit).toBe('1000.00');

    // Alt+U restores hidden row
    registry.triggerCommand('register.restoreLine');
    tbFixture.detectChanges();
    expect(tbFixture.componentInstance.displayRows().length).toBe(2);
    expect(tbFixture.componentInstance.effectiveTotals().isBalanced).toBe(true);
    tbFixture.destroy();

    // StockSummary OPEN_ORDERS tab
    const stockFixture = TestBed.createComponent(StockSummaryComponent);
    stockFixture.componentRef.setInput('initialTab', 'OPEN_ORDERS');
    stockFixture.detectChanges();

    for (const req of httpMock.match(() => true)) {
      if (
        req.request.url.includes('/orders/open') &&
        req.request.params.get('orderType') === 'SALES_ORDER'
      ) {
        req.flush([
          {
            orderVoucherId: 'ord-1',
            voucherNumber: 'SO-001',
            voucherDate: '2025-04-12',
            orderType: 'SALES_ORDER',
            partyLedgerName: 'Acme Retail',
            stockItemId: 'item-1',
            stockItemName: 'Copper Wire Coil',
            uomSymbol: 'NOS',
            orderedQty: '10.0000',
            fulfilledQty: '2.0000',
            pendingQty: '8.0000',
            rate: '125.50',
            discountPct: '10.00',
          },
        ]);
      } else {
        req.flush([]);
      }
    }
    await flushMicrotasks();
    stockFixture.detectChanges();

    // 8 * 125.50 = 1004.00; less 10% (100.40) = 903.60 exact!
    expect(stockFixture.componentInstance.openOrdersPendingTotalWire()).toBe('903.60');
    stockFixture.destroy();
  });

  it('Increment X Spreadsheet Wizard: prepares multi-ledger batch, previews cell-coordinate errors, commits LIVE, and downloads safe error CSV', async () => {
    await TestBed.configureTestingModule({
      imports: [MigrationAndExchangeComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyContext = TestBed.inject(CompanyContextStore);
    companyContext.setCompanies([MOCK_COMPANY_A], MOCK_COMPANY_A.id);

    const fixture = TestBed.createComponent(MigrationAndExchangeComponent);
    fixture.componentRef.setInput('initialTab', 'SPREADSHEET_WIZARD');
    fixture.detectChanges();

    for (const req of httpMock.match((r) => r.url.includes('/spreadsheet-imports/templates'))) {
      req.flush([]);
    }

    fixture.componentInstance.sheetCsvText =
      'Date,Voucher No,Customer,Invoice Total,Taxable Sales,Output CGST,Output SGST\n2025-04-10,INV-1,Acme,1180.00,1000.00,90.00,90.00';
    fixture.componentInstance.sheetCachedFormulaConsent = true;
    const prepPromise = fixture.componentInstance.prepareSpreadsheet();

    const prepReq = httpMock.expectOne((r) => r.url.endsWith('/spreadsheet-imports/prepare'));
    expect(prepReq.request.body.cachedFormulaConsent).toBe(true);
    expect(prepReq.request.body.mapping.ledgerColumns.length).toBe(3);
    prepReq.flush({
      id: 'batch-101',
      companyId: MOCK_COMPANY_A.id,
      sourceFileName: 'sales-register.csv',
      sourceSha256Hex: 'a'.repeat(64),
      formatProfile: 'CSV',
      targetType: 'ACCOUNTING_VOUCHERS',
      sheetName: 'Sheet1',
      availableSheets: ['Sheet1'],
      detectedHeaders: ['Date', 'Voucher No', 'Customer', 'Invoice Total'],
      headerRowIndex: 1,
      dateSystem: 'ISO_TEXT',
      macroDetected: false,
      formulaCellsDetected: true,
      cachedFormulaPolicyAccepted: true,
      mappingRevision: 1,
      status: 'READY',
      totalUnits: 2,
      validUnits: 1,
      invalidUnits: 1,
      committedUnits: 0,
      failedUnits: 0,
      skippedUnits: 0,
      uncertainUnits: 0,
      totalDebit: '1180.00',
      totalCredit: '1180.00',
      diagnostics: [],
      versionNo: 1,
      units: [
        {
          id: 'u-1',
          unitSeq: 1,
          unitKey: 'INV-1',
          targetType: 'ACCOUNTING_VOUCHERS',
          validationStatus: 'VALID',
          executionStatus: 'PENDING',
          unitDebit: '1180.00',
          unitCredit: '1180.00',
          unitIdempotencyKey: 'idem-1',
          validationErrors: [],
        },
        {
          id: 'u-2',
          unitSeq: 2,
          unitKey: 'INV-2',
          targetType: 'ACCOUNTING_VOUCHERS',
          validationStatus: 'INVALID',
          executionStatus: 'SKIPPED',
          unitDebit: '0.00',
          unitCredit: '0.00',
          unitIdempotencyKey: 'idem-2',
          validationErrors: [
            {
              code: 'MC-SHEET-INVALID-DATE',
              field: 'voucherDate',
              sheetName: 'Sheet1',
              rowNumber: 3,
              columnIndex: 1,
              cellRef: 'A3',
              columnHeader: 'Date',
              rawCellSnippet: '1900-02-29',
              message: 'Fictitious 1900-02-29 leap date rejected.',
            },
          ],
        },
      ],
    });
    await prepPromise;
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Sheet1!A3');
    expect(fixture.nativeElement.textContent).toContain('1900-02-29');

    // Execute LIVE commit
    const execPromise = fixture.componentInstance.executeSpreadsheetBatch(false);
    const execReq = httpMock.expectOne((r) =>
      r.url.endsWith('/spreadsheet-imports/batches/batch-101/execute'),
    );
    expect(execReq.request.body.action).toBe('POST');
    execReq.flush({
      ...fixture.componentInstance.activeSpreadsheetBatch(),
      status: 'COMPLETED',
      committedUnits: 1,
    });
    await execPromise;
    fixture.detectChanges();
    expect(fixture.componentInstance.activeSpreadsheetBatch()?.committedUnits).toBe(1);

    // Download Safe Error CSV
    const csvPromise = fixture.componentInstance.downloadSpreadsheetErrors();
    const csvReq = httpMock.expectOne((r) =>
      r.url.endsWith('/spreadsheet-imports/batches/batch-101/errors.csv'),
    );
    csvReq.flush("unitSeq,cellRef,rawSnippet\n2,A3,\"'=CMD()\"");
    await csvPromise;
    expect(fixture.componentInstance.statusBanner()).toContain('batch-101');
  });

  it('Increment X Extension Workbench: creates SELECTED_PRIVATE field, evaluates bounded FEEL, imports .tdl parity matrix, and activates package', async () => {
    await TestBed.configureTestingModule({
      imports: [ExtensionWorkbenchComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyContext = TestBed.inject(CompanyContextStore);
    companyContext.setCompanies([MOCK_COMPANY_A], MOCK_COMPANY_A.id);

    const fixture = TestBed.createComponent(ExtensionWorkbenchComponent);
    fixture.detectChanges();

    for (const req of httpMock.match((r) => r.url.includes('/extensions/custom-fields'))) {
      req.flush([]);
    }

    // 1. Create SELECTED_PRIVATE Custom Field
    fixture.componentInstance.cfFieldCode = 'secret_margin_note';
    fixture.componentInstance.cfLabel = 'Confidential Margin Note';
    fixture.componentInstance.cfProtectionClass = 'SELECTED_PRIVATE';
    const cfPromise = fixture.componentInstance.createFieldDefinition();
    const cfReq = httpMock.expectOne(
      (r) => r.method === 'POST' && r.url.endsWith('/extensions/custom-fields'),
    );
    expect(cfReq.request.body.protectionClass).toBe('SELECTED_PRIVATE');
    cfReq.flush({
      id: 'cf-1',
      companyId: MOCK_COMPANY_A.id,
      namespace: 'com.acme.logistics',
      fieldUuid: '33333333-3333-4333-8333-333333333333',
      ownerEntityType: 'VOUCHER',
      fieldCode: 'secret_margin_note',
      label: 'Confidential Margin Note',
      dataType: 'TEXT',
      protectionClass: 'SELECTED_PRIVATE',
      uiAnchor: 'voucher.header.extra',
      requiredFlag: false,
      active: true,
      versionNo: 1,
    });
    await cfPromise;
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('SELECTED_PRIVATE');

    // 2. Bounded FEEL preview
    fixture.componentInstance.switchTab('RULES_PACKAGES');
    fixture.detectChanges();
    const evalPromise = fixture.componentInstance.runFeelPreview();
    const evalReq = httpMock.expectOne((r) => r.url.endsWith('/extensions/evaluate-preview'));
    evalReq.flush({
      valid: true,
      resultType: 'BOOLEAN',
      resultValue: 'true',
      matchedRuleIds: [],
      outcome: 'ALLOW',
      messages: [],
      astDepth: 3,
      stepsConsumed: 5,
    });
    await evalPromise;
    fixture.detectChanges();
    expect(fixture.componentInstance.previewResult()?.resultValue).toBe('true');

    // 3. Static .tdl import & 5-disposition Functional Parity Matrix filter
    fixture.componentInstance.switchTab('TDL_IMPORTER');
    for (const req of httpMock.match((r) => r.url.endsWith('/extensions/tdl-imports') && r.method === 'GET')) {
      req.flush([]);
    }
    const tdlPromise = fixture.componentInstance.analyzeTdlProject();
    const tdlReq = httpMock.expectOne(
      (r) => r.method === 'POST' && r.url.endsWith('/extensions/tdl-imports'),
    );
    tdlReq.flush({
      id: 'tdl-1',
      companyId: MOCK_COMPANY_A.id,
      projectName: 'Legacy Logistics Customization',
      fileName: 'logistics.tdl',
      sourceSha256Hex: 'b'.repeat(64),
      hasCompiledTcpBlock: false,
      hasNativeDllBlock: true,
      hasUnresolvedIncludes: false,
      status: 'ANALYZED',
      draftExtensionId: 'pkg-draft-1',
      summaryCounts: { TRANSLATED_VERIFICATION_PENDING: 1, UNSUPPORTED: 1 },
      constructs: [],
      parityMatrix: [
        {
          rowNo: 1,
          sourceRef: 'logistics.tdl:1-2',
          constructIdentifier: 'UDF:VehicleRegNo',
          requiredBehaviour: 'Custom string field on Voucher',
          targetComponent: 'CustomFieldDef',
          disposition: 'TRANSLATED_VERIFICATION_PENDING',
          plannedFixture: 'vch-udf-1',
          expectedResult: 'Persists string value',
          executionStatus: 'NOT_RUN',
        },
        {
          rowNo: 2,
          sourceRef: 'logistics.tdl:10-12',
          constructIdentifier: 'CallDll:SignToken',
          requiredBehaviour: 'Invoke native Windows DLL',
          targetComponent: 'NONE',
          disposition: 'UNSUPPORTED',
          plannedFixture: 'N/A',
          expectedResult: 'Rejected fail-closed',
          executionStatus: 'NOT_RUN',
        },
      ],
    });
    await tdlPromise;
    fixture.detectChanges();
    expect(fixture.componentInstance.filteredParityRows().length).toBe(2);
    fixture.componentInstance.tdlDispositionFilter.set('UNSUPPORTED');
    fixture.detectChanges();
    expect(fixture.componentInstance.filteredParityRows().length).toBe(1);
    expect(fixture.componentInstance.filteredParityRows()[0].constructIdentifier).toBe(
      'CallDll:SignToken',
    );

    // 4. Package Validate + Activate (sending If-Match)
    fixture.componentInstance.switchTab('LIFECYCLE_ODBC');
    for (const req of httpMock.match((r) => r.url.includes('/extensions/'))) {
      if (req.request.url.endsWith('/packages')) {
        req.flush([
          {
            id: 'pkg-draft-1',
            companyId: MOCK_COMPANY_A.id,
            namespace: 'com.acme.logistics',
            extensionUuid: '44444444-4444-4444-8444-444444444444',
            displayName: 'Logistics Compliance Extension',
            revisionNo: 1,
            schemaDialect: 'https://json-schema.org/draft/2020-12/schema',
            definitionSha256Hex: 'c'.repeat(64),
            status: 'VALIDATED',
            bindingState: 'INACTIVE',
            validationDiagnostics: [],
            versionNo: 2,
          },
        ]);
      } else {
        req.flush([]);
      }
    }
    await flushMicrotasks();
    fixture.detectChanges();

    const pkg = fixture.componentInstance.packages()[0];
    const actPromise = fixture.componentInstance.activatePkg(pkg);
    const actReq = httpMock.expectOne((r) =>
      r.url.endsWith('/extensions/packages/pkg-draft-1/activate'),
    );
    expect(actReq.request.headers.get('If-Match')).toBe('"v2"');
    actReq.flush({
      id: 'bind-1',
      companyId: MOCK_COMPANY_A.id,
      namespace: 'com.acme.logistics',
      extensionUuid: '44444444-4444-4444-8444-444444444444',
      extensionPackageId: 'pkg-draft-1',
      revisionNo: 1,
      displayName: 'Logistics Compliance Extension',
      boundDefinitionSha256Hex: 'c'.repeat(64),
      state: 'ACTIVE',
      versionNo: 1,
    });
    await actPromise;
    fixture.detectChanges();
    expect(fixture.componentInstance.packages()[0].bindingState).toBe('ACTIVE');
  });

  it('VoucherGridComponent F10 Other Vouchers modal lists all 24 voucher types and dismisses on global Escape', async () => {
    await TestBed.configureTestingModule({
      imports: [VoucherGridComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyContext = TestBed.inject(CompanyContextStore);
    const registry = TestBed.inject(ShortcutCommandRegistry);
    companyContext.setCompanies([MOCK_COMPANY_A], MOCK_COMPANY_A.id);

    const fixture = TestBed.createComponent(VoucherGridComponent);
    fixture.detectChanges();
    for (const req of httpMock.match(() => true)) {
      req.flush([]);
    }
    await flushMicrotasks();
    fixture.detectChanges();

    // Open F10 Other Vouchers modal
    registry.triggerCommand('voucher.otherVouchers');
    fixture.detectChanges();
    expect(fixture.componentInstance.otherVouchersModalOpen()).toBe(true);
    expect(fixture.componentInstance.filteredOtherVoucherChoices().length).toBeGreaterThanOrEqual(
      24,
    );

    // Dispatch Escape from document.body (even if focus had not yet moved into input) -> closes cleanly
    document.body.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true, cancelable: true }),
    );
    registry.recordKeyUp(new KeyboardEvent('keyup', { key: 'Escape', code: 'Escape' }));
    fixture.detectChanges();
    expect(fixture.componentInstance.otherVouchersModalOpen()).toBe(false);
  });
});
