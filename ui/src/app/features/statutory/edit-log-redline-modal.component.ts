import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import {
  ActiveModalContextHandle,
  ShortcutCommandRegistry,
  asciiPair,
} from '../../core/keyboard/shortcut-command.registry';
import { ExactDecimal } from '../../core/money/exact-decimal';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { SemanticIconComponent } from '../../shared/components/semantic-icon/semantic-icon.component';
import { FocusReturnDirective } from '../../shared/directives/focus-return.directive';
import { EditLogModalService } from './edit-log-modal.service';
import { EditLogVersionDto } from './statutory.models';
import { StatutoryService } from './statutory.service';

export interface RedlineDiffRow {
  readonly fieldPath: string;
  readonly beforeFormatted: string;
  readonly afterFormatted: string;
  readonly isNumericAmount: boolean;
  readonly isAltered: boolean;
}

function formatDiffValue(val: unknown): { readonly text: string; readonly isAmount: boolean } {
  if (val === null || val === undefined || val === '') {
    return { text: '—', isAmount: false };
  }
  if (typeof val === 'boolean') {
    return { text: val ? 'Yes' : 'No', isAmount: false };
  }
  if (typeof val === 'string') {
    const trimmed = val.trim();
    const cleaned = trimmed.replace(/,/g, '');
    if (/^-?\d+\.\d{2}$/.test(cleaned)) {
      const parsed = ExactDecimal.tryParse(cleaned, 2, 'HALF_UP');
      if (parsed) {
        return { text: parsed.formatIndian({ scale: 2 }), isAmount: true };
      }
    }
    return { text: trimmed, isAmount: false };
  }
  if (typeof val === 'number') {
    return { text: String(val), isAmount: false };
  }
  try {
    return { text: JSON.stringify(val), isAmount: false };
  } catch {
    return { text: String(val), isAmount: false };
  }
}

function flattenSnapshot(
  obj: Record<string, unknown>,
  prefix = '',
  out: Map<string, unknown> = new Map(),
): Map<string, unknown> {
  for (const key of Object.keys(obj)) {
    const path = prefix ? `${prefix}.${key}` : key;
    const value = obj[key];
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      flattenSnapshot(value as Record<string, unknown>, path, out);
    } else {
      out.set(path, value);
    }
  }
  return out;
}

