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
import { RouterLink } from '@angular/router';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { INDIAN_GST_STATES, validateGstin } from '../../core/validation/gstin-validator';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';
import { CompanyContextStore } from '../../state/company-context.store';
import {
  GstRegistrationKind,
  GstRegistrationMaster,
  Gstr1Periodicity,
  StatutoryRulePackDto,
  UpsertGstRegistrationRequest,
} from './statutory.models';
import { StatutoryService } from './statutory.service';

@Component({
  selector: 'mc-gst-registration-workbench',
  standalone: true,
  imports: [FormsModule, RouterLink, CodeBadgeComponent, GridCellNavDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="gst-reg-layout">
      <section class="mc-card reg-list-card" aria-label="Multi-GSTIN Registrations">
        <header class="card-header">
          <div>
            <h1>GST Registrations (Multi-GSTIN)</h1>
            <p class="subtitle">
              State-wise regular/SEZ/ISD GSTINs, LUT/Bond validity &amp; e-Invoice / e-Way Bill rules
            </p>
          </div>
          <div class="header-btns">
            <button type="button" class="mc-btn" (click)="loadRulePacks()">
              Rule Packs
            </button>
            <button
              type="button"
              class="mc-btn mc-btn-primary"
              data-testid="new-gst-reg-btn"
              (click)="resetForCreate()"
            >
              + New GSTIN
            </button>
          </div>
        </header>

        @if (rulePacks().length > 0) {
          <div class="rule-pack-banner" data-testid="active-rule-packs">
            <strong>Date-Effective Rule Pack:</strong>
            @for (pack of rulePacks(); track pack.id) {
              <span class="pack-chip">
                <mc-code-badge [code]="pack.packCode" />
                from {{ pack.effectiveFrom }}
                @if (pack.b2clThreshold) {
                  &bull; B2CL Threshold:
                  <span class="mc-amount">&#x20B9; {{ pack.b2clThreshold }}</span>
                }
                @if (pack.gstSlabs && pack.gstSlabs.length > 0) {
                  &bull; Slabs: {{ pack.gstSlabs.join('%, ') }}%
                }
              </span>
            }
          </div>
        }

        @if (loading()) {
          <div class="empty-msg">Loading company GST registrations...</div>
        } @else if (registrations().length === 0) {
          <div class="empty-msg">
            No Multi-GSTIN registrations configured yet. Create your primary state GSTIN on the right.
          </div>
        } @else {
          <table class="mc-table" data-testid="gst-registrations-table">
            <thead>
              <tr>
                <th>State</th>
                <th>GSTIN</th>
                <th>Type</th>
                <th>GSTR-1</th>
                <th>e-Inv / EWB</th>
                <th>Effective From</th>
              </tr>
            </thead>
            <tbody>
              @for (reg of registrations(); track reg.id) {
                <tr
                  class="clickable-row"
                  [class.row-selected]="editingId() === reg.id"
                  (click)="selectForEdit(reg)"
                >
                  <td>
                    <strong>{{ reg.stateCode }}</strong> — {{ stateNameFor(reg.stateCode) }}
                    @if (reg.isPrimary) {
                      <span class="primary-pill">Primary</span>
                    }
                  </td>
                  <td><mc-code-badge [code]="reg.gstin" /></td>
                  <td>{{ reg.registrationType }}</td>
                  <td>{{ reg.gstr1Periodicity }}</td>
                  <td>
                    {{ reg.eInvoiceApplicable ? 'e-Inv' : '—' }} /
                    {{ reg.eWayBillApplicable ? 'EWB' : '—' }}
                  </td>
                  <td>{{ reg.effectiveFrom }}</td>
                </tr>
              }
            </tbody>
          </table>
        }

        <footer class="card-footer">
          <a routerLink="/app/statutory/gstr1" class="mc-btn">GSTR-1</a>
          <a routerLink="/app/statutory/gstr3b" class="mc-btn">GSTR-3B</a>
          <a routerLink="/app/statutory/ims" class="mc-btn">IMS Reconciliation</a>
          <a routerLink="/app/masters" class="mc-btn">Back to Masters</a>
        </footer>
      </section>

      <section class="mc-card reg-form-card" aria-label="GST Registration Editor" mcGridCellNav>
        <header class="card-header">
          <h2>
            {{ editingId() ? 'GST Registration Alteration' : 'GST Registration Creation' }}
          </h2>
          <span class="save-hint">Ctrl+A = Accept</span>
        </header>

        @if (feedbackMessage(); as msg) {
          <div class="feedback-banner" [class.feedback-err]="feedbackIsError()" role="status">
            {{ msg }}
          </div>
        }

        <form class="reg-form" (ngSubmit)="save()">
          <div class="form-row">
            <label for="gst-state">State / UT</label>
            <select
              id="gst-state"
              name="stateCode"
              class="mc-input"
              data-mc-grid-cell="true"
              [ngModel]="stateCode()"
              (ngModelChange)="stateCode.set($event)"
            >
              @for (st of states; track st.code) {
                <option [value]="st.code">{{ st.code }} — {{ st.name }}</option>
              }
            </select>
          </div>

          <div class="form-row">
            <label for="gst-number">GSTIN (15-char)</label>
            <input
              id="gst-number"
              name="gstin"
              type="text"
              maxlength="15"
              class="mc-input mc-code"
              data-mc-grid-cell="true"
              data-testid="gstin-input"
              [ngModel]="gstin()"
              (ngModelChange)="gstin.set($event.toUpperCase())"
              placeholder="33AABCM1234F1Z4"
            />
            @if (gstin().trim().length > 0) {
              @if (gstinValidation().valid) {
                <span class="valid-hint">
                  Mod-36 Checksum Valid &bull; PAN:
                  <span class="mc-code">{{ gstinValidation().pan }}</span>
                </span>
              } @else {
                <span class="invalid-hint">{{ gstinValidation().error }}</span>
              }
            }
          </div>

          <div class="two-col">
            <div class="form-row">
              <label for="gst-reg-type">Registration Type</label>
              <select
                id="gst-reg-type"
                name="registrationType"
                class="mc-input"
                data-mc-grid-cell="true"
                [ngModel]="registrationType()"
                (ngModelChange)="registrationType.set($event)"
              >
                <option value="REGULAR">Regular</option>
                <option value="COMPOSITION">Composition</option>
                <option value="ISD">Input Service Distributor (ISD)</option>
                <option value="SEZ">SEZ Unit / Developer</option>
              </select>
            </div>

            <div class="form-row">
              <label for="gst-periodicity">GSTR-1 Periodicity</label>
              <select
                id="gst-periodicity"
                name="gstr1Periodicity"
                class="mc-input"
                data-mc-grid-cell="true"
                [ngModel]="gstr1Periodicity()"
                (ngModelChange)="gstr1Periodicity.set($event)"
              >
                <option value="MONTHLY">Monthly</option>
                <option value="QUARTERLY">Quarterly (QRMP)</option>
              </select>
            </div>
          </div>

          <div class="two-col">
            <div class="form-row">
              <label for="gst-eff-from">Applicable From</label>
              <input
                id="gst-eff-from"
                name="effectiveFrom"
                type="date"
                class="mc-input"
                data-mc-grid-cell="true"
                [ngModel]="effectiveFrom()"
                (ngModelChange)="effectiveFrom.set($event)"
              />
            </div>

            <div class="form-row">
              <label for="gst-lut">LUT / Bond No. (Exports)</label>
              <input
                id="gst-lut"
                name="lutBondNumber"
                type="text"
                class="mc-input mc-code"
                data-mc-grid-cell="true"
                [ngModel]="lutBondNumber()"
                (ngModelChange)="lutBondNumber.set($event)"
                placeholder="AD3304250001234"
              />
            </div>
          </div>

          <div class="two-col">
            <label class="checkbox-row">
              <input
                type="checkbox"
                name="eInvoiceApplicable"
                [ngModel]="eInvoiceApplicable()"
                (ngModelChange)="eInvoiceApplicable.set($event)"
              />
              <span>e-Invoice Applicable</span>
            </label>

            <label class="checkbox-row">
              <input
                type="checkbox"
                name="eWayBillApplicable"
                [ngModel]="eWayBillApplicable()"
                (ngModelChange)="eWayBillApplicable.set($event)"
              />
              <span>e-Way Bill Applicable</span>
            </label>
          </div>

          <div class="two-col">
            <div class="form-row">
              <label for="ewb-threshold">e-Way Bill Threshold (&#x20B9;)</label>
              <input
                id="ewb-threshold"
                name="eWayBillThreshold"
                type="text"
                class="mc-input mc-amount"
                data-mc-grid-cell="true"
                [ngModel]="eWayBillThreshold()"
                (ngModelChange)="eWayBillThreshold.set($event)"
              />
            </div>

            <label class="checkbox-row align-end">
              <input
                type="checkbox"
                name="isPrimary"
                [ngModel]="isPrimary()"
                (ngModelChange)="isPrimary.set($event)"
              />
              <span>Primary Company GSTIN</span>
            </label>
          </div>

          <div class="form-actions">
            <button
              type="submit"
              class="mc-btn mc-btn-primary"
              data-testid="save-gst-reg-btn"
              [disabled]="saving()"
            >
              Accept (Ctrl+A)
            </button>
          </div>
        </form>
      </section>
    </div>
  `,
  styles: `
    .gst-reg-layout {
      display: grid;
      grid-template-columns: minmax(0, 1.25fr) minmax(340px, 0.95fr);
      gap: 1rem;
      align-items: start;
    }

    @media (max-width: 980px) {
      .gst-reg-layout {
        grid-template-columns: 1fr;
      }
    }

    .reg-list-card,
    .reg-form-card {
      overflow: hidden;
    }

    .card-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      padding: 0.75rem 1rem;
      background: #142f42;
      color: #ffffff;
    }

    .card-header h1,
    .card-header h2 {
      margin: 0;
      font-size: 0.98rem;
      font-weight: 700;
      color: #ffffff;
    }

    .subtitle {
      margin: 0.15rem 0 0;
      font-size: 0.76rem;
      color: #cfe2eb;
      font-weight: 400;
    }

    .header-btns {
      display: flex;
      gap: 0.45rem;
    }

    .save-hint {
      font-size: 0.75rem;
      color: #4ce1bd;
      font-weight: 600;
    }

    .rule-pack-banner {
      padding: 0.55rem 1rem;
      background: #eaf7f5;
      border-bottom: 1px solid #cbe4e0;
      font-size: 0.78rem;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
      color: #142f42;
    }

    .pack-chip {
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
    }

    .empty-msg {
      padding: 1.5rem 1rem;
      font-size: 0.85rem;
      color: #4a6270;
      font-weight: 500;
    }

    .clickable-row {
      cursor: pointer;
    }

    .row-selected {
      background: #eaf7f5;
    }

    .primary-pill {
      margin-left: 0.4rem;
      padding: 0.08rem 0.4rem;
      border-radius: 999px;
      background: #dff6ef;
      color: #006d68;
      font-size: 0.68rem;
      font-weight: 700;
    }

    .card-footer {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      padding: 0.65rem 1rem;
      background: #f6faf9;
      border-top: 1px solid #d4e2e0;
    }

    .feedback-banner {
      margin: 0.75rem 1rem 0;
      padding: 0.5rem 0.75rem;
      border-radius: 6px;
      background: #ebfbf5;
      border: 1px solid #22b69c;
      color: #006d68;
      font-size: 0.82rem;
      font-weight: 600;
    }

    .feedback-err {
      background: #fdecec;
      border-color: #d64545;
      color: #8f1d1d;
    }

    .reg-form {
      padding: 0.9rem 1rem 1.1rem;
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }

    .form-row {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }

    .form-row label {
      font-size: 0.76rem;
      font-weight: 600;
      color: #142f42;
    }

    .two-col {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.75rem;
    }

    .checkbox-row {
      display: flex;
      align-items: center;
      gap: 0.45rem;
      font-size: 0.82rem;
      font-weight: 600;
      color: #142f42;
      cursor: pointer;
    }

    .align-end {
      align-self: end;
      padding-bottom: 0.4rem;
    }

    .valid-hint {
      font-size: 0.74rem;
      color: #006d68;
      font-weight: 600;
    }

    .invalid-hint {
      font-size: 0.74rem;
      color: #9e1c1c;
      font-weight: 600;
    }

    .form-actions {
      display: flex;
      justify-content: flex-end;
      padding-top: 0.35rem;
    }
  `,
})
export class GstRegistrationWorkbenchComponent implements OnInit {
  private readonly statutoryService = inject(StatutoryService);
  private readonly companyContext = inject(CompanyContextStore);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly destroyRef = inject(DestroyRef);

  readonly states = INDIAN_GST_STATES;

  readonly loading = signal<boolean>(false);
  readonly saving = signal<boolean>(false);
  readonly registrations = signal<readonly GstRegistrationMaster[]>([]);
  readonly rulePacks = signal<readonly StatutoryRulePackDto[]>([]);

  readonly editingId = signal<string | null>(null);
  readonly editingVersionNo = signal<number | undefined>(undefined);

  readonly stateCode = signal<string>('33');
  readonly gstin = signal<string>('');
  readonly registrationType = signal<GstRegistrationKind>('REGULAR');
  readonly gstr1Periodicity = signal<Gstr1Periodicity>('MONTHLY');
  readonly effectiveFrom = signal<string>('2025-04-01');
  readonly lutBondNumber = signal<string>('');
  readonly eInvoiceApplicable = signal<boolean>(true);
  readonly eWayBillApplicable = signal<boolean>(true);
  readonly eWayBillThreshold = signal<string>('50000.00');
  readonly isPrimary = signal<boolean>(false);

  readonly feedbackMessage = signal<string | null>(null);
  readonly feedbackIsError = signal<boolean>(false);

  readonly gstinValidation = computed(() => validateGstin(this.gstin(), this.stateCode()));

  constructor() {
    const unbindAccept = this.shortcutRegistry.bindHandler('screen.accept', 'SCREEN', () => {
      void this.save();
    });
    this.destroyRef.onDestroy(() => unbindAccept());
  }

  ngOnInit(): void {
    const active = this.companyContext.activeCompany();
    if (active) {
      this.stateCode.set(active.stateCode || '33');
      this.effectiveFrom.set(active.financialYearFrom || '2025-04-01');
      void this.reloadRegistrations();
    }
  }

  stateNameFor(code: string): string {
    return INDIAN_GST_STATES.find((s) => s.code === code)?.name ?? code;
  }

  async reloadRegistrations(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) {
      return;
    }
    this.loading.set(true);
    try {
      const list = await this.statutoryService.listGstRegistrations(companyId);
      this.registrations.set(list);
    } catch {
      this.registrations.set([]);
    } finally {
      this.loading.set(false);
    }
  }

  async loadRulePacks(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) {
      return;
    }
    try {
      const packs = await this.statutoryService.listRulePacks(
        companyId,
        this.companyContext.workingDate(),
      );
      this.rulePacks.set(packs);
    } catch {
      this.rulePacks.set([]);
    }
  }

  resetForCreate(): void {
    const active = this.companyContext.activeCompany();
    this.editingId.set(null);
    this.editingVersionNo.set(undefined);
    this.stateCode.set(active?.stateCode ?? '33');
    this.gstin.set('');
    this.registrationType.set('REGULAR');
    this.gstr1Periodicity.set('MONTHLY');
    this.effectiveFrom.set(active?.financialYearFrom ?? '2025-04-01');
    this.lutBondNumber.set('');
    this.eInvoiceApplicable.set(true);
    this.eWayBillApplicable.set(true);
    this.eWayBillThreshold.set('50000.00');
    this.isPrimary.set(this.registrations().length === 0);
    this.feedbackMessage.set(null);
  }

  selectForEdit(reg: GstRegistrationMaster): void {
    this.editingId.set(reg.id);
    this.editingVersionNo.set(reg.versionNo);
    this.stateCode.set(reg.stateCode);
    this.gstin.set(reg.gstin);
    this.registrationType.set(reg.registrationType);
    this.gstr1Periodicity.set(reg.gstr1Periodicity);
    this.effectiveFrom.set(reg.effectiveFrom);
    this.lutBondNumber.set(reg.lutBondNumber ?? '');
    this.eInvoiceApplicable.set(reg.eInvoiceApplicable);
    this.eWayBillApplicable.set(reg.eWayBillApplicable);
    this.eWayBillThreshold.set(reg.eWayBillThreshold ?? '50000.00');
    this.isPrimary.set(reg.isPrimary);
    this.feedbackMessage.set(null);
  }

  async save(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId || this.saving()) {
      return;
    }

    const validation = this.gstinValidation();
    if (!validation.valid) {
      this.feedbackIsError.set(true);
      this.feedbackMessage.set(validation.error ?? 'Enter a valid 15-character GSTIN.');
      return;
    }

    const payload: UpsertGstRegistrationRequest = {
      gstin: validation.normalized,
      stateCode: this.stateCode(),
      stateName: this.stateNameFor(this.stateCode()),
      registrationType: this.registrationType(),
      effectiveFrom: this.effectiveFrom(),
      gstr1Periodicity: this.gstr1Periodicity(),
      lutBondNumber: this.lutBondNumber().trim() || null,
      eInvoiceApplicable: this.eInvoiceApplicable(),
      eInvoiceEffectiveFrom: this.eInvoiceApplicable() ? this.effectiveFrom() : null,
      eWayBillApplicable: this.eWayBillApplicable(),
      eWayBillThreshold: this.eWayBillThreshold().trim() || '50000.00',
      isPrimary: this.isPrimary(),
      active: true,
      versionNo: this.editingVersionNo(),
    };

    this.saving.set(true);
    try {
      const id = this.editingId();
      if (id) {
        const updated = await this.statutoryService.updateGstRegistration(
          companyId,
          id,
          payload,
          this.editingVersionNo(),
        );
        this.registrations.update((list) => list.map((r) => (r.id === id ? updated : r)));
        this.selectForEdit(updated);
        this.feedbackIsError.set(false);
        this.feedbackMessage.set(`Updated GSTIN ${updated.gstin}.`);
      } else {
        const created = await this.statutoryService.createGstRegistration(companyId, payload);
        this.registrations.update((list) => [...list, created]);
        this.selectForEdit(created);
        this.feedbackIsError.set(false);
        this.feedbackMessage.set(`Registered GSTIN ${created.gstin}.`);
      }
    } catch {
      this.feedbackIsError.set(true);
      this.feedbackMessage.set('Failed to save GST registration.');
    } finally {
      this.saving.set(false);
    }
  }
}
