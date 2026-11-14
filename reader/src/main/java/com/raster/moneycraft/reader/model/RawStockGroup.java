package com.raster.moneycraft.reader.model;

import java.util.Objects;

/**
 * Extracted Stock Group master (classId = 7 in Manager.1800 or STOCKGROUP in XML).
 */
public record RawStockGroup(
        int masterId,
        String guid,
        String name,
        String parentName
) {
    public RawStockGroup {
        Objects.requireNonNull(guid, "guid must not be null");
        Objects.requireNonNull(name, "name must not be null");
        parentName = parentName == null ? "Primary" : parentName;
    }
}
