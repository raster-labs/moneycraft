import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  OnInit,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { isEditableTextElement } from '../../core/keyboard/collision-guard';
import {
  ActiveModalContextHandle,
  ShortcutCommandRegistry,
  asciiPair,
} from '../../core/keyboard/shortcut-command.registry';
import { ExactDecimal } from '../../core/money/exact-decimal';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { ReportOutputModalComponent } from '../../shared/components/report-output-modal/report-output-modal.component';
import { SemanticIconComponent } from '../../shared/components/semantic-icon/semantic-icon.component';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import {
  BalanceSheetFormat,
  BalanceSheetReportDto,
  FinancialStatementLineDto,
} from './reports.models';
import { ReportsService } from './reports.service';

@Component({
  selector: 'mc-balance-sheet',
  standalone: true,
  imports: [
    FormsModule,
    RouterLink,
    IndianCurrencyPipe,
    SemanticIconComponent,
    ReportOutputModalComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bs-page">
      <header class="mc-card bs-header">
        <div>
          <span class="bs-pill">Financial Statements &bull; Balance Sheet</span>
          <h1>Balance Sheet</h1>
          <p class="bs-sub">
            As at <span class="mc-code">{{ asOfDate() }}</span> &bull;
            {{ format() === 'HORIZONTAL' ? 'Horizontal Two-Column (Ctrl+H = Vertical)' : 'Vertical Statement (Ctrl+H = Horizontal)' }}
            &bull; {{ detailedMode() ? 'Detailed (Alt+F1)' : 'Condensed (Alt+F1)' }}
          </p>
        </div>

        <div class="bs-actions">
          <button
            type="button"
            class="mc-btn"
            data-testid="bs-toggle-format-btn"
            (click)="toggleFormat()"
          >
            Ctrl+H: {{ format() === 'HORIZONTAL' ? 'Vertical' : 'Horizontal' }}
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="bs-toggle-detailed-btn"
            (click)="detailedMode.set(!detailedMode())"
          >
            Alt+F1: {{ detailedMode() ? 'Condensed' : 'Detailed' }}
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="bs-auto-column-btn"
            [class.mc-btn-primary]="autoColumnar()"
            (click)="autoColumnar.set(!autoColumnar())"
          >
            Alt+N: Auto-Column
          </button>
          <button type="button" class="mc-btn" (click)="toggleOptional()">
            Ctrl+B: {{ includeOptional() ? 'Postings Only' : 'Incl. Optional' }}
          </button>
          <select
            class="mc-input"
            aria-label="Scale Factor"
            data-testid="bs-scale-factor-select"
            [ngModel]="scaleFactor()"
            (ngModelChange)="setScaleFactor($event)"
          >
            <option value="DEFAULT">Scale: Default</option>
            <option value="HUNDREDS">In Hundreds</option>
            <option value="THOUSANDS">In Thousands</option>
            <option value="LAKHS">In Lakhs</option>
            <option value="MILLIONS">In Millions</option>
            <option value="CRORES">In Crores</option>
          </select>
          <button type="button" class="mc-btn mc-btn-secondary" (click)="openDateModal()">
            <mc-icon name="calendar" [size]="16" />
            <span>F2: As-of Date</span>
          </button>
          @if (removedKeys().length > 0) {
            <button
              type="button"
              class="mc-btn mc-btn-secondary"
              data-testid="bs-restore-line-btn"
              (click)="restoreLastRemovedLine()"
            >
              Restore Hidden ({{ removedKeys().length }}) &bull; Alt+U
            </button>
          }
          <button
            type="button"
            class="mc-btn mc-btn-secondary"
            data-testid="bs-output-btn"
            (click)="outputModalOpen.set(true)"
          >
            <mc-icon name="export" [size]="16" />
            <span>Output</span>
          </button>
          <button
            type="button"
            class="mc-btn mc-btn-primary"
            data-testid="bs-print-pdf-btn"
            (click)="exportPdf()"
          >
            <mc-icon name="print" [size]="16" />
            <span>Print PDF (Ctrl+P)</span>
          </button>
        </div>
      </header>

      @if (statusMessage(); as msg) {
        <div class="bs-banner" data-testid="bs-status-banner" role="status">{{ msg }}</div>
      }

      @if (loading()) {
        <div class="mc-card state-box">Computing Balance Sheet assets &amp; liabilities...</div>
      } @else if (report(); as r) {
        @if (format() === 'HORIZONTAL') {
          <section class="mc-card bs-card" data-testid="bs-horizontal-view">
            <div class="bs-two-col">
              <!-- Left Column: Liabilities -->
              <div class="bs-col bs-col-left">
                <div class="bs-col-head">
                  <span>Liabilities</span>
                  <div class="amt-heads">
                    @if (autoColumnar()) {
                      <span>Comparative</span>
                    }
                    <span>As at {{ r.asOfDate }}</span>
                  </div>
                </div>
                <ul class="bs-list">
                  @for (line of visibleLiabilityLines(); track line.id + '-' + line.name) {
                    <li
                      class="bs-row"
                      [class.bs-row-active]="selectedKey() === 'L:' + line.id"
                      (click)="selectedKey.set('L:' + line.id)"
                      (dblclick)="drillLine(line)"
                    >
                      <div class="bs-row-main">
                        <strong>{{ line.name }}</strong>
                        <div class="amt-pair">
                          @if (autoColumnar()) {
                            <span class="mc-amount muted-amt">
                              {{ scaleAmount(line.comparativeAmount ?? '0.00') | mcIndianCurrency }}
                            </span>
                          }
                          <span class="mc-amount">{{ scaleAmount(line.amount) | mcIndianCurrency }}</span>
                        </div>
                      </div>
                      @if (detailedMode() && line.children.length > 0) {
                        <ul class="bs-sublist">
                          @for (sub of line.children; track sub.id + '-' + sub.name) {
                            <li
                              class="bs-subrow"
                              [class.bs-subrow-active]="selectedKey() === 'L:' + line.id + ':' + sub.id"
                              (click)="selectedKey.set('L:' + line.id + ':' + sub.id); $event.stopPropagation()"
                              (dblclick)="drillLine(sub); $event.stopPropagation()"
                            >
                              <span>{{ sub.name }}</span>
                              <span class="mc-amount">{{ scaleAmount(sub.amount) | mcIndianCurrency }}</span>
                            </li>
                          }
                        </ul>
                      }
                    </li>
                  }
                </ul>
                <div class="bs-col-total">
                  <strong>Total Liabilities</strong>
                  <strong class="mc-amount" data-testid="bs-total-liabilities">
                    {{ scaleAmount(r.totalLiabilities) | mcIndianCurrency }}
                  </strong>
                </div>
              </div>

              <!-- Right Column: Assets -->
              <div class="bs-col">
                <div class="bs-col-head">
                  <span>Assets</span>
                  <div class="amt-heads">
                    @if (autoColumnar()) {
                      <span>Comparative</span>
                    }
                    <span>As at {{ r.asOfDate }}</span>
                  </div>
                </div>
                <ul class="bs-list">
                  @for (line of visibleAssetLines(); track line.id + '-' + line.name) {
                    <li
                      class="bs-row"
                      [class.bs-row-active]="selectedKey() === 'A:' + line.id"
                      (click)="selectedKey.set('A:' + line.id)"
                      (dblclick)="drillLine(line)"
                    >
                      <div class="bs-row-main">
                        <strong>{{ line.name }}</strong>
                        <div class="amt-pair">
                          @if (autoColumnar()) {
                            <span class="mc-amount muted-amt">
                              {{ scaleAmount(line.comparativeAmount ?? '0.00') | mcIndianCurrency }}
                            </span>
                          }
                          <span class="mc-amount">{{ scaleAmount(line.amount) | mcIndianCurrency }}</span>
                        </div>
                      </div>
                      @if (detailedMode() && line.children.length > 0) {
                        <ul class="bs-sublist">
                          @for (sub of line.children; track sub.id + '-' + sub.name) {
                            <li
                              class="bs-subrow"
                              [class.bs-subrow-active]="selectedKey() === 'A:' + line.id + ':' + sub.id"
                              (click)="selectedKey.set('A:' + line.id + ':' + sub.id); $event.stopPropagation()"
                              (dblclick)="drillLine(sub); $event.stopPropagation()"
                            >
                              <span>{{ sub.name }}</span>
                              <span class="mc-amount">{{ scaleAmount(sub.amount) | mcIndianCurrency }}</span>
                            </li>
                          }
                        </ul>
                      }
                    </li>
                  }
                </ul>
                <div class="bs-col-total">
                  <strong>Total Assets</strong>
                  <strong class="mc-amount" data-testid="bs-total-assets">
                    {{ scaleAmount(r.totalAssets) | mcIndianCurrency }}
                  </strong>
                </div>
              </div>
            </div>
          </section>
        } @else {
          <!-- Vertical Statement Format (Ctrl+H) -->
          <section class="mc-card bs-card" data-testid="bs-vertical-view">
            <div class="vertical-section-title">I. Sources of Funds (Equity &amp; Liabilities)</div>
            <ul class="bs-list">
              @for (line of visibleLiabilityLines(); track line.id + '-' + line.name) {
                <li
                  class="bs-row"
                  [class.bs-row-active]="selectedKey() === 'L:' + line.id"
                  (click)="selectedKey.set('L:' + line.id)"
                  (dblclick)="drillLine(line)"
                >
                  <div class="bs-row-main">
                    <strong>{{ line.name }}</strong>
                    <span class="mc-amount">{{ scaleAmount(line.amount) | mcIndianCurrency }}</span>
                  </div>
                  @if (detailedMode() && line.children.length > 0) {
                    <ul class="bs-sublist">
                      @for (sub of line.children; track sub.id + '-' + sub.name) {
                        <li
                          class="bs-subrow"
                          [class.bs-subrow-active]="selectedKey() === 'L:' + line.id + ':' + sub.id"
                          (click)="selectedKey.set('L:' + line.id + ':' + sub.id); $event.stopPropagation()"
                          (dblclick)="drillLine(sub); $event.stopPropagation()"
                        >
                          <span>{{ sub.name }}</span>
                          <span class="mc-amount">{{ scaleAmount(sub.amount) | mcIndianCurrency }}</span>
                        </li>
                      }
                    </ul>
                  }
                </li>
              }
            </ul>
            <div class="bs-col-total">
              <strong>Total Sources of Funds</strong>
              <strong class="mc-amount" data-testid="bs-total-liabilities">
                {{ scaleAmount(r.totalLiabilities) | mcIndianCurrency }}
              </strong>
            </div>

            <div class="vertical-section-title">II. Application of Funds (Assets)</div>
            <ul class="bs-list">
              @for (line of visibleAssetLines(); track line.id + '-' + line.name) {
                <li
                  class="bs-row"
                  [class.bs-row-active]="selectedKey() === 'A:' + line.id"
                  (click)="selectedKey.set('A:' + line.id)"
                  (dblclick)="drillLine(line)"
                >
                  <div class="bs-row-main">
                    <strong>{{ line.name }}</strong>
                    <span class="mc-amount">{{ scaleAmount(line.amount) | mcIndianCurrency }}</span>
                  </div>
                  @if (detailedMode() && line.children.length > 0) {
                    <ul class="bs-sublist">
                      @for (sub of line.children; track sub.id + '-' + sub.name) {
                        <li
                          class="bs-subrow"
                          [class.bs-subrow-active]="selectedKey() === 'A:' + line.id + ':' + sub.id"
                          (click)="selectedKey.set('A:' + line.id + ':' + sub.id); $event.stopPropagation()"
                          (dblclick)="drillLine(sub); $event.stopPropagation()"
                        >
                          <span>{{ sub.name }}</span>
                          <span class="mc-amount">{{ scaleAmount(sub.amount) | mcIndianCurrency }}</span>
                        </li>
                      }
                    </ul>
                  }
                </li>
              }
            </ul>
            <div class="bs-col-total">
              <strong>Total Application of Funds</strong>
              <strong class="mc-amount" data-testid="bs-total-assets">
                {{ scaleAmount(r.totalAssets) | mcIndianCurrency }}
              </strong>
            </div>
          </section>
        }

        <footer class="mc-card bs-footer">
          <div class="nav-links">
            @if (r.isBalanced) {
              <span class="mc-badge mc-badge-jade" data-testid="bs-balanced-badge">
                Assets = Liabilities ({{ r.totalAssets | mcIndianCurrency }})
              </span>
            } @else {
              <span class="diff-warn">Difference: {{ r.differenceAmount | mcIndianCurrency }}</span>
            }
            @if (isNonZero(r.openingBalanceDifference)) {
              <span class="mc-badge mc-badge-slate">
                Opening Diff: {{ r.openingBalanceDifference | mcIndianCurrency }}
              </span>
            }
            <a routerLink="/app/reports/pnl" class="mc-btn">Profit &amp; Loss A/c</a>
            <a routerLink="/app/reports/schedule-iii" class="mc-btn">MCA Schedule III</a>
            <a routerLink="/app/reports/ratios" class="mc-btn">Ratio Analysis</a>
          </div>
          <span class="hint-text">Enter = Drill Down &bull; Ctrl+H = Horizontal / Vertical &bull; Ctrl+P = PDF</span>
        </footer>
      }

      @if (dateModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Balance Sheet As-Of Date"
          (keydown.escape)="dateModalOpen.set(false)"
        >
          <form class="mc-modal-dialog date-dialog" (ngSubmit)="applyDate()">
            <div class="mc-modal-header">
              <span>Balance Sheet As-Of Date (F2)</span>
              <button type="button" class="mc-btn" (click)="dateModalOpen.set(false)">Esc</button>
            </div>
            <div class="mc-modal-body">
              <label class="field-col">
                <span>As at Date</span>
                <input type="date" name="draftAsOf" class="mc-input" [(ngModel)]="draftAsOf" required />
              </label>
            </div>
            <div class="mc-modal-footer">
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                (click)="dateModalOpen.set(false)"
              >
                <mc-icon name="close" [size]="16" />
                <span>Close</span>
                @if (dateDialogHint('editor.close', 'C/c'); as cHint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ cHint }}]</span>
                }
              </button>
              <button type="submit" class="mc-btn mc-btn-primary">
                <mc-icon name="calendar" [size]="16" />
                <span>Apply</span>
                @if (dateDialogHint('context.applyDate', 'A/a'); as aHint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ aHint }}]</span>
                }
              </button>
            </div>
          </form>
        </div>
      }

      <mc-report-output-modal
        [open]="outputModalOpen()"
        reportTitle="Balance Sheet"
        [boundsLabel]="'As at ' + asOfDate()"
        (printRequested)="exportPdf()"
        (exportRequested)="exportPdf()"
        (closed)="outputModalOpen.set(false)"
      />
    </div>
  `,
  styles: `
    .bs-page {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }

    .bs-header {
      padding: 0.85rem 1rem;
      background: #142f42;
      color: #ffffff;
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.75rem;
    }

    .bs-header h1 {
      margin: 0.15rem 0 0.1rem;
      font-size: 1.15rem;
      font-weight: 700;
    }

    .bs-sub {
      margin: 0;
      font-size: 0.78rem;
      color: #cbd5e1;
      font-weight: 400;
    }

    .bs-pill {
      font-size: 0.68rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: #4ce1bd;
    }

    .bs-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
    }

    .bs-banner {
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

    .bs-card {
      overflow: hidden;
    }

    .bs-two-col {
      display: grid;
      grid-template-columns: 1fr 1fr;
    }

    @media (max-width: 860px) {
      .bs-two-col {
        grid-template-columns: 1fr;
      }
    }

    .bs-col {
      display: flex;
      flex-direction: column;
      min-height: 240px;
    }

    .bs-col-left {
      border-right: 1px solid #cbd5e1;
    }

    .bs-col-head {
      padding: 0.55rem 0.95rem;
      background: #f1f5f9;
      border-bottom: 1px solid #cbd5e1;
      display: flex;
      justify-content: space-between;
      font-size: 0.74rem;
      font-weight: 700;
      color: #1e293b;
      text-transform: uppercase;
    }

    .amt-heads,
    .amt-pair {
      display: flex;
      align-items: center;
      gap: 1.25rem;
    }

    .muted-amt {
      color: #64748b;
      font-weight: 500;
    }

    .bs-list {
      list-style: none;
      margin: 0;
      padding: 0;
      flex: 1;
    }

    .bs-row {
      padding: 0.5rem 0.95rem;
      border-bottom: 1px solid #f1f5f9;
      cursor: pointer;
    }

    .bs-row-active {
      background: #e6f7f5;
      border-left: 3px solid #008d87;
    }

    .bs-row-main {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 0.86rem;
    }

    .bs-sublist {
      list-style: none;
      margin: 0.35rem 0 0;
      padding-left: 1rem;
      border-left: 2px solid #cbd5e1;
    }

    .bs-subrow {
      padding: 0.22rem 0.35rem;
      display: flex;
      justify-content: space-between;
      font-size: 0.78rem;
      color: #475569;
      font-weight: 500;
      border-radius: 3px;
    }

    .bs-subrow-active {
      background: #006d68;
      color: #ffffff;
      font-weight: 600;
    }

    .bs-col-total {
      padding: 0.6rem 0.95rem;
      background: #f6faf9;
      border-top: 2px solid #142f42;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .vertical-section-title {
      padding: 0.55rem 0.95rem;
      background: #142f42;
      color: #ffffff;
      font-size: 0.8rem;
      font-weight: 700;
      letter-spacing: 0.03em;
    }

    .bs-footer {
      padding: 0.65rem 1rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.5rem;
    }

    .nav-links {
      display: flex;
      align-items: center;
      gap: 0.45rem;
      flex-wrap: wrap;
    }

    .diff-warn {
      color: #b91c1c;
      font-weight: 700;
      font-size: 0.8rem;
    }

    .hint-text {
      font-size: 0.75rem;
      color: #475569;
      font-weight: 500;
    }

    .date-dialog {
      width: min(380px, 92vw);
    }

    .field-col {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
      font-size: 0.8rem;
      font-weight: 600;
    }
  `,
})
export class BalanceSheetComponent implements OnInit {
  readonly embeddedOverlay = input<boolean>(false);

  private readonly companyContext = inject(CompanyContextStore);
  private readonly reportsService = inject(ReportsService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly navStack = inject(NavigationStackStore);
  private readonly destroyRef = inject(DestroyRef);

  readonly asOfDate = signal<string>(this.companyContext.periodTo());
  readonly format = signal<BalanceSheetFormat>('HORIZONTAL');
  readonly includeOptional = signal<boolean>(false);
  readonly detailedMode = signal<boolean>(false);
  readonly autoColumnar = signal<boolean>(false);
  readonly scaleFactor = signal<
    'DEFAULT' | 'HUNDREDS' | 'THOUSANDS' | 'LAKHS' | 'MILLIONS' | 'CRORES'
  >('DEFAULT');

  readonly loading = signal<boolean>(false);
  readonly report = signal<BalanceSheetReportDto | null>(null);
  readonly selectedKey = signal<string>('');
  readonly statusMessage = signal<string | null>(null);
  readonly dateModalOpen = signal<boolean>(false);
  readonly outputModalOpen = signal<boolean>(false);
  readonly removedKeys = signal<readonly string[]>([]);

  draftAsOf = '';
  private dateModalHandle: ActiveModalContextHandle | null = null;

  dateDialogHint(commandId: string, pairSpec: 'A/a' | 'C/c'): string | null {
    return this.shortcutRegistry.effectiveActionHint(
      'context.dateDialog',
      commandId,
      asciiPair(pairSpec),
    );
  }

  constructor() {
    effect(() => {
      const open = this.dateModalOpen();
      if (this.dateModalHandle) {
        this.dateModalHandle.close();
        this.dateModalHandle = null;
      }
      if (open) {
        this.dateModalHandle = this.shortcutRegistry.openModalContext(
          [
            {
              contextId: 'context.dateDialog',
              locale: 'en-GB',
              commandId: 'context.applyDate',
              label: 'Apply',
              letterPair: asciiPair('A/a'),
              permissionRef: 'report.read',
              effect: 'Apply Balance Sheet As-of Date.',
              normalActivation: () => this.applyDate(),
              iconSemanticId: 'mc.icon.calendar',
              stateTokenRole: 'action.primary',
            },
            {
              contextId: 'context.dateDialog',
              locale: 'en-GB',
              commandId: 'editor.close',
              label: 'Close',
              letterPair: asciiPair('C/c'),
              permissionRef: 'authenticated',
              effect: 'Close Balance Sheet As-of Date dialog.',
              normalActivation: () => this.dateModalOpen.set(false),
              iconSemanticId: 'mc.icon.close',
              stateTokenRole: 'action.secondary',
            },
          ],
          'en-GB',
        );
      }
    });
  }

  readonly visibleLiabilityLines = computed<readonly FinancialStatementLineDto[]>(() => {
    const r = this.report();
    if (!r) return [];
    return r.liabilityLines.filter((l) => !this.removedKeys().includes('L:' + l.id));
  });

  readonly visibleAssetLines = computed<readonly FinancialStatementLineDto[]>(() => {
    const r = this.report();
    if (!r) return [];
    return r.assetLines.filter((l) => !this.removedKeys().includes('A:' + l.id));
  });

  readonly navigableLines = computed<readonly { readonly key: string; readonly line: FinancialStatementLineDto }[]>(
    () => {
      const detailed = this.detailedMode();
      const out: { readonly key: string; readonly line: FinancialStatementLineDto }[] = [];
      for (const line of this.visibleLiabilityLines()) {
        out.push({ key: 'L:' + line.id, line });
        if (detailed && line.children?.length) {
          for (const sub of line.children) {
            out.push({ key: 'L:' + line.id + ':' + sub.id, line: sub });
          }
        }
      }
      for (const line of this.visibleAssetLines()) {
        out.push({ key: 'A:' + line.id, line });
        if (detailed && line.children?.length) {
          for (const sub of line.children) {
            out.push({ key: 'A:' + line.id + ':' + sub.id, line: sub });
          }
        }
      }
      return out;
    },
  );

  ngOnInit(): void {
    const disposers = [
      this.shortcutRegistry.bindHandler('voucher.changeMode', 'SCREEN', () => {
        this.toggleFormat();
      }),
      this.shortcutRegistry.bindHandler('report.toggleDetailed', 'SCREEN', () => {
        this.detailedMode.set(!this.detailedMode());
      }),
      this.shortcutRegistry.bindHandler('report.autoColumn', 'SCREEN', () => {
        this.autoColumnar.set(!this.autoColumnar());
      }),
      this.shortcutRegistry.bindHandler('report.basisOfValues', 'SCREEN', () => {
        this.toggleOptional();
      }),
      this.shortcutRegistry.bindHandler('context.changeDate', 'SCREEN', () => {
        this.openDateModal();
      }),
      this.shortcutRegistry.bindHandler('menu.print', 'SCREEN', () => {
        void this.exportPdf();
      }),
      this.shortcutRegistry.bindHandler('register.removeLine', 'SCREEN', () => {
        this.removeSelectedLine();
      }),
      this.shortcutRegistry.bindHandler('register.restoreLine', 'SCREEN', () => {
        this.restoreLastRemovedLine();
      }),
      this.shortcutRegistry.bindHandler('register.restoreAllLines', 'SCREEN', () => {
        this.removedKeys.set([]);
      }),
      this.shortcutRegistry.bindHandler('report.explodeRow', 'SCREEN', () => {
        this.detailedMode.set(!this.detailedMode());
      }),
      this.shortcutRegistry.bindHandler('report.addColumn', 'SCREEN', () => {
        this.autoColumnar.set(!this.autoColumnar());
      }),
      this.shortcutRegistry.bindHandler(
        'nav.escape',
        'MODAL',
        () => {
          if (this.outputModalOpen()) {
            this.outputModalOpen.set(false);
            return true;
          }
          if (this.dateModalOpen()) {
            this.dateModalOpen.set(false);
            return true;
          }
          return false;
        },
        () => this.dateModalOpen() || this.outputModalOpen(),
      ),
    ];
    this.destroyRef.onDestroy(() => {
      for (const d of disposers) d();
      if (this.dateModalHandle) {
        this.dateModalHandle.close();
        this.dateModalHandle = null;
      }
    });
    void this.reload();
  }

  removeSelectedLine(): void {
    const items = this.navigableLines();
    if (items.length === 0) return;
    const currentKey = this.selectedKey() || items[0].key;
    if (!this.removedKeys().includes(currentKey)) {
      this.removedKeys.set([...this.removedKeys(), currentKey]);
      const next = this.navigableLines()[0];
      this.selectedKey.set(next ? next.key : '');
    }
  }

  restoreLastRemovedLine(): void {
    const stack = this.removedKeys();
    if (stack.length === 0) return;
    this.removedKeys.set(stack.slice(0, -1));
  }

  async reload(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;
    this.loading.set(true);
    try {
      const data = await this.reportsService.getBalanceSheet(companyId, {
        asOfDate: this.asOfDate(),
        format: this.format(),
        includeOptional: this.includeOptional(),
      });
      this.report.set(data);
      const first = this.navigableLines()[0];
      if (first) this.selectedKey.set(first.key);
    } catch {
      this.report.set(null);
    } finally {
      this.loading.set(false);
    }
  }

  toggleFormat(): void {
    const next: BalanceSheetFormat = this.format() === 'HORIZONTAL' ? 'VERTICAL' : 'HORIZONTAL';
    this.format.set(next);
    void this.reload();
  }

  toggleOptional(): void {
    this.includeOptional.set(!this.includeOptional());
    void this.reload();
  }

  setScaleFactor(
    factor: 'DEFAULT' | 'HUNDREDS' | 'THOUSANDS' | 'LAKHS' | 'MILLIONS' | 'CRORES',
  ): void {
    this.scaleFactor.set(factor);
  }

  scaleAmount(wire: string): string {
    const factor = this.scaleFactor();
    if (factor === 'DEFAULT') return wire;
    const scale = this.companyContext.currencyScale();
    const dec = ExactDecimal.tryParse(wire, scale, 'HALF_UP');
    if (!dec) return wire;
    const divisorInt =
      factor === 'HUNDREDS'
        ? 100
        : factor === 'THOUSANDS'
          ? 1000
          : factor === 'LAKHS'
            ? 100000
            : factor === 'MILLIONS'
              ? 1000000
              : 10000000;
    return dec.divHalfUp(ExactDecimal.fromInt(divisorInt, 0), scale).toWireString(scale);
  }

  openDateModal(): void {
    this.draftAsOf = this.asOfDate();
    this.dateModalOpen.set(true);
  }

  applyDate(): void {
    if (this.draftAsOf) {
      this.asOfDate.set(this.draftAsOf);
      this.dateModalOpen.set(false);
      void this.reload();
    }
  }

  drillLine(line: FinancialStatementLineDto): void {
    const lower = line.name.toLowerCase();
    if (line.lineType === 'PROFIT_LOSS' || lower.includes('profit & loss')) {
      this.navStack.push({
        title: 'Profit & Loss A/c',
        route: '/app/reports/pnl',
      });
      return;
    }
    if (line.lineType === 'STOCK' || lower.includes('closing stock')) {
      this.navStack.push({
        title: 'Stock Summary',
        route: '/app/reports/stock-summary',
      });
      return;
    }
    if (line.lineType === 'LEDGER' || line.ledgerId) {
      this.navStack.push({
        title: `Ledger Vouchers: ${line.name}`,
        route: '/app/daybook',
        params: { ledgerId: line.ledgerId ?? line.id, ledgerName: line.name },
      });
      return;
    }
    this.navStack.push({
      title: `Trial Balance: ${line.name}`,
      route: '/app/reports/trial-balance',
      params: { groupId: line.groupId ?? line.id },
    });
  }

  async exportPdf(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;
    await this.reportsService.exportReportPdf(
      companyId,
      'balance-sheet',
      {
        asOfDate: this.asOfDate(),
        format: this.format(),
        includeOptional: this.includeOptional(),
        detailed: this.detailedMode(),
      },
      `BalanceSheet_${this.asOfDate()}.pdf`,
    );
    this.statusMessage.set('Balance Sheet PDF generated cleanly.');
  }

  isNonZero(wire: string): boolean {
    const dec = ExactDecimal.tryParse(wire, 6, 'HALF_UP');
    return dec !== null && !dec.isZero();
  }

  @HostListener('document:keydown', ['$event'])
  onKeyDown(event: KeyboardEvent): void {
    if (this.dateModalOpen() || isEditableTextElement(event.target)) return;
    if (this.navStack.hasOverlays() && !this.embeddedOverlay()) return;
    if (event.altKey || event.ctrlKey || event.metaKey) return;

    const items = this.navigableLines();
    if (items.length === 0) return;

    const idx = Math.max(0, items.findIndex((i) => i.key === this.selectedKey()));
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.selectedKey.set(items[(idx + 1) % items.length].key);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.selectedKey.set(items[(idx - 1 + items.length) % items.length].key);
    } else if (event.key === 'Enter') {
      const current = items[idx];
      if (current) {
        event.preventDefault();
        this.drillLine(current.line);
      }
    }
  }
}
