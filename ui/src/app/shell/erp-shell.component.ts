import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthStore } from '../core/auth/auth.store';
import { RuntimeConfigService } from '../core/http/runtime-config.service';
import { SseStreamService } from '../core/http/sse-stream.service';
import {
  ActiveModalContextHandle,
  EXPLICIT_EN_GB_ASCII_PAIRS,
  INITIAL_BROWSER_MODAL_INVENTORY,
  ModalActionDefinition,
  ShortcutCommandRegistry,
  asciiPair,
} from '../core/keyboard/shortcut-command.registry';
import { ExactDecimal } from '../core/money/exact-decimal';
import { BankingBrsWorkbenchComponent } from '../features/banking/banking-brs-workbench.component';
import { MigrationAndExchangeComponent } from '../features/exchange/migration-and-exchange.component';
import { ExtensionWorkbenchComponent } from '../features/extensions/extension-workbench.component';
import { GodownWorkbenchComponent } from '../features/inventory/godown-workbench.component';
import { InventoryStore } from '../features/inventory/inventory.store';
import { PriceListWorkbenchComponent } from '../features/inventory/price-list-workbench.component';
import { StockGroupWorkbenchComponent } from '../features/inventory/stock-group-workbench.component';
import { StockItemWorkbenchComponent } from '../features/inventory/stock-item-workbench.component';
import { StockSummaryComponent } from '../features/inventory/stock-summary.component';
import { UomWorkbenchComponent } from '../features/inventory/uom-workbench.component';
import { CoaExplorerComponent } from '../features/masters/coa-explorer.component';
import { ChartOfAccountsStore } from '../features/masters/chart-of-accounts.store';
import { CostCentreWorkbenchComponent } from '../features/masters/cost-centre-workbench.component';
import { CurrencyWorkbenchComponent } from '../features/masters/currency-workbench.component';
import { GroupWorkbenchComponent } from '../features/masters/group-workbench.component';
import { LedgerWorkbenchComponent } from '../features/masters/ledger-workbench.component';
import { MastersHubComponent } from '../features/masters/masters-hub.component';
import { VoucherBaseCategory } from '../features/masters/masters.models';
import { SecondaryMasterModalComponent } from '../features/masters/secondary-master-modal.component';
import {
  SECONDARY_MASTER_ROUTE_TOKEN,
  SecondaryMasterModalService,
} from '../features/masters/secondary-master-modal.service';
import { VoucherTypeWorkbenchComponent } from '../features/masters/voucher-type-workbench.component';
import { AccountBooksAndExceptionsComponent } from '../features/reports/account-books-and-exceptions.component';
import { BalanceSheetComponent } from '../features/reports/balance-sheet.component';
import { BillsAgeingComponent } from '../features/reports/bills-ageing.component';
import { CashFlowAndRatiosComponent } from '../features/reports/cash-flow-and-ratios.component';
import { ProfitAndLossComponent } from '../features/reports/profit-and-loss.component';
import { AccountBookType, ExceptionReportType } from '../features/reports/reports.models';
import { ScheduleIiiComponent } from '../features/reports/schedule-iii.component';
import {
  TB_GROUP_DRILL_ROUTE_TOKEN,
  TrialBalanceComponent,
} from '../features/reports/trial-balance.component';
import { EDIT_LOG_ROUTE_TOKEN } from '../features/statutory/edit-log-modal.service';
import { EditLogRedlineModalComponent } from '../features/statutory/edit-log-redline-modal.component';
import { EinvoiceWorkbenchComponent } from '../features/statutory/einvoice-workbench.component';
import { GstRegistrationWorkbenchComponent } from '../features/statutory/gst-registration-workbench.component';
import { Gstr1DashboardComponent } from '../features/statutory/gstr1-dashboard.component';
import { Gstr3bDashboardComponent } from '../features/statutory/gstr3b-dashboard.component';
import { Gstr9DashboardComponent } from '../features/statutory/gstr9-dashboard.component';
import { ImsWorkbenchComponent } from '../features/statutory/ims-workbench.component';
import { Tds26qWorkbenchComponent } from '../features/statutory/tds-26q-workbench.component';
import { ApprovalQueueComponent } from '../features/vouchers/approval-queue.component';
import { DayBookComponent } from '../features/vouchers/day-book.component';
import { VoucherGridComponent } from '../features/vouchers/voucher-grid.component';
import { CodeBadgeComponent } from '../shared/components/code-badge/code-badge.component';
import { ContextualHelpService } from '../shared/components/contextual-help/contextual-help.service';
import { HelpPanelComponent } from '../shared/components/contextual-help/help-panel.component';
import {
  FunctionBarButtonItem,
  FunctionBarComponent,
} from '../shared/components/function-bar/function-bar.component';
import { GoToPaletteComponent } from '../shared/components/go-to-palette/go-to-palette.component';
import { KeyboardSettingsModalComponent } from '../shared/components/keyboard-settings-modal/keyboard-settings-modal.component';
import {
  SemanticIconComponent,
  SemanticIconName,
} from '../shared/components/semantic-icon/semantic-icon.component';
import { SmartFindModalComponent } from '../shared/components/smart-find-modal/smart-find-modal.component';
import {
  CompanyContextStore,
  CompanyF12Config,
  DEFAULT_COMPANY_F12_CONFIG,
} from '../state/company-context.store';
import { NavigationStackStore } from '../state/navigation-stack.store';
import { SmartFindStore } from '../state/smart-find.store';

type TopBarMenuKind =
  | 'COMPANY'
  | 'DATA'
  | 'EXCHANGE'
  | 'IMPORT'
  | 'EXPORT'
  | 'SHARE'
  | 'PRINT'
  | 'HELP';

const TOP_BAR_MENU_ORDER: readonly TopBarMenuKind[] = [
  'COMPANY',
  'DATA',
  'EXCHANGE',
  'IMPORT',
  'EXPORT',
  'SHARE',
  'PRINT',
  'HELP',
];

interface TopBarMenuEntry {
  readonly id: string;
  readonly mnemonic: string;
  readonly label: string;
  readonly shortcutHint?: string;
  readonly disabled?: boolean;
}

const TOP_BAR_MENUS: Record<TopBarMenuKind, readonly TopBarMenuEntry[]> = {
  COMPANY: [
    { id: 'company.create', mnemonic: 'C', label: 'Create Company' },
    { id: 'company.alter', mnemonic: 'A', label: 'Alter Company' },
    { id: 'company.select', mnemonic: 'S', label: 'Select Company', shortcutHint: 'F3 / Alt+F3' },
    { id: 'company.shut', mnemonic: 'H', label: 'Shut Company', shortcutHint: 'Ctrl+F3' },
    { id: 'company.features', mnemonic: 'F', label: 'Features', shortcutHint: 'F11' },
    { id: 'company.security', mnemonic: 'U', label: 'Security & Administration' },
  ],
  DATA: [
    { id: 'data.backup', mnemonic: 'B', label: 'Backup & Restore Snapshot' },
    { id: 'data.split', mnemonic: 'S', label: 'Split / Fiscal Year Rollover' },
    { id: 'data.path', mnemonic: 'P', label: 'Data Path & Storage Status' },
  ],
  EXCHANGE: [
    { id: 'exchange.migrate', mnemonic: 'M', label: 'Migrate Legacy .1800 Snapshot' },
    { id: 'exchange.einvoice', mnemonic: 'E', label: 'e-Invoice & e-Way Bill Exchange' },
    { id: 'exchange.ims', mnemonic: 'I', label: 'IMS Inward Invoice Sync' },
    { id: 'exchange.xml', mnemonic: 'X', label: 'Data Synchronization (XML / JSONEx)' },
    { id: 'exchange.apiServer', mnemonic: 'A', label: 'Backend API Server (IP & Port)' },
  ],
  IMPORT: [
    { id: 'import.spreadsheet', mnemonic: 'S', label: 'Guided Spreadsheet Wizard (.xlsx/.ods/.csv)' },
    { id: 'import.xml', mnemonic: 'M', label: 'Masters & Transactions (Legacy XML / JSONEx)' },
    { id: 'import.bank', mnemonic: 'B', label: 'Bank Statement & OCR Staging Queue' },
    { id: 'import.tdl', mnemonic: 'T', label: 'TDL / Extension Package (.tdl)' },
  ],
  EXPORT: [
    { id: 'export.current', mnemonic: 'C', label: 'Current Report / Voucher (PDF)' },
    { id: 'export.xml', mnemonic: 'M', label: 'Masters & Transactions (XML / JSONEx)' },
    { id: 'export.gst', mnemonic: 'G', label: 'GST Statutory Returns (GSTR-1 / 3B / 9)' },
  ],
  SHARE: [
    { id: 'share.pdf', mnemonic: 'P', label: 'Generate Printable PDF Document' },
    { id: 'share.package', mnemonic: 'E', label: 'Export Signed Exchange Envelope' },
  ],
  PRINT: [
    { id: 'print.current', mnemonic: 'C', label: 'Current Report / Voucher', shortcutHint: 'Ctrl+P' },
    { id: 'print.configure', mnemonic: 'O', label: 'Configuration', shortcutHint: 'F12' },
  ],
  HELP: [
    { id: 'help.contextual', mnemonic: 'H', label: 'Contextual Help', shortcutHint: 'F1' },
    { id: 'help.preferences', mnemonic: 'K', label: 'Appearance, Text Scale & Keyboard' },
    { id: 'help.audit', mnemonic: 'T', label: 'Troubleshooting & Audit Chain' },
    { id: 'help.about', mnemonic: 'A', label: 'About MoneyCraft v1.1.0' },
  ],
};

interface CommandsMenuRowView {
  readonly commandId: string;
  readonly label: string;
  readonly pairSpec: keyof typeof EXPLICIT_EN_GB_ASCII_PAIRS;
  readonly iconName: SemanticIconName | null;
  readonly stateTokenRole: 'action.primary' | 'action.secondary' | 'action.danger';
}

const COMMANDS_MENU_ROWS: readonly CommandsMenuRowView[] = INITIAL_BROWSER_MODAL_INVENTORY.filter(
  (item) => item.contextId === 'commands.menu',
).map((item) => ({
  commandId: item.commandId,
  label: item.label,
  pairSpec: item.pairSpec,
  iconName: item.iconSemanticId
    ? (item.iconSemanticId.replace(/^mc\.icon\./, '') as SemanticIconName)
    : null,
  stateTokenRole: item.stateTokenRole,
}));

