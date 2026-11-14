package com.raster.moneycraft.foundation.crypto;

import java.time.Instant;
import java.util.UUID;

public record CursorPayload(
    UUID keyId,
    UUID principalId,
    UUID companyId,
    String endpointId,
    String filterHash,
    long cursorEpoch,
    String lastSortValue,
    UUID lastEntityId,
    Instant expiresAt
) {
}
