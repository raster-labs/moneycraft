import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { CompanyContextStore } from '../../state/company-context.store';
import { EinvoiceGenerateResponse, EwayBillGenerateResponse } from './statutory.models';
import { StatutoryService } from './statutory.service';

@Component({
  selector: 'mc-einvoice-workbench',
  standalone: true,
  imports: [FormsModule, RouterLink, CodeBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="einv-page">
      <header class="mc-card einv-header">
        <div>
          <span class="return-pill">NIC IRP &amp; e-Way Bill &bull; Air-Gapped Safe SPI</span>
          <h1>e-Invoice (IRN) &amp; e-Way Bill Exchange Workbench</h1>
          <p class="subtitle">
            Offline Bulk JSON Export enabled by default &bull; Direct NIC/GSP connector gated behind
            explicit Outbound Connector SPI allowlist
          </p>
        </div>

        <div class="bulk-actions">
          <button
            type="button"
            class="mc-btn mc-btn-primary"
            data-testid="einvoice-offline-export-btn"
            (click)="exportOfflineEinvoice()"
          >
            Export Offline e-Invoice JSON
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="ewaybill-offline-export-btn"
            (click)="exportOfflineEwayBill()"
          >
            Export Offline e-Way Bill JSON
          </button>
          <a routerLink="/app/statutory/gstr1" class="mc-btn">GSTR-1</a>
        </div>
      </header>

      @if (statusMessage(); as msg) {
        <div class="status-banner" [class.status-err]="statusIsError()" role="status">
          {{ msg }}
        </div>
      }

      <div class="two-col">
        <!-- e-Invoice IRN Generation & 24h Cancellation -->
        <section class="mc-card panel-card">
          <header class="card-subhead">
            <h2>e-Invoice IRN Generation &amp; Cancellation</h2>
          </header>

          <div class="form-stack">
            <div class="field-group">
              <label for="einv-voucher-id">Voucher ID / Outward Invoice Reference</label>
              <input
                id="einv-voucher-id"
                type="text"
                class="mc-input mc-code"
                data-testid="einvoice-voucher-id-input"
                [ngModel]="voucherId()"
                (ngModelChange)="voucherId.set($event)"
                placeholder="vch-sales-001"
              />
            </div>

            <div class="btn-row">
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="generate-irn-btn"
                (click)="generateIrn()"
              >
                Generate IRN (NIC/GSP)
              </button>
              <button
                type="button"
                class="mc-btn"
                (click)="showCancelBox.set(!showCancelBox())"
              >
                Cancel IRN (Within 24h)
              </button>
            </div>

            @if (showCancelBox()) {
              <div class="cancel-box">
                <div class="field-group">
                  <label for="cancel-reason">NIC Cancel Reason Code</label>
                  <select
                    id="cancel-reason"
                    class="mc-input"
                    [ngModel]="cancelReasonCode()"
                    (ngModelChange)="cancelReasonCode.set($event)"
                  >
                    <option value="1">1 — Duplicate</option>
                    <option value="2">2 — Data Entry Mistake</option>
                    <option value="3">3 — Order Cancelled</option>
                    <option value="4">4 — Others</option>
                  </select>
                </div>
                <div class="field-group">
                  <label for="cancel-remarks">Cancellation Remarks</label>
                  <input
                    id="cancel-remarks"
                    type="text"
                    class="mc-input"
                    [ngModel]="cancelRemarks()"
                    (ngModelChange)="cancelRemarks.set($event)"
                    placeholder="Mandatory NIC remarks..."
                  />
                </div>
                <button
                  type="button"
                  class="mc-btn mc-btn-primary"
                  data-testid="confirm-cancel-irn-btn"
                  (click)="cancelIrn()"
                >
                  Submit IRN Cancellation
                </button>
              </div>
            }

            @if (latestIrnResult(); as irnRes) {
              <div class="result-card" data-testid="einvoice-irn-result">
                <div class="res-row">
                  <span>Status:</span>
                  <strong>{{ irnRes.status }}</strong>
                </div>
                <div class="res-row">
                  <span>Ack No &amp; Date:</span>
                  <span>
                    <mc-code-badge [code]="irnRes.ackNo" /> &bull; {{ irnRes.ackDate }}
                  </span>
                </div>
                <div class="res-col">
                  <span>64-Character SHA-256 IRN:</span>
                  <div class="mc-code irn-hash" data-testid="einvoice-irn-code">
                    {{ irnRes.irn }}
                  </div>
                </div>
              </div>
            }
          </div>
        </section>

        <!-- e-Way Bill Part A + Part B -->
        <section class="mc-card panel-card">
          <header class="card-subhead">
            <h2>e-Way Bill (Part A + Part B Transport Details)</h2>
          </header>

          <div class="form-stack">
            <div class="two-col-inner">
              <div class="field-group">
                <label for="ewb-mode">Transport Mode</label>
                <select
                  id="ewb-mode"
                  class="mc-input"
                  [ngModel]="transportMode()"
                  (ngModelChange)="transportMode.set($event)"
                >
                  <option value="ROAD">Road</option>
                  <option value="RAIL">Rail</option>
                  <option value="AIR">Air</option>
                  <option value="SHIP">Ship</option>
                </select>
              </div>

              <div class="field-group">
                <label for="ewb-dist">Distance (KM)</label>
                <input
                  id="ewb-dist"
                  type="number"
                  class="mc-input mc-amount"
                  [ngModel]="distanceKm()"
                  (ngModelChange)="distanceKm.set(+$event)"
                />
              </div>
            </div>

            <div class="two-col-inner">
              <div class="field-group">
                <label for="ewb-vehicle">Vehicle No (Part B)</label>
                <input
                  id="ewb-vehicle"
                  type="text"
                  class="mc-input mc-code"
                  [ngModel]="vehicleNo()"
                  (ngModelChange)="vehicleNo.set($event.toUpperCase())"
                  placeholder="TN01AB1234"
                />
              </div>

              <div class="field-group">
                <label for="ewb-transporter">Transporter ID (GSTIN/TRANSIN)</label>
                <input
                  id="ewb-transporter"
                  type="text"
                  class="mc-input mc-code"
                  [ngModel]="transporterId()"
                  (ngModelChange)="transporterId.set($event.toUpperCase())"
                  placeholder="33AABCT9999K1Z5"
                />
              </div>
            </div>

            <div class="btn-row">
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="generate-ewb-btn"
                (click)="generateEwb()"
              >
                Generate e-Way Bill
              </button>
            </div>

            @if (latestEwbResult(); as ewb) {
              <div class="result-card" data-testid="ewaybill-result">
                <div class="res-row">
                  <span>e-Way Bill No:</span>
                  <mc-code-badge [code]="ewb.ewbNo" />
                </div>
                <div class="res-row">
                  <span>Generated / Valid Upto:</span>
                  <span>{{ ewb.ewbDate }} &#x2192; {{ ewb.ewbValidUpto }}</span>
                </div>
              </div>
            }
          </div>
        </section>
      </div>
    </div>
  `,
  styles: `
    .einv-page {
      display: flex;
      flex-direction: column;
      gap: 0.85rem;
    }

    .einv-header {
      padding: 0.85rem 1rem;
      background: #142f42;
      color: #ffffff;
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 0.75rem;
    }

    .einv-header h1 {
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

    .bulk-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
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

    .status-err {
      background: #fdecec;
      border-color: #d64545;
      color: #8f1d1d;
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

    .panel-card {
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

    .form-stack {
      padding: 0.9rem 1rem;
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }

    .two-col-inner {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.65rem;
    }

    .field-group {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }

    .field-group label {
      font-size: 0.75rem;
      font-weight: 600;
      color: #142f42;
    }

    .btn-row {
      display: flex;
      gap: 0.5rem;
    }

    .cancel-box {
      padding: 0.75rem;
      background: #fff8ee;
      border: 1px solid #e3b363;
      border-radius: 6px;
      display: flex;
      flex-direction: column;
      gap: 0.55rem;
    }

    .result-card {
      padding: 0.75rem;
      background: #f6faf9;
      border: 1px solid #b9cfcc;
      border-radius: 6px;
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
      font-size: 0.8rem;
    }

    .res-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 0.5rem;
    }

    .res-col {
      display: flex;
      flex-direction: column;
      gap: 0.2rem;
    }

    .irn-hash {
      padding: 0.4rem 0.55rem;
      background: #ffffff;
      border: 1px solid #d4e2e0;
      border-radius: 4px;
      word-break: break-all;
      font-size: 0.76rem;
      color: #142f42;
    }
  `,
})
export class EinvoiceWorkbenchComponent {
  private readonly statutoryService = inject(StatutoryService);
  private readonly companyContext = inject(CompanyContextStore);

  readonly voucherId = signal<string>('');
  readonly showCancelBox = signal<boolean>(false);
  readonly cancelReasonCode = signal<string>('2');
  readonly cancelRemarks = signal<string>('');

  readonly transportMode = signal<'ROAD' | 'RAIL' | 'AIR' | 'SHIP'>('ROAD');
  readonly distanceKm = signal<number>(45);
  readonly vehicleNo = signal<string>('');
  readonly transporterId = signal<string>('');

  readonly latestIrnResult = signal<EinvoiceGenerateResponse | null>(null);
  readonly latestEwbResult = signal<EwayBillGenerateResponse | null>(null);
  readonly statusMessage = signal<string | null>(null);
  readonly statusIsError = signal<boolean>(false);

  async generateIrn(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    const vId = this.voucherId().trim();
    if (!companyId || !vId) {
      this.statusIsError.set(true);
      this.statusMessage.set('Enter a valid Outward Voucher ID to generate an e-Invoice IRN.');
      return;
    }
    try {
      const res = await this.statutoryService.generateEinvoice(companyId, vId);
      this.latestIrnResult.set(res);
      this.statusIsError.set(false);
      this.statusMessage.set(`Generated 64-character IRN (Ack No: ${res.ackNo}).`);
    } catch {
      this.statusIsError.set(true);
      this.statusMessage.set(
        'NIC/GSP Outbound Connector is disabled in air-gapped mode. Use Export Offline e-Invoice JSON.',
      );
    }
  }

  async cancelIrn(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    const vId = this.voucherId().trim();
    const remarks = this.cancelRemarks().trim();
    if (!companyId || !vId || !remarks) {
      this.statusIsError.set(true);
      this.statusMessage.set('Voucher ID and NIC cancellation remarks are mandatory.');
      return;
    }
    try {
      const res = await this.statutoryService.cancelEinvoice(companyId, vId, {
        cancelReasonCode: this.cancelReasonCode(),
        cancelRemarks: remarks,
      });
      this.latestIrnResult.set(res);
      this.statusIsError.set(false);
      this.statusMessage.set(`Cancelled IRN for voucher ${vId}.`);
    } catch {
      this.statusIsError.set(true);
      this.statusMessage.set('Unable to cancel IRN (check 24-hour window or connector state).');
    }
  }

  async generateEwb(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    const vId = this.voucherId().trim();
    if (!companyId || !vId) {
      this.statusIsError.set(true);
      this.statusMessage.set('Enter a valid Voucher ID before generating an e-Way Bill.');
      return;
    }
    try {
      const res = await this.statutoryService.generateEwayBill(companyId, vId, {
        transportMode: this.transportMode(),
        distanceKm: this.distanceKm(),
        vehicleNo: this.vehicleNo().trim() || null,
        transporterId: this.transporterId().trim() || null,
      });
      this.latestEwbResult.set(res);
      this.statusIsError.set(false);
      this.statusMessage.set(`Generated e-Way Bill ${res.ewbNo}.`);
    } catch {
      this.statusIsError.set(true);
      this.statusMessage.set(
        'e-Way Bill connector is offline in air-gapped mode. Use Export Offline e-Way Bill JSON.',
      );
    }
  }

  async exportOfflineEinvoice(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) {
      return;
    }
    const ids = this.voucherId().trim() ? [this.voucherId().trim()] : [];
    await this.statutoryService.exportEinvoiceOfflineJson(companyId, ids);
    this.statusIsError.set(false);
    this.statusMessage.set('Exported NIC Schema 1.1 Offline Bulk e-Invoice JSON.');
  }

  async exportOfflineEwayBill(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) {
      return;
    }
    const ids = this.voucherId().trim() ? [this.voucherId().trim()] : [];
    await this.statutoryService.exportEwayBillOfflineJson(companyId, ids);
    this.statusIsError.set(false);
    this.statusMessage.set('Exported NIC Offline Bulk e-Way Bill JSON.');
  }
}
