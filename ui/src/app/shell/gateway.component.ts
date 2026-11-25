import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { isEditableTextElement, isStrictInputOwnerElement } from '../core/keyboard/collision-guard';
import { ShortcutCommandRegistry } from '../core/keyboard/shortcut-command.registry';
import { IndianCurrencyPipe } from '../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../shared/components/code-badge/code-badge.component';
import { SemanticIconComponent } from '../shared/components/semantic-icon/semantic-icon.component';
import { CompanyContextStore, CompanyFeaturesConfig } from '../state/company-context.store';
import { NavigationStackStore } from '../state/navigation-stack.store';

export interface GatewayMenuNode {
  readonly key: string;
  readonly label: string;
  readonly section?: string;
  readonly route?: string;
  readonly requiredFeature?: keyof CompanyFeaturesConfig;
  readonly requiredFeatureLabel?: string;
  readonly showMoreOnly?: boolean;
  readonly children?: readonly GatewayMenuNode[];
}

export interface GatewayMenuItem {
  readonly key: string;
  readonly section: 'MASTERS' | 'TRANSACTIONS' | 'UTILITIES' | 'REPORTS';
  readonly prefixText: string;
  readonly mnemonicChar: string;
  readonly suffixText: string;
  readonly panelId: string;
  readonly directRoute?: string;
  readonly requiredFeature?: keyof CompanyFeaturesConfig;
  readonly requiredFeatureLabel?: string;
  readonly children?: readonly GatewayMenuNode[];
}

function buildMasterSubTree(mode: 'create' | 'alter'): readonly GatewayMenuNode[] {
  return [
    {
      key: 'A',
      label: 'Accounting Masters',
      section: 'ACCOUNTING',
      children: [
        { key: 'G', label: 'Group', route: `/app/masters/groups?mode=${mode}` },
        { key: 'L', label: 'Ledger', route: `/app/masters/ledgers?mode=${mode}` },
        { key: 'C', label: 'Currency', route: `/app/masters/currencies?mode=${mode}` },
        {
          key: 'R',
          label: 'Rates of Exchange',
          route: `/app/masters/currencies?mode=${mode}&tab=rates`,
          showMoreOnly: true,
        },
        {
          key: 'Y',
          label: 'Cost Category',
          route: `/app/masters/cost-centres?mode=${mode}&tab=categories`,
          requiredFeature: 'costCentresEnabled',
          requiredFeatureLabel: 'Enable Cost Centres',
        },
        {
          key: 'O',
          label: 'Cost Centre',
          route: `/app/masters/cost-centres?mode=${mode}`,
          requiredFeature: 'costCentresEnabled',
          requiredFeatureLabel: 'Enable Cost Centres',
        },
        {
          key: 'K',
          label: 'Cost Centre Class',
          route: `/app/masters/cost-centres?mode=${mode}`,
          requiredFeature: 'costCentresEnabled',
          requiredFeatureLabel: 'Enable Cost Centres',
          showMoreOnly: true,
        },
        {
          key: 'B',
          label: 'Budget',
          route: `/app/masters/groups?mode=${mode}`,
          showMoreOnly: true,
        },
        {
          key: 'N',
          label: 'Scenario',
          route: `/app/masters/voucher-types?mode=${mode}`,
          showMoreOnly: true,
        },
        { key: 'V', label: 'Voucher Type', route: `/app/masters/voucher-types?mode=${mode}` },
        {
          key: 'E',
          label: 'Credit Limits',
          route: `/app/masters/ledgers?mode=${mode}`,
          showMoreOnly: true,
        },
      ],
    },
    {
      key: 'I',
      label: 'Inventory Masters',
      section: 'INVENTORY',
      requiredFeature: 'maintainInventory',
      requiredFeatureLabel: 'Maintain Inventory',
      children: [
        {
          key: 'G',
          label: 'Stock Group',
          route: `/app/inventory/stock-groups?mode=${mode}`,
          requiredFeature: 'maintainInventory',
          requiredFeatureLabel: 'Maintain Inventory',
        },
        {
          key: 'C',
          label: 'Stock Category',
          route: `/app/inventory/stock-groups?mode=${mode}&tab=categories`,
          requiredFeature: 'maintainInventory',
          requiredFeatureLabel: 'Maintain Inventory',
          showMoreOnly: true,
        },
        {
          key: 'I',
          label: 'Stock Item & BOM',
          route: `/app/inventory/stock-items?mode=${mode}`,
          requiredFeature: 'maintainInventory',
          requiredFeatureLabel: 'Maintain Inventory',
        },
        {
          key: 'U',
          label: 'Unit (UOM)',
          route: `/app/inventory/uoms?mode=${mode}`,
          requiredFeature: 'maintainInventory',
          requiredFeatureLabel: 'Maintain Inventory',
        },
        {
          key: 'D',
          label: 'Godown / Location',
          route: `/app/inventory/godowns?mode=${mode}`,
          requiredFeature: 'maintainInventory',
          requiredFeatureLabel: 'Maintain Inventory',
        },
        {
          key: 'L',
          label: 'Price Levels',
          route: `/app/inventory/price-lists?mode=${mode}`,
          requiredFeature: 'multiplePriceLevelsEnabled',
          requiredFeatureLabel: 'Enable multiple Price Levels',
        },
        {
          key: 'P',
          label: 'Price List',
          route: `/app/inventory/price-lists?mode=${mode}`,
          requiredFeature: 'multiplePriceLevelsEnabled',
          requiredFeatureLabel: 'Enable multiple Price Levels',
        },
      ],
    },
    {
      key: 'P',
      label: 'Payroll Masters',
      section: 'PAYROLL',
      requiredFeature: 'payrollEnabled',
      requiredFeatureLabel: 'Maintain Payroll',
      children: [
        {
          key: 'G',
          label: 'Employee Group',
          route: `/app/masters/cost-centres?mode=${mode}`,
          requiredFeature: 'payrollEnabled',
          requiredFeatureLabel: 'Maintain Payroll',
        },
        {
          key: 'E',
          label: 'Employee',
          route: `/app/masters/cost-centres?mode=${mode}`,
          requiredFeature: 'payrollEnabled',
          requiredFeatureLabel: 'Maintain Payroll',
        },
        {
          key: 'U',
          label: 'Units (Work)',
          route: `/app/inventory/uoms?mode=${mode}`,
          requiredFeature: 'payrollEnabled',
          requiredFeatureLabel: 'Maintain Payroll',
        },
        {
          key: 'A',
          label: 'Attendance / Production Type',
          route: `/app/masters/voucher-types?mode=${mode}`,
          requiredFeature: 'payrollEnabled',
          requiredFeatureLabel: 'Maintain Payroll',
        },
        {
          key: 'H',
          label: 'Pay Heads',
          route: `/app/masters/ledgers?mode=${mode}`,
          requiredFeature: 'payrollEnabled',
          requiredFeatureLabel: 'Maintain Payroll',
        },
        {
          key: 'V',
          label: 'Payroll Voucher Type',
          route: `/app/masters/voucher-types?mode=${mode}`,
          requiredFeature: 'payrollEnabled',
          requiredFeatureLabel: 'Maintain Payroll',
        },
      ],
    },
    {
      key: 'S',
      label: 'Statutory Masters & Details',
      section: 'STATUTORY',
      children: [
        {
          key: 'G',
          label: 'GST Registrations (Multi-GSTIN)',
          route: '/app/statutory/gst-registrations',
          requiredFeature: 'gstEnabled',
          requiredFeatureLabel: 'Enable Goods and Services Tax (GST)',
        },
        {
          key: 'C',
          label: 'GST Classification',
          route: '/app/statutory/gst-registrations',
          requiredFeature: 'gstEnabled',
          requiredFeatureLabel: 'Enable Goods and Services Tax (GST)',
          showMoreOnly: true,
        },
        {
          key: 'D',
          label: 'Company GST & F11 Details',
          route: '/app/companies?mode=features',
        },
        {
          key: 'T',
          label: 'TDS Nature of Payment (26Q)',
          route: '/app/statutory/tds-26q',
          requiredFeature: 'tdsEnabled',
          requiredFeatureLabel: 'Enable Tax Deducted at Source (TDS)',
        },
        {
          key: 'O',
          label: 'TCS Nature of Goods (27EQ)',
          route: '/app/statutory/tds-26q',
          requiredFeature: 'tcsEnabled',
          requiredFeatureLabel: 'Enable Tax Collected at Source (TCS)',
        },
        {
          key: 'P',
          label: 'Company PAN / Profile',
          route: '/app/companies?mode=alter',
        },
        {
          key: 'A',
          label: 'Multiple Addresses',
          route: '/app/companies?mode=alter',
          requiredFeature: 'multipleAddressesEnabled',
          requiredFeatureLabel: 'Enable multiple addresses',
        },
      ],
    },
    {
      key: 'M',
      label: mode === 'create' ? 'Open Unified Master Creation Hub' : 'Open Unified Master Alteration Hub',
      section: 'ALL MASTERS',
      route: `/app/masters?mode=${mode}`,
    },
  ];
}

