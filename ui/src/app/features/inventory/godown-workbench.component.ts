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
import { RouterLink } from '@angular/router';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { TypeaheadSelectComponent } from '../../shared/components/typeahead-select/typeahead-select.component';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { GodownMaster } from './inventory.models';
import { InventoryStore } from './inventory.store';

@Component({
  selector: 'mc-godown-workbench',
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
      <!-- Left Hierarchical Godowns List -->
      <section class="mc-card mc-inv-list-card" aria-label="List of Godowns / Locations">
        <div class="mc-inv-card-head">
          <span>Godowns / Locations ({{ inventoryStore.godowns().length }})</span>
          <button type="button" class="mc-btn" (click)="resetToCreate()">+ New Godown</button>
        </div>

        <ul class="mc-inv-list" role="listbox">
          @for (g of inventoryStore.godowns(); track g.id) {
            <li
              role="option"
              class="mc-inv-list-row"
              [class.mc-inv-list-row-active]="editingId() === g.id"
              [attr.aria-selected]="editingId() === g.id"
              (click)="selectForAlter(g)"
            >
              <div>
                <strong>{{ g.name }}</strong>
                @if (g.parentGodownName) {
                  <span class="mc-inv-sub"> &bull; Under {{ g.parentGodownName }}</span>
                }
              </div>
              @if (g.isSystemGodown) {
                <span class="mc-badge mc-badge-jade">System Main</span>
              } @else if (g.aliasCode) {
                <mc-code-badge [code]="g.aliasCode" />
              }
            </li>
          } @empty {
            <li class="mc-inv-empty">Main Location + custom warehouses/bins appear here.</li>
          }
        </ul>
      </section>

      <!-- Right Creation / Alteration Form -->
      <section class="mc-card mc-inv-form-card" aria-label="Godown Creation / Alteration">
        <header class="mc-inv-card-head">
          <span>{{ editingId() ? 'Godown Alteration' : 'Godown Creation' }}</span>
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

        <form class="mc-inv-form" data-mc-nav-container (ngSubmit)="saveGodown()">
          <div class="mc-inv-row-2">
            <div>
              <label class="mc-field-label" for="gd-name">Godown / Location Name</label>
              <input
                #nameInput
                id="gd-name"
                type="text"
                name="name"
                class="mc-input"
                mcGridCellNav
                [(ngModel)]="name"
                required
              />
            </div>

            <div>
              <label class="mc-field-label" for="gd-alias">Alias</label>
              <input
                id="gd-alias"
                type="text"
                name="aliasCode"
                class="mc-input mc-code"
                mcGridCellNav
                [(ngModel)]="aliasCode"
              />
            </div>
          </div>

          <div class="mc-inv-row-2">
            <div>
              <label class="mc-field-label" for="gd-under">Under</label>
              <mc-typeahead-select
                inputId="gd-under"
                listTitle="List of Godowns"
                [options]="inventoryStore.parentGodownOptions()"
                [selectedId]="parentGodownId() ?? '__PRIMARY__'"
                [allowInlineCreate]="false"
                (optionSelected)="parentGodownId.set($event.id === '__PRIMARY__' ? null : $event.id)"
              />
            </div>

            <div>
              <label class="mc-field-label" for="gd-addr">Address (Optional)</label>
              <input
                id="gd-addr"
                type="text"
                name="address"
                class="mc-input"
                mcGridCellNav
                [(ngModel)]="address"
                placeholder="Warehouse / Bin physical address"
              />
            </div>
          </div>

          <div class="mc-inv-checks">
            <label class="mc-inv-check">
              <input
                type="checkbox"
                name="allowStorageOfMaterials"
                [(ngModel)]="allowStorageOfMaterials"
              />
              <span>Allow storage of materials</span>
            </label>
            <label class="mc-inv-check">
              <input
                type="checkbox"
                name="ourStockWithThirdParty"
                [(ngModel)]="ourStockWithThirdParty"
              />
              <span>Our stock with third party (Job Work Out)</span>
            </label>
            <label class="mc-inv-check">
              <input
                type="checkbox"
                name="thirdPartyStockWithUs"
                [(ngModel)]="thirdPartyStockWithUs"
              />
              <span>Third party stock with us (Job Work In)</span>
            </label>
          </div>

          <footer class="mc-inv-footer">
            <span class="mc-inv-hint">
              Protected <strong>Main Location</strong> cannot be deleted.
            </span>
            <div class="mc-inv-btns">
              @if (editingId() && !editingIsSystem()) {
                <button
                  type="button"
                  class="mc-btn"
                  [disabled]="saving() || companyContext.quarantined()"
                  (click)="deleteCurrent()"
                >
                  Delete
                </button>
              }
              <button
                type="submit"
                class="mc-btn mc-btn-primary"
                data-testid="godown-save-btn"
                [disabled]="saving() || companyContext.quarantined()"
              >
                Accept (<span class="mc-key-ctrl">A</span>)
              </button>
            </div>
          </footer>
        </form>
      </section>
    </div>
  `,
  styles: `
    .mc-inv-wb-grid {
      display: grid;
      grid-template-columns: minmax(260px, 340px) minmax(0, 1fr);
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
    }

    .mc-inv-checks {
      display: flex;
      flex-wrap: wrap;
      gap: var(--mc-space-4);
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
    }

    .mc-inv-check {
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

    .mc-inv-btns {
      display: flex;
      gap: var(--mc-space-2);
    }
  `,
})
export class GodownWorkbenchComponent implements OnInit {
  readonly embeddedOverlay = input<boolean>(false);

  readonly inventoryStore = inject(InventoryStore);
  readonly companyContext = inject(CompanyContextStore);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly navStack = inject(NavigationStackStore);
  private readonly destroyRef = inject(DestroyRef);

  private readonly nameInputRef = viewChild<ElementRef<HTMLInputElement>>('nameInput');

  readonly editingId = signal<string | null>(null);
  readonly editingVersionNo = signal<number>(1);
  readonly editingIsSystem = signal<boolean>(false);
  readonly parentGodownId = signal<string | null>(null);
  readonly saving = signal<boolean>(false);
  readonly errorMsg = signal<string | null>(null);
  readonly savedMsg = signal<string | null>(null);

  name = '';
  aliasCode = '';
  address = '';
  allowStorageOfMaterials = true;
  ourStockWithThirdParty = false;
  thirdPartyStockWithUs = false;

  constructor() {
    effect(() => {
      const cid = this.companyContext.activeCompany()?.id;
      if (cid) void this.inventoryStore.ensureLoaded(cid);
    });
  }

  ngOnInit(): void {
    void this.inventoryStore.ensureLoaded();
    const dispose = this.shortcutRegistry.bindHandler(
      'screen.accept',
      'SCREEN',
      () => {
        void this.saveGodown();
      },
      () => !this.navStack.hasOverlays() || this.embeddedOverlay(),
    );
    this.destroyRef.onDestroy(dispose);
  }

  resetToCreate(): void {
    this.editingId.set(null);
    this.editingVersionNo.set(1);
    this.editingIsSystem.set(false);
    this.name = '';
    this.aliasCode = '';
    this.parentGodownId.set(null);
    this.address = '';
    this.allowStorageOfMaterials = true;
    this.ourStockWithThirdParty = false;
    this.thirdPartyStockWithUs = false;
    this.errorMsg.set(null);
    queueMicrotask(() => this.nameInputRef()?.nativeElement.focus());
  }

  selectForAlter(g: GodownMaster): void {
    this.editingId.set(g.id);
    this.editingVersionNo.set(g.versionNo);
    this.editingIsSystem.set(g.isSystemGodown);
    this.name = g.name;
    this.aliasCode = g.aliasCode ?? '';
    this.parentGodownId.set(g.parentGodownId);
    this.address = g.address ?? '';
    this.allowStorageOfMaterials = g.allowStorageOfMaterials;
    this.ourStockWithThirdParty = g.ourStockWithThirdParty;
    this.thirdPartyStockWithUs = g.thirdPartyStockWithUs;
    this.errorMsg.set(null);
    this.savedMsg.set(null);
  }

  async saveGodown(): Promise<void> {
    if (this.saving()) return;
    const cleanName = this.name.trim();
    if (!cleanName) {
      this.errorMsg.set('Godown Name is required.');
      return;
    }

    this.saving.set(true);
    this.errorMsg.set(null);
    this.savedMsg.set(null);
    try {
      const payload = {
        name: cleanName,
        aliasCode: this.aliasCode.trim() || null,
        parentGodownId: this.parentGodownId(),
        address: this.address.trim() || null,
        allowStorageOfMaterials: this.allowStorageOfMaterials,
        ourStockWithThirdParty: this.ourStockWithThirdParty,
        thirdPartyStockWithUs: this.thirdPartyStockWithUs,
      };
      const id = this.editingId();
      if (id) {
        const updated = await this.inventoryStore.updateGodown(
          id,
          payload,
          this.editingVersionNo(),
        );
        this.editingVersionNo.set(updated.versionNo);
        this.savedMsg.set(`Updated Godown "${updated.name}".`);
      } else {
        const created = await this.inventoryStore.createGodown(payload);
        this.resetToCreate();
        this.savedMsg.set(`Created Godown "${created.name}".`);
      }
    } catch (err: unknown) {
      this.errorMsg.set(err instanceof Error ? err.message : 'Unable to save Godown.');
    } finally {
      this.saving.set(false);
    }
  }

  async deleteCurrent(): Promise<void> {
    const id = this.editingId();
    if (!id || this.editingIsSystem() || this.saving()) return;
    this.saving.set(true);
    try {
      await this.inventoryStore.deleteGodown(id);
      this.resetToCreate();
      this.savedMsg.set('Deleted Godown.');
    } catch (err: unknown) {
      this.errorMsg.set(err instanceof Error ? err.message : 'Unable to delete Godown.');
    } finally {
      this.saving.set(false);
    }
  }
}
