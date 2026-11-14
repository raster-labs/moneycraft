package com.raster.moneycraft.accounting.dto;

import java.util.UUID;

/**
 * TallyPrime 7.2 24 predefined and custom Voucher Type master wire contracts.
 */
public final class VoucherTypeDtos {

    private VoucherTypeDtos() {
    }

    public record VoucherTypeResponse(
        UUID id,
        UUID companyId,
        String name,
        String abbreviation,
        UUID parentVoucherTypeId,
        String parentVoucherType,
        String category,
        boolean postsAccountingEntries,
        boolean movesInventory,
        String defaultEntryMode,
        String numberingMethod,
        String approvalPolicy,
        boolean preventDuplicates,
        boolean allowZeroValued,
        boolean isOptionalByDefault,
        boolean printAfterSave,
        String shortcutKey,
        boolean isSystemType,
        boolean active,
        long masterSeqId,
        long alterId,
        long versionNo
    ) {
    }

    public record CreateOrUpdateVoucherTypeRequest(
        String name,
        String abbreviation,
        UUID parentVoucherTypeId,
        String parentVoucherType,
        String category,
        Boolean postsAccountingEntries,
        Boolean movesInventory,
        String defaultEntryMode,
        String numberingMethod,
        String approvalPolicy,
        Boolean preventDuplicates,
        Boolean allowZeroValued,
        Boolean isOptionalByDefault,
        Boolean printAfterSave,
        String shortcutKey,
        Boolean active,
        Long versionNo
    ) {
    }
}
