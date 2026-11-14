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
import { Router, RouterLink } from '@angular/router';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { ExactDecimal } from '../../core/money/exact-decimal';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { CompanyContextStore } from '../../state/company-context.store';
import {
  GstRegistrationMaster,
  Gstr1ReportDto,
  UncertainTransactionDto,
} from './statutory.models';
import { StatutoryService } from './statutory.service';

export type Gstr1InvoiceTab = 'B2B' | 'B2CL' | 'B2CS' | 'CDNR' | 'EXP';

@Component({
  selector: 'mc-gstr1-dashboard',
  standalone: true,
  imports: [FormsModule, RouterLink, IndianCurrencyPipe, CodeBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="gstr1-page">
      <header class="mc-card report-header">
        <div class="header-left">
          <span class="return-pill">Form GSTR-1 &bull; Outward Supplies</span>
          <h1>GSTR-1 Return Dashboard</h1>
          <p class="period-caption">
            Period: <strong>{{ fromDate() }}</strong> to <strong>{{ toDate() }}</strong>
            @if (activeGstinLabel(); as gstinText) {
              &bull; GSTIN: <mc-code-badge [code]="gstinText" />
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
              aria-label="GSTR-1 From Date"
            />
          </label>
          <label class="inline-field">
            <span>To</span>
            <input
              type="date"
              class="mc-input"
              [ngModel]="toDate()"
              (ngModelChange)="toDate.set($event)"
              aria-label="GSTR-1 To Date"
            />
          </label>

          @if (registrations().length > 0 || multiGstinEnabled()) {
            <label class="inline-field">
              <span>GSTIN</span>
              <select
                class="mc-input"
                aria-label="Select GSTIN Registration"
                [ngModel]="selectedRegistrationId()"
                (focus)="ensureRegistrationsLoaded()"
                (ngModelChange)="onRegistrationChange($event)"
              >
                <option value="">All / Primary GSTIN</option>
                @for (reg of registrations(); track reg.id) {
                  <option [value]="reg.id">{{ reg.stateCode }} — {{ reg.gstin }}</option>
                }
              </select>
            </label>
          }

          <button type="button" class="mc-btn" (click)="reloadReport()">Refresh</button>
          <button
            type="button"
            class="mc-btn mc-btn-primary"
            data-testid="gstr1-export-json-btn"
            (click)="exportJson()"
          >
            Export JSON (Alt+E)
          </button>
        </div>
      </header>

      <!-- Uncertain Transactions (Ctrl+J) Exception Banner -->
      <section
        class="uncertain-banner"
        [class.uncertain-alert]="(report()?.uncertainCount ?? 0) > 0"
        data-testid="gstr1-uncertain-banner"
      >
        <div class="uncertain-info">
          <strong>Uncertain Transactions (Corrections Needed):</strong>
          <span class="uncertain-count" data-testid="gstr1-uncertain-count">
            {{ report()?.uncertainCount ?? 0 }}
          </span>
          <span class="uncertain-desc">
            Vouchers with invalid party GSTIN, missing HSN/SAC, or manual tax override conflicts.
          </span>
        </div>
        <button
          type="button"
          class="mc-btn"
          data-testid="open-uncertain-btn"
          (click)="openUncertainModal()"
        >
          Exceptions (Ctrl+J)
        </button>
      </section>

      @if (loading()) {
        <div class="mc-card state-box">Computing GSTR-1 return tables...</div>
      } @else if (report(); as r) {
        <!-- Top KPI Totals -->
        <section class="kpi-strip" aria-label="GSTR-1 Return Totals">
          <article class="mc-card kpi-tile">
            <span class="kpi-label">Total Taxable Value</span>
            <span class="mc-amount kpi-val" data-testid="gstr1-total-taxable">
              {{ r.totalTaxableValue | mcIndianCurrency }}
            </span>
          </article>
          <article class="mc-card kpi-tile">
            <span class="kpi-label">Total Output Tax</span>
            <span class="mc-amount kpi-val kpi-accent" data-testid="gstr1-total-tax">
              {{ r.totalTax | mcIndianCurrency }}
            </span>
          </article>
          <article class="mc-card kpi-tile">
            <span class="kpi-label">IGST</span>
            <span class="mc-amount kpi-val">{{ r.totalIgst | mcIndianCurrency }}</span>
          </article>
          <article class="mc-card kpi-tile">
            <span class="kpi-label">CGST</span>
            <span class="mc-amount kpi-val">{{ r.totalCgst | mcIndianCurrency }}</span>
          </article>
          <article class="mc-card kpi-tile">
            <span class="kpi-label">SGST / UTGST</span>
            <span class="mc-amount kpi-val">{{ r.totalSgst | mcIndianCurrency }}</span>
          </article>
          <article class="mc-card kpi-tile">
            <span class="kpi-label">Cess</span>
            <span class="mc-amount kpi-val">{{ r.totalCess | mcIndianCurrency }}</span>
          </article>
        </section>

        <!-- Summary Cards (B2B, B2CL, B2CS, CDNR, Exports) -->
        <section class="bucket-cards" aria-label="GSTR-1 Return Sections">
          @for (card of sectionSummaryCards(); track card.tab) {
            <button
              type="button"
              class="mc-card bucket-card"
              [class.bucket-active]="activeTab() === card.tab"
              [attr.data-testid]="'gstr1-card-' + card.tab"
              (click)="activeTab.set(card.tab)"
            >
              <div class="bucket-head">
                <span class="bucket-title">{{ card.title }}</span>
                <span class="bucket-count">{{ card.count }} rows</span>
              </div>
              <div class="bucket-metric">
                <span>Taxable</span>
                <span class="mc-amount">{{ card.taxable | mcIndianCurrency }}</span>
              </div>
              <div class="bucket-metric">
                <span>Tax</span>
                <span class="mc-amount">{{ card.tax | mcIndianCurrency }}</span>
              </div>
            </button>
          }
        </section>

        <!-- Active Voucher / Rate Table -->
        <section class="mc-card section-panel">
          <header class="panel-head">
            <h2>Section Drill-Down — {{ activeTab() }}</h2>
            <div class="nav-links">
              <a routerLink="/app/statutory/gstr3b" class="mc-btn">Open GSTR-3B</a>
              <a routerLink="/app/statutory/gstr9" class="mc-btn">One-Click GSTR-9</a>
              <a routerLink="/app/statutory/einvoice" class="mc-btn">e-Invoice / EWB</a>
            </div>
          </header>

          @switch (activeTab()) {
            @case ('B2B') {
              <table class="mc-table" data-testid="gstr1-b2b-table">
                <thead>
                  <tr>
                    <th>Recipient GSTIN</th>
                    <th>Party Name</th>
                    <th>Invoice No</th>
                    <th>Date</th>
                    <th>POS</th>
                    <th class="num-col">Taxable Value</th>
                    <th class="num-col">IGST</th>
                    <th class="num-col">CGST</th>
                    <th class="num-col">SGST</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of r.b2b; track row.invoiceNumber + row.partyGstin) {
                    <tr>
                      <td><mc-code-badge [code]="row.partyGstin" /></td>
                      <td>{{ row.partyName }}</td>
                      <td class="mc-code">{{ row.invoiceNumber }}</td>
                      <td>{{ row.invoiceDate }}</td>
                      <td>{{ row.placeOfSupply }}</td>
                      <td class="mc-amount num-col">{{ row.taxableValue | mcIndianCurrency }}</td>
                      <td class="mc-amount num-col">{{ row.igstAmount | mcIndianCurrency }}</td>
                      <td class="mc-amount num-col">{{ row.cgstAmount | mcIndianCurrency }}</td>
                      <td class="mc-amount num-col">{{ row.sgstAmount | mcIndianCurrency }}</td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="9" class="empty-cell">No B2B invoices in selected period.</td>
                    </tr>
                  }
                </tbody>
              </table>
            }
            @case ('B2CL') {
              <table class="mc-table">
                <thead>
                  <tr>
                    <th>Invoice No</th>
                    <th>Date</th>
                    <th>Place of Supply</th>
                    <th class="num-col">Rate %</th>
                    <th class="num-col">Taxable Value</th>
                    <th class="num-col">IGST</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of r.b2cl; track row.invoiceNumber) {
                    <tr>
                      <td class="mc-code">{{ row.invoiceNumber }}</td>
                      <td>{{ row.invoiceDate }}</td>
                      <td>{{ row.placeOfSupply }}</td>
                      <td class="mc-amount num-col">{{ row.gstRatePct }}%</td>
                      <td class="mc-amount num-col">{{ row.taxableValue | mcIndianCurrency }}</td>
                      <td class="mc-amount num-col">{{ row.igstAmount | mcIndianCurrency }}</td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="6" class="empty-cell">
                        No B2C Large inter-state invoices (&gt; &#x20B9; 1,00,000) in period.
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            }
            @case ('B2CS') {
              <table class="mc-table">
                <thead>
                  <tr>
                    <th>Supply Type</th>
                    <th>Place of Supply</th>
                    <th class="num-col">Rate %</th>
                    <th class="num-col">Taxable Value</th>
                    <th class="num-col">IGST</th>
                    <th class="num-col">CGST</th>
                    <th class="num-col">SGST</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of r.b2cs; track row.placeOfSupply + row.gstRatePct) {
                    <tr>
                      <td>{{ row.supplyType }}</td>
                      <td>{{ row.placeOfSupply }}</td>
                      <td class="mc-amount num-col">{{ row.gstRatePct }}%</td>
                      <td class="mc-amount num-col">{{ row.taxableValue | mcIndianCurrency }}</td>
                      <td class="mc-amount num-col">{{ row.igstAmount | mcIndianCurrency }}</td>
                      <td class="mc-amount num-col">{{ row.cgstAmount | mcIndianCurrency }}</td>
                      <td class="mc-amount num-col">{{ row.sgstAmount | mcIndianCurrency }}</td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="7" class="empty-cell">No B2C Small supplies in period.</td>
                    </tr>
                  }
                </tbody>
              </table>
            }
            @case ('CDNR') {
              <table class="mc-table">
                <thead>
                  <tr>
                    <th>Recipient GSTIN</th>
                    <th>Note No</th>
                    <th>Date</th>
                    <th>Type</th>
                    <th class="num-col">Taxable Value</th>
                    <th class="num-col">IGST</th>
                    <th class="num-col">CGST</th>
                    <th class="num-col">SGST</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of r.cdnr; track row.noteNumber) {
                    <tr>
                      <td><mc-code-badge [code]="row.partyGstin" /></td>
                      <td class="mc-code">{{ row.noteNumber }}</td>
                      <td>{{ row.noteDate }}</td>
                      <td>{{ row.noteType }}</td>
                      <td class="mc-amount num-col">{{ row.taxableValue | mcIndianCurrency }}</td>
                      <td class="mc-amount num-col">{{ row.igstAmount | mcIndianCurrency }}</td>
                      <td class="mc-amount num-col">{{ row.cgstAmount | mcIndianCurrency }}</td>
                      <td class="mc-amount num-col">{{ row.sgstAmount | mcIndianCurrency }}</td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="8" class="empty-cell">No Credit/Debit Notes (Registered) in period.</td>
                    </tr>
                  }
                </tbody>
              </table>
            }
            @case ('EXP') {
              <table class="mc-table">
                <thead>
                  <tr>
                    <th>Export Type</th>
                    <th>Invoice No</th>
                    <th>Date</th>
                    <th>Port / Shipping Bill</th>
                    <th class="num-col">Taxable Value</th>
                    <th class="num-col">IGST</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of r.exp; track row.invoiceNumber) {
                    <tr>
                      <td>{{ row.exportType }}</td>
                      <td class="mc-code">{{ row.invoiceNumber }}</td>
                      <td>{{ row.invoiceDate }}</td>
                      <td>{{ row.portCode || '—' }} / {{ row.shippingBillNo || '—' }}</td>
                      <td class="mc-amount num-col">{{ row.taxableValue | mcIndianCurrency }}</td>
                      <td class="mc-amount num-col">{{ row.igstAmount | mcIndianCurrency }}</td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="6" class="empty-cell">No Export invoices in period.</td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          }
        </section>

        <!-- Phase III Bifurcated Table 12: HSN B2B & HSN B2C -->
        <div class="two-col-panels">
          <section class="mc-card section-panel" data-testid="gstr1-hsn-b2b-panel">
            <header class="panel-head">
              <h2>Table 12A — HSN Summary (B2B Supplies)</h2>
              <span class="phase-pill">Phase III Bifurcated</span>
            </header>
            <table class="mc-table">
              <thead>
                <tr>
                  <th>HSN / SAC</th>
                  <th>UQC</th>
                  <th class="num-col">Qty</th>
                  <th class="num-col">Taxable Value</th>
                  <th class="num-col">IGST</th>
                  <th class="num-col">CGST</th>
                  <th class="num-col">SGST</th>
                </tr>
              </thead>
              <tbody>
                @for (h of r.hsnB2b; track h.hsnSacCode + h.uqcCode + (h.gstRatePct ?? '')) {
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
                    <td colspan="7" class="empty-cell">No B2B HSN rows.</td>
                  </tr>
                }
              </tbody>
            </table>
          </section>

          <section class="mc-card section-panel" data-testid="gstr1-hsn-b2c-panel">
            <header class="panel-head">
              <h2>Table 12B — HSN Summary (B2C Supplies)</h2>
              <span class="phase-pill">Phase III Bifurcated</span>
            </header>
            <table class="mc-table">
              <thead>
                <tr>
                  <th>HSN / SAC</th>
                  <th>UQC</th>
                  <th class="num-col">Qty</th>
                  <th class="num-col">Taxable Value</th>
                  <th class="num-col">IGST</th>
                  <th class="num-col">CGST</th>
                  <th class="num-col">SGST</th>
                </tr>
              </thead>
              <tbody>
                @for (h of r.hsnB2c; track h.hsnSacCode + h.uqcCode + (h.gstRatePct ?? '')) {
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
                    <td colspan="7" class="empty-cell">No B2C HSN rows.</td>
                  </tr>
                }
              </tbody>
            </table>
          </section>
        </div>

        <!-- Table 13 Document Summary -->
        <section class="mc-card section-panel" data-testid="gstr1-doc-summary-panel">
          <header class="panel-head">
            <h2>Table 13 — Document Summary (Serial Continuity &amp; Cancelled Count)</h2>
          </header>
          <table class="mc-table">
            <thead>
              <tr>
                <th>Nature of Document</th>
                <th>Sr. No. From</th>
                <th>Sr. No. To</th>
                <th class="num-col">Total Issued</th>
                <th class="num-col">Cancelled</th>
                <th class="num-col">Net Issued</th>
              </tr>
            </thead>
            <tbody>
              @for (doc of r.documentSummary; track doc.documentNature + doc.serialFrom) {
                <tr>
                  <td>{{ doc.documentNature }}</td>
                  <td class="mc-code">{{ doc.serialFrom }}</td>
                  <td class="mc-code">{{ doc.serialTo }}</td>
                  <td class="mc-amount num-col">{{ doc.totalCount }}</td>
                  <td class="mc-amount num-col">{{ doc.cancelledCount }}</td>
                  <td class="mc-amount num-col">{{ doc.netIssuedCount }}</td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="6" class="empty-cell">No serial document summary rows in period.</td>
                </tr>
              }
            </tbody>
          </table>
        </section>
      }

      <!-- Uncertain Transactions (Ctrl+J) Modal -->
      @if (uncertainModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Uncertain Transactions Resolution"
          data-testid="uncertain-modal"
          (keydown.escape)="closeUncertainModal()"
        >
          <div class="mc-panel uncertain-dialog">
            <header class="dialog-head">
              <h2>Uncertain Transactions (Ctrl+J) — Statutory Exception Fixer</h2>
              <button type="button" class="mc-btn" (click)="closeUncertainModal()">Esc Close</button>
            </header>

            @if (conflictError(); as cErr) {
              <div class="conflict-error" role="alert">{{ cErr }}</div>
            }

            <div class="dialog-body">
              <table class="mc-table" data-testid="uncertain-transactions-table">
                <thead>
                  <tr>
                    <th>Voucher</th>
                    <th>Party</th>
                    <th>Exception</th>
                    <th class="num-col">Computed Tax</th>
                    <th class="num-col">Booked Tax</th>
                    <th>Resolution / Audit Reason</th>
                  </tr>
                </thead>
                <tbody>
                  @for (u of uncertainItems(); track u.voucherId) {
                    <tr [attr.data-testid]="'uncertain-row-' + u.voucherId">
                      <td>
                        <strong>{{ u.voucherTypeName }}</strong>
                        <span class="mc-code">{{ u.voucherNumber }}</span>
                        <div class="muted-date">{{ u.voucherDate }}</div>
                      </td>
                      <td>
                        {{ u.partyName }}
                        @if (u.partyGstin) {
                          <div><mc-code-badge [code]="u.partyGstin" /></div>
                        }
                      </td>
                      <td>
                        <mc-code-badge [code]="u.exceptionCode" />
                        <div class="muted-date">{{ u.exceptionReason }}</div>
                      </td>
                      <td class="mc-amount num-col">{{ u.computedTaxAmount | mcIndianCurrency }}</td>
                      <td class="mc-amount num-col">{{ u.bookedTaxAmount | mcIndianCurrency }}</td>
                      <td>
                        <div class="resolve-controls">
                          <input
                            type="text"
                            class="mc-input"
                            [attr.data-testid]="'conflict-reason-' + u.voucherId"
                            [ngModel]="reasonFor(u.voucherId)"
                            (ngModelChange)="setReason(u.voucherId, $event)"
                            placeholder="Mandatory audit reason to accept..."
                          />
                          <div class="resolve-btns">
                            <button
                              type="button"
                              class="mc-btn mc-btn-primary"
                              [attr.data-testid]="'accept-conflict-btn-' + u.voucherId"
                              (click)="acceptConflict(u)"
                            >
                              Accept Conflict
                            </button>
                            <button
                              type="button"
                              class="mc-btn"
                              (click)="drillToVoucher(u.voucherId)"
                            >
                              Alter Voucher
                            </button>
                          </div>
                        </div>
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="6" class="empty-cell">
                        Zero uncertain transactions pending in this period.
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        </div>
      }
    </div>
  `,
  styles: `
    .gstr1-page {
      display: flex;
      flex-direction: column;
      gap: 0.85rem;
    }

    .report-header {
      padding: 0.85rem 1rem;
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 0.75rem;
      background: #142f42;
      color: #ffffff;
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

    .uncertain-banner {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      padding: 0.6rem 1rem;
      border-radius: 6px;
      background: #f6faf9;
      border: 1px solid #cbe4e0;
      font-size: 0.82rem;
      color: #142f42;
    }

    .uncertain-alert {
      background: #fff6e5;
      border-color: #e09f2d;
      color: #6b4000;
    }

    .uncertain-info {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.5rem;
    }

    .uncertain-count {
      padding: 0.1rem 0.5rem;
      border-radius: 999px;
      background: #142f42;
      color: #ffffff;
      font-weight: 700;
      font-size: 0.78rem;
    }

    .uncertain-desc {
      font-weight: 400;
    }

    .kpi-strip {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(165px, 1fr));
      gap: 0.65rem;
    }

    .kpi-tile {
      padding: 0.65rem 0.85rem;
      display: flex;
      flex-direction: column;
      gap: 0.2rem;
    }

    .kpi-label {
      font-size: 0.74rem;
      font-weight: 600;
      color: #4f6775;
    }

    .kpi-val {
      font-size: 1rem;
      font-weight: 700;
      color: #142f42;
    }

    .kpi-accent {
      color: #006d68;
    }

    .bucket-cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(185px, 1fr));
      gap: 0.65rem;
    }

    .bucket-card {
      padding: 0.65rem 0.85rem;
      text-align: left;
      cursor: pointer;
      display: flex;
      flex-direction: column;
      gap: 0.3rem;
      background: #ffffff;
      border: 1px solid #d4e2e0;
    }

    .bucket-active {
      border: 2px solid #008d87;
      background: #f0faf8;
    }

    .bucket-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 0.8rem;
      font-weight: 700;
      color: #142f42;
    }

    .bucket-count {
      font-size: 0.72rem;
      color: #006d68;
      font-weight: 600;
    }

    .bucket-metric {
      display: flex;
      justify-content: space-between;
      font-size: 0.76rem;
      color: #4f6775;
    }

    .section-panel {
      overflow: hidden;
    }

    .panel-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.65rem;
      padding: 0.6rem 0.9rem;
      background: #f6faf9;
      border-bottom: 1px solid #d4e2e0;
    }

    .panel-head h2 {
      margin: 0;
      font-size: 0.88rem;
      font-weight: 700;
      color: #142f42;
    }

    .phase-pill {
      padding: 0.1rem 0.45rem;
      border-radius: 999px;
      background: #dff6ef;
      color: #006d68;
      font-size: 0.7rem;
      font-weight: 700;
    }

    .nav-links {
      display: flex;
      gap: 0.45rem;
    }

    .two-col-panels {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.85rem;
    }

    @media (max-width: 980px) {
      .two-col-panels {
        grid-template-columns: 1fr;
      }
    }

    .num-col {
      text-align: right;
    }

    .empty-cell {
      text-align: center;
      padding: 1rem;
      color: #5b7280;
      font-weight: 500;
    }

    .state-box {
      padding: 1.5rem;
      font-weight: 600;
      color: #142f42;
    }

    .uncertain-dialog {
      width: min(980px, 95vw);
      max-height: 85vh;
      display: flex;
      flex-direction: column;
      background: #ffffff;
      overflow: hidden;
    }

    .dialog-head {
      padding: 0.75rem 1rem;
      background: #142f42;
      color: #ffffff;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .dialog-head h2 {
      margin: 0;
      font-size: 0.95rem;
      font-weight: 700;
      color: #ffffff;
    }

    .dialog-body {
      padding: 0.85rem 1rem;
      overflow-y: auto;
    }

    .conflict-error {
      margin: 0.65rem 1rem 0;
      padding: 0.5rem 0.75rem;
      border-radius: 6px;
      background: #fdecec;
      color: #8f1d1d;
      font-size: 0.82rem;
      font-weight: 600;
    }

    .muted-date {
      font-size: 0.74rem;
      color: #566e7c;
    }

    .resolve-controls {
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
    }

    .resolve-btns {
      display: flex;
      gap: 0.4rem;
    }
  `,
})
export class Gstr1DashboardComponent implements OnInit {
  private readonly statutoryService = inject(StatutoryService);
  private readonly companyContext = inject(CompanyContextStore);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal<boolean>(false);
  readonly report = signal<Gstr1ReportDto | null>(null);
  readonly registrations = signal<readonly GstRegistrationMaster[]>([]);
  readonly selectedRegistrationId = signal<string>('');
  readonly fromDate = signal<string>('2025-04-01');
  readonly toDate = signal<string>('2025-04-30');
  readonly activeTab = signal<Gstr1InvoiceTab>('B2B');

  readonly uncertainModalOpen = signal<boolean>(false);
  readonly uncertainItems = signal<readonly UncertainTransactionDto[]>([]);
  readonly conflictReasons = signal<Readonly<Record<string, string>>>({});
  readonly conflictError = signal<string | null>(null);

  readonly multiGstinEnabled = computed(
    () => this.companyContext.activeCompany()?.features.multiGstinEnabled ?? false,
  );

  readonly activeGstinLabel = computed(() => {
    const regId = this.selectedRegistrationId();
    if (regId) {
      const found = this.registrations().find((r) => r.id === regId);
      if (found) {
        return found.gstin;
      }
    }
    return this.report()?.gstin ?? this.companyContext.activeCompany()?.features.gstin ?? '';
  });

  readonly sectionSummaryCards = computed(() => {
    const r = this.report();
    if (!r) {
      return [];
    }
    const sumTax = (
      rows: readonly {
        readonly igstAmount?: string;
        readonly cgstAmount?: string;
        readonly sgstAmount?: string;
        readonly cessAmount?: string;
      }[],
    ): string => {
      let acc = ExactDecimal.zero(2);
      for (const row of rows) {
        acc = acc
          .add(ExactDecimal.tryParse(row.igstAmount ?? '0', 2, 'HALF_UP') ?? ExactDecimal.zero(2))
          .add(ExactDecimal.tryParse(row.cgstAmount ?? '0', 2, 'HALF_UP') ?? ExactDecimal.zero(2))
          .add(ExactDecimal.tryParse(row.sgstAmount ?? '0', 2, 'HALF_UP') ?? ExactDecimal.zero(2))
          .add(ExactDecimal.tryParse(row.cessAmount ?? '0', 2, 'HALF_UP') ?? ExactDecimal.zero(2));
      }
      return acc.toWireString(2);
    };
    const sumTaxable = (rows: readonly { readonly taxableValue: string }[]): string =>
      ExactDecimal.sum(
        rows.map((x) => x.taxableValue),
        2,
      ).toWireString(2);

    return [
      {
        tab: 'B2B' as Gstr1InvoiceTab,
        title: '4A — B2B Invoices',
        count: r.b2b.length,
        taxable: sumTaxable(r.b2b),
        tax: sumTax(r.b2b),
      },
      {
        tab: 'B2CL' as Gstr1InvoiceTab,
        title: '5A — B2C Large',
        count: r.b2cl.length,
        taxable: sumTaxable(r.b2cl),
        tax: sumTax(r.b2cl),
      },
      {
        tab: 'B2CS' as Gstr1InvoiceTab,
        title: '7 — B2C Small',
        count: r.b2cs.length,
        taxable: sumTaxable(r.b2cs),
        tax: sumTax(r.b2cs),
      },
      {
        tab: 'CDNR' as Gstr1InvoiceTab,
        title: '9B — Credit/Debit Notes',
        count: r.cdnr.length,
        taxable: sumTaxable(r.cdnr),
        tax: sumTax(r.cdnr),
      },
      {
        tab: 'EXP' as Gstr1InvoiceTab,
        title: '6A — Exports',
        count: r.exp.length,
        taxable: sumTaxable(r.exp),
        tax: sumTax(r.exp),
      },
    ];
  });

  constructor() {
    const unbindExceptions = this.shortcutRegistry.bindHandler(
      'report.exceptions',
      'SCREEN',
      () => {
        void this.openUncertainModal();
      },
    );
    const unbindExport = this.shortcutRegistry.bindHandler('menu.export', 'SCREEN', () => {
      void this.exportJson();
    });
    this.destroyRef.onDestroy(() => {
      unbindExceptions();
      unbindExport();
    });
  }

  ngOnInit(): void {
    this.fromDate.set(this.companyContext.periodFrom());
    this.toDate.set(this.companyContext.periodTo());
    if (this.multiGstinEnabled()) {
      void this.ensureRegistrationsLoaded();
    }
    void this.reloadReport();
  }

  async ensureRegistrationsLoaded(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId || this.registrations().length > 0) {
      return;
    }
    try {
      const list = await this.statutoryService.listGstRegistrations(companyId);
      this.registrations.set(list);
    } catch {
      this.registrations.set([]);
    }
  }

  onRegistrationChange(regId: string): void {
    this.selectedRegistrationId.set(regId);
    void this.reloadReport();
  }

  async reloadReport(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) {
      return;
    }
    this.loading.set(true);
    try {
      const data = await this.statutoryService.getGstr1(companyId, {
        fromDate: this.fromDate(),
        toDate: this.toDate(),
        gstRegistrationId: this.selectedRegistrationId() || null,
      });
      this.report.set(data);
    } catch {
      this.report.set(null);
    } finally {
      this.loading.set(false);
    }
  }

  async exportJson(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) {
      return;
    }
    await this.statutoryService.exportGstr1(companyId, {
      fromDate: this.fromDate(),
      toDate: this.toDate(),
      gstRegistrationId: this.selectedRegistrationId() || null,
    });
  }

  async openUncertainModal(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) {
      return;
    }
    this.conflictError.set(null);
    this.uncertainModalOpen.set(true);
    try {
      const list = await this.statutoryService.listUncertainTransactions(
        companyId,
        this.fromDate(),
        this.toDate(),
      );
      this.uncertainItems.set(list);
    } catch {
      this.uncertainItems.set([]);
    }
  }

  closeUncertainModal(): void {
    this.uncertainModalOpen.set(false);
    this.conflictError.set(null);
  }

  reasonFor(voucherId: string): string {
    return this.conflictReasons()[voucherId] ?? '';
  }

  setReason(voucherId: string, reason: string): void {
    this.conflictReasons.update((prev) => ({ ...prev, [voucherId]: reason }));
  }

  async acceptConflict(item: UncertainTransactionDto): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) {
      return;
    }
    const reason = this.reasonFor(item.voucherId).trim();
    if (!reason) {
      this.conflictError.set('Mandatory audit reason is required before accepting a tax conflict.');
      return;
    }
    this.conflictError.set(null);
    await this.statutoryService.acceptUncertainConflict(companyId, item.voucherId, reason);
    this.uncertainItems.update((list) => list.filter((x) => x.voucherId !== item.voucherId));
    this.report.update((curr) =>
      curr ? { ...curr, uncertainCount: Math.max(0, curr.uncertainCount - 1) } : curr,
    );
  }

  drillToVoucher(voucherId: string): void {
    this.uncertainModalOpen.set(false);
    void this.router.navigate(['/app/vouchers', voucherId]);
  }
}
