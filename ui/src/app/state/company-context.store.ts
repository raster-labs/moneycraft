import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ProblemFeedbackStore } from '../core/http/problem-details.interceptor';
import { RuntimeConfigService } from '../core/http/runtime-config.service';

export type InstallationSecurityState = 'UNINITIALISED' | 'ACTIVE' | 'QUARANTINED';
export type FiscalPeriodState = 'OPEN' | 'SOFT_LOCKED' | 'CLOSED';

export interface SystemStatusResponse {
  readonly securityState: InstallationSecurityState;
  readonly fenceEpoch: number;
  readonly version: string;
  readonly quarantineReason?: string | null;
}

export interface CompanyFeaturesConfig {
  readonly maintainAccounts: boolean;
  readonly billWiseEnabled: boolean;
  readonly costCentresEnabled: boolean;
  readonly interestCalculationEnabled?: boolean;
  readonly maintainInventory: boolean;
  readonly inventoryIntegrated: boolean;
  readonly multiplePriceLevelsEnabled?: boolean;
  readonly batchesEnabled?: boolean;
  readonly expiryDateForBatchesEnabled?: boolean;
  readonly jobOrderProcessingEnabled?: boolean;
  readonly costTrackingEnabled?: boolean;
  readonly jobCostingEnabled?: boolean;
  readonly useDiscountColumnInInvoices?: boolean;
  readonly useSeparateActualAndBilledQty?: boolean;
  readonly gstEnabled: boolean;
  readonly multiGstinEnabled: boolean;
  readonly gstRegistrationType: 'REGULAR' | 'COMPOSITION' | 'ISD' | 'SEZ';
  readonly gstin: string;
  readonly gstr1Periodicity: 'MONTHLY' | 'QUARTERLY';
  readonly gstEffectiveFrom: string;
  readonly eInvoiceApplicable?: boolean;
  readonly eWayBillApplicable?: boolean;
  readonly eWayBillThresholdLimit?: string;
  readonly tdsEnabled: boolean;
  readonly tcsEnabled?: boolean;
  readonly vatEnabled?: boolean;
  readonly exciseEnabled?: boolean;
  readonly serviceTaxEnabled?: boolean;
  readonly payrollEnabled?: boolean;
  readonly payrollStatutoryEnabled?: boolean;
  readonly multipleAddressesEnabled?: boolean;
  readonly markModifiedVouchers?: boolean;
  readonly mcaEditLogEnforced: boolean;
}

export interface CompanyF12Config {
  readonly useCrDrInsteadOfToBy: boolean;
  readonly provideReferenceNoAndDate: boolean;
  readonly provideDispatchOrderExportDetails: boolean;
  readonly warnOnNegativeCash: boolean;
  readonly warnOnNegativeStock: boolean;
  readonly preallocateBills: boolean;
  readonly showBillWiseList: boolean;
  readonly showBillFinalBalances: boolean;
  readonly useSingleEntryForPaymentReceiptContra: boolean;
  readonly showLedgerCurrentBalance: boolean;
  readonly showBalancesAsOnVoucherDate: boolean;
  readonly provideLineNarration: boolean;
  readonly provideCommonNarration: boolean;
  readonly consolidateStockItemsWithSameRate: boolean;
  readonly allowGstRateOverride: boolean;
  readonly provideAliases: boolean;
  readonly billWiseForNonTradingAccounts: boolean;
  readonly provideCreditLimits: boolean;
  readonly provideLedgerAddresses: boolean;
  readonly provideLedgerBankDetails: boolean;
  readonly providePartNumbers: boolean;
  readonly provideAlternateUnits: boolean;
  readonly provideStandardRates: boolean;
  readonly showOpeningBalances: boolean;
  readonly showTransactions: boolean;
  readonly showNettTransactionsOnly: boolean;
  readonly showClosingBalances: boolean;
  readonly showPercentages: boolean;
  readonly reportFormat: string;
  readonly scaleFactor: string;
  readonly sortingMethod: string;
}

