package com.raster.moneycraft.foundation.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OperationalEvent(
    String eventId,
    String type,
    UUID companyId,
    Instant occurredAt,
    Map<String, Object> payload
) {
}
