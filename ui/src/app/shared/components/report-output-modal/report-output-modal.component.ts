import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  effect,
  inject,
  input,
  output,
  viewChild,
} from '@angular/core';
import {
  ActiveModalContextHandle,
  ModalActionDefinition,
  ShortcutCommandRegistry,
  asciiPair,
} from '../../../core/keyboard/shortcut-command.registry';
import { SemanticIconComponent } from '../semantic-icon/semantic-icon.component';

/**
 * MoneyCraft 1.0.0 — `report.output` Modal (`Doc 45 §2` & `Doc 46 §2`):
 *   - Non-editable report kind & period bounds
 *   - `Print` (`report.print`, `P/p`, `mc.icon.print`, `action.secondary`)
 *   - `Export PDF` (`report.export`, `E/e`, `mc.icon.export`, `action.secondary`)
 *   - `Close` (`editor.close`, `C/c`, `mc.icon.close`, `action.secondary` — initial focus)
 */
@Component({
  selector: 'mc-report-output-modal',
  standalone: true,
  imports: [SemanticIconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (open()) {
      <div
        class="mc-ro-backdrop"
        role="dialog"
        aria-modal="true"
        aria-labelledby="mc-ro-title"
        (keydown.escape)="onClose($event)"
      >
        <div class="mc-card mc-ro-card">
          <div class="mc-ro-header">
            <strong id="mc-ro-title">Report Output — {{ reportTitle() }}</strong>
            <span class="mc-badge mc-badge-slate">PDF</span>
          </div>

          <div class="mc-ro-body">
            <div class="mc-ro-summary">
              <div><strong>Report:</strong> {{ reportTitle() }}</div>
              <div><strong>Bounds:</strong> {{ boundsLabel() }}</div>
              <div class="mc-ro-note">
                Requests server-rendered PDF with active report filters and watermark status.
              </div>
            </div>
          </div>

          <div class="mc-ro-footer">
            <button
              type="button"
              class="mc-btn mc-btn-secondary"
              [disabled]="!exportPermitted()"
              data-testid="report-output-print-btn"
              (click)="onPrint()"
            >
              <mc-icon name="print" [size]="16" />
              <span>Print</span>
              @if (actionHint('report.print', 'P/p'); as hint) {
                <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
              }
            </button>

            <button
              type="button"
              class="mc-btn mc-btn-secondary"
              [disabled]="!exportPermitted()"
              data-testid="report-output-export-btn"
              (click)="onExportPdf()"
            >
              <mc-icon name="export" [size]="16" />
              <span>Export PDF</span>
              @if (actionHint('report.export', 'E/e'); as hint) {
                <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
              }
            </button>

            <button
              #closeBtn
              type="button"
              class="mc-btn mc-btn-secondary"
              data-testid="report-output-close-btn"
              (click)="onClose()"
            >
              <mc-icon name="close" [size]="16" />
              <span>Close</span>
              @if (actionHint('editor.close', 'C/c'); as hint) {
                <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
              }
            </button>
          </div>
        </div>
      </div>
    }
  `,
  styles: `
    .mc-ro-backdrop {
      position: fixed;
      inset: 0;
      background: rgba(15, 23, 42, 0.45);
      z-index: var(--mc-z-modal);
      display: flex;
      align-items: center;
      justify-content: center;
      padding: var(--mc-space-4);
    }

    .mc-ro-card {
      width: min(460px, 94vw);
      overflow: hidden;
    }

    .mc-ro-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: var(--mc-space-3) var(--mc-space-4);
      background: var(--mc-color-slate-900);
      color: #ffffff;
    }

    .mc-ro-body {
      padding: var(--mc-space-4);
    }

    .mc-ro-summary {
      display: flex;
      flex-direction: column;
      gap: 6px;
      font-size: var(--mc-font-size-sm);
    }

    .mc-ro-note {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      margin-top: 4px;
    }

    .mc-ro-footer {
      display: flex;
      justify-content: flex-end;
      gap: var(--mc-space-2);
      padding: var(--mc-space-3) var(--mc-space-4);
      border-top: var(--mc-border-subtle);
      background: var(--mc-color-canvas);
    }
  `,
})
export class ReportOutputModalComponent {
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly destroyRef = inject(DestroyRef);

  readonly open = input<boolean>(false);
  readonly reportTitle = input<string>('Financial Report');
  readonly boundsLabel = input<string>('');
  readonly exportPermitted = input<boolean>(true);

  readonly printRequested = output<void>();
  readonly exportRequested = output<void>();
  readonly closed = output<void>();

  private readonly closeBtn = viewChild<ElementRef<HTMLButtonElement>>('closeBtn');
  private modalHandle: ActiveModalContextHandle | null = null;
  private invokingElement: HTMLElement | null = null;

  constructor() {
    effect(() => {
      if (this.open()) {
        this.activateContext();
      } else {
        this.deactivateContext();
      }
    });

    this.destroyRef.onDestroy(() => {
      this.deactivateContext();
    });
  }

  actionHint(commandId: string, pairSpec: 'P/p' | 'E/e' | 'C/c'): string | null {
    return this.shortcutRegistry.effectiveActionHint(
      'report.output',
      commandId,
      asciiPair(pairSpec),
    );
  }

  onPrint(): void {
    if (!this.exportPermitted()) {
      return;
    }
    this.printRequested.emit();
    this.onClose();
  }

  onExportPdf(): void {
    if (!this.exportPermitted()) {
      return;
    }
    this.exportRequested.emit();
    this.onClose();
  }

  onClose(event?: Event): void {
    event?.preventDefault();
    event?.stopPropagation();
    this.deactivateContext();
    this.closed.emit();
    const target = this.invokingElement;
    this.invokingElement = null;
    if (target && typeof target.focus === 'function') {
      queueMicrotask(() => target.focus());
    }
  }

  private activateContext(): void {
    if (typeof document !== 'undefined' && document.activeElement instanceof HTMLElement) {
      this.invokingElement = document.activeElement;
    }
    this.deactivateContext();
    const actions: readonly ModalActionDefinition[] = [
      {
        contextId: 'report.output',
        locale: 'en-GB',
        commandId: 'report.print',
        label: 'Print',
        letterPair: asciiPair('P/p'),
        enabledWhen: () => this.exportPermitted(),
        permissionRef: 'export',
        effect: 'Request server-rendered PDF and open browser PDF print viewer.',
        normalActivation: () => this.onPrint(),
        iconSemanticId: 'mc.icon.print',
        stateTokenRole: 'action.secondary',
      },
      {
        contextId: 'report.output',
        locale: 'en-GB',
        commandId: 'report.export',
        label: 'Export PDF',
        letterPair: asciiPair('E/e'),
        enabledWhen: () => this.exportPermitted(),
        permissionRef: 'export',
        effect: 'Request server-rendered PDF and download PDF file.',
        normalActivation: () => this.onExportPdf(),
        iconSemanticId: 'mc.icon.export',
        stateTokenRole: 'action.secondary',
      },
      {
        contextId: 'report.output',
        locale: 'en-GB',
        commandId: 'editor.close',
        label: 'Close',
        letterPair: asciiPair('C/c'),
        permissionRef: 'authenticated',
        effect: 'Close output modal and restore output control.',
        normalActivation: () => this.onClose(),
        iconSemanticId: 'mc.icon.close',
        stateTokenRole: 'action.secondary',
      },
    ];
    this.modalHandle = this.shortcutRegistry.openModalContext(actions, 'en-GB');
    queueMicrotask(() => {
      this.closeBtn()?.nativeElement.focus();
    });
  }

  private deactivateContext(): void {
    if (this.modalHandle) {
      this.modalHandle.close();
      this.modalHandle = null;
    }
  }
}
