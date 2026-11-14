import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { InventoryStore } from '../inventory/inventory.store';
import { ChartOfAccountsStore } from './chart-of-accounts.store';

export interface MasterCatalogEntry {
  readonly id: string;
  readonly category:
    | 'Accounting Masters'
    | 'Inventory Masters'
    | 'Statutory Masters'
    | 'Cost & Multi-Currency'
    | 'Chart & Hierarchy';
  readonly label: string;
  readonly description: string;
  readonly route: string;
  readonly badge: string;
  readonly extraParams?: Readonly<Record<string, string>>;
}

export const ACCOUNTING_MASTER_CATALOG: readonly MasterCatalogEntry[] = [
  {
    id: 'group',
    category: 'Accounting Masters',
    label: 'Group',
    description: '28 predefined TallyPrime 7.2 primary/sub-groups and custom reporting groups',
    route: '/app/masters/groups',
    badge: '28+ GROUPS',
  },
  {
    id: 'ledger',
    category: 'Accounting Masters',
    label: 'Ledger',
    description: 'Party, Bank, Cash, Sales/Purchase, Expense, GST Duty & TDS Ledgers',
    route: '/app/masters/ledgers',
    badge: 'GL & SUB-LEDGER',
  },
  {
    id: 'voucher-type',
    category: 'Accounting Masters',
    label: 'Voucher Type',
    description: '24 predefined accounting/inventory voucher types, numbering series & 4-eyes approval',
    route: '/app/masters/voucher-types',
    badge: '24 VCH TYPES',
  },
  {
    id: 'stock-group',
    category: 'Inventory Masters',
    label: 'Stock Group',
    description: 'Hierarchical product families with inherited HSN/SAC and GST tax rates',
    route: '/app/inventory/stock-groups',
    badge: 'STK GROUP',
  },
  {
    id: 'stock-category',
    category: 'Inventory Masters',
    label: 'Stock Category',
    description: 'Parallel inventory classification dimension across Stock Groups',
    route: '/app/inventory/stock-groups',
    badge: 'STK CAT',
    extraParams: { tab: 'categories' },
  },
  {
    id: 'stock-item',
    category: 'Inventory Masters',
    label: 'Stock Item',
    description: 'SKUs, Alternate UOMs, Batches, 8 Costing Methods, Tobacco MRP & Multi-BOM',
    route: '/app/inventory/stock-items',
    badge: 'SKU & BOM',
  },
  {
    id: 'uom',
    category: 'Inventory Masters',
    label: 'Unit (UOM)',
    description: 'Simple and Compound Units of Measure mapped to GST UQC codes',
    route: '/app/inventory/uoms',
    badge: 'UOM / UQC',
  },
  {
    id: 'godown',
    category: 'Inventory Masters',
    label: 'Godown / Location',
    description: 'Multi-warehouse storage hierarchy, bins, Job Work Out/In & Main Location',
    route: '/app/inventory/godowns',
    badge: 'WAREHOUSE',
  },
  {
    id: 'price-list',
    category: 'Inventory Masters',
    label: 'Price List',
    description: 'Date-effective Wholesale/Dealer/Retail staggered quantity slab pricing',
    route: '/app/inventory/price-lists',
    badge: 'SLAB PRICE',
  },
  {
    id: 'currency',
    category: 'Cost & Multi-Currency',
    label: 'Currency',
    description: 'ISO 4217 currencies, decimal places, symbol placement & formatting',
    route: '/app/masters/currencies',
    badge: 'FOREX',
  },
  {
    id: 'forex-rates',
    category: 'Cost & Multi-Currency',
    label: 'Rates of Exchange',
    description: 'Date-effective Standard, Selling, and Buying foreign exchange rates',
    route: '/app/masters/currencies',
    badge: 'RATES',
    extraParams: { tab: 'rates' },
  },
  {
    id: 'cost-category',
    category: 'Cost & Multi-Currency',
    label: 'Cost Category',
    description: 'Parallel allocation dimensions for Revenue and Non-Revenue items',
    route: '/app/masters/cost-centres',
    badge: 'CATEGORY',
    extraParams: { tab: 'categories' },
  },
  {
    id: 'cost-centre',
    category: 'Cost & Multi-Currency',
    label: 'Cost Centre',
    description: 'Hierarchical profit/cost units under Primary or custom Cost Categories',
    route: '/app/masters/cost-centres',
    badge: 'CENTRE',
  },
  {
    id: 'gst-registration',
    category: 'Statutory Masters',
    label: 'GST Registration (Multi-GSTIN)',
    description: 'State-wise GSTIN registrations, LUT/Bond validity, e-Invoice & e-Way Bill thresholds',
    route: '/app/statutory/gst-registrations',
    badge: 'MULTI-GSTIN',
  },
  {
    id: 'coa-explorer',
    category: 'Chart & Hierarchy',
    label: 'Chart of Accounts Explorer',
    description: 'Expandable tree with Alt+F1 Condensed/Detailed toggle & ExactDecimal Opening Totals',
    route: '/app/coa',
    badge: 'TREE',
  },
];

