package com.raster.moneycraft.desktop.device;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable audit and state snapshot of a single local print attempt (DEV-002, DEV-004).
 * Stores payload digest rather than retaining raw sensitive document text on disk.
 */
public record PrintAttemptRecord(
    UUID attemptId,
    UUID companyId,
    UUID actorPrincipalId,
    UUID documentId,
    long documentVersion,
    boolean containsSelectedPrivateFields,
    String payloadSha256Hex,
    String profileId,
    String profileVersion,
    String destination,
    PrintAttemptState state,
    CompletionEvidenceSource evidenceSource,
    boolean reprint,
    UUID reprintOfAttemptId,
    String reprintReason,
    boolean unknownReconciled,
    String reconciliationNote,
    String diagnosticMessage,
    Instant createdAt,
    Instant updatedAt,
    Instant expiresAt
) {

    public PrintAttemptRecord {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(actorPrincipalId, "actorPrincipalId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(payloadSha256Hex, "payloadSha256Hex must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(profileVersion, "profileVersion must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(evidenceSource, "evidenceSource must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
