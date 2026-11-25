package com.raster.moneycraft.desktop.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Swg002NativePkceAuthAndWarIsolationTest {

    @TempDir
    Path tempDir;

    private HttpServer issuerServer;
    private URI issuerBaseUri;
    private KeyPair issuerRsaKeys;
    private final AtomicReference<String> storedChallengeS256 = new AtomicReference<>();
    private final AtomicReference<String> storedNonce = new AtomicReference<>();
    private final AtomicReference<String> overrideTokenIssuer = new AtomicReference<>();
    private final AtomicReference<String> overrideTokenAudience = new AtomicReference<>();
    private final AtomicBoolean forcePkceGrantFailure = new AtomicBoolean(false);
    private final AtomicBoolean revokedEndpointCalled = new AtomicBoolean(false);

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        issuerRsaKeys = kpg.generateKeyPair();

        issuerServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        issuerBaseUri = URI.create("http://127.0.0.1:" + issuerServer.getAddress().getPort());
        registerMockOidcEndpoints();
        issuerServer.start();
    }

    @AfterEach
    void tearDown() {
        if (issuerServer != null) {
            issuerServer.stop(0);
        }
    }

    @Test
    void at09Swg002A_and_B_completesExternalBrowserPkceS256ExposesZeroControlRoutesClosesLoopbackAndWipesOnLogout() throws Exception {
        Path vaultPath = tempDir.resolve("credentials.enc");
        byte[] vaultKey = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        EncryptedFileCredentialVault vault = new EncryptedFileCredentialVault(vaultPath, vaultKey);

        AtomicInteger probedPrintStatus = new AtomicInteger();
        AtomicInteger probedDeviceStatus = new AtomicInteger();
        AtomicInteger probedPostCallbackStatus = new AtomicInteger();

        SystemBrowserLauncher simulatedOsBrowser = authUri -> {
            Map<String, String> authParams = parseQuery(authUri.getRawQuery());
            assertEquals("S256", authParams.get("code_challenge_method"));
            assertTrue(authParams.get("code_challenge").length() >= 43);
            storedChallengeS256.set(authParams.get("code_challenge"));
            storedNonce.set(authParams.get("nonce"));

            URI redirectUri = URI.create(authParams.get("redirect_uri"));
            URI loopbackRoot = URI.create("http://127.0.0.1:" + redirectUri.getPort());
            try (HttpClient probeClient = HttpClient.newHttpClient()) {
                // Probe ephemeral loopback listener for forbidden device/printer/business control endpoints
                probedPrintStatus.set(probeClient.send(
                    HttpRequest.newBuilder(loopbackRoot.resolve("/print")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()
                ).statusCode());
                probedDeviceStatus.set(probeClient.send(
                    HttpRequest.newBuilder(loopbackRoot.resolve("/device/serial")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()
                ).statusCode());
                probedPostCallbackStatus.set(probeClient.send(
                    HttpRequest.newBuilder(redirectUri)
                        .POST(HttpRequest.BodyPublishers.ofString("cmd=cut"))
                        .build(),
                    HttpResponse.BodyHandlers.discarding()
                ).statusCode());

                URI callbackWithCode = URI.create(
                    redirectUri + "?code=auth-code-valid-001&state=" + authParams.get("state")
                );
                HttpResponse<String> cbResp = probeClient.send(
                    HttpRequest.newBuilder(callbackWithCode).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                assertEquals(200, cbResp.statusCode());
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        };

        AdmittedNativeIssuerProfile profile = buildIssuerProfile();
        try (NativePkceAuthClient authClient = new NativePkceAuthClient(vault, simulatedOsBrowser)) {
            StoredNativeCredentials creds = authClient.signInBlocking(profile, Duration.ofSeconds(5));
            assertEquals("person-operator-42", creds.subject());
            assertEquals(issuerBaseUri.toString(), creds.issuerUri());
            assertTrue(creds.toString().contains("[REDACTED]"));
            assertFalse(creds.toString().contains(creds.accessToken()));

            // Loopback listener exposed ZERO printer/device/POST control endpoints during sign-in
            assertEquals(404, probedPrintStatus.get());
            assertEquals(404, probedDeviceStatus.get());
            assertEquals(405, probedPostCallbackStatus.get());

            // Loopback listener closed IMMEDIATELY after token exchange
            int closedPort = authClient.lastLoopbackPort();
            assertTrue(closedPort > 0);
            assertThrows(ConnectException.class, () -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), closedPort), 500);
                }
            });

            // Encrypted host vault contains zero plaintext tokens on disk
            byte[] rawVaultBytes = Files.readAllBytes(vaultPath);
            String rawAsText = new String(rawVaultBytes, StandardCharsets.ISO_8859_1);
            assertFalse(rawAsText.contains("access-token-secret-xyz"));
            assertFalse(rawAsText.contains("refresh-token-secret-xyz"));
            assertEquals("access-token-secret-xyz", authClient.currentAccessToken().orElseThrow());

            // Logout wipes vault and invokes revocation endpoint
            authClient.logout(profile);
            assertFalse(Files.exists(vaultPath));
            assertTrue(authClient.currentAccessToken().isEmpty());
            assertTrue(revokedEndpointCalled.get());
        }
    }

    @Test
    void at09Swg002B_deniesWrongStateWrongIssuerWrongAudiencePkceMismatchUnavailableVaultAndUnsafeModes() {
        Path vaultPath = tempDir.resolve("denied-vault.enc");
        byte[] vaultKey = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.US_ASCII);
        EncryptedFileCredentialVault vault = new EncryptedFileCredentialVault(vaultPath, vaultKey);
        AdmittedNativeIssuerProfile profile = buildIssuerProfile();

        // 1. Unavailable vault fails closed before sign-in
        vault.setAvailable(false);
        try (NativePkceAuthClient unavailableClient = new NativePkceAuthClient(vault, uri -> { })) {
            assertThrows(
                VaultUnavailableException.class,
                () -> unavailableClient.signInBlocking(profile, Duration.ofSeconds(3))
            );
        }
        vault.setAvailable(true);

        // 2. Wrong callback state rejected
        SystemBrowserLauncher wrongStateBrowser = authUri -> {
            Map<String, String> p = parseQuery(authUri.getRawQuery());
            storedChallengeS256.set(p.get("code_challenge"));
            storedNonce.set(p.get("nonce"));
            triggerGet(URI.create(p.get("redirect_uri") + "?code=auth-code-valid-001&state=forged-state"));
        };
        try (NativePkceAuthClient client = new NativePkceAuthClient(vault, wrongStateBrowser)) {
            NativeAuthSecurityException ex = assertThrows(
                NativeAuthSecurityException.class,
                () -> client.signInBlocking(profile, Duration.ofSeconds(3))
            );
            assertEquals("MC-PKCE-STATE-MISMATCH", ex.errorCode());
        }

        // 3. Wrong issuer rejected
        overrideTokenIssuer.set("https://rogue-issuer.example.org");
        try (NativePkceAuthClient client = new NativePkceAuthClient(vault, validRedirectBrowser())) {
            NativeAuthSecurityException ex = assertThrows(
                NativeAuthSecurityException.class,
                () -> client.signInBlocking(profile, Duration.ofSeconds(3))
            );
            assertEquals("MC-OIDC-ISSUER-MISMATCH", ex.errorCode());
        }
        overrideTokenIssuer.set(null);

        // 4. Wrong audience rejected
        overrideTokenAudience.set("unrelated-client-aud");
        try (NativePkceAuthClient client = new NativePkceAuthClient(vault, validRedirectBrowser())) {
            NativeAuthSecurityException ex = assertThrows(
                NativeAuthSecurityException.class,
                () -> client.signInBlocking(profile, Duration.ofSeconds(3))
            );
            assertEquals("MC-OIDC-AUDIENCE-MISMATCH", ex.errorCode());
        }
        overrideTokenAudience.set(null);

        // 5. PKCE verifier mismatch rejected at token endpoint
        forcePkceGrantFailure.set(true);
        try (NativePkceAuthClient client = new NativePkceAuthClient(vault, validRedirectBrowser())) {
            NativeAuthSecurityException ex = assertThrows(
                NativeAuthSecurityException.class,
                () -> client.signInBlocking(profile, Duration.ofSeconds(3))
            );
            assertEquals("MC-PKCE-TOKEN-EXCHANGE-DENIED", ex.errorCode());
        }
        forcePkceGrantFailure.set(false);

        // 6. Shared secrets, embedded WebViews, and service API keys strictly rejected
        assertThrows(
            NativeAuthSecurityException.class,
            () -> AdmittedNativeIssuerProfile.rejectSharedSecretOrPasswordFallback("embedded-webview-login")
        );
        assertThrows(
            NativeAuthSecurityException.class,
            () -> new AdmittedNativeIssuerProfile(
                "bad-key",
                AdmittedNativeIssuerProfile.IssuerCategory.LOCAL_INSTALLATION_ISSUER,
                URI.create("https://auth.example.internal"),
                URI.create("https://auth.example.internal/authorize"),
                URI.create("https://auth.example.internal/token"),
                null,
                "mc_sk_serviceKeyForbidden",
                "moneycraft-desktop",
                List.of("openid"),
                null,
                false
            )
        );
    }

    @Test
    void at09Swg002C_serverPomAndWarRemainCompletelyIndependentOfDesktopSwingAndDeviceLibraries() throws IOException {
        Path serverPom = Files.exists(Path.of("../server/pom.xml"))
            ? Path.of("../server/pom.xml")
            : Path.of("server/pom.xml");
        assertTrue(Files.exists(serverPom), "Expected server/pom.xml to exist");

        String serverPomXml = Files.readString(serverPom, StandardCharsets.UTF_8);
        assertFalse(serverPomXml.contains("moneycraft-desktop"));
        assertFalse(serverPomXml.contains("jSerialComm"));

        Path serverWar = serverPom.getParent().resolve("target/moneycraft.war");
        if (Files.exists(serverWar)) {
            try (ZipFile warZip = new ZipFile(serverWar.toFile())) {
                Enumeration<? extends ZipEntry> entries = warZip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    assertFalse(
                        name.contains("moneycraft-desktop") || name.contains("com/raster/moneycraft/desktop/"),
                        "WAR must never package desktop Swing or device classes: " + name
                    );
                }
            }
        }
    }

    private AdmittedNativeIssuerProfile buildIssuerProfile() {
        return new AdmittedNativeIssuerProfile(
            "local-keycloak",
            AdmittedNativeIssuerProfile.IssuerCategory.ORGANISATIONAL_SSO_ISSUER,
            issuerBaseUri,
            issuerBaseUri.resolve("/authorize"),
            issuerBaseUri.resolve("/token"),
            issuerBaseUri.resolve("/revoke"),
            "moneycraft-swing-public",
            "moneycraft-api",
            List.of("openid", "profile"),
            issuerRsaKeys.getPublic(),
            true
        );
    }

    private SystemBrowserLauncher validRedirectBrowser() {
        return authUri -> {
            Map<String, String> p = parseQuery(authUri.getRawQuery());
            storedChallengeS256.set(p.get("code_challenge"));
            storedNonce.set(p.get("nonce"));
            triggerGet(URI.create(p.get("redirect_uri") + "?code=auth-code-valid-001&state=" + p.get("state")));
        };
    }

    private static void triggerGet(URI uri) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void registerMockOidcEndpoints() {
        issuerServer.createContext("/token", exchange -> {
            String formBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = parseQuery(formBody);
            assertFalse(form.containsKey("client_secret"), "Public native client must never send client_secret");

            String verifier = form.get("code_verifier");
            String expectedChallenge = PkceTransaction.computeS256Challenge(verifier);
            if (forcePkceGrantFailure.get() || !expectedChallenge.equals(storedChallengeS256.get())) {
                byte[] err = "{\"error\":\"invalid_grant\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
                exchange.close();
                return;
            }

            try {
                String iss = overrideTokenIssuer.get() != null ? overrideTokenIssuer.get() : issuerBaseUri.toString();
                String aud = overrideTokenAudience.get() != null ? overrideTokenAudience.get() : "moneycraft-api";
                String idToken = signRs256IdToken(iss, aud, "person-operator-42", storedNonce.get());
                String tokenJson = "{\"access_token\":\"access-token-secret-xyz\","
                    + "\"refresh_token\":\"refresh-token-secret-xyz\","
                    + "\"token_type\":\"Bearer\",\"expires_in\":300,"
                    + "\"id_token\":\"" + idToken + "\"}";
                byte[] bytes = tokenJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception ex) {
                throw new IOException(ex);
            } finally {
                exchange.close();
            }
        });

        issuerServer.createContext("/revoke", exchange -> {
            revokedEndpointCalled.set(true);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
    }

    private String signRs256IdToken(String iss, String aud, String sub, String nonce) throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        long exp = Instant.now().plusSeconds(600).getEpochSecond();
        String payload = "{\"iss\":\"" + iss + "\",\"aud\":\"" + aud + "\",\"sub\":\"" + sub
            + "\",\"exp\":" + exp + ",\"nonce\":\"" + nonce + "\"}";
        String signingInput = b64.encodeToString(header.getBytes(StandardCharsets.UTF_8))
            + "." + b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(issuerRsaKeys.getPrivate());
        sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + b64.encodeToString(sig.sign());
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                map.put(
                    URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8)
                );
            }
        }
        return map;
    }
}
