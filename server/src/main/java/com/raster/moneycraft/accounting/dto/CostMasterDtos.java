package com.raster.moneycraft.accounting.dto;

import java.util.UUID;

/**
 * Parallel Cost Category and hierarchical Cost Centre wire contracts.
 */
public final class CostMasterDtos {

    private CostMasterDtos() {
    }

    public record CostCategoryResponse(
        UUID id,
        UUID companyId,
        String name,
        boolean allocateRevenueItems,
        boolean allocateNonRevenueItems,
        boolean isPrimaryCategory,
        boolean active,
        long masterSeqId,
        long alterId,
        long versionNo
    ) {
    }

    public record CreateOrUpdateCostCategoryRequest(
        String name,
        Boolean allocateRevenueItems,
        Boolean allocateNonRevenueItems,
        Boolean active,
        Long versionNo
    ) {
    }

    public record CostCentreResponse(
        UUID id,
        UUID companyId,
        UUID categoryId,
        String categoryName,
        UUID parentCostCentreId,
        String parentCostCentreName,
        String name,
        String aliasCode,
        int depthLevel,
        boolean active,
        long masterSeqId,
        long alterId,
        long versionNo
    ) {
    }

    public record CreateOrUpdateCostCentreRequest(
        UUID categoryId,
        UUID costCategoryId,
        UUID parentCostCentreId,
        UUID parentId,
        String name,
        String aliasCode,
        Boolean active,
        Long versionNo
    ) {
        public UUID resolvedCategoryId() {
            return categoryId != null ? categoryId : costCategoryId;
        }

        public UUID resolvedParentId() {
            return parentCostCentreId != null ? parentCostCentreId : parentId;
        }
    }
}
