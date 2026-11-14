import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChildren,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { ExactDecimal, RUPEE_SYMBOL } from '../../core/money/exact-decimal';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { AmountCellComponent } from '../../shared/components/amount-cell/amount-cell.component';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';
import { BalanceSide } from '../masters/masters.models';
import {
  BANK_TRANSACTION_TYPES,
  BankAllocationDto,
  BankTransactionType,
} from './vouchers.models';

export interface EditableBankAllocationRow {
  readonly rowId: string;
  transactionType: BankTransactionType;
  instrumentNo: string;
  instrumentDate: string;
  favouringName: string;
  amount: string;
}

@Component({
  selector: 'mc-bank-allocation-popover',
  standalone: true,
  imports: [
    FormsModule,
    IndianCurrencyPipe,
    AmountCellComponent,
    CodeBadgeComponent,
    GridCellNavDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (isOpen()) {
      <div
        class="mc-modal-backdrop mc-submodal-backdrop"
        role="dialog"
        aria-modal="true"
        data-testid="bank-allocation-modal"
        [attr.aria-label]="'Bank Allocations for ' + bankLedgerName()"
        (keydown)="onDialogKeyDown($event)"
      >
        <div class="mc-modal-dialog mc-bank-dialog" data-mc-nav-container>
          <div class="mc-modal-header">
            <div class="mc-bank-head-left">
              <span>Bank Allocations for: <strong>{{ bankLedgerName() }}</strong></span>
              <span class="mc-badge mc-badge-jade">
                For: {{ targetAmount() | mcIndianCurrency: currencyScale() : true }} {{ targetSide() }}
              </span>
            </div>
            <button type="button" class="mc-submodal-esc-btn" (click)="cancelled.emit()">
              Esc
            </button>
          </div>

          <div class="mc-modal-body mc-bank-body">
            @if (validationError(); as err) {
              <div class="mc-field-error mc-bank-err" role="alert" data-testid="bank-alloc-error">
                {{ err }}
              </div>
            }

            <div class="mc-bank-table-wrap">
              <table class="mc-dense-table" aria-label="Bank Instrument Allocation Rows">
                <thead>
                  <tr>
                    <th class="mc-bk-col-type">Transaction Type</th>
                    <th class="mc-bk-col-inst">Inst No. / UTR</th>
                    <th class="mc-bk-col-date">Inst Date</th>
                    <th>Favouring / Transfer Ref</th>
                    <th class="mc-bk-col-amt mc-amount">Amount</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of rows(); track row.rowId; let idx = $index) {
                    <tr>
                      <td>
                        <select
                          #txTypeSelect
                          class="mc-select"
                          mcGridCellNav
                          [attr.data-testid]="'bank-txtype-' + idx"
                          [ngModel]="row.transactionType"
                          (ngModelChange)="onTxTypeChange(idx, $event)"
                          aria-label="Bank Transaction Type"
                        >
                          @for (t of transactionTypes; track t.code) {
                            <option [value]="t.code">{{ t.label }}</option>
                          }
                        </select>
                      </td>

                      <td>
                        <input
                          type="text"
                          class="mc-input mc-code"
                          mcGridCellNav
                          [attr.data-testid]="'bank-instno-' + idx"
                          [ngModel]="row.instrumentNo"
                          (ngModelChange)="onInstNoChange(idx, $event)"
                          placeholder="Cheque / UTR No."
                          aria-label="Instrument Number"
                        />
                      </td>

                      <td>
                        <input
                          type="date"
                          class="mc-input mc-code"
                          mcGridCellNav
                          [ngModel]="row.instrumentDate"
                          (ngModelChange)="onInstDateChange(idx, $event)"
                          aria-label="Instrument Date"
                        />
                      </td>

                      <td>
                        <input
                          type="text"
                          class="mc-input"
                          mcGridCellNav
                          [ngModel]="row.favouringName"
                          (ngModelChange)="onFavouringChange(idx, $event)"
                          placeholder="Payee / Remitter"
                          aria-label="Favouring Name"
                        />
                      </td>

                      <td>
                        <mc-amount-cell
                          [inputId]="'bank-amt-' + idx"
                          [scale]="currencyScale()"
                          [currencySymbol]="currencySymbol()"
                          [advanceFocusOnEnter]="false"
                          [ngModel]="row.amount"
                          (ngModelChange)="onRowAmountChange(idx, $event)"
                          (enterPressed)="onRowAmountEnter(idx, $event)"
                        />
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </div>

          <div class="mc-modal-footer mc-bank-footer">
            <div class="mc-bank-totals">
              <span>
                Allocated:
                <strong class="mc-amount">
                  {{ summary().allocatedWire | mcIndianCurrency: currencyScale() : true }}
                </strong>
              </span>
              @if (!summary().isBalanced) {
                <span class="mc-bank-unbalanced">
                  Diff: {{ summary().residualAbsWire | mcIndianCurrency: currencyScale() : true }}
                </span>
              }
            </div>

            <div class="mc-bank-actions">
              <span class="mc-bank-hint">Enter on Amount or <mc-code-badge code="Ctrl+A" /> = Accept</span>
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="bank-accept-btn"
                (click)="commitAllocations()"
              >
                Accept (<span class="mc-key-ctrl">A</span>)
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `,
  styles: `
    .mc-submodal-backdrop {
      z-index: var(--mc-z-modal);
    }

    .mc-bank-dialog {
      width: min(820px, 95vw);
    }

    .mc-bank-head-left {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      flex-wrap: wrap;
    }

    .mc-submodal-esc-btn {
      background: rgba(255, 255, 255, 0.14);
      border: 1px solid rgba(255, 255, 255, 0.3);
      color: var(--mc-color-surface);
      border-radius: var(--mc-radius-xs);
      padding: 0.1rem 0.5rem;
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
      cursor: pointer;
    }

    .mc-bank-body {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
      max-height: 64vh;
      overflow-y: auto;
    }

    .mc-bank-err {
      padding: var(--mc-space-2);
      background: #fef2f2;
      border: 1px solid var(--mc-color-danger-600);
      border-radius: var(--mc-radius-xs);
    }

    .mc-bank-table-wrap {
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      overflow-x: auto;
    }

    .mc-bk-col-type {
      width: 190px;
    }

    .mc-bk-col-inst {
      width: 150px;
    }

    .mc-bk-col-date {
      width: 145px;
    }

    .mc-bk-col-amt {
      width: 165px;
    }

    .mc-bank-footer {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-3);
      flex-wrap: wrap;
    }

    .mc-bank-totals {
      display: flex;
      align-items: center;
      gap: var(--mc-space-3);
      font-size: var(--mc-font-size-xs);
      font-weight: 500;
    }

    .mc-bank-unbalanced {
      color: var(--mc-color-danger-600);
      font-weight: 700;
    }

    .mc-bank-actions {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-bank-hint {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }
  `,
})
export class BankAllocationPopoverComponent {
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly destroyRef = inject(DestroyRef);

  readonly isOpen = input.required<boolean>();
  readonly bankLedgerId = input.required<string>();
  readonly bankLedgerName = input.required<string>();
  readonly targetAmount = input.required<string>();
  readonly targetSide = input.required<BalanceSide>();
  readonly voucherDate = input<string>('2025-04-01');
  readonly defaultFavouring = input<string>('');
  readonly currencyScale = input<number>(2);
  readonly currencySymbol = input<string>(RUPEE_SYMBOL);
  readonly initialAllocations = input<readonly BankAllocationDto[]>([]);

  readonly allocationsCommitted = output<readonly BankAllocationDto[]>();
  readonly cancelled = output<void>();

  readonly transactionTypes = BANK_TRANSACTION_TYPES;
  private readonly txTypeSelectRefs = viewChildren<ElementRef<HTMLSelectElement>>('txTypeSelect');

  readonly rows = signal<readonly EditableBankAllocationRow[]>([]);
  readonly validationError = signal<string | null>(null);

  private rowCounter = 1;
  private wasOpen = false;

  readonly summary = computed(() => {
    const scale = this.currencyScale();
    const target =
      ExactDecimal.tryParse(this.targetAmount(), scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
    let allocated = ExactDecimal.zero(scale);
    for (const r of this.rows()) {
      const amt = ExactDecimal.tryParse(r.amount, scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
      allocated = allocated.add(amt);
    }
    const residual = target.sub(allocated);
    return {
      target,
      allocated,
      residual,
      allocatedWire: allocated.toWireString(scale),
      residualAbsWire: residual.abs().toWireString(scale),
      isBalanced: residual.isZero() && target.isPositive(),
    };
  });

  constructor() {
    const disposeAccept = this.shortcutRegistry.bindHandler(
      'screen.accept',
      'MODAL',
      () => {
        this.commitAllocations();
      },
      () => this.isOpen(),
    );
    const disposeEsc = this.shortcutRegistry.bindHandler(
      'nav.escape',
      'MODAL',
      () => {
        this.cancelled.emit();
      },
      () => this.isOpen(),
    );
    this.destroyRef.onDestroy(() => {
      disposeAccept();
      disposeEsc();
    });

    effect(() => {
      const open = this.isOpen();
      if (open && !this.wasOpen) {
        this.wasOpen = true;
        this.validationError.set(null);
        this.hydrateRows();
      } else if (!open && this.wasOpen) {
        this.wasOpen = false;
      }
    });
  }

  private hydrateRows(): void {
    const scale = this.currencyScale();
    const targetWire = (
      ExactDecimal.tryParse(this.targetAmount(), scale, 'HALF_UP') ?? ExactDecimal.zero(scale)
    ).toWireString(scale);
    const existing = this.initialAllocations();

    if (existing.length > 0) {
      this.rows.set(
        existing.map((a, idx) => ({
          rowId: `bank-row-${this.rowCounter++}`,
          transactionType: a.transactionType,
          instrumentNo: a.instrumentNo ?? '',
          instrumentDate: a.instrumentDate ?? this.voucherDate(),
          favouringName: a.favouringName ?? this.defaultFavouring(),
          amount: existing.length === 1 && idx === 0 ? targetWire : a.amount,
        })),
      );
    } else {
      this.rows.set([
        {
          rowId: `bank-row-${this.rowCounter++}`,
          transactionType: 'NEFT',
          instrumentNo: '',
          instrumentDate: this.voucherDate(),
          favouringName: this.defaultFavouring(),
          amount: targetWire,
        },
      ]);
    }

    queueMicrotask(() => {
      this.txTypeSelectRefs()[0]?.nativeElement.focus();
    });
  }

  onDialogKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.cancelled.emit();
    }
  }

  onTxTypeChange(idx: number, txType: BankTransactionType): void {
    this.rows.set(
      this.rows().map((r, i) => (i === idx ? { ...r, transactionType: txType } : r)),
    );
  }

  onInstNoChange(idx: number, instNo: string): void {
    this.rows.set(
      this.rows().map((r, i) => (i === idx ? { ...r, instrumentNo: instNo } : r)),
    );
  }

  onInstDateChange(idx: number, instDate: string): void {
    this.rows.set(
      this.rows().map((r, i) => (i === idx ? { ...r, instrumentDate: instDate } : r)),
    );
  }

  onFavouringChange(idx: number, fav: string): void {
    this.rows.set(
      this.rows().map((r, i) => (i === idx ? { ...r, favouringName: fav } : r)),
    );
  }

  onRowAmountChange(idx: number, amountWire: string): void {
    this.validationError.set(null);
    this.rows.set(this.rows().map((r, i) => (i === idx ? { ...r, amount: amountWire } : r)));
  }

  onRowAmountEnter(idx: number, amountWire: string): void {
    this.onRowAmountChange(idx, amountWire);
    const sum = this.summary();
    if (sum.isBalanced) {
      this.commitAllocations();
      return;
    }

    if (sum.residual.isPositive() && idx === this.rows().length - 1) {
      const nextIdx = this.rows().length;
      this.rows.set([
        ...this.rows(),
        {
          rowId: `bank-row-${this.rowCounter++}`,
          transactionType: 'NEFT',
          instrumentNo: '',
          instrumentDate: this.voucherDate(),
          favouringName: this.defaultFavouring(),
          amount: sum.residual.toWireString(this.currencyScale()),
        },
      ]);
      queueMicrotask(() => {
        this.txTypeSelectRefs()[nextIdx]?.nativeElement.focus();
      });
    }
  }

  commitAllocations(): void {
    this.validationError.set(null);
    const scale = this.currencyScale();
    const sum = this.summary();
    if (!sum.isBalanced) {
      this.validationError.set(
        `Bank allocations must equal ${sum.target.toWireString(scale)} ${this.targetSide()}. Remaining difference: ${sum.residualAbsWire}.`,
      );
      return;
    }

    const committed: BankAllocationDto[] = this.rows()
      .filter((r) => (ExactDecimal.tryParse(r.amount, scale, 'HALF_UP')?.isPositive() ?? false))
      .map((r) => ({
        bankLedgerId: this.bankLedgerId(),
        transactionType: r.transactionType,
        instrumentNo: r.instrumentNo.trim() || null,
        instrumentDate: r.instrumentDate || null,
        favouringName: r.favouringName.trim() || null,
        transferModeRef: r.instrumentNo.trim() || null,
        side: this.targetSide(),
        amount: ExactDecimal.parse(r.amount, scale, 'HALF_UP').toWireString(scale),
      }));

    this.allocationsCommitted.emit(committed);
  }
}
