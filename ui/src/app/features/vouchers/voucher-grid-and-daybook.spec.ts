import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, convertToParamMap, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { APP_ROUTES } from '../../app.routes';
import { AuthStore } from '../../core/auth/auth.store';
import { etagInterceptor } from '../../core/http/etag.interceptor';
import { idempotencyInterceptor } from '../../core/http/idempotency.interceptor';
import { problemDetailsInterceptor } from '../../core/http/problem-details.interceptor';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { RUPEE_SYMBOL } from '../../core/money/exact-decimal';
import { CompanyContextStore, CompanySummary } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { ChartOfAccountsStore } from '../masters/chart-of-accounts.store';
import {
  AccountGroup,
  CostCentre,
  Ledger,
  PREDEFINED_28_ACCOUNT_GROUPS,
  VoucherTypeMaster,
} from '../masters/masters.models';
import { SecondaryMasterModalService } from '../masters/secondary-master-modal.service';
import { ApprovalQueueComponent } from './approval-queue.component';
import { BankAllocationPopoverComponent } from './bank-allocation-popover.component';
import { BillWisePopoverComponent } from './bill-wise-popover.component';
import { CostAllocationPopoverComponent } from './cost-allocation-popover.component';
import { DayBookComponent } from './day-book.component';
import { GodownBatchPopoverComponent } from './godown-batch-popover.component';
import { VoucherGridComponent } from './voucher-grid.component';
import { VoucherService } from './voucher.service';
import { BillAllocationDto, CostAllocationDto, VoucherDetailDto } from './vouchers.models';

function seedPhase3Company(companyStore: CompanyContextStore): CompanySummary {
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
    lastEntryDate: '2025-05-31',
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
  companyStore.setCompanies([company], company.id);
  companyStore.setWorkingDate('2025-05-15');
  companyStore.setPeriodRange('2025-04-01', '2026-03-31');
  return company;
}

function buildGroupsAndLedgers(companyId: string): {
  readonly groups: AccountGroup[];
  readonly ledgers: Ledger[];
  readonly voucherTypes: VoucherTypeMaster[];
  readonly costCentres: CostCentre[];
} {
  const idByName = new Map<string, string>();
  PREDEFINED_28_ACCOUNT_GROUPS.forEach((desc, idx) => {
    idByName.set(desc.name, `grp-${idx + 1}`);
  });

  const groups: AccountGroup[] = PREDEFINED_28_ACCOUNT_GROUPS.map((desc, idx) => ({
    id: idByName.get(desc.name)!,
    companyId,
    name: desc.name,
    aliasCode: null,
    parentGroupId: desc.parentName ? (idByName.get(desc.parentName) ?? null) : null,
    parentGroupName: desc.parentName,
    nature: desc.nature,
    affectsGrossProfit: desc.affectsGrossProfit,
    behavesLikeSubledger: desc.behavesLikeSubledger,
    netDebitCreditForReporting: false,
    usedForCalculation: false,
    methodToAllocatePurchase: 'NOT_APPLICABLE',
    scheduleIiiLineCode: null,
    defaultGstRatePct: null,
    defaultHsnSacCode: null,
    depthLevel: desc.parentName ? 1 : 0,
    isSystemGroup: true,
    sortOrder: idx + 1,
    versionNo: 1,
  }));

  const baseLedger = (
    overrides: Partial<Ledger> & Pick<Ledger, 'id' | 'name' | 'groupId'>,
  ): Ledger => ({
    companyId,
    aliasCode: null,
    currencyCode: 'INR',
    openingBalance: '0.00',
    openingSide: 'DR',
    maintainBalancesBillByBill: false,
    defaultCreditPeriodDays: null,
    checkCreditDaysDuringVoucher: false,
    creditLimit: null,
    inventoryValuesAffected: false,
    costCentresApplicable: false,
    taxType: 'NONE',
    dutyHead: null,
    gstApplicability: 'UNDEFINED',
    gstSupplyType: null,
    hsnSacCode: null,
    gstRatePct: null,
    cessRatePct: null,
    appropriateFor: 'NONE',
    appropriationBasis: null,
    roundingMethod: 'NONE',
    roundingLimit: null,
    partyRegistrationType: 'UNKNOWN',
    gstin: null,
    countryCode: 'IN',
    stateCode: '33',
    stateName: 'Tamil Nadu',
    pincode: null,
    mailingName: overrides.name,
    mailingAddress: null,
    panPlainMasked: null,
    msmeRegistrationType: null,
    msmeUdyamNo: null,
    tdsApplicable: false,
    tdsNatureCode: null,
    tdsDeducteeType: null,
    bankAccountHolder: null,
    bankIfscCode: null,
    bankName: null,
    bankBranch: null,
    upiId: null,
    isSystemLedger: false,
    active: true,
    versionNo: 1,
    ...overrides,
  });

  const ledgers: Ledger[] = [
    baseLedger({
      id: 'led-hdfc',
      name: 'HDFC Bank Current A/c',
      groupId: idByName.get('Bank Accounts')!,
      bankName: 'HDFC Bank',
      bankIfscCode: 'HDFC0000123',
    }),
    baseLedger({
      id: 'led-cash',
      name: 'Cash',
      groupId: idByName.get('Cash-in-Hand')!,
      isSystemLedger: true,
    }),
    baseLedger({
      id: 'led-debtor-1',
      name: 'Apex Diagnostics Pvt Ltd',
      groupId: idByName.get('Sundry Debtors')!,
      maintainBalancesBillByBill: true,
      defaultCreditPeriodDays: 30,
      partyRegistrationType: 'REGULAR',
      gstin: '33AABCA5678K1Z2',
      stateCode: '33',
    }),
    baseLedger({
      id: 'led-sales-gst',
      name: 'Domestic GST Sales',
      groupId: idByName.get('Sales Accounts')!,
      gstApplicability: 'APPLICABLE',
      gstRatePct: '18',
    }),
    baseLedger({
      id: 'led-cgst',
      name: 'Output CGST 9%',
      groupId: idByName.get('Duties & Taxes')!,
      taxType: 'GST',
      dutyHead: 'CGST',
      gstRatePct: '9',
    }),
    baseLedger({
      id: 'led-sgst',
      name: 'Output SGST 9%',
      groupId: idByName.get('Duties & Taxes')!,
      taxType: 'GST',
      dutyHead: 'SGST_UTGST',
      gstRatePct: '9',
    }),
    baseLedger({
      id: 'led-rent',
      name: 'Office Rent Expense',
      groupId: idByName.get('Indirect Expenses')!,
      costCentresApplicable: true,
    }),
  ];

  const makeVt = (
    id: string,
    name: string,
    abbr: string,
    baseCategory: VoucherTypeMaster['baseCategory'],
    defaultEntryMode: VoucherTypeMaster['defaultEntryMode'],
    shortcutKey: string,
  ): VoucherTypeMaster => ({
    id,
    companyId,
    name,
    abbreviation: abbr,
    baseCategory,
    parentVoucherTypeId: null,
    postsAccountingEntries: true,
    movesInventory: false,
    defaultEntryMode,
    numberingMethod: 'AUTOMATIC_RETAIN_NUMBERS',
    preventDuplicateNumbers: true,
    useEffectiveDates: false,
    allowZeroValuedTransactions: false,
    makeOptionalByDefault: false,
    allowNarrationPerLedger: false,
    printAfterSave: false,
    isManufacturingJournal: false,
    approvalPolicy: 'SINGLE_STEP',
    shortcutKey,
    isSystemVoucherType: true,
    active: true,
    versionNo: 1,
  });

  const voucherTypes: VoucherTypeMaster[] = [
    makeVt('vt-payment', 'Payment', 'Pymt', 'PAYMENT', 'SINGLE_ENTRY', 'F5'),
    makeVt('vt-journal', 'Journal', 'Jrnl', 'JOURNAL', 'AS_VOUCHER', 'F7'),
    makeVt('vt-sales', 'Sales', 'Sale', 'SALES', 'ACCOUNTING_INVOICE', 'F8'),
  ];

  const costCentres: CostCentre[] = [
    {
      id: 'cc-chennai',
      companyId,
      costCategoryId: 'cat-primary',
      parentCostCentreId: null,
      name: 'Chennai HQ',
      aliasCode: 'CHN',
      depthLevel: 0,
      active: true,
      versionNo: 1,
    },
    {
      id: 'cc-madurai',
      companyId,
      costCategoryId: 'cat-primary',
      parentCostCentreId: null,
      name: 'Madurai Lab',
      aliasCode: 'MDU',
      depthLevel: 0,
      active: true,
      versionNo: 1,
    },
  ];

  return { groups, ledgers, voucherTypes, costCentres };
}