const STATUTORY_SUB_MENU: readonly GatewayMenuNode[] = [
  { key: '1', label: 'GSTR-1 Outward Supplies', section: 'GST REPORTS', route: '/app/statutory/gstr1' },
  { key: '3', label: 'GSTR-3B Monthly Summary', section: 'GST REPORTS', route: '/app/statutory/gstr3b' },
  { key: '9', label: 'GSTR-9 Annual Return', section: 'GST REPORTS', route: '/app/statutory/gstr9' },
  { key: 'I', label: 'IMS Inward Invoice Workbench', section: 'GST REPORTS', route: '/app/statutory/ims' },
  { key: 'E', label: 'e-Invoice & IRN Register', section: 'GST REPORTS', route: '/app/statutory/einvoice' },
  { key: 'T', label: 'Form 26Q Quarterly TDS', section: 'TDS REPORTS', route: '/app/statutory/tds-26q' },
  { key: 'G', label: 'GST Registrations (Multi-GSTIN)', section: 'REGISTRATIONS', route: '/app/statutory/gst-registrations' },
];

const DISPLAY_MORE_REPORTS_TREE: readonly GatewayMenuNode[] = [
  { key: 'T', label: 'Trial Balance', section: 'ACCOUNTING', route: '/app/reports/trial-balance' },
  { key: 'D', label: 'Day Book', section: 'ACCOUNTING', route: '/app/daybook' },
  { key: 'C', label: 'Cash Flow', section: 'ACCOUNTING', route: '/app/reports/cash-flow' },
  { key: 'F', label: 'Funds Flow / MCA Schedule III', section: 'ACCOUNTING', route: '/app/reports/schedule-iii' },
  {
    key: 'A',
    label: 'Account Books',
    section: 'BOOKS & REGISTERS',
    children: [
      { key: 'C', label: 'Cash / Bank Book(s)', route: '/app/reports/account-books?bookType=CASH_BANK_SUMMARY' },
      { key: 'L', label: 'Ledger Vouchers', route: '/app/daybook' },
      { key: 'G', label: 'Group Summary', route: '/app/reports/account-books?bookType=GROUP_SUMMARY' },
      { key: 'V', label: 'Group Vouchers', route: '/app/reports/account-books?bookType=GROUP_VOUCHERS' },
      { key: 'O', label: 'Contra Register', route: '/app/reports/account-books?bookType=CONTRA_REGISTER' },
      { key: 'P', label: 'Payment Register', route: '/app/reports/account-books?bookType=PAYMENT_REGISTER' },
      { key: 'R', label: 'Receipt Register', route: '/app/reports/account-books?bookType=RECEIPT_REGISTER' },
      { key: 'S', label: 'Sales Register', route: '/app/reports/account-books?bookType=SALES_REGISTER' },
      { key: 'U', label: 'Purchase Register', route: '/app/reports/account-books?bookType=PURCHASE_REGISTER' },
      { key: 'J', label: 'Journal Register', route: '/app/reports/account-books?bookType=JOURNAL_REGISTER' },
      { key: 'D', label: 'Debit Note Register', route: '/app/reports/account-books?bookType=DEBIT_NOTE_REGISTER' },
      { key: 'N', label: 'Credit Note Register', route: '/app/reports/account-books?bookType=CREDIT_NOTE_REGISTER' },
    ],
  },
  {
    key: 'S',
    label: 'Statements of Accounts',
    section: 'BOOKS & REGISTERS',
    children: [
      { key: 'R', label: 'Bills Receivable', route: '/app/reports/bills-receivable' },
      { key: 'P', label: 'Bills Payable', route: '/app/reports/bills-payable' },
      { key: 'A', label: 'Outstandings Ageing Analysis', route: '/app/reports/ageing' },
      { key: 'C', label: 'Cost Centres Summary', route: '/app/masters/cost-centres' },
      { key: 'T', label: 'Statistics (Vouchers & Masters)', route: '/app/reports/statistics' },
    ],
  },
  {
    key: 'I',
    label: 'Inventory Books',
    section: 'INVENTORY',
    children: [
      { key: 'S', label: 'Stock Item Summary', route: '/app/reports/stock-summary' },
      { key: 'G', label: 'Stock Group Summary', route: '/app/inventory/stock-groups' },
      { key: 'B', label: 'Batch & Expiry Ageing', route: '/app/reports/batch-ageing' },
      { key: 'O', label: 'Sales & Purchase Order Outstandings', route: '/app/reports/open-orders' },
    ],
  },
  {
    key: 'E',
    label: 'Statements of Inventory',
    section: 'INVENTORY',
    children: [
      { key: 'G', label: 'Godown / Location Summary', route: '/app/reports/godown-summary' },
      { key: 'Q', label: 'Stock Query (360° Item View)', route: '/app/reports/stock-query' },
      { key: 'B', label: 'BOM & Cost Estimation', route: '/app/inventory/stock-items' },
    ],
  },
  {
    key: 'O',
    label: 'Statutory Reports',
    section: 'STATUTORY & EXCEPTIONS',
    children: STATUTORY_SUB_MENU,
  },
  {
    key: 'X',
    label: 'Exception Reports',
    section: 'STATUTORY & EXCEPTIONS',
    children: [
      { key: 'S', label: 'Negative Stock', route: '/app/reports/exceptions?type=NEGATIVE_STOCK' },
      { key: 'L', label: 'Negative Ledgers', route: '/app/reports/exceptions?type=NEGATIVE_LEDGERS' },
      { key: 'R', label: 'Overdue Receivables', route: '/app/reports/exceptions?type=OVERDUE_RECEIVABLES' },
      { key: 'P', label: 'Overdue Payables', route: '/app/reports/exceptions?type=OVERDUE_PAYABLES' },
      { key: 'O', label: 'Optional Vouchers', route: '/app/reports/exceptions?type=OPTIONAL_VOUCHERS' },
      { key: 'C', label: 'Cancelled Vouchers', route: '/app/reports/exceptions?type=CANCELLED_VOUCHERS' },
      { key: 'M', label: 'Memorandum Vouchers', route: '/app/reports/exceptions?type=MEMORANDUM_VOUCHERS' },
      { key: 'J', label: 'Reversing Journals', route: '/app/reports/exceptions?type=REVERSING_JOURNALS' },
      { key: 'A', label: 'Maker-Checker Approval Queue', route: '/app/approvals' },
    ],
  },
  {
    key: 'V',
    label: 'Analysis & Verification',
    section: 'STATUTORY & EXCEPTIONS',
    children: [
      { key: 'T', label: 'Trial Balance Verification', route: '/app/reports/trial-balance' },
      { key: 'A', label: 'MC-AUDIT-2 Chain & Admin Console', route: '/app/admin' },
    ],
  },
];

