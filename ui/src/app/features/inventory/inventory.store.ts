import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { TypeaheadOption } from '../../shared/components/typeahead-select/typeahead-select.component';
import { CompanyContextStore } from '../../state/company-context.store';
import {
  BomMaster,
  GodownMaster,
  PriceListMaster,
  StockCategoryMaster,
  StockGroupMaster,
  StockItemMaster,
  UomMaster,
  UpsertBomRequest,
  UpsertGodownRequest,
  UpsertPriceListRequest,
  UpsertStockCategoryRequest,
  UpsertStockGroupRequest,
  UpsertStockItemRequest,
  UpsertUomRequest,
} from './inventory.models';
import { InventoryService } from './inventory.service';

export interface SeedInventoryMastersInput {
  readonly uoms?: readonly UomMaster[];
  readonly godowns?: readonly GodownMaster[];
  readonly stockGroups?: readonly StockGroupMaster[];
  readonly stockCategories?: readonly StockCategoryMaster[];
  readonly stockItems?: readonly StockItemMaster[];
  readonly priceLists?: readonly PriceListMaster[];
  readonly boms?: readonly BomMaster[];
}

@Injectable({ providedIn: 'root' })
export class InventoryStore {
  private readonly api = inject(InventoryService);
  private readonly companyContext = inject(CompanyContextStore);

  private readonly uomsSignal = signal<readonly UomMaster[]>([]);
  private readonly godownsSignal = signal<readonly GodownMaster[]>([]);
  private readonly stockGroupsSignal = signal<readonly StockGroupMaster[]>([]);
  private readonly stockCategoriesSignal = signal<readonly StockCategoryMaster[]>([]);
  private readonly stockItemsSignal = signal<readonly StockItemMaster[]>([]);
  private readonly priceListsSignal = signal<readonly PriceListMaster[]>([]);
  private readonly bomsSignal = signal<readonly BomMaster[]>([]);
  private readonly loadingSignal = signal<boolean>(false);
  private readonly errorSignal = signal<string | null>(null);

  private cachedCompanyId: string | null = null;

  readonly uoms = this.uomsSignal.asReadonly();
  readonly godowns = this.godownsSignal.asReadonly();
  readonly stockGroups = this.stockGroupsSignal.asReadonly();
  readonly stockCategories = this.stockCategoriesSignal.asReadonly();
  readonly stockItems = this.stockItemsSignal.asReadonly();
  readonly priceLists = this.priceListsSignal.asReadonly();
  readonly boms = this.bomsSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();

  readonly uomOptions = computed<readonly TypeaheadOption[]>(() =>
    this.uomsSignal().map((u) => ({
      id: u.id,
      label: u.formalName ? `${u.symbol} — ${u.formalName}` : u.symbol,
      secondaryLabel: u.gstUqcCode ?? u.uomType,
      codeBadge: u.symbol,
    })),
  );

  readonly simpleUomOptions = computed<readonly TypeaheadOption[]>(() =>
    this.uomsSignal()
      .filter((u) => u.uomType === 'SIMPLE')
      .map((u) => ({
        id: u.id,
        label: u.formalName ? `${u.symbol} — ${u.formalName}` : u.symbol,
        secondaryLabel: u.gstUqcCode ?? 'SIMPLE',
        codeBadge: u.symbol,
      })),
  );

  readonly godownOptions = computed<readonly TypeaheadOption[]>(() =>
    this.godownsSignal().map((g) => ({
      id: g.id,
      label: g.name,
      secondaryLabel: g.parentGodownName ?? (g.isSystemGodown ? 'System Default' : undefined),
      codeBadge: g.aliasCode ?? (g.isSystemGodown ? 'MAIN' : undefined),
    })),
  );

  readonly parentGodownOptions = computed<readonly TypeaheadOption[]>(() => [
    { id: '__PRIMARY__', label: 'Primary (Root Location)' },
    ...this.godownOptions(),
  ]);

