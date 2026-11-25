import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * MoneyCraft 1.0.0 — Original 24 Semantic UI Icons (`VIS-001` / `UI-VIS-100` `Doc 46`).
 *
 * Renders literal trusted inline `24x24` `1.75`-unit rounded `currentColor` SVG primitives
 * (`aria-hidden="true"`, `focusable="false"`) matching `moneycraft-semantic-icons.json` (`U26–U49`)
 * byte-for-byte in element geometry without `innerHTML` or external icon dependencies.
 */
export type SemanticIconName =
  | 'save-document'
  | 'post-ledger'
  | 'approve-shield'
  | 'close'
  | 'discard-draft'
  | 'delete'
  | 'import'
  | 'export'
  | 'print'
  | 'waiting'
  | 'unknown'
  | 'error'
  | 'confirmed'
  | 'warning'
  | 'company'
  | 'ledger'
  | 'back'
  | 'filter'
  | 'clear-filter'
  | 'refresh'
  | 'details'
  | 'keyboard'
  | 'calendar'
  | 'search';

export type CanonicalSemanticIconId = `mc.icon.${SemanticIconName}`;

export type SemanticIconInput = SemanticIconName | CanonicalSemanticIconId;

export interface SemanticIconManifestRecord {
  readonly manifestRow: string;
  readonly name: SemanticIconName;
  readonly semanticId: CanonicalSemanticIconId;
  readonly file: string;
  readonly sha256: string;
  readonly designLabel: string;
}

export const ALL_24_SEMANTIC_ICON_NAMES: readonly SemanticIconName[] = [
  'save-document',
  'post-ledger',
  'approve-shield',
  'close',
  'discard-draft',
  'delete',
  'import',
  'export',
  'print',
  'waiting',
  'unknown',
  'error',
  'confirmed',
  'warning',
  'company',
  'ledger',
  'back',
  'filter',
  'clear-filter',
  'refresh',
  'details',
  'keyboard',
  'calendar',
  'search',
] as const;

