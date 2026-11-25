package com.raster.moneycraft.desktop.device;

import com.raster.moneycraft.desktop.keyboard.DesktopCommandRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Profiled serial weighing-scale frame parser and barcode scan session manager
 * with automatic unframed keyboard-wedge character-shortcut suppression (DEV-003, DEV-004, KEY-001).
 *
 * <p>Supported serial scale frame formats:
 * <ul>
 *   <li>Full gross/tare/net: {@code ST,GS,12.500,0.500,12.000,KG\r\n}</li>
 *   <li>Direct net frame: {@code ST,NT,12.000,KG\r\n}</li>
 * </ul>
 * Unstable ({@code US}), overload/error ({@code OL}/{@code ERR}), stale, disconnected, wrong-unit,
 * or tare-inconsistent frames are rejected explicitly and never default to {@code 0} or reuse a
 * previous reading.
 */
public final class ScaleAndBarcodeCaptureManager {

    private final DesktopCommandRegistry commandRegistry;
    private BarcodeScannerProfile activeBarcodeProfile;
    private String lastAcceptedBarcode;

    public ScaleAndBarcodeCaptureManager(DesktopCommandRegistry commandRegistry) {
        this.commandRegistry = Objects.requireNonNull(commandRegistry, "commandRegistry must not be null");
    }

    /**
     * Rejects unadmitted PoS payment-terminal, cardholder-data, or fiscal-printer claims (AT09-DEV-003-C).
     */
    public static void rejectUnclaimedPaymentOrFiscalCapability(String peripheralCategory) {
        throw new DeviceSecurityException(
            "MC-POS-CAPABILITY-UNCLAIMED",
            "Peripheral category '" + peripheralCategory
                + "' (payment terminal, cardholder data, or fiscal device) is not claimed or qualified in Increment N."
        );
    }

    /**
     * Rejects speed/timing-based keyboard-wedge detection claims (Doc 28 §7, AT09-DEV-003-C).
     */
    public static void rejectKeystrokeTimingScannerHeuristic() {
        throw new DeviceSecurityException(
            "MC-SCANNER-TIMING-HEURISTIC-FORBIDDEN",
            "Keystroke timing cannot reliably distinguish keyboard-wedge scanners from typing; "
                + "activate an explicit BarcodeScannerProfile instead."
        );
    }

    /**
     * Parses and validates a single weighing-scale serial frame under the admitted profile.
     */
    public ScaleReadingResult captureScaleFrame(
        WeighingScaleProfile profile,
        DeviceAuthorityContext authority,
        boolean transportConnected,
        String rawFrame,
        Instant frameTimestamp,
        Instant evaluationTimestamp
    ) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(authority, "authority must not be null");
        Objects.requireNonNull(evaluationTimestamp, "evaluationTimestamp must not be null");

        profile.assertAdmitted();
        authority.assertCanCaptureDeviceInput(authority.authorizedCompanyId(), "device.scaleCapture");

        if (!transportConnected || rawFrame == null || rawFrame.isEmpty()) {
            return ScaleReadingResult.rejected(
                ScaleReadingResult.RejectionReason.DISCONNECTED,
                "Scale serial connection is disconnected or empty; refusing to substitute zero or prior weight."
            );
        }
        if (frameTimestamp == null) {
            return ScaleReadingResult.rejected(
                ScaleReadingResult.RejectionReason.MALFORMED_FRAME,
                "Scale reading lacked capture timestamp."
            );
        }
        Duration age = Duration.between(frameTimestamp, evaluationTimestamp);
        if (age.isNegative() || age.compareTo(profile.maxReadingAge()) > 0) {
            return ScaleReadingResult.rejected(
                ScaleReadingResult.RejectionReason.STALE_TIMEOUT,
                "Scale reading exceeded freshness timeout (" + age.toMillis() + " ms > "
                    + profile.maxReadingAge().toMillis() + " ms)."
            );
        }
        if (!rawFrame.endsWith(profile.frameTerminator())) {
            return ScaleReadingResult.rejected(
                ScaleReadingResult.RejectionReason.MALFORMED_FRAME,
                "Scale frame did not end with expected terminator."
            );
        }

