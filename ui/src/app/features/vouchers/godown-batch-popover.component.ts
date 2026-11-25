import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ActiveModalContextHandle,
  ShortcutCommandRegistry,
  asciiPair,
} from '../../core/keyboard/shortcut-command.registry';
import { ExactDecimal } from '../../core/money/exact-decimal';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { SemanticIconComponent } from '../../shared/components/semantic-icon/semantic-icon.component';
import {
  TypeaheadOption,
  TypeaheadSelectComponent,
} from '../../shared/components/typeahead-select/typeahead-select.component';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';
import { StockBatchDto } from '../inventory/inventory.models';
import { InventoryService } from '../inventory/inventory.service';
import { InventoryStore } from '../inventory/inventory.store';
import { SecondaryMasterModalService } from '../masters/secondary-master-modal.service';
import { VoucherBatchAllocationDto } from './vouchers.models';

export interface EditableBatchAllocationRow {
  readonly rowId: string;
  godownId: string | null;
  godownName: string;
  batchName: string;
  actualQty: string;
  billedQty: string;
  rate: string;
  amount: string;
  mfgDate: string;
  expiryDate: string;
}

@Component({
  selector: 'mc-godown-batch-popover',
  standalone: true,
  imports: [
    FormsModule,
    IndianCurrencyPipe,
    CodeBadgeComponent,
    SemanticIconComponent,
    TypeaheadSelectComponent,
    GridCellNavDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (isOpen()) {
      <div
        class="mc-modal-backdrop mc-submodal-backdrop"
        role="dialog"
        aria-modal="true"
        data-testid="godown-batch-modal"
        [attr.aria-label]="'Item Allocations for ' + stockItemName()"
        (keydown)="onDialogKeyDown($event)"
      >
        <div class="mc-modal-dialog mc-gb-dialog" data-mc-nav-container>
          <div class="mc-modal-header">
            <div class="mc-gb-head-left">
              <span>
                Item Allocations for: <strong>{{ stockItemName() }}</strong>
              </span>
              <span class="mc-badge mc-badge-jade">
                Target: <span class="mc-amount">{{ targetBilledQty() }}</span> {{ uomSymbol() }}
                ({{ movementDirection() }})
              </span>
            </div>
            <button
              type="button"
              class="mc-submodal-esc-btn"
              data-testid="godown-batch-esc-btn"
              (click)="cancelled.emit()"
            >
              Esc
            </button>
          </div>

          <div class="mc-modal-body mc-gb-body">
            @if (validationError(); as err) {
              <div
                class="mc-field-error mc-gb-err"
                role="alert"
                data-testid="godown-batch-error"
              >
                {{ err }}
              </div>
            }

            <!-- Active Batches Picker on Outward Movement -->
            @if (movementDirection() === 'OUTWARD' && maintainInBatches() && availableBatches().length > 0) {
              <div class="mc-gb-batches-panel" aria-label="List of Active Batches">
                <div class="mc-gb-batches-title">
                  Active Batches as of <span class="mc-code">{{ voucherDate() || 'Today' }}</span>
                  (Click to Pick — Expired Batches Blocked)
                </div>
                <div class="mc-gb-batch-chips">
                  @for (b of availableBatches(); track b.batchName + '_' + (b.godownId ?? '')) {
                    <button
                      type="button"
                      class="mc-gb-batch-chip"
                      [class.mc-gb-batch-expired]="isBatchExpired(b.expiryDate ?? null, b.isExpired)"
                      [attr.data-testid]="'batch-chip-' + b.batchName"
                      (click)="pickAvailableBatch(0, b)"
                    >
                      <span class="mc-code">{{ b.batchName }}</span>
                      <span class="mc-amount">{{ b.closingQty }} {{ uomSymbol() }}</span>
                      @if (b.godownName) {
                        <span class="mc-gb-chip-sub">{{ b.godownName }}</span>
                      }
                      @if (b.expiryDate) {
                        <span class="mc-gb-chip-sub mc-code">Exp: {{ b.expiryDate }}</span>
                      }
                      @if (isBatchExpired(b.expiryDate ?? null, b.isExpired)) {
                        <mc-code-badge code="EXPIRED" />
                      }
                    </button>
                  }
                </div>
              </div>
            }

            <div class="mc-gb-table-wrap">
              <table class="mc-dense-table" aria-label="Godown and Batch Allocation Rows">
                <thead>
                  <tr>
                    <th>Godown (<span class="mc-key-alt">C</span> = Create)</th>
                    @if (maintainInBatches()) {
                      <th>Batch / Lot</th>
                    }
                    @if (maintainInBatches() && trackDateOfMfg()) {
                      <th>Mfg. Date</th>
                    }
                    @if (maintainInBatches() && useExpiryDates()) {
                      <th>Expiry Date</th>
                    }
                    <th class="mc-gb-num">Actual Qty</th>
                    <th class="mc-gb-num">Billed Qty</th>
                    <th class="mc-gb-num">Rate</th>
                    <th class="mc-gb-num">Amount</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of rows(); track row.rowId; let idx = $index) {
                    <tr>
                      <td>
                        <mc-typeahead-select
                          [inputId]="'gb-godown-' + idx"
                          listTitle="List of Godowns"
                          [options]="godownOptions()"
                          [selectedId]="row.godownId"
                          [allowInlineCreate]="true"
                          (optionSelected)="onGodownSelected(idx, $event)"
                          (createRequested)="openInlineGodownCreate(idx, $event)"
                        />
                      </td>

                      @if (maintainInBatches()) {
                        <td>
                          <input
                            type="text"
                            class="mc-input mc-code"
                            [attr.data-testid]="'gb-batch-input-' + idx"
                            [name]="'gbBatch' + row.rowId"
                            mcGridCellNav
                            [ngModel]="row.batchName"
                            (ngModelChange)="onBatchNameChange(idx, $event)"
                            placeholder="BATCH-001"
                          />
                        </td>
                      }

                      @if (maintainInBatches() && trackDateOfMfg()) {
                        <td>
                          <input
                            type="date"
                            class="mc-input mc-code"
                            [name]="'gbMfg' + row.rowId"
                            mcGridCellNav
                            [ngModel]="row.mfgDate"
                            (ngModelChange)="onMfgDateChange(idx, $event)"
                          />
                        </td>
                      }

                      @if (maintainInBatches() && useExpiryDates()) {
                        <td>
                          <input
                            type="date"
                            class="mc-input mc-code"
                            [attr.data-testid]="'gb-exp-input-' + idx"
                            [name]="'gbExp' + row.rowId"
                            mcGridCellNav
                            [ngModel]="row.expiryDate"
                            (ngModelChange)="onExpiryDateChange(idx, $event)"
                          />
                        </td>
                      }

                      <td>
                        <input
                          type="text"
                          class="mc-input mc-amount"
                          [attr.data-testid]="'gb-actual-qty-' + idx"
                          [name]="'gbActual' + row.rowId"
                          mcGridCellNav
                          [ngModel]="row.actualQty"
                          (ngModelChange)="onActualQtyChange(idx, $event)"
                        />
                      </td>

                      <td>
                        <input
                          type="text"
                          class="mc-input mc-amount"
                          [attr.data-testid]="'gb-billed-qty-' + idx"
                          [name]="'gbBilled' + row.rowId"
                          mcGridCellNav
                          [ngModel]="row.billedQty"
                          (ngModelChange)="onBilledQtyChange(idx, $event)"
                          (keydown.enter)="onRowBilledEnter(idx, $event)"
                        />
                      </td>

                      <td>
                        <input
                          type="text"
                          class="mc-input mc-amount"
                          [attr.data-testid]="'gb-rate-' + idx"
                          [name]="'gbRate' + row.rowId"
                          mcGridCellNav
                          [ngModel]="row.rate"
                          (ngModelChange)="onRateChange(idx, $event)"
                        />
                      </td>

                      <td class="mc-gb-num mc-amount">
                        {{ row.amount | mcIndianCurrency: currencyScale() : true }}
                      </td>

                      <td>
                        @if (rows().length > 1) {
                          <button
                            type="button"
                            class="mc-btn"
                            (click)="removeRow(idx)"
                          >
                            &times;
                          </button>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>

            <div class="mc-gb-summary">
              <button
                type="button"
                class="mc-btn"
                data-testid="gb-add-row-btn"
                (click)="appendSplitRow()"
              >
                + Split Godown / Batch Row
              </button>

              <div class="mc-gb-totals">
                <span>
                  Allocated:
                  <strong class="mc-amount">{{ summary().allocatedBilledWire }}</strong> /
                  <strong class="mc-amount">{{ targetBilledQty() }}</strong> {{ uomSymbol() }}
                </span>
                <span>
                  Total Value:
                  <strong class="mc-amount">
                    {{ summary().totalAmountWire | mcIndianCurrency: currencyScale() : true }}
                  </strong>
                </span>
              </div>
            </div>
          </div>

          <div class="mc-modal-footer mc-gb-footer">
            <span class="mc-gb-hint">
              Enter = Next Cell &bull; Ctrl+A = Accept Allocation
            </span>
            <div class="mc-gb-actions">
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                (click)="cancelled.emit()"
              >
                <mc-icon name="close" [size]="16" />
                <span>Cancel</span>
                @if (actionHint('editor.close', 'C/c'); as cHint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ cHint }}]</span>
                }
              </button>
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="gb-commit-btn"
                (click)="commitAllocations()"
              >
                <mc-icon name="save-document" [size]="16" />
                <span>Save (<span class="mc-key-ctrl">A</span>)</span>
                @if (actionHint('ledger.save', 'S/s'); as sHint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ sHint }}]</span>
                }
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `,
  styles: `
    .mc-gb-dialog {
      width: min(940px, 95vw);
      max-height: 88vh;
      display: flex;
      flex-direction: column;
    }

    .mc-gb-head-left {
      display: flex;
      align-items: center;
      gap: var(--mc-space-3);
      flex-wrap: wrap;
    }

    .mc-gb-body {
      padding: var(--mc-space-4);
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-gb-err {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: #fef2f2;
      border: 1px solid #fecaca;
      border-radius: var(--mc-radius-xs);
    }

    .mc-gb-batches-panel {
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-gb-batches-title {
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      color: var(--mc-color-slate-900);
    }

    .mc-gb-batch-chips {
      display: flex;
      flex-wrap: wrap;
      gap: var(--mc-space-2);
    }

    .mc-gb-batch-chip {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 0.3rem 0.6rem;
      background: var(--mc-color-surface);
      border: 1px solid var(--mc-color-slate-300);
      border-radius: var(--mc-radius-xs);
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
      cursor: pointer;
    }

    .mc-gb-batch-expired {
      background: #fef2f2;
      border-color: #f87171;
      color: #991b1b;
    }

    .mc-gb-chip-sub {
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-gb-table-wrap {
      overflow: visible;
    }

    .mc-gb-num {
      text-align: right;
    }

    .mc-gb-summary {
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-3);
      padding-top: var(--mc-space-2);
      border-top: var(--mc-border-subtle);
    }

    .mc-gb-totals {
      display: flex;
      gap: var(--mc-space-4);
      font-size: var(--mc-font-size-sm);
      font-weight: 600;
    }

    .mc-gb-footer {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .mc-gb-hint {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-gb-actions {
      display: flex;
      gap: var(--mc-space-2);
    }
  `,
})
export class GodownBatchPopoverComponent {
  readonly isOpen = input<boolean>(false);
  readonly companyId = input<string | null>(null);
  readonly stockItemId = input<string>('');
  readonly stockItemName = input<string>('');
  readonly movementDirection = input<'INWARD' | 'OUTWARD'>('OUTWARD');
  readonly maintainInBatches = input<boolean>(true);
  readonly trackDateOfMfg = input<boolean>(false);
  readonly useExpiryDates = input<boolean>(false);
  readonly targetActualQty = input<string>('0');
  readonly targetBilledQty = input<string>('0');
  readonly defaultRate = input<string>('0');
  readonly uomSymbol = input<string>('NOS');
  readonly voucherDate = input<string>('');
  readonly currencyScale = input<number>(2);
  readonly initialAllocations = input<readonly VoucherBatchAllocationDto[]>([]);

  readonly allocationsCommitted = output<readonly VoucherBatchAllocationDto[]>();
  readonly cancelled = output<void>();

  private readonly inventoryStore = inject(InventoryStore);
  private readonly inventoryService = inject(InventoryService);
  private readonly secondaryModal = inject(SecondaryMasterModalService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly destroyRef = inject(DestroyRef);

  readonly rows = signal<readonly EditableBatchAllocationRow[]>([]);
  readonly availableBatches = signal<readonly StockBatchDto[]>([]);
  readonly validationError = signal<string | null>(null);

  private rowCounter = 1;
  private wasOpen = false;

  readonly godownOptions = computed<readonly TypeaheadOption[]>(() =>
    this.inventoryStore.godownOptions(),
  );

  readonly summary = computed(() => {
    const scale = this.currencyScale();
    const targetBilled = ExactDecimal.tryParse(this.targetBilledQty()) ?? ExactDecimal.zero(3);
    const targetActual = ExactDecimal.tryParse(this.targetActualQty()) ?? targetBilled;

    let allocBilled = ExactDecimal.zero(3);
    let allocActual = ExactDecimal.zero(3);
    let totalAmt = ExactDecimal.zero(scale);

    for (const r of this.rows()) {
      const bq = ExactDecimal.tryParse(r.billedQty) ?? ExactDecimal.zero(3);
      const aq = ExactDecimal.tryParse(r.actualQty) ?? bq;
      const amt = ExactDecimal.tryParse(r.amount, scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
      allocBilled = allocBilled.add(bq);
      allocActual = allocActual.add(aq);
      totalAmt = totalAmt.add(amt, scale);
    }

    const remBilled = targetBilled.sub(allocBilled);
    const remActual = targetActual.sub(allocActual);

    return {
      allocatedBilledWire: allocBilled.toWireString(),
      allocatedActualWire: allocActual.toWireString(),
      remainingBilled: remBilled,
      remainingActual: remActual,
      totalAmountWire: totalAmt.toWireString(scale),
      isBalanced: remBilled.isZero() && remActual.isZero(),
    };
  });

  private modalHandle: ActiveModalContextHandle | null = null;

  actionHint(commandId: string, pairSpec: 'S/s' | 'C/c'): string | null {
    return this.shortcutRegistry.effectiveActionHint(
      'ledger.create',
      commandId,
      asciiPair(pairSpec),
    );
  }

  constructor() {
    effect(() => {
      const open = this.isOpen();
      if (open && !this.wasOpen) {
        this.wasOpen = true;
        this.releaseModal();
        this.modalHandle = this.shortcutRegistry.openModalContext(
          [
            {
              contextId: 'ledger.create',
              locale: 'en-GB',
              commandId: 'ledger.save',
              label: 'Save',
              letterPair: asciiPair('S/s'),
              permissionRef: 'voucher.create',
              effect: 'Commit godown and batch allocations.',
              normalActivation: () => this.commitAllocations(),
              iconSemanticId: 'mc.icon.save-document',
              stateTokenRole: 'action.primary',
            },
            {
              contextId: 'ledger.create',
              locale: 'en-GB',
              commandId: 'editor.close',
              label: 'Cancel',
              letterPair: asciiPair('C/c'),
              permissionRef: 'authenticated',
              effect: 'Close godown and batch allocation popover.',
              normalActivation: () => this.cancelled.emit(),
              iconSemanticId: 'mc.icon.close',
              stateTokenRole: 'action.secondary',
            },
          ],
          'en-GB',
        );
        this.hydrateModal();
      } else if (!open && this.wasOpen) {
        this.wasOpen = false;
        this.releaseModal();
      }
    });

    const disposeAccept = this.shortcutRegistry.bindHandler(
      'screen.accept',
      'MODAL',
      () => this.commitAllocations(),
      () => this.isOpen() && !this.secondaryModal.isOpen(),
    );
    this.destroyRef.onDestroy(() => {
      disposeAccept();
      this.releaseModal();
    });
  }

  private releaseModal(): void {
    if (this.modalHandle) {
      this.modalHandle.close();
      this.modalHandle = null;
    }
  }

  private hydrateModal(): void {
    this.validationError.set(null);
    this.availableBatches.set([]);

    const cid = this.companyId();
    const itemId = this.stockItemId();
    if (this.movementDirection() === 'OUTWARD' && this.maintainInBatches() && cid && itemId) {
      void this.inventoryService
        .listStockItemBatches(cid, itemId, { asOfDate: this.voucherDate() || null })
        .then((batches) => this.availableBatches.set(batches))
        .catch(() => this.availableBatches.set([]));
    }

    const defaultGodown =
      this.inventoryStore.godowns().find((g) => g.isSystemGodown) ??
      this.inventoryStore.godowns()[0] ??
      null;

    const existing = this.initialAllocations();
    if (existing.length > 0) {
      this.rows.set(
        existing.map((a) => ({
          rowId: `gb-${this.rowCounter++}`,
          godownId: a.godownId ?? defaultGodown?.id ?? null,
          godownName: a.godownName ?? defaultGodown?.name ?? 'Main Location',
          batchName: a.batchName,
          actualQty: a.actualQty,
          billedQty: a.billedQty,
          rate: a.rate,
          amount: a.amount,
          mfgDate: a.mfgDate ?? '',
          expiryDate: a.expiryDate ?? '',
        })),
      );
      return;
    }

    const billed = this.targetBilledQty() || '1';
    const actual = this.targetActualQty() || billed;
    const rate = this.defaultRate() || '0';

    this.rows.set([
      {
        rowId: `gb-${this.rowCounter++}`,
        godownId: defaultGodown?.id ?? null,
        godownName: defaultGodown?.name ?? 'Main Location',
        batchName: this.maintainInBatches() ? '' : 'Primary Batch',
        actualQty: actual,
        billedQty: billed,
        rate,
        amount: this.computeRowAmount(billed, rate),
        mfgDate: '',
        expiryDate: '',
      },
    ]);
  }

  private computeRowAmount(billedQtyStr: string, rateStr: string): string {
    const scale = this.currencyScale();
    const bq = ExactDecimal.tryParse(billedQtyStr);
    const rt = ExactDecimal.tryParse(rateStr);
    if (!bq || !rt) {
      return ExactDecimal.zero(scale).toWireString(scale);
    }
    return bq.mulScaled(rt, scale, 'HALF_UP').toWireString(scale);
  }

  isBatchExpired(expiryDate: string | null, explicitFlag?: boolean): boolean {
    if (explicitFlag === true) {
      return true;
    }
    const vDate = this.voucherDate();
    if (expiryDate && vDate && expiryDate < vDate) {
      return true;
    }
    return false;
  }

  pickAvailableBatch(rowIndex: number, batch: StockBatchDto): void {
    const list = [...this.rows()];
    const targetRow = list[rowIndex] ?? list[0];
    if (!targetRow) return;

    const idx = list[rowIndex] ? rowIndex : 0;
    list[idx] = {
      ...targetRow,
      godownId: batch.godownId ?? targetRow.godownId,
      godownName: batch.godownName ?? targetRow.godownName,
      batchName: batch.batchName,
      mfgDate: batch.mfgDate ?? targetRow.mfgDate,
      expiryDate: batch.expiryDate ?? targetRow.expiryDate,
    };
    this.rows.set(list);
    this.validationError.set(null);
  }

  onGodownSelected(idx: number, opt: TypeaheadOption): void {
    const list = [...this.rows()];
    if (!list[idx]) return;
    list[idx] = { ...list[idx], godownId: opt.id, godownName: opt.label };
    this.rows.set(list);
  }

  openInlineGodownCreate(idx: number, initialName: string): void {
    void this.secondaryModal.open({
      kind: 'GODOWN',
      initialName,
      onCreated: (created) => {
        const list = [...this.rows()];
        if (list[idx]) {
          list[idx] = { ...list[idx], godownId: created.id, godownName: created.name };
          this.rows.set(list);
        }
      },
    });
  }

  onBatchNameChange(idx: number, batchName: string): void {
    const list = [...this.rows()];
    if (!list[idx]) return;
    const matched = this.availableBatches().find(
      (b) => b.batchName.toLowerCase() === batchName.trim().toLowerCase(),
    );
    list[idx] = {
      ...list[idx],
      batchName,
      expiryDate: matched?.expiryDate ?? list[idx].expiryDate,
      mfgDate: matched?.mfgDate ?? list[idx].mfgDate,
    };
    this.rows.set(list);
  }

  onMfgDateChange(idx: number, mfgDate: string): void {
    const list = [...this.rows()];
    if (!list[idx]) return;
    list[idx] = { ...list[idx], mfgDate };
    this.rows.set(list);
  }

  onExpiryDateChange(idx: number, expiryDate: string): void {
    const list = [...this.rows()];
    if (!list[idx]) return;
    list[idx] = { ...list[idx], expiryDate };
    this.rows.set(list);
  }

  onActualQtyChange(idx: number, actualQty: string): void {
    const list = [...this.rows()];
    if (!list[idx]) return;
    list[idx] = { ...list[idx], actualQty };
    this.rows.set(list);
  }

  onBilledQtyChange(idx: number, billedQty: string): void {
    const list = [...this.rows()];
    if (!list[idx]) return;
    const row = list[idx];
    list[idx] = {
      ...row,
      billedQty,
      actualQty: row.actualQty === row.billedQty ? billedQty : row.actualQty,
      amount: this.computeRowAmount(billedQty, row.rate),
    };
    this.rows.set(list);
  }

  onRateChange(idx: number, rate: string): void {
    const list = [...this.rows()];
    if (!list[idx]) return;
    list[idx] = {
      ...list[idx],
      rate,
      amount: this.computeRowAmount(list[idx].billedQty, rate),
    };
    this.rows.set(list);
  }

  onRowBilledEnter(idx: number, event: Event): void {
    const rem = this.summary().remainingBilled;
    if (rem.isPositive() && idx === this.rows().length - 1) {
      event.preventDefault();
      this.appendSplitRow();
    }
  }

  appendSplitRow(): void {
    const remBilled = this.summary().remainingBilled;
    const remActual = this.summary().remainingActual;
    const billed = remBilled.isPositive() ? remBilled.toWireString() : '0';
    const actual = remActual.isPositive() ? remActual.toWireString() : billed;
    const defaultGodown =
      this.inventoryStore.godowns().find((g) => g.isSystemGodown) ??
      this.inventoryStore.godowns()[0] ??
      null;
    const rate = this.defaultRate() || '0';

    this.rows.set([
      ...this.rows(),
      {
        rowId: `gb-${this.rowCounter++}`,
        godownId: defaultGodown?.id ?? null,
        godownName: defaultGodown?.name ?? 'Main Location',
        batchName: this.maintainInBatches() ? '' : 'Primary Batch',
        actualQty: actual,
        billedQty: billed,
        rate,
        amount: this.computeRowAmount(billed, rate),
        mfgDate: '',
        expiryDate: '',
      },
    ]);
  }

  removeRow(idx: number): void {
    if (this.rows().length <= 1) return;
    this.rows.set(this.rows().filter((_, i) => i !== idx));
  }

  onDialogKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.cancelled.emit();
    }
  }

  commitAllocations(): void {
    this.validationError.set(null);
    const activeRows = this.rows().filter((r) => {
      const bq = ExactDecimal.tryParse(r.billedQty);
      return bq !== null && bq.isPositive();
    });

    if (activeRows.length === 0) {
      this.validationError.set('At least one positive Godown/Batch quantity allocation is required.');
      return;
    }

    const seenGodownBatchKeys = new Set<string>();
    for (const r of activeRows) {
      const cleanBatch = r.batchName.trim();
      if (this.maintainInBatches() && !cleanBatch) {
        this.validationError.set('Batch / Lot Name is required for batch-maintained items.');
        return;
      }

      const effBatchKey = `${(r.godownId ?? r.godownName ?? 'MAIN').toLowerCase()}::${(this.maintainInBatches() ? cleanBatch : 'PRIMARY BATCH').toLowerCase()}`;
      if (seenGodownBatchKeys.has(effBatchKey)) {
        this.validationError.set('Duplicate Godown and Batch combination is not allowed in Item Allocations.');
        return;
      }
      seenGodownBatchKeys.add(effBatchKey);

      if (r.mfgDate && r.expiryDate && r.expiryDate < r.mfgDate) {
        this.validationError.set(
          `Expiry Date (${r.expiryDate}) cannot be earlier than Manufacturing Date (${r.mfgDate}).`,
        );
        return;
      }

      if (this.movementDirection() === 'OUTWARD' && this.maintainInBatches()) {
        const knownBatch = this.availableBatches().find(
          (b) =>
            b.batchName.toLowerCase() === cleanBatch.toLowerCase() &&
            (!r.godownId || !b.godownId || b.godownId === r.godownId),
        );
        const effExpiry = r.expiryDate || knownBatch?.expiryDate || null;
        if (this.isBatchExpired(effExpiry, knownBatch?.isExpired)) {
          this.validationError.set(
            `Batch "${cleanBatch}" expired on ${effExpiry ?? 'prior date'} and cannot be issued outward on ${this.voucherDate() || 'this voucher'}.`,
          );
          return;
        }
      }
    }

    const sumState = this.summary();
    if (!sumState.isBalanced) {
      this.validationError.set(
        `Allocated Billed Qty (${sumState.allocatedBilledWire}) must equal Item Billed Qty (${this.targetBilledQty()}).`,
      );
      return;
    }

    const scale = this.currencyScale();
    const outputDtos: VoucherBatchAllocationDto[] = activeRows.map((r) => ({
      godownId: r.godownId,
      godownName: r.godownName,
      batchName: this.maintainInBatches() ? r.batchName.trim() : 'Primary Batch',
      actualQty: r.actualQty.trim() || r.billedQty.trim(),
      billedQty: r.billedQty.trim(),
      rate: r.rate.trim() || '0',
      amount: (ExactDecimal.tryParse(r.amount, scale, 'HALF_UP') ?? ExactDecimal.zero(scale)).toWireString(scale),
      mfgDate: r.mfgDate || null,
      expiryDate: r.expiryDate || null,
    }));

    this.allocationsCommitted.emit(outputDtos);
  }
}
