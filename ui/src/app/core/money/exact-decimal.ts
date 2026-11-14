/**
 * MoneyCraft 1.0.0 — Exact Client-Side Fixed-Point Decimal & Expression Engine (`MCJ1-DATA-01`)
 *
 * Strictly pure `bigint` scaled integer arithmetic (scales 0..12).
 * Zero IEEE-754 floating-point math, zero `eval()`, zero `Function()`.
 */

export type ExactRoundingMode = 'HALF_UP' | 'DOWN' | 'UP' | 'UNNECESSARY';

export interface IndianFormatOptions {
  readonly scale?: number;
  readonly withCurrencySymbol?: boolean;
  readonly currencySymbol?: string;
  readonly useTrueMinus?: boolean;
  readonly suffixSide?: 'DR' | 'CR';
}

export class ExactDecimalError extends Error {
  constructor(
    public readonly code:
      | 'MC-ACC-DECIMAL-INVALID'
      | 'MC-ACC-SCALE-OVERFLOW'
      | 'MC-ACC-DIV-BY-ZERO'
      | 'MC-ACC-EXPR-SYNTAX',
    message: string,
  ) {
    super(message);
    this.name = 'ExactDecimalError';
  }
}

export const RUPEE_SYMBOL = '\u20B9';
export const TRUE_MINUS = '\u2212';
export const MAX_DECIMAL_SCALE = 12;
const INTERNAL_CALC_SCALE = 12;

const POW10: readonly bigint[] = Array.from({ length: 30 }, (_, idx) => 10n ** BigInt(idx));

function pow10(exp: number): bigint {
  if (!Number.isInteger(exp) || exp < 0) {
    throw new ExactDecimalError('MC-ACC-SCALE-OVERFLOW', `Invalid scale exponent: ${exp}`);
  }
  if (exp < POW10.length) {
    return POW10[exp];
  }
  return 10n ** BigInt(exp);
}

function assertValidScale(scale: number): void {
  if (!Number.isInteger(scale) || scale < 0 || scale > MAX_DECIMAL_SCALE) {
    throw new ExactDecimalError(
      'MC-ACC-SCALE-OVERFLOW',
      `Scale must be an integer between 0 and ${MAX_DECIMAL_SCALE}; received ${scale}`,
    );
  }
}

function rescaleRawBigInt(
  value: bigint,
  fromScale: number,
  toScale: number,
  roundingMode: ExactRoundingMode,
): bigint {
  if (fromScale === toScale) {
    return value;
  }
  if (fromScale < toScale) {
    return value * pow10(toScale - fromScale);
  }

  const diff = fromScale - toScale;
  const factor = pow10(diff);
  const isNeg = value < 0n;
  const absVal = isNeg ? -value : value;
  let quotient = absVal / factor;
  const remainder = absVal % factor;

  if (remainder !== 0n) {
    switch (roundingMode) {
      case 'UNNECESSARY':
        throw new ExactDecimalError(
          'MC-ACC-SCALE-OVERFLOW',
          `Fractional digits exceed permitted scale ${toScale}`,
        );
      case 'HALF_UP':
        if (remainder * 2n >= factor) {
          quotient += 1n;
        }
        break;
      case 'UP':
        quotient += 1n;
        break;
      case 'DOWN':
        break;
    }
  }

  return isNeg ? -quotient : quotient;
}

export class ExactDecimal {
  readonly unscaled: bigint;
  readonly scale: number;

  private constructor(unscaled: bigint, scale: number) {
    assertValidScale(scale);
    this.unscaled = unscaled;
    this.scale = scale;
  }

  static zero(scale = 2): ExactDecimal {
    return new ExactDecimal(0n, scale);
  }

  static fromUnscaled(unscaled: bigint, scale = 2): ExactDecimal {
    return new ExactDecimal(unscaled, scale);
  }

