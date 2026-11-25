package com.raster.moneycraft.desktop.client;

import java.util.Objects;

/**
 * Indicates that an in-flight financial mutation lost its network response before a definitive
 * HTTP status arrived, preserving the original {@link MutationIntention} so the client checks
 * server status and retries with the identical Idempotency-Key and If-Match header (UX-009, SWG-001).
 */
public final class UncertainTransportException extends RuntimeException {

    private final MutationIntention intention;

    public UncertainTransportException(MutationIntention intention, Throwable cause) {
        super(
            "Outcome unknown for mutation '" + Objects.requireNonNull(intention, "intention").operationCode()
                + "' [Idempotency-Key=" + intention.idempotencyKey() + "]; check server status before retrying.",
            cause
        );
        this.intention = intention;
    }

    public MutationIntention intention() {
        return intention;
    }
}
