import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RuntimeConfigService } from '../../core/http/runtime-config.service';
import { CursorPage } from '../masters/masters.models';
import {
  BatchAgeingReportDto,
  BomMaster,
  GodownMaster,
  GodownSummaryReportDto,
  ManufacturingPreviewRequest,
  ManufacturingPreviewResponse,
  MovementAnalysisReportDto,
  OpenOrderLineDto,
  OrderVoucherKind,
  PriceListMaster,
  PriceLookupResponseDto,
  StockBatchDto,
  StockCategoryMaster,
  StockCostingMethod,
  StockGroupMaster,
  StockItemMaster,
  StockSummaryReportDto,
  UomMaster,
  UpsertBomRequest,
  UpsertGodownRequest,
  UpsertPriceListRequest,
  UpsertStockCategoryRequest,
  UpsertStockGroupRequest,
  UpsertStockItemRequest,
  UpsertUomRequest,
} from './inventory.models';

function ifMatchHeader(versionNo?: number): Readonly<Record<string, string>> {
  return versionNo !== undefined ? { 'If-Match': `"v${versionNo}"` } : {};
}

function unwrapList<T>(body: CursorPage<T> | readonly T[] | null | undefined): readonly T[] {
  if (!body) {
    return [];
  }
  if (Array.isArray(body)) {
    return body as readonly T[];
  }
  const page = body as CursorPage<T>;
  return Array.isArray(page.items) ? page.items : [];
}

@Injectable({ providedIn: 'root' })
export class InventoryService {
  private readonly http = inject(HttpClient);
  private readonly runtimeConfig = inject(RuntimeConfigService);

  private companyUrl(companyId: string, suffix: string): string {
    return this.runtimeConfig.url(`/companies/${encodeURIComponent(companyId)}${suffix}`);
  }

  // ---------------------------------------------------------------------------
  // 1. Units of Measure (`/uoms`)
  // ---------------------------------------------------------------------------

  async listUoms(companyId: string): Promise<readonly UomMaster[]> {
    const body = await firstValueFrom(
      this.http.get<CursorPage<UomMaster> | readonly UomMaster[]>(
        this.companyUrl(companyId, '/uoms'),
        { withCredentials: true },
      ),
    );
    return unwrapList(body);
  }

  async getUom(companyId: string, uomId: string): Promise<UomMaster> {
    return firstValueFrom(
      this.http.get<UomMaster>(
        this.companyUrl(companyId, `/uoms/${encodeURIComponent(uomId)}`),
        { withCredentials: true },
      ),
    );
  }

  async createUom(companyId: string, payload: UpsertUomRequest): Promise<UomMaster> {
    return firstValueFrom(
      this.http.post<UomMaster>(this.companyUrl(companyId, '/uoms'), payload, {
        withCredentials: true,
      }),
    );
  }

  async updateUom(
    companyId: string,
    uomId: string,
    payload: UpsertUomRequest,
    versionNo?: number,
  ): Promise<UomMaster> {
    return firstValueFrom(
      this.http.put<UomMaster>(
        this.companyUrl(companyId, `/uoms/${encodeURIComponent(uomId)}`),
        payload,
        { headers: ifMatchHeader(versionNo ?? payload.versionNo), withCredentials: true },
      ),
    );
  }

  async deleteUom(companyId: string, uomId: string): Promise<void> {
    await firstValueFrom(
      this.http.delete<void>(this.companyUrl(companyId, `/uoms/${encodeURIComponent(uomId)}`), {
        withCredentials: true,
      }),
    );
  }

  // ---------------------------------------------------------------------------
  // 2. Godowns / Locations (`/godowns`)
  // ---------------------------------------------------------------------------

  async listGodowns(companyId: string): Promise<readonly GodownMaster[]> {
    const body = await firstValueFrom(
      this.http.get<CursorPage<GodownMaster> | readonly GodownMaster[]>(
        this.companyUrl(companyId, '/godowns'),
        { withCredentials: true },
      ),
    );
    return unwrapList(body);
  }

