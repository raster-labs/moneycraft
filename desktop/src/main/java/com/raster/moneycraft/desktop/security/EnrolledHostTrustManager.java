package com.raster.moneycraft.desktop.security;

import com.raster.moneycraft.desktop.auth.NativeAuthSecurityException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Private-LAN and enterprise TLS trust manager for MoneyCraft Swing Desktop (ITER110-026, S-07, S-08).
 * Enrols an administrator-provisioned CA PEM anchor only after verifying its out-of-band SHA-256
 * DER fingerprint, enforces RFC 9525 HTTPS endpoint hostname identification, rejects untrusted
 * roots supplied by failing endpoints, and records CA rotation and removal events.
 */
public final class EnrolledHostTrustManager {

    private final URI canonicalHostOrigin;
    private final List<TrustAuditEvent> auditEvents = new ArrayList<>();
    private volatile String enrolledTrustProfileId;
    private volatile String enrolledCaFingerprintSha256;
    private volatile SSLContext activeSslContext;
    private volatile X509TrustManager activeX509TrustManager;

    public EnrolledHostTrustManager(URI canonicalHostOrigin) {
        Objects.requireNonNull(canonicalHostOrigin, "canonicalHostOrigin must not be null");
        if (!"https".equalsIgnoreCase(canonicalHostOrigin.getScheme())
            || canonicalHostOrigin.getHost() == null
            || canonicalHostOrigin.getHost().isBlank()) {
            throw new NativeAuthSecurityException(
                "MC-TLS-CANONICAL-HTTPS-REQUIRED",
                "Enrolled host origin must use https:// with a non-blank DNS or qualified IP host."
            );
        }
        this.canonicalHostOrigin = normalizeOrigin(canonicalHostOrigin);
        installSystemDefaultTrust("SYSTEM_DEFAULT_PKIX");
    }

    public URI canonicalHostOrigin() {
        return canonicalHostOrigin;
    }

    public String enrolledTrustProfileId() {
        return enrolledTrustProfileId;
    }

    public String enrolledCaFingerprintSha256() {
        return enrolledCaFingerprintSha256;
    }

    public SSLContext activeSslContext() {
        return activeSslContext;
    }

    public X509TrustManager activeX509TrustManager() {
        return activeX509TrustManager;
    }

    public SSLParameters strictHttpsParameters() {
        SSLParameters params = new SSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        params.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        return params;
    }

    public synchronized List<TrustAuditEvent> auditEvents() {
        return Collections.unmodifiableList(new ArrayList<>(auditEvents));
    }

    /**
     * Enrols an administrator-provisioned private-LAN CA PEM certificate after verifying its exact
     * SHA-256 DER fingerprint out-of-band (S-08, ITER110-026).
     */
    public synchronized void enrollProvisionedPrivateCa(
        String trustProfileId,
        String caCertificatePem,
        String expectedFingerprintSha256Hex,
        String administratorReference
    ) {
        Objects.requireNonNull(trustProfileId, "trustProfileId must not be null");
        Objects.requireNonNull(caCertificatePem, "caCertificatePem must not be null");
        Objects.requireNonNull(expectedFingerprintSha256Hex, "expectedFingerprintSha256Hex must not be null");
        Objects.requireNonNull(administratorReference, "administratorReference must not be null");
        if (trustProfileId.isBlank() || administratorReference.isBlank()) {
            throw new NativeAuthSecurityException(
                "MC-TLS-TRUST-PROFILE-INVALID",
                "trustProfileId and administratorReference are required for private-CA enrolment."
            );
        }

        X509Certificate caCert = parseAndValidateCaPem(caCertificatePem);
        String actualSha256 = sha256DerHex(caCert);
        String normalizedExpected = normalizeFingerprint(expectedFingerprintSha256Hex);
        if (!MessageDigest.isEqual(
            actualSha256.getBytes(StandardCharsets.US_ASCII),
            normalizedExpected.getBytes(StandardCharsets.US_ASCII)
        )) {
            throw new NativeAuthSecurityException(
                "MC-TLS-CA-FINGERPRINT-MISMATCH",
                "Provisioned CA SHA-256 fingerprint (" + actualSha256
                    + ") did not match administrator-verified fingerprint (" + normalizedExpected + ")."
            );
        }

        buildScopedSslContext(trustProfileId.trim(), caCert, actualSha256);
        auditEvents.add(new TrustAuditEvent(
            "ENROLLED_PRIVATE_CA",
            trustProfileId.trim(),
            actualSha256,
            administratorReference.trim(),
            Instant.now()
        ));
    }

    /**
     * Rotates the enrolled private-LAN CA to a newly verified anchor and invalidates the prior root.
     */
    public synchronized void rotateProvisionedPrivateCa(
        String nextTrustProfileId,
        String nextCaCertificatePem,
        String expectedNextFingerprintSha256Hex,
        String administratorReference
    ) {
        enrollProvisionedPrivateCa(
            nextTrustProfileId,
            nextCaCertificatePem,
            expectedNextFingerprintSha256Hex,
            administratorReference
        );
        auditEvents.add(new TrustAuditEvent(
            "ROTATED_PRIVATE_CA",
            nextTrustProfileId.trim(),
            enrolledCaFingerprintSha256,
            administratorReference.trim(),
            Instant.now()
        ));
    }

