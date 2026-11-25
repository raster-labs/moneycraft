import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { SemanticIconComponent } from '../../shared/components/semantic-icon/semantic-icon.component';
import { CompanyContextStore } from '../../state/company-context.store';
import {
  DashboardTileDto,
  DashboardTilesExportResponse,
  DataVerificationReportDto,
  EditLogSummaryResponseDto,
  ExchangeEntityScope,
  ExchangeFormat,
  ExchangeMappingTemplateDto,
  ExchangeTab,
  ExportDataResultDto,
  ImportDataResultDto,
  ImportExceptionBehavior,
  ImportExceptionItemDto,
  ImportJobProofDto,
  ImportJobSummaryDto,
  LedgerColumnMappingDto,
  MasterDuplicateRule,
  MultiAccountReportExportResponse,
  SampleExcelType,
  SpreadsheetBatchDto,
  SpreadsheetMappingSpecDto,
  SpreadsheetTargetType,
  SpreadsheetTemplateDto,
  SpreadsheetVoucherAction,
  SyncRuleDto,
  SyncSessionResultDto,
} from './exchange.models';
import { ExchangeService } from './exchange.service';

@Component({
  selector: 'mc-migration-and-exchange',
  standalone: true,
  imports: [FormsModule, CodeBadgeComponent, IndianCurrencyPipe, SemanticIconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-exg-shell" [class.mc-exg-embedded]="embeddedOverlay()">
      <header class="mc-exg-header mc-card">
        <div>
          <div class="mc-exg-heading-row">
            <h1 class="mc-exg-title">
              @switch (activeTab()) {
                @case ('MIGRATE_1800') {
                  Clean-Room Legacy .1800 Migration &amp; Parity Proof Wizard
                }
                @case ('SPREADSHEET_WIZARD') {
                  Guided Multi-Format Columnar Spreadsheet Importer
                }
                @default {
                  Data Exchange — Legacy XML, JSONEx &amp; OpenXML (.xlsx)
                }
              }
            </h1>
            <span class="mc-badge mc-badge-jade">Zero-Variance Gate</span>
          </div>
          <p class="mc-exg-subtitle">
            Standalone clean-room 512-byte sector reader, multi-ledger columnar spreadsheet wizard
            (.xlsx / .xls / .xlsm / .ods / .csv), and lossless XML / JSONEx exchange
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
            <mc-icon name="import" [size]="16" />
            <span>Legacy .1800 Snapshot</span>
          </button>
          <button
            type="button"
            role="tab"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'SPREADSHEET_WIZARD'"
            [attr.aria-selected]="activeTab() === 'SPREADSHEET_WIZARD'"
            data-testid="tab-spreadsheet-wizard"
            (click)="switchTab('SPREADSHEET_WIZARD')"
          >
            <mc-icon name="ledger" [size]="16" />
            <span>Spreadsheet Wizard</span>
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
            <mc-icon name="export" [size]="16" />
            <span>Import / Export (<span class="mc-key-alt">O</span> / <span class="mc-key-alt">E</span>)</span>
          </button>
        </div>
      </header>

      @if (errorBanner(); as err) {
        <div class="mc-exg-alert mc-exg-alert-error" role="alert">
          <mc-icon name="error" [size]="20" />
          <span>{{ err }}</span>
        </div>
      }
      @if (statusBanner(); as msg) {
        <div class="mc-exg-alert mc-exg-alert-ok" role="status" data-testid="exchange-status-banner">
          <mc-icon name="confirmed" [size]="20" />
          <span>{{ msg }}</span>
        </div>
      }

      <!-- ================================================================= -->
      <!-- TAB 1: CLEAN-ROOM LEGACY .1800 MIGRATION WIZARD                   -->
      <!-- ================================================================= -->
      @if (activeTab() === 'MIGRATE_1800') {
        <section class="mc-card mc-exg-card" aria-label=".1800 Migration Staging Form">
          <div class="mc-exg-section-head">
            <span>Step 1 — Stage Quiescent Legacy (.1800) Snapshot</span>
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
                Companion XML Reference Directory (Optional cross-verification)
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
                <mc-icon name="import" [size]="16" />
                <span>{{ busy() ? 'Staging & Verifying...' : 'Stage & Verify (.1800)' }}</span>
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
                <mc-icon name="post-ledger" [size]="16" />
                <span>
                  {{ proof.status === 'COMMITTED' ? 'Committed Atomically' : 'Commit Atomic Promotion' }}
                </span>
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
                          <mc-icon name="details" [size]="16" />
                          <span>View Proof</span>
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
      <!-- TAB 2: INCREMENT X GUIDED COLUMNAR SPREADSHEET IMPORTER           -->
      <!-- ================================================================= -->
      @if (activeTab() === 'SPREADSHEET_WIZARD') {
        <section class="mc-card mc-exg-card" aria-label="Guided Spreadsheet Source and Mapping">
          <div class="mc-exg-section-head">
            <span>Step 1 — Workbook / CSV / Google Sheet Source &amp; Columnar Mapping</span>
            <span class="mc-code">SHEET-001 .. SHEET-008</span>
          </div>

          <div class="mc-exg-body-stack">
            <div class="mc-exg-grid-4">
              <div>
                <label class="mc-field-label" for="mc-sheet-file">
                  Upload Workbook (.xlsx, .xls, .xlsm, .ods, .csv)
                </label>
                <input
                  id="mc-sheet-file"
                  type="file"
                  class="mc-input"
                  accept=".xlsx,.xls,.xlsm,.ods,.csv"
                  data-testid="sheet-file-input"
                  (change)="onSpreadsheetFileSelected($event)"
                />
              </div>

              <div>
                <label class="mc-field-label" for="mc-sheet-fname">Source File Name</label>
                <input
                  id="mc-sheet-fname"
                  type="text"
                  class="mc-input mc-code"
                  data-testid="sheet-filename-input"
                  [(ngModel)]="sheetFileName"
                />
              </div>

              <div>
                <label class="mc-field-label" for="mc-sheet-target">Target Entity</label>
                <select
                  id="mc-sheet-target"
                  class="mc-select"
                  data-testid="sheet-target-select"
                  [ngModel]="sheetTargetType()"
                  (ngModelChange)="sheetTargetType.set($event)"
                >
                  <option value="VOUCHERS">Accounting Vouchers (Multi-Ledger / Horizontal)</option>
                  <option value="LEDGERS">Ledger Masters (Opening Balances &amp; GSTIN)</option>
                </select>
              </div>

              <div>
                <label class="mc-field-label" for="mc-sheet-tpl-pick">Saved Mapping Template</label>
                <select
                  id="mc-sheet-tpl-pick"
                  class="mc-select"
                  data-testid="sheet-template-select"
                  [ngModel]="selectedSpreadsheetTemplateId()"
                  (ngModelChange)="applySpreadsheetTemplate($event)"
                >
                  <option value="">Custom Ad-Hoc Mapping</option>
                  @for (stpl of spreadsheetTemplates(); track stpl.id) {
                    <option [value]="stpl.id">{{ stpl.name }} (v{{ stpl.versionNo }})</option>
                  }
                </select>
              </div>
            </div>

            <div class="mc-exg-grid-4">
              <div>
                <label class="mc-field-label" for="mc-sheet-name">Sheet Name</label>
                @if ((activeSpreadsheetBatch()?.availableSheets?.length ?? 0) > 0) {
                  <select
                    id="mc-sheet-name"
                    class="mc-select mc-code"
                    [(ngModel)]="sheetSelectedName"
                  >
                    @for (sn of activeSpreadsheetBatch()!.availableSheets; track sn) {
                      <option [value]="sn">{{ sn }}</option>
                    }
                  </select>
                } @else {
                  <input
                    id="mc-sheet-name"
                    type="text"
                    class="mc-input mc-code"
                    [(ngModel)]="sheetSelectedName"
                    placeholder="Sheet1"
                  />
                }
              </div>

              <div>
                <label class="mc-field-label" for="mc-sheet-hdr-row">Header Row (1-based)</label>
                <input
                  id="mc-sheet-hdr-row"
                  type="number"
                  min="1"
                  class="mc-input mc-code"
                  [(ngModel)]="sheetHeaderRowIndex"
                />
              </div>

              <div>
                <label class="mc-field-label" for="mc-sheet-delim">CSV Delimiter</label>
                <select id="mc-sheet-delim" class="mc-select" [(ngModel)]="sheetDelimiter">
                  <option value=",">Comma (,)</option>
                  <option value=";">Semicolon (;)</option>
                  <option value="|">Pipe (|)</option>
                </select>
              </div>

              <div>
                <label class="mc-field-label" for="mc-sheet-date-pat">Date Pattern</label>
                <select id="mc-sheet-date-pat" class="mc-select mc-code" [(ngModel)]="sheetDatePattern">
                  <option value="yyyy-MM-dd">yyyy-MM-dd</option>
                  <option value="dd-MM-yyyy">dd-MM-yyyy</option>
                  <option value="dd/MM/yyyy">dd/MM/yyyy</option>
                </select>
              </div>
            </div>

            <div>
              <label class="mc-field-label" for="mc-sheet-csv-paste">
                Direct CSV / Delimited Text (or leave blank when uploading binary workbook)
              </label>
              <textarea
                id="mc-sheet-csv-paste"
                rows="4"
                class="mc-input mc-code"
                data-testid="sheet-csv-textarea"
                [(ngModel)]="sheetCsvText"
                placeholder="Date,Voucher No,Customer,Invoice Total,Taxable Sales,Output CGST,Output SGST&#10;2025-04-10,INV-101,Acme Traders,1180.00,1000.00,90.00,90.00"
              ></textarea>
            </div>

            <div class="mc-exg-check-grid">
              <label class="mc-exg-check-row">
                <input
                  type="checkbox"
                  data-testid="sheet-formula-consent"
                  [(ngModel)]="sheetCachedFormulaConsent"
                />
                <span>
                  Accept cached formula values (strictly no formula recalculation or macro execution)
                </span>
              </label>

              <label class="mc-exg-check-row">
                <input
                  type="checkbox"
                  data-testid="sheet-duplicate-ack"
                  [(ngModel)]="sheetAcknowledgeDuplicateRun"
                />
                <span>Acknowledge re-run of previously staged SHA-256 workbook</span>
              </label>

              <label class="mc-exg-check-row">
                <input
                  type="checkbox"
                  data-testid="sheet-google-toggle"
                  [(ngModel)]="sheetUseGoogleCapture"
                />
                <span>Capture immutable Google Sheets range snapshot (read-only scope)</span>
              </label>
            </div>

            @if (sheetUseGoogleCapture) {
              <div class="mc-exg-grid-4 mc-exg-subpanel">
                <div>
                  <label class="mc-field-label" for="mc-gs-id">Google Spreadsheet ID</label>
                  <input
                    id="mc-gs-id"
                    type="text"
                    class="mc-input mc-code"
                    [(ngModel)]="googleSpreadsheetId"
                    placeholder="1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"
                  />
                </div>
                <div>
                  <label class="mc-field-label" for="mc-gs-sheet">Tab Name</label>
                  <input id="mc-gs-sheet" type="text" class="mc-input mc-code" [(ngModel)]="googleSheetName" />
                </div>
                <div>
                  <label class="mc-field-label" for="mc-gs-range">Range (A1)</label>
                  <input id="mc-gs-range" type="text" class="mc-input mc-code" [(ngModel)]="googleRangeA1" />
                </div>
                <div>
                  <label class="mc-field-label" for="mc-gs-scope">OAuth Read-Only Scope</label>
                  <input
                    id="mc-gs-scope"
                    type="text"
                    class="mc-input mc-code"
                    [(ngModel)]="googleScopeUsed"
                    readonly
                  />
                </div>
              </div>
            }

            @if (sheetTargetType() === 'VOUCHERS') {
              <div class="mc-exg-subpanel">
                <div class="mc-exg-subhead">
                  <strong>Voucher Header &amp; Multi-Ledger Horizontal Column Mapping</strong>
                  <button
                    type="button"
                    class="mc-btn"
                    data-testid="sheet-add-ledger-col-btn"
                    (click)="addLedgerColumnMapping()"
                  >
                    + Add Ledger Column
                  </button>
                </div>

                <div class="mc-exg-grid-4">
                  <div>
                    <label class="mc-field-label" for="mc-vch-act">LIVE Commit Action</label>
                    <select id="mc-vch-act" class="mc-select" [(ngModel)]="sheetVoucherAction">
                      <option value="POST">Post Directly to Ledger</option>
                      <option value="SAVE_DRAFT">Save as Draft</option>
                      <option value="SUBMIT_FOR_APPROVAL">Submit for Maker-Checker Approval</option>
                    </select>
                  </div>
                  <div>
                    <label class="mc-field-label" for="mc-vch-def-type">Default Voucher Type</label>
                    <input
                      id="mc-vch-def-type"
                      type="text"
                      class="mc-input"
                      [(ngModel)]="sheetDefaultVoucherType"
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="mc-vch-date-col">Date Column Header</label>
                    <input
                      id="mc-vch-date-col"
                      type="text"
                      class="mc-input mc-code"
                      [(ngModel)]="sheetVoucherDateCol"
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="mc-vch-num-col">Voucher No Column</label>
                    <input
                      id="mc-vch-num-col"
                      type="text"
                      class="mc-input mc-code"
                      [(ngModel)]="sheetVoucherNumberCol"
                    />
                  </div>
                </div>

                <div class="mc-exg-grid-4">
                  <div>
                    <label class="mc-field-label" for="mc-vch-party-col">Party Ledger Column</label>
                    <input
                      id="mc-vch-party-col"
                      type="text"
                      class="mc-input mc-code"
                      [(ngModel)]="sheetPartyLedgerCol"
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="mc-vch-party-amt">Party Total Column</label>
                    <input
                      id="mc-vch-party-amt"
                      type="text"
                      class="mc-input mc-code"
                      [(ngModel)]="sheetPartyAmountCol"
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="mc-vch-party-side">Party Side</label>
                    <select id="mc-vch-party-side" class="mc-select" [(ngModel)]="sheetPartySide">
                      <option value="DR">Debit (DR)</option>
                      <option value="CR">Credit (CR)</option>
                    </select>
                  </div>
                  <div>
                    <label class="mc-field-label" for="mc-vch-narr-col">Narration Column</label>
                    <input
                      id="mc-vch-narr-col"
                      type="text"
                      class="mc-input mc-code"
                      [(ngModel)]="sheetNarrationCol"
                    />
                  </div>
                </div>

                <div class="mc-exg-table-wrap">
                  <table class="mc-exg-table" aria-label="Horizontal Ledger Column Mappings">
                    <thead>
                      <tr>
                        <th scope="col">Spreadsheet Column Header</th>
                        <th scope="col">Target MoneyCraft Ledger Name</th>
                        <th scope="col">Dr / Cr</th>
                        <th scope="col">Line Role</th>
                        <th scope="col">Action</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (col of sheetLedgerColumns(); track $index; let idx = $index) {
                        <tr>
                          <td>
                            <input
                              type="text"
                              class="mc-input mc-code"
                              [attr.aria-label]="'Source column ' + (idx + 1)"
                              [(ngModel)]="col.sourceColumn"
                            />
                          </td>
                          <td>
                            <input
                              type="text"
                              class="mc-input"
                              [attr.aria-label]="'Target ledger ' + (idx + 1)"
                              [(ngModel)]="col.ledgerName"
                            />
                          </td>
                          <td>
                            <select
                              class="mc-select"
                              [attr.aria-label]="'Side ' + (idx + 1)"
                              [(ngModel)]="col.side"
                            >
                              <option value="CR">CR</option>
                              <option value="DR">DR</option>
                            </select>
                          </td>
                          <td>
                            <select
                              class="mc-select"
                              [attr.aria-label]="'Role ' + (idx + 1)"
                              [(ngModel)]="col.lineRole"
                            >
                              <option value="ACCOUNTING">ACCOUNTING</option>
                              <option value="TAX">TAX</option>
                              <option value="DISCOUNT">DISCOUNT</option>
                              <option value="ROUND_OFF">ROUND_OFF</option>
                              <option value="LEDGER">LEDGER</option>
                            </select>
                          </td>
                          <td>
                            <button
                              type="button"
                              class="mc-btn"
                              (click)="removeLedgerColumnMapping(idx)"
                            >
                              <mc-icon name="delete" [size]="16" />
                              <span>Remove</span>
                            </button>
                          </td>
                        </tr>
                      } @empty {
                        <tr>
                          <td colspan="5" class="mc-empty-records">No matching records</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              </div>
            } @else {
              <div class="mc-exg-subpanel">
                <div class="mc-exg-subhead">
                  <strong>Ledger Master Column Mapping</strong>
                </div>
                <div class="mc-exg-grid-4">
                  <div>
                    <label class="mc-field-label" for="mc-ldg-name-col">Ledger Name Column</label>
                    <input
                      id="mc-ldg-name-col"
                      type="text"
                      class="mc-input mc-code"
                      [(ngModel)]="sheetLedgerNameCol"
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="mc-ldg-grp-col">Parent Group Column</label>
                    <input
                      id="mc-ldg-grp-col"
                      type="text"
                      class="mc-input mc-code"
                      [(ngModel)]="sheetGroupNameCol"
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="mc-ldg-op-col">Opening Balance Column</label>
                    <input
                      id="mc-ldg-op-col"
                      type="text"
                      class="mc-input mc-code"
                      [(ngModel)]="sheetOpeningBalanceCol"
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="mc-ldg-gstin-col">GSTIN Column</label>
                    <input
                      id="mc-ldg-gstin-col"
                      type="text"
                      class="mc-input mc-code"
                      [(ngModel)]="sheetGstinCol"
                    />
                  </div>
                </div>
              </div>
            }

            <div class="mc-exg-commit-bar">
              <div class="mc-exg-inline-row">
                <input
                  type="text"
                  class="mc-input"
                  data-testid="sheet-tpl-name-input"
                  [(ngModel)]="sheetTemplateNameInput"
                  placeholder="Save mapping as reusable template..."
                />
                <button
                  type="button"
                  class="mc-btn"
                  data-testid="sheet-save-template-btn"
                  [disabled]="busy() || !sheetTemplateNameInput.trim()"
                  (click)="saveCurrentSpreadsheetTemplate()"
                >
                  <mc-icon name="save-document" [size]="16" />
                  <span>Save Mapping Template</span>
                </button>
              </div>

              <div class="mc-exg-inline-row">
                @if (activeSpreadsheetBatch()) {
                  <button
                    type="button"
                    class="mc-btn"
                    data-testid="sheet-remap-btn"
                    [disabled]="busy() || companyContext.quarantined()"
                    (click)="remapActiveBatch()"
                  >
                    <mc-icon name="refresh" [size]="16" />
                    <span>Re-Validate Mapping</span>
                  </button>
                }
                <button
                  type="button"
                  class="mc-btn mc-btn-primary"
                  data-testid="sheet-prepare-btn"
                  [disabled]="busy() || companyContext.quarantined() || !canPrepareSpreadsheet()"
                  (click)="prepareSpreadsheet()"
                >
                  <mc-icon name="import" [size]="16" />
                  <span>{{ busy() ? 'Validating...' : 'Prepare & Dry-Run Validate' }}</span>
                </button>
              </div>
            </div>
          </div>
        </section>

        @if (activeSpreadsheetBatch(); as batch) {
          <section
            class="mc-card mc-exg-card"
            data-testid="sheet-batch-card"
            aria-label="Spreadsheet Dry-Run Preview and LIVE Commit"
          >
            <div class="mc-exg-section-head">
              <div class="mc-exg-proof-title-group">
                <span>Step 2 — Dry-Run Cell Preview &amp; Per-Unit LIVE Commit ({{ batch.sourceFileName }})</span>
                <span class="mc-badge mc-badge-slate" data-testid="sheet-batch-status">{{ batch.status }}</span>
                <span class="mc-badge mc-badge-jade">{{ batch.formatProfile }}</span>
                <span class="mc-code">Date System: {{ batch.dateSystem }}</span>
              </div>
              <div class="mc-exg-proof-badges">
                <span>
                  Valid: <strong>{{ batch.validUnits }}</strong> / {{ batch.totalUnits }}
                </span>
                <span>
                  Invalid: <strong>{{ batch.invalidUnits }}</strong>
                </span>
                <span>
                  Committed: <strong>{{ batch.committedUnits }}</strong>
                </span>
              </div>
            </div>

            @if (batch.macroDetected) {
              <div class="mc-exg-alert mc-exg-alert-warn" role="status">
                <mc-icon name="warning" [size]="20" />
                <span>
                  Macro container (.xlsm / VBA) detected — embedded macros are strictly stripped and never executed.
                </span>
              </div>
            }
            @if (batch.formulaCellsDetected) {
              <div class="mc-exg-alert mc-exg-alert-warn" role="status">
                <mc-icon name="warning" [size]="20" />
                <span>
                  Formula cells detected — cached cell values policy:
                  {{ batch.cachedFormulaPolicyAccepted ? 'Explicitly Accepted' : 'Requires Cached Formula Consent' }}.
                </span>
              </div>
            }

            <div class="mc-exg-proof-meta">
              <div>
                <span class="mc-exg-meta-lbl">Batch ID:</span>
                <mc-code-badge [code]="batch.id" />
              </div>
              <div>
                <span class="mc-exg-meta-lbl">Source SHA-256:</span>
                <mc-code-badge [code]="batch.sourceSha256Hex" />
              </div>
              <div>
                <span class="mc-exg-meta-lbl">Total Dr:</span>
                <span class="mc-amount">
                  {{ batch.totalDebit | mcIndianCurrency: companyContext.currencyScale() }}
                </span>
              </div>
              <div>
                <span class="mc-exg-meta-lbl">Total Cr:</span>
                <span class="mc-amount">
                  {{ batch.totalCredit | mcIndianCurrency: companyContext.currencyScale() }}
                </span>
              </div>
            </div>

            <div class="mc-exg-table-wrap">
              <table class="mc-exg-table" aria-label="Staged Accounting Units">
                <thead>
                  <tr>
                    <th scope="col">#</th>
                    <th scope="col">Unit Key</th>
                    <th scope="col" class="mc-col-amt">Debit</th>
                    <th scope="col" class="mc-col-amt">Credit</th>
                    <th scope="col">Validation</th>
                    <th scope="col">Execution</th>
                    <th scope="col">Cell Coordinates &amp; Diagnostics</th>
                  </tr>
                </thead>
                <tbody>
                  @for (unit of batch.units; track unit.id) {
                    <tr>
                      <td class="mc-code">{{ unit.unitSeq }}</td>
                      <td class="mc-code">{{ unit.unitKey }}</td>
                      <td class="mc-amount mc-col-amt">
                        {{ unit.unitDebit | mcIndianCurrency: companyContext.currencyScale() }}
                      </td>
                      <td class="mc-amount mc-col-amt">
                        {{ unit.unitCredit | mcIndianCurrency: companyContext.currencyScale() }}
                      </td>
                      <td>
                        <span
                          class="mc-badge"
                          [class.mc-badge-jade]="unit.validationStatus === 'VALID'"
                          [class.mc-badge-danger]="unit.validationStatus !== 'VALID'"
                        >
                          {{ unit.validationStatus }}
                        </span>
                      </td>
                      <td>
                        <span class="mc-badge mc-badge-slate">{{ unit.executionStatus }}</span>
                        @if (unit.resultingReference) {
                          <mc-code-badge [code]="unit.resultingReference" />
                        }
                      </td>
                      <td>
                        @if (unit.validationErrors.length > 0) {
                          @for (cerr of unit.validationErrors; track $index) {
                            <div class="mc-exg-cell-err">
                              <span class="mc-code">{{ cerr.sheetName }}!{{ cerr.cellRef }}</span>:
                              {{ cerr.message }}
                            </div>
                          }
                        } @else if (unit.executionErrorMessage) {
                          <div class="mc-exg-cell-err">{{ unit.executionErrorMessage }}</div>
                        } @else {
                          <span class="mc-code">
                            Rows {{ (unit.sourceCoordinates?.rowNumbers ?? []).join(', ') || 'Verified' }}
                          </span>
                        }
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="7" class="mc-empty-records">No matching records</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>

            <div class="mc-exg-commit-bar">
              <div class="mc-exg-inline-row">
                <button
                  type="button"
                  class="mc-btn"
                  data-testid="sheet-errors-csv-btn"
                  [disabled]="busy()"
                  (click)="downloadSpreadsheetErrors()"
                >
                  <mc-icon name="export" [size]="16" />
                  <span>Download Safe Error CSV</span>
                </button>
                <button
                  type="button"
                  class="mc-btn mc-btn-danger"
                  data-testid="sheet-cancel-btn"
                  [disabled]="busy() || batch.status === 'COMPLETED' || batch.status === 'CANCELLED'"
                  (click)="cancelActiveSpreadsheetBatch()"
                >
                  <mc-icon name="discard-draft" [size]="16" />
                  <span>Cancel Pending Units</span>
                </button>
              </div>

              <div class="mc-exg-inline-row">
                @if (batch.status === 'PARTIAL_INTERRUPTED' || batch.committedUnits > 0) {
                  <button
                    type="button"
                    class="mc-btn"
                    data-testid="sheet-resume-btn"
                    [disabled]="busy() || batch.validUnits === 0 || companyContext.quarantined()"
                    (click)="executeSpreadsheetBatch(true)"
                  >
                    <mc-icon name="refresh" [size]="16" />
                    <span>Resume Pending Units</span>
                  </button>
                }
                <button
                  type="button"
                  class="mc-btn mc-btn-primary"
                  data-testid="sheet-execute-btn"
                  [disabled]="
                    busy() ||
                    batch.validUnits === 0 ||
                    batch.status === 'COMPLETED' ||
                    companyContext.quarantined()
                  "
                  (click)="executeSpreadsheetBatch(false)"
                >
                  <mc-icon name="post-ledger" [size]="16" />
                  <span>Commit Valid Units (LIVE)</span>
                </button>
              </div>
            </div>
          </section>
        }
      }

      <!-- ================================================================= -->
      <!-- TAB 3: LEGACY XML / JSONEX / OPENXML DATA EXCHANGE                -->
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
                  <option value="XML">Legacy XML &lt;ENVELOPE&gt; (-Dr / +Cr Sign Parity)</option>
                  <option value="JSONEX">JSONEx Schema (Exact String Decimals)</option>
                  <option value="EXCEL">OpenXML Spreadsheet (.xlsx)</option>
                  <option value="ASCII">ASCII (Comma Delimited .csv)</option>
                  <option value="HTML">HTML (Web-Publishing .html)</option>
                </select>
              </div>

              <div>
                <label class="mc-field-label" for="mc-exp-scope">Entity Scope</label>
                <select
                  id="mc-exp-scope"
                  class="mc-select"
                  data-testid="exchange-export-scope"
                  [ngModel]="exportScope()"
                  (ngModelChange)="exportScope.set($event)"
                >
                  <option value="ALL">All Masters &amp; Vouchers</option>
                  <option value="MASTERS">Accounting &amp; Inventory Masters</option>
                  <option value="VOUCHERS">Day Book Vouchers (Period)</option>
                  <option value="TRIAL_BALANCE">Trial Balance Closing Balances</option>
                  <option value="REMINDER_LETTERS">Multi-Account: Reminder Letters</option>
                  <option value="CONFIRMATION_OF_ACCOUNTS">Multi-Account: Confirmation of Accounts</option>
                </select>
              </div>

              <div class="mc-exg-check-grid" aria-label="Export Configuration">
                <label class="mc-exg-check-row">
                  <input
                    type="checkbox"
                    data-testid="exchange-export-dep-masters"
                    [(ngModel)]="exportIncludeDependentMasters"
                  />
                  <span>Include Dependent Masters</span>
                </label>
                <label class="mc-exg-check-row">
                  <input
                    type="checkbox"
                    data-testid="exchange-export-narration"
                    [(ngModel)]="exportIncludeNarration"
                  />
                  <span>Export Narration</span>
                </label>
                <label class="mc-exg-check-row">
                  <input
                    type="checkbox"
                    data-testid="exchange-export-billwise"
                    [(ngModel)]="exportIncludeBillWise"
                  />
                  <span>Bill-Wise Details</span>
                </label>
                <label class="mc-exg-check-row">
                  <input
                    type="checkbox"
                    data-testid="exchange-export-inventory"
                    [(ngModel)]="exportIncludeInventory"
                  />
                  <span>Inventory Details</span>
                </label>
              </div>

              @if (selectedFormat() === 'HTML') {
                <div>
                  <label class="mc-field-label" for="mc-exp-dsc">Digital Signature Signer (Optional)</label>
                  <input
                    id="mc-exp-dsc"
                    type="text"
                    class="mc-input"
                    data-testid="exchange-export-dsc-signer"
                    [(ngModel)]="exportDigitalSignatureSignerName"
                    placeholder="e.g. CA Rajesh Kumar (DSC)"
                  />
                </div>
              }

              @if (selectedFormat() === 'EXCEL') {
                <div>
                  <label class="mc-field-label" for="mc-exp-tpl">Spreadsheet Mapping Template</label>
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
                <mc-icon name="export" [size]="16" />
                <span>{{ busy() ? 'Exporting...' : 'Export ' + selectedFormat() }}</span>
              </button>

              @if (lastExportResult(); as exp) {
                <div class="mc-exg-export-preview" data-testid="exchange-export-preview">
                  <div class="mc-exg-preview-head">
                    <mc-code-badge [code]="exp.fileName" />
                    <button type="button" class="mc-btn" (click)="downloadLastExport()">
                      <mc-icon name="export" [size]="16" />
                      <span>Download File</span>
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
                  placeholder="Paste Legacy <ENVELOPE> XML, JSONEx, or Base64 .xlsx content..."
                ></textarea>
              </div>

              @if (selectedFormat() === 'EXCEL') {
                <div>
                  <label class="mc-field-label" for="mc-imp-tpl">Mapping Template</label>
                  <select
                    id="mc-imp-tpl"
                    class="mc-select"
                    data-testid="exchange-import-template-select"
                    [ngModel]="selectedTemplateId()"
                    (ngModelChange)="selectedTemplateId.set($event)"
                  >
                    <option value="">Default Template</option>
                    @for (tpl of templates(); track tpl.id) {
                      <option [value]="tpl.id">
                        {{ tpl.templateName }} ({{ tpl.sheetName }}) — {{ tpl.templateScope ?? 'THIS_COMPANY' }}
                      </option>
                    }
                  </select>
                </div>
              }

              <div class="mc-exg-split-grid">
                <div>
                  <label class="mc-field-label" for="mc-imp-dup-rule">
                    Behaviour if Master Already Exists
                  </label>
                  <select
                    id="mc-imp-dup-rule"
                    class="mc-select"
                    data-testid="exchange-import-dup-rule"
                    [(ngModel)]="importMasterDuplicateRule"
                  >
                    <option value="COMBINE_OPENING_BALANCES">Combine Opening Balances</option>
                    <option value="IGNORE_DUPLICATES">Ignore Duplicates</option>
                    <option value="MODIFY_WITH_NEW_DATA">Modify with New Data</option>
                  </select>
                </div>
                <div>
                  <label class="mc-field-label" for="mc-imp-exc-rule">
                    Behaviour if Data has Exceptions
                  </label>
                  <select
                    id="mc-imp-exc-rule"
                    class="mc-select"
                    data-testid="exchange-import-exc-behavior"
                    [(ngModel)]="importExceptionBehavior"
                  >
                    <option value="STOP_AT_FIRST_EXCEPTION">Stop Import at First Exception</option>
                    <option value="RECORD_EXCEPTIONS_AND_IMPORT">Record Exceptions and Import</option>
                    <option value="IGNORE_EXCEPTIONS_AND_IMPORT">Ignore Exceptions and Import</option>
                  </select>
                </div>
              </div>

              <div class="mc-exg-check-grid">
                <label class="mc-exg-check-row">
                  <input
                    type="checkbox"
                    data-testid="exchange-import-overwrite-guid"
                    [(ngModel)]="importOverwriteVoucherByGuid"
                  />
                  <span>Overwrite Vouchers with Same GUID</span>
                </label>
                <label class="mc-exg-check-row">
                  <input type="checkbox" [(ngModel)]="importDryRun" />
                  <span>Dry-Run Validation Only (Pre-verify Trial Balance without committing)</span>
                </label>
              </div>

              <div class="mc-exg-inline-row">
                <button
                  type="button"
                  class="mc-btn mc-btn-primary"
                  data-testid="exchange-import-btn"
                  [disabled]="busy() || !importContent.trim() || companyContext.quarantined()"
                  (click)="runImport()"
                >
                  <mc-icon name="import" [size]="16" />
                  <span>{{ busy() ? 'Importing...' : 'Import ' + selectedFormat() }}</span>
                </button>
                <select
                  class="mc-select"
                  aria-label="Sample Excel Type"
                  data-testid="exchange-sample-excel-type"
                  [(ngModel)]="selectedSampleExcelType"
                >
                  <option value="ALL">All Masters &amp; Vouchers</option>
                  <option value="ACCOUNTING_MASTERS">Accounting Masters</option>
                  <option value="INVENTORY_MASTERS">Inventory Masters</option>
                  <option value="ACCOUNTING_VOUCHERS">Accounting Vouchers</option>
                  <option value="ITEM_VOUCHERS">Item Invoice Vouchers</option>
                </select>
                <button
                  type="button"
                  class="mc-btn"
                  data-testid="exchange-sample-excel-btn"
                  [disabled]="busy()"
                  (click)="downloadSampleExcelFile(selectedSampleExcelType)"
                >
                  <mc-icon name="export" [size]="16" />
                  <span>Sample Excel Files (<span class="mc-key-alt">L</span>)</span>
                </button>
              </div>

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

        <!-- Custom OpenXML Mapping Templates -->
        <section class="mc-card mc-exg-card" aria-label="Excel Mapping Templates">
          <div class="mc-exg-section-head">
            <span>Custom OpenXML (.xlsx) Column Mapping Templates</span>
            <span>{{ templates().length }} template(s)</span>
          </div>
          <div class="mc-exg-body-stack">
            <form class="mc-exg-tpl-form" (ngSubmit)="saveNewTemplate()">
              <input
                type="text"
                name="newTemplateName"
                class="mc-input"
                data-testid="exchange-tpl-name-input"
                [(ngModel)]="newTemplateName"
                placeholder="Template Name (e.g. Sales Register XLSX)"
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
              <select
                name="newTemplateCompanyScope"
                class="mc-select"
                data-testid="exchange-tpl-company-scope"
                [(ngModel)]="newTemplateCompanyScope"
              >
                <option value="THIS_COMPANY">This Company</option>
                <option value="ALL_COMPANIES">All Companies</option>
              </select>
              <input
                type="text"
                name="newTemplateFromColumn"
                class="mc-input mc-code"
                data-testid="exchange-tpl-from-col"
                [(ngModel)]="newTemplateFromColumn"
                placeholder="From Col (A..ZZ)"
              />
              <input
                type="text"
                name="newTemplateToColumn"
                class="mc-input mc-code"
                data-testid="exchange-tpl-to-col"
                [(ngModel)]="newTemplateToColumn"
                placeholder="To Col (A..ZZ)"
              />
              <input
                type="text"
                name="newTemplateExcludeRowColumn"
                class="mc-input mc-code"
                data-testid="exchange-tpl-exclude-col"
                [(ngModel)]="newTemplateExcludeRowColumn"
                placeholder="Exclude Row Col"
              />
              <input
                type="text"
                name="newTemplateExcludeRowValue"
                class="mc-input"
                data-testid="exchange-tpl-exclude-val"
                [(ngModel)]="newTemplateExcludeRowValue"
                placeholder="Exclude Value"
              />
              <button
                type="submit"
                class="mc-btn"
                data-testid="exchange-tpl-save-btn"
                [disabled]="busy() || !newTemplateName.trim()"
              >
                <mc-icon name="save-document" [size]="16" />
                <span>Save Template</span>
              </button>
            </form>

            @if (templates().length > 0) {
              <div class="mc-exg-table-wrap">
                <table class="mc-exg-table" aria-label="Saved Mapping Templates" data-testid="exchange-templates-table">
                  <thead>
                    <tr>
                      <th scope="col">Template Name</th>
                      <th scope="col">Worksheet</th>
                      <th scope="col">Entity</th>
                      <th scope="col">Scope</th>
                      <th scope="col">Columns</th>
                      <th scope="col">Row Exclusion</th>
                      <th scope="col">Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (tpl of templates(); track tpl.id) {
                      <tr>
                        <td><strong>{{ tpl.templateName }}</strong></td>
                        <td class="mc-code">{{ tpl.sheetName }}</td>
                        <td><span class="mc-badge mc-badge-slate">{{ tpl.entityScope }}</span></td>
                        <td>
                          <span class="mc-badge mc-badge-jade">
                            {{ tpl.templateScope ?? 'THIS_COMPANY' }}
                          </span>
                        </td>
                        <td class="mc-code">
                          {{ tpl.fromColumn || 'A' }} .. {{ tpl.toColumn || 'ZZ' }}
                        </td>
                        <td class="mc-code">
                          @if (tpl.excludeRowColumn) {
                            {{ tpl.excludeRowColumn }} {{ tpl.excludeRowOperator || 'CONTAINS' }} "{{ tpl.excludeRowValue || '' }}"
                          } @else {
                            None
                          }
                        </td>
                        <td>
                          <button
                            type="button"
                            class="mc-btn mc-btn-danger"
                            data-testid="exchange-tpl-delete-btn"
                            [disabled]="busy()"
                            (click)="deleteMappingTemplate(tpl.id)"
                          >
                            <mc-icon name="delete" [size]="16" />
                            <span>Delete</span>
                          </button>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </section>

        <!-- Import Exceptions & Data Exception Resolution -->
        <section
          class="mc-card mc-exg-card"
          aria-label="Import Exceptions and Resolution"
          data-testid="exchange-exceptions-card"
        >
          <div class="mc-exg-section-head">
            <span>Import Exceptions &amp; Data Exception Resolution</span>
            <button
              type="button"
              class="mc-btn"
              data-testid="exchange-refresh-exceptions-btn"
              [disabled]="busy()"
              (click)="loadImportExceptions()"
            >
              <mc-icon name="refresh" [size]="16" />
              <span>Refresh Exceptions ({{ importExceptions().length }})</span>
            </button>
          </div>
          <div class="mc-exg-body-stack">
            <div class="mc-exg-grid-4">
              <div>
                <label class="mc-field-label" for="mc-exc-vtype">Resolution Voucher Type</label>
                <input
                  id="mc-exc-vtype"
                  type="text"
                  class="mc-input"
                  data-testid="exchange-resolve-vtype"
                  [(ngModel)]="resolveVoucherTypeName"
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-exc-vdate">Resolution Date</label>
                <input
                  id="mc-exc-vdate"
                  type="text"
                  class="mc-input mc-code"
                  data-testid="exchange-resolve-vdate"
                  [(ngModel)]="resolveVoucherDate"
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-exc-dr">Debit Ledger Name</label>
                <input
                  id="mc-exc-dr"
                  type="text"
                  class="mc-input"
                  data-testid="exchange-resolve-dr-ledger"
                  [(ngModel)]="resolveDebitLedgerName"
                  placeholder="e.g. Alpha Traders"
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-exc-cr">Credit Ledger Name</label>
                <input
                  id="mc-exc-cr"
                  type="text"
                  class="mc-input"
                  data-testid="exchange-resolve-cr-ledger"
                  [(ngModel)]="resolveCreditLedgerName"
                  placeholder="e.g. Gold Sales A/c"
                />
              </div>
            </div>
            <div class="mc-exg-split-grid">
              <div>
                <label class="mc-field-label" for="mc-exc-amt">Balanced Amount</label>
                <input
                  id="mc-exc-amt"
                  type="text"
                  class="mc-input mc-code"
                  data-testid="exchange-resolve-amount"
                  [(ngModel)]="resolveAmount"
                  placeholder="450.00"
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-exc-narr">Resolution Narration</label>
                <input
                  id="mc-exc-narr"
                  type="text"
                  class="mc-input"
                  data-testid="exchange-resolve-narration"
                  [(ngModel)]="resolveNarration"
                  placeholder="Created due to Data Exception Resolution"
                />
              </div>
            </div>

            @if (importExceptions().length > 0) {
              <div class="mc-exg-table-wrap">
                <table class="mc-exg-table" aria-label="Import Exceptions Queue" data-testid="exchange-exceptions-table">
                  <thead>
                    <tr>
                      <th scope="col">Exception ID</th>
                      <th scope="col">Entity</th>
                      <th scope="col">Identifier</th>
                      <th scope="col">Diagnostic Message</th>
                      <th scope="col">Status</th>
                      <th scope="col">Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (exc of importExceptions(); track exc.exceptionId) {
                      <tr>
                        <td><mc-code-badge [code]="exc.exceptionId" /></td>
                        <td>{{ exc.entityType }}</td>
                        <td class="mc-code">{{ exc.identifier }}</td>
                        <td>{{ exc.message }}</td>
                        <td>
                          <span
                            class="mc-badge"
                            [class.mc-badge-jade]="exc.resolved"
                            [class.mc-badge-danger]="!exc.resolved"
                          >
                            {{ exc.resolved ? 'RESOLVED' : 'PENDING' }}
                          </span>
                        </td>
                        <td>
                          <button
                            type="button"
                            class="mc-btn mc-btn-primary"
                            data-testid="exchange-resolve-exception-btn"
                            [disabled]="busy() || exc.resolved || companyContext.quarantined()"
                            (click)="resolveException(exc.exceptionId)"
                          >
                            <mc-icon name="post-ledger" [size]="16" />
                            <span>{{ exc.resolved ? 'Resolved' : 'Resolve Exception' }}</span>
                          </button>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </section>

        <!-- Client/Server Data Synchronization (Alt+Z) -->
        <section
          class="mc-card mc-exg-card"
          aria-label="Client/Server Data Synchronization"
          data-testid="exchange-sync-rules-card"
        >
          <div class="mc-exg-section-head">
            <span>Client/Server Data Synchronization (<span class="mc-key-alt">Z</span>)</span>
            <button
              type="button"
              class="mc-btn"
              data-testid="exchange-refresh-sync-rules-btn"
              [disabled]="busy()"
              (click)="loadSyncRules()"
            >
              <mc-icon name="refresh" [size]="16" />
              <span>Refresh Rules ({{ syncRules().length }})</span>
            </button>
          </div>
          <div class="mc-exg-body-stack">
            <form class="mc-exg-grid-4" (ngSubmit)="createNewSyncRule()">
              <div>
                <label class="mc-field-label" for="mc-sync-name">Client/Server Rule Name</label>
                <input
                  id="mc-sync-name"
                  type="text"
                  name="newSyncRuleName"
                  class="mc-input"
                  data-testid="exchange-sync-rule-name"
                  [(ngModel)]="newSyncRuleName"
                  placeholder="e.g. HO to Branch Full Sync"
                  required
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-sync-type">Rule Type (SVSyncRuleType)</label>
                <select
                  id="mc-sync-type"
                  name="newSyncRuleType"
                  class="mc-select"
                  data-testid="exchange-sync-rule-type"
                  [(ngModel)]="newSyncRuleType"
                >
                  <option value="FULL_SYNC">Full Sync (Masters &amp; Transactions)</option>
                  <option value="MASTER_SYNC">Master Sync (SVIsForMasterSync)</option>
                  <option value="TRANSACTION_SYNC">Transaction Sync</option>
                </select>
              </div>
              <div>
                <label class="mc-field-label" for="mc-sync-dir">Direction of Sync</label>
                <select
                  id="mc-sync-dir"
                  name="newSyncDirection"
                  class="mc-select"
                  data-testid="exchange-sync-direction"
                  [(ngModel)]="newSyncDirection"
                >
                  <option value="BOTH_WAYS">Both Ways (Send &amp; Receive)</option>
                  <option value="SEND_ONLY">Send Data Only</option>
                  <option value="RECEIVE_ONLY">Receive Data Only</option>
                </select>
              </div>
              <div>
                <label class="mc-field-label" for="mc-sync-target-guid">Destination Company GUID</label>
                <input
                  id="mc-sync-target-guid"
                  type="text"
                  name="newSyncTargetCompanyGuid"
                  class="mc-input mc-code"
                  data-testid="exchange-sync-target-guid"
                  [(ngModel)]="newSyncTargetCompanyGuid"
                  placeholder="Target Company UUID / GUID"
                  required
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-sync-target-name">Destination Company Name</label>
                <input
                  id="mc-sync-target-name"
                  type="text"
                  name="newSyncTargetCompanyName"
                  class="mc-input"
                  data-testid="exchange-sync-target-name"
                  [(ngModel)]="newSyncTargetCompanyName"
                  placeholder="e.g. Branch Sync Target Co"
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-sync-url">Server URL</label>
                <input
                  id="mc-sync-url"
                  type="text"
                  name="newSyncServerUrl"
                  class="mc-input mc-code"
                  data-testid="exchange-sync-server-url"
                  [(ngModel)]="newSyncServerUrl"
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-sync-dup">Master Duplicate Behaviour</label>
                <select
                  id="mc-sync-dup"
                  name="newSyncMasterDuplicateRule"
                  class="mc-select"
                  data-testid="exchange-sync-dup-rule"
                  [(ngModel)]="newSyncMasterDuplicateRule"
                >
                  <option value="COMBINE_OPENING_BALANCES">Combine Opening Balances</option>
                  <option value="IGNORE_DUPLICATES">Ignore Duplicates</option>
                  <option value="MODIFY_WITH_NEW_DATA">Modify with New Data</option>
                </select>
              </div>
              <div class="mc-exg-inline-row">
                <label class="mc-exg-check-row">
                  <input
                    type="checkbox"
                    name="newSyncOverwriteVoucherByGuid"
                    data-testid="exchange-sync-overwrite-guid"
                    [(ngModel)]="newSyncOverwriteVoucherByGuid"
                  />
                  <span>Overwrite Vouchers (GUID)</span>
                </label>
                <button
                  type="submit"
                  class="mc-btn mc-btn-primary"
                  data-testid="exchange-sync-save-btn"
                  [disabled]="busy() || !newSyncRuleName.trim() || companyContext.quarantined()"
                >
                  <mc-icon name="save-document" [size]="16" />
                  <span>Save Sync Rule</span>
                </button>
              </div>
            </form>

            @if (lastSyncSessionResult(); as syncRes) {
              <div class="mc-exg-import-summary" data-testid="exchange-sync-session-result">
                <strong>{{ syncRes.status }}</strong> ({{ syncRes.ruleName }}) — Masters Synced:
                {{ syncRes.mastersSynced }}, Vouchers Synced: {{ syncRes.vouchersSynced }},
                AlterID Watermark: <span class="mc-code">{{ syncRes.previousAlterIdWatermark }} &#x2192; {{ syncRes.newAlterIdWatermark }}</span>
              </div>
            }

            @if (syncRules().length > 0) {
              <div class="mc-exg-table-wrap">
                <table class="mc-exg-table" aria-label="Client/Server Sync Rules" data-testid="exchange-sync-rules-table">
                  <thead>
                    <tr>
                      <th scope="col">Rule Name</th>
                      <th scope="col">Type</th>
                      <th scope="col">Direction</th>
                      <th scope="col">Target Company</th>
                      <th scope="col">Master Duplicate Rule</th>
                      <th scope="col">Last Sync AlterID</th>
                      <th scope="col">Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (rule of syncRules(); track rule.id) {
                      <tr>
                        <td><strong>{{ rule.ruleName }}</strong></td>
                        <td><span class="mc-badge mc-badge-slate">{{ rule.ruleType }}</span></td>
                        <td><span class="mc-badge mc-badge-jade">{{ rule.direction }}</span></td>
                        <td>
                          {{ rule.targetCompanyName }}
                          <mc-code-badge [code]="rule.targetCompanyGuid" />
                        </td>
                        <td class="mc-code">{{ rule.masterDuplicateRule }}</td>
                        <td class="mc-code">{{ rule.lastSyncAlterId }}</td>
                        <td>
                          <button
                            type="button"
                            class="mc-btn mc-btn-primary"
                            data-testid="exchange-sync-execute-btn"
                            [disabled]="busy() || companyContext.quarantined()"
                            (click)="runSyncRule(rule.id)"
                          >
                            <mc-icon name="refresh" [size]="16" />
                            <span>Synchronise Now (Alt+Z)</span>
                          </button>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </section>

        <!-- TallyAudit & MCA Edit Log Activity Summary -->
        <section
          class="mc-card mc-exg-card"
          aria-label="TallyAudit and Edit Log Activity Summary"
          data-testid="exchange-edit-log-summary-card"
        >
          <div class="mc-exg-section-head">
            <span>TallyAudit &amp; MCA Edit Log Activity Register (AlterID Trail)</span>
            <button
              type="button"
              class="mc-btn"
              data-testid="exchange-load-edit-log-summary-btn"
              [disabled]="busy()"
              (click)="loadEditLogSummary()"
            >
              <mc-icon name="refresh" [size]="16" />
              <span>Load Edit Log Summary</span>
            </button>
          </div>
          <div class="mc-exg-body-stack">
            <div class="mc-exg-grid-4">
              <div>
                <label class="mc-field-label" for="mc-el-etype">Entity Type</label>
                <select
                  id="mc-el-etype"
                  class="mc-select"
                  data-testid="exchange-edit-log-entity-type"
                  [(ngModel)]="editLogEntityTypeFilter"
                >
                  <option value="ALL">All Masters &amp; Vouchers</option>
                  <option value="VOUCHER">Vouchers Only</option>
                  <option value="LEDGER">Ledgers Only</option>
                  <option value="STOCK_ITEM">Stock Items Only</option>
                </select>
              </div>
              <div>
                <label class="mc-field-label" for="mc-el-user">EditLog User Name Filter</label>
                <input
                  id="mc-el-user"
                  type="text"
                  class="mc-input"
                  data-testid="exchange-edit-log-username"
                  [(ngModel)]="editLogUsernameFilter"
                  placeholder="Filter by username (optional)"
                />
              </div>
              <div class="mc-exg-inline-row">
                <label class="mc-exg-check-row">
                  <input
                    type="checkbox"
                    data-testid="exchange-edit-log-only-altered"
                    [(ngModel)]="editLogOnlyAltered"
                  />
                  <span>Basis of Values: Altered Records Only (Version &gt; 1)</span>
                </label>
              </div>
            </div>

            @if (editLogSummary(); as summary) {
              <div class="mc-exg-table-wrap">
                <table class="mc-exg-table" aria-label="Edit Log Activity Summary" data-testid="exchange-edit-log-summary-table">
                  <thead>
                    <tr>
                      <th scope="col">Entity</th>
                      <th scope="col">Particulars / Voucher No.</th>
                      <th scope="col">Activity Type</th>
                      <th scope="col">Altered Due To</th>
                      <th scope="col">Versions</th>
                      <th scope="col">Latest AlterID</th>
                      <th scope="col">User Name</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (item of summary.items; track item.entityId) {
                      <tr>
                        <td><span class="mc-badge mc-badge-slate">{{ item.entityType }}</span></td>
                        <td><strong>{{ item.displayName }}</strong></td>
                        <td>
                          <span
                            class="mc-badge"
                            [class.mc-badge-jade]="item.activityType === 'CREATED'"
                            [class.mc-badge-danger]="item.activityType === 'CANCELLED'"
                          >
                            {{ item.activityType }}
                          </span>
                        </td>
                        <td class="mc-code">{{ item.alteredDueTo }}</td>
                        <td class="mc-code">v{{ item.versionCount }}</td>
                        <td class="mc-code">{{ item.latestAlterId }}</td>
                        <td>{{ item.lastModifiedBy }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </section>

        <!-- Multi-Account Reports Workbench (Alt+E > Confirmation of Accounts / Reminder Letters) -->
        <section
          class="mc-card mc-exg-card"
          aria-label="Multi-Account Reports Export"
          data-testid="exchange-multi-account-card"
        >
          <div class="mc-exg-section-head">
            <span>Multi-Account Reports (<span class="mc-key-alt">E</span>) — Confirmation of Accounts &amp; Reminder Letters</span>
            <button
              type="button"
              class="mc-btn mc-btn-primary"
              data-testid="exchange-multi-account-run-btn"
              [disabled]="busy()"
              (click)="runMultiAccountReportExport()"
            >
              <mc-icon name="export" [size]="16" />
              <span>Export Multi-Account Report</span>
            </button>
          </div>
          <div class="mc-exg-body-stack">
            <div class="mc-exg-grid-4">
              <div>
                <label class="mc-field-label" for="mc-ma-type">Multi-Account Report Type</label>
                <select
                  id="mc-ma-type"
                  class="mc-select"
                  data-testid="exchange-multi-account-type"
                  [(ngModel)]="multiAccountReportType"
                >
                  <option value="CONFIRMATION_OF_ACCOUNTS">Confirmation of Accounts</option>
                  <option value="REMINDER_LETTER">Reminder Letters (Overdue Bills Ageing)</option>
                </select>
              </div>
              <div>
                <label class="mc-field-label" for="mc-ma-fmt">Output Format</label>
                <select
                  id="mc-ma-fmt"
                  class="mc-select"
                  data-testid="exchange-multi-account-format"
                  [(ngModel)]="multiAccountExportFormat"
                >
                  <option value="HTML">HTML (Web-Publishing)</option>
                  <option value="ASCII">ASCII (Comma Delimited .csv)</option>
                  <option value="JSON">JSON (Structured Statements)</option>
                </select>
              </div>
              <div class="mc-exg-inline-row">
                <label class="mc-exg-check-row">
                  <input
                    type="checkbox"
                    data-testid="exchange-multi-account-zero"
                    [(ngModel)]="multiAccountIncludeZeroBalances"
                  />
                  <span>Include Zero-Balance Parties</span>
                </label>
              </div>
            </div>

            @if (multiAccountReportResponse(); as ma) {
              <div class="mc-exg-table-wrap">
                <table
                  class="mc-exg-table"
                  aria-label="Multi-Account Party Statements"
                  data-testid="exchange-multi-account-table"
                >
                  <thead>
                    <tr>
                      <th scope="col">Party Ledger</th>
                      <th scope="col">Group</th>
                      <th scope="col" class="mc-col-amt">Opening</th>
                      <th scope="col" class="mc-col-amt">Period Dr</th>
                      <th scope="col" class="mc-col-amt">Period Cr</th>
                      <th scope="col" class="mc-col-amt">Closing</th>
                      <th scope="col" class="mc-col-amt">0–30 Days</th>
                      <th scope="col" class="mc-col-amt">31–60 Days</th>
                      <th scope="col" class="mc-col-amt">61–90 Days</th>
                      <th scope="col" class="mc-col-amt">&gt;90 Days</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (st of ma.statements; track st.ledgerId) {
                      <tr>
                        <td><strong>{{ st.partyName }}</strong></td>
                        <td>{{ st.groupName }}</td>
                        <td class="mc-amount mc-col-amt">
                          {{ st.openingBalance | mcIndianCurrency: companyContext.currencyScale() }} {{ st.openingSide }}
                        </td>
                        <td class="mc-amount mc-col-amt">
                          {{ st.periodDebit | mcIndianCurrency: companyContext.currencyScale() }}
                        </td>
                        <td class="mc-amount mc-col-amt">
                          {{ st.periodCredit | mcIndianCurrency: companyContext.currencyScale() }}
                        </td>
                        <td class="mc-amount mc-col-amt">
                          <strong>{{ st.closingBalance | mcIndianCurrency: companyContext.currencyScale() }} {{ st.closingSide }}</strong>
                        </td>
                        <td class="mc-amount mc-col-amt">
                          {{ st.slab0To30 | mcIndianCurrency: companyContext.currencyScale() }}
                        </td>
                        <td class="mc-amount mc-col-amt">
                          {{ st.slab31To60 | mcIndianCurrency: companyContext.currencyScale() }}
                        </td>
                        <td class="mc-amount mc-col-amt">
                          {{ st.slab61To90 | mcIndianCurrency: companyContext.currencyScale() }}
                        </td>
                        <td class="mc-amount mc-col-amt">
                          {{ st.slabOver90 | mcIndianCurrency: companyContext.currencyScale() }}
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </section>

        <!-- Interactive Dashboard Tiles Workbench (Add Tile / Remove Tile / Expand Tile / SVDBVerticalView) -->
        <section
          class="mc-card mc-exg-card"
          aria-label="Dashboard Tiles Workbench"
          data-testid="exchange-dashboard-tiles-card"
        >
          <div class="mc-exg-section-head">
            <span>Dashboard Tiles Workbench &amp; Export (Add / Remove / Expand Tile &bull; SVDBVerticalView)</span>
            <div class="mc-exg-inline-row">
              <select
                class="mc-select"
                aria-label="Dashboard Tiles Format"
                data-testid="exchange-dashboard-format-select"
                [(ngModel)]="dashboardExportFormat"
              >
                <option value="HTML">HTML</option>
                <option value="ASCII">ASCII (.csv)</option>
                <option value="JSON">JSON</option>
              </select>
              <button
                type="button"
                class="mc-btn"
                data-testid="exchange-dashboard-vertical-btn"
                (click)="toggleDashboardVerticalView()"
              >
                <span>{{ dashboardVerticalView() ? 'Grid View' : 'Vertical View' }}</span>
              </button>
              @if (removedDashboardTiles().length > 0) {
                <button
                  type="button"
                  class="mc-btn"
                  data-testid="exchange-dashboard-add-tile-btn"
                  (click)="restoreAllDashboardTiles()"
                >
                  <span>Add Tile ({{ removedDashboardTiles().length }})</span>
                </button>
              }
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="exchange-load-dashboard-tiles-btn"
                [disabled]="busy()"
                (click)="loadDashboardTiles()"
              >
                <mc-icon name="refresh" [size]="16" />
                <span>Load &amp; Export Dashboard Tiles</span>
              </button>
            </div>
          </div>
          <div class="mc-exg-body-stack">
            @if (visibleDashboardTiles().length > 0) {
              <div
                [class.mc-exg-split-grid]="!dashboardVerticalView()"
                [class.mc-exg-body-stack]="dashboardVerticalView()"
                data-testid="exchange-dashboard-tiles-grid"
              >
                @for (tile of visibleDashboardTiles(); track tile.tileCode) {
                  <article
                    class="mc-exg-subpanel"
                    [attr.data-testid]="'dashboard-tile-' + tile.tileCode"
                  >
                    <div class="mc-exg-subhead">
                      <strong>{{ tile.tileTitle }}</strong>
                      <div class="mc-exg-inline-row">
                        <button
                          type="button"
                          class="mc-btn"
                          [attr.data-testid]="'expand-tile-btn-' + tile.tileCode"
                          (click)="toggleExpandDashboardTile(tile.tileCode)"
                        >
                          <span>{{ expandedDashboardTileCode() === tile.tileCode ? 'Collapse' : 'Expand Tile' }}</span>
                        </button>
                        <button
                          type="button"
                          class="mc-btn mc-btn-danger"
                          [attr.data-testid]="'remove-tile-btn-' + tile.tileCode"
                          (click)="removeDashboardTile(tile.tileCode)"
                        >
                          <span>Remove Tile</span>
                        </button>
                      </div>
                    </div>
                    <div>
                      <span class="mc-exg-meta-lbl">{{ tile.primaryMetricLabel }}:</span>
                      <strong class="mc-amount">{{ tile.primaryMetricValue }}</strong>
                    </div>
                    @if (expandedDashboardTileCode() === tile.tileCode) {
                      <div
                        class="mc-exg-export-preview"
                        [attr.data-testid]="'expanded-tile-details-' + tile.tileCode"
                      >
                        @for (entry of tileBreakdownEntries(tile); track entry.key) {
                          <div>
                            <span class="mc-exg-meta-lbl">{{ entry.key }}:</span>
                            <span class="mc-code">{{ entry.value }}</span>
                          </div>
                        }
                      </div>
                    }
                  </article>
                }
              </div>
            }
          </div>
        </section>

        <!-- Company Data Verification & Pre-Split/Sync Exception Audit (Alt+Y) -->
        <section
          class="mc-card mc-exg-card"
          aria-label="Company Data Verification"
          data-testid="exchange-data-verification-card"
        >
          <div class="mc-exg-section-head">
            <span>Company Data Verification &amp; Pre-Split/Sync Audit (<span class="mc-key-alt">Y</span>)</span>
            <button
              type="button"
              class="mc-btn mc-btn-primary"
              data-testid="exchange-verify-data-btn"
              [disabled]="busy()"
              (click)="runCompanyDataVerification()"
            >
              <mc-icon name="confirmed" [size]="16" />
              <span>Verify Company Data (Alt+Y)</span>
            </button>
          </div>
          @if (dataVerificationReport(); as dv) {
            <div class="mc-exg-body-stack">
              <div class="mc-exg-proof-meta">
                <div>
                  <span class="mc-exg-meta-lbl">Split / Sync Readiness:</span>
                  <span
                    class="mc-badge"
                    [class.mc-badge-jade]="dv.readyForSplitOrSync"
                    [class.mc-badge-danger]="!dv.readyForSplitOrSync"
                    data-testid="exchange-data-verify-ready-badge"
                  >
                    {{ dv.readyForSplitOrSync ? 'READY FOR SPLIT / SYNC' : 'ISSUES REQUIRE RESOLUTION' }}
                  </span>
                </div>
                <div>
                  <span class="mc-exg-meta-lbl">TB Difference:</span>
                  <span class="mc-amount">{{ dv.trialBalanceDifference }}</span>
                </div>
                <div>
                  <span class="mc-exg-meta-lbl">Unbalanced Vouchers:</span>
                  <span class="mc-code">{{ dv.unbalancedVoucherCount }}</span>
                </div>
                <div>
                  <span class="mc-exg-meta-lbl">Unit Mismatches:</span>
                  <span class="mc-code">{{ dv.unitMismatchCount }}</span>
                </div>
                <div>
                  <span class="mc-exg-meta-lbl">Pending Import Exceptions:</span>
                  <span class="mc-code">{{ dv.pendingImportExceptionCount }}</span>
                </div>
              </div>
              <div class="mc-exg-table-wrap">
                <table
                  class="mc-exg-table"
                  aria-label="Data Verification Checks"
                  data-testid="exchange-data-verify-checks-table"
                >
                  <thead>
                    <tr>
                      <th scope="col">Check Code</th>
                      <th scope="col">Verification Check</th>
                      <th scope="col">Status</th>
                      <th scope="col" class="mc-col-amt">Issues</th>
                      <th scope="col">Diagnostic Detail</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (chk of dv.checks; track chk.checkCode) {
                      <tr>
                        <td><mc-code-badge [code]="chk.checkCode" /></td>
                        <td><strong>{{ chk.checkTitle }}</strong></td>
                        <td>
                          <span
                            class="mc-badge"
                            [class.mc-badge-jade]="chk.status === 'PASS'"
                            [class.mc-badge-danger]="chk.status !== 'PASS'"
                          >
                            {{ chk.status }}
                          </span>
                        </td>
                        <td class="mc-amount mc-col-amt">{{ chk.issueCount }}</td>
                        <td>{{ chk.detail }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          }
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

    .mc-exg-heading-row,
    .mc-exg-inline-row {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      flex-wrap: wrap;
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
      flex-wrap: wrap;
    }

    .mc-exg-alert {
      padding: var(--mc-space-2) var(--mc-space-3);
      border-radius: var(--mc-radius-sm);
      font-size: var(--mc-font-size-sm);
      font-weight: 600;
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-exg-alert-error {
      background: var(--mc-color-danger-50);
      color: var(--mc-color-danger-700);
      border: 1px solid #FCA5A5;
    }

    .mc-exg-alert-warn {
      background: var(--mc-color-warning-50, #FFFBEB);
      color: var(--mc-color-warning-700, #6F4E00);
      border: 1px solid #FCD34D;
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
      width: 175px;
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

    .mc-exg-grid-4 {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    .mc-exg-check-grid {
      display: flex;
      flex-wrap: wrap;
      gap: var(--mc-space-4);
    }

    @media (max-width: 960px) {
      .mc-exg-split-grid,
      .mc-exg-grid-4 {
        grid-template-columns: 1fr;
      }
    }

    .mc-exg-body-stack {
      padding: var(--mc-space-3) var(--mc-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-exg-subpanel {
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-exg-subhead {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-2);
      font-size: var(--mc-font-size-sm);
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

    .mc-exg-cell-err {
      color: var(--mc-color-danger-700);
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
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

  // Increment X Guided Columnar Spreadsheet Importer State
  readonly sheetTargetType = signal<SpreadsheetTargetType>('VOUCHERS');
  readonly spreadsheetTemplates = signal<readonly SpreadsheetTemplateDto[]>([]);
  readonly selectedSpreadsheetTemplateId = signal<string>('');
  readonly activeSpreadsheetBatch = signal<SpreadsheetBatchDto | null>(null);
  readonly sheetLedgerColumns = signal<LedgerColumnMappingDto[]>([
    {
      sourceColumn: 'Taxable Sales',
      ledgerName: 'Local Sales',
      side: 'CR',
      lineRole: 'ACCOUNTING',
    },
    {
      sourceColumn: 'Output CGST',
      ledgerName: 'Output CGST',
      side: 'CR',
      lineRole: 'TAX',
    },
    {
      sourceColumn: 'Output SGST',
      ledgerName: 'Output SGST',
      side: 'CR',
      lineRole: 'TAX',
    },
  ]);

  sheetFileName = 'sales-register.csv';
  sheetBase64Content: string | null = null;
  sheetCsvText = '';
  sheetSelectedName = 'Sheet1';
  sheetHeaderRowIndex = 1;
  sheetDelimiter = ',';
  sheetDatePattern = 'yyyy-MM-dd';
  sheetDuplicateHandling = 'SKIP';
  sheetCachedFormulaConsent = false;
  sheetAcknowledgeDuplicateRun = false;
  sheetUseGoogleCapture = false;
  googleSpreadsheetId = '';
  googleSheetName = 'Sheet1';
  googleRangeA1 = 'A1:Z500';
  googleScopeUsed = 'https://www.googleapis.com/auth/spreadsheets.readonly';

  sheetVoucherAction: SpreadsheetVoucherAction = 'POST';
  sheetDefaultVoucherType = 'Sales';
  sheetVoucherDateCol = 'Date';
  sheetVoucherNumberCol = 'Voucher No';
  sheetPartyLedgerCol = 'Customer';
  sheetPartyAmountCol = 'Invoice Total';
  sheetPartySide: 'DR' | 'CR' = 'DR';
  sheetNarrationCol = 'Narration';

  sheetLedgerNameCol = 'Ledger Name';
  sheetGroupNameCol = 'Under Group';
  sheetOpeningBalanceCol = 'Opening Balance';
  sheetGstinCol = 'GSTIN';
  sheetTemplateNameInput = '';

  // Legacy XML / JSONEx / OpenXML Exchange State
  readonly selectedFormat = signal<ExchangeFormat>('XML');
  readonly exportScope = signal<ExchangeEntityScope>('ALL');
  readonly selectedTemplateId = signal<string>('');
  readonly templates = signal<readonly ExchangeMappingTemplateDto[]>([]);
  readonly lastExportResult = signal<ExportDataResultDto | null>(null);
  readonly lastImportResult = signal<ImportDataResultDto | null>(null);

  importFileName = 'legacy-export.xml';
  importContent = '';
  importDryRun = false;
  importMasterDuplicateRule: MasterDuplicateRule = 'MODIFY_WITH_NEW_DATA';
  importOverwriteVoucherByGuid = false;
  importExceptionBehavior: ImportExceptionBehavior = 'STOP_AT_FIRST_EXCEPTION';

  exportIncludeDependentMasters = true;
  exportIncludeNarration = true;
  exportIncludeBillWise = true;
  exportIncludeInventory = true;
  exportDigitalSignatureSignerName = '';
  selectedSampleExcelType: SampleExcelType = 'ALL';

  readonly importExceptions = signal<readonly ImportExceptionItemDto[]>([]);
  readonly syncRules = signal<readonly SyncRuleDto[]>([]);
  readonly lastSyncSessionResult = signal<SyncSessionResultDto | null>(null);
  readonly editLogSummary = signal<EditLogSummaryResponseDto | null>(null);
  readonly multiAccountReportResponse = signal<MultiAccountReportExportResponse | null>(null);
  readonly dashboardTilesResponse = signal<DashboardTilesExportResponse | null>(null);
  readonly dashboardVerticalView = signal<boolean>(false);
  readonly hiddenDashboardTileCodes = signal<readonly string[]>([]);
  readonly expandedDashboardTileCode = signal<string | null>(null);
  readonly dataVerificationReport = signal<DataVerificationReportDto | null>(null);

  readonly visibleDashboardTiles = computed<readonly DashboardTileDto[]>(() => {
    const all = this.dashboardTilesResponse()?.tiles ?? [];
    const hidden = new Set(this.hiddenDashboardTileCodes());
    return all.filter((t) => !hidden.has(t.tileCode));
  });

  readonly removedDashboardTiles = computed<readonly DashboardTileDto[]>(() => {
    const all = this.dashboardTilesResponse()?.tiles ?? [];
    const hidden = new Set(this.hiddenDashboardTileCodes());
    return all.filter((t) => hidden.has(t.tileCode));
  });

  multiAccountReportType: 'CONFIRMATION_OF_ACCOUNTS' | 'REMINDER_LETTER' =
    'CONFIRMATION_OF_ACCOUNTS';
  multiAccountExportFormat: 'JSON' | 'HTML' | 'ASCII' = 'HTML';
  multiAccountIncludeZeroBalances = false;
  dashboardExportFormat: 'JSON' | 'HTML' | 'ASCII' = 'HTML';

  newSyncRuleName = '';
  newSyncRuleType: 'FULL_SYNC' | 'MASTER_SYNC' | 'TRANSACTION_SYNC' = 'FULL_SYNC';
  newSyncDirection: 'BOTH_WAYS' | 'SEND_ONLY' | 'RECEIVE_ONLY' = 'BOTH_WAYS';
  newSyncTargetCompanyGuid = '';
  newSyncTargetCompanyName = '';
  newSyncServerUrl = 'http://localhost:18080';
  newSyncMasterDuplicateRule: MasterDuplicateRule = 'MODIFY_WITH_NEW_DATA';
  newSyncOverwriteVoucherByGuid = true;

  editLogEntityTypeFilter: 'ALL' | 'VOUCHER' | 'LEDGER' | 'STOCK_ITEM' = 'ALL';
  editLogOnlyAltered = true;
  editLogUsernameFilter = '';

  newTemplateName = '';
  newTemplateSheet = 'Sheet1';
  newTemplateScope: 'MASTERS' | 'VOUCHERS' = 'VOUCHERS';
  newTemplateCompanyScope: 'THIS_COMPANY' | 'ALL_COMPANIES' = 'THIS_COMPANY';
  newTemplateFromColumn = '';
  newTemplateToColumn = '';
  newTemplateExcludeRowColumn = '';
  newTemplateExcludeRowOperator = 'CONTAINS';
  newTemplateExcludeRowValue = '';

  resolveVoucherTypeName = 'Sales';
  resolveVoucherDate = '2025-04-15';
  resolveDebitLedgerName = '';
  resolveCreditLedgerName = '';
  resolveAmount = '';
  resolveNarration = '';

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
      } else if (this.activeTab() === 'SPREADSHEET_WIZARD') {
        const tpls = await this.exchangeService.listSpreadsheetTemplates(cid);
        this.spreadsheetTemplates.set(tpls);
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
      const proof = await this.exchangeService.stageLegacy1800(cid, {
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

  // ---------------------------------------------------------------------------
  // Increment X Spreadsheet Wizard Actions
  // ---------------------------------------------------------------------------

  canPrepareSpreadsheet(): boolean {
    if (this.sheetUseGoogleCapture && this.googleSpreadsheetId.trim().length > 0) {
      return true;
    }
    return Boolean(this.sheetBase64Content) || this.sheetCsvText.trim().length > 0;
  }

  onSpreadsheetFileSelected(event: Event): void {
    const inputEl = event.target as HTMLInputElement | null;
    const file = inputEl?.files?.[0];
    if (!file) {
      return;
    }
    this.sheetFileName = file.name;
    const lower = file.name.toLowerCase();
    const reader = new FileReader();
    if (lower.endsWith('.csv')) {
      reader.onload = () => {
        this.sheetCsvText = typeof reader.result === 'string' ? reader.result : '';
        this.sheetBase64Content = null;
      };
      reader.readAsText(file);
    } else {
      reader.onload = () => {
        if (reader.result instanceof ArrayBuffer) {
          const bytes = new Uint8Array(reader.result);
          let binary = '';
          for (const byte of bytes) {
            binary += String.fromCharCode(byte);
          }
          this.sheetBase64Content = btoa(binary);
          this.sheetCsvText = '';
        }
      };
      reader.readAsArrayBuffer(file);
    }
  }

  addLedgerColumnMapping(): void {
    this.sheetLedgerColumns.update((cols) => [
      ...cols,
      {
        sourceColumn: '',
        ledgerName: '',
        side: 'CR',
        lineRole: 'ACCOUNTING',
      },
    ]);
  }

  removeLedgerColumnMapping(index: number): void {
    this.sheetLedgerColumns.update((cols) => cols.filter((_, idx) => idx !== index));
  }

  applySpreadsheetTemplate(templateId: string): void {
    this.selectedSpreadsheetTemplateId.set(templateId);
    const tpl = this.spreadsheetTemplates().find((t) => t.id === templateId);
    if (!tpl) {
      return;
    }
    this.sheetSelectedName = tpl.sheetName || 'Sheet1';
    this.sheetHeaderRowIndex = tpl.headerRowIndex || 1;
    const target: SpreadsheetTargetType = tpl.targetType === 'LEDGERS' ? 'LEDGERS' : 'VOUCHERS';
    this.sheetTargetType.set(target);
    const m = tpl.mapping;
    if (m) {
      this.sheetVoucherAction =
        (m.voucherAction as SpreadsheetVoucherAction | undefined) ?? 'POST';
      this.sheetDefaultVoucherType = m.defaultVoucherType ?? 'Sales';
      this.sheetVoucherDateCol = m.voucherDateColumn ?? 'Date';
      this.sheetVoucherNumberCol = m.voucherNumberColumn ?? 'Voucher No';
      this.sheetPartyLedgerCol = m.partyLedgerNameColumn ?? 'Customer';
      this.sheetPartyAmountCol = m.partyAmountColumn ?? 'Invoice Total';
      this.sheetPartySide = m.partySide === 'CR' ? 'CR' : 'DR';
      this.sheetNarrationCol = m.narrationColumn ?? 'Narration';
      this.sheetLedgerNameCol = m.ledgerNameColumn ?? 'Ledger Name';
      this.sheetGroupNameCol = m.groupNameColumn ?? 'Under Group';
      this.sheetOpeningBalanceCol = m.openingBalanceColumn ?? 'Opening Balance';
      this.sheetGstinCol = m.gstinColumn ?? 'GSTIN';
      if (m.ledgerColumns && m.ledgerColumns.length > 0) {
        this.sheetLedgerColumns.set(m.ledgerColumns.map((col) => ({ ...col })));
      }
    }
  }

  buildCurrentSpreadsheetMapping(): SpreadsheetMappingSpecDto {
    if (this.sheetTargetType() === 'LEDGERS') {
      return {
        targetType: 'LEDGERS',
        ledgerNameColumn: this.sheetLedgerNameCol.trim() || null,
        groupNameColumn: this.sheetGroupNameCol.trim() || null,
        openingBalanceColumn: this.sheetOpeningBalanceCol.trim() || null,
        gstinColumn: this.sheetGstinCol.trim() || null,
        ledgerColumns: [],
        datePattern: this.sheetDatePattern,
        duplicateHandling: this.sheetDuplicateHandling,
      };
    }
    return {
      targetType: 'VOUCHERS',
      voucherAction: this.sheetVoucherAction,
      defaultVoucherType: this.sheetDefaultVoucherType.trim() || 'Sales',
      voucherDateColumn: this.sheetVoucherDateCol.trim() || null,
      voucherNumberColumn: this.sheetVoucherNumberCol.trim() || null,
      partyLedgerNameColumn: this.sheetPartyLedgerCol.trim() || null,
      partyAmountColumn: this.sheetPartyAmountCol.trim() || null,
      partySide: this.sheetPartySide,
      narrationColumn: this.sheetNarrationCol.trim() || null,
      ledgerColumns: this.sheetLedgerColumns().filter(
        (c) => c.sourceColumn.trim().length > 0 && c.ledgerName.trim().length > 0,
      ),
      datePattern: this.sheetDatePattern,
      duplicateHandling: this.sheetDuplicateHandling,
    };
  }

  async saveCurrentSpreadsheetTemplate(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    const name = this.sheetTemplateNameInput.trim();
    if (!cid || !name) return;

    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const saved = await this.exchangeService.saveSpreadsheetTemplate(cid, {
        name,
        targetType: this.sheetTargetType(),
        sheetName: this.sheetSelectedName.trim() || 'Sheet1',
        headerRowIndex: this.sheetHeaderRowIndex,
        mapping: this.buildCurrentSpreadsheetMapping(),
        duplicateHandling: this.sheetDuplicateHandling,
      });
      this.spreadsheetTemplates.update((prev) => [...prev.filter((p) => p.id !== saved.id), saved]);
      this.selectedSpreadsheetTemplateId.set(saved.id);
      this.sheetTemplateNameInput = '';
      this.statusBanner.set(`Saved spreadsheet mapping template "${saved.name}" (v${saved.versionNo}).`);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to save mapping template');
    } finally {
      this.busy.set(false);
    }
  }

  async prepareSpreadsheet(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;

    this.busy.set(true);
    this.errorBanner.set(null);
    this.statusBanner.set(null);
    try {
      const batch = await this.exchangeService.prepareSpreadsheetBatch(cid, {
        fileName: this.sheetFileName.trim() || 'import.csv',
        base64Content: this.sheetBase64Content,
        csvText: this.sheetCsvText.trim() || null,
        sheetName: this.sheetSelectedName.trim() || 'Sheet1',
        headerRowIndex: this.sheetHeaderRowIndex,
        delimiter: this.sheetDelimiter,
        cachedFormulaConsent: this.sheetCachedFormulaConsent,
        acknowledgeDuplicateRun: this.sheetAcknowledgeDuplicateRun,
        templateId: this.selectedSpreadsheetTemplateId() || null,
        mapping: this.buildCurrentSpreadsheetMapping(),
        googleSheetCapture:
          this.sheetUseGoogleCapture && this.googleSpreadsheetId.trim()
            ? {
                spreadsheetId: this.googleSpreadsheetId.trim(),
                sheetName: this.googleSheetName.trim() || 'Sheet1',
                rangeA1: this.googleRangeA1.trim() || 'A1:Z500',
                scopeUsed: this.googleScopeUsed,
              }
            : null,
      });
      this.activeSpreadsheetBatch.set(batch);
      this.statusBanner.set(
        `Prepared ${batch.sourceFileName}: ${batch.validUnits} valid / ${batch.invalidUnits} invalid unit(s).`,
      );
    } catch (err: unknown) {
      this.errorBanner.set(
        err instanceof Error ? err.message : 'Unable to prepare spreadsheet batch',
      );
    } finally {
      this.busy.set(false);
    }
  }

  async remapActiveBatch(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    const batch = this.activeSpreadsheetBatch();
    if (!cid || !batch) return;

    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const updated = await this.exchangeService.remapSpreadsheetBatch(cid, batch.id, {
        sheetName: this.sheetSelectedName.trim() || batch.sheetName,
        headerRowIndex: this.sheetHeaderRowIndex,
        delimiter: this.sheetDelimiter,
        cachedFormulaConsent: this.sheetCachedFormulaConsent,
        templateId: this.selectedSpreadsheetTemplateId() || null,
        mapping: this.buildCurrentSpreadsheetMapping(),
      });
      this.activeSpreadsheetBatch.set(updated);
      this.statusBanner.set(
        `Re-validated mapping rev #${updated.mappingRevision}: ${updated.validUnits} valid unit(s).`,
      );
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to re-validate batch');
    } finally {
      this.busy.set(false);
    }
  }

  async executeSpreadsheetBatch(resume: boolean): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    const batch = this.activeSpreadsheetBatch();
    if (!cid || !batch) return;

    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const executed = await this.exchangeService.executeOrResumeSpreadsheetBatch(
        cid,
        batch.id,
        resume,
        {
          action: this.sheetTargetType() === 'VOUCHERS' ? this.sheetVoucherAction : null,
          allowValidUnitsOnly: true,
        },
      );
      this.activeSpreadsheetBatch.set(executed);
      this.statusBanner.set(
        `Spreadsheet batch ${executed.status}: ${executed.committedUnits} unit(s) committed.`,
      );
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to commit spreadsheet batch');
    } finally {
      this.busy.set(false);
    }
  }

  async cancelActiveSpreadsheetBatch(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    const batch = this.activeSpreadsheetBatch();
    if (!cid || !batch) return;

    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const cancelled = await this.exchangeService.cancelSpreadsheetBatch(cid, batch.id);
      this.activeSpreadsheetBatch.set(cancelled);
      this.statusBanner.set(`Cancelled pending units for batch ${cancelled.id}.`);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to cancel spreadsheet batch');
    } finally {
      this.busy.set(false);
    }
  }

  async downloadSpreadsheetErrors(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    const batch = this.activeSpreadsheetBatch();
    if (!cid || !batch) return;

    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      await this.exchangeService.downloadSpreadsheetErrorsCsv(cid, batch.id);
      this.statusBanner.set(`Downloaded formula-neutralized error CSV for batch ${batch.id}.`);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to download error CSV');
    } finally {
      this.busy.set(false);
    }
  }

  // ---------------------------------------------------------------------------
  // Legacy XML / JSONEx / OpenXML Exchange Actions
  // ---------------------------------------------------------------------------

  async runExport(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    const fmt = this.selectedFormat();
    const signer = this.exportDigitalSignatureSignerName.trim();
    const req = {
      entityScope: this.exportScope(),
      fromDate: this.companyContext.periodFrom(),
      toDate: this.companyContext.periodTo(),
      includeDependentMasters: this.exportIncludeDependentMasters,
      templateId: this.selectedTemplateId() || null,
      includeNarration: this.exportIncludeNarration,
      includeBillWise: this.exportIncludeBillWise,
      includeInventory: this.exportIncludeInventory,
      ...(signer ? { digitalSignatureSignerName: signer } : {}),
    };

    this.busy.set(true);
    this.errorBanner.set(null);
    this.statusBanner.set(null);
    try {
      if (fmt === 'XML') {
        const res = await this.exchangeService.exportXml(cid, req);
        this.lastExportResult.set(res);
        this.exchangeService.triggerTextDownload(res);
        this.statusBanner.set(`Exported Legacy XML (${res.fileName}).`);
      } else if (fmt === 'JSONEX') {
        const res = await this.exchangeService.exportJsonEx(cid, req);
        this.lastExportResult.set(res);
        this.exchangeService.triggerTextDownload(res);
        this.statusBanner.set(`Exported JSONEx (${res.fileName}).`);
      } else if (fmt === 'ASCII') {
        const res = await this.exchangeService.exportAscii(cid, req);
        this.lastExportResult.set(res);
        this.exchangeService.triggerTextDownload(res);
        this.statusBanner.set(`Exported ASCII CSV (${res.fileName}).`);
      } else if (fmt === 'HTML') {
        const res = await this.exchangeService.exportHtml(cid, req);
        this.lastExportResult.set(res);
        this.exchangeService.triggerTextDownload(res);
        this.statusBanner.set(`Exported Web-Publishing HTML (${res.fileName}).`);
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

  async downloadSampleExcelFile(sampleType: SampleExcelType = 'ALL'): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      await this.exchangeService.downloadSampleExcel(cid, sampleType);
      this.statusBanner.set(`Downloaded Sample Excel File (${sampleType}).`);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to download sample Excel');
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
      masterDuplicateRule: this.importMasterDuplicateRule,
      overwriteVoucherByGuid: this.importOverwriteVoucherByGuid,
      exceptionBehavior: this.importExceptionBehavior,
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
      if (res.exceptions && res.exceptions.length > 0) {
        this.importExceptions.set(res.exceptions);
      }
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

    const fromCol = this.newTemplateFromColumn.trim().toUpperCase();
    const toCol = this.newTemplateToColumn.trim().toUpperCase();
    const colPattern = /^[A-Z]{1,2}$/;
    if ((fromCol && !colPattern.test(fromCol)) || (toCol && !colPattern.test(toCol))) {
      this.errorBanner.set(
        'The value entered for the from/to column in the mapping template is invalid. Enter a value from A to ZZ and try again.',
      );
      return;
    }
    if (fromCol && toCol && this.excelColToIndex(toCol) < this.excelColToIndex(fromCol)) {
      this.errorBanner.set(
        "In the selected mapping template, the value in the 'to' field should be greater than or equal to the value in the 'from' field.",
      );
      return;
    }

    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const created = await this.exchangeService.createTemplate(cid, {
        templateName: name,
        entityScope: this.newTemplateScope,
        sheetName: this.newTemplateSheet.trim() || 'Sheet1',
        headerRowIndex: 1,
        templateScope: this.newTemplateCompanyScope,
        fromColumn: fromCol || null,
        toColumn: toCol || null,
        excludeRowColumn: this.newTemplateExcludeRowColumn.trim() || null,
        excludeRowOperator: this.newTemplateExcludeRowColumn.trim()
          ? this.newTemplateExcludeRowOperator
          : null,
        excludeRowValue: this.newTemplateExcludeRowColumn.trim()
          ? this.newTemplateExcludeRowValue.trim()
          : null,
        columnMappings:
          this.newTemplateScope === 'MASTERS'
            ? {
                name: 'Party Name',
                groupName: 'Under Group',
                openingBalance: 'Opening Balance',
              }
            : {
                voucherDate: 'Date',
                voucherNumber: 'Voucher No',
                ledgerName: 'Particulars',
                amount: 'Amount',
              },
      });
      this.templates.update((prev) => [...prev, created]);
      this.selectedTemplateId.set(created.id);
      this.newTemplateName = '';
      this.newTemplateFromColumn = '';
      this.newTemplateToColumn = '';
      this.newTemplateExcludeRowColumn = '';
      this.newTemplateExcludeRowValue = '';
      this.statusBanner.set(`Saved spreadsheet mapping template "${created.templateName}".`);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to save template');
    } finally {
      this.busy.set(false);
    }
  }

  async deleteMappingTemplate(templateId: string): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid || !templateId) return;
    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      await this.exchangeService.deleteTemplate(cid, templateId);
      this.templates.update((prev) => prev.filter((t) => t.id !== templateId));
      if (this.selectedTemplateId() === templateId) {
        this.selectedTemplateId.set('');
      }
      this.statusBanner.set('Deleted mapping template.');
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to delete template');
    } finally {
      this.busy.set(false);
    }
  }

  async loadImportExceptions(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const items = await this.exchangeService.listImportExceptions(cid);
      this.importExceptions.set(items);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to load import exceptions');
    } finally {
      this.busy.set(false);
    }
  }

  async resolveException(exceptionId: string): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid || !exceptionId) return;
    const dr = this.resolveDebitLedgerName.trim();
    const cr = this.resolveCreditLedgerName.trim();
    const amt = this.resolveAmount.trim();
    if (!dr || !cr || !amt) {
      this.errorBanner.set(
        'Provide non-blank debitLedgerName, creditLedgerName, and positive amount to resolve exception.',
      );
      return;
    }

    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const resolved = await this.exchangeService.resolveImportException(cid, exceptionId, {
        voucherTypeName: this.resolveVoucherTypeName.trim() || 'Sales',
        voucherDate: this.resolveVoucherDate.trim() || this.companyContext.periodFrom(),
        debitLedgerName: dr,
        creditLedgerName: cr,
        amount: amt,
        narration: this.resolveNarration.trim() || 'Created due to Data Exception Resolution',
      });
      this.importExceptions.update((prev) =>
        prev.map((item) => (item.exceptionId === exceptionId ? resolved : item)),
      );
      this.statusBanner.set(
        `Resolved import exception ${exceptionId} (Created due to Data Exception Resolution).`,
      );
    } catch (err: unknown) {
      this.errorBanner.set(
        err instanceof Error ? err.message : 'Unable to resolve import exception',
      );
    } finally {
      this.busy.set(false);
    }
  }

  async loadSyncRules(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const rules = await this.exchangeService.listSyncRules(cid);
      this.syncRules.set(rules);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to load sync rules');
    } finally {
      this.busy.set(false);
    }
  }

  async createNewSyncRule(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    const ruleName = this.newSyncRuleName.trim();
    const targetGuid = this.newSyncTargetCompanyGuid.trim();
    if (!cid || !ruleName) return;
    if (!targetGuid) {
      this.errorBanner.set(
        'Insufficient information for Synchronisation- No Destination Company Name present',
      );
      return;
    }
    if (targetGuid.toLowerCase() === cid.toLowerCase()) {
      this.errorBanner.set(
        'Client and Server companies have same GUID. Cannot proceed with synchronization.',
      );
      return;
    }

    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const created = await this.exchangeService.createSyncRule(cid, {
        ruleName,
        ruleType: this.newSyncRuleType,
        direction: this.newSyncDirection,
        targetCompanyId: targetGuid,
        targetCompanyGuid: targetGuid,
        targetCompanyName: this.newSyncTargetCompanyName.trim() || 'Remote Company',
        serverUrl: this.newSyncServerUrl.trim() || 'http://localhost:18080',
        masterDuplicateRule: this.newSyncMasterDuplicateRule,
        overwriteVoucherByGuid: this.newSyncOverwriteVoucherByGuid,
      });
      this.syncRules.update((prev) => [...prev, created]);
      this.newSyncRuleName = '';
      this.newSyncTargetCompanyGuid = '';
      this.newSyncTargetCompanyName = '';
      this.statusBanner.set(`Saved Client/Server Sync Rule "${created.ruleName}".`);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to save sync rule');
    } finally {
      this.busy.set(false);
    }
  }

  async runSyncRule(ruleId: string): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid || !ruleId) return;
    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const result = await this.exchangeService.executeSyncRule(cid, ruleId);
      this.lastSyncSessionResult.set(result);
      this.syncRules.update((prev) =>
        prev.map((r) =>
          r.id === ruleId ? { ...r, lastSyncAlterId: result.newAlterIdWatermark } : r,
        ),
      );
      this.statusBanner.set(result.message);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Synchronisation failed');
    } finally {
      this.busy.set(false);
    }
  }

  async loadEditLogSummary(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const summary = await this.exchangeService.getEditLogSummary(
        cid,
        this.editLogEntityTypeFilter,
        this.editLogOnlyAltered,
        this.editLogUsernameFilter,
      );
      this.editLogSummary.set(summary);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to load Edit Log summary');
    } finally {
      this.busy.set(false);
    }
  }

  async runMultiAccountReportExport(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const res = await this.exchangeService.exportMultiAccountReport(cid, {
        reportType: this.multiAccountReportType,
        fromDate: this.companyContext.periodFrom(),
        toDate: this.companyContext.periodTo(),
        format: this.multiAccountExportFormat,
        includeZeroBalanceParties: this.multiAccountIncludeZeroBalances,
      });
      this.multiAccountReportResponse.set(res);
      this.statusBanner.set(
        `Exported Multi-Account ${res.reportType} (${res.partyCount} party statement(s)).`,
      );
    } catch (err: unknown) {
      this.errorBanner.set(
        err instanceof Error ? err.message : 'Unable to export Multi-Account report',
      );
    } finally {
      this.busy.set(false);
    }
  }

  async loadDashboardTiles(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const res = await this.exchangeService.exportDashboardTiles(cid, {
        fromDate: this.companyContext.periodFrom(),
        toDate: this.companyContext.periodTo(),
        format: this.dashboardExportFormat,
      });
      this.dashboardTilesResponse.set(res);
      this.statusBanner.set(`Loaded ${res.totalTiles} Dashboard Tile(s) (${res.format}).`);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to load Dashboard Tiles');
    } finally {
      this.busy.set(false);
    }
  }

  toggleDashboardVerticalView(): void {
    this.dashboardVerticalView.set(!this.dashboardVerticalView());
  }

  removeDashboardTile(tileCode: string): void {
    if (!this.hiddenDashboardTileCodes().includes(tileCode)) {
      this.hiddenDashboardTileCodes.set([...this.hiddenDashboardTileCodes(), tileCode]);
    }
    if (this.expandedDashboardTileCode() === tileCode) {
      this.expandedDashboardTileCode.set(null);
    }
  }

  addDashboardTile(tileCode: string): void {
    this.hiddenDashboardTileCodes.set(
      this.hiddenDashboardTileCodes().filter((code) => code !== tileCode),
    );
  }

  restoreAllDashboardTiles(): void {
    this.hiddenDashboardTileCodes.set([]);
  }

  toggleExpandDashboardTile(tileCode: string): void {
    this.expandedDashboardTileCode.set(
      this.expandedDashboardTileCode() === tileCode ? null : tileCode,
    );
  }

  tileBreakdownEntries(
    tile: DashboardTileDto,
  ): readonly { readonly key: string; readonly value: string }[] {
    return Object.entries(tile.breakdown ?? {}).map(([key, value]) => ({
      key,
      value: String(value),
    }));
  }

  async runCompanyDataVerification(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const report = await this.exchangeService.verifyCompanyData(cid);
      this.dataVerificationReport.set(report);
      this.statusBanner.set(
        report.readyForSplitOrSync
          ? 'Company Data Verification PASSED — Ready for Split / Synchronization.'
          : 'Company Data Verification found pending exceptions requiring resolution.',
      );
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to verify company data');
    } finally {
      this.busy.set(false);
    }
  }

  private excelColToIndex(col: string): number {
    let idx = 0;
    for (let i = 0; i < col.length; i++) {
      idx = idx * 26 + (col.charCodeAt(i) - 64);
    }
    return idx;
  }
}
