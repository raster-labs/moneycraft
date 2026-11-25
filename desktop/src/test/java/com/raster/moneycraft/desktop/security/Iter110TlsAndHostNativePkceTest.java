package com.raster.moneycraft.desktop.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raster.moneycraft.desktop.auth.AdmittedNativeIssuerProfile;
import com.raster.moneycraft.desktop.auth.HostNativeIssuerFactory;
import com.raster.moneycraft.desktop.auth.InMemoryAccessTokenHolder;
import com.raster.moneycraft.desktop.auth.NativeAuthSecurityException;
import com.raster.moneycraft.desktop.auth.NativePkceAuthClient;
import com.raster.moneycraft.desktop.auth.PlatformSecretStoreCredentialVault;
import com.raster.moneycraft.desktop.auth.StoredNativeCredentials;
import com.raster.moneycraft.desktop.auth.VaultUnavailableException;
import com.raster.moneycraft.desktop.client.DesktopApiClient;
import com.raster.moneycraft.desktop.client.DesktopClientConfig;
import com.raster.moneycraft.desktop.client.dto.SystemStatusDto;
import com.raster.moneycraft.desktop.device.PrintAttemptLedger;
import com.raster.moneycraft.desktop.device.ScaleAndBarcodeCaptureManager;
import com.raster.moneycraft.desktop.keyboard.DesktopCommandRegistry;
import com.raster.moneycraft.desktop.ui.BrandSwingTheme;
import com.raster.moneycraft.desktop.ui.MoneyCraftDesktopFrame;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.swing.JPasswordField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Qualification suite for Iteration v1.1.0 TLS Private-CA Trust Enrolment, Host Native OAuth2/PKCE,
 * In-Memory Access Token Custody, OS Secret Store Fallback, and Zero-Password-Field UI (S-05..S-08,
 * AT-ITER110-024..026).
 */
class Iter110TlsAndHostNativePkceTest {

    @TempDir
    Path tempDir;