export const DEFAULT_COMPANY_F12_CONFIG: CompanyF12Config = {
  useCrDrInsteadOfToBy: true,
  provideReferenceNoAndDate: true,
  provideDispatchOrderExportDetails: true,
  warnOnNegativeCash: true,
  warnOnNegativeStock: true,
  preallocateBills: true,
  showBillWiseList: true,
  showBillFinalBalances: true,
  useSingleEntryForPaymentReceiptContra: true,
  showLedgerCurrentBalance: true,
  showBalancesAsOnVoucherDate: true,
  provideLineNarration: false,
  provideCommonNarration: true,
  consolidateStockItemsWithSameRate: false,
  allowGstRateOverride: true,
  provideAliases: true,
  billWiseForNonTradingAccounts: false,
  provideCreditLimits: true,
  provideLedgerAddresses: true,
  provideLedgerBankDetails: true,
  providePartNumbers: true,
  provideAlternateUnits: true,
  provideStandardRates: true,
  showOpeningBalances: false,
  showTransactions: false,
  showNettTransactionsOnly: false,
  showClosingBalances: true,
  showPercentages: false,
  reportFormat: 'CONDENSED',
  scaleFactor: 'DEFAULT',
  sortingMethod: 'DEFAULT',
};

export const DEFAULT_F12_CONFIG = DEFAULT_COMPANY_F12_CONFIG;

export function normalizeCompanyFeatures(
  raw?: Partial<CompanyFeaturesConfig> | null,
): CompanyFeaturesConfig {
  const maintainAccounts = raw?.maintainAccounts ?? true;
  const maintainInventory = raw?.maintainInventory ?? true;
  const batchesEnabled = raw?.batchesEnabled ?? true;
  const costCentresEnabled = raw?.costCentresEnabled ?? false;
  const payrollEnabled = raw?.payrollEnabled ?? false;
  return {
    maintainAccounts,
    billWiseEnabled: maintainAccounts ? (raw?.billWiseEnabled ?? true) : false,
    costCentresEnabled,
    interestCalculationEnabled: raw?.interestCalculationEnabled ?? false,
    maintainInventory,
    inventoryIntegrated:
      maintainAccounts && maintainInventory ? (raw?.inventoryIntegrated ?? true) : false,
    multiplePriceLevelsEnabled: raw?.multiplePriceLevelsEnabled ?? false,
    batchesEnabled,
    expiryDateForBatchesEnabled: batchesEnabled
      ? (raw?.expiryDateForBatchesEnabled ?? true)
      : false,
    jobOrderProcessingEnabled: raw?.jobOrderProcessingEnabled ?? false,
    costTrackingEnabled: raw?.costTrackingEnabled ?? false,
    jobCostingEnabled: costCentresEnabled ? (raw?.jobCostingEnabled ?? false) : false,
    useDiscountColumnInInvoices: raw?.useDiscountColumnInInvoices ?? true,
    useSeparateActualAndBilledQty: raw?.useSeparateActualAndBilledQty ?? true,
    gstEnabled: raw?.gstEnabled ?? true,
    multiGstinEnabled: raw?.multiGstinEnabled ?? false,
    gstRegistrationType: raw?.gstRegistrationType ?? 'REGULAR',
    gstin: raw?.gstin ?? '',
    gstr1Periodicity: raw?.gstr1Periodicity ?? 'MONTHLY',
    gstEffectiveFrom: raw?.gstEffectiveFrom ?? '2025-04-01',
    eInvoiceApplicable: raw?.eInvoiceApplicable ?? false,
    eWayBillApplicable: raw?.eWayBillApplicable ?? true,
    eWayBillThresholdLimit: raw?.eWayBillThresholdLimit ?? '50000.00',
    tdsEnabled: raw?.tdsEnabled ?? true,
    tcsEnabled: raw?.tcsEnabled ?? false,
    vatEnabled: raw?.vatEnabled ?? false,
    exciseEnabled: raw?.exciseEnabled ?? false,
    serviceTaxEnabled: raw?.serviceTaxEnabled ?? false,
    payrollEnabled,
    payrollStatutoryEnabled: payrollEnabled ? (raw?.payrollStatutoryEnabled ?? false) : false,
    multipleAddressesEnabled: raw?.multipleAddressesEnabled ?? false,
    markModifiedVouchers: raw?.markModifiedVouchers ?? false,
    mcaEditLogEnforced: raw?.mcaEditLogEnforced ?? true,
  };
}

