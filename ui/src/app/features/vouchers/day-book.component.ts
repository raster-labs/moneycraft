import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  OnInit,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { isEditableTextElement } from '../../core/keyboard/collision-guard';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { ExactDecimal } from '../../core/money/exact-decimal';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { ChartOfAccountsStore } from '../masters/chart-of-accounts.store';
import { VoucherBaseCategory } from '../masters/masters.models';
import { EditLogModalService } from '../statutory/edit-log-modal.service';
import { VoucherService } from './voucher.service';
import { PREDEFINED_24_VOUCHER_SPECS, VoucherSummaryDto } from './vouchers.models';

interface VoucherTypeFilterChoice {
  readonly id: string | null;
  readonly name: string;
  readonly category: VoucherBaseCategory | null;
  readonly abbreviation: string;
}

function compareDayBookCanonical(a: VoucherSummaryDto, b: VoucherSummaryDto): number {
  const byDate = a.voucherDate.localeCompare(b.voucherDate);
  if (byDate !== 0) {
    return byDate;
  }
  const byType = a.voucherTypeName.localeCompare(b.voucherTypeName, undefined, {
    sensitivity: 'base',
  });
  if (byType !== 0) {
    return byType;
  }
  return a.voucherNumber.localeCompare(b.voucherNumber, undefined, {
    numeric: true,
    sensitivity: 'base',
  });
}

