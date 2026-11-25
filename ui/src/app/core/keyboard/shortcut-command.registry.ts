import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, computed, inject, signal, untracked } from '@angular/core';
import {
  assertSafeBrowserShortcut,
  extractNormalizedChordFromEvent,
  isEditableTextElement,
  isImeOrAltGraphEvent,
  isStrictInputOwnerElement,
  normalizeShortcutString,
} from './collision-guard';
import {
  AsciiLetterPair,
  ContextMapValidationResult,
  DEFAULT_KEYBOARD_PREFERENCES,
  DEFAULT_QUALIFIED_MODIFIER_PROFILE_ID,
  KeyboardDispatchMode,
  KeyboardOverrideEntry,
  KeyboardPreferenceState,
  ModalActionDefinition,
  QUALIFIED_DESKTOP_MODIFIER_PROFILE,
  QualifiedModifierProfile,
  validateContextMap,
} from './modal-action.contract';

export * from './modal-action.contract';

export type ShortcutScope = 'GLOBAL' | 'SCREEN' | 'REPORT' | 'VOUCHER' | 'EDITOR' | 'MODAL';
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

export interface ContextGenerationHandle {
  readonly generation: number;
  readonly isCurrent: () => boolean;
  readonly runIfCurrent: (callback: () => void) => boolean;
}

export interface ActiveModalContextHandle {
  readonly instanceId: number;
  readonly contextId: string;
  readonly locale: string;
  readonly openedAtGeneration: number;
  readonly close: () => void;
  readonly notifyActionSetChanged: () => number;
}

export interface ModalKeyDispatchOutcome {
  readonly dispatched: boolean;
  readonly consumedByModalFence: boolean;
  readonly commandId: string | null;
  readonly contextId: string | null;
  readonly generation: number;
  readonly reason: string;
}

interface ActiveModalStackEntry {
  readonly instanceId: number;
  readonly contextId: string;
  readonly locale: string;
  readonly actions: readonly ModalActionDefinition[];
  readonly heldKeysAtOpen: Set<string>;
  readonly inFlightCommands: Set<string>;
  characterDispatchClosedByRuntimeDefect: boolean;
  ready: boolean;
}

let standaloneGenerationCounter = 0;

export function createContextGeneration(
  readCurrent?: () => number,
  advance?: () => number,
): ContextGenerationHandle {
  const gen = advance ? advance() : ++standaloneGenerationCounter;
  const currentReader = readCurrent ?? (() => standaloneGenerationCounter);
  return {
    generation: gen,
    isCurrent: () => currentReader() === gen,
    runIfCurrent: (callback: () => void) => {
      if (currentReader() !== gen) {
        return false;
      }
      callback();
      return true;
    },
  };
}

export function dispatchContextKey(
  registry: ShortcutCommandRegistry,
  event: KeyboardEvent,
): ModalKeyDispatchOutcome {
  return registry.dispatchContextKey(event);
}

function physicalKeyTokens(event: KeyboardEvent): string[] {
  const tokens: string[] = [];
  if (event.key && event.key.length > 0) {
    tokens.push(`key:${event.key}`);
    if (event.key.length === 1) {
      const codePoint = event.key.charCodeAt(0);
      if (codePoint >= 0x41 && codePoint <= 0x5a) {
        tokens.push(`ascii:${String.fromCharCode(codePoint + 32)}`);
      } else if (codePoint >= 0x61 && codePoint <= 0x7a) {
        tokens.push(`ascii:${event.key}`);
      }
    }
  }
  if (event.code && event.code.length > 0) {
    tokens.push(`code:${event.code}`);
  }
  return tokens;
}

