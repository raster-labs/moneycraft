import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { APP_ROUTES } from '../../app.routes';
import { etagInterceptor } from '../../core/http/etag.interceptor';
import { idempotencyInterceptor } from '../../core/http/idempotency.interceptor';
import { problemDetailsInterceptor } from '../../core/http/problem-details.interceptor';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { RUPEE_SYMBOL } from '../../core/money/exact-decimal';
import { CompanyContextStore, CompanySummary } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { DayBookComponent } from '../vouchers/day-book.component';
import { EditLogModalService } from './edit-log-modal.service';
import { EditLogRedlineModalComponent } from './edit-log-redline-modal.component';
import { EinvoiceWorkbenchComponent } from './einvoice-workbench.component';
import { GstRegistrationWorkbenchComponent } from './gst-registration-workbench.component';
import { Gstr1DashboardComponent } from './gstr1-dashboard.component';
import { Gstr3bDashboardComponent } from './gstr3b-dashboard.component';
import { Gstr9DashboardComponent } from './gstr9-dashboard.component';
import { ImsWorkbenchComponent } from './ims-workbench.component';
import { StatutoryService } from './statutory.service';
import { Tds26qWorkbenchComponent } from './tds-26q-workbench.component';

function seedPhase5Company(store: CompanyContextStore): CompanySummary {
  const company: CompanySummary = {
    id: 'comp-tn-01',
    code: 'MCPB01',
    legalName: 'MoneyCraft Parity Benchmark Pvt Ltd',
    mailingName: 'MoneyCraft Parity Benchmark Pvt Ltd',
    countryCode: 'IN',
    stateCode: '33',
    pinCode: '600001',
    baseCurrencyCode: 'INR',
    currencySymbol: RUPEE_SYMBOL,
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
      multiGstinEnabled: false,
      gstRegistrationType: 'REGULAR',
      gstin: '33AABCM1234F1Z4',
      gstr1Periodicity: 'MONTHLY',
      gstEffectiveFrom: '2025-04-01',
      tdsEnabled: true,
      mcaEditLogEnforced: true,
    },
  };
  store.setCompanies([company], company.id);
  store.setPeriodRange('2025-04-01', '2025-04-30');
  return company;
}

function commonProviders() {
  return [
    provideRouter(APP_ROUTES),
    provideHttpClient(
      withInterceptors([etagInterceptor, idempotencyInterceptor, problemDetailsInterceptor]),
    ),
    provideHttpClientTesting(),
  ];
}

