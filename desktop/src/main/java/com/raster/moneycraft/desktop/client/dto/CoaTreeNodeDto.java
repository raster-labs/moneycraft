package com.raster.moneycraft.desktop.client.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CoaTreeNodeDto(
    UUID id,
    String nodeType,
    String name,
    String aliasCode,
    UUID parentId,
    String nature,
    boolean isSystem,
    BigDecimal openingBalance,
    String openingSide,
    BigDecimal openingDebit,
    BigDecimal openingCredit,
    long versionNo,
    List<CoaTreeNodeDto> children
) {
    public CoaTreeNodeDto {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
