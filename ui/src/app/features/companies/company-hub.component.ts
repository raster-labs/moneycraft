import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { INDIAN_GST_STATES, validateGstin } from '../../core/validation/gstin-validator';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';
import {
  CompanyContextStore,
  CompanySummary,
  CreateOrUpdateCompanyPayload,
  FiscalPeriodState,
} from '../../state/company-context.store';

@Component({
  selector: 'mc-company-hub',
  standalone: true,
  imports: [FormsModule, CodeBadgeComponent, GridCellNavDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-company-hub-layout" data-mc-nav-container>
      <div class="mc-hub-header">
        <div>
          <h1>Company Hub, F11 Features &amp; Fiscal Periods</h1>
          <p class="mc-hub-sub">
            Switch active company (<strong>F3</strong>), configure statutory GST/TDS &amp; MCA Edit Log
            features (<strong>F11</strong>), and manage fiscal periods (<strong>Alt+F2</strong>).
          </p>
        </div>
        <div class="mc-hub-tabs">
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'list'"
            (click)="activeTab.set('list')"
          >
            Select / Switch (F3)
          </button>
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'form'"
            (click)="startCreateCompany()"
          >
            Create Company
          </button>
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'periods'"
            (click)="openPeriodsTab()"
          >
            Fiscal Periods (<span class="mc-key-alt">F2</span>)
          </button>
        </div>
      </div>

      @if (feedbackMessage(); as msg) {
        <div class="mc-hub-banner" role="status">{{ msg }}</div>
      }

      @if (activeTab() === 'list') {
        <section class="mc-card mc-hub-card" aria-label="Open Companies List">
          <table class="mc-table">
            <thead>
              <tr>
                <th>Code</th>
                <th>Company Legal Name</th>
                <th>State</th>
                <th>FY From</th>
                <th>GSTIN</th>
                <th>MCA Edit Log</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              @for (comp of companyContext.openCompanies(); track comp.id) {
                <tr [class.mc-row-selected]="companyContext.activeCompany()?.id === comp.id">
                  <td><mc-code-badge [code]="comp.code" /></td>
                  <td>
                    <strong>{{ comp.legalName }}</strong>
                  </td>
                  <td>{{ stateName(comp.stateCode) }} ({{ comp.stateCode }})</td>
                  <td><mc-code-badge [code]="comp.financialYearFrom" /></td>
                  <td>
                    @if (comp.features.gstEnabled && comp.features.gstin) {
                      <mc-code-badge [code]="comp.features.gstin" />
                    } @else {
                      <span>—</span>
                    }
                  </td>
                  <td>
                    @if (comp.features.mcaEditLogEnforced) {
                      <span class="mc-badge mc-badge-jade">Enforced</span>
                    } @else {
                      <span class="mc-badge mc-badge-slate">Optional</span>
                    }
                  </td>
                  <td class="mc-row-actions">
                    <button
                      type="button"
                      class="mc-btn"
                      (click)="selectCompany(comp.id)"
                    >
                      Select
                    </button>
                    <button
                      type="button"
                      class="mc-btn"
                      (click)="startAlterCompany(comp)"
                    >
                      Alter / F11
                    </button>
                    <button
                      type="button"
                      class="mc-btn"
                      (click)="companyContext.shutCompany(comp.id)"
                    >
                      Shut
                    </button>
                  </td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="7">
                    No companies available yet. Click <strong>Create Company</strong> to bootstrap
                    your first set of books (28 predefined Tally groups + Cash + Profit &amp; Loss A/c).
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </section>
      }

      @if (activeTab() === 'form') {
        <form (ngSubmit)="saveCompanyForm()" class="mc-card mc-hub-card mc-company-form">
          <div class="mc-form-section-title">
            {{ editingCompanyId() ? 'Company Alteration & F11 Features' : 'Company Creation & F11 Features' }}
          </div>

          <div class="mc-grid-3">
            <div>
              <label class="mc-field-label" for="comp-code">Company Code</label>
              <input
                id="comp-code"
                type="text"
                name="code"
                class="mc-input mc-code"
                mcGridCellNav
                [(ngModel)]="formCode"
                required
              />
            </div>
            <div>
              <label class="mc-field-label" for="comp-legal">Company Legal Name</label>
              <input
                id="comp-legal"
                type="text"
                name="legalName"
                class="mc-input"
                mcGridCellNav
                [(ngModel)]="formLegalName"
                (blur)="syncMailingName()"
                required
              />
            </div>
            <div>
              <label class="mc-field-label" for="comp-mailing">Mailing Name</label>
              <input
                id="comp-mailing"
                type="text"
                name="mailingName"
                class="mc-input"
                mcGridCellNav
                [(ngModel)]="formMailingName"
                required
              />
            </div>
          </div>

          <div class="mc-grid-4">
            <div>
              <label class="mc-field-label" for="comp-country">Country</label>
              <input
                id="comp-country"
                type="text"
                name="countryCode"
                class="mc-input"
                mcGridCellNav
                [(ngModel)]="formCountryCode"
                required
              />
            </div>
            <div>
              <label class="mc-field-label" for="comp-state">State (India GST Code)</label>
              <select
                id="comp-state"
                name="stateCode"
                class="mc-select"
                mcGridCellNav
                [ngModel]="formStateCode()"
                (ngModelChange)="formStateCode.set($event)"
              >
                @for (st of states; track st.code) {
                  <option [value]="st.code">{{ st.code }} - {{ st.name }}</option>
                }
              </select>
            </div>
            <div>
              <label class="mc-field-label" for="comp-pin">PIN Code</label>
              <input
                id="comp-pin"
                type="text"
                name="pinCode"
                class="mc-input mc-code"
                mcGridCellNav
                [(ngModel)]="formPinCode"
              />
            </div>
            <div>
              <label class="mc-field-label" for="comp-scale">Base Currency &amp; Scale (0..6)</label>
              <div class="mc-currency-inline">
                <input
                  type="text"
                  name="baseCurrencyCode"
                  class="mc-input mc-code"
                  [(ngModel)]="formCurrencyCode"
                  aria-label="Base Currency ISO Code"
                />
                <input
                  id="comp-scale"
                  type="number"
                  min="0"
                  max="6"
                  name="currencyScale"
                  class="mc-input"
                  [(ngModel)]="formCurrencyScale"
                />
              </div>
            </div>
          </div>

          <div class="mc-grid-2">
            <div>
              <label class="mc-field-label" for="comp-fy-from">Financial Year Beginning From</label>
              <input
                id="comp-fy-from"
                type="date"
                name="financialYearFrom"
                class="mc-input"
                mcGridCellNav
                [(ngModel)]="formFyFrom"
                required
              />
            </div>
            <div>
              <label class="mc-field-label" for="comp-books-from">Books Beginning From</label>
              <input
                id="comp-books-from"
                type="date"
                name="booksBeginningFrom"
                class="mc-input"
                mcGridCellNav
                [(ngModel)]="formBooksFrom"
                required
              />
            </div>
          </div>

          <!-- F11 Company Features Panel -->
          <div class="mc-f11-panel">
            <div class="mc-f11-title-row">
              <div class="mc-form-section-title">
                F11: Company Features (Accounting, Inventory, Statutory &amp; MCA Audit)
              </div>
              <div class="mc-f11-tier-toggles">
                <label class="mc-toggle-line">
                  <input
                    type="checkbox"
                    name="f11ShowMore"
                    data-testid="hub-f11-show-more-toggle"
                    [ngModel]="f11ShowMore()"
                    (ngModelChange)="onF11ShowMoreChange($event)"
                  />
                  <span>Show more features</span>
                </label>
                <label class="mc-toggle-line">
                  <input
                    type="checkbox"
                    name="f11ShowAll"
                    data-testid="hub-f11-show-all-toggle"
                    [ngModel]="f11ShowAll()"
                    (ngModelChange)="onF11ShowAllChange($event)"
                  />
                  <span>Show all features</span>
                </label>
              </div>
            </div>

            <div class="mc-f11-columns">
              <div class="mc-f11-group">
                <h3>Accounting Features</h3>
                <label class="mc-toggle-line">
                  <input type="checkbox" name="maintainAccounts" [(ngModel)]="featMaintainAccounts" />
                  <span>Maintain Accounts</span>
                </label>
                <label class="mc-toggle-line">
                  <input
                    type="checkbox"
                    name="billWiseEnabled"
                    [(ngModel)]="featBillWise"
                    [disabled]="!featMaintainAccounts"
                  />
                  <span>Enable Bill-wise entry</span>
                </label>
                @if (f11ShowMore() || f11ShowAll()) {
                  <label class="mc-toggle-line">
                    <input type="checkbox" name="costCentresEnabled" [(ngModel)]="featCostCentres" />
                    <span>Enable Cost Centres</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="interestCalculationEnabled"
                      [(ngModel)]="featInterestCalculation"
                    />
                    <span>Enable Interest Calculation</span>
                  </label>
                }

                <h3>Inventory Features</h3>
                <label class="mc-toggle-line">
                  <input type="checkbox" name="maintainInventory" [(ngModel)]="featMaintainInventory" />
                  <span>Maintain Inventory</span>
                </label>
                <label class="mc-toggle-line">
                  <input
                    type="checkbox"
                    name="inventoryIntegrated"
                    [(ngModel)]="featInventoryIntegrated"
                    [disabled]="!featMaintainInventory || !featMaintainAccounts"
                  />
                  <span>Integrate Accounts with Inventory</span>
                </label>
                @if (f11ShowMore() || f11ShowAll()) {
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="multiplePriceLevelsEnabled"
                      [(ngModel)]="featMultiplePriceLevels"
                    />
                    <span>Enable multiple Price Levels</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input type="checkbox" name="batchesEnabled" [(ngModel)]="featBatches" />
                    <span>Enable Batches</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="expiryDateForBatchesEnabled"
                      [(ngModel)]="featExpiryDateForBatches"
                      [disabled]="!featBatches"
                    />
                    <span>Maintain Expiry Date for Batches</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="useDiscountColumnInInvoices"
                      [(ngModel)]="featUseDiscountColumn"
                    />
                    <span>Use Discount column in invoices</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="useSeparateActualAndBilledQty"
                      [(ngModel)]="featUseSeparateActualBilledQty"
                    />
                    <span>Use separate Actual and Billed Quantity columns in invoices</span>
                  </label>
                }
                @if (f11ShowAll()) {
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="jobOrderProcessingEnabled"
                      [(ngModel)]="featJobOrderProcessing"
                    />
                    <span>Enable Job Order Processing</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input type="checkbox" name="costTrackingEnabled" [(ngModel)]="featCostTracking" />
                    <span>Enable Cost Tracking</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="jobCostingEnabled"
                      [(ngModel)]="featJobCosting"
                      [disabled]="!featCostCentres"
                    />
                    <span>Enable Job Costing</span>
                  </label>
                }
              </div>

              <div class="mc-f11-group">
                <h3>Taxation &amp; Statutory</h3>
                <label class="mc-toggle-line">
                  <input
                    type="checkbox"
                    name="gstEnabled"
                    [ngModel]="featGstEnabled()"
                    (ngModelChange)="featGstEnabled.set($event)"
                  />
                  <span>Enable Goods and Services Tax (GST)</span>
                </label>

                @if (featGstEnabled()) {
                  <div class="mc-gst-subpanel">
                    <div class="mc-grid-2">
                      <div>
                        <label class="mc-field-label" for="comp-gst-reg-type">Registration Type</label>
                        <select
                          id="comp-gst-reg-type"
                          name="gstRegistrationType"
                          class="mc-select"
                          [(ngModel)]="featGstRegType"
                        >
                          <option value="REGULAR">Regular</option>
                          <option value="COMPOSITION">Composition</option>
                          <option value="ISD">Input Service Distributor</option>
                          <option value="SEZ">SEZ</option>
                        </select>
                      </div>
                      <div>
                        <label class="mc-field-label" for="comp-gstr1-period">Periodicity of GSTR-1</label>
                        <select
                          id="comp-gstr1-period"
                          name="gstr1Periodicity"
                          class="mc-select"
                          [(ngModel)]="featGstr1Periodicity"
                        >
                          <option value="MONTHLY">Monthly</option>
                          <option value="QUARTERLY">Quarterly (QRMP)</option>
                        </select>
                      </div>
                    </div>

                    <div>
                      <label class="mc-field-label" for="comp-gstin">
                        GSTIN / UIN (15-char Luhn Mod-36 Verified)
                      </label>
                      <input
                        id="comp-gstin"
                        type="text"
                        maxlength="15"
                        name="gstin"
                        class="mc-input mc-code"
                        [ngModel]="featGstin()"
                        (ngModelChange)="featGstin.set($event)"
                        [attr.aria-invalid]="!gstinValidation().valid && featGstin().trim().length > 0"
                        placeholder="e.g. 33AABCM1234F1Z4"
                      />
                      @if (featGstin().trim().length > 0) {
                        @if (gstinValidation().valid) {
                          <div class="mc-gstin-valid">
                            Valid Mod-36 GSTIN &bull; PAN: {{ gstinValidation().pan }}
                          </div>
                        } @else {
                          <div class="mc-field-error" role="alert">
                            {{ gstinValidation().error }}
                          </div>
                        }
                      }
                    </div>

                    <div class="mc-grid-2">
                      <label class="mc-toggle-line">
                        <input
                          type="checkbox"
                          name="eWayBillApplicable"
                          [(ngModel)]="featEWayBillApplicable"
                        />
                        <span>e-Way Bill applicable</span>
                      </label>
                      <label class="mc-toggle-line">
                        <input
                          type="checkbox"
                          name="eInvoiceApplicable"
                          [(ngModel)]="featEInvoiceApplicable"
                        />
                        <span>e-Invoicing applicable</span>
                      </label>
                    </div>

                    @if (featEWayBillApplicable) {
                      <div>
                        <label class="mc-field-label" for="comp-ewb-limit">e-Way Bill Threshold Limit</label>
                        <input
                          id="comp-ewb-limit"
                          type="text"
                          name="eWayBillThresholdLimit"
                          class="mc-input mc-code"
                          [(ngModel)]="featEWayBillThreshold"
                        />
                      </div>
                    }

                    <label class="mc-toggle-line">
                      <input type="checkbox" name="multiGstinEnabled" [(ngModel)]="featMultiGstin" />
                      <span>Enable Multi-GSTIN Registrations</span>
                    </label>
                  </div>
                }

                <label class="mc-toggle-line">
                  <input type="checkbox" name="tdsEnabled" [(ngModel)]="featTdsEnabled" />
                  <span>Enable Tax Deducted at Source (TDS)</span>
                </label>

                @if (f11ShowMore() || f11ShowAll()) {
                  <label class="mc-toggle-line">
                    <input type="checkbox" name="tcsEnabled" [(ngModel)]="featTcsEnabled" />
                    <span>Enable Tax Collected at Source (TCS)</span>
                  </label>
                }

                @if (f11ShowAll()) {
                  <label class="mc-toggle-line">
                    <input type="checkbox" name="vatEnabled" [(ngModel)]="featVatEnabled" />
                    <span>Enable Value Added Tax (VAT)</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input type="checkbox" name="exciseEnabled" [(ngModel)]="featExciseEnabled" />
                    <span>Enable Excise</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input type="checkbox" name="serviceTaxEnabled" [(ngModel)]="featServiceTaxEnabled" />
                    <span>Enable Service Tax</span>
                  </label>
                }

                @if (f11ShowMore() || f11ShowAll()) {
                  <h3>Payroll &amp; Others</h3>
                  <label class="mc-toggle-line">
                    <input type="checkbox" name="payrollEnabled" [(ngModel)]="featPayrollEnabled" />
                    <span>Maintain Payroll</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="payrollStatutoryEnabled"
                      [(ngModel)]="featPayrollStatutoryEnabled"
                      [disabled]="!featPayrollEnabled"
                    />
                    <span>Enable Payroll Statutory</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="multipleAddressesEnabled"
                      [(ngModel)]="featMultipleAddresses"
                    />
                    <span>Enable multiple addresses</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="markModifiedVouchers"
                      [(ngModel)]="featMarkModifiedVouchers"
                    />
                    <span>Mark modified vouchers</span>
                  </label>
                }

                <label class="mc-toggle-line">
                  <input
                    type="checkbox"
                    name="mcaEditLogEnforced"
                    [(ngModel)]="featMcaEditLog"
                    [disabled]="mcaEditLogLocked()"
                  />
                  <span>
                    Enforce MCA Audit Trail (Edit Log Rule 3(1))
                    @if (mcaEditLogLocked()) {
                      <strong class="mc-lock-tag">(Locked — Non-Disablable)</strong>
                    }
                  </span>
                </label>
              </div>
            </div>
          </div>

          @if (formError(); as err) {
            <div class="mc-field-error" role="alert">{{ err }}</div>
          }

          <div class="mc-form-footer">
            <button type="button" class="mc-btn" (click)="activeTab.set('list')">Cancel (Esc)</button>
            <button
              type="button"
              class="mc-btn mc-btn-primary"
              [disabled]="companyContext.quarantined()"
              (click)="saveCompanyForm()"
            >
              Accept (<span class="mc-key-ctrl">A</span>)
            </button>
          </div>
        </form>
      }

      @if (activeTab() === 'periods') {
        <section class="mc-card mc-hub-card" aria-label="Fiscal Period Controls">
          <div class="mc-period-bar">
            <div>
              <strong>Active Reporting Period:</strong>
              <mc-code-badge [code]="companyContext.periodFrom() + ' to ' + companyContext.periodTo()" />
            </div>
            <div class="mc-period-inline-inputs">
              <input type="date" class="mc-input" [(ngModel)]="quickPeriodFrom" aria-label="Period From" />
              <input type="date" class="mc-input" [(ngModel)]="quickPeriodTo" aria-label="Period To" />
              <button type="button" class="mc-btn mc-btn-primary" (click)="applyQuickPeriod()">
                Apply Range
              </button>
            </div>
          </div>

          <table class="mc-table">
            <thead>
              <tr>
                <th>Period Code</th>
                <th>Start Date</th>
                <th>End Date</th>
                <th>State</th>
                <th>Lock / Reopen Controls</th>
              </tr>
            </thead>
            <tbody>
              @for (p of companyContext.periods(); track p.id) {
                <tr>
                  <td><mc-code-badge [code]="p.periodCode" /></td>
                  <td><mc-code-badge [code]="p.startDate" /></td>
                  <td><mc-code-badge [code]="p.endDate" /></td>
                  <td>
                    <span
                      class="mc-badge"
                      [class.mc-badge-jade]="p.state === 'OPEN'"
                      [class.mc-badge-warning]="p.state === 'SOFT_LOCKED'"
                      [class.mc-badge-danger]="p.state === 'CLOSED'"
                    >
                      {{ p.state }}
                    </span>
                  </td>
                  <td class="mc-row-actions">
                    <button type="button" class="mc-btn" (click)="transitionPeriod(p.id, 'OPEN')">
                      Open
                    </button>
                    <button type="button" class="mc-btn" (click)="transitionPeriod(p.id, 'SOFT_LOCKED')">
                      Soft Lock
                    </button>
                    <button type="button" class="mc-btn" (click)="transitionPeriod(p.id, 'CLOSED')">
                      Close Period
                    </button>
                  </td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="5">
                    Select an active company to load its monthly / fiscal-year accounting periods.
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </section>
      }
      @if (postCreateF11ModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Company created successfully — F11: Company Features"
          data-testid="post-create-f11-modal"
          (keydown.escape)="closePostCreateF11Modal()"
        >
          <div class="mc-modal-dialog mc-post-f11-dialog">
            <div class="mc-modal-header">
              <span>
                Company created successfully — F11: Company Features ({{ createdCompanySummary()?.legalName }})
              </span>
              <div class="mc-f11-tier-toggles">
                <label class="mc-toggle-line">
                  <input
                    type="checkbox"
                    name="modalF11ShowMore"
                    [ngModel]="f11ShowMore()"
                    (ngModelChange)="onF11ShowMoreChange($event)"
                  />
                  <span>Show more features</span>
                </label>
                <label class="mc-toggle-line">
                  <input
                    type="checkbox"
                    name="modalF11ShowAll"
                    [ngModel]="f11ShowAll()"
                    (ngModelChange)="onF11ShowAllChange($event)"
                  />
                  <span>Show all features</span>
                </label>
                <button
                  type="button"
                  class="mc-btn mc-btn-secondary"
                  data-testid="post-create-f11-close-btn"
                  (click)="closePostCreateF11Modal()"
                >
                  Close (Esc)
                </button>
              </div>
            </div>
            <div class="mc-modal-body">
              <div class="mc-f11-columns">
                <div class="mc-f11-group">
                  <h3>Accounting &amp; Inventory</h3>
                  <label class="mc-toggle-line">
                    <input type="checkbox" name="modalMaintainAccounts" [(ngModel)]="featMaintainAccounts" />
                    <span>Maintain Accounts</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="modalBillWise"
                      [(ngModel)]="featBillWise"
                      [disabled]="!featMaintainAccounts"
                    />
                    <span>Enable Bill-wise entry</span>
                  </label>
                  @if (f11ShowMore() || f11ShowAll()) {
                    <label class="mc-toggle-line">
                      <input type="checkbox" name="modalCostCentres" [(ngModel)]="featCostCentres" />
                      <span>Enable Cost Centres</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="modalInterestCalc"
                        [(ngModel)]="featInterestCalculation"
                      />
                      <span>Enable Interest Calculation</span>
                    </label>
                  }
                  <label class="mc-toggle-line">
                    <input type="checkbox" name="modalMaintainInventory" [(ngModel)]="featMaintainInventory" />
                    <span>Maintain Inventory</span>
                  </label>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="modalInvIntegrated"
                      [(ngModel)]="featInventoryIntegrated"
                      [disabled]="!featMaintainInventory || !featMaintainAccounts"
                    />
                    <span>Integrate Accounts with Inventory</span>
                  </label>
                  @if (f11ShowMore() || f11ShowAll()) {
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="modalPriceLevels"
                        [(ngModel)]="featMultiplePriceLevels"
                      />
                      <span>Enable multiple Price Levels</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input type="checkbox" name="modalBatches" [(ngModel)]="featBatches" />
                      <span>Enable Batches</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="modalExpiryBatches"
                        [(ngModel)]="featExpiryDateForBatches"
                        [disabled]="!featBatches"
                      />
                      <span>Maintain Expiry Date for Batches</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="modalDiscountCol"
                        [(ngModel)]="featUseDiscountColumn"
                      />
                      <span>Use Discount column in invoices</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="modalSeparateActualBilled"
                        [(ngModel)]="featUseSeparateActualBilledQty"
                      />
                      <span>Use separate Actual and Billed Quantity columns in invoices</span>
                    </label>
                  }
                  @if (f11ShowAll()) {
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="modalJobOrder"
                        [(ngModel)]="featJobOrderProcessing"
                      />
                      <span>Enable Job Order Processing</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input type="checkbox" name="modalCostTracking" [(ngModel)]="featCostTracking" />
                      <span>Enable Cost Tracking</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="modalJobCosting"
                        [(ngModel)]="featJobCosting"
                        [disabled]="!featCostCentres"
                      />
                      <span>Enable Job Costing</span>
                    </label>
                  }
                </div>

                <div class="mc-f11-group">
                  <h3>Taxation, Payroll &amp; MCA Edit Log</h3>
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="modalGstEnabled"
                      [ngModel]="featGstEnabled()"
                      (ngModelChange)="featGstEnabled.set($event)"
                    />
                    <span>Enable Goods and Services Tax (GST)</span>
                  </label>
                  @if (featGstEnabled()) {
                    <div>
                      <label class="mc-field-label" for="modal-gstin">GSTIN (Optional now)</label>
                      <input
                        id="modal-gstin"
                        type="text"
                        name="modalGstin"
                        class="mc-input mc-code"
                        [ngModel]="featGstin()"
                        (ngModelChange)="featGstin.set($event)"
                        placeholder="33AABCM1234F1Z4"
                      />
                    </div>
                  }
                  <label class="mc-toggle-line">
                    <input type="checkbox" name="modalTdsEnabled" [(ngModel)]="featTdsEnabled" />
                    <span>Enable Tax Deducted at Source (TDS)</span>
                  </label>
                  @if (f11ShowMore() || f11ShowAll()) {
                    <label class="mc-toggle-line">
                      <input type="checkbox" name="modalTcsEnabled" [(ngModel)]="featTcsEnabled" />
                      <span>Enable Tax Collected at Source (TCS)</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input type="checkbox" name="modalPayrollEnabled" [(ngModel)]="featPayrollEnabled" />
                      <span>Maintain Payroll</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="modalPayrollStat"
                        [(ngModel)]="featPayrollStatutoryEnabled"
                        [disabled]="!featPayrollEnabled"
                      />
                      <span>Enable Payroll Statutory</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="modalMultiAddr"
                        [(ngModel)]="featMultipleAddresses"
                      />
                      <span>Enable multiple addresses</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="modalMarkModVch"
                        [(ngModel)]="featMarkModifiedVouchers"
                      />
                      <span>Mark modified vouchers</span>
                    </label>
                  }
                  @if (f11ShowAll()) {
                    <label class="mc-toggle-line">
                      <input type="checkbox" name="modalVatEnabled" [(ngModel)]="featVatEnabled" />
                      <span>Enable Value Added Tax (VAT)</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input type="checkbox" name="modalExciseEnabled" [(ngModel)]="featExciseEnabled" />
                      <span>Enable Excise</span>
                    </label>
                    <label class="mc-toggle-line">
                      <input
                        type="checkbox"
                        name="modalServiceTaxEnabled"
                        [(ngModel)]="featServiceTaxEnabled"
                      />
                      <span>Enable Service Tax</span>
                    </label>
                  }
                  <label class="mc-toggle-line">
                    <input
                      type="checkbox"
                      name="modalMcaEditLog"
                      [(ngModel)]="featMcaEditLog"
                      [disabled]="mcaEditLogLocked()"
                    />
                    <span>Enforce Immutable MCA Edit Log</span>
                  </label>
                </div>
              </div>
            </div>
            <div class="mc-modal-footer">
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="post-create-f11-accept-btn"
                (click)="confirmPostCreateFeatures()"
              >
                Accept Features &amp; Open Gateway (<span class="mc-key-ctrl">A</span>)
              </button>
            </div>
          </div>
        </div>
      }
    </div>
  `,
  styles: `
    .mc-post-f11-dialog {
      width: min(760px, 95vw);
    }
    .mc-f11-title-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-2);
    }
    .mc-f11-tier-toggles {
      display: flex;
      align-items: center;
      gap: var(--mc-space-3);
      flex-wrap: wrap;
    }
    .mc-company-hub-layout {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-4);
    }

    .mc-hub-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: var(--mc-space-3);
      flex-wrap: wrap;
    }

    .mc-hub-sub {
      margin: 4px 0 0;
      color: var(--mc-color-slate-600);
      font-size: var(--mc-font-size-sm);
    }

    .mc-hub-tabs {
      display: flex;
      gap: var(--mc-space-2);
    }

    .mc-hub-banner {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-teal-50);
      border-left: 3px solid var(--mc-color-verdigris-teal);
      font-weight: 600;
      color: var(--mc-color-teal-700);
    }

    .mc-hub-card {
      padding: var(--mc-space-4);
    }

    .mc-row-actions {
      display: flex;
      gap: 6px;
    }

    .mc-company-form {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-form-section-title {
      font-weight: 700;
      color: var(--mc-color-slate-900);
      padding-bottom: 6px;
      border-bottom: var(--mc-border-subtle);
    }

    .mc-grid-2 {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    .mc-grid-3 {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    .mc-grid-4 {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    @media (max-width: 900px) {
      .mc-grid-3,
      .mc-grid-4 {
        grid-template-columns: 1fr;
      }
    }

    .mc-currency-inline {
      display: grid;
      grid-template-columns: 1fr 80px;
      gap: 6px;
    }

    .mc-f11-panel {
      margin-top: var(--mc-space-2);
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-sm);
    }

    .mc-f11-columns {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-4);
      margin-top: var(--mc-space-3);
    }

    @media (max-width: 820px) {
      .mc-f11-columns {
        grid-template-columns: 1fr;
      }
    }

    .mc-f11-group {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);

      h3 {
        font-size: var(--mc-font-size-sm);
        margin-bottom: 4px;
      }
    }

    .mc-toggle-line {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      font-weight: 500;
      cursor: pointer;
    }

    .mc-gst-subpanel {
      padding: var(--mc-space-3);
      background: var(--mc-color-surface);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-gstin-valid {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-success-700);
      font-weight: 600;
      margin-top: 3px;
    }

    .mc-lock-tag {
      color: var(--mc-color-teal-700);
      font-size: var(--mc-font-size-xs);
    }

    .mc-form-footer {
      display: flex;
      justify-content: flex-end;
      gap: var(--mc-space-2);
      padding-top: var(--mc-space-2);
      border-top: var(--mc-border-subtle);
    }

    .mc-period-bar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-3);
      margin-bottom: var(--mc-space-3);
      flex-wrap: wrap;
    }

    .mc-period-inline-inputs {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
    }
  `,
})
export class CompanyHubComponent implements OnInit {
  readonly companyContext = inject(CompanyContextStore);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly states = INDIAN_GST_STATES;

  readonly activeTab = signal<'list' | 'form' | 'periods'>('list');
  readonly editingCompanyId = signal<string | null>(null);
  readonly mcaEditLogLocked = signal<boolean>(false);
  readonly formError = signal<string | null>(null);
  readonly feedbackMessage = signal<string | null>(null);
  readonly postCreateF11ModalOpen = signal<boolean>(false);
  readonly createdCompanySummary = signal<CompanySummary | null>(null);

  readonly f11ShowMore = signal<boolean>(true);
  readonly f11ShowAll = signal<boolean>(false);

  formCode = 'MCPB01';
  formLegalName = '';
  formMailingName = '';
  formCountryCode = 'IN';
  readonly formStateCode = signal<string>('33');
  formPinCode = '600001';
  formCurrencyCode = 'INR';
  formCurrencyScale = 2;
  formFyFrom = '2025-04-01';
  formBooksFrom = '2025-04-01';

  featMaintainAccounts = true;
  featBillWise = true;
  featCostCentres = false;
  featInterestCalculation = false;
  featMaintainInventory = true;
  featInventoryIntegrated = true;
  featMultiplePriceLevels = false;
  featBatches = true;
  featExpiryDateForBatches = true;
  featJobOrderProcessing = false;
  featCostTracking = false;
  featJobCosting = false;
  featUseDiscountColumn = true;
  featUseSeparateActualBilledQty = true;
  readonly featGstEnabled = signal<boolean>(true);
  featMultiGstin = false;
  featGstRegType: 'REGULAR' | 'COMPOSITION' | 'ISD' | 'SEZ' = 'REGULAR';
  readonly featGstin = signal<string>('');
  featGstr1Periodicity: 'MONTHLY' | 'QUARTERLY' = 'MONTHLY';
  featEInvoiceApplicable = false;
  featEWayBillApplicable = true;
  featEWayBillThreshold = '50000.00';
  featTdsEnabled = true;
  featTcsEnabled = false;
  featVatEnabled = false;
  featExciseEnabled = false;
  featServiceTaxEnabled = false;
  featPayrollEnabled = false;
  featPayrollStatutoryEnabled = false;
  featMultipleAddresses = false;
  featMarkModifiedVouchers = false;
  featMcaEditLog = true;

  quickPeriodFrom = '2025-04-01';
  quickPeriodTo = '2026-03-31';

  readonly gstinValidation = computed(() =>
    validateGstin(this.featGstin(), this.formStateCode()),
  );

  onF11ShowMoreChange(val: boolean): void {
    this.f11ShowMore.set(val);
    if (!val) {
      this.f11ShowAll.set(false);
    }
  }

  onF11ShowAllChange(val: boolean): void {
    this.f11ShowAll.set(val);
    if (val) {
      this.f11ShowMore.set(true);
    }
  }

  ngOnInit(): void {
    const mode = this.route.snapshot.queryParamMap.get('mode');
    if (mode === 'create') {
      this.startCreateCompany();
    } else if ((mode === 'alter' || mode === 'features') && this.companyContext.activeCompany()) {
      this.startAlterCompany(this.companyContext.activeCompany()!);
    }

    const disposeAccept = this.shortcutRegistry.bindHandler('screen.accept', 'SCREEN', () => {
      if (this.activeTab() === 'form') {
        void this.saveCompanyForm();
      }
    });
    this.destroyRef.onDestroy(disposeAccept);
  }

  stateName(code: string): string {
    return INDIAN_GST_STATES.find((s) => s.code === code)?.name ?? code;
  }

  syncMailingName(): void {
    if (!this.formMailingName.trim() && this.formLegalName.trim()) {
      this.formMailingName = this.formLegalName.trim();
    }
  }

  selectCompany(companyId: string): void {
    const ok = this.companyContext.selectActiveCompany(companyId);
    if (ok) {
      this.feedbackMessage.set('Switched active company.');
    } else {
      this.formError.set('Cannot switch companies while an unsaved draft is open.');
    }
  }

  startCreateCompany(): void {
    this.editingCompanyId.set(null);
    this.mcaEditLogLocked.set(false);
    this.formError.set(null);
    this.formCode = `COMP${String(this.companyContext.openCompanies().length + 1).padStart(2, '0')}`;
    this.formLegalName = '';
    this.formMailingName = '';
    this.formStateCode.set('33');
    this.formPinCode = '600001';
    this.formCurrencyCode = 'INR';
    this.formCurrencyScale = 2;
    this.formFyFrom = '2025-04-01';
    this.formBooksFrom = '2025-04-01';
    this.featMaintainAccounts = true;
    this.featBillWise = true;
    this.featCostCentres = false;
    this.featInterestCalculation = false;
    this.featMaintainInventory = true;
    this.featInventoryIntegrated = true;
    this.featMultiplePriceLevels = false;
    this.featBatches = true;
    this.featExpiryDateForBatches = true;
    this.featJobOrderProcessing = false;
    this.featCostTracking = false;
    this.featJobCosting = false;
    this.featUseDiscountColumn = true;
    this.featUseSeparateActualBilledQty = true;
    this.featGstEnabled.set(true);
    this.featGstin.set('');
    this.featEInvoiceApplicable = false;
    this.featEWayBillApplicable = true;
    this.featEWayBillThreshold = '50000.00';
    this.featTdsEnabled = true;
    this.featTcsEnabled = false;
    this.featVatEnabled = false;
    this.featExciseEnabled = false;
    this.featServiceTaxEnabled = false;
    this.featPayrollEnabled = false;
    this.featPayrollStatutoryEnabled = false;
    this.featMultipleAddresses = false;
    this.featMarkModifiedVouchers = false;
    this.featMcaEditLog = true;
    this.activeTab.set('form');
  }

  startAlterCompany(comp: CompanySummary): void {
    this.editingCompanyId.set(comp.id);
    this.mcaEditLogLocked.set(comp.features.mcaEditLogEnforced);
    this.formError.set(null);
    this.formCode = comp.code;
    this.formLegalName = comp.legalName;
    this.formMailingName = comp.mailingName;
    this.formCountryCode = comp.countryCode;
    this.formStateCode.set(comp.stateCode);
    this.formPinCode = comp.pinCode;
    this.formCurrencyCode = comp.baseCurrencyCode;
    this.formCurrencyScale = comp.currencyScale;
    this.formFyFrom = comp.financialYearFrom;
    this.formBooksFrom = comp.booksBeginningFrom;
    this.featMaintainAccounts = comp.features.maintainAccounts;
    this.featBillWise = comp.features.billWiseEnabled;
    this.featCostCentres = comp.features.costCentresEnabled;
    this.featInterestCalculation = Boolean(comp.features.interestCalculationEnabled);
    this.featMaintainInventory = comp.features.maintainInventory;
    this.featInventoryIntegrated = comp.features.inventoryIntegrated;
    this.featMultiplePriceLevels = Boolean(comp.features.multiplePriceLevelsEnabled);
    this.featBatches = comp.features.batchesEnabled !== false;
    this.featExpiryDateForBatches = comp.features.expiryDateForBatchesEnabled !== false;
    this.featJobOrderProcessing = Boolean(comp.features.jobOrderProcessingEnabled);
    this.featCostTracking = Boolean(comp.features.costTrackingEnabled);
    this.featJobCosting = Boolean(comp.features.jobCostingEnabled);
    this.featUseDiscountColumn = comp.features.useDiscountColumnInInvoices !== false;
    this.featUseSeparateActualBilledQty = comp.features.useSeparateActualAndBilledQty !== false;
    this.featGstEnabled.set(comp.features.gstEnabled);
    this.featMultiGstin = comp.features.multiGstinEnabled;
    this.featGstRegType = comp.features.gstRegistrationType;
    this.featGstin.set(comp.features.gstin);
    this.featGstr1Periodicity = comp.features.gstr1Periodicity;
    this.featEInvoiceApplicable = Boolean(comp.features.eInvoiceApplicable);
    this.featEWayBillApplicable = comp.features.eWayBillApplicable !== false;
    this.featEWayBillThreshold = comp.features.eWayBillThresholdLimit ?? '50000.00';
    this.featTdsEnabled = comp.features.tdsEnabled;
    this.featTcsEnabled = Boolean(comp.features.tcsEnabled);
    this.featVatEnabled = Boolean(comp.features.vatEnabled);
    this.featExciseEnabled = Boolean(comp.features.exciseEnabled);
    this.featServiceTaxEnabled = Boolean(comp.features.serviceTaxEnabled);
    this.featPayrollEnabled = Boolean(comp.features.payrollEnabled);
    this.featPayrollStatutoryEnabled = Boolean(comp.features.payrollStatutoryEnabled);
    this.featMultipleAddresses = Boolean(comp.features.multipleAddressesEnabled);
    this.featMarkModifiedVouchers = Boolean(comp.features.markModifiedVouchers);
    this.featMcaEditLog = comp.features.mcaEditLogEnforced;
    this.activeTab.set('form');
  }

  async saveCompanyForm(): Promise<void> {
    this.formError.set(null);
    if (!this.formLegalName.trim() || !this.formCode.trim()) {
      this.formError.set('Company Code and Legal Name are required.');
      return;
    }

    if (this.formBooksFrom < this.formFyFrom) {
      this.formError.set('Books Beginning From date cannot precede Financial Year Beginning From.');
      return;
    }

    if (this.featGstEnabled() && this.featGstin().trim().length > 0) {
      const check = this.gstinValidation();
      if (!check.valid) {
        this.formError.set(check.error ?? 'Invalid GSTIN');
        return;
      }
    }

    const editingExisting = this.editingCompanyId()
      ? this.companyContext.openCompanies().find((c) => c.id === this.editingCompanyId())
      : undefined;

    const payload: CreateOrUpdateCompanyPayload = {
      code: this.formCode.trim().toUpperCase(),
      legalName: this.formLegalName.trim(),
      mailingName: (this.formMailingName || this.formLegalName).trim(),
      countryCode: this.formCountryCode.trim().toUpperCase(),
      stateCode: this.formStateCode(),
      stateName: this.stateName(this.formStateCode()),
      pinCode: this.formPinCode.trim(),
      baseCurrencyCode: this.formCurrencyCode.trim().toUpperCase(),
      currencySymbol: this.formCurrencyCode.trim().toUpperCase() === 'INR' ? '\u20B9' : this.formCurrencyCode.trim(),
      currencyScale: Math.min(6, Math.max(0, Math.trunc(this.formCurrencyScale))),
      financialYearFrom: this.formFyFrom,
      booksBeginningFrom: this.formBooksFrom,
      versionNo: editingExisting?.versionNo,
      features: {
        maintainAccounts: this.featMaintainAccounts,
        billWiseEnabled: this.featMaintainAccounts ? this.featBillWise : false,
        costCentresEnabled: this.featCostCentres,
        interestCalculationEnabled: this.featInterestCalculation,
        maintainInventory: this.featMaintainInventory,
        inventoryIntegrated:
          this.featMaintainAccounts && this.featMaintainInventory
            ? this.featInventoryIntegrated
            : false,
        multiplePriceLevelsEnabled: this.featMultiplePriceLevels,
        batchesEnabled: this.featBatches,
        expiryDateForBatchesEnabled: this.featBatches ? this.featExpiryDateForBatches : false,
        jobOrderProcessingEnabled: this.featJobOrderProcessing,
        costTrackingEnabled: this.featCostTracking,
        jobCostingEnabled: this.featCostCentres ? this.featJobCosting : false,
        useDiscountColumnInInvoices: this.featUseDiscountColumn,
        useSeparateActualAndBilledQty: this.featUseSeparateActualBilledQty,
        gstEnabled: this.featGstEnabled(),
        multiGstinEnabled: this.featMultiGstin,
        gstRegistrationType: this.featGstRegType,
        gstin: this.featGstin().trim().toUpperCase(),
        gstr1Periodicity: this.featGstr1Periodicity,
        gstEffectiveFrom: this.formFyFrom,
        eInvoiceApplicable: this.featEInvoiceApplicable,
        eWayBillApplicable: this.featEWayBillApplicable,
        eWayBillThresholdLimit: this.featEWayBillThreshold.trim() || '50000.00',
        tdsEnabled: this.featTdsEnabled,
        tcsEnabled: this.featTcsEnabled,
        vatEnabled: this.featVatEnabled,
        exciseEnabled: this.featExciseEnabled,
        serviceTaxEnabled: this.featServiceTaxEnabled,
        payrollEnabled: this.featPayrollEnabled,
        payrollStatutoryEnabled: this.featPayrollEnabled ? this.featPayrollStatutoryEnabled : false,
        multipleAddressesEnabled: this.featMultipleAddresses,
        markModifiedVouchers: this.featMarkModifiedVouchers,
        mcaEditLogEnforced: this.mcaEditLogLocked() ? true : this.featMcaEditLog,
      },
    };

    try {
      const editId = this.editingCompanyId();
      if (editId) {
        await this.companyContext.updateCompany(editId, payload);
        this.feedbackMessage.set(`Updated company "${payload.legalName}".`);
        this.activeTab.set('list');
      } else {
        const created = await this.companyContext.createCompany(payload);
        this.feedbackMessage.set(`Created company "${payload.legalName}" with 28 predefined groups.`);
        this.createdCompanySummary.set(created);
        this.startAlterCompany(created);
        this.postCreateF11ModalOpen.set(true);
      }
    } catch (err: unknown) {
      this.formError.set(err instanceof Error ? err.message : 'Failed to save company.');
    }
  }

  closePostCreateF11Modal(): void {
    this.postCreateF11ModalOpen.set(false);
    this.activeTab.set('list');
  }

  async confirmPostCreateFeatures(): Promise<void> {
    await this.saveCompanyForm();
    if (!this.formError()) {
      this.postCreateF11ModalOpen.set(false);
      await this.router.navigateByUrl('/app/gateway');
    }
  }

  openPeriodsTab(): void {
    this.quickPeriodFrom = this.companyContext.periodFrom();
    this.quickPeriodTo = this.companyContext.periodTo();
    this.activeTab.set('periods');
    const active = this.companyContext.activeCompany();
    if (active) {
      void this.companyContext.loadFiscalPeriods(active.id);
    }
  }

  applyQuickPeriod(): void {
    try {
      this.companyContext.setPeriodRange(this.quickPeriodFrom, this.quickPeriodTo);
      this.feedbackMessage.set(
        `Updated active reporting period to ${this.quickPeriodFrom} .. ${this.quickPeriodTo}.`,
      );
    } catch (err: unknown) {
      this.formError.set(err instanceof Error ? err.message : 'Invalid period range');
    }
  }

  async transitionPeriod(periodId: string, state: FiscalPeriodState): Promise<void> {
    const active = this.companyContext.activeCompany();
    if (!active) return;
    try {
      await this.companyContext.changePeriodState(active.id, periodId, state);
      this.feedbackMessage.set(`Period state transitioned to ${state}.`);
    } catch (err: unknown) {
      this.formError.set(err instanceof Error ? err.message : 'Period transition failed.');
    }
  }
}