export const GATEWAY_MENU_ITEMS: readonly GatewayMenuItem[] = [
  {
    key: 'C',
    section: 'MASTERS',
    prefixText: '',
    mnemonicChar: 'C',
    suffixText: 'reate',
    panelId: 'masters-create',
    directRoute: '/app/masters?mode=create',
    children: buildMasterSubTree('create'),
  },
  {
    key: 'A',
    section: 'MASTERS',
    prefixText: '',
    mnemonicChar: 'A',
    suffixText: 'lter',
    panelId: 'masters-alter',
    directRoute: '/app/masters?mode=alter',
    children: buildMasterSubTree('alter'),
  },
  {
    key: 'H',
    section: 'MASTERS',
    prefixText: 'C',
    mnemonicChar: 'H',
    suffixText: 'art of Accounts',
    panelId: 'chart-of-accounts',
    directRoute: '/app/coa',
    children: [
      { key: 'T', label: 'Chart of Accounts Tree Explorer', route: '/app/coa' },
      { key: 'G', label: 'Account Groups', route: '/app/masters/groups' },
      { key: 'L', label: 'Ledgers', route: '/app/masters/ledgers' },
      { key: 'V', label: 'Voucher Types (24 Predefined)', route: '/app/masters/voucher-types' },
      { key: 'I', label: 'Stock Items', route: '/app/inventory/stock-items' },
      { key: 'C', label: 'Cost Centres', route: '/app/masters/cost-centres' },
    ],
  },
  {
    key: 'V',
    section: 'TRANSACTIONS',
    prefixText: '',
    mnemonicChar: 'V',
    suffixText: 'ouchers',
    panelId: 'vouchers',
    directRoute: '/app/vouchers/new',
  },
  {
    key: 'K',
    section: 'TRANSACTIONS',
    prefixText: 'Day Boo',
    mnemonicChar: 'K',
    suffixText: '',
    panelId: 'day-book',
    directRoute: '/app/daybook',
  },
  {
    key: 'N',
    section: 'UTILITIES',
    prefixText: 'Ba',
    mnemonicChar: 'N',
    suffixText: 'king',
    panelId: 'banking',
    directRoute: '/app/banking/brs',
    children: [
      { key: 'B', label: 'Bank Reconciliation (Auto/Manual BRS)', route: '/app/banking/brs' },
      { key: 'C', label: 'Cheque Books & Cheque Register', route: '/app/banking/cheque-books' },
      { key: 'O', label: 'Bank Statement / OCR Staging Queue', route: '/app/banking/ocr-queue' },
    ],
  },
  {
    key: 'M',
    section: 'UTILITIES',
    prefixText: 'I',
    mnemonicChar: 'M',
    suffixText: 'port & Exchange',
    panelId: 'migration-exchange',
    directRoute: '/app/exchange/migrate-1800',
    children: [
      { key: 'S', label: 'Guided Spreadsheet Import (.xlsx/.ods/.csv)', route: '/app/exchange/spreadsheet-import' },
      { key: 'M', label: 'Migrate Legacy .1800 Snapshot', route: '/app/exchange/migrate-1800' },
      { key: 'X', label: 'Legacy XML / JSONEx Import & Export', route: '/app/exchange/import-export' },
      { key: 'E', label: 'Extension & TDL Workbench', route: '/app/extensions' },
    ],
  },
  {
    key: 'E',
    section: 'UTILITIES',
    prefixText: '',
    mnemonicChar: 'E',
    suffixText: 'xtension Workbench & TDL Importer',
    panelId: 'extension-workbench',
    directRoute: '/app/extensions',
  },
  {
    key: 'B',
    section: 'REPORTS',
    prefixText: '',
    mnemonicChar: 'B',
    suffixText: 'alance Sheet',
    panelId: 'balance-sheet',
    directRoute: '/app/reports/balance-sheet',
  },
  {
    key: 'P',
    section: 'REPORTS',
    prefixText: '',
    mnemonicChar: 'P',
    suffixText: 'rofit & Loss A/c',
    panelId: 'profit-loss',
    directRoute: '/app/reports/pnl',
  },
  {
    key: 'S',
    section: 'REPORTS',
    prefixText: '',
    mnemonicChar: 'S',
    suffixText: 'tock Summary',
    panelId: 'stock-summary',
    directRoute: '/app/reports/stock-summary',
  },
  {
    key: 'R',
    section: 'REPORTS',
    prefixText: '',
    mnemonicChar: 'R',
    suffixText: 'atio Analysis',
    panelId: 'ratios',
    directRoute: '/app/reports/ratios',
  },
  {
    key: 'D',
    section: 'REPORTS',
    prefixText: '',
    mnemonicChar: 'D',
    suffixText: 'isplay More Reports',
    panelId: 'more-reports',
    directRoute: '/app/reports/trial-balance',
    children: DISPLAY_MORE_REPORTS_TREE,
  },
  {
    key: 'O',
    section: 'REPORTS',
    prefixText: 'Statut',
    mnemonicChar: 'O',
    suffixText: 'ry Reports (GST / IMS / TDS)',
    panelId: 'statutory-reports',
    directRoute: '/app/statutory/gstr1',
    children: STATUTORY_SUB_MENU,
  },
];

