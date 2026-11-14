/**
 * MoneyCraft 1.0.0 — Browser Shortcut Collision Guard & Register (`UX-004`–`UX-006`)
 *
 * Enforces that OS/browser-owned window management shortcuts (`Ctrl+N`, `Ctrl+T`, `Ctrl+W`)
 * are NEVER bound by application code, and normalizes cross-platform key chords
 * (including macOS Option/Alt dead-key character remapping via `KeyboardEvent.code`).
 */

export interface CollisionMatrixEntry {
  readonly commandId: string;
  readonly tallyDesktopBinding: string;
  readonly moneycraftPrimaryBinding: string;
  readonly alternateBinding?: string;
  readonly browserConflictNotes: string;
}

export const FORBIDDEN_BROWSER_SHORTCUTS: ReadonlySet<string> = new Set([
  'Ctrl+N',
  'Ctrl+T',
  'Ctrl+W',
  'Ctrl+Shift+N',
  'Ctrl+Shift+T',
  'Ctrl+Tab',
  'Ctrl+Shift+Tab',
  'Meta+N',
  'Meta+T',
  'Meta+W',
  'Meta+Q',
]);

export const BROWSER_COLLISION_REGISTER: readonly CollisionMatrixEntry[] = [
  {
    commandId: 'nav.goTo',
    tallyDesktopBinding: 'Alt+G',
    moneycraftPrimaryBinding: 'Alt+G',
    browserConflictNotes: 'Safe across Chrome, Firefox, Safari; normalized via KeyboardEvent.code on macOS.',
  },
  {
    commandId: 'nav.switchTo',
    tallyDesktopBinding: 'Ctrl+G',
    moneycraftPrimaryBinding: 'Ctrl+G',
    browserConflictNotes: 'Intercepts Find-Next when ERP shell has focus via preventDefault().',
  },
  {
    commandId: 'search.smartFind',
    tallyDesktopBinding: 'Alt+F',
    moneycraftPrimaryBinding: 'Alt+F',
    alternateBinding: 'Ctrl+F',
    browserConflictNotes: 'Double-Ctrl+F within 700ms delegates cleanly to native browser Find.',
  },
  {
    commandId: 'voucher.autofill',
    tallyDesktopBinding: 'Ctrl+F',
    moneycraftPrimaryBinding: 'Ctrl+Alt+F',
    browserConflictNotes: 'Remapped from Ctrl+F to Ctrl+Alt+F + right-rail button to free Ctrl+F for SmartFind.',
  },
  {
    commandId: 'company.features',
    tallyDesktopBinding: 'F11',
    moneycraftPrimaryBinding: 'F11',
    alternateBinding: 'Shift+F11',
    browserConflictNotes: 'Shift+F11 + right-rail button provided where OS/browser reserves F11 fullscreen.',
  },
  {
    commandId: 'voucher.changeMode',
    tallyDesktopBinding: 'Ctrl+H',
    moneycraftPrimaryBinding: 'Ctrl+H',
    alternateBinding: 'Alt+H',
    browserConflictNotes: 'Alt+H fallback provided where browser reserves Ctrl+H for History.',
  },
  {
    commandId: 'screen.accept',
    tallyDesktopBinding: 'Ctrl+A',
    moneycraftPrimaryBinding: 'Ctrl+A',
    alternateBinding: 'Ctrl+Enter',
    browserConflictNotes: 'Inside editable text inputs, Ctrl+A preserves text select-all while Ctrl+Enter accepts.',
  },
  {
    commandId: 'register.duplicateVoucher',
    tallyDesktopBinding: 'Alt+2',
    moneycraftPrimaryBinding: 'Alt+2',
    alternateBinding: 'Alt+Insert',
    browserConflictNotes: 'Never binds Ctrl+N (New Window).',
  },
  {
    commandId: 'register.cancelVoucher',
    tallyDesktopBinding: 'Alt+X',
    moneycraftPrimaryBinding: 'Alt+X',
    browserConflictNotes: 'Never binds Ctrl+W (Close Tab).',
  },
];

