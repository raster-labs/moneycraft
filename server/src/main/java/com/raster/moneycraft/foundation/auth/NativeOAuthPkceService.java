package com.raster.moneycraft.foundation.auth;

import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.OidcClockSource;
import com.raster.moneycraft.foundation.security.OidcSessionFreshnessFilter;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.foundation.security.ServiceApiKeyAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * First-party host native public-client OAuth 2.0 + OIDC PKCE S256 issuer (Mobile Doc 02, Swing Doc 02 §5).
 * Reuses the existing host browser authentication surface for both independent LOCAL password/passkey
 * accounts and MC-FED-01 organisational identities without password-to-token or cookie-borrowing shortcuts.
 */
@Service
public class NativeOAuthPkceService {

    public static final String ACCESS_TOKEN_PREFIX = "mc_nat_at_";
    public static final String REFRESH_TOKEN_PREFIX = "mc_nat_rt_";
    public static final String AUTH_CODE_PREFIX = "mc_nat_cd_";
    private static final String SIGNING_POLICY_CODE = "NATIVE_OAUTH_SIGNING_KEY_V1";
    private static final Duration AUTH_CODE_TTL = Duration.ofSeconds(60);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MoneyCraftProperties properties;
    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacAuthorizationService;
    private final OidcClockSource clockSource;
    private final TinkCryptoService tinkCryptoService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate authorityJdbcTemplate;
    private final TransactionTemplate authorityTxTemplate;
    private final TransactionTemplate independentTxTemplate;

    private volatile SigningKeyMaterial cachedSigningKey;

    public NativeOAuthPkceService(
        MoneyCraftProperties properties,
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacAuthorizationService,
        OidcClockSource clockSource,
        TinkCryptoService tinkCryptoService,
        JcsCanonicalizer jcsCanonicalizer,
        ObjectMapper objectMapper,
        @Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate,
        @Qualifier("authorityTransactionManager") PlatformTransactionManager authorityTransactionManager
    ) {
        this.properties = properties;
        this.authorityGuard = authorityGuard;
        this.rbacAuthorizationService = rbacAuthorizationService;
        this.clockSource = clockSource;
        this.tinkCryptoService = tinkCryptoService;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.objectMapper = objectMapper;
        this.authorityJdbcTemplate = authorityJdbcTemplate;
        this.authorityTxTemplate = new TransactionTemplate(authorityTransactionManager);
        TransactionTemplate reqNew = new TransactionTemplate(authorityTransactionManager);
        reqNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.independentTxTemplate = reqNew;
    }

    public AuthDtos.NativeOidcDiscoveryDto buildDiscoveryDocument(HttpServletRequest request) {
        String issuer = resolveCanonicalIssuerOrigin(request);
        SigningKeyMaterial keyMaterial = ensureSigningKeyMaterial();
        return new AuthDtos.NativeOidcDiscoveryDto(
            issuer,
            issuer + "/api/v1/auth/native/authorize",
            issuer + "/api/v1/auth/native/token",
            issuer + "/api/v1/auth/native/revoke",
            issuer + "/api/v1/auth/native/jwks.json",
            List.of("code"),
            List.of("authorization_code", "refresh_token"),
            List.of("S256"),
            List.of("none"),
            List.of("ES256"),
            keyMaterial.publicKeyPem()
        );
    }

