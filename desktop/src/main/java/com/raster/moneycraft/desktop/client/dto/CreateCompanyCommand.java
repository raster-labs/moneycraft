package com.raster.moneycraft.desktop.client.dto;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Request DTO for creating or altering a company and its F11 Company Features (UM-110-03).
 */
public record CreateCompanyCommand(
    String code,
    String legalName,
    String mailingName,
    String addressLines,
    String countryCode,
    String stateCode,
    String stateName,
    String pinCode,
    String baseCurrencyCode,
    String currencySymbol,
    int currencyScale,
    LocalDate financialYearFrom,
    LocalDate booksBeginningFrom,
    Long versionNo,
    Features features
) {

    public CreateCompanyCommand {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(legalName, "legalName must not be null");
        if (code.isBlank() || legalName.isBlank()) {
            throw new IllegalArgumentException("Company code and legalName must not be blank.");
        }
    }

    public record Features(
        boolean maintainAccounts,
        boolean billWiseEnabled,
        boolean costCentresEnabled,
        boolean maintainInventory,
        boolean inventoryIntegrated,
        boolean gstEnabled,
        boolean multiGstinEnabled,
        String gstRegistrationType,
        String gstin,
        String gstr1Periodicity,
        LocalDate gstEffectiveFrom,
        boolean tdsEnabled,
        boolean mcaEditLogEnforced
    ) {
        public static Features defaults() {
            return new Features(
                true, true, true, true, true, true, false,
                "REGULAR", null, "MONTHLY", LocalDate.of(2026, 4, 1), true, true
            );
        }
    }
}