interface GatewayDrillFrame {
  readonly title: string;
  readonly nodes: readonly GatewayMenuNode[];
  readonly selectedKey: string;
}

@Component({
  selector: 'mc-gateway',
  standalone: true,
  imports: [RouterLink, CodeBadgeComponent, IndianCurrencyPipe, SemanticIconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-gateway-grid">
      <!-- Left Card: Active Company Books OR UM-110-03 Windows Startup Select Company Card -->
      <section class="mc-card mc-gateway-summary" aria-label="Company Books Summary">
        @if (companyContext.activeCompany(); as company) {
          <div class="mc-gateway-section-head">
            <h2>Active Books Context</h2>
            <div class="mc-gateway-quick-links">
              <a routerLink="/app/vouchers/new" class="mc-btn mc-btn-secondary">
                <mc-icon name="post-ledger" [size]="16" />
                <span>New Voucher</span>
              </a>
              <a routerLink="/app/reports/balance-sheet" class="mc-btn mc-btn-secondary">Balance Sheet</a>
              <a routerLink="/app/reports/pnl" class="mc-btn mc-btn-secondary">Profit &amp; Loss</a>
              <a routerLink="/app/reports/trial-balance" class="mc-btn mc-btn-secondary">Trial Balance</a>
              <a routerLink="/app/reports/account-books" class="mc-btn mc-btn-secondary">Account Books</a>
              <a routerLink="/app/reports/statistics" class="mc-btn mc-btn-secondary">Statistics</a>
              <a routerLink="/app/reports/exceptions" class="mc-btn mc-btn-secondary">Exceptions</a>
              <a routerLink="/app/banking/brs" class="mc-btn mc-btn-secondary">Auto-BRS &amp; OCR</a>
              <a routerLink="/app/exchange/spreadsheet-import" class="mc-btn mc-btn-secondary">
                <mc-icon name="import" [size]="16" />
                <span>Spreadsheet Import</span>
              </a>
              <a routerLink="/app/extensions" class="mc-btn mc-btn-secondary">Extension Workbench</a>
              <a routerLink="/app/companies" class="mc-btn mc-btn-secondary">
                <mc-icon name="company" [size]="16" />
                <span>Company Hub (F3)</span>
              </a>
            </div>
          </div>

          <div class="mc-company-kv-grid">
            <div class="mc-kv-item">
              <span class="mc-kv-label">Legal Name</span>
              <span class="mc-kv-value">{{ company.legalName }}</span>
            </div>
            <div class="mc-kv-item">
              <span class="mc-kv-label">Company Code</span>
              <mc-code-badge [code]="company.code" />
            </div>
            <div class="mc-kv-item">
              <span class="mc-kv-label">Financial Year From</span>
              <mc-code-badge [code]="company.financialYearFrom" />
            </div>
            <div class="mc-kv-item">
              <span class="mc-kv-label">Base Currency / Scale</span>
              <span class="mc-kv-value">
                {{ company.baseCurrencyCode }} ({{ company.currencySymbol }}) &bull; {{ company.currencyScale }} decimals
              </span>
            </div>
            <div class="mc-kv-item">
              <span class="mc-kv-label">GSTIN Registration</span>
              @if (company.features.gstEnabled && company.features.gstin) {
                <mc-code-badge [code]="company.features.gstin" />
              } @else {
                <span class="mc-badge mc-badge-slate">Not Configured</span>
              }
            </div>
            <div class="mc-kv-item">
              <span class="mc-kv-label">MCA Edit Log (Audit Trail)</span>
              @if (company.features.mcaEditLogEnforced) {
                <span class="mc-badge mc-badge-jade">Enforced (Immutable)</span>
              } @else {
                <span class="mc-badge mc-badge-slate">Standard</span>
              }
            </div>
          </div>
        } @else {
          <!-- UM-110-03 Classic Windows Startup Select Company Card -->
          <div class="mc-startup-company-box" data-testid="windows-startup-select-company">
            <div class="mc-gateway-section-head">
              <h2>Select Company</h2>
              <div class="mc-gateway-quick-links">
                <a
                  routerLink="/app/companies"
                  [queryParams]="{ mode: 'create' }"
                  class="mc-btn mc-btn-primary"
                  data-testid="startup-create-company-btn"
                >
                  <mc-icon name="company" [size]="16" />
                  <span>Create Company</span>
                </a>
                <a
                  routerLink="/app/exchange/migrate-1800"
                  class="mc-btn mc-btn-secondary"
                  data-testid="startup-migrate-1800-btn"
                >
                  <mc-icon name="import" [size]="16" />
                  <span>Specify Path / Migrate Legacy .1800</span>
                </a>
              </div>
            </div>
            <p class="mc-preview-desc">
              Select an existing company below or choose <strong>Create Company</strong> to configure
              your books and post-create <strong>F11: Company Features</strong>.
            </p>
            <table class="mc-table">
              <thead>
                <tr>
                  <th scope="col">Code</th>
                  <th scope="col">Name of Company</th>
                  <th scope="col">FY From</th>
                  <th scope="col">Last Entry</th>
                </tr>
              </thead>
              <tbody>
                @for (comp of companyContext.openCompanies(); track comp.id) {
                  <tr (click)="companyContext.selectActiveCompany(comp.id)">
                    <td><mc-code-badge [code]="comp.code" /></td>
                    <td><strong>{{ comp.legalName }}</strong></td>
                    <td class="mc-code">{{ comp.financialYearFrom }}</td>
                    <td class="mc-code">{{ comp.lastEntryDate ?? 'No Vouchers' }}</td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="4" class="mc-empty-records">No matching records</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }

        @if (selectedMnemonicItem(); as activeItem) {
          <div class="mc-selected-panel-preview" role="region" aria-live="polite">
            <div class="mc-preview-header">
              <span>
                Selected Gateway Action:
                <strong>{{ activeItem.prefixText }}{{ activeItem.mnemonicChar }}{{ activeItem.suffixText }}</strong>
              </span>
              <mc-code-badge [code]="'LEVEL-' + currentLevelNumber()" />
            </div>
            <p class="mc-preview-desc">
              Navigate <strong>Level 1 &rarr; Level 2 &rarr; Level 3</strong> menus with single-letter
              mnemonics or Arrow keys + Enter, press <strong>Esc</strong> to step back one level, or
              press <strong>Alt+G</strong> (Go To) at any time.
            </p>
            <div class="mc-preview-sample-row">
              <span>Exact Arithmetic Sample Balance:</span>
              <span class="mc-amount">{{ '500000.00' | mcIndianCurrency }}</span>
            </div>
          </div>
        }
      </section>

      <!-- Right Menu Card: 3-Level Cascading Gateway of MoneyCraft -->
      <nav
        class="mc-card mc-gateway-menu-card"
        aria-label="Gateway of MoneyCraft Menu"
        data-testid="gateway-menu-card"
      >
        <div class="mc-gateway-menu-header">
          <div class="mc-gateway-header-top">
            @if (drillStack().length > 0) {
              <button
                type="button"
                class="mc-gateway-back-btn"
                data-testid="gateway-step-back-btn"
                (click)="stepBackLevel()"
              >
                &larr; Esc
              </button>
            }
            <span>{{ activeMenuTitle() }}</span>
          </div>
          <div class="mc-gateway-breadcrumb" data-testid="gateway-breadcrumb">
            {{ breadcrumbText() }}
          </div>
          @if (drillStack().length > 0) {
            <div class="mc-gateway-toggle-row">
              <button
                type="button"
                class="mc-gateway-toggle-pill"
                [class.mc-gateway-toggle-pill-active]="showMore()"
                data-testid="gateway-show-more-btn"
                (click)="toggleShowMore()"
              >
                {{ showMore() ? 'Show Less' : 'Show More' }}
              </button>
              <button
                type="button"
                class="mc-gateway-toggle-pill"
                [class.mc-gateway-toggle-pill-active]="showInactive()"
                data-testid="gateway-show-inactive-btn"
                (click)="toggleShowInactive()"
              >
                {{ showInactive() ? 'Hide Inactive' : 'Show Inactive' }}
              </button>
            </div>
          }
        </div>

        @if (activeDrillFrame(); as frame) {
          <!-- LEVEL 2 or LEVEL 3 CASCADING SUB-MENU -->
          <div class="mc-gateway-menu-body" [attr.data-testid]="'gateway-level-' + currentLevelNumber()">
            <ul class="mc-gateway-list">
              @for (node of visibleDrillNodes(); track node.key + node.label) {
                <li>
                  <button
                    type="button"
                    class="mc-gateway-item-btn"
                    [class.mc-gateway-item-active]="frame.selectedKey === node.key"
                    [class.mc-gateway-item-inactive]="isNodeInactive(node)"
                    [attr.data-testid]="'gateway-subnode-' + node.key"
                    (click)="activateSubNode(node)"
                  >
                    <span>
                      {{ node.label }}
                      @if (isNodeInactive(node)) {
                        <span class="mc-inactive-tag">(Inactive — F11)</span>
                      }
                      @if (node.children?.length) {
                        <span class="mc-gateway-drill-arrow">&rsaquo;</span>
                      }
                    </span>
                    <span class="mc-gateway-hotkey">{{ node.key }}</span>
                  </button>
                </li>
              }
            </ul>
          </div>
        } @else {
          <!-- LEVEL 1 ROOT GATEWAY MENU -->
          <div class="mc-gateway-menu-body" data-testid="gateway-level-1">
            @for (sec of sections; track sec.id) {
              <div class="mc-gateway-group">
                <div class="mc-gateway-group-title">{{ sec.label }}</div>
                <ul class="mc-gateway-list">
                  @for (item of itemsBySection(sec.id); track item.key) {
                    <li>
                      <button
                        type="button"
                        class="mc-gateway-item-btn"
                        [class.mc-gateway-item-active]="activeMnemonicKey() === item.key"
                        [attr.data-testid]="'gateway-l1-' + item.key"
                        (click)="activateMenuItem(item)"
                      >
                        <span>
                          {{ item.prefixText }}<span class="mc-mnemonic-char">{{ item.mnemonicChar }}</span>{{ item.suffixText }}
                          @if (item.children?.length) {
                            <span class="mc-gateway-drill-arrow">&rsaquo;</span>
                          }
                        </span>
                        <span class="mc-gateway-hotkey">{{ item.key }}</span>
                      </button>
                    </li>
                  }
                </ul>
              </div>
            }
          </div>
        }

        <div class="mc-gateway-menu-footer">
          @if (drillStack().length > 0) {
            Level {{ currentLevelNumber() }} &bull; Press highlighted hotkey, Enter to open, or <strong>Esc</strong> to step back
          } @else {
            Press highlighted letter (<strong>C, A, H, V, K, N, M, E, B, P, S, R, D, O</strong>) or Arrow keys + Enter
          }
        </div>
      </nav>

      @if (pendingFeatureNode(); as pending) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Enable F11 Feature Confirmation"
          data-testid="inactive-feature-enable-modal"
          (keydown)="onFeaturePromptKeyDown($event)"
        >
          <div class="mc-modal-dialog mc-feature-prompt-dialog">
            <div class="mc-modal-header">
              <span>Activate Feature in F11: Company Features</span>
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                data-testid="inactive-feature-enable-no-btn"
                (click)="cancelFeatureActivation()"
              >
                No (Esc / N)
              </button>
            </div>
            <div class="mc-modal-body">
              <p class="mc-feature-prompt-copy">
                You need to enable
                <strong>{{ pending.requiredFeatureLabel ?? pending.requiredFeature }}</strong>
                in <strong>F11: Company Features</strong> to use
                <strong>{{ pending.label }}</strong>. Enable now?
              </p>
            </div>
            <div class="mc-modal-footer">
              <button
                type="button"
                class="mc-btn"
                (click)="cancelFeatureActivation()"
              >
                No (Esc / N)
              </button>
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="inactive-feature-enable-yes-btn"
                (click)="confirmFeatureActivation()"
              >
                Yes (Enter / Y)
              </button>
            </div>
          </div>
        </div>
      }
    </div>
  `,
  styles: `
    .mc-gateway-grid {
      display: grid;
      grid-template-columns: minmax(0, 1.35fr) minmax(310px, 400px);
      gap: var(--mc-space-5);
      align-items: start;
    }

    @media (max-width: 960px) {
      .mc-gateway-grid {
        grid-template-columns: 1fr;
      }
    }

    .mc-gateway-summary {
      padding: var(--mc-space-5);
    }

    .mc-gateway-section-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-3);
      padding-bottom: var(--mc-space-3);
      border-bottom: var(--mc-border-subtle);
      margin-bottom: var(--mc-space-4);
      flex-wrap: wrap;
    }

    .mc-gateway-quick-links {
      display: flex;
      flex-wrap: wrap;
      gap: var(--mc-space-2);
    }

    .mc-company-kv-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    .mc-kv-item {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-sm);
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .mc-kv-label {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 600;
    }

    .mc-kv-value {
      font-weight: 600;
      color: var(--mc-color-slate-900);
    }

    .mc-startup-company-box {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-selected-panel-preview {
      margin-top: var(--mc-space-4);
      padding: var(--mc-space-3);
      background: var(--mc-color-teal-50);
      border-left: 3px solid var(--mc-color-pressed-teal);
      border-radius: var(--mc-radius-xs);
    }

    .mc-preview-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: var(--mc-space-1);
    }

    .mc-preview-desc {
      margin: 0 0 var(--mc-space-2);
      font-size: var(--mc-font-size-sm);
      color: var(--mc-color-slate-700);
      font-weight: 400;
    }

    .mc-preview-sample-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-top: var(--mc-space-2);
      border-top: 1px solid var(--mc-color-teal-100);
      font-size: var(--mc-font-size-sm);
      font-weight: 500;
    }

    .mc-gateway-menu-card {
      overflow: hidden;
      border: 2px solid var(--mc-color-slate-900);
    }

    .mc-gateway-menu-header {
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      padding: 0.55rem 0.9rem;
      font-weight: 700;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .mc-gateway-header-top {
      display: flex;
      align-items: center;
      justify-content: center;
      position: relative;
      gap: var(--mc-space-2);
    }

    .mc-gateway-back-btn {
      position: absolute;
      left: 0;
      background: rgba(255, 255, 255, 0.14);
      border: 1px solid rgba(255, 255, 255, 0.35);
      color: var(--mc-color-surface);
      border-radius: var(--mc-radius-xs);
      padding: 1px 6px;
      font-size: 11px;
      font-weight: 600;
      cursor: pointer;
    }

    .mc-gateway-breadcrumb {
      text-align: center;
      font-size: 11px;
      font-weight: 500;
      color: var(--mc-color-mint-signal);
    }

    .mc-gateway-toggle-row {
      display: flex;
      justify-content: flex-end;
      gap: 6px;
      margin-top: 2px;
    }

    .mc-gateway-toggle-pill {
      background: rgba(255, 255, 255, 0.12);
      border: 1px solid rgba(255, 255, 255, 0.32);
      color: var(--mc-color-surface);
      border-radius: var(--mc-radius-xs);
      padding: 2px 7px;
      font-size: 11px;
      font-weight: 600;
      cursor: pointer;
    }

    .mc-gateway-toggle-pill-active {
      background: var(--mc-color-verdigris-teal);
      border-color: var(--mc-color-mint-signal);
    }

    .mc-gateway-menu-body {
      padding: var(--mc-space-3) var(--mc-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-gateway-group-title {
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      color: var(--mc-color-slate-500);
      letter-spacing: 0.06em;
      margin-bottom: 4px;
    }

    .mc-gateway-list {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .mc-gateway-item-btn {
      width: 100%;
      padding: 0.35rem 0.65rem;
      border: 1px solid transparent;
      border-radius: var(--mc-radius-xs);
      background: transparent;
      color: var(--mc-color-slate-900);
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-weight: 500;
      cursor: pointer;

      &:hover {
        background: var(--mc-color-row-hover);
      }
    }

    .mc-gateway-item-inactive {
      color: var(--mc-color-slate-500);
      border-style: dashed;
    }

    .mc-inactive-tag {
      margin-left: 6px;
      font-size: 11px;
      font-weight: 600;
      color: var(--mc-color-slate-500);
    }

    .mc-gateway-item-active {
      background: var(--mc-color-pressed-teal);
      color: var(--mc-color-surface);
      font-weight: 600;

      .mc-mnemonic-char,
      .mc-gateway-hotkey,
      .mc-gateway-drill-arrow,
      .mc-inactive-tag {
        color: var(--mc-color-mint-signal);
      }
    }

    .mc-gateway-drill-arrow {
      margin-left: 4px;
      font-weight: 700;
    }

    .mc-gateway-hotkey {
      font-family: var(--mc-font-mono);
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-500);
      font-weight: 600;
    }

    .mc-gateway-menu-footer {
      padding: 0.45rem 0.75rem;
      background: var(--mc-color-canvas);
      border-top: var(--mc-border-subtle);
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      text-align: center;
    }

    .mc-feature-prompt-dialog {
      width: min(460px, 94vw);
    }

    .mc-feature-prompt-copy {
      margin: 0;
      font-size: var(--mc-font-size-sm);
      color: var(--mc-color-slate-800);
      line-height: 1.45;
    }
  `,
})
export class GatewayComponent implements OnInit {
  readonly companyContext = inject(CompanyContextStore);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly navStack = inject(NavigationStackStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  readonly sections: readonly {
    readonly id: GatewayMenuItem['section'];
    readonly label: string;
  }[] = [
    { id: 'MASTERS', label: 'MASTERS' },
    { id: 'TRANSACTIONS', label: 'TRANSACTIONS' },
    { id: 'UTILITIES', label: 'UTILITIES' },
    { id: 'REPORTS', label: 'REPORTS' },
  ];

  readonly activeMnemonicKey = signal<string>('V');
  readonly drillStack = signal<readonly GatewayDrillFrame[]>([]);
  readonly showMore = signal<boolean>(false);
  readonly showInactive = signal<boolean>(false);
  readonly pendingFeatureNode = signal<GatewayMenuNode | null>(null);

  readonly activeDrillFrame = computed<GatewayDrillFrame | null>(() => {
    const stack = this.drillStack();
    return stack.length > 0 ? stack[stack.length - 1] : null;
  });

  readonly visibleDrillNodes = computed<readonly GatewayMenuNode[]>(() => {
    const frame = this.activeDrillFrame();
    if (!frame) return [];
    const more = this.showMore();
    const inact = this.showInactive();
    return frame.nodes.filter((n) => {
      if (n.showMoreOnly && !more && !inact) {
        return false;
      }
      if (this.isNodeInactive(n) && !inact) {
        // Keep core masters visible or show when showInactive is toggled
        return (
          n.label === 'Accounting Masters' ||
          n.label === 'Inventory Masters' ||
          n.label === 'Statutory Masters & Details'
        );
      }
      return true;
    });
  });

  readonly currentLevelNumber = computed<number>(() => this.drillStack().length + 1);

  readonly activeMenuTitle = computed<string>(() => {
    const frame = this.activeDrillFrame();
    return frame ? frame.title : 'Gateway of MoneyCraft';
  });

  readonly breadcrumbText = computed<string>(() => {
    const crumbs = ['Gateway', ...this.drillStack().map((f) => f.title)];
    return crumbs.join(' > ');
  });

  readonly selectedMnemonicItem = computed(
    () =>
      GATEWAY_MENU_ITEMS.find((i) => i.key === this.activeMnemonicKey()) ?? GATEWAY_MENU_ITEMS[0],
  );

  constructor() {
    const initialPanel = this.route.snapshot.queryParamMap.get('panel');
    if (initialPanel) {
      const matched = GATEWAY_MENU_ITEMS.find((i) => i.panelId === initialPanel);
      if (matched) {
        this.activeMnemonicKey.set(matched.key);
        if (matched.children && matched.children.length > 0) {
          this.pushDrillFrame(
            `${matched.prefixText}${matched.mnemonicChar}${matched.suffixText}`,
            matched.children,
          );
        }
      }
    }
  }

  ngOnInit(): void {
    const disposeEsc = this.shortcutRegistry.bindHandler(
      'nav.escape',
      'MODAL',
      () => {
        if (this.pendingFeatureNode()) {
          this.cancelFeatureActivation();
          return true;
        }
        return this.stepBackLevel();
      },
      () => (this.drillStack().length > 0 || Boolean(this.pendingFeatureNode())) && !this.navStack.hasOverlays(),
    );
    this.destroyRef.onDestroy(disposeEsc);
  }

  toggleShowMore(): void {
    this.showMore.update((v) => !v);
  }

  toggleShowInactive(): void {
    const next = !this.showInactive();
    this.showInactive.set(next);
    if (next) {
      this.showMore.set(true);
    }
  }

  isNodeInactive(node: GatewayMenuNode): boolean {
    if (!node.requiredFeature) return false;
    const comp = this.companyContext.activeCompany();
    if (!comp) return false;
    return !comp.features[node.requiredFeature];
  }

  itemsBySection(section: GatewayMenuItem['section']): readonly GatewayMenuItem[] {
    return GATEWAY_MENU_ITEMS.filter((item) => item.section === section);
  }

  stepBackLevel(): boolean {
    const stack = this.drillStack();
    if (stack.length === 0) {
      return false;
    }
    this.drillStack.set(stack.slice(0, -1));
    return true;
  }

  private pushDrillFrame(title: string, nodes: readonly GatewayMenuNode[]): void {
    if (nodes.length === 0) return;
    this.drillStack.set([
      ...this.drillStack(),
      {
        title,
        nodes,
        selectedKey: nodes[0].key,
      },
    ]);
  }

  private updateTopFrameSelectedKey(nextKey: string): void {
    const stack = this.drillStack();
    if (stack.length === 0) return;
    const top = stack[stack.length - 1];
    this.drillStack.set([...stack.slice(0, -1), { ...top, selectedKey: nextKey }]);
  }

  onFeaturePromptKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Escape' || event.key === 'n' || event.key === 'N') {
      event.preventDefault();
      event.stopPropagation();
      this.cancelFeatureActivation();
      return;
    }
    if (event.key === 'Enter' || event.key === 'y' || event.key === 'Y') {
      event.preventDefault();
      event.stopPropagation();
      void this.confirmFeatureActivation();
    }
  }

  cancelFeatureActivation(): void {
    this.pendingFeatureNode.set(null);
  }

  async confirmFeatureActivation(): Promise<void> {
    const node = this.pendingFeatureNode();
    this.pendingFeatureNode.set(null);
    if (!node) return;
    if (node.requiredFeature) {
      await this.companyContext.enableCompanyFeature(node.requiredFeature);
    }
    if (node.children && node.children.length > 0) {
      this.pushDrillFrame(node.label, node.children);
      return;
    }
    if (node.route) {
      void this.router.navigateByUrl(node.route);
    }
  }

  @HostListener('window:keydown', ['$event'])
  onGatewayMnemonicKey(event: KeyboardEvent): void {
    if (this.pendingFeatureNode()) {
      this.onFeaturePromptKeyDown(event);
      return;
    }
    if (
      event.defaultPrevented ||
      event.altKey ||
      event.metaKey ||
      event.isComposing ||
      this.navStack.hasOverlays() ||
      this.shortcutRegistry.hasActiveModalContext() ||
      isEditableTextElement(event.target) ||
      isStrictInputOwnerElement(event.target)
    ) {
      return;
    }

    if (event.ctrlKey) {
      const frame = this.activeDrillFrame();
      if (frame) {
        const nodes = this.visibleDrillNodes();
        if (nodes.length > 0 && event.key === 'ArrowUp') {
          event.preventDefault();
          this.updateTopFrameSelectedKey(nodes[0].key);
        } else if (nodes.length > 0 && event.key === 'ArrowDown') {
          event.preventDefault();
          this.updateTopFrameSelectedKey(nodes[nodes.length - 1].key);
        }
      } else {
        if (event.key === 'ArrowUp') {
          event.preventDefault();
          this.activeMnemonicKey.set(GATEWAY_MENU_ITEMS[0].key);
        } else if (event.key === 'ArrowDown') {
          event.preventDefault();
          this.activeMnemonicKey.set(GATEWAY_MENU_ITEMS[GATEWAY_MENU_ITEMS.length - 1].key);
        }
      }
      return;
    }

    if (event.key === 'Escape' && this.drillStack().length > 0) {
      event.preventDefault();
      event.stopPropagation();
      this.stepBackLevel();
      return;
    }

    const frame = this.activeDrillFrame();
    if (frame) {
      const nodes = this.visibleDrillNodes();
      if (nodes.length === 0) return;
      const idx = Math.max(
        0,
        nodes.findIndex((n) => n.key === frame.selectedKey),
      );
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        const next = nodes[(idx + 1) % nodes.length];
        this.updateTopFrameSelectedKey(next.key);
        return;
      }
      if (event.key === 'ArrowUp') {
        event.preventDefault();
        const prev = nodes[(idx - 1 + nodes.length) % nodes.length];
        this.updateTopFrameSelectedKey(prev.key);
        return;
      }
      if (event.key === 'Enter') {
        event.preventDefault();
        const current = nodes[idx] ?? nodes[0];
        if (current) {
          this.activateSubNode(current);
        }
        return;
      }
      if (this.shortcutRegistry.effectiveDispatchMode() !== 'CHARACTER') {
        return;
      }
      if (event.key.length === 1 && !event.repeat) {
        const upper = event.key.toUpperCase();
        const matched = nodes.find((n) => n.key.toUpperCase() === upper);
        if (matched) {
          event.preventDefault();
          this.activateSubNode(matched);
        }
      }
      return;
    }

    // Level 1 navigation
    const idx = GATEWAY_MENU_ITEMS.findIndex((i) => i.key === this.activeMnemonicKey());
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      const next = GATEWAY_MENU_ITEMS[(idx + 1) % GATEWAY_MENU_ITEMS.length];
      this.activeMnemonicKey.set(next.key);
      return;
    }

    if (event.key === 'ArrowUp') {
      event.preventDefault();
      const prev =
        GATEWAY_MENU_ITEMS[(idx - 1 + GATEWAY_MENU_ITEMS.length) % GATEWAY_MENU_ITEMS.length];
      this.activeMnemonicKey.set(prev.key);
      return;
    }

    if (event.key === 'Enter') {
      const current = this.selectedMnemonicItem();
      if (current) {
        event.preventDefault();
        this.activateMenuItem(current);
      }
      return;
    }

    if (this.shortcutRegistry.effectiveDispatchMode() !== 'CHARACTER') {
      return;
    }

    if (event.key.length === 1 && !event.repeat) {
      const upper = event.key.toUpperCase();
      const matched = GATEWAY_MENU_ITEMS.find((i) => i.key === upper);
      if (matched) {
        event.preventDefault();
        this.activateMenuItem(matched);
      }
    }
  }

  activateMenuItem(item: GatewayMenuItem): void {
    this.activeMnemonicKey.set(item.key);
    if (item.children && item.children.length > 0) {
      this.pushDrillFrame(
        `${item.prefixText}${item.mnemonicChar}${item.suffixText}`,
        item.children,
      );
      return;
    }
    if (item.directRoute) {
      void this.router.navigateByUrl(item.directRoute);
      return;
    }
    void this.router.navigate(['/app/gateway'], {
      queryParams: { panel: item.panelId },
    });
  }

  activateSubNode(node: GatewayMenuNode): void {
    this.updateTopFrameSelectedKey(node.key);
    if (this.isNodeInactive(node)) {
      this.pendingFeatureNode.set(node);
      return;
    }
    if (node.children && node.children.length > 0) {
      this.pushDrillFrame(node.label, node.children);
      return;
    }
    if (node.route) {
      void this.router.navigateByUrl(node.route);
    }
  }
}
