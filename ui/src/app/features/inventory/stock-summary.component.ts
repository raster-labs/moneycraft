import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { isEditableTextElement } from '../../core/keyboard/collision-guard';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { ExactDecimal } from '../../core/money/exact-decimal';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import {
  BatchAgeingReportDto,
  GodownSummaryReportDto,
  MovementAnalysisReportDto,
  MovementAnalysisVoucherRowDto,
  STOCK_COSTING_METHOD_OPTIONS,
  StockCostingMethod,
  StockSummaryGroupNodeDto,
  StockSummaryItemDto,
  StockSummaryReportDto,
} from './inventory.models';
import { InventoryService } from './inventory.service';

export type InventoryReportTab =
  | 'STOCK_SUMMARY'
  | 'GODOWN_SUMMARY'
  | 'BATCH_AGEING'
  | 'MOVEMENT_ANALYSIS';

export interface GroupedStockSummarySection {
  readonly group: StockSummaryGroupNodeDto | null;
  readonly groupTitle: string;
  readonly items: readonly StockSummaryItemDto[];
}

@Component({
  selector: 'mc-stock-summary',
  standalone: true,
  imports: [FormsModule, RouterLink, CodeBadgeComponent, IndianCurrencyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-stocksumm-shell" [class.mc-stocksumm-embedded]="embeddedOverlay()">
      <!-- Header & Action Toolbar -->
      <header class="mc-stocksumm-header mc-card">
        <div class="mc-stocksumm-title-group">
          <div class="mc-stocksumm-heading-row">
            <h1 class="mc-stocksumm-title">{{ activeHeaderTitle() }}</h1>
            <mc-code-badge [code]="periodBadgeText()" />
            @if (valuationMethodOverride(); as methodOverride) {
              <span class="mc-badge mc-badge-jade" data-testid="stock-summary-valuation-badge">
                Valuation: {{ formatCostingMethod(methodOverride) }}
              </span>
            } @else {
              <span class="mc-badge mc-badge-slate" data-testid="stock-summary-valuation-badge">
                Valuation: Master Default
              </span>
            }
            @if (detailedMode() && activeTab() === 'STOCK_SUMMARY') {
              <span class="mc-badge mc-badge-jade" data-testid="stock-summary-detailed-badge">
                Detailed
              </span>
            }
          </div>
          <p class="mc-stocksumm-subtitle">
            @switch (activeTab()) {
              @case ('STOCK_SUMMARY') {
                Stock Group &rarr; Stock Item valuation register &bull; Enter drills into Item Movement Analysis &bull; Ctrl+B switches costing basis.
              }
              @case ('MOVEMENT_ANALYSIS') {
                Stock Item Inward / Outward voucher movement register &bull; Enter opens voucher &bull; Backspace returns to Stock Summary.
              }
              @case ('GODOWN_SUMMARY') {
                Warehouse / Location-wise closing stock quantities and valuation.
              }
              @case ('BATCH_AGEING') {
                Batch / Lot manufacturing, expiry horizon, and expired-lot quarantine status.
              }
            }
          </p>
        </div>

        <div class="mc-stocksumm-toolbar" role="toolbar" aria-label="Inventory Report Actions">
          <div class="mc-stocksumm-tabs" role="tablist" aria-label="Inventory Report Views">
            <button
              type="button"
              role="tab"
              class="mc-btn"
              [class.mc-btn-primary]="activeTab() === 'STOCK_SUMMARY' || activeTab() === 'MOVEMENT_ANALYSIS'"
              [attr.aria-selected]="activeTab() === 'STOCK_SUMMARY' || activeTab() === 'MOVEMENT_ANALYSIS'"
              data-testid="tab-stock-summary"
              (click)="switchTab('STOCK_SUMMARY')"
            >
              Stock Summary
            </button>
            <button
              type="button"
              role="tab"
              class="mc-btn"
              [class.mc-btn-primary]="activeTab() === 'GODOWN_SUMMARY'"
              [attr.aria-selected]="activeTab() === 'GODOWN_SUMMARY'"
              data-testid="tab-godown-summary"
              (click)="switchTab('GODOWN_SUMMARY')"
            >
              Godown Summary
            </button>
            <button
              type="button"
              role="tab"
              class="mc-btn"
              [class.mc-btn-primary]="activeTab() === 'BATCH_AGEING'"
              [attr.aria-selected]="activeTab() === 'BATCH_AGEING'"
              data-testid="tab-batch-ageing"
              (click)="switchTab('BATCH_AGEING')"
            >
              Batch Ageing
            </button>
          </div>

          <div class="mc-stocksumm-actions">
            @if (activeTab() === 'MOVEMENT_ANALYSIS') {
              <button
                type="button"
                class="mc-btn"
                data-testid="movement-back-btn"
                (click)="backToStockSummary()"
              >
                &larr; Back to Stock Summary (Backspace)
              </button>
            }
            <button
              type="button"
              class="mc-btn"
              data-testid="stock-summary-period-btn"
              (click)="openPeriodModal()"
            >
              Period (<span class="mc-key-alt">F2</span>)
            </button>
            @if (activeTab() === 'STOCK_SUMMARY') {
              <button
                type="button"
                class="mc-btn"
                data-testid="stock-summary-basis-btn"
                (click)="openBasisModal()"
              >
                Basis of Values (<span class="mc-key-ctrl">B</span>)
              </button>
              <button
                type="button"
                class="mc-btn"
                data-testid="stock-summary-detailed-btn"
                (click)="toggleDetailedMode()"
              >
                {{ detailedMode() ? 'Condensed' : 'Detailed' }} (<span class="mc-key-alt">F1</span>)
              </button>
            }
            <a routerLink="/app/inventory/stock-items" class="mc-btn">Stock Items</a>
          </div>
        </div>
      </header>

      @if (errorBanner(); as err) {
        <div class="mc-stocksumm-alert mc-stocksumm-alert-error" role="alert">{{ err }}</div>
      }

      <!-- 1. STOCK SUMMARY VIEW -->
      @if (activeTab() === 'STOCK_SUMMARY') {
        <section class="mc-card mc-stocksumm-table-card" aria-label="Stock Summary Valuation Grid">
          @if (loading()) {
            <div class="mc-stocksumm-loading">Loading Stock Summary valuation...</div>
          } @else {
            <div class="mc-stocksumm-table-wrap">
              <table class="mc-stocksumm-table" role="grid" aria-label="Stock Summary Items">
                <thead>
                  @if (detailedMode()) {
                    <tr class="mc-thead-super">
                      <th scope="col" rowspan="2" class="mc-col-particulars">Particulars</th>
                      <th scope="col" colspan="3" class="mc-col-group-head">Opening Balance</th>
                      <th scope="col" colspan="2" class="mc-col-group-head">Inwards</th>
                      <th scope="col" colspan="2" class="mc-col-group-head">Outwards</th>
                      <th scope="col" colspan="3" class="mc-col-group-head">Closing Balance</th>
                    </tr>
                    <tr>
                      <th scope="col" class="mc-col-qty">Quantity</th>
                      <th scope="col" class="mc-col-rate">Rate</th>
                      <th scope="col" class="mc-col-val">Value</th>
                      <th scope="col" class="mc-col-qty">Quantity</th>
                      <th scope="col" class="mc-col-val">Value</th>
                      <th scope="col" class="mc-col-qty">Quantity</th>
                      <th scope="col" class="mc-col-val">Value</th>
                      <th scope="col" class="mc-col-qty">Quantity</th>
                      <th scope="col" class="mc-col-rate">Rate</th>
                      <th scope="col" class="mc-col-val">Value</th>
                    </tr>
                  } @else {
                    <tr>
                      <th scope="col" class="mc-col-particulars">Particulars</th>
                      <th scope="col" class="mc-col-method">Costing Method</th>
                      <th scope="col" class="mc-col-qty">Closing Quantity</th>
                      <th scope="col" class="mc-col-rate">Closing Rate</th>
                      <th scope="col" class="mc-col-val">Closing Value</th>
                    </tr>
                  }
                </thead>

                <tbody>
                  @for (section of groupedSections(); track section.groupTitle) {
                    @if (section.group; as grp) {
                      <tr
                        class="mc-stocksumm-group-row"
                        [attr.data-testid]="'stock-group-row-' + grp.stockGroupId"
                      >
                        <td class="mc-col-particulars">
                          <span class="mc-group-name">{{ grp.stockGroupName }}</span>
                          <span class="mc-badge mc-badge-slate">Group</span>
                        </td>
                        @if (detailedMode()) {
                          <td class="mc-col-qty"></td>
                          <td class="mc-col-rate"></td>
                          <td class="mc-amount mc-col-val">
                            {{ grp.openingValue | mcIndianCurrency: companyContext.currencyScale() : true }}
                          </td>
                          <td class="mc-col-qty"></td>
                          <td class="mc-amount mc-col-val">
                            {{ grp.inwardValue | mcIndianCurrency: companyContext.currencyScale() : true }}
                          </td>
                          <td class="mc-col-qty"></td>
                          <td class="mc-amount mc-col-val">
                            {{ grp.outwardValue | mcIndianCurrency: companyContext.currencyScale() : true }}
                          </td>
                          <td class="mc-col-qty">
                            @if (grp.closingQty) {
                              <span class="mc-amount">{{ grp.closingQty }}</span>
                            }
                          </td>
                          <td class="mc-col-rate"></td>
                          <td class="mc-amount mc-col-val mc-group-subtotal">
                            {{ grp.closingValue | mcIndianCurrency: companyContext.currencyScale() : true }}
                          </td>
                        } @else {
                          <td class="mc-col-method"></td>
                          <td class="mc-col-qty">
                            @if (grp.closingQty) {
                              <span class="mc-amount">{{ grp.closingQty }}</span>
                            }
                          </td>
                          <td class="mc-col-rate"></td>
                          <td class="mc-amount mc-col-val mc-group-subtotal">
                            {{ grp.closingValue | mcIndianCurrency: companyContext.currencyScale() : true }}
                          </td>
                        }
                      </tr>
                    }

                    @for (item of section.items; track item.stockItemId) {
                      <tr
                        class="mc-stocksumm-item-row"
                        [class.mc-stocksumm-row-selected]="selectedStockItemId() === item.stockItemId"
                        [class.mc-stocksumm-item-indented]="section.group !== null"
                        [attr.aria-selected]="selectedStockItemId() === item.stockItemId"
                        [attr.data-testid]="'stock-item-row-' + item.stockItemId"
                        (click)="selectStockItemById(item.stockItemId)"
                        (dblclick)="drillIntoItemMovement(item)"
                      >
                        <td class="mc-col-particulars">
                          <div class="mc-item-primary">
                            <button
                              type="button"
                              class="mc-item-drill-link"
                              [attr.data-testid]="'drill-item-btn-' + item.stockItemId"
                              (click)="drillIntoItemMovement(item); $event.stopPropagation()"
                            >
                              {{ item.stockItemName }}
                            </button>
                            <mc-code-badge [code]="item.uomSymbol" />
                            @if (detailedMode() && item.hsnSacCode) {
                              <mc-code-badge [code]="'HSN ' + item.hsnSacCode" />
                            }
                          </div>
                        </td>

                        @if (detailedMode()) {
                          <td class="mc-col-qty">
                            <span class="mc-amount">{{ item.openingQty }}</span>
                            <span class="mc-code mc-uom-inline">{{ item.uomSymbol }}</span>
                          </td>
                          <td class="mc-amount mc-col-rate">
                            {{ item.openingRate | mcIndianCurrency: companyContext.currencyScale() : true }}
                          </td>
                          <td class="mc-amount mc-col-val">
                            {{ item.openingValue | mcIndianCurrency: companyContext.currencyScale() : true }}
                          </td>
                          <td class="mc-col-qty">
                            <span class="mc-amount">{{ item.inwardQty }}</span>
                            <span class="mc-code mc-uom-inline">{{ item.uomSymbol }}</span>
                          </td>
                          <td class="mc-amount mc-col-val">
                            {{ item.inwardValue | mcIndianCurrency: companyContext.currencyScale() : true }}
                          </td>
                          <td class="mc-col-qty">
                            <span class="mc-amount">{{ item.outwardQty }}</span>
                            <span class="mc-code mc-uom-inline">{{ item.uomSymbol }}</span>
                          </td>
                          <td class="mc-amount mc-col-val">
                            {{ item.outwardValue | mcIndianCurrency: companyContext.currencyScale() : true }}
                          </td>
                          <td class="mc-col-qty" [attr.data-testid]="'closing-qty-' + item.stockItemId">
                            <span class="mc-amount">{{ item.closingQty }}</span>
                            <span class="mc-code mc-uom-inline">{{ item.uomSymbol }}</span>
                          </td>
                          <td class="mc-amount mc-col-rate" [attr.data-testid]="'closing-rate-' + item.stockItemId">
                            {{ item.closingRate | mcIndianCurrency: companyContext.currencyScale() : true }}
                          </td>
                          <td class="mc-amount mc-col-val" [attr.data-testid]="'closing-val-' + item.stockItemId">
                            {{ item.closingValue | mcIndianCurrency: companyContext.currencyScale() }}
                          </td>
                        } @else {
                          <td class="mc-col-method">
                            <span class="mc-badge mc-badge-slate">
                              {{ formatCostingMethod(valuationMethodOverride() ?? item.costingMethod) }}
                            </span>
                          </td>
                          <td class="mc-col-qty" [attr.data-testid]="'closing-qty-' + item.stockItemId">
                            <span class="mc-amount">{{ item.closingQty }}</span>
                            <span class="mc-code mc-uom-inline">{{ item.uomSymbol }}</span>
                          </td>
                          <td class="mc-amount mc-col-rate" [attr.data-testid]="'closing-rate-' + item.stockItemId">
                            {{ item.closingRate | mcIndianCurrency: companyContext.currencyScale() : true }}
                          </td>
                          <td class="mc-amount mc-col-val" [attr.data-testid]="'closing-val-' + item.stockItemId">
                            {{ item.closingValue | mcIndianCurrency: companyContext.currencyScale() }}
                          </td>
                        }
                      </tr>
                    }
                  } @empty {
                    <tr>
                      <td [attr.colspan]="detailedMode() ? 11 : 5" class="mc-stocksumm-empty">
                        No stock movements or opening balances recorded for the selected period.
                      </td>
                    </tr>
                  }
                </tbody>

                <tfoot>
                  <tr class="mc-stocksumm-grand-total">
                    <td class="mc-col-particulars">Grand Total</td>
                    @if (detailedMode()) {
                      <td class="mc-col-qty"></td>
                      <td class="mc-col-rate"></td>
                      <td class="mc-amount mc-col-val" data-testid="grand-total-opening-val">
                        {{ computedGrandTotals().openingValue | mcIndianCurrency: companyContext.currencyScale() }}
                      </td>
                      <td class="mc-col-qty"></td>
                      <td class="mc-amount mc-col-val" data-testid="grand-total-inward-val">
                        {{ computedGrandTotals().inwardValue | mcIndianCurrency: companyContext.currencyScale() }}
                      </td>
                      <td class="mc-col-qty"></td>
                      <td class="mc-amount mc-col-val" data-testid="grand-total-outward-val">
                        {{ computedGrandTotals().outwardValue | mcIndianCurrency: companyContext.currencyScale() }}
                      </td>
                      <td class="mc-amount mc-col-qty" data-testid="grand-total-closing-qty">
                        {{ computedGrandTotals().closingQty }}
                      </td>
                      <td class="mc-col-rate"></td>
                      <td class="mc-amount mc-col-val" data-testid="grand-total-closing-val">
                        {{ computedGrandTotals().closingValue | mcIndianCurrency: companyContext.currencyScale() }}
                      </td>
                    } @else {
                      <td class="mc-col-method"></td>
                      <td class="mc-amount mc-col-qty" data-testid="grand-total-closing-qty">
                        {{ computedGrandTotals().closingQty }}
                      </td>
                      <td class="mc-col-rate"></td>
                      <td class="mc-amount mc-col-val" data-testid="grand-total-closing-val">
                        {{ computedGrandTotals().closingValue | mcIndianCurrency: companyContext.currencyScale() }}
                      </td>
                    }
                  </tr>
                </tfoot>
              </table>
            </div>
          }
        </section>
      }

      <!-- 2. PROGRESSIVE DRILL-DOWN: STOCK ITEM MOVEMENT ANALYSIS -->
      @if (activeTab() === 'MOVEMENT_ANALYSIS') {
        <section class="mc-card mc-stocksumm-table-card" aria-label="Stock Item Movement Analysis">
          @if (loading()) {
            <div class="mc-stocksumm-loading">Loading Item Movement Analysis...</div>
          } @else if (movementReport(); as mv) {
            <div class="mc-movement-summary-strip">
              <div class="mc-movement-kpi">
                <span class="mc-movement-kpi-label">Stock Item</span>
                <strong>{{ mv.stockItemName }}</strong>
                <mc-code-badge [code]="mv.uomSymbol" />
              </div>
              <div class="mc-movement-kpi">
                <span class="mc-movement-kpi-label">Total Inwards</span>
                <span class="mc-amount">{{ mv.inwardQty }}</span>
                <span class="mc-code mc-uom-inline">{{ mv.uomSymbol }}</span>
                <span class="mc-amount">
                  ({{ mv.inwardValue | mcIndianCurrency: companyContext.currencyScale() }})
                </span>
              </div>
              <div class="mc-movement-kpi">
                <span class="mc-movement-kpi-label">Total Outwards</span>
                <span class="mc-amount">{{ mv.outwardQty }}</span>
                <span class="mc-code mc-uom-inline">{{ mv.uomSymbol }}</span>
                <span class="mc-amount">
                  ({{ mv.outwardValue | mcIndianCurrency: companyContext.currencyScale() }})
                </span>
              </div>
            </div>

            <div class="mc-stocksumm-table-wrap">
              <table class="mc-stocksumm-table" role="grid" aria-label="Stock Item Voucher Movements">
                <thead>
                  <tr>
                    <th scope="col">Date</th>
                    <th scope="col" class="mc-col-particulars">Particulars</th>
                    <th scope="col">Vch Type</th>
                    <th scope="col">Vch No.</th>
                    <th scope="col">Direction</th>
                    <th scope="col" class="mc-col-qty">Quantity</th>
                    <th scope="col" class="mc-col-rate">Eff. Rate</th>
                    <th scope="col" class="mc-col-val">Value</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of mv.movements; track row.voucherId + row.movementDirection + $index; let idx = $index) {
                    <tr
                      class="mc-stocksumm-item-row"
                      [class.mc-stocksumm-row-selected]="idx === selectedMovementIndex()"
                      [attr.data-testid]="'movement-row-' + idx"
                      (click)="selectedMovementIndex.set(idx)"
                      (dblclick)="drillIntoVoucher(row)"
                    >
                      <td class="mc-code">{{ row.voucherDate }}</td>
                      <td class="mc-col-particulars">
                        <button
                          type="button"
                          class="mc-item-drill-link"
                          [attr.data-testid]="'movement-vch-btn-' + idx"
                          (click)="drillIntoVoucher(row); $event.stopPropagation()"
                        >
                          {{ row.particulars }}
                        </button>
                      </td>
                      <td>{{ row.voucherTypeName }}</td>
                      <td class="mc-code">{{ row.voucherNumber }}</td>
                      <td>
                        @if (row.movementDirection === 'INWARD') {
                          <span class="mc-badge mc-badge-jade">INWARD</span>
                        } @else {
                          <span class="mc-badge mc-badge-slate">OUTWARD</span>
                        }
                      </td>
                      <td class="mc-col-qty">
                        <span class="mc-amount">{{ row.quantity }}</span>
                        <span class="mc-code mc-uom-inline">{{ mv.uomSymbol }}</span>
                      </td>
                      <td class="mc-amount mc-col-rate">
                        {{ row.rate | mcIndianCurrency: companyContext.currencyScale() : true }}
                      </td>
                      <td class="mc-amount mc-col-val">
                        {{ row.value | mcIndianCurrency: companyContext.currencyScale() }}
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="8" class="mc-stocksumm-empty">
                        No voucher movements found for {{ mv.stockItemName }} in this period.
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </section>
      }

      <!-- 3. GODOWN / LOCATION SUMMARY -->
      @if (activeTab() === 'GODOWN_SUMMARY') {
        <section class="mc-card mc-stocksumm-table-card" aria-label="Godown Summary">
          @if (loading()) {
            <div class="mc-stocksumm-loading">Loading Godown / Location Summary...</div>
          } @else {
            <div class="mc-stocksumm-table-wrap">
              <table class="mc-stocksumm-table" role="grid" aria-label="Godown Summary Rows">
                <thead>
                  <tr>
                    <th scope="col" class="mc-col-particulars">Godown / Location</th>
                    <th scope="col">Stock Item</th>
                    <th scope="col" class="mc-col-qty">Closing Quantity</th>
                    <th scope="col" class="mc-col-rate">Rate</th>
                    <th scope="col" class="mc-col-val">Closing Value</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of godownReport()?.rows ?? []; track row.godownId + (row.stockItemId ?? '') + $index) {
                    <tr class="mc-stocksumm-item-row" [attr.data-testid]="'godown-row-' + row.godownId">
                      <td class="mc-col-particulars">
                        <strong>{{ row.godownName }}</strong>
                      </td>
                      <td>{{ row.stockItemName ?? 'All Items' }}</td>
                      <td class="mc-col-qty">
                        <span class="mc-amount">{{ row.closingQty }}</span>
                        @if (row.uomSymbol; as sym) {
                          <span class="mc-code mc-uom-inline">{{ sym }}</span>
                        }
                      </td>
                      <td class="mc-amount mc-col-rate">
                        @if (row.closingRate; as r) {
                          {{ r | mcIndianCurrency: companyContext.currencyScale() : true }}
                        }
                      </td>
                      <td class="mc-amount mc-col-val">
                        {{ row.closingValue | mcIndianCurrency: companyContext.currencyScale() }}
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="5" class="mc-stocksumm-empty">
                        No warehouse stock balances recorded for this period.
                      </td>
                    </tr>
                  }
                </tbody>
                <tfoot>
                  <tr class="mc-stocksumm-grand-total">
                    <td colspan="4" class="mc-col-particulars">Total Godown Valuation</td>
                    <td class="mc-amount mc-col-val" data-testid="godown-total-closing-val">
                      {{ (godownReport()?.totalClosingValue ?? '0.00') | mcIndianCurrency: companyContext.currencyScale() }}
                    </td>
                  </tr>
                </tfoot>
              </table>
            </div>
          }
        </section>
      }

      <!-- 4. BATCH / EXPIRY AGEING -->
      @if (activeTab() === 'BATCH_AGEING') {
        <section class="mc-card mc-stocksumm-table-card" aria-label="Batch Ageing Report">
          @if (loading()) {
            <div class="mc-stocksumm-loading">Loading Batch / Expiry Ageing...</div>
          } @else {
            <div class="mc-stocksumm-table-wrap">
              <table class="mc-stocksumm-table" role="grid" aria-label="Batch Ageing Lots">
                <thead>
                  <tr>
                    <th scope="col" class="mc-col-particulars">Stock Item</th>
                    <th scope="col">Godown</th>
                    <th scope="col">Batch / Lot</th>
                    <th scope="col">Mfg Date</th>
                    <th scope="col">Expiry Date</th>
                    <th scope="col" class="mc-col-qty">Days to Expiry</th>
                    <th scope="col">Status</th>
                    <th scope="col" class="mc-col-qty">Closing Qty</th>
                    <th scope="col" class="mc-col-val">Closing Value</th>
                  </tr>
                </thead>
                <tbody>
                  @for (b of batchAgeingReport()?.batches ?? []; track b.stockItemId + b.batchName + b.godownName) {
                    <tr
                      class="mc-stocksumm-item-row"
                      [class.mc-batch-row-expired]="b.isExpired"
                      [attr.data-testid]="'batch-ageing-row-' + b.batchName"
                    >
                      <td class="mc-col-particulars">
                        <strong>{{ b.stockItemName }}</strong>
                      </td>
                      <td>{{ b.godownName }}</td>
                      <td><mc-code-badge [code]="b.batchName" /></td>
                      <td class="mc-code">{{ b.mfgDate ?? '—' }}</td>
                      <td class="mc-code">{{ b.expiryDate ?? '—' }}</td>
                      <td class="mc-amount mc-col-qty">
                        {{ b.daysToExpiry !== null ? b.daysToExpiry : '—' }}
                      </td>
                      <td>
                        @if (b.isExpired) {
                          <span class="mc-badge mc-badge-danger" data-testid="batch-expired-badge">
                            EXPIRED
                          </span>
                        } @else {
                          <span class="mc-badge mc-badge-jade">Active</span>
                        }
                      </td>
                      <td class="mc-amount mc-col-qty">{{ b.closingQty }}</td>
                      <td class="mc-amount mc-col-val">
                        {{ b.closingValue | mcIndianCurrency: companyContext.currencyScale() }}
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="9" class="mc-stocksumm-empty">
                        No batch-tracked stock lots on hand as of {{ toDate() }}.
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </section>
      }

      <!-- Ctrl+B: Basis of Values (Dynamic Re-Valuation Override) Modal -->
      @if (basisModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Basis of Values — Stock Valuation Method (Ctrl+B)"
        >
          <form class="mc-modal-dialog mc-stocksumm-modal" (ngSubmit)="applyBasisOfValues()">
            <div class="mc-modal-header">
              <span>Basis of Values (<span class="mc-key-ctrl">B</span>) — Stock Valuation</span>
              <button type="button" class="mc-btn" (click)="basisModalOpen.set(false)">Esc</button>
            </div>

            <div class="mc-modal-body mc-stocksumm-modal-fields">
              <div>
                <label class="mc-field-label" for="mc-stock-valuation-override">
                  Stock Valuation Method
                </label>
                <select
                  id="mc-stock-valuation-override"
                  name="valuationMethodOverride"
                  class="mc-input"
                  data-testid="basis-valuation-select"
                  [ngModel]="draftValuationOverride"
                  (ngModelChange)="draftValuationOverride = $event"
                >
                  <option value="">Default (As per Stock Item Master)</option>
                  @for (opt of costingMethodOptions; track opt.value) {
                    <option [value]="opt.value">{{ opt.label }} ({{ opt.value }})</option>
                  }
                </select>
              </div>
              <p class="mc-modal-hint">
                Dynamically re-computes Closing Rate &amp; Closing Value across all Stock Items for
                analytical comparison without altering master definitions.
              </p>
            </div>

            <div class="mc-modal-footer">
              <span>Enter to Apply Re-Valuation</span>
              <button
                type="submit"
                class="mc-btn mc-btn-primary"
                data-testid="basis-apply-btn"
              >
                Accept
              </button>
            </div>
          </form>
        </div>
      }

      <!-- Alt+F2: Period Modal -->
      @if (periodModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Change Report Period (Alt+F2)"
        >
          <form class="mc-modal-dialog mc-stocksumm-modal" (ngSubmit)="applyPeriodChange()">
            <div class="mc-modal-header">
              <span>Report Period (<span class="mc-key-alt">F2</span>)</span>
              <button type="button" class="mc-btn" (click)="periodModalOpen.set(false)">Esc</button>
            </div>

            <div class="mc-modal-body mc-stocksumm-modal-fields">
              <div>
                <label class="mc-field-label" for="mc-stocksumm-from">From Date</label>
                <input
                  id="mc-stocksumm-from"
                  type="date"
                  name="fromDate"
                  class="mc-input"
                  [(ngModel)]="draftFromDate"
                  required
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-stocksumm-to">To Date</label>
                <input
                  id="mc-stocksumm-to"
                  type="date"
                  name="toDate"
                  class="mc-input"
                  [(ngModel)]="draftToDate"
                  required
                />
              </div>
            </div>

            <div class="mc-modal-footer">
              <span>Enter to Refresh</span>
              <button type="submit" class="mc-btn mc-btn-primary">Accept</button>
            </div>
          </form>
        </div>
      }
    </div>
  `,
  styles: `
    .mc-stocksumm-shell {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-stocksumm-header {
      padding: var(--mc-space-3) var(--mc-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-stocksumm-heading-row {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-2);
    }

    .mc-stocksumm-title {
      margin: 0;
      font-size: var(--mc-font-size-lg);
      font-weight: 700;
      color: var(--mc-color-slate-900);
    }

    .mc-stocksumm-subtitle {
      margin: 4px 0 0;
      font-size: var(--mc-font-size-xs);
      font-weight: 400;
      color: var(--mc-color-slate-600);
    }

    .mc-stocksumm-toolbar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-2);
      padding-top: var(--mc-space-2);
      border-top: var(--mc-border-subtle);
    }

    .mc-stocksumm-tabs,
    .mc-stocksumm-actions {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-2);
    }

    .mc-stocksumm-alert {
      padding: var(--mc-space-2) var(--mc-space-3);
      border-radius: var(--mc-radius-sm);
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
    }

    .mc-stocksumm-alert-error {
      background: var(--mc-color-danger-50);
      color: var(--mc-color-danger-700);
      border: 1px solid #FCA5A5;
    }

    .mc-stocksumm-table-card {
      overflow: hidden;
    }

    .mc-stocksumm-loading,
    .mc-stocksumm-empty {
      padding: var(--mc-space-5);
      text-align: center;
      font-size: var(--mc-font-size-sm);
      font-weight: 500;
      color: var(--mc-color-slate-600);
    }

    .mc-stocksumm-table-wrap {
      overflow-x: auto;
    }

    .mc-stocksumm-table {
      width: 100%;
      border-collapse: collapse;
      font-size: var(--mc-font-size-sm);
    }

    .mc-stocksumm-table th {
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      padding: 0.45rem 0.65rem;
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      text-align: left;
      border-bottom: 1px solid rgba(255, 255, 255, 0.16);
      white-space: nowrap;
    }

    .mc-thead-super th.mc-col-group-head {
      text-align: center;
      border-left: 1px solid rgba(255, 255, 255, 0.18);
    }

    .mc-stocksumm-table th.mc-col-qty,
    .mc-stocksumm-table th.mc-col-rate,
    .mc-stocksumm-table th.mc-col-val {
      text-align: right;
    }

    .mc-stocksumm-table td {
      padding: 0.4rem 0.65rem;
      border-bottom: 1px solid var(--mc-color-slate-100);
      vertical-align: middle;
    }

    .mc-col-qty,
    .mc-col-rate,
    .mc-col-val {
      text-align: right;
      white-space: nowrap;
    }

    .mc-stocksumm-group-row {
      background: var(--mc-color-canvas);
      font-weight: 700;
    }

    .mc-group-name {
      margin-right: var(--mc-space-2);
      font-weight: 700;
      color: var(--mc-color-slate-900);
    }

    .mc-group-subtotal {
      font-weight: 700;
      color: var(--mc-color-slate-900);
    }

    .mc-stocksumm-item-row {
      cursor: pointer;

      &:hover {
        background: var(--mc-color-row-hover);
      }
    }

    .mc-stocksumm-item-indented .mc-col-particulars {
      padding-left: 1.5rem;
    }

    .mc-stocksumm-row-selected {
      background: var(--mc-color-teal-50);
      outline: 2px solid var(--mc-color-verdigris-teal);
      outline-offset: -2px;
    }

    .mc-batch-row-expired {
      background: var(--mc-color-danger-50);
    }

    .mc-item-primary {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-item-drill-link {
      background: transparent;
      border: none;
      padding: 0;
      font: inherit;
      font-weight: 600;
      color: var(--mc-color-slate-900);
      text-align: left;
      cursor: pointer;

      &:hover {
        color: var(--mc-color-verdigris-teal);
        text-decoration: underline;
      }
    }

    .mc-uom-inline {
      margin-left: 4px;
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
    }

    .mc-stocksumm-grand-total td {
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      font-weight: 700;
      padding: 0.55rem 0.65rem;
      border-top: 2px solid var(--mc-color-verdigris-teal);
    }

    .mc-movement-summary-strip {
      display: flex;
      flex-wrap: wrap;
      gap: var(--mc-space-4);
      padding: var(--mc-space-3) var(--mc-space-4);
      background: var(--mc-color-teal-50);
      border-bottom: 1px solid var(--mc-color-teal-100);
    }

    .mc-movement-kpi {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      font-size: var(--mc-font-size-sm);
      font-weight: 500;
    }

    .mc-movement-kpi-label {
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      color: var(--mc-color-slate-600);
      text-transform: uppercase;
    }

    .mc-stocksumm-modal {
      width: min(460px, 92vw);
    }

    .mc-stocksumm-modal-fields {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-modal-hint {
      margin: 0;
      font-size: var(--mc-font-size-xs);
      font-weight: 400;
      color: var(--mc-color-slate-600);
    }

    .mc-badge-danger {
      background: var(--mc-color-danger-50);
      color: var(--mc-color-danger-700);
      border: 1px solid #FCA5A5;
    }
  `,
})
export class StockSummaryComponent implements OnInit {
  readonly companyContext = inject(CompanyContextStore);
  private readonly inventoryService = inject(InventoryService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly navStack = inject(NavigationStackStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  readonly embeddedOverlay = input<boolean>(false);
  readonly initialTab = input<InventoryReportTab>('STOCK_SUMMARY');

  readonly costingMethodOptions = STOCK_COSTING_METHOD_OPTIONS;

  readonly activeTab = signal<InventoryReportTab>('STOCK_SUMMARY');
  readonly detailedMode = signal<boolean>(false);
  readonly fromDate = signal<string>('2025-04-01');
  readonly toDate = signal<string>('2026-03-31');
  readonly valuationMethodOverride = signal<StockCostingMethod | null>(null);

  readonly loading = signal<boolean>(false);
  readonly errorBanner = signal<string | null>(null);

  readonly stockSummaryReport = signal<StockSummaryReportDto | null>(null);
  readonly godownReport = signal<GodownSummaryReportDto | null>(null);
  readonly batchAgeingReport = signal<BatchAgeingReportDto | null>(null);
  readonly movementReport = signal<MovementAnalysisReportDto | null>(null);

  readonly selectedStockItemId = signal<string | null>(null);
  readonly selectedMovementIndex = signal<number>(0);

  readonly basisModalOpen = signal<boolean>(false);
  readonly periodModalOpen = signal<boolean>(false);

  draftValuationOverride: StockCostingMethod | '' = '';
  draftFromDate = '2025-04-01';
  draftToDate = '2026-03-31';

  readonly activeHeaderTitle = computed(() => {
    switch (this.activeTab()) {
      case 'STOCK_SUMMARY':
        return 'Stock Summary';
      case 'MOVEMENT_ANALYSIS': {
        const mv = this.movementReport();
        return mv ? `Stock Item Movement Analysis — ${mv.stockItemName}` : 'Movement Analysis';
      }
      case 'GODOWN_SUMMARY':
        return 'Godown / Location Summary';
      case 'BATCH_AGEING':
        return 'Batch / Expiry Ageing';
    }
  });

  readonly periodBadgeText = computed(() =>
    this.activeTab() === 'BATCH_AGEING'
      ? `As of ${this.toDate()}`
      : `${this.fromDate()} to ${this.toDate()}`,
  );

  readonly flatSelectableItems = computed<readonly StockSummaryItemDto[]>(
    () => this.stockSummaryReport()?.items ?? [],
  );

  readonly groupedSections = computed<readonly GroupedStockSummarySection[]>(() => {
    const rpt = this.stockSummaryReport();
    if (!rpt || rpt.items.length === 0) {
      return [];
    }

    if (!rpt.groups || rpt.groups.length === 0) {
      return [
        {
          group: null,
          groupTitle: 'Primary Stock Items',
          items: rpt.items,
        },
      ];
    }

    const byGroup = new Map<string, StockSummaryItemDto[]>();
    const ungrouped: StockSummaryItemDto[] = [];

    for (const item of rpt.items) {
      if (item.stockGroupId) {
        const list = byGroup.get(item.stockGroupId) ?? [];
        list.push(item);
        byGroup.set(item.stockGroupId, list);
      } else {
        ungrouped.push(item);
      }
    }

    const sections: GroupedStockSummarySection[] = [];
    for (const grp of rpt.groups) {
      const grpItems = byGroup.get(grp.stockGroupId) ?? [];
      sections.push({
        group: grp,
        groupTitle: grp.stockGroupName,
        items: grpItems,
      });
      byGroup.delete(grp.stockGroupId);
    }

    for (const [orphanGroupId, orphanItems] of byGroup.entries()) {
      sections.push({
        group: null,
        groupTitle: orphanItems[0]?.stockGroupName ?? orphanGroupId,
        items: orphanItems,
      });
    }

    if (ungrouped.length > 0) {
      sections.push({
        group: null,
        groupTitle: 'Primary (Ungrouped)',
        items: ungrouped,
      });
    }

    return sections;
  });

  readonly computedGrandTotals = computed(() => {
    const scale = this.companyContext.currencyScale();
    const rpt = this.stockSummaryReport();
    if (!rpt) {
      const zero = ExactDecimal.zero(scale).toWireString(scale);
      return {
        openingValue: zero,
        inwardValue: zero,
        outwardValue: zero,
        closingQty: '0',
        closingValue: zero,
      };
    }

    let sumOpen = ExactDecimal.zero(scale);
    let sumIn = ExactDecimal.zero(scale);
    let sumOut = ExactDecimal.zero(scale);
    let sumCloseVal = ExactDecimal.zero(scale);
    let sumCloseQty = ExactDecimal.zero(3);

    for (const item of rpt.items) {
      sumOpen = sumOpen.add(
        ExactDecimal.tryParse(item.openingValue ?? '0', scale, 'HALF_UP') ??
          ExactDecimal.zero(scale),
      );
      sumIn = sumIn.add(
        ExactDecimal.tryParse(item.inwardValue ?? '0', scale, 'HALF_UP') ??
          ExactDecimal.zero(scale),
      );
      sumOut = sumOut.add(
        ExactDecimal.tryParse(item.outwardValue ?? '0', scale, 'HALF_UP') ??
          ExactDecimal.zero(scale),
      );
      sumCloseVal = sumCloseVal.add(
        ExactDecimal.tryParse(item.closingValue ?? '0', scale, 'HALF_UP') ??
          ExactDecimal.zero(scale),
      );
      sumCloseQty = sumCloseQty.add(
        ExactDecimal.tryParse(item.closingQty ?? '0', 3, 'HALF_UP') ?? ExactDecimal.zero(3),
      );
    }

    return {
      openingValue: rpt.totalOpeningValue ?? sumOpen.toWireString(scale),
      inwardValue: rpt.totalInwardValue ?? sumIn.toWireString(scale),
      outwardValue: rpt.totalOutwardValue ?? sumOut.toWireString(scale),
      closingQty: rpt.totalClosingQty ?? sumCloseQty.toWireString(2),
      closingValue: rpt.totalClosingValue ?? sumCloseVal.toWireString(scale),
    };
  });

  ngOnInit(): void {
    this.fromDate.set(this.companyContext.periodFrom());
    this.toDate.set(this.companyContext.periodTo());

    const routeDataTab = this.route.snapshot.data['reportTab'] as InventoryReportTab | undefined;
    const queryTab = this.route.snapshot.queryParamMap.get('tab');
    if (queryTab === 'godown-summary') {
      this.activeTab.set('GODOWN_SUMMARY');
    } else if (queryTab === 'batch-ageing') {
      this.activeTab.set('BATCH_AGEING');
    } else if (routeDataTab) {
      this.activeTab.set(routeDataTab);
    } else {
      this.activeTab.set(this.initialTab());
    }

    const disposers: (() => void)[] = [
      this.shortcutRegistry.bindHandler(
        'report.toggleDetailed',
        'SCREEN',
        () => {
          this.toggleDetailedMode();
        },
        () => !this.anyModalOpen(),
      ),
      this.shortcutRegistry.bindHandler(
        'report.basisOfValues',
        'SCREEN',
        () => {
          this.openBasisModal();
        },
        () => !this.anyModalOpen(),
      ),
      this.shortcutRegistry.bindHandler(
        'context.changePeriod',
        'SCREEN',
        () => {
          this.openPeriodModal();
        },
        () => !this.anyModalOpen(),
      ),
    ];

    this.destroyRef.onDestroy(() => {
      for (const dispose of disposers) {
        dispose();
      }
    });

    void this.refreshActiveReport();
  }

  private anyModalOpen(): boolean {
    return this.basisModalOpen() || this.periodModalOpen();
  }

  formatCostingMethod(method: StockCostingMethod): string {
    return (
      this.costingMethodOptions.find((m) => m.value === method)?.label ?? method
    );
  }

  toggleDetailedMode(): void {
    this.detailedMode.update((v) => !v);
  }

  switchTab(tab: InventoryReportTab): void {
    if (this.activeTab() === tab && tab !== 'STOCK_SUMMARY') {
      return;
    }
    this.activeTab.set(tab);
    void this.refreshActiveReport();
  }

  async refreshActiveReport(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) {
      return;
    }

    this.loading.set(true);
    this.errorBanner.set(null);

    try {
      const tab = this.activeTab();
      if (tab === 'STOCK_SUMMARY') {
        const rpt = await this.inventoryService.getStockSummary(cid, {
          fromDate: this.fromDate(),
          toDate: this.toDate(),
          valuationMethodOverride: this.valuationMethodOverride(),
        });
        this.stockSummaryReport.set(rpt);
        const currentSelected = this.selectedStockItemId();
        const stillPresent = rpt.items.some((i) => i.stockItemId === currentSelected);
        if (!stillPresent) {
          this.selectedStockItemId.set(rpt.items[0]?.stockItemId ?? null);
        }
      } else if (tab === 'GODOWN_SUMMARY') {
        const gd = await this.inventoryService.getGodownSummary(cid, {
          fromDate: this.fromDate(),
          toDate: this.toDate(),
        });
        this.godownReport.set(gd);
      } else if (tab === 'BATCH_AGEING') {
        const ba = await this.inventoryService.getBatchAgeing(cid, {
          asOfDate: this.toDate(),
        });
        this.batchAgeingReport.set(ba);
      } else if (tab === 'MOVEMENT_ANALYSIS') {
        const itemId = this.selectedStockItemId();
        if (itemId) {
          const mv = await this.inventoryService.getMovementAnalysis(cid, itemId, {
            fromDate: this.fromDate(),
            toDate: this.toDate(),
          });
          this.movementReport.set(mv);
          this.selectedMovementIndex.set(0);
        }
      }
    } catch (err: unknown) {
      this.errorBanner.set(
        err instanceof Error ? err.message : 'Unable to load inventory valuation report.',
      );
    } finally {
      this.loading.set(false);
    }
  }

  selectStockItemById(stockItemId: string): void {
    this.selectedStockItemId.set(stockItemId);
  }

  async drillIntoItemMovement(item: StockSummaryItemDto): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) {
      return;
    }
    this.selectedStockItemId.set(item.stockItemId);
    this.activeTab.set('MOVEMENT_ANALYSIS');
    this.loading.set(true);
    this.errorBanner.set(null);

    try {
      const mv = await this.inventoryService.getMovementAnalysis(cid, item.stockItemId, {
        fromDate: this.fromDate(),
        toDate: this.toDate(),
      });
      this.movementReport.set(mv);
      this.selectedMovementIndex.set(0);
    } catch (err: unknown) {
      this.errorBanner.set(
        err instanceof Error ? err.message : 'Unable to load Stock Item Movement Analysis.',
      );
    } finally {
      this.loading.set(false);
    }
  }

  backToStockSummary(): void {
    this.activeTab.set('STOCK_SUMMARY');
    if (!this.stockSummaryReport()) {
      void this.refreshActiveReport();
    }
  }

  drillIntoVoucher(row: MovementAnalysisVoucherRowDto): void {
    if (this.embeddedOverlay()) {
      this.navStack.clear();
    }
    void this.router.navigate(['/app/vouchers', row.voucherId]);
  }

  openBasisModal(): void {
    this.draftValuationOverride = this.valuationMethodOverride() ?? '';
    this.basisModalOpen.set(true);
  }

  applyBasisOfValues(): void {
    const nextOverride: StockCostingMethod | null =
      this.draftValuationOverride === '' ? null : this.draftValuationOverride;
    this.valuationMethodOverride.set(nextOverride);
    this.basisModalOpen.set(false);
    if (this.activeTab() !== 'STOCK_SUMMARY') {
      this.activeTab.set('STOCK_SUMMARY');
    }
    void this.refreshActiveReport();
  }

  openPeriodModal(): void {
    this.draftFromDate = this.fromDate();
    this.draftToDate = this.toDate();
    this.periodModalOpen.set(true);
  }

  applyPeriodChange(): void {
    if (this.draftFromDate > this.draftToDate) {
      this.errorBanner.set('Period From date must be on or before Period To date.');
      return;
    }
    this.fromDate.set(this.draftFromDate);
    this.toDate.set(this.draftToDate);
    this.periodModalOpen.set(false);
    void this.refreshActiveReport();
  }

  @HostListener('window:keydown', ['$event'])
  onKeyDown(event: KeyboardEvent): void {
    if (event.isComposing) {
      return;
    }

    if (event.key === 'Escape') {
      if (this.basisModalOpen()) {
        event.preventDefault();
        event.stopPropagation();
        this.basisModalOpen.set(false);
        return;
      }
      if (this.periodModalOpen()) {
        event.preventDefault();
        event.stopPropagation();
        this.periodModalOpen.set(false);
        return;
      }
    }

    if (
      this.anyModalOpen() ||
      (this.navStack.hasOverlays() && !this.embeddedOverlay()) ||
      isEditableTextElement(event.target) ||
      event.ctrlKey ||
      event.altKey ||
      event.metaKey
    ) {
      return;
    }

    if (this.activeTab() === 'STOCK_SUMMARY') {
      const items = this.flatSelectableItems();
      if (items.length === 0) {
        return;
      }
      const curIdx = Math.max(
        0,
        items.findIndex((i) => i.stockItemId === this.selectedStockItemId()),
      );

      if (event.key === 'ArrowDown') {
        event.preventDefault();
        const next = items[(curIdx + 1) % items.length];
        this.selectedStockItemId.set(next.stockItemId);
        return;
      }
      if (event.key === 'ArrowUp') {
        event.preventDefault();
        const prev = items[(curIdx - 1 + items.length) % items.length];
        this.selectedStockItemId.set(prev.stockItemId);
        return;
      }
      if (event.key === 'Enter') {
        const selected = items[curIdx];
        if (selected) {
          event.preventDefault();
          void this.drillIntoItemMovement(selected);
        }
      }
      return;
    }

    if (this.activeTab() === 'MOVEMENT_ANALYSIS') {
      if (event.key === 'Backspace') {
        event.preventDefault();
        this.backToStockSummary();
        return;
      }
      const rows = this.movementReport()?.movements ?? [];
      if (rows.length === 0) {
        return;
      }
      const idx = this.selectedMovementIndex();
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        this.selectedMovementIndex.set((idx + 1) % rows.length);
        return;
      }
      if (event.key === 'ArrowUp') {
        event.preventDefault();
        this.selectedMovementIndex.set((idx - 1 + rows.length) % rows.length);
        return;
      }
      if (event.key === 'Enter') {
        const row = rows[idx];
        if (row) {
          event.preventDefault();
          this.drillIntoVoucher(row);
        }
      }
    }
  }
}
