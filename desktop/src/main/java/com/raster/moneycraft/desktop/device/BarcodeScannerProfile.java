package com.raster.moneycraft.desktop.device;

import java.util.Objects;

/**
 * Admitted barcode scanner peripheral profile (Doc 28 §7, Doc 29 §Scanner profile safeguard, DEV-003).
 * Explicitly separates framed/native devices from unframed keyboard-wedge workflows; never relies
 * on typing speed heuristics.
 */
public record BarcodeScannerProfile(
    String profileId,
    boolean admitted,
    String manufacturer,
    String model,
    IsolationMode isolationMode,
    String expectedPrefix,
    String expectedSuffix,
    boolean preserveLeadingZeros,
    DuplicateScanPolicy duplicateScanPolicy,
    int minIdentifierLength,
    int maxIdentifierLength
) {

    public enum IsolationMode {
        FRAMED_SERIAL_OR_NATIVE,
        UNFRAMED_KEYBOARD_WEDGE
    }

    public enum DuplicateScanPolicy {
        REJECT_CONSECUTIVE_DUPLICATE,
        ALLOW_REPEATED_SCAN
    }

    public BarcodeScannerProfile {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(manufacturer, "manufacturer must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(isolationMode, "isolationMode must not be null");
        Objects.requireNonNull(expectedPrefix, "expectedPrefix must not be null");
        Objects.requireNonNull(expectedSuffix, "expectedSuffix must not be null");
        Objects.requireNonNull(duplicateScanPolicy, "duplicateScanPolicy must not be null");

        if (profileId.isBlank()) {
            throw new IllegalArgumentException("profileId must not be blank.");
        }
        if (!preserveLeadingZeros) {
            throw new IllegalArgumentException("Doc 28 §7 violation: barcode profiles must preserve leading zeros.");
        }
        if (minIdentifierLength <= 0 || maxIdentifierLength < minIdentifierLength) {
            throw new IllegalArgumentException("Invalid barcode identifier length bounds.");
        }
    }

    public void assertAdmitted() {
        if (!admitted) {
            throw new DeviceSecurityException(
                "MC-BARCODE-PROFILE-UNADMITTED",
                "Barcode scanner profile '" + profileId + "' is not admitted (DEV-004)."
            );
        }
    }
}
