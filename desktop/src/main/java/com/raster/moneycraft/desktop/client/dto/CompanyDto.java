package com.raster.moneycraft.desktop.client.dto;

import java.time.LocalDate;
import java.util.UUID;

public record CompanyDto(
    UUID id,
    String code,
    String legalName,
    String mailingName,
    String countryCode,
    String stateCode,
    String baseCurrencyCode,
    String currencySymbol,
    int currencyScale,
    LocalDate financialYearFrom,
    LocalDate booksBeginningFrom,
    long versionNo
) {
}
