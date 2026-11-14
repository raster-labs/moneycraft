import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { APP_ROUTES } from '../../app.routes';
import { etagInterceptor } from '../../core/http/etag.interceptor';
import { idempotencyInterceptor } from '../../core/http/idempotency.interceptor';
import { problemDetailsInterceptor } from '../../core/http/problem-details.interceptor';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { CompanyContextStore, CompanySummary } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { ChartOfAccountsStore } from '../masters/chart-of-accounts.store';
import { AccountGroup, Ledger, VoucherTypeMaster } from '../masters/masters.models';
import { SecondaryMasterModalComponent } from '../masters/secondary-master-modal.component';
import { SecondaryMasterModalService } from '../masters/secondary-master-modal.service';
import { VoucherGridComponent } from '../vouchers/voucher-grid.component';
import { UpsertVoucherRequest, VoucherDetailDto } from '../vouchers/vouchers.models';
import {
  BatchAgeingReportDto,
  BomMaster,
  GodownMaster,
  ManufacturingPreviewResponse,
  MovementAnalysisReportDto,
  StockBatchDto,
  StockGroupMaster,
  StockItemMaster,
  StockSummaryReportDto,
  UomMaster,
} from './inventory.models';
import { InventoryStore } from './inventory.store';
import { StockItemWorkbenchComponent } from './stock-item-workbench.component';
import { StockSummaryComponent } from './stock-summary.component';
import { UomWorkbenchComponent } from './uom-workbench.component';

const TEST_COMPANY_ID = 'comp-inv-01';

const TEST_COMPANY: CompanySummary = {
  id: TEST_COMPANY_ID,
  code: 'MCINV01',
  legalName: 'MoneyCraft MedTech Supply Pvt Ltd',
  mailingName: 'MoneyCraft MedTech',
  stateCode: '33',
  pinCode: '600001',
  countryCode: 'IN',
  financialYearFrom: '2025-04-01',
  booksBeginningFrom: '2025-04-01',
  baseCurrencyCode: 'INR',
  currencySymbol: '₹',
  currencyScale: 2,
  lastEntryDate: '2025-05-20',
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
    tdsEnabled: false,
    mcaEditLogEnforced: true,
  },
};

const SEED_UOMS: readonly UomMaster[] = [
  {
    id: 'uom-nos',
    companyId: TEST_COMPANY_ID,
    uomType: 'SIMPLE',
    symbol: 'NOS',
    formalName: 'Numbers',
    gstUqcCode: 'NOS',
    decimalPlaces: 0,
    firstUomId: null,
    conversionFactor: null,
    secondUomId: null,
    versionNo: 1,
  },
  {
    id: 'uom-box',
    companyId: TEST_COMPANY_ID,
    uomType: 'SIMPLE',
    symbol: 'BOX',
    formalName: 'Boxes',
    gstUqcCode: 'BOX',
    decimalPlaces: 0,
    firstUomId: null,
    conversionFactor: null,
    secondUomId: null,
    versionNo: 1,
  },
];

const SEED_GODOWNS: readonly GodownMaster[] = [
  {
    id: 'gd-main',
    companyId: TEST_COMPANY_ID,
    name: 'Main Location',
    aliasCode: 'MAIN',
    parentGodownId: null,
    address: 'Chennai Central Hub',
    allowStorageOfMaterials: true,
    ourStockWithThirdParty: false,
    thirdPartyStockWithUs: false,
    isSystemGodown: true,
    versionNo: 1,
  },
];

const SEED_STOCK_GROUPS: readonly StockGroupMaster[] = [
  {
    id: 'sg-med',
    companyId: TEST_COMPANY_ID,
    name: 'Diagnostic Devices',
    aliasCode: 'DIAG',
    parentStockGroupId: null,
    shouldQuantitiesOfItemsBeAdded: true,
    hsnSacCode: '90189099',
    gstRatePct: '12',
    cessRatePct: null,
    versionNo: 1,
  },
];

