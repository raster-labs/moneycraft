package com.raster.moneycraft.accounting.dto;

import java.util.List;

/**
 * Signed keyset pagination envelope (LIST-050-01).
 */
public record CursorPage<T>(
    List<T> items,
    String nextCursor,
    boolean hasMore
) {
}
