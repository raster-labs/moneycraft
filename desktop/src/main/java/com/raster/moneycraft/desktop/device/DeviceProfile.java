package com.raster.moneycraft.desktop.device;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Admitted local printer/label device capability profile (DEV-001, DEV-004).
 * Records manufacturer, model, firmware, OS/arch, transport, command language, code page,
 * paper/label geometry, barcode symbologies, explicit glyph rules, and completion evidence capability.
 */
public record DeviceProfile(
    String profileId,
    String profileVersion,
    boolean admitted,
    String manufacturer,
    String model,
    String firmwareVersion,
    String hostOs,
    String hostArchitecture,
    TransportMode transportMode,
    CommandLanguage commandLanguage,
    String charsetName,
    int escPosCodePageByte,
    int paperWidthMm,
    int columnsPerLine,
    int linesPerPage,
    int quietZoneDots,
    Set<String> supportedBarcodeSymbologies,
    Set<Character> customMappedGlyphs,
    Optional<String> approvedCurrencyFallbackToken,
    boolean cutterPermitted,
    boolean cashDrawerKickPermitted,
    CompletionEvidenceCapability completionEvidenceCapability
) {

    private static final Pattern SAFE_DESTINATION_PATTERN = Pattern.compile("^[A-Za-z0-9._:\\- ]{1,80}$");

    public enum TransportMode {
        OS_PRINT_DRIVER,
        USB_RAW_PORT,
        SERIAL_RS232,
        LAN_RAW_SOCKET
    }

    public enum CommandLanguage {
        OS_PAGE_PDF,
        EPSON_ESC_POS,
        DOT_MATRIX_ESC_P,
        BARCODE_LABEL_TSPL
    }

    public enum CompletionEvidenceCapability {
        NONE,
        SPOOLER_DATA_TRANSFERRED_ONLY,
        PRINTER_HARDWARE_CONFIRMED
    }

    public DeviceProfile {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(profileVersion, "profileVersion must not be null");
        Objects.requireNonNull(manufacturer, "manufacturer must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(firmwareVersion, "firmwareVersion must not be null");
        Objects.requireNonNull(hostOs, "hostOs must not be null");
        Objects.requireNonNull(hostArchitecture, "hostArchitecture must not be null");
        Objects.requireNonNull(transportMode, "transportMode must not be null");
        Objects.requireNonNull(commandLanguage, "commandLanguage must not be null");
        Objects.requireNonNull(charsetName, "charsetName must not be null");
        Objects.requireNonNull(approvedCurrencyFallbackToken, "approvedCurrencyFallbackToken must not be null");
        Objects.requireNonNull(completionEvidenceCapability, "completionEvidenceCapability must not be null");

        if (profileId.isBlank() || manufacturer.isBlank() || model.isBlank() || firmwareVersion.isBlank()) {
            throw new IllegalArgumentException("DeviceProfile identity fields must not be blank.");
        }
        if (columnsPerLine < 16 || paperWidthMm <= 0) {
            throw new IllegalArgumentException("DeviceProfile paperWidthMm and columnsPerLine must be positive.");
        }
        supportedBarcodeSymbologies = supportedBarcodeSymbologies == null
            ? Set.of()
            : Set.copyOf(supportedBarcodeSymbologies);
        customMappedGlyphs = customMappedGlyphs == null
            ? Set.of()
            : Set.copyOf(customMappedGlyphs);
    }

    public void assertAdmitted() {
        if (!admitted) {
            throw new DeviceSecurityException(
                "MC-DEVICE-PROFILE-UNADMITTED",
                "Device profile '" + profileId + "' (" + manufacturer + " " + model
                    + ") is not admitted for production output (DEV-004)."
            );
        }
    }

    public static void validateSafeLocalDestination(String destination) {
        Objects.requireNonNull(destination, "destination must not be null");
        if (destination.isBlank()
            || destination.contains("..")
            || destination.contains("/")
            || destination.contains("\\")
            || destination.contains(";")
            || destination.contains("|")
            || destination.contains("$")
            || destination.contains("`")
            || !SAFE_DESTINATION_PATTERN.matcher(destination).matches()) {
            throw new DeviceSecurityException(
                "MC-DEVICE-UNSAFE-DESTINATION",
                "Rejected arbitrary host path, remote endpoint, or shell token in destination: " + destination
            );
        }
    }
}
