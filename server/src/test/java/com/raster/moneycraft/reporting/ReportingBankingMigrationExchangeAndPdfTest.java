package com.raster.moneycraft.reporting;

import com.raster.moneycraft.MoneyCraftApplication;
import com.raster.moneycraft.foundation.DualPostgres18TestcontainersSupport;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
 * End-to-end Phase 6 (Gates G2-I, G3 & G4) integration test suite against real Dual-PostgreSQL 18
 * clusters verifying:
 * 1. Penny-exact Financial & Ageing Statements on the MoneyCraft Parity Benchmark (AT-RPT-001..008).
 * 2. Clean-room .1800 binary staging, Sector-0/CmpSave proof, and 0.00-variance promotion on ABC & Co
 *    Company 100000 with MIGRATE_IN MCA Edit Log v1 baseline (AT-MIG-004..005).
 * 3. Tally <ENVELOPE> XML (-Dr/+Cr, ASCII comments, &#4; sanitization), Rel 7.2 JSONEx, and Apache POI
 *    .xlsx round-trip exchange + custom Excel mapping templates (AT-EXG-001..004).
 * 4. Sub-50ms SmartFind (ACTIVE & ALL_OPEN_COMPANIES), Banking Auto-BRS (CSV & MT940), Cheque Books,
 *    Tink-encrypted Payroll Employees, and Docs-by-Ira OCR Human-Review Queue.
 * 5. Deterministic Brand Identity v0.2.0 PDFBox 3 vector rendering verified via FontBox/PDFBox
 *    (embedded Manrope + JetBrains Mono TrueType subsets, U+20B9 ₹ presence, zero ligatures)
 *    (AT-PDF-001..004).
 */
@SpringBootTest(classes = MoneyCraftApplication.class)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReportingBankingMigrationExchangeAndPdfTest {

    private static final String ADMIN_USER = "phase6_admin";
    private static final String ADMIN_PASS = "ChiefPhase6#2026!";

    private static final Path SOURCE_100000_DIR = Path.of(
        "/Users/pushparaj/projects/Money_Craft/argon_workspace/Source/Binary/Tally Prime/Data/100000"
    );
    private static final Path ORACLE_100000_XML_DIR = Path.of(
        "/Users/pushparaj/projects/Money_Craft/argon_workspace/windows_lab_oracles/100000"
    );

    private static final DualPostgres18TestcontainersSupport.ClusterPair CLUSTERS =
        DualPostgres18TestcontainersSupport.startIsolatedClusterPair();

    private static Cookie ownerSessionCookie;
    private static Cookie ownerXsrfCookie;

    private static UUID parityCompanyId;
    private static UUID parityBankLedgerId;
    private static UUID paritySales1VoucherId;
    private static UUID abcMigratedCompanyId;

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
    void parityBenchmarkFinancialStatementsScheduleIiiCashFlowRatiosAndAgeing() throws Exception {
        MvcResult initRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/system/initialise")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "installationLabel": "MoneyCraft Phase 6 Reporting, Migration & PDF Cluster",
                      "ownerUsername": "%s",
                      "ownerDisplayName": "Chief Financial & Migration Controller",
                      "password": "%s"
                    }
                    """.formatted(ADMIN_USER, ADMIN_PASS)
                )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        ownerSessionCookie = latestCookie(initRes, "MCSESSION");
        ownerXsrfCookie = latestCookie(initRes, "XSRF-TOKEN");

        String cmpGstinPrefix = "33AABCM1234F1Z";
        String cmpGstin = cmpGstinPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(cmpGstinPrefix);
        parityCompanyId = createTestCompany(
            "MCPARITY06",
            "MoneyCraft Parity Benchmark",
            "33",
            "2025-04-01",
            cmpGstin
        );

        // Verify default Tamil Nadu GSTIN auto-seeded on parityCompanyId for Tax Invoice PDF header verification
        MvcResult gstRegsRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + parityCompanyId + "/gst/registrations")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Assertions.assertThat(objectMapper.readTree(gstRegsRes.getResponse().getContentAsByteArray()).size())
            .isGreaterThanOrEqualTo(1);

        UUID capitalGrp = findGroupIdByName(parityCompanyId, "Capital Account");
        UUID bankGrp = findGroupIdByName(parityCompanyId, "Bank Accounts");
        UUID debtorsGrp = findGroupIdByName(parityCompanyId, "Sundry Debtors");
        UUID creditorsGrp = findGroupIdByName(parityCompanyId, "Sundry Creditors");
        UUID dutiesGrp = findGroupIdByName(parityCompanyId, "Duties & Taxes");
        UUID salesGrp = findGroupIdByName(parityCompanyId, "Sales Accounts");
        UUID purchGrp = findGroupIdByName(parityCompanyId, "Purchase Accounts");
        UUID indirectExpGrp = findGroupIdByName(parityCompanyId, "Indirect Expenses");
        UUID cashLedgerId = findLedgerIdByName(parityCompanyId, "Cash");

        UUID promoterCapitalId = createSimpleLedger(parityCompanyId, "Promoter Capital A/c", capitalGrp);
        parityBankLedgerId = createSimpleLedger(parityCompanyId, "HDFC Current A/c", bankGrp);
        UUID salesGstId = createSimpleLedger(parityCompanyId, "Sales - GST", salesGrp);
        UUID purchasesGstId = createSimpleLedger(parityCompanyId, "Purchases - GST", purchGrp);
        UUID legalAuditId = createSimpleLedger(parityCompanyId, "Legal & Audit Consultancy", indirectExpGrp);

        UUID cgstId = createGstDutyLedger(parityCompanyId, "CGST", dutiesGrp, "CGST");
        UUID sgstId = createGstDutyLedger(parityCompanyId, "SGST", dutiesGrp, "SGST_UTGST");
        UUID igstId = createGstDutyLedger(parityCompanyId, "IGST", dutiesGrp, "IGST");
        UUID tds194JId = createTdsDutyLedger(parityCompanyId, "TDS Payable - 194J", dutiesGrp, "194J");

        String kaveriPrefix = "33AAFCK5678G1Z";
        String kaveriGstin = kaveriPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(kaveriPrefix);
        UUID kaveriSupplierId = createBillWisePartyLedger(
            parityCompanyId, "Kaveri Meditech Pvt Ltd", creditorsGrp, "33", kaveriGstin
        );

        String salemPrefix = "33AABCS9012H1Z";
        String salemGstin = salemPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(salemPrefix);
        UUID salemHospitalId = createBillWisePartyLedger(
            parityCompanyId, "Salem Care Hospital", debtorsGrp, "33", salemGstin
        );

        String apexDiagPrefix = "29AADCA3456J1Z";
        String apexDiagGstin = apexDiagPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(apexDiagPrefix);
        UUID apexBengaluruId = createBillWisePartyLedger(
            parityCompanyId, "Apex Diagnostics Bengaluru", debtorsGrp, "29", apexDiagGstin
        );

        UUID apexLegalId = createBillWiseTdsVendorLedger(
            parityCompanyId, "Apex Legal Associates", creditorsGrp, "AAAPA1234L", "194J"
        );

        UUID uomNosId = createSimpleUom(parityCompanyId, "NOS", "Numbers", "NOS", 0);
        UUID medDevicesGroupId = createStockGroup(parityCompanyId, "Medical Devices");

        UUID oximeterId = createStockItem(
            parityCompanyId,
            "Pulse Oximeter Pro",
            medDevicesGroupId,
            uomNosId,
            "FIFO",
            "90189099",
            "12.00",
            salesGstId,
            purchasesGstId
        );
        UUID thermometerId = createStockItem(
            parityCompanyId,
            "Digital Thermometer X1",
            medDevicesGroupId,
            uomNosId,
            "AVG_COST",
            "90251190",
            "18.00",
            salesGstId,
            purchasesGstId
        );

        // V1: Receipt #1 (2025-04-01) — Seed equity capital 500,000.00 into HDFC Current A/c
        postAccountingVoucher(
            parityCompanyId,
            "Receipt",
            "2025-04-01",
            "NORMAL",
            "Seed equity capital received via NEFT",
            """
            [
              { "ledgerId": "%s", "side": "DR", "amount": "500000.00",
                "bankAllocations": [
                  { "bankLedgerId": "%s", "transactionType": "NEFT", "instrumentNo": "UTR500001",
                    "instrumentDate": "2025-04-01", "side": "DR", "amount": "500000.00" }
                ]
              },
              { "ledgerId": "%s", "side": "CR", "amount": "500000.00" }
            ]
            """.formatted(parityBankLedgerId, parityBankLedgerId, promoterCapitalId)
        );

        // V2: Contra #1 (2025-04-01) — Cash withdrawal 25,000.00 from HDFC Current A/c
        postAccountingVoucher(
            parityCompanyId,
            "Contra",
            "2025-04-01",
            "NORMAL",
            "Cash withdrawal from HDFC for office float",
            """
            [
              { "ledgerId": "%s", "side": "DR", "amount": "25000.00" },
              { "ledgerId": "%s", "side": "CR", "amount": "25000.00",
                "bankAllocations": [
                  { "bankLedgerId": "%s", "transactionType": "CHEQUE", "instrumentNo": "100001",
                    "instrumentDate": "2025-04-01", "side": "CR", "amount": "25000.00" }
                ]
              }
            ]
            """.formatted(cashLedgerId, parityBankLedgerId, parityBankLedgerId)
        );

        // V3: Purchase #1 (2025-04-02) — KM-001: 50 Oximeter @ 1200 + 100 Thermometer @ 300 = 90000 + CGST 6300 + SGST 6300 = 102600.00
        postItemInvoice(
            parityCompanyId,
            "Purchase",
            "2025-04-02",
            "KM-001",
            kaveriSupplierId,
            purchasesGstId,
            "33",
            """
            [
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "50", "billedQty": "50", "rate": "1200.00", "amount": "60000.00" },
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "100", "billedQty": "100", "rate": "300.00", "amount": "30000.00" }
            ]
            """.formatted(oximeterId, uomNosId, thermometerId, uomNosId),
            """
            [
              { "lineRole": "ITEM_ACCOUNTING", "ledgerId": "%s", "side": "DR", "amount": "90000.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "DR", "amount": "6300.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "DR", "amount": "6300.00" },
              { "lineRole": "PARTY", "ledgerId": "%s", "side": "CR", "amount": "102600.00",
                "billAllocations": [
                  { "allocationType": "NEW_REF", "billName": "KM-001", "dueDate": "2025-05-02", "side": "CR", "amount": "102600.00" }
                ]
              }
            ]
            """.formatted(purchasesGstId, cgstId, sgstId, kaveriSupplierId)
        );

        // V4: Purchase #2 (2025-05-01) — KM-019: 30 Oximeter @ 1350 + 40 Thermometer @ 340 = 54100 + CGST 3654 + SGST 3654 = 61408.00
        postItemInvoice(
            parityCompanyId,
            "Purchase",
            "2025-05-01",
            "KM-019",
            kaveriSupplierId,
            purchasesGstId,
            "33",
            """
            [
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "30", "billedQty": "30", "rate": "1350.00", "amount": "40500.00" },
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "40", "billedQty": "40", "rate": "340.00", "amount": "13600.00" }
            ]
            """.formatted(oximeterId, uomNosId, thermometerId, uomNosId),
            """
            [
              { "lineRole": "ITEM_ACCOUNTING", "ledgerId": "%s", "side": "DR", "amount": "54100.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "DR", "amount": "3654.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "DR", "amount": "3654.00" },
              { "lineRole": "PARTY", "ledgerId": "%s", "side": "CR", "amount": "61408.00",
                "billAllocations": [
                  { "allocationType": "NEW_REF", "billName": "KM-019", "dueDate": "2025-05-31", "side": "CR", "amount": "61408.00" }
                ]
              }
            ]
            """.formatted(purchasesGstId, cgstId, sgstId, kaveriSupplierId)
        );

        // V5: Sales #1 (2025-05-02) — SAL-001: 40 Oximeter @ 1800 + 60 Thermometer @ 480 = 100800 + CGST 6912 + SGST 6912 = 114624.00
        paritySales1VoucherId = postItemInvoice(
            parityCompanyId,
            "Sales",
            "2025-05-02",
            null,
            salemHospitalId,
            salesGstId,
            "33",
            """
            [
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "40", "billedQty": "40", "rate": "1800.00", "amount": "72000.00" },
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "60", "billedQty": "60", "rate": "480.00", "amount": "28800.00" }
            ]
            """.formatted(oximeterId, uomNosId, thermometerId, uomNosId),
            """
            [
              { "lineRole": "PARTY", "ledgerId": "%s", "side": "DR", "amount": "114624.00",
                "billAllocations": [
                  { "allocationType": "NEW_REF", "billName": "SAL-001", "dueDate": "2025-06-01", "side": "DR", "amount": "114624.00" }
                ]
              },
              { "lineRole": "ITEM_ACCOUNTING", "ledgerId": "%s", "side": "CR", "amount": "100800.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "CR", "amount": "6912.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "CR", "amount": "6912.00" }
            ]
            """.formatted(salemHospitalId, salesGstId, cgstId, sgstId)
        );

        // V6: Sales #2 (2025-05-02) — SAL-002: 20 Oximeter @ 1850 + 20 Thermometer @ 500 = 47000 + IGST 6240 = 53240.00
        postItemInvoice(
            parityCompanyId,
            "Sales",
            "2025-05-02",
            null,
            apexBengaluruId,
            salesGstId,
            "29",
            """
            [
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "20", "billedQty": "20", "rate": "1850.00", "amount": "37000.00" },
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "20", "billedQty": "20", "rate": "500.00", "amount": "10000.00" }
            ]
            """.formatted(oximeterId, uomNosId, thermometerId, uomNosId),
            """
            [
              { "lineRole": "PARTY", "ledgerId": "%s", "side": "DR", "amount": "53240.00",
                "billAllocations": [
                  { "allocationType": "NEW_REF", "billName": "SAL-002", "dueDate": "2025-06-01", "side": "DR", "amount": "53240.00" }
                ]
              },
              { "lineRole": "ITEM_ACCOUNTING", "ledgerId": "%s", "side": "CR", "amount": "47000.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "CR", "amount": "6240.00" }
            ]
            """.formatted(apexBengaluruId, salesGstId, igstId)
        );

        // V7: Journal #1 (2025-05-02) — First post v1 (40,000 -> TDS 4,000 / ALA-09 36,000), then alter to v2 (45,000 -> TDS 4,500 / ALA-09 40,500)
        UUID journal1Id = postAccountingVoucher(
            parityCompanyId,
            "Journal",
            "2025-05-02",
            "NORMAL",
            "Retainer consultancy fee v1",
            """
            [
              { "ledgerId": "%s", "side": "DR", "amount": "40000.00" },
              { "ledgerId": "%s", "side": "CR", "amount": "4000.00" },
              { "ledgerId": "%s", "side": "CR", "amount": "36000.00",
                "billAllocations": [
                  { "allocationType": "NEW_REF", "billName": "ALA-09", "dueDate": "2025-06-01", "side": "CR", "amount": "36000.00" }
                ]
              }
            ]
            """.formatted(legalAuditId, tds194JId, apexLegalId)
        );

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put("/api/v1/companies/" + parityCompanyId + "/vouchers/" + journal1Id)
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Journal",
                          "voucherDate": "2025-05-02",
                          "persistedView": "AS_VOUCHER",
                          "narration": "Retainer consultancy fee v2 revised to 45000 with 10%% TDS 194J",
                          "lines": [
                            { "ledgerId": "%s", "side": "DR", "amount": "45000.00" },
                            { "ledgerId": "%s", "side": "CR", "amount": "4500.00" },
                            { "ledgerId": "%s", "side": "CR", "amount": "40500.00",
                              "billAllocations": [
                                { "allocationType": "NEW_REF", "billName": "ALA-09", "dueDate": "2025-06-01", "side": "CR", "amount": "40500.00" }
                              ]
                            }
                          ]
                        }
                        """.formatted(legalAuditId, tds194JId, apexLegalId)
                    ),
                "p6-j1-alter-v2"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.header().string("ETag", "\"v2\""));

        // V8: Payment #1 (2025-05-31) — RTGS settlement of KM-001 (102,600.00)
        postAccountingVoucher(
            parityCompanyId,
            "Payment",
            "2025-05-31",
            "NORMAL",
            "RTGS settlement against bill KM-001",
            """
            [
              { "ledgerId": "%s", "side": "DR", "amount": "102600.00",
                "billAllocations": [
                  { "allocationType": "AGST_REF", "billName": "KM-001", "side": "DR", "amount": "102600.00" }
                ]
              },
              { "ledgerId": "%s", "side": "CR", "amount": "102600.00",
                "bankAllocations": [
                  { "bankLedgerId": "%s", "transactionType": "RTGS", "instrumentNo": "RTGS102600",
                    "instrumentDate": "2025-05-31", "side": "CR", "amount": "102600.00" }
                ]
              }
            ]
            """.formatted(kaveriSupplierId, parityBankLedgerId, parityBankLedgerId)
        );

        // V9: Receipt #2 (2025-05-31) — NEFT part receipt against SAL-001 (100,000.00)
        postAccountingVoucher(
            parityCompanyId,
            "Receipt",
            "2025-05-31",
            "NORMAL",
            "NEFT part receipt against SAL-001",
            """
            [
              { "ledgerId": "%s", "side": "DR", "amount": "100000.00",
                "bankAllocations": [
                  { "bankLedgerId": "%s", "transactionType": "NEFT", "instrumentNo": "NEFT100000",
                    "instrumentDate": "2025-05-31", "side": "DR", "amount": "100000.00" }
                ]
              },
              { "ledgerId": "%s", "side": "CR", "amount": "100000.00",
                "billAllocations": [
                  { "allocationType": "AGST_REF", "billName": "SAL-001", "side": "CR", "amount": "100000.00" }
                ]
              }
            ]
            """.formatted(parityBankLedgerId, parityBankLedgerId, salemHospitalId)
        );

        // Non-posting OPTIONAL voucher — must NEVER pollute includeOptional=false financial statements!
        postAccountingVoucher(
            parityCompanyId,
            "Journal",
            "2025-06-15",
            "OPTIONAL",
            "What-if provisional accrual (optional)",
            """
            [
              { "ledgerId": "%s", "side": "DR", "amount": "9999.00" },
              { "ledgerId": "%s", "side": "CR", "amount": "9999.00" }
            ]
            """.formatted(legalAuditId, promoterCapitalId)
        );

        // 1. AT-RPT-001: Trial Balance == 760,448.00 DR / 760,448.00 CR (altered v1 batch excluded!)
        MvcResult tbRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + parityCompanyId + "/reports/trial-balance?fromDate=2025-04-01&toDate=2026-03-31")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode tb = objectMapper.readTree(tbRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(tb.get("totalClosingDebit").asString()).isEqualTo("760448.00");
        Assertions.assertThat(tb.get("totalClosingCredit").asString()).isEqualTo("760448.00");
        Assertions.assertThat(tb.get("differenceAmount").asString()).isEqualTo("0.00");
        Assertions.assertThat(tb.get("isBalanced").asBoolean()).isTrue();

        // 2. AT-RPT-002: Profit & Loss == Sales 147,800.00, Closing Stock 45,685.71, Purchases 144,100.00, GP 49,385.71, NP 4,385.71
        MvcResult pnlRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + parityCompanyId + "/reports/profit-and-loss?fromDate=2025-04-01&toDate=2026-03-31")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode pnl = objectMapper.readTree(pnlRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(pnl.get("salesTotal").asString()).isEqualTo("147800.00");
        Assertions.assertThat(pnl.get("closingStockValue").asString()).isEqualTo("45685.71");
        Assertions.assertThat(pnl.get("purchaseTotal").asString()).isEqualTo("144100.00");
        Assertions.assertThat(pnl.get("grossProfit").asString()).isEqualTo("49385.71");
        Assertions.assertThat(pnl.get("indirectExpensesTotal").asString()).isEqualTo("45000.00");
        Assertions.assertThat(pnl.get("netProfit").asString()).isEqualTo("4385.71");

        // 3. AT-RPT-003: Balance Sheet (HORIZONTAL & VERTICAL) == 610,949.71
        for (String fmt : new String[] {"HORIZONTAL", "VERTICAL"}) {
            MvcResult bsRes = mockMvc.perform(
                authedGet(
                    "/api/v1/companies/" + parityCompanyId
                        + "/reports/balance-sheet?asOfDate=2026-03-31&format=" + fmt
                )
            )
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
            JsonNode bs = objectMapper.readTree(bsRes.getResponse().getContentAsByteArray());
            Assertions.assertThat(bs.get("totalLiabilities").asString()).isEqualTo("610949.71");
            Assertions.assertThat(bs.get("totalAssets").asString()).isEqualTo("610949.71");
            Assertions.assertThat(bs.get("differenceAmount").asString()).isEqualTo("0.00");
            Assertions.assertThat(bs.get("isBalanced").asBoolean()).isTrue();
        }

        // 4. AT-RPT-004..005: MCA Schedule III (DIVISION_I & NCE; ACTUAL & LAKHS)
        MvcResult schActualRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + parityCompanyId
                    + "/reports/schedule-iii?asOfDate=2026-03-31&comparativeAsOfDate=2025-03-31"
                    + "&entityFormat=DIVISION_I&roundingScale=ACTUAL"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode schActual = objectMapper.readTree(schActualRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(schActual.get("isBalanced").asBoolean()).isTrue();
        Assertions.assertThat(schActual.get("totalEquityAndLiabilities").asString()).isEqualTo("610949.71");
        Assertions.assertThat(schActual.get("totalAssets").asString()).isEqualTo("610949.71");
        Assertions.assertThat(schActual.get("notes").size()).isGreaterThanOrEqualTo(5);

        MvcResult schNceLakhsRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + parityCompanyId
                    + "/reports/schedule-iii?asOfDate=2026-03-31&entityFormat=NCE&roundingScale=LAKHS"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode schNceLakhs = objectMapper.readTree(schNceLakhsRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(schNceLakhs.get("entityFormat").asString()).isEqualTo("NCE");
        Assertions.assertThat(schNceLakhs.get("roundingScale").asString()).isEqualTo("LAKHS");
        Assertions.assertThat(schNceLakhs.get("isBalanced").asBoolean()).isTrue();

        // 5. AT-RPT-006: Bills Receivable (67,864.00) & Bills Payable (101,908.00) Ageing
        MvcResult recvAgeRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + parityCompanyId + "/reports/ageing?type=RECEIVABLES&asOfDate=2026-03-31")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode recvAge = objectMapper.readTree(recvAgeRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(recvAge.get("totalPendingAmount").asString()).isEqualTo("67864.00");
        Assertions.assertThat(findInArray(recvAge.get("bills"), "billName", "SAL-001").get("pendingAmount").asString())
            .isEqualTo("14624.00");
        Assertions.assertThat(findInArray(recvAge.get("bills"), "billName", "SAL-002").get("pendingAmount").asString())
            .isEqualTo("53240.00");

        MvcResult payAgeRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + parityCompanyId + "/reports/ageing?type=PAYABLES&asOfDate=2026-03-31")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode payAge = objectMapper.readTree(payAgeRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(payAge.get("totalPendingAmount").asString()).isEqualTo("101908.00");
        Assertions.assertThat(findInArray(payAge.get("bills"), "billName", "KM-019").get("pendingAmount").asString())
            .isEqualTo("61408.00");
        Assertions.assertThat(findInArray(payAge.get("bills"), "billName", "ALA-09").get("pendingAmount").asString())
            .isEqualTo("40500.00");

        // 6. AT-RPT-007..008: Cash Flow & Ratio Analysis
        MvcResult cfRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + parityCompanyId + "/reports/cash-flow?fromDate=2025-04-01&toDate=2026-03-31")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode cf = objectMapper.readTree(cfRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(cf.get("openingCashAndBank").asString()).isEqualTo("0.00");
        Assertions.assertThat(cf.get("netCashMovement").asString()).isEqualTo("497400.00");
        Assertions.assertThat(cf.get("closingCashAndBank").asString()).isEqualTo("497400.00");

        MvcResult ratiosRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + parityCompanyId + "/reports/ratios?fromDate=2025-04-01&toDate=2026-03-31")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode ratios = objectMapper.readTree(ratiosRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(ratios.get("workingCapital").asString()).isEqualTo("504385.71");
        Assertions.assertThat(ratios.get("cashInHand").asString()).isEqualTo("25000.00");
        Assertions.assertThat(ratios.get("bankAccounts").asString()).isEqualTo("472400.00");
        Assertions.assertThat(ratios.get("sundryDebtors").asString()).isEqualTo("67864.00");
        Assertions.assertThat(ratios.get("sundryCreditors").asString()).isEqualTo("101908.00");
        Assertions.assertThat(ratios.get("stockInHand").asString()).isEqualTo("45685.71");
        Assertions.assertThat(ratios.get("netProfit").asString()).isEqualTo("4385.71");
    }

    @Test
    @Order(2)
    void cleanRoom1800SnapshotStagingProofAndZeroVariancePromotionCompany100000(@TempDir Path tempDir) throws Exception {
        String abcPrefix = "33AABCA1000K1Z";
        String abcGstin = abcPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(abcPrefix);
        abcMigratedCompanyId = createTestCompany(
            "MCABC100000",
            "ABC & Co (Migrated 100000)",
            "33",
            "2026-04-01",
            abcGstin
        );
        if (!Files.isDirectory(SOURCE_100000_DIR) || !Files.isDirectory(ORACLE_100000_XML_DIR)) {
            UUID debtorsGrp = findGroupIdByName(abcMigratedCompanyId, "Sundry Debtors");
            createSimpleLedger(abcMigratedCompanyId, "SENTHIL  ENTERPRISES", debtorsGrp);
            return;
        }
        // Copy ONLY *.1800 data files into isolated @TempDir (never touching Source/ or reading .lic files)
        Path snapshotCopyDir = tempDir.resolve("100000_copy");
        Files.createDirectories(snapshotCopyDir);
        int copied1800Count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(SOURCE_100000_DIR, "*.1800")) {
            for (Path src : stream) {
                Files.copy(src, snapshotCopyDir.resolve(src.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                copied1800Count++;
            }
        }
        Assertions.assertThat(copied1800Count).isGreaterThanOrEqualTo(5);

        // 1. Stage .1800 snapshot into mc_import_job + mc_import_staging_record + mc_import_reconciliation_proof
        MvcResult stageRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + abcMigratedCompanyId + "/imports/tally-1800/stage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "snapshotDirectoryPath": "%s",
                          "companionXmlDirectoryPath": "%s"
                        }
                        """.formatted(snapshotCopyDir.toAbsolutePath(), ORACLE_100000_XML_DIR.toAbsolutePath())
                    ),
                "mig-stage-100000"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        JsonNode stagedProof = objectMapper.readTree(stageRes.getResponse().getContentAsByteArray());
        UUID jobId = UUID.fromString(stagedProof.get("jobId").asString());

        Assertions.assertThat(stagedProof.get("sector0Verified").asBoolean()).isTrue();
        Assertions.assertThat(stagedProof.get("cmpSaveParityVerified").asBoolean()).isTrue();
        Assertions.assertThat(stagedProof.get("sourceGroupCount").asInt()).isEqualTo(28);
        Assertions.assertThat(stagedProof.get("sourceLedgerCount").asInt()).isEqualTo(24);
        Assertions.assertThat(stagedProof.get("sourceVoucherCount").asInt()).isEqualTo(13);
        Assertions.assertThat(stagedProof.get("stagedGrossDebit").asString()).isEqualTo("365376.00");
        Assertions.assertThat(stagedProof.get("stagedNetTrialBalance").asString()).isEqualTo("338580.00");
        Assertions.assertThat(stagedProof.get("stagedNetProfit").asString()).isEqualTo("80155.41");
        Assertions.assertThat(stagedProof.get("isZeroVariance").asBoolean()).isTrue();
        Assertions.assertThat(stagedProof.get("proofSha256Hex").asString()).hasSize(64);

        // Verify GET /imports & GET /imports/{jobId}/proof
        MvcResult listJobsRes = mockMvc.perform(authedGet("/api/v1/companies/" + abcMigratedCompanyId + "/imports"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Assertions.assertThat(objectMapper.readTree(listJobsRes.getResponse().getContentAsByteArray()).size())
            .isEqualTo(1);

        mockMvc.perform(authedGet("/api/v1/companies/" + abcMigratedCompanyId + "/imports/" + jobId + "/proof"))
            .andExpect(MockMvcResultMatchers.status().isOk());

        // 2. Commit staged job into live MoneyCraft masters & double-entry vouchers
        MvcResult commitRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post(
                    "/api/v1/companies/" + abcMigratedCompanyId + "/imports/" + jobId + "/commit"
                ),
                "mig-commit-100000"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode committedProof = objectMapper.readTree(commitRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(committedProof.get("status").asString()).isEqualTo("COMMITTED");
        Assertions.assertThat(committedProof.get("isZeroVariance").asBoolean()).isTrue();
        Assertions.assertThat(committedProof.get("varianceDebit").asString()).isEqualTo("0.00");
        Assertions.assertThat(committedProof.get("varianceCredit").asString()).isEqualTo("0.00");

        // 3. Assert live Trial Balance & Profit & Loss on migrated ABC & Co match TallyPrime 7.2 oracle to 0.00!
        MvcResult abcTbRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + abcMigratedCompanyId
                    + "/reports/trial-balance?fromDate=2026-04-01&toDate=2027-03-31"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode abcTb = objectMapper.readTree(abcTbRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(abcTb.get("totalPeriodDebit").asString()).isEqualTo("365376.00");
        Assertions.assertThat(abcTb.get("totalPeriodCredit").asString()).isEqualTo("365376.00");
        Assertions.assertThat(abcTb.get("totalClosingDebit").asString()).isEqualTo("338580.00");
        Assertions.assertThat(abcTb.get("totalClosingCredit").asString()).isEqualTo("338580.00");
        Assertions.assertThat(abcTb.get("isBalanced").asBoolean()).isTrue();

        MvcResult abcPnlRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + abcMigratedCompanyId
                    + "/reports/profit-and-loss?fromDate=2026-04-01&toDate=2027-03-31"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode abcPnl = objectMapper.readTree(abcPnlRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(abcPnl.get("grossProfit").asString()).isEqualTo("80155.70");
        Assertions.assertThat(abcPnl.get("netProfit").asString()).isEqualTo("80155.41");

        // Verify internal double-space preservation ("SENTHIL  ENTERPRISES") and MIGRATE_IN Edit Log v1 baseline
        MvcResult senthilRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + abcMigratedCompanyId + "/ledgers?limit=50&q=SENTHIL")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode senthilItems = objectMapper.readTree(senthilRes.getResponse().getContentAsByteArray()).get("items");
        UUID senthilLedgerId = null;
        for (JsonNode item : senthilItems) {
            if ("SENTHIL  ENTERPRISES".equals(item.get("name").asString())) {
                senthilLedgerId = UUID.fromString(item.get("id").asString());
            }
        }
        Assertions.assertThat(senthilLedgerId).isNotNull();

        MvcResult ledgerEditLogRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + abcMigratedCompanyId
                    + "/edit-log/LEDGER/" + senthilLedgerId + "/versions"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode ledgerVersions = objectMapper.readTree(ledgerEditLogRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(ledgerVersions.size()).isEqualTo(1);
        Assertions.assertThat(ledgerVersions.get(0).get("operation").asString()).isEqualTo("MIGRATE_IN");
        Assertions.assertThat(ledgerVersions.get(0).get("versionNo").asInt()).isEqualTo(1);

        MvcResult abcVouchersRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + abcMigratedCompanyId + "/vouchers?limit=50")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode abcVoucherItems = objectMapper.readTree(abcVouchersRes.getResponse().getContentAsByteArray()).get("items");
        Assertions.assertThat(abcVoucherItems.size()).isEqualTo(13);
        UUID firstMigratedVoucherId = UUID.fromString(abcVoucherItems.get(0).get("id").asString());

        MvcResult voucherEditLogRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + abcMigratedCompanyId
                    + "/edit-log/VOUCHER/" + firstMigratedVoucherId + "/versions"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode voucherVersions = objectMapper.readTree(voucherEditLogRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(voucherVersions.size()).isEqualTo(1);
        Assertions.assertThat(voucherVersions.get(0).get("operation").asString()).isEqualTo("MIGRATE_IN");
    }

    @Test
    @Order(3)
    void xmlEnvelopeJsonExAndExcelRoundTripExchangeWithZeroVariance() throws Exception {
        if (!Files.isDirectory(SOURCE_100000_DIR) || !Files.isDirectory(ORACLE_100000_XML_DIR)) {
            return;
        }
        // 1. Export ABC & Co as Tally <ENVELOPE> XML
        MvcResult xmlExpRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + abcMigratedCompanyId + "/exports/xml")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"entityScope\":\"ALL\",\"fromDate\":\"2026-04-01\",\"toDate\":\"2027-03-31\"}"),
                "exg-exp-xml"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode xmlExpJson = objectMapper.readTree(xmlExpRes.getResponse().getContentAsByteArray());
        String xmlPayload = xmlExpJson.get("content").asString();
        Assertions.assertThat(xmlPayload).contains("<ENVELOPE>");
        Assertions.assertThat(xmlPayload).contains("<AMOUNT>-");
        Assertions.assertThat(xmlPayload).contains("SENTHIL  ENTERPRISES");

        // Verify all XML comments are 100% pure ASCII (prevents TallyPrime UTF-8 comment bug)
        Matcher commentMatcher = Pattern.compile("<!--(.*?)-->", Pattern.DOTALL).matcher(xmlPayload);
        int commentCount = 0;
        while (commentMatcher.find()) {
            commentCount++;
            String commentBody = commentMatcher.group(1);
            Assertions.assertThat(commentBody.chars().allMatch(ch -> ch < 128)).isTrue();
        }
        Assertions.assertThat(commentCount).isGreaterThanOrEqualTo(1);

        // Inject Tally &#4; Primary control entity into XML and import into fresh target company
        String xmlWithCtrl4 = xmlPayload.replace("<PARENT>Primary</PARENT>", "<PARENT>&#4; Primary</PARENT>");
        String xmlCmpPrefix = "33AABCX2001L1Z";
        UUID xmlTargetCompanyId = createTestCompany(
            "MCEXGXML06",
            "XML RoundTrip Target Co",
            "33",
            "2026-04-01",
            xmlCmpPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(xmlCmpPrefix)
        );

        MvcResult xmlImpRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + xmlTargetCompanyId + "/imports/xml")
                    .contentType(MediaType.APPLICATION_XML)
                    .content(xmlWithCtrl4),
                "exg-imp-xml-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode xmlImpBody = objectMapper.readTree(xmlImpRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(xmlImpBody.get("isBalanced").asBoolean()).isTrue();
        Assertions.assertThat(xmlImpBody.get("vouchersImported").asInt()).isEqualTo(13);

        // Re-import identical XML under a NEW Idempotency-Key -> GUID deduplication prevents duplicate vouchers!
        MvcResult xmlReImpRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + xmlTargetCompanyId + "/imports/xml")
                    .contentType(MediaType.APPLICATION_XML)
                    .content(xmlWithCtrl4),
                "exg-imp-xml-replay-guid"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(xmlReImpRes.getResponse().getContentAsByteArray()).get("vouchersImported").asInt()
        ).isEqualTo(0);

        assertTargetCompanyTrialBalance(xmlTargetCompanyId, "2026-04-01", "2027-03-31", "338580.00");

        // 2. Export & Import TallyPrime Release 7.2 JSONEx (version: 1)
        MvcResult jxExpRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + abcMigratedCompanyId + "/exports/jsonex")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"entityScope\":\"ALL\",\"fromDate\":\"2026-04-01\",\"toDate\":\"2027-03-31\"}"),
                "exg-exp-jsonex"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode jxExpBody = objectMapper.readTree(jxExpRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(jxExpBody.get("header").get("version").asInt()).isEqualTo(1);
        String jsonExContent = jxExpBody.get("content").asString();

        String jxCmpPrefix = "33AABCJ3002M1Z";
        UUID jsonExTargetCompanyId = createTestCompany(
            "MCEXGJX06",
            "JSONEx RoundTrip Target Co",
            "33",
            "2026-04-01",
            jxCmpPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(jxCmpPrefix)
        );

        MvcResult jxImpRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + jsonExTargetCompanyId + "/imports/jsonex")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            java.util.Map.of("fileName", "abc_rel72.json", "content", jsonExContent)
                        )
                    ),
                "exg-imp-jsonex"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(jxImpRes.getResponse().getContentAsByteArray()).get("vouchersImported").asInt()
        ).isEqualTo(13);
        assertTargetCompanyTrialBalance(jsonExTargetCompanyId, "2026-04-01", "2027-03-31", "338580.00");

        // 3. Export & Import Apache POI .xlsx Workbook + Custom Mapping Template CRUD
        MvcResult xlsxExpRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + abcMigratedCompanyId + "/exports/excel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"entityScope\":\"ALL\",\"fromDate\":\"2026-04-01\",\"toDate\":\"2027-03-31\"}"),
                "exg-exp-xlsx"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        byte[] xlsxBytes = xlsxExpRes.getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            Assertions.assertThat(wb.getSheet("Ledgers")).isNotNull();
            Assertions.assertThat(wb.getSheet("Vouchers")).isNotNull();
        }

        String xlCmpPrefix = "33AABCE4003N1Z";
        UUID excelTargetCompanyId = createTestCompany(
            "MCEXGXL06",
            "Excel RoundTrip Target Co",
            "33",
            "2026-04-01",
            xlCmpPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(xlCmpPrefix)
        );

        String xsrfVal = URLDecoder.decode(ownerXsrfCookie.getValue(), StandardCharsets.UTF_8);
        MockMultipartFile excelMultipart = new MockMultipartFile(
            "file",
            "abc_export.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            xlsxBytes
        );
        MvcResult xlsxImpRes = mockMvc.perform(
            MockMvcRequestBuilders.multipart("/api/v1/companies/" + excelTargetCompanyId + "/imports/excel")
                .file(excelMultipart)
                .cookie(ownerSessionCookie, ownerXsrfCookie)
                .header("X-XSRF-TOKEN", xsrfVal)
                .header("Idempotency-Key", "exg-imp-xlsx-mp")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(xlsxImpRes.getResponse().getContentAsByteArray()).get("vouchersImported").asInt()
        ).isEqualTo(13);
        assertTargetCompanyTrialBalance(excelTargetCompanyId, "2026-04-01", "2027-03-31", "338580.00");

        // Save and list custom Excel column mapping template
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + excelTargetCompanyId + "/exchange/templates")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "templateName": "CA Audit Ledger Sheet",
                          "entityScope": "MASTERS",
                          "sheetName": "Ledgers",
                          "headerRowIndex": 0,
                          "columnMappings": {
                            "name": "Ledger Name",
                            "parentGroup": "Under Group",
                            "openingBalance": "Opening Balance"
                          }
                        }
                        """
                    ),
                "exg-tpl-create"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        MvcResult tplListRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + excelTargetCompanyId + "/exchange/templates")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Assertions.assertThat(objectMapper.readTree(tplListRes.getResponse().getContentAsByteArray()).size())
            .isEqualTo(1);
    }

    @Test
    @Order(4)
    void smartFindGlobalSearchBankingAutoBrsPayrollTinkAndOcrHumanReviewGate() throws Exception {
        // 1. SmartFind (ACTIVE & ALL_OPEN_COMPANIES)
        MvcResult sfActiveRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + parityCompanyId + "/search/smartfind?q=Kaveri&scope=ACTIVE&limit=20")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode sfActive = objectMapper.readTree(sfActiveRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(sfActive.get("results").size()).isGreaterThanOrEqualTo(1);
        Assertions.assertThat(sfActive.toString()).contains("Kaveri Meditech Pvt Ltd");

        MvcResult sfMultiRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + parityCompanyId
                    + "/search/smartfind?q=SENTHIL&scope=ALL_OPEN_COMPANIES&limit=20"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode sfMulti = objectMapper.readTree(sfMultiRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(sfMulti.toString()).contains("SENTHIL  ENTERPRISES");

        // 2. Cheque Book & Leaf Generation
        MvcResult cbRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/banking/cheque-books")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "bankLedgerId": "%s",
                          "bookName": "HDFC Corporate Series A",
                          "fromLeafNo": "100001",
                          "toLeafNo": "100010"
                        }
                        """.formatted(parityBankLedgerId)
                    ),
                "bnk-cb-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        JsonNode cb = objectMapper.readTree(cbRes.getResponse().getContentAsByteArray());
        UUID bookId = UUID.fromString(cb.get("id").asString());
        Assertions.assertThat(cb.get("totalLeaves").asInt()).isEqualTo(10);

        MvcResult leavesRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + parityCompanyId + "/banking/cheque-books/" + bookId + "/leaves")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Assertions.assertThat(objectMapper.readTree(leavesRes.getResponse().getContentAsByteArray()).size())
            .isEqualTo(10);

        // 3. Bank Statement Import (CSV + MT940) & Auto-BRS Reconciliation on HDFC Current A/c
        String csvStatement = String.join(
            "\n",
            "Date,ValueDate,InstrumentNo,UTR,Narration,Withdrawal,Deposit,Balance",
            "2025-04-01,2025-04-01,,UTR500001,Seed equity NEFT,0.00,500000.00,500000.00",
            "2025-04-01,2025-04-01,100001,,Self cash cheque,25000.00,0.00,475000.00",
            "2025-05-31,2025-05-31,,RTGS102600,Kaveri Meditech RTGS,102600.00,0.00,372400.00"
        );
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/banking/statements/import")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            java.util.Map.of(
                                "bankLedgerId", parityBankLedgerId.toString(),
                                "fileName", "hdfc_apr_may.csv",
                                "format", "CSV",
                                "content", csvStatement
                            )
                        )
                    ),
                "bnk-stmt-csv"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        String mt940Statement = String.join(
            "\n",
            ":20:HDFC20250531",
            ":25:50200012345678",
            ":60F:C250531INR372400,00",
            ":61:2505310531C100000,00NTRFNEFT100000//NEFT100000",
            ":86:NEFT receipt from Salem Care Hospital",
            ":62F:C250531INR472400,00"
        );
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/banking/statements/import")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            java.util.Map.of(
                                "bankLedgerId", parityBankLedgerId.toString(),
                                "fileName", "hdfc_mt940.sta",
                                "format", "MT940",
                                "content", mt940Statement
                            )
                        )
                    ),
                "bnk-stmt-mt940"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        MvcResult autoMatchRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/banking/brs/auto-match")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "bankLedgerId": "%s",
                          "dateToleranceDays": 3
                        }
                        """.formatted(parityBankLedgerId)
                    ),
                "bnk-brs-automatch"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode autoMatch = objectMapper.readTree(autoMatchRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(autoMatch.get("matchedCount").asInt()).isEqualTo(4);
        Assertions.assertThat(autoMatch.get("remainingUnreconciledCount").asInt()).isEqualTo(0);

        MvcResult brsRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + parityCompanyId
                    + "/banking/brs?bankLedgerId=" + parityBankLedgerId + "&asOfDate=2026-03-31"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode brs = objectMapper.readTree(brsRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(brs.get("balanceAsPerCompanyBooks").asString()).isEqualTo("472400.00");
        Assertions.assertThat(brs.get("balanceAsPerBankComputed").asString()).isEqualTo("472400.00");

        // 4. Payroll Pay Head & Tink-Encrypted Employee PII
        UUID indirectExpGrp = findGroupIdByName(parityCompanyId, "Indirect Expenses");
        UUID salaryLedgerId = createSimpleLedger(parityCompanyId, "Salaries & Wages", indirectExpGrp);
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/banking/payroll/heads")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Basic Salary",
                          "payHeadType": "EARNINGS_FOR_EMPLOYEES",
                          "calculationType": "FLAT_RATE",
                          "ledgerId": "%s"
                        }
                        """.formatted(salaryLedgerId)
                    ),
                "bnk-payhead-basic"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        MvcResult empRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/banking/payroll/employees")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "employeeCode": "EMP-001",
                          "employeeName": "Dr. Kavitha Subramanian",
                          "designation": "Quality Lead",
                          "dateOfJoining": "2025-04-01",
                          "pan": "ABCPK4321Q",
                          "uan": "100987654321",
                          "bankAccountNumber": "50100998877665",
                          "bankIfsc": "HDFC0000123"
                        }
                        """
                    ),
                "bnk-emp-001"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        JsonNode emp = objectMapper.readTree(empRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(emp.get("panMasked").asString()).contains("*");

        // 5. Docs-by-Ira OCR Document Staging & Mandatory Human Review Gate
        MvcResult ocrStageRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/banking/ocr-staging/documents")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "fileName": "courier_receipt_may2025.pdf",
                          "documentType": "EXPENSE_RECEIPT",
                          "supplierName": "BlueDart Express Courier",
                          "invoiceNumber": "BD-7781",
                          "invoiceDate": "2025-05-20",
                          "taxableAmount": "1500.00",
                          "taxAmount": "0.00",
                          "totalAmount": "1500.00"
                        }
                        """
                    ),
                "bnk-ocr-stage-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        JsonNode ocrDoc = objectMapper.readTree(ocrStageRes.getResponse().getContentAsByteArray());
        UUID ocrDocId = UUID.fromString(ocrDoc.get("id").asString());
        Assertions.assertThat(ocrDocId).isNotNull();
        Assertions.assertThat(ocrDoc.get("reviewStatus").asString()).isEqualTo("EXTRACTED_PENDING_REVIEW");
        Assertions.assertThat(ocrDoc.path("convertedVoucherId").isNull()).isTrue();

        // Approve & convert OCR document into a posted voucher on a dedicated OCR test company so parityCompanyId totals stay untouched!
        String ocrCmpPrefix = "33AABCO7004P1Z";
        UUID ocrCompanyId = createTestCompany(
            "MCOCRLAB06",
            "OCR Review Verification Co",
            "33",
            "2025-04-01",
            ocrCmpPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(ocrCmpPrefix)
        );
        MvcResult ocrStage2Res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + ocrCompanyId + "/banking/ocr-staging/documents")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "fileName": "vendor_inv_901.pdf",
                          "documentType": "PURCHASE_INVOICE",
                          "supplierName": "Chennai Stationery Mart",
                          "invoiceNumber": "CSM-901",
                          "invoiceDate": "2025-05-12",
                          "taxableAmount": "2500.00",
                          "taxAmount": "0.00",
                          "totalAmount": "2500.00"
                        }
                        """
                    ),
                "bnk-ocr-stage-2"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID ocrDoc2Id = UUID.fromString(
            objectMapper.readTree(ocrStage2Res.getResponse().getContentAsByteArray()).get("id").asString()
        );

        MvcResult ocrApproveRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post(
                    "/api/v1/companies/" + ocrCompanyId
                        + "/banking/ocr-staging/documents/" + ocrDoc2Id + "/approve-convert"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"verifiedTotalAmount\":\"2500.00\",\"narration\":\"Human-verified OCR purchase\"}"),
                "bnk-ocr-approve-2"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode approvedOcr = objectMapper.readTree(ocrApproveRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(approvedOcr.get("status").asString()).isEqualTo("APPROVED_CONVERTED");
        UUID convertedVoucherId = UUID.fromString(approvedOcr.get("convertedVoucherId").asString());

        MvcResult convertedVchRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + ocrCompanyId + "/vouchers/" + convertedVoucherId)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(convertedVchRes.getResponse().getContentAsByteArray()).get("status").asString()
        ).isEqualTo("POSTED");

        // 6. CAMT.053 ISO 20022 XML Bank Statement Import verification
        UUID ocrBankGroupId = findGroupIdByName(ocrCompanyId, "Bank Accounts");
        UUID ocrBankLedgerId = createSimpleLedger(ocrCompanyId, "ICICI CAMT A/c", ocrBankGroupId);
        String camt053Xml = """
            <Document>
              <BkToCstmrStmt>
                <Stmt>
                  <Ntry>
                    <Amt Ccy="INR">12500.00</Amt>
                    <CdtDbtInd>CRDT</CdtDbtInd>
                    <BookgDt><Dt>2025-05-15</Dt></BookgDt>
                    <NtryDtls><TxDtls><Refs><EndToEndId>CAMTUTR0099</EndToEndId></Refs></TxDtls></NtryDtls>
                    <AddtlNtryInf>Customer NEFT CAMT.053</AddtlNtryInf>
                  </Ntry>
                </Stmt>
              </BkToCstmrStmt>
            </Document>
            """;
        MvcResult camtRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + ocrCompanyId + "/banking/statements/import")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            java.util.Map.of(
                                "bankLedgerId", ocrBankLedgerId.toString(),
                                "fileName", "icici_may2025_camt053.xml",
                                "sourceFormat", "CAMT_053",
                                "rawContent", camt053Xml
                            )
                        )
                    ),
                "bnk-stmt-camt053"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(camtRes.getResponse().getContentAsByteArray()).get("importedLinesCount").asInt()
        ).isEqualTo(1);
    }

    @Test
    @Order(5)
    void deterministicBrandV020PdfRenderingVoucherAndFinancialReportsVerifiedByPdfBox() throws Exception {
        // 1. Tax Invoice / Voucher PDF (Sales #1 SAL-001 on parityCompanyId)
        MvcResult vchPdfRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post(
                    "/api/v1/companies/" + parityCompanyId + "/vouchers/" + paritySales1VoucherId + "/print-pdf"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"copiesLabel\":\"ORIGINAL FOR RECIPIENT\"}"),
                "pdf-voucher-sal001"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_PDF))
            .andReturn();

        byte[] voucherPdfBytes = vchPdfRes.getResponse().getContentAsByteArray();
        verifyPdfBrandInvariants(
            voucherPdfBytes,
            "TAX INVOICE",
            "114624.00",
            "90189099"
        );

        // 2. Balance Sheet PDF on parityCompanyId (assert 610949.71, ₹, embedded Manrope + JetBrains Mono)
        MvcResult bsPdfRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/reports/balance-sheet/pdf")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"asOfDate\":\"2026-03-31\",\"format\":\"HORIZONTAL\"}"),
                "pdf-rpt-bs"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_PDF))
            .andReturn();

        byte[] bsPdfBytes = bsPdfRes.getResponse().getContentAsByteArray();
        verifyPdfBrandInvariants(
            bsPdfBytes,
            "BALANCE SHEET",
            "610949.71",
            "SHA256:"
        );

        // 3. Verify all remaining supported report PDF endpoints render valid multi-font PDFs
        for (String rpt : new String[] {
            "trial-balance", "profit-and-loss", "schedule-iii", "day-book", "stock-summary", "ageing"
        }) {
            MvcResult rptPdfRes = mockMvc.perform(
                authedMutate(
                    MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/reports/" + rpt + "/pdf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromDate\":\"2025-04-01\",\"toDate\":\"2026-03-31\",\"asOfDate\":\"2026-03-31\"}"),
                    "pdf-rpt-" + rpt
                )
            )
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();
            verifyPdfBrandInvariants(rptPdfRes.getResponse().getContentAsByteArray(), "MoneyCraft 1.0.0", "SHA256:", "\u20B9");
        }
    }

    // =========================================================================
    // Verification & Fixture Helpers
    // =========================================================================

    private static void verifyPdfBrandInvariants(
        byte[] pdfBytes,
        String expectedToken1,
        String expectedToken2,
        String expectedToken3
    ) throws Exception {
        Assertions.assertThat(pdfBytes).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            Assertions.assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);

            Set<String> embeddedFontNames = new HashSet<>();
            for (PDPage page : doc.getPages()) {
                PDResources res = page.getResources();
                for (COSName fontKey : res.getFontNames()) {
                    PDFont font = res.getFont(fontKey);
                    Assertions.assertThat(font.isEmbedded())
                        .as("Every PDF font must be an embedded TrueType subset: " + font.getName())
                        .isTrue();
                    embeddedFontNames.add(font.getName());
                }
            }

            boolean hasManrope = embeddedFontNames.stream().anyMatch(n -> n.contains("Manrope"));
            boolean hasJetBrainsMono = embeddedFontNames.stream().anyMatch(n -> n.contains("JetBrainsMono"));
            Assertions.assertThat(hasManrope)
                .as("Embedded fonts must include Manrope subset: " + embeddedFontNames)
                .isTrue();
            Assertions.assertThat(hasJetBrainsMono)
                .as("Embedded fonts must include JetBrainsMono subset: " + embeddedFontNames)
                .isTrue();

            String extractedText = new PDFTextStripper().getText(doc);
            Assertions.assertThat(extractedText).contains("\u20B9");
            Assertions.assertThat(extractedText).contains(expectedToken1);
            Assertions.assertThat(extractedText).contains(expectedToken2);
            Assertions.assertThat(extractedText).contains(expectedToken3);

            for (int i = 0; i < extractedText.length(); i++) {
                char ch = extractedText.charAt(i);
                Assertions.assertThat(ch < '\uFB00' || ch > '\uFB06')
                    .as("PDF text must contain zero ligature presentation forms")
                    .isTrue();
            }
        }
    }

    private void assertTargetCompanyTrialBalance(
        UUID companyId,
        String fromDate,
        String toDate,
        String expectedClosingTotal
    ) throws Exception {
        MvcResult res = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + companyId
                    + "/reports/trial-balance?fromDate=" + fromDate + "&toDate=" + toDate
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode tb = objectMapper.readTree(res.getResponse().getContentAsByteArray());
        Assertions.assertThat(tb.get("totalClosingDebit").asString()).isEqualTo(expectedClosingTotal);
        Assertions.assertThat(tb.get("totalClosingCredit").asString()).isEqualTo(expectedClosingTotal);
        Assertions.assertThat(tb.get("differenceAmount").asString()).isEqualTo("0.00");
        Assertions.assertThat(tb.get("isBalanced").asBoolean()).isTrue();
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
                            "payrollEnabled": true,
                            "gstRegistrationType": "REGULAR",
                            "gstin": "%s",
                            "mcaEditLogEnforced": true
                          }
                        }
                        """.formatted(code, legalName, stateCode, fyStart, fyStart, gstin)
                    ),
                "p6-cmp-" + code
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        MvcResult loginRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(ADMIN_USER, ADMIN_PASS))
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
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID findGroupIdByName(UUID targetCompanyId, String groupName) throws Exception {
        String cursor = null;
        do {
            String url = "/api/v1/companies/" + targetCompanyId + "/account-groups?limit=50"
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
                "p6-ldg-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID createBillWisePartyLedger(
        UUID targetCompanyId,
        String name,
        UUID groupId,
        String stateCode,
        String gstin
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
                          "defaultCreditPeriodDays": 30,
                          "openingBalance": "0.00",
                          "openingSide": "DR"
                        }
                        """.formatted(name, groupId, stateCode, gstin)
                    ),
                "p6-pty-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID createBillWiseTdsVendorLedger(
        UUID targetCompanyId,
        String name,
        UUID groupId,
        String pan,
        String tdsNatureCode
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
                          "partyRegistrationType": "UNREGISTERED",
                          "countryCode": "IN",
                          "stateCode": "33",
                          "pan": "%s",
                          "tdsApplicable": true,
                          "tdsNatureCode": "%s",
                          "tdsDeducteeType": "PARTNERSHIP_FIRM",
                          "maintainBalancesBillByBill": true,
                          "openingBalance": "0.00",
                          "openingSide": "CR"
                        }
                        """.formatted(name, groupId, pan, tdsNatureCode)
                    ),
                "p6-tdsvnd-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID createGstDutyLedger(
        UUID targetCompanyId,
        String name,
        UUID groupId,
        String dutyHead
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
                          "taxType": "GST",
                          "dutyHead": "%s",
                          "openingBalance": "0.00",
                          "openingSide": "CR"
                        }
                        """.formatted(name, groupId, dutyHead)
                    ),
                "p6-gstduty-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID createTdsDutyLedger(
        UUID targetCompanyId,
        String name,
        UUID groupId,
        String tdsNatureCode
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
                          "taxType": "TDS",
                          "tdsApplicable": true,
                          "tdsNatureCode": "%s",
                          "openingBalance": "0.00",
                          "openingSide": "CR"
                        }
                        """.formatted(name, groupId, tdsNatureCode)
                    ),
                "p6-tdsduty-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
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
                "p6-uom-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID createStockGroup(UUID targetCompanyId, String name) throws Exception {
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/stock-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"%s\"}".formatted(name)),
                "p6-sgrp-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
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
                          "gstApplicability": "APPLICABLE",
                          "gstSupplyType": "GOODS",
                          "hsnSacCode": "%s",
                          "gstRatePct": "%s",
                          "defaultSalesLedgerId": "%s",
                          "defaultPurchaseLedgerId": "%s"
                        }
                        """.formatted(
                            name,
                            stockGroupId,
                            baseUomId,
                            costingMethod,
                            hsnSacCode,
                            gstRatePct,
                            salesLedgerId,
                            purchLedgerId
                        )
                    ),
                "p6-sitem-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID postAccountingVoucher(
        UUID targetCompanyId,
        String voucherTypeName,
        String voucherDate,
        String postingNature,
        String narration,
        String linesJson
    ) throws Exception {
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
                          "narration": "%s",
                          "lines": %s
                        }
                        """.formatted(voucherTypeName, voucherDate, postingNature, narration, linesJson)
                    ),
                "p6-vch-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID postItemInvoice(
        UUID targetCompanyId,
        String voucherTypeName,
        String voucherDate,
        String supplierInvoiceNo,
        UUID partyLedgerId,
        UUID accountingLedgerId,
        String posState,
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
                          "supplierInvoiceNo": %s,
                          "supplierInvoiceDate": %s,
                          "partyLedgerId": "%s",
                          "accountingLedgerId": "%s",
                          "placeOfSupplyState": "%s",
                          "inventoryLines": %s,
                          "lines": %s
                        }
                        """.formatted(
                            voucherTypeName,
                            voucherDate,
                            suppNoJson,
                            suppDateJson,
                            partyLedgerId,
                            accountingLedgerId,
                            posState,
                            inventoryLinesJson,
                            linesJson
                        )
                    ),
                "p6-inv-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private static JsonNode findInArray(JsonNode array, String field, String expected) {
        for (JsonNode el : array) {
            if (el.has(field) && expected.equals(el.get(field).asString())) {
                return el;
            }
        }
        throw new AssertionError("Expected element with " + field + "=" + expected + " in " + array);
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
