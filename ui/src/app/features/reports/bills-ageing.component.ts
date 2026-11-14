import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { isEditableTextElement } from '../../core/keyboard/collision-guard';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { AgeingBillRowDto, BillsAgeingReportDto, BillsAgeingType } from './reports.models';
import { ReportsService } from './reports.service';

@Component({
  selector: 'mc-bills-ageing',
  standalone: true,
  imports: [FormsModule, RouterLink, CodeBadgeComponent, IndianCurrencyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="age-page">
      <header class="mc-card age-header">
        <div>
          <span class="age-pill">Outstandings &amp; Sub-Ledger Bill Ageing</span>
          <h1>
            {{
              ageingType() === 'RECEIVABLES'
                ? 'Bills Receivable — Customer Ageing'
                : 'Bills Payable — Supplier Ageing'
            }}
          </h1>
          <p class="age-sub">
            As at <span class="mc-code">{{ asOfDate() }}</span> &bull; F4 = Switch Receivable /
            Payable &bull; Enter = Drill Down to Voucher / Party Ledger
          </p>
        </div>

        <div class="age-actions">
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="ageingType() === 'RECEIVABLES'"
            data-testid="ageing-tab-receivables"
            (click)="setAgeingType('RECEIVABLES')"
          >
            Receivables (F4)
          </button>
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="ageingType() === 'PAYABLES'"
            data-testid="ageing-tab-payables"
            (click)="setAgeingType('PAYABLES')"
          >
            Payables (F4)
          </button>

          <input
            type="date"
            class="mc-input"
            aria-label="Ageing As Of Date"
            [ngModel]="asOfDate()"
            (ngModelChange)="onDateChange($event)"
          />

          <input
            type="text"
            class="mc-input"
            placeholder="Filter party or bill ref..."
            aria-label="Filter Party or Bill"
            data-testid="ageing-party-filter"
            [ngModel]="partyFilter()"
            (ngModelChange)="partyFilter.set($event)"
          />

          <button
            type="button"
            class="mc-btn mc-btn-primary"
            data-testid="ageing-print-pdf-btn"
            (click)="exportPdf()"
          >
            Print PDF (Ctrl+P)
          </button>
        </div>
      </header>

      @if (statusMessage(); as msg) {
        <div class="age-banner" role="status">{{ msg }}</div>
      }

      @if (loading()) {
        <div class="mc-card state-box">Computing bill-by-bill ageing buckets...</div>
      } @else if (report(); as r) {
        <!-- Bucket KPI Strip -->
        <section class="bucket-strip" aria-label="Ageing Bucket Totals">
          <article class="mc-card bkt-card">
            <span class="bkt-label">0 – 30 Days</span>
            <span class="mc-amount bkt-val" data-testid="ageing-bkt-0-30">
              {{ r.bucketTotals.bucket0To30 | mcIndianCurrency }}
            </span>
          </article>
          <article class="mc-card bkt-card">
            <span class="bkt-label">31 – 60 Days</span>
            <span class="mc-amount bkt-val" data-testid="ageing-bkt-31-60">
              {{ r.bucketTotals.bucket31To60 | mcIndianCurrency }}
            </span>
          </article>
          <article class="mc-card bkt-card">
            <span class="bkt-label">61 – 90 Days</span>
            <span class="mc-amount bkt-val" data-testid="ageing-bkt-61-90">
              {{ r.bucketTotals.bucket61To90 | mcIndianCurrency }}
            </span>
          </article>
          <article class="mc-card bkt-card bkt-overdue">
            <span class="bkt-label">&gt; 90 Days</span>
            <span class="mc-amount bkt-val" data-testid="ageing-bkt-gt-90">
              {{ r.bucketTotals.bucketOver90 | mcIndianCurrency }}
            </span>
          </article>
          <article class="mc-card bkt-card bkt-total">
            <span class="bkt-label">Total Pending</span>
            <span class="mc-amount bkt-val" data-testid="ageing-total-pending">
              {{ r.totalPendingAmount | mcIndianCurrency }}
            </span>
          </article>
        </section>

        <section class="mc-card table-card">
          <div class="table-scroll">
            <table class="mc-table" data-testid="ageing-bills-table">
              <thead>
                <tr>
                  <th>Bill Date</th>
                  <th>Ref. Name</th>
                  <th>Party Name</th>
                  <th>Due Date</th>
                  <th class="num-col">Overdue Days</th>
                  <th class="num-col">0–30 Days</th>
                  <th class="num-col">31–60 Days</th>
                  <th class="num-col">61–90 Days</th>
                  <th class="num-col">&gt;90 Days</th>
                  <th class="num-col">Pending Balance</th>
                </tr>
              </thead>
              <tbody>
                @for (b of filteredBills(); track b.id; let idx = $index) {
                  <tr
                    class="bill-row"
                    [class.bill-row-active]="selectedIndex() === idx"
                    (click)="selectedIndex.set(idx)"
                    (dblclick)="drillBill(b)"
                  >
                    <td>{{ b.billDate }}</td>
                    <td><mc-code-badge [code]="b.billName" /></td>
                    <td>
                      <strong>{{ b.partyName }}</strong>
                      @if (b.partyGstin) {
                        <div><mc-code-badge [code]="b.partyGstin" /></div>
                      }
                    </td>
                    <td>{{ b.dueDate }}</td>
                    <td class="mc-amount num-col">{{ b.overdueDays }}</td>
                    <td class="mc-amount num-col">{{ b.bucket0To30 | mcIndianCurrency }}</td>
                    <td class="mc-amount num-col">{{ b.bucket31To60 | mcIndianCurrency }}</td>
                    <td class="mc-amount num-col">{{ b.bucket61To90 | mcIndianCurrency }}</td>
                    <td class="mc-amount num-col">{{ b.bucketOver90 | mcIndianCurrency }}</td>
                    <td class="mc-amount num-col">
                      <strong>{{ b.pendingAmount | mcIndianCurrency }}</strong>
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="10" class="empty-cell">No open bills pending as at {{ r.asOfDate }}.</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <footer class="age-footer">
            <a routerLink="/app/reports/balance-sheet" class="mc-btn">Balance Sheet</a>
            <a routerLink="/app/reports/ratios" class="mc-btn">Ratio Analysis</a>
          </footer>
        </section>
      }
    </div>
  `,
  styles: `
    .age-page {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }

    .age-header {
      padding: 0.85rem 1rem;
      background: #142f42;
      color: #ffffff;
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.75rem;
    }

    .age-header h1 {
      margin: 0.15rem 0 0.1rem;
      font-size: 1.12rem;
      font-weight: 700;
    }

    .age-sub {
      margin: 0;
      font-size: 0.78rem;
      color: #cbd5e1;
      font-weight: 400;
    }

    .age-pill {
      font-size: 0.68rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: #4ce1bd;
    }

    .age-actions {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.4rem;
    }

    .age-banner {
      padding: 0.5rem 0.85rem;
      background: #ecfdf5;
      border: 1px solid #6ee7b7;
      color: #065f46;
      border-radius: 6px;
      font-size: 0.8rem;
      font-weight: 600;
    }

    .state-box {
      padding: 2rem;
      text-align: center;
      color: #475569;
      font-weight: 500;
    }

    .bucket-strip {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(155px, 1fr));
      gap: 0.6rem;
    }

    .bkt-card {
      padding: 0.7rem 0.9rem;
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }

    .bkt-overdue {
      border-left: 3px solid #dc2626;
    }

    .bkt-total {
      border-left: 3px solid #008d87;
    }

    .bkt-label {
      font-size: 0.7rem;
      font-weight: 700;
      color: #64748b;
      text-transform: uppercase;
    }

    .bkt-val {
      font-size: 1rem;
      font-weight: 700;
      color: #0f172a;
    }

    .table-card {
      overflow: hidden;
    }

    .table-scroll {
      overflow-x: auto;
    }

    .num-col {
      text-align: right;
    }

    .bill-row {
      cursor: pointer;
    }

    .bill-row-active {
      background: #e6f7f5;
      outline: 2px solid #008d87;
      outline-offset: -2px;
    }

    .empty-cell {
      padding: 1.5rem;
      text-align: center;
      color: #64748b;
      font-weight: 500;
    }

    .age-footer {
      padding: 0.65rem 1rem;
      border-top: 1px solid #e2e8f0;
      background: #f8fafc;
      display: flex;
      gap: 0.4rem;
    }
  `,
})
export class BillsAgeingComponent implements OnInit {
  readonly embeddedOverlay = input<boolean>(false);
  readonly initialType = input<BillsAgeingType | null>(null);

  private readonly companyContext = inject(CompanyContextStore);
  private readonly reportsService = inject(ReportsService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly navStack = inject(NavigationStackStore);
  private readonly route = inject(ActivatedRoute, { optional: true });
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly ageingType = signal<BillsAgeingType>('RECEIVABLES');
  readonly asOfDate = signal<string>(this.companyContext.periodTo());
  readonly partyFilter = signal<string>('');
  readonly loading = signal<boolean>(false);
  readonly report = signal<BillsAgeingReportDto | null>(null);
  readonly selectedIndex = signal<number>(0);
  readonly statusMessage = signal<string | null>(null);

  readonly filteredBills = computed<readonly AgeingBillRowDto[]>(() => {
    const r = this.report();
    if (!r) return [];
    const q = this.partyFilter().trim().toLowerCase();
    if (!q) return r.bills;
    return r.bills.filter(
      (b) =>
        b.partyName.toLowerCase().includes(q) ||
        b.billName.toLowerCase().includes(q) ||
        (b.partyGstin ?? '').toLowerCase().includes(q),
    );
  });

  ngOnInit(): void {
    const fromInput = this.initialType();
    const fromRoute = this.route?.snapshot.data['ageingType'] as BillsAgeingType | undefined;
    if (fromInput) {
      this.ageingType.set(fromInput);
    } else if (fromRoute === 'PAYABLES' || fromRoute === 'RECEIVABLES') {
      this.ageingType.set(fromRoute);
    }

    const disposers = [
      this.shortcutRegistry.bindHandler('voucher.contra', 'SCREEN', () => {
        this.setAgeingType(this.ageingType() === 'RECEIVABLES' ? 'PAYABLES' : 'RECEIVABLES');
      }),
      this.shortcutRegistry.bindHandler('menu.print', 'SCREEN', () => {
        void this.exportPdf();
      }),
    ];
    this.destroyRef.onDestroy(() => {
      for (const d of disposers) d();
    });

    void this.reload();
  }

  setAgeingType(next: BillsAgeingType): void {
    if (this.ageingType() === next) return;
    this.ageingType.set(next);
    void this.reload();
  }

  onDateChange(nextDate: string): void {
    this.asOfDate.set(nextDate);
    void this.reload();
  }

  async reload(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;
    this.loading.set(true);
    try {
      const data = await this.reportsService.getBillsAgeing(companyId, {
        type: this.ageingType(),
        asOfDate: this.asOfDate(),
      });
      this.report.set(data);
      this.selectedIndex.set(0);
    } catch {
      this.report.set(null);
    } finally {
      this.loading.set(false);
    }
  }

  drillBill(bill: AgeingBillRowDto): void {
    if (bill.voucherId) {
      if (!this.companyContext.hasUnsavedDraft() && !this.embeddedOverlay()) {
        void this.router.navigate(['/app/vouchers', bill.voucherId]);
        return;
      }
    }
    this.navStack.push({
      title: `Party Ledger Bills: ${bill.partyName}`,
      route: '/app/daybook',
      params: { ledgerId: bill.ledgerId, ledgerName: bill.partyName },
    });
  }

  async exportPdf(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;
    await this.reportsService.exportReportPdf(
      companyId,
      'ageing',
      { type: this.ageingType(), asOfDate: this.asOfDate() },
      `BillsAgeing_${this.ageingType()}_${this.asOfDate()}.pdf`,
    );
    this.statusMessage.set('Bills Ageing PDF generated cleanly.');
  }

  @HostListener('document:keydown', ['$event'])
  onKeyDown(event: KeyboardEvent): void {
    if (isEditableTextElement(event.target)) return;
    if (this.navStack.hasOverlays() && !this.embeddedOverlay()) return;
    if (event.altKey || event.ctrlKey || event.metaKey) return;

    const rows = this.filteredBills();
    if (rows.length === 0) return;

    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.selectedIndex.set(Math.min(rows.length - 1, this.selectedIndex() + 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.selectedIndex.set(Math.max(0, this.selectedIndex() - 1));
    } else if (event.key === 'Enter') {
      const bill = rows[this.selectedIndex()];
      if (bill) {
        event.preventDefault();
        this.drillBill(bill);
      }
    }
  }
}
