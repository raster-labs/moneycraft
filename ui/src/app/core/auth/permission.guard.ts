import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { CompanyContextStore } from '../../state/company-context.store';
import { AuthStore, SystemRole } from './auth.store';

export const setupRouteGuard: CanActivateFn = async () => {
  const companyContext = inject(CompanyContextStore);
  const router = inject(Router);

  if (!companyContext.statusLoaded()) {
    await companyContext.fetchSystemStatus();
  }

  if (companyContext.systemStatus() === 'UNINITIALISED') {
    return true;
  }

  return router.createUrlTree(['/login']);
};

export const authenticatedGuard: CanActivateFn = async () => {
  const companyContext = inject(CompanyContextStore);
  const authStore = inject(AuthStore);
  const router = inject(Router);

  if (!companyContext.statusLoaded()) {
    await companyContext.fetchSystemStatus();
  }

  if (companyContext.systemStatus() === 'UNINITIALISED') {
    return router.createUrlTree(['/setup']);
  }

  if (!authStore.sessionChecked()) {
    await authStore.refreshSession();
  }

  if (authStore.isAuthenticated()) {
    return true;
  }

  return router.createUrlTree(['/login']);
};

export function systemRoleGuard(requiredRoles: readonly SystemRole[]): CanActivateFn {
  return async () => {
    const authStore = inject(AuthStore);
    const router = inject(Router);

    if (!authStore.sessionChecked()) {
      await authStore.refreshSession();
    }

    if (!authStore.isAuthenticated()) {
      return router.createUrlTree(['/login']);
    }

    const hasAny = requiredRoles.some((role) => authStore.hasSystemRole(role));
    return hasAny ? true : router.createUrlTree(['/app/gateway']);
  };
}