const SEED_STOCK_ITEMS: readonly StockItemMaster[] = [
  {
    id: 'item-oximeter',
    companyId: TEST_COMPANY_ID,
    name: 'Pulse Oximeter Pro',
    aliasCode: 'OXI-PRO',
    partNo: 'MD-OXI-01',
    description: 'Fingertip SpO2 Monitor',
    stockGroupId: 'sg-med',
    stockGroupName: 'Diagnostic Devices',
    stockCategoryId: null,
    baseUomId: 'uom-nos',
    baseUomSymbol: 'NOS',
    baseUomDecimals: 0,
    alternateUomId: null,
    alternateUomSymbol: null,
    conversionNumerator: null,
    conversionDenominator: null,
    maintainInBatches: true,
    trackDateOfMfg: true,
    useExpiryDates: true,
    costingMethod: 'AVG_COST',
    marketValuationMethod: 'AVG_PRICE',
    standardCost: '900.00',
    standardSellingPrice: '1500.00',
    openingQty: '20',
    openingRate: '900.00',
    openingValue: '18000.00',
    openingGodownId: 'gd-main',
    openingBatchName: 'LOT-INIT',
    openingMfgDate: '2025-01-01',
    openingExpiryDate: '2026-12-31',
    gstApplicability: 'APPLICABLE',
    gstSupplyType: 'GOODS',
    hsnSacCode: '90189099',
    gstRatePct: '12',
    cessRatePct: null,
    cessPerUnit: null,
    isTobaccoMrpItem: false,
    mrpPerUnit: null,
    defaultSalesLedgerId: 'led-sales',
    defaultPurchaseLedgerId: null,
    versionNo: 1,
  },
  {
    id: 'item-gloves',
    companyId: TEST_COMPANY_ID,
    name: 'Surgical Nitrile Gloves',
    aliasCode: 'GLV-100',
    partNo: 'MD-GLV-10',
    description: '10 NOS per BOX dual-unit item',
    stockGroupId: 'sg-med',
    stockGroupName: 'Diagnostic Devices',
    stockCategoryId: null,
    baseUomId: 'uom-nos',
    baseUomSymbol: 'NOS',
    baseUomDecimals: 0,
    alternateUomId: 'uom-box',
    alternateUomSymbol: 'BOX',
    conversionNumerator: '10',
    conversionDenominator: '1',
    maintainInBatches: false,
    trackDateOfMfg: false,
    useExpiryDates: false,
    costingMethod: 'AVG_COST',
    marketValuationMethod: 'AVG_PRICE',
    standardCost: '50.00',
    standardSellingPrice: '80.00',
    openingQty: null,
    openingRate: null,
    openingValue: null,
    openingGodownId: null,
    openingBatchName: null,
    openingMfgDate: null,
    openingExpiryDate: null,
    gstApplicability: 'APPLICABLE',
    gstSupplyType: 'GOODS',
    hsnSacCode: '40151100',
    gstRatePct: '12',
    cessRatePct: null,
    cessPerUnit: null,
    isTobaccoMrpItem: false,
    mrpPerUnit: null,
    defaultSalesLedgerId: 'led-sales',
    defaultPurchaseLedgerId: null,
    versionNo: 1,
  },
  {
    id: 'item-tobacco',
    companyId: TEST_COMPANY_ID,
    name: 'Classic Leaf Pack 20s',
    aliasCode: 'TOB-20',
    partNo: 'TB-20',
    description: 'Rel 7.2 Retail Sale Price (MRP) GST item',
    stockGroupId: null,
    stockGroupName: null,
    stockCategoryId: null,
    baseUomId: 'uom-nos',
    baseUomSymbol: 'NOS',
    baseUomDecimals: 0,
    alternateUomId: null,
    alternateUomSymbol: null,
    conversionNumerator: null,
    conversionDenominator: null,
    maintainInBatches: false,
    trackDateOfMfg: false,
    useExpiryDates: false,
    costingMethod: 'AVG_COST',
    marketValuationMethod: 'AVG_PRICE',
    standardCost: '70.00',
    standardSellingPrice: '90.00',
    openingQty: null,
    openingRate: null,
    openingValue: null,
    openingGodownId: null,
    openingBatchName: null,
    openingMfgDate: null,
    openingExpiryDate: null,
    gstApplicability: 'APPLICABLE',
    gstSupplyType: 'GOODS',
    hsnSacCode: '24022090',
    gstRatePct: '28',
    cessRatePct: null,
    cessPerUnit: null,
    isTobaccoMrpItem: true,
    mrpPerUnit: '128.00',
    defaultSalesLedgerId: 'led-sales',
    defaultPurchaseLedgerId: null,
    versionNo: 1,
  },
  {
    id: 'item-sensor-raw',
    companyId: TEST_COMPANY_ID,
    name: 'Optical SpO2 Sensor Module',
    aliasCode: 'RAW-SNS',
    partNo: 'RM-01',
    description: 'Raw component for BOM',
    stockGroupId: 'sg-med',
    stockGroupName: 'Diagnostic Devices',
    stockCategoryId: null,
    baseUomId: 'uom-nos',
    baseUomSymbol: 'NOS',
    baseUomDecimals: 0,
    alternateUomId: null,
    alternateUomSymbol: null,
    conversionNumerator: null,
    conversionDenominator: null,
    maintainInBatches: false,
    trackDateOfMfg: false,
    useExpiryDates: false,
    costingMethod: 'AVG_COST',
    marketValuationMethod: 'AVG_PRICE',
    standardCost: '350.00',
    standardSellingPrice: null,
    openingQty: '100',
    openingRate: '350.00',
    openingValue: '35000.00',
    openingGodownId: 'gd-main',
    openingBatchName: null,
    openingMfgDate: null,
    openingExpiryDate: null,
    gstApplicability: 'APPLICABLE',
    gstSupplyType: 'GOODS',
    hsnSacCode: '90189099',
    gstRatePct: '12',
    cessRatePct: null,
    cessPerUnit: null,
    isTobaccoMrpItem: false,
    mrpPerUnit: null,
    defaultSalesLedgerId: null,
    defaultPurchaseLedgerId: null,
    versionNo: 1,
  },
];

const SEED_BOMS: readonly BomMaster[] = [
  {
    id: 'bom-oximeter-std',
    companyId: TEST_COMPANY_ID,
    stockItemId: 'item-oximeter',
    stockItemName: 'Pulse Oximeter Pro',
    bomName: 'Oximeter Standard Assembly BOM',
    unitOfManufacture: '1',
    components: [
      {
        stockItemId: 'item-sensor-raw',
        stockItemName: 'Optical SpO2 Sensor Module',
        componentType: 'COMPONENT',
        godownId: 'gd-main',
        quantity: '2',
        uomId: 'uom-nos',
      },
    ],
    additionalCostRules: [],
    versionNo: 1,
  },
];