  readonly stockGroupOptions = computed<readonly TypeaheadOption[]>(() =>
    this.stockGroupsSignal().map((sg) => ({
      id: sg.id,
      label: sg.name,
      secondaryLabel: sg.parentStockGroupName ?? (sg.gstRatePct ? `GST ${sg.gstRatePct}%` : undefined),
      codeBadge: sg.hsnSacCode ?? sg.aliasCode ?? undefined,
    })),
  );

  readonly parentStockGroupOptions = computed<readonly TypeaheadOption[]>(() => [
    { id: '__PRIMARY__', label: 'Primary' },
    ...this.stockGroupOptions(),
  ]);

  readonly stockCategoryOptions = computed<readonly TypeaheadOption[]>(() =>
    this.stockCategoriesSignal().map((sc) => ({
      id: sc.id,
      label: sc.name,
      secondaryLabel: sc.parentStockCategoryName ?? undefined,
      codeBadge: sc.aliasCode ?? undefined,
    })),
  );

  readonly parentStockCategoryOptions = computed<readonly TypeaheadOption[]>(() => [
    { id: '__PRIMARY__', label: 'Primary' },
    ...this.stockCategoryOptions(),
  ]);

  readonly stockItemOptions = computed<readonly TypeaheadOption[]>(() =>
    this.stockItemsSignal().map((item) => {
      const codeParts: string[] = [];
      if (item.partNo) codeParts.push(item.partNo);
      if (item.aliasCode) codeParts.push(item.aliasCode);
      codeParts.push(item.baseUomSymbol);
      return {
        id: item.id,
        label: item.name,
        secondaryLabel: [item.stockGroupName, item.baseUomSymbol].filter(Boolean).join(' · '),
        codeBadge: codeParts.join(' · '),
      };
    }),
  );

  constructor() {
    effect(() => {
      const nextCid = this.companyContext.activeCompany()?.id ?? null;
      if (this.cachedCompanyId !== null && nextCid !== this.cachedCompanyId) {
        this.cachedCompanyId = nextCid;
        this.uomsSignal.set([]);
        this.godownsSignal.set([]);
        this.stockGroupsSignal.set([]);
        this.stockCategoriesSignal.set([]);
        this.stockItemsSignal.set([]);
        this.priceListsSignal.set([]);
        this.bomsSignal.set([]);
      }
    });
  }

  setInventoryMasters(seed: SeedInventoryMastersInput, companyId?: string): void {
    this.cachedCompanyId = companyId ?? this.companyContext.activeCompany()?.id ?? 'local';
    if (seed.uoms) this.uomsSignal.set(seed.uoms);
    if (seed.godowns) this.godownsSignal.set(seed.godowns);
    if (seed.stockGroups) this.stockGroupsSignal.set(seed.stockGroups);
    if (seed.stockCategories) this.stockCategoriesSignal.set(seed.stockCategories);
    if (seed.stockItems) this.stockItemsSignal.set(seed.stockItems);
    if (seed.priceLists) this.priceListsSignal.set(seed.priceLists);
    if (seed.boms) this.bomsSignal.set(seed.boms);
  }

  async ensureLoaded(companyId?: string): Promise<void> {
    const cid = companyId ?? this.companyContext.activeCompany()?.id;
    if (!cid) return;
    if (
      this.cachedCompanyId === cid &&
      (this.uomsSignal().length > 0 ||
        this.godownsSignal().length > 0 ||
        this.stockItemsSignal().length > 0)
    ) {
      return;
    }
    await this.loadAllInventoryMasters(cid);
  }

