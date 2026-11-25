import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ActiveModalContextHandle,
  ShortcutCommandRegistry,
  asciiPair,
} from '../../core/keyboard/shortcut-command.registry';
import { INDIAN_GST_STATES, validateGstin } from '../../core/validation/gstin-validator';
import { AmountCellComponent } from '../../shared/components/amount-cell/amount-cell.component';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { SemanticIconComponent } from '../../shared/components/semantic-icon/semantic-icon.component';
import {
  TypeaheadOption,
  TypeaheadSelectComponent,
} from '../../shared/components/typeahead-select/typeahead-select.component';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import {
  GST_UQC_OPTIONS,
  STOCK_COSTING_METHOD_OPTIONS,
  StockCostingMethod,
  UomType,
} from '../inventory/inventory.models';
import { InventoryStore } from '../inventory/inventory.store';
import { ChartOfAccountsStore } from './chart-of-accounts.store';
import {
  AccountNature,
  BalanceSide,
  LedgerDutyHead,
  LedgerTaxType,
  PartyRegistrationType,
  TDS_DEDUCTEE_TYPES,
  TDS_NATURE_SECTIONS,
  resolveGroupLineageFlags,
} from './masters.models';
import { SecondaryMasterKind, SecondaryMasterModalService } from './secondary-master-modal.service';

