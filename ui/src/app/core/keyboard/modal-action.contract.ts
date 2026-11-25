import { isKnownSemanticIconId } from '../../shared/components/semantic-icon/semantic-icon.component';
import { FORBIDDEN_BROWSER_SHORTCUTS, normalizeShortcutString } from './collision-guard';

/**
 * MoneyCraft 1.0.0 — `KEY-001` (`Doc 45`) & `VIS-001` (`Doc 46`) Closed Modal Action Contract.
 *
 * Defines explicit `en-GB` ASCII upper/lower action-letter pairs, `stateTokenRole` & `iconSemanticId`
 * validation (`validateContextMap`), per-user page-memory `KeyboardPreferenceState`, qualified
 * modifier profiles, and the complete initial 15 modal contexts + 13 `commands.menu` bindings.
 */

export type StateTokenRole = 'action.primary' | 'action.secondary' | 'action.danger';

export type KeyboardDispatchMode = 'CHARACTER' | 'OFF' | 'MODIFIER';

export type AsciiLetterPair = readonly [string, string]; // [lower, upper], e.g. ['s', 'S']

export interface ModalActionDefinition {
  readonly contextId: string;
  readonly locale: string;
  readonly commandId: string;
  readonly label: string;
  readonly letterPair: AsciiLetterPair;
  readonly visibleWhen?: () => boolean;
  readonly enabledWhen?: () => boolean;
  readonly disabledReason?: string | null;
  readonly permissionRef: string;
  readonly effect: string;
  readonly normalActivation: () => void | Promise<void>;
  readonly focusOnClose?: string | null;
  readonly modifierCandidates?: readonly string[];
  readonly iconSemanticId: string | null;
  readonly stateTokenRole: StateTokenRole;
  readonly isMutation?: boolean;
}

export interface ContextMapValidationResult {
  readonly valid: boolean;
  readonly defects: readonly string[];
}

export interface KeyboardOverrideEntry {
  readonly contextId: string;
  readonly commandId: string;
  readonly chordId: string;
}

export interface KeyboardPreferenceState {
  readonly version: 1;
  readonly mode: KeyboardDispatchMode;
  readonly locale: string;
  readonly profileId: string | null;
  readonly overrides: readonly KeyboardOverrideEntry[];
}

export interface QualifiedModifierActionBinding {
  readonly contextId: string;
  readonly commandId: string;
  readonly defaultChord: string;
  readonly qualifiedCandidates: readonly string[];
  readonly rejectedCandidates: readonly { readonly chord: string; readonly reason: string }[];
}

export interface QualifiedModifierProfile {
  readonly profileId: string;
  readonly label: string;
  readonly locale: string;
  readonly environment: {
    readonly browser: string;
    readonly os: string;
    readonly keyboardLayout: string;
    readonly functionKeyMode: string;
    readonly ime: string;
    readonly assistiveTech: string;
  };
  readonly bindings: readonly QualifiedModifierActionBinding[];
}

export const DEFAULT_KEYBOARD_PREFERENCES: KeyboardPreferenceState = Object.freeze({
  version: 1,
  mode: 'CHARACTER',
  locale: 'en-GB',
  profileId: null,
  overrides: Object.freeze([]),
});

export const EXPLICIT_EN_GB_ASCII_PAIRS: Readonly<Record<string, AsciiLetterPair>> = Object.freeze({
  'A/a': ['a', 'A'],
  'B/b': ['b', 'B'],
  'C/c': ['c', 'C'],
  'D/d': ['d', 'D'],
  'E/e': ['e', 'E'],
  'F/f': ['f', 'F'],
  'G/g': ['g', 'G'],
  'H/h': ['h', 'H'],
  'I/i': ['i', 'I'],
  'J/j': ['j', 'J'],
  'K/k': ['k', 'K'],
  'L/l': ['l', 'L'],
  'M/m': ['m', 'M'],
  'N/n': ['n', 'N'],
  'O/o': ['o', 'O'],
  'P/p': ['p', 'P'],
  'Q/q': ['q', 'Q'],
  'R/r': ['r', 'R'],
  'S/s': ['s', 'S'],
  'T/t': ['t', 'T'],
  'U/u': ['u', 'U'],
  'V/v': ['v', 'V'],
  'W/w': ['w', 'W'],
  'X/x': ['x', 'X'],
  'Y/y': ['y', 'Y'],
  'Z/z': ['z', 'Z'],
});

