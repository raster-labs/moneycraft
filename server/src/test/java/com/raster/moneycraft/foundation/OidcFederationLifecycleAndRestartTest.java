package com.raster.moneycraft.foundation;

import com.raster.moneycraft.MoneyCraftApplication;
import com.raster.moneycraft.foundation.auth.AuthDtos;
import com.raster.moneycraft.foundation.auth.IssuerConfig2;
import com.raster.moneycraft.foundation.auth.OidcPkceService;
import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.CompanyPermissions;
import com.raster.moneycraft.foundation.security.CompanyRole;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.OidcClockSource;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end verification of Document 47 Rev 2 (MC-FED-01) OIDC Federation Lifecycle,
 * JWKS RS256/ES256 ID Token verification, ordinary-restart continuity vs. changed-profile
 * per-person invalidation, Increment X RBAC permission constants, and Document 30 neutral
 * /imports/legacy-1800/stage routing against real PostgreSQL 18 Dual-Cluster Testcontainers.
 */
@SpringBootTest(classes = MoneyCraftApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OidcFederationLifecycleAndRestartTest {

    private static final UUID ISSUER_ID = UUID.fromString("00000000-0000-4000-8000-000000000700");
    private static final String DANA_SUB_701 = "00000000-0000-4000-8000-000000000701";
    private static final UUID FED_COMPONENT_702 = UUID.fromString("00000000-0000-4000-8000-000000000702");
    private static final UUID GROUP_703 = UUID.fromString("00000000-0000-4000-8000-000000000703");
    private static final UUID GROUP_704 = UUID.fromString("00000000-0000-4000-8000-000000000704");
    private static final String UNLINKED_SUB_799 = "00000000-0000-4000-8000-000000000799";

    private static final Instant T0_BASE = Instant.parse("2026-11-13T12:00:00Z");
    private static final String QUALIFICATION_EVIDENCE = "qualified-ad-keycloak-bound-20s-fixture-v1";

    private static DualPostgres18TestcontainersSupport.ClusterPair isolatedCluster;
    private static HttpServer mockKeycloak;
    private static int keycloakPort;

    private static KeyPair custodianEd25519KeyPair;
    private static KeyPair rsaJwksKeyPair;
    private static KeyPair ecJwksKeyPair;

    private static final AtomicReference<Instant> PINNED_UTC = new AtomicReference<>(T0_BASE);
    private static final AtomicLong PINNED_NANO = new AtomicLong(1_000_000_000_000L);

    private static final AtomicBoolean DANA_ENABLED_IN_IDP = new AtomicBoolean(true);
    private static final AtomicReference<List<String>> DANA_GROUPS_IN_IDP = new AtomicReference<>(
        List.of(GROUP_703.toString(), GROUP_704.toString())
    );
    private static final AtomicInteger FORCE_ADMIN_HTTP_STATUS = new AtomicInteger(0);
    private static final AtomicBoolean DUPLICATE_GROUP_IN_RESPONSE = new AtomicBoolean(false);
    private static final AtomicBoolean OVERFLOW_4100_GROUPS = new AtomicBoolean(false);
    private static final AtomicReference<String> NEXT_AUTH_CODE_SUBJECT = new AtomicReference<>(DANA_SUB_701);
    private static final AtomicReference<String> NEXT_AUTH_CODE_NONCE = new AtomicReference<>("");
    private static final AtomicReference<String> NEXT_JWT_ALG = new AtomicReference<>("RS256");
    private static final AtomicBoolean TAMPER_ID_TOKEN_SIGNATURE = new AtomicBoolean(false);
    private static final AtomicReference<String> OVERRIDE_ISS_CLAIM = new AtomicReference<>(null);
    private static final AtomicInteger TOKEN_EXCHANGE_CALL_COUNT = new AtomicInteger(0);

    private static UUID installationId;
    private static UUID incarnation;
    private static UUID c1CompanyId;
    private static UUID c2CompanyId;
    private static UUID danaPrincipalId;
    private static UUID danaLinkId;

    private static Cookie ownerSessionCookie;
    private static Cookie ownerXsrfCookie;
    private static String serviceKeyBearer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JcsCanonicalizer jcsCanonicalizer;

    @Autowired
    private OidcPkceService oidcPkceService;

    @Autowired
    private OidcClockSource oidcClockSource;

    @Autowired
    private RbacAuthorizationService rbacAuthorizationService;

    @Autowired
    private MoneyCraftProperties moneyCraftProperties;

    @Autowired
    @Qualifier("authorityJdbcTemplate")
    private JdbcTemplate authorityJdbcTemplate;

    @BeforeAll
    static void startContainersAndMockKeycloak() throws Exception {
        isolatedCluster = DualPostgres18TestcontainersSupport.startIsolatedClusterPair();

        KeyPairGenerator edGen = KeyPairGenerator.getInstance("Ed25519");
        custodianEd25519KeyPair = edGen.generateKeyPair();

        KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
        rsaGen.initialize(2048);
        rsaJwksKeyPair = rsaGen.generateKeyPair();

        KeyPairGenerator ecGen = KeyPairGenerator.getInstance("EC");
        ecGen.initialize(new ECGenParameterSpec("secp256r1"));
        ecJwksKeyPair = ecGen.generateKeyPair();

        mockKeycloak = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        keycloakPort = mockKeycloak.getAddress().getPort();

        mockKeycloak.createContext("/realms/moneycraft-test/protocol/openid-connect/certs", exchange -> {
            String jwksJson = buildJwksDocument();
            writeJsonResponse(exchange, 200, jwksJson);
        });

        mockKeycloak.createContext("/realms/moneycraft-test/protocol/openid-connect/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseForm(form);
            String grantType = params.getOrDefault("grant_type", "");
            if ("client_credentials".equals(grantType)) {
                if (!"mc-lifecycle-reader".equals(params.get("client_id"))
                    || !"lifecycle-secret-prod-safe-001".equals(params.get("client_secret"))) {
                    writeJsonResponse(exchange, 401, "{\"error\":\"invalid_client\"}");
                    return;
                }
                writeJsonResponse(
                    exchange,
                    200,
                    "{\"access_token\":\"kc-admin-ro-token\",\"token_type\":\"Bearer\",\"expires_in\":300}"
                );
                return;
            }

            if ("authorization_code".equals(grantType)) {
                TOKEN_EXCHANGE_CALL_COUNT.incrementAndGet();
                if (!"valid-pkce-code".equals(params.get("code"))) {
                    writeJsonResponse(exchange, 400, "{\"error\":\"invalid_grant\"}");
                    return;
                }
                String idToken = signIdToken(
                    NEXT_JWT_ALG.get(),
                    NEXT_AUTH_CODE_SUBJECT.get(),
                    NEXT_AUTH_CODE_NONCE.get(),
                    PINNED_UTC.get().plusSeconds(300)
                );
                writeJsonResponse(
                    exchange,
                    200,
                    "{\"access_token\":\"user-tok\",\"token_type\":\"Bearer\",\"id_token\":\"" + idToken + "\"}"
                );
                return;
            }
            writeJsonResponse(exchange, 400, "{\"error\":\"unsupported_grant_type\"}");
        });

        mockKeycloak.createContext("/admin/realms/moneycraft-test/users/", exchange -> {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer kc-admin-ro-token".equals(authHeader)) {
                writeJsonResponse(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            int forced = FORCE_ADMIN_HTTP_STATUS.get();
            if (forced == 302) {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + keycloakPort + "/redirected");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            if (forced > 0) {
                writeJsonResponse(exchange, forced, "{\"error\":\"forced_fault\"}");
                return;
            }

            URI reqUri = exchange.getRequestURI();
            String path = reqUri.getPath();
            String prefix = "/admin/realms/moneycraft-test/users/";
            String remainder = path.substring(prefix.length());

            if (remainder.endsWith("/groups")) {
                String subject = remainder.substring(0, remainder.length() - "/groups".length());
                handleAdminGroupsGet(exchange, subject, parseForm(reqUri.getRawQuery()));
            } else {
                handleAdminUserGet(exchange, remainder);
            }
        });

        mockKeycloak.start();
    }

    @AfterAll
    static void stopMockKeycloak() {
        if (mockKeycloak != null) {
            mockKeycloak.stop(0);
        }
    }

    @DynamicPropertySource
    static void bindProperties(DynamicPropertyRegistry registry) {
        DualPostgres18TestcontainersSupport.bindClusterProperties(registry, isolatedCluster);
        registry.add("moneycraft.security.allow-test-provider-profile", () -> "true");
        registry.add(
            "moneycraft.security.provider-custodian-public-key",
            () -> Base64.getEncoder().encodeToString(custodianEd25519KeyPair.getPublic().getEncoded())
        );
        registry.add("moneycraft.security.secret-registry.oidc-client-secret-ref", () -> "oidc-browser-secret-001");
        registry.add(
            "moneycraft.security.secret-registry.oidc-lifecycle-secret-ref",
            () -> "lifecycle-secret-prod-safe-001"
        );
        registry.add("moneycraft.security.evidence-registry.qual-ad-keycloak-01", () -> QUALIFICATION_EVIDENCE);
    }

    @Test
    @Order(1)
    void providerConfigValidationExamplePlaceholderRejectionAndPublicIssuersList() throws Exception {
        oidcPkceService.setLifecycleWorkerPaused(true);
        pinClock(T0_BASE);

        // 1. Bootstrap installation + create C1 & C2 + create Dana principal + explicit C1 report.read grant
        MvcResult initRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/system/initialise")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "installationLabel": "Federation Test Cluster",
                      "ownerUsername": "owner",
                      "ownerDisplayName": "Local Owner",
                      "password": "OwnerMasterPassword#2026!"
                    }
                    """)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        ownerSessionCookie = latestCookie(initRes, "MCSESSION");
        ownerXsrfCookie = latestCookie(initRes, "XSRF-TOKEN");

        installationId = authorityJdbcTemplate.queryForObject(
            "SELECT installation_id FROM ma_state WHERE singleton = 1",
            UUID.class
        );
        incarnation = authorityJdbcTemplate.queryForObject(
            "SELECT incarnation FROM ma_state WHERE singleton = 1",
            UUID.class
        );

        c1CompanyId = createTestCompany("FED-C1", "Federation Company One Pvt Ltd", "33AABCF1111F1Z");
        c2CompanyId = createTestCompany("FED-C2", "Federation Company Two Pvt Ltd", "29AABCF2222F1Z");

        // Issue a scoped S1 service API key on C1 to prove independence from OIDC lifecycle
        MvcResult svcRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/admin/service-keys")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "label", "s1-component-key",
                    "companyIds", List.of(c1CompanyId.toString()),
                    "endpointAllowlist", List.of("GET:/api/v1/companies/**")
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        serviceKeyBearer = objectMapper.readTree(svcRes.getResponse().getContentAsByteArray())
            .get("rawSecretOnce").asString();

        // Create Dana person and grant ONLY explicit report.read on C1
        MvcResult danaRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/admin/principals")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "dana",
                      "displayName": "Dana Federated",
                      "email": "dana@corp.example",
                      "password": "DanaFallbackPassword#2026",
                      "systemRoles": []
                    }
                    """)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        danaPrincipalId = UUID.fromString(
            objectMapper.readTree(danaRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        mockMvc.perform(
            MockMvcRequestBuilders.put("/api/v1/admin/principals/dana/grants")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "companyId", c1CompanyId.toString(),
                    "roleCode", "READ_ONLY_VIEWER",
                    "permissions", List.of(CompanyPermissions.REPORT_READ)
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        // 2. Verify B12 deployment/provider-lifecycle.example.json rejects UNAVAILABLE placeholders
        Path exampleFile = Path.of("..", "deployment", "provider-lifecycle.example.json").normalize();
        if (!Files.exists(exampleFile)) {
            exampleFile = Path.of("deployment", "provider-lifecycle.example.json");
        }
        Assertions.assertThat(Files.isRegularFile(exampleFile)).isTrue();
        String exampleJson = Files.readString(exampleFile, StandardCharsets.UTF_8);
        Assertions.assertThatThrownBy(() -> oidcPkceService.applySignedProviderConfig(exampleJson))
            .isInstanceOf(MoneyCraftException.class)
            .extracting(ex -> ((MoneyCraftException) ex).code())
            .isEqualTo("MC-PROVIDER-CONFIG-INVALID");

        // 3. Reject forbidden Increment-X / admin operation inside provider groupMappings
        String forbiddenOpJson = buildSignedProviderConfigJson(
            "ENABLED",
            null,
            T0_BASE.minusSeconds(60),
            T0_BASE.plusSeconds(3600),
            List.of(Map.of(
                "companyId", c1CompanyId.toString(),
                "operation", CompanyPermissions.EXTENSION_MANAGE,
                "fieldGroup", "NONE"
            )),
            false
        );
        Assertions.assertThatThrownBy(() -> oidcPkceService.applySignedProviderConfig(forbiddenOpJson))
            .isInstanceOf(MoneyCraftException.class)
            .extracting(ex -> ((MoneyCraftException) ex).code())
            .isEqualTo("MC-PROVIDER-CONFIG-INVALID");

        // 4. Reject bad Ed25519 signature and expired new config
        String badSigJson = buildSignedProviderConfigJson(
            "ENABLED",
            null,
            T0_BASE.minusSeconds(60),
            T0_BASE.plusSeconds(3600),
            defaultC1Grants(),
            true
        );
        Assertions.assertThatThrownBy(() -> oidcPkceService.applySignedProviderConfig(badSigJson))
            .isInstanceOf(MoneyCraftException.class)
            .extracting(ex -> ((MoneyCraftException) ex).code())
            .isEqualTo("MC-PROVIDER-CONFIG-SIGNATURE-INVALID");

        String expiredNewJson = buildSignedProviderConfigJson(
            "ENABLED",
            null,
            T0_BASE.minusSeconds(7200),
            T0_BASE.minusSeconds(60),
            defaultC1Grants(),
            false
        );
        Assertions.assertThatThrownBy(() -> oidcPkceService.applySignedProviderConfig(expiredNewJson))
            .isInstanceOf(MoneyCraftException.class)
            .extracting(ex -> ((MoneyCraftException) ex).code())
            .isEqualTo("MC-PROVIDER-CONFIG-EXPIRED");

        // 5. Install valid signed MC-PROVIDER-CONFIG-1 v1 and link Dana's canonical UUID subject
        String validV1Json = buildSignedProviderConfigJson(
            "ENABLED",
            null,
            T0_BASE.minusSeconds(60),
            T0_BASE.plusSeconds(3600),
            defaultC1Grants(),
            false
        );
        boolean changed = oidcPkceService.applySignedProviderConfig(validV1Json);
        Assertions.assertThat(changed).isTrue();

        danaLinkId = UUID.randomUUID();
        authorityJdbcTemplate.update(
            """
            INSERT INTO ma_oidc_link (id, principal_id, issuer_id, subject, external_email, state)
            VALUES (?, ?, ?, ?, 'dana@corp.example', 'ACTIVE')
            """,
            danaLinkId,
            danaPrincipalId,
            ISSUER_ID,
            DANA_SUB_701
        );
        authorityJdbcTemplate.update(
            "UPDATE ma_principal SET auth_authority = 'OIDC', issuer_id = ? WHERE principal_id = ?",
            ISSUER_ID,
            danaPrincipalId
        );

        // 6. Verify unauthenticated GET /api/v1/auth/oidc/issuers exposes only safe DTO fields
        MvcResult publicIssuersRes = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/oidc/issuers"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode publicArr = objectMapper.readTree(publicIssuersRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(publicArr.size()).isEqualTo(1);
        Assertions.assertThat(publicArr.get(0).get("id").asString()).isEqualTo(ISSUER_ID.toString());
        Assertions.assertThat(publicArr.get(0).get("registrationId").asString()).isEqualTo("keycloak-ad");
        Assertions.assertThat(publicArr.get(0).has("clientSecret")).isFalse();
    }

    @Test
    @Order(2)
    void providerPositiveSweepConservativeTimestampJwksRs256Es256AndIncrementXPermissions() throws Exception {
        pinClock(T0_BASE);
        DANA_ENABLED_IN_IDP.set(true);
        DANA_GROUPS_IN_IDP.set(List.of(GROUP_703.toString(), GROUP_704.toString()));
        FORCE_ADMIN_HTTP_STATUS.set(0);

        // 1. Verify RS256 JWKS signature rejection when tampered or wrong iss
        TAMPER_ID_TOKEN_SIGNATURE.set(true);
        AuthDtos.OidcAuthorizeResponse tamperedRedirect = oidcPkceService.buildAuthorizationRedirect("keycloak-ad");
        extractAndBindNonce(tamperedRedirect.authorizationUrl());
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/oidc/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "code", "valid-pkce-code",
                    "state", tamperedRedirect.state()
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isUnauthorized())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-OIDC-IDTOKEN-SIGNATURE-INVALID"));
        TAMPER_ID_TOKEN_SIGNATURE.set(false);

        OVERRIDE_ISS_CLAIM.set("https://wrong-issuer.example");
        AuthDtos.OidcAuthorizeResponse badIssRedirect = oidcPkceService.buildAuthorizationRedirect("keycloak-ad");
        extractAndBindNonce(badIssRedirect.authorizationUrl());
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/oidc/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "code", "valid-pkce-code",
                    "state", badIssRedirect.state()
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isUnauthorized())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-OIDC-ISS-MISMATCH"));
        OVERRIDE_ISS_CLAIM.set(null);

        // 2. Complete valid OIDC callback using ES256 JWKS + augmented exact-subject sweep at t0!
        NEXT_JWT_ALG.set("ES256");
        Cookie danaOidcSession = performOidcLoginForSubject(DANA_SUB_701);
        NEXT_JWT_ALG.set("RS256");

        Instant expectedS = T0_BASE.minusSeconds(25);
        Instant issuerChecked = authorityJdbcTemplate.queryForObject(
            "SELECT checked_at FROM ma_issuer WHERE id = ?",
            (rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant(),
            ISSUER_ID
        );
        Instant linkChecked = authorityJdbcTemplate.queryForObject(
            "SELECT checked_at FROM ma_oidc_link WHERE id = ?",
            (rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant(),
            danaLinkId
        );
        Assertions.assertThat(issuerChecked).isEqualTo(expectedS);
        Assertions.assertThat(linkChecked).isEqualTo(expectedS);

        // Verify pure checked_at renewal on unchanged sweep does NOT bump version_no or authority_epoch
        long linkVBefore = authorityJdbcTemplate.queryForObject(
            "SELECT version_no FROM ma_oidc_link WHERE id = ?",
            Long.class,
            danaLinkId
        );
        long epochBefore = authorityJdbcTemplate.queryForObject(
            "SELECT authority_epoch FROM ma_state WHERE singleton = 1",
            Long.class
        );
        pinClock(T0_BASE.plusSeconds(10));
        Assertions.assertThat(oidcPkceService.runLifecycleSweep(ISSUER_ID)).isTrue();
        long linkVAfter = authorityJdbcTemplate.queryForObject(
            "SELECT version_no FROM ma_oidc_link WHERE id = ?",
            Long.class,
            danaLinkId
        );
        long epochAfter = authorityJdbcTemplate.queryForObject(
            "SELECT authority_epoch FROM ma_state WHERE singleton = 1",
            Long.class
        );
        Assertions.assertThat(linkVAfter).isEqualTo(linkVBefore);
        Assertions.assertThat(epochAfter).isEqualTo(epochBefore);

        // Verify exact PROVIDER grants on C1 (company.read, master.read) + EXPLICIT report.read, zero on C2!
        MoneyCraftPrincipal danaProfile = rbacAuthorizationService.loadPrincipalProfile(danaPrincipalId);
        Assertions.assertThat(
            rbacAuthorizationService.hasCompanyPermission(danaProfile, c1CompanyId, CompanyPermissions.COMPANY_READ)
        ).isTrue();
        Assertions.assertThat(
            rbacAuthorizationService.hasCompanyPermission(danaProfile, c1CompanyId, CompanyPermissions.MASTER_READ)
        ).isTrue();
        Assertions.assertThat(
            rbacAuthorizationService.hasCompanyPermission(danaProfile, c1CompanyId, CompanyPermissions.REPORT_READ)
        ).isTrue();
        Assertions.assertThat(
            rbacAuthorizationService.hasCompanyPermission(danaProfile, c1CompanyId, CompanyPermissions.VOUCHER_POST)
        ).isFalse();
        Assertions.assertThat(rbacAuthorizationService.canAccessCompany(danaProfile, c2CompanyId)).isFalse();

        // Unknown subject with same email cannot use Dana's link
        AuthDtos.OidcAuthorizeResponse unlinkedRedirect = oidcPkceService.buildAuthorizationRedirect("keycloak-ad");
        extractAndBindNonce(unlinkedRedirect.authorizationUrl());
        NEXT_AUTH_CODE_SUBJECT.set(UNLINKED_SUB_799);
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/oidc/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "code", "valid-pkce-code",
                    "state", unlinkedRedirect.state()
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-OIDC-SUBJECT-UNLINKED"));
        NEXT_AUTH_CODE_SUBJECT.set(DANA_SUB_701);

        // Verify Dana's OIDC session accesses C1 cleanly while denying C2
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/companies/" + c1CompanyId).cookie(danaOidcSession))
            .andExpect(MockMvcResultMatchers.status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/companies/" + c2CompanyId).cookie(danaOidcSession))
            .andExpect(MockMvcResultMatchers.status().isForbidden());

        // Verify Increment X permission constants on COMPANY_ADMIN / INSTALLATION_OWNER
        Assertions.assertThat(CompanyRole.COMPANY_ADMIN.defaultPermissions()).contains(
            CompanyPermissions.EXTENSION_AUTHOR,
            CompanyPermissions.EXTENSION_MANAGE,
            CompanyPermissions.IMPORT_PREPARE,
            CompanyPermissions.IMPORT_READ,
            CompanyPermissions.IMPORT_COMMIT,
            CompanyPermissions.IMPORT_TEMPLATE_MANAGE,
            CompanyPermissions.CONNECTOR_MANAGE
        );
    }

    @Test
    @Order(3)
    void providerExpiryExactNanosecondBoundaryLeavesLocalAndServiceKeyUntouched() throws Exception {
        pinClock(T0_BASE);
        DANA_ENABLED_IN_IDP.set(true);
        DANA_GROUPS_IN_IDP.set(List.of(GROUP_703.toString(), GROUP_704.toString()));
        Cookie danaSession = performOidcLoginForSubject(DANA_SUB_701);

        // Stop worker after observation at T0_BASE (checked_at = T0_BASE - 25s; fresh until < T0_BASE + 95s)
        oidcPkceService.setLifecycleWorkerPaused(true);

        // At t0 + 94.999999s -> STILL FRESH!
        pinClock(T0_BASE.plusSeconds(94).plusNanos(999_999_000L));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/companies/" + c1CompanyId).cookie(danaSession))
            .andExpect(MockMvcResultMatchers.status().isOk());

        // At exact t0 + 95s -> DENIED immediately without poll or cleanup!
        pinClock(T0_BASE.plusSeconds(95));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/companies/" + c1CompanyId).cookie(danaSession))
            .andExpect(MockMvcResultMatchers.status().isUnauthorized())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-OIDC-FRESHNESS-EXPIRED"));

        // Independent LOCAL owner session and S1 service key remain 100% operational!
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/companies/" + c1CompanyId).cookie(ownerSessionCookie))
            .andExpect(MockMvcResultMatchers.status().isOk());
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/companies/" + c1CompanyId)
                .header("Authorization", "Bearer " + serviceKeyBearer)
        )
            .andExpect(MockMvcResultMatchers.status().isOk());
    }

    @Test
    @Order(4)
    void providerPagingRemoveSourcePreservesExplicitGrantAndRetainsGrantIdsOnReadd() throws Exception {
        pinClock(T0_BASE);
        // 1. Multi-page group read: 100 distinct unmapped UUIDs on page 1, GROUP_703 on page 2
        List<String> pagedGroups = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            pagedGroups.add(String.format("00000000-0000-4000-8000-%012d", 1000 + i));
        }
        pagedGroups.add(GROUP_703.toString());
        DANA_GROUPS_IN_IDP.set(pagedGroups);

        Cookie danaSession = performOidcLoginForSubject(DANA_SUB_701);
        List<UUID> originalProviderGrantIds = authorityJdbcTemplate.query(
            """
            SELECT grant_id FROM ma_grant
             WHERE principal_id = ? AND source = 'PROVIDER' AND state = 'ACTIVE'
             ORDER BY grant_id ASC
            """,
            (rs, rowNum) -> rs.getObject(1, UUID.class),
            danaPrincipalId
        );
        Assertions.assertThat(originalProviderGrantIds).hasSize(2);

        // Duplicate group ID or > 4096 overflow must fail sweep and clear issuer freshness
        DUPLICATE_GROUP_IN_RESPONSE.set(true);
        Assertions.assertThat(oidcPkceService.runLifecycleSweep(ISSUER_ID)).isFalse();
        DUPLICATE_GROUP_IN_RESPONSE.set(false);

        OVERFLOW_4100_GROUPS.set(true);
        Assertions.assertThat(oidcPkceService.runLifecycleSweep(ISSUER_ID)).isFalse();
        OVERFLOW_4100_GROUPS.set(false);

        // 2. provider-remove-source: remove GROUP_703, leaving only unmapped GROUP_704
        DANA_GROUPS_IN_IDP.set(List.of(GROUP_704.toString()));
        Assertions.assertThat(oidcPkceService.runLifecycleSweep(ISSUER_ID)).isTrue();

        Integer activeProviderGrants = authorityJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ma_grant WHERE principal_id = ? AND source = 'PROVIDER' AND state = 'ACTIVE'",
            Integer.class,
            danaPrincipalId
        );
        Integer revokedProviderGrants = authorityJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ma_grant WHERE principal_id = ? AND source = 'PROVIDER' AND state = 'REVOKED'",
            Integer.class,
            danaPrincipalId
        );
        Integer activeExplicitGrants = authorityJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ma_grant WHERE principal_id = ? AND source = 'EXPLICIT' AND state = 'ACTIVE'",
            Integer.class,
            danaPrincipalId
        );
        Assertions.assertThat(activeProviderGrants).isZero();
        Assertions.assertThat(revokedProviderGrants).isEqualTo(2);
        Assertions.assertThat(activeExplicitGrants).isEqualTo(1);

        // Old Dana session was revoked when mapped grants changed
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/companies/" + c1CompanyId).cookie(danaSession))
            .andExpect(MockMvcResultMatchers.status().isUnauthorized());

        // Explicit report.read remains intact while company.read/master.read are gone
        MoneyCraftPrincipal refreshedDana = rbacAuthorizationService.loadPrincipalProfile(danaPrincipalId);
        Assertions.assertThat(
            rbacAuthorizationService.hasCompanyPermission(refreshedDana, c1CompanyId, CompanyPermissions.REPORT_READ)
        ).isTrue();
        Assertions.assertThat(
            rbacAuthorizationService.hasCompanyPermission(refreshedDana, c1CompanyId, CompanyPermissions.MASTER_READ)
        ).isFalse();

        // Re-adding GROUP_703 reactivates the exact same grant_ids!
        DANA_GROUPS_IN_IDP.set(List.of(GROUP_703.toString(), GROUP_704.toString()));
        Assertions.assertThat(oidcPkceService.runLifecycleSweep(ISSUER_ID)).isTrue();
        List<UUID> reactivatedIds = authorityJdbcTemplate.query(
            """
            SELECT grant_id FROM ma_grant
             WHERE principal_id = ? AND source = 'PROVIDER' AND state = 'ACTIVE'
             ORDER BY grant_id ASC
            """,
            (rs, rowNum) -> rs.getObject(1, UUID.class),
            danaPrincipalId
        );
        Assertions.assertThat(reactivatedIds).isEqualTo(originalProviderGrantIds);
    }

    @Test
    @Order(5)
    void providerDisableOutageClockSkewAndLateResultDiscard() throws Exception {
        pinClock(T0_BASE);
        DANA_ENABLED_IN_IDP.set(true);
        DANA_GROUPS_IN_IDP.set(List.of(GROUP_703.toString()));
        Cookie danaSession = performOidcLoginForSubject(DANA_SUB_701);

        // 1. provider-disable: IdP reports enabled=false
        DANA_ENABLED_IN_IDP.set(false);
        Assertions.assertThat(oidcPkceService.runLifecycleSweep(ISSUER_ID)).isTrue();

        OffsetDateTime linkCheckedAfterDisable = authorityJdbcTemplate.queryForObject(
            "SELECT checked_at FROM ma_oidc_link WHERE id = ?",
            OffsetDateTime.class,
            danaLinkId
        );
        Assertions.assertThat(linkCheckedAfterDisable).isNull();
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/companies/" + c1CompanyId).cookie(danaSession))
            .andExpect(MockMvcResultMatchers.status().isUnauthorized());

        // Local owner and S1 service key remain unaffected
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/companies/" + c1CompanyId).cookie(ownerSessionCookie))
            .andExpect(MockMvcResultMatchers.status().isOk());

        // Positive provider read must NEVER enable a locally DISABLED principal
        authorityJdbcTemplate.update(
            "UPDATE ma_principal SET status = 'DISABLED' WHERE principal_id = ?",
            danaPrincipalId
        );
        DANA_ENABLED_IN_IDP.set(true);
        Assertions.assertThat(oidcPkceService.runLifecycleSweep(ISSUER_ID)).isTrue();
        String localStatus = authorityJdbcTemplate.queryForObject(
            "SELECT status FROM ma_principal WHERE principal_id = ?",
            String.class,
            danaPrincipalId
        );
        Assertions.assertThat(localStatus).isEqualTo("DISABLED");
        authorityJdbcTemplate.update(
            "UPDATE ma_principal SET status = 'ENABLED' WHERE principal_id = ?",
            danaPrincipalId
        );

        // 2. provider-outage: 503, 403, and 302 redirect clear ma_issuer.checked_at immediately
        Cookie freshSession = performOidcLoginForSubject(DANA_SUB_701);
        for (int faultCode : List.of(503, 403, 302)) {
            FORCE_ADMIN_HTTP_STATUS.set(faultCode);
            Assertions.assertThat(oidcPkceService.runLifecycleSweep(ISSUER_ID)).isFalse();
            OffsetDateTime issuerStamp = authorityJdbcTemplate.queryForObject(
                "SELECT checked_at FROM ma_issuer WHERE id = ?",
                OffsetDateTime.class,
                ISSUER_ID
            );
            Assertions.assertThat(issuerStamp).isNull();
            mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/companies/" + c1CompanyId).cookie(freshSession))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized());

            FORCE_ADMIN_HTTP_STATUS.set(0);
            Assertions.assertThat(oidcPkceService.runLifecycleSweep(ISSUER_ID)).isTrue();
            mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/companies/" + c1CompanyId).cookie(freshSession))
                .andExpect(MockMvcResultMatchers.status().isOk());
        }

        // 3. provider-clock: 6s UTC jump vs monotonic elapsed invalidates sweep and closes issuer freshness
        oidcPkceService.setPostReadBeforeReconcileHook(() -> pinClock(T0_BASE.plusSeconds(6)));
        Assertions.assertThat(oidcPkceService.runLifecycleSweep(ISSUER_ID)).isFalse();
        oidcPkceService.setPostReadBeforeReconcileHook(null);
        pinClock(T0_BASE);
        Assertions.assertThat(oidcPkceService.runLifecycleSweep(ISSUER_ID)).isTrue();

        // 4. provider-late-result: link version changed after capture -> stale observation discarded!
        authorityJdbcTemplate.update("UPDATE ma_oidc_link SET checked_at = NULL WHERE id = ?", danaLinkId);
        oidcPkceService.setPostReadBeforeReconcileHook(() ->
            authorityJdbcTemplate.update(
                "UPDATE ma_oidc_link SET version_no = version_no + 1 WHERE id = ?",
                danaLinkId
            )
        );
        Assertions.assertThat(oidcPkceService.runLifecycleSweep(ISSUER_ID)).isFalse();
        oidcPkceService.setPostReadBeforeReconcileHook(null);
        OffsetDateTime discardedLinkStamp = authorityJdbcTemplate.queryForObject(
            "SELECT checked_at FROM ma_oidc_link WHERE id = ?",
            OffsetDateTime.class,
            danaLinkId
        );
        Assertions.assertThat(discardedLinkStamp).isNull();
    }

    @Test
    @Order(6)
    void providerRestartRev2ContinuityVsProfileChangeCrashRecoveryAndNeutralMigrationEndpoint() throws Exception {
        pinClock(T0_BASE);
        DANA_ENABLED_IN_IDP.set(true);
        DANA_GROUPS_IN_IDP.set(List.of(GROUP_703.toString()));
        FORCE_ADMIN_HTTP_STATUS.set(0);

        Cookie danaSession = performOidcLoginForSubject(DANA_SUB_701);
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/companies/" + c1CompanyId).cookie(danaSession))
            .andExpect(MockMvcResultMatchers.status().isOk());

        // Prepare all 5 durable ceremony stages before ordinary restart!
        OidcPkceService.PendingOidcCeremony issuedCeremony = oidcPkceService.createIssuedCeremony("keycloak-ad");
        AuthDtos.OidcAuthorizeResponse redirectedCeremony = oidcPkceService.buildAuthorizationRedirect("keycloak-ad");
        OidcPkceService.PendingOidcCeremony claimedCeremony = oidcPkceService.createIssuedCeremony("keycloak-ad");
        authorityJdbcTemplate.update(
            "UPDATE ma_auth_challenge SET ceremony_stage = 'CLAIMED' WHERE challenge_id = ?",
            claimedCeremony.challengeId()
        );
        UUID verifiedLinkCeremonyId = oidcPkceService.createVerifiedLinkCeremony(
            ISSUER_ID,
            danaPrincipalId,
            DANA_SUB_701,
            Duration.ofMinutes(5)
        );
        OidcPkceService.PendingOidcCeremony consumedCeremony = oidcPkceService.createIssuedCeremony("keycloak-ad");
        authorityJdbcTemplate.update(
            "UPDATE ma_auth_challenge SET ceremony_stage = 'CONSUMED', consumed = true, consumed_at = clock_timestamp() WHERE challenge_id = ?",
            consumedCeremony.challengeId()
        );

        long issuerVersionBeforeRestart = authorityJdbcTemplate.queryForObject(
            "SELECT version_no FROM ma_issuer WHERE id = ?",
            Long.class,
            ISSUER_ID
        );
        long linkVersionBeforeRestart = authorityJdbcTemplate.queryForObject(
            "SELECT version_no FROM ma_oidc_link WHERE id = ?",
            Long.class,
            danaLinkId
        );
        long epochBeforeRestart = authorityJdbcTemplate.queryForObject(
            "SELECT authority_epoch FROM ma_state WHERE singleton = 1",
            Long.class
        );
        int tokenExchangesBeforeRestart = TOKEN_EXCHANGE_CALL_COUNT.get();

        // Advance +10s (and even supply an already-installed identical config whose expiresAt has passed!)
        pinClock(T0_BASE.plusSeconds(10));
        String identicalInstalledButExpiredEnvelope = buildSignedProviderConfigJson(
            "ENABLED",
            Long.toString(issuerVersionBeforeRestart),
            T0_BASE.minusSeconds(7200),
            T0_BASE.minusSeconds(60),
            defaultC1Grants(),
            false
        );
        boolean configChangedOnRestart = oidcPkceService.applySignedProviderConfig(identicalInstalledButExpiredEnvelope);
        Assertions.assertThat(configChangedOnRestart).isFalse();

        // 1. Timestamps are NULL, semantic versions & epoch did NOT churn, SPRING_SESSION was NOT deleted!
        Assertions.assertThat(
            authorityJdbcTemplate.queryForObject("SELECT checked_at FROM ma_issuer WHERE id = ?", OffsetDateTime.class, ISSUER_ID)
        ).isNull();
        Assertions.assertThat(
            authorityJdbcTemplate.queryForObject("SELECT checked_at FROM ma_oidc_link WHERE id = ?", OffsetDateTime.class, danaLinkId)
        ).isNull();
        Assertions.assertThat(
            authorityJdbcTemplate.queryForObject("SELECT version_no FROM ma_issuer WHERE id = ?", Long.class, ISSUER_ID)
        ).isEqualTo(issuerVersionBeforeRestart);
        Assertions.assertThat(
            authorityJdbcTemplate.queryForObject("SELECT version_no FROM ma_oidc_link WHERE id = ?", Long.class, danaLinkId)
        ).isEqualTo(linkVersionBeforeRestart);
        Assertions.assertThat(
            authorityJdbcTemplate.queryForObject("SELECT authority_epoch FROM ma_state WHERE singleton = 1", Long.class)
        ).isEqualTo(epochBeforeRestart);

        Integer survivingOidcSessions = authorityJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES WHERE ATTRIBUTE_NAME LIKE 'MC_OIDC_BINDING:%'",
            Integer.class
        );
        Assertions.assertThat(survivingOidcSessions).isGreaterThanOrEqualTo(1);

        // CLAIMED was terminalised to CONSUMED without retrying token exchange!
        String claimedStageAfterRestart = authorityJdbcTemplate.queryForObject(
            "SELECT ceremony_stage FROM ma_auth_challenge WHERE challenge_id = ?",
            String.class,
            claimedCeremony.challengeId()
        );
        Assertions.assertThat(claimedStageAfterRestart).isEqualTo("CONSUMED");
        Assertions.assertThat(TOKEN_EXCHANGE_CALL_COUNT.get()).isEqualTo(tokenExchangesBeforeRestart);

        // 2. While freshness is unavailable, session & ISSUED & VERIFIED_LINK deny WITHOUT consuming pending requests!
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/companies/" + c1CompanyId).cookie(danaSession))
            .andExpect(MockMvcResultMatchers.status().isUnauthorized())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-OIDC-FRESHNESS-EXPIRED"));

        Assertions.assertThatThrownBy(() -> oidcPkceService.reconstructIssuedCeremony(issuedCeremony.challengeId()))
            .isInstanceOf(MoneyCraftException.class)
            .extracting(ex -> ((MoneyCraftException) ex).code())
            .isEqualTo("MC-OIDC-FRESHNESS-UNAVAILABLE");

        Assertions.assertThatThrownBy(() ->
            oidcPkceService.confirmVerifiedLinkCeremony(verifiedLinkCeremonyId, danaPrincipalId)
        )
            .isInstanceOf(MoneyCraftException.class)
            .extracting(ex -> ((MoneyCraftException) ex).code())
            .isEqualTo("MC-OIDC-FRESHNESS-UNAVAILABLE");

        // 3. Run fresh unchanged sweep -> surviving session, ISSUED, REDIRECTED, and VERIFIED_LINK all resume!
        Assertions.assertThat(oidcPkceService.runLifecycleSweep(ISSUER_ID)).isTrue();

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/companies/" + c1CompanyId).cookie(danaSession))
            .andExpect(MockMvcResultMatchers.status().isOk());

        AuthDtos.OidcAuthorizeResponse reconstructed = oidcPkceService.reconstructIssuedCeremony(
            issuedCeremony.challengeId()
        );
        Assertions.assertThat(reconstructed.authorizationUrl()).isEqualTo(issuedCeremony.response().authorizationUrl());
        Assertions.assertThat(reconstructed.state()).isEqualTo(issuedCeremony.response().state());

        UUID confirmedLink = oidcPkceService.confirmVerifiedLinkCeremony(verifiedLinkCeremonyId, danaPrincipalId);
        Assertions.assertThat(confirmedLink).isEqualTo(danaLinkId);

        extractAndBindNonce(redirectedCeremony.authorizationUrl());
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/oidc/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "code", "valid-pkce-code",
                    "state", redirectedCeremony.state()
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        // CLAIMED and CONSUMED never replay!
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/oidc/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "code", "valid-pkce-code",
                    "state", claimedCeremony.response().state()
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isBadRequest())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-OIDC-STATE-INVALID"));

        // 4. Changed MC-PROVIDER-CONFIG-1 profile with crash BEFORE_FINAL_ISSUER_COMMIT:
        //    Cleanup executes BEFORE final issuer commit; retry is idempotent and commits version 2!
        String v2ChangedConfigJson = buildSignedProviderConfigJson(
            "ENABLED",
            Long.toString(issuerVersionBeforeRestart),
            T0_BASE,
            T0_BASE.plusSeconds(3600),
            List.of(Map.of(
                "companyId", c1CompanyId.toString(),
                "operation", CompanyPermissions.COMPANY_READ,
                "fieldGroup", "NONE"
            )),
            false
        );

        Assertions.assertThatThrownBy(() ->
            oidcPkceService.applySignedProviderConfigWithHook(v2ChangedConfigJson, step -> {
                if ("BEFORE_FINAL_ISSUER_COMMIT".equals(step)) {
                    throw new IllegalStateException("Simulated crash before final ma_issuer commit");
                }
            })
        ).isInstanceOf(IllegalStateException.class);

        // Before final commit: ma_issuer is STILL v1, while Dana's OIDC sessions & PROVIDER grants are ALREADY revoked!
        Assertions.assertThat(
            authorityJdbcTemplate.queryForObject("SELECT version_no FROM ma_issuer WHERE id = ?", Long.class, ISSUER_ID)
        ).isEqualTo(issuerVersionBeforeRestart);
        Assertions.assertThat(
            authorityJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES WHERE ATTRIBUTE_NAME LIKE 'MC_OIDC_BINDING:%'",
                Integer.class
            )
        ).isZero();
        Assertions.assertThat(
            authorityJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ma_grant WHERE principal_id = ? AND source = 'PROVIDER' AND state = 'ACTIVE'",
                Integer.class,
                danaPrincipalId
            )
        ).isZero();

        long linkVersionAfterFirstCleanup = authorityJdbcTemplate.queryForObject(
            "SELECT version_no FROM ma_oidc_link WHERE id = ?",
            Long.class,
            danaLinkId
        );

        // Retry same valid signed v1->v2 input: already-cleaned Dana is an idempotent no-op, final v2 commits!
        Assertions.assertThat(oidcPkceService.applySignedProviderConfig(v2ChangedConfigJson)).isTrue();
        Assertions.assertThat(
            authorityJdbcTemplate.queryForObject("SELECT version_no FROM ma_issuer WHERE id = ?", Long.class, ISSUER_ID)
        ).isEqualTo(issuerVersionBeforeRestart + 1L);
        Assertions.assertThat(
            authorityJdbcTemplate.queryForObject("SELECT version_no FROM ma_oidc_link WHERE id = ?", Long.class, danaLinkId)
        ).isEqualTo(linkVersionAfterFirstCleanup);

        // Verify MC-AUDIT-2 chain remains 100% intact across all provider.configure / reconcile / invalidate events
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/audit/verify").cookie(ownerSessionCookie))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.verified").value(true));

        // 5. Verify neutral /imports/legacy-1800/stage endpoint is mapped and returns clean neutral diagnostics
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies/" + c1CompanyId + "/imports/legacy-1800/stage")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"snapshotDirectoryPath\":\"\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isBadRequest())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-MIG-PATH-REQUIRED"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.detail").value(
                "Specify snapshotDirectoryPath pointing to a legacy .1800 company snapshot or companion XML directory."
            ));
    }

    private Cookie performOidcLoginForSubject(String subject) throws Exception {
        NEXT_AUTH_CODE_SUBJECT.set(subject);
        AuthDtos.OidcAuthorizeResponse redirect = oidcPkceService.buildAuthorizationRedirect("keycloak-ad");
        extractAndBindNonce(redirect.authorizationUrl());

        MvcResult callbackRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/oidc/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "code", "valid-pkce-code",
                    "state", redirect.state()
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();

        Cookie sessionCookie = latestCookie(callbackRes, "MCSESSION");
        Assertions.assertThat(sessionCookie).isNotNull();
        return sessionCookie;
    }

    private void extractAndBindNonce(String authorizationUrl) {
        Map<String, String> query = parseForm(URI.create(authorizationUrl).getRawQuery());
        NEXT_AUTH_CODE_NONCE.set(query.getOrDefault("nonce", ""));
    }

    private void pinClock(Instant instant) {
        PINNED_UTC.set(instant);
        oidcClockSource.overrideForTesting(
            Clock.fixed(instant, ZoneOffset.UTC),
            PINNED_NANO::get
        );
    }

    private UUID createTestCompany(String code, String legalName, String gstinPrefix14) throws Exception {
        String gstin = gstinPrefix14 + ExactMoneyValidator.computeGstinMod36CheckChar(gstinPrefix14);
        String stateCode = gstinPrefix14.substring(0, 2);
        String payload = """
            {
              "code": "%s",
              "legalName": "%s",
              "countryCode": "IN",
              "stateCode": "%s",
              "pinCode": "600001",
              "baseCurrencyCode": "INR",
              "currencySymbol": "₹",
              "currencyScale": 2,
              "financialYearFrom": "2025-04-01",
              "booksBeginningFrom": "2025-04-01",
              "features": {
                "gstEnabled": true,
                "gstRegistrationType": "REGULAR",
                "gstin": "%s",
                "gstr1Periodicity": "MONTHLY"
              }
            }
            """.formatted(code, legalName, stateCode, gstin);

        MvcResult res = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies")
                .cookie(ownerSessionCookie)
                .cookie(ownerXsrfCookie)
                .header("X-XSRF-TOKEN", ownerXsrfCookie.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private List<Map<String, Object>> defaultC1Grants() {
        List<Map<String, Object>> grants = new ArrayList<>();
        grants.add(Map.of(
            "companyId", c1CompanyId.toString(),
            "operation", CompanyPermissions.COMPANY_READ,
            "fieldGroup", "NONE"
        ));
        grants.add(Map.of(
            "companyId", c1CompanyId.toString(),
            "operation", CompanyPermissions.MASTER_READ,
            "fieldGroup", "NONE"
        ));
        return grants;
    }

    private String buildSignedProviderConfigJson(
        String state,
        String expectedIssuerVersion,
        Instant issuedAt,
        Instant expiresAt,
        List<Map<String, Object>> group703Grants,
        boolean corruptSignature
    ) throws Exception {
        String baseUrl = "http://127.0.0.1:" + keycloakPort;
        String realmIssuer = baseUrl + "/realms/moneycraft-test";
        String qualDigest = JcsCanonicalizer.toHex(
            jcsCanonicalizer.sha256(QUALIFICATION_EVIDENCE.getBytes(StandardCharsets.UTF_8))
        );

        Map<String, Object> lifecycle = new LinkedHashMap<>();
        lifecycle.put("profile", "MC-FED-01");
        lifecycle.put("realm", "moneycraft-test");
        lifecycle.put("adminBaseUri", baseUrl);
        lifecycle.put("lifecycleClientId", "mc-lifecycle-reader");
        lifecycle.put("lifecycleSecretRef", "oidc-lifecycle-secret-ref");
        lifecycle.put("federationComponentId", FED_COMPONENT_702.toString());
        lifecycle.put("qualificationRef", "qual-ad-keycloak-01");
        lifecycle.put("qualificationDigest", qualDigest);
        lifecycle.put("upstreamMaxSeconds", "20");
        lifecycle.put("pollSeconds", "60");
        lifecycle.put("maxAgeSeconds", "120");
        lifecycle.put("clockAllowanceSeconds", "5");
        lifecycle.put("cycleDeadlineSeconds", "30");
        lifecycle.put("groupMappings", List.of(Map.of(
            "groupId", GROUP_703.toString(),
            "grants", group703Grants
        )));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("canon", "MC-ISSUER-CONFIG-2");
        config.put("issuer", realmIssuer);
        config.put("clientId", "moneycraft-web");
        config.put("clientSecretRef", "oidc-client-secret-ref");
        config.put("authorizationUri", realmIssuer + "/protocol/openid-connect/auth");
        config.put("tokenUri", realmIssuer + "/protocol/openid-connect/token");
        config.put("jwksUri", realmIssuer + "/protocol/openid-connect/certs");
        config.put("userInfoUri", null);
        config.put("redirectUri", moneyCraftProperties.getBaseUrl() + "/oidc/callback");
        config.put("rpId", "localhost");
        config.put("origins", List.of("http://localhost:8080"));
        config.put("lifecycle", lifecycle);

        String configDigest = JcsCanonicalizer.toHex(
            jcsCanonicalizer.sha256(jcsCanonicalizer.canonicalizeObject(config).getBytes(StandardCharsets.UTF_8))
        );

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "MC-PROVIDER-CONFIG-1");
        envelope.put("installationId", installationId.toString());
        envelope.put("incarnation", incarnation.toString());
        envelope.put("incidentId", null);
        envelope.put("issuerId", ISSUER_ID.toString());
        envelope.put("registrationId", "keycloak-ad");
        envelope.put("state", state);
        envelope.put("expectedIssuerVersion", expectedIssuerVersion);
        envelope.put("config", config);
        envelope.put("configDigest", configDigest);
        envelope.put("issuedAt", issuedAt.toString());
        envelope.put("expiresAt", expiresAt.toString());
        envelope.put("custodianRef", "custodian-ed25519-01");
        envelope.put("evidenceRef", "evidence-signed-cfg-01");

        String sig = IssuerConfig2.signDetachedEd25519(
            custodianEd25519KeyPair.getPrivate(),
            envelope,
            jcsCanonicalizer
        );
        if (corruptSignature) {
            byte[] rawSig = Base64.getUrlDecoder().decode(sig);
            rawSig[0] ^= 0xFF;
            sig = Base64.getUrlEncoder().withoutPadding().encodeToString(rawSig);
        }
        envelope.put("signature", sig);
        return objectMapper.writeValueAsString(envelope);
    }

    private static void handleAdminUserGet(HttpExchange exchange, String subject) throws IOException {
        if (!DANA_SUB_701.equals(subject) && !UNLINKED_SUB_799.equals(subject)) {
            writeJsonResponse(exchange, 404, "{\"error\":\"User not found\"}");
            return;
        }
        boolean enabled = DANA_SUB_701.equals(subject) ? DANA_ENABLED_IN_IDP.get() : true;
        String json = "{"
            + "\"id\":\"" + subject + "\","
            + "\"enabled\":" + enabled + ","
            + "\"federationLink\":\"" + FED_COMPONENT_702 + "\""
            + "}";
        writeJsonResponse(exchange, 200, json);
    }

    private static void handleAdminGroupsGet(
        HttpExchange exchange,
        String subject,
        Map<String, String> query
    ) throws IOException {
        int first = Integer.parseInt(query.getOrDefault("first", "0"));
        int max = Integer.parseInt(query.getOrDefault("max", "100"));

        if (OVERFLOW_4100_GROUPS.get()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < 100; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"id\":\"").append(String.format("00000000-0000-4000-8000-%012d", first + i + 1)).append("\"}");
            }
            sb.append(']');
            writeJsonResponse(exchange, 200, sb.toString());
            return;
        }

        List<String> allGroups = DANA_SUB_701.equals(subject) ? DANA_GROUPS_IN_IDP.get() : List.of();
        if (DUPLICATE_GROUP_IN_RESPONSE.get() && !allGroups.isEmpty()) {
            List<String> dup = new ArrayList<>(allGroups);
            dup.add(allGroups.getFirst());
            allGroups = dup;
        }

        int fromIdx = Math.min(first, allGroups.size());
        int toIdx = Math.min(first + max, allGroups.size());
        List<String> slice = allGroups.subList(fromIdx, toIdx);

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < slice.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"").append(slice.get(i)).append("\"}");
        }
        sb.append(']');
        writeJsonResponse(exchange, 200, sb.toString());
    }

    private static String buildJwksDocument() {
        RSAPublicKey rsaPub = (RSAPublicKey) rsaJwksKeyPair.getPublic();
        ECPublicKey ecPub = (ECPublicKey) ecJwksKeyPair.getPublic();

        String rsaN = b64UrlUnsigned(rsaPub.getModulus().toByteArray());
        String rsaE = b64UrlUnsigned(rsaPub.getPublicExponent().toByteArray());
        String ecX = b64UrlFixedCoord(ecPub.getW().getAffineX().toByteArray(), 32);
        String ecY = b64UrlFixedCoord(ecPub.getW().getAffineY().toByteArray(), 32);

        return "{"
            + "\"keys\":["
            + "{\"kty\":\"RSA\",\"kid\":\"rsa-key-1\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\"" + rsaN + "\",\"e\":\"" + rsaE + "\"},"
            + "{\"kty\":\"EC\",\"kid\":\"ec-key-1\",\"use\":\"sig\",\"alg\":\"ES256\",\"crv\":\"P-256\",\"x\":\"" + ecX + "\",\"y\":\"" + ecY + "\"}"
            + "]}";
    }

    private static String signIdToken(String alg, String subject, String nonce, Instant exp) {
        try {
            String kid = "ES256".equals(alg) ? "ec-key-1" : "rsa-key-1";
            String headerJson = "{\"alg\":\"" + alg + "\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}";
            String iss = OVERRIDE_ISS_CLAIM.get() != null
                ? OVERRIDE_ISS_CLAIM.get()
                : "http://127.0.0.1:" + keycloakPort + "/realms/moneycraft-test";
            String payloadJson = "{"
                + "\"iss\":\"" + iss + "\","
                + "\"aud\":\"moneycraft-web\","
                + "\"sub\":\"" + subject + "\","
                + "\"nonce\":\"" + nonce + "\","
                + "\"exp\":" + exp.getEpochSecond() + ","
                + "\"iat\":" + exp.minusSeconds(300).getEpochSecond()
                + "}";

            String signingInput = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8))
                + "."
                + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

            Signature signer;
            if ("ES256".equals(alg)) {
                signer = Signature.getInstance("SHA256withECDSAinP1363Format");
                signer.initSign(ecJwksKeyPair.getPrivate());
            } else {
                signer = Signature.getInstance("SHA256withRSA");
                signer.initSign(rsaJwksKeyPair.getPrivate());
            }
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            byte[] sigBytes = signer.sign();
            if (TAMPER_ID_TOKEN_SIGNATURE.get()) {
                sigBytes[0] ^= 0x55;
            }
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign test JWT", ex);
        }
    }

    private static String b64UrlUnsigned(byte[] bigIntBytes) {
        if (bigIntBytes.length > 1 && bigIntBytes[0] == 0) {
            byte[] stripped = new byte[bigIntBytes.length - 1];
            System.arraycopy(bigIntBytes, 1, stripped, 0, stripped.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(stripped);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bigIntBytes);
    }

    private static String b64UrlFixedCoord(byte[] bigIntBytes, int targetLen) {
        byte[] out = new byte[targetLen];
        int srcPos = bigIntBytes.length > targetLen ? bigIntBytes.length - targetLen : 0;
        int dstPos = bigIntBytes.length < targetLen ? targetLen - bigIntBytes.length : 0;
        int len = Math.min(bigIntBytes.length - srcPos, targetLen);
        System.arraycopy(bigIntBytes, srcPos, out, dstPos, len);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }

    private static void writeJsonResponse(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
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

    private static Map<String, String> parseForm(String raw) {
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
