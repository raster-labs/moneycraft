package com.raster.moneycraft.desktop.client;

import java.util.Objects;

/**
 * Carries a deserialized API resource alongside its HTTP ETag version token for optimistic concurrency.
 */
public record VersionedResponse<T>(
    T body,
    String etag,
    int statusCode
) {
    public VersionedResponse {
        Objects.requireNonNull(body, "body must not be null");
    }
}