export interface CompanySummary {
  readonly id: string;
  readonly code: string;
  readonly legalName: string;
  readonly mailingName: string;
  readonly countryCode: string;
  readonly stateCode: string;
  readonly pinCode: string;
  readonly baseCurrencyCode: string;
  readonly currencySymbol: string;
  readonly currencyScale: number;
  readonly financialYearFrom: string;
  readonly booksBeginningFrom: string;
  readonly lastEntryDate: string | null;
  readonly versionNo: number;
  readonly features: CompanyFeaturesConfig;
}

export interface FiscalPeriodSummary {
  readonly id: string;
  readonly fiscalYearId: string;
  readonly periodNo?: number;
  readonly code?: string;
  readonly periodCode: string;
  readonly name?: string;
  readonly startDate: string;
  readonly endDate: string;
  readonly state: FiscalPeriodState;
  readonly lockedReason?: string | null;
  readonly versionNo?: number;
}

export interface FiscalYearSummary {
  readonly id: string;
  readonly fiscalYearId: string;
  readonly code: string;
  readonly periodCode: string;
  readonly displayLabel: string;
  readonly startDate: string;
  readonly endDate: string;
  readonly closed: boolean;
  readonly state: string;
  readonly versionNo: number;
  readonly periods: readonly FiscalPeriodSummary[];
}

export interface CreateFiscalYearPayload {
  readonly code: string;
  readonly displayLabel: string;
  readonly startDate: string;
  readonly endDate: string;
}

export interface CreateOrUpdateCompanyPayload {
  readonly code: string;
  readonly legalName: string;
  readonly mailingName: string;
  readonly addressLines?: string;
  readonly countryCode: string;
  readonly stateCode: string;
  readonly stateName?: string;
  readonly pinCode: string;
  readonly pan?: string;
  readonly baseCurrencyCode: string;
  readonly currencySymbol: string;
  readonly currencyScale: number;
  readonly financialYearFrom: string;
  readonly booksBeginningFrom: string;
  readonly versionNo?: number;
  readonly features: CompanyFeaturesConfig;
}

const SESSION_ACTIVE_COMPANY_KEY = 'mc.activeCompanyId';
const SESSION_PERIOD_PREFIX = 'mc.period.';
const SESSION_WORKDATE_PREFIX = 'mc.workDate.';
const SESSION_F12_PREFIX = 'mc.f12Config.';

function isBrowserStorageAvailable(): boolean {
  if (typeof sessionStorage === 'undefined') {
    return false;
  }
  if (typeof navigator !== 'undefined' && /jsdom/i.test(navigator.userAgent)) {
    return false;
  }
  return true;
}

function readSessionValue(key: string): string | null {
  try {
    return isBrowserStorageAvailable() ? sessionStorage.getItem(key) : null;
  } catch {
    return null;
  }
}

function writeSessionValue(key: string, value: string): void {
  try {
    if (isBrowserStorageAvailable()) {
      sessionStorage.setItem(key, value);
    }
  } catch {
    // Ignore storage quota or restricted context errors
  }
}

@Injectable({ providedIn: 'root' })
export class CompanyContextStore {
  private readonly http = inject(HttpClient);
  private readonly runtimeConfig = inject(RuntimeConfigService);
  private readonly problemFeedback = inject(ProblemFeedbackStore);

  private readonly systemStatusSignal = signal<InstallationSecurityState>('ACTIVE');
  private readonly statusLoadedSignal = signal<boolean>(false);
  private readonly companiesLoadedSignal = signal<boolean>(false);
  private readonly fenceEpochSignal = signal<number>(1);
  private readonly quarantineReasonSignal = signal<string | null>(null);

  private readonly openCompaniesSignal = signal<readonly CompanySummary[]>([]);
  private readonly activeCompanyIdSignal = signal<string | null>(
    readSessionValue(SESSION_ACTIVE_COMPANY_KEY),
  );
  private readonly periodsSignal = signal<readonly FiscalPeriodSummary[]>([]);

  private readonly periodFromSignal = signal<string>('2025-04-01');
  private readonly periodToSignal = signal<string>('2026-03-31');
  private readonly workingDateSignal = signal<string>('2025-04-01');
  private readonly hasUnsavedDraftSignal = signal<boolean>(false);
  private readonly f12ConfigSignal = signal<CompanyF12Config>(DEFAULT_COMPANY_F12_CONFIG);

  readonly systemStatus = this.systemStatusSignal.asReadonly();
  readonly statusLoaded = this.statusLoadedSignal.asReadonly();
  readonly companiesLoaded = this.companiesLoadedSignal.asReadonly();
  readonly fenceEpoch = this.fenceEpochSignal.asReadonly();
  readonly quarantineReason = this.quarantineReasonSignal.asReadonly();

