package com.raster.moneycraft.desktop.auth;

/**
 * Explicit fail-closed exception thrown when the host credential vault is unavailable (AT09-SWG-002-B).
 */
public final class VaultUnavailableException extends NativeAuthSecurityException {

    public VaultUnavailableException(String message) {
        super("MC-VAULT-UNAVAILABLE", message);
    }

    public VaultUnavailableException(String message, Throwable cause) {
        super("MC-VAULT-UNAVAILABLE", message, cause);
    }
}
