package com.raster.moneycraft.foundation.company;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * MoneyCraft multi-company master, F11 statutory feature flags, and fiscal period DTOs.
 */
public final class CompanyDtos {

    private CompanyDtos() {
    }

    public record CompanyFeaturesDto(
        Boolean maintainAccounts,
        Boolean billWiseEnabled,
        Boolean costCentresEnabled,
        Boolean maintainInventory,
        Boolean inventoryIntegrated,
        Boolean gstEnabled,
        Boolean multiGstinEnabled,
        String gstRegistrationType,
        String gstin,
        String gstr1Periodicity,
        LocalDate gstEffectiveFrom,
        Boolean tdsEnabled,
        Boolean mcaEditLogEnforced
    ) {
    }

    public record CreateOrUpdateCompanyRequest(
        String code,
        String legalName,
        String mailingName,
        String addressLines,
        String countryCode,
        String stateCode,
        String stateName,
        String pinCode,
        String pan,
        String baseCurrencyCode,
        String currencySymbol,
        Integer currencyScale,
        LocalDate financialYearFrom,
        LocalDate booksBeginningFrom,
        Long versionNo,
        CompanyFeaturesDto features
    ) {
    }

    public record CompanySummaryDto(
        UUID id,
        String code,
        String legalName,
        String mailingName,
        String countryCode,
        String stateCode,
        String pinCode,
        String panMasked,
        String baseCurrencyCode,
        String currencySymbol,
        int currencyScale,
        LocalDate financialYearFrom,
        LocalDate booksBeginningFrom,
        LocalDate lastEntryDate,
        long versionNo,
        CompanyFeaturesDto features
    ) {
    }

    public record AccountingPeriodDto(
        UUID id,
        UUID fiscalYearId,
        int periodNo,
        String code,
        String periodCode,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        String state,
        String lockedReason,
        long versionNo
    ) {
    }

    public record FiscalYearDto(
        UUID id,
        UUID fiscalYearId,
        String code,
        String periodCode,
        String displayLabel,
        LocalDate startDate,
        LocalDate endDate,
        boolean closed,
        String state,
        long versionNo,
        List<AccountingPeriodDto> periods
    ) {
    }

    public record CreateFiscalYearRequest(
        String code,
        String displayLabel,
        LocalDate startDate,
        LocalDate endDate
    ) {
    }

    public record PatchPeriodStateRequest(
        String state,
        String reason
    ) {
    }
}
