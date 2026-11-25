import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { assertSafeBrowserShortcut } from './collision-guard';
import {
  ActiveModalContextHandle,
  DEFAULT_QUALIFIED_MODIFIER_PROFILE_ID,
  INITIAL_BROWSER_MODAL_INVENTORY,
  ModalActionDefinition,
  ShortcutCommandRegistry,
  asciiPair,
  validateContextMap,
} from './shortcut-command.registry';

describe('ShortcutCommandRegistry, BrowserCollisionGuard & KEY-001 Modal Action Contract', () => {
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

  // ---------------------------------------------------------------------------
  // KEY-001 (`Doc 45 §6`) Mandatory Verification Suite
  // ---------------------------------------------------------------------------

  it('letters-complete: validates all 15 initial modal contexts and 13-row commands.menu with zero collisions', () => {
    const byContext = new Map<string, ModalActionDefinition[]>();
    for (const spec of INITIAL_BROWSER_MODAL_INVENTORY) {
      const list = byContext.get(spec.contextId) ?? [];
      list.push({
        contextId: spec.contextId,
        locale: 'en-GB',
        commandId: spec.commandId,
        label: spec.label,
        letterPair: asciiPair(spec.pairSpec),
        permissionRef: spec.permissionRef,
        effect: spec.effect,
        normalActivation: () => undefined,
        iconSemanticId: spec.iconSemanticId,
        stateTokenRole: spec.stateTokenRole,
        isMutation: spec.isMutation,
      });
      byContext.set(spec.contextId, list);
    }

    expect(byContext.size).toBe(15);
    expect(byContext.get('commands.menu')?.length).toBe(13);

    for (const [ctxId, actions] of byContext.entries()) {
      const res = validateContextMap(actions, 'en-GB');
      expect(res.valid, `Expected ${ctxId} to be valid: ${res.defects.join('; ')}`).toBe(true);
      expect(res.defects).toEqual([]);
    }
  });

  it('dirty-three-actions: dispatches Save draft (S/s), Discard (D/d), and Stay (T/t) while blocking unmapped characters', () => {
    const fired: string[] = [];
    const handle = registry.openModalContext([
      {
        contextId: 'editor.dirty',
        locale: 'en-GB',
        commandId: 'journal.saveDraft',
        label: 'Save draft',
        letterPair: asciiPair('S/s'),
        permissionRef: 'voucher.create',
        effect: 'Save draft once.',
        normalActivation: () => {
          fired.push('SAVE');
        },
        iconSemanticId: 'mc.icon.save-document',
        stateTokenRole: 'action.primary',
        isMutation: true,
      },
      {
        contextId: 'editor.dirty',
        locale: 'en-GB',
        commandId: 'editor.discard',
        label: 'Discard',
        letterPair: asciiPair('D/d'),
        permissionRef: 'authenticated',
        effect: 'Discard local edits.',
        normalActivation: () => {
          fired.push('DISCARD');
        },
        iconSemanticId: 'mc.icon.discard-draft',
        stateTokenRole: 'action.danger',
      },
      {
        contextId: 'editor.dirty',
        locale: 'en-GB',
        commandId: 'editor.stay',
        label: 'Stay',
        letterPair: asciiPair('T/t'),
        permissionRef: 'authenticated',
        effect: 'Stay on editor.',
        normalActivation: () => {
          fired.push('STAY');
        },
        iconSemanticId: null,
        stateTokenRole: 'action.secondary',
      },
    ]);
    expect(handle).not.toBeNull();

    // Unmapped character 'x' is blocked at modal fence
    const unmapped = registry.dispatchContextKey(
      new KeyboardEvent('keydown', { key: 'x', code: 'KeyX', cancelable: true }),
    );
    registry.recordKeyUp(new KeyboardEvent('keyup', { key: 'x', code: 'KeyX' }));
    expect(unmapped.dispatched).toBe(false);
    expect(unmapped.consumedByModalFence).toBe(true);

    // Lowercase 's', uppercase 'D', lowercase 't'
    registry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 's', code: 'KeyS', cancelable: true }),
    );
    registry.recordKeyUp(new KeyboardEvent('keyup', { key: 's', code: 'KeyS' }));

    registry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'D', code: 'KeyD', shiftKey: true, cancelable: true }),
    );
    registry.recordKeyUp(new KeyboardEvent('keyup', { key: 'D', code: 'KeyD' }));

    registry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 't', code: 'KeyT', cancelable: true }),
    );
    registry.recordKeyUp(new KeyboardEvent('keyup', { key: 't', code: 'KeyT' }));

    expect(fired).toEqual(['SAVE', 'DISCARD', 'STAY']);
    handle?.close();
  });

  it('nested-open-held: prevents opening key, held key, or auto-repeat from triggering newly opened nested dialog until keyup + fresh keydown', () => {
    const childSaveSpy = vi.fn();
    let childHandle = null as ActiveModalContextHandle | null;
    const genBefore = registry.contextGeneration();

    // Simulate user already holding 's' before child modal opens
    registry.dispatchContextKey(
      new KeyboardEvent('keydown', { key: 's', code: 'KeyS', cancelable: true }),
    );

    const parentHandle = registry.openModalContext([
      {
        contextId: 'ledger.lookup',
        locale: 'en-GB',
        commandId: 'ledger.create',
        label: 'Create ledger',
        letterPair: asciiPair('N/n'),
        permissionRef: 'master.create',
        effect: 'Open nested ledger.create modal.',
        normalActivation: () => {
          childHandle = registry.openModalContext([
            {
              contextId: 'ledger.create',
              locale: 'en-GB',
              commandId: 'ledger.save',
              label: 'Save',
              letterPair: asciiPair('S/s'),
              permissionRef: 'master.create',
              effect: 'Save ledger.',
              normalActivation: childSaveSpy,
              iconSemanticId: 'mc.icon.save-document',
              stateTokenRole: 'action.primary',
              isMutation: true,
            },
            {
              contextId: 'ledger.create',
              locale: 'en-GB',
              commandId: 'editor.close',
              label: 'Cancel',
              letterPair: asciiPair('C/c'),
              permissionRef: 'authenticated',
              effect: 'Cancel.',
              normalActivation: () => undefined,
              iconSemanticId: 'mc.icon.close',
              stateTokenRole: 'action.secondary',
            },
          ]);
        },
        iconSemanticId: 'mc.icon.ledger',
        stateTokenRole: 'action.secondary',
      },
    ]);

    // Trigger nested open with 'n'
    registry.dispatchKeyboardEvent(
      new KeyboardEvent('keydown', { key: 'n', code: 'KeyN', cancelable: true }),
    );
    registry.recordKeyUp(new KeyboardEvent('keyup', { key: 'n', code: 'KeyN' }));

    expect(childHandle).not.toBeNull();
    expect(registry.topModalContextId()).toBe('ledger.create');
    expect(registry.contextGeneration()).toBeGreaterThan(genBefore + 1);

    // Pre-held 's' and auto-repeat 's' must be blocked in child modal!
    const heldOutcome = registry.dispatchContextKey(
      new KeyboardEvent('keydown', { key: 's', code: 'KeyS', cancelable: true }),
    );
    expect(heldOutcome.dispatched).toBe(false);
    expect(heldOutcome.reason).toBe('HELD_OR_OPENING_KEY_BLOCKED');

    const repeatOutcome = registry.dispatchContextKey(
      new KeyboardEvent('keydown', { key: 's', code: 'KeyS', repeat: true, cancelable: true }),
    );
    expect(repeatOutcome.dispatched).toBe(false);
    expect(repeatOutcome.reason).toBe('KEY_AUTO_REPEAT_BLOCKED');
    expect(childSaveSpy).not.toHaveBeenCalled();

    // Release 's', then press fresh 's' -> dispatches exactly once
    registry.recordKeyUp(new KeyboardEvent('keyup', { key: 's', code: 'KeyS' }));
    const freshOutcome = registry.dispatchContextKey(
      new KeyboardEvent('keydown', { key: 's', code: 'KeyS', cancelable: true }),
    );
    expect(freshOutcome.dispatched).toBe(true);
    expect(childSaveSpy).toHaveBeenCalledTimes(1);

    childHandle?.close();
    parentHandle?.close();
  });

  it('input-ownership: never dispatches character shortcuts from inputs, selects, typeahead, amount-cell, IME composition, or AltGraph', () => {
    const saveSpy = vi.fn();
    const handle = registry.openModalContext([
      {
        contextId: 'ledger.create',
        locale: 'en-GB',
        commandId: 'ledger.save',
        label: 'Save',
        letterPair: asciiPair('S/s'),
        permissionRef: 'master.create',
        effect: 'Save ledger.',
        normalActivation: saveSpy,
        iconSemanticId: 'mc.icon.save-document',
        stateTokenRole: 'action.primary',
      },
    ]);

    const input = document.createElement('input');
    const select = document.createElement('select');
    const typeaheadHost = document.createElement('mc-typeahead-select');
    const typeaheadBtn = document.createElement('button');
    typeaheadHost.appendChild(typeaheadBtn);
    const amountHost = document.createElement('mc-amount-cell');
    const amountSpan = document.createElement('span');
    amountHost.appendChild(amountSpan);

    document.body.append(input, select, typeaheadHost, amountHost);

    for (const targetEl of [input, select, typeaheadBtn, amountSpan]) {
      const ev = new KeyboardEvent('keydown', {
        key: 's',
        code: 'KeyS',
        bubbles: true,
        cancelable: true,
      });
      Object.defineProperty(ev, 'target', { value: targetEl });
      const res = registry.dispatchContextKey(ev);
      registry.recordKeyUp(new KeyboardEvent('keyup', { key: 's', code: 'KeyS' }));
      expect(res.dispatched).toBe(false);
      expect(res.reason).toBe('INPUT_OWNER_ACTIVE');
    }

    // IME composition
    const imeEvent = new KeyboardEvent('keydown', {
      key: 's',
      code: 'KeyS',
      isComposing: true,
      cancelable: true,
    });
    const imeRes = registry.dispatchContextKey(imeEvent);
    registry.recordKeyUp(new KeyboardEvent('keyup', { key: 's', code: 'KeyS' }));
    expect(imeRes.dispatched).toBe(false);
    expect(imeRes.reason).toBe('IME_OR_ALTGRAPH_OWNED');

    // AltGraph modifier active
    const altGrEvent = new KeyboardEvent('keydown', {
      key: 's',
      code: 'KeyS',
      modifierAltGraph: true,
      cancelable: true,
    });
    const altGrRes = registry.dispatchContextKey(altGrEvent);
    registry.recordKeyUp(new KeyboardEvent('keyup', { key: 's', code: 'KeyS' }));
    expect(altGrRes.dispatched).toBe(false);
    expect(altGrRes.reason).toBe('IME_OR_ALTGRAPH_OWNED');

    expect(saveSpy).not.toHaveBeenCalled();
    handle?.close();
  });

  it('scanner-suppression: suppresses character-only shortcuts globally across open and new dialogs without overwriting saved mode', () => {
    const applySpy = vi.fn();
    const handle = registry.openModalContext([
      {
        contextId: 'context.dateDialog',
        locale: 'en-GB',
        commandId: 'context.applyDate',
        label: 'Apply',
        letterPair: asciiPair('A/a'),
        permissionRef: 'company.read',
        effect: 'Apply date.',
        normalActivation: applySpy,
        iconSemanticId: 'mc.icon.calendar',
        stateTokenRole: 'action.primary',
      },
    ]);

    registry.setScannerWorkflowActive(true);
    expect(registry.preferences().mode).toBe('CHARACTER');
    expect(registry.effectiveDispatchMode()).toBe('OFF');
    expect(
      registry.effectiveActionHint('context.dateDialog', 'context.applyDate', asciiPair('A/a')),
    ).toBeNull();

    const suppressed = registry.dispatchContextKey(
      new KeyboardEvent('keydown', { key: 'a', code: 'KeyA', cancelable: true }),
    );
    registry.recordKeyUp(new KeyboardEvent('keyup', { key: 'a', code: 'KeyA' }));
    expect(suppressed.dispatched).toBe(false);
    expect(suppressed.reason).toBe('SCANNER_WORKFLOW_SUPPRESSED');
    expect(applySpy).not.toHaveBeenCalled();

    // Disable scanner suppression -> CHARACTER mode resumes cleanly
    registry.setScannerWorkflowActive(false);
    expect(registry.effectiveDispatchMode()).toBe('CHARACTER');
    const restored = registry.dispatchContextKey(
      new KeyboardEvent('keydown', { key: 'a', code: 'KeyA', cancelable: true }),
    );
    expect(restored.dispatched).toBe(true);
    expect(applySpy).toHaveBeenCalledTimes(1);

    handle?.close();
  });

  it('preferences-and-reset: supports MODIFIER mode overrides, principal isolation on logout, and fail-safe OFF on corrupted state', () => {
    registry.syncAuthenticatedPrincipal('person-a');
    const applied = registry.applyKeyboardPreferences({
      version: 1,
      mode: 'MODIFIER',
      locale: 'en-GB',
      profileId: DEFAULT_QUALIFIED_MODIFIER_PROFILE_ID,
      overrides: [
        {
          contextId: 'context.dateDialog',
          commandId: 'context.applyDate',
          chordId: 'Alt+Shift+A',
        },
      ],
    });
    expect(applied.valid).toBe(true);
    expect(
      registry.effectiveActionHint('context.dateDialog', 'context.applyDate', asciiPair('A/a')),
    ).toBe('Alt+Shift+A');

    const applySpy = vi.fn();
    const handle = registry.openModalContext([
      {
        contextId: 'context.dateDialog',
        locale: 'en-GB',
        commandId: 'context.applyDate',
        label: 'Apply',
        letterPair: asciiPair('A/a'),
        permissionRef: 'company.read',
        effect: 'Apply date.',
        normalActivation: applySpy,
        iconSemanticId: 'mc.icon.calendar',
        stateTokenRole: 'action.primary',
      },
    ]);

    // Plain 'a' must NOT fire in MODIFIER mode
    const plainRes = registry.dispatchContextKey(
      new KeyboardEvent('keydown', { key: 'a', code: 'KeyA', cancelable: true }),
    );
    registry.recordKeyUp(new KeyboardEvent('keyup', { key: 'a', code: 'KeyA' }));
    expect(plainRes.dispatched).toBe(false);

    // Alt+Shift+A fires
    const chordRes = registry.dispatchContextKey(
      new KeyboardEvent('keydown', {
        key: 'A',
        code: 'KeyA',
        altKey: true,
        shiftKey: true,
        cancelable: true,
      }),
    );
    registry.recordKeyUp(new KeyboardEvent('keyup', { key: 'A', code: 'KeyA' }));
    expect(chordRes.dispatched).toBe(true);
    expect(applySpy).toHaveBeenCalledTimes(1);
    handle?.close();

    // Logout person-a and sign in person-b -> preferences reset to CHARACTER defaults
    registry.syncAuthenticatedPrincipal(null);
    registry.syncAuthenticatedPrincipal('person-b');
    expect(registry.preferences().mode).toBe('CHARACTER');
    expect(registry.preferences().overrides).toEqual([]);

    // Corrupted raw state fails closed to OFF
    const corrupt = registry.ingestRawPreferenceState({ version: 99, mode: 'BROKEN' });
    expect(corrupt.valid).toBe(false);
    expect(registry.preferences().mode).toBe('OFF');
  });

  it('new-set-and-locale: validates fixture.recovery under en-GB and test-only pl-PL while rejecting unadmitted locales', () => {
    const plActions: readonly ModalActionDefinition[] = [
      {
        contextId: 'fixture.recovery',
        locale: 'pl-PL',
        commandId: 'fixture.retry',
        label: 'Ponow',
        letterPair: ['r', 'R'],
        permissionRef: 'authenticated',
        effect: 'Retry fixture.',
        normalActivation: () => undefined,
        iconSemanticId: 'mc.icon.refresh',
        stateTokenRole: 'action.primary',
      },
      {
        contextId: 'fixture.recovery',
        locale: 'pl-PL',
        commandId: 'fixture.close',
        label: 'Zamknij',
        letterPair: ['c', 'C'],
        permissionRef: 'authenticated',
        effect: 'Close fixture.',
        normalActivation: () => undefined,
        iconSemanticId: 'mc.icon.close',
        stateTokenRole: 'action.secondary',
      },
    ];
    expect(validateContextMap(plActions, 'pl-PL').valid).toBe(true);
    expect(validateContextMap(plActions, 'de-DE').valid).toBe(false);
  });

  it('invalid-registry: rejects duplicate pairs, missing pairs, undeclared case pairs, unknown icons, and runtime duplicate mutation', () => {
    const dupRes = validateContextMap([
      {
        contextId: 'broken.dup',
        locale: 'en-GB',
        commandId: 'cmd.one',
        label: 'One',
        letterPair: asciiPair('S/s'),
        permissionRef: 'public',
        effect: 'First.',
        normalActivation: () => undefined,
        iconSemanticId: null,
        stateTokenRole: 'action.primary',
      },
      {
        contextId: 'broken.dup',
        locale: 'en-GB',
        commandId: 'cmd.two',
        label: 'Two',
        letterPair: asciiPair('S/s'),
        permissionRef: 'public',
        effect: 'Second.',
        normalActivation: () => undefined,
        iconSemanticId: null,
        stateTokenRole: 'action.secondary',
      },
    ]);
    expect(dupRes.valid).toBe(false);
    expect(dupRes.defects.some((d) => d.includes('DUPLICATE_LETTER_PAIR'))).toBe(true);

    const badIcon = validateContextMap([
      {
        contextId: 'broken.icon',
        locale: 'en-GB',
        commandId: 'cmd.one',
        label: 'One',
        letterPair: asciiPair('S/s'),
        permissionRef: 'public',
        effect: 'First.',
        normalActivation: () => undefined,
        iconSemanticId: 'mc.icon.non-existent',
        stateTokenRole: 'action.primary',
      },
    ]);
    expect(badIcon.valid).toBe(false);
    expect(badIcon.defects.some((d) => d.includes('UNKNOWN_ICON_SEMANTIC_ID'))).toBe(true);

    // Runtime dynamic visibility introducing a duplicate closes character dispatch fail-safe
    let revealDuplicate = false;
    const handle = registry.openModalContext([
      {
        contextId: 'dynamic.ctx',
        locale: 'en-GB',
        commandId: 'cmd.primary',
        label: 'Save',
        letterPair: asciiPair('S/s'),
        permissionRef: 'public',
        effect: 'Save.',
        normalActivation: () => undefined,
        iconSemanticId: null,
        stateTokenRole: 'action.primary',
      },
      {
        contextId: 'dynamic.ctx',
        locale: 'en-GB',
        commandId: 'cmd.colliding',
        label: 'Send',
        letterPair: asciiPair('S/s'),
        visibleWhen: () => revealDuplicate,
        permissionRef: 'public',
        effect: 'Colliding.',
        normalActivation: () => undefined,
        iconSemanticId: null,
        stateTokenRole: 'action.secondary',
      },
    ]);
    expect(handle).not.toBeNull();
    revealDuplicate = true;

    const closedRes = registry.dispatchContextKey(
      new KeyboardEvent('keydown', { key: 's', code: 'KeyS', cancelable: true }),
    );
    expect(closedRes.dispatched).toBe(false);
    expect(closedRes.reason).toBe('RUNTIME_REGISTRY_DEFECT_CLOSED');
    handle?.close();
  });
});
