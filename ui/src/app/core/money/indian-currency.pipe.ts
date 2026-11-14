import { Pipe, PipeTransform } from '@angular/core';
import { ExactDecimal } from './exact-decimal';

@Pipe({
  name: 'mcIndianCurrency',
  standalone: true,
  pure: true,
})
export class IndianCurrencyPipe implements PipeTransform {
  transform(
    value: ExactDecimal | string | null | undefined,
    scale = 2,
    withSymbol = true,
    suffixSide?: 'DR' | 'CR',
  ): string {
    if (value === null || value === undefined || value === '') {
      return ExactDecimal.zero(scale).formatIndian({
        scale,
        withCurrencySymbol: withSymbol,
        suffixSide,
      });
    }

    const decimal =
      value instanceof ExactDecimal ? value : ExactDecimal.tryParse(value, scale, 'HALF_UP');

    if (!decimal) {
      return value.toString();
    }

    return decimal.formatIndian({
      scale,
      withCurrencySymbol: withSymbol,
      suffixSide,
    });
  }
}
