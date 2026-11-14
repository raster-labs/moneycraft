package com.raster.moneycraft.reader.model;

import java.util.Objects;

/**
 * Extracted Unit of Measure (UOM) master (classId = 12 in Manager.1800 or UNIT in XML).
 */
public record RawUom(
        int masterId,
        String guid,
        String symbol,
        String formalName,
        int decimalPlaces,
        boolean simpleUnit
) {
    public RawUom {
        Objects.requireNonNull(guid, "guid must not be null");
        Objects.requireNonNull(symbol, "symbol must not be null");
        formalName = formalName == null ? "" : formalName;
    }
}