        String stripped = rawFrame.substring(0, rawFrame.length() - profile.frameTerminator().length()).trim();
        String[] tokens = stripped.split(",");
        if (tokens.length != 4 && tokens.length != 6) {
            return ScaleReadingResult.rejected(
                ScaleReadingResult.RejectionReason.MALFORMED_FRAME,
                "Expected 4 or 6 comma-delimited scale fields, got " + tokens.length + "."
            );
        }

        String stabilityToken = tokens[0].trim();
        if ("OL".equalsIgnoreCase(stabilityToken) || "ERR".equalsIgnoreCase(stabilityToken)) {
            return ScaleReadingResult.rejected(
                ScaleReadingResult.RejectionReason.DEVICE_ERROR_FLAG,
                "Scale reported hardware error or overload status '" + stabilityToken + "'."
            );
        }
        if ("US".equalsIgnoreCase(stabilityToken)) {
            return ScaleReadingResult.rejected(
                ScaleReadingResult.RejectionReason.UNSTABLE_READING,
                "Scale reading is unstable (US); wait for stable platform indicator before capture."
            );
        }
        if (!"ST".equalsIgnoreCase(stabilityToken)) {
            return ScaleReadingResult.rejected(
                ScaleReadingResult.RejectionReason.MALFORMED_FRAME,
                "Unknown stability indicator '" + stabilityToken + "'."
            );
        }

        String modeToken = tokens[1].trim();
        if (!"GS".equalsIgnoreCase(modeToken) && !"NT".equalsIgnoreCase(modeToken)) {
            return ScaleReadingResult.rejected(
                ScaleReadingResult.RejectionReason.MALFORMED_FRAME,
                "Unknown weight mode token '" + modeToken + "'."
            );
        }

        String unitToken = tokens[tokens.length - 1].trim();
        if (!profile.expectedUnit().equalsIgnoreCase(unitToken)) {
            return ScaleReadingResult.rejected(
                ScaleReadingResult.RejectionReason.UNIT_MISMATCH,
                "Scale reported unit '" + unitToken + "' instead of profile unit '" + profile.expectedUnit() + "'."
            );
        }

