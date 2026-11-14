import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RuntimeConfigService } from '../../core/http/runtime-config.service';
import {
  AccountGroup,
  BackendCoaTreeNodeDto,
  BankDetailsDto,
  CoaTreeNode,
  CoaTreeResponse,
  CostCategory,
  CostCentre,
  CreateForexRatePayload,
  CreateOrUpdateAccountGroupPayload,
  CreateOrUpdateCostCategoryPayload,
  CreateOrUpdateCostCentrePayload,
  CreateOrUpdateCurrencyPayload,
  CreateOrUpdateLedgerPayload,
  CreateOrUpdateVoucherTypePayload,
  CurrencyMaster,
  CursorPage,
  ForexRate,
  Ledger,
  LedgerTypeaheadFilter,
  LedgerTypeaheadItem,
  VoucherBaseCategory,
  VoucherTypeMaster,
} from './masters.models';

function unwrapItems<T>(body: CursorPage<T> | readonly T[] | null | undefined): readonly T[] {
  if (!body) {
    return [];
  }
  if (Array.isArray(body)) {
    return body as readonly T[];
  }
  const page = body as CursorPage<T>;
  return Array.isArray(page.items) ? page.items : [];
}

function toCursorPage<T>(body: CursorPage<T> | readonly T[] | null | undefined): CursorPage<T> {
  if (!body) {
    return { items: [], nextCursor: null, hasMore: false };
  }
  if (Array.isArray(body)) {
    return { items: body as readonly T[], nextCursor: null, hasMore: false };
  }
  const page = body as CursorPage<T>;
  return {
    items: Array.isArray(page.items) ? page.items : [],
    nextCursor: page.nextCursor ?? null,
    hasMore: Boolean(page.hasMore),
  };
}

function ifMatchHeader(versionNo?: number): Readonly<Record<string, string>> {
  return versionNo !== undefined ? { 'If-Match': `"v${versionNo}"` } : {};
}

type RawLedgerWire = Partial<Ledger> & {
  readonly id: string;
  readonly companyId?: string;
  readonly name: string;
  readonly panMasked?: string | null;
  readonly bankDetails?: BankDetailsDto | null;
};

function normalizeLedger(raw: RawLedgerWire, fallbackCompanyId = ''): Ledger {
  const bank = raw.bankDetails ?? null;
  return {
    id: raw.id,
    companyId: raw.companyId ?? fallbackCompanyId,
    name: raw.name,
    aliasCode: raw.aliasCode ?? null,
    groupId: raw.groupId ?? null,
    groupName: raw.groupName ?? null,
    openingBalance: raw.openingBalance ?? '0.00',
    openingSide: raw.openingSide ?? 'DR',
    currencyCode: raw.currencyCode ?? 'INR',
    maintainBalancesBillByBill: Boolean(raw.maintainBalancesBillByBill),
    defaultCreditPeriodDays: raw.defaultCreditPeriodDays ?? null,
    checkCreditDaysDuringVoucher: Boolean(raw.checkCreditDaysDuringVoucher),
    creditLimit: raw.creditLimit ?? null,
    inventoryValuesAffected: Boolean(raw.inventoryValuesAffected),
    costCentresApplicable: Boolean(raw.costCentresApplicable),
    interestCalculationEnabled: raw.interestCalculationEnabled,
    taxType: raw.taxType ?? 'NONE',
    dutyHead: raw.dutyHead ?? null,
    gstApplicability: raw.gstApplicability ?? 'APPLICABLE',
    gstSupplyType: raw.gstSupplyType ?? null,
    hsnSacCode: raw.hsnSacCode ?? null,
    gstRatePct: raw.gstRatePct ?? null,
    cessRatePct: raw.cessRatePct ?? null,
    appropriateFor: raw.appropriateFor ?? 'NONE',
    appropriationBasis: raw.appropriationBasis ?? null,
    roundingMethod: raw.roundingMethod ?? 'NONE',
    roundingLimit: raw.roundingLimit ?? null,
    partyRegistrationType: raw.partyRegistrationType ?? 'UNKNOWN',
    gstin: raw.gstin ?? null,
    countryCode: raw.countryCode ?? 'IN',
    stateCode: raw.stateCode ?? null,
    stateName: raw.stateName ?? null,
    pincode: raw.pincode ?? null,
    mailingName: raw.mailingName ?? null,
    mailingAddress: raw.mailingAddress ?? null,
    panPlainMasked: raw.panPlainMasked ?? raw.panMasked ?? null,
    panMasked: raw.panMasked ?? raw.panPlainMasked ?? null,
    msmeRegistrationType: raw.msmeRegistrationType ?? null,
    msmeUdyamNo: raw.msmeUdyamNo ?? null,
    tdsApplicable: Boolean(raw.tdsApplicable),
    tdsNatureCode: raw.tdsNatureCode ?? null,
    tdsDeducteeType: raw.tdsDeducteeType ?? null,
    bankDetails: bank,
    bankAccountHolder: raw.bankAccountHolder ?? bank?.accountHolder ?? null,
    bankAccountMasked: raw.bankAccountMasked ?? bank?.accountNumber ?? null,
    bankIfscCode: raw.bankIfscCode ?? bank?.ifscCode ?? null,
    bankName: raw.bankName ?? bank?.bankName ?? null,
    bankBranch: raw.bankBranch ?? bank?.branch ?? null,
    upiId: raw.upiId ?? bank?.upiId ?? null,
    isSystemLedger: Boolean(raw.isSystemLedger),
    active: raw.active ?? true,
    versionNo: raw.versionNo ?? 1,
  };
}

