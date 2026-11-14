import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import {
  MAX_NAVIGATION_STACK_DEPTH,
  NavigationStackStore,
} from '../../../state/navigation-stack.store';

export interface GoToDestination {
  readonly id: string;
  readonly group: 'Quick Actions' | 'Financial Reports' | 'Registers & Books' | 'Statutory & Admin';
  readonly title: string;
  readonly route: string;
  readonly params?: Readonly<Record<string, string>>;
}

export const GOTO_DESTINATIONS: readonly GoToDestination[] = [
  { id: 'act-gateway', group: 'Quick Actions', title: 'Gateway of MoneyCraft', route: '/app/gateway' },
  { id: 'act-companies', group: 'Quick Actions', title: 'Company Hub & Features (F3 / F11)', route: '/app/companies' },
  { id: 'act-create-master', group: 'Quick Actions', title: 'Create Master', route: '/app/masters', params: { mode: 'create' } },
  { id: 'act-alter-master', group: 'Quick Actions', title: 'Alter Master', route: '/app/masters', params: { mode: 'alter' } },
  { id: 'act-create-ledger', group: 'Quick Actions', title: 'Create / Alter Ledger', route: '/app/masters/ledgers', params: { mode: 'create' } },
  { id: 'act-create-group', group: 'Quick Actions', title: 'Create / Alter Account Group', route: '/app/masters/groups', params: { mode: 'create' } },
  { id: 'act-currencies', group: 'Quick Actions', title: 'Currencies & Forex Rates', route: '/app/masters/currencies' },
  { id: 'act-cost-centres', group: 'Quick Actions', title: 'Cost Categories & Cost Centres', route: '/app/masters/cost-centres' },
  { id: 'act-voucher-types', group: 'Quick Actions', title: 'Voucher Types (24 Categories)', route: '/app/masters/voucher-types' },
  { id: 'act-inv-items', group: 'Quick Actions', title: 'Stock Items & BOM', route: '/app/inventory/stock-items' },
  { id: 'act-inv-groups', group: 'Quick Actions', title: 'Stock Groups & Categories', route: '/app/inventory/stock-groups' },
  { id: 'act-inv-uoms', group: 'Quick Actions', title: 'Units of Measure (UOM)', route: '/app/inventory/uoms' },
  { id: 'act-inv-godowns', group: 'Quick Actions', title: 'Godowns / Warehouse Locations', route: '/app/inventory/godowns' },
  { id: 'act-inv-pricelists', group: 'Quick Actions', title: 'Price Lists & Quantity Tiers', route: '/app/inventory/price-lists' },
  { id: 'act-create-vch', group: 'Quick Actions', title: 'Create Voucher', route: '/app/vouchers/new' },
  { id: 'act-approvals', group: 'Quick Actions', title: 'Approval Queue (Four-Eyes)', route: '/app/approvals' },
  { id: 'reg-coa', group: 'Registers & Books', title: 'Chart of Accounts (28 Groups)', route: '/app/coa' },
  { id: 'reg-daybook', group: 'Registers & Books', title: 'Day Book', route: '/app/daybook' },
  { id: 'reg-vouchers', group: 'Registers & Books', title: 'Voucher Register', route: '/app/daybook' },
  { id: 'rpt-bs', group: 'Financial Reports', title: 'Balance Sheet', route: '/app/gateway', params: { panel: 'balance-sheet' } },
  { id: 'rpt-pnl', group: 'Financial Reports', title: 'Profit & Loss A/c', route: '/app/gateway', params: { panel: 'profit-loss' } },
  { id: 'rpt-tb', group: 'Financial Reports', title: 'Trial Balance', route: '/app/gateway', params: { panel: 'trial-balance' } },
  { id: 'rpt-stock', group: 'Financial Reports', title: 'Stock Summary', route: '/app/reports/stock-summary' },
  { id: 'rpt-godown', group: 'Financial Reports', title: 'Godown / Location Summary', route: '/app/reports/godown-summary' },
  { id: 'rpt-batch-ageing', group: 'Financial Reports', title: 'Batch / Expiry Ageing', route: '/app/reports/batch-ageing' },
  { id: 'rpt-ratios', group: 'Financial Reports', title: 'Ratio Analysis', route: '/app/gateway', params: { panel: 'ratios' } },
  { id: 'stat-gstr1', group: 'Statutory & Admin', title: 'GSTR-1 Outward Supplies', route: '/app/statutory/gstr1' },
  { id: 'stat-gstr3b', group: 'Statutory & Admin', title: 'GSTR-3B Monthly Return', route: '/app/statutory/gstr3b' },
  { id: 'stat-gstr9', group: 'Statutory & Admin', title: 'GSTR-9 Annual Return (One-Click)', route: '/app/statutory/gstr9' },
  { id: 'stat-ims', group: 'Statutory & Admin', title: 'IMS Reconciliation Workbench', route: '/app/statutory/ims' },
  { id: 'stat-einvoice', group: 'Statutory & Admin', title: 'e-Invoice & e-Way Bill Exchange', route: '/app/statutory/einvoice' },
  { id: 'stat-tds-26q', group: 'Statutory & Admin', title: 'TDS Form 26Q & ITNS 281 Challans', route: '/app/statutory/tds-26q' },
  { id: 'stat-gst-reg', group: 'Statutory & Admin', title: 'GST Registrations (Multi-GSTIN)', route: '/app/statutory/gst-registrations' },
  { id: 'adm-security', group: 'Statutory & Admin', title: 'Administration, RBAC & Audit Chain', route: '/app/admin' },
];