  static fromInt(intVal: number | bigint, scale = 2): ExactDecimal {
    assertValidScale(scale);
    const base = typeof intVal === 'bigint' ? intVal : BigInt(Math.trunc(intVal));
    return new ExactDecimal(base * pow10(scale), scale);
  }

  /**
   * Parses a decimal string (wire JSON or user-formatted Indian/international string)
   * into an ExactDecimal without touching IEEE-754 floats.
   */
  static parse(
    raw: string,
    targetScale?: number,
    roundingMode: ExactRoundingMode = 'UNNECESSARY',
  ): ExactDecimal {
    if (typeof raw !== 'string') {
      throw new ExactDecimalError('MC-ACC-DECIMAL-INVALID', 'Decimal input must be a string');
    }

    let cleaned = raw.trim();
    if (cleaned.startsWith(RUPEE_SYMBOL)) {
      cleaned = cleaned.slice(RUPEE_SYMBOL.length).trim();
    }

    let isNegative = false;
    if (cleaned.startsWith('-') || cleaned.startsWith(TRUE_MINUS)) {
      isNegative = true;
      cleaned = cleaned.slice(1).trim();
    } else if (cleaned.startsWith('+')) {
      cleaned = cleaned.slice(1).trim();
    }

    if (cleaned.startsWith(RUPEE_SYMBOL)) {
      cleaned = cleaned.slice(RUPEE_SYMBOL.length).trim();
    }

    cleaned = cleaned.replace(/,/g, '').replace(/_/g, '');

    if (cleaned.length === 0 || !/^(?:\d+(?:\.\d*)?|\.\d+)$/.test(cleaned)) {
      throw new ExactDecimalError('MC-ACC-DECIMAL-INVALID', `Malformed decimal string: "${raw}"`);
    }

    const parts = cleaned.split('.');
    const intPart = parts[0] && parts[0].length > 0 ? parts[0] : '0';
    const fracPart = parts[1] ?? '';

    if (fracPart.length > MAX_DECIMAL_SCALE && targetScale === undefined) {
      throw new ExactDecimalError(
        'MC-ACC-SCALE-OVERFLOW',
        `Fractional precision (${fracPart.length}) exceeds maximum scale ${MAX_DECIMAL_SCALE}`,
      );
    }

    const rawDigits = `${intPart}${fracPart}`;
    const rawBig = BigInt(rawDigits);
    const signedRaw = isNegative && rawBig !== 0n ? -rawBig : rawBig;

    if (targetScale !== undefined) {
      assertValidScale(targetScale);
      const rescaled = rescaleRawBigInt(signedRaw, fracPart.length, targetScale, roundingMode);
      return new ExactDecimal(rescaled, targetScale);
    }

    return new ExactDecimal(signedRaw, fracPart.length);
  }

  static tryParse(
    raw: string,
    targetScale?: number,
    roundingMode: ExactRoundingMode = 'HALF_UP',
  ): ExactDecimal | null {
    try {
      return ExactDecimal.parse(raw, targetScale, roundingMode);
    } catch {
      return null;
    }
  }

  rescale(targetScale: number, roundingMode: ExactRoundingMode = 'HALF_UP'): ExactDecimal {
    assertValidScale(targetScale);
    if (this.scale === targetScale) {
      return this;
    }
    return new ExactDecimal(
      rescaleRawBigInt(this.unscaled, this.scale, targetScale, roundingMode),
      targetScale,
    );
  }

  add(other: ExactDecimal, targetScale?: number): ExactDecimal {
    const commonScale = this.scale >= other.scale ? this.scale : other.scale;
    const lhs = rescaleRawBigInt(this.unscaled, this.scale, commonScale, 'UNNECESSARY');
    const rhs = rescaleRawBigInt(other.unscaled, other.scale, commonScale, 'UNNECESSARY');
    const sum = new ExactDecimal(lhs + rhs, commonScale);
    return targetScale !== undefined ? sum.rescale(targetScale, 'HALF_UP') : sum;
  }