async function flushAsyncTicks(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

describe('Phase 5 Statutory Returns, Rel 7.2 IMS, TDS 26Q & Alt+Q MCA Edit Log', () => {
  it('renders GSTR-1 (1,47,800.00 taxable, 20,064.00 tax, bifurcated HSN 90189099 + 90251190, Ctrl+J Uncertain conflict), GSTR-3B (19,908.00 ITC), and One-Click GSTR-9 .xlsx/.json exports', async () => {
    await TestBed.configureTestingModule({
      imports: [Gstr1DashboardComponent, Gstr3bDashboardComponent, Gstr9DashboardComponent],
      providers: commonProviders(),
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyStore = TestBed.inject(CompanyContextStore);
    const shortcutRegistry = TestBed.inject(ShortcutCommandRegistry);
    const statutoryService = TestBed.inject(StatutoryService);
    seedPhase5Company(companyStore);

    // 1A. GSTR-1 Dashboard + Bifurcated HSN + Ctrl+J Uncertain Transactions
    const gstr1Fixture = TestBed.createComponent(Gstr1DashboardComponent);
    gstr1Fixture.detectChanges();

    const gstr1Req = httpMock.expectOne((req) =>
      req.url.includes('/api/v1/companies/comp-tn-01/gst/gstr1'),
    );
    expect(gstr1Req.request.method).toBe('GET');
    gstr1Req.flush({
      fromDate: '2025-04-01',
      toDate: '2025-04-30',
      gstin: '33AABCM1234F1Z4',
      b2b: [
        {
          voucherId: 'vch-s-1',
          partyGstin: '29AABCT1332L1ZT',
          partyName: 'Kaveri Meditech Bengaluru',
          invoiceNumber: 'S/25-26/001',
          invoiceDate: '2025-04-10',
          invoiceValue: '118000.00',
          placeOfSupply: '29',
          taxableValue: '100000.00',
          igstAmount: '18000.00',
          cgstAmount: '0.00',
          sgstAmount: '0.00',
          cessAmount: '0.00',
        },
      ],
      b2cl: [],
      b2cs: [
        {
          supplyType: 'INTRA',
          placeOfSupply: '33',
          gstRatePct: '5.00',
          taxableValue: '47800.00',
          igstAmount: '0.00',
          cgstAmount: '1032.00',
          sgstAmount: '1032.00',
          cessAmount: '0.00',
        },
      ],
      cdnr: [],
      exp: [],
      hsnB2b: [
        {
          hsnSacCode: '90189099',
          description: 'Surgical Diagnostic Catheters',
          uqcCode: 'NOS',
          totalQuantity: '100',
          taxableValue: '100000.00',
          igstAmount: '18000.00',
          cgstAmount: '0.00',
          sgstAmount: '0.00',
          cessAmount: '0.00',
        },
      ],
      hsnB2c: [
        {
          hsnSacCode: '90251190',
          description: 'Clinical Digital Thermometers',
          uqcCode: 'NOS',
          totalQuantity: '200',
          taxableValue: '47800.00',
          igstAmount: '0.00',
          cgstAmount: '1032.00',
          sgstAmount: '1032.00',
          cessAmount: '0.00',
        },
      ],
      documentSummary: [
        {
          documentNature: 'Invoices for outward supply',
          serialFrom: 'S/25-26/001',
          serialTo: 'S/25-26/005',
          totalCount: 5,
          cancelledCount: 1,
          netIssuedCount: 4,
        },
      ],
      uncertainCount: 1,
      totalTaxableValue: '147800.00',
      totalIgst: '18000.00',
      totalCgst: '1032.00',
      totalSgst: '1032.00',
      totalCess: '0.00',
      totalTax: '20064.00',
    });

    await flushAsyncTicks();
    gstr1Fixture.detectChanges();

    const gstr1El: HTMLElement = gstr1Fixture.nativeElement;
    expect(gstr1El.querySelector('[data-testid="gstr1-total-taxable"]')?.textContent).toContain(
      '1,47,800.00',
    );
    expect(gstr1El.querySelector('[data-testid="gstr1-total-tax"]')?.textContent).toContain(
      '20,064.00',
    );
    expect(gstr1El.querySelector('[data-testid="gstr1-hsn-b2b-panel"]')?.textContent).toContain(
      '90189099',
    );
    expect(gstr1El.querySelector('[data-testid="gstr1-hsn-b2c-panel"]')?.textContent).toContain(
      '90251190',
    );
    expect(gstr1El.querySelector('[data-testid="gstr1-uncertain-count"]')?.textContent?.trim()).toBe(
      '1',
    );

    // Trigger Ctrl+J (`report.exceptions`) to resolve Uncertain Transaction conflict
    shortcutRegistry.triggerCommand('report.exceptions');
    gstr1Fixture.detectChanges();

    const uncReq = httpMock.expectOne((req) =>
      req.url.includes('/api/v1/companies/comp-tn-01/gst/uncertain-transactions'),
    );
    uncReq.flush([
      {
        voucherId: 'vch-unc-01',
        voucherDate: '2025-04-18',
        voucherTypeName: 'Sales',
        voucherNumber: 'S/25-26/004',
        partyName: 'Delta Clinic',
        partyGstin: '33AABCD9999K1Z2',
        exceptionCode: 'TAX_OVERRIDE_MISMATCH',
        exceptionReason: 'Manual tax override differs by Re 0.40',
        taxableAmount: '47800.00',
        computedTaxAmount: '2064.40',
        bookedTaxAmount: '2064.00',
        differenceAmount: '0.40',
      },
    ]);
    await flushAsyncTicks();
    gstr1Fixture.detectChanges();

    // Empty reason must block conflict acceptance
    const acceptBtn: HTMLButtonElement = gstr1El.querySelector(
      '[data-testid="accept-conflict-btn-vch-unc-01"]',
    )!;
    acceptBtn.click();
    gstr1Fixture.detectChanges();
    expect(gstr1El.textContent).toContain('Mandatory audit reason');
    httpMock.expectNone((req) => req.url.includes('/accept-conflict'));

    // Provide audit reason and accept conflict
    gstr1Fixture.componentInstance.setReason(
      'vch-unc-01',
      'Commercial invoice rounded to nearest rupee as per contract',
    );
    gstr1Fixture.detectChanges();
    acceptBtn.click();

    const acceptConflictReq = httpMock.expectOne(
      (req) =>
        req.method === 'POST' &&
        req.url.includes(
          '/api/v1/companies/comp-tn-01/gst/uncertain-transactions/vch-unc-01/accept-conflict',
        ),
    );
    expect(acceptConflictReq.request.body).toEqual({
      reason: 'Commercial invoice rounded to nearest rupee as per contract',
    });
    acceptConflictReq.flush({ status: 'ACCEPTED' });
    await flushAsyncTicks();
    gstr1Fixture.detectChanges();
    expect(gstr1El.querySelector('[data-testid="gstr1-uncertain-count"]')?.textContent?.trim()).toBe(
      '0',
    );
    gstr1Fixture.destroy();

    // 1B. GSTR-3B Dashboard (`19,908.00` Eligible ITC)
    const gstr3bFixture = TestBed.createComponent(Gstr3bDashboardComponent);
    gstr3bFixture.detectChanges();

    const gstr3bReq = httpMock.expectOne((req) =>
      req.url.includes('/api/v1/companies/comp-tn-01/gst/gstr3b'),
    );
    gstr3bReq.flush({
      returnPeriod: '042025',
      gstin: '33AABCM1234F1Z4',
      outwardTaxable: {
        taxableValue: '147800.00',
        igstAmount: '18000.00',
        cgstAmount: '1032.00',
        sgstAmount: '1032.00',
        cessAmount: '0.00',
        totalTax: '20064.00',
      },
      inwardRcm: {
        taxableValue: '0.00',
        igstAmount: '0.00',
        cgstAmount: '0.00',
        sgstAmount: '0.00',
        cessAmount: '0.00',
        totalTax: '0.00',
      },
      eligibleItc: {
        taxableValue: '110600.00',
        igstAmount: '18000.00',
        cgstAmount: '954.00',
        sgstAmount: '954.00',
        cessAmount: '0.00',
        totalTax: '19908.00',
      },
      itcReversed: {
        taxableValue: '0.00',
        igstAmount: '0.00',
        cgstAmount: '0.00',
        sgstAmount: '0.00',
        cessAmount: '0.00',
        totalTax: '0.00',
      },
      netTaxPayable: {
        igstAmount: '0.00',
        cgstAmount: '78.00',
        sgstAmount: '78.00',
        cessAmount: '0.00',
        totalTax: '156.00',
      },
    });
    await flushAsyncTicks();
    gstr3bFixture.detectChanges();

    const gstr3bEl: HTMLElement = gstr3bFixture.nativeElement;
    expect(gstr3bEl.querySelector('[data-testid="gstr3b-eligible-itc"]')?.textContent).toContain(
      '19,908.00',
    );
    expect(gstr3bEl.querySelector('[data-testid="gstr3b-net-payable"]')?.textContent).toContain(
      '156.00',
    );

    // Loop 5: Multi-GSTIN registration selector on GSTR-3B (`ensureRegistrationsLoaded` + `onRegistrationChange`)
    const regLoadPromise = gstr3bFixture.componentInstance.ensureRegistrationsLoaded();
    const regListReq = httpMock.expectOne((req) =>
      req.url.includes('/api/v1/companies/comp-tn-01/gst/registrations'),
    );
    regListReq.flush([
      {
        id: 'reg-tn-01',
        gstin: '33AABCM1234F1Z4',
        stateCode: '33',
        stateName: 'Tamil Nadu',
        registrationType: 'REGULAR',
      },
    ]);
    await regLoadPromise;
    gstr3bFixture.detectChanges();
    expect(gstr3bFixture.componentInstance.registrations().length).toBe(1);

    gstr3bFixture.componentInstance.onRegistrationChange('reg-tn-01');
    const gstr3bByRegReq = httpMock.expectOne(
      (req) =>
        req.url.includes('/api/v1/companies/comp-tn-01/gst/gstr3b') &&
        req.params.get('gstRegistrationId') === 'reg-tn-01',
    );
    gstr3bByRegReq.flush(gstr3bFixture.componentInstance.report());
    await flushAsyncTicks();
    gstr3bFixture.destroy();

    // 1C. Rel 7.2 One-Click GSTR-9 Annual Return (.xlsx + .json exports)
    const gstr9Fixture = TestBed.createComponent(Gstr9DashboardComponent);
    gstr9Fixture.detectChanges();

    const gstr9Get = httpMock.expectOne((req) =>
      req.url.includes('/api/v1/companies/comp-tn-01/gst/gstr9'),
    );
    gstr9Get.flush({
      fiscalYearId: 'FY2025-26',
      table4Outward: [
        {
          tableCode: '4B',
          description: 'Supplies made to registered persons (B2B)',
          taxableValue: '100000.00',
          igstAmount: '18000.00',
          cgstAmount: '0.00',
          sgstAmount: '0.00',
          cessAmount: '0.00',
        },
      ],
      table6ItcAvailed: [
        {
          tableCode: '6B',
          description: 'Inward supplies (other than imports & RCM)',
          taxableValue: '110600.00',
          igstAmount: '18000.00',
          cgstAmount: '954.00',
          sgstAmount: '954.00',
          cessAmount: '0.00',
        },
      ],
      table17HsnOutward: [],
      totalOutwardTaxable: '147800.00',
      totalOutwardTax: '20064.00',
      totalItcAvailed: '19908.00',
      totalItcReversed: '0.00',
      netTaxPayable: '156.00',
      reconciliationDifference: '0.00',
    });
    await flushAsyncTicks();
    gstr9Fixture.detectChanges();

    const gstr9El: HTMLElement = gstr9Fixture.nativeElement;
    (gstr9El.querySelector('[data-testid="gstr9-export-xlsx-btn"]') as HTMLButtonElement).click();
    const xlsxExportReq = httpMock.expectOne(
      (req) =>
        req.method === 'POST' &&
        req.url.includes('/api/v1/companies/comp-tn-01/gst/gstr9/export') &&
        req.params.get('format') === 'XLSX',
    );
    xlsxExportReq.flush(new Blob(['PK-xlsx-bytes'], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }));
    await flushAsyncTicks();
    expect(statutoryService.lastDownloadedFilename).toBe('GSTR9_FY2025-26.xlsx');

    (gstr9El.querySelector('[data-testid="gstr9-export-json-btn"]') as HTMLButtonElement).click();
    const jsonExportReq = httpMock.expectOne(
      (req) =>
        req.method === 'POST' &&
        req.url.includes('/api/v1/companies/comp-tn-01/gst/gstr9/export') &&
        req.params.get('format') === 'JSON',
    );
    jsonExportReq.flush(new Blob(['{"fp":"032026"}'], { type: 'application/json' }));
    await flushAsyncTicks();
    expect(statutoryService.lastDownloadedFilename).toBe('GSTR9_FY2025-26.json');

    gstr9Fixture.destroy();
    httpMock.verify();
  });

  it('supports Rel 7.2 IMS keyboard Accept (Alt+A), Pending (Alt+P), Reject (Alt+R), and enforces mandatory remarks on REJECT or itcReductionAmount > 0', async () => {
    await TestBed.configureTestingModule({
      imports: [ImsWorkbenchComponent],
      providers: commonProviders(),
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyStore = TestBed.inject(CompanyContextStore);
    const shortcutRegistry = TestBed.inject(ShortcutCommandRegistry);
    seedPhase5Company(companyStore);

    const fixture = TestBed.createComponent(ImsWorkbenchComponent);
    fixture.detectChanges();

    const listReq = httpMock.expectOne((req) =>
      req.url.includes('/api/v1/companies/comp-tn-01/gst/ims/invoices'),
    );
    listReq.flush([
      {
        id: 'ims-inv-1',
        supplierGstin: '29AABCT1332L1ZT',
        supplierName: 'Bengaluru BioLabs Ltd',
        invoiceNumber: 'BLR-101',
        invoiceDate: '2025-04-05',
        returnPeriod: '042025',
        invoiceValue: '118000.00',
        taxableValue: '100000.00',
        igstAmount: '18000.00',
        cgstAmount: '0.00',
        sgstAmount: '0.00',
        cessAmount: '0.00',
        portalItcAmount: '18000.00',
        matchStatus: 'EXACT_MATCH',
        imsStatus: 'NO_ACTION',
        itcReductionAmount: '0.00',
      },
      {
        id: 'ims-inv-2',
        supplierGstin: '33AAFCK5678G1ZC',
        supplierName: 'Chennai Surgical Traders',
        invoiceNumber: 'CST-209',
        invoiceDate: '2025-04-12',
        returnPeriod: '042025',
        invoiceValue: '35400.00',
        taxableValue: '30000.00',
        igstAmount: '0.00',
        cgstAmount: '2700.00',
        sgstAmount: '2700.00',
        cessAmount: '0.00',
        portalItcAmount: '5400.00',
        matchStatus: 'MISSING_IN_BOOKS',
        imsStatus: 'NO_ACTION',
        itcReductionAmount: '0.00',
      },
      {
        id: 'ims-inv-3',
        supplierGstin: '33AABCM9876D1Z9',
        supplierName: 'Madras Packaging Works',
        invoiceNumber: 'MPW-315',
        invoiceDate: '2025-04-19',
        returnPeriod: '042025',
        invoiceValue: '12508.00',
        taxableValue: '10600.00',
        igstAmount: '0.00',
        cgstAmount: '954.00',
        sgstAmount: '954.00',
        cessAmount: '0.00',
        portalItcAmount: '1908.00',
        matchStatus: 'PARTIAL_MISMATCH',
        imsStatus: 'NO_ACTION',
        itcReductionAmount: '0.00',
      },
    ]);
    await flushAsyncTicks();
    fixture.detectChanges();

    // 1. Keyboard Alt+A (`register.addVoucher`) accepts row 0 (`ims-inv-1`)
    shortcutRegistry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'a', code: 'KeyA', altKey: true, cancelable: true }),
    );
    const acceptReq = httpMock.expectOne(
      (req) =>
        req.method === 'POST' && req.url.includes('/api/v1/companies/comp-tn-01/gst/ims/actions'),
    );
    expect(acceptReq.request.body.action).toBe('ACCEPT');
    expect(acceptReq.request.body.imsInvoiceId).toBe('ims-inv-1');
    acceptReq.flush({
      ...fixture.componentInstance.invoices()[0],
      imsStatus: 'ACCEPT',
      itcReductionAmount: '0.00',
    });
    await flushAsyncTicks();
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="ims-net-eligible-itc"]')?.textContent).toContain(
      '18,000.00',
    );

    // 2. Focus row 1 (`ims-inv-2`) and press Alt+R (`Reject`) -> mandatory remarks enforced!
    fixture.componentInstance.focusRow(1);
    fixture.detectChanges();
    shortcutRegistry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'r', code: 'KeyR', altKey: true, cancelable: true }),
    );
    fixture.detectChanges();
    expect(fixture.componentInstance.actionModalOpen()).toBe(true);
    expect(fixture.componentInstance.modalAction()).toBe('REJECT');

    // Attempt submit with blank remarks -> must block!
    await fixture.componentInstance.submitModalAction();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="ims-modal-error"]')?.textContent).toContain(
      'Remarks are mandatory',
    );
    httpMock.expectNone((req) => req.url.includes('/gst/ims/actions'));

    // Provide remarks -> succeeds!
    fixture.componentInstance.modalRemarks.set('Goods not received at Chennai warehouse');
    const rejectPromise = fixture.componentInstance.submitModalAction();
    const rejectReq = httpMock.expectOne((req) =>
      req.url.includes('/api/v1/companies/comp-tn-01/gst/ims/actions'),
    );
    expect(rejectReq.request.body).toMatchObject({
      imsInvoiceId: 'ims-inv-2',
      action: 'REJECT',
      remarks: 'Goods not received at Chennai warehouse',
    });
    rejectReq.flush({
      ...fixture.componentInstance.invoices()[1],
      imsStatus: 'REJECT',
      remarks: 'Goods not received at Chennai warehouse',
    });
    await rejectPromise;
    fixture.detectChanges();

    // 3. Focus row 2 (`ims-inv-3`): first mark Pending via Alt+P, then Accept with partial ITC reduction
    fixture.componentInstance.focusRow(2);
    fixture.detectChanges();
    shortcutRegistry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'p', code: 'KeyP', altKey: true, cancelable: true }),
    );
    const pendingReq = httpMock.expectOne((req) =>
      req.url.includes('/api/v1/companies/comp-tn-01/gst/ims/actions'),
    );
    expect(pendingReq.request.body.action).toBe('PENDING');
    pendingReq.flush({
      ...fixture.componentInstance.invoices()[2],
      imsStatus: 'PENDING',
    });
    await flushAsyncTicks();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="ims-pending-itc"]')?.textContent).toContain('1,908.00');

    // Now open Partial ITC Reduction (`ACCEPT` with `itcReductionAmount = '408.00'`) without remarks -> must block!
    fixture.componentInstance.openActionModal('ACCEPT', true);
    fixture.componentInstance.modalRemarks.set('');
    fixture.componentInstance.modalItcReduction.set('408.00');
    fixture.detectChanges();

    await fixture.componentInstance.submitModalAction();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="ims-modal-error"]')?.textContent).toContain(
      'Remarks are mandatory',
    );
    httpMock.expectNone((req) => req.url.includes('/gst/ims/actions'));

    // Enter remarks -> net eligible ITC becomes 18,000.00 + (1,908.00 - 408.00) = 19,500.00!
    fixture.componentInstance.modalRemarks.set('Rule 42 common credit proportionate reversal');
    const partialPromise = fixture.componentInstance.submitModalAction();
    const partialReq = httpMock.expectOne((req) =>
      req.url.includes('/api/v1/companies/comp-tn-01/gst/ims/actions'),
    );
    expect(partialReq.request.body).toMatchObject({
      imsInvoiceId: 'ims-inv-3',
      action: 'ACCEPT',
      itcReductionAmount: '408.00',
      remarks: 'Rule 42 common credit proportionate reversal',
    });
    partialReq.flush({
      ...fixture.componentInstance.invoices()[2],
      imsStatus: 'ACCEPT',
      itcReductionAmount: '408.00',
      remarks: 'Rule 42 common credit proportionate reversal',
    });
    await partialPromise;
    fixture.detectChanges();

    expect(el.querySelector('[data-testid="ims-net-eligible-itc"]')?.textContent).toContain(
      '19,500.00',
    );
    expect(el.querySelector('[data-testid="ims-total-reductions"]')?.textContent).toContain(
      '408.00',
    );

    // 4. Loop 5: IMS Reset (`quickReset` -> `NO_ACTION` with `0.00` reduction, `0x1410926f0`)
    const resetPromise = fixture.componentInstance.quickReset();
    const resetReq = httpMock.expectOne((req) =>
      req.url.includes('/api/v1/companies/comp-tn-01/gst/ims/actions'),
    );
    expect(resetReq.request.body).toMatchObject({
      imsInvoiceId: 'ims-inv-3',
      action: 'NO_ACTION',
      itcReductionAmount: '0.00',
      remarks: null,
    });
    resetReq.flush({
      ...fixture.componentInstance.invoices()[2],
      imsStatus: 'NO_ACTION',
      itcReductionAmount: '0.00',
      remarks: null,
    });
    await resetPromise;
    fixture.detectChanges();
    expect(fixture.componentInstance.invoices()[2].imsStatus).toBe('NO_ACTION');
    expect(el.querySelector('[data-testid="ims-net-eligible-itc"]')?.textContent).toContain(
      '18,000.00',
    );

    fixture.destroy();
    httpMock.verify();
  });

  it('renders TDS Form 26Q (45,000.00 gross, 4,500.00 tax), records ITNS 281 challan, and exports Protean FVU 1.2 file', async () => {
    await TestBed.configureTestingModule({
      imports: [Tds26qWorkbenchComponent],
      providers: commonProviders(),
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyStore = TestBed.inject(CompanyContextStore);
    const statutoryService = TestBed.inject(StatutoryService);
    seedPhase5Company(companyStore);

    const fixture = TestBed.createComponent(Tds26qWorkbenchComponent);
    fixture.detectChanges();

    const q1Req = httpMock.expectOne(
      (req) =>
        req.url.includes('/api/v1/companies/comp-tn-01/tds/form26q') &&
        req.params.get('quarter') === 'Q1',
    );
    q1Req.flush({
      quarter: 'Q1',
      fiscalYearId: 'FY2025-26',
      deductions: [
        {
          voucherId: 'vch-jrn-1',
          voucherNumber: '1',
          deductionDate: '2025-04-25',
          sectionCode: '194J',
          deducteeName: 'Sundaram Legal Associates',
          deducteePan: 'AABFS4321M',
          hasValidPan: true,
          higherRate206aaApplied: false,
          grossAmount: '45000.00',
          tdsRatePct: '10.00',
          tdsAmount: '4500.00',
        },
      ],
      challans: [],
      totalGrossAmount: '45000.00',
      totalTdsDeducted: '4500.00',
      totalTdsDeposited: '0.00',
      unpaidTdsAmount: '4500.00',
    });
    await flushAsyncTicks();
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="tds-total-gross"]')?.textContent).toContain('45,000.00');
    expect(el.querySelector('[data-testid="tds-total-deducted"]')?.textContent).toContain(
      '4,500.00',
    );
    expect(el.querySelector('[data-testid="tds-unpaid-balance"]')?.textContent).toContain(
      '4,500.00',
    );

    // Record ITNS 281 Challan (`POST /tds/challans`)
    (el.querySelector('[data-testid="tds-open-challan-modal-btn"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    const challanPromise = fixture.componentInstance.submitChallan();
    const challanReq = httpMock.expectOne(
      (req) =>
        req.method === 'POST' && req.url.includes('/api/v1/companies/comp-tn-01/tds/challans'),
    );
    expect(challanReq.request.body).toMatchObject({
      quarter: 'Q1',
      sectionCode: '194J',
      bsrCode: '0510308',
      challanSerialNo: '00412',
      tdsAmount: '4500.00',
    });
    challanReq.flush({
      id: 'ch-281-01',
      quarter: 'Q1',
      sectionCode: '194J',
      bsrCode: '0510308',
      challanSerialNo: '00412',
      depositDate: '2025-05-07',
      tdsAmount: '4500.00',
      totalAmount: '4500.00',
    });
    await challanPromise;
    fixture.detectChanges();

    expect(el.querySelector('[data-testid="tds-total-deposited"]')?.textContent).toContain(
      '4,500.00',
    );
    expect(el.querySelector('[data-testid="tds-unpaid-balance"]')?.textContent).toContain('0.00');

    // Export Protean FVU 1.2 (`POST /tds/export-fvu`)
    (el.querySelector('[data-testid="tds-export-fvu-btn"]') as HTMLButtonElement).click();
    const fvuReq = httpMock.expectOne(
      (req) =>
        req.method === 'POST' &&
        req.url.includes('/api/v1/companies/comp-tn-01/tds/export-fvu') &&
        req.params.get('quarter') === 'Q1',
    );
    fvuReq.flush(new Blob(['^FH^NS1^26Q^'], { type: 'text/plain' }));
    await flushAsyncTicks();
    expect(statutoryService.lastDownloadedFilename).toBe('Form26Q_Q1_FY2025-26.fvu');

    fixture.destroy();
    httpMock.verify();
  });

  it('opens Alt+Q EditLogRedlineModalComponent defaulting to Show Altered Values Only and renders Journal #1 v1 (40,000.00) -> v2 (45,000.00) redline diffs', async () => {
    await TestBed.configureTestingModule({
      imports: [DayBookComponent, EditLogRedlineModalComponent],
      providers: commonProviders(),
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyStore = TestBed.inject(CompanyContextStore);
    const shortcutRegistry = TestBed.inject(ShortcutCommandRegistry);
    const navStack = TestBed.inject(NavigationStackStore);
    const editLogModalService = TestBed.inject(EditLogModalService);
    seedPhase5Company(companyStore);

    const dayBookFixture = TestBed.createComponent(DayBookComponent);
    const redlineFixture = TestBed.createComponent(EditLogRedlineModalComponent);
    dayBookFixture.detectChanges();
    redlineFixture.detectChanges();

    // Flush DayBook initial requests (`/voucher-types` + `/vouchers`)
    const vtReqs = httpMock.match((req) => req.url.includes('/voucher-types'));
    for (const r of vtReqs) {
      r.flush([]);
    }
    const dayBookReq = httpMock.expectOne((req) =>
      req.url.includes('/api/v1/companies/comp-tn-01/vouchers'),
    );
    dayBookReq.flush({
      items: [
        {
          id: 'vch-jrn-1',
          voucherDate: '2025-04-25',
          voucherTypeId: 'vt-journal',
          voucherTypeName: 'Journal',
          voucherCategory: 'JOURNAL',
          voucherNumber: '1',
          primaryParticulars: 'Professional Fees Dr',
          debitAmount: '45000.00',
          creditAmount: '45000.00',
          status: 'POSTED',
          postingNature: 'NORMAL',
          versionNo: 2,
        },
      ],
      nextCursor: null,
      hasMore: false,
    });
    await flushAsyncTicks();
    dayBookFixture.detectChanges();

    // Press Alt+Q (`audit.editLog`) from DayBook with `Journal #1` selected
    shortcutRegistry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'q', code: 'KeyQ', altKey: true, cancelable: true }),
    );
    redlineFixture.detectChanges();

    expect(editLogModalService.isOpen()).toBe(true);
    expect(navStack.depth()).toBe(1);

    const versionsReq = httpMock.expectOne(
      (req) =>
        req.method === 'GET' &&
        req.url.includes('/api/v1/companies/comp-tn-01/edit-log/VOUCHER/vch-jrn-1/versions'),
    );
    versionsReq.flush([
      {
        versionNo: 1,
        masterId: 101,
        alterId: 101,
        operation: 'CREATE',
        actorUsername: 'audit_admin',
        occurredAt: '2025-04-25T10:15:00Z',
        voucherNumberSnapshot: '1',
        voucherDateSnapshot: '2025-04-25',
        canonicalSnapshot: {
          voucherTypeName: 'Journal',
          voucherNumber: '1',
          narration: 'Initial audit retainer',
          debitTotal: '38000.00',
        },
        changedFields: [],
        snapshotSha256Hex: '8b2c4d9f01a3e56789abcdef0123456789abcdef0123456789abcdef01234567',
      },
      {
        versionNo: 2,
        masterId: 101,
        alterId: 105,
        operation: 'ALTER',
        actorUsername: 'audit_admin',
        occurredAt: '2025-04-25T12:00:00Z',
        voucherNumberSnapshot: '1',
        voucherDateSnapshot: '2025-04-25',
        canonicalSnapshot: {
          voucherTypeName: 'Journal',
          voucherNumber: '1',
          narration: 'Audit & legal retainer',
          debitTotal: '40000.00',
        },
        changedFields: [
          {
            fieldPath: 'narration',
            beforeValue: 'Initial audit retainer',
            afterValue: 'Audit & legal retainer',
          },
          {
            fieldPath: 'debitTotal',
            beforeValue: '38000.00',
            afterValue: '40000.00',
          },
        ],
        snapshotSha256Hex: 'c44e7a00112233445566778899aabbccddeeff00112233445566778899aabbcc',
      },
      {
        versionNo: 3,
        masterId: 101,
        alterId: 108,
        operation: 'ALTER',
        actorUsername: 'audit_admin',
        occurredAt: '2025-04-25T14:30:00Z',
        voucherNumberSnapshot: '1',
        voucherDateSnapshot: '2025-04-25',
        canonicalSnapshot: {
          voucherTypeName: 'Journal',
          voucherNumber: '1',
          narration: 'Audit & legal retainer',
          debitTotal: '45000.00',
        },
        changedFields: [
          {
            fieldPath: 'debitTotal',
            beforeValue: '40000.00',
            afterValue: '45000.00',
          },
        ],
        snapshotSha256Hex: 'f91e7a00112233445566778899aabbccddeeff00112233445566778899aabbcc',
      },
    ]);

    await flushAsyncTicks();
    redlineFixture.detectChanges();

    // TallyPrime 7.2 Parity Rule: strictly defaults to `Show Altered Values Only = true`
    expect(redlineFixture.componentInstance.showAlteredValuesOnly()).toBe(true);

    const modalEl: HTMLElement = redlineFixture.nativeElement;
    expect(modalEl.textContent).toContain('Edit Log — Journal #1');
    expect(modalEl.querySelector('[data-testid="edit-log-filter-mode"]')?.textContent).toContain(
      'Show Altered Values Only',
    );

    // Only the altered `debitTotal` row is rendered by default (`40,000.00` -> `45,000.00` between v2 and v3)
    const alteredRows = modalEl.querySelectorAll('[data-testid="edit-log-redline-table"] tbody tr');
    expect(alteredRows.length).toBe(1);
    expect(modalEl.querySelector('[data-testid="diff-before-cell"]')?.textContent?.trim()).toBe(
      '40,000.00',
    );
    expect(modalEl.querySelector('[data-testid="diff-after-cell"]')?.textContent?.trim()).toBe(
      '45,000.00',
    );

    // Loop 4: Compare Version 3 directly against Version 1 (index 0) via `selectCompareBaselineIndex(0)`
    redlineFixture.componentInstance.selectCompareBaselineIndex(0);
    redlineFixture.detectChanges();
    expect(
      modalEl.querySelector('[data-testid="edit-log-comparison-heading"]')?.textContent,
    ).toContain('Comparing Version 1 (CREATE)');
    const cumulativeRows = modalEl.querySelectorAll(
      '[data-testid="edit-log-redline-table"] tbody tr',
    );
    expect(cumulativeRows.length).toBe(2);

    // Press F12 (`screen.configure` in MODAL scope) -> toggles to Show All Fields
    shortcutRegistry.triggerCommand('screen.configure');
    redlineFixture.detectChanges();
    expect(redlineFixture.componentInstance.showAlteredValuesOnly()).toBe(false);
    const allRows = modalEl.querySelectorAll('[data-testid="edit-log-redline-table"] tbody tr');
    expect(allRows.length).toBeGreaterThan(2);

    // Closing modal pops NavigationStackStore back to depth 0
    editLogModalService.close();
    redlineFixture.detectChanges();
    expect(editLogModalService.isOpen()).toBe(false);
    expect(navStack.depth()).toBe(0);

    dayBookFixture.destroy();
    redlineFixture.destroy();
    httpMock.verify();
  });

  it('validates Multi-GSTIN Luhn Mod-36 checksum live and renders 64-char SHA-256 IRN in .mc-code with air-gapped Offline Bulk JSON export', async () => {
    await TestBed.configureTestingModule({
      imports: [GstRegistrationWorkbenchComponent, EinvoiceWorkbenchComponent],
      providers: commonProviders(),
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyStore = TestBed.inject(CompanyContextStore);
    const statutoryService = TestBed.inject(StatutoryService);
    seedPhase5Company(companyStore);

    // 1. Multi-GSTIN Workbench live Mod-36 validation
    const gstFixture = TestBed.createComponent(GstRegistrationWorkbenchComponent);
    gstFixture.detectChanges();

    httpMock
      .expectOne((req) => req.url.includes('/api/v1/companies/comp-tn-01/gst/registrations'))
      .flush([]);
    void gstFixture.componentInstance.loadRulePacks();
    httpMock
      .expectOne((req) => req.url.includes('/api/v1/companies/comp-tn-01/statutory/rule-packs'))
      .flush([
        {
          id: 'rp-2025-in',
          packCode: 'IN_GST_TDS_FY2025_26',
          jurisdiction: 'IN',
          effectiveFrom: '2025-04-01',
          effectiveTo: null,
          b2clThreshold: '250000.00',
          gstSlabs: ['0', '5', '12', '18', '28'],
        },
      ]);
    await flushAsyncTicks();
    gstFixture.detectChanges();

    // Tampered checksum ('0' instead of '4') must fail Luhn Mod-36 validation
    gstFixture.componentInstance.gstin.set('33AABCM1234F1Z0');
    gstFixture.detectChanges();
    expect(gstFixture.componentInstance.gstinValidation().valid).toBe(false);

    // Valid Tamil Nadu GSTIN passes Luhn Mod-36 validation
    gstFixture.componentInstance.gstin.set('33AABCM1234F1Z4');
    gstFixture.detectChanges();
    expect(gstFixture.componentInstance.gstinValidation().valid).toBe(true);
    gstFixture.destroy();

    // 2. e-Invoice IRN (.mc-code) + Offline Bulk JSON export
    const einvFixture = TestBed.createComponent(EinvoiceWorkbenchComponent);
    einvFixture.detectChanges();

    einvFixture.componentInstance.voucherId.set('vch-s-1');
    einvFixture.detectChanges();
    (einvFixture.nativeElement.querySelector('[data-testid="generate-irn-btn"]') as HTMLButtonElement).click();

    const irnReq = httpMock.expectOne(
      (req) =>
        req.method === 'POST' &&
        req.url.includes('/api/v1/companies/comp-tn-01/gst/einvoice/vch-s-1/generate'),
    );
    const irn64 = 'a3f5c8e90123456789abcdef0123456789abcdef0123456789abcdef01234567';
    irnReq.flush({
      voucherId: 'vch-s-1',
      irn: irn64,
      ackNo: '112510009876543',
      ackDate: '2025-04-10 11:20:00',
      status: 'GENERATED',
    });
    await flushAsyncTicks();
    einvFixture.detectChanges();

    const irnEl = einvFixture.nativeElement.querySelector('[data-testid="einvoice-irn-code"]') as HTMLElement;
    expect(irnEl.classList.contains('mc-code')).toBe(true);
    expect(irnEl.textContent?.trim()).toBe(irn64);

    // Trigger air-gapped Offline Bulk JSON export
    (
      einvFixture.nativeElement.querySelector(
        '[data-testid="einvoice-offline-export-btn"]',
      ) as HTMLButtonElement
    ).click();
    const offlineReq = httpMock.expectOne(
      (req) =>
        req.method === 'POST' &&
        req.url.includes('/api/v1/companies/comp-tn-01/gst/einvoice/export-offline-json'),
    );
    offlineReq.flush(new Blob(['{"Version":"1.1"}'], { type: 'application/json' }));
    await flushAsyncTicks();
    expect(statutoryService.lastDownloadedFilename).toBe('einvoice_bulk_offline.json');

    einvFixture.destroy();
    httpMock.verify();
  });
});
