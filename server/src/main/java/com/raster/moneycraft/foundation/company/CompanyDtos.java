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
        Boolean interestCalculationEnabled,
        Boolean maintainInventory,
        Boolean inventoryIntegrated,
        Boolean multiplePriceLevelsEnabled,
        Boolean batchesEnabled,
        Boolean expiryDateForBatchesEnabled,
        Boolean jobOrderProcessingEnabled,
        Boolean costTrackingEnabled,
        Boolean jobCostingEnabled,
        Boolean useDiscountColumnInInvoices,
        Boolean useSeparateActualAndBilledQty,
        Boolean gstEnabled,
        Boolean multiGstinEnabled,
        String gstRegistrationType,
        String gstin,
        String gstr1Periodicity,
        LocalDate gstEffectiveFrom,
        Boolean eInvoiceApplicable,
        Boolean eWayBillApplicable,
        String eWayBillThresholdLimit,
        Boolean tdsEnabled,
        Boolean tcsEnabled,
        Boolean vatEnabled,
        Boolean exciseEnabled,
        Boolean serviceTaxEnabled,
        Boolean payrollEnabled,
        Boolean payrollStatutoryEnabled,
        Boolean multipleAddressesEnabled,
        Boolean markModifiedVouchers,
        Boolean mcaEditLogEnforced
    ) {
        public CompanyFeaturesDto(
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
            this(
                maintainAccounts,
                billWiseEnabled,
                costCentresEnabled,
                false,
                maintainInventory,
                inventoryIntegrated,
                false,
                true,
                true,
                false,
                false,
                false,
                true,
                true,
                gstEnabled,
                multiGstinEnabled,
                gstRegistrationType,
                gstin,
                gstr1Periodicity,
                gstEffectiveFrom,
                false,
                true,
                "50000.00",
                tdsEnabled,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                mcaEditLogEnforced
            );
        }
    }

    public record CompanyF12ConfigDto(
        boolean useCrDrInsteadOfToBy,
        boolean provideReferenceNoAndDate,
        boolean provideDispatchOrderExportDetails,
        boolean warnOnNegativeCash,
        boolean warnOnNegativeStock,
        boolean preallocateBills,
        boolean showBillWiseList,
        boolean showBillFinalBalances,
        boolean useSingleEntryForPaymentReceiptContra,
        boolean showLedgerCurrentBalance,
        boolean showBalancesAsOnVoucherDate,
        boolean provideLineNarration,
        boolean provideCommonNarration,
        boolean consolidateStockItemsWithSameRate,
        boolean allowGstRateOverride,
        boolean provideAliases,
        boolean billWiseForNonTradingAccounts,
        boolean provideCreditLimits,
        boolean provideLedgerAddresses,
        boolean provideLedgerBankDetails,
        boolean providePartNumbers,
        boolean provideAlternateUnits,
        boolean provideStandardRates,
        boolean showOpeningBalances,
        boolean showTransactions,
        boolean showNettTransactionsOnly,
        boolean showClosingBalances,
        boolean showPercentages,
        String reportFormat,
        String scaleFactor,
        String sortingMethod
    ) {
        public static CompanyF12ConfigDto defaults() {
            return new CompanyF12ConfigDto(
                true, true, true, true, true, true, true, true, true, true,
                true, false, true, false, true,
                true, false, true, true, true, true, true, true,
                false, false, false, true, false, "CONDENSED", "DEFAULT", "DEFAULT"
            );
        }
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