export const SEMANTIC_ICON_MANIFEST: readonly SemanticIconManifestRecord[] = [
  {
    manifestRow: 'U26',
    name: 'save-document',
    semanticId: 'mc.icon.save-document',
    file: 'mc-save-document.svg',
    sha256: '65e2b33851ba86fca2dfc33ee7221d439d1509908f34b7ebce4a1528f9e6ecaf',
    designLabel: 'Save draft',
  },
  {
    manifestRow: 'U27',
    name: 'post-ledger',
    semanticId: 'mc.icon.post-ledger',
    file: 'mc-post-ledger.svg',
    sha256: '1498962daab74ee3474e0cf7fc9f010095e6d0bf6f4a5a6fba394e2d35a43559',
    designLabel: 'Post journal',
  },
  {
    manifestRow: 'U28',
    name: 'approve-shield',
    semanticId: 'mc.icon.approve-shield',
    file: 'mc-approve-shield.svg',
    sha256: '9036ba7a1be23a2521f2756b9ae86756d1bd19faf837655cdbf90308dcf8c9bb',
    designLabel: 'Approve journal',
  },
  {
    manifestRow: 'U29',
    name: 'close',
    semanticId: 'mc.icon.close',
    file: 'mc-close.svg',
    sha256: '7152adffdaf46d9936c9f340a1cf989a690be57c12329f721c52edfc63e25ed9',
    designLabel: 'Close or cancel this view',
  },
  {
    manifestRow: 'U30',
    name: 'discard-draft',
    semanticId: 'mc.icon.discard-draft',
    file: 'mc-discard-draft.svg',
    sha256: 'aeab6d9c9ed5f47a999acbfd988344592d01d3adb4ccb15280249d92b606b0a6',
    designLabel: 'Discard unsaved edits',
  },
  {
    manifestRow: 'U31',
    name: 'delete',
    semanticId: 'mc.icon.delete',
    file: 'mc-delete.svg',
    sha256: 'e3a501d7a88cbb68c2d65592c6903b22381f0616de12d13b889939d628daf940',
    designLabel: 'Delete admitted object',
  },
  {
    manifestRow: 'U32',
    name: 'import',
    semanticId: 'mc.icon.import',
    file: 'mc-import.svg',
    sha256: '7e906ce5a05a25bfe6fb0a495aee329e3eb425b9060e9134b7cbbe93bb888b7f',
    designLabel: 'Import into MoneyCraft',
  },
  {
    manifestRow: 'U33',
    name: 'export',
    semanticId: 'mc.icon.export',
    file: 'mc-export.svg',
    sha256: 'aa56714a024cfcf445e27220b6213053efdc445c63988460ebc0f8199c7c3392',
    designLabel: 'Export from MoneyCraft',
  },
  {
    manifestRow: 'U34',
    name: 'print',
    semanticId: 'mc.icon.print',
    file: 'mc-print.svg',
    sha256: '20aabf807a13050f668242fe7779f678fa6b11bc74f9b581747f4c1fde9119ff',
    designLabel: 'Print report',
  },
  {
    manifestRow: 'U35',
    name: 'waiting',
    semanticId: 'mc.icon.waiting',
    file: 'mc-waiting.svg',
    sha256: 'f3faa17f6e20ea41d4224eecc64b2af8e21805d4fe5031a6fd9e9c63b09e4499',
    designLabel: 'Pending',
  },
  {
    manifestRow: 'U36',
    name: 'unknown',
    semanticId: 'mc.icon.unknown',
    file: 'mc-unknown.svg',
    sha256: 'fce8547cfded177ec1ad9ab626a3372f5ba706d42214b0f11f58ab62c349a5d0',
    designLabel: 'Outcome unknown',
  },
  {
    manifestRow: 'U37',
    name: 'error',
    semanticId: 'mc.icon.error',
    file: 'mc-error.svg',
    sha256: '3ade7a78a3574aad0b4fdc99a26a81877b44a58505cf95d7f83a6b6417790644',
    designLabel: 'Failed',
  },
  {
    manifestRow: 'U38',
    name: 'confirmed',
    semanticId: 'mc.icon.confirmed',
    file: 'mc-confirmed.svg',
    sha256: 'b41e546c5908aa912a2290559ff844ba80105d920d22a8605066ed776a65dd6d',
    designLabel: 'Confirmed',
  },
  {
    manifestRow: 'U39',
    name: 'warning',
    semanticId: 'mc.icon.warning',
    file: 'mc-warning.svg',
    sha256: '9399c0b383005cce761f91e81800ab7ced440701d7097cd0a3a6259879f3d2ec',
    designLabel: 'Attention required',
  },
  {
    manifestRow: 'U40',
    name: 'company',
    semanticId: 'mc.icon.company',
    file: 'mc-company.svg',
    sha256: '2a1e406e30b73e04c7ddb3e3ed63794905988d96a9f271e9271e4740c1d3fa26',
    designLabel: 'Company',
  },
  {
    manifestRow: 'U41',
    name: 'ledger',
    semanticId: 'mc.icon.ledger',
    file: 'mc-ledger.svg',
    sha256: '80952a6fc29ad0ce8729533fe3c77efe90e0dd71247b97744a9329d63d6d9e28',
    designLabel: 'Ledger',
  },
  {
    manifestRow: 'U42',
    name: 'back',
    semanticId: 'mc.icon.back',
    file: 'mc-back.svg',
    sha256: 'd033183db84b0502716453e7a339f485ae362598f6701450cc4f21a91d90da34',
    designLabel: 'Return',
  },
  {
    manifestRow: 'U43',
    name: 'filter',
    semanticId: 'mc.icon.filter',
    file: 'mc-filter.svg',
    sha256: '22596967f3567289c005e030f85efe565b6ba04c03f1396ae57bebbd8eac69a1',
    designLabel: 'Filter',
  },
  {
    manifestRow: 'U44',
    name: 'clear-filter',
    semanticId: 'mc.icon.clear-filter',
    file: 'mc-clear-filter.svg',
    sha256: 'c95295fe738e11c039e84621dd73688134702256233f21ccc78cd4cc52d04fba',
    designLabel: 'Clear filters',
  },
  {
    manifestRow: 'U45',
    name: 'refresh',
    semanticId: 'mc.icon.refresh',
    file: 'mc-refresh.svg',
    sha256: 'b9458861a6443c826db874533f4f2612aee8ac6774af2361ccdf9c0a19135da2',
    designLabel: 'Refresh or check status',
  },
  {
    manifestRow: 'U46',
    name: 'details',
    semanticId: 'mc.icon.details',
    file: 'mc-details.svg',
    sha256: '2295d13de9f938f2e5166f8580c065fcf2e95c387bc5f9bb23b0b13e36e1a821',
    designLabel: 'Details',
  },
  {
    manifestRow: 'U47',
    name: 'keyboard',
    semanticId: 'mc.icon.keyboard',
    file: 'mc-keyboard.svg',
    sha256: '49350fcc1cd26f85e6146e860e7c7b4c63e9aa6f206b54385eda6b382d44166c',
    designLabel: 'Keyboard preferences',
  },
  {
    manifestRow: 'U48',
    name: 'calendar',
    semanticId: 'mc.icon.calendar',
    file: 'mc-calendar.svg',
    sha256: 'ada24283164dce63bc03c6ee47b7b633f0515aa2cefc45c2304cdf1e6381b9ed',
    designLabel: 'Date',
  },
  {
    manifestRow: 'U49',
    name: 'search',
    semanticId: 'mc.icon.search',
    file: 'mc-search.svg',
    sha256: '0fb1bfe8d3747d5276cc4a824d06594ef516ba74db995c8aa6efb03b8c1bb9d3',
    designLabel: 'Search',
  },
] as const;

