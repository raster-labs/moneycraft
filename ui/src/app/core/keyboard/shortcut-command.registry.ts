import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import {
  assertSafeBrowserShortcut,
  extractNormalizedChordFromEvent,
  isEditableTextElement,
  normalizeShortcutString,
} from './collision-guard';

export type ShortcutScope = 'GLOBAL' | 'SCREEN' | 'EDITOR' | 'MODAL';
export type KeycapModifierStyle = 'PLAIN' | 'ALT_SINGLE_UNDERLINE' | 'CTRL_DOUBLE_UNDERLINE';

export interface ShortcutCommandDescriptor {
  readonly id: string;
  readonly label: string;
  readonly keycap: string;
  readonly modifierStyle: KeycapModifierStyle;
  readonly primaryBinding: string;
  readonly alternateBindings?: readonly string[];
  readonly scope: ShortcutScope;
  readonly allowRepeat?: boolean;
  readonly allowInEditableInput?: boolean;
  readonly requiredPermission?: string;
}

interface BoundCommandHandler {
  readonly registrationId: number;
  readonly commandId: string;
  readonly scope: ShortcutScope;
  readonly isEnabled?: () => boolean;
  readonly handler: (event: KeyboardEvent) => void | boolean;
}

const SCOPE_PRIORITY: Readonly<Record<ShortcutScope, number>> = {
  MODAL: 40,
  EDITOR: 30,
  SCREEN: 20,
  GLOBAL: 10,
};

const DOUBLE_CTRL_F_WINDOW_MS = 700;

