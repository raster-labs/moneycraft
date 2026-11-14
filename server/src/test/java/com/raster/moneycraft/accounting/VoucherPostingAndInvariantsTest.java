package com.raster.moneycraft.accounting;

import com.raster.moneycraft.MoneyCraftApplication;
import com.raster.moneycraft.foundation.DualPostgres18TestcontainersSupport;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end Phase 3 (Gate G2-J Part 2) verification against isolated Dual-PostgreSQL 18 clusters:
 * raw PG18 deferred double-entry & immutability triggers, Contra/Payment/Receipt/Journal/Sales/Purchase,
 * Single-Entry & Double-Entry views, Optional (Ctrl+L) & Memorandum 0-GL isolation, FOUR_EYES governance,
 * PUT /vouchers/{id} compensating batch alteration + MCA Edit Log v1->v2 redline diffs, Alt+X Cancel
 * preserving voucher number for GSTR-1 Table 13, HS256 Day Book keyset pagination, Bill-Wise lifecycle,
 * and Multi-Currency Unadjusted Forex Gain/Loss (Golden Vector A) (AT-ACC-005..014).
 */
@SpringBootTest(classes = MoneyCraftApplication.class)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VoucherPostingAndInvariantsTest {

    private static final DualPostgres18TestcontainersSupport.ClusterPair CLUSTERS =
        DualPostgres18TestcontainersSupport.startIsolatedClusterPair();

    private static Cookie ownerSessionCookie;
    private static Cookie ownerXsrfCookie;
    private static UUID ownerPrincipalId;
    private static UUID companyId;
    private static UUID openAprilPeriodId;
    private static UUID march2026PeriodId;
    private static UUID fiscalYearId;
    private static UUID journalVoucherTypeId;

    private static UUID cashLedgerId;
    private static UUID hdfcBankLedgerId;
    private static UUID domesticSalesLedgerId;
    private static UUID domesticPurchaseLedgerId;
    private static UUID officeRentLedgerId;
    private static UUID muruganDebtorLedgerId;
    private static UUID globalTechUsdDebtorId;
    private static UUID chennaiBranchCentreId;
    private static UUID journal1Id;

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
    @Qualifier("transactionManager")
    private PlatformTransactionManager businessTxManager;

    @Autowired
    @Qualifier("businessFlyway")
    private Flyway businessFlyway;

    @Test
    @Order(1)
    void postgresql18DoubleEntryScalePeriodAndImmutabilityTriggersFailClosed() throws Exception {
        MvcResult initRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/system/initialise")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "installationLabel": "MoneyCraft Phase 3 Voucher Engine Cluster",
                      "ownerUsername": "vch_admin",
                      "ownerDisplayName": "Chief Voucher Controller",
                      "password": "ChiefVoucher#2026!"
                    }
                    """
                )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        ownerSessionCookie = latestCookie(initRes, "MCSESSION");
        ownerXsrfCookie = latestCookie(initRes, "XSRF-TOKEN");

        MvcResult meRes = mockMvc.perform(authedGet("/api/v1/auth/me"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        ownerPrincipalId = UUID.fromString(
            objectMapper.readTree(meRes.getResponse().getContentAsByteArray()).get("principalId").asString()
        );

        companyId = createTestCompany("VCH25", "Cauvery Precision Works Pvt Ltd", "33", "2025-04-01");

        // Resolve seeded groups, Cash ledger, Primary Cost Category, and periods
        UUID bankGroupId = findGroupIdByName("Bank Accounts");
        UUID salesGroupId = findGroupIdByName("Sales Accounts");
        UUID purchaseGroupId = findGroupIdByName("Purchase Accounts");
        UUID indirectExpGroupId = findGroupIdByName("Indirect Expenses");
        UUID sundryDebtorsGroupId = findGroupIdByName("Sundry Debtors");

        cashLedgerId = findLedgerIdByName("Cash");
        hdfcBankLedgerId = createLedger("HDFC Bank Current A/c", bankGroupId, false, false, null);
        domesticSalesLedgerId = createLedger("Domestic Sales", salesGroupId, false, false, null);
        domesticPurchaseLedgerId = createLedger("Domestic Purchases", purchaseGroupId, false, false, null);
        officeRentLedgerId = createLedger("Office Rent", indirectExpGroupId, false, true, null);
        muruganDebtorLedgerId = createLedger("Sri Murugan Traders", sundryDebtorsGroupId, true, false, 30);
        globalTechUsdDebtorId = createLedger("Global Tech Inc (USD)", sundryDebtorsGroupId, true, false, 15);

        // Create USD currency and Cost Centre under Primary Cost Category
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/currencies")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "isoCode": "USD",
                          "symbol": "$",
                          "formalName": "US Dollar",
                          "decimalPlaces": 2
                        }
                        """
                    ),
                "vch-ccy-usd-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        MvcResult catRes = mockMvc.perform(authedGet("/api/v1/companies/" + companyId + "/cost-categories"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        UUID primaryCatId = UUID.fromString(
            objectMapper.readTree(catRes.getResponse().getContentAsByteArray()).get(0).get("id").asString()
        );

        MvcResult ccRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/cost-centres")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "categoryId": "%s",
                          "name": "Chennai Branch",
                          "aliasCode": "CC-MAA"
                        }
                        """.formatted(primaryCatId)
                    ),
                "vch-cc-maa-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        chennaiBranchCentreId = UUID.fromString(
            objectMapper.readTree(ccRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        MvcResult periodsRes = mockMvc.perform(authedGet("/api/v1/companies/" + companyId + "/periods"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode periods = objectMapper.readTree(periodsRes.getResponse().getContentAsByteArray());
        openAprilPeriodId = UUID.fromString(periods.get(0).get("id").asString());
        fiscalYearId = UUID.fromString(periods.get(0).get("fiscalYearId").asString());
        march2026PeriodId = UUID.fromString(periods.get(11).get("id").asString());

        TenantContextHolder.runWithCompany(companyId, () -> {
            journalVoucherTypeId = businessJdbcTemplate.queryForObject(
                "SELECT id FROM mc_voucher_type WHERE company_id = ? AND name = 'Journal'",
                UUID.class,
                companyId
            );
        });

        TransactionTemplate txTemplate = new TransactionTemplate(businessTxManager);

        // 1. Direct SQL unbalanced DR != CR aborts at COMMIT via trg_mc_journal_double_entry_balance
        Assertions.assertThatThrownBy(() ->
            TenantContextHolder.runWithCompany(companyId, () -> txTemplate.executeWithoutResult(status -> {
                UUID badJournalId = insertRawPostedJournal(openAprilPeriodId, LocalDate.of(2025, 4, 2));
                insertRawEntry(badJournalId, openAprilPeriodId, LocalDate.of(2025, 4, 2), officeRentLedgerId, "DR",
                    new BigDecimal("1000.00"));
                insertRawEntry(badJournalId, openAprilPeriodId, LocalDate.of(2025, 4, 2), cashLedgerId, "CR",
                    new BigDecimal("999.99"));
            }))
        )
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("MC-ACC-UNBALANCED-JOURNAL");

        // 2. Direct SQL single-entry (COUNT = 1) aborts at COMMIT with MC-ACC-MIN-TWO-ENTRIES
        Assertions.assertThatThrownBy(() ->
            TenantContextHolder.runWithCompany(companyId, () -> txTemplate.executeWithoutResult(status -> {
                UUID singleEntryJournalId = insertRawPostedJournal(openAprilPeriodId, LocalDate.of(2025, 4, 2));
                insertRawEntry(singleEntryJournalId, openAprilPeriodId, LocalDate.of(2025, 4, 2),
                    officeRentLedgerId, "DR", new BigDecimal("500.00"));
            }))
        )
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("MC-ACC-MIN-TWO-ENTRIES");

        // 3. Direct SQL zero-entry / zero-amount guard fails closed
        Assertions.assertThatThrownBy(() ->
            TenantContextHolder.runWithCompany(companyId, () -> txTemplate.executeWithoutResult(status -> {
                insertRawPostedJournal(openAprilPeriodId, LocalDate.of(2025, 4, 2));
            }))
        )
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("MC-ACC-MIN-TWO-ENTRIES");

        Assertions.assertThatThrownBy(() ->
            TenantContextHolder.runWithCompany(companyId, () -> txTemplate.executeWithoutResult(status -> {
                UUID zeroJournalId = insertRawPostedJournal(openAprilPeriodId, LocalDate.of(2025, 4, 2));
                insertRawEntry(zeroJournalId, openAprilPeriodId, LocalDate.of(2025, 4, 2),
                    officeRentLedgerId, "DR", BigDecimal.ZERO);
            }))
        )
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("chk_mc_entry_amount_exact_numeric");

        // 4. Direct SQL excess fractional scale (100.123 on scale-2 company) fails with MC-ACC-SCALE-OVERFLOW
        Assertions.assertThatThrownBy(() ->
            TenantContextHolder.runWithCompany(companyId, () -> txTemplate.executeWithoutResult(status -> {
                UUID scaleJournalId = insertRawPostedJournal(openAprilPeriodId, LocalDate.of(2025, 4, 2));
                insertRawEntry(scaleJournalId, openAprilPeriodId, LocalDate.of(2025, 4, 2),
                    officeRentLedgerId, "DR", new BigDecimal("100.123"));
            }))
        )
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("MC-ACC-SCALE-OVERFLOW");

        // 5. Soft-lock March 2026 period -> both API and raw SQL mc_entry insert fail with MC-ACC-PERIOD-NOT-OPEN
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.patch("/api/v1/companies/" + companyId + "/periods/" + march2026PeriodId)
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"state\":\"SOFT_LOCKED\",\"reason\":\"Year-end audit lock\"}"),
                "vch-lock-mar26"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Journal",
                          "voucherDate": "2026-03-15",
                          "lines": [
                            { "ledgerId": "%s", "side": "DR", "amount": "1000.00" },
                            { "ledgerId": "%s", "side": "CR", "amount": "1000.00" }
                          ]
                        }
                        """.formatted(officeRentLedgerId, cashLedgerId)
                    ),
                "vch-closed-period-api"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-ACC-PERIOD-NOT-OPEN"));

        Assertions.assertThatThrownBy(() ->
            TenantContextHolder.runWithCompany(companyId, () -> txTemplate.executeWithoutResult(status -> {
                UUID lockedJournalId = insertRawPostedJournal(march2026PeriodId, LocalDate.of(2026, 3, 15));
                insertRawEntry(lockedJournalId, march2026PeriodId, LocalDate.of(2026, 3, 15),
                    officeRentLedgerId, "DR", new BigDecimal("1000.00"));
                insertRawEntry(lockedJournalId, march2026PeriodId, LocalDate.of(2026, 3, 15),
                    cashLedgerId, "CR", new BigDecimal("1000.00"));
            }))
        )
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("MC-ACC-PERIOD-NOT-OPEN");
    }

    @Test
    @Order(2)
    void contraPaymentReceiptJournalPurchaseOptionalMemorandumAndImmutabilityTriggers() throws Exception {
        // Preview next number for Contra before posting
        MvcResult nextNumRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyId + "/vouchers/next-number?voucherTypeName=Contra&voucherDate=2025-04-02")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode nextNumJson = objectMapper.readTree(nextNumRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(nextNumJson.get("expectedVoucherNumber").asString()).isEqualTo("1");
        Assertions.assertThat(nextNumJson.get("automatic").asBoolean()).isTrue();

        // 1. Contra (F4): Non-Cash/Bank ledger must fail closed with 422 MC-ACC-CONTRA-CASH-BANK-ONLY
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Contra",
                          "voucherDate": "2025-04-02",
                          "accountingLedgerId": "%s",
                          "lines": [
                            { "ledgerId": "%s", "amount": "5000.00" }
                          ]
                        }
                        """.formatted(hdfcBankLedgerId, officeRentLedgerId)
                    ),
                "vch-contra-invalid-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-ACC-CONTRA-CASH-BANK-ONLY"));

        // Valid Contra (SINGLE_ENTRY): Cash CR 25,000.00 -> HDFC Bank DR 25,000.00
        MvcResult contraRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Contra",
                          "voucherDate": "2025-04-02",
                          "persistedView": "SINGLE_ENTRY",
                          "accountingLedgerId": "%s",
                          "narration": "Cash deposited into HDFC Bank",
                          "lines": [
                            { "ledgerId": "%s", "amount": "25000.00" }
                          ]
                        }
                        """.formatted(hdfcBankLedgerId, cashLedgerId)
                    ),
                "vch-contra-ok-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.header().string("ETag", "\"v1\""))
            .andReturn();
        JsonNode contraJson = objectMapper.readTree(contraRes.getResponse().getContentAsByteArray());
        UUID contraId = UUID.fromString(contraJson.get("id").asString());
        Assertions.assertThat(contraJson.get("voucherNumber").asString()).isEqualTo("1");
        Assertions.assertThat(contraJson.get("totalDebit").asString()).isEqualTo("25000.00");
        Assertions.assertThat(contraJson.get("totalCredit").asString()).isEqualTo("25000.00");
        Assertions.assertThat(contraJson.get("lines").size()).isEqualTo(2);

        // Verify defense-in-depth immutability on mc_entry and mc_edit_log_version:
        // (a) Runtime role (mc_app_runtime) is blocked by PostgreSQL REVOKE UPDATE, DELETE ("permission denied")
        // (b) Even privileged owner/migrator role is blocked by BEFORE UPDATE OR DELETE trigger ("MC-IMMUTABLE-RECORD")
        TransactionTemplate txTemplate = new TransactionTemplate(businessTxManager);
        Assertions.assertThatThrownBy(() ->
            TenantContextHolder.runWithCompany(companyId, () -> txTemplate.executeWithoutResult(status ->
                businessJdbcTemplate.update(
                    "UPDATE mc_entry SET amount = 99999.00 WHERE company_id = ? AND journal_id = ?",
                    companyId, contraId
                )
            ))
        )
            .isInstanceOfSatisfying(DataAccessException.class, ex ->
                Assertions.assertThat(ex.getMostSpecificCause().getMessage())
                    .containsAnyOf("permission denied", "MC-IMMUTABLE-RECORD")
            );

        JdbcTemplate migratorJdbc = new JdbcTemplate(businessFlyway.getConfiguration().getDataSource());
        TransactionTemplate migratorTx = new TransactionTemplate(
            new DataSourceTransactionManager(businessFlyway.getConfiguration().getDataSource())
        );
        Assertions.assertThatThrownBy(() ->
            migratorTx.executeWithoutResult(status -> {
                migratorJdbc.execute("SET LOCAL app.bypass_rls = 'on'");
                migratorJdbc.update(
                    "UPDATE mc_entry SET amount = 99999.00 WHERE company_id = ? AND journal_id = ?",
                    companyId, contraId
                );
            })
        )
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("MC-IMMUTABLE-RECORD");

        Assertions.assertThatThrownBy(() ->
            migratorTx.executeWithoutResult(status -> {
                migratorJdbc.execute("SET LOCAL app.bypass_rls = 'on'");
                migratorJdbc.update(
                    "DELETE FROM mc_entry WHERE company_id = ? AND journal_id = ?",
                    companyId, contraId
                );
            })
        )
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("MC-IMMUTABLE-RECORD");

        Assertions.assertThatThrownBy(() ->
            migratorTx.executeWithoutResult(status -> {
                migratorJdbc.execute("SET LOCAL app.bypass_rls = 'on'");
                migratorJdbc.update(
                    "UPDATE mc_edit_log_version SET actor_username = 'tampered' WHERE company_id = ? AND entity_id = ?",
                    companyId, contraId
                );
            })
        )
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("MC-IMMUTABLE-RECORD");

        Assertions.assertThatThrownBy(() ->
            migratorTx.executeWithoutResult(status -> {
                migratorJdbc.execute("SET LOCAL app.bypass_rls = 'on'");
                migratorJdbc.update(
                    "DELETE FROM mc_edit_log_version WHERE company_id = ? AND entity_id = ?",
                    companyId, contraId
                );
            })
        )
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("MC-IMMUTABLE-RECORD");

        // 2. Payment (F5) in SINGLE_ENTRY with Cost Centre allocation & Cheque Bank Allocation
        MvcResult paymentRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Payment",
                          "voucherDate": "2025-04-04",
                          "persistedView": "SINGLE_ENTRY",
                          "accountingLedgerId": "%s",
                          "narration": "April rent by cheque",
                          "lines": [
                            {
                              "ledgerId": "%s",
                              "amount": "12000.00",
                              "costAllocations": [
                                { "costCentreId": "%s", "amount": "12000.00" }
                              ],
                              "bankAllocations": [
                                {
                                  "transactionType": "CHEQUE",
                                  "instrumentNo": "100201",
                                  "instrumentDate": "2025-04-04",
                                  "favouringName": "Chennai Tower Estates"
                                }
                              ]
                            }
                          ]
                        }
                        """.formatted(hdfcBankLedgerId, officeRentLedgerId, chennaiBranchCentreId)
                    ),
                "vch-payment-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        JsonNode paymentJson = objectMapper.readTree(paymentRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(paymentJson.get("voucherNumber").asString()).isEqualTo("1");
        Assertions.assertThat(paymentJson.get("lines").get(0).get("costAllocations").size()).isEqualTo(1);
        Assertions.assertThat(paymentJson.get("lines").get(0).get("bankAllocations").get(0).get("instrumentNo").asString())
            .isEqualTo("100201");

        // 3. Receipt (F6): SINGLE_ENTRY (#1) and AS_VOUCHER (#2)
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Receipt",
                          "voucherDate": "2025-04-05",
                          "persistedView": "SINGLE_ENTRY",
                          "accountingLedgerId": "%s",
                          "narration": "Cash counter sales receipt",
                          "lines": [
                            { "ledgerId": "%s", "amount": "8000.00" }
                          ]
                        }
                        """.formatted(cashLedgerId, domesticSalesLedgerId)
                    ),
                "vch-receipt-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.voucherNumber").value("1"));

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Receipt",
                          "voucherDate": "2025-04-06",
                          "persistedView": "AS_VOUCHER",
                          "narration": "NEFT sales collection",
                          "lines": [
                            {
                              "ledgerId": "%s",
                              "side": "DR",
                              "amount": "15000.00",
                              "bankAllocations": [
                                { "transactionType": "NEFT", "transferModeRef": "UTR99887766", "amount": "15000.00" }
                              ]
                            },
                            { "ledgerId": "%s", "side": "CR", "amount": "15000.00" }
                          ]
                        }
                        """.formatted(hdfcBankLedgerId, domesticSalesLedgerId)
                    ),
                "vch-receipt-2"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.voucherNumber").value("2"));

        // 4. Journal (F7) #1 for 40,000.00 (will be altered to 45,000.00 in Order 3!)
        MvcResult jrnlRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Journal",
                          "voucherDate": "2025-04-07",
                          "narration": "Rent provision",
                          "lines": [
                            {
                              "ledgerId": "%s",
                              "side": "DR",
                              "amount": "40000.00",
                              "costAllocations": [
                                { "costCentreId": "%s", "amount": "40000.00" }
                              ]
                            },
                            { "ledgerId": "%s", "side": "CR", "amount": "40000.00" }
                          ]
                        }
                        """.formatted(officeRentLedgerId, chennaiBranchCentreId, cashLedgerId)
                    ),
                "vch-journal-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        journal1Id = UUID.fromString(
            objectMapper.readTree(jrnlRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        // 5. Purchase (F9) #1
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Purchase",
                          "voucherDate": "2025-04-07",
                          "supplierInvoiceNo": "SUP-INV-77",
                          "supplierInvoiceDate": "2025-04-07",
                          "persistedView": "AS_VOUCHER",
                          "lines": [
                            { "ledgerId": "%s", "side": "DR", "amount": "18000.00" },
                            { "ledgerId": "%s", "side": "CR", "amount": "18000.00" }
                          ]
                        }
                        """.formatted(domesticPurchaseLedgerId, cashLedgerId)
                    ),
                "vch-purchase-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.voucherNumber").value("1"));

        // Office Rent GL balance should be 12,000.00 + 40,000.00 = 52,000.00 DR
        Assertions.assertThat(readClosingNetBalance(officeRentLedgerId)).isEqualByComparingTo("52000.00");

        // 6. Optional (Ctrl+L) & Memorandum must have 0.00 GL impact in vw_mc_ledger_closing_balance
        MvcResult optRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Journal",
                          "voucherDate": "2025-04-08",
                          "postingNature": "OPTIONAL",
                          "narration": "Proforma optional rent adjustment",
                          "lines": [
                            { "ledgerId": "%s", "side": "DR", "amount": "99000.00" },
                            { "ledgerId": "%s", "side": "CR", "amount": "99000.00" }
                          ]
                        }
                        """.formatted(officeRentLedgerId, cashLedgerId)
                    ),
                "vch-optional-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.postingNature").value("OPTIONAL"))
            .andReturn();
        UUID optionalVoucherId = UUID.fromString(
            objectMapper.readTree(optRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Memorandum",
                          "voucherDate": "2025-04-08",
                          "narration": "Non-accounting memo",
                          "lines": [
                            { "ledgerId": "%s", "side": "DR", "amount": "77000.00" },
                            { "ledgerId": "%s", "side": "CR", "amount": "77000.00" }
                          ]
                        }
                        """.formatted(officeRentLedgerId, cashLedgerId)
                    ),
                "vch-memo-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.postingNature").value("MEMORANDUM"));

        Assertions.assertThat(readClosingNetBalance(officeRentLedgerId)).isEqualByComparingTo("52000.00");

        // Promote Optional -> Regular via /post, verify GL becomes 151,000.00, then /reverse back to 52,000.00
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post(
                    "/api/v1/companies/" + companyId + "/vouchers/" + optionalVoucherId + "/post"
                ),
                "vch-optional-post-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.postingNature").value("NORMAL"));
        Assertions.assertThat(readClosingNetBalance(officeRentLedgerId)).isEqualByComparingTo("151000.00");

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post(
                    "/api/v1/companies/" + companyId + "/vouchers/" + optionalVoucherId + "/reverse"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"narration\":\"Reverse temporary adjustment\"}"),
                "vch-optional-rev-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.reversalOfJournalId").value(optionalVoucherId.toString()));
        Assertions.assertThat(readClosingNetBalance(officeRentLedgerId)).isEqualByComparingTo("52000.00");
    }

    @Test
    @Order(3)
    void fourEyesMakerCheckerVoucherAlterationEditLogRedlineAndAltXCancellation() throws Exception {
        // 1. Create custom FOUR_EYES voucher type
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/voucher-types")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "High Value Payment",
                          "abbreviation": "HVP",
                          "parentVoucherType": "Payment",
                          "category": "PAYMENT",
                          "approvalPolicy": "FOUR_EYES"
                        }
                        """
                    ),
                "vt-four-eyes-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        // Maker submits FOUR_EYES voucher -> saved as DRAFT
        MvcResult hvpRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "High Value Payment",
                          "voucherDate": "2025-04-09",
                          "persistedView": "AS_VOUCHER",
                          "action": "POST",
                          "narration": "Executive lease deposit",
                          "lines": [
                            { "ledgerId": "%s", "side": "DR", "amount": "5000.00" },
                            { "ledgerId": "%s", "side": "CR", "amount": "5000.00" }
                          ]
                        }
                        """.formatted(officeRentLedgerId, cashLedgerId)
                    ),
                "vch-hvp-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("DRAFT"))
            .andReturn();
        UUID hvpId = UUID.fromString(
            objectMapper.readTree(hvpRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        // Maker self-approval MUST be rejected with 403 MC-ACC-FOUR-EYES-VIOLATION
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers/" + hvpId + "/approve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"postImmediately\":true}"),
                "vch-hvp-self-approve"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-ACC-FOUR-EYES-VIOLATION"));

        // Unapproved /post MUST also be rejected with 403 MC-ACC-FOUR-EYES-VIOLATION
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers/" + hvpId + "/post"),
                "vch-hvp-unapproved-post"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-ACC-FOUR-EYES-VIOLATION"));

        // Provision distinct second user 'checker_priya' with SENIOR_ACCOUNTANT role and approve + post
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/admin/principals")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "username": "checker_priya",
                          "displayName": "Priya Audit Checker",
                          "password": "PriyaChecker#2026!",
                          "systemRoles": []
                        }
                        """
                    ),
                "usr-priya-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put("/api/v1/admin/principals/checker_priya/grants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of(
                                "companyId", companyId.toString(),
                                "roleCode", "SENIOR_ACCOUNTANT",
                                "backdatedDaysLimit", 30
                            )
                        )
                    ),
                "usr-priya-grant-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        MvcResult priyaLogin = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"checker_priya\",\"password\":\"PriyaChecker#2026!\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Cookie priyaSession = latestCookie(priyaLogin, "MCSESSION");
        Cookie priyaXsrf = latestCookie(priyaLogin, "XSRF-TOKEN");

        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers/" + hvpId + "/approve")
                .cookie(priyaSession, priyaXsrf)
                .header("X-XSRF-TOKEN", URLDecoder.decode(priyaXsrf.getValue(), StandardCharsets.UTF_8))
                .header("Idempotency-Key", "vch-hvp-priya-approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"postImmediately\":true,\"remarks\":\"Verified invoice\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("POSTED"));

        // Office Rent GL is now 52,000 + 5,000 = 57,000.00
        Assertions.assertThat(readClosingNetBalance(officeRentLedgerId)).isEqualByComparingTo("57000.00");

        // 2. PUT /vouchers/{journal1Id} altering 40,000.00 -> 45,000.00 (MCA Edit Log v1 -> v2 + compensating GL batch)
        MvcResult alterRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put("/api/v1/companies/" + companyId + "/vouchers/" + journal1Id)
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Journal",
                          "voucherDate": "2025-04-07",
                          "narration": "Rent provision revised upward",
                          "lines": [
                            {
                              "ledgerId": "%s",
                              "side": "DR",
                              "amount": "45000.00",
                              "costAllocations": [
                                { "costCentreId": "%s", "amount": "45000.00" }
                              ]
                            },
                            { "ledgerId": "%s", "side": "CR", "amount": "45000.00" }
                          ]
                        }
                        """.formatted(officeRentLedgerId, chennaiBranchCentreId, cashLedgerId)
                    ),
                "vch-journal-1-alter"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.header().string("ETag", "\"v2\""))
            .andExpect(MockMvcResultMatchers.jsonPath("$.versionNo").value(2))
            .andExpect(MockMvcResultMatchers.jsonPath("$.totalDebit").value("45000.00"))
            .andReturn();
        JsonNode alteredJson = objectMapper.readTree(alterRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(alteredJson.get("currentPostingBatchNo").asInt()).isEqualTo(3);

        // Office Rent GL MUST reflect 45,000.00 (not 85,000.00!): 12,000 + 45,000 + 5,000 = 62,000.00
        Assertions.assertThat(readClosingNetBalance(officeRentLedgerId)).isEqualByComparingTo("62000.00");

        // Verify stale If-Match "v1" returns 412 MC-CONCURRENCY-STALE-VERSION
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put("/api/v1/companies/" + companyId + "/vouchers/" + journal1Id)
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Journal",
                          "voucherDate": "2025-04-07",
                          "lines": [
                            { "ledgerId": "%s", "side": "DR", "amount": "46000.00" },
                            { "ledgerId": "%s", "side": "CR", "amount": "46000.00" }
                          ]
                        }
                        """.formatted(officeRentLedgerId, cashLedgerId)
                    ),
                "vch-journal-1-stale"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isPreconditionFailed())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-CONCURRENCY-STALE-VERSION"));

        // Verify mc_edit_log_version has v1 (CREATE) and v2 (ALTER) with 40000.00 -> 45000.00 field diff
        TenantContextHolder.runWithCompany(companyId, () -> {
            List<Map<String, Object>> editVersions = businessJdbcTemplate.queryForList(
                """
                SELECT version_no, operation, changed_fields::text AS diffs
                  FROM mc_edit_log_version
                 WHERE company_id = ? AND entity_type = 'VOUCHER' AND entity_id = ?
                 ORDER BY version_no ASC
                """,
                companyId,
                journal1Id
            );
            Assertions.assertThat(editVersions).hasSize(2);
            Assertions.assertThat(editVersions.get(0).get("operation")).isEqualTo("CREATE");
            Assertions.assertThat(editVersions.get(1).get("operation")).isEqualTo("ALTER");
            String diffsText = editVersions.get(1).get("diffs").toString();
            Assertions.assertThat(diffsText).contains("totalDebit", "40000.00", "45000.00");
        });

        // 3. Alt+X Cancel: Post Sales #1 with bill allocation, then cancel it -> preserves number #1, zeroes GL & releases bill
        MvcResult salesCancelTarget = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Sales",
                          "voucherDate": "2025-04-10",
                          "persistedView": "AS_VOUCHER",
                          "partyLedgerId": "%s",
                          "narration": "Invoice cancelled by customer",
                          "lines": [
                            {
                              "ledgerId": "%s",
                              "side": "DR",
                              "amount": "25000.00",
                              "billAllocations": [
                                { "allocationType": "NEW_REF", "billName": "INV-CANCEL-01", "amount": "25000.00" }
                              ]
                            },
                            { "ledgerId": "%s", "side": "CR", "amount": "25000.00" }
                          ]
                        }
                        """.formatted(muruganDebtorLedgerId, muruganDebtorLedgerId, domesticSalesLedgerId)
                    ),
                "vch-sales-cancel-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.voucherNumber").value("1"))
            .andReturn();
        UUID sales1Id = UUID.fromString(
            objectMapper.readTree(salesCancelTarget.getResponse().getContentAsByteArray()).get("id").asString()
        );

        // Cancel Sales #1 (Alt+X)
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers/" + sales1Id + "/cancel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Customer cancelled order prior to dispatch\"}"),
                "vch-sales-cancel-exec"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("CANCELLED"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.voucherNumber").value("1"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.totalDebit").value("0.00"));

        Assertions.assertThat(readClosingNetBalance(muruganDebtorLedgerId)).isEqualByComparingTo("0.00");

        MvcResult openBillsAfterCancel = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyId + "/ledgers/" + muruganDebtorLedgerId + "/open-bills")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(openBillsAfterCancel.getResponse().getContentAsByteArray()).size()
        ).isZero();

        // Day Book keyset pagination across all vouchers + Cancelled badge check
        List<String> dayBookIds = new ArrayList<>();
        boolean sawCancelledBadge = false;
        String cursor = null;
        do {
            String url = "/api/v1/companies/" + companyId + "/vouchers?limit=3"
                + (cursor == null ? "" : "&cursor=" + cursor);
            MvcResult pageRes = mockMvc.perform(authedGet(url))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
            JsonNode page = objectMapper.readTree(pageRes.getResponse().getContentAsByteArray());
            for (JsonNode item : page.get("items")) {
                dayBookIds.add(item.get("id").asString());
                if (sales1Id.toString().equals(item.get("id").asString())) {
                    Assertions.assertThat(item.get("primaryParticulars").asString()).isEqualTo("Cancelled");
                    sawCancelledBadge = true;
                }
            }
            cursor = page.get("hasMore").asBoolean() ? page.get("nextCursor").asString() : null;
        } while (cursor != null);

        Assertions.assertThat(sawCancelledBadge).isTrue();
        Assertions.assertThat(dayBookIds).doesNotHaveDuplicates();
    }

    @Test
    @Order(4)
    void billWiseSubLedgerLifecycleOverSettlementGuardAndMultiCurrencyUnadjustedForexDelta() throws Exception {
        // 1. Bill-wise ledger without billAllocations must fail closed with 422 MC-ACC-BILL-ALLOCATION-REQUIRED
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Sales",
                          "voucherDate": "2025-04-12",
                          "persistedView": "AS_VOUCHER",
                          "lines": [
                            { "ledgerId": "%s", "side": "DR", "amount": "50000.00" },
                            { "ledgerId": "%s", "side": "CR", "amount": "50000.00" }
                          ]
                        }
                        """.formatted(muruganDebtorLedgerId, domesticSalesLedgerId)
                    ),
                "vch-bill-missing-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-ACC-BILL-ALLOCATION-REQUIRED"));

        // 2. Post Sales #2 (NEW_REF SAL-001 for 50,000.00; #1 was cancelled & retained)
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Sales",
                          "voucherDate": "2025-04-12",
                          "persistedView": "AS_VOUCHER",
                          "partyLedgerId": "%s",
                          "narration": "Credit sale SAL-001",
                          "lines": [
                            {
                              "ledgerId": "%s",
                              "side": "DR",
                              "amount": "50000.00",
                              "billAllocations": [
                                { "allocationType": "NEW_REF", "billName": "SAL-001", "amount": "50000.00" }
                              ]
                            },
                            { "ledgerId": "%s", "side": "CR", "amount": "50000.00" }
                          ]
                        }
                        """.formatted(muruganDebtorLedgerId, muruganDebtorLedgerId, domesticSalesLedgerId)
                    ),
                "vch-sales-sal001"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.voucherNumber").value("2"));

        // 3. Partial Receipt (AGST_REF SAL-001 for 20,000.00)
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Receipt",
                          "voucherDate": "2025-04-15",
                          "persistedView": "AS_VOUCHER",
                          "lines": [
                            { "ledgerId": "%s", "side": "DR", "amount": "20000.00" },
                            {
                              "ledgerId": "%s",
                              "side": "CR",
                              "amount": "20000.00",
                              "billAllocations": [
                                { "allocationType": "AGST_REF", "billName": "SAL-001", "amount": "20000.00" }
                              ]
                            }
                          ]
                        }
                        """.formatted(hdfcBankLedgerId, muruganDebtorLedgerId)
                    ),
                "vch-rcpt-sal001-part"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        // 4. Verify residual open bill balance = 30,000.00 DR with auto-computed 30-day dueDate (2025-05-12)
        MvcResult openBillsRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyId + "/ledgers/" + muruganDebtorLedgerId + "/open-bills")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode openBills = objectMapper.readTree(openBillsRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(openBills.size()).isEqualTo(1);
        Assertions.assertThat(openBills.get(0).get("billName").asString()).isEqualTo("SAL-001");
        Assertions.assertThat(openBills.get(0).get("pendingAmount").asString()).isEqualTo("30000.00");
        Assertions.assertThat(openBills.get(0).get("pendingSide").asString()).isEqualTo("DR");
        Assertions.assertThat(openBills.get(0).get("dueDate").asString()).isEqualTo("2025-05-12");

        // 5. Over-settlement (35,000.00 against 30,000.00 open) rejected with 422 MC-ACC-BILL-OVERSETTLED
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Receipt",
                          "voucherDate": "2025-04-16",
                          "persistedView": "AS_VOUCHER",
                          "lines": [
                            { "ledgerId": "%s", "side": "DR", "amount": "35000.00" },
                            {
                              "ledgerId": "%s",
                              "side": "CR",
                              "amount": "35000.00",
                              "billAllocations": [
                                { "allocationType": "AGST_REF", "billName": "SAL-001", "amount": "35000.00" }
                              ]
                            }
                          ]
                        }
                        """.formatted(hdfcBankLedgerId, muruganDebtorLedgerId)
                    ),
                "vch-rcpt-sal001-over"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-ACC-BILL-OVERSETTLED"));

        // 6. Golden Vector A: Multi-Currency USD $1,000.00 @ 83.00 = ₹83,000.00 settled @ 84.50 = ₹84,500.00
        //    -> unadjusted_forex_delta == 1500.00!
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Sales",
                          "voucherDate": "2025-04-18",
                          "persistedView": "AS_VOUCHER",
                          "currencyCode": "USD",
                          "exchangeRate": "83.00",
                          "partyLedgerId": "%s",
                          "narration": "Export Software Invoice USD 1000 @ 83.00",
                          "lines": [
                            {
                              "ledgerId": "%s",
                              "side": "DR",
                              "forexCurrencyCode": "USD",
                              "forexAmount": "1000.00",
                              "forexRate": "83.00",
                              "amount": "83000.00",
                              "billAllocations": [
                                {
                                  "allocationType": "NEW_REF",
                                  "billName": "EXP-USD-001",
                                  "forexCurrencyCode": "USD",
                                  "forexAmount": "1000.00",
                                  "forexRate": "83.00",
                                  "amount": "83000.00"
                                }
                              ]
                            },
                            { "ledgerId": "%s", "side": "CR", "amount": "83000.00" }
                          ]
                        }
                        """.formatted(globalTechUsdDebtorId, globalTechUsdDebtorId, domesticSalesLedgerId)
                    ),
                "vch-fx-sales-usd-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        MvcResult fxSettleRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Receipt",
                          "voucherDate": "2025-04-25",
                          "persistedView": "AS_VOUCHER",
                          "currencyCode": "USD",
                          "exchangeRate": "84.50",
                          "narration": "SWIFT inward remittance USD 1000 @ 84.50",
                          "lines": [
                            { "ledgerId": "%s", "side": "DR", "amount": "84500.00" },
                            {
                              "ledgerId": "%s",
                              "side": "CR",
                              "forexCurrencyCode": "USD",
                              "forexAmount": "1000.00",
                              "forexRate": "84.50",
                              "amount": "84500.00",
                              "billAllocations": [
                                {
                                  "allocationType": "AGST_REF",
                                  "billName": "EXP-USD-001",
                                  "forexCurrencyCode": "USD",
                                  "forexAmount": "1000.00",
                                  "forexRate": "84.50",
                                  "amount": "84500.00"
                                }
                              ]
                            }
                          ]
                        }
                        """.formatted(hdfcBankLedgerId, globalTechUsdDebtorId)
                    ),
                "vch-fx-rcpt-usd-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        JsonNode fxSettleJson = objectMapper.readTree(fxSettleRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(
            fxSettleJson.get("lines").get(1).get("billAllocations").get(0).get("unadjustedForexDelta").asString()
        ).isEqualTo("1500.00");

        // Verify Trial Balance global invariant SUM(DR) == SUM(CR) and MC-AUDIT-2 hash chain integrity
        TenantContextHolder.runWithCompany(companyId, () -> {
            BigDecimal tbDiff = businessJdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(dr_total) - SUM(cr_total), 0::numeric)
                  FROM mc_ledger_period_bal
                 WHERE company_id = ?
                """,
                BigDecimal.class,
                companyId
            );
            Assertions.assertThat(tbDiff).isEqualByComparingTo(BigDecimal.ZERO);
        });

        mockMvc.perform(authedGet("/api/v1/admin/audit/verify"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.verified").value(true));
    }

    private UUID insertRawPostedJournal(UUID periodId, LocalDate vchDate) {
        UUID jId = UUID.randomUUID();
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_journal (
                id, company_id, period_id, fiscal_year_id, voucher_type_id,
                status, posting_nature, persisted_view, approval_policy,
                voucher_number, voucher_date, effective_date,
                total_debit, total_credit, current_posting_batch_no, created_by
            ) VALUES (?, ?, ?, ?, ?, 'POSTED', 'NORMAL', 'AS_VOUCHER', 'SINGLE_STEP', ?, ?, ?, 1000, 1000, 1, ?)
            """,
            jId,
            companyId,
            periodId,
            fiscalYearId,
            journalVoucherTypeId,
            "RAW-" + jId.toString().substring(0, 8),
            vchDate,
            vchDate,
            ownerPrincipalId
        );
        return jId;
    }

    private void insertRawEntry(
        UUID journalId,
        UUID periodId,
        LocalDate entryDate,
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
            periodId,
            entryDate,
            ledgerId,
            side,
            amount,
            amount
        );
    }

    private BigDecimal readClosingNetBalance(UUID ledgerId) {
        return TenantContextHolder.callWithCompany(companyId, () -> {
            List<BigDecimal> rows = businessJdbcTemplate.query(
                """
                SELECT closing_dr - closing_cr AS signed_closing
                  FROM vw_mc_ledger_closing_balance
                 WHERE company_id = ? AND ledger_id = ?
                """,
                (rs, rowNum) -> rs.getBigDecimal("signed_closing"),
                companyId,
                ledgerId
            );
            return rows.isEmpty() ? BigDecimal.ZERO : rows.getFirst();
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

    private UUID createLedger(
        String name,
        UUID groupId,
        boolean billWise,
        boolean costCentres,
        Integer creditDays
    ) throws Exception {
        String creditDaysField = creditDays == null ? "null" : creditDays.toString();
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "groupId": "%s",
                          "maintainBalancesBillByBill": %s,
                          "costCentresApplicable": %s,
                          "defaultCreditPeriodDays": %s,
                          "openingBalance": "0.00",
                          "openingBalanceSide": "DR"
                        }
                        """.formatted(name, groupId, billWise, costCentres, creditDaysField)
                    ),
                "ldg-" + UUID.randomUUID()
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
                            "costCentresEnabled": true,
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
                .content("{\"username\":\"vch_admin\",\"password\":\"ChiefVoucher#2026!\"}")
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
