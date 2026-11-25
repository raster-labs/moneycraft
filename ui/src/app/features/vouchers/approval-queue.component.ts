import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  OnInit,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthStore } from '../../core/auth/auth.store';
import { isEditableTextElement } from '../../core/keyboard/collision-guard';
import {
  ActiveModalContextHandle,
  ShortcutCommandRegistry,
  asciiPair,
} from '../../core/keyboard/shortcut-command.registry';
import { ExactDecimal } from '../../core/money/exact-decimal';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { SemanticIconComponent } from '../../shared/components/semantic-icon/semantic-icon.component';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { VoucherService } from './voucher.service';
import { VoucherSummaryDto } from './vouchers.models';

export type QueueStatusTab = 'ALL_UNPOSTED' | 'PENDING_APPROVAL' | 'DRAFT';

@Component({
  selector: 'mc-approval-queue',
  standalone: true,
  imports: [FormsModule, RouterLink, CodeBadgeComponent, IndianCurrencyPipe, SemanticIconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-queue-shell">
      <header class="mc-queue-header mc-card">
        <div>
          <div class="mc-queue-heading-row">
            <h1 class="mc-queue-title">Four-Eyes Approval Queue &amp; Draft Workbench</h1>
            <span class="mc-badge mc-badge-jade">Maker-Checker SoD</span>
          </div>
          <p class="mc-queue-subtitle">
            Review unposted vouchers, enforce <code>approved_by &lt;&gt; created_by</code>, and post
            approved entries to the immutable general ledger.
          </p>
        </div>

        <div class="mc-queue-toolbar">
          <div class="mc-queue-tabs" role="tablist" aria-label="Queue Status Filter">
            <button
              type="button"
              role="tab"
              class="mc-btn"
              [class.mc-btn-primary]="activeTab() === 'ALL_UNPOSTED'"
              [attr.aria-selected]="activeTab() === 'ALL_UNPOSTED'"
              (click)="setTab('ALL_UNPOSTED')"
            >
              All Unposted ({{ unpostedItems().length }})
            </button>
            <button
              type="button"
              role="tab"
              class="mc-btn"
              [class.mc-btn-primary]="activeTab() === 'PENDING_APPROVAL'"
              [attr.aria-selected]="activeTab() === 'PENDING_APPROVAL'"
              (click)="setTab('PENDING_APPROVAL')"
            >
              Pending Approval ({{ pendingCount() }})
            </button>
            <button
              type="button"
              role="tab"
              class="mc-btn"
              [class.mc-btn-primary]="activeTab() === 'DRAFT'"
              [attr.aria-selected]="activeTab() === 'DRAFT'"
              (click)="setTab('DRAFT')"
            >
              Drafts ({{ draftCount() }})
            </button>
          </div>

          <button type="button" class="mc-btn mc-btn-secondary" [disabled]="loading()" (click)="reloadQueue()">
            <mc-icon name="refresh" [size]="16" />
            <span>Refresh</span>
          </button>
          <a routerLink="/app/daybook" class="mc-btn mc-btn-secondary">Day Book</a>
        </div>
      </header>

      @if (errorBanner(); as err) {
        <div class="mc-queue-alert mc-queue-alert-error" role="alert">{{ err }}</div>
      }
      @if (statusBanner(); as msg) {
        <div class="mc-queue-alert mc-queue-alert-ok" role="status">{{ msg }}</div>
      }

      <section class="mc-card mc-queue-table-card" aria-label="Approval Queue Vouchers">
        @if (loading()) {
          <div class="mc-queue-loading">Loading unposted and pending-approval vouchers...</div>
        } @else {
          <div class="mc-queue-table-wrap">
            <table class="mc-queue-table" role="grid">
              <thead>
                <tr>
                  <th scope="col">Date</th>
                  <th scope="col">Vch No.</th>
                  <th scope="col">Vch Type</th>
                  <th scope="col">Particulars</th>
                  <th scope="col">Maker</th>
                  <th scope="col">Status</th>
                  <th scope="col" class="mc-col-amount">Amount</th>
                  <th scope="col" class="mc-col-actions">Actions</th>
                </tr>
              </thead>
              <tbody>
                @for (item of filteredItems(); track item.id; let idx = $index) {
                  <tr
                    class="mc-queue-row"
                    [class.mc-queue-row-selected]="idx === selectedIndex()"
                    [attr.aria-selected]="idx === selectedIndex()"
                    [attr.data-testid]="'approval-row-' + idx"
                    (click)="selectedIndex.set(idx)"
                    (dblclick)="inspectVoucher(item)"
                  >
                    <td class="mc-code">{{ item.voucherDate }}</td>
                    <td class="mc-code">{{ item.voucherNumber }}</td>
                    <td>{{ item.voucherTypeName }}</td>
                    <td>
                      <div class="mc-queue-particulars">{{ item.particulars }}</div>
                      @if (item.narration) {
                        <div class="mc-queue-narration">{{ item.narration }}</div>
                      }
                    </td>
                    <td>
                      @if (item.createdBy) {
                        <mc-code-badge [code]="item.createdBy" />
                      } @else {
                        <span>—</span>
                      }
                    </td>
                    <td>
                      @if (item.status === 'PENDING_APPROVAL') {
                        <span class="mc-badge mc-badge-pending">Pending Four-Eyes</span>
                      } @else {
                        <span class="mc-badge mc-badge-slate">Draft</span>
                      }
                    </td>
                    <td class="mc-amount mc-col-amount">
                      {{ item.debitAmount | mcIndianCurrency: companyContext.currencyScale() : true }}
                    </td>
                    <td class="mc-col-actions">
                      <div class="mc-row-action-btns">
                        <button
                          type="button"
                          class="mc-btn"
                          (click)="inspectVoucher(item); $event.stopPropagation()"
                        >
                          Inspect
                        </button>

                        @if (item.status === 'PENDING_APPROVAL') {
                          @if (isSelfMaker(item)) {
                            <span
                              class="mc-self-maker-note"
                              data-testid="self-maker-blocked"
                              title="Segregation of Duties: Maker cannot approve their own voucher"
                            >
                              Maker=You (Blocked)
                            </span>
                          } @else {
                            <button
                              type="button"
                              class="mc-btn mc-btn-primary"
                              data-testid="approve-vch-btn"
                              [disabled]="busyAction() || companyContext.quarantined()"
                              (click)="openApproveModal(item); $event.stopPropagation()"
                            >
                              <mc-icon name="approve-shield" [size]="16" />
                              <span>Approve (<span class="mc-key-ctrl">A</span>)</span>
                            </button>
                          }
                        } @else if (item.status === 'DRAFT') {
                          <button
                            type="button"
                            class="mc-btn mc-btn-primary"
                            data-testid="post-draft-btn"
                            [disabled]="busyAction() || companyContext.quarantined()"
                            (click)="postDraftVoucher(item); $event.stopPropagation()"
                          >
                            <mc-icon name="post-ledger" [size]="16" />
                            <span>Post</span>
                          </button>
                        }
                      </div>
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="8" class="mc-empty-records">No matching records</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }

        <footer class="mc-queue-footer">
          <span class="mc-queue-footer-hint">
            Up/Down = Select &bull; Enter = Inspect/Alter &bull; Ctrl+A = Approve Highlighted
          </span>
          <div class="mc-queue-total-box">
            <span class="mc-queue-total-lbl">Total Queue Value</span>
            <span class="mc-amount mc-queue-total-val" data-testid="approval-queue-total">
              {{ queueTotalWire() | mcIndianCurrency: companyContext.currencyScale() : true }}
            </span>
          </div>
        </footer>
      </section>

      @if (approveModalTarget(); as target) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Approve Voucher (Four-Eyes)"
        >
          <form
            class="mc-modal-dialog mc-approve-modal"
            (ngSubmit)="confirmApprove()"
            (keydown.escape)="closeApproveModal($event)"
          >
            <div class="mc-modal-header">
              <span>Approve &amp; Post Voucher (<span class="mc-key-ctrl">A</span>)</span>
              <button type="button" class="mc-btn" (click)="approveModalTarget.set(null)">
                Esc
              </button>
            </div>
            <div class="mc-modal-body mc-approve-body">
              <p class="mc-approve-summary">
                Approve <strong>{{ target.voucherTypeName }}</strong>
                <mc-code-badge [code]="target.voucherNumber" /> for
                <span class="mc-amount">
                  {{ target.debitAmount | mcIndianCurrency: companyContext.currencyScale() : true }}
                </span>
                ?
              </p>
              <div>
                <label class="mc-field-label" for="mc-approve-remarks">
                  Checker Remarks (Optional)
                </label>
                <input
                  #remarksInput
                  id="mc-approve-remarks"
                  type="text"
                  name="approvalRemarks"
                  class="mc-input"
                  [(ngModel)]="approvalRemarks"
                  placeholder="Verified supporting tax invoice and cost centre allocation"
                />
              </div>
            </div>
            <div class="mc-modal-footer">
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                data-testid="approve-return-btn"
                (click)="approveModalTarget.set(null)"
              >
                <mc-icon name="back" [size]="16" />
                <span>Return</span>
                @if (approveHint('editor.close', 'R/r'); as rHint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ rHint }}]</span>
                }
              </button>
              <button
                type="submit"
                class="mc-btn mc-btn-primary"
                data-testid="confirm-approve-btn"
                [disabled]="busyAction()"
              >
                <mc-icon name="approve-shield" [size]="16" />
                <span>{{ busyAction() ? 'Approving...' : 'Approve' }}</span>
                @if (approveHint('journal.approve', 'A/a'); as aHint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ aHint }}]</span>
                }
              </button>
            </div>
          </form>
        </div>
      }
    </div>
  `,
  styles: `
    .mc-queue-shell {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-queue-header {
      padding: var(--mc-space-3) var(--mc-space-4);
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-3);
    }

    .mc-queue-heading-row {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-queue-title {
      margin: 0;
      font-size: var(--mc-font-size-lg);
      font-weight: 700;
      color: var(--mc-color-slate-900);
    }

    .mc-queue-subtitle {
      margin: 2px 0 0;
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-queue-toolbar {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-2);
    }

    .mc-queue-tabs {
      display: flex;
      gap: 4px;
    }

    .mc-queue-alert {
      padding: var(--mc-space-2) var(--mc-space-3);
      border-radius: var(--mc-radius-xs);
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
    }

    .mc-queue-alert-error {
      background: #fef2f2;
      color: #991b1b;
      border: 1px solid #fecaca;
    }

    .mc-queue-alert-ok {
      background: var(--mc-color-teal-50);
      color: var(--mc-color-pressed-teal);
      border: 1px solid var(--mc-color-teal-100);
    }

    .mc-queue-table-card {
      overflow: hidden;
    }

    .mc-queue-loading,
    .mc-queue-empty {
      padding: var(--mc-space-5);
      text-align: center;
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-queue-table-wrap {
      overflow-x: auto;
      max-height: 60vh;
      overflow-y: auto;
    }

    .mc-queue-table {
      width: 100%;
      border-collapse: collapse;
      font-size: var(--mc-font-size-sm);
    }

    .mc-queue-table thead th {
      position: sticky;
      top: 0;
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      padding: 0.45rem 0.75rem;
      text-align: left;
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      text-transform: uppercase;
    }

    .mc-queue-table thead th.mc-col-amount {
      text-align: right;
    }

    .mc-queue-row {
      border-bottom: 1px solid var(--mc-color-slate-100);
      cursor: pointer;
      font-weight: 500;
    }

    .mc-queue-row td {
      padding: 0.45rem 0.75rem;
      vertical-align: middle;
    }

    .mc-queue-row-selected {
      background: var(--mc-color-teal-50);
    }

    .mc-queue-particulars {
      font-weight: 600;
      color: var(--mc-color-slate-900);
    }

    .mc-queue-narration {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
    }

    .mc-badge-pending {
      background: #e0f2fe;
      color: #075985;
      border: 1px solid #bae6fd;
    }

    .mc-col-amount {
      width: 150px;
      white-space: nowrap;
    }

    .mc-col-actions {
      width: 220px;
      white-space: nowrap;
    }

    .mc-row-action-btns {
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .mc-self-maker-note {
      font-size: var(--mc-font-size-xs);
      color: #b45309;
      font-weight: 600;
    }

    .mc-queue-footer {
      background: var(--mc-color-canvas);
      border-top: 2px solid var(--mc-color-slate-200);
      padding: 0.5rem 0.875rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-3);
    }

    .mc-queue-footer-hint {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-queue-total-box {
      display: flex;
      align-items: baseline;
      gap: var(--mc-space-2);
    }

    .mc-queue-total-lbl {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 700;
      text-transform: uppercase;
    }

    .mc-queue-total-val {
      font-size: var(--mc-font-size-md);
      font-weight: 700;
      color: var(--mc-color-slate-900);
    }

    .mc-approve-modal {
      width: min(460px, 94vw);
    }

    .mc-approve-body {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-approve-summary {
      margin: 0;
      font-weight: 600;
    }
  `,
})
export class ApprovalQueueComponent implements OnInit {
  readonly embeddedOverlay = input<boolean>(false);

  readonly companyContext = inject(CompanyContextStore);
  private readonly authStore = inject(AuthStore);
  private readonly voucherService = inject(VoucherService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly navStack = inject(NavigationStackStore);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  private readonly remarksInputRef = viewChild<ElementRef<HTMLInputElement>>('remarksInput');

  readonly activeTab = signal<QueueStatusTab>('ALL_UNPOSTED');
  readonly unpostedItems = signal<readonly VoucherSummaryDto[]>([]);
  readonly loading = signal<boolean>(false);
  readonly busyAction = signal<boolean>(false);
  readonly errorBanner = signal<string | null>(null);
  readonly statusBanner = signal<string | null>(null);
  readonly selectedIndex = signal<number>(0);

  readonly approveModalTarget = signal<VoucherSummaryDto | null>(null);
  approvalRemarks = '';

  private lastLoadedCompanyId: string | null = null;
  private approveModalHandle: ActiveModalContextHandle | null = null;

  approveHint(commandId: string, pairSpec: 'A/a' | 'R/r'): string | null {
    return this.shortcutRegistry.effectiveActionHint(
      'journal.approveConfirm',
      commandId,
      asciiPair(pairSpec),
    );
  }

  readonly pendingCount = computed(
    () => this.unpostedItems().filter((i) => i.status === 'PENDING_APPROVAL').length,
  );

  readonly draftCount = computed(
    () => this.unpostedItems().filter((i) => i.status === 'DRAFT').length,
  );

  readonly filteredItems = computed<readonly VoucherSummaryDto[]>(() => {
    const tab = this.activeTab();
    const list = this.unpostedItems();
    if (tab === 'PENDING_APPROVAL') {
      return list.filter((i) => i.status === 'PENDING_APPROVAL');
    }
    if (tab === 'DRAFT') {
      return list.filter((i) => i.status === 'DRAFT');
    }
    return list;
  });

  readonly selectedItem = computed<VoucherSummaryDto | null>(() => {
    const items = this.filteredItems();
    if (items.length === 0) return null;
    const idx = Math.min(Math.max(0, this.selectedIndex()), items.length - 1);
    return items[idx] ?? null;
  });

  readonly queueTotalWire = computed<string>(() => {
    const scale = this.companyContext.currencyScale();
    let sum = ExactDecimal.zero(scale);
    for (const item of this.filteredItems()) {
      const dec = ExactDecimal.tryParse(item.debitAmount, scale, 'HALF_UP');
      if (dec) {
        sum = sum.add(dec);
      }
    }
    return sum.toWireString(scale);
  });

  constructor() {
    effect(() => {
      const cid = this.companyContext.activeCompany()?.id ?? null;
      if (cid && cid !== this.lastLoadedCompanyId) {
        this.lastLoadedCompanyId = cid;
        void this.reloadQueue();
      }
    });

    effect(() => {
      const target = this.approveModalTarget();
      this.releaseApproveModal();
      if (target) {
        this.approveModalHandle = this.shortcutRegistry.openModalContext(
          [
            {
              contextId: 'journal.approveConfirm',
              locale: 'en-GB',
              commandId: 'journal.approve',
              label: 'Approve',
              letterPair: asciiPair('A/a'),
              enabledWhen: () => !this.busyAction() && !this.companyContext.quarantined(),
              permissionRef: 'voucher.approve',
              effect: 'Confirm FOUR_EYES approval on current saved version in open period.',
              normalActivation: () => this.confirmApprove(),
              iconSemanticId: 'mc.icon.approve-shield',
              stateTokenRole: 'action.primary',
              isMutation: true,
            },
            {
              contextId: 'journal.approveConfirm',
              locale: 'en-GB',
              commandId: 'editor.close',
              label: 'Return',
              letterPair: asciiPair('R/r'),
              permissionRef: 'authenticated',
              effect: 'Close approval confirmation without approving.',
              normalActivation: () => this.approveModalTarget.set(null),
              iconSemanticId: 'mc.icon.back',
              stateTokenRole: 'action.secondary',
            },
          ],
          'en-GB',
        );
      }
    });
  }

  private releaseApproveModal(): void {
    if (this.approveModalHandle) {
      this.approveModalHandle.close();
      this.approveModalHandle = null;
    }
  }

  ngOnInit(): void {
    const cid = this.companyContext.activeCompany()?.id ?? null;
    if (cid && cid !== this.lastLoadedCompanyId) {
      this.lastLoadedCompanyId = cid;
      void this.reloadQueue();
    }

    const disposeAccept = this.shortcutRegistry.bindHandler(
      'screen.accept',
      'SCREEN',
      () => {
        const current = this.selectedItem();
        if (current && current.status === 'PENDING_APPROVAL' && !this.isSelfMaker(current)) {
          this.openApproveModal(current);
        }
      },
      () => !this.approveModalTarget() && (!this.navStack.hasOverlays() || this.embeddedOverlay()),
    );

    this.destroyRef.onDestroy(() => {
      disposeAccept();
      this.releaseApproveModal();
    });
  }

  @HostListener('document:keydown', ['$event'])
  onDocumentKeyDown(event: KeyboardEvent): void {
    if (this.approveModalTarget()) return;
    if (this.navStack.hasOverlays() && !this.embeddedOverlay()) return;
    if (isEditableTextElement(event.target)) return;
    if (event.altKey || event.ctrlKey || event.metaKey) return;

    const rows = this.filteredItems();
    if (rows.length === 0) return;

    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.selectedIndex.set(Math.min(rows.length - 1, this.selectedIndex() + 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.selectedIndex.set(Math.max(0, this.selectedIndex() - 1));
    } else if (event.key === 'Enter') {
      const item = this.selectedItem();
      if (item) {
        event.preventDefault();
        this.inspectVoucher(item);
      }
    }
  }

  setTab(tab: QueueStatusTab): void {
    this.activeTab.set(tab);
    this.selectedIndex.set(0);
  }

  isSelfMaker(item: VoucherSummaryDto): boolean {
    const principal = this.authStore.principal();
    if (!principal || !item.createdBy) {
      return false;
    }
    return (
      item.createdBy === principal.principalId ||
      item.createdBy.toLowerCase() === principal.username.toLowerCase()
    );
  }

  inspectVoucher(item: VoucherSummaryDto): void {
    if (this.embeddedOverlay()) {
      this.navStack.clear();
    }
    void this.router.navigate(['/app/vouchers', item.id]);
  }

  async reloadQueue(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;

    this.loading.set(true);
    this.errorBanner.set(null);
    try {
      const [pendingPage, draftPage] = await Promise.all([
        this.voucherService.listVouchers(cid, {
          status: 'PENDING_APPROVAL',
          includeOptional: true,
          limit: 200,
        }),
        this.voucherService.listVouchers(cid, {
          status: 'DRAFT',
          includeOptional: true,
          limit: 200,
        }),
      ]);

      const mergedMap = new Map<string, VoucherSummaryDto>();
      for (const item of pendingPage.items) {
        mergedMap.set(item.id, item);
      }
      for (const item of draftPage.items) {
        mergedMap.set(item.id, item);
      }

      const merged = Array.from(mergedMap.values()).sort((a, b) =>
        a.voucherDate.localeCompare(b.voucherDate),
      );
      this.unpostedItems.set(merged);
      this.selectedIndex.set(0);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to load approval queue.');
    } finally {
      this.loading.set(false);
    }
  }

  openApproveModal(item: VoucherSummaryDto): void {
    if (this.isSelfMaker(item) || this.companyContext.quarantined()) {
      return;
    }
    this.approvalRemarks = '';
    this.approveModalTarget.set(item);
    queueMicrotask(() => {
      this.remarksInputRef()?.nativeElement.focus();
    });
  }

  closeApproveModal(event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    this.approveModalTarget.set(null);
  }

  async confirmApprove(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    const target = this.approveModalTarget();
    if (!cid || !target || this.busyAction()) return;

    this.busyAction.set(true);
    this.errorBanner.set(null);
    try {
      await this.voucherService.approveVoucher(
        cid,
        target.id,
        this.approvalRemarks.trim() || undefined,
        target.versionNo,
      );
      this.unpostedItems.set(this.unpostedItems().filter((i) => i.id !== target.id));
      this.approveModalTarget.set(null);
      this.statusBanner.set(
        `Approved & posted ${target.voucherTypeName} voucher #${target.voucherNumber}.`,
      );
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Voucher approval failed.');
    } finally {
      this.busyAction.set(false);
    }
  }

  async postDraftVoucher(item: VoucherSummaryDto): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid || this.busyAction()) return;

    this.busyAction.set(true);
    this.errorBanner.set(null);
    try {
      await this.voucherService.postVoucher(cid, item.id, item.versionNo);
      this.unpostedItems.set(this.unpostedItems().filter((i) => i.id !== item.id));
      this.statusBanner.set(`Posted draft ${item.voucherTypeName} voucher #${item.voucherNumber}.`);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Posting draft voucher failed.');
    } finally {
      this.busyAction.set(false);
    }
  }
}
