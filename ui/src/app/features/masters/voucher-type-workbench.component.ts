import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import {
  TypeaheadOption,
  TypeaheadSelectComponent,
} from '../../shared/components/typeahead-select/typeahead-select.component';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';
import { CompanyContextStore } from '../../state/company-context.store';
import { ChartOfAccountsStore } from './chart-of-accounts.store';
import {
  VOUCHER_BASE_CATEGORIES,
  VoucherApprovalPolicy,
  VoucherBaseCategory,
  VoucherEntryMode,
  VoucherNumberingMethod,
  VoucherTypeMaster,
} from './masters.models';

@Component({
  selector: 'mc-voucher-type-workbench',
  standalone: true,
  imports: [
    FormsModule,
    RouterLink,
    CodeBadgeComponent,
    TypeaheadSelectComponent,
    GridCellNavDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-vt-split">
      <!-- Left Form: Voucher Type Creation / Alteration -->
      <form
        class="mc-card mc-vt-form-card"
        data-mc-nav-container
        (ngSubmit)="saveVoucherType()"
      >
        <div class="mc-vt-header">
          <div class="mc-vt-title-wrap">
            <h1>{{ editingVoucherType() ? 'Voucher Type Alteration' : 'Voucher Type Creation' }}</h1>
            @if (editingVoucherType()?.isSystemVoucherType) {
              <span class="mc-badge mc-badge-slate">Predefined 24 System Voucher Type</span>
            }
          </div>
          <div class="mc-vt-header-btns">
            <button type="button" class="mc-btn" (click)="startCreateVoucherType()">+ New Sub-Type</button>
            <a routerLink="/app/masters" class="mc-btn">Masters Hub</a>
          </div>
        </div>

        @if (statusMessage(); as msg) {
          <div class="mc-vt-status" role="status">{{ msg }}</div>
        }
        @if (formError(); as err) {
          <div class="mc-field-error mc-vt-err" role="alert">{{ err }}</div>
        }

        <div class="mc-vt-grid-3">
          <div>
            <label class="mc-field-label" for="vt-name">Name</label>
            <input
              id="vt-name"
              type="text"
              name="vtName"
              class="mc-input"
              mcGridCellNav
              [readOnly]="Boolean(editingVoucherType()?.isSystemVoucherType)"
              [(ngModel)]="formName"
              placeholder="e.g. GST B2B Sales"
              required
            />
          </div>

          <div>
            <label class="mc-field-label" for="vt-abbr">Abbreviation</label>
            <input
              id="vt-abbr"
              type="text"
              maxlength="12"
              name="vtAbbreviation"
              class="mc-input mc-code"
              mcGridCellNav
              [(ngModel)]="formAbbreviation"
              placeholder="Sale"
              required
            />
          </div>

          <div>
            <label class="mc-field-label" for="vt-category">Select Type of Voucher</label>
            <mc-typeahead-select
              inputId="vt-category"
              listTitle="List of Voucher Categories"
              [options]="baseCategoryOptions"
              [selectedId]="formBaseCategory()"
              [disabled]="Boolean(editingVoucherType()?.isSystemVoucherType)"
              [allowInlineCreate]="false"
              (optionSelected)="onBaseCategorySelected($event)"
            />
          </div>
        </div>

        <div class="mc-vt-grid-3">
          <div>
            <label class="mc-field-label" for="vt-numbering">Method of Voucher Numbering</label>
            <select
              id="vt-numbering"
              name="numberingMethod"
              class="mc-select"
              mcGridCellNav
              [(ngModel)]="formNumberingMethod"
            >
              <option value="AUTOMATIC_RETAIN_NUMBERS">Automatic (Manual Override / Retain GST Numbers)</option>
              <option value="AUTOMATIC_RENUMBER">Automatic (Renumber on Insert/Delete)</option>
              <option value="MULTI_USER_AUTO">Multi-User Auto</option>
              <option value="MANUAL">Manual</option>
              <option value="NONE">None</option>
            </select>
          </div>

          <div>
            <label class="mc-field-label" for="vt-entrymode">Default Entry Mode (Ctrl+H)</label>
            <select
              id="vt-entrymode"
              name="defaultEntryMode"
              class="mc-select"
              mcGridCellNav
              [(ngModel)]="formDefaultEntryMode"
            >
              <option value="SINGLE_ENTRY">Single Entry</option>
              <option value="DOUBLE_ENTRY">Double Entry</option>
              <option value="ITEM_INVOICE">Item Invoice</option>
              <option value="ACCOUNTING_INVOICE">Accounting Invoice</option>
              <option value="AS_VOUCHER">As Voucher</option>
            </select>
          </div>

          <div>
            <label class="mc-field-label" for="vt-approval">Maker-Checker Approval Policy</label>
            <select
              id="vt-approval"
              name="approvalPolicy"
              class="mc-select"
              mcGridCellNav
              [(ngModel)]="formApprovalPolicy"
            >
              <option value="SINGLE_STEP">Single Step (Immediate Post)</option>
              <option value="FOUR_EYES">Four Eyes (Pending Approval Queue)</option>
            </select>
          </div>
        </div>

        <div class="mc-vt-subpanel">
          <div class="mc-vt-subpanel-title">General, Printing &amp; Manufacturing Controls</div>
          <div class="mc-vt-toggles-grid">
            <label class="mc-vt-toggle">
              <input type="checkbox" name="preventDup" [(ngModel)]="formPreventDuplicates" />
              <span>Prevent duplicate voucher numbers (Mandatory for GSTR-1)</span>
            </label>
            <label class="mc-vt-toggle">
              <input type="checkbox" name="effectiveDates" [(ngModel)]="formUseEffectiveDates" />
              <span>Use effective dates for vouchers</span>
            </label>
            <label class="mc-vt-toggle">
              <input type="checkbox" name="zeroValued" [(ngModel)]="formAllowZeroValued" />
              <span>Allow zero-valued transactions</span>
            </label>
            <label class="mc-vt-toggle">
              <input type="checkbox" name="optionalDefault" [(ngModel)]="formMakeOptionalByDefault" />
              <span>Make this voucher type as 'Optional' by default</span>
            </label>
            <label class="mc-vt-toggle">
              <input type="checkbox" name="narrationPerLed" [(ngModel)]="formAllowNarrationPerLedger" />
              <span>Provide narrations for each ledger in voucher</span>
            </label>
            <label class="mc-vt-toggle">
              <input type="checkbox" name="printAfterSave" [(ngModel)]="formPrintAfterSave" />
              <span>Print voucher after saving</span>
            </label>
            @if (formBaseCategory() === 'STOCK_JOURNAL') {
              <label class="mc-vt-toggle">
                <input type="checkbox" name="mfgJournal" [(ngModel)]="formIsManufacturingJournal" />
                <span>Use as a Manufacturing Journal (BoM Consumption &amp; Co-Products)</span>
              </label>
            }
          </div>
        </div>

        <div class="mc-vt-footer">
          <span class="mc-vt-hint">Enter = Next Field &bull; 24 TallyPrime 7.2 Voucher Categories</span>
          <button
            type="button"
            class="mc-btn mc-btn-primary"
            [disabled]="saving() || companyContext.quarantined()"
            (click)="saveVoucherType()"
          >
            Accept (<span class="mc-key-ctrl">A</span>)
          </button>
        </div>
      </form>

      <!-- Right Directory of 24 Predefined + Custom Voucher Types -->
      <aside class="mc-card mc-vt-list-card" aria-label="Voucher Types Directory">
        <div class="mc-vt-list-head">
          <strong>Voucher Types ({{ filteredVoucherTypes().length }})</strong>
          <input
            type="text"
            class="mc-input"
            [ngModel]="directoryQuery()"
            (ngModelChange)="directoryQuery.set($event)"
            placeholder="Filter voucher types..."
            aria-label="Filter Voucher Types"
          />
        </div>

        <ul class="mc-vt-list">
          @for (vt of filteredVoucherTypes(); track vt.id) {
            <li
              class="mc-vt-row"
              [class.mc-vt-row-active]="editingVoucherType()?.id === vt.id"
              (click)="selectVoucherTypeForAlter(vt)"
            >
              <div class="mc-vt-row-main">
                <span class="mc-vt-row-name">{{ vt.name }}</span>
                <span class="mc-vt-row-sub">
                  {{ vt.baseCategory }} &bull; {{ vt.defaultEntryMode }}
                </span>
              </div>
              <div class="mc-vt-row-badges">
                @if (vt.shortcutKey) {
                  <mc-code-badge [code]="vt.shortcutKey" />
                }
                <mc-code-badge [code]="vt.abbreviation" />
              </div>
            </li>
          } @empty {
            <li class="mc-vt-empty">No voucher types match filter.</li>
          }
        </ul>
      </aside>
    </div>
  `,
  styles: `
    .mc-vt-split {
      display: grid;
      grid-template-columns: minmax(0, 1.55fr) minmax(280px, 0.85fr);
      gap: var(--mc-space-4);
      align-items: start;
    }

    @media (max-width: 1024px) {
      .mc-vt-split {
        grid-template-columns: 1fr;
      }
    }

    .mc-vt-form-card {
      padding: var(--mc-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-vt-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-2);
      padding-bottom: var(--mc-space-2);
      border-bottom: var(--mc-border-subtle);
      flex-wrap: wrap;
    }

    .mc-vt-title-wrap {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);

      h1 {
        margin: 0;
        font-size: var(--mc-font-size-lg);
      }
    }

    .mc-vt-header-btns {
      display: flex;
      gap: var(--mc-space-2);
    }

    .mc-vt-status {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-teal-50);
      border-left: 3px solid var(--mc-color-verdigris-teal);
      color: var(--mc-color-teal-700);
      font-weight: 600;
      font-size: var(--mc-font-size-xs);
    }

    .mc-vt-err {
      padding: var(--mc-space-2);
      background: var(--mc-color-danger-50);
      border-left: 3px solid var(--mc-color-danger-600);
    }

    .mc-vt-grid-3 {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    @media (max-width: 860px) {
      .mc-vt-grid-3 {
        grid-template-columns: 1fr;
      }
    }

    .mc-vt-subpanel {
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-sm);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-vt-subpanel-title {
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      text-transform: uppercase;
      color: var(--mc-color-slate-700);
    }

    .mc-vt-toggles-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-2);
    }

    .mc-vt-toggle {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      font-weight: 500;
      cursor: pointer;
    }

    .mc-vt-footer {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-top: var(--mc-space-2);
      border-top: var(--mc-border-subtle);
    }

    .mc-vt-hint {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-500);
    }

    .mc-vt-list-card {
      overflow: hidden;
      display: flex;
      flex-direction: column;
      max-height: calc(100vh - 160px);
    }

    .mc-vt-list-head {
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border-bottom: var(--mc-border-subtle);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-vt-list {
      list-style: none;
      margin: 0;
      padding: 0;
      overflow-y: auto;
    }

    .mc-vt-row {
      padding: 0.45rem 0.75rem;
      border-bottom: 1px solid var(--mc-color-slate-100);
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-2);
      cursor: pointer;
    }

    .mc-vt-row-active {
      background: var(--mc-color-teal-50);
      border-left: 3px solid var(--mc-color-verdigris-teal);
    }

    .mc-vt-row-main {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .mc-vt-row-name {
      font-weight: 600;
    }

    .mc-vt-row-sub {
      font-size: 11px;
      color: var(--mc-color-slate-600);
    }

    .mc-vt-row-badges {
      display: flex;
      gap: 4px;
    }

    .mc-vt-empty {
      padding: var(--mc-space-4);
      text-align: center;
      color: var(--mc-color-slate-500);
    }
  `,
})
export class VoucherTypeWorkbenchComponent implements OnInit {
  protected readonly Boolean = Boolean;

  readonly coaStore = inject(ChartOfAccountsStore);
  readonly companyContext = inject(CompanyContextStore);

  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly destroyRef = inject(DestroyRef);

  readonly embeddedOverlay = input<boolean>(false);

  readonly baseCategoryOptions: readonly TypeaheadOption[] = VOUCHER_BASE_CATEGORIES.map((c) => ({
    id: c.code,
    label: c.label,
    secondaryLabel: c.defaultMode,
    codeBadge: c.code,
  }));

  readonly editingVoucherType = signal<VoucherTypeMaster | null>(null);
  readonly saving = signal<boolean>(false);
  readonly statusMessage = signal<string | null>(null);
  readonly formError = signal<string | null>(null);
  readonly directoryQuery = signal<string>('');

  formName = '';
  formAbbreviation = 'Sale';
  readonly formBaseCategory = signal<VoucherBaseCategory>('SALES');
  formNumberingMethod: VoucherNumberingMethod = 'AUTOMATIC_RETAIN_NUMBERS';
  formDefaultEntryMode: VoucherEntryMode = 'ITEM_INVOICE';
  formApprovalPolicy: VoucherApprovalPolicy = 'SINGLE_STEP';
  formPreventDuplicates = true;
  formUseEffectiveDates = false;
  formAllowZeroValued = false;
  formMakeOptionalByDefault = false;
  formAllowNarrationPerLedger = false;
  formPrintAfterSave = false;
  formIsManufacturingJournal = false;

  readonly filteredVoucherTypes = computed(() => {
    const q = this.directoryQuery().trim().toLowerCase();
    const list = this.coaStore.voucherTypes();
    if (!q) return list;
    return list.filter(
      (v) =>
        v.name.toLowerCase().includes(q) ||
        v.abbreviation.toLowerCase().includes(q) ||
        v.baseCategory.toLowerCase().includes(q),
    );
  });

  async ngOnInit(): Promise<void> {
    const scope = this.embeddedOverlay() ? 'MODAL' : 'SCREEN';
    const disposeAccept = this.shortcutRegistry.bindHandler('screen.accept', scope, () => {
      void this.saveVoucherType();
    });
    this.destroyRef.onDestroy(disposeAccept);

    const loaded = await this.coaStore.loadVoucherTypes().catch(() => []);
    if (loaded.length > 0) {
      this.selectVoucherTypeForAlter(loaded[0]);
    }
  }

  onBaseCategorySelected(opt: TypeaheadOption): void {
    const cat = opt.id as VoucherBaseCategory;
    this.formBaseCategory.set(cat);
    const meta = VOUCHER_BASE_CATEGORIES.find((m) => m.code === cat);
    if (meta) {
      this.formDefaultEntryMode = meta.defaultMode;
    }
  }

  startCreateVoucherType(): void {
    this.editingVoucherType.set(null);
    this.formError.set(null);
    this.formName = '';
    this.formAbbreviation = 'Sale';
    this.formBaseCategory.set('SALES');
    this.formNumberingMethod = 'AUTOMATIC_RETAIN_NUMBERS';
    this.formDefaultEntryMode = 'ITEM_INVOICE';
    this.formApprovalPolicy = 'SINGLE_STEP';
    this.formPreventDuplicates = true;
    this.formUseEffectiveDates = false;
    this.formAllowZeroValued = false;
    this.formMakeOptionalByDefault = false;
    this.formAllowNarrationPerLedger = false;
    this.formPrintAfterSave = false;
    this.formIsManufacturingJournal = false;
  }

  selectVoucherTypeForAlter(vt: VoucherTypeMaster): void {
    this.editingVoucherType.set(vt);
    this.formError.set(null);
    this.formName = vt.name;
    this.formAbbreviation = vt.abbreviation;
    this.formBaseCategory.set(vt.baseCategory);
    this.formNumberingMethod = vt.numberingMethod;
    this.formDefaultEntryMode = vt.defaultEntryMode;
    this.formApprovalPolicy = vt.approvalPolicy;
    this.formPreventDuplicates = vt.preventDuplicateNumbers;
    this.formUseEffectiveDates = vt.useEffectiveDates;
    this.formAllowZeroValued = vt.allowZeroValuedTransactions;
    this.formMakeOptionalByDefault = vt.makeOptionalByDefault;
    this.formAllowNarrationPerLedger = vt.allowNarrationPerLedger;
    this.formPrintAfterSave = vt.printAfterSave;
    this.formIsManufacturingJournal = vt.isManufacturingJournal;
  }

  async saveVoucherType(): Promise<void> {
    if (this.saving()) return;
    this.formError.set(null);
    if (!this.formName.trim() || !this.formAbbreviation.trim()) {
      this.formError.set('Voucher Type Name and Abbreviation are required.');
      return;
    }

    this.saving.set(true);
    try {
      const parentSys = this.coaStore
        .voucherTypes()
        .find((v) => v.isSystemVoucherType && v.baseCategory === this.formBaseCategory());

      const payload = {
        name: this.formName.trim(),
        abbreviation: this.formAbbreviation.trim(),
        baseCategory: this.formBaseCategory(),
        parentVoucherTypeId: this.editingVoucherType()?.isSystemVoucherType
          ? null
          : (parentSys?.id ?? null),
        defaultEntryMode: this.formDefaultEntryMode,
        numberingMethod: this.formNumberingMethod,
        preventDuplicateNumbers: this.formPreventDuplicates,
        useEffectiveDates: this.formUseEffectiveDates,
        allowZeroValuedTransactions: this.formAllowZeroValued,
        makeOptionalByDefault: this.formMakeOptionalByDefault,
        allowNarrationPerLedger: this.formAllowNarrationPerLedger,
        printAfterSave: this.formPrintAfterSave,
        isManufacturingJournal:
          this.formBaseCategory() === 'STOCK_JOURNAL' ? this.formIsManufacturingJournal : false,
        approvalPolicy: this.formApprovalPolicy,
        active: true,
      };

      const editing = this.editingVoucherType();
      if (editing) {
        const updated = await this.coaStore.updateVoucherType(
          editing.id,
          payload,
          editing.versionNo,
        );
        this.selectVoucherTypeForAlter(updated);
        this.statusMessage.set(`Updated Voucher Type "${updated.name}".`);
      } else {
        const created = await this.coaStore.createVoucherType(payload);
        this.selectVoucherTypeForAlter(created);
        this.statusMessage.set(`Created custom Voucher Type "${created.name}".`);
      }
    } catch (err: unknown) {
      this.formError.set(err instanceof Error ? err.message : 'Failed to save Voucher Type.');
    } finally {
      this.saving.set(false);
    }
  }
}
