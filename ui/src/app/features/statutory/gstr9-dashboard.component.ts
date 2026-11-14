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
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { CompanyContextStore } from '../../state/company-context.store';
import { Gstr9ExportFormat, Gstr9ReportDto } from './statutory.models';
import { StatutoryService } from './statutory.service';

@Component({
  selector: 'mc-gstr9-dashboard',
  standalone: true,
  imports: [FormsModule, RouterLink, IndianCurrencyPipe, CodeBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="gstr9-page">
      <header class="mc-card report-header">
        <div>
          <span class="return-pill">TallyPrime Rel 7.2 Parity &bull; One-Click Form GSTR-9</span>
          <h1>GSTR-9 Annual Return Dashboard (Tables 4–19)</h1>
          <p class="period-caption">
            Fiscal Year: <strong>{{ fiscalYearId() }}</strong>
            @if (lastExportBanner(); as msg) {
              &bull; <span class="export-status" data-testid="gstr9-export-status">{{ msg }}</span>
            }
          </p>
        </div>

        <div class="header-controls">
          <label class="inline-field">
            <span>Fiscal Year ID</span>
            <input
              type="text"
              class="mc-input mc-code"
              data-testid="gstr9-fy-input"
              [ngModel]="fiscalYearId()"
              (ngModelChange)="fiscalYearId.set($event)"
              placeholder="FY2025-26"
            />
          </label>
          <button type="button" class="mc-btn" (click)="reloadReport()">Refresh</button>

          <button
            type="button"
            class="mc-btn mc-btn-primary"
            data-testid="gstr9-export-xlsx-btn"
            (click)="exportReturn('XLSX')"
          >
            One-Click Export .xlsx (Offline Tool)
          </button>

          <button
            type="button"
            class="mc-btn mc-btn-primary"
            data-testid="gstr9-export-json-btn"
            (click)="exportReturn('JSON')"
          >
            One-Click Export .json (Portal)
          </button>
        </div>
      </header>

      @if (loading()) {
        <div class="mc-card state-box">Compiling Annual GSTR-9 Tables 4 through 19...</div>
      } @else if (report(); as r) {
        <section class="kpi-grid" aria-label="GSTR-9 Annual Summary">
          <article class="mc-card kpi-card">
            <span class="kpi-label">Part II Table 4 — Outward Taxable</span>
            <span class="mc-amount kpi-val" data-testid="gstr9-outward-taxable">
              {{ r.totalOutwardTaxable | mcIndianCurrency }}
            </span>
          </article>
          <article class="mc-card kpi-card">
            <span class="kpi-label">Table 4 — Total Outward Tax</span>
            <span class="mc-amount kpi-val">{{ r.totalOutwardTax | mcIndianCurrency }}</span>
          </article>
          <article class="mc-card kpi-card">
            <span class="kpi-label">Part III Table 6 — ITC Availed</span>
            <span class="mc-amount kpi-val kpi-teal" data-testid="gstr9-itc-availed">
              {{ r.totalItcAvailed | mcIndianCurrency }}
            </span>
          </article>
          <article class="mc-card kpi-card">
            <span class="kpi-label">Table 9 — Net Tax Payable</span>
            <span class="mc-amount kpi-val">{{ r.netTaxPayable | mcIndianCurrency }}</span>
          </article>
          <article class="mc-card kpi-card">
            <span class="kpi-label">Table 8 — Books vs Portal Diff</span>
            <span class="mc-amount kpi-val">{{ r.reconciliationDifference | mcIndianCurrency }}</span>
          </article>
        </section>

        <div class="two-col">
          <section class="mc-card section-card">
            <header class="card-subhead">
              <h2>Part II — Table 4: Outward &amp; Inward Supplies Declared During FY</h2>
            </header>
            <table class="mc-table" data-testid="gstr9-table4">
              <thead>
                <tr>
                  <th>Sub-Table</th>
                  <th>Nature of Supplies</th>
                  <th class="num-col">Taxable Value</th>
                  <th class="num-col">IGST</th>
                  <th class="num-col">CGST</th>
                  <th class="num-col">SGST</th>
                </tr>
              </thead>
              <tbody>
                @for (row of r.table4Outward; track row.tableCode) {
                  <tr>
                    <td><mc-code-badge [code]="row.tableCode" /></td>
                    <td>{{ row.description }}</td>
                    <td class="mc-amount num-col">{{ row.taxableValue | mcIndianCurrency }}</td>
                    <td class="mc-amount num-col">{{ row.igstAmount | mcIndianCurrency }}</td>
                    <td class="mc-amount num-col">{{ row.cgstAmount | mcIndianCurrency }}</td>
                    <td class="mc-amount num-col">{{ row.sgstAmount | mcIndianCurrency }}</td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="6" class="empty-cell">No Table 4 outward entries in FY.</td>
                  </tr>
                }
              </tbody>
            </table>
          </section>

          <section class="mc-card section-card">
            <header class="card-subhead">
              <h2>Part III — Table 6: Details of ITC Availed During FY</h2>
            </header>
            <table class="mc-table" data-testid="gstr9-table6">
              <thead>
                <tr>
                  <th>Sub-Table</th>
                  <th>ITC Description</th>
                  <th class="num-col">IGST</th>
                  <th class="num-col">CGST</th>
                  <th class="num-col">SGST</th>
                </tr>
              </thead>
              <tbody>
                @for (row of r.table6ItcAvailed; track row.tableCode) {
                  <tr>
                    <td><mc-code-badge [code]="row.tableCode" /></td>
                    <td>{{ row.description }}</td>
                    <td class="mc-amount num-col">{{ row.igstAmount | mcIndianCurrency }}</td>
                    <td class="mc-amount num-col">{{ row.cgstAmount | mcIndianCurrency }}</td>
                    <td class="mc-amount num-col">{{ row.sgstAmount | mcIndianCurrency }}</td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="5" class="empty-cell">No Table 6 ITC rows in FY.</td>
                  </tr>
                }
              </tbody>
            </table>
          </section>
        </div>

        <section class="mc-card section-card">
          <header class="card-subhead">
            <h2>Part VI — Table 17: HSN Wise Summary of Outward Supplies</h2>
            <div class="footer-links">
              <a routerLink="/app/statutory/gstr1" class="mc-btn">GSTR-1</a>
              <a routerLink="/app/statutory/gstr3b" class="mc-btn">GSTR-3B</a>
            </div>
          </header>
          <table class="mc-table" data-testid="gstr9-table17-hsn">
            <thead>
              <tr>
                <th>HSN / SAC</th>
                <th>UQC</th>
                <th class="num-col">Total Qty</th>
                <th class="num-col">Taxable Value</th>
                <th class="num-col">IGST</th>
                <th class="num-col">CGST</th>
                <th class="num-col">SGST</th>
              </tr>
            </thead>
            <tbody>
              @for (h of r.table17HsnOutward; track h.hsnSacCode + h.uqcCode) {
                <tr>
                  <td><mc-code-badge [code]="h.hsnSacCode" /></td>
                  <td class="mc-code">{{ h.uqcCode }}</td>
                  <td class="mc-amount num-col">{{ h.totalQuantity }}</td>
                  <td class="mc-amount num-col">{{ h.taxableValue | mcIndianCurrency }}</td>
                  <td class="mc-amount num-col">{{ h.igstAmount | mcIndianCurrency }}</td>
                  <td class="mc-amount num-col">{{ h.cgstAmount | mcIndianCurrency }}</td>
                  <td class="mc-amount num-col">{{ h.sgstAmount | mcIndianCurrency }}</td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="7" class="empty-cell">No annual HSN outward rows.</td>
                </tr>
              }
            </tbody>
          </table>
        </section>
      }
    </div>
  `,
  styles: `
    .gstr9-page {
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
    }

    .export-status {
      color: #4ce1bd;
      font-weight: 600;
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

    .kpi-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(195px, 1fr));
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
      font-size: 1.02rem;
      font-weight: 700;
      color: #142f42;
    }

    .kpi-teal {
      color: #006d68;
    }

    .two-col {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.85rem;
    }

    @media (max-width: 980px) {
      .two-col {
        grid-template-columns: 1fr;
      }
    }

    .section-card {
      overflow: hidden;
    }

    .card-subhead {
      padding: 0.65rem 0.95rem;
      background: #f6faf9;
      border-bottom: 1px solid #d4e2e0;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
    }

    .card-subhead h2 {
      margin: 0;
      font-size: 0.86rem;
      font-weight: 700;
      color: #142f42;
    }

    .footer-links {
      display: flex;
      gap: 0.45rem;
    }

    .num-col {
      text-align: right;
    }

    .empty-cell {
      text-align: center;
      padding: 1rem;
      color: #5b7280;
    }

    .state-box {
      padding: 1.5rem;
      font-weight: 600;
      color: #142f42;
    }
  `,
})
export class Gstr9DashboardComponent implements OnInit {
  private readonly statutoryService = inject(StatutoryService);
  private readonly companyContext = inject(CompanyContextStore);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal<boolean>(false);
  readonly report = signal<Gstr9ReportDto | null>(null);
  readonly fiscalYearId = signal<string>('FY2025-26');
  readonly lastExportBanner = signal<string | null>(null);

  constructor() {
    const unbindExport = this.shortcutRegistry.bindHandler('menu.export', 'SCREEN', () => {
      void this.exportReturn('JSON');
    });
    this.destroyRef.onDestroy(() => unbindExport());
  }

  ngOnInit(): void {
    const periods = this.companyContext.periods();
    if (periods.length > 0 && periods[0].fiscalYearId) {
      this.fiscalYearId.set(periods[0].fiscalYearId);
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
      const data = await this.statutoryService.getGstr9(companyId, this.fiscalYearId());
      this.report.set(data);
    } catch {
      this.report.set(null);
    } finally {
      this.loading.set(false);
    }
  }

  async exportReturn(format: Gstr9ExportFormat): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) {
      return;
    }
    await this.statutoryService.exportGstr9(companyId, this.fiscalYearId(), format);
    this.lastExportBanner.set(
      format === 'XLSX'
        ? 'Downloaded One-Click GSTR-9 Offline Tool (.xlsx)'
        : 'Downloaded One-Click GSTR-9 Portal Payload (.json)',
    );
  }
}
