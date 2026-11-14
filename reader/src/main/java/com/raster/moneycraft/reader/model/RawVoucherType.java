package com.raster.moneycraft.reader.model;

import java.util.Objects;

/**
 * Extracted Voucher Type master (classId = 10 in Manager.1800 or VOUCHERTYPE in XML).
 */
public record RawVoucherType(
        int masterId,
        String guid,
        String name,
        String parentName,
        String abbreviation,
        int baseTypeId,
        boolean active,
        int alterId
) {
    public RawVoucherType {
        Objects.requireNonNull(guid, "guid must not be null");
        Objects.requireNonNull(name, "name must not be null");
        parentName = parentName == null ? "" : parentName;
        abbreviation = abbreviation == null ? "" : abbreviation;
    }
}
