import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { isEditableTextElement } from '../../core/keyboard/collision-guard';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { ChartOfAccountsStore } from './chart-of-accounts.store';
import { AccountNature, CoaTreeNode, Ledger } from './masters.models';
import { SecondaryMasterModalService } from './secondary-master-modal.service';

export interface FlattenedCoaRow {
  readonly rowKey: string;
  readonly rowType: 'GROUP' | 'LEDGER';
  readonly id: string;
  readonly parentGroupId: string | null;
  readonly name: string;
  readonly aliasCode: string | null;
  readonly gstin: string | null;
  readonly nature: AccountNature;
  readonly depth: number;
  readonly hasChildren: boolean;
  readonly expanded: boolean;
  readonly isSystem: boolean;
  readonly openingDebit: string;
  readonly openingCredit: string;
}

@Component({
  selector: 'mc-coa-explorer',
  standalone: true,
  imports: [FormsModule, RouterLink, CodeBadgeComponent, IndianCurrencyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-coa-layout">
      <!-- Top Header & Controls -->
      <div class="mc-coa-header">
        <div>
          <h1>Chart of Accounts</h1>
          <p class="mc-coa-sub">
            28 Predefined TallyPrime 7.2 Groups, Custom Sub-Groups &amp; Ledgers &bull;
            <strong><span class="mc-key-alt">F1</span></strong> = {{ detailedMode() ? 'Condensed' : 'Detailed' }}
            &bull; <strong>Enter</strong> = Alter &bull; <strong><span class="mc-key-alt">C</span></strong> = Create Under Group
          </p>
        </div>

        <div class="mc-coa-toolbar">
          <input
            type="text"
            class="mc-input mc-coa-filter-input"
            [ngModel]="searchFilter()"
            (ngModelChange)="onFilterChange($event)"
            placeholder="Filter groups, ledgers, aliases, GSTINs..."
            aria-label="Filter Chart of Accounts"
          />

          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="detailedMode()"
            (click)="toggleDetailedMode()"
          >
            <span class="mc-key-alt">F1</span>: {{ detailedMode() ? 'Detailed (Groups + Ledgers)' : 'Condensed (Groups Only)' }}
          </button>

          <button type="button" class="mc-btn" (click)="expandAll()">Expand All</button>
          <button type="button" class="mc-btn" (click)="collapseToPrimary()">Primary Only</button>
          <button type="button" class="mc-btn" (click)="createChildGroupInline()">
            + Sub-Group
          </button>
          <button type="button" class="mc-btn mc-btn-primary" (click)="createChildLedgerInline()">
            <span class="mc-key-alt">C</span>: + Ledger
          </button>
        </div>
      </div>

      <!-- Tree Grid Card -->
      <section class="mc-card mc-coa-table-card" aria-label="Chart of Accounts Hierarchy">
        <div class="mc-coa-table-scroll">
          <table class="mc-table mc-coa-table">
            <thead>
              <tr>
                <th>Particulars (Account Group / Ledger)</th>
                <th>Type &amp; Nature</th>
                <th>Alias / GSTIN</th>
                <th class="mc-col-right">Opening Debit</th>
                <th class="mc-col-right">Opening Credit</th>
              </tr>
            </thead>
            <tbody>
              @for (row of visibleRows(); track row.rowKey; let idx = $index) {
                <tr
                  class="mc-coa-row"
                  [class.mc-coa-group-row]="row.rowType === 'GROUP'"
                  [class.mc-coa-ledger-row]="row.rowType === 'LEDGER'"
                  [class.mc-row-selected]="idx === selectedRowIndex()"
                  (click)="selectedRowIndex.set(idx)"
                  (dblclick)="drillIntoRow(row)"
                >
                  <td [class]="indentClass(row.depth)">
                    <div class="mc-coa-cell-title">
                      @if (row.rowType === 'GROUP' && row.hasChildren) {
                        <button
                          type="button"
                          class="mc-tree-caret"
                          [attr.aria-label]="row.expanded ? 'Collapse ' + row.name : 'Expand ' + row.name"
                          (click)="toggleGroupExpand($event, row.id)"
                        >
                          {{ row.expanded ? '▾' : '▸' }}
                        </button>
                      } @else {
                        <span class="mc-tree-caret-spacer" aria-hidden="true">
                          {{ row.rowType === 'LEDGER' ? '•' : '' }}
                        </span>
                      }

                      <span [class.mc-coa-group-name]="row.rowType === 'GROUP'">
                        {{ row.name }}
                      </span>

                      @if (row.isSystem) {
                        <span class="mc-badge mc-badge-slate">System</span>
                      }
                    </div>
                  </td>

                  <td>
                    @if (row.rowType === 'GROUP') {
                      <span class="mc-badge mc-badge-slate">Group &bull; {{ row.nature }}</span>
                    } @else {
                      <span class="mc-badge mc-badge-jade">Ledger</span>
                    }
                  </td>

                  <td>
                    <div class="mc-coa-codes">
                      @if (row.aliasCode) {
                        <mc-code-badge [code]="row.aliasCode" />
                      }
                      @if (row.gstin) {
                        <mc-code-badge [code]="row.gstin" />
                      }
                      @if (!row.aliasCode && !row.gstin) {
                        <span class="mc-muted-dash">—</span>
                      }
                    </div>
                  </td>

                  <td class="mc-amount">
                    @if (isPositiveWire(row.openingDebit)) {
                      {{ row.openingDebit | mcIndianCurrency: companyContext.currencyScale() : true }}
                    } @else {
                      <span class="mc-muted-dash">—</span>
                    }
                  </td>

                  <td class="mc-amount">
                    @if (isPositiveWire(row.openingCredit)) {
                      {{ row.openingCredit | mcIndianCurrency: companyContext.currencyScale() : true }}
                    } @else {
                      <span class="mc-muted-dash">—</span>
                    }
                  </td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="5" class="mc-coa-empty">
                    @if (coaStore.loading()) {
                      Loading Chart of Accounts...
                    } @else {
                      No matching Account Groups or Ledgers found. Select an active company (<mc-code-badge code="F3" />) or clear filter.
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        <!-- Sticky ExactDecimal Opening Balance Summary Footer -->
        <footer class="mc-coa-summary-bar" aria-label="Opening Balance Totals">
          <div class="mc-coa-summary-left">
            <span class="mc-coa-stat-pill">
              Groups: <strong>{{ coaStore.groups().length }}</strong>
            </span>
            <span class="mc-coa-stat-pill">
              Ledgers: <strong>{{ coaStore.ledgers().length + coaStore.systemRootLedgers().length }}</strong>
            </span>
            <a routerLink="/app/masters" class="mc-coa-hub-link">Masters Workbench</a>
          </div>

          <div class="mc-coa-totals-grid">
            <div class="mc-coa-total-box">
              <span class="mc-coa-total-label">Total Opening Dr</span>
              <span class="mc-amount mc-coa-total-val" data-testid="coa-total-dr">
                {{ coaStore.totalOpeningDebit() | mcIndianCurrency: companyContext.currencyScale() : true }}
              </span>
            </div>

            <div class="mc-coa-total-box">
              <span class="mc-coa-total-label">Total Opening Cr</span>
              <span class="mc-amount mc-coa-total-val" data-testid="coa-total-cr">
                {{ coaStore.totalOpeningCredit() | mcIndianCurrency: companyContext.currencyScale() : true }}
              </span>
            </div>

            <div
              class="mc-coa-total-box"
              [class.mc-coa-diff-warn]="coaStore.openingDifferenceSide() !== 'BALANCED'"
            >
              <span class="mc-coa-total-label">
                Opening Difference
                @if (coaStore.openingDifferenceSide() !== 'BALANCED') {
                  ({{ coaStore.openingDifferenceSide() }})
                }
              </span>
              <span class="mc-amount mc-coa-total-val" data-testid="coa-opening-diff">
                {{ coaStore.openingDifference() | mcIndianCurrency: companyContext.currencyScale() : true }}
              </span>
            </div>
          </div>
        </footer>
      </section>
    </div>
  `,
  styles: `
    .mc-coa-layout {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-coa-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: var(--mc-space-3);
      flex-wrap: wrap;
    }

    .mc-coa-sub {
      margin: 4px 0 0;
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-coa-toolbar {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      flex-wrap: wrap;
    }

    .mc-coa-filter-input {
      width: 240px;
    }

    .mc-coa-table-card {
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }

    .mc-coa-table-scroll {
      max-height: calc(100vh - 250px);
      overflow-y: auto;
    }

    .mc-coa-group-row {
      background: rgba(246, 250, 249, 0.55);
    }

    .mc-coa-group-name {
      font-weight: 700;
      color: var(--mc-color-slate-900);
    }

    .mc-coa-ledger-row {
      font-weight: 500;
      color: var(--mc-color-slate-800);
    }

    .mc-coa-cell-title {
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .mc-tree-caret {
      width: 20px;
      height: 20px;
      padding: 0;
      border: 1px solid var(--mc-color-slate-200);
      border-radius: var(--mc-radius-xs);
      background: var(--mc-color-surface);
      color: var(--mc-color-teal-700);
      font-size: 12px;
      font-weight: 700;
      cursor: pointer;
    }

    .mc-tree-caret-spacer {
      display: inline-flex;
      justify-content: center;
      width: 20px;
      color: var(--mc-color-slate-500);
    }

    .mc-indent-0 {
      padding-left: 0.625rem !important;
    }

    .mc-indent-1 {
      padding-left: 1.75rem !important;
    }

    .mc-indent-2 {
      padding-left: 2.875rem !important;
    }

    .mc-indent-3 {
      padding-left: 4rem !important;
    }

    .mc-indent-4 {
      padding-left: 5.125rem !important;
    }

    .mc-indent-5 {
      padding-left: 6.25rem !important;
    }

    .mc-indent-6 {
      padding-left: 7.375rem !important;
    }

    .mc-coa-codes {
      display: flex;
      align-items: center;
      gap: 6px;
      flex-wrap: wrap;
    }

    .mc-muted-dash {
      color: var(--mc-color-slate-400);
    }

    .mc-coa-empty {
      padding: var(--mc-space-5) !important;
      text-align: center;
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-coa-summary-bar {
      padding: 0.6rem 1rem;
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-4);
      flex-wrap: wrap;
      border-top: 2px solid var(--mc-color-verdigris-teal);
    }

    .mc-coa-summary-left {
      display: flex;
      align-items: center;
      gap: var(--mc-space-3);
      font-size: var(--mc-font-size-xs);
    }

    .mc-coa-stat-pill {
      padding: 0.15rem 0.5rem;
      background: rgba(255, 255, 255, 0.1);
      border-radius: var(--mc-radius-xs);
    }

    .mc-coa-hub-link {
      color: var(--mc-color-mint-signal);
      font-weight: 600;
      text-decoration: none;
    }

    .mc-coa-totals-grid {
      display: flex;
      align-items: center;
      gap: var(--mc-space-4);
      flex-wrap: wrap;
    }

    .mc-coa-total-box {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      gap: 2px;
    }

    .mc-coa-total-label {
      font-size: 11px;
      font-weight: 600;
      color: var(--mc-color-slate-300);
      text-transform: uppercase;
      letter-spacing: 0.03em;
    }

    .mc-coa-total-val {
      font-size: var(--mc-font-size-md);
      color: var(--mc-color-mint-signal);
    }

    .mc-coa-diff-warn .mc-coa-total-val {
      color: #FCD34D;
    }
  `,
})
export class CoaExplorerComponent implements OnInit {
  readonly coaStore = inject(ChartOfAccountsStore);
  readonly companyContext = inject(CompanyContextStore);

  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly secondaryMasterModal = inject(SecondaryMasterModalService);
  private readonly navStack = inject(NavigationStackStore);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly embeddedOverlay = input<boolean>(false);

  /**
   * `false` = Condensed (Groups Only), `true` = Detailed (Groups + Ledgers).
   * Toggled via `Alt+F1` (`report.toggleDetailed`).
   */
  readonly detailedMode = signal<boolean>(false);
  readonly searchFilter = signal<string>('');
  readonly collapsedGroupIds = signal<ReadonlySet<string>>(new Set());
  readonly selectedRowIndex = signal<number>(0);

  readonly visibleRows = computed<readonly FlattenedCoaRow[]>(() => {
    const roots = this.coaStore.coaTree();
    const showLedgers = this.detailedMode();
    const collapsed = this.collapsedGroupIds();
    const q = this.searchFilter().trim().toLowerCase();
    const rows: FlattenedCoaRow[] = [];

    const matchesQuery = (text: string | null | undefined): boolean =>
      Boolean(text && text.toLowerCase().includes(q));

    const nodeHasMatch = (node: CoaTreeNode): boolean => {
      if (!q) return true;
      if (matchesQuery(node.name) || matchesQuery(node.aliasCode)) return true;
      if (
        showLedgers &&
        node.ledgers.some(
          (l) => matchesQuery(l.name) || matchesQuery(l.aliasCode) || matchesQuery(l.gstin),
        )
      ) {
        return true;
      }
      return node.children.some((c) => nodeHasMatch(c));
    };

    const visit = (nodes: readonly CoaTreeNode[], depth: number): void => {
      for (const node of nodes) {
        if (!nodeHasMatch(node)) {
          continue;
        }
        const hasVisibleChildren =
          node.children.length > 0 || (showLedgers && node.ledgers.length > 0);
        const isExpanded = q.length > 0 ? true : !collapsed.has(node.id);

        rows.push({
          rowKey: `GRP:${node.id}`,
          rowType: 'GROUP',
          id: node.id,
          parentGroupId: node.parentGroupId,
          name: node.name,
          aliasCode: node.aliasCode,
          gstin: null,
          nature: node.nature,
          depth,
          hasChildren: hasVisibleChildren,
          expanded: isExpanded,
          isSystem: node.isSystemGroup,
          openingDebit: node.openingDebit,
          openingCredit: node.openingCredit,
        });

        if (isExpanded) {
          if (node.children.length > 0) {
            visit(node.children, depth + 1);
          }
          if (showLedgers) {
            for (const led of node.ledgers) {
              if (
                q &&
                !matchesQuery(node.name) &&
                !matchesQuery(led.name) &&
                !matchesQuery(led.aliasCode) &&
                !matchesQuery(led.gstin)
              ) {
                continue;
              }
              rows.push(this.toLedgerRow(led, node.nature, depth + 1));
            }
          }
        }
      }
    };

    visit(roots, 0);

    if (showLedgers) {
      for (const rootLed of this.coaStore.systemRootLedgers()) {
        if (q && !matchesQuery(rootLed.name) && !matchesQuery(rootLed.aliasCode)) {
          continue;
        }
        rows.push(this.toLedgerRow(rootLed, 'LIABILITIES', 0));
      }
    }

    return rows;
  });

  ngOnInit(): void {
    void this.coaStore.ensureAccountingMastersLoaded();

    const scope = this.embeddedOverlay() ? 'MODAL' : 'SCREEN';
    const disposers = [
      this.shortcutRegistry.bindHandler('report.toggleDetailed', scope, () => {
        this.toggleDetailedMode();
      }),
      this.shortcutRegistry.bindHandler(
        'inline.createOrCalc',
        scope,
        () => {
          this.createChildLedgerInline();
        },
        () => !this.secondaryMasterModal.isOpen(),
      ),
    ];

    this.destroyRef.onDestroy(() => {
      for (const d of disposers) {
        d();
      }
    });
  }

  indentClass(depth: number): string {
    const clamped = Math.min(6, Math.max(0, depth));
    return `mc-indent-${clamped}`;
  }

  isPositiveWire(wire: string): boolean {
    const cleaned = wire.trim();
    return cleaned.length > 0 && !/^0+(\.0+)?$/.test(cleaned);
  }

  toggleDetailedMode(): void {
    this.detailedMode.set(!this.detailedMode());
    this.clampSelection();
  }

  onFilterChange(val: string): void {
    this.searchFilter.set(val);
    this.selectedRowIndex.set(0);
  }

  expandAll(): void {
    this.collapsedGroupIds.set(new Set());
  }

  collapseToPrimary(): void {
    const ids = new Set<string>();
    const collect = (nodes: readonly CoaTreeNode[]): void => {
      for (const n of nodes) {
        ids.add(n.id);
        collect(n.children);
      }
    };
    collect(this.coaStore.coaTree());
    this.collapsedGroupIds.set(ids);
    this.clampSelection();
  }

  toggleGroupExpand(event: MouseEvent | null, groupId: string): void {
    event?.stopPropagation();
    const next = new Set(this.collapsedGroupIds());
    if (next.has(groupId)) {
      next.delete(groupId);
    } else {
      next.add(groupId);
    }
    this.collapsedGroupIds.set(next);
    this.clampSelection();
  }

  @HostListener('window:keydown', ['$event'])
  onArrowNavigation(event: KeyboardEvent): void {
    if (
      event.ctrlKey ||
      event.altKey ||
      event.metaKey ||
      this.secondaryMasterModal.isOpen() ||
      isEditableTextElement(event.target)
    ) {
      return;
    }

    const rows = this.visibleRows();
    if (rows.length === 0) {
      return;
    }

    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.selectedRowIndex.set(Math.min(rows.length - 1, this.selectedRowIndex() + 1));
      return;
    }

    if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.selectedRowIndex.set(Math.max(0, this.selectedRowIndex() - 1));
      return;
    }

    const activeRow = rows[this.selectedRowIndex()];
    if (!activeRow) {
      return;
    }

    if (event.key === 'ArrowLeft' && activeRow.rowType === 'GROUP' && activeRow.expanded) {
      event.preventDefault();
      this.toggleGroupExpand(null, activeRow.id);
      return;
    }

    if (event.key === 'ArrowRight' && activeRow.rowType === 'GROUP' && !activeRow.expanded) {
      event.preventDefault();
      this.toggleGroupExpand(null, activeRow.id);
      return;
    }

    if (event.key === 'Enter') {
      event.preventDefault();
      this.drillIntoRow(activeRow);
    }
  }

  drillIntoRow(row: FlattenedCoaRow): void {
    const route = row.rowType === 'GROUP' ? '/app/masters/groups' : '/app/masters/ledgers';
    const params = { mode: 'alter', id: row.id };

    if (this.embeddedOverlay()) {
      this.navStack.push({
        title: `${row.rowType === 'GROUP' ? 'Group' : 'Ledger'} Alteration — ${row.name}`,
        route,
        params,
      });
      return;
    }

    void this.router.navigate([route], { queryParams: params });
  }

  createChildLedgerInline(): void {
    const highlighted = this.visibleRows()[this.selectedRowIndex()];
    const targetGroupId =
      highlighted?.rowType === 'GROUP' ? highlighted.id : (highlighted?.parentGroupId ?? null);
    this.detailedMode.set(true);
    void this.secondaryMasterModal.open({
      kind: 'LEDGER',
      defaultGroupId: targetGroupId,
    });
  }

  createChildGroupInline(): void {
    const highlighted = this.visibleRows()[this.selectedRowIndex()];
    const targetParentId =
      highlighted?.rowType === 'GROUP' ? highlighted.id : (highlighted?.parentGroupId ?? null);
    void this.secondaryMasterModal.open({
      kind: 'GROUP',
      defaultParentGroupId: targetParentId,
    });
  }

  private toLedgerRow(led: Ledger, nature: AccountNature, depth: number): FlattenedCoaRow {
    const scale = this.companyContext.currencyScale();
    const zero = '0.' + '0'.repeat(scale);
    return {
      rowKey: `LED:${led.id}`,
      rowType: 'LEDGER',
      id: led.id,
      parentGroupId: led.groupId,
      name: led.name,
      aliasCode: led.aliasCode,
      gstin: led.gstin,
      nature,
      depth,
      hasChildren: false,
      expanded: false,
      isSystem: Boolean(led.isSystemLedger),
      openingDebit: led.openingSide === 'DR' ? led.openingBalance : zero,
      openingCredit: led.openingSide === 'CR' ? led.openingBalance : zero,
    };
  }

  private clampSelection(): void {
    const maxIdx = Math.max(0, this.visibleRows().length - 1);
    if (this.selectedRowIndex() > maxIdx) {
      this.selectedRowIndex.set(maxIdx);
    }
  }
}