function buildLedgerRequestWire(
  payload: CreateOrUpdateLedgerPayload,
  versionNo?: number,
): Record<string, unknown> {
  const hasBankField = Boolean(
    payload.bankAccountHolder ||
      payload.bankAccountNumber ||
      payload.bankIfscCode ||
      payload.bankName ||
      payload.bankBranch ||
      payload.upiId,
  );
  const bankDetails: BankDetailsDto | null =
    payload.bankDetails !== undefined
      ? payload.bankDetails
      : hasBankField
        ? {
            accountHolder: payload.bankAccountHolder,
            accountNumber: payload.bankAccountNumber,
            ifscCode: payload.bankIfscCode,
            bankName: payload.bankName,
            branch: payload.bankBranch,
            upiId: payload.upiId,
          }
        : null;

  const v = versionNo ?? payload.versionNo;
  return {
    ...payload,
    bankDetails,
    ...(v !== undefined ? { versionNo: v } : {}),
  };
}

function isBackendMixedNode(node: unknown): node is BackendCoaTreeNodeDto {
  return (
    typeof node === 'object' &&
    node !== null &&
    'nodeType' in node &&
    ((node as BackendCoaTreeNodeDto).nodeType === 'GROUP' ||
      (node as BackendCoaTreeNodeDto).nodeType === 'LEDGER')
  );
}

function mapMixedBackendGroupNode(
  node: BackendCoaTreeNodeDto,
  companyId: string,
  depthLevel: number,
  sortIndex: number,
): CoaTreeNode {
  const childGroups: CoaTreeNode[] = [];
  const childLedgers: Ledger[] = [];

  (node.children ?? []).forEach((child, idx) => {
    if (child.nodeType === 'GROUP') {
      childGroups.push(mapMixedBackendGroupNode(child, companyId, depthLevel + 1, idx));
    } else {
      childLedgers.push(
        normalizeLedger(
          {
            id: child.id,
            companyId,
            name: child.name,
            aliasCode: child.aliasCode,
            groupId: node.id,
            groupName: node.name,
            openingBalance: child.openingBalance,
            openingSide: child.openingSide,
            isSystemLedger: child.isSystem,
            versionNo: child.versionNo,
          },
          companyId,
        ),
      );
    }
  });

  return {
    id: node.id,
    name: node.name,
    aliasCode: node.aliasCode ?? null,
    parentGroupId: node.parentId ?? null,
    nature: node.nature,
    affectsGrossProfit: Boolean(node.affectsGrossProfit),
    behavesLikeSubledger: Boolean(node.behavesLikeSubledger),
    isSystemGroup: Boolean(node.isSystem),
    depthLevel,
    sortOrder: sortIndex,
    versionNo: node.versionNo ?? 1,
    openingDebit: node.openingDebit ?? '0.00',
    openingCredit: node.openingCredit ?? '0.00',
    children: childGroups,
    ledgers: childLedgers,
  };
}

type RawCostCentreWire = Partial<CostCentre> & {
  readonly id: string;
  readonly name: string;
  readonly categoryId?: string;
  readonly categoryName?: string;
};

