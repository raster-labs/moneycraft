package com.raster.moneycraft.desktop.device;

/**
 * Thrown when untrusted customer/imported text contains forbidden printer control bytes (0x00-0x1F,
 * ESC 0x1B, FS 0x1C, GS 0x1D, DEL 0x7F, or C1 controls) or violates geometry constraints (AT09-DEV-001-C).
 */
public final class RawPrintCompilationException extends DeviceSecurityException {

    public RawPrintCompilationException(String errorCode, String message) {
        super(errorCode, message);
    }
}