@Component({
  selector: 'mc-masters-hub',
  standalone: true,
  imports: [FormsModule, RouterLink, CodeBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-masters-hub-grid">
      <!-- Left Tally-Style List of Masters Selector -->
      <section class="mc-card mc-masters-selector-card" aria-label="List of Accounting Masters">
        <div class="mc-masters-card-header">
          <span>
            {{ hubMode() === 'alter' ? 'Master Alteration' : 'Master Creation' }} — List of Masters
          </span>
          <div class="mc-mode-pills">
            <button
              type="button"
              class="mc-mode-pill"
              [class.mc-mode-pill-active]="hubMode() === 'create'"
              (click)="hubMode.set('create')"
            >
              Create
            </button>
            <button
              type="button"
              class="mc-mode-pill"
              [class.mc-mode-pill-active]="hubMode() === 'alter'"
              (click)="hubMode.set('alter')"
            >
              Alter
            </button>
          </div>
        </div>

        <div class="mc-masters-search-bar">
          <input
            #filterInput
            type="text"
            class="mc-input"
            [ngModel]="filterQuery()"
            (ngModelChange)="onFilterChange($event)"
            (keydown)="onKeyDown($event)"
            placeholder="Type master type (Group, Ledger, Currency, Cost Centre, Voucher Type)..."
            aria-label="Filter List of Masters"
          />
        </div>

        <ul class="mc-masters-list" role="listbox">
          @for (entry of filteredEntries(); track entry.id; let idx = $index) {
            <li
              role="option"
              class="mc-masters-item"
              [class.mc-masters-item-active]="idx === selectedIndex()"
              [attr.aria-selected]="idx === selectedIndex()"
              (mouseenter)="selectedIndex.set(idx)"
              (click)="openMasterWorkbench(entry)"
            >
              <div class="mc-masters-item-main">
                <span class="mc-masters-item-title">{{ entry.label }}</span>
                <span class="mc-masters-item-desc">{{ entry.description }}</span>
              </div>
              <mc-code-badge [code]="entry.badge" />
            </li>
          } @empty {
            <li class="mc-masters-empty">No master type matches "{{ filterQuery() }}".</li>
          }
        </ul>

        <div class="mc-masters-card-footer">
          Up / Down = Highlight &bull; Enter = Open {{ hubMode() === 'alter' ? 'Alteration' : 'Creation' }} Workbench
        </div>
      </section>

      <!-- Right Live Summary Card -->
      <aside class="mc-card mc-masters-overview-card" aria-label="Masters Summary">
        <h2>Active Chart of Accounts Snapshot</h2>
        <p class="mc-overview-note">
          Every master created or altered via <strong>Ctrl+A</strong> is immediately indexed in
          scoped typeahead comboboxes and inline <strong>Alt+C</strong> Secondary Master modals.
        </p>

        <div class="mc-overview-metrics">
          <div class="mc-metric-tile">
            <span class="mc-metric-label">Account Groups</span>
            <span class="mc-metric-val">{{ coaStore.groups().length }}</span>
          </div>
          <div class="mc-metric-tile">
            <span class="mc-metric-label">Active Ledgers</span>
            <span class="mc-metric-val">{{ coaStore.ledgers().length }}</span>
          </div>
          <div class="mc-metric-tile">
            <span class="mc-metric-label">Voucher Types</span>
            <span class="mc-metric-val">{{ coaStore.voucherTypes().length }}</span>
          </div>
          <div class="mc-metric-tile">
            <span class="mc-metric-label">Currencies / Centres</span>
            <span class="mc-metric-val">
              {{ coaStore.currencies().length }} / {{ coaStore.costCentres().length }}
            </span>
          </div>
          <div class="mc-metric-tile">
            <span class="mc-metric-label">Stock Items / BOMs</span>
            <span class="mc-metric-val">
              {{ inventoryStore.stockItems().length }} / {{ inventoryStore.boms().length }}
            </span>
          </div>
          <div class="mc-metric-tile">
            <span class="mc-metric-label">Godowns / Units</span>
            <span class="mc-metric-val">
              {{ inventoryStore.godowns().length }} / {{ inventoryStore.uoms().length }}
            </span>
          </div>
        </div>

        <div class="mc-overview-links">
          <a routerLink="/app/coa" class="mc-btn mc-btn-primary">
            Open Chart of Accounts (<span class="mc-key-alt">G</span>)
          </a>
          <a routerLink="/app/reports/stock-summary" class="mc-btn">Stock Summary</a>
          <a routerLink="/app/gateway" class="mc-btn">Back to Gateway</a>
        </div>
      </aside>
    </div>
  `,
  styles: `
    .mc-masters-hub-grid {
      display: grid;
      grid-template-columns: minmax(0, 1.35fr) minmax(280px, 0.9fr);
      gap: var(--mc-space-4);
      align-items: start;
    }

    @media (max-width: 960px) {
      .mc-masters-hub-grid {
        grid-template-columns: 1fr;
      }
    }

    .mc-masters-selector-card {
      overflow: hidden;
      border: 2px solid var(--mc-color-slate-900);
    }

    .mc-masters-card-header {
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      padding: 0.55rem 0.9rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-weight: 700;
    }

    .mc-mode-pills {
      display: flex;
      gap: 4px;
    }

    .mc-mode-pill {
      background: rgba(255, 255, 255, 0.12);
      border: 1px solid rgba(255, 255, 255, 0.24);
      color: var(--mc-color-surface);
      border-radius: var(--mc-radius-xs);
      padding: 0.12rem 0.55rem;
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
      cursor: pointer;
    }

    .mc-mode-pill-active {
      background: var(--mc-color-verdigris-teal);
      border-color: var(--mc-color-mint-signal);
    }

    .mc-masters-search-bar {
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border-bottom: var(--mc-border-subtle);
    }

    .mc-masters-list {
      list-style: none;
      margin: 0;
      padding: 0;
    }

    .mc-masters-item {
      padding: 0.55rem 0.9rem;
      border-bottom: 1px solid var(--mc-color-slate-100);
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-3);
      cursor: pointer;
    }

    .mc-masters-item-active {
      background: var(--mc-color-verdigris-teal);
      color: var(--mc-color-surface);

      .mc-masters-item-desc {
        color: rgba(255, 255, 255, 0.88);
      }
    }

    .mc-masters-item-main {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .mc-masters-item-title {
      font-weight: 700;
    }

    .mc-masters-item-desc {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-masters-empty {
      padding: var(--mc-space-4);
      text-align: center;
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-masters-card-footer {
      padding: 0.45rem 0.85rem;
      background: var(--mc-color-canvas);
      border-top: var(--mc-border-subtle);
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
      text-align: center;
    }

    .mc-masters-overview-card {
      padding: var(--mc-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);

      h2 {
        margin: 0;
        font-size: var(--mc-font-size-md);
      }
    }

    .mc-overview-note {
      margin: 0;
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-overview-metrics {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-2);
    }

    .mc-metric-tile {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .mc-metric-label {
      font-size: 11px;
      font-weight: 600;
      color: var(--mc-color-slate-600);
    }

    .mc-metric-val {
      font-size: var(--mc-font-size-lg);
      font-weight: 700;
      color: var(--mc-color-teal-700);
    }

    .mc-overview-links {
      display: flex;
      gap: var(--mc-space-2);
      flex-wrap: wrap;
    }
  `,
})
export class MastersHubComponent implements OnInit {
  readonly coaStore = inject(ChartOfAccountsStore);
  readonly inventoryStore = inject(InventoryStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly initialMode = input<'create' | 'alter' | null>(null);
  readonly initialSection = input<'all' | 'inventory'>('all');

  private readonly filterInputRef = viewChild<ElementRef<HTMLInputElement>>('filterInput');

  readonly hubMode = signal<'create' | 'alter'>('create');
  readonly filterQuery = signal<string>('');
  readonly selectedIndex = signal<number>(0);

  readonly filteredEntries = computed(() => {
    const q = this.filterQuery().trim().toLowerCase();
    const base =
      this.initialSection() === 'inventory'
        ? ACCOUNTING_MASTER_CATALOG.filter((e) => e.category === 'Inventory Masters')
        : ACCOUNTING_MASTER_CATALOG;
    if (!q) {
      return base;
    }
    return base.filter(
      (e) =>
        e.label.toLowerCase().includes(q) ||
        e.description.toLowerCase().includes(q) ||
        e.category.toLowerCase().includes(q),
    );
  });

  ngOnInit(): void {
    const paramMode = this.initialMode() ?? this.route.snapshot.queryParamMap.get('mode');
    if (paramMode === 'alter') {
      this.hubMode.set('alter');
    } else {
      this.hubMode.set('create');
    }
    if (this.router.url.includes('/inventory/masters') && !this.filterQuery()) {
      this.filterQuery.set('Inventory Masters');
    }

    void this.coaStore.ensureAccountingMastersLoaded();
    void this.coaStore.loadVoucherTypes().catch(() => []);
    void this.coaStore.loadCurrencies().catch(() => []);
    void this.coaStore.loadCostCentresAndCategories().catch(() => undefined);
    void this.inventoryStore.ensureLoaded();

    queueMicrotask(() => {
      this.filterInputRef()?.nativeElement.focus();
    });
  }

  onFilterChange(val: string): void {
    this.filterQuery.set(val);
    this.selectedIndex.set(0);
  }

  onKeyDown(event: KeyboardEvent): void {
    const items = this.filteredEntries();
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      if (items.length > 0) {
        this.selectedIndex.set((this.selectedIndex() + 1) % items.length);
      }
      return;
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      if (items.length > 0) {
        this.selectedIndex.set((this.selectedIndex() - 1 + items.length) % items.length);
      }
      return;
    }
    if (event.key === 'Enter') {
      event.preventDefault();
      const target = items[this.selectedIndex()];
      if (target) {
        this.openMasterWorkbench(target);
      }
    }
  }

  openMasterWorkbench(entry: MasterCatalogEntry): void {
    if (entry.route === '/app/coa') {
      void this.router.navigate(['/app/coa']);
      return;
    }
    void this.router.navigate([entry.route], {
      queryParams: {
        mode: this.hubMode(),
        ...(entry.extraParams ?? {}),
      },
    });
  }
}
