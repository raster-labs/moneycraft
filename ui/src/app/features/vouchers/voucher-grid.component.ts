import { ScrollingModule } from '@angular/cdk/scrolling';
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
  viewChildren,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { ExactDecimal } from '../../core/money/exact-decimal';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { INDIAN_GST_STATES } from '../../core/validation/gstin-validator';
import { AmountCellComponent } from '../../shared/components/amount-cell/amount-cell.component';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import {
  TypeaheadOption,
  TypeaheadSelectComponent,
} from '../../shared/components/typeahead-select/typeahead-select.component';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { ChartOfAccountsStore } from '../masters/chart-of-accounts.store';
import {
  AccountGroup,
  BalanceSide,
  Ledger,
  VoucherBaseCategory,
  resolveGroupLineageFlags,
} from '../masters/masters.models';
import { SecondaryMasterModalService } from '../masters/secondary-master-modal.service';
import { BankAllocationPopoverComponent } from './bank-allocation-popover.component';
import { BillWisePopoverComponent } from './bill-wise-popover.component';
import { CostAllocationPopoverComponent } from './cost-allocation-popover.component';
import { VoucherService } from './voucher.service';
import {
  BankAllocationDto,
  BillAllocationDto,
   BillAllocationType,
  CostAllocationDto,
  PREDEFINED_24_VOUCHER_SPECS,
  PredefinedVoucherSpec,
  UpsertVoucherRequest,
  VoucherDetailDto,
  VoucherInventoryItemHookDto,
  VoucherLifecycleStatus,
  VoucherLineRequest,
  VoucherLineRole,
  VoucherPersistedView,
  VoucherPostingNature,
  VoucherSubmitAction,
  resolveVoucherSpecByCategory,
} from './vouchers.models';

export interface EditableVoucherLine {
  readonly rowId: string;
  side: BalanceSide;
  lineRole: VoucherLineRole;
  ledgerId: string | null;
  ledgerName: string;
  ratePct: string;
  computedTaxAmount: string | null;
  isGstOverridden: boolean;
  amount: string;
  lineNarration: string;
  billAllocations: readonly BillAllocationDto[];
  costAllocations: readonly CostAllocationDto[];
  bankAllocations: readonly BankAllocationDto[];
}

type PendingAllocationStage = 'BILL' | 'COST' | 'BANK';

interface ActiveSubModalContext {
  readonly target: 'LINE' | 'HEADER_ACCOUNT' | 'HEADER_PARTY';
  readonly lineIndex: number;
  readonly stage: PendingAllocationStage;
  readonly ledger: Ledger;
  readonly amount: string;
  readonly side: BalanceSide;
}

function isCashOrBankLedger(ledger: Ledger, groups: readonly AccountGroup[]): boolean {
  const flags = resolveGroupLineageFlags(ledger.groupId, groups);
  if (flags.isBankAccount || flags.isCashInHand) {
    return true;
  }
  const gName = (ledger.groupName ?? '').toLowerCase();
  return (
    gName.includes('cash-in-hand') ||
    gName.includes('bank accounts') ||
    gName.includes('bank od') ||
    gName.includes('bank occ')
  );
}

function isBankOnlyLedger(ledger: Ledger, groups: readonly AccountGroup[]): boolean {
  const flags = resolveGroupLineageFlags(ledger.groupId, groups);
  if (flags.isBankAccount) {
    return true;
  }
  const gName = (ledger.groupName ?? '').toLowerCase();
  return (
    gName.includes('bank accounts') ||
    gName.includes('bank od') ||
    gName.includes('bank occ')
  );
}

function isPartyLedger(ledger: Ledger, groups: readonly AccountGroup[]): boolean {
  const flags = resolveGroupLineageFlags(ledger.groupId, groups);
  if (flags.isPartyGroup) {
    return true;
  }
  const gName = (ledger.groupName ?? '').toLowerCase();
  return gName.includes('sundry debtors') || gName.includes('sundry creditors');
}

function isSalesOrPurchaseLedger(ledger: Ledger, groups: readonly AccountGroup[]): boolean {
  const flags = resolveGroupLineageFlags(ledger.groupId, groups);
  if (flags.isSalesOrPurchase) {
    return true;
  }
  const gName = (ledger.groupName ?? '').toLowerCase();
  return gName.includes('sales accounts') || gName.includes('purchase accounts');
}

function inferInvoiceLineRole(ledger: Ledger, groups: readonly AccountGroup[]): VoucherLineRole {
  if (ledger.taxType !== 'NONE' || resolveGroupLineageFlags(ledger.groupId, groups).isDutiesAndTaxes) {
    return 'TAX';
  }
  const lower = ledger.name.toLowerCase();
  if (lower.includes('round off') || lower.includes('rounding')) {
    return 'ROUND_OFF';
  }
  if (lower.includes('discount') || lower.includes('rebate')) {
    return 'DISCOUNT';
  }
  if (isSalesOrPurchaseLedger(ledger, groups)) {
    return 'ACCOUNTING';
  }
  return 'ADDITIONAL_CHARGE';
}

function invoicePartyAndRevenueSides(category: VoucherBaseCategory): {
  readonly partySide: BalanceSide;
  readonly revenueExpenseSide: BalanceSide;
} {
  switch (category) {
    case 'PURCHASE':
    case 'PURCHASE_ORDER':
    case 'RECEIPT_NOTE':
    case 'CREDIT_NOTE':
    case 'REJECTIONS_IN':
      return { partySide: 'CR', revenueExpenseSide: 'DR' };
    default:
      return { partySide: 'DR', revenueExpenseSide: 'CR' };
  }
}

