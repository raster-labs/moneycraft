package com.raster.moneycraft.accounting;

import com.raster.moneycraft.MoneyCraftApplication;
import com.raster.moneycraft.foundation.DualPostgres18TestcontainersSupport;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
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
 * End-to-end Phase 2 (Gate G2-J Part 1) verification against isolated Dual-PostgreSQL 18 clusters:
 * 28 predefined groups + CoA tree, Cash & Profit & Loss A/c, 24 voucher types, custom ledgers
 * with Mod-36 GSTIN, Tink AES256_SIV PAN & AES256_GCM bank details, RoundingMode.UNNECESSARY
 * scale guard, HS256 keyset pagination, sub-15ms typeahead, ETag/If-Match + MCA Edit Log diffs,
 * multi-currency forex rates, hierarchical Cost Centres cycle guard, tenant RLS isolation,
 * and MC-AUDIT-2 hash chain integrity (AT-ACC-001..004).
 */
@SpringBootTest(classes = MoneyCraftApplication.class)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountingMastersAndCoaTest {

    private static final DualPostgres18TestcontainersSupport.ClusterPair CLUSTERS =
        DualPostgres18TestcontainersSupport.startIsolatedClusterPair();

    private static Cookie ownerSessionCookie;
    private static Cookie ownerXsrfCookie;
    private static UUID companyAId;
    private static UUID companyBId;
    private static UUID sundryDebtorsGroupId;
    private static UUID sundryCreditorsGroupId;
    private static UUID bankAccountsGroupId;
    private static UUID cashLedgerId;
    private static UUID pnlLedgerId;

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

    @Autowired
    private TinkCryptoService tinkCryptoService;

    @Test
    @Order(1)
    void seededCompanyExposes28GroupsCashAndPnl24VoucherTypesInrAndPrimaryCostCategory() throws Exception {
        String initPayload = """
            {
              "installationLabel": "MoneyCraft Accounting Test Cluster",
              "ownerUsername": "acc_admin",
              "ownerDisplayName": "Chief Accountant",
              "password": "ChiefAccountant#2026!"
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

        companyAId = createTestCompany("KAV25", "Kaveri Enterprises Pvt Ltd", "33", "2025-04-01");
        companyBId = createTestCompany("BLR25", "Deccan Precision Tools Ltd", "29", "2025-04-01");

        MvcResult treeRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyAId + "/chart-of-accounts/tree")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();

        JsonNode tree = objectMapper.readTree(treeRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(tree.get("totalGroups").asInt()).isEqualTo(28);
        Assertions.assertThat(tree.get("totalLedgers").asInt()).isEqualTo(2);
        Assertions.assertThat(tree.get("totalOpeningDebit").asString()).isEqualTo("0.00");
        Assertions.assertThat(tree.get("totalOpeningCredit").asString()).isEqualTo("0.00");
        Assertions.assertThat(tree.get("openingDifference").asString()).isEqualTo("0.00");
        // 15 Primary Groups + 1 ungrouped Profit & Loss A/c root node
        Assertions.assertThat(tree.get("nodes").size()).isEqualTo(16);

        Set<String> groupNames = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            String url = "/api/v1/companies/" + companyAId + "/account-groups?limit=15"
                + (cursor == null ? "" : "&cursor=" + cursor);
            MvcResult grpRes = mockMvc.perform(authedGet(url))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
            JsonNode page = objectMapper.readTree(grpRes.getResponse().getContentAsByteArray());
            for (JsonNode g : page.get("items")) {
                String gName = g.get("name").asString();
                groupNames.add(gName);
                if ("Sundry Debtors".equals(gName)) {
                    sundryDebtorsGroupId = UUID.fromString(g.get("id").asString());
                } else if ("Sundry Creditors".equals(gName)) {
                    sundryCreditorsGroupId = UUID.fromString(g.get("id").asString());
                } else if ("Bank Accounts".equals(gName)) {
                    bankAccountsGroupId = UUID.fromString(g.get("id").asString());
                }
            }
            cursor = page.get("hasMore").asBoolean() ? page.get("nextCursor").asString() : null;
            pages++;
        } while (cursor != null);

        Assertions.assertThat(pages).isEqualTo(2);
        Assertions.assertThat(groupNames).hasSize(28);
        Assertions.assertThat(sundryDebtorsGroupId).isNotNull();
        Assertions.assertThat(sundryCreditorsGroupId).isNotNull();
        Assertions.assertThat(bankAccountsGroupId).isNotNull();

        MvcResult ledgersRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyAId + "/ledgers")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode ledgerItems = objectMapper.readTree(ledgersRes.getResponse().getContentAsByteArray()).get("items");
        Assertions.assertThat(ledgerItems.size()).isEqualTo(2);
        for (JsonNode l : ledgerItems) {
            if ("Cash".equals(l.get("name").asString())) {
                cashLedgerId = UUID.fromString(l.get("id").asString());
            } else if ("Profit & Loss A/c".equals(l.get("name").asString())) {
                pnlLedgerId = UUID.fromString(l.get("id").asString());
            }
        }
        Assertions.assertThat(cashLedgerId).isNotNull();
        Assertions.assertThat(pnlLedgerId).isNotNull();

        MvcResult vchTypesRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyAId + "/voucher-types")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode vchTypes = objectMapper.readTree(vchTypesRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(vchTypes.size()).isEqualTo(24);

        MvcResult ccyRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyAId + "/currencies")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode currencies = objectMapper.readTree(ccyRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(currencies.size()).isEqualTo(1);
        Assertions.assertThat(currencies.get(0).get("isoCode").asString()).isEqualTo("INR");
        Assertions.assertThat(currencies.get(0).get("isBaseCurrency").asBoolean()).isTrue();

        MvcResult costCatRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyAId + "/cost-categories")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode costCats = objectMapper.readTree(costCatRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(costCats.size()).isEqualTo(1);
        Assertions.assertThat(costCats.get(0).get("name").asString()).isEqualTo("Primary Cost Category");
        Assertions.assertThat(costCats.get(0).get("isPrimaryCategory").asBoolean()).isTrue();
    }

    @Test
    @Order(2)
    void systemGroupAndLedgerProtectionAndAccountGroupCycleGuardAndEditLog() throws Exception {
        MvcResult delSysGrp = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.delete(
                    "/api/v1/companies/" + companyAId + "/account-groups/" + sundryDebtorsGroupId
                ),
                "del-sys-group-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andReturn();
        Assertions.assertThat(readErrorCode(delSysGrp)).isEqualTo("MC-ACC-SYSTEM-GROUP-PROTECTED");

        MvcResult delCash = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.delete("/api/v1/companies/" + companyAId + "/ledgers/" + cashLedgerId),
                "del-cash-ledger-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andReturn();
        Assertions.assertThat(readErrorCode(delCash)).isEqualTo("MC-ACC-SYSTEM-LEDGER-PROTECTED");

        MvcResult delPnl = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.delete("/api/v1/companies/" + companyAId + "/ledgers/" + pnlLedgerId),
                "del-pnl-ledger-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andReturn();
        Assertions.assertThat(readErrorCode(delPnl)).isEqualTo("MC-ACC-SYSTEM-LEDGER-PROTECTED");

        // Create South Zone Debtors -> Chennai Retail Debtors and test parent cycle guard
        MvcResult southRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyAId + "/account-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "South Zone Debtors",
                          "parentGroupId": "%s",
                          "behavesLikeSubledger": true,
                          "defaultGstRatePct": "18"
                        }
                        """.formatted(sundryDebtorsGroupId)
                    ),
                "grp-south-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.header().string("ETag", "\"v1\""))
            .andReturn();
        UUID southGroupId = UUID.fromString(
            objectMapper.readTree(southRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        MvcResult chennaiRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyAId + "/account-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Chennai Retail Debtors",
                          "parentGroupId": "%s",
                          "behavesLikeSubledger": true
                        }
                        """.formatted(southGroupId)
                    ),
                "grp-chennai-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID chennaiGroupId = UUID.fromString(
            objectMapper.readTree(chennaiRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        // Attempt to make South Zone Debtors a child of its own child Chennai Retail Debtors
        MvcResult cycleRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put("/api/v1/companies/" + companyAId + "/account-groups/" + southGroupId)
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "South Zone Debtors",
                          "parentGroupId": "%s",
                          "behavesLikeSubledger": true
                        }
                        """.formatted(chennaiGroupId)
                    ),
                "grp-cycle-attempt-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andReturn();
        Assertions.assertThat(readErrorCode(cycleRes)).isEqualTo("MC-ACC-GROUP-CYCLE");

        // Alter Chennai Retail Debtors (v1 -> v2) and then delete it (v3)
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put("/api/v1/companies/" + companyAId + "/account-groups/" + chennaiGroupId)
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Chennai Institutional Debtors",
                          "aliasCode": "CHN-INST",
                          "parentGroupId": "%s",
                          "behavesLikeSubledger": true
                        }
                        """.formatted(southGroupId)
                    ),
                "grp-chennai-alter-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.header().string("ETag", "\"v2\""));

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.delete("/api/v1/companies/" + companyAId + "/account-groups/" + chennaiGroupId),
                "grp-chennai-delete-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isNoContent());

        Integer grpEditLogCount = TenantContextHolder.callWithCompany(companyAId, () ->
            businessJdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM mc_edit_log_version
                 WHERE company_id = ? AND entity_type = 'ACCOUNT_GROUP' AND entity_id = ?
                """,
                Integer.class,
                companyAId,
                chennaiGroupId
            )
        );
        Assertions.assertThat(grpEditLogCount).isEqualTo(3);
    }

    @Test
    @Order(3)
    void customLedgersGstinMod36TinkSivGcmScaleOverflowTypeaheadCursorAndRlsIsolation() throws Exception {
        // 1. Kaveri Meditech Pvt Ltd with valid Mod-36 GSTIN 33AAFCK5678G1ZC
        MvcResult kaveriRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyAId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Kaveri Meditech Pvt Ltd",
                          "aliasCode": "KAV-MED",
                          "groupId": "%s",
                          "openingBalance": "125000.50",
                          "openingSide": "DR",
                          "maintainBalancesBillByBill": true,
                          "defaultCreditPeriodDays": 30,
                          "checkCreditDaysDuringVoucher": true,
                          "creditLimit": "500000.00",
                          "partyRegistrationType": "REGULAR",
                          "countryCode": "IN",
                          "stateCode": "33",
                          "stateName": "Tamil Nadu",
                          "pincode": "600018",
                          "gstin": "33AAFCK5678G1ZC"
                        }
                        """.formatted(sundryDebtorsGroupId)
                    ),
                "ldg-kaveri-create-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.header().string("ETag", "\"v1\""))
            .andReturn();

        JsonNode kaveriJson = objectMapper.readTree(kaveriRes.getResponse().getContentAsByteArray());
        UUID kaveriId = UUID.fromString(kaveriJson.get("id").asString());
        Assertions.assertThat(kaveriJson.get("gstin").asString()).isEqualTo("33AAFCK5678G1ZC");
        Assertions.assertThat(kaveriJson.get("panMasked").asString()).isEqualTo("AAFCK****G");
        Assertions.assertThat(kaveriJson.get("openingBalance").asString()).isEqualTo("125000.50");

        // 2. Apex Legal Associates with Tink AES256_SIV PAN & 194J TDS
        MvcResult apexRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyAId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Apex Legal Associates",
                          "aliasCode": "APEX-LAW",
                          "groupId": "%s",
                          "openingBalance": "45000.00",
                          "openingSide": "CR",
                          "maintainBalancesBillByBill": true,
                          "pan": "AABFA4321K",
                          "tdsApplicable": true,
                          "tdsNatureCode": "194J",
                          "tdsDeducteeType": "PARTNERSHIP_FIRM"
                        }
                        """.formatted(sundryCreditorsGroupId)
                    ),
                "ldg-apex-create-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        JsonNode apexJson = objectMapper.readTree(apexRes.getResponse().getContentAsByteArray());
        UUID apexId = UUID.fromString(apexJson.get("id").asString());
        Assertions.assertThat(apexJson.get("panMasked").asString()).isEqualTo("AABFA****K");
        Assertions.assertThat(apexJson.get("tdsApplicable").asBoolean()).isTrue();
        Assertions.assertThat(apexJson.get("tdsNatureCode").asString()).isEqualTo("194J");

        byte[] storedPanSiv = TenantContextHolder.callWithCompany(companyAId, () ->
            businessJdbcTemplate.queryForObject(
                "SELECT pan_siv FROM mc_ledger WHERE company_id = ? AND id = ?",
                (rs, rowNum) -> rs.getBytes("pan_siv"),
                companyAId,
                apexId
            )
        );
        Assertions.assertThat(storedPanSiv).isNotNull();
        Assertions.assertThat(new String(storedPanSiv, StandardCharsets.UTF_8)).doesNotContain("AABFA4321K");
        Assertions.assertThat(
            tinkCryptoService.decryptDeterministicSiv(companyAId, "mc_ledger", "pan_siv", storedPanSiv)
        ).isEqualTo("AABFA4321K");

        // 3. HDFC Current A/c with Tink AES256_GCM bankDetails
        MvcResult hdfcRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyAId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "HDFC Current A/c",
                          "aliasCode": "HDFC-CA",
                          "groupId": "%s",
                          "openingBalance": "250000.00",
                          "openingSide": "DR",
                          "bankDetails": {
                            "accountHolder": "Kaveri Enterprises Pvt Ltd",
                            "accountNumber": "50200012345678",
                            "ifscCode": "HDFC0000123",
                            "bankName": "HDFC Bank",
                            "branch": "Anna Salai",
                            "upiId": "kaveri@hdfcbank"
                          }
                        }
                        """.formatted(bankAccountsGroupId)
                    ),
                "ldg-hdfc-create-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        JsonNode hdfcJson = objectMapper.readTree(hdfcRes.getResponse().getContentAsByteArray());
        UUID hdfcId = UUID.fromString(hdfcJson.get("id").asString());
        Assertions.assertThat(hdfcJson.get("bankDetails").get("accountNumber").asString())
            .isEqualTo("50200012345678");

        byte[] storedBankGcm = TenantContextHolder.callWithCompany(companyAId, () ->
            businessJdbcTemplate.queryForObject(
                "SELECT bank_details_gcm FROM mc_ledger WHERE company_id = ? AND id = ?",
                (rs, rowNum) -> rs.getBytes("bank_details_gcm"),
                companyAId,
                hdfcId
            )
        );
        Assertions.assertThat(storedBankGcm).isNotNull();
        Assertions.assertThat(new String(storedBankGcm, StandardCharsets.UTF_8)).doesNotContain("50200012345678");

        // 4. Reject excess fractional scale 100.123 -> 422 MC-ACC-SCALE-OVERFLOW
        MvcResult scaleErrRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyAId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Overflow Test Ledger",
                          "groupId": "%s",
                          "openingBalance": "100.123",
                          "openingSide": "DR"
                        }
                        """.formatted(sundryDebtorsGroupId)
                    ),
                "ldg-scale-overflow-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andReturn();
        Assertions.assertThat(readErrorCode(scaleErrRes)).isEqualTo("MC-ACC-SCALE-OVERFLOW");

        // 5. Reject invalid Mod-36 GSTIN -> 422 MC-TAX-INVALID-GSTIN
        MvcResult badGstinRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyAId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Bad GSTIN Party",
                          "groupId": "%s",
                          "gstin": "33AAFCK5678G1Z9"
                        }
                        """.formatted(sundryDebtorsGroupId)
                    ),
                "ldg-bad-gstin-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andReturn();
        Assertions.assertThat(readErrorCode(badGstinRes)).isEqualTo("MC-TAX-INVALID-GSTIN");

        // 6. Alter Kaveri Meditech Pvt Ltd (ETag v1 -> v2, Idempotency replay, stale If-Match 412, Edit Log diff)
        String alterBody = """
            {
              "name": "Kaveri Meditech Pvt Ltd",
              "aliasCode": "KAV-MED",
              "groupId": "%s",
              "openingBalance": "150000.75",
              "openingSide": "DR",
              "maintainBalancesBillByBill": true,
              "defaultCreditPeriodDays": 45,
              "checkCreditDaysDuringVoucher": true,
              "creditLimit": "600000.00",
              "partyRegistrationType": "REGULAR",
              "countryCode": "IN",
              "stateCode": "33",
              "stateName": "Tamil Nadu",
              "pincode": "600018",
              "gstin": "33AAFCK5678G1ZC"
            }
            """.formatted(sundryDebtorsGroupId);

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put("/api/v1/companies/" + companyAId + "/ledgers/" + kaveriId)
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(alterBody),
                "ldg-kaveri-alter-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.header().string("ETag", "\"v2\""));

        // Exact replay with same Idempotency-Key returns cached v2 receipt
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put("/api/v1/companies/" + companyAId + "/ledgers/" + kaveriId)
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(alterBody),
                "ldg-kaveri-alter-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.header().string("X-Idempotency-Replayed", "true"));

        // Stale If-Match with new Idempotency-Key fails with 412 MC-CONCURRENCY-STALE-VERSION
        MvcResult staleRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put("/api/v1/companies/" + companyAId + "/ledgers/" + kaveriId)
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(alterBody),
                "ldg-kaveri-stale-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isPreconditionFailed())
            .andReturn();
        Assertions.assertThat(readErrorCode(staleRes)).isEqualTo("MC-CONCURRENCY-STALE-VERSION");

        String editLogChangedFields = TenantContextHolder.callWithCompany(companyAId, () ->
            businessJdbcTemplate.queryForObject(
                """
                SELECT changed_fields::text
                  FROM mc_edit_log_version
                 WHERE company_id = ? AND entity_type = 'LEDGER' AND entity_id = ? AND version_no = 2
                """,
                String.class,
                companyAId,
                kaveriId
            )
        );
        Assertions.assertThat(editLogChangedFields)
            .contains("openingBalance")
            .contains("125000.50")
            .contains("150000.75");

        // 7. HS256 Keyset Pagination on GET /ledgers?limit=2
        Set<String> pagedLedgers = new HashSet<>();
        String ldgCursor = null;
        do {
            String url = "/api/v1/companies/" + companyAId + "/ledgers?limit=2"
                + (ldgCursor == null ? "" : "&cursor=" + ldgCursor);
            MvcResult pageRes = mockMvc.perform(authedGet(url))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
            JsonNode pageJson = objectMapper.readTree(pageRes.getResponse().getContentAsByteArray());
            for (JsonNode item : pageJson.get("items")) {
                pagedLedgers.add(item.get("name").asString());
            }
            ldgCursor = pageJson.get("hasMore").asBoolean() ? pageJson.get("nextCursor").asString() : null;
        } while (ldgCursor != null);
        Assertions.assertThat(pagedLedgers).containsExactlyInAnyOrder(
            "Cash",
            "Profit & Loss A/c",
            "Kaveri Meditech Pvt Ltd",
            "Apex Legal Associates",
            "HDFC Current A/c"
        );

        // 8. Sub-15ms Typeahead filtering (warm JIT/pool, then measure HTTP end-to-end)
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(
                authedGet("/api/v1/companies/" + companyAId + "/ledgers/typeahead?filter=PARTY&q=Kav")
            ).andExpect(MockMvcResultMatchers.status().isOk());
        }

        long bestMicros = Long.MAX_VALUE;
        MvcResult partyTypeahead = null;
        for (int i = 0; i < 3; i++) {
            long startNanos = System.nanoTime();
            partyTypeahead = mockMvc.perform(
                authedGet("/api/v1/companies/" + companyAId + "/ledgers/typeahead?filter=PARTY&q=Kav")
            )
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
            long sampleMicros = (System.nanoTime() - startNanos) / 1_000L;
            if (sampleMicros < bestMicros) {
                bestMicros = sampleMicros;
            }
        }
        Assertions.assertThat(bestMicros).isLessThan(15_000L);

        JsonNode partyItems = objectMapper.readTree(partyTypeahead.getResponse().getContentAsByteArray());
        Assertions.assertThat(partyItems.size()).isEqualTo(1);
        Assertions.assertThat(partyItems.get(0).get("name").asString()).isEqualTo("Kaveri Meditech Pvt Ltd");
        Assertions.assertThat(partyItems.get(0).get("currentBalance").asString()).isEqualTo("150000.75");
        Assertions.assertThat(partyItems.get(0).get("currentBalanceSide").asString()).isEqualTo("DR");

        MvcResult cashBankTypeahead = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyAId + "/ledgers/typeahead?filter=CASH_BANK")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode cashBankItems = objectMapper.readTree(cashBankTypeahead.getResponse().getContentAsByteArray());
        Assertions.assertThat(cashBankItems.size()).isEqualTo(2);

        // 9. Strict Tenant RLS Isolation: Company B cannot see Company A ledgers
        mockMvc.perform(
            authedGet("/api/v1/companies/" + companyBId + "/ledgers/" + kaveriId)
        )
            .andExpect(MockMvcResultMatchers.status().isNotFound());

        MvcResult companyBLedgers = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyBId + "/ledgers")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode compBItems = objectMapper.readTree(companyBLedgers.getResponse().getContentAsByteArray()).get("items");
        Assertions.assertThat(compBItems.size()).isEqualTo(2);
    }

    @Test
    @Order(4)
    void multiCurrencyForexRatesCustomVoucherSubTypesAndHierarchicalCostCentres() throws Exception {
        // 1. Create USD currency and date-effective forex rates (84.50, 85.20)
        MvcResult usdRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyAId + "/currencies")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "isoCode": "USD",
                          "symbol": "$",
                          "formalName": "US Dollar",
                          "decimalPlaces": 2,
                          "decimalSymbol": "cents"
                        }
                        """
                    ),
                "ccy-usd-create-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.header().string("ETag", "\"v1\""))
            .andReturn();
        UUID usdId = UUID.fromString(
            objectMapper.readTree(usdRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post(
                    "/api/v1/companies/" + companyAId + "/currencies/" + usdId + "/rates"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "effectiveDate": "2025-04-01",
                          "standardRate": "84.50",
                          "sellingRate": "84.80",
                          "buyingRate": "84.20"
                        }
                        """
                    ),
                "fx-usd-apr-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post(
                    "/api/v1/companies/" + companyAId + "/currencies/" + usdId + "/rates"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "effectiveDate": "2025-05-01",
                          "standardRate": "85.20",
                          "sellingRate": "85.50",
                          "buyingRate": "84.90"
                        }
                        """
                    ),
                "fx-usd-may-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        MvcResult ratesRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyAId + "/currencies/" + usdId + "/rates")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode rates = objectMapper.readTree(ratesRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(rates.size()).isEqualTo(2);
        Assertions.assertThat(rates.get(0).get("standardRate").asString()).isEqualTo("85.20");
        Assertions.assertThat(rates.get(1).get("standardRate").asString()).isEqualTo("84.50");

        // 2. Custom Voucher Sub-Type under Sales
        MvcResult exportSalesRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyAId + "/voucher-types")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Export Sales",
                          "abbreviation": "ExpSale",
                          "parentVoucherType": "Sales",
                          "approvalPolicy": "FOUR_EYES",
                          "printAfterSave": true
                        }
                        """
                    ),
                "vt-export-sales-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        JsonNode exportSalesJson = objectMapper.readTree(exportSalesRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(exportSalesJson.get("category").asString()).isEqualTo("SALES");
        Assertions.assertThat(exportSalesJson.get("parentVoucherType").asString()).isEqualTo("Sales");
        Assertions.assertThat(exportSalesJson.get("movesInventory").asBoolean()).isTrue();
        Assertions.assertThat(exportSalesJson.get("approvalPolicy").asString()).isEqualTo("FOUR_EYES");

        // 3. Cost Category & Hierarchical Cost Centres + Cycle Guard
        MvcResult catRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyAId + "/cost-categories")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Branch Profitability",
                          "allocateRevenueItems": true,
                          "allocateNonRevenueItems": false
                        }
                        """
                    ),
                "ccat-branch-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID branchCatId = UUID.fromString(
            objectMapper.readTree(catRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        MvcResult southCcRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyAId + "/cost-centres")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "categoryId": "%s",
                          "name": "South Region",
                          "aliasCode": "CC-SOUTH"
                        }
                        """.formatted(branchCatId)
                    ),
                "cc-south-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID southCentreId = UUID.fromString(
            objectMapper.readTree(southCcRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        MvcResult chennaiCcRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyAId + "/cost-centres")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "categoryId": "%s",
                          "parentCostCentreId": "%s",
                          "name": "Chennai HQ",
                          "aliasCode": "CC-MAA"
                        }
                        """.formatted(branchCatId, southCentreId)
                    ),
                "cc-chennai-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID chennaiCentreId = UUID.fromString(
            objectMapper.readTree(chennaiCcRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        // Attempt cycle: South Region -> parent Chennai HQ -> 422 MC-ACC-COST-CENTRE-CYCLE
        MvcResult ccCycleRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put("/api/v1/companies/" + companyAId + "/cost-centres/" + southCentreId)
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "categoryId": "%s",
                          "parentCostCentreId": "%s",
                          "name": "South Region"
                        }
                        """.formatted(branchCatId, chennaiCentreId)
                    ),
                "cc-cycle-attempt-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andReturn();
        Assertions.assertThat(readErrorCode(ccCycleRes)).isEqualTo("MC-ACC-COST-CENTRE-CYCLE");

        // Delete leaf Cost Centre Chennai HQ
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.delete("/api/v1/companies/" + companyAId + "/cost-centres/" + chennaiCentreId),
                "cc-chennai-del-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isNoContent());

        // Verify MC-AUDIT-2 cryptographic hash chain across all streams
        MvcResult auditVerifyRes = mockMvc.perform(authedGet("/api/v1/admin/audit/verify"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode auditReport = objectMapper.readTree(auditVerifyRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(auditReport.get("verified").asBoolean()).isTrue();
        Assertions.assertThat(auditReport.get("checkedEventCount").asLong()).isGreaterThanOrEqualTo(12L);
    }

    private UUID createTestCompany(String code, String legalName, String stateCode, String fyFrom) throws Exception {
        String payload = """
            {
              "code": "%s",
              "legalName": "%s",
              "countryCode": "IN",
              "stateCode": "%s",
              "baseCurrencyCode": "INR",
              "currencyScale": 2,
              "financialYearFrom": "%s",
              "booksBeginningFrom": "%s",
              "features": {
                "billWiseEnabled": true,
                "costCentresEnabled": true,
                "maintainInventory": true,
                "inventoryIntegrated": true,
                "gstEnabled": false,
                "tdsEnabled": true,
                "mcaEditLogEnforced": true
              }
            }
            """.formatted(code, legalName, stateCode, fyFrom, fyFrom);

        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
                "create-co-" + code
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        // Refresh session principal grants after new company creation
        MvcResult loginRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"acc_admin\",\"password\":\"ChiefAccountant#2026!\"}")
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

    private String readErrorCode(MvcResult result) throws Exception {
        JsonNode pd = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return pd.get("code").asString();
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
