import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { ReportOutputModalComponent } from '../../shared/components/report-output-modal/report-output-modal.component';
import { SemanticIconComponent } from '../../shared/components/semantic-icon/semantic-icon.component';
import { CompanyContextStore } from '../../state/company-context.store';
import {
  ScheduleIiiEntityFormat,
  ScheduleIiiReportDto,
  ScheduleIiiRoundingScale,
} from './reports.models';
import { ReportsService } from './reports.service';

@Component({
  selector: 'mc-schedule-iii',
  standalone: true,
  imports: [
    FormsModule,
    RouterLink,
    CodeBadgeComponent,
    IndianCurrencyPipe,
    SemanticIconComponent,
    ReportOutputModalComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="s3-page">
      <header class="mc-card s3-header">
        <div>
          <span class="s3-pill">Statutory Financial Statements &bull; Companies Act 2013 / ICAI</span>
          <h1>
            MCA Schedule III —
            {{
              entityFormat() === 'DIVISION_I'
                ? 'Division I (Companies)'
                : 'Non-Corporate Entities (NCE)'
            }}
          </h1>
          <p class="s3-sub">
            As at <span class="mc-code">{{ asOfDate() }}</span>
            @if (comparativeAsOfDate()) {
              &bull; Comparative <span class="mc-code">{{ comparativeAsOfDate() }}</span>
            }
            &bull; Unit: <strong>{{ roundingScale() }}</strong>
          </p>
        </div>

        <div class="s3-controls">
          <label class="ctrl-field">
            <span>Format</span>
            <select
              class="mc-input"
              aria-label="Schedule III Entity Format"
              data-testid="s3-entity-format-select"
              [ngModel]="entityFormat()"
              (ngModelChange)="onFormatChange($event)"
            >
              <option value="DIVISION_I">Division I — Companies</option>
              <option value="NCE">ICAI — Non-Corporate Entities (NCE)</option>
            </select>
          </label>

          <label class="ctrl-field">
            <span>Rounding</span>
            <select
              class="mc-input"
              aria-label="Schedule III Rounding Scale"
              data-testid="s3-rounding-select"
              [ngModel]="roundingScale()"
              (ngModelChange)="onRoundingChange($event)"
            >
              <option value="ACTUAL">Actual (INR)</option>
              <option value="HUNDREDS">Hundreds</option>
              <option value="THOUSANDS">Thousands</option>
              <option value="LAKHS">Lakhs</option>
              <option value="CRORES">Crores</option>
            </select>
          </label>

          <label class="ctrl-field">
            <span>As at</span>
            <input
              type="date"
              class="mc-input"
              aria-label="Schedule III As Of Date"
              [ngModel]="asOfDate()"
              (ngModelChange)="asOfDate.set($event)"
            />
          </label>

          <label class="ctrl-field">
            <span>Prior Period</span>
            <input
              type="date"
              class="mc-input"
              aria-label="Schedule III Comparative Date"
              [ngModel]="comparativeAsOfDate()"
              (ngModelChange)="comparativeAsOfDate.set($event)"
            />
          </label>

          <button type="button" class="mc-btn mc-btn-secondary" (click)="reload()">
            <mc-icon name="refresh" [size]="16" />
            <span>Refresh</span>
          </button>
          @if (removedLineCodes().length > 0) {
            <button
              type="button"
              class="mc-btn mc-btn-secondary"
              data-testid="s3-restore-line-btn"
              (click)="restoreLastRemovedLine()"
            >
              Restore Hidden ({{ removedLineCodes().length }}) &bull; Alt+U
            </button>
          }
          <button
            type="button"
            class="mc-btn mc-btn-secondary"
            data-testid="s3-output-btn"
            (click)="outputModalOpen.set(true)"
          >
            <mc-icon name="export" [size]="16" />
            <span>Output</span>
          </button>
          <button
            type="button"
            class="mc-btn mc-btn-primary"
            data-testid="s3-print-pdf-btn"
            (click)="exportPdf()"
          >
            <mc-icon name="print" [size]="16" />
            <span>Schedule III PDF (Ctrl+P)</span>
          </button>
        </div>
      </header>

      @if (statusMessage(); as msg) {
        <div class="s3-banner" data-testid="s3-status-banner" role="status">{{ msg }}</div>
      }

      @if (loading()) {
        <div class="mc-card state-box">Compiling MCA Schedule III Balance Sheet, P&amp;L &amp; Notes...</div>
      } @else if (report(); as r) {
        <!-- Part I: Balance Sheet -->
        <section class="mc-card part-card" data-testid="s3-part-i-bs">
          <header class="part-head">
            <h2>Part I — Balance Sheet ({{ roundingUnitLabel() }})</h2>
            @if (r.isBalanced) {
              <span class="mc-badge mc-badge-jade" data-testid="s3-balanced-badge">
                Equity &amp; Liabilities = Assets
              </span>
            }
          </header>
          <table class="mc-table">
            <thead>
              <tr>
                <th>Code</th>
                <th>Particulars</th>
                <th>Note No.</th>
                <th class="num-col">Current Reporting Period ({{ r.asOfDate }})</th>
                <th class="num-col">Previous Reporting Period ({{ r.comparativeAsOfDate ?? 'Prior' }})</th>
              </tr>
            </thead>
            <tbody>
              @for (line of visibleBsLines(); track line.lineCode + '-' + line.particulars) {
                <tr
                  [class.subtotal-row]="line.isSubtotal"
                  [class.s3-selected-row]="selectedLineCode() === 'BS:' + line.lineCode"
                  (click)="selectedLineCode.set('BS:' + line.lineCode)"
                >
                  <td><mc-code-badge [code]="line.lineCode" /></td>
                  <td>
                    <span [class.indent-1]="line.indentLevel === 1" [class.indent-2]="line.indentLevel >= 2">
                      @if (line.isSubtotal) {
                        <strong>{{ line.particulars }}</strong>
                      } @else {
                        {{ line.particulars }}
                      }
                    </span>
                  </td>
                  <td>
                    @if (line.noteNo) {
                      <button
                        type="button"
                        class="note-pill"
                        [attr.data-testid]="'s3-jump-note-' + line.noteNo"
                        (click)="toggleNote(line.noteNo)"
                      >
                        Note {{ line.noteNo }}
                      </button>
                    }
                  </td>
                  <td class="mc-amount num-col">{{ line.currentAmount | mcIndianCurrency }}</td>
                  <td class="mc-amount num-col muted-amt">
                    {{ line.comparativeAmount | mcIndianCurrency }}
                  </td>
                </tr>
              }
            </tbody>
            <tfoot>
              <tr class="grand-row">
                <td colspan="3"><strong>Total Equity &amp; Liabilities / Assets</strong></td>
                <td class="mc-amount num-col" data-testid="s3-total-equity-liab">
                  <strong>{{ r.totalEquityAndLiabilities | mcIndianCurrency }}</strong>
                </td>
                <td class="mc-amount num-col" data-testid="s3-total-assets">
                  <strong>{{ r.totalAssets | mcIndianCurrency }}</strong>
                </td>
              </tr>
            </tfoot>
          </table>
        </section>

        <!-- Part II: Statement of Profit and Loss -->
        <section class="mc-card part-card" data-testid="s3-part-ii-pnl">
          <header class="part-head">
            <h2>Part II — Statement of Profit and Loss ({{ roundingUnitLabel() }})</h2>
            <span class="mc-amount profit-pill" data-testid="s3-net-profit">
              Profit for Period: {{ r.netProfitForPeriod | mcIndianCurrency }}
            </span>
          </header>
          <table class="mc-table">
            <thead>
              <tr>
                <th>Code</th>
                <th>Particulars</th>
                <th>Note No.</th>
                <th class="num-col">Current Period</th>
                <th class="num-col">Previous Period</th>
              </tr>
            </thead>
            <tbody>
              @for (line of visiblePnlLines(); track line.lineCode + '-' + line.particulars) {
                <tr
                  [class.subtotal-row]="line.isSubtotal"
                  [class.s3-selected-row]="selectedLineCode() === 'PNL:' + line.lineCode"
                  (click)="selectedLineCode.set('PNL:' + line.lineCode)"
                >
                  <td><mc-code-badge [code]="line.lineCode" /></td>
                  <td>
                    @if (line.isSubtotal) {
                      <strong>{{ line.particulars }}</strong>
                    } @else {
                      {{ line.particulars }}
                    }
                  </td>
                  <td>
                    @if (line.noteNo) {
                      <button
                        type="button"
                        class="note-pill"
                        (click)="toggleNote(line.noteNo)"
                      >
                        Note {{ line.noteNo }}
                      </button>
                    }
                  </td>
                  <td class="mc-amount num-col">{{ line.currentAmount | mcIndianCurrency }}</td>
                  <td class="mc-amount num-col muted-amt">
                    {{ line.comparativeAmount | mcIndianCurrency }}
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </section>

        <!-- Expandable Notes to Accounts (Note 1..N) -->
        <section class="mc-card part-card" data-testid="s3-notes-section">
          <header class="part-head">
            <h2>Notes to Accounts (Note 1 .. {{ r.notes.length }})</h2>
            <button type="button" class="mc-btn" (click)="expandAllNotes()">
              Expand / Collapse All Notes
            </button>
          </header>

          <div class="notes-stack">
            @for (note of r.notes; track note.noteNo) {
              <article class="note-card" [attr.data-testid]="'s3-note-card-' + note.noteNo">
                <button
                  type="button"
                  class="note-header-btn"
                  [attr.data-testid]="'s3-note-toggle-' + note.noteNo"
                  (click)="toggleNote(note.noteNo)"
                >
                  <div class="note-title-grp">
                    <span class="note-badge">Note {{ note.noteNo }}</span>
                    <strong>{{ note.title }}</strong>
                    @if (note.lineCode) {
                      <mc-code-badge [code]="note.lineCode" />
                    }
                  </div>
                  <div class="note-totals-grp">
                    <span class="mc-amount">{{ note.currentTotal | mcIndianCurrency }}</span>
                    <span class="chevron">{{ isNoteExpanded(note.noteNo) ? '▾' : '▸' }}</span>
                  </div>
                </button>

                @if (isNoteExpanded(note.noteNo)) {
                  <div class="note-body" [attr.data-testid]="'s3-note-body-' + note.noteNo">
                    <table class="mc-table">
                      <thead>
                        <tr>
                          <th>Ledger / Sub-Group Particulars</th>
                          <th class="num-col">Current Period</th>
                          <th class="num-col">Previous Period</th>
                        </tr>
                      </thead>
                      <tbody>
                        @for (it of note.items; track it.id + '-' + it.name) {
                          <tr>
                            <td>{{ it.name }}</td>
                            <td class="mc-amount num-col">{{ it.currentAmount | mcIndianCurrency }}</td>
                            <td class="mc-amount num-col muted-amt">
                              {{ it.comparativeAmount | mcIndianCurrency }}
                            </td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  </div>
                }
              </article>
            } @empty {
              <div class="mc-empty-records">No matching records</div>
            }
          </div>

          <footer class="s3-footer">
            <a routerLink="/app/reports/balance-sheet" class="mc-btn mc-btn-secondary">Balance Sheet</a>
            <a routerLink="/app/reports/pnl" class="mc-btn mc-btn-secondary">Profit &amp; Loss A/c</a>
            <a routerLink="/app/reports/trial-balance" class="mc-btn mc-btn-secondary">Trial Balance</a>
          </footer>
        </section>
      }

      <mc-report-output-modal
        [open]="outputModalOpen()"
        reportTitle="MCA Schedule III"
        [boundsLabel]="'As at ' + asOfDate()"
        (printRequested)="exportPdf()"
        (exportRequested)="exportPdf()"
        (closed)="outputModalOpen.set(false)"
      />
    </div>
  `,
  styles: `
    .s3-page {
      display: flex;
      flex-direction: column;
      gap: 0.8rem;
    }

    .s3-header {
      padding: 0.85rem 1rem;
      background: #142f42;
      color: #ffffff;
      display: flex;
      justify-content: space-between;
      align-items: flex-end;
      flex-wrap: wrap;
      gap: 0.75rem;
    }

    .s3-header h1 {
      margin: 0.15rem 0 0.1rem;
      font-size: 1.12rem;
      font-weight: 700;
    }

    .s3-sub {
      margin: 0;
      font-size: 0.78rem;
      color: #cbd5e1;
      font-weight: 400;
    }

    .s3-pill {
      font-size: 0.68rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: #4ce1bd;
    }

    .s3-controls {
      display: flex;
      align-items: flex-end;
      flex-wrap: wrap;
      gap: 0.45rem;
    }

    .ctrl-field {
      display: flex;
      flex-direction: column;
      gap: 2px;
      font-size: 0.68rem;
      font-weight: 600;
      color: #cbd5e1;
    }

    .s3-banner {
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

    .part-card {
      overflow: hidden;
    }

    .part-head {
      padding: 0.65rem 1rem;
      background: #f6faf9;
      border-bottom: 1px solid #cbd5e1;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .part-head h2 {
      margin: 0;
      font-size: 0.92rem;
      font-weight: 700;
      color: #142f42;
    }

    .profit-pill {
      font-size: 0.84rem;
      font-weight: 700;
      color: #006d68;
    }

    .num-col {
      text-align: right;
    }

    .muted-amt {
      color: #64748b;
    }

    .subtotal-row td {
      background: #f8fafc;
      font-weight: 700;
    }

    .grand-row td {
      background: #f6faf9;
      border-top: 2px solid #142f42;
    }

    .indent-1 {
      padding-left: 1rem;
    }

    .indent-2 {
      padding-left: 2rem;
    }

    .note-pill {
      background: #e6f7f5;
      border: 1px solid #008d87;
      color: #006d68;
      border-radius: 4px;
      padding: 0.1rem 0.45rem;
      font-size: 0.72rem;
      font-weight: 700;
      cursor: pointer;
    }

    .notes-stack {
      padding: 0.75rem 1rem;
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }

    .note-card {
      border: 1px solid #cbd5e1;
      border-radius: 6px;
      overflow: hidden;
    }

    .note-header-btn {
      width: 100%;
      padding: 0.55rem 0.85rem;
      background: #f8fafc;
      border: none;
      display: flex;
      justify-content: space-between;
      align-items: center;
      cursor: pointer;
      text-align: left;
    }

    .note-title-grp,
    .note-totals-grp {
      display: flex;
      align-items: center;
      gap: 0.55rem;
    }

    .note-badge {
      background: #142f42;
      color: #4ce1bd;
      border-radius: 4px;
      padding: 0.1rem 0.45rem;
      font-size: 0.72rem;
      font-weight: 700;
    }

    .chevron {
      font-weight: 700;
      color: #008d87;
    }

    .note-body {
      border-top: 1px solid #e2e8f0;
    }

    .empty-notes {
      padding: 1rem;
      text-align: center;
      color: #64748b;
      font-weight: 500;
    }

    .s3-footer {
      padding: 0.65rem 1rem;
      border-top: 1px solid #e2e8f0;
      background: #f8fafc;
      display: flex;
      gap: 0.45rem;
    }

    .s3-selected-row td {
      background: #e6f7f5;
    }
  `,
})
export class ScheduleIiiComponent implements OnInit {
  readonly embeddedOverlay = input<boolean>(false);

  private readonly companyContext = inject(CompanyContextStore);
  private readonly reportsService = inject(ReportsService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly destroyRef = inject(DestroyRef);

  readonly asOfDate = signal<string>(this.companyContext.periodTo());
  readonly comparativeAsOfDate = signal<string>('');
  readonly entityFormat = signal<ScheduleIiiEntityFormat>('DIVISION_I');
  readonly roundingScale = signal<ScheduleIiiRoundingScale>('ACTUAL');

  readonly loading = signal<boolean>(false);
  readonly report = signal<ScheduleIiiReportDto | null>(null);
  readonly expandedNotes = signal<ReadonlySet<string>>(new Set());
  readonly statusMessage = signal<string | null>(null);
  readonly outputModalOpen = signal<boolean>(false);
  readonly selectedLineCode = signal<string>('');
  readonly removedLineCodes = signal<readonly string[]>([]);

  readonly visibleBsLines = computed(() => {
    const r = this.report();
    if (!r) return [];
    const hidden = this.removedLineCodes();
    return r.balanceSheetLines.filter((l) => !hidden.includes('BS:' + l.lineCode));
  });

  readonly visiblePnlLines = computed(() => {
    const r = this.report();
    if (!r) return [];
    const hidden = this.removedLineCodes();
    return r.statementOfPnlLines.filter((l) => !hidden.includes('PNL:' + l.lineCode));
  });

  ngOnInit(): void {
    const fyStart = this.companyContext.activeCompany()?.financialYearFrom ?? '2025-04-01';
    const yr = Number(fyStart.slice(0, 4));
    if (Number.isFinite(yr) && yr > 1970) {
      this.comparativeAsOfDate.set(`${yr}-03-31`);
    }

    const disposers = [
      this.shortcutRegistry.bindHandler('menu.print', 'SCREEN', () => {
        void this.exportPdf();
      }),
      this.shortcutRegistry.bindHandler('register.removeLine', 'SCREEN', () => {
        this.removeSelectedLine();
      }),
      this.shortcutRegistry.bindHandler('register.restoreLine', 'SCREEN', () => {
        this.restoreLastRemovedLine();
      }),
    ];
    this.destroyRef.onDestroy(() => {
      for (const d of disposers) d();
    });

    void this.reload();
  }

  removeSelectedLine(): void {
    const allCodes = [
      ...this.visibleBsLines().map((l) => 'BS:' + l.lineCode),
      ...this.visiblePnlLines().map((l) => 'PNL:' + l.lineCode),
    ];
    if (allCodes.length === 0) return;
    const target = this.selectedLineCode() && allCodes.includes(this.selectedLineCode())
      ? this.selectedLineCode()
      : allCodes[0];
    this.removedLineCodes.set([...this.removedLineCodes(), target]);
    const next = [
      ...this.visibleBsLines().map((l) => 'BS:' + l.lineCode),
      ...this.visiblePnlLines().map((l) => 'PNL:' + l.lineCode),
    ][0];
    this.selectedLineCode.set(next ?? '');
  }

  restoreLastRemovedLine(): void {
    const stack = this.removedLineCodes();
    if (stack.length === 0) return;
    this.removedLineCodes.set(stack.slice(0, -1));
  }

  roundingUnitLabel(): string {
    switch (this.roundingScale()) {
      case 'HUNDREDS':
        return 'INR in Hundreds';
      case 'THOUSANDS':
        return 'INR in Thousands';
      case 'LAKHS':
        return 'INR in Lakhs';
      case 'CRORES':
        return 'INR in Crores';
      default:
        return 'INR Actual';
    }
  }

  onFormatChange(next: ScheduleIiiEntityFormat): void {
    this.entityFormat.set(next);
    void this.reload();
  }

  onRoundingChange(next: ScheduleIiiRoundingScale): void {
    this.roundingScale.set(next);
    void this.reload();
  }

  async reload(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;
    this.loading.set(true);
    try {
      const data = await this.reportsService.getScheduleIii(companyId, {
        asOfDate: this.asOfDate(),
        comparativeAsOfDate: this.comparativeAsOfDate() || null,
        entityFormat: this.entityFormat(),
        roundingScale: this.roundingScale(),
      });
      this.report.set(data);
      if (data.notes.length > 0 && this.expandedNotes().size === 0) {
        this.expandedNotes.set(new Set([data.notes[0].noteNo]));
      }
    } catch {
      this.report.set(null);
    } finally {
      this.loading.set(false);
    }
  }

  isNoteExpanded(noteNo: string): boolean {
    return this.expandedNotes().has(noteNo);
  }

  toggleNote(noteNo: string): void {
    const next = new Set(this.expandedNotes());
    if (next.has(noteNo)) {
      next.delete(noteNo);
    } else {
      next.add(noteNo);
    }
    this.expandedNotes.set(next);
  }

  expandAllNotes(): void {
    const r = this.report();
    if (!r) return;
    if (this.expandedNotes().size === r.notes.length) {
      this.expandedNotes.set(new Set());
    } else {
      this.expandedNotes.set(new Set(r.notes.map((n) => n.noteNo)));
    }
  }

  async exportPdf(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;
    await this.reportsService.exportReportPdf(
      companyId,
      'schedule-iii',
      {
        asOfDate: this.asOfDate(),
        comparativeAsOfDate: this.comparativeAsOfDate() || null,
        entityFormat: this.entityFormat(),
        roundingScale: this.roundingScale(),
      },
      `ScheduleIII_${this.entityFormat()}_${this.asOfDate()}.pdf`,
    );
    this.statusMessage.set('MCA Schedule III PDF generated cleanly.');
  }
}
