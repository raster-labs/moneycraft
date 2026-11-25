package com.raster.moneycraft.desktop.client.dto;

import java.math.BigDecimal;
import java.util.List;

public record CoaTreeDto(
    List<CoaTreeNodeDto> nodes,
    int totalGroups,
    int totalLedgers,
    BigDecimal totalOpeningDebit,
    BigDecimal totalOpeningCredit,
    BigDecimal openingDifference
) {
    public CoaTreeDto {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}
