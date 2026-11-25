package com.raster.moneycraft.desktop.auth;

import com.raster.moneycraft.desktop.client.DesktopJcsCanonicalizer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * RFC 8252 + RFC 9700 public native OAuth 2.0 / OIDC + PKCE S256 authentication client (SWG-002).
 * Uses the external system browser, binds an ephemeral 127.0.0.1:0 loopback callback listener
 * with zero device/printer/business endpoints, closes the listener immediately after the callback,
 * validates issuer/audience/state/nonce/PKCE, and stores tokens in a qualified {@link CredentialVault}.
 */
public final class NativePkceAuthClient implements AutoCloseable {

    private static final String CALLBACK_PATH = "/oauth2/callback";

    private final CredentialVault credentialVault;
    private final SystemBrowserLauncher browserLauncher;
    private final Clock clock;
    private final DesktopJcsCanonicalizer canonicalizer;
    private final ExecutorService authExecutor;
    private final HttpClient httpClient;
    private volatile int lastLoopbackPort;

    public NativePkceAuthClient(
        CredentialVault credentialVault,
        SystemBrowserLauncher browserLauncher
    ) {
        this(credentialVault, browserLauncher, Clock.systemUTC(), null);
    }

    public NativePkceAuthClient(
        CredentialVault credentialVault,
        SystemBrowserLauncher browserLauncher,
        Clock clock,
        SSLContext sslContext
    ) {
        this.credentialVault = Objects.requireNonNull(credentialVault, "credentialVault must not be null");
        this.browserLauncher = Objects.requireNonNull(browserLauncher, "browserLauncher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.canonicalizer = new DesktopJcsCanonicalizer();
        this.authExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("mc-desktop-auth-", 0).factory()
        );

        SSLParameters sslParams = new SSLParameters();
        sslParams.setEndpointIdentificationAlgorithm("HTTPS");

        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .sslParameters(sslParams)
            .executor(authExecutor);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        this.httpClient = builder.build();
    }

    public int lastLoopbackPort() {
        return lastLoopbackPort;
    }

    public Optional<String> currentAccessToken() {
        if (!credentialVault.isAvailable()) {
            return Optional.empty();
        }
        return credentialVault.load()
            .filter(creds -> !creds.isExpired(clock.instant()))
            .map(StoredNativeCredentials::accessToken);
    }

    /**
     * Executes an external-browser PKCE S256 sign-in off the Swing EDT.
     */
    public CompletableFuture<StoredNativeCredentials> signInAsync(
        AdmittedNativeIssuerProfile profile,
        Duration callbackTimeout
    ) {
        Objects.requireNonNull(profile, "profile must not be null");
        Duration effectiveTimeout = callbackTimeout == null ? Duration.ofMinutes(2) : callbackTimeout;
        return CompletableFuture.supplyAsync(() -> signInBlocking(profile, effectiveTimeout), authExecutor);
    }

    public StoredNativeCredentials signInBlocking(
        AdmittedNativeIssuerProfile profile,
        Duration callbackTimeout
    ) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(callbackTimeout, "callbackTimeout must not be null");
        if (!credentialVault.isAvailable()) {
            throw new VaultUnavailableException(
                "Host credential vault is unavailable; refusing native sign-in without protected secret storage."
            );
        }

