package com.raster.moneycraft.desktop.device;

import java.util.Objects;

/**
 * Validated barcode capture outcome preserving exact leading zeros as untrusted string data (DEV-003).
 */
public record BarcodeCaptureResult(
    boolean accepted,
    RejectionReason rejectionReason,
    String exactIdentifier,
    String targetFieldId,
    String diagnosticMessage
) {

    public enum RejectionReason {
        NONE,
        EMPTY_OR_FRAMING_MISMATCH,
        CONTROL_CHARACTER_INJECTION,
        LENGTH_OUT_OF_BOUNDS,
        CONSECUTIVE_DUPLICATE_SCAN
    }

    public BarcodeCaptureResult {
        Objects.requireNonNull(rejectionReason, "rejectionReason must not be null");
        Objects.requireNonNull(diagnosticMessage, "diagnosticMessage must not be null");
        if (accepted && (exactIdentifier == null || exactIdentifier.isEmpty())) {
            throw new IllegalArgumentException("Accepted barcode capture must provide non-empty exactIdentifier.");
        }
        if (!accepted && exactIdentifier != null) {
            throw new IllegalArgumentException("Rejected barcode capture must not expose partial identifier.");
        }
    }

    public static BarcodeCaptureResult rejected(RejectionReason reason, String diagnosticMessage) {
        return new BarcodeCaptureResult(false, reason, null, null, diagnosticMessage);
    }

    public static BarcodeCaptureResult accepted(String exactIdentifier, String targetFieldId) {
        return new BarcodeCaptureResult(
            true,
            RejectionReason.NONE,
            exactIdentifier,
            targetFieldId,
            "Captured exact identifier '" + exactIdentifier + "' into '" + targetFieldId + "'."
        );
    }
}
