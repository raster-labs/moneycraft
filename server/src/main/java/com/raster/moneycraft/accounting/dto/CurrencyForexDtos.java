package com.raster.moneycraft.accounting.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Multi-currency master and date-effective forex rate wire contracts.
 */
public final class CurrencyForexDtos {

    private CurrencyForexDtos() {
    }

    public record CurrencyResponse(
        UUID id,
        UUID companyId,
        String isoCode,
        String symbol,
        String formalName,
        int decimalPlaces,
        String decimalSymbol,
        boolean showAmountInMillions,
        boolean suffixSymbolToAmount,
        boolean addSpaceBetweenAmountAndSymbol,
        boolean isBaseCurrency,
        boolean active,
        long masterSeqId,
        long alterId,
        long versionNo
    ) {
    }

    public record CreateOrUpdateCurrencyRequest(
        String isoCode,
        String symbol,
        String formalName,
        Integer decimalPlaces,
        String decimalSymbol,
        Boolean showAmountInMillions,
        Boolean suffixSymbolToAmount,
        Boolean addSpaceBetweenAmountAndSymbol,
        Boolean active,
        Long versionNo
    ) {
    }

    public record ForexRateResponse(
        UUID id,
        UUID companyId,
        UUID currencyId,
        String isoCode,
        LocalDate effectiveDate,
        String standardRate,
        String sellingRate,
        String buyingRate,
        long versionNo
    ) {
    }

    public record UpsertForexRateRequest(
        LocalDate effectiveDate,
        String standardRate,
        String sellingRate,
        String buyingRate
    ) {
    }
}
