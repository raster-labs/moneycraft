import { Routes } from '@angular/router';
import {
  authenticatedGuard,
  setupRouteGuard,
  systemRoleGuard,
} from './core/auth/permission.guard';

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