const KNOWN_ICON_NAMES: ReadonlySet<string> = new Set(ALL_24_SEMANTIC_ICON_NAMES);

export function isKnownSemanticIconId(iconId: string | null | undefined): boolean {
  if (iconId === null) {
    return true;
  }
  if (!iconId) {
    return false;
  }
  const stripped = iconId.startsWith('mc.icon.') ? iconId.slice('mc.icon.'.length) : iconId;
  return KNOWN_ICON_NAMES.has(stripped);
}

export function normalizeSemanticIconName(raw: SemanticIconInput): SemanticIconName {
  const stripped = raw.startsWith('mc.icon.') ? raw.slice('mc.icon.'.length) : raw;
  return (KNOWN_ICON_NAMES.has(stripped) ? stripped : 'details') as SemanticIconName;
}

@Component({
  selector: 'mc-icon',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      [attr.width]="resolvedSize()"
      [attr.height]="resolvedSize()"
      fill="none"
      stroke="currentColor"
      stroke-width="1.75"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
      focusable="false"
      class="mc-semantic-svg"
      [class.mc-icon-16]="resolvedSize() === 16"
      [class.mc-icon-20]="resolvedSize() === 20"
      [class.mc-icon-24]="resolvedSize() === 24"
      [attr.data-mc-icon]="'mc.icon.' + normalizedName()"
    >
      @switch (normalizedName()) {
        @case ('save-document') {
          <path d="M5 3h9l5 5v13H5z M14 3v5h5 M12 10v6 M9 13l3 3 3-3 M8 19h8" />
        }
        @case ('post-ledger') {
          <path d="M4 3h10v18H4z M7 7h4 M7 11h4 M7 15h3 M13 14l3 3 5-6" />
        }
        @case ('approve-shield') {
          <path d="M12 3l8 3v6c0 4-4 7-8 9-4-2-8-5-8-9V6z M8 11l3 3 5-6" />
        }
        @case ('close') {
          <path d="M6 6l12 12 M18 6L6 18" />
        }
        @case ('discard-draft') {
          <path d="M5 3h9l5 5v13H5z M14 3v5h5 M8 11l8 7 M16 11l-8 7" />
        }
        @case ('delete') {
          <path d="M4 6h16 M9 6V3h6v3 M6 6l1 15h10l1-15 M10 10v7 M14 10v7" />
        }
        @case ('import') {
          <path d="M12 3v12 M8 11l4 4 4-4 M4 14v6h16v-6" />
        }
        @case ('export') {
          <path d="M12 15V3 M8 7l4-4 4 4 M4 14v6h16v-6" />
        }
        @case ('print') {
          <path d="M7 8V3h10v5 M7 17H3v-7h18v7h-4 M7 14h10v7H7z M17 12h1" />
        }
        @case ('waiting') {
          <circle cx="12" cy="12" r="9" />
          <path d="M12 6v6l4 3" />
        }
        @case ('unknown') {
          <path d="M12 2l10 10-10 10L2 12z M9.5 8.5a2.5 2.5 0 0 1 5 0c0 2-2.5 2-2.5 4" />
          <circle cx="12" cy="16" r="0.6" fill="currentColor" stroke="none" />
        }
        @case ('error') {
          <path d="M8 3h8l5 5v8l-5 5H8l-5-5V8z M12 7v6" />
          <circle cx="12" cy="17" r="0.7" fill="currentColor" stroke="none" />
        }
        @case ('confirmed') {
          <circle cx="12" cy="12" r="9" />
          <path d="M7 12l3 3 7-7" />
        }
        @case ('warning') {
          <path d="M12 3l10 18H2z M12 9v5" />
          <circle cx="12" cy="18" r="0.7" fill="currentColor" stroke="none" />
        }
        @case ('company') {
          <path d="M5 21V3h14v18 M3 21h18 M9 21v-5h6v5 M8 7h1 M15 7h1 M8 11h1 M15 11h1" />
        }
        @case ('ledger') {
          <path d="M12 5c-3-2-6-2-10-1v15c4-1 7-1 10 1 3-2 6-2 10-1V4c-4-1-7-1-10 1v15 M5 8h4 M5 12h4 M15 8h4 M15 12h4" />
        }
        @case ('back') {
          <path d="M10 5l-7 7 7 7 M3 12h18" />
        }
        @case ('filter') {
          <path d="M3 4h18l-7 8v7l-4 2v-9z" />
        }
        @case ('clear-filter') {
          <path d="M3 4h18l-7 8v7l-4 2v-9z M3 17l4 4 M7 17l-4 4" />
        }
        @case ('refresh') {
          <path d="M20 9a8 8 0 0 0-14-3L3 9 M3 4v5h5 M4 15a8 8 0 0 0 14 3l3-3 M16 15h5v5" />
        }
        @case ('details') {
          <circle cx="12" cy="12" r="9" />
          <path d="M12 11v6 M10 17h4" />
          <circle cx="12" cy="7" r="0.7" fill="currentColor" stroke="none" />
        }
        @case ('keyboard') {
          <rect x="2" y="5" width="20" height="14" rx="2" />
          <path d="M5 9h1 M9 9h1 M13 9h1 M17 9h2 M5 12h1 M9 12h1 M13 12h1 M17 12h2 M6 16h12" />
        }
        @case ('calendar') {
          <rect x="3" y="5" width="18" height="16" rx="2" />
          <path d="M7 3v4 M17 3v4 M3 10h18 M7 14h2 M15 14h2 M7 18h2 M15 18h2" />
        }
        @case ('search') {
          <circle cx="10" cy="10" r="7" />
          <path d="M15 15l6 6" />
        }
      }
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      line-height: 0;
    }

    .mc-semantic-svg {
      display: block;
      flex-shrink: 0;
    }

    .mc-icon-16 {
      width: 16px;
      height: 16px;
    }

    .mc-icon-20 {
      width: 20px;
      height: 20px;
    }

    .mc-icon-24 {
      width: 24px;
      height: 24px;
    }
  `,
})
export class SemanticIconComponent {
  readonly name = input.required<SemanticIconInput>();
  readonly size = input<16 | 20 | 24>(20);

  readonly normalizedName = computed(() => normalizeSemanticIconName(this.name()));
  readonly resolvedSize = computed<16 | 20 | 24>(() => {
    const requested = this.size();
    if (requested === 16 || requested === 24) {
      return requested;
    }
    return 20;
  });
}
