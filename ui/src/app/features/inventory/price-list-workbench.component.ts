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
import { ExactDecimal } from '../../core/money/exact-decimal';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { TypeaheadSelectComponent } from '../../shared/components/typeahead-select/typeahead-select.component';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { SecondaryMasterModalService } from '../masters/secondary-master-modal.service';
import { PriceListMaster, PriceTierDto, UpsertPriceListRequest } from './inventory.models';
import { InventoryStore } from './inventory.store';

interface EditablePriceTierRow {
  rowKey: number;
  stockItemId: string;
  fromQty: string;
  lessThanQty: string;
  rate: string;
  discountPct: string;
}

@Component({
  selector: 'mc-price-list-workbench',
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
    <div class="mc-pl-wb-grid">
      <!-- Left Price Levels List -->
      <section class="mc-card mc-pl-list-card" aria-label="List of Price Lists">
        <div class="mc-pl-card-head">
          <span>Price Lists ({{ inventoryStore.priceLists().length }})</span>
          <button type="button" class="mc-btn" (click)="resetToCreate()">+ New Price List</button>
        </div>

        <ul class="mc-pl-list" role="listbox">
          @for (pl of inventoryStore.priceLists(); track pl.id) {
            <li
              role="option"
              class="mc-pl-list-row"
              [class.mc-pl-list-row-active]="editingId() === pl.id"
              [attr.aria-selected]="editingId() === pl.id"
              (click)="selectForAlter(pl)"
            >
              <div>
                <strong>{{ pl.priceLevelName }}</strong>
                <div class="mc-pl-sub">
                  Applicable from <span class="mc-code">{{ pl.applicableFrom }}</span>
                </div>
              </div>
              <mc-code-badge [code]="pl.tiers.length + ' TIERS'" />
            </li>
          } @empty {
            <li class="mc-pl-empty">
              No Price Lists created yet. Define Wholesale, Dealer, or Retail staggered price tiers.
            </li>
          }
        </ul>
      </section>

      <!-- Right Staggered Price List Editor -->
      <section class="mc-card mc-pl-form-card" aria-label="Price List Creation / Alteration">
        <header class="mc-pl-card-head">
          <span>{{ editingId() ? 'Price List Alteration' : 'Price List Creation' }}</span>
          @if (!embeddedOverlay()) {
            <a routerLink="/app/masters" class="mc-btn">Masters Hub</a>
          }
        </header>

        @if (errorMsg(); as err) {
          <div class="mc-pl-banner mc-pl-banner-err" role="alert">{{ err }}</div>
        }
        @if (savedMsg(); as ok) {
          <div class="mc-pl-banner mc-pl-banner-ok" role="status">{{ ok }}</div>
        }

        <form class="mc-pl-form" data-mc-nav-container (ngSubmit)="savePriceList()">
          <div class="mc-pl-row-2">
            <div>
              <label class="mc-field-label" for="pl-level">Price Level</label>
              <input
                #levelInput
                id="pl-level"
                type="text"
                name="priceLevelName"
                class="mc-input"
                data-testid="pl-level-input"
                mcGridCellNav
                [(ngModel)]="priceLevelName"
                placeholder="Wholesale / Institutional / Retail"
                required
              />
            </div>

            <div>
              <label class="mc-field-label" for="pl-date">Applicable From</label>
              <input
                id="pl-date"
                type="date"
                name="applicableFrom"
                class="mc-input mc-code"
                mcGridCellNav
                [(ngModel)]="applicableFrom"
                required
              />
            </div>
          </div>

          <div class="mc-pl-tier-head">
            <span>Staggered Quantity Slabs (From Qty &rarr; Less Than Qty &rarr; Rate &amp; Disc %)</span>
            <button type="button" class="mc-btn" (click)="addTierRow()">+ Add Slab Row</button>
          </div>

          <div class="mc-pl-table-wrap">
            <table class="mc-table mc-pl-table">
              <thead>
                <tr>
                  <th>Stock Item (<span class="mc-key-alt">C</span> = Create)</th>
                  <th class="mc-text-right">From Qty</th>
                  <th class="mc-text-right">Less Than</th>
                  <th class="mc-text-right">Rate (₹)</th>
                  <th class="mc-text-right">Disc %</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                @for (row of tiers(); track row.rowKey; let rIdx = $index) {
                  <tr>
                    <td>
                      <mc-typeahead-select
                        [inputId]="'pl-item-' + row.rowKey"
                        listTitle="List of Stock Items"
                        [options]="inventoryStore.stockItemOptions()"
                        [selectedId]="row.stockItemId || null"
                        [allowInlineCreate]="true"
                        (optionSelected)="row.stockItemId = $event.id"
                        (createRequested)="openInlineItemCreate(rIdx, $event)"
                      />
                    </td>
                    <td>
                      <input
                        type="text"
                        class="mc-input mc-amount"
                        [name]="'plFrom' + row.rowKey"
                        mcGridCellNav
                        [(ngModel)]="row.fromQty"
                        placeholder="0"
                      />
                    </td>
                    <td>
                      <input
                        type="text"
                        class="mc-input mc-amount"
                        [name]="'plLess' + row.rowKey"
                        mcGridCellNav
                        [(ngModel)]="row.lessThanQty"
                        placeholder="Unlimited"
                      />
                    </td>
                    <td>
                      <input
                        type="text"
                        class="mc-input mc-amount"
                        [name]="'plRate' + row.rowKey"
                        mcGridCellNav
                        [(ngModel)]="row.rate"
                        placeholder="0.00"
                      />
                    </td>
                    <td>
                      <input
                        type="text"
                        class="mc-input mc-amount"
                        [name]="'plDisc' + row.rowKey"
                        mcGridCellNav
                        [(ngModel)]="row.discountPct"
                        placeholder="0"
                      />
                    </td>
                    <td>
                      <button
                        type="button"
                        class="mc-btn"
                        (click)="removeTierRow(row.rowKey)"
                      >
                        &times;
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>

          <footer class="mc-pl-footer">
            <span class="mc-pl-hint">
              Enter = Next Cell &bull; Leave "Less Than" blank for open-ended upper slab &bull; Ctrl+A = Accept
            </span>
            <button
              type="submit"
              class="mc-btn mc-btn-primary"
              data-testid="pl-save-btn"
              [disabled]="saving() || companyContext.quarantined()"
            >
              Accept (<span class="mc-key-ctrl">A</span>)
            </button>
          </footer>
        </form>
      </section>
    </div>
  `,
  styles: `
    .mc-pl-wb-grid {
      display: grid;
      grid-template-columns: minmax(250px, 320px) minmax(0, 1fr);
      gap: var(--mc-space-4);
      align-items: start;
    }

    @media (max-width: 940px) {
      .mc-pl-wb-grid {
        grid-template-columns: 1fr;
      }
    }

    .mc-pl-card-head {
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      padding: 0.5rem 0.875rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-weight: 700;
    }

    .mc-pl-list-card,
    .mc-pl-form-card {
      overflow: visible;
    }

    .mc-pl-list {
      list-style: none;
      margin: 0;
      padding: 0;
      max-height: 62vh;
      overflow-y: auto;
    }

    .mc-pl-list-row {
      padding: 0.5rem 0.85rem;
      border-bottom: 1px solid var(--mc-color-slate-100);
      display: flex;
      justify-content: space-between;
      align-items: center;
      cursor: pointer;
      font-weight: 600;
    }

    .mc-pl-list-row-active {
      background: var(--mc-color-teal-50);
    }

    .mc-pl-sub {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-pl-empty {
      padding: var(--mc-space-4);
      text-align: center;
      color: var(--mc-color-slate-600);
      font-size: var(--mc-font-size-xs);
      font-weight: 500;
    }

    .mc-pl-banner {
      margin: var(--mc-space-3) var(--mc-space-4) 0;
      padding: var(--mc-space-2) var(--mc-space-3);
      border-radius: var(--mc-radius-xs);
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
    }

    .mc-pl-banner-err {
      background: #fef2f2;
      color: #991b1b;
      border: 1px solid #fecaca;
    }

    .mc-pl-banner-ok {
      background: var(--mc-color-teal-50);
      color: var(--mc-color-pressed-teal);
      border: 1px solid var(--mc-color-teal-100);
    }

    .mc-pl-form {
      padding: var(--mc-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-pl-row-2 {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    .mc-pl-tier-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.03em;
      color: var(--mc-color-slate-900);
    }

    .mc-pl-table-wrap {
      overflow: visible;
    }

    .mc-pl-table {
      width: 100%;
    }

    .mc-text-right {
      text-align: right;
    }

    .mc-pl-footer {
      border-top: var(--mc-border-subtle);
      padding-top: var(--mc-space-3);
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .mc-pl-hint {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }
  `,
})
export class PriceListWorkbenchComponent implements OnInit {
  readonly embeddedOverlay = input<boolean>(false);

  readonly inventoryStore = inject(InventoryStore);
  readonly companyContext = inject(CompanyContextStore);
  private readonly secondaryModal = inject(SecondaryMasterModalService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly navStack = inject(NavigationStackStore);
  private readonly destroyRef = inject(DestroyRef);

  private readonly levelInputRef = viewChild<ElementRef<HTMLInputElement>>('levelInput');

  readonly editingId = signal<string | null>(null);
  readonly editingVersionNo = signal<number>(1);
  readonly saving = signal<boolean>(false);
  readonly errorMsg = signal<string | null>(null);
  readonly savedMsg = signal<string | null>(null);

  priceLevelName = '';
  applicableFrom = '2025-04-01';
  readonly tiers = signal<EditablePriceTierRow[]>([]);
  private nextRowKey = 1;

  constructor() {
    effect(() => {
      const cid = this.companyContext.activeCompany()?.id;
      if (cid) {
        void this.inventoryStore.ensureLoaded(cid);
      }
    });
  }

  ngOnInit(): void {
    this.applicableFrom =
      this.companyContext.activeCompany()?.financialYearFrom ?? '2025-04-01';
    this.tiers.set([this.makeEmptyTierRow()]);
    void this.inventoryStore.ensureLoaded();

    const dispose = this.shortcutRegistry.bindHandler(
      'screen.accept',
      'SCREEN',
      () => {
        void this.savePriceList();
      },
      () =>
        !this.secondaryModal.isOpen() &&
        (!this.navStack.hasOverlays() || this.embeddedOverlay()),
    );
    this.destroyRef.onDestroy(dispose);
  }

  private makeEmptyTierRow(): EditablePriceTierRow {
    return {
      rowKey: this.nextRowKey++,
      stockItemId: '',
      fromQty: '0',
      lessThanQty: '',
      rate: '',
      discountPct: '0',
    };
  }

  addTierRow(): void {
    this.tiers.set([...this.tiers(), this.makeEmptyTierRow()]);
  }

  removeTierRow(rowKey: number): void {
    const next = this.tiers().filter((r) => r.rowKey !== rowKey);
    this.tiers.set(next.length > 0 ? next : [this.makeEmptyTierRow()]);
  }

  openInlineItemCreate(rowIndex: number, initialName: string): void {
    void this.secondaryModal.open({
      kind: 'STOCK_ITEM',
      initialName,
      onCreated: (created) => {
        const list = [...this.tiers()];
        if (list[rowIndex]) {
          list[rowIndex] = { ...list[rowIndex], stockItemId: created.id };
          this.tiers.set(list);
        }
      },
    });
  }

  resetToCreate(): void {
    this.editingId.set(null);
    this.editingVersionNo.set(1);
    this.priceLevelName = '';
    this.applicableFrom =
      this.companyContext.activeCompany()?.financialYearFrom ?? '2025-04-01';
    this.tiers.set([this.makeEmptyTierRow()]);
    this.errorMsg.set(null);
    queueMicrotask(() => this.levelInputRef()?.nativeElement.focus());
  }

  selectForAlter(pl: PriceListMaster): void {
    this.editingId.set(pl.id);
    this.editingVersionNo.set(pl.versionNo);
    this.priceLevelName = pl.priceLevelName;
    this.applicableFrom = pl.applicableFrom;
    this.tiers.set(
      pl.tiers.length > 0
        ? pl.tiers.map((t) => ({
            rowKey: this.nextRowKey++,
            stockItemId: t.stockItemId,
            fromQty: t.fromQty,
            lessThanQty: t.lessThanQty ?? '',
            rate: t.rate,
            discountPct: t.discountPct,
          }))
        : [this.makeEmptyTierRow()],
    );
    this.errorMsg.set(null);
    this.savedMsg.set(null);
  }

  async savePriceList(): Promise<void> {
    if (this.saving()) return;
    const level = this.priceLevelName.trim();
    if (!level) {
      this.errorMsg.set('Price Level Name is required.');
      return;
    }
    if (!this.applicableFrom) {
      this.errorMsg.set('Applicable From date is required.');
      return;
    }

    const compiledTiers: PriceTierDto[] = [];
    for (const row of this.tiers()) {
      if (!row.stockItemId) continue;
      const rateDec = ExactDecimal.tryParse(row.rate);
      if (!rateDec || rateDec.isNegative()) {
        this.errorMsg.set('Each Price List slab must specify a valid non-negative Rate.');
        return;
      }
      compiledTiers.push({
        stockItemId: row.stockItemId,
        fromQty: row.fromQty.trim() || '0',
        lessThanQty: row.lessThanQty.trim() || null,
        rate: row.rate.trim(),
        discountPct: row.discountPct.trim() || '0',
      });
    }

    if (compiledTiers.length === 0) {
      this.errorMsg.set('Add at least one Stock Item tier slab before saving.');
      return;
    }

    const payload: UpsertPriceListRequest = {
      priceLevelName: level,
      applicableFrom: this.applicableFrom,
      tiers: compiledTiers,
    };

    this.saving.set(true);
    this.errorMsg.set(null);
    this.savedMsg.set(null);
    try {
      const id = this.editingId();
      if (id) {
        const updated = await this.inventoryStore.updatePriceList(
          id,
          payload,
          this.editingVersionNo(),
        );
        this.editingVersionNo.set(updated.versionNo);
        this.savedMsg.set(`Updated Price List "${updated.priceLevelName}".`);
      } else {
        const created = await this.inventoryStore.createPriceList(payload);
        this.resetToCreate();
        this.savedMsg.set(`Created Price List "${created.priceLevelName}".`);
      }
    } catch (err: unknown) {
      this.errorMsg.set(err instanceof Error ? err.message : 'Unable to save Price List.');
    } finally {
      this.saving.set(false);
    }
  }
}
