package com.raster.moneycraft.reader.model;

import java.util.Objects;

/**
 * Extracted accounting Group master (classId = 2 in Manager.1800 or GROUP in XML).
 */
public record RawGroup(
        int masterId,
        String guid,
        String name,
        String parentName,
        boolean revenue,
        boolean deemedPositive,
        boolean affectsGrossProfit,
        boolean subledger,
        int alterId
) {
    public RawGroup {
        Objects.requireNonNull(guid, "guid must not be null");
        Objects.requireNonNull(name, "name must not be null");
        parentName = parentName == null ? "Primary" : parentName;
    }
}