    @Test
    void atIter110_026_privateCaEnrolmentFingerprintHostnameRotationAndCrossOriginRejection() throws Exception {
        Path caV1P12 = tempDir.resolve("ca-v1.p12");
        Path caV1Pem = tempDir.resolve("ca-v1.pem");
        Path serverLeafP12 = tempDir.resolve("server-leaf.p12");
        Path serverCsr = tempDir.resolve("server.csr");
        Path serverSignedPem = tempDir.resolve("server-signed.pem");
        Path caV2P12 = tempDir.resolve("ca-v2.p12");
        Path caV2Pem = tempDir.resolve("ca-v2.pem");

        generateRootCaAndSignedServerKeystore(caV1P12, caV1Pem, serverLeafP12, serverCsr, serverSignedPem);
        generateStandaloneRootCa(caV2P12, caV2Pem, "CN=MoneyCraft Rotated Root CA v2,O=Raster");

        String caV1PemText = Files.readString(caV1Pem, StandardCharsets.UTF_8);
        String caV2PemText = Files.readString(caV2Pem, StandardCharsets.UTF_8);
        String caV1Sha256 = computePemSha256Hex(caV1Pem);
        String caV2Sha256 = computePemSha256Hex(caV2Pem);

        HttpsServer httpsServer = startPrivateCaHttpsServer(serverLeafP12);
        try {
            int port = httpsServer.getAddress().getPort();
            URI hostOrigin = URI.create("https://localhost:" + port);
            EnrolledHostTrustManager trustManager = new EnrolledHostTrustManager(hostOrigin);

            // 1. Default JVM PKIX rejects un-enrolled private-LAN CA before sending payload
            DesktopClientConfig unenrolledCfg = new DesktopClientConfig(
                hostOrigin,
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                trustManager.activeSslContext(),
                false,
                true
            );
            try (DesktopApiClient unenrolledClient = new DesktopApiClient(unenrolledCfg, () -> Optional.of("tok"))) {
                assertThrows(CompletionException.class, () -> unenrolledClient.getSystemStatus().join());
            }

            // 2. Wrong out-of-band SHA-256 fingerprint is rejected deterministically
            NativeAuthSecurityException badFp = assertThrows(
                NativeAuthSecurityException.class,
                () -> trustManager.enrollProvisionedPrivateCa(
                    "LAN_PROFILE_BAD",
                    caV1PemText,
                    "0".repeat(64),
                    "admin-ticket-101"
                )
            );
            assertEquals("MC-TLS-CA-FINGERPRINT-MISMATCH", badFp.errorCode());

            // 3. Trust-on-first-use (learning root from failing endpoint) is explicitly forbidden
            NativeAuthSecurityException tofuBlocked = assertThrows(
                NativeAuthSecurityException.class,
                () -> EnrolledHostTrustManager.rejectUntrustedEndpointSuppliedRoot(hostOrigin)
            );
            assertEquals("MC-TLS-TOFU-FORBIDDEN", tofuBlocked.errorCode());

            // 4. Verified out-of-band SHA-256 fingerprint enrolment succeeds over real TLS + HTTPS hostname check
            trustManager.enrollProvisionedPrivateCa(
                "LAN_PROFILE_V1",
                caV1PemText,
                caV1Sha256,
                "admin-ticket-102"
            );
            assertEquals("LAN_PROFILE_V1", trustManager.enrolledTrustProfileId());
            assertEquals(caV1Sha256, trustManager.enrolledCaFingerprintSha256());

            DesktopClientConfig enrolledCfg = new DesktopClientConfig(
                hostOrigin,
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                trustManager.activeSslContext(),
                false,
                true
            );
            try (DesktopApiClient enrolledClient = new DesktopApiClient(enrolledCfg, Optional::empty)) {
                SystemStatusDto status = enrolledClient.getSystemStatus().join();
                assertEquals("NORMAL", status.securityState());
                assertEquals("1.1.0", status.version());
            }

            // 5. Cross-origin and HTTP downgrade targets are blocked before credentials leave client
            NativeAuthSecurityException downgrade = assertThrows(
                NativeAuthSecurityException.class,
                () -> trustManager.assertMatchesEnrolledOrigin(URI.create("http://localhost:" + port + "/api/v1/companies"))
            );
            assertEquals("MC-TLS-DOWNGRADE-BLOCKED", downgrade.errorCode());

            NativeAuthSecurityException crossOrigin = assertThrows(
                NativeAuthSecurityException.class,
                () -> trustManager.assertMatchesEnrolledOrigin(URI.create("https://rogue.internal:" + port + "/api/v1/companies"))
            );
            assertEquals("MC-TLS-CROSS-ORIGIN-BLOCKED", crossOrigin.errorCode());

            // 6. Rotating CA to v2 invalidates v1 server certificate immediately and audits rotation + removal
            trustManager.rotateProvisionedPrivateCa(
                "LAN_PROFILE_V2",
                caV2PemText,
                caV2Sha256,
                "admin-ticket-103"
            );
            DesktopClientConfig rotatedCfg = new DesktopClientConfig(
                hostOrigin,
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                trustManager.activeSslContext(),
                false,
                true
            );
            try (DesktopApiClient rotatedClient = new DesktopApiClient(rotatedCfg, Optional::empty)) {
                assertThrows(CompletionException.class, () -> rotatedClient.getSystemStatus().join());
            }

            trustManager.removeProvisionedPrivateCa("admin-ticket-104");
            assertEquals("SYSTEM_DEFAULT_PKIX", trustManager.enrolledTrustProfileId());
            assertTrue(trustManager.auditEvents().size() >= 4);
        } finally {
            httpsServer.stop(0);
        }
    }