@Component({
  selector: 'mc-day-book',
  standalone: true,
  imports: [FormsModule, RouterLink, CodeBadgeComponent, IndianCurrencyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-daybook-shell" [class.mc-daybook-embedded]="embeddedOverlay()">
      <!-- Header & Action Toolbar -->
      <header class="mc-daybook-header mc-card">
        <div class="mc-daybook-title-group">
          <div class="mc-daybook-heading-row">
            <h1 class="mc-daybook-title">Day Book</h1>
            <mc-code-badge [code]="periodBadgeText()" />
            @if (activeTypeLabel(); as typeLabel) {
              <span class="mc-badge mc-badge-jade">{{ typeLabel }}</span>
            }
            @if (detailedMode()) {
              <span class="mc-badge mc-badge-slate">Detailed</span>
            }
          </div>
          <p class="mc-daybook-subtitle">
            TallyPrime 7.2 canonical register sorted by Date &rarr; Voucher Type &rarr; Voucher No.
          </p>
        </div>

        <div class="mc-daybook-toolbar" role="toolbar" aria-label="Day Book Actions">
          <button
            type="button"
            class="mc-btn"
            data-testid="daybook-period-btn"
            (click)="openPeriodModal()"
          >
            Period (F2 / <span class="mc-key-alt">F2</span>)
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="daybook-vchtype-btn"
            (click)="openTypeFilterModal()"
          >
            Chg Vch (F4)
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="daybook-basis-btn"
            (click)="openBasisModal()"
          >
            Basis of Values (<span class="mc-key-ctrl">B</span>)
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="daybook-detailed-btn"
            (click)="toggleDetailedMode()"
          >
            {{ detailedMode() ? 'Condensed' : 'Detailed' }} (<span class="mc-key-alt">F1</span>)
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="daybook-add-btn"
            (click)="addOrInsertVoucher()"
          >
            Add Vch (<span class="mc-key-alt">A</span>)
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="daybook-duplicate-btn"
            [disabled]="!selectedVoucher() || busyAction()"
            (click)="duplicateSelectedVoucher()"
          >
            Duplicate (<span class="mc-key-alt">2</span>)
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="daybook-cancel-btn"
            [disabled]="
              !selectedVoucher() ||
              selectedVoucher()?.status === 'CANCELLED' ||
              companyContext.quarantined() ||
              busyAction()
            "
            (click)="openCancelConfirmation()"
          >
            Cancel Vch (<span class="mc-key-alt">X</span>)
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="daybook-edit-log-btn"
            [disabled]="!selectedVoucher()"
            (click)="openSelectedEditLog()"
          >
            Edit Log (<span class="mc-key-alt">Q</span>)
          </button>
          <a routerLink="/app/approvals" class="mc-btn">Approval Queue</a>
        </div>
      </header>

      @if (errorBanner(); as err) {
        <div class="mc-daybook-alert mc-daybook-alert-error" role="alert">{{ err }}</div>
      }
      @if (statusBanner(); as msg) {
        <div class="mc-daybook-alert mc-daybook-alert-ok" role="status">{{ msg }}</div>
      }

      <!-- Main Day Book Register Grid -->
      <section class="mc-card mc-daybook-table-card" aria-label="Day Book Voucher Register">
        @if (loading()) {
          <div class="mc-daybook-loading">Loading Day Book entries...</div>
        } @else {
          <div class="mc-daybook-table-wrap">
            <table class="mc-daybook-table" role="grid" aria-label="Day Book Vouchers">
              <thead>
                <tr>
                  <th scope="col" class="mc-col-date">Date</th>
                  <th scope="col" class="mc-col-particulars">Particulars</th>
                  <th scope="col" class="mc-col-vchtype">Vch Type</th>
                  <th scope="col" class="mc-col-vchno">Vch No.</th>
                  <th scope="col" class="mc-col-amount">Debit Amount</th>
                  <th scope="col" class="mc-col-amount">Credit Amount</th>
                </tr>
              </thead>
              <tbody>
                @for (vch of visibleVouchers(); track vch.id; let idx = $index) {
                  <tr
                    class="mc-daybook-row"
                    [class.mc-daybook-row-selected]="idx === selectedIndex()"
                    [class.mc-daybook-row-cancelled]="vch.status === 'CANCELLED'"
                    [attr.aria-selected]="idx === selectedIndex()"
                    [attr.data-testid]="'daybook-row-' + idx"
                    (click)="selectRow(idx)"
                    (dblclick)="drillIntoVoucher(vch)"
                  >
                    <td class="mc-code mc-col-date">{{ vch.voucherDate }}</td>
                    <td class="mc-col-particulars">
                      <div class="mc-particulars-primary">
                        @if (vch.status === 'CANCELLED') {
                          <span class="mc-cancelled-label">(Cancelled)</span>
                        } @else {
                          <span class="mc-particulars-name">{{ vch.particulars }}</span>
                        }

                        @if (vch.postingNature === 'OPTIONAL') {
                          <span class="mc-badge mc-badge-optional" data-testid="daybook-optional-badge">
                            Optional
                          </span>
                        } @else if (vch.postingNature === 'MEMORANDUM') {
                          <span class="mc-badge mc-badge-slate">Memorandum</span>
                        }

                        @if (vch.status === 'CANCELLED') {
                          <span class="mc-badge mc-badge-danger" data-testid="daybook-cancelled-badge">
                            Cancelled
                          </span>
                        } @else if (vch.status === 'DRAFT') {
                          <span class="mc-badge mc-badge-slate">Draft</span>
                        } @else if (vch.status === 'PENDING_APPROVAL') {
                          <span class="mc-badge mc-badge-pending">Pending Approval</span>
                        } @else if (vch.status === 'REVERSED') {
                          <span class="mc-badge mc-badge-slate">Reversed</span>
                        }
                      </div>

                      @if (detailedMode() && vch.narration) {
                        <div class="mc-particulars-narration">Narration: {{ vch.narration }}</div>
                      }
                    </td>
                    <td class="mc-col-vchtype">{{ vch.voucherTypeName }}</td>
                    <td class="mc-code mc-col-vchno">{{ vch.voucherNumber }}</td>
                    <td class="mc-amount mc-col-amount" data-testid="daybook-cell-debit">
                      @if (vch.status !== 'CANCELLED' && isPositiveWire(vch.debitAmount)) {
                        {{ vch.debitAmount | mcIndianCurrency: companyContext.currencyScale() : true }}
                      }
                    </td>
                    <td class="mc-amount mc-col-amount" data-testid="daybook-cell-credit">
                      @if (vch.status !== 'CANCELLED' && isPositiveWire(vch.creditAmount)) {
                        {{ vch.creditAmount | mcIndianCurrency: companyContext.currencyScale() : true }}
                      }
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="6" class="mc-daybook-empty">
                      No vouchers recorded for {{ fromDate() }} to {{ toDate() }}. Press
                      <strong>Alt+A</strong> to add a voucher or <strong>F2</strong> to change period.
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }

        <!-- Sticky ExactDecimal Totals Footer -->
        <footer class="mc-daybook-footer" aria-label="Day Book Totals">
          <div class="mc-daybook-footer-meta">
            <span>
              Showing <strong>{{ visibleVouchers().length }}</strong> voucher(s)
            </span>
            @if (removedIdsStack().length > 0) {
              <button type="button" class="mc-btn" (click)="restoreLastRemovedLine()">
                Restore Hidden ({{ removedIdsStack().length }}) &bull;
                <span class="mc-key-alt">U</span>
              </button>
            }
            @if (hasMore()) {
              <button
                type="button"
                class="mc-btn"
                [disabled]="loadingMore()"
                (click)="loadNextPage()"
              >
                {{ loadingMore() ? 'Loading...' : 'Load More' }}
              </button>
            }
          </div>

          <div class="mc-daybook-footer-totals">
            <div class="mc-daybook-total-cell">
              <span class="mc-daybook-total-label">Total Debit</span>
              <span class="mc-amount mc-daybook-total-val" data-testid="daybook-total-debit">
                {{ footerTotals().totalDebitWire | mcIndianCurrency: companyContext.currencyScale() : true }}
              </span>
            </div>
            <div class="mc-daybook-total-cell">
              <span class="mc-daybook-total-label">Total Credit</span>
              <span class="mc-amount mc-daybook-total-val" data-testid="daybook-total-credit">
                {{ footerTotals().totalCreditWire | mcIndianCurrency: companyContext.currencyScale() : true }}
              </span>
            </div>
          </div>
        </footer>
      </section>

      <!-- =================================================================== -->
      <!-- Period Filter Modal (F2 / Alt+F2)                                   -->
      <!-- =================================================================== -->
      @if (periodModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Change Day Book Period"
        >
          <form
            class="mc-modal-dialog mc-daybook-modal"
            (ngSubmit)="applyPeriodFilter()"
            (keydown.escape)="closePeriodModal($event)"
          >
            <div class="mc-modal-header">
              <span>Day Book Period (F2 / <span class="mc-key-alt">F2</span>)</span>
              <button type="button" class="mc-btn" (click)="periodModalOpen.set(false)">Esc</button>
            </div>
            <div class="mc-modal-body mc-daybook-form-stack">
              <div>
                <label class="mc-field-label" for="mc-daybook-from">From Date</label>
                <input
                  #periodFromInput
                  id="mc-daybook-from"
                  type="date"
                  name="draftFromDate"
                  class="mc-input mc-code"
                  [(ngModel)]="draftFromDate"
                  required
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-daybook-to">To Date</label>
                <input
                  id="mc-daybook-to"
                  type="date"
                  name="draftToDate"
                  class="mc-input mc-code"
                  [(ngModel)]="draftToDate"
                  required
                />
              </div>
            </div>
            <div class="mc-modal-footer">
              <button type="button" class="mc-btn" (click)="periodModalOpen.set(false)">
                Cancel (Esc)
              </button>
              <button type="submit" class="mc-btn mc-btn-primary">Apply (Enter)</button>
            </div>
          </form>
        </div>
      }

      <!-- =================================================================== -->
      <!-- Voucher Type Filter Modal (F4: Chg Vch)                             -->
      <!-- =================================================================== -->
      @if (typeFilterModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Filter by Voucher Type (F4)"
        >
          <div class="mc-modal-dialog mc-daybook-modal" (keydown)="onTypeFilterKeyDown($event)">
            <div class="mc-modal-header">
              <span>Voucher Type Filter (F4)</span>
              <button type="button" class="mc-btn" (click)="typeFilterModalOpen.set(false)">
                Esc
              </button>
            </div>
            <div class="mc-daybook-filter-search">
              <input
                #typeSearchInput
                type="text"
                class="mc-input"
                [value]="typeFilterQuery()"
                (input)="onTypeFilterQueryInput($event)"
                placeholder="Type voucher type name (or select All Vouchers)..."
                aria-label="Search voucher types"
              />
            </div>
            <ul class="mc-daybook-choice-list" role="listbox">
              @for (choice of filteredTypeChoices(); track choice.name; let idx = $index) {
                <li
                  role="option"
                  class="mc-daybook-choice-item"
                  [class.mc-daybook-choice-active]="idx === typeFilterHighlightIdx()"
                  [attr.aria-selected]="idx === typeFilterHighlightIdx()"
                  (mouseenter)="typeFilterHighlightIdx.set(idx)"
                  (click)="applyTypeFilterChoice(choice)"
                >
                  <span>{{ choice.name }}</span>
                  <span class="mc-code">{{ choice.abbreviation }}</span>
                </li>
              }
            </ul>
          </div>
        </div>
      }

      <!-- =================================================================== -->
      <!-- Basis of Values Modal (Ctrl+B)                                      -->
      <!-- =================================================================== -->
      @if (basisModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Basis of Values (Ctrl+B)"
        >
          <form
            class="mc-modal-dialog mc-daybook-modal"
            (ngSubmit)="applyBasisModal()"
            (keydown.escape)="closeBasisModal($event)"
          >
            <div class="mc-modal-header">
              <span>Basis of Values (<span class="mc-key-ctrl">B</span>)</span>
              <button type="button" class="mc-btn" (click)="basisModalOpen.set(false)">Esc</button>
            </div>
            <div class="mc-modal-body mc-daybook-form-stack">
              <label class="mc-basis-toggle-row">
                <span>Include Optional Vouchers</span>
                <input
                  #basisOptionalCheckbox
                  type="checkbox"
                  name="draftIncludeOptional"
                  [(ngModel)]="draftIncludeOptional"
                />
              </label>
              <label class="mc-basis-toggle-row">
                <span>Include Cancelled Vouchers</span>
                <input
                  type="checkbox"
                  name="draftIncludeCancelled"
                  [(ngModel)]="draftIncludeCancelled"
                />
              </label>
            </div>
            <div class="mc-modal-footer">
              <button type="button" class="mc-btn" (click)="basisModalOpen.set(false)">
                Cancel (Esc)
              </button>
              <button type="submit" class="mc-btn mc-btn-primary" data-testid="basis-apply-btn">
                Accept (Enter)
              </button>
            </div>
          </form>
        </div>
      }

      <!-- =================================================================== -->
      <!-- Cancel Voucher Confirmation Dialog (Alt+X)                          -->
      <!-- =================================================================== -->
      @if (cancelModalOpen() && selectedVoucher(); as targetVch) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Cancel Voucher Confirmation"
        >
          <form
            class="mc-modal-dialog mc-daybook-modal"
            data-testid="daybook-cancel-dialog"
            (ngSubmit)="confirmCancelSelectedVoucher()"
            (keydown)="onCancelModalKeyDown($event)"
          >
            <div class="mc-modal-header">
              <span>Cancel Voucher (<span class="mc-key-alt">X</span>)</span>
              <button type="button" class="mc-btn" (click)="cancelModalOpen.set(false)">Esc</button>
            </div>
            <div class="mc-modal-body mc-daybook-form-stack">
              <p class="mc-cancel-prompt-text">
                Cancel <strong>{{ targetVch.voucherTypeName }}</strong> voucher
                <mc-code-badge [code]="targetVch.voucherNumber" /> dated
                <span class="mc-code">{{ targetVch.voucherDate }}</span>?
              </p>
              <p class="mc-cancel-hint">
                Voucher number will remain reserved as <strong>(Cancelled)</strong> for audit sequence
                continuity, and accounting / bill-wise impacts will be cleared with an MCA Edit Log entry.
              </p>
              <div>
                <label class="mc-field-label" for="mc-cancel-reason">
                  Cancellation Reason (Optional)
                </label>
                <input
                  id="mc-cancel-reason"
                  type="text"
                  name="cancelReasonInput"
                  class="mc-input"
                  [(ngModel)]="cancelReasonInput"
                  placeholder="e.g. Customer order cancelled prior to dispatch"
                />
              </div>
            </div>
            <div class="mc-modal-footer">
              <button
                type="button"
                class="mc-btn"
                data-testid="daybook-cancel-no-btn"
                (click)="cancelModalOpen.set(false)"
              >
                No (Esc / N)
              </button>
              <button
                #cancelYesBtn
                type="submit"
                class="mc-btn mc-btn-primary"
                data-testid="daybook-cancel-yes-btn"
                [disabled]="busyAction()"
              >
                {{ busyAction() ? 'Cancelling...' : 'Yes, Cancel Voucher (Enter / Y)' }}
              </button>
            </div>
          </form>
        </div>
      }
    </div>
  `,
  styles: `
    .mc-daybook-shell {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-daybook-header {
      padding: var(--mc-space-3) var(--mc-space-4);
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-3);
    }

    .mc-daybook-heading-row {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      flex-wrap: wrap;
    }

    .mc-daybook-title {
      margin: 0;
      font-size: var(--mc-font-size-lg);
      font-weight: 700;
      color: var(--mc-color-slate-900);
    }

    .mc-daybook-subtitle {
      margin: 2px 0 0;
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-daybook-toolbar {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-2);
    }

    .mc-daybook-alert {
      padding: var(--mc-space-2) var(--mc-space-3);
      border-radius: var(--mc-radius-xs);
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
    }

    .mc-daybook-alert-error {
      background: #fef2f2;
      color: #991b1b;
      border: 1px solid #fecaca;
    }

    .mc-daybook-alert-ok {
      background: var(--mc-color-teal-50);
      color: var(--mc-color-pressed-teal);
      border: 1px solid var(--mc-color-teal-100);
    }

    .mc-daybook-table-card {
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }

    .mc-daybook-loading {
      padding: var(--mc-space-5);
      text-align: center;
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-daybook-table-wrap {
      overflow-x: auto;
      max-height: 62vh;
      overflow-y: auto;
    }

    .mc-daybook-table {
      width: 100%;
      border-collapse: collapse;
      font-size: var(--mc-font-size-sm);
    }

    .mc-daybook-table thead th {
      position: sticky;
      top: 0;
      z-index: 2;
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      padding: 0.45rem 0.75rem;
      text-align: left;
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      letter-spacing: 0.02em;
      text-transform: uppercase;
    }

    .mc-daybook-table thead th.mc-col-amount {
      text-align: right;
    }

    .mc-daybook-row {
      border-bottom: 1px solid var(--mc-color-slate-100);
      cursor: pointer;
      font-weight: 500;
    }

    .mc-daybook-row td {
      padding: 0.42rem 0.75rem;
      vertical-align: top;
    }

    .mc-daybook-row:hover {
      background: var(--mc-color-teal-50);
    }

    .mc-daybook-row-selected {
      background: var(--mc-color-verdigris-teal) !important;
      color: var(--mc-color-surface);
    }

    .mc-daybook-row-selected .mc-particulars-narration,
    .mc-daybook-row-selected .mc-cancelled-label {
      color: rgba(255, 255, 255, 0.88);
    }

    .mc-daybook-row-cancelled {
      opacity: 0.78;
    }

    .mc-col-date {
      width: 118px;
      white-space: nowrap;
    }

    .mc-col-vchtype {
      width: 150px;
      white-space: nowrap;
      font-weight: 600;
    }

    .mc-col-vchno {
      width: 130px;
      white-space: nowrap;
    }

    .mc-col-amount {
      width: 165px;
      white-space: nowrap;
    }

    .mc-particulars-primary {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      flex-wrap: wrap;
    }

    .mc-particulars-name {
      font-weight: 600;
    }

    .mc-cancelled-label {
      font-style: italic;
      font-weight: 600;
      color: #b91c1c;
    }

    .mc-particulars-narration {
      margin-top: 2px;
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 400;
    }

    .mc-badge-optional {
      background: #fef3c7;
      color: #92400e;
      border: 1px solid #fde68a;
    }

    .mc-badge-danger {
      background: #fee2e2;
      color: #991b1b;
      border: 1px solid #fecaca;
    }

    .mc-badge-pending {
      background: #e0f2fe;
      color: #075985;
      border: 1px solid #bae6fd;
    }

    .mc-daybook-empty {
      padding: var(--mc-space-5);
      text-align: center;
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-daybook-footer {
      background: var(--mc-color-canvas);
      border-top: 2px solid var(--mc-color-slate-200);
      padding: 0.55rem 0.875rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-3);
    }

    .mc-daybook-footer-meta {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-700);
      font-weight: 500;
    }

    .mc-daybook-footer-totals {
      display: flex;
      align-items: center;
      gap: var(--mc-space-5);
    }

    .mc-daybook-total-cell {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      gap: 2px;
    }

    .mc-daybook-total-label {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 700;
      text-transform: uppercase;
    }

    .mc-daybook-total-val {
      font-size: var(--mc-font-size-md);
      font-weight: 700;
      color: var(--mc-color-slate-900);
    }

    .mc-daybook-modal {
      width: min(460px, 94vw);
    }

    .mc-daybook-form-stack {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-daybook-filter-search {
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border-bottom: var(--mc-border-subtle);
    }

    .mc-daybook-choice-list {
      list-style: none;
      margin: 0;
      padding: 0;
      max-height: 280px;
      overflow-y: auto;
    }

    .mc-daybook-choice-item {
      padding: 0.45rem 0.875rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: 1px solid var(--mc-color-slate-100);
      cursor: pointer;
      font-weight: 500;
    }

    .mc-daybook-choice-active {
      background: var(--mc-color-verdigris-teal);
      color: var(--mc-color-surface);
    }

    .mc-basis-toggle-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 0.45rem 0.65rem;
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      font-weight: 600;
      cursor: pointer;
    }

    .mc-cancel-prompt-text {
      margin: 0;
      font-weight: 600;
      color: var(--mc-color-slate-900);
    }

    .mc-cancel-hint {
      margin: 0;
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }
  `,
})
export class DayBookComponent implements OnInit {
  readonly embeddedOverlay = input<boolean>(false);

  readonly companyContext = inject(CompanyContextStore);
  private readonly voucherService = inject(VoucherService);
  private readonly coaStore = inject(ChartOfAccountsStore);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly editLogModal = inject(EditLogModalService);
  private readonly navStack = inject(NavigationStackStore);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  private readonly periodFromInputRef = viewChild<ElementRef<HTMLInputElement>>('periodFromInput');
  private readonly typeSearchInputRef = viewChild<ElementRef<HTMLInputElement>>('typeSearchInput');
  private readonly basisOptionalRef =
    viewChild<ElementRef<HTMLInputElement>>('basisOptionalCheckbox');
  private readonly cancelYesBtnRef = viewChild<ElementRef<HTMLButtonElement>>('cancelYesBtn');

  readonly fromDate = signal<string>(this.companyContext.periodFrom());
  readonly toDate = signal<string>(this.companyContext.periodTo());
  readonly filterVoucherTypeId = signal<string | null>(null);
  readonly filterVoucherCategory = signal<VoucherBaseCategory | null>(null);
  readonly activeTypeLabel = signal<string | null>(null);

  readonly includeOptional = signal<boolean>(true);
  readonly includeCancelled = signal<boolean>(true);
  readonly detailedMode = signal<boolean>(false);

  readonly rawVouchers = signal<readonly VoucherSummaryDto[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly hasMore = signal<boolean>(false);
  readonly loading = signal<boolean>(false);
  readonly loadingMore = signal<boolean>(false);
  readonly busyAction = signal<boolean>(false);
  readonly errorBanner = signal<string | null>(null);
  readonly statusBanner = signal<string | null>(null);

  readonly selectedIndex = signal<number>(0);
  readonly removedIdsStack = signal<readonly string[]>([]);

  // Local modals
  readonly periodModalOpen = signal<boolean>(false);
  readonly typeFilterModalOpen = signal<boolean>(false);
  readonly basisModalOpen = signal<boolean>(false);
  readonly cancelModalOpen = signal<boolean>(false);

  draftFromDate = '';
  draftToDate = '';
  draftIncludeOptional = true;
  draftIncludeCancelled = true;
  cancelReasonInput = '';

  readonly typeFilterQuery = signal<string>('');
  readonly typeFilterHighlightIdx = signal<number>(0);

  private lastLoadedCompanyId: string | null = null;

  readonly periodBadgeText = computed(() => `${this.fromDate()} .. ${this.toDate()}`);

  readonly visibleVouchers = computed<readonly VoucherSummaryDto[]>(() => {
    const hiddenSet = new Set(this.removedIdsStack());
    const includeOpt = this.includeOptional();
    const includeCanc = this.includeCancelled();
    const catFilter = this.filterVoucherCategory();
    const vtFilter = this.filterVoucherTypeId();

    const filtered = this.rawVouchers().filter((v) => {
      if (hiddenSet.has(v.id)) return false;
      if (!includeOpt && v.postingNature === 'OPTIONAL') return false;
      if (!includeCanc && v.status === 'CANCELLED') return false;
      if (vtFilter && v.voucherTypeId && v.voucherTypeId !== vtFilter) return false;
      if (!vtFilter && catFilter && v.voucherCategory && v.voucherCategory !== catFilter) {
        return false;
      }
      return true;
    });

    return [...filtered].sort(compareDayBookCanonical);
  });

  readonly selectedVoucher = computed<VoucherSummaryDto | null>(() => {
    const rows = this.visibleVouchers();
    if (rows.length === 0) return null;
    const clamped = Math.min(Math.max(0, this.selectedIndex()), rows.length - 1);
    return rows[clamped] ?? null;
  });

  readonly footerTotals = computed(() => {
    const scale = this.companyContext.currencyScale();
    let dr = ExactDecimal.zero(scale);
    let cr = ExactDecimal.zero(scale);

    for (const vch of this.visibleVouchers()) {
      if (vch.status === 'CANCELLED') {
        continue;
      }
      const d = ExactDecimal.tryParse(vch.debitAmount, scale, 'HALF_UP');
      const c = ExactDecimal.tryParse(vch.creditAmount, scale, 'HALF_UP');
      if (d) dr = dr.add(d);
      if (c) cr = cr.add(c);
    }

    return {
      totalDebitWire: dr.toWireString(scale),
      totalCreditWire: cr.toWireString(scale),
    };
  });

  readonly allTypeChoices = computed<readonly VoucherTypeFilterChoice[]>(() => {
    const allChoice: VoucherTypeFilterChoice = {
      id: null,
      name: 'All Vouchers',
      category: null,
      abbreviation: 'ALL',
    };
    const storeTypes = this.coaStore.voucherTypes();
    if (storeTypes.length > 0) {
      return [
        allChoice,
        ...storeTypes.map((vt) => ({
          id: vt.id,
          name: vt.name,
          category: vt.baseCategory,
          abbreviation: vt.abbreviation,
        })),
      ];
    }
    return [
      allChoice,
      ...PREDEFINED_24_VOUCHER_SPECS.map((spec) => ({
        id: null,
        name: spec.name,
        category: spec.category,
        abbreviation: spec.abbreviation,
      })),
    ];
  });

  readonly filteredTypeChoices = computed<readonly VoucherTypeFilterChoice[]>(() => {
    const q = this.typeFilterQuery().trim().toLowerCase();
    const list = this.allTypeChoices();
    if (!q) return list;
    return list.filter(
      (c) => c.name.toLowerCase().includes(q) || c.abbreviation.toLowerCase().includes(q),
    );
  });

  constructor() {
    effect(() => {
      const cid = this.companyContext.activeCompany()?.id ?? null;
      const pFrom = this.companyContext.periodFrom();
      const pTo = this.companyContext.periodTo();
      if (cid && cid !== this.lastLoadedCompanyId) {
        this.lastLoadedCompanyId = cid;
        this.fromDate.set(pFrom);
        this.toDate.set(pTo);
        if (this.coaStore.voucherTypes().length === 0) {
          void this.coaStore.loadVoucherTypes(cid).catch(() => undefined);
        }
        void this.reloadVouchers();
      }
    });
  }

  ngOnInit(): void {
    const cid = this.companyContext.activeCompany()?.id ?? null;
    if (cid && this.lastLoadedCompanyId !== cid) {
      this.lastLoadedCompanyId = cid;
      this.fromDate.set(this.companyContext.periodFrom());
      this.toDate.set(this.companyContext.periodTo());
      if (this.coaStore.voucherTypes().length === 0) {
        void this.coaStore.loadVoucherTypes(cid).catch(() => undefined);
      }
      void this.reloadVouchers();
    }

    const screenActive = () =>
      !this.periodModalOpen() &&
      !this.typeFilterModalOpen() &&
      !this.basisModalOpen() &&
      !this.cancelModalOpen() &&
      (!this.navStack.hasOverlays() || this.embeddedOverlay());

    const disposers: (() => void)[] = [
      this.shortcutRegistry.bindHandler(
        'context.changeDate',
        'SCREEN',
        () => this.openPeriodModal(),
        screenActive,
      ),
      this.shortcutRegistry.bindHandler(
        'context.changePeriod',
        'SCREEN',
        () => this.openPeriodModal(),
        screenActive,
      ),
      this.shortcutRegistry.bindHandler(
        'voucher.contra',
        'SCREEN',
        () => this.openTypeFilterModal(),
        screenActive,
      ),
      this.shortcutRegistry.bindHandler(
        'report.basisOfValues',
        'SCREEN',
        () => this.openBasisModal(),
        screenActive,
      ),
      this.shortcutRegistry.bindHandler(
        'report.toggleDetailed',
        'SCREEN',
        () => this.toggleDetailedMode(),
        screenActive,
      ),
      this.shortcutRegistry.bindHandler(
        'register.addVoucher',
        'SCREEN',
        () => this.addOrInsertVoucher(),
        screenActive,
      ),
      this.shortcutRegistry.bindHandler(
        'register.insertVoucher',
        'SCREEN',
        () => this.addOrInsertVoucher(),
        screenActive,
      ),
      this.shortcutRegistry.bindHandler(
        'register.duplicateVoucher',
        'SCREEN',
        () => {
          void this.duplicateSelectedVoucher();
        },
        screenActive,
      ),
      this.shortcutRegistry.bindHandler(
        'register.cancelVoucher',
        'SCREEN',
        () => this.openCancelConfirmation(),
        screenActive,
      ),
      this.shortcutRegistry.bindHandler(
        'register.removeLine',
        'SCREEN',
        () => this.removeSelectedLine(),
        screenActive,
      ),
      this.shortcutRegistry.bindHandler(
        'register.restoreLine',
        'SCREEN',
        () => this.restoreLastRemovedLine(),
        screenActive,
      ),
      this.shortcutRegistry.bindHandler(
        'audit.editLog',
        'SCREEN',
        () => this.openSelectedEditLog(),
        () => screenActive() && Boolean(this.selectedVoucher()),
      ),
    ];

    this.destroyRef.onDestroy(() => {
      for (const dispose of disposers) {
        dispose();
      }
    });
  }

  openSelectedEditLog(): void {
    const vch = this.selectedVoucher();
    if (!vch) {
      return;
    }
    this.editLogModal.open({
      entityType: 'VOUCHER',
      entityId: vch.id,
      entityLabel: `${vch.voucherTypeName} #${vch.voucherNumber}`,
    });
  }

  @HostListener('document:keydown', ['$event'])
  onDocumentKeyDown(event: KeyboardEvent): void {
    if (
      this.periodModalOpen() ||
      this.typeFilterModalOpen() ||
      this.basisModalOpen() ||
      this.cancelModalOpen()
    ) {
      return;
    }
    if (this.navStack.hasOverlays() && !this.embeddedOverlay()) {
      return;
    }
    if (isEditableTextElement(event.target)) {
      return;
    }
    if (event.altKey || event.ctrlKey || event.metaKey) {
      return;
    }

    const rows = this.visibleVouchers();
    if (rows.length === 0) {
      return;
    }

    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.selectedIndex.set(Math.min(rows.length - 1, this.selectedIndex() + 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.selectedIndex.set(Math.max(0, this.selectedIndex() - 1));
    } else if (event.key === 'Home') {
      event.preventDefault();
      this.selectedIndex.set(0);
    } else if (event.key === 'End') {
      event.preventDefault();
      this.selectedIndex.set(rows.length - 1);
    } else if (event.key === 'Enter') {
      const target = this.selectedVoucher();
      if (target) {
        event.preventDefault();
        this.drillIntoVoucher(target);
      }
    }
  }

  isPositiveWire(amount: string): boolean {
    const dec = ExactDecimal.tryParse(amount, 6, 'HALF_UP');
    return dec !== null && dec.isPositive();
  }

  selectRow(idx: number): void {
    this.selectedIndex.set(idx);
  }

  drillIntoVoucher(voucher: VoucherSummaryDto): void {
    if (this.embeddedOverlay()) {
      this.navStack.clear();
    }
    void this.router.navigate(['/app/vouchers', voucher.id]);
  }

  async reloadVouchers(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;

    this.loading.set(true);
    this.errorBanner.set(null);
    try {
      const page = await this.voucherService.listVouchers(cid, {
        fromDate: this.fromDate(),
        toDate: this.toDate(),
        voucherTypeId: this.filterVoucherTypeId() ?? undefined,
        voucherCategory: this.filterVoucherCategory() ?? undefined,
        includeOptional: this.includeOptional(),
        includeCancelled: this.includeCancelled(),
        limit: 250,
      });
      this.rawVouchers.set(page.items);
      this.nextCursor.set(page.nextCursor);
      this.hasMore.set(page.hasMore);
      this.removedIdsStack.set([]);
      this.selectedIndex.set(0);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to load Day Book');
    } finally {
      this.loading.set(false);
    }
  }

  async loadNextPage(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    const cursor = this.nextCursor();
    if (!cid || !cursor || this.loadingMore()) return;

    this.loadingMore.set(true);
    try {
      const page = await this.voucherService.listVouchers(cid, {
        fromDate: this.fromDate(),
        toDate: this.toDate(),
        voucherTypeId: this.filterVoucherTypeId() ?? undefined,
        voucherCategory: this.filterVoucherCategory() ?? undefined,
        includeOptional: this.includeOptional(),
        includeCancelled: this.includeCancelled(),
        limit: 250,
        cursor,
      });
      this.rawVouchers.set([...this.rawVouchers(), ...page.items]);
      this.nextCursor.set(page.nextCursor);
      this.hasMore.set(page.hasMore);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to load next page');
    } finally {
      this.loadingMore.set(false);
    }
  }

  // ---------------------------------------------------------------------------
  // Period Filter (F2 / Alt+F2)
  // ---------------------------------------------------------------------------

  openPeriodModal(): void {
    this.draftFromDate = this.fromDate();
    this.draftToDate = this.toDate();
    this.periodModalOpen.set(true);
    queueMicrotask(() => {
      this.periodFromInputRef()?.nativeElement.focus();
    });
  }

  closePeriodModal(event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    this.periodModalOpen.set(false);
  }

  applyPeriodFilter(): void {
    if (!this.draftFromDate || !this.draftToDate || this.draftFromDate > this.draftToDate) {
      this.errorBanner.set('From Date must be on or before To Date.');
      return;
    }
    this.fromDate.set(this.draftFromDate);
    this.toDate.set(this.draftToDate);
    try {
      this.companyContext.setPeriodRange(this.draftFromDate, this.draftToDate);
    } catch {
      // Keep local Day Book period filter even if outside company default bounds
    }
    this.periodModalOpen.set(false);
    void this.reloadVouchers();
  }

  // ---------------------------------------------------------------------------
  // Voucher Type Filter (F4: Chg Vch)
  // ---------------------------------------------------------------------------

  openTypeFilterModal(): void {
    this.typeFilterQuery.set('');
    this.typeFilterHighlightIdx.set(0);
    this.typeFilterModalOpen.set(true);
    queueMicrotask(() => {
      this.typeSearchInputRef()?.nativeElement.focus();
    });
  }

  onTypeFilterQueryInput(event: Event): void {
    this.typeFilterQuery.set((event.target as HTMLInputElement).value);
    this.typeFilterHighlightIdx.set(0);
  }

  onTypeFilterKeyDown(event: KeyboardEvent): void {
    const items = this.filteredTypeChoices();
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.typeFilterModalOpen.set(false);
      return;
    }
    if (event.key === 'ArrowDown' && items.length > 0) {
      event.preventDefault();
      this.typeFilterHighlightIdx.set((this.typeFilterHighlightIdx() + 1) % items.length);
      return;
    }
    if (event.key === 'ArrowUp' && items.length > 0) {
      event.preventDefault();
      this.typeFilterHighlightIdx.set(
        (this.typeFilterHighlightIdx() - 1 + items.length) % items.length,
      );
      return;
    }
    if (event.key === 'Enter' && items.length > 0) {
      event.preventDefault();
      this.applyTypeFilterChoice(items[this.typeFilterHighlightIdx()]);
    }
  }

  applyTypeFilterChoice(choice: VoucherTypeFilterChoice): void {
    this.filterVoucherTypeId.set(choice.id);
    this.filterVoucherCategory.set(choice.category);
    this.activeTypeLabel.set(choice.category === null && choice.id === null ? null : choice.name);
    this.typeFilterModalOpen.set(false);
    void this.reloadVouchers();
  }

  // ---------------------------------------------------------------------------
  // Basis of Values (Ctrl+B) & Detailed Toggle (Alt+F1)
  // ---------------------------------------------------------------------------

  openBasisModal(): void {
    this.draftIncludeOptional = this.includeOptional();
    this.draftIncludeCancelled = this.includeCancelled();
    this.basisModalOpen.set(true);
    queueMicrotask(() => {
      this.basisOptionalRef()?.nativeElement.focus();
    });
  }

  closeBasisModal(event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    this.basisModalOpen.set(false);
  }

  applyBasisModal(): void {
    this.includeOptional.set(this.draftIncludeOptional);
    this.includeCancelled.set(this.draftIncludeCancelled);
    this.basisModalOpen.set(false);
    void this.reloadVouchers();
  }

  toggleDetailedMode(): void {
    this.detailedMode.set(!this.detailedMode());
  }

  // ---------------------------------------------------------------------------
  // Add / Insert (Alt+A / Alt+I), Duplicate (Alt+2), Cancel (Alt+X)
  // ---------------------------------------------------------------------------

  addOrInsertVoucher(): void {
    const highlighted = this.selectedVoucher();
    this.voucherService.stageSeedParams({
      voucherDate: highlighted?.voucherDate ?? this.fromDate(),
      voucherTypeId: highlighted?.voucherTypeId || undefined,
      voucherCategory: highlighted?.voucherCategory,
    });
    if (this.embeddedOverlay()) {
      this.navStack.clear();
    }
    void this.router.navigate(['/app/vouchers/new']);
  }

  async duplicateSelectedVoucher(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    const row = this.selectedVoucher();
    if (!cid || !row || this.busyAction()) return;

    this.busyAction.set(true);
    this.errorBanner.set(null);
    try {
      const detail = await this.voucherService.getVoucher(cid, row.id);
      this.voucherService.stageDuplicateDraft(detail);
      if (this.embeddedOverlay()) {
        this.navStack.clear();
      }
      await this.router.navigate(['/app/vouchers/new'], {
        queryParams: { duplicateFrom: row.id },
      });
    } catch (err: unknown) {
      this.errorBanner.set(
        err instanceof Error ? err.message : 'Unable to load source voucher for duplication.',
      );
    } finally {
      this.busyAction.set(false);
    }
  }

  openCancelConfirmation(): void {
    const row = this.selectedVoucher();
    if (!row || row.status === 'CANCELLED' || this.companyContext.quarantined()) {
      return;
    }
    this.cancelReasonInput = '';
    this.cancelModalOpen.set(true);
    queueMicrotask(() => {
      this.cancelYesBtnRef()?.nativeElement.focus();
    });
  }

  onCancelModalKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.cancelModalOpen.set(false);
      return;
    }
    if (!isEditableTextElement(event.target)) {
      if (event.key === 'n' || event.key === 'N') {
        event.preventDefault();
        this.cancelModalOpen.set(false);
      } else if (event.key === 'y' || event.key === 'Y') {
        event.preventDefault();
        void this.confirmCancelSelectedVoucher();
      }
    }
  }

  async confirmCancelSelectedVoucher(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    const row = this.selectedVoucher();
    if (!cid || !row || row.status === 'CANCELLED' || this.busyAction()) {
      return;
    }

    this.busyAction.set(true);
    this.errorBanner.set(null);
    try {
      const reason = this.cancelReasonInput.trim() || 'Cancelled via Day Book (Alt+X)';
      const updated = await this.voucherService.cancelVoucher(cid, row.id, reason, row.versionNo);
      this.rawVouchers.set(
        this.rawVouchers().map((v) =>
          v.id === row.id
            ? {
                ...v,
                status: updated.status ?? 'CANCELLED',
                debitAmount: '0.00',
                creditAmount: '0.00',
                versionNo: updated.versionNo,
              }
            : v,
        ),
      );
      this.cancelModalOpen.set(false);
      this.statusBanner.set(
        `Cancelled ${row.voucherTypeName} voucher #${row.voucherNumber} (number reserved).`,
      );
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to cancel voucher.');
    } finally {
      this.busyAction.set(false);
    }
  }

  // ---------------------------------------------------------------------------
  // Ad-Hoc Session Remove Line (Alt+R) / Restore Line (Alt+U)
  // ---------------------------------------------------------------------------

  removeSelectedLine(): void {
    const row = this.selectedVoucher();
    if (!row) return;
    this.removedIdsStack.set([...this.removedIdsStack(), row.id]);
    const remaining = this.visibleVouchers().length;
    if (this.selectedIndex() >= remaining && remaining > 0) {
      this.selectedIndex.set(remaining - 1);
    }
  }

  restoreLastRemovedLine(): void {
    const stack = this.removedIdsStack();
    if (stack.length === 0) return;
    this.removedIdsStack.set(stack.slice(0, -1));
  }
}