export function normalizeShortcutString(raw: string): string {
  const parts = raw
    .split('+')
    .map((p) => p.trim())
    .filter((p) => p.length > 0);

  let ctrl = false;
  let alt = false;
  let shift = false;
  let meta = false;
  let mainKey = '';

  for (const part of parts) {
    const lower = part.toLowerCase();
    if (lower === 'ctrl' || lower === 'control') {
      ctrl = true;
    } else if (lower === 'alt' || lower === 'option') {
      alt = true;
    } else if (lower === 'shift') {
      shift = true;
    } else if (lower === 'meta' || lower === 'cmd' || lower === 'command') {
      meta = true;
    } else if (lower === 'esc' || lower === 'escape') {
      mainKey = 'Escape';
    } else if (lower === 'enter' || lower === 'return') {
      mainKey = 'Enter';
    } else if (lower === 'space' || lower === 'spacebar') {
      mainKey = 'Space';
    } else if (lower === 'ins' || lower === 'insert') {
      mainKey = 'Insert';
    } else if (/^f([1-9]|1[0-2])$/i.test(part)) {
      mainKey = part.toUpperCase();
    } else if (part.length === 1) {
      mainKey = part.toUpperCase();
    } else {
      mainKey = part[0].toUpperCase() + part.slice(1);
    }
  }

  const mods: string[] = [];
  if (ctrl) mods.push('Ctrl');
  if (alt) mods.push('Alt');
  if (shift) mods.push('Shift');
  if (meta) mods.push('Meta');
  if (mainKey) mods.push(mainKey);
  return mods.join('+');
}

export function assertSafeBrowserShortcut(binding: string): string {
  const normalized = normalizeShortcutString(binding);
  if (FORBIDDEN_BROWSER_SHORTCUTS.has(normalized)) {
    throw new Error(
      `BrowserCollisionGuard: Binding "${normalized}" is strictly prohibited because it collides with OS/browser window controls.`,
    );
  }
  return normalized;
}

export function extractNormalizedChordFromEvent(event: KeyboardEvent): string | null {
  if (event.isComposing || event.keyCode === 229) {
    return null;
  }

  const rawKey = event.key;
  if (rawKey === 'Control' || rawKey === 'Alt' || rawKey === 'Shift' || rawKey === 'Meta') {
    return null;
  }

  // Respect European AltGr character composition inside inputs
  if (typeof event.getModifierState === 'function' && event.getModifierState('AltGraph')) {
    return null;
  }

  let mainKey: string;
  const code = event.code || '';

  if (code.startsWith('Key') && code.length === 4) {
    mainKey = code.slice(3).toUpperCase();
  } else if (code.startsWith('Digit') && code.length === 6) {
    mainKey = code.slice(5);
  } else if (/^F([1-9]|1[0-2])$/i.test(rawKey)) {
    mainKey = rawKey.toUpperCase();
  } else if (rawKey === 'Escape' || rawKey === 'Esc') {
    mainKey = 'Escape';
  } else if (rawKey === 'Enter') {
    mainKey = 'Enter';
  } else if (rawKey === ' ') {
    mainKey = 'Space';
  } else if (rawKey === 'Insert') {
    mainKey = 'Insert';
  } else if (rawKey.length === 1) {
    mainKey = rawKey.toUpperCase();
  } else {
    mainKey = rawKey;
  }

  const parts: string[] = [];
  if (event.ctrlKey) parts.push('Ctrl');
  if (event.altKey) parts.push('Alt');
  if (event.shiftKey) parts.push('Shift');
  if (event.metaKey) parts.push('Meta');
  parts.push(mainKey);

  return parts.join('+');
}

export function isEditableTextElement(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  if (target.isContentEditable) {
    return true;
  }
  const tag = target.tagName;
  if (tag === 'TEXTAREA') {
    return true;
  }
  if (tag === 'INPUT') {
    const inputType = ((target as HTMLInputElement).type || 'text').toLowerCase();
    return !['button', 'checkbox', 'radio', 'submit', 'reset'].includes(inputType);
  }
  return false;
}