const SEED_GROUPS: readonly AccountGroup[] = [
  {
    id: 'grp-debtors',
    companyId: TEST_COMPANY_ID,
    name: 'Sundry Debtors',
    aliasCode: null,
    parentGroupId: null,
    nature: 'ASSETS',
    affectsGrossProfit: false,
    behavesLikeSubledger: true,
    netDebitCreditForReporting: false,
    usedForCalculation: false,
    methodToAllocatePurchase: 'NOT_APPLICABLE',
    scheduleIiiLineCode: null,
    defaultGstRatePct: null,
    defaultHsnSacCode: null,
    depthLevel: 1,
    isSystemGroup: true,
    sortOrder: 10,
    versionNo: 1,
  },
  {
    id: 'grp-sales',
    companyId: TEST_COMPANY_ID,
    name: 'Sales Accounts',
    aliasCode: null,
    parentGroupId: null,
    nature: 'INCOME',
    affectsGrossProfit: true,
    behavesLikeSubledger: false,
    netDebitCreditForReporting: false,
    usedForCalculation: false,
    methodToAllocatePurchase: 'NOT_APPLICABLE',
    scheduleIiiLineCode: null,
    defaultGstRatePct: null,
    defaultHsnSacCode: null,
    depthLevel: 0,
    isSystemGroup: true,
    sortOrder: 20,
    versionNo: 1,
  },
  {
    id: 'grp-duties',
    companyId: TEST_COMPANY_ID,
    name: 'Duties & Taxes',
    aliasCode: null,
    parentGroupId: null,
    nature: 'LIABILITIES',
    affectsGrossProfit: false,
    behavesLikeSubledger: false,
    netDebitCreditForReporting: false,
    usedForCalculation: true,
    methodToAllocatePurchase: 'NOT_APPLICABLE',
    scheduleIiiLineCode: null,
    defaultGstRatePct: null,
    defaultHsnSacCode: null,
    depthLevel: 1,
    isSystemGroup: true,
    sortOrder: 30,
    versionNo: 1,
  },
];

function makeSeedLedger(
  overrides: Partial<Ledger> & Pick<Ledger, 'id' | 'name' | 'groupId'>,
): Ledger {
  return {
    companyId: TEST_COMPANY_ID,
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
  };
}

const SEED_LEDGERS: readonly Ledger[] = [
  makeSeedLedger({
    id: 'led-party-ap',
    name: 'Apollo Hospitals Chennai',
    aliasCode: 'APOLLO',
    groupId: 'grp-debtors',
    groupName: 'Sundry Debtors',
    partyRegistrationType: 'REGULAR',
    gstin: '33AAACA1234C1ZV',
    stateCode: '33',
  }),
  makeSeedLedger({
    id: 'led-sales',
    name: 'Domestic Sales A/c',
    aliasCode: 'SALES',
    groupId: 'grp-sales',
    groupName: 'Sales Accounts',
    openingSide: 'CR',
    inventoryValuesAffected: true,
    gstApplicability: 'APPLICABLE',
    gstRatePct: '12',
    gstSupplyType: 'GOODS',
  }),
  makeSeedLedger({
    id: 'led-cgst',
    name: 'Output CGST',
    aliasCode: 'CGST',
    groupId: 'grp-duties',
    groupName: 'Duties & Taxes',
    openingSide: 'CR',
    taxType: 'GST',
    dutyHead: 'CGST',
    gstRatePct: '14',
  }),
  makeSeedLedger({
    id: 'led-sgst',
    name: 'Output SGST',
    aliasCode: 'SGST',
    groupId: 'grp-duties',
    groupName: 'Duties & Taxes',
    openingSide: 'CR',
    taxType: 'GST',
    dutyHead: 'SGST_UTGST',
    gstRatePct: '14',
  }),
];

const SEED_VOUCHER_TYPES: readonly VoucherTypeMaster[] = [
  {
    id: 'vt-sales',
    companyId: TEST_COMPANY_ID,
    name: 'Sales',
    abbreviation: 'Sales',
    baseCategory: 'SALES',
    parentVoucherTypeId: null,
    postsAccountingEntries: true,
    movesInventory: true,
    defaultEntryMode: 'ITEM_INVOICE',
    numberingMethod: 'AUTOMATIC_RETAIN_NUMBERS',
    preventDuplicateNumbers: true,
    useEffectiveDates: false,
    allowZeroValuedTransactions: false,
    makeOptionalByDefault: false,
    allowNarrationPerLedger: false,
    printAfterSave: false,
    isManufacturingJournal: false,
    approvalPolicy: 'SINGLE_STEP',
    shortcutKey: 'F8',
    isSystemVoucherType: true,
    active: true,
    versionNo: 1,
  },
  {
    id: 'vt-stkjrn',
    companyId: TEST_COMPANY_ID,
    name: 'Stock Journal',
    abbreviation: 'Stk Jrnl',
    baseCategory: 'STOCK_JOURNAL',
    parentVoucherTypeId: null,
    postsAccountingEntries: false,
    movesInventory: true,
    defaultEntryMode: 'DOUBLE_ENTRY',
    numberingMethod: 'AUTOMATIC_RETAIN_NUMBERS',
    preventDuplicateNumbers: true,
    useEffectiveDates: false,
    allowZeroValuedTransactions: false,
    makeOptionalByDefault: false,
    allowNarrationPerLedger: false,
    printAfterSave: false,
    isManufacturingJournal: false,
    approvalPolicy: 'SINGLE_STEP',
    shortcutKey: 'Alt+F7',
    isSystemVoucherType: true,
    active: true,
    versionNo: 1,
  },
];

