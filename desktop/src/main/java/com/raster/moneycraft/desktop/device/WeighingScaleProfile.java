package com.raster.moneycraft.desktop.device;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * Admitted serial weighing-scale profile (Doc 28 §7, DEV-003).
 * Uses exact {@link BigDecimal} bounds; zero binary floating-point types.
 */
public record WeighingScaleProfile(
    String profileId,
    boolean admitted,
    String manufacturer,
    String model,
    int baudRate,
    int dataBits,
    String parity,
    int stopBits,
    String frameTerminator,
    Duration maxReadingAge,
    String expectedUnit,
    int precisionScale,
    BigDecimal minNetWeight,
    BigDecimal maxNetWeight
) {

    public WeighingScaleProfile {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(manufacturer, "manufacturer must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(parity, "parity must not be null");
        Objects.requireNonNull(frameTerminator, "frameTerminator must not be null");
        Objects.requireNonNull(maxReadingAge, "maxReadingAge must not be null");
        Objects.requireNonNull(expectedUnit, "expectedUnit must not be null");
        Objects.requireNonNull(minNetWeight, "minNetWeight must not be null");
        Objects.requireNonNull(maxNetWeight, "maxNetWeight must not be null");

        if (profileId.isBlank() || expectedUnit.isBlank()) {
            throw new IllegalArgumentException("profileId and expectedUnit must not be blank.");
        }
        if (baudRate <= 0 || dataBits < 7 || stopBits < 1) {
            throw new IllegalArgumentException("Invalid serial port framing parameters.");
        }
        if (maxReadingAge.isZero() || maxReadingAge.isNegative()) {
            throw new IllegalArgumentException("maxReadingAge timeout must be positive.");
        }
        if (minNetWeight.signum() < 0 || maxNetWeight.compareTo(minNetWeight) <= 0) {
            throw new IllegalArgumentException("Invalid scale weight bounds.");
        }
    }

    public void assertAdmitted() {
        if (!admitted) {
            throw new DeviceSecurityException(
                "MC-SCALE-PROFILE-UNADMITTED",
                "Weighing scale profile '" + profileId + "' is not admitted (DEV-004)."
            );
        }
    }
}
