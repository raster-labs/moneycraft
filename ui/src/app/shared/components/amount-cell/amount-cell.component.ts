import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  forwardRef,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';
import { ExactDecimal, RUPEE_SYMBOL } from '../../../core/money/exact-decimal';

@Component({
  selector: 'mc-amount-cell',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => AmountCellComponent),
      multi: true,
    },
  ],
  template: `
    <div class="mc-amount-wrapper">
      <div class="mc-amount-input-row">
        @if (showCurrencyPrefix()) {
          <span class="mc-currency-glyph" aria-hidden="true">{{ currencySymbol() }}</span>
        }
        <input
          #amountInput
          type="text"
          inputmode="decimal"
          class="mc-input mc-amount mc-amount-input"
          [id]="inputId()"
          [value]="displayText()"
          [disabled]="isDisabled()"
          [readOnly]="readonly()"
          [placeholder]="placeholder()"
          [attr.aria-label]="ariaLabel()"
          [attr.aria-invalid]="validationError() !== null"
          (focus)="onFocus()"
          (input)="onRawInput($event)"
          (keydown)="onKeyDown($event)"
          (blur)="onBlur()"
        />
        @if (!readonly() && !isDisabled()) {
          <button
            type="button"
            class="mc-calc-trigger"
            tabindex="-1"
            title="Inline Calculator (Alt+C or '=')"
            (click)="openCalculator()"
          >
            =
          </button>
        }
      </div>

      @if (validationError(); as err) {
        <div class="mc-field-error" role="alert">{{ err }}</div>
      }

      @if (calculatorOpen()) {
        <div class="mc-calc-popover" role="dialog" aria-label="Inline Amount Calculator">
          <div class="mc-calc-header">
            <span>Exact Calculator (<span class="mc-key-alt">C</span> / =)</span>
            <span class="mc-calc-hint">Enter = Apply &bull; Esc = Cancel</span>
          </div>
          <input
            #calcInput
            type="text"
            class="mc-input mc-calc-expr-input"
            [ngModel]="calcExpression()"
            (ngModelChange)="onCalcExpressionChange($event)"
            (keydown)="onCalcKeyDown($event)"
            placeholder="e.g. (1250 * 18%) + 1250"
            aria-label="Calculator Expression"
          />
          <div class="mc-calc-preview-bar">
            <span class="mc-calc-preview-label">Preview:</span>
            @if (calcError(); as cErr) {
              <span class="mc-calc-error">{{ cErr }}</span>
            } @else {
              <span class="mc-amount mc-calc-preview-val">
                {{ currencySymbol() }} {{ calcPreviewFormatted() }}
              </span>
            }
          </div>
        </div>
      }
    </div>
  `,
  styles: `
    .mc-amount-wrapper {
      position: relative;
      width: 100%;
    }

    .mc-amount-input-row {
      display: flex;
      align-items: center;
      position: relative;
    }

    .mc-currency-glyph {
      position: absolute;
      left: 0.5rem;
      color: var(--mc-color-slate-600);
      font-weight: 600;
      pointer-events: none;
    }

    .mc-amount-input {
      padding-left: 1.5rem;
      padding-right: 1.75rem;
    }

    .mc-calc-trigger {
      position: absolute;
      right: 0.25rem;
      height: 22px;
      min-width: 22px;
      border: 1px solid var(--mc-color-slate-200);
      border-radius: var(--mc-radius-xs);
      background: var(--mc-color-canvas);
      color: var(--mc-color-teal-700);
      font-weight: 700;
      font-size: var(--mc-font-size-xs);
      cursor: pointer;
    }

    .mc-calc-popover {
      position: absolute;
      right: 0;
      top: calc(100% + 4px);
      width: min(320px, 90vw);
      background: var(--mc-color-surface);
      border: 2px solid var(--mc-color-slate-900);
      border-radius: var(--mc-radius-sm);
      box-shadow: var(--mc-shadow-modal);
      padding: var(--mc-space-2);
      z-index: var(--mc-z-dropdown);
    }

    .mc-calc-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
      color: var(--mc-color-slate-800);
      margin-bottom: var(--mc-space-1);
    }

    .mc-calc-hint {
      color: var(--mc-color-slate-500);
      font-weight: 500;
    }

    .mc-calc-expr-input {
      font-family: var(--mc-font-mono);
      font-size: var(--mc-font-size-sm);
      margin-bottom: var(--mc-space-1);
    }

    .mc-calc-preview-bar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: var(--mc-font-size-xs);
      padding-top: 2px;
    }

    .mc-calc-preview-label {
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-calc-preview-val {
      color: var(--mc-color-teal-700);
    }

    .mc-calc-error {
      color: var(--mc-color-danger-600);
      font-weight: 600;
    }
  `,
})
export class AmountCellComponent implements ControlValueAccessor {
  readonly inputId = input<string>('mc-amount-field');
  readonly scale = input<number>(2);
  readonly currencySymbol = input<string>(RUPEE_SYMBOL);
  readonly showCurrencyPrefix = input<boolean>(true);
  readonly readonly = input<boolean>(false);
  readonly placeholder = input<string>('0.00');
  readonly ariaLabel = input<string>('Amount');
  readonly value = input<string | null>(null);

  readonly valueCommitted = output<string>();

  private readonly amountInputRef = viewChild<ElementRef<HTMLInputElement>>('amountInput');
  private readonly calcInputRef = viewChild<ElementRef<HTMLInputElement>>('calcInput');

  private readonly wireValueSignal = signal<string>('0.00');
  private readonly rawEditingText = signal<string | null>(null);
  readonly isDisabled = signal<boolean>(false);
  readonly validationError = signal<string | null>(null);

