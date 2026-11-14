import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { AuthStore } from '../core/auth/auth.store';
import { SseStreamService } from '../core/http/sse-stream.service';
import { ShortcutCommandRegistry } from '../core/keyboard/shortcut-command.registry';
import { GodownWorkbenchComponent } from '../features/inventory/godown-workbench.component';
import { InventoryStore } from '../features/inventory/inventory.store';
import { PriceListWorkbenchComponent } from '../features/inventory/price-list-workbench.component';
import { StockGroupWorkbenchComponent } from '../features/inventory/stock-group-workbench.component';
import { StockItemWorkbenchComponent } from '../features/inventory/stock-item-workbench.component';
import { StockSummaryComponent } from '../features/inventory/stock-summary.component';
import { UomWorkbenchComponent } from '../features/inventory/uom-workbench.component';
import { CoaExplorerComponent } from '../features/masters/coa-explorer.component';
import { CostCentreWorkbenchComponent } from '../features/masters/cost-centre-workbench.component';
import { CurrencyWorkbenchComponent } from '../features/masters/currency-workbench.component';
import { GroupWorkbenchComponent } from '../features/masters/group-workbench.component';
import { LedgerWorkbenchComponent } from '../features/masters/ledger-workbench.component';
import { MastersHubComponent } from '../features/masters/masters-hub.component';
import { VoucherBaseCategory } from '../features/masters/masters.models';
import { SecondaryMasterModalComponent } from '../features/masters/secondary-master-modal.component';
import { SECONDARY_MASTER_ROUTE_TOKEN } from '../features/masters/secondary-master-modal.service';
import { VoucherTypeWorkbenchComponent } from '../features/masters/voucher-type-workbench.component';
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
import {
  FunctionBarButtonItem,
  FunctionBarComponent,
} from '../shared/components/function-bar/function-bar.component';
import { GoToPaletteComponent } from '../shared/components/go-to-palette/go-to-palette.component';
import { SmartFindModalComponent } from '../shared/components/smart-find-modal/smart-find-modal.component';
import { CompanyContextStore } from '../state/company-context.store';
import { NavigationStackStore } from '../state/navigation-stack.store';
import { SmartFindStore } from '../state/smart-find.store';

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
            QUARANTINE DISASTER-RECOVERY MODE ACTIVE (MC-SYSTEM-QUARANTINED)
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

      <!-- Top TallyPrime-Inspired Omni-Bar in MoneyCraft Brand v0.2.0 (#142F42) -->
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
            <button type="button" class="mc-omni-btn" (click)="openCompanySwitcherModal()">
              <span class="mc-key-alt">K</span>: Company
            </button>
            <button type="button" class="mc-omni-btn" (click)="showTopMenuToast('Data Backup & Restore (Alt+Y)')">
              <span class="mc-key-alt">Y</span>: Data
            </button>
            <button type="button" class="mc-omni-btn" (click)="showTopMenuToast('Data Exchange (Alt+Z)')">
              <span class="mc-key-alt">Z</span>: Exchange
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
          <button type="button" class="mc-omni-btn" (click)="showTopMenuToast('Import Masters / Vouchers (Alt+O)')">
            <span class="mc-key-alt">O</span>: Import
          </button>
          <button type="button" class="mc-omni-btn" (click)="showTopMenuToast('Export XML / JSONEx / Excel (Alt+E)')">
            <span class="mc-key-alt">E</span>: Export
          </button>
          <button type="button" class="mc-omni-btn" (click)="showTopMenuToast('Print / PDF Preview (Alt+P)')">
            <span class="mc-key-alt">P</span>: Print
          </button>

          @if (authStore.principal(); as user) {
            <span class="mc-user-chip" [title]="user.displayName">{{ user.username }}</span>
          }
          <button type="button" class="mc-omni-btn" (click)="signOut()">Sign Out</button>
        </div>
      </header>

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
                    Close Overlay (Esc)
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
                  @case ('/app/vouchers/new') {
                    <mc-voucher-grid [embeddedOverlay]="true" />
                  }
                  @case ('/vouchers/new') {
                    <mc-voucher-grid [embeddedOverlay]="true" />
                  }
                  @case ('/app/daybook') {
                    <mc-day-book [embeddedOverlay]="true" />
                  }
                  @case ('/daybook') {
                    <mc-day-book [embeddedOverlay]="true" />
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

      @if (workingDateModalOpen()) {
        <div class="mc-modal-backdrop" role="dialog" aria-modal="true" aria-label="Change Current Date (F2)">
          <form class="mc-modal-dialog mc-compact-modal" (ngSubmit)="applyWorkingDate()">
            <div class="mc-modal-header">
              <span>Change Current Date (F2)</span>
              <button type="button" class="mc-btn" (click)="workingDateModalOpen.set(false)">Esc</button>
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
              <span>Enter / Ctrl+A to Accept</span>
              <button type="submit" class="mc-btn mc-btn-primary">Accept</button>
            </div>
          </form>
        </div>
      }

      @if (periodModalOpen()) {
        <div class="mc-modal-backdrop" role="dialog" aria-modal="true" aria-label="Change Period (Alt+F2)">
          <form class="mc-modal-dialog mc-compact-modal" (ngSubmit)="applyPeriodRange()">
            <div class="mc-modal-header">
              <span>Change Period (<span class="mc-key-alt">F2</span>)</span>
              <button type="button" class="mc-btn" (click)="periodModalOpen.set(false)">Esc</button>
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
              <span>Enter / Ctrl+A to Accept</span>
              <button type="submit" class="mc-btn mc-btn-primary">Accept</button>
            </div>
          </form>
        </div>
      }

      @if (companySwitcherOpen()) {
        <div class="mc-modal-backdrop" role="dialog" aria-modal="true" aria-label="Select Company (F3)">
          <div class="mc-modal-dialog mc-compact-modal">
            <div class="mc-modal-header">
              <span>List of Companies (F3)</span>
              <button type="button" class="mc-btn" (click)="companySwitcherOpen.set(false)">Esc</button>
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
              </div>
              <ul class="mc-switcher-list">
                @for (comp of companyContext.openCompanies(); track comp.id) {
                  <li>
                    <button
                      type="button"
                      class="mc-switcher-row"
                      [class.mc-switcher-row-active]="companyContext.activeCompany()?.id === comp.id"
                      (click)="selectFromCompanyModal(comp.id)"
                    >
                      <span>{{ comp.legalName }}</span>
                      <mc-code-badge [code]="comp.code" />
                    </button>
                  </li>
                } @empty {
                  <li class="mc-switcher-empty">No companies loaded yet. Choose Create Company.</li>
                }
              </ul>
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

    .mc-quarantine-title {
      font-weight: 700;
      letter-spacing: 0.02em;
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

    .mc-goto-pill {
      background: var(--mc-color-verdigris-teal);
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

      &:hover {
        background: var(--mc-color-pressed-teal);
      }
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
      min-width: 240px;
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
        border-color: var(--mc-color-verdigris-teal);
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
      color: var(--mc-color-teal-700);
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
      border: 2px solid var(--mc-color-verdigris-teal);
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

    .mc-goto-overlay-title {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-goto-overlay-bar-btns {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-goto-overlay-content {
      padding: var(--mc-space-4);
    }

    .mc-goto-overlay-actions {
      margin-top: var(--mc-space-3);
    }

    .mc-compact-modal {
      width: min(440px, 92vw);
    }

    .mc-period-fields {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-switcher-actions {
      display: flex;
      gap: var(--mc-space-2);
      margin-bottom: var(--mc-space-3);
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
        border-color: var(--mc-color-verdigris-teal);
      }
    }

    .mc-switcher-row-active {
      background: var(--mc-color-teal-50);
      border-color: var(--mc-color-verdigris-teal);
    }

    .mc-switcher-empty {
      padding: var(--mc-space-2);
      color: var(--mc-color-slate-600);
      font-size: var(--mc-font-size-xs);
    }
  `,
})
export class ErpShellComponent implements OnInit {
  readonly authStore = inject(AuthStore);
  readonly companyContext = inject(CompanyContextStore);
  readonly navStack = inject(NavigationStackStore);
  readonly smartFind = inject(SmartFindStore);

  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly inventoryStore = inject(InventoryStore);
  private readonly sseStream = inject(SseStreamService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  constructor() {
    effect(() => {
      const cid = this.companyContext.activeCompany()?.id;
      if (cid) {
        void this.inventoryStore.ensureLoaded(cid);
      }
    });
  }

  readonly goToOpen = signal<boolean>(false);
  readonly goToMode = signal<'GO_TO' | 'SWITCH_TO'>('GO_TO');
  readonly workingDateModalOpen = signal<boolean>(false);
  readonly periodModalOpen = signal<boolean>(false);
  readonly companySwitcherOpen = signal<boolean>(false);
  readonly companySwitchBlockedWarning = signal<boolean>(false);
  readonly periodError = signal<string | null>(null);
  readonly statusToast = signal<string | null>(null);

  draftWorkingDate = '2025-04-01';
  draftPeriodFrom = '2025-04-01';
  draftPeriodTo = '2026-03-31';

  readonly goToOverlayFrames = computed(() =>
    this.navStack
      .frames()
      .filter(
        (f) => f.route !== SECONDARY_MASTER_ROUTE_TOKEN && f.route !== EDIT_LOG_ROUTE_TOKEN,
      ),
  );

  readonly rightRailItems = computed<readonly FunctionBarButtonItem[]>(() => [
    { commandId: 'context.changeDate', keycap: 'F2', modifierStyle: 'PLAIN', label: 'Date' },
    { commandId: 'context.changePeriod', keycap: 'F2', modifierStyle: 'ALT_SINGLE_UNDERLINE', label: 'Period' },
    { commandId: 'context.selectCompany', keycap: 'F3', modifierStyle: 'PLAIN', label: 'Company' },
    { commandId: 'voucher.contra', keycap: 'F4', modifierStyle: 'PLAIN', label: 'Contra' },
    { commandId: 'voucher.payment', keycap: 'F5', modifierStyle: 'PLAIN', label: 'Payment' },
    { commandId: 'voucher.receipt', keycap: 'F6', modifierStyle: 'PLAIN', label: 'Receipt' },
    { commandId: 'voucher.journal', keycap: 'F7', modifierStyle: 'PLAIN', label: 'Journal' },
    { commandId: 'voucher.sales', keycap: 'F8', modifierStyle: 'PLAIN', label: 'Sales' },
    { commandId: 'voucher.purchase', keycap: 'F9', modifierStyle: 'PLAIN', label: 'Purchase' },
    { commandId: 'voucher.otherVouchers', keycap: 'F10', modifierStyle: 'PLAIN', label: 'Other Vouchers' },
    { commandId: 'company.features', keycap: 'F11', modifierStyle: 'PLAIN', label: 'Features' },
    { commandId: 'screen.configure', keycap: 'F12', modifierStyle: 'PLAIN', label: 'Configure' },
    { commandId: 'report.toggleDetailed', keycap: 'F1', modifierStyle: 'ALT_SINGLE_UNDERLINE', label: 'Detailed' },
  ]);

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
    void this.companyContext.loadCompanies();
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
      this.shortcutRegistry.bindHandler('menu.company', 'GLOBAL', () =>
        this.openCompanySwitcherModal(),
      ),
      this.shortcutRegistry.bindHandler('company.features', 'GLOBAL', () =>
        this.navigateToCompanyHub('features'),
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
      this.sseStream.disconnect();
    });
  }

  openGoToPalette(mode: 'GO_TO' | 'SWITCH_TO'): void {
    this.goToMode.set(mode);
    this.goToOpen.set(true);
  }

  openWorkingDateModal(): void {
    this.draftWorkingDate = this.companyContext.workingDate();
    this.workingDateModalOpen.set(true);
  }

  applyWorkingDate(): void {
    this.companyContext.setWorkingDate(this.draftWorkingDate);
    this.workingDateModalOpen.set(false);
  }

  openPeriodModal(): void {
    this.draftPeriodFrom = this.companyContext.periodFrom();
    this.draftPeriodTo = this.companyContext.periodTo();
    this.periodError.set(null);
    this.periodModalOpen.set(true);
  }

  applyPeriodRange(): void {
    try {
      this.companyContext.setPeriodRange(this.draftPeriodFrom, this.draftPeriodTo);
      this.periodError.set(null);
      this.periodModalOpen.set(false);
    } catch (err: unknown) {
      this.periodError.set(err instanceof Error ? err.message : 'Invalid period range');
    }
  }

  openCompanySwitcherModal(): void {
    this.companySwitchBlockedWarning.set(false);
    this.companySwitcherOpen.set(true);
  }

  switchCompany(companyId: string): void {
    const ok = this.companyContext.selectActiveCompany(companyId);
    if (!ok) {
      this.companySwitchBlockedWarning.set(true);
      this.companySwitcherOpen.set(true);
    }
  }

  selectFromCompanyModal(companyId: string): void {
    const ok = this.companyContext.selectActiveCompany(companyId);
    if (!ok) {
      this.companySwitchBlockedWarning.set(true);
      return;
    }
    this.companySwitcherOpen.set(false);
  }

  navigateToCompanyHub(tab: 'create' | 'alter' | 'features'): void {
    this.companySwitcherOpen.set(false);
    void this.router.navigate(['/app/companies'], { queryParams: { mode: tab } });
  }

  private canJumpToGlobalVoucher(): boolean {
    return (
      !this.companyContext.hasUnsavedDraft() &&
      !this.navStack.hasOverlays() &&
      !this.goToOpen() &&
      !this.smartFind.isOpen() &&
      !this.workingDateModalOpen() &&
      !this.periodModalOpen() &&
      !this.companySwitcherOpen()
    );
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
      case 'company.features':
        this.navigateToCompanyHub('features');
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

  async signOut(): Promise<void> {
    await this.authStore.logout();
    void this.router.navigateByUrl('/login');
  }

  private handleGlobalEscape(): boolean {
    if (this.goToOpen()) {
      this.goToOpen.set(false);
      return true;
    }
    if (this.smartFind.isOpen()) {
      this.smartFind.close();
      return true;
    }
    if (this.workingDateModalOpen()) {
      this.workingDateModalOpen.set(false);
      return true;
    }
    if (this.periodModalOpen()) {
      this.periodModalOpen.set(false);
      return true;
    }
    if (this.companySwitcherOpen()) {
      this.companySwitcherOpen.set(false);
      return true;
    }
    if (this.navStack.hasOverlays()) {
      this.navStack.pop();
      return true;
    }
    return false;
  }
}
