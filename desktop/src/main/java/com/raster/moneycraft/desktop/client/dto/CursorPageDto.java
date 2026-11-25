package com.raster.moneycraft.desktop.client.dto;

import java.util.List;

public record CursorPageDto<T>(
    List<T> items,
    String nextCursor,
    boolean hasMore
) {
    public CursorPageDto {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
