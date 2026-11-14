import { describe, expect, it } from 'vitest';
import {
  ExactDecimal,
  ExactDecimalError,
  RUPEE_SYMBOL,
  TRUE_MINUS,
} from './exact-decimal';
import { IndianCurrencyPipe } from './indian-currency.pipe';

describe('ExactDecimal (MCJ1-DATA-01 Pure BigInt Arithmetic)', () => {
  it('parses exact wire strings and rejects excess scale under UNNECESSARY rounding', () => {
    const val = ExactDecimal.parse('125000.50', 2, 'UNNECESSARY');
    expect(val.toWireString(2)).toBe('125000.50');
    expect(val.isZero()).toBe(false);
    expect(val.isPositive()).toBe(true);

    expect(() => ExactDecimal.parse('125.505', 2, 'UNNECESSARY')).toThrowError(ExactDecimalError);
    try {
      ExactDecimal.parse('125.505', 2, 'UNNECESSARY');
    } catch (err) {
      expect((err as ExactDecimalError).code).toBe('MC-ACC-SCALE-OVERFLOW');
    }
  });

  it('adds, subtracts, multiplies, divides and compares without IEEE-754 float drift', () => {
    // Classic IEEE-754 trap: 0.1 + 0.2 === 0.30000000000000004 in JS floats
    const a = ExactDecimal.parse('0.10', 2);
    const b = ExactDecimal.parse('0.20', 2);
    const sum = a.add(b);
    expect(sum.toWireString(2)).toBe('0.30');
    expect(sum.compare(ExactDecimal.parse('0.3', 1))).toBe(0);

    const diff = ExactDecimal.parse('500000.00', 2)
      .sub(ExactDecimal.parse('25000.00', 2))
      .sub(ExactDecimal.parse('102600.00', 2))
      .add(ExactDecimal.parse('100000.00', 2));
    // Matches HDFC Current A/c closing balance in TALLY_PARITY_BENCHMARK.md (4,72,400.00)
    expect(diff.toWireString(2)).toBe('472400.00');
  });

  it('computes TallyPrime 7.2 Golden Vector Avg. Cost 60 * (43600 / 140) = 18685.71 (never 18685.80)', () => {
    const inwardVal = ExactDecimal.parse('43600.00', 2);
    const inwardQty = ExactDecimal.parse('140', 0);
    const closingQty = ExactDecimal.parse('60', 0);

    const unitCostScale10 = inwardVal.divExactScale10(inwardQty);
    expect(unitCostScale10.toWireString(10)).toBe('311.4285714286');

    const closingValue = closingQty.mulScaled(unitCostScale10, 2, 'HALF_UP');
    expect(closingValue.toWireString(2)).toBe('18685.71');
    expect(
      ExactDecimal.computeAvgCostClosingValue(inwardVal, inwardQty, closingQty, 2).toWireString(2),
    ).toBe('18685.71');
  });

  it('computes Golden Vector B Avg. Cost 11 * (2390 / 23) = 1143.04 (never 1143.01)', () => {
    const inwardVal = ExactDecimal.parse('2390.00', 2);
    const inwardQty = ExactDecimal.parse('23', 0);
    const closingQty = ExactDecimal.parse('11', 0);

    const unitCostScale10 = inwardVal.divExactScale10(inwardQty);
    expect(unitCostScale10.toWireString(10)).toBe('103.9130434783');

    const closingValue = closingQty.mulScaled(unitCostScale10, 2, 'HALF_UP');
    expect(closingValue.toWireString(2)).toBe('1143.04');
  });

  it('formats Indian lakh/crore grouping with U+20B9 ₹ and U+2212 − true minus', () => {
    expect(ExactDecimal.parse('500000.00').formatIndian()).toBe('5,00,000.00');
    expect(ExactDecimal.parse('102600.00').formatIndian()).toBe('1,02,600.00');
    expect(ExactDecimal.parse('18685.71').formatIndian({ withCurrencySymbol: true })).toBe(
      `${RUPEE_SYMBOL} 18,685.71`,
    );
    expect(ExactDecimal.parse('-18685.71').formatIndian({ withCurrencySymbol: true })).toBe(
      `${TRUE_MINUS}${RUPEE_SYMBOL} 18,685.71`,
    );
    expect(ExactDecimal.parse('123456789.50').formatIndian()).toBe('12,34,56,789.50');

    const pipe = new IndianCurrencyPipe();
    expect(pipe.transform('760448.00', 2, true)).toBe(`${RUPEE_SYMBOL} 7,60,448.00`);
    expect(pipe.transform('14624.00', 2, true, 'DR')).toBe(`${RUPEE_SYMBOL} 14,624.00 Dr`);
  });

  it('evaluates inline calculator expressions safely without eval or floats', () => {
    expect(ExactDecimal.evaluateExpression('(1250 * 18%) + 1250', 2).toWireString(2)).toBe(
      '1475.00',
    );
    expect(ExactDecimal.evaluateExpression('1250 + 18%', 2).toWireString(2)).toBe('1475.00');
    expect(ExactDecimal.evaluateExpression('1250 - 10%', 2).toWireString(2)).toBe('1125.00');
    expect(ExactDecimal.evaluateExpression('= 60 * (43600 / 140)', 2).toWireString(2)).toBe(
      '18685.71',
    );
    expect(ExactDecimal.evaluateExpression('1,00,000 + 25,000.50', 2).toWireString(2)).toBe(
      '125000.50',
    );
    expect(() => ExactDecimal.evaluateExpression('100 / 0', 2)).toThrowError(ExactDecimalError);
    expect(() => ExactDecimal.evaluateExpression('100 + * 5', 2)).toThrowError(ExactDecimalError);
  });
});
