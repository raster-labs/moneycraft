package com.raster.moneycraft.desktop.client;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Ephemeral in-memory record of an active financial mutation intention (UX-009, SWG-001).
 * Never persisted to disk so no disconnected/offline financial posting queue exists.
 */
public record MutationIntention(
    UUID intentionId,
    String operationCode,
    String httpMethod,
    URI requestUri,
    String idempotencyKey,
    String ifMatchEtag,
    String wirePayloadJson,
    String canonicalPayloadSha256Hex,
    Instant createdAt
) {

    public MutationIntention {
        Objects.requireNonNull(intentionId, "intentionId must not be null");
        Objects.requireNonNull(operationCode, "operationCode must not be null");
        Objects.requireNonNull(httpMethod, "httpMethod must not be null");
        Objects.requireNonNull(requestUri, "requestUri must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(wirePayloadJson, "wirePayloadJson must not be null");
        Objects.requireNonNull(canonicalPayloadSha256Hex, "canonicalPayloadSha256Hex must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }
}