@Component({
  selector: 'mc-secondary-master-modal',
  standalone: true,
  imports: [
    FormsModule,
    AmountCellComponent,
    CodeBadgeComponent,
    SemanticIconComponent,
    TypeaheadSelectComponent,
    GridCellNavDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (modalService.activeSession(); as session) {
      <div
        class="mc-modal-backdrop mc-secondary-modal-backdrop"
        role="dialog"
        aria-modal="true"
        [attr.aria-label]="session.title"
      >
        <form
          class="mc-modal-dialog mc-secondary-modal-dialog"
          data-mc-nav-container
          (ngSubmit)="saveActiveSecondaryMaster()"
        >
          <div class="mc-modal-header">
            <div class="mc-secondary-header-title">
              <span>{{ session.title }}</span>
              <span class="mc-badge mc-badge-jade">Stack {{ navStack.depth() }}/8</span>
            </div>
            <button
              type="button"
              class="mc-secondary-esc-btn"
              (click)="modalService.cancelActiveSession()"
            >
              Esc
            </button>
          </div>

          <div class="mc-modal-body mc-secondary-body">
            @if (formError(); as err) {
              <div class="mc-field-error mc-secondary-error-banner" role="alert">{{ err }}</div>
            }

            @switch (session.kind) {
              @case ('GROUP') {
                <div class="mc-sec-grid-2">
                  <div>
                    <label class="mc-field-label" for="sec-grp-name">Group Name</label>
                    <input
                      #primaryInput
                      id="sec-grp-name"
                      type="text"
                      name="secGroupName"
                      class="mc-input"
                      mcGridCellNav
                      [(ngModel)]="groupName"
                      required
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="sec-grp-alias">Alias</label>
                    <input
                      id="sec-grp-alias"
                      type="text"
                      name="secGroupAlias"
                      class="mc-input mc-code"
                      mcGridCellNav
                      [(ngModel)]="groupAlias"
                    />
                  </div>
                </div>

                <div class="mc-sec-grid-2">
                  <div>
                    <label class="mc-field-label" for="sec-grp-under">Under</label>
                    <mc-typeahead-select
                      inputId="sec-grp-under"
                      listTitle="List of Groups"
                      [options]="coaStore.parentGroupTypeaheadOptions()"
                      [selectedId]="groupParentId() ?? '__PRIMARY__'"
                      [allowInlineCreate]="false"
                      (optionSelected)="onSecGroupParentSelected($event)"
                    />
                  </div>

                  @if (groupParentId() === null) {
                    <div>
                      <label class="mc-field-label" for="sec-grp-nature">Nature of Group</label>
                      <select
                        id="sec-grp-nature"
                        name="secGroupNature"
                        class="mc-select"
                        mcGridCellNav
                        [(ngModel)]="groupNature"
                      >
                        <option value="ASSETS">Assets</option>
                        <option value="LIABILITIES">Liabilities</option>
                        <option value="INCOME">Incomes</option>
                        <option value="EXPENSES">Expenses</option>
                      </select>
                    </div>
                  }
                </div>

                <div class="mc-sec-toggles">
                  <label class="mc-sec-toggle">
                    <input
                      type="checkbox"
                      name="secSubledger"
                      [(ngModel)]="groupBehavesLikeSubledger"
                    />
                    <span>Group behaves like a sub-ledger</span>
                  </label>
                  <label class="mc-sec-toggle">
                    <input
                      type="checkbox"
                      name="secNetBalances"
                      [(ngModel)]="groupNetBalances"
                    />
                    <span>Nett Debit/Credit Balances for Reporting</span>
                  </label>
                </div>
              }

              @case ('LEDGER') {
                <div class="mc-sec-grid-2">
                  <div>
                    <label class="mc-field-label" for="sec-led-name">Name</label>
                    <input
                      #primaryInput
                      id="sec-led-name"
                      type="text"
                      name="secLedgerName"
                      class="mc-input"
                      mcGridCellNav
                      [(ngModel)]="ledgerName"
                      required
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="sec-led-alias">Alias</label>
                    <input
                      id="sec-led-alias"
                      type="text"
                      name="secLedgerAlias"
                      class="mc-input mc-code"
                      mcGridCellNav
                      [(ngModel)]="ledgerAlias"
                    />
                  </div>
                </div>

                <div class="mc-sec-grid-2">
                  <div>
                    <label class="mc-field-label" for="sec-led-under">
                      Under (<span class="mc-key-alt">C</span> to create Group)
                    </label>
                    <mc-typeahead-select
                      inputId="sec-led-under"
                      listTitle="List of Groups"
                      [options]="coaStore.groupTypeaheadOptions()"
                      [selectedId]="ledgerGroupId()"
                      [allowInlineCreate]="true"
                      (optionSelected)="onSecLedgerGroupSelected($event)"
                      (createRequested)="openNestedGroupCreation($event)"
                    />
                  </div>

                  <div>
                    <label class="mc-field-label" for="sec-led-opbal">Opening Balance</label>
                    <div class="mc-sec-opbal-row">
                      <mc-amount-cell
                        inputId="sec-led-opbal"
                        name="secLedgerOpBal"
                        [scale]="companyContext.currencyScale()"
                        [currencySymbol]="companyContext.currencySymbol()"
                        [(ngModel)]="ledgerOpeningBalance"
                      />
                      <select
                        name="secLedgerOpSide"
                        class="mc-select mc-side-select"
                        mcGridCellNav
                        [(ngModel)]="ledgerOpeningSide"
                        aria-label="Opening Balance Side"
                      >
                        <option value="DR">Dr</option>
                        <option value="CR">Cr</option>
                      </select>
                    </div>
                  </div>
                </div>

                @if (ledgerLineage().isPartyGroup) {
                  <div class="mc-sec-subcard">
                    <div class="mc-sec-subtitle">Party Bill-Wise &amp; GST Registration</div>
                    <label class="mc-sec-toggle">
                      <input
                        type="checkbox"
                        name="secBillWise"
                        [(ngModel)]="ledgerBillWise"
                      />
                      <span>Maintain balances bill-by-bill</span>
                    </label>
                    <div class="mc-sec-grid-3">
                      <div>
                        <label class="mc-field-label" for="sec-led-state">State</label>
                        <select
                          id="sec-led-state"
                          name="secLedgerState"
                          class="mc-select"
                          mcGridCellNav
                          [ngModel]="ledgerStateCode()"
                          (ngModelChange)="ledgerStateCode.set($event)"
                        >
                          @for (st of states; track st.code) {
                            <option [value]="st.code">{{ st.code }} - {{ st.name }}</option>
                          }
                        </select>
                      </div>
                      <div>
                        <label class="mc-field-label" for="sec-led-regtype">Registration Type</label>
                        <select
                          id="sec-led-regtype"
                          name="secLedgerRegType"
                          class="mc-select"
                          mcGridCellNav
                          [(ngModel)]="ledgerRegType"
                        >
                          <option value="REGULAR">Regular</option>
                          <option value="COMPOSITION">Composition</option>
                          <option value="UNREGISTERED">Unregistered</option>
                          <option value="CONSUMER">Consumer</option>
                          <option value="SEZ">SEZ</option>
                          <option value="OVERSEAS">Overseas</option>
                        </select>
                      </div>
                      <div>
                        <label class="mc-field-label" for="sec-led-gstin">GSTIN / UIN</label>
                        <input
                          id="sec-led-gstin"
                          type="text"
                          maxlength="15"
                          name="secLedgerGstin"
                          class="mc-input mc-code"
                          mcGridCellNav
                          [ngModel]="ledgerGstin()"
                          (ngModelChange)="ledgerGstin.set($event)"
                          [attr.aria-invalid]="ledgerGstin().trim().length > 0 && !secGstinCheck().valid"
                          placeholder="33AABCS9012H1ZM"
                        />
                      </div>
                    </div>
                    @if (ledgerGstin().trim().length > 0 && !secGstinCheck().valid) {
                      <div class="mc-field-error" role="alert">{{ secGstinCheck().error }}</div>
                    }
                    <div class="mc-sec-grid-2">
                      <div>
                        <label class="mc-field-label" for="sec-led-pan">PAN / IT No.</label>
                        <input
                          id="sec-led-pan"
                          type="text"
                          maxlength="10"
                          name="secLedgerPan"
                          class="mc-input mc-code"
                          mcGridCellNav
                          [(ngModel)]="ledgerPan"
                          placeholder="AABFA4321K"
                        />
                      </div>
                      <div>
                        <label class="mc-field-label" for="sec-led-deductee">TDS Deductee Type</label>
                        <select
                          id="sec-led-deductee"
                          name="secLedgerDeductee"
                          class="mc-select"
                          mcGridCellNav
                          [(ngModel)]="ledgerDeducteeType"
                        >
                          <option value="">Not Specified</option>
                          @for (dt of deducteeTypes; track dt.code) {
                            <option [value]="dt.code">{{ dt.label }}</option>
                          }
                        </select>
                      </div>
                    </div>
                  </div>
                }

                @if (ledgerLineage().isDutiesAndTaxes) {
                  <div class="mc-sec-subcard">
                    <div class="mc-sec-subtitle">Statutory Tax / Duty Configuration</div>
                    <div class="mc-sec-grid-2">
                      <div>
                        <label class="mc-field-label" for="sec-led-taxtype">Type of Duty/Tax</label>
                        <select
                          id="sec-led-taxtype"
                          name="secLedgerTaxType"
                          class="mc-select"
                          mcGridCellNav
                          [ngModel]="ledgerTaxType()"
                          (ngModelChange)="onSecTaxTypeChange($event)"
                        >
                          <option value="GST">GST</option>
                          <option value="TDS">TDS</option>
                          <option value="TCS">TCS</option>
                          <option value="OTHERS">Others</option>
                        </select>
                      </div>
                      @if (ledgerTaxType() === 'GST') {
                        <div>
                          <label class="mc-field-label" for="sec-led-dutyhead">Tax Type (Duty Head)</label>
                          <select
                            id="sec-led-dutyhead"
                            name="secLedgerDutyHead"
                            class="mc-select"
                            mcGridCellNav
                            [(ngModel)]="ledgerDutyHead"
                          >
                            <option value="CGST">CGST</option>
                            <option value="SGST_UTGST">SGST / UTGST</option>
                            <option value="IGST">IGST</option>
                            <option value="CESS">Cess</option>
                          </select>
                        </div>
                      }
                      @if (ledgerTaxType() === 'TDS' || ledgerTaxType() === 'TCS') {
                        <div>
                          <label class="mc-field-label" for="sec-led-tdssec">Nature of Payment</label>
                          <select
                            id="sec-led-tdssec"
                            name="secLedgerTdsNature"
                            class="mc-select"
                            mcGridCellNav
                            [(ngModel)]="ledgerTdsNatureCode"
                          >
                            @for (sec of tdsSections; track sec.section) {
                              <option [value]="sec.section">{{ sec.name }}</option>
                            }
                          </select>
                        </div>
                      }
                    </div>
                  </div>
                }
              }

              @case ('COST_CENTRE') {
                <div class="mc-sec-grid-2">
                  <div>
                    <label class="mc-field-label" for="sec-cc-name">Cost Centre Name</label>
                    <input
                      #primaryInput
                      id="sec-cc-name"
                      type="text"
                      name="secCcName"
                      class="mc-input"
                      mcGridCellNav
                      [(ngModel)]="costCentreName"
                      required
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="sec-cc-alias">Alias</label>
                    <input
                      id="sec-cc-alias"
                      type="text"
                      name="secCcAlias"
                      class="mc-input mc-code"
                      mcGridCellNav
                      [(ngModel)]="costCentreAlias"
                    />
                  </div>
                </div>
                <div class="mc-sec-grid-2">
                  <div>
                    <label class="mc-field-label" for="sec-cc-cat">Category</label>
                    <mc-typeahead-select
                      inputId="sec-cc-cat"
                      listTitle="List of Cost Categories"
                      [options]="coaStore.costCategoryTypeaheadOptions()"
                      [selectedId]="costCentreCategoryId()"
                      [allowInlineCreate]="false"
                      (optionSelected)="costCentreCategoryId.set($event.id)"
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="sec-cc-under">Under</label>
                    <mc-typeahead-select
                      inputId="sec-cc-under"
                      listTitle="List of Cost Centres"
                      [options]="coaStore.costCentreTypeaheadOptions()"
                      [selectedId]="costCentreParentId() ?? '__PRIMARY__'"
                      [allowInlineCreate]="false"
                      (optionSelected)="costCentreParentId.set($event.id === '__PRIMARY__' ? null : $event.id)"
                    />
                  </div>
                </div>
              }

              @case ('CURRENCY') {
                <div class="mc-sec-grid-3">
                  <div>
                    <label class="mc-field-label" for="sec-cur-iso">ISO Code (3 chars)</label>
                    <input
                      #primaryInput
                      id="sec-cur-iso"
                      type="text"
                      maxlength="3"
                      name="secCurIso"
                      class="mc-input mc-code"
                      mcGridCellNav
                      [(ngModel)]="currencyIsoCode"
                      placeholder="USD"
                      required
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="sec-cur-sym">Symbol</label>
                    <input
                      id="sec-cur-sym"
                      type="text"
                      name="secCurSym"
                      class="mc-input"
                      mcGridCellNav
                      [(ngModel)]="currencySymbol"
                      placeholder="$"
                      required
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="sec-cur-dec">Decimal Places</label>
                    <input
                      id="sec-cur-dec"
                      type="number"
                      min="0"
                      max="6"
                      name="secCurDec"
                      class="mc-input"
                      mcGridCellNav
                      [(ngModel)]="currencyDecimalPlaces"
                    />
                  </div>
                </div>
                <div>
                  <label class="mc-field-label" for="sec-cur-formal">Formal Name</label>
                  <input
                    id="sec-cur-formal"
                    type="text"
                    name="secCurFormal"
                    class="mc-input"
                    mcGridCellNav
                    [(ngModel)]="currencyFormalName"
                    placeholder="US Dollar"
                    required
                  />
                </div>
              }

              @case ('UOM') {
                <div class="mc-sec-grid-3">
                  <div>
                    <label class="mc-field-label" for="sec-uom-type">Type</label>
                    <select
                      id="sec-uom-type"
                      name="secUomType"
                      class="mc-select"
                      mcGridCellNav
                      [(ngModel)]="uomType"
                    >
                      <option value="SIMPLE">Simple</option>
                      <option value="COMPOUND">Compound</option>
                    </select>
                  </div>
                  <div>
                    <label class="mc-field-label" for="sec-uom-sym">Symbol</label>
                    <input
                      #primaryInput
                      id="sec-uom-sym"
                      type="text"
                      name="secUomSymbol"
                      class="mc-input mc-code"
                      mcGridCellNav
                      [(ngModel)]="uomSymbol"
                      placeholder="NOS"
                      required
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="sec-uom-dec">Number of Decimal Places</label>
                    <input
                      id="sec-uom-dec"
                      type="number"
                      min="0"
                      max="6"
                      name="secUomDecimals"
                      class="mc-input"
                      mcGridCellNav
                      [(ngModel)]="uomDecimalPlaces"
                    />
                  </div>
                </div>

                @if (uomType === 'SIMPLE') {
                  <div class="mc-sec-grid-2">
                    <div>
                      <label class="mc-field-label" for="sec-uom-formal">Formal Name</label>
                      <input
                        id="sec-uom-formal"
                        type="text"
                        name="secUomFormal"
                        class="mc-input"
                        mcGridCellNav
                        [(ngModel)]="uomFormalName"
                        placeholder="Numbers"
                      />
                    </div>
                    <div>
                      <label class="mc-field-label" for="sec-uom-uqc">Unit Quantity Code (UQC)</label>
                      <select
                        id="sec-uom-uqc"
                        name="secUomUqc"
                        class="mc-select mc-code"
                        mcGridCellNav
                        [(ngModel)]="uomGstUqcCode"
                      >
                        @for (uqc of uqcOptions; track uqc.code) {
                          <option [value]="uqc.code">{{ uqc.description }}</option>
                        }
                      </select>
                    </div>
                  </div>
                } @else {
                  <div class="mc-sec-grid-3">
                    <div>
                      <label class="mc-field-label" for="sec-uom-first">First Unit</label>
                      <select
                        id="sec-uom-first"
                        name="secUomFirst"
                        class="mc-select"
                        mcGridCellNav
                        [(ngModel)]="uomFirstId"
                      >
                        @for (u of inventoryStore.uoms(); track u.id) {
                          <option [value]="u.id">{{ u.symbol }}</option>
                        }
                      </select>
                    </div>
                    <div>
                      <label class="mc-field-label" for="sec-uom-conv">Conversion (of)</label>
                      <input
                        id="sec-uom-conv"
                        type="text"
                        name="secUomConv"
                        class="mc-input mc-amount"
                        mcGridCellNav
                        [(ngModel)]="uomConversionFactor"
                        placeholder="10"
                      />
                    </div>
                    <div>
                      <label class="mc-field-label" for="sec-uom-second">Second Unit</label>
                      <select
                        id="sec-uom-second"
                        name="secUomSecond"
                        class="mc-select"
                        mcGridCellNav
                        [(ngModel)]="uomSecondId"
                      >
                        @for (u of inventoryStore.uoms(); track u.id) {
                          <option [value]="u.id">{{ u.symbol }}</option>
                        }
                      </select>
                    </div>
                  </div>
                }
              }

              @case ('GODOWN') {
                <div class="mc-sec-grid-2">
                  <div>
                    <label class="mc-field-label" for="sec-gd-name">Godown / Location Name</label>
                    <input
                      #primaryInput
                      id="sec-gd-name"
                      type="text"
                      name="secGodownName"
                      class="mc-input"
                      mcGridCellNav
                      [(ngModel)]="godownName"
                      required
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="sec-gd-alias">Alias</label>
                    <input
                      id="sec-gd-alias"
                      type="text"
                      name="secGodownAlias"
                      class="mc-input mc-code"
                      mcGridCellNav
                      [(ngModel)]="godownAlias"
                    />
                  </div>
                </div>
                <div>
                  <label class="mc-field-label" for="sec-gd-under">Under</label>
                  <mc-typeahead-select
                    inputId="sec-gd-under"
                    listTitle="List of Godowns"
                    [options]="inventoryStore.parentGodownOptions()"
                    [selectedId]="godownParentId() ?? '__PRIMARY__'"
                    [allowInlineCreate]="false"
                    (optionSelected)="godownParentId.set($event.id === '__PRIMARY__' ? null : $event.id)"
                  />
                </div>
              }

              @case ('STOCK_GROUP') {
                <div class="mc-sec-grid-2">
                  <div>
                    <label class="mc-field-label" for="sec-sg-name">Stock Group Name</label>
                    <input
                      #primaryInput
                      id="sec-sg-name"
                      type="text"
                      name="secSgName"
                      class="mc-input"
                      mcGridCellNav
                      [(ngModel)]="stockGroupName"
                      required
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="sec-sg-under">Under</label>
                    <mc-typeahead-select
                      inputId="sec-sg-under"
                      listTitle="List of Stock Groups"
                      [options]="inventoryStore.parentStockGroupOptions()"
                      [selectedId]="stockGroupParentId() ?? '__PRIMARY__'"
                      [allowInlineCreate]="false"
                      (optionSelected)="stockGroupParentId.set($event.id === '__PRIMARY__' ? null : $event.id)"
                    />
                  </div>
                </div>
                <div class="mc-sec-grid-3">
                  <label class="mc-sec-toggle">
                    <input
                      type="checkbox"
                      name="secSgAddQty"
                      [(ngModel)]="stockGroupAddQuantities"
                    />
                    <span>Should quantities of items be added</span>
                  </label>
                  <div>
                    <label class="mc-field-label" for="sec-sg-hsn">HSN / SAC Code</label>
                    <input
                      id="sec-sg-hsn"
                      type="text"
                      name="secSgHsn"
                      class="mc-input mc-code"
                      mcGridCellNav
                      [(ngModel)]="stockGroupHsn"
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="sec-sg-gst">GST Rate (%)</label>
                    <input
                      id="sec-sg-gst"
                      type="text"
                      name="secSgGst"
                      class="mc-input mc-amount"
                      mcGridCellNav
                      [(ngModel)]="stockGroupGstRate"
                    />
                  </div>
                </div>
              }

              @case ('STOCK_ITEM') {
                <div class="mc-sec-grid-3">
                  <div>
                    <label class="mc-field-label" for="sec-si-name">Name</label>
                    <input
                      #primaryInput
                      id="sec-si-name"
                      type="text"
                      name="secSiName"
                      class="mc-input"
                      mcGridCellNav
                      [(ngModel)]="stockItemName"
                      required
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="sec-si-part">Part No.</label>
                    <input
                      id="sec-si-part"
                      type="text"
                      name="secSiPart"
                      class="mc-input mc-code"
                      mcGridCellNav
                      [(ngModel)]="stockItemPartNo"
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="sec-si-cost">Costing Method</label>
                    <select
                      id="sec-si-cost"
                      name="secSiCosting"
                      class="mc-select"
                      mcGridCellNav
                      [(ngModel)]="stockItemCostingMethod"
                    >
                      @for (cm of costingMethods; track cm.value) {
                        <option [value]="cm.value">{{ cm.label }}</option>
                      }
                    </select>
                  </div>
                </div>

                <div class="mc-sec-grid-2">
                  <div>
                    <label class="mc-field-label" for="sec-si-under">
                      Under (<span class="mc-key-alt">C</span> = Create Stock Group)
                    </label>
                    <mc-typeahead-select
                      inputId="sec-si-under"
                      listTitle="List of Stock Groups"
                      [options]="inventoryStore.parentStockGroupOptions()"
                      [selectedId]="stockItemGroupId() ?? '__PRIMARY__'"
                      [allowInlineCreate]="true"
                      (optionSelected)="stockItemGroupId.set($event.id === '__PRIMARY__' ? null : $event.id)"
                      (createRequested)="openNestedStockGroupCreation($event)"
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="sec-si-uom">
                      Units (<span class="mc-key-alt">C</span> = Create Unit)
                    </label>
                    <mc-typeahead-select
                      inputId="sec-si-uom"
                      listTitle="List of Units"
                      [options]="inventoryStore.uomOptions()"
                      [selectedId]="stockItemBaseUomId()"
                      [allowInlineCreate]="true"
                      (optionSelected)="stockItemBaseUomId.set($event.id)"
                      (createRequested)="openNestedUomCreation($event)"
                    />
                  </div>
                </div>

                <div class="mc-sec-grid-3">
                  <div>
                    <label class="mc-field-label" for="sec-si-hsn">HSN / SAC</label>
                    <input
                      id="sec-si-hsn"
                      type="text"
                      name="secSiHsn"
                      class="mc-input mc-code"
                      mcGridCellNav
                      [(ngModel)]="stockItemHsn"
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="sec-si-gst">GST Rate (%)</label>
                    <input
                      id="sec-si-gst"
                      type="text"
                      name="secSiGst"
                      class="mc-input mc-amount"
                      mcGridCellNav
                      [(ngModel)]="stockItemGstRate"
                    />
                  </div>
                  <label class="mc-sec-toggle">
                    <input
                      type="checkbox"
                      name="secSiBatches"
                      [(ngModel)]="stockItemBatches"
                    />
                    <span>Maintain in batches</span>
                  </label>
                </div>
              }
            }
          </div>

          <div class="mc-modal-footer">
            <span class="mc-sec-footer-hint">
              Enter = Next Field &bull; <mc-code-badge code="Ctrl+A" /> = Accept &amp; Return to Calling Field
            </span>
            <div class="mc-sec-footer-btns">
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                data-testid="sec-master-cancel-btn"
                (click)="modalService.cancelActiveSession()"
              >
                <mc-icon name="close" [size]="16" />
                <span>Cancel</span>
                @if (actionHint('editor.close', 'C/c'); as cHint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ cHint }}]</span>
                }
              </button>
              <button
                id="sec-master-accept"
                type="button"
                class="mc-btn mc-btn-primary"
                [disabled]="saving() || companyContext.quarantined()"
                (click)="saveActiveSecondaryMaster()"
              >
                <mc-icon name="save-document" [size]="16" />
                <span>Save (<span class="mc-key-ctrl">A</span>)</span>
                @if (actionHint('ledger.save', 'S/s'); as sHint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ sHint }}]</span>
                }
              </button>
            </div>
          </div>
        </form>
      </div>
    }
  `,
  styles: `
    .mc-secondary-modal-backdrop {
      z-index: var(--mc-z-palette);
      padding-top: 7vh;
    }

    .mc-secondary-modal-dialog {
      width: min(640px, 94vw);
    }

    .mc-secondary-header-title {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-secondary-esc-btn {
      background: rgba(255, 255, 255, 0.14);
      border: 1px solid rgba(255, 255, 255, 0.3);
      color: var(--mc-color-surface);
      border-radius: var(--mc-radius-xs);
      padding: 0.1rem 0.45rem;
      font-size: var(--mc-font-size-xs);
      cursor: pointer;
    }

    .mc-secondary-body {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-secondary-error-banner {
      padding: var(--mc-space-2);
      background: var(--mc-color-danger-50);
      border-left: 3px solid var(--mc-color-danger-600);
    }

    .mc-sec-grid-2 {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    .mc-sec-grid-3 {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--mc-space-2);
    }

    @media (max-width: 720px) {
      .mc-sec-grid-2,
      .mc-sec-grid-3 {
        grid-template-columns: 1fr;
      }
    }

    .mc-sec-opbal-row {
      display: grid;
      grid-template-columns: 1fr 72px;
      gap: 6px;
    }

    .mc-side-select {
      font-weight: 700;
    }

    .mc-sec-subcard {
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-sec-subtitle {
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      color: var(--mc-color-slate-700);
      text-transform: uppercase;
      letter-spacing: 0.03em;
    }

    .mc-sec-toggles {
      display: flex;
      flex-wrap: wrap;
      gap: var(--mc-space-4);
    }

    .mc-sec-toggle {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      font-weight: 500;
      cursor: pointer;
    }

    .mc-sec-footer-hint {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }

    .mc-sec-footer-btns {
      display: flex;
      gap: var(--mc-space-2);
    }
  `,
})
export class SecondaryMasterModalComponent {
  readonly modalService = inject(SecondaryMasterModalService);
  readonly coaStore = inject(ChartOfAccountsStore);
  readonly inventoryStore = inject(InventoryStore);
  readonly companyContext = inject(CompanyContextStore);
  readonly navStack = inject(NavigationStackStore);

  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly destroyRef = inject(DestroyRef);

  private readonly primaryInputRef = viewChild<ElementRef<HTMLInputElement>>('primaryInput');

  readonly states = INDIAN_GST_STATES;
  readonly tdsSections = TDS_NATURE_SECTIONS;
  readonly deducteeTypes = TDS_DEDUCTEE_TYPES;
  readonly uqcOptions = GST_UQC_OPTIONS;
  readonly costingMethods = STOCK_COSTING_METHOD_OPTIONS;

  readonly saving = signal<boolean>(false);
  readonly formError = signal<string | null>(null);

  // Group fields
  groupName = '';
  groupAlias = '';
  readonly groupParentId = signal<string | null>(null);
  groupNature: AccountNature = 'ASSETS';
  groupBehavesLikeSubledger = false;
  groupNetBalances = false;

  // Ledger fields
  ledgerName = '';
  ledgerAlias = '';
  readonly ledgerGroupId = signal<string | null>(null);
  ledgerOpeningBalance = '0.00';
  ledgerOpeningSide: BalanceSide = 'DR';
  ledgerBillWise = false;
  readonly ledgerStateCode = signal<string>('33');
  ledgerRegType: PartyRegistrationType = 'REGULAR';
  readonly ledgerGstin = signal<string>('');
  ledgerPan = '';
  ledgerDeducteeType = '';
  readonly ledgerTaxType = signal<LedgerTaxType>('GST');
  ledgerDutyHead: LedgerDutyHead = 'CGST';
  ledgerTdsNatureCode = '194J';

  // Cost Centre fields
  costCentreName = '';
  costCentreAlias = '';
  readonly costCentreCategoryId = signal<string | null>(null);
  readonly costCentreParentId = signal<string | null>(null);

  // Currency fields
  currencyIsoCode = '';
  currencySymbol = '$';
  currencyFormalName = '';
  currencyDecimalPlaces = 2;

  // UOM fields
  uomType: UomType = 'SIMPLE';
  uomSymbol = '';
  uomFormalName = '';
  uomGstUqcCode = 'NOS';
  uomDecimalPlaces = 0;
  uomFirstId = '';
  uomConversionFactor = '10';
  uomSecondId = '';

  // Godown fields
  godownName = '';
  godownAlias = '';
  readonly godownParentId = signal<string | null>(null);

  // Stock Group fields
  stockGroupName = '';
  readonly stockGroupParentId = signal<string | null>(null);
  stockGroupAddQuantities = true;
  stockGroupHsn = '';
  stockGroupGstRate = '';

  // Stock Item fields
  stockItemName = '';
  stockItemPartNo = '';
  stockItemCostingMethod: StockCostingMethod = 'AVG_COST';
  readonly stockItemGroupId = signal<string | null>(null);
  readonly stockItemBaseUomId = signal<string | null>(null);
  stockItemHsn = '';
  stockItemGstRate = '';
  stockItemBatches = false;

  private readonly hydratedSessionIds = new Set<string>();
  private modalHandle: ActiveModalContextHandle | null = null;
  private activeModalSessionId: string | null = null;

  readonly ledgerLineage = computed(() =>
    resolveGroupLineageFlags(this.ledgerGroupId(), this.coaStore.groups()),
  );

  readonly secGstinCheck = computed(() =>
    validateGstin(this.ledgerGstin(), this.ledgerStateCode()),
  );

  actionHint(commandId: string, pairSpec: 'S/s' | 'C/c'): string | null {
    return this.shortcutRegistry.effectiveActionHint(
      'ledger.create',
      commandId,
      asciiPair(pairSpec),
    );
  }

  constructor() {
    const disposeAccept = this.shortcutRegistry.bindHandler(
      'screen.accept',
      'MODAL',
      () => {
        void this.saveActiveSecondaryMaster();
      },
      () => this.modalService.isOpen(),
    );
    this.destroyRef.onDestroy(() => {
      disposeAccept();
      this.releaseModalContext();
    });

    effect(() => {
      const session = this.modalService.activeSession();
      if (!session) {
        this.hydratedSessionIds.clear();
        this.releaseModalContext();
        return;
      }
      if (this.activeModalSessionId !== session.sessionId) {
        this.releaseModalContext();
        this.activeModalSessionId = session.sessionId;
        this.modalHandle = this.shortcutRegistry.openModalContext(
          [
            {
              contextId: 'ledger.create',
              locale: 'en-GB',
              commandId: 'ledger.save',
              label: 'Save',
              letterPair: asciiPair('S/s'),
              enabledWhen: () => !this.saving() && !this.companyContext.quarantined(),
              permissionRef: 'master.create',
              effect: 'Persist valid secondary master and return permitted selection to originating cell.',
              normalActivation: () => this.saveActiveSecondaryMaster(),
              iconSemanticId: 'mc.icon.save-document',
              stateTokenRole: 'action.primary',
              isMutation: true,
            },
            {
              contextId: 'ledger.create',
              locale: 'en-GB',
              commandId: 'editor.close',
              label: 'Cancel',
              letterPair: asciiPair('C/c'),
              permissionRef: 'authenticated',
              effect: 'Discard unsaved secondary master form and return to originating cell.',
              normalActivation: () => this.modalService.cancelActiveSession(),
              iconSemanticId: 'mc.icon.close',
              stateTokenRole: 'action.secondary',
            },
          ],
          'en-GB',
        );
      }
      if (!this.hydratedSessionIds.has(session.sessionId)) {
        this.hydratedSessionIds.add(session.sessionId);
        this.formError.set(null);
        this.hydrateSessionDefaults(session.kind, session.initialName);
      }

      queueMicrotask(() => {
        const el = this.primaryInputRef()?.nativeElement;
        if (el) {
          el.focus();
          el.select();
        }
      });
    });
  }

  private releaseModalContext(): void {
    if (this.modalHandle) {
      this.modalHandle.close();
      this.modalHandle = null;
    }
    this.activeModalSessionId = null;
  }

  onSecGroupParentSelected(opt: TypeaheadOption): void {
    if (opt.id === '__PRIMARY__') {
      this.groupParentId.set(null);
      return;
    }
    this.groupParentId.set(opt.id);
    const parent = this.coaStore.groups().find((g) => g.id === opt.id);
    if (parent) {
      this.groupNature = parent.nature;
    }
  }

  onSecLedgerGroupSelected(opt: TypeaheadOption): void {
    this.ledgerGroupId.set(opt.id);
    const flags = resolveGroupLineageFlags(opt.id, this.coaStore.groups());
    this.ledgerOpeningSide = flags.defaultOpeningSide;
    this.ledgerBillWise = flags.isPartyGroup;
    if (flags.isDutiesAndTaxes) {
      this.ledgerTaxType.set('GST');
      this.ledgerDutyHead = 'CGST';
    }
  }

  onSecTaxTypeChange(nextType: LedgerTaxType): void {
    this.ledgerTaxType.set(nextType);
    if (nextType === 'GST') {
      this.ledgerDutyHead = 'CGST';
    } else if (nextType === 'TDS') {
      this.ledgerDutyHead = 'TDS_SEC';
    } else if (nextType === 'TCS') {
      this.ledgerDutyHead = 'TCS_SEC';
    } else {
      this.ledgerDutyHead = 'OTHERS';
    }
  }

  openNestedGroupCreation(seedText: string): void {
    void this.modalService.open({
      kind: 'GROUP',
      initialName: seedText,
      defaultParentGroupId: this.ledgerGroupId(),
      onCreated: (created) => {
        this.onSecLedgerGroupSelected({ id: created.id, label: created.name });
      },
    });
  }

  openNestedStockGroupCreation(seedText: string): void {
    void this.modalService.open({
      kind: 'STOCK_GROUP',
      initialName: seedText,
      onCreated: (created) => {
        this.stockItemGroupId.set(created.id);
      },
    });
  }

  openNestedUomCreation(seedText: string): void {
    void this.modalService.open({
      kind: 'UOM',
      initialName: seedText,
      onCreated: (created) => {
        this.stockItemBaseUomId.set(created.id);
      },
    });
  }

  async saveActiveSecondaryMaster(): Promise<void> {
    const session = this.modalService.activeSession();
    if (!session || this.saving()) {
      return;
    }

    this.formError.set(null);
    this.saving.set(true);

    try {
      switch (session.kind) {
        case 'GROUP': {
          const name = this.groupName.trim();
          if (!name) {
            this.formError.set('Group Name is required.');
            return;
          }
          const parent = this.groupParentId()
            ? this.coaStore.groups().find((g) => g.id === this.groupParentId())
            : undefined;
          const created = await this.coaStore.createGroup({
            name,
            aliasCode: this.groupAlias.trim() || null,
            parentGroupId: this.groupParentId(),
            nature: parent ? parent.nature : this.groupNature,
            affectsGrossProfit: parent ? parent.affectsGrossProfit : false,
            behavesLikeSubledger: this.groupBehavesLikeSubledger,
            netDebitCreditForReporting: this.groupNetBalances,
            usedForCalculation: false,
            methodToAllocatePurchase: 'NOT_APPLICABLE',
            defaultGstRatePct: null,
            defaultHsnSacCode: null,
          });
          this.modalService.completeActiveSession({
            id: created.id,
            name: created.name,
            kind: 'GROUP',
          });
          return;
        }

        case 'LEDGER': {
          const name = this.ledgerName.trim();
          const groupId = this.ledgerGroupId();
          if (!name) {
            this.formError.set('Ledger Name is required.');
            return;
          }
          if (!groupId) {
            this.formError.set('Select a valid Parent Group ("Under").');
            return;
          }
          const flags = this.ledgerLineage();
          const gstinClean = this.ledgerGstin().trim().toUpperCase();
          if (flags.isPartyGroup && gstinClean.length > 0) {
            const check = this.secGstinCheck();
            if (!check.valid) {
              this.formError.set(check.error ?? 'Invalid GSTIN');
              return;
            }
          }
          const stateInfo = INDIAN_GST_STATES.find((s) => s.code === this.ledgerStateCode());
          const created = await this.coaStore.createLedger({
            name,
            aliasCode: this.ledgerAlias.trim() || null,
            groupId,
            openingBalance: this.ledgerOpeningBalance || '0.00',
            openingSide: this.ledgerOpeningSide,
            currencyCode: this.companyContext.activeCompany()?.baseCurrencyCode ?? 'INR',
            maintainBalancesBillByBill: flags.isPartyGroup ? this.ledgerBillWise : false,
            defaultCreditPeriodDays: null,
            checkCreditDaysDuringVoucher: false,
            creditLimit: null,
            inventoryValuesAffected: flags.isSalesOrPurchase,
            costCentresApplicable: false,
            taxType: flags.isDutiesAndTaxes ? this.ledgerTaxType() : 'NONE',
            dutyHead: flags.isDutiesAndTaxes ? this.ledgerDutyHead : null,
            gstApplicability: flags.isSalesOrPurchase ? 'APPLICABLE' : 'UNDEFINED',
            gstSupplyType: flags.isSalesOrPurchase ? 'GOODS' : null,
            hsnSacCode: null,
            gstRatePct: null,
            cessRatePct: null,
            appropriateFor: 'NONE',
            appropriationBasis: null,
            roundingMethod: 'NONE',
            roundingLimit: null,
            partyRegistrationType: flags.isPartyGroup ? this.ledgerRegType : 'UNKNOWN',
            gstin: flags.isPartyGroup && gstinClean ? gstinClean : null,
            countryCode: this.companyContext.activeCompany()?.countryCode ?? 'IN',
            stateCode: flags.isPartyGroup ? this.ledgerStateCode() : null,
            stateName: flags.isPartyGroup ? (stateInfo?.name ?? null) : null,
            pincode: null,
            mailingName: name,
            mailingAddress: null,
            pan: flags.isPartyGroup
              ? this.ledgerPan.trim().toUpperCase() ||
                (gstinClean.length === 15 ? gstinClean.slice(2, 12) : null)
              : null,
            msmeRegistrationType: null,
            msmeUdyamNo: null,
            tdsApplicable: flags.isDutiesAndTaxes && this.ledgerTaxType() === 'TDS',
            tdsNatureCode:
              flags.isDutiesAndTaxes &&
              (this.ledgerTaxType() === 'TDS' || this.ledgerTaxType() === 'TCS')
                ? this.ledgerTdsNatureCode
                : null,
            tdsDeducteeType: flags.isPartyGroup ? this.ledgerDeducteeType || null : null,
            bankAccountHolder: null,
            bankAccountNumber: null,
            bankIfscCode: null,
            bankName: null,
            bankBranch: null,
            upiId: null,
            active: true,
          });
          this.modalService.completeActiveSession({
            id: created.id,
            name: created.name,
            kind: 'LEDGER',
          });
          return;
        }

        case 'COST_CENTRE': {
          const name = this.costCentreName.trim();
          const categoryId = this.costCentreCategoryId();
          if (!name || !categoryId) {
            this.formError.set('Cost Centre Name and Cost Category are required.');
            return;
          }
          const created = await this.coaStore.createCostCentre({
            costCategoryId: categoryId,
            parentCostCentreId: this.costCentreParentId(),
            name,
            aliasCode: this.costCentreAlias.trim() || null,
            active: true,
          });
          this.modalService.completeActiveSession({
            id: created.id,
            name: created.name,
            kind: 'COST_CENTRE',
          });
          return;
        }

        case 'CURRENCY': {
          const iso = this.currencyIsoCode.trim().toUpperCase();
          const formal = this.currencyFormalName.trim();
          if (iso.length !== 3 || !formal) {
            this.formError.set('3-letter ISO Code and Formal Name are required.');
            return;
          }
          const created = await this.coaStore.createCurrency({
            isoCode: iso,
            symbol: this.currencySymbol.trim() || iso,
            formalName: formal,
            decimalPlaces: Math.min(6, Math.max(0, Math.trunc(this.currencyDecimalPlaces))),
            decimalSymbol: 'cents',
            showAmountInMillions: false,
            suffixSymbolToAmount: false,
            addSpaceBetweenAmountAndSymbol: true,
          });
          this.modalService.completeActiveSession({
            id: created.id,
            name: `${created.isoCode} — ${created.formalName}`,
            kind: 'CURRENCY',
          });
          return;
        }

        case 'UOM': {
          const symbol = this.uomSymbol.trim();
          if (!symbol) {
            this.formError.set('Unit Symbol is required.');
            return;
          }
          if (this.uomType === 'COMPOUND' && (!this.uomFirstId || !this.uomSecondId)) {
            this.formError.set('Compound UOM requires First Unit and Second Unit.');
            return;
          }
          const created = await this.inventoryStore.createUom({
            uomType: this.uomType,
            symbol,
            formalName: this.uomType === 'SIMPLE' ? this.uomFormalName.trim() || symbol : null,
            gstUqcCode: this.uomType === 'SIMPLE' ? this.uomGstUqcCode : null,
            decimalPlaces: Math.min(6, Math.max(0, Math.trunc(this.uomDecimalPlaces))),
            firstUomId: this.uomType === 'COMPOUND' ? this.uomFirstId : null,
            conversionFactor:
              this.uomType === 'COMPOUND' ? this.uomConversionFactor.trim() || '1' : null,
            secondUomId: this.uomType === 'COMPOUND' ? this.uomSecondId : null,
          });
          this.modalService.completeActiveSession({
            id: created.id,
            name: created.symbol,
            kind: 'UOM',
          });
          return;
        }

        case 'GODOWN': {
          const name = this.godownName.trim();
          if (!name) {
            this.formError.set('Godown Name is required.');
            return;
          }
          const created = await this.inventoryStore.createGodown({
            name,
            aliasCode: this.godownAlias.trim() || null,
            parentGodownId: this.godownParentId(),
            allowStorageOfMaterials: true,
            ourStockWithThirdParty: false,
            thirdPartyStockWithUs: false,
          });
          this.modalService.completeActiveSession({
            id: created.id,
            name: created.name,
            kind: 'GODOWN',
          });
          return;
        }

        case 'STOCK_GROUP': {
          const name = this.stockGroupName.trim();
          if (!name) {
            this.formError.set('Stock Group Name is required.');
            return;
          }
          const created = await this.inventoryStore.createStockGroup({
            name,
            parentStockGroupId: this.stockGroupParentId(),
            shouldQuantitiesOfItemsBeAdded: this.stockGroupAddQuantities,
            hsnSacCode: this.stockGroupHsn.trim() || null,
            gstRatePct: this.stockGroupGstRate.trim() || null,
          });
          this.modalService.completeActiveSession({
            id: created.id,
            name: created.name,
            kind: 'STOCK_GROUP',
          });
          return;
        }

        case 'STOCK_ITEM': {
          const name = this.stockItemName.trim();
          const baseUomId = this.stockItemBaseUomId();
          if (!name) {
            this.formError.set('Stock Item Name is required.');
            return;
          }
          if (!baseUomId) {
            this.formError.set('Select or create (Alt+C) a Base Unit of Measure.');
            return;
          }
          const created = await this.inventoryStore.createStockItem({
            name,
            partNo: this.stockItemPartNo.trim() || null,
            stockGroupId: this.stockItemGroupId(),
            baseUomId,
            maintainInBatches: this.stockItemBatches,
            trackDateOfMfg: false,
            useExpiryDates: false,
            costingMethod: this.stockItemCostingMethod,
            marketValuationMethod: 'AVG_PRICE',
            gstApplicability: this.stockItemGstRate.trim() ? 'APPLICABLE' : 'UNDEFINED',
            gstSupplyType: 'GOODS',
            hsnSacCode: this.stockItemHsn.trim() || null,
            gstRatePct: this.stockItemGstRate.trim() || null,
            isTobaccoMrpItem: false,
          });
          this.modalService.completeActiveSession({
            id: created.id,
            name: created.name,
            kind: 'STOCK_ITEM',
          });
          return;
        }
      }
    } catch (err: unknown) {
      this.formError.set(
        err instanceof Error ? err.message : 'Failed to create secondary master.',
      );
    } finally {
      this.saving.set(false);
    }
  }

  private hydrateSessionDefaults(kind: SecondaryMasterKind, initialName: string): void {
    const session = this.modalService.activeSession();
    if (
      kind === 'UOM' ||
      kind === 'GODOWN' ||
      kind === 'STOCK_GROUP' ||
      kind === 'STOCK_ITEM'
    ) {
      void this.inventoryStore.ensureLoaded();
    } else {
      void this.coaStore.ensureAccountingMastersLoaded();
    }

    if (kind === 'GROUP') {
      this.groupName = initialName;
      this.groupAlias = '';
      this.groupParentId.set(session?.defaultParentGroupId ?? null);
      this.groupNature = 'ASSETS';
      this.groupBehavesLikeSubledger = false;
      this.groupNetBalances = false;
      return;
    }

    if (kind === 'LEDGER') {
      this.ledgerName = initialName;
      this.ledgerAlias = '';
      const defaultGrp =
        session?.defaultGroupId ??
        this.coaStore.groups().find((g) => g.name === 'Sundry Debtors')?.id ??
        this.coaStore.groups()[0]?.id ??
        null;
      this.ledgerGroupId.set(defaultGrp);
      const flags = resolveGroupLineageFlags(defaultGrp, this.coaStore.groups());
      this.ledgerOpeningBalance = '0.00';
      this.ledgerOpeningSide = flags.defaultOpeningSide;
      this.ledgerBillWise = flags.isPartyGroup;
      this.ledgerStateCode.set(this.companyContext.activeCompany()?.stateCode ?? '33');
      this.ledgerRegType = 'REGULAR';
      this.ledgerGstin.set('');
      this.ledgerPan = '';
      this.ledgerDeducteeType = '';
      this.ledgerTaxType.set('GST');
      this.ledgerDutyHead = 'CGST';
      return;
    }

    if (kind === 'COST_CENTRE') {
      void this.coaStore.loadCostCentresAndCategories();
      this.costCentreName = initialName;
      this.costCentreAlias = '';
      this.costCentreCategoryId.set(
        session?.defaultCostCategoryId ?? this.coaStore.costCategories()[0]?.id ?? null,
      );
      this.costCentreParentId.set(null);
      return;
    }

    if (kind === 'UOM') {
      this.uomType = 'SIMPLE';
      this.uomSymbol = initialName.trim() || 'NOS';
      this.uomFormalName = '';
      this.uomGstUqcCode = 'NOS';
      this.uomDecimalPlaces = 0;
      this.uomFirstId = this.inventoryStore.uoms()[0]?.id ?? '';
      this.uomConversionFactor = '10';
      this.uomSecondId = this.inventoryStore.uoms()[0]?.id ?? '';
      return;
    }

    if (kind === 'GODOWN') {
      this.godownName = initialName;
      this.godownAlias = '';
      this.godownParentId.set(null);
      return;
    }

    if (kind === 'STOCK_GROUP') {
      this.stockGroupName = initialName;
      this.stockGroupParentId.set(null);
      this.stockGroupAddQuantities = true;
      this.stockGroupHsn = '';
      this.stockGroupGstRate = '';
      return;
    }

    if (kind === 'STOCK_ITEM') {
      this.stockItemName = initialName;
      this.stockItemPartNo = '';
      this.stockItemCostingMethod = 'AVG_COST';
      this.stockItemGroupId.set(session?.defaultStockGroupId ?? null);
      this.stockItemBaseUomId.set(
        session?.defaultBaseUomId ?? this.inventoryStore.uoms()[0]?.id ?? null,
      );
      this.stockItemHsn = '';
      this.stockItemGstRate = '';
      this.stockItemBatches = false;
      return;
    }

    this.currencyIsoCode = initialName.slice(0, 3).toUpperCase();
    this.currencySymbol = '$';
    this.currencyFormalName = initialName.length > 3 ? initialName : '';
    this.currencyDecimalPlaces = 2;
  }
}
