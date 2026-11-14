import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RuntimeConfigService } from '../core/http/runtime-config.service';
import { CompanyContextStore } from './company-context.store';

export type SmartFindScope = 'ACTIVE_COMPANY' | 'ALL_OPEN_COMPANIES';
export type SmartFindCategory = 'MASTER' | 'VOUCHER' | 'STATUTORY' | 'BILL_REF';

export interface SmartFindResultItem {
  readonly id: string;
  readonly category: SmartFindCategory;
  readonly companyId: string;
  readonly companyName: string;
  readonly title: string;
  readonly subtitle: string;
  readonly referenceCode: string | null;
  readonly amountWire: string | null;
  readonly targetRoute: string;
  readonly matchedField: string;
}

@Injectable({ providedIn: 'root' })
export class SmartFindStore {
  private readonly http = inject(HttpClient);
  private readonly runtimeConfig = inject(RuntimeConfigService);
  private readonly companyContext = inject(CompanyContextStore);

  private readonly isOpenSignal = signal<boolean>(false);
  private readonly querySignal = signal<string>('');
  private readonly scopeSignal = signal<SmartFindScope>('ACTIVE_COMPANY');
  private readonly resultsSignal = signal<readonly SmartFindResultItem[]>([]);
  private readonly selectedIndexSignal = signal<number>(0);
  private readonly loadingSignal = signal<boolean>(false);

  readonly isOpen = this.isOpenSignal.asReadonly();
  readonly query = this.querySignal.asReadonly();
  readonly scope = this.scopeSignal.asReadonly();
  readonly results = this.resultsSignal.asReadonly();
  readonly selectedIndex = this.selectedIndexSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();

  readonly selectedResult = computed<SmartFindResultItem | null>(() => {
    const items = this.resultsSignal();
    const idx = this.selectedIndexSignal();
    return items[idx] ?? null;
  });

  open(initialQuery = ''): void {
    this.isOpenSignal.set(true);
    if (initialQuery) {
      this.querySignal.set(initialQuery);
    }
    this.selectedIndexSignal.set(0);
  }

  close(): void {
    this.isOpenSignal.set(false);
  }

  toggleScope(): void {
    const next: SmartFindScope =
      this.scopeSignal() === 'ACTIVE_COMPANY' ? 'ALL_OPEN_COMPANIES' : 'ACTIVE_COMPANY';
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

  async search(rawQuery: string): Promise<readonly SmartFindResultItem[]> {
    this.querySignal.set(rawQuery);
    const trimmed = rawQuery.trim();
    if (trimmed.length === 0) {
      this.resultsSignal.set([]);
      this.selectedIndexSignal.set(0);
      return [];
    }

    const activeCompany = this.companyContext.activeCompany();
    if (!activeCompany) {
      const localMatches = this.buildOpenCompanyMatches(trimmed);
      this.resultsSignal.set(localMatches);
      this.selectedIndexSignal.set(0);
      return localMatches;
    }

    this.loadingSignal.set(true);
    try {
      const params = new HttpParams().set('q', trimmed).set('scope', this.scopeSignal());
      const items = await firstValueFrom(
        this.http.get<readonly SmartFindResultItem[]>(
          this.runtimeConfig.url(
            `/companies/${encodeURIComponent(activeCompany.id)}/search/smartfind`,
          ),
          { params, withCredentials: true },
        ),
      );
      this.resultsSignal.set(items);
      this.selectedIndexSignal.set(0);
      return items;
    } catch {
      const fallback = this.buildOpenCompanyMatches(trimmed);
      this.resultsSignal.set(fallback);
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
        companyId: c.id,
        companyName: c.legalName,
        title: c.legalName,
        subtitle: `Company (${c.stateCode}) • FY from ${c.financialYearFrom}`,
        referenceCode: c.features.gstin || c.code,
        amountWire: null,
        targetRoute: '/app/companies',
        matchedField: 'Company Master',
      }));
  }
}