        try {
            BigDecimal gross;
            BigDecimal tare;
            BigDecimal net;
            if (tokens.length == 6) {
                gross = new BigDecimal(tokens[2].trim());
                tare = new BigDecimal(tokens[3].trim());
                net = new BigDecimal(tokens[4].trim());
                if (tare.signum() < 0 || gross.subtract(tare).compareTo(net) != 0) {
                    return ScaleReadingResult.rejected(
                        ScaleReadingResult.RejectionReason.TARE_GROSS_NET_MISMATCH,
                        "Scale gross (" + gross.toPlainString() + ") minus tare ("
                            + tare.toPlainString() + ") did not equal net (" + net.toPlainString() + ")."
                    );
                }
            } else {
                net = new BigDecimal(tokens[2].trim());
                gross = net;
                tare = BigDecimal.ZERO.setScale(profile.precisionScale());
            }

            if (net.compareTo(profile.minNetWeight()) < 0 || net.compareTo(profile.maxNetWeight()) > 0
                || net.signum() <= 0) {
                return ScaleReadingResult.rejected(
                    ScaleReadingResult.RejectionReason.OUT_OF_RANGE,
                    "Scale net weight " + net.toPlainString() + " " + profile.expectedUnit()
                        + " is outside permissible range [" + profile.minNetWeight().toPlainString()
                        + ", " + profile.maxNetWeight().toPlainString() + "]."
                );
            }

            BigDecimal scaledNet = net.setScale(profile.precisionScale());
            return ScaleReadingResult.accepted(gross, tare, scaledNet, profile.expectedUnit());
        } catch (NumberFormatException | ArithmeticException ex) {
            return ScaleReadingResult.rejected(
                ScaleReadingResult.RejectionReason.MALFORMED_FRAME,
                "Invalid decimal precision or non-numeric weight token in scale frame."
            );
        }
    }

    /**
     * Activates a barcode scanner profile and enforces unframed keyboard-wedge global character
     * shortcut suppression across active screens and modals (Doc 28 §7, Doc 29, AT09-DEV-003-B).
     */
    public synchronized void activateBarcodeWorkflow(
        BarcodeScannerProfile profile,
        DeviceAuthorityContext authority
    ) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(authority, "authority must not be null");
        profile.assertAdmitted();
        authority.assertCanCaptureDeviceInput(authority.authorizedCompanyId(), "device.barcodeCapture");

        this.activeBarcodeProfile = profile;
        this.lastAcceptedBarcode = null;
        boolean suppressCharacterShortcuts =
            profile.isolationMode() == BarcodeScannerProfile.IsolationMode.UNFRAMED_KEYBOARD_WEDGE;
        commandRegistry.setUnframedWedgeScannerActive(suppressCharacterShortcuts);
    }

    public synchronized void deactivateBarcodeWorkflow() {
        this.activeBarcodeProfile = null;
        this.lastAcceptedBarcode = null;
        commandRegistry.setUnframedWedgeScannerActive(false);
    }

    public synchronized BarcodeCaptureResult captureBarcode(
        DeviceAuthorityContext authority,
        String rawScanInput,
        String targetFieldId
    ) {
        Objects.requireNonNull(authority, "authority must not be null");
        if (activeBarcodeProfile == null) {
            throw new DeviceSecurityException(
                "MC-BARCODE-WORKFLOW-INACTIVE",
                "Activate an admitted BarcodeScannerProfile before capturing scan input."
            );
        }
        authority.assertCanCaptureDeviceInput(authority.authorizedCompanyId(), "device.barcodeCapture");

        if (rawScanInput == null || rawScanInput.isEmpty() || targetFieldId == null || targetFieldId.isBlank()) {
            return BarcodeCaptureResult.rejected(
                BarcodeCaptureResult.RejectionReason.EMPTY_OR_FRAMING_MISMATCH,
                "Scan input or designated target field was empty."
            );
        }

        String prefix = activeBarcodeProfile.expectedPrefix();
        String suffix = activeBarcodeProfile.expectedSuffix();
        if (!rawScanInput.startsWith(prefix) || !rawScanInput.endsWith(suffix)
            || rawScanInput.length() < prefix.length() + suffix.length()) {
            return BarcodeCaptureResult.rejected(
                BarcodeCaptureResult.RejectionReason.EMPTY_OR_FRAMING_MISMATCH,
                "Scan input did not match profile prefix/suffix framing."
            );
        }

        String extracted = rawScanInput.substring(prefix.length(), rawScanInput.length() - suffix.length());
        for (int i = 0; i < extracted.length(); i++) {
            char ch = extracted.charAt(i);
            if (ch <= 0x1F || ch == 0x7F || (ch >= 0x80 && ch <= 0x9F)) {
                return BarcodeCaptureResult.rejected(
                    BarcodeCaptureResult.RejectionReason.CONTROL_CHARACTER_INJECTION,
                    "Rejected control character 0x" + String.format("%02X", (int) ch) + " inside scanned payload."
                );
            }
        }

        if (extracted.length() < activeBarcodeProfile.minIdentifierLength()
            || extracted.length() > activeBarcodeProfile.maxIdentifierLength()) {
            return BarcodeCaptureResult.rejected(
                BarcodeCaptureResult.RejectionReason.LENGTH_OUT_OF_BOUNDS,
                "Scanned identifier length " + extracted.length() + " outside profile bounds."
            );
        }

        if (activeBarcodeProfile.duplicateScanPolicy()
                == BarcodeScannerProfile.DuplicateScanPolicy.REJECT_CONSECUTIVE_DUPLICATE
            && extracted.equals(lastAcceptedBarcode)) {
            return BarcodeCaptureResult.rejected(
                BarcodeCaptureResult.RejectionReason.CONSECUTIVE_DUPLICATE_SCAN,
                "Rejected consecutive duplicate barcode scan under profile policy."
            );
        }

        // Preserve exact string verbatim (including all leading zeros such as "0008901234567")
        this.lastAcceptedBarcode = extracted;
        return BarcodeCaptureResult.accepted(extracted, targetFieldId);
    }
}
