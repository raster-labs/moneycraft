import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RuntimeConfigService } from '../core/http/runtime-config.service';
import { CompanyContextStore } from './company-context.store';

export type SmartFindScope = 'ACTIVE' | 'ALL_OPEN_COMPANIES';
export type SmartFindCategory = 'MASTER' | 'VOUCHER' | 'STATUTORY';

export interface SmartFindResultItem {
  readonly id: string;
  readonly companyId: string;
  readonly companyName: string;
  readonly category: SmartFindCategory;
  readonly entityType: string;
  readonly title: string;
  readonly subtitle: string;
  readonly matchedField: string;
  readonly matchedSnippet: string;
  readonly referenceCode: string | null;
  readonly amount: string | null;
  readonly route: string;
}

export interface SmartFindResponseDto {
  readonly query: string;
  readonly scope: SmartFindScope;
  readonly tookMs: number;
  readonly totalMatches: number;
  readonly results: readonly SmartFindResultItem[];
}

export interface SmartFindCategoryGroup {
  readonly category: SmartFindCategory;
  readonly label: string;
  readonly entries: readonly { readonly item: SmartFindResultItem; readonly flatIndex: number }[];
}

type RawRecord = Record<string, unknown>;

const CATEGORY_ORDER: readonly { readonly key: SmartFindCategory; readonly label: string }[] = [
  { key: 'MASTER', label: 'Masters' },
  { key: 'VOUCHER', label: 'Vouchers' },
  { key: 'STATUTORY', label: 'Statutory' },
];

function normalizeCategory(raw: unknown): SmartFindCategory {
  const upper = String(raw ?? 'MASTER').toUpperCase();
  if (upper.startsWith('VOUCHER') || upper === 'TRANSACTION' || upper === 'BILL_REF') {
    return 'VOUCHER';
  }
  if (upper.startsWith('STATUTORY') || upper === 'GST' || upper === 'TDS' || upper === 'RETURN') {
    return 'STATUTORY';
  }
  return 'MASTER';
}

function normalizeResultItem(
  raw: RawRecord,
  fallbackCompanyId: string,
  fallbackCompanyName: string,
): SmartFindResultItem {
  const rawRoute = String(raw['route'] ?? raw['targetRoute'] ?? '/app/gateway').trim();
  const normalizedRoute = rawRoute.startsWith('/') ? rawRoute : `/app/${rawRoute}`;
  const rawAmount = raw['amount'] ?? raw['amountWire'];
  const amount =
    typeof rawAmount === 'string' && rawAmount.trim().length > 0
      ? rawAmount.trim()
      : typeof rawAmount === 'number' && Number.isFinite(rawAmount)
        ? String(rawAmount)
        : null;

  return {
    id: String(raw['id'] ?? ''),
    companyId: String(raw['companyId'] ?? fallbackCompanyId),
    companyName: String(raw['companyName'] ?? fallbackCompanyName),
    category: normalizeCategory(raw['category']),
    entityType: String(raw['entityType'] ?? raw['category'] ?? 'MASTER'),
    title: String(raw['title'] ?? raw['name'] ?? ''),
    subtitle: String(raw['subtitle'] ?? ''),
    matchedField: String(raw['matchedField'] ?? 'Name'),
    matchedSnippet: String(raw['matchedSnippet'] ?? raw['snippet'] ?? raw['subtitle'] ?? ''),
    referenceCode: raw['referenceCode'] ? String(raw['referenceCode']) : null,
    amount,
    route: normalizedRoute,
  };
}

@Injectable({ providedIn: 'root' })
export class SmartFindStore {
  private readonly http = inject(HttpClient);
  private readonly runtimeConfig = inject(RuntimeConfigService);
  private readonly companyContext = inject(CompanyContextStore);

  private readonly isOpenSignal = signal<boolean>(false);
  private readonly querySignal = signal<string>('');
  private readonly scopeSignal = signal<SmartFindScope>('ACTIVE');
  private readonly resultsSignal = signal<readonly SmartFindResultItem[]>([]);
  private readonly selectedIndexSignal = signal<number>(0);
  private readonly loadingSignal = signal<boolean>(false);
  private readonly tookMsSignal = signal<number>(0);
  private readonly totalMatchesSignal = signal<number>(0);

  readonly isOpen = this.isOpenSignal.asReadonly();
  readonly query = this.querySignal.asReadonly();
  readonly scope = this.scopeSignal.asReadonly();
  readonly results = this.resultsSignal.asReadonly();
  readonly selectedIndex = this.selectedIndexSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  readonly tookMs = this.tookMsSignal.asReadonly();
  readonly totalMatches = this.totalMatchesSignal.asReadonly();

  readonly groupedCategories = computed<readonly SmartFindCategoryGroup[]>(() => {
    const all = this.resultsSignal();
    const groups: SmartFindCategoryGroup[] = [];
    for (const cat of CATEGORY_ORDER) {
      const entries: { readonly item: SmartFindResultItem; readonly flatIndex: number }[] = [];
      all.forEach((item, idx) => {
        if (item.category === cat.key) {
          entries.push({ item, flatIndex: idx });
        }
      });
      if (entries.length > 0) {
        groups.push({
          category: cat.key,
          label: cat.label,
          entries,
        });
      }
    }
    return groups;
  });

