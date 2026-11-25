package com.raster.moneycraft.desktop.client;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.X509TrustManager;

/**
 * Validated configuration for {@link DesktopApiClient} enforcing HTTPS transport,
 * endpoint hostname verification, and zero database connection configuration (SWG-001, SWG-002).
 */
public record DesktopClientConfig(
    URI baseApiUri,
    Duration connectTimeout,
    Duration requestTimeout,
    SSLContext sslContext,
    boolean allowLoopbackHttpForTesting,
    boolean rejectServiceApiKeys
) {

    public DesktopClientConfig {
        Objects.requireNonNull(baseApiUri, "baseApiUri must not be null");
        Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
        Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");

        String rawUri = baseApiUri.toString();
        String lowerUri = rawUri.toLowerCase(Locale.ROOT);
        if (lowerUri.startsWith("jdbc:")
            || lowerUri.startsWith("postgresql:")
            || lowerUri.startsWith("postgres:")
            || lowerUri.contains("password=")
            || baseApiUri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                "SWG-001 violation: desktop client configuration must never contain database addresses or credentials."
            );
        }

        String scheme = baseApiUri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("baseApiUri must specify https scheme.");
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if ("https".equals(normalizedScheme)) {
            if (baseApiUri.getHost() == null || baseApiUri.getHost().isBlank()) {
                throw new IllegalArgumentException("HTTPS baseApiUri must specify a valid server host.");
            }
        } else if ("http".equals(normalizedScheme)) {
            if (!allowLoopbackHttpForTesting || !isLoopbackHost(baseApiUri.getHost())) {
                throw new IllegalArgumentException(
                    "SWG-002 violation: clear-text HTTP is prohibited for remote MoneyCraft server traffic."
                );
            }
        } else {
            throw new IllegalArgumentException("Unsupported URI scheme for MoneyCraft API: " + scheme);
        }

        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive.");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive.");
        }
    }

    public static DesktopClientConfig httpsDefault(URI baseApiUri) {
        return new DesktopClientConfig(
            baseApiUri,
            Duration.ofSeconds(5),
            Duration.ofSeconds(15),
            null,
            false,
            true
        );
    }

    public static DesktopClientConfig loopbackTestConfig(URI loopbackApiUri) {
        return new DesktopClientConfig(
            loopbackApiUri,
            Duration.ofSeconds(3),
            Duration.ofSeconds(10),
            null,
            true,
            true
        );
    }

    /**
     * Verifies that a supplied TrustManager is not a permissive trust-all implementation (SWG-002).
     */
    public static void assertNotTrustAll(X509TrustManager trustManager) {
        Objects.requireNonNull(trustManager, "trustManager must not be null");
        try {
            trustManager.checkServerTrusted(new java.security.cert.X509Certificate[0], "RSA");
            throw new IllegalArgumentException(
                "SWG-002 violation: trust-all X509TrustManager accepted an empty certificate chain."
            );
        } catch (java.security.cert.CertificateException | IllegalArgumentException expected) {
            if (expected instanceof IllegalArgumentException
                && expected.getMessage() != null
                && expected.getMessage().startsWith("SWG-002 violation:")) {
                throw (IllegalArgumentException) expected;
            }
            // Proper X509TrustManager rejected empty chain as required.
        }
    }

    public SSLParameters verifiedSslParameters() {
        SSLParameters params = new SSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        return params;
    }

    public static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String trimmed = host.trim().toLowerCase(Locale.ROOT);
        if ("127.0.0.1".equals(trimmed) || "::1".equals(trimmed) || "[::1]".equals(trimmed) || "localhost".equals(trimmed)) {
            return true;
        }
        try {
            return InetAddress.getByName(trimmed).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }
}
