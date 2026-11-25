package com.raster.moneycraft.desktop.device;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Validated weighing-scale capture outcome (DEV-003).
 * Rejected frames never expose zero or reuse a stale prior reading; {@code netWeight} is null on rejection.
 */
public record ScaleReadingResult(
    boolean accepted,
    RejectionReason rejectionReason,
    BigDecimal grossWeight,
    BigDecimal tareWeight,
    BigDecimal netWeight,
    String unit,
    String diagnosticMessage
) {

    public enum RejectionReason {
        NONE,
        DISCONNECTED,
        MALFORMED_FRAME,
        STALE_TIMEOUT,
        UNSTABLE_READING,
        DEVICE_ERROR_FLAG,
        UNIT_MISMATCH,
        TARE_GROSS_NET_MISMATCH,
        OUT_OF_RANGE
    }

    public ScaleReadingResult {
        Objects.requireNonNull(rejectionReason, "rejectionReason must not be null");
        Objects.requireNonNull(diagnosticMessage, "diagnosticMessage must not be null");
        if (accepted && netWeight == null) {
            throw new IllegalArgumentException("Accepted scale reading must provide non-null netWeight.");
        }
        if (!accepted && netWeight != null) {
            throw new IllegalArgumentException("Rejected scale reading must never supply a fallback netWeight.");
        }
    }

    public static ScaleReadingResult rejected(RejectionReason reason, String diagnosticMessage) {
        return new ScaleReadingResult(false, reason, null, null, null, null, diagnosticMessage);
    }

    public static ScaleReadingResult accepted(
        BigDecimal grossWeight,
        BigDecimal tareWeight,
        BigDecimal netWeight,
        String unit
    ) {
        return new ScaleReadingResult(
            true,
            RejectionReason.NONE,
            grossWeight,
            tareWeight,
            netWeight,
            unit,
            "Verified stable reading (" + netWeight.toPlainString() + " " + unit + ")."
        );
    }
}
