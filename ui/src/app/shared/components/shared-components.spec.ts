import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { etagInterceptor } from '../../core/http/etag.interceptor';
import { idempotencyInterceptor } from '../../core/http/idempotency.interceptor';
import { problemDetailsInterceptor } from '../../core/http/problem-details.interceptor';
import { RUPEE_SYMBOL } from '../../core/money/exact-decimal';
import { CompanyContextStore } from '../../state/company-context.store';
import { AmountCellComponent } from './amount-cell/amount-cell.component';
import { CodeBadgeComponent } from './code-badge/code-badge.component';
import { TypeaheadSelectComponent } from './typeahead-select/typeahead-select.component';

describe('Shared ERP Components & HTTP Interceptors', () => {
  it('raises Tally-authentic Spelling Error modal on non-matching Enter and emits Alt+C Create', async () => {
    await TestBed.configureTestingModule({
      imports: [TypeaheadSelectComponent],
    }).compileComponents();

    const fixture = TestBed.createComponent(TypeaheadSelectComponent);
    fixture.componentRef.setInput('listTitle', 'List of Ledger Accounts');
    fixture.componentRef.setInput('options', [
      { id: '1', label: 'Cash' },
      { id: '2', label: 'HDFC Current A/c' },
    ]);
    const createSpy = vi.fn();
    fixture.componentInstance.createRequested.subscribe(createSpy);
    fixture.detectChanges();

    const inputEl: HTMLInputElement = fixture.nativeElement.querySelector('input');
    inputEl.value = 'NonExistentLedger';
    inputEl.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    inputEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
    fixture.detectChanges();

    expect(fixture.componentInstance.spellingErrorOpen()).toBe(true);
    const alertText = fixture.nativeElement.textContent as string;
    expect(alertText).toContain('Spelling Error!');

    // Alt+C from Spelling Error triggers inline master creation
    inputEl.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'c', code: 'KeyC', altKey: true, cancelable: true }),
    );
    fixture.detectChanges();
    expect(createSpy).toHaveBeenCalledWith('NonExistentLedger');
  });

  it('never renders U+20B9 ₹ inside .mc-code CodeBadgeComponent', async () => {
    await TestBed.configureTestingModule({
      imports: [CodeBadgeComponent],
    }).compileComponents();

    const fixture = TestBed.createComponent(CodeBadgeComponent);
    fixture.componentRef.setInput('code', `INV-01O5-${RUPEE_SYMBOL}100`);
    fixture.detectChanges();

    const badgeEl: HTMLElement = fixture.nativeElement.querySelector('.mc-code');
    expect(badgeEl.textContent).not.toContain(RUPEE_SYMBOL);
    expect(badgeEl.textContent).toContain('INV-01O5-INR 100');
  });

  it('evaluates inline calculator inside AmountCellComponent and commits wire decimal', async () => {
    await TestBed.configureTestingModule({
      imports: [AmountCellComponent],
    }).compileComponents();

    const fixture = TestBed.createComponent(AmountCellComponent);
    const committedSpy = vi.fn();
    fixture.componentInstance.valueCommitted.subscribe(committedSpy);
    fixture.detectChanges();

    fixture.componentInstance.openCalculator('(1250 * 18%) + 1250');
    fixture.detectChanges();

    fixture.componentInstance.onCalcKeyDown(
      new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }),
    );
    fixture.detectChanges();

    expect(committedSpy).toHaveBeenCalledWith('1475.00');
    // Immediately after commit, focus returns to the input in raw-edit mode ('1475.00');
    // blurring formats the cell into Indian grouping ('1,475.00').
    expect(fixture.componentInstance.displayText()).toBe('1475.00');
    fixture.componentInstance.onBlur();
    fixture.detectChanges();
    expect(fixture.componentInstance.displayText()).toBe('1,475.00');
  });

  it('attaches Idempotency-Key + If-Match headers and detects 503 MC-SYSTEM-QUARANTINED', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withInterceptors([idempotencyInterceptor, etagInterceptor, problemDetailsInterceptor]),
        ),
        provideHttpClientTesting(),
      ],
    });

    const http = TestBed.inject(HttpClient);
    const httpMock = TestBed.inject(HttpTestingController);
    const companyContext = TestBed.inject(CompanyContextStore);

    // 1. GET caches ETag
    const getPromise = firstValueFrom(http.get('/api/v1/companies/c1'));
    const getReq = httpMock.expectOne('/api/v1/companies/c1');
    expect(getReq.request.headers.has('Idempotency-Key')).toBe(false);
    getReq.flush({ id: 'c1' }, { headers: { ETag: '"v3"' } });
    await getPromise;

    // 2. PUT attaches both Idempotency-Key and If-Match: "v3"
    const putPromise = firstValueFrom(http.put('/api/v1/companies/c1', { legalName: 'Updated' }));
    const putReq = httpMock.expectOne('/api/v1/companies/c1');
    expect(putReq.request.headers.get('If-Match')).toBe('"v3"');
    expect(putReq.request.headers.get('Idempotency-Key')).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    );
    putReq.flush(
      {
        title: 'System Quarantined',
        status: 503,
        detail: 'Audit tail divergence',
        code: 'MC-SYSTEM-QUARANTINED',
      },
      { status: 503, statusText: 'Service Unavailable' },
    );

    await expect(putPromise).rejects.toThrow(/MC-SYSTEM-QUARANTINED/);
    expect(companyContext.quarantined()).toBe(true);
    httpMock.verify();
  });
});