export const DEFAULT_ERP_SHORTCUT_COMMANDS: readonly ShortcutCommandDescriptor[] = [
  {
    id: 'nav.goTo',
    label: 'Go To',
    keycap: 'G',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+G',
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
  {
    id: 'nav.switchTo',
    label: 'Switch To',
    keycap: 'G',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+G',
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
  {
    id: 'search.smartFind',
    label: 'Find',
    keycap: 'F',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+F',
    alternateBindings: ['Ctrl+F'],
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.autofill',
    label: 'Autofill',
    keycap: 'F',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+Alt+F',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'context.changeDate',
    label: 'Date',
    keycap: 'F2',
    modifierStyle: 'PLAIN',
    primaryBinding: 'F2',
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
  {
    id: 'context.changePeriod',
    label: 'Period',
    keycap: 'F2',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+F2',
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
  {
    id: 'context.selectCompany',
    label: 'Company',
    keycap: 'F3',
    modifierStyle: 'PLAIN',
    primaryBinding: 'F3',
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.contra',
    label: 'Contra',
    keycap: 'F4',
    modifierStyle: 'PLAIN',
    primaryBinding: 'F4',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.payment',
    label: 'Payment',
    keycap: 'F5',
    modifierStyle: 'PLAIN',
    primaryBinding: 'F5',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.receipt',
    label: 'Receipt',
    keycap: 'F6',
    modifierStyle: 'PLAIN',
    primaryBinding: 'F6',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.journal',
    label: 'Journal',
    keycap: 'F7',
    modifierStyle: 'PLAIN',
    primaryBinding: 'F7',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.sales',
    label: 'Sales',
    keycap: 'F8',
    modifierStyle: 'PLAIN',
    primaryBinding: 'F8',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.purchase',
    label: 'Purchase',
    keycap: 'F9',
    modifierStyle: 'PLAIN',
    primaryBinding: 'F9',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.otherVouchers',
    label: 'Other Vouchers',
    keycap: 'F10',
    modifierStyle: 'PLAIN',
    primaryBinding: 'F10',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.debitNote',
    label: 'Debit Note',
    keycap: 'F5',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+F5',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.creditNote',
    label: 'Credit Note',
    keycap: 'F6',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+F6',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.stockJournal',
    label: 'Stock Journal',
    keycap: 'F7',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+F7',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.physicalStock',
    label: 'Physical Stock',
    keycap: 'F7',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+F7',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.salesOrder',
    label: 'Sales Order',
    keycap: 'F8',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+F8',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.purchaseOrder',
    label: 'Purchase Order',
    keycap: 'F9',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+F9',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.deliveryNote',
    label: 'Delivery Note',
    keycap: 'F8',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+F8',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.receiptNote',
    label: 'Receipt Note',
    keycap: 'F9',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+F9',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'company.features',
    label: 'Features',
    keycap: 'F11',
    modifierStyle: 'PLAIN',
    primaryBinding: 'F11',
    alternateBindings: ['Shift+F11'],
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
  {
    id: 'screen.configure',
    label: 'Configure',
    keycap: 'F12',
    modifierStyle: 'PLAIN',
    primaryBinding: 'F12',
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.changeMode',
    label: 'Change Mode',
    keycap: 'H',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+H',
    alternateBindings: ['Alt+H'],
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.toggleOptional',
    label: 'Optional',
    keycap: 'L',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+L',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'inline.createOrCalc',
    label: 'Create / Calc',
    keycap: 'C',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+C',
    scope: 'EDITOR',
    allowInEditableInput: true,
  },
  {
    id: 'screen.accept',
    label: 'Accept',
    keycap: 'A',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+A',
    alternateBindings: ['Ctrl+Enter'],
    scope: 'SCREEN',
    allowRepeat: false,
    allowInEditableInput: true,
  },
  {
    id: 'register.addVoucher',
    label: 'Add Vch',
    keycap: 'A',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+A',
    scope: 'SCREEN',
    allowInEditableInput: false,
  },
  {
    id: 'register.duplicateVoucher',
    label: 'Duplicate',
    keycap: '2',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+2',
    alternateBindings: ['Alt+Insert'],
    scope: 'SCREEN',
    allowInEditableInput: false,
  },
  {
    id: 'register.insertVoucher',
    label: 'Insert Vch',
    keycap: 'I',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+I',
    scope: 'SCREEN',
    allowInEditableInput: false,
  },
  {
    id: 'register.cancelVoucher',
    label: 'Cancel Vch',
    keycap: 'X',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+X',
    scope: 'SCREEN',
    allowInEditableInput: false,
  },
  {
    id: 'register.deleteVoucher',
    label: 'Delete',
    keycap: 'D',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+D',
    scope: 'SCREEN',
    allowInEditableInput: false,
  },
  {
    id: 'register.removeLine',
    label: 'Remove Line',
    keycap: 'R',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+R',
    scope: 'SCREEN',
    allowInEditableInput: false,
  },
  {
    id: 'register.restoreLine',
    label: 'Restore Line',
    keycap: 'U',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+U',
    scope: 'SCREEN',
    allowInEditableInput: false,
  },
  {
    id: 'audit.editLog',
    label: 'Edit Log',
    keycap: 'Q',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+Q',
    alternateBindings: ['Ctrl+O'],
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'report.toggleDetailed',
    label: 'Detailed',
    keycap: 'F1',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+F1',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'report.basisOfValues',
    label: 'Basis of Values',
    keycap: 'B',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+B',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'report.exceptions',
    label: 'Exceptions',
    keycap: 'J',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+J',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.taxAnalysis',
    label: 'Tax Analysis',
    keycap: 'I',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+I',
    alternateBindings: ['Ctrl+Alt+I'],
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'menu.company',
    label: 'Company',
    keycap: 'K',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+K',
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
  {
    id: 'menu.data',
    label: 'Data',
    keycap: 'Y',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+Y',
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
  {
    id: 'menu.exchange',
    label: 'Exchange',
    keycap: 'Z',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+Z',
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
  {
    id: 'menu.import',
    label: 'Import',
    keycap: 'O',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+O',
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
  {
    id: 'menu.export',
    label: 'Export',
    keycap: 'E',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+E',
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
  {
    id: 'menu.print',
    label: 'Print',
    keycap: 'P',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+P',
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
  {
    id: 'nav.escape',
    label: 'Quit / Close',
    keycap: 'Esc',
    modifierStyle: 'PLAIN',
    primaryBinding: 'Escape',
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
];

@Injectable({ providedIn: 'root' })
export class ShortcutCommandRegistry {
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);

  private readonly descriptorsMap = signal<ReadonlyMap<string, ShortcutCommandDescriptor>>(
    new Map(),
  );
  private readonly chordIndex = new Map<string, string[]>();
  private readonly handlers: BoundCommandHandler[] = [];
  private nextRegistrationId = 1;
  private lastCtrlFTimestamp = 0;
  private smartFindOpenCheck: () => boolean = () => false;
  private smartFindCloseHook: () => void = () => undefined;

  readonly commands = computed(() => Array.from(this.descriptorsMap().values()));
  readonly lastDispatchedCommandId = signal<string | null>(null);

  constructor() {
    for (const cmd of DEFAULT_ERP_SHORTCUT_COMMANDS) {
      this.registerCommand(cmd);
    }

    const listener = (event: KeyboardEvent): void => {
      this.dispatchKeyboardEvent(event);
    };
    this.document.addEventListener('keydown', listener, { capture: true });
    this.destroyRef.onDestroy(() => {
      this.document.removeEventListener('keydown', listener, { capture: true });
    });
  }

  configureSmartFindPassthrough(isOpen: () => boolean, closeSmartFind: () => void): void {
    this.smartFindOpenCheck = isOpen;
    this.smartFindCloseHook = closeSmartFind;
  }

  registerCommand(descriptor: ShortcutCommandDescriptor): void {
    const primary = assertSafeBrowserShortcut(descriptor.primaryBinding);
    const alternates = (descriptor.alternateBindings ?? []).map((b) =>
      assertSafeBrowserShortcut(b),
    );

    const normalizedDescriptor: ShortcutCommandDescriptor = {
      ...descriptor,
      primaryBinding: primary,
      alternateBindings: alternates,
    };

    const nextMap = new Map(this.descriptorsMap());
    nextMap.set(descriptor.id, normalizedDescriptor);
    this.descriptorsMap.set(nextMap);

    for (const chord of [primary, ...alternates]) {
      const existing = this.chordIndex.get(chord) ?? [];
      if (!existing.includes(descriptor.id)) {
        this.chordIndex.set(chord, [...existing, descriptor.id]);
      }
    }
  }

  getCommand(commandId: string): ShortcutCommandDescriptor | undefined {
    return this.descriptorsMap().get(commandId);
  }

  bindHandler(
    commandId: string,
    scope: ShortcutScope,
    handler: (event: KeyboardEvent) => void | boolean,
    isEnabled?: () => boolean,
  ): () => void {
    const registrationId = this.nextRegistrationId++;
    this.handlers.push({
      registrationId,
      commandId,
      scope,
      isEnabled,
      handler,
    });

    return () => {
      const idx = this.handlers.findIndex((h) => h.registrationId === registrationId);
      if (idx >= 0) {
        this.handlers.splice(idx, 1);
      }
    };
  }

  dispatchKeyboardEvent(event: KeyboardEvent): boolean {
    const chord = extractNormalizedChordFromEvent(event);
    if (!chord) {
      return false;
    }

    // Double-Ctrl+F passthrough to native browser Find (UX-004 / Architecture Section 3.2)
    if (chord === 'Ctrl+F') {
      const now = Date.now();
      const withinDoublePressWindow = now - this.lastCtrlFTimestamp <= DOUBLE_CTRL_F_WINDOW_MS;
      this.lastCtrlFTimestamp = now;

      if (withinDoublePressWindow || this.smartFindOpenCheck()) {
        this.smartFindCloseHook();
        return false; // Do NOT call preventDefault(); allow native browser Find
      }
    }

    // UX-006: Preserve Ctrl+A select-all inside editable text fields while Ctrl+Enter always saves
    const inEditable = isEditableTextElement(event.target);
    if (chord === 'Ctrl+A' && inEditable) {
      return false;
    }

    const candidateCommandIds = this.chordIndex.get(normalizeShortcutString(chord));
    if (!candidateCommandIds || candidateCommandIds.length === 0) {
      return false;
    }

    const matchingHandlers: {
      readonly entry: BoundCommandHandler;
      readonly descriptor: ShortcutCommandDescriptor;
    }[] = [];

    for (const cmdId of candidateCommandIds) {
      const descriptor = this.descriptorsMap().get(cmdId);
      if (!descriptor) continue;

      if (event.repeat && !descriptor.allowRepeat) {
        return false;
      }
      if (inEditable && descriptor.allowInEditableInput === false) {
        continue;
      }

      for (const h of this.handlers) {
        if (h.commandId === cmdId && (!h.isEnabled || h.isEnabled())) {
          matchingHandlers.push({ entry: h, descriptor });
        }
      }
    }

    if (matchingHandlers.length === 0) {
      return false;
    }

    // Sort highest scope priority first; latest registration wins ties (modal stack semantics)
    matchingHandlers.sort((a, b) => {
      const scopeDiff = SCOPE_PRIORITY[b.entry.scope] - SCOPE_PRIORITY[a.entry.scope];
      if (scopeDiff !== 0) return scopeDiff;
      return b.entry.registrationId - a.entry.registrationId;
    });

    const winner = matchingHandlers[0];
    const result = winner.entry.handler(event);
    if (result === false) {
      return false;
    }

    event.preventDefault();
    event.stopPropagation();
    this.lastDispatchedCommandId.set(winner.descriptor.id);
    return true;
  }

  /**
   * Programmatically invokes the highest-priority enabled handler for `commandId`
   * (used by right-hand Function Rail & bottom action bar buttons).
   */
  triggerCommand(commandId: string): boolean {
    const descriptor = this.descriptorsMap().get(commandId);
    if (!descriptor) {
      return false;
    }

    const matching = this.handlers.filter(
      (h) => h.commandId === commandId && (!h.isEnabled || h.isEnabled()),
    );
    if (matching.length === 0) {
      return false;
    }

    matching.sort((a, b) => {
      const scopeDiff = SCOPE_PRIORITY[b.scope] - SCOPE_PRIORITY[a.scope];
      if (scopeDiff !== 0) return scopeDiff;
      return b.registrationId - a.registrationId;
    });

    const syntheticEvent = new KeyboardEvent('keydown', {
      key: descriptor.keycap,
      cancelable: true,
    });
    const result = matching[0].handler(syntheticEvent);
    if (result === false) {
      return false;
    }
    this.lastDispatchedCommandId.set(commandId);
    return true;
  }
}
