package com.raster.moneycraft.desktop.auth;

/**
 * Thrown when an OAuth 2.0 / OIDC + PKCE S256 native sign-in security invariant fails (SWG-002).
 */
public class NativeAuthSecurityException extends RuntimeException {

    private final String errorCode;

    public NativeAuthSecurityException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode == null ? "MC-NATIVE-AUTH-ERROR" : errorCode;
    }

    public NativeAuthSecurityException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode == null ? "MC-NATIVE-AUTH-ERROR" : errorCode;
    }

    public final String errorCode() {
        return errorCode;
    }
}
