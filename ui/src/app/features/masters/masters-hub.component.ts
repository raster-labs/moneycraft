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
import {
  CompanyContextStore,
  CompanyFeaturesConfig,
} from '../../state/company-context.store';
import { InventoryStore } from '../inventory/inventory.store';
import { ChartOfAccountsStore } from './chart-of-accounts.store';

export interface MasterCatalogEntry {
  readonly id: string;
  readonly category:
    | 'Accounting Masters'
    | 'Inventory Masters'
    | 'Payroll Masters'
    | 'Statutory Masters'
    | 'Cost & Multi-Currency'
    | 'Chart & Hierarchy';
  readonly label: string;
  readonly description: string;
  readonly route: string;
  readonly badge: string;
  readonly requiredFeature?: keyof CompanyFeaturesConfig;
  readonly requiredFeatureLabel?: string;
  readonly showMoreOnly?: boolean;
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
    id: 'budget',
    category: 'Accounting Masters',
    label: 'Budget',
    description: 'Periodic Group, Ledger, and Cost Centre variance budgets',
    route: '/app/masters/groups',
    badge: 'BUDGET',
    showMoreOnly: true,
  },
  {
    id: 'scenario',
    category: 'Accounting Masters',
    label: 'Scenario',
    description: 'Provisional what-if reporting combining Actuals + Optional / Reversing Journals',
    route: '/app/masters/voucher-types',
    badge: 'SCENARIO',
    showMoreOnly: true,
  },
  {
    id: 'credit-limits',
    category: 'Accounting Masters',
    label: 'Credit Limits',
    description: 'Bulk party credit limit and credit period assignment table',
    route: '/app/masters/ledgers',
    badge: 'CREDIT LIMIT',
    showMoreOnly: true,
  },
  {
    id: 'stock-group',
    category: 'Inventory Masters',
    label: 'Stock Group',
    description: 'Hierarchical product families with inherited HSN/SAC and GST tax rates',
    route: '/app/inventory/stock-groups',
    badge: 'STK GROUP',
    requiredFeature: 'maintainInventory',
    requiredFeatureLabel: 'Maintain Inventory',
  },
  {
    id: 'stock-category',
    category: 'Inventory Masters',
    label: 'Stock Category',
    description: 'Parallel inventory classification dimension across Stock Groups',
    route: '/app/inventory/stock-groups',
    badge: 'STK CAT',
    requiredFeature: 'maintainInventory',
    requiredFeatureLabel: 'Maintain Inventory',
    showMoreOnly: true,
    extraParams: { tab: 'categories' },
  },
  {
    id: 'stock-item',
    category: 'Inventory Masters',
    label: 'Stock Item',
    description: 'SKUs, Alternate UOMs, Batches, 8 Costing Methods, Tobacco MRP & Multi-BOM',
    route: '/app/inventory/stock-items',
    badge: 'SKU & BOM',
    requiredFeature: 'maintainInventory',
    requiredFeatureLabel: 'Maintain Inventory',
  },
  {
    id: 'uom',
    category: 'Inventory Masters',
    label: 'Unit (UOM)',
    description: 'Simple and Compound Units of Measure mapped to GST UQC codes',
    route: '/app/inventory/uoms',
    badge: 'UOM / UQC',
    requiredFeature: 'maintainInventory',
    requiredFeatureLabel: 'Maintain Inventory',
  },
  {
    id: 'godown',
    category: 'Inventory Masters',
    label: 'Godown / Location',
    description: 'Multi-warehouse storage hierarchy, bins, Job Work Out/In & Main Location',
    route: '/app/inventory/godowns',
    badge: 'WAREHOUSE',
    requiredFeature: 'maintainInventory',
    requiredFeatureLabel: 'Maintain Inventory',
  },
  {
    id: 'price-levels',
    category: 'Inventory Masters',
    label: 'Price Levels',
    description: 'Named commercial price levels (Wholesale, Dealer, Retail, Export)',
    route: '/app/inventory/price-lists',
    badge: 'PRICE LEVEL',
    requiredFeature: 'multiplePriceLevelsEnabled',
    requiredFeatureLabel: 'Enable multiple Price Levels',
  },
  {
    id: 'price-list',
    category: 'Inventory Masters',
    label: 'Price List',
    description: 'Date-effective Wholesale/Dealer/Retail staggered quantity slab pricing',
    route: '/app/inventory/price-lists',
    badge: 'SLAB PRICE',
    requiredFeature: 'multiplePriceLevelsEnabled',
    requiredFeatureLabel: 'Enable multiple Price Levels',
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
    showMoreOnly: true,
    extraParams: { tab: 'rates' },
  },
  {
    id: 'cost-category',
    category: 'Cost & Multi-Currency',
    label: 'Cost Category',
    description: 'Parallel allocation dimensions for Revenue and Non-Revenue items',
    route: '/app/masters/cost-centres',
    badge: 'CATEGORY',
    requiredFeature: 'costCentresEnabled',
    requiredFeatureLabel: 'Enable Cost Centres',
    extraParams: { tab: 'categories' },
  },
  {
    id: 'cost-centre',
    category: 'Cost & Multi-Currency',
    label: 'Cost Centre',
    description: 'Hierarchical profit/cost units under Primary or custom Cost Categories',
    route: '/app/masters/cost-centres',
    badge: 'CENTRE',
    requiredFeature: 'costCentresEnabled',
    requiredFeatureLabel: 'Enable Cost Centres',
  },
  {
    id: 'cost-centre-class',
    category: 'Cost & Multi-Currency',
    label: 'Cost Centre Class',
    description: 'Automated percentage-based cost allocation templates',
    route: '/app/masters/cost-centres',
    badge: 'CC CLASS',
    requiredFeature: 'costCentresEnabled',
    requiredFeatureLabel: 'Enable Cost Centres',
    showMoreOnly: true,
  },
  {
    id: 'employee-group',
    category: 'Payroll Masters',
    label: 'Employee Group',
    description: 'Departmental and functional grouping of employees for Payroll',
    route: '/app/masters/cost-centres',
    badge: 'PAYROLL',
    requiredFeature: 'payrollEnabled',
    requiredFeatureLabel: 'Maintain Payroll',
  },
  {
    id: 'employee',
    category: 'Payroll Masters',
    label: 'Employee',
    description: 'Employee profile, statutory PF/ESI/UAN/PAN details, and salary structure',
    route: '/app/masters/cost-centres',
    badge: 'PAYROLL',
    requiredFeature: 'payrollEnabled',
    requiredFeatureLabel: 'Maintain Payroll',
  },
  {
    id: 'pay-heads',
    category: 'Payroll Masters',
    label: 'Pay Heads',
    description: 'Earnings, deductions, statutory contributions, and gratuity pay heads',
    route: '/app/masters/ledgers',
    badge: 'PAY HEAD',
    requiredFeature: 'payrollEnabled',
    requiredFeatureLabel: 'Maintain Payroll',
  },
  {
    id: 'gst-registration',
    category: 'Statutory Masters',
    label: 'GST Registration (Multi-GSTIN)',
    description: 'State-wise GSTIN registrations, LUT/Bond validity, e-Invoice & e-Way Bill thresholds',
    route: '/app/statutory/gst-registrations',
    badge: 'MULTI-GSTIN',
    requiredFeature: 'gstEnabled',
    requiredFeatureLabel: 'Enable Goods and Services Tax (GST)',
  },
  {
    id: 'tds-nature',
    category: 'Statutory Masters',
    label: 'TDS Nature of Payment (26Q)',
    description: 'Sections 194C, 194J, 194H, 194I, 194Q threshold & rate configurations',
    route: '/app/statutory/tds-26q',
    badge: 'TDS 26Q',
    requiredFeature: 'tdsEnabled',
    requiredFeatureLabel: 'Enable Tax Deducted at Source (TDS)',
  },
  {
    id: 'tcs-nature',
    category: 'Statutory Masters',
    label: 'TCS Nature of Goods (27EQ)',
    description: 'Section 206C Tax Collected at Source categories and threshold limits',
    route: '/app/statutory/tds-26q',
    badge: 'TCS 27EQ',
    requiredFeature: 'tcsEnabled',
    requiredFeatureLabel: 'Enable Tax Collected at Source (TCS)',
  },
  {
    id: 'multiple-addresses',
    category: 'Statutory Masters',
    label: 'Multiple Addresses',
    description: 'Maintain multiple named dispatch and branch mailing addresses for the company',
    route: '/app/companies',
    badge: 'ADDRESSES',
    requiredFeature: 'multipleAddressesEnabled',
    requiredFeatureLabel: 'Enable multiple addresses',
    showMoreOnly: true,
    extraParams: { mode: 'alter' },
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
              [class.mc-mode-pill-active]="showMore()"
              data-testid="masters-show-more-btn"
              (click)="toggleShowMore()"
            >
              {{ showMore() ? 'Show Less' : 'Show More' }}
            </button>
            <button
              type="button"
              class="mc-mode-pill"
              [class.mc-mode-pill-active]="showInactive()"
              data-testid="masters-show-inactive-btn"
              (click)="toggleShowInactive()"
            >
              {{ showInactive() ? 'Hide Inactive' : 'Show Inactive' }}
            </button>
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
              [class.mc-masters-item-inactive]="isEntryInactive(entry)"
              [attr.aria-selected]="idx === selectedIndex()"
              [attr.data-testid]="'master-catalog-item-' + entry.id"
              (mouseenter)="selectedIndex.set(idx)"
              (click)="openMasterWorkbench(entry)"
            >
              <div class="mc-masters-item-main">
                <span class="mc-masters-item-title">
                  {{ entry.label }}
                  @if (isEntryInactive(entry)) {
                    <span class="mc-inactive-badge">(Inactive — F11)</span>
                  }
                </span>
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

      @if (pendingFeatureEntry(); as pending) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Enable F11 Feature Confirmation"
          data-testid="inactive-feature-enable-modal"
          (keydown)="onFeaturePromptKeyDown($event)"
        >
          <div class="mc-modal-dialog mc-feature-prompt-dialog">
            <div class="mc-modal-header">
              <span>Activate Feature in F11: Company Features</span>
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                data-testid="inactive-feature-enable-no-btn"
                (click)="cancelFeatureActivation()"
              >
                No (Esc / N)
              </button>
            </div>
            <div class="mc-modal-body">
              <p>
                You need to enable
                <strong>{{ pending.requiredFeatureLabel ?? pending.requiredFeature }}</strong>
                in <strong>F11: Company Features</strong> to use
                <strong>{{ pending.label }}</strong>. Enable now?
              </p>
            </div>
            <div class="mc-modal-footer">
              <button type="button" class="mc-btn" (click)="cancelFeatureActivation()">
                No (Esc / N)
              </button>
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="inactive-feature-enable-yes-btn"
                (click)="confirmFeatureActivation()"
              >
                Yes (Enter / Y)
              </button>
            </div>
          </div>
        </div>
      }
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
      flex-wrap: wrap;
      gap: 6px;
    }

    .mc-mode-pills {
      display: flex;
      gap: 4px;
      flex-wrap: wrap;
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

    .mc-masters-item-inactive {
      opacity: 0.85;
      background: var(--mc-color-cool-alabaster);
    }

    .mc-inactive-badge {
      margin-left: 6px;
      font-size: 11px;
      font-weight: 600;
      color: var(--mc-color-slate-600);
    }

    .mc-masters-item-active {
      background: var(--mc-color-verdigris-teal);
      color: var(--mc-color-surface);

      .mc-masters-item-desc,
      .mc-inactive-badge {
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

    .mc-feature-prompt-dialog {
      width: min(460px, 94vw);
    }
  `,
})
export class MastersHubComponent implements OnInit {
  readonly companyContext = inject(CompanyContextStore);
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
  readonly showMore = signal<boolean>(true);
  readonly showInactive = signal<boolean>(true);
  readonly pendingFeatureEntry = signal<MasterCatalogEntry | null>(null);

  readonly filteredEntries = computed(() => {
    const q = this.filterQuery().trim().toLowerCase();
    const more = this.showMore();
    const inact = this.showInactive();
    const base = (
      this.initialSection() === 'inventory'
        ? ACCOUNTING_MASTER_CATALOG.filter((e) => e.category === 'Inventory Masters')
        : ACCOUNTING_MASTER_CATALOG
    ).filter((e) => {
      if (e.showMoreOnly && !more && !inact) return false;
      if (this.isEntryInactive(e) && !inact && !q) return false;
      return true;
    });
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

  toggleShowMore(): void {
    this.showMore.update((v) => !v);
  }

  toggleShowInactive(): void {
    const next = !this.showInactive();
    this.showInactive.set(next);
    if (next) {
      this.showMore.set(true);
    }
  }

  isEntryInactive(entry: MasterCatalogEntry): boolean {
    if (!entry.requiredFeature) return false;
    const comp = this.companyContext.activeCompany();
    if (!comp) return false;
    return !comp.features[entry.requiredFeature];
  }

  onFilterChange(val: string): void {
    this.filterQuery.set(val);
    this.selectedIndex.set(0);
  }

  onKeyDown(event: KeyboardEvent): void {
    const items = this.filteredEntries();
    if ((event.ctrlKey || event.metaKey) && event.key === 'ArrowUp' && items.length > 0) {
      event.preventDefault();
      this.selectedIndex.set(0);
      return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key === 'ArrowDown' && items.length > 0) {
      event.preventDefault();
      this.selectedIndex.set(items.length - 1);
      return;
    }
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

  onFeaturePromptKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Escape' || event.key === 'n' || event.key === 'N') {
      event.preventDefault();
      event.stopPropagation();
      this.cancelFeatureActivation();
      return;
    }
    if (event.key === 'Enter' || event.key === 'y' || event.key === 'Y') {
      event.preventDefault();
      event.stopPropagation();
      void this.confirmFeatureActivation();
    }
  }

  cancelFeatureActivation(): void {
    this.pendingFeatureEntry.set(null);
  }

  async confirmFeatureActivation(): Promise<void> {
    const entry = this.pendingFeatureEntry();
    this.pendingFeatureEntry.set(null);
    if (!entry) return;
    if (entry.requiredFeature) {
      await this.companyContext.enableCompanyFeature(entry.requiredFeature);
    }
    this.navigateToMasterWorkbench(entry);
  }

  openMasterWorkbench(entry: MasterCatalogEntry): void {
    if (this.isEntryInactive(entry)) {
      this.pendingFeatureEntry.set(entry);
      return;
    }
    this.navigateToMasterWorkbench(entry);
  }

  private navigateToMasterWorkbench(entry: MasterCatalogEntry): void {
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
