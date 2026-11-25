package com.raster.moneycraft.reporting;

import com.raster.moneycraft.foundation.DualPostgres18TestcontainersSupport;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
 * Real Dual-PostgreSQL 18 integration tests for Wave W2B:
 * 1. Level 2 / Level 3 Tally Report Endpoints:
 *    - GET /api/v1/companies/{companyId}/reports/statistics
 *    - GET /api/v1/companies/{companyId}/reports/exceptions
 *    - GET /api/v1/companies/{companyId}/reports/account-books
 *    - GET /api/v1/companies/{companyId}/reports/stock-query
 * 2. Tally Voucher Keyboard Shortcut Helpers:
 *    - GET /api/v1/companies/{companyId}/vouchers/adjacent (PageUp / PageDown)
 *    - GET /api/v1/companies/{companyId}/vouchers/last-narration (Ctrl+R)
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TallyLevel3ReportsAndShortcutApisTest {

    private static final String ADMIN_USER = "w2b_controller";
    private static final String ADMIN_PASS = "Correct-Horse-Battery-Staple-99!";

    private static final DualPostgres18TestcontainersSupport.ClusterPair CLUSTERS =
        DualPostgres18TestcontainersSupport.startIsolatedClusterPair();

    private static Cookie ownerSessionCookie;
    private static Cookie ownerXsrfCookie;

    private static UUID companyId;
    private static UUID debtorsGroupId;
    private static UUID bankLedgerId;
    private static UUID cashLedgerId;
    private static UUID supplierLedgerId;
    private static UUID customerLedgerId;
    private static UUID oximeterItemId;
    private static UUID maskBoxItemId;
    private static UUID salesVch1Id;
    private static UUID salesVch2Id;

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

    @Test
    @Order(1)
    void seedCompanyMastersInventoryAndMultiCategoryVouchers() throws Exception {
        MvcResult initRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/system/initialise")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "installationLabel": "MoneyCraft Wave W2B Level 3 Reports Cluster",
                      "ownerUsername": "%s",
                      "ownerDisplayName": "Level 3 Reporting Lead",
                      "password": "%s"
                    }
                    """.formatted(ADMIN_USER, ADMIN_PASS)
                )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        ownerSessionCookie = latestCookie(initRes, "MCSESSION");
        ownerXsrfCookie = latestCookie(initRes, "XSRF-TOKEN");

        String cmpGstinPrefix = "33AABCM4545F1Z";
        String cmpGstin = cmpGstinPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(cmpGstinPrefix);
        companyId = createTestCompany("MCTALLY3", "MoneyCraft Level 3 Verification Co", "33", "2025-04-01", cmpGstin);

        UUID capitalGrp = findGroupIdByName(companyId, "Capital Account");
        UUID bankGrp = findGroupIdByName(companyId, "Bank Accounts");
        debtorsGroupId = findGroupIdByName(companyId, "Sundry Debtors");
        UUID creditorsGrp = findGroupIdByName(companyId, "Sundry Creditors");
        UUID dutiesGrp = findGroupIdByName(companyId, "Duties & Taxes");
        UUID salesGrp = findGroupIdByName(companyId, "Sales Accounts");
        UUID purchGrp = findGroupIdByName(companyId, "Purchase Accounts");
        UUID indirectExpGrp = findGroupIdByName(companyId, "Indirect Expenses");
        cashLedgerId = findLedgerIdByName(companyId, "Cash");

        UUID capitalLedgerId = createSimpleLedger(companyId, "Founder Capital A/c", capitalGrp);
        bankLedgerId = createSimpleLedger(companyId, "ICICI Corporate A/c", bankGrp);
        UUID salesLedgerId = createSimpleLedger(companyId, "Sales - Medical", salesGrp);
        UUID purchLedgerId = createSimpleLedger(companyId, "Purchases - Medical", purchGrp);
        UUID rentLedgerId = createSimpleLedger(companyId, "Warehouse Rent", indirectExpGrp);
        UUID cgstId = createGstDutyLedger(companyId, "CGST", dutiesGrp, "CGST");
        UUID sgstId = createGstDutyLedger(companyId, "SGST", dutiesGrp, "SGST_UTGST");

        String suppPrefix = "33AAFCK5678G1Z";
        supplierLedgerId = createBillWisePartyLedger(
            companyId,
            "Kaveri Meditech Suppliers",
            creditorsGrp,
            "33",
            suppPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(suppPrefix),
            "CR"
        );
        String custPrefix = "33AABCS9012H1Z";
        customerLedgerId = createBillWisePartyLedger(
            companyId,
            "Apollo Diagnostics Centre",
            debtorsGroupId,
            "33",
            custPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(custPrefix),
            "DR"
        );

        UUID uomNosId = createSimpleUom(companyId, "NOS", "Numbers", "NOS", 0);
        UUID stockGrpId = createStockGroup(companyId, "Medical Devices");
        oximeterItemId = createStockItem(
            companyId, "Pulse Oximeter Pro", stockGrpId, uomNosId, "AVG_COST", "90181990", "18.00",
            salesLedgerId, purchLedgerId
        );
        maskBoxItemId = createStockItem(
            companyId, "Surgical Mask Box", stockGrpId, uomNosId, "AVG_COST", "63079090", "5.00",
            salesLedgerId, purchLedgerId
        );

        // 1. Receipt (2025-04-01): Capital 300,000.00 into Bank
        postAccountingVoucher(
            companyId, "Receipt", "2025-04-01", "NORMAL", null,
            "Initial founder capital via RTGS",
            """
            [
              { "ledgerId": "%s", "side": "DR", "amount": "300000.00",
                "bankAllocations": [
                  { "bankLedgerId": "%s", "transactionType": "RTGS", "instrumentNo": "UTR30001",
                    "instrumentDate": "2025-04-01", "side": "DR", "amount": "300000.00" }
                ]
              },
              { "ledgerId": "%s", "side": "CR", "amount": "300000.00" }
            ]
            """.formatted(bankLedgerId, bankLedgerId, capitalLedgerId)
        );

        // 2. Contra (2025-04-02): Withdraw 10,000.00 Cash from Bank
        postAccountingVoucher(
            companyId, "Contra", "2025-04-02", "NORMAL", null,
            "Petty cash withdrawal from ICICI",
            """
            [
              { "ledgerId": "%s", "side": "DR", "amount": "10000.00" },
              { "ledgerId": "%s", "side": "CR", "amount": "10000.00",
                "bankAllocations": [
                  { "bankLedgerId": "%s", "transactionType": "CHEQUE", "instrumentNo": "200001",
                    "instrumentDate": "2025-04-02", "side": "CR", "amount": "10000.00" }
                ]
              }
            ]
            """.formatted(cashLedgerId, bankLedgerId, bankLedgerId)
        );

        // 3. Payment (2025-04-05): Pay 25,000.00 Cash for Rent -> makes Cash go NEGATIVE (-15,000.00 CR) for NEGATIVE_LEDGERS!
        postAccountingVoucher(
            companyId, "Payment", "2025-04-05", "NORMAL", null,
            "April warehouse rent paid in cash",
            """
            [
              { "ledgerId": "%s", "side": "DR", "amount": "25000.00" },
              { "ledgerId": "%s", "side": "CR", "amount": "25000.00" }
            ]
            """.formatted(rentLedgerId, cashLedgerId)
        );

        // 4. Purchase Item Invoice (2025-04-10, due 2025-04-20 -> overdue by 2025-06-30!): 20 Oximeter @ 1000 = 20000 + 1800 + 1800 = 23600
        postItemInvoice(
            companyId, "Purchase", "2025-04-10", "KAV-101", supplierLedgerId, purchLedgerId, "33",
            "Purchase batch 1 of Pulse Oximeter Pro",
            """
            [
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "20", "billedQty": "20", "rate": "1000.00", "amount": "20000.00" }
            ]
            """.formatted(oximeterItemId, uomNosId),
            """
            [
              { "lineRole": "ITEM_ACCOUNTING", "ledgerId": "%s", "side": "DR", "amount": "20000.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "DR", "amount": "1800.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "DR", "amount": "1800.00" },
              { "lineRole": "PARTY", "ledgerId": "%s", "side": "CR", "amount": "23600.00",
                "billAllocations": [
                  { "allocationType": "NEW_REF", "billName": "KAV-101", "dueDate": "2025-04-20", "side": "CR", "amount": "23600.00" }
                ]
              }
            ]
            """.formatted(purchLedgerId, cgstId, sgstId, supplierLedgerId)
        );

        // 5. Sales #1 Item Invoice (2025-04-15, due 2025-04-25 -> overdue by 2025-06-30!):
        //    8 Oximeter @ 1500 = 12000 + 5 Mask Box @ 200 = 1000 (Mask Box has 0 inward -> triggers NEGATIVE_STOCK of -5 NOS!)
        salesVch1Id = postItemInvoice(
            companyId, "Sales", "2025-04-15", null, customerLedgerId, salesLedgerId, "33",
            "First hospital consignment to Apollo Diagnostics",
            """
            [
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "8", "billedQty": "8", "rate": "1500.00", "amount": "12000.00" },
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "5", "billedQty": "5", "rate": "200.00", "amount": "1000.00" }
            ]
            """.formatted(oximeterItemId, uomNosId, maskBoxItemId, uomNosId),
            """
            [
              { "lineRole": "PARTY", "ledgerId": "%s", "side": "DR", "amount": "15340.00",
                "billAllocations": [
                  { "allocationType": "NEW_REF", "billName": "INV-AP-001", "dueDate": "2025-04-25", "side": "DR", "amount": "15340.00" }
                ]
              },
              { "lineRole": "ITEM_ACCOUNTING", "ledgerId": "%s", "side": "CR", "amount": "13000.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "CR", "amount": "1170.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "CR", "amount": "1170.00" }
            ]
            """.formatted(customerLedgerId, salesLedgerId, cgstId, sgstId)
        );

        // 6. Sales #2 Item Invoice in May (2025-05-10): 4 Oximeter @ 1600 = 6400 + 576 + 576 = 7552.00
        salesVch2Id = postItemInvoice(
            companyId, "Sales", "2025-05-10", null, customerLedgerId, salesLedgerId, "33",
            "May follow-up supply to Apollo Diagnostics",
            """
            [
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "4", "billedQty": "4", "rate": "1600.00", "amount": "6400.00" }
            ]
            """.formatted(oximeterItemId, uomNosId),
            """
            [
              { "lineRole": "PARTY", "ledgerId": "%s", "side": "DR", "amount": "7552.00",
                "billAllocations": [
                  { "allocationType": "NEW_REF", "billName": "INV-AP-002", "dueDate": "2025-07-15", "side": "DR", "amount": "7552.00" }
                ]
              },
              { "lineRole": "ITEM_ACCOUNTING", "ledgerId": "%s", "side": "CR", "amount": "6400.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "CR", "amount": "576.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "CR", "amount": "576.00" }
            ]
            """.formatted(customerLedgerId, salesLedgerId, cgstId, sgstId)
        );

        // 7. Journal, Debit Note, Credit Note
        postAccountingVoucher(
            companyId, "Journal", "2025-05-12", "NORMAL", null,
            "Accrued warehouse maintenance provision",
            """
            [
              { "ledgerId": "%s", "side": "DR", "amount": "1200.00" },
              { "ledgerId": "%s", "side": "CR", "amount": "1200.00" }
            ]
            """.formatted(rentLedgerId, capitalLedgerId)
        );
        postAccountingVoucher(
            companyId, "Debit Note", "2025-05-14", "NORMAL", null,
            "Supplier rate difference debit note",
            """
            [
              { "ledgerId": "%s", "side": "DR", "amount": "600.00",
                "billAllocations": [
                  { "allocationType": "AGST_REF", "billName": "KAV-101", "side": "DR", "amount": "600.00" }
                ]
              },
              { "ledgerId": "%s", "side": "CR", "amount": "600.00" }
            ]
            """.formatted(supplierLedgerId, purchLedgerId)
        );
        postAccountingVoucher(
            companyId, "Credit Note", "2025-05-15", "NORMAL", null,
            "Prompt display rebate credit note",
            """
            [
              { "ledgerId": "%s", "side": "DR", "amount": "340.00" },
              { "ledgerId": "%s", "side": "CR", "amount": "340.00",
                "billAllocations": [
                  { "allocationType": "AGST_REF", "billName": "INV-AP-001", "side": "CR", "amount": "340.00" }
                ]
              }
            ]
            """.formatted(salesLedgerId, customerLedgerId)
        );

        // 8. Optional Journal, Cancelled Journal, Memorandum, and Reversing Journal for Exception Reports & Statistics
        postAccountingVoucher(
            companyId, "Journal", "2025-05-18", "OPTIONAL", null,
            "Proforma optional adjustment",
            """
            [
              { "ledgerId": "%s", "side": "DR", "amount": "500.00" },
              { "ledgerId": "%s", "side": "CR", "amount": "500.00" }
            ]
            """.formatted(rentLedgerId, capitalLedgerId)
        );

        UUID toCancelId = postAccountingVoucher(
            companyId, "Journal", "2025-05-19", "NORMAL", null,
            "Journal to be cancelled",
            """
            [
              { "ledgerId": "%s", "side": "DR", "amount": "250.00" },
              { "ledgerId": "%s", "side": "CR", "amount": "250.00" }
            ]
            """.formatted(rentLedgerId, capitalLedgerId)
        );
        MvcResult getVch = mockMvc.perform(authedGet("/api/v1/companies/" + companyId + "/vouchers/" + toCancelId))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        String etag = getVch.getResponse().getHeader("ETag");
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers/" + toCancelId + "/cancel")
                    .header("If-Match", etag)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Duplicate test entry\"}"),
                "w2b-cancel-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        postAccountingVoucher(
            companyId, "Memorandum", "2025-05-20", "MEMORANDUM", null,
            "Non-posting petty cash memo",
            """
            [
              { "ledgerId": "%s", "side": "DR", "amount": "900.00" },
              { "ledgerId": "%s", "side": "CR", "amount": "900.00" }
            ]
            """.formatted(rentLedgerId, capitalLedgerId)
        );

        postAccountingVoucher(
            companyId, "Reversing Journal", "2025-05-21", "REVERSING_JOURNAL", "2025-05-31",
            "Month-end accrual reversing journal",
            """
            [
              { "ledgerId": "%s", "side": "DR", "amount": "1800.00" },
              { "ledgerId": "%s", "side": "CR", "amount": "1800.00" }
            ]
            """.formatted(rentLedgerId, capitalLedgerId)
        );
    }

    @Test
    @Order(2)
    void statisticsReportReturnsAll24VoucherTypesAndAll11MasterCounts() throws Exception {
        MvcResult res = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyId + "/reports/statistics?fromDate=2025-04-01&toDate=2026-03-31")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();

        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsByteArray());
        Assertions.assertThat(body.get("voucherTypeCounts").size()).isGreaterThanOrEqualTo(24);
        Assertions.assertThat(body.get("masterCounts").size()).isEqualTo(11);
        Assertions.assertThat(body.get("totalPostedVouchers").asLong()).isGreaterThanOrEqualTo(9L);
        Assertions.assertThat(body.get("totalOptionalVouchers").asLong()).isEqualTo(1L);
        Assertions.assertThat(body.get("totalCancelledVouchers").asLong()).isEqualTo(1L);

        Map<String, Long> masterByName = new HashMap<>();
        for (JsonNode m : body.get("masterCounts")) {
            masterByName.put(m.get("name").asString(), m.get("count").asLong());
        }
        Assertions.assertThat(masterByName).containsKeys(
            "Groups", "Ledgers", "Stock Groups", "Stock Categories", "Stock Items",
            "Units", "Godowns", "Cost Categories", "Cost Centres", "Currencies", "Voucher Types"
        );
        Assertions.assertThat(masterByName.get("Voucher Types")).isEqualTo(24L);
        Assertions.assertThat(masterByName.get("Currencies")).isEqualTo(1L);
        Assertions.assertThat(masterByName.get("Stock Items")).isEqualTo(2L);
    }

    @Test
    @Order(3)
    void exceptionReportsCoverAll8RequiredViewsWithExactBigDecimalTotals() throws Exception {
        // 1. NEGATIVE_STOCK -> Surgical Mask Box (-5 NOS)
        JsonNode negStock = getJson(
            "/api/v1/companies/" + companyId + "/reports/exceptions?type=NEGATIVE_STOCK&asOfDate=2025-06-30"
        );
        Assertions.assertThat(negStock.get("rowCount").asInt()).isEqualTo(1);
        Assertions.assertThat(negStock.get("rows").get(0).get("particulars").asString()).isEqualTo("Surgical Mask Box");
        Assertions.assertThat(negStock.get("rows").get(0).get("quantity").asString()).isEqualTo("-5");

        // 2. NEGATIVE_LEDGERS -> Cash has 15,000.00 CR (abnormal for ASSETS)
        JsonNode negLedgers = getJson(
            "/api/v1/companies/" + companyId + "/reports/exceptions?type=NEGATIVE_LEDGERS&asOfDate=2025-06-30"
        );
        Assertions.assertThat(negLedgers.get("rowCount").asInt()).isGreaterThanOrEqualTo(1);
        boolean foundCash = false;
        for (JsonNode row : negLedgers.get("rows")) {
            if ("Cash".equals(row.get("particulars").asString())) {
                foundCash = true;
                Assertions.assertThat(row.get("normalSide").asString()).isEqualTo("DR");
                Assertions.assertThat(row.get("actualSide").asString()).isEqualTo("CR");
                Assertions.assertThat(row.get("creditAmount").asString()).isEqualTo("15000.00");
            }
        }
        Assertions.assertThat(foundCash).isTrue();

        // 3. OVERDUE_RECEIVABLES (as of 2025-06-30 -> INV-AP-001 due 2025-04-25 is overdue by 66 days, pending 15000.00)
        JsonNode overdueRec = getJson(
            "/api/v1/companies/" + companyId + "/reports/exceptions?type=OVERDUE_RECEIVABLES&asOfDate=2025-06-30"
        );
        Assertions.assertThat(overdueRec.get("rowCount").asInt()).isEqualTo(1);
        Assertions.assertThat(overdueRec.get("totalAmount").asString()).isEqualTo("15000.00");

        // 4. OVERDUE_PAYABLES (as of 2025-06-30 -> KAV-101 due 2025-04-20 is overdue, pending 23000.00)
        JsonNode overduePay = getJson(
            "/api/v1/companies/" + companyId + "/reports/exceptions?type=OVERDUE_PAYABLES&asOfDate=2025-06-30"
        );
        Assertions.assertThat(overduePay.get("rowCount").asInt()).isEqualTo(1);
        Assertions.assertThat(overduePay.get("totalAmount").asString()).isEqualTo("23000.00");

        // 5..8. OPTIONAL_VOUCHERS, CANCELLED_VOUCHERS, MEMORANDUM_VOUCHERS, REVERSING_JOURNALS
        for (String vType : List.of(
            "OPTIONAL_VOUCHERS", "CANCELLED_VOUCHERS", "MEMORANDUM_VOUCHERS", "REVERSING_JOURNALS"
        )) {
            JsonNode exRes = getJson(
                "/api/v1/companies/" + companyId + "/reports/exceptions?type=" + vType
                    + "&fromDate=2025-04-01&toDate=2025-06-30"
            );
            Assertions.assertThat(exRes.get("rowCount").asInt())
                .as("Expected 1 row for exception view " + vType)
                .isEqualTo(1);
        }
    }

    @Test
    @Order(4)
    void accountBooksAndStockQueryReturnMonthlySummariesVouchersAnd360ItemBreakup() throws Exception {
        // 1. CASH_BANK_SUMMARY: Bank +290,000.00 DR, Cash -15,000.00 CR -> net closing 275,000.00 DR
        JsonNode cashBank = getJson(
            "/api/v1/companies/" + companyId + "/reports/account-books?bookType=CASH_BANK_SUMMARY"
                + "&fromDate=2025-04-01&toDate=2025-05-31"
        );
        Assertions.assertThat(cashBank.get("closingDebit").asString()).isEqualTo("275000.00");
        Assertions.assertThat(cashBank.get("summaryRows").size()).isGreaterThanOrEqualTo(2);
        Assertions.assertThat(cashBank.get("monthlySummary").size()).isEqualTo(2);

        // 2. GROUP_SUMMARY & GROUP_VOUCHERS on Sundry Debtors (15340 + 7552 - 340 = 22552.00 DR)
        JsonNode grpSummary = getJson(
            "/api/v1/companies/" + companyId + "/reports/account-books?bookType=GROUP_SUMMARY&groupId="
                + debtorsGroupId + "&fromDate=2025-04-01&toDate=2025-05-31"
        );
        Assertions.assertThat(grpSummary.get("closingDebit").asString()).isEqualTo("22552.00");

        JsonNode grpVouchers = getJson(
            "/api/v1/companies/" + companyId + "/reports/account-books?bookType=GROUP_VOUCHERS&groupId="
                + debtorsGroupId + "&fromDate=2025-04-01&toDate=2025-05-31"
        );
        Assertions.assertThat(grpVouchers.get("voucherCount").asLong()).isEqualTo(3L);
        Assertions.assertThat(grpVouchers.get("closingDebit").asString()).isEqualTo("22552.00");

        // 3. All 8 Voucher Registers return exact monthly buckets & totals
        Map<String, String> expectedRegisterTotals = Map.of(
            "SALES_REGISTER", "22892.00",
            "PURCHASE_REGISTER", "23600.00",
            "JOURNAL_REGISTER", "1200.00",
            "CONTRA_REGISTER", "10000.00",
            "PAYMENT_REGISTER", "25000.00",
            "RECEIPT_REGISTER", "300000.00",
            "DEBIT_NOTE_REGISTER", "600.00",
            "CREDIT_NOTE_REGISTER", "340.00"
        );
        for (Map.Entry<String, String> entry : expectedRegisterTotals.entrySet()) {
            JsonNode reg = getJson(
                "/api/v1/companies/" + companyId + "/reports/account-books?bookType=" + entry.getKey()
                    + "&fromDate=2025-04-01&toDate=2025-05-31"
            );
            Assertions.assertThat(reg.get("totalDebit").asString())
                .as("Register totalDebit for " + entry.getKey())
                .isEqualTo(entry.getValue());
            Assertions.assertThat(reg.get("monthlySummary").size()).isEqualTo(2);
        }

        // 4. Stock Query 360-degree single-item view on Pulse Oximeter Pro:
        //    Inward 20 @ 1000 = 20000.00; Outward (8 + 4 = 12); Closing 8 @ 1000.00 = 8000.00
        JsonNode sq = getJson(
            "/api/v1/companies/" + companyId + "/reports/stock-query?stockItemId=" + oximeterItemId
                + "&fromDate=2025-04-01&toDate=2025-05-31"
        );
        Assertions.assertThat(sq.get("stockItemName").asString()).isEqualTo("Pulse Oximeter Pro");
        Assertions.assertThat(sq.get("inwardQty").asString()).isEqualTo("20");
        Assertions.assertThat(sq.get("outwardQty").asString()).isEqualTo("12");
        Assertions.assertThat(sq.get("closingQty").asString()).isEqualTo("8");
        Assertions.assertThat(sq.get("closingValue").asString()).isEqualTo("8000.00");
        Assertions.assertThat(sq.get("lastPurchaseParty").asString()).isEqualTo("Kaveri Meditech Suppliers");
        Assertions.assertThat(sq.get("lastPurchaseRate").asString()).isEqualTo("1000.00");
        Assertions.assertThat(sq.get("lastSalesParty").asString()).isEqualTo("Apollo Diagnostics Centre");
        Assertions.assertThat(sq.get("lastSalesRate").asString()).isEqualTo("1600.00");
        Assertions.assertThat(sq.get("movements").size()).isEqualTo(3);
    }

    @Test
    @Order(5)
    void voucherAdjacentPageUpPageDownAndCtrlRLastNarrationHelpers() throws Exception {
        // 1. Unsaved Sales entry pressing PageUp (PREV) lands on latest Sales voucher (salesVch2Id)
        JsonNode prevFromUnsaved = getJson(
            "/api/v1/companies/" + companyId + "/vouchers/adjacent?voucherCategory=SALES&direction=PREV"
        );
        Assertions.assertThat(prevFromUnsaved.get("found").asBoolean()).isTrue();
        Assertions.assertThat(prevFromUnsaved.get("adjacentVoucherId").asString()).isEqualTo(salesVch2Id.toString());
        Assertions.assertThat(prevFromUnsaved.get("hasPrevious").asBoolean()).isTrue();
        Assertions.assertThat(prevFromUnsaved.get("hasNext").asBoolean()).isFalse();

        // 2. Pressing PageUp (PREV) again from salesVch2Id lands on salesVch1Id
        JsonNode prevToFirst = getJson(
            "/api/v1/companies/" + companyId + "/vouchers/adjacent?currentVoucherId=" + salesVch2Id
                + "&voucherCategory=SALES&direction=PREV"
        );
        Assertions.assertThat(prevToFirst.get("found").asBoolean()).isTrue();
        Assertions.assertThat(prevToFirst.get("adjacentVoucherId").asString()).isEqualTo(salesVch1Id.toString());
        Assertions.assertThat(prevToFirst.get("hasPrevious").asBoolean()).isFalse();
        Assertions.assertThat(prevToFirst.get("hasNext").asBoolean()).isTrue();

        // 3. Pressing PageDown (NEXT) from salesVch1Id steps forward to salesVch2Id
        JsonNode nextToSecond = getJson(
            "/api/v1/companies/" + companyId + "/vouchers/adjacent?currentVoucherId=" + salesVch1Id
                + "&voucherCategory=SALES&direction=NEXT"
        );
        Assertions.assertThat(nextToSecond.get("found").asBoolean()).isTrue();
        Assertions.assertThat(nextToSecond.get("adjacentVoucherId").asString()).isEqualTo(salesVch2Id.toString());

        // 4. Ctrl+R last-narration on SALES returns Sales #2 narration; on PAYMENT returns Rent narration
        JsonNode lastSalesNarr = getJson(
            "/api/v1/companies/" + companyId + "/vouchers/last-narration?voucherCategory=SALES"
        );
        Assertions.assertThat(lastSalesNarr.get("found").asBoolean()).isTrue();
        Assertions.assertThat(lastSalesNarr.get("narration").asString())
            .isEqualTo("May follow-up supply to Apollo Diagnostics");

        JsonNode lastPaymentNarr = getJson(
            "/api/v1/companies/" + companyId + "/vouchers/last-narration?voucherCategory=PAYMENT&ledgerId="
                + cashLedgerId
        );
        Assertions.assertThat(lastPaymentNarr.get("found").asBoolean()).isTrue();
        Assertions.assertThat(lastPaymentNarr.get("narration").asString())
            .isEqualTo("April warehouse rent paid in cash");
    }

    // =========================================================================
    // Test Helpers
    // =========================================================================

    private JsonNode getJson(String uri) throws Exception {
        MvcResult res = mockMvc.perform(authedGet(uri))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsByteArray());
    }

    private UUID createTestCompany(
        String code,
        String legalName,
        String stateCode,
        String fyStart,
        String gstin
    ) throws Exception {
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
                          "mcaEditLogEnforced": true,
                          "features": {
                            "maintainAccounts": true,
                            "maintainInventory": true,
                            "billWiseEnabled": true,
                            "costCentresEnabled": true,
                            "gstEnabled": true,
                            "tdsEnabled": true,
                            "payrollEnabled": false,
                            "gstRegistrationType": "REGULAR",
                            "gstin": "%s",
                            "mcaEditLogEnforced": true
                          }
                        }
                        """.formatted(code, legalName, stateCode, fyStart, fyStart, gstin)
                    ),
                "w2b-cmp-" + code
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private UUID findGroupIdByName(UUID targetCompanyId, String groupName) throws Exception {
        MvcResult res = mockMvc.perform(authedGet("/api/v1/companies/" + targetCompanyId + "/account-groups?limit=100"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode page = objectMapper.readTree(res.getResponse().getContentAsByteArray());
        for (JsonNode g : page.get("items")) {
            if (groupName.equals(g.get("name").asString())) {
                return UUID.fromString(g.get("id").asString());
            }
        }
        throw new IllegalStateException("Group not found: " + groupName);
    }

    private UUID findLedgerIdByName(UUID targetCompanyId, String ledgerName) throws Exception {
        MvcResult res = mockMvc.perform(
            authedGet("/api/v1/companies/" + targetCompanyId + "/ledgers?limit=50&q=" + ledgerName)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode page = objectMapper.readTree(res.getResponse().getContentAsByteArray());
        for (JsonNode l : page.get("items")) {
            if (ledgerName.equals(l.get("name").asString())) {
                return UUID.fromString(l.get("id").asString());
            }
        }
        throw new IllegalStateException("Ledger not found: " + ledgerName);
    }

    private UUID createSimpleLedger(UUID targetCompanyId, String name, UUID groupId) throws Exception {
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "groupId": "%s",
                          "openingBalance": "0.00",
                          "openingSide": "DR"
                        }
                        """.formatted(name, groupId)
                    ),
                "w2b-ldg-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private UUID createGstDutyLedger(UUID targetCompanyId, String name, UUID groupId, String dutyHead) throws Exception {
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "groupId": "%s",
                          "taxType": "GST",
                          "dutyHead": "%s",
                          "openingBalance": "0.00",
                          "openingSide": "CR"
                        }
                        """.formatted(name, groupId, dutyHead)
                    ),
                "w2b-gst-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private UUID createBillWisePartyLedger(
        UUID targetCompanyId,
        String name,
        UUID groupId,
        String stateCode,
        String gstin,
        String openingSide
    ) throws Exception {
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "groupId": "%s",
                          "partyRegistrationType": "REGULAR",
                          "countryCode": "IN",
                          "stateCode": "%s",
                          "gstin": "%s",
                          "maintainBalancesBillByBill": true,
                          "defaultCreditPeriodDays": 10,
                          "openingBalance": "0.00",
                          "openingSide": "%s"
                        }
                        """.formatted(name, groupId, stateCode, gstin, openingSide)
                    ),
                "w2b-pty-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private UUID createSimpleUom(
        UUID targetCompanyId,
        String symbol,
        String formalName,
        String uqc,
        int decimals
    ) throws Exception {
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/uoms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "uomType": "SIMPLE",
                          "symbol": "%s",
                          "formalName": "%s",
                          "gstUqcCode": "%s",
                          "decimalPlaces": %d
                        }
                        """.formatted(symbol, formalName, uqc, decimals)
                    ),
                "w2b-uom-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private UUID createStockGroup(UUID targetCompanyId, String name) throws Exception {
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/stock-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"%s\"}".formatted(name)),
                "w2b-sgrp-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private UUID createStockItem(
        UUID targetCompanyId,
        String name,
        UUID stockGroupId,
        UUID baseUomId,
        String costingMethod,
        String hsnSacCode,
        String gstRatePct,
        UUID salesLedgerId,
        UUID purchLedgerId
    ) throws Exception {
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/stock-items")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "stockGroupId": "%s",
                          "baseUomId": "%s",
                          "costingMethod": "%s",
                          "hsnSacCode": "%s",
                          "gstRatePct": "%s",
                          "defaultSalesLedgerId": "%s",
                          "defaultPurchaseLedgerId": "%s"
                        }
                        """.formatted(
                            name, stockGroupId, baseUomId, costingMethod, hsnSacCode,
                            gstRatePct, salesLedgerId, purchLedgerId
                        )
                    ),
                "w2b-sitem-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private UUID postAccountingVoucher(
        UUID targetCompanyId,
        String voucherTypeName,
        String voucherDate,
        String postingNature,
        String reversingUpto,
        String narration,
        String linesJson
    ) throws Exception {
        String revJson = reversingUpto == null ? "null" : "\"" + reversingUpto + "\"";
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "%s",
                          "voucherDate": "%s",
                          "persistedView": "AS_VOUCHER",
                          "postingNature": "%s",
                          "reversingApplicableUpto": %s,
                          "action": "POST",
                          "narration": "%s",
                          "lines": %s
                        }
                        """.formatted(voucherTypeName, voucherDate, postingNature, revJson, narration, linesJson)
                    ),
                "w2b-vch-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private UUID postItemInvoice(
        UUID targetCompanyId,
        String voucherTypeName,
        String voucherDate,
        String supplierInvoiceNo,
        UUID partyLedgerId,
        UUID accountingLedgerId,
        String posState,
        String narration,
        String inventoryLinesJson,
        String linesJson
    ) throws Exception {
        String suppNoJson = supplierInvoiceNo == null ? "null" : "\"" + supplierInvoiceNo + "\"";
        String suppDateJson = supplierInvoiceNo == null ? "null" : "\"" + voucherDate + "\"";
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "%s",
                          "voucherDate": "%s",
                          "persistedView": "ITEM_INVOICE",
                          "action": "POST",
                          "supplierInvoiceNo": %s,
                          "supplierInvoiceDate": %s,
                          "partyLedgerId": "%s",
                          "accountingLedgerId": "%s",
                          "placeOfSupplyState": "%s",
                          "narration": "%s",
                          "inventoryLines": %s,
                          "lines": %s
                        }
                        """.formatted(
                            voucherTypeName, voucherDate, suppNoJson, suppDateJson,
                            partyLedgerId, accountingLedgerId, posState, narration,
                            inventoryLinesJson, linesJson
                        )
                    ),
                "w2b-inv-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private static MockHttpServletRequestBuilder authedGet(String uri) {
        return MockMvcRequestBuilders.get(uri).cookie(ownerSessionCookie, ownerXsrfCookie);
    }

    private static MockHttpServletRequestBuilder authedMutate(
        MockHttpServletRequestBuilder builder,
        String idempotencyKey
    ) {
        return builder
            .cookie(ownerSessionCookie, ownerXsrfCookie)
            .header("X-XSRF-TOKEN", URLDecoder.decode(ownerXsrfCookie.getValue(), StandardCharsets.UTF_8))
            .header("Idempotency-Key", idempotencyKey);
    }

    private static Cookie latestCookie(MvcResult result, String name) {
        Cookie[] cookies = result.getResponse().getCookies();
        Cookie found = null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isEmpty()) {
                found = c;
            }
        }
        return found;
    }
}
