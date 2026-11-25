package com.raster.moneycraft.desktop.device;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Closed permission and actor boundary for local print and peripheral operations (Doc 28 §4, DEV-004).
 * Device configuration authority, service API keys, and extensions never confer interactive business output rights.
 */
public record DeviceAuthorityContext(
    ActorKind actorKind,
    UUID actorPrincipalId,
    UUID authorizedCompanyId,
    boolean sessionActive,
    Set<String> grantedPermissions
) {

    private static final Set<String> CLOSED_PERMISSION_REGISTRY = Set.of(
        "company.read",
        "master.read",
        "voucher.read",
        "voucher.create",
        "voucher.approve",
        "voucher.post",
        "report.read",
        "export",
        "privateField.read",
        "device.configure",
        "device.cutter",
        "device.cashDrawer",
        "device.scaleCapture",
        "device.barcodeCapture"
    );

    public enum ActorKind {
        INTERACTIVE_PERSON,
        DEVICE_ADMINISTRATOR_CONFIG_ONLY,
        INTEGRATION_SERVICE_KEY,
        EXTENSION_AUTHOR_SANDBOX
    }

    public DeviceAuthorityContext {
        Objects.requireNonNull(actorKind, "actorKind must not be null");
        Objects.requireNonNull(actorPrincipalId, "actorPrincipalId must not be null");
        grantedPermissions = grantedPermissions == null ? Set.of() : Set.copyOf(grantedPermissions);
        for (String perm : grantedPermissions) {
            if (!CLOSED_PERMISSION_REGISTRY.contains(perm)) {
                throw new DeviceSecurityException(
                    "MC-DEVICE-UNKNOWN-PERMISSION",
                    "Unknown permission '" + perm + "' rejected by closed permission registry (DEV-004)."
                );
            }
        }
    }

    public boolean hasClosedPermission(String permission) {
        if (permission == null || !CLOSED_PERMISSION_REGISTRY.contains(permission)) {
            return false;
        }
        return sessionActive && grantedPermissions.contains(permission);
    }

    public void assertCanOutputDocument(UUID targetCompanyId, boolean containsSelectedPrivateFields) {
        Objects.requireNonNull(targetCompanyId, "targetCompanyId must not be null");
        if (!sessionActive) {
            throw new DeviceSecurityException(
                "MC-DEVICE-AUTH-REVOKED",
                "Interactive session is inactive or logged out; refusing local output release."
            );
        }
        if (actorKind != ActorKind.INTERACTIVE_PERSON) {
            throw new DeviceSecurityException(
                "MC-DEVICE-ACTOR-FORBIDDEN",
                "Actor kind '" + actorKind + "' cannot release business print output (Doc 28 §4)."
            );
        }
        if (authorizedCompanyId == null || !authorizedCompanyId.equals(targetCompanyId)) {
            throw new DeviceSecurityException(
                "MC-DEVICE-COMPANY-MISMATCH",
                "Current active company authority does not match target document company."
            );
        }
        if (!hasClosedPermission("company.read") || !hasClosedPermission("export")) {
            throw new DeviceSecurityException(
                "MC-DEVICE-EXPORT-DENIED",
                "Current person lacks required 'company.read' and 'export' permissions."
            );
        }
        if (containsSelectedPrivateFields && !hasClosedPermission("privateField.read")) {
            throw new DeviceSecurityException(
                "MC-DEVICE-PRIVATE-FIELD-DENIED",
                "Document contains protected private fields and caller lacks 'privateField.read'."
            );
        }
    }

    public void assertCanCaptureDeviceInput(UUID targetCompanyId, String requiredDevicePermission) {
        Objects.requireNonNull(targetCompanyId, "targetCompanyId must not be null");
        if (!sessionActive || actorKind != ActorKind.INTERACTIVE_PERSON) {
            throw new DeviceSecurityException(
                "MC-DEVICE-CAPTURE-DENIED",
                "Interactive authenticated person context is required for peripheral capture."
            );
        }
        if (authorizedCompanyId == null || !authorizedCompanyId.equals(targetCompanyId)) {
            throw new DeviceSecurityException(
                "MC-DEVICE-COMPANY-MISMATCH",
                "Peripheral capture company mismatch."
            );
        }
        if (!hasClosedPermission("company.read") || !hasClosedPermission(requiredDevicePermission)) {
            throw new DeviceSecurityException(
                "MC-DEVICE-CAPTURE-PERMISSION-DENIED",
                "Caller lacks permission '" + requiredDevicePermission + "'."
            );
        }
    }
}