        CompletableFuture<Map<String, String>> callbackFuture = new CompletableFuture<>();
        HttpServer callbackServer = bindEphemeralLoopbackCallback(callbackFuture);
        try {
            int port = callbackServer.getAddress().getPort();
            this.lastLoopbackPort = port;
            URI redirectUri = URI.create("http://127.0.0.1:" + port + CALLBACK_PATH);
            PkceTransaction pkce = PkceTransaction.generate(redirectUri, clock.instant().plus(callbackTimeout));

            URI authorizationUri = buildAuthorizationUri(profile, pkce);
            browserLauncher.openExternalBrowser(authorizationUri);

            Map<String, String> callbackParams = awaitCallback(callbackFuture, callbackTimeout);
            return completeTokenExchange(profile, pkce, callbackParams);
        } finally {
            callbackServer.stop(0);
        }
    }

    /**
     * Rotates refresh credentials using RFC 9700 public-client replay/revocation handling.
     */
    public StoredNativeCredentials rotateRefreshToken(AdmittedNativeIssuerProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        if (!credentialVault.isAvailable()) {
            throw new VaultUnavailableException("Host credential vault is unavailable during token renewal.");
        }
        StoredNativeCredentials existing = credentialVault.load()
            .orElseThrow(() -> new NativeAuthSecurityException("MC-NO-STORED-SESSION", "No active native session."));
        if (existing.refreshToken() == null || existing.refreshToken().isBlank()) {
            throw new NativeAuthSecurityException("MC-REFRESH-UNAVAILABLE", "No refresh token issued for session.");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", profile.clientId());
        form.put("refresh_token", existing.refreshToken());

        HttpRequest request = HttpRequest.newBuilder(profile.tokenEndpoint())
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8))
            .build();
        try {
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() != 200) {
                credentialVault.clear();
                throw new NativeAuthSecurityException(
                    "MC-REFRESH-REJECTED",
                    "Refresh token rotation denied by issuer (HTTP " + response.statusCode()
                        + "); local credentials cleared."
                );
            }
            StoredNativeCredentials rotated = parseAndValidateTokenResponse(profile, null, response.body());
            credentialVault.store(rotated);
            return rotated;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new NativeAuthSecurityException("MC-REFRESH-INTERRUPTED", "Interrupted during token refresh.", ex);
        } catch (IOException ex) {
            throw new NativeAuthSecurityException("MC-REFRESH-IO-ERROR", "Transport error during token refresh.", ex);
        }
    }

    /**
     * Clears local credentials unconditionally and notifies the issuer revocation endpoint if configured.
     */
    public void logout(AdmittedNativeIssuerProfile profile) {
        Optional<StoredNativeCredentials> existing = Optional.empty();
        try {
            if (credentialVault.isAvailable()) {
                existing = credentialVault.load();
            }
        } finally {
            credentialVault.clear();
        }

        if (profile != null && profile.revocationEndpoint() != null && existing.isPresent()) {
            StoredNativeCredentials creds = existing.get();
            String tokenToRevoke = creds.refreshToken() != null && !creds.refreshToken().isBlank()
                ? creds.refreshToken()
                : creds.accessToken();
            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", profile.clientId());
            form.put("token", tokenToRevoke);
            HttpRequest req = HttpRequest.newBuilder(profile.revocationEndpoint())
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8))
                .build();
            try {
                httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Local credentials are already wiped; remote revocation failure cannot retain local access.
            }
        }
    }

    @Override
    public void close() {
        httpClient.close();
        authExecutor.close();
    }

    private HttpServer bindEphemeralLoopbackCallback(CompletableFuture<Map<String, String>> callbackFuture) {
        try {
            InetSocketAddress loopback = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
            HttpServer server = HttpServer.create(loopback, 0);
            AtomicBoolean callbackConsumed = new AtomicBoolean(false);
            server.setExecutor(authExecutor);
            server.createContext("/", exchange -> handleLoopbackExchange(exchange, callbackFuture, callbackConsumed));
            server.start();
            return server;
        } catch (IOException ex) {
            throw new NativeAuthSecurityException(
                "MC-LOOPBACK-BIND-FAILED",
                "Failed to bind ephemeral 127.0.0.1 loopback PKCE callback listener.",
                ex
            );
        }
    }

    private static void handleLoopbackExchange(
        HttpExchange exchange,
        CompletableFuture<Map<String, String>> callbackFuture,
        AtomicBoolean callbackConsumed
    ) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (!CALLBACK_PATH.equals(path)) {
                writePlainResponse(
                    exchange,
                    404,
                    "Not Found: ephemeral OAuth loopback listener exposes zero device, printer, or business routes."
                );
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writePlainResponse(exchange, 405, "Method Not Allowed: only GET authorization redirect is accepted.");
                return;
            }
            if (!callbackConsumed.compareAndSet(false, true)) {
                writePlainResponse(exchange, 409, "Conflict: OAuth callback already consumed; replay rejected.");
                return;
            }
            Map<String, String> queryParams = parseQuery(exchange.getRequestURI().getRawQuery());
            callbackFuture.complete(queryParams);
            writePlainResponse(
                exchange,
                200,
                "MoneyCraft sign-in response received. You may close this browser window."
            );
        } finally {
            exchange.close();
        }
    }

    private static void writePlainResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> awaitCallback(
        CompletableFuture<Map<String, String>> callbackFuture,
        Duration timeout
    ) {
        try {
            return callbackFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new NativeAuthSecurityException(
                "MC-PKCE-CALLBACK-TIMEOUT",
                "Timed out waiting for external-browser OAuth 2.0 callback.",
                ex
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new NativeAuthSecurityException("MC-PKCE-INTERRUPTED", "Sign-in callback interrupted.", ex);
        } catch (Exception ex) {
            throw new NativeAuthSecurityException("MC-PKCE-CALLBACK-FAILED", "Failed receiving OAuth callback.", ex);
        }
    }

    private StoredNativeCredentials completeTokenExchange(
        AdmittedNativeIssuerProfile profile,
        PkceTransaction pkce,
        Map<String, String> callbackParams
    ) {
        if (clock.instant().isAfter(pkce.expiresAt())) {
            throw new NativeAuthSecurityException("MC-PKCE-EXPIRED", "PKCE sign-in transaction expired.");
        }
        String returnedState = callbackParams.get("state");
        if (!pkce.matchesStateConstantTime(returnedState)) {
            throw new NativeAuthSecurityException(
                "MC-PKCE-STATE-MISMATCH",
                "OAuth callback state parameter did not match active PKCE transaction."
            );
        }
        String error = callbackParams.get("error");
        if (error != null && !error.isBlank()) {
            throw new NativeAuthSecurityException(
                "MC-OAUTH-AUTHORIZATION-DENIED",
                "Authorization server denied request: " + error
            );
        }
        String code = callbackParams.get("code");
        if (code == null || code.isBlank()) {
            throw new NativeAuthSecurityException("MC-PKCE-MISSING-CODE", "OAuth callback omitted authorization code.");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", pkce.redirectUri().toString());
        form.put("client_id", profile.clientId());
        form.put("code_verifier", pkce.codeVerifier());

        HttpRequest tokenRequest = HttpRequest.newBuilder(profile.tokenEndpoint())
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(
                tokenRequest,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() != 200) {
                throw new NativeAuthSecurityException(
                    "MC-PKCE-TOKEN-EXCHANGE-DENIED",
                    "Token endpoint rejected PKCE code exchange with HTTP " + response.statusCode() + "."
                );
            }
            StoredNativeCredentials verified = parseAndValidateTokenResponse(profile, pkce, response.body());
            credentialVault.store(verified);
            return verified;
        } catch (NativeAuthSecurityException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new NativeAuthSecurityException("MC-PKCE-INTERRUPTED", "Token exchange interrupted.", ex);
        } catch (IOException ex) {
            throw new NativeAuthSecurityException("MC-PKCE-IO-ERROR", "Transport failure during token exchange.", ex);
        }
    }

    private StoredNativeCredentials parseAndValidateTokenResponse(
        AdmittedNativeIssuerProfile profile,
        PkceTransaction pkceOrNull,
        String rawJson
    ) {
        try {
            JsonNode root = canonicalizer.wireMapper().readTree(rawJson);
            String accessToken = requiredText(root, "access_token");
            String refreshToken = optionalText(root, "refresh_token");
            String idToken = optionalText(root, "id_token");
            long expiresInSeconds = root.path("expires_in").asLong(300L);
            if (expiresInSeconds <= 0L) {
                throw new NativeAuthSecurityException("MC-TOKEN-EXPIRY-INVALID", "Token expires_in must be positive.");
            }
            if (idToken != null && idToken.equals(accessToken)) {
                throw new NativeAuthSecurityException(
                    "MC-IDTOKEN-AS-ACCESSTOKEN-FORBIDDEN",
                    "OIDC id_token must never be substituted as an API bearer access_token (M-S04)."
                );
            }

            Instant tokenExpiry = clock.instant().plusSeconds(expiresInSeconds);
            String verifiedIssuer;
            String verifiedSubject;
            Instant effectiveExpiry;

            if (idToken != null && !idToken.isBlank()) {
                VerifiedIdTokenClaims claims = verifyIdToken(profile, pkceOrNull, idToken);
                verifiedIssuer = claims.issuer();
                verifiedSubject = claims.subject();
                effectiveExpiry = claims.expiresAt().isBefore(tokenExpiry) ? claims.expiresAt() : tokenExpiry;
            } else if (profile.category() == AdmittedNativeIssuerProfile.IssuerCategory.LOCAL_INSTALLATION_ISSUER) {
                String explicitIssuer = optionalText(root, "issuer");
                if (explicitIssuer == null) {
                    explicitIssuer = optionalText(root, "iss");
                }
                if (explicitIssuer != null && !profile.issuerUri().toString().equals(explicitIssuer)) {
                    throw new NativeAuthSecurityException(
                        "MC-OIDC-ISSUER-MISMATCH",
                        "Native token issuer '" + explicitIssuer + "' did not match '" + profile.issuerUri() + "'."
                    );
                }
                JsonNode audNode = root.get("audience") != null ? root.get("audience") : root.get("aud");
                if (audNode != null && !audNode.isNull() && !audienceMatches(audNode, profile.expectedAudience())) {
                    throw new NativeAuthSecurityException(
                        "MC-OIDC-AUDIENCE-MISMATCH",
                        "Native token audience did not include '" + profile.expectedAudience() + "'."
                    );
                }
                String sub = optionalText(root, "subject");
                if (sub == null || sub.isBlank()) {
                    sub = optionalText(root, "sub");
                }
                if (sub == null || sub.isBlank()) {
                    sub = optionalText(root, "principalId");
                }
                if (sub == null || sub.isBlank()) {
                    sub = "host-authenticated-person";
                }
                verifiedIssuer = profile.issuerUri().toString();
                verifiedSubject = sub;
                effectiveExpiry = tokenExpiry;
            } else {
                throw new NativeAuthSecurityException(
                    "MC-OIDC-CLAIM-MISSING",
                    "Organisational OIDC token response omitted required id_token."
                );
            }

            List<String> grantedScopes = new ArrayList<>(profile.scopes());
            String scopeField = optionalText(root, "scope");
            if (scopeField != null && !scopeField.isBlank()) {
                grantedScopes = List.of(scopeField.trim().split("\\s+"));
            }

            return new StoredNativeCredentials(
                verifiedIssuer,
                verifiedSubject,
                profile.expectedAudience(),
                accessToken,
                refreshToken,
                effectiveExpiry,
                grantedScopes
            );
        } catch (JacksonException ex) {
            throw new NativeAuthSecurityException("MC-TOKEN-JSON-INVALID", "Malformed JSON from token endpoint.", ex);
        }
    }

    private VerifiedIdTokenClaims verifyIdToken(
        AdmittedNativeIssuerProfile profile,
        PkceTransaction pkceOrNull,
        String jwt
    ) {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            throw new NativeAuthSecurityException("MC-IDTOKEN-MALFORMED", "OIDC id_token must be a 3-part JWS.");
        }
        Base64.Decoder urlDecoder = Base64.getUrlDecoder();
        JsonNode header;
        JsonNode payload;
        try {
            header = canonicalizer.wireMapper().readTree(
                new String(urlDecoder.decode(parts[0]), StandardCharsets.UTF_8)
            );
            payload = canonicalizer.wireMapper().readTree(
                new String(urlDecoder.decode(parts[1]), StandardCharsets.UTF_8)
            );
        } catch (IllegalArgumentException | JacksonException ex) {
            throw new NativeAuthSecurityException("MC-IDTOKEN-MALFORMED", "Unable to decode id_token JWT.", ex);
        }

        String alg = requiredText(header, "alg");
        if ("none".equalsIgnoreCase(alg)) {
            throw new NativeAuthSecurityException("MC-IDTOKEN-UNSIGNED", "Unsigned alg=none JWTs are forbidden.");
        }
        PublicKey verificationKey = profile.jwtVerificationKey();
        if (verificationKey == null
            && profile.category() == AdmittedNativeIssuerProfile.IssuerCategory.LOCAL_INSTALLATION_ISSUER) {
            verificationKey = HostNativeIssuerFactory.discoverFromHostOrigin(
                profile.issuerUri(),
                httpClient,
                profile.allowLoopbackHttpForTesting()
            ).jwtVerificationKey();
        }
        if (verificationKey != null) {
            byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
            byte[] signatureBytes;
            try {
                signatureBytes = urlDecoder.decode(parts[2]);
            } catch (IllegalArgumentException ex) {
                throw new NativeAuthSecurityException("MC-IDTOKEN-SIG-INVALID", "Malformed JWS signature encoding.", ex);
            }
            verifyJwsSignature(alg, verificationKey, signingInput, signatureBytes);
        }

        String iss = requiredText(payload, "iss");
        if (!issuerMatchesProfile(profile, iss)) {
            throw new NativeAuthSecurityException(
                "MC-OIDC-ISSUER-MISMATCH",
                "ID token issuer '" + iss + "' did not match admitted issuer '" + profile.issuerUri() + "'."
            );
        }

        if (!audienceMatches(payload.get("aud"), profile.expectedAudience())) {
            throw new NativeAuthSecurityException(
                "MC-OIDC-AUDIENCE-MISMATCH",
                "ID token audience did not include expected audience '" + profile.expectedAudience() + "'."
            );
        }

        long expEpochSec = payload.path("exp").asLong(0L);
        Instant expiresAt = Instant.ofEpochSecond(expEpochSec);
        if (!expiresAt.isAfter(clock.instant())) {
            throw new NativeAuthSecurityException("MC-OIDC-TOKEN-EXPIRED", "ID token exp has already passed.");
        }

        if (pkceOrNull != null) {
            String nonce = optionalText(payload, "nonce");
            if (nonce == null || !MessageDigest.isEqual(
                pkceOrNull.nonce().getBytes(StandardCharsets.UTF_8),
                nonce.getBytes(StandardCharsets.UTF_8)
            )) {
                throw new NativeAuthSecurityException(
                    "MC-OIDC-NONCE-MISMATCH",
                    "ID token nonce did not match active PKCE transaction nonce."
                );
            }
        }

        String subject = requiredText(payload, "sub");
        return new VerifiedIdTokenClaims(iss, subject, expiresAt);
    }

    private static void verifyJwsSignature(
        String alg,
        PublicKey key,
        byte[] signingInput,
        byte[] signatureBytes
    ) {
        String jcaAlg = switch (alg) {
            case "RS256" -> "SHA256withRSA";
            case "ES256" -> "SHA256withECDSAinP1363Format";
            default -> throw new NativeAuthSecurityException(
                "MC-IDTOKEN-ALG-UNSUPPORTED",
                "Unsupported JWS alg '" + alg + "'; expected RS256 or ES256."
            );
        };
        try {
            Signature verifier = Signature.getInstance(jcaAlg);
            verifier.initVerify(key);
            verifier.update(signingInput);
            if (!verifier.verify(signatureBytes)) {
                throw new NativeAuthSecurityException("MC-IDTOKEN-SIG-INVALID", "ID token signature verification failed.");
            }
        } catch (NativeAuthSecurityException ex) {
            throw ex;
        } catch (GeneralSecurityException ex) {
            throw new NativeAuthSecurityException("MC-IDTOKEN-SIG-INVALID", "Cryptographic verification failed.", ex);
        }
    }

    private static boolean issuerMatchesProfile(AdmittedNativeIssuerProfile profile, String tokenIss) {
        if (profile.issuerUri().toString().equals(tokenIss)) {
            return true;
        }
        if (profile.category() == AdmittedNativeIssuerProfile.IssuerCategory.LOCAL_INSTALLATION_ISSUER) {
            try {
                URI tokenUri = URI.create(tokenIss);
                URI expectedUri = profile.issuerUri();
                boolean sameScheme = expectedUri.getScheme() != null
                    && expectedUri.getScheme().equalsIgnoreCase(tokenUri.getScheme());
                boolean sameHost = expectedUri.getHost() != null
                    && expectedUri.getHost().equalsIgnoreCase(tokenUri.getHost());
                boolean isLoopback = "localhost".equalsIgnoreCase(expectedUri.getHost())
                    || "127.0.0.1".equals(expectedUri.getHost());
                return sameScheme && sameHost && isLoopback;
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean audienceMatches(JsonNode audNode, String expectedAudience) {
        if (audNode == null || audNode.isNull()) {
            return false;
        }
        if (audNode.isString()) {
            return expectedAudience.equals(audNode.stringValue());
        }
        if (audNode.isArray()) {
            for (int i = 0; i < audNode.size(); i++) {
                JsonNode elem = audNode.get(i);
                if (elem != null && elem.isString() && expectedAudience.equals(elem.stringValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static URI buildAuthorizationUri(AdmittedNativeIssuerProfile profile, PkceTransaction pkce) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", profile.clientId());
        params.put("redirect_uri", pkce.redirectUri().toString());
        params.put("scope", String.join(" ", profile.scopes()));
        params.put("state", pkce.state());
        params.put("nonce", pkce.nonce());
        params.put("code_challenge", pkce.codeChallengeS256());
        params.put("code_challenge_method", "S256");

        String base = profile.authorizationEndpoint().toString();
        char separator = base.contains("?") ? '&' : '?';
        return URI.create(base + separator + encodeForm(params));
    }

    private static String encodeForm(Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                result.put(key, val);
            } else if (!pair.isEmpty()) {
                result.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            }
        }
        return result;
    }

    private static String requiredText(JsonNode node, String field) {
        String val = optionalText(node, field);
        if (val == null || val.isBlank()) {
            throw new NativeAuthSecurityException(
                "MC-OIDC-CLAIM-MISSING",
                "Required OAuth/OIDC field '" + field + "' was absent."
            );
        }
        return val;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        return child.asString(null);
    }

    private record VerifiedIdTokenClaims(String issuer, String subject, Instant expiresAt) {
    }
}