function normalizeCostCentre(raw: RawCostCentreWire, fallbackCompanyId = ''): CostCentre {
  const catId = raw.costCategoryId ?? raw.categoryId ?? '';
  const catName = raw.costCategoryName ?? raw.categoryName;
  return {
    id: raw.id,
    companyId: raw.companyId ?? fallbackCompanyId,
    costCategoryId: catId,
    categoryId: catId,
    costCategoryName: catName,
    categoryName: catName,
    parentCostCentreId: raw.parentCostCentreId ?? null,
    parentCostCentreName: raw.parentCostCentreName ?? null,
    name: raw.name,
    aliasCode: raw.aliasCode ?? null,
    depthLevel: raw.depthLevel ?? 0,
    active: raw.active ?? true,
    versionNo: raw.versionNo ?? 1,
  };
}

type RawVoucherTypeWire = Partial<VoucherTypeMaster> & {
  readonly id: string;
  readonly name: string;
  readonly category?: VoucherBaseCategory;
  readonly isSystemType?: boolean;
  readonly preventDuplicates?: boolean;
  readonly allowZeroValued?: boolean;
  readonly isOptionalByDefault?: boolean;
};

function normalizeVoucherType(
  raw: RawVoucherTypeWire,
  fallbackCompanyId = '',
): VoucherTypeMaster {
  const baseCategory = (raw.baseCategory ?? raw.category ?? 'JOURNAL') as VoucherBaseCategory;
  const preventDuplicateNumbers = Boolean(raw.preventDuplicateNumbers ?? raw.preventDuplicates);
  const allowZeroValuedTransactions = Boolean(
    raw.allowZeroValuedTransactions ?? raw.allowZeroValued,
  );
  const makeOptionalByDefault = Boolean(raw.makeOptionalByDefault ?? raw.isOptionalByDefault);
  const isSystemVoucherType = Boolean(raw.isSystemVoucherType ?? raw.isSystemType);

  return {
    id: raw.id,
    companyId: raw.companyId ?? fallbackCompanyId,
    name: raw.name,
    abbreviation: raw.abbreviation ?? '',
    baseCategory,
    category: baseCategory,
    parentVoucherTypeId: raw.parentVoucherTypeId ?? null,
    parentVoucherType: raw.parentVoucherType ?? null,
    postsAccountingEntries: raw.postsAccountingEntries ?? true,
    movesInventory: Boolean(raw.movesInventory),
    defaultEntryMode: raw.defaultEntryMode ?? 'DOUBLE_ENTRY',
    numberingMethod: raw.numberingMethod ?? 'AUTOMATIC_RETAIN_NUMBERS',
    preventDuplicateNumbers,
    preventDuplicates: preventDuplicateNumbers,
    useEffectiveDates: Boolean(raw.useEffectiveDates),
    allowZeroValuedTransactions,
    allowZeroValued: allowZeroValuedTransactions,
    makeOptionalByDefault,
    isOptionalByDefault: makeOptionalByDefault,
    allowNarrationPerLedger: Boolean(raw.allowNarrationPerLedger),
    printAfterSave: Boolean(raw.printAfterSave),
    isManufacturingJournal: Boolean(raw.isManufacturingJournal),
    approvalPolicy: raw.approvalPolicy ?? 'SINGLE_STEP',
    shortcutKey: raw.shortcutKey ?? null,
    isSystemVoucherType,
    isSystemType: isSystemVoucherType,
    active: raw.active ?? true,
    versionNo: raw.versionNo ?? 1,
  };
}

function buildVoucherTypeRequestWire(
  payload: CreateOrUpdateVoucherTypePayload,
  versionNo?: number,
): Record<string, unknown> {
  const v = versionNo ?? payload.versionNo;
  const category = payload.category ?? payload.baseCategory;
  const preventDuplicates = payload.preventDuplicates ?? payload.preventDuplicateNumbers;
  const allowZeroValued = payload.allowZeroValued ?? payload.allowZeroValuedTransactions;
  const isOptionalByDefault = payload.isOptionalByDefault ?? payload.makeOptionalByDefault;
  return {
    ...payload,
    category,
    preventDuplicates,
    allowZeroValued,
    isOptionalByDefault,
    ...(v !== undefined ? { versionNo: v } : {}),
  };
}