  sub(other: ExactDecimal, targetScale?: number): ExactDecimal {
    const commonScale = this.scale >= other.scale ? this.scale : other.scale;
    const lhs = rescaleRawBigInt(this.unscaled, this.scale, commonScale, 'UNNECESSARY');
    const rhs = rescaleRawBigInt(other.unscaled, other.scale, commonScale, 'UNNECESSARY');
    const diff = new ExactDecimal(lhs - rhs, commonScale);
    return targetScale !== undefined ? diff.rescale(targetScale, 'HALF_UP') : diff;
  }

  mulScaled(
    other: ExactDecimal,
    resultScale = 2,
    roundingMode: ExactRoundingMode = 'HALF_UP',
  ): ExactDecimal {
    assertValidScale(resultScale);
    const product = this.unscaled * other.unscaled;
    const combinedScale = this.scale + other.scale;
    const rescaled = rescaleRawBigInt(product, combinedScale, resultScale, roundingMode);
    return new ExactDecimal(rescaled, resultScale);
  }

  divHalfUp(divisor: ExactDecimal, resultScale = 2): ExactDecimal {
    assertValidScale(resultScale);
    if (divisor.unscaled === 0n) {
      throw new ExactDecimalError('MC-ACC-DIV-BY-ZERO', 'Division by zero in ExactDecimal');
    }

    const sign = (this.unscaled < 0n) !== (divisor.unscaled < 0n) ? -1n : 1n;
    const absNum = this.unscaled < 0n ? -this.unscaled : this.unscaled;
    const absDen = divisor.unscaled < 0n ? -divisor.unscaled : divisor.unscaled;

    const scaleShift = resultScale + divisor.scale - this.scale;
    const scaledNum = scaleShift >= 0 ? absNum * pow10(scaleShift) : absNum;
    const scaledDen = scaleShift < 0 ? absDen * pow10(-scaleShift) : absDen;

    let quotient = scaledNum / scaledDen;
    const remainder = scaledNum % scaledDen;
    if (remainder * 2n >= scaledDen) {
      quotient += 1n;
    }

    return new ExactDecimal(quotient === 0n ? 0n : sign * quotient, resultScale);
  }

  /**
   * High-precision unit-cost division at scale 10 matching TallyPrime 7.2 `Avg. Cost`
   * valuation rules (e.g., 43600 / 140 => 311.4285714286; 2390 / 23 => 103.9130434783).
   */
  divExactScale10(divisor: ExactDecimal): ExactDecimal {
    return this.divHalfUp(divisor, 10);
  }

  /**
   * Computes closing stock valuation under TallyPrime 7.2 `Avg. Cost`:
   * retains scale-10 intermediate unit cost and rounds HALF_UP only on final closing value.
   */
  static computeAvgCostClosingValue(
    netInwardValue: ExactDecimal,
    netInwardQty: ExactDecimal,
    closingQty: ExactDecimal,
    currencyScale = 2,
  ): ExactDecimal {
    const unitCostScale10 = netInwardValue.divExactScale10(netInwardQty);
    return closingQty.mulScaled(unitCostScale10, currencyScale, 'HALF_UP');
  }

  negate(): ExactDecimal {
    return this.unscaled === 0n ? this : new ExactDecimal(-this.unscaled, this.scale);
  }

  abs(): ExactDecimal {
    return this.unscaled < 0n ? new ExactDecimal(-this.unscaled, this.scale) : this;
  }

  isZero(): boolean {
    return this.unscaled === 0n;
  }

  isNegative(): boolean {
    return this.unscaled < 0n;
  }

  isPositive(): boolean {
    return this.unscaled > 0n;
  }

  compare(other: ExactDecimal): -1 | 0 | 1 {
    const commonScale = this.scale >= other.scale ? this.scale : other.scale;
    const lhs = rescaleRawBigInt(this.unscaled, this.scale, commonScale, 'UNNECESSARY');
    const rhs = rescaleRawBigInt(other.unscaled, other.scale, commonScale, 'UNNECESSARY');
    if (lhs < rhs) return -1;
    if (lhs > rhs) return 1;
    return 0;
  }