  async loadAllInventoryMasters(companyId?: string): Promise<void> {
    const cid = companyId ?? this.companyContext.activeCompany()?.id;
    if (!cid) return;
    this.cachedCompanyId = cid;
    this.loadingSignal.set(true);
    this.errorSignal.set(null);
    try {
      const [uoms, godowns, stockGroups, stockCategories, stockItems, priceLists, boms] =
        await Promise.all([
          this.api.listUoms(cid).catch(() => [] as readonly UomMaster[]),
          this.api.listGodowns(cid).catch(() => [] as readonly GodownMaster[]),
          this.api.listStockGroups(cid).catch(() => [] as readonly StockGroupMaster[]),
          this.api.listStockCategories(cid).catch(() => [] as readonly StockCategoryMaster[]),
          this.api.listStockItems(cid).catch(() => [] as readonly StockItemMaster[]),
          this.api.listPriceLists(cid).catch(() => [] as readonly PriceListMaster[]),
          this.api.listBoms(cid).catch(() => [] as readonly BomMaster[]),
        ]);
      this.uomsSignal.set(uoms);
      this.godownsSignal.set(godowns);
      this.stockGroupsSignal.set(stockGroups);
      this.stockCategoriesSignal.set(stockCategories);
      this.stockItemsSignal.set(stockItems);
      this.priceListsSignal.set(priceLists);
      this.bomsSignal.set(boms);
    } catch (err: unknown) {
      this.errorSignal.set(
        err instanceof Error ? err.message : 'Unable to load inventory masters.',
      );
    } finally {
      this.loadingSignal.set(false);
    }
  }