  readonly selectedResult = computed<SmartFindResultItem | null>(() => {
    const items = this.resultsSignal();
    const idx = this.selectedIndexSignal();
    return items[idx] ?? null;
  });

  open(initialQuery = ''): void {
    this.isOpenSignal.set(true);
    if (initialQuery) {
      this.querySignal.set(initialQuery);
      void this.search(initialQuery);
    }
    this.selectedIndexSignal.set(0);
  }

  close(): void {
    this.isOpenSignal.set(false);
  }

  toggleScope(): void {
    const next: SmartFindScope =
      this.scopeSignal() === 'ACTIVE' ? 'ALL_OPEN_COMPANIES' : 'ACTIVE';
    this.scopeSignal.set(next);
    if (this.querySignal().trim().length > 0) {
      void this.search(this.querySignal());
    }
  }

  moveSelection(delta: number): void {
    const total = this.resultsSignal().length;
    if (total === 0) {
      this.selectedIndexSignal.set(0);
      return;
    }
    const next = (this.selectedIndexSignal() + delta + total) % total;
    this.selectedIndexSignal.set(next);
  }

  setSelectedIndex(index: number): void {
    const total = this.resultsSignal().length;
    if (index >= 0 && index < total) {
      this.selectedIndexSignal.set(index);
    }
  }

  async search(rawQuery: string): Promise<readonly SmartFindResultItem[]> {
    this.querySignal.set(rawQuery);
    const trimmed = rawQuery.trim();
    if (trimmed.length === 0) {
      this.resultsSignal.set([]);
      this.totalMatchesSignal.set(0);
      this.tookMsSignal.set(0);
      this.selectedIndexSignal.set(0);
      return [];
    }

    const activeCompany = this.companyContext.activeCompany();
    if (!activeCompany) {
      const localMatches = this.buildOpenCompanyMatches(trimmed);
      this.resultsSignal.set(localMatches);
      this.totalMatchesSignal.set(localMatches.length);
      this.tookMsSignal.set(1);
      this.selectedIndexSignal.set(0);
      return localMatches;
    }

    this.loadingSignal.set(true);
    try {
      const params = new HttpParams()
        .set('q', trimmed)
        .set('scope', this.scopeSignal())
        .set('limit', '30');

      const body = await firstValueFrom(
        this.http.get<unknown>(
          this.runtimeConfig.url(
            `/companies/${encodeURIComponent(activeCompany.id)}/search/smartfind`,
          ),
          { params, withCredentials: true },
        ),
      );

      let rawItems: RawRecord[] = [];
      let tookMs = 0;
      let totalMatches = 0;

      if (Array.isArray(body)) {
        rawItems = body as RawRecord[];
        totalMatches = rawItems.length;
      } else if (body && typeof body === 'object') {
        const rec = body as RawRecord;
        rawItems = Array.isArray(rec['results'])
          ? (rec['results'] as RawRecord[])
          : Array.isArray(rec['items'])
            ? (rec['items'] as RawRecord[])
            : [];
        tookMs = typeof rec['tookMs'] === 'number' ? rec['tookMs'] : 0;
        totalMatches =
          typeof rec['totalMatches'] === 'number' ? rec['totalMatches'] : rawItems.length;
      }

      const normalized = rawItems.map((r) =>
        normalizeResultItem(r, activeCompany.id, activeCompany.legalName),
      );
      // Sort canonically by category order so flat index matches visual group order
      const sorted = [
        ...normalized.filter((i) => i.category === 'MASTER'),
        ...normalized.filter((i) => i.category === 'VOUCHER'),
        ...normalized.filter((i) => i.category === 'STATUTORY'),
      ];

      this.resultsSignal.set(sorted);
      this.tookMsSignal.set(tookMs);
      this.totalMatchesSignal.set(totalMatches);
      this.selectedIndexSignal.set(0);
      return sorted;
    } catch {
      const fallback = this.buildOpenCompanyMatches(trimmed);
      this.resultsSignal.set(fallback);
      this.totalMatchesSignal.set(fallback.length);
      this.tookMsSignal.set(0);
      this.selectedIndexSignal.set(0);
      return fallback;
    } finally {
      this.loadingSignal.set(false);
    }
  }

  private buildOpenCompanyMatches(query: string): SmartFindResultItem[] {
    const needle = query.toLowerCase();
    return this.companyContext
      .openCompanies()
      .filter(
        (c) =>
          c.legalName.toLowerCase().includes(needle) ||
          c.code.toLowerCase().includes(needle) ||
          c.features.gstin.toLowerCase().includes(needle),
      )
      .map((c) => ({
        id: `company-${c.id}`,
        category: 'MASTER' as const,
        entityType: 'COMPANY',
        companyId: c.id,
        companyName: c.legalName,
        title: c.legalName,
        subtitle: `Company (${c.stateCode}) • FY from ${c.financialYearFrom}`,
        matchedField: 'Company Master',
        matchedSnippet: `${c.legalName} (${c.features.gstin || c.code})`,
        referenceCode: c.features.gstin || c.code,
        amount: null,
        route: '/app/companies',
      }));
  }
}
