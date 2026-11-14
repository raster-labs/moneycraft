package com.raster.moneycraft.accounting;

import com.raster.moneycraft.MoneyCraftApplication;
import com.raster.moneycraft.foundation.DualPostgres18TestcontainersSupport;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Concurrency & Idempotency verification (AT-CONC-01 & AT-RETRY-01) against Dual-PostgreSQL 18:
 * 32 concurrent virtual threads posting simultaneously against overlapping Cash / Sales / Party
 * ledgers (in deliberately inverted input order) and shared AUTOMATIC_RETAIN_NUMBERS / MULTI_USER_AUTO
 * number series, plus RFC 8785 JCS Idempotency-Key network-drop replay and payload-mismatch conflict.
 */
@SpringBootTest(
    classes = MoneyCraftApplication.class,
    properties = {
        "moneycraft.datasource.business.maximum-pool-size=40",
        "moneycraft.datasource.authority.maximum-pool-size=24"
    }
)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VoucherConcurrencyAndIdempotencyTest {

    private static final int CONCURRENT_THREADS = 32;

    private static final DualPostgres18TestcontainersSupport.ClusterPair CLUSTERS =
        DualPostgres18TestcontainersSupport.startIsolatedClusterPair();

    private static Cookie ownerSessionCookie;
    private static Cookie ownerXsrfCookie;
    private static String serviceApiKeyBearer;
    private static UUID companyId;
    private static UUID cashLedgerId;
    private static UUID salesLedgerId;
    private static UUID partyLedgerId;

    @DynamicPropertySource
    static void registerClusterProperties(DynamicPropertyRegistry registry) {
        DualPostgres18TestcontainersSupport.bindClusterProperties(registry, CLUSTERS);
    }

    @AfterAll
    static void stopClusters() {
        CLUSTERS.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("businessJdbcTemplate")
    private JdbcTemplate businessJdbcTemplate;

    @Test
    @Order(1)
    void thirtyTwoConcurrentThreadsPostOverlappingLedgersWithZeroDeadlocksAndGapFreeNumbers() throws Exception {
        MvcResult initRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/system/initialise")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "installationLabel": "MoneyCraft 32-Thread Concurrency Cluster",
                      "ownerUsername": "conc_admin",
                      "ownerDisplayName": "Concurrency Controller",
                      "password": "ConcPassword#2026!"
                    }
                    """
                )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        ownerSessionCookie = latestCookie(initRes, "MCSESSION");
        ownerXsrfCookie = latestCookie(initRes, "XSRF-TOKEN");

        companyId = createTestCompany("CONC25", "Vaigai High-Throughput Retail Ltd", "33", "2025-04-01");

        // Create a scoped Service API Key so 32 concurrent HTTP threads hammer the full filter + DB stack
        MvcResult apiKeyRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/admin/service-keys")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "label": "32-Thread POS Terminal Key",
                          "companyIds": ["%s"],
                          "endpointAllowlist": ["POST:/api/v1/companies/**", "GET:/api/v1/companies/**"]
                        }
                        """.formatted(companyId)
                    ),
                "conc-apikey-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        serviceApiKeyBearer = objectMapper.readTree(apiKeyRes.getResponse().getContentAsByteArray())
            .get("rawSecretOnce").asString();

        cashLedgerId = findLedgerIdByName("Cash");
        UUID salesGroupId = findGroupIdByName("Sales Accounts");
        UUID debtorsGroupId = findGroupIdByName("Sundry Debtors");
        salesLedgerId = createLedger("POS Counter Sales", salesGroupId);
        partyLedgerId = createLedger("Walk-in Corporate Account", debtorsGroupId);

        // Create a MULTI_USER_AUTO voucher type as well
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/voucher-types")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "POS Fast Sales",
                          "abbreviation": "POS",
                          "parentVoucherType": "Sales",
                          "category": "SALES",
                          "numberingMethod": "MULTI_USER_AUTO"
                        }
                        """
                    ),
                "vt-pos-multi-auto"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        // Wave 1: 32 concurrent virtual threads against seeded "Sales" (AUTOMATIC_RETAIN_NUMBERS)
        runConcurrentWave("Sales", "wave1");

        // Wave 2: 32 concurrent virtual threads against "POS Fast Sales" (MULTI_USER_AUTO)
        runConcurrentWave("POS Fast Sales", "wave2");

        // Assert global ledger period balance invariant SUM(DR) == SUM(CR) across all 64 concurrent vouchers
        TenantContextHolder.runWithCompany(companyId, () -> {
            BigDecimal totalDr = businessJdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(dr_total), 0::numeric) FROM mc_ledger_period_bal WHERE company_id = ?",
                BigDecimal.class,
                companyId
            );
            BigDecimal totalCr = businessJdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(cr_total), 0::numeric) FROM mc_ledger_period_bal WHERE company_id = ?",
                BigDecimal.class,
                companyId
            );
            // Each wave posts 32 vouchers of 1000.00 each -> 64,000.00 total DR and 64,000.00 total CR
            Assertions.assertThat(totalDr).isEqualByComparingTo("64000.00");
            Assertions.assertThat(totalCr).isEqualByComparingTo("64000.00");
        });

        // Assert MC-AUDIT-2 hash chain serialized all 64+ concurrent events with zero forks
        mockMvc.perform(authedGet("/api/v1/admin/audit/verify"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.verified").value(true));
    }

    @Test
    @Order(2)
    void jcsIdempotencyReplayReturnsIdenticalVoucherWithoutDoublePostingAndRejectsMutatedPayload() throws Exception {
        String idemKey = "idem-retry-vch-" + UUID.randomUUID();
        String originalPayload = """
            {
              "voucherTypeName": "Journal",
              "voucherDate": "2025-04-20",
              "narration": "Retry-safe JCS voucher",
              "lines": [
                {
                  "ledgerId": "%s",
                  "side": "DR",
                  "amount": "75000.00"
                },
                {
                  "ledgerId": "%s",
                  "side": "CR",
                  "amount": "75000.00"
                }
              ]
            }
            """.formatted(cashLedgerId, salesLedgerId);

        MvcResult firstRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                .header("Authorization", "Bearer " + serviceApiKeyBearer)
                .header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(originalPayload)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.header().string("ETag", "\"v1\""))
            .andReturn();

        JsonNode firstJson = objectMapper.readTree(firstRes.getResponse().getContentAsByteArray());
        String createdVoucherId = firstJson.get("id").asString();
        String createdVoucherNumber = firstJson.get("voucherNumber").asString();

        long entriesAfterFirst = countCompanyEntries();

        // Simulate client network drop + retry with reordered JSON keys & compact whitespace under SAME Idempotency-Key
        String reorderedSemanticEquivalent = """
            {"lines":[{"amount":"75000.00","side":"DR","ledgerId":"%s"},{"side":"CR","ledgerId":"%s","amount":"75000.00"}],"narration":"Retry-safe JCS voucher","voucherDate":"2025-04-20","voucherTypeName":"Journal"}
            """.formatted(cashLedgerId, salesLedgerId).trim();

        MvcResult replayRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                .header("Authorization", "Bearer " + serviceApiKeyBearer)
                .header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reorderedSemanticEquivalent)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.header().string("X-Idempotency-Replayed", "true"))
            .andReturn();

        JsonNode replayJson = objectMapper.readTree(replayRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(replayJson.get("id").asString()).isEqualTo(createdVoucherId);
        Assertions.assertThat(replayJson.get("voucherNumber").asString()).isEqualTo(createdVoucherNumber);
        Assertions.assertThat(countCompanyEntries()).isEqualTo(entriesAfterFirst);

        // Mutating payload amount with the same Idempotency-Key MUST fail closed with 409 MC-IDEMPOTENCY-PAYLOAD-MISMATCH
        String mutatedPayload = originalPayload.replace("75000.00", "75000.01");
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                .header("Authorization", "Bearer " + serviceApiKeyBearer)
                .header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mutatedPayload)
        )
            .andExpect(MockMvcResultMatchers.status().isConflict())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-IDEMPOTENCY-PAYLOAD-MISMATCH"));

        Assertions.assertThat(countCompanyEntries()).isEqualTo(entriesAfterFirst);
    }

    private void runConcurrentWave(String voucherTypeName, String wavePrefix) throws Exception {
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_THREADS);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>(CONCURRENT_THREADS);

        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            final int threadIdx = i;
            tasks.add(() -> {
                // Deliberately alternate ledger input order across even/odd threads to prove
                // canonical UUID lock sorting in VoucherService prevents AB/BA deadlocks!
                String linesJson = (threadIdx % 2 == 0)
                    ? """
                      [
                        { "ledgerId": "%s", "side": "DR", "amount": "1000.00" },
                        { "ledgerId": "%s", "side": "CR", "amount": "1000.00" }
                      ]
                      """.formatted(cashLedgerId, salesLedgerId)
                    : """
                      [
                        { "ledgerId": "%s", "side": "CR", "amount": "1000.00" },
                        { "ledgerId": "%s", "side": "DR", "amount": "1000.00" }
                      ]
                      """.formatted(salesLedgerId, partyLedgerId);

                String body = """
                    {
                      "voucherTypeName": "%s",
                      "voucherDate": "2025-04-15",
                      "persistedView": "AS_VOUCHER",
                      "narration": "Concurrent wave %s #%d",
                      "lines": %s
                    }
                    """.formatted(voucherTypeName, wavePrefix, threadIdx, linesJson);

                readyLatch.countDown();
                if (!startGun.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for concurrency start gun");
                }

                MvcResult res = mockMvc.perform(
                    MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                        .header("Authorization", "Bearer " + serviceApiKeyBearer)
                        .header("Idempotency-Key", wavePrefix + "-idem-" + threadIdx)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                )
                    .andReturn();

                int httpStatus = res.getResponse().getStatus();
                String respBody = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
                if (httpStatus != 201) {
                    throw new AssertionError(
                        "Thread " + threadIdx + " failed with HTTP " + httpStatus + ": " + respBody
                    );
                }
                JsonNode json = objectMapper.readTree(respBody);
                return json.get("voucherNumber").asString();
            });
        }

        List<Future<String>> futures = new ArrayList<>(CONCURRENT_THREADS);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Callable<String> task : tasks) {
                futures.add(executor.submit(task));
            }
            Assertions.assertThat(readyLatch.await(15, TimeUnit.SECONDS)).isTrue();
            startGun.countDown();

            Set<Integer> assignedNumbers = new TreeSet<>();
            for (Future<String> f : futures) {
                String vchNo = f.get(45, TimeUnit.SECONDS);
                assignedNumbers.add(Integer.parseInt(vchNo));
            }

            Assertions.assertThat(assignedNumbers).hasSize(CONCURRENT_THREADS);
            Assertions.assertThat(assignedNumbers.iterator().next()).isEqualTo(1);
            Assertions.assertThat(assignedNumbers.stream().max(Integer::compareTo).orElseThrow())
                .isEqualTo(CONCURRENT_THREADS);
        }
    }

    private long countCompanyEntries() {
        return TenantContextHolder.callWithCompany(companyId, () -> {
            Long cnt = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_entry WHERE company_id = ?",
                Long.class,
                companyId
            );
            return cnt == null ? 0L : cnt;
        });
    }

    private UUID findGroupIdByName(String groupName) throws Exception {
        String cursor = null;
        do {
            String url = "/api/v1/companies/" + companyId + "/account-groups?limit=50"
                + (cursor == null ? "" : "&cursor=" + cursor);
            MvcResult res = mockMvc.perform(authedGet(url))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
            JsonNode page = objectMapper.readTree(res.getResponse().getContentAsByteArray());
            for (JsonNode g : page.get("items")) {
                if (groupName.equals(g.get("name").asString())) {
                    return UUID.fromString(g.get("id").asString());
                }
            }
            cursor = page.get("hasMore").asBoolean() ? page.get("nextCursor").asString() : null;
        } while (cursor != null);
        throw new IllegalStateException("Group not found: " + groupName);
    }

    private UUID findLedgerIdByName(String ledgerName) throws Exception {
        MvcResult res = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyId + "/ledgers?q=" + ledgerName)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode items = objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("items");
        for (JsonNode l : items) {
            if (ledgerName.equals(l.get("name").asString())) {
                return UUID.fromString(l.get("id").asString());
            }
        }
        throw new IllegalStateException("Ledger not found: " + ledgerName);
    }

    private UUID createLedger(String name, UUID groupId) throws Exception {
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "groupId": "%s",
                          "maintainBalancesBillByBill": false,
                          "openingBalance": "0.00",
                          "openingBalanceSide": "DR"
                        }
                        """.formatted(name, groupId)
                    ),
                "conc-ldg-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID createTestCompany(
        String code,
        String legalName,
        String stateCode,
        String fyStart
    ) throws Exception {
        String gstinPrefix = stateCode + "AABCC1596D1Z";
        String gstin = gstinPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(gstinPrefix);
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "code": "%s",
                          "legalName": "%s",
                          "stateCode": "%s",
                          "countryCode": "IN",
                          "baseCurrencyCode": "INR",
                          "baseCurrencySymbol": "₹",
                          "formalCurrencyName": "Indian Rupee",
                          "currencyScale": 2,
                          "financialYearFrom": "%s",
                          "booksBeginningFrom": "%s",
                          "features": {
                            "maintainAccounts": true,
                            "billWiseEnabled": true,
                            "gstEnabled": true,
                            "gstRegistrationType": "REGULAR",
                            "gstin": "%s",
                            "mcaEditLogEnforced": true
                          }
                        }
                        """.formatted(code, legalName, stateCode, fyStart, fyStart, gstin)
                    ),
                "cmp-" + code
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        MvcResult loginRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"conc_admin\",\"password\":\"ConcPassword#2026!\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Cookie refreshedSession = latestCookie(loginRes, "MCSESSION");
        Cookie refreshedXsrf = latestCookie(loginRes, "XSRF-TOKEN");
        if (refreshedSession != null) {
            ownerSessionCookie = refreshedSession;
        }
        if (refreshedXsrf != null) {
            ownerXsrfCookie = refreshedXsrf;
        }
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private static MockHttpServletRequestBuilder authedGet(String uri) {
        return MockMvcRequestBuilders.get(uri).cookie(ownerSessionCookie, ownerXsrfCookie);
    }

    private static MockHttpServletRequestBuilder authedMutate(
        MockHttpServletRequestBuilder builder,
        String idempotencyKey
    ) {
        String xsrfVal = URLDecoder.decode(ownerXsrfCookie.getValue(), StandardCharsets.UTF_8);
        return builder
            .cookie(ownerSessionCookie, ownerXsrfCookie)
            .header("X-XSRF-TOKEN", xsrfVal)
            .header("Idempotency-Key", idempotencyKey);
    }

    private static Cookie latestCookie(MvcResult result, String name) {
        Cookie[] cookies = result.getResponse().getCookies();
        Cookie found = null;
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isEmpty()) {
                found = cookie;
            }
        }
        return found;
    }
}
