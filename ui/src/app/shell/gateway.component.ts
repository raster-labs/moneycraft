import {
  ChangeDetectionStrategy,
  Component,
  HostListener,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { isEditableTextElement } from '../core/keyboard/collision-guard';
import { IndianCurrencyPipe } from '../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../shared/components/code-badge/code-badge.component';
import { CompanyContextStore } from '../state/company-context.store';
import { NavigationStackStore } from '../state/navigation-stack.store';

export interface GatewayMenuItem {
  readonly key: string;
  readonly section: 'MASTERS' | 'TRANSACTIONS' | 'UTILITIES' | 'REPORTS';
  readonly prefixText: string;
  readonly mnemonicChar: string;
  readonly suffixText: string;
  readonly panelId: string;
  readonly directRoute?: string;
}

export const GATEWAY_MENU_ITEMS: readonly GatewayMenuItem[] = [
  {
    key: 'C',
    section: 'MASTERS',
    prefixText: '',
    mnemonicChar: 'C',
    suffixText: 'reate',
    panelId: 'masters-create',
    directRoute: '/app/masters?mode=create',
  },
  {
    key: 'A',
    section: 'MASTERS',
    prefixText: '',
    mnemonicChar: 'A',
    suffixText: 'lter',
    panelId: 'masters-alter',
    directRoute: '/app/masters?mode=alter',
  },
  {
    key: 'H',
    section: 'MASTERS',
    prefixText: 'C',
    mnemonicChar: 'H',
    suffixText: 'art of Accounts',
    panelId: 'chart-of-accounts',
    directRoute: '/app/coa',
  },
  {
    key: 'V',
    section: 'TRANSACTIONS',
    prefixText: '',
    mnemonicChar: 'V',
    suffixText: 'ouchers',
    panelId: 'vouchers',
    directRoute: '/app/vouchers/new',
  },
  {
    key: 'K',
    section: 'TRANSACTIONS',
    prefixText: 'Day Boo',
    mnemonicChar: 'K',
    suffixText: '',
    panelId: 'day-book',
    directRoute: '/app/daybook',
  },
  { key: 'N', section: 'UTILITIES', prefixText: 'Ba', mnemonicChar: 'N', suffixText: 'king', panelId: 'banking' },
  { key: 'B', section: 'REPORTS', prefixText: '', mnemonicChar: 'B', suffixText: 'alance Sheet', panelId: 'balance-sheet' },
  { key: 'P', section: 'REPORTS', prefixText: '', mnemonicChar: 'P', suffixText: 'rofit & Loss A/c', panelId: 'profit-loss' },
  { key: 'S', section: 'REPORTS', prefixText: '', mnemonicChar: 'S', suffixText: 'tock Summary', panelId: 'stock-summary' },
  { key: 'R', section: 'REPORTS', prefixText: '', mnemonicChar: 'R', suffixText: 'atio Analysis', panelId: 'ratios' },
  { key: 'D', section: 'REPORTS', prefixText: '', mnemonicChar: 'D', suffixText: 'isplay More Reports', panelId: 'more-reports' },
  { key: 'O', section: 'REPORTS', prefixText: 'Dashb', mnemonicChar: 'O', suffixText: 'ard', panelId: 'dashboard' },
];

@Component({
  selector: 'mc-gateway',
  standalone: true,
  imports: [RouterLink, CodeBadgeComponent, IndianCurrencyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-gateway-grid">
      <!-- Left Summary Card: Active Company & Accounting Readiness -->
      <section class="mc-card mc-gateway-summary" aria-label="Company Books Summary">
        <div class="mc-gateway-section-head">
          <h2>Active Books Context</h2>
          <div class="mc-gateway-quick-links">
            <a routerLink="/app/vouchers/new" class="mc-btn">New Voucher</a>
            <a routerLink="/app/approvals" class="mc-btn">Approvals</a>
            <a routerLink="/app/companies" class="mc-btn">Company Hub (F3)</a>
            <a routerLink="/app/admin" class="mc-btn">Security &amp; Audit</a>
          </div>
        </div>

        @if (companyContext.activeCompany(); as company) {
          <div class="mc-company-kv-grid">
            <div class="mc-kv-item">
              <span class="mc-kv-label">Legal Name</span>
              <span class="mc-kv-value">{{ company.legalName }}</span>
            </div>
            <div class="mc-kv-item">
              <span class="mc-kv-label">Company Code</span>
              <mc-code-badge [code]="company.code" />
            </div>
            <div class="mc-kv-item">
              <span class="mc-kv-label">Financial Year From</span>
              <mc-code-badge [code]="company.financialYearFrom" />
            </div>
            <div class="mc-kv-item">
              <span class="mc-kv-label">Base Currency / Scale</span>
              <span class="mc-kv-value">
                {{ company.baseCurrencyCode }} ({{ company.currencySymbol }}) &bull; {{ company.currencyScale }} decimals
              </span>
            </div>
            <div class="mc-kv-item">
              <span class="mc-kv-label">GSTIN Registration</span>
              @if (company.features.gstEnabled && company.features.gstin) {
                <mc-code-badge [code]="company.features.gstin" />
              } @else {
                <span class="mc-badge mc-badge-slate">Not Configured</span>
              }
            </div>
            <div class="mc-kv-item">
              <span class="mc-kv-label">MCA Edit Log (Audit Trail)</span>
              @if (company.features.mcaEditLogEnforced) {
                <span class="mc-badge mc-badge-jade">Enforced (Immutable)</span>
              } @else {
                <span class="mc-badge mc-badge-slate">Standard</span>
              }
            </div>
          </div>
        } @else {
          <div class="mc-empty-company-callout">
            <p>
              <strong>No active company selected.</strong> Create or select a company to begin
              entering masters and vouchers.
            </p>
            <a routerLink="/app/companies" class="mc-btn mc-btn-primary">
              Create / Select Company (F3)
            </a>
          </div>
        }

        @if (selectedMnemonicItem(); as activeItem) {
          <div class="mc-selected-panel-preview" role="region" aria-live="polite">
            <div class="mc-preview-header">
              <span>Selected Gateway Action: <strong>{{ activeItem.prefixText }}{{ activeItem.mnemonicChar }}{{ activeItem.suffixText }}</strong></span>
              <mc-code-badge [code]="'KEY-' + activeItem.key" />
            </div>
            <p class="mc-preview-desc">
              Use <strong>Alt+G</strong> (Go To) to stack up to 8 non-destructive overlays above any active screen, or press <strong>F3</strong> to manage Company Features &amp; Fiscal Periods.
            </p>
            <div class="mc-preview-sample-row">
              <span>Exact Arithmetic Sample Balance:</span>
              <span class="mc-amount">{{ '500000.00' | mcIndianCurrency }}</span>
            </div>
          </div>
        }
      </section>

      <!-- Right Menu Card: Authentic Gateway of MoneyCraft Mnemonic Menu -->
      <nav class="mc-card mc-gateway-menu-card" aria-label="Gateway of MoneyCraft Menu">
        <div class="mc-gateway-menu-header">Gateway of MoneyCraft</div>

        <div class="mc-gateway-menu-body">
          @for (sec of sections; track sec.id) {
            <div class="mc-gateway-group">
              <div class="mc-gateway-group-title">{{ sec.label }}</div>
              <ul class="mc-gateway-list">
                @for (item of itemsBySection(sec.id); track item.key) {
                  <li>
                    <button
                      type="button"
                      class="mc-gateway-item-btn"
                      [class.mc-gateway-item-active]="activeMnemonicKey() === item.key"
                      (click)="activateMenuItem(item)"
                    >
                      <span>
                        {{ item.prefixText }}<span class="mc-mnemonic-char">{{ item.mnemonicChar }}</span>{{ item.suffixText }}
                      </span>
                      <span class="mc-gateway-hotkey">{{ item.key }}</span>
                    </button>
                  </li>
                }
              </ul>
            </div>
          }
        </div>

        <div class="mc-gateway-menu-footer">
          Press highlighted letter (<strong>C, A, H, V, K, N, B, P, S, R, D</strong>) or Arrow keys + Enter
        </div>
      </nav>
    </div>
  `,
  styles: `
    .mc-gateway-grid {
      display: grid;
      grid-template-columns: minmax(0, 1.35fr) minmax(300px, 380px);
      gap: var(--mc-space-5);
      align-items: start;
    }

    @media (max-width: 960px) {
      .mc-gateway-grid {
        grid-template-columns: 1fr;
      }
    }

    .mc-gateway-summary {
      padding: var(--mc-space-5);
    }

    .mc-gateway-section-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-3);
      padding-bottom: var(--mc-space-3);
      border-bottom: var(--mc-border-subtle);
      margin-bottom: var(--mc-space-4);
    }

    .mc-gateway-quick-links {
      display: flex;
      gap: var(--mc-space-2);
    }

    .mc-company-kv-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    .mc-kv-item {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-sm);
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .mc-kv-label {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 600;
    }

    .mc-kv-value {
      font-weight: 600;
      color: var(--mc-color-slate-900);
    }

    .mc-empty-company-callout {
      padding: var(--mc-space-4);
      background: var(--mc-color-teal-50);
      border: 1px solid var(--mc-color-teal-100);
      border-radius: var(--mc-radius-sm);
    }

    .mc-selected-panel-preview {
      margin-top: var(--mc-space-4);
      padding: var(--mc-space-3);
      background: var(--mc-color-teal-50);
      border-left: 3px solid var(--mc-color-verdigris-teal);
      border-radius: var(--mc-radius-xs);
    }

    .mc-preview-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: var(--mc-space-1);
    }

    .mc-preview-desc {
      margin: 0 0 var(--mc-space-2);
      font-size: var(--mc-font-size-sm);
      color: var(--mc-color-slate-700);
      font-weight: 400;
    }

    .mc-preview-sample-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-top: var(--mc-space-2);
      border-top: 1px solid var(--mc-color-teal-100);
      font-size: var(--mc-font-size-sm);
      font-weight: 500;
    }

    .mc-gateway-menu-card {
      overflow: hidden;
      border: 2px solid var(--mc-color-slate-900);
    }

    .mc-gateway-menu-header {
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      padding: 0.6rem 1rem;
      font-weight: 700;
      text-align: center;
      letter-spacing: 0.02em;
    }

    .mc-gateway-menu-body {
      padding: var(--mc-space-3) var(--mc-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-gateway-group-title {
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      color: var(--mc-color-slate-500);
      letter-spacing: 0.06em;
      margin-bottom: 4px;
    }

    .mc-gateway-list {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .mc-gateway-item-btn {
      width: 100%;
      padding: 0.35rem 0.65rem;
      border: 1px solid transparent;
      border-radius: var(--mc-radius-xs);
      background: transparent;
      color: var(--mc-color-slate-900);
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-weight: 500;
      cursor: pointer;

      &:hover {
        background: var(--mc-color-row-hover);
      }
    }

    .mc-gateway-item-active {
      background: var(--mc-color-verdigris-teal);
      color: var(--mc-color-surface);
      font-weight: 600;

      .mc-mnemonic-char,
      .mc-gateway-hotkey {
        color: var(--mc-color-mint-signal);
      }
    }

    .mc-gateway-hotkey {
      font-family: var(--mc-font-mono);
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-500);
      font-weight: 600;
    }

    .mc-gateway-menu-footer {
      padding: 0.45rem 0.75rem;
      background: var(--mc-color-canvas);
      border-top: var(--mc-border-subtle);
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      text-align: center;
    }
  `,
})
export class GatewayComponent {
  readonly companyContext = inject(CompanyContextStore);
  private readonly navStack = inject(NavigationStackStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly sections: readonly {
    readonly id: GatewayMenuItem['section'];
    readonly label: string;
  }[] = [
    { id: 'MASTERS', label: 'MASTERS' },
    { id: 'TRANSACTIONS', label: 'TRANSACTIONS' },
    { id: 'UTILITIES', label: 'UTILITIES' },
    { id: 'REPORTS', label: 'REPORTS' },
  ];

  readonly activeMnemonicKey = signal<string>('V');

  readonly selectedMnemonicItem = computed(() =>
    GATEWAY_MENU_ITEMS.find((i) => i.key === this.activeMnemonicKey()) ?? GATEWAY_MENU_ITEMS[0],
  );

  constructor() {
    const initialPanel = this.route.snapshot.queryParamMap.get('panel');
    if (initialPanel) {
      const matched = GATEWAY_MENU_ITEMS.find((i) => i.panelId === initialPanel);
      if (matched) {
        this.activeMnemonicKey.set(matched.key);
      }
    }
  }

  itemsBySection(section: GatewayMenuItem['section']): readonly GatewayMenuItem[] {
    return GATEWAY_MENU_ITEMS.filter((item) => item.section === section);
  }

  @HostListener('window:keydown', ['$event'])
  onGatewayMnemonicKey(event: KeyboardEvent): void {
    if (
      event.ctrlKey ||
      event.altKey ||
      event.metaKey ||
      event.isComposing ||
      this.navStack.hasOverlays() ||
      isEditableTextElement(event.target)
    ) {
      return;
    }

    const idx = GATEWAY_MENU_ITEMS.findIndex((i) => i.key === this.activeMnemonicKey());
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      const next = GATEWAY_MENU_ITEMS[(idx + 1) % GATEWAY_MENU_ITEMS.length];
      this.activeMnemonicKey.set(next.key);
      return;
    }

    if (event.key === 'ArrowUp') {
      event.preventDefault();
      const prev =
        GATEWAY_MENU_ITEMS[(idx - 1 + GATEWAY_MENU_ITEMS.length) % GATEWAY_MENU_ITEMS.length];
      this.activeMnemonicKey.set(prev.key);
      return;
    }

    if (event.key === 'Enter') {
      const current = this.selectedMnemonicItem();
      if (current) {
        event.preventDefault();
        this.activateMenuItem(current);
      }
      return;
    }

    if (event.key.length === 1) {
      const upper = event.key.toUpperCase();
      const matched = GATEWAY_MENU_ITEMS.find((i) => i.key === upper);
      if (matched) {
        event.preventDefault();
        this.activateMenuItem(matched);
      }
    }
  }

  activateMenuItem(item: GatewayMenuItem): void {
    this.activeMnemonicKey.set(item.key);
    if (item.directRoute) {
      void this.router.navigateByUrl(item.directRoute);
      return;
    }
    void this.router.navigate(['/app/gateway'], {
      queryParams: { panel: item.panelId },
    });
  }
}
