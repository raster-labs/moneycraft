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
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { GST_UQC_OPTIONS, UomMaster, UomType } from './inventory.models';
import { InventoryStore } from './inventory.store';

@Component({
  selector: 'mc-uom-workbench',
  standalone: true,
  imports: [FormsModule, RouterLink, CodeBadgeComponent, GridCellNavDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-inv-wb-grid">
      <!-- Left List of Units -->
      <section class="mc-card mc-inv-list-card" aria-label="List of Units">
        <div class="mc-inv-card-head">
          <span>Units of Measure ({{ inventoryStore.uoms().length }})</span>
          <button type="button" class="mc-btn" (click)="resetToCreate()">+ New Unit</button>
        </div>

        <ul class="mc-inv-list" role="listbox">
          @for (u of inventoryStore.uoms(); track u.id) {
            <li
              role="option"
              class="mc-inv-list-row"
              [class.mc-inv-list-row-active]="editingId() === u.id"
              [attr.aria-selected]="editingId() === u.id"
              (click)="selectForAlter(u)"
            >
              <div>
                <strong class="mc-code">{{ u.symbol }}</strong>
                @if (u.formalName) {
                  <span class="mc-inv-sub"> — {{ u.formalName }}</span>
                }
                @if (u.uomType === 'COMPOUND') {
                  <span class="mc-inv-sub">
                    ({{ u.firstUomSymbol ?? 'Unit' }} of {{ u.conversionFactor }} {{ u.secondUomSymbol ?? 'Base' }})
                  </span>
                }
              </div>
              <mc-code-badge [code]="u.gstUqcCode ?? u.uomType" />
            </li>
          } @empty {
            <li class="mc-inv-empty">No Units created yet. Add Simple (NOS, KGS) or Compound (BOX of 10 NOS) units.</li>
          }
        </ul>
      </section>

      <!-- Right Creation / Alteration Form -->
      <section class="mc-card mc-inv-form-card" aria-label="Unit Creation / Alteration">
        <header class="mc-inv-card-head">
          <span>{{ editingId() ? 'Unit Alteration' : 'Unit Creation' }}</span>
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

        <form class="mc-inv-form" data-mc-nav-container (ngSubmit)="saveUom()">
          <div class="mc-inv-row-3">
            <div>
              <label class="mc-field-label" for="uom-type">Type</label>
              <select
                id="uom-type"
                name="uomType"
                class="mc-select"
                mcGridCellNav
                [ngModel]="uomType()"
                (ngModelChange)="uomType.set($event)"
              >
                <option value="SIMPLE">Simple</option>
                <option value="COMPOUND">Compound</option>
              </select>
            </div>

            <div>
              <label class="mc-field-label" for="uom-symbol">Symbol</label>
              <input
                #symbolInput
                id="uom-symbol"
                type="text"
                name="symbol"
                class="mc-input mc-code"
                mcGridCellNav
                [(ngModel)]="symbol"
                placeholder="NOS / BOX-10"
                required
              />
            </div>

            <div>
              <label class="mc-field-label" for="uom-decimals">Number of Decimal Places (0–6)</label>
              <input
                id="uom-decimals"
                type="number"
                min="0"
                max="6"
                name="decimalPlaces"
                class="mc-input"
                mcGridCellNav
                [(ngModel)]="decimalPlaces"
              />
            </div>
          </div>

          @if (uomType() === 'SIMPLE') {
            <div class="mc-inv-row-2">
              <div>
                <label class="mc-field-label" for="uom-formal">Formal Name</label>
                <input
                  id="uom-formal"
                  type="text"
                  name="formalName"
                  class="mc-input"
                  mcGridCellNav
                  [(ngModel)]="formalName"
                  placeholder="Numbers / Kilograms"
                />
              </div>

              <div>
                <label class="mc-field-label" for="uom-uqc">Unit Quantity Code (UQC — GST)</label>
                <select
                  id="uom-uqc"
                  name="gstUqcCode"
                  class="mc-select mc-code"
                  mcGridCellNav
                  [(ngModel)]="gstUqcCode"
                >
                  @for (uqc of uqcOptions; track uqc.code) {
                    <option [value]="uqc.code">{{ uqc.description }}</option>
                  }
                </select>
              </div>
            </div>
          } @else {
            <div class="mc-inv-row-3">
              <div>
                <label class="mc-field-label" for="uom-first">First Unit</label>
                <select
                  id="uom-first"
                  name="firstUomId"
                  class="mc-select"
                  mcGridCellNav
                  [(ngModel)]="firstUomId"
                >
                  @for (su of inventoryStore.uoms(); track su.id) {
                    <option [value]="su.id">{{ su.symbol }}</option>
                  }
                </select>
              </div>
              <div>
                <label class="mc-field-label" for="uom-conv">Conversion (of)</label>
                <input
                  id="uom-conv"
                  type="text"
                  name="conversionFactor"
                  class="mc-input mc-amount"
                  mcGridCellNav
                  [(ngModel)]="conversionFactor"
                  placeholder="10"
                />
              </div>
              <div>
                <label class="mc-field-label" for="uom-second">Second Unit</label>
                <select
                  id="uom-second"
                  name="secondUomId"
                  class="mc-select"
                  mcGridCellNav
                  [(ngModel)]="secondUomId"
                >
                  @for (su of inventoryStore.uoms(); track su.id) {
                    <option [value]="su.id">{{ su.symbol }}</option>
                  }
                </select>
              </div>
            </div>
          }

          <footer class="mc-inv-footer">
            <span class="mc-inv-hint">Enter = Next Field &bull; Ctrl+A = Accept</span>
            <div class="mc-inv-btns">
              @if (editingId()) {
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
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="uom-save-btn"
                [disabled]="saving() || companyContext.quarantined()"
                (click)="saveUom()"
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
      font-weight: 500;
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

    .mc-inv-row-3 {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--mc-space-3);
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
export class UomWorkbenchComponent implements OnInit {
  readonly embeddedOverlay = input<boolean>(false);

  readonly inventoryStore = inject(InventoryStore);
  readonly companyContext = inject(CompanyContextStore);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly navStack = inject(NavigationStackStore);
  private readonly destroyRef = inject(DestroyRef);

  private readonly symbolInputRef = viewChild<ElementRef<HTMLInputElement>>('symbolInput');

  readonly uqcOptions = GST_UQC_OPTIONS;
  readonly editingId = signal<string | null>(null);
  readonly editingVersionNo = signal<number>(1);
  readonly uomType = signal<UomType>('SIMPLE');
  readonly saving = signal<boolean>(false);
  readonly errorMsg = signal<string | null>(null);
  readonly savedMsg = signal<string | null>(null);

  symbol = '';
  formalName = '';
  gstUqcCode = 'NOS';
  decimalPlaces = 0;
  firstUomId = '';
  conversionFactor = '10';
  secondUomId = '';

  constructor() {
    effect(() => {
      const cid = this.companyContext.activeCompany()?.id;
      if (cid) {
        void this.inventoryStore.ensureLoaded(cid);
      }
    });
  }

  ngOnInit(): void {
    void this.inventoryStore.ensureLoaded();
    const dispose = this.shortcutRegistry.bindHandler(
      'screen.accept',
      'SCREEN',
      () => {
        void this.saveUom();
      },
      () => !this.navStack.hasOverlays() || this.embeddedOverlay(),
    );
    this.destroyRef.onDestroy(dispose);
  }

  resetToCreate(): void {
    this.editingId.set(null);
    this.editingVersionNo.set(1);
    this.uomType.set('SIMPLE');
    this.symbol = '';
    this.formalName = '';
    this.gstUqcCode = 'NOS';
    this.decimalPlaces = 0;
    this.firstUomId = this.inventoryStore.uoms()[0]?.id ?? '';
    this.conversionFactor = '10';
    this.secondUomId = this.inventoryStore.uoms()[0]?.id ?? '';
    this.errorMsg.set(null);
    queueMicrotask(() => this.symbolInputRef()?.nativeElement.focus());
  }

  selectForAlter(u: UomMaster): void {
    this.editingId.set(u.id);
    this.editingVersionNo.set(u.versionNo);
    this.uomType.set(u.uomType);
    this.symbol = u.symbol;
    this.formalName = u.formalName ?? '';
    this.gstUqcCode = u.gstUqcCode ?? 'NOS';
    this.decimalPlaces = u.decimalPlaces;
    this.firstUomId = u.firstUomId ?? '';
    this.conversionFactor = u.conversionFactor ?? '10';
    this.secondUomId = u.secondUomId ?? '';
    this.errorMsg.set(null);
    this.savedMsg.set(null);
  }

  async saveUom(): Promise<void> {
    if (this.saving()) return;
    const cleanSymbol = this.symbol.trim();
    if (!cleanSymbol) {
      this.errorMsg.set('Unit Symbol is required.');
      return;
    }
    if (this.uomType() === 'COMPOUND' && (!this.firstUomId || !this.secondUomId)) {
      this.errorMsg.set('Compound Unit requires both First Unit and Second Unit.');
      return;
    }

    this.saving.set(true);
    this.errorMsg.set(null);
    this.savedMsg.set(null);
    try {
      const payload = {
        uomType: this.uomType(),
        symbol: cleanSymbol,
        formalName: this.uomType() === 'SIMPLE' ? this.formalName.trim() || cleanSymbol : null,
        gstUqcCode: this.uomType() === 'SIMPLE' ? this.gstUqcCode : null,
        decimalPlaces: Math.min(6, Math.max(0, Math.trunc(this.decimalPlaces))),
        firstUomId: this.uomType() === 'COMPOUND' ? this.firstUomId : null,
        conversionFactor:
          this.uomType() === 'COMPOUND' ? this.conversionFactor.trim() || '1' : null,
        secondUomId: this.uomType() === 'COMPOUND' ? this.secondUomId : null,
      };
      const id = this.editingId();
      if (id) {
        const updated = await this.inventoryStore.updateUom(id, payload, this.editingVersionNo());
        this.editingVersionNo.set(updated.versionNo);
        this.savedMsg.set(`Updated Unit "${updated.symbol}".`);
      } else {
        const created = await this.inventoryStore.createUom(payload);
        this.resetToCreate();
        this.savedMsg.set(`Created Unit "${created.symbol}".`);
      }
    } catch (err: unknown) {
      this.errorMsg.set(err instanceof Error ? err.message : 'Unable to save Unit.');
    } finally {
      this.saving.set(false);
    }
  }

  async deleteCurrent(): Promise<void> {
    const id = this.editingId();
    if (!id || this.saving()) return;
    this.saving.set(true);
    try {
      await this.inventoryStore.deleteUom(id);
      this.resetToCreate();
      this.savedMsg.set('Deleted Unit.');
    } catch (err: unknown) {
      this.errorMsg.set(err instanceof Error ? err.message : 'Unable to delete Unit.');
    } finally {
      this.saving.set(false);
    }
  }
}
