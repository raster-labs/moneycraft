import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';

export interface TypeaheadOption {
  readonly id: string;
  readonly label: string;
  readonly secondaryLabel?: string;
  readonly codeBadge?: string;
}

@Component({
  selector: 'mc-typeahead-select',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-typeahead-host">
      <input
        #comboInput
        type="text"
        role="combobox"
        class="mc-input"
        [id]="inputId()"
        [value]="queryText()"
        [placeholder]="placeholder()"
        [disabled]="disabled()"
        [attr.aria-label]="listTitle()"
        [attr.aria-expanded]="dropdownOpen()"
        [attr.aria-invalid]="spellingErrorOpen()"
        autocomplete="off"
        (focus)="onFocus()"
        (input)="onInput($event)"
        (keydown)="onKeyDown($event)"
        (blur)="onBlur()"
      />

      @if (dropdownOpen()) {
        <div class="mc-typeahead-panel" role="listbox" [attr.aria-label]="listTitle()">
          <div class="mc-typeahead-panel-header">
            <span>{{ listTitle() }}</span>
            @if (allowInlineCreate()) {
              <button
                type="button"
                class="mc-inline-create-btn"
                (mousedown)="triggerInlineCreate($event)"
              >
                <span class="mc-key-alt">C</span>reate
              </button>
            }
          </div>

          <ul class="mc-typeahead-list">
            @for (opt of filteredOptions(); track opt.id; let idx = $index) {
              <li
                role="option"
                class="mc-typeahead-item"
                [class.mc-typeahead-item-active]="idx === highlightedIndex()"
                [attr.aria-selected]="idx === highlightedIndex()"
                (mousedown)="selectOptionByMouse($event, opt)"
              >
                <span class="mc-typeahead-item-label">{{ opt.label }}</span>
                @if (opt.secondaryLabel) {
                  <span class="mc-typeahead-item-sub">{{ opt.secondaryLabel }}</span>
                }
              </li>
            } @empty {
              <li class="mc-typeahead-empty">
                No matching entry in {{ listTitle() }}. Press Alt+C to Create.
              </li>
            }
          </ul>
        </div>
      }

      @if (spellingErrorOpen()) {
        <div
          class="mc-modal-backdrop mc-spelling-error-backdrop"
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="mc-spelling-err-title"
          aria-describedby="mc-spelling-err-desc"
          (keydown)="onSpellingErrorKeyDown($event)"
        >
          <div class="mc-spelling-error-box">
            <div class="mc-spelling-error-header" id="mc-spelling-err-title">Error</div>
            <div class="mc-spelling-error-body" id="mc-spelling-err-desc">
              <div class="mc-spelling-oops">Spelling Error!</div>
              <p>
                <strong>"{{ queryText() }}"</strong> does not match any entry in
                <strong>{{ listTitle() }}</strong>.
              </p>
              <p class="mc-spelling-hint">
                Select a valid item from the list or press <span class="mc-key-alt">C</span> (Alt+C)
                to create a new master.
              </p>
            </div>
            <div class="mc-spelling-error-footer">
              @if (allowInlineCreate()) {
                <button
                  type="button"
                  class="mc-btn"
                  (click)="createFromSpellingError()"
                >
                  <span class="mc-key-alt">C</span>reate Master
                </button>
              }
              <button
                #errorAckBtn
                type="button"
                class="mc-btn mc-btn-primary"
                (click)="dismissSpellingError()"
              >
                OK (Enter / Esc)
              </button>
            </div>
          </div>
        </div>
      }
    </div>
  `,
  styles: `
    .mc-typeahead-host {
      position: relative;
      width: 100%;
    }

    .mc-typeahead-panel {
      position: absolute;
      left: 0;
      top: calc(100% + 2px);
      width: min(360px, 92vw);
      max-height: 280px;
      background: var(--mc-color-surface);
      border: 2px solid var(--mc-color-slate-900);
      border-radius: var(--mc-radius-xs);
      box-shadow: var(--mc-shadow-modal);
      z-index: var(--mc-z-dropdown);
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }

    .mc-typeahead-panel-header {
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      padding: 0.3rem 0.6rem;
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .mc-inline-create-btn {
      background: transparent;
      border: 1px solid var(--mc-color-mint-signal);
      color: var(--mc-color-mint-signal);
      border-radius: var(--mc-radius-xs);
      padding: 0.05rem 0.4rem;
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
      cursor: pointer;
    }

    .mc-typeahead-list {
      list-style: none;
      margin: 0;
      padding: 0;
      overflow-y: auto;
    }

    .mc-typeahead-item {
      padding: 0.3rem 0.6rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-2);
      cursor: pointer;
      font-weight: 500;
      border-bottom: 1px solid var(--mc-color-slate-100);
    }

    .mc-typeahead-item-active {
      background: var(--mc-color-verdigris-teal);
      color: var(--mc-color-surface);
    }

    .mc-typeahead-item-sub {
      font-size: var(--mc-font-size-xs);
      opacity: 0.85;
    }

    .mc-typeahead-empty {
      padding: var(--mc-space-3);
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-spelling-error-backdrop {
      z-index: var(--mc-z-error-modal);
      align-items: center;
      padding-top: 0;
    }

    .mc-spelling-error-box {
      width: min(400px, 90vw);
      background: var(--mc-color-surface);
      border: 2px solid var(--mc-color-danger-600);
      border-radius: var(--mc-radius-sm);
      box-shadow: var(--mc-shadow-modal);
      overflow: hidden;
    }

    .mc-spelling-error-header {
      background: var(--mc-color-danger-600);
      color: var(--mc-color-surface);
      padding: 0.4rem 0.75rem;
      font-weight: 700;
      text-align: center;
    }

    .mc-spelling-error-body {
      padding: var(--mc-space-4);
      text-align: center;
      font-weight: 500;
    }

    .mc-spelling-oops {
      font-size: var(--mc-font-size-lg);
      font-weight: 700;
      color: var(--mc-color-danger-700);
      margin-bottom: var(--mc-space-2);
    }

    .mc-spelling-hint {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      margin-bottom: 0;
    }

    .mc-spelling-error-footer {
      padding: 0.5rem 0.75rem;
      background: var(--mc-color-canvas);
      border-top: var(--mc-border-subtle);
      display: flex;
      justify-content: flex-end;
      gap: var(--mc-space-2);
    }
  `,
})
export class TypeaheadSelectComponent {
  readonly inputId = input<string>('mc-typeahead');
  readonly listTitle = input<string>('List of Ledger Accounts');
  readonly placeholder = input<string>('Type to filter...');
  readonly options = input.required<readonly TypeaheadOption[]>();
  readonly selectedId = input<string | null>(null);
  readonly allowEmptyOnEnter = input<boolean>(false);
  readonly allowInlineCreate = input<boolean>(true);
  readonly disabled = input<boolean>(false);

  readonly optionSelected = output<TypeaheadOption>();
  readonly emptyConfirmed = output<void>();
  readonly createRequested = output<string>();

  private readonly comboInputRef = viewChild<ElementRef<HTMLInputElement>>('comboInput');
  private readonly errorAckBtnRef = viewChild<ElementRef<HTMLButtonElement>>('errorAckBtn');

  readonly queryText = signal<string>('');
  readonly dropdownOpen = signal<boolean>(false);
  readonly highlightedIndex = signal<number>(0);
  readonly spellingErrorOpen = signal<boolean>(false);

  readonly filteredOptions = computed(() => {
    const q = this.queryText().trim().toLowerCase();
    const all = this.options();
    if (q.length === 0) {
      return all;
    }
    return all.filter(
      (opt) =>
        opt.label.toLowerCase().includes(q) ||
        (opt.secondaryLabel?.toLowerCase().includes(q) ?? false) ||
        (opt.codeBadge?.toLowerCase().includes(q) ?? false),
    );
  });

  onFocus(): void {
    if (this.disabled()) {
      return;
    }
    const selId = this.selectedId();
    if (selId && this.queryText().length === 0) {
      const existing = this.options().find((o) => o.id === selId);
      if (existing) {
        this.queryText.set(existing.label);
      }
    }
    this.dropdownOpen.set(true);
    this.highlightedIndex.set(0);
  }

  onInput(event: Event): void {
    const val = (event.target as HTMLInputElement).value;
    this.queryText.set(val);
    this.dropdownOpen.set(true);
    this.highlightedIndex.set(0);
  }

  onBlur(): void {
    setTimeout(() => {
      if (!this.spellingErrorOpen()) {
        this.dropdownOpen.set(false);
      }
    }, 120);
  }

  onKeyDown(event: KeyboardEvent): void {
    if (this.spellingErrorOpen()) {
      this.onSpellingErrorKeyDown(event);
      return;
    }

    const isAltC =
      event.altKey &&
      !event.ctrlKey &&
      !event.metaKey &&
      (event.code === 'KeyC' || event.key.toLowerCase() === 'c');

    if (isAltC && this.allowInlineCreate()) {
      event.preventDefault();
      event.stopPropagation();
      this.dropdownOpen.set(false);
      this.createRequested.emit(this.queryText().trim());
      return;
    }

    const matches = this.filteredOptions();

    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.dropdownOpen.set(true);
      if (matches.length > 0) {
        this.highlightedIndex.set((this.highlightedIndex() + 1) % matches.length);
      }
      return;
    }

    if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.dropdownOpen.set(true);
      if (matches.length > 0) {
        this.highlightedIndex.set(
          (this.highlightedIndex() - 1 + matches.length) % matches.length,
        );
      }
      return;
    }

    if (event.key === 'Escape' && this.dropdownOpen()) {
      event.preventDefault();
      event.stopPropagation();
      this.dropdownOpen.set(false);
      return;
    }

    if (event.key === 'Enter') {
      const trimmed = this.queryText().trim();
      if (trimmed.length === 0 && this.allowEmptyOnEnter()) {
        this.dropdownOpen.set(false);
        this.emptyConfirmed.emit();
        return;
      }

      if (matches.length === 0) {
        // TallyPrime 7.2 empirical rule: raise blocking Spelling Error alert
        event.preventDefault();
        event.stopPropagation();
        this.dropdownOpen.set(false);
        this.spellingErrorOpen.set(true);
        queueMicrotask(() => {
          this.errorAckBtnRef()?.nativeElement.focus();
        });
        return;
      }

      event.preventDefault();
      const chosen = matches[this.highlightedIndex()] ?? matches[0];
      this.commitSelection(chosen);
    }
  }

  onSpellingErrorKeyDown(event: KeyboardEvent): void {
    const isAltC =
      event.altKey &&
      !event.ctrlKey &&
      (event.code === 'KeyC' || event.key.toLowerCase() === 'c');

    if (isAltC && this.allowInlineCreate()) {
      event.preventDefault();
      event.stopPropagation();
      this.createFromSpellingError();
      return;
    }

    if (event.key === 'Enter' || event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.dismissSpellingError();
    }
  }

  dismissSpellingError(): void {
    this.spellingErrorOpen.set(false);
    this.dropdownOpen.set(true);
    queueMicrotask(() => {
      const inputEl = this.comboInputRef()?.nativeElement;
      if (inputEl) {
        inputEl.focus();
        inputEl.select();
      }
    });
  }

  createFromSpellingError(): void {
    const seed = this.queryText().trim();
    this.spellingErrorOpen.set(false);
    this.dropdownOpen.set(false);
    this.createRequested.emit(seed);
  }

  selectOptionByMouse(event: MouseEvent, option: TypeaheadOption): void {
    event.preventDefault();
    this.commitSelection(option);
  }

  triggerInlineCreate(event: MouseEvent): void {
    event.preventDefault();
    this.dropdownOpen.set(false);
    this.createRequested.emit(this.queryText().trim());
  }

  private commitSelection(option: TypeaheadOption): void {
    this.queryText.set(option.label);
    this.dropdownOpen.set(false);
    this.spellingErrorOpen.set(false);
    this.optionSelected.emit(option);
  }
}
