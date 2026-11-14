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
import { VoucherService } from './voucher.service';
import { BillAllocationDto, BillAllocationType, OpenBillDto } from './vouchers.models';

export interface EditableBillAllocationRow {
  readonly rowId: string;
  allocationType: BillAllocationType;
  billName: string;
  creditPeriodDays: number | null;
  dueDate: string;
  side: BalanceSide;
  amount: string;
  allowOverSettlement: boolean;
}

function computeDueDateIso(voucherDate: string, creditDays: number | null): string {
  if (!creditDays || creditDays <= 0 || !/^\d{4}-\d{2}-\d{2}$/.test(voucherDate)) {
    return '';
  }
  const [y, m, d] = voucherDate.split('-').map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d + Math.trunc(creditDays)));
  return dt.toISOString().slice(0, 10);
}

@Component({
  selector: 'mc-bill-wise-popover',
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
        data-testid="bill-wise-modal"
        [attr.aria-label]="'Bill-wise Details for ' + ledgerName()"
        (keydown)="onDialogKeyDown($event)"
      >
        <div class="mc-modal-dialog mc-billwise-dialog" data-mc-nav-container>
          <div class="mc-modal-header">
            <div class="mc-billwise-head-left">
              <span>Bill-wise Details for: <strong>{{ ledgerName() }}</strong></span>
              <span class="mc-badge mc-badge-jade">
                Up to: {{ targetAmount() | mcIndianCurrency: currencyScale() : true }} {{ targetSide() }}
              </span>
            </div>
            <button
              type="button"
              class="mc-submodal-esc-btn"
              (click)="cancelled.emit()"
              aria-label="Cancel Bill-wise Allocation"
            >
              Esc
            </button>
          </div>

          <div class="mc-modal-body mc-billwise-body">
            @if (validationError(); as err) {
              <div class="mc-field-error mc-billwise-err" role="alert" data-testid="billwise-error">
                {{ err }}
              </div>
            }

            <div class="mc-billwise-table-wrap">
              <table class="mc-dense-table" aria-label="Bill-wise Allocation Rows">
                <thead>
                  <tr>
                    <th class="mc-bw-col-type">Type of Ref</th>
                    <th class="mc-bw-col-name">Name / Bill Ref</th>
                    <th class="mc-bw-col-due">Due Date / Days</th>
                    <th class="mc-bw-col-amt mc-amount">Amount</th>
                    <th class="mc-bw-col-side">Dr/Cr</th>
                    <th class="mc-bw-col-act"></th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of rows(); track row.rowId; let idx = $index) {
                    <tr [class.mc-bw-row-active]="activeRowIndex() === idx">
                      <td>
                        <select
                          #methodSelect
                          class="mc-select"
                          mcGridCellNav
                          [attr.data-testid]="'bw-method-' + idx"
                          [ngModel]="row.allocationType"
                          (focus)="activeRowIndex.set(idx)"
                          (ngModelChange)="onMethodChange(idx, $event)"
                          aria-label="Type of Reference"
                        >
                          <option value="NEW_REF">New Ref</option>
                          <option value="AGST_REF">Agst Ref</option>
                          <option value="ADVANCE">Advance</option>
                          <option value="ON_ACCOUNT">On Account</option>
                        </select>
                      </td>

                      <td>
                        @if (row.allocationType === 'ON_ACCOUNT') {
                          <span class="mc-bw-onaccount-pill">On Account</span>
                        } @else if (row.allocationType === 'AGST_REF' && openBills().length > 0) {
                          <div class="mc-bw-agst-cell">
                            <select
                              class="mc-select mc-code"
                              mcGridCellNav
                              [attr.data-testid]="'bw-openbill-select-' + idx"
                              [ngModel]="row.billName"
                              (focus)="activeRowIndex.set(idx)"
                              (ngModelChange)="onOpenBillSelected(idx, $event)"
                              aria-label="Pending Bill Reference"
                            >
                              <option value="">-- Select Pending Bill --</option>
                              @for (bill of openBills(); track bill.billName) {
                                <option [value]="bill.billName">
                                  {{ bill.billName }} ({{ bill.billDate }} • Pending {{ bill.pendingAmount }} {{ bill.pendingSide }})
                                </option>
                              }
                            </select>
                          </div>
                        } @else {
                          <input
                            type="text"
                            class="mc-input mc-code"
                            mcGridCellNav
                            [attr.data-testid]="'bw-billname-' + idx"
                            [ngModel]="row.billName"
                            (focus)="activeRowIndex.set(idx)"
                            (ngModelChange)="onBillNameChange(idx, $event)"
                            placeholder="Invoice / Bill No."
                            aria-label="Bill Reference Name"
                          />
                        }
                      </td>

                      <td>
                        @if (row.allocationType === 'NEW_REF' || row.allocationType === 'ADVANCE') {
                          <div class="mc-bw-due-pair">
                            <input
                              type="number"
                              min="0"
                              max="3650"
                              class="mc-input mc-bw-days-input"
                              mcGridCellNav
                              [ngModel]="row.creditPeriodDays"
                              (focus)="activeRowIndex.set(idx)"
                              (ngModelChange)="onCreditDaysChange(idx, $event)"
                              placeholder="Days"
                              aria-label="Credit Days"
                            />
                            @if (row.dueDate) {
                              <mc-code-badge [code]="row.dueDate" />
                            }
                          </div>
                        } @else {
                          <span class="mc-bw-dash">—</span>
                        }
                      </td>

                      <td>
                        <mc-amount-cell
                          [inputId]="'bw-amt-' + idx"
                          [scale]="currencyScale()"
                          [currencySymbol]="currencySymbol()"
                          [advanceFocusOnEnter]="false"
                          [ngModel]="row.amount"
                          (ngModelChange)="onRowAmountChange(idx, $event)"
                          (enterPressed)="onRowAmountEnter(idx, $event)"
                        />
                      </td>

                      <td>
                        <select
                          class="mc-select"
                          [ngModel]="row.side"
                          (ngModelChange)="onRowSideChange(idx, $event)"
                          aria-label="Allocation Side"
                        >
                          <option value="DR">Dr</option>
                          <option value="CR">Cr</option>
                        </select>
                      </td>

                      <td>
                        @if (rows().length > 1) {
                          <button
                            type="button"
                            class="mc-btn mc-bw-del-btn"
                            (click)="removeRow(idx)"
                            title="Remove allocation row"
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

            <!-- Open Bills Quick Reference Panel when Agst Ref is active or Pending Bills exist -->
            @if (openBills().length > 0) {
              <div class="mc-bw-pending-panel" data-testid="pending-bills-panel">
                <div class="mc-bw-pending-head">
                  <span>Pending Bills for <strong>{{ ledgerName() }}</strong> (Click or choose in Agst Ref)</span>
                  <span>{{ openBills().length }} open</span>
                </div>
                <div class="mc-bw-pending-list">
                  @for (bill of openBills(); track bill.billName) {
                    <button
                      type="button"
                      class="mc-bw-pending-chip"
                      (click)="applyOpenBillToActiveRow(bill)"
                    >
                      <mc-code-badge [code]="bill.billName" />
                      <span class="mc-bw-chip-date">{{ bill.billDate }}</span>
                      <span class="mc-amount mc-bw-chip-amt">
                        {{ bill.pendingAmount | mcIndianCurrency: currencyScale() : true }} {{ bill.pendingSide }}
                      </span>
                    </button>
                  }
                </div>
              </div>
            }
          </div>

          <div class="mc-modal-footer mc-billwise-footer">
            <div class="mc-bw-summary">
              <span>
                Allocated:
                <strong class="mc-amount" data-testid="bw-allocated-total">
                  {{ allocatedSummary().allocatedWire | mcIndianCurrency: currencyScale() : true }} {{ targetSide() }}
                </strong>
              </span>
              <span>
                Remaining:
                <strong
                  class="mc-amount"
                  data-testid="bw-remaining-total"
                  [class.mc-bw-unbalanced]="!allocatedSummary().isBalanced"
                >
                  {{ allocatedSummary().residualAbsWire | mcIndianCurrency: currencyScale() : true }}
                </strong>
              </span>
              <button type="button" class="mc-btn" (click)="addBlankRow()">+ Add Ref</button>
            </div>

            <div class="mc-bw-actions">
              <span class="mc-bw-hint">Enter on balanced amount or <mc-code-badge code="Ctrl+A" /> to Accept</span>
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="bw-accept-btn"
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

    .mc-billwise-dialog {
      width: min(760px, 95vw);
    }

    .mc-billwise-head-left {
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

    .mc-billwise-body {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
      max-height: 68vh;
      overflow-y: auto;
    }

    .mc-billwise-err {
      padding: var(--mc-space-2);
      background: var(--mc-color-danger-50, #fef2f2);
      border: 1px solid var(--mc-color-danger-600);
      border-radius: var(--mc-radius-xs);
    }

    .mc-billwise-table-wrap {
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      overflow-x: auto;
    }

    .mc-bw-col-type {
      width: 135px;
    }

    .mc-bw-col-name {
      min-width: 200px;
    }

    .mc-bw-col-due {
      width: 150px;
    }

    .mc-bw-col-amt {
      width: 165px;
    }

    .mc-bw-col-side {
      width: 72px;
    }

    .mc-bw-col-act {
      width: 36px;
    }

    .mc-bw-row-active {
      background: var(--mc-color-teal-50);
    }

    .mc-bw-onaccount-pill {
      display: inline-block;
      padding: 0.2rem 0.5rem;
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
      color: var(--mc-color-slate-700);
      background: var(--mc-color-slate-100);
      border-radius: var(--mc-radius-xs);
    }

    .mc-bw-due-pair {
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .mc-bw-days-input {
      width: 64px;
    }

    .mc-bw-dash {
      color: var(--mc-color-slate-400);
    }

    .mc-bw-del-btn {
      padding: 0 0.4rem;
      font-weight: 700;
      color: var(--mc-color-danger-600);
    }

    .mc-bw-pending-panel {
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      padding: var(--mc-space-2) var(--mc-space-3);
    }

    .mc-bw-pending-head {
      display: flex;
      justify-content: space-between;
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
      color: var(--mc-color-slate-700);
      margin-bottom: 6px;
    }

    .mc-bw-pending-list {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }

    .mc-bw-pending-chip {
      background: var(--mc-color-surface);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      padding: 0.25rem 0.5rem;
      display: inline-flex;
      align-items: center;
      gap: 8px;
      font-size: var(--mc-font-size-xs);
      font-weight: 500;
      cursor: pointer;

      &:hover {
        border-color: var(--mc-color-verdigris-teal);
      }
    }

    .mc-bw-chip-date {
      color: var(--mc-color-slate-500);
      font-weight: 500;
    }

    .mc-bw-chip-amt {
      color: var(--mc-color-slate-900);
    }

    .mc-billwise-footer {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-3);
      flex-wrap: wrap;
    }

    .mc-bw-summary {
      display: flex;
      align-items: center;
      gap: var(--mc-space-3);
      font-size: var(--mc-font-size-xs);
      font-weight: 500;
    }

    .mc-bw-unbalanced {
      color: var(--mc-color-danger-600);
    }

    .mc-bw-actions {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-bw-hint {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }
  `,
})
export class BillWisePopoverComponent {
  private readonly voucherService = inject(VoucherService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly destroyRef = inject(DestroyRef);

  readonly isOpen = input.required<boolean>();
  readonly companyId = input.required<string>();
  readonly ledgerId = input.required<string>();
  readonly ledgerName = input.required<string>();
  readonly targetAmount = input.required<string>();
  readonly targetSide = input.required<BalanceSide>();
  readonly defaultBillRef = input<string>('1');
  readonly defaultMethod = input<BillAllocationType>('NEW_REF');
  readonly defaultCreditDays = input<number | null>(null);
  readonly voucherDate = input<string>('2025-04-01');
  readonly excludeVoucherId = input<string | null>(null);
  readonly currencyScale = input<number>(2);
  readonly currencySymbol = input<string>(RUPEE_SYMBOL);
  readonly initialAllocations = input<readonly BillAllocationDto[]>([]);

  readonly allocationsCommitted = output<readonly BillAllocationDto[]>();
  readonly cancelled = output<void>();

  private readonly methodSelectRefs = viewChildren<ElementRef<HTMLSelectElement>>('methodSelect');

  readonly openBills = signal<readonly OpenBillDto[]>([]);
  readonly rows = signal<readonly EditableBillAllocationRow[]>([]);
  readonly activeRowIndex = signal<number>(0);
  readonly validationError = signal<string | null>(null);

  private rowCounter = 1;
  private wasOpen = false;

  readonly allocatedSummary = computed(() => {
    const scale = this.currencyScale();
    const target =
      ExactDecimal.tryParse(this.targetAmount(), scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
    let signedAllocated = ExactDecimal.zero(scale);

    for (const r of this.rows()) {
      const amt = ExactDecimal.tryParse(r.amount, scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
      if (r.side === this.targetSide()) {
        signedAllocated = signedAllocated.add(amt);
      } else {
        signedAllocated = signedAllocated.sub(amt);
      }
    }

    const residual = target.sub(signedAllocated);
    return {
      target,
      signedAllocated,
      residual,
      allocatedWire: signedAllocated.abs().toWireString(scale),
      residualAbsWire: residual.abs().toWireString(scale),
      isBalanced: residual.isZero(),
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
        this.activeRowIndex.set(0);
        void this.initializeModalState();
      } else if (!open && this.wasOpen) {
        this.wasOpen = false;
      }
    });
  }

  private async initializeModalState(): Promise<void> {
    const cid = this.companyId();
    const lid = this.ledgerId();
    let fetchedBills: readonly OpenBillDto[] = [];
    if (cid && lid) {
      try {
        fetchedBills = await this.voucherService.listOpenBills(cid, lid, this.excludeVoucherId());
      } catch {
        fetchedBills = [];
      }
    }
    this.openBills.set(fetchedBills);

    const existing = this.initialAllocations();
    const scale = this.currencyScale();
    const targetWire = (
      ExactDecimal.tryParse(this.targetAmount(), scale, 'HALF_UP') ?? ExactDecimal.zero(scale)
    ).toWireString(scale);

    if (existing.length > 0) {
      const hydrated = existing.map((a, idx) => ({
        rowId: `bw-row-${this.rowCounter++}`,
        allocationType: a.allocationType,
        billName: a.billName,
        creditPeriodDays: a.creditPeriodDays ?? this.defaultCreditDays(),
        dueDate: a.dueDate ?? computeDueDateIso(this.voucherDate(), a.creditPeriodDays ?? null),
        side: a.side,
        amount: existing.length === 1 && idx === 0 ? targetWire : a.amount,
        allowOverSettlement: Boolean(a.allowOverSettlement),
      }));
      this.rows.set(hydrated);
    } else {
      const preferredMethod: BillAllocationType =
        this.defaultMethod() === 'AGST_REF' && fetchedBills.length === 0
          ? 'NEW_REF'
          : this.defaultMethod();

      if (preferredMethod === 'AGST_REF' && fetchedBills.length > 0) {
        const firstBill = fetchedBills[0];
        const targetDec =
          ExactDecimal.tryParse(targetWire, scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
        const pendingDec =
          ExactDecimal.tryParse(firstBill.pendingAmount, scale, 'HALF_UP') ?? targetDec;
        const settledDec = targetDec.compare(pendingDec) <= 0 ? targetDec : pendingDec;

        const initialRows: EditableBillAllocationRow[] = [
          {
            rowId: `bw-row-${this.rowCounter++}`,
            allocationType: 'AGST_REF',
            billName: firstBill.billName,
            creditPeriodDays: null,
            dueDate: firstBill.dueDate ?? '',
            side: this.targetSide(),
            amount: settledDec.toWireString(scale),
            allowOverSettlement: false,
          },
        ];

        const rem = targetDec.sub(settledDec);
        if (rem.isPositive()) {
          initialRows.push({
            rowId: `bw-row-${this.rowCounter++}`,
            allocationType: 'ON_ACCOUNT',
            billName: 'On Account',
            creditPeriodDays: null,
            dueDate: '',
            side: this.targetSide(),
            amount: rem.toWireString(scale),
            allowOverSettlement: false,
          });
        }
        this.rows.set(initialRows);
      } else {
        const creditDays = this.defaultCreditDays();
        this.rows.set([
          {
            rowId: `bw-row-${this.rowCounter++}`,
            allocationType: preferredMethod,
            billName:
              preferredMethod === 'ON_ACCOUNT' ? 'On Account' : this.defaultBillRef() || '1',
            creditPeriodDays: creditDays,
            dueDate: computeDueDateIso(this.voucherDate(), creditDays),
            side: this.targetSide(),
            amount: targetWire,
            allowOverSettlement: false,
          },
        ]);
      }
    }

    queueMicrotask(() => {
      this.methodSelectRefs()[0]?.nativeElement.focus();
    });
  }

  onDialogKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.cancelled.emit();
    }
  }

  onMethodChange(idx: number, nextMethod: BillAllocationType): void {
    this.validationError.set(null);
    const updated = this.rows().map((row, i) => {
      if (i !== idx) return row;
      if (nextMethod === 'ON_ACCOUNT') {
        return { ...row, allocationType: nextMethod, billName: 'On Account', dueDate: '' };
      }
      if (nextMethod === 'AGST_REF') {
        const firstBill = this.openBills()[0];
        return {
          ...row,
          allocationType: nextMethod,
          billName: firstBill?.billName ?? '',
          dueDate: firstBill?.dueDate ?? '',
        };
      }
      return {
        ...row,
        allocationType: nextMethod,
        billName:
          row.billName && row.billName !== 'On Account'
            ? row.billName
            : this.defaultBillRef() || '1',
        dueDate: computeDueDateIso(this.voucherDate(), row.creditPeriodDays),
      };
    });
    this.rows.set(updated);
  }

  onOpenBillSelected(idx: number, billName: string): void {
    this.validationError.set(null);
    const bill = this.openBills().find((b) => b.billName === billName);
    const scale = this.currencyScale();
    const otherRowsAllocated = this.computeAllocatedExcludingRow(idx);
    const targetDec =
      ExactDecimal.tryParse(this.targetAmount(), scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
    const remainingForRow = targetDec.sub(otherRowsAllocated);

    let suggestedWire = remainingForRow.isPositive()
      ? remainingForRow.toWireString(scale)
      : this.rows()[idx]?.amount ?? '0.00';

    if (bill) {
      const pendingDec =
        ExactDecimal.tryParse(bill.pendingAmount, scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
      if (remainingForRow.isPositive() && pendingDec.compare(remainingForRow) < 0) {
        suggestedWire = pendingDec.toWireString(scale);
      }
    }

    this.rows.set(
      this.rows().map((r, i) =>
        i === idx
          ? {
              ...r,
              billName,
              dueDate: bill?.dueDate ?? '',
              amount: suggestedWire,
            }
          : r,
      ),
    );
  }

  applyOpenBillToActiveRow(bill: OpenBillDto): void {
    const idx = this.activeRowIndex();
    this.onMethodChange(idx, 'AGST_REF');
    this.onOpenBillSelected(idx, bill.billName);
  }

  onBillNameChange(idx: number, name: string): void {
    this.validationError.set(null);
    this.rows.set(this.rows().map((r, i) => (i === idx ? { ...r, billName: name } : r)));
  }

  onCreditDaysChange(idx: number, rawDays: number | string | null): void {
    const parsed =
      rawDays === null || rawDays === '' ? null : Math.max(0, Math.trunc(Number(rawDays)));
    const due = computeDueDateIso(this.voucherDate(), parsed);
    this.rows.set(
      this.rows().map((r, i) =>
        i === idx ? { ...r, creditPeriodDays: parsed, dueDate: due } : r,
      ),
    );
  }

  onRowAmountChange(idx: number, amountWire: string): void {
    this.validationError.set(null);
    this.rows.set(this.rows().map((r, i) => (i === idx ? { ...r, amount: amountWire } : r)));
  }

  onRowSideChange(idx: number, side: BalanceSide): void {
    this.validationError.set(null);
    this.rows.set(this.rows().map((r, i) => (i === idx ? { ...r, side } : r)));
  }

  onRowAmountEnter(idx: number, amountWire: string): void {
    this.onRowAmountChange(idx, amountWire);
    const summary = this.allocatedSummary();
    if (summary.isBalanced) {
      this.commitAllocations();
      return;
    }

    if (summary.residual.isPositive() && idx === this.rows().length - 1) {
      const nextIdx = this.rows().length;
      this.rows.set([
        ...this.rows(),
        {
          rowId: `bw-row-${this.rowCounter++}`,
          allocationType: 'NEW_REF',
          billName: `${this.defaultBillRef() || '1'}-${nextIdx + 1}`,
          creditPeriodDays: this.defaultCreditDays(),
          dueDate: computeDueDateIso(this.voucherDate(), this.defaultCreditDays()),
          side: this.targetSide(),
          amount: summary.residual.toWireString(this.currencyScale()),
          allowOverSettlement: false,
        },
      ]);
      this.activeRowIndex.set(nextIdx);
      queueMicrotask(() => {
        this.methodSelectRefs()[nextIdx]?.nativeElement.focus();
      });
    }
  }

  addBlankRow(): void {
    const summary = this.allocatedSummary();
    const scale = this.currencyScale();
    const nextAmt = summary.residual.isPositive()
      ? summary.residual.toWireString(scale)
      : ExactDecimal.zero(scale).toWireString(scale);
    const nextIdx = this.rows().length;
    this.rows.set([
      ...this.rows(),
      {
        rowId: `bw-row-${this.rowCounter++}`,
        allocationType: this.defaultMethod(),
        billName:
          this.defaultMethod() === 'ON_ACCOUNT'
            ? 'On Account'
            : `${this.defaultBillRef() || '1'}-${nextIdx + 1}`,
        creditPeriodDays: this.defaultCreditDays(),
        dueDate: computeDueDateIso(this.voucherDate(), this.defaultCreditDays()),
        side: this.targetSide(),
        amount: nextAmt,
        allowOverSettlement: false,
      },
    ]);
    this.activeRowIndex.set(nextIdx);
  }

  removeRow(idx: number): void {
    if (this.rows().length <= 1) return;
    this.rows.set(this.rows().filter((_, i) => i !== idx));
    this.activeRowIndex.set(Math.max(0, idx - 1));
  }

  commitAllocations(): void {
    this.validationError.set(null);
    const scale = this.currencyScale();
    const nonZeroRows = this.rows().filter((r) => {
      const dec = ExactDecimal.tryParse(r.amount, scale, 'HALF_UP');
      return dec !== null && dec.isPositive();
    });

    if (nonZeroRows.length === 0) {
      this.validationError.set('Enter at least one positive bill-wise allocation amount.');
      return;
    }

    for (const r of nonZeroRows) {
      if (r.allocationType !== 'ON_ACCOUNT' && !r.billName.trim()) {
        this.validationError.set('Every New Ref / Agst Ref / Advance row requires a Bill Reference Name.');
        return;
      }
      if (r.allocationType === 'AGST_REF' && !r.allowOverSettlement) {
        const openBill = this.openBills().find((b) => b.billName === r.billName.trim());
        if (openBill) {
          const rowAmt = ExactDecimal.parse(r.amount, scale, 'HALF_UP');
          const pendingAmt = ExactDecimal.parse(openBill.pendingAmount, scale, 'HALF_UP');
          if (rowAmt.compare(pendingAmt) > 0) {
            this.validationError.set(
              `Allocation against bill "${openBill.billName}" (${rowAmt.toWireString(scale)}) exceeds its pending balance (${pendingAmt.toWireString(scale)}).`,
            );
            return;
          }
        }
      }
    }

    const summary = this.allocatedSummary();
    if (!summary.isBalanced) {
      this.validationError.set(
        `Bill-wise allocations must equal the party line amount (${summary.target.toWireString(scale)} ${this.targetSide()}). Remaining difference: ${summary.residualAbsWire}.`,
      );
      return;
    }

    const committed: BillAllocationDto[] = nonZeroRows.map((r) => ({
      allocationType: r.allocationType,
      billName: r.allocationType === 'ON_ACCOUNT' ? 'On Account' : r.billName.trim(),
      creditPeriodDays: r.creditPeriodDays,
      dueDate: r.dueDate || null,
      side: r.side,
      amount: ExactDecimal.parse(r.amount, scale, 'HALF_UP').toWireString(scale),
      allowOverSettlement: r.allowOverSettlement,
    }));

    this.allocationsCommitted.emit(committed);
  }

  private computeAllocatedExcludingRow(excludedIdx: number): ExactDecimal {
    const scale = this.currencyScale();
    let acc = ExactDecimal.zero(scale);
    this.rows().forEach((r, idx) => {
      if (idx === excludedIdx) return;
      const amt = ExactDecimal.tryParse(r.amount, scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
      acc = r.side === this.targetSide() ? acc.add(amt) : acc.sub(amt);
    });
    return acc;
  }
}
