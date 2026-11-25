package com.raster.moneycraft.desktop.auth;

import com.raster.moneycraft.desktop.client.DesktopClientConfig;
import java.net.URI;
import java.security.PublicKey;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Admitted native public OAuth 2.0 / OIDC issuer profile for Swing sign-in (SWG-002, RFC 8252, RFC 9700).
 * Forbids shared client secrets, service API keys, embedded web views, and clear-text remote endpoints.
 */
public record AdmittedNativeIssuerProfile(
    String issuerId,
    IssuerCategory category,
    URI issuerUri,
    URI authorizationEndpoint,
    URI tokenEndpoint,
    URI revocationEndpoint,
    String clientId,
    String expectedAudience,
    List<String> scopes,
    PublicKey jwtVerificationKey,
    boolean allowLoopbackHttpForTesting
) {

    public enum IssuerCategory {
        LOCAL_INSTALLATION_ISSUER,
        ORGANISATIONAL_SSO_ISSUER
    }

    public AdmittedNativeIssuerProfile {
        Objects.requireNonNull(issuerId, "issuerId must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(issuerUri, "issuerUri must not be null");
        Objects.requireNonNull(authorizationEndpoint, "authorizationEndpoint must not be null");
        Objects.requireNonNull(tokenEndpoint, "tokenEndpoint must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(expectedAudience, "expectedAudience must not be null");

        if (issuerId.isBlank() || clientId.isBlank() || expectedAudience.isBlank()) {
            throw new NativeAuthSecurityException(
                "MC-NATIVE-ISSUER-INVALID",
                "issuerId, clientId, and expectedAudience must be non-blank."
            );
        }
        if (clientId.startsWith("mc_sk_")) {
            throw new NativeAuthSecurityException(
                "MC-NATIVE-SERVICE-KEY-FORBIDDEN",
                "SWG-002 violation: native Swing client must never use a service API key as clientId."
            );
        }

        validateEndpointUri("issuerUri", issuerUri, allowLoopbackHttpForTesting);
        validateEndpointUri("authorizationEndpoint", authorizationEndpoint, allowLoopbackHttpForTesting);
        validateEndpointUri("tokenEndpoint", tokenEndpoint, allowLoopbackHttpForTesting);
        if (revocationEndpoint != null) {
            validateEndpointUri("revocationEndpoint", revocationEndpoint, allowLoopbackHttpForTesting);
        }

        scopes = scopes == null || scopes.isEmpty()
            ? List.of("openid", "profile")
            : List.copyOf(scopes);
    }

    public static void rejectSharedSecretOrPasswordFallback(String attemptedMechanism) {
        throw new NativeAuthSecurityException(
            "MC-NATIVE-UNSAFE-AUTH-MECHANISM",
            "SWG-002 / RFC 8252 violation: '" + attemptedMechanism
                + "' is forbidden in the public Swing client (no shared secret, embedded web view, or password grant)."
        );
    }

    private static void validateEndpointUri(String label, URI uri, boolean allowLoopbackHttp) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new NativeAuthSecurityException("MC-NATIVE-URI-INVALID", label + " must declare https scheme.");
        }
        String lower = scheme.toLowerCase(Locale.ROOT);
        if ("https".equals(lower)) {
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new NativeAuthSecurityException("MC-NATIVE-URI-INVALID", label + " host must not be blank.");
            }
            return;
        }
        if ("http".equals(lower) && allowLoopbackHttp && DesktopClientConfig.isLoopbackHost(uri.getHost())) {
            return;
        }
        throw new NativeAuthSecurityException(
            "MC-NATIVE-HTTPS-REQUIRED",
            "SWG-002 violation: " + label + " must use verified HTTPS (" + uri + ")."
        );
    }
}
