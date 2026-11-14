import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { assertSafeBrowserShortcut } from './collision-guard';
import { ShortcutCommandRegistry } from './shortcut-command.registry';

describe('ShortcutCommandRegistry & BrowserCollisionGuard', () => {
  let registry: ShortcutCommandRegistry;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    registry = TestBed.inject(ShortcutCommandRegistry);
    document.body.innerHTML = '';
  });

  it('strictly forbids binding Ctrl+N, Ctrl+T, Ctrl+W, or Meta+W', () => {
    expect(() => assertSafeBrowserShortcut('Ctrl+N')).toThrow(/prohibited/);
    expect(() => assertSafeBrowserShortcut('Ctrl+T')).toThrow(/prohibited/);
    expect(() => assertSafeBrowserShortcut('Ctrl+W')).toThrow(/prohibited/);
    expect(() =>
      registry.registerCommand({
        id: 'illegal.newWin',
        label: 'Illegal',
        keycap: 'N',
        modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
        primaryBinding: 'Ctrl+N',
        scope: 'GLOBAL',
      }),
    ).toThrow(/prohibited/);
  });

  it('dispatches Alt+G (including macOS Option dead-key KeyG) and respects MODAL > SCREEN > GLOBAL precedence', () => {
    const calls: string[] = [];
    registry.bindHandler('nav.goTo', 'GLOBAL', () => {
      calls.push('GLOBAL');
    });
    const disposeModal = registry.bindHandler('nav.goTo', 'MODAL', () => {
      calls.push('MODAL');
    });

    // Simulate macOS Option+G where event.key is '©' and event.code is 'KeyG'
    const macAltG = new KeyboardEvent('keydown', {
      key: '©',
      code: 'KeyG',
      altKey: true,
      cancelable: true,
    });
    const handled = registry.dispatchKeyboardEvent(macAltG);
    expect(handled).toBe(true);
    expect(macAltG.defaultPrevented).toBe(true);
    expect(calls).toEqual(['MODAL']);

    disposeModal();
    const winAltG = new KeyboardEvent('keydown', {
      key: 'g',
      code: 'KeyG',
      altKey: true,
      cancelable: true,
    });
    registry.dispatchKeyboardEvent(winAltG);
    expect(calls).toEqual(['MODAL', 'GLOBAL']);
  });

  it('opens SmartFind on first Ctrl+F and passes through to native browser Find on double-Ctrl+F', () => {
    let smartFindOpen = false;
    registry.configureSmartFindPassthrough(
      () => smartFindOpen,
      () => {
        smartFindOpen = false;
      },
    );
    registry.bindHandler('search.smartFind', 'GLOBAL', () => {
      smartFindOpen = true;
    });

    const firstCtrlF = new KeyboardEvent('keydown', {
      key: 'f',
      code: 'KeyF',
      ctrlKey: true,
      cancelable: true,
    });
    expect(registry.dispatchKeyboardEvent(firstCtrlF)).toBe(true);
    expect(firstCtrlF.defaultPrevented).toBe(true);
    expect(smartFindOpen).toBe(true);

    const secondCtrlF = new KeyboardEvent('keydown', {
      key: 'f',
      code: 'KeyF',
      ctrlKey: true,
      cancelable: true,
    });
    expect(registry.dispatchKeyboardEvent(secondCtrlF)).toBe(false);
    expect(secondCtrlF.defaultPrevented).toBe(false);
    expect(smartFindOpen).toBe(false);
  });

  it('preserves Ctrl+A text selection inside inputs while allowing Ctrl+Enter to Accept and blocking repeat', () => {
    const acceptSpy = vi.fn();
    registry.bindHandler('screen.accept', 'SCREEN', acceptSpy);

    const input = document.createElement('input');
    input.type = 'text';
    document.body.appendChild(input);

    const ctrlAInInput = new KeyboardEvent('keydown', {
      key: 'a',
      code: 'KeyA',
      ctrlKey: true,
      bubbles: true,
      cancelable: true,
    });
    input.dispatchEvent(ctrlAInInput);
    expect(acceptSpy).not.toHaveBeenCalled();

    const ctrlEnterInInput = new KeyboardEvent('keydown', {
      key: 'Enter',
      code: 'Enter',
      ctrlKey: true,
      bubbles: true,
      cancelable: true,
    });
    input.dispatchEvent(ctrlEnterInInput);
    expect(acceptSpy).toHaveBeenCalledTimes(1);

    // Held auto-repeat must NOT double-post
    const repeatedCtrlEnter = new KeyboardEvent('keydown', {
      key: 'Enter',
      code: 'Enter',
      ctrlKey: true,
      repeat: true,
      bubbles: true,
      cancelable: true,
    });
    input.dispatchEvent(repeatedCtrlEnter);
    expect(acceptSpy).toHaveBeenCalledTimes(1);
  });
});
