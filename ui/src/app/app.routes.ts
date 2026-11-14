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