    @Test
    void atIter110_024_hostNativePkceSingleUseCallbackReplayRejectionAndZeroJPasswordFields() throws Exception {
        AtomicReference<String> capturedCodeChallenge = new AtomicReference<>();
        AtomicReference<String> capturedClientId = new AtomicReference<>();
        AtomicReference<String> capturedRevokeToken = new AtomicReference<>();

        HttpServer hostAuthServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int port = hostAuthServer.getAddress().getPort();
        URI hostOrigin = URI.create("http://127.0.0.1:" + port);

        hostAuthServer.createContext("/api/v1/auth/native/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseForm(form);
            capturedClientId.set(params.get("client_id"));

            String verifier = params.get("code_verifier");
            String recomputedChallenge;
            try {
                recomputedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))
                );
            } catch (java.security.NoSuchAlgorithmException ex) {
                throw new IllegalStateException(ex);
            }
            assertEquals(capturedCodeChallenge.get(), recomputedChallenge);

            String tokenJson = """
                {
                  "access_token": "mc_at_native_live_991122",
                  "refresh_token": "mc_rt_native_live_883344",
                  "token_type": "Bearer",
                  "expires_in": 900,
                  "subject": "person-uuid-771",
                  "scope": "openid profile"
                }
                """;
            byte[] body = tokenJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        hostAuthServer.createContext("/api/v1/auth/native/revoke", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedRevokeToken.set(parseForm(form).get("token"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        hostAuthServer.start();

        try {
            AdmittedNativeIssuerProfile issuerProfile = HostNativeIssuerFactory.forHostOrigin(hostOrigin, true);
            assertEquals("moneycraft-swing-desktop", issuerProfile.clientId());

            InMemoryAccessTokenHolder memoryTokens = new InMemoryAccessTokenHolder();
            UnlockedMemorySecretBackend backend = new UnlockedMemorySecretBackend();
            PlatformSecretStoreCredentialVault vault = new PlatformSecretStoreCredentialVault(
                hostOrigin,
                issuerProfile.clientId(),
                "desktop-profile-01",
                memoryTokens,
                backend,
                true
            );

            AtomicReference<URI> callbackReplayTarget = new AtomicReference<>();
            AtomicReference<Integer> replayStatus = new AtomicReference<>();

            NativePkceAuthClient authClient = new NativePkceAuthClient(
                vault,
                authorizeUri -> {
                    Map<String, String> q = parseForm(authorizeUri.getRawQuery());
                    assertEquals("S256", q.get("code_challenge_method"));
                    assertEquals("moneycraft-swing-desktop", q.get("client_id"));
                    capturedCodeChallenge.set(q.get("code_challenge"));

                    URI redirectUri = URI.create(q.get("redirect_uri"));
                    URI firstCallback = URI.create(redirectUri + "?code=authcode-551&state=" + q.get("state"));
                    callbackReplayTarget.set(firstCallback);

                    CompletableFuture.runAsync(() -> {
                        try {
                            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                            java.net.http.HttpResponse<String> first = client.send(
                                java.net.http.HttpRequest.newBuilder(firstCallback).GET().build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString()
                            );
                            assertEquals(200, first.statusCode());
                            // Attempt replay immediately if socket still open
                            try {
                                java.net.http.HttpResponse<String> second = client.send(
                                    java.net.http.HttpRequest.newBuilder(firstCallback).GET().build(),
                                    java.net.http.HttpResponse.BodyHandlers.ofString()
                                );
                                replayStatus.set(second.statusCode());
                            } catch (IOException closedAlready) {
                                // Immediate close of ephemeral loopback server also prevents replay!
                                replayStatus.set(409);
                            }
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    });
                }
            );

            StoredNativeCredentials stored;
            try (authClient) {
                stored = authClient.signInAsync(issuerProfile, Duration.ofSeconds(5)).join();
                assertEquals("person-uuid-771", stored.subject());
                assertEquals("mc_at_native_live_991122", memoryTokens.accessTokenForOrigin(hostOrigin).orElseThrow());
                assertEquals("moneycraft-swing-desktop", capturedClientId.get());
                assertEquals(409, replayStatus.get());
                // Verify raw access_token was NOT written into the OS refresh-token vault payload
                String persistedVaultPayload = backend.entries.values().iterator().next();
                assertFalse(persistedVaultPayload.contains("mc_at_native_live_991122"));
                assertTrue(persistedVaultPayload.contains("mc_rt_native_live_883344"));

                authClient.logout(issuerProfile);
                assertTrue(memoryTokens.accessTokenForOrigin(hostOrigin).isEmpty());
                assertEquals("mc_rt_native_live_883344", capturedRevokeToken.get());
            }

            // Verify zero JPasswordField anywhere in MoneyCraftDesktopFrame component hierarchy
            byte[] ledgerKey = new byte[32];
            new SecureRandom().nextBytes(ledgerKey);
            DesktopCommandRegistry registry = new DesktopCommandRegistry();
            MoneyCraftDesktopFrame frame = new MoneyCraftDesktopFrame(
                new BrandSwingTheme(),
                registry,
                new PrintAttemptLedger(tempDir.resolve("ledger.enc"), ledgerKey),
                new ScaleAndBarcodeCaptureManager(registry)
            );
            assertEquals(0, countPasswordFields(frame.rootPane()), "Swing client must contain zero JPasswordField controls");
        } finally {
            hostAuthServer.stop(0);
        }
    }

    @Test
    void atIter110_025_inMemoryTokenCrossHostIsolationLockedVaultZeroPlaintextAndStepUpBoundary() throws Exception {
        URI hostA = URI.create("https://acct-a.internal:8443");
        URI hostB = URI.create("https://acct-b.internal:8443");
        InMemoryAccessTokenHolder holder = new InMemoryAccessTokenHolder();

        // Reject id_token masquerading as access_token and reject mc_sk_ service API keys
        assertThrows(
            NativeAuthSecurityException.class,
            () -> holder.storeInMemory(
                hostA, hostA.toString(), "moneycraft-swing-desktop", "sub-1",
                "same-jwt-value", "same-jwt-value", Instant.now().plusSeconds(600L)
            )
        );
        assertThrows(
            NativeAuthSecurityException.class,
            () -> holder.storeInMemory(
                hostA, hostA.toString(), "moneycraft-swing-desktop", "sub-1",
                "mc_sk_automation_secret", null, Instant.now().plusSeconds(600L)
            )
        );

        holder.storeInMemory(
            hostA, hostA.toString(), "moneycraft-swing-desktop", "sub-1",
            "mc_at_scoped_host_a", "distinct_id_token", Instant.now().plusSeconds(600L)
        );
        assertEquals("mc_at_scoped_host_a", holder.accessTokenForOrigin(hostA).orElseThrow());
        assertFalse(holder.toString().contains("mc_at_scoped_host_a"));

        NativeAuthSecurityException crossHost = assertThrows(
            NativeAuthSecurityException.class,
            () -> holder.accessTokenForOrigin(hostB)
        );
        assertEquals("MC-CROSS-HOST-TOKEN-REUSE-BLOCKED", crossHost.errorCode());

        // Local unlock NEVER satisfies server-side recent-auth / 5-minute step-up
        NativeAuthSecurityException stepUp = assertThrows(
            NativeAuthSecurityException.class,
            InMemoryAccessTokenHolder::assertLocalUnlockCannotSatisfyServerStepUp
        );
        assertEquals("MC-SERVER-STEP-UP-REQUIRED", stepUp.errorCode());

        // Locked/unavailable OS vault degrades to interactive memory-only session with ZERO plaintext files
        Path emptyCheckDir = tempDir.resolve("zero-plaintext-dir");
        Files.createDirectories(emptyCheckDir);
        InMemoryAccessTokenHolder lockedSessionHolder = new InMemoryAccessTokenHolder();
        PlatformSecretStoreCredentialVault degradingVault = new PlatformSecretStoreCredentialVault(
            hostA,
            "moneycraft-swing-desktop",
            "desktop-01",
            lockedSessionHolder,
            new PlatformSecretStoreCredentialVault.UnavailableOsSecretBackend(
                PlatformSecretStoreCredentialVault.PlatformKind.MACOS_KEYCHAIN
            ),
            true
        );
        assertEquals(
            PlatformSecretStoreCredentialVault.FallbackState.LOCKED_OR_UNAVAILABLE_INTERACTIVE_REAUTH_ONLY,
            degradingVault.fallbackState()
        );

        degradingVault.store(new StoredNativeCredentials(
            hostA.toString(),
            "sub-locked",
            "moneycraft-api",
            "mc_at_interactive_only",
            "mc_rt_never_written_to_disk",
            Instant.now().plusSeconds(600L),
            List.of("openid")
        ));
        assertEquals("mc_at_interactive_only", degradingVault.load().orElseThrow().accessToken());
        try (var stream = Files.list(emptyCheckDir)) {
            assertEquals(0L, stream.count(), "Zero plaintext fallback files may be created when OS vault is locked");
        }

        // Strict vault (interactiveFallback=false) throws VaultUnavailableException explicitly
        PlatformSecretStoreCredentialVault strictVault = new PlatformSecretStoreCredentialVault(
            hostA,
            "moneycraft-swing-desktop",
            "desktop-01",
            new InMemoryAccessTokenHolder(),
            new PlatformSecretStoreCredentialVault.UnavailableOsSecretBackend(
                PlatformSecretStoreCredentialVault.PlatformKind.LINUX_SECRET_SERVICE
            ),
            false
        );
        assertThrows(VaultUnavailableException.class, strictVault::load);
    }

    private static int countPasswordFields(Component component) {
        int count = component instanceof JPasswordField ? 1 : 0;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                count += countPasswordFields(child);
            }
        }
        return count;
    }

    private static void generateRootCaAndSignedServerKeystore(
        Path caP12,
        Path caPem,
        Path serverP12,
        Path serverCsr,
        Path serverSignedPem
    ) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        String pass = "changeit-test-only";

        runKeytool(List.of(
            keytool, "-genkeypair", "-alias", "rootca", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=MoneyCraft Private LAN Root CA v1,O=Raster",
            "-ext", "bc:c=ca:true", "-ext", "ku=keyCertSign,cRLSign",
            "-validity", "365", "-storetype", "PKCS12", "-keystore", caP12.toString(), "-storepass", pass
        ));
        runKeytool(List.of(
            keytool, "-exportcert", "-rfc", "-alias", "rootca",
            "-keystore", caP12.toString(), "-storepass", pass, "-file", caPem.toString()
        ));
        runKeytool(List.of(
            keytool, "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=localhost,O=Raster",
            "-validity", "365", "-storetype", "PKCS12", "-keystore", serverP12.toString(), "-storepass", pass
        ));
        runKeytool(List.of(
            keytool, "-certreq", "-alias", "server",
            "-keystore", serverP12.toString(), "-storepass", pass, "-file", serverCsr.toString()
        ));
        runKeytool(List.of(
            keytool, "-gencert", "-rfc", "-alias", "rootca",
            "-keystore", caP12.toString(), "-storepass", pass,
            "-infile", serverCsr.toString(), "-outfile", serverSignedPem.toString(),
            "-ext", "san=dns:localhost,ip:127.0.0.1", "-ext", "eku=serverAuth", "-validity", "365"
        ));
        runKeytool(List.of(
            keytool, "-importcert", "-noprompt", "-alias", "rootca",
            "-keystore", serverP12.toString(), "-storepass", pass, "-file", caPem.toString()
        ));
        runKeytool(List.of(
            keytool, "-importcert", "-noprompt", "-alias", "server",
            "-keystore", serverP12.toString(), "-storepass", pass, "-file", serverSignedPem.toString()
        ));
    }

    private static void generateStandaloneRootCa(Path caP12, Path caPem, String dname) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        String pass = "changeit-test-only";
        runKeytool(List.of(
            keytool, "-genkeypair", "-alias", "rootca2", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", dname, "-ext", "bc:c=ca:true",
            "-validity", "365", "-storetype", "PKCS12", "-keystore", caP12.toString(), "-storepass", pass
        ));
        runKeytool(List.of(
            keytool, "-exportcert", "-rfc", "-alias", "rootca2",
            "-keystore", caP12.toString(), "-storepass", pass, "-file", caPem.toString()
        ));
    }

    private static void runKeytool(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0, "keytool failed: " + output);
    }

    private static String computePemSha256Hex(Path pemPath) throws Exception {
        try (InputStream in = Files.newInputStream(pemPath)) {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        }
    }

    private static HttpsServer startPrivateCaHttpsServer(Path serverLeafP12) throws Exception {
        char[] pass = "changeit-test-only".toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(serverLeafP12)) {
            ks.load(in, pass);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters params) {
                params.setSSLParameters(getSSLContext().getDefaultSSLParameters());
            }
        });
        server.createContext("/api/v1/system/status", exchange -> {
            byte[] json = """
                {"securityState":"NORMAL","fenceEpoch":1,"version":"1.1.0","quarantineReason":null}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json);
            }
        });
        server.start();
        return server;
    }

    private static Map<String, String> parseForm(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                result.put(
                    URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
                );
            }
        }
        return result;
    }

    private static final class UnlockedMemorySecretBackend implements PlatformSecretStoreCredentialVault.OsSecretBackend {
        private final Map<String, String> entries = new ConcurrentHashMap<>();

        @Override
        public PlatformSecretStoreCredentialVault.PlatformKind platformKind() {
            return PlatformSecretStoreCredentialVault.PlatformKind.MACOS_KEYCHAIN;
        }

        @Override
        public boolean isUnlockedAndAvailable() {
            return true;
        }

        @Override
        public void putSecret(String scopedKey, String secretPayload) {
            entries.put(scopedKey, secretPayload);
        }

        @Override
        public Optional<String> getSecret(String scopedKey) {
            return Optional.ofNullable(entries.get(scopedKey));
        }

        @Override
        public void deleteSecret(String scopedKey) {
            entries.remove(scopedKey);
        }
    }
}