  equals(other: ExactDecimal): boolean {
    return this.compare(other) === 0;
  }

  static compare(a: ExactDecimal, b: ExactDecimal): -1 | 0 | 1 {
    return a.compare(b);
  }

  static sum(values: readonly (ExactDecimal | string)[], scale = 2): ExactDecimal {
    let acc = ExactDecimal.zero(scale);
    for (const item of values) {
      const dec = typeof item === 'string' ? ExactDecimal.parse(item, scale, 'HALF_UP') : item;
      acc = acc.add(dec);
    }
    return acc.rescale(scale, 'HALF_UP');
  }

  /**
   * Canonical ASCII JSON wire representation (`"125000.50"`, `"-45.00"`).
   */
  toWireString(scale?: number): string {
    const target = scale !== undefined ? this.rescale(scale, 'HALF_UP') : this;
    const isNeg = target.unscaled < 0n;
    const absStr = (isNeg ? -target.unscaled : target.unscaled).toString();

    if (target.scale === 0) {
      return `${isNeg ? '-' : ''}${absStr}`;
    }

    const padded = absStr.padStart(target.scale + 1, '0');
    const splitIdx = padded.length - target.scale;
    const intPart = padded.slice(0, splitIdx);
    const fracPart = padded.slice(splitIdx);
    return `${isNeg ? '-' : ''}${intPart}.${fracPart}`;
  }

  toString(): string {
    return this.toWireString();
  }

  /**
   * Formats in Indian lakh/crore grouping (`5,00,000.00`, `1,02,600.00`, `18,685.71`)
   * with optional U+20B9 (`₹`) and U+2212 (`−`) true minus sign.
   */
  formatIndian(options: IndianFormatOptions = {}): string {
    const scale = options.scale ?? this.scale;
    const target = this.rescale(scale, 'HALF_UP');
    const isNeg = target.unscaled < 0n;
    const absWire = target.abs().toWireString(scale);
    const [rawInt, rawFrac] = absWire.split('.');

    const groupedInt = groupIndianInteger(rawInt);
    const numericPart = rawFrac !== undefined ? `${groupedInt}.${rawFrac}` : groupedInt;

    const currencyPrefix = options.withCurrencySymbol
      ? `${options.currencySymbol ?? RUPEE_SYMBOL} `
      : '';
    const minusChar = options.useTrueMinus === false ? '-' : TRUE_MINUS;
    const signPrefix = isNeg && !options.suffixSide ? minusChar : '';
    const sideSuffix = options.suffixSide ? ` ${options.suffixSide === 'DR' ? 'Dr' : 'Cr'}` : '';

    return `${signPrefix}${currencyPrefix}${numericPart}${sideSuffix}`;
  }

  formatInternational(options: {
    scale?: number;
    withCurrencySymbol?: boolean;
    currencySymbol?: string;
    useTrueMinus?: boolean;
  } = {}): string {
    const scale = options.scale ?? this.scale;
    const target = this.rescale(scale, 'HALF_UP');
    const isNeg = target.unscaled < 0n;
    const absWire = target.abs().toWireString(scale);
    const [rawInt, rawFrac] = absWire.split('.');
    const groupedInt = rawInt.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    const numericPart = rawFrac !== undefined ? `${groupedInt}.${rawFrac}` : groupedInt;
    const currencyPrefix = options.withCurrencySymbol
      ? `${options.currencySymbol ?? '$'} `
      : '';
    const minusChar = options.useTrueMinus === false ? '-' : TRUE_MINUS;
    return `${isNeg ? minusChar : ''}${currencyPrefix}${numericPart}`;
  }

