package com.raster.moneycraft.desktop.auth;

import com.raster.moneycraft.desktop.security.EnrolledHostTrustManager;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Strictly process-memory-only access token holder bound to a single enrolled host origin,
 * issuer, client registration, and authenticated person subject (Swing Doc 02 §5, S-06, AT-ITER110-024/025).
 *
 * <p>Short-lived access tokens are never written to disk, preferences, logs, or OS vaults,
 * and are never transmitted across host origin changes.
 */
public final class InMemoryAccessTokenHolder {

    private final Clock clock;
    private volatile ScopedMemoryToken current;

    public InMemoryAccessTokenHolder() {
        this(Clock.systemUTC());
    }

    public InMemoryAccessTokenHolder(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void storeInMemory(
        URI hostOrigin,
        String issuerUri,
        String clientId,
        String subject,
        String accessToken,
        String companionIdTokenOrNull,
        Instant expiresAt
    ) {
        Objects.requireNonNull(hostOrigin, "hostOrigin must not be null");
        Objects.requireNonNull(issuerUri, "issuerUri must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");

        if (accessToken.isBlank()) {
            throw new NativeAuthSecurityException("MC-TOKEN-EMPTY", "Access token must not be blank.");
        }
        if (accessToken.startsWith("mc_sk_")) {
            throw new NativeAuthSecurityException(
                "MC-NATIVE-SERVICE-KEY-FORBIDDEN",
                "Service API keys are prohibited in interactive Swing sessions."
            );
        }
        if (companionIdTokenOrNull != null && companionIdTokenOrNull.equals(accessToken)) {
            throw new NativeAuthSecurityException(
                "MC-IDTOKEN-AS-ACCESSTOKEN-FORBIDDEN",
                "OIDC id_token must never be used as an API bearer access_token (M-S04)."
            );
        }

        URI normalizedOrigin = EnrolledHostTrustManager.normalizeOrigin(hostOrigin);
        this.current = new ScopedMemoryToken(
            normalizedOrigin,
            issuerUri,
            clientId,
            subject,
            accessToken,
            expiresAt
        );
    }

    public Optional<String> accessTokenForOrigin(URI targetUri) {
        return tokenSnapshotForOrigin(targetUri).map(ScopedMemoryToken::accessToken);
    }

    public Optional<ScopedMemoryToken> tokenSnapshotForOrigin(URI targetUri) {
        Objects.requireNonNull(targetUri, "targetUri must not be null");
        ScopedMemoryToken snapshot = this.current;
        if (snapshot == null) {
            return Optional.empty();
        }
        URI normalizedTarget = EnrolledHostTrustManager.normalizeOrigin(targetUri);
        if (!snapshot.hostOrigin().equals(normalizedTarget)) {
            throw new NativeAuthSecurityException(
                "MC-CROSS-HOST-TOKEN-REUSE-BLOCKED",
                "Refusing to send access token bound to '" + snapshot.hostOrigin()
                    + "' toward different host '" + normalizedTarget + "'."
            );
        }
        if (!clock.instant().isBefore(snapshot.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    public Optional<String> currentSubject() {
        ScopedMemoryToken snapshot = this.current;
        if (snapshot == null || !clock.instant().isBefore(snapshot.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(snapshot.subject());
    }

    public void clear() {
        this.current = null;
    }

    /**
     * Enforces AT-ITER110-025: local OS vault or biometric unlock never manufactures server
     * authentication or satisfies a server-side 5-minute step-up challenge.
     */
    public static void assertLocalUnlockCannotSatisfyServerStepUp() {
        throw new NativeAuthSecurityException(
            "MC-SERVER-STEP-UP-REQUIRED",
            "Local credential-store or biometric unlock is device-local only and cannot satisfy "
                + "a server-verified recent-authentication or step-up intent (AT-ITER110-025)."
        );
    }

    @Override
    public String toString() {
        ScopedMemoryToken snapshot = this.current;
        return snapshot == null
            ? "InMemoryAccessTokenHolder[empty]"
            : "InMemoryAccessTokenHolder[origin=" + snapshot.hostOrigin()
                + ", subject=" + snapshot.subject() + ", token=[REDACTED]]";
    }

    public record ScopedMemoryToken(
        URI hostOrigin,
        String issuerUri,
        String clientId,
        String subject,
        String accessToken,
        Instant expiresAt
    ) {
    }
}