  readonly openCompanies = this.openCompaniesSignal.asReadonly();
  readonly activeCompanyId = this.activeCompanyIdSignal.asReadonly();
  readonly periods = this.periodsSignal.asReadonly();
  readonly periodFrom = this.periodFromSignal.asReadonly();
  readonly periodTo = this.periodToSignal.asReadonly();
  readonly workingDate = this.workingDateSignal.asReadonly();
  readonly hasUnsavedDraft = this.hasUnsavedDraftSignal.asReadonly();
  readonly f12Config = this.f12ConfigSignal.asReadonly();

  readonly activeCompany = computed<CompanySummary | null>(() => {
    const id = this.activeCompanyIdSignal();
    const list = this.openCompaniesSignal();
    if (!id) {
      return list[0] ?? null;
    }
    return list.find((c) => c.id === id) ?? list[0] ?? null;
  });

  readonly currencyScale = computed<number>(() => this.activeCompany()?.currencyScale ?? 2);
  readonly currencySymbol = computed<string>(() => this.activeCompany()?.currencySymbol ?? '\u20B9');

  readonly isInventoryEnabled = computed<boolean>(
    () => this.activeCompany()?.features.maintainInventory !== false,
  );
  readonly isBillWiseEnabled = computed<boolean>(
    () =>
      this.activeCompany()?.features.billWiseEnabled !== false &&
      this.activeCompany()?.features.maintainAccounts !== false,
  );
  readonly isCostCentresEnabled = computed<boolean>(() =>
    Boolean(this.activeCompany()?.features.costCentresEnabled),
  );
  readonly isGstEnabled = computed<boolean>(
    () => this.activeCompany()?.features.gstEnabled !== false,
  );
  readonly isTdsEnabled = computed<boolean>(
    () => this.activeCompany()?.features.tdsEnabled !== false,
  );
  readonly isTcsEnabled = computed<boolean>(() =>
    Boolean(this.activeCompany()?.features.tcsEnabled),
  );
  readonly isPayrollEnabled = computed<boolean>(() =>
    Boolean(this.activeCompany()?.features.payrollEnabled),
  );
  readonly isPriceLevelsEnabled = computed<boolean>(() =>
    Boolean(this.activeCompany()?.features.multiplePriceLevelsEnabled),
  );
  readonly isBatchesEnabled = computed<boolean>(
    () => this.activeCompany()?.features.batchesEnabled !== false,
  );
  readonly isDiscountColEnabled = computed<boolean>(
    () => this.activeCompany()?.features.useDiscountColumnInInvoices !== false,
  );
  readonly isActualBilledQtySplit = computed<boolean>(
    () => this.activeCompany()?.features.useSeparateActualAndBilledQty !== false,
  );

  readonly quarantined = computed<boolean>(
    () =>
      this.systemStatusSignal() === 'QUARANTINED' || this.problemFeedback.quarantineDetected(),
  );

  setSystemStatus(state: InstallationSecurityState, reason: string | null = null): void {
    this.systemStatusSignal.set(state);
    this.quarantineReasonSignal.set(reason);
    this.statusLoadedSignal.set(true);
    if (state !== 'QUARANTINED') {
      this.problemFeedback.quarantineDetected.set(false);
    }
  }

  setHasUnsavedDraft(dirty: boolean): void {
    this.hasUnsavedDraftSignal.set(dirty);
  }

  setWorkingDate(isoDate: string): void {
    if (/^\d{4}-\d{2}-\d{2}$/.test(isoDate)) {
      this.workingDateSignal.set(isoDate);
      const activeId = this.activeCompany()?.id;
      if (activeId) {
        writeSessionValue(SESSION_WORKDATE_PREFIX + activeId, isoDate);
      }
    }
  }

  setPeriodRange(fromDate: string, toDate: string): void {
    if (fromDate > toDate) {
      throw new Error('Period "From" date must be on or before "To" date.');
    }
    this.periodFromSignal.set(fromDate);
    this.periodToSignal.set(toDate);
    const activeId = this.activeCompany()?.id;
    if (activeId) {
      writeSessionValue(SESSION_PERIOD_PREFIX + activeId, `${fromDate}..${toDate}`);
    }
  }