@Injectable({ providedIn: 'root' })
export class AccountingMastersService {
  private readonly http = inject(HttpClient);
  private readonly runtimeConfig = inject(RuntimeConfigService);

  private companyBase(companyId: string, suffix: string): string {
    return this.runtimeConfig.url(`/companies/${encodeURIComponent(companyId)}${suffix}`);
  }

  // ---------------------------------------------------------------------------
  // Chart of Accounts Tree
  // ---------------------------------------------------------------------------

  async getChartOfAccountsTree(companyId: string): Promise<CoaTreeResponse> {
    const raw = await firstValueFrom(
      this.http.get<
        | CoaTreeResponse
        | readonly CoaTreeNode[]
        | {
            readonly nodes?: readonly (BackendCoaTreeNodeDto | CoaTreeNode)[];
            readonly roots?: readonly CoaTreeNode[];
            readonly totalOpeningDebit?: string;
            readonly totalOpeningCredit?: string;
            readonly openingDifference?: string;
          }
      >(this.companyBase(companyId, '/chart-of-accounts/tree'), { withCredentials: true }),
    );

    if (Array.isArray(raw)) {
      return { companyId, groups: raw as readonly CoaTreeNode[] };
    }

    const obj = raw as CoaTreeResponse & {
      readonly nodes?: readonly (BackendCoaTreeNodeDto | CoaTreeNode)[];
      readonly roots?: readonly CoaTreeNode[];
    };

    if (Array.isArray(obj.nodes) && obj.nodes.some(isBackendMixedNode)) {
      const rootGroups: CoaTreeNode[] = [];
      const rootSystemLedgers: Ledger[] = [...(obj.systemLedgers ?? [])];

      (obj.nodes as readonly BackendCoaTreeNodeDto[]).forEach((node, idx) => {
        if (node.nodeType === 'GROUP') {
          rootGroups.push(mapMixedBackendGroupNode(node, companyId, 0, idx));
        } else {
          rootSystemLedgers.push(
            normalizeLedger(
              {
                id: node.id,
                companyId,
                name: node.name,
                aliasCode: node.aliasCode,
                groupId: null,
                groupName: 'Primary',
                openingBalance: node.openingBalance,
                openingSide: node.openingSide,
                isSystemLedger: node.isSystem,
                versionNo: node.versionNo,
              },
              companyId,
            ),
          );
        }
      });

      return {
        companyId: obj.companyId ?? companyId,
        groups: rootGroups,
        systemLedgers: rootSystemLedgers,
        totalOpeningDebit: obj.totalOpeningDebit,
        totalOpeningCredit: obj.totalOpeningCredit,
        openingDifference: obj.openingDifference,
      };
    }

    return {
      companyId: obj.companyId ?? companyId,
      groups: obj.groups ?? (obj.nodes as readonly CoaTreeNode[] | undefined) ?? obj.roots ?? [],
      systemLedgers: (obj.systemLedgers ?? []).map((led) => normalizeLedger(led, companyId)),
      totalOpeningDebit: obj.totalOpeningDebit,
      totalOpeningCredit: obj.totalOpeningCredit,
      openingDifference: obj.openingDifference,
    };
  }

  // ---------------------------------------------------------------------------
  // Account Groups (28 Predefined + Custom)
  // ---------------------------------------------------------------------------

  async listAccountGroups(companyId: string): Promise<readonly AccountGroup[]> {
    const body = await firstValueFrom(
      this.http.get<CursorPage<AccountGroup> | readonly AccountGroup[]>(
        this.companyBase(companyId, '/account-groups'),
        { withCredentials: true },
      ),
    );
    return unwrapItems(body);
  }

  async getAccountGroup(companyId: string, groupId: string): Promise<AccountGroup> {
    return firstValueFrom(
      this.http.get<AccountGroup>(
        this.companyBase(companyId, `/account-groups/${encodeURIComponent(groupId)}`),
        { withCredentials: true },
      ),
    );
  }

  async createAccountGroup(
    companyId: string,
    payload: CreateOrUpdateAccountGroupPayload,
  ): Promise<AccountGroup> {
    return firstValueFrom(
      this.http.post<AccountGroup>(
        this.companyBase(companyId, '/account-groups'),
        payload,
        { withCredentials: true },
      ),
    );
  }

