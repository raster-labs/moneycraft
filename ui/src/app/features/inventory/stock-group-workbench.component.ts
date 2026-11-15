import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { TypeaheadSelectComponent } from '../../shared/components/typeahead-select/typeahead-select.component';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { StockCategoryMaster, StockGroupMaster } from './inventory.models';
import { InventoryStore } from './inventory.store';

@Component({
  selector: 'mc-stock-group-workbench',
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
    <div class="mc-inv-wb-grid">
      <!-- Left List of Stock Groups / Categories -->
      <section class="mc-card mc-inv-list-card" aria-label="List of Stock Groups and Categories">
        <div class="mc-inv-card-head">
          <div class="mc-tab-row">
            <button
              type="button"
              class="mc-tab-pill"
              [class.mc-tab-pill-active]="activeTab() === 'GROUPS'"
              (click)="switchTab('GROUPS')"
            >
              Stock Groups ({{ inventoryStore.stockGroups().length }})
            </button>
            <button
              type="button"
              class="mc-tab-pill"
              [class.mc-tab-pill-active]="activeTab() === 'CATEGORIES'"
              (click)="switchTab('CATEGORIES')"
            >
              Categories ({{ inventoryStore.stockCategories().length }})
            </button>
          </div>
          <button type="button" class="mc-btn" (click)="resetToCreate()">+ New</button>
        </div>

        @if (activeTab() === 'GROUPS') {
          <ul class="mc-inv-list" role="listbox">
            @for (sg of inventoryStore.stockGroups(); track sg.id) {
              <li
                role="option"
                class="mc-inv-list-row"
                [class.mc-inv-list-row-active]="editingGroupId() === sg.id"
                [attr.aria-selected]="editingGroupId() === sg.id"
                (click)="selectGroup(sg)"
              >
                <div>
                  <strong>{{ sg.name }}</strong>
                  @if (sg.gstRatePct) {
                    <span class="mc-inv-sub"> &bull; GST {{ sg.gstRatePct }}%</span>
                  }
                </div>
                @if (sg.hsnSacCode) {
                  <mc-code-badge [code]="sg.hsnSacCode" />
                }
              </li>
            } @empty {
              <li class="mc-inv-empty">No Stock Groups defined yet.</li>
            }
          </ul>
        } @else {
          <ul class="mc-inv-list" role="listbox">
            @for (sc of inventoryStore.stockCategories(); track sc.id) {
              <li
                role="option"
                class="mc-inv-list-row"
                [class.mc-inv-list-row-active]="editingCategoryId() === sc.id"
                [attr.aria-selected]="editingCategoryId() === sc.id"
                (click)="selectCategory(sc)"
              >
                <strong>{{ sc.name }}</strong>
                @if (sc.aliasCode) {
                  <mc-code-badge [code]="sc.aliasCode" />
                }
              </li>
            } @empty {
              <li class="mc-inv-empty">No Stock Categories defined yet.</li>
            }
          </ul>
        }
      </section>

      <!-- Right Creation / Alteration Form -->
      <section class="mc-card mc-inv-form-card" aria-label="Stock Group or Category Form">
        <header class="mc-inv-card-head">
          <span>
            @if (activeTab() === 'GROUPS') {
              {{ editingGroupId() ? 'Stock Group Alteration' : 'Stock Group Creation' }}
            } @else {
              {{ editingCategoryId() ? 'Stock Category Alteration' : 'Stock Category Creation' }}
            }
          </span>
          @if (!embeddedOverlay()) {
            <a routerLink="/app/masters" class="mc-btn">Masters Hub</a>
          }
        </header>

        @if (errorMsg(); as err) {
          <div class="mc-inv-banner mc-inv-banner-err" role="alert">{{ err }}</div>
        }
        @if (savedMsg(); as ok) {
          <div class="mc-inv-banner mc-inv-banner-ok" role="status">{{ ok }}</div>
        }

        @if (activeTab() === 'GROUPS') {
          <form class="mc-inv-form" data-mc-nav-container (ngSubmit)="saveActiveForm()">
            <div class="mc-inv-row-2">
              <div>
                <label class="mc-field-label" for="sg-name">Stock Group Name</label>
                <input
                  #nameInput
                  id="sg-name"
                  type="text"
                  name="groupName"
                  class="mc-input"
                  mcGridCellNav
                  [(ngModel)]="groupName"
                  required
                />
              </div>
              <div>
                <label class="mc-field-label" for="sg-alias">Alias</label>
                <input
                  id="sg-alias"
                  type="text"
                  name="groupAlias"
                  class="mc-input mc-code"
                  mcGridCellNav
                  [(ngModel)]="groupAlias"
                />
              </div>
            </div>

            <div class="mc-inv-row-2">
              <div>
                <label class="mc-field-label" for="sg-under">Under</label>
                <mc-typeahead-select
                  inputId="sg-under"
                  listTitle="List of Stock Groups"
                  [options]="inventoryStore.parentStockGroupOptions()"
                  [selectedId]="parentStockGroupId() ?? '__PRIMARY__'"
                  [allowInlineCreate]="false"
                  (optionSelected)="parentStockGroupId.set($event.id === '__PRIMARY__' ? null : $event.id)"
                />
              </div>
              <label class="mc-inv-check-card">
                <input
                  type="checkbox"
                  name="shouldQuantitiesOfItemsBeAdded"
                  [(ngModel)]="shouldQuantitiesOfItemsBeAdded"
                />
                <span>Should quantities of items be added</span>
              </label>
            </div>

            <div class="mc-inv-row-3">
              <div>
                <label class="mc-field-label" for="sg-hsn">Inherited HSN / SAC Code</label>
                <input
                  id="sg-hsn"
                  type="text"
                  name="hsnSacCode"
                  class="mc-input mc-code"
                  mcGridCellNav
                  [(ngModel)]="hsnSacCode"
                  placeholder="9018"
                />
              </div>
              <div>
                <label class="mc-field-label" for="sg-gst">Inherited GST Rate (%)</label>
                <input
                  id="sg-gst"
                  type="text"
                  name="gstRatePct"
                  class="mc-input mc-amount"
                  mcGridCellNav
                  [(ngModel)]="gstRatePct"
                  placeholder="12"
                />
              </div>
              <div>
                <label class="mc-field-label" for="sg-cess">Compensation Cess (%)</label>
                <input
                  id="sg-cess"
                  type="text"
                  name="cessRatePct"
                  class="mc-input mc-amount"
                  mcGridCellNav
                  [(ngModel)]="cessRatePct"
                  placeholder="0"
                />
              </div>
            </div>

            <footer class="mc-inv-footer">
              <span class="mc-inv-hint">6-Tier GST Inheritance: Stock Group feeds child Stock Items</span>
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="stock-group-save-btn"
                [disabled]="saving() || companyContext.quarantined()"
                (click)="saveActiveForm()"
              >
                Accept (<span class="mc-key-ctrl">A</span>)
              </button>
            </footer>
          </form>
        } @else {
          <form class="mc-inv-form" data-mc-nav-container (ngSubmit)="saveActiveForm()">
            <div class="mc-inv-row-2">
              <div>
                <label class="mc-field-label" for="sc-name">Stock Category Name</label>
                <input
                  #nameInput
                  id="sc-name"
                  type="text"
                  name="categoryName"
                  class="mc-input"
                  mcGridCellNav
                  [(ngModel)]="categoryName"
                  required
                />
              </div>
              <div>
                <label class="mc-field-label" for="sc-alias">Alias</label>
                <input
                  id="sc-alias"
                  type="text"
                  name="categoryAlias"
                  class="mc-input mc-code"
                  mcGridCellNav
                  [(ngModel)]="categoryAlias"
                />
              </div>
            </div>

            <div>
              <label class="mc-field-label" for="sc-under">Under</label>
              <mc-typeahead-select
                inputId="sc-under"
                listTitle="List of Stock Categories"
                [options]="inventoryStore.parentStockCategoryOptions()"
                [selectedId]="parentStockCategoryId() ?? '__PRIMARY__'"
                [allowInlineCreate]="false"
                (optionSelected)="parentStockCategoryId.set($event.id === '__PRIMARY__' ? null : $event.id)"
              />
            </div>

            <footer class="mc-inv-footer">
              <span class="mc-inv-hint">Parallel Stock Category reporting dimension</span>
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                [disabled]="saving() || companyContext.quarantined()"
                (click)="saveActiveForm()"
              >
                Accept (<span class="mc-key-ctrl">A</span>)
              </button>
            </footer>
          </form>
        }
      </section>
    </div>
  `,
  styles: `
    .mc-inv-wb-grid {
      display: grid;
      grid-template-columns: minmax(280px, 360px) minmax(0, 1fr);
      gap: var(--mc-space-4);
      align-items: start;
    }

    @media (max-width: 920px) {
      .mc-inv-wb-grid {
        grid-template-columns: 1fr;
      }
    }

    .mc-inv-card-head {
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      padding: 0.5rem 0.875rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-weight: 700;
      gap: var(--mc-space-2);
    }

    .mc-tab-row {
      display: flex;
      gap: 4px;
    }

    .mc-tab-pill {
      background: rgba(255, 255, 255, 0.12);
      border: 1px solid rgba(255, 255, 255, 0.24);
      color: var(--mc-color-surface);
      border-radius: var(--mc-radius-xs);
      padding: 0.14rem 0.5rem;
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
      cursor: pointer;
    }

    .mc-tab-pill-active {
      background: var(--mc-color-verdigris-teal);
      border-color: var(--mc-color-mint-signal);
    }

    .mc-inv-list-card,
    .mc-inv-form-card {
      overflow: hidden;
    }

    .mc-inv-list {
      list-style: none;
      margin: 0;
      padding: 0;
      max-height: 62vh;
      overflow-y: auto;
    }

    .mc-inv-list-row {
      padding: 0.45rem 0.85rem;
      border-bottom: 1px solid var(--mc-color-slate-100);
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-2);
      cursor: pointer;
    }

    .mc-inv-list-row-active {
      background: var(--mc-color-teal-50);
    }

    .mc-inv-sub {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
    }

    .mc-inv-empty {
      padding: var(--mc-space-4);
      color: var(--mc-color-slate-600);
      font-size: var(--mc-font-size-xs);
      text-align: center;
    }

    .mc-inv-banner {
      margin: var(--mc-space-3) var(--mc-space-4) 0;
      padding: var(--mc-space-2) var(--mc-space-3);
      border-radius: var(--mc-radius-xs);
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
    }

    .mc-inv-banner-err {
      background: #fef2f2;
      color: #991b1b;
      border: 1px solid #fecaca;
    }

    .mc-inv-banner-ok {
      background: var(--mc-color-teal-50);
      color: var(--mc-color-pressed-teal);
      border: 1px solid var(--mc-color-teal-100);
    }

    .mc-inv-form {
      padding: var(--mc-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-inv-row-2 {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-3);
      align-items: end;
    }

    .mc-inv-row-3 {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    .mc-inv-check-card {
      padding: 0.45rem 0.65rem;
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      font-weight: 500;
      cursor: pointer;
    }

    .mc-inv-footer {
      border-top: var(--mc-border-subtle);
      padding-top: var(--mc-space-3);
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .mc-inv-hint {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }
  `,
})
export class StockGroupWorkbenchComponent implements OnInit {
  readonly embeddedOverlay = input<boolean>(false);

  readonly inventoryStore = inject(InventoryStore);
  readonly companyContext = inject(CompanyContextStore);
  private readonly route = inject(ActivatedRoute);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly navStack = inject(NavigationStackStore);
  private readonly destroyRef = inject(DestroyRef);

  private readonly nameInputRef = viewChild<ElementRef<HTMLInputElement>>('nameInput');

  readonly activeTab = signal<'GROUPS' | 'CATEGORIES'>('GROUPS');
  readonly editingGroupId = signal<string | null>(null);
  readonly editingCategoryId = signal<string | null>(null);
  readonly editingVersionNo = signal<number>(1);
  readonly parentStockGroupId = signal<string | null>(null);
  readonly parentStockCategoryId = signal<string | null>(null);
  readonly saving = signal<boolean>(false);
  readonly errorMsg = signal<string | null>(null);
  readonly savedMsg = signal<string | null>(null);

  groupName = '';
  groupAlias = '';
  shouldQuantitiesOfItemsBeAdded = true;
  hsnSacCode = '';
  gstRatePct = '';
  cessRatePct = '';

  categoryName = '';
  categoryAlias = '';

  constructor() {
    effect(() => {
      const cid = this.companyContext.activeCompany()?.id;
      if (cid) void this.inventoryStore.ensureLoaded(cid);
    });
  }

  ngOnInit(): void {
    void this.inventoryStore.ensureLoaded();
    if (this.route.snapshot.queryParamMap.get('tab') === 'categories') {
      this.activeTab.set('CATEGORIES');
    }

    const dispose = this.shortcutRegistry.bindHandler(
      'screen.accept',
      'SCREEN',
      () => {
        void this.saveActiveForm();
      },
      () => !this.navStack.hasOverlays() || this.embeddedOverlay(),
    );
    this.destroyRef.onDestroy(dispose);
  }

  switchTab(tab: 'GROUPS' | 'CATEGORIES'): void {
    this.activeTab.set(tab);
    this.resetToCreate();
  }

  resetToCreate(): void {
    this.editingGroupId.set(null);
    this.editingCategoryId.set(null);
    this.editingVersionNo.set(1);
    this.groupName = '';
    this.groupAlias = '';
    this.parentStockGroupId.set(null);
    this.shouldQuantitiesOfItemsBeAdded = true;
    this.hsnSacCode = '';
    this.gstRatePct = '';
    this.cessRatePct = '';
    this.categoryName = '';
    this.categoryAlias = '';
    this.parentStockCategoryId.set(null);
    this.errorMsg.set(null);
    queueMicrotask(() => this.nameInputRef()?.nativeElement.focus());
  }

  selectGroup(sg: StockGroupMaster): void {
    this.activeTab.set('GROUPS');
    this.editingGroupId.set(sg.id);
    this.editingVersionNo.set(sg.versionNo);
    this.groupName = sg.name;
    this.groupAlias = sg.aliasCode ?? '';
    this.parentStockGroupId.set(sg.parentStockGroupId);
    this.shouldQuantitiesOfItemsBeAdded = sg.shouldQuantitiesOfItemsBeAdded;
    this.hsnSacCode = sg.hsnSacCode ?? '';
    this.gstRatePct = sg.gstRatePct ?? '';
    this.cessRatePct = sg.cessRatePct ?? '';
    this.errorMsg.set(null);
    this.savedMsg.set(null);
  }

  selectCategory(sc: StockCategoryMaster): void {
    this.activeTab.set('CATEGORIES');
    this.editingCategoryId.set(sc.id);
    this.editingVersionNo.set(sc.versionNo);
    this.categoryName = sc.name;
    this.categoryAlias = sc.aliasCode ?? '';
    this.parentStockCategoryId.set(sc.parentStockCategoryId);
    this.errorMsg.set(null);
    this.savedMsg.set(null);
  }

  async saveActiveForm(): Promise<void> {
    if (this.saving()) return;
    this.saving.set(true);
    this.errorMsg.set(null);
    this.savedMsg.set(null);

    try {
      if (this.activeTab() === 'GROUPS') {
        const cleanName = this.groupName.trim();
        if (!cleanName) {
          this.errorMsg.set('Stock Group Name is required.');
          return;
        }
        const payload = {
          name: cleanName,
          aliasCode: this.groupAlias.trim() || null,
          parentStockGroupId: this.parentStockGroupId(),
          shouldQuantitiesOfItemsBeAdded: this.shouldQuantitiesOfItemsBeAdded,
          hsnSacCode: this.hsnSacCode.trim() || null,
          gstRatePct: this.gstRatePct.trim() || null,
          cessRatePct: this.cessRatePct.trim() || null,
        };
        const id = this.editingGroupId();
        if (id) {
          const updated = await this.inventoryStore.updateStockGroup(
            id,
            payload,
            this.editingVersionNo(),
          );
          this.editingVersionNo.set(updated.versionNo);
          this.savedMsg.set(`Updated Stock Group "${updated.name}".`);
        } else {
          const created = await this.inventoryStore.createStockGroup(payload);
          this.resetToCreate();
          this.savedMsg.set(`Created Stock Group "${created.name}".`);
        }
      } else {
        const cleanName = this.categoryName.trim();
        if (!cleanName) {
          this.errorMsg.set('Stock Category Name is required.');
          return;
        }
        const payload = {
          name: cleanName,
          aliasCode: this.categoryAlias.trim() || null,
          parentStockCategoryId: this.parentStockCategoryId(),
        };
        const id = this.editingCategoryId();
        if (id) {
          const updated = await this.inventoryStore.updateStockCategory(
            id,
            payload,
            this.editingVersionNo(),
          );
          this.editingVersionNo.set(updated.versionNo);
          this.savedMsg.set(`Updated Stock Category "${updated.name}".`);
        } else {
          const created = await this.inventoryStore.createStockCategory(payload);
          this.resetToCreate();
          this.savedMsg.set(`Created Stock Category "${created.name}".`);
        }
      }
    } catch (err: unknown) {
      this.errorMsg.set(err instanceof Error ? err.message : 'Unable to save master.');
    } finally {
      this.saving.set(false);
    }
  }
}
