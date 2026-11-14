import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { ExactDecimal } from '../../core/money/exact-decimal';
import { TypeaheadOption } from '../../shared/components/typeahead-select/typeahead-select.component';
import { CompanyContextStore } from '../../state/company-context.store';
import { AccountingMastersService } from './accounting-masters.service';
import {
  AccountGroup,
  BalanceSide,
  CoaTreeNode,
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
  ForexRate,
  Ledger,
  LedgerTypeaheadFilter,
  LedgerTypeaheadItem,
  VoucherTypeMaster,
} from './masters.models';

export type OpeningDifferenceSide = 'DR' | 'CR' | 'BALANCED';

/**
 * Computes bottom-up `ExactDecimal` opening Dr / Cr subtotals for every node in a CoA tree.
 */
export function recomputeCoaTreeBalances(
  nodes: readonly CoaTreeNode[],
  scale: number,
): readonly CoaTreeNode[] {
  return nodes.map((node) => {
    const computedChildren = recomputeCoaTreeBalances(node.children ?? [], scale);
    let dr = ExactDecimal.zero(scale);
    let cr = ExactDecimal.zero(scale);

    for (const ledger of node.ledgers ?? []) {
      if (ledger.active === false) {
        continue;
      }
      const amt = ExactDecimal.tryParse(ledger.openingBalance ?? '0', scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
      if (ledger.openingSide === 'CR') {
        cr = cr.add(amt);
      } else {
        dr = dr.add(amt);
      }
    }

    for (const child of computedChildren) {
      const childDr = ExactDecimal.tryParse(child.openingDebit ?? '0', scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
      const childCr = ExactDecimal.tryParse(child.openingCredit ?? '0', scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
      dr = dr.add(childDr);
      cr = cr.add(childCr);
    }

    return {
      ...node,
      openingDebit: dr.toWireString(scale),
      openingCredit: cr.toWireString(scale),
      children: computedChildren,
      ledgers: node.ledgers ?? [],
    };
  });
}

/**
 * Assembles a hierarchical `CoaTreeNode[]` from flat `AccountGroup[]` and `Ledger[]` arrays
 * and rolls up `ExactDecimal` opening balances.
 */
export function buildCoaTreeFromGroupsAndLedgers(
  groups: readonly AccountGroup[],
  ledgers: readonly Ledger[],
  scale: number,
): readonly CoaTreeNode[] {
  const sortedGroups = [...groups].sort((a, b) => {
    const orderDiff = (a.sortOrder ?? 0) - (b.sortOrder ?? 0);
    if (orderDiff !== 0) return orderDiff;
    return a.name.localeCompare(b.name);
  });

  const ledgersByGroup = new Map<string, Ledger[]>();
  for (const ledger of ledgers) {
    if (!ledger.groupId) continue;
    const bucket = ledgersByGroup.get(ledger.groupId) ?? [];
    bucket.push(ledger);
    ledgersByGroup.set(ledger.groupId, bucket);
  }
  for (const bucket of ledgersByGroup.values()) {
    bucket.sort((a, b) => a.name.localeCompare(b.name));
  }

  const childrenByParent = new Map<string | null, AccountGroup[]>();
  const groupIdSet = new Set(sortedGroups.map((g) => g.id));

  for (const grp of sortedGroups) {
    const parentKey = grp.parentGroupId && groupIdSet.has(grp.parentGroupId) ? grp.parentGroupId : null;
    const list = childrenByParent.get(parentKey) ?? [];
    list.push(grp);
    childrenByParent.set(parentKey, list);
  }

  const buildSubtree = (parentId: string | null, depth: number): readonly CoaTreeNode[] => {
    const directGroups = childrenByParent.get(parentId) ?? [];
    return directGroups.map((grp) => ({
      id: grp.id,
      name: grp.name,
      aliasCode: grp.aliasCode,
      parentGroupId: grp.parentGroupId,
      nature: grp.nature,
      affectsGrossProfit: grp.affectsGrossProfit,
      behavesLikeSubledger: grp.behavesLikeSubledger,
      isSystemGroup: Boolean(grp.isSystemGroup),
      depthLevel: grp.depthLevel ?? depth,
      sortOrder: grp.sortOrder ?? 0,
      versionNo: grp.versionNo ?? 1,
      openingDebit: ExactDecimal.zero(scale).toWireString(scale),
      openingCredit: ExactDecimal.zero(scale).toWireString(scale),
      children: buildSubtree(grp.id, depth + 1),
      ledgers: ledgersByGroup.get(grp.id) ?? [],
    }));
  };

  return recomputeCoaTreeBalances(buildSubtree(null, 0), scale);
}

function flattenGroupsFromTree(nodes: readonly CoaTreeNode[], companyId: string): AccountGroup[] {
  const acc: AccountGroup[] = [];
  const visit = (list: readonly CoaTreeNode[], depth: number): void => {
    for (const n of list) {
      acc.push({
        id: n.id,
        companyId,
        name: n.name,
        aliasCode: n.aliasCode ?? null,
        parentGroupId: n.parentGroupId ?? null,
        nature: n.nature,
        affectsGrossProfit: Boolean(n.affectsGrossProfit),
        behavesLikeSubledger: Boolean(n.behavesLikeSubledger),
        netDebitCreditForReporting: false,
        usedForCalculation: false,
        methodToAllocatePurchase: 'NOT_APPLICABLE',
        scheduleIiiLineCode: null,
        defaultGstRatePct: null,
        defaultHsnSacCode: null,
        depthLevel: n.depthLevel ?? depth,
        isSystemGroup: Boolean(n.isSystemGroup),
        sortOrder: n.sortOrder ?? 0,
        versionNo: n.versionNo ?? 1,
      });
      if (n.children && n.children.length > 0) {
        visit(n.children, depth + 1);
      }
    }
  };
  visit(nodes, 0);
  return acc;
}

function flattenLedgersFromTree(nodes: readonly CoaTreeNode[]): Ledger[] {
  const acc: Ledger[] = [];
  const seen = new Set<string>();
  const visit = (list: readonly CoaTreeNode[]): void => {
    for (const n of list) {
      for (const l of n.ledgers ?? []) {
        if (!seen.has(l.id)) {
          seen.add(l.id);
          acc.push({ ...l, groupId: l.groupId || n.id, groupName: l.groupName ?? n.name });
        }
      }
      if (n.children && n.children.length > 0) {
        visit(n.children);
      }
    }
  };
  visit(nodes);
  return acc;
}

@Injectable({ providedIn: 'root' })
export class ChartOfAccountsStore {
  private readonly api = inject(AccountingMastersService);
  private readonly companyContext = inject(CompanyContextStore);

  private cachedCompanyId: string | null = null;
  private readonly typeaheadCache = new Map<string, readonly LedgerTypeaheadItem[]>();

  private readonly loadingSignal = signal<boolean>(false);
  private readonly errorSignal = signal<string | null>(null);

  private readonly groupsSignal = signal<readonly AccountGroup[]>([]);
  private readonly ledgersSignal = signal<readonly Ledger[]>([]);
  private readonly rawTreeSignal = signal<readonly CoaTreeNode[] | null>(null);
  private readonly systemRootLedgersSignal = signal<readonly Ledger[]>([]);

  private readonly currenciesSignal = signal<readonly CurrencyMaster[]>([]);
  private readonly forexRatesMapSignal = signal<ReadonlyMap<string, readonly ForexRate[]>>(new Map());
  private readonly costCategoriesSignal = signal<readonly CostCategory[]>([]);
  private readonly costCentresSignal = signal<readonly CostCentre[]>([]);
  private readonly voucherTypesSignal = signal<readonly VoucherTypeMaster[]>([]);

  readonly loading = this.loadingSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();

  readonly groups = this.groupsSignal.asReadonly();
  readonly ledgers = this.ledgersSignal.asReadonly();
  readonly systemRootLedgers = this.systemRootLedgersSignal.asReadonly();
  readonly currencies = this.currenciesSignal.asReadonly();
  readonly forexRatesByCurrency = this.forexRatesMapSignal.asReadonly();
  readonly costCategories = this.costCategoriesSignal.asReadonly();
  readonly costCentres = this.costCentresSignal.asReadonly();
  readonly voucherTypes = this.voucherTypesSignal.asReadonly();

  readonly coaTree = computed<readonly CoaTreeNode[]>(() => {
    const scale = this.companyContext.currencyScale();
    const explicitTree = this.rawTreeSignal();
    if (explicitTree && explicitTree.length > 0) {
      return recomputeCoaTreeBalances(explicitTree, scale);
    }
    return buildCoaTreeFromGroupsAndLedgers(this.groupsSignal(), this.ledgersSignal(), scale);
  });

  private readonly openingTotalsComputed = computed(() => {
    const scale = this.companyContext.currencyScale();
    let totalDr = ExactDecimal.zero(scale);
    let totalCr = ExactDecimal.zero(scale);

    const allLedgers = [...this.ledgersSignal(), ...this.systemRootLedgersSignal()];
    const seen = new Set<string>();

    for (const ledger of allLedgers) {
      if (seen.has(ledger.id) || ledger.active === false) {
        continue;
      }
      seen.add(ledger.id);
      const amt =
        ExactDecimal.tryParse(ledger.openingBalance ?? '0', scale, 'HALF_UP') ??
        ExactDecimal.zero(scale);
      if (ledger.openingSide === 'CR') {
        totalCr = totalCr.add(amt);
      } else {
        totalDr = totalDr.add(amt);
      }
    }

    const cmp = totalDr.compare(totalCr);
    let diff = ExactDecimal.zero(scale);
    let diffSide: OpeningDifferenceSide = 'BALANCED';
    if (cmp > 0) {
      diff = totalDr.sub(totalCr);
      diffSide = 'CR';
    } else if (cmp < 0) {
      diff = totalCr.sub(totalDr);
      diffSide = 'DR';
    }

    return {
      totalOpeningDebit: totalDr.toWireString(scale),
      totalOpeningCredit: totalCr.toWireString(scale),
      openingDifference: diff.toWireString(scale),
      openingDifferenceSide: diffSide,
    };
  });

  readonly totalOpeningDebit = computed(() => this.openingTotalsComputed().totalOpeningDebit);
  readonly totalOpeningCredit = computed(() => this.openingTotalsComputed().totalOpeningCredit);
  readonly openingDifference = computed(() => this.openingTotalsComputed().openingDifference);
  readonly openingDifferenceSide = computed(
    () => this.openingTotalsComputed().openingDifferenceSide,
  );

  readonly groupTypeaheadOptions = computed<readonly TypeaheadOption[]>(() =>
    this.groupsSignal().map((g) => ({
      id: g.id,
      label: g.name,
      secondaryLabel: g.parentGroupName ?? (g.parentGroupId ? g.nature : `Primary (${g.nature})`),
      codeBadge: g.aliasCode ?? undefined,
    })),
  );

  readonly parentGroupTypeaheadOptions = computed<readonly TypeaheadOption[]>(() => [
    { id: '__PRIMARY__', label: 'Primary', secondaryLabel: 'Top-Level Group' },
    ...this.groupTypeaheadOptions(),
  ]);

  readonly costCategoryTypeaheadOptions = computed<readonly TypeaheadOption[]>(() =>
    this.costCategoriesSignal().map((c) => ({
      id: c.id,
      label: c.name,
      secondaryLabel: c.isPrimaryCategory ? 'Primary Cost Category' : 'Cost Category',
    })),
  );

  readonly costCentreTypeaheadOptions = computed<readonly TypeaheadOption[]>(() => [
    { id: '__PRIMARY__', label: 'Primary', secondaryLabel: 'Root Cost Centre' },
    ...this.costCentresSignal().map((cc) => ({
      id: cc.id,
      label: cc.name,
      secondaryLabel: cc.costCategoryName ?? 'Cost Centre',
      codeBadge: cc.aliasCode ?? undefined,
    })),
  ]);

  readonly currencyTypeaheadOptions = computed<readonly TypeaheadOption[]>(() =>
    this.currenciesSignal().map((cur) => ({
      id: cur.id,
      label: `${cur.isoCode} — ${cur.formalName}`,
      secondaryLabel: cur.symbol,
      codeBadge: cur.isoCode,
    })),
  );

  constructor() {
    this.cachedCompanyId = this.companyContext.activeCompany()?.id ?? null;
    effect(() => {
      const activeId = this.companyContext.activeCompany()?.id ?? null;
      if (activeId !== this.cachedCompanyId) {
        this.cachedCompanyId = activeId;
        this.invalidateCache();
      }
    });
  }

  invalidateCache(): void {
    this.typeaheadCache.clear();
    this.groupsSignal.set([]);
    this.ledgersSignal.set([]);
    this.rawTreeSignal.set(null);
    this.systemRootLedgersSignal.set([]);
    this.currenciesSignal.set([]);
    this.forexRatesMapSignal.set(new Map());
    this.costCategoriesSignal.set([]);
    this.costCentresSignal.set([]);
    this.voucherTypesSignal.set([]);
    this.errorSignal.set(null);
  }

  /**
   * Direct synchronous state hydration helper (used by deterministic specs and Go To snapshots).
   */
  setGroupsAndLedgers(
    groups: readonly AccountGroup[],
    ledgers: readonly Ledger[],
    systemRootLedgers: readonly Ledger[] = [],
  ): void {
    this.cachedCompanyId =
      groups[0]?.companyId ?? this.companyContext.activeCompany()?.id ?? this.cachedCompanyId;
    this.rawTreeSignal.set(null);
    this.groupsSignal.set(groups);
    this.ledgersSignal.set(ledgers);
    this.systemRootLedgersSignal.set(systemRootLedgers);
    this.typeaheadCache.clear();
  }

  setCoaTree(
    nodes: readonly CoaTreeNode[],
    companyId = 'local',
    systemRootLedgers: readonly Ledger[] = [],
  ): void {
    this.cachedCompanyId = this.companyContext.activeCompany()?.id ?? companyId;
    this.rawTreeSignal.set(nodes);
    this.groupsSignal.set(flattenGroupsFromTree(nodes, companyId));
    this.ledgersSignal.set(flattenLedgersFromTree(nodes));
    this.systemRootLedgersSignal.set(systemRootLedgers);
    this.typeaheadCache.clear();
  }

  async loadChartOfAccounts(companyId?: string): Promise<readonly CoaTreeNode[]> {
    const cid = companyId ?? this.companyContext.activeCompany()?.id;
    if (!cid) {
      return [];
    }
    this.cachedCompanyId = cid;
    this.loadingSignal.set(true);
    this.errorSignal.set(null);
    try {
      const [treeResp, groupsList, ledgersList] = await Promise.all([
        this.api.getChartOfAccountsTree(cid).catch(() => null),
        this.api.listAccountGroups(cid).catch(() => null),
        this.api.listLedgers(cid).catch(() => null),
      ]);

      if (groupsList && groupsList.length > 0) {
        this.groupsSignal.set(groupsList);
      } else if (treeResp && treeResp.groups.length > 0) {
        this.groupsSignal.set(flattenGroupsFromTree(treeResp.groups, cid));
      }

      if (ledgersList && ledgersList.length > 0) {
        this.ledgersSignal.set(ledgersList);
      } else if (treeResp && treeResp.groups.length > 0) {
        this.ledgersSignal.set(flattenLedgersFromTree(treeResp.groups));
      }

      if (treeResp?.systemLedgers) {
        this.systemRootLedgersSignal.set(treeResp.systemLedgers);
      }

      if (treeResp && treeResp.groups.length > 0 && (!groupsList || groupsList.length === 0)) {
        this.rawTreeSignal.set(treeResp.groups);
      } else {
        this.rawTreeSignal.set(null);
      }

      return this.coaTree();
    } catch (err: unknown) {
      this.errorSignal.set(
        err instanceof Error ? err.message : 'Failed to load Chart of Accounts.',
      );
      return this.coaTree();
    } finally {
      this.loadingSignal.set(false);
    }
  }

  async ensureAccountingMastersLoaded(companyId?: string): Promise<void> {
    const cid = companyId ?? this.companyContext.activeCompany()?.id;
    if (!cid) return;
    if (this.groupsSignal().length === 0) {
      await this.loadChartOfAccounts(cid);
    }
  }

  async loadCurrencies(companyId?: string): Promise<readonly CurrencyMaster[]> {
    const cid = companyId ?? this.companyContext.activeCompany()?.id;
    if (!cid) return [];
    const list = await this.api.listCurrencies(cid);
    this.currenciesSignal.set(list);
    return list;
  }

  async loadForexRates(currencyId: string, companyId?: string): Promise<readonly ForexRate[]> {
    const cid = companyId ?? this.companyContext.activeCompany()?.id;
    if (!cid) return [];
    const rates = await this.api.listForexRates(cid, currencyId);
    const next = new Map(this.forexRatesMapSignal());
    next.set(currencyId, rates);
    this.forexRatesMapSignal.set(next);
    return rates;
  }

  async loadCostCentresAndCategories(companyId?: string): Promise<void> {
    const cid = companyId ?? this.companyContext.activeCompany()?.id;
    if (!cid) return;
    const [categories, centres] = await Promise.all([
      this.api.listCostCategories(cid),
      this.api.listCostCentres(cid),
    ]);
    this.costCategoriesSignal.set(categories);
    this.costCentresSignal.set(centres);
  }

  async loadVoucherTypes(companyId?: string): Promise<readonly VoucherTypeMaster[]> {
    const cid = companyId ?? this.companyContext.activeCompany()?.id;
    if (!cid) return [];
    const list = await this.api.listVoucherTypes(cid);
    this.voucherTypesSignal.set(list);
    return list;
  }

  async queryLedgerTypeahead(
    q = '',
    filter: LedgerTypeaheadFilter = 'ALL',
    companyId?: string,
  ): Promise<readonly LedgerTypeaheadItem[]> {
    const cid = companyId ?? this.companyContext.activeCompany()?.id;
    if (!cid) return [];
    const cacheKey = `${cid}::${filter}::${q.trim().toLowerCase()}`;
    const cached = this.typeaheadCache.get(cacheKey);
    if (cached) {
      return cached;
    }
    const items = await this.api.searchLedgerTypeahead(cid, q, filter);
    this.typeaheadCache.set(cacheKey, items);
    return items;
  }

  // ---------------------------------------------------------------------------
  // Mutations (Invalidate Typeahead & Synchronize Reactive Tree)
  // ---------------------------------------------------------------------------

  async createGroup(
    payload: CreateOrUpdateAccountGroupPayload,
    companyId?: string,
  ): Promise<AccountGroup> {
    const cid = this.requireCompanyId(companyId);
    const created = await this.api.createAccountGroup(cid, payload);
    this.rawTreeSignal.set(null);
    this.groupsSignal.set([...this.groupsSignal(), created]);
    this.typeaheadCache.clear();
    return created;
  }

  async updateGroup(
    groupId: string,
    payload: CreateOrUpdateAccountGroupPayload,
    versionNo?: number,
    companyId?: string,
  ): Promise<AccountGroup> {
    const cid = this.requireCompanyId(companyId);
    const existing = this.groupsSignal().find((g) => g.id === groupId);
    const updated = await this.api.updateAccountGroup(
      cid,
      groupId,
      payload,
      versionNo ?? existing?.versionNo,
    );
    this.rawTreeSignal.set(null);
    this.groupsSignal.set(this.groupsSignal().map((g) => (g.id === groupId ? updated : g)));
    this.typeaheadCache.clear();
    return updated;
  }

  async deleteGroup(groupId: string, versionNo?: number, companyId?: string): Promise<void> {
    const cid = this.requireCompanyId(companyId);
    const existing = this.groupsSignal().find((g) => g.id === groupId);
    if (existing?.isSystemGroup) {
      throw new Error('Predefined system groups cannot be deleted.');
    }
    await this.api.deleteAccountGroup(cid, groupId, versionNo ?? existing?.versionNo);
    this.rawTreeSignal.set(null);
    this.groupsSignal.set(this.groupsSignal().filter((g) => g.id !== groupId));
    this.typeaheadCache.clear();
  }

  async createLedger(
    payload: CreateOrUpdateLedgerPayload,
    companyId?: string,
  ): Promise<Ledger> {
    const cid = this.requireCompanyId(companyId);
    const created = await this.api.createLedger(cid, payload);
    this.rawTreeSignal.set(null);
    this.ledgersSignal.set([...this.ledgersSignal(), created]);
    this.typeaheadCache.clear();
    return created;
  }

  async updateLedger(
    ledgerId: string,
    payload: CreateOrUpdateLedgerPayload,
    versionNo?: number,
    companyId?: string,
  ): Promise<Ledger> {
    const cid = this.requireCompanyId(companyId);
    const existing = this.ledgersSignal().find((l) => l.id === ledgerId);
    const updated = await this.api.updateLedger(
      cid,
      ledgerId,
      payload,
      versionNo ?? existing?.versionNo,
    );
    this.rawTreeSignal.set(null);
    this.ledgersSignal.set(this.ledgersSignal().map((l) => (l.id === ledgerId ? updated : l)));
    this.typeaheadCache.clear();
    return updated;
  }

  async deleteLedger(ledgerId: string, versionNo?: number, companyId?: string): Promise<void> {
    const cid = this.requireCompanyId(companyId);
    const existing = this.ledgersSignal().find((l) => l.id === ledgerId);
    if (existing?.isSystemLedger) {
      throw new Error('System ledgers (Cash / Profit & Loss A/c) cannot be deleted.');
    }
    await this.api.deleteLedger(cid, ledgerId, versionNo ?? existing?.versionNo);
    this.rawTreeSignal.set(null);
    this.ledgersSignal.set(this.ledgersSignal().filter((l) => l.id !== ledgerId));
    this.typeaheadCache.clear();
  }

  async createCurrency(
    payload: CreateOrUpdateCurrencyPayload,
    companyId?: string,
  ): Promise<CurrencyMaster> {
    const cid = this.requireCompanyId(companyId);
    const created = await this.api.createCurrency(cid, payload);
    this.currenciesSignal.set([...this.currenciesSignal(), created]);
    return created;
  }

  async updateCurrency(
    currencyId: string,
    payload: CreateOrUpdateCurrencyPayload,
    versionNo?: number,
    companyId?: string,
  ): Promise<CurrencyMaster> {
    const cid = this.requireCompanyId(companyId);
    const existing = this.currenciesSignal().find((c) => c.id === currencyId);
    const updated = await this.api.updateCurrency(
      cid,
      currencyId,
      payload,
      versionNo ?? existing?.versionNo,
    );
    this.currenciesSignal.set(
      this.currenciesSignal().map((c) => (c.id === currencyId ? updated : c)),
    );
    return updated;
  }

  async addForexRate(
    currencyId: string,
    payload: CreateForexRatePayload,
    companyId?: string,
  ): Promise<ForexRate> {
    const cid = this.requireCompanyId(companyId);
    const created = await this.api.createForexRate(cid, currencyId, payload);
    const existing = this.forexRatesMapSignal().get(currencyId) ?? [];
    const next = new Map(this.forexRatesMapSignal());
    next.set(currencyId, [created, ...existing]);
    this.forexRatesMapSignal.set(next);
    return created;
  }

  async createCostCategory(
    payload: CreateOrUpdateCostCategoryPayload,
    companyId?: string,
  ): Promise<CostCategory> {
    const cid = this.requireCompanyId(companyId);
    const created = await this.api.createCostCategory(cid, payload);
    this.costCategoriesSignal.set([...this.costCategoriesSignal(), created]);
    return created;
  }

  async updateCostCategory(
    categoryId: string,
    payload: CreateOrUpdateCostCategoryPayload,
    versionNo?: number,
    companyId?: string,
  ): Promise<CostCategory> {
    const cid = this.requireCompanyId(companyId);
    const existing = this.costCategoriesSignal().find((c) => c.id === categoryId);
    const updated = await this.api.updateCostCategory(
      cid,
      categoryId,
      payload,
      versionNo ?? existing?.versionNo,
    );
    this.costCategoriesSignal.set(
      this.costCategoriesSignal().map((c) => (c.id === categoryId ? updated : c)),
    );
    return updated;
  }

  async createCostCentre(
    payload: CreateOrUpdateCostCentrePayload,
    companyId?: string,
  ): Promise<CostCentre> {
    const cid = this.requireCompanyId(companyId);
    const created = await this.api.createCostCentre(cid, payload);
    this.costCentresSignal.set([...this.costCentresSignal(), created]);
    return created;
  }

  async updateCostCentre(
    centreId: string,
    payload: CreateOrUpdateCostCentrePayload,
    versionNo?: number,
    companyId?: string,
  ): Promise<CostCentre> {
    const cid = this.requireCompanyId(companyId);
    const existing = this.costCentresSignal().find((c) => c.id === centreId);
    const updated = await this.api.updateCostCentre(
      cid,
      centreId,
      payload,
      versionNo ?? existing?.versionNo,
    );
    this.costCentresSignal.set(
      this.costCentresSignal().map((c) => (c.id === centreId ? updated : c)),
    );
    return updated;
  }

  async deleteCostCentre(
    centreId: string,
    versionNo?: number,
    companyId?: string,
  ): Promise<void> {
    const cid = this.requireCompanyId(companyId);
    const existing = this.costCentresSignal().find((c) => c.id === centreId);
    await this.api.deleteCostCentre(cid, centreId, versionNo ?? existing?.versionNo);
    this.costCentresSignal.set(this.costCentresSignal().filter((c) => c.id !== centreId));
  }

  async createVoucherType(
    payload: CreateOrUpdateVoucherTypePayload,
    companyId?: string,
  ): Promise<VoucherTypeMaster> {
    const cid = this.requireCompanyId(companyId);
    const created = await this.api.createVoucherType(cid, payload);
    this.voucherTypesSignal.set([...this.voucherTypesSignal(), created]);
    return created;
  }

  async updateVoucherType(
    voucherTypeId: string,
    payload: CreateOrUpdateVoucherTypePayload,
    versionNo?: number,
    companyId?: string,
  ): Promise<VoucherTypeMaster> {
    const cid = this.requireCompanyId(companyId);
    const existing = this.voucherTypesSignal().find((v) => v.id === voucherTypeId);
    const updated = await this.api.updateVoucherType(
      cid,
      voucherTypeId,
      payload,
      versionNo ?? existing?.versionNo,
    );
    this.voucherTypesSignal.set(
      this.voucherTypesSignal().map((v) => (v.id === voucherTypeId ? updated : v)),
    );
    return updated;
  }

  formatSignedBalance(drStr: string, crStr: string): {
    readonly amount: string;
    readonly side: BalanceSide | null;
  } {
    const scale = this.companyContext.currencyScale();
    const dr = ExactDecimal.tryParse(drStr, scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
    const cr = ExactDecimal.tryParse(crStr, scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
    const cmp = dr.compare(cr);
    if (cmp === 0) {
      return { amount: ExactDecimal.zero(scale).toWireString(scale), side: null };
    }
    if (cmp > 0) {
      return { amount: dr.sub(cr).toWireString(scale), side: 'DR' };
    }
    return { amount: cr.sub(dr).toWireString(scale), side: 'CR' };
  }

  private requireCompanyId(explicitId?: string): string {
    const cid = explicitId ?? this.companyContext.activeCompany()?.id;
    if (!cid) {
      throw new Error('Select an active company (F3) before creating or altering masters.');
    }
    return cid;
  }
}