    /**
     * Removes a private-LAN CA trust profile and reverts strictly to JVM default PKIX roots.
     */
    public synchronized void removeProvisionedPrivateCa(String administratorReference) {
        Objects.requireNonNull(administratorReference, "administratorReference must not be null");
        String removedFingerprint = this.enrolledCaFingerprintSha256;
        installSystemDefaultTrust("SYSTEM_DEFAULT_PKIX");
        auditEvents.add(new TrustAuditEvent(
            "REMOVED_PRIVATE_CA",
            "SYSTEM_DEFAULT_PKIX",
            removedFingerprint,
            administratorReference.trim(),
            Instant.now()
        ));
    }

    /**
     * Explicitly refuses to learn or install a CA certificate presented by a failing TLS endpoint (S-07, S-08).
     */
    public static void rejectUntrustedEndpointSuppliedRoot(URI failingEndpoint) {
        throw new NativeAuthSecurityException(
            "MC-TLS-TOFU-FORBIDDEN",
            "Refusing to trust certificate anchor supplied by failing endpoint '" + failingEndpoint
                + "'; private-LAN CA roots must be provisioned and fingerprint-verified out of band."
        );
    }

    /**
     * Verifies that a target request URI matches the enrolled canonical HTTPS origin before
     * attaching credentials or sending business payloads (S-07, ITER110-026).
     */
    public void assertMatchesEnrolledOrigin(URI candidateTargetUri) {
        Objects.requireNonNull(candidateTargetUri, "candidateTargetUri must not be null");
        if (!"https".equalsIgnoreCase(candidateTargetUri.getScheme())) {
            throw new NativeAuthSecurityException(
                "MC-TLS-DOWNGRADE-BLOCKED",
                "Blocked non-HTTPS downgrade or redirect target: " + candidateTargetUri
            );
        }
        URI normalizedCandidate = normalizeOrigin(candidateTargetUri);
        if (!canonicalHostOrigin.equals(normalizedCandidate)) {
            throw new NativeAuthSecurityException(
                "MC-TLS-CROSS-ORIGIN-BLOCKED",
                "Target origin '" + normalizedCandidate + "' does not match enrolled host '"
                    + canonicalHostOrigin + "'; withholding credentials and payload."
            );
        }
    }

    public static String computeCertificateSha256Hex(X509Certificate certificate) {
        return sha256DerHex(Objects.requireNonNull(certificate, "certificate must not be null"));
    }

    private void installSystemDefaultTrust(String profileId) {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
            tmf.init((KeyStore) null);
            X509TrustManager x509 = extractX509TrustManager(tmf.getTrustManagers());
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{x509}, null);
            this.enrolledTrustProfileId = profileId;
            this.enrolledCaFingerprintSha256 = null;
            this.activeX509TrustManager = x509;
            this.activeSslContext = ctx;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed initializing default PKIX SSLContext.", ex);
        }
    }

    private void buildScopedSslContext(String profileId, X509Certificate caCert, String sha256Hex) {
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("mc-private-lan-ca", caCert);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
            tmf.init(ks);
            X509TrustManager x509 = extractX509TrustManager(tmf.getTrustManagers());

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{x509}, null);

            this.enrolledTrustProfileId = profileId;
            this.enrolledCaFingerprintSha256 = sha256Hex;
            this.activeX509TrustManager = x509;
            this.activeSslContext = ctx;
        } catch (IOException | GeneralSecurityException ex) {
            throw new NativeAuthSecurityException(
                "MC-TLS-KEYSTORE-INIT-FAILED",
                "Failed building scoped PKIX trust store for private-LAN CA.",
                ex
            );
        }
    }

    private static X509Certificate parseAndValidateCaPem(String pem) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII))
            );
            cert.checkValidity();
            return cert;
        } catch (GeneralSecurityException ex) {
            throw new NativeAuthSecurityException(
                "MC-TLS-CA-CERT-INVALID",
                "Provisioned CA certificate is malformed, expired, or not yet valid.",
                ex
            );
        }
    }

    private static X509TrustManager extractX509TrustManager(TrustManager[] managers) {
        for (TrustManager tm : managers) {
            if (tm instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException("PKIX TrustManagerFactory produced no X509TrustManager.");
    }

    private static String sha256DerHex(X509Certificate cert) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(cert.getEncoded()));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed computing SHA-256 certificate fingerprint.", ex);
        }
    }

    private static String normalizeFingerprint(String hexOrColon) {
        String cleaned = hexOrColon.replace(":", "").replace(" ", "").trim().toLowerCase(Locale.ROOT);
        if (cleaned.length() != 64) {
            throw new NativeAuthSecurityException(
                "MC-TLS-FINGERPRINT-FORMAT",
                "Expected 64-character SHA-256 hex fingerprint."
            );
        }
        return cleaned;
    }

    public static URI normalizeOrigin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        String authority = (port <= 0 || defaultPort) ? host : host + ":" + port;
        return URI.create(scheme + "://" + authority);
    }

    public record TrustAuditEvent(
        String action,
        String trustProfileId,
        String caSha256Hex,
        String administratorReference,
        Instant occurredAt
    ) {
    }
}
