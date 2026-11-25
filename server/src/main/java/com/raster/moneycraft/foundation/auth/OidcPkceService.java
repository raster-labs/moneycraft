package com.raster.moneycraft.foundation.auth;

import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.authority.AdminReceiptService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.OidcClockSource;
import com.raster.moneycraft.foundation.security.OidcSessionFreshnessFilter;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
    private final ProviderLifecycleAdapter lifecycleAdapter;
    private final OidcClockSource clockSource;
    private final TinkCryptoService tinkCryptoService;
    private final AdminReceiptService adminReceiptService;
    private final AuditChainService auditChainService;
    private final RbacAuthorizationService rbacAuthorizationService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate authorityJdbcTemplate;
    private final TransactionTemplate authorityTxTemplate;
    private final TransactionTemplate requiresNewAuthorityTxTemplate;
    private final TransactionTemplate businessTxTemplate;
    private final HttpClient httpClient;

    public OidcPkceService(
        MoneyCraftProperties properties,
        AuthorityGuard authorityGuard,
        AuthService authService,
        ProviderLifecycleAdapter lifecycleAdapter,
        OidcClockSource clockSource,
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
        this.lifecycleAdapter = lifecycleAdapter;
        this.clockSource = clockSource;
        this.tinkCryptoService = tinkCryptoService;
        this.adminReceiptService = adminReceiptService;
        this.auditChainService = auditChainService;
        this.rbacAuthorizationService = rbacAuthorizationService;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.objectMapper = objectMapper;
        this.authorityJdbcTemplate = authorityJdbcTemplate;
        this.authorityTxTemplate = new TransactionTemplate(authorityTransactionManager);
        this.requiresNewAuthorityTxTemplate = new TransactionTemplate(authorityTransactionManager);
        this.requiresNewAuthorityTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
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

    public List<AuthDtos.PublicOidcIssuerDto> listPublicIssuers() {
        return authorityJdbcTemplate.query(
            """
            SELECT id, registration_id, display_name
              FROM ma_issuer
             WHERE state = 'ENABLED'
             ORDER BY display_name ASC
            """,
            (rs, rowNum) -> new AuthDtos.PublicOidcIssuerDto(
                rs.getObject("id", UUID.class),
                rs.getString("registration_id"),
                rs.getString("display_name")
            )
        );
    }

    public boolean applySignedProviderConfig(String signedProviderConfigJson) {
        return lifecycleAdapter.applySignedProviderConfigJson(signedProviderConfigJson, null).changed();
    }

    public boolean applySignedProviderConfigWithHook(
        String signedProviderConfigJson,
        Consumer<String> stepHook
    ) {
        ProviderLifecycleAdapter.FaultHook hook = stepHook == null ? null : stepHook::accept;
        return lifecycleAdapter.applySignedProviderConfigJson(signedProviderConfigJson, hook).changed();
    }

    public void simulateOrdinaryRestartBookkeeping() {
        lifecycleAdapter.performOrdinaryRestartBookkeeping();
    }

    public boolean runLifecycleSweep(UUID issuerId) {
        return lifecycleAdapter.runSweep(issuerId).issuerFresh();
    }

    public Instant verifyRecoveryTargetSubject(UUID issuerId, String verifiedSubject) {
        return lifecycleAdapter.verifyRecoveryTargetSubject(issuerId, verifiedSubject);
    }

    public void setLifecycleWorkerPaused(boolean paused) {
        lifecycleAdapter.setWorkerPausedForTesting(paused);
    }

    public void setPostReadBeforeReconcileHook(Runnable hook) {
        lifecycleAdapter.setPostReadBeforeReconcileHookForTesting(hook);
    }

    public AuthDtos.OidcAuthorizeResponse buildAuthorizationRedirect(String issuerRef) {
        return initiatePkceChallenge(issuerRef, "REDIRECTED").response();
    }

    public PendingOidcCeremony createIssuedCeremony(String issuerRef) {
        return initiatePkceChallenge(issuerRef, "ISSUED");
    }

    public AuthDtos.OidcAuthorizeResponse reconstructIssuedCeremony(UUID challengeId) {
        Instant now = clockSource.instant();
        List<OidcChallengeRow> rows = authorityJdbcTemplate.query(
            """
            SELECT challenge_id, principal_id, ceremony_stage, consumed,
                   pkce_verifier_gcm, state_nonce, metadata_json::text AS meta_json, expires_at
              FROM ma_auth_challenge
             WHERE challenge_id = ? AND purpose = 'OIDC_AUTHORIZE'
            """,
            this::mapChallengeRow,
            challengeId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-OIDC-CEREMONY-NOT-FOUND",
                "OIDC Ceremony Not Found",
                "Pending OIDC authorization ceremony was not found."
            );
        }
        OidcChallengeRow ch = rows.getFirst();
        if (ch.consumed() || !"ISSUED".equals(ch.ceremonyStage()) || !ch.expiresAt().isAfter(now)) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-OIDC-CEREMONY-NOT-RESUMABLE",
                "OIDC Ceremony Cannot Be Reconstructed",
                "Only unconsumed ISSUED ceremonies within their original TTL may be reconstructed."
            );
        }

        ChallengeMetadata meta = parseChallengeMetadata(ch.metaJson());
        IssuerRow issuer = findEnabledIssuer(meta.issuerId().toString());
        if (meta.issuerVersion() != null && issuer.versionNo() != meta.issuerVersion().longValue()) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-OIDC-ISSUER-VERSION-CHANGED",
                "OIDC Issuer Version Changed",
                "Pending OIDC request predates current issuer configuration version."
            );
        }
        if (IssuerConfig2.PROFILE_MC_FED_01.equals(issuer.lifecycleProfile())
            && !OidcSessionFreshnessFilter.isFresh(issuer.checkedAt(), now)) {
            throw new MoneyCraftException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MC-OIDC-FRESHNESS-UNAVAILABLE",
                "OIDC Provider Freshness Temporarily Unavailable",
                "Pending request preserved; retry continuation once qualified provider sweep succeeds."
            );
        }
        return new AuthDtos.OidcAuthorizeResponse(meta.authorizationUrl(), ch.stateNonce());
    }

    public UUID createVerifiedLinkCeremony(UUID issuerId, UUID principalId, String subject, Duration ttl) {
        IssuerRow issuer = findEnabledIssuer(issuerId.toString());
        UUID challengeId = UUID.randomUUID();
        Instant now = clockSource.instant();
        Instant expiresAt = now.plus(ttl == null ? Duration.ofMinutes(5) : ttl);
        byte[] stateEntropy = new byte[24];
        SECURE_RANDOM.nextBytes(stateEntropy);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateEntropy);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("issuerId", issuer.id().toString());
        meta.put("issuerVersion", issuer.versionNo());
        meta.put("subject", subject);
        meta.put("principalId", principalId.toString());

        authorityJdbcTemplate.update(
            """
            INSERT INTO ma_auth_challenge (
                challenge_id, principal_id, session_primary_id, purpose,
                challenge_bytes, pkce_verifier_gcm, state_nonce, metadata_json,
                ceremony_stage, consumed, created_at, expires_at
            ) VALUES (?, ?, NULL, 'OIDC_AUTHORIZE', ?, NULL, ?, ?::jsonb, 'VERIFIED_LINK', false, ?, ?)
            """,
            challengeId,
            principalId,
            stateEntropy,
            state,
            jcsCanonicalizer.canonicalizeObject(meta),
            Timestamp.from(now),
            Timestamp.from(expiresAt)
        );
        return challengeId;
    }

    public UUID confirmVerifiedLinkCeremony(UUID challengeId, UUID callerPrincipalId) {
        Instant now = clockSource.instant();
        List<OidcChallengeRow> rows = authorityJdbcTemplate.query(
            """
            SELECT challenge_id, principal_id, ceremony_stage, consumed,
                   pkce_verifier_gcm, state_nonce, metadata_json::text AS meta_json, expires_at
              FROM ma_auth_challenge
             WHERE challenge_id = ? AND purpose = 'OIDC_AUTHORIZE'
            """,
            this::mapChallengeRow,
            challengeId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-OIDC-CEREMONY-NOT-FOUND",
                "OIDC Link Ceremony Not Found",
                "Pending VERIFIED_LINK ceremony does not exist."
            );
        }
        OidcChallengeRow ch = rows.getFirst();
        if (ch.consumed() || !"VERIFIED_LINK".equals(ch.ceremonyStage()) || !ch.expiresAt().isAfter(now)) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-OIDC-CEREMONY-NOT-RESUMABLE",
                "OIDC Link Ceremony Not Confirmable",
                "VERIFIED_LINK ceremony is expired or already consumed."
            );
        }
        if (ch.principalId() == null || !ch.principalId().equals(callerPrincipalId)) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-OIDC-PRINCIPAL-MISMATCH",
                "Same-Person Confirmation Required",
                "VERIFIED_LINK confirmation requires the same authenticated principal."
            );
        }

        JsonNode metaNode;
        try {
            metaNode = objectMapper.readTree(ch.metaJson());
        } catch (JacksonException ex) {
            throw new MoneyCraftException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "MC-OIDC-METADATA-CORRUPT",
                "Corrupted OIDC Ceremony Metadata",
                "Unable to parse VERIFIED_LINK metadata."
            );
        }
        UUID issuerId = UUID.fromString(metaNode.path("issuerId").asString());
        long expectedIssuerVersion = metaNode.path("issuerVersion").asLong(-1L);
        String subject = metaNode.path("subject").asString();

        IssuerRow issuer = findEnabledIssuer(issuerId.toString());
        if (expectedIssuerVersion > 0L && issuer.versionNo() != expectedIssuerVersion) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-OIDC-ISSUER-VERSION-CHANGED",
                "OIDC Issuer Version Changed",
                "VERIFIED_LINK ceremony predates current issuer version."
            );
        }
        if (IssuerConfig2.PROFILE_MC_FED_01.equals(issuer.lifecycleProfile())
            && !OidcSessionFreshnessFilter.isFresh(issuer.checkedAt(), now)) {
            throw new MoneyCraftException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MC-OIDC-FRESHNESS-UNAVAILABLE",
                "OIDC Provider Freshness Temporarily Unavailable",
                "VERIFIED_LINK preserved within original deadline until fresh provider sweep completes."
            );
        }

        return authorityTxTemplate.execute(status -> {
            int claimed = authorityJdbcTemplate.update(
                """
                UPDATE ma_auth_challenge
                   SET ceremony_stage = 'CONSUMED',
                       consumed = true,
                       consumed_at = ?
                 WHERE challenge_id = ?
                   AND ceremony_stage = 'VERIFIED_LINK'
                   AND consumed = false
                """,
                Timestamp.from(now),
                challengeId
            );
            if (claimed != 1) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-OIDC-CEREMONY-ALREADY-CONSUMED",
                    "Ceremony Already Consumed",
                    "VERIFIED_LINK ceremony was already consumed."
                );
            }

            List<UUID> existingLinks = authorityJdbcTemplate.query(
                "SELECT id FROM ma_oidc_link WHERE issuer_id = ? AND subject = ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                issuer.id(),
                subject
            );
            if (!existingLinks.isEmpty()) {
                return existingLinks.getFirst();
            }
            UUID linkId = UUID.randomUUID();
            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_oidc_link (id, principal_id, issuer_id, subject, state)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """,
                linkId,
                callerPrincipalId,
                issuer.id(),
                subject
            );
            return linkId;
        });
    }

    private PendingOidcCeremony initiatePkceChallenge(String issuerRef, String initialStage) {
        IssuerRow issuer = findEnabledIssuer(issuerRef);

        byte[] verifierEntropy = new byte[32];
        SECURE_RANDOM.nextBytes(verifierEntropy);
        String codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierEntropy);
        byte[] s256Digest = jcsCanonicalizer.sha256(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(s256Digest);

        byte[] stateEntropy = new byte[24];
        SECURE_RANDOM.nextBytes(stateEntropy);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateEntropy);

        byte[] nonceEntropy = new byte[24];
        SECURE_RANDOM.nextBytes(nonceEntropy);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceEntropy);

        UUID challengeId = UUID.randomUUID();
        byte[] encryptedVerifier = tinkCryptoService.encryptGcm(
            ("ma_auth_challenge|" + challengeId + "|" + state).getBytes(StandardCharsets.UTF_8),
            codeVerifier.getBytes(StandardCharsets.UTF_8)
        );

        String redirectUri = resolveRedirectUri(issuer);
        String authBase = issuer.authorizationUri() != null && !issuer.authorizationUri().isBlank()
            ? issuer.authorizationUri()
            : issuer.issuerUri() + "/authorize";
        String query = "response_type=code"
            + "&client_id=" + urlEncode(issuer.clientId())
            + "&redirect_uri=" + urlEncode(redirectUri)
            + "&scope=" + urlEncode(issuer.scopes())
            + "&state=" + urlEncode(state)
            + "&nonce=" + urlEncode(nonce)
            + "&code_challenge=" + urlEncode(codeChallenge)
            + "&code_challenge_method=S256";
        String separator = authBase.contains("?") ? "&" : "?";
        String authorizationUrl = authBase + separator + query;

        Instant now = clockSource.instant();
        Instant expiresAt = now.plus(Duration.ofMinutes(5));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuerId", issuer.id().toString());
        metadata.put("issuerVersion", issuer.versionNo());
        metadata.put("redirectUri", redirectUri);
        metadata.put("nonce", nonce);
        metadata.put("authorizationUrl", authorizationUrl);

        authorityJdbcTemplate.update(
            """
            INSERT INTO ma_auth_challenge (
                challenge_id, principal_id, session_primary_id, purpose,
                challenge_bytes, pkce_verifier_gcm, state_nonce, metadata_json,
                ceremony_stage, consumed, created_at, expires_at
            ) VALUES (?, NULL, NULL, 'OIDC_AUTHORIZE', ?, ?, ?, ?::jsonb, ?, false, ?, ?)
            """,
            challengeId,
            s256Digest,
            encryptedVerifier,
            state,
            jcsCanonicalizer.canonicalizeObject(metadata),
            initialStage,
            Timestamp.from(now),
            Timestamp.from(expiresAt)
        );

        return new PendingOidcCeremony(
            challengeId,
            nonce,
            codeVerifier,
            codeChallenge,
            new AuthDtos.OidcAuthorizeResponse(authorizationUrl, state)
        );
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

        Instant now = clockSource.instant();
        List<OidcChallengeRow> challenges = authorityJdbcTemplate.query(
            """
            SELECT challenge_id, principal_id, ceremony_stage, consumed,
                   pkce_verifier_gcm, state_nonce, metadata_json::text AS meta_json, expires_at
              FROM ma_auth_challenge
             WHERE purpose = 'OIDC_AUTHORIZE'
               AND state_nonce = ?
            """,
            this::mapChallengeRow,
            request.state().trim()
        );

        if (challenges.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-OIDC-STATE-INVALID",
                "Invalid or Expired OIDC State",
                "OIDC PKCE state parameter did not match any active ceremony."
            );
        }

        OidcChallengeRow challenge = challenges.getFirst();
        if (challenge.consumed()
            || (!"REDIRECTED".equals(challenge.ceremonyStage()) && !"ISSUED".equals(challenge.ceremonyStage()))
            || !challenge.expiresAt().isAfter(now)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-OIDC-STATE-INVALID",
                "OIDC Ceremony Expired, Claimed, or Consumed",
                "OIDC ceremony cannot be claimed or replayed."
            );
        }

        ChallengeMetadata meta = parseChallengeMetadata(challenge.metaJson());
        IssuerRow issuer = findEnabledIssuer(meta.issuerId().toString());
        if (meta.issuerVersion() != null && issuer.versionNo() != meta.issuerVersion().longValue()) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-OIDC-ISSUER-VERSION-CHANGED",
                "OIDC Issuer Configuration Changed",
                "Pending OIDC callback predates current issuer configuration version."
            );
        }

        // Document 18 / Document 47 Rev 2: atomically mark CLAIMED in an independent transaction before code exchange
        Integer claimedCount = requiresNewAuthorityTxTemplate.execute(status ->
            authorityJdbcTemplate.update(
                """
                UPDATE ma_auth_challenge
                   SET ceremony_stage = 'CLAIMED'
                 WHERE challenge_id = ?
                   AND ceremony_stage IN ('REDIRECTED', 'ISSUED')
                   AND consumed = false
                """,
                challenge.challengeId()
            )
        );
        if (claimedCount == null || claimedCount != 1) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-OIDC-CEREMONY-ALREADY-CLAIMED",
                "OIDC Callback Already Claimed",
                "Authorization callback was already claimed and cannot be replayed."
            );
        }

        byte[] decryptedVerifier = tinkCryptoService.decryptGcm(
            ("ma_auth_challenge|" + challenge.challengeId() + "|" + challenge.stateNonce())
                .getBytes(StandardCharsets.UTF_8),
            challenge.pkceVerifierGcm()
        );
        String codeVerifier = new String(decryptedVerifier, StandardCharsets.UTF_8);

        String subject = exchangeCodeForVerifiedSubject(
            issuer,
            request.code().trim(),
            meta.redirectUri(),
            codeVerifier,
            meta.nonce()
        );

        // Document 47 §4: augmented exact-subject sweep outside financial/authority locks before completion
        if (IssuerConfig2.PROFILE_MC_FED_01.equals(issuer.lifecycleProfile())) {
            lifecycleAdapter.runAugmentedSweepForSubject(issuer.id(), subject);
        }

        // Revalidate issuer & link versions and finalize CONSUMED transition
        IssuerRow postSweepIssuer = findEnabledIssuer(issuer.id().toString());
        if (postSweepIssuer.versionNo() != issuer.versionNo()) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-OIDC-ISSUER-VERSION-CHANGED",
                "OIDC Issuer Changed During Callback",
                "Issuer semantic version changed during callback verification."
            );
        }

        List<ActiveOidcLinkRow> linkedRows = authorityJdbcTemplate.query(
            """
            SELECT id, principal_id, version_no
              FROM ma_oidc_link
             WHERE issuer_id = ? AND subject = ? AND state = 'ACTIVE'
            """,
            (rs, rowNum) -> new ActiveOidcLinkRow(
                rs.getObject("id", UUID.class),
                rs.getObject("principal_id", UUID.class),
                rs.getLong("version_no")
            ),
            issuer.id(),
            subject
        );

        if (linkedRows.isEmpty()) {
            requiresNewAuthorityTxTemplate.executeWithoutResult(status ->
                authorityJdbcTemplate.update(
                    """
                    UPDATE ma_auth_challenge
                       SET ceremony_stage = 'CONSUMED',
                           consumed = true,
                           consumed_at = clock_timestamp()
                     WHERE challenge_id = ?
                    """,
                    challenge.challengeId()
                )
            );
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-OIDC-SUBJECT-UNLINKED",
                "OIDC Subject Not Linked",
                "Authenticated OIDC subject is not linked to an active MoneyCraft principal."
            );
        }

        ActiveOidcLinkRow activeLink = linkedRows.getFirst();
        authorityTxTemplate.executeWithoutResult(status -> {
            authorityJdbcTemplate.execute("SET LOCAL ma.bookkeeping_checked_at_only = 'on'");
            authorityJdbcTemplate.update(
                """
                UPDATE ma_auth_challenge
                   SET ceremony_stage = 'CONSUMED',
                       consumed = true,
                       consumed_at = clock_timestamp()
                 WHERE challenge_id = ?
                """,
                challenge.challengeId()
            );
            authorityJdbcTemplate.update(
                "UPDATE ma_oidc_link SET last_verified_at = clock_timestamp() WHERE id = ?",
                activeLink.linkId()
            );
            authorityJdbcTemplate.update(
                "UPDATE ma_principal SET last_authenticated_at = clock_timestamp() WHERE principal_id = ?",
                activeLink.principalId()
            );
        });

        MoneyCraftPrincipal loaded = rbacAuthorizationService.loadPrincipalProfile(activeLink.principalId());
        return loaded.withOidcSessionBinding(
            postSweepIssuer.id(),
            activeLink.linkId(),
            postSweepIssuer.versionNo(),
            activeLink.versionNo()
        );
    }

    private String exchangeCodeForVerifiedSubject(
        IssuerRow issuer,
        String code,
        String redirectUri,
        String codeVerifier,
        String expectedNonce
    ) {
        String tokenUri = issuer.tokenUri() != null && !issuer.tokenUri().isBlank()
            ? issuer.tokenUri()
            : issuer.issuerUri() + "/token";
        URI parsedUri = URI.create(tokenUri);
        enforceOutboundAllowlist(parsedUri);

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
            boolean jwksConfigured = issuer.jwksUri() != null && !issuer.jwksUri().isBlank();
            if (tokenJson.hasNonNull("id_token")) {
                return verifyAndExtractSubjectFromIdToken(
                    tokenJson.get("id_token").asString(),
                    issuer,
                    expectedNonce,
                    jwksConfigured
                );
            }
            if (jwksConfigured) {
                throw new MoneyCraftException(
                    HttpStatus.UNAUTHORIZED,
                    "MC-OIDC-IDTOKEN-REQUIRED",
                    "Signed OIDC ID Token Required",
                    "Issuer configured with jwks_uri requires a signed id_token in token response."
                );
            }
            if (tokenJson.hasNonNull("sub")) {
                return tokenJson.get("sub").asString();
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

    private String verifyAndExtractSubjectFromIdToken(
        String jwt,
        IssuerRow issuer,
        String expectedNonce,
        boolean requireJwksSignature
    ) throws JacksonException {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2 || (requireJwksSignature && parts.length != 3)) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-OIDC-IDTOKEN-INVALID",
                "Malformed OIDC ID Token",
                "id_token was not a valid signed JWS."
            );
        }

        JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));

        if (parts.length == 3 && (requireJwksSignature || !"none".equalsIgnoreCase(header.path("alg").asString("none")))) {
            if (issuer.jwksUri() == null || issuer.jwksUri().isBlank()) {
                throw new MoneyCraftException(
                    HttpStatus.UNAUTHORIZED,
                    "MC-OIDC-JWKS-MISSING",
                    "JWKS URI Missing",
                    "Cannot verify signed id_token without configured jwks_uri."
                );
            }
            verifyJwtSignatureWithJwks(parts[0] + "." + parts[1], parts[2], header, issuer.jwksUri());
        } else if (requireJwksSignature) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-OIDC-IDTOKEN-SIGNATURE-INVALID",
                "Unsigned ID Token Rejected",
                "Unsigned or alg=none id_token is forbidden."
            );
        }

        // Validate iss, aud, exp, and nonce claims when present or when JWKS is configured
        if (requireJwksSignature || claims.hasNonNull("iss")) {
            String iss = claims.path("iss").asString("");
            if (!Objects.equals(issuer.issuerUri(), iss)) {
                throw new MoneyCraftException(
                    HttpStatus.UNAUTHORIZED,
                    "MC-OIDC-ISS-MISMATCH",
                    "OIDC Issuer Claim Mismatch",
                    "id_token iss claim did not match configured issuer URI."
                );
            }
        }

        if (requireJwksSignature || claims.hasNonNull("aud")) {
            if (!audienceMatches(claims.get("aud"), issuer.clientId())) {
                throw new MoneyCraftException(
                    HttpStatus.UNAUTHORIZED,
                    "MC-OIDC-AUD-MISMATCH",
                    "OIDC Audience Claim Mismatch",
                    "id_token aud claim did not include configured client_id."
                );
            }
        }

        if (requireJwksSignature || claims.hasNonNull("exp")) {
            if (!claims.hasNonNull("exp") || !claims.get("exp").isNumber()) {
                throw new MoneyCraftException(
                    HttpStatus.UNAUTHORIZED,
                    "MC-OIDC-EXP-INVALID",
                    "OIDC Token Expiration Missing",
                    "id_token exp claim is missing or non-numeric."
                );
            }
            long expEpochSec = claims.get("exp").longValue();
            long nowSec = clockSource.instant().getEpochSecond();
            if (nowSec >= expEpochSec + 5L) {
                throw new MoneyCraftException(
                    HttpStatus.UNAUTHORIZED,
                    "MC-OIDC-TOKEN-EXPIRED",
                    "OIDC ID Token Expired",
                    "id_token exp claim has expired."
                );
            }
        }

        if (expectedNonce != null && !expectedNonce.isBlank()
            && (requireJwksSignature || claims.hasNonNull("nonce"))) {
            String tokenNonce = claims.path("nonce").asString("");
            if (!expectedNonce.equals(tokenNonce)) {
                throw new MoneyCraftException(
                    HttpStatus.UNAUTHORIZED,
                    "MC-OIDC-NONCE-MISMATCH",
                    "OIDC Nonce Mismatch",
                    "id_token nonce did not match stored PKCE ceremony nonce."
                );
            }
        }

        if (!claims.hasNonNull("sub") || claims.get("sub").asString().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-OIDC-SUBJECT-MISSING",
                "OIDC ID Token Missing sub",
                "id_token payload did not contain a valid sub claim."
            );
        }
        return claims.get("sub").asString();
    }

    private void verifyJwtSignatureWithJwks(
        String signingInputAscii,
        String signatureB64Url,
        JsonNode jwtHeader,
        String jwksUri
    ) {
        String alg = jwtHeader.path("alg").asString("");
        if (!"RS256".equals(alg) && !"ES256".equals(alg)) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-OIDC-ALG-UNSUPPORTED",
                "Unsupported JWT Signature Algorithm",
                "Only RS256 and ES256 are supported for OIDC id_token verification."
            );
        }
        String kid = jwtHeader.hasNonNull("kid") ? jwtHeader.get("kid").asString() : null;

        URI parsedJwksUri = URI.create(jwksUri);
        enforceOutboundAllowlist(parsedJwksUri);
        HttpRequest req = HttpRequest.newBuilder(parsedJwksUri)
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .GET()
            .build();

        JsonNode keysArray;
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_GATEWAY,
                    "MC-OIDC-JWKS-FETCH-FAILED",
                    "JWKS Endpoint Error",
                    "JWKS endpoint returned HTTP " + resp.statusCode() + "."
                );
            }
            JsonNode jwksRoot = objectMapper.readTree(resp.body());
            keysArray = jwksRoot.get("keys");
        } catch (IOException | InterruptedException | JacksonException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new MoneyCraftException(
                HttpStatus.BAD_GATEWAY,
                "MC-OIDC-JWKS-FETCH-FAILED",
                "JWKS Endpoint Unreachable",
                "Failed to fetch or parse OIDC JWKS document."
            );
        }

        if (keysArray == null || !keysArray.isArray() || keysArray.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-OIDC-JWKS-EMPTY",
                "No JWKS Keys Available",
                "JWKS document contained no signing keys."
            );
        }

        String expectedKty = "RS256".equals(alg) ? "RSA" : "EC";
        byte[] sigBytes = Base64.getUrlDecoder().decode(signatureB64Url);
        byte[] dataBytes = signingInputAscii.getBytes(StandardCharsets.US_ASCII);

        for (JsonNode jwk : keysArray) {
            if (!expectedKty.equals(jwk.path("kty").asString(""))) {
                continue;
            }
            String use = jwk.path("use").asString("sig");
            if (!"sig".equals(use)) {
                continue;
            }
            if (kid != null && jwk.hasNonNull("kid") && !kid.equals(jwk.get("kid").asString())) {
                continue;
            }
            if (verifySingleJwk(alg, jwk, dataBytes, sigBytes)) {
                return;
            }
        }

        throw new MoneyCraftException(
            HttpStatus.UNAUTHORIZED,
            "MC-OIDC-IDTOKEN-SIGNATURE-INVALID",
            "OIDC ID Token Signature Verification Failed",
            "id_token signature could not be verified against issuer JWKS."
        );
    }

    private static boolean verifySingleJwk(String alg, JsonNode jwk, byte[] signingInput, byte[] sigBytes) {
        try {
            if ("RS256".equals(alg)) {
                BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("n").asString()));
                BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("e").asString()));
                PublicKey rsaKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));
                Signature verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(rsaKey);
                verifier.update(signingInput);
                return verifier.verify(sigBytes);
            }
            if ("ES256".equals(alg)) {
                if (!"P-256".equals(jwk.path("crv").asString("P-256"))) {
                    return false;
                }
                BigInteger x = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("x").asString()));
                BigInteger y = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("y").asString()));
                AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
                params.init(new ECGenParameterSpec("secp256r1"));
                ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);
                PublicKey ecKey = KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), ecSpec));
                Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
                verifier.initVerify(ecKey);
                verifier.update(signingInput);
                return verifier.verify(sigBytes);
            }
            return false;
        } catch (GeneralSecurityException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean audienceMatches(JsonNode audNode, String expectedClientId) {
        if (audNode == null || audNode.isNull()) {
            return false;
        }
        if (audNode.isString()) {
            return expectedClientId.equals(audNode.asString());
        }
        if (audNode.isArray()) {
            for (JsonNode child : audNode) {
                if (child.isString() && expectedClientId.equals(child.asString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void enforceOutboundAllowlist(URI parsedUri) {
        String host = parsedUri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        if (!loopback) {
            if (!properties.getConnector().isEnabled()
                || !properties.getConnector().getEgressHostnameAllowlist().contains(host)) {
                throw new MoneyCraftException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "MC-CONNECTOR-EGRESS-BLOCKED",
                    "Outbound OIDC Egress Disabled",
                    "External OIDC endpoint requires moneycraft.connector.enabled=true and hostname allowlisting."
                );
            }
        }
    }

    private String resolveRedirectUri(IssuerRow issuer) {
        if (IssuerConfig2.PROFILE_MC_FED_01.equals(issuer.lifecycleProfile()) && issuer.metadataCipher() != null) {
            List<Map<String, Object>> authRows = authorityJdbcTemplate.queryForList(
                "SELECT installation_id, incarnation FROM ma_state WHERE singleton = 1"
            );
            if (!authRows.isEmpty()) {
                UUID instId = (UUID) authRows.getFirst().get("installation_id");
                UUID inc = (UUID) authRows.getFirst().get("incarnation");
                IssuerConfig2 cfg = IssuerConfig2.openAuthorityEnvelope(
                    issuer.metadataCipher(),
                    instId,
                    inc,
                    issuer.id(),
                    issuer.versionNo(),
                    tinkCryptoService,
                    jcsCanonicalizer,
                    properties.getSecurity().isAllowTestProviderProfile()
                );
                return cfg.redirectUri();
            }
        }
        return properties.getBaseUrl() + "/oidc/callback";
    }

    private ChallengeMetadata parseChallengeMetadata(String rawMetaJson) {
        try {
            JsonNode meta = objectMapper.readTree(rawMetaJson);
            UUID issuerId = UUID.fromString(meta.get("issuerId").asString());
            Long issuerVersion = meta.hasNonNull("issuerVersion") ? meta.get("issuerVersion").asLong() : null;
            String redirectUri = meta.path("redirectUri").asString(properties.getBaseUrl() + "/oidc/callback");
            String nonce = meta.hasNonNull("nonce") ? meta.get("nonce").asString() : null;
            String authUrl = meta.hasNonNull("authorizationUrl") ? meta.get("authorizationUrl").asString() : "";
            return new ChallengeMetadata(issuerId, issuerVersion, redirectUri, nonce, authUrl);
        } catch (RuntimeException ex) {
            throw new MoneyCraftException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "MC-OIDC-METADATA-CORRUPT",
                "Corrupted OIDC Challenge Metadata",
                "Unable to parse stored OIDC PKCE challenge metadata."
            );
        }
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
                SELECT id, registration_id, issuer_uri, authorization_uri, token_uri, jwks_uri,
                       client_id, client_secret_gcm, scopes, version_no, lifecycle_profile,
                       checked_at, metadata_cipher
                  FROM ma_issuer
                 WHERE id = ? AND state = 'ENABLED'
                """,
                this::mapIssuerRow,
                parsedId
            )
            : authorityJdbcTemplate.query(
                """
                SELECT id, registration_id, issuer_uri, authorization_uri, token_uri, jwks_uri,
                       client_id, client_secret_gcm, scopes, version_no, lifecycle_profile,
                       checked_at, metadata_cipher
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
        OffsetDateTime checked = rs.getObject("checked_at", OffsetDateTime.class);
        return new IssuerRow(
            rs.getObject("id", UUID.class),
            rs.getString("registration_id"),
            rs.getString("issuer_uri"),
            rs.getString("authorization_uri"),
            rs.getString("token_uri"),
            rs.getString("jwks_uri"),
            rs.getString("client_id"),
            rs.getBytes("client_secret_gcm"),
            rs.getString("scopes"),
            rs.getLong("version_no"),
            rs.getString("lifecycle_profile"),
            checked == null ? null : checked.toInstant(),
            rs.getBytes("metadata_cipher")
        );
    }

    private OidcChallengeRow mapChallengeRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        OffsetDateTime exp = rs.getObject("expires_at", OffsetDateTime.class);
        return new OidcChallengeRow(
            rs.getObject("challenge_id", UUID.class),
            rs.getObject("principal_id", UUID.class),
            rs.getString("ceremony_stage"),
            rs.getBoolean("consumed"),
            rs.getBytes("pkce_verifier_gcm"),
            rs.getString("state_nonce"),
            rs.getString("meta_json"),
            exp.toInstant()
        );
    }

    private static String urlEncode(String val) {
        return URLEncoder.encode(val, StandardCharsets.UTF_8);
    }

    public record PendingOidcCeremony(
        UUID challengeId,
        String nonce,
        String codeVerifier,
        String codeChallenge,
        AuthDtos.OidcAuthorizeResponse response
    ) {
    }

    private record IssuerRow(
        UUID id,
        String registrationId,
        String issuerUri,
        String authorizationUri,
        String tokenUri,
        String jwksUri,
        String clientId,
        byte[] clientSecretGcm,
        String scopes,
        long versionNo,
        String lifecycleProfile,
        Instant checkedAt,
        byte[] metadataCipher
    ) {
    }

    private record ActiveOidcLinkRow(UUID linkId, UUID principalId, long versionNo) {
    }

    private record OidcChallengeRow(
        UUID challengeId,
        UUID principalId,
        String ceremonyStage,
        boolean consumed,
        byte[] pkceVerifierGcm,
        String stateNonce,
        String metaJson,
        Instant expiresAt
    ) {
    }

    private record ChallengeMetadata(
        UUID issuerId,
        Long issuerVersion,
        String redirectUri,
        String nonce,
        String authorizationUrl
    ) {
    }
}
