import { Routes } from '@angular/router';
import {
  authenticatedGuard,
  setupRouteGuard,
  systemRoleGuard,
} from './core/auth/permission.guard';

const MASTERS_CHILD_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./features/masters/masters-hub.component').then((m) => m.MastersHubComponent),
  },
  {
    path: 'groups',
    loadComponent: () =>
      import('./features/masters/group-workbench.component').then(
        (m) => m.GroupWorkbenchComponent,
      ),
  },
  {
    path: 'ledgers',
    loadComponent: () =>
      import('./features/masters/ledger-workbench.component').then(
        (m) => m.LedgerWorkbenchComponent,
      ),
  },
  {
    path: 'currencies',
    loadComponent: () =>
      import('./features/masters/currency-workbench.component').then(
        (m) => m.CurrencyWorkbenchComponent,
      ),
  },
  {
    path: 'cost-centres',
    loadComponent: () =>
      import('./features/masters/cost-centre-workbench.component').then(
        (m) => m.CostCentreWorkbenchComponent,
      ),
  },
  {
    path: 'voucher-types',
    loadComponent: () =>
      import('./features/masters/voucher-type-workbench.component').then(
        (m) => m.VoucherTypeWorkbenchComponent,
      ),
  },
];

const VOUCHERS_CHILD_ROUTES: Routes = [
  {
    path: 'new',
    loadComponent: () =>
      import('./features/vouchers/voucher-grid.component').then((m) => m.VoucherGridComponent),
  },
  {
    path: ':voucherId',
    loadComponent: () =>
      import('./features/vouchers/voucher-grid.component').then((m) => m.VoucherGridComponent),
  },
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'new',
  },
];

const INVENTORY_CHILD_ROUTES: Routes = [
  {
    path: 'masters',
    loadComponent: () =>
      import('./features/masters/masters-hub.component').then((m) => m.MastersHubComponent),
  },
  {
    path: 'uoms',
    loadComponent: () =>
      import('./features/inventory/uom-workbench.component').then((m) => m.UomWorkbenchComponent),
  },
  {
    path: 'godowns',
    loadComponent: () =>
      import('./features/inventory/godown-workbench.component').then(
        (m) => m.GodownWorkbenchComponent,
      ),
  },
  {
    path: 'stock-groups',
    loadComponent: () =>
      import('./features/inventory/stock-group-workbench.component').then(
        (m) => m.StockGroupWorkbenchComponent,
      ),
  },
  {
    path: 'stock-items',
    loadComponent: () =>
      import('./features/inventory/stock-item-workbench.component').then(
        (m) => m.StockItemWorkbenchComponent,
      ),
  },
  {
    path: 'price-lists',
    loadComponent: () =>
      import('./features/inventory/price-list-workbench.component').then(
        (m) => m.PriceListWorkbenchComponent,
      ),
  },
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'masters',
  },
];

const REPORTS_CHILD_ROUTES: Routes = [
  {
    path: 'trial-balance',
    loadComponent: () =>
      import('./features/reports/trial-balance.component').then((m) => m.TrialBalanceComponent),
  },
  {
    path: 'pnl',
    loadComponent: () =>
      import('./features/reports/profit-and-loss.component').then((m) => m.ProfitAndLossComponent),
  },
  {
    path: 'profit-and-loss',
    loadComponent: () =>
      import('./features/reports/profit-and-loss.component').then((m) => m.ProfitAndLossComponent),
  },
  {
    path: 'balance-sheet',
    loadComponent: () =>
      import('./features/reports/balance-sheet.component').then((m) => m.BalanceSheetComponent),
  },
  {
    path: 'schedule-iii',
    loadComponent: () =>
      import('./features/reports/schedule-iii.component').then((m) => m.ScheduleIiiComponent),
  },
  {
    path: 'ageing',
    data: { ageingType: 'RECEIVABLES' },
    loadComponent: () =>
      import('./features/reports/bills-ageing.component').then((m) => m.BillsAgeingComponent),
  },
  {
    path: 'bills-receivable',
    data: { ageingType: 'RECEIVABLES' },
    loadComponent: () =>
      import('./features/reports/bills-ageing.component').then((m) => m.BillsAgeingComponent),
  },
  {
    path: 'bills-payable',
    data: { ageingType: 'PAYABLES' },
    loadComponent: () =>
      import('./features/reports/bills-ageing.component').then((m) => m.BillsAgeingComponent),
  },
  {
    path: 'cash-flow',
    data: { viewMode: 'CASH_FLOW' },
    loadComponent: () =>
      import('./features/reports/cash-flow-and-ratios.component').then(
        (m) => m.CashFlowAndRatiosComponent,
      ),
  },
  {
    path: 'ratios',
    data: { viewMode: 'RATIOS' },
    loadComponent: () =>
      import('./features/reports/cash-flow-and-ratios.component').then(
        (m) => m.CashFlowAndRatiosComponent,
      ),
  },
  {
    path: 'stock-summary',
    data: { reportTab: 'STOCK_SUMMARY' },
    loadComponent: () =>
      import('./features/inventory/stock-summary.component').then((m) => m.StockSummaryComponent),
  },
  {
    path: 'godown-summary',
    data: { reportTab: 'GODOWN_SUMMARY' },
    loadComponent: () =>
      import('./features/inventory/stock-summary.component').then((m) => m.StockSummaryComponent),
  },
  {
    path: 'batch-ageing',
    data: { reportTab: 'BATCH_AGEING' },
    loadComponent: () =>
      import('./features/inventory/stock-summary.component').then((m) => m.StockSummaryComponent),
  },
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'balance-sheet',
  },
];