  setCompanies(companies: readonly CompanySummary[], selectId?: string): void {
    const normalized = companies.map((c) => ({
      ...c,
      features: normalizeCompanyFeatures(c.features),
    }));
    this.openCompaniesSignal.set(normalized);
    this.companiesLoadedSignal.set(true);
    const chosenId = selectId ?? this.activeCompanyIdSignal() ?? normalized[0]?.id ?? null;
    if (chosenId) {
      this.selectActiveCompany(chosenId, true);
    }
  }

  /**
   * UX-002: Switching company must never silently redirect an unsaved transaction into another company.
   */
  selectActiveCompany(companyId: string, forceDiscardDraft = false): boolean {
    if (
      this.hasUnsavedDraftSignal() &&
      !forceDiscardDraft &&
      this.activeCompanyIdSignal() !== null &&
      this.activeCompanyIdSignal() !== companyId
    ) {
      return false;
    }

    const found = this.openCompaniesSignal().find((c) => c.id === companyId);
    if (!found) {
      return false;
    }

    this.activeCompanyIdSignal.set(found.id);
    writeSessionValue(SESSION_ACTIVE_COMPANY_KEY, found.id);
    const fyStart = found.financialYearFrom || '2025-04-01';
    const startYear = Number.parseInt(fyStart.slice(0, 4), 10);
    const fyEnd = Number.isNaN(startYear) ? '2026-03-31' : `${startYear + 1}-03-31`;

    const savedPeriod = readSessionValue(SESSION_PERIOD_PREFIX + found.id);
    const savedParts = savedPeriod ? savedPeriod.split('..') : [];
    if (
      savedParts.length === 2 &&
      /^\d{4}-\d{2}-\d{2}$/.test(savedParts[0]) &&
      /^\d{4}-\d{2}-\d{2}$/.test(savedParts[1]) &&
      savedParts[0] <= savedParts[1]
    ) {
      this.periodFromSignal.set(savedParts[0]);
      this.periodToSignal.set(savedParts[1]);
    } else {
      this.periodFromSignal.set(fyStart);
      this.periodToSignal.set(fyEnd);
    }

    const savedWorkDate = readSessionValue(SESSION_WORKDATE_PREFIX + found.id);
    if (savedWorkDate && /^\d{4}-\d{2}-\d{2}$/.test(savedWorkDate)) {
      this.workingDateSignal.set(savedWorkDate);
    } else {
      this.workingDateSignal.set(found.lastEntryDate ?? fyStart);
    }

    const savedF12 = readSessionValue(SESSION_F12_PREFIX + found.id);
    if (savedF12) {
      try {
        const parsed = JSON.parse(savedF12) as Partial<CompanyF12Config>;
        this.f12ConfigSignal.set({ ...DEFAULT_COMPANY_F12_CONFIG, ...parsed });
      } catch {
        this.f12ConfigSignal.set(DEFAULT_COMPANY_F12_CONFIG);
      }
    } else {
      this.f12ConfigSignal.set(DEFAULT_COMPANY_F12_CONFIG);
    }

    if (forceDiscardDraft) {
      this.hasUnsavedDraftSignal.set(false);
    }
    return true;
  }

  shutCompany(companyId: string): void {
    const remaining = this.openCompaniesSignal().filter((c) => c.id !== companyId);
    this.openCompaniesSignal.set(remaining);
    if (this.activeCompanyIdSignal() === companyId) {
      this.activeCompanyIdSignal.set(remaining[0]?.id ?? null);
    }
  }

  async fetchSystemStatus(): Promise<SystemStatusResponse> {
    try {
      const status = await firstValueFrom(
        this.http.get<SystemStatusResponse>(this.runtimeConfig.url('/system/status'), {
          withCredentials: true,
        }),
      );
      this.systemStatusSignal.set(status.securityState);
      this.fenceEpochSignal.set(status.fenceEpoch ?? 1);
      this.quarantineReasonSignal.set(status.quarantineReason ?? null);
      this.statusLoadedSignal.set(true);
      return status;
    } catch {
      this.statusLoadedSignal.set(true);
      return {
        securityState: this.systemStatusSignal(),
        fenceEpoch: this.fenceEpochSignal(),
        version: this.runtimeConfig.config().releaseVersion,
      };
    }
  }

