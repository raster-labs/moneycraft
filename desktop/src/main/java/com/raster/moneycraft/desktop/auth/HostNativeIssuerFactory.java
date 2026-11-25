package com.raster.moneycraft.desktop.auth;

import com.raster.moneycraft.desktop.client.DesktopJcsCanonicalizer;
import com.raster.moneycraft.desktop.security.EnrolledHostTrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * Binds Swing public-client PKCE authentication to MoneyCraft Host v1.1.0's first-party
 * native OAuth2/PKCE issuer ({@code /api/v1/auth/native/authorize}, {@code /token}, {@code /revoke})
 * supporting both LOCAL password/passkey and federated OIDC users via external browser (ITER110-024).
 */
public final class HostNativeIssuerFactory {

    public static final String SWING_PUBLIC_CLIENT_ID = "moneycraft-swing-desktop";
    public static final String DEFAULT_NATIVE_AUDIENCE = "moneycraft-api";

    private HostNativeIssuerFactory() {
    }

    public static AdmittedNativeIssuerProfile forHostOrigin(
        URI hostOrigin,
        boolean allowLoopbackHttpForTesting
    ) {
        return forHostOriginWithPublicKey(hostOrigin, null, allowLoopbackHttpForTesting);
    }

    public static AdmittedNativeIssuerProfile forHostOriginWithPublicKey(
        URI hostOrigin,
        PublicKey verificationKey,
        boolean allowLoopbackHttpForTesting
    ) {
        Objects.requireNonNull(hostOrigin, "hostOrigin must not be null");
        URI normalized = EnrolledHostTrustManager.normalizeOrigin(hostOrigin);
        String base = normalized.toString();
        return new AdmittedNativeIssuerProfile(
            "moneycraft-host-native",
            AdmittedNativeIssuerProfile.IssuerCategory.LOCAL_INSTALLATION_ISSUER,
            normalized,
            URI.create(base + "/api/v1/auth/native/authorize"),
            URI.create(base + "/api/v1/auth/native/token"),
            URI.create(base + "/api/v1/auth/native/revoke"),
            SWING_PUBLIC_CLIENT_ID,
            DEFAULT_NATIVE_AUDIENCE,
            List.of("openid", "profile"),
            verificationKey,
            allowLoopbackHttpForTesting
        );
    }

    /**
     * Queries the host's {@code /api/v1/auth/native/.well-known/openid-configuration} endpoint
     * to bind the live EC P-256 ({@code ES256}) verification public key into the issuer profile.
     */
    public static AdmittedNativeIssuerProfile discoverFromHostOrigin(
        URI hostOrigin,
        HttpClient httpClient,
        boolean allowLoopbackHttpForTesting
    ) {
        Objects.requireNonNull(hostOrigin, "hostOrigin must not be null");
        Objects.requireNonNull(httpClient, "httpClient must not be null");
        URI normalized = EnrolledHostTrustManager.normalizeOrigin(hostOrigin);
        URI discoveryUri = URI.create(normalized + "/api/v1/auth/native/.well-known/openid-configuration");
        HttpRequest req = HttpRequest.newBuilder(discoveryUri)
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .GET()
            .build();
        try {
            HttpResponse<String> response = httpClient.send(
                req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() != 200) {
                return forHostOrigin(normalized, allowLoopbackHttpForTesting);
            }
            JsonNode root = new DesktopJcsCanonicalizer().wireMapper().readTree(response.body());
            JsonNode pemNode = root.get("public_key_pem");
            PublicKey key = pemNode != null && !pemNode.isNull() && !pemNode.asString("").isBlank()
                ? parsePublicKeyPem(pemNode.asString())
                : null;
            return forHostOriginWithPublicKey(normalized, key, allowLoopbackHttpForTesting);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return forHostOrigin(normalized, allowLoopbackHttpForTesting);
        } catch (IOException ex) {
            return forHostOrigin(normalized, allowLoopbackHttpForTesting);
        }
    }

    public static PublicKey parsePublicKeyPem(String publicKeyPem) {
        Objects.requireNonNull(publicKeyPem, "publicKeyPem must not be null");
        String stripped = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(stripped);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        try {
            return KeyFactory.getInstance("EC").generatePublic(spec);
        } catch (GeneralSecurityException ecEx) {
            try {
                return KeyFactory.getInstance("RSA").generatePublic(spec);
            } catch (GeneralSecurityException rsaEx) {
                throw new NativeAuthSecurityException(
                    "MC-JWKS-KEY-INVALID",
                    "Unable to parse host native OAuth public key PEM.",
                    rsaEx
                );
            }
        }
    }
}