const BANKING_CHILD_ROUTES: Routes = [
  {
    path: 'brs',
    data: { bankingTab: 'BRS' },
    loadComponent: () =>
      import('./features/banking/banking-brs-workbench.component').then(
        (m) => m.BankingBrsWorkbenchComponent,
      ),
  },
  {
    path: 'cheque-books',
    data: { bankingTab: 'CHEQUE_BOOKS' },
    loadComponent: () =>
      import('./features/banking/banking-brs-workbench.component').then(
        (m) => m.BankingBrsWorkbenchComponent,
      ),
  },
  {
    path: 'ocr-queue',
    data: { bankingTab: 'OCR_QUEUE' },
    loadComponent: () =>
      import('./features/banking/banking-brs-workbench.component').then(
        (m) => m.BankingBrsWorkbenchComponent,
      ),
  },
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'brs',
  },
];

const EXCHANGE_CHILD_ROUTES: Routes = [
  {
    path: 'migrate-1800',
    data: { exchangeTab: 'MIGRATE_1800' },
    loadComponent: () =>
      import('./features/exchange/migration-and-exchange.component').then(
        (m) => m.MigrationAndExchangeComponent,
      ),
  },
  {
    path: 'import-export',
    data: { exchangeTab: 'IMPORT_EXPORT' },
    loadComponent: () =>
      import('./features/exchange/migration-and-exchange.component').then(
        (m) => m.MigrationAndExchangeComponent,
      ),
  },
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'migrate-1800',
  },
];

const STATUTORY_CHILD_ROUTES: Routes = [
  {
    path: 'gstr1',
    loadComponent: () =>
      import('./features/statutory/gstr1-dashboard.component').then(
        (m) => m.Gstr1DashboardComponent,
      ),
  },
  {
    path: 'gstr3b',
    loadComponent: () =>
      import('./features/statutory/gstr3b-dashboard.component').then(
        (m) => m.Gstr3bDashboardComponent,
      ),
  },
  {
    path: 'gstr9',
    loadComponent: () =>
      import('./features/statutory/gstr9-dashboard.component').then(
        (m) => m.Gstr9DashboardComponent,
      ),
  },
  {
    path: 'ims',
    loadComponent: () =>
      import('./features/statutory/ims-workbench.component').then(
        (m) => m.ImsWorkbenchComponent,
      ),
  },
  {
    path: 'einvoice',
    loadComponent: () =>
      import('./features/statutory/einvoice-workbench.component').then(
        (m) => m.EinvoiceWorkbenchComponent,
      ),
  },
  {
    path: 'tds-26q',
    loadComponent: () =>
      import('./features/statutory/tds-26q-workbench.component').then(
        (m) => m.Tds26qWorkbenchComponent,
      ),
  },
  {
    path: 'gst-registrations',
    loadComponent: () =>
      import('./features/statutory/gst-registration-workbench.component').then(
        (m) => m.GstRegistrationWorkbenchComponent,
      ),
  },
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'gstr1',
  },
];