/** Test-only `pl-PL` explicit pair set (`Doc 45 §1 & §6` `new-set-and-locale` — not a Polish UI claim). */
export const TEST_ONLY_PL_PL_ASCII_PAIRS: Readonly<Record<string, AsciiLetterPair>> = Object.freeze({
  'R/r': ['r', 'R'],
  'W/w': ['w', 'W'],
  'S/s': ['s', 'S'],
  'C/c': ['c', 'C'],
  'A/a': ['a', 'A'],
  'D/d': ['d', 'D'],
});

export const DECLARED_LOCALE_PAIR_CATALOG: Readonly<
  Record<string, Readonly<Record<string, AsciiLetterPair>>>
> = Object.freeze({
  'en-GB': EXPLICIT_EN_GB_ASCII_PAIRS,
  'pl-PL': TEST_ONLY_PL_PL_ASCII_PAIRS,
});

const PRIMARY_ROLE_COMMANDS: ReadonlySet<string> = new Set([
  'company.open',
  'ledger.select',
  'ledger.save',
  'journal.saveDraft',
  'journal.approve',
  'journal.post',
  'context.applyDate',
  'report.applyFilters',
  'keyboard.apply',
]);

export function resolveStateTokenRole(commandId: string): StateTokenRole {
  if (commandId === 'editor.discard') {
    return 'action.danger';
  }
  if (PRIMARY_ROLE_COMMANDS.has(commandId)) {
    return 'action.primary';
  }
  return 'action.secondary';
}

export function asciiPair(pairSpec: keyof typeof EXPLICIT_EN_GB_ASCII_PAIRS): AsciiLetterPair {
  return EXPLICIT_EN_GB_ASCII_PAIRS[pairSpec];
}

export function formatPairSpec(pair: AsciiLetterPair | undefined | null): string {
  if (!pair || pair.length !== 2) {
    return '';
  }
  return `${pair[1]}/${pair[0]}`;
}

export function isDeclaredLocalePair(
  locale: string,
  pair: readonly string[] | undefined | null,
): pair is AsciiLetterPair {
  if (!Array.isArray(pair) || pair.length !== 2) {
    return false;
  }
  const catalog = DECLARED_LOCALE_PAIR_CATALOG[locale];
  if (!catalog) {
    return false;
  }
  return Object.values(catalog).some((entry) => entry[0] === pair[0] && entry[1] === pair[1]);
}

/**
 * `Doc 45 §5` deterministic modifier candidate order:
 * 1. `Ctrl+Shift+<ASCII Upper>`
 * 2. `Alt+Shift+<ASCII Upper>`
 * 3. `Ctrl+Alt+Shift+<ASCII Upper>`
 * 4. `Ctrl+Shift+F1` .. `Ctrl+Shift+F12`
 */
export function buildModifierCandidatesForLetter(upperAscii: string): readonly string[] {
  const candidates: string[] = [
    `Ctrl+Shift+${upperAscii}`,
    `Alt+Shift+${upperAscii}`,
    `Ctrl+Alt+Shift+${upperAscii}`,
  ];
  for (let fn = 1; fn <= 12; fn += 1) {
    candidates.push(`Ctrl+Shift+F${fn}`);
  }
  return candidates;
}

/**
 * Validates a complete visible modal action set (`Doc 45 §1 & §6` `validateContextMap`).
 * Includes presently disabled visible actions (which reserve their pair) and rejects:
 *  - unknown locale
 *  - empty visible action set
 *  - missing or undeclared case pairs
 *  - duplicate letter pairs within the context
 *  - unknown `iconSemanticId` or invalid `stateTokenRole`
 */