@Component({
  selector: 'mc-go-to-palette',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (isOpen()) {
      <div
        class="mc-modal-backdrop"
        role="dialog"
        aria-modal="true"
        [attr.aria-label]="mode() === 'GO_TO' ? 'Go To Overlay Launcher' : 'Switch To Launcher'"
        (click)="onBackdropClick($event)"
      >
        <div class="mc-modal-dialog mc-goto-dialog" (keydown)="onKeyDown($event)">
          <div class="mc-modal-header">
            <span>
              @if (mode() === 'GO_TO') {
                Go To (<span class="mc-key-alt">G</span>) — Non-Destructive Overlay Stack ({{ navStack.depth() }}/{{ maxDepth }})
              } @else {
                Switch To (<span class="mc-key-ctrl">G</span>) — Replace Workspace View
              }
            </span>
            <button type="button" class="mc-close-icon-btn" (click)="closed.emit()" aria-label="Close">
              Esc
            </button>
          </div>

          <div class="mc-goto-search-box">
            <input
              #searchInput
              type="text"
              class="mc-input"
              [value]="filterQuery()"
              (input)="onQueryInput($event)"
              placeholder="Type report, master, voucher, or administration screen..."
              aria-label="Filter Go To destinations"
            />
          </div>

          @if (navStack.isAtMaxDepth() && mode() === 'GO_TO') {
            <div class="mc-goto-limit-banner" role="alert">
              Maximum Go To overlay depth ({{ maxDepth }}) reached. Press Esc to close an existing overlay first, or use Ctrl+G (Switch To).
            </div>
          }

          <div class="mc-goto-list-header">List of Reports &amp; Actions</div>
          <ul class="mc-goto-list" role="listbox">
            @for (dest of filteredDestinations(); track dest.id; let idx = $index) {
              <li
                role="option"
                class="mc-goto-item"
                [class.mc-goto-item-active]="idx === selectedIndex()"
                [attr.aria-selected]="idx === selectedIndex()"
                (mouseenter)="selectedIndex.set(idx)"
                (click)="activateDestination(dest)"
              >
                <span class="mc-goto-item-title">{{ dest.title }}</span>
                <span class="mc-badge mc-badge-slate">{{ dest.group }}</span>
              </li>
            } @empty {
              <li class="mc-goto-empty">
                No matching destination found for "{{ filterQuery() }}".
              </li>
            }
          </ul>

          <div class="mc-modal-footer">
            <span class="mc-goto-footer-hint">
              Enter = Open &bull; Up/Down = Navigate &bull; Esc = Return without losing draft
            </span>
          </div>
        </div>
      </div>
    }
  `,
  styles: `
    .mc-goto-dialog {
      width: min(560px, 94vw);
    }

    .mc-close-icon-btn {
      background: rgba(255, 255, 255, 0.14);
      border: 1px solid rgba(255, 255, 255, 0.25);
      color: var(--mc-color-surface);
      border-radius: var(--mc-radius-xs);
      padding: 0.1rem 0.45rem;
      font-size: var(--mc-font-size-xs);
      cursor: pointer;
    }

    .mc-goto-search-box {
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border-bottom: var(--mc-border-subtle);
    }

    .mc-goto-limit-banner {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-warning-50);
      color: var(--mc-color-warning-700);
      border-bottom: 1px solid var(--mc-color-warning-100);
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
    }

    .mc-goto-list-header {
      padding: 0.3rem 0.875rem;
      background: var(--mc-color-slate-100);
      color: var(--mc-color-slate-700);
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.03em;
    }

    .mc-goto-list {
      list-style: none;
      margin: 0;
      padding: 0;
      max-height: 320px;
      overflow-y: auto;
    }

    .mc-goto-item {
      padding: 0.45rem 0.875rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: 1px solid var(--mc-color-slate-100);
      cursor: pointer;
      font-weight: 500;
    }

    .mc-goto-item-active {
      background: var(--mc-color-verdigris-teal);
      color: var(--mc-color-surface);
    }

    .mc-goto-empty {
      padding: var(--mc-space-4);
      color: var(--mc-color-slate-600);
      text-align: center;
      font-weight: 500;
    }

    .mc-goto-footer-hint {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }
  `,
})
export class GoToPaletteComponent {
  readonly navStack = inject(NavigationStackStore);
  private readonly router = inject(Router);

  readonly isOpen = input.required<boolean>();
  readonly mode = input<'GO_TO' | 'SWITCH_TO'>('GO_TO');
  readonly closed = output<void>();

  private readonly searchInputRef = viewChild<ElementRef<HTMLInputElement>>('searchInput');

  readonly maxDepth = MAX_NAVIGATION_STACK_DEPTH;
  readonly filterQuery = signal<string>('');
  readonly selectedIndex = signal<number>(0);

  readonly filteredDestinations = computed(() => {
    const q = this.filterQuery().trim().toLowerCase();
    if (!q) {
      return GOTO_DESTINATIONS;
    }
    return GOTO_DESTINATIONS.filter(
      (d) => d.title.toLowerCase().includes(q) || d.group.toLowerCase().includes(q),
    );
  });

  constructor() {
    effect(() => {
      if (this.isOpen()) {
        this.filterQuery.set('');
        this.selectedIndex.set(0);
        queueMicrotask(() => {
          this.searchInputRef()?.nativeElement.focus();
        });
      }
    });
  }

  onQueryInput(event: Event): void {
    this.filterQuery.set((event.target as HTMLInputElement).value);
    this.selectedIndex.set(0);
  }

  onKeyDown(event: KeyboardEvent): void {
    const items = this.filteredDestinations();
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.closed.emit();
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
        this.activateDestination(target);
      }
    }
  }

  activateDestination(dest: GoToDestination): void {
    if (this.mode() === 'GO_TO') {
      const pushed = this.navStack.push({
        title: dest.title,
        route: dest.route,
        params: dest.params ?? {},
      });
      if (pushed) {
        this.closed.emit();
      }
      return;
    }

    this.closed.emit();
    void this.router.navigate([dest.route], { queryParams: dest.params });
  }

  onBackdropClick(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.closed.emit();
    }
  }
}