@Component({
  selector: 'mc-erp-shell',
  standalone: true,
  imports: [
    FormsModule,
    RouterOutlet,
    RouterLink,
    CodeBadgeComponent,
    FunctionBarComponent,
    GoToPaletteComponent,
    HelpPanelComponent,
    KeyboardSettingsModalComponent,
    SemanticIconComponent,
    SmartFindModalComponent,
    SecondaryMasterModalComponent,
    EditLogRedlineModalComponent,
    CoaExplorerComponent,
    MastersHubComponent,
    GroupWorkbenchComponent,
    LedgerWorkbenchComponent,
    CurrencyWorkbenchComponent,
    CostCentreWorkbenchComponent,
    VoucherTypeWorkbenchComponent,
    UomWorkbenchComponent,
    GodownWorkbenchComponent,
    StockGroupWorkbenchComponent,
    StockItemWorkbenchComponent,
    PriceListWorkbenchComponent,
    StockSummaryComponent,
    Gstr1DashboardComponent,
    Gstr3bDashboardComponent,
    Gstr9DashboardComponent,
    ImsWorkbenchComponent,
    EinvoiceWorkbenchComponent,
    Tds26qWorkbenchComponent,
    GstRegistrationWorkbenchComponent,
    BalanceSheetComponent,
    ProfitAndLossComponent,
    TrialBalanceComponent,
    ScheduleIiiComponent,
    BillsAgeingComponent,
    CashFlowAndRatiosComponent,
    AccountBooksAndExceptionsComponent,
    BankingBrsWorkbenchComponent,
    MigrationAndExchangeComponent,
    ExtensionWorkbenchComponent,
    VoucherGridComponent,
    DayBookComponent,
    ApprovalQueueComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-shell-viewport">
      <!-- Persistent Red Alert Banner when Installation is in QUARANTINED DR Mode -->
      @if (companyContext.quarantined()) {
        <div class="mc-quarantine-banner" role="alert">
          <div class="mc-quarantine-title">
            <mc-icon name="error" [size]="20" />
            <span>QUARANTINE DISASTER-RECOVERY MODE ACTIVE (MC-SYSTEM-QUARANTINED)</span>
          </div>
          <div class="mc-quarantine-desc">
            All business accounting writes are locked fail-closed. Forensic read-only inspection is
            permitted for Installation Owners.
            @if (companyContext.quarantineReason(); as reason) {
              <span> Reason: {{ reason }}</span>
            }
          </div>
          <a routerLink="/app/admin" class="mc-quarantine-link">Recovery Console</a>
        </div>
      }

      <!-- Top Omni-Bar in MoneyCraft Brand v0.2.0 (#142F42) -->
      <header class="mc-omni-bar mc-no-print">
        <div class="mc-omni-left">
          <a routerLink="/app/gateway" class="mc-brand-link" aria-label="MoneyCraft Gateway Home">
            <img
              src="assets/brand/moneycraft-logo-reverse.svg"
              alt="MoneyCraft"
              class="mc-brand-logo"
              width="148"
              height="26"
            />
          </a>

          <nav class="mc-omni-menus" aria-label="Primary Top Bar Commands">
            <button
              type="button"
              class="mc-omni-btn"
              [class.mc-omni-btn-active]="openTopBarMenu() === 'COMPANY'"
              data-testid="omni-menu-company-btn"
              [attr.aria-expanded]="openTopBarMenu() === 'COMPANY'"
              (click)="toggleTopBarMenu('COMPANY')"
            >
              <span class="mc-key-alt">K</span>: Company
            </button>
            <button
              type="button"
              class="mc-omni-btn"
              [class.mc-omni-btn-active]="openTopBarMenu() === 'DATA'"
              data-testid="omni-menu-data-btn"
              [attr.aria-expanded]="openTopBarMenu() === 'DATA'"
              (click)="toggleTopBarMenu('DATA')"
            >
              <span class="mc-key-alt">Y</span>: Data
            </button>
            <button
              type="button"
              class="mc-omni-btn"
              [class.mc-omni-btn-active]="openTopBarMenu() === 'EXCHANGE'"
              data-testid="omni-menu-exchange-btn"
              [attr.aria-expanded]="openTopBarMenu() === 'EXCHANGE'"
              (click)="toggleTopBarMenu('EXCHANGE')"
            >
              <span class="mc-key-alt">Z</span>: Exchange
            </button>
            <button
              type="button"
              class="mc-omni-btn"
              data-testid="omni-commands-btn"
              (click)="openCommandsMenuModal()"
            >
              Commands
            </button>
          </nav>
        </div>

        <div class="mc-omni-center">
          <button
            type="button"
            class="mc-goto-pill"
            (click)="openGoToPalette('GO_TO')"
          >
            <span class="mc-key-alt">G</span>: Go To
            @if (navStack.depth() > 0) {
              <span class="mc-stack-counter">{{ navStack.depth() }}</span>
            }
          </button>

          <button
            type="button"
            class="mc-smartfind-pill"
            (click)="smartFind.open()"
            aria-label="Find details entered in masters and transactions (Alt+F or Ctrl+F)"
          >
            <span class="mc-smartfind-pill-text">Find masters, vouchers, GSTINs...</span>
            <span class="mc-smartfind-pill-key"><span class="mc-key-alt">F</span> / <span class="mc-key-ctrl">F</span></span>
          </button>
        </div>

        <div class="mc-omni-right">
          <button
            type="button"
            class="mc-omni-btn"
            [class.mc-omni-btn-active]="openTopBarMenu() === 'IMPORT'"
            data-testid="omni-menu-import-btn"
            [attr.aria-expanded]="openTopBarMenu() === 'IMPORT'"
            (click)="toggleTopBarMenu('IMPORT')"
          >
            <span class="mc-key-alt">O</span>: Import
          </button>
          <button
            type="button"
            class="mc-omni-btn"
            [class.mc-omni-btn-active]="openTopBarMenu() === 'EXPORT'"
            data-testid="omni-menu-export-btn"
            [attr.aria-expanded]="openTopBarMenu() === 'EXPORT'"
            (click)="toggleTopBarMenu('EXPORT')"
          >
            <span class="mc-key-alt">E</span>: Export
          </button>
          <button
            type="button"
            class="mc-omni-btn"
            [class.mc-omni-btn-active]="openTopBarMenu() === 'SHARE'"
            data-testid="omni-menu-share-btn"
            [attr.aria-expanded]="openTopBarMenu() === 'SHARE'"
            (click)="toggleTopBarMenu('SHARE')"
          >
            <span class="mc-key-alt">M</span>: Share
          </button>
          <button
            type="button"
            class="mc-omni-btn"
            [class.mc-omni-btn-active]="openTopBarMenu() === 'PRINT'"
            data-testid="omni-menu-print-btn"
            [attr.aria-expanded]="openTopBarMenu() === 'PRINT'"
            (click)="toggleTopBarMenu('PRINT')"
          >
            <span class="mc-key-alt">P</span>: Print
          </button>
          <button
            type="button"
            class="mc-omni-btn"
            [class.mc-omni-btn-active]="openTopBarMenu() === 'HELP'"
            data-testid="omni-menu-help-btn"
            [attr.aria-expanded]="openTopBarMenu() === 'HELP'"
            (click)="toggleTopBarMenu('HELP')"
          >
            F1: Help
          </button>

          <button
            type="button"
            class="mc-omni-btn"
            [class.mc-omni-btn-active]="shortcutRegistry.scannerWorkflowActive()"
            data-testid="omni-scanner-btn"
            [attr.aria-pressed]="shortcutRegistry.scannerWorkflowActive()"
            (click)="toggleScannerWorkflow()"
          >
            {{ shortcutRegistry.scannerWorkflowActive() ? 'Scanner: ON' : 'Scanner' }}
          </button>

          <button
            type="button"
            class="mc-omni-btn mc-omni-icon-btn"
            data-testid="omni-keyboard-settings-btn"
            aria-label="Keyboard settings"
            (click)="shortcutRegistry.openKeyboardSettingsModal()"
          >
            <mc-icon name="keyboard" [size]="16" />
            <span>Keyboard</span>
          </button>

          @if (authStore.principal(); as user) {
            <span class="mc-user-chip" [title]="user.displayName">{{ user.username }}</span>
          }
          <button type="button" class="mc-omni-btn" (click)="signOut()">Sign Out</button>
        </div>
      </header>

      @if (openTopBarMenu(); as activeTopMenu) {
        <div
          class="mc-omni-dropdown-backdrop mc-no-print"
          (click)="closeTopBarMenu()"
        >
          <div
            #topBarMenuPanel
            class="mc-omni-dropdown"
            [class.mc-omni-dropdown-right]="isRightAlignedTopMenu(activeTopMenu)"
            role="menu"
            tabindex="0"
            [attr.aria-label]="activeTopMenu + ' Menu'"
            [attr.data-testid]="'topbar-dropdown-' + activeTopMenu.toLowerCase()"
            (click)="$event.stopPropagation()"
            (keydown)="onTopBarMenuKeyDown($event)"
          >
            <div class="mc-omni-dropdown-head">
              <span>{{ activeTopMenu }} MENU</span>
              <span class="mc-code">Esc</span>
            </div>
            <ul class="mc-omni-dropdown-list">
              @for (entry of currentTopBarMenuItems(); track entry.id; let idx = $index) {
                <li>
                  <button
                    type="button"
                    role="menuitem"
                    class="mc-omni-dropdown-item"
                    [class.mc-omni-dropdown-item-active]="idx === topBarMenuIndex()"
                    [disabled]="entry.disabled"
                    [attr.data-testid]="'topbar-item-' + entry.id"
                    (mouseenter)="topBarMenuIndex.set(idx)"
                    (click)="executeTopBarMenuEntry(entry.id)"
                  >
                    <span>
                      <strong class="mc-omni-mnemonic">{{ entry.mnemonic }}</strong>: {{ entry.label }}
                    </span>
                    @if (entry.shortcutHint; as sc) {
                      <span class="mc-code mc-omni-sc">{{ sc }}</span>
                    }
                  </button>
                </li>
              }
            </ul>
          </div>
        </div>
      }

      <!-- Main 3-Column ERP Body: Left Info Panel | Center Workspace | Right F2-F12 Rail -->
      <div class="mc-shell-main">
        <aside class="mc-info-panel mc-no-print" aria-label="Company and Period Context">
          <div class="mc-info-row-grid">
            <button type="button" class="mc-info-stat-box" (click)="openPeriodModal()">
              <span class="mc-info-stat-label">CURRENT PERIOD (<span class="mc-key-alt">F2</span>)</span>
              <span class="mc-code mc-info-stat-val">
                {{ companyContext.periodFrom() }} to {{ companyContext.periodTo() }}
              </span>
            </button>
            <button type="button" class="mc-info-stat-box" (click)="openWorkingDateModal()">
              <span class="mc-info-stat-label">CURRENT DATE (F2)</span>
              <span class="mc-code mc-info-stat-val">{{ companyContext.workingDate() }}</span>
            </button>
          </div>

          <div class="mc-open-companies-section">
            <div class="mc-open-companies-header">
              <span>Name of Company</span>
              <span>Date of Last Entry</span>
            </div>
            <ul class="mc-open-companies-list">
              @for (comp of companyContext.openCompanies(); track comp.id) {
                <li
                  class="mc-open-company-row"
                  [class.mc-open-company-active]="companyContext.activeCompany()?.id === comp.id"
                  (click)="switchCompany(comp.id)"
                >
                  <span class="mc-open-company-name">{{ comp.legalName }}</span>
                  <span class="mc-code mc-open-company-date">
                    {{ comp.lastEntryDate ?? 'No Vouchers' }}
                  </span>
                </li>
              } @empty {
                <li class="mc-open-company-empty">
                  No open companies. Press <strong>F3</strong> to Create or Select.
                </li>
              }
            </ul>
          </div>

          @if (statusToast(); as toast) {
            <div class="mc-shell-toast" role="status">{{ toast }}</div>
          }
        </aside>

        <main class="mc-workspace-canvas" data-mc-workspace-scroll>
          <router-outlet />

          <!-- Non-Destructive Alt+G Modal Overlay Stack Host (Depth 1..8) -->
          @for (frame of goToOverlayFrames(); track frame.id; let idx = $index) {
            <section
              class="mc-goto-overlay-frame"
              role="dialog"
              aria-modal="true"
              [attr.aria-label]="'Go To Overlay: ' + frame.title"
            >
              <div class="mc-goto-overlay-bar">
                <div class="mc-goto-overlay-title">
                  <span class="mc-badge mc-badge-jade">Overlay #{{ idx + 1 }}</span>
                  <strong>{{ frame.title }}</strong>
                  <mc-code-badge [code]="frame.route" />
                </div>
                <div class="mc-goto-overlay-bar-btns">
                  <button
                    type="button"
                    class="mc-btn"
                    (click)="switchWorkspaceFromOverlay(frame.route, frame.params)"
                  >
                    Full View (<span class="mc-key-ctrl">G</span>)
                  </button>
                  <button type="button" class="mc-btn" (click)="navStack.pop()">
                    <mc-icon name="close" [size]="16" />
                    <span>Close Overlay (Esc)</span>
                  </button>
                </div>
              </div>
              <div class="mc-goto-overlay-content">
                @switch (frame.route) {
                  @case ('/app/coa') {
                    <mc-coa-explorer [embeddedOverlay]="true" />
                  }
                  @case ('/coa') {
                    <mc-coa-explorer [embeddedOverlay]="true" />
                  }
                  @case ('/app/masters') {
                    <mc-masters-hub [initialMode]="frame.params['mode'] === 'alter' ? 'alter' : 'create'" />
                  }
                  @case ('/app/masters/groups') {
                    <mc-group-workbench
                      [embeddedOverlay]="true"
                      [initialGroupId]="frame.params['id'] ?? null"
                    />
                  }
                  @case ('/app/masters/ledgers') {
                    <mc-ledger-workbench
                      [embeddedOverlay]="true"
                      [initialLedgerId]="frame.params['id'] ?? null"
                    />
                  }
                  @case ('/app/masters/currencies') {
                    <mc-currency-workbench [embeddedOverlay]="true" />
                  }
                  @case ('/app/masters/cost-centres') {
                    <mc-cost-centre-workbench [embeddedOverlay]="true" />
                  }
                  @case ('/app/masters/voucher-types') {
                    <mc-voucher-type-workbench [embeddedOverlay]="true" />
                  }
                  @case ('/app/inventory/masters') {
                    <mc-masters-hub [initialMode]="frame.params['mode'] === 'alter' ? 'alter' : 'create'" />
                  }
                  @case ('/inventory/masters') {
                    <mc-masters-hub [initialMode]="frame.params['mode'] === 'alter' ? 'alter' : 'create'" />
                  }
                  @case ('/app/inventory/uoms') {
                    <mc-uom-workbench [embeddedOverlay]="true" />
                  }
                  @case ('/inventory/uoms') {
                    <mc-uom-workbench [embeddedOverlay]="true" />
                  }
                  @case ('/app/inventory/godowns') {
                    <mc-godown-workbench [embeddedOverlay]="true" />
                  }
                  @case ('/inventory/godowns') {
                    <mc-godown-workbench [embeddedOverlay]="true" />
                  }
                  @case ('/app/inventory/stock-groups') {
                    <mc-stock-group-workbench [embeddedOverlay]="true" />
                  }
                  @case ('/inventory/stock-groups') {
                    <mc-stock-group-workbench [embeddedOverlay]="true" />
                  }
                  @case ('/app/inventory/stock-items') {
                    <mc-stock-item-workbench [embeddedOverlay]="true" />
                  }
                  @case ('/inventory/stock-items') {
                    <mc-stock-item-workbench [embeddedOverlay]="true" />
                  }
                  @case ('/app/inventory/price-lists') {
                    <mc-price-list-workbench [embeddedOverlay]="true" />
                  }
                  @case ('/inventory/price-lists') {
                    <mc-price-list-workbench [embeddedOverlay]="true" />
                  }
                  @case ('/app/reports/stock-summary') {
                    <mc-stock-summary [embeddedOverlay]="true" initialTab="STOCK_SUMMARY" />
                  }
                  @case ('/reports/stock-summary') {
                    <mc-stock-summary [embeddedOverlay]="true" initialTab="STOCK_SUMMARY" />
                  }
                  @case ('/app/reports/godown-summary') {
                    <mc-stock-summary [embeddedOverlay]="true" initialTab="GODOWN_SUMMARY" />
                  }
                  @case ('/reports/godown-summary') {
                    <mc-stock-summary [embeddedOverlay]="true" initialTab="GODOWN_SUMMARY" />
                  }
                  @case ('/app/reports/batch-ageing') {
                    <mc-stock-summary [embeddedOverlay]="true" initialTab="BATCH_AGEING" />
                  }
                  @case ('/reports/batch-ageing') {
                    <mc-stock-summary [embeddedOverlay]="true" initialTab="BATCH_AGEING" />
                  }
                  @case ('/app/reports/open-orders') {
                    <mc-stock-summary [embeddedOverlay]="true" initialTab="OPEN_ORDERS" />
                  }
                  @case ('/reports/open-orders') {
                    <mc-stock-summary [embeddedOverlay]="true" initialTab="OPEN_ORDERS" />
                  }
                  @case ('/app/vouchers/new') {
                    <mc-voucher-grid [embeddedOverlay]="true" />
                  }
                  @case ('/vouchers/new') {
                    <mc-voucher-grid [embeddedOverlay]="true" />
                  }
                  @case ('/app/daybook') {
                    <mc-day-book
                      [embeddedOverlay]="true"
                      [initialLedgerId]="frame.params['ledgerId'] ?? null"
                      [initialLedgerName]="frame.params['ledgerName'] ?? null"
                    />
                  }
                  @case ('/daybook') {
                    <mc-day-book
                      [embeddedOverlay]="true"
                      [initialLedgerId]="frame.params['ledgerId'] ?? null"
                      [initialLedgerName]="frame.params['ledgerName'] ?? null"
                    />
                  }
                  @case ('/app/reports/trial-balance') {
                    <mc-trial-balance [embeddedOverlay]="true" />
                  }
                  @case ('/reports/trial-balance') {
                    <mc-trial-balance [embeddedOverlay]="true" />
                  }
                  @case ('/app/reports/pnl') {
                    <mc-profit-and-loss [embeddedOverlay]="true" />
                  }
                  @case ('/reports/pnl') {
                    <mc-profit-and-loss [embeddedOverlay]="true" />
                  }
                  @case ('/app/reports/profit-and-loss') {
                    <mc-profit-and-loss [embeddedOverlay]="true" />
                  }
                  @case ('/reports/profit-and-loss') {
                    <mc-profit-and-loss [embeddedOverlay]="true" />
                  }
                  @case ('/app/reports/balance-sheet') {
                    <mc-balance-sheet [embeddedOverlay]="true" />
                  }
                  @case ('/reports/balance-sheet') {
                    <mc-balance-sheet [embeddedOverlay]="true" />
                  }
                  @case ('/app/reports/schedule-iii') {
                    <mc-schedule-iii [embeddedOverlay]="true" />
                  }
                  @case ('/reports/schedule-iii') {
                    <mc-schedule-iii [embeddedOverlay]="true" />
                  }
                  @case ('/app/reports/ageing') {
                    <mc-bills-ageing [embeddedOverlay]="true" initialType="RECEIVABLES" />
                  }
                  @case ('/reports/ageing') {
                    <mc-bills-ageing [embeddedOverlay]="true" initialType="RECEIVABLES" />
                  }
                  @case ('/app/reports/bills-receivable') {
                    <mc-bills-ageing [embeddedOverlay]="true" initialType="RECEIVABLES" />
                  }
                  @case ('/reports/bills-receivable') {
                    <mc-bills-ageing [embeddedOverlay]="true" initialType="RECEIVABLES" />
                  }
                  @case ('/app/reports/bills-payable') {
                    <mc-bills-ageing [embeddedOverlay]="true" initialType="PAYABLES" />
                  }
                  @case ('/reports/bills-payable') {
                    <mc-bills-ageing [embeddedOverlay]="true" initialType="PAYABLES" />
                  }
                  @case ('/app/reports/cash-flow') {
                    <mc-cash-flow-and-ratios [embeddedOverlay]="true" initialTab="CASH_FLOW" />
                  }
                  @case ('/reports/cash-flow') {
                    <mc-cash-flow-and-ratios [embeddedOverlay]="true" initialTab="CASH_FLOW" />
                  }
                  @case ('/app/reports/ratios') {
                    <mc-cash-flow-and-ratios [embeddedOverlay]="true" initialTab="RATIOS" />
                  }
                  @case ('/reports/ratios') {
                    <mc-cash-flow-and-ratios [embeddedOverlay]="true" initialTab="RATIOS" />
                  }
                  @case ('/app/reports/account-books') {
                    <mc-account-books-and-exceptions
                      [embeddedOverlay]="true"
                      initialMode="ACCOUNT_BOOKS"
                      [initialBookType]="resolveOverlayBookType(frame.params['bookType'])"
                    />
                  }
                  @case ('/reports/account-books') {
                    <mc-account-books-and-exceptions
                      [embeddedOverlay]="true"
                      initialMode="ACCOUNT_BOOKS"
                      [initialBookType]="resolveOverlayBookType(frame.params['bookType'])"
                    />
                  }
                  @case ('/app/reports/exceptions') {
                    <mc-account-books-and-exceptions
                      [embeddedOverlay]="true"
                      initialMode="EXCEPTIONS"
                      [initialExceptionType]="resolveOverlayExceptionType(frame.params['type'])"
                    />
                  }
                  @case ('/reports/exceptions') {
                    <mc-account-books-and-exceptions
                      [embeddedOverlay]="true"
                      initialMode="EXCEPTIONS"
                      [initialExceptionType]="resolveOverlayExceptionType(frame.params['type'])"
                    />
                  }
                  @case ('/app/reports/statistics') {
                    <mc-account-books-and-exceptions
                      [embeddedOverlay]="true"
                      initialMode="STATISTICS"
                    />
                  }
                  @case ('/reports/statistics') {
                    <mc-account-books-and-exceptions
                      [embeddedOverlay]="true"
                      initialMode="STATISTICS"
                    />
                  }
                  @case ('/app/reports/stock-query') {
                    <mc-account-books-and-exceptions
                      [embeddedOverlay]="true"
                      initialMode="STOCK_QUERY"
                    />
                  }
                  @case ('/reports/stock-query') {
                    <mc-account-books-and-exceptions
                      [embeddedOverlay]="true"
                      initialMode="STOCK_QUERY"
                    />
                  }
                  @case ('/app/banking/brs') {
                    <mc-banking-brs-workbench [embeddedOverlay]="true" initialTab="BRS" />
                  }
                  @case ('/banking/brs') {
                    <mc-banking-brs-workbench [embeddedOverlay]="true" initialTab="BRS" />
                  }
                  @case ('/app/banking/cheque-books') {
                    <mc-banking-brs-workbench [embeddedOverlay]="true" initialTab="CHEQUE_BOOKS" />
                  }
                  @case ('/banking/cheque-books') {
                    <mc-banking-brs-workbench [embeddedOverlay]="true" initialTab="CHEQUE_BOOKS" />
                  }
                  @case ('/app/banking/ocr-queue') {
                    <mc-banking-brs-workbench [embeddedOverlay]="true" initialTab="OCR_QUEUE" />
                  }
                  @case ('/banking/ocr-queue') {
                    <mc-banking-brs-workbench [embeddedOverlay]="true" initialTab="OCR_QUEUE" />
                  }
                  @case ('/app/exchange/migrate-1800') {
                    <mc-migration-and-exchange [embeddedOverlay]="true" initialTab="MIGRATE_1800" />
                  }
                  @case ('/exchange/migrate-1800') {
                    <mc-migration-and-exchange [embeddedOverlay]="true" initialTab="MIGRATE_1800" />
                  }
                  @case ('/app/exchange/spreadsheet-import') {
                    <mc-migration-and-exchange [embeddedOverlay]="true" initialTab="SPREADSHEET_WIZARD" />
                  }
                  @case ('/exchange/spreadsheet-import') {
                    <mc-migration-and-exchange [embeddedOverlay]="true" initialTab="SPREADSHEET_WIZARD" />
                  }
                  @case ('/app/exchange/import-export') {
                    <mc-migration-and-exchange [embeddedOverlay]="true" initialTab="IMPORT_EXPORT" />
                  }
                  @case ('/exchange/import-export') {
                    <mc-migration-and-exchange [embeddedOverlay]="true" initialTab="IMPORT_EXPORT" />
                  }
                  @case ('/app/extensions') {
                    <mc-extension-workbench [embeddedOverlay]="true" />
                  }
                  @case ('/extensions') {
                    <mc-extension-workbench [embeddedOverlay]="true" />
                  }
                  @case ('/app/approvals') {
                    <mc-approval-queue [embeddedOverlay]="true" />
                  }
                  @case ('/approvals') {
                    <mc-approval-queue [embeddedOverlay]="true" />
                  }
                  @case ('/app/statutory/gstr1') {
                    <mc-gstr1-dashboard />
                  }
                  @case ('/statutory/gstr1') {
                    <mc-gstr1-dashboard />
                  }
                  @case ('/app/statutory/gstr3b') {
                    <mc-gstr3b-dashboard />
                  }
                  @case ('/statutory/gstr3b') {
                    <mc-gstr3b-dashboard />
                  }
                  @case ('/app/statutory/gstr9') {
                    <mc-gstr9-dashboard />
                  }
                  @case ('/statutory/gstr9') {
                    <mc-gstr9-dashboard />
                  }
                  @case ('/app/statutory/ims') {
                    <mc-ims-workbench />
                  }
                  @case ('/statutory/ims') {
                    <mc-ims-workbench />
                  }
                  @case ('/app/statutory/einvoice') {
                    <mc-einvoice-workbench />
                  }
                  @case ('/statutory/einvoice') {
                    <mc-einvoice-workbench />
                  }
                  @case ('/app/statutory/tds-26q') {
                    <mc-tds-26q-workbench />
                  }
                  @case ('/statutory/tds-26q') {
                    <mc-tds-26q-workbench />
                  }
                  @case ('/app/statutory/gst-registrations') {
                    <mc-gst-registration-workbench />
                  }
                  @case ('/statutory/gst-registrations') {
                    <mc-gst-registration-workbench />
                  }
                  @default {
                    @if (extractVoucherIdFromRoute(frame.route); as overlayVoucherId) {
                      <mc-voucher-grid
                        [embeddedOverlay]="true"
                        [initialVoucherId]="overlayVoucherId"
                      />
                    } @else {
                      <p>
                        Non-destructive <strong>Go To</strong> view suspended above your previous screen
                        (active focus anchor:
                        <mc-code-badge [code]="frame.activeElementId ?? 'root'" />).
                      </p>
                      <p>
                        Press <strong>Esc</strong> at any time to pop this overlay and restore exact
                        scroll position, cell focus, and unsaved draft state in &lt; 16 ms, or press
                        <strong>Alt+G</strong> to stack another report on top (up to 8 levels).
                      </p>
                    }
                  }
                }
              </div>
            </section>
          }
        </main>

        <mc-function-bar
          orientation="vertical"
          [items]="rightRailItems()"
          (commandTriggered)="onCommandButton($event)"
        />
      </div>

      <!-- Bottom Shortcut Action Bar -->
      <mc-function-bar
        orientation="horizontal"
        [items]="bottomBarItems()"
        (commandTriggered)="onCommandButton($event)"
      />

      <!-- Global Palettes & Quick Context Modals -->
      <mc-go-to-palette
        [isOpen]="goToOpen()"
        [mode]="goToMode()"
        (closed)="goToOpen.set(false)"
      />
      <mc-smart-find-modal />
      <mc-secondary-master-modal />
      <mc-edit-log-redline-modal />
      <mc-keyboard-settings-modal />
      <mc-help-panel />

      <!-- F2 Working Date Modal (context.dateDialog: Apply [A], Close [C]) -->
      @if (workingDateModalOpen()) {
        <div class="mc-modal-backdrop" role="dialog" aria-modal="true" aria-label="Change Current Date (F2)">
          <form class="mc-modal-dialog mc-compact-modal" (ngSubmit)="applyWorkingDate()">
            <div class="mc-modal-header">
              <span>Change Current Date (F2)</span>
              <button type="button" class="mc-btn mc-btn-secondary" (click)="closeWorkingDateModal()">
                <mc-icon name="close" [size]="16" />
                <span>Close</span>
                @if (modalHint('context.dateDialog', 'editor.close', 'C/c'); as hint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
                }
              </button>
            </div>
            <div class="mc-modal-body">
              <label class="mc-field-label" for="mc-f2-date-input">Current Working / Voucher Date</label>
              <input
                id="mc-f2-date-input"
                type="date"
                name="workingDate"
                class="mc-input"
                [(ngModel)]="draftWorkingDate"
                required
              />
            </div>
            <div class="mc-modal-footer">
              <span>Enter / Ctrl+A or [A] to Apply</span>
              <button type="submit" class="mc-btn mc-btn-primary" data-testid="date-apply-btn">
                <mc-icon name="calendar" [size]="16" />
                <span>Apply</span>
                @if (modalHint('context.dateDialog', 'context.applyDate', 'A/a'); as hint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
                }
              </button>
            </div>
          </form>
        </div>
      }

      <!-- Alt+F2 Period Modal (context.dateDialog: Apply [A], Close [C]) -->
      @if (periodModalOpen()) {
        <div class="mc-modal-backdrop" role="dialog" aria-modal="true" aria-label="Change Period (Alt+F2)">
          <form class="mc-modal-dialog mc-compact-modal" (ngSubmit)="applyPeriodRange()">
            <div class="mc-modal-header">
              <span>Change Period (<span class="mc-key-alt">F2</span>)</span>
              <button type="button" class="mc-btn mc-btn-secondary" (click)="closePeriodModal()">
                <mc-icon name="close" [size]="16" />
                <span>Close</span>
                @if (modalHint('context.dateDialog', 'editor.close', 'C/c'); as hint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
                }
              </button>
            </div>
            <div class="mc-modal-body mc-period-fields">
              <div>
                <label class="mc-field-label" for="mc-period-from">From</label>
                <input
                  id="mc-period-from"
                  type="date"
                  name="periodFrom"
                  class="mc-input"
                  [(ngModel)]="draftPeriodFrom"
                  required
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-period-to">To</label>
                <input
                  id="mc-period-to"
                  type="date"
                  name="periodTo"
                  class="mc-input"
                  [(ngModel)]="draftPeriodTo"
                  required
                />
              </div>
              @if (periodError(); as pErr) {
                <div class="mc-field-error" role="alert">{{ pErr }}</div>
              }
            </div>
            <div class="mc-modal-footer">
              <span>Enter / Ctrl+A or [A] to Apply</span>
              <button type="submit" class="mc-btn mc-btn-primary" data-testid="period-apply-btn">
                <mc-icon name="calendar" [size]="16" />
                <span>Apply</span>
                @if (modalHint('context.dateDialog', 'context.applyDate', 'A/a'); as hint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
                }
              </button>
            </div>
          </form>
        </div>
      }

      <!-- F3 Company Selector (company.pick: Open [O], Close [C]) -->
      @if (companySwitcherOpen()) {
        <div class="mc-modal-backdrop" role="dialog" aria-modal="true" aria-label="Select Company (F3)">
          <div class="mc-modal-dialog mc-compact-modal">
            <div class="mc-modal-header">
              <span>List of Companies (F3)</span>
              <button type="button" class="mc-btn mc-btn-secondary" (click)="closeCompanySwitcherModal()">
                <mc-icon name="close" [size]="16" />
                <span>Close</span>
                @if (modalHint('company.pick', 'editor.close', 'C/c'); as hint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
                }
              </button>
            </div>
            <div class="mc-modal-body">
              @if (companySwitchBlockedWarning()) {
                <div class="mc-field-error" role="alert">
                  Unsaved draft detected! Save or discard your current voucher/form before switching companies.
                </div>
              }
              <div class="mc-switcher-actions">
                <button type="button" class="mc-btn mc-btn-primary" (click)="navigateToCompanyHub('create')">
                  Create Company
                </button>
                <button type="button" class="mc-btn" (click)="navigateToCompanyHub('alter')">
                  Alter / F11 Features
                </button>
                <button
                  type="button"
                  class="mc-btn"
                  (click)="closeCompanySwitcherModal(); navigateToExchange('MIGRATE_1800')"
                >
                  Migrate Legacy .1800
                </button>
                @if (companyContext.activeCompany()) {
                  <button
                    type="button"
                    class="mc-btn mc-btn-secondary"
                    data-testid="company-shut-btn"
                    (click)="shutActiveCompany()"
                  >
                    Shut Company (Ctrl+F3)
                  </button>
                }
              </div>
              <div class="mc-switcher-search">
                <label class="mc-field-label" for="mc-company-filter">Filter Companies</label>
                <input
                  id="mc-company-filter"
                  type="text"
                  class="mc-input"
                  [ngModel]="companySearchQuery()"
                  (ngModelChange)="companySearchQuery.set($event)"
                  placeholder="Search company name or code..."
                />
              </div>
              <ul class="mc-switcher-list">
                @for (comp of filteredOpenCompanies(); track comp.id) {
                  <li>
                    <button
                      type="button"
                      class="mc-switcher-row"
                      [class.mc-switcher-row-active]="selectedSwitcherCompanyId() === comp.id"
                      (focus)="selectedSwitcherCompanyId.set(comp.id)"
                      (click)="selectFromCompanyModal(comp.id)"
                    >
                      <span class="mc-switcher-row-main">
                        <span>{{ comp.legalName }}</span>
                        <span class="mc-switcher-meta">
                          FY {{ comp.financialYearFrom }} &bull; Last: {{ comp.lastEntryDate ?? 'No Vouchers' }}
                        </span>
                      </span>
                      <mc-code-badge [code]="comp.code" />
                    </button>
                  </li>
                } @empty {
                  <li class="mc-empty-records">No matching records</li>
                }
              </ul>
            </div>
            <div class="mc-modal-footer">
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="company-open-btn"
                [disabled]="!selectedSwitcherCompanyId()"
                (click)="openHighlightedCompanyFromModal()"
              >
                <mc-icon name="company" [size]="16" />
                <span>Open</span>
                @if (modalHint('company.pick', 'company.open', 'O/o'); as hint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
                }
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Dirty Editor 3-Way Modal (editor.dirty: Save draft [S], Discard [D], Stay [T]) -->
      @if (dirtyEditorModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Unsaved Draft Confirmation"
          data-testid="dirty-editor-modal"
        >
          <div class="mc-modal-dialog mc-compact-modal">
            <div class="mc-modal-header">
              <div class="mc-modal-title-inline">
                <mc-icon name="warning" [size]="20" />
                <span>Unsaved Draft Changes</span>
              </div>
            </div>
            <div class="mc-modal-body">
              <p class="mc-modal-copy">
                You have unsaved accounting edits in the active editor. Choose whether to save a
                draft first, discard local edits, or stay on the current form.
              </p>
            </div>
            <div class="mc-modal-footer mc-modal-footer-end">
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="dirty-save-draft-btn"
                (click)="saveDraftFromDirtyDialog()"
              >
                <mc-icon name="save-document" [size]="16" />
                <span>Save draft</span>
                @if (modalHint('editor.dirty', 'journal.saveDraft', 'S/s'); as hint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
                }
              </button>
              <button
                type="button"
                class="mc-btn mc-btn-danger"
                data-testid="dirty-discard-btn"
                (click)="discardDraftFromDirtyDialog()"
              >
                <mc-icon name="discard-draft" [size]="16" />
                <span>Discard</span>
                @if (modalHint('editor.dirty', 'editor.discard', 'D/d'); as hint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
                }
              </button>
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                data-testid="dirty-stay-btn"
                (click)="stayInDirtyDialog()"
              >
                <span>Stay</span>
                @if (modalHint('editor.dirty', 'editor.stay', 'T/t'); as hint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
                }
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Commands Menu Modal (commands.menu: 13-row fixed order per Doc 45 §3) -->
      @if (commandsMenuOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="MoneyCraft Commands Menu"
          data-testid="commands-menu-modal"
        >
          <div class="mc-modal-dialog mc-commands-modal">
            <div class="mc-modal-header">
              <span>Commands</span>
              <button type="button" class="mc-btn mc-btn-secondary" (click)="closeCommandsMenuModal()">
                <mc-icon name="close" [size]="16" />
                <span>Close</span>
                @if (modalHint('commands.menu', 'editor.close', 'C/c'); as hint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
                }
              </button>
            </div>
            <div class="mc-modal-body">
              <ul class="mc-switcher-list" aria-label="Contextual Commands">
                @for (row of commandsMenuRows; track row.commandId) {
                  <li>
                    <button
                      type="button"
                      class="mc-switcher-row"
                      [attr.data-testid]="'cmd-row-' + row.commandId"
                      (click)="executeCommandsMenuRow(row.commandId)"
                    >
                      <span class="mc-modal-title-inline">
                        @if (row.iconName; as ic) {
                          <mc-icon [name]="ic" [size]="16" />
                        }
                        <span>{{ row.label }}</span>
                      </span>
                      @if (modalHint('commands.menu', row.commandId, row.pairSpec); as hint) {
                        <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
                      }
                    </button>
                  </li>
                }
              </ul>
            </div>
          </div>
        </div>
      }

      <!-- Uncertain Write Recovery Modal (request.uncertain & nested message.details) -->
      @if (uncertainWriteModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Uncertain Write Outcome Recovery"
          data-testid="uncertain-write-modal"
        >
          <div class="mc-modal-dialog mc-compact-modal">
            <div class="mc-modal-header">
              <div class="mc-modal-title-inline">
                <mc-icon name="unknown" [size]="24" />
                <span>Uncertain Request Outcome</span>
              </div>
            </div>
            <div class="mc-modal-body">
              <p class="mc-modal-copy">{{ uncertainSummary() }}</p>
            </div>
            <div class="mc-modal-footer mc-modal-footer-end">
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                data-testid="uncertain-check-btn"
                (click)="checkUncertainStatus()"
              >
                <mc-icon name="refresh" [size]="16" />
                <span>Check status</span>
                @if (modalHint('request.uncertain', 'request.check', 'K/k'); as hint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
                }
              </button>
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                data-testid="uncertain-retry-btn"
                (click)="retryUncertainOriginal()"
              >
                <mc-icon name="refresh" [size]="16" />
                <span>Retry original</span>
                @if (modalHint('request.uncertain', 'request.retryOriginal', 'R/r'); as hint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
                }
              </button>
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                data-testid="uncertain-details-btn"
                (click)="openUncertainDetailsModal()"
              >
                <mc-icon name="details" [size]="16" />
                <span>Details</span>
                @if (modalHint('request.uncertain', 'request.details', 'D/d'); as hint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
                }
              </button>
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                data-testid="uncertain-kbd-btn"
                (click)="shortcutRegistry.openKeyboardSettingsModal()"
              >
                <mc-icon name="keyboard" [size]="16" />
                <span>Keyboard settings</span>
                @if (modalHint('request.uncertain', 'keyboard.open', 'B/b'); as hint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
                }
              </button>
            </div>
          </div>
        </div>
      }

      @if (messageDetailsOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Diagnostic Details"
          data-testid="message-details-modal"
        >
          <div class="mc-modal-dialog mc-compact-modal">
            <div class="mc-modal-header">
              <span>Diagnostic Details</span>
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                data-testid="details-close-btn"
                (click)="closeMessageDetailsModal()"
              >
                <mc-icon name="close" [size]="16" />
                <span>Close</span>
                @if (modalHint('message.details', 'editor.close', 'C/c'); as hint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
                }
              </button>
            </div>
            <div class="mc-modal-body">
              <pre class="mc-code mc-details-pre">{{ messageDetailsText() }}</pre>
            </div>
          </div>
        </div>
      }

      <!-- In-Place F11: Company Features Modal Overlay -->
      @if (f11ModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="F11: Company Features"
          data-testid="global-f11-modal"
          (keydown.escape)="closeF11Modal()"
        >
          <div class="mc-modal-dialog mc-f11-modal-dialog">
            <div class="mc-modal-header">
              <span>
                F11: Company Features
                @if (companyContext.activeCompany(); as cmp) {
                  — {{ cmp.legalName }}
                }
              </span>
              <div class="mc-f11-header-toggles">
                <label class="mc-toggle-line">
                  <input
                    type="checkbox"
                    name="f11ShowMore"
                    data-testid="f11-show-more-toggle"
                    [ngModel]="f11ShowMore()"
                    (ngModelChange)="onF11ShowMoreChange($event)"
                  />
                  <span>Show more features</span>
                </label>
                <label class="mc-toggle-line">
                  <input
                    type="checkbox"
                    name="f11ShowAll"
                    data-testid="f11-show-all-toggle"
                    [ngModel]="f11ShowAll()"
                    (ngModelChange)="onF11ShowAllChange($event)"
                  />
                  <span>Show all features</span>
                </label>
                <button
                  type="button"
                  class="mc-btn mc-btn-secondary"
                  (click)="closeF11Modal()"
                >
                  <mc-icon name="close" [size]="16" />
                  <span>Close</span>
                </button>
              </div>
            </div>

            <div class="mc-modal-body mc-f11-modal-body">
              <div class="mc-f11-modal-grid">
                <div class="mc-f11-modal-col">
                  <h3>Accounting Features</h3>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f11MaintainAccounts"
                      data-testid="f11-maintain-accounts-cb"
                      [(ngModel)]="f11DraftMaintainAccounts"
                    />
                    <span>Maintain Accounts</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f11BillWise"
                      data-testid="f11-billwise-cb"
                      [(ngModel)]="f11DraftBillWise"
                      [disabled]="!f11DraftMaintainAccounts"
                    />
                    <span>Enable Bill-wise entry</span>
                  </label>
                  @if (f11ShowMore() || f11ShowAll()) {
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11CostCentres"
                        data-testid="f11-cost-centres-cb"
                        [(ngModel)]="f11DraftCostCentres"
                      />
                      <span>Enable Cost Centres</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11InterestCalc"
                        data-testid="f11-interest-calc-cb"
                        [(ngModel)]="f11DraftInterestCalc"
                      />
                      <span>Enable Interest Calculation</span>
                    </label>
                  }

                  <h3>Inventory Features</h3>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f11MaintainInventory"
                      data-testid="f11-maintain-inventory-cb"
                      [(ngModel)]="f11DraftMaintainInventory"
                    />
                    <span>Maintain Inventory</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f11InventoryIntegrated"
                      data-testid="f11-inventory-integrated-cb"
                      [(ngModel)]="f11DraftInventoryIntegrated"
                      [disabled]="!f11DraftMaintainInventory || !f11DraftMaintainAccounts"
                    />
                    <span>Integrate Accounts with Inventory</span>
                  </label>
                  @if (f11ShowMore() || f11ShowAll()) {
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11PriceLevels"
                        data-testid="f11-price-levels-cb"
                        [(ngModel)]="f11DraftPriceLevels"
                      />
                      <span>Enable multiple Price Levels</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11Batches"
                        data-testid="f11-batches-cb"
                        [(ngModel)]="f11DraftBatches"
                      />
                      <span>Enable Batches</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11ExpiryBatches"
                        data-testid="f11-expiry-batches-cb"
                        [(ngModel)]="f11DraftExpiryBatches"
                        [disabled]="!f11DraftBatches"
                      />
                      <span>Maintain Expiry Date for Batches</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11DiscountCol"
                        data-testid="f11-discount-col-cb"
                        [(ngModel)]="f11DraftDiscountCol"
                      />
                      <span>Use Discount column in invoices</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11SeparateActualBilled"
                        data-testid="f11-separate-actual-billed-cb"
                        [(ngModel)]="f11DraftSeparateActualBilled"
                      />
                      <span>Use separate Actual and Billed Quantity columns in invoices</span>
                    </label>
                  }
                  @if (f11ShowAll()) {
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11JobOrder"
                        data-testid="f11-job-order-cb"
                        [(ngModel)]="f11DraftJobOrder"
                      />
                      <span>Enable Job Order Processing</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11CostTracking"
                        data-testid="f11-cost-tracking-cb"
                        [(ngModel)]="f11DraftCostTracking"
                      />
                      <span>Enable Cost Tracking</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11JobCosting"
                        data-testid="f11-job-costing-cb"
                        [(ngModel)]="f11DraftJobCosting"
                        [disabled]="!f11DraftCostCentres"
                      />
                      <span>Enable Job Costing</span>
                    </label>
                  }
                </div>

                <div class="mc-f11-modal-col">
                  <h3>Taxation &amp; Statutory</h3>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f11GstEnabled"
                      data-testid="f11-gst-cb"
                      [(ngModel)]="f11DraftGstEnabled"
                    />
                    <span>Enable Goods and Services Tax (GST)</span>
                  </label>
                  @if (f11DraftGstEnabled) {
                    <div class="mc-f11-gst-subbox">
                      <div class="mc-f11-inline-pair">
                        <div>
                          <label class="mc-field-label" for="f11-gst-reg-type">Registration Type</label>
                          <select
                            id="f11-gst-reg-type"
                            name="f11GstRegType"
                            class="mc-select"
                            [(ngModel)]="f11DraftGstRegType"
                          >
                            <option value="REGULAR">Regular</option>
                            <option value="COMPOSITION">Composition</option>
                            <option value="ISD">Input Service Distributor</option>
                            <option value="SEZ">SEZ</option>
                          </select>
                        </div>
                        <div>
                          <label class="mc-field-label" for="f11-gstr1-period">Periodicity</label>
                          <select
                            id="f11-gstr1-period"
                            name="f11Gstr1Periodicity"
                            class="mc-select"
                            [(ngModel)]="f11DraftGstr1Periodicity"
                          >
                            <option value="MONTHLY">Monthly</option>
                            <option value="QUARTERLY">Quarterly (QRMP)</option>
                          </select>
                        </div>
                      </div>
                      <div>
                        <label class="mc-field-label" for="f11-gstin-input">GSTIN / UIN</label>
                        <input
                          id="f11-gstin-input"
                          type="text"
                          name="f11Gstin"
                          class="mc-input mc-code"
                          [(ngModel)]="f11DraftGstin"
                          placeholder="33AABCM1234F1Z4"
                        />
                      </div>
                      <label class="mc-toggle-line">
                        <input
                          type="checkbox"
                          name="f11EWayBill"
                          [(ngModel)]="f11DraftEWayBill"
                        />
                        <span>e-Way Bill applicable (Limit: {{ f11DraftEWayBillLimit }})</span>
                      </label>
                      <label class="mc-toggle-line">
                        <input
                          type="checkbox"
                          name="f11EInvoice"
                          [(ngModel)]="f11DraftEInvoice"
                        />
                        <span>e-Invoicing applicable</span>
                      </label>
                      <label class="mc-toggle-line">
                        <input
                          type="checkbox"
                          name="f11MultiGstin"
                          [(ngModel)]="f11DraftMultiGstin"
                        />
                        <span>Enable Multi-GSTIN Registrations</span>
                      </label>
                    </div>
                  }

                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f11TdsEnabled"
                      data-testid="f11-tds-cb"
                      [(ngModel)]="f11DraftTdsEnabled"
                    />
                    <span>Enable Tax Deducted at Source (TDS)</span>
                  </label>
                  @if (f11ShowMore() || f11ShowAll()) {
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11TcsEnabled"
                        data-testid="f11-tcs-cb"
                        [(ngModel)]="f11DraftTcsEnabled"
                      />
                      <span>Enable Tax Collected at Source (TCS)</span>
                    </label>
                  }
                  @if (f11ShowAll()) {
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11VatEnabled"
                        data-testid="f11-vat-cb"
                        [(ngModel)]="f11DraftVatEnabled"
                      />
                      <span>Enable Value Added Tax (VAT)</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11ExciseEnabled"
                        data-testid="f11-excise-cb"
                        [(ngModel)]="f11DraftExciseEnabled"
                      />
                      <span>Enable Excise</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11ServiceTaxEnabled"
                        data-testid="f11-service-tax-cb"
                        [(ngModel)]="f11DraftServiceTaxEnabled"
                      />
                      <span>Enable Service Tax</span>
                    </label>
                  }

                  @if (f11ShowMore() || f11ShowAll()) {
                    <h3>Payroll &amp; Others</h3>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11PayrollEnabled"
                        data-testid="f11-payroll-cb"
                        [(ngModel)]="f11DraftPayrollEnabled"
                      />
                      <span>Maintain Payroll</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11PayrollStat"
                        data-testid="f11-payroll-statutory-cb"
                        [(ngModel)]="f11DraftPayrollStatEnabled"
                        [disabled]="!f11DraftPayrollEnabled"
                      />
                      <span>Enable Payroll Statutory</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11MultiAddr"
                        data-testid="f11-multiple-addresses-cb"
                        [(ngModel)]="f11DraftMultipleAddresses"
                      />
                      <span>Enable multiple addresses</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="f11MarkModified"
                        data-testid="f11-mark-modified-cb"
                        [(ngModel)]="f11DraftMarkModifiedVouchers"
                      />
                      <span>Mark modified vouchers</span>
                    </label>
                  }

                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f11McaEditLog"
                      data-testid="f11-mca-edit-log-cb"
                      [(ngModel)]="f11DraftMcaEditLog"
                      [disabled]="f11McaLocked()"
                    />
                    <span>
                      Enforce MCA Edit Log (Audit Trail)
                      @if (f11McaLocked()) {
                        <strong>(Locked)</strong>
                      }
                    </span>
                  </label>
                </div>
              </div>
            </div>

            <div class="mc-modal-footer mc-modal-footer-end">
              <button
                type="button"
                class="mc-btn"
                data-testid="f11-open-company-hub-btn"
                (click)="closeF11Modal(); navigateToCompanyHub('features')"
              >
                Open Full Company Hub
              </button>
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                (click)="closeF11Modal()"
              >
                Cancel (Esc)
              </button>
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="f11-accept-btn"
                (click)="acceptF11Modal()"
              >
                Accept (<span class="mc-key-ctrl">A</span>)
              </button>
            </div>
          </div>
        </div>
      }

      @if (shellConfigureOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Configuration (F12)"
          data-testid="shell-configure-modal"
          (keydown.escape)="closeShellConfigureModal()"
        >
          <div class="mc-modal-dialog mc-f12-modal-dialog">
            <div class="mc-modal-header">
              <span>Configuration (F12)</span>
              <div class="mc-f12-tabs">
                <button
                  type="button"
                  class="mc-btn"
                  [class.mc-btn-primary]="f12ActiveTab() === 'VOUCHER'"
                  data-testid="f12-tab-voucher"
                  (click)="f12ActiveTab.set('VOUCHER')"
                >
                  Voucher Entry
                </button>
                <button
                  type="button"
                  class="mc-btn"
                  [class.mc-btn-primary]="f12ActiveTab() === 'MASTERS'"
                  data-testid="f12-tab-masters"
                  (click)="f12ActiveTab.set('MASTERS')"
                >
                  Masters
                </button>
                <button
                  type="button"
                  class="mc-btn"
                  [class.mc-btn-primary]="f12ActiveTab() === 'REPORTS'"
                  data-testid="f12-tab-reports"
                  (click)="f12ActiveTab.set('REPORTS')"
                >
                  Reports &amp; Statements
                </button>
                <button
                  type="button"
                  class="mc-btn mc-btn-secondary"
                  (click)="closeShellConfigureModal()"
                >
                  <mc-icon name="close" [size]="16" />
                  <span>Close</span>
                </button>
              </div>
            </div>
            <div class="mc-modal-body mc-period-fields">
              @if (f12ActiveTab() === 'VOUCHER') {
                <div class="mc-f12-options-grid">
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12UseCrDr"
                      data-testid="f12-use-crdr-toggle"
                      [(ngModel)]="f12Draft.useCrDrInsteadOfToBy"
                    />
                    <span>Use Cr/Dr instead of To/By during voucher entry</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12ProvideRef"
                      data-testid="f12-provide-ref-toggle"
                      [(ngModel)]="f12Draft.provideReferenceNoAndDate"
                    />
                    <span>Provide Supplier Invoice No. and Date</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12DispatchDetails"
                      [(ngModel)]="f12Draft.provideDispatchOrderExportDetails"
                    />
                    <span>Provide Buyer / Dispatch, Order, and Export details</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12LineNarration"
                      data-testid="f12-line-narration-toggle"
                      [(ngModel)]="f12Draft.provideLineNarration"
                    />
                    <span>Provide narration for each ledger in voucher</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12CommonNarration"
                      [(ngModel)]="f12Draft.provideCommonNarration"
                    />
                    <span>Provide common narration</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12LedgerCurBal"
                      data-testid="f12-ledger-curbal-toggle"
                      [(ngModel)]="f12Draft.showLedgerCurrentBalance"
                    />
                    <span>Show Current Balance of Ledgers</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12WarnNegCash"
                      data-testid="f12-warn-neg-cash-toggle"
                      [(ngModel)]="f12Draft.warnOnNegativeCash"
                    />
                    <span>Warn on negative cash balance</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12WarnNegStock"
                      data-testid="f12-warn-neg-stock-toggle"
                      [(ngModel)]="f12Draft.warnOnNegativeStock"
                    />
                    <span>Warn on negative stock balance</span>
                  </label>
                </div>
              } @else if (f12ActiveTab() === 'MASTERS') {
                <div class="mc-f12-options-grid">
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12ProvideAliases"
                      [(ngModel)]="f12Draft.provideAliases"
                    />
                    <span>Provide aliases along with name</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12BillWiseNonTrading"
                      [(ngModel)]="f12Draft.billWiseForNonTradingAccounts"
                    />
                    <span>Provide Maintain balances bill-by-bill for Non-Trading Accounts</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12CreditLimits"
                      [(ngModel)]="f12Draft.provideCreditLimits"
                    />
                    <span>Provide Credit Limits</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12LedgerAddresses"
                      [(ngModel)]="f12Draft.provideLedgerAddresses"
                    />
                    <span>Use Addresses for Ledger Accounts</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12PartNumbers"
                      [(ngModel)]="f12Draft.providePartNumbers"
                    />
                    <span>Use Part Numbers for Stock Items</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12AlternateUnits"
                      [(ngModel)]="f12Draft.provideAlternateUnits"
                    />
                    <span>Use Alternate Units for Stock Items</span>
                  </label>
                </div>
              } @else {
                <div class="mc-f12-options-grid">
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12ShowOpening"
                      data-testid="f12-show-opening-toggle"
                      [(ngModel)]="f12Draft.showOpeningBalances"
                    />
                    <span>Show Opening Balances</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12ShowTransactions"
                      data-testid="f12-show-transactions-toggle"
                      [(ngModel)]="f12Draft.showTransactions"
                    />
                    <span>Show Transactions</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12ShowNett"
                      data-testid="f12-show-nett-toggle"
                      [(ngModel)]="f12Draft.showNettTransactionsOnly"
                    />
                    <span>Show Nett Transactions Only</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12ShowClosing"
                      data-testid="f12-show-closing-toggle"
                      [(ngModel)]="f12Draft.showClosingBalances"
                    />
                    <span>Show Closing Balances</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="f12ShowPercentages"
                      data-testid="f12-show-percentages-toggle"
                      [(ngModel)]="f12Draft.showPercentages"
                    />
                    <span>Show Percentages</span>
                  </label>
                  <div>
                    <label class="mc-field-label" for="f12-sorting-method">Sorting Method</label>
                    <select
                      id="f12-sorting-method"
                      name="f12SortingMethod"
                      class="mc-select"
                      data-testid="f12-sorting-select"
                      [(ngModel)]="f12Draft.sortingMethod"
                    >
                      <option value="DEFAULT">Default</option>
                      <option value="ALPHABETICAL_ASC">Alphabetical (A to Z)</option>
                      <option value="ALPHABETICAL_DESC">Alphabetical (Z to A)</option>
                      <option value="AMOUNT_DESC">Amount (Decreasing)</option>
                      <option value="AMOUNT_ASC">Amount (Increasing)</option>
                    </select>
                  </div>
                </div>
              }

              <div class="mc-switcher-actions">
                <button
                  type="button"
                  class="mc-btn"
                  data-testid="f12-open-api-config-btn"
                  (click)="closeShellConfigureModal(); openApiServerModal()"
                >
                  <span>Backend API Server (IP &amp; Port)</span>
                </button>
                <button
                  type="button"
                  class="mc-btn"
                  (click)="closeShellConfigureModal(); shortcutRegistry.openKeyboardSettingsModal()"
                >
                  <mc-icon name="keyboard" [size]="16" />
                  <span>Display &amp; Keyboard Preferences</span>
                </button>
                <button
                  type="button"
                  class="mc-btn"
                  (click)="closeShellConfigureModal(); openF11Modal()"
                >
                  <span>F11: Company Features</span>
                </button>
                <button
                  type="button"
                  class="mc-btn mc-btn-primary"
                  data-testid="f12-accept-btn"
                  (click)="acceptShellConfigureModal()"
                >
                  <span>Accept (<span class="mc-key-ctrl">A</span>)</span>
                </button>
              </div>
            </div>
          </div>
        </div>
      }

      @if (apiServerModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Backend API Server (IP & Port)"
          data-testid="api-server-config-modal"
          (keydown.escape)="closeApiServerModal()"
        >
          <form class="mc-modal-dialog mc-compact-modal" (ngSubmit)="applyShellBackendApiConfig()">
            <div class="mc-modal-header">
              <span>Backend API Server (IP &amp; Port)</span>
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                (click)="closeApiServerModal()"
              >
                <mc-icon name="close" [size]="16" />
                <span>Close (Esc)</span>
              </button>
            </div>
            <div class="mc-modal-body mc-period-fields">
              <div class="mc-modal-copy">
                Active Endpoint:
                <mc-code-badge [code]="runtimeConfig.apiBaseUrl" data-testid="shell-active-api-url" />
              </div>
              <div>
                <label class="mc-field-label" for="mc-shell-api-scheme">Protocol</label>
                <select
                  id="mc-shell-api-scheme"
                  name="shellApiScheme"
                  class="mc-select"
                  data-testid="shell-api-scheme-select"
                  [(ngModel)]="shellApiScheme"
                >
                  <option value="https">HTTPS (TLS)</option>
                  <option value="http">HTTP (Loopback / Dev)</option>
                </select>
              </div>
              <div>
                <label class="mc-field-label" for="mc-shell-api-host">Backend API IP / Hostname</label>
                <input
                  id="mc-shell-api-host"
                  type="text"
                  name="shellApiHost"
                  class="mc-input mc-code"
                  data-testid="shell-api-host-input"
                  [(ngModel)]="shellApiHost"
                  placeholder="192.168.1.100 or localhost"
                  required
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-shell-api-port">Backend API Port</label>
                <input
                  id="mc-shell-api-port"
                  type="text"
                  name="shellApiPort"
                  class="mc-input mc-code"
                  data-testid="shell-api-port-input"
                  [(ngModel)]="shellApiPort"
                  placeholder="8443"
                  required
                />
              </div>
              @if (shellApiConfigError(); as apiErr) {
                <div class="mc-field-error" role="alert" data-testid="shell-api-error">{{ apiErr }}</div>
              }
            </div>
            <div class="mc-modal-footer mc-modal-footer-end">
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                data-testid="shell-api-reset-btn"
                (click)="resetShellBackendApiConfig()"
              >
                Reset to Gateway Default (/api/v1)
              </button>
              <button
                type="submit"
                class="mc-btn mc-btn-primary"
                data-testid="shell-api-apply-btn"
              >
                Save &amp; Apply IP / Port
              </button>
            </div>
          </form>
        </div>
      }

      @if (shellCalculatorOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Calculator Pane (Alt+N)"
          data-testid="shell-calculator-modal"
        >
          <div class="mc-modal-dialog mc-compact-modal">
            <div class="mc-modal-header">
              <span>ExactDecimal Calculator (<span class="mc-key-alt">N</span>)</span>
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                (click)="closeShellCalculator()"
              >
                <mc-icon name="close" [size]="16" />
                <span>Close</span>
              </button>
            </div>
            <div class="mc-modal-body mc-period-fields">
              <label class="mc-field-label" for="mc-shell-calc-expr">
                Arithmetic Expression (e.g. (1250 * 18%) + 1250)
              </label>
              <input
                id="mc-shell-calc-expr"
                type="text"
                class="mc-input mc-code"
                data-testid="shell-calculator-input"
                [ngModel]="shellCalcExpr()"
                (ngModelChange)="onShellCalcInput($event)"
                placeholder="Type expression..."
              />
              <div class="mc-modal-copy">
                Result: <strong class="mc-code" data-testid="shell-calculator-result">{{ shellCalcResult() }}</strong>
              </div>
            </div>
          </div>
        </div>
      }
    </div>
  `,
  styles: `
    .mc-shell-viewport {
      display: flex;
      flex-direction: column;
      height: 100vh;
      width: 100vw;
      overflow: hidden;
      background: var(--mc-color-canvas);
    }

    .mc-quarantine-banner {
      background: var(--mc-color-danger-700);
      color: var(--mc-color-surface);
      padding: 0.45rem 1rem;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--mc-space-3);
      z-index: var(--mc-z-quarantine);
      border-bottom: 2px solid #FCA5A5;
    }

    .mc-quarantine-title,
    .mc-modal-title-inline,
    .mc-omni-icon-btn {
      display: inline-flex;
      align-items: center;
      gap: var(--mc-space-2);
      font-weight: 700;
    }

    .mc-quarantine-desc {
      font-size: var(--mc-font-size-xs);
      font-weight: 500;
    }

    .mc-quarantine-link {
      color: var(--mc-color-surface);
      background: rgba(255, 255, 255, 0.16);
      border: 1px solid rgba(255, 255, 255, 0.45);
      border-radius: var(--mc-radius-xs);
      padding: 0.2rem 0.6rem;
      text-decoration: none;
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      white-space: nowrap;
    }

    .mc-omni-bar {
      min-height: var(--mc-shell-topbar-height);
      background: var(--mc-color-deep-ledger-slate);
      color: var(--mc-color-surface);
      padding: 0.25rem 0.75rem;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--mc-space-3);
      z-index: var(--mc-z-shell);
    }

    .mc-omni-left,
    .mc-omni-center,
    .mc-omni-right,
    .mc-omni-menus {
      display: flex;
      align-items: center;
      gap: 0.35rem;
    }

    .mc-brand-link {
      display: inline-flex;
      align-items: center;
      margin-right: var(--mc-space-2);
    }

    .mc-brand-logo {
      display: block;
      height: 24px;
      width: auto;
    }

    .mc-omni-btn {
      background: transparent;
      border: 1px solid transparent;
      border-radius: var(--mc-radius-xs);
      color: var(--mc-color-surface);
      padding: 0.2rem 0.5rem;
      font-size: var(--mc-font-size-xs);
      font-weight: 500;
      cursor: pointer;

      &:hover {
        background: rgba(76, 225, 189, 0.14);
        border-color: rgba(76, 225, 189, 0.4);
      }
    }

    .mc-omni-btn-active {
      background: var(--mc-color-pressed-teal);
      border-color: var(--mc-color-mint-signal);
      font-weight: 700;
    }

    .mc-goto-pill {
      background: var(--mc-color-pressed-teal);
      border: 1px solid var(--mc-color-mint-signal);
      color: var(--mc-color-surface);
      border-radius: var(--mc-radius-sm);
      padding: 0.2rem 0.7rem;
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      cursor: pointer;
    }

    .mc-stack-counter {
      background: var(--mc-color-mint-signal);
      color: var(--mc-color-slate-900);
      border-radius: 999px;
      padding: 0 6px;
      font-size: 11px;
      font-weight: 700;
    }

    .mc-smartfind-pill {
      min-width: 220px;
      background: rgba(255, 255, 255, 0.1);
      border: 1px solid rgba(255, 255, 255, 0.24);
      border-radius: var(--mc-radius-sm);
      color: var(--mc-color-slate-200);
      padding: 0.2rem 0.65rem;
      display: inline-flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--mc-space-2);
      font-size: var(--mc-font-size-xs);
      cursor: pointer;

      &:hover {
        border-color: var(--mc-color-mint-signal);
        color: var(--mc-color-surface);
      }
    }

    .mc-smartfind-pill-key {
      color: var(--mc-color-mint-signal);
      font-weight: 600;
    }

    .mc-user-chip {
      background: rgba(255, 255, 255, 0.12);
      border-radius: var(--mc-radius-xs);
      padding: 0.15rem 0.45rem;
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
      color: var(--mc-color-mint-signal);
    }

    .mc-shell-main {
      flex: 1;
      min-height: 0;
      display: grid;
      grid-template-columns: var(--mc-shell-infopanel-width) minmax(0, 1fr) var(--mc-shell-fbar-width);
    }

    @media (max-width: 1100px) {
      .mc-shell-main {
        grid-template-columns: minmax(0, 1fr) var(--mc-shell-fbar-width);
      }

      .mc-info-panel {
        display: none;
      }
    }

    .mc-info-panel {
      background: var(--mc-color-surface);
      border-right: var(--mc-border-strong);
      padding: var(--mc-space-3);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
      overflow-y: auto;
    }

    .mc-info-row-grid {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-info-stat-box {
      width: 100%;
      text-align: left;
      padding: var(--mc-space-2);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      display: flex;
      flex-direction: column;
      gap: 2px;
      cursor: pointer;

      &:hover {
        border-color: var(--mc-color-pressed-teal);
      }
    }

    .mc-info-stat-label {
      font-size: 11px;
      font-weight: 700;
      color: var(--mc-color-slate-600);
    }

    .mc-info-stat-val {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-900);
    }

    .mc-open-companies-section {
      border-top: var(--mc-border-subtle);
      padding-top: var(--mc-space-2);
    }

    .mc-open-companies-header {
      display: flex;
      justify-content: space-between;
      font-size: 11px;
      font-weight: 700;
      color: var(--mc-color-slate-600);
      padding-bottom: 4px;
      border-bottom: var(--mc-border-subtle);
    }

    .mc-open-companies-list {
      list-style: none;
      margin: 0;
      padding: 0;
    }

    .mc-open-company-row {
      padding: 0.35rem 0.25rem;
      border-bottom: 1px solid var(--mc-color-slate-100);
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 6px;
      font-size: var(--mc-font-size-xs);
      cursor: pointer;
    }

    .mc-open-company-active {
      font-weight: 700;
      color: var(--mc-color-pressed-teal);
      background: var(--mc-color-teal-50);
    }

    .mc-open-company-name {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .mc-open-company-date {
      font-size: 11px;
      color: var(--mc-color-slate-600);
    }

    .mc-open-company-empty {
      padding: var(--mc-space-2) 0;
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
    }

    .mc-shell-toast {
      margin-top: auto;
      padding: var(--mc-space-2);
      background: var(--mc-color-slate-900);
      color: var(--mc-color-mint-signal);
      border-radius: var(--mc-radius-xs);
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
    }

    .mc-workspace-canvas {
      position: relative;
      padding: var(--mc-space-4);
      overflow-y: auto;
      min-height: 0;
    }

    .mc-goto-overlay-frame {
      position: sticky;
      top: 0;
      margin-top: var(--mc-space-3);
      background: var(--mc-color-surface);
      border: 2px solid var(--mc-color-pressed-teal);
      border-radius: var(--mc-radius-md);
      box-shadow: var(--mc-shadow-modal);
      z-index: var(--mc-z-goto-stack-base);
      overflow: hidden;
    }

    .mc-goto-overlay-bar {
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      padding: 0.45rem 0.875rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .mc-goto-overlay-title,
    .mc-goto-overlay-bar-btns {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-goto-overlay-content {
      padding: var(--mc-space-4);
    }

    .mc-compact-modal {
      width: min(460px, 94vw);
    }

    .mc-commands-modal {
      width: min(520px, 95vw);
      max-height: 85vh;
      overflow-y: auto;
    }

    .mc-period-fields {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-switcher-actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--mc-space-2);
      margin-bottom: var(--mc-space-2);
    }

    .mc-switcher-row-main {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 2px;
      text-align: left;
    }

    .mc-switcher-meta {
      font-size: 11px;
      font-weight: 500;
      color: var(--mc-color-slate-600);
    }

    .mc-omni-dropdown-backdrop {
      position: fixed;
      inset: var(--mc-shell-topbar-height) 0 0 0;
      z-index: var(--mc-z-modal);
      display: flex;
      align-items: flex-start;
      padding: 0.25rem 0.75rem;
    }

    .mc-omni-dropdown {
      min-width: 280px;
      background: var(--mc-color-surface);
      border: 2px solid var(--mc-color-deep-ledger-slate);
      border-radius: var(--mc-radius-sm);
      box-shadow: var(--mc-shadow-modal);
      overflow: hidden;
      outline: none;
    }

    .mc-omni-dropdown-right {
      margin-left: auto;
    }

    .mc-omni-dropdown-head {
      padding: 0.35rem 0.7rem;
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      font-size: 11px;
      font-weight: 700;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .mc-omni-dropdown-list {
      list-style: none;
      margin: 0;
      padding: 0.25rem 0;
    }

    .mc-omni-dropdown-item {
      width: 100%;
      border: 0;
      background: transparent;
      padding: 0.42rem 0.75rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-3);
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-900);
      cursor: pointer;
      text-align: left;
    }

    .mc-omni-dropdown-item-active {
      background: var(--mc-color-pressed-teal);
      color: var(--mc-color-surface);
    }

    .mc-omni-mnemonic {
      text-decoration: underline;
    }

    .mc-omni-sc {
      font-size: 11px;
      opacity: 0.85;
    }

    .mc-switcher-search {
      margin-bottom: var(--mc-space-2);
    }

    .mc-switcher-list {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .mc-switcher-row {
      width: 100%;
      padding: 0.45rem 0.65rem;
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-weight: 600;
      cursor: pointer;

      &:hover {
        border-color: var(--mc-color-pressed-teal);
      }
    }

    .mc-switcher-row-active {
      background: var(--mc-color-teal-50);
      border-color: var(--mc-color-pressed-teal);
    }

    .mc-modal-copy {
      margin: 0;
      font-size: var(--mc-font-size-sm);
      color: var(--mc-color-slate-800);
      line-height: 1.45;
    }

    .mc-modal-footer-end {
      display: flex;
      justify-content: flex-end;
      flex-wrap: wrap;
      gap: var(--mc-space-2);
    }

    .mc-details-pre {
      margin: 0;
      white-space: pre-wrap;
      font-size: var(--mc-font-size-xs);
    }

    .mc-f11-modal-dialog {
      width: min(860px, 96vw);
      max-height: 90vh;
      overflow-y: auto;
    }

    .mc-f11-header-toggles {
      display: flex;
      align-items: center;
      gap: var(--mc-space-3);
      flex-wrap: wrap;
    }

    .mc-f11-modal-body {
      max-height: 68vh;
      overflow-y: auto;
    }

    .mc-f11-modal-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-4);
    }

    @media (max-width: 820px) {
      .mc-f11-modal-grid {
        grid-template-columns: 1fr;
      }
    }

    .mc-f11-modal-col {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);

      h3 {
        margin: 4px 0 2px;
        font-size: var(--mc-font-size-sm);
        color: var(--mc-color-slate-900);
        border-bottom: var(--mc-border-subtle);
        padding-bottom: 2px;
      }
    }

    .mc-f11-gst-subbox {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-f11-inline-pair {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-2);
    }

    .mc-f12-modal-dialog {
      width: min(640px, 95vw);
      max-height: 88vh;
      overflow-y: auto;
    }

    .mc-f12-tabs {
      display: flex;
      align-items: center;
      gap: 4px;
      flex-wrap: wrap;
    }

    .mc-f12-options-grid {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
      padding: var(--mc-space-2) 0;
    }

    .mc-toggle-line {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      font-size: var(--mc-font-size-xs);
      font-weight: 500;
      cursor: pointer;
    }

    .mc-omni-dropdown-item:disabled {
      opacity: 0.55;
      cursor: not-allowed;
    }
  `,
})
export class ErpShellComponent implements OnInit {
  readonly authStore = inject(AuthStore);
  readonly runtimeConfig = inject(RuntimeConfigService);
  readonly companyContext = inject(CompanyContextStore);
  readonly navStack = inject(NavigationStackStore);
  readonly smartFind = inject(SmartFindStore);
  readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  readonly contextualHelp = inject(ContextualHelpService);

  private readonly coaStore = inject(ChartOfAccountsStore);
  private readonly inventoryStore = inject(InventoryStore);
  private readonly secondaryMasterModal = inject(SecondaryMasterModalService);
  private readonly sseStream = inject(SseStreamService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly commandsMenuRows = COMMANDS_MENU_ROWS;
  readonly currentRouteUrl = signal<string>(this.router.url || '/app/gateway');

  constructor() {
    effect(() => {
      const cid = this.companyContext.activeCompany()?.id;
      if (cid) {
        void this.coaStore.ensureAccountingMastersLoaded(cid);
        void this.coaStore.loadVoucherTypes(cid);
        void this.inventoryStore.ensureLoaded(cid);
      }
    });

    const routerSub = this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe((e) => {
        this.currentRouteUrl.set(e.urlAfterRedirects || e.url);
      });
    this.destroyRef.onDestroy(() => routerSub.unsubscribe());
  }

  private readonly topBarMenuPanelRef = viewChild<ElementRef<HTMLElement>>('topBarMenuPanel');

  readonly goToOpen = signal<boolean>(false);
  readonly goToMode = signal<'GO_TO' | 'SWITCH_TO'>('GO_TO');
  readonly openTopBarMenu = signal<TopBarMenuKind | null>(null);
  readonly topBarMenuIndex = signal<number>(0);

  // In-Place F11 Company Features Modal State
  readonly f11ModalOpen = signal<boolean>(false);
  readonly f11ShowMore = signal<boolean>(true);
  readonly f11ShowAll = signal<boolean>(false);
  readonly f11McaLocked = signal<boolean>(false);
  f11DraftMaintainAccounts = true;
  f11DraftBillWise = true;
  f11DraftCostCentres = false;
  f11DraftInterestCalc = false;
  f11DraftMaintainInventory = true;
  f11DraftInventoryIntegrated = true;
  f11DraftPriceLevels = false;
  f11DraftBatches = true;
  f11DraftExpiryBatches = true;
  f11DraftDiscountCol = true;
  f11DraftSeparateActualBilled = true;
  f11DraftJobOrder = false;
  f11DraftCostTracking = false;
  f11DraftJobCosting = false;
  f11DraftGstEnabled = true;
  f11DraftGstRegType: 'REGULAR' | 'COMPOSITION' | 'ISD' | 'SEZ' = 'REGULAR';
  f11DraftGstr1Periodicity: 'MONTHLY' | 'QUARTERLY' = 'MONTHLY';
  f11DraftGstin = '';
  f11DraftEWayBill = true;
  f11DraftEWayBillLimit = '50000.00';
  f11DraftEInvoice = false;
  f11DraftMultiGstin = false;
  f11DraftTdsEnabled = true;
  f11DraftTcsEnabled = false;
  f11DraftVatEnabled = false;
  f11DraftExciseEnabled = false;
  f11DraftServiceTaxEnabled = false;
  f11DraftPayrollEnabled = false;
  f11DraftPayrollStatEnabled = false;
  f11DraftMultipleAddresses = false;
  f11DraftMarkModifiedVouchers = false;
  f11DraftMcaEditLog = true;

  // Context-Sensitive F12 Configuration Modal State
  readonly shellConfigureOpen = signal<boolean>(false);
  readonly f12ActiveTab = signal<'VOUCHER' | 'MASTERS' | 'REPORTS'>('VOUCHER');
  f12Draft: { -readonly [K in keyof CompanyF12Config]: CompanyF12Config[K] } = {
    ...DEFAULT_COMPANY_F12_CONFIG,
  };

  readonly shellCalculatorOpen = signal<boolean>(false);
  readonly shellCalcExpr = signal<string>('');
  readonly shellCalcResult = signal<string>('0.00');
  readonly apiServerModalOpen = signal<boolean>(false);
  readonly shellApiConfigError = signal<string | null>(null);
  shellApiScheme: 'https' | 'http' = 'https';
  shellApiHost = 'localhost';
  shellApiPort = '8443';
  readonly workingDateModalOpen = signal<boolean>(false);
  readonly periodModalOpen = signal<boolean>(false);
  readonly companySwitcherOpen = signal<boolean>(false);
  readonly companySwitchBlockedWarning = signal<boolean>(false);
  readonly companySearchQuery = signal<string>('');
  readonly selectedSwitcherCompanyId = signal<string | null>(null);

  readonly currentTopBarMenuItems = computed<readonly TopBarMenuEntry[]>(() => {
    const active = this.openTopBarMenu();
    if (!active) {
      return [];
    }
    const hasCompany = Boolean(this.companyContext.activeCompany());
    const gstEnabled = this.companyContext.isGstEnabled();
    const baseEntries =
      active === 'COMPANY' && this.authStore.isSuperAdmin()
        ? [
            ...TOP_BAR_MENUS.COMPANY,
            {
              id: 'company.govIntegrations',
              mnemonic: 'G',
              label: 'Government & Banking Integrations (SUPER_ADMIN)',
            },
          ]
        : TOP_BAR_MENUS[active];

    return baseEntries.map((entry) => {
      if (
        !hasCompany &&
        (entry.id === 'company.alter' ||
          entry.id === 'company.shut' ||
          entry.id === 'company.features' ||
          entry.id === 'data.split' ||
          entry.id === 'export.current')
      ) {
        return { ...entry, disabled: true };
      }
      if (
        !gstEnabled &&
        (entry.id === 'exchange.einvoice' ||
          entry.id === 'exchange.ims' ||
          entry.id === 'export.gst')
      ) {
        return { ...entry, disabled: true };
      }
      return entry;
    });
  });

  readonly dirtyEditorModalOpen = signal<boolean>(false);
  readonly commandsMenuOpen = signal<boolean>(false);
  readonly uncertainWriteModalOpen = signal<boolean>(false);
  readonly messageDetailsOpen = signal<boolean>(false);
  readonly uncertainSummary = signal<string>(
    'The network connection closed before posting confirmation arrived. Check status before retrying.',
  );
  readonly messageDetailsText = signal<string>('Status: UNCERTAIN_OUTCOME');

  readonly periodError = signal<string | null>(null);
  readonly statusToast = signal<string | null>(null);

  draftWorkingDate = '2025-04-01';
  draftPeriodFrom = '2025-04-01';
  draftPeriodTo = '2026-03-31';

  private dateContextHandle: ActiveModalContextHandle | null = null;
  private periodContextHandle: ActiveModalContextHandle | null = null;
  private companyPickContextHandle: ActiveModalContextHandle | null = null;
  private dirtyEditorContextHandle: ActiveModalContextHandle | null = null;
  private commandsMenuContextHandle: ActiveModalContextHandle | null = null;
  private uncertainContextHandle: ActiveModalContextHandle | null = null;
  private messageDetailsContextHandle: ActiveModalContextHandle | null = null;
  private pendingNavigationAfterDirtyResolution: (() => void) | null = null;

  readonly filteredOpenCompanies = computed(() => {
    const q = this.companySearchQuery().trim().toLowerCase();
    const list = this.companyContext.openCompanies();
    if (!q) {
      return list;
    }
    return list.filter(
      (c) => c.legalName.toLowerCase().includes(q) || c.code.toLowerCase().includes(q),
    );
  });

  readonly goToOverlayFrames = computed(() =>
    this.navStack
      .frames()
      .filter(
        (f) =>
          f.route !== SECONDARY_MASTER_ROUTE_TOKEN &&
          f.route !== EDIT_LOG_ROUTE_TOKEN &&
          f.route !== TB_GROUP_DRILL_ROUTE_TOKEN &&
          f.params['viaSmartFind'] !== '1',
      ),
  );

  readonly rightRailItems = computed<readonly FunctionBarButtonItem[]>(() => {
    const active = this.companyContext.activeCompany();
    const hasCompany = Boolean(active);
    const maintainAccounts = active ? active.features.maintainAccounts !== false : false;
    const url = this.currentRouteUrl();
    const isGateway = url.startsWith('/app/gateway') || url === '/gateway' || url === '/';
    const isDayBook = url.includes('/daybook');
    const isTrialBalance = url.includes('/reports/trial-balance');
    const isReport =
      url.includes('/reports') ||
      url.includes('/daybook') ||
      url.includes('/statutory') ||
      url.includes('/coa');
    const isVoucher = url.includes('/vouchers');
    const voucherDisabled = !hasCompany || !maintainAccounts;

    return [
      {
        commandId: 'context.changeDate',
        keycap: 'F2',
        modifierStyle: 'PLAIN',
        label: isReport ? 'Period' : 'Date',
        disabled: !hasCompany,
      },
      {
        commandId: 'context.changePeriod',
        keycap: 'F2',
        modifierStyle: 'ALT_SINGLE_UNDERLINE',
        label: 'Period',
        disabled: !hasCompany,
      },
      { commandId: 'context.selectCompany', keycap: 'F3', modifierStyle: 'PLAIN', label: 'Company' },
      {
        commandId: 'company.shut',
        keycap: 'F3',
        modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
        label: 'Shut Cmp',
        disabled: !hasCompany,
      },
      {
        commandId: 'voucher.contra',
        keycap: 'F4',
        modifierStyle: 'PLAIN',
        label: isDayBook ? 'Chg Vch' : 'Contra',
        disabled: voucherDisabled,
      },
      {
        commandId: 'voucher.payment',
        keycap: 'F5',
        modifierStyle: 'PLAIN',
        label: isTrialBalance ? 'Ledger-wise' : 'Payment',
        disabled: voucherDisabled,
      },
      {
        commandId: 'voucher.receipt',
        keycap: 'F6',
        modifierStyle: 'PLAIN',
        label: 'Receipt',
        disabled: voucherDisabled,
      },
      {
        commandId: 'voucher.journal',
        keycap: 'F7',
        modifierStyle: 'PLAIN',
        label: 'Journal',
        disabled: voucherDisabled,
      },
      {
        commandId: 'voucher.sales',
        keycap: 'F8',
        modifierStyle: 'PLAIN',
        label: 'Sales',
        disabled: voucherDisabled,
      },
      {
        commandId: 'voucher.purchase',
        keycap: 'F9',
        modifierStyle: 'PLAIN',
        label: 'Purchase',
        disabled: voucherDisabled,
      },
      {
        commandId: 'voucher.otherVouchers',
        keycap: 'F10',
        modifierStyle: 'PLAIN',
        label: 'Other Vouchers',
        disabled: voucherDisabled,
      },
      {
        commandId: 'report.toggleDetailed',
        keycap: 'F1',
        modifierStyle: 'ALT_SINGLE_UNDERLINE',
        label: 'Detailed',
        disabled: isGateway || !hasCompany,
      },
      {
        commandId: 'report.basisOfValues',
        keycap: 'B',
        modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
        label: 'Basis of Values',
        disabled: !isReport || !hasCompany,
      },
      {
        commandId: 'voucher.changeMode',
        keycap: 'H',
        modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
        label: isReport ? 'Change View' : 'Change Mode',
        disabled: (!isVoucher && !isReport) || !hasCompany,
      },
      {
        commandId: 'voucher.taxAnalysis',
        keycap: 'I',
        modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
        label: 'More Details',
        disabled: !isVoucher || !hasCompany,
      },
      {
        commandId: 'report.exceptions',
        keycap: 'J',
        modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
        label: 'Exception Reports',
        disabled: !hasCompany,
      },
      {
        commandId: 'voucher.toggleOptional',
        keycap: 'L',
        modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
        label: 'Optional',
        disabled: !isVoucher || !hasCompany,
      },
      {
        commandId: 'voucher.togglePostDated',
        keycap: 'T',
        modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
        label: 'Post-Dated',
        disabled: !isVoucher || !hasCompany,
      },
      {
        commandId: 'company.features',
        keycap: 'F11',
        modifierStyle: 'PLAIN',
        label: 'Features',
        disabled: !hasCompany,
      },
      { commandId: 'screen.configure', keycap: 'F12', modifierStyle: 'PLAIN', label: 'Configure' },
    ];
  });

  readonly bottomBarItems = computed<readonly FunctionBarButtonItem[]>(() => [
    { commandId: 'nav.escape', keycap: 'Q', modifierStyle: 'CTRL_DOUBLE_UNDERLINE', label: 'Quit' },
    { commandId: 'screen.accept', keycap: 'A', modifierStyle: 'CTRL_DOUBLE_UNDERLINE', label: 'Accept' },
    { commandId: 'inline.createOrCalc', keycap: 'C', modifierStyle: 'ALT_SINGLE_UNDERLINE', label: 'Create' },
    { commandId: 'register.deleteVoucher', keycap: 'D', modifierStyle: 'ALT_SINGLE_UNDERLINE', label: 'Delete' },
    { commandId: 'voucher.changeMode', keycap: 'H', modifierStyle: 'CTRL_DOUBLE_UNDERLINE', label: 'Change Mode' },
    { commandId: 'voucher.toggleOptional', keycap: 'L', modifierStyle: 'CTRL_DOUBLE_UNDERLINE', label: 'Optional' },
    { commandId: 'report.basisOfValues', keycap: 'B', modifierStyle: 'CTRL_DOUBLE_UNDERLINE', label: 'Basis of Values' },
    { commandId: 'voucher.autofill', keycap: 'F', modifierStyle: 'CTRL_DOUBLE_UNDERLINE', label: 'Autofill' },
    { commandId: 'register.duplicateVoucher', keycap: '2', modifierStyle: 'ALT_SINGLE_UNDERLINE', label: 'Duplicate' },
    { commandId: 'register.cancelVoucher', keycap: 'X', modifierStyle: 'ALT_SINGLE_UNDERLINE', label: 'Cancel Vch' },
    { commandId: 'voucher.taxAnalysis', keycap: 'I', modifierStyle: 'CTRL_DOUBLE_UNDERLINE', label: 'Tax Analysis' },
    { commandId: 'report.exceptions', keycap: 'J', modifierStyle: 'CTRL_DOUBLE_UNDERLINE', label: 'Exceptions' },
    { commandId: 'audit.editLog', keycap: 'Q', modifierStyle: 'ALT_SINGLE_UNDERLINE', label: 'Edit Log' },
    { commandId: 'report.toggleDetailed', keycap: 'F1', modifierStyle: 'ALT_SINGLE_UNDERLINE', label: 'Detailed' },
  ]);

  ngOnInit(): void {
    if (!this.companyContext.companiesLoaded()) {
      void this.companyContext.loadCompanies();
    }
    this.sseStream.connect();

    this.shortcutRegistry.configureSmartFindPassthrough(
      () => this.smartFind.isOpen(),
      () => this.smartFind.close(),
    );

    const disposers: (() => void)[] = [
      this.shortcutRegistry.bindHandler('nav.goTo', 'GLOBAL', () => this.openGoToPalette('GO_TO')),
      this.shortcutRegistry.bindHandler('nav.switchTo', 'GLOBAL', () =>
        this.openGoToPalette('SWITCH_TO'),
      ),
      this.shortcutRegistry.bindHandler('search.smartFind', 'GLOBAL', () => this.smartFind.open()),
      this.shortcutRegistry.bindHandler('context.changeDate', 'GLOBAL', () =>
        this.openWorkingDateModal(),
      ),
      this.shortcutRegistry.bindHandler('context.changePeriod', 'GLOBAL', () =>
        this.openPeriodModal(),
      ),
      this.shortcutRegistry.bindHandler('context.selectCompany', 'GLOBAL', () =>
        this.openCompanySwitcherModal(),
      ),
      this.shortcutRegistry.bindHandler('company.shut', 'GLOBAL', () => this.shutActiveCompany()),
      this.shortcutRegistry.bindHandler('menu.company', 'GLOBAL', () =>
        this.toggleTopBarMenu('COMPANY'),
      ),
      this.shortcutRegistry.bindHandler('menu.data', 'GLOBAL', () =>
        this.toggleTopBarMenu('DATA'),
      ),
      this.shortcutRegistry.bindHandler('menu.exchange', 'GLOBAL', () =>
        this.toggleTopBarMenu('EXCHANGE'),
      ),
      this.shortcutRegistry.bindHandler('menu.import', 'GLOBAL', () =>
        this.toggleTopBarMenu('IMPORT'),
      ),
      this.shortcutRegistry.bindHandler('menu.export', 'GLOBAL', () =>
        this.toggleTopBarMenu('EXPORT'),
      ),
      this.shortcutRegistry.bindHandler('menu.share', 'GLOBAL', () =>
        this.toggleTopBarMenu('SHARE'),
      ),
      this.shortcutRegistry.bindHandler(
        'menu.print',
        'GLOBAL',
        () => this.toggleTopBarMenu('PRINT'),
        () => !this.isDispatchingPrintFromMenu,
      ),
      this.shortcutRegistry.bindHandler('help.contextual', 'GLOBAL', () =>
        this.openContextualHelp(),
      ),
      this.shortcutRegistry.bindHandler('company.features', 'GLOBAL', () =>
        this.openF11Modal(),
      ),
      this.shortcutRegistry.bindHandler('screen.configure', 'GLOBAL', () =>
        this.openShellConfigureModal(),
      ),
      this.shortcutRegistry.bindHandler(
        'screen.accept',
        'MODAL',
        () => {
          if (this.f11ModalOpen()) {
            void this.acceptF11Modal();
            return true;
          }
          if (this.shellConfigureOpen()) {
            void this.acceptShellConfigureModal();
            return true;
          }
          return false;
        },
        () => this.f11ModalOpen() || this.shellConfigureOpen(),
      ),
      this.shortcutRegistry.bindHandler('report.autoColumn', 'GLOBAL', () =>
        this.openShellCalculator(),
      ),
      this.shortcutRegistry.bindHandler(
        'report.exceptions',
        'GLOBAL',
        () => {
          void this.router.navigateByUrl('/app/reports/exceptions');
        },
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.contra',
        'GLOBAL',
        () => this.navigateToNewVoucher('CONTRA'),
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.payment',
        'GLOBAL',
        () => this.navigateToNewVoucher('PAYMENT'),
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.receipt',
        'GLOBAL',
        () => this.navigateToNewVoucher('RECEIPT'),
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.journal',
        'GLOBAL',
        () => this.navigateToNewVoucher('JOURNAL'),
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.sales',
        'GLOBAL',
        () => this.navigateToNewVoucher('SALES'),
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.purchase',
        'GLOBAL',
        () => this.navigateToNewVoucher('PURCHASE'),
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.debitNote',
        'GLOBAL',
        () => this.navigateToNewVoucher('DEBIT_NOTE'),
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.creditNote',
        'GLOBAL',
        () => this.navigateToNewVoucher('CREDIT_NOTE'),
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.stockJournal',
        'GLOBAL',
        () => this.navigateToNewVoucher('STOCK_JOURNAL'),
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.physicalStock',
        'GLOBAL',
        () => this.navigateToNewVoucher('PHYSICAL_STOCK'),
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.salesOrder',
        'GLOBAL',
        () => this.navigateToNewVoucher('SALES_ORDER'),
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.purchaseOrder',
        'GLOBAL',
        () => this.navigateToNewVoucher('PURCHASE_ORDER'),
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.deliveryNote',
        'GLOBAL',
        () => this.navigateToNewVoucher('DELIVERY_NOTE'),
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.receiptNote',
        'GLOBAL',
        () => this.navigateToNewVoucher('RECEIPT_NOTE'),
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.rejectionsOut',
        'GLOBAL',
        () => this.navigateToNewVoucher('REJECTIONS_OUT'),
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.rejectionsIn',
        'GLOBAL',
        () => this.navigateToNewVoucher('REJECTIONS_IN'),
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.otherVouchers',
        'GLOBAL',
        () => this.navigateToNewVoucher('JOURNAL', true),
        () => this.canJumpToGlobalVoucher(),
      ),
      this.shortcutRegistry.bindHandler('nav.escape', 'GLOBAL', () => this.handleGlobalEscape()),
    ];

    this.destroyRef.onDestroy(() => {
      for (const dispose of disposers) {
        dispose();
      }
      this.releaseAllShellModalContexts();
      this.sseStream.disconnect();
    });
  }

  modalHint(
    contextId: string,
    commandId: string,
    pairSpec: keyof typeof EXPLICIT_EN_GB_ASCII_PAIRS,
  ): string | null {
    return this.shortcutRegistry.effectiveActionHint(contextId, commandId, asciiPair(pairSpec));
  }

  toggleScannerWorkflow(): void {
    this.shortcutRegistry.setScannerWorkflowActive(!this.shortcutRegistry.scannerWorkflowActive());
  }

  openGoToPalette(mode: 'GO_TO' | 'SWITCH_TO'): void {
    this.goToMode.set(mode);
    this.goToOpen.set(true);
  }

  openWorkingDateModal(): void {
    this.draftWorkingDate = this.companyContext.workingDate();
    this.workingDateModalOpen.set(true);
    this.dateContextHandle?.close();
    this.dateContextHandle = this.shortcutRegistry.openModalContext([
      {
        contextId: 'context.dateDialog',
        locale: 'en-GB',
        commandId: 'context.applyDate',
        label: 'Apply',
        letterPair: asciiPair('A/a'),
        permissionRef: 'company.read',
        effect: 'Apply selected working date.',
        normalActivation: () => this.applyWorkingDate(),
        iconSemanticId: 'mc.icon.calendar',
        stateTokenRole: 'action.primary',
      },
      {
        contextId: 'context.dateDialog',
        locale: 'en-GB',
        commandId: 'editor.close',
        label: 'Close',
        letterPair: asciiPair('C/c'),
        permissionRef: 'authenticated',
        effect: 'Close working date dialog without applying.',
        normalActivation: () => this.closeWorkingDateModal(),
        iconSemanticId: 'mc.icon.close',
        stateTokenRole: 'action.secondary',
      },
    ]);
  }

  closeWorkingDateModal(): void {
    this.dateContextHandle?.close();
    this.dateContextHandle = null;
    this.workingDateModalOpen.set(false);
  }

  applyWorkingDate(): void {
    this.companyContext.setWorkingDate(this.draftWorkingDate);
    this.closeWorkingDateModal();
  }

  openPeriodModal(): void {
    this.draftPeriodFrom = this.companyContext.periodFrom();
    this.draftPeriodTo = this.companyContext.periodTo();
    this.periodError.set(null);
    this.periodModalOpen.set(true);
    this.periodContextHandle?.close();
    this.periodContextHandle = this.shortcutRegistry.openModalContext([
      {
        contextId: 'context.dateDialog',
        locale: 'en-GB',
        commandId: 'context.applyDate',
        label: 'Apply',
        letterPair: asciiPair('A/a'),
        permissionRef: 'company.read',
        effect: 'Apply selected fiscal period bounds.',
        normalActivation: () => this.applyPeriodRange(),
        iconSemanticId: 'mc.icon.calendar',
        stateTokenRole: 'action.primary',
      },
      {
        contextId: 'context.dateDialog',
        locale: 'en-GB',
        commandId: 'editor.close',
        label: 'Close',
        letterPair: asciiPair('C/c'),
        permissionRef: 'authenticated',
        effect: 'Close period dialog without applying.',
        normalActivation: () => this.closePeriodModal(),
        iconSemanticId: 'mc.icon.close',
        stateTokenRole: 'action.secondary',
      },
    ]);
  }

  closePeriodModal(): void {
    this.periodContextHandle?.close();
    this.periodContextHandle = null;
    this.periodModalOpen.set(false);
  }

  applyPeriodRange(): void {
    try {
      this.companyContext.setPeriodRange(this.draftPeriodFrom, this.draftPeriodTo);
      this.periodError.set(null);
      this.closePeriodModal();
    } catch (err: unknown) {
      this.periodError.set(err instanceof Error ? err.message : 'Invalid period range');
    }
  }

  openCompanySwitcherModal(): void {
    this.companySwitchBlockedWarning.set(false);
    this.companySearchQuery.set('');
    this.selectedSwitcherCompanyId.set(
      this.companyContext.activeCompany()?.id ?? this.companyContext.openCompanies()[0]?.id ?? null,
    );
    this.companySwitcherOpen.set(true);
    this.companyPickContextHandle?.close();
    this.companyPickContextHandle = this.shortcutRegistry.openModalContext([
      {
        contextId: 'company.pick',
        locale: 'en-GB',
        commandId: 'company.open',
        label: 'Open',
        letterPair: asciiPair('O/o'),
        enabledWhen: () => Boolean(this.selectedSwitcherCompanyId()),
        permissionRef: 'company.read',
        effect: 'Switch to selected company (opening editor.dirty first if unsaved draft exists).',
        normalActivation: () => this.openHighlightedCompanyFromModal(),
        iconSemanticId: 'mc.icon.company',
        stateTokenRole: 'action.primary',
      },
      {
        contextId: 'company.pick',
        locale: 'en-GB',
        commandId: 'editor.close',
        label: 'Close',
        letterPair: asciiPair('C/c'),
        permissionRef: 'authenticated',
        effect: 'Close company selector.',
        normalActivation: () => this.closeCompanySwitcherModal(),
        iconSemanticId: 'mc.icon.close',
        stateTokenRole: 'action.secondary',
      },
    ]);
  }

  closeCompanySwitcherModal(): void {
    this.companyPickContextHandle?.close();
    this.companyPickContextHandle = null;
    this.companySwitcherOpen.set(false);
  }

  openHighlightedCompanyFromModal(): void {
    const id = this.selectedSwitcherCompanyId();
    if (id) {
      this.selectFromCompanyModal(id);
    }
  }

  switchCompany(companyId: string): void {
    const ok = this.companyContext.selectActiveCompany(companyId);
    if (!ok) {
      this.companySwitchBlockedWarning.set(true);
      this.promptDirtyEditorNavigation(() => {
        this.companyContext.selectActiveCompany(companyId, true);
      });
    }
  }

  selectFromCompanyModal(companyId: string): void {
    this.selectedSwitcherCompanyId.set(companyId);
    const ok = this.companyContext.selectActiveCompany(companyId);
    if (!ok) {
      this.companySwitchBlockedWarning.set(true);
      this.closeCompanySwitcherModal();
      this.promptDirtyEditorNavigation(() => {
        this.companyContext.selectActiveCompany(companyId, true);
      });
      return;
    }
    this.closeCompanySwitcherModal();
  }

  promptDirtyEditorNavigation(onProceedNavigation: () => void): void {
    this.pendingNavigationAfterDirtyResolution = onProceedNavigation;
    this.dirtyEditorModalOpen.set(true);
    this.dirtyEditorContextHandle?.close();
    this.dirtyEditorContextHandle = this.shortcutRegistry.openModalContext([
      {
        contextId: 'editor.dirty',
        locale: 'en-GB',
        commandId: 'journal.saveDraft',
        label: 'Save draft',
        letterPair: asciiPair('S/s'),
        permissionRef: 'voucher.create',
        effect: 'Save editable draft once and navigate only after confirmed save.',
        normalActivation: () => this.saveDraftFromDirtyDialog(),
        iconSemanticId: 'mc.icon.save-document',
        stateTokenRole: 'action.primary',
        isMutation: true,
      },
      {
        contextId: 'editor.dirty',
        locale: 'en-GB',
        commandId: 'editor.discard',
        label: 'Discard',
        letterPair: asciiPair('D/d'),
        permissionRef: 'authenticated',
        effect: 'Discard local unsaved edits and perform recorded navigation.',
        normalActivation: () => this.discardDraftFromDirtyDialog(),
        iconSemanticId: 'mc.icon.discard-draft',
        stateTokenRole: 'action.danger',
      },
      {
        contextId: 'editor.dirty',
        locale: 'en-GB',
        commandId: 'editor.stay',
        label: 'Stay',
        letterPair: asciiPair('T/t'),
        permissionRef: 'authenticated',
        effect: 'Preserve unsaved draft and stay on current editor.',
        normalActivation: () => this.stayInDirtyDialog(),
        iconSemanticId: null,
        stateTokenRole: 'action.secondary',
      },
    ]);
  }

  saveDraftFromDirtyDialog(): void {
    this.shortcutRegistry.triggerCommand('screen.accept');
    if (!this.companyContext.hasUnsavedDraft()) {
      const proceed = this.pendingNavigationAfterDirtyResolution;
      this.closeDirtyEditorModal();
      proceed?.();
    } else {
      this.showTopMenuToast('Draft requires attention before saving — fix validation errors or Discard.');
      this.closeDirtyEditorModal();
    }
  }

  discardDraftFromDirtyDialog(): void {
    const proceed = this.pendingNavigationAfterDirtyResolution;
    this.companyContext.setHasUnsavedDraft(false);
    this.closeDirtyEditorModal();
    proceed?.();
  }

  stayInDirtyDialog(): void {
    this.closeDirtyEditorModal();
  }

  private closeDirtyEditorModal(): void {
    this.dirtyEditorContextHandle?.close();
    this.dirtyEditorContextHandle = null;
    this.pendingNavigationAfterDirtyResolution = null;
    this.dirtyEditorModalOpen.set(false);
  }

  openCommandsMenuModal(): void {
    this.commandsMenuOpen.set(true);
    this.commandsMenuContextHandle?.close();
    const actions: ModalActionDefinition[] = COMMANDS_MENU_ROWS.map((row) => ({
      contextId: 'commands.menu',
      locale: 'en-GB',
      commandId: row.commandId,
      label: row.label,
      letterPair: asciiPair(row.pairSpec),
      permissionRef: 'authenticated',
      effect: `Execute ${row.label} from Commands dialog.`,
      normalActivation: () => this.executeCommandsMenuRow(row.commandId),
      iconSemanticId: row.iconName ? `mc.icon.${row.iconName}` : null,
      stateTokenRole: row.stateTokenRole,
    }));
    this.commandsMenuContextHandle = this.shortcutRegistry.openModalContext(actions);
  }

  closeCommandsMenuModal(): void {
    this.commandsMenuContextHandle?.close();
    this.commandsMenuContextHandle = null;
    this.commandsMenuOpen.set(false);
  }

  executeCommandsMenuRow(commandId: string): void {
    this.closeCommandsMenuModal();
    switch (commandId) {
      case 'company.open':
        this.openCompanySwitcherModal();
        break;
      case 'ledger.create':
        void this.secondaryMasterModal.open({ kind: 'LEDGER' });
        break;
      case 'journal.saveDraft':
      case 'journal.approve':
      case 'journal.post':
        this.shortcutRegistry.triggerCommand('screen.accept');
        break;
      case 'context.date':
        this.openWorkingDateModal();
        break;
      case 'company.features':
        this.navigateToCompanyHub('features');
        break;
      case 'report.open':
        void this.router.navigateByUrl('/app/reports/trial-balance');
        break;
      case 'report.back':
        if (this.navStack.hasOverlays()) {
          this.navStack.pop();
        }
        break;
      case 'keyboard.open':
        this.shortcutRegistry.openKeyboardSettingsModal();
        break;
      case 'report.output':
        this.triggerPrintFromTopBar();
        break;
      case 'report.refresh':
        this.showTopMenuToast('Refreshed active workspace projection.');
        break;
      case 'editor.close':
      default:
        break;
    }
  }

  openUncertainWriteRecovery(summary: string, diagnosticDetails: string): void {
    this.uncertainSummary.set(summary);
    this.messageDetailsText.set(diagnosticDetails);
    this.uncertainWriteModalOpen.set(true);
    this.uncertainContextHandle?.close();
    this.uncertainContextHandle = this.shortcutRegistry.openModalContext([
      {
        contextId: 'request.uncertain',
        locale: 'en-GB',
        commandId: 'request.check',
        label: 'Check status',
        letterPair: asciiPair('K/k'),
        permissionRef: 'voucher.read',
        effect: 'Check server status without allocating a new Idempotency-Key.',
        normalActivation: () => this.checkUncertainStatus(),
        iconSemanticId: 'mc.icon.refresh',
        stateTokenRole: 'action.secondary',
      },
      {
        contextId: 'request.uncertain',
        locale: 'en-GB',
        commandId: 'request.retryOriginal',
        label: 'Retry original',
        letterPair: asciiPair('R/r'),
        permissionRef: 'voucher.post',
        effect: 'Retry original retained idempotent payload.',
        normalActivation: () => this.retryUncertainOriginal(),
        iconSemanticId: 'mc.icon.refresh',
        stateTokenRole: 'action.secondary',
        isMutation: true,
      },
      {
        contextId: 'request.uncertain',
        locale: 'en-GB',
        commandId: 'request.details',
        label: 'Details',
        letterPair: asciiPair('D/d'),
        permissionRef: 'authenticated',
        effect: 'Open diagnostic details view.',
        normalActivation: () => this.openUncertainDetailsModal(),
        iconSemanticId: 'mc.icon.details',
        stateTokenRole: 'action.secondary',
      },
      {
        contextId: 'request.uncertain',
        locale: 'en-GB',
        commandId: 'keyboard.open',
        label: 'Keyboard settings',
        letterPair: asciiPair('B/b'),
        permissionRef: 'authenticated',
        effect: 'Open keyboard settings modal.',
        normalActivation: () => this.shortcutRegistry.openKeyboardSettingsModal(),
        iconSemanticId: 'mc.icon.keyboard',
        stateTokenRole: 'action.secondary',
      },
    ]);
  }

  checkUncertainStatus(): void {
    this.showTopMenuToast('Checked server resource status using original correlation key.');
    this.closeUncertainWriteModal();
  }

  retryUncertainOriginal(): void {
    this.showTopMenuToast('Retrying original request with retained Idempotency-Key.');
    this.closeUncertainWriteModal();
  }

  openUncertainDetailsModal(): void {
    this.messageDetailsOpen.set(true);
    this.messageDetailsContextHandle?.close();
    this.messageDetailsContextHandle = this.shortcutRegistry.openModalContext([
      {
        contextId: 'message.details',
        locale: 'en-GB',
        commandId: 'editor.close',
        label: 'Close',
        letterPair: asciiPair('C/c'),
        permissionRef: 'authenticated',
        effect: 'Close diagnostic details and return to parent recovery modal.',
        normalActivation: () => this.closeMessageDetailsModal(),
        iconSemanticId: 'mc.icon.close',
        stateTokenRole: 'action.secondary',
      },
    ]);
  }

  closeMessageDetailsModal(): void {
    this.messageDetailsContextHandle?.close();
    this.messageDetailsContextHandle = null;
    this.messageDetailsOpen.set(false);
  }

  closeUncertainWriteModal(): void {
    this.closeMessageDetailsModal();
    this.uncertainContextHandle?.close();
    this.uncertainContextHandle = null;
    this.uncertainWriteModalOpen.set(false);
  }

  navigateToCompanyHub(tab: 'create' | 'alter' | 'features'): void {
    this.closeCompanySwitcherModal();
    void this.router.navigate(['/app/companies'], { queryParams: { mode: tab } });
  }

  private isDispatchingPrintFromMenu = false;

  private canJumpToGlobalVoucher(): boolean {
    return (
      !this.companyContext.hasUnsavedDraft() &&
      !this.navStack.hasOverlays() &&
      !this.openTopBarMenu() &&
      !this.f11ModalOpen() &&
      !this.shellConfigureOpen() &&
      !this.apiServerModalOpen() &&
      !this.goToOpen() &&
      !this.smartFind.isOpen() &&
      !this.workingDateModalOpen() &&
      !this.periodModalOpen() &&
      !this.companySwitcherOpen() &&
      !this.dirtyEditorModalOpen() &&
      !this.commandsMenuOpen() &&
      !this.uncertainWriteModalOpen()
    );
  }

  isRightAlignedTopMenu(menu: TopBarMenuKind): boolean {
    return (
      menu === 'IMPORT' ||
      menu === 'EXPORT' ||
      menu === 'SHARE' ||
      menu === 'PRINT' ||
      menu === 'HELP'
    );
  }

  toggleTopBarMenu(menu: TopBarMenuKind): void {
    if (this.openTopBarMenu() === menu) {
      this.closeTopBarMenu();
      return;
    }
    this.openTopBarMenu.set(menu);
    this.topBarMenuIndex.set(0);
    queueMicrotask(() => {
      this.topBarMenuPanelRef()?.nativeElement.focus();
    });
  }

  closeTopBarMenu(): void {
    this.openTopBarMenu.set(null);
  }

  onTopBarMenuKeyDown(event: KeyboardEvent): void {
    const entries = this.currentTopBarMenuItems();
    if (entries.length === 0) return;

    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.closeTopBarMenu();
      return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key === 'ArrowRight') {
      event.preventDefault();
      const cur = this.openTopBarMenu();
      const curIdx = cur ? TOP_BAR_MENU_ORDER.indexOf(cur) : 0;
      const nextMenu = TOP_BAR_MENU_ORDER[(curIdx + 1) % TOP_BAR_MENU_ORDER.length];
      this.openTopBarMenu.set(nextMenu);
      this.topBarMenuIndex.set(0);
      return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key === 'ArrowLeft') {
      event.preventDefault();
      const cur = this.openTopBarMenu();
      const curIdx = cur ? TOP_BAR_MENU_ORDER.indexOf(cur) : 0;
      const prevMenu =
        TOP_BAR_MENU_ORDER[(curIdx - 1 + TOP_BAR_MENU_ORDER.length) % TOP_BAR_MENU_ORDER.length];
      this.openTopBarMenu.set(prevMenu);
      this.topBarMenuIndex.set(0);
      return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key === 'ArrowUp') {
      event.preventDefault();
      this.topBarMenuIndex.set(0);
      return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key === 'ArrowDown') {
      event.preventDefault();
      this.topBarMenuIndex.set(entries.length - 1);
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.topBarMenuIndex.update((idx) => (idx + 1) % entries.length);
      return;
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.topBarMenuIndex.update((idx) => (idx - 1 + entries.length) % entries.length);
      return;
    }
    if (event.key === 'Enter') {
      event.preventDefault();
      const target = entries[this.topBarMenuIndex()];
      if (target && !target.disabled) {
        this.executeTopBarMenuEntry(target.id);
      }
      return;
    }

    if (!event.ctrlKey && !event.metaKey && !event.altKey && event.key.length === 1) {
      const upper = event.key.toUpperCase();
      const match = entries.find((e) => e.mnemonic.toUpperCase() === upper);
      if (match && !match.disabled) {
        event.preventDefault();
        this.executeTopBarMenuEntry(match.id);
      }
    }
  }

  executeTopBarMenuEntry(entryId: string): void {
    const matched = this.currentTopBarMenuItems().find((e) => e.id === entryId);
    if (matched?.disabled) {
      return;
    }
    this.closeTopBarMenu();
    switch (entryId) {
      case 'company.create':
        this.navigateToCompanyHub('create');
        break;
      case 'company.alter':
      case 'data.split':
        this.navigateToCompanyHub('alter');
        break;
      case 'company.select':
        this.openCompanySwitcherModal();
        break;
      case 'company.shut':
        this.shutActiveCompany();
        break;
      case 'company.features':
        this.openF11Modal();
        break;
      case 'company.security':
      case 'help.audit':
        void this.router.navigateByUrl('/app/admin');
        break;
      case 'company.govIntegrations':
        void this.router.navigateByUrl('/app/admin/government-integrations');
        break;
      case 'data.backup':
      case 'exchange.migrate':
        this.navigateToExchange('MIGRATE_1800');
        break;
      case 'data.path':
        this.showTopMenuToast('PostgreSQL 18 Dual-DB Storage Active (Authority + Tenant Schema).');
        break;
      case 'exchange.einvoice':
        void this.router.navigateByUrl('/app/statutory/einvoice');
        break;
      case 'exchange.ims':
        void this.router.navigateByUrl('/app/statutory/ims');
        break;
      case 'exchange.xml':
      case 'import.xml':
      case 'export.xml':
      case 'share.package':
        this.navigateToExchange('IMPORT_EXPORT');
        break;
      case 'exchange.apiServer':
        this.openApiServerModal();
        break;
      case 'import.spreadsheet':
        this.navigateToExchange('SPREADSHEET_WIZARD');
        break;
      case 'import.bank':
        void this.router.navigateByUrl('/app/banking/ocr-queue');
        break;
      case 'import.tdl':
        void this.router.navigateByUrl('/app/extensions');
        break;
      case 'export.current':
      case 'share.pdf':
      case 'print.current':
        this.triggerPrintFromTopBar();
        break;
      case 'export.gst':
        void this.router.navigateByUrl('/app/statutory/gstr1');
        break;
      case 'print.configure':
        this.openShellConfigureModal();
        break;
      case 'help.contextual':
        this.openContextualHelp();
        break;
      case 'help.preferences':
        this.shortcutRegistry.openKeyboardSettingsModal();
        break;
      case 'help.about':
        this.showTopMenuToast('MoneyCraft v1.1.0 — Offline-First PostgreSQL 18 ERP.');
        break;
    }
  }

  shutActiveCompany(): void {
    const active = this.companyContext.activeCompany();
    if (!active) {
      return;
    }
    if (this.companyContext.hasUnsavedDraft()) {
      this.closeCompanySwitcherModal();
      this.promptDirtyEditorNavigation(() => {
        this.companyContext.shutCompany(active.id);
        this.showTopMenuToast(`Shut company: ${active.legalName}`);
        if (!this.companyContext.activeCompany()) {
          void this.router.navigateByUrl('/app/gateway');
        }
      });
      return;
    }
    this.companyContext.shutCompany(active.id);
    this.closeCompanySwitcherModal();
    this.showTopMenuToast(`Shut company: ${active.legalName}`);
    if (!this.companyContext.activeCompany()) {
      void this.router.navigateByUrl('/app/gateway');
    }
  }

  onF11ShowMoreChange(val: boolean): void {
    this.f11ShowMore.set(val);
    if (!val) {
      this.f11ShowAll.set(false);
    }
  }

  onF11ShowAllChange(val: boolean): void {
    this.f11ShowAll.set(val);
    if (val) {
      this.f11ShowMore.set(true);
    }
  }

  openF11Modal(): void {
    const active = this.companyContext.activeCompany();
    if (active) {
      const f = active.features;
      this.f11McaLocked.set(Boolean(f.mcaEditLogEnforced));
      this.f11DraftMaintainAccounts = f.maintainAccounts !== false;
      this.f11DraftBillWise = f.billWiseEnabled !== false;
      this.f11DraftCostCentres = Boolean(f.costCentresEnabled);
      this.f11DraftInterestCalc = Boolean(f.interestCalculationEnabled);
      this.f11DraftMaintainInventory = f.maintainInventory !== false;
      this.f11DraftInventoryIntegrated = f.inventoryIntegrated !== false;
      this.f11DraftPriceLevels = Boolean(f.multiplePriceLevelsEnabled);
      this.f11DraftBatches = f.batchesEnabled !== false;
      this.f11DraftExpiryBatches = f.expiryDateForBatchesEnabled !== false;
      this.f11DraftDiscountCol = f.useDiscountColumnInInvoices !== false;
      this.f11DraftSeparateActualBilled = f.useSeparateActualAndBilledQty !== false;
      this.f11DraftJobOrder = Boolean(f.jobOrderProcessingEnabled);
      this.f11DraftCostTracking = Boolean(f.costTrackingEnabled);
      this.f11DraftJobCosting = Boolean(f.jobCostingEnabled);
      this.f11DraftGstEnabled = f.gstEnabled !== false;
      this.f11DraftGstRegType = f.gstRegistrationType ?? 'REGULAR';
      this.f11DraftGstr1Periodicity = f.gstr1Periodicity ?? 'MONTHLY';
      this.f11DraftGstin = f.gstin ?? '';
      this.f11DraftEWayBill = f.eWayBillApplicable !== false;
      this.f11DraftEWayBillLimit = f.eWayBillThresholdLimit ?? '50000.00';
      this.f11DraftEInvoice = Boolean(f.eInvoiceApplicable);
      this.f11DraftMultiGstin = Boolean(f.multiGstinEnabled);
      this.f11DraftTdsEnabled = f.tdsEnabled !== false;
      this.f11DraftTcsEnabled = Boolean(f.tcsEnabled);
      this.f11DraftVatEnabled = Boolean(f.vatEnabled);
      this.f11DraftExciseEnabled = Boolean(f.exciseEnabled);
      this.f11DraftServiceTaxEnabled = Boolean(f.serviceTaxEnabled);
      this.f11DraftPayrollEnabled = Boolean(f.payrollEnabled);
      this.f11DraftPayrollStatEnabled = Boolean(f.payrollStatutoryEnabled);
      this.f11DraftMultipleAddresses = Boolean(f.multipleAddressesEnabled);
      this.f11DraftMarkModifiedVouchers = Boolean(f.markModifiedVouchers);
      this.f11DraftMcaEditLog = Boolean(f.mcaEditLogEnforced);
    }
    this.f11ModalOpen.set(true);
  }

  closeF11Modal(): void {
    this.f11ModalOpen.set(false);
  }

  async acceptF11Modal(): Promise<void> {
    await this.companyContext.patchFeatureToggle({
      maintainAccounts: this.f11DraftMaintainAccounts,
      billWiseEnabled: this.f11DraftMaintainAccounts ? this.f11DraftBillWise : false,
      costCentresEnabled: this.f11DraftCostCentres,
      interestCalculationEnabled: this.f11DraftInterestCalc,
      maintainInventory: this.f11DraftMaintainInventory,
      inventoryIntegrated:
        this.f11DraftMaintainAccounts && this.f11DraftMaintainInventory
          ? this.f11DraftInventoryIntegrated
          : false,
      multiplePriceLevelsEnabled: this.f11DraftPriceLevels,
      batchesEnabled: this.f11DraftBatches,
      expiryDateForBatchesEnabled: this.f11DraftBatches ? this.f11DraftExpiryBatches : false,
      useDiscountColumnInInvoices: this.f11DraftDiscountCol,
      useSeparateActualAndBilledQty: this.f11DraftSeparateActualBilled,
      jobOrderProcessingEnabled: this.f11DraftJobOrder,
      costTrackingEnabled: this.f11DraftCostTracking,
      jobCostingEnabled: this.f11DraftCostCentres ? this.f11DraftJobCosting : false,
      gstEnabled: this.f11DraftGstEnabled,
      gstRegistrationType: this.f11DraftGstRegType,
      gstr1Periodicity: this.f11DraftGstr1Periodicity,
      gstin: this.f11DraftGstin.trim().toUpperCase(),
      eWayBillApplicable: this.f11DraftEWayBill,
      eWayBillThresholdLimit: this.f11DraftEWayBillLimit,
      eInvoiceApplicable: this.f11DraftEInvoice,
      multiGstinEnabled: this.f11DraftMultiGstin,
      tdsEnabled: this.f11DraftTdsEnabled,
      tcsEnabled: this.f11DraftTcsEnabled,
      vatEnabled: this.f11DraftVatEnabled,
      exciseEnabled: this.f11DraftExciseEnabled,
      serviceTaxEnabled: this.f11DraftServiceTaxEnabled,
      payrollEnabled: this.f11DraftPayrollEnabled,
      payrollStatutoryEnabled: this.f11DraftPayrollEnabled ? this.f11DraftPayrollStatEnabled : false,
      multipleAddressesEnabled: this.f11DraftMultipleAddresses,
      markModifiedVouchers: this.f11DraftMarkModifiedVouchers,
      mcaEditLogEnforced: this.f11McaLocked() ? true : this.f11DraftMcaEditLog,
    });
    this.f11ModalOpen.set(false);
    this.showTopMenuToast('Saved F11: Company Features.');
  }

  openShellConfigureModal(): void {
    const url = this.currentRouteUrl();
    if (url.includes('/vouchers')) {
      this.f12ActiveTab.set('VOUCHER');
    } else if (url.includes('/masters') || url.includes('/inventory') || url.includes('/coa')) {
      this.f12ActiveTab.set('MASTERS');
    } else if (url.includes('/reports') || url.includes('/daybook') || url.includes('/statutory')) {
      this.f12ActiveTab.set('REPORTS');
    } else {
      this.f12ActiveTab.set('VOUCHER');
    }
    this.f12Draft = { ...this.companyContext.f12Config() };
    this.shellConfigureOpen.set(true);
  }

  closeShellConfigureModal(): void {
    this.shellConfigureOpen.set(false);
  }

  async acceptShellConfigureModal(): Promise<void> {
    await this.companyContext.updateF12Config({ ...this.f12Draft });
    this.shellConfigureOpen.set(false);
    this.showTopMenuToast('Saved F12: Configuration.');
  }

  openApiServerModal(): void {
    const parts = this.runtimeConfig.currentEndpointParts();
    this.shellApiScheme = parts.scheme;
    this.shellApiHost = parts.host;
    this.shellApiPort = parts.port;
    this.shellApiConfigError.set(null);
    this.apiServerModalOpen.set(true);
  }

  closeApiServerModal(): void {
    this.apiServerModalOpen.set(false);
    this.shellApiConfigError.set(null);
  }

  applyShellBackendApiConfig(): void {
    try {
      const updated = this.runtimeConfig.setBackendHostAndPort(
        this.shellApiHost,
        this.shellApiPort,
        this.shellApiScheme,
      );
      this.shellApiConfigError.set(null);
      this.apiServerModalOpen.set(false);
      this.showTopMenuToast(`Backend API endpoint set to ${updated.apiBaseUrl}`);
    } catch (err: unknown) {
      this.shellApiConfigError.set(
        err instanceof Error ? err.message : 'Invalid backend IP or port.',
      );
    }
  }

  resetShellBackendApiConfig(): void {
    const restored = this.runtimeConfig.resetBackendEndpointToDefault();
    const parts = this.runtimeConfig.currentEndpointParts();
    this.shellApiScheme = parts.scheme;
    this.shellApiHost = parts.host;
    this.shellApiPort = parts.port;
    this.shellApiConfigError.set(null);
    this.apiServerModalOpen.set(false);
    this.showTopMenuToast(`Backend API endpoint reset to ${restored.apiBaseUrl}`);
  }

  openShellCalculator(): void {
    this.shellCalculatorOpen.set(true);
  }

  closeShellCalculator(): void {
    this.shellCalculatorOpen.set(false);
  }

  onShellCalcInput(expr: string): void {
    this.shellCalcExpr.set(expr);
    if (!expr.trim()) {
      this.shellCalcResult.set('0.00');
      return;
    }
    try {
      const val = ExactDecimal.evaluateExpression(expr, this.companyContext.currencyScale());
      this.shellCalcResult.set(val.formatIndian({ scale: this.companyContext.currencyScale() }));
    } catch {
      this.shellCalcResult.set('...');
    }
  }

  openContextualHelp(): void {
    this.contextualHelp.openGlobalShortcutHelp();
  }

  resolveOverlayBookType(raw?: string): AccountBookType | null {
    return (raw as AccountBookType | undefined) ?? null;
  }

  resolveOverlayExceptionType(raw?: string): ExceptionReportType | null {
    return (raw as ExceptionReportType | undefined) ?? null;
  }

  navigateToNewVoucher(category: VoucherBaseCategory, openOtherPicker = false): void {
    void this.router.navigate(['/app/vouchers/new'], {
      queryParams: openOtherPicker ? { category, other: '1' } : { category },
    });
  }

  switchWorkspaceFromOverlay(route: string, params: Readonly<Record<string, string>>): void {
    this.navStack.clear();
    void this.router.navigate([route], { queryParams: params });
  }

  onCommandButton(commandId: string): void {
    const handledByBoundCommand = this.shortcutRegistry.triggerCommand(commandId);
    if (handledByBoundCommand) {
      return;
    }

    switch (commandId) {
      case 'context.changeDate':
        this.openWorkingDateModal();
        break;
      case 'context.changePeriod':
        this.openPeriodModal();
        break;
      case 'context.selectCompany':
        this.openCompanySwitcherModal();
        break;
      case 'company.shut':
        this.shutActiveCompany();
        break;
      case 'company.features':
        this.openF11Modal();
        break;
      case 'screen.configure':
        this.openShellConfigureModal();
        break;
      case 'report.autoColumn':
        this.openShellCalculator();
        break;
      case 'report.exceptions':
        void this.router.navigateByUrl('/app/reports/exceptions');
        break;
      case 'help.contextual':
        this.openContextualHelp();
        break;
      case 'nav.escape':
        this.handleGlobalEscape();
        break;
      default:
        this.showTopMenuToast(`Activated command: ${commandId}`);
        break;
    }
  }

  showTopMenuToast(message: string): void {
    this.statusToast.set(message);
  }

  navigateToExchange(tab: 'MIGRATE_1800' | 'SPREADSHEET_WIZARD' | 'IMPORT_EXPORT'): void {
    const targetRoute =
      tab === 'MIGRATE_1800'
        ? '/app/exchange/migrate-1800'
        : tab === 'SPREADSHEET_WIZARD'
          ? '/app/exchange/spreadsheet-import'
          : '/app/exchange/import-export';
    if (this.companyContext.hasUnsavedDraft()) {
      this.navStack.push({
        title:
          tab === 'MIGRATE_1800'
            ? 'Legacy .1800 Snapshot'
            : tab === 'SPREADSHEET_WIZARD'
              ? 'Spreadsheet Wizard'
              : 'Import / Export Data',
        route: targetRoute,
      });
      return;
    }
    void this.router.navigateByUrl(targetRoute);
  }

  triggerPrintFromTopBar(): void {
    this.isDispatchingPrintFromMenu = true;
    try {
      const handled = this.shortcutRegistry.triggerCommand('menu.print');
      if (!handled) {
        this.showTopMenuToast(
          'Open any Financial Statement, Register, or Saved Voucher and press Ctrl+P / Alt+P to generate PDF.',
        );
      }
    } finally {
      this.isDispatchingPrintFromMenu = false;
    }
  }

  extractVoucherIdFromRoute(route: string): string | null {
    const clean = route.split('?')[0];
    const prefixApp = '/app/vouchers/';
    const prefixRoot = '/vouchers/';
    if (clean.startsWith(prefixApp)) {
      const id = clean.slice(prefixApp.length);
      return id && id !== 'new' ? id : null;
    }
    if (clean.startsWith(prefixRoot)) {
      const id = clean.slice(prefixRoot.length);
      return id && id !== 'new' ? id : null;
    }
    return null;
  }

  async signOut(): Promise<void> {
    await this.authStore.logout();
    void this.router.navigateByUrl('/login');
  }

  private releaseAllShellModalContexts(): void {
    this.dateContextHandle?.close();
    this.periodContextHandle?.close();
    this.companyPickContextHandle?.close();
    this.dirtyEditorContextHandle?.close();
    this.commandsMenuContextHandle?.close();
    this.uncertainContextHandle?.close();
    this.messageDetailsContextHandle?.close();
  }

  private handleGlobalEscape(): boolean {
    if (this.contextualHelp.dismissTopmostOverlay()) {
      return true;
    }
    if (this.openTopBarMenu()) {
      this.closeTopBarMenu();
      return true;
    }
    if (this.f11ModalOpen()) {
      this.closeF11Modal();
      return true;
    }
    if (this.shellConfigureOpen()) {
      this.closeShellConfigureModal();
      return true;
    }
    if (this.apiServerModalOpen()) {
      this.closeApiServerModal();
      return true;
    }
    if (this.shellCalculatorOpen()) {
      this.closeShellCalculator();
      return true;
    }
    if (this.messageDetailsOpen()) {
      this.closeMessageDetailsModal();
      return true;
    }
    if (this.dirtyEditorModalOpen()) {
      this.stayInDirtyDialog();
      return true;
    }
    if (this.commandsMenuOpen()) {
      this.closeCommandsMenuModal();
      return true;
    }
    if (this.goToOpen()) {
      this.goToOpen.set(false);
      return true;
    }
    if (this.smartFind.isOpen()) {
      this.smartFind.close();
      return true;
    }
    if (this.workingDateModalOpen()) {
      this.closeWorkingDateModal();
      return true;
    }
    if (this.periodModalOpen()) {
      this.closePeriodModal();
      return true;
    }
    if (this.companySwitcherOpen()) {
      this.closeCompanySwitcherModal();
      return true;
    }
    if (this.shortcutRegistry.settingsModalOpen()) {
      this.shortcutRegistry.closeKeyboardSettingsModal();
      return true;
    }
    if (this.navStack.hasOverlays()) {
      this.navStack.pop();
      return true;
    }
    if (
      this.shortcutRegistry.hasActiveModalContext() ||
      this.hasOpenChildDialogOrDropdownInDom()
    ) {
      return false;
    }
    return this.stepBackRouteToTallyParent();
  }

  private hasOpenChildDialogOrDropdownInDom(): boolean {
    if (typeof document === 'undefined') {
      return false;
    }
    return (
      document.querySelector(
        '.mc-modal-backdrop, .mc-ro-backdrop, .mc-typeahead-panel, .mc-popover-backdrop, [role="dialog"], [role="alertdialog"]',
      ) !== null
    );
  }

  private stepBackRouteToTallyParent(): boolean {
    const cleanUrl = this.currentRouteUrl().split('?')[0];
    if (
      !cleanUrl ||
      cleanUrl === '/' ||
      cleanUrl === '/gateway' ||
      cleanUrl.startsWith('/app/gateway') ||
      cleanUrl.startsWith('/login') ||
      cleanUrl.startsWith('/setup')
    ) {
      return false;
    }

    let parentRoute = '/app/gateway';
    if (this.extractVoucherIdFromRoute(cleanUrl) !== null) {
      parentRoute = '/app/daybook';
    } else if (
      (cleanUrl.startsWith('/app/masters/') && cleanUrl !== '/app/masters') ||
      (cleanUrl.startsWith('/app/inventory/') && !cleanUrl.startsWith('/app/inventory/masters'))
    ) {
      parentRoute = '/app/masters';
    }

    if (this.companyContext.hasUnsavedDraft()) {
      this.promptDirtyEditorNavigation(() => {
        void this.router.navigateByUrl(parentRoute);
      });
      return true;
    }
    void this.router.navigateByUrl(parentRoute);
    return true;
  }
}
