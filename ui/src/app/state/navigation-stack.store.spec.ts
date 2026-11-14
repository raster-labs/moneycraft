import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import {
  MAX_NAVIGATION_STACK_DEPTH,
  NavigationStackStore,
} from './navigation-stack.store';

describe('NavigationStackStore (Depth-8 Non-Destructive Alt+G Modal Stack)', () => {
  let store: NavigationStackStore;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    store = TestBed.inject(NavigationStackStore);
    document.body.innerHTML = '';
  });

  it('pushes up to 8 overlays and blocks a 9th overlay without losing state', () => {
    expect(MAX_NAVIGATION_STACK_DEPTH).toBe(8);

    for (let i = 1; i <= 8; i += 1) {
      const frame = store.push({
        title: `Report #${i}`,
        route: `/app/reports/r${i}`,
        params: { step: String(i) },
        scrollTop: i * 40,
        draftSnapshot: { dirtyLineCount: i },
      });
      expect(frame).not.toBeNull();
      expect(store.depth()).toBe(i);
    }

    expect(store.isAtMaxDepth()).toBe(true);
    const ninth = store.push({
      title: 'Overflow Report #9',
      route: '/app/reports/r9',
    });
    expect(ninth).toBeNull();
    expect(store.depth()).toBe(8);
    expect(store.depthLimitReached()).toBe(true);
    expect(store.topFrame()?.title).toBe('Report #8');
  });

  it('restores exact scroll position, DOM focus, and dirty draft state in < 16ms on pop()', () => {
    const scrollContainer = document.createElement('div');
    scrollContainer.setAttribute('data-mc-workspace-scroll', '');
    const voucherInput = document.createElement('input');
    voucherInput.id = 'voucher-row-3-amount';
    scrollContainer.appendChild(voucherInput);
    document.body.appendChild(scrollContainer);

    scrollContainer.scrollTop = 240;
    voucherInput.focus();

    let restoredDraft: unknown = null;
    store.registerDraftLifecycleHooks(
      () => ({ narration: 'Unsaved Sales Invoice #19', total: '114624.00' }),
      (snap) => {
        restoredDraft = snap;
      },
    );

    const pushed = store.push({
      title: 'Balance Sheet',
      route: '/app/gateway',
      params: { panel: 'balance-sheet' },
      scrollTop: 240,
    });
    expect(pushed?.activeElementId).toBe('voucher-row-3-amount');

    // Simulate user scrolling and focusing inside the overlay
    scrollContainer.scrollTop = 0;
    const overlayInput = document.createElement('input');
    document.body.appendChild(overlayInput);
    overlayInput.focus();
    expect(document.activeElement).toBe(overlayInput);

    const t0 = performance.now();
    const popped = store.pop();
    const elapsedMs = performance.now() - t0;

    expect(popped?.title).toBe('Balance Sheet');
    expect(elapsedMs).toBeLessThan(16);
    expect(scrollContainer.scrollTop).toBe(240);
    expect(document.activeElement).toBe(voucherInput);
    expect(restoredDraft).toEqual({
      narration: 'Unsaved Sales Invoice #19',
      total: '114624.00',
    });
  });
});
