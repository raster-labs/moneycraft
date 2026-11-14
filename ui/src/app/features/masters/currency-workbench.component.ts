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
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { AmountCellComponent } from '../../shared/components/amount-cell/amount-cell.component';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { TypeaheadSelectComponent } from '../../shared/components/typeahead-select/typeahead-select.component';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';
import { CompanyContextStore } from '../../state/company-context.store';
import { ChartOfAccountsStore } from './chart-of-accounts.store';
import { CurrencyMaster } from './masters.models';
import { SecondaryMasterModalService } from './secondary-master-modal.service';

@Component({
  selector: 'mc-currency-workbench',
  standalone: true,
  imports: [
    FormsModule,
    RouterLink,
    AmountCellComponent,
    CodeBadgeComponent,
    TypeaheadSelectComponent,
    GridCellNavDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-cur-layout">
      <div class="mc-cur-header">
        <div>
          <h1>Currencies &amp; Date-Effective Forex Rates</h1>
          <p class="mc-cur-sub">
            Configure ISO 4217 currencies and record date-effective Standard, Selling, and Buying
            exchange rates.
          </p>
        </div>
        <div class="mc-cur-tabs">
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'currency'"
            (click)="activeTab.set('currency')"
          >
            Currency Master
          </button>
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'rates'"
            (click)="openRatesTab()"
          >
            Rates of Exchange
          </button>
          <a routerLink="/app/masters" class="mc-btn">Masters Hub</a>
        </div>
      </div>

      @if (statusMessage(); as msg) {
        <div class="mc-cur-status" role="status">{{ msg }}</div>
      }
      @if (formError(); as err) {
        <div class="mc-field-error mc-cur-err" role="alert">{{ err }}</div>
      }

      @if (activeTab() === 'currency') {
        <div class="mc-cur-split">
          <form
            class="mc-card mc-cur-form-card"
            data-mc-nav-container
            (ngSubmit)="saveCurrency()"
          >
            <div class="mc-cur-card-head">
              <strong>{{ editingCurrency() ? 'Currency Alteration' : 'Currency Creation' }}</strong>
              @if (editingCurrency()?.isBaseCurrency) {
                <span class="mc-badge mc-badge-jade">Base Company Currency</span>
              }
              <button type="button" class="mc-btn" (click)="startCreateCurrency()">+ New</button>
            </div>

            <div class="mc-cur-grid-3">
              <div>
                <label class="mc-field-label" for="cur-iso">ISO Code (3 Letters)</label>
                <input
                  id="cur-iso"
                  type="text"
                  maxlength="3"
                  name="isoCode"
                  class="mc-input mc-code"
                  mcGridCellNav
                  [readOnly]="Boolean(editingCurrency()?.isBaseCurrency)"
                  [(ngModel)]="formIsoCode"
                  placeholder="USD"
                  required
                />
              </div>
              <div>
                <label class="mc-field-label" for="cur-symbol">Symbol</label>
                <input
                  id="cur-symbol"
                  type="text"
                  name="symbol"
                  class="mc-input"
                  mcGridCellNav
                  [(ngModel)]="formSymbol"
                  placeholder="$"
                  required
                />
              </div>
              <div>
                <label class="mc-field-label" for="cur-formal">Formal Name</label>
                <input
                  id="cur-formal"
                  type="text"
                  name="formalName"
                  class="mc-input"
                  mcGridCellNav
                  [(ngModel)]="formFormalName"
                  placeholder="US Dollar"
                  required
                />
              </div>
            </div>

            <div class="mc-cur-grid-2">
              <div>
                <label class="mc-field-label" for="cur-dec">Number of Decimal Places (0..6)</label>
                <input
                  id="cur-dec"
                  type="number"
                  min="0"
                  max="6"
                  name="decimalPlaces"
                  class="mc-input"
                  mcGridCellNav
                  [(ngModel)]="formDecimalPlaces"
                />
              </div>
              <div>
                <label class="mc-field-label" for="cur-decsym">
                  Word Representing Amount After Decimal
                </label>
                <input
                  id="cur-decsym"
                  type="text"
                  name="decimalSymbol"
                  class="mc-input"
                  mcGridCellNav
                  [(ngModel)]="formDecimalSymbol"
                  placeholder="cents / paise"
                />
              </div>
            </div>

            <div class="mc-cur-toggles">
              <label class="mc-cur-toggle">
                <input type="checkbox" name="millions" [(ngModel)]="formShowInMillions" />
                <span>Show amounts in millions (instead of lakhs/crores)</span>
              </label>
              <label class="mc-cur-toggle">
                <input type="checkbox" name="suffix" [(ngModel)]="formSuffixSymbol" />
                <span>Suffix symbol to amount</span>
              </label>
              <label class="mc-cur-toggle">
                <input type="checkbox" name="space" [(ngModel)]="formSpaceBetween" />
                <span>Add space between amount and symbol</span>
              </label>
            </div>

            <div class="mc-cur-footer">
              <span>Enter = Next Field</span>
              <button
                type="submit"
                class="mc-btn mc-btn-primary"
                [disabled]="saving() || companyContext.quarantined()"
              >
                Accept (<span class="mc-key-ctrl">A</span>)
              </button>
            </div>
          </form>

          <aside class="mc-card mc-cur-list-card" aria-label="Configured Currencies">
            <div class="mc-cur-list-head">Configured Currencies ({{ coaStore.currencies().length }})</div>
            <ul class="mc-cur-list">
              @for (cur of coaStore.currencies(); track cur.id) {
                <li
                  class="mc-cur-row"
                  [class.mc-cur-row-active]="editingCurrency()?.id === cur.id"
                  (click)="selectCurrencyForAlter(cur)"
                >
                  <div>
                    <strong>{{ cur.formalName }}</strong>
                    <span class="mc-cur-row-sym">({{ cur.symbol }} &bull; {{ cur.decimalPlaces }} dec)</span>
                  </div>
                  <mc-code-badge [code]="cur.isoCode" />
                </li>
              } @empty {
                <li class="mc-cur-empty">No foreign currencies added yet.</li>
              }
            </ul>
          </aside>
        </div>
      }

      @if (activeTab() === 'rates') {
        <div class="mc-cur-split">
          <form
            class="mc-card mc-cur-form-card"
            data-mc-nav-container
            (ngSubmit)="saveForexRate()"
          >
            <div class="mc-cur-card-head">
              <strong>Add Date-Effective Exchange Rate</strong>
            </div>

            <div class="mc-cur-grid-2">
              <div>
                <label class="mc-field-label" for="fx-currency">
                  Currency (<span class="mc-key-alt">C</span> to create Currency)
                </label>
                <mc-typeahead-select
                  inputId="fx-currency"
                  listTitle="List of Currencies"
                  [options]="coaStore.currencyTypeaheadOptions()"
                  [selectedId]="selectedRateCurrencyId()"
                  [allowInlineCreate]="true"
                  (optionSelected)="onRateCurrencySelected($event.id)"
                  (createRequested)="openInlineCurrencyCreator($event)"
                />
              </div>

              <div>
                <label class="mc-field-label" for="fx-date">Effective Date</label>
                <input
                  id="fx-date"
                  type="date"
                  name="effectiveDate"
                  class="mc-input"
                  mcGridCellNav
                  [(ngModel)]="rateEffectiveDate"
                  required
                />
              </div>
            </div>

            <div class="mc-cur-grid-3">
              <div>
                <label class="mc-field-label" for="fx-std">Standard Rate (Base per 1 Unit)</label>
                <mc-amount-cell
                  inputId="fx-std"
                  name="standardRate"
                  [scale]="4"
                  [currencySymbol]="companyContext.currencySymbol()"
                  [(ngModel)]="rateStandard"
                />
              </div>
              <div>
                <label class="mc-field-label" for="fx-sell">Selling Rate (Export / Receivable)</label>
                <mc-amount-cell
                  inputId="fx-sell"
                  name="sellingRate"
                  [scale]="4"
                  [currencySymbol]="companyContext.currencySymbol()"
                  [(ngModel)]="rateSelling"
                />
              </div>
              <div>
                <label class="mc-field-label" for="fx-buy">Buying Rate (Import / Payable)</label>
                <mc-amount-cell
                  inputId="fx-buy"
                  name="buyingRate"
                  [scale]="4"
                  [currencySymbol]="companyContext.currencySymbol()"
                  [(ngModel)]="rateBuying"
                />
              </div>
            </div>

            <div class="mc-cur-footer">
              <span>Exact 4-decimal BigInt rate valuation</span>
              <button
                type="submit"
                class="mc-btn mc-btn-primary"
                [disabled]="saving() || companyContext.quarantined()"
              >
                Accept Rate (<span class="mc-key-ctrl">A</span>)
              </button>
            </div>
          </form>

          <aside class="mc-card mc-cur-list-card" aria-label="Forex Rate History">
            <div class="mc-cur-list-head">Exchange Rate History</div>
            <table class="mc-table">
              <thead>
                <tr>
                  <th>Date</th>
                  <th class="mc-col-right">Std Rate</th>
                  <th class="mc-col-right">Selling</th>
                  <th class="mc-col-right">Buying</th>
                </tr>
              </thead>
              <tbody>
                @for (fx of selectedCurrencyRates(); track fx.id) {
                  <tr>
                    <td><mc-code-badge [code]="fx.effectiveDate" /></td>
                    <td class="mc-amount">{{ fx.standardRate }}</td>
                    <td class="mc-amount">{{ fx.sellingRate ?? '—' }}</td>
                    <td class="mc-amount">{{ fx.buyingRate ?? '—' }}</td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="4" class="mc-cur-empty">No rates recorded for selected currency.</td>
                  </tr>
                }
              </tbody>
            </table>
          </aside>
        </div>
      }
    </div>
  `,
  styles: `
    .mc-cur-layout {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-cur-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: var(--mc-space-3);
      flex-wrap: wrap;

      h1 {
        margin: 0;
        font-size: var(--mc-font-size-lg);
      }
    }

    .mc-cur-sub {
      margin: 4px 0 0;
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
    }

    .mc-cur-tabs {
      display: flex;
      gap: var(--mc-space-2);
    }

    .mc-cur-status {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-teal-50);
      border-left: 3px solid var(--mc-color-verdigris-teal);
      color: var(--mc-color-teal-700);
      font-weight: 600;
      font-size: var(--mc-font-size-xs);
    }

    .mc-cur-err {
      padding: var(--mc-space-2);
      background: var(--mc-color-danger-50);
      border-left: 3px solid var(--mc-color-danger-600);
    }

    .mc-cur-split {
      display: grid;
      grid-template-columns: minmax(0, 1.4fr) minmax(280px, 0.9fr);
      gap: var(--mc-space-4);
      align-items: start;
    }

    @media (max-width: 960px) {
      .mc-cur-split {
        grid-template-columns: 1fr;
      }
    }

    .mc-cur-form-card {
      padding: var(--mc-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-cur-card-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-bottom: var(--mc-space-2);
      border-bottom: var(--mc-border-subtle);
    }

    .mc-cur-grid-2 {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    .mc-cur-grid-3 {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    .mc-cur-toggles {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-cur-toggle {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      font-weight: 500;
      cursor: pointer;
    }

    .mc-cur-footer {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-top: var(--mc-space-2);
      border-top: var(--mc-border-subtle);
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-500);
    }

    .mc-cur-list-card {
      overflow: hidden;
    }

    .mc-cur-list-head {
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border-bottom: var(--mc-border-subtle);
      font-weight: 700;
    }

    .mc-cur-list {
      list-style: none;
      margin: 0;
      padding: 0;
    }

    .mc-cur-row {
      padding: 0.5rem 0.75rem;
      border-bottom: 1px solid var(--mc-color-slate-100);
      display: flex;
      justify-content: space-between;
      align-items: center;
      cursor: pointer;
    }

    .mc-cur-row-active {
      background: var(--mc-color-teal-50);
    }

    .mc-cur-row-sym {
      margin-left: 6px;
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
    }

    .mc-cur-empty {
      padding: var(--mc-space-4);
      text-align: center;
      color: var(--mc-color-slate-500);
    }
  `,
})
export class CurrencyWorkbenchComponent implements OnInit {
  protected readonly Boolean = Boolean;

  readonly coaStore = inject(ChartOfAccountsStore);
  readonly companyContext = inject(CompanyContextStore);

  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly secondaryMasterModal = inject(SecondaryMasterModalService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  readonly embeddedOverlay = input<boolean>(false);

  readonly activeTab = signal<'currency' | 'rates'>('currency');
  readonly editingCurrency = signal<CurrencyMaster | null>(null);
  readonly saving = signal<boolean>(false);
  readonly statusMessage = signal<string | null>(null);
  readonly formError = signal<string | null>(null);

  formIsoCode = 'USD';
  formSymbol = '$';
  formFormalName = 'US Dollar';
  formDecimalPlaces = 2;
  formDecimalSymbol = 'cents';
  formShowInMillions = true;
  formSuffixSymbol = false;
  formSpaceBetween = true;

  readonly selectedRateCurrencyId = signal<string | null>(null);
  rateEffectiveDate = '2025-04-01';
  rateStandard = '85.5000';
  rateSelling = '85.2500';
  rateBuying = '85.7500';

  readonly selectedCurrencyRates = computed(() => {
    const cid = this.selectedRateCurrencyId();
    if (!cid) return [];
    return this.coaStore.forexRatesByCurrency().get(cid) ?? [];
  });

  async ngOnInit(): Promise<void> {
    const scope = this.embeddedOverlay() ? 'MODAL' : 'SCREEN';
    const disposeAccept = this.shortcutRegistry.bindHandler(
      'screen.accept',
      scope,
      () => {
        if (this.activeTab() === 'rates') {
          void this.saveForexRate();
        } else {
          void this.saveCurrency();
        }
      },
      () => !this.secondaryMasterModal.isOpen(),
    );
    this.destroyRef.onDestroy(disposeAccept);

    const tab = this.route.snapshot.queryParamMap.get('tab');
    if (tab === 'rates') {
      this.activeTab.set('rates');
    }

    this.rateEffectiveDate = this.companyContext.workingDate();
    const list = await this.coaStore.loadCurrencies().catch(() => []);
    const firstNonBase = list.find((c) => !c.isBaseCurrency) ?? list[0];
    if (firstNonBase) {
      this.selectedRateCurrencyId.set(firstNonBase.id);
      if (this.activeTab() === 'rates') {
        void this.coaStore.loadForexRates(firstNonBase.id).catch(() => []);
      }
    }
  }

  openRatesTab(): void {
    this.activeTab.set('rates');
    const cid = this.selectedRateCurrencyId() ?? this.coaStore.currencies()[0]?.id ?? null;
    if (cid) {
      this.onRateCurrencySelected(cid);
    }
  }

  onRateCurrencySelected(currencyId: string): void {
    this.selectedRateCurrencyId.set(currencyId);
    void this.coaStore.loadForexRates(currencyId).catch(() => []);
  }

  openInlineCurrencyCreator(seed: string): void {
    void this.secondaryMasterModal.open({
      kind: 'CURRENCY',
      initialName: seed,
      onCreated: (created) => {
        this.onRateCurrencySelected(created.id);
      },
    });
  }

  startCreateCurrency(): void {
    this.editingCurrency.set(null);
    this.formError.set(null);
    this.formIsoCode = '';
    this.formSymbol = '';
    this.formFormalName = '';
    this.formDecimalPlaces = 2;
    this.formDecimalSymbol = 'cents';
    this.formShowInMillions = true;
    this.formSuffixSymbol = false;
    this.formSpaceBetween = true;
  }

  selectCurrencyForAlter(cur: CurrencyMaster): void {
    this.editingCurrency.set(cur);
    this.formError.set(null);
    this.formIsoCode = cur.isoCode;
    this.formSymbol = cur.symbol;
    this.formFormalName = cur.formalName;
    this.formDecimalPlaces = cur.decimalPlaces;
    this.formDecimalSymbol = cur.decimalSymbol ?? '';
    this.formShowInMillions = cur.showAmountInMillions;
    this.formSuffixSymbol = cur.suffixSymbolToAmount;
    this.formSpaceBetween = cur.addSpaceBetweenAmountAndSymbol;
  }

  async saveCurrency(): Promise<void> {
    if (this.saving()) return;
    this.formError.set(null);
    const iso = this.formIsoCode.trim().toUpperCase();
    if (iso.length !== 3 || !this.formFormalName.trim()) {
      this.formError.set('3-letter ISO Code and Formal Name are required.');
      return;
    }

    this.saving.set(true);
    try {
      const payload = {
        isoCode: iso,
        symbol: this.formSymbol.trim() || iso,
        formalName: this.formFormalName.trim(),
        decimalPlaces: Math.min(6, Math.max(0, Math.trunc(this.formDecimalPlaces))),
        decimalSymbol: this.formDecimalSymbol.trim() || null,
        showAmountInMillions: this.formShowInMillions,
        suffixSymbolToAmount: this.formSuffixSymbol,
        addSpaceBetweenAmountAndSymbol: this.formSpaceBetween,
      };
      const editing = this.editingCurrency();
      if (editing) {
        const updated = await this.coaStore.updateCurrency(editing.id, payload, editing.versionNo);
        this.selectCurrencyForAlter(updated);
        this.statusMessage.set(`Updated currency ${updated.isoCode}.`);
      } else {
        const created = await this.coaStore.createCurrency(payload);
        this.selectedRateCurrencyId.set(created.id);
        this.startCreateCurrency();
        this.statusMessage.set(`Created currency ${created.isoCode} (${created.formalName}).`);
      }
    } catch (err: unknown) {
      this.formError.set(err instanceof Error ? err.message : 'Failed to save Currency.');
    } finally {
      this.saving.set(false);
    }
  }

  async saveForexRate(): Promise<void> {
    if (this.saving()) return;
    const cid = this.selectedRateCurrencyId();
    if (!cid) {
      this.formError.set('Select a target Currency first.');
      return;
    }
    this.formError.set(null);
    this.saving.set(true);
    try {
      await this.coaStore.addForexRate(cid, {
        effectiveDate: this.rateEffectiveDate,
        standardRate: this.rateStandard,
        sellingRate: this.rateSelling || null,
        buyingRate: this.rateBuying || null,
      });
      this.statusMessage.set(`Saved exchange rate for ${this.rateEffectiveDate}.`);
    } catch (err: unknown) {
      this.formError.set(err instanceof Error ? err.message : 'Failed to save Forex Rate.');
    } finally {
      this.saving.set(false);
    }
  }
}
