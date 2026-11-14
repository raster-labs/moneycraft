import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { ExactDecimal } from '../../core/money/exact-decimal';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import {
  TypeaheadOption,
  TypeaheadSelectComponent,
} from '../../shared/components/typeahead-select/typeahead-select.component';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { ChartOfAccountsStore } from '../masters/chart-of-accounts.store';
import { GstApplicability, GstSupplyType } from '../masters/masters.models';
import { SecondaryMasterModalService } from '../masters/secondary-master-modal.service';
import {
  BomAdditionalCostRuleDto,
  BomComponentDto,
  BomComponentRole,
  BomCostRuleBasis,
  BomMaster,
  MARKET_VALUATION_METHOD_OPTIONS,
  MarketValuationMethod,
  STOCK_COSTING_METHOD_OPTIONS,
  StockCostingMethod,
  StockItemMaster,
  UpsertBomRequest,
  UpsertStockItemRequest,
} from './inventory.models';
import { InventoryStore } from './inventory.store';

interface EditableBomComponentRow {
  rowKey: number;
  componentType: BomComponentRole;
  stockItemId: string;
  godownId: string | null;
  quantity: string;
  costAllocationPct: string;
}

interface EditableBomCostRuleRow {
  rowKey: number;
  ledgerId: string;
  calculationBasis: BomCostRuleBasis;
  rateOrAmount: string;
}

