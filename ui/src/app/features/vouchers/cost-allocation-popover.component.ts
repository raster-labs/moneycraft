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
  viewChildren,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ActiveModalContextHandle,
  ShortcutCommandRegistry,
  asciiPair,
} from '../../core/keyboard/shortcut-command.registry';
import { ExactDecimal, RUPEE_SYMBOL } from '../../core/money/exact-decimal';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { AmountCellComponent } from '../../shared/components/amount-cell/amount-cell.component';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { SemanticIconComponent } from '../../shared/components/semantic-icon/semantic-icon.component';
import {
  TypeaheadOption,
  TypeaheadSelectComponent,
} from '../../shared/components/typeahead-select/typeahead-select.component';
import { ChartOfAccountsStore } from '../masters/chart-of-accounts.store';
import { BalanceSide } from '../masters/masters.models';
import { SecondaryMasterModalService } from '../masters/secondary-master-modal.service';
import { CostAllocationDto } from './vouchers.models';

export interface EditableCostAllocationRow {
  readonly rowId: string;
  costCategoryId: string | null;
  costCentreId: string | null;
  costCentreName: string;
  amount: string;
}

@Component({
  selector: 'mc-cost-allocation-popover',
  standalone: true,
  imports: [
    FormsModule,
    IndianCurrencyPipe,
    AmountCellComponent,
    CodeBadgeComponent,
    SemanticIconComponent,
    TypeaheadSelectComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (isOpen()) {
      <div
        class="mc-modal-backdrop mc-submodal-backdrop"
        role="dialog"
        aria-modal="true"
        data-testid="cost-allocation-modal"
        [attr.aria-label]="'Cost Allocations for ' + ledgerName()"
        (keydown)="onDialogKeyDown($event)"
      >
        <div class="mc-modal-dialog mc-cost-dialog" data-mc-nav-container>
          <div class="mc-modal-header">
            <div class="mc-cost-head-left">
              <span>Cost Allocations for: <strong>{{ ledgerName() }}</strong></span>
              <span class="mc-badge mc-badge-jade">
                Up to: {{ targetAmount() | mcIndianCurrency: currencyScale() : true }} {{ targetSide() }}
              </span>
            </div>
            <button type="button" class="mc-submodal-esc-btn" (click)="cancelled.emit()">
              Esc
            </button>
          </div>

          <div class="mc-modal-body mc-cost-body">
            @if (validationError(); as err) {
              <div class="mc-field-error mc-cost-err" role="alert" data-testid="cost-alloc-error">
                {{ err }}
              </div>
            }

            <div class="mc-cost-table-wrap">
              <table class="mc-dense-table" aria-label="Cost Centre Allocation Rows">
                <thead>
                  <tr>
                    <th>Name of Cost Centre (<span class="mc-key-alt">C</span> to Create)</th>
                    <th class="mc-cost-col-amt mc-amount">Amount</th>
                    <th class="mc-cost-col-act"></th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of rows(); track row.rowId; let idx = $index) {
                    <tr>
                      <td>
                        <mc-typeahead-select
                          #centreSelect
                          [inputId]="'cost-centre-sel-' + idx"
                          listTitle="List of Cost Centres"
                          [options]="costCentreOptions()"
                          [selectedId]="row.costCentreId"
                          [allowInlineCreate]="true"
                          [allowEmptyOnEnter]="summary().isBalanced && !row.costCentreId"
                          (optionSelected)="onCentreSelected(idx, $event)"
                          (emptyConfirmed)="commitAllocations()"
                          (createRequested)="openInlineCostCentreCreate(idx, $event)"
                        />
                      </td>
                      <td>
                        <mc-amount-cell
                          [inputId]="'cost-amt-' + idx"
                          [scale]="currencyScale()"
                          [currencySymbol]="currencySymbol()"
                          [advanceFocusOnEnter]="false"
                          [ngModel]="row.amount"
                          (ngModelChange)="onRowAmountChange(idx, $event)"
                          (enterPressed)="onRowAmountEnter(idx, $event)"
                        />
                      </td>
                      <td>
                        @if (rows().length > 1) {
                          <button
                            type="button"
                            class="mc-btn mc-cost-del-btn"
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
          </div>

          <div class="mc-modal-footer mc-cost-footer">
            <div class="mc-cost-totals">
              <span>
                Allocated:
                <strong class="mc-amount" data-testid="cost-allocated-total">
                  {{ summary().allocatedWire | mcIndianCurrency: currencyScale() : true }}
                </strong>
              </span>
              <span>
                Remaining:
                <strong
                  class="mc-amount"
                  [class.mc-cost-unbalanced]="!summary().isBalanced"
                >
                  {{ summary().residualAbsWire | mcIndianCurrency: currencyScale() : true }}
                </strong>
              </span>
              <button type="button" class="mc-btn" (click)="addRow()">+ Add Row</button>
            </div>

            <div class="mc-cost-actions">
              <span class="mc-cost-hint"><mc-code-badge code="Ctrl+A" /> = Accept</span>
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
                data-testid="cost-accept-btn"
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
    .mc-submodal-backdrop {
      z-index: var(--mc-z-modal);
    }

    .mc-cost-dialog {
      width: min(620px, 94vw);
    }

    .mc-cost-head-left {
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

    .mc-cost-body {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
      max-height: 64vh;
      overflow-y: auto;
    }

    .mc-cost-err {
      padding: var(--mc-space-2);
      background: #fef2f2;
      border: 1px solid var(--mc-color-danger-600);
      border-radius: var(--mc-radius-xs);
    }

    .mc-cost-table-wrap {
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      overflow: visible;
    }

    .mc-cost-col-amt {
      width: 180px;
    }

    .mc-cost-col-act {
      width: 36px;
    }

    .mc-cost-del-btn {
      padding: 0 0.4rem;
      font-weight: 700;
      color: var(--mc-color-danger-600);
    }

    .mc-cost-footer {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-3);
      flex-wrap: wrap;
    }

    .mc-cost-totals {
      display: flex;
      align-items: center;
      gap: var(--mc-space-3);
      font-size: var(--mc-font-size-xs);
      font-weight: 500;
    }

    .mc-cost-unbalanced {
      color: var(--mc-color-danger-600);
    }

    .mc-cost-actions {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-cost-hint {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }
  `,
})
export class CostAllocationPopoverComponent {
  private readonly coaStore = inject(ChartOfAccountsStore);
  private readonly secondaryModal = inject(SecondaryMasterModalService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly destroyRef = inject(DestroyRef);

  readonly isOpen = input.required<boolean>();
  readonly ledgerName = input.required<string>();
  readonly targetAmount = input.required<string>();
  readonly targetSide = input.required<BalanceSide>();
  readonly currencyScale = input<number>(2);
  readonly currencySymbol = input<string>(RUPEE_SYMBOL);
  readonly initialAllocations = input<readonly CostAllocationDto[]>([]);

  readonly allocationsCommitted = output<readonly CostAllocationDto[]>();
  readonly cancelled = output<void>();

  private readonly centreSelectRefs = viewChildren<TypeaheadSelectComponent>('centreSelect');

  readonly rows = signal<readonly EditableCostAllocationRow[]>([]);
  readonly validationError = signal<string | null>(null);

  private rowCounter = 1;
  private wasOpen = false;
  private modalHandle: ActiveModalContextHandle | null = null;

  actionHint(commandId: string, pairSpec: 'S/s' | 'C/c'): string | null {
    return this.shortcutRegistry.effectiveActionHint(
      'ledger.create',
      commandId,
      asciiPair(pairSpec),
    );
  }

  readonly costCentreOptions = computed<readonly TypeaheadOption[]>(() =>
    this.coaStore.costCentres().map((cc) => ({
      id: cc.id,
      label: cc.name,
      secondaryLabel: cc.costCategoryName ?? cc.categoryName ?? 'Primary Cost Category',
      codeBadge: cc.aliasCode ?? undefined,
    })),
  );

  readonly summary = computed(() => {
    const scale = this.currencyScale();
    const target =
      ExactDecimal.tryParse(this.targetAmount(), scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
    let allocated = ExactDecimal.zero(scale);
    for (const r of this.rows()) {
      if (!r.costCentreId) continue;
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
      () => this.isOpen() && !this.secondaryModal.isOpen(),
    );
    const disposeEsc = this.shortcutRegistry.bindHandler(
      'nav.escape',
      'MODAL',
      () => {
        this.cancelled.emit();
      },
      () => this.isOpen() && !this.secondaryModal.isOpen(),
    );
    this.destroyRef.onDestroy(() => {
      disposeAccept();
      disposeEsc();
      this.releaseModal();
    });

    effect(() => {
      const open = this.isOpen();
      if (open && !this.wasOpen) {
        this.wasOpen = true;
        this.validationError.set(null);
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
              effect: 'Commit cost centre allocations.',
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
              effect: 'Close cost allocation popover.',
              normalActivation: () => this.cancelled.emit(),
              iconSemanticId: 'mc.icon.close',
              stateTokenRole: 'action.secondary',
            },
          ],
          'en-GB',
        );
        void this.initializeModal();
      } else if (!open && this.wasOpen) {
        this.wasOpen = false;
        this.releaseModal();
      }
    });
  }

  private releaseModal(): void {
    if (this.modalHandle) {
      this.modalHandle.close();
      this.modalHandle = null;
    }
  }

  private async initializeModal(): Promise<void> {
    if (this.coaStore.costCentres().length === 0) {
      await this.coaStore.loadCostCentresAndCategories().catch(() => undefined);
    }

    const scale = this.currencyScale();
    const targetWire = (
      ExactDecimal.tryParse(this.targetAmount(), scale, 'HALF_UP') ?? ExactDecimal.zero(scale)
    ).toWireString(scale);
    const existing = this.initialAllocations();

    if (existing.length > 0) {
      this.rows.set(
        existing.map((a, idx) => {
          const matched = this.coaStore.costCentres().find((c) => c.id === a.costCentreId);
          return {
            rowId: `cost-row-${this.rowCounter++}`,
            costCategoryId: a.costCategoryId ?? matched?.costCategoryId ?? null,
            costCentreId: a.costCentreId,
            costCentreName: a.costCentreName ?? matched?.name ?? '',
            amount: existing.length === 1 && idx === 0 ? targetWire : a.amount,
          };
        }),
      );
    } else {
      const firstCc = this.coaStore.costCentres()[0] ?? null;
      this.rows.set([
        {
          rowId: `cost-row-${this.rowCounter++}`,
          costCategoryId: firstCc?.costCategoryId ?? null,
          costCentreId: firstCc?.id ?? null,
          costCentreName: firstCc?.name ?? '',
          amount: targetWire,
        },
      ]);
    }

    queueMicrotask(() => {
      this.centreSelectRefs()[0]?.focusInput();
    });
  }

  onDialogKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Escape' && !this.secondaryModal.isOpen()) {
      event.preventDefault();
      event.stopPropagation();
      this.cancelled.emit();
    }
  }

  onCentreSelected(idx: number, opt: TypeaheadOption): void {
    this.validationError.set(null);
    const cc = this.coaStore.costCentres().find((c) => c.id === opt.id);
    this.rows.set(
      this.rows().map((r, i) =>
        i === idx
          ? {
              ...r,
              costCategoryId: cc?.costCategoryId ?? null,
              costCentreId: opt.id,
              costCentreName: opt.label,
            }
          : r,
      ),
    );
  }

  openInlineCostCentreCreate(idx: number, seedName: string): void {
    void this.secondaryModal.open({
      kind: 'COST_CENTRE',
      initialName: seedName,
      defaultCostCategoryId: this.coaStore.costCategories()[0]?.id ?? null,
      onCreated: (created) => {
        this.onCentreSelected(idx, { id: created.id, label: created.name });
      },
    });
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
          rowId: `cost-row-${this.rowCounter++}`,
          costCategoryId: this.coaStore.costCategories()[0]?.id ?? null,
          costCentreId: null,
          costCentreName: '',
          amount: sum.residual.toWireString(this.currencyScale()),
        },
      ]);
      queueMicrotask(() => {
        this.centreSelectRefs()[nextIdx]?.focusInput();
      });
    }
  }

  addRow(): void {
    const sum = this.summary();
    const scale = this.currencyScale();
    this.rows.set([
      ...this.rows(),
      {
        rowId: `cost-row-${this.rowCounter++}`,
        costCategoryId: this.coaStore.costCategories()[0]?.id ?? null,
        costCentreId: null,
        costCentreName: '',
        amount: sum.residual.isPositive()
          ? sum.residual.toWireString(scale)
          : ExactDecimal.zero(scale).toWireString(scale),
      },
    ]);
  }

  removeRow(idx: number): void {
    if (this.rows().length <= 1) return;
    this.rows.set(this.rows().filter((_, i) => i !== idx));
  }

  commitAllocations(): void {
    this.validationError.set(null);
    const scale = this.currencyScale();
    const activeRows = this.rows().filter((r) => {
      const dec = ExactDecimal.tryParse(r.amount, scale, 'HALF_UP');
      return Boolean(r.costCentreId && dec && dec.isPositive());
    });

    if (activeRows.length === 0) {
      this.validationError.set('Select at least one Cost Centre with a positive allocation amount.');
      return;
    }

    const seenCentres = new Set<string>();
    for (const r of activeRows) {
      const key = (r.costCentreId ?? r.costCentreName).trim().toLowerCase();
      if (seenCentres.has(key)) {
        this.validationError.set('Name of Cost Centre is duplicate in Cost Allocation.');
        return;
      }
      seenCentres.add(key);
    }

    const sum = this.summary();
    if (!sum.isBalanced) {
      this.validationError.set(
        `Cost allocations must equal 100% of line amount (${sum.target.toWireString(scale)}). Remaining difference: ${sum.residualAbsWire}.`,
      );
      return;
    }

    const committed: CostAllocationDto[] = activeRows.map((r) => ({
      costCategoryId: r.costCategoryId,
      costCentreId: r.costCentreId!,
      costCentreName: r.costCentreName,
      side: this.targetSide(),
      amount: ExactDecimal.parse(r.amount, scale, 'HALF_UP').toWireString(scale),
    }));

    this.allocationsCommitted.emit(committed);
  }
}
