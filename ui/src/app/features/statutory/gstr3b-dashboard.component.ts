import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { CompanyContextStore } from '../../state/company-context.store';
import { GstRegistrationMaster, Gstr3bReportDto } from './statutory.models';
import { StatutoryService } from './statutory.service';

@Component({
  selector: 'mc-gstr3b-dashboard',
  standalone: true,
  imports: [FormsModule, RouterLink, IndianCurrencyPipe, CodeBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="gstr3b-page">
      <header class="mc-card report-header">
        <div>
          <span class="return-pill">Form GSTR-3B &bull; Monthly Summary Return</span>
          <h1>GSTR-3B Liability, Eligible ITC &amp; Net Tax Payable</h1>
          <p class="period-caption">
            Period: <strong>{{ fromDate() }}</strong> to <strong>{{ toDate() }}</strong>
            @if (activeGstin(); as gstin) {
              &bull; GSTIN: <mc-code-badge [code]="gstin" />
            }
          </p>
        </div>

        <div class="header-controls">
          <label class="inline-field">
            <span>From</span>
            <input
              type="date"
              class="mc-input"
              [ngModel]="fromDate()"
              (ngModelChange)="fromDate.set($event)"
            />
          </label>
          <label class="inline-field">
            <span>To</span>
            <input
              type="date"
              class="mc-input"
              [ngModel]="toDate()"
              (ngModelChange)="toDate.set($event)"
            />
          </label>
          <label class="inline-field">
            <span>Return Period (MMYYYY)</span>
            <input
              type="text"
              class="mc-input mc-code"
              [ngModel]="returnPeriod()"
              (ngModelChange)="returnPeriod.set($event)"
              placeholder="042025"
            />
          </label>
          <button type="button" class="mc-btn mc-btn-primary" (click)="reloadReport()">
            Refresh
          </button>
        </div>
      </header>

      <!-- Live Rel 7.2 IMS Impact Banner -->
      <section class="ims-impact-banner" data-testid="gstr3b-ims-banner">
        <div>
          <strong>TallyPrime Rel 7.2 Live IMS Feed:</strong>
          Table 4(A)(5) Eligible ITC reflects accepted supplier invoices net of any partial ITC
          reductions; <em>Pending</em> invoices remain deferred to subsequent filing periods.
          @if (report()?.imsItcReductionTotal; as red) {
            <span>
              &bull; IMS Partial Reduction:
              <strong class="mc-amount">{{ red | mcIndianCurrency }}</strong>
            </span>
          }
        </div>
        <div class="banner-links">
          <a routerLink="/app/statutory/ims" class="mc-btn mc-btn-primary">
            Open IMS Reconciliation
          </a>
          <a routerLink="/app/statutory/gstr1" class="mc-btn">GSTR-1</a>
          <a routerLink="/app/statutory/gstr9" class="mc-btn">GSTR-9 Annual</a>
        </div>
      </section>

      @if (loading()) {
        <div class="mc-card state-box">Computing GSTR-3B Tables 3.1, 4 &amp; 6.1...</div>
      } @else if (report(); as r) {
        <!-- Summary KPI Cards -->
        <section class="kpi-grid" aria-label="GSTR-3B Summary Totals">
          <article class="mc-card kpi-card">
            <span class="kpi-title">3.1(a) Outward Taxable Liability</span>
            <span class="mc-amount kpi-big" data-testid="gstr3b-outward-tax">
              {{ r.outwardTaxable.totalTax | mcIndianCurrency }}
            </span>
          </article>

          <article class="mc-card kpi-card">
            <span class="kpi-title">3.1(d) Inward Reverse Charge (RCM)</span>
            <span class="mc-amount kpi-big" data-testid="gstr3b-rcm-tax">
              {{ r.inwardRcm.totalTax | mcIndianCurrency }}
            </span>
          </article>

          <article class="mc-card kpi-card">
            <span class="kpi-title">4(A) Eligible Input Tax Credit (ITC)</span>
            <span class="mc-amount kpi-big kpi-teal" data-testid="gstr3b-eligible-itc">
              {{ r.eligibleItc.totalTax | mcIndianCurrency }}
            </span>
          </article>

          <article class="mc-card kpi-card">
            <span class="kpi-title">6.1 Net Tax Payable</span>
            <span class="mc-amount kpi-big kpi-navy" data-testid="gstr3b-net-payable">
              {{ r.netTaxPayable.totalTax | mcIndianCurrency }}
            </span>
          </article>
        </section>

        <!-- Detailed Statutory Table 3.1, Table 4 & Table 6.1 -->
        <section class="mc-card table-card">
          <header class="card-subhead">
            <h2>Table 3.1, Table 4 &amp; Table 6.1 — Head-Wise Tax Schedule</h2>
          </header>
          <table class="mc-table" data-testid="gstr3b-schedule-table">
            <thead>
              <tr>
                <th>GSTR-3B Section</th>
                <th class="num-col">Taxable Value</th>
                <th class="num-col">Integrated Tax (IGST)</th>
                <th class="num-col">Central Tax (CGST)</th>
                <th class="num-col">State / UT Tax (SGST)</th>
                <th class="num-col">Cess</th>
                <th class="num-col">Total Tax</th>
              </tr>
            </thead>
            <tbody>
              <tr data-testid="gstr3b-row-outward">
                <td><strong>3.1(a) Outward taxable supplies (other than zero rated, nil, exempt)</strong></td>
                <td class="mc-amount num-col">
                  {{ (r.outwardTaxable.taxableValue ?? '0.00') | mcIndianCurrency }}
                </td>
                <td class="mc-amount num-col">{{ r.outwardTaxable.igstAmount | mcIndianCurrency }}</td>
                <td class="mc-amount num-col">{{ r.outwardTaxable.cgstAmount | mcIndianCurrency }}</td>
                <td class="mc-amount num-col">{{ r.outwardTaxable.sgstAmount | mcIndianCurrency }}</td>
                <td class="mc-amount num-col">{{ r.outwardTaxable.cessAmount | mcIndianCurrency }}</td>
                <td class="mc-amount num-col"><strong>{{ r.outwardTaxable.totalTax | mcIndianCurrency }}</strong></td>
              </tr>

              <tr data-testid="gstr3b-row-rcm">
                <td><strong>3.1(d) Inward supplies (liable to reverse charge)</strong></td>
                <td class="mc-amount num-col">
                  {{ (r.inwardRcm.taxableValue ?? '0.00') | mcIndianCurrency }}
                </td>
                <td class="mc-amount num-col">{{ r.inwardRcm.igstAmount | mcIndianCurrency }}</td>
                <td class="mc-amount num-col">{{ r.inwardRcm.cgstAmount | mcIndianCurrency }}</td>
                <td class="mc-amount num-col">{{ r.inwardRcm.sgstAmount | mcIndianCurrency }}</td>
                <td class="mc-amount num-col">{{ r.inwardRcm.cessAmount | mcIndianCurrency }}</td>
                <td class="mc-amount num-col"><strong>{{ r.inwardRcm.totalTax | mcIndianCurrency }}</strong></td>
              </tr>

              <tr data-testid="gstr3b-row-itc">
                <td><strong>4(A) Eligible ITC (IMS Accepted + RCM Eligible)</strong></td>
                <td class="mc-amount num-col">
                  {{ (r.eligibleItc.taxableValue ?? '0.00') | mcIndianCurrency }}
                </td>
                <td class="mc-amount num-col">{{ r.eligibleItc.igstAmount | mcIndianCurrency }}</td>
                <td class="mc-amount num-col">{{ r.eligibleItc.cgstAmount | mcIndianCurrency }}</td>
                <td class="mc-amount num-col">{{ r.eligibleItc.sgstAmount | mcIndianCurrency }}</td>
                <td class="mc-amount num-col">{{ r.eligibleItc.cessAmount | mcIndianCurrency }}</td>
                <td class="mc-amount num-col"><strong>{{ r.eligibleItc.totalTax | mcIndianCurrency }}</strong></td>
              </tr>

              <tr data-testid="gstr3b-row-itc-reversed">
                <td><strong>4(B) ITC Reversed (Rule 42/43 &amp; Section 17(5) Ineligible)</strong></td>
                <td class="mc-amount num-col">
                  {{ (r.itcReversed.taxableValue ?? '0.00') | mcIndianCurrency }}
                </td>
                <td class="mc-amount num-col">{{ r.itcReversed.igstAmount | mcIndianCurrency }}</td>
                <td class="mc-amount num-col">{{ r.itcReversed.cgstAmount | mcIndianCurrency }}</td>
                <td class="mc-amount num-col">{{ r.itcReversed.sgstAmount | mcIndianCurrency }}</td>
                <td class="mc-amount num-col">{{ r.itcReversed.cessAmount | mcIndianCurrency }}</td>
                <td class="mc-amount num-col"><strong>{{ r.itcReversed.totalTax | mcIndianCurrency }}</strong></td>
              </tr>

              <tr class="net-row" data-testid="gstr3b-row-net-payable">
                <td><strong>6.1 Net Tax Payable (Outward + RCM &minus; Net Eligible ITC)</strong></td>
                <td class="num-col">—</td>
                <td class="mc-amount num-col"><strong>{{ r.netTaxPayable.igstAmount | mcIndianCurrency }}</strong></td>
                <td class="mc-amount num-col"><strong>{{ r.netTaxPayable.cgstAmount | mcIndianCurrency }}</strong></td>
                <td class="mc-amount num-col"><strong>{{ r.netTaxPayable.sgstAmount | mcIndianCurrency }}</strong></td>
                <td class="mc-amount num-col"><strong>{{ r.netTaxPayable.cessAmount | mcIndianCurrency }}</strong></td>
                <td class="mc-amount num-col"><strong>{{ r.netTaxPayable.totalTax | mcIndianCurrency }}</strong></td>
              </tr>
            </tbody>
          </table>
        </section>
      }
    </div>
  `,
  styles: `
    .gstr3b-page {
      display: flex;
      flex-direction: column;
      gap: 0.85rem;
    }

    .report-header {
      padding: 0.85rem 1rem;
      background: #142f42;
      color: #ffffff;
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 0.75rem;
    }

    .report-header h1 {
      margin: 0.2rem 0;
      font-size: 1.05rem;
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

    .period-caption {
      margin: 0;
      font-size: 0.78rem;
      color: #d3e5ee;
      display: flex;
      align-items: center;
      gap: 0.4rem;
    }

    .header-controls {
      display: flex;
      align-items: end;
      flex-wrap: wrap;
      gap: 0.5rem;
    }

    .inline-field {
      display: flex;
      flex-direction: column;
      gap: 0.18rem;
      font-size: 0.72rem;
      font-weight: 600;
      color: #d3e5ee;
    }

    .ims-impact-banner {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 0.75rem;
      padding: 0.65rem 1rem;
      border-radius: 6px;
      background: #eaf7f5;
      border: 1px solid #9bd8ce;
      font-size: 0.82rem;
      color: #142f42;
    }

    .banner-links {
      display: flex;
      gap: 0.45rem;
    }

    .kpi-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(215px, 1fr));
      gap: 0.75rem;
    }

    .kpi-card {
      padding: 0.75rem 0.95rem;
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }

    .kpi-title {
      font-size: 0.76rem;
      font-weight: 600;
      color: #4f6775;
    }

    .kpi-big {
      font-size: 1.08rem;
      font-weight: 700;
      color: #142f42;
    }

    .kpi-teal {
      color: #006d68;
    }

    .kpi-navy {
      color: #142f42;
    }

    .table-card {
      overflow: hidden;
    }

    .card-subhead {
      padding: 0.65rem 1rem;
      background: #f6faf9;
      border-bottom: 1px solid #d4e2e0;
    }

    .card-subhead h2 {
      margin: 0;
      font-size: 0.88rem;
      font-weight: 700;
      color: #142f42;
    }

    .num-col {
      text-align: right;
    }

    .net-row {
      background: #f0faf8;
    }

    .state-box {
      padding: 1.5rem;
      font-weight: 600;
      color: #142f42;
    }
  `,
})
export class Gstr3bDashboardComponent implements OnInit {
  private readonly statutoryService = inject(StatutoryService);
  private readonly companyContext = inject(CompanyContextStore);

  readonly loading = signal<boolean>(false);
  readonly report = signal<Gstr3bReportDto | null>(null);
  readonly registrations = signal<readonly GstRegistrationMaster[]>([]);
  readonly selectedRegistrationId = signal<string>('');
  readonly fromDate = signal<string>('2025-04-01');
  readonly toDate = signal<string>('2025-04-30');
  readonly returnPeriod = signal<string>('042025');

  readonly activeGstin = computed(
    () => this.report()?.gstin ?? this.companyContext.activeCompany()?.features.gstin ?? '',
  );

  ngOnInit(): void {
    const pFrom = this.companyContext.periodFrom();
    this.fromDate.set(pFrom);
    this.toDate.set(this.companyContext.periodTo());
    if (/^\d{4}-\d{2}/.test(pFrom)) {
      this.returnPeriod.set(`${pFrom.slice(5, 7)}${pFrom.slice(0, 4)}`);
    }
    void this.reloadReport();
  }

  async reloadReport(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) {
      return;
    }
    this.loading.set(true);
    try {
      const res = await this.statutoryService.getGstr3b(companyId, {
        fromDate: this.fromDate(),
        toDate: this.toDate(),
        returnPeriod: this.returnPeriod(),
        gstRegistrationId: this.selectedRegistrationId() || null,
      });
      this.report.set(res);
    } catch {
      this.report.set(null);
    } finally {
      this.loading.set(false);
    }
  }
}