@Component({
  selector: 'mc-stock-item-workbench',
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
    <div class="mc-si-wb-grid">
      <!-- Left List of Stock Items -->
      <section class="mc-card mc-si-list-card" aria-label="List of Stock Items">
        <div class="mc-si-card-head">
          <span>Stock Items ({{ inventoryStore.stockItems().length }})</span>
          <button
            type="button"
            class="mc-btn"
            data-testid="stock-item-new-btn"
            (click)="resetToCreate()"
          >
            + New Item
          </button>
        </div>

        <div class="mc-si-search-wrap">
          <input
            type="text"
            class="mc-input"
            [ngModel]="searchQuery()"
            (ngModelChange)="searchQuery.set($event)"
            placeholder="Filter by Name, Alias, Part No, HSN..."
            aria-label="Filter Stock Items"
          />
        </div>

        <ul class="mc-si-list" role="listbox">
          @for (item of filteredItems(); track item.id) {
            <li
              role="option"
              class="mc-si-list-row"
              [class.mc-si-list-row-active]="editingId() === item.id"
              [attr.aria-selected]="editingId() === item.id"
              (click)="selectForAlter(item)"
            >
              <div class="mc-si-row-main">
                <strong>{{ item.name }}</strong>
                <span class="mc-si-row-sub">
                  {{ item.stockGroupName ?? 'Primary' }}
                  @if (item.partNo) {
                    &bull; <span class="mc-code">{{ item.partNo }}</span>
                  }
                  @if (item.hsnSacCode) {
                    &bull; HSN <span class="mc-code">{{ item.hsnSacCode }}</span>
                  }
                </span>
              </div>
              <div class="mc-si-row-badges">
                @if (bomsCountByItem().get(item.id); as bCount) {
                  <mc-code-badge [code]="'BOM×' + bCount" />
                }
                @if (item.isTobaccoMrpItem) {
                  <mc-code-badge code="MRP" />
                }
                <mc-code-badge [code]="item.baseUomSymbol" />
              </div>
            </li>
          } @empty {
            <li class="mc-si-empty">
              No Stock Items match filter. Create your first Stock Item on the right.
            </li>
          }
        </ul>
      </section>

      <!-- Right Stock Item Creation / Alteration Workbench -->
      <section class="mc-card mc-si-form-card" aria-label="Stock Item Creation / Alteration">
        <header class="mc-si-card-head">
          <span>{{ editingId() ? 'Stock Item Alteration' : 'Stock Item Creation' }}</span>
          <div class="mc-si-head-actions">
            <button
              type="button"
              class="mc-btn"
              data-testid="open-bom-modal-btn"
              (click)="openBomModal()"
            >
              Set / Alter BOM ({{ activeItemBoms().length + (pendingBomDraft() ? 1 : 0) }})
            </button>
            @if (!embeddedOverlay()) {
              <a routerLink="/app/masters" class="mc-btn">Masters Hub</a>
            }
          </div>
        </header>

        @if (errorMsg(); as err) {
          <div class="mc-si-banner mc-si-banner-err" role="alert">{{ err }}</div>
        }
        @if (savedMsg(); as ok) {
          <div class="mc-si-banner mc-si-banner-ok" role="status">{{ ok }}</div>
        }

        <form class="mc-si-form" data-mc-nav-container (ngSubmit)="saveStockItem()">
          <!-- Identity Row -->
          <div class="mc-si-row-3">
            <div>
              <label class="mc-field-label" for="si-name">Name</label>
              <input
                #nameInput
                id="si-name"
                type="text"
                name="name"
                class="mc-input"
                data-testid="si-name-input"
                mcGridCellNav
                [(ngModel)]="name"
                placeholder="Pulse Oximeter Pro"
                required
              />
            </div>

            <div>
              <label class="mc-field-label" for="si-alias">Alias</label>
              <input
                id="si-alias"
                type="text"
                name="aliasCode"
                class="mc-input mc-code"
                mcGridCellNav
                [(ngModel)]="aliasCode"
                placeholder="POP-01"
              />
            </div>

            <div>
              <label class="mc-field-label" for="si-part">Part No.</label>
              <input
                id="si-part"
                type="text"
                name="partNo"
                class="mc-input mc-code"
                data-testid="si-part-input"
                mcGridCellNav
                [(ngModel)]="partNo"
                placeholder="SKU-OXI-900"
              />
            </div>
          </div>

          <!-- Hierarchy & Units Row -->
          <div class="mc-si-row-3">
            <div>
              <label class="mc-field-label" for="si-group">
                Under (<span class="mc-key-alt">C</span> = Stock Group)
              </label>
              <mc-typeahead-select
                inputId="si-group"
                listTitle="List of Stock Groups"
                [options]="inventoryStore.parentStockGroupOptions()"
                [selectedId]="stockGroupId() ?? '__PRIMARY__'"
                [allowInlineCreate]="true"
                (optionSelected)="onStockGroupSelected($event)"
                (createRequested)="openInlineStockGroupCreate($event)"
              />
            </div>

            <div>
              <label class="mc-field-label" for="si-category">Category</label>
              <mc-typeahead-select
                inputId="si-category"
                listTitle="List of Stock Categories"
                [options]="inventoryStore.parentStockCategoryOptions()"
                [selectedId]="stockCategoryId() ?? '__PRIMARY__'"
                [allowInlineCreate]="false"
                (optionSelected)="stockCategoryId.set($event.id === '__PRIMARY__' ? null : $event.id)"
              />
            </div>

            <div>
              <label class="mc-field-label" for="si-base-uom">
                Units (<span class="mc-key-alt">C</span> = Unit)
              </label>
              <mc-typeahead-select
                inputId="si-base-uom"
                listTitle="List of Units"
                [options]="inventoryStore.uomOptions()"
                [selectedId]="baseUomId()"
                [allowInlineCreate]="true"
                (optionSelected)="baseUomId.set($event.id)"
                (createRequested)="openInlineUomCreate($event, 'BASE')"
              />
            </div>
          </div>

          <!-- Optional Alternate Unit Conversion -->
          <div class="mc-si-subbox">
            <div class="mc-si-subhead">
              <span>Alternate Units &amp; Conversion Ratio (Optional)</span>
              @if (alternateUomId()) {
                <button
                  type="button"
                  class="mc-btn"
                  (click)="clearAlternateUom()"
                >
                  Clear Alternate Unit
                </button>
              }
            </div>
            <div class="mc-si-row-3">
              <div>
                <label class="mc-field-label" for="si-alt-uom">
                  Alternate Unit (<span class="mc-key-alt">C</span> = Unit)
                </label>
                <mc-typeahead-select
                  inputId="si-alt-uom"
                  listTitle="List of Units"
                  [options]="alternateUomCandidates()"
                  [selectedId]="alternateUomId() ?? '__NONE__'"
                  [allowInlineCreate]="true"
                  (optionSelected)="onAlternateUomSelected($event)"
                  (createRequested)="openInlineUomCreate($event, 'ALT')"
                />
              </div>

              @if (alternateUomId()) {
                <div>
                  <label class="mc-field-label" for="si-conv-den">
                    Where ({{ selectedAltSymbol() }})
                  </label>
                  <input
                    id="si-conv-den"
                    type="text"
                    name="conversionDenominator"
                    class="mc-input mc-amount"
                    mcGridCellNav
                    [(ngModel)]="conversionDenominator"
                    placeholder="1"
                  />
                </div>
                <div>
                  <label class="mc-field-label" for="si-conv-num">
                    Equals ({{ selectedBaseSymbol() }})
                  </label>
                  <input
                    id="si-conv-num"
                    type="text"
                    name="conversionNumerator"
                    class="mc-input mc-amount"
                    mcGridCellNav
                    [(ngModel)]="conversionNumerator"
                    placeholder="10"
                  />
                </div>
              }
            </div>
          </div>

          <!-- Batch, Costing & Standard Rates -->
          <div class="mc-si-subbox">
            <div class="mc-si-subhead">
              <span>Batch Tracking &amp; Valuation Behaviour</span>
            </div>

            <div class="mc-si-checks">
              <label class="mc-si-check">
                <input
                  type="checkbox"
                  name="maintainInBatches"
                  data-testid="si-batches-checkbox"
                  [ngModel]="maintainInBatches()"
                  (ngModelChange)="onMaintainInBatchesChange($event)"
                />
                <span>Maintain in batches</span>
              </label>

              @if (maintainInBatches()) {
                <label class="mc-si-check">
                  <input
                    type="checkbox"
                    name="trackDateOfMfg"
                    [(ngModel)]="trackDateOfMfg"
                  />
                  <span>Track date of manufacturing</span>
                </label>

                <label class="mc-si-check">
                  <input
                    type="checkbox"
                    name="useExpiryDates"
                    [(ngModel)]="useExpiryDates"
                  />
                  <span>Use expiry dates</span>
                </label>
              }
            </div>

            <div class="mc-si-row-4">
              <div>
                <label class="mc-field-label" for="si-costing">Costing Method</label>
                <select
                  id="si-costing"
                  name="costingMethod"
                  class="mc-select"
                  data-testid="si-costing-select"
                  mcGridCellNav
                  [(ngModel)]="costingMethod"
                >
                  @for (cm of costingMethodOptions; track cm.value) {
                    <option [value]="cm.value">{{ cm.label }}</option>
                  }
                </select>
              </div>

              <div>
                <label class="mc-field-label" for="si-market">Market Valuation Method</label>
                <select
                  id="si-market"
                  name="marketValuationMethod"
                  class="mc-select"
                  mcGridCellNav
                  [(ngModel)]="marketValuationMethod"
                >
                  @for (mv of marketValuationOptions; track mv.value) {
                    <option [value]="mv.value">{{ mv.label }}</option>
                  }
                </select>
              </div>

              <div>
                <label class="mc-field-label" for="si-std-cost">Std. Cost</label>
                <input
                  id="si-std-cost"
                  type="text"
                  name="standardCost"
                  class="mc-input mc-amount"
                  mcGridCellNav
                  [(ngModel)]="standardCost"
                  placeholder="0.00"
                />
              </div>

              <div>
                <label class="mc-field-label" for="si-std-price">Std. Selling Price</label>
                <input
                  id="si-std-price"
                  type="text"
                  name="standardSellingPrice"
                  class="mc-input mc-amount"
                  mcGridCellNav
                  [(ngModel)]="standardSellingPrice"
                  placeholder="0.00"
                />
              </div>
            </div>
          </div>

          <!-- Statutory GST + Rel 7.2 Tobacco Deemed MRP + Default Ledgers -->
          <div class="mc-si-subbox">
            <div class="mc-si-subhead">
              <span>Statutory / GST Details &amp; Rel 7.2 Tobacco MRP Valuation</span>
            </div>

            <div class="mc-si-row-4">
              <div>
                <label class="mc-field-label" for="si-gst-app">GST Applicability</label>
                <select
                  id="si-gst-app"
                  name="gstApplicability"
                  class="mc-select"
                  mcGridCellNav
                  [(ngModel)]="gstApplicability"
                >
                  <option value="APPLICABLE">Applicable</option>
                  <option value="NOT_APPLICABLE">Not Applicable</option>
                  <option value="UNDEFINED">Undefined</option>
                </select>
              </div>

              <div>
                <label class="mc-field-label" for="si-supply-type">Type of Supply</label>
                <select
                  id="si-supply-type"
                  name="gstSupplyType"
                  class="mc-select"
                  mcGridCellNav
                  [(ngModel)]="gstSupplyType"
                >
                  <option value="GOODS">Goods</option>
                  <option value="SERVICES">Services</option>
                  <option value="CAPITAL_GOODS">Capital Goods</option>
                </select>
              </div>

              <div>
                <label class="mc-field-label" for="si-hsn">HSN / SAC Code</label>
                <input
                  id="si-hsn"
                  type="text"
                  name="hsnSacCode"
                  class="mc-input mc-code"
                  data-testid="si-hsn-input"
                  mcGridCellNav
                  [(ngModel)]="hsnSacCode"
                  placeholder="90189099"
                />
              </div>

              <div>
                <label class="mc-field-label" for="si-gst-rate">GST Rate (%)</label>
                <input
                  id="si-gst-rate"
                  type="text"
                  name="gstRatePct"
                  class="mc-input mc-amount"
                  data-testid="si-gst-rate-input"
                  mcGridCellNav
                  [(ngModel)]="gstRatePct"
                  placeholder="12"
                />
              </div>
            </div>

            <div class="mc-si-row-4">
              <div>
                <label class="mc-field-label" for="si-cess-pct">Cess Rate (%)</label>
                <input
                  id="si-cess-pct"
                  type="text"
                  name="cessRatePct"
                  class="mc-input mc-amount"
                  mcGridCellNav
                  [(ngModel)]="cessRatePct"
                  placeholder="0"
                />
              </div>

              <div>
                <label class="mc-field-label" for="si-cess-unit">Cess / Unit</label>
                <input
                  id="si-cess-unit"
                  type="text"
                  name="cessPerUnit"
                  class="mc-input mc-amount"
                  mcGridCellNav
                  [(ngModel)]="cessPerUnit"
                  placeholder="0.00"
                />
              </div>

              <label class="mc-si-check mc-si-check-inline">
                <input
                  type="checkbox"
                  name="isTobaccoMrpItem"
                  data-testid="si-tobacco-mrp-checkbox"
                  [ngModel]="isTobaccoMrpItem()"
                  (ngModelChange)="isTobaccoMrpItem.set($event)"
                />
                <span>Tobacco / Retail Sale Price (MRP) Item</span>
              </label>

              @if (isTobaccoMrpItem()) {
                <div>
                  <label class="mc-field-label" for="si-mrp">MRP / Unit (₹)</label>
                  <input
                    id="si-mrp"
                    type="text"
                    name="mrpPerUnit"
                    class="mc-input mc-amount"
                    data-testid="si-mrp-input"
                    mcGridCellNav
                    [(ngModel)]="mrpPerUnit"
                    placeholder="350.00"
                  />
                </div>
              }
            </div>

            <div class="mc-si-row-2">
              <div>
                <label class="mc-field-label" for="si-def-sales">Default Sales Ledger</label>
                <mc-typeahead-select
                  inputId="si-def-sales"
                  listTitle="List of Sales Ledgers"
                  [options]="salesLedgerOptions()"
                  [selectedId]="defaultSalesLedgerId() ?? '__NONE__'"
                  [allowInlineCreate]="false"
                  (optionSelected)="defaultSalesLedgerId.set($event.id === '__NONE__' ? null : $event.id)"
                />
              </div>

              <div>
                <label class="mc-field-label" for="si-def-purchase">Default Purchase Ledger</label>
                <mc-typeahead-select
                  inputId="si-def-purchase"
                  listTitle="List of Purchase Ledgers"
                  [options]="purchaseLedgerOptions()"
                  [selectedId]="defaultPurchaseLedgerId() ?? '__NONE__'"
                  [allowInlineCreate]="false"
                  (optionSelected)="defaultPurchaseLedgerId.set($event.id === '__NONE__' ? null : $event.id)"
                />
              </div>
            </div>
          </div>

          <!-- Opening Balance -->
          <div class="mc-si-subbox">
            <div class="mc-si-subhead">
              <span>Opening Balance (ExactDecimal Quantity &times; Rate = Value)</span>
            </div>

            <div class="mc-si-row-4">
              <div>
                <label class="mc-field-label" for="si-open-qty">Quantity</label>
                <input
                  id="si-open-qty"
                  type="text"
                  name="openingQty"
                  class="mc-input mc-amount"
                  data-testid="si-open-qty-input"
                  mcGridCellNav
                  [ngModel]="openingQty()"
                  (ngModelChange)="onOpeningQtyOrRateChange($event, openingRate())"
                  placeholder="0"
                />
              </div>

              <div>
                <label class="mc-field-label" for="si-open-rate">Rate per {{ selectedBaseSymbol() }}</label>
                <input
                  id="si-open-rate"
                  type="text"
                  name="openingRate"
                  class="mc-input mc-amount"
                  data-testid="si-open-rate-input"
                  mcGridCellNav
                  [ngModel]="openingRate()"
                  (ngModelChange)="onOpeningQtyOrRateChange(openingQty(), $event)"
                  placeholder="0.00"
                />
              </div>

              <div>
                <label class="mc-field-label" for="si-open-val">Value (₹)</label>
                <input
                  id="si-open-val"
                  type="text"
                  name="openingValue"
                  class="mc-input mc-amount"
                  data-testid="si-open-val-input"
                  mcGridCellNav
                  [ngModel]="openingValue()"
                  (ngModelChange)="openingValue.set($event)"
                  placeholder="0.00"
                />
              </div>

              <div>
                <label class="mc-field-label" for="si-open-godown">
                  Opening Godown (<span class="mc-key-alt">C</span> = Godown)
                </label>
                <mc-typeahead-select
                  inputId="si-open-godown"
                  listTitle="List of Godowns"
                  [options]="openingGodownOptions()"
                  [selectedId]="openingGodownId() ?? '__NONE__'"
                  [allowInlineCreate]="true"
                  (optionSelected)="openingGodownId.set($event.id === '__NONE__' ? null : $event.id)"
                  (createRequested)="openInlineGodownCreate($event)"
                />
              </div>
            </div>

            @if (maintainInBatches()) {
              <div class="mc-si-row-3">
                <div>
                  <label class="mc-field-label" for="si-open-batch">Opening Batch / Lot Name</label>
                  <input
                    id="si-open-batch"
                    type="text"
                    name="openingBatchName"
                    class="mc-input mc-code"
                    mcGridCellNav
                    [(ngModel)]="openingBatchName"
                    placeholder="BATCH-001"
                  />
                </div>

                @if (trackDateOfMfg) {
                  <div>
                    <label class="mc-field-label" for="si-open-mfg">Mfg. Date</label>
                    <input
                      id="si-open-mfg"
                      type="date"
                      name="openingMfgDate"
                      class="mc-input mc-code"
                      mcGridCellNav
                      [(ngModel)]="openingMfgDate"
                    />
                  </div>
                }

                @if (useExpiryDates) {
                  <div>
                    <label class="mc-field-label" for="si-open-exp">Expiry Date</label>
                    <input
                      id="si-open-exp"
                      type="date"
                      name="openingExpiryDate"
                      class="mc-input mc-code"
                      mcGridCellNav
                      [(ngModel)]="openingExpiryDate"
                    />
                  </div>
                }
              </div>
            }
          </div>

          <footer class="mc-si-footer">
            <span class="mc-si-hint">
              Enter = Next Field &bull; Alt+C = Create Group / UOM / Godown &bull; Ctrl+A = Accept
            </span>
            <div class="mc-si-btns">
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
                type="submit"
                class="mc-btn mc-btn-primary"
                data-testid="stock-item-save-btn"
                [disabled]="saving() || companyContext.quarantined()"
              >
                Accept (<span class="mc-key-ctrl">A</span>)
              </button>
            </div>
          </footer>
        </form>
      </section>
    </div>

    <!-- Inline Set / Alter Components (BOM) Modal -->
    @if (bomModalOpen()) {
      <div
        class="mc-modal-backdrop"
        role="dialog"
        aria-modal="true"
        aria-labelledby="mc-bom-modal-title"
        (keydown.escape)="closeBomModal()"
      >
        <div class="mc-card mc-bom-modal" data-mc-nav-container>
          <header class="mc-si-card-head">
            <span id="mc-bom-modal-title">
              Stock Item Multi-Component List (BOM) — {{ name || 'New Finished Good' }}
            </span>
            <button type="button" class="mc-btn" (click)="closeBomModal()">Esc Close</button>
          </header>

          @if (bomErrorMsg(); as bErr) {
            <div class="mc-si-banner mc-si-banner-err" role="alert">{{ bErr }}</div>
          }

          <div class="mc-bom-body">
            @if (activeItemBoms().length > 0) {
              <div class="mc-bom-pills">
                @for (b of activeItemBoms(); track b.id) {
                  <button
                    type="button"
                    class="mc-btn"
                    [class.mc-btn-primary]="editingBomId() === b.id"
                    (click)="loadBomIntoModal(b)"
                  >
                    {{ b.bomName }} ({{ b.components.length }} comp)
                  </button>
                }
                <button type="button" class="mc-btn" (click)="resetBomModalFields()">
                  + New BOM Variant
                </button>
              </div>
            }

            <div class="mc-si-row-2">
              <div>
                <label class="mc-field-label" for="bom-name">Name of BOM</label>
                <input
                  id="bom-name"
                  type="text"
                  name="bomName"
                  class="mc-input"
                  data-testid="bom-name-input"
                  mcGridCellNav
                  [(ngModel)]="bomName"
                  placeholder="Standard Assembly BOM"
                />
              </div>

              <div>
                <label class="mc-field-label" for="bom-uom-qty">
                  Unit of Manufacture ({{ selectedBaseSymbol() }})
                </label>
                <input
                  id="bom-uom-qty"
                  type="text"
                  name="unitOfManufacture"
                  class="mc-input mc-amount"
                  data-testid="bom-unit-qty-input"
                  mcGridCellNav
                  [(ngModel)]="unitOfManufacture"
                  placeholder="1"
                />
              </div>
            </div>

            <div class="mc-bom-table-head">
              <strong>Components / Co-Products / Scrap</strong>
              <button
                type="button"
                class="mc-btn"
                data-testid="bom-add-component-btn"
                (click)="addBomComponentRow()"
              >
                + Add Component Row
              </button>
            </div>

            <table class="mc-table mc-bom-table">
              <thead>
                <tr>
                  <th>Role</th>
                  <th>Item (<span class="mc-key-alt">C</span> = Create)</th>
                  <th>Godown</th>
                  <th class="mc-text-right">Quantity</th>
                  <th class="mc-text-right">Cost %</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                @for (row of bomComponents(); track row.rowKey; let rIdx = $index) {
                  <tr>
                    <td>
                      <select
                        class="mc-select"
                        [name]="'bomRole' + row.rowKey"
                        mcGridCellNav
                        [(ngModel)]="row.componentType"
                      >
                        <option value="COMPONENT">Component</option>
                        <option value="CO_PRODUCT">Co-Product</option>
                        <option value="BY_PRODUCT">By-Product</option>
                        <option value="SCRAP">Scrap</option>
                      </select>
                    </td>
                    <td>
                      <mc-typeahead-select
                        [inputId]="'bom-item-' + row.rowKey"
                        listTitle="List of Stock Items"
                        [options]="inventoryStore.stockItemOptions()"
                        [selectedId]="row.stockItemId || null"
                        [allowInlineCreate]="true"
                        (optionSelected)="row.stockItemId = $event.id"
                        (createRequested)="openInlineBomItemCreate(rIdx, $event)"
                      />
                    </td>
                    <td>
                      <select
                        class="mc-select"
                        [name]="'bomGodown' + row.rowKey"
                        mcGridCellNav
                        [ngModel]="row.godownId ?? ''"
                        (ngModelChange)="row.godownId = $event || null"
                      >
                        <option value="">Default / Main</option>
                        @for (g of inventoryStore.godowns(); track g.id) {
                          <option [value]="g.id">{{ g.name }}</option>
                        }
                      </select>
                    </td>
                    <td>
                      <input
                        type="text"
                        class="mc-input mc-amount"
                        [attr.data-testid]="'bom-comp-qty-' + rIdx"
                        [name]="'bomQty' + row.rowKey"
                        mcGridCellNav
                        [(ngModel)]="row.quantity"
                        placeholder="1"
                      />
                    </td>
                    <td>
                      <input
                        type="text"
                        class="mc-input mc-amount"
                        [name]="'bomCostPct' + row.rowKey"
                        mcGridCellNav
                        [(ngModel)]="row.costAllocationPct"
                        placeholder="0"
                      />
                    </td>
                    <td>
                      <button
                        type="button"
                        class="mc-btn"
                        (click)="removeBomComponentRow(row.rowKey)"
                      >
                        &times;
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>

            <div class="mc-bom-table-head">
              <strong>Type of Additional Cost (Power, Direct Wages, Overheads)</strong>
              <button type="button" class="mc-btn" (click)="addBomCostRuleRow()">
                + Add Cost Rule
              </button>
            </div>

            @if (bomCostRules().length > 0) {
              <table class="mc-table mc-bom-table">
                <thead>
                  <tr>
                    <th>Expense / Overhead Ledger</th>
                    <th>Calculation Basis</th>
                    <th class="mc-text-right">% or Fixed Amount</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  @for (rule of bomCostRules(); track rule.rowKey) {
                    <tr>
                      <td>
                        <mc-typeahead-select
                          [inputId]="'bom-rule-led-' + rule.rowKey"
                          listTitle="List of Expense Ledgers"
                          [options]="expenseLedgerOptions()"
                          [selectedId]="rule.ledgerId || null"
                          [allowInlineCreate]="false"
                          (optionSelected)="rule.ledgerId = $event.id"
                        />
                      </td>
                      <td>
                        <select
                          class="mc-select"
                          [name]="'bomRuleBasis' + rule.rowKey"
                          mcGridCellNav
                          [(ngModel)]="rule.calculationBasis"
                        >
                          <option value="PERCENTAGE">Percentage (%)</option>
                          <option value="FIXED_AMOUNT">Fixed Amount (₹)</option>
                        </select>
                      </td>
                      <td>
                        <input
                          type="text"
                          class="mc-input mc-amount"
                          [name]="'bomRuleVal' + rule.rowKey"
                          mcGridCellNav
                          [(ngModel)]="rule.rateOrAmount"
                          placeholder="5.00"
                        />
                      </td>
                      <td>
                        <button
                          type="button"
                          class="mc-btn"
                          (click)="removeBomCostRuleRow(rule.rowKey)"
                        >
                          &times;
                        </button>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          </div>

          <footer class="mc-bom-footer">
            <span class="mc-si-hint">
              @if (editingId()) {
                Persists directly to /boms for {{ name }}
              } @else {
                Staged for automatic /boms creation when {{ name || 'this Stock Item' }} is accepted
              }
            </span>
            <div class="mc-si-btns">
              <button type="button" class="mc-btn" (click)="closeBomModal()">Cancel</button>
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="bom-save-btn"
                [disabled]="savingBom()"
                (click)="saveBomFromModal()"
              >
                Save BOM
              </button>
            </div>
          </footer>
        </div>
      </div>
    }
  `,
  styles: `
    .mc-si-wb-grid {
      display: grid;
      grid-template-columns: minmax(270px, 340px) minmax(0, 1fr);
      gap: var(--mc-space-4);
      align-items: start;
    }

    @media (max-width: 980px) {
      .mc-si-wb-grid {
        grid-template-columns: 1fr;
      }
    }

    .mc-si-card-head {
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      padding: 0.5rem 0.875rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-2);
      font-weight: 700;
    }

    .mc-si-head-actions {
      display: flex;
      gap: var(--mc-space-2);
      align-items: center;
    }

    .mc-si-list-card,
    .mc-si-form-card {
      overflow: visible;
    }

    .mc-si-search-wrap {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-canvas);
      border-bottom: var(--mc-border-subtle);
    }

    .mc-si-list {
      list-style: none;
      margin: 0;
      padding: 0;
      max-height: 68vh;
      overflow-y: auto;
    }

    .mc-si-list-row {
      padding: 0.5rem 0.85rem;
      border-bottom: 1px solid var(--mc-color-slate-100);
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-2);
      cursor: pointer;
    }

    .mc-si-list-row-active {
      background: var(--mc-color-teal-50);
    }

    .mc-si-row-main {
      display: flex;
      flex-direction: column;
      gap: 2px;
      font-weight: 600;
    }

    .mc-si-row-sub {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-si-row-badges {
      display: flex;
      gap: 4px;
      align-items: center;
      flex-shrink: 0;
    }

    .mc-si-empty {
      padding: var(--mc-space-4);
      text-align: center;
      color: var(--mc-color-slate-600);
      font-size: var(--mc-font-size-xs);
      font-weight: 500;
    }

    .mc-si-banner {
      margin: var(--mc-space-3) var(--mc-space-4) 0;
      padding: var(--mc-space-2) var(--mc-space-3);
      border-radius: var(--mc-radius-xs);
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
    }

    .mc-si-banner-err {
      background: #fef2f2;
      color: #991b1b;
      border: 1px solid #fecaca;
    }

    .mc-si-banner-ok {
      background: var(--mc-color-teal-50);
      color: var(--mc-color-pressed-teal);
      border: 1px solid var(--mc-color-teal-100);
    }

    .mc-si-form {
      padding: var(--mc-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-si-subbox {
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-si-subhead {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      color: var(--mc-color-slate-900);
      text-transform: uppercase;
      letter-spacing: 0.03em;
    }

    .mc-si-row-2 {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    .mc-si-row-3 {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    .mc-si-row-4 {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: var(--mc-space-3);
      align-items: end;
    }

    @media (max-width: 920px) {
      .mc-si-row-3,
      .mc-si-row-4 {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }

    .mc-si-checks {
      display: flex;
      flex-wrap: wrap;
      gap: var(--mc-space-4);
    }

    .mc-si-check {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      font-size: var(--mc-font-size-sm);
      font-weight: 600;
      cursor: pointer;
    }

    .mc-si-check-inline {
      padding-bottom: 0.35rem;
    }

    .mc-si-footer {
      border-top: var(--mc-border-subtle);
      padding-top: var(--mc-space-3);
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .mc-si-hint {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-si-btns {
      display: flex;
      gap: var(--mc-space-2);
    }

    .mc-bom-modal {
      width: min(920px, 95vw);
      max-height: 88vh;
      display: flex;
      flex-direction: column;
      border: 2px solid var(--mc-color-slate-900);
      overflow: hidden;
    }

    .mc-bom-body {
      padding: var(--mc-space-4);
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-bom-pills {
      display: flex;
      flex-wrap: wrap;
      gap: var(--mc-space-2);
    }

    .mc-bom-table-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-top: var(--mc-space-2);
      border-top: var(--mc-border-subtle);
      font-weight: 700;
    }

    .mc-bom-table {
      width: 100%;
    }

    .mc-text-right {
      text-align: right;
    }

    .mc-bom-footer {
      padding: var(--mc-space-3) var(--mc-space-4);
      background: var(--mc-color-canvas);
      border-top: var(--mc-border-subtle);
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
  `,
})
export class StockItemWorkbenchComponent implements OnInit {
  readonly embeddedOverlay = input<boolean>(false);
  readonly initialItemId = input<string | null>(null);

  readonly inventoryStore = inject(InventoryStore);
  readonly coaStore = inject(ChartOfAccountsStore);
  readonly companyContext = inject(CompanyContextStore);
  private readonly secondaryModal = inject(SecondaryMasterModalService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly navStack = inject(NavigationStackStore);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  private readonly nameInputRef = viewChild<ElementRef<HTMLInputElement>>('nameInput');

  readonly costingMethodOptions = STOCK_COSTING_METHOD_OPTIONS;
  readonly marketValuationOptions = MARKET_VALUATION_METHOD_OPTIONS;

  readonly searchQuery = signal<string>('');
  readonly editingId = signal<string | null>(null);
  readonly editingVersionNo = signal<number>(1);
  readonly saving = signal<boolean>(false);
  readonly errorMsg = signal<string | null>(null);
  readonly savedMsg = signal<string | null>(null);

  // Primary Stock Item Fields
  name = '';
  aliasCode = '';
  partNo = '';
  description = '';
  readonly stockGroupId = signal<string | null>(null);
  readonly stockCategoryId = signal<string | null>(null);
  readonly baseUomId = signal<string | null>(null);
  readonly alternateUomId = signal<string | null>(null);
  conversionNumerator = '10';
  conversionDenominator = '1';

  readonly maintainInBatches = signal<boolean>(false);
  trackDateOfMfg = false;
  useExpiryDates = false;

  costingMethod: StockCostingMethod = 'AVG_COST';
  marketValuationMethod: MarketValuationMethod = 'AVG_PRICE';
  standardCost = '';
  standardSellingPrice = '';

  gstApplicability: GstApplicability = 'APPLICABLE';
  gstSupplyType: GstSupplyType = 'GOODS';
  hsnSacCode = '';
  gstRatePct = '';
  cessRatePct = '';
  cessPerUnit = '';
  readonly isTobaccoMrpItem = signal<boolean>(false);
  mrpPerUnit = '';

  readonly defaultSalesLedgerId = signal<string | null>(null);
  readonly defaultPurchaseLedgerId = signal<string | null>(null);

  readonly openingQty = signal<string>('');
  readonly openingRate = signal<string>('');
  readonly openingValue = signal<string>('');
  readonly openingGodownId = signal<string | null>(null);
  openingBatchName = '';
  openingMfgDate = '';
  openingExpiryDate = '';

  // Inline BOM Editor State
  readonly bomModalOpen = signal<boolean>(false);
  readonly editingBomId = signal<string | null>(null);
  readonly editingBomVersion = signal<number>(1);
  readonly savingBom = signal<boolean>(false);
  readonly bomErrorMsg = signal<string | null>(null);
  readonly pendingBomDraft = signal<Omit<UpsertBomRequest, 'stockItemId'> | null>(null);

  bomName = 'Standard BOM';
  unitOfManufacture = '1';
  readonly bomComponents = signal<EditableBomComponentRow[]>([]);
  readonly bomCostRules = signal<EditableBomCostRuleRow[]>([]);
  private nextBomRowKey = 1;

  readonly filteredItems = computed<readonly StockItemMaster[]>(() => {
    const q = this.searchQuery().trim().toLowerCase();
    const list = this.inventoryStore.stockItems();
    if (!q) {
      return list;
    }
    return list.filter(
      (i) =>
        i.name.toLowerCase().includes(q) ||
        (i.aliasCode?.toLowerCase().includes(q) ?? false) ||
        (i.partNo?.toLowerCase().includes(q) ?? false) ||
        (i.hsnSacCode?.toLowerCase().includes(q) ?? false) ||
        (i.stockGroupName?.toLowerCase().includes(q) ?? false),
    );
  });

  readonly bomsCountByItem = computed<ReadonlyMap<string, number>>(() => {
    const map = new Map<string, number>();
    for (const b of this.inventoryStore.boms()) {
      map.set(b.stockItemId, (map.get(b.stockItemId) ?? 0) + 1);
    }
    return map;
  });

  readonly activeItemBoms = computed<readonly BomMaster[]>(() => {
    const id = this.editingId();
    if (!id) return [];
    return this.inventoryStore.boms().filter((b) => b.stockItemId === id);
  });

  readonly selectedBaseSymbol = computed<string>(() => {
    const id = this.baseUomId();
    return this.inventoryStore.uoms().find((u) => u.id === id)?.symbol ?? 'Unit';
  });

  readonly selectedAltSymbol = computed<string>(() => {
    const id = this.alternateUomId();
    return this.inventoryStore.uoms().find((u) => u.id === id)?.symbol ?? 'Alt';
  });

  readonly alternateUomCandidates = computed<readonly TypeaheadOption[]>(() => {
    const baseId = this.baseUomId();
    return [
      { id: '__NONE__', label: 'Not Applicable' },
      ...this.inventoryStore.uomOptions().filter((o) => o.id !== baseId),
    ];
  });

  readonly openingGodownOptions = computed<readonly TypeaheadOption[]>(() => [
    { id: '__NONE__', label: 'Main Location (Default)' },
    ...this.inventoryStore.godownOptions(),
  ]);

  readonly salesLedgerOptions = computed<readonly TypeaheadOption[]>(() => [
    { id: '__NONE__', label: 'Not Specified' },
    ...this.coaStore.ledgers().map((l) => ({
      id: l.id,
      label: l.name,
      secondaryLabel: l.groupName ?? undefined,
    })),
  ]);

  readonly purchaseLedgerOptions = computed<readonly TypeaheadOption[]>(() => [
    { id: '__NONE__', label: 'Not Specified' },
    ...this.coaStore.ledgers().map((l) => ({
      id: l.id,
      label: l.name,
      secondaryLabel: l.groupName ?? undefined,
    })),
  ]);

  readonly expenseLedgerOptions = computed<readonly TypeaheadOption[]>(() =>
    this.coaStore.ledgers().map((l) => ({
      id: l.id,
      label: l.name,
      secondaryLabel: l.groupName ?? undefined,
    })),
  );

  constructor() {
    effect(() => {
      const cid = this.companyContext.activeCompany()?.id;
      if (cid) {
        void this.inventoryStore.ensureLoaded(cid);
        void this.coaStore.ensureAccountingMastersLoaded(cid);
      }
    });

    effect(() => {
      const uoms = this.inventoryStore.uoms();
      if (!this.baseUomId() && uoms.length > 0) {
        this.baseUomId.set(uoms[0].id);
      }
    });
  }

  ngOnInit(): void {
    void this.inventoryStore.ensureLoaded().then(() => {
      const targetId =
        this.initialItemId() ?? this.route.snapshot.queryParamMap.get('stockItemId');
      if (targetId) {
        const found = this.inventoryStore.stockItems().find((i) => i.id === targetId);
        if (found) {
          this.selectForAlter(found);
        }
      }
    });
    void this.coaStore.ensureAccountingMastersLoaded();

    const dispose = this.shortcutRegistry.bindHandler(
      'screen.accept',
      'SCREEN',
      () => {
        if (this.bomModalOpen()) {
          void this.saveBomFromModal();
        } else {
          void this.saveStockItem();
        }
      },
      () =>
        !this.secondaryModal.isOpen() &&
        (!this.navStack.hasOverlays() || this.embeddedOverlay()),
    );
    this.destroyRef.onDestroy(dispose);
  }

  onStockGroupSelected(opt: TypeaheadOption): void {
    const gid = opt.id === '__PRIMARY__' ? null : opt.id;
    this.stockGroupId.set(gid);
    if (gid) {
      const grp = this.inventoryStore.stockGroups().find((g) => g.id === gid);
      if (grp) {
        if (!this.hsnSacCode.trim() && grp.hsnSacCode) {
          this.hsnSacCode = grp.hsnSacCode;
        }
        if (!this.gstRatePct.trim() && grp.gstRatePct) {
          this.gstRatePct = grp.gstRatePct;
        }
      }
    }
  }

  onAlternateUomSelected(opt: TypeaheadOption): void {
    if (opt.id === '__NONE__') {
      this.clearAlternateUom();
    } else {
      this.alternateUomId.set(opt.id);
    }
  }

  clearAlternateUom(): void {
    this.alternateUomId.set(null);
    this.conversionNumerator = '10';
    this.conversionDenominator = '1';
  }

  onMaintainInBatchesChange(enabled: boolean): void {
    this.maintainInBatches.set(enabled);
    if (!enabled) {
      this.trackDateOfMfg = false;
      this.useExpiryDates = false;
    }
  }

  onOpeningQtyOrRateChange(nextQty: string, nextRate: string): void {
    this.openingQty.set(nextQty);
    this.openingRate.set(nextRate);
    const qtyDec = ExactDecimal.tryParse(nextQty);
    const rateDec = ExactDecimal.tryParse(nextRate);
    if (qtyDec && rateDec && !qtyDec.isZero() && !rateDec.isZero()) {
      const scale = this.companyContext.currencyScale();
      this.openingValue.set(qtyDec.mulScaled(rateDec, scale, 'HALF_UP').toWireString(scale));
    } else if (!nextQty.trim() || !nextRate.trim()) {
      this.openingValue.set('');
    }
  }

  openInlineStockGroupCreate(initialName: string): void {
    void this.secondaryModal.open({
      kind: 'STOCK_GROUP',
      initialName,
      onCreated: (created) => {
        this.stockGroupId.set(created.id);
      },
    });
  }

  openInlineUomCreate(initialSymbol: string, target: 'BASE' | 'ALT'): void {
    void this.secondaryModal.open({
      kind: 'UOM',
      initialName: initialSymbol,
      onCreated: (created) => {
        if (target === 'BASE') {
          this.baseUomId.set(created.id);
        } else {
          this.alternateUomId.set(created.id);
        }
      },
    });
  }

  openInlineGodownCreate(initialName: string): void {
    void this.secondaryModal.open({
      kind: 'GODOWN',
      initialName,
      onCreated: (created) => {
        this.openingGodownId.set(created.id);
      },
    });
  }

  openInlineBomItemCreate(rowIndex: number, initialName: string): void {
    void this.secondaryModal.open({
      kind: 'STOCK_ITEM',
      initialName,
      defaultBaseUomId: this.baseUomId(),
      onCreated: (created) => {
        const rows = [...this.bomComponents()];
        if (rows[rowIndex]) {
          rows[rowIndex] = { ...rows[rowIndex], stockItemId: created.id };
          this.bomComponents.set(rows);
        }
      },
    });
  }

  resetToCreate(): void {
    this.editingId.set(null);
    this.editingVersionNo.set(1);
    this.name = '';
    this.aliasCode = '';
    this.partNo = '';
    this.description = '';
    this.stockGroupId.set(null);
    this.stockCategoryId.set(null);
    this.baseUomId.set(this.inventoryStore.uoms()[0]?.id ?? null);
    this.alternateUomId.set(null);
    this.conversionNumerator = '10';
    this.conversionDenominator = '1';
    this.maintainInBatches.set(false);
    this.trackDateOfMfg = false;
    this.useExpiryDates = false;
    this.costingMethod = 'AVG_COST';
    this.marketValuationMethod = 'AVG_PRICE';
    this.standardCost = '';
    this.standardSellingPrice = '';
    this.gstApplicability = 'APPLICABLE';
    this.gstSupplyType = 'GOODS';
    this.hsnSacCode = '';
    this.gstRatePct = '';
    this.cessRatePct = '';
    this.cessPerUnit = '';
    this.isTobaccoMrpItem.set(false);
    this.mrpPerUnit = '';
    this.defaultSalesLedgerId.set(null);
    this.defaultPurchaseLedgerId.set(null);
    this.openingQty.set('');
    this.openingRate.set('');
    this.openingValue.set('');
    this.openingGodownId.set(null);
    this.openingBatchName = '';
    this.openingMfgDate = '';
    this.openingExpiryDate = '';
    this.pendingBomDraft.set(null);
    this.errorMsg.set(null);
    queueMicrotask(() => this.nameInputRef()?.nativeElement.focus());
  }

  selectForAlter(item: StockItemMaster): void {
    this.editingId.set(item.id);
    this.editingVersionNo.set(item.versionNo);
    this.name = item.name;
    this.aliasCode = item.aliasCode ?? '';
    this.partNo = item.partNo ?? '';
    this.description = item.description ?? '';
    this.stockGroupId.set(item.stockGroupId);
    this.stockCategoryId.set(item.stockCategoryId);
    this.baseUomId.set(item.baseUomId);
    this.alternateUomId.set(item.alternateUomId);
    this.conversionNumerator = item.conversionNumerator ?? '10';
    this.conversionDenominator = item.conversionDenominator ?? '1';
    this.maintainInBatches.set(item.maintainInBatches);
    this.trackDateOfMfg = item.trackDateOfMfg;
    this.useExpiryDates = item.useExpiryDates;
    this.costingMethod = item.costingMethod;
    this.marketValuationMethod = item.marketValuationMethod;
    this.standardCost = item.standardCost ?? '';
    this.standardSellingPrice = item.standardSellingPrice ?? '';
    this.gstApplicability = item.gstApplicability;
    this.gstSupplyType = item.gstSupplyType ?? 'GOODS';
    this.hsnSacCode = item.hsnSacCode ?? '';
    this.gstRatePct = item.gstRatePct ?? '';
    this.cessRatePct = item.cessRatePct ?? '';
    this.cessPerUnit = item.cessPerUnit ?? '';
    this.isTobaccoMrpItem.set(item.isTobaccoMrpItem);
    this.mrpPerUnit = item.mrpPerUnit ?? '';
    this.defaultSalesLedgerId.set(item.defaultSalesLedgerId);
    this.defaultPurchaseLedgerId.set(item.defaultPurchaseLedgerId);
    this.openingQty.set(item.openingQty ?? '');
    this.openingRate.set(item.openingRate ?? '');
    this.openingValue.set(item.openingValue ?? '');
    this.openingGodownId.set(item.openingGodownId);
    this.openingBatchName = item.openingBatchName ?? '';
    this.openingMfgDate = item.openingMfgDate ?? '';
    this.openingExpiryDate = item.openingExpiryDate ?? '';
    this.pendingBomDraft.set(null);
    this.errorMsg.set(null);
    this.savedMsg.set(null);
  }

  async saveStockItem(): Promise<void> {
    if (this.saving()) return;
    const cleanName = this.name.trim();
    if (!cleanName) {
      this.errorMsg.set('Stock Item Name is required.');
      return;
    }
    const uomId = this.baseUomId();
    if (!uomId) {
      this.errorMsg.set('Base Unit (UOM) is required. Press Alt+C in Units to create one.');
      return;
    }
    if (this.isTobaccoMrpItem() && !this.mrpPerUnit.trim()) {
      this.errorMsg.set('MRP / Unit is required when Tobacco / Retail Sale Price item is enabled.');
      return;
    }

    const payload: UpsertStockItemRequest = {
      name: cleanName,
      aliasCode: this.aliasCode.trim() || null,
      partNo: this.partNo.trim() || null,
      description: this.description.trim() || null,
      stockGroupId: this.stockGroupId(),
      stockCategoryId: this.stockCategoryId(),
      baseUomId: uomId,
      alternateUomId: this.alternateUomId(),
      conversionNumerator: this.alternateUomId() ? this.conversionNumerator.trim() || '1' : null,
      conversionDenominator: this.alternateUomId()
        ? this.conversionDenominator.trim() || '1'
        : null,
      maintainInBatches: this.maintainInBatches(),
      trackDateOfMfg: this.maintainInBatches() ? this.trackDateOfMfg : false,
      useExpiryDates: this.maintainInBatches() ? this.useExpiryDates : false,
      costingMethod: this.costingMethod,
      marketValuationMethod: this.marketValuationMethod,
      standardCost: this.standardCost.trim() || null,
      standardSellingPrice: this.standardSellingPrice.trim() || null,
      openingQty: this.openingQty().trim() || null,
      openingRate: this.openingRate().trim() || null,
      openingValue: this.openingValue().trim() || null,
      openingGodownId: this.openingGodownId(),
      openingBatchName:
        this.maintainInBatches() && this.openingBatchName.trim()
          ? this.openingBatchName.trim()
          : null,
      openingMfgDate:
        this.maintainInBatches() && this.trackDateOfMfg && this.openingMfgDate
          ? this.openingMfgDate
          : null,
      openingExpiryDate:
        this.maintainInBatches() && this.useExpiryDates && this.openingExpiryDate
          ? this.openingExpiryDate
          : null,
      gstApplicability: this.gstApplicability,
      gstSupplyType: this.gstSupplyType,
      hsnSacCode: this.hsnSacCode.trim() || null,
      gstRatePct: this.gstRatePct.trim() || null,
      cessRatePct: this.cessRatePct.trim() || null,
      cessPerUnit: this.cessPerUnit.trim() || null,
      isTobaccoMrpItem: this.isTobaccoMrpItem(),
      mrpPerUnit: this.isTobaccoMrpItem() ? this.mrpPerUnit.trim() || null : null,
      defaultSalesLedgerId: this.defaultSalesLedgerId(),
      defaultPurchaseLedgerId: this.defaultPurchaseLedgerId(),
    };

    this.saving.set(true);
    this.errorMsg.set(null);
    this.savedMsg.set(null);
    try {
      const existingId = this.editingId();
      if (existingId) {
        const updated = await this.inventoryStore.updateStockItem(
          existingId,
          payload,
          this.editingVersionNo(),
        );
        this.editingVersionNo.set(updated.versionNo);
        this.savedMsg.set(`Updated Stock Item "${updated.name}".`);
      } else {
        const created = await this.inventoryStore.createStockItem(payload);
        const stagedBom = this.pendingBomDraft();
        if (stagedBom) {
          await this.inventoryStore.createBom({
            ...stagedBom,
            stockItemId: created.id,
          });
          this.pendingBomDraft.set(null);
        }
        this.resetToCreate();
        this.savedMsg.set(`Created Stock Item "${created.name}".`);
      }
    } catch (err: unknown) {
      this.errorMsg.set(err instanceof Error ? err.message : 'Unable to save Stock Item.');
    } finally {
      this.saving.set(false);
    }
  }

  async deleteCurrent(): Promise<void> {
    const id = this.editingId();
    if (!id || this.saving()) return;
    this.saving.set(true);
    try {
      await this.inventoryStore.deleteStockItem(id);
      this.resetToCreate();
      this.savedMsg.set('Deleted Stock Item.');
    } catch (err: unknown) {
      this.errorMsg.set(err instanceof Error ? err.message : 'Unable to delete Stock Item.');
    } finally {
      this.saving.set(false);
    }
  }

  // --- Inline BOM Modal Methods ---

  openBomModal(): void {
    this.bomErrorMsg.set(null);
    const existingBoms = this.activeItemBoms();
    if (existingBoms.length > 0) {
      this.loadBomIntoModal(existingBoms[0]);
    } else if (this.pendingBomDraft()) {
      const draft = this.pendingBomDraft()!;
      this.editingBomId.set(null);
      this.bomName = draft.bomName;
      this.unitOfManufacture = draft.unitOfManufacture;
      this.bomComponents.set(
        draft.components.map((c) => ({
          rowKey: this.nextBomRowKey++,
          componentType: c.componentType ?? 'COMPONENT',
          stockItemId: c.stockItemId,
          godownId: c.godownId ?? null,
          quantity: c.quantity,
          costAllocationPct: c.costAllocationPct ?? '',
        })),
      );
      this.bomCostRules.set(
        draft.additionalCostRules.map((r) => ({
          rowKey: this.nextBomRowKey++,
          ledgerId: r.ledgerId,
          calculationBasis: r.calculationBasis,
          rateOrAmount: r.rateOrAmount,
        })),
      );
    } else {
      this.resetBomModalFields();
    }
    this.bomModalOpen.set(true);
  }

  closeBomModal(): void {
    this.bomModalOpen.set(false);
  }

  resetBomModalFields(): void {
    this.editingBomId.set(null);
    this.editingBomVersion.set(1);
    this.bomName = this.name.trim() ? `${this.name.trim()} Standard BOM` : 'Standard BOM';
    this.unitOfManufacture = '1';
    this.bomComponents.set([
      {
        rowKey: this.nextBomRowKey++,
        componentType: 'COMPONENT',
        stockItemId: '',
        godownId: null,
        quantity: '1',
        costAllocationPct: '',
      },
    ]);
    this.bomCostRules.set([]);
    this.bomErrorMsg.set(null);
  }

  loadBomIntoModal(bom: BomMaster): void {
    this.editingBomId.set(bom.id);
    this.editingBomVersion.set(bom.versionNo);
    this.bomName = bom.bomName;
    this.unitOfManufacture = bom.unitOfManufacture;
    this.bomComponents.set(
      bom.components.map((c) => ({
        rowKey: this.nextBomRowKey++,
        componentType: c.componentType ?? 'COMPONENT',
        stockItemId: c.stockItemId,
        godownId: c.godownId ?? null,
        quantity: c.quantity,
        costAllocationPct: c.costAllocationPct ?? '',
      })),
    );
    this.bomCostRules.set(
      bom.additionalCostRules.map((r) => ({
        rowKey: this.nextBomRowKey++,
        ledgerId: r.ledgerId,
        calculationBasis: r.calculationBasis,
        rateOrAmount: r.rateOrAmount,
      })),
    );
    this.bomErrorMsg.set(null);
  }

  addBomComponentRow(): void {
    this.bomComponents.set([
      ...this.bomComponents(),
      {
        rowKey: this.nextBomRowKey++,
        componentType: 'COMPONENT',
        stockItemId: '',
        godownId: null,
        quantity: '1',
        costAllocationPct: '',
      },
    ]);
  }

  removeBomComponentRow(rowKey: number): void {
    const remaining = this.bomComponents().filter((r) => r.rowKey !== rowKey);
    this.bomComponents.set(remaining);
  }

  addBomCostRuleRow(): void {
    this.bomCostRules.set([
      ...this.bomCostRules(),
      {
        rowKey: this.nextBomRowKey++,
        ledgerId: '',
        calculationBasis: 'PERCENTAGE',
        rateOrAmount: '0',
      },
    ]);
  }

  removeBomCostRuleRow(rowKey: number): void {
    this.bomCostRules.set(this.bomCostRules().filter((r) => r.rowKey !== rowKey));
  }

  async saveBomFromModal(): Promise<void> {
    if (this.savingBom()) return;
    const cleanBomName = this.bomName.trim();
    if (!cleanBomName) {
      this.bomErrorMsg.set('BOM Name is required.');
      return;
    }

    const validComponents: BomComponentDto[] = this.bomComponents()
      .filter((c) => c.stockItemId && (ExactDecimal.tryParse(c.quantity)?.isPositive() ?? false))
      .map((c) => ({
        stockItemId: c.stockItemId,
        componentType: c.componentType,
        godownId: c.godownId,
        quantity: c.quantity.trim(),
        costAllocationPct: c.costAllocationPct.trim() || null,
      }));

    if (validComponents.length === 0) {
      this.bomErrorMsg.set('Add at least one component Stock Item with a positive quantity.');
      return;
    }

    const validRules: BomAdditionalCostRuleDto[] = this.bomCostRules()
      .filter((r) => r.ledgerId && r.rateOrAmount.trim().length > 0)
      .map((r) => ({
        ledgerId: r.ledgerId,
        calculationBasis: r.calculationBasis,
        rateOrAmount: r.rateOrAmount.trim(),
      }));

    const itemId = this.editingId();
    if (!itemId) {
      this.pendingBomDraft.set({
        bomName: cleanBomName,
        unitOfManufacture: this.unitOfManufacture.trim() || '1',
        components: validComponents,
        additionalCostRules: validRules,
      });
      this.bomModalOpen.set(false);
      this.savedMsg.set(
        `BOM "${cleanBomName}" staged (${validComponents.length} components) — press Ctrl+A to save Stock Item + BOM.`,
      );
      return;
    }

    this.savingBom.set(true);
    this.bomErrorMsg.set(null);
    try {
      const payload: UpsertBomRequest = {
        stockItemId: itemId,
        bomName: cleanBomName,
        unitOfManufacture: this.unitOfManufacture.trim() || '1',
        components: validComponents,
        additionalCostRules: validRules,
      };
      const bomId = this.editingBomId();
      if (bomId) {
        await this.inventoryStore.updateBom(bomId, payload, this.editingBomVersion());
        this.savedMsg.set(`Updated BOM "${cleanBomName}".`);
      } else {
        await this.inventoryStore.createBom(payload);
        this.savedMsg.set(`Created BOM "${cleanBomName}".`);
      }
      this.bomModalOpen.set(false);
    } catch (err: unknown) {
      this.bomErrorMsg.set(err instanceof Error ? err.message : 'Unable to save BOM.');
    } finally {
      this.savingBom.set(false);
    }
  }
}
