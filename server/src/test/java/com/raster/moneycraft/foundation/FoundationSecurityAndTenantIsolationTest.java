package com.raster.moneycraft.foundation;

import com.raster.moneycraft.MoneyCraftApplication;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.authority.InstallationSecurityState;
import com.raster.moneycraft.foundation.company.CompanyService;
import com.raster.moneycraft.foundation.crypto.CursorCodec;
import com.raster.moneycraft.foundation.crypto.CursorPayload;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import com.raster.moneycraft.ops.health.AuthorityDbHealthIndicator;
import com.raster.moneycraft.ops.health.AuthorityGuardHealthIndicator;
import com.raster.moneycraft.ops.health.BusinessDbHealthIndicator;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end Phase 1 verification against two real PostgreSQL 18 (18.6-bookworm) clusters:
 * Dual-Flyway, AuthorityGuard single-writer advisory lock, Bootstrap, Argon2id session/CSRF,
 * WebAuthn FIDO2 Passkeys, Enterprise OIDC S256 PKCE, Tink AES256_SIV & AES256_GCM, HS256 Cursors,
 * Multi-Company PG18 seeding (28 groups + 24 voucher types + 12 periods), RFC 8785 JCS Idempotency,
 * ETag/If-Match & MCA Edit Log immutability, Cross-Company RBAC + PostgreSQL 18 FORCE RLS,
 * Scoped Service API Keys, MC-AUDIT-2 hash chain, and Fail-Closed QUARANTINE DR recovery.
 */
