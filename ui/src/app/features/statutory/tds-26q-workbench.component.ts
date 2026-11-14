import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { ExactDecimal } from '../../core/money/exact-decimal';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { CompanyContextStore } from '../../state/company-context.store';
import {
  TdsForm26qReportDto,
  TdsNatureDto,
  TdsQuarter,
} from './statutory.models';
import { StatutoryService } from './statutory.service';

@Component({
  selector: 'mc-tds-26q-workbench',
  standalone: true,
  imports: [FormsModule, RouterLink, IndianCurrencyPipe, CodeBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tds-page">
      <header class="mc-card tds-header">
        <div>
          <span class="return-pill">Income Tax TDS &bull; Form 26Q &amp; Protean FVU 1.2</span>
          <h1>TDS Form 26Q Return &amp; ITNS 281 Challan Workbench</h1>
          <p class="subtitle">
            Sections 194C / 194J / 194H / 194I / 194Q &bull; Automatic Sec 206AA 20% No-PAN
            enforcement
          </p>
        </div>

        <div class="header-controls">
          <div class="quarter-pills" role="group" aria-label="Select TDS Quarter">
            @for (q of quarters; track q) {
              <button
                type="button"
                class="q-pill"
                [class.q-active]="quarter() === q"
                [attr.data-testid]="'tds-quarter-' + q"
                (click)="selectQuarter(q)"
              >
                {{ q }}
              </button>
            }
          </div>

          <button type="button" class="mc-btn" (click)="toggleNaturesPanel()">
            Section Rates
          </button>

          <button
            type="button"
            class="mc-btn"
            data-testid="tds-open-challan-modal-btn"
            (click)="openChallanModal()"
          >
            + Record ITNS 281 Challan
          </button>

          <button
            type="button"
            class="mc-btn mc-btn-primary"
            data-testid="tds-export-fvu-btn"
            (click)="exportFvu()"
          >
            Export Protean FVU 1.2 (26Q)
          </button>
        </div>
      </header>

      @if (bannerMessage(); as msg) {
        <div class="status-banner" data-testid="tds-status-banner">{{ msg }}</div>
      }

      @if (naturesOpen() && natures().length > 0) {
        <section class="mc-card section-card" data-testid="tds-natures-panel">
          <header class="card-subhead">
            <h2>Active TDS Nature of Payment Thresholds &amp; Rates</h2>
          </header>
          <table class="mc-table">
            <thead>
              <tr>
                <th>Section</th>
                <th>Description</th>
                <th class="num-col">Single Bill Limit</th>
                <th class="num-col">Annual Limit</th>
                <th class="num-col">Ind / HUF %</th>
                <th class="num-col">Company / Firm %</th>
                <th class="num-col">No-PAN (206AA) %</th>
              </tr>
            </thead>
            <tbody>
              @for (n of natures(); track n.sectionCode) {
                <tr>
                  <td><mc-code-badge [code]="n.sectionCode" /></td>
                  <td>{{ n.description }}</td>
                  <td class="mc-amount num-col">{{ n.singleBillThreshold | mcIndianCurrency }}</td>
                  <td class="mc-amount num-col">{{ n.annualAggregateThreshold | mcIndianCurrency }}</td>
                  <td class="mc-amount num-col">{{ n.rateIndividualHufPct }}%</td>
                  <td class="mc-amount num-col">{{ n.rateOtherDeducteePct }}%</td>
                  <td class="mc-amount num-col">{{ n.noPanHigherRatePct }}%</td>
                </tr>
              }
            </tbody>
          </table>
        </section>
      }

      @if (loading()) {
        <div class="mc-card state-box">Loading Form 26Q deductions and challans...</div>
      } @else if (report(); as r) {
        <section class="kpi-grid" aria-label="Form 26Q Totals">
          <article class="mc-card kpi-card">
            <span class="kpi-label">Total Gross Paid / Credited ({{ r.quarter }})</span>
            <span class="mc-amount kpi-val" data-testid="tds-total-gross">
              {{ r.totalGrossAmount | mcIndianCurrency }}
            </span>
          </article>
          <article class="mc-card kpi-card">
            <span class="kpi-label">Total TDS Deducted</span>
            <span class="mc-amount kpi-val kpi-teal" data-testid="tds-total-deducted">
              {{ r.totalTdsDeducted | mcIndianCurrency }}
            </span>
          </article>
          <article class="mc-card kpi-card">
            <span class="kpi-label">Total Deposited (ITNS 281)</span>
            <span class="mc-amount kpi-val" data-testid="tds-total-deposited">
              {{ r.totalTdsDeposited | mcIndianCurrency }}
            </span>
          </article>
          <article class="mc-card kpi-card">
            <span class="kpi-label">Unpaid / Balance TDS</span>
            <span class="mc-amount kpi-val" data-testid="tds-unpaid-balance">
              {{ r.unpaidTdsAmount | mcIndianCurrency }}
            </span>
          </article>
        </section>

        <section class="mc-card section-card">
          <header class="card-subhead">
            <h2>Annexure I — Deductee Wise TDS Schedule ({{ r.quarter }})</h2>
            <a routerLink="/app/statutory/gstr3b" class="mc-btn">GSTR-3B</a>
          </header>
          <table class="mc-table" data-testid="tds-deductions-table">
            <thead>
              <tr>
                <th>Date</th>
                <th>Section</th>
                <th>Deductee Name</th>
                <th>PAN / Sec 206AA Status</th>
                <th class="num-col">Gross Amount</th>
                <th class="num-col">Rate %</th>
                <th class="num-col">TDS Deducted</th>
              </tr>
            </thead>
            <tbody>
              @for (d of r.deductions; track (d.voucherId ?? '') + d.deducteeName + d.deductionDate) {
                <tr>
                  <td>{{ d.deductionDate }}</td>
                  <td><mc-code-badge [code]="d.sectionCode" /></td>
                  <td><strong>{{ d.deducteeName }}</strong></td>
                  <td>
                    @if (d.deducteePan && d.hasValidPan && !d.higherRate206aaApplied) {
                      <mc-code-badge [code]="d.deducteePan" />
                    } @else {
                      <span class="nopan-badge" data-testid="tds-206aa-badge">
                        20% No-PAN (Sec 206AA)
                      </span>
                    }
                  </td>
                  <td class="mc-amount num-col">{{ d.grossAmount | mcIndianCurrency }}</td>
                  <td class="mc-amount num-col">{{ d.tdsRatePct }}%</td>
                  <td class="mc-amount num-col"><strong>{{ d.tdsAmount | mcIndianCurrency }}</strong></td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="7" class="empty-cell">No TDS deductions recorded in {{ r.quarter }}.</td>
                </tr>
              }
            </tbody>
          </table>
        </section>

        <section class="mc-card section-card">
          <header class="card-subhead">
            <h2>ITNS 281 Bank Challans Mapped to {{ r.quarter }}</h2>
          </header>
          <table class="mc-table" data-testid="tds-challans-table">
            <thead>
              <tr>
                <th>Deposit Date</th>
                <th>Section</th>
                <th>BSR Code (7-Digit)</th>
                <th>Challan Serial (5-Digit)</th>
                <th class="num-col">TDS Amount</th>
                <th class="num-col">Total Deposited</th>
              </tr>
            </thead>
            <tbody>
              @for (c of r.challans; track c.id) {
                <tr>
                  <td>{{ c.depositDate }}</td>
                  <td><mc-code-badge [code]="c.sectionCode" /></td>
                  <td class="mc-code">{{ c.bsrCode }}</td>
                  <td class="mc-code">{{ c.challanSerialNo }}</td>
                  <td class="mc-amount num-col">{{ c.tdsAmount | mcIndianCurrency }}</td>
                  <td class="mc-amount num-col"><strong>{{ c.totalAmount | mcIndianCurrency }}</strong></td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="6" class="empty-cell">No ITNS 281 challans recorded for {{ r.quarter }}.</td>
                </tr>
              }
            </tbody>
          </table>
        </section>
      }

      <!-- Record ITNS 281 Challan Modal -->
      @if (challanModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Record ITNS 281 TDS Challan"
          data-testid="tds-challan-modal"
          (keydown.escape)="challanModalOpen.set(false)"
        >
          <div class="mc-panel modal-dialog">
            <header class="dialog-head">
              <h2>Record ITNS 281 TDS Deposit Challan ({{ quarter() }})</h2>
              <button type="button" class="mc-btn" (click)="challanModalOpen.set(false)">
                Esc Close
              </button>
            </header>

            @if (challanError(); as err) {
              <div class="modal-error" data-testid="tds-challan-error" role="alert">{{ err }}</div>
            }

            <form class="dialog-form" (ngSubmit)="submitChallan()">
              <div class="two-col">
                <div class="field-group">
                  <label for="ch-section">Section Code</label>
                  <select
                    id="ch-section"
                    name="sectionCode"
                    class="mc-input"
                    data-testid="challan-section-select"
                    [ngModel]="challanSection()"
                    (ngModelChange)="challanSection.set($event)"
                  >
                    <option value="194J">194J — Professional / Technical Fees</option>
                    <option value="194C">194C — Contractor Payments</option>
                    <option value="194H">194H — Commission / Brokerage</option>
                    <option value="194I">194I — Rent</option>
                    <option value="194Q">194Q — Purchase of Goods</option>
                  </select>
                </div>

                <div class="field-group">
                  <label for="ch-date">Bank Deposit Date</label>
                  <input
                    id="ch-date"
                    name="depositDate"
                    type="date"
                    class="mc-input"
                    data-testid="challan-date-input"
                    [ngModel]="challanDate()"
                    (ngModelChange)="challanDate.set($event)"
                  />
                </div>
              </div>

              <div class="two-col">
                <div class="field-group">
                  <label for="ch-bsr">7-Digit Bank BSR Code</label>
                  <input
                    id="ch-bsr"
                    name="bsrCode"
                    type="text"
                    maxlength="7"
                    class="mc-input mc-code"
                    data-testid="challan-bsr-input"
                    [ngModel]="challanBsrCode()"
                    (ngModelChange)="challanBsrCode.set($event)"
                    placeholder="0510308"
                  />
                </div>

                <div class="field-group">
                  <label for="ch-serial">5-Digit Challan Serial No</label>
                  <input
                    id="ch-serial"
                    name="challanSerialNo"
                    type="text"
                    maxlength="5"
                    class="mc-input mc-code"
                    data-testid="challan-serial-input"
                    [ngModel]="challanSerialNo()"
                    (ngModelChange)="challanSerialNo.set($event)"
                    placeholder="00412"
                  />
                </div>
              </div>

              <div class="field-group">
                <label for="ch-amount">TDS Amount Deposited (&#x20B9;)</label>
                <input
                  id="ch-amount"
                  name="tdsAmount"
                  type="text"
                  class="mc-input mc-amount"
                  data-testid="challan-amount-input"
                  [ngModel]="challanTdsAmount()"
                  (ngModelChange)="challanTdsAmount.set($event)"
                  placeholder="4500.00"
                />
              </div>

              <div class="dialog-actions">
                <button type="button" class="mc-btn" (click)="challanModalOpen.set(false)">
                  Cancel
                </button>
                <button
                  type="submit"
                  class="mc-btn mc-btn-primary"
                  data-testid="challan-submit-btn"
                >
                  Save ITNS 281 Challan (Ctrl+A)
                </button>
              </div>
            </form>
          </div>
        </div>
      }
    </div>
  `,
  styles: `
    .tds-page {
      display: flex;
      flex-direction: column;
      gap: 0.85rem;
    }

    .tds-header {
      padding: 0.85rem 1rem;
      background: #142f42;
      color: #ffffff;
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 0.75rem;
    }

    .tds-header h1 {
      margin: 0.2rem 0;
      font-size: 1.03rem;
      font-weight: 700;
      color: #ffffff;
    }

    .return-pill {
      font-size: 0.72rem;
      font-weight: 700;
      color: #4ce1bd;
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }

    .subtitle {
      margin: 0;
      font-size: 0.76rem;
      color: #cfe2eb;
    }

    .header-controls {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.5rem;
    }

    .quarter-pills {
      display: flex;
      gap: 0.25rem;
      background: rgba(255, 255, 255, 0.1);
      padding: 0.2rem;
      border-radius: 6px;
    }

    .q-pill {
      border: none;
      background: transparent;
      color: #d3e5ee;
      padding: 0.28rem 0.65rem;
      border-radius: 4px;
      font-weight: 700;
      font-size: 0.78rem;
      cursor: pointer;
    }

    .q-active {
      background: #008d87;
      color: #ffffff;
    }

    .status-banner {
      padding: 0.55rem 0.9rem;
      border-radius: 6px;
      background: #ebfbf5;
      border: 1px solid #22b69c;
      color: #006d68;
      font-size: 0.82rem;
      font-weight: 600;
    }

    .kpi-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(205px, 1fr));
      gap: 0.65rem;
    }

    .kpi-card {
      padding: 0.7rem 0.9rem;
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }

    .kpi-label {
      font-size: 0.75rem;
      font-weight: 600;
      color: #4f6775;
    }

    .kpi-val {
      font-size: 1.04rem;
      font-weight: 700;
      color: #142f42;
    }

    .kpi-teal {
      color: #006d68;
    }

    .section-card {
      overflow: hidden;
    }

    .card-subhead {
      padding: 0.65rem 1rem;
      background: #f6faf9;
      border-bottom: 1px solid #d4e2e0;
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .card-subhead h2 {
      margin: 0;
      font-size: 0.88rem;
      font-weight: 700;
      color: #142f42;
    }

    .nopan-badge {
      display: inline-block;
      padding: 0.1rem 0.45rem;
      border-radius: 999px;
      background: #fde2e2;
      color: #9e1c1c;
      font-size: 0.7rem;
      font-weight: 700;
    }

    .num-col {
      text-align: right;
    }

    .empty-cell {
      text-align: center;
      padding: 1.1rem;
      color: #5b7280;
    }

    .state-box {
      padding: 1.5rem;
      font-weight: 600;
      color: #142f42;
    }

    .modal-dialog {
      width: min(560px, 94vw);
      background: #ffffff;
      overflow: hidden;
    }

    .dialog-head {
      padding: 0.75rem 1rem;
      background: #142f42;
      color: #ffffff;
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .dialog-head h2 {
      margin: 0;
      font-size: 0.92rem;
      font-weight: 700;
      color: #ffffff;
    }

    .modal-error {
      margin: 0.75rem 1rem 0;
      padding: 0.5rem 0.75rem;
      border-radius: 6px;
      background: #fdecec;
      color: #8f1d1d;
      font-size: 0.82rem;
      font-weight: 600;
    }

    .dialog-form {
      padding: 0.9rem 1rem 1.1rem;
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }

    .two-col {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.75rem;
    }

    .field-group {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }

    .field-group label {
      font-size: 0.76rem;
      font-weight: 600;
      color: #142f42;
    }

    .dialog-actions {
      display: flex;
      justify-content: flex-end;
      gap: 0.5rem;
      padding-top: 0.3rem;
    }
  `,
})
export class Tds26qWorkbenchComponent implements OnInit {
  private readonly statutoryService = inject(StatutoryService);
  private readonly companyContext = inject(CompanyContextStore);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly destroyRef = inject(DestroyRef);

  readonly quarters: readonly TdsQuarter[] = ['Q1', 'Q2', 'Q3', 'Q4'];

  readonly loading = signal<boolean>(false);
  readonly quarter = signal<TdsQuarter>('Q1');
  readonly fiscalYearId = signal<string>('FY2025-26');
  readonly report = signal<TdsForm26qReportDto | null>(null);
  readonly bannerMessage = signal<string | null>(null);

  readonly naturesOpen = signal<boolean>(false);
  readonly natures = signal<readonly TdsNatureDto[]>([]);

  readonly challanModalOpen = signal<boolean>(false);
  readonly challanSection = signal<string>('194J');
  readonly challanBsrCode = signal<string>('0510308');
  readonly challanSerialNo = signal<string>('00412');
  readonly challanDate = signal<string>('2025-05-07');
  readonly challanTdsAmount = signal<string>('');
  readonly challanError = signal<string | null>(null);

  constructor() {
    const unbindAccept = this.shortcutRegistry.bindHandler(
      'screen.accept',
      'MODAL',
      () => {
        void this.submitChallan();
      },
      () => this.challanModalOpen(),
    );
    const unbindExport = this.shortcutRegistry.bindHandler('menu.export', 'SCREEN', () => {
      void this.exportFvu();
    });
    this.destroyRef.onDestroy(() => {
      unbindAccept();
      unbindExport();
    });
  }

  ngOnInit(): void {
    const periods = this.companyContext.periods();
    if (periods.length > 0 && periods[0].fiscalYearId) {
      this.fiscalYearId.set(periods[0].fiscalYearId);
    }
    void this.reloadReport();
  }

  selectQuarter(q: TdsQuarter): void {
    this.quarter.set(q);
    void this.reloadReport();
  }

  async reloadReport(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) {
      return;
    }
    this.loading.set(true);
    try {
      const res = await this.statutoryService.getTdsForm26q(
        companyId,
        this.quarter(),
        this.fiscalYearId(),
      );
      this.report.set(res);
    } catch {
      this.report.set(null);
    } finally {
      this.loading.set(false);
    }
  }

  async toggleNaturesPanel(): Promise<void> {
    const next = !this.naturesOpen();
    this.naturesOpen.set(next);
    const companyId = this.companyContext.activeCompany()?.id;
    if (next && companyId && this.natures().length === 0) {
      try {
        const list = await this.statutoryService.listTdsNatures(companyId);
        this.natures.set(list);
      } catch {
        this.natures.set([]);
      }
    }
  }

  openChallanModal(): void {
    const unpaid = this.report()?.unpaidTdsAmount;
    this.challanTdsAmount.set(unpaid && unpaid !== '0.00' ? unpaid : '4500.00');
    this.challanError.set(null);
    this.challanModalOpen.set(true);
  }

  async submitChallan(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) {
      return;
    }
    const bsr = this.challanBsrCode().trim();
    const serial = this.challanSerialNo().trim();
    if (!/^\d{7}$/.test(bsr)) {
      this.challanError.set('BSR Code must be exactly 7 digits (Protean FVU 1.2 rule).');
      return;
    }
    if (!/^\d{5}$/.test(serial)) {
      this.challanError.set('Challan Serial Number must be exactly 5 digits.');
      return;
    }
    const amt = ExactDecimal.tryParse(this.challanTdsAmount(), 2, 'UNNECESSARY');
    if (!amt || !amt.isPositive()) {
      this.challanError.set('Enter a positive exact TDS deposit amount.');
      return;
    }

    this.challanError.set(null);
    const created = await this.statutoryService.createTdsChallan(companyId, {
      quarter: this.quarter(),
      fiscalYearId: this.fiscalYearId(),
      sectionCode: this.challanSection(),
      bsrCode: bsr,
      challanSerialNo: serial,
      depositDate: this.challanDate(),
      tdsAmount: amt.toWireString(2),
    });

    this.report.update((curr) => {
      if (!curr) {
        return curr;
      }
      const prevDep =
        ExactDecimal.tryParse(curr.totalTdsDeposited, 2, 'HALF_UP') ?? ExactDecimal.zero(2);
      const prevUnpaid =
        ExactDecimal.tryParse(curr.unpaidTdsAmount, 2, 'HALF_UP') ?? ExactDecimal.zero(2);
      const added = ExactDecimal.tryParse(created.totalAmount || created.tdsAmount, 2, 'HALF_UP') ?? amt;
      const nextDep = prevDep.add(added, 2);
      const rawUnpaid = prevUnpaid.sub(added, 2);
      return {
        ...curr,
        challans: [...curr.challans, created],
        totalTdsDeposited: nextDep.toWireString(2),
        unpaidTdsAmount: (rawUnpaid.isNegative() ? ExactDecimal.zero(2) : rawUnpaid).toWireString(2),
      };
    });
    this.challanModalOpen.set(false);
    this.bannerMessage.set(
      `Recorded ITNS 281 Challan #${created.challanSerialNo} (BSR ${created.bsrCode}).`,
    );
  }

  async exportFvu(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) {
      return;
    }
    await this.statutoryService.exportTdsFvu(companyId, this.quarter(), this.fiscalYearId());
    this.bannerMessage.set(
      `Exported Protean FVU 1.2 Form 26Q file for ${this.quarter()} (${this.fiscalYearId()}).`,
    );
  }
}
