package com.raster.moneycraft.reader.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Extracted Stock Item master (classId = 9 in Manager.1800 or STOCKITEM in XML).
 */
public record RawStockItem(
        int masterId,
        String guid,
        String name,
        String parentGroupName,
        String baseUnitSymbol,
        String costingMethod,
        BigDecimal openingQty,
        BigDecimal openingValueSigned
) {
    public RawStockItem {
        Objects.requireNonNull(guid, "guid must not be null");
        Objects.requireNonNull(name, "name must not be null");
        parentGroupName = parentGroupName == null ? "Primary" : parentGroupName;
        baseUnitSymbol = baseUnitSymbol == null ? "" : baseUnitSymbol;
        costingMethod = costingMethod == null || costingMethod.isBlank() ? "Avg. Cost" : costingMethod;
        openingQty = openingQty == null ? BigDecimal.ZERO : openingQty;
        openingValueSigned = openingValueSigned == null ? BigDecimal.ZERO : openingValueSigned;
    }
}