    public Map<String, Object> buildJwksDocument() {
        SigningKeyMaterial keyMaterial = ensureSigningKeyMaterial();
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "EC");
        jwk.put("crv", "P-256");
        jwk.put("use", "sig");
        jwk.put("alg", "ES256");
        jwk.put("kid", keyMaterial.kid());
        jwk.put("x", keyMaterial.xCoordBase64Url());
        jwk.put("y", keyMaterial.yCoordBase64Url());
        return Map.of("keys", List.of(jwk));
    }

    public PublicKey currentVerificationPublicKey() {
        return ensureSigningKeyMaterial().publicKey();
    }

    public AuthorizeOutcome handleBrowserAuthorizeGet(
        HttpServletRequest request,
        Map<String, String> queryParams
    ) {
        ValidatedAuthorizeRequest validated = validateAuthorizeParameters(request, queryParams);
        MoneyCraftPrincipal sessionPerson = resolveAuthenticatedBrowserSessionPerson();
        if (sessionPerson == null) {
            String canonicalQuery = buildCanonicalAuthorizeQuery(validated);
            String authorizeRelativeTarget = prefixContextPath(
                request,
                "/api/v1/auth/native/authorize?" + canonicalQuery
            );
            String loginPath = prefixContextPath(
                request,
                "/login?nativeAuthorize=" + URLEncoder.encode(authorizeRelativeTarget, StandardCharsets.UTF_8)
            );
            return new AuthorizeOutcome(URI.create(loginPath), null, validated.state(), false);
        }

        authorityGuard.assertWritable();
        String rawCode = issueAuthorizationCode(validated, sessionPerson);
        URI callbackRedirect = appendCodeAndState(validated.redirectUri(), rawCode, validated.state());
        return new AuthorizeOutcome(callbackRedirect, rawCode, validated.state(), true);
    }

    public AuthDtos.NativeAuthorizeApproveResponse approveFromAuthenticatedSession(
        HttpServletRequest request,
        AuthDtos.NativeAuthorizeApproveRequest payload
    ) {
        authorityGuard.assertWritable();
        MoneyCraftPrincipal sessionPerson = resolveAuthenticatedBrowserSessionPerson();
        if (sessionPerson == null) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-NATIVE-BROWSER-SESSION-REQUIRED",
                "Authenticated Browser Session Required",
                "Interactive browser login must complete before approving native client authorization."
            );
        }
        if (payload == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-PKCE-INVALID",
                "Authorization Request Missing",
                "Native authorization approval payload is required."
            );
        }
        Map<String, String> params;
        if (payload.nativeAuthorizeQuery() != null && !payload.nativeAuthorizeQuery().isBlank()) {
            params = parseQueryString(payload.nativeAuthorizeQuery());
        } else {
            params = new LinkedHashMap<>();
            params.put("response_type", "code");
            params.put("client_id", payload.clientId());
            params.put("redirect_uri", payload.redirectUri());
            params.put("scope", payload.scope());
            params.put("state", payload.state());
            params.put("nonce", payload.nonce());
            params.put("code_challenge", payload.codeChallenge());
            params.put("code_challenge_method", payload.codeChallengeMethod());
        }
        ValidatedAuthorizeRequest validated = validateAuthorizeParameters(request, params);
        String rawCode = issueAuthorizationCode(validated, sessionPerson);
        URI callbackRedirect = appendCodeAndState(validated.redirectUri(), rawCode, validated.state());
        return new AuthDtos.NativeAuthorizeApproveResponse(
            callbackRedirect.toString(),
            rawCode,
            validated.state()
        );
    }

    public AuthDtos.NativeTokenResponseDto exchangeOrRotateToken(Map<String, String> params) {
        authorityGuard.assertWritable();
        if (params == null || params.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-TOKEN-REQUEST-INVALID",
                "Invalid Token Request",
                "Token endpoint parameters are required."
            );
        }
        if (params.containsKey("password") || params.containsKey("username")) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-PASSWORD-GRANT-FORBIDDEN",
                "Password Grant Forbidden",
                "Direct password-to-token exchange is prohibited; use external browser authorization_code + PKCE S256."
            );
        }
        String grantType = trimOrNull(params.get("grant_type"));
        if ("authorization_code".equals(grantType)) {
            return exchangeAuthorizationCode(params);
        }
        if ("refresh_token".equals(grantType)) {
            return rotateRefreshToken(params);
        }
        throw new MoneyCraftException(
            HttpStatus.BAD_REQUEST,
            "MC-NATIVE-UNSUPPORTED-GRANT",
            "Unsupported Grant Type",
            "Supported native grant_type values are 'authorization_code' and 'refresh_token'."
        );
    }

    public void revokeToken(Map<String, String> params) {
        if (params == null) {
            return;
        }
        String clientId = trimOrNull(params.get("client_id"));
        String rawToken = trimOrNull(params.get("token"));
        if (clientId == null || rawToken == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-REVOKE-INVALID",
                "Invalid Token Revocation Request",
                "Both client_id and token are required."
            );
        }
        loadEnabledClient(clientId);
        byte[] tokenSha = jcsCanonicalizer.sha256(rawToken.getBytes(StandardCharsets.UTF_8));
        Instant now = clockSource.instant();
        OffsetDateTime nowTs = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

        authorityTxTemplate.executeWithoutResult(status -> {
            List<UUID> familyIds = authorityJdbcTemplate.query(
                """
                SELECT token_family_id
                  FROM ma_native_token
                 WHERE client_id = ?
                   AND (access_token_sha256 = ? OR refresh_token_sha256 = ?)
                """,
                (rs, rowNum) -> rs.getObject("token_family_id", UUID.class),
                clientId,
                tokenSha,
                tokenSha
            );
            for (UUID familyId : familyIds) {
                authorityJdbcTemplate.update(
                    """
                    UPDATE ma_native_token
                       SET state = 'REVOKED',
                           revoked_at = COALESCE(revoked_at, ?),
                           revoke_reason = COALESCE(revoke_reason, 'CLIENT_REVOCATION')
                     WHERE token_family_id = ?
                       AND state IN ('ACTIVE', 'ROTATED')
                    """,
                    nowTs,
                    familyId
                );
            }
        });
    }

    private AuthDtos.NativeTokenResponseDto exchangeAuthorizationCode(Map<String, String> params) {
        String rawCode = trimOrNull(params.get("code"));
        String redirectUri = trimOrNull(params.get("redirect_uri"));
        String clientId = trimOrNull(params.get("client_id"));
        String codeVerifier = trimOrNull(params.get("code_verifier"));

        if (rawCode == null || redirectUri == null || clientId == null || codeVerifier == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-TOKEN-REQUEST-INVALID",
                "Missing Authorization Code Exchange Parameters",
                "code, redirect_uri, client_id, and code_verifier are required."
            );
        }

        NativeClientRow client = loadEnabledClient(clientId);
        byte[] codeSha = jcsCanonicalizer.sha256(rawCode.getBytes(StandardCharsets.UTF_8));
        Instant now = clockSource.instant();
        OffsetDateTime nowTs = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

        ClaimedCodeOutcome claimed = independentTxTemplate.execute(status -> {
            List<AuthCodeRow> rows = authorityJdbcTemplate.query(
                """
                SELECT code_id, installation_id, incarnation, client_id, principal_id,
                       issuer_uri, redirect_uri, scopes::text AS scopes_json, code_challenge,
                       code_challenge_method, nonce_param, oidc_issuer_id, oidc_link_id,
                       oidc_issuer_version, oidc_link_version, consumed, issued_token_family_id, expires_at
                  FROM ma_native_auth_code
                 WHERE code_sha256 = ?
                   FOR UPDATE
                """,
                (rs, rowNum) -> new AuthCodeRow(
                    rs.getObject("code_id", UUID.class),
                    rs.getObject("installation_id", UUID.class),
                    rs.getObject("incarnation", UUID.class),
                    rs.getString("client_id"),
                    rs.getObject("principal_id", UUID.class),
                    rs.getString("issuer_uri"),
                    rs.getString("redirect_uri"),
                    rs.getString("scopes_json"),
                    rs.getString("code_challenge"),
                    rs.getString("code_challenge_method"),
                    rs.getString("nonce_param"),
                    rs.getObject("oidc_issuer_id", UUID.class),
                    rs.getObject("oidc_link_id", UUID.class),
                    rs.getObject("oidc_issuer_version", Long.class),
                    rs.getObject("oidc_link_version", Long.class),
                    rs.getBoolean("consumed"),
                    rs.getObject("issued_token_family_id", UUID.class),
                    rs.getObject("expires_at", OffsetDateTime.class)
                ),
                codeSha
            );
            if (rows.isEmpty()) {
                return ClaimedCodeOutcome.missing();
            }
            AuthCodeRow codeRow = rows.getFirst();
            if (codeRow.consumed()) {
                if (codeRow.issuedTokenFamilyId() != null) {
                    authorityJdbcTemplate.update(
                        """
                        UPDATE ma_native_token
                           SET state = 'REVOKED',
                               revoked_at = COALESCE(revoked_at, ?),
                               revoke_reason = 'AUTH_CODE_REPLAY_DETECTED'
                         WHERE token_family_id = ?
                           AND state IN ('ACTIVE', 'ROTATED')
                        """,
                        nowTs,
                        codeRow.issuedTokenFamilyId()
                    );
                }
                return ClaimedCodeOutcome.replayed();
            }
            authorityJdbcTemplate.update(
                """
                UPDATE ma_native_auth_code
                   SET consumed = true,
                       consumed_at = ?
                 WHERE code_id = ?
                """,
                nowTs,
                codeRow.codeId()
            );
            return ClaimedCodeOutcome.claimed(codeRow);
        });

        if (claimed == null || claimed.status() == ClaimStatus.MISSING) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-CODE-INVALID",
                "Invalid Authorization Code",
                "Authorization code is unknown or invalid."
            );
        }
        if (claimed.status() == ClaimStatus.REPLAYED) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-CODE-REPLAYED",
                "Authorization Code Replay Rejected",
                "Authorization code has already been consumed; any associated token family was revoked."
            );
        }

        AuthCodeRow codeRow = claimed.row();
        ActiveStateRow state = loadActiveState();
        if (!codeRow.installationId().equals(state.installationId())
            || !codeRow.incarnation().equals(state.incarnation())) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-INCARNATION-MISMATCH",
                "Authority Incarnation Changed",
                "Authorization code predates the active installation incarnation."
            );
        }
        if (!codeRow.expiresAt().toInstant().isAfter(now)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-CODE-EXPIRED",
                "Authorization Code Expired",
                "Authorization code expired beyond its 60-second validity window."
            );
        }
        if (!codeRow.clientId().equals(clientId) || !codeRow.redirectUri().equals(redirectUri)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-BINDING-MISMATCH",
                "Client or Redirect Binding Mismatch",
                "client_id or redirect_uri did not match the issued authorization code."
            );
        }
        verifyPkceS256(codeVerifier, codeRow.codeChallenge());

        MoneyCraftPrincipal livePrincipal = verifyLivePrincipalAndOidcBinding(
            codeRow.principalId(),
            codeRow.oidcIssuerId(),
            codeRow.oidcLinkId(),
            codeRow.oidcIssuerVersion(),
            codeRow.oidcLinkVersion()
        );

        UUID familyId = UUID.randomUUID();
        List<String> grantedScopes = rbacAuthorizationService.parseStringArray(codeRow.scopesJson());
        IssuedTokens issued = persistTokenRow(
            familyId,
            1,
            state,
            client,
            livePrincipal,
            codeRow.issuerUri(),
            grantedScopes,
            codeRow.nonceParam(),
            codeRow.oidcIssuerId(),
            codeRow.oidcLinkId(),
            codeRow.oidcIssuerVersion(),
            codeRow.oidcLinkVersion(),
            now
        );

        authorityJdbcTemplate.update(
            "UPDATE ma_native_auth_code SET issued_token_family_id = ? WHERE code_id = ?",
            familyId,
            codeRow.codeId()
        );

        return issued.response();
    }

    private AuthDtos.NativeTokenResponseDto rotateRefreshToken(Map<String, String> params) {
        String clientId = trimOrNull(params.get("client_id"));
        String rawRefreshToken = trimOrNull(params.get("refresh_token"));
        if (clientId == null || rawRefreshToken == null || !rawRefreshToken.startsWith(REFRESH_TOKEN_PREFIX)) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-NATIVE-REFRESH-INVALID",
                "Invalid Refresh Token",
                "Valid client_id and refresh_token are required."
            );
        }

        NativeClientRow client = loadEnabledClient(clientId);
        byte[] refreshSha = jcsCanonicalizer.sha256(rawRefreshToken.getBytes(StandardCharsets.UTF_8));
        Instant now = clockSource.instant();
        OffsetDateTime nowTs = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

        RefreshClaimOutcome outcome = independentTxTemplate.execute(status -> {
            List<TokenRow> rows = authorityJdbcTemplate.query(
                """
                SELECT token_id, token_family_id, generation, installation_id, incarnation,
                       client_id, principal_id, issuer_uri, scopes::text AS scopes_json,
                       nonce_param, refresh_expires_at, oidc_issuer_id, oidc_link_id,
                       oidc_issuer_version, oidc_link_version, state
                  FROM ma_native_token
                 WHERE refresh_token_sha256 = ?
                   FOR UPDATE
                """,
                (rs, rowNum) -> new TokenRow(
                    rs.getObject("token_id", UUID.class),
                    rs.getObject("token_family_id", UUID.class),
                    rs.getInt("generation"),
                    rs.getObject("installation_id", UUID.class),
                    rs.getObject("incarnation", UUID.class),
                    rs.getString("client_id"),
                    rs.getObject("principal_id", UUID.class),
                    rs.getString("issuer_uri"),
                    rs.getString("scopes_json"),
                    rs.getString("nonce_param"),
                    rs.getObject("refresh_expires_at", OffsetDateTime.class),
                    rs.getObject("oidc_issuer_id", UUID.class),
                    rs.getObject("oidc_link_id", UUID.class),
                    rs.getObject("oidc_issuer_version", Long.class),
                    rs.getObject("oidc_link_version", Long.class),
                    rs.getString("state")
                ),
                refreshSha
            );
            if (rows.isEmpty()) {
                return RefreshClaimOutcome.missing();
            }
            TokenRow tokenRow = rows.getFirst();
            if (!"ACTIVE".equals(tokenRow.state())) {
                authorityJdbcTemplate.update(
                    """
                    UPDATE ma_native_token
                       SET state = 'REVOKED',
                           revoked_at = COALESCE(revoked_at, ?),
                           revoke_reason = 'REFRESH_TOKEN_REPLAY_DETECTED'
                     WHERE token_family_id = ?
                       AND state IN ('ACTIVE', 'ROTATED')
                    """,
                    nowTs,
                    tokenRow.tokenFamilyId()
                );
                return RefreshClaimOutcome.replayed();
            }
            if (!tokenRow.clientId().equals(clientId)) {
                authorityJdbcTemplate.update(
                    """
                    UPDATE ma_native_token
                       SET state = 'REVOKED',
                           revoked_at = ?,
                           revoke_reason = 'CLIENT_MISMATCH'
                     WHERE token_family_id = ?
                    """,
                    nowTs,
                    tokenRow.tokenFamilyId()
                );
                return RefreshClaimOutcome.clientMismatch();
            }
            if (tokenRow.refreshExpiresAt() == null || !tokenRow.refreshExpiresAt().toInstant().isAfter(now)) {
                authorityJdbcTemplate.update(
                    """
                    UPDATE ma_native_token
                       SET state = 'REVOKED',
                           revoked_at = ?,
                           revoke_reason = 'REFRESH_EXPIRED'
                     WHERE token_id = ?
                    """,
                    nowTs,
                    tokenRow.tokenId()
                );
                return RefreshClaimOutcome.expired();
            }

            authorityJdbcTemplate.update(
                """
                UPDATE ma_native_token
                   SET state = 'ROTATED',
                       rotated_at = ?
                 WHERE token_id = ? AND state = 'ACTIVE'
                """,
                nowTs,
                tokenRow.tokenId()
            );
            return RefreshClaimOutcome.claimed(tokenRow);
        });

        if (outcome == null || outcome.status() == RefreshStatus.MISSING) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-NATIVE-REFRESH-INVALID",
                "Unknown Refresh Token",
                "Refresh token was not recognised."
            );
        }
        if (outcome.status() == RefreshStatus.REPLAYED) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-NATIVE-REFRESH-REPLAYED",
                "Refresh Token Replay Detected",
                "Replayed or revoked refresh token detected; entire token family has been revoked."
            );
        }
        if (outcome.status() == RefreshStatus.CLIENT_MISMATCH) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-NATIVE-CLIENT-MISMATCH",
                "Refresh Token Client Mismatch",
                "Refresh token client binding did not match client_id."
            );
        }
        if (outcome.status() == RefreshStatus.EXPIRED) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-NATIVE-REFRESH-EXPIRED",
                "Refresh Token Expired",
                "Refresh token lifetime has elapsed; interactive reauthentication is required."
            );
        }

        TokenRow rotatedFrom = outcome.row();
        ActiveStateRow state = loadActiveState();
        if (!rotatedFrom.installationId().equals(state.installationId())
            || !rotatedFrom.incarnation().equals(state.incarnation())) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-NATIVE-INCARNATION-MISMATCH",
                "Installation Incarnation Changed",
                "Refresh token predates the current installation incarnation."
            );
        }

        MoneyCraftPrincipal livePrincipal = verifyLivePrincipalAndOidcBinding(
            rotatedFrom.principalId(),
            rotatedFrom.oidcIssuerId(),
            rotatedFrom.oidcLinkId(),
            rotatedFrom.oidcIssuerVersion(),
            rotatedFrom.oidcLinkVersion()
        );

        List<String> grantedScopes = rbacAuthorizationService.parseStringArray(rotatedFrom.scopesJson());
        return persistTokenRow(
            rotatedFrom.tokenFamilyId(),
            rotatedFrom.generation() + 1,
            state,
            client,
            livePrincipal,
            rotatedFrom.issuerUri(),
            grantedScopes,
            rotatedFrom.nonceParam(),
            rotatedFrom.oidcIssuerId(),
            rotatedFrom.oidcLinkId(),
            rotatedFrom.oidcIssuerVersion(),
            rotatedFrom.oidcLinkVersion(),
            now
        ).response();
    }

    private IssuedTokens persistTokenRow(
        UUID familyId,
        int generation,
        ActiveStateRow state,
        NativeClientRow client,
        MoneyCraftPrincipal principal,
        String issuerUri,
        List<String> scopes,
        String nonceParam,
        UUID oidcIssuerId,
        UUID oidcLinkId,
        Long oidcIssuerVersion,
        Long oidcLinkVersion,
        Instant now
    ) {
        String rawAccess = ACCESS_TOKEN_PREFIX + randomBase64Url(32);
        String rawRefresh = REFRESH_TOKEN_PREFIX + randomBase64Url(32);
        byte[] accessSha = jcsCanonicalizer.sha256(rawAccess.getBytes(StandardCharsets.UTF_8));
        byte[] refreshSha = jcsCanonicalizer.sha256(rawRefresh.getBytes(StandardCharsets.UTF_8));

        long ttlSec = client.accessTokenTtlSec();
        Instant accessExp = now.plusSeconds(ttlSec);
        Instant refreshExp = now.plusSeconds(client.refreshTokenTtlSec());
        String idToken = mintSignedEs256IdToken(
            issuerUri,
            principal,
            client.clientId(),
            nonceParam,
            now,
            accessExp
        );
        String scopesJson = serializeJson(scopes);

        authorityTxTemplate.executeWithoutResult(status -> authorityJdbcTemplate.update(
            """
            INSERT INTO ma_native_token (
                token_id, token_family_id, generation, installation_id, incarnation,
                client_id, principal_id, issuer_uri, scopes, nonce_param,
                access_token_sha256, refresh_token_sha256, access_expires_at, refresh_expires_at,
                oidc_issuer_id, oidc_link_id, oidc_issuer_version, oidc_link_version, state, issued_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
            """,
            UUID.randomUUID(),
            familyId,
            generation,
            state.installationId(),
            state.incarnation(),
            client.clientId(),
            principal.principalId(),
            issuerUri,
            scopesJson,
            nonceParam,
            accessSha,
            refreshSha,
            OffsetDateTime.ofInstant(accessExp, ZoneOffset.UTC),
            OffsetDateTime.ofInstant(refreshExp, ZoneOffset.UTC),
            oidcIssuerId,
            oidcLinkId,
            oidcIssuerVersion,
            oidcLinkVersion,
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        ));

        AuthDtos.NativeTokenResponseDto dto = new AuthDtos.NativeTokenResponseDto(
            "Bearer",
            rawAccess,
            rawRefresh,
            idToken,
            ttlSec,
            String.join(" ", scopes)
        );
        return new IssuedTokens(dto);
    }

    private String issueAuthorizationCode(ValidatedAuthorizeRequest req, MoneyCraftPrincipal person) {
        ActiveStateRow state = loadActiveState();
        String rawCode = AUTH_CODE_PREFIX + randomBase64Url(32);
        byte[] codeSha = jcsCanonicalizer.sha256(rawCode.getBytes(StandardCharsets.UTF_8));
        Instant now = clockSource.instant();
        Instant expiresAt = now.plus(AUTH_CODE_TTL);
        String scopesJson = serializeJson(req.scopes());

        authorityTxTemplate.executeWithoutResult(status -> authorityJdbcTemplate.update(
            """
            INSERT INTO ma_native_auth_code (
                code_id, code_sha256, installation_id, incarnation, client_id, principal_id,
                issuer_uri, redirect_uri, scopes, code_challenge, code_challenge_method,
                state_param, nonce_param, oidc_issuer_id, oidc_link_id,
                oidc_issuer_version, oidc_link_version, consumed, issued_at, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'S256', ?, ?, ?, ?, ?, ?, false, ?, ?)
            """,
            UUID.randomUUID(),
            codeSha,
            state.installationId(),
            state.incarnation(),
            req.client().clientId(),
            person.principalId(),
            req.issuerUri(),
            req.redirectUri().toString(),
            scopesJson,
            req.codeChallenge(),
            req.state(),
            req.nonce(),
            person.oidcIssuerId(),
            person.oidcLinkId(),
            person.oidcIssuerVersion(),
            person.oidcLinkVersion(),
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
            OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC)
        ));
        return rawCode;
    }

    private ValidatedAuthorizeRequest validateAuthorizeParameters(
        HttpServletRequest request,
        Map<String, String> params
    ) {
        String clientId = trimOrNull(params.get("client_id"));
        if (clientId == null || clientId.startsWith(ServiceApiKeyAuthFilter.KEY_PREFIX_MARKER)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-CLIENT-INVALID",
                "Invalid Native Client Identifier",
                "A registered public native client_id is required."
            );
        }
        NativeClientRow client = loadEnabledClient(clientId);

        String rawRedirect = trimOrNull(params.get("redirect_uri"));
        URI redirectUri = validateRedirectUri(client, rawRedirect);

        String responseType = trimOrNull(params.get("response_type"));
        if (!"code".equals(responseType)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-RESPONSE-TYPE-INVALID",
                "Unsupported Response Type",
                "Native OAuth 2.0 authorization requires response_type=code."
            );
        }

        String state = trimOrNull(params.get("state"));
        if (state == null || state.length() > 256 || !state.matches("^[A-Za-z0-9._~-]{8,256}$")) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-STATE-INVALID",
                "Invalid OAuth State Parameter",
                "state parameter must be 8..256 URL-safe characters."
            );
        }

        String nonce = trimOrNull(params.get("nonce"));
        if (nonce != null && (nonce.length() > 256 || !nonce.matches("^[A-Za-z0-9._~-]{8,256}$"))) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-NONCE-INVALID",
                "Invalid OIDC Nonce Parameter",
                "nonce parameter must be 8..256 URL-safe characters when supplied."
            );
        }

        String challengeMethod = trimOrNull(params.get("code_challenge_method"));
        if (!"S256".equals(challengeMethod)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-PKCE-METHOD-INVALID",
                "PKCE S256 Required",
                "code_challenge_method must equal S256."
            );
        }

        String codeChallenge = trimOrNull(params.get("code_challenge"));
        if (codeChallenge == null || !codeChallenge.matches("^[A-Za-z0-9_-]{43,128}$")) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-PKCE-INVALID",
                "Invalid PKCE Code Challenge",
                "code_challenge must be a 43..128 character unpadded base64url S256 digest."
            );
        }

        List<String> allowedScopes = rbacAuthorizationService.parseStringArray(client.allowedScopesJson());
        String rawScope = trimOrNull(params.get("scope"));
        Set<String> parsedScopes = new LinkedHashSet<>();
        if (rawScope == null) {
            parsedScopes.add("openid");
            parsedScopes.add("profile");
        } else {
            for (String s : rawScope.split("\\s+")) {
                if (!s.isBlank()) {
                    parsedScopes.add(s.trim());
                }
            }
        }
        if (!parsedScopes.contains("openid") || !allowedScopes.containsAll(parsedScopes)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-SCOPE-INVALID",
                "Unsupported Native OAuth Scope",
                "Requested scope must include 'openid' and remain within client allowed_scopes."
            );
        }

        String issuerOrigin = resolveCanonicalIssuerOrigin(request);
        return new ValidatedAuthorizeRequest(
            client,
            redirectUri,
            List.copyOf(parsedScopes),
            state,
            nonce,
            codeChallenge,
            issuerOrigin
        );
    }

    private URI validateRedirectUri(NativeClientRow client, String rawRedirect) {
        if (rawRedirect == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-REDIRECT-INVALID",
                "Redirect URI Required",
                "redirect_uri is required."
            );
        }
        URI uri;
        try {
            uri = URI.create(rawRedirect);
        } catch (IllegalArgumentException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-REDIRECT-INVALID",
                "Malformed Redirect URI",
                "redirect_uri could not be parsed as a valid URI."
            );
        }
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getRawQuery() != null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-REDIRECT-INVALID",
                "Forbidden Redirect URI Components",
                "redirect_uri must not contain userinfo, query parameters, or fragments."
            );
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("http".equals(scheme)) {
            if (!client.allowLoopbackRedirect()
                || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() < 1
                || uri.getPort() > 65535
                || !client.loopbackCallbackPath().equals(uri.getPath())) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-NATIVE-REDIRECT-INVALID",
                    "Unregistered Loopback Redirect URI",
                    "Loopback redirect must bind http://127.0.0.1:<ephemeralPort>" + client.loopbackCallbackPath() + "."
                );
            }
            return uri;
        }
        if ("https".equals(scheme)) {
            List<String> admittedHttps = rbacAuthorizationService.parseStringArray(client.admittedHttpsRedirectsJson());
            if (admittedHttps.contains(rawRedirect)) {
                return uri;
            }
        }
        throw new MoneyCraftException(
            HttpStatus.BAD_REQUEST,
            "MC-NATIVE-REDIRECT-INVALID",
            "Unregistered Redirect URI",
            "redirect_uri '" + rawRedirect + "' is not registered for client '" + client.clientId() + "'."
        );
    }

    private MoneyCraftPrincipal resolveAuthenticatedBrowserSessionPerson() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
            || !(auth.getPrincipal() instanceof MoneyCraftPrincipal sessionPrincipal)) {
            return null;
        }
        if (sessionPrincipal.isServiceKeyAuthenticated() || sessionPrincipal.isNativeBearerAuthenticated()) {
            return null;
        }
        try {
            MoneyCraftPrincipal refreshed = rbacAuthorizationService.refreshPrincipal(sessionPrincipal);
            if (!"PERSON".equals(refreshed.kind())) {
                return null;
            }
            if (refreshed.isOidcAuthenticated()) {
                verifyLivePrincipalAndOidcBinding(
                    refreshed.principalId(),
                    refreshed.oidcIssuerId(),
                    refreshed.oidcLinkId(),
                    refreshed.oidcIssuerVersion(),
                    refreshed.oidcLinkVersion()
                );
            }
            return refreshed;
        } catch (MoneyCraftException ex) {
            return null;
        }
    }

    private MoneyCraftPrincipal verifyLivePrincipalAndOidcBinding(
        UUID principalId,
        UUID oidcIssuerId,
        UUID oidcLinkId,
        Long expectedIssuerVersion,
        Long expectedLinkVersion
    ) {
        MoneyCraftPrincipal live = rbacAuthorizationService.loadPrincipalProfile(principalId);
        if (!"PERSON".equals(live.kind())) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-PERSON-PRINCIPAL-REQUIRED",
                "Person Principal Required",
                "Native OAuth tokens can only be issued to enabled PERSON principals."
            );
        }
        if (oidcIssuerId == null && oidcLinkId == null) {
            return live;
        }
        List<OidcBindingRow> rows = authorityJdbcTemplate.query(
            """
            SELECT i.state AS issuer_state, i.version_no AS issuer_version,
                   i.lifecycle_profile, i.checked_at AS issuer_checked_at,
                   l.state AS link_state, l.version_no AS link_version,
                   l.checked_at AS link_checked_at
              FROM ma_oidc_link l
              JOIN ma_issuer i ON i.id = l.issuer_id
             WHERE l.id = ? AND i.id = ? AND l.principal_id = ?
            """,
            (rs, rowNum) -> {
                OffsetDateTime issChecked = rs.getObject("issuer_checked_at", OffsetDateTime.class);
                OffsetDateTime lnkChecked = rs.getObject("link_checked_at", OffsetDateTime.class);
                return new OidcBindingRow(
                    rs.getString("issuer_state"),
                    rs.getLong("issuer_version"),
                    rs.getString("lifecycle_profile"),
                    issChecked == null ? null : issChecked.toInstant(),
                    rs.getString("link_state"),
                    rs.getLong("link_version"),
                    lnkChecked == null ? null : lnkChecked.toInstant()
                );
            },
            oidcLinkId,
            oidcIssuerId,
            principalId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-OIDC-SESSION-INVALID",
                "OIDC Federation Binding Invalid",
                "Federated OIDC link or issuer no longer exists."
            );
        }
        OidcBindingRow binding = rows.getFirst();
        if (!"ENABLED".equals(binding.issuerState()) || !"ACTIVE".equals(binding.linkState())) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-OIDC-SESSION-INVALID",
                "OIDC Principal or Issuer Disabled",
                "Federated OIDC link or issuer is disabled."
            );
        }
        if ((expectedIssuerVersion != null && binding.issuerVersion() != expectedIssuerVersion)
            || (expectedLinkVersion != null && binding.linkVersion() != expectedLinkVersion)) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-OIDC-ISSUER-VERSION-CHANGED",
                "OIDC Semantic Version Changed",
                "Federated binding predates the current OIDC issuer or link configuration version."
            );
        }
        if ("MC-FED-01".equals(binding.lifecycleProfile())) {
            Instant now = clockSource.instant();
            if (!OidcSessionFreshnessFilter.isFresh(binding.issuerCheckedAt(), now)
                || !OidcSessionFreshnessFilter.isFresh(binding.linkCheckedAt(), now)) {
                throw new MoneyCraftException(
                    HttpStatus.UNAUTHORIZED,
                    "MC-OIDC-FRESHNESS-EXPIRED",
                    "OIDC Freshness Expired",
                    "Federated session lacks current 120-second provider lifecycle freshness."
                );
            }
        }
        return live.withOidcSessionBinding(oidcIssuerId, oidcLinkId, binding.issuerVersion(), binding.linkVersion());
    }

    private static void verifyPkceS256(String codeVerifier, String expectedChallenge) {
        if (codeVerifier.length() < 43 || codeVerifier.length() > 128
            || !codeVerifier.matches("^[A-Za-z0-9._~-]{43,128}$")) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-PKCE-VERIFIER-INVALID",
                "Invalid PKCE Code Verifier",
                "code_verifier must be 43..128 RFC 7636 unreserved characters."
            );
        }
        try {
            byte[] sha = MessageDigest.getInstance("SHA-256")
                .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(sha);
            if (!MessageDigest.isEqual(
                expectedChallenge.getBytes(StandardCharsets.US_ASCII),
                computed.getBytes(StandardCharsets.US_ASCII)
            )) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-NATIVE-PKCE-VERIFIER-MISMATCH",
                    "PKCE Code Verifier Mismatch",
                    "S256(code_verifier) did not match the transaction code_challenge."
                );
            }
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String mintSignedEs256IdToken(
        String issuerUri,
        MoneyCraftPrincipal principal,
        String clientId,
        String nonce,
        Instant issuedAt,
        Instant expiresAt
    ) {
        SigningKeyMaterial key = ensureSigningKeyMaterial();
        Base64.Encoder urlEnc = Base64.getUrlEncoder().withoutPadding();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "ES256");
        header.put("typ", "JWT");
        header.put("kid", key.kid());

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuerUri);
        claims.put("sub", principal.principalId().toString());
        claims.put("aud", List.of(clientId, "moneycraft-api"));
        claims.put("azp", clientId);
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        if (nonce != null && !nonce.isBlank()) {
            claims.put("nonce", nonce);
        }
        if (principal.username() != null && !principal.username().isBlank()) {
            claims.put("preferred_username", principal.username());
        }
        claims.put("name", principal.displayName());

        String headerPart = urlEnc.encodeToString(serializeJson(header).getBytes(StandardCharsets.UTF_8));
        String payloadPart = urlEnc.encodeToString(serializeJson(claims).getBytes(StandardCharsets.UTF_8));
        String signingInput = headerPart + "." + payloadPart;

        try {
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(key.privateKey());
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            byte[] sigBytes = signer.sign();
            return signingInput + "." + urlEnc.encodeToString(sigBytes);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed signing native ES256 id_token", ex);
        }
    }

    private SigningKeyMaterial ensureSigningKeyMaterial() {
        SigningKeyMaterial existing = this.cachedSigningKey;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (this.cachedSigningKey != null) {
                return this.cachedSigningKey;
            }
            List<UUID> stateIds = authorityJdbcTemplate.query(
                "SELECT installation_id FROM ma_state WHERE singleton = 1 AND security_state = 'ACTIVE'",
                (rs, rowNum) -> rs.getObject("installation_id", UUID.class)
            );
            if (stateIds.isEmpty()) {
                return generateEphemeralKeyMaterial();
            }
            UUID installationId = stateIds.getFirst();
            byte[] ad = ("ma_policy|" + installationId + "|" + SIGNING_POLICY_CODE).getBytes(StandardCharsets.UTF_8);

            List<String> storedConfigs = authorityJdbcTemplate.query(
                """
                SELECT config_json::text
                  FROM ma_policy
                 WHERE installation_id = ? AND company_scope IS NULL AND policy_code = ?
                """,
                (rs, rowNum) -> rs.getString(1),
                installationId,
                SIGNING_POLICY_CODE
            );
            if (!storedConfigs.isEmpty()) {
                SigningKeyMaterial restored = restoreSigningKey(storedConfigs.getFirst(), ad);
                this.cachedSigningKey = restored;
                return restored;
            }

            SigningKeyMaterial generated = generateEphemeralKeyMaterial();
            byte[] encryptedPkcs8 = tinkCryptoService.encryptGcm(ad, generated.privateKey().getEncoded());
            Map<String, Object> configMap = new LinkedHashMap<>();
            configMap.put("kid", generated.kid());
            configMap.put("x509Base64", Base64.getEncoder().encodeToString(generated.publicKey().getEncoded()));
            configMap.put("pkcs8CipherBase64", Base64.getEncoder().encodeToString(encryptedPkcs8));
            String configJson = serializeJson(configMap);

            authorityTxTemplate.executeWithoutResult(status -> authorityJdbcTemplate.update(
                """
                INSERT INTO ma_policy (
                    id, installation_id, company_scope, policy_code, policy_state,
                    session_idle_timeout_sec, session_absolute_timeout_sec,
                    outbound_connectors_enabled, egress_hostname_allowlist, config_json
                ) VALUES (?, ?, NULL, ?, 'ENFORCED', 1800, 43200, false, '[]'::jsonb, ?::jsonb)
                ON CONFLICT ((COALESCE(company_scope, '00000000-0000-0000-0000-000000000000'::uuid)), policy_code)
                DO NOTHING
                """,
                UUID.randomUUID(),
                installationId,
                SIGNING_POLICY_CODE,
                configJson
            ));

            List<String> committed = authorityJdbcTemplate.query(
                """
                SELECT config_json::text
                  FROM ma_policy
                 WHERE installation_id = ? AND company_scope IS NULL AND policy_code = ?
                """,
                (rs, rowNum) -> rs.getString(1),
                installationId,
                SIGNING_POLICY_CODE
            );
            SigningKeyMaterial loaded = restoreSigningKey(committed.getFirst(), ad);
            this.cachedSigningKey = loaded;
            return loaded;
        }
    }

    private SigningKeyMaterial restoreSigningKey(String configJson, byte[] ad) {
        try {
            JsonNode node = objectMapper.readTree(configJson);
            String kid = node.get("kid").asString();
            byte[] x509 = Base64.getDecoder().decode(node.get("x509Base64").asString());
            byte[] cipher = Base64.getDecoder().decode(node.get("pkcs8CipherBase64").asString());
            byte[] pkcs8 = tinkCryptoService.decryptGcm(ad, cipher);

            KeyFactory kf = KeyFactory.getInstance("EC");
            ECPublicKey pub = (ECPublicKey) kf.generatePublic(new X509EncodedKeySpec(x509));
            PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            return toSigningKeyMaterial(kid, pub, priv);
        } catch (JacksonException | GeneralSecurityException ex) {
            throw new IllegalStateException("Failed restoring native OAuth EC P-256 signing key", ex);
        }
    }

    private static SigningKeyMaterial generateEphemeralKeyMaterial() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"), SECURE_RANDOM);
            KeyPair kp = kpg.generateKeyPair();
            ECPublicKey pub = (ECPublicKey) kp.getPublic();
            String kid = "mc-native-es256-" + UUID.randomUUID().toString().substring(0, 8);
            return toSigningKeyMaterial(kid, pub, kp.getPrivate());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed generating EC P-256 keypair", ex);
        }
    }

    private static SigningKeyMaterial toSigningKeyMaterial(String kid, ECPublicKey pub, PrivateKey priv) {
        Base64.Encoder urlEnc = Base64.getUrlEncoder().withoutPadding();
        String x = urlEnc.encodeToString(toFixed32Bytes(pub.getW().getAffineX()));
        String y = urlEnc.encodeToString(toFixed32Bytes(pub.getW().getAffineY()));
        String pem = "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pub.getEncoded())
            + "\n-----END PUBLIC KEY-----";
        return new SigningKeyMaterial(kid, pub, priv, pem, x, y);
    }

    private static byte[] toFixed32Bytes(BigInteger coordinate) {
        byte[] raw = coordinate.toByteArray();
        if (raw.length == 32) {
            return raw;
        }
        if (raw.length > 32) {
            return Arrays.copyOfRange(raw, raw.length - 32, raw.length);
        }
        byte[] padded = new byte[32];
        System.arraycopy(raw, 0, padded, 32 - raw.length, raw.length);
        return padded;
    }

    private String resolveCanonicalIssuerOrigin(HttpServletRequest request) {
        String configuredBase = stripTrailingSlash(properties.getBaseUrl());
        if (request == null) {
            return configuredBase;
        }
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        if (scheme == null || host == null || host.isBlank()) {
            return configuredBase;
        }
        boolean defaultPort = ("https".equalsIgnoreCase(scheme) && port == 443)
            || ("http".equalsIgnoreCase(scheme) && port == 80)
            || port <= 0;
        String candidateOrigin = scheme.toLowerCase(Locale.ROOT) + "://" + host
            + (defaultPort ? "" : ":" + port);

        if (candidateOrigin.equalsIgnoreCase(configuredBase)) {
            return candidateOrigin;
        }
        for (String allowed : properties.getWebauthn().getAllowedOrigins()) {
            if (allowed != null && candidateOrigin.equalsIgnoreCase(stripTrailingSlash(allowed))) {
                return candidateOrigin;
            }
        }
        if (("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))
            && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return candidateOrigin;
        }
        return configuredBase;
    }

    private NativeClientRow loadEnabledClient(String clientId) {
        List<NativeClientRow> rows = authorityJdbcTemplate.query(
            """
            SELECT client_id, client_kind, allow_loopback_redirect, loopback_callback_path,
                   admitted_https_redirects::text AS https_json, allowed_scopes::text AS scopes_json,
                   access_token_ttl_sec, refresh_token_ttl_sec, state
              FROM ma_native_oauth_client
             WHERE client_id = ?
            """,
            (rs, rowNum) -> new NativeClientRow(
                rs.getString("client_id"),
                rs.getString("client_kind"),
                rs.getBoolean("allow_loopback_redirect"),
                rs.getString("loopback_callback_path"),
                rs.getString("https_json"),
                rs.getString("scopes_json"),
                rs.getInt("access_token_ttl_sec"),
                rs.getInt("refresh_token_ttl_sec"),
                rs.getString("state")
            ),
            clientId
        );
        if (rows.isEmpty() || !"ENABLED".equals(rows.getFirst().state())) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-NATIVE-CLIENT-INVALID",
                "Unregistered or Disabled Native Client",
                "client_id '" + clientId + "' is not an enabled public native OAuth client."
            );
        }
        return rows.getFirst();
    }

    private ActiveStateRow loadActiveState() {
        List<ActiveStateRow> rows = authorityJdbcTemplate.query(
            "SELECT installation_id, incarnation FROM ma_state WHERE singleton = 1 AND security_state = 'ACTIVE'",
            (rs, rowNum) -> new ActiveStateRow(
                rs.getObject("installation_id", UUID.class),
                rs.getObject("incarnation", UUID.class)
            )
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MC-SYSTEM-NOT-ACTIVE",
                "Installation Authority Not Active",
                "Protected Authority ma_state is not ACTIVE."
            );
        }
        return rows.getFirst();
    }

    private static String buildCanonicalAuthorizeQuery(ValidatedAuthorizeRequest req) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("response_type", "code");
        map.put("client_id", req.client().clientId());
        map.put("redirect_uri", req.redirectUri().toString());
        map.put("scope", String.join(" ", req.scopes()));
        map.put("state", req.state());
        if (req.nonce() != null) {
            map.put("nonce", req.nonce());
        }
        map.put("code_challenge", req.codeChallenge());
        map.put("code_challenge_method", "S256");

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            parts.add(
                URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)
            );
        }
        return String.join("&", parts);
    }

    public static Map<String, String> parseQueryString(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return out;
        }
        int qIdx = rawQuery.indexOf('?');
        String trimmed = qIdx >= 0 ? rawQuery.substring(qIdx + 1) : rawQuery;
        for (String pair : trimmed.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                out.put(k, v);
            }
        }
        return out;
    }

    private static URI appendCodeAndState(URI redirectUri, String code, String state) {
        String encodedCode = URLEncoder.encode(code, StandardCharsets.UTF_8);
        String encodedState = URLEncoder.encode(state, StandardCharsets.UTF_8);
        return URI.create(redirectUri.toString() + "?code=" + encodedCode + "&state=" + encodedState);
    }

    private static String prefixContextPath(HttpServletRequest request, String relativePath) {
        String ctx = request.getContextPath();
        if (ctx == null || ctx.isBlank() || "/".equals(ctx)) {
            return relativePath;
        }
        return ctx + relativePath;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String randomBase64Url(int byteLen) {
        byte[] bytes = new byte[byteLen];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String trimOrNull(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        return val.trim();
    }

    private String serializeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("JSON serialization failed", ex);
        }
    }

    public record AuthorizeOutcome(
        URI redirectTarget,
        String issuedCode,
        String state,
        boolean authorizedImmediately
    ) {
    }

    private record ValidatedAuthorizeRequest(
        NativeClientRow client,
        URI redirectUri,
        List<String> scopes,
        String state,
        String nonce,
        String codeChallenge,
        String issuerUri
    ) {
    }

    private record NativeClientRow(
        String clientId,
        String clientKind,
        boolean allowLoopbackRedirect,
        String loopbackCallbackPath,
        String admittedHttpsRedirectsJson,
        String allowedScopesJson,
        int accessTokenTtlSec,
        int refreshTokenTtlSec,
        String state
    ) {
    }

    private record ActiveStateRow(UUID installationId, UUID incarnation) {
    }

    private record AuthCodeRow(
        UUID codeId,
        UUID installationId,
        UUID incarnation,
        String clientId,
        UUID principalId,
        String issuerUri,
        String redirectUri,
        String scopesJson,
        String codeChallenge,
        String codeChallengeMethod,
        String nonceParam,
        UUID oidcIssuerId,
        UUID oidcLinkId,
        Long oidcIssuerVersion,
        Long oidcLinkVersion,
        boolean consumed,
        UUID issuedTokenFamilyId,
        OffsetDateTime expiresAt
    ) {
    }

    private enum ClaimStatus {
        CLAIMED,
        MISSING,
        REPLAYED
    }

    private record ClaimedCodeOutcome(ClaimStatus status, AuthCodeRow row) {
        static ClaimedCodeOutcome missing() {
            return new ClaimedCodeOutcome(ClaimStatus.MISSING, null);
        }

        static ClaimedCodeOutcome replayed() {
            return new ClaimedCodeOutcome(ClaimStatus.REPLAYED, null);
        }

        static ClaimedCodeOutcome claimed(AuthCodeRow row) {
            return new ClaimedCodeOutcome(ClaimStatus.CLAIMED, row);
        }
    }

    private record TokenRow(
        UUID tokenId,
        UUID tokenFamilyId,
        int generation,
        UUID installationId,
        UUID incarnation,
        String clientId,
        UUID principalId,
        String issuerUri,
        String scopesJson,
        String nonceParam,
        OffsetDateTime refreshExpiresAt,
        UUID oidcIssuerId,
        UUID oidcLinkId,
        Long oidcIssuerVersion,
        Long oidcLinkVersion,
        String state
    ) {
    }

    private enum RefreshStatus {
        CLAIMED,
        MISSING,
        REPLAYED,
        CLIENT_MISMATCH,
        EXPIRED
    }

    private record RefreshClaimOutcome(RefreshStatus status, TokenRow row) {
        static RefreshClaimOutcome missing() {
            return new RefreshClaimOutcome(RefreshStatus.MISSING, null);
        }

        static RefreshClaimOutcome replayed() {
            return new RefreshClaimOutcome(RefreshStatus.REPLAYED, null);
        }

        static RefreshClaimOutcome clientMismatch() {
            return new RefreshClaimOutcome(RefreshStatus.CLIENT_MISMATCH, null);
        }

        static RefreshClaimOutcome expired() {
            return new RefreshClaimOutcome(RefreshStatus.EXPIRED, null);
        }

        static RefreshClaimOutcome claimed(TokenRow row) {
            return new RefreshClaimOutcome(RefreshStatus.CLAIMED, row);
        }
    }

    private record OidcBindingRow(
        String issuerState,
        long issuerVersion,
        String lifecycleProfile,
        Instant issuerCheckedAt,
        String linkState,
        long linkVersion,
        Instant linkCheckedAt
    ) {
    }

    private record IssuedTokens(AuthDtos.NativeTokenResponseDto response) {
    }

    private record SigningKeyMaterial(
        String kid,
        ECPublicKey publicKey,
        PrivateKey privateKey,
        String publicKeyPem,
        String xCoordBase64Url,
        String yCoordBase64Url
    ) {
    }
}