  private requireCompanyId(): string {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) {
      throw new Error('Select an active company (F3) first.');
    }
    this.cachedCompanyId = cid;
    return cid;
  }

  // --- UOM Mutations ---

  async createUom(payload: UpsertUomRequest): Promise<UomMaster> {
    const created = await this.api.createUom(this.requireCompanyId(), payload);
    this.uomsSignal.set([...this.uomsSignal(), created]);
    return created;
  }

  async updateUom(uomId: string, payload: UpsertUomRequest, versionNo?: number): Promise<UomMaster> {
    const updated = await this.api.updateUom(this.requireCompanyId(), uomId, payload, versionNo);
    this.uomsSignal.set(this.uomsSignal().map((u) => (u.id === uomId ? updated : u)));
    return updated;
  }

  async deleteUom(uomId: string): Promise<void> {
    await this.api.deleteUom(this.requireCompanyId(), uomId);
    this.uomsSignal.set(this.uomsSignal().filter((u) => u.id !== uomId));
  }

  // --- Godown Mutations ---

  async createGodown(payload: UpsertGodownRequest): Promise<GodownMaster> {
    const created = await this.api.createGodown(this.requireCompanyId(), payload);
    this.godownsSignal.set([...this.godownsSignal(), created]);
    return created;
  }

  async updateGodown(
    godownId: string,
    payload: UpsertGodownRequest,
    versionNo?: number,
  ): Promise<GodownMaster> {
    const updated = await this.api.updateGodown(
      this.requireCompanyId(),
      godownId,
      payload,
      versionNo,
    );
    this.godownsSignal.set(this.godownsSignal().map((g) => (g.id === godownId ? updated : g)));
    return updated;
  }

  async deleteGodown(godownId: string): Promise<void> {
    await this.api.deleteGodown(this.requireCompanyId(), godownId);
    this.godownsSignal.set(this.godownsSignal().filter((g) => g.id !== godownId));
  }

  // --- Stock Group & Category Mutations ---

  async createStockGroup(payload: UpsertStockGroupRequest): Promise<StockGroupMaster> {
    const created = await this.api.createStockGroup(this.requireCompanyId(), payload);
    this.stockGroupsSignal.set([...this.stockGroupsSignal(), created]);
    return created;
  }

  async updateStockGroup(
    stockGroupId: string,
    payload: UpsertStockGroupRequest,
    versionNo?: number,
  ): Promise<StockGroupMaster> {
    const updated = await this.api.updateStockGroup(
      this.requireCompanyId(),
      stockGroupId,
      payload,
      versionNo,
    );
    this.stockGroupsSignal.set(
      this.stockGroupsSignal().map((g) => (g.id === stockGroupId ? updated : g)),
    );
    return updated;
  }

  async deleteStockGroup(stockGroupId: string): Promise<void> {
    await this.api.deleteStockGroup(this.requireCompanyId(), stockGroupId);
    this.stockGroupsSignal.set(this.stockGroupsSignal().filter((g) => g.id !== stockGroupId));
  }

  async createStockCategory(payload: UpsertStockCategoryRequest): Promise<StockCategoryMaster> {
    const created = await this.api.createStockCategory(this.requireCompanyId(), payload);
    this.stockCategoriesSignal.set([...this.stockCategoriesSignal(), created]);
    return created;
  }

  async updateStockCategory(
    stockCategoryId: string,
    payload: UpsertStockCategoryRequest,
    versionNo?: number,
  ): Promise<StockCategoryMaster> {
    const updated = await this.api.updateStockCategory(
      this.requireCompanyId(),
      stockCategoryId,
      payload,
      versionNo,
    );
    this.stockCategoriesSignal.set(
      this.stockCategoriesSignal().map((c) => (c.id === stockCategoryId ? updated : c)),
    );
    return updated;
  }

  async deleteStockCategory(stockCategoryId: string): Promise<void> {
    await this.api.deleteStockCategory(this.requireCompanyId(), stockCategoryId);
    this.stockCategoriesSignal.set(
      this.stockCategoriesSignal().filter((c) => c.id !== stockCategoryId),
    );
  }

  // --- Stock Item Mutations ---

  async createStockItem(payload: UpsertStockItemRequest): Promise<StockItemMaster> {
    const created = await this.api.createStockItem(this.requireCompanyId(), payload);
    this.stockItemsSignal.set([...this.stockItemsSignal(), created]);
    return created;
  }

  async updateStockItem(
    stockItemId: string,
    payload: UpsertStockItemRequest,
    versionNo?: number,
  ): Promise<StockItemMaster> {
    const updated = await this.api.updateStockItem(
      this.requireCompanyId(),
      stockItemId,
      payload,
      versionNo,
    );
    this.stockItemsSignal.set(
      this.stockItemsSignal().map((i) => (i.id === stockItemId ? updated : i)),
    );
    return updated;
  }

  async deleteStockItem(stockItemId: string): Promise<void> {
    await this.api.deleteStockItem(this.requireCompanyId(), stockItemId);
    this.stockItemsSignal.set(this.stockItemsSignal().filter((i) => i.id !== stockItemId));
  }

  // --- Price List & BOM Mutations ---

  async createPriceList(payload: UpsertPriceListRequest): Promise<PriceListMaster> {
    const created = await this.api.createPriceList(this.requireCompanyId(), payload);
    this.priceListsSignal.set([...this.priceListsSignal(), created]);
    return created;
  }

  async updatePriceList(
    priceListId: string,
    payload: UpsertPriceListRequest,
    versionNo?: number,
  ): Promise<PriceListMaster> {
    const updated = await this.api.updatePriceList(
      this.requireCompanyId(),
      priceListId,
      payload,
      versionNo,
    );
    this.priceListsSignal.set(
      this.priceListsSignal().map((pl) => (pl.id === priceListId ? updated : pl)),
    );
    return updated;
  }

  async createBom(payload: UpsertBomRequest): Promise<BomMaster> {
    const created = await this.api.createBom(this.requireCompanyId(), payload);
    this.bomsSignal.set([...this.bomsSignal(), created]);
    return created;
  }

  async updateBom(bomId: string, payload: UpsertBomRequest, versionNo?: number): Promise<BomMaster> {
    const updated = await this.api.updateBom(this.requireCompanyId(), bomId, payload, versionNo);
    this.bomsSignal.set(this.bomsSignal().map((b) => (b.id === bomId ? updated : b)));
    return updated;
  }
}
