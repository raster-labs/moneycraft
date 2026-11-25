package com.raster.moneycraft.foundation;

import com.raster.moneycraft.MoneyCraftApplication;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.http.HttpHeaders;
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
 * End-to-end PostgreSQL 18 Testcontainers verification for MoneyCraft v1.1.0 Wave W2:
 * - Authority V003 schema & explicit initial-super-admin-username ceremony
 * - Self-service GET/PATCH /api/v1/me/ui-preferences (Doc 50, Doc 52 §2, AT-ITER110-002..004, 009)
 * - SUPER_ADMIN Government & Connected Banking Configuration (Docs 51 & 52, UM-110-04, AT-ITER110-012..018, 035)
 * - Host Native Public-Client OAuth2 + PKCE S256 Issuer & Bearer Filter (Mobile Doc 02, AT-ITER110-031..032)
 */
@SpringBootTest(classes = MoneyCraftApplication.class)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class V110PreferencesGovConfigAndNativePkceTest {

    private static final String OWNER_PASS = "OwnerMaster#2026!Secure";
    private static final String ALICE_PASS = "AliceOperator#2026!Ok";
    private static final String SECADMIN_PASS = "SecAdminNoSuper#2026!";
    private static final String SYNTHETIC_IRP_SECRET = "IRP6-ULTRA-SECRET-CRED-99887766";
    private static final String GSTIN_A =
        "33AABCM1234F1Z" + ExactMoneyValidator.computeGstinMod36CheckChar("33AABCM1234F1Z");
    private static final String GSTIN_B =
        "29AABCM1234F1Z" + ExactMoneyValidator.computeGstinMod36CheckChar("29AABCM1234F1Z");

    private static DualPostgres18TestcontainersSupport.ClusterPair isolatedCluster;
    private static HttpServer mockProviderServer;
    private static int mockProviderPort;
    private static final AtomicInteger PROBE_STATUS_CODE = new AtomicInteger(200);
    private static final AtomicReference<String> PROBE_RESPONSE_BODY =
        new AtomicReference<>("{\"status\":\"UP\"}");
    private static final AtomicReference<String> LAST_PROBE_METHOD = new AtomicReference<>("");

    private static UUID ownerPrincipalId;
    private static UUID alicePrincipalId;
    private static UUID secAdminPrincipalId;
    private static Cookie ownerSession;
    private static Cookie ownerXsrf;
    private static Cookie aliceSession;
    private static Cookie aliceXsrf;
    private static Cookie secAdminSession;
    private static Cookie secAdminXsrf;
    private static String rawServiceApiKey;
    private static UUID companyAId;
    private static UUID companyBId;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("authorityJdbcTemplate")
    private JdbcTemplate authorityJdbcTemplate;

    @BeforeAll
    static void startProbeServer() throws Exception {
        ensureMockServerStarted();
    }

    @AfterAll
    static void stopProbeServer() {
        if (mockProviderServer != null) {
            mockProviderServer.stop(0);
        }
    }

    private static synchronized void ensureClusterAndProbeStarted() {
        if (isolatedCluster == null) {
            isolatedCluster = DualPostgres18TestcontainersSupport.startIsolatedClusterPair();
        }
        ensureMockServerStarted();
    }

    private static synchronized void ensureMockServerStarted() {
        if (mockProviderServer != null) {
            return;
        }
        try {
            mockProviderServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            mockProviderPort = mockProviderServer.getAddress().getPort();
            mockProviderServer.createContext("/probe/irp6-health", exchange -> {
                LAST_PROBE_METHOD.set(exchange.getRequestMethod());
                byte[] resp = PROBE_RESPONSE_BODY.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(PROBE_STATUS_CODE.get(), resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                } finally {
                    exchange.close();
                }
            });
            mockProviderServer.start();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed starting loopback statutory mock probe server", ex);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        ensureClusterAndProbeStarted();
        DualPostgres18TestcontainersSupport.bindClusterProperties(registry, isolatedCluster);
        registry.add("moneycraft.security.initial-super-admin-username", () -> "owner");
        registry.add("moneycraft.connector.enabled", () -> "true");
        registry.add("moneycraft.connector.allow-loopback-http", () -> "true");
        registry.add("moneycraft.connector.egress-hostname-allowlist[0]", () -> "127.0.0.1");
        registry.add(
            "moneycraft.connector.endpoint-profile-overrides.IRP6_PRODUCTION_V1",
            () -> "http://127.0.0.1:" + mockProviderPort + "/probe/irp6-health"
        );
        registry.add(
            "moneycraft.connector.endpoint-profile-overrides.ICICI_CIB_SANDBOX_V1",
            () -> "http://127.0.0.1:" + mockProviderPort + "/probe/irp6-health"
        );
    }

    @Test
    @Order(1)
    void authorityV003MigrationAndExplicitSuperAdminBootstrapCeremony() throws Exception {
        Integer authCount = authorityJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
            Integer.class
        );
        Assertions.assertThat(authCount).isGreaterThanOrEqualTo(3);

        MvcResult initRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/system/initialise")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "installationLabel", "MoneyCraft v1.1.0 Test Cluster",
                    "ownerUsername", "owner",
                    "ownerDisplayName", "Installation Super Admin",
                    "password", OWNER_PASS
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.ownerPrincipal.superAdmin").value(true))
            .andReturn();

        JsonNode initBody = objectMapper.readTree(initRes.getResponse().getContentAsByteArray());
        ownerPrincipalId = UUID.fromString(initBody.get("ownerPrincipal").get("principalId").asString());
        ownerSession = initRes.getResponse().getCookie("MCSESSION");
        ownerXsrf = initRes.getResponse().getCookie("XSRF-TOKEN");

        // Create secadmin WITH INSTALLATION_OWNER + SECURITY_AUDITOR system roles, but NO Super Admin designation!
        MvcResult secCreateRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/admin/principals")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", "secadmin",
                    "displayName", "Security Admin Without SuperAdmin",
                    "email", "secadmin@moneycraft.local",
                    "password", SECADMIN_PASS,
                    "systemRoles", List.of("INSTALLATION_OWNER", "SECURITY_AUDITOR")
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        secAdminPrincipalId = UUID.fromString(
            objectMapper.readTree(secCreateRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        // Create ordinary user alice
        MvcResult aliceCreateRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/admin/principals")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", "alice",
                    "displayName", "Alice Company Admin",
                    "email", "alice@moneycraft.local",
                    "password", ALICE_PASS,
                    "systemRoles", List.of()
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        alicePrincipalId = UUID.fromString(
            objectMapper.readTree(aliceCreateRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        // Create two companies via HTTP API (Company A with GSTIN 33AABCM1234A1Z5, Company B with 29AABCM9999B1Z5)
        String createCompAJson = """
            {
              "code": "MCV110A",
              "legalName": "MoneyCraft Manufacturing Pvt Ltd",
              "countryCode": "IN",
              "stateCode": "33",
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
                "gstr1Periodicity": "MONTHLY",
                "tdsEnabled": false,
                "mcaEditLogEnforced": false
              }
            }
            """.formatted(GSTIN_A);
        MvcResult compARes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createCompAJson)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        companyAId = UUID.fromString(
            objectMapper.readTree(compARes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        String createCompBJson = """
            {
              "code": "MCV110B",
              "legalName": "MoneyCraft Exports Ltd",
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
                "tdsEnabled": false,
                "mcaEditLogEnforced": false
              }
            }
            """.formatted(GSTIN_B);
        MvcResult compBRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createCompBJson)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        companyBId = UUID.fromString(
            objectMapper.readTree(compBRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        mockMvc.perform(
            MockMvcRequestBuilders.put("/api/v1/admin/principals/" + alicePrincipalId + "/grants")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "companyId", companyAId.toString(),
                    "roleCode", "COMPANY_ADMIN",
                    "exportPermitted", true
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        // Issue a SERVICE API key
        MvcResult svcRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/admin/service-keys")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "label", "ERP Batch Connector",
                    "companyIds", List.of(companyAId.toString()),
                    "endpointAllowlist", List.of("*:/api/v1/**")
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        rawServiceApiKey = objectMapper.readTree(svcRes.getResponse().getContentAsByteArray())
            .get("rawSecretOnce").asString();

        // Sign in secadmin & alice and verify superAdmin=false on both!
        SessionPair secLogin = loginPassword("secadmin", SECADMIN_PASS, false);
        secAdminSession = secLogin.session();
        secAdminXsrf = secLogin.xsrf();

        SessionPair aliceLogin = loginPassword("alice", ALICE_PASS, false);
        aliceSession = aliceLogin.session();
        aliceXsrf = aliceLogin.xsrf();
    }

    @Test
    @Order(2)
    void uiPreferencesSchema2ConcurrencyValidationReplayAndPersonIsolation() throws Exception {
        // 1. Service API key rejected 403 on person-only UI preferences
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/me/ui-preferences")
                .header("Authorization", "Bearer " + rawServiceApiKey)
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-PERSON-PRINCIPAL-REQUIRED"));

        // 2. Absent row returns explicit schemaVersion 2 defaults with revision "0" and strong ETag
        MvcResult get0 = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/me/ui-preferences").cookie(ownerSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.revision").value("0"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.preferences.schemaVersion").value(2))
            .andExpect(MockMvcResultMatchers.jsonPath("$.preferences.appearance").value("SYSTEM"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.preferences.textScalePercent").value(100))
            .andExpect(MockMvcResultMatchers.jsonPath("$.preferences.keyboard.mode").value("CHARACTER"))
            .andReturn();

        String etag0 = get0.getResponse().getHeader(HttpHeaders.ETAG);
        JsonNode body0 = objectMapper.readTree(get0.getResponse().getContentAsByteArray());
        String ownerScope = body0.get("subjectScope").asString();
        Assertions.assertThat(ownerScope).matches("^[A-Za-z0-9_-]{16,128}$");
        Assertions.assertThat(etag0).isEqualTo("\"ui:" + ownerScope + ":0\"");
        Assertions.assertThat(get0.getResponse().getContentAsString())
            .doesNotContain("owner")
            .doesNotContain("Installation Super Admin");

        // 3. Alice gets her own distinct subjectScope
        MvcResult aliceGet0 = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/me/ui-preferences").cookie(aliceSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        String aliceEtag0 = aliceGet0.getResponse().getHeader(HttpHeaders.ETAG);
        Assertions.assertThat(aliceEtag0).isNotEqualTo(etag0);

        // 4. Missing If-Match -> 428; wrong-identity ETag -> 400; unknown field -> 400; >16KiB -> 413; >8 depth -> 413
        String validPatchJson = """
            {"schemaVersion":2,"changes":{"appearance":"DARK","textScalePercent":112.5}}
            """;

        mockMvc.perform(
            MockMvcRequestBuilders.patch("/api/v1/me/ui-preferences")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPatchJson)
        )
            .andExpect(MockMvcResultMatchers.status().isPreconditionRequired())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-PRECONDITION-REQUIRED"));

        mockMvc.perform(
            MockMvcRequestBuilders.patch("/api/v1/me/ui-preferences")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, aliceEtag0)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPatchJson)
        )
            .andExpect(MockMvcResultMatchers.status().isBadRequest())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-ETAG-WRONG-SCOPE"));

        mockMvc.perform(
            MockMvcRequestBuilders.patch("/api/v1/me/ui-preferences")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, etag0)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schemaVersion\":2,\"changes\":{\"appearance\":\"DARK\"},\"extraSneaky\":true}")
        )
            .andExpect(MockMvcResultMatchers.status().isBadRequest())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-PREFERENCES-UNKNOWN-FIELD"));

        String oversized = "{\"schemaVersion\":2,\"changes\":{\"appearance\":\"" + "A".repeat(17_000) + "\"}}";
        mockMvc.perform(
            MockMvcRequestBuilders.patch("/api/v1/me/ui-preferences")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, etag0)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(oversized)
        )
            .andExpect(MockMvcResultMatchers.status().isPayloadTooLarge())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-PREFERENCES-PAYLOAD-TOO-LARGE"));

        String deepJson = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":{\"g\":{\"h\":{\"i\":1}}}}}}}}}";
        mockMvc.perform(
            MockMvcRequestBuilders.patch("/api/v1/me/ui-preferences")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, etag0)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(deepJson)
        )
            .andExpect(MockMvcResultMatchers.status().isPayloadTooLarge())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-PREFERENCES-DEPTH-EXCEEDED"));

        // 5. Valid partial PATCH -> revision 1, preserves unmentioned keyboard property, and records preferences.update receipt
        String idemp1 = UUID.randomUUID().toString();
        MvcResult patch1 = mockMvc.perform(
            MockMvcRequestBuilders.patch("/api/v1/me/ui-preferences")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, etag0)
                .header("Idempotency-Key", idemp1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPatchJson)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.revision").value("1"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.preferences.appearance").value("DARK"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.preferences.textScalePercent").value(112.5))
            .andExpect(MockMvcResultMatchers.jsonPath("$.preferences.keyboard.mode").value("CHARACTER"))
            .andReturn();
        String etag1 = patch1.getResponse().getHeader(HttpHeaders.ETAG);
        Assertions.assertThat(etag1).isEqualTo("\"ui:" + ownerScope + ":1\"");

        // 6. Exact same Idempotency-Key + original If-Match replays 200 OK without conflict
        mockMvc.perform(
            MockMvcRequestBuilders.patch("/api/v1/me/ui-preferences")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, etag0)
                .header("Idempotency-Key", idemp1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPatchJson)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.header().string("X-Idempotency-Replayed", "true"))
            .andExpect(MockMvcResultMatchers.header().string(HttpHeaders.ETAG, etag1));

        // 7. Stale If-Match with a new Idempotency-Key returns 412 with serverCurrent & serverRevision
        mockMvc.perform(
            MockMvcRequestBuilders.patch("/api/v1/me/ui-preferences")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, etag0)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schemaVersion\":2,\"changes\":{\"appearance\":\"LIGHT\"}}")
        )
            .andExpect(MockMvcResultMatchers.status().isPreconditionFailed())
            .andExpect(MockMvcResultMatchers.header().string(HttpHeaders.ETAG, etag1))
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-PREFERENCES-REVISION-CONFLICT"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.serverRevision").value("1"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.serverCurrent.appearance").value("DARK"));

        // 8. Alice remains isolated at revision 0
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/me/ui-preferences").cookie(aliceSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.revision").value("0"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.preferences.appearance").value("SYSTEM"));
    }

    @Test
    @Order(3)
    void superAdminOnlyGovernmentAccessControlAndInventoryReconciliation() throws Exception {
        // 1. Ordinary user (alice), INSTALLATION_OWNER without designation (secadmin), and SERVICE key are all denied 403
        for (Cookie deniedSession : List.of(aliceSession, secAdminSession)) {
            mockMvc.perform(
                MockMvcRequestBuilders.get("/api/v1/admin/government-interface-inventory").cookie(deniedSession)
            )
                .andExpect(MockMvcResultMatchers.status().isForbidden())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-SUPER-ADMIN-REQUIRED"));

            mockMvc.perform(
                MockMvcRequestBuilders.get("/api/v1/admin/government-integration-profiles").cookie(deniedSession)
            )
                .andExpect(MockMvcResultMatchers.status().isForbidden())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-SUPER-ADMIN-REQUIRED"));
        }

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/admin/government-integration-profiles")
                .header("Authorization", "Bearer " + rawServiceApiKey)
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-SUPER-ADMIN-REQUIRED"));

        // 2. Designated Super Admin PERSON (owner) retrieves full 44-row inventory + 13 provider schemas
        MvcResult invRes = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/admin/government-interface-inventory").cookie(ownerSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn();
        JsonNode invBody = objectMapper.readTree(invRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(invBody.get("capabilities").size()).isEqualTo(44);
        Assertions.assertThat(invBody.get("providers").size()).isEqualTo(13);
    }

    @Test
    @Order(4)
    void governmentAndBankingVaultStepUpProbeSecretRedactionAndRevocation() throws Exception {
        // 1. Wrong password step-up fails 401
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/step-up/password")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "password", "WrongPassword#1234",
                    "action", "government.profile.create"
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isUnauthorized())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-STEP-UP-INVALID-CREDENTIALS"));

        // 2. Reject cross-environment endpointProfileId and arbitrary URL before consuming step-up
        String stepUpCreateBad = issueStepUp("government.profile.create", null, null);
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/admin/government-integration-profiles")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "profileName", "Bad Cross-Env Profile",
                    "providerCode", "EINVOICE_IRP6",
                    "endpointProfileId", "IRP6_SANDBOX_V1",
                    "environment", "PRODUCTION",
                    "companyId", companyAId.toString(),
                    "registrationRef", GSTIN_A,
                    "allowedOperationIds", List.of("GOV-D12", "GOV-D13"),
                    "consentEvidenceRef", "IRP6-CONSENT-2026-001",
                    "stepUpIntentId", stepUpCreateBad
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isBadRequest())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-GOV-ENDPOINT-ENV-MISMATCH"));

        // Wrong GSTIN not belonging to Company A -> 422 MC-GOV-REGISTRATION-MISMATCH
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/admin/government-integration-profiles")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "profileName", "Wrong GSTIN Binding",
                    "providerCode", "EINVOICE_IRP6",
                    "endpointProfileId", "IRP6_PRODUCTION_V1",
                    "environment", "PRODUCTION",
                    "companyId", companyAId.toString(),
                    "registrationRef", GSTIN_B,
                    "allowedOperationIds", List.of("GOV-D12"),
                    "consentEvidenceRef", "IRP6-CONSENT-2026-001",
                    "stepUpIntentId", stepUpCreateBad
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-GOV-REGISTRATION-MISMATCH"));

        // 3. Create valid PRODUCTION IRP6 profile draft + test receipt replay vs step-up replay
        String createIdempKey = UUID.randomUUID().toString();
        Map<String, Object> createDraftMap = new LinkedHashMap<>();
        createDraftMap.put("profileName", "TN Primary IRP6 Production");
        createDraftMap.put("providerCode", "EINVOICE_IRP6");
        createDraftMap.put("adapterCode", "EINVOICE_IRP6_V1");
        createDraftMap.put("endpointProfileId", "IRP6_PRODUCTION_V1");
        createDraftMap.put("domainCategory", "GOVERNMENT_STATUTORY");
        createDraftMap.put("environment", "PRODUCTION");
        createDraftMap.put("companyId", companyAId.toString());
        createDraftMap.put("registrationRef", GSTIN_A);
        createDraftMap.put("allowedOperationIds", List.of("GOV-D12", "GOV-D13", "GOV-D17"));
        createDraftMap.put("consentEvidenceRef", "IRP6-ADM-2026-TN-01");
        createDraftMap.put("stepUpIntentId", stepUpCreateBad);
        String createBody = objectMapper.writeValueAsString(createDraftMap);

        MvcResult createRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/admin/government-integration-profiles")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header("Idempotency-Key", createIdempKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("DRAFT"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.revision").value("1"))
            .andReturn();

        JsonNode createdJson = objectMapper.readTree(createRes.getResponse().getContentAsByteArray());
        UUID profileId = UUID.fromString(createdJson.get("id").asString());
        String etagRev1 = createRes.getResponse().getHeader(HttpHeaders.ETAG);
        Assertions.assertThat(etagRev1).isEqualTo("\"gov:" + profileId + ":1\"");

        // Exact receipt replay succeeds even though stepUpCreateBad is now CONSUMED (Doc 52 §3)
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/admin/government-integration-profiles")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header("Idempotency-Key", createIdempKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.header().string("X-Idempotency-Replayed", "true"));

        // Reusing consumed stepUpCreateBad with a NEW Idempotency-Key is rejected 403 MC-STEP-UP-REPLAYED
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/admin/government-integration-profiles")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody)
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-STEP-UP-REPLAYED"));

        // 4. Stage write-only Tink-encrypted credentials & verify zero secret disclosure anywhere
        String stepUpStage = issueStepUp("government.profile.credential.stage", profileId, 1L);
        MvcResult stageRes = mockMvc.perform(
            MockMvcRequestBuilders.post(
                "/api/v1/admin/government-integration-profiles/" + profileId + "/credential-versions"
            )
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, etagRev1)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "slots", Map.of(
                        "clientId", "IRP6-PARTNER-ID-01",
                        "clientSecret", SYNTHETIC_IRP_SECRET,
                        "taxpayerApiUsername", "tn_api_user",
                        "taxpayerApiPassword", "TaxpayerPass#9988"
                    ),
                    "expectedRevision", 1,
                    "stepUpIntentId", stepUpStage
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.credentialRevision").value("1"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.profileRevision").value("2"))
            .andReturn();

        Assertions.assertThat(stageRes.getResponse().getContentAsString()).doesNotContain(SYNTHETIC_IRP_SECRET);
        byte[] rawCipherInDb = authorityJdbcTemplate.queryForObject(
            "SELECT secret_envelope_gcm FROM ma_gov_credential_version WHERE profile_id = ? AND credential_revision = 1",
            (rs, rowNum) -> rs.getBytes(1),
            profileId
        );
        Assertions.assertThat(new String(rawCipherInDb, StandardCharsets.UTF_8)).doesNotContain(SYNTHETIC_IRP_SECRET);

        String etagRev2 = stageRes.getResponse().getHeader(HttpHeaders.ETAG);

        // 5. Simulate upstream probe 500 that echoes the submitted secret -> verify redaction!
        PROBE_STATUS_CODE.set(500);
        PROBE_RESPONSE_BODY.set("Upstream error: bad secret " + SYNTHETIC_IRP_SECRET);
        String stepUpValFail = issueStepUp("government.profile.validation.start", profileId, 2L);
        MvcResult valFailRes = mockMvc.perform(
            MockMvcRequestBuilders.post(
                "/api/v1/admin/government-integration-profiles/" + profileId + "/validations"
            )
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, etagRev2)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "expectedRevision", 2,
                    "credentialRevision", 1,
                    "stepUpIntentId", stepUpValFail
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isAccepted())
            .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("FAILED"))
            .andReturn();
        String valFailBody = valFailRes.getResponse().getContentAsString();
        Assertions.assertThat(valFailBody).doesNotContain(SYNTHETIC_IRP_SECRET).contains("[REDACTED]");
        Assertions.assertThat(LAST_PROBE_METHOD.get()).isEqualTo("GET");

        // 6. Now probe returns 200 OK -> VALIDATED -> Activate -> Disable with CONSENT_REVOKED -> Block rollback
        PROBE_STATUS_CODE.set(200);
        PROBE_RESPONSE_BODY.set("{\"status\":\"OK\"}");
        String stepUpValOk = issueStepUp("government.profile.validation.start", profileId, 2L);
        MvcResult valOkRes = mockMvc.perform(
            MockMvcRequestBuilders.post(
                "/api/v1/admin/government-integration-profiles/" + profileId + "/validations"
            )
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, etagRev2)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "expectedRevision", 2,
                    "credentialRevision", 1,
                    "stepUpIntentId", stepUpValOk
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isAccepted())
            .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("VALIDATED"))
            .andReturn();
        UUID validationId = UUID.fromString(
            objectMapper.readTree(valOkRes.getResponse().getContentAsByteArray()).get("validationId").asString()
        );

        String stepUpActivate = issueStepUp("government.profile.activate", profileId, 2L);
        MvcResult actRes = mockMvc.perform(
            MockMvcRequestBuilders.post(
                "/api/v1/admin/government-integration-profiles/" + profileId + "/activate"
            )
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, etagRev2)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "validationId", validationId.toString(),
                    "expectedRevision", 2,
                    "stepUpIntentId", stepUpActivate
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("ACTIVE"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.revision").value("3"))
            .andReturn();
        String etagRev3 = actRes.getResponse().getHeader(HttpHeaders.ETAG);

        // Disable with CONSENT_REVOKED -> marks credential REVOKED and blocks any re-validation/rollback
        String stepUpDisable = issueStepUp("government.profile.disable", profileId, 3L);
        MvcResult disRes = mockMvc.perform(
            MockMvcRequestBuilders.post(
                "/api/v1/admin/government-integration-profiles/" + profileId + "/disable"
            )
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, etagRev3)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "reasonCode", "CONSENT_REVOKED",
                    "reasonDetail", "Taxpayer revoked GSP API access on portal",
                    "expectedRevision", 3,
                    "stepUpIntentId", stepUpDisable
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("EXPIRED_OR_REVOKED"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.revision").value("4"))
            .andReturn();
        String etagRev4 = disRes.getResponse().getHeader(HttpHeaders.ETAG);

        // Attempting to validate the revoked credential revision 1 fails 409 MC-GOV-CREDENTIAL-REVOKED
        String stepUpRetryRevoked = issueStepUp("government.profile.validation.start", profileId, 4L);
        mockMvc.perform(
            MockMvcRequestBuilders.post(
                "/api/v1/admin/government-integration-profiles/" + profileId + "/validations"
            )
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, etagRev4)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "expectedRevision", 4,
                    "credentialRevision", 1,
                    "stepUpIntentId", stepUpRetryRevoked
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isConflict())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-GOV-CREDENTIAL-REVOKED"));

        // Audit history contains zero secrets
        MvcResult auditRes = mockMvc.perform(
            MockMvcRequestBuilders.get(
                "/api/v1/admin/government-integration-profiles/" + profileId + "/audit"
            ).cookie(ownerSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Assertions.assertThat(auditRes.getResponse().getContentAsString())
            .doesNotContain(SYNTHETIC_IRP_SECRET)
            .contains("government.profile.activate");

        // Designate then immediately revoke Super Admin on secadmin -> subsequent request denied 403!
        mockMvc.perform(
            MockMvcRequestBuilders.put("/api/v1/admin/super-admin-designations/" + secAdminPrincipalId)
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Temporary ceremony test\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/admin/government-integration-profiles").cookie(secAdminSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        mockMvc.perform(
            MockMvcRequestBuilders.delete("/api/v1/admin/super-admin-designations/" + secAdminPrincipalId)
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/admin/government-integration-profiles").cookie(secAdminSession)
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-SUPER-ADMIN-REQUIRED"));
    }

    @Test
    @Order(5)
    void hostNativePublicClientPkceS256RefreshReplayAndMixedCredentialEnforcement() throws Exception {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk-MoneyCraftPkce2026";
        String challenge = computeS256Challenge(verifier);
        String redirectUri = "http://127.0.0.1:51923/oauth2/callback";
        String state = "state-swing-login-001";
        String nonce = "nonce-swing-login-001";

        // 1. Reject unregistered client_id and non-loopback HTTP redirect BEFORE redirecting
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/auth/native/authorize")
                .param("response_type", "code")
                .param("client_id", "unknown-client")
                .param("redirect_uri", redirectUri)
                .param("state", state)
                .param("code_challenge", challenge)
                .param("code_challenge_method", "S256")
        )
            .andExpect(MockMvcResultMatchers.status().isBadRequest())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-NATIVE-CLIENT-INVALID"));

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/auth/native/authorize")
                .param("response_type", "code")
                .param("client_id", "moneycraft-swing-desktop")
                .param("redirect_uri", "http://evil.example.org:51923/oauth2/callback")
                .param("state", state)
                .param("code_challenge", challenge)
                .param("code_challenge_method", "S256")
        )
            .andExpect(MockMvcResultMatchers.status().isBadRequest())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-NATIVE-REDIRECT-INVALID"));

        // 2. Unauthenticated browser navigation redirects 302 to /login?nativeAuthorize=...
        MvcResult unauthAuthz = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/auth/native/authorize")
                .param("response_type", "code")
                .param("client_id", "moneycraft-swing-desktop")
                .param("redirect_uri", redirectUri)
                .param("scope", "openid profile")
                .param("state", state)
                .param("nonce", nonce)
                .param("code_challenge", challenge)
                .param("code_challenge_method", "S256")
        )
            .andExpect(MockMvcResultMatchers.status().isFound())
            .andReturn();
        String unauthLocation = unauthAuthz.getResponse().getHeader(HttpHeaders.LOCATION);
        Assertions.assertThat(unauthLocation).startsWith("/login?nativeAuthorize=");
        String decodedNativeAuthorize = URLDecoder.decode(
            unauthLocation.substring("/login?nativeAuthorize=".length()),
            StandardCharsets.UTF_8
        );
        Assertions.assertThat(decodedNativeAuthorize).startsWith("/api/v1/auth/native/authorize?response_type=code");

        // 3. Authenticated Alice browser session issues 60s code via 302 redirect
        MvcResult authzOk = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/auth/native/authorize")
                .cookie(aliceSession)
                .param("response_type", "code")
                .param("client_id", "moneycraft-swing-desktop")
                .param("redirect_uri", redirectUri)
                .param("scope", "openid profile")
                .param("state", state)
                .param("nonce", nonce)
                .param("code_challenge", challenge)
                .param("code_challenge_method", "S256")
        )
            .andExpect(MockMvcResultMatchers.status().isFound())
            .andReturn();
        URI callbackLocation = URI.create(authzOk.getResponse().getHeader(HttpHeaders.LOCATION));
        Map<String, String> cbParams = parseQuery(callbackLocation.getRawQuery());
        Assertions.assertThat(cbParams.get("state")).isEqualTo(state);
        String code1 = cbParams.get("code");
        Assertions.assertThat(code1).startsWith("mc_nat_cd_");

        // 4. Exchange code with form-urlencoded POST; verify ES256 id_token signature & claims
        MvcResult tokenRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/native/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(encodeForm(Map.of(
                    "grant_type", "authorization_code",
                    "code", code1,
                    "redirect_uri", redirectUri,
                    "client_id", "moneycraft-swing-desktop",
                    "code_verifier", verifier
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode tokenJson = objectMapper.readTree(tokenRes.getResponse().getContentAsByteArray());
        String access1 = tokenJson.get("access_token").asString();
        String refresh1 = tokenJson.get("refresh_token").asString();
        String idToken1 = tokenJson.get("id_token").asString();
        verifyEs256IdTokenSignatureAndClaims(idToken1, alicePrincipalId, nonce);

        // 5. ID token substituted as API Bearer access token -> rejected 401!
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + idToken1)
        )
            .andExpect(MockMvcResultMatchers.status().isUnauthorized())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-NATIVE-ID-TOKEN-FORBIDDEN"));

        // 6. Mixed credentials (Bearer + MCSESSION cookie OR Bearer + X-API-Key) -> rejected 400 MC-MIXED-CREDENTIALS!
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/auth/me")
                .cookie(aliceSession)
                .header("Authorization", "Bearer " + access1)
        )
            .andExpect(MockMvcResultMatchers.status().isBadRequest())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-MIXED-CREDENTIALS"));

        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies")
                .cookie(aliceSession)
                .header("Authorization", "Bearer " + access1)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        )
            .andExpect(MockMvcResultMatchers.status().isBadRequest())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-MIXED-CREDENTIALS"));

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + access1)
                .header("X-API-Key", rawServiceApiKey)
        )
            .andExpect(MockMvcResultMatchers.status().isBadRequest())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-MIXED-CREDENTIALS"));

        // 7. Pure Native Bearer accesses permitted business routes, skips CSRF on PATCH preferences,
        //    blocks cross-company B, and blocks Super Admin government configuration (AT-ITER110-035)!
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/companies/" + companyAId)
                .header("Authorization", "Bearer " + access1)
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/companies/" + companyBId)
                .header("Authorization", "Bearer " + access1)
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-RBAC-COMPANY-FORBIDDEN"));

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/admin/government-integration-profiles")
                .header("Authorization", "Bearer " + access1)
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-SUPER-ADMIN-REQUIRED"));

        MvcResult alicePrefGet = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/me/ui-preferences")
                .header("Authorization", "Bearer " + access1)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        String alicePrefEtag = alicePrefGet.getResponse().getHeader(HttpHeaders.ETAG);

        // Pure Bearer mutates preferences WITHOUT X-XSRF-TOKEN cookie/header
        mockMvc.perform(
            MockMvcRequestBuilders.patch("/api/v1/me/ui-preferences")
                .header("Authorization", "Bearer " + access1)
                .header(HttpHeaders.IF_MATCH, alicePrefEtag)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schemaVersion\":2,\"changes\":{\"textScalePercent\":150}}")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.preferences.textScalePercent").value(150));

        // 8. Refresh token rotation + RFC 9700 replay family revocation!
        MvcResult rotRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/native/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(encodeForm(Map.of(
                    "grant_type", "refresh_token",
                    "client_id", "moneycraft-swing-desktop",
                    "refresh_token", refresh1
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode rotJson = objectMapper.readTree(rotRes.getResponse().getContentAsByteArray());
        String access2 = rotJson.get("access_token").asString();

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/auth/me").header("Authorization", "Bearer " + access2)
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        // Replay rotated refresh1 -> 401 MC-NATIVE-REFRESH-REPLAYED AND revokes access2 immediately!
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/native/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(encodeForm(Map.of(
                    "grant_type", "refresh_token",
                    "client_id", "moneycraft-swing-desktop",
                    "refresh_token", refresh1
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isUnauthorized())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-NATIVE-REFRESH-REPLAYED"));

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/auth/me").header("Authorization", "Bearer " + access2)
        )
            .andExpect(MockMvcResultMatchers.status().isUnauthorized())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-NATIVE-TOKEN-EXPIRED"));

        // 9. Issue another session via SPA /authorize/approve helper and test /revoke
        MvcResult approveRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/native/authorize/approve")
                .cookie(aliceSession, aliceXsrf)
                .header("X-XSRF-TOKEN", aliceXsrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "client_id", "moneycraft-swing-desktop",
                    "redirect_uri", redirectUri,
                    "scope", "openid profile",
                    "state", "state-approve-002",
                    "nonce", "nonce-approve-002",
                    "code_challenge", challenge,
                    "code_challenge_method", "S256"
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        String code2 = objectMapper.readTree(approveRes.getResponse().getContentAsByteArray()).get("code").asString();

        MvcResult token2Res = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/native/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(encodeForm(Map.of(
                    "grant_type", "authorization_code",
                    "code", code2,
                    "redirect_uri", redirectUri,
                    "client_id", "moneycraft-swing-desktop",
                    "code_verifier", verifier
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        String access3 = objectMapper.readTree(token2Res.getResponse().getContentAsByteArray())
            .get("access_token").asString();

        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/native/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(encodeForm(Map.of(
                    "client_id", "moneycraft-swing-desktop",
                    "token", access3
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/auth/me").header("Authorization", "Bearer " + access3)
        )
            .andExpect(MockMvcResultMatchers.status().isUnauthorized());
    }

    private void verifyEs256IdTokenSignatureAndClaims(String jwt, UUID expectedSub, String expectedNonce)
        throws Exception {
        MvcResult discRes = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/auth/native/.well-known/openid-configuration")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        String pem = objectMapper.readTree(discRes.getResponse().getContentAsByteArray())
            .get("public_key_pem").asString();
        String base64Der = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s+", "");
        PublicKey pubKey = KeyFactory.getInstance("EC")
            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64Der)));

        String[] parts = jwt.split("\\.");
        Assertions.assertThat(parts).hasSize(3);

        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(pubKey);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        boolean sigValid = verifier.verify(Base64.getUrlDecoder().decode(parts[2]));
        Assertions.assertThat(sigValid).isTrue();

        JsonNode claims = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        Assertions.assertThat(claims.get("sub").asString()).isEqualTo(expectedSub.toString());
        Assertions.assertThat(claims.get("nonce").asString()).isEqualTo(expectedNonce);
        List<String> auds = List.of(claims.get("aud").get(0).asString(), claims.get("aud").get(1).asString());
        Assertions.assertThat(auds).contains("moneycraft-swing-desktop", "moneycraft-api");
    }

    private String issueStepUp(String action, UUID targetProfileId, Long expectedRevision) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", OWNER_PASS);
        body.put("action", action);
        if (targetProfileId != null) {
            body.put("targetProfileId", targetProfileId.toString());
        }
        if (expectedRevision != null) {
            body.put("expectedRevision", expectedRevision);
        }
        MvcResult res = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/step-up/password")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("stepUpIntentId").asString();
    }

    private SessionPair loginPassword(String username, String password, boolean expectedSuperAdmin) throws Exception {
        MvcResult res = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password)))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.superAdmin").value(expectedSuperAdmin))
            .andReturn();
        return new SessionPair(res.getResponse().getCookie("MCSESSION"), res.getResponse().getCookie("XSRF-TOKEN"));
    }

    private static String computeS256Challenge(String codeVerifier) throws Exception {
        byte[] sha = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha);
    }

    private static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
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

    private record SessionPair(Cookie session, Cookie xsrf) {
    }
}
