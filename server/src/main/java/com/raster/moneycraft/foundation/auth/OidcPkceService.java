package com.raster.moneycraft.foundation.auth;

import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.authority.AdminReceiptService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OidcPkceService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MoneyCraftProperties properties;
    private final AuthorityGuard authorityGuard;
    private final AuthService authService;
    private final TinkCryptoService tinkCryptoService;
    private final AdminReceiptService adminReceiptService;
    private final AuditChainService auditChainService;
    private final RbacAuthorizationService rbacAuthorizationService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate authorityJdbcTemplate;
    private final TransactionTemplate authorityTxTemplate;
    private final TransactionTemplate businessTxTemplate;
    private final HttpClient httpClient;

    public OidcPkceService(
        MoneyCraftProperties properties,
        AuthorityGuard authorityGuard,
        AuthService authService,
        TinkCryptoService tinkCryptoService,
        AdminReceiptService adminReceiptService,
        AuditChainService auditChainService,
        RbacAuthorizationService rbacAuthorizationService,
        JcsCanonicalizer jcsCanonicalizer,
        ObjectMapper objectMapper,
        @Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate,
        @Qualifier("authorityTransactionManager") PlatformTransactionManager authorityTransactionManager,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.properties = properties;
        this.authorityGuard = authorityGuard;
        this.authService = authService;
        this.tinkCryptoService = tinkCryptoService;
        this.adminReceiptService = adminReceiptService;
        this.auditChainService = auditChainService;
        this.rbacAuthorizationService = rbacAuthorizationService;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.objectMapper = objectMapper;
        this.authorityJdbcTemplate = authorityJdbcTemplate;
        this.authorityTxTemplate = new TransactionTemplate(authorityTransactionManager);
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    public AuthDtos.OidcIssuerDto registerIssuer(
        MoneyCraftPrincipal actor,
        AuthDtos.CreateOidcIssuerRequest request
    ) {
        authorityGuard.assertWritable();
        if (request == null || request.registrationId() == null || request.registrationId().isBlank()
            || request.displayName() == null || request.displayName().isBlank()
            || request.issuerUri() == null || request.issuerUri().isBlank()
            || request.clientId() == null || request.clientId().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-OIDC-ISSUER-INVALID",
                "Invalid OIDC Issuer Configuration",
                "registrationId, displayName, issuerUri, and clientId are required."
            );
        }

        UUID installationId = authService.requireActiveInstallationId();
        UUID issuerId = UUID.randomUUID();
        String regId = request.registrationId().trim().toLowerCase();
        String issuerUri = request.issuerUri().trim();
        String authUri = (request.authorizationUri() == null || request.authorizationUri().isBlank())
            ? issuerUri + "/authorize"
            : request.authorizationUri().trim();
        String tokenUri = (request.tokenUri() == null || request.tokenUri().isBlank())
            ? issuerUri + "/token"
            : request.tokenUri().trim();
        String scopes = (request.scopes() == null || request.scopes().isBlank())
            ? "openid profile email"
            : request.scopes().trim();

        byte[] encryptedSecret = null;
        if (request.clientSecret() != null && !request.clientSecret().isBlank()) {
            encryptedSecret = tinkCryptoService.encryptGcm(
                ("ma_issuer|" + issuerId).getBytes(StandardCharsets.UTF_8),
                request.clientSecret().trim().getBytes(StandardCharsets.UTF_8)
            );
        }
        final byte[] secretToStore = encryptedSecret;

        authorityTxTemplate.executeWithoutResult(status -> {
            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_issuer (
                    id, installation_id, registration_id, display_name, issuer_uri,
                    authorization_uri, token_uri, jwks_uri, userinfo_uri,
                    client_id, client_secret_gcm, scopes, pkce_required, state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, 'ENABLED')
                """,
                issuerId,
                installationId,
                regId,
                request.displayName().trim(),
                issuerUri,
                authUri,
                tokenUri,
                request.jwksUri(),
                request.userinfoUri(),
                request.clientId().trim(),
                secretToStore,
                scopes
            );

            if (request.linkPrincipalUsername() != null && !request.linkPrincipalUsername().isBlank()
                && request.linkSubject() != null && !request.linkSubject().isBlank()) {
                List<UUID> pIds = authorityJdbcTemplate.query(
                    "SELECT principal_id FROM ma_principal WHERE username = ?",
                    (rs, rowNum) -> rs.getObject("principal_id", UUID.class),
                    request.linkPrincipalUsername().trim()
                );
                if (pIds.isEmpty()) {
                    throw new MoneyCraftException(
                        HttpStatus.NOT_FOUND,
                        "MC-PRINCIPAL-NOT-FOUND",
                        "Principal Not Found For OIDC Link",
                        "No principal found with username '" + request.linkPrincipalUsername().trim() + "'."
                    );
                }
                authorityJdbcTemplate.update(
                    """
                    INSERT INTO ma_oidc_link (id, principal_id, issuer_id, subject, state)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    """,
                    UUID.randomUUID(),
                    pIds.getFirst(),
                    issuerId,
                    request.linkSubject().trim()
                );
            }
        });

        TenantContextHolder.callWithSystemBypass("oidc-issuer-audit", () -> {
            businessTxTemplate.executeWithoutResult(status -> {
                adminReceiptService.recordAndMirrorAdminReceipt(
                    installationId,
                    actor.principalId(),
                    null,
                    "OIDC_ISSUER_REGISTER",
                    "OIDC_ISSUER",
                    issuerId.toString(),
                    null,
                    null,
                    Map.of("registrationId", regId, "issuerUri", issuerUri)
                );
                auditChainService.appendInstallationEvent(
                    installationId,
                    actor.principalId(),
                    actor.kind(),
                    "OIDC_ISSUER_REGISTER",
                    "OIDC_ISSUER",
                    issuerId.toString(),
                    "SUCCESS",
                    null,
                    Map.of("registrationId", regId, "issuerUri", issuerUri)
                );
            });
            return null;
        });

        return new AuthDtos.OidcIssuerDto(
            issuerId,
            regId,
            request.displayName().trim(),
            issuerUri,
            request.clientId().trim(),
            true
        );
    }

    public List<AuthDtos.OidcIssuerDto> listIssuers() {
        return authorityJdbcTemplate.query(
            """
            SELECT id, registration_id, display_name, issuer_uri, client_id, pkce_required
              FROM ma_issuer
             WHERE state = 'ENABLED'
             ORDER BY display_name ASC
            """,
            (rs, rowNum) -> new AuthDtos.OidcIssuerDto(
                rs.getObject("id", UUID.class),
                rs.getString("registration_id"),
                rs.getString("display_name"),
                rs.getString("issuer_uri"),
                rs.getString("client_id"),
                rs.getBoolean("pkce_required")
            )
        );
    }

    public AuthDtos.OidcAuthorizeResponse buildAuthorizationRedirect(String issuerRef) {
        IssuerRow issuer = findEnabledIssuer(issuerRef);

        byte[] verifierEntropy = new byte[32];
        SECURE_RANDOM.nextBytes(verifierEntropy);
        String codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierEntropy);
        byte[] s256Digest = jcsCanonicalizer.sha256(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(s256Digest);

        byte[] stateEntropy = new byte[24];
        SECURE_RANDOM.nextBytes(stateEntropy);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateEntropy);

        UUID challengeId = UUID.randomUUID();
        byte[] encryptedVerifier = tinkCryptoService.encryptGcm(
            ("ma_auth_challenge|" + challengeId + "|" + state).getBytes(StandardCharsets.UTF_8),
            codeVerifier.getBytes(StandardCharsets.UTF_8)
        );

        String redirectUri = properties.getBaseUrl() + "/oidc/callback";
        authorityJdbcTemplate.update(
            """
            INSERT INTO ma_auth_challenge (
                challenge_id, principal_id, session_primary_id, purpose,
                challenge_bytes, pkce_verifier_gcm, state_nonce, metadata_json,
                consumed, expires_at
            ) VALUES (?, NULL, NULL, 'OIDC_AUTHORIZE', ?, ?, ?, ?::jsonb, false, clock_timestamp() + interval '10 minutes')
            """,
            challengeId,
            s256Digest,
            encryptedVerifier,
            state,
            jcsCanonicalizer.canonicalizeObject(Map.of(
                "issuerId", issuer.id().toString(),
                "redirectUri", redirectUri
            ))
        );

        String authBase = issuer.authorizationUri() != null && !issuer.authorizationUri().isBlank()
            ? issuer.authorizationUri()
            : issuer.issuerUri() + "/authorize";
        String query = "response_type=code"
            + "&client_id=" + urlEncode(issuer.clientId())
            + "&redirect_uri=" + urlEncode(redirectUri)
            + "&scope=" + urlEncode(issuer.scopes())
            + "&state=" + urlEncode(state)
            + "&code_challenge=" + urlEncode(codeChallenge)
            + "&code_challenge_method=S256";
        String separator = authBase.contains("?") ? "&" : "?";

        return new AuthDtos.OidcAuthorizeResponse(authBase + separator + query, state);
    }

    public MoneyCraftPrincipal completePkceCallback(AuthDtos.OidcCallbackRequest request) {
        if (request == null || request.code() == null || request.code().isBlank()
            || request.state() == null || request.state().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-OIDC-CALLBACK-INVALID",
                "Missing OIDC Code or State",
                "Both code and state parameters are required to complete PKCE authentication."
            );
        }

        List<OidcChallengeRow> challenges = authorityJdbcTemplate.query(
            """
            SELECT challenge_id, pkce_verifier_gcm, state_nonce, metadata_json::text AS meta_json
              FROM ma_auth_challenge
             WHERE purpose = 'OIDC_AUTHORIZE'
               AND state_nonce = ?
               AND consumed = false
               AND expires_at > clock_timestamp()
            """,
            (rs, rowNum) -> new OidcChallengeRow(
                rs.getObject("challenge_id", UUID.class),
                rs.getBytes("pkce_verifier_gcm"),
                rs.getString("state_nonce"),
                rs.getString("meta_json")
            ),
            request.state().trim()
        );

        if (challenges.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-OIDC-STATE-INVALID",
                "Invalid or Expired OIDC State",
                "OIDC PKCE state parameter did not match any active unconsumed ceremony."
            );
        }

        OidcChallengeRow challenge = challenges.getFirst();
        authorityJdbcTemplate.update(
            "UPDATE ma_auth_challenge SET consumed = true, consumed_at = clock_timestamp() WHERE challenge_id = ?",
            challenge.challengeId()
        );

        byte[] decryptedVerifier = tinkCryptoService.decryptGcm(
            ("ma_auth_challenge|" + challenge.challengeId() + "|" + challenge.stateNonce())
                .getBytes(StandardCharsets.UTF_8),
            challenge.pkceVerifierGcm()
        );
        String codeVerifier = new String(decryptedVerifier, StandardCharsets.UTF_8);

        UUID issuerId;
        String redirectUri;
        try {
            JsonNode meta = objectMapper.readTree(challenge.metaJson());
            issuerId = UUID.fromString(meta.get("issuerId").asString());
            redirectUri = meta.get("redirectUri").asString();
        } catch (RuntimeException ex) {
            throw new MoneyCraftException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "MC-OIDC-METADATA-CORRUPT",
                "Corrupted OIDC Challenge Metadata",
                "Unable to parse stored OIDC PKCE challenge metadata."
            );
        }

        IssuerRow issuer = findEnabledIssuer(issuerId.toString());
        String subject = exchangeCodeForSubject(issuer, request.code().trim(), redirectUri, codeVerifier);

        List<UUID> linkedPrincipals = authorityJdbcTemplate.query(
            """
            SELECT principal_id
              FROM ma_oidc_link
             WHERE issuer_id = ? AND subject = ? AND state = 'ACTIVE'
            """,
            (rs, rowNum) -> rs.getObject("principal_id", UUID.class),
            issuer.id(),
            subject
        );

        if (linkedPrincipals.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-OIDC-SUBJECT-UNLINKED",
                "OIDC Subject Not Linked",
                "Authenticated OIDC subject is not linked to an active MoneyCraft principal."
            );
        }

        UUID principalId = linkedPrincipals.getFirst();
        authorityJdbcTemplate.update(
            "UPDATE ma_oidc_link SET last_verified_at = clock_timestamp() WHERE issuer_id = ? AND subject = ?",
            issuer.id(),
            subject
        );
        authorityJdbcTemplate.update(
            "UPDATE ma_principal SET last_authenticated_at = clock_timestamp() WHERE principal_id = ?",
            principalId
        );

        return rbacAuthorizationService.loadPrincipalProfile(principalId);
    }

    private String exchangeCodeForSubject(
        IssuerRow issuer,
        String code,
        String redirectUri,
        String codeVerifier
    ) {
        String tokenUri = issuer.tokenUri() != null && !issuer.tokenUri().isBlank()
            ? issuer.tokenUri()
            : issuer.issuerUri() + "/token";
        URI parsedUri = URI.create(tokenUri);
        String host = parsedUri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        if (!loopback) {
            if (!properties.getConnector().isEnabled()
                || !properties.getConnector().getEgressHostnameAllowlist().contains(host)) {
                throw new MoneyCraftException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "MC-CONNECTOR-EGRESS-BLOCKED",
                    "Outbound OIDC Egress Disabled",
                    "External OIDC token exchange requires moneycraft.connector.enabled=true and hostname allowlisting."
                );
            }
        }

        StringBuilder form = new StringBuilder();
        form.append("grant_type=authorization_code")
            .append("&code=").append(urlEncode(code))
            .append("&redirect_uri=").append(urlEncode(redirectUri))
            .append("&client_id=").append(urlEncode(issuer.clientId()))
            .append("&code_verifier=").append(urlEncode(codeVerifier));

        if (issuer.clientSecretGcm() != null) {
            byte[] secretBytes = tinkCryptoService.decryptGcm(
                ("ma_issuer|" + issuer.id()).getBytes(StandardCharsets.UTF_8),
                issuer.clientSecretGcm()
            );
            form.append("&client_secret=")
                .append(urlEncode(new String(secretBytes, StandardCharsets.UTF_8)));
        }

        HttpRequest httpRequest = HttpRequest.newBuilder(parsedUri)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new MoneyCraftException(
                HttpStatus.BAD_GATEWAY,
                "MC-OIDC-TOKEN-EXCHANGE-FAILED",
                "OIDC Token Endpoint Unreachable",
                "Failed to exchange PKCE authorization code with OIDC token endpoint."
            );
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-OIDC-CODE-REJECTED",
                "OIDC Authorization Code Rejected",
                "OIDC IdP rejected PKCE code exchange with HTTP " + response.statusCode() + "."
            );
        }

        try {
            JsonNode tokenJson = objectMapper.readTree(response.body());
            if (tokenJson.hasNonNull("sub")) {
                return tokenJson.get("sub").asString();
            }
            if (tokenJson.hasNonNull("id_token")) {
                return extractSubjectFromJwtPayload(tokenJson.get("id_token").asString());
            }
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-OIDC-SUBJECT-MISSING",
                "OIDC Subject Claim Missing",
                "OIDC token response did not include an id_token or sub claim."
            );
        } catch (JacksonException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_GATEWAY,
                "MC-OIDC-INVALID-TOKEN-JSON",
                "Invalid OIDC Token Response",
                "OIDC token endpoint returned malformed JSON."
            );
        }
    }

    private String extractSubjectFromJwtPayload(String jwt) throws JacksonException {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-OIDC-IDTOKEN-INVALID",
                "Malformed OIDC ID Token",
                "id_token was not a valid JWT."
            );
        }
        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        JsonNode claims = objectMapper.readTree(payloadBytes);
        if (!claims.hasNonNull("sub")) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-OIDC-SUBJECT-MISSING",
                "OIDC ID Token Missing sub",
                "id_token payload did not contain a sub claim."
            );
        }
        return claims.get("sub").asString();
    }

    private IssuerRow findEnabledIssuer(String issuerRef) {
        if (issuerRef == null || issuerRef.isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-OIDC-ISSUER-REQUIRED",
                "OIDC Issuer Identifier Required",
                "Specify an OIDC issuer UUID or registrationId."
            );
        }
        String trimmed = issuerRef.trim();
        UUID parsedId = null;
        try {
            parsedId = UUID.fromString(trimmed);
        } catch (IllegalArgumentException ignored) {
            // Match by registration_id
        }

        List<IssuerRow> rows = parsedId != null
            ? authorityJdbcTemplate.query(
                """
                SELECT id, registration_id, issuer_uri, authorization_uri, token_uri,
                       client_id, client_secret_gcm, scopes
                  FROM ma_issuer
                 WHERE id = ? AND state = 'ENABLED'
                """,
                this::mapIssuerRow,
                parsedId
            )
            : authorityJdbcTemplate.query(
                """
                SELECT id, registration_id, issuer_uri, authorization_uri, token_uri,
                       client_id, client_secret_gcm, scopes
                  FROM ma_issuer
                 WHERE registration_id = ? AND state = 'ENABLED'
                """,
                this::mapIssuerRow,
                trimmed.toLowerCase()
            );

        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-OIDC-ISSUER-NOT-FOUND",
                "OIDC Issuer Not Found",
                "No enabled OIDC issuer matched '" + trimmed + "'."
            );
        }
        return rows.getFirst();
    }

    private IssuerRow mapIssuerRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new IssuerRow(
            rs.getObject("id", UUID.class),
            rs.getString("registration_id"),
            rs.getString("issuer_uri"),
            rs.getString("authorization_uri"),
            rs.getString("token_uri"),
            rs.getString("client_id"),
            rs.getBytes("client_secret_gcm"),
            rs.getString("scopes")
        );
    }

    private static String urlEncode(String val) {
        return URLEncoder.encode(val, StandardCharsets.UTF_8);
    }

    private record IssuerRow(
        UUID id,
        String registrationId,
        String issuerUri,
        String authorizationUri,
        String tokenUri,
        String clientId,
        byte[] clientSecretGcm,
        String scopes
    ) {
    }

    private record OidcChallengeRow(
        UUID challengeId,
        byte[] pkceVerifierGcm,
        String stateNonce,
        String metaJson
    ) {
    }
}
