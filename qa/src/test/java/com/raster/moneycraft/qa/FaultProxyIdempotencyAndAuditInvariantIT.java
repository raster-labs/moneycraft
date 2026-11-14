package com.raster.moneycraft.qa;

import com.raster.moneycraft.MoneyCraftApplication;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import com.zaxxer.hikari.HikariDataSource;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Gate G2 & G3 Failsafe Integration Suite:
 * 1. Real PostgreSQL 18.6 Dual-Cluster + Testcontainers ToxiproxyContainer in front of business PG18
 *    plus deterministic mid-response TCP FaultProxy.
 * 2. RFC 8785 JCS Idempotency-Key crash-retry with reordered JSON keys -> exact cached receipt,
 *    zero duplicate mc_entry postings, SUM(DR) == SUM(CR).
 * 3. Mutated Idempotency-Key payload conflict fail-closed rejection + exact minor-unit scale guard.
 * 4. Append-only immutability of mc_entry, mc_audit_event, and mc_edit_log_version enforced at both
 *    PostgreSQL runtime role privilege boundary and owner/superuser trigger boundary.
 * 5. Unbroken MC-AUDIT-2 hash chain and cross-cluster ma_receipt / mc_admin_receipt_mirror parity.
 */
@SpringBootTest(
    classes = MoneyCraftApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "moneycraft.datasource.business.maximum-pool-size=16",
        "moneycraft.datasource.authority.maximum-pool-size=12"
    }
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FaultProxyIdempotencyAndAuditInvariantIT {

    private static final int TOXIPROXY_PG_PORT = 8666;
    private static final QaDualClusterTestSupport.NetworkedDualCluster CLUSTERS;
    private static final ToxiproxyContainer TOXIPROXY;
    private static final Proxy BUSINESS_PG_PROXY;
    private static final String PROXIED_BUSINESS_JDBC_URL;

    private static CookieManager cookieManager;
    private static HttpClient httpClient;
    private static MidResponseTcpFaultProxy httpFaultProxy;

    private static String serviceApiKeyBearer;
    private static UUID ownerPrincipalId;
    private static UUID companyId;
    private static UUID openPeriodId;
    private static UUID journalVoucherTypeId;
    private static UUID cashLedgerId;
    private static UUID salesLedgerId;
    private static UUID debtorLedgerId;
    private static UUID crashRetriedVoucherId;
    private static String crashRetriedIdempotencyKey;

    static {
        CLUSTERS = QaDualClusterTestSupport.startNetworkedDualPostgres18();
        TOXIPROXY = new ToxiproxyContainer(QaDualClusterTestSupport.TOXIPROXY_IMAGE)
            .withNetwork(CLUSTERS.network())
            .withNetworkAliases("toxiproxy");
        TOXIPROXY.start();
        try {
            ToxiproxyClient client = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
            BUSINESS_PG_PROXY = client.createProxy(
                "business-pg-fault-proxy",
                "0.0.0.0:" + TOXIPROXY_PG_PORT,
                QaDualClusterTestSupport.BUSINESS_ALIAS + ":5432"
            );
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
        PROXIED_BUSINESS_JDBC_URL = "jdbc:postgresql://" + TOXIPROXY.getHost() + ":"
            + TOXIPROXY.getMappedPort(TOXIPROXY_PG_PORT) + "/" + QaDualClusterTestSupport.BUSINESS_DB_NAME;
    }

    @LocalServerPort
    private int serverPort;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("authorityJdbcTemplate")
    private JdbcTemplate authorityJdbcTemplate;

    @Autowired
    @Qualifier("businessJdbcTemplate")
    private JdbcTemplate businessJdbcTemplate;

    @Autowired
    @Qualifier("businessRawDataSource")
    private HikariDataSource businessRawDataSource;

    @Autowired
    @Qualifier("transactionManager")
    private PlatformTransactionManager businessTxManager;

    @DynamicPropertySource
    static void registerProxiedDualClusterProperties(DynamicPropertyRegistry registry) {
        registry.add("moneycraft.datasource.authority.jdbc-url", CLUSTERS.authorityPg()::getJdbcUrl);
        registry.add("moneycraft.datasource.authority.username", () -> QaDualClusterTestSupport.RUNTIME_USER);
        registry.add("moneycraft.datasource.authority.password", () -> QaDualClusterTestSupport.RUNTIME_PASS);
        registry.add("moneycraft.datasource.authority.migrator-username", () -> QaDualClusterTestSupport.MIGRATOR_USER);
        registry.add("moneycraft.datasource.authority.migrator-password", () -> QaDualClusterTestSupport.MIGRATOR_PASS);

        registry.add("moneycraft.datasource.business.jdbc-url", () -> PROXIED_BUSINESS_JDBC_URL);
        registry.add("moneycraft.datasource.business.username", () -> QaDualClusterTestSupport.RUNTIME_USER);
        registry.add("moneycraft.datasource.business.password", () -> QaDualClusterTestSupport.RUNTIME_PASS);
        registry.add("moneycraft.datasource.business.migrator-username", () -> QaDualClusterTestSupport.MIGRATOR_USER);
        registry.add("moneycraft.datasource.business.migrator-password", () -> QaDualClusterTestSupport.MIGRATOR_PASS);

        registry.add("moneycraft.crypto.master-kek", () -> QaDualClusterTestSupport.TEST_KEK);
        registry.add("moneycraft.security.cookie-secure", () -> "false");
        registry.add("moneycraft.base-url", () -> "http://localhost:8080");
        registry.add("moneycraft.webauthn.rp-id", () -> "localhost");
        registry.add("moneycraft.webauthn.allowed-origins[0]", () -> "http://localhost:8080");
        registry.add("management.server.port", () -> "0");
    }

    @BeforeAll
    static void initHttpClient() {
        cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        httpClient = HttpClient.newBuilder()
            .cookieHandler(cookieManager)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @AfterAll
    static void tearDownContainers() {
        if (httpFaultProxy != null) {
            httpFaultProxy.close();
        }
        try {
            TOXIPROXY.stop();
        } finally {
            CLUSTERS.close();
        }
    }

    @Test
    @Order(1)
    void toxiproxyBusinessPgResetFailsClosedWithoutPartialEntriesAndSucceedsOnRetry() throws Exception {
        if (httpFaultProxy == null) {
            httpFaultProxy = new MidResponseTcpFaultProxy(serverPort);
        }

        HttpResponse<String> initRes = postJson(
            "/api/v1/system/initialise",
            """
            {
              "installationLabel": "MoneyCraft QA FaultProxy & Invariant Cluster",
              "ownerUsername": "qa_fault_owner",
              "ownerDisplayName": "Principal QA Verifier",
              "password": "QaFaultOwner#2026!"
            }
            """,
            null
        );
        Assertions.assertThat(initRes.statusCode()).isEqualTo(201);
        JsonNode initJson = objectMapper.readTree(initRes.body());
        ownerPrincipalId = UUID.fromString(initJson.get("ownerPrincipal").get("principalId").asString());

        String gstinPrefix = "33AABCQ7788M1Z";
        String validGstin = gstinPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(gstinPrefix);
        HttpResponse<String> cmpRes = postSessionMutate(
            "/api/v1/companies",
            """
            {
              "code": "MCQAFAULT01",
              "legalName": "MoneyCraft FaultProxy Verification Pvt Ltd",
              "stateCode": "33",
              "countryCode": "IN",
              "baseCurrencyCode": "INR",
              "baseCurrencySymbol": "₹",
              "formalCurrencyName": "Indian Rupee",
              "currencyScale": 2,
              "financialYearFrom": "2025-04-01",
              "booksBeginningFrom": "2025-04-01",
              "features": {
                "maintainAccounts": true,
                "billWiseEnabled": true,
                "gstEnabled": true,
                "gstRegistrationType": "REGULAR",
                "gstin": "%s",
                "mcaEditLogEnforced": true
              }
            }
            """.formatted(validGstin),
            "qa-cmp-create-001"
        );
        Assertions.assertThat(cmpRes.statusCode()).isEqualTo(201);
        companyId = UUID.fromString(objectMapper.readTree(cmpRes.body()).get("id").asString());

        // Refresh session grant projection after creating company
        HttpResponse<String> loginRes = postJson(
            "/api/v1/auth/login",
            "{\"username\":\"qa_fault_owner\",\"password\":\"QaFaultOwner#2026!\"}",
            null
        );
        Assertions.assertThat(loginRes.statusCode()).isEqualTo(200);

        // Issue a scoped Service API Key for deterministic stateless retry & FaultProxy testing
        HttpResponse<String> keyRes = postSessionMutate(
            "/api/v1/admin/service-keys",
            """
            {
              "label": "QA FaultProxy Retry Terminal",
              "companyIds": ["%s"],
              "endpointAllowlist": ["POST:/api/v1/companies/**", "PUT:/api/v1/companies/**", "GET:/api/v1/companies/**"]
            }
            """.formatted(companyId),
            "qa-svc-key-001"
        );
        Assertions.assertThat(keyRes.statusCode()).isEqualTo(201);
        serviceApiKeyBearer = objectMapper.readTree(keyRes.body()).get("rawSecretOnce").asString();

        cashLedgerId = findLedgerIdByName("Cash");
        UUID salesGroupId = findGroupIdByName("Sales Accounts");
        UUID debtorsGroupId = findGroupIdByName("Sundry Debtors");
        salesLedgerId = createLedger("Domestic Product Sales", salesGroupId);
        debtorLedgerId = createLedger("Kaveri Industrial Buyers", debtorsGroupId);

        HttpResponse<String> periodsRes = getAuthed("/api/v1/companies/" + companyId + "/periods");
        Assertions.assertThat(periodsRes.statusCode()).isEqualTo(200);
        openPeriodId = UUID.fromString(objectMapper.readTree(periodsRes.body()).get(0).get("id").asString());

        TenantContextHolder.runWithCompany(companyId, () -> {
            journalVoucherTypeId = businessJdbcTemplate.queryForObject(
                "SELECT id FROM mc_voucher_type WHERE company_id = ? AND name = 'Journal'",
                UUID.class,
                companyId
            );
        });

        String dbCutIdemKey = "idem-toxiproxy-db-rst-" + UUID.randomUUID();
        String voucherPayload = """
            {
              "voucherTypeName": "Sales",
              "voucherDate": "2025-04-10",
              "persistedView": "AS_VOUCHER",
              "narration": "Upstream Toxiproxy PG18 reset resilience check",
              "lines": [
                { "ledgerId": "%s", "side": "DR", "amount": "50000.00" },
                { "ledgerId": "%s", "side": "CR", "amount": "50000.00" }
              ]
            }
            """.formatted(debtorLedgerId, salesLedgerId);

        // Inject real TCP RST toxic on Business PostgreSQL 18 via ToxiproxyContainer
        BUSINESS_PG_PROXY.toxics().resetPeer("pg-downstream-rst", ToxicDirection.DOWNSTREAM, 0L);
        BUSINESS_PG_PROXY.toxics().resetPeer("pg-upstream-rst", ToxicDirection.UPSTREAM, 0L);
        if (businessRawDataSource.getHikariPoolMXBean() != null) {
            businessRawDataSource.getHikariPoolMXBean().softEvictConnections();
        }

        try {
            HttpResponse<String> failedAttempt = postBearerVoucher(voucherPayload, dbCutIdemKey);
            Assertions.assertThat(failedAttempt.statusCode()).isGreaterThanOrEqualTo(400);
        } finally {
            BUSINESS_PG_PROXY.toxics().get("pg-downstream-rst").remove();
            BUSINESS_PG_PROXY.toxics().get("pg-upstream-rst").remove();
        }

        // Wait > 1s so AuthorityGuard lease health check detects broken socket and re-acquires advisory lock
        Thread.sleep(1_150L);
        if (businessRawDataSource.getHikariPoolMXBean() != null) {
            businessRawDataSource.getHikariPoolMXBean().softEvictConnections();
        }

        // Confirm zero partial or unbalanced mc_entry rows were persisted during the Toxiproxy TCP reset
        Assertions.assertThat(countCompanyEntriesDirect()).isZero();

        // Retry with the SAME Idempotency-Key after PostgreSQL 18 link recovery
        HttpResponse<String> recoveredAttempt = postBearerVoucher(voucherPayload, dbCutIdemKey);
        Assertions.assertThat(recoveredAttempt.statusCode()).isEqualTo(201);
        JsonNode created = objectMapper.readTree(recoveredAttempt.body());
        Assertions.assertThat(created.get("status").asString()).isEqualTo("POSTED");
        Assertions.assertThat(countCompanyEntriesDirect()).isEqualTo(2L);
        assertGlobalDoubleEntryBalance("50000.00");
    }

    @Test
    @Order(2)
    void faultProxyMidResponseDropAndJcsReorderedReplayReturnCachedReceiptWithZeroDuplicateEntries()
        throws Exception {
        long entriesBefore = countCompanyEntriesDirect();
        long journalsBefore = countCompanyJournalsDirect();

        crashRetriedIdempotencyKey = "idem-faultproxy-jcs-" + UUID.randomUUID();
        String originalPayload = """
            {
              "voucherTypeName": "Journal",
              "voucherDate": "2025-04-18",
              "persistedView": "AS_VOUCHER",
              "narration": "Mid-flight TCP drop + RFC 8785 JCS key-reordered replay",
              "lines": [
                {
                  "ledgerId": "%s",
                  "side": "DR",
                  "amount": "125000.00"
                },
                {
                  "ledgerId": "%s",
                  "side": "CR",
                  "amount": "125000.00"
                }
              ]
            }
            """.formatted(cashLedgerId, salesLedgerId);

        // Arm FaultProxy to sever TCP socket with RST after reading first 16 bytes of HTTP 201 response
        // (AFTER PostgreSQL 18 committed mc_entry + mc_retry, BEFORE client receives full HTTP response!)
        httpFaultProxy.armSeverAfterResponseBytes(16);
        Assertions.assertThatThrownBy(() ->
            httpFaultProxy.sendPostThroughFaultySocket(
                "/api/v1/companies/" + companyId + "/vouchers",
                serviceApiKeyBearer,
                crashRetriedIdempotencyKey,
                originalPayload
            )
        ).isInstanceOf(IOException.class);

        // Server committed exactly 1 voucher (2 mc_entry rows) despite client losing the response mid-flight
        Assertions.assertThat(countCompanyJournalsDirect()).isEqualTo(journalsBefore + 1L);
        Assertions.assertThat(countCompanyEntriesDirect()).isEqualTo(entriesBefore + 2L);

        // Client retries with IDENTICAL Idempotency-Key and JCS-equivalent JSON with ALL keys reordered!
        String jcsKeyReorderedPayload = """
            {"lines":[{"amount":"125000.00","side":"DR","ledgerId":"%s"},{"side":"CR","amount":"125000.00","ledgerId":"%s"}],"narration":"Mid-flight TCP drop + RFC 8785 JCS key-reordered replay","persistedView":"AS_VOUCHER","voucherDate":"2025-04-18","voucherTypeName":"Journal"}
            """.formatted(cashLedgerId, salesLedgerId).trim();

        HttpResponse<String> replayRes = postBearerVoucher(jcsKeyReorderedPayload, crashRetriedIdempotencyKey);
        Assertions.assertThat(replayRes.statusCode()).isEqualTo(201);
        Assertions.assertThat(replayRes.headers().firstValue("X-Idempotency-Replayed"))
            .contains("true");
        Assertions.assertThat(replayRes.headers().firstValue("ETag"))
            .contains("\"v1\"");

        JsonNode replayBody = objectMapper.readTree(replayRes.body());
        crashRetriedVoucherId = UUID.fromString(replayBody.get("id").asString());
        String cachedVoucherNumber = replayBody.get("voucherNumber").asString();
        Assertions.assertThat(cachedVoucherNumber).isNotBlank();

        // Zero duplicate vouchers or mc_entry rows inserted!
        Assertions.assertThat(countCompanyJournalsDirect()).isEqualTo(journalsBefore + 1L);
        Assertions.assertThat(countCompanyEntriesDirect()).isEqualTo(entriesBefore + 2L);

        // Second replay with another key permutation confirms deterministic receipt stability
        String secondPermutation = """
            {
              "persistedView": "AS_VOUCHER",
              "narration": "Mid-flight TCP drop + RFC 8785 JCS key-reordered replay",
              "voucherTypeName": "Journal",
              "lines": [
                { "side": "DR", "ledgerId": "%s", "amount": "125000.00" },
                { "amount": "125000.00", "ledgerId": "%s", "side": "CR" }
              ],
              "voucherDate": "2025-04-18"
            }
            """.formatted(cashLedgerId, salesLedgerId);

        HttpResponse<String> secondReplayRes = postBearerVoucher(secondPermutation, crashRetriedIdempotencyKey);
        Assertions.assertThat(secondReplayRes.statusCode()).isEqualTo(201);
        Assertions.assertThat(secondReplayRes.headers().firstValue("X-Idempotency-Replayed"))
            .contains("true");
        JsonNode secondReplayJson = objectMapper.readTree(secondReplayRes.body());
        Assertions.assertThat(secondReplayJson.get("id").asString()).isEqualTo(crashRetriedVoucherId.toString());
        Assertions.assertThat(secondReplayJson.get("voucherNumber").asString()).isEqualTo(cachedVoucherNumber);
        Assertions.assertThat(countCompanyEntriesDirect()).isEqualTo(entriesBefore + 2L);

        // Total DR == Total CR == 50,000.00 + 125,000.00 = 175,000.00
        assertGlobalDoubleEntryBalance("175000.00");
    }

    @Test
    @Order(3)
    void mutatedIdempotencyPayloadConflictAndExcessScaleFailClosed() throws Exception {
        long entriesBefore = countCompanyEntriesDirect();

        // Replaying the same Idempotency-Key with a mutated amount MUST fail closed without posting
        String mutatedAmountPayload = """
            {
              "voucherTypeName": "Journal",
              "voucherDate": "2025-04-18",
              "persistedView": "AS_VOUCHER",
              "narration": "Mid-flight TCP drop + RFC 8785 JCS key-reordered replay",
              "lines": [
                { "ledgerId": "%s", "side": "DR", "amount": "125000.01" },
                { "ledgerId": "%s", "side": "CR", "amount": "125000.01" }
              ]
            }
            """.formatted(cashLedgerId, salesLedgerId);

        HttpResponse<String> conflictRes = postBearerVoucher(mutatedAmountPayload, crashRetriedIdempotencyKey);
        Assertions.assertThat(conflictRes.statusCode()).isIn(409, 422);
        JsonNode problem = objectMapper.readTree(conflictRes.body());
        Assertions.assertThat(problem.get("code").asString()).isEqualTo("MC-IDEMPOTENCY-PAYLOAD-MISMATCH");
        Assertions.assertThat(countCompanyEntriesDirect()).isEqualTo(entriesBefore);

        // Posting excess minor-unit scale (3 decimals on INR scale-2 company) fails closed with HTTP 422
        String scaleOverflowPayload = """
            {
              "voucherTypeName": "Journal",
              "voucherDate": "2025-04-19",
              "persistedView": "AS_VOUCHER",
              "narration": "Scale overflow check",
              "lines": [
                { "ledgerId": "%s", "side": "DR", "amount": "100.125" },
                { "ledgerId": "%s", "side": "CR", "amount": "100.125" }
              ]
            }
            """.formatted(cashLedgerId, salesLedgerId);

        HttpResponse<String> scaleRes = postBearerVoucher(
            scaleOverflowPayload,
            "idem-scale-overflow-" + UUID.randomUUID()
        );
        Assertions.assertThat(scaleRes.statusCode()).isEqualTo(422);
        Assertions.assertThat(objectMapper.readTree(scaleRes.body()).get("code").asString())
            .isEqualTo("MC-ACC-SCALE-OVERFLOW");
        Assertions.assertThat(countCompanyEntriesDirect()).isEqualTo(entriesBefore);
    }

    @Test
    @Order(4)
    void updateAndDeleteAgainstMcEntryMcAuditEventAndMcEditLogVersionFailClosedAtPrivilegeAndTriggerBoundaries()
        throws Exception {
        // Alter voucher v1 -> v2 so mc_edit_log_version holds both CREATE (v1) and ALTER (v2) snapshots
        String alterPayload = """
            {
              "voucherTypeName": "Journal",
              "voucherDate": "2025-04-18",
              "persistedView": "AS_VOUCHER",
              "narration": "Altered narration for MCA Edit Log immutability verification",
              "lines": [
                { "ledgerId": "%s", "side": "DR", "amount": "130000.00" },
                { "ledgerId": "%s", "side": "CR", "amount": "130000.00" }
              ]
            }
            """.formatted(cashLedgerId, salesLedgerId);

        HttpResponse<String> alterRes = putBearerVoucher(
            crashRetriedVoucherId,
            "\"v1\"",
            alterPayload,
            "idem-alter-vch-" + UUID.randomUUID()
        );
        Assertions.assertThat(alterRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(alterRes.headers().firstValue("ETag")).contains("\"v2\"");

        // Stale If-Match ("v1" when current is "v2") must fail closed with HTTP 412 Precondition Failed
        HttpResponse<String> staleRes = putBearerVoucher(
            crashRetriedVoucherId,
            "\"v1\"",
            alterPayload,
            "idem-stale-ifmatch-" + UUID.randomUUID()
        );
        Assertions.assertThat(staleRes.statusCode()).isEqualTo(412);

        // Total DR == Total CR == 50,000.00 + 130,000.00 = 180,000.00 (compensating reversal + repost)
        assertGlobalDoubleEntryBalance("180000.00");

        // 1. Runtime privilege boundary (mc_app_runtime NOBYPASSRLS has UPDATE/DELETE revoked)
        TenantContextHolder.runWithCompany(companyId, () -> {
            Assertions.assertThatThrownBy(() ->
                businessJdbcTemplate.update("UPDATE mc_entry SET amount = amount + 1 WHERE company_id = ?", companyId)
            ).isInstanceOf(DataAccessException.class);

            Assertions.assertThatThrownBy(() ->
                businessJdbcTemplate.update("DELETE FROM mc_entry WHERE company_id = ?", companyId)
            ).isInstanceOf(DataAccessException.class);

            Assertions.assertThatThrownBy(() ->
                businessJdbcTemplate.update("UPDATE mc_audit_event SET outcome = 'FAILED' WHERE company_id = ?", companyId)
            ).isInstanceOf(DataAccessException.class);

            Assertions.assertThatThrownBy(() ->
                businessJdbcTemplate.update("DELETE FROM mc_audit_event WHERE company_id = ?", companyId)
            ).isInstanceOf(DataAccessException.class);

            Assertions.assertThatThrownBy(() ->
                businessJdbcTemplate.update(
                    "UPDATE mc_edit_log_version SET operation = 'DELETE' WHERE company_id = ?",
                    companyId
                )
            ).isInstanceOf(DataAccessException.class);

            Assertions.assertThatThrownBy(() ->
                businessJdbcTemplate.update("DELETE FROM mc_edit_log_version WHERE company_id = ?", companyId)
            ).isInstanceOf(DataAccessException.class);
        });

        // 2. PostgreSQL trigger boundary (even superuser/owner bypassing GRANTs is blocked fail-closed!)
        try (
            Connection superConn = DriverManager.getConnection(
                CLUSTERS.businessPg().getJdbcUrl(),
                QaDualClusterTestSupport.SUPERUSER,
                QaDualClusterTestSupport.SUPERUSER_PASS
            );
            Statement stmt = superConn.createStatement()
        ) {
            assertSqlFailsWithImmutableTrigger(
                stmt,
                "UPDATE mc_entry SET amount = amount + 1 WHERE company_id = '" + companyId + "'"
            );
            assertSqlFailsWithImmutableTrigger(
                stmt,
                "DELETE FROM mc_entry WHERE company_id = '" + companyId + "'"
            );
            assertSqlFailsWithImmutableTrigger(
                stmt,
                "UPDATE mc_audit_event SET outcome = 'FAILED' WHERE scope_type = 'COMPANY'"
            );
            assertSqlFailsWithImmutableTrigger(
                stmt,
                "DELETE FROM mc_audit_event WHERE scope_type = 'COMPANY'"
            );
            assertSqlFailsWithImmutableTrigger(
                stmt,
                "UPDATE mc_edit_log_version SET actor_username = 'tampered' WHERE company_id = '" + companyId + "'"
            );
            assertSqlFailsWithImmutableTrigger(
                stmt,
                "DELETE FROM mc_edit_log_version WHERE company_id = '" + companyId + "'"
            );
        }

        // 3. Deferred double-entry trigger aborts unbalanced direct SQL commit on mc_entry
        TransactionTemplate txTemplate = new TransactionTemplate(businessTxManager);
        Assertions.assertThatThrownBy(() ->
            TenantContextHolder.runWithCompany(companyId, () -> txTemplate.executeWithoutResult(status -> {
                UUID badJournalId = UUID.randomUUID();
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_journal (
                        id, company_id, period_id, voucher_type_id, status, posting_nature,
                        persisted_view, approval_policy, voucher_number, voucher_date, effective_date,
                        total_debit, total_credit, narration, current_posting_batch_no, created_by, posted_by
                    ) VALUES (?, ?, ?, ?, 'POSTED', 'NORMAL', 'AS_VOUCHER', 'SINGLE_STEP', 'BAD-UNBAL-1',
                              ?, ?, 500.00, 499.99, 'Adversarial unbalanced SQL test', 1, ?, ?)
                    """,
                    badJournalId,
                    companyId,
                    openPeriodId,
                    journalVoucherTypeId,
                    LocalDate.of(2025, 4, 22),
                    LocalDate.of(2025, 4, 22),
                    ownerPrincipalId,
                    ownerPrincipalId
                );
                insertRawMcEntry(badJournalId, cashLedgerId, "DR", new BigDecimal("500.00"));
                insertRawMcEntry(badJournalId, salesLedgerId, "CR", new BigDecimal("499.99"));
            }))
        )
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("MC-ACC-UNBALANCED-JOURNAL");
    }

    @Test
    @Order(5)
    void adminAuditVerifyConfirmsUnbrokenMcAudit2ChainAndCrossClusterAdminReceiptParity() throws Exception {
        HttpResponse<String> auditRes = getAuthed("/api/v1/admin/audit/verify");
        Assertions.assertThat(auditRes.statusCode()).isEqualTo(200);
        JsonNode auditJson = objectMapper.readTree(auditRes.body());

        Assertions.assertThat(auditJson.get("verified").asBoolean()).isTrue();
        Assertions.assertThat(auditJson.get("checkedEventCount").asLong()).isGreaterThanOrEqualTo(6L);
        Assertions.assertThat(auditJson.get("tailHashSha256").asString()).matches("^[0-9a-f]{64}$");
        Assertions.assertThat(auditJson.get("failureDetail").isNull()).isTrue();

        // Verify zero raw credentials/secrets leaked into mc_audit_event.redacted_payload
        Integer leakedSecrets = businessJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM mc_audit_event
             WHERE redacted_payload::text ILIKE '%QaFaultOwner#2026!%'
                OR redacted_payload::text ILIKE '%$argon2id$%'
            """,
            Integer.class
        );
        Assertions.assertThat(leakedSecrets).isZero();

        // Verify cryptographic receipt parity across Authority (ma_receipt) & Business (mc_admin_receipt_mirror)
        List<ReceiptDigestRow> authReceipts = authorityJdbcTemplate.query(
            """
            SELECT receipt_seq, receipt_id, receipt_hash, prev_receipt_hash, mirrored_to_business
              FROM ma_receipt
             ORDER BY receipt_seq ASC
            """,
            (rs, rowNum) -> new ReceiptDigestRow(
                rs.getLong("receipt_seq"),
                rs.getObject("receipt_id", UUID.class),
                rs.getBytes("receipt_hash"),
                rs.getBytes("prev_receipt_hash"),
                rs.getBoolean("mirrored_to_business")
            )
        );
        List<ReceiptDigestRow> bizMirrors = businessJdbcTemplate.query(
            """
            SELECT receipt_seq, receipt_id, receipt_hash, prev_receipt_hash, true AS mirrored_to_business
              FROM mc_admin_receipt_mirror
             ORDER BY receipt_seq ASC
            """,
            (rs, rowNum) -> new ReceiptDigestRow(
                rs.getLong("receipt_seq"),
                rs.getObject("receipt_id", UUID.class),
                rs.getBytes("receipt_hash"),
                rs.getBytes("prev_receipt_hash"),
                rs.getBoolean("mirrored_to_business")
            )
        );

        Assertions.assertThat(authReceipts).isNotEmpty();
        Assertions.assertThat(bizMirrors).hasSameSizeAs(authReceipts);

        for (int i = 0; i < authReceipts.size(); i++) {
            ReceiptDigestRow authRow = authReceipts.get(i);
            ReceiptDigestRow bizRow = bizMirrors.get(i);
            Assertions.assertThat(authRow.mirrored()).isTrue();
            Assertions.assertThat(bizRow.seq()).isEqualTo(authRow.seq());
            Assertions.assertThat(bizRow.receiptId()).isEqualTo(authRow.receiptId());
            Assertions.assertThat(MessageDigest.isEqual(authRow.receiptHash(), bizRow.receiptHash())).isTrue();
            Assertions.assertThat(MessageDigest.isEqual(authRow.prevReceiptHash(), bizRow.prevReceiptHash())).isTrue();
            if (i > 0) {
                Assertions.assertThat(
                    MessageDigest.isEqual(authReceipts.get(i - 1).receiptHash(), authRow.prevReceiptHash())
                ).isTrue();
            }
        }

        Long fenceMirroredSeq = businessJdbcTemplate.queryForObject(
            "SELECT last_mirrored_receipt_seq FROM mc_installation_fence WHERE id = 1",
            Long.class
        );
        Assertions.assertThat(fenceMirroredSeq).isEqualTo(authReceipts.getLast().seq());
    }

    private void insertRawMcEntry(
        UUID journalId,
        UUID ledgerId,
        String side,
        BigDecimal amount
    ) {
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_entry (
                id, company_id, journal_id, journal_line_id, period_id, entry_date,
                ledger_id, side, amount, base_currency_amount, posting_batch_no, is_reversal
            ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, 1, false)
            """,
            UUID.randomUUID(),
            companyId,
            journalId,
            openPeriodId,
            LocalDate.of(2025, 4, 22),
            ledgerId,
            side,
            amount,
            amount
        );
    }

    private static void assertSqlFailsWithImmutableTrigger(Statement stmt, String sql) {
        Assertions.assertThatThrownBy(() -> stmt.executeUpdate(sql))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("MC-IMMUTABLE-RECORD");
    }

    private long countCompanyEntriesDirect() throws SQLException {
        try (
            Connection conn = DriverManager.getConnection(
                CLUSTERS.businessPg().getJdbcUrl(),
                QaDualClusterTestSupport.SUPERUSER,
                QaDualClusterTestSupport.SUPERUSER_PASS
            );
            PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM mc_entry WHERE company_id = ?")
        ) {
            ps.setObject(1, companyId);
            var rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    private long countCompanyJournalsDirect() throws SQLException {
        try (
            Connection conn = DriverManager.getConnection(
                CLUSTERS.businessPg().getJdbcUrl(),
                QaDualClusterTestSupport.SUPERUSER,
                QaDualClusterTestSupport.SUPERUSER_PASS
            );
            PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM mc_journal WHERE company_id = ?")
        ) {
            ps.setObject(1, companyId);
            var rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    private void assertGlobalDoubleEntryBalance(String expectedNetClosingTurnover) {
        TenantContextHolder.runWithCompany(companyId, () -> {
            BigDecimal entryDr = businessJdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0::numeric) FROM mc_entry WHERE company_id = ? AND side = 'DR'",
                BigDecimal.class,
                companyId
            );
            BigDecimal entryCr = businessJdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0::numeric) FROM mc_entry WHERE company_id = ? AND side = 'CR'",
                BigDecimal.class,
                companyId
            );
            Assertions.assertThat(entryDr).isNotNull();
            Assertions.assertThat(entryCr).isNotNull();
            Assertions.assertThat(entryDr).isEqualByComparingTo(entryCr);

            BigDecimal periodDr = businessJdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(dr_total), 0::numeric) FROM mc_ledger_period_bal WHERE company_id = ?",
                BigDecimal.class,
                companyId
            );
            BigDecimal periodCr = businessJdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(cr_total), 0::numeric) FROM mc_ledger_period_bal WHERE company_id = ?",
                BigDecimal.class,
                companyId
            );
            Assertions.assertThat(periodDr).isNotNull();
            Assertions.assertThat(periodCr).isNotNull();
            Assertions.assertThat(periodDr).isEqualByComparingTo(periodCr);

            BigDecimal closingDr = businessJdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(closing_dr), 0::numeric) FROM vw_mc_ledger_closing_balance WHERE company_id = ?",
                BigDecimal.class,
                companyId
            );
            BigDecimal closingCr = businessJdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(closing_cr), 0::numeric) FROM vw_mc_ledger_closing_balance WHERE company_id = ?",
                BigDecimal.class,
                companyId
            );
            Assertions.assertThat(closingDr).isEqualByComparingTo(expectedNetClosingTurnover);
            Assertions.assertThat(closingCr).isEqualByComparingTo(expectedNetClosingTurnover);
        });
    }

    private UUID findGroupIdByName(String groupName) throws Exception {
        String cursor = null;
        do {
            String path = "/api/v1/companies/" + companyId + "/account-groups?limit=50"
                + (cursor == null ? "" : "&cursor=" + cursor);
            HttpResponse<String> res = getAuthed(path);
            Assertions.assertThat(res.statusCode()).isEqualTo(200);
            JsonNode page = objectMapper.readTree(res.body());
            for (JsonNode g : page.get("items")) {
                if (groupName.equals(g.get("name").asString())) {
                    return UUID.fromString(g.get("id").asString());
                }
            }
            cursor = page.get("hasMore").asBoolean() ? page.get("nextCursor").asString() : null;
        } while (cursor != null);
        throw new IllegalStateException("Seeded account group not found: " + groupName);
    }

    private UUID findLedgerIdByName(String ledgerName) throws Exception {
        HttpResponse<String> res = getAuthed("/api/v1/companies/" + companyId + "/ledgers?q=" + ledgerName);
        Assertions.assertThat(res.statusCode()).isEqualTo(200);
        for (JsonNode item : objectMapper.readTree(res.body()).get("items")) {
            if (ledgerName.equals(item.get("name").asString())) {
                return UUID.fromString(item.get("id").asString());
            }
        }
        throw new IllegalStateException("Ledger not found: " + ledgerName);
    }

    private UUID createLedger(String name, UUID groupId) throws Exception {
        HttpResponse<String> res = postSessionMutate(
            "/api/v1/companies/" + companyId + "/ledgers",
            """
            {
              "name": "%s",
              "groupId": "%s",
              "maintainBalancesBillByBill": false,
              "openingBalance": "0.00",
              "openingBalanceSide": "DR"
            }
            """.formatted(name, groupId),
            "qa-ldg-" + UUID.randomUUID()
        );
        Assertions.assertThat(res.statusCode()).isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(res.body()).get("id").asString());
    }

    private HttpResponse<String> getAuthed(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + serverPort + path))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postJson(String path, String json, String idempotencyKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + serverPort + path))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postSessionMutate(String path, String json, String idempotencyKey) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + serverPort + path))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("X-XSRF-TOKEN", currentXsrfToken())
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postBearerVoucher(String json, String idempotencyKey) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + serverPort + "/api/v1/companies/" + companyId + "/vouchers"))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + serviceApiKeyBearer)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> putBearerVoucher(
        UUID voucherId,
        String ifMatchEtag,
        String json,
        String idempotencyKey
    ) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(
                URI.create(
                    "http://localhost:" + serverPort
                        + "/api/v1/companies/" + companyId + "/vouchers/" + voucherId
                )
            )
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + serviceApiKeyBearer)
            .header("If-Match", ifMatchEtag)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String currentXsrfToken() {
        for (HttpCookie cookie : cookieManager.getCookieStore().getCookies()) {
            if ("XSRF-TOKEN".equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return URLDecoder.decode(cookie.getValue(), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Missing XSRF-TOKEN cookie in CookieStore");
    }

    private record ReceiptDigestRow(
        long seq,
        UUID receiptId,
        byte[] receiptHash,
        byte[] prevReceiptHash,
        boolean mirrored
    ) {
    }

    /**
     * Deterministic TCP FaultProxy that forwards an HTTP request completely to the upstream server
     * (so the server commits the DB transaction + RFC 8785 JCS idempotency receipt) and then severs
     * the downstream TCP socket with RST (SO_LINGER = 0) mid-response.
     */
    private static final class MidResponseTcpFaultProxy implements AutoCloseable {

        private final int upstreamPort;
        private final ServerSocket serverSocket;
        private final ExecutorService acceptorPool;
        private final AtomicInteger severAfterBytes = new AtomicInteger(-1);
        private volatile boolean active = true;

        MidResponseTcpFaultProxy(int upstreamPort) throws IOException {
            this.upstreamPort = upstreamPort;
            this.serverSocket = new ServerSocket();
            this.serverSocket.setReuseAddress(true);
            this.serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
            this.acceptorPool = Executors.newVirtualThreadPerTaskExecutor();
            this.acceptorPool.submit(this::acceptLoop);
        }

        void armSeverAfterResponseBytes(int byteLimit) {
            severAfterBytes.set(byteLimit);
        }

        void sendPostThroughFaultySocket(
            String path,
            String bearerToken,
            String idempotencyKey,
            String jsonBody
        ) throws IOException {
            byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            String rawHttpRequest = "POST " + path + " HTTP/1.1\r\n"
                + "Host: localhost:" + upstreamPort + "\r\n"
                + "Authorization: Bearer " + bearerToken + "\r\n"
                + "Idempotency-Key: " + idempotencyKey + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n\r\n";

            try (Socket clientSocket = new Socket("127.0.0.1", serverSocket.getLocalPort())) {
                clientSocket.setSoTimeout(15_000);
                OutputStream out = clientSocket.getOutputStream();
                out.write(rawHttpRequest.getBytes(StandardCharsets.UTF_8));
                out.write(bodyBytes);
                out.flush();

                InputStream in = clientSocket.getInputStream();
                ByteArrayOutputStream responseBuf = new ByteArrayOutputStream();
                byte[] chunk = new byte[256];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    responseBuf.write(chunk, 0, n);
                }
                String received = responseBuf.toString(StandardCharsets.UTF_8);
                if (!received.contains("\r\n\r\n") || !received.trim().endsWith("}")) {
                    throw new IOException("Truncated HTTP response due to mid-flight FaultProxy cut: " + received);
                }
            }
        }

        private void acceptLoop() {
            while (active) {
                try {
                    Socket downSocket = serverSocket.accept();
                    acceptorPool.submit(() -> relayConnection(downSocket));
                } catch (IOException ignored) {
                    // Socket closed on shutdown
                }
            }
        }

        private void relayConnection(Socket downSocket) {
            int cutLimit = severAfterBytes.getAndSet(-1);
            try (
                Socket client = downSocket;
                Socket upstream = new Socket("127.0.0.1", upstreamPort)
            ) {
                Thread reqPipe = Thread.ofVirtual().start(() -> {
                    try {
                        InputStream cIn = client.getInputStream();
                        OutputStream uOut = upstream.getOutputStream();
                        byte[] buf = new byte[4096];
                        int read;
                        while ((read = cIn.read(buf)) != -1) {
                            uOut.write(buf, 0, read);
                            uOut.flush();
                        }
                    } catch (IOException ignored) {
                        // Upstream closed after response cut
                    }
                });

                InputStream uIn = upstream.getInputStream();
                OutputStream cOut = client.getOutputStream();
                byte[] buf = new byte[64];
                int totalForwarded = 0;
                int read;
                while ((read = uIn.read(buf)) != -1) {
                    if (cutLimit >= 0 && totalForwarded + read >= cutLimit) {
                        int partial = Math.max(0, cutLimit - totalForwarded);
                        if (partial > 0) {
                            cOut.write(buf, 0, partial);
                            cOut.flush();
                        }
                        client.setSoLinger(true, 0);
                        client.close();
                        reqPipe.interrupt();
                        return;
                    }
                    cOut.write(buf, 0, read);
                    cOut.flush();
                    totalForwarded += read;
                }
                reqPipe.join(2_000L);
            } catch (IOException | InterruptedException ignored) {
                // Expected during simulated TCP reset
            }
        }

        @Override
        public void close() {
            active = false;
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // Already closed
            }
            acceptorPool.shutdownNow();
        }
    }
}
