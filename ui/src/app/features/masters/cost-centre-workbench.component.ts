import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { TypeaheadSelectComponent } from '../../shared/components/typeahead-select/typeahead-select.component';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';
import { CompanyContextStore } from '../../state/company-context.store';
import { ChartOfAccountsStore } from './chart-of-accounts.store';
import { CostCategory, CostCentre } from './masters.models';
import { SecondaryMasterModalService } from './secondary-master-modal.service';

@Component({
  selector: 'mc-cost-centre-workbench',
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
    <div class="mc-cc-layout">
      <div class="mc-cc-header">
        <div>
          <h1>Cost Categories &amp; Cost Centres</h1>
          <p class="mc-cc-sub">
            Parallel Revenue &amp; Non-Revenue allocation categories and hierarchical Cost Centres.
          </p>
        </div>
        <div class="mc-cc-tabs">
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'centres'"
            (click)="activeTab.set('centres')"
          >
            Cost Centres
          </button>
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'categories'"
            (click)="activeTab.set('categories')"
          >
            Cost Categories
          </button>
          <a routerLink="/app/masters" class="mc-btn">Masters Hub</a>
        </div>
      </div>

      @if (statusMessage(); as msg) {
        <div class="mc-cc-status" role="status">{{ msg }}</div>
      }
      @if (formError(); as err) {
        <div class="mc-field-error mc-cc-err" role="alert">{{ err }}</div>
      }

      @if (activeTab() === 'centres') {
        <div class="mc-cc-split">
          <form
            class="mc-card mc-cc-form-card"
            data-mc-nav-container
            (ngSubmit)="saveCostCentre()"
          >
            <div class="mc-cc-card-head">
              <strong>{{ editingCentre() ? 'Cost Centre Alteration' : 'Cost Centre Creation' }}</strong>
              <button type="button" class="mc-btn" (click)="startCreateCentre()">+ New Centre</button>
            </div>

            <div class="mc-cc-grid-2">
              <div>
                <label class="mc-field-label" for="cc-category">Category</label>
                <mc-typeahead-select
                  inputId="cc-category"
                  listTitle="List of Cost Categories"
                  [options]="coaStore.costCategoryTypeaheadOptions()"
                  [selectedId]="centreCategoryId()"
                  [allowInlineCreate]="false"
                  (optionSelected)="centreCategoryId.set($event.id)"
                />
              </div>

              <div>
                <label class="mc-field-label" for="cc-under">
                  Under (<span class="mc-key-alt">C</span> to create Parent Cost Centre)
                </label>
                <mc-typeahead-select
                  inputId="cc-under"
                  listTitle="List of Cost Centres"
                  [options]="coaStore.costCentreTypeaheadOptions()"
                  [selectedId]="centreParentId() ?? '__PRIMARY__'"
                  [allowInlineCreate]="true"
                  (optionSelected)="centreParentId.set($event.id === '__PRIMARY__' ? null : $event.id)"
                  (createRequested)="openInlineParentCentre($event)"
                />
              </div>
            </div>

            <div class="mc-cc-grid-2">
              <div>
                <label class="mc-field-label" for="cc-name">Name</label>
                <input
                  id="cc-name"
                  type="text"
                  name="centreName"
                  class="mc-input"
                  mcGridCellNav
                  [(ngModel)]="centreName"
                  required
                />
              </div>
              <div>
                <label class="mc-field-label" for="cc-alias">(Alias)</label>
                <input
                  id="cc-alias"
                  type="text"
                  name="centreAlias"
                  class="mc-input mc-code"
                  mcGridCellNav
                  [(ngModel)]="centreAlias"
                />
              </div>
            </div>

            <div class="mc-cc-footer">
              <div>
                @if (editingCentre()) {
                  <button
                    type="button"
                    class="mc-btn mc-btn-danger"
                    [disabled]="companyContext.quarantined()"
                    (click)="deleteCurrentCentre()"
                  >
                    <span class="mc-key-alt">D</span>: Delete
                  </button>
                }
              </div>
              <button
                type="submit"
                class="mc-btn mc-btn-primary"
                [disabled]="saving() || companyContext.quarantined()"
              >
                Accept (<span class="mc-key-ctrl">A</span>)
              </button>
            </div>
          </form>

          <aside class="mc-card mc-cc-list-card" aria-label="Cost Centres Directory">
            <div class="mc-cc-list-head">Cost Centres ({{ coaStore.costCentres().length }})</div>
            <ul class="mc-cc-list">
              @for (cc of coaStore.costCentres(); track cc.id) {
                <li
                  class="mc-cc-row"
                  [class.mc-cc-row-active]="editingCentre()?.id === cc.id"
                  (click)="selectCentreForAlter(cc)"
                >
                  <div>
                    <strong>{{ cc.name }}</strong>
                    @if (cc.aliasCode) {
                      <mc-code-badge [code]="cc.aliasCode" />
                    }
                  </div>
                  <span class="mc-badge mc-badge-slate">Depth {{ cc.depthLevel }}</span>
                </li>
              } @empty {
                <li class="mc-cc-empty">No Cost Centres created yet.</li>
              }
            </ul>
          </aside>
        </div>
      }

      @if (activeTab() === 'categories') {
        <div class="mc-cc-split">
          <form
            class="mc-card mc-cc-form-card"
            data-mc-nav-container
            (ngSubmit)="saveCostCategory()"
          >
            <div class="mc-cc-card-head">
              <strong>{{ editingCategory() ? 'Cost Category Alteration' : 'Cost Category Creation' }}</strong>
              @if (editingCategory()?.isPrimaryCategory) {
                <span class="mc-badge mc-badge-slate">Primary Category</span>
              }
              <button type="button" class="mc-btn" (click)="startCreateCategory()">+ New Category</button>
            </div>

            <div>
              <label class="mc-field-label" for="cat-name">Category Name</label>
              <input
                id="cat-name"
                type="text"
                name="categoryName"
                class="mc-input"
                mcGridCellNav
                [readOnly]="Boolean(editingCategory()?.isPrimaryCategory)"
                [(ngModel)]="categoryName"
                required
              />
            </div>

            <div class="mc-cc-toggles">
              <label class="mc-cc-toggle">
                <input type="checkbox" name="allocRev" [(ngModel)]="categoryAllocRevenue" />
                <span>Allocate Revenue Items (P&amp;L Incomes &amp; Expenses)</span>
              </label>
              <label class="mc-cc-toggle">
                <input type="checkbox" name="allocNonRev" [(ngModel)]="categoryAllocNonRevenue" />
                <span>Allocate Non-Revenue Items (Balance Sheet Capital / Asset Accounts)</span>
              </label>
            </div>

            <div class="mc-cc-footer">
              <span>At least one allocation scope must be enabled</span>
              <button
                type="submit"
                class="mc-btn mc-btn-primary"
                [disabled]="saving() || companyContext.quarantined()"
              >
                Accept (<span class="mc-key-ctrl">A</span>)
              </button>
            </div>
          </form>

          <aside class="mc-card mc-cc-list-card" aria-label="Cost Categories Directory">
            <div class="mc-cc-list-head">Cost Categories ({{ coaStore.costCategories().length }})</div>
            <ul class="mc-cc-list">
              @for (cat of coaStore.costCategories(); track cat.id) {
                <li
                  class="mc-cc-row"
                  [class.mc-cc-row-active]="editingCategory()?.id === cat.id"
                  (click)="selectCategoryForAlter(cat)"
                >
                  <strong>{{ cat.name }}</strong>
                  <span class="mc-badge mc-badge-jade">
                    {{ cat.allocateRevenueItems ? 'Rev' : '' }}{{ cat.allocateNonRevenueItems ? ' + Non-Rev' : '' }}
                  </span>
                </li>
              } @empty {
                <li class="mc-cc-empty">No Cost Categories loaded.</li>
              }
            </ul>
          </aside>
        </div>
      }
    </div>
  `,
  styles: `
    .mc-cc-layout {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-cc-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: var(--mc-space-3);
      flex-wrap: wrap;

      h1 {
        margin: 0;
        font-size: var(--mc-font-size-lg);
      }
    }

    .mc-cc-sub {
      margin: 4px 0 0;
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
    }

    .mc-cc-tabs {
      display: flex;
      gap: var(--mc-space-2);
    }

    .mc-cc-status {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-teal-50);
      border-left: 3px solid var(--mc-color-verdigris-teal);
      color: var(--mc-color-teal-700);
      font-weight: 600;
      font-size: var(--mc-font-size-xs);
    }

    .mc-cc-err {
      padding: var(--mc-space-2);
      background: var(--mc-color-danger-50);
      border-left: 3px solid var(--mc-color-danger-600);
    }

    .mc-cc-split {
      display: grid;
      grid-template-columns: minmax(0, 1.4fr) minmax(280px, 0.9fr);
      gap: var(--mc-space-4);
      align-items: start;
    }

    @media (max-width: 960px) {
      .mc-cc-split {
        grid-template-columns: 1fr;
      }
    }

    .mc-cc-form-card {
      padding: var(--mc-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-cc-card-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-bottom: var(--mc-space-2);
      border-bottom: var(--mc-border-subtle);
    }

    .mc-cc-grid-2 {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    .mc-cc-toggles {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-cc-toggle {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      font-weight: 500;
      cursor: pointer;
    }

    .mc-cc-footer {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-top: var(--mc-space-2);
      border-top: var(--mc-border-subtle);
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-500);
    }

    .mc-cc-list-card {
      overflow: hidden;
    }

    .mc-cc-list-head {
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border-bottom: var(--mc-border-subtle);
      font-weight: 700;
    }

    .mc-cc-list {
      list-style: none;
      margin: 0;
      padding: 0;
    }

    .mc-cc-row {
      padding: 0.5rem 0.75rem;
      border-bottom: 1px solid var(--mc-color-slate-100);
      display: flex;
      justify-content: space-between;
      align-items: center;
      cursor: pointer;
    }

    .mc-cc-row-active {
      background: var(--mc-color-teal-50);
    }

    .mc-cc-empty {
      padding: var(--mc-space-4);
      text-align: center;
      color: var(--mc-color-slate-500);
    }
  `,
})
export class CostCentreWorkbenchComponent implements OnInit {
  protected readonly Boolean = Boolean;

  readonly coaStore = inject(ChartOfAccountsStore);
  readonly companyContext = inject(CompanyContextStore);

  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly secondaryMasterModal = inject(SecondaryMasterModalService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  readonly embeddedOverlay = input<boolean>(false);

  readonly activeTab = signal<'centres' | 'categories'>('centres');
  readonly editingCentre = signal<CostCentre | null>(null);
  readonly editingCategory = signal<CostCategory | null>(null);
  readonly saving = signal<boolean>(false);
  readonly statusMessage = signal<string | null>(null);
  readonly formError = signal<string | null>(null);

  readonly centreCategoryId = signal<string | null>(null);
  readonly centreParentId = signal<string | null>(null);
  centreName = '';
  centreAlias = '';

  categoryName = '';
  categoryAllocRevenue = true;
  categoryAllocNonRevenue = false;

  async ngOnInit(): Promise<void> {
    const scope = this.embeddedOverlay() ? 'MODAL' : 'SCREEN';
    const disposers = [
      this.shortcutRegistry.bindHandler(
        'screen.accept',
        scope,
        () => {
          if (this.activeTab() === 'categories') {
            void this.saveCostCategory();
          } else {
            void this.saveCostCentre();
          }
        },
        () => !this.secondaryMasterModal.isOpen(),
      ),
      this.shortcutRegistry.bindHandler(
        'register.deleteVoucher',
        scope,
        () => {
          void this.deleteCurrentCentre();
        },
        () => this.activeTab() === 'centres' && this.editingCentre() !== null,
      ),
    ];
    this.destroyRef.onDestroy(() => {
      for (const d of disposers) d();
    });

    if (this.route.snapshot.queryParamMap.get('tab') === 'categories') {
      this.activeTab.set('categories');
    }

    await this.coaStore.loadCostCentresAndCategories().catch(() => undefined);
    const primaryCat =
      this.coaStore.costCategories().find((c) => c.isPrimaryCategory) ??
      this.coaStore.costCategories()[0];
    if (primaryCat) {
      this.centreCategoryId.set(primaryCat.id);
    }
  }

  openInlineParentCentre(seedText: string): void {
    void this.secondaryMasterModal.open({
      kind: 'COST_CENTRE',
      initialName: seedText,
      defaultCostCategoryId: this.centreCategoryId(),
      onCreated: (created) => {
        this.centreParentId.set(created.id);
      },
    });
  }

  startCreateCentre(): void {
    this.editingCentre.set(null);
    this.formError.set(null);
    this.centreName = '';
    this.centreAlias = '';
    this.centreParentId.set(null);
  }

  selectCentreForAlter(cc: CostCentre): void {
    this.editingCentre.set(cc);
    this.formError.set(null);
    this.centreCategoryId.set(cc.costCategoryId);
    this.centreParentId.set(cc.parentCostCentreId);
    this.centreName = cc.name;
    this.centreAlias = cc.aliasCode ?? '';
  }

  startCreateCategory(): void {
    this.editingCategory.set(null);
    this.formError.set(null);
    this.categoryName = '';
    this.categoryAllocRevenue = true;
    this.categoryAllocNonRevenue = false;
  }

  selectCategoryForAlter(cat: CostCategory): void {
    this.editingCategory.set(cat);
    this.formError.set(null);
    this.categoryName = cat.name;
    this.categoryAllocRevenue = cat.allocateRevenueItems;
    this.categoryAllocNonRevenue = cat.allocateNonRevenueItems;
  }

  async saveCostCentre(): Promise<void> {
    if (this.saving()) return;
    this.formError.set(null);
    const catId = this.centreCategoryId();
    if (!catId || !this.centreName.trim()) {
      this.formError.set('Cost Category and Cost Centre Name are required.');
      return;
    }

    this.saving.set(true);
    try {
      const payload = {
        costCategoryId: catId,
        parentCostCentreId: this.centreParentId(),
        name: this.centreName.trim(),
        aliasCode: this.centreAlias.trim() || null,
        active: true,
      };
      const editing = this.editingCentre();
      if (editing) {
        const updated = await this.coaStore.updateCostCentre(editing.id, payload, editing.versionNo);
        this.selectCentreForAlter(updated);
        this.statusMessage.set(`Updated Cost Centre "${updated.name}".`);
      } else {
        const created = await this.coaStore.createCostCentre(payload);
        this.startCreateCentre();
        this.statusMessage.set(`Created Cost Centre "${created.name}".`);
      }
    } catch (err: unknown) {
      this.formError.set(err instanceof Error ? err.message : 'Failed to save Cost Centre.');
    } finally {
      this.saving.set(false);
    }
  }

  async deleteCurrentCentre(): Promise<void> {
    const current = this.editingCentre();
    if (!current) return;
    try {
      await this.coaStore.deleteCostCentre(current.id, current.versionNo);
      this.statusMessage.set(`Deleted Cost Centre "${current.name}".`);
      this.startCreateCentre();
    } catch (err: unknown) {
      this.formError.set(err instanceof Error ? err.message : 'Unable to delete Cost Centre.');
    }
  }

  async saveCostCategory(): Promise<void> {
    if (this.saving()) return;
    this.formError.set(null);
    if (!this.categoryName.trim()) {
      this.formError.set('Category Name is required.');
      return;
    }
    if (!this.categoryAllocRevenue && !this.categoryAllocNonRevenue) {
      this.formError.set('Enable at least Revenue or Non-Revenue allocation.');
      return;
    }

    this.saving.set(true);
    try {
      const payload = {
        name: this.categoryName.trim(),
        allocateRevenueItems: this.categoryAllocRevenue,
        allocateNonRevenueItems: this.categoryAllocNonRevenue,
      };
      const editing = this.editingCategory();
      if (editing) {
        const updated = await this.coaStore.updateCostCategory(
          editing.id,
          payload,
          editing.versionNo,
        );
        this.selectCategoryForAlter(updated);
        this.statusMessage.set(`Updated Cost Category "${updated.name}".`);
      } else {
        const created = await this.coaStore.createCostCategory(payload);
        this.centreCategoryId.set(created.id);
        this.startCreateCategory();
        this.statusMessage.set(`Created Cost Category "${created.name}".`);
      }
    } catch (err: unknown) {
      this.formError.set(err instanceof Error ? err.message : 'Failed to save Cost Category.');
    } finally {
      this.saving.set(false);
    }
  }
}