export const APP_ROUTES: Routes = [
  {
    path: 'setup',
    canActivate: [setupRouteGuard],
    loadComponent: () =>
      import('./features/auth/setup.component').then((m) => m.SetupComponent),
  },
  {
    path: 'login',
    loadComponent: () =>
      import('./features/auth/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'auth/oidc/callback',
    loadComponent: () =>
      import('./features/auth/oidc-callback.component').then((m) => m.OidcCallbackComponent),
  },
  {
    path: 'vouchers',
    canActivate: [authenticatedGuard],
    loadComponent: () =>
      import('./shell/erp-shell.component').then((m) => m.ErpShellComponent),
    children: VOUCHERS_CHILD_ROUTES,
  },
  {
    path: 'daybook',
    canActivate: [authenticatedGuard],
    loadComponent: () =>
      import('./shell/erp-shell.component').then((m) => m.ErpShellComponent),
    children: [
      {
        path: '',
        loadComponent: () =>
          import('./features/vouchers/day-book.component').then((m) => m.DayBookComponent),
      },
    ],
  },
  {
    path: 'approvals',
    canActivate: [authenticatedGuard],
    loadComponent: () =>
      import('./shell/erp-shell.component').then((m) => m.ErpShellComponent),
    children: [
      {
        path: '',
        loadComponent: () =>
          import('./features/vouchers/approval-queue.component').then(
            (m) => m.ApprovalQueueComponent,
          ),
      },
    ],
  },
  {
    path: 'coa',
    canActivate: [authenticatedGuard],
    loadComponent: () =>
      import('./shell/erp-shell.component').then((m) => m.ErpShellComponent),
    children: [
      {
        path: '',
        loadComponent: () =>
          import('./features/masters/coa-explorer.component').then((m) => m.CoaExplorerComponent),
      },
    ],
  },
  {
    path: 'masters',
    canActivate: [authenticatedGuard],
    loadComponent: () =>
      import('./shell/erp-shell.component').then((m) => m.ErpShellComponent),
    children: MASTERS_CHILD_ROUTES,
  },
  {
    path: 'inventory',
    canActivate: [authenticatedGuard],
    loadComponent: () =>
      import('./shell/erp-shell.component').then((m) => m.ErpShellComponent),
    children: INVENTORY_CHILD_ROUTES,
  },
  {
    path: 'reports',
    canActivate: [authenticatedGuard],
    loadComponent: () =>
      import('./shell/erp-shell.component').then((m) => m.ErpShellComponent),
    children: REPORTS_CHILD_ROUTES,
  },
  {
    path: 'statutory',
    canActivate: [authenticatedGuard],
    loadComponent: () =>
      import('./shell/erp-shell.component').then((m) => m.ErpShellComponent),
    children: STATUTORY_CHILD_ROUTES,
  },
  {
    path: 'banking',
    canActivate: [authenticatedGuard],
    loadComponent: () =>
      import('./shell/erp-shell.component').then((m) => m.ErpShellComponent),
    children: BANKING_CHILD_ROUTES,
  },
  {
    path: 'exchange',
    canActivate: [authenticatedGuard],
    loadComponent: () =>
      import('./shell/erp-shell.component').then((m) => m.ErpShellComponent),
    children: EXCHANGE_CHILD_ROUTES,
  },
  {
    path: 'companies',
    canActivate: [authenticatedGuard],
    loadComponent: () =>
      import('./shell/erp-shell.component').then((m) => m.ErpShellComponent),
    children: [
      {
        path: '',
        loadComponent: () =>
          import('./features/companies/company-hub.component').then((m) => m.CompanyHubComponent),
      },
    ],
  },
  {
    path: 'admin',
    canActivate: [
      authenticatedGuard,
      systemRoleGuard(['INSTALLATION_OWNER', 'SECURITY_AUDITOR', 'SYSTEM_OPERATOR']),
    ],
    loadComponent: () =>
      import('./shell/erp-shell.component').then((m) => m.ErpShellComponent),
    children: [
      {
        path: '',
        loadComponent: () =>
          import('./features/companies/admin-console.component').then(
            (m) => m.AdminConsoleComponent,
          ),
      },
    ],
  },
  {
    path: 'app',
    canActivate: [authenticatedGuard],
    loadComponent: () =>
      import('./shell/erp-shell.component').then((m) => m.ErpShellComponent),
    children: [
      {
        path: 'gateway',
        loadComponent: () =>
          import('./shell/gateway.component').then((m) => m.GatewayComponent),
      },
      {
        path: 'vouchers',
        children: VOUCHERS_CHILD_ROUTES,
      },
      {
        path: 'daybook',
        loadComponent: () =>
          import('./features/vouchers/day-book.component').then((m) => m.DayBookComponent),
      },
      {
        path: 'approvals',
        loadComponent: () =>
          import('./features/vouchers/approval-queue.component').then(
            (m) => m.ApprovalQueueComponent,
          ),
      },
      {
        path: 'coa',
        loadComponent: () =>
          import('./features/masters/coa-explorer.component').then((m) => m.CoaExplorerComponent),
      },
      {
        path: 'masters',
        children: MASTERS_CHILD_ROUTES,
      },
      {
        path: 'inventory',
        children: INVENTORY_CHILD_ROUTES,
      },
      {
        path: 'reports',
        children: REPORTS_CHILD_ROUTES,
      },
      {
        path: 'statutory',
        children: STATUTORY_CHILD_ROUTES,
      },
      {
        path: 'banking',
        children: BANKING_CHILD_ROUTES,
      },
      {
        path: 'exchange',
        children: EXCHANGE_CHILD_ROUTES,
      },
      {
        path: 'companies',
        loadComponent: () =>
          import('./features/companies/company-hub.component').then((m) => m.CompanyHubComponent),
      },
      {
        path: 'admin',
        canActivate: [
          systemRoleGuard(['INSTALLATION_OWNER', 'SECURITY_AUDITOR', 'SYSTEM_OPERATOR']),
        ],
        loadComponent: () =>
          import('./features/companies/admin-console.component').then(
            (m) => m.AdminConsoleComponent,
          ),
      },
      {
        path: '',
        pathMatch: 'full',
        redirectTo: 'gateway',
      },
    ],
  },
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'app/gateway',
  },
  {
    path: '**',
    redirectTo: 'app/gateway',
  },
];