  async getGodown(companyId: string, godownId: string): Promise<GodownMaster> {
    return firstValueFrom(
      this.http.get<GodownMaster>(
        this.companyUrl(companyId, `/godowns/${encodeURIComponent(godownId)}`),
        { withCredentials: true },
      ),
    );
  }

  async createGodown(companyId: string, payload: UpsertGodownRequest): Promise<GodownMaster> {
    return firstValueFrom(
      this.http.post<GodownMaster>(this.companyUrl(companyId, '/godowns'), payload, {
        withCredentials: true,
      }),
    );
  }

  async updateGodown(
    companyId: string,
    godownId: string,
    payload: UpsertGodownRequest,
    versionNo?: number,
  ): Promise<GodownMaster> {
    return firstValueFrom(
      this.http.put<GodownMaster>(
        this.companyUrl(companyId, `/godowns/${encodeURIComponent(godownId)}`),
        payload,
        { headers: ifMatchHeader(versionNo ?? payload.versionNo), withCredentials: true },
      ),
    );
  }

  async deleteGodown(companyId: string, godownId: string): Promise<void> {
    await firstValueFrom(
      this.http.delete<void>(
        this.companyUrl(companyId, `/godowns/${encodeURIComponent(godownId)}`),
        { withCredentials: true },
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // 3. Stock Groups & Stock Categories (`/stock-groups`, `/stock-categories`)
  // ---------------------------------------------------------------------------

  async listStockGroups(companyId: string): Promise<readonly StockGroupMaster[]> {
    const body = await firstValueFrom(
      this.http.get<CursorPage<StockGroupMaster> | readonly StockGroupMaster[]>(
        this.companyUrl(companyId, '/stock-groups'),
        { withCredentials: true },
      ),
    );
    return unwrapList(body);
  }

  async getStockGroup(companyId: string, stockGroupId: string): Promise<StockGroupMaster> {
    return firstValueFrom(
      this.http.get<StockGroupMaster>(
        this.companyUrl(companyId, `/stock-groups/${encodeURIComponent(stockGroupId)}`),
        { withCredentials: true },
      ),
    );
  }

  async createStockGroup(
    companyId: string,
    payload: UpsertStockGroupRequest,
  ): Promise<StockGroupMaster> {
    return firstValueFrom(
      this.http.post<StockGroupMaster>(this.companyUrl(companyId, '/stock-groups'), payload, {
        withCredentials: true,
      }),
    );
  }

  async updateStockGroup(
    companyId: string,
    stockGroupId: string,
    payload: UpsertStockGroupRequest,
    versionNo?: number,
  ): Promise<StockGroupMaster> {
    return firstValueFrom(
      this.http.put<StockGroupMaster>(
        this.companyUrl(companyId, `/stock-groups/${encodeURIComponent(stockGroupId)}`),
        payload,
        { headers: ifMatchHeader(versionNo ?? payload.versionNo), withCredentials: true },
      ),
    );
  }

  async deleteStockGroup(companyId: string, stockGroupId: string): Promise<void> {
    await firstValueFrom(
      this.http.delete<void>(
        this.companyUrl(companyId, `/stock-groups/${encodeURIComponent(stockGroupId)}`),
        { withCredentials: true },
      ),
    );
  }

  async listStockCategories(companyId: string): Promise<readonly StockCategoryMaster[]> {
    const body = await firstValueFrom(
      this.http.get<CursorPage<StockCategoryMaster> | readonly StockCategoryMaster[]>(
        this.companyUrl(companyId, '/stock-categories'),
        { withCredentials: true },
      ),
    );
    return unwrapList(body);
  }

  async getStockCategory(
    companyId: string,
    stockCategoryId: string,
  ): Promise<StockCategoryMaster> {
    return firstValueFrom(
      this.http.get<StockCategoryMaster>(
        this.companyUrl(companyId, `/stock-categories/${encodeURIComponent(stockCategoryId)}`),
        { withCredentials: true },
      ),
    );
  }

  async createStockCategory(
    companyId: string,
    payload: UpsertStockCategoryRequest,
  ): Promise<StockCategoryMaster> {
    return firstValueFrom(
      this.http.post<StockCategoryMaster>(
        this.companyUrl(companyId, '/stock-categories'),
        payload,
        { withCredentials: true },
      ),
    );
  }

  async updateStockCategory(
    companyId: string,
    stockCategoryId: string,
    payload: UpsertStockCategoryRequest,
    versionNo?: number,
  ): Promise<StockCategoryMaster> {
    return firstValueFrom(
      this.http.put<StockCategoryMaster>(
        this.companyUrl(companyId, `/stock-categories/${encodeURIComponent(stockCategoryId)}`),
        payload,
        { headers: ifMatchHeader(versionNo ?? payload.versionNo), withCredentials: true },
      ),
    );
  }

  async deleteStockCategory(companyId: string, stockCategoryId: string): Promise<void> {
    await firstValueFrom(
      this.http.delete<void>(
        this.companyUrl(companyId, `/stock-categories/${encodeURIComponent(stockCategoryId)}`),
        { withCredentials: true },
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // 4. Stock Items & Batches (`/stock-items`, `/stock-items/typeahead`, `/batches`)
  // ---------------------------------------------------------------------------

  async listStockItems(companyId: string): Promise<readonly StockItemMaster[]> {
    const body = await firstValueFrom(
      this.http.get<CursorPage<StockItemMaster> | readonly StockItemMaster[]>(
        this.companyUrl(companyId, '/stock-items'),
        { withCredentials: true },
      ),
    );
    return unwrapList(body);
  }

  async typeaheadStockItems(companyId: string, query: string): Promise<readonly StockItemMaster[]> {
    const params = new HttpParams().set('q', query);
    const body = await firstValueFrom(
      this.http.get<CursorPage<StockItemMaster> | readonly StockItemMaster[]>(
        this.companyUrl(companyId, '/stock-items/typeahead'),
        { params, withCredentials: true },
      ),
    );
    return unwrapList(body);
  }

  async getStockItem(companyId: string, stockItemId: string): Promise<StockItemMaster> {
    return firstValueFrom(
      this.http.get<StockItemMaster>(
        this.companyUrl(companyId, `/stock-items/${encodeURIComponent(stockItemId)}`),
        { withCredentials: true },
      ),
    );
  }

  async createStockItem(
    companyId: string,
    payload: UpsertStockItemRequest,
  ): Promise<StockItemMaster> {
    return firstValueFrom(
      this.http.post<StockItemMaster>(this.companyUrl(companyId, '/stock-items'), payload, {
        withCredentials: true,
      }),
    );
  }

  async updateStockItem(
    companyId: string,
    stockItemId: string,
    payload: UpsertStockItemRequest,
    versionNo?: number,
  ): Promise<StockItemMaster> {
    return firstValueFrom(
      this.http.put<StockItemMaster>(
        this.companyUrl(companyId, `/stock-items/${encodeURIComponent(stockItemId)}`),
        payload,
        { headers: ifMatchHeader(versionNo ?? payload.versionNo), withCredentials: true },
      ),
    );
  }

  async deleteStockItem(companyId: string, stockItemId: string): Promise<void> {
    await firstValueFrom(
      this.http.delete<void>(
        this.companyUrl(companyId, `/stock-items/${encodeURIComponent(stockItemId)}`),
        { withCredentials: true },
      ),
    );
  }

  async listStockItemBatches(
    companyId: string,
    stockItemId: string,
    options?: { readonly godownId?: string | null; readonly asOfDate?: string | null },
  ): Promise<readonly StockBatchDto[]> {
    let params = new HttpParams();
    if (options?.godownId) {
      params = params.set('godownId', options.godownId);
    }
    if (options?.asOfDate) {
      params = params.set('asOfDate', options.asOfDate);
    }
    const body = await firstValueFrom(
      this.http.get<CursorPage<StockBatchDto> | readonly StockBatchDto[]>(
        this.companyUrl(companyId, `/stock-items/${encodeURIComponent(stockItemId)}/batches`),
        { params, withCredentials: true },
      ),
    );
    return unwrapList(body);
  }

  // ---------------------------------------------------------------------------
  // 5. Price Lists (`/price-lists`)
  // ---------------------------------------------------------------------------

  async listPriceLists(companyId: string): Promise<readonly PriceListMaster[]> {
    const body = await firstValueFrom(
      this.http.get<CursorPage<PriceListMaster> | readonly PriceListMaster[]>(
        this.companyUrl(companyId, '/price-lists'),
        { withCredentials: true },
      ),
    );
    return unwrapList(body);
  }

  async getPriceList(companyId: string, priceListId: string): Promise<PriceListMaster> {
    return firstValueFrom(
      this.http.get<PriceListMaster>(
        this.companyUrl(companyId, `/price-lists/${encodeURIComponent(priceListId)}`),
        { withCredentials: true },
      ),
    );
  }

  async createPriceList(
    companyId: string,
    payload: UpsertPriceListRequest,
  ): Promise<PriceListMaster> {
    return firstValueFrom(
      this.http.post<PriceListMaster>(this.companyUrl(companyId, '/price-lists'), payload, {
        withCredentials: true,
      }),
    );
  }

  async updatePriceList(
    companyId: string,
    priceListId: string,
    payload: UpsertPriceListRequest,
    versionNo?: number,
  ): Promise<PriceListMaster> {
    return firstValueFrom(
      this.http.put<PriceListMaster>(
        this.companyUrl(companyId, `/price-lists/${encodeURIComponent(priceListId)}`),
        payload,
        { headers: ifMatchHeader(versionNo ?? payload.versionNo), withCredentials: true },
      ),
    );
  }

  async lookupPrice(
    companyId: string,
    priceLevelName: string,
    stockItemId: string,
    qty: string,
    asOfDate: string,
  ): Promise<PriceLookupResponseDto> {
    const params = new HttpParams()
      .set('priceLevelName', priceLevelName)
      .set('stockItemId', stockItemId)
      .set('qty', qty)
      .set('asOfDate', asOfDate);

    return firstValueFrom(
      this.http.get<PriceLookupResponseDto>(this.companyUrl(companyId, '/price-lists/lookup'), {
        params,
        withCredentials: true,
      }),
    );
  }

  // ---------------------------------------------------------------------------
  // 6. Bills of Materials (`/boms`) & Manufacturing Preview
  // ---------------------------------------------------------------------------

  async listBoms(companyId: string, stockItemId?: string): Promise<readonly BomMaster[]> {
    let params = new HttpParams();
    if (stockItemId) {
      params = params.set('stockItemId', stockItemId);
    }
    const body = await firstValueFrom(
      this.http.get<CursorPage<BomMaster> | readonly BomMaster[]>(
        this.companyUrl(companyId, '/boms'),
        { params, withCredentials: true },
      ),
    );
    return unwrapList(body);
  }

  async getBom(companyId: string, bomId: string): Promise<BomMaster> {
    return firstValueFrom(
      this.http.get<BomMaster>(this.companyUrl(companyId, `/boms/${encodeURIComponent(bomId)}`), {
        withCredentials: true,
      }),
    );
  }

  async createBom(companyId: string, payload: UpsertBomRequest): Promise<BomMaster> {
    return firstValueFrom(
      this.http.post<BomMaster>(this.companyUrl(companyId, '/boms'), payload, {
        withCredentials: true,
      }),
    );
  }

  async updateBom(
    companyId: string,
    bomId: string,
    payload: UpsertBomRequest,
    versionNo?: number,
  ): Promise<BomMaster> {
    return firstValueFrom(
      this.http.put<BomMaster>(
        this.companyUrl(companyId, `/boms/${encodeURIComponent(bomId)}`),
        payload,
        { headers: ifMatchHeader(versionNo ?? payload.versionNo), withCredentials: true },
      ),
    );
  }

  async previewManufacturingBom(
    companyId: string,
    payload: ManufacturingPreviewRequest,
  ): Promise<ManufacturingPreviewResponse> {
    return firstValueFrom(
      this.http.post<ManufacturingPreviewResponse>(
        this.companyUrl(companyId, '/vouchers/manufacturing-preview'),
        payload,
        { withCredentials: true },
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // 7. Open Orders (`/orders/open`) & Valuation Reports (`/reports/...`)
  // ---------------------------------------------------------------------------

  async listOpenOrders(
    companyId: string,
    orderType: OrderVoucherKind,
    partyLedgerId?: string | null,
  ): Promise<readonly OpenOrderLineDto[]> {
    let params = new HttpParams().set('orderType', orderType);
    if (partyLedgerId) {
      params = params.set('partyLedgerId', partyLedgerId);
    }
    const body = await firstValueFrom(
      this.http.get<CursorPage<OpenOrderLineDto> | readonly OpenOrderLineDto[]>(
        this.companyUrl(companyId, '/orders/open'),
        { params, withCredentials: true },
      ),
    );
    return unwrapList(body);
  }

  async getStockSummary(
    companyId: string,
    options?: {
      readonly fromDate?: string;
      readonly toDate?: string;
      readonly stockGroupId?: string | null;
      readonly valuationMethodOverride?: StockCostingMethod | null;
    },
  ): Promise<StockSummaryReportDto> {
    let params = new HttpParams();
    if (options?.fromDate) params = params.set('fromDate', options.fromDate);
    if (options?.toDate) params = params.set('toDate', options.toDate);
    if (options?.stockGroupId) params = params.set('stockGroupId', options.stockGroupId);
    if (options?.valuationMethodOverride) {
      params = params.set('valuationMethodOverride', options.valuationMethodOverride);
    }

    return firstValueFrom(
      this.http.get<StockSummaryReportDto>(this.companyUrl(companyId, '/reports/stock-summary'), {
        params,
        withCredentials: true,
      }),
    );
  }

  async getGodownSummary(
    companyId: string,
    options?: {
      readonly fromDate?: string;
      readonly toDate?: string;
      readonly godownId?: string | null;
    },
  ): Promise<GodownSummaryReportDto> {
    let params = new HttpParams();
    if (options?.fromDate) params = params.set('fromDate', options.fromDate);
    if (options?.toDate) params = params.set('toDate', options.toDate);
    if (options?.godownId) params = params.set('godownId', options.godownId);

    return firstValueFrom(
      this.http.get<GodownSummaryReportDto>(
        this.companyUrl(companyId, '/reports/godown-summary'),
        { params, withCredentials: true },
      ),
    );
  }

  async getMovementAnalysis(
    companyId: string,
    stockItemId: string,
    options?: { readonly fromDate?: string; readonly toDate?: string },
  ): Promise<MovementAnalysisReportDto> {
    let params = new HttpParams().set('stockItemId', stockItemId);
    if (options?.fromDate) params = params.set('fromDate', options.fromDate);
    if (options?.toDate) params = params.set('toDate', options.toDate);

    return firstValueFrom(
      this.http.get<MovementAnalysisReportDto>(
        this.companyUrl(companyId, '/reports/movement-analysis'),
        { params, withCredentials: true },
      ),
    );
  }

  async getBatchAgeing(
    companyId: string,
    options?: { readonly asOfDate?: string; readonly stockItemId?: string | null },
  ): Promise<BatchAgeingReportDto> {
    let params = new HttpParams();
    if (options?.asOfDate) params = params.set('asOfDate', options.asOfDate);
    if (options?.stockItemId) params = params.set('stockItemId', options.stockItemId);

    return firstValueFrom(
      this.http.get<BatchAgeingReportDto>(this.companyUrl(companyId, '/reports/batch-ageing'), {
        params,
        withCredentials: true,
      }),
    );
  }
}