function flushNextNumberRequests(httpMock: HttpTestingController, nextNumber = 'INV/25-26/001'): void {
  const pending = httpMock.match((req) => req.url.includes('/vouchers/next-number'));
  for (const req of pending) {
    req.flush({ voucherNumber: nextNumber });
  }
}

async function flushAsyncTicks(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

describe('Phase 3 — Flagship Mouseless VoucherGridComponent, Sub-Ledger Popovers, Day Book & Approval Queue', () => {
  it('supports mouseless F7 Journal auto-balancing, F8 Sales Accounting Invoice with GST %, Ctrl+L Optional badge, and Ctrl+A POST with UUID Idempotency-Key', async () => {
    await TestBed.configureTestingModule({
      imports: [VoucherGridComponent],
      providers: [
        provideRouter(APP_ROUTES),
        provideHttpClient(
          withInterceptors([idempotencyInterceptor, etagInterceptor, problemDetailsInterceptor]),
        ),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyStore = TestBed.inject(CompanyContextStore);
    const coaStore = TestBed.inject(ChartOfAccountsStore);
    const shortcutRegistry = TestBed.inject(ShortcutCommandRegistry);
    const navStack = TestBed.inject(NavigationStackStore);

    const company = seedPhase3Company(companyStore);
    const { groups, ledgers } = buildGroupsAndLedgers(company.id);
    coaStore.setGroupsAndLedgers(groups, ledgers);

    const fixture = TestBed.createComponent(VoucherGridComponent);
    fixture.detectChanges();

    // Flush initial loadVoucherTypes if requested
    const vtReqs = httpMock.match((r) => r.url.endsWith('/voucher-types'));
    for (const r of vtReqs) {
      r.flush([]);
    }
    await fixture.whenStable();
    fixture.detectChanges();

    const comp = fixture.componentInstance;

    // 1. Press F7 (Journal) -> switches to AS_VOUCHER and auto-balances line 2 via ExactDecimal
    shortcutRegistry.triggerCommand('voucher.journal');
    flushNextNumberRequests(httpMock, 'JRNL-001');
    fixture.detectChanges();

    expect(comp.activeCategory()).toBe('JOURNAL');
    expect(comp.persistedView()).toBe('AS_VOUCHER');

    comp.onLineLedgerSelected(0, { id: 'led-rent', label: 'Office Rent Expense' });
    comp.onLineAmountEnter(0, '125450.75');
    fixture.detectChanges();

    expect(comp.gridLines().length).toBe(2);
    expect(comp.gridLines()[1].side).toBe('CR');
    expect(comp.gridLines()[1].amount).toBe('125450.75');

    // 2. Verify Alt+G Go To overlay push & Esc pop preserves dirty draft lines
    navStack.push({
      title: 'Chart of Accounts',
      route: '/app/coa',
      params: {},
    });
    expect(navStack.depth()).toBe(1);
    navStack.pop();
    expect(navStack.depth()).toBe(0);
    expect(comp.gridLines()[0].amount).toBe('125450.75');

    // 3. Press F8 (Sales) -> ACCOUNTING_INVOICE with GST 9% + 9% and Ctrl+L Optional toggle
    comp.initializeBlankVoucher('SALES');
    flushNextNumberRequests(httpMock, 'GST/25-26/042');
    fixture.detectChanges();

    expect(comp.activeCategory()).toBe('SALES');
    expect(comp.persistedView()).toBe('ACCOUNTING_INVOICE');

    comp.voucherNumber.set('GST/25-26/042');
    comp.onPartyLedgerSelected({ id: 'led-debtor-1', label: 'Apex Diagnostics Pvt Ltd' });
    comp.onLineLedgerSelected(0, { id: 'led-sales-gst', label: 'Domestic GST Sales' });
    comp.onInvoiceLineAmountManualChange(0, '100000.00');

    comp.appendBlankLine();
    comp.onLineLedgerSelected(1, { id: 'led-cgst', label: 'Output CGST 9%' });
    comp.appendBlankLine();
    comp.onLineLedgerSelected(2, { id: 'led-sgst', label: 'Output SGST 9%' });
    comp.narration.set('Diagnostic analyser supply under Tamil Nadu intra-state GST');
    fixture.detectChanges();

    expect(comp.gridLines()[1].amount).toBe('9000.00');
    expect(comp.gridLines()[2].amount).toBe('9000.00');
    expect(comp.totals().totalDebitWire).toBe('118000.00');
    expect(comp.totals().totalCreditWire).toBe('118000.00');
    expect(comp.totals().isBalancedAndPositive).toBe(true);

    // Toggle Optional (Ctrl+L) -> verify badge appears in DOM -> toggle back to NORMAL
    shortcutRegistry.triggerCommand('voucher.toggleOptional');
    fixture.detectChanges();
    expect(comp.postingNature()).toBe('OPTIONAL');
    const hostEl = fixture.nativeElement as HTMLElement;
    expect(hostEl.textContent).toContain('Optional');

    shortcutRegistry.triggerCommand('voucher.toggleOptional');
    fixture.detectChanges();
    expect(comp.postingNature()).toBe('NORMAL');

    // 4. Press Ctrl+A (`screen.accept`) -> POST /vouchers with UUID Idempotency-Key
    shortcutRegistry.triggerCommand('screen.accept');

    const postReq = httpMock.expectOne(
      (r) => r.method === 'POST' && r.url.endsWith(`/companies/${company.id}/vouchers`),
    );
    const idemHeader = postReq.request.headers.get('Idempotency-Key');
    expect(idemHeader).toBeTruthy();
    expect(idemHeader!.length).toBeGreaterThanOrEqual(20);

    expect(postReq.request.body.persistedView).toBe('ACCOUNTING_INVOICE');
    expect(postReq.request.body.postingNature).toBe('NORMAL');
    expect(postReq.request.body.action).toBe('POST');
    expect(postReq.request.body.lines).toHaveLength(4);
    expect(postReq.request.body.lines[0]).toMatchObject({
      lineRole: 'PARTY',
      ledgerId: 'led-debtor-1',
      side: 'DR',
      amount: '118000.00',
    });
    expect(postReq.request.body.lines[0].billAllocations[0]).toMatchObject({
      allocationType: 'NEW_REF',
      billName: 'GST/25-26/042',
      side: 'DR',
      amount: '118000.00',
    });

    postReq.flush({
      id: 'vch-sales-42',
      companyId: company.id,
      voucherTypeId: 'vt-sales',
      voucherTypeName: 'Sales',
      voucherNumber: 'GST/25-26/042',
      voucherDate: '2025-05-15',
      persistedView: 'ACCOUNTING_INVOICE',
      postingNature: 'NORMAL',
      status: 'POSTED',
      currencyCode: 'INR',
      narration: 'Diagnostic analyser supply under Tamil Nadu intra-state GST',
      totalDebit: '118000.00',
      totalCredit: '118000.00',
      lines: postReq.request.body.lines,
      versionNo: 1,
    });
    flushNextNumberRequests(httpMock);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp.savedBanner()).toContain('GST/25-26/042');

    // 5. Verify F5 Payment SINGLE_ENTRY -> Ctrl+H conversion to AS_VOUCHER
    comp.initializeBlankVoucher('PAYMENT');
    flushNextNumberRequests(httpMock);
    comp.onSingleEntryAccountSelected({ id: 'led-hdfc', label: 'HDFC Bank Current A/c' });
    comp.onLineLedgerSelected(0, { id: 'led-rent', label: 'Office Rent Expense' });
    comp.onLineAmountChange(0, '25000.00');
    comp.applyPersistedView('AS_VOUCHER');
    fixture.detectChanges();

    expect(comp.persistedView()).toBe('AS_VOUCHER');
    expect(comp.totals().totalDebitWire).toBe('25000.00');
    expect(comp.totals().totalCreditWire).toBe('25000.00');

    httpMock.verify();
  });

  it('enforces ExactDecimal sub-ledger sums and over-settlement guard across Bill-Wise, Cost Centre, and Bank Popovers', async () => {
    await TestBed.configureTestingModule({
      imports: [
        BillWisePopoverComponent,
        CostAllocationPopoverComponent,
        BankAllocationPopoverComponent,
      ],
      providers: [
        provideRouter(APP_ROUTES),
        provideHttpClient(withInterceptors([idempotencyInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyStore = TestBed.inject(CompanyContextStore);
    const coaStore = TestBed.inject(ChartOfAccountsStore);

    const company = seedPhase3Company(companyStore);
    const { costCentres } = buildGroupsAndLedgers(company.id);
    vi.spyOn(coaStore, 'costCentres').mockReturnValue(costCentres);

    // --- A. Bill-Wise Allocation Popover (Agst Ref + Open Bills + Over-Settlement Guard) ---
    const billFixture = TestBed.createComponent(BillWisePopoverComponent);
    billFixture.componentRef.setInput('isOpen', true);
    billFixture.componentRef.setInput('companyId', company.id);
    billFixture.componentRef.setInput('ledgerId', 'led-debtor-1');
    billFixture.componentRef.setInput('ledgerName', 'Apex Diagnostics Pvt Ltd');
    billFixture.componentRef.setInput('targetAmount', '50000.00');
    billFixture.componentRef.setInput('targetSide', 'CR');
    billFixture.componentRef.setInput('defaultBillRef', 'RCPT-101');
    billFixture.componentRef.setInput('defaultMethod', 'AGST_REF');
    billFixture.componentRef.setInput('voucherDate', '2025-05-20');
    billFixture.detectChanges();

    const openBillsReq = httpMock.expectOne((r) =>
      r.url.endsWith(`/companies/${company.id}/ledgers/led-debtor-1/open-bills`),
    );
    openBillsReq.flush([
      {
        billName: 'GST/25-26/009',
        billDate: '2025-05-01',
        dueDate: '2025-05-31',
        totalDr: '35000.00',
        totalCr: '0.00',
        pendingAmount: '35000.00',
        pendingSide: 'DR',
      },
    ]);
    await flushAsyncTicks();
    billFixture.detectChanges();

    const billComp = billFixture.componentInstance;
    expect(billComp.openBills().length).toBe(1);
    let committedBills: readonly BillAllocationDto[] | null = null;
    billComp.allocationsCommitted.subscribe((out) => {
      committedBills = out;
    });

    // Attempt over-settlement of ₹50,000 against ₹35,000 open bill -> must reject!
    if (billComp.rows().length > 1) {
      billComp.removeRow(1);
    }
    billComp.onMethodChange(0, 'AGST_REF');
    billComp.onOpenBillSelected(0, billComp.openBills()[0].billName);
    billComp.onRowAmountChange(0, '50000.00');
    billComp.commitAllocations();
    expect(committedBills).toBeNull();
    expect(billComp.validationError()).toContain('exceeds its pending balance');

    // Settle ₹35,000 against GST/25-26/009 + ₹15,000 On Account == exact ₹50,000.00
    billComp.onRowAmountEnter(0, '35000.00');
    expect(billComp.rows().length).toBe(2);
    billComp.onMethodChange(1, 'ON_ACCOUNT');
    billComp.onRowAmountEnter(1, '15000.00');

    expect(committedBills).not.toBeNull();
    expect(committedBills!).toHaveLength(2);
    expect(committedBills![0]).toMatchObject({
      allocationType: 'AGST_REF',
      billName: 'GST/25-26/009',
      amount: '35000.00',
    });
    expect(committedBills![1]).toMatchObject({
      allocationType: 'ON_ACCOUNT',
      amount: '15000.00',
    });

    // --- B. Cost Allocation Popover (100% ExactDecimal Split) ---
    const costFixture = TestBed.createComponent(CostAllocationPopoverComponent);
    costFixture.componentRef.setInput('isOpen', true);
    costFixture.componentRef.setInput('ledgerName', 'Office Rent Expense');
    costFixture.componentRef.setInput('targetAmount', '25000.00');
    costFixture.componentRef.setInput('targetSide', 'DR');
    costFixture.detectChanges();

    const costComp = costFixture.componentInstance;
    let committedCosts: readonly CostAllocationDto[] | null = null;
    costComp.allocationsCommitted.subscribe((out) => {
      committedCosts = out;
    });

    costComp.onCentreSelected(0, { id: 'cc-chennai', label: 'Chennai HQ' });
    costComp.onRowAmountEnter(0, '15000.00');
    expect(costComp.rows().length).toBe(2);
    expect(costComp.rows()[1].amount).toBe('10000.00');

    costComp.onCentreSelected(1, { id: 'cc-madurai', label: 'Madurai Lab' });
    costComp.onRowAmountEnter(1, '10000.00');
    expect(committedCosts).toHaveLength(2);

    // Duplicate Cost Centre guard (`0x1410908d0`: 'Name of Cost Centre is duplicate in Cost Allocation.')
    committedCosts = null;
    costComp.onCentreSelected(1, { id: 'cc-chennai', label: 'Chennai HQ' });
    costComp.commitAllocations();
    expect(committedCosts).toBeNull();
    expect(costComp.validationError()).toBe('Name of Cost Centre is duplicate in Cost Allocation.');

    // --- C. Bank Allocation Popover ---
    const bankFixture = TestBed.createComponent(BankAllocationPopoverComponent);
    bankFixture.componentRef.setInput('isOpen', true);
    bankFixture.componentRef.setInput('bankLedgerId', 'led-hdfc');
    bankFixture.componentRef.setInput('bankLedgerName', 'HDFC Bank Current A/c');
    bankFixture.componentRef.setInput('targetAmount', '25000.00');
    bankFixture.componentRef.setInput('targetSide', 'CR');
    bankFixture.componentRef.setInput('voucherDate', '2025-05-20');
    bankFixture.detectChanges();

    let bankCommittedCount = 0;
    bankFixture.componentInstance.allocationsCommitted.subscribe((rows) => {
      bankCommittedCount = rows.length;
    });
    bankFixture.componentInstance.onRowAmountEnter(0, '25000.00');
    expect(bankCommittedCount).toBe(1);

    httpMock.verify();
  });

  it('renders DayBookComponent in canonical TallyPrime 7.2 order, supports Alt+2 Duplicate, Alt+X Cancel, Voucher Alteration with If-Match, and Four-Eyes Maker-Checker guard', async () => {
    await TestBed.configureTestingModule({
      imports: [DayBookComponent, ApprovalQueueComponent, VoucherGridComponent],
      providers: [
        provideRouter(APP_ROUTES),
        provideHttpClient(
          withInterceptors([idempotencyInterceptor, etagInterceptor, problemDetailsInterceptor]),
        ),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyStore = TestBed.inject(CompanyContextStore);
    const authStore = TestBed.inject(AuthStore);
    const voucherService = TestBed.inject(VoucherService);
    const router = TestBed.inject(Router);
    const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    const company = seedPhase3Company(companyStore);

    const dayBookFixture = TestBed.createComponent(DayBookComponent);
    dayBookFixture.detectChanges();

    const vtReqs = httpMock.match((r) => r.url.endsWith('/voucher-types'));
    for (const r of vtReqs) r.flush([]);

    const listReq = httpMock.expectOne(
      (r) => r.method === 'GET' && r.url.endsWith(`/companies/${company.id}/vouchers`),
    );
    // Return intentionally unsorted rows + a CANCELLED row to verify canonical sort & total exclusion
    listReq.flush({
      items: [
        {
          id: 'vch-3',
          voucherDate: '2025-05-10',
          voucherTypeId: 'vt-sales',
          voucherTypeName: 'Sales',
          voucherCategory: 'SALES',
          voucherNumber: '12',
          particulars: 'Beta Hospitals',
          status: 'POSTED',
          postingNature: 'NORMAL',
          debitAmount: '40000.00',
          creditAmount: '0.00',
          versionNo: 1,
        },
        {
          id: 'vch-1',
          voucherDate: '2025-05-10',
          voucherTypeId: 'vt-payment',
          voucherTypeName: 'Payment',
          voucherCategory: 'PAYMENT',
          voucherNumber: '2',
          particulars: 'Office Rent Expense',
          status: 'POSTED',
          postingNature: 'NORMAL',
          debitAmount: '25000.00',
          creditAmount: '0.00',
          versionNo: 1,
        },
        {
          id: 'vch-2',
          voucherDate: '2025-05-10',
          voucherTypeId: 'vt-sales',
          voucherTypeName: 'Sales',
          voucherCategory: 'SALES',
          voucherNumber: '2',
          particulars: 'Apex Diagnostics Pvt Ltd',
          status: 'POSTED',
          postingNature: 'OPTIONAL',
          debitAmount: '15000.00',
          creditAmount: '0.00',
          versionNo: 1,
        },
        {
          id: 'vch-cancelled',
          voucherDate: '2025-05-09',
          voucherTypeId: 'vt-sales',
          voucherTypeName: 'Sales',
          voucherCategory: 'SALES',
          voucherNumber: '1',
          particulars: 'Voided Customer',
          status: 'CANCELLED',
          postingNature: 'NORMAL',
          debitAmount: '99999.00',
          creditAmount: '0.00',
          versionNo: 2,
        },
      ],
      nextCursor: null,
      hasMore: false,
    });
    await flushAsyncTicks();
    dayBookFixture.detectChanges();

    const dayBook = dayBookFixture.componentInstance;

    // 1. Verify canonical sort: 2025-05-09 Sales #1, 2025-05-10 Payment #2, Sales #2, Sales #12
    expect(dayBook.visibleVouchers().map((v) => v.id)).toEqual([
      'vch-cancelled',
      'vch-1',
      'vch-2',
      'vch-3',
    ]);
    // CANCELLED row (99,999.00) must be excluded from ExactDecimal totals (25,000 + 15,000 + 40,000 = 80,000.00)
    expect(dayBook.footerTotals().totalDebitWire).toBe('80000.00');

    // 2. Select row 1 ('vch-1') and trigger Alt+2 Duplicate -> stages draft and navigates to /app/vouchers/new
    dayBook.selectRow(1);
    const dupPromise = dayBook.duplicateSelectedVoucher();
    const getDupReq = httpMock.expectOne(
      (r) => r.method === 'GET' && r.url.endsWith(`/companies/${company.id}/vouchers/vch-1`),
    );
    const sampleDetail: VoucherDetailDto = {
      id: 'vch-1',
      companyId: company.id,
      voucherTypeId: 'vt-payment',
      voucherTypeName: 'Payment',
      voucherCategory: 'PAYMENT',
      voucherNumber: '2',
      voucherDate: '2025-05-10',
      persistedView: 'AS_VOUCHER',
      postingNature: 'NORMAL',
      status: 'POSTED',
      currencyCode: 'INR',
      narration: 'May rent',
      totalDebit: '25000.00',
      totalCredit: '25000.00',
      lines: [
        { lineNo: 1, lineRole: 'LEDGER', ledgerId: 'led-rent', side: 'DR', amount: '25000.00' },
        { lineNo: 2, lineRole: 'LEDGER', ledgerId: 'led-hdfc', side: 'CR', amount: '25000.00' },
      ],
      versionNo: 1,
    };
    getDupReq.flush(sampleDetail);
    await dupPromise;

    expect(voucherService.pendingDuplicateDraft()?.id).toBe('vch-1');
    expect(navigateSpy).toHaveBeenCalledWith(['/app/vouchers/new'], {
      queryParams: { duplicateFrom: 'vch-1' },
    });
    voucherService.consumeDuplicateDraft();

    // 3. Trigger Alt+X Cancel on 'vch-1' -> opens confirmation dialog -> POST /vouchers/vch-1/cancel with If-Match: "v1"
    dayBook.openCancelConfirmation();
    dayBookFixture.detectChanges();
    expect(dayBook.cancelModalOpen()).toBe(true);

    const cancelPromise = dayBook.confirmCancelSelectedVoucher();
    const cancelReq = httpMock.expectOne(
      (r) =>
        r.method === 'POST' && r.url.endsWith(`/companies/${company.id}/vouchers/vch-1/cancel`),
    );
    expect(cancelReq.request.headers.get('If-Match')).toBe('"v1"');
    cancelReq.flush({ ...sampleDetail, status: 'CANCELLED', versionNo: 2 });
    await cancelPromise;
    dayBookFixture.detectChanges();

    expect(dayBook.visibleVouchers().find((v) => v.id === 'vch-1')?.status).toBe('CANCELLED');
    // Total Debit drops by 25,000.00 -> 55,000.00!
    expect(dayBook.footerTotals().totalDebitWire).toBe('55000.00');

    // 4. Verify PUT /vouchers/{id} sends If-Match: "v1" on Voucher Alteration
    const updatePromise = voucherService.updateVoucher(
      company.id,
      'vch-3',
      {
        voucherTypeName: 'Sales',
        voucherDate: '2025-05-10',
        persistedView: 'AS_VOUCHER',
        postingNature: 'NORMAL',
        action: 'POST',
        narration: 'Altered narration',
        lines: sampleDetail.lines,
      },
      1,
    );
    const putReq = httpMock.expectOne(
      (r) => r.method === 'PUT' && r.url.endsWith(`/companies/${company.id}/vouchers/vch-3`),
    );
    expect(putReq.request.headers.get('If-Match')).toBe('"v1"');
    expect(putReq.request.headers.get('Idempotency-Key')).toBeTruthy();
    putReq.flush({ ...sampleDetail, id: 'vch-3', versionNo: 2 });
    await updatePromise;

    // 5. Verify Four-Eyes Maker-Checker Guard in ApprovalQueueComponent
    vi.spyOn(authStore, 'principal').mockReturnValue({
      principalId: 'user-maker-1',
      username: 'maker_anita',
      displayName: 'Anita Maker',
      systemRoles: [],
      companyGrants: [],
      passkeyEnrolled: false,
    });

    const queueFixture = TestBed.createComponent(ApprovalQueueComponent);
    queueFixture.detectChanges();

    const queueReqs = httpMock.match(
      (r) => r.method === 'GET' && r.url.endsWith(`/companies/${company.id}/vouchers`),
    );
    expect(queueReqs.length).toBe(2);
    queueReqs[0].flush({
      items: [
        {
          id: 'vch-pending-self',
          voucherDate: '2025-05-18',
          voucherTypeId: 'vt-payment',
          voucherTypeName: 'Payment',
          voucherNumber: '99',
          particulars: 'Vendor Settlement',
          status: 'PENDING_APPROVAL',
          postingNature: 'NORMAL',
          debitAmount: '75000.00',
          creditAmount: '0.00',
          createdBy: 'maker_anita',
          versionNo: 1,
        },
        {
          id: 'vch-pending-other',
          voucherDate: '2025-05-19',
          voucherTypeId: 'vt-payment',
          voucherTypeName: 'Payment',
          voucherNumber: '100',
          particulars: 'Audit Fee Payment',
          status: 'PENDING_APPROVAL',
          postingNature: 'NORMAL',
          debitAmount: '30000.00',
          creditAmount: '0.00',
          createdBy: 'maker_ravi',
          versionNo: 1,
        },
      ],
      nextCursor: null,
      hasMore: false,
    });
    queueReqs[1].flush({ items: [], nextCursor: null, hasMore: false });
    await flushAsyncTicks();
    queueFixture.detectChanges();

    const queueComp = queueFixture.componentInstance;
    expect(queueComp.isSelfMaker(queueComp.filteredItems()[0])).toBe(true);
    expect(queueComp.isSelfMaker(queueComp.filteredItems()[1])).toBe(false);

    queueComp.openApproveModal(queueComp.filteredItems()[1]);
    const approvePromise = queueComp.confirmApprove();
    const approveReq = httpMock.expectOne(
      (r) =>
        r.method === 'POST' &&
        r.url.endsWith(`/companies/${company.id}/vouchers/vch-pending-other/approve`),
    );
    expect(approveReq.request.headers.get('If-Match')).toBe('"v1"');
    approveReq.flush({ ...sampleDetail, id: 'vch-pending-other', status: 'POSTED', versionNo: 2 });
    await approvePromise;

    expect(queueComp.filteredItems().map((i) => i.id)).toEqual(['vch-pending-self']);

    expect(convertToParamMap({ voucherId: 'vch-3' }).get('voucherId')).toBe('vch-3');

    httpMock.verify();
  });

  it('supports Alt+C inline Secondary Ledger creation, ITEM_INVOICE read-only UOM skip, and Day Book Alt+R / Alt+U session subtotal inspection', async () => {
    await TestBed.configureTestingModule({
      imports: [VoucherGridComponent, DayBookComponent],
      providers: [
        provideRouter(APP_ROUTES),
        provideHttpClient(withInterceptors([idempotencyInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyStore = TestBed.inject(CompanyContextStore);
    const coaStore = TestBed.inject(ChartOfAccountsStore);
    const secondaryModal = TestBed.inject(SecondaryMasterModalService);

    const company = seedPhase3Company(companyStore);
    const { groups, ledgers } = buildGroupsAndLedgers(company.id);
    coaStore.setGroupsAndLedgers(groups, ledgers);

    const gridFixture = TestBed.createComponent(VoucherGridComponent);
    gridFixture.detectChanges();
    for (const r of httpMock.match((req) => req.url.endsWith('/voucher-types'))) {
      r.flush([]);
    }
    await flushAsyncTicks();
    gridFixture.detectChanges();

    const grid = gridFixture.componentInstance;
    grid.initializeBlankVoucher('JOURNAL');
    flushNextNumberRequests(httpMock);

    // 1. Trigger Alt+C inline Ledger creation on line 0 while preserving voucher state
    grid.narration.set('Draft preserved across Alt+C');
    grid.openInlineLedgerCreate('LINE', 0, 'Courier Charges');
    expect(secondaryModal.isOpen()).toBe(true);

    secondaryModal.completeActiveSession({
      kind: 'LEDGER',
      id: 'led-courier-new',
      name: 'Courier Charges',
    });
    expect(secondaryModal.isOpen()).toBe(false);
    expect(grid.narration()).toBe('Draft preserved across Alt+C');
    expect(grid.gridLines()[0].ledgerId).toBe('led-courier-new');

    // 2. Switch to SALES ITEM_INVOICE -> verify read-only UOM cell (tabindex -1) when alternateUomId is null
    grid.initializeBlankVoucher('SALES');
    grid.applyPersistedView('ITEM_INVOICE');
    grid.onPartyLedgerSelected({ id: 'led-debtor-1', label: 'Apex Diagnostics Pvt Ltd' });
    grid.onAccountingLedgerSelected({ id: 'led-sales-gst', label: 'Domestic GST Sales' });
    grid.onItemHookNameChange(0, 'Hematology Reagent Pack');
    grid.onItemHookQtyOrRateChange(0, '4', '2500.00');
    grid.onLineLedgerSelected(0, { id: 'led-cgst', label: 'Output CGST 9%' });
    gridFixture.detectChanges();

    const uomCell = (gridFixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="readonly-uom-cell"]',
    ) as HTMLElement | null;
    expect(uomCell).not.toBeNull();
    expect(uomCell!.tagName).toBe('SPAN');
    expect(uomCell!.textContent?.trim()).toBe('NOS');
    expect(grid.itemHookRows()[0].amount).toBe('10000.00');
    expect(grid.gridLines()[0].amount).toBe('900.00');
    expect(grid.totals().totalDebitWire).toBe('10900.00');

    // 3. Verify Day Book Alt+R Remove Line & Alt+U LIFO Restore Line
    const dbFixture = TestBed.createComponent(DayBookComponent);
    dbFixture.detectChanges();
    for (const r of httpMock.match((req) => req.url.endsWith('/voucher-types'))) {
      r.flush([]);
    }
    const listReq = httpMock.expectOne((r) => r.url.endsWith(`/companies/${company.id}/vouchers`));
    listReq.flush({
      items: [
        {
          id: 'v-a',
          voucherDate: '2025-05-01',
          voucherTypeId: 'vt-sales',
          voucherTypeName: 'Sales',
          voucherNumber: '1',
          particulars: 'Customer A',
          status: 'POSTED',
          postingNature: 'NORMAL',
          debitAmount: '10000.00',
          creditAmount: '0.00',
          versionNo: 1,
        },
        {
          id: 'v-b',
          voucherDate: '2025-05-02',
          voucherTypeId: 'vt-sales',
          voucherTypeName: 'Sales',
          voucherNumber: '2',
          particulars: 'Customer B',
          status: 'POSTED',
          postingNature: 'NORMAL',
          debitAmount: '15000.00',
          creditAmount: '0.00',
          versionNo: 1,
        },
      ],
      nextCursor: null,
      hasMore: false,
    });
    await flushAsyncTicks();
    dbFixture.detectChanges();

    const db = dbFixture.componentInstance;
    expect(db.footerTotals().totalDebitWire).toBe('25000.00');
    db.selectRow(0);
    db.removeSelectedLine();
    expect(db.visibleVouchers()).toHaveLength(1);
    expect(db.footerTotals().totalDebitWire).toBe('15000.00');
    db.restoreLastRemovedLine();
    expect(db.visibleVouchers()).toHaveLength(2);
    expect(db.footerTotals().totalDebitWire).toBe('25000.00');

    httpMock.verify();
  });

  it('enforces Loop 2 Contra Cash/Bank guard, Party Credit Limit with Override toggle, Ctrl+Alt+F Autofill, Alt+D Delete Draft, Bill-Wise Due Date >= Bill Date, and Godown/Batch duplicate & expiry checks', async () => {
    await TestBed.configureTestingModule({
      imports: [
        VoucherGridComponent,
        BillWisePopoverComponent,
        GodownBatchPopoverComponent,
      ],
      providers: [
        provideRouter(APP_ROUTES),
        provideHttpClient(withInterceptors([idempotencyInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyStore = TestBed.inject(CompanyContextStore);
    const coaStore = TestBed.inject(ChartOfAccountsStore);
    const shortcutRegistry = TestBed.inject(ShortcutCommandRegistry);

    const company = seedPhase3Company(companyStore);
    const { groups, ledgers } = buildGroupsAndLedgers(company.id);
    const ledgersWithCreditCap: Ledger[] = ledgers.map((l) =>
      l.id === 'led-debtor-1'
        ? { ...l, checkCreditDaysDuringVoucher: true, creditLimit: '50000.00' }
        : l,
    );
    coaStore.setGroupsAndLedgers(groups, ledgersWithCreditCap);

    const gridFixture = TestBed.createComponent(VoucherGridComponent);
    gridFixture.detectChanges();
    for (const r of httpMock.match((req) => req.url.endsWith('/voucher-types'))) {
      r.flush([]);
    }
    await flushAsyncTicks();
    gridFixture.detectChanges();

    const grid = gridFixture.componentInstance;

    // 1. Contra (F4) in Double-Entry (AS_VOUCHER) mode restricts options to Cash/Bank and rejects non-Cash/Bank ledgers
    shortcutRegistry.triggerCommand('voucher.contra');
    flushNextNumberRequests(httpMock, 'CNTR-001');
    grid.applyPersistedView('AS_VOUCHER');
    gridFixture.detectChanges();

    expect(grid.activeCategory()).toBe('CONTRA');
    expect(grid.asVoucherLedgerOptions().map((o) => o.id)).toEqual(['led-hdfc', 'led-cash']);

    grid.onLineLedgerSelected(0, { id: 'led-rent', label: 'Office Rent Expense' });
    grid.onLineAmountEnter(0, '5000.00');
    grid.onLineLedgerSelected(1, { id: 'led-cash', label: 'Cash' });
    expect(() => grid.buildUpsertRequest('POST')).toThrow(/Contra voucher allows only Cash-in-Hand or Bank Account ledgers/);

    // 2. Sales (F8) with Ctrl+Alt+F Autofill, Party Credit Limit guard (50,000.00), and Override Credit Limit toggle
    grid.initializeBlankVoucher('SALES');
    flushNextNumberRequests(httpMock, 'GST/25-26/088');
    grid.onPartyLedgerSelected({ id: 'led-debtor-1', label: 'Apex Diagnostics Pvt Ltd' });
    grid.onLineLedgerSelected(0, { id: 'led-sales-gst', label: 'Domestic GST Sales' });
    grid.onInvoiceLineAmountManualChange(0, '100000.00');
    gridFixture.detectChanges();

    // Trigger Ctrl+Alt+F (`voucher.autofill`) -> auto-populates Output CGST 9% (9000.00) and Output SGST 9% (9000.00)
    shortcutRegistry.triggerCommand('voucher.autofill');
    gridFixture.detectChanges();
    expect(grid.gridLines().length).toBe(3);
    expect(grid.gridLines()[1].ledgerId).toBe('led-cgst');
    expect(grid.gridLines()[1].amount).toBe('9000.00');
    expect(grid.gridLines()[2].ledgerId).toBe('led-sgst');
    expect(grid.gridLines()[2].amount).toBe('9000.00');
    expect(grid.totals().totalDebitWire).toBe('118000.00');

    // Attempt buildUpsertRequest('POST') when total Dr 118000.00 > creditLimit 50000.00 -> throws credit limit error
    expect(() => grid.buildUpsertRequest('POST')).toThrow(
      /Credit Limit of 50000.00 for 'Apex Diagnostics Pvt Ltd' exceeded/,
    );

    // Enable Override Credit Limit -> buildUpsertRequest('POST') succeeds!
    grid.overrideCreditLimit.set(true);
    const validSalesReq = grid.buildUpsertRequest('POST');
    expect(validSalesReq.lines[0].amount).toBe('118000.00');

    // 3. Alt+D (`register.deleteVoucher`) clears unsaved draft, and blocks deletion when editing a POSTED voucher under MCA Edit Log
    shortcutRegistry.triggerCommand('register.deleteVoucher');
    flushNextNumberRequests(httpMock, 'GST/25-26/089');
    expect(grid.savedBanner()).toContain('Cleared current voucher draft (Alt+D)');
    expect(grid.partyLedgerId()).toBeNull();

    grid.editingVoucherId.set('vch-posted-1');
    grid.editingStatus.set('POSTED');
    shortcutRegistry.triggerCommand('register.deleteVoucher');
    expect(grid.errorBanner()).toContain(
      'Posted vouchers cannot be deleted when MCA Edit Log is enforced; use Alt+X to Cancel.',
    );

    // 3b. Alt+2 (`register.duplicateVoucher`, `0x14124adf0`) switches an open voucher into Duplication Mode
    shortcutRegistry.triggerCommand('register.duplicateVoucher');
    flushNextNumberRequests(httpMock, 'GST/25-26/090');
    expect(grid.editingVoucherId()).toBeNull();
    expect(grid.editingStatus()).toBe('DRAFT');
    expect(grid.savedBanner()).toContain('Switched to Voucher Duplication Mode (Alt+2).');

    // 3c. Bidirectional Ctrl+H conversion: AS_VOUCHER -> SINGLE_ENTRY on Payment promotes Cash/Bank line into headerAccountLedgerId
    grid.initializeBlankVoucher('PAYMENT');
    flushNextNumberRequests(httpMock, 'PYMT-010');
    grid.applyPersistedView('AS_VOUCHER');
    grid.onLineLedgerSelected(0, { id: 'led-rent', label: 'Office Rent Expense' });
    grid.onLineAmountEnter(0, '8500.00');
    grid.onLineLedgerSelected(1, { id: 'led-hdfc', label: 'HDFC Bank Current A/c' });
    grid.applyPersistedView('SINGLE_ENTRY');
    expect(grid.persistedView()).toBe('SINGLE_ENTRY');
    expect(grid.headerAccountLedgerId()).toBe('led-hdfc');
    expect(grid.gridLines()[0].ledgerId).toBe('led-rent');
    expect(grid.totals().totalDebitWire).toBe('8500.00');

    // 4. BillWisePopoverComponent rejects Due Date < Bill Date (`0x14108de40`) and duplicate Bill References
    const bwFixture = TestBed.createComponent(BillWisePopoverComponent);
    bwFixture.componentRef.setInput('isOpen', true);
    bwFixture.componentRef.setInput('companyId', company.id);
    bwFixture.componentRef.setInput('ledgerId', 'led-debtor-1');
    bwFixture.componentRef.setInput('ledgerName', 'Apex Diagnostics Pvt Ltd');
    bwFixture.componentRef.setInput('targetAmount', '10000.00');
    bwFixture.componentRef.setInput('targetSide', 'DR');
    bwFixture.componentRef.setInput('defaultBillRef', 'INV-10');
    bwFixture.componentRef.setInput('defaultMethod', 'NEW_REF');
    bwFixture.componentRef.setInput('voucherDate', '2025-05-20');
    bwFixture.detectChanges();
    for (const r of httpMock.match((req) => req.url.includes('/open-bills'))) {
      r.flush([]);
    }
    await flushAsyncTicks();
    bwFixture.detectChanges();

    const bwComp = bwFixture.componentInstance;
    bwComp.rows.set([
      {
        ...bwComp.rows()[0],
        dueDate: '2025-05-10', // Earlier than voucherDate 2025-05-20!
      },
    ]);
    bwComp.commitAllocations();
    expect(bwComp.validationError()).toBe('Due Date is earlier than Bill Date in Bill-wise details.');

    // Duplicate Bill Reference rows on the same line must also be rejected
    bwComp.rows.set([
      {
        rowId: 'bw-dup-1',
        allocationType: 'NEW_REF',
        billName: 'INV-DUP-1',
        creditPeriodDays: 30,
        dueDate: '2025-06-19',
        side: 'DR',
        amount: '5000.00',
        allowOverSettlement: false,
      },
      {
        rowId: 'bw-dup-2',
        allocationType: 'NEW_REF',
        billName: 'INV-DUP-1',
        creditPeriodDays: 30,
        dueDate: '2025-06-19',
        side: 'DR',
        amount: '5000.00',
        allowOverSettlement: false,
      },
    ]);
    bwComp.commitAllocations();
    expect(bwComp.validationError()).toBe('Duplicate Bill Reference in Bill-wise details.');

    // 5. GodownBatchPopoverComponent rejects duplicate (Godown, Batch) rows (`0x14108f720`) and Expiry Date < Mfg Date (`0x14108f600`)
    const gbFixture = TestBed.createComponent(GodownBatchPopoverComponent);
    gbFixture.componentRef.setInput('isOpen', true);
    gbFixture.componentRef.setInput('stockItemId', 'item-1');
    gbFixture.componentRef.setInput('stockItemName', 'Reagent Kit');
    gbFixture.componentRef.setInput('movementDirection', 'INWARD');
    gbFixture.componentRef.setInput('maintainInBatches', true);
    gbFixture.componentRef.setInput('trackDateOfMfg', true);
    gbFixture.componentRef.setInput('useExpiryDates', true);
    gbFixture.componentRef.setInput('targetActualQty', '10');
    gbFixture.componentRef.setInput('targetBilledQty', '10');
    gbFixture.componentRef.setInput('defaultRate', '100.00');
    gbFixture.componentRef.setInput('voucherDate', '2025-05-20');
    gbFixture.detectChanges();

    const gbComp = gbFixture.componentInstance;
    // Duplicate Godown + Batch rows
    gbComp.rows.set([
      {
        rowId: 'gb-1',
        godownId: 'gd-main',
        godownName: 'Main Location',
        batchName: 'LOT-A1',
        actualQty: '5',
        billedQty: '5',
        rate: '100.00',
        amount: '500.00',
        mfgDate: '2025-05-01',
        expiryDate: '2026-05-01',
      },
      {
        rowId: 'gb-2',
        godownId: 'gd-main',
        godownName: 'Main Location',
        batchName: 'LOT-A1',
        actualQty: '5',
        billedQty: '5',
        rate: '100.00',
        amount: '500.00',
        mfgDate: '2025-05-01',
        expiryDate: '2026-05-01',
      },
    ]);
    gbComp.commitAllocations();
    expect(gbComp.validationError()).toBe(
      'Duplicate Godown and Batch combination is not allowed in Item Allocations.',
    );

    // Distinct batches, but Expiry Date < Mfg Date
    gbComp.rows.set([
      {
        ...gbComp.rows()[0],
        batchName: 'LOT-A1',
        mfgDate: '2025-06-01',
        expiryDate: '2025-05-01',
      },
      {
        ...gbComp.rows()[1],
        batchName: 'LOT-A2',
      },
    ]);
    gbComp.commitAllocations();
    expect(gbComp.validationError()).toContain('cannot be earlier than Manufacturing Date');

    httpMock.verify();
  });
});
