import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { ExactDecimal } from '../../core/money/exact-decimal';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { CompanyContextStore } from '../../state/company-context.store';
import { ImsActionType, ImsInvoiceDto, ImsMatchStatus } from './statutory.models';
import { StatutoryService } from './statutory.service';

@Component({
  selector: 'mc-ims-workbench',
  standalone: true,
  imports: [FormsModule, RouterLink, IndianCurrencyPipe, CodeBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ims-page" (keydown)="onWorkbenchKeydown($event)">
      <header class="mc-card ims-header">
        <div>
          <span class="return-pill">TallyPrime Release 7.2 Parity &bull; GSTN IMS</span>
          <h1>IMS (Invoice Management System) Reconciliation Workbench</h1>
          <p class="subtitle">
            Space = Select &bull; Alt+A = Accept &bull; Alt+R = Reject (Remarks Mandatory) &bull;
            Alt+P = Pending
          </p>
        </div>

        <div class="header-controls">
          <label class="inline-field">
            <span>Return Period</span>
            <input
              type="text"
              class="mc-input mc-code"
              data-testid="ims-period-input"
              [ngModel]="returnPeriod()"
              (ngModelChange)="returnPeriod.set($event)"
              placeholder="042025"
            />
          </label>
          <button type="button" class="mc-btn" (click)="reloadInvoices()">Refresh</button>
          <button
            type="button"
            class="mc-btn mc-btn-primary"
            data-testid="ims-open-import-btn"
            (click)="importModalOpen.set(true)"
          >
            Import GSTR-2B / IMS JSON
          </button>
        </div>
      </header>

      <!-- Live GSTR-3B Table 4(A)(5) Eligible ITC Impact Banner -->
      <section class="itc-impact-strip" data-testid="ims-gstr3b-impact-banner">
        <div class="impact-metrics">
          <div class="impact-item">
            <span class="impact-label">GSTR-3B Table 4(A)(5) Net Eligible ITC</span>
            <span class="mc-amount impact-val teal-val" data-testid="ims-net-eligible-itc">
              {{ liveItcSummary().netAcceptedItc | mcIndianCurrency }}
            </span>
          </div>
          <div class="impact-item">
            <span class="impact-label">Partial ITC Reductions</span>
            <span class="mc-amount impact-val" data-testid="ims-total-reductions">
              {{ liveItcSummary().totalReductions | mcIndianCurrency }}
            </span>
          </div>
          <div class="impact-item">
            <span class="impact-label">Deferred (Pending) ITC</span>
            <span class="mc-amount impact-val" data-testid="ims-pending-itc">
              {{ liveItcSummary().pendingItc | mcIndianCurrency }}
            </span>
          </div>
          <div class="impact-item">
            <span class="impact-label">Rejected ITC</span>
            <span class="mc-amount impact-val danger-val" data-testid="ims-rejected-itc">
              {{ liveItcSummary().rejectedItc | mcIndianCurrency }}
            </span>
          </div>
        </div>
        <a routerLink="/app/statutory/gstr3b" class="mc-btn">View GSTR-3B Impact</a>
      </section>

      <!-- Filter & Keyboard Action Bar -->
      <section class="mc-card toolbar-bar">
        <div class="filters">
          <label class="filter-ctl">
            <span>Match Status</span>
            <select
              class="mc-input"
              data-testid="ims-match-filter"
              [ngModel]="matchFilter()"
              (ngModelChange)="matchFilter.set($event)"
            >
              <option value="ALL">All Match States</option>
              <option value="EXACT_MATCH">Exact Match</option>
              <option value="PARTIAL_MISMATCH">Partial Mismatch</option>
              <option value="MISSING_IN_BOOKS">Missing in Books</option>
            </select>
          </label>

          <label class="filter-ctl">
            <span>IMS Status</span>
            <select
              class="mc-input"
              data-testid="ims-status-filter"
              [ngModel]="statusFilter()"
              (ngModelChange)="statusFilter.set($event)"
            >
              <option value="ALL">All IMS Actions</option>
              <option value="NO_ACTION">No Action</option>
              <option value="ACCEPT">Accepted</option>
              <option value="REJECT">Rejected</option>
              <option value="PENDING">Pending</option>
            </select>
          </label>
        </div>

        <div class="triage-buttons">
          <button
            type="button"
            class="mc-btn mc-btn-primary"
            data-testid="ims-accept-btn"
            [disabled]="!activeTargetInvoice()"
            (click)="quickAccept()"
          >
            Accept (Alt+A)
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="ims-partial-reduce-btn"
            [disabled]="!activeTargetInvoice()"
            (click)="openActionModal('ACCEPT', true)"
          >
            Accept w/ ITC Reduction
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="ims-pending-btn"
            [disabled]="!activeTargetInvoice()"
            (click)="quickPending()"
          >
            Pending (Alt+P)
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="ims-reject-btn"
            [disabled]="!activeTargetInvoice()"
            (click)="openActionModal('REJECT', false)"
          >
            Reject (Alt+R)
          </button>
        </div>
      </section>

      @if (loading()) {
        <div class="mc-card state-box">Loading supplier GSTR-2B / IMS inward invoices...</div>
      } @else {
        <section class="mc-card grid-card">
          <table class="mc-table" data-testid="ims-invoices-table">
            <thead>
              <tr>
                <th>Sel</th>
                <th>Supplier GSTIN &amp; Name</th>
                <th>Invoice No &amp; Date</th>
                <th>Match Status</th>
                <th class="num-col">Portal ITC</th>
                <th class="num-col">ITC Reduction</th>
                <th>IMS Action</th>
                <th>Remarks</th>
              </tr>
            </thead>
            <tbody>
              @for (inv of filteredInvoices(); track inv.id; let idx = $index) {
                <tr
                  tabindex="0"
                  class="ims-row"
                  [class.row-focused]="focusedIndex() === idx"
                  [class.row-checked]="isSelected(inv.id)"
                  [attr.data-testid]="'ims-row-' + inv.id"
                  (click)="focusRow(idx)"
                >
                  <td>
                    <input
                      type="checkbox"
                      [checked]="isSelected(inv.id)"
                      [attr.aria-label]="'Select invoice ' + inv.invoiceNumber"
                      (change)="toggleSelection(inv.id)"
                      (click)="$event.stopPropagation()"
                    />
                  </td>
                  <td>
                    <div><mc-code-badge [code]="inv.supplierGstin" /></div>
                    <div class="supplier-name">{{ inv.supplierName }}</div>
                  </td>
                  <td>
                    <div class="mc-code">{{ inv.invoiceNumber }}</div>
                    <div class="muted-sub">{{ inv.invoiceDate }}</div>
                  </td>
                  <td>
                    <span
                      class="match-pill"
                      [class.match-exact]="inv.matchStatus === 'EXACT_MATCH'"
                      [class.match-partial]="inv.matchStatus === 'PARTIAL_MISMATCH'"
                      [class.match-missing]="inv.matchStatus === 'MISSING_IN_BOOKS'"
                    >
                      {{ inv.matchStatus }}
                    </span>
                  </td>
                  <td class="mc-amount num-col">{{ inv.portalItcAmount | mcIndianCurrency }}</td>
                  <td class="mc-amount num-col">{{ inv.itcReductionAmount | mcIndianCurrency }}</td>
                  <td>
                    <span
                      class="status-pill"
                      [attr.data-testid]="'ims-status-badge-' + inv.id"
                      [class.st-accept]="inv.imsStatus === 'ACCEPT'"
                      [class.st-reject]="inv.imsStatus === 'REJECT'"
                      [class.st-pending]="inv.imsStatus === 'PENDING'"
                    >
                      {{ inv.imsStatus }}
                    </span>
                  </td>
                  <td class="remarks-col">{{ inv.remarks || '—' }}</td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="8" class="empty-cell">
                    No IMS supplier invoices match the active filters.
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </section>
      }

      <!-- Rel 7.2 Partial ITC Reduction & Mandatory Remarks Modal -->
      @if (actionModalOpen() && modalInvoice(); as target) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="IMS Action and Mandatory Remarks"
          data-testid="ims-action-modal"
          (keydown.escape)="closeActionModal()"
        >
          <div class="mc-panel modal-dialog">
            <header class="dialog-head">
              <h2>
                IMS Decision — {{ target.supplierName }} (<span class="mc-code">{{ target.invoiceNumber }}</span>)
              </h2>
              <button type="button" class="mc-btn" (click)="closeActionModal()">Esc Cancel</button>
            </header>

            @if (actionModalError(); as err) {
              <div class="modal-error" data-testid="ims-modal-error" role="alert">{{ err }}</div>
            }

            <form class="dialog-form" (ngSubmit)="submitModalAction()">
              <div class="two-col">
                <div class="field-group">
                  <label for="ims-modal-action">IMS Action</label>
                  <select
                    id="ims-modal-action"
                    name="modalAction"
                    class="mc-input"
                    data-testid="ims-modal-action-select"
                    [ngModel]="modalAction()"
                    (ngModelChange)="modalAction.set($event)"
                  >
                    <option value="ACCEPT">ACCEPT</option>
                    <option value="REJECT">REJECT</option>
                    <option value="PENDING">PENDING</option>
                  </select>
                </div>

                <div class="field-group">
                  <label>Portal Eligible ITC</label>
                  <div class="readonly-amount mc-amount">
                    {{ target.portalItcAmount | mcIndianCurrency }}
                  </div>
                </div>
              </div>

              @if (modalAction() === 'ACCEPT') {
                <div class="two-col">
                  <div class="field-group">
                    <label for="ims-reduction-input">
                      ITC Reduction Amount (&#x20B9;) — Partial Credit Reversal
                    </label>
                    <input
                      id="ims-reduction-input"
                      name="modalItcReduction"
                      type="text"
                      class="mc-input mc-amount"
                      data-testid="ims-modal-reduction-input"
                      [ngModel]="modalItcReduction()"
                      (ngModelChange)="modalItcReduction.set($event)"
                      placeholder="0.00"
                    />
                  </div>

                  <div class="field-group">
                    <label for="ims-eff-period">Effective Filing Period</label>
                    <input
                      id="ims-eff-period"
                      name="modalEffectivePeriod"
                      type="text"
                      class="mc-input mc-code"
                      [ngModel]="modalEffectivePeriod()"
                      (ngModelChange)="modalEffectivePeriod.set($event)"
                      placeholder="042025"
                    />
                  </div>
                </div>
              }

              <div class="field-group">
                <label for="ims-remarks-input">
                  Statutory Remarks
                  @if (remarksRequired()) {
                    <span class="req-tag">(Mandatory for Reject / Partial ITC Reduction)</span>
                  }
                </label>
                <textarea
                  id="ims-remarks-input"
                  name="modalRemarks"
                  rows="3"
                  class="mc-input"
                  data-testid="ims-modal-remarks-input"
                  [ngModel]="modalRemarks()"
                  (ngModelChange)="modalRemarks.set($event)"
                  placeholder="State reason for rejection or partial Rule 42/Sec 17(5) ITC reduction..."
                ></textarea>
              </div>

              <div class="dialog-actions">
                <button type="button" class="mc-btn" (click)="closeActionModal()">Cancel</button>
                <button
                  type="submit"
                  class="mc-btn mc-btn-primary"
                  data-testid="ims-modal-submit-btn"
                >
                  Confirm IMS Action (Ctrl+A)
                </button>
              </div>
            </form>
          </div>
        </div>
      }

      <!-- Import GSTR-2B / IMS JSON Modal -->
      @if (importModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Import Supplier IMS JSON"
          data-testid="ims-import-modal"
          (keydown.escape)="importModalOpen.set(false)"
        >
          <div class="mc-panel modal-dialog">
            <header class="dialog-head">
              <h2>Import GSTN GSTR-2B / IMS Supplier Payload (JSON)</h2>
              <button type="button" class="mc-btn" (click)="importModalOpen.set(false)">
                Esc Close
              </button>
            </header>
            @if (importError(); as iErr) {
              <div class="modal-error" role="alert">{{ iErr }}</div>
            }
            <div class="dialog-form">
              <div class="field-group">
                <label for="ims-json-paste">Paste IMS / GSTR-2B JSON Payload</label>
                <textarea
                  id="ims-json-paste"
                  rows="6"
                  class="mc-input mc-code"
                  data-testid="ims-import-json-textarea"
                  [ngModel]="rawImportJson()"
                  (ngModelChange)="rawImportJson.set($event)"
                  placeholder='{"invoices":[{"supplierGstin":"29AABCT1332L1ZT","invoiceNumber":"INV-901","invoiceDate":"2025-04-12","taxableValue":"100000.00","igstAmount":"18000.00"}]}'
                ></textarea>
              </div>
              <div class="dialog-actions">
                <button
                  type="button"
                  class="mc-btn mc-btn-primary"
                  data-testid="ims-import-submit-btn"
                  (click)="submitImportJson()"
                >
                  Import &amp; Match Invoices
                </button>
              </div>
            </div>
          </div>
        </div>
      }
    </div>
  `,
  styles: `
    .ims-page {
      display: flex;
      flex-direction: column;
      gap: 0.8rem;
    }

    .ims-header {
      padding: 0.85rem 1rem;
      background: #142f42;
      color: #ffffff;
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 0.75rem;
    }

    .ims-header h1 {
      margin: 0.2rem 0;
      font-size: 1.03rem;
      font-weight: 700;
      color: #ffffff;
    }

    .return-pill {
      font-size: 0.72rem;
      font-weight: 700;
      color: #4ce1bd;
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }

    .subtitle {
      margin: 0;
      font-size: 0.76rem;
      color: #cfe2eb;
    }

    .header-controls {
      display: flex;
      align-items: end;
      gap: 0.5rem;
    }

    .inline-field {
      display: flex;
      flex-direction: column;
      gap: 0.18rem;
      font-size: 0.72rem;
      font-weight: 600;
      color: #d3e5ee;
    }

    .itc-impact-strip {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 0.75rem;
      padding: 0.7rem 1rem;
      background: #eaf7f5;
      border: 1px solid #9bd8ce;
      border-radius: 6px;
    }

    .impact-metrics {
      display: flex;
      flex-wrap: wrap;
      gap: 1.4rem;
    }

    .impact-item {
      display: flex;
      flex-direction: column;
      gap: 0.15rem;
    }

    .impact-label {
      font-size: 0.72rem;
      font-weight: 600;
      color: #3f5866;
    }

    .impact-val {
      font-size: 0.98rem;
      font-weight: 700;
      color: #142f42;
    }

    .teal-val {
      color: #006d68;
    }

    .danger-val {
      color: #9e1c1c;
    }

    .toolbar-bar {
      padding: 0.65rem 0.95rem;
      display: flex;
      align-items: end;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 0.75rem;
    }

    .filters,
    .triage-buttons {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: end;
    }

    .filter-ctl {
      display: flex;
      flex-direction: column;
      gap: 0.2rem;
      font-size: 0.74rem;
      font-weight: 600;
      color: #142f42;
    }

    .grid-card {
      overflow: hidden;
    }

    .ims-row {
      cursor: pointer;
    }

    .row-focused {
      background: #f0faf8;
      box-shadow: inset 3px 0 0 #008d87;
    }

    .row-checked {
      background: #e3f5f1;
    }

    .supplier-name {
      font-size: 0.8rem;
      font-weight: 600;
      color: #142f42;
    }

    .muted-sub {
      font-size: 0.73rem;
      color: #5b7280;
    }

    .match-pill,
    .status-pill {
      display: inline-block;
      padding: 0.1rem 0.45rem;
      border-radius: 999px;
      font-size: 0.7rem;
      font-weight: 700;
      background: #e5edec;
      color: #142f42;
    }

    .match-exact,
    .st-accept {
      background: #dff6ef;
      color: #006d68;
    }

    .match-partial,
    .st-pending {
      background: #fff3d6;
      color: #8a5300;
    }

    .match-missing,
    .st-reject {
      background: #fde2e2;
      color: #9e1c1c;
    }

    .num-col {
      text-align: right;
    }

    .remarks-col {
      max-width: 210px;
      font-size: 0.78rem;
      color: #3f5866;
    }

    .empty-cell {
      text-align: center;
      padding: 1.25rem;
      color: #5b7280;
    }

    .state-box {
      padding: 1.5rem;
      font-weight: 600;
      color: #142f42;
    }

    .modal-dialog {
      width: min(640px, 94vw);
      background: #ffffff;
      overflow: hidden;
    }

    .dialog-head {
      padding: 0.75rem 1rem;
      background: #142f42;
      color: #ffffff;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
    }

    .dialog-head h2 {
      margin: 0;
      font-size: 0.92rem;
      font-weight: 700;
      color: #ffffff;
    }

    .modal-error {
      margin: 0.75rem 1rem 0;
      padding: 0.55rem 0.75rem;
      border-radius: 6px;
      background: #fdecec;
      border: 1px solid #d64545;
      color: #8f1d1d;
      font-size: 0.82rem;
      font-weight: 600;
    }

    .dialog-form {
      padding: 0.9rem 1rem 1.1rem;
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }

    .two-col {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.75rem;
    }

    .field-group {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }

    .field-group label {
      font-size: 0.76rem;
      font-weight: 600;
      color: #142f42;
    }

    .req-tag {
      color: #9e1c1c;
      font-weight: 700;
    }

    .readonly-amount {
      padding: 0.45rem 0.65rem;
      background: #f6faf9;
      border: 1px solid #d4e2e0;
      border-radius: 4px;
      font-weight: 700;
      color: #006d68;
    }

    .dialog-actions {
      display: flex;
      justify-content: flex-end;
      gap: 0.5rem;
      padding-top: 0.3rem;
    }
  `,
})
export class ImsWorkbenchComponent implements OnInit {
  private readonly statutoryService = inject(StatutoryService);
  private readonly companyContext = inject(CompanyContextStore);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal<boolean>(false);
  readonly invoices = signal<readonly ImsInvoiceDto[]>([]);
  readonly returnPeriod = signal<string>('042025');

  readonly matchFilter = signal<'ALL' | ImsMatchStatus>('ALL');
  readonly statusFilter = signal<'ALL' | ImsActionType>('ALL');
  readonly focusedIndex = signal<number>(0);
  readonly selectedIds = signal<ReadonlySet<string>>(new Set());

  readonly actionModalOpen = signal<boolean>(false);
  readonly modalInvoice = signal<ImsInvoiceDto | null>(null);
  readonly modalAction = signal<ImsActionType>('REJECT');
  readonly modalItcReduction = signal<string>('0.00');
  readonly modalRemarks = signal<string>('');
  readonly modalEffectivePeriod = signal<string>('042025');
  readonly actionModalError = signal<string | null>(null);

  readonly importModalOpen = signal<boolean>(false);
  readonly rawImportJson = signal<string>('');
  readonly importError = signal<string | null>(null);

  readonly filteredInvoices = computed(() => {
    const mFilter = this.matchFilter();
    const sFilter = this.statusFilter();
    return this.invoices().filter((inv) => {
      if (mFilter !== 'ALL' && inv.matchStatus !== mFilter) {
        return false;
      }
      if (sFilter !== 'ALL' && inv.imsStatus !== sFilter) {
        return false;
      }
      return true;
    });
  });

  readonly activeTargetInvoice = computed<ImsInvoiceDto | null>(() => {
    const list = this.filteredInvoices();
    if (list.length === 0) {
      return null;
    }
    const idx = Math.min(Math.max(0, this.focusedIndex()), list.length - 1);
    return list[idx] ?? null;
  });

  readonly remarksRequired = computed<boolean>(() => {
    if (this.modalAction() === 'REJECT') {
      return true;
    }
    const red = ExactDecimal.tryParse(this.modalItcReduction() || '0', 2, 'HALF_UP');
    return red !== null && red.isPositive();
  });

  readonly liveItcSummary = computed(() => {
    let netAccepted = ExactDecimal.zero(2);
    let totalReductions = ExactDecimal.zero(2);
    let pendingItc = ExactDecimal.zero(2);
    let rejectedItc = ExactDecimal.zero(2);

    for (const inv of this.invoices()) {
      const portal =
        ExactDecimal.tryParse(inv.portalItcAmount, 2, 'HALF_UP') ?? ExactDecimal.zero(2);
      const reduction =
        ExactDecimal.tryParse(inv.itcReductionAmount || '0', 2, 'HALF_UP') ?? ExactDecimal.zero(2);

      if (inv.imsStatus === 'ACCEPT') {
        const net = portal.sub(reduction, 2);
        netAccepted = netAccepted.add(net.isNegative() ? ExactDecimal.zero(2) : net, 2);
        totalReductions = totalReductions.add(reduction, 2);
      } else if (inv.imsStatus === 'PENDING') {
        pendingItc = pendingItc.add(portal, 2);
      } else if (inv.imsStatus === 'REJECT') {
        rejectedItc = rejectedItc.add(portal, 2);
      }
    }

    return {
      netAcceptedItc: netAccepted.toWireString(2),
      totalReductions: totalReductions.toWireString(2),
      pendingItc: pendingItc.toWireString(2),
      rejectedItc: rejectedItc.toWireString(2),
    };
  });

  constructor() {
    // Alt+A (`register.addVoucher`), Alt+R (`register.removeLine`), Alt+P (`menu.print` overridden in SCREEN scope)
    const unbindAltA = this.shortcutRegistry.bindHandler(
      'register.addVoucher',
      'SCREEN',
      () => {
        void this.quickAccept();
      },
      () => !this.actionModalOpen() && !this.importModalOpen(),
    );
    const unbindAltR = this.shortcutRegistry.bindHandler(
      'register.removeLine',
      'SCREEN',
      () => {
        this.openActionModal('REJECT', false);
      },
      () => !this.actionModalOpen() && !this.importModalOpen(),
    );
    const unbindAltP = this.shortcutRegistry.bindHandler(
      'menu.print',
      'SCREEN',
      () => {
        void this.quickPending();
      },
      () => !this.actionModalOpen() && !this.importModalOpen(),
    );
    const unbindModalAccept = this.shortcutRegistry.bindHandler(
      'screen.accept',
      'MODAL',
      () => {
        void this.submitModalAction();
      },
      () => this.actionModalOpen(),
    );

    this.destroyRef.onDestroy(() => {
      unbindAltA();
      unbindAltR();
      unbindAltP();
      unbindModalAccept();
    });
  }

  ngOnInit(): void {
    const pFrom = this.companyContext.periodFrom();
    if (/^\d{4}-\d{2}/.test(pFrom)) {
      this.returnPeriod.set(`${pFrom.slice(5, 7)}${pFrom.slice(0, 4)}`);
    }
    void this.reloadInvoices();
  }

  async reloadInvoices(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) {
      return;
    }
    this.loading.set(true);
    try {
      const list = await this.statutoryService.listImsInvoices(companyId, {
        returnPeriod: this.returnPeriod(),
      });
      this.invoices.set(list);
      this.focusedIndex.set(0);
    } catch {
      this.invoices.set([]);
    } finally {
      this.loading.set(false);
    }
  }

  focusRow(index: number): void {
    this.focusedIndex.set(index);
  }

  isSelected(id: string): boolean {
    return this.selectedIds().has(id);
  }

  toggleSelection(id: string): void {
    const next = new Set(this.selectedIds());
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }
    this.selectedIds.set(next);
  }

  onWorkbenchKeydown(event: KeyboardEvent): void {
    if (this.actionModalOpen() || this.importModalOpen()) {
      return;
    }
    const targetTag = (event.target as HTMLElement | null)?.tagName;
    const inInput = targetTag === 'INPUT' || targetTag === 'TEXTAREA' || targetTag === 'SELECT';
    if (inInput) {
      return;
    }

    const list = this.filteredInvoices();
    if (event.key === 'ArrowDown' && list.length > 0) {
      event.preventDefault();
      this.focusedIndex.set(Math.min(list.length - 1, this.focusedIndex() + 1));
    } else if (event.key === 'ArrowUp' && list.length > 0) {
      event.preventDefault();
      this.focusedIndex.set(Math.max(0, this.focusedIndex() - 1));
    } else if (event.key === ' ') {
      const curr = this.activeTargetInvoice();
      if (curr) {
        event.preventDefault();
        this.toggleSelection(curr.id);
      }
    }
  }

  private targetBatchForQuickAction(): readonly ImsInvoiceDto[] {
    const selected = this.selectedIds();
    if (selected.size > 0) {
      return this.filteredInvoices().filter((i) => selected.has(i.id));
    }
    const active = this.activeTargetInvoice();
    return active ? [active] : [];
  }

  async quickAccept(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    const targets = this.targetBatchForQuickAction();
    if (!companyId || targets.length === 0) {
      return;
    }
    for (const inv of targets) {
      const updated = await this.statutoryService.applyImsAction(companyId, {
        imsInvoiceId: inv.id,
        action: 'ACCEPT',
        itcReductionAmount: '0.00',
        effectiveFilingPeriod: this.returnPeriod(),
      });
      this.replaceInvoice(updated);
    }
  }

  async quickPending(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    const targets = this.targetBatchForQuickAction();
    if (!companyId || targets.length === 0) {
      return;
    }
    for (const inv of targets) {
      const updated = await this.statutoryService.applyImsAction(companyId, {
        imsInvoiceId: inv.id,
        action: 'PENDING',
        itcReductionAmount: '0.00',
        remarks: inv.remarks ?? 'Deferred to subsequent tax period',
      });
      this.replaceInvoice(updated);
    }
  }

  openActionModal(action: ImsActionType, focusReduction: boolean): void {
    const target = this.activeTargetInvoice();
    if (!target) {
      return;
    }
    this.modalInvoice.set(target);
    this.modalAction.set(action);
    this.modalItcReduction.set(
      focusReduction && target.itcReductionAmount === '0.00' ? '' : target.itcReductionAmount,
    );
    this.modalRemarks.set(target.remarks ?? '');
    this.modalEffectivePeriod.set(target.effectiveFilingPeriod ?? this.returnPeriod());
    this.actionModalError.set(null);
    this.actionModalOpen.set(true);
  }

  closeActionModal(): void {
    this.actionModalOpen.set(false);
    this.actionModalError.set(null);
  }

  async submitModalAction(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    const target = this.modalInvoice();
    if (!companyId || !target) {
      return;
    }

    const action = this.modalAction();
    const rawReduction = (this.modalItcReduction() || '0.00').trim() || '0.00';
    const reductionDec = ExactDecimal.tryParse(rawReduction, 2, 'UNNECESSARY');
    if (!reductionDec || reductionDec.isNegative()) {
      this.actionModalError.set('Enter a valid non-negative ITC reduction amount (up to 2 decimals).');
      return;
    }

    const portalDec =
      ExactDecimal.tryParse(target.portalItcAmount, 2, 'HALF_UP') ?? ExactDecimal.zero(2);
    if (reductionDec.compare(portalDec) > 0) {
      this.actionModalError.set('ITC reduction amount cannot exceed Portal Eligible ITC.');
      return;
    }

    const remarks = this.modalRemarks().trim();
    if ((action === 'REJECT' || reductionDec.isPositive()) && remarks.length === 0) {
      this.actionModalError.set(
        'Remarks are mandatory under TallyPrime Rel 7.2 IMS rules when rejecting an invoice or reducing eligible ITC.',
      );
      return;
    }

    this.actionModalError.set(null);
    const updated = await this.statutoryService.applyImsAction(companyId, {
      imsInvoiceId: target.id,
      action,
      itcReductionAmount: action === 'ACCEPT' ? reductionDec.toWireString(2) : '0.00',
      remarks: remarks || null,
      effectiveFilingPeriod: this.modalEffectivePeriod().trim() || this.returnPeriod(),
    });
    this.replaceInvoice(updated);
    this.actionModalOpen.set(false);
  }

  async submitImportJson(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) {
      return;
    }
    try {
      const parsed: unknown = JSON.parse(this.rawImportJson());
      const payload =
        parsed && typeof parsed === 'object' && !Array.isArray(parsed)
          ? { returnPeriod: this.returnPeriod(), ...(parsed as Record<string, unknown>) }
          : { returnPeriod: this.returnPeriod(), invoices: Array.isArray(parsed) ? parsed : [] };
      const imported = await this.statutoryService.importImsJson(companyId, payload);
      this.invoices.set(imported);
      this.importError.set(null);
      this.importModalOpen.set(false);
    } catch {
      this.importError.set('Invalid JSON payload. Paste a valid GSTN GSTR-2B / IMS JSON document.');
    }
  }

  private replaceInvoice(updated: ImsInvoiceDto): void {
    this.invoices.update((list) => list.map((item) => (item.id === updated.id ? updated : item)));
  }
}
