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
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { INDIAN_GST_STATES, validateGstin } from '../../core/validation/gstin-validator';
import { AmountCellComponent } from '../../shared/components/amount-cell/amount-cell.component';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import {
  TypeaheadOption,
  TypeaheadSelectComponent,
} from '../../shared/components/typeahead-select/typeahead-select.component';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { ChartOfAccountsStore } from './chart-of-accounts.store';
import {
  AppropriateForType,
  AppropriationBasis,
  BalanceSide,
  CreateOrUpdateLedgerPayload,
  GstApplicability,
  GstSupplyType,
  Ledger,
  LedgerDutyHead,
  LedgerRoundingMethod,
  LedgerTaxType,
  PartyRegistrationType,
  TDS_DEDUCTEE_TYPES,
  TDS_NATURE_SECTIONS,
  resolveGroupLineageFlags,
} from './masters.models';
import { SecondaryMasterModalService } from './secondary-master-modal.service';

@Component({
  selector: 'mc-ledger-workbench',
  standalone: true,
  imports: [
    FormsModule,
    RouterLink,
    AmountCellComponent,
    CodeBadgeComponent,
    TypeaheadSelectComponent,
    GridCellNavDirective,
    IndianCurrencyPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-ledger-wb-split">
      <!-- Left Primary Ledger Creation / Alteration Form -->
      <form
        class="mc-card mc-ledger-form-card"
        data-mc-nav-container
        (ngSubmit)="saveLedger()"
      >
        <div class="mc-ledger-header">
          <div class="mc-ledger-title-wrap">
            <h1>{{ editingLedger() ? 'Ledger Alteration' : 'Ledger Creation' }}</h1>
            @if (editingLedger()?.isSystemLedger) {
              <span class="mc-badge mc-badge-slate" data-testid="system-ledger-badge">
                System Ledger (Protected)
              </span>
            }
          </div>
          <div class="mc-ledger-header-btns">
            <button type="button" class="mc-btn" (click)="startCreateLedger()">+ New Ledger</button>
            <a routerLink="/app/coa" class="mc-btn">Chart of Accounts</a>
          </div>
        </div>

        @if (statusMessage(); as msg) {
          <div class="mc-ledger-status" role="status">{{ msg }}</div>
        }
        @if (formError(); as err) {
          <div class="mc-field-error mc-ledger-err" role="alert">{{ err }}</div>
        }

        <!-- Core Identity: Name, Alias, Under (28+ Groups) -->
        <div class="mc-led-grid-3">
          <div>
            <label class="mc-field-label" for="ledger-name">Name</label>
            <input
              #nameInput
              id="ledger-name"
              type="text"
              name="ledgerName"
              class="mc-input"
              mcGridCellNav
              [readOnly]="Boolean(editingLedger()?.isSystemLedger)"
              [ngModel]="formName()"
              (ngModelChange)="onNameInput($event)"
              required
            />
          </div>

          <div>
            <label class="mc-field-label" for="ledger-alias">(Alias)</label>
            <input
              id="ledger-alias"
              type="text"
              name="ledgerAlias"
              class="mc-input mc-code"
              mcGridCellNav
              [ngModel]="formAlias()"
              (ngModelChange)="formAlias.set($event); markDirty()"
              placeholder="Optional short code"
            />
          </div>

          <div>
            <label class="mc-field-label" for="ledger-under-group">
              Under (<span class="mc-key-alt">C</span> to create Group)
            </label>
            <mc-typeahead-select
              inputId="ledger-under-group"
              listTitle="List of Groups"
              [options]="coaStore.groupTypeaheadOptions()"
              [selectedId]="formGroupId()"
              [disabled]="Boolean(editingLedger()?.isSystemLedger)"
              [allowInlineCreate]="true"
              (optionSelected)="onGroupSelected($event)"
              (createRequested)="openInlineGroupCreator($event)"
            />
          </div>
        </div>

        <!-- Two-Column Tally-Style Conditional Panels: Left = Accounting/Statutory, Right = Mailing/Tax/Bank -->
        <div class="mc-led-columns">
          <!-- Left Column: Sub-Ledger, Inventory, Cost Centres, Tax/Duty, GST/TDS -->
          <div class="mc-led-col">
            <div class="mc-led-section">
              <div class="mc-led-section-title">Accounting, Bill-Wise &amp; Statutory Rules</div>

              @if (lineage().isPartyGroup) {
                <div class="mc-led-conditional-box" data-testid="party-billwise-section">
                  <label class="mc-led-toggle">
                    <input
                      type="checkbox"
                      name="billWise"
                      [ngModel]="formBillWise()"
                      (ngModelChange)="formBillWise.set($event); markDirty()"
                    />
                    <span>Maintain balances bill-by-bill</span>
                  </label>

                  @if (formBillWise()) {
                    <div class="mc-led-grid-2">
                      <div>
                        <label class="mc-field-label" for="ledger-credit-days">
                          Default credit period (days)
                        </label>
                        <input
                          id="ledger-credit-days"
                          type="number"
                          min="0"
                          name="creditDays"
                          class="mc-input"
                          mcGridCellNav
                          [ngModel]="formCreditDays()"
                          (ngModelChange)="formCreditDays.set($event); markDirty()"
                        />
                      </div>
                      <div>
                        <label class="mc-field-label" for="ledger-credit-limit">
                          Specify credit limit
                        </label>
                        <mc-amount-cell
                          inputId="ledger-credit-limit"
                          name="creditLimit"
                          [scale]="companyContext.currencyScale()"
                          [currencySymbol]="companyContext.currencySymbol()"
                          [ngModel]="formCreditLimit()"
                          (ngModelChange)="formCreditLimit.set($event); markDirty()"
                        />
                      </div>
                    </div>

                    <label class="mc-led-toggle">
                      <input
                        type="checkbox"
                        name="checkCreditDays"
                        [ngModel]="formCheckCreditDays()"
                        (ngModelChange)="formCheckCreditDays.set($event); markDirty()"
                      />
                      <span>Check for credit days during voucher entry</span>
                    </label>
                  }
                </div>
              }

              <div class="mc-led-toggles-stack">
                <label class="mc-led-toggle">
                  <input
                    type="checkbox"
                    name="inventoryAffected"
                    [ngModel]="formInventoryAffected()"
                    (ngModelChange)="formInventoryAffected.set($event); markDirty()"
                  />
                  <span>Inventory values are affected</span>
                </label>

                <label class="mc-led-toggle">
                  <input
                    type="checkbox"
                    name="costCentresApplicable"
                    [ngModel]="formCostCentresApplicable()"
                    (ngModelChange)="formCostCentresApplicable.set($event); markDirty()"
                  />
                  <span>Cost centres are applicable</span>
                </label>
              </div>

              <!-- Duties & Taxes Conditional Section -->
              @if (lineage().isDutiesAndTaxes || formTaxType() !== 'NONE') {
                <div class="mc-led-conditional-box" data-testid="duties-taxes-section">
                  <div class="mc-led-subhead">Duties &amp; Taxes Classification</div>
                  <div class="mc-led-grid-2">
                    <div>
                      <label class="mc-field-label" for="ledger-tax-type">Type of Duty/Tax</label>
                      <select
                        id="ledger-tax-type"
                        name="taxType"
                        class="mc-select"
                        mcGridCellNav
                        [ngModel]="formTaxType()"
                        (ngModelChange)="onTaxTypeChange($event)"
                      >
                        <option value="NONE">Not Applicable</option>
                        <option value="GST">GST</option>
                        <option value="TDS">TDS</option>
                        <option value="TCS">TCS</option>
                        <option value="OTHERS">Others</option>
                      </select>
                    </div>

                    @if (formTaxType() === 'GST') {
                      <div data-testid="gst-duty-head-field">
                        <label class="mc-field-label" for="ledger-duty-head">Tax Type (Duty Head)</label>
                        <select
                          id="ledger-duty-head"
                          name="dutyHead"
                          class="mc-select"
                          mcGridCellNav
                          [ngModel]="formDutyHead()"
                          (ngModelChange)="formDutyHead.set($event); markDirty()"
                        >
                          <option value="CGST">CGST</option>
                          <option value="SGST_UTGST">SGST / UTGST</option>
                          <option value="IGST">IGST</option>
                          <option value="CESS">Cess</option>
                        </select>
                      </div>
                    }

                    @if (formTaxType() === 'TDS' || formTaxType() === 'TCS') {
                      <div data-testid="tds-nature-duty-field">
                        <label class="mc-field-label" for="ledger-duty-tds-nature">
                          TDS / TCS Nature of Payment
                        </label>
                        <mc-typeahead-select
                          inputId="ledger-duty-tds-nature"
                          listTitle="List of TDS Natures"
                          [options]="tdsNatureOptions"
                          [selectedId]="formTdsNatureCode()"
                          [allowInlineCreate]="false"
                          (optionSelected)="formTdsNatureCode.set($event.id); markDirty()"
                        />
                      </div>
                    }
                  </div>
                </div>
              }

              <!-- Sales / Purchase / Expense / Income GST, Apportionment, Rounding & TDS -->
              @if (lineage().isSalesOrPurchase || lineage().isExpenseOrIncome) {
                <div class="mc-led-conditional-box" data-testid="revenue-statutory-section">
                  <div class="mc-led-subhead">Statutory GST, Apportionment &amp; TDS</div>

                  <div class="mc-led-grid-3">
                    <div>
                      <label class="mc-field-label" for="ledger-gst-app">Is GST Applicable</label>
                      <select
                        id="ledger-gst-app"
                        name="gstApplicability"
                        class="mc-select"
                        mcGridCellNav
                        [ngModel]="formGstApplicability()"
                        (ngModelChange)="formGstApplicability.set($event); markDirty()"
                      >
                        <option value="APPLICABLE">Applicable</option>
                        <option value="NOT_APPLICABLE">Not Applicable</option>
                        <option value="UNDEFINED">As per Group / Item</option>
                      </select>
                    </div>

                    <div>
                      <label class="mc-field-label" for="ledger-supply-type">Type of Supply</label>
                      <select
                        id="ledger-supply-type"
                        name="gstSupplyType"
                        class="mc-select"
                        mcGridCellNav
                        [ngModel]="formGstSupplyType()"
                        (ngModelChange)="formGstSupplyType.set($event); markDirty()"
                      >
                        <option value="GOODS">Goods</option>
                        <option value="SERVICES">Services</option>
                        <option value="CAPITAL_GOODS">Capital Goods</option>
                      </select>
                    </div>

                    <div>
                      <label class="mc-field-label" for="ledger-appropriate">
                        Appropriate to GST
                      </label>
                      <select
                        id="ledger-appropriate"
                        name="appropriateFor"
                        class="mc-select"
                        mcGridCellNav
                        [ngModel]="formAppropriateFor()"
                        (ngModelChange)="onAppropriateForChange($event)"
                      >
                        <option value="NONE">Not Applicable</option>
                        <option value="GST">GST (By Value / Qty)</option>
                        <option value="BOTH_VALUE">Both GST &amp; Inventory (Value)</option>
                        <option value="BOTH_QTY">Both GST &amp; Inventory (Qty)</option>
                      </select>
                    </div>
                  </div>

                  <div class="mc-led-grid-3">
                    <div>
                      <label class="mc-field-label" for="ledger-hsn">HSN / SAC Code</label>
                      <input
                        id="ledger-hsn"
                        type="text"
                        name="hsnSacCode"
                        class="mc-input mc-code"
                        mcGridCellNav
                        [ngModel]="formHsnSac()"
                        (ngModelChange)="formHsnSac.set($event); markDirty()"
                        placeholder="90189099"
                      />
                    </div>
                    <div>
                      <label class="mc-field-label" for="ledger-gstrate">GST Rate (%)</label>
                      <input
                        id="ledger-gstrate"
                        type="text"
                        name="gstRatePct"
                        class="mc-input mc-amount"
                        mcGridCellNav
                        [ngModel]="formGstRatePct()"
                        (ngModelChange)="formGstRatePct.set($event); markDirty()"
                        placeholder="18.00"
                      />
                    </div>
                    <div>
                      <label class="mc-field-label" for="ledger-rounding">Rounding Method</label>
                      <select
                        id="ledger-rounding"
                        name="roundingMethod"
                        class="mc-select"
                        mcGridCellNav
                        [ngModel]="formRoundingMethod()"
                        (ngModelChange)="formRoundingMethod.set($event); markDirty()"
                      >
                        <option value="NONE">Not Applicable</option>
                        <option value="NORMAL_HALF_UP">Normal Rounding</option>
                        <option value="UPWARD">Upward Rounding</option>
                        <option value="DOWNWARD">Downward Rounding</option>
                      </select>
                    </div>
                  </div>

                  <div class="mc-led-tds-row">
                    <label class="mc-led-toggle">
                      <input
                        type="checkbox"
                        name="tdsApplicable"
                        [ngModel]="formTdsApplicable()"
                        (ngModelChange)="formTdsApplicable.set($event); markDirty()"
                      />
                      <span>Is TDS Applicable?</span>
                    </label>

                    @if (formTdsApplicable()) {
                      <div class="mc-led-tds-select">
                        <label class="mc-field-label" for="ledger-exp-tds-nature">
                          Nature of Payment (Section)
                        </label>
                        <mc-typeahead-select
                          inputId="ledger-exp-tds-nature"
                          listTitle="List of TDS Natures"
                          [options]="tdsNatureOptions"
                          [selectedId]="formTdsNatureCode()"
                          [allowInlineCreate]="false"
                          (optionSelected)="formTdsNatureCode.set($event.id); markDirty()"
                        />
                      </div>
                    }
                  </div>
                </div>
              }

              <!-- Bank Account Details Conditional Section -->
              @if (lineage().isBankAccount) {
                <div class="mc-led-conditional-box" data-testid="bank-details-section">
                  <div class="mc-led-subhead">Bank Account Details</div>
                  <div class="mc-led-grid-2">
                    <div>
                      <label class="mc-field-label" for="bank-holder">A/c Holder's Name</label>
                      <input
                        id="bank-holder"
                        type="text"
                        name="bankAccountHolder"
                        class="mc-input"
                        mcGridCellNav
                        [ngModel]="formBankHolder()"
                        (ngModelChange)="formBankHolder.set($event); markDirty()"
                      />
                    </div>
                    <div>
                      <label class="mc-field-label" for="bank-acno">A/c No.</label>
                      <input
                        id="bank-acno"
                        type="text"
                        name="bankAccountNumber"
                        class="mc-input mc-code"
                        mcGridCellNav
                        [ngModel]="formBankAccountNo()"
                        (ngModelChange)="formBankAccountNo.set($event); markDirty()"
                      />
                    </div>
                  </div>
                  <div class="mc-led-grid-3">
                    <div>
                      <label class="mc-field-label" for="bank-ifsc">IFS Code</label>
                      <input
                        id="bank-ifsc"
                        type="text"
                        name="bankIfscCode"
                        class="mc-input mc-code"
                        mcGridCellNav
                        [ngModel]="formBankIfsc()"
                        (ngModelChange)="formBankIfsc.set($event.toUpperCase()); markDirty()"
                        placeholder="HDFC0000123"
                      />
                    </div>
                    <div>
                      <label class="mc-field-label" for="bank-name">Bank Name</label>
                      <input
                        id="bank-name"
                        type="text"
                        name="bankName"
                        class="mc-input"
                        mcGridCellNav
                        [ngModel]="formBankName()"
                        (ngModelChange)="formBankName.set($event); markDirty()"
                      />
                    </div>
                    <div>
                      <label class="mc-field-label" for="bank-branch">Branch / UPI ID</label>
                      <input
                        id="bank-branch"
                        type="text"
                        name="bankBranch"
                        class="mc-input"
                        mcGridCellNav
                        [ngModel]="formBankBranch()"
                        (ngModelChange)="formBankBranch.set($event); markDirty()"
                      />
                    </div>
                  </div>
                </div>
              }
            </div>
          </div>

          <!-- Right Column: Mailing & Tax Registration Details -->
          <div class="mc-led-col">
            <div class="mc-led-section">
              <div class="mc-led-section-title">Mailing &amp; Tax Registration Details</div>

              <div class="mc-led-grid-2">
                <div>
                  <label class="mc-field-label" for="ledger-mailing-name">Mailing Name</label>
                  <input
                    id="ledger-mailing-name"
                    type="text"
                    name="mailingName"
                    class="mc-input"
                    mcGridCellNav
                    [ngModel]="formMailingName()"
                    (ngModelChange)="formMailingName.set($event); markDirty()"
                  />
                </div>
                <div>
                  <label class="mc-field-label" for="ledger-pin">Pincode</label>
                  <input
                    id="ledger-pin"
                    type="text"
                    name="pincode"
                    class="mc-input mc-code"
                    mcGridCellNav
                    [ngModel]="formPincode()"
                    (ngModelChange)="formPincode.set($event); markDirty()"
                  />
                </div>
              </div>

              <div>
                <label class="mc-field-label" for="ledger-address">Address</label>
                <input
                  id="ledger-address"
                  type="text"
                  name="mailingAddress"
                  class="mc-input"
                  mcGridCellNav
                  [ngModel]="formMailingAddress()"
                  (ngModelChange)="formMailingAddress.set($event); markDirty()"
                />
              </div>

              <div class="mc-led-grid-2">
                <div>
                  <label class="mc-field-label" for="ledger-country">Country</label>
                  <input
                    id="ledger-country"
                    type="text"
                    name="countryCode"
                    class="mc-input"
                    mcGridCellNav
                    [ngModel]="formCountryCode()"
                    (ngModelChange)="formCountryCode.set($event.toUpperCase()); markDirty()"
                  />
                </div>
                <div>
                  <label class="mc-field-label" for="ledger-state">State</label>
                  <mc-typeahead-select
                    inputId="ledger-state"
                    listTitle="List of States"
                    [options]="stateTypeaheadOptions"
                    [selectedId]="formStateCode()"
                    [allowInlineCreate]="false"
                    (optionSelected)="formStateCode.set($event.id); markDirty()"
                  />
                </div>
              </div>

              <div class="mc-led-grid-2">
                <div>
                  <label class="mc-field-label" for="ledger-reg-type">Registration Type</label>
                  <select
                    id="ledger-reg-type"
                    name="partyRegistrationType"
                    class="mc-select"
                    mcGridCellNav
                    [ngModel]="formPartyRegType()"
                    (ngModelChange)="formPartyRegType.set($event); markDirty()"
                  >
                    <option value="UNKNOWN">Not Applicable / Unknown</option>
                    <option value="REGULAR">Regular</option>
                    <option value="COMPOSITION">Composition</option>
                    <option value="UNREGISTERED">Unregistered / Consumer</option>
                    <option value="CONSUMER">Consumer (B2C)</option>
                    <option value="SEZ">SEZ</option>
                    <option value="OVERSEAS">Overseas</option>
                    <option value="GOVERNMENT_ENTITY">Government Entity / PSU</option>
                  </select>
                </div>

                <div>
                  <label class="mc-field-label" for="ledger-gstin">
                    GSTIN / UIN (Mod-36 Verified)
                  </label>
                  <input
                    id="ledger-gstin"
                    type="text"
                    maxlength="15"
                    name="gstin"
                    class="mc-input mc-code"
                    mcGridCellNav
                    [ngModel]="formGstin()"
                    (ngModelChange)="onGstinInput($event)"
                    [attr.aria-invalid]="formGstin().trim().length > 0 && !gstinValidation().valid"
                    placeholder="e.g. 33AAFCK5678G1ZC"
                  />
                </div>
              </div>

              @if (formGstin().trim().length > 0) {
                @if (gstinValidation().valid) {
                  <div class="mc-gstin-ok" data-testid="ledger-gstin-valid">
                    Valid Luhn Mod-36 GSTIN &bull; State {{ gstinValidation().stateCode }} &bull; PAN {{ gstinValidation().pan }}
                  </div>
                } @else {
                  <div class="mc-field-error" role="alert" data-testid="ledger-gstin-error">
                    {{ gstinValidation().error }}
                  </div>
                }
              }

              <div class="mc-led-grid-2">
                <div>
                  <label class="mc-field-label" for="ledger-pan">PAN / IT No.</label>
                  <input
                    id="ledger-pan"
                    type="text"
                    maxlength="10"
                    name="pan"
                    class="mc-input mc-code"
                    mcGridCellNav
                    [ngModel]="formPan()"
                    (ngModelChange)="formPan.set($event.toUpperCase()); markDirty()"
                    placeholder="AABFA4321K"
                  />
                </div>
                <div>
                  <label class="mc-field-label" for="ledger-deductee">TDS Deductee Type</label>
                  <select
                    id="ledger-deductee"
                    name="tdsDeducteeType"
                    class="mc-select"
                    mcGridCellNav
                    [ngModel]="formTdsDeducteeType()"
                    (ngModelChange)="formTdsDeducteeType.set($event); markDirty()"
                  >
                    <option value="">Not Specified</option>
                    @for (dt of deducteeTypes; track dt.code) {
                      <option [value]="dt.code">{{ dt.label }}</option>
                    }
                  </select>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Bottom Tally Opening Balance Bar & Accept Footer -->
        <div class="mc-led-opening-bar">
          <div class="mc-led-opening-field">
            <label class="mc-field-label" for="ledger-opening-balance">
              Opening Balance (on {{ companyContext.activeCompany()?.booksBeginningFrom ?? '2025-04-01' }})
            </label>
            <div class="mc-led-opbal-inputs">
              <mc-amount-cell
                inputId="ledger-opening-balance"
                name="openingBalance"
                [scale]="companyContext.currencyScale()"
                [currencySymbol]="companyContext.currencySymbol()"
                [ngModel]="formOpeningBalance()"
                (ngModelChange)="formOpeningBalance.set($event); markDirty()"
              />
              <select
                name="openingSide"
                class="mc-select mc-op-side-select"
                mcGridCellNav
                [ngModel]="formOpeningSide()"
                (ngModelChange)="formOpeningSide.set($event); markDirty()"
                aria-label="Opening Balance Dr or Cr"
              >
                <option value="DR">Dr</option>
                <option value="CR">Cr</option>
              </select>
            </div>
          </div>

          <div class="mc-led-opening-summary">
            <span>
              Total Op. Dr:
              <strong class="mc-amount">
                {{ coaStore.totalOpeningDebit() | mcIndianCurrency: companyContext.currencyScale() : true }}
              </strong>
            </span>
            <span>
              Total Op. Cr:
              <strong class="mc-amount">
                {{ coaStore.totalOpeningCredit() | mcIndianCurrency: companyContext.currencyScale() : true }}
              </strong>
            </span>
            <span>
              Diff:
              <strong class="mc-amount">
                {{ coaStore.openingDifference() | mcIndianCurrency: companyContext.currencyScale() : true }}
              </strong>
            </span>
          </div>
        </div>

        <div class="mc-ledger-footer">
          <div>
            @if (editingLedger(); as led) {
              <button
                type="button"
                class="mc-btn mc-btn-danger"
                data-testid="delete-ledger-btn"
                [disabled]="led.isSystemLedger || companyContext.quarantined()"
                (click)="deleteCurrentLedger()"
              >
                <span class="mc-key-alt">D</span>: Delete
              </button>
            }
          </div>

          <div class="mc-ledger-footer-right">
            <span class="mc-ledger-hint">
              Enter = Next &bull; Shift+Enter = Prev &bull; <mc-code-badge code="Ctrl+A" /> = Save
            </span>
            <button
              type="submit"
              class="mc-btn mc-btn-primary"
              data-testid="accept-ledger-btn"
              [disabled]="saving() || companyContext.quarantined()"
            >
              Accept (<span class="mc-key-ctrl">A</span>)
            </button>
          </div>
        </div>
      </form>

      <!-- Right Directory List of Existing Ledgers -->
      <aside class="mc-card mc-ledger-directory-card" aria-label="Ledgers Directory">
        <div class="mc-ledger-dir-head">
          <strong>Active Ledgers ({{ filteredLedgers().length }})</strong>
          <input
            type="text"
            class="mc-input"
            [ngModel]="directoryQuery()"
            (ngModelChange)="directoryQuery.set($event)"
            placeholder="Filter by name, group, GSTIN..."
            aria-label="Filter Existing Ledgers"
          />
        </div>

        <ul class="mc-ledger-dir-list">
          @for (led of filteredLedgers(); track led.id) {
            <li
              class="mc-ledger-dir-row"
              [class.mc-ledger-dir-active]="editingLedger()?.id === led.id"
              (click)="selectLedgerForAlter(led)"
            >
              <div class="mc-ledger-dir-info">
                <span class="mc-ledger-dir-name">{{ led.name }}</span>
                <span class="mc-ledger-dir-sub">
                  {{ groupNameById(led.groupId, led.groupName) }}
                </span>
              </div>
              <div class="mc-ledger-dir-right">
                @if (led.isSystemLedger) {
                  <span class="mc-badge mc-badge-slate">System</span>
                }
                <span class="mc-amount mc-ledger-dir-amt">
                  {{ led.openingBalance | mcIndianCurrency: companyContext.currencyScale() : true }}
                  {{ led.openingSide }}
                </span>
              </div>
            </li>
          } @empty {
            <li class="mc-ledger-dir-empty">No ledgers match filter.</li>
          }
        </ul>
      </aside>
    </div>
  `,
  styles: `
    .mc-ledger-wb-split {
      display: grid;
      grid-template-columns: minmax(0, 1.75fr) minmax(270px, 0.78fr);
      gap: var(--mc-space-4);
      align-items: start;
    }

    @media (max-width: 1120px) {
      .mc-ledger-wb-split {
        grid-template-columns: 1fr;
      }
    }

    .mc-ledger-form-card {
      padding: var(--mc-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-ledger-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-2);
      padding-bottom: var(--mc-space-2);
      border-bottom: var(--mc-border-subtle);
      flex-wrap: wrap;
    }

    .mc-ledger-title-wrap {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);

      h1 {
        margin: 0;
        font-size: var(--mc-font-size-lg);
      }
    }

    .mc-ledger-header-btns {
      display: flex;
      gap: var(--mc-space-2);
    }

    .mc-ledger-status {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-teal-50);
      border-left: 3px solid var(--mc-color-verdigris-teal);
      color: var(--mc-color-teal-700);
      font-weight: 600;
      font-size: var(--mc-font-size-xs);
    }

    .mc-ledger-err {
      padding: var(--mc-space-2);
      background: var(--mc-color-danger-50);
      border-left: 3px solid var(--mc-color-danger-600);
    }

    .mc-led-grid-2 {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-2);
    }

    .mc-led-grid-3 {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    .mc-led-columns {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-3);
      align-items: start;
    }

    @media (max-width: 900px) {
      .mc-led-grid-3,
      .mc-led-columns {
        grid-template-columns: 1fr;
      }
    }

    .mc-led-col {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-led-section {
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-sm);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-led-section-title {
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--mc-color-slate-800);
      border-bottom: var(--mc-border-subtle);
      padding-bottom: 4px;
    }

    .mc-led-conditional-box {
      padding: var(--mc-space-2);
      background: var(--mc-color-surface);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-led-subhead {
      font-size: 11px;
      font-weight: 700;
      color: var(--mc-color-teal-700);
      text-transform: uppercase;
    }

    .mc-led-toggles-stack {
      display: flex;
      flex-wrap: wrap;
      gap: var(--mc-space-3);
    }

    .mc-led-toggle {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      font-weight: 500;
      cursor: pointer;
    }

    .mc-led-tds-row {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
      padding-top: 4px;
      border-top: 1px dashed var(--mc-color-slate-200);
    }

    .mc-led-tds-select {
      max-width: 340px;
    }

    .mc-gstin-ok {
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
      color: var(--mc-color-success-700);
    }

    .mc-led-opening-bar {
      padding: var(--mc-space-3);
      background: var(--mc-color-teal-50);
      border: 1px solid var(--mc-color-teal-100);
      border-radius: var(--mc-radius-sm);
      display: flex;
      justify-content: space-between;
      align-items: flex-end;
      gap: var(--mc-space-3);
      flex-wrap: wrap;
    }

    .mc-led-opening-field {
      min-width: 280px;
    }

    .mc-led-opbal-inputs {
      display: grid;
      grid-template-columns: 1fr 72px;
      gap: 6px;
    }

    .mc-op-side-select {
      font-weight: 700;
    }

    .mc-led-opening-summary {
      display: flex;
      gap: var(--mc-space-3);
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-700);
      flex-wrap: wrap;
    }

    .mc-ledger-footer {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-top: var(--mc-space-2);
      border-top: var(--mc-border-subtle);
      gap: var(--mc-space-2);
    }

    .mc-ledger-footer-right {
      display: flex;
      align-items: center;
      gap: var(--mc-space-3);
    }

    .mc-ledger-hint {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-500);
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }

    .mc-ledger-directory-card {
      overflow: hidden;
      display: flex;
      flex-direction: column;
      max-height: calc(100vh - 160px);
    }

    .mc-ledger-dir-head {
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border-bottom: var(--mc-border-subtle);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-ledger-dir-list {
      list-style: none;
      margin: 0;
      padding: 0;
      overflow-y: auto;
    }

    .mc-ledger-dir-row {
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

    .mc-ledger-dir-active {
      background: var(--mc-color-teal-50);
      border-left: 3px solid var(--mc-color-verdigris-teal);
    }

    .mc-ledger-dir-info {
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-width: 0;
    }

    .mc-ledger-dir-name {
      font-weight: 600;
      color: var(--mc-color-slate-900);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .mc-ledger-dir-sub {
      font-size: 11px;
      color: var(--mc-color-slate-600);
    }

    .mc-ledger-dir-right {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      gap: 2px;
      white-space: nowrap;
    }

    .mc-ledger-dir-amt {
      font-size: var(--mc-font-size-xs);
    }

    .mc-ledger-dir-empty {
      padding: var(--mc-space-3);
      text-align: center;
      color: var(--mc-color-slate-500);
      font-size: var(--mc-font-size-xs);
    }
  `,
})
export class LedgerWorkbenchComponent implements OnInit {
  protected readonly Boolean = Boolean;

  readonly coaStore = inject(ChartOfAccountsStore);
  readonly companyContext = inject(CompanyContextStore);

  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly secondaryMasterModal = inject(SecondaryMasterModalService);
  private readonly navStack = inject(NavigationStackStore);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  readonly embeddedOverlay = input<boolean>(false);
  readonly initialLedgerId = input<string | null>(null);

  private readonly nameInputRef = viewChild<ElementRef<HTMLInputElement>>('nameInput');

  readonly stateTypeaheadOptions: readonly TypeaheadOption[] = INDIAN_GST_STATES.map((st) => ({
    id: st.code,
    label: `${st.code} - ${st.name}`,
    secondaryLabel: st.code,
    codeBadge: st.code,
  }));

  readonly tdsNatureOptions: readonly TypeaheadOption[] = TDS_NATURE_SECTIONS.map((s) => ({
    id: s.section,
    label: s.name,
    secondaryLabel: `${s.rateWithPanPct}%`,
    codeBadge: s.paymentCode,
  }));

  readonly deducteeTypes = TDS_DEDUCTEE_TYPES;

  readonly editingLedger = signal<Ledger | null>(null);
  readonly saving = signal<boolean>(false);
  readonly statusMessage = signal<string | null>(null);
  readonly formError = signal<string | null>(null);
  readonly directoryQuery = signal<string>('');

  // Reactive Form Signals
  readonly formName = signal<string>('');
  readonly formAlias = signal<string>('');
  readonly formGroupId = signal<string | null>(null);

  readonly formBillWise = signal<boolean>(false);
  readonly formCreditDays = signal<number | null>(null);
  readonly formCheckCreditDays = signal<boolean>(false);
  readonly formCreditLimit = signal<string>('0.00');

  readonly formInventoryAffected = signal<boolean>(false);
  readonly formCostCentresApplicable = signal<boolean>(false);

  readonly formTaxType = signal<LedgerTaxType>('NONE');
  readonly formDutyHead = signal<LedgerDutyHead>('CGST');

  readonly formGstApplicability = signal<GstApplicability>('UNDEFINED');
  readonly formGstSupplyType = signal<GstSupplyType>('GOODS');
  readonly formHsnSac = signal<string>('');
  readonly formGstRatePct = signal<string>('');
  readonly formAppropriateFor = signal<AppropriateForType>('NONE');
  readonly formAppropriationBasis = signal<AppropriationBasis | null>(null);
  readonly formRoundingMethod = signal<LedgerRoundingMethod>('NONE');

  readonly formTdsApplicable = signal<boolean>(false);
  readonly formTdsNatureCode = signal<string | null>('194J');
  readonly formTdsDeducteeType = signal<string>('');

  readonly formBankHolder = signal<string>('');
  readonly formBankAccountNo = signal<string>('');
  readonly formBankIfsc = signal<string>('');
  readonly formBankName = signal<string>('');
  readonly formBankBranch = signal<string>('');

  readonly formMailingName = signal<string>('');
  readonly formMailingAddress = signal<string>('');
  readonly formCountryCode = signal<string>('IN');
  readonly formStateCode = signal<string>('33');
  readonly formPincode = signal<string>('');
  readonly formPartyRegType = signal<PartyRegistrationType>('UNKNOWN');
  readonly formGstin = signal<string>('');
  readonly formPan = signal<string>('');

  readonly formOpeningBalance = signal<string>('0.00');
  readonly formOpeningSide = signal<BalanceSide>('DR');

  readonly lineage = computed(() =>
    resolveGroupLineageFlags(this.formGroupId(), this.coaStore.groups()),
  );

  readonly gstinValidation = computed(() =>
    validateGstin(this.formGstin(), this.formStateCode() || undefined),
  );

  readonly filteredLedgers = computed(() => {
    const q = this.directoryQuery().trim().toLowerCase();
    const list = this.coaStore.ledgers();
    if (!q) return list;
    return list.filter(
      (l) =>
        l.name.toLowerCase().includes(q) ||
        (l.aliasCode?.toLowerCase().includes(q) ?? false) ||
        (l.gstin?.toLowerCase().includes(q) ?? false) ||
        this.groupNameById(l.groupId, l.groupName).toLowerCase().includes(q),
    );
  });

  async ngOnInit(): Promise<void> {
    const scope = this.embeddedOverlay() ? 'MODAL' : 'SCREEN';
    const disposers = [
      this.shortcutRegistry.bindHandler(
        'screen.accept',
        scope,
        () => {
          void this.saveLedger();
        },
        () => !this.secondaryMasterModal.isOpen(),
      ),
      this.shortcutRegistry.bindHandler(
        'register.deleteVoucher',
        scope,
        () => {
          void this.deleteCurrentLedger();
        },
        () => Boolean(this.editingLedger() && !this.editingLedger()!.isSystemLedger),
      ),
      this.navStack.registerDraftLifecycleHooks(
        () => ({
          name: this.formName(),
          alias: this.formAlias(),
          groupId: this.formGroupId(),
          gstin: this.formGstin(),
          openingBalance: this.formOpeningBalance(),
          openingSide: this.formOpeningSide(),
        }),
        (snap) => {
          const s = snap as Record<string, unknown>;
          if (typeof s['name'] === 'string') this.formName.set(s['name']);
          if (typeof s['alias'] === 'string') this.formAlias.set(s['alias']);
          if (typeof s['groupId'] === 'string' || s['groupId'] === null) {
            this.formGroupId.set(s['groupId'] as string | null);
          }
          if (typeof s['gstin'] === 'string') this.formGstin.set(s['gstin']);
          if (typeof s['openingBalance'] === 'string') {
            this.formOpeningBalance.set(s['openingBalance']);
          }
          if (s['openingSide'] === 'DR' || s['openingSide'] === 'CR') {
            this.formOpeningSide.set(s['openingSide']);
          }
        },
      ),
    ];

    this.destroyRef.onDestroy(() => {
      for (const d of disposers) d();
      this.companyContext.setHasUnsavedDraft(false);
    });

    await this.coaStore.ensureAccountingMastersLoaded();

    const targetId = this.initialLedgerId() ?? this.route.snapshot.queryParamMap.get('id');
    if (targetId) {
      const found = this.coaStore.ledgers().find((l) => l.id === targetId);
      if (found) {
        this.selectLedgerForAlter(found);
        return;
      }
    }

    const mode = this.route.snapshot.queryParamMap.get('mode');
    if (mode === 'alter' && this.coaStore.ledgers().length > 0) {
      this.selectLedgerForAlter(this.coaStore.ledgers()[0]);
      return;
    }

    this.startCreateLedger();
  }

  groupNameById(groupId: string | null, fallback?: string | null): string {
    if (!groupId) {
      return fallback ?? 'Primary';
    }
    return this.coaStore.groups().find((g) => g.id === groupId)?.name ?? fallback ?? 'Primary';
  }

  markDirty(): void {
    this.companyContext.setHasUnsavedDraft(true);
  }

  onNameInput(val: string): void {
    const prevName = this.formName();
    this.formName.set(val);
    if (!this.formMailingName() || this.formMailingName() === prevName) {
      this.formMailingName.set(val);
    }
    this.markDirty();
  }

  onGroupSelected(opt: TypeaheadOption): void {
    this.formGroupId.set(opt.id);
    const flags = resolveGroupLineageFlags(opt.id, this.coaStore.groups());
    this.formOpeningSide.set(flags.defaultOpeningSide);

    if (flags.isPartyGroup) {
      this.formBillWise.set(true);
      if (this.formPartyRegType() === 'UNKNOWN') {
        this.formPartyRegType.set('REGULAR');
      }
      this.formTaxType.set('NONE');
    } else if (flags.isDutiesAndTaxes) {
      this.formBillWise.set(false);
      if (this.formTaxType() === 'NONE') {
        this.formTaxType.set('GST');
        this.formDutyHead.set('CGST');
      }
    } else if (flags.isSalesOrPurchase) {
      this.formBillWise.set(false);
      this.formInventoryAffected.set(true);
      this.formGstApplicability.set('APPLICABLE');
      this.formTaxType.set('NONE');
    } else {
      this.formBillWise.set(false);
      this.formTaxType.set('NONE');
    }

    this.markDirty();
  }

  onTaxTypeChange(next: LedgerTaxType): void {
    this.formTaxType.set(next);
    if (next === 'GST') {
      this.formDutyHead.set('CGST');
    } else if (next === 'TDS') {
      this.formDutyHead.set('TDS_SEC');
    } else if (next === 'TCS') {
      this.formDutyHead.set('TCS_SEC');
    } else {
      this.formDutyHead.set('OTHERS');
    }
    this.markDirty();
  }

  onAppropriateForChange(next: AppropriateForType): void {
    this.formAppropriateFor.set(next);
    if (next === 'BOTH_QTY') {
      this.formAppropriationBasis.set('BY_QTY');
    } else if (next === 'GST' || next === 'BOTH_VALUE') {
      this.formAppropriationBasis.set('BY_VALUE');
    } else {
      this.formAppropriationBasis.set(null);
    }
    this.markDirty();
  }

  onGstinInput(raw: string): void {
    const upper = raw.toUpperCase();
    this.formGstin.set(upper);
    if (upper.length >= 12) {
      const statePrefix = upper.slice(0, 2);
      if (INDIAN_GST_STATES.some((s) => s.code === statePrefix)) {
        this.formStateCode.set(statePrefix);
      }
      if (!this.formPan()) {
        this.formPan.set(upper.slice(2, 12));
      }
    }
    this.markDirty();
  }

  openInlineGroupCreator(seedText: string): void {
    void this.secondaryMasterModal.open({
      kind: 'GROUP',
      initialName: seedText,
      defaultParentGroupId: this.formGroupId(),
      onCreated: (created) => {
        this.onGroupSelected({ id: created.id, label: created.name });
      },
    });
  }

  startCreateLedger(): void {
    this.editingLedger.set(null);
    this.formError.set(null);
    this.formName.set('');
    this.formAlias.set('');

    const defaultGroup =
      this.coaStore.groups().find((g) => g.name === 'Sundry Debtors') ??
      this.coaStore.groups()[0] ??
      null;
    this.formGroupId.set(defaultGroup?.id ?? null);

    const flags = resolveGroupLineageFlags(defaultGroup?.id ?? null, this.coaStore.groups());
    this.formBillWise.set(flags.isPartyGroup);
    this.formCreditDays.set(null);
    this.formCheckCreditDays.set(false);
    this.formCreditLimit.set('0.00');
    this.formInventoryAffected.set(flags.isSalesOrPurchase);
    this.formCostCentresApplicable.set(false);
    this.formTaxType.set(flags.isDutiesAndTaxes ? 'GST' : 'NONE');
    this.formDutyHead.set('CGST');
    this.formGstApplicability.set(flags.isSalesOrPurchase ? 'APPLICABLE' : 'UNDEFINED');
    this.formGstSupplyType.set('GOODS');
    this.formHsnSac.set('');
    this.formGstRatePct.set('');
    this.formAppropriateFor.set('NONE');
    this.formAppropriationBasis.set(null);
    this.formRoundingMethod.set('NONE');
    this.formTdsApplicable.set(false);
    this.formTdsNatureCode.set('194J');
    this.formTdsDeducteeType.set('');
    this.formBankHolder.set('');
    this.formBankAccountNo.set('');
    this.formBankIfsc.set('');
    this.formBankName.set('');
    this.formBankBranch.set('');
    this.formMailingName.set('');
    this.formMailingAddress.set('');
    this.formCountryCode.set(this.companyContext.activeCompany()?.countryCode ?? 'IN');
    this.formStateCode.set(this.companyContext.activeCompany()?.stateCode ?? '33');
    this.formPincode.set('');
    this.formPartyRegType.set(flags.isPartyGroup ? 'REGULAR' : 'UNKNOWN');
    this.formGstin.set('');
    this.formPan.set('');
    this.formOpeningBalance.set('0.00');
    this.formOpeningSide.set(flags.defaultOpeningSide);
    this.companyContext.setHasUnsavedDraft(false);

    queueMicrotask(() => {
      this.nameInputRef()?.nativeElement.focus();
    });
  }

  selectLedgerForAlter(led: Ledger): void {
    this.editingLedger.set(led);
    this.formError.set(null);
    this.formName.set(led.name);
    this.formAlias.set(led.aliasCode ?? '');
    this.formGroupId.set(led.groupId);
    this.formBillWise.set(led.maintainBalancesBillByBill);
    this.formCreditDays.set(led.defaultCreditPeriodDays);
    this.formCheckCreditDays.set(led.checkCreditDaysDuringVoucher);
    this.formCreditLimit.set(led.creditLimit ?? '0.00');
    this.formInventoryAffected.set(led.inventoryValuesAffected);
    this.formCostCentresApplicable.set(led.costCentresApplicable);
    this.formTaxType.set(led.taxType);
    this.formDutyHead.set(led.dutyHead ?? 'CGST');
    this.formGstApplicability.set(led.gstApplicability);
    this.formGstSupplyType.set(led.gstSupplyType ?? 'GOODS');
    this.formHsnSac.set(led.hsnSacCode ?? '');
    this.formGstRatePct.set(led.gstRatePct ?? '');
    this.formAppropriateFor.set(led.appropriateFor);
    this.formAppropriationBasis.set(led.appropriationBasis);
    this.formRoundingMethod.set(led.roundingMethod);
    this.formTdsApplicable.set(led.tdsApplicable);
    this.formTdsNatureCode.set(led.tdsNatureCode ?? '194J');
    this.formTdsDeducteeType.set(led.tdsDeducteeType ?? '');
    this.formBankHolder.set(led.bankAccountHolder ?? '');
    this.formBankAccountNo.set('');
    this.formBankIfsc.set(led.bankIfscCode ?? '');
    this.formBankName.set(led.bankName ?? '');
    this.formBankBranch.set(led.bankBranch ?? '');
    this.formMailingName.set(led.mailingName ?? led.name);
    this.formMailingAddress.set(led.mailingAddress ?? '');
    this.formCountryCode.set(led.countryCode || 'IN');
    this.formStateCode.set(
      led.stateCode ?? this.companyContext.activeCompany()?.stateCode ?? '33',
    );
    this.formPincode.set(led.pincode ?? '');
    this.formPartyRegType.set(led.partyRegistrationType);
    this.formGstin.set(led.gstin ?? '');
    this.formPan.set('');
    this.formOpeningBalance.set(led.openingBalance || '0.00');
    this.formOpeningSide.set(led.openingSide);
    this.companyContext.setHasUnsavedDraft(false);
  }

  async saveLedger(): Promise<void> {
    if (this.saving()) return;
    this.formError.set(null);
    this.statusMessage.set(null);

    const name = this.formName().trim();
    const groupId = this.formGroupId();
    if (!name) {
      this.formError.set('Ledger Name is required.');
      return;
    }
    if (!groupId) {
      this.formError.set('Select a valid Parent Group ("Under") from the List of Groups.');
      return;
    }

    const cleanGstin = this.formGstin().trim().toUpperCase();
    if (cleanGstin.length > 0) {
      const check = this.gstinValidation();
      if (!check.valid) {
        this.formError.set(check.error ?? 'Invalid GSTIN');
        return;
      }
    }

    const flags = this.lineage();
    const taxType = this.formTaxType();
    const dutyHead =
      taxType === 'NONE'
        ? null
        : taxType === 'GST'
          ? this.formDutyHead()
          : taxType === 'TDS'
            ? 'TDS_SEC'
            : taxType === 'TCS'
              ? 'TCS_SEC'
              : 'OTHERS';

    const stateObj = INDIAN_GST_STATES.find((s) => s.code === this.formStateCode());
    const creditLimitClean =
      flags.isPartyGroup && !/^0+(\.0+)?$/.test(this.formCreditLimit().trim())
        ? this.formCreditLimit().trim()
        : null;

    const payload: CreateOrUpdateLedgerPayload = {
      name,
      aliasCode: this.formAlias().trim() || null,
      groupId,
      openingBalance: this.formOpeningBalance() || '0.00',
      openingSide: this.formOpeningSide(),
      currencyCode: this.companyContext.activeCompany()?.baseCurrencyCode ?? 'INR',
      maintainBalancesBillByBill: flags.isPartyGroup ? this.formBillWise() : false,
      defaultCreditPeriodDays: flags.isPartyGroup ? this.formCreditDays() : null,
      checkCreditDaysDuringVoucher: flags.isPartyGroup ? this.formCheckCreditDays() : false,
      creditLimit: creditLimitClean,
      inventoryValuesAffected: this.formInventoryAffected(),
      costCentresApplicable: this.formCostCentresApplicable(),
      taxType,
      dutyHead,
      gstApplicability: this.formGstApplicability(),
      gstSupplyType:
        this.formGstApplicability() === 'APPLICABLE' ? this.formGstSupplyType() : null,
      hsnSacCode: this.formHsnSac().trim() || null,
      gstRatePct: this.formGstRatePct().trim() || null,
      cessRatePct: null,
      appropriateFor: this.formAppropriateFor(),
      appropriationBasis: this.formAppropriationBasis(),
      roundingMethod: this.formRoundingMethod(),
      roundingLimit: this.formRoundingMethod() === 'NONE' ? null : '1.00',
      partyRegistrationType: this.formPartyRegType(),
      gstin: cleanGstin || null,
      countryCode: this.formCountryCode().trim() || 'IN',
      stateCode: this.formStateCode() || null,
      stateName: stateObj?.name ?? null,
      pincode: this.formPincode().trim() || null,
      mailingName: (this.formMailingName() || name).trim(),
      mailingAddress: this.formMailingAddress().trim() || null,
      pan: this.formPan().trim() || null,
      msmeRegistrationType: null,
      msmeUdyamNo: null,
      tdsApplicable: this.formTdsApplicable() || taxType === 'TDS',
      tdsNatureCode:
        this.formTdsApplicable() || taxType === 'TDS' || taxType === 'TCS'
          ? this.formTdsNatureCode()
          : null,
      tdsDeducteeType: this.formTdsDeducteeType() || null,
      bankAccountHolder: flags.isBankAccount ? this.formBankHolder().trim() || null : null,
      bankAccountNumber: flags.isBankAccount ? this.formBankAccountNo().trim() || null : null,
      bankIfscCode: flags.isBankAccount ? this.formBankIfsc().trim() || null : null,
      bankName: flags.isBankAccount ? this.formBankName().trim() || null : null,
      bankBranch: flags.isBankAccount ? this.formBankBranch().trim() || null : null,
      upiId: null,
      active: true,
    };

    this.saving.set(true);
    try {
      const current = this.editingLedger();
      if (current) {
        const updated = await this.coaStore.updateLedger(current.id, payload, current.versionNo);
        this.selectLedgerForAlter(updated);
        this.statusMessage.set(`Updated ledger "${updated.name}".`);
      } else {
        const created = await this.coaStore.createLedger(payload);
        this.startCreateLedger();
        this.statusMessage.set(`Created ledger "${created.name}".`);
      }
      this.companyContext.setHasUnsavedDraft(false);
    } catch (err: unknown) {
      this.formError.set(err instanceof Error ? err.message : 'Failed to save Ledger.');
    } finally {
      this.saving.set(false);
    }
  }

  async deleteCurrentLedger(): Promise<void> {
    const current = this.editingLedger();
    if (!current || current.isSystemLedger) {
      return;
    }
    this.formError.set(null);
    try {
      await this.coaStore.deleteLedger(current.id, current.versionNo);
      this.statusMessage.set(`Deleted ledger "${current.name}".`);
      this.startCreateLedger();
    } catch (err: unknown) {
      this.formError.set(err instanceof Error ? err.message : 'Unable to delete Ledger.');
    }
  }
}