export function validateContextMap(
  actions: readonly ModalActionDefinition[],
  expectedLocale?: string,
): ContextMapValidationResult {
  const defects: string[] = [];

  if (!Array.isArray(actions) || actions.length === 0) {
    return { valid: false, defects: ['EMPTY_CONTEXT_ACTION_SET: At least one action is required.'] };
  }

  const locale = expectedLocale ?? actions[0].locale;
  if (!DECLARED_LOCALE_PAIR_CATALOG[locale]) {
    defects.push(`UNADMITTED_LOCALE: Locale "${locale}" has no admitted surface pair inventory.`);
  }

  const contextId = actions[0].contextId;
  if (!contextId || contextId.trim().length === 0) {
    defects.push('MISSING_CONTEXT_ID: Every modal action must declare a non-empty contextId.');
  }

  const visibleActions = actions.filter((a) => (a.visibleWhen ? a.visibleWhen() : true));
  if (visibleActions.length === 0) {
    defects.push(`NO_VISIBLE_ACTIONS: Context "${contextId}" has zero visible actions.`);
  }

  const claimedPairs = new Map<string, string>();

  for (const action of visibleActions) {
    if (action.contextId !== contextId) {
      defects.push(
        `MIXED_CONTEXT_ID: Action "${action.commandId}" belongs to "${action.contextId}" instead of "${contextId}".`,
      );
    }
    if (!action.commandId || action.commandId.trim().length === 0) {
      defects.push(`MISSING_COMMAND_ID: Context "${contextId}" contains an unnamed commandId.`);
    }
    if (!action.label || action.label.trim().length === 0) {
      defects.push(`MISSING_LABEL: Action "${action.commandId}" has no visible label.`);
    }
    if (!action.permissionRef || action.permissionRef.trim().length === 0) {
      defects.push(`MISSING_PERMISSION_REF: Action "${action.commandId}" has no permissionRef.`);
    }
    if (!action.effect || action.effect.trim().length === 0) {
      defects.push(`MISSING_EFFECT: Action "${action.commandId}" has no documented effect.`);
    }
    if (typeof action.normalActivation !== 'function') {
      defects.push(
        `MISSING_NORMAL_ACTIVATION: Action "${action.commandId}" lacks an ordinary activation handler.`,
      );
    }
    if (action.locale !== locale) {
      defects.push(
        `LOCALE_MISMATCH: Action "${action.commandId}" declares locale "${action.locale}" instead of "${locale}".`,
      );
    }
    if (!isKnownSemanticIconId(action.iconSemanticId)) {
      defects.push(
        `UNKNOWN_ICON_SEMANTIC_ID: Action "${action.commandId}" references unknown icon "${String(action.iconSemanticId)}".`,
      );
    }
    if (
      action.stateTokenRole !== 'action.primary' &&
      action.stateTokenRole !== 'action.secondary' &&
      action.stateTokenRole !== 'action.danger'
    ) {
      defects.push(
        `INVALID_STATE_TOKEN_ROLE: Action "${action.commandId}" has invalid role "${String(action.stateTokenRole)}".`,
      );
    }

    const pair = action.letterPair as unknown as readonly string[] | undefined | null;
    if (!pair || (Array.isArray(pair) && pair.length === 0)) {
      defects.push(
        `MISSING_ACTION_PAIR: Visible action "${action.commandId}" ("${action.label}") has no assigned letterPair.`,
      );
      continue;
    }

    if (!isDeclaredLocalePair(locale, pair)) {
      defects.push(
        `UNDECLARED_CASE_PAIR: Action "${action.commandId}" uses "${ Array.isArray(pair) ? pair.join('/') : String(pair) }", which is not an explicitly declared case pair for ${locale}.`,
      );
      continue;
    }

    const pairKey = `${pair[1]}/${pair[0]}`;
    const existingOwner = claimedPairs.get(pairKey);
    if (existingOwner) {
      defects.push(
        `DUPLICATE_LETTER_PAIR: Pair "${pairKey}" in context "${contextId}" is claimed by both "${existingOwner}" and "${action.commandId}".`,
      );
    } else {
      claimedPairs.set(pairKey, action.commandId);
    }
  }

  return {
    valid: defects.length === 0,
    defects,
  };
}

