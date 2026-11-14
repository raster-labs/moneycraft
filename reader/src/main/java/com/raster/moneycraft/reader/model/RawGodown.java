package com.raster.moneycraft.reader.model;

import java.util.Objects;

/**
 * Extracted Godown / Location master (classId = 6 in Manager.1800 or GODOWN in XML).
 */
public record RawGodown(
        int masterId,
        String guid,
        String name,
        String parentName
) {
    public RawGodown {
        Objects.requireNonNull(guid, "guid must not be null");
        Objects.requireNonNull(name, "name must not be null");
        parentName = parentName == null ? "Primary" : parentName;
    }
}