  async loadCompanies(): Promise<readonly CompanySummary[]> {
    try {
      const companies = await firstValueFrom(
        this.http.get<readonly CompanySummary[]>(this.runtimeConfig.url('/companies'), {
          withCredentials: true,
        }),
      );
      this.setCompanies(companies);
      return companies;
    } catch {
      return this.openCompaniesSignal();
    } finally {
      this.companiesLoadedSignal.set(true);
    }
  }

  async createCompany(payload: CreateOrUpdateCompanyPayload): Promise<CompanySummary> {
    const created = await firstValueFrom(
      this.http.post<CompanySummary>(this.runtimeConfig.url('/companies'), payload, {
        withCredentials: true,
      }),
    );
    const updated = [...this.openCompaniesSignal(), created];
    this.setCompanies(updated, created.id);
    return created;
  }

  async updateCompany(
    companyId: string,
    payload: CreateOrUpdateCompanyPayload,
  ): Promise<CompanySummary> {
    const existing = this.openCompaniesSignal().find((c) => c.id === companyId);
    const versionNo = payload.versionNo ?? existing?.versionNo;
    const headers: Record<string, string> =
      versionNo !== undefined ? { 'If-Match': `"v${versionNo}"` } : {};
    const body: CreateOrUpdateCompanyPayload =
      versionNo !== undefined ? { ...payload, versionNo } : payload;

    const updatedCompany = await firstValueFrom(
      this.http.put<CompanySummary>(
        this.runtimeConfig.url(`/companies/${encodeURIComponent(companyId)}`),
        body,
        { headers, withCredentials: true },
      ),
    );
    const normalizedUpdated: CompanySummary = {
      ...updatedCompany,
      features: normalizeCompanyFeatures(updatedCompany.features),
    };
    const updatedList = this.openCompaniesSignal().map((c) =>
      c.id === companyId ? normalizedUpdated : c,
    );
    this.openCompaniesSignal.set(updatedList);
    return normalizedUpdated;
  }

  async loadF12Config(companyId?: string): Promise<CompanyF12Config> {
    const cid = companyId ?? this.activeCompany()?.id;
    if (!cid) {
      return this.f12ConfigSignal();
    }
    try {
      const remote = await firstValueFrom(
        this.http.get<Partial<CompanyF12Config>>(
          this.runtimeConfig.url(`/companies/${encodeURIComponent(cid)}/f12-config`),
          { withCredentials: true },
        ),
      );
      const merged: CompanyF12Config = { ...DEFAULT_COMPANY_F12_CONFIG, ...remote };
      this.f12ConfigSignal.set(merged);
      writeSessionValue(SESSION_F12_PREFIX + cid, JSON.stringify(merged));
      return merged;
    } catch {
      return this.f12ConfigSignal();
    }
  }

  async updateF12Config(patch: Partial<CompanyF12Config>): Promise<CompanyF12Config> {
    const merged: CompanyF12Config = { ...this.f12ConfigSignal(), ...patch };
    this.f12ConfigSignal.set(merged);
    const cid = this.activeCompany()?.id;
    if (cid) {
      writeSessionValue(SESSION_F12_PREFIX + cid, JSON.stringify(merged));
      if (isBrowserStorageAvailable()) {
        try {
          const remote = await firstValueFrom(
            this.http.put<Partial<CompanyF12Config>>(
              this.runtimeConfig.url(`/companies/${encodeURIComponent(cid)}/f12-config`),
              merged,
              { withCredentials: true },
            ),
          );
          const synced: CompanyF12Config = { ...DEFAULT_COMPANY_F12_CONFIG, ...merged, ...remote };
          this.f12ConfigSignal.set(synced);
          writeSessionValue(SESSION_F12_PREFIX + cid, JSON.stringify(synced));
          return synced;
        } catch {
          return merged;
        }
      }
    }
    return merged;
  }

