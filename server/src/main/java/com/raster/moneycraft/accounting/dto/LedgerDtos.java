package com.raster.moneycraft.accounting.dto;

import java.util.UUID;

/**
 * Accounting Ledger master and sub-15ms Typeahead wire contracts.
 */
public final class LedgerDtos {

    private LedgerDtos() {
    }

    public record BankDetailsDto(
        String accountHolder,
        String accountNumber,
        String ifscCode,
        String bankName,
        String branch,
        String upiId
    ) {
    }

    public record LedgerResponse(
        UUID id,
        UUID companyId,
        String name,
        String aliasCode,
        UUID groupId,
        String groupName,
        String nature,
        String openingBalance,
        String openingSide,
        String currencyCode,
        boolean maintainBalancesBillByBill,
        Integer defaultCreditPeriodDays,
        boolean checkCreditDaysDuringVoucher,
        String creditLimit,
        boolean inventoryValuesAffected,
        boolean costCentresApplicable,
        String taxType,
        String dutyHead,
        String gstApplicability,
        String gstSupplyType,
        String gstRatePct,
        String hsnSacCode,
        String appropriateFor,
        String partyRegistrationType,
        String countryCode,
        String stateCode,
        String stateName,
        String pincode,
        String gstin,
        String panMasked,
        boolean tdsApplicable,
        String tdsNatureCode,
        String tdsDeducteeType,
        String msmeUdyamNo,
        BankDetailsDto bankDetails,
        boolean isSystemLedger,
        boolean active,
        long masterSeqId,
        long alterId,
        long versionNo
    ) {
    }

    public record LedgerTypeaheadResponse(
        UUID id,
        String name,
        String aliasCode,
        UUID groupId,
        String groupName,
        String nature,
        boolean maintainBalancesBillByBill,
        boolean costCentresApplicable,
        String taxType,
        String dutyHead,
        String gstin,
        String stateName,
        String partyRegistrationType,
        String appropriateFor,
        boolean tdsApplicable,
        String tdsNatureCode,
        String currentBalance,
        String currentBalanceSide
    ) {
    }

    public record CreateOrUpdateLedgerRequest(
        String name,
        String aliasCode,
        UUID groupId,
        String openingBalance,
        String openingSide,
        String currencyCode,
        Boolean maintainBalancesBillByBill,
        Integer defaultCreditPeriodDays,
        Boolean checkCreditDaysDuringVoucher,
        String creditLimit,
        Boolean inventoryValuesAffected,
        Boolean costCentresApplicable,
        String taxType,
        String dutyHead,
        String gstApplicability,
        String gstSupplyType,
        String gstRatePct,
        String hsnSacCode,
        String appropriateFor,
        String appropriationBasis,
        String partyRegistrationType,
        String countryCode,
        String stateCode,
        String stateName,
        String pincode,
        String gstin,
        String pan,
        Boolean tdsApplicable,
        String tdsNatureCode,
        String tdsDeducteeType,
        String msmeUdyamNo,
        BankDetailsDto bankDetails,
        Boolean active,
        Long versionNo
    ) {
    }
}