export interface CanonicalContextActionSpec {
  readonly contextId: string;
  readonly commandId: string;
  readonly label: string;
  readonly pairSpec: keyof typeof EXPLICIT_EN_GB_ASCII_PAIRS;
  readonly iconSemanticId: string | null;
  readonly stateTokenRole: StateTokenRole;
  readonly permissionRef: string;
  readonly effect: string;
  readonly isMutation?: boolean;
}

/**
 * Complete `Doc 45 §§2–3` & `Doc 46 §2` Initial Browser Action & Visual Inventory (`en-GB`).
 */
export const INITIAL_BROWSER_MODAL_INVENTORY: readonly CanonicalContextActionSpec[] = Object.freeze([
  // 1. company.initial
  {
    contextId: 'company.initial',
    commandId: 'company.open',
    label: 'Open',
    pairSpec: 'O/o',
    iconSemanticId: 'mc.icon.company',
    stateTokenRole: 'action.primary',
    permissionRef: 'company.read',
    effect: 'Open ACTIVE selected company under current company.read authority.',
  },
  // 2. company.pick
  {
    contextId: 'company.pick',
    commandId: 'company.open',
    label: 'Open',
    pairSpec: 'O/o',
    iconSemanticId: 'mc.icon.company',
    stateTokenRole: 'action.primary',
    permissionRef: 'company.read',
    effect: 'Switch to ACTIVE selected company (entering editor.dirty first if unsaved draft exists).',
  },
  {
    contextId: 'company.pick',
    commandId: 'editor.close',
    label: 'Close',
    pairSpec: 'C/c',
    iconSemanticId: 'mc.icon.close',
    stateTokenRole: 'action.secondary',
    permissionRef: 'authenticated',
    effect: 'Close company selector and restore invoking control and active company.',
  },
  // 3. ledger.lookup
  {
    contextId: 'ledger.lookup',
    commandId: 'ledger.select',
    label: 'Select',
    pairSpec: 'S/s',
    iconSemanticId: 'mc.icon.ledger',
    stateTokenRole: 'action.primary',
    permissionRef: 'master.read',
    effect: 'Fill originating journal ledger cell with selected permitted ledger and return focus.',
  },
  {
    contextId: 'ledger.lookup',
    commandId: 'ledger.create',
    label: 'Create ledger',
    pairSpec: 'N/n',
    iconSemanticId: 'mc.icon.ledger',
    stateTokenRole: 'action.secondary',
    permissionRef: 'master.create',
    effect: 'Open nested ledger.create modal preserving parent journal draft.',
  },
  {
    contextId: 'ledger.lookup',
    commandId: 'editor.close',
    label: 'Close',
    pairSpec: 'C/c',
    iconSemanticId: 'mc.icon.close',
    stateTokenRole: 'action.secondary',
    permissionRef: 'authenticated',
    effect: 'Close lookup and return to originating cell with original value.',
  },
  // 4. ledger.create
  {
    contextId: 'ledger.create',
    commandId: 'ledger.save',
    label: 'Save',
    pairSpec: 'S/s',
    iconSemanticId: 'mc.icon.save-document',
    stateTokenRole: 'action.primary',
    permissionRef: 'master.create',
    effect: 'Persist valid secondary master and return permitted selection to originating cell.',
    isMutation: true,
  },
  {
    contextId: 'ledger.create',
    commandId: 'editor.close',
    label: 'Cancel',
    pairSpec: 'C/c',
    iconSemanticId: 'mc.icon.close',
    stateTokenRole: 'action.secondary',
    permissionRef: 'authenticated',
    effect: 'Discard unsaved secondary master form and return to originating cell.',
  },
  // 5. journal.approveConfirm
  {
    contextId: 'journal.approveConfirm',
    commandId: 'journal.approve',
    label: 'Approve',
    pairSpec: 'A/a',
    iconSemanticId: 'mc.icon.approve-shield',
    stateTokenRole: 'action.primary',
    permissionRef: 'voucher.approve',
    effect: 'Confirm FOUR_EYES approval on current saved version in open period.',
    isMutation: true,
  },
  {
    contextId: 'journal.approveConfirm',
    commandId: 'editor.close',
    label: 'Return',
    pairSpec: 'R/r',
    iconSemanticId: 'mc.icon.back',
    stateTokenRole: 'action.secondary',
    permissionRef: 'authenticated',
    effect: 'Close approval confirmation without approving and restore Approve control.',
  },
  // 6. journal.postConfirm
  {
    contextId: 'journal.postConfirm',
    commandId: 'journal.post',
    label: 'Post',
    pairSpec: 'P/p',
    iconSemanticId: 'mc.icon.post-ledger',
    stateTokenRole: 'action.primary',
    permissionRef: 'voucher.post',
    effect: 'Send single idempotent posting intention for current voucher.',
    isMutation: true,
  },
  {
    contextId: 'journal.postConfirm',
    commandId: 'editor.close',
    label: 'Return',
    pairSpec: 'R/r',
    iconSemanticId: 'mc.icon.back',
    stateTokenRole: 'action.secondary',
    permissionRef: 'authenticated',
    effect: 'Close posting confirmation without posting and restore Post control.',
  },
  // 7. editor.dirty
  {
    contextId: 'editor.dirty',
    commandId: 'journal.saveDraft',
    label: 'Save draft',
    pairSpec: 'S/s',
    iconSemanticId: 'mc.icon.save-document',
    stateTokenRole: 'action.primary',
    permissionRef: 'voucher.create',
    effect: 'Save editable draft once (never approving/posting) and navigate only after confirmed save.',
    isMutation: true,
  },
  {
    contextId: 'editor.dirty',
    commandId: 'editor.discard',
    label: 'Discard',
    pairSpec: 'D/d',
    iconSemanticId: 'mc.icon.discard-draft',
    stateTokenRole: 'action.danger',
    permissionRef: 'authenticated',
    effect: 'Discard local unsaved edits only and perform recorded navigation.',
  },
  {
    contextId: 'editor.dirty',
    commandId: 'editor.stay',
    label: 'Stay',
    pairSpec: 'T/t',
    iconSemanticId: null,
    stateTokenRole: 'action.secondary',
    permissionRef: 'authenticated',
    effect: 'Preserve unsaved draft and restore invoking control.',
  },
  // 8. context.dateDialog
  {
    contextId: 'context.dateDialog',
    commandId: 'context.applyDate',
    label: 'Apply',
    pairSpec: 'A/a',
    iconSemanticId: 'mc.icon.calendar',
    stateTokenRole: 'action.primary',
    permissionRef: 'company.read',
    effect: 'Validate and apply selected working date or period without saving/posting.',
  },
  {
    contextId: 'context.dateDialog',
    commandId: 'editor.close',
    label: 'Close',
    pairSpec: 'C/c',
    iconSemanticId: 'mc.icon.close',
    stateTokenRole: 'action.secondary',
    permissionRef: 'authenticated',
    effect: 'Discard unapplied date selection and restore invoking date control.',
  },
  // 9. report.filters
  {
    contextId: 'report.filters',
    commandId: 'report.applyFilters',
    label: 'Apply',
    pairSpec: 'A/a',
    iconSemanticId: 'mc.icon.filter',
    stateTokenRole: 'action.primary',
    permissionRef: 'report.read',
    effect: 'Validate report bounds and request authorised report.',
  },
  {
    contextId: 'report.filters',
    commandId: 'report.clearFilters',
    label: 'Clear',
    pairSpec: 'L/l',
    iconSemanticId: 'mc.icon.clear-filter',
    stateTokenRole: 'action.secondary',
    permissionRef: 'report.read',
    effect: 'Restore active fiscal period start/end in filter dialog.',
  },
  {
    contextId: 'report.filters',
    commandId: 'editor.close',
    label: 'Close',
    pairSpec: 'C/c',
    iconSemanticId: 'mc.icon.close',
    stateTokenRole: 'action.secondary',
    permissionRef: 'authenticated',
    effect: 'Discard unapplied filter edits and restore invoking filter control.',
  },
  // 10. report.output
  {
    contextId: 'report.output',
    commandId: 'report.print',
    label: 'Print',
    pairSpec: 'P/p',
    iconSemanticId: 'mc.icon.print',
    stateTokenRole: 'action.secondary',
    permissionRef: 'export',
    effect: 'Request server-rendered PDF and open browser PDF print viewer.',
  },
  {
    contextId: 'report.output',
    commandId: 'report.export',
    label: 'Export PDF',
    pairSpec: 'E/e',
    iconSemanticId: 'mc.icon.export',
    stateTokenRole: 'action.secondary',
    permissionRef: 'export',
    effect: 'Request server-rendered PDF and download PDF file.',
  },
  {
    contextId: 'report.output',
    commandId: 'editor.close',
    label: 'Close',
    pairSpec: 'C/c',
    iconSemanticId: 'mc.icon.close',
    stateTokenRole: 'action.secondary',
    permissionRef: 'authenticated',
    effect: 'Close output modal and restore output control.',
  },
  // 11. request.uncertain
  {
    contextId: 'request.uncertain',
    commandId: 'request.check',
    label: 'Check status',
    pairSpec: 'K/k',
    iconSemanticId: 'mc.icon.refresh',
    stateTokenRole: 'action.secondary',
    permissionRef: 'voucher.read',
    effect: 'Read current journal/outcome status using original resource without allocating a new key.',
  },
  {
    contextId: 'request.uncertain',
    commandId: 'request.retryOriginal',
    label: 'Retry original',
    pairSpec: 'R/r',
    iconSemanticId: 'mc.icon.refresh',
    stateTokenRole: 'action.secondary',
    permissionRef: 'voucher.post',
    effect: 'Repeat exact retained original body, Idempotency-Key, and If-Match after status check.',
    isMutation: true,
  },
  {
    contextId: 'request.uncertain',
    commandId: 'request.details',
    label: 'Details',
    pairSpec: 'D/d',
    iconSemanticId: 'mc.icon.details',
    stateTokenRole: 'action.secondary',
    permissionRef: 'authenticated',
    effect: 'Open message.details with safe correlation and status diagnostics.',
  },
  {
    contextId: 'request.uncertain',
    commandId: 'keyboard.open',
    label: 'Keyboard settings',
    pairSpec: 'B/b',
    iconSemanticId: 'mc.icon.keyboard',
    stateTokenRole: 'action.secondary',
    permissionRef: 'authenticated',
    effect: 'Open keyboard preferences without changing financial state.',
  },
  // 12. error.recoverable
  {
    contextId: 'error.recoverable',
    commandId: 'request.retryOriginal',
    label: 'Retry',
    pairSpec: 'R/r',
    iconSemanticId: 'mc.icon.refresh',
    stateTokenRole: 'action.secondary',
    permissionRef: 'authenticated',
    effect: 'Repeat safely repeatable request with original identity.',
    isMutation: true,
  },
  {
    contextId: 'error.recoverable',
    commandId: 'editor.close',
    label: 'Return',
    pairSpec: 'U/u',
    iconSemanticId: 'mc.icon.back',
    stateTokenRole: 'action.secondary',
    permissionRef: 'authenticated',
    effect: 'Keep draft and restore invoking control.',
  },
  {
    contextId: 'error.recoverable',
    commandId: 'request.details',
    label: 'Details',
    pairSpec: 'D/d',
    iconSemanticId: 'mc.icon.details',
    stateTokenRole: 'action.secondary',
    permissionRef: 'authenticated',
    effect: 'Open message.details diagnostic view.',
  },
  // 13. message.details
  {
    contextId: 'message.details',
    commandId: 'editor.close',
    label: 'Close',
    pairSpec: 'C/c',
    iconSemanticId: 'mc.icon.close',
    stateTokenRole: 'action.secondary',
    permissionRef: 'authenticated',
    effect: 'Close diagnostic details and return to Details button in parent context.',
  },
  // 14. keyboard.settings
  {
    contextId: 'keyboard.settings',
    commandId: 'keyboard.apply',
    label: 'Apply',
    pairSpec: 'A/a',
    iconSemanticId: null,
    stateTokenRole: 'action.primary',
    permissionRef: 'public',
    effect: 'Validate complete selected keyboard map and apply atomically to current tab.',
  },
  {
    contextId: 'keyboard.settings',
    commandId: 'keyboard.defaults',
    label: 'Reset defaults',
    pairSpec: 'D/d',
    iconSemanticId: null,
    stateTokenRole: 'action.secondary',
    permissionRef: 'public',
    effect: 'Reset pending preference form to CHARACTER, en-GB, null profile, and empty overrides.',
  },
  {
    contextId: 'keyboard.settings',
    commandId: 'editor.close',
    label: 'Close',
    pairSpec: 'C/c',
    iconSemanticId: 'mc.icon.close',
    stateTokenRole: 'action.secondary',
    permissionRef: 'public',
    effect: 'Discard unapplied preference edits and restore invoking Keyboard settings control.',
  },
  // 15. commands.menu (Doc 45 §3 fixed 13-row order)
  {
    contextId: 'commands.menu',
    commandId: 'company.open',
    label: 'Open company',
    pairSpec: 'O/o',
    iconSemanticId: 'mc.icon.company',
    stateTokenRole: 'action.primary',
    permissionRef: 'company.read',
    effect: 'Open the company selector.',
  },
  {
    contextId: 'commands.menu',
    commandId: 'ledger.create',
    label: 'Create ledger',
    pairSpec: 'L/l',
    iconSemanticId: 'mc.icon.ledger',
    stateTokenRole: 'action.secondary',
    permissionRef: 'master.create',
    effect: 'Open nested ledger creation flow in current permitted company.',
  },
  {
    contextId: 'commands.menu',
    commandId: 'journal.saveDraft',
    label: 'Save draft',
    pairSpec: 'S/s',
    iconSemanticId: 'mc.icon.save-document',
    stateTokenRole: 'action.primary',
    permissionRef: 'voucher.create',
    effect: 'Save valid journal draft.',
  },
  {
    contextId: 'commands.menu',
    commandId: 'journal.approve',
    label: 'Approve',
    pairSpec: 'A/a',
    iconSemanticId: 'mc.icon.approve-shield',
    stateTokenRole: 'action.primary',
    permissionRef: 'voucher.approve',
    effect: 'Open approval confirmation when approval predicate permits it.',
  },
  {
    contextId: 'commands.menu',
    commandId: 'journal.post',
    label: 'Post',
    pairSpec: 'P/p',
    iconSemanticId: 'mc.icon.post-ledger',
    stateTokenRole: 'action.primary',
    permissionRef: 'voucher.post',
    effect: 'Open posting confirmation when posting predicate permits it.',
  },
  {
    contextId: 'commands.menu',
    commandId: 'context.date',
    label: 'Change date',
    pairSpec: 'D/d',
    iconSemanticId: 'mc.icon.calendar',
    stateTokenRole: 'action.secondary',
    permissionRef: 'company.read',
    effect: 'Open date/period dialog.',
  },
  {
    contextId: 'commands.menu',
    commandId: 'company.features',
    label: 'Company features',
    pairSpec: 'F/f',
    iconSemanticId: null,
    stateTokenRole: 'action.secondary',
    permissionRef: 'company.update',
    effect: 'Open Company Features configuration.',
  },
  {
    contextId: 'commands.menu',
    commandId: 'report.open',
    label: 'Ledger report',
    pairSpec: 'R/r',
    iconSemanticId: 'mc.icon.ledger',
    stateTokenRole: 'action.secondary',
    permissionRef: 'report.read',
    effect: 'Open authorised ledger/trial-balance report flow.',
  },
  {
    contextId: 'commands.menu',
    commandId: 'report.back',
    label: 'Return to report',
    pairSpec: 'B/b',
    iconSemanticId: 'mc.icon.back',
    stateTokenRole: 'action.secondary',
    permissionRef: 'report.read',
    effect: 'Return to parent report context on overlay stack.',
  },
  {
    contextId: 'commands.menu',
    commandId: 'editor.close',
    label: 'Close',
    pairSpec: 'C/c',
    iconSemanticId: 'mc.icon.close',
    stateTokenRole: 'action.secondary',
    permissionRef: 'public',
    effect: 'Close command dialog without implicit abandonment.',
  },
  {
    contextId: 'commands.menu',
    commandId: 'keyboard.open',
    label: 'Keyboard settings',
    pairSpec: 'K/k',
    iconSemanticId: 'mc.icon.keyboard',
    stateTokenRole: 'action.secondary',
    permissionRef: 'public',
    effect: 'Open keyboard preferences without changing financial state.',
  },
  {
    contextId: 'commands.menu',
    commandId: 'report.output',
    label: 'Report output',
    pairSpec: 'U/u',
    iconSemanticId: 'mc.icon.print',
    stateTokenRole: 'action.secondary',
    permissionRef: 'export',
    effect: 'Open report.output for current authorised report.',
  },
  {
    contextId: 'commands.menu',
    commandId: 'report.refresh',
    label: 'Refresh report',
    pairSpec: 'H/h',
    iconSemanticId: 'mc.icon.refresh',
    stateTokenRole: 'action.secondary',
    permissionRef: 'report.read',
    effect: 'Refresh current report bounds.',
  },
]);