  async patchFeatureToggle(patch: Partial<CompanyFeaturesConfig>): Promise<CompanySummary | null> {
    const active = this.activeCompany();
    if (!active) {
      return null;
    }
    const mergedFeatures = normalizeCompanyFeatures({
      ...active.features,
      ...patch,
      mcaEditLogEnforced: active.features.mcaEditLogEnforced
        ? true
        : (patch.mcaEditLogEnforced ?? active.features.mcaEditLogEnforced),
    });
    const optimistic: CompanySummary = {
      ...active,
      features: mergedFeatures,
    };
    this.openCompaniesSignal.set(
      this.openCompaniesSignal().map((c) => (c.id === active.id ? optimistic : c)),
    );

    if (isBrowserStorageAvailable()) {
      try {
        return await this.updateCompany(active.id, {
          code: active.code,
          legalName: active.legalName,
          mailingName: active.mailingName,
          countryCode: active.countryCode,
          stateCode: active.stateCode,
          pinCode: active.pinCode,
          baseCurrencyCode: active.baseCurrencyCode,
          currencySymbol: active.currencySymbol,
          currencyScale: active.currencyScale,
          financialYearFrom: active.financialYearFrom,
          booksBeginningFrom: active.booksBeginningFrom,
          versionNo: active.versionNo,
          features: mergedFeatures,
        });
      } catch {
        return optimistic;
      }
    }
    return optimistic;
  }

  async enableCompanyFeature(
    featureKey: keyof CompanyFeaturesConfig,
  ): Promise<CompanySummary | null> {
    const patch: Record<string, unknown> = { [featureKey]: true };
    if (
      featureKey === 'multiplePriceLevelsEnabled' ||
      featureKey === 'batchesEnabled' ||
      featureKey === 'expiryDateForBatchesEnabled' ||
      featureKey === 'jobOrderProcessingEnabled' ||
      featureKey === 'costTrackingEnabled' ||
      featureKey === 'useDiscountColumnInInvoices' ||
      featureKey === 'useSeparateActualAndBilledQty'
    ) {
      patch['maintainInventory'] = true;
    }
    if (featureKey === 'expiryDateForBatchesEnabled') {
      patch['batchesEnabled'] = true;
    }
    if (featureKey === 'jobCostingEnabled') {
      patch['costCentresEnabled'] = true;
    }
    if (featureKey === 'payrollStatutoryEnabled') {
      patch['payrollEnabled'] = true;
    }
    if (featureKey === 'billWiseEnabled' || featureKey === 'costCentresEnabled' || featureKey === 'interestCalculationEnabled') {
      patch['maintainAccounts'] = true;
    }
    return this.patchFeatureToggle(patch as Partial<CompanyFeaturesConfig>);
  }

  async loadFiscalPeriods(companyId: string): Promise<readonly FiscalPeriodSummary[]> {
    try {
      const list = await firstValueFrom(
        this.http.get<readonly FiscalPeriodSummary[]>(
          this.runtimeConfig.url(`/companies/${encodeURIComponent(companyId)}/periods`),
          { withCredentials: true },
        ),
      );
      this.periodsSignal.set(list);
      return list;
    } catch {
      return this.periodsSignal();
    }
  }

  async loadFiscalYears(companyId: string): Promise<readonly FiscalYearSummary[]> {
    return firstValueFrom(
      this.http.get<readonly FiscalYearSummary[]>(
        this.runtimeConfig.url(`/companies/${encodeURIComponent(companyId)}/fiscal-years`),
        { withCredentials: true },
      ),
    );
  }

  async createFiscalYear(
    companyId: string,
    payload: CreateFiscalYearPayload,
  ): Promise<FiscalYearSummary> {
    const created = await firstValueFrom(
      this.http.post<FiscalYearSummary>(
        this.runtimeConfig.url(`/companies/${encodeURIComponent(companyId)}/fiscal-years`),
        payload,
        { withCredentials: true },
      ),
    );
    await this.loadFiscalPeriods(companyId);
    return created;
  }

  async changePeriodState(
    companyId: string,
    periodId: string,
    state: FiscalPeriodState,
    reason?: string,
  ): Promise<FiscalPeriodSummary> {
    const existing = this.periodsSignal().find((p) => p.id === periodId);
    const headers: Record<string, string> =
      existing?.versionNo !== undefined ? { 'If-Match': `"v${existing.versionNo}"` } : {};

    const updated = await firstValueFrom(
      this.http.patch<FiscalPeriodSummary>(
        this.runtimeConfig.url(
          `/companies/${encodeURIComponent(companyId)}/periods/${encodeURIComponent(periodId)}`,
        ),
        { state, reason: reason ?? `Period transitioned to ${state}` },
        { headers, withCredentials: true },
      ),
    );
    this.periodsSignal.set(
      this.periodsSignal().map((p) => (p.id === periodId ? updated : p)),
    );
    return updated;
  }
}