  /**
   * Safe recursive-descent inline calculator supporting `+`, `-` (ASCII & U+2212),
   * `*`, `/`, `%` (both standalone `18%` => `0.18` and Tally additive `1250 + 18%` => `1475.00`),
   * unary `+/-`, and parentheses `(...)` using scale-12 bigint arithmetic.
   */
  static evaluateExpression(rawExpression: string, resultScale = 2): ExactDecimal {
    assertValidScale(resultScale);
    let expr = rawExpression.trim();
    if (expr.startsWith('=')) {
      expr = expr.slice(1).trim();
    }
    if (expr.length === 0) {
      throw new ExactDecimalError('MC-ACC-EXPR-SYNTAX', 'Empty calculator expression');
    }

    const tokens = tokenizeCalculatorExpression(expr);
    const parser = new CalculatorParser(tokens);
    const value = parser.parseFullExpression();
    return value.rescale(resultScale, 'HALF_UP');
  }
}

function groupIndianInteger(digits: string): string {
  if (digits.length <= 3) {
    return digits;
  }
  const lastThree = digits.slice(-3);
  const head = digits.slice(0, -3);
  const pairs: string[] = [];
  for (let i = head.length; i > 0; i -= 2) {
    const start = i - 2 >= 0 ? i - 2 : 0;
    pairs.unshift(head.slice(start, i));
  }
  return `${pairs.join(',')},${lastThree}`;
}

type CalcToken =
  | { readonly kind: 'NUM'; readonly raw: string }
  | { readonly kind: 'OP'; readonly op: '+' | '-' | '*' | '/' | '%' | '(' | ')' };

interface EvalTerm {
  readonly value: ExactDecimal;
  readonly isBarePercent: boolean;
  readonly rawBeforePercent?: ExactDecimal;
}

const HUNDRED = ExactDecimal.fromInt(100, INTERNAL_CALC_SCALE);

function tokenizeCalculatorExpression(expr: string): CalcToken[] {
  const tokens: CalcToken[] = [];
  let i = 0;

  while (i < expr.length) {
    const ch = expr[i];
    if (ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r' || ch === RUPEE_SYMBOL) {
      i += 1;
      continue;
    }

    if (ch === TRUE_MINUS) {
      tokens.push({ kind: 'OP', op: '-' });
      i += 1;
      continue;
    }

    if (ch === '+' || ch === '-' || ch === '*' || ch === '/' || ch === '%' || ch === '(' || ch === ')') {
      tokens.push({ kind: 'OP', op: ch });
      i += 1;
      continue;
    }

    if ((ch >= '0' && ch <= '9') || ch === '.') {
      let numBuf = '';
      let dotSeen = false;
      while (i < expr.length) {
        const c = expr[i];
        if (c >= '0' && c <= '9') {
          numBuf += c;
          i += 1;
        } else if (c === ',') {
          i += 1;
        } else if (c === '.') {
          if (dotSeen) {
            throw new ExactDecimalError('MC-ACC-EXPR-SYNTAX', `Multiple decimal points in "${expr}"`);
          }
          dotSeen = true;
          numBuf += c;
          i += 1;
        } else {
          break;
        }
      }
      if (numBuf === '.') {
        throw new ExactDecimalError('MC-ACC-EXPR-SYNTAX', `Invalid number token "." in "${expr}"`);
      }
      tokens.push({ kind: 'NUM', raw: numBuf });
      continue;
    }

    throw new ExactDecimalError(
      'MC-ACC-EXPR-SYNTAX',
      `Unexpected character "${ch}" in calculator expression`,
    );
  }

  return tokens;
}

class CalculatorParser {
  private pos = 0;

  constructor(private readonly tokens: readonly CalcToken[]) {}

  parseFullExpression(): ExactDecimal {
    const res = this.parseAddSub();
    if (this.pos < this.tokens.length) {
      throw new ExactDecimalError('MC-ACC-EXPR-SYNTAX', 'Unexpected trailing tokens in expression');
    }
    return res.value;
  }

