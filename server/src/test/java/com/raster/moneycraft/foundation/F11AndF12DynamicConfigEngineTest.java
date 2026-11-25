package com.raster.moneycraft.foundation;

import com.raster.moneycraft.MoneyCraftApplication;
import com.raster.moneycraft.foundation.company.CompanyDtos;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * PostgreSQL 18 Testcontainers verification for CHG-DB-001, CHG-BE-001, CHG-BE-002, and CHG-BE-003:
 * - Business V010 Flyway migration & all 33 TallyPrime 7.2 F11 Company Features
 * - Dependent F11 feature normalization invariants & exact BigDecimal e-Way Bill threshold validation
 * - Context-sensitive F12 Configuration Engine (GET/PUT /api/v1/companies/{companyId}/f12-config + MC-AUDIT-2)
 * - Ctrl+Alt+U Unused / Cancelled Voucher Numbers endpoint (GET /api/v1/companies/{companyId}/vouchers/unused-numbers)
 */
@SpringBootTest(classes = MoneyCraftApplication.class)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class F11AndF12DynamicConfigEngineTest {

    private static final String OWNER_PASS = "OwnerF11F12#2026!Secure";
    private static final String GSTIN_TN =
        "33AABCM1234F1Z" + ExactMoneyValidator.computeGstinMod36CheckChar("33AABCM1234F1Z");

    private static DualPostgres18TestcontainersSupport.ClusterPair isolatedCluster;
    private static Cookie ownerSession;
    private static Cookie ownerXsrf;
    private static UUID companyId;
    private static String companyEtag;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("businessJdbcTemplate")
    private JdbcTemplate businessJdbcTemplate;

    private static synchronized void ensureClusterStarted() {
        if (isolatedCluster == null) {
            isolatedCluster = DualPostgres18TestcontainersSupport.startIsolatedClusterPair();
        }
    }

    @AfterAll
    static void stopCluster() {
        if (isolatedCluster != null) {
            isolatedCluster.close();
            isolatedCluster = null;
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        ensureClusterStarted();
        DualPostgres18TestcontainersSupport.bindClusterProperties(registry, isolatedCluster);
        registry.add("moneycraft.security.initial-super-admin-username", () -> "owner");
    }

    @Test
    @Order(1)
    void bootstrapAndCreateCompanyWithAll33F11FlagsAndVerifyBackwardCompatibleConstructor() throws Exception {
        // Verify 13-arg backward-compatible overload constructor defaults
        CompanyDtos.CompanyFeaturesDto compatDto = new CompanyDtos.CompanyFeaturesDto(
            true, true, false, true, true, true, false,
            "REGULAR", GSTIN_TN, "MONTHLY", LocalDate.of(2025, 4, 1), false, false
        );
        Assertions.assertThat(compatDto.interestCalculationEnabled()).isFalse();
        Assertions.assertThat(compatDto.multiplePriceLevelsEnabled()).isFalse();
        Assertions.assertThat(compatDto.batchesEnabled()).isTrue();
        Assertions.assertThat(compatDto.expiryDateForBatchesEnabled()).isTrue();
        Assertions.assertThat(compatDto.useDiscountColumnInInvoices()).isTrue();
        Assertions.assertThat(compatDto.useSeparateActualAndBilledQty()).isTrue();
        Assertions.assertThat(compatDto.eWayBillApplicable()).isTrue();
        Assertions.assertThat(compatDto.eWayBillThresholdLimit()).isEqualTo("50000.00");
        Assertions.assertThat(compatDto.tcsEnabled()).isFalse();
        Assertions.assertThat(compatDto.payrollEnabled()).isFalse();

        // Confirm V010 Flyway migration applied on Business DB
        Integer bizMigrations = businessJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version = '010'",
            Integer.class
        );
        Assertions.assertThat(bizMigrations).isEqualTo(1);

        MvcResult initRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/system/initialise")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "installationLabel", "MoneyCraft v2.0 F11/F12 Test Cluster",
                    "ownerUsername", "owner",
                    "ownerDisplayName", "Installation Owner",
                    "password", OWNER_PASS
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        ownerSession = initRes.getResponse().getCookie("MCSESSION");
        ownerXsrf = initRes.getResponse().getCookie("XSRF-TOKEN");

        Map<String, Object> featuresMap = new LinkedHashMap<>();
        featuresMap.put("maintainAccounts", true);
        featuresMap.put("billWiseEnabled", true);
        featuresMap.put("costCentresEnabled", true);
        featuresMap.put("interestCalculationEnabled", true);
        featuresMap.put("maintainInventory", true);
        featuresMap.put("inventoryIntegrated", true);
        featuresMap.put("multiplePriceLevelsEnabled", true);
        featuresMap.put("batchesEnabled", true);
        featuresMap.put("expiryDateForBatchesEnabled", true);
        featuresMap.put("jobOrderProcessingEnabled", true);
        featuresMap.put("costTrackingEnabled", true);
        featuresMap.put("jobCostingEnabled", true);
        featuresMap.put("useDiscountColumnInInvoices", false);
        featuresMap.put("useSeparateActualAndBilledQty", false);
        featuresMap.put("gstEnabled", true);
        featuresMap.put("multiGstinEnabled", true);
        featuresMap.put("gstRegistrationType", "REGULAR");
        featuresMap.put("gstin", GSTIN_TN);
        featuresMap.put("gstr1Periodicity", "MONTHLY");
        featuresMap.put("gstEffectiveFrom", "2025-04-01");
        featuresMap.put("eInvoiceApplicable", true);
        featuresMap.put("eWayBillApplicable", true);
        featuresMap.put("eWayBillThresholdLimit", "100000.00");
        featuresMap.put("tdsEnabled", true);
        featuresMap.put("tcsEnabled", true);
        featuresMap.put("vatEnabled", true);
        featuresMap.put("exciseEnabled", true);
        featuresMap.put("serviceTaxEnabled", true);
        featuresMap.put("payrollEnabled", true);
        featuresMap.put("payrollStatutoryEnabled", true);
        featuresMap.put("multipleAddressesEnabled", true);
        featuresMap.put("markModifiedVouchers", true);
        featuresMap.put("mcaEditLogEnforced", false);

        Map<String, Object> createReq = new LinkedHashMap<>();
        createReq.put("code", "F11FULL01");
        createReq.put("legalName", "Apex Full Parity Enterprises Pvt Ltd");
        createReq.put("countryCode", "IN");
        createReq.put("stateCode", "33");
        createReq.put("pinCode", "600001");
        createReq.put("baseCurrencyCode", "INR");
        createReq.put("currencySymbol", "₹");
        createReq.put("currencyScale", 2);
        createReq.put("financialYearFrom", "2025-04-01");
        createReq.put("booksBeginningFrom", "2025-04-01");
        createReq.put("features", featuresMap);

        MvcResult createRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createReq))
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.maintainAccounts").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.interestCalculationEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.multiplePriceLevelsEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.batchesEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.expiryDateForBatchesEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.jobOrderProcessingEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.costTrackingEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.jobCostingEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.useDiscountColumnInInvoices").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.useSeparateActualAndBilledQty").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.eInvoiceApplicable").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.eWayBillApplicable").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.eWayBillThresholdLimit").value("100000.00"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.tcsEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.vatEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.exciseEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.serviceTaxEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.payrollEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.payrollStatutoryEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.multipleAddressesEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.markModifiedVouchers").value(true))
            .andReturn();

        JsonNode createdNode = objectMapper.readTree(createRes.getResponse().getContentAsByteArray());
        companyId = UUID.fromString(createdNode.get("id").asString());
        companyEtag = createRes.getResponse().getHeader(HttpHeaders.ETAG);

        // Verify GET /api/v1/companies/{companyId} reads all 33 flags from mc_company
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/companies/" + companyId)
                .cookie(ownerSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.interestCalculationEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.multiplePriceLevelsEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.useDiscountColumnInInvoices").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.useSeparateActualAndBilledQty").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.eWayBillThresholdLimit").value("100000.00"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.tcsEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.payrollEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.payrollStatutoryEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.multipleAddressesEnabled").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.markModifiedVouchers").value(true));
    }

    @Test
    @Order(2)
    void dependentF11FlagNormalizationAndExactDecimalThresholdValidation() throws Exception {
        // 1. Update company disabling batchesEnabled, costCentresEnabled, and payrollEnabled while leaving
        //    expiryDateForBatchesEnabled=true, jobCostingEnabled=true, payrollStatutoryEnabled=true -> auto-normalized to false
        Map<String, Object> featuresStep1 = new LinkedHashMap<>();
        featuresStep1.put("maintainAccounts", true);
        featuresStep1.put("billWiseEnabled", true);
        featuresStep1.put("costCentresEnabled", false);
        featuresStep1.put("maintainInventory", true);
        featuresStep1.put("inventoryIntegrated", true);
        featuresStep1.put("batchesEnabled", false);
        featuresStep1.put("expiryDateForBatchesEnabled", true);
        featuresStep1.put("jobCostingEnabled", true);
        featuresStep1.put("payrollEnabled", false);
        featuresStep1.put("payrollStatutoryEnabled", true);
        featuresStep1.put("gstEnabled", true);
        featuresStep1.put("gstin", GSTIN_TN);
        featuresStep1.put("eWayBillThresholdLimit", "50000");

        Map<String, Object> updateReq1 = new LinkedHashMap<>();
        updateReq1.put("code", "F11FULL01");
        updateReq1.put("legalName", "Apex Full Parity Enterprises Pvt Ltd");
        updateReq1.put("countryCode", "IN");
        updateReq1.put("stateCode", "33");
        updateReq1.put("financialYearFrom", "2025-04-01");
        updateReq1.put("booksBeginningFrom", "2025-04-01");
        updateReq1.put("features", featuresStep1);

        MvcResult res1 = mockMvc.perform(
            MockMvcRequestBuilders.put("/api/v1/companies/" + companyId)
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, companyEtag)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateReq1))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.batchesEnabled").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.expiryDateForBatchesEnabled").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.costCentresEnabled").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.jobCostingEnabled").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.payrollEnabled").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.payrollStatutoryEnabled").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.eWayBillThresholdLimit").value("50000.00"))
            .andReturn();
        companyEtag = res1.getResponse().getHeader(HttpHeaders.ETAG);

        // 2. Disabling maintainInventory disables inventoryIntegrated, multiplePriceLevelsEnabled, batchesEnabled,
        //    expiryDateForBatchesEnabled, jobOrderProcessingEnabled, and costTrackingEnabled
        Map<String, Object> featuresStep2 = new LinkedHashMap<>();
        featuresStep2.put("maintainAccounts", true);
        featuresStep2.put("maintainInventory", false);
        featuresStep2.put("inventoryIntegrated", true);
        featuresStep2.put("multiplePriceLevelsEnabled", true);
        featuresStep2.put("batchesEnabled", true);
        featuresStep2.put("expiryDateForBatchesEnabled", true);
        featuresStep2.put("jobOrderProcessingEnabled", true);
        featuresStep2.put("costTrackingEnabled", true);
        featuresStep2.put("gstEnabled", true);
        featuresStep2.put("gstin", GSTIN_TN);

        updateReq1.put("features", featuresStep2);

        MvcResult res2 = mockMvc.perform(
            MockMvcRequestBuilders.put("/api/v1/companies/" + companyId)
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, companyEtag)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateReq1))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.maintainInventory").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.inventoryIntegrated").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.multiplePriceLevelsEnabled").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.batchesEnabled").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.expiryDateForBatchesEnabled").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.jobOrderProcessingEnabled").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.costTrackingEnabled").value(false))
            .andReturn();
        companyEtag = res2.getResponse().getHeader(HttpHeaders.ETAG);

        // 3. Disabling maintainAccounts persists maintainAccounts=false and disables billWiseEnabled & inventoryIntegrated
        Map<String, Object> featuresStep3 = new LinkedHashMap<>();
        featuresStep3.put("maintainAccounts", false);
        featuresStep3.put("billWiseEnabled", true);
        featuresStep3.put("maintainInventory", true);
        featuresStep3.put("inventoryIntegrated", true);
        featuresStep3.put("gstEnabled", true);
        featuresStep3.put("gstin", GSTIN_TN);

        updateReq1.put("features", featuresStep3);

        MvcResult res3 = mockMvc.perform(
            MockMvcRequestBuilders.put("/api/v1/companies/" + companyId)
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, companyEtag)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateReq1))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.maintainAccounts").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.billWiseEnabled").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.features.inventoryIntegrated").value(false))
            .andReturn();
        companyEtag = res3.getResponse().getHeader(HttpHeaders.ETAG);

        // Re-enable maintainAccounts & maintainInventory for subsequent tests
        featuresStep3.put("maintainAccounts", true);
        featuresStep3.put("billWiseEnabled", true);
        featuresStep3.put("maintainInventory", true);
        featuresStep3.put("inventoryIntegrated", true);
        MvcResult res4 = mockMvc.perform(
            MockMvcRequestBuilders.put("/api/v1/companies/" + companyId)
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, companyEtag)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateReq1))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        companyEtag = res4.getResponse().getHeader(HttpHeaders.ETAG);

        // 4. Reject excess scale (> 6 fractional digits) on eWayBillThresholdLimit with 422 MC-ACC-SCALE-OVERFLOW
        featuresStep3.put("eWayBillThresholdLimit", "50000.1234567");
        mockMvc.perform(
            MockMvcRequestBuilders.put("/api/v1/companies/" + companyId)
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header(HttpHeaders.IF_MATCH, companyEtag)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateReq1))
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-ACC-SCALE-OVERFLOW"));
    }

    @Test
    @Order(3)
    void f12ConfigurationEngineGetAndPutAndAuditChainIntegrity() throws Exception {
        // 1. Fresh company returns TallyPrime 7.2 defaults
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/companies/" + companyId + "/f12-config")
                .cookie(ownerSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.useCrDrInsteadOfToBy").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.provideReferenceNoAndDate").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.warnOnNegativeCash").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.provideLineNarration").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.consolidateStockItemsWithSameRate").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.showOpeningBalances").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.showClosingBalances").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.reportFormat").value("CONDENSED"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.scaleFactor").value("DEFAULT"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.sortingMethod").value("DEFAULT"));

        // 2. Update F12 configuration via PUT /api/v1/companies/{companyId}/f12-config
        CompanyDtos.CompanyF12ConfigDto customF12 = new CompanyDtos.CompanyF12ConfigDto(
            false, true, true, true, false, true, true, true, false, true,
            true, true, true, true, true,
            true, true, true, true, true, true, true, true,
            true, true, false, true, true, "detailed", "lakhs", "amount_desc"
        );

        mockMvc.perform(
            MockMvcRequestBuilders.put("/api/v1/companies/" + companyId + "/f12-config")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(customF12))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.useCrDrInsteadOfToBy").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.warnOnNegativeStock").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.useSingleEntryForPaymentReceiptContra").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.provideLineNarration").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.consolidateStockItemsWithSameRate").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.billWiseForNonTradingAccounts").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.showOpeningBalances").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.showTransactions").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.showPercentages").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.reportFormat").value("DETAILED"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.scaleFactor").value("LAKHS"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.sortingMethod").value("AMOUNT_DESC"));

        // 3. Re-read GET /api/v1/companies/{companyId}/f12-config to confirm persistence in mc_company.f12_config
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/companies/" + companyId + "/f12-config")
                .cookie(ownerSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.useCrDrInsteadOfToBy").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.provideLineNarration").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.reportFormat").value("DETAILED"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.scaleFactor").value("LAKHS"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.sortingMethod").value("AMOUNT_DESC"));

        // 4. Verify MC-AUDIT-2 event COMPANY_F12_CONFIG_UPDATE was recorded and hash chain is intact
        Integer auditCount = TenantContextHolder.callWithSystemBypass("check-f12-audit", () ->
            businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_audit_event WHERE company_id = ? AND operation = 'COMPANY_F12_CONFIG_UPDATE'",
                Integer.class,
                companyId
            )
        );
        Assertions.assertThat(auditCount).isEqualTo(1);

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/admin/audit/verify")
                .cookie(ownerSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.verified").value(true));
    }

    @Test
    @Order(4)
    void unusedAndCancelledVoucherNumbersEndpointForCtrlAltU() throws Exception {
        // 1. Find Cash and Sales ledgers (or create a Sales ledger under Sales Accounts)
        MvcResult ledgersRes = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/companies/" + companyId + "/ledgers/typeahead")
                .param("q", "")
                .param("filter", "ALL")
                .cookie(ownerSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode ledgersNode = objectMapper.readTree(ledgersRes.getResponse().getContentAsByteArray());
        UUID cashLedgerId = null;
        for (JsonNode l : ledgersNode) {
            if ("Cash".equalsIgnoreCase(l.get("name").asString())) {
                cashLedgerId = UUID.fromString(l.get("id").asString());
                break;
            }
        }
        Assertions.assertThat(cashLedgerId).isNotNull();

        // Find Indirect Incomes or Sales Accounts group to create a revenue ledger
        MvcResult groupsRes = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/companies/" + companyId + "/account-groups")
                .param("q", "Sales Accounts")
                .cookie(ownerSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        UUID salesGroupId = UUID.fromString(
            objectMapper.readTree(groupsRes.getResponse().getContentAsByteArray())
                .get("items").get(0).get("id").asString()
        );

        MvcResult salesLedgerRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/ledgers")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "Domestic Sales A/c",
                    "groupId", salesGroupId.toString(),
                    "openingBalance", "0.00",
                    "openingSide", "CR",
                    "gstApplicability", "NOT_APPLICABLE"
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID salesLedgerId = UUID.fromString(
            objectMapper.readTree(salesLedgerRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        // 2. Create two Sales vouchers (#1 and #2) and cancel voucher #2 via Alt+X (/cancel)
        for (int i = 1; i <= 2; i++) {
            Map<String, Object> vchReq = new LinkedHashMap<>();
            vchReq.put("voucherTypeName", "Sales");
            vchReq.put("voucherDate", "2025-04-10");
            vchReq.put("persistedView", "AS_VOUCHER");
            vchReq.put("postingNature", "NORMAL");
            vchReq.put("action", "POST");
            vchReq.put("narration", "Test Sales Voucher #" + i);
            vchReq.put("lines", List.of(
                Map.of("lineNo", 1, "lineRole", "LEDGER", "ledgerId", cashLedgerId.toString(), "side", "DR", "amount", "1500.00"),
                Map.of("lineNo", 2, "lineRole", "LEDGER", "ledgerId", salesLedgerId.toString(), "side", "CR", "amount", "1500.00")
            ));

            MvcResult vchRes = mockMvc.perform(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .cookie(ownerSession, ownerXsrf)
                    .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(vchReq))
            )
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn();

            if (i == 2) {
                JsonNode v2 = objectMapper.readTree(vchRes.getResponse().getContentAsByteArray());
                UUID v2Id = UUID.fromString(v2.get("id").asString());
                String v2Etag = vchRes.getResponse().getHeader(HttpHeaders.ETAG);

                mockMvc.perform(
                    MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers/" + v2Id + "/cancel")
                        .cookie(ownerSession, ownerXsrf)
                        .header("X-XSRF-TOKEN", ownerXsrf.getValue())
                        .header(HttpHeaders.IF_MATCH, v2Etag)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "Customer cancelled order")))
                )
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("CANCELLED"));
            }
        }

        // Also seed an AVAILABLE unused voucher number #1 in the same Sales series to test both AVAILABLE and CANCELLED_FOR_GSTR1
        UUID salesVoucherTypeId = TenantContextHolder.callWithSystemBypass("lookup-sales-vt", () -> {
            UUID vtId = businessJdbcTemplate.queryForObject(
                "SELECT id FROM mc_voucher_type WHERE company_id = ? AND lower(name) = 'sales'",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                companyId
            );
            UUID seriesId = businessJdbcTemplate.queryForObject(
                "SELECT id FROM mc_number_series WHERE company_id = ? AND voucher_type_id = ? LIMIT 1",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                companyId,
                vtId
            );
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_unused_voucher_number (
                    id, company_id, number_series_id, voucher_type_id,
                    voucher_number, numeric_seq, released_reason, status
                ) VALUES (?, ?, ?, ?, '1', 1, 'Deleted in AUTOMATIC_RETAIN_NUMBERS mode', 'AVAILABLE')
                """,
                UUID.randomUUID(),
                companyId,
                seriesId,
                vtId
            );
            return vtId;
        });

        // 3. Query GET /api/v1/companies/{companyId}/vouchers/unused-numbers by voucherTypeName=Sales
        MvcResult unusedByName = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/companies/" + companyId + "/vouchers/unused-numbers")
                .param("voucherTypeName", "Sales")
                .cookie(ownerSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.length()").value(2))
            .andExpect(MockMvcResultMatchers.jsonPath("$[0].voucherNumber").value("1"))
            .andExpect(MockMvcResultMatchers.jsonPath("$[0].numericSeq").value(1))
            .andExpect(MockMvcResultMatchers.jsonPath("$[0].status").value("AVAILABLE"))
            .andExpect(MockMvcResultMatchers.jsonPath("$[1].voucherNumber").value("2"))
            .andExpect(MockMvcResultMatchers.jsonPath("$[1].numericSeq").value(2))
            .andExpect(MockMvcResultMatchers.jsonPath("$[1].status").value("CANCELLED_FOR_GSTR1"))
            .andExpect(MockMvcResultMatchers.jsonPath("$[1].releasedReason").value("Customer cancelled order"))
            .andReturn();

        Assertions.assertThat(unusedByName.getResponse().getContentAsString()).contains(salesVoucherTypeId.toString());

        // 4. Query GET /api/v1/companies/{companyId}/vouchers/unused-numbers by voucherTypeId
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/companies/" + companyId + "/vouchers/unused-numbers")
                .param("voucherTypeId", salesVoucherTypeId.toString())
                .cookie(ownerSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.length()").value(2));
    }
}
