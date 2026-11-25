import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component } from '@angular/core';
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
import { AccountingMastersService } from './accounting-masters.service';
import { ChartOfAccountsStore } from './chart-of-accounts.store';
import { CoaExplorerComponent } from './coa-explorer.component';
import { GroupWorkbenchComponent } from './group-workbench.component';
import { LedgerWorkbenchComponent } from './ledger-workbench.component';
import { AccountGroup, Ledger, PREDEFINED_28_ACCOUNT_GROUPS } from './masters.models';
import { SecondaryMasterModalComponent } from './secondary-master-modal.component';
import { VoucherTypeWorkbenchComponent } from './voucher-type-workbench.component';

function seedBenchmarkCompany(store: CompanyContextStore): CompanySummary {
  const comp: CompanySummary = {
    id: 'comp-tn-01',
    code: 'MCPB01',
    legalName: 'MoneyCraft Parity Benchmark',
    mailingName: 'MoneyCraft Parity Benchmark',
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

  const secondComp: CompanySummary = {
    ...comp,
    id: 'comp-ka-02',
    code: 'MCPB02',
    legalName: 'Apex Diagnostics Branch',
    stateCode: '29',
  };

  store.setCompanies([comp, secondComp], comp.id);
  return comp;
}

function buildPredefined28Groups(companyId: string): AccountGroup[] {
  const idByName = new Map<string, string>();
  PREDEFINED_28_ACCOUNT_GROUPS.forEach((desc, idx) => {
    idByName.set(desc.name, `grp-${idx + 1}`);
  });

  return PREDEFINED_28_ACCOUNT_GROUPS.map((desc, idx) => ({
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
}

function makeSampleLedger(
  partial: Partial<Ledger> & Pick<Ledger, 'id' | 'name' | 'groupId' | 'openingBalance' | 'openingSide'>,
): Ledger {
  return {
    companyId: 'comp-tn-01',
    aliasCode: null,
    currencyCode: 'INR',
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
    mailingName: partial.name,
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
    ...partial,
  };
}

@Component({
  standalone: true,
  imports: [LedgerWorkbenchComponent, SecondaryMasterModalComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <mc-ledger-workbench />
    <mc-secondary-master-modal />
  `,
})
class LedgerWithSecondaryHostComponent {}

describe('Phase 2 Chart of Accounts Explorer, Accounting Masters & Alt+C Secondary Modal', () => {
  it('renders 28 predefined groups, toggles Alt+F1 Condensed/Detailed, and computes ExactDecimal opening totals', async () => {
    await TestBed.configureTestingModule({
      imports: [CoaExplorerComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const companyStore = TestBed.inject(CompanyContextStore);
    const coaStore = TestBed.inject(ChartOfAccountsStore);
    const shortcutRegistry = TestBed.inject(ShortcutCommandRegistry);
    seedBenchmarkCompany(companyStore);

    const groups = buildPredefined28Groups('comp-tn-01');
    const capitalGrp = groups.find((g) => g.name === 'Capital Account')!;
    const cashGrp = groups.find((g) => g.name === 'Cash-in-Hand')!;
    const bankGrp = groups.find((g) => g.name === 'Bank Accounts')!;
    const debtorsGrp = groups.find((g) => g.name === 'Sundry Debtors')!;

    const ledgers: Ledger[] = [
      makeSampleLedger({
        id: 'led-cap',
        name: 'Promoter Capital A/c',
        groupId: capitalGrp.id,
        openingBalance: '500000.00',
        openingSide: 'CR',
      }),
      makeSampleLedger({
        id: 'led-cash',
        name: 'Cash',
        groupId: cashGrp.id,
        openingBalance: '25000.00',
        openingSide: 'DR',
        isSystemLedger: true,
      }),
      makeSampleLedger({
        id: 'led-hdfc',
        name: 'HDFC Current A/c',
        groupId: bankGrp.id,
        openingBalance: '472400.00',
        openingSide: 'DR',
      }),
      makeSampleLedger({
        id: 'led-salem',
        name: 'Salem Care Hospital',
        groupId: debtorsGrp.id,
        openingBalance: '14624.00',
        openingSide: 'DR',
        gstin: '33AABCS9012H1ZM',
      }),
    ];

    coaStore.setGroupsAndLedgers(groups, ledgers);

    const fixture = TestBed.createComponent(CoaExplorerComponent);
    fixture.detectChanges();

    // 1. Condensed mode (default): shows all 28 groups and 0 ledgers
    expect(fixture.componentInstance.detailedMode()).toBe(false);
    expect(fixture.componentInstance.visibleRows().length).toBe(28);
    expect(fixture.componentInstance.visibleRows().every((r) => r.rowType === 'GROUP')).toBe(true);

    // 2. ExactDecimal opening totals: Dr = 25000 + 472400 + 14624 = 512024.00, Cr = 500000.00, Diff = 12024.00 CR
    expect(coaStore.totalOpeningDebit()).toBe('512024.00');
    expect(coaStore.totalOpeningCredit()).toBe('500000.00');
    expect(coaStore.openingDifference()).toBe('12024.00');
    expect(coaStore.openingDifferenceSide()).toBe('CR');

    const totalDrEl: HTMLElement = fixture.nativeElement.querySelector('[data-testid="coa-total-dr"]');
    const totalCrEl: HTMLElement = fixture.nativeElement.querySelector('[data-testid="coa-total-cr"]');
    const diffEl: HTMLElement = fixture.nativeElement.querySelector('[data-testid="coa-opening-diff"]');
    expect(totalDrEl.textContent).toContain(`${RUPEE_SYMBOL} 5,12,024.00`);
    expect(totalCrEl.textContent).toContain(`${RUPEE_SYMBOL} 5,00,000.00`);
    expect(diffEl.textContent).toContain(`${RUPEE_SYMBOL} 12,024.00`);

    // 3. Dispatch Alt+F1 to switch into Detailed mode (28 Groups + 4 Ledgers = 32 rows)
    shortcutRegistry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'F1', code: 'F1', altKey: true, cancelable: true }),
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.detailedMode()).toBe(true);
    expect(fixture.componentInstance.visibleRows().length).toBe(32);

    // Verify GSTIN renders inside .mc-code without U+20B9 ₹
    const codeBadges = Array.from<HTMLElement>(fixture.nativeElement.querySelectorAll('.mc-code'));
    expect(codeBadges.some((el) => el.textContent?.includes('33AABCS9012H1ZM'))).toBe(true);
    expect(codeBadges.every((el) => !el.textContent?.includes(RUPEE_SYMBOL))).toBe(true);
  });

  it('validates GSTIN Mod-36 live, renders conditional Duties & Taxes Duty Head, guards system ledgers, and posts on Ctrl+Enter', async () => {
    await TestBed.configureTestingModule({
      imports: [LedgerWorkbenchComponent],
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
    seedBenchmarkCompany(companyStore);

    const groups = buildPredefined28Groups('comp-tn-01');
    const creditorsGrp = groups.find((g) => g.name === 'Sundry Creditors')!;
    const dutiesGrp = groups.find((g) => g.name === 'Duties & Taxes')!;
    const cashGrp = groups.find((g) => g.name === 'Cash-in-Hand')!;
    const systemCash = makeSampleLedger({
      id: 'sys-cash',
      name: 'Cash',
      groupId: cashGrp.id,
      openingBalance: '0.00',
      openingSide: 'DR',
      isSystemLedger: true,
    });
    coaStore.setGroupsAndLedgers(groups, [systemCash]);

    const fixture = TestBed.createComponent(LedgerWorkbenchComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    // 1. Party GSTIN Mod-36 validation check
    fixture.componentInstance.onNameInput('Kaveri Meditech Pvt Ltd');
    fixture.componentInstance.onGroupSelected({
      id: creditorsGrp.id,
      label: creditorsGrp.name,
    });
    fixture.componentInstance.onGstinInput('33AAFCK5678G1Z0'); // wrong check char
    fixture.detectChanges();

    expect(fixture.componentInstance.gstinValidation().valid).toBe(false);
    await fixture.componentInstance.saveLedger();
    expect(fixture.componentInstance.formError()).toContain('Mod-36 checksum mismatch');

    // Valid Mod-36 GSTIN passes and extracts PAN
    fixture.componentInstance.onGstinInput('33AAFCK5678G1ZC');
    fixture.detectChanges();
    expect(fixture.componentInstance.gstinValidation().valid).toBe(true);
    expect(fixture.componentInstance.formPan()).toBe('AAFCK5678G');

    // 2. Switch Under -> Duties & Taxes: conditional Duty Head field appears
    fixture.componentInstance.startCreateLedger();
    fixture.componentInstance.onNameInput('SGST');
    fixture.componentInstance.onGroupSelected({
      id: dutiesGrp.id,
      label: dutiesGrp.name,
    });
    fixture.componentInstance.formDutyHead.set('SGST_UTGST');
    fixture.detectChanges();

    const dutyField = fixture.nativeElement.querySelector('[data-testid="gst-duty-head-field"]');
    expect(dutyField).not.toBeNull();

    // Press Ctrl+Enter (screen.accept) -> sends POST /ledgers with Idempotency-Key
    shortcutRegistry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, cancelable: true }),
    );

    const postReq = httpMock.expectOne('/api/v1/companies/comp-tn-01/ledgers');
    expect(postReq.request.method).toBe('POST');
    expect(postReq.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/i);
    expect(postReq.request.body.name).toBe('SGST');
    expect(postReq.request.body.taxType).toBe('GST');
    expect(postReq.request.body.dutyHead).toBe('SGST_UTGST');

    postReq.flush(
      makeSampleLedger({
        id: 'led-sgst',
        name: 'SGST',
        groupId: dutiesGrp.id,
        openingBalance: '0.00',
        openingSide: 'CR',
        taxType: 'GST',
        dutyHead: 'SGST_UTGST',
      }),
    );
    await Promise.resolve();
    fixture.detectChanges();

    // 3. System Ledger (Cash) disables Alt+D Delete button
    fixture.componentInstance.selectLedgerForAlter(systemCash);
    fixture.detectChanges();
    const deleteBtn: HTMLButtonElement = fixture.nativeElement.querySelector(
      '[data-testid="delete-ledger-btn"]',
    );
    expect(deleteBtn.disabled).toBe(true);
    await expect(coaStore.deleteLedger(systemCash.id)).rejects.toThrow(/System ledgers/);

    httpMock.verify();
  });

  it('opens Alt+C SecondaryMasterModal over a dirty parent Ledger form, saves a new Group, auto-selects it, and preserves parent state', async () => {
    await TestBed.configureTestingModule({
      imports: [LedgerWithSecondaryHostComponent],
      providers: [
        provideRouter(APP_ROUTES),
        provideHttpClient(withInterceptors([idempotencyInterceptor, etagInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyStore = TestBed.inject(CompanyContextStore);
    const coaStore = TestBed.inject(ChartOfAccountsStore);
    const navStack = TestBed.inject(NavigationStackStore);
    const shortcutRegistry = TestBed.inject(ShortcutCommandRegistry);
    seedBenchmarkCompany(companyStore);

    const groups = buildPredefined28Groups('comp-tn-01');
    const debtorsGrp = groups.find((g) => g.name === 'Sundry Debtors')!;
    coaStore.setGroupsAndLedgers(groups, []);

    const fixture = TestBed.createComponent(LedgerWithSecondaryHostComponent);
    fixture.detectChanges();
    await Promise.resolve();
    fixture.detectChanges();

    const ledgerWb = fixture.debugElement.children[0].componentInstance as LedgerWorkbenchComponent;

    // Dirty the parent Ledger form
    ledgerWb.onNameInput('Salem Care Hospital');
    ledgerWb.formOpeningBalance.set('114624.00');
    ledgerWb.onGroupSelected({ id: debtorsGrp.id, label: debtorsGrp.name });
    fixture.detectChanges();
    expect(companyStore.hasUnsavedDraft()).toBe(true);

    // Focus the Under combobox input and press Alt+C with seed text
    const underInput: HTMLInputElement = fixture.nativeElement.querySelector('#ledger-under-group');
    underInput.focus();
    underInput.value = 'TN Hospital Debtors';
    underInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    underInput.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'c', code: 'KeyC', altKey: true, cancelable: true }),
    );
    fixture.detectChanges();

    // Verify SecondaryMasterModal pushed onto NavigationStackStore (depth == 1)
    expect(navStack.depth()).toBe(1);
    expect(fixture.nativeElement.textContent).toContain('Group Creation (Secondary)');

    // Dispatch Ctrl+Enter at MODAL scope to save Secondary Group (not parent Ledger!)
    shortcutRegistry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, cancelable: true }),
    );

    const groupPost = httpMock.expectOne('/api/v1/companies/comp-tn-01/account-groups');
    expect(groupPost.request.method).toBe('POST');
    expect(groupPost.request.body.name).toBe('TN Hospital Debtors');
    expect(groupPost.request.body.parentGroupId).toBe(debtorsGrp.id);

    const createdSubGroup: AccountGroup = {
      id: 'grp-custom-tn-hosp',
      companyId: 'comp-tn-01',
      name: 'TN Hospital Debtors',
      aliasCode: null,
      parentGroupId: debtorsGrp.id,
      parentGroupName: debtorsGrp.name,
      nature: 'ASSETS',
      affectsGrossProfit: false,
      behavesLikeSubledger: true,
      netDebitCreditForReporting: false,
      usedForCalculation: false,
      methodToAllocatePurchase: 'NOT_APPLICABLE',
      scheduleIiiLineCode: null,
      defaultGstRatePct: null,
      defaultHsnSacCode: null,
      depthLevel: 2,
      isSystemGroup: false,
      sortOrder: 99,
      versionNo: 1,
    };
    groupPost.flush(createdSubGroup);
    await fixture.whenStable();
    fixture.detectChanges();

    // Modal popped, parent dirty state intact, and new group auto-selected in Under combobox!
    expect(navStack.depth()).toBe(0);
    expect(ledgerWb.formName()).toBe('Salem Care Hospital');
    expect(ledgerWb.formOpeningBalance()).toBe('114624.00');
    expect(ledgerWb.formGroupId()).toBe('grp-custom-tn-hosp');
    expect(underInput.value).toBe('TN Hospital Debtors');

    // Switching company invalidates cached ChartOfAccountsStore cleanly once draft is cleared
    companyStore.setHasUnsavedDraft(false);
    companyStore.selectActiveCompany('comp-ka-02');
    TestBed.flushEffects();
    expect(coaStore.groups().length).toBe(0);

    httpMock.verify();
  });

  it('normalizes OpenAPI v1 mixed CoA tree nodes, nested bankDetails, CostCentre categoryId, and VoucherType category 1:1', async () => {
    await TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([idempotencyInterceptor, etagInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const service = TestBed.inject(AccountingMastersService);

    const treePromise = service.getChartOfAccountsTree('comp-tn-01');
    const treeReq = httpMock.expectOne('/api/v1/companies/comp-tn-01/chart-of-accounts/tree');
    treeReq.flush({
      nodes: [
        {
          id: 'grp-ca',
          nodeType: 'GROUP',
          name: 'Current Assets',
          aliasCode: null,
          parentId: null,
          nature: 'ASSETS',
          affectsGrossProfit: false,
          behavesLikeSubledger: false,
          scheduleIiiLineCode: 'BS_CA',
          isSystem: true,
          openingBalance: '25000.00',
          openingSide: 'DR',
          openingDebit: '25000.00',
          openingCredit: '0.00',
          versionNo: 1,
          children: [
            {
              id: 'led-cash-1',
              nodeType: 'LEDGER',
              name: 'Cash',
              aliasCode: null,
              parentId: 'grp-ca',
              nature: 'ASSETS',
              affectsGrossProfit: false,
              behavesLikeSubledger: false,
              scheduleIiiLineCode: null,
              isSystem: true,
              openingBalance: '25000.00',
              openingSide: 'DR',
              openingDebit: '25000.00',
              openingCredit: '0.00',
              versionNo: 1,
              children: [],
            },
          ],
        },
        {
          id: 'led-pnl-root',
          nodeType: 'LEDGER',
          name: 'Profit & Loss A/c',
          aliasCode: null,
          parentId: null,
          nature: 'LIABILITIES',
          affectsGrossProfit: false,
          behavesLikeSubledger: false,
          scheduleIiiLineCode: 'BS_PNL',
          isSystem: true,
          openingBalance: '0.00',
          openingSide: 'CR',
          openingDebit: '0.00',
          openingCredit: '0.00',
          versionNo: 1,
          children: [],
        },
      ],
      totalGroups: 1,
      totalLedgers: 2,
      totalOpeningDebit: '25000.00',
      totalOpeningCredit: '0.00',
      openingDifference: '25000.00',
    });

    const normalizedTree = await treePromise;
    expect(normalizedTree.groups.length).toBe(1);
    expect(normalizedTree.groups[0].ledgers.length).toBe(1);
    expect(normalizedTree.groups[0].ledgers[0].name).toBe('Cash');
    expect(normalizedTree.systemLedgers?.length).toBe(1);
    expect(normalizedTree.systemLedgers?.[0].name).toBe('Profit & Loss A/c');

    const vtPromise = service.listVoucherTypes('comp-tn-01');
    const vtReq = httpMock.expectOne('/api/v1/companies/comp-tn-01/voucher-types');
    vtReq.flush([
      {
        id: 'vt-sales',
        companyId: 'comp-tn-01',
        name: 'Sales',
        abbreviation: 'Sale',
        parentVoucherTypeId: null,
        parentVoucherType: null,
        category: 'SALES',
        postsAccountingEntries: true,
        movesInventory: true,
        defaultEntryMode: 'ITEM_INVOICE',
        numberingMethod: 'AUTOMATIC_RETAIN_NUMBERS',
        approvalPolicy: 'SINGLE_STEP',
        preventDuplicates: true,
        allowZeroValued: false,
        isOptionalByDefault: false,
        printAfterSave: false,
        shortcutKey: 'F8',
        isSystemType: true,
        active: true,
        versionNo: 1,
      },
    ]);
    const vts = await vtPromise;
    expect(vts[0].baseCategory).toBe('SALES');
    expect(vts[0].isSystemVoucherType).toBe(true);
    expect(vts[0].preventDuplicateNumbers).toBe(true);

    httpMock.verify();
  });

  it('Loop 1 Parity: GroupWorkbenchComponent, VoucherTypeWorkbenchComponent, and LedgerWorkbenchComponent F12 $$IsAllBillWiseOn toggles', async () => {
    await TestBed.configureTestingModule({
      imports: [GroupWorkbenchComponent, VoucherTypeWorkbenchComponent, LedgerWorkbenchComponent],
      providers: [
        provideRouter(APP_ROUTES),
        provideHttpClient(withInterceptors([idempotencyInterceptor, etagInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyStore = TestBed.inject(CompanyContextStore);
    const coaStore = TestBed.inject(ChartOfAccountsStore);
    seedBenchmarkCompany(companyStore);

    const groups = buildPredefined28Groups('comp-tn-01');
    const indirectExpensesGrp = groups.find((g) => g.name === 'Indirect Expenses')!;
    const debtorsGrp = groups.find((g) => g.name === 'Sundry Debtors')!;
    coaStore.setGroupsAndLedgers(groups, []);

    // 1. GroupWorkbenchComponent: inherits nature from parent group & protects predefined 28 system groups
    const grpFixture = TestBed.createComponent(GroupWorkbenchComponent);
    grpFixture.detectChanges();
    await grpFixture.whenStable();
    grpFixture.detectChanges();

    const grpComp = grpFixture.componentInstance;
    grpComp.onFieldEdit('name', 'South Zone Debtors');
    grpComp.onParentSelected({ id: debtorsGrp.id, label: debtorsGrp.name });
    grpComp.formBehavesLikeSubledger.set(true);
    expect(grpComp.formNature()).toBe('ASSETS');

    const saveGrpPromise = grpComp.saveGroup();
    const grpPostReq = httpMock.expectOne('/api/v1/companies/comp-tn-01/account-groups');
    expect(grpPostReq.request.body.name).toBe('South Zone Debtors');
    expect(grpPostReq.request.body.behavesLikeSubledger).toBe(true);
    grpPostReq.flush({
      ...debtorsGrp,
      id: 'grp-south-debtors',
      name: 'South Zone Debtors',
      parentGroupId: debtorsGrp.id,
      isSystemGroup: false,
    });
    await saveGrpPromise;
    expect(grpComp.statusMessage()).toContain('South Zone Debtors');

    // System group protects Delete button
    grpComp.selectGroupForAlter(debtorsGrp);
    grpFixture.detectChanges();
    const delGrpBtn: HTMLButtonElement = grpFixture.nativeElement.querySelector(
      '[data-testid="delete-group-btn"]',
    );
    expect(delGrpBtn.disabled).toBe(true);

    // 2. VoucherTypeWorkbenchComponent: configures Method of Voucher Numbering, Prevent Duplicates & Manufacturing Journal
    const vtFixture = TestBed.createComponent(VoucherTypeWorkbenchComponent);
    vtFixture.detectChanges();
    for (const r of httpMock.match((req) => req.url.includes('/voucher-types'))) {
      r.flush([]);
    }
    await vtFixture.whenStable();

    const vtComp = vtFixture.componentInstance;
    vtComp.startCreateVoucherType();
    vtComp.formName = 'BOM Manufacturing Journal';
    vtComp.formAbbreviation = 'MfgJrn';
    vtComp.onBaseCategorySelected({ id: 'STOCK_JOURNAL', label: 'Stock Journal' });
    vtComp.formNumberingMethod = 'AUTOMATIC_RETAIN_NUMBERS';
    vtComp.formPreventDuplicates = true;
    vtComp.formIsManufacturingJournal = true;

    const saveVtPromise = vtComp.saveVoucherType();
    const vtPostReq = httpMock.expectOne('/api/v1/companies/comp-tn-01/voucher-types');
    expect(vtPostReq.request.body.baseCategory).toBe('STOCK_JOURNAL');
    expect(vtPostReq.request.body.isManufacturingJournal).toBe(true);
    expect(vtPostReq.request.body.preventDuplicateNumbers).toBe(true);
    vtPostReq.flush({
      id: 'vt-mfg-1',
      companyId: 'comp-tn-01',
      name: 'BOM Manufacturing Journal',
      abbreviation: 'MfgJrn',
      baseCategory: 'STOCK_JOURNAL',
      defaultEntryMode: 'ITEM_INVOICE',
      numberingMethod: 'AUTOMATIC_RETAIN_NUMBERS',
      approvalPolicy: 'SINGLE_STEP',
      preventDuplicateNumbers: true,
      useEffectiveDates: false,
      allowZeroValuedTransactions: false,
      makeOptionalByDefault: false,
      allowNarrationPerLedger: false,
      printAfterSave: false,
      isManufacturingJournal: true,
      isSystemVoucherType: false,
      active: true,
      versionNo: 1,
    });
    await saveVtPromise;
    expect(vtComp.statusMessage()).toContain('BOM Manufacturing Journal');

    // 3. LedgerWorkbenchComponent: F12 billWiseForNonTradingAccounts ($$IsAllBillWiseOn) exposes Bill-Wise on non-party ledger
    const ledFixture = TestBed.createComponent(LedgerWorkbenchComponent);
    ledFixture.detectChanges();
    await ledFixture.whenStable();

    const ledComp = ledFixture.componentInstance;
    ledComp.onGroupSelected({ id: indirectExpensesGrp.id, label: indirectExpensesGrp.name });
    ledFixture.detectChanges();
    expect(ledFixture.nativeElement.querySelector('[data-testid="party-billwise-section"]')).toBeNull();

    await companyStore.updateF12Config({ billWiseForNonTradingAccounts: true });
    ledFixture.detectChanges();
    expect(
      ledFixture.nativeElement.querySelector('[data-testid="party-billwise-section"]'),
    ).not.toBeNull();

    httpMock.verify();
  });
});