@Component({
  selector: 'mc-voucher-grid',
  standalone: true,
  imports: [
    FormsModule,
    RouterLink,
    ScrollingModule,
    IndianCurrencyPipe,
    AmountCellComponent,
    CodeBadgeComponent,
    TypeaheadSelectComponent,
    GridCellNavDirective,
    BillWisePopoverComponent,
    CostAllocationPopoverComponent,
    BankAllocationPopoverComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-vch-shell" data-testid="voucher-grid-root">
      <!-- Top Voucher Type Ribbon & Mode / Optional Badges -->
      <header class="mc-vch-topbar">
        <div class="mc-vch-title-cluster">
          <span class="mc-vch-type-pill" [class]="'mc-vch-cat-' + activeCategory().toLowerCase()">
            {{ activeVoucherTypeName() }}
          </span>

          <span class="mc-vch-no-box">
            No.
            @if (isManualNumbering()) {
              <input
                type="text"
                class="mc-input mc-code mc-vch-no-input"
                [ngModel]="voucherNumber()"
                (ngModelChange)="voucherNumber.set($event); markDirty()"
                aria-label="Manual Voucher Number"
              />
            } @else {
              <mc-code-badge [code]="voucherNumber() || 'Auto'" />
            }
          </span>

          @if (postingNature() === 'OPTIONAL') {
            <span class="mc-badge mc-vch-optional-badge" data-testid="voucher-optional-badge">
              Optional
            </span>
          } @else if (postingNature() === 'MEMORANDUM') {
            <span class="mc-badge mc-badge-slate" data-testid="voucher-memo-badge">
              Memorandum (Non-Posting)
            </span>
          } @else if (postingNature() === 'REVERSING_JOURNAL') {
            <span class="mc-badge mc-badge-slate">Reversing Journal</span>
          }

          @if (editingVoucherId()) {
            <span class="mc-badge mc-badge-jade" data-testid="voucher-alter-badge">
              Alteration ({{ editingStatus() }} • v{{ editingVersionNo() }})
            </span>
          }

          <span class="mc-vch-mode-label" data-testid="voucher-persisted-view">
            Mode: <strong>{{ persistedViewLabel() }}</strong>
          </span>
        </div>

        <div class="mc-vch-date-cluster">
          <div class="mc-vch-quick-btns">
            <button
              type="button"
              class="mc-btn"
              data-testid="btn-change-mode"
              (click)="openChangeModeModal()"
            >
              <span class="mc-key-ctrl">H</span>: Mode
            </button>
            <button
              type="button"
              class="mc-btn"
              data-testid="btn-toggle-optional"
              (click)="toggleOptional()"
            >
              <span class="mc-key-ctrl">L</span>: {{ postingNature() === 'OPTIONAL' ? 'Regular' : 'Optional' }}
            </button>
            <button
              type="button"
              class="mc-btn"
              data-testid="btn-other-vouchers"
              (click)="openOtherVouchersModal()"
            >
              F10: Other Vouchers
            </button>
            <a routerLink="/app/daybook" class="mc-btn">Day Book</a>
          </div>

          <div class="mc-vch-date-box">
            <label class="mc-field-label" for="voucher-date-input">Date (F2)</label>
            <input
              #voucherDateInput
              id="voucher-date-input"
              type="date"
              class="mc-input mc-code mc-vch-date-input"
              mcGridCellNav
              [ngModel]="voucherDate()"
              (ngModelChange)="onVoucherDateChange($event)"
            />
          </div>
        </div>
      </header>

      <!-- Quick Voucher Type Shortcut Strip (F4 - F9, Alt+F5, Alt+F6) -->
      <nav class="mc-vch-shortcut-strip mc-no-print" aria-label="Voucher Type Switcher">
        @for (tab of primaryVoucherTabs; track tab.category) {
          <button
            type="button"
            class="mc-vch-tab-btn"
            [class.mc-vch-tab-active]="activeCategory() === tab.category"
            [attr.data-testid]="'vch-tab-' + tab.category"
            (click)="switchToCategory(tab.category)"
          >
            <span class="mc-vch-tab-key">{{ tab.key }}</span>
            <span>{{ tab.label }}</span>
          </button>
        }
      </nav>

      @if (errorBanner(); as err) {
        <div class="mc-field-error mc-vch-banner-error" role="alert" data-testid="voucher-error-banner">
          {{ err }}
        </div>
      }
      @if (savedBanner(); as msg) {
        <div class="mc-vch-banner-ok" role="status" data-testid="voucher-saved-banner">
          {{ msg }}
        </div>
      }

      <!-- Main Voucher Canvas -->
      <section class="mc-card mc-vch-card" data-mc-nav-container>
        <!-- Conditional Header Block per Entry Mode & Voucher Category -->
        <div class="mc-vch-header-fields">
          @if (isPurchaseOrDebitNote()) {
            <div class="mc-vch-supplier-row" data-testid="supplier-invoice-header">
              <div>
                <label class="mc-field-label" for="vch-supp-inv-no">Supplier Invoice No.</label>
                <input
                  id="vch-supp-inv-no"
                  type="text"
                  class="mc-input mc-code"
                  mcGridCellNav
                  [ngModel]="supplierInvoiceNo()"
                  (ngModelChange)="supplierInvoiceNo.set($event); markDirty()"
                  placeholder="e.g. KM-001"
                />
              </div>
              <div>
                <label class="mc-field-label" for="vch-supp-inv-date">Invoice Date</label>
                <input
                  id="vch-supp-inv-date"
                  type="date"
                  class="mc-input mc-code"
                  mcGridCellNav
                  [ngModel]="supplierInvoiceDate()"
                  (ngModelChange)="supplierInvoiceDate.set($event); markDirty()"
                />
              </div>
            </div>
          }

          @if (postingNature() === 'REVERSING_JOURNAL') {
            <div class="mc-vch-rev-row">
              <label class="mc-field-label" for="vch-rev-upto">Applicable Upto (Reversal Date)</label>
              <input
                id="vch-rev-upto"
                type="date"
                class="mc-input mc-code"
                mcGridCellNav
                [ngModel]="reversingApplicableUpto()"
                (ngModelChange)="reversingApplicableUpto.set($event); markDirty()"
              />
            </div>
          }

          <!-- SINGLE_ENTRY Header: Top Cash / Bank Account -->
          @if (persistedView() === 'SINGLE_ENTRY') {
            <div class="mc-vch-single-account-box" data-testid="single-entry-header">
              <div class="mc-vch-single-field">
                <label class="mc-field-label" for="vch-single-account">
                  Account (Cash / Bank &bull; <span class="mc-key-alt">C</span> to Create)
                </label>
                <mc-typeahead-select
                  #singleAccountSelect
                  inputId="vch-single-account"
                  listTitle="List of Ledger Accounts (Cash / Bank)"
                  [options]="cashBankLedgerOptions()"
                  [selectedId]="headerAccountLedgerId()"
                  [allowInlineCreate]="true"
                  (optionSelected)="onSingleEntryAccountSelected($event)"
                  (createRequested)="openInlineLedgerCreate('HEADER_ACCOUNT', -1, $event)"
                />
              </div>

              @if (selectedHeaderAccountLedger(); as acctLed) {
                <div class="mc-vch-curbal-pill">
                  <span>Cur Bal:</span>
                  <strong class="mc-amount">
                    {{ acctLed.openingBalance | mcIndianCurrency: companyContext.currencyScale() : true }}
                    {{ acctLed.openingSide }}
                  </strong>
                  @if (headerBankAllocations().length > 0) {
                    <mc-code-badge [code]="headerBankAllocations()[0].transactionType" />
                  }
                </div>
              }
            </div>
          }

          <!-- ACCOUNTING_INVOICE / ITEM_INVOICE Header: Party A/c Name + Place of Supply + Sales/Purchase Ledger -->
          @if (persistedView() === 'ACCOUNTING_INVOICE' || persistedView() === 'ITEM_INVOICE') {
            <div class="mc-vch-invoice-header-grid" data-testid="invoice-party-header">
              <div>
                <label class="mc-field-label" for="vch-party-account">
                  Party A/c name (<span class="mc-key-alt">C</span> to Create)
                </label>
                <mc-typeahead-select
                  #partyAccountSelect
                  inputId="vch-party-account"
                  listTitle="List of Party & Cash/Bank Ledgers"
                  [options]="partyAndCashBankLedgerOptions()"
                  [selectedId]="partyLedgerId()"
                  [allowInlineCreate]="true"
                  (optionSelected)="onPartyLedgerSelected($event)"
                  (createRequested)="openInlineLedgerCreate('HEADER_PARTY', -1, $event)"
                />
              </div>

              @if (persistedView() === 'ITEM_INVOICE') {
                <div>
                  <label class="mc-field-label" for="vch-accounting-ledger">
                    {{ isOutwardInvoice() ? 'Sales Ledger' : 'Purchase Ledger' }}
                  </label>
                  <mc-typeahead-select
                    inputId="vch-accounting-ledger"
                    listTitle="List of Sales / Purchase Accounts"
                    [options]="salesPurchaseLedgerOptions()"
                    [selectedId]="accountingLedgerId()"
                    [allowInlineCreate]="true"
                    (optionSelected)="onAccountingLedgerSelected($event)"
                    (createRequested)="openInlineLedgerCreate('HEADER_ACCOUNT', -1, $event)"
                  />
                </div>
              }

              <div>
                <label class="mc-field-label" for="vch-pos-state">Place of Supply</label>
                <select
                  id="vch-pos-state"
                  class="mc-select"
                  mcGridCellNav
                  [ngModel]="placeOfSupplyState()"
                  (ngModelChange)="placeOfSupplyState.set($event); markDirty()"
                >
                  @for (st of gstStates; track st.code) {
                    <option [value]="st.code">{{ st.code }} — {{ st.name }}</option>
                  }
                </select>
              </div>

              <div class="mc-vch-party-meta">
                @if (partyGstin()) {
                  <div class="mc-vch-meta-line">
                    <span class="mc-vch-meta-lbl">Party GSTIN</span>
                    <mc-code-badge [code]="partyGstin()!" />
                  </div>
                }
                <label class="mc-vch-rcm-toggle">
                  <input
                    type="checkbox"
                    [ngModel]="isReverseCharge()"
                    (ngModelChange)="isReverseCharge.set($event); markDirty()"
                  />
                  <span>Reverse Charge (RCM)</span>
                </label>
              </div>
            </div>
          }
        </div>

        <!-- ======================================================================= -->
        <!-- ITEM_INVOICE Stock Item Extension Section (Phase 4 Hook + Skip UOM per) -->
        <!-- ======================================================================= -->
        @if (persistedView() === 'ITEM_INVOICE') {
          <div class="mc-vch-item-section" data-testid="item-invoice-section">
            <div class="mc-vch-section-banner">
              <span>Name of Item (Phase 4 Inventory Hook — UOM 'per' skipped on Enter when no Alternate UOM)</span>
              <button type="button" class="mc-btn" (click)="addItemHookRow()">+ Add Item Line</button>
            </div>
            <table class="mc-dense-table" aria-label="Item Invoice Lines">
              <thead>
                <tr>
                  <th>Name of Item</th>
                  <th class="mc-vch-col-qty mc-amount">Quantity</th>
                  <th class="mc-vch-col-rate mc-amount">Rate</th>
                  <th class="mc-vch-col-uom">per</th>
                  <th class="mc-vch-col-amt mc-amount">Amount</th>
                </tr>
              </thead>
              <tbody>
                @for (item of itemHookRows(); track item.rowId; let iIdx = $index) {
                  <tr>
                    <td>
                      <input
                        type="text"
                        class="mc-input"
                        mcGridCellNav
                        [ngModel]="item.stockItemName"
                        (ngModelChange)="onItemHookNameChange(iIdx, $event)"
                        placeholder="Stock Item description (or leave blank for Additional Ledgers)"
                      />
                    </td>
                    <td>
                      <input
                        type="text"
                        class="mc-input mc-amount"
                        mcGridCellNav
                        [ngModel]="item.quantity"
                        (ngModelChange)="onItemHookQtyOrRateChange(iIdx, $event, item.rate)"
                      />
                    </td>
                    <td>
                      <input
                        type="text"
                        class="mc-input mc-amount"
                        mcGridCellNav
                        [ngModel]="item.rate"
                        (ngModelChange)="onItemHookQtyOrRateChange(iIdx, item.quantity, $event)"
                      />
                    </td>
                    <td>
                      @if (item.alternateUomId !== null) {
                        <input
                          type="text"
                          class="mc-input mc-code"
                          mcGridCellNav
                          [ngModel]="item.uomCode"
                        />
                      } @else {
                        <!-- Read-only UOM ('per') omitted from mcGridCellNav so Enter skips directly to Amount! -->
                        <span class="mc-code mc-vch-readonly-uom" data-testid="readonly-uom-cell">
                          {{ item.uomCode }}
                        </span>
                      }
                    </td>
                    <td>
                      <mc-amount-cell
                        [inputId]="'item-amt-' + iIdx"
                        [scale]="companyContext.currencyScale()"
                        [currencySymbol]="companyContext.currencySymbol()"
                        [ngModel]="item.amount"
                        (ngModelChange)="onItemHookAmountOverride(iIdx, $event)"
                      />
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }

        <!-- ======================================================================= -->
        <!-- Core Voucher Lines Grid (SINGLE_ENTRY | AS_VOUCHER | INVOICE LEDGERS)   -->
        <!-- ======================================================================= -->
        <div class="mc-vch-grid-wrap">
          @if (persistedView() === 'AS_VOUCHER') {
            @if (gridLines().length > 50) {
              <cdk-virtual-scroll-viewport itemSize="40" class="mc-vch-virtual-viewport">
                <div
                  *cdkVirtualFor="let row of gridLines(); let idx = index; trackBy: trackLineById"
                  class="mc-vch-virtual-row"
                >
                  <span class="mc-code">{{ row.side }}</span>
                  <span>{{ row.ledgerName || 'Select Ledger' }}</span>
                  <span class="mc-amount">{{ row.amount | mcIndianCurrency: companyContext.currencyScale() : true }}</span>
                </div>
              </cdk-virtual-scroll-viewport>
            } @else {
              <table class="mc-dense-table mc-vch-table" aria-label="Double-Entry Voucher Lines">
                <thead>
                  <tr>
                    <th class="mc-vch-col-side">Dr/Cr</th>
                    <th>Particulars (<span class="mc-key-alt">C</span> = Create Ledger)</th>
                    <th class="mc-vch-col-amt mc-amount">Debit</th>
                    <th class="mc-vch-col-amt mc-amount">Credit</th>
                    <th class="mc-vch-col-act"></th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of gridLines(); track row.rowId; let idx = $index) {
                    <tr [attr.data-testid]="'vch-line-row-' + idx">
                      <td>
                        <select
                          class="mc-select mc-code mc-vch-side-select"
                          [attr.data-testid]="'vch-side-' + idx"
                          [disabled]="idx === 0 && gridLines().length === 1"
                          [ngModel]="row.side"
                          (ngModelChange)="onLineSideChange(idx, $event)"
                          aria-label="Debit or Credit"
                        >
                          <option value="DR">Dr</option>
                          <option value="CR">Cr</option>
                        </select>
                      </td>

                      <td>
                        <mc-typeahead-select
                          #lineLedgerSelect
                          [inputId]="'vch-ledger-' + idx"
                          listTitle="List of Ledger Accounts"
                          [options]="allLedgerOptions()"
                          [selectedId]="row.ledgerId"
                          [allowInlineCreate]="true"
                          [allowEmptyOnEnter]="idx > 0 && totals().isBalancedAndPositive"
                          (optionSelected)="onLineLedgerSelected(idx, $event)"
                          (emptyConfirmed)="onEmptyLineEnterJumpToNarration(idx)"
                          (createRequested)="openInlineLedgerCreate('LINE', idx, $event)"
                        />
                        @if (row.billAllocations.length > 0 || row.costAllocations.length > 0 || row.bankAllocations.length > 0) {
                          <div class="mc-vch-alloc-chips">
                            @for (b of row.billAllocations; track b.billName + b.allocationType) {
                              <button
                                type="button"
                                class="mc-vch-alloc-chip"
                                (click)="reopenLineSubModal(idx, 'BILL')"
                              >
                                {{ b.allocationType }}: <mc-code-badge [code]="b.billName" />
                                ({{ b.amount | mcIndianCurrency: companyContext.currencyScale() : true }})
                              </button>
                            }
                            @for (c of row.costAllocations; track c.costCentreId) {
                              <button
                                type="button"
                                class="mc-vch-alloc-chip"
                                (click)="reopenLineSubModal(idx, 'COST')"
                              >
                                CC: {{ c.costCentreName ?? c.costCentreId }}
                                ({{ c.amount | mcIndianCurrency: companyContext.currencyScale() : true }})
                              </button>
                            }
                            @for (bk of row.bankAllocations; track bk.transactionType) {
                              <button
                                type="button"
                                class="mc-vch-alloc-chip"
                                (click)="reopenLineSubModal(idx, 'BANK')"
                              >
                                Bank: <mc-code-badge [code]="bk.transactionType" />
                                @if (bk.instrumentNo) {
                                  #{{ bk.instrumentNo }}
                                }
                              </button>
                            }
                          </div>
                        }
                      </td>

                      <td>
                        @if (row.side === 'DR') {
                          <mc-amount-cell
                            #lineAmountCell
                            [inputId]="'vch-dr-amt-' + idx"
                            [scale]="companyContext.currencyScale()"
                            [currencySymbol]="companyContext.currencySymbol()"
                            [advanceFocusOnEnter]="false"
                            [ngModel]="row.amount"
                            (ngModelChange)="onLineAmountChange(idx, $event)"
                            (enterPressed)="onLineAmountEnter(idx, $event)"
                          />
                        } @else {
                          <span class="mc-vch-blank-cell">—</span>
                        }
                      </td>

                      <td>
                        @if (row.side === 'CR') {
                          <mc-amount-cell
                            #lineAmountCell
                            [inputId]="'vch-cr-amt-' + idx"
                            [scale]="companyContext.currencyScale()"
                            [currencySymbol]="companyContext.currencySymbol()"
                            [advanceFocusOnEnter]="false"
                            [ngModel]="row.amount"
                            (ngModelChange)="onLineAmountChange(idx, $event)"
                            (enterPressed)="onLineAmountEnter(idx, $event)"
                          />
                        } @else {
                          <span class="mc-vch-blank-cell">—</span>
                        }
                      </td>

                      <td>
                        @if (gridLines().length > 1) {
                          <button
                            type="button"
                            class="mc-btn mc-vch-row-del"
                            (click)="removeGridLine(idx)"
                            title="Remove voucher line"
                          >
                            &times;
                          </button>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          } @else if (persistedView() === 'SINGLE_ENTRY') {
            <!-- SINGLE_ENTRY Particulars Grid -->
            <table class="mc-dense-table mc-vch-table" aria-label="Single-Entry Particulars Lines">
              <thead>
                <tr>
                  <th>Particulars (<span class="mc-key-alt">C</span> = Create Ledger)</th>
                  <th class="mc-vch-col-amt mc-amount">Amount</th>
                  <th class="mc-vch-col-act"></th>
                </tr>
              </thead>
              <tbody>
                @for (row of gridLines(); track row.rowId; let idx = $index) {
                  <tr [attr.data-testid]="'vch-single-row-' + idx">
                    <td>
                      <mc-typeahead-select
                        #lineLedgerSelect
                        [inputId]="'vch-single-ledger-' + idx"
                        listTitle="List of Particulars Ledgers"
                        [options]="singleEntryParticularsOptions()"
                        [selectedId]="row.ledgerId"
                        [allowInlineCreate]="true"
                        [allowEmptyOnEnter]="idx > 0 && totals().isBalancedAndPositive"
                        (optionSelected)="onLineLedgerSelected(idx, $event)"
                        (emptyConfirmed)="onEmptyLineEnterJumpToNarration(idx)"
                        (createRequested)="openInlineLedgerCreate('LINE', idx, $event)"
                      />
                      @if (row.billAllocations.length > 0 || row.costAllocations.length > 0 || row.bankAllocations.length > 0) {
                        <div class="mc-vch-alloc-chips">
                          @for (b of row.billAllocations; track b.billName + b.allocationType) {
                            <button
                              type="button"
                              class="mc-vch-alloc-chip"
                              (click)="reopenLineSubModal(idx, 'BILL')"
                            >
                              {{ b.allocationType }}: <mc-code-badge [code]="b.billName" />
                              ({{ b.amount | mcIndianCurrency: companyContext.currencyScale() : true }})
                            </button>
                          }
                          @for (c of row.costAllocations; track c.costCentreId) {
                            <button
                              type="button"
                              class="mc-vch-alloc-chip"
                              (click)="reopenLineSubModal(idx, 'COST')"
                            >
                              CC: {{ c.costCentreName ?? c.costCentreId }}
                            </button>
                          }
                          @for (bk of row.bankAllocations; track bk.transactionType) {
                            <button
                              type="button"
                              class="mc-vch-alloc-chip"
                              (click)="reopenLineSubModal(idx, 'BANK')"
                            >
                              Bank: <mc-code-badge [code]="bk.transactionType" />
                            </button>
                          }
                        </div>
                      }
                    </td>

                    <td>
                      <mc-amount-cell
                        #lineAmountCell
                        [inputId]="'vch-single-amt-' + idx"
                        [scale]="companyContext.currencyScale()"
                        [currencySymbol]="companyContext.currencySymbol()"
                        [advanceFocusOnEnter]="false"
                        [ngModel]="row.amount"
                        (ngModelChange)="onLineAmountChange(idx, $event)"
                        (enterPressed)="onLineAmountEnter(idx, $event)"
                      />
                    </td>

                    <td>
                      @if (gridLines().length > 1) {
                        <button
                          type="button"
                          class="mc-btn mc-vch-row-del"
                          (click)="removeGridLine(idx)"
                        >
                          &times;
                        </button>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          } @else {
            <!-- ACCOUNTING_INVOICE / ITEM_INVOICE Additional & Tax Ledgers Grid (with Rate % + Amount) -->
            <table class="mc-dense-table mc-vch-table" aria-label="Invoice Accounting & Tax Ledgers">
              <thead>
                <tr>
                  <th>
                    {{ persistedView() === 'ITEM_INVOICE' ? 'Additional / Tax Ledgers (CGST / SGST / IGST / Freight / Round Off)' : 'Particulars (Revenue / Expense / Tax / Additional Ledgers)' }}
                  </th>
                  <th class="mc-vch-col-ratepct mc-amount">Rate (%)</th>
                  <th class="mc-vch-col-amt mc-amount">Amount</th>
                  <th class="mc-vch-col-act"></th>
                </tr>
              </thead>
              <tbody>
                @for (row of gridLines(); track row.rowId; let idx = $index) {
                  <tr [attr.data-testid]="'vch-inv-row-' + idx">
                    <td>
                      <mc-typeahead-select
                        #lineLedgerSelect
                        [inputId]="'vch-inv-ledger-' + idx"
                        listTitle="List of Invoice & Tax Ledgers"
                        [options]="invoiceBodyLedgerOptions()"
                        [selectedId]="row.ledgerId"
                        [allowInlineCreate]="true"
                        [allowEmptyOnEnter]="totals().isBalancedAndPositive"
                        (optionSelected)="onLineLedgerSelected(idx, $event)"
                        (emptyConfirmed)="onEmptyInvoiceLineFinish(idx)"
                        (createRequested)="openInlineLedgerCreate('LINE', idx, $event)"
                      />
                      @if (row.isGstOverridden) {
                        <span class="mc-badge mc-badge-slate">GST Overridden</span>
                      }
                    </td>

                    <td>
                      <input
                        type="text"
                        class="mc-input mc-amount"
                        mcGridCellNav
                        [attr.data-testid]="'vch-ratepct-' + idx"
                        [ngModel]="row.ratePct"
                        (ngModelChange)="onInvoiceLineRatePctChange(idx, $event)"
                        placeholder="%"
                        aria-label="Rate Percentage"
                      />
                    </td>

                    <td>
                      <mc-amount-cell
                        #lineAmountCell
                        [inputId]="'vch-inv-amt-' + idx"
                        [scale]="companyContext.currencyScale()"
                        [currencySymbol]="companyContext.currencySymbol()"
                        [advanceFocusOnEnter]="false"
                        [ngModel]="row.amount"
                        (ngModelChange)="onInvoiceLineAmountManualChange(idx, $event)"
                        (enterPressed)="onLineAmountEnter(idx, $event)"
                      />
                    </td>

                    <td>
                      @if (gridLines().length > 1) {
                        <button
                          type="button"
                          class="mc-btn mc-vch-row-del"
                          (click)="removeGridLine(idx)"
                        >
                          &times;
                        </button>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>

            @if (partyBillAllocations().length > 0) {
              <div class="mc-vch-party-bills-bar" data-testid="invoice-party-bills-bar">
                <span>Party Bill-wise Refs:</span>
                @for (b of partyBillAllocations(); track b.billName + b.allocationType) {
                  <button type="button" class="mc-vch-alloc-chip" (click)="openPartyBillWiseModal()">
                    {{ b.allocationType }}: <mc-code-badge [code]="b.billName" />
                    ({{ b.amount | mcIndianCurrency: companyContext.currencyScale() : true }} {{ b.side }})
                  </button>
                }
              </div>
            }
          }
        </div>

        <div class="mc-vch-addline-row">
          <button type="button" class="mc-btn" (click)="appendBlankLine()">+ Add Line</button>
          @if (selectedPartyLedger()?.maintainBalancesBillByBill && (persistedView() === 'ACCOUNTING_INVOICE' || persistedView() === 'ITEM_INVOICE')) {
            <button
              type="button"
              class="mc-btn"
              data-testid="btn-open-party-billwise"
              (click)="openPartyBillWiseModal()"
            >
              Bill-wise Details ({{ partyBillAllocations().length || 'Auto New Ref' }})
            </button>
          }
        </div>

        <!-- Narration + Running Totals Footer -->
        <footer class="mc-vch-footer">
          <div class="mc-vch-narration-box">
            <label class="mc-field-label" for="voucher-narration-input">Narration</label>
            <input
              #narrationInput
              id="voucher-narration-input"
              type="text"
              class="mc-input"
              data-testid="voucher-narration-input"
              [ngModel]="narration()"
              (ngModelChange)="narration.set($event); markDirty()"
              (keydown.enter)="onNarrationEnter($event)"
              placeholder="Enter voucher narration (Enter -> Accept? Yes/No • Ctrl+A -> Immediate Save)..."
            />
          </div>

          <div class="mc-vch-totals-panel">
            <div class="mc-vch-total-metric">
              <span class="mc-vch-total-lbl">Total Debit</span>
              <span class="mc-amount mc-vch-total-num" data-testid="vch-total-debit">
                {{ totals().totalDebitWire | mcIndianCurrency: companyContext.currencyScale() : true }}
              </span>
            </div>

            <div class="mc-vch-total-metric">
              <span class="mc-vch-total-lbl">Total Credit</span>
              <span class="mc-amount mc-vch-total-num" data-testid="vch-total-credit">
                {{ totals().totalCreditWire | mcIndianCurrency: companyContext.currencyScale() : true }}
              </span>
            </div>

            @if (!totals().isBalanced) {
              <div class="mc-vch-total-metric mc-vch-diff-box">
                <span class="mc-vch-total-lbl">Out of Balance</span>
                <span class="mc-amount mc-vch-diff-num" data-testid="vch-out-of-balance">
                  {{ totals().diffWire | mcIndianCurrency: companyContext.currencyScale() : true }}
                </span>
              </div>
            }

            <div class="mc-vch-submit-btns">
              <button
                type="button"
                class="mc-btn"
                [disabled]="saving() || companyContext.quarantined()"
                (click)="submitVoucher('SAVE_DRAFT')"
              >
                Save Draft
              </button>
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="vch-accept-btn"
                [disabled]="saving() || companyContext.quarantined()"
                (click)="submitVoucher()"
              >
                Accept (<span class="mc-key-ctrl">A</span>)
              </button>
            </div>
          </div>
        </footer>
      </section>

      <!-- ======================================================================= -->
      <!-- Sub-Ledger Allocation Popovers (Bill-Wise, Cost Centre, Bank)           -->
      <!-- ======================================================================= -->
      <mc-bill-wise-popover
        [isOpen]="activeSubModal()?.stage === 'BILL'"
        [companyId]="companyContext.activeCompany()?.id ?? ''"
        [ledgerId]="activeSubModal()?.ledger?.id ?? ''"
        [ledgerName]="activeSubModal()?.ledger?.name ?? ''"
        [targetAmount]="activeSubModal()?.amount ?? '0.00'"
        [targetSide]="activeSubModal()?.side ?? 'DR'"
        [defaultBillRef]="defaultBillReference()"
        [defaultMethod]="defaultBillMethod()"
        [defaultCreditDays]="activeSubModal()?.ledger?.defaultCreditPeriodDays ?? null"
        [voucherDate]="voucherDate()"
        [excludeVoucherId]="editingVoucherId()"
        [currencyScale]="companyContext.currencyScale()"
        [currencySymbol]="companyContext.currencySymbol()"
        [initialAllocations]="currentSubModalBillAllocations()"
        (allocationsCommitted)="onBillAllocationsCommitted($event)"
        (cancelled)="onSubModalCancelled()"
      />

      <mc-cost-allocation-popover
        [isOpen]="activeSubModal()?.stage === 'COST'"
        [ledgerName]="activeSubModal()?.ledger?.name ?? ''"
        [targetAmount]="activeSubModal()?.amount ?? '0.00'"
        [targetSide]="activeSubModal()?.side ?? 'DR'"
        [currencyScale]="companyContext.currencyScale()"
        [currencySymbol]="companyContext.currencySymbol()"
        [initialAllocations]="currentSubModalCostAllocations()"
        (allocationsCommitted)="onCostAllocationsCommitted($event)"
        (cancelled)="onSubModalCancelled()"
      />

      <mc-bank-allocation-popover
        [isOpen]="activeSubModal()?.stage === 'BANK'"
        [bankLedgerId]="activeSubModal()?.ledger?.id ?? ''"
        [bankLedgerName]="activeSubModal()?.ledger?.name ?? ''"
        [targetAmount]="activeSubModal()?.amount ?? '0.00'"
        [targetSide]="activeSubModal()?.side ?? 'DR'"
        [voucherDate]="voucherDate()"
        [defaultFavouring]="defaultFavouringName()"
        [currencyScale]="companyContext.currencyScale()"
        [currencySymbol]="companyContext.currencySymbol()"
        [initialAllocations]="currentSubModalBankAllocations()"
        (allocationsCommitted)="onBankAllocationsCommitted($event)"
        (cancelled)="onSubModalCancelled()"
      />

      <!-- ======================================================================= -->
      <!-- Tally-Authentic Accept? Yes / No Confirmation Box                       -->
      <!-- ======================================================================= -->
      @if (acceptPromptOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          data-testid="voucher-accept-prompt"
          aria-label="Accept Voucher Confirmation"
          (keydown)="onAcceptPromptKeyDown($event)"
        >
          <div class="mc-vch-accept-box">
            <div class="mc-vch-accept-title">Accept?</div>
            <div class="mc-vch-accept-btns">
              <button
                #acceptYesBtn
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="accept-yes-btn"
                (click)="confirmAcceptYes()"
              >
                <strong>Y</strong>es (Enter)
              </button>
              <button
                type="button"
                class="mc-btn"
                data-testid="accept-no-btn"
                (click)="dismissAcceptPrompt()"
              >
                <strong>N</strong>o (Esc)
              </button>
            </div>
          </div>
        </div>
      }

      <!-- ======================================================================= -->
      <!-- Ctrl+H Change Mode Modal                                                -->
      <!-- ======================================================================= -->
      @if (changeModeModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          data-testid="change-mode-modal"
          aria-label="Change Voucher Mode (Ctrl+H)"
          (keydown)="onChangeModeKeyDown($event)"
        >
          <div class="mc-modal-dialog mc-vch-small-modal">
            <div class="mc-modal-header">
              <span>List of Modes (<span class="mc-key-ctrl">H</span>)</span>
              <button type="button" class="mc-btn" (click)="changeModeModalOpen.set(false)">Esc</button>
            </div>
            <ul class="mc-vch-modal-list" role="listbox">
              @for (m of availableViewsForCategory(); track m; let mIdx = $index) {
                <li
                  role="option"
                  class="mc-vch-modal-item"
                  [class.mc-vch-modal-item-active]="changeModeHighlightIdx() === mIdx"
                  [attr.aria-selected]="changeModeHighlightIdx() === mIdx"
                  (click)="applyPersistedView(m)"
                >
                  <span>{{ formatViewLabel(m) }}</span>
                  @if (persistedView() === m) {
                    <span class="mc-badge mc-badge-jade">Current</span>
                  }
                </li>
              }
            </ul>
          </div>
        </div>
      }

      <!-- ======================================================================= -->
      <!-- F10 Other Vouchers Modal (All 24 Predefined + Custom Voucher Types)     -->
      <!-- ======================================================================= -->
      @if (otherVouchersModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          data-testid="other-vouchers-modal"
          aria-label="List of Voucher Types (F10)"
          (keydown)="onOtherVouchersKeyDown($event)"
        >
          <div class="mc-modal-dialog mc-vch-other-modal">
            <div class="mc-modal-header">
              <span>List of Voucher Types (F10 — 24 Categories)</span>
              <button type="button" class="mc-btn" (click)="otherVouchersModalOpen.set(false)">Esc</button>
            </div>
            <div class="mc-vch-other-search">
              <input
                #otherVchSearchInput
                type="text"
                class="mc-input"
                [ngModel]="otherVouchersQuery()"
                (ngModelChange)="otherVouchersQuery.set($event); otherVouchersHighlightIdx.set(0)"
                placeholder="Filter 24 voucher types (Contra, Memorandum, Reversing Journal, Stock Journal...)"
              />
            </div>
            <ul class="mc-vch-modal-list" role="listbox">
              @for (vt of filteredOtherVoucherChoices(); track vt.key; let vIdx = $index) {
                <li
                  role="option"
                  class="mc-vch-modal-item"
                  [class.mc-vch-modal-item-active]="otherVouchersHighlightIdx() === vIdx"
                  (click)="selectOtherVoucherChoice(vt)"
                >
                  <span>{{ vt.name }}</span>
                  <div class="mc-vch-other-badges">
                    <span class="mc-badge mc-badge-slate">{{ vt.category }}</span>
                    @if (vt.shortcutKey) {
                      <mc-code-badge [code]="vt.shortcutKey" />
                    }
                  </div>
                </li>
              }
            </ul>
          </div>
        </div>
      }
    </div>
  `,
  styles: `
    .mc-vch-shell {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-vch-topbar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-3);
      flex-wrap: wrap;
      background: var(--mc-color-surface);
      border: var(--mc-border-strong);
      border-radius: var(--mc-radius-sm);
      padding: var(--mc-space-2) var(--mc-space-3);
    }

    .mc-vch-title-cluster {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      flex-wrap: wrap;
    }

    .mc-vch-type-pill {
      background: var(--mc-color-deep-ledger-slate);
      color: var(--mc-color-surface);
      font-weight: 700;
      padding: 0.28rem 0.75rem;
      border-radius: var(--mc-radius-xs);
      letter-spacing: 0.02em;
    }

    .mc-vch-cat-sales {
      background: var(--mc-color-verdigris-teal);
    }

    .mc-vch-cat-purchase {
      background: #1e3a5f;
    }

    .mc-vch-cat-payment {
      background: #7c2d12;
    }

    .mc-vch-cat-receipt {
      background: #065f46;
    }

    .mc-vch-no-box {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
    }

    .mc-vch-no-input {
      width: 110px;
    }

    .mc-vch-optional-badge {
      background: #fef3c7;
      color: #92400e;
      border: 1px solid #f59e0b;
      font-weight: 700;
    }

    .mc-vch-mode-label {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-vch-date-cluster {
      display: flex;
      align-items: flex-end;
      gap: var(--mc-space-3);
      flex-wrap: wrap;
    }

    .mc-vch-quick-btns {
      display: flex;
      align-items: center;
      gap: 6px;
      flex-wrap: wrap;
    }

    .mc-vch-date-box {
      min-width: 148px;
    }

    .mc-vch-date-input {
      height: 30px;
    }

    .mc-vch-shortcut-strip {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
    }

    .mc-vch-tab-btn {
      background: var(--mc-color-surface);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      padding: 0.2rem 0.55rem;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
      color: var(--mc-color-slate-800);
      cursor: pointer;

      &:hover {
        border-color: var(--mc-color-verdigris-teal);
      }
    }

    .mc-vch-tab-active {
      background: var(--mc-color-verdigris-teal);
      border-color: var(--mc-color-pressed-teal);
      color: var(--mc-color-surface);

      .mc-vch-tab-key {
        color: var(--mc-color-mint-signal);
      }
    }

    .mc-vch-tab-key {
      font-family: var(--mc-font-mono);
      font-size: 11px;
      color: var(--mc-color-teal-700);
      font-weight: 700;
    }

    .mc-vch-banner-error {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: #fef2f2;
      border: 1px solid var(--mc-color-danger-600);
      border-radius: var(--mc-radius-xs);
      font-weight: 600;
    }

    .mc-vch-banner-ok {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-teal-50);
      border: 1px solid var(--mc-color-verdigris-teal);
      color: var(--mc-color-teal-700);
      border-radius: var(--mc-radius-xs);
      font-weight: 600;
      font-size: var(--mc-font-size-xs);
    }

    .mc-vch-card {
      padding: var(--mc-space-3);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-vch-header-fields {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
      padding-bottom: var(--mc-space-2);
      border-bottom: var(--mc-border-subtle);
    }

    .mc-vch-supplier-row {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 260px));
      gap: var(--mc-space-3);
    }

    .mc-vch-rev-row {
      max-width: 260px;
    }

    .mc-vch-single-account-box {
      display: flex;
      align-items: flex-end;
      justify-content: space-between;
      gap: var(--mc-space-3);
      flex-wrap: wrap;
    }

    .mc-vch-single-field {
      flex: 1;
      min-width: 280px;
      max-width: 480px;
    }

    .mc-vch-curbal-pill {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 0.3rem 0.65rem;
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      font-size: var(--mc-font-size-xs);
      font-weight: 500;
    }

    .mc-vch-invoice-header-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: var(--mc-space-3);
      align-items: end;
    }

    .mc-vch-party-meta {
      display: flex;
      flex-direction: column;
      gap: 6px;
      font-size: var(--mc-font-size-xs);
    }

    .mc-vch-meta-line {
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .mc-vch-meta-lbl {
      color: var(--mc-color-slate-600);
      font-weight: 600;
    }

    .mc-vch-rcm-toggle {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-weight: 600;
      cursor: pointer;
    }

    .mc-vch-item-section {
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      background: var(--mc-color-canvas);
      padding: var(--mc-space-2);
    }

    .mc-vch-section-banner {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      color: var(--mc-color-slate-700);
      margin-bottom: var(--mc-space-2);
    }

    .mc-vch-col-qty {
      width: 110px;
    }

    .mc-vch-col-rate {
      width: 130px;
    }

    .mc-vch-col-uom {
      width: 80px;
    }

    .mc-vch-readonly-uom {
      display: inline-block;
      padding: 0.25rem 0.45rem;
      background: var(--mc-color-slate-100);
      border-radius: var(--mc-radius-xs);
      font-size: var(--mc-font-size-xs);
    }

    .mc-vch-grid-wrap {
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      overflow: visible;
    }

    .mc-vch-virtual-viewport {
      height: 360px;
      width: 100%;
    }

    .mc-vch-virtual-row {
      height: 40px;
      display: grid;
      grid-template-columns: 70px 1fr 180px;
      align-items: center;
      padding: 0 var(--mc-space-3);
      border-bottom: 1px solid var(--mc-color-slate-100);
    }

    .mc-vch-col-side {
      width: 84px;
    }

    .mc-vch-col-ratepct {
      width: 110px;
    }

    .mc-vch-col-amt {
      width: 185px;
    }

    .mc-vch-col-act {
      width: 36px;
    }

    .mc-vch-side-select {
      font-weight: 700;
    }

    .mc-vch-blank-cell {
      display: block;
      text-align: right;
      color: var(--mc-color-slate-300);
      padding-right: var(--mc-space-2);
    }

    .mc-vch-row-del {
      padding: 0 0.4rem;
      font-weight: 700;
      color: var(--mc-color-danger-600);
    }

    .mc-vch-alloc-chips {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
      margin-top: 4px;
    }

    .mc-vch-alloc-chip {
      background: var(--mc-color-teal-50);
      border: 1px solid var(--mc-color-teal-100);
      border-radius: var(--mc-radius-xs);
      padding: 0.1rem 0.45rem;
      font-size: 11px;
      font-weight: 600;
      color: var(--mc-color-slate-800);
      display: inline-flex;
      align-items: center;
      gap: 4px;
      cursor: pointer;
    }

    .mc-vch-party-bills-bar {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 6px;
      padding: var(--mc-space-2);
      border-top: var(--mc-border-subtle);
      background: var(--mc-color-canvas);
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
    }

    .mc-vch-addline-row {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-vch-footer {
      display: grid;
      grid-template-columns: minmax(260px, 1fr) auto;
      gap: var(--mc-space-4);
      align-items: end;
      padding-top: var(--mc-space-3);
      border-top: var(--mc-border-strong);
    }

    @media (max-width: 900px) {
      .mc-vch-footer {
        grid-template-columns: 1fr;
      }
    }

    .mc-vch-totals-panel {
      display: flex;
      align-items: center;
      gap: var(--mc-space-4);
      flex-wrap: wrap;
    }

    .mc-vch-total-metric {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
    }

    .mc-vch-total-lbl {
      font-size: 11px;
      font-weight: 700;
      color: var(--mc-color-slate-600);
      text-transform: uppercase;
    }

    .mc-vch-total-num {
      font-size: var(--mc-font-size-md);
      color: var(--mc-color-slate-900);
    }

    .mc-vch-diff-box {
      padding: 0.15rem 0.5rem;
      background: #fef2f2;
      border: 1px solid var(--mc-color-danger-600);
      border-radius: var(--mc-radius-xs);
    }

    .mc-vch-diff-num {
      color: var(--mc-color-danger-600);
    }

    .mc-vch-submit-btns {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-vch-accept-box {
      width: 260px;
      background: var(--mc-color-surface);
      border: 2px solid var(--mc-color-deep-ledger-slate);
      border-radius: var(--mc-radius-sm);
      box-shadow: var(--mc-shadow-modal);
      padding: var(--mc-space-4);
      text-align: center;
    }

    .mc-vch-accept-title {
      font-size: var(--mc-font-size-lg);
      font-weight: 700;
      color: var(--mc-color-slate-900);
      margin-bottom: var(--mc-space-3);
    }

    .mc-vch-accept-btns {
      display: flex;
      justify-content: center;
      gap: var(--mc-space-2);
    }

    .mc-vch-small-modal {
      width: min(380px, 92vw);
    }

    .mc-vch-other-modal {
      width: min(520px, 94vw);
    }

    .mc-vch-other-search {
      padding: var(--mc-space-2) var(--mc-space-3);
      border-bottom: var(--mc-border-subtle);
      background: var(--mc-color-canvas);
    }

    .mc-vch-modal-list {
      list-style: none;
      margin: 0;
      padding: 0;
      max-height: 320px;
      overflow-y: auto;
    }

    .mc-vch-modal-item {
      padding: 0.45rem 0.875rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: 1px solid var(--mc-color-slate-100);
      font-weight: 500;
      cursor: pointer;
    }

    .mc-vch-modal-item-active {
      background: var(--mc-color-verdigris-teal);
      color: var(--mc-color-surface);
    }

    .mc-vch-other-badges {
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
  `,
})
export class VoucherGridComponent implements OnInit {
  readonly companyContext = inject(CompanyContextStore);
  readonly coaStore = inject(ChartOfAccountsStore);
  private readonly voucherService = inject(VoucherService);
  private readonly secondaryMasterModal = inject(SecondaryMasterModalService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly navStack = inject(NavigationStackStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly embeddedOverlay = input<boolean>(false);
  readonly initialVoucherId = input<string | null>(null);
  readonly initialCategory = input<VoucherBaseCategory | null>(null);

  private readonly voucherDateInputRef = viewChild<ElementRef<HTMLInputElement>>('voucherDateInput');
  private readonly singleAccountSelectRef = viewChild<TypeaheadSelectComponent>('singleAccountSelect');
  private readonly partyAccountSelectRef = viewChild<TypeaheadSelectComponent>('partyAccountSelect');
  private readonly narrationInputRef = viewChild<ElementRef<HTMLInputElement>>('narrationInput');
  private readonly acceptYesBtnRef = viewChild<ElementRef<HTMLButtonElement>>('acceptYesBtn');
  private readonly otherVchSearchInputRef = viewChild<ElementRef<HTMLInputElement>>('otherVchSearchInput');
  private readonly lineLedgerSelectRefs = viewChildren<TypeaheadSelectComponent>('lineLedgerSelect');
  private readonly lineAmountCellRefs = viewChildren<AmountCellComponent>('lineAmountCell');

  readonly gstStates = INDIAN_GST_STATES;

  readonly primaryVoucherTabs: readonly {
    readonly key: string;
    readonly label: string;
    readonly category: VoucherBaseCategory;
  }[] = [
    { key: 'F4', label: 'Contra', category: 'CONTRA' },
    { key: 'F5', label: 'Payment', category: 'PAYMENT' },
    { key: 'F6', label: 'Receipt', category: 'RECEIPT' },
    { key: 'F7', label: 'Journal', category: 'JOURNAL' },
    { key: 'F8', label: 'Sales', category: 'SALES' },
    { key: 'F9', label: 'Purchase', category: 'PURCHASE' },
    { key: 'Alt+F5', label: 'Debit Note', category: 'DEBIT_NOTE' },
    { key: 'Alt+F6', label: 'Credit Note', category: 'CREDIT_NOTE' },
  ];

  // Voucher Header & Identity Signals
  readonly editingVoucherId = signal<string | null>(null);
  readonly editingVersionNo = signal<number>(1);
  readonly editingStatus = signal<VoucherLifecycleStatus>('DRAFT');

  readonly activeCategory = signal<VoucherBaseCategory>('PAYMENT');
  readonly activeVoucherTypeId = signal<string | null>(null);
  readonly activeVoucherTypeName = signal<string>('Payment');
  readonly voucherNumber = signal<string>('1');
  readonly voucherDate = signal<string>('2025-04-01');
  readonly reversingApplicableUpto = signal<string>('');
  readonly persistedView = signal<VoucherPersistedView>('SINGLE_ENTRY');
  readonly postingNature = signal<VoucherPostingNature>('NORMAL');

  // Commercial / Single-Entry Header Signals
  readonly supplierInvoiceNo = signal<string>('');
  readonly supplierInvoiceDate = signal<string>('2025-04-01');
  readonly headerAccountLedgerId = signal<string | null>(null);
  readonly headerBankAllocations = signal<readonly BankAllocationDto[]>([]);
  readonly partyLedgerId = signal<string | null>(null);
  readonly partyBillAllocations = signal<readonly BillAllocationDto[]>([]);
  readonly accountingLedgerId = signal<string | null>(null);
  readonly placeOfSupplyState = signal<string>('33');
  readonly partyGstin = signal<string | null>(null);
  readonly partyMailingName = signal<string | null>(null);
  readonly partyStateCode = signal<string | null>(null);
  readonly isReverseCharge = signal<boolean>(false);
  readonly narration = signal<string>('');

  // Grid Lines & Phase 4 Item Hook Signals
  readonly gridLines = signal<readonly EditableVoucherLine[]>([]);
  readonly itemHookRows = signal<readonly VoucherInventoryItemHookDto[]>([]);

  // Modal & UI Status Signals
  readonly activeSubModal = signal<ActiveSubModalContext | null>(null);
  readonly acceptPromptOpen = signal<boolean>(false);
  readonly changeModeModalOpen = signal<boolean>(false);
  readonly changeModeHighlightIdx = signal<number>(0);
  readonly otherVouchersModalOpen = signal<boolean>(false);
  readonly otherVouchersQuery = signal<string>('');
  readonly otherVouchersHighlightIdx = signal<number>(0);

  readonly saving = signal<boolean>(false);
  readonly errorBanner = signal<string | null>(null);
  readonly savedBanner = signal<string | null>(null);

  private rowCounter = 1;

  // ---------------------------------------------------------------------------
  // Computed Lookups & Ledger Typeahead Options
  // ---------------------------------------------------------------------------

  readonly isPurchaseOrDebitNote = computed(() => {
    const cat = this.activeCategory();
    return cat === 'PURCHASE' || cat === 'DEBIT_NOTE' || cat === 'PURCHASE_ORDER' || cat === 'RECEIPT_NOTE';
  });

  readonly isOutwardInvoice = computed(() => {
    const cat = this.activeCategory();
    return cat === 'SALES' || cat === 'CREDIT_NOTE' || cat === 'SALES_ORDER' || cat === 'DELIVERY_NOTE';
  });

  readonly isManualNumbering = computed(() => {
    const vtId = this.activeVoucherTypeId();
    if (!vtId) return false;
    const vt = this.coaStore.voucherTypes().find((t) => t.id === vtId);
    return vt?.numberingMethod === 'MANUAL';
  });

  readonly availableViewsForCategory = computed<readonly VoucherPersistedView[]>(() => {
    const spec = resolveVoucherSpecByCategory(this.activeCategory());
    return spec.allowedViews;
  });

  readonly persistedViewLabel = computed(() => this.formatViewLabel(this.persistedView()));

  private mapLedgersToOptions(ledgers: readonly Ledger[]): readonly TypeaheadOption[] {
    return ledgers.map((l) => ({
      id: l.id,
      label: l.name,
      secondaryLabel: this.coaStore.groups().find((g) => g.id === l.groupId)?.name ?? l.groupName ?? undefined,
      codeBadge: l.gstin ?? l.aliasCode ?? undefined,
    }));
  }

  readonly allLedgerOptions = computed<readonly TypeaheadOption[]>(() =>
    this.mapLedgersToOptions(this.coaStore.ledgers().filter((l) => l.active !== false)),
  );

  readonly cashBankLedgerOptions = computed<readonly TypeaheadOption[]>(() => {
    const groups = this.coaStore.groups();
    const filtered = this.coaStore
      .ledgers()
      .filter((l) => l.active !== false && isCashOrBankLedger(l, groups));
    return filtered.length > 0 ? this.mapLedgersToOptions(filtered) : this.allLedgerOptions();
  });

  readonly singleEntryParticularsOptions = computed<readonly TypeaheadOption[]>(() => {
    if (this.activeCategory() === 'CONTRA') {
      return this.cashBankLedgerOptions();
    }
    const groups = this.coaStore.groups();
    const nonCashBank = this.coaStore
      .ledgers()
      .filter((l) => l.active !== false && !isCashOrBankLedger(l, groups));
    return nonCashBank.length > 0 ? this.mapLedgersToOptions(nonCashBank) : this.allLedgerOptions();
  });

  readonly partyAndCashBankLedgerOptions = computed<readonly TypeaheadOption[]>(() => {
    const groups = this.coaStore.groups();
    const matched = this.coaStore
      .ledgers()
      .filter((l) => l.active !== false && (isPartyLedger(l, groups) || isCashOrBankLedger(l, groups)));
    return matched.length > 0 ? this.mapLedgersToOptions(matched) : this.allLedgerOptions();
  });

  readonly salesPurchaseLedgerOptions = computed<readonly TypeaheadOption[]>(() => {
    const groups = this.coaStore.groups();
    const matched = this.coaStore
      .ledgers()
      .filter((l) => l.active !== false && isSalesOrPurchaseLedger(l, groups));
    return matched.length > 0 ? this.mapLedgersToOptions(matched) : this.allLedgerOptions();
  });

  readonly invoiceBodyLedgerOptions = computed<readonly TypeaheadOption[]>(() => {
    const groups = this.coaStore.groups();
    const matched = this.coaStore
      .ledgers()
      .filter((l) => l.active !== false && !isCashOrBankLedger(l, groups) && !isPartyLedger(l, groups));
    return matched.length > 0 ? this.mapLedgersToOptions(matched) : this.allLedgerOptions();
  });

  readonly selectedHeaderAccountLedger = computed<Ledger | null>(() => {
    const id = this.headerAccountLedgerId();
    return id ? (this.coaStore.ledgers().find((l) => l.id === id) ?? null) : null;
  });

  readonly selectedPartyLedger = computed<Ledger | null>(() => {
    const id = this.partyLedgerId();
    return id ? (this.coaStore.ledgers().find((l) => l.id === id) ?? null) : null;
  });

  readonly filteredOtherVoucherChoices = computed(() => {
    const q = this.otherVouchersQuery().trim().toLowerCase();
    const loadedVts = this.coaStore.voucherTypes();
    const choices =
      loadedVts.length > 0
        ? loadedVts.map((vt) => ({
            key: vt.id,
            id: vt.id,
            name: vt.name,
            category: vt.baseCategory,
            shortcutKey: vt.shortcutKey,
          }))
        : PREDEFINED_24_VOUCHER_SPECS.map((s) => ({
            key: s.category,
            id: null as string | null,
            name: s.name,
            category: s.category,
            shortcutKey: s.shortcutKey,
          }));

    if (!q) return choices;
    return choices.filter(
      (c) => c.name.toLowerCase().includes(q) || c.category.toLowerCase().includes(q),
    );
  });

  // ---------------------------------------------------------------------------
  // ExactDecimal Running Totals Computation
  // ---------------------------------------------------------------------------

  readonly totals = computed(() => {
    const scale = this.companyContext.currencyScale();
    const view = this.persistedView();

    if (view === 'AS_VOUCHER') {
      let dr = ExactDecimal.zero(scale);
      let cr = ExactDecimal.zero(scale);
      for (const row of this.gridLines()) {
        if (!row.ledgerId) continue;
        const amt = ExactDecimal.tryParse(row.amount, scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
        if (row.side === 'DR') {
          dr = dr.add(amt);
        } else {
          cr = cr.add(amt);
        }
      }
      const diff = dr.sub(cr).abs();
      const isBalanced = dr.equals(cr);
      return {
        totalDebit: dr,
        totalCredit: cr,
        totalDebitWire: dr.toWireString(scale),
        totalCreditWire: cr.toWireString(scale),
        diffWire: diff.toWireString(scale),
        isBalanced,
        isBalancedAndPositive: isBalanced && dr.isPositive(),
      };
    }

    if (view === 'SINGLE_ENTRY') {
      let sum = ExactDecimal.zero(scale);
      for (const row of this.gridLines()) {
        if (!row.ledgerId) continue;
        const amt = ExactDecimal.tryParse(row.amount, scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
        sum = sum.add(amt);
      }
      const hasHeaderAcct = Boolean(this.headerAccountLedgerId());
      const dr = this.activeCategory() === 'PAYMENT' ? sum : hasHeaderAcct ? sum : ExactDecimal.zero(scale);
      const cr = this.activeCategory() === 'PAYMENT' ? (hasHeaderAcct ? sum : ExactDecimal.zero(scale)) : sum;
      const diff = dr.sub(cr).abs();
      return {
        totalDebit: dr,
        totalCredit: cr,
        totalDebitWire: dr.toWireString(scale),
        totalCreditWire: cr.toWireString(scale),
        diffWire: diff.toWireString(scale),
        isBalanced: dr.equals(cr),
        isBalancedAndPositive: dr.equals(cr) && sum.isPositive(),
      };
    }

    // ACCOUNTING_INVOICE or ITEM_INVOICE
    let itemSubtotal = ExactDecimal.zero(scale);
    if (view === 'ITEM_INVOICE') {
      for (const item of this.itemHookRows()) {
        if (!item.stockItemName.trim()) continue;
        const amt = ExactDecimal.tryParse(item.amount, scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
        itemSubtotal = itemSubtotal.add(amt);
      }
    }

    const { partySide } = invoicePartyAndRevenueSides(this.activeCategory());
    let netInvoiceTotal = itemSubtotal;

    for (const row of this.gridLines()) {
      if (!row.ledgerId) continue;
      const amt = ExactDecimal.tryParse(row.amount, scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
      if (row.lineRole === 'DISCOUNT') {
        netInvoiceTotal = netInvoiceTotal.sub(amt);
      } else if (row.side === partySide && row.lineRole === 'ROUND_OFF') {
        netInvoiceTotal = netInvoiceTotal.sub(amt);
      } else {
        netInvoiceTotal = netInvoiceTotal.add(amt);
      }
    }

    const hasParty = Boolean(this.partyLedgerId());
    const hasAccountingForItems =
      view !== 'ITEM_INVOICE' || itemSubtotal.isZero() || Boolean(this.accountingLedgerId());
    const balancedTotal = netInvoiceTotal.isPositive() ? netInvoiceTotal : ExactDecimal.zero(scale);
    const dr =
      partySide === 'DR'
        ? hasParty
          ? balancedTotal
          : ExactDecimal.zero(scale)
        : hasAccountingForItems
          ? balancedTotal
          : ExactDecimal.zero(scale);
    const cr =
      partySide === 'CR'
        ? hasParty
          ? balancedTotal
          : ExactDecimal.zero(scale)
        : hasAccountingForItems
          ? balancedTotal
          : ExactDecimal.zero(scale);
    const diff = dr.sub(cr).abs();

    return {
      totalDebit: dr,
      totalCredit: cr,
      totalDebitWire: dr.toWireString(scale),
      totalCreditWire: cr.toWireString(scale),
      diffWire: diff.toWireString(scale),
      isBalanced: dr.equals(cr),
      isBalancedAndPositive: dr.equals(cr) && balancedTotal.isPositive(),
    };
  });

  // Sub-Modal Helper Computeds
  readonly defaultBillReference = computed(() => {
    if (this.isPurchaseOrDebitNote() && this.supplierInvoiceNo().trim()) {
      return this.supplierInvoiceNo().trim();
    }
    return this.voucherNumber().trim() || '1';
  });

  readonly defaultBillMethod = computed<BillAllocationType>(() => {
    const cat = this.activeCategory();
    if (cat === 'PAYMENT' || cat === 'RECEIPT') {
      return 'AGST_REF';
    }
    return 'NEW_REF';
  });

  readonly defaultFavouringName = computed(() => {
    if (this.selectedPartyLedger()) {
      return this.selectedPartyLedger()!.name;
    }
    const firstNonEmpty = this.gridLines().find((l) => l.ledgerName);
    return firstNonEmpty?.ledgerName ?? '';
  });

  readonly currentSubModalBillAllocations = computed<readonly BillAllocationDto[]>(() => {
    const sub = this.activeSubModal();
    if (!sub || sub.stage !== 'BILL') return [];
    if (sub.target === 'HEADER_PARTY') return this.partyBillAllocations();
    return this.gridLines()[sub.lineIndex]?.billAllocations ?? [];
  });

  readonly currentSubModalCostAllocations = computed<readonly CostAllocationDto[]>(() => {
    const sub = this.activeSubModal();
    if (!sub || sub.stage !== 'COST') return [];
    return this.gridLines()[sub.lineIndex]?.costAllocations ?? [];
  });

  readonly currentSubModalBankAllocations = computed<readonly BankAllocationDto[]>(() => {
    const sub = this.activeSubModal();
    if (!sub || sub.stage !== 'BANK') return [];
    if (sub.target === 'HEADER_ACCOUNT') return this.headerBankAllocations();
    return this.gridLines()[sub.lineIndex]?.bankAllocations ?? [];
  });

  // ---------------------------------------------------------------------------
  // Lifecycle & Keyboard Bindings
  // ---------------------------------------------------------------------------

  async ngOnInit(): Promise<void> {
    const noModalGuard = () =>
      !this.activeSubModal() &&
      !this.secondaryMasterModal.isOpen() &&
      !this.acceptPromptOpen() &&
      !this.changeModeModalOpen() &&
      !this.otherVouchersModalOpen() &&
      (!this.navStack.hasOverlays() || this.embeddedOverlay());

    const disposers: (() => void)[] = [
      this.shortcutRegistry.bindHandler(
        'voucher.contra',
        'SCREEN',
        () => this.switchToCategory('CONTRA'),
        noModalGuard,
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.payment',
        'SCREEN',
        () => this.switchToCategory('PAYMENT'),
        noModalGuard,
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.receipt',
        'SCREEN',
        () => this.switchToCategory('RECEIPT'),
        noModalGuard,
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.journal',
        'SCREEN',
        () => this.switchToCategory('JOURNAL'),
        noModalGuard,
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.sales',
        'SCREEN',
        () => this.switchToCategory('SALES'),
        noModalGuard,
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.purchase',
        'SCREEN',
        () => this.switchToCategory('PURCHASE'),
        noModalGuard,
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.debitNote',
        'SCREEN',
        () => this.switchToCategory('DEBIT_NOTE'),
        noModalGuard,
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.creditNote',
        'SCREEN',
        () => this.switchToCategory('CREDIT_NOTE'),
        noModalGuard,
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.stockJournal',
        'SCREEN',
        () => this.switchToCategory('STOCK_JOURNAL'),
        noModalGuard,
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.physicalStock',
        'SCREEN',
        () => this.switchToCategory('PHYSICAL_STOCK'),
        noModalGuard,
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.otherVouchers',
        'SCREEN',
        () => this.openOtherVouchersModal(),
        noModalGuard,
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.changeMode',
        'SCREEN',
        () => this.openChangeModeModal(),
        noModalGuard,
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.toggleOptional',
        'SCREEN',
        () => this.toggleOptional(),
        noModalGuard,
      ),
      this.shortcutRegistry.bindHandler(
        'context.changeDate',
        'SCREEN',
        () => {
          this.voucherDateInputRef()?.nativeElement.focus();
        },
        noModalGuard,
      ),
      this.shortcutRegistry.bindHandler(
        'screen.accept',
        'SCREEN',
        () => {
          void this.submitVoucher();
        },
        noModalGuard,
      ),
      this.shortcutRegistry.bindHandler(
        'register.cancelVoucher',
        'SCREEN',
        () => {
          void this.cancelLoadedVoucher();
        },
        () => noModalGuard() && Boolean(this.editingVoucherId()),
      ),
      this.navStack.registerDraftLifecycleHooks(
        () => this.captureDraftSnapshot(),
        (snap) => this.restoreDraftSnapshot(snap),
      ),
    ];

    this.destroyRef.onDestroy(() => {
      for (const d of disposers) d();
      this.companyContext.setHasUnsavedDraft(false);
    });

    this.voucherDate.set(this.companyContext.workingDate());
    this.supplierInvoiceDate.set(this.companyContext.workingDate());
    this.placeOfSupplyState.set(this.companyContext.activeCompany()?.stateCode ?? '33');

    await Promise.all([
      this.coaStore.ensureAccountingMastersLoaded(),
      this.coaStore.voucherTypes().length === 0
        ? this.coaStore.loadVoucherTypes().catch(() => [])
        : Promise.resolve([]),
    ]);

    // Check Alteration Mode (`/vouchers/:voucherId`)
    const routeVoucherId =
      this.initialVoucherId() ?? this.route.snapshot.paramMap.get('voucherId');
    if (routeVoucherId && routeVoucherId !== 'new') {
      await this.loadExistingVoucherForAlteration(routeVoucherId);
      return;
    }

    // Check Alt+2 Staged Duplicate Draft
    const duplicateDraft = this.voucherService.consumeDuplicateDraft();
    if (duplicateDraft) {
      await this.hydrateFromVoucherDetail(duplicateDraft, true);
      return;
    }

    // Check Seeded Category / Date from Gateway, F4-F9, or Day Book Alt+A / Alt+I
    const stagedSeed = this.voucherService.consumeSeedParams();
    const queryCat = (this.route.snapshot.queryParamMap.get('category') ??
      stagedSeed?.voucherCategory ??
      this.initialCategory()) as VoucherBaseCategory | null;
    const queryDate = this.route.snapshot.queryParamMap.get('date') ?? stagedSeed?.voucherDate;
    if (queryDate) {
      this.voucherDate.set(queryDate);
      this.supplierInvoiceDate.set(queryDate);
    }

    this.initializeBlankVoucher(queryCat ?? 'PAYMENT');
  }

  trackLineById(_idx: number, row: EditableVoucherLine): string {
    return row.rowId;
  }

  markDirty(): void {
    this.companyContext.setHasUnsavedDraft(true);
  }

  // ---------------------------------------------------------------------------
  // Category & Mode Switching (F4-F10, Ctrl+H, Ctrl+L)
  // ---------------------------------------------------------------------------

  initializeBlankVoucher(category: VoucherBaseCategory): void {
    const spec = resolveVoucherSpecByCategory(category);
    const matchedVt = this.coaStore
      .voucherTypes()
      .find((vt) => vt.baseCategory === category && vt.isSystemVoucherType);

    this.editingVoucherId.set(null);
    this.editingVersionNo.set(1);
    this.editingStatus.set('DRAFT');

    this.activeCategory.set(category);
    this.activeVoucherTypeId.set(matchedVt?.id ?? null);
    this.activeVoucherTypeName.set(matchedVt?.name ?? spec.name);
    this.persistedView.set(spec.defaultPersistedView);
    this.postingNature.set(
      matchedVt?.makeOptionalByDefault ? 'OPTIONAL' : spec.defaultPostingNature,
    );

    this.supplierInvoiceNo.set('');
    this.supplierInvoiceDate.set(this.voucherDate());
    this.headerAccountLedgerId.set(null);
    this.headerBankAllocations.set([]);
    this.partyLedgerId.set(null);
    this.partyBillAllocations.set([]);
    this.accountingLedgerId.set(null);
    this.partyGstin.set(null);
    this.partyMailingName.set(null);
    this.partyStateCode.set(null);
    this.isReverseCharge.set(false);
    this.narration.set('');
    this.errorBanner.set(null);

    const scale = this.companyContext.currencyScale();
    const zeroWire = ExactDecimal.zero(scale).toWireString(scale);
    const firstSide = this.defaultLineSideForCurrentView(spec);

    this.gridLines.set([this.makeBlankLine(firstSide, zeroWire)]);
    this.itemHookRows.set(
      spec.defaultPersistedView === 'ITEM_INVOICE'
        ? [
            {
              rowId: `item-${this.rowCounter++}`,
              stockItemId: null,
              stockItemName: '',
              quantity: '1',
              uomCode: 'NOS',
              alternateUomId: null,
              rate: zeroWire,
              amount: zeroWire,
            },
          ]
        : [],
    );

    this.companyContext.setHasUnsavedDraft(false);
    void this.refreshNextVoucherNumber();
    this.focusPrimaryVoucherEntryField();
  }

  switchToCategory(category: VoucherBaseCategory): void {
    if (this.editingVoucherId() && this.activeCategory() === category) {
      return;
    }
    const spec = resolveVoucherSpecByCategory(category);
    const matchedVt = this.coaStore
      .voucherTypes()
      .find((vt) => vt.baseCategory === category && vt.isSystemVoucherType);

    this.activeCategory.set(category);
    this.activeVoucherTypeId.set(matchedVt?.id ?? null);
    this.activeVoucherTypeName.set(matchedVt?.name ?? spec.name);

    if (!spec.allowedViews.includes(this.persistedView())) {
      this.applyPersistedView(spec.defaultPersistedView);
    } else if (
      this.gridLines().every((l) => !l.ledgerId) &&
      !this.headerAccountLedgerId() &&
      !this.partyLedgerId()
    ) {
      this.persistedView.set(spec.defaultPersistedView);
      const firstSide = this.defaultLineSideForCurrentView(spec);
      this.gridLines.set([
        this.makeBlankLine(
          firstSide,
          ExactDecimal.zero(this.companyContext.currencyScale()).toWireString(
            this.companyContext.currencyScale(),
          ),
        ),
      ]);
    }

    if (category === 'MEMORANDUM') {
      this.postingNature.set('MEMORANDUM');
    } else if (category === 'REVERSING_JOURNAL') {
      this.postingNature.set('REVERSING_JOURNAL');
    } else if (
      this.postingNature() === 'MEMORANDUM' ||
      this.postingNature() === 'REVERSING_JOURNAL'
    ) {
      this.postingNature.set('NORMAL');
    }

    void this.refreshNextVoucherNumber();
    this.focusPrimaryVoucherEntryField();
  }

  toggleOptional(): void {
    const current = this.postingNature();
    if (current === 'MEMORANDUM' || current === 'REVERSING_JOURNAL') {
      return;
    }
    this.postingNature.set(current === 'OPTIONAL' ? 'NORMAL' : 'OPTIONAL');
    this.markDirty();
  }

  openChangeModeModal(): void {
    const allowed = this.availableViewsForCategory();
    if (allowed.length <= 1) {
      return;
    }
    const curIdx = Math.max(0, allowed.indexOf(this.persistedView()));
    this.changeModeHighlightIdx.set(curIdx);
    this.changeModeModalOpen.set(true);
  }

  onChangeModeKeyDown(event: KeyboardEvent): void {
    const allowed = this.availableViewsForCategory();
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.changeModeModalOpen.set(false);
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.changeModeHighlightIdx.set((this.changeModeHighlightIdx() + 1) % allowed.length);
      return;
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.changeModeHighlightIdx.set(
        (this.changeModeHighlightIdx() - 1 + allowed.length) % allowed.length,
      );
      return;
    }
    if (event.key === 'Enter') {
      event.preventDefault();
      const target = allowed[this.changeModeHighlightIdx()];
      if (target) {
        this.applyPersistedView(target);
      }
    }
  }

  applyPersistedView(nextView: VoucherPersistedView): void {
    const prevView = this.persistedView();
    this.changeModeModalOpen.set(false);
    if (prevView === nextView) {
      return;
    }

    const scale = this.companyContext.currencyScale();
    const zeroWire = ExactDecimal.zero(scale).toWireString(scale);

    // Convert cleanly if switching between SINGLE_ENTRY and AS_VOUCHER
    if (prevView === 'SINGLE_ENTRY' && nextView === 'AS_VOUCHER') {
      const headerId = this.headerAccountLedgerId();
      const headerLed = this.selectedHeaderAccountLedger();
      const existingRows = this.gridLines().filter((l) => l.ledgerId);
      if (headerId && headerLed && existingRows.length > 0) {
        const headerSide: BalanceSide = this.activeCategory() === 'PAYMENT' ? 'CR' : 'DR';
        const lineSide: BalanceSide = headerSide === 'DR' ? 'CR' : 'DR';
        const sum = ExactDecimal.sum(
          existingRows.map((r) => r.amount),
          scale,
        ).toWireString(scale);

        const converted: EditableVoucherLine[] = [
          ...existingRows.map((r) => ({ ...r, side: lineSide })),
          {
            rowId: `vline-${this.rowCounter++}`,
            side: headerSide,
            lineRole: 'LEDGER',
            ledgerId: headerId,
            ledgerName: headerLed.name,
            ratePct: '',
            computedTaxAmount: null,
            isGstOverridden: false,
            amount: sum,
            lineNarration: '',
            billAllocations: [],
            costAllocations: [],
            bankAllocations: this.headerBankAllocations(),
          },
        ];
        this.gridLines.set(converted);
      } else {
        const spec = resolveVoucherSpecByCategory(this.activeCategory());
        this.gridLines.set([this.makeBlankLine(spec.defaultFirstSide, zeroWire)]);
      }
    } else if (nextView === 'ITEM_INVOICE' && this.itemHookRows().length === 0) {
      this.itemHookRows.set([
        {
          rowId: `item-${this.rowCounter++}`,
          stockItemId: null,
          stockItemName: '',
          quantity: '1',
          uomCode: 'NOS',
          alternateUomId: null,
          rate: zeroWire,
          amount: zeroWire,
        },
      ]);
    } else if (this.gridLines().length === 0) {
      const spec = resolveVoucherSpecByCategory(this.activeCategory());
      this.gridLines.set([this.makeBlankLine(this.defaultLineSideForView(nextView, spec), zeroWire)]);
    }

    this.persistedView.set(nextView);
    this.focusPrimaryVoucherEntryField();
  }

  openOtherVouchersModal(): void {
    this.otherVouchersQuery.set('');
    this.otherVouchersHighlightIdx.set(0);
    this.otherVouchersModalOpen.set(true);
    queueMicrotask(() => {
      this.otherVchSearchInputRef()?.nativeElement.focus();
    });
  }

  onOtherVouchersKeyDown(event: KeyboardEvent): void {
    const items = this.filteredOtherVoucherChoices();
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.otherVouchersModalOpen.set(false);
      return;
    }
    if (event.key === 'ArrowDown' && items.length > 0) {
      event.preventDefault();
      this.otherVouchersHighlightIdx.set((this.otherVouchersHighlightIdx() + 1) % items.length);
      return;
    }
    if (event.key === 'ArrowUp' && items.length > 0) {
      event.preventDefault();
      this.otherVouchersHighlightIdx.set(
        (this.otherVouchersHighlightIdx() - 1 + items.length) % items.length,
      );
      return;
    }
    if (event.key === 'Enter' && items.length > 0) {
      event.preventDefault();
      this.selectOtherVoucherChoice(items[this.otherVouchersHighlightIdx()]);
    }
  }

  selectOtherVoucherChoice(choice: {
    readonly id: string | null;
    readonly name: string;
    readonly category: VoucherBaseCategory;
  }): void {
    this.otherVouchersModalOpen.set(false);
    this.switchToCategory(choice.category);
    if (choice.id) {
      this.activeVoucherTypeId.set(choice.id);
      this.activeVoucherTypeName.set(choice.name);
      void this.refreshNextVoucherNumber();
    }
  }

  onVoucherDateChange(nextDate: string): void {
    this.voucherDate.set(nextDate);
    this.companyContext.setWorkingDate(nextDate);
    this.markDirty();
    if (!this.editingVoucherId()) {
      void this.refreshNextVoucherNumber();
    }
  }

  private async refreshNextVoucherNumber(): Promise<void> {
    if (this.editingVoucherId()) return;
    const cid = this.companyContext.activeCompany()?.id;
    const vtId = this.activeVoucherTypeId();
    if (!cid || !vtId) {
      return;
    }
    try {
      const nextNo = await this.voucherService.getNextVoucherNumber(
        cid,
        vtId,
        this.voucherDate(),
      );
      if (nextNo) {
        this.voucherNumber.set(nextNo);
      }
    } catch {
      // Keep default sequence placeholder when offline / unseeded
    }
  }

  // ---------------------------------------------------------------------------
  // Header & Grid Line Event Handlers
  // ---------------------------------------------------------------------------

  onSingleEntryAccountSelected(opt: TypeaheadOption): void {
    this.headerAccountLedgerId.set(opt.id);
    this.headerBankAllocations.set([]);
    this.markDirty();
    queueMicrotask(() => {
      this.lineLedgerSelectRefs()[0]?.focusInput();
    });
  }

  onPartyLedgerSelected(opt: TypeaheadOption): void {
    this.partyLedgerId.set(opt.id);
    this.partyBillAllocations.set([]);
    const led = this.coaStore.ledgers().find((l) => l.id === opt.id);
    if (led) {
      this.partyGstin.set(led.gstin);
      this.partyMailingName.set(led.mailingName ?? led.name);
      this.partyStateCode.set(led.stateCode);
      if (led.stateCode) {
        this.placeOfSupplyState.set(led.stateCode);
      }
    }
    this.markDirty();
  }

  onAccountingLedgerSelected(opt: TypeaheadOption): void {
    this.accountingLedgerId.set(opt.id);
    this.markDirty();
  }

  onLineSideChange(idx: number, nextSide: BalanceSide): void {
    this.gridLines.set(
      this.gridLines().map((r, i) => (i === idx ? { ...r, side: nextSide } : r)),
    );
    this.markDirty();
  }

  onLineLedgerSelected(idx: number, opt: TypeaheadOption): void {
    const led = this.coaStore.ledgers().find((l) => l.id === opt.id);
    const groups = this.coaStore.groups();
    const view = this.persistedView();
    const scale = this.companyContext.currencyScale();

    this.gridLines.set(
      this.gridLines().map((r, i) => {
        if (i !== idx) return r;
        const role: VoucherLineRole =
          view === 'ACCOUNTING_INVOICE' || view === 'ITEM_INVOICE'
            ? led
              ? inferInvoiceLineRole(led, groups)
              : 'ACCOUNTING'
            : 'LEDGER';

        const { partySide, revenueExpenseSide } = invoicePartyAndRevenueSides(
          this.activeCategory(),
        );
        const side: BalanceSide =
          view === 'ACCOUNTING_INVOICE' || view === 'ITEM_INVOICE'
            ? role === 'DISCOUNT'
              ? partySide
              : revenueExpenseSide
            : view === 'SINGLE_ENTRY'
              ? this.activeCategory() === 'PAYMENT'
                ? 'DR'
                : 'CR'
              : r.side;

        let nextRatePct = r.ratePct;
        let nextAmount = r.amount;
        let computedTax: string | null = r.computedTaxAmount;

        if ((view === 'ACCOUNTING_INVOICE' || view === 'ITEM_INVOICE') && led) {
          if (led.gstRatePct && !nextRatePct) {
            nextRatePct = led.gstRatePct;
          }
          if (nextRatePct && role === 'TAX') {
            const base = this.computeInvoiceTaxableBase(idx);
            const pctDec = ExactDecimal.tryParse(nextRatePct, 4, 'HALF_UP');
            if (pctDec && base.isPositive()) {
              const hundred = ExactDecimal.fromInt(100, 2);
              const taxAmt = base.mulScaled(pctDec, 6, 'HALF_UP').divHalfUp(hundred, scale);
              nextAmount = taxAmt.toWireString(scale);
              computedTax = nextAmount;
            }
          }
        }

        return {
          ...r,
          side,
          lineRole: role,
          ledgerId: opt.id,
          ledgerName: opt.label,
          ratePct: nextRatePct,
          computedTaxAmount: computedTax,
          isGstOverridden: false,
          amount: nextAmount,
          billAllocations: r.ledgerId === opt.id ? r.billAllocations : [],
          costAllocations: r.ledgerId === opt.id ? r.costAllocations : [],
          bankAllocations: r.ledgerId === opt.id ? r.bankAllocations : [],
        };
      }),
    );

    this.markDirty();
    queueMicrotask(() => {
      this.lineAmountCellRefs()[idx]?.focusInput();
    });
  }

  onLineAmountChange(idx: number, amountWire: string): void {
    this.gridLines.set(
      this.gridLines().map((r, i) => (i === idx ? { ...r, amount: amountWire } : r)),
    );
    this.markDirty();
  }

  onInvoiceLineRatePctChange(idx: number, rawPct: string): void {
    const scale = this.companyContext.currencyScale();
    const base = this.computeInvoiceTaxableBase(idx);
    const pctDec = ExactDecimal.tryParse(rawPct.trim(), 4, 'HALF_UP');

    this.gridLines.set(
      this.gridLines().map((r, i) => {
        if (i !== idx) return r;
        if (pctDec && base.isPositive()) {
          const hundred = ExactDecimal.fromInt(100, 2);
          const computed = base
            .mulScaled(pctDec, 6, 'HALF_UP')
            .divHalfUp(hundred, scale)
            .toWireString(scale);
          return {
            ...r,
            ratePct: rawPct,
            amount: computed,
            computedTaxAmount: r.lineRole === 'TAX' ? computed : r.computedTaxAmount,
            isGstOverridden: false,
          };
        }
        return { ...r, ratePct: rawPct };
      }),
    );
    this.markDirty();
  }

  onInvoiceLineAmountManualChange(idx: number, amountWire: string): void {
    this.gridLines.set(
      this.gridLines().map((r, i) => {
        if (i !== idx) return r;
        const overridden =
          r.lineRole === 'TAX' &&
          r.computedTaxAmount !== null &&
          r.computedTaxAmount !== amountWire;
        return {
          ...r,
          amount: amountWire,
          isGstOverridden: overridden,
        };
      }),
    );
    this.markDirty();
  }

  onLineAmountEnter(idx: number, amountWire: string): void {
    this.onLineAmountChange(idx, amountWire);
    const row = this.gridLines()[idx];
    if (!row || !row.ledgerId) {
      return;
    }

    const scale = this.companyContext.currencyScale();
    const amtDec = ExactDecimal.tryParse(amountWire, scale, 'HALF_UP');
    if (!amtDec || !amtDec.isPositive()) {
      return;
    }

    const ledger = this.coaStore.ledgers().find((l) => l.id === row.ledgerId);
    if (ledger) {
      const nextStage = this.findNextRequiredAllocationStage(ledger, null);
      if (nextStage) {
        this.activeSubModal.set({
          target: 'LINE',
          lineIndex: idx,
          stage: nextStage,
          ledger,
          amount: amtDec.toWireString(scale),
          side: row.side,
        });
        return;
      }
    }

    this.advanceAfterLineCompleted(idx);
  }

  reopenLineSubModal(idx: number, stage: PendingAllocationStage): void {
    const row = this.gridLines()[idx];
    if (!row?.ledgerId) return;
    const ledger = this.coaStore.ledgers().find((l) => l.id === row.ledgerId);
    if (!ledger) return;
    this.activeSubModal.set({
      target: 'LINE',
      lineIndex: idx,
      stage,
      ledger,
      amount: row.amount,
      side: row.side,
    });
  }

  openPartyBillWiseModal(): void {
    const party = this.selectedPartyLedger();
    if (!party) return;
    const { partySide } = invoicePartyAndRevenueSides(this.activeCategory());
    const tot = this.totals();
    const amount = partySide === 'DR' ? tot.totalDebitWire : tot.totalCreditWire;
    const amtDec = ExactDecimal.tryParse(amount, this.companyContext.currencyScale(), 'HALF_UP');
    if (!amtDec || !amtDec.isPositive()) {
      this.errorBanner.set('Enter at least one positive invoice line amount before allocating Party bills.');
      return;
    }
    this.activeSubModal.set({
      target: 'HEADER_PARTY',
      lineIndex: -1,
      stage: 'BILL',
      ledger: party,
      amount,
      side: partySide,
    });
  }

  // ---------------------------------------------------------------------------
  // Sub-Ledger Allocation Completion & Auto-Balancing Line Progression
  // ---------------------------------------------------------------------------

  onBillAllocationsCommitted(allocations: readonly BillAllocationDto[]): void {
    const sub = this.activeSubModal();
    if (!sub) return;

    if (sub.target === 'HEADER_PARTY') {
      this.partyBillAllocations.set(allocations);
      this.activeSubModal.set(null);
      this.markDirty();
      queueMicrotask(() => {
        this.narrationInputRef()?.nativeElement.focus();
      });
      return;
    }

    this.gridLines.set(
      this.gridLines().map((r, i) =>
        i === sub.lineIndex ? { ...r, billAllocations: allocations } : r,
      ),
    );
    this.markDirty();

    const nextStage = this.findNextRequiredAllocationStage(sub.ledger, 'BILL');
    if (nextStage) {
      this.activeSubModal.set({ ...sub, stage: nextStage });
      return;
    }
    this.activeSubModal.set(null);
    this.advanceAfterLineCompleted(sub.lineIndex);
  }

  onCostAllocationsCommitted(allocations: readonly CostAllocationDto[]): void {
    const sub = this.activeSubModal();
    if (!sub) return;

    this.gridLines.set(
      this.gridLines().map((r, i) =>
        i === sub.lineIndex ? { ...r, costAllocations: allocations } : r,
      ),
    );
    this.markDirty();

    const nextStage = this.findNextRequiredAllocationStage(sub.ledger, 'COST');
    if (nextStage) {
      this.activeSubModal.set({ ...sub, stage: nextStage });
      return;
    }
    this.activeSubModal.set(null);
    this.advanceAfterLineCompleted(sub.lineIndex);
  }

  onBankAllocationsCommitted(allocations: readonly BankAllocationDto[]): void {
    const sub = this.activeSubModal();
    if (!sub) return;

    if (sub.target === 'HEADER_ACCOUNT') {
      this.headerBankAllocations.set(allocations);
      this.activeSubModal.set(null);
      this.markDirty();
      queueMicrotask(() => {
        this.narrationInputRef()?.nativeElement.focus();
      });
      return;
    }

    this.gridLines.set(
      this.gridLines().map((r, i) =>
        i === sub.lineIndex ? { ...r, bankAllocations: allocations } : r,
      ),
    );
    this.markDirty();
    this.activeSubModal.set(null);
    this.advanceAfterLineCompleted(sub.lineIndex);
  }

  onSubModalCancelled(): void {
    const sub = this.activeSubModal();
    this.activeSubModal.set(null);
    if (sub && sub.lineIndex >= 0) {
      queueMicrotask(() => {
        this.lineAmountCellRefs()[sub.lineIndex]?.focusInput();
      });
    }
  }

  private findNextRequiredAllocationStage(
    ledger: Ledger,
    completedStage: PendingAllocationStage | null,
  ): PendingAllocationStage | null {
    const order: readonly PendingAllocationStage[] = ['BILL', 'COST', 'BANK'];
    const startIdx = completedStage ? order.indexOf(completedStage) + 1 : 0;

    for (let i = startIdx; i < order.length; i += 1) {
      const candidate = order[i];
      if (candidate === 'BILL' && ledger.maintainBalancesBillByBill) {
        return 'BILL';
      }
      if (
        candidate === 'COST' &&
        ledger.costCentresApplicable &&
        this.coaStore.costCentres().length > 0
      ) {
        return 'COST';
      }
      if (
        candidate === 'BANK' &&
        isBankOnlyLedger(ledger, this.coaStore.groups()) &&
        (this.activeCategory() === 'CONTRA' ||
          this.activeCategory() === 'PAYMENT' ||
          this.activeCategory() === 'RECEIPT')
      ) {
        return 'BANK';
      }
    }
    return null;
  }

  private advanceAfterLineCompleted(completedIdx: number): void {
    const scale = this.companyContext.currencyScale();
    const zeroWire = ExactDecimal.zero(scale).toWireString(scale);
    const view = this.persistedView();

    if (view === 'AS_VOUCHER') {
      const tot = this.totals();
      if (tot.isBalancedAndPositive) {
        // Double-entry voucher is balanced! Jump straight to Narration.
        queueMicrotask(() => {
          this.narrationInputRef()?.nativeElement.focus();
        });
        return;
      }

      // Compute exact residual and opposite balancing side
      const neededSide: BalanceSide =
        tot.totalDebit.compare(tot.totalCredit) > 0 ? 'CR' : 'DR';
      const residualWire = tot.totalDebit.sub(tot.totalCredit).abs().toWireString(scale);

      const lines = [...this.gridLines()];
      const nextIdx = completedIdx + 1;
      if (nextIdx < lines.length && !lines[nextIdx].ledgerId) {
        lines[nextIdx] = {
          ...lines[nextIdx],
          side: neededSide,
          amount: residualWire,
        };
      } else if (nextIdx >= lines.length) {
        lines.push(this.makeBlankLine(neededSide, residualWire));
      }
      this.gridLines.set(lines);

      queueMicrotask(() => {
        this.lineLedgerSelectRefs()[nextIdx]?.focusInput();
      });
      return;
    }

    // SINGLE_ENTRY or INVOICE mode: append next blank row so user can add another line or press Enter on blank to finish
    const lines = [...this.gridLines()];
    const nextIdx = completedIdx + 1;
    if (nextIdx >= lines.length) {
      const spec = resolveVoucherSpecByCategory(this.activeCategory());
      lines.push(this.makeBlankLine(this.defaultLineSideForView(view, spec), zeroWire));
      this.gridLines.set(lines);
    }
    queueMicrotask(() => {
      this.lineLedgerSelectRefs()[nextIdx]?.focusInput();
    });
  }

  onEmptyLineEnterJumpToNarration(emptyIdx: number): void {
    if (this.gridLines().length > 1 && !this.gridLines()[emptyIdx]?.ledgerId) {
      this.gridLines.set(this.gridLines().filter((_, i) => i !== emptyIdx));
    }

    // In SINGLE_ENTRY, if header Account is a Bank Account without bank allocations yet, prompt Bank Allocations!
    if (this.persistedView() === 'SINGLE_ENTRY') {
      const acct = this.selectedHeaderAccountLedger();
      if (
        acct &&
        isBankOnlyLedger(acct, this.coaStore.groups()) &&
        this.headerBankAllocations().length === 0 &&
        this.totals().isBalancedAndPositive
      ) {
        const headerSide: BalanceSide = this.activeCategory() === 'PAYMENT' ? 'CR' : 'DR';
        const amt =
          headerSide === 'DR' ? this.totals().totalDebitWire : this.totals().totalCreditWire;
        this.activeSubModal.set({
          target: 'HEADER_ACCOUNT',
          lineIndex: -1,
          stage: 'BANK',
          ledger: acct,
          amount: amt,
          side: headerSide,
        });
        return;
      }
    }

    queueMicrotask(() => {
      this.narrationInputRef()?.nativeElement.focus();
    });
  }

  onEmptyInvoiceLineFinish(emptyIdx: number): void {
    if (this.gridLines().length > 1 && !this.gridLines()[emptyIdx]?.ledgerId) {
      this.gridLines.set(this.gridLines().filter((_, i) => i !== emptyIdx));
    }

    const party = this.selectedPartyLedger();
    if (
      party?.maintainBalancesBillByBill &&
      this.partyBillAllocations().length === 0 &&
      this.totals().isBalancedAndPositive
    ) {
      this.openPartyBillWiseModal();
      return;
    }

    queueMicrotask(() => {
      this.narrationInputRef()?.nativeElement.focus();
    });
  }

  appendBlankLine(): void {
    const scale = this.companyContext.currencyScale();
    const zeroWire = ExactDecimal.zero(scale).toWireString(scale);
    const spec = resolveVoucherSpecByCategory(this.activeCategory());
    const view = this.persistedView();

    let side = this.defaultLineSideForView(view, spec);
    let defaultAmt = zeroWire;
    if (view === 'AS_VOUCHER') {
      const tot = this.totals();
      if (!tot.isBalanced) {
        side = tot.totalDebit.compare(tot.totalCredit) > 0 ? 'CR' : 'DR';
        defaultAmt = tot.totalDebit.sub(tot.totalCredit).abs().toWireString(scale);
      }
    }

    this.gridLines.set([...this.gridLines(), this.makeBlankLine(side, defaultAmt)]);
  }

  removeGridLine(idx: number): void {
    if (this.gridLines().length <= 1) return;
    this.gridLines.set(this.gridLines().filter((_, i) => i !== idx));
    this.markDirty();
  }

  // ---------------------------------------------------------------------------
  // Phase 4 Item-Invoice Hook Helpers
  // ---------------------------------------------------------------------------

  addItemHookRow(): void {
    const zeroWire = ExactDecimal.zero(this.companyContext.currencyScale()).toWireString(
      this.companyContext.currencyScale(),
    );
    this.itemHookRows.set([
      ...this.itemHookRows(),
      {
        rowId: `item-${this.rowCounter++}`,
        stockItemId: null,
        stockItemName: '',
        quantity: '1',
        uomCode: 'NOS',
        alternateUomId: null,
        rate: zeroWire,
        amount: zeroWire,
      },
    ]);
  }

  onItemHookNameChange(idx: number, name: string): void {
    this.itemHookRows.set(
      this.itemHookRows().map((r, i) => (i === idx ? { ...r, stockItemName: name } : r)),
    );
    this.markDirty();
  }

  onItemHookQtyOrRateChange(idx: number, qtyRaw: string, rateRaw: string): void {
    const scale = this.companyContext.currencyScale();
    const qtyDec = ExactDecimal.tryParse(qtyRaw.trim(), 4, 'HALF_UP');
    const rateDec = ExactDecimal.tryParse(rateRaw.trim(), 4, 'HALF_UP');
    const computedAmt =
      qtyDec && rateDec
        ? qtyDec.mulScaled(rateDec, scale, 'HALF_UP').toWireString(scale)
        : this.itemHookRows()[idx]?.amount ?? '0.00';

    this.itemHookRows.set(
      this.itemHookRows().map((r, i) =>
        i === idx ? { ...r, quantity: qtyRaw, rate: rateRaw, amount: computedAmt } : r,
      ),
    );
    this.markDirty();
  }

  onItemHookAmountOverride(idx: number, amountWire: string): void {
    this.itemHookRows.set(
      this.itemHookRows().map((r, i) => (i === idx ? { ...r, amount: amountWire } : r)),
    );
    this.markDirty();
  }

  // ---------------------------------------------------------------------------
  // Inline Secondary Master Creation (`Alt+C`)
  // ---------------------------------------------------------------------------

  openInlineLedgerCreate(
    target: 'LINE' | 'HEADER_ACCOUNT' | 'HEADER_PARTY',
    lineIdx: number,
    seedName: string,
  ): void {
    let defaultGrpId: string | null = null;
    const groups = this.coaStore.groups();
    if (target === 'HEADER_ACCOUNT') {
      defaultGrpId = groups.find((g) => g.name === 'Bank Accounts')?.id ?? null;
    } else if (target === 'HEADER_PARTY') {
      const targetName = this.isOutwardInvoice() ? 'Sundry Debtors' : 'Sundry Creditors';
      defaultGrpId = groups.find((g) => g.name === targetName)?.id ?? null;
    }

    void this.secondaryMasterModal.open({
      kind: 'LEDGER',
      initialName: seedName,
      defaultGroupId: defaultGrpId,
      onCreated: (created) => {
        if (target === 'HEADER_ACCOUNT') {
          if (this.persistedView() === 'ITEM_INVOICE') {
            this.onAccountingLedgerSelected({ id: created.id, label: created.name });
          } else {
            this.onSingleEntryAccountSelected({ id: created.id, label: created.name });
          }
          return;
        }
        if (target === 'HEADER_PARTY') {
          this.onPartyLedgerSelected({ id: created.id, label: created.name });
          return;
        }
        this.onLineLedgerSelected(lineIdx, { id: created.id, label: created.name });
      },
    });
  }

  // ---------------------------------------------------------------------------
  // Narration Accept Prompt & Payload Compilation / Save
  // ---------------------------------------------------------------------------

  onNarrationEnter(event: Event): void {
    event.preventDefault();
    this.errorBanner.set(null);
    this.acceptPromptOpen.set(true);
    queueMicrotask(() => {
      this.acceptYesBtnRef()?.nativeElement.focus();
    });
  }

  onAcceptPromptKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Escape' || event.key.toLowerCase() === 'n') {
      event.preventDefault();
      event.stopPropagation();
      this.dismissAcceptPrompt();
      return;
    }
    if (event.key === 'Enter' || event.key.toLowerCase() === 'y') {
      event.preventDefault();
      event.stopPropagation();
      this.confirmAcceptYes();
    }
  }

  dismissAcceptPrompt(): void {
    this.acceptPromptOpen.set(false);
    queueMicrotask(() => {
      this.narrationInputRef()?.nativeElement.focus();
    });
  }

  confirmAcceptYes(): void {
    this.acceptPromptOpen.set(false);
    void this.submitVoucher();
  }

  buildUpsertRequest(overrideAction?: VoucherSubmitAction): UpsertVoucherRequest {
    const scale = this.companyContext.currencyScale();
    const view = this.persistedView();
    const vt = this.coaStore
      .voucherTypes()
      .find((t) => t.id === this.activeVoucherTypeId());
    const defaultAction: VoucherSubmitAction =
      vt?.approvalPolicy === 'FOUR_EYES' ? 'SUBMIT_FOR_APPROVAL' : 'POST';
    const action = overrideAction ?? defaultAction;

    const compiledLines: VoucherLineRequest[] = [];

    if (view === 'AS_VOUCHER') {
      const populated = this.gridLines().filter((l) => {
        const dec = ExactDecimal.tryParse(l.amount, scale, 'HALF_UP');
        return Boolean(l.ledgerId && dec && dec.isPositive());
      });

      populated.forEach((row, idx) => {
        const led = this.coaStore.ledgers().find((l) => l.id === row.ledgerId);
        const autoBills =
          led?.maintainBalancesBillByBill && row.billAllocations.length === 0
            ? [
                {
                  allocationType: this.defaultBillMethod(),
                  billName: this.defaultBillReference(),
                  creditPeriodDays: led.defaultCreditPeriodDays,
                  side: row.side,
                  amount: ExactDecimal.parse(row.amount, scale, 'HALF_UP').toWireString(scale),
                } satisfies BillAllocationDto,
              ]
            : row.billAllocations;

        compiledLines.push({
          lineNo: idx + 1,
          lineRole: row.lineRole,
          ledgerId: row.ledgerId!,
          side: row.side,
          amount: ExactDecimal.parse(row.amount, scale, 'HALF_UP').toWireString(scale),
          lineNarration: row.lineNarration || undefined,
          billAllocations: autoBills.length > 0 ? autoBills : undefined,
          costAllocations: row.costAllocations.length > 0 ? row.costAllocations : undefined,
          bankAllocations: row.bankAllocations.length > 0 ? row.bankAllocations : undefined,
        });
      });
    } else if (view === 'SINGLE_ENTRY') {
      const headerAcctId = this.headerAccountLedgerId();
      if (!headerAcctId) {
        throw new Error('Select a Cash or Bank Account in the top Account field.');
      }
      const populated = this.gridLines().filter((l) => {
        const dec = ExactDecimal.tryParse(l.amount, scale, 'HALF_UP');
        return Boolean(l.ledgerId && dec && dec.isPositive());
      });
      if (populated.length === 0) {
        throw new Error('Enter at least one Particulars ledger line with a positive amount.');
      }

      const headerSide: BalanceSide = this.activeCategory() === 'PAYMENT' ? 'CR' : 'DR';
      const lineSide: BalanceSide = headerSide === 'DR' ? 'CR' : 'DR';
      const totalSum = ExactDecimal.sum(
        populated.map((r) => r.amount),
        scale,
      ).toWireString(scale);

      compiledLines.push({
        lineNo: 1,
        lineRole: 'ACCOUNTING',
        ledgerId: headerAcctId,
        side: headerSide,
        amount: totalSum,
        bankAllocations:
          this.headerBankAllocations().length > 0 ? this.headerBankAllocations() : undefined,
      });

      populated.forEach((row, idx) => {
        const led = this.coaStore.ledgers().find((l) => l.id === row.ledgerId);
        const autoBills =
          led?.maintainBalancesBillByBill && row.billAllocations.length === 0
            ? [
                {
                  allocationType: this.defaultBillMethod(),
                  billName: this.defaultBillReference(),
                  creditPeriodDays: led.defaultCreditPeriodDays,
                  side: lineSide,
                  amount: ExactDecimal.parse(row.amount, scale, 'HALF_UP').toWireString(scale),
                } satisfies BillAllocationDto,
              ]
            : row.billAllocations;

        compiledLines.push({
          lineNo: idx + 2,
          lineRole: 'LEDGER',
          ledgerId: row.ledgerId!,
          side: lineSide,
          amount: ExactDecimal.parse(row.amount, scale, 'HALF_UP').toWireString(scale),
          billAllocations: autoBills.length > 0 ? autoBills : undefined,
          costAllocations: row.costAllocations.length > 0 ? row.costAllocations : undefined,
          bankAllocations: row.bankAllocations.length > 0 ? row.bankAllocations : undefined,
        });
      });
    } else {
      // ACCOUNTING_INVOICE or ITEM_INVOICE
      const partyId = this.partyLedgerId();
      if (!partyId) {
        throw new Error('Select a Party A/c name for this invoice.');
      }
      const { partySide, revenueExpenseSide } = invoicePartyAndRevenueSides(this.activeCategory());
      const tot = this.totals();
      const partyTotalWire = partySide === 'DR' ? tot.totalDebitWire : tot.totalCreditWire;
      const partyLed = this.selectedPartyLedger();

      const partyBills: readonly BillAllocationDto[] =
        partyLed?.maintainBalancesBillByBill && this.partyBillAllocations().length === 0
          ? [
              {
                allocationType: 'NEW_REF',
                billName: this.defaultBillReference(),
                creditPeriodDays: partyLed.defaultCreditPeriodDays,
                side: partySide,
                amount: partyTotalWire,
              },
            ]
          : this.partyBillAllocations();

      let lineSeq = 1;
      compiledLines.push({
        lineNo: lineSeq++,
        lineRole: 'PARTY',
        ledgerId: partyId,
        side: partySide,
        amount: partyTotalWire,
        billAllocations: partyBills.length > 0 ? partyBills : undefined,
      });

      if (view === 'ITEM_INVOICE') {
        let itemSubtotal = ExactDecimal.zero(scale);
        for (const item of this.itemHookRows()) {
          if (!item.stockItemName.trim()) continue;
          const amt = ExactDecimal.tryParse(item.amount, scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
          itemSubtotal = itemSubtotal.add(amt);
        }
        if (itemSubtotal.isPositive()) {
          const acctId = this.accountingLedgerId();
          if (!acctId) {
            throw new Error('Select a Sales / Purchase Ledger for the item lines.');
          }
          compiledLines.push({
            lineNo: lineSeq++,
            lineRole: 'ITEM_ACCOUNTING',
            ledgerId: acctId,
            side: revenueExpenseSide,
            amount: itemSubtotal.toWireString(scale),
          });
        }
      }

      for (const row of this.gridLines()) {
        const dec = ExactDecimal.tryParse(row.amount, scale, 'HALF_UP');
        if (!row.ledgerId || !dec || !dec.isPositive()) continue;
        compiledLines.push({
          lineNo: lineSeq++,
          lineRole: row.lineRole,
          ledgerId: row.ledgerId,
          side: row.lineRole === 'DISCOUNT' ? partySide : revenueExpenseSide,
          amount: dec.toWireString(scale),
          ratePct: row.ratePct.trim() || null,
          computedTaxAmount: row.computedTaxAmount,
          isGstOverridden: row.isGstOverridden,
          costAllocations: row.costAllocations.length > 0 ? row.costAllocations : undefined,
        });
      }
    }

    if (action !== 'SAVE_DRAFT') {
      if (compiledLines.length < 2) {
        throw new Error('A double-entry voucher requires at least two balanced ledger lines.');
      }
      const tot = this.totals();
      if (!tot.isBalancedAndPositive) {
        throw new Error(
          `Voucher is out of balance (Total Dr ${tot.totalDebitWire} != Total Cr ${tot.totalCreditWire}).`,
        );
      }
    }

    return {
      voucherTypeId: this.activeVoucherTypeId() ?? undefined,
      voucherTypeName: this.activeVoucherTypeName(),
      voucherNumber: this.voucherNumber().trim() || null,
      voucherDate: this.voucherDate(),
      effectiveDate: this.voucherDate(),
      reversingApplicableUpto:
        this.postingNature() === 'REVERSING_JOURNAL'
          ? this.reversingApplicableUpto() || null
          : null,
      persistedView: view,
      postingNature: this.postingNature(),
      action,
      supplierInvoiceNo: this.isPurchaseOrDebitNote()
        ? this.supplierInvoiceNo().trim() || null
        : null,
      supplierInvoiceDate: this.isPurchaseOrDebitNote()
        ? this.supplierInvoiceDate() || null
        : null,
      partyLedgerId:
        view === 'ACCOUNTING_INVOICE' || view === 'ITEM_INVOICE' ? this.partyLedgerId() : null,
      accountingLedgerId:
        view === 'ITEM_INVOICE'
          ? this.accountingLedgerId()
          : view === 'SINGLE_ENTRY'
            ? this.headerAccountLedgerId()
            : null,
      placeOfSupplyState:
        view === 'ACCOUNTING_INVOICE' || view === 'ITEM_INVOICE'
          ? this.placeOfSupplyState() || null
          : null,
      partyGstin: this.partyGstin(),
      partyMailingName: this.partyMailingName(),
      partyStateCode: this.partyStateCode(),
      isReverseCharge: this.isReverseCharge(),
      currencyCode: this.companyContext.activeCompany()?.baseCurrencyCode ?? 'INR',
      exchangeRate: '1',
      narration: this.narration().trim(),
      lines: compiledLines,
    };
  }

  async submitVoucher(overrideAction?: VoucherSubmitAction): Promise<void> {
    if (this.saving()) return;
    this.errorBanner.set(null);
    this.savedBanner.set(null);

    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) {
      this.errorBanner.set('Select an active company (F3) before saving vouchers.');
      return;
    }

    let request: UpsertVoucherRequest;
    try {
      request = this.buildUpsertRequest(overrideAction);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Invalid voucher lines.');
      return;
    }

    this.saving.set(true);
    try {
      const existingId = this.editingVoucherId();
      if (existingId) {
        const updated = await this.voucherService.updateVoucher(
          cid,
          existingId,
          request,
          this.editingVersionNo(),
        );
        await this.hydrateFromVoucherDetail(updated, false);
        this.savedBanner.set(
          `Updated ${updated.voucherTypeName} #${updated.voucherNumber} (v${updated.versionNo}).`,
        );
      } else {
        const created = await this.voucherService.createVoucher(cid, request);
        const currentCategory = this.activeCategory();
        const currentView = this.persistedView();
        this.initializeBlankVoucher(currentCategory);
        this.persistedView.set(currentView);
        this.savedBanner.set(
          `Saved ${created.voucherTypeName} #${created.voucherNumber} (${created.status}).`,
        );
      }
      this.companyContext.setHasUnsavedDraft(false);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Failed to save voucher.');
    } finally {
      this.saving.set(false);
    }
  }

  async cancelLoadedVoucher(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    const vid = this.editingVoucherId();
    if (!cid || !vid) return;

    this.saving.set(true);
    this.errorBanner.set(null);
    try {
      const cancelled = await this.voucherService.cancelVoucher(
        cid,
        vid,
        'Cancelled via Alt+X',
        this.editingVersionNo(),
      );
      await this.hydrateFromVoucherDetail(cancelled, false);
      this.savedBanner.set(
        `Cancelled ${cancelled.voucherTypeName} #${cancelled.voucherNumber}.`,
      );
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Failed to cancel voucher.');
    } finally {
      this.saving.set(false);
    }
  }

  // ---------------------------------------------------------------------------
  // Alteration & Duplicate Hydration + Draft Snapshot Hooks
  // ---------------------------------------------------------------------------

  private async loadExistingVoucherForAlteration(voucherId: string): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    try {
      const detail = await this.voucherService.getVoucher(cid, voucherId);
      await this.hydrateFromVoucherDetail(detail, false);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to load voucher.');
    }
  }

  async hydrateFromVoucherDetail(
    detail: VoucherDetailDto,
    asDuplicateNew: boolean,
  ): Promise<void> {
    const vt =
      this.coaStore.voucherTypes().find((t) => t.id === detail.voucherTypeId) ??
      this.coaStore.voucherTypes().find((t) => t.name === detail.voucherTypeName);
    const category: VoucherBaseCategory =
      detail.voucherCategory ??
      vt?.baseCategory ??
      (PREDEFINED_24_VOUCHER_SPECS.find(
        (s) => s.name.toLowerCase() === detail.voucherTypeName.toLowerCase(),
      )?.category ?? 'JOURNAL');

    this.editingVoucherId.set(asDuplicateNew ? null : detail.id);
    this.editingVersionNo.set(asDuplicateNew ? 1 : detail.versionNo);
    this.editingStatus.set(asDuplicateNew ? 'DRAFT' : detail.status);

    this.activeCategory.set(category);
    this.activeVoucherTypeId.set(detail.voucherTypeId || vt?.id || null);
    this.activeVoucherTypeName.set(detail.voucherTypeName || vt?.name || 'Voucher');
    this.voucherNumber.set(asDuplicateNew ? '' : detail.voucherNumber);
    this.voucherDate.set(detail.voucherDate);
    this.reversingApplicableUpto.set(detail.reversingApplicableUpto ?? '');
    this.persistedView.set(detail.persistedView);
    this.postingNature.set(detail.postingNature);
    this.supplierInvoiceNo.set(asDuplicateNew ? '' : detail.supplierInvoiceNo ?? '');
    this.supplierInvoiceDate.set(detail.supplierInvoiceDate ?? detail.voucherDate);
    this.partyLedgerId.set(detail.partyLedgerId ?? null);
    this.accountingLedgerId.set(detail.accountingLedgerId ?? null);
    this.placeOfSupplyState.set(
      detail.placeOfSupplyState ?? this.companyContext.activeCompany()?.stateCode ?? '33',
    );
    this.partyGstin.set(detail.partyGstin ?? null);
    this.partyMailingName.set(detail.partyMailingName ?? null);
    this.partyStateCode.set(detail.partyStateCode ?? null);
    this.isReverseCharge.set(Boolean(detail.isReverseCharge));
    this.narration.set(detail.narration ?? '');

    if (detail.persistedView === 'SINGLE_ENTRY' && detail.lines.length >= 2) {
      const headerLine =
        detail.lines.find((l) => l.lineRole === 'ACCOUNTING') ?? detail.lines[0];
      this.headerAccountLedgerId.set(headerLine.ledgerId);
      this.headerBankAllocations.set(headerLine.bankAllocations ?? []);
      const remaining = detail.lines.filter((l) => l !== headerLine);
      this.gridLines.set(remaining.map((l) => this.mapResponseLineToEditable(l)));
    } else if (
      detail.persistedView === 'ACCOUNTING_INVOICE' ||
      detail.persistedView === 'ITEM_INVOICE'
    ) {
      const pLine = detail.lines.find((l) => l.lineRole === 'PARTY');
      if (pLine) {
        this.partyLedgerId.set(pLine.ledgerId);
        this.partyBillAllocations.set(asDuplicateNew ? [] : pLine.billAllocations ?? []);
      }
      const bodyLines = detail.lines.filter((l) => l.lineRole !== 'PARTY');
      this.gridLines.set(
        bodyLines.length > 0
          ? bodyLines.map((l) => this.mapResponseLineToEditable(l))
          : [this.makeBlankLine('CR', '0.00')],
      );
    } else {
      this.gridLines.set(
        detail.lines.length > 0
          ? detail.lines.map((l) => this.mapResponseLineToEditable(l))
          : [this.makeBlankLine('DR', '0.00')],
      );
    }

    this.companyContext.setHasUnsavedDraft(asDuplicateNew);
    if (asDuplicateNew) {
      await this.refreshNextVoucherNumber();
    }
  }

  private mapResponseLineToEditable(line: VoucherDetailDto['lines'][number]): EditableVoucherLine {
    const led = this.coaStore.ledgers().find((l) => l.id === line.ledgerId);
    return {
      rowId: `vline-${this.rowCounter++}`,
      side: line.side,
      lineRole: line.lineRole,
      ledgerId: line.ledgerId,
      ledgerName: line.ledgerName ?? led?.name ?? '',
      ratePct: line.ratePct ?? '',
      computedTaxAmount: line.computedTaxAmount ?? null,
      isGstOverridden: Boolean(line.isGstOverridden),
      amount: line.amount,
      lineNarration: line.lineNarration ?? '',
      billAllocations: line.billAllocations ?? [],
      costAllocations: line.costAllocations ?? [],
      bankAllocations: line.bankAllocations ?? [],
    };
  }

  formatViewLabel(view: VoucherPersistedView): string {
    switch (view) {
      case 'SINGLE_ENTRY':
        return 'Single Entry';
      case 'AS_VOUCHER':
        return 'Double Entry (As Voucher)';
      case 'ACCOUNTING_INVOICE':
        return 'Accounting Invoice';
      case 'ITEM_INVOICE':
        return 'Item Invoice';
    }
  }

  private computeInvoiceTaxableBase(beforeLineIdx: number): ExactDecimal {
    const scale = this.companyContext.currencyScale();
    let base = ExactDecimal.zero(scale);
    if (this.persistedView() === 'ITEM_INVOICE') {
      for (const item of this.itemHookRows()) {
        if (!item.stockItemName.trim()) continue;
        const amt = ExactDecimal.tryParse(item.amount, scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
        base = base.add(amt);
      }
    }
    this.gridLines().forEach((r, idx) => {
      if (idx >= beforeLineIdx || !r.ledgerId || r.lineRole === 'TAX' || r.lineRole === 'ROUND_OFF') {
        return;
      }
      const amt = ExactDecimal.tryParse(r.amount, scale, 'HALF_UP') ?? ExactDecimal.zero(scale);
      base = r.lineRole === 'DISCOUNT' ? base.sub(amt) : base.add(amt);
    });
    return base;
  }

  private defaultLineSideForCurrentView(spec: PredefinedVoucherSpec): BalanceSide {
    return this.defaultLineSideForView(this.persistedView(), spec);
  }

  private defaultLineSideForView(
    view: VoucherPersistedView,
    spec: PredefinedVoucherSpec,
  ): BalanceSide {
    if (view === 'SINGLE_ENTRY') {
      return spec.category === 'PAYMENT' ? 'DR' : 'CR';
    }
    if (view === 'ACCOUNTING_INVOICE' || view === 'ITEM_INVOICE') {
      return invoicePartyAndRevenueSides(spec.category).revenueExpenseSide;
    }
    return spec.defaultFirstSide;
  }

  private makeBlankLine(side: BalanceSide, amountWire: string): EditableVoucherLine {
    return {
      rowId: `vline-${this.rowCounter++}`,
      side,
      lineRole:
        this.persistedView() === 'ACCOUNTING_INVOICE' || this.persistedView() === 'ITEM_INVOICE'
          ? 'ACCOUNTING'
          : 'LEDGER',
      ledgerId: null,
      ledgerName: '',
      ratePct: '',
      computedTaxAmount: null,
      isGstOverridden: false,
      amount: amountWire,
      lineNarration: '',
      billAllocations: [],
      costAllocations: [],
      bankAllocations: [],
    };
  }

  private focusPrimaryVoucherEntryField(): void {
    queueMicrotask(() => {
      const view = this.persistedView();
      if (view === 'SINGLE_ENTRY') {
        this.singleAccountSelectRef()?.focusInput();
      } else if (view === 'ACCOUNTING_INVOICE' || view === 'ITEM_INVOICE') {
        this.partyAccountSelectRef()?.focusInput();
      } else {
        this.lineLedgerSelectRefs()[0]?.focusInput();
      }
    });
  }

  private captureDraftSnapshot(): unknown {
    return {
      activeCategory: this.activeCategory(),
      activeVoucherTypeId: this.activeVoucherTypeId(),
      activeVoucherTypeName: this.activeVoucherTypeName(),
      voucherNumber: this.voucherNumber(),
      voucherDate: this.voucherDate(),
      persistedView: this.persistedView(),
      postingNature: this.postingNature(),
      supplierInvoiceNo: this.supplierInvoiceNo(),
      supplierInvoiceDate: this.supplierInvoiceDate(),
      headerAccountLedgerId: this.headerAccountLedgerId(),
      headerBankAllocations: this.headerBankAllocations(),
      partyLedgerId: this.partyLedgerId(),
      partyBillAllocations: this.partyBillAllocations(),
      accountingLedgerId: this.accountingLedgerId(),
      placeOfSupplyState: this.placeOfSupplyState(),
      narration: this.narration(),
      gridLines: this.gridLines(),
      itemHookRows: this.itemHookRows(),
    };
  }

  private restoreDraftSnapshot(snap: unknown): void {
    if (!snap || typeof snap !== 'object') return;
    const s = snap as Record<string, unknown>;
    if (typeof s['activeCategory'] === 'string') {
      this.activeCategory.set(s['activeCategory'] as VoucherBaseCategory);
    }
    if (typeof s['activeVoucherTypeId'] === 'string' || s['activeVoucherTypeId'] === null) {
      this.activeVoucherTypeId.set(s['activeVoucherTypeId'] as string | null);
    }
    if (typeof s['activeVoucherTypeName'] === 'string') {
      this.activeVoucherTypeName.set(s['activeVoucherTypeName']);
    }
    if (typeof s['voucherNumber'] === 'string') this.voucherNumber.set(s['voucherNumber']);
    if (typeof s['voucherDate'] === 'string') this.voucherDate.set(s['voucherDate']);
    if (typeof s['persistedView'] === 'string') {
      this.persistedView.set(s['persistedView'] as VoucherPersistedView);
    }
    if (typeof s['postingNature'] === 'string') {
      this.postingNature.set(s['postingNature'] as VoucherPostingNature);
    }
    if (typeof s['supplierInvoiceNo'] === 'string') {
      this.supplierInvoiceNo.set(s['supplierInvoiceNo']);
    }
    if (typeof s['supplierInvoiceDate'] === 'string') {
      this.supplierInvoiceDate.set(s['supplierInvoiceDate']);
    }
    if (typeof s['headerAccountLedgerId'] === 'string' || s['headerAccountLedgerId'] === null) {
      this.headerAccountLedgerId.set(s['headerAccountLedgerId'] as string | null);
    }
    if (Array.isArray(s['headerBankAllocations'])) {
      this.headerBankAllocations.set(s['headerBankAllocations'] as readonly BankAllocationDto[]);
    }
    if (typeof s['partyLedgerId'] === 'string' || s['partyLedgerId'] === null) {
      this.partyLedgerId.set(s['partyLedgerId'] as string | null);
    }
    if (Array.isArray(s['partyBillAllocations'])) {
      this.partyBillAllocations.set(s['partyBillAllocations'] as readonly BillAllocationDto[]);
    }
    if (typeof s['accountingLedgerId'] === 'string' || s['accountingLedgerId'] === null) {
      this.accountingLedgerId.set(s['accountingLedgerId'] as string | null);
    }
    if (typeof s['placeOfSupplyState'] === 'string') {
      this.placeOfSupplyState.set(s['placeOfSupplyState']);
    }
    if (typeof s['narration'] === 'string') this.narration.set(s['narration']);
    if (Array.isArray(s['gridLines'])) {
      this.gridLines.set(s['gridLines'] as readonly EditableVoucherLine[]);
    }
    if (Array.isArray(s['itemHookRows'])) {
      this.itemHookRows.set(s['itemHookRows'] as readonly VoucherInventoryItemHookDto[]);
    }
  }
}