const SCOPE_PRIORITY: Readonly<Record<ShortcutScope, number>> = {
  MODAL: 40,
  EDITOR: 30,
  VOUCHER: 25,
  REPORT: 20,
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
    id: 'help.contextual',
    label: 'Help',
    keycap: 'F1',
    modifierStyle: 'PLAIN',
    primaryBinding: 'F1',
    alternateBindings: ['Shift+F1', 'Ctrl+F1'],
    scope: 'GLOBAL',
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
    label: 'Select Company',
    keycap: 'F3',
    modifierStyle: 'PLAIN',
    primaryBinding: 'F3',
    alternateBindings: ['Alt+F3'],
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
  {
    id: 'company.shut',
    label: 'Shut Company',
    keycap: 'F3',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+F3',
    alternateBindings: ['Alt+Shift+F3'],
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
    id: 'voucher.rejectionsOut',
    label: 'Rejections Out',
    keycap: 'F5',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+F5',
    alternateBindings: ['Alt+Shift+F5'],
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.rejectionsIn',
    label: 'Rejections In',
    keycap: 'F6',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+F6',
    alternateBindings: ['Alt+Shift+F6'],
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
    id: 'voucher.togglePostDated',
    label: 'Post-Dated',
    keycap: 'T',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+Alt+T',
    alternateBindings: ['Alt+T'],
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.repeatNarration',
    label: 'Repeat Narration',
    keycap: 'R',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+R',
    alternateBindings: ['Ctrl+Alt+R', 'Alt+R'],
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.prevVoucher',
    label: 'Prev Voucher',
    keycap: 'PgUp',
    modifierStyle: 'PLAIN',
    primaryBinding: 'PageUp',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.nextVoucher',
    label: 'Next Voucher',
    keycap: 'PgDn',
    modifierStyle: 'PLAIN',
    primaryBinding: 'PageDown',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.deleteRow',
    label: 'Delete Row',
    keycap: 'D',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+D',
    alternateBindings: ['Alt+Delete'],
    scope: 'EDITOR',
    allowInEditableInput: true,
  },
  {
    id: 'inline.alterMaster',
    label: 'Alter Master',
    keycap: 'Enter',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+Enter',
    scope: 'EDITOR',
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
    id: 'register.restoreAllLines',
    label: 'Restore All',
    keycap: 'U',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+U',
    scope: 'SCREEN',
    allowInEditableInput: false,
  },
  {
    id: 'register.toggleSelectRow',
    label: 'Select Row',
    keycap: 'Space',
    modifierStyle: 'PLAIN',
    primaryBinding: 'Space',
    scope: 'SCREEN',
    allowInEditableInput: false,
  },
  {
    id: 'register.selectAllRows',
    label: 'Select All',
    keycap: 'Space',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+Space',
    scope: 'SCREEN',
    allowInEditableInput: false,
  },
  {
    id: 'register.invertSelection',
    label: 'Invert Selection',
    keycap: 'I',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+Alt+I',
    scope: 'SCREEN',
    allowInEditableInput: false,
  },
  {
    id: 'report.explodeRow',
    label: 'Explode Line',
    keycap: 'Enter',
    modifierStyle: 'PLAIN',
    primaryBinding: 'Shift+Enter',
    alternateBindings: ['Alt+Enter'],
    scope: 'SCREEN',
    allowInEditableInput: false,
  },
  {
    id: 'report.addColumn',
    label: 'New Column',
    keycap: 'C',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+C',
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
    alternateBindings: ['Alt+F5'],
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
    id: 'report.autoColumn',
    label: 'Auto Column',
    keycap: 'N',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+N',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'banking.autoMatch',
    label: 'Auto BRS',
    keycap: 'M',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+M',
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
    id: 'voucher.unusedNumbers',
    label: 'Unused Vch Nos',
    keycap: 'U',
    modifierStyle: 'CTRL_DOUBLE_UNDERLINE',
    primaryBinding: 'Ctrl+Alt+U',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.stockQuery',
    label: 'Stock Query',
    keycap: 'S',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+S',
    scope: 'SCREEN',
    allowInEditableInput: true,
  },
  {
    id: 'voucher.statAdjustment',
    label: 'Stat Adjustment',
    keycap: 'J',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+J',
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
    id: 'menu.share',
    label: 'Share',
    keycap: 'M',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+M',
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
  {
    id: 'menu.print',
    label: 'Print',
    keycap: 'P',
    modifierStyle: 'ALT_SINGLE_UNDERLINE',
    primaryBinding: 'Alt+P',
    alternateBindings: ['Ctrl+P'],
    scope: 'GLOBAL',
    allowInEditableInput: true,
  },
  {
    id: 'nav.escape',
    label: 'Quit / Close',
    keycap: 'Esc',
    modifierStyle: 'PLAIN',
    primaryBinding: 'Escape',
    alternateBindings: ['Ctrl+Q'],
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
  private nextModalInstanceId = 1;
  private lastCtrlFTimestamp = 0;
  private smartFindOpenCheck: () => boolean = () => false;
  private smartFindCloseHook: () => void = () => undefined;

  // --- KEY-001 (`Doc 45`) Modal Action State, Generation, Held Keys & Preferences ---
  private readonly contextGenerationSignal = signal<number>(1);
  private readonly modalStackSignal = signal<readonly ActiveModalStackEntry[]>([]);
  private readonly scannerWorkflowActiveSignal = signal<boolean>(false);
  private readonly preferencesSignal = signal<KeyboardPreferenceState>(DEFAULT_KEYBOARD_PREFERENCES);
  private readonly preferenceOwnerPrincipalIdSignal = signal<string | null>(null);
  private readonly preferenceFailureReasonSignal = signal<string | null>(null);
  private readonly lastRegistryDefectsSignal = signal<readonly string[]>([]);
  private readonly settingsModalOpenSignal = signal<boolean>(false);

  private readonly qualifiedProfiles = new Map<string, QualifiedModifierProfile>([
    [DEFAULT_QUALIFIED_MODIFIER_PROFILE_ID, QUALIFIED_DESKTOP_MODIFIER_PROFILE],
  ]);
  private readonly physicallyHeldKeys = new Set<string>();
  private readonly processedEvents = new WeakSet<KeyboardEvent>();
  private activeDispatchEvent: KeyboardEvent | null = null;

  readonly commands = computed(() => Array.from(this.descriptorsMap().values()));
  readonly lastDispatchedCommandId = signal<string | null>(null);
  readonly lastModalDispatch = signal<ModalKeyDispatchOutcome | null>(null);

  readonly contextGeneration = this.contextGenerationSignal.asReadonly();
  readonly scannerWorkflowActive = this.scannerWorkflowActiveSignal.asReadonly();
  readonly preferences = this.preferencesSignal.asReadonly();
  readonly preferenceOwnerPrincipalId = this.preferenceOwnerPrincipalIdSignal.asReadonly();
  readonly preferenceFailureReason = this.preferenceFailureReasonSignal.asReadonly();
  readonly lastRegistryDefects = this.lastRegistryDefectsSignal.asReadonly();
  readonly settingsModalOpen = this.settingsModalOpenSignal.asReadonly();

  readonly hasActiveModalContext = computed(() => this.modalStackSignal().length > 0);
  readonly topModalContextId = computed(() => {
    const stack = this.modalStackSignal();
    return stack.length > 0 ? stack[stack.length - 1].contextId : null;
  });

  readonly effectiveDispatchMode = computed<KeyboardDispatchMode>(() => {
    const prefs = this.preferencesSignal();
    if (prefs.mode === 'CHARACTER' && this.scannerWorkflowActiveSignal()) {
      return 'OFF';
    }
    return prefs.mode;
  });

  constructor() {
    for (const cmd of DEFAULT_ERP_SHORTCUT_COMMANDS) {
      this.registerCommand(cmd);
    }

    const keydownListener = (event: KeyboardEvent): void => {
      this.dispatchKeyboardEvent(event);
    };
    const keyupListener = (event: KeyboardEvent): void => {
      this.recordKeyUp(event);
    };
    const blurListener = (): void => {
      this.resetHeldKeysAndGenerationOnBlur();
    };
    const visibilityListener = (): void => {
      if (this.document.visibilityState === 'hidden') {
        this.resetHeldKeysAndGenerationOnBlur();
      }
    };

    this.document.addEventListener('keydown', keydownListener, { capture: true });
    this.document.addEventListener('keyup', keyupListener, { capture: true });
    const win = this.document.defaultView;
    win?.addEventListener('blur', blurListener);
    this.document.addEventListener('visibilitychange', visibilityListener);

    this.destroyRef.onDestroy(() => {
      this.document.removeEventListener('keydown', keydownListener, { capture: true });
      this.document.removeEventListener('keyup', keyupListener, { capture: true });
      win?.removeEventListener('blur', blurListener);
      this.document.removeEventListener('visibilitychange', visibilityListener);
    });
  }

  openKeyboardSettingsModal(): void {
    this.settingsModalOpenSignal.set(true);
  }

  closeKeyboardSettingsModal(): void {
    this.settingsModalOpenSignal.set(false);
  }

  advanceContextGeneration(): number {
    return untracked(() => {
      const next = this.contextGenerationSignal() + 1;
      this.contextGenerationSignal.set(next);
      return next;
    });
  }

  createGenerationHandle(): ContextGenerationHandle {
    return createContextGeneration(
      () => this.contextGenerationSignal(),
      () => this.advanceContextGeneration(),
    );
  }

  listQualifiedModifierProfiles(): readonly QualifiedModifierProfile[] {
    return Array.from(this.qualifiedProfiles.values());
  }

  registerQualifiedModifierProfile(profile: QualifiedModifierProfile): void {
    this.qualifiedProfiles.set(profile.profileId, profile);
  }

  /**
   * Unframed Barcode Scanner Workflow Suppression (`Doc 45 §4`).
   * Forces character-only dispatch OFF throughout the application without overwriting
   * the user's saved `KeyboardPreferenceState`. Restoring `false` advances `contextGeneration`.
   */
  setScannerWorkflowActive(active: boolean): void {
    untracked(() => {
      const prev = this.scannerWorkflowActiveSignal();
      if (prev === active) {
        return;
      }
      this.scannerWorkflowActiveSignal.set(active);
      this.advanceContextGeneration();
    });
  }

  /**
   * Syncs page-memory preference ownership with `AuthStore` (`Doc 45 §5`):
   *  - `null -> personId` (same-tab anonymous sign-in): carries accessibility setting to `personId`.
   *  - `personId -> personId` (company switch / refresh): retains `personId`'s setting.
   *  - `personId -> null` (logout / expiry) or `personA -> personB`: resets to defaults so no other
   *    person ever inherits `personA`'s bindings.
   */
  syncAuthenticatedPrincipal(principalId: string | null): void {
    untracked(() => {
      const currentOwner = this.preferenceOwnerPrincipalIdSignal();
      if (currentOwner === null && principalId !== null) {
        this.preferenceOwnerPrincipalIdSignal.set(principalId);
        return;
      }
      if (currentOwner === principalId) {
        return;
      }
      this.preferenceOwnerPrincipalIdSignal.set(principalId);
      this.preferencesSignal.set(DEFAULT_KEYBOARD_PREFERENCES);
      this.preferenceFailureReasonSignal.set(null);
      this.advanceContextGeneration();
    });
  }

  /**
   * Fails closed to `OFF` if in-memory preference state is corrupted (`Doc 45 §5`).
   */
  ingestRawPreferenceState(candidate: unknown): ContextMapValidationResult {
    if (!candidate || typeof candidate !== 'object') {
      this.failClosedToOff('Invalid preference object; character shortcuts disabled for safety.');
      return { valid: false, defects: ['INVALID_PREFERENCE_STATE'] };
    }
    const raw = candidate as Partial<KeyboardPreferenceState>;
    if (
      raw.version !== 1 ||
      (raw.mode !== 'CHARACTER' && raw.mode !== 'OFF' && raw.mode !== 'MODIFIER') ||
      typeof raw.locale !== 'string' ||
      !Array.isArray(raw.overrides)
    ) {
      this.failClosedToOff('Corrupted in-memory keyboard preference state; failed safe to OFF.');
      return { valid: false, defects: ['CORRUPTED_PREFERENCE_SCHEMA'] };
    }
    return this.applyKeyboardPreferences(raw as KeyboardPreferenceState);
  }

  private failClosedToOff(reason: string): void {
    this.preferencesSignal.set({
      version: 1,
      mode: 'OFF',
      locale: 'en-GB',
      profileId: null,
      overrides: [],
    });
    this.preferenceFailureReasonSignal.set(reason);
    this.advanceContextGeneration();
  }

  /**
   * Validates and atomically applies a complete `KeyboardPreferenceState` (`Doc 45 §5`).
   * Rejects unknown profiles, unknown context/command overrides, unqualified chords, or duplicate
   * chords within a context without mutating the active map.
   */
  applyKeyboardPreferences(next: KeyboardPreferenceState): ContextMapValidationResult {
    const defects: string[] = [];

    if (next.version !== 1) {
      defects.push('UNSUPPORTED_PREFERENCE_VERSION: Expected version 1.');
    }
    if (next.mode !== 'CHARACTER' && next.mode !== 'OFF' && next.mode !== 'MODIFIER') {
      defects.push(`INVALID_MODE: Unsupported keyboard mode "${String(next.mode)}".`);
    }
    if (next.locale !== 'en-GB' && next.locale !== 'pl-PL') {
      defects.push(`UNQUALIFIED_LOCALE: Locale "${next.locale}" is not admitted.`);
    }

    if (next.mode === 'MODIFIER') {
      if (!next.profileId) {
        defects.push('MISSING_MODIFIER_PROFILE: Select a qualified modifier profile before enabling MODIFIER mode.');
      } else {
        const profile = this.qualifiedProfiles.get(next.profileId);
        if (!profile) {
          defects.push(`UNKNOWN_MODIFIER_PROFILE: Profile "${next.profileId}" is not qualified.`);
        } else {
          defects.push(...this.validateModifierOverrides(profile, next.overrides));
        }
      }
    } else if (next.overrides.length > 0 && next.profileId !== null) {
      const profile = this.qualifiedProfiles.get(next.profileId);
      if (!profile) {
        defects.push(`UNKNOWN_MODIFIER_PROFILE: Profile "${next.profileId}" is not qualified.`);
      } else {
        defects.push(...this.validateModifierOverrides(profile, next.overrides));
      }
    }

    if (defects.length > 0) {
      this.lastRegistryDefectsSignal.set(defects);
      return { valid: false, defects };
    }

    const frozenOverrides: readonly KeyboardOverrideEntry[] = Object.freeze(
      next.overrides.map((o) => ({
        contextId: o.contextId,
        commandId: o.commandId,
        chordId: normalizeShortcutString(o.chordId),
      })),
    );

    this.preferencesSignal.set(
      Object.freeze({
        version: 1,
        mode: next.mode,
        locale: next.locale,
        profileId: next.mode === 'MODIFIER' ? next.profileId : null,
        overrides: frozenOverrides,
      }),
    );
    this.preferenceFailureReasonSignal.set(null);
    this.lastRegistryDefectsSignal.set([]);
    this.advanceContextGeneration();
    return { valid: true, defects: [] };
  }

  private validateModifierOverrides(
    profile: QualifiedModifierProfile,
    overrides: readonly KeyboardOverrideEntry[],
  ): string[] {
    const defects: string[] = [];
    const seenTargets = new Set<string>();

    for (const ov of overrides) {
      const targetKey = `${ov.contextId}::${ov.commandId}`;
      if (seenTargets.has(targetKey)) {
        defects.push(`DUPLICATE_OVERRIDE_TARGET: Duplicate override for ${targetKey}.`);
        continue;
      }
      seenTargets.add(targetKey);

      const binding = profile.bindings.find(
        (b) => b.contextId === ov.contextId && b.commandId === ov.commandId,
      );
      if (!binding) {
        defects.push(
          `UNKNOWN_CONTEXT_ACTION: "${ov.contextId}/${ov.commandId}" is not in profile "${profile.profileId}".`,
        );
        continue;
      }
      const normalizedChord = normalizeShortcutString(ov.chordId);
      if (!binding.qualifiedCandidates.includes(normalizedChord)) {
        defects.push(
          `UNQUALIFIED_CHORD: "${normalizedChord}" is not a qualified candidate for "${ov.contextId}/${ov.commandId}".`,
        );
      }
    }

    if (defects.length > 0) {
      return defects;
    }

    // Verify zero chord collisions within each context after applying overrides
    const contexts = new Set(profile.bindings.map((b) => b.contextId));
    for (const ctxId of contexts) {
      const effectiveChords = new Map<string, string>();
      const ctxBindings = profile.bindings.filter((b) => b.contextId === ctxId);
      for (const b of ctxBindings) {
        const override = overrides.find(
          (o) => o.contextId === ctxId && o.commandId === b.commandId,
        );
        const chord = override ? normalizeShortcutString(override.chordId) : b.defaultChord;
        const conflictCmd = effectiveChords.get(chord);
        if (conflictCmd) {
          defects.push(
            `DUPLICATE_MODIFIER_CHORD: Chord "${chord}" in "${ctxId}" collides between "${conflictCmd}" and "${b.commandId}".`,
          );
        } else {
          effectiveChords.set(chord, b.commandId);
        }
      }
    }

    return defects;
  }

  /**
   * Computes the visible shortcut hint for a modal action button (`Doc 45 §5`):
   *  - `CHARACTER` (when scanner suppression is off): returns uppercase letter, e.g. `'S'` (rendered as `[S]`).
   *  - `MODIFIER` (with qualified profile): returns effective chord, e.g. `'Alt+Shift+S'`.
   *  - `OFF` or scanner-suppressed: returns `null` (ordinary keyboard Tab/Enter/Space only).
   */
  effectiveActionHint(
    contextId: string,
    commandId: string,
    defaultPair: AsciiLetterPair,
  ): string | null {
    const mode = this.effectiveDispatchMode();
    if (mode === 'OFF') {
      return null;
    }
    if (mode === 'CHARACTER') {
      return defaultPair[1];
    }
    const prefs = this.preferencesSignal();
    if (!prefs.profileId) {
      return null;
    }
    const profile = this.qualifiedProfiles.get(prefs.profileId);
    if (!profile) {
      return null;
    }
    const override = prefs.overrides.find(
      (o) => o.contextId === contextId && o.commandId === commandId,
    );
    if (override) {
      return override.chordId;
    }
    const binding = profile.bindings.find(
      (b) => b.contextId === contextId && b.commandId === commandId,
    );
    return binding?.defaultChord ?? null;
  }

  /**
   * Activates a validated `KEY-001` modal context on top of the modal stack.
   * Refuses activation if `validateContextMap` finds missing/colliding/undeclared pairs or unknown icons!
   */
  openModalContext(
    actions: readonly ModalActionDefinition[],
    locale?: string,
  ): ActiveModalContextHandle | null {
    return untracked(() => {
      const validation = validateContextMap(actions, locale);
      if (!validation.valid) {
        this.lastRegistryDefectsSignal.set(validation.defects);
        return null;
      }

      this.lastRegistryDefectsSignal.set([]);
      const generation = this.advanceContextGeneration();
      const instanceId = this.nextModalInstanceId++;
      const heldSnapshot = new Set<string>(this.physicallyHeldKeys);
      if (this.activeDispatchEvent) {
        for (const tok of physicalKeyTokens(this.activeDispatchEvent)) {
          heldSnapshot.add(tok);
        }
      }

      const entry: ActiveModalStackEntry = {
        instanceId,
        contextId: actions[0].contextId,
        locale: locale ?? actions[0].locale,
        actions,
        heldKeysAtOpen: heldSnapshot,
        inFlightCommands: new Set<string>(),
        characterDispatchClosedByRuntimeDefect: false,
        ready: true,
      };

      this.modalStackSignal.set([...this.modalStackSignal(), entry]);

      return {
        instanceId,
        contextId: entry.contextId,
        locale: entry.locale,
        openedAtGeneration: generation,
        close: () => {
          this.closeModalContext(instanceId);
        },
        notifyActionSetChanged: () => this.advanceContextGeneration(),
      };
    });
  }

  closeModalContext(instanceId: number): void {
    untracked(() => {
      const current = this.modalStackSignal();
      const next = current.filter((c) => c.instanceId !== instanceId);
      if (next.length !== current.length) {
        this.modalStackSignal.set(next);
        this.advanceContextGeneration();
      }
    });
  }

  recordKeyUp(event: KeyboardEvent): void {
    untracked(() => {
      const tokens = physicalKeyTokens(event);
      for (const tok of tokens) {
        this.physicallyHeldKeys.delete(tok);
        for (const ctx of this.modalStackSignal()) {
          ctx.heldKeysAtOpen.delete(tok);
        }
      }
    });
  }

  resetHeldKeysAndGenerationOnBlur(): void {
    untracked(() => {
      this.physicallyHeldKeys.clear();
      for (const ctx of this.modalStackSignal()) {
        ctx.heldKeysAtOpen.clear();
      }
      this.advanceContextGeneration();
    });
  }

  /**
   * Arbitrates a `keydown` event against the topmost active `KEY-001` modal context (`Doc 45 §4`).
   */
  dispatchContextKey(event: KeyboardEvent): ModalKeyDispatchOutcome {
    const gen = this.contextGenerationSignal();
    const stack = this.modalStackSignal();
    const keyTokens = physicalKeyTokens(event);

    if (stack.length === 0) {
      for (const tok of keyTokens) {
        this.physicallyHeldKeys.add(tok);
      }
      return {
        dispatched: false,
        consumedByModalFence: false,
        commandId: null,
        contextId: null,
        generation: gen,
        reason: 'NO_ACTIVE_MODAL_CONTEXT',
      };
    }

    const top = stack[stack.length - 1];

    // Check whether this key was already held when `top` opened or is an auto-repeat
    const isHeldFromBeforeReady = keyTokens.some((tok) => top.heldKeysAtOpen.has(tok));

    // Record key as physically held now (for any nested context opened by this event)
    for (const tok of keyTokens) {
      this.physicallyHeldKeys.add(tok);
    }

    if (event.defaultPrevented) {
      return {
        dispatched: false,
        consumedByModalFence: false,
        commandId: null,
        contextId: top.contextId,
        generation: gen,
        reason: 'ALREADY_HANDLED',
      };
    }

    // Never intercept Tab, Shift+Tab, Escape, Enter, Space, or Arrow navigation here
    if (
      event.key === 'Tab' ||
      event.key === 'Escape' ||
      event.key === 'Esc' ||
      event.key === 'Enter' ||
      event.key === ' ' ||
      event.key.startsWith('Arrow')
    ) {
      return {
        dispatched: false,
        consumedByModalFence: false,
        commandId: null,
        contextId: top.contextId,
        generation: gen,
        reason: 'ORDINARY_NAVIGATION_KEY',
      };
    }

    if (isImeOrAltGraphEvent(event)) {
      return {
        dispatched: false,
        consumedByModalFence: true,
        commandId: null,
        contextId: top.contextId,
        generation: gen,
        reason: 'IME_OR_ALTGRAPH_OWNED',
      };
    }

    if (isStrictInputOwnerElement(event.target, this.document.activeElement)) {
      return {
        dispatched: false,
        consumedByModalFence: true,
        commandId: null,
        contextId: top.contextId,
        generation: gen,
        reason: 'INPUT_OWNER_ACTIVE',
      };
    }

    if (!top.ready || event.repeat || isHeldFromBeforeReady || this.activeDispatchEvent === event) {
      return {
        dispatched: false,
        consumedByModalFence: true,
        commandId: null,
        contextId: top.contextId,
        generation: gen,
        reason: event.repeat ? 'KEY_AUTO_REPEAT_BLOCKED' : 'HELD_OR_OPENING_KEY_BLOCKED',
      };
    }

    // Re-validate visible action set at runtime to guard against dynamic duplicate mutations
    const runtimeCheck = validateContextMap(top.actions, top.locale);
    if (!runtimeCheck.valid) {
      top.characterDispatchClosedByRuntimeDefect = true;
      this.lastRegistryDefectsSignal.set(runtimeCheck.defects);
    }
    if (top.characterDispatchClosedByRuntimeDefect) {
      return {
        dispatched: false,
        consumedByModalFence: true,
        commandId: null,
        contextId: top.contextId,
        generation: gen,
        reason: 'RUNTIME_REGISTRY_DEFECT_CLOSED',
      };
    }

    const effectiveMode = this.effectiveDispatchMode();
    if (effectiveMode === 'OFF') {
      const isPlainChar =
        !event.ctrlKey && !event.altKey && !event.metaKey && event.key.length === 1;
      return {
        dispatched: false,
        consumedByModalFence: isPlainChar,
        commandId: null,
        contextId: top.contextId,
        generation: gen,
        reason: this.scannerWorkflowActiveSignal()
          ? 'SCANNER_WORKFLOW_SUPPRESSED'
          : 'MODE_OFF',
      };
    }

    const visibleActions = top.actions.filter((a) => (a.visibleWhen ? a.visibleWhen() : true));
    let matchedAction: ModalActionDefinition | undefined;

    if (effectiveMode === 'CHARACTER') {
      if (event.ctrlKey || event.altKey || event.metaKey) {
        return {
          dispatched: false,
          consumedByModalFence: false,
          commandId: null,
          contextId: top.contextId,
          generation: gen,
          reason: 'MODIFIER_CHORD_PASSTHROUGH',
        };
      }
      matchedAction = visibleActions.find(
        (a) => a.letterPair[0] === event.key || a.letterPair[1] === event.key,
      );
      if (!matchedAction) {
        return {
          dispatched: false,
          consumedByModalFence: event.key.length === 1,
          commandId: null,
          contextId: top.contextId,
          generation: gen,
          reason: 'UNMAPPED_CHARACTER_BLOCKED_AT_MODAL_FENCE',
        };
      }
    } else {
      // MODIFIER mode
      const prefs = this.preferencesSignal();
      const profile = prefs.profileId ? this.qualifiedProfiles.get(prefs.profileId) : undefined;
      if (!profile) {
        return {
          dispatched: false,
          consumedByModalFence: true,
          commandId: null,
          contextId: top.contextId,
          generation: gen,
          reason: 'UNQUALIFIED_MODIFIER_PROFILE',
        };
      }
      const deliveredChord = extractNormalizedChordFromEvent(event);
      if (!deliveredChord) {
        return {
          dispatched: false,
          consumedByModalFence: true,
          commandId: null,
          contextId: top.contextId,
          generation: gen,
          reason: 'NO_MODIFIER_CHORD',
        };
      }
      const normalizedDelivered = normalizeShortcutString(deliveredChord);
      for (const action of visibleActions) {
        const expectedHint = this.effectiveActionHint(
          top.contextId,
          action.commandId,
          action.letterPair,
        );
        if (expectedHint && normalizeShortcutString(expectedHint) === normalizedDelivered) {
          matchedAction = action;
          break;
        }
      }
      if (!matchedAction) {
        return {
          dispatched: false,
          consumedByModalFence: event.key.length === 1 && !event.ctrlKey && !event.altKey,
          commandId: null,
          contextId: top.contextId,
          generation: gen,
          reason: 'UNMAPPED_MODIFIER_CHORD',
        };
      }
    }

    const isEnabled = matchedAction.enabledWhen ? matchedAction.enabledWhen() : true;
    if (!isEnabled) {
      return {
        dispatched: false,
        consumedByModalFence: true,
        commandId: matchedAction.commandId,
        contextId: top.contextId,
        generation: gen,
        reason: 'ACTION_DISABLED_PAIR_RESERVED',
      };
    }

    if (top.inFlightCommands.has(matchedAction.commandId)) {
      return {
        dispatched: false,
        consumedByModalFence: true,
        commandId: matchedAction.commandId,
        contextId: top.contextId,
        generation: gen,
        reason: 'MUTATION_IN_FLIGHT',
      };
    }

    if (event.cancelable) {
      event.preventDefault();
    }
    event.stopPropagation();

    const previousDispatchEvent = this.activeDispatchEvent;
    this.activeDispatchEvent = event;
    if (matchedAction.isMutation) {
      top.inFlightCommands.add(matchedAction.commandId);
    }

    try {
      const maybePromise = matchedAction.normalActivation();
      if (maybePromise && typeof (maybePromise as Promise<void>).finally === 'function') {
        void (maybePromise as Promise<void>).finally(() => {
          top.inFlightCommands.delete(matchedAction!.commandId);
        });
      } else if (matchedAction.isMutation) {
        top.inFlightCommands.delete(matchedAction.commandId);
      }
    } finally {
      this.activeDispatchEvent = previousDispatchEvent;
    }

    const outcome: ModalKeyDispatchOutcome = {
      dispatched: true,
      consumedByModalFence: true,
      commandId: matchedAction.commandId,
      contextId: top.contextId,
      generation: gen,
      reason: 'DISPATCHED',
    };
    this.lastDispatchedCommandId.set(matchedAction.commandId);
    this.lastModalDispatch.set(outcome);
    return outcome;
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
    if (this.processedEvents.has(event)) {
      return false;
    }
    this.processedEvents.add(event);

    // 1. Arbitrate topmost KEY-001 modal context first when active
    if (this.modalStackSignal().length > 0) {
      const modalOutcome = this.dispatchContextKey(event);
      this.lastModalDispatch.set(modalOutcome);
      if (modalOutcome.dispatched) {
        return true;
      }
      if (modalOutcome.consumedByModalFence) {
        return false;
      }
    } else {
      // Track physical held keys even when no modal is open yet (for pre-held key guard!)
      for (const tok of physicalKeyTokens(event)) {
        this.physicallyHeldKeys.add(tok);
      }
    }

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

    // UX-006 / UM-110-02: Preserve Ctrl+A select-all inside standalone text fields,
    // while allowing Ctrl+A inside mc-amount-cell or [data-mc-accept-ctrl-a="true"] voucher/form grids.
    const inEditable = isEditableTextElement(event.target);
    const targetEl = event.target instanceof Element ? event.target : null;
    const allowCtrlAAcceptInGrid =
      targetEl !== null &&
      typeof targetEl.closest === 'function' &&
      targetEl.closest('mc-amount-cell, [data-mc-accept-ctrl-a="true"]') !== null;
    if (chord === 'Ctrl+A' && inEditable && !allowCtrlAAcceptInGrid) {
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
    const prevEvent = this.activeDispatchEvent;
    this.activeDispatchEvent = event;
    let result: void | boolean;
    try {
      result = winner.entry.handler(event);
    } finally {
      this.activeDispatchEvent = prevEvent;
    }
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
