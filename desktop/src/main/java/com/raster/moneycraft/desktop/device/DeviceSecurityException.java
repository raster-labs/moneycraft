package com.raster.moneycraft.desktop.device;

/**
 * Fail-closed security, capability, or protocol exception for local device adapters (DEV-001..004).
 */
public class DeviceSecurityException extends RuntimeException {

    private final String errorCode;

    public DeviceSecurityException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode == null ? "MC-DEVICE-ERROR" : errorCode;
    }

    public DeviceSecurityException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode == null ? "MC-DEVICE-ERROR" : errorCode;
    }

    public final String errorCode() {
        return errorCode;
    }
}