async function flushAsyncTicks(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

describe('Phase 4 Supply Chain Masters, ITEM_INVOICE & Stock Journal Grid, Batch Expiry Guard & Stock Summary', () => {
  let httpMock: HttpTestingController;
  let companyContext: CompanyContextStore;
  let coaStore: ChartOfAccountsStore;
  let inventoryStore: InventoryStore;
  let navStack: NavigationStackStore;
  let shortcutRegistry: ShortcutCommandRegistry;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      providers: [
        provideRouter(APP_ROUTES),
        provideHttpClient(
          withInterceptors([idempotencyInterceptor, etagInterceptor, problemDetailsInterceptor]),
        ),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    companyContext = TestBed.inject(CompanyContextStore);
    coaStore = TestBed.inject(ChartOfAccountsStore);
    inventoryStore = TestBed.inject(InventoryStore);
    navStack = TestBed.inject(NavigationStackStore);
    shortcutRegistry = TestBed.inject(ShortcutCommandRegistry);

    companyContext.setCompanies([TEST_COMPANY], TEST_COMPANY_ID);
    companyContext.setWorkingDate('2025-05-20');
    companyContext.setPeriodRange('2025-04-01', '2026-03-31');

    coaStore.setGroupsAndLedgers(SEED_GROUPS, SEED_LEDGERS);

    inventoryStore.setInventoryMasters(
      {
        uoms: SEED_UOMS,
        godowns: SEED_GODOWNS,
        stockGroups: SEED_STOCK_GROUPS,
        stockCategories: [],
        stockItems: SEED_STOCK_ITEMS,
        priceLists: [],
        boms: SEED_BOMS,
      },
      TEST_COMPANY_ID,
    );
  });

  it('creates UOMs, Stock Items with ExactDecimal Opening Value + staged BOM, and inline Alt+C Secondary Stock Item', async () => {
    // 1. UomWorkbenchComponent: create CTN Simple UOM
    const uomFixture = TestBed.createComponent(UomWorkbenchComponent);
    uomFixture.detectChanges();

    const uomComp = uomFixture.componentInstance;
    uomComp.symbol = 'CTN';
    uomComp.formalName = 'Cartons';
    uomComp.gstUqcCode = 'BOX';
    uomComp.decimalPlaces = 0;

    const uomSavePromise = uomComp.saveUom();
    const uomReq = httpMock.expectOne(`/api/v1/companies/${TEST_COMPANY_ID}/uoms`);
    expect(uomReq.request.method).toBe('POST');
    expect(uomReq.request.body).toMatchObject({
      uomType: 'SIMPLE',
      symbol: 'CTN',
      formalName: 'Cartons',
      gstUqcCode: 'BOX',
      decimalPlaces: 0,
    });
    uomReq.flush({
      id: 'uom-ctn',
      companyId: TEST_COMPANY_ID,
      uomType: 'SIMPLE',
      symbol: 'CTN',
      formalName: 'Cartons',
      gstUqcCode: 'BOX',
      decimalPlaces: 0,
      firstUomId: null,
      conversionFactor: null,
      secondUomId: null,
      versionNo: 1,
    } satisfies UomMaster);
    await uomSavePromise;
    expect(inventoryStore.uoms().some((u) => u.symbol === 'CTN')).toBe(true);
    uomFixture.destroy();

    // 2. StockItemWorkbenchComponent: ExactDecimal Opening Value (12.50 * 400.00 = 5000.00) + staged BOM -> POST /stock-items + POST /boms
    const itemFixture = TestBed.createComponent(StockItemWorkbenchComponent);
    itemFixture.detectChanges();

    const itemComp = itemFixture.componentInstance;
    itemComp.name = 'Digital Thermometer X1';
    itemComp.aliasCode = 'THERM-X1';
    itemComp.onStockGroupSelected({ id: 'sg-med', label: 'Diagnostic Devices' });
    expect(itemComp.hsnSacCode).toBe('90189099');
    expect(itemComp.gstRatePct).toBe('12');

    itemComp.onOpeningQtyOrRateChange('12.50', '400.00');
    expect(itemComp.openingValue()).toBe('5000.00');

    // Stage inline BOM before item is persisted
    itemComp.openBomModal();
    itemComp.bomName = 'Thermometer X1 Standard BOM';
    itemComp.unitOfManufacture = '1';
    itemComp.bomComponents.set([
      {
        rowKey: 1,
        componentType: 'COMPONENT',
        stockItemId: 'item-sensor-raw',
        godownId: 'gd-main',
        quantity: '1',
        costAllocationPct: '',
      },
    ]);
    await itemComp.saveBomFromModal();
    expect(itemComp.bomModalOpen()).toBe(false);
    expect(itemComp.pendingBomDraft()?.bomName).toBe('Thermometer X1 Standard BOM');

    const saveItemPromise = itemComp.saveStockItem();
    const createItemReq = httpMock.expectOne(`/api/v1/companies/${TEST_COMPANY_ID}/stock-items`);
    expect(createItemReq.request.method).toBe('POST');
    expect(createItemReq.request.body).toMatchObject({
      name: 'Digital Thermometer X1',
      openingQty: '12.50',
      openingRate: '400.00',
      openingValue: '5000.00',
      hsnSacCode: '90189099',
      gstRatePct: '12',
    });
    createItemReq.flush({
      ...SEED_STOCK_ITEMS[0],
      id: 'item-therm-x1',
      name: 'Digital Thermometer X1',
      aliasCode: 'THERM-X1',
      openingQty: '12.50',
      openingRate: '400.00',
      openingValue: '5000.00',
    } satisfies StockItemMaster);
    await flushAsyncTicks();

    const createBomReq = httpMock.expectOne(`/api/v1/companies/${TEST_COMPANY_ID}/boms`);
    expect(createBomReq.request.method).toBe('POST');
    expect(createBomReq.request.body).toMatchObject({
      stockItemId: 'item-therm-x1',
      bomName: 'Thermometer X1 Standard BOM',
      unitOfManufacture: '1',
    });
    createBomReq.flush({
      id: 'bom-therm-01',
      companyId: TEST_COMPANY_ID,
      stockItemId: 'item-therm-x1',
      bomName: 'Thermometer X1 Standard BOM',
      unitOfManufacture: '1',
      components: createBomReq.request.body.components,
      additionalCostRules: [],
      versionNo: 1,
    } satisfies BomMaster);
    await saveItemPromise;
    itemFixture.destroy();

    // 3. Alt+C SecondaryMasterModalComponent inline STOCK_ITEM creation
    const modalService = TestBed.inject(SecondaryMasterModalService);
    const secFixture = TestBed.createComponent(SecondaryMasterModalComponent);
    secFixture.detectChanges();

    let createdInlineId = '';
    modalService.open({
      kind: 'STOCK_ITEM',
      initialName: 'Inline Lancets Pack',
      onCreated: (res) => {
        createdInlineId = res.id;
      },
    });
    secFixture.detectChanges();
    expect(navStack.depth()).toBe(1);

    const secSavePromise = secFixture.componentInstance.saveActiveSecondaryMaster();
    const secItemReq = httpMock.expectOne(`/api/v1/companies/${TEST_COMPANY_ID}/stock-items`);
    expect(secItemReq.request.body.name).toBe('Inline Lancets Pack');
    secItemReq.flush({
      ...SEED_STOCK_ITEMS[1],
      id: 'item-inline-lancet',
      name: 'Inline Lancets Pack',
    } satisfies StockItemMaster);
    await secSavePromise;

    expect(createdInlineId).toBe('item-inline-lancet');
    expect(navStack.depth()).toBe(0);
    secFixture.destroy();
    httpMock.verify();
  });

  it('enforces single-UOM Enter skip vs dual-UOM focusable selector, blocks expired batches in GodownBatchPopover, and computes Rel 7.2 Tobacco Deemed MRP GST on Sales ITEM_INVOICE', async () => {
    const fixture = TestBed.createComponent(VoucherGridComponent);
    fixture.detectChanges();

    httpMock
      .match((req) => req.url.endsWith('/voucher-types'))
      .forEach((r) => r.flush(SEED_VOUCHER_TYPES));
    await fixture.whenStable();
    httpMock
      .match((req) => req.url.endsWith('/vouchers/next-number'))
      .forEach((r) => r.flush({ voucherNumber: 'INV/0001/25-26' }));
    await fixture.whenStable();

    const grid = fixture.componentInstance;
    grid.switchToCategory('SALES');
    httpMock
      .match((req) => req.url.endsWith('/vouchers/next-number'))
      .forEach((r) => r.flush({ voucherNumber: 'INV/0001/25-26' }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(grid.persistedView()).toBe('ITEM_INVOICE');

    // 1. Select single-UOM batch-tracked item (`Pulse Oximeter Pro`, alternateUomId === null)
    grid.onPartyLedgerSelected({ id: 'led-party-ap', label: 'Apollo Hospitals Chennai' });
    grid.onStockItemSelected(0, { id: 'item-oximeter', label: 'Pulse Oximeter Pro' });
    grid.onItemHookQtyOrRateChange(0, '2', '1500.00', '0');
    fixture.detectChanges();

    const readonlyUomSpan: HTMLElement | null = fixture.nativeElement.querySelector(
      '[data-testid="readonly-uom-cell"]',
    );
    expect(readonlyUomSpan).not.toBeNull();
    expect(readonlyUomSpan?.textContent?.trim()).toBe('NOS');
    expect(readonlyUomSpan?.hasAttribute('mcgridcellnav')).toBe(false);
    expect(fixture.nativeElement.querySelector('[data-testid="editable-uom-cell-0"]')).toBeNull();

    // 2. Open Godown & Batch allocation modal -> verify expired batch is blocked, valid batch is accepted
    grid.openItemBatchModal('SOURCE', 0);
    fixture.detectChanges();

    const batchReq = httpMock.expectOne(
      (req) =>
        req.url === `/api/v1/companies/${TEST_COMPANY_ID}/stock-items/item-oximeter/batches` &&
        req.params.get('asOfDate') === '2025-05-20',
    );
    const batchesPayload: readonly StockBatchDto[] = [
      {
        stockItemId: 'item-oximeter',
        godownId: 'gd-main',
        godownName: 'Main Location',
        batchName: 'LOT-EXP-01',
        mfgDate: '2024-04-01',
        expiryDate: '2025-04-10',
        closingQty: '5',
        isExpired: true,
      },
      {
        stockItemId: 'item-oximeter',
        godownId: 'gd-main',
        godownName: 'Main Location',
        batchName: 'LOT-OK-02',
        mfgDate: '2025-01-15',
        expiryDate: '2026-06-30',
        closingQty: '15',
        isExpired: false,
      },
    ];
    batchReq.flush(batchesPayload);
    await flushAsyncTicks();
    fixture.detectChanges();

    const batchPopoverEl = fixture.debugElement.children[0].query(
      (de) => de.name === 'mc-godown-batch-popover',
    );
    const batchPopover = batchPopoverEl.componentInstance;

    // Attempt to pick and commit the expired batch (`2025-04-10 < 2025-05-20`)
    batchPopover.pickAvailableBatch(0, batchesPayload[0]);
    batchPopover.commitAllocations();
    fixture.detectChanges();

    expect(batchPopover.validationError()).toContain('LOT-EXP-01');
    expect(batchPopover.validationError()).toContain('expired');
    expect(grid.activeItemBatchModal()).not.toBeNull();

    // Pick valid non-expired batch (`LOT-OK-02`) and commit
    batchPopover.pickAvailableBatch(0, batchesPayload[1]);
    batchPopover.commitAllocations();
    fixture.detectChanges();

    expect(batchPopover.validationError()).toBeNull();
    expect(grid.activeItemBatchModal()).toBeNull();
    expect(grid.itemHookRows()[0].batchAllocations).toHaveLength(1);
    expect(grid.itemHookRows()[0].batchAllocations?.[0].batchName).toBe('LOT-OK-02');

    // 3. Switch row 0 to dual-UOM item (`Surgical Nitrile Gloves`, alternateUomId === 'uom-box') to verify focusable UOM selector
    grid.onStockItemSelected(0, { id: 'item-gloves', label: 'Surgical Nitrile Gloves' });
    fixture.detectChanges();
    const editableUomSelect: HTMLSelectElement | null = fixture.nativeElement.querySelector(
      '[data-testid="editable-uom-cell-0"]',
    );
    expect(editableUomSelect).not.toBeNull();

    // 4. Switch row 0 to Rel 7.2 Tobacco Deemed MRP item (`mrpPerUnit: 128.00`, `gstRatePct: 28`, qty `10`, commercial rate `90.00`)
    // Commercial line amount = 900.00; Deemed Taxable Base = (128 * 10 * 100) / 128 = 1000.00
    // Output CGST @ 14% = 140.00, Output SGST @ 14% = 140.00; Total Party Invoice = 1180.00
    grid.onStockItemSelected(0, { id: 'item-tobacco', label: 'Classic Leaf Pack 20s' });
    grid.onItemHookQtyOrRateChange(0, '10', '90.00', '0');
    grid.onLineLedgerSelected(0, { id: 'led-cgst', label: 'Output CGST' });
    grid.appendBlankLine();
    grid.onLineLedgerSelected(1, { id: 'led-sgst', label: 'Output SGST' });
    fixture.detectChanges();

    expect(grid.itemHookRows()[0].amount).toBe('900.00');
    expect(grid.gridLines()[0].amount).toBe('140.00');
    expect(grid.gridLines()[1].amount).toBe('140.00');
    expect(grid.totals().totalDebit.toWireString(2)).toBe('1180.00');
    expect(grid.totals().totalCredit.toWireString(2)).toBe('1180.00');
    expect(grid.totals().isBalancedAndPositive).toBe(true);

    // Submit Sales ITEM_INVOICE voucher and inspect serialized payload
    const submitPromise = grid.submitVoucher('POST');
    const postReq = httpMock.expectOne(`/api/v1/companies/${TEST_COMPANY_ID}/vouchers`);
    expect(postReq.request.method).toBe('POST');
    expect(postReq.request.headers.get('Idempotency-Key')).toBeTruthy();

    const sentBody = postReq.request.body as UpsertVoucherRequest;
    expect(sentBody.persistedView).toBe('ITEM_INVOICE');
    expect(sentBody.inventoryLines).toHaveLength(1);
    expect(sentBody.inventoryLines?.[0]).toMatchObject({
      stockItemId: 'item-tobacco',
      movementDirection: 'OUTWARD',
      actualQty: '10',
      billedQty: '10',
      rate: '90.00',
      amount: '900.00',
      declaredMrp: '128.00',
    });
    expect(sentBody.lines.find((l) => l.lineRole === 'PARTY')?.amount).toBe('1180.00');

    postReq.flush({
      id: 'vch-sales-tob-01',
      companyId: TEST_COMPANY_ID,
      voucherTypeId: 'vt-sales',
      voucherTypeName: 'Sales',
      voucherCategory: 'SALES',
      voucherNumber: 'INV/0001/25-26',
      voucherDate: '2025-05-20',
      persistedView: 'ITEM_INVOICE',
      postingNature: 'NORMAL',
      status: 'POSTED',
      currencyCode: 'INR',
      narration: '',
      totalDebit: '1180.00',
      totalCredit: '1180.00',
      versionNo: 1,
      lines: sentBody.lines.map((l, idx) => ({ ...l, lineNo: l.lineNo ?? idx + 1 })),
      inventoryLines: sentBody.inventoryLines,
    } satisfies VoucherDetailDto);
    await submitPromise;
    httpMock.match((req) => req.url.endsWith('/vouchers/next-number')).forEach((r) =>
      r.flush({ voucherNumber: 'INV/0002/25-26' }),
    );
    await flushAsyncTicks();

    fixture.destroy();
    httpMock.verify();
  });

  it('runs Stock Journal (Alt+F7) Ctrl+H Manufacturing BOM Preview and posts pure inventory lines', async () => {
    const fixture = TestBed.createComponent(VoucherGridComponent);
    fixture.detectChanges();
    httpMock
      .match((req) => req.url.endsWith('/voucher-types'))
      .forEach((r) => r.flush(SEED_VOUCHER_TYPES));
    await fixture.whenStable();
    httpMock
      .match((req) => req.url.endsWith('/vouchers/next-number'))
      .forEach((r) => r.flush({ voucherNumber: 'SJ/001' }));
    await fixture.whenStable();

    const grid = fixture.componentInstance;
    grid.switchToCategory('STOCK_JOURNAL');
    httpMock
      .match((req) => req.url.endsWith('/vouchers/next-number'))
      .forEach((r) => r.flush({ voucherNumber: 'SJ/001' }));
    await fixture.whenStable();

    // Toggle Ctrl+H Change Mode on Stock Journal -> enables Use for Manufacturing (BOM)
    shortcutRegistry.triggerCommand('voucher.changeMode');
    fixture.detectChanges();
    expect(grid.bomManufacturingMode()).toBe(true);

    grid.onManufacturingProductSelected({ id: 'item-oximeter', label: 'Pulse Oximeter Pro' });
    expect(grid.selectedBomId()).toBe('bom-oximeter-std');
    grid.manufacturedQty.set('5');
    grid.manufacturingTargetGodownId.set('gd-main');

    const previewPromise = grid.runManufacturingBomPreview();
    const previewReq = httpMock.expectOne(
      `/api/v1/companies/${TEST_COMPANY_ID}/vouchers/manufacturing-preview`,
    );
    expect(previewReq.request.method).toBe('POST');
    expect(previewReq.request.body).toMatchObject({
      bomId: 'bom-oximeter-std',
      finishedStockItemId: 'item-oximeter',
      manufacturedQty: '5',
      targetGodownId: 'gd-main',
    });

    const previewResp: ManufacturingPreviewResponse = {
      bomId: 'bom-oximeter-std',
      bomName: 'Oximeter Standard Assembly BOM',
      finishedStockItemId: 'item-oximeter',
      finishedStockItemName: 'Pulse Oximeter Pro',
      manufacturedQty: '5',
      components: [
        {
          stockItemId: 'item-sensor-raw',
          stockItemName: 'Optical SpO2 Sensor Module',
          godownId: 'gd-main',
          uomId: 'uom-nos',
          uomSymbol: 'NOS',
          requiredQty: '10',
          unitRate: '350.00',
          amount: '3500.00',
        },
      ],
      componentTotalCost: '3500.00',
      additionalCostTotal: '250.00',
      totalManufacturingCost: '3750.00',
      effectiveUnitRate: '750.00',
    };
    previewReq.flush(previewResp);
    await previewPromise;
    fixture.detectChanges();

    expect(grid.itemHookRows()).toHaveLength(1);
    expect(grid.itemHookRows()[0]).toMatchObject({
      stockItemId: 'item-sensor-raw',
      quantity: '10',
      rate: '350.00',
      amount: '3500.00',
      movementDirection: 'OUTWARD',
    });
    expect(grid.stockJournalDestRows()).toHaveLength(1);
    expect(grid.stockJournalDestRows()[0]).toMatchObject({
      stockItemId: 'item-oximeter',
      quantity: '5',
      rate: '750.00',
      amount: '3750.00',
      movementDirection: 'INWARD',
    });

    const submitPromise = grid.submitVoucher('POST');
    const postReq = httpMock.expectOne(`/api/v1/companies/${TEST_COMPANY_ID}/vouchers`);
    const body = postReq.request.body as UpsertVoucherRequest;
    expect(body.lines).toEqual([]);
    expect(body.inventoryLines).toHaveLength(2);
    expect(body.inventoryLines?.[0].movementDirection).toBe('OUTWARD');
    expect(body.inventoryLines?.[1].movementDirection).toBe('INWARD');
    expect(body.inventoryLines?.[1].amount).toBe('3750.00');

    postReq.flush({
      id: 'vch-sj-001',
      companyId: TEST_COMPANY_ID,
      voucherTypeId: 'vt-stkjrn',
      voucherTypeName: 'Stock Journal',
      voucherCategory: 'STOCK_JOURNAL',
      voucherNumber: 'SJ/001',
      voucherDate: '2025-05-20',
      persistedView: 'AS_VOUCHER',
      postingNature: 'NORMAL',
      status: 'POSTED',
      currencyCode: 'INR',
      narration: '',
      totalDebit: '0.00',
      totalCredit: '0.00',
      versionNo: 1,
      lines: [],
      inventoryLines: body.inventoryLines,
    } satisfies VoucherDetailDto);
    await submitPromise;
    httpMock.match((req) => req.url.endsWith('/vouchers/next-number')).forEach((r) =>
      r.flush({ voucherNumber: 'SJ/002' }),
    );
    await flushAsyncTicks();

    fixture.destroy();
    httpMock.verify();
  });

  it('renders Golden Vector Stock Summary values, switches Costing Basis via Ctrl+B (FIFO), drills into Movement Analysis on Enter, and flags EXPIRED batches', async () => {
    const router = TestBed.inject(Router);
    const navSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    const fixture = TestBed.createComponent(StockSummaryComponent);
    fixture.detectChanges();

    const initialSummaryReq = httpMock.expectOne(
      (req) =>
        req.url === `/api/v1/companies/${TEST_COMPANY_ID}/reports/stock-summary` &&
        !req.params.has('valuationMethodOverride'),
    );

    const goldenVectorReport: StockSummaryReportDto = {
      fromDate: '2025-04-01',
      toDate: '2026-03-31',
      valuationMethodOverride: null,
      groups: [
        {
          stockGroupId: 'sg-med',
          stockGroupName: 'Diagnostic Devices',
          openingValue: '18000.00',
          inwardValue: '40000.00',
          outwardValue: '12314.29',
          closingQty: '62',
          closingValue: '45685.71',
        },
      ],
      items: [
        {
          stockItemId: 'item-oximeter',
          stockItemName: 'Pulse Oximeter Pro',
          stockGroupId: 'sg-med',
          stockGroupName: 'Diagnostic Devices',
          hsnSacCode: '90189099',
          uomSymbol: 'NOS',
          costingMethod: 'AVG_COST',
          openingQty: '20',
          openingRate: '900.00',
          openingValue: '18000.00',
          inwardQty: '15',
          inwardValue: '15000.00',
          outwardQty: '5',
          outwardValue: '6000.00',
          closingQty: '30',
          closingRate: '900.00',
          closingValue: '27000.00',
        },
        {
          stockItemId: 'item-therm-x1',
          stockItemName: 'Digital Thermometer X1',
          stockGroupId: 'sg-med',
          stockGroupName: 'Diagnostic Devices',
          hsnSacCode: '90251190',
          uomSymbol: 'NOS',
          costingMethod: 'AVG_COST',
          openingQty: '0',
          openingRate: '0.00',
          openingValue: '0.00',
          inwardQty: '35',
          inwardValue: '20400.00',
          outwardQty: '3',
          outwardValue: '1714.29',
          closingQty: '32',
          closingRate: '583.93',
          closingValue: '18685.71',
        },
      ],
      totalOpeningValue: '18000.00',
      totalInwardValue: '35400.00',
      totalOutwardValue: '7714.29',
      totalClosingQty: '62',
      totalClosingValue: '45685.71',
    };

    initialSummaryReq.flush(goldenVectorReport);
    await flushAsyncTicks();
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="closing-val-item-oximeter"]')?.textContent,
    ).toContain('27,000.00');
    expect(
      fixture.nativeElement.querySelector('[data-testid="closing-val-item-therm-x1"]')?.textContent,
    ).toContain('18,685.71');
    expect(
      fixture.nativeElement.querySelector('[data-testid="grand-total-closing-val"]')?.textContent,
    ).toContain('45,685.71');

    // Toggle Alt+F1 Detailed Mode
    shortcutRegistry.triggerCommand('report.toggleDetailed');
    fixture.detectChanges();
    expect(fixture.componentInstance.detailedMode()).toBe(true);
    expect(
      fixture.nativeElement.querySelector('[data-testid="stock-summary-detailed-badge"]'),
    ).not.toBeNull();

    // Open Ctrl+B Basis of Values -> switch to FIFO -> verify valuationMethodOverride=FIFO re-query
    shortcutRegistry.triggerCommand('report.basisOfValues');
    fixture.detectChanges();
    expect(fixture.componentInstance.basisModalOpen()).toBe(true);

    fixture.componentInstance.draftValuationOverride = 'FIFO';
    fixture.componentInstance.applyBasisOfValues();

    const fifoSummaryReq = httpMock.expectOne(
      (req) =>
        req.url === `/api/v1/companies/${TEST_COMPANY_ID}/reports/stock-summary` &&
        req.params.get('valuationMethodOverride') === 'FIFO',
    );
    fifoSummaryReq.flush({
      ...goldenVectorReport,
      valuationMethodOverride: 'FIFO',
      totalClosingValue: '46100.00',
    });
    await flushAsyncTicks();
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="stock-summary-valuation-badge"]')
        ?.textContent,
    ).toContain('FIFO');
    expect(
      fixture.nativeElement.querySelector('[data-testid="grand-total-closing-val"]')?.textContent,
    ).toContain('46,100.00');

    // Press Enter on selected Stock Item (`item-oximeter`) -> drills down to Movement Analysis
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
    const movementReq = httpMock.expectOne(
      (req) =>
        req.url === `/api/v1/companies/${TEST_COMPANY_ID}/reports/movement-analysis` &&
        req.params.get('stockItemId') === 'item-oximeter',
    );
    const movementPayload: MovementAnalysisReportDto = {
      stockItemId: 'item-oximeter',
      stockItemName: 'Pulse Oximeter Pro',
      uomSymbol: 'NOS',
      inwardQty: '15',
      inwardValue: '15000.00',
      outwardQty: '5',
      outwardValue: '7500.00',
      movements: [
        {
          voucherId: 'vch-sale-99',
          voucherDate: '2025-05-18',
          voucherTypeName: 'Sales',
          voucherNumber: 'INV/0009/25-26',
          particulars: 'Apollo Hospitals Chennai',
          movementDirection: 'OUTWARD',
          quantity: '5',
          rate: '1500.00',
          value: '7500.00',
        },
      ],
    };
    movementReq.flush(movementPayload);
    await flushAsyncTicks();
    fixture.detectChanges();

    expect(fixture.componentInstance.activeTab()).toBe('MOVEMENT_ANALYSIS');
    expect(
      fixture.nativeElement.querySelector('[data-testid="movement-row-0"]')?.textContent,
    ).toContain('Apollo Hospitals Chennai');

    // Press Enter on movement voucher row -> navigates to /app/vouchers/vch-sale-99
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
    expect(navSpy).toHaveBeenCalledWith(['/app/vouchers', 'vch-sale-99']);

    // Switch to Batch Ageing tab -> verify EXPIRED badge renders
    fixture.componentInstance.switchTab('BATCH_AGEING');
    const batchAgeingReq = httpMock.expectOne(
      (req) => req.url === `/api/v1/companies/${TEST_COMPANY_ID}/reports/batch-ageing`,
    );
    batchAgeingReq.flush({
      asOfDate: '2026-03-31',
      batches: [
        {
          stockItemId: 'item-oximeter',
          stockItemName: 'Pulse Oximeter Pro',
          godownName: 'Main Location',
          batchName: 'LOT-EXP-01',
          mfgDate: '2024-04-01',
          expiryDate: '2025-04-10',
          daysToExpiry: -355,
          isExpired: true,
          closingQty: '5',
          closingValue: '4500.00',
        },
      ],
    } satisfies BatchAgeingReportDto);
    await flushAsyncTicks();
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="batch-expired-badge"]')?.textContent,
    ).toContain('EXPIRED');

    fixture.destroy();
    httpMock.verify();
  });
});