  async updateAccountGroup(
    companyId: string,
    groupId: string,
    payload: CreateOrUpdateAccountGroupPayload,
    versionNo?: number,
  ): Promise<AccountGroup> {
    const v = versionNo ?? payload.versionNo;
    const body = v !== undefined ? { ...payload, versionNo: v } : payload;
    return firstValueFrom(
      this.http.put<AccountGroup>(
        this.companyBase(companyId, `/account-groups/${encodeURIComponent(groupId)}`),
        body,
        { headers: ifMatchHeader(v), withCredentials: true },
      ),
    );
  }

  async deleteAccountGroup(
    companyId: string,
    groupId: string,
    versionNo?: number,
  ): Promise<void> {
    await firstValueFrom(
      this.http.delete<void>(
        this.companyBase(companyId, `/account-groups/${encodeURIComponent(groupId)}`),
        { headers: ifMatchHeader(versionNo), withCredentials: true },
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Ledgers & Scoped Typeahead
  // ---------------------------------------------------------------------------

  async listLedgersPage(
    companyId: string,
    options?: {
      readonly cursor?: string;
      readonly limit?: number;
      readonly q?: string;
      readonly groupId?: string;
      readonly taxType?: string;
    },
  ): Promise<CursorPage<Ledger>> {
    let params = new HttpParams();
    if (options?.cursor) {
      params = params.set('cursor', options.cursor);
    }
    if (options?.limit !== undefined) {
      params = params.set('limit', String(options.limit));
    }
    if (options?.q) {
      params = params.set('q', options.q);
    }
    if (options?.groupId) {
      params = params.set('groupId', options.groupId);
    }
    if (options?.taxType) {
      params = params.set('taxType', options.taxType);
    }
    const body = await firstValueFrom(
      this.http.get<CursorPage<RawLedgerWire> | readonly RawLedgerWire[]>(
        this.companyBase(companyId, '/ledgers'),
        { params, withCredentials: true },
      ),
    );
    const page = toCursorPage(body);
    return {
      items: page.items.map((item) => normalizeLedger(item, companyId)),
      nextCursor: page.nextCursor,
      hasMore: page.hasMore,
    };
  }

  async listLedgers(companyId: string): Promise<readonly Ledger[]> {
    const page = await this.listLedgersPage(companyId, { limit: 500 });
    return page.items;
  }

  async searchLedgerTypeahead(
    companyId: string,
    q = '',
    filter: LedgerTypeaheadFilter = 'ALL',
  ): Promise<readonly LedgerTypeaheadItem[]> {
    const params = new HttpParams().set('q', q).set('filter', filter);
    const body = await firstValueFrom(
      this.http.get<CursorPage<LedgerTypeaheadItem> | readonly LedgerTypeaheadItem[]>(
        this.companyBase(companyId, '/ledgers/typeahead'),
        { params, withCredentials: true },
      ),
    );
    return unwrapItems(body);
  }

  async getLedger(companyId: string, ledgerId: string): Promise<Ledger> {
    const raw = await firstValueFrom(
      this.http.get<RawLedgerWire>(
        this.companyBase(companyId, `/ledgers/${encodeURIComponent(ledgerId)}`),
        { withCredentials: true },
      ),
    );
    return normalizeLedger(raw, companyId);
  }

  async createLedger(
    companyId: string,
    payload: CreateOrUpdateLedgerPayload,
  ): Promise<Ledger> {
    const raw = await firstValueFrom(
      this.http.post<RawLedgerWire>(
        this.companyBase(companyId, '/ledgers'),
        buildLedgerRequestWire(payload),
        { withCredentials: true },
      ),
    );
    return normalizeLedger(raw, companyId);
  }

  async updateLedger(
    companyId: string,
    ledgerId: string,
    payload: CreateOrUpdateLedgerPayload,
    versionNo?: number,
  ): Promise<Ledger> {
    const v = versionNo ?? payload.versionNo;
    const raw = await firstValueFrom(
      this.http.put<RawLedgerWire>(
        this.companyBase(companyId, `/ledgers/${encodeURIComponent(ledgerId)}`),
        buildLedgerRequestWire(payload, v),
        { headers: ifMatchHeader(v), withCredentials: true },
      ),
    );
    return normalizeLedger(raw, companyId);
  }

  async deleteLedger(companyId: string, ledgerId: string, versionNo?: number): Promise<void> {
    await firstValueFrom(
      this.http.delete<void>(
        this.companyBase(companyId, `/ledgers/${encodeURIComponent(ledgerId)}`),
        { headers: ifMatchHeader(versionNo), withCredentials: true },
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Currencies & Date-Effective Forex Rates
  // ---------------------------------------------------------------------------

  async listCurrencies(companyId: string): Promise<readonly CurrencyMaster[]> {
    const body = await firstValueFrom(
      this.http.get<CursorPage<CurrencyMaster> | readonly CurrencyMaster[]>(
        this.companyBase(companyId, '/currencies'),
        { withCredentials: true },
      ),
    );
    return unwrapItems(body);
  }

  async getCurrency(companyId: string, currencyId: string): Promise<CurrencyMaster> {
    return firstValueFrom(
      this.http.get<CurrencyMaster>(
        this.companyBase(companyId, `/currencies/${encodeURIComponent(currencyId)}`),
        { withCredentials: true },
      ),
    );
  }

  async createCurrency(
    companyId: string,
    payload: CreateOrUpdateCurrencyPayload,
  ): Promise<CurrencyMaster> {
    return firstValueFrom(
      this.http.post<CurrencyMaster>(
        this.companyBase(companyId, '/currencies'),
        payload,
        { withCredentials: true },
      ),
    );
  }

  async updateCurrency(
    companyId: string,
    currencyId: string,
    payload: CreateOrUpdateCurrencyPayload,
    versionNo?: number,
  ): Promise<CurrencyMaster> {
    const v = versionNo ?? payload.versionNo;
    const body = v !== undefined ? { ...payload, versionNo: v } : payload;
    return firstValueFrom(
      this.http.put<CurrencyMaster>(
        this.companyBase(companyId, `/currencies/${encodeURIComponent(currencyId)}`),
        body,
        { headers: ifMatchHeader(v), withCredentials: true },
      ),
    );
  }

  async listForexRates(companyId: string, currencyId: string): Promise<readonly ForexRate[]> {
    const body = await firstValueFrom(
      this.http.get<CursorPage<ForexRate> | readonly ForexRate[]>(
        this.companyBase(companyId, `/currencies/${encodeURIComponent(currencyId)}/rates`),
        { withCredentials: true },
      ),
    );
    return unwrapItems(body);
  }

  async createForexRate(
    companyId: string,
    currencyId: string,
    payload: CreateForexRatePayload,
  ): Promise<ForexRate> {
    return firstValueFrom(
      this.http.post<ForexRate>(
        this.companyBase(companyId, `/currencies/${encodeURIComponent(currencyId)}/rates`),
        payload,
        { withCredentials: true },
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Cost Categories & Cost Centres
  // ---------------------------------------------------------------------------

  async listCostCategories(companyId: string): Promise<readonly CostCategory[]> {
    const body = await firstValueFrom(
      this.http.get<CursorPage<CostCategory> | readonly CostCategory[]>(
        this.companyBase(companyId, '/cost-categories'),
        { withCredentials: true },
      ),
    );
    return unwrapItems(body);
  }

  async createCostCategory(
    companyId: string,
    payload: CreateOrUpdateCostCategoryPayload,
  ): Promise<CostCategory> {
    return firstValueFrom(
      this.http.post<CostCategory>(
        this.companyBase(companyId, '/cost-categories'),
        payload,
        { withCredentials: true },
      ),
    );
  }

  async updateCostCategory(
    companyId: string,
    categoryId: string,
    payload: CreateOrUpdateCostCategoryPayload,
    versionNo?: number,
  ): Promise<CostCategory> {
    const v = versionNo ?? payload.versionNo;
    const body = v !== undefined ? { ...payload, versionNo: v } : payload;
    return firstValueFrom(
      this.http.put<CostCategory>(
        this.companyBase(companyId, `/cost-categories/${encodeURIComponent(categoryId)}`),
        body,
        { headers: ifMatchHeader(v), withCredentials: true },
      ),
    );
  }

  async listCostCentres(companyId: string, categoryId?: string): Promise<readonly CostCentre[]> {
    let params = new HttpParams();
    if (categoryId) {
      params = params.set('categoryId', categoryId);
    }
    const body = await firstValueFrom(
      this.http.get<CursorPage<RawCostCentreWire> | readonly RawCostCentreWire[]>(
        this.companyBase(companyId, '/cost-centres'),
        { params, withCredentials: true },
      ),
    );
    return unwrapItems(body).map((item) => normalizeCostCentre(item, companyId));
  }

  async getCostCentre(companyId: string, centreId: string): Promise<CostCentre> {
    const raw = await firstValueFrom(
      this.http.get<RawCostCentreWire>(
        this.companyBase(companyId, `/cost-centres/${encodeURIComponent(centreId)}`),
        { withCredentials: true },
      ),
    );
    return normalizeCostCentre(raw, companyId);
  }

  async createCostCentre(
    companyId: string,
    payload: CreateOrUpdateCostCentrePayload,
  ): Promise<CostCentre> {
    const categoryId = payload.categoryId ?? payload.costCategoryId;
    const raw = await firstValueFrom(
      this.http.post<RawCostCentreWire>(
        this.companyBase(companyId, '/cost-centres'),
        { ...payload, categoryId, costCategoryId: payload.costCategoryId },
        { withCredentials: true },
      ),
    );
    return normalizeCostCentre(raw, companyId);
  }

  async updateCostCentre(
    companyId: string,
    centreId: string,
    payload: CreateOrUpdateCostCentrePayload,
    versionNo?: number,
  ): Promise<CostCentre> {
    const v = versionNo ?? payload.versionNo;
    const categoryId = payload.categoryId ?? payload.costCategoryId;
    const body = {
      ...payload,
      categoryId,
      costCategoryId: payload.costCategoryId,
      ...(v !== undefined ? { versionNo: v } : {}),
    };
    const raw = await firstValueFrom(
      this.http.put<RawCostCentreWire>(
        this.companyBase(companyId, `/cost-centres/${encodeURIComponent(centreId)}`),
        body,
        { headers: ifMatchHeader(v), withCredentials: true },
      ),
    );
    return normalizeCostCentre(raw, companyId);
  }

  async deleteCostCentre(companyId: string, centreId: string, versionNo?: number): Promise<void> {
    await firstValueFrom(
      this.http.delete<void>(
        this.companyBase(companyId, `/cost-centres/${encodeURIComponent(centreId)}`),
        { headers: ifMatchHeader(versionNo), withCredentials: true },
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Voucher Types (24 Predefined + Custom Sub-Types)
  // ---------------------------------------------------------------------------

  async listVoucherTypes(companyId: string): Promise<readonly VoucherTypeMaster[]> {
    const body = await firstValueFrom(
      this.http.get<CursorPage<RawVoucherTypeWire> | readonly RawVoucherTypeWire[]>(
        this.companyBase(companyId, '/voucher-types'),
        { withCredentials: true },
      ),
    );
    return unwrapItems(body).map((item) => normalizeVoucherType(item, companyId));
  }

  async getVoucherType(companyId: string, voucherTypeId: string): Promise<VoucherTypeMaster> {
    const raw = await firstValueFrom(
      this.http.get<RawVoucherTypeWire>(
        this.companyBase(companyId, `/voucher-types/${encodeURIComponent(voucherTypeId)}`),
        { withCredentials: true },
      ),
    );
    return normalizeVoucherType(raw, companyId);
  }

  async createVoucherType(
    companyId: string,
    payload: CreateOrUpdateVoucherTypePayload,
  ): Promise<VoucherTypeMaster> {
    const raw = await firstValueFrom(
      this.http.post<RawVoucherTypeWire>(
        this.companyBase(companyId, '/voucher-types'),
        buildVoucherTypeRequestWire(payload),
        { withCredentials: true },
      ),
    );
    return normalizeVoucherType(raw, companyId);
  }

  async updateVoucherType(
    companyId: string,
    voucherTypeId: string,
    payload: CreateOrUpdateVoucherTypePayload,
    versionNo?: number,
  ): Promise<VoucherTypeMaster> {
    const v = versionNo ?? payload.versionNo;
    const raw = await firstValueFrom(
      this.http.put<RawVoucherTypeWire>(
        this.companyBase(companyId, `/voucher-types/${encodeURIComponent(voucherTypeId)}`),
        buildVoucherTypeRequestWire(payload, v),
        { headers: ifMatchHeader(v), withCredentials: true },
      ),
    );
    return normalizeVoucherType(raw, companyId);
  }
}