const BROWSER_STOLEN_CTRL_SHIFT_LETTERS: ReadonlySet<string> = new Set([
  'N', // New Incognito/Private Window
  'T', // Reopen Closed Tab
  'W', // Close Window
  'I', // DevTools
  'J', // Downloads / Console
  'C', // DevTools Inspector
  'P', // System Print Dialog
  'R', // Hard Reload
]);

function buildQualifiedProfileBindings(
  inventory: readonly CanonicalContextActionSpec[],
): readonly QualifiedModifierActionBinding[] {
  const byContext = new Map<string, CanonicalContextActionSpec[]>();
  for (const spec of inventory) {
    const list = byContext.get(spec.contextId) ?? [];
    list.push(spec);
    byContext.set(spec.contextId, list);
  }

  const out: QualifiedModifierActionBinding[] = [];
  for (const [, contextActions] of byContext.entries()) {
    const usedDefaults = new Set<string>();
    for (const action of contextActions) {
      const upper = EXPLICIT_EN_GB_ASCII_PAIRS[action.pairSpec][1];
      const rawCandidates = buildModifierCandidatesForLetter(upper);
      const qualified: string[] = [];
      const rejected: { readonly chord: string; readonly reason: string }[] = [];

      for (const rawChord of rawCandidates) {
        const normalized = normalizeShortcutString(rawChord);
        if (FORBIDDEN_BROWSER_SHORTCUTS.has(normalized)) {
          rejected.push({ chord: normalized, reason: 'Reserved OS/browser window shortcut.' });
          continue;
        }
        if (
          normalized.startsWith('Ctrl+Shift+') &&
          normalized.length === 'Ctrl+Shift+X'.length &&
          BROWSER_STOLEN_CTRL_SHIFT_LETTERS.has(normalized.slice(-1))
        ) {
          rejected.push({ chord: normalized, reason: 'Intercepted by desktop browser chrome.' });
          continue;
        }
        qualified.push(normalized);
      }

      const chosenDefault =
        qualified.find((c) => !usedDefaults.has(c)) ?? `Alt+Shift+${upper}`;
      usedDefaults.add(chosenDefault);

      out.push({
        contextId: action.contextId,
        commandId: action.commandId,
        defaultChord: chosenDefault,
        qualifiedCandidates: qualified,
        rejectedCandidates: rejected,
      });
    }
  }
  return out;
}

export const DEFAULT_QUALIFIED_MODIFIER_PROFILE_ID = 'mc-en-gb-qualified-mod-v1';

export const QUALIFIED_DESKTOP_MODIFIER_PROFILE: QualifiedModifierProfile = Object.freeze({
  profileId: DEFAULT_QUALIFIED_MODIFIER_PROFILE_ID,
  label: 'Qualified Desktop Browser Profile (en-GB QWERTY, No AltGraph)',
  locale: 'en-GB',
  environment: Object.freeze({
    browser: 'Chromium / Firefox / Safari Desktop',
    os: 'macOS / Linux / Windows',
    keyboardLayout: 'en-GB / US QWERTY',
    functionKeyMode: 'Standard F1-F12',
    ime: 'Inactive',
    assistiveTech: 'Compatible (non-intercepting)',
  }),
  bindings: buildQualifiedProfileBindings(INITIAL_BROWSER_MODAL_INVENTORY),
});
