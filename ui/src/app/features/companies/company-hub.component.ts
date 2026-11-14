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
import { ActivatedRoute } from '@angular/router';
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
            <div class="mc-form-section-title">F11: Company Features (Accounting, Inventory, Statutory &amp; MCA Audit)</div>

            <div class="mc-f11-columns">
              <div class="mc-f11-group">
                <h3>Accounting &amp; Inventory</h3>
                <label class="mc-toggle-line">
                  <input type="checkbox" name="maintainAccounts" [(ngModel)]="featMaintainAccounts" />
                  <span>Maintain Accounts</span>
                </label>
                <label class="mc-toggle-line">
                  <input type="checkbox" name="billWiseEnabled" [(ngModel)]="featBillWise" />
                  <span>Enable Bill-wise entry</span>
                </label>
                <label class="mc-toggle-line">
                  <input type="checkbox" name="costCentresEnabled" [(ngModel)]="featCostCentres" />
                  <span>Enable Cost Centres</span>
                </label>
                <label class="mc-toggle-line">
                  <input type="checkbox" name="maintainInventory" [(ngModel)]="featMaintainInventory" />
                  <span>Maintain Inventory</span>
                </label>
                <label class="mc-toggle-line">
                  <input type="checkbox" name="inventoryIntegrated" [(ngModel)]="featInventoryIntegrated" />
                  <span>Integrate Accounts with Inventory</span>
                </label>
              </div>

              <div class="mc-f11-group">
                <h3>Statutory, Tax &amp; MCA Edit Log</h3>
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
              type="submit"
              class="mc-btn mc-btn-primary"
              [disabled]="companyContext.quarantined()"
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
    </div>
  `,
  styles: `
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
  private readonly destroyRef = inject(DestroyRef);

  readonly states = INDIAN_GST_STATES;

  readonly activeTab = signal<'list' | 'form' | 'periods'>('list');
  readonly editingCompanyId = signal<string | null>(null);
  readonly mcaEditLogLocked = signal<boolean>(false);
  readonly formError = signal<string | null>(null);
  readonly feedbackMessage = signal<string | null>(null);

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
  featMaintainInventory = true;
  featInventoryIntegrated = true;
  readonly featGstEnabled = signal<boolean>(true);
  featMultiGstin = false;
  featGstRegType: 'REGULAR' | 'COMPOSITION' | 'ISD' | 'SEZ' = 'REGULAR';
  readonly featGstin = signal<string>('');
  featGstr1Periodicity: 'MONTHLY' | 'QUARTERLY' = 'MONTHLY';
  featTdsEnabled = true;
  featMcaEditLog = true;

  quickPeriodFrom = '2025-04-01';
  quickPeriodTo = '2026-03-31';

  readonly gstinValidation = computed(() =>
    validateGstin(this.featGstin(), this.formStateCode()),
  );

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
    this.featMaintainInventory = true;
    this.featInventoryIntegrated = true;
    this.featGstEnabled.set(true);
    this.featGstin.set('');
    this.featTdsEnabled = true;
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
    this.featMaintainInventory = comp.features.maintainInventory;
    this.featInventoryIntegrated = comp.features.inventoryIntegrated;
    this.featGstEnabled.set(comp.features.gstEnabled);
    this.featMultiGstin = comp.features.multiGstinEnabled;
    this.featGstRegType = comp.features.gstRegistrationType;
    this.featGstin.set(comp.features.gstin);
    this.featGstr1Periodicity = comp.features.gstr1Periodicity;
    this.featTdsEnabled = comp.features.tdsEnabled;
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

    const payload: CreateOrUpdateCompanyPayload = {
      code: this.formCode.trim().toUpperCase(),
      legalName: this.formLegalName.trim(),
      mailingName: (this.formMailingName || this.formLegalName).trim(),
      countryCode: this.formCountryCode.trim().toUpperCase(),
      stateCode: this.formStateCode(),
      pinCode: this.formPinCode.trim(),
      baseCurrencyCode: this.formCurrencyCode.trim().toUpperCase(),
      currencySymbol: this.formCurrencyCode.trim().toUpperCase() === 'INR' ? '\u20B9' : this.formCurrencyCode.trim(),
      currencyScale: Math.min(6, Math.max(0, Math.trunc(this.formCurrencyScale))),
      financialYearFrom: this.formFyFrom,
      booksBeginningFrom: this.formBooksFrom,
      features: {
        maintainAccounts: this.featMaintainAccounts,
        billWiseEnabled: this.featBillWise,
        costCentresEnabled: this.featCostCentres,
        maintainInventory: this.featMaintainInventory,
        inventoryIntegrated: this.featInventoryIntegrated,
        gstEnabled: this.featGstEnabled(),
        multiGstinEnabled: this.featMultiGstin,
        gstRegistrationType: this.featGstRegType,
        gstin: this.featGstin().trim().toUpperCase(),
        gstr1Periodicity: this.featGstr1Periodicity,
        gstEffectiveFrom: this.formFyFrom,
        tdsEnabled: this.featTdsEnabled,
        mcaEditLogEnforced: this.mcaEditLogLocked() ? true : this.featMcaEditLog,
      },
    };

    try {
      const editId = this.editingCompanyId();
      if (editId) {
        await this.companyContext.updateCompany(editId, payload);
        this.feedbackMessage.set(`Updated company "${payload.legalName}".`);
      } else {
        await this.companyContext.createCompany(payload);
        this.feedbackMessage.set(`Created company "${payload.legalName}" with 28 predefined groups.`);
      }
      this.activeTab.set('list');
    } catch (err: unknown) {
      this.formError.set(err instanceof Error ? err.message : 'Failed to save company.');
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