@Component({
  selector: 'mc-edit-log-redline-modal',
  standalone: true,
  imports: [CodeBadgeComponent, FocusReturnDirective, SemanticIconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (modalService.activeSession(); as session) {
      <div
        class="mc-modal-backdrop edit-log-backdrop"
        role="dialog"
        aria-modal="true"
        aria-labelledby="mc-edit-log-title"
        mcFocusReturn
        (keydown)="onDialogKeydown($event)"
      >
        <div class="mc-panel edit-log-dialog">
          <header class="edit-log-header">
            <div class="title-cluster">
              <span class="mca-pill">MCA Rule 3(1) Immutable Audit Trail</span>
              <h2 id="mc-edit-log-title">Edit Log — {{ session.entityLabel }}</h2>
              <span class="entity-meta">
                Entity: <mc-code-badge [code]="session.entityType" />
              </span>
            </div>

            <div class="header-actions">
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                data-testid="edit-log-toggle-altered-btn"
                [attr.aria-pressed]="showAlteredValuesOnly()"
                (click)="toggleShowAlteredOnly()"
              >
                <mc-icon name="filter" [size]="16" />
                <span>
                  @if (showAlteredValuesOnly()) {
                    Showing: Altered Values Only (F12: Show All)
                  } @else {
                    Showing: All Fields (F12: Altered Only)
                  }
                </span>
              </button>
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                data-testid="edit-log-close-btn"
                (click)="modalService.close()"
              >
                <mc-icon name="close" [size]="16" />
                <span>Close</span>
                @if (closeHint(); as cHint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ cHint }}]</span>
                }
              </button>
            </div>
          </header>

          @if (loading()) {
            <div class="state-banner" data-testid="edit-log-loading">
              Loading immutable MCA Edit Log versions...
            </div>
          } @else if (errorMessage(); as err) {
            <div class="state-banner state-error" role="alert">{{ err }}</div>
          } @else if (versions().length === 0) {
            <div class="mc-empty-records" data-testid="edit-log-empty">No matching records</div>
          } @else {
            <div class="edit-log-body">
              <aside class="version-timeline" aria-label="Version History">
                <div class="timeline-heading">
                  Version History ({{ versions().length }})
                </div>
                <ul class="timeline-list" role="listbox" aria-label="Edit Log Versions">
                  @for (ver of versions(); track ver.versionNo; let idx = $index) {
                    <li
                      role="option"
                      tabindex="0"
                      class="timeline-item"
                      [attr.data-testid]="'edit-log-version-' + ver.versionNo"
                      [class.selected]="selectedIndex() === idx"
                      [attr.aria-selected]="selectedIndex() === idx"
                      (click)="selectVersionIndex(idx)"
                      (keydown.enter)="selectVersionIndex(idx)"
                    >
                      <div class="ver-top-row">
                        <span class="ver-badge">Version {{ ver.versionNo }}</span>
                        <span
                          class="op-badge"
                          [class.op-create]="ver.operation === 'CREATE'"
                          [class.op-alter]="ver.operation === 'ALTER'"
                          [class.op-cancel]="ver.operation === 'CANCEL' || ver.operation === 'DELETE'"
                        >
                          {{ ver.operation }}
                        </span>
                      </div>
                      <div class="ver-actor-row">
                        <strong>{{ ver.actorUsername }}</strong>
                        <span>{{ ver.occurredAt }}</span>
                      </div>
                      <div class="ver-ids-row">
                        <span>Master ID: <span class="mc-code">{{ ver.masterId }}</span></span>
                        <span>Alter ID: <span class="mc-code">{{ ver.alterId }}</span></span>
                      </div>
                    </li>
                  }
                </ul>
              </aside>

              <section class="redline-viewer" aria-label="Version Redline Comparison">
                @if (selectedVersion(); as current) {
                  <div class="comparison-meta-bar">
                    <div class="comparison-label" data-testid="edit-log-comparison-heading">
                      @if (previousVersion(); as prev) {
                        Comparing <strong>Version {{ prev.versionNo }}</strong> ({{ prev.operation }})
                        &#x2192; <strong>Version {{ current.versionNo }}</strong> ({{ current.operation }})
                      } @else {
                        Baseline <strong>Version {{ current.versionNo }}</strong> ({{ current.operation }})
                      }
                    </div>

                    @if (selectedIndex() > 0) {
                      <div class="baseline-picker">
                        <label for="mc-edit-log-baseline-sel" class="hash-label">Compare With:</label>
                        <select
                          id="mc-edit-log-baseline-sel"
                          class="mc-select"
                          data-testid="edit-log-compare-baseline-select"
                          [value]="effectiveBaselineIndex()"
                          (change)="onBaselineSelectChange($event)"
                        >
                          @for (bVer of baselineCandidates(); track bVer.version.versionNo) {
                            <option [value]="bVer.index">
                              Version {{ bVer.version.versionNo }} ({{ bVer.version.operation }})
                            </option>
                          }
                        </select>
                      </div>
                    }

                    <div class="hash-cluster">
                      <span class="hash-label">SHA-256:</span>
                      <span class="mc-code hash-value" data-testid="edit-log-sha256">
                        {{ current.snapshotSha256Hex || '—' }}
                      </span>
                    </div>
                  </div>

                  <div class="filter-indicator" data-testid="edit-log-filter-mode">
                    @if (showAlteredValuesOnly()) {
                      Filter Mode: <strong>Show Altered Values Only</strong> (Default — MCA Rule 3(1) Audit Trail)
                    } @else {
                      Filter Mode: <strong>Show All Snapshot Fields</strong>
                    }
                  </div>

                  @if (visibleDiffRows().length === 0) {
                    <div class="empty-diff-note" data-testid="edit-log-no-altered-fields">
                      @if (previousVersion() === null) {
                        Initial Version 1 baseline captured. Press <strong>F12</strong> to inspect all snapshot fields.
                      } @else {
                        No altered field values between Version {{ previousVersion()?.versionNo }} and Version {{ current.versionNo }}.
                      }
                    </div>
                  } @else {
                    <table class="mc-table redline-table" data-testid="edit-log-redline-table">
                      <thead>
                        <tr>
                          <th>Field Path</th>
                          <th>
                            @if (previousVersion(); as prev) {
                              Previous Value (v{{ prev.versionNo }})
                            } @else {
                              Previous Value
                            }
                          </th>
                          <th>Current Value (v{{ current.versionNo }})</th>
                        </tr>
                      </thead>
                      <tbody>
                        @for (row of visibleDiffRows(); track row.fieldPath) {
                          <tr
                            [class.row-altered]="row.isAltered"
                            [attr.data-testid]="'diff-row-' + row.fieldPath"
                          >
                            <td class="mc-code field-col">{{ row.fieldPath }}</td>
                            <td
                              class="before-col"
                              [class.diff-removed]="row.isAltered"
                              [class.mc-amount]="row.isNumericAmount"
                              data-testid="diff-before-cell"
                            >
                              {{ row.beforeFormatted }}
                            </td>
                            <td
                              class="after-col"
                              [class.diff-added]="row.isAltered"
                              [class.mc-amount]="row.isNumericAmount"
                              data-testid="diff-after-cell"
                            >
                              {{ row.afterFormatted }}
                            </td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  }
                }
              </section>
            </div>
          }
        </div>
      </div>
    }
  `,
  styles: `
    .edit-log-backdrop {
      z-index: 460;
    }

    .edit-log-dialog {
      width: min(1040px, 95vw);
      max-height: 86vh;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      background: #ffffff;
    }

    .edit-log-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: 0.75rem 1rem;
      background: #142f42;
      color: #ffffff;
    }

    .title-cluster {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.65rem;
    }

    .title-cluster h2 {
      margin: 0;
      font-size: 1rem;
      font-weight: 700;
      color: #ffffff;
    }

    .mca-pill {
      padding: 0.14rem 0.5rem;
      border-radius: 999px;
      background: rgba(76, 225, 189, 0.18);
      border: 1px solid #4ce1bd;
      color: #4ce1bd;
      font-size: 0.72rem;
      font-weight: 600;
    }

    .entity-meta {
      font-size: 0.78rem;
      color: #d4e4ec;
      font-weight: 500;
    }

    .header-actions {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }

    .state-banner {
      padding: 1.5rem;
      font-size: 0.88rem;
      font-weight: 500;
      color: #142f42;
    }

    .state-error {
      background: #fdecec;
      color: #8f1d1d;
    }

    .edit-log-body {
      display: grid;
      grid-template-columns: 290px 1fr;
      min-height: 360px;
      max-height: calc(86vh - 64px);
      overflow: hidden;
    }

    .version-timeline {
      border-right: 1px solid var(--mc-border-subtle, #d4e2e0);
      background: #f6faf9;
      display: flex;
      flex-direction: column;
      overflow-y: auto;
    }

    .timeline-heading {
      padding: 0.6rem 0.85rem;
      font-size: 0.76rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: #142f42;
      border-bottom: 1px solid #d4e2e0;
    }

    .timeline-list {
      list-style: none;
      margin: 0;
      padding: 0.45rem;
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
    }

    .timeline-item {
      padding: 0.55rem 0.65rem;
      border-radius: 6px;
      border: 1px solid #d4e2e0;
      background: #ffffff;
      cursor: pointer;
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }

    .timeline-item.selected {
      border-color: #008d87;
      background: #eaf7f5;
      box-shadow: inset 3px 0 0 #008d87;
    }

    .ver-top-row,
    .ver-actor-row,
    .ver-ids-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.4rem;
      font-size: 0.76rem;
    }

    .ver-badge {
      font-weight: 700;
      color: #142f42;
    }

    .op-badge {
      padding: 0.08rem 0.42rem;
      border-radius: 4px;
      font-size: 0.68rem;
      font-weight: 700;
      background: #e3eceb;
      color: #142f42;
    }

    .op-create {
      background: #dff6ef;
      color: #006d68;
    }

    .op-alter {
      background: #fff3d6;
      color: #8a5300;
    }

    .op-cancel {
      background: #fde2e2;
      color: #9e1c1c;
    }

    .ver-actor-row {
      color: #3f5866;
      font-weight: 400;
    }

    .ver-ids-row {
      color: #4f6775;
      font-size: 0.72rem;
    }

    .redline-viewer {
      padding: 0.85rem 1rem;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 0.65rem;
    }

    .comparison-meta-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 0.5rem;
      padding: 0.5rem 0.75rem;
      background: #f6faf9;
      border: 1px solid #d4e2e0;
      border-radius: 6px;
      font-size: 0.82rem;
      color: #142f42;
    }

    .hash-cluster {
      display: flex;
      align-items: center;
      gap: 0.35rem;
      font-size: 0.75rem;
    }

    .hash-label {
      font-weight: 600;
      color: #4f6775;
    }

    .hash-value {
      max-width: 340px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .filter-indicator {
      font-size: 0.78rem;
      color: #006d68;
      font-weight: 500;
    }

    .empty-diff-note {
      padding: 1.25rem;
      background: #f6faf9;
      border: 1px dashed #b9cfcc;
      border-radius: 6px;
      font-size: 0.84rem;
      color: #3f5866;
    }

    .redline-table th,
    .redline-table td {
      padding: 0.45rem 0.65rem;
      vertical-align: top;
      font-size: 0.82rem;
    }

    .field-col {
      width: 32%;
      color: #142f42;
    }

    .diff-removed {
      background: #fff1f1;
      color: #9b1c1c;
      text-decoration: line-through;
      font-weight: 600;
    }

    .diff-added {
      background: #ebfbf5;
      color: #006d68;
      font-weight: 700;
    }
  `,
})
export class EditLogRedlineModalComponent {
  readonly modalService = inject(EditLogModalService);
  private readonly statutoryService = inject(StatutoryService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal<boolean>(false);
  readonly errorMessage = signal<string | null>(null);
  readonly versions = signal<readonly EditLogVersionDto[]>([]);
  readonly selectedIndex = signal<number>(0);
  readonly compareBaselineIndex = signal<number | null>(null);

  /**
   * TallyPrime Release 7.2 Mandatory Parity Rule:
   * Default to `Show Altered Values Only = true` whenever opened.
   */
  readonly showAlteredValuesOnly = signal<boolean>(true);

  readonly selectedVersion = computed<EditLogVersionDto | null>(() => {
    const list = this.versions();
    const idx = this.selectedIndex();
    return list[idx] ?? null;
  });

  readonly effectiveBaselineIndex = computed<number>(() => {
    const idx = this.selectedIndex();
    if (idx <= 0) {
      return -1;
    }
    const custom = this.compareBaselineIndex();
    if (custom !== null && custom >= 0 && custom < idx) {
      return custom;
    }
    return idx - 1;
  });

  readonly baselineCandidates = computed<
    readonly { readonly index: number; readonly version: EditLogVersionDto }[]
  >(() => {
    const list = this.versions();
    const idx = this.selectedIndex();
    const candidates: { readonly index: number; readonly version: EditLogVersionDto }[] = [];
    for (let i = 0; i < idx && i < list.length; i++) {
      candidates.push({ index: i, version: list[i] });
    }
    return candidates;
  });

  readonly previousVersion = computed<EditLogVersionDto | null>(() => {
    const list = this.versions();
    const baseIdx = this.effectiveBaselineIndex();
    return baseIdx >= 0 ? (list[baseIdx] ?? null) : null;
  });

  readonly visibleDiffRows = computed<readonly RedlineDiffRow[]>(() => {
    const current = this.selectedVersion();
    if (!current) {
      return [];
    }
    const prev = this.previousVersion();
    const alteredOnly = this.showAlteredValuesOnly();
    const isAdjacentComparison = this.effectiveBaselineIndex() === this.selectedIndex() - 1;

    const explicitMap = new Map<
      string,
      { readonly beforeValue: unknown; readonly afterValue: unknown }
    >();
    if (isAdjacentComparison) {
      for (const diff of current.changedFields) {
        if (diff.fieldPath) {
          explicitMap.set(diff.fieldPath, {
            beforeValue: diff.beforeValue,
            afterValue: diff.afterValue,
          });
        }
      }
    }

    const prevSnap =
      prev && typeof prev.canonicalSnapshot === 'object' && prev.canonicalSnapshot !== null
        ? flattenSnapshot(prev.canonicalSnapshot)
        : new Map<string, unknown>();
    const currSnap =
      typeof current.canonicalSnapshot === 'object' && current.canonicalSnapshot !== null
        ? flattenSnapshot(current.canonicalSnapshot)
        : new Map<string, unknown>();

    if (explicitMap.size === 0 && prev) {
      const allKeys = new Set<string>([...prevSnap.keys(), ...currSnap.keys()]);
      for (const key of allKeys) {
        const bVal = prevSnap.get(key);
        const aVal = currSnap.get(key);
        if (JSON.stringify(bVal) !== JSON.stringify(aVal)) {
          explicitMap.set(key, { beforeValue: bVal, afterValue: aVal });
        }
      }
    }

    if (alteredOnly) {
      const rows: RedlineDiffRow[] = [];
      for (const [fieldPath, pair] of explicitMap.entries()) {
        const beforeFmt = formatDiffValue(pair.beforeValue);
        const afterFmt = formatDiffValue(pair.afterValue);
        rows.push({
          fieldPath,
          beforeFormatted: beforeFmt.text,
          afterFormatted: afterFmt.text,
          isNumericAmount: beforeFmt.isAmount || afterFmt.isAmount,
          isAltered: true,
        });
      }
      return rows;
    }

    // Show All Fields mode (`F12` toggled)
    const allFieldNames = new Set<string>([
      ...explicitMap.keys(),
      ...prevSnap.keys(),
      ...currSnap.keys(),
    ]);
    const allRows: RedlineDiffRow[] = [];
    for (const fieldPath of Array.from(allFieldNames).sort()) {
      const explicit = explicitMap.get(fieldPath);
      const rawBefore = explicit ? explicit.beforeValue : prevSnap.get(fieldPath);
      const rawAfter = explicit ? explicit.afterValue : currSnap.get(fieldPath);
      const isAltered =
        explicit !== undefined || (prev !== null && JSON.stringify(rawBefore) !== JSON.stringify(rawAfter));
      const beforeFmt = formatDiffValue(rawBefore);
      const afterFmt = formatDiffValue(rawAfter);
      allRows.push({
        fieldPath,
        beforeFormatted: beforeFmt.text,
        afterFormatted: afterFmt.text,
        isNumericAmount: beforeFmt.isAmount || afterFmt.isAmount,
        isAltered,
      });
    }
    return allRows;
  });

  private modalHandle: ActiveModalContextHandle | null = null;

  readonly closeHint = computed(() =>
    this.shortcutRegistry.effectiveActionHint('message.details', 'editor.close', asciiPair('C/c')),
  );

  constructor() {
    const unbindF12 = this.shortcutRegistry.bindHandler(
      'screen.configure',
      'MODAL',
      () => {
        this.toggleShowAlteredOnly();
      },
      () => this.modalService.isOpen(),
    );
    this.destroyRef.onDestroy(() => {
      unbindF12();
      this.releaseModal();
    });

    effect(() => {
      const session = this.modalService.activeSession();
      if (!session) {
        this.releaseModal();
        this.versions.set([]);
        this.errorMessage.set(null);
        return;
      }
      this.releaseModal();
      this.modalHandle = this.shortcutRegistry.openModalContext(
        [
          {
            contextId: 'message.details',
            locale: 'en-GB',
            commandId: 'editor.close',
            label: 'Close',
            letterPair: asciiPair('C/c'),
            permissionRef: 'authenticated',
            effect: 'Close MCA Edit Log Redline viewer.',
            normalActivation: () => this.modalService.close(),
            iconSemanticId: 'mc.icon.close',
            stateTokenRole: 'action.secondary',
          },
        ],
        'en-GB',
      );
      this.showAlteredValuesOnly.set(true);
      void this.loadVersions(session.companyId, session.entityType, session.entityId);
    });
  }

  private releaseModal(): void {
    if (this.modalHandle) {
      this.modalHandle.close();
      this.modalHandle = null;
    }
  }

  toggleShowAlteredOnly(): void {
    this.showAlteredValuesOnly.update((v) => !v);
  }

  selectVersionIndex(index: number): void {
    const list = this.versions();
    if (index >= 0 && index < list.length) {
      this.selectedIndex.set(index);
      this.compareBaselineIndex.set(null);
    }
  }

  selectCompareBaselineIndex(index: number | null): void {
    if (index === null) {
      this.compareBaselineIndex.set(null);
      return;
    }
    if (index >= 0 && index < this.selectedIndex()) {
      this.compareBaselineIndex.set(index);
    }
  }

  onBaselineSelectChange(event: Event): void {
    const target = event.target as HTMLSelectElement | null;
    if (!target) {
      return;
    }
    const parsed = Number.parseInt(target.value, 10);
    if (Number.isFinite(parsed)) {
      this.selectCompareBaselineIndex(parsed);
    }
  }

  onDialogKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.selectVersionIndex(Math.max(0, this.selectedIndex() - 1));
    } else if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.selectVersionIndex(Math.min(this.versions().length - 1, this.selectedIndex() + 1));
    }
  }

  private async loadVersions(
    companyId: string,
    entityType: string,
    entityId: string,
  ): Promise<void> {
    this.loading.set(true);
    this.errorMessage.set(null);
    try {
      const list = await this.statutoryService.getEditLogVersions(companyId, entityType, entityId);
      this.versions.set(list);
      // Default to latest version so `v(N-1) -> vN` redline diffs display immediately!
      this.selectedIndex.set(list.length > 0 ? list.length - 1 : 0);
    } catch {
      this.errorMessage.set('Unable to load MCA Edit Log versions for this record.');
    } finally {
      this.loading.set(false);
    }
  }
}