  readonly calculatorOpen = signal<boolean>(false);
  readonly calcExpression = signal<string>('');
  readonly calcError = signal<string | null>(null);
  readonly calcPreviewFormatted = signal<string>('0.00');

  private onChangeCallback: (val: string) => void = () => undefined;
  private onTouchedCallback: () => void = () => undefined;

  readonly displayText = computed(() => {
    const rawEdit = this.rawEditingText();
    if (rawEdit !== null) {
      return rawEdit;
    }
    const source = this.value() ?? this.wireValueSignal();
    const parsed = ExactDecimal.tryParse(source, this.scale(), 'HALF_UP');
    return parsed
      ? parsed.formatIndian({ scale: this.scale(), withCurrencySymbol: false })
      : source;
  });

  writeValue(val: string | null): void {
    const fallback = ExactDecimal.zero(this.scale()).toWireString(this.scale());
    if (!val || val.trim().length === 0) {
      this.wireValueSignal.set(fallback);
      this.rawEditingText.set(null);
      return;
    }
    const parsed = ExactDecimal.tryParse(val, this.scale(), 'HALF_UP');
    this.wireValueSignal.set(parsed ? parsed.toWireString(this.scale()) : val);
    this.rawEditingText.set(null);
  }

  registerOnChange(fn: (val: string) => void): void {
    this.onChangeCallback = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouchedCallback = fn;
  }

  setDisabledState(disabled: boolean): void {
    this.isDisabled.set(disabled);
  }

  onFocus(): void {
    if (this.readonly() || this.isDisabled()) {
      return;
    }
    const source = this.value() ?? this.wireValueSignal();
    this.rawEditingText.set(source);
    queueMicrotask(() => {
      this.amountInputRef()?.nativeElement.select();
    });
  }

  onRawInput(event: Event): void {
    const text = (event.target as HTMLInputElement).value;
    if (text.startsWith('=')) {
      this.openCalculator(text.slice(1));
      return;
    }
    this.rawEditingText.set(text);
    this.validationError.set(null);
  }

  onKeyDown(event: KeyboardEvent): void {
    if (this.readonly() || this.isDisabled()) {
      return;
    }

    const isAltC =
      event.altKey &&
      !event.ctrlKey &&
      !event.metaKey &&
      (event.code === 'KeyC' || event.key.toLowerCase() === 'c');

    if (isAltC || (event.key === '=' && (this.rawEditingText() ?? '').length === 0)) {
      event.preventDefault();
      event.stopPropagation();
      this.openCalculator(this.rawEditingText() ?? this.wireValueSignal());
      return;
    }

    if (event.key === 'Enter') {
      this.commitRawText();
    }
  }

  onBlur(): void {
    this.onTouchedCallback();
    if (!this.calculatorOpen()) {
      this.commitRawText();
    }
  }

  openCalculator(seedExpression = ''): void {
    this.calculatorOpen.set(true);
    const initial = seedExpression === '0.00' ? '' : seedExpression;
    this.calcExpression.set(initial);
    this.onCalcExpressionChange(initial);
    queueMicrotask(() => {
      this.calcInputRef()?.nativeElement.focus();
    });
  }

  onCalcExpressionChange(expr: string): void {
    this.calcExpression.set(expr);
    if (expr.trim().length === 0) {
      this.calcError.set(null);
      this.calcPreviewFormatted.set(
        ExactDecimal.zero(this.scale()).formatIndian({ scale: this.scale() }),
      );
      return;
    }
    try {
      const evaluated = ExactDecimal.evaluateExpression(expr, this.scale());
      this.calcError.set(null);
      this.calcPreviewFormatted.set(evaluated.formatIndian({ scale: this.scale() }));
    } catch {
      this.calcError.set('Incomplete expression');
    }
  }

  onCalcKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.calculatorOpen.set(false);
      this.amountInputRef()?.nativeElement.focus();
      return;
    }

    if (event.key === 'Enter') {
      event.preventDefault();
      event.stopPropagation();
      try {
        const result = ExactDecimal.evaluateExpression(this.calcExpression(), this.scale());
        const wire = result.toWireString(this.scale());
        this.wireValueSignal.set(wire);
        this.rawEditingText.set(null);
        this.validationError.set(null);
        this.calculatorOpen.set(false);
        this.onChangeCallback(wire);
        this.valueCommitted.emit(wire);
        this.amountInputRef()?.nativeElement.focus();
      } catch (err: unknown) {
        this.calcError.set(err instanceof Error ? err.message : 'Invalid calculator expression');
      }
    }
  }

  private commitRawText(): void {
    const raw = this.rawEditingText();
    if (raw === null) {
      return;
    }
    const trimmed = raw.trim();
    if (trimmed.length === 0) {
      const zeroWire = ExactDecimal.zero(this.scale()).toWireString(this.scale());
      this.wireValueSignal.set(zeroWire);
      this.rawEditingText.set(null);
      this.validationError.set(null);
      this.onChangeCallback(zeroWire);
      this.valueCommitted.emit(zeroWire);
      return;
    }

    try {
      const parsed = ExactDecimal.parse(trimmed, this.scale(), 'UNNECESSARY');
      const wire = parsed.toWireString(this.scale());
      this.wireValueSignal.set(wire);
      this.rawEditingText.set(null);
      this.validationError.set(null);
      this.onChangeCallback(wire);
      this.valueCommitted.emit(wire);
    } catch (err: unknown) {
      this.validationError.set(
        err instanceof Error ? err.message : 'Invalid exact monetary amount',
      );
    }
  }
}
