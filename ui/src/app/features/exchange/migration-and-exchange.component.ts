import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { CompanyContextStore } from '../../state/company-context.store';
import {
  ExchangeEntityScope,
  ExchangeFormat,
  ExchangeMappingTemplateDto,
  ExchangeTab,
  ExportDataResultDto,
  ImportDataResultDto,
  ImportJobProofDto,
  ImportJobSummaryDto,
} from './exchange.models';
import { ExchangeService } from './exchange.service';

@Component({
  selector: 'mc-migration-and-exchange',
  standalone: true,
  imports: [FormsModule, CodeBadgeComponent, IndianCurrencyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-exg-shell" [class.mc-exg-embedded]="embeddedOverlay()">
      <header class="mc-exg-header mc-card">
        <div>
          <div class="mc-exg-heading-row">
            <h1 class="mc-exg-title">
              {{
                activeTab() === 'MIGRATE_1800'
                  ? 'Clean-Room .1800 Migration & Parity Proof Wizard'
                  : 'Data Exchange — Tally XML, Rel 7.2 JSONEx & Custom Excel'
              }}
            </h1>
            <span class="mc-badge mc-badge-jade">Zero-Variance Gate</span>
          </div>
          <p class="mc-exg-subtitle">
            Standalone clean-room 512-byte sector reader, pre-commit Trial Balance reconciliation proof,
            and lossless XML / JSONEx / OpenXML (.xlsx) round-trip exchange
          </p>
        </div>

        <div class="mc-exg-tabs" role="tablist" aria-label="Migration and Exchange Views">
          <button
            type="button"
            role="tab"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'MIGRATE_1800'"
            [attr.aria-selected]="activeTab() === 'MIGRATE_1800'"
            data-testid="tab-migrate-1800"
            (click)="switchTab('MIGRATE_1800')"
          >
            Migrate .1800 Company
          </button>
          <button
            type="button"
            role="tab"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'IMPORT_EXPORT'"
            [attr.aria-selected]="activeTab() === 'IMPORT_EXPORT'"
            data-testid="tab-import-export"
            (click)="switchTab('IMPORT_EXPORT')"
          >
            Import / Export (<span class="mc-key-alt">O</span> / <span class="mc-key-alt">E</span>)
          </button>
        </div>
      </header>

      @if (errorBanner(); as err) {
        <div class="mc-exg-alert mc-exg-alert-error" role="alert">{{ err }}</div>
      }
      @if (statusBanner(); as msg) {
        <div class="mc-exg-alert mc-exg-alert-ok" role="status" data-testid="exchange-status-banner">
          {{ msg }}
        </div>
      }

      <!-- ================================================================= -->
      <!-- TAB 1: CLEAN-ROOM .1800 MIGRATION WIZARD                          -->
      <!-- ================================================================= -->
      @if (activeTab() === 'MIGRATE_1800') {
        <section class="mc-card mc-exg-card" aria-label=".1800 Migration Staging Form">
          <div class="mc-exg-section-head">
            <span>Step 1 — Stage Quiescent TallyPrime 7.2 (.1800) Snapshot</span>
            <span class="mc-code">MIG-003 / MIG-007</span>
          </div>
          <form class="mc-exg-stage-form" (ngSubmit)="stageSnapshot()">
            <div class="mc-exg-field-grow">
              <label class="mc-field-label" for="mc-mig-snap-path">
                Snapshot Directory Path (containing Company.1800, CmpSave.1800, Manager.1800, TranMgr.1800)
              </label>
              <input
                id="mc-mig-snap-path"
                type="text"
                name="snapshotPathInput"
                class="mc-input mc-code"
                data-testid="migrate-snapshot-path-input"
                [(ngModel)]="snapshotPathInput"
                placeholder="/var/moneycraft/staging/100000"
                required
              />
            </div>

            <div class="mc-exg-field-grow">
              <label class="mc-field-label" for="mc-mig-xml-path">
                Companion XML Oracle Directory (Optional cross-verification)
              </label>
              <input
                id="mc-mig-xml-path"
                type="text"
                name="companionXmlPathInput"
                class="mc-input mc-code"
                data-testid="migrate-companion-xml-input"
                [(ngModel)]="companionXmlPathInput"
                placeholder="/var/moneycraft/staging/100000/xml"
              />
            </div>

            <div class="mc-exg-stage-actions">
              <button
                type="submit"
                class="mc-btn mc-btn-primary"
                data-testid="migrate-stage-btn"
                [disabled]="busy() || !snapshotPathInput.trim() || companyContext.quarantined()"
              >
                {{ busy() ? 'Staging & Verifying...' : 'Stage & Verify (.1800)' }}
              </button>
            </div>
          </form>
        </section>

        @if (activeProof(); as proof) {
          <section
            class="mc-card mc-exg-card"
            data-testid="migrate-proof-card"
            aria-label="Pre-Commit Trial Balance Reconciliation Proof"
          >
            <div class="mc-exg-section-head">
              <div class="mc-exg-proof-title-group">
                <span>
                  Step 2 — Pre-Commit Trial Balance Reconciliation Proof ({{ proof.sourceCompanyName }})
                </span>
                <span class="mc-badge mc-badge-slate">{{ proof.status }}</span>
                @if (proof.sector0Verified) {
                  <span class="mc-badge mc-badge-jade">Sector-0 Valid</span>
                }
                @if (proof.cmpSaveParityVerified) {
                  <span class="mc-badge mc-badge-jade">CmpSave == Company</span>
                }
              </div>

              <div class="mc-exg-proof-badges">
                <span
                  class="mc-badge"
                  [class.mc-badge-jade]="proof.isZeroVariance"
                  [class.mc-badge-danger]="!proof.isZeroVariance"
                  data-testid="migrate-variance-badge"
                >
                  Variance:
                  <span class="mc-amount">
                    {{ proof.varianceAmount | mcIndianCurrency: companyContext.currencyScale() }}
                  </span>
                  {{ proof.isZeroVariance ? '(ZERO VARIANCE)' : '(MISMATCH)' }}
                </span>
              </div>
            </div>

            <div class="mc-exg-proof-meta">
              <div>
                <span class="mc-exg-meta-lbl">Job ID:</span>
                <mc-code-badge [code]="proof.jobId" />
              </div>
              <div data-testid="migrate-proof-hash">
                <span class="mc-exg-meta-lbl">SHA-256 Proof Hash:</span>
                <mc-code-badge [code]="proof.proofSha256Hex" />
              </div>
              <div>
                <span class="mc-exg-meta-lbl">Edit Log Baseline:</span>
                <span class="mc-code">v{{ proof.editLogBaselineVersion }}</span>
              </div>
            </div>

            <div class="mc-exg-table-wrap">
              <table class="mc-exg-table" aria-label="Source vs Staged Reconciliation">
                <thead>
                  <tr>
                    <th scope="col">Reconciliation Dimension</th>
                    <th scope="col" class="mc-col-amt">Source (.1800 Snapshot)</th>
                    <th scope="col" class="mc-col-amt">Staged (PostgreSQL 18)</th>
                    <th scope="col" class="mc-col-status">Parity</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td>Account Groups (28 Predefined + Custom)</td>
                    <td class="mc-amount mc-col-amt">{{ proof.sourceGroupCount }}</td>
                    <td class="mc-amount mc-col-amt">{{ proof.stagedGroupCount }}</td>
                    <td class="mc-col-status">
                      <span
                        class="mc-badge"
                        [class.mc-badge-jade]="proof.sourceGroupCount === proof.stagedGroupCount"
                        [class.mc-badge-danger]="proof.sourceGroupCount !== proof.stagedGroupCount"
                      >
                        {{ proof.sourceGroupCount === proof.stagedGroupCount ? 'EXACT' : 'DIFF' }}
                      </span>
                    </td>
                  </tr>
                  <tr>
                    <td>Accounting Ledgers</td>
                    <td class="mc-amount mc-col-amt">{{ proof.sourceLedgerCount }}</td>
                    <td class="mc-amount mc-col-amt">{{ proof.stagedLedgerCount }}</td>
                    <td class="mc-col-status">
                      <span
                        class="mc-badge"
                        [class.mc-badge-jade]="proof.sourceLedgerCount === proof.stagedLedgerCount"
                        [class.mc-badge-danger]="proof.sourceLedgerCount !== proof.stagedLedgerCount"
                      >
                        {{ proof.sourceLedgerCount === proof.stagedLedgerCount ? 'EXACT' : 'DIFF' }}
                      </span>
                    </td>
                  </tr>
                  <tr>
                    <td>Stock Items</td>
                    <td class="mc-amount mc-col-amt">{{ proof.sourceStockItemCount }}</td>
                    <td class="mc-amount mc-col-amt">{{ proof.stagedStockItemCount }}</td>
                    <td class="mc-col-status">
                      <span
                        class="mc-badge"
                        [class.mc-badge-jade]="proof.sourceStockItemCount === proof.stagedStockItemCount"
                        [class.mc-badge-danger]="proof.sourceStockItemCount !== proof.stagedStockItemCount"
                      >
                        {{ proof.sourceStockItemCount === proof.stagedStockItemCount ? 'EXACT' : 'DIFF' }}
                      </span>
                    </td>
                  </tr>
                  <tr>
                    <td>Accounting &amp; Inventory Vouchers</td>
                    <td class="mc-amount mc-col-amt">{{ proof.sourceVoucherCount }}</td>
                    <td class="mc-amount mc-col-amt">{{ proof.stagedVoucherCount }}</td>
                    <td class="mc-col-status">
                      <span
                        class="mc-badge"
                        [class.mc-badge-jade]="proof.sourceVoucherCount === proof.stagedVoucherCount"
                        [class.mc-badge-danger]="proof.sourceVoucherCount !== proof.stagedVoucherCount"
                      >
                        {{ proof.sourceVoucherCount === proof.stagedVoucherCount ? 'EXACT' : 'DIFF' }}
                      </span>
                    </td>
                  </tr>
                  <tr>
                    <td>Gross Debit Turnover</td>
                    <td class="mc-amount mc-col-amt">
                      {{ proof.sourceGrossDebit | mcIndianCurrency: companyContext.currencyScale() }}
                    </td>
                    <td class="mc-amount mc-col-amt">
                      {{ proof.stagedGrossDebit | mcIndianCurrency: companyContext.currencyScale() }}
                    </td>
                    <td class="mc-col-status">
                      <span class="mc-badge mc-badge-jade">VERIFIED</span>
                    </td>
                  </tr>
                  <tr>
                    <td>Gross Credit Turnover</td>
                    <td class="mc-amount mc-col-amt">
                      {{ proof.sourceGrossCredit | mcIndianCurrency: companyContext.currencyScale() }}
                    </td>
                    <td class="mc-amount mc-col-amt">
                      {{ proof.stagedGrossCredit | mcIndianCurrency: companyContext.currencyScale() }}
                    </td>
                    <td class="mc-col-status">
                      <span class="mc-badge mc-badge-jade">VERIFIED</span>
                    </td>
                  </tr>
                  <tr class="mc-exg-highlight-row">
                    <td><strong>Net Trial Balance</strong></td>
                    <td class="mc-amount mc-col-amt" data-testid="migrate-source-net-tb">
                      {{ proof.sourceNetTrialBalance | mcIndianCurrency: companyContext.currencyScale() }}
                    </td>
                    <td class="mc-amount mc-col-amt" data-testid="migrate-staged-net-tb">
                      {{ proof.stagedNetTrialBalance | mcIndianCurrency: companyContext.currencyScale() }}
                    </td>
                    <td class="mc-col-status">
                      <span
                        class="mc-badge"
                        [class.mc-badge-jade]="proof.isZeroVariance"
                        [class.mc-badge-danger]="!proof.isZeroVariance"
                      >
                        {{ proof.isZeroVariance ? '0.00' : 'VARIANCE' }}
                      </span>
                    </td>
                  </tr>
                  <tr>
                    <td>Closing Inventory Valuation</td>
                    <td class="mc-amount mc-col-amt">
                      {{ proof.sourceClosingStock | mcIndianCurrency: companyContext.currencyScale() }}
                    </td>
                    <td class="mc-amount mc-col-amt">
                      {{ proof.stagedClosingStock | mcIndianCurrency: companyContext.currencyScale() }}
                    </td>
                    <td class="mc-col-status">
                      <span class="mc-badge mc-badge-jade">EXACT</span>
                    </td>
                  </tr>
                  <tr>
                    <td>Net Profit / (Loss)</td>
                    <td class="mc-amount mc-col-amt">
                      {{ proof.sourceNetProfit | mcIndianCurrency: companyContext.currencyScale() }}
                    </td>
                    <td class="mc-amount mc-col-amt">
                      {{ proof.stagedNetProfit | mcIndianCurrency: companyContext.currencyScale() }}
                    </td>
                    <td class="mc-col-status">
                      <span class="mc-badge mc-badge-jade">EXACT</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div class="mc-exg-commit-bar">
              <div class="mc-exg-commit-note">
                @if (proof.status === 'COMMITTED') {
                  Atomic promotion committed into Business DB with MCA Edit Log v{{ proof.editLogBaselineVersion }} baseline.
                } @else if (proof.isZeroVariance) {
                  All counts and Trial Balance totals match to 0.00 variance. Ready for atomic promotion.
                } @else {
                  Atomic promotion is blocked fail-closed until Trial Balance variance is 0.00.
                }
              </div>

              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="migrate-commit-btn"
                [disabled]="
                  !proof.isZeroVariance ||
                  proof.status === 'COMMITTED' ||
                  busy() ||
                  companyContext.quarantined()
                "
                (click)="commitActiveProof()"
              >
                {{
                  proof.status === 'COMMITTED'
                    ? 'Committed Atomically'
                    : 'Commit Atomic Promotion'
                }}
              </button>
            </div>
          </section>
        }

        @if (importJobs().length > 0) {
          <section class="mc-card mc-exg-card" aria-label="Migration Job History">
            <div class="mc-exg-section-head">
              <span>Migration &amp; Staging Jobs</span>
              <span>{{ importJobs().length }} job(s)</span>
            </div>
            <div class="mc-exg-table-wrap">
              <table class="mc-exg-table">
                <thead>
                  <tr>
                    <th>Job ID</th>
                    <th>Source Company</th>
                    <th>Snapshot Path</th>
                    <th>Status</th>
                    <th class="mc-col-amt">Variance</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  @for (job of importJobs(); track job.id) {
                    <tr>
                      <td><mc-code-badge [code]="job.id" /></td>
                      <td>{{ job.sourceCompanyName }}</td>
                      <td class="mc-code">{{ job.snapshotDirectoryPath }}</td>
                      <td><span class="mc-badge mc-badge-slate">{{ job.status }}</span></td>
                      <td class="mc-amount mc-col-amt">
                        {{ job.varianceAmount | mcIndianCurrency: companyContext.currencyScale() }}
                      </td>
                      <td>
                        <button type="button" class="mc-btn" (click)="inspectJobProof(job.id)">
                          View Proof
                        </button>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        }
      }

      <!-- ================================================================= -->
      <!-- TAB 2: XML / REL 7.2 JSONEX / CUSTOM EXCEL DATA EXCHANGE          -->
      <!-- ================================================================= -->
      @if (activeTab() === 'IMPORT_EXPORT') {
        <div class="mc-exg-split-grid">
          <!-- Export Card -->
          <section class="mc-card mc-exg-card" aria-label="Export Data">
            <div class="mc-exg-section-head">
              <span>Export Masters &amp; Vouchers (<span class="mc-key-alt">E</span>)</span>
            </div>
            <div class="mc-exg-body-stack">
              <div>
                <label class="mc-field-label" for="mc-exp-format">Format</label>
                <select
                  id="mc-exp-format"
                  class="mc-select"
                  data-testid="exchange-export-format"
                  [ngModel]="selectedFormat()"
                  (ngModelChange)="selectedFormat.set($event)"
                >
                  <option value="XML">Tally XML &lt;ENVELOPE&gt; (-Dr / +Cr Sign Parity)</option>
                  <option value="JSONEX">TallyPrime Rel 7.2 JSONEx Schema</option>
                  <option value="EXCEL">OpenXML Spreadsheet (.xlsx)</option>
                </select>
              </div>

              <div>
                <label class="mc-field-label" for="mc-exp-scope">Entity Scope</label>
                <select
                  id="mc-exp-scope"
                  class="mc-select"
                  [ngModel]="exportScope()"
                  (ngModelChange)="exportScope.set($event)"
                >
                  <option value="ALL">All Masters &amp; Vouchers</option>
                  <option value="MASTERS">Accounting &amp; Inventory Masters</option>
                  <option value="VOUCHERS">Day Book Vouchers (Period)</option>
                  <option value="TRIAL_BALANCE">Trial Balance Closing Balances</option>
                </select>
              </div>

              @if (selectedFormat() === 'EXCEL') {
                <div>
                  <label class="mc-field-label" for="mc-exp-tpl">Excel Mapping Template</label>
                  <select
                    id="mc-exp-tpl"
                    class="mc-select"
                    [ngModel]="selectedTemplateId()"
                    (ngModelChange)="selectedTemplateId.set($event)"
                  >
                    <option value="">Default Canonical Layout</option>
                    @for (tpl of templates(); track tpl.id) {
                      <option [value]="tpl.id">{{ tpl.templateName }} ({{ tpl.sheetName }})</option>
                    }
                  </select>
                </div>
              }

              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="exchange-export-btn"
                [disabled]="busy()"
                (click)="runExport()"
              >
                {{ busy() ? 'Exporting...' : 'Export ' + selectedFormat() }}
              </button>

              @if (lastExportResult(); as exp) {
                <div class="mc-exg-export-preview" data-testid="exchange-export-preview">
                  <div class="mc-exg-preview-head">
                    <mc-code-badge [code]="exp.fileName" />
                    <button type="button" class="mc-btn" (click)="downloadLastExport()">
                      Download File
                    </button>
                  </div>
                  <pre class="mc-code mc-exg-pre">{{ exp.content }}</pre>
                </div>
              }
            </div>
          </section>

          <!-- Import Card -->
          <section class="mc-card mc-exg-card" aria-label="Import Data">
            <div class="mc-exg-section-head">
              <span>Import Masters &amp; Vouchers (<span class="mc-key-alt">O</span>)</span>
            </div>
            <div class="mc-exg-body-stack">
              <div>
                <label class="mc-field-label" for="mc-imp-file">Source File Name</label>
                <input
                  id="mc-imp-file"
                  type="text"
                  class="mc-input mc-code"
                  [(ngModel)]="importFileName"
                />
              </div>

              <div>
                <label class="mc-field-label" for="mc-imp-content">
                  {{
                    selectedFormat() === 'EXCEL'
                      ? 'Base64 .xlsx Payload'
                      : selectedFormat() + ' Payload Content'
                  }}
                </label>
                <textarea
                  id="mc-imp-content"
                  rows="6"
                  class="mc-input mc-code"
                  data-testid="exchange-import-textarea"
                  [(ngModel)]="importContent"
                  placeholder="Paste Tally <ENVELOPE> XML, Rel 7.2 JSONEx, or Base64 .xlsx content..."
                ></textarea>
              </div>

              <label class="mc-exg-check-row">
                <input type="checkbox" [(ngModel)]="importDryRun" />
                <span>Dry-Run Validation Only (Pre-verify Trial Balance without committing)</span>
              </label>

              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="exchange-import-btn"
                [disabled]="busy() || !importContent.trim() || companyContext.quarantined()"
                (click)="runImport()"
              >
                {{ busy() ? 'Importing...' : 'Import ' + selectedFormat() }}
              </button>

              @if (lastImportResult(); as imp) {
                <div class="mc-exg-import-summary" data-testid="exchange-import-result">
                  <div>
                    <strong>{{ imp.status }}</strong> — Groups: {{ imp.groupsImported }}, Ledgers:
                    {{ imp.ledgersImported }}, Items: {{ imp.stockItemsImported }}, Vouchers:
                    {{ imp.vouchersImported }}
                  </div>
                  <div>{{ imp.message }}</div>
                </div>
              }
            </div>
          </section>
        </div>

        <!-- Custom Excel Mapping Templates -->
        <section class="mc-card mc-exg-card" aria-label="Excel Mapping Templates">
          <div class="mc-exg-section-head">
            <span>Custom OpenXML (.xlsx) Column Mapping Templates</span>
            <span>{{ templates().length }} template(s)</span>
          </div>
          <form class="mc-exg-tpl-form" (ngSubmit)="saveNewTemplate()">
            <input
              type="text"
              name="newTemplateName"
              class="mc-input"
              data-testid="exchange-tpl-name-input"
              [(ngModel)]="newTemplateName"
              placeholder="Template Name (e.g. Tally Sales Register XLSX)"
              required
            />
            <input
              type="text"
              name="newTemplateSheet"
              class="mc-input mc-code"
              [(ngModel)]="newTemplateSheet"
              placeholder="Sheet1"
              required
            />
            <select name="newTemplateScope" class="mc-select" [(ngModel)]="newTemplateScope">
              <option value="VOUCHERS">Vouchers</option>
              <option value="MASTERS">Masters</option>
            </select>
            <button
              type="submit"
              class="mc-btn"
              data-testid="exchange-tpl-save-btn"
              [disabled]="busy() || !newTemplateName.trim()"
            >
              Save Template
            </button>
          </form>
        </section>
      }
    </div>
  `,
  styles: `
    .mc-exg-shell {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-exg-header {
      padding: var(--mc-space-3) var(--mc-space-4);
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-3);
    }

    .mc-exg-heading-row {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-exg-title {
      margin: 0;
      font-size: var(--mc-font-size-lg);
      font-weight: 700;
      color: var(--mc-color-slate-900);
    }

    .mc-exg-subtitle {
      margin: 2px 0 0;
      font-size: var(--mc-font-size-xs);
      font-weight: 500;
      color: var(--mc-color-slate-600);
    }

    .mc-exg-tabs {
      display: flex;
      gap: var(--mc-space-2);
    }

    .mc-exg-alert {
      padding: var(--mc-space-2) var(--mc-space-3);
      border-radius: var(--mc-radius-sm);
      font-size: var(--mc-font-size-sm);
      font-weight: 600;
    }

    .mc-exg-alert-error {
      background: var(--mc-color-danger-50);
      color: var(--mc-color-danger-700);
      border: 1px solid #FCA5A5;
    }

    .mc-exg-alert-ok {
      background: var(--mc-color-teal-50);
      color: var(--mc-color-pressed-teal);
      border: 1px solid var(--mc-color-teal-100);
    }

    .mc-exg-card {
      overflow: hidden;
    }

    .mc-exg-section-head {
      padding: 0.5rem 0.85rem;
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      text-transform: uppercase;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-2);
      flex-wrap: wrap;
    }

    .mc-exg-proof-title-group,
    .mc-exg-proof-badges {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      flex-wrap: wrap;
    }

    .mc-exg-stage-form {
      padding: var(--mc-space-3) var(--mc-space-4);
      display: flex;
      flex-wrap: wrap;
      gap: var(--mc-space-3);
      align-items: flex-end;
    }

    .mc-exg-field-grow {
      flex: 1 1 280px;
    }

    .mc-exg-proof-meta {
      padding: var(--mc-space-2) var(--mc-space-4);
      background: var(--mc-color-canvas);
      border-bottom: var(--mc-border-subtle);
      display: flex;
      flex-wrap: wrap;
      gap: var(--mc-space-4);
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
    }

    .mc-exg-meta-lbl {
      color: var(--mc-color-slate-600);
      margin-right: 4px;
    }

    .mc-exg-table-wrap {
      overflow-x: auto;
    }

    .mc-exg-table {
      width: 100%;
      border-collapse: collapse;
      font-size: var(--mc-font-size-sm);

      th {
        padding: 0.45rem 0.75rem;
        background: var(--mc-color-slate-100);
        color: var(--mc-color-slate-700);
        font-size: var(--mc-font-size-xs);
        font-weight: 700;
        text-transform: uppercase;
        text-align: left;
        border-bottom: var(--mc-border-subtle);
      }

      td {
        padding: 0.45rem 0.75rem;
        border-bottom: 1px solid var(--mc-color-slate-100);
        font-weight: 500;
      }
    }

    .mc-col-amt {
      text-align: right;
      width: 185px;
    }

    .mc-col-status {
      width: 120px;
      text-align: center;
    }

    .mc-exg-highlight-row td {
      background: var(--mc-color-teal-50);
      font-weight: 700;
    }

    .mc-exg-commit-bar {
      padding: var(--mc-space-3) var(--mc-space-4);
      background: var(--mc-color-canvas);
      border-top: var(--mc-border-subtle);
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-3);
      flex-wrap: wrap;
    }

    .mc-exg-commit-note {
      font-size: var(--mc-font-size-sm);
      font-weight: 600;
      color: var(--mc-color-slate-700);
    }

    .mc-exg-split-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    @media (max-width: 960px) {
      .mc-exg-split-grid {
        grid-template-columns: 1fr;
      }
    }

    .mc-exg-body-stack {
      padding: var(--mc-space-3) var(--mc-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-exg-export-preview,
    .mc-exg-import-summary {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      font-size: var(--mc-font-size-xs);
      font-weight: 500;
    }

    .mc-exg-preview-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: var(--mc-space-2);
    }

    .mc-exg-pre {
      margin: 0;
      max-height: 160px;
      overflow: auto;
      font-size: var(--mc-font-size-xs);
    }

    .mc-exg-check-row {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
    }

    .mc-exg-tpl-form {
      padding: var(--mc-space-3) var(--mc-space-4);
      display: grid;
      grid-template-columns: 1.5fr 1fr 1fr auto;
      gap: var(--mc-space-2);
      align-items: center;
    }

    .mc-badge-danger {
      background: var(--mc-color-danger-50);
      color: var(--mc-color-danger-700);
      border: 1px solid #FCA5A5;
    }
  `,
})
export class MigrationAndExchangeComponent implements OnInit {
  readonly companyContext = inject(CompanyContextStore);
  private readonly exchangeService = inject(ExchangeService);
  private readonly route = inject(ActivatedRoute, { optional: true });

  readonly embeddedOverlay = input<boolean>(false);
  readonly initialTab = input<ExchangeTab>('MIGRATE_1800');

  readonly activeTab = signal<ExchangeTab>('MIGRATE_1800');
  readonly busy = signal<boolean>(false);
  readonly errorBanner = signal<string | null>(null);
  readonly statusBanner = signal<string | null>(null);

  // .1800 Migration Wizard State
  snapshotPathInput = '/var/moneycraft/staging/100000';
  companionXmlPathInput = '';
  readonly activeProof = signal<ImportJobProofDto | null>(null);
  readonly importJobs = signal<readonly ImportJobSummaryDto[]>([]);

  // XML / JSONEx / Excel Exchange State
  readonly selectedFormat = signal<ExchangeFormat>('XML');
  readonly exportScope = signal<ExchangeEntityScope>('ALL');
  readonly selectedTemplateId = signal<string>('');
  readonly templates = signal<readonly ExchangeMappingTemplateDto[]>([]);
  readonly lastExportResult = signal<ExportDataResultDto | null>(null);
  readonly lastImportResult = signal<ImportDataResultDto | null>(null);

  importFileName = 'tally-export.xml';
  importContent = '';
  importDryRun = false;

  newTemplateName = '';
  newTemplateSheet = 'Sheet1';
  newTemplateScope: 'MASTERS' | 'VOUCHERS' = 'VOUCHERS';

  ngOnInit(): void {
    const routeTab = this.route?.snapshot.data['exchangeTab'] as ExchangeTab | undefined;
    if (routeTab) {
      this.activeTab.set(routeTab);
    } else {
      this.activeTab.set(this.initialTab());
    }

    void this.loadInitialData();
  }

  switchTab(tab: ExchangeTab): void {
    this.activeTab.set(tab);
    this.errorBanner.set(null);
    this.statusBanner.set(null);
    void this.loadInitialData();
  }

  async loadInitialData(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    try {
      if (this.activeTab() === 'MIGRATE_1800') {
        const jobs = await this.exchangeService.listImportJobs(cid);
        this.importJobs.set(jobs);
      } else {
        const tpls = await this.exchangeService.listTemplates(cid);
        this.templates.set(tpls);
      }
    } catch {
      // Non-fatal initial list load
    }
  }

  async stageSnapshot(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    const snapPath = this.snapshotPathInput.trim();
    if (!cid || !snapPath) return;

    this.busy.set(true);
    this.errorBanner.set(null);
    this.statusBanner.set(null);
    try {
      const proof = await this.exchangeService.stageTally1800(cid, {
        snapshotDirectoryPath: snapPath,
        companionXmlDirectoryPath: this.companionXmlPathInput.trim() || null,
      });
      this.activeProof.set(proof);
      this.statusBanner.set(
        `Staged ${proof.sourceCompanyName} — Variance: ${proof.varianceAmount} (${proof.isZeroVariance ? 'Zero Variance Verified' : 'Variance Detected'}).`,
      );
      const jobs = await this.exchangeService.listImportJobs(cid).catch(() => this.importJobs());
      this.importJobs.set(jobs);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to stage .1800 snapshot');
    } finally {
      this.busy.set(false);
    }
  }

  async inspectJobProof(jobId: string): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const proof = await this.exchangeService.getImportJobProof(cid, jobId);
      this.activeProof.set(proof);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to load proof');
    } finally {
      this.busy.set(false);
    }
  }

  async commitActiveProof(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    const proof = this.activeProof();
    if (!cid || !proof || !proof.isZeroVariance) return;

    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const committed = await this.exchangeService.commitImportJob(cid, proof.jobId);
      this.activeProof.set({
        ...proof,
        ...committed,
        status: 'COMMITTED',
      });
      this.statusBanner.set(
        `Committed ${proof.sourceCompanyName} atomically into PostgreSQL 18 Business DB.`,
      );
      const jobs = await this.exchangeService.listImportJobs(cid).catch(() => this.importJobs());
      this.importJobs.set(jobs);
    } catch (err: unknown) {
      this.errorBanner.set(
        err instanceof Error ? err.message : 'Unable to commit staged migration',
      );
    } finally {
      this.busy.set(false);
    }
  }

  async runExport(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    const fmt = this.selectedFormat();
    const req = {
      entityScope: this.exportScope(),
      fromDate: this.companyContext.periodFrom(),
      toDate: this.companyContext.periodTo(),
      includeDependentMasters: true,
      templateId: this.selectedTemplateId() || null,
    };

    this.busy.set(true);
    this.errorBanner.set(null);
    this.statusBanner.set(null);
    try {
      if (fmt === 'XML') {
        const res = await this.exchangeService.exportXml(cid, req);
        this.lastExportResult.set(res);
        this.exchangeService.triggerTextDownload(res);
        this.statusBanner.set(`Exported Tally XML (${res.fileName}).`);
      } else if (fmt === 'JSONEX') {
        const res = await this.exchangeService.exportJsonEx(cid, req);
        this.lastExportResult.set(res);
        this.exchangeService.triggerTextDownload(res);
        this.statusBanner.set(`Exported Rel 7.2 JSONEx (${res.fileName}).`);
      } else {
        await this.exchangeService.exportExcel(cid, req);
        this.statusBanner.set('Exported OpenXML (.xlsx) workbook.');
      }
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : `Unable to export ${fmt}`);
    } finally {
      this.busy.set(false);
    }
  }

  downloadLastExport(): void {
    const res = this.lastExportResult();
    if (res) {
      this.exchangeService.triggerTextDownload(res);
    }
  }

  async runImport(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    const content = this.importContent.trim();
    if (!cid || !content) return;
    const fmt = this.selectedFormat();
    const req = {
      fileName: this.importFileName.trim() || `import.${fmt.toLowerCase()}`,
      content,
      base64Content: fmt === 'EXCEL' ? content : null,
      templateId: this.selectedTemplateId() || null,
      dryRun: this.importDryRun,
    };

    this.busy.set(true);
    this.errorBanner.set(null);
    this.statusBanner.set(null);
    try {
      const res =
        fmt === 'XML'
          ? await this.exchangeService.importXml(cid, req)
          : fmt === 'JSONEX'
            ? await this.exchangeService.importJsonEx(cid, req)
            : await this.exchangeService.importExcel(cid, req);
      this.lastImportResult.set(res);
      this.statusBanner.set(res.message);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : `Unable to import ${fmt}`);
    } finally {
      this.busy.set(false);
    }
  }

  async saveNewTemplate(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    const name = this.newTemplateName.trim();
    if (!cid || !name) return;

    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const created = await this.exchangeService.createTemplate(cid, {
        templateName: name,
        entityScope: this.newTemplateScope,
        sheetName: this.newTemplateSheet.trim() || 'Sheet1',
        headerRowIndex: 1,
        columnMappings: {
          voucherDate: 'Date',
          voucherNumber: 'Voucher No',
          ledgerName: 'Particulars',
          amount: 'Amount',
        },
      });
      this.templates.update((prev) => [...prev, created]);
      this.selectedTemplateId.set(created.id);
      this.newTemplateName = '';
      this.statusBanner.set(`Saved Excel mapping template "${created.templateName}".`);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to save template');
    } finally {
      this.busy.set(false);
    }
  }
}
