import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { APP_ROUTES } from './app.routes';
import { AdminConsoleComponent } from './features/companies/admin-console.component';
import { CompanyHubComponent } from './features/companies/company-hub.component';
import { ErpShellComponent } from './shell/erp-shell.component';
import { GatewayComponent } from './shell/gateway.component';
import { CompanyContextStore } from './state/company-context.store';

describe('Phase 1 ERP Shell, Gateway & Company Hub Screens', () => {
  it('renders Quarantine red banner in ErpShellComponent when QUARANTINED', async () => {
    await TestBed.configureTestingModule({
      imports: [ErpShellComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const companyContext = TestBed.inject(CompanyContextStore);
    companyContext.setSystemStatus('QUARANTINED', 'Simulated MC-AUDIT-2 chain mismatch');

    const fixture = TestBed.createComponent(ErpShellComponent);
    fixture.detectChanges();

    const banner: HTMLElement | null = fixture.nativeElement.querySelector('.mc-quarantine-banner');
    expect(banner).not.toBeNull();
    expect(banner?.textContent).toContain('MC-SYSTEM-QUARANTINED');
    expect(banner?.textContent).toContain('Simulated MC-AUDIT-2 chain mismatch');
  });

  it('supports single-key Gateway mnemonics (C, A, H, V, K, N, B, P, S, R, D)', async () => {
    await TestBed.configureTestingModule({
      imports: [GatewayComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(GatewayComponent);
    fixture.detectChanges();

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'b', cancelable: true }));
    fixture.detectChanges();
    expect(fixture.componentInstance.activeMnemonicKey()).toBe('B');
    expect(fixture.componentInstance.selectedMnemonicItem().suffixText).toBe('alance Sheet');
  });

  it('validates GSTIN Mod-36 live inside CompanyHubComponent F11 Features', async () => {
    await TestBed.configureTestingModule({
      imports: [CompanyHubComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(CompanyHubComponent);
    fixture.componentInstance.startCreateCompany();
    fixture.componentInstance.formStateCode.set('33');
    fixture.componentInstance.featGstin.set('33AABCM1234F1Z4');
    fixture.detectChanges();

    expect(fixture.componentInstance.gstinValidation().valid).toBe(true);

    fixture.componentInstance.featGstin.set('33AABCM1234F1Z0');
    fixture.detectChanges();
    expect(fixture.componentInstance.gstinValidation().valid).toBe(false);
  });

  it('applies TallyPrime Gold Security Level presets and submits granular voucherTypeAllowlist, backdatedDaysLimit, and exportPermitted grants in AdminConsoleComponent', async () => {
    await TestBed.configureTestingModule({
      imports: [AdminConsoleComponent],
      providers: [provideRouter(APP_ROUTES), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const companyContext = TestBed.inject(CompanyContextStore);
    companyContext.setCompanies(
      [
        {
          id: 'cmp-gold-1',
          code: 'MCGOLD',
          legalName: 'MoneyCraft Gold Co',
          mailingName: 'MoneyCraft Gold Co',
          countryCode: 'IN',
          stateCode: '33',
          pinCode: '600001',
          baseCurrencyCode: 'INR',
          currencySymbol: '₹',
          currencyScale: 2,
          financialYearFrom: '2025-04-01',
          booksBeginningFrom: '2025-04-01',
          lastEntryDate: '2025-04-30',
          versionNo: 1,
          features: {
            maintainAccounts: true,
            billWiseEnabled: true,
            costCentresEnabled: true,
            maintainInventory: true,
            inventoryIntegrated: true,
            gstEnabled: true,
            multiGstinEnabled: false,
            gstRegistrationType: 'REGULAR',
            gstin: '33AABCM1234F1Z4',
            gstr1Periodicity: 'MONTHLY',
            gstEffectiveFrom: '2025-04-01',
            tdsEnabled: true,
            mcaEditLogEnforced: true,
          },
        },
      ],
      'cmp-gold-1',
    );

    const fixture = TestBed.createComponent(AdminConsoleComponent);
    fixture.detectChanges();

    // Flush initial principals & oidc-issuers requests
    httpMock
      .match((r) => r.url.includes('/admin/principals') || r.url.includes('/oidc'))
      .forEach((r) => r.flush([]));

    const comp = fixture.componentInstance;
    comp.grantPrincipalId = 'gold_clerk';
    comp.applySecurityLevelPreset('DATA_ENTRY');
    expect(comp.grantRole).toBe('DATA_ENTRY_OPERATOR');
    expect(comp.grantBackdatedDays).toBe(7);
    expect(comp.grantExportPermitted).toBe(false);
    expect(comp.grantVoucherTypeAllowlistInput).toBe('Sales, Purchase, Receipt, Payment');

    // Customize voucher types and backdated limit
    comp.grantBackdatedDays = 5;
    comp.grantVoucherTypeAllowlistInput = 'Sales, Receipt';
    const savePromise = comp.assignCompanyGrant();

    const putGrantReq = httpMock.expectOne(
      (r) => r.method === 'PUT' && r.url.endsWith('/api/v1/admin/principals/gold_clerk/grants'),
    );
    expect(putGrantReq.request.body).toEqual({
      companyId: 'cmp-gold-1',
      roleCode: 'DATA_ENTRY_OPERATOR',
      voucherTypeAllowlist: ['Sales', 'Receipt'],
      backdatedDaysLimit: 5,
      exportPermitted: false,
    });
    putGrantReq.flush({});
    await savePromise;
    expect(comp.statusMessage()).toContain('Updated DATA_ENTRY_OPERATOR grant on company.');
  });
});
