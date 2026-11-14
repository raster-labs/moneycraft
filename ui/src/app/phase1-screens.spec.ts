import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { APP_ROUTES } from './app.routes';
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
});
