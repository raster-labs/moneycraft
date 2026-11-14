package com.raster.moneycraft.accounting.dto;

import java.util.List;
import java.util.UUID;

/**
 * Chart of Accounts tree and Account Group master wire contracts.
 */
public final class AccountGroupDtos {

    private AccountGroupDtos() {
    }

    public record AccountGroupResponse(
        UUID id,
        UUID companyId,
        String name,
        String aliasCode,
        UUID parentGroupId,
        String parentGroupName,
        String nature,
        boolean affectsGrossProfit,
        boolean behavesLikeSubledger,
        boolean netDebitCreditForReporting,
        boolean usedForCalculation,
        String methodToAllocatePurchase,
        String scheduleIiiLineCode,
        String defaultGstRatePct,
        String defaultHsnSacCode,
        int depthLevel,
        boolean isSystemGroup,
        int sortOrder,
        long masterSeqId,
        long alterId,
        long versionNo
    ) {
    }

    public record CreateOrUpdateAccountGroupRequest(
        String name,
        String aliasCode,
        UUID parentGroupId,
        String nature,
        Boolean affectsGrossProfit,
        Boolean behavesLikeSubledger,
        Boolean netDebitCreditForReporting,
        Boolean usedForCalculation,
        String methodToAllocatePurchase,
        String scheduleIiiLineCode,
        String defaultGstRatePct,
        String defaultHsnSacCode,
        Integer sortOrder,
        Long versionNo
    ) {
    }

    public record CoaTreeNodeDto(
        UUID id,
        String nodeType,
        String name,
        String aliasCode,
        UUID parentId,
        String nature,
        boolean affectsGrossProfit,
        boolean behavesLikeSubledger,
        String scheduleIiiLineCode,
        boolean isSystem,
        String openingBalance,
        String openingSide,
        String openingDebit,
        String openingCredit,
        long versionNo,
        List<CoaTreeNodeDto> children
    ) {
    }

    public record ChartOfAccountsTreeResponse(
        List<CoaTreeNodeDto> nodes,
        int totalGroups,
        int totalLedgers,
        String totalOpeningDebit,
        String totalOpeningCredit,
        String openingDifference
    ) {
    }
}
