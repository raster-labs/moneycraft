import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  effect,
  inject,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SemanticIconComponent } from '../semantic-icon/semantic-icon.component';
import { ContextualHelpService } from './contextual-help.service';

/**
 * MoneyCraft `v1.1.0` — Local Contextual Help Disclosure & Reference Panel (`Docs 50 §7 & 57`).
 *
 * - Non-modal disclosure / dialog surface (`role="dialog"`, `aria-modal="false"`) that does NOT
 *   trap keyboard navigation when inspecting underlying fields, while restoring focus cleanly via
 *   `"Close help and return to [control name]."` (`Doc 50 §7`).
 * - Served 100% locally for private-LAN autonomy; zero external links or trackers required.
 */
@Component({
  selector: 'mc-help-panel',
  standalone: true,
  imports: [FormsModule, SemanticIconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (helpService.panelOpen() && helpService.activePanelEntry(); as entry) {
      <aside
        class="mc-card mc-help-panel-surface"
        role="dialog"
        aria-modal="false"
        aria-labelledby="mc-help-panel-heading"
        data-testid="contextual-help-panel"
      >
        <div class="mc-help-panel-header">
          <div class="mc-help-panel-meta">
            <span class="mc-badge mc-badge-jade mc-code">{{ entry.help_id }}</span>
            <span class="mc-badge mc-badge-slate">v{{ entry.content_revision }} · {{ entry.locale }}</span>
          </div>
          <button
            #closeHelpBtn
            type="button"
            class="mc-btn mc-btn-secondary mc-help-close-btn"
            data-testid="contextual-help-close-btn"
            [attr.aria-label]="helpService.closeButtonLabel()"
            (click)="helpService.closeHelpPanel(true)"
          >
            <mc-icon name="close" [size]="16" />
            <span>{{ helpService.closeButtonLabel() }}</span>
          </button>
        </div>

        <div class="mc-help-panel-body">
          <div class="mc-help-audience-line">
            <span><strong>Audience:</strong> {{ entry.audience }}</span>
            <span><strong>Context:</strong> <code class="mc-code">{{ entry.context_key }}</code></span>
          </div>

          <h2 id="mc-help-panel-heading" class="mc-help-title">{{ entry.title }}</h2>

          <p class="mc-help-summary">{{ entry.short_text }}</p>

          <div class="mc-help-instructions-box">
            <strong>Guidance</strong>
            <p class="mc-help-instructions">{{ entry.instructions }}</p>
          </div>

          <div class="mc-help-directory-toggle-row">
            <button
              type="button"
              class="mc-btn mc-btn-secondary"
              data-testid="contextual-help-browse-toggle"
              (click)="helpService.toggleTopicDirectory()"
            >
              <mc-icon name="search" [size]="16" />
              <span>
                {{
                  helpService.showTopicDirectory()
                    ? 'Hide all help topics'
                    : 'Browse all 26 local help topics (F1)'
                }}
              </span>
            </button>
          </div>

          @if (helpService.showTopicDirectory()) {
            <div class="mc-help-directory" role="region" aria-label="Local Help Catalogue">
              <label class="mc-field-label" for="mc-help-search-input">
                Filter local help topics
              </label>
              <input
                id="mc-help-search-input"
                type="search"
                class="mc-input"
                data-testid="contextual-help-search-input"
                placeholder="Search e.g. appearance, amounts, post, shortcuts…"
                [ngModel]="helpService.browseSearchQuery()"
                (ngModelChange)="helpService.setBrowseSearchQuery($event)"
              />

              <ul class="mc-help-topic-list" role="list">
                @for (topic of helpService.filteredDirectoryEntries(); track topic.help_id) {
                  <li>
                    <button
                      type="button"
                      class="mc-help-topic-item"
                      [class.mc-help-topic-active]="topic.help_id === entry.help_id"
                      [attr.data-testid]="'help-topic-btn-' + topic.help_id"
                      (click)="helpService.selectDirectoryTopic(topic.help_id)"
                    >
                      <span class="mc-code mc-help-topic-code">{{ topic.help_id }}</span>
                      <span>{{ topic.title }}</span>
                    </button>
                  </li>
                }
              </ul>
            </div>
          }
        </div>

        <div class="mc-help-panel-footer">
          <span>Local MoneyCraft help ({{ entry.locale }}) · Press <kbd>Esc</kbd> to dismiss</span>
        </div>
      </aside>
    }
  `,
  styles: `
    .mc-help-panel-surface {
      position: fixed;
      top: 3rem;
      right: 1rem;
      width: min(440px, calc(100vw - 2rem));
      max-height: calc(100vh - 4.5rem);
      display: flex;
      flex-direction: column;
      z-index: var(--mc-z-help-panel, 920);
      border: 2px solid var(--mc-color-slate-900);
      box-shadow: var(--mc-shadow-modal);
      overflow: hidden;
    }

    .mc-help-panel-header {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--mc-space-2);
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-chrome-bg, #142f42);
      color: var(--mc-color-chrome-fg, #ffffff);
      border-bottom: var(--mc-border-subtle);
    }

    .mc-help-panel-meta {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      flex-wrap: wrap;
    }

    .mc-help-close-btn {
      font-size: var(--mc-font-size-xs);
    }

    .mc-help-panel-body {
      padding: var(--mc-space-4);
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-help-audience-line {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: var(--mc-space-2);
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
    }

    .mc-help-title {
      font-size: var(--mc-font-size-lg);
      margin: 0;
    }

    .mc-help-summary {
      margin: 0;
      font-size: var(--mc-font-size-base);
      font-weight: 600;
      line-height: 1.45;
    }

    .mc-help-instructions-box {
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-sm);
      font-size: var(--mc-font-size-sm);
      line-height: 1.5;
    }

    .mc-help-instructions {
      margin: 0.35rem 0 0;
    }

    .mc-help-directory-toggle-row {
      display: flex;
      align-items: center;
    }

    .mc-help-directory {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
      border-top: var(--mc-border-subtle);
      padding-top: var(--mc-space-3);
    }

    .mc-help-topic-list {
      list-style: none;
      margin: 0;
      padding: 0;
      max-height: 200px;
      overflow-y: auto;
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
    }

    .mc-help-topic-item {
      width: 100%;
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      padding: 0.35rem 0.5rem;
      background: var(--mc-color-surface);
      color: var(--mc-color-slate-900);
      border: 0;
      border-bottom: var(--mc-border-subtle);
      text-align: left;
      font-size: var(--mc-font-size-xs);
      cursor: pointer;
    }

    .mc-help-topic-item:hover {
      background: var(--mc-color-row-hover);
    }

    .mc-help-topic-active {
      background: var(--mc-color-row-selected);
      font-weight: 700;
    }

    .mc-help-topic-code {
      font-size: 0.7rem;
      flex-shrink: 0;
    }

    .mc-help-panel-footer {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-canvas);
      border-top: var(--mc-border-subtle);
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
    }
  `,
})
export class HelpPanelComponent {
  readonly helpService = inject(ContextualHelpService);
  private readonly closeHelpBtnRef = viewChild<ElementRef<HTMLButtonElement>>('closeHelpBtn');

  constructor() {
    effect(() => {
      if (this.helpService.panelOpen()) {
        queueMicrotask(() => {
          this.closeHelpBtnRef()?.nativeElement.focus({ preventScroll: true });
        });
      }
    });
  }
}
