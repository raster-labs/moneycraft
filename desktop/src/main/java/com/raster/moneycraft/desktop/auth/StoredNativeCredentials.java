package com.raster.moneycraft.desktop.auth;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Redacted credential holder for an authenticated native PERSON session (SWG-002, DEV-004).
 */
public record StoredNativeCredentials(
    String issuerUri,
    String subject,
    String audience,
    String accessToken,
    String refreshToken,
    Instant expiresAt,
    List<String> scopes
) {
    public StoredNativeCredentials {
        Objects.requireNonNull(issuerUri, "issuerUri must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(audience, "audience must not be null");
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (accessToken.isBlank()) {
            throw new NativeAuthSecurityException("MC-TOKEN-EMPTY", "Access token must not be blank.");
        }
        if (accessToken.startsWith("mc_sk_")) {
            throw new NativeAuthSecurityException(
                "MC-NATIVE-SERVICE-KEY-FORBIDDEN",
                "Service API keys are prohibited inside interactive native PERSON credential storage."
            );
        }
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    @Override
    public String toString() {
        return "StoredNativeCredentials[issuerUri=" + issuerUri
            + ", subject=" + subject
            + ", audience=" + audience
            + ", accessToken=[REDACTED]"
            + ", refreshToken=" + (refreshToken == null ? "null" : "[REDACTED]")
            + ", expiresAt=" + expiresAt
            + ", scopes=" + scopes + "]";
    }
}
