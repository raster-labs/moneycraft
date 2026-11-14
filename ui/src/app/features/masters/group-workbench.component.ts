import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import {
  TypeaheadOption,
  TypeaheadSelectComponent,
} from '../../shared/components/typeahead-select/typeahead-select.component';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { ChartOfAccountsStore } from './chart-of-accounts.store';
import {
  AccountGroup,
  AccountNature,
  CreateOrUpdateAccountGroupPayload,
  PurchaseAllocationMethod,
} from './masters.models';
import { EditLogModalService } from '../statutory/edit-log-modal.service';
import { SecondaryMasterModalService } from './secondary-master-modal.service';

@Component({
  selector: 'mc-group-workbench',
  standalone: true,
  imports: [
    FormsModule,
    RouterLink,
    TypeaheadSelectComponent,
    GridCellNavDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-workbench-split">
      <!-- Left Master Form: Group Creation / Alteration -->
      <form
        class="mc-card mc-workbench-form-card"
        data-mc-nav-container
        (ngSubmit)="saveGroup()"
      >
        <div class="mc-workbench-header">
          <div class="mc-workbench-title-group">
            <h1>{{ editingGroup() ? 'Account Group Alteration' : 'Account Group Creation' }}</h1>
            @if (editingGroup()?.isSystemGroup) {
              <span class="mc-badge mc-badge-slate" data-testid="system-group-badge">
                Predefined System Group (Protected)
              </span>
            }
          </div>
          <div class="mc-workbench-header-btns">
            @if (editingGroup(); as edGrp) {
              <button
                type="button"
                class="mc-btn"
                data-testid="group-edit-log-btn"
                (click)="openGroupEditLog(edGrp)"
              >
                Edit Log (<span class="mc-key-alt">Q</span>)
              </button>
            }
            <button type="button" class="mc-btn" (click)="startCreateGroup()">+ New Group</button>
            <a routerLink="/app/coa" class="mc-btn">Chart of Accounts</a>
          </div>
        </div>

        @if (statusMessage(); as msg) {
          <div class="mc-workbench-status" role="status">{{ msg }}</div>
        }
        @if (formError(); as err) {
          <div class="mc-field-error mc-workbench-err" role="alert">{{ err }}</div>
        }

        <div class="mc-wb-grid-2">
          <div>
            <label class="mc-field-label" for="grp-name">Name</label>
            <input
              #nameInput
              id="grp-name"
              type="text"
              name="groupName"
              class="mc-input"
              mcGridCellNav
              [readOnly]="Boolean(editingGroup()?.isSystemGroup)"
              [ngModel]="formName()"
              (ngModelChange)="onFieldEdit('name', $event)"
              required
            />
          </div>

          <div>
            <label class="mc-field-label" for="grp-alias">(Alias)</label>
            <input
              id="grp-alias"
              type="text"
              name="groupAlias"
              class="mc-input mc-code"
              mcGridCellNav
              [ngModel]="formAlias()"
              (ngModelChange)="onFieldEdit('alias', $event)"
              placeholder="Optional short code"
            />
          </div>
        </div>

        <div class="mc-wb-grid-2">
          <div>
            <label class="mc-field-label" for="grp-under">
              Under (<span class="mc-key-alt">C</span> to create Parent Group)
            </label>
            <mc-typeahead-select
              inputId="grp-under"
              listTitle="List of Groups"
              [options]="availableParentOptions()"
              [selectedId]="formParentGroupId() ?? '__PRIMARY__'"
              [disabled]="Boolean(editingGroup()?.isSystemGroup)"
              [allowInlineCreate]="true"
              (optionSelected)="onParentSelected($event)"
              (createRequested)="openInlineParentCreator($event)"
            />
          </div>

          <div>
            <label class="mc-field-label" for="grp-nature">Nature of Group</label>
            <select
              id="grp-nature"
              name="groupNature"
              class="mc-select"
              mcGridCellNav
              [disabled]="formParentGroupId() !== null || Boolean(editingGroup()?.isSystemGroup)"
              [ngModel]="formNature()"
              (ngModelChange)="formNature.set($event); markDirty()"
            >
              <option value="ASSETS">Assets</option>
              <option value="LIABILITIES">Liabilities</option>
              <option value="INCOME">Incomes</option>
              <option value="EXPENSES">Expenses</option>
            </select>
          </div>
        </div>

        <!-- TallyPrime 7.2 Group Behavioural Flags -->
        <div class="mc-wb-subpanel">
          <div class="mc-wb-subpanel-title">Reporting &amp; Calculation Behaviour</div>

          <div class="mc-wb-toggles-grid">
            @if (formParentGroupId() === null && (formNature() === 'INCOME' || formNature() === 'EXPENSES')) {
              <label class="mc-wb-toggle">
                <input
                  type="checkbox"
                  name="affectsGrossProfit"
                  [disabled]="Boolean(editingGroup()?.isSystemGroup)"
                  [ngModel]="formAffectsGrossProfit()"
                  (ngModelChange)="formAffectsGrossProfit.set($event); markDirty()"
                />
                <span>Does it affect Gross Profits? (Trading vs. P&amp;L)</span>
              </label>
            }

            <label class="mc-wb-toggle">
              <input
                type="checkbox"
                name="behavesLikeSubledger"
                [ngModel]="formBehavesLikeSubledger()"
                (ngModelChange)="formBehavesLikeSubledger.set($event); markDirty()"
              />
              <span>Group behaves like a sub-ledger</span>
            </label>

            <label class="mc-wb-toggle">
              <input
                type="checkbox"
                name="netDebitCredit"
                [ngModel]="formNetDebitCredit()"
                (ngModelChange)="formNetDebitCredit.set($event); markDirty()"
              />
              <span>Nett Debit/Credit Balances for Reporting</span>
            </label>

            <label class="mc-wb-toggle">
              <input
                type="checkbox"
                name="usedForCalculation"
                [ngModel]="formUsedForCalculation()"
                (ngModelChange)="formUsedForCalculation.set($event); markDirty()"
              />
              <span>Used for Calculation (e.g. Taxes, Discounts in Invoice Entry)</span>
            </label>
          </div>

          <div class="mc-wb-grid-3">
            <div>
              <label class="mc-field-label" for="grp-alloc">
                Method to Allocate when used in Purchase Invoice
              </label>
              <select
                id="grp-alloc"
                name="methodToAllocatePurchase"
                class="mc-select"
                mcGridCellNav
                [ngModel]="formMethodToAllocate()"
                (ngModelChange)="formMethodToAllocate.set($event); markDirty()"
              >
                <option value="NOT_APPLICABLE">Not Applicable</option>
                <option value="APPROPRIATE_BY_QTY">Appropriate by Qty</option>
                <option value="APPROPRIATE_BY_VALUE">Appropriate by Value</option>
              </select>
            </div>

            <div>
              <label class="mc-field-label" for="grp-hsn">Default HSN/SAC (Tier 5 Inheritance)</label>
              <input
                id="grp-hsn"
                type="text"
                name="defaultHsnSacCode"
                class="mc-input mc-code"
                mcGridCellNav
                [ngModel]="formDefaultHsn()"
                (ngModelChange)="formDefaultHsn.set($event); markDirty()"
                placeholder="e.g. 90189099"
              />
            </div>

            <div>
              <label class="mc-field-label" for="grp-gstrate">Default GST Rate (%)</label>
              <input
                id="grp-gstrate"
                type="text"
                name="defaultGstRatePct"
                class="mc-input mc-amount"
                mcGridCellNav
                [ngModel]="formDefaultGstRate()"
                (ngModelChange)="formDefaultGstRate.set($event); markDirty()"
                placeholder="e.g. 18.00"
              />
            </div>
          </div>
        </div>

        <div class="mc-workbench-footer">
          <div class="mc-footer-left">
            @if (editingGroup(); as grp) {
              <button
                type="button"
                class="mc-btn mc-btn-danger"
                data-testid="delete-group-btn"
                [disabled]="grp.isSystemGroup || companyContext.quarantined()"
                (click)="deleteCurrentGroup()"
              >
                <span class="mc-key-alt">D</span>: Delete
              </button>
            }
          </div>

          <div class="mc-footer-right">
            <span class="mc-footer-hint">Enter = Next &bull; Shift+Enter = Prev</span>
            <button
              type="submit"
              class="mc-btn mc-btn-primary"
              [disabled]="saving() || companyContext.quarantined()"
            >
              Accept (<span class="mc-key-ctrl">A</span>)
            </button>
          </div>
        </div>
      </form>

      <!-- Right Directory List: 28 Predefined + Custom Groups -->
      <aside class="mc-card mc-workbench-list-card" aria-label="Account Groups Directory">
        <div class="mc-list-head">
          <strong>Existing Account Groups ({{ filteredGroups().length }})</strong>
          <input
            type="text"
            class="mc-input"
            [ngModel]="directoryQuery()"
            (ngModelChange)="directoryQuery.set($event)"
            placeholder="Filter groups..."
            aria-label="Filter Existing Groups"
          />
        </div>

        <ul class="mc-directory-list">
          @for (grp of filteredGroups(); track grp.id) {
            <li
              class="mc-directory-row"
              [class.mc-directory-row-active]="editingGroup()?.id === grp.id"
              (click)="selectGroupForAlter(grp)"
            >
              <div class="mc-directory-row-main">
                <span class="mc-directory-name">{{ grp.name }}</span>
                <span class="mc-directory-sub">
                  {{ grp.parentGroupName ?? (grp.parentGroupId ? grp.nature : 'Primary • ' + grp.nature) }}
                </span>
              </div>
              @if (grp.isSystemGroup) {
                <span class="mc-badge mc-badge-slate">System</span>
              } @else {
                <span class="mc-badge mc-badge-jade">Custom</span>
              }
            </li>
          }
        </ul>
      </aside>
    </div>
  `,
  styles: `
    .mc-workbench-split {
      display: grid;
      grid-template-columns: minmax(0, 1.55fr) minmax(280px, 0.85fr);
      gap: var(--mc-space-4);
      align-items: start;
    }

    @media (max-width: 1024px) {
      .mc-workbench-split {
        grid-template-columns: 1fr;
      }
    }

    .mc-workbench-form-card {
      padding: var(--mc-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-workbench-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-2);
      padding-bottom: var(--mc-space-2);
      border-bottom: var(--mc-border-subtle);
      flex-wrap: wrap;
    }

    .mc-workbench-title-group {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      flex-wrap: wrap;

      h1 {
        margin: 0;
        font-size: var(--mc-font-size-lg);
      }
    }

    .mc-workbench-header-btns {
      display: flex;
      gap: var(--mc-space-2);
    }

    .mc-workbench-status {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-teal-50);
      border-left: 3px solid var(--mc-color-verdigris-teal);
      color: var(--mc-color-teal-700);
      font-weight: 600;
      font-size: var(--mc-font-size-xs);
    }

    .mc-workbench-err {
      padding: var(--mc-space-2);
      background: var(--mc-color-danger-50);
      border-left: 3px solid var(--mc-color-danger-600);
    }

    .mc-wb-grid-2 {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    .mc-wb-grid-3 {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    @media (max-width: 780px) {
      .mc-wb-grid-2,
      .mc-wb-grid-3 {
        grid-template-columns: 1fr;
      }
    }

    .mc-wb-subpanel {
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-sm);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-wb-subpanel-title {
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--mc-color-slate-700);
    }

    .mc-wb-toggles-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-2);
    }

    .mc-wb-toggle {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      font-weight: 500;
      cursor: pointer;
    }

    .mc-workbench-footer {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-top: var(--mc-space-3);
      border-top: var(--mc-border-subtle);
      gap: var(--mc-space-2);
    }

    .mc-footer-right {
      display: flex;
      align-items: center;
      gap: var(--mc-space-3);
    }

    .mc-footer-hint {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-500);
      font-weight: 500;
    }

    .mc-workbench-list-card {
      overflow: hidden;
      display: flex;
      flex-direction: column;
      max-height: calc(100vh - 160px);
    }

    .mc-list-head {
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border-bottom: var(--mc-border-subtle);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-directory-list {
      list-style: none;
      margin: 0;
      padding: 0;
      overflow-y: auto;
    }

    .mc-directory-row {
      padding: 0.45rem 0.75rem;
      border-bottom: 1px solid var(--mc-color-slate-100);
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-2);
      cursor: pointer;

      &:hover {
        background: var(--mc-color-row-hover);
      }
    }

    .mc-directory-row-active {
      background: var(--mc-color-teal-50);
      border-left: 3px solid var(--mc-color-verdigris-teal);
    }

    .mc-directory-row-main {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .mc-directory-name {
      font-weight: 600;
      color: var(--mc-color-slate-900);
    }

    .mc-directory-sub {
      font-size: 11px;
      color: var(--mc-color-slate-600);
    }
  `,
})
export class GroupWorkbenchComponent implements OnInit {
  protected readonly Boolean = Boolean;

  readonly coaStore = inject(ChartOfAccountsStore);
  readonly companyContext = inject(CompanyContextStore);

  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly secondaryMasterModal = inject(SecondaryMasterModalService);
  private readonly editLogModal = inject(EditLogModalService);
  private readonly navStack = inject(NavigationStackStore);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  readonly embeddedOverlay = input<boolean>(false);
  readonly initialGroupId = input<string | null>(null);

  private readonly nameInputRef = viewChild<ElementRef<HTMLInputElement>>('nameInput');

  readonly editingGroup = signal<AccountGroup | null>(null);
  readonly saving = signal<boolean>(false);
  readonly statusMessage = signal<string | null>(null);
  readonly formError = signal<string | null>(null);
  readonly directoryQuery = signal<string>('');

  readonly formName = signal<string>('');
  readonly formAlias = signal<string>('');
  readonly formParentGroupId = signal<string | null>(null);
  readonly formNature = signal<AccountNature>('ASSETS');
  readonly formAffectsGrossProfit = signal<boolean>(false);
  readonly formBehavesLikeSubledger = signal<boolean>(false);
  readonly formNetDebitCredit = signal<boolean>(false);
  readonly formUsedForCalculation = signal<boolean>(false);
  readonly formMethodToAllocate = signal<PurchaseAllocationMethod>('NOT_APPLICABLE');
  readonly formDefaultHsn = signal<string>('');
  readonly formDefaultGstRate = signal<string>('');

  readonly availableParentOptions = computed<readonly TypeaheadOption[]>(() => {
    const editingId = this.editingGroup()?.id;
    return this.coaStore
      .parentGroupTypeaheadOptions()
      .filter((o) => o.id !== editingId);
  });

  readonly filteredGroups = computed(() => {
    const q = this.directoryQuery().trim().toLowerCase();
    const list = this.coaStore.groups();
    if (!q) return list;
    return list.filter(
      (g) =>
        g.name.toLowerCase().includes(q) ||
        (g.aliasCode?.toLowerCase().includes(q) ?? false) ||
        g.nature.toLowerCase().includes(q),
    );
  });

  async ngOnInit(): Promise<void> {
    const scope = this.embeddedOverlay() ? 'MODAL' : 'SCREEN';
    const disposers = [
      this.shortcutRegistry.bindHandler(
        'screen.accept',
        scope,
        () => {
          void this.saveGroup();
        },
        () => !this.secondaryMasterModal.isOpen(),
      ),
      this.shortcutRegistry.bindHandler(
        'register.deleteVoucher',
        scope,
        () => {
          void this.deleteCurrentGroup();
        },
        () => Boolean(this.editingGroup() && !this.editingGroup()!.isSystemGroup),
      ),
      this.shortcutRegistry.bindHandler(
        'audit.editLog',
        scope,
        () => {
          const grp = this.editingGroup();
          if (grp) {
            this.openGroupEditLog(grp);
          }
        },
        () => Boolean(this.editingGroup()),
      ),
      this.navStack.registerDraftLifecycleHooks(
        () => ({
          name: this.formName(),
          alias: this.formAlias(),
          parentGroupId: this.formParentGroupId(),
          nature: this.formNature(),
        }),
        (snap) => {
          const s = snap as {
            name?: string;
            alias?: string;
            parentGroupId?: string | null;
            nature?: AccountNature;
          };
          if (typeof s.name === 'string') this.formName.set(s.name);
          if (typeof s.alias === 'string') this.formAlias.set(s.alias);
          if (s.parentGroupId !== undefined) this.formParentGroupId.set(s.parentGroupId);
          if (s.nature) this.formNature.set(s.nature);
        },
      ),
    ];

    this.destroyRef.onDestroy(() => {
      for (const d of disposers) d();
      this.companyContext.setHasUnsavedDraft(false);
    });

    await this.coaStore.ensureAccountingMastersLoaded();

    const targetId = this.initialGroupId() ?? this.route.snapshot.queryParamMap.get('id');
    if (targetId) {
      const found = this.coaStore.groups().find((g) => g.id === targetId);
      if (found) {
        this.selectGroupForAlter(found);
        return;
      }
    }

    const mode = this.route.snapshot.queryParamMap.get('mode');
    if (mode === 'alter' && this.coaStore.groups().length > 0) {
      this.selectGroupForAlter(this.coaStore.groups()[0]);
      return;
    }

    this.startCreateGroup();
  }

  openGroupEditLog(group: AccountGroup): void {
    this.editLogModal.open({
      entityType: 'ACCOUNT_GROUP',
      entityId: group.id,
      entityLabel: group.name,
    });
  }

  onFieldEdit(field: 'name' | 'alias', value: string): void {
    if (field === 'name') {
      this.formName.set(value);
    } else {
      this.formAlias.set(value);
    }
    this.markDirty();
  }

  markDirty(): void {
    this.companyContext.setHasUnsavedDraft(true);
  }

  onParentSelected(opt: TypeaheadOption): void {
    if (opt.id === '__PRIMARY__') {
      this.formParentGroupId.set(null);
    } else {
      this.formParentGroupId.set(opt.id);
      const parent = this.coaStore.groups().find((g) => g.id === opt.id);
      if (parent) {
        this.formNature.set(parent.nature);
        this.formAffectsGrossProfit.set(parent.affectsGrossProfit);
      }
    }
    this.markDirty();
  }

  openInlineParentCreator(seedText: string): void {
    void this.secondaryMasterModal.open({
      kind: 'GROUP',
      initialName: seedText,
      onCreated: (created) => {
        this.onParentSelected({ id: created.id, label: created.name });
      },
    });
  }

  startCreateGroup(): void {
    this.editingGroup.set(null);
    this.formError.set(null);
    this.formName.set('');
    this.formAlias.set('');
    this.formParentGroupId.set(null);
    this.formNature.set('ASSETS');
    this.formAffectsGrossProfit.set(false);
    this.formBehavesLikeSubledger.set(false);
    this.formNetDebitCredit.set(false);
    this.formUsedForCalculation.set(false);
    this.formMethodToAllocate.set('NOT_APPLICABLE');
    this.formDefaultHsn.set('');
    this.formDefaultGstRate.set('');
    this.companyContext.setHasUnsavedDraft(false);

    queueMicrotask(() => {
      this.nameInputRef()?.nativeElement.focus();
    });
  }

  selectGroupForAlter(grp: AccountGroup): void {
    this.editingGroup.set(grp);
    this.formError.set(null);
    this.formName.set(grp.name);
    this.formAlias.set(grp.aliasCode ?? '');
    this.formParentGroupId.set(grp.parentGroupId);
    this.formNature.set(grp.nature);
    this.formAffectsGrossProfit.set(grp.affectsGrossProfit);
    this.formBehavesLikeSubledger.set(grp.behavesLikeSubledger);
    this.formNetDebitCredit.set(grp.netDebitCreditForReporting);
    this.formUsedForCalculation.set(grp.usedForCalculation);
    this.formMethodToAllocate.set(grp.methodToAllocatePurchase ?? 'NOT_APPLICABLE');
    this.formDefaultHsn.set(grp.defaultHsnSacCode ?? '');
    this.formDefaultGstRate.set(grp.defaultGstRatePct ?? '');
    this.companyContext.setHasUnsavedDraft(false);
  }

  async saveGroup(): Promise<void> {
    if (this.saving()) return;
    this.formError.set(null);
    this.statusMessage.set(null);

    const name = this.formName().trim();
    if (!name) {
      this.formError.set('Account Group Name is required.');
      return;
    }

    const parent = this.formParentGroupId()
      ? this.coaStore.groups().find((g) => g.id === this.formParentGroupId())
      : undefined;

    const payload: CreateOrUpdateAccountGroupPayload = {
      name,
      aliasCode: this.formAlias().trim() || null,
      parentGroupId: this.formParentGroupId(),
      nature: parent ? parent.nature : this.formNature(),
      affectsGrossProfit: parent ? parent.affectsGrossProfit : this.formAffectsGrossProfit(),
      behavesLikeSubledger: this.formBehavesLikeSubledger(),
      netDebitCreditForReporting: this.formNetDebitCredit(),
      usedForCalculation: this.formUsedForCalculation(),
      methodToAllocatePurchase: this.formMethodToAllocate(),
      defaultHsnSacCode: this.formDefaultHsn().trim() || null,
      defaultGstRatePct: this.formDefaultGstRate().trim() || null,
    };

    this.saving.set(true);
    try {
      const current = this.editingGroup();
      if (current) {
        const updated = await this.coaStore.updateGroup(current.id, payload, current.versionNo);
        this.selectGroupForAlter(updated);
        this.statusMessage.set(`Updated Account Group "${updated.name}".`);
      } else {
        const created = await this.coaStore.createGroup(payload);
        this.startCreateGroup();
        this.statusMessage.set(`Created Account Group "${created.name}".`);
      }
      this.companyContext.setHasUnsavedDraft(false);
    } catch (err: unknown) {
      this.formError.set(err instanceof Error ? err.message : 'Failed to save Account Group.');
    } finally {
      this.saving.set(false);
    }
  }

  async deleteCurrentGroup(): Promise<void> {
    const current = this.editingGroup();
    if (!current || current.isSystemGroup) {
      return;
    }
    this.formError.set(null);
    try {
      await this.coaStore.deleteGroup(current.id, current.versionNo);
      this.statusMessage.set(`Deleted custom group "${current.name}".`);
      this.startCreateGroup();
    } catch (err: unknown) {
      this.formError.set(err instanceof Error ? err.message : 'Unable to delete Account Group.');
    }
  }
}