  private peek(): CalcToken | undefined {
    return this.tokens[this.pos];
  }

  private consume(): CalcToken {
    const tok = this.tokens[this.pos];
    if (!tok) {
      throw new ExactDecimalError('MC-ACC-EXPR-SYNTAX', 'Unexpected end of calculator expression');
    }
    this.pos += 1;
    return tok;
  }

  private parseAddSub(): EvalTerm {
    let left = this.parseMulDiv();

    while (true) {
      const tok = this.peek();
      if (!tok || tok.kind !== 'OP' || (tok.op !== '+' && tok.op !== '-')) {
        break;
      }
      this.consume();
      const right = this.parseMulDiv();

      // Tally calculator parity: `1250 + 18%` => `1250 + (1250 * 0.18)`
      const effectiveRhs =
        right.isBarePercent && right.rawBeforePercent
          ? left.value.mulScaled(right.value, INTERNAL_CALC_SCALE, 'HALF_UP')
          : right.value;

      const combined =
        tok.op === '+'
          ? left.value.add(effectiveRhs, INTERNAL_CALC_SCALE)
          : left.value.sub(effectiveRhs, INTERNAL_CALC_SCALE);

      left = { value: combined, isBarePercent: false };
    }

    return left;
  }

  private parseMulDiv(): EvalTerm {
    let left = this.parseUnary();

    while (true) {
      const tok = this.peek();
      if (!tok || tok.kind !== 'OP' || (tok.op !== '*' && tok.op !== '/')) {
        break;
      }
      this.consume();
      const right = this.parseUnary();

      const combined =
        tok.op === '*'
          ? left.value.mulScaled(right.value, INTERNAL_CALC_SCALE, 'HALF_UP')
          : left.value.divHalfUp(right.value, INTERNAL_CALC_SCALE);

      left = { value: combined, isBarePercent: false };
    }

    return left;
  }

  private parseUnary(): EvalTerm {
    const tok = this.peek();
    if (tok && tok.kind === 'OP' && (tok.op === '+' || tok.op === '-')) {
      this.consume();
      const inner = this.parseUnary();
      return {
        value: tok.op === '-' ? inner.value.negate() : inner.value,
        isBarePercent: inner.isBarePercent,
        rawBeforePercent:
          tok.op === '-' && inner.rawBeforePercent
            ? inner.rawBeforePercent.negate()
            : inner.rawBeforePercent,
      };
    }
    return this.parsePostfixPercent();
  }

  private parsePostfixPercent(): EvalTerm {
    let base = this.parsePrimary();
    let sawPercent = false;

    while (true) {
      const tok = this.peek();
      if (tok && tok.kind === 'OP' && tok.op === '%') {
        this.consume();
        const rawBefore = base.value;
        base = {
          value: base.value.divHalfUp(HUNDRED, INTERNAL_CALC_SCALE),
          isBarePercent: true,
          rawBeforePercent: rawBefore,
        };
        sawPercent = true;
      } else {
        break;
      }
    }

    return sawPercent ? base : { value: base.value, isBarePercent: false };
  }

  private parsePrimary(): EvalTerm {
    const tok = this.consume();
    if (tok.kind === 'NUM') {
      const parsed = ExactDecimal.parse(tok.raw, INTERNAL_CALC_SCALE, 'HALF_UP');
      return { value: parsed, isBarePercent: false };
    }
    if (tok.kind === 'OP' && tok.op === '(') {
      const inner = this.parseAddSub();
      const closing = this.consume();
      if (closing.kind !== 'OP' || closing.op !== ')') {
        throw new ExactDecimalError('MC-ACC-EXPR-SYNTAX', 'Expected closing parenthesis ")"');
      }
      return { value: inner.value, isBarePercent: false };
    }
    throw new ExactDecimalError('MC-ACC-EXPR-SYNTAX', 'Expected number or "(" in expression');
  }
}