@SpringBootTest(classes = MoneyCraftApplication.class)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FoundationSecurityAndTenantIsolationTest extends DualPostgres18TestcontainersSupport {

    private static HttpServer loopbackOidcIdp;
    private static int oidcPort;
    private static final AtomicReference<String> EXPECTED_OIDC_CODE_CHALLENGE = new AtomicReference<>();

    private static UUID ownerPrincipalId;
    private static String recoveryReceiptCode;
    private static Cookie ownerSessionCookie;
    private static Cookie ownerXsrfCookie;

    private static UUID companyAId;
    private static UUID companyBId;
    private static UUID companyAFirstPeriodId;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("authorityJdbcTemplate")
    private JdbcTemplate authorityJdbcTemplate;

    @Autowired
    @Qualifier("businessJdbcTemplate")
    private JdbcTemplate businessJdbcTemplate;

    @Autowired
    @Qualifier("transactionManager")
    private PlatformTransactionManager businessTxManager;

    @Autowired
    private AuthorityGuard authorityGuard;

    @Autowired
    private TinkCryptoService tinkCryptoService;

    @Autowired
    private JcsCanonicalizer jcsCanonicalizer;

    @Autowired
    private CursorCodec cursorCodec;

    @Autowired
    private CompanyService companyService;

    @Autowired
    private RbacAuthorizationService rbacAuthorizationService;

    @Autowired
    private AuthorityDbHealthIndicator authorityDbHealthIndicator;

    @Autowired
    private BusinessDbHealthIndicator businessDbHealthIndicator;

    @Autowired
    private AuthorityGuardHealthIndicator authorityGuardHealthIndicator;

    @BeforeAll
    static void startLoopbackOidcProvider() throws Exception {
        loopbackOidcIdp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        oidcPort = loopbackOidcIdp.getAddress().getPort();
        loopbackOidcIdp.createContext("/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseFormUrlEncoded(form);
            String codeVerifier = params.getOrDefault("code_verifier", "");
            byte[] sha;
            try {
                sha = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            } catch (java.security.NoSuchAlgorithmException ex) {
                throw new IllegalStateException(ex);
            }
            String recomputedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha);

            if (!recomputedChallenge.equals(EXPECTED_OIDC_CODE_CHALLENGE.get())
                || !"valid-oidc-auth-code".equals(params.get("code"))) {
                byte[] err = "{\"error\":\"invalid_grant\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, err.length);
                exchange.getResponseBody().write(err);
                exchange.close();
                return;
            }

            byte[] ok = "{\"access_token\":\"tok\",\"token_type\":\"Bearer\",\"sub\":\"oidc-sub-owner-001\"}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        loopbackOidcIdp.start();
    }

    @AfterAll
    static void stopLoopbackOidcProvider() {
        if (loopbackOidcIdp != null) {
            loopbackOidcIdp.stop(0);
        }
    }

    @Test
    @Order(1)
    void dualFlywayMigrationsAppliedAndAuthorityLeaseAndCspHeadersActive() throws Exception {
        Integer authMigrations = authorityJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
            Integer.class
        );
        Integer bizMigrations = businessJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
            Integer.class
        );
        Assertions.assertThat(authMigrations).isGreaterThanOrEqualTo(2);
        Assertions.assertThat(bizMigrations).isGreaterThanOrEqualTo(8);

        Assertions.assertThat(authorityGuard.isLeaseHealthy()).isTrue();
        Assertions.assertThat(authorityDbHealthIndicator.health().getStatus()).isEqualTo(Status.UP);
        Assertions.assertThat(businessDbHealthIndicator.health().getStatus()).isEqualTo(Status.UP);
        Assertions.assertThat(authorityGuardHealthIndicator.health().getStatus()).isEqualTo(Status.UP);

        MvcResult statusRes = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/system/status"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.header().exists("Content-Security-Policy"))
            .andExpect(MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(MockMvcResultMatchers.header().string("X-Frame-Options", "DENY"))
            .andReturn();

        String csp = statusRes.getResponse().getHeader("Content-Security-Policy");
        Assertions.assertThat(csp)
            .contains("script-src 'self' 'nonce-")
            .doesNotContain("unsafe-inline")
            .doesNotContain("unsafe-eval");

        JsonNode body = objectMapper.readTree(statusRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(body.get("securityState").asString()).isEqualTo("UNINITIALISED");
    }

    @Test
    @Order(2)
    void bootstrapInitialisesOwnerTinkKeysReceiptsAndRejectsReplay() throws Exception {
        String initPayload = """
            {
              "installationLabel": "MoneyCraft Test Cluster",
              "ownerUsername": "owner",
              "ownerDisplayName": "Primary Owner",
              "password": "OwnerMasterPassword#2026!"
            }
            """;

        MvcResult initRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/system/initialise")
                .contentType(MediaType.APPLICATION_JSON)
                .content(initPayload)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        ownerSessionCookie = latestCookie(initRes, "MCSESSION");
        ownerXsrfCookie = latestCookie(initRes, "XSRF-TOKEN");
        Assertions.assertThat(ownerSessionCookie).isNotNull();
        Assertions.assertThat(ownerXsrfCookie).isNotNull();

        JsonNode root = objectMapper.readTree(initRes.getResponse().getContentAsByteArray());
        ownerPrincipalId = UUID.fromString(root.get("ownerPrincipal").get("principalId").asString());
        recoveryReceiptCode = root.get("recoveryReceiptCode").asString();
        Assertions.assertThat(recoveryReceiptCode).startsWith("MCRK-");

        Integer keyCount = authorityJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ma_key WHERE state = 'ACTIVE'",
            Integer.class
        );
        Assertions.assertThat(keyCount).isEqualTo(4);

        String storedHash = authorityJdbcTemplate.queryForObject(
            "SELECT argon2id_hash FROM ma_password WHERE principal_id = ?",
            String.class,
            ownerPrincipalId
        );
        Assertions.assertThat(storedHash).startsWith("$argon2id$v=19$m=65536,t=3,p=1$");

        // Second bootstrap attempt must fail closed with 409 Conflict
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/system/initialise")
                .contentType(MediaType.APPLICATION_JSON)
                .content(initPayload)
        )
            .andExpect(MockMvcResultMatchers.status().isConflict())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-SYSTEM-ALREADY-INITIALISED"));
    }

    @Test
    @Order(3)
    void loginLogoutSessionRotationAndCsrfEnforcement() throws Exception {
        // Logout invalidates session
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/logout")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
        )
            .andExpect(MockMvcResultMatchers.status().isNoContent());

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/me").cookie(ownerSessionCookie))
            .andExpect(MockMvcResultMatchers.status().isUnauthorized())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-AUTH-REQUIRED"));

        // Wrong password rejected with 401
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"owner\",\"password\":\"WrongPassword#123\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isUnauthorized())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-AUTH-INVALID-CREDENTIALS"));

        // Establish an anonymous passkey challenge session first so we prove login rotates MCSESSION ID!
        MvcResult preAuthOpt = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/passkeys/authenticate/options")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"owner\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Cookie preLoginSessionCookie = latestCookie(preAuthOpt, "MCSESSION");
        Assertions.assertThat(preLoginSessionCookie).isNotNull();

        // Valid Argon2id login rotates session ID in Protected Authority SPRING_SESSION and issues XSRF-TOKEN
        MvcResult loginRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/login")
                .cookie(preLoginSessionCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"owner\",\"password\":\"OwnerMasterPassword#2026!\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.username").value("owner"))
            .andReturn();

        ownerSessionCookie = latestCookie(loginRes, "MCSESSION");
        ownerXsrfCookie = latestCookie(loginRes, "XSRF-TOKEN");
        Assertions.assertThat(ownerSessionCookie).isNotNull();
        Assertions.assertThat(ownerSessionCookie.getValue()).isNotEqualTo(preLoginSessionCookie.getValue());
        Assertions.assertThat(ownerXsrfCookie).isNotNull();

        // Missing X-XSRF-TOKEN on session-authenticated mutation is rejected with 403 MC-CSRF-INVALID
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/logout")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-CSRF-INVALID"));
    }

    @Test
    @Order(4)
    void webAuthnPasskeyEnrollmentAndCryptographicAssertionCeremony() throws Exception {
        WebAuthnTestAuthenticator authenticator = new WebAuthnTestAuthenticator();

        MvcResult regOptRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/passkeys/register/options")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"owner-yubikey\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();

        JsonNode regOpts = objectMapper.readTree(regOptRes.getResponse().getContentAsByteArray());
        String regChallenge = regOpts.get("challenge").asString();
        String userHandle = regOpts.get("user").get("id").asString();

        WebAuthnTestAuthenticator.EnrolledAttestationPayload attestation =
            authenticator.createRegistrationAttestation(regChallenge, "http://localhost:8080", "localhost");

        String verifyRegJson = objectMapper.writeValueAsString(Map.of(
            "label", "owner-yubikey",
            "credentialId", attestation.credentialId(),
            "rawId", attestation.credentialId(),
            "type", "public-key",
            "clientDataJSON", attestation.clientDataJson(),
            "attestationObject", attestation.attestationObject()
        ));

        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/passkeys/register/verify")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(verifyRegJson)
        )
            .andExpect(MockMvcResultMatchers.status().isNoContent());

        // Authenticate in a brand-new unauthenticated session using the FIDO2 passkey
        MvcResult authOptRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/passkeys/authenticate/options")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"owner\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();

        Cookie passkeyCeremonyCookie = latestCookie(authOptRes, "MCSESSION");
        Assertions.assertThat(passkeyCeremonyCookie).isNotNull();
        JsonNode authOpts = objectMapper.readTree(authOptRes.getResponse().getContentAsByteArray());
        String authChallenge = authOpts.get("challenge").asString();

        WebAuthnTestAuthenticator.SignedAssertionPayload assertion =
            authenticator.createAuthenticationAssertion(
                authChallenge,
                "http://localhost:8080",
                "localhost",
                userHandle
            );

        String verifyAuthJson = objectMapper.writeValueAsString(Map.of(
            "credentialId", assertion.credentialId(),
            "rawId", assertion.credentialId(),
            "type", "public-key",
            "clientDataJSON", assertion.clientDataJson(),
            "authenticatorData", assertion.authenticatorData(),
            "signature", assertion.signature(),
            "userHandle", assertion.userHandle()
        ));

        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/passkeys/authenticate/verify")
                .cookie(passkeyCeremonyCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(verifyAuthJson)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.username").value("owner"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.passkeyEnrolled").value(true));
    }

    @Test
    @Order(5)
    void oidcPkceS256EndToEndAuthorizationAndTokenExchange() throws Exception {
        String issuerBase = "http://127.0.0.1:" + oidcPort;
        String createIssuerJson = objectMapper.writeValueAsString(Map.of(
            "registrationId", "corp-sso",
            "displayName", "Corporate Keycloak",
            "issuerUri", issuerBase,
            "authorizationUri", issuerBase + "/authorize",
            "tokenUri", issuerBase + "/token",
            "clientId", "moneycraft-erp",
            "clientSecret", "super-secret-oidc-client-secret",
            "linkPrincipalUsername", "owner",
            "linkSubject", "oidc-sub-owner-001"
        ));

        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/admin/oidc-issuers")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createIssuerJson)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.registrationId").value("corp-sso"));

        MvcResult authUrlRes = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/auth/oidc/corp-sso/authorize")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();

        JsonNode authNode = objectMapper.readTree(authUrlRes.getResponse().getContentAsByteArray());
        String authUrl = authNode.get("authorizationUrl").asString();
        String state = authNode.get("state").asString();
        Map<String, String> queryParams = parseFormUrlEncoded(URI.create(authUrl).getRawQuery());
        Assertions.assertThat(queryParams.get("code_challenge_method")).isEqualTo("S256");
        EXPECTED_OIDC_CODE_CHALLENGE.set(queryParams.get("code_challenge"));

        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/oidc/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "code", "valid-oidc-auth-code",
                    "state", state
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.username").value("owner"));
    }

    @Test
    @Order(6)
    void multiCompanyCreationPg18TriggerSeedingTinkSivCursorsAndJcsIdempotency() throws Exception {
        String gstinA = "33AABCM1234F1Z" + ExactMoneyValidator.computeGstinMod36CheckChar("33AABCM1234F1Z");
        String gstinB = "29AABCM1234F1Z" + ExactMoneyValidator.computeGstinMod36CheckChar("29AABCM1234F1Z");

        // Missing Idempotency-Key must fail closed with 400
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"COMPA\",\"legalName\":\"Alpha Manufacturing Pvt Ltd\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isBadRequest())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-IDEMPOTENCY-KEY-REQUIRED"));

        String idemKeyA = UUID.randomUUID().toString();
        String createCompanyAJson = """
            {
              "code": "COMPA",
              "legalName": "Alpha Manufacturing Pvt Ltd",
              "mailingName": "Alpha Manufacturing Pvt Ltd",
              "countryCode": "IN",
              "stateCode": "33",
              "pinCode": "600001",
              "baseCurrencyCode": "INR",
              "currencySymbol": "₹",
              "currencyScale": 2,
              "financialYearFrom": "2025-04-01",
              "booksBeginningFrom": "2025-04-01",
              "features": {
                "maintainAccounts": true,
                "billWiseEnabled": true,
                "costCentresEnabled": false,
                "maintainInventory": true,
                "inventoryIntegrated": true,
                "gstEnabled": true,
                "multiGstinEnabled": false,
                "gstRegistrationType": "REGULAR",
                "gstin": "%s",
                "gstr1Periodicity": "MONTHLY",
                "gstEffectiveFrom": "2025-04-01",
                "tdsEnabled": true,
                "mcaEditLogEnforced": true
              }
            }
            """.formatted(gstinA);

        MvcResult createARes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", idemKeyA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createCompanyAJson)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.header().string("ETag", "\"v1\""))
            .andReturn();

        JsonNode compANode = objectMapper.readTree(createARes.getResponse().getContentAsByteArray());
        companyAId = UUID.fromString(compANode.get("id").asString());
        Assertions.assertThat(compANode.get("panMasked").asString()).isEqualTo("AABCM****F");

        // Replay exact same semantic JSON with reordered keys & compact formatting under same Idempotency-Key
        String reorderedEquivalentJson = jcsCanonicalizer.canonicalizeJson(createCompanyAJson);
        MvcResult replayRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", idemKeyA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reorderedEquivalentJson)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.header().string("X-Idempotency-Replayed", "true"))
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(replayRes.getResponse().getContentAsByteArray()).get("id").asString()
        ).isEqualTo(companyAId.toString());

        // Reusing same Idempotency-Key with different payload must fail closed with 409 MC-IDEMPOTENCY-PAYLOAD-MISMATCH
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", idemKeyA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"DIFFERENT\",\"legalName\":\"Different Corp\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isConflict())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-IDEMPOTENCY-PAYLOAD-MISMATCH"));

        // Create Company B in Karnataka (state 29) with SAME underlying PAN to prove domain-separated AES256_SIV
        String createCompanyBJson = """
            {
              "code": "COMPB",
              "legalName": "Beta Logistics Pvt Ltd",
              "countryCode": "IN",
              "stateCode": "29",
              "pinCode": "560001",
              "baseCurrencyCode": "INR",
              "currencySymbol": "₹",
              "currencyScale": 2,
              "financialYearFrom": "2025-04-01",
              "booksBeginningFrom": "2025-04-01",
              "features": {
                "gstEnabled": true,
                "gstRegistrationType": "REGULAR",
                "gstin": "%s",
                "gstr1Periodicity": "QUARTERLY",
                "tdsEnabled": true,
                "mcaEditLogEnforced": false
              }
            }
            """.formatted(gstinB);

        MvcResult createBRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createCompanyBJson)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        companyBId = UUID.fromString(
            objectMapper.readTree(createBRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        // Verify PostgreSQL 18 trigger seeding inside Company A
        TransactionTemplate txTemplate = new TransactionTemplate(businessTxManager);
        TenantContextHolder.runWithCompany(companyAId, () -> txTemplate.executeWithoutResult(status -> {
            Integer groupCount = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_account_group",
                Integer.class
            );
            Integer ledgerCount = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_ledger",
                Integer.class
            );
            Integer voucherTypeCount = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_voucher_type",
                Integer.class
            );
            Integer godownCount = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_godown",
                Integer.class
            );
            Integer tdsCount = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_tds_nature",
                Integer.class
            );
            Integer periodCount = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_period",
                Integer.class
            );

            Assertions.assertThat(groupCount).isEqualTo(28);
            Assertions.assertThat(ledgerCount).isEqualTo(2);
            Assertions.assertThat(voucherTypeCount).isEqualTo(24);
            Assertions.assertThat(godownCount).isEqualTo(1);
            Assertions.assertThat(tdsCount).isEqualTo(6);
            Assertions.assertThat(periodCount).isEqualTo(12);
        }));

        // Verify Tink AES256_SIV determinism within tenant and domain separation across tenants
        byte[] sivA1 = tinkCryptoService.encryptDeterministicSiv(companyAId, "mc_company", "pan_siv", "AABCM1234F");
        byte[] sivA2 = tinkCryptoService.encryptDeterministicSiv(companyAId, "mc_company", "pan_siv", "AABCM1234F");
        byte[] sivB1 = tinkCryptoService.encryptDeterministicSiv(companyBId, "mc_company", "pan_siv", "AABCM1234F");
        Assertions.assertThat(sivA1).isEqualTo(sivA2);
        Assertions.assertThat(sivA1).isNotEqualTo(sivB1);

        MoneyCraftPrincipal ownerPrincipal = rbacAuthorizationService.loadPrincipalProfile(ownerPrincipalId);
        String decryptedPan = TenantContextHolder.callWithCompany(
            companyAId,
            () -> companyService.decryptCompanyPan(ownerPrincipal, companyAId)
        );
        Assertions.assertThat(decryptedPan).isEqualTo("AABCM1234F");

        // Verify HS256 signed keyset cursor encode/decode & tamper rejection
        UUID lastEntity = UUID.randomUUID();
        String cursorToken = cursorCodec.encode(
            ownerPrincipalId,
            companyAId,
            "GET /companies/ledgers",
            "filterHash01",
            "Cash",
            lastEntity,
            Instant.now().plusSeconds(600)
        );
        CursorPayload verifiedCursor = cursorCodec.decodeAndVerify(
            cursorToken,
            ownerPrincipalId,
            companyAId,
            "GET /companies/ledgers",
            "filterHash01"
        );
        Assertions.assertThat(verifiedCursor.lastEntityId()).isEqualTo(lastEntity);

        Assertions.assertThatThrownBy(() ->
            cursorCodec.decodeAndVerify(
                cursorToken,
                ownerPrincipalId,
                companyBId,
                "GET /companies/ledgers",
                "filterHash01"
            )
        )
            .isInstanceOf(MoneyCraftException.class)
            .extracting(ex -> ((MoneyCraftException) ex).code())
            .isEqualTo("MC-CURSOR-INVALID");
    }

    @Test
    @Order(7)
    void etagOptimisticConcurrencyMcaEditLogGuardAndFiscalPeriodTransitions() throws Exception {
        String gstinA = "33AABCM1234F1Z" + ExactMoneyValidator.computeGstinMod36CheckChar("33AABCM1234F1Z");

        // 1. Missing If-Match returns 428
        mockMvc.perform(
            MockMvcRequestBuilders.put("/api/v1/companies/" + companyAId)
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"COMPA\",\"legalName\":\"Alpha Mfg Updated\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isPreconditionRequired())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-IF-MATCH-REQUIRED"));

        // 2. Stale If-Match returns 412
        mockMvc.perform(
            MockMvcRequestBuilders.put("/api/v1/companies/" + companyAId)
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"v99\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"COMPA\",\"legalName\":\"Alpha Mfg Updated\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isPreconditionFailed())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-CONCURRENCY-STALE-VERSION"));

        // 3. Attempting to disable mcaEditLogEnforced once true returns 403 MC-EDITLOG-MANDATORY
        String disableEditLogPayload = """
            {
              "code": "COMPA",
              "legalName": "Alpha Manufacturing Pvt Ltd",
              "stateCode": "33",
              "financialYearFrom": "2025-04-01",
              "booksBeginningFrom": "2025-04-01",
              "features": {
                "gstEnabled": true,
                "gstin": "%s",
                "mcaEditLogEnforced": false
              }
            }
            """.formatted(gstinA);

        mockMvc.perform(
            MockMvcRequestBuilders.put("/api/v1/companies/" + companyAId)
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"v1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(disableEditLogPayload)
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-EDITLOG-MANDATORY"));

        // 4. Valid alteration with If-Match "v1" increments ETag to "v2" and records MCA Edit Log diff
        String validAlterPayload = """
            {
              "code": "COMPA",
              "legalName": "Alpha Precision Engineering Pvt Ltd",
              "stateCode": "33",
              "pinCode": "600002",
              "financialYearFrom": "2025-04-01",
              "booksBeginningFrom": "2025-04-01",
              "features": {
                "gstEnabled": true,
                "gstin": "%s",
                "mcaEditLogEnforced": true
              }
            }
            """.formatted(gstinA);

        mockMvc.perform(
            MockMvcRequestBuilders.put("/api/v1/companies/" + companyAId)
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"v1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validAlterPayload)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.header().string("ETag", "\"v2\""))
            .andExpect(MockMvcResultMatchers.jsonPath("$.versionNo").value(2));

        TransactionTemplate txTemplate = new TransactionTemplate(businessTxManager);
        TenantContextHolder.runWithCompany(companyAId, () -> txTemplate.executeWithoutResult(status -> {
            Integer editLogVersions = businessJdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM mc_edit_log_version
                 WHERE company_id = ? AND entity_type = 'COMPANY' AND entity_id = ?
                """,
                Integer.class,
                companyAId,
                companyAId
            );
            String v2ChangedFields = businessJdbcTemplate.queryForObject(
                """
                SELECT changed_fields::text FROM mc_edit_log_version
                 WHERE company_id = ? AND entity_type = 'COMPANY' AND entity_id = ? AND version_no = 2
                """,
                String.class,
                companyAId,
                companyAId
            );
            Assertions.assertThat(editLogVersions).isEqualTo(2);
            Assertions.assertThat(v2ChangedFields).contains("legalName").contains("pinCode");
        }));

        // 5. Fiscal years & periods: overlapping FY rejected with 409, next FY created with 201, period transitioned
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies/" + companyAId + "/fiscal-years")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"startDate\":\"2025-10-01\",\"endDate\":\"2026-09-30\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isConflict())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-ACC-FY-OVERLAP"));

        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies/" + companyAId + "/fiscal-years")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"startDate\":\"2026-04-01\",\"endDate\":\"2027-03-31\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("FY2026-27"));

        MvcResult periodsRes = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/companies/" + companyAId + "/periods")
                .cookie(ownerSessionCookie)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();

        JsonNode periodsArr = objectMapper.readTree(periodsRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(periodsArr.size()).isEqualTo(24);
        companyAFirstPeriodId = UUID.fromString(periodsArr.get(0).get("id").asString());

        mockMvc.perform(
            MockMvcRequestBuilders.patch(
                "/api/v1/companies/" + companyAId + "/periods/" + companyAFirstPeriodId
            )
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"v1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"SOFT_LOCKED\",\"reason\":\"GSTR-1 filed\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.header().string("ETag", "\"v2\""))
            .andExpect(MockMvcResultMatchers.jsonPath("$.state").value("SOFT_LOCKED"));
    }

    @Test
    @Order(8)
    void crossCompanyRbacIsolationPostgresForceRlsAndScopedServiceApiKeys() throws Exception {
        // 1. Provision operator 'alice' granted ONLY on Company A
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/admin/principals")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "alice",
                      "displayName": "Alice Accountant",
                      "password": "AliceStrongPassword#2026",
                      "systemRoles": []
                    }
                    """)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        mockMvc.perform(
            MockMvcRequestBuilders.put("/api/v1/admin/principals/alice/grants")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "companyId", companyAId.toString(),
                    "roleCode", "SENIOR_ACCOUNTANT",
                    "backdatedDaysLimit", 7
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.roleCode").value("SENIOR_ACCOUNTANT"));

        // Sign in as alice
        MvcResult aliceLogin = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"AliceStrongPassword#2026\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Cookie aliceSessionCookie = latestCookie(aliceLogin, "MCSESSION");
        Assertions.assertThat(aliceSessionCookie).isNotNull();

        // Alice sees ONLY Company A in /companies, succeeds on Company A, and gets 403 on Company B!
        MvcResult aliceList = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/companies").cookie(aliceSessionCookie)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode aliceCompanies = objectMapper.readTree(aliceList.getResponse().getContentAsByteArray());
        Assertions.assertThat(aliceCompanies.size()).isEqualTo(1);
        Assertions.assertThat(aliceCompanies.get(0).get("id").asString()).isEqualTo(companyAId.toString());

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/companies/" + companyAId + "/periods").cookie(aliceSessionCookie)
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/companies/" + companyBId + "/periods").cookie(aliceSessionCookie)
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-RBAC-COMPANY-FORBIDDEN"));

        // 2. Prove PostgreSQL 18 FORCE ROW LEVEL SECURITY at the raw SQL layer (zero WHERE clause!)
        TransactionTemplate txTemplate = new TransactionTemplate(businessTxManager);
        Integer uncontextualizedRows = txTemplate.execute(status ->
            businessJdbcTemplate.queryForObject("SELECT COUNT(*) FROM mc_period", Integer.class)
        );
        Integer companyBRowsOnly = TenantContextHolder.callWithCompany(companyBId, () ->
            txTemplate.execute(status ->
                businessJdbcTemplate.queryForObject("SELECT COUNT(*) FROM mc_period", Integer.class)
            )
        );
        Integer companyARowsOnly = TenantContextHolder.callWithCompany(companyAId, () ->
            txTemplate.execute(status ->
                businessJdbcTemplate.queryForObject("SELECT COUNT(*) FROM mc_period", Integer.class)
            )
        );
        Assertions.assertThat(uncontextualizedRows).isZero();
        Assertions.assertThat(companyBRowsOnly).isEqualTo(12);
        Assertions.assertThat(companyARowsOnly).isEqualTo(24);

        // 3. Issue scoped Service API key restricted to Company A & GET endpoints only
        MvcResult svcKeyRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/admin/service-keys")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "label", "bi-readonly-connector",
                    "companyIds", java.util.List.of(companyAId.toString()),
                    "endpointAllowlist", java.util.List.of("GET:/api/v1/companies/**")
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        String rawServiceKey = objectMapper.readTree(svcKeyRes.getResponse().getContentAsByteArray())
            .get("rawSecretOnce").asString();
        Assertions.assertThat(rawServiceKey).startsWith("mc_sk_");

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/companies/" + companyAId)
                .header("Authorization", "Bearer " + rawServiceKey)
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/companies/" + companyBId)
                .header("Authorization", "Bearer " + rawServiceKey)
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-RBAC-COMPANY-FORBIDDEN"));

        mockMvc.perform(
            MockMvcRequestBuilders.patch(
                "/api/v1/companies/" + companyAId + "/periods/" + companyAFirstPeriodId
            )
                .header("Authorization", "Bearer " + rawServiceKey)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"OPEN\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-SERVICE-KEY-ENDPOINT-FORBIDDEN"));
    }

    @Test
    @Order(9)
    void auditChainVerificationSplitBrainReceiptDivergenceQuarantineAndRecoveryCeremony() throws Exception {
        // 1. MC-AUDIT-2 hash chain verifies cleanly across installation & company streams
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/admin/audit/verify").cookie(ownerSessionCookie)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.verified").value(true));

        // 2. Simulate split-brain backup/restore divergence between Authority ma_receipt and Business mirror
        businessJdbcTemplate.update(
            """
            DELETE FROM mc_admin_receipt_mirror
             WHERE receipt_seq = (SELECT MAX(receipt_seq) FROM mc_admin_receipt_mirror)
            """
        );

        // Next mutating call must immediately trigger fail-closed QUARANTINE (503 MC-SYSTEM-QUARANTINED)
        mockMvc.perform(
            MockMvcRequestBuilders.patch(
                "/api/v1/companies/" + companyAId + "/periods/" + companyAFirstPeriodId
            )
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"OPEN\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isServiceUnavailable())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-SYSTEM-QUARANTINED"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.errorCode").value("MC-SYSTEM-QUARANTINED"));

        Assertions.assertThat(authorityGuard.currentSnapshot().securityState())
            .isEqualTo(InstallationSecurityState.QUARANTINED);
        Assertions.assertThat(authorityGuardHealthIndicator.health().getStatus())
            .isEqualTo(Status.OUT_OF_SERVICE);

        // 3. Invalid recovery receipt must be rejected with 403
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/admin/quarantine/recover")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recoveryReceipt\":\"MCRK-00000000-00000000-00000000-00000000\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-RECOVERY-RECEIPT-INVALID"));

        // 4. Valid bootstrap recoveryReceiptCode reconciles mirrors, bumps fence_epoch, and restores ACTIVE state
        long epochBeforeRecovery = authorityGuard.currentSnapshot().fenceEpoch();
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/admin/quarantine/recover")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("recoveryReceipt", recoveryReceiptCode)))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.securityState").value("ACTIVE"));

        Assertions.assertThat(authorityGuard.currentSnapshot().fenceEpoch())
            .isGreaterThan(epochBeforeRecovery);
        Assertions.assertThat(authorityGuardHealthIndicator.health().getStatus())
            .isEqualTo(Status.UP);

        // Mutations succeed again after recovery!
        mockMvc.perform(
            MockMvcRequestBuilders.patch(
                "/api/v1/companies/" + companyAId + "/periods/" + companyAFirstPeriodId
            )
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"OPEN\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.state").value("OPEN"));
    }

    @Test
    @Order(10)
    void oneShotMigratorCliModeRunsCleanlyWithoutContendingWithWriterLease() {
        SpringApplication migratorApp = new SpringApplication(MoneyCraftApplication.class);
        migratorApp.setWebApplicationType(WebApplicationType.NONE);

        try (ConfigurableApplicationContext ctx = migratorApp.run(
            "--spring.profiles.active=migrator",
            "--moneycraft.migrator.only=true",
            "--moneycraft.migrator.exit-on-complete=false",
            "--moneycraft.datasource.authority.jdbc-url=" + AUTHORITY_PG.getJdbcUrl(),
            "--moneycraft.datasource.authority.username=" + MIGRATOR_USER,
            "--moneycraft.datasource.authority.password=" + MIGRATOR_PASS,
            "--moneycraft.datasource.authority.migrator-username=" + MIGRATOR_USER,
            "--moneycraft.datasource.authority.migrator-password=" + MIGRATOR_PASS,
            "--moneycraft.datasource.business.jdbc-url=" + BUSINESS_PG.getJdbcUrl(),
            "--moneycraft.datasource.business.username=" + MIGRATOR_USER,
            "--moneycraft.datasource.business.password=" + MIGRATOR_PASS,
            "--moneycraft.datasource.business.migrator-username=" + MIGRATOR_USER,
            "--moneycraft.datasource.business.migrator-password=" + MIGRATOR_PASS,
            "--moneycraft.crypto.master-kek=" + TEST_KEK
        )) {
            Assertions.assertThat(ctx.isActive()).isTrue();
            Assertions.assertThat(authorityGuard.isLeaseHealthy()).isTrue();
        }
    }

    private static Cookie latestCookie(MvcResult result, String cookieName) {
        Cookie[] cookies = result.getResponse().getCookies();
        for (int i = cookies.length - 1; i >= 0; i--) {
            if (cookieName.equals(cookies[i].getName())) {
                return cookies[i];
            }
        }
        return null;
    }

    private static Map<String, String> parseFormUrlEncoded(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return map;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(
                    URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)
                );
            }
        }
        return map;
    }
}
